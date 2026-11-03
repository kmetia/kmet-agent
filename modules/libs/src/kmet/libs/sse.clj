(ns kmet.libs.sse
  "Generic Server-Sent Events framing and stream-reading primitives. In the JS
   world this is the eventsource-parser npm package; kept in-house because no
   Babashka-compatible Clojure equivalent exists.

   Provider-specific event parsing and the per-provider stream processors live
   in kmet.ai.api.sse, which drives these primitives. parse-sse-line is the
   public wire-level helper extensions (e.g. the mcp-adapter) use."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.libs.concurrent :as concurrent]))

(defn parse-sse-line
  "Parse one SSE line. Returns [event-name data] — exactly one is non-nil
   (:event or :data), both nil for blank/comment lines. A field value drops
   at most one space after the colon (spec); any other whitespace is part
   of the value."
  [line]
  (let [value (fn [v] (if (str/starts-with? v " ") (subs v 1) v))]
    (cond
      (str/starts-with? line "event:") [(value (subs line 6)) nil]
      (str/starts-with? line "data:") [nil (value (subs line 5))]
      :else [nil nil])))

(defn append-data
  "Append one data: field value to BUF. The spec joins an event's data
   fields with a newline, so a payload split across several `data:` lines is
   reassembled exactly; the first value seeds the buffer."
  [buf data]
  (if (empty? buf)
    (or data "")
    (str buf "\n" data)))

(defn body->reader
  "Response :body to a java.io.Reader. io/reader wraps a user-implemented
   Reader in a delegating BufferedReader that drives its .read (and
   forwards .close), so both production InputStreams and proxy Readers
   (tests fail the read on demand) end up with .read/.readLine/.close."
  [body]
  (io/reader body))

(def ^:private time-unit-millis
  "The `MILLISECONDS` unit, resolved once at load. `valueOf` is the portable
   spelling: a static *field* read inside a function body is `Unable to resolve
   symbol` in an extension's SCI, while the static method resolves there and on
   every host."
  (java.util.concurrent.TimeUnit/valueOf "MILLISECONDS"))

(def ^:private idle-chunk-size
  "Units (bytes/chars) the daemon reads per queue handoff. The handoff is the
   point: the per-unit protocol parks the consumer and wakes it for every
   single byte, which on Jolt is ~100 µs of context switching per byte — so a
   1 KB/s stream burns a fifth of a core. A chunk costs one wake."
  8192)

(defn- make-chunk-idle-reader
  "The chunked core behind make-byte-idle-reader / make-char-idle-reader /
   make-idle-reader: a daemon thread blocking-reads up to IDLE-CHUNK-SIZE
   units into NEW-BUF buffers and hands whole chunks to the consumer through
   a queue. The consumer converts a unit with ELEM and waits for the next
   chunk under a FRESH idle deadline — the clock measures time between
   received chunks, undici bodyTimeout semantics at transport granularity.

   NEW-BUF — (fn [len]) -> a fresh buffer to read into
   FILL!   — (fn [buf len]) -> units read into buf, or throws; a negative
             count at the end of the stream. Reader/InputStream read with a
             positive length returns at least one unit or -1, never 0.
   ELEM    — (fn [buf i]) -> the int at index i (char code / unsigned byte)

   Returns [read stop thread] exactly as make-idle-reader documents."
  [new-buf fill! elem idle-ms signal]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        t (Thread.
           (fn []
             (try
               (loop []
                 (let [buf (new-buf idle-chunk-size)
                       n (try (fill! buf idle-chunk-size) (catch Exception e e))]
                   (if (instance? Exception n)
                     ;; A read failure (e.g. HTTP/2 RST_STREAM) is put on the
                     ;; queue like data so the caller reports the real error
                     ;; instead of stalling to the idle deadline.
                     (.put q n)
                     (if (neg? n)
                       (.put q :eof)
                       (do (.put q [buf n]) (recur))))))
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.start t)
    [(let [cur-buf (volatile! nil)
           cur-n (volatile! 0)
           cur-idx (volatile! 0)]
       (fn []
         (let [i (long @cur-idx)]
           (if (< i (long @cur-n))
             (do (vreset! cur-idx (inc i))
                 (elem @cur-buf i))
             ;; Buffered units drained — wait for the next chunk (or the
             ;; stream's end) under a fresh idle deadline, in 100 ms slices so
             ;; the cancel flag and deadline are re-checked while the stream is
             ;; quiet.
             (let [deadline (+ (concurrent/monotonic-ms) idle-ms)]
               (loop []
                 (let [v (.poll q 100 time-unit-millis)]
                   (cond
                     (instance? Exception v) v
                     (nil? v) (cond
                                (and signal @signal) :aborted
                                (>= (concurrent/monotonic-ms) deadline) :timeout
                                :else (recur))
                     (= :eof v) -1
                     :else (let [[buf n] v]
                             (vreset! cur-buf buf)
                             (vreset! cur-n n)
                             (vreset! cur-idx 1)
                             (elem buf 0))))))))))
     (fn [] (.interrupt t))
     t]))

(defn make-char-idle-reader
  "Idle-timeout int reader over a java.io.Reader: a daemon reads chunks of
   chars (READER.read(char[], int, int)), the consumer serves char codes.
   See make-idle-reader for the [read stop thread] contract."
  [rdr idle-ms signal]
  (make-chunk-idle-reader
   #(char-array %)
   (fn [buf len] (.read ^java.io.Reader rdr ^chars buf 0 len))
   (fn [buf i] (int (aget ^chars buf i)))
   idle-ms signal))

(defn make-byte-idle-reader
  "Idle-timeout int reader over an InputStream: a daemon reads chunks of
   bytes (INPUT-STREAM.read(byte[], int, int)), the consumer serves unsigned
   bytes (0-255). See make-idle-reader for the [read stop thread] contract."
  [is idle-ms signal]
  (make-chunk-idle-reader
   #(byte-array %)
   (fn [buf len] (.read ^java.io.InputStream is ^bytes buf 0 len))
   (fn [buf i] (bit-and (aget ^bytes buf i) 0xff))
   idle-ms signal))

(defn make-idle-reader
  "Idle-timeout int reader over a no-arg read fn (undici bodyTimeout
   semantics — the clock measures time between received values and resets on
   every value). A daemon thread performs the blocking reads and hands values
   to a queue; the caller polls with the idle deadline, so a stalled stream
   yields :timeout while clean EOF still yields -1 (EOF is only observable
   through a blocking read — ready()/available() stay 0 at EOF on
   java.net.http streams).

   read-fn — no-arg fn returning the next int (char or byte, -1 at EOF) or
   throwing. This is the single-unit wrapper over the chunked core; stream
   readers should use the typed constructors above (char/byte), which hand
   off whole read bursts instead of parking per byte.

   Returns [read stop thread]:
     read      — no-arg fn: next int, -1 at EOF, the read exception when the
                 underlying stream failed, :timeout on stall, :aborted when
                 the cancel signal fired mid-poll
     stop      — interrupts the daemon so a blocked read releases (required
                  before closing the reader, which deadlocks while a read is
                  in flight on java.net.http streams)
     thread    — the daemon thread, for join-before-close"
  [read-fn idle-ms signal]
  (make-chunk-idle-reader
   #(int-array %)
   (fn [^ints buf _len]
     (let [c (read-fn)]
       (if (neg? c) -1 (do (aset buf 0 c) 1))))
   (fn [^ints buf i] (aget buf i))
   idle-ms signal))

(defn- strip-trailing-cr
  "Drop a single trailing CR so the idle arm's .read loop matches .readLine
   on CRLF input: the loop splits on \\n only, so a kept CR would stop a
   blank separator line from reading empty and stall Anthropic/Responses
   event buffering. (The non-idle arm calls .readLine, which ends a line
   on \\r, \\n or \\r\\n itself.)"
  [s]
  (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))

(defn- read-line-from
  "Assemble one line from an idle-reader read fn. Returns the line string,
   nil at EOF, the read exception when the stream failed, or the reader's
   :timeout/:aborted sentinel."
  [read-char]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read-char)]
        (cond
          (instance? Exception c) c
          (or (= :timeout c) (= :aborted c)) c
          (neg? c) (when (pos? (.length sb)) (strip-trailing-cr (str sb))) ;; EOF mid-line
          (= c (int \newline)) (strip-trailing-cr (str sb))
          :else (do (.append sb (char c)) (recur)))))))

(defn- make-idle-line-reader
  "Line reader with a per-byte idle timeout (undici bodyTimeout semantics)
   over BODY (a java.io.InputStream or Reader). Returns
   {:read-line f :stop f :thread t :close f} — f produces lines, nil at EOF,
   the read exception when the stream failed, :timeout on stall, :aborted on
   cancel; stop releases a blocked read; close releases the underlying
   resource; thread is the daemon thread (nil when the idle timeout is
   disabled)."
  [body idle-ms signal]
  (if (and idle-ms (pos? idle-ms))
    (let [rdr (body->reader body)
          [read-char stop thread] (make-char-idle-reader rdr idle-ms signal)]
      {:read-line #(read-line-from read-char)
       :stop stop
       :thread thread
       :close #(.close ^java.io.Reader rdr)})
    ;; A real .readLine: io/reader's BufferedReader ends a line on \n, \r
    ;; or \r\n, so no char-assembly loop (and no CR strip) is needed when
    ;; the idle timeout is off.
    (let [rdr (body->reader body)]
      {:read-line #(try (.readLine rdr) (catch Exception e e))
       :stop (fn [])
       :thread nil
       :close #(.close ^java.io.Reader rdr)})))

(defn stream-loop
  "Shared driver for both stream processors. Reads lines via the idle-aware
   line reader and calls handle-line per line. BODY is a java.io.InputStream
   or Reader. On a stall the abort-fn (if any) kills the transport
   (curl) so the blocked read releases, then the error is reported via
   error-fn. Cleanup on every exit path: the daemon is interrupted and
   joined before the reader is closed — closing while a read is in flight
   deadlocks java.net.http streams, and process pipes (curl) need the
   abort-fn since interrupts don't unblock them.

   Returns :aborted when cancelled, :timeout when idle limit reached,
   :error when the underlying read failed (the exception is reported via
   error-fn), or :eof when the stream ended cleanly (nil read without
   cancel/timeout). The caller can distinguish clean EOF from premature
   termination."
  [body idle-timeout-ms signal abort-fn handle-line error-fn]
  (let [idle (make-idle-line-reader body idle-timeout-ms signal)]
    (try
      (loop []
        (let [line ((:read-line idle))]
          (cond
            (= :aborted line)
            (do ((:stop idle)) :aborted)
            (= :timeout line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn)
                :timeout)
            (nil? line) :eof
            (and signal @signal)
            (do ((:stop idle)) :aborted)
            ;; A transport read failure (RST_STREAM, connection reset, ...)
            ;; surfaces immediately — cancel wins if it raced with the error.
            ;; abort-fn kills a possibly-still-alive curl transport so
            ;; close!'s process deref doesn't block on it.
            (instance? Exception line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn line)
                :error)
            :else
            (do (handle-line line)
                (recur)))))
      (finally
        ((:stop idle))
        (when-let [t (:thread idle)]
          (.join t 2000))
        ;; close can fail on a transport that already errored — the error
        ;; path owns the resource, so it must not surface as a second event
        ;; behind the real error.
        (try ((:close idle)) (catch Exception _ nil))))))

(defn process-data-stream
  "Shared driver for SSE streams whose events arrive on data: lines (OpenAI
   chat-completions, Google streamGenerateContent, Mistral chat-completions):
   parses each payload via parse-fn and dispatches events to handler.
   terminal? marks the event that proves the stream completed (premature-end
   detection); end-message is reported when the stream ends without one.
   signal, idle-timeout-ms, abort-fn as in process-openai-stream."
  [response handler signal parse-fn terminal? end-message idle-timeout-ms abort-fn]
  (try
    (let [saw-terminal (atom false)
          end-reason (stream-loop (:body response) idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[_ data] (parse-sse-line line)]
                                      (when data
                                        (doseq [event (parse-fn data)]
                                          (when (terminal? event) (reset! saw-terminal true))
                                          (handler event)))))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-terminal) (not (and signal @signal)))
        (handler {:type :error :message end-message})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

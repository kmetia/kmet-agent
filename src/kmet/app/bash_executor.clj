(ns kmet.app.bash-executor
  "Bash command execution with streaming, truncation, and temp file spill.
   Port of pi's bash-executor.ts + OutputAccumulator."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.libs.process :as process]
            [kmet.debug :as debug]))

(def DEFAULT-MAX-LINES 2000)
(def DEFAULT-MAX-BYTES (* 50 1024))
(def MAX-ROLLING-BYTES (* DEFAULT-MAX-BYTES 2))
(def TEMP-FILE-PREFIX "kmet-bash-")
(def TEMP-FILE-SUFFIX ".log")

(def session-env-keys
  "Session metadata env vars injected per execution (see
   kmet.app.tools.bash/session-env). Stripped from the inherited environment
   before the current session's values are merged (pi: resolveSpawnContext
   deletes PI_SESSION_ID/PI_SESSION_FILE/PI_PROVIDER/PI_MODEL/
   PI_REASONING_LEVEL first), so a nested kmet's parent values never leak
   into a child command."
  ["KMET_SESSION_ID" "KMET_SESSION_FILE" "KMET_PROVIDER" "KMET_MODEL"
   "KMET_REASONING_LEVEL"])

(defonce ^:private shell-options
  ;; pi: SettingsManager getShellPath/getShellCommandPrefix — pi reads them
  ;; when the tool is created and in executeBash; kmet's built-in tool is a
  ;; static def, so the merged settings live here and execute-bash applies
  ;; them as defaults (explicit call-site options win).
  (atom {:shell-path nil :command-prefix nil}))

(defn set-shell-options!
  "Apply the shell settings to bash execution: :shell-path (pi: shellPath,
   a custom shell binary) and :command-prefix (pi: shellCommandPrefix, a line
   prepended to every command). Called by kmet.config/load-config — the
   single choke point every entry mode passes through."
  [{:keys [shell-path command-prefix]}]
  (reset! shell-options {:shell-path shell-path :command-prefix command-prefix}))

(defn- strip-session-env
  "Drop the session metadata vars from an inherited environment (pi:
   resolveSpawnContext's deletes before the current session's values are
   merged)."
  [env]
  (apply dissoc env session-env-keys))

;; ─── Format utilities (pi: formatSize) ──────────────────────────────────────

(defn format-size
  "Format bytes as human-readable size (pi: formatSize).
   Returns strings like '50B', '12.5KB', '1.5MB'."
  [bytes]
  (let [b (int bytes)]
    (cond
      (< b 1024) (str b "B")
      (< b (* 1024 1024)) (str (format "%.1f" (float (/ b 1024))) "KB")
      :else (str (format "%.1f" (float (/ b 1024 1024))) "MB"))))

(defn byte-length
  "UTF-8 byte length (pi: Buffer.byteLength)."
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- split-lines-for-counting
  "Pi: splitLinesForCounting — '' -> [], else split on \"\\n\" and drop a
   single trailing empty entry when the content ends with \"\\n\"."
  [content]
  (if (zero? (count content))
    []
    (let [parts (str/split content #"\n" -1)]
      (if (str/ends-with? content "\n")
        (pop (vec parts))
        (vec parts)))))

(defn- truncate-string-to-bytes-from-end
  "Pi: truncateStringToBytesFromEnd — tail slice that respects UTF-8 boundaries."
  [^String s max-bytes]
  (let [bs (.getBytes s "UTF-8")
        n (alength bs)]
    (if (<= n max-bytes)
      s
      (let [start (- n max-bytes)
            start (loop [i start]
                    (if (and (< i n)
                             (= (bit-and (bit-and (aget bs i) 0xFF) 0xC0) 0x80))
                      (recur (inc i))
                      i))
            sliced (java.util.Arrays/copyOfRange bs start n)]
        (String. sliced "UTF-8")))))

(def ^:private ANSI-PATTERN
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")
(defn- strip-ansi [s] (str/replace s ANSI-PATTERN ""))
(defn- sanitize-output
  "Pi: sanitizeBinaryOutput() — strips control chars (except \\t \\n \\r)
   and Unicode format characters (U+FFF9-U+FFFB)."
  [s]
  (-> s strip-ansi
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      ;; Pi: filter out control chars + Unicode format characters
      (str/replace #"[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]" "")))

(defn truncate-head
  "Pi: truncateHead — keep first N lines/bytes, never partial lines."
  [content & {:keys [max-lines max-bytes]
              :or {max-lines 2000 max-bytes (* 50 1024)}}]
  (let [total-bytes (byte-length content)
        lines (split-lines-for-counting content)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil
       :total-lines total-lines :total-bytes total-bytes
       :output-lines total-lines :output-bytes total-bytes
       :last-line-partial false :first-line-exceeds-limit false
       :max-lines max-lines :max-bytes max-bytes}
      (let [first-line-bytes (if (seq lines) (byte-length (first lines)) 0)]
        (if (> first-line-bytes max-bytes)
          {:content "" :truncated true :truncated-by :bytes
           :total-lines total-lines :total-bytes total-bytes
           :output-lines 0 :output-bytes 0
           :last-line-partial false :first-line-exceeds-limit true
           :max-lines max-lines :max-bytes max-bytes}
          (let [[out-lines out-bytes truncated-by]
                (loop [i 0 acc [] b 0 tb :lines]
                  (if (or (>= i (count lines)) (>= (count acc) max-lines))
                    [acc b tb]
                    (let [line (nth lines i)
                          lb (+ (byte-length line) (if (pos? (count acc)) 1 0))]
                      (if (> (+ b lb) max-bytes)
                        [acc b :bytes]
                        (recur (inc i) (conj acc line) (+ b lb) tb)))))
                truncated-by (if (and (= (count out-lines) max-lines)
                                      (<= out-bytes max-bytes)
                                      (< (count out-lines) total-lines))
                               :lines truncated-by)
                ;; pi keeps :lines when the loop filled max-lines without
                ;; hitting the byte limit; the accumulator above already uses
                ;; :lines as default — keep it unless we broke for bytes.
                out-content (str/join "\n" out-lines)
                out-bytes (if (seq out-lines) (byte-length out-content) 0)]
            {:content out-content :truncated true :truncated-by truncated-by
             :total-lines total-lines :total-bytes total-bytes
             :output-lines (count out-lines) :output-bytes out-bytes
             :last-line-partial false :first-line-exceeds-limit false
             :max-lines max-lines :max-bytes max-bytes}))))))

(defn truncate-tail
  "Pi: truncateTail — keep last N lines/bytes, may return partial first line."
  [content & {:keys [max-lines max-bytes]
              :or {max-lines 2000 max-bytes (* 50 1024)}}]
  (let [total-bytes (byte-length content)
        lines (split-lines-for-counting content)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil
       :total-lines total-lines :total-bytes total-bytes
       :output-lines total-lines :output-bytes total-bytes
       :last-line-partial false :first-line-exceeds-limit false
       :max-lines max-lines :max-bytes max-bytes}
      (let [[out-lines out-bytes last-partial truncated-by]
            (loop [i (dec (count lines)) acc [] b 0 lp false tb :lines]
              (if (or (< i 0) (>= (count acc) max-lines))
                [(vec (reverse acc)) b lp tb]
                (let [line (nth lines i)
                      lb (+ (byte-length line) (if (seq acc) 1 0))]
                  (if (> (+ b lb) max-bytes)
                    (if (empty? acc)
                      (let [t (truncate-string-to-bytes-from-end line max-bytes)]
                        [[t] (byte-length t) true :bytes])
                      [(vec (reverse acc)) b lp :bytes])
                    (recur (dec i) (conj acc line) (+ b lb) lp tb)))))
            truncated-by (if (and (= (count out-lines) max-lines)
                                  (<= out-bytes max-bytes)
                                  (< (count out-lines) total-lines))
                           :lines truncated-by)
            out-content (str/join "\n" out-lines)
            out-bytes (if (seq out-lines) (byte-length out-content) 0)]
        {:content out-content :truncated true :truncated-by truncated-by
         :total-lines total-lines :total-bytes total-bytes
         :output-lines (count out-lines) :output-bytes out-bytes
         :last-line-partial last-partial :first-line-exceeds-limit false
         :max-lines max-lines :max-bytes max-bytes}))))

(defn- create-temp-file []
  (let [tmp-dir (or (System/getenv "TMPDIR")
                    (System/getProperty "java.io.tmpdir"))
        tmp (fs/create-temp-file {:prefix TEMP-FILE-PREFIX
                                  :suffix TEMP-FILE-SUFFIX
                                  :dir tmp-dir})]
    (fs/delete-on-exit tmp)
    (str (fs/canonicalize tmp))))

(defn- find-bash-on-path []
  (try
    (let [cmd (if process/windows-os?
                ["cmd.exe" "/c" "where bash.exe"]
                ["sh" "-c" "command -v bash"])
          ;; Same class as the main spawn: this probe runs inside the TUI
          ;; process, so it must never inherit the TTY (a failed `where`
          ;; would garble the screen; stdin is never read but redirecting
          ;; it keeps the invariant). Note: babashka has no `:ignore` —
          ;; stderr must be `:discard` (unread pipe would block on output).
          result @(proc/process cmd {:out :string :err :discard
                                     :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
          output (str/trim (:out result))
          ;; Pi: where can return non-existent paths on Windows — verify the file exists
          first-match (first (str/split-lines output))]
      (when (and first-match
                 (or (not process/windows-os?) (fs/exists? first-match))
                 (seq (str/trim first-match)))
        (str/trim first-match)))
    (catch Exception _ nil)))

(defn- resolve-shell []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (find-bash-on-path)
      (when process/windows-os?
        (some (fn [env-var]
                (when-let [pf (System/getenv env-var)]
                  (let [path (str pf "\\Git\\bin\\bash.exe")]
                    (when (fs/exists? path) path))))
              ["ProgramFiles" "ProgramFiles(x86)"]))
      (if process/windows-os? "cmd.exe" "sh")))

(defn create-default-ops [& {:keys [shell-path]}]
  ;; Pi: throw early if custom shellPath is specified but not found
  (when (and shell-path (not (fs/exists? shell-path)))
    (throw (ex-info (str "Custom shell path not found: " shell-path)
                    {:shell-path shell-path})))
  (let [shell (or shell-path (resolve-shell))]
    (fn [{:keys [command cwd on-data signal timeout env]}]
      (let [_ (when-not (fs/exists? cwd)
                (throw (ex-info (str "Working directory does not exist: " cwd) {:cwd cwd})))
            ;; Pi: getShellConfig resolves shell + args per platform
            use-stdin? (and process/windows-os?
                            (re-find #"(?i)windows\\system32\\bash\.exe"
                                     (str/replace shell "/" "\\")))
            shell-args (cond
                         (str/includes? shell "cmd") [shell "/c" command]
                         use-stdin? [shell "-s"]
                         ;; setsid makes the sh its own process-group leader so
                         ;; kill-process-tree! can group-kill it — catches bg
                         ;; jobs mksh reparents outside the ppid tree.
                         :else (if-let [setsid @process/setsid-path]
                                 [setsid shell "-c" command]
                                 [shell "-c" command]))
            proc-opts {:dir cwd :err :pipe :out :pipe :env env
                       ;; Pi: stdio [ignore, pipe, pipe] — stdin is a
                       ;; NUL//dev/null redirect so a command that reads
                       ;; stdin (e.g. bare `cat`) hits EOF instead of
                       ;; inheriting the TTY and deadlocking the TUI. The
                       ;; WSL `bash -s` transport needs a real pipe instead:
                       ;; it writes the command to stdin, then closes it.
                       :in (if use-stdin?
                             :pipe
                             (fs/file (if process/windows-os? "NUL" "/dev/null")))}
            p (proc/process shell-args proc-opts)
            ;; Pi: write command to stdin for WSL -s transport, then close
            ;; it — the EOF pi's stdio `ignore` gives the other transport.
            _ (when (and use-stdin? (:in p))
                (try (spit (:in p) command) (catch Exception _ nil))
                (try (.close (:in p)) (catch Exception _ nil)))
            pid (try (-> p :proc .pid) (catch Exception _ nil))
            _ (when pid (process/track-pid! pid))
            ;; Pi: timeout handled manually (setTimeout + killProcessTree) —
            ;; babashka.process's :timeout reports exit 0 on kill, so a
            ;; timed-out command wouldn't be distinguishable from success.
            timed-out (atom false)
            ;; `done` is shared by both watchers below: it becomes true once
            ;; the process has exited, so each watcher can stop itself instead
            ;; of sleeping out its full window (future-cancel can't stop a
            ;; running future). Binding it here, before the watchers, keeps
            ;; the loop bodies able to see it.
            done (atom false)
            _ (when (and (number? timeout) (pos? timeout) pid)
                (let [deadline (+ (System/currentTimeMillis)
                                  (* timeout 1000))]
                  (future
                    (loop []
                      (when (and (not @done)
                                 (< (System/currentTimeMillis) deadline))
                        (Thread/sleep 100)
                        (recur)))
                    (when-not @done
                      (reset! timed-out true)
                      (process/kill-process-tree! pid)))))
            read-stream (fn [stream tag]
                          (let [buf (byte-array 8192)]
                            (loop [] (let [n (.read stream buf)]
                                       (when (pos? n) (on-data buf 0 n tag) (recur))))))
            out-future (future (try (read-stream (:out p) :stdout)
                                    (catch Exception e (debug/log "ops stdout: " e))))
            err-future (when-let [err-stream (:err p)]
                         (future (try (read-stream err-stream :stderr)
                                      (catch Exception e (debug/log "ops stderr: " e)))))
            ;; Pi: cancel signal — a poller that kills the process tree when the
            ;; signal fires mid-run. `done` lets the poller exit on normal
            ;; completion; without it the future spins until the next cancel
            ;; (thread leak per bash call). future-cancel can't stop an
            ;; already-running loop, so the flag is the exit mechanism.
            _ (when (and signal pid)
                (future (loop []
                          (when-not (or @signal @done)
                            (Thread/sleep 200)
                            (recur)))
                        (when @signal (process/kill-process-tree! pid))))
            ;; Wait for the process here, in binding order, so the watchers
            ;; above are already polling while we block. The watchers kill the
            ;; tree on timeout/cancel, so deref returns on every path — and
            ;; `done` below is only reset after this, which is what lets the
            ;; signal poller keep killing while the process is still running.
            result (try (deref p) (catch Exception _ nil))
            exit-code (:exit result)]
          ;; done stops both watchers (signal + timeout) within their next
          ;; sleep cycle. Set only after the process has exited; resetting
          ;; earlier would tell the watchers we are done while the process is
          ;; still running, so cancel/timeout would never kill it.
        (reset! done true)
        (when pid (process/untrack-pid! pid))
        (cond
              ;; Pi: after the process exits, re-check abort/timeout — the kill
              ;; itself returns normally (SIGKILL exit code), so cancelled/timeout
              ;; must be inferred from the signal/flag, not the exit code.
          @timed-out (throw (ex-info (str "timeout:" timeout) {}))
          (and signal @signal) (throw (ex-info "aborted" {}))
          :else {:exit-code exit-code
                 :cleanup (fn []
                            ;; Pi: waitForChildProcess finalize. The process
                            ;; exiting doesn't mean all its output was read: up
                            ;; to a pipe buffer of data can still be in flight,
                            ;; so the readers must be allowed to drain it first.
                            ;; Only close the streams when a reader is genuinely
                            ;; stuck (a detached descendant holding the pipe
                            ;; open) — closing early truncates the tail. Java
                            ;; process-pipe reads can't be unblocked (close and
                            ;; interrupt are no-ops on a blocked
                            ;; FileInputStream.read), so the drain is a bounded
                            ;; settle: readers that already EOF'd join
                            ;; instantly, while a reader blocked on a pipe held
                            ;; open by a detached descendant is abandoned after
                            ;; the grace period and the stream is closed to
                            ;; release it (it terminates on its own when the
                            ;; descendant exits). Both readers share ONE grace
                            ;; deadline — draining them sequentially would
                            ;; double (or worse) the bounded return time for
                            ;; commands with a detached descendant holding both
                            ;; pipes.
                            (let [deadline (+ (System/currentTimeMillis) 2000)]
                              (doseq [f [out-future err-future]]
                                (when f
                                  (try (deref f (max 0 (- deadline (System/currentTimeMillis))) nil)
                                       (catch Exception _ nil)))))
                            (doseq [stream [(:out p) (:err p)]]
                              (when stream
                                (try (.close stream) (catch Exception _ nil))))
                            (doseq [f [out-future err-future]]
                              (when f
                                (try (deref f 250 nil) (catch Exception _ nil)))))})))))

(defn execute-bash
  [{:keys [command cwd env on-chunk signal timeout spawn-hook
           operations shell-path command-prefix max-lines max-bytes]
    :or {cwd (System/getProperty "user.dir")
         max-lines DEFAULT-MAX-LINES
         max-bytes DEFAULT-MAX-BYTES}}]
  (let [;; Shell settings applied as defaults (pi: shellPath /
        ;; shellCommandPrefix read at tool creation and in executeBash) —
        ;; explicit call-site options win.
        {default-shell-path :shell-path default-command-prefix :command-prefix}
        @shell-options
        shell-path (or shell-path default-shell-path)
        command-prefix (or command-prefix default-command-prefix)
        ;; Resolve command with prefix (pi: commandPrefix)
        resolved-command (if command-prefix
                           (str command-prefix "\n" command)
                           command)
        ;; Use provided operations or default (pi: BashOperations)
        exec-ops (or operations (create-default-ops :shell-path shell-path))

        raw-chunks (atom [])
        tail-buf (atom [])
        tail-bytes (atom 0)
        total-decoded-bytes (atom 0)
        tail-starts-at-line-boundary (atom true)
        temp-file-path (atom nil)
        temp-file-stream (atom nil)

        handle-text
        (fn [text]
          (let [bytes (byte-length text)]
            (swap! total-decoded-bytes + bytes)
            (swap! tail-buf conj text)
            (swap! tail-bytes + bytes)
            (when (> @tail-bytes MAX-ROLLING-BYTES)
              (loop []
                (when (and (> (count @tail-buf) 1)
                           (> @tail-bytes MAX-ROLLING-BYTES))
                  (let [removed (first @tail-buf)]
                    (swap! tail-buf subvec 1)
                    (swap! tail-bytes - (byte-length removed))
                    (when (not (str/ends-with? removed "\n"))
                      (reset! tail-starts-at-line-boundary false)))
                  (recur))))
            (when on-chunk
              (try (on-chunk text)
                   (catch Exception e
                     (debug/log "bash chunk callback: " e))))))

        ;; Pi: streaming UTF-8 decoder (TextDecoder with {stream: true}) per
        ;; stream: a CharsetDecoder with REPLACE fed that stream's chunks. A
        ;; multi-byte sequence split across reads stays in the decoder's
        ;; input buffer (UNDERFLOW) and rides into that stream's next chunk;
        ;; a sequence stranded at EOF decodes as U+FFFD. stdout and stderr
        ;; are independent streams — a partial sequence at the end of one's
        ;; chunk never combines with the other's bytes.
        make-decode-state
        (fn []
          (let [decoder (doto (.newDecoder (java.nio.charset.Charset/forName "UTF-8"))
                          (.onMalformedInput java.nio.charset.CodingErrorAction/REPLACE)
                          (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPLACE))
                ;; Capacity 8192 (the read-stream buffer) + 3: the carry
                ;; after .compact is at most a 3-byte partial sequence.
                decode-in (java.nio.ByteBuffer/allocate (+ 8192 3))
                decode-out (java.nio.CharBuffer/allocate 8192)
                append-out!
                (fn [^StringBuilder sb]
                  (.flip decode-out)
                  (.append sb decode-out)
                  (.clear decode-out))
                decode-into!
                (fn [sb end?]
                  (loop []
                    (let [res (.decode decoder decode-in decode-out end?)]
                      (when (.isError res)
                        (throw (ex-info "UTF-8 decode error" {:result (str res)})))
                      (append-out! sb)
                      (when (.isOverflow res) (recur)))))]
            {:chunk
             (fn [raw-bytes offset len]
               (when (pos? len)
                 (.put decode-in raw-bytes offset len))
               (.flip decode-in)
               (let [sb (StringBuilder.)]
                 (decode-into! sb false)
                 (.compact decode-in)
                 (let [s (str sb)]
                   (when (pos? (count s)) s))))
             :finish
             (fn []
               (.flip decode-in)
               (let [sb (StringBuilder.)]
                 (decode-into! sb true)
                 (loop []
                   (let [res (.flush decoder decode-out)]
                     (when (.isError res)
                       (throw (ex-info "UTF-8 flush error" {:result (str res)})))
                     (append-out! sb)
                     (when (.isOverflow res) (recur))))
                 (let [s (str sb)]
                   (when (pos? (count s)) s))))}))

        decode-states {:stdout (make-decode-state)
                       :stderr (make-decode-state)}

        handle-raw-bytes
        (fn handle-raw-bytes
          ([raw-bytes offset len] (handle-raw-bytes raw-bytes offset len :stdout))
          ([raw-bytes offset len stream]
           (if (or @temp-file-stream @temp-file-path
                   (> @total-decoded-bytes max-bytes))
             (do
               (when (nil? @temp-file-path)
                 (let [path (create-temp-file)
                       output-stream (java.io.FileOutputStream. path)]
                   (reset! temp-file-path path)
                   (reset! temp-file-stream output-stream)
                   (doseq [[data start end] @raw-chunks]
                     (.write output-stream data start (- end start)))
                   (.write output-stream raw-bytes offset len)
                   (.flush output-stream)))
               (when @temp-file-stream
                 (.write @temp-file-stream raw-bytes offset len)
                 (.flush @temp-file-stream)))
             (let [copy (java.util.Arrays/copyOfRange raw-bytes offset (+ offset len))]
               (swap! raw-chunks conj [copy 0 (alength copy)])))
           ;; Pi: streaming decode — handles multi-byte sequences split across chunks
           (when-let [decoded ((:chunk (get decode-states stream :stdout)) raw-bytes offset len)]
             (let [clean (sanitize-output decoded)]
               (when (seq clean)
                 (handle-text clean))))))

        finalize
        (fn []
          (when @temp-file-stream
            (try (.close @temp-file-stream) (catch Exception _ nil))
            (reset! temp-file-stream nil))
          ;; Pi: flush remaining bytes from each stream's decoder (end-of-input)
          (doseq [state [(:stdout decode-states) (:stderr decode-states)]
                  :let [flushed ((:finish state))]
                  :when flushed]
            (let [clean (sanitize-output flushed)]
              (when (seq clean)
                (handle-text clean))))
          (let [raw-output (apply str @tail-buf)
                clean-output (if @tail-starts-at-line-boundary
                               raw-output
                               (let [first-newline (str/index-of raw-output "\n")]
                                 (if first-newline
                                   (subs raw-output (inc first-newline))
                                   raw-output)))
                truncation (truncate-tail clean-output
                                          :max-lines max-lines
                                          :max-bytes max-bytes)
                truncated (:truncated truncation)
                content (:content truncation)]
            (when (and truncated (nil? @temp-file-path))
              (let [path (create-temp-file)]
                (reset! temp-file-path path)
                (spit path clean-output)))
            {:output content
             :exit-code nil
             :cancelled false
             :truncated truncated
             :truncation truncation
             :full-output-path @temp-file-path}))]

    (try
      (let [base-env (strip-session-env (into {} (System/getenv)))
            merged-env (if env (merge base-env env) base-env)
            {:keys [command cwd env]}
            (if spawn-hook
              (spawn-hook {:command resolved-command :cwd cwd :env merged-env})
              {:command resolved-command :cwd cwd :env merged-env})
            ops-result (exec-ops
                        {:command command
                         :cwd cwd
                         :on-data handle-raw-bytes
                         :signal signal
                         :timeout timeout
                         :env env})
            exit-code (:exit-code ops-result)]
        ;; Grace polling for pending stream data after operations complete
        (loop [last-bytes @tail-bytes
               deadline (+ (System/currentTimeMillis) 100)]
          (when (< (System/currentTimeMillis) deadline)
            (let [current-bytes @tail-bytes]
              (if (> current-bytes last-bytes)
                (recur current-bytes (+ (System/currentTimeMillis) 100))
                (do (Thread/sleep 10)
                    (recur last-bytes deadline))))))
        ;; Pi: waitForChildProcess finalize — once the output has gone idle,
        ;; destroy the streams so a detached descendant holding the pipe open
        ;; can't keep the reader thread alive (pi destroys child.stdout/stderr).
        (when-let [cleanup (:cleanup ops-result)]
          (try (cleanup) (catch Exception _ nil)))
        (assoc (finalize) :exit-code exit-code))

      (catch Exception e
        (debug/log "bash execute error: " e)
        (let [raw-output (apply str @tail-buf)
              clean-output (if @tail-starts-at-line-boundary
                             raw-output
                             (let [first-newline (str/index-of raw-output "\n")]
                               (if first-newline
                                 (subs raw-output (inc first-newline))
                                 raw-output)))
              truncation (truncate-tail clean-output
                                        :max-lines max-lines
                                        :max-bytes max-bytes)
              content (:content truncation)
              truncated (:truncated truncation)
              ;; Pi: persistIfTruncated — save the full output whenever truncated
              _ (when (and truncated (nil? @temp-file-path))
                  (let [path (create-temp-file)]
                    (reset! temp-file-path path)
                    (spit path clean-output)))]
          (cond
            (and signal @signal)
            {:output content :exit-code nil :cancelled true
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}
            (str/includes? (str (ex-message e)) "timeout")
            {:output content :exit-code nil :cancelled false :timed-out true
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}
            :else
            {:output (str content "\n\nError: " (ex-message e))
             :exit-code nil :cancelled false
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}))))))

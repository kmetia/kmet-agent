(ns kmet.app.tools.script
  "The `script` tool — model-written Clojure in a per-call SCI sandbox with
   kmet's tools bridged in (design: script.md).

   Lifecycle: every call forks a fresh SCI context from a base cached per
   [registry-generation enabled-tool-set] and discards it on return. The base
   carries the capability namespaces and the surface's discovery fns
   (tools/list, tools/describe); the fork carries the cwd-dependent
   capabilities and the per-call tools bridge (async promises over
   execute-tool, drained by a shared bounded worker pool). The script runs on
   a daemon thread whose SCI :interrupt-fn aborts interpreted code on the run
   signal or the deadline; *out*/*err* are captured to bounded temp files,
   streamed through on-update, and assembled into the result (tools' 50 KiB /
   2000-line convention, tail truncation)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.invoke :as invoke]
            [kmet.app.tools.util :as tool-util]
            [kmet.debug :as debug]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.host :as host]
            [kmet.libs.process :as kprocess]
            [kmet.loader.core :as loader]
            [kmet.loader.sci-loader :as sci-loader]
            [sci.core :as sci]))

;; ─── Constants ────────────────────────────────────────────────────────────

(def default-timeout-ms 30000)

(defn- ms->sec
  "Milliseconds as seconds — whole numbers stay integers (30000 → 30), the
   rest a double (300 → 0.3), so the timeout reported in a result reads like
   the one that was asked for."
  [ms]
  (let [q (quot ms 1000)]
    (if (= ms (* q 1000)) q (/ ms 1000.0))))

(def ^:private max-output-bytes bash-exec/DEFAULT-MAX-BYTES)
(def ^:private max-output-lines bash-exec/DEFAULT-MAX-LINES)
(def ^:private max-capture-bytes (* 16 1024 1024))
(def ^:private max-tail-bytes (* 128 1024))
;; Bound on printed collections (the script's own printing and the return
;; value). Generous on purpose: it exists to stop an unbounded seq from
;; printing forever past the deadline, not to second-guess a script's pr-str.
(def ^:private print-length-limit 100000)
(def ^:private print-level-limit 30)
(def ^:private update-throttle-ms 100)
(def ^:private drain-timeout-ms 2000)
(def ^:private interrupt-grace-ms 1500)
(def ^:private error-preview-chars 300)
(def ^:private error-preview-lines 3)
(def ^:private base-cache-size 4)
(def ^:private excluded-tool-names #{"script"})

(def ^:private alias-prelude
  "The aliases every script gets without a require — the standard Clojure and
   babashka vocabulary. Evaluated once per fork, before the user code, as a
   separate eval so the script's own line numbers stay intact; `require`
   resolves the namespaces the context was built with."
  (str "(require '[babashka.fs :as fs]"
       " '[clojure.string :as str]"
       " '[clojure.set :as set]"
       " '[clojure.edn :as edn]"
       " '[clojure.walk :as walk]"
       " '[kmet.libs.json :as json]"
       " '[babashka.process :as p])"))

;; ─── Run-level context ────────────────────────────────────────────────────

(def ^:dynamic *enabled-tools-fn*
  "0-arg fn returning the run's enabled tool-name set (nil = all), bound by
   kmet.app.loop next to bash-tool/*cancel-signal*. It is the session half of
   the base-cache key: a script's callable surface is the registry filtered
   to this set. nil outside the loop's run paths (extension code calling
   execute-tool directly) — then every registry tool is callable."
  nil)

(def ^:dynamic *tool-hooks*
  "Bound by kmet.app.loop next to *enabled-tools-fn*: the run's agent-level
   tool hooks — the same fns the loop's own batches call:
     :before (fn [{:keys [tool-name args tool-call-id assistant-message]}])
              → nil | {:block true :reason …} | {:args rewritten}
     :after  (fn [{:keys [tool-name args result is-error]}])
              → nil | {:content … :is-error …}
   nil = no hooks (extension code calling the tool outside a loop run).
   A scripted inner call carries a synthetic :tool-call-id and the turn's
   :assistant-message (from *assistant-message*), and the hook's :terminate
   hint is ignored (there is no batch)."
  nil)

(def ^:dynamic *assistant-message*
  "The assistant message whose tool-call batch is running — bound by
   kmet.app.loop around execute-tool-calls! so a script's inner calls carry
   the same :assistant-message in their hook payload as the outer script
   call. nil outside the loop (extension code calling the tool directly);
   the key is then omitted from the hook payload."
  nil)

;; ─── Small helpers ────────────────────────────────────────────────────────

(defn- byte-length [s] (alength (.getBytes (str s) "UTF-8")))

(defn- text-lines
  ;; String.split is the fast path on purpose: this runs per reader block on
  ;; the babashka interpreter, where a per-char predicate scan costs ~2ms per
  ;; 8KiB block and makes the readers fall behind a fast writer.
  [s]
  (dec (alength (.split (str s) "\n" -1))))

(defn- preview
  "A short rendering of a failing tool result's content for the call trace."
  [content]
  (let [s (str content)
        lines (str/split-lines s)
        head (str/join "\n" (take error-preview-lines lines))
        head (if (> (count lines) error-preview-lines) (str head "…") head)]
    (if (> (count head) error-preview-chars)
      (str (subs head 0 error-preview-chars) "…")
      head)))

(defn- temp-root []
  (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn- normalize-timeout
  "The effective deadline in ms from TIMEOUT seconds (bash's unit). Absent,
   ≤ 0 or non-numeric → the 30 s default. Fractional seconds work (0.5)."
  [v]
  (let [secs (try
               (double (cond
                         (number? v) v
                         (string? v) (Double/parseDouble (str/trim v))
                         :else nil))
               (catch Exception _ nil))]
    (if (and secs (pos? secs))
      (long (* 1000.0 secs))
      default-timeout-ms)))

(defn- combined-signal
  "The cancel signal a script's inner calls observe: true when the run's
   cancel signal (Escape) fired or the script itself aborted
   (timeout/output-limit). Read-only (kmet.libs.concurrent/or-signal) — tools
   poll it (bash's poller kills the process tree), and the bridge checks it at
   admission, so an aborted script's queued calls never run. A normally
   finished script leaves it false: its queue still drains (fire-and-forget
   `tools/call`)."
  [run-signal abort]
  (concurrent/or-signal run-signal abort))

(defn- path-in-cwd
  "Resolve a relative string path against CWD — the read/write/edit tools'
   resolve-tool-path behavior, so slurp/spit/file-seq follow the session cwd."
  [cwd p]
  (if (and (string? p) (not (fs/absolute? p)))
    (str (fs/normalize (fs/path cwd p)))
    p))

(defn- eval-with-io
  "Run THUNK with SCI's *out*/*err* bound to the capture writers (the
   kmet.app.extensions/with-sci-io pattern: babashka's SCI is wired to the
   host streams, Jolt's sci vars are bound explicitly). Used by the eval
   thread and by sandbox/spawn, whose future thread would otherwise print to
   an unbound *out* on Jolt. Print length/level are bounded alongside: an
   infinite seq must not print host code past the deadline, and host printing
   is not interruptible — on babashka the host vars drive SCI's printer, on
   Jolt sci has its own."
  [out-w err-w thunk]
  #?(:jolt (sci/binding [sci/out out-w
                         sci/err err-w
                         sci/print-length print-length-limit
                         sci/print-level print-level-limit]
             (thunk))
     :default (binding [*out* out-w
                        *err* err-w
                        *print-length* print-length-limit
                        *print-level* print-level-limit]
                (thunk))))

;; ─── Output capture ───────────────────────────────────────────────────────
;;
;; *out*/*err* are bound to temp files (no portable bounded in-memory Writer
;; exists on both hosts — Jolt has no PipedWriter, and proxy is unavailable
;; here). A reader thread per file drains it as the eval thread writes,
;; keeping a bounded tail for the result and streaming throttled updates.
;; Past max-capture-bytes the run is aborted (:output-limit), so a runaway
;; print loop stays bounded in memory and on disk.

(defn- tail-text
  "The suffix of S holding at most BUDGET UTF-8 bytes (cut on char
   boundaries)."
  [s budget]
  (cond
    (<= (byte-length s) budget) s
    ;; ASCII fast path: chars and bytes agree, so the cut is a native slice
    (= (count s) (byte-length s)) (subs s (- (count s) budget))
    :else
    (loop [i (count s) used 0]
      (if (zero? i)
        s
        (let [n (+ used (byte-length (subs s (dec i) i)))]
          (if (> n budget) (subs s i) (recur (dec i) n)))))))

(defn- append-chunk
  "Append CHUNK to a capture state, keeping the retained tail at or under
   max-tail-bytes. Leading chunks are dropped whole while the state is over
   budget; when the newest chunk alone is over, its *tail* is kept — a burst
   is one reader poll's worth of output (a script can flush megabytes in
   25ms) and dropping it whole loses the very output the truncation notice
   describes. SKIPPED-BYTES/LINES carry the head a reader trimmed off the
   burst; SEEN-BYTES/SEEN-LINES count everything ever read (never decreased),
   so the notice can report totals the retained tail no longer knows."
  ([state chunk] (append-chunk state chunk 0 0))
  ([state chunk skipped-bytes skipped-lines]
   (let [state (-> state
                   (update :chunks conj chunk)
                   (update :bytes + (byte-length chunk))
                   (update :lines + (text-lines chunk))
                   (update :seen-bytes + (byte-length chunk) skipped-bytes)
                   (update :seen-lines + (text-lines chunk) skipped-lines))]
     (loop [s state]
       (cond
         (and (> (:bytes s) max-tail-bytes) (> (count (:chunks s)) 1))
         (let [c (first (:chunks s))]
           (recur (-> s
                      (update :chunks subvec 1)
                      (update :bytes - (byte-length c))
                      (update :lines - (text-lines c)))))

         (and (> (:bytes s) max-tail-bytes) (= 1 (count (:chunks s))))
         (let [c (first (:chunks s))
               t (tail-text c max-tail-bytes)
               head (subs c 0 (- (count c) (count t)))]
           (-> s
               (assoc :chunks [t])
               (update :bytes - (byte-length head))
               (update :lines - (text-lines head))))

         :else s)))))

(defn- chunks-text [state] (apply str (:chunks state)))

(defn- skip-bytes!
  "Advance IN to byte OFFSET (FileInputStream.skip may skip fewer)."
  [in offset]
  (loop [remaining offset]
    (when (pos? remaining)
      (let [skipped (.skip in remaining)]
        (if (pos? skipped)
          (recur (- remaining skipped))
          (do (.read in) (recur (dec remaining))))))))

(defn- read-burst
  "Read FILE from byte OFFSET to the current end, returning [text end
   skipped-bytes skipped-lines]. TEXT is only the retained tail — a burst is
   one reader poll's worth of output and can be arbitrarily larger than the
   state's budget, so the head is trimmed while reading (bounded memory) and
   reported back, keeping the byte/line totals exact. A fresh stream per
   burst: a stream that has seen EOF keeps reporting EOF when the file grows
   later (InputStreamReader does not re-check), and the eval thread is
   usually still writing when the readers start."
  [file offset]
  (try
    (let [size (fs/size file)]
      (if (<= size offset)
        ["" offset 0 0]
        (with-open [in (java.io.FileInputStream. (str file))]
          (skip-bytes! in offset)
          (let [r (java.io.InputStreamReader. in "UTF-8")
                buf (char-array 8192)]
            (loop [sb (StringBuilder.) bytes 0 lines 0]
              (let [n (.read r buf)]
                (if (pos? n)
                  (let [s (String. buf 0 n)
                        sb (doto sb (.append s))
                        bytes (+ bytes (byte-length s))
                        lines (+ lines (text-lines s))]
                    (if (> (count sb) (* 4 max-tail-bytes))
                      (recur (StringBuilder.
                              (tail-text (str sb) (* 2 max-tail-bytes)))
                             bytes
                             lines)
                      (recur sb bytes lines)))
                  (let [text (str sb)]
                    ;; END is what was actually consumed — the file can grow
                    ;; between the size probe and the read, and a stale END
                    ;; would re-read (and re-count) that overlap
                    [text (+ offset bytes)
                     (- bytes (byte-length text))
                     (- lines (text-lines text))]))))))))
    (catch Throwable _ ["" offset 0 0])))

(defn- start-reader
  "Daemon thread draining FILE in bursts until the eval thread closes the
   writer and no bytes remain unread, appending to STATE and calling
   PROGRESS per burst."
  [file state progress closed?]
  (let [drain (fn []
                (loop [offset 0]
                  (let [[text offset' skipped-bytes skipped-lines]
                        (read-burst file offset)]
                    (when (seq text)
                      (swap! state append-chunk text skipped-bytes skipped-lines)
                      (progress))
                    (if (and (empty? text) @closed?)
                      nil
                      (do (Thread/sleep 25)
                          (recur offset'))))))
        t (Thread. drain)]
    (.setDaemon t true)
    (.start t)
    t))

(defn- make-progress
  "The throttled on-update emitter shared by both readers. Also enforces the
   total *seen* capture bound — the retained tail is capped by
   max-tail-bytes, so the running total must come from the seen counters. A
   still-running script past max-capture-bytes is aborted (a finished one is
   only truncated — the limit must not turn a successful script into an error
   after the fact)."
  [out-state err-state on-update abort closed?]
  (let [last-at (atom 0)]
    (fn []
      (let [total (+ (:seen-bytes @out-state) (:seen-bytes @err-state))]
        (when (and abort (nil? @abort) (not @closed?) (> total max-capture-bytes))
          (reset! abort :output-limit))
        (when on-update
          (let [now (System/currentTimeMillis)]
            (when (>= (- now @last-at) update-throttle-ms)
              (reset! last-at now)
              (let [out (chunks-text @out-state)
                    err (chunks-text @err-state)]
                (on-update {:content (str out
                                          (when (seq err) (str "\n[stderr]\n" err)))
                            :is-partial true})))))))))

(defn- start-capture [on-update abort]
  (let [dir (str (temp-root) "/kmet-script-" (System/nanoTime))
        _ (fs/create-dirs dir)
        out-file (str dir "/out.log")
        err-file (str dir "/err.log")
        closed? (atom false)
        out-state (atom {:chunks [] :bytes 0 :lines 0 :seen-bytes 0 :seen-lines 0})
        err-state (atom {:chunks [] :bytes 0 :lines 0 :seen-bytes 0 :seen-lines 0})
        progress (make-progress out-state err-state on-update abort closed?)]
    {:dir dir
     :closed? closed?
     :out-w (java.io.OutputStreamWriter. (java.io.FileOutputStream. out-file) "UTF-8")
     :err-w (java.io.OutputStreamWriter. (java.io.FileOutputStream. err-file) "UTF-8")
     :out-state out-state
     :err-state err-state
     :readers [(start-reader out-file out-state progress closed?)
               (start-reader err-file err-state progress closed?)]}))

(defn- finish-capture!
  "Snapshot the capture tails and stop the readers. When FINISHED? the eval
   thread closed the writers, so wait briefly for the readers to drain them;
   an abandoned thread's readers are stopped here too — their temp file is
   gone, and late `on-update`s must not reach a finished tool call. The
   byte/line counts are the *seen* totals: the retained tails no longer know
   how much output they stand for."
  [capture finished?]
  (when finished?
    (doseq [t (:readers capture)]
      (try (.join t drain-timeout-ms) (catch Throwable _ nil))))
  (reset! (:closed? capture) true)
  (let [out @(:out-state capture)
        err @(:err-state capture)]
    (try (fs/delete-tree (:dir capture)) (catch Throwable _ nil))
    {:out (chunks-text out) :out-bytes (:seen-bytes out) :out-lines (:seen-lines out)
     :err (chunks-text err) :err-bytes (:seen-bytes err) :err-lines (:seen-lines err)}))

;; ─── Capabilities ─────────────────────────────────────────────────────────

(defn- shared-fns
  "Deref'd public fn values of NS-SYM — SCI contexts take values, not host
   Var objects. Macros are skipped: a macro value reaches SCI as a plain fn
   (arguments would be evaluated first), so `$` is deliberately absent."
  [ns-sym]
  (into {}
        (keep (fn [[k v]]
                (let [m (meta v)]
                  (when (and (fn? @v) (not (:macro m)))
                    [k @v]))))
        (ns-publics ns-sym)))

(defn- core-fns [cwd]
  ;; SCI's builtin clojure.core lacks slurp/spit/file-seq (the extensions'
  ;; build-context-namespaces carries the same patches) — inject the host
  ;; fns, resolving relative paths against the session cwd like the tools.
  {'slurp (fn [f & opts] (apply slurp (path-in-cwd cwd f) opts))
   'spit (fn [f content & opts] (apply spit (path-in-cwd cwd f) content opts))
   'file-seq (fn [dir] (file-seq (fs/file (path-in-cwd cwd dir))))})

(defn- process-fns
  "babashka.process with the session cwd as the default :dir and pid tracking
   for `process` (kmet's shutdown kills tracked children). babashka.process
   takes opts FIRST for token commands and LAST for a command vector — the
   wrapper normalizes both shapes."
  [cwd]
  (let [fns (shared-fns 'babashka.process)
        with-dir (fn [f]
                   (fn [& args]
                     (let [[a & more] args]
                       (cond
                         ;; (f opts cmd & inputs) — normalize to the vector form
                         (and (map? a) (vector? (first more)))
                         (let [[cmd & inputs] more]
                           (apply f cmd (merge {:dir cwd} a) inputs))

                         ;; (f opts & tokens)
                         (map? a)
                         (apply f (merge {:dir cwd} a) more)

                         ;; (f cmd & inputs) → (f cmd opts & inputs)
                         (vector? a)
                         (let [user-opts (if (map? (first more)) (first more) {})
                               inputs (if (map? (first more)) (rest more) more)]
                           (apply f a (merge {:dir cwd} user-opts) inputs))

                         ;; (f token & tokens), with a trailing opts map hoisted
                         :else
                         (let [last-arg (last args)]
                           (if (map? last-arg)
                             (apply f (merge {:dir cwd} last-arg) (butlast args))
                             (apply f {:dir cwd} args)))))))
        tracked (fn [f]
                  (fn [& args]
                    (let [p (apply f args)
                          alive? (get fns 'alive?)]
                      (try
                        (when-let [pid (kprocess/process-pid p)]
                          (kprocess/track-pid! pid)
                          ;; untrack when the process exits — a tracked pid
                          ;; left behind could be reused by an unrelated
                          ;; process and killed at kmet shutdown (bash-executor
                          ;; untracks the same way)
                          (when alive?
                            (future
                              (loop []
                                (when (try (alive? p) (catch Throwable _ false))
                                  (Thread/sleep 100)
                                  (recur)))
                              (kprocess/untrack-pid! pid))))
                        (catch Throwable _ nil))
                      p)))]
    (cond-> fns
      (contains? fns 'sh) (update 'sh with-dir)
      (contains? fns 'shell) (update 'shell with-dir)
      (contains? fns 'process) (update 'process #(tracked (with-dir %))))))

(defn- sandbox-fns [cwd writers]
  {'cwd (fn [] cwd)
   'env (fn [k] (System/getenv (str k)))
   'now (fn [] (System/currentTimeMillis))
   'sleep (fn [ms] (Thread/sleep (long ms)))
   ;; a derefable result on a daemon thread (babashka/jolt futures are
   ;; daemon-backed; SCI's own future is unavailable in a value-shared ctx).
   ;; The writers are re-bound: a future does not convey SCI's var bindings
   ;; on Jolt, so a spawned println would write to an unbound *out*.
   'spawn (fn [f]
            (if-let [{:keys [out-w err-w]} @writers]
              (future (eval-with-io out-w err-w f))
              (future (f))))})

(defn- tool-name
  "The registry name for X — strings pass through, keywords/symbols lose
   their prefix (`:read` → \"read\"), anything else stringifies. Discovery
   and dispatch must agree on it: `(tools/describe :read)` resolves, so
   `(tools/call :read {...})` must dispatch."
  [x]
  (cond
    (string? x) x
    (or (keyword? x) (symbol? x)) (name x)
    :else (str x)))

(defn- discovery-fns
  "tools/list and tools/describe over the surface this base was built for —
   discovery is a bridge-local read of the active set, never a dispatch."
  [surface]
  {'list (fn [] (vec (keys surface)))
   'describe (fn [name]
               (if-let [t (get surface (tool-name name))]
                 (select-keys t [:name :label :description :parameters])
                 {:is-error true
                  :content (str "Unknown tool: " name ". Active tools: "
                                (str/join ", " (keys surface)))}))})

(defn- require-host-namespaces!
  "Host-require before value-sharing: babashka preloads babashka.fs/process,
   Jolt does not."
  []
  (require 'babashka.fs)
  (require 'babashka.process)
  nil)

(defn- base-namespaces [surface]
  (require-host-namespaces!)
  {'clojure.core {'pmap (deref #'pmap)}
   'babashka.fs (shared-fns 'babashka.fs)
   ;; kmet.libs.json under its real name and the short `json` alias scripts
   ;; would otherwise have to require
   'kmet.libs.json (shared-fns 'kmet.libs.json)
   'tools (discovery-fns surface)})

(defn- fork-namespaces [cwd bridge writers]
  {'clojure.core (core-fns cwd)
   'babashka.process (process-fns cwd)
   'sandbox (sandbox-fns cwd writers)
   'tools bridge})

(defn- build-base
  "The base context for a surface: capability namespaces plus the surface's
   discovery fns, no per-call (or per-session) state. No :classes/:imports —
   the sandbox has no Java interop."
  [surface]
  (sci/init {:namespaces (base-namespaces surface)
             :features (if (host/jolt?) #{:jolt} #{:bb})}))

;; ─── Base cache + surface ───────────────────────────────────────────────────────

(defonce ^:private base-cache (atom {:order [] :bases {}}))

(defn- cached-base
  "The base context for KEY, built on first use. A small keyed cache keeps
   sibling sessions with different surfaces from evicting each other's; a
   rebuild is cheap and idempotent. A hit moves KEY to the back of the LRU
   (and a key already present is re-seated rather than duplicated, so
   repeated hits cannot shrink the cache below base-cache-size)."
  [key build]
  (or (get-in @base-cache [:bases key])
      (let [base (build)]
        (swap! base-cache
               (fn [{:keys [order bases]}]
                 (let [order (conj (vec (remove #{key} order)) key)
                       order (if (> (count order) base-cache-size)
                               (subvec order (- (count order) base-cache-size))
                               order)]
                   {:order order
                    :bases (assoc (select-keys bases order) key base)})))
        base)))

(defn- active-surface
  "Ordered map of name → Tool record for the run's callable set, via the
   registry's select-tools: the registry tools the model can call (∩ ENABLED;
   nil = all), plus the extension-contributed sandbox tools (always
   available — they are not in the model's tool set; the registry shadows a
   colliding contribution), minus the exclusion list. Sorted so discovery
   and dispatch share one stable order."
  [select-tools all contributed enabled]
  (into (sorted-map)
        (select-tools all {:enabled enabled
                           :contributed contributed
                           :exclude excluded-tool-names})))

;; ─── The tools bridge ─────────────────────────────────────────────────────

(def ^:private bridge-worker-count 16)

(defn- start-bridge-worker!
  "One pool worker: block on QUEUE, run a task, repeat. A task's own errors
   are caught by its submitter; a stray throw is logged so the worker
   survives and the pool keeps its size."
  [queue]
  (concurrent/spawn
   (fn []
     (loop []
       (let [task (.take queue)]
         (try
           (task)
           (catch Throwable t
             (debug/log "script bridge task failed: " t))))
       (recur)))))

(defonce ^:private bridge-queue
  ;; The shared bridge pool, started on first dispatch: inner calls queue
  ;; here instead of each starting a platform thread, so concurrent tool
  ;; executions (processes, connections) are bounded by bridge-worker-count.
  ;; The queue itself is unbounded — like the promise set a fanning-out
  ;; script already holds — and FIFO, so a script that fans out far more
  ;; calls than workers and derefs them in reverse waits for the queue ahead
  ;; of it. Bridge submissions cannot nest (script is excluded from the
  ;; surface), so the pool cannot deadlock on itself.
  (delay
    (let [q (java.util.concurrent.LinkedBlockingQueue.)]
      (dotimes [_ bridge-worker-count] (start-bridge-worker! q))
      q)))

(defn- dispatch!
  "Gate NAME against the surface, then hand the call to the shared bridge
   pool and return the promise the script derefs. Unknown/inactive names
   settle immediately with {:is-error true} and the active list. The pool
   worker conveys no dynamic bindings, so the bridge passes the run's cancel
   signal, session env and cwd as the invocation pipeline's :bindings (bash
   reads *cancel-signal*; read/write/edit resolve against *cwd*). The call
   runs through the shared pipeline (kmet.app.tools.invoke) with the run's
   tool hooks (*tool-hooks*): a blocked call settles with the hook's reason
   without executing, and the after hook runs for blocked calls too (loop
   parity). Inner calls carry a synthetic tool-call id and the turn's
   assistant message. A call picked up after the script aborted — or whose
   before hook ran past the abort — is settled as cancelled and never
   executes: the pool queue cannot fire side effects past the deadline."
  [{:keys [surface execute-tool signal ctx cwd session-env-fn hooks assistant-message
           trace on-update]} name args]
  (let [name (tool-name name)
        p (promise)]
    (if-not (contains? surface name)
      (deliver p {:is-error true
                  :content (str "Tool not active in this script: " name
                                ". Active tools: " (str/join ", " (keys surface)))})
      (let [started (System/currentTimeMillis)
            idx (dec (count (swap! trace conj {:tool name :ok false
                                               :error "incomplete"
                                               :started-at started})))
            settle-cancelled!
            (fn []
              (try
                (swap! trace assoc-in [idx]
                       {:tool name
                        :ok false
                        :error "cancelled"
                        :duration-ms (- (System/currentTimeMillis) started)})
                (catch Throwable _ nil))
              (deliver p {:is-error true
                          :content (str "Script cancelled before " name " ran")}))]
        (.offer @bridge-queue
                (fn []
                  (if @signal
                    (settle-cancelled!)
                    (let [call {:tool-name name
                                :args args
                                :tool-call-id (str "script-" idx)
                                :assistant-message assistant-message}
                          prep (invoke/prepare-tool-call (assoc call :before-hook (:before hooks)))]
                      (if @signal
                        (settle-cancelled!)
                        (let [call (assoc call :args (if (contains? prep :args) (:args prep) args))
                              executed (if (:block prep)
                                         (:block prep)
                                         (invoke/execute-tool-call
                                          execute-tool
                                          (assoc call
                                                 :signal signal
                                                 :ctx ctx
                                                 :on-update on-update
                                                 :tools surface
                                                 :bindings {:signal signal
                                                            :session-env-fn session-env-fn
                                                            :cwd cwd})))
                              result (-> (invoke/finish-tool-call
                                          (assoc call :after-hook (:after hooks))
                                          executed)
                                         (dissoc :terminate))]
                          (try
                            (swap! trace assoc-in [idx]
                                   (cond-> {:tool name
                                            :ok (not (:is-error result))
                                            :duration-ms (- (System/currentTimeMillis) started)}
                                     (:is-error result)
                                     (assoc :error (preview (:content result)))))
                            (catch Throwable _ nil))
                          (deliver p result)))))))
        p))))

(defn- bridge-fns [opts]
  {'call (fn [name & [args]] (dispatch! opts name args))
   'read (fn [& [args]] (dispatch! opts "read" args))
   'write (fn [& [args]] (dispatch! opts "write" args))
   'edit (fn [& [args]] (dispatch! opts "edit" args))
   'bash (fn [& [args]] (dispatch! opts "bash" args))})

;; ─── Result assembly ──────────────────────────────────────────────────────

(defn- format-value
  "Format a script's return value: strings raw, everything else pr-str —
   a Clojure sandbox reports Clojure data (T2 formats MCP envelopes itself).
   The print limits bound it here on the host: printing is host code, and an
   unbounded value would grow in an abandoned thread past the interrupt."
  [v]
  (if (string? v)
    v
    (binding [*print-length* print-length-limit
              *print-level* print-level-limit]
      (pr-str v))))

(defn- truncation-notice [t]
  (str "[Script output truncated: " (:total-lines t) " lines / "
       (bash-exec/format-size (:total-bytes t))
       " total, showing the last " (:shown-lines t)
       " lines. Print less or return distilled data instead.]"))

(defn- bound-content
  "Cap BODY at the tools' 50 KiB / 2000-line convention (bash-executor's tail
   truncation — the end holds the return value). TOTALS carries the capture's
   full byte/line counts, which the retained tail no longer knows."
  [body totals]
  (let [t (bash-exec/truncate-tail body)
        total-bytes (max (:total-bytes t) (:bytes totals))
        total-lines (max (:total-lines t) (:lines totals))
        truncated? (or (:truncated t)
                       (> total-bytes max-output-bytes)
                       (> total-lines max-output-lines))]
    (if truncated?
      {:content (str (:content t) "\n\n"
                     (truncation-notice {:total-lines total-lines
                                         :total-bytes total-bytes
                                         :shown-lines (:output-lines t)}))
       :truncation {:total-lines total-lines
                    :total-bytes total-bytes
                    :shown-lines (:output-lines t)
                    ;; :capture — the body fits, the capture tail dropped the
                    ;; rest (the totals above are the whole output)
                    :truncated-by (or (:truncated-by t) :capture)
                    :max-bytes max-output-bytes
                    :max-lines max-output-lines}}
      {:content body :truncation nil})))

(defn- trace-snapshot
  "The call trace with in-flight entries completed as `incomplete`, carrying
   their elapsed-at-snapshot duration (mcpScript parity)."
  [trace]
  (let [now (System/currentTimeMillis)]
    (mapv (fn [e]
            (if (= "incomplete" (:error e))
              (assoc e :duration-ms (max 0 (- now (or (:started-at e) now))))
              e))
          @trace)))

(defn- script-error-message [t]
  (str (some-> t class .getSimpleName) ": " (or (ex-message t) (str t))))

(defn- interrupt-fn
  "SCI's :interrupt-fn — called on every interpreted fn/loop entry. Sets the
   abort reason (so the result reports signal/deadline) and throws to unwind
   the interpreter. Host-native code is not interruptible (T1's documented
   runaway limitation); the host also waits with a timeout."
  [abort signal deadline]
  (fn []
    (cond
      (some? @abort)
      (throw (ex-info "Script interrupted" {:type :script/interrupt :reason @abort}))

      (and signal @signal)
      (do (reset! abort :aborted)
          (throw (ex-info "Script interrupted" {:type :script/interrupt :reason :aborted})))

      (>= (System/currentTimeMillis) deadline)
      (do (reset! abort :timeout)
          (throw (ex-info "Script interrupted" {:type :script/interrupt :reason :timeout}))))))

(defn- start-eval-thread!
  "Start the daemon eval thread. The alias prelude runs here, on the eval
   thread, immediately before the user code: SCI's *ns* is per-thread, so
   aliases registered on the caller thread would not necessarily land in the
   namespace the script evaluates in. Returns {:done promise :result atom};
   the thread closes the writers in its finally, which ends the readers."
  [fork-ctx code capture]
  (let [done (promise)
        result (atom nil)
        thread (Thread. (fn []
                          (try
                            (let [v (eval-with-io (:out-w capture) (:err-w capture)
                                                  (fn []
                                                    (sci/eval-string* fork-ctx alias-prelude)
                                                    (sci/eval-string* fork-ctx code)))]
                              (reset! result {:status :ok :value v}))
                            (catch Throwable t
                              (let [reason (:reason (ex-data t))]
                                (reset! result (if reason
                                                 {:status reason}
                                                 {:status :error
                                                  :error (script-error-message t)}))))
                            (finally
                              (try (.close (:out-w capture)) (catch Throwable _ nil))
                              (try (.close (:err-w capture)) (catch Throwable _ nil))
                              (reset! (:closed? capture) true)
                              (deliver done true)))))]
    (.setDaemon thread true)
    (.start thread)
    {:done done :result result}))

(defn- assemble-result
  "Build the tool result from the eval outcome and the capture snapshot.
   ABORT-REASON wins when set: a script unwound by the interrupt (however it
   reports, e.g. a catch that rethrows) cannot outlive its deadline."
  [res capture timeout-ms trace abort-reason elapsed-ms]
  (let [{:keys [out err out-bytes out-lines err-bytes err-lines]} capture
        status (or abort-reason (:status res))
        value (when (= :ok status) (:value res))
        ret (when (some? value) (format-value value))
        error-text (case status
                     :timeout (str "Script timed out after " (ms->sec timeout-ms) "s")
                     :aborted "Script aborted"
                     :output-limit (str "Script output exceeded "
                                        (bash-exec/format-size max-capture-bytes)
                                        " — stopped")
                     :error (:error res)
                     nil)
        parts (cond-> []
                (seq out) (conj out)
                (seq err) (conj (str "[stderr]\n" err))
                (some? ret) (conj ret)
                error-text (conj error-text))
        body (if (seq parts) (str/join "\n" parts) "(no output)")
        totals {:bytes (+ out-bytes err-bytes (byte-length ret) (byte-length error-text))
                :lines (+ out-lines err-lines (text-lines ret) (text-lines error-text))}
        {:keys [content truncation]} (bound-content body totals)
        calls (trace-snapshot trace)]
    (cond-> {:content content
             :is-error (not= :ok status)
             ;; :timeout in seconds (the unit it was asked for), :elapsed-ms
             ;; the measured total — the UI's Took line reads it
             :details (cond-> {:timeout (ms->sec timeout-ms)
                               :elapsed-ms elapsed-ms}
                        (not= :ok status) (assoc :error status)
                        (seq calls) (assoc :calls calls))}
      truncation (assoc :truncation truncation))))

(defn- run-script
  [{:keys [code timeout signal ctx on-update get-all-tools get-contributed-tools
           select-tools execute-tool generation-fn]}]
  (let [timeout-ms (normalize-timeout timeout)
        started-at (System/currentTimeMillis)
        cwd (tool-util/cwd)
        session-env-fn bash-tool/*session-env-fn*
        hooks *tool-hooks*
        assistant-message *assistant-message*
        live? (atom true)
        ;; a queued call still runs after the script returned
        ;; (fire-and-forget) — its UI updates must not: the outer tool call is
        ;; over, the transcript already has its result
        on-update (when on-update
                    (fn [evt] (when @live? (on-update evt))))
        enabled (when *enabled-tools-fn* (*enabled-tools-fn*))
        surface (active-surface select-tools
                                (get-all-tools)
                                (if get-contributed-tools (get-contributed-tools) {})
                                enabled)
        base-key [(generation-fn) (when enabled (into (sorted-set) enabled))]
        base (cached-base base-key #(build-base surface))
        abort (atom nil)
        cancelled (combined-signal signal abort)
        writers (atom nil)
        deadline (+ started-at timeout-ms)
        trace (atom [])
        bridge (bridge-fns {:surface surface
                            :execute-tool execute-tool
                            :signal cancelled
                            :ctx ctx
                            :cwd cwd
                            :session-env-fn session-env-fn
                            :hooks hooks
                            :assistant-message assistant-message
                            :on-update on-update
                            :trace trace})
        fork (sci-loader/sci-loader
              {:id "script"
               :base base
               :namespaces (fork-namespaces cwd bridge writers)
               :sci-opts {:interrupt-fn (interrupt-fn abort signal deadline)}})
        fork-ctx (loader/context fork)
        capture (start-capture on-update abort)
        _ (reset! writers capture)
        {:keys [done result]} (start-eval-thread! fork-ctx code capture)]
    (when-not (deref done timeout-ms false)
      (compare-and-set! abort nil :timeout)
      ;; the interpreter notices the abort on its next step; a host-blocked
      ;; script does not, and is abandoned here
      (deref done interrupt-grace-ms false))
    (try
      (let [finished? (realized? done)
            snapshot (finish-capture! capture finished?)
            res (or @result {:status (or @abort :timeout)})]
        (assemble-result res snapshot timeout-ms trace @abort
                         (- (System/currentTimeMillis) started-at)))
      (finally (reset! live? false)))))

;; ─── Tool record ──────────────────────────────────────────────────────────

(def ^:private description
  (str "Run a Clojure script in a sandbox with kmet's tools bridged in. Use it to run a "
       "multi-step task as one call — fan tool calls out in parallel, loop, chain, filter, "
       "scan files — so N tool results cost one round trip and only the script's distilled "
       "output enters the conversation; inner results return to the script, never to the "
       "model.\n\n"
       "Tool calls return promises — deref when the value is needed:\n"
       "  @(tools/call \"name\" {...})   ;; any active tool, by name (string/keyword/symbol)\n"
       "  @(tools/bash {...}) @(tools/read {...}) @(tools/write {...}) @(tools/edit {...})\n"
       "  (tools/list) (tools/describe \"name\")   ;; discovery (synchronous)\n"
       "Start independent calls before derefing any of them so they run in parallel; "
       "(deref p ms ::timeout) bounds a call. The surface is every active tool plus "
       "extension-contributed sandbox tools (e.g. MCP server tools). A call settles with "
       "the tool's own result map {:content :is-error :details :truncation :images} — "
       "branch on :is-error and read :content; don't pr-str the whole map (an image read "
       "carries base64).\n\n"
       "Preloaded aliases (no require needed): fs (babashka.fs), str/set/edn/walk "
       "(clojure.*), json (kmet.libs.json), p (babashka.process), plus tools and sandbox "
       "(cwd/env/now/sleep/spawn); clojure.core carries slurp/spit/file-seq/pmap. No Java "
       "interop and no other requires. Print with println; the last expression's value is "
       "reported too. Errors are Exceptions — catch with (catch Exception e ...); Throwable "
       "is not a class here. Relative paths in slurp/spit/sh resolve against the session cwd "
       "((sandbox/cwd)); babashka.fs uses the process directory. Default timeout 30 s — raise "
       ":timeout for long scans; on timeout the output so far is returned, inner "
       "calls are cancelled, and the result carries :elapsed-ms."))

(defn title
  "Quiet one-liner body for the script tool: the first code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (let [line (first (str/split-lines code))
          line (if (> (count line) 80) (str (subs line 0 80) "…") line)]
      (str "script " line))))

(defn create-tool
  "Build the script Tool record. OPTS wires the registry seams (registry.clj
   builds the built-in entry; tests may build their own):
     :get-all-tools — 0-arg registry tool map (the model's tools)
     :get-contributed-tools — 0-arg extension-contributed sandbox tool map
                              (script.md T2; optional, defaults to none)
     :select-tools  — (fn [all {:keys [enabled contributed exclude]}]) — the
                      registry's surface filter, so a script's callable set
                      resolves exactly like the loop's schema
     :execute-tool  — (fn [name args opts]) dispatch
     :generation-fn — 0-arg registry generation counter"
  [{:keys [get-all-tools get-contributed-tools select-tools execute-tool generation-fn]}]
  (tool/make-tool
   :name "script"
   :label "Run script"
   :description description
   :prompt-snippet "Orchestrate tool calls and bulk file work in one Clojure script, printing only distilled results"
   :prompt-guidelines
   ["Use script to collapse a multi-step workflow into one call — a search/read/verify chain, a loop over many files, or several tool calls belong in one script, not in N round-trips."
    "Fire independent inner calls before derefing any of them: every tools/call returns a promise and runs in parallel; deref only when a value is needed."
    "Route file work through script when the raw output is large or needs filtering (rg/cat/head/wc/sed/awk pipelines, many read calls): scan, filter and aggregate in Clojure and print only the distilled lines — raw output is re-sent every later turn."
    "A quick one-off command with small output still belongs in bash; issue it alongside the script call in the same message, not in a separate turn."
    "Prefer read for exactly one file and edit/write for reviewable changes; script is for bulk edits and data that would bloat the transcript."]
   :params {:code {:type :string :description "Clojure code to run"}
            :timeout {:type :number
                      :description (str "Timeout in seconds (default "
                                        (ms->sec default-timeout-ms)
                                        "; fractional allowed), like bash's :timeout")
                      :optional? true}}
   :execute (fn [args on-update signal ctx]
              (let [code (some-> (:code args) str)]
                (if (str/blank? code)
                  {:content "No code provided." :is-error true}
                  (run-script {:code code
                               :timeout (:timeout args)
                               :signal signal
                               :ctx ctx
                               :on-update on-update
                               :get-all-tools get-all-tools
                               :get-contributed-tools get-contributed-tools
                               :select-tools select-tools
                               :execute-tool execute-tool
                               :generation-fn generation-fn}))))
   :streams? true
   :contextual? true
   :title title))


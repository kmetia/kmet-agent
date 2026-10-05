(ns kmet.app.tools.run-code
  "The `run_code` tool — model-written Clojure in a per-call SCI sandbox with
   kmet's tools bridged in (design: run_code.md).

   Lifecycle: every call builds a fresh SCI context and discards it on
   return. The context carries the capability namespaces, the surface's
   discovery fns (tools/list, tools/describe) and the per-call tools bridge
   (async promises over execute-tool, drained by a shared bounded worker
   pool). The script runs on a daemon thread whose SCI :interrupt-fn aborts
   interpreted code on the run signal or the deadline; *out*/*err* are
   captured to bounded temp files, streamed through on-update, and assembled
   into the result (a 16 KiB / 2000-line default budget, configurable per
   call, with a bounded capture tail)."
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
            [sci.core :as sci]))

;; ─── Constants ────────────────────────────────────────────────────────────

(def ^:private max-timeout-ms
  "Cap on an explicit timeout — a day. Bash has no cap (a huge :timeout just
   runs); capping keeps the deadline arithmetic overflow-free and the value
   bounded in the result."
  (* 24 60 60 1000))

(defn- ms->sec
  "Milliseconds as seconds — whole numbers stay integers (30000 → 30), the
   rest a double (300 → 0.3), so the timeout reported in a result reads like
   the one that was asked for."
  [ms]
  (let [q (quot ms 1000)]
    (if (= ms (* q 1000)) q (/ ms 1000.0))))

(def ^:private tool-max-output-bytes bash-exec/DEFAULT-MAX-BYTES)
(def ^:private tool-max-output-lines bash-exec/DEFAULT-MAX-LINES)

(def ^:private default-run-code-output-bytes (* 16 1024))
(def ^:private default-capture-bytes (* 1 1024 1024))
(def ^:private max-tail-bytes (* 128 1024))
;; Bound on printed collections (the script's own printing and the return
;; value). Generous on purpose: it exists to stop an unbounded seq from
;; printing forever past the deadline, not to second-guess a script's pr-str.
(def ^:private print-length-limit 100000)
(def ^:private print-level-limit 30)
(def ^:private update-throttle-ms 100)

(def ^:private await-poll-ms 25)
(def ^:private capture-poll-ms 25)
(def ^:private interrupt-grace-ms 1500)
(def ^:private error-preview-chars 300)
(def ^:private error-preview-lines 3)
(def ^:private excluded-tool-names #{"run_code"})

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
   kmet.app.loop next to bash-tool/*cancel-signal*: a script's callable
   surface is the registry filtered to this set. nil outside the loop's run
   paths (extension code calling execute-tool directly) — then every
   registry tool is callable."
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
  "The effective deadline in ms from TIMEOUT seconds (bash's unit), or nil
   for **no deadline** — absent, 0, negative and non-numeric all mean none,
   exactly bash's :timeout semantics. A positive value rounds to the nearest
   ms (minimum 1) and is capped at max-timeout-ms."
  [v]
  (let [secs (try
               (double (cond
                         (number? v) v
                         (string? v) (Double/parseDouble (str/trim v))
                         :else nil))
               (catch Exception _ nil))]
    (when (and secs (pos? secs))
      (-> (* 1000.0 secs) Math/round (max 1) (min max-timeout-ms)))))

(defn- normalize-limit
  "Normalize an output limit to a positive integer no larger than the tool-wide cap."
  [v fallback upper]
  (let [n (try (long v) (catch Exception _ nil))]
    (cond
      (or (nil? n) (not (pos? n))) fallback
      :else (min n upper))))

(defn- resolve-limits
  "Resolve the per-call budgets once, before the script starts. Only the
   result byte budget is callable; the line and capture budgets are fixed."
  [max-bytes]
  {:bytes (normalize-limit max-bytes
                           default-run-code-output-bytes
                           tool-max-output-bytes)
   :lines tool-max-output-lines
   :capture-bytes default-capture-bytes})

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
;; here). A monitor thread polls the files: it streams throttled on-updates
;; and aborts the run past the fixed 1 MiB capture budget, so a runaway print
;; loop stays bounded in memory and on disk. The final result scans the files
;; once for the exact totals and the retained tail.

(defn- file-size [f]
  (try (fs/size f) (catch Throwable _ 0)))

(defn- skip-bytes!
  "Advance IN to byte OFFSET (FileInputStream.skip may skip fewer)."
  [in offset]
  (loop [remaining offset]
    (when (pos? remaining)
      (let [skipped (.skip in remaining)]
        (if (pos? skipped)
          (recur (- remaining skipped))
          (do (.read in) (recur (dec remaining))))))))

(defn- read-tail
  "The tail of FILE as a string — at most MAX-BYTES of its end, cut on a
   UTF-8 char boundary (replacement chars from a mid-char cut are dropped).
   \"\" when the file is missing or unreadable."
  [file max-bytes]
  (try
    (let [size (file-size file)]
      (if (zero? size)
        ""
        (let [start (max 0 (- size max-bytes))]
          (with-open [in (java.io.FileInputStream. (str file))
                      r (java.io.InputStreamReader. in "UTF-8")]
            (skip-bytes! in start)
            (let [buf (char-array 8192)
                  sb (StringBuilder.)]
              (loop []
                (let [n (.read r buf)]
                  (when (pos? n)
                    (.append sb (String. buf 0 n))
                    (recur))))
              (let [s (str sb)]
                (if (pos? start)
                  ;; start can land inside a multi-byte char: drop every
                  ;; replacement char the truncated prefix produced
                  (loop [s s]
                    (if (str/starts-with? s "\uFFFD")
                      (recur (subs s 1))
                      s))
                  s)))))))
    (catch Throwable _ "")))

(defn- scan-file
  "Scan FILE once, returning its exact UTF-8 byte and newline totals."
  [file]
  (try
    (with-open [in (java.io.FileInputStream. (str file))
                r (java.io.InputStreamReader. in "UTF-8")]
      (let [buf (char-array 8192)]
        (loop [bytes 0 lines 0]
          (let [n (.read r buf)]
            (if (pos? n)
              (let [s (String. buf 0 n)]
                (recur (+ bytes (byte-length s)) (+ lines (text-lines s))))
              {:bytes bytes :lines lines})))))
    (catch Throwable _ {:bytes 0 :lines 0})))

(defn- start-capture
  "Open the capture files and start the monitor thread. The monitor streams
   throttled on-update tails while output is arriving and sets ABORT to
   :output-limit once the two files pass CAPTURE-LIMIT; CLOSED? stops it
   (the eval thread's finally)."
  [on-update abort capture-limit]
  (let [dir (str (temp-root) "/kmet-run-code-" (System/nanoTime))
        _ (fs/create-dirs dir)
        out-file (str dir "/out.log")
        err-file (str dir "/err.log")
        out-w (java.io.OutputStreamWriter. (java.io.FileOutputStream. out-file) "UTF-8")
        err-w (java.io.OutputStreamWriter. (java.io.FileOutputStream. err-file) "UTF-8")
        closed? (atom false)
        last-at (atom 0)
        last-total (atom 0)
        monitor (Thread.
                 (fn []
                   (loop []
                     (when-not @closed?
                       (let [total (+ (file-size out-file) (file-size err-file))]
                         (when (and abort (nil? @abort) (> total capture-limit))
                           (reset! abort :output-limit))
                         (when (and on-update (> total @last-total))
                           (let [now (concurrent/monotonic-ms)]
                             (when (>= (- now @last-at) update-throttle-ms)
                               (reset! last-at now)
                               (reset! last-total total)
                               (let [out (read-tail out-file max-tail-bytes)
                                     err (read-tail err-file max-tail-bytes)]
                                 (try
                                   (on-update {:content (str out
                                                             (when (seq err)
                                                               (str "\n[stderr]\n" err)))
                                               :is-partial true})
                                   (catch Throwable t
                                     ;; the monitor must survive a throwing
                                     ;; callback: it enforces the capture abort
                                     (debug/log "run_code capture update failed: " t)))))))
                         (Thread/sleep capture-poll-ms)
                         (recur))))))]
    (.setDaemon monitor true)
    (.start monitor)
    {:dir dir
     :closed? closed?
     :out-file out-file
     :err-file err-file
     :out-w out-w
     :err-w err-w}))

(defn- finish-capture!
  "Stop the monitor and snapshot the capture: the exact totals (one scan per
   file) and the retained tails. The writers were closed by the eval thread's
   finally before it delivered `done`, so a finished run's files are complete;
   an abandoned thread's flushed bytes are read as-is."
  [capture]
  (reset! (:closed? capture) true)
  (let [{out-bytes :bytes out-lines :lines} (scan-file (:out-file capture))
        {err-bytes :bytes err-lines :lines} (scan-file (:err-file capture))
        out (read-tail (:out-file capture) max-tail-bytes)
        err (read-tail (:err-file capture) max-tail-bytes)]
    (try (fs/delete-tree (:dir capture)) (catch Throwable _ nil))
    {:out out :out-bytes out-bytes :out-lines out-lines
     :err err :err-bytes err-bytes :err-lines err-lines}))

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

(defn- emit-value
  "Write one compact script result to the captured writer; host println is not bound to SCI output on Jolt."
  [out-w v]
  (binding [*out* out-w
            *print-length* print-length-limit
            *print-level* print-level-limit]
    (println (if (string? v) v (pr-str v)))))

(defn- sandbox-fns [cwd writers]
  {'cwd (fn [] cwd)
   'env (fn [k] (System/getenv (str k)))
   'now (fn [] (System/currentTimeMillis))
   'sleep (fn [ms] (Thread/sleep (long ms)))
   'emit (fn [v]
           (if-let [{:keys [out-w]} @writers]
             (emit-value out-w v)
             (throw (ex-info "run_code output is not initialized"
                             {:type :run-code/no-output}))))
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

(defn- unreadable-namespace
  "The boundary error for a namespace the sandbox cannot serve."
  [namespace]
  (throw (ex-info (str "run_code cannot load namespace " namespace
                       "; the sandbox only has its injected namespaces and"
                       " SCI's built-in namespaces")
                  {:type :run-code/unreadable :namespace (str namespace)})))

(defn- sandbox-load-fn
  "SCI's :load-fn for the sandbox. SCI consults it even for a `:reload` of an
   already-loaded namespace, so a known namespace gets the no-op empty source
   (the loader the sandbox used before returned its existing handle the same
   way — a reload changed nothing); anything else gets the boundary error."
  [ctx-holder]
  (fn [{:keys [namespace]}]
    (if (and @ctx-holder (sci/find-ns @ctx-holder (symbol namespace)))
      {:file (str namespace) :source ""}
      (unreadable-namespace namespace))))

(defn- sandbox-context
  "Build the per-call SCI context: NAMESPACES plus the sandbox's features,
   load-fn and interrupt-fn."
  [namespaces interrupt-fn]
  (let [holder (atom nil)
        ctx (sci/init {:namespaces namespaces
                       :features (if (host/jolt?) #{:jolt} #{:bb})
                       :load-fn (sandbox-load-fn holder)
                       :interrupt-fn interrupt-fn})]
    (reset! holder ctx)
    ctx))

(defn- context-namespaces
  "The per-call sandbox namespaces: the capability vocabulary (SCI's builtin
   clojure.core plus slurp/spit/file-seq/pmap, babashka.fs/json, the process
   wrappers), the surface's discovery fns and the per-call bridge, cwd fns
   and writers. Built fresh for every call — a fresh sci/init is cheap, so
   there is no cached base to fork and no registry generation to key on. No
   :classes/:imports (no Java interop); the context's :load-fn is
   sandbox-load-fn, so an unknown require fails with the boundary error and a
   reload of a known namespace is a no-op."
  [surface cwd bridge writers]
  (require-host-namespaces!)
  {'clojure.core (merge {'pmap (deref #'pmap)} (core-fns cwd))
   'babashka.fs (shared-fns 'babashka.fs)
   ;; kmet.libs.json under its real name and the short `json` alias scripts
   ;; would otherwise have to require
   'kmet.libs.json (shared-fns 'kmet.libs.json)
   'babashka.process (process-fns cwd)
   'sandbox (sandbox-fns cwd writers)
   'tools (merge (discovery-fns surface) bridge)})

;; ─── The callable surface ───────────────────────────────────────────────────────

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
             (debug/log "run_code bridge task failed: " t))))
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
                  :content (str "Tool not active in this run_code call: " name
                                ". Active tools: " (str/join ", " (keys surface)))})
      (let [idx (dec (count (swap! trace conj {:tool name :ok false
                                               :error "incomplete"})))
            settle-cancelled!
            (fn []
              (try
                (swap! trace assoc-in [idx] {:tool name :ok false :error "cancelled"})
                (catch Throwable _ nil))
              (deliver p {:is-error true
                          :content (str "run_code cancelled before " name " ran")}))]
        (.offer @bridge-queue
                (fn []
                  (if @signal
                    (settle-cancelled!)
                    (let [call {:tool-name name
                                :args args
                                :tool-call-id (str "run_code-" idx)
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
                                            :ok (not (:is-error result))}
                                     (:is-error result)
                                     (assoc :error (preview (:content result)))))
                            (catch Throwable _ nil))
                          (deliver p result)))))))
        p))))

(defn- call-spec
  "Normalize one call-many descriptor to [tool-name args]."
  [spec]
  (cond
    (map? spec) [(:name spec) (:args spec)]
    (sequential? spec) [(first spec) (second spec)]
    :else (throw (ex-info "call-many expects {:name ... :args ...} or [name args]"
                          {:type :run-code/invalid-call}))))

(defn- call-many
  "Validate the complete batch before dispatching any descriptor."
  [opts calls]
  (let [calls (mapv call-spec calls)]
    (mapv (fn [[name args]] (dispatch! opts name args)) calls)))

(defn- abort-await! [abort]
  (when abort
    (compare-and-set! abort nil :aborted))
  (throw (ex-info "run_code aborted while waiting for tool calls"
                  {:type :run-code/await-aborted
                   :reason :aborted})))

(defn- await-one
  "Wait for one tool promise in short polls so script cancellation reaches the interpreter."
  [signal abort promise]
  (loop []
    (if (and signal @signal)
      (abort-await! abort)
      (let [value (deref promise await-poll-ms ::await-pending)]
        (cond
          (and signal @signal) (abort-await! abort)
          (not= ::await-pending value) value
          :else (recur))))))

(defn- await-all [signal abort promises]
  (mapv (fn [promise] (await-one signal abort promise)) promises))

(defn- bridge-fns [opts]
  {'call (fn [name & [args]] (dispatch! opts name args))
   'call-many (fn [calls] (call-many opts calls))
   'await-all (fn [promises] (await-all (:signal opts) (:abort opts) promises))
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
  (str "[run_code output truncated: " (:total-lines t) " lines / "
       (bash-exec/format-size (:total-bytes t))
       " total, showing the last " (:shown-lines t)
       " lines. Print less or return distilled data instead.]"))

(defn- bound-content
  "Cap BODY at the per-call byte/line budget (bash-executor's tail
   truncation — the end holds the return value). TOTALS carries the capture's
   full byte/line counts, which the retained tail no longer knows."
  [body totals max-bytes max-lines]
  (let [t (bash-exec/truncate-tail body
                                   :max-bytes max-bytes
                                   :max-lines max-lines)
        total-bytes (max (:total-bytes t) (:bytes totals))
        total-lines (max (:total-lines t) (:lines totals))
        truncated? (or (:truncated t)
                       (> total-bytes max-bytes)
                       (> total-lines max-lines))]
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
                    :max-bytes max-bytes
                    :max-lines max-lines}}
      {:content body :truncation nil})))

(defn- run-code-error-message [t]
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
      (throw (ex-info "run_code interrupted" {:type :run-code/interrupt :reason @abort}))

      (and signal @signal)
      (do (reset! abort :aborted)
          (throw (ex-info "run_code interrupted" {:type :run-code/interrupt :reason :aborted})))

      (and deadline (>= (concurrent/monotonic-ms) deadline))
      (do (reset! abort :timeout)
          (throw (ex-info "run_code interrupted" {:type :run-code/interrupt :reason :timeout}))))))

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
                                                  :error (run-code-error-message t)}))))
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
   reports, e.g. a catch that rethrows) cannot outlive its deadline.
   TIMEOUT-MS nil = no deadline (bash parity) and the result's :timeout is
   nil; ELAPSED-MS is the measured wall clock of the whole call."
  [{:keys [res capture timeout-ms trace abort-reason elapsed-ms
           output-bytes output-lines capture-bytes]}]
  (let [{:keys [out err out-bytes out-lines err-bytes err-lines]} capture
        status (or abort-reason (:status res))
        value (when (= :ok status) (:value res))
        ret (when (some? value) (format-value value))
        error-text (case status
                     :timeout (str "run_code timed out after " (ms->sec timeout-ms) "s")
                     :aborted "run_code aborted"
                     :output-limit (str "run_code output exceeded "
                                        (bash-exec/format-size capture-bytes)
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
        {:keys [content truncation]} (bound-content body totals output-bytes output-lines)
        calls @trace]
    (cond-> {:content content
             :is-error (not= :ok status)
             ;; :timeout in seconds (the unit it was asked for; nil = no
             ;; deadline), :elapsed-ms the measured total — the UI's Took
             ;; line reads it
             :details (cond-> {:timeout (some-> timeout-ms ms->sec)
                               :elapsed-ms elapsed-ms}
                        (not= :ok status) (assoc :error status)
                        (seq calls) (assoc :calls calls))}
      truncation (assoc :truncation truncation))))

(defn- run-code
  [{:keys [code timeout limits signal ctx on-update
           get-all-tools get-contributed-tools select-tools execute-tool]}]
  (let [timeout-ms (normalize-timeout timeout)
        ;; monotonic: this origin measures both the deadline and the
        ;; :elapsed-ms reported in the result
        started-at (concurrent/monotonic-ms)
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
        abort (atom nil)
        cancelled (combined-signal signal abort)
        writers (atom nil)
        deadline (when timeout-ms (+ started-at timeout-ms))
        trace (atom [])
        bridge (bridge-fns {:surface surface
                            :execute-tool execute-tool
                            :signal cancelled
                            :abort abort
                            :ctx ctx
                            :cwd cwd
                            :session-env-fn session-env-fn
                            :hooks hooks
                            :assistant-message assistant-message
                            :on-update on-update
                            :trace trace})
        fork-ctx (sandbox-context (context-namespaces surface cwd bridge writers)
                                  (interrupt-fn abort signal deadline))
        capture (start-capture on-update abort (:capture-bytes limits))
        _ (reset! writers capture)
        {:keys [done result]} (start-eval-thread! fork-ctx code capture)]
    (if timeout-ms
      (when-not (deref done timeout-ms false)
        (compare-and-set! abort nil :timeout)
        ;; the interpreter notices the abort on its next step; a host-blocked
        ;; script does not, and is abandoned here
        (deref done interrupt-grace-ms false))
      ;; no deadline (bash's nil/0): wait for the script — but Escape must
      ;; still end the call, so a host-blocked script (which the interrupt
      ;; cannot reach) is abandoned after the same grace the deadline uses
      (loop []
        (when (= ::pending (deref done interrupt-grace-ms ::pending))
          (if @cancelled
            (compare-and-set! abort nil :aborted)
            (recur)))))
    (try
      (let [snapshot (finish-capture! capture)
            res (or @result {:status (or @abort :timeout)})]
        (assemble-result {:res res
                          :capture snapshot
                          :timeout-ms timeout-ms
                          :output-bytes (:bytes limits)
                          :output-lines (:lines limits)
                          :capture-bytes (:capture-bytes limits)
                          :trace trace
                          :abort-reason @abort
                          :elapsed-ms (- (concurrent/monotonic-ms) started-at)}))
      (finally (reset! live? false)))))

;; ─── Tool record ──────────────────────────────────────────────────────────

(def ^:private description
  (str "Run a Clojure program against the available tools. `code` is the program body; top-level forms run in order and the last value is returned. Call tools from inside the program — inner results stay there and never enter the conversation. Only what you print or return is program output — curate it.\n\n"
       "Tool calls are async: @(tools/call \"name\" args). Fire independent calls before derefing them; use (deref p ms ::timeout) when needed. For batches, use (tools/await-all (tools/call-many [{:name \"read\" :args {...}} ...])). Discover active tools with (tools/list) and (tools/describe \"name\"). (sandbox/emit value) prints one compact result and returns nil. Calls settle as {:content :is-error :details :truncation :images}; branch on :is-error and read :content.\n\n"
       "Aliases: fs, str/set/edn/walk, json, p, tools, and sandbox; core adds slurp/spit/file-seq/pmap. No Java interop. `fs` is process-relative; `slurp`/`spit`/`sh` use the session cwd. Catch `Exception` (`Throwable` is unavailable); `:content` may be a block vector/image. Extension-contributed tools join the active surface. :timeout is seconds (0 or omitted means no deadline). Output is capped at 16 KiB/2000 lines by default; :max-output-bytes can raise the byte cap up to 50 KiB."))

(defn title
  "Quiet one-liner body for the run_code tool: the first code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (let [line (first (str/split-lines code))
          line (if (> (count line) 80) (str (subs line 0 80) "…") line)]
      (str "run_code " line))))

(defn create-tool
  "Build the script Tool record. OPTS wires the registry seams (registry.clj
   builds the built-in entry; tests may build their own):
     :get-all-tools — 0-arg registry tool map (the model's tools)
     :get-contributed-tools — 0-arg extension-contributed sandbox tool map
                              (run_code.md T2; optional, defaults to none)
     :select-tools  — (fn [all {:keys [enabled contributed exclude]}]) — the
                      registry's surface filter, so a script's callable set
                      resolves exactly like the loop's schema
     :execute-tool  — (fn [name args opts]) dispatch"
  [{:keys [get-all-tools get-contributed-tools select-tools execute-tool]}]
  (tool/make-tool
   :name "run_code"
   :label "Run code"
   :description description
   :prompt-snippet "Run a Clojure program against the available tools, curating its output"
   :prompt-guidelines
   ["Use run_code when a task needs several tool calls, a loop over many files, or a search/read/verify chain; use bash for one small command and read for one file."
    "Call tools from inside the program; only what it prints or returns enters the conversation — curate it. Fire independent calls before derefing them, and use tools/call-many + tools/await-all for batches."
    "Filter and aggregate locally, then use sandbox/emit (or return one distilled value) instead of printing raw file/search output."]
   :params {:code {:type :string :description "Clojure program body to run"}
            :timeout {:type :number
                      :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                        "= no deadline; fractional ok, capped at a day")
                      :optional? true}
            :max-output-bytes {:type :number
                               :description "Maximum result bytes (default 16 KiB; capped at 50 KiB)"
                               :optional? true}}
   :execute (fn [args on-update signal ctx]
              (let [code (some-> (:code args) str)]
                (if (str/blank? code)
                  {:content "No code provided." :is-error true}
                  (run-code {:code code
                             :timeout (:timeout args)
                             :limits (resolve-limits (:max-output-bytes args))
                             :signal signal
                             :ctx ctx
                             :on-update on-update
                             :get-all-tools get-all-tools
                             :get-contributed-tools get-contributed-tools
                             :select-tools select-tools
                             :execute-tool execute-tool}))))
   :streams? true
   :contextual? true
   ;; the script tool: the transcript keeps the Elapsed/Took tail line
   ;; (tool-execution :status) for its whole life
   :status true
   :title title))


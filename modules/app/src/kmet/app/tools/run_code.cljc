(ns kmet.app.tools.run-code
  "The `run_code` tool — model-written Clojure in a per-call SCI sandbox with
   kmet's tools bridged in (design: docs/architecture.md § Tools and the script sandbox).

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
            [kmet.app.tools.bash-output :as bash-out]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.invoke :as invoke]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.sandbox :as sandbox]
            [kmet.app.debug :as debug]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.host :as host]
            [kmet.libs.process :as kprocess]
            [kmet.libs.text :as text]
            [kmet.srce.core :as srce]
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

(def ^:private max-image-bytes
  "Cap on one emitted image's base64 payload — the provider-side ceiling for
   a single image request, so an oversized image fails inside the script
   instead of at the API."
  (* 4 1024 1024))

(def ^:private max-image-file-bytes
  "Cap on the raw bytes read from an emit-image path. Base64 expands 4/3, so
   3/4 of MAX-IMAGE-BYTES is the largest file that stays under it. Checked
   before the read, so a huge file is refused without loading it."
  (quot (* 3 max-image-bytes) 4))

(def ^:private max-images
  "Cap on images attached to one run_code result."
  8)

(def ^:private tool-max-output-bytes bash-out/DEFAULT-MAX-BYTES)
(def ^:private tool-max-output-lines bash-out/DEFAULT-MAX-LINES)

(def ^:private default-run-code-output-bytes (* 16 1024))
(def ^:private default-capture-bytes (* 1 1024 1024))
(def ^:private max-tail-bytes (* 128 1024))
;; Bound on printed collections (the script's own printing and the return
;; value). Generous on purpose: it exists to stop an unbounded seq from
;; printing forever past the deadline, not to second-guess a script's pr-str.
(def ^:private print-length-limit 100000)
(def ^:private print-level-limit 30)
(def ^:private update-throttle-ms 100)

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
   :assistant-message (from *assistant-message*); a blocked call's :terminate
   rides through to the script's result — an inner call has no batch to stop."
  nil)

(def ^:dynamic *assistant-message*
  "The assistant message whose tool-call batch is running — bound by
   kmet.app.loop around execute-tool-calls! so a script's inner calls carry
   the same :assistant-message in their hook payload as the outer script
   call. nil outside the loop (extension code calling the tool directly);
   the key is then omitted from the hook payload."
  nil)

;; ─── Small helpers ────────────────────────────────────────────────────────

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
   poll it (bash's poller kills the process tree), and the bridge checks it
   before a call starts and again after the before hook, so an aborted
   script's queued calls never run. A normally finished script leaves it
   false: its queue still drains (fire-and-forget `tools/call`)."
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
   kmet.app.extensions.core/with-sci-io pattern). Babashka's SCI is wired to
   the host dynamic vars, so binding those reaches the sandbox; on Jolt and on
   the JVM the standalone sci has its OWN out/err vars — bound explicitly, or
   the sandbox's printlns hit an unbound sci var (SciUnbound cannot be cast to
   Writer) and stream nothing. Used by the eval thread and by sandbox/spawn,
   whose future thread would otherwise print outside the capture. Print
   length/level are bounded alongside: an infinite seq must not print host
   code past the deadline, and host printing is not interruptible."
  [out-w err-w thunk]
  #?(:bb (binding [*out* out-w
                   *err* err-w
                   *print-length* print-length-limit
                   *print-level* print-level-limit]
           (thunk))
     :default (sci/binding [sci/out out-w
                            sci/err err-w
                            sci/print-length print-length-limit
                            sci/print-level print-level-limit]
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

(defn- start-capture
  "Open the capture files and start the monitor thread. The monitor streams
   throttled on-update tails while output is arriving and sets ABORT to
   :output-limit once the two files pass CAPTURE-LIMIT; CLOSED? stops it
   (the eval thread's finally)."
  [on-update abort capture-limit]
  (let [dir (str (temp-root) "/kmet-run-code-" (System/nanoTime))
        _ (fs/create-dirs dir)
        out-file (str dir "/kmet-out.log")
        err-file (str dir "/kmet-err.log")
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
  "Stop the monitor and remove the capture directory. The script's reported
   output is the emit buffer (assemble-result), not the captured streams: the
   files exist to swallow the script's stdout/stderr and to enforce the
   capture budget, so there is nothing to read back."
  [capture]
  (reset! (:closed? capture) true)
  (try (fs/delete-tree (:dir capture)) (catch Throwable _ nil)))

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

(defn- format-value
  "Render a script value for reported output: strings raw, everything else
   pr-str — a Clojure sandbox reports Clojure data. The print limits bound it
   here on the host: printing is host code, and an unbounded value would grow
   in an abandoned thread past the interrupt."
  [v]
  (if (string? v)
    v
    (binding [*print-length* print-length-limit
              *print-level* print-level-limit]
      (pr-str v))))

(defn- emit!
  "Append one value to the call's reported output and stream it. `emit` is
   the curated text channel: stdout/stderr are captured but never reported,
   and a script that emits nothing reports its final value instead
   (assemble-result). The buffer is bounded at the capture budget — past it
   the abort is set to :output-limit and emit throws, so a runaway emit loop
   cannot grow the buffer without bound. Returns nil."
  [{:keys [emitted emitted-bytes on-update abort capture-limit]} v]
  (let [s (format-value v)
        total (swap! emitted-bytes + (bash-out/byte-length s))]
    (when (and capture-limit (> total capture-limit))
      (compare-and-set! abort nil :output-limit)
      (throw (ex-info "run_code output limit exceeded"
                      {:type :run-code/output-limit :reason :output-limit})))
    (swap! emitted conj s)
    (when on-update
      (on-update {:content s :is-partial true}))
    nil))

(defn- image-arg
  "Normalize one emit-image argument to the wire image block
   `{:data <base64> :mime-type <str>}`: a supported image file path (resolved
   against the runtime cwd) or such a block as a map (an MCP image result, for
   example), whose type must be one a provider accepts — a script-built block
   must not be able to fail a later turn at the API. A path is refused by
   MAX-IMAGE-FILE-BYTES before it is read, so a huge file never loads. Throws
   for anything else, so a mistyped path fails the script loudly instead of
   attaching nothing."
  [cwd x]
  (cond
    (map? x)
    (let [{:keys [data mime-type]} x]
      (cond
        (not (and (string? data) (string? mime-type) (seq data)))
        (throw (ex-info "emit-image expects {:data <base64> :mime-type <str>}"
                        {:type :run-code/bad-image}))

        (not (contains? read-tool/supported-image-mime-types mime-type))
        (throw (ex-info (str "emit-image: unsupported image type " mime-type
                             " (supported: "
                             (str/join ", " (sort read-tool/supported-image-mime-types))
                             ")")
                        {:type :run-code/bad-image :mime-type mime-type}))

        :else {:data data :mime-type mime-type}))

    (string? x)
    (let [path (path-in-cwd cwd x)
          size (try (fs/size path) (catch Throwable _ nil))]
      (when (and size (> size max-image-file-bytes))
        (throw (ex-info (str "emit-image: " x " is "
                             (bash-out/format-size size) " — the cap is "
                             (bash-out/format-size max-image-file-bytes))
                        {:type :run-code/image-too-large :path x})))
      (let [bytes (try (fs/read-all-bytes path) (catch Throwable _ nil))]
        (or (and bytes (read-tool/image-block bytes))
            (throw (ex-info (str "emit-image: not a supported image file: " x)
                            {:type :run-code/bad-image :path x})))))

    :else
    (throw (ex-info "emit-image expects a path or a {:data :mime-type} block"
                    {:type :run-code/bad-image :value (str (type x))}))))

(defn- emit-image!
  "Append one image to the call's reported :images and return nil — the image
   counterpart of emit. X is a supported image file path (resolved against
   the runtime cwd) or a wire block {:data :mime-type}. MAX-IMAGE-BYTES of
   base64 per image and MAX-IMAGES per call bound the payload: the blocks
   travel to the provider with the result."
  [{:keys [images]} cwd x]
  (let [block (image-arg cwd x)]
    (when (> (count (:data block)) max-image-bytes)
      (throw (ex-info (str "emit-image: image exceeds "
                           (bash-out/format-size max-image-bytes) " of base64")
                      {:type :run-code/image-too-large})))
    (when (>= (count @images) max-images)
      (throw (ex-info (str "emit-image: at most " max-images " images per call")
                      {:type :run-code/too-many-images})))
    (swap! images conj block)
    nil))

(defn- sandbox-fns [cwd writers emit-state]
  {'cwd (fn [] cwd)
   'env (fn [k] (System/getenv (str k)))
   'now (fn [] (System/currentTimeMillis))
   'sleep (fn [ms] (Thread/sleep (long ms)))
   'emit (fn [v] (emit! emit-state v))
   'emit-image (fn [x] (emit-image! emit-state cwd x))
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
   discovery is a bridge-local read of the active set, never a dispatch.
   Each entry carries the tool's model-facing declaration (name, description,
   parameters), so a script chooses a call without one describe per candidate;
   :execute and the UI-only :label never leave the registry."
  [surface]
  {'list (fn []
           (mapv #(select-keys % [:name :description :parameters])
                 (vals surface)))
   'describe (fn [name]
               (if-let [t (get surface (tool-name name))]
                 (select-keys t [:name :description :parameters])
                 {:is-error true
                  :content (str "Unknown tool: " name ". Active tools: "
                                (str/join ", " (keys surface)))}))})

(def ^:private bundled-lib-namespace?
  "The fixed bundled extension set's membership predicate
   (kmet.app.extensions.libs, bundled-library-namespace?), resolved lazily:
   require-host-namespaces! loads the set on the first script call, and the
   delay keeps its libraries off the app's startup path. A script can then
   require the same copies extension contexts share — rewrite-clj.*,
   edamame.core, cljfmt.*, parinferish.core (the set's clojure.* members ride
   copyable-namespace?'s clojure.* clause)."
  (delay @(requiring-resolve 'kmet.app.extensions.libs/bundled-library-namespace?)))

(defn- copyable-namespace?
  "True for a loaded host namespace the full-babashka surface copies: the
   babashka/clojure vocabulary, minus clojure.core (SCI's builtin core is the
   interpreter's own and must not be shadowed) and babashka.nrepl (a host
   server, not a script API), plus the fixed bundled extension set
   (kmet.app.extensions.libs — the same shared copies extension contexts get,
   so a script's structural-editing and formatting vocabulary matches an
   extension's). kmet's own namespaces are never in this set."
  [ns-sym]
  (let [n (str ns-sym)]
    (and (or (str/starts-with? n "clojure.")
             (str/starts-with? n "babashka.")
             (@bundled-lib-namespace? n))
         (not= n "clojure.core")
         (not (str/starts-with? n "babashka.nrepl")))))

(defn- copy-ns-publics
  "The SCI namespace map for host namespace NS-SYM: every public var's value
   re-wrapped in a fresh sci var (macros keep their :macro meta), so a script
   can `require` it just like in babashka. Fresh vars per call keep a script's
   def/redefinitions call-local instead of leaking into the host namespace."
  [ns-sym]
  (let [sns (sci/create-ns ns-sym)]
    (reduce-kv
     (fn [m k v]
       (try
         (assoc m k (sci/new-var k @v (assoc (meta v) :ns sns :name k)))
         (catch Throwable _ m)))
     {}
     (ns-publics ns-sym))))

(defn- full-babashka-namespaces
  "Map of the host's babashka/clojure namespaces, copied for the per-call
   context — the `full babashka script` surface: clojure.pprint, java.io,
   clojure.zip, babashka http/process/… all `require`-able, unlike the old
   fixed capability table. Rebuilt each call so a script's redefinitions stay
   call-local; the copy costs a few ms (measured ~3ms copy + ~9ms sci/init)."
  []
  (into {}
        (for [n (all-ns)
              :let [s (ns-name n)]
              :when (copyable-namespace? s)]
          [s (copy-ns-publics s)])))

;; referenced only from the :default reader branch, invisible to the bb lint view
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private jvm-class-names
  "The JVM sandbox's class surface: the java.lang core a script expects, the
   java.io/java.util types the tool's contract names, and nothing else. bb
   gets the whole host table (babashka.classes/all-classes); the JVM has no
   such enumeration, and SCI's own JVM defaults resolve only java.lang.Exception
   — so this list is explicit. Extend it when a script needs a new JDK type."
  '[java.lang.System java.lang.Thread java.lang.Runtime java.lang.Math
    java.lang.Object java.lang.String java.lang.StringBuilder
    java.lang.Character java.lang.Boolean java.lang.Byte java.lang.Short
    java.lang.Integer java.lang.Long java.lang.Float java.lang.Double
    java.lang.Number java.lang.CharSequence java.lang.Comparable
    java.lang.Iterable java.lang.Class java.lang.Enum java.lang.Cloneable
    java.lang.Throwable java.lang.Exception java.lang.RuntimeException
    java.lang.IllegalArgumentException java.lang.IllegalStateException
    java.lang.NumberFormatException java.lang.UnsupportedOperationException
    java.lang.IndexOutOfBoundsException java.lang.StackTraceElement
    java.io.File java.io.IOException java.io.Reader java.io.Writer
    java.io.InputStream java.io.OutputStream java.io.BufferedReader
    java.io.BufferedWriter java.io.StringReader java.io.StringWriter
    java.util.Date java.util.UUID java.util.List java.util.Map java.util.Set
    java.util.ArrayList java.util.HashMap java.util.HashSet java.util.Collections])

(defn- host-class-map
  "symbol → Class under both its full and simple name, so
   System/Thread/java.io.*/Throwable/… resolve as in a babashka script. bb
   answers its whole class table, the JVM an explicit java.lang/java.io/
   java.util subset (SCI's JVM defaults alone resolve only Exception), and
   Jolt nothing — its SCI exposes no class table."
  []
  #?(:bb
     (try
       (let [all-classes (deref (requiring-resolve 'babashka.classes/all-classes))]
         (reduce
          (fn [m c]
            (try
              (let [full (symbol (.getName c))
                    simple (symbol (.getSimpleName c))]
                (cond-> (assoc m full c)
                  (not= full simple) (assoc simple c)))
              (catch Throwable _ m)))
          {}
          (all-classes)))
       (catch Throwable _ {}))
     :jolt {}
     :default
     (reduce (fn [m name-sym]
               (try
                 (let [c (Class/forName (str name-sym))
                       simple (symbol (.getSimpleName c))]
                   (assoc m name-sym c simple c))
                 (catch Throwable _ m)))
             {}
             jvm-class-names)))

(def ^:private extra-core-vars
  "Host clojure.core vars copied into the sandbox core as sci vars (macros keep
   their :macro meta), so a script that reaches for them behaves like a
   babashka script. `slurp`/`spit`/`file-seq`/`pmap` are injected separately
   with cwd resolution."
  '[future future-call])

(defn- extra-core-vars-map
  "The extra-core-vars sci-var map for the sandbox's clojure.core namespace."
  []
  (let [sns (sci/create-ns 'clojure.core)
        pubs (ns-publics 'clojure.core)]
    (into {}
          (keep (fn [s]
                  (when-let [v (get pubs s)]
                    (try
                      [s (sci/new-var s @v (assoc (meta v) :ns sns :name s))]
                      (catch Throwable _ nil)))))
          extra-core-vars)))

(defn- require-host-namespaces!
  "Host-require before value-sharing: babashka preloads babashka.fs/process,
   Jolt does not. kmet.app.extensions.libs is the fixed bundled extension set
   (its requires are the enumeration), loaded here so the copy sees it; the
   set's membership rule rides copyable-namespace?'s lazy resolve."
  []
  (require 'babashka.fs)
  (require 'babashka.process)
  (require 'kmet.app.extensions.libs)
  nil)

(defn- unreadable-namespace
  "The boundary error for a namespace the sandbox cannot serve."
  [namespace]
  (throw (ex-info (str "run_code cannot load namespace " namespace
                       "; it is not part of the sandbox's script surface"
                       " (kmet's own namespaces are excluded)")
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
   the host class table (a full babashka script resolves classes), load-fn and
   interrupt-fn."
  [namespaces interrupt-fn]
  (let [holder (atom nil)
        ctx (sci/init {:namespaces namespaces
                       :classes (host-class-map)
                       :features (if (host/jolt?) #{:jolt} #{:bb})
                       :load-fn (sandbox-load-fn holder)
                       :interrupt-fn interrupt-fn})]
    (reset! holder ctx)
    ctx))

(defn- context-namespaces
  "The per-call sandbox namespaces. The host's babashka/clojure namespaces are
   copied in first (the full-babashka surface), then the sandbox's own
   overrides layer on top: SCI core patches (slurp/spit/file-seq/pmap), json,
   the cwd/pid-tracking process wrappers, the per-call bridge and discovery
   fns, the cwd/emit/spawn helpers, and the bare `emit`/`sh`. Built fresh every
   call, so a def in one script is invisible to the next, and the context's
   :load-fn (sandbox-load-fn) makes an unknown require fail with the boundary
   error while a reload of a known namespace is a no-op."
  [surface cwd bridge writers emit-state]
  (require-host-namespaces!)
  (let [process (process-fns cwd)]
    (merge-with
     merge
     (full-babashka-namespaces)
     (cond-> {'clojure.core (merge {'pmap (deref #'pmap)}
                                   (core-fns cwd)
                                   (extra-core-vars-map))
              ;; kmet.libs.json under its real name and the short `json` alias
              ;; scripts would otherwise have to require
              'kmet.libs.json (shared-fns 'kmet.libs.json)
              'babashka.process process
              'sandbox (sandbox-fns cwd writers emit-state)
              'tools (merge (discovery-fns surface) bridge)
              'user {'emit (fn [v] (emit! emit-state v))
                     'emit-image (fn [x] (emit-image! emit-state cwd x))}}
       ;; the cwd-defaulting sh is preloaded bare alongside the aliases
       (contains? process 'sh) (assoc-in ['user 'sh] (get process 'sh))))))

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
  "One pool worker: block on QUEUE, run a task, repeat. A task body that
   throws is logged here so the worker survives and the pool keeps its size;
   a call's own failures are settled by the pipeline as error results."
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
  ;;
  ;; Kept process-wide on purpose (landing 9b's decision): this is a *bound*,
  ;; not a setting. Everything an inner call needs to know — the cwd, the
  ;; shell, the skill resolver, the cancel signal, the hooks — travels per
  ;; submission in the invocation pipeline's :bindings, so two agents sharing
  ;; the pool still run in their own cwd and shell. A pool per agent would
  ;; multiply platform threads by the number of agents (the thread *cap* is
  ;; the reason the pool exists), and a per-agent pool would have to be
  ;; disposed with its agent or leak its workers. Bounding concurrency
  ;; process-wide is what a server of N connections wants anyway.
  (delay
    (let [q (java.util.concurrent.LinkedBlockingQueue.)]
      (dotimes [_ bridge-worker-count] (start-bridge-worker! q))
      q)))

(defn- dispatch!
  "Gate NAME against the surface, submit the call to the shared bridge pool,
   and return the promise the script derefs. Unknown/inactive names settle
   immediately with {:is-error true} and the active list. Calls have no
   ordering guarantee between themselves; they overlap on the shared pool.
   There is no :sequential barrier inside a script, because that classifier
   belongs to the loop's own batches. The pool worker conveys no dynamic
   bindings, so the bridge passes the run's cancel signal, session env, shell,
   skill resolver and cwd as the invocation pipeline's :bindings (bash reads
   *cancel-signal* and *shell*; read/write/edit resolve against *cwd*). The
   call runs through the shared pipeline (kmet.app.tools.invoke) with the run's
   tool hooks (*tool-hooks*): a blocked call settles with the hook's reason
   without executing, and the after hook runs for blocked calls too (loop
   parity). Inner calls carry a synthetic tool-call id and the turn's
   assistant message. A call picked up after the script aborted — or whose
   before hook ran past the abort — is settled as cancelled and never
   executes: the pool queue cannot fire side effects past the deadline."
  [{:keys [surface execute-tool signal ctx cwd session-env-fn shell skill-resolver hooks
           assistant-message trace on-update]} name args]
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
                          :content (str "run_code cancelled before " name " ran")}))
            run
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
                                                        :shell shell
                                                        :skill-resolver skill-resolver
                                                        :cwd cwd})))
                          result (invoke/finish-tool-call
                                  (assoc call :after-hook (:after hooks))
                                  executed)]
                      (try
                        (swap! trace assoc-in [idx]
                               (cond-> {:tool name
                                        :ok (not (:is-error result))}
                                 (:is-error result)
                                 (assoc :error (preview (:content result)))))
                        (catch Throwable _ nil))
                      (deliver p result))))))]
        (.offer @bridge-queue run)
        p))))

(defn- bridge-fns [opts]
  {'call (fn [name & [args]] (dispatch! opts name args))})

;; ─── Result assembly ──────────────────────────────────────────────────────

(defn- truncation-notice [t]
  (str "[run_code output truncated: " (:total-lines t) " lines / "
       (bash-out/format-size (:total-bytes t))
       " total, showing the last " (:shown-lines t)
       " lines. Print less or return distilled data instead.]"))

(defn- bound-content
  "Cap BODY at the per-call byte/line budget (bash-output's tail truncation
   — the end holds the return value). TOTALS carries the capture's full
   byte/line counts, which the retained tail no longer knows."
  [body totals max-bytes max-lines]
  (let [t (bash-out/truncate-tail body
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
  "Build the tool result from the eval outcome and the emitted output. `emit`
   is the curated text channel; when a successful script emitted nothing its
   final value is reported instead (the REPL rule), while captured stdout and
   stderr stay absent. Emitted images travel beside the text as the result's
   :images (the transcript's count line reads :details :images). ABORT-REASON
   wins when set: a script unwound by the interrupt (however it reports, e.g. a
   catch that rethrows) cannot outlive its deadline. TIMEOUT-MS nil = no
   deadline (bash parity) and the result's :timeout is nil; ELAPSED-MS is the
   measured wall clock."
  [{:keys [res emitted images timeout-ms trace abort-reason elapsed-ms
           output-bytes output-lines capture-bytes]}]
  (let [status (or abort-reason (:status res))
        error-text (case status
                     :timeout (str "run_code timed out after " (ms->sec timeout-ms) "s")
                     :aborted "run_code aborted"
                     :output-limit (str "run_code output exceeded "
                                        (bash-out/format-size capture-bytes)
                                        " — stopped")
                     :error (:error res)
                     nil)
        parts (cond-> (vec emitted)
                error-text (conj error-text))
        ;; a silent success reports its final value (the REPL rule); emit is
        ;; the curated channel, so a non-empty emit never adds it
        value-text (when (and (empty? parts) (= :ok status) (some? (:value res)))
                     (try (format-value (:value res))
                          (catch Throwable _ nil)))
        body (cond
               (seq parts) (str/join "\n" parts)
               value-text value-text
               :else "(no output)")
        totals {:bytes (bash-out/byte-length body) :lines (text-lines body)}
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
                        (seq calls) (assoc :calls calls)
                        (seq images) (assoc :images (count images)))}
      truncation (assoc :truncation truncation)
      (seq images) (assoc :images images))))

(declare run-code*)

(defn- script-realm
  "The realm the script's inner tool calls execute in: a child of the run's
   ctx blocked for the names the agent's `:sandbox` value names, so a tool a
   script calls cannot read the composition's services from the ctx it was
   handed (`kmet.app.sandbox`).

   nil when there is nothing to add — the caller passed no run ctx (extension
   code calling the tool outside a run), or the value isolates nothing (a
   composition that said so)."
  [ctx]
  (when-let [base (:srce-ctx ctx)]
    (let [realm (sandbox/realm base (or (srce/get base :sandbox)
                                        sandbox/default-value))]
      (when-not (identical? realm base) realm))))

(defn- run-code
  "RUN-CODE* under the run's sandbox realm. The realm exists for the call's
   lifetime and is detached when the script returns; a fire-and-forget inner
   call still holds the ctx value, which keeps resolving exactly as it did."
  [opts]
  (let [realm (script-realm (:ctx opts))]
    (try
      (run-code* (if realm (update opts :ctx assoc :srce-ctx realm) opts))
      (finally
        (when realm
          ;; a detach failure is a leak in the tree, not the script's result:
          ;; reported, never thrown over the call
          (try (srce/detach! realm)
               (catch Throwable t
                 (debug/log "run_code: sandbox realm detach failed: " t))))))))

(defn- run-code*
  [{:keys [code timeout limits signal ctx on-update
           get-all-tools get-contributed-tools select-tools execute-tool]}]
  (let [timeout-ms (normalize-timeout timeout)
        ;; monotonic: this origin measures both the deadline and the
        ;; :elapsed-ms reported in the result
        started-at (concurrent/monotonic-ms)
        cwd (tool-util/cwd)
        session-env-fn bash-tool/*session-env-fn*
        shell bash-tool/*shell*
        skill-resolver read-tool/*skill-resolver*
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
        ;; the script's reported output channels: emitted text and images
        emitted (atom [])
        emitted-bytes (atom 0)
        images (atom [])
        deadline (when timeout-ms (+ started-at timeout-ms))
        trace (atom [])
        emit-state {:emitted emitted
                    :emitted-bytes emitted-bytes
                    :images images
                    :on-update on-update
                    :abort abort
                    :capture-limit (:capture-bytes limits)}
        bridge (bridge-fns {:surface surface
                            :execute-tool execute-tool
                            :signal cancelled
                            :ctx ctx
                            :cwd cwd
                            :session-env-fn session-env-fn
                            :shell shell
                            :skill-resolver skill-resolver
                            :hooks hooks
                            :assistant-message assistant-message
                            :on-update on-update
                            :trace trace})
        fork-ctx (sandbox-context (context-namespaces surface cwd bridge writers emit-state)
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
      (finish-capture! capture)
      (let [res (or @result {:status (or @abort :timeout)})]
        (assemble-result {:res res
                          :emitted @emitted
                          :images @images
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
  (str "Run a Clojure script to work over the environment and report a digest. Use it when the intermediate output would flood the context — scan or transform many files, run a search→read→verify chain, compute counts, matches or a summary — instead of chaining bash cat/head/rg. Use bash for one small command and read for one file.\n\n"
       "Inside the script the local primitives are already there and return values, not rendered text: (slurp path), fs/* (babashka.fs), the bare (sh ...), p/*, json (kmet.libs.json), and (cwd), (env k), (now), (sleep ms), (spawn f).\n\n"
       "Call a tool from the script only when its result is too large to read while the useful part is small — a web fetch, an MCP call: @(tools/call \"name\" args) settles with {:content :is-error :details :truncation :images}; check :is-error, and the inner result never enters the conversation. (tools/list) returns the callable tools with their descriptions and parameters; (tools/describe \"name\") returns one tool's declaration.\n\n"
       "Report with (emit value), or end the script on the value you want reported: a successful script that emits nothing reports its final value. Strings print raw, other data pr-str, and stdout and stderr are always discarded. (emit-image \"shot.png\") attaches a supported image file (up to 3 MiB) or an {:data <base64> :mime-type <str>} block; at most 8.\n\n"
       "Sandbox: Clojure; on babashka a full babashka surface, on the JVM the java.lang/java.io/java.util subset. Require clojure.*/babashka.* namespaces or the bundled libs (rewrite-clj, cljfmt, edamame, parinferish); System/java.io.*/Throwable resolve on babashka (Jolt's SCI exposes no class table). Aliases: fs, str/set/edn/walk, json, p, sh, tools, sandbox. :timeout is seconds (0/omitted = none). Output is capped at 16 KiB/2000 lines; :max-output-bytes raises it to 50 KiB."))

(defn title
  "Quiet one-liner body for the run_code tool: `run $ ` and the first
   non-blank code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (when-let [line (text/title-line code)]
      (str "run $ " line))))

(defn create-tool
  "Build the script Tool record. OPTS wires the registry seams (the
   `:tool/run_code` row builds the composition's record via `definition`;
   tests may build their own):
     :get-all-tools — 0-arg registry tool map (the model's tools)
     :get-contributed-tools — 0-arg extension-contributed sandbox tool map
                              (contributed sandbox sources; optional, defaults to none)
     :select-tools  — (fn [all {:keys [enabled contributed exclude]}]) — the
                      registry's surface filter, so a script's callable set
                      resolves exactly like the loop's schema
     :execute-tool  — (fn [name args opts]) dispatch"
  [{:keys [get-all-tools get-contributed-tools select-tools execute-tool]}]
  (tool/make-tool
   :name "run_code"
   :label "Run code"
   :description description
   :prompt-snippet "Script the environment (fs/slurp/sh) and emit a compact digest"
   :prompt-guidelines
   ["Use run_code when a command's output would flood the context: compute inside the script and (emit) only the digest — counts, matches, the lines that matter — instead of chaining bash cat/head/rg; println is discarded (emit the digest, or end on the value you want reported)."
    "Inside a script prefer (slurp path), fs/* and the bare (sh ...) for local work over the bash and read tools — they return values, not rendered text — and call a tool only when its result is too large to read (a web fetch, an MCP call), emitting the filtered part: @(tools/call \"name\" args)."
    "When the answer is an image (a screenshot, a chart, a rendered page), attach it with (emit-image path) rather than describing it."]
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

;; ─── The tool and its row ──────────────────────────────────────────────────

(defn definition
  "The `run_code` tool's record for HANDLE — the table it lists and dispatches
   through, so a script's callable surface is the table its own agent sees."
  [handle]
  (create-tool {:get-all-tools #(registry/get-all-tools handle)
                :get-contributed-tools #(registry/get-contributed-tools handle)
                :select-tools registry/select-tools
                :execute-tool #(registry/execute-tool handle %1 %2 %3)}))

(srce/defplugin plugin
  {:name :tool/run_code
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx (definition (srce/get ctx :tools)) config))})

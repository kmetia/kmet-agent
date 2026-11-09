(ns kmet.app.tools.script
  "The script sandbox shared by `codemode` and `eval` (design:
   docs/architecture.md § Tools and the script sandbox).

   A call builds a per-call state (`prepare`), seeds the sandbox namespaces
   into an SCI context (`base-namespaces`, `call-namespaces`, `seed!`), and
   runs the code on a daemon thread whose SCI :interrupt-fn aborts interpreted
   code on the run signal or the deadline. `run` owns the capture files, the
   wait, the sandbox realm and the result assembly; a caller that keeps its
   context (`eval`) supplies `:sci-ctx` and the `:interrupt` holder it built
   the context with, and `run` re-arms and seeds it per call.

   The context carries the capability namespaces and the script's
   emit/capture channels; *out*/*err* are captured to bounded temp files,
   streamed through on-update, and assembled into the result (a 16 KiB /
   2000-line default budget, configurable per call, with a bounded capture
   tail). Unless the caller opts out with `:tools? false`, it also carries the
   `tools` namespace — discovery (tools/list, tools/describe), the per-call
   bridge (async promises over execute-tool, drained by a shared bounded
   worker pool) with its result readers and one var per active tool. `eval`
   opts out: its sandbox has no tools namespace and cannot call a tool."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.app.sandbox :as sandbox]
            [kmet.app.tools.bash-output :as bash-out]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.edit :as edit-tool]
            [kmet.app.tools.invoke :as invoke]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.tools.write :as write-tool]
            [kmet.libs.clj-edit :as clj-edit]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.host :as host]
            [kmet.libs.process :as kprocess]
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
  "Cap on images attached to one script result."
  8)

(def ^:private tool-max-output-bytes bash-out/DEFAULT-MAX-BYTES)
(def ^:private tool-max-output-lines bash-out/DEFAULT-MAX-LINES)

(def ^:private default-output-bytes (* 16 1024))
(def ^:private default-capture-bytes (* 1 1024 1024))
(def ^:private max-tail-bytes (* 128 1024))
;; Bound on printed collections (the script's own printing and the return
;; value). Generous on purpose: it exists to stop an unbounded seq from
;; printing forever past the deadline, not to second-guess a script's pr-str.
(def ^:private print-length-limit 100000)
(def ^:private print-level-limit 30)
(def ^:private update-throttle-ms 100)

(def ^:private capture-poll-ms 25)
(def ^:private cancel-poll-ms 100)
(def ^:private interrupt-grace-ms 1500)
(def ^:private error-preview-chars 300)
(def ^:private error-preview-lines 3)
(def script-tool-names
  "The script tools themselves: neither can start the other, and neither is
   callable from inside a script."
  #{"codemode" "eval"})

(def core-summary
  "The model-facing summary of the eval sandbox surface, labelled by
   `(eval/help)`; the summary itself stays host-neutral."
  (str "aliases fs, str, set, edn, walk, json and p; cwd-resolving slurp, "
       "spit and file-seq; core verbs, also referred unqualified: read, write, "
       "edit, bash, sh, cwd, env, now, sleep, emit, emit-image and spawn "
       "(core/<verb> stays available); the core, clj and eval namespaces; "
       "clojure.*, babashka.*, the bundled libs (rewrite-clj, "
       "cljfmt, edamame, parinferish) and the kmet.libs ports (json, http, "
       "yaml, markdown) are require-able."))

(def core-refer-verbs
  "The core verbs the sandbox also refers unqualified. They are the same
   functions the `core` namespace installs, so `(emit v)` and `(core/emit v)`
   are one call; `read` deliberately shadows `clojure.core/read`."
  '[read write edit bash sh cwd env now sleep emit emit-image spawn])

(def alias-prelude
  "The aliases and core refers every script gets without its own require — the
   standard Clojure and babashka vocabulary plus the bare core verbs.
   Evaluated once per fork, before the user code, as a separate eval so the
   script's own line numbers stay intact; `require` resolves the namespaces
   the context was built with."
  (str "(require '[babashka.fs :as fs]"
       " '[clojure.string :as str]"
       " '[clojure.set :as set]"
       " '[clojure.edn :as edn]"
       " '[clojure.walk :as walk]"
       " '[kmet.libs.json :as json]"
       " '[babashka.process :as p])"
       "\n(require '[core :refer " (pr-str core-refer-verbs) "])"))

(defn alias-source
  "Enter NS and install the standard aliases and core refers, for a stored
   definition evaluated outside a real call. A rebuild replays this per
   definition's namespace; the alias prelude a real call runs lives in the
   sticky namespace and is not part of the kept source."
  [ns]
  (str "(ns " ns ")\n" alias-prelude))

;; ─── Run-level context ────────────────────────────────────────────────────

(def ^:dynamic *enabled-tools-fn*
  "0-arg fn returning the run's enabled tool-name set (nil = all), bound by
   kmet.app.loop next to bash-tool/*cancel-signal*: a script's callable
   surface is the registry filtered to this set. nil outside the loop's run
   paths (extension code calling execute-tool directly) — then every
   registry tool is callable."
  nil)

(def ^:dynamic *tool-hook*
  "Bound by kmet.app.loop next to *enabled-tools-fn*: the run's tool chain —
   the same `(fn [op next])` the loop's own batches run. nil = no hooks
   (extension code calling the tool outside a loop run).
   A scripted inner call carries a synthetic :tool-call-id and the turn's
   :assistant-message (from *assistant-message*); a settled call's :terminate
   rides through to the script's result — an inner call has no batch to stop."
  nil)

(def ^:dynamic *tool-check*
  "Bound by kmet.app.loop next to *tool-hook*: the run's permission check,
   `(fn [op])` → nil when the call may proceed, else the block result. The
   agent's own ctx — never the sandbox realm's, which is the boundary the
   script runs behind and not the ctx its policy lives on. nil = no gate."
  nil)

(def ^:dynamic *assistant-message*
  "The assistant message whose tool-call batch is running — bound by
   kmet.app.loop around execute-tool-calls! so a script's inner calls carry
   the same :assistant-message in their hook payload as the outer script
   call. nil outside the loop (extension code calling the tool directly);
   the key is then omitted from the hook payload."
  nil)

(def ^:dynamic *call-state*
  "The prepared state of the script call currently executing on this thread.
   The per-call namespace functions are stable and read this, so a definition
   kept in a persistent eval environment uses the current call's emit, cwd,
   process wrappers and tools instead of the call that compiled it."
  nil)

(defn- call-cwd []
  (or (:cwd *call-state*) (tool-util/cwd)))

(defn- tool-error-type
  "The tool's error keyword for SUFFIX — `:codemode/output-limit` for
   codemode, `:eval/…` for eval. The tool name is the only part the type
   varies by; the suffix is the sandbox layer's."
  [tool suffix]
  (keyword (str (str/replace tool "_" "-") "/" suffix)))

(defn- require-tool!
  "A script run names its tool: the name is in every message and error type,
   so a missing one is a caller bug reported here, not a nil in a result."
  [tool]
  (when-not (and (string? tool) (seq tool))
    (throw (ex-info "a script run needs :tool — the tool name used in messages"
                    {:type :script/missing-tool :tool tool})))
  tool)

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

(defn resolve-limits
  "Resolve the per-call budgets once, before the script starts. Only the
   result byte budget is callable; the line and capture budgets are fixed."
  [max-bytes]
  {:bytes (normalize-limit max-bytes
                           default-output-bytes
                           tool-max-output-bytes)
   :lines tool-max-output-lines
   :capture-bytes default-capture-bytes})

(defn- combined-signal
  "The cancel signal a script's inner calls observe: true when the run's
   cancel signal (Escape) fired or the script itself aborted
   (timeout/output-limit). Read-only (kmet.libs.concurrent/or-signal) — tools
   poll it (bash's poller kills the process tree), and the bridge checks it
   before a call starts and again after the chain settled, so an aborted
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
   Writer) and stream nothing. Used by the eval thread and by core/spawn,
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
  (let [dir (str (temp-root) "/kmet-script-" (System/nanoTime))
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
                                     (debug/log "script capture update failed: " t)))))))
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

(defn- core-fns []
  ;; SCI's builtin clojure.core lacks slurp/spit/file-seq (the extensions'
  ;; build-context-namespaces carries the same patches) — inject the host
  ;; fns, resolving relative paths against the current call's cwd like the
  ;; tools. The functions read *call-state* at call time so a kept definition
  ;; follows the current call.
  {'slurp (fn [f & opts] (apply slurp (path-in-cwd (call-cwd) f) opts))
   'spit (fn [f content & opts] (apply spit (path-in-cwd (call-cwd) f) content opts))
   'file-seq (fn [dir] (file-seq (fs/file (path-in-cwd (call-cwd) dir))))})

(defn- split-token-options
  "Normalize token-form arguments with trailing keyword options into a trailing
   opts map, so `(sh \"cmd\" :in text)` reaches babashka.process as options
   rather than command tokens. Returns ARGS unchanged without a keyword."
  [args]
  (let [idx (first (keep-indexed (fn [i a] (when (keyword? a) i)) args))]
    (when (and idx (not (even? (- (count args) idx))))
      (throw (ex-info "options need a value after the last option" {})))
    (if idx
      (concat (take idx args) [(apply hash-map (drop idx args))])
      args)))

(defn- process-fns
  "babashka.process with the current call's cwd as the default :dir, pid
   tracking (kmet's shutdown kills tracked children) and cancel-signal
   killing for `process`, `sh` and `shell` (Escape stops a bare shell command;
   host code is otherwise past SCI's interrupt). babashka.process takes opts
   FIRST for token commands and LAST for a command vector — the wrapper
   normalizes both shapes. The default dir is read at call time, so a kept
   definition follows the current call."
  []
  (let [fns (shared-fns 'babashka.process)
        with-dir (fn [f]
                   (fn [& args]
                     (let [cwd (call-cwd)
                           [a & more] args]
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
                         (let [args (split-token-options args)
                               last-arg (last args)]
                           (if (map? last-arg)
                             (apply f (merge {:dir cwd} last-arg) (butlast args))
                             (apply f {:dir cwd} args)))))))
        alive? (get fns 'alive?)
        track! (fn [p]
                 ;; the signal is captured here, not read in the future: a
                 ;; future does not convey the call's dynamic bindings on Jolt
                 (let [signal (:cancelled *call-state*)]
                   (try
                     (when-let [pid (kprocess/process-pid p)]
                       (kprocess/track-pid! pid)
                       ;; one watcher per process: untrack on exit and kill the
                       ;; tree on cancel/abort; a spawned command would
                       ;; otherwise outlive Escape, which SCI cannot reach
                       (when alive?
                         (future
                           (loop []
                             (cond
                               (and signal @signal)
                               (kprocess/kill-process-tree! pid)
                               (try (alive? p) (catch Throwable _ false))
                               (do (Thread/sleep 100)
                                   (recur))
                               :else nil))
                           (kprocess/untrack-pid! pid))))
                     (catch Throwable _ nil))
                   p))
        tracked (fn [f]
                  (fn [& args] (track! (apply f args))))
        process* (get fns 'process*)
        parse-args (get fns 'parse-args)
        check (get fns 'check)
        destroy-tree (get fns 'destroy-tree)
        start (fn [{:keys [opts] :as parsed} defaults]
                (track! (process* (assoc parsed :opts (merge defaults opts)))))
        ;; `sh` and `shell` block inside babashka.process, so their pid is not
        ;; visible to a wrapper around the call; start with process*, watch,
        ;; then block. `sh` mirrors its :out/:err defaults; `shell` mirrors
        ;; inherited I/O, destroy-tree, and throw-on-exit unless :continue.
        sh* (when (and process* parse-args)
              (fn [& args]
                @(start (parse-args args) {:out :string :err :string})))
        shell* (when (and process* parse-args check)
                 (fn [& args]
                   (let [{:keys [opts] :as parsed} (parse-args args)
                         proc @(start parsed {:in :inherit
                                              :out :inherit
                                              :err :inherit
                                              :shutdown destroy-tree})]
                     (if (:continue opts) proc (check proc)))))]
    (cond-> fns
      (contains? fns 'sh) (assoc 'sh (with-dir (or sh* (get fns 'sh))))
      (contains? fns 'shell) (assoc 'shell (with-dir (or shell* (get fns 'shell))))
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
  [{:keys [tool emitted emitted-bytes on-update abort capture-limit]} v]
  (let [s (format-value v)
        total (swap! emitted-bytes + (bash-out/byte-length s))]
    (when (and capture-limit (> total capture-limit))
      (compare-and-set! abort nil :output-limit)
      (throw (ex-info (str tool " output limit exceeded")
                      {:type (tool-error-type tool "output-limit")
                       :reason :output-limit})))
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
                        {:type :script/bad-image}))

        (not (contains? read-tool/supported-image-mime-types mime-type))
        (throw (ex-info (str "emit-image: unsupported image type " mime-type
                             " (supported: "
                             (str/join ", " (sort read-tool/supported-image-mime-types))
                             ")")
                        {:type :script/bad-image :mime-type mime-type}))

        :else {:data data :mime-type mime-type}))

    (string? x)
    (let [path (path-in-cwd cwd x)
          size (try (fs/size path) (catch Throwable _ nil))]
      (when (and size (> size max-image-file-bytes))
        (throw (ex-info (str "emit-image: " x " is "
                             (bash-out/format-size size) " — the cap is "
                             (bash-out/format-size max-image-file-bytes))
                        {:type :script/image-too-large :path x})))
      (let [bytes (try (fs/read-all-bytes path) (catch Throwable _ nil))]
        (or (and bytes (read-tool/image-block bytes))
            (throw (ex-info (str "emit-image: not a supported image file: " x)
                            {:type :script/bad-image :path x})))))

    :else
    (throw (ex-info "emit-image expects a path or a {:data :mime-type} block"
                    {:type :script/bad-image :value (str (type x))}))))

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
                      {:type :script/image-too-large})))
    (when (>= (count @images) max-images)
      (throw (ex-info (str "emit-image: at most " max-images " images per call")
                      {:type :script/too-many-images})))
    (swap! images conj block)
    nil))

(defn- emit-with-state
  "Emit through the current call's channel; a build-time definition evaluation
   has no call to report to and emits nothing."
  [v]
  (when-let [state *call-state*]
    (emit! (:emit-state state) v)))

(defn- emit-image-with-state [x]
  (when-let [state *call-state*]
    (emit-image! (:emit-state state) (:cwd state) x)))

(defn- core-namespace-fns []
  {'cwd (fn [] (call-cwd))
   'env (fn [k] (System/getenv (str k)))
   'now (fn [] (System/currentTimeMillis))
   'sleep (fn [ms] (Thread/sleep (long ms)))
   'emit emit-with-state
   'emit-image emit-image-with-state
   ;; a derefable result on a daemon thread (babashka/jolt futures are
   ;; daemon-backed; SCI's own future is unavailable in a value-shared ctx).
   ;; The state and writers are re-bound: a future does not convey SCI's var
   ;; bindings on Jolt, so a spawned println would write to an unbound *out*.
   'spawn (fn [f]
            (let [state *call-state*]
              (future
                (binding [*call-state* state]
                  (if-let [{:keys [out-w err-w]} (some-> state :writers deref)]
                    (eval-with-io out-w err-w f)
                    (f))))))})

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

(defn- current-surface []
  (or (:surface *call-state*) {}))

(defn- discovery-fns
  "tools/list and tools/describe over the current call's surface — discovery
   is a bridge-local read of the active set, never a dispatch. Each entry
   carries the tool's model-facing declaration (name, description,
   parameters), so a script chooses a call without one describe per candidate;
   :execute and the UI-only :label never leave the registry."
  []
  {'list (fn []
           (mapv #(select-keys % [:name :description :parameters])
                 (vals (current-surface))))
   'describe (fn [name]
               (let [surface (current-surface)]
                 (if-let [t (get surface (tool-name name))]
                   (select-keys t [:name :description :parameters])
                   {:is-error true
                    :content (str "Unknown tool: " name ". Active tools: "
                                  (str/join ", " (keys surface)))})))})

(def ^:private bundled-lib-namespace?
  "The fixed bundled extension set's membership predicate
   (kmet.app.extensions.libs, bundled-library-namespace?), resolved lazily:
   require-host-namespaces! loads the set on the first script call, and the
   delay keeps its libraries off the app's startup path. A script can then
   require the same copies extension contexts share — rewrite-clj.*,
   edamame.core, cljfmt.*, parinferish.core (the set's clojure.* members ride
   copyable-namespace?'s clojure.* clause)."
  (delay @(requiring-resolve 'kmet.app.extensions.libs/bundled-library-namespace?)))

(def ^:private script-lib-namespaces
  "The kmet.libs ports a script may require: the portable data boundaries
   (JSON, YAML, Markdown) and the proxy-aware HTTP boundary. They replace the
   babashka-hosted libraries that are not portable, and they are the same
   seams extension contexts get. Every other kmet namespace stays out."
  '#{kmet.libs.http kmet.libs.json kmet.libs.markdown kmet.libs.yaml})

(defn- copyable-namespace?
  "True for a loaded host namespace the sandbox base copies: the
   babashka/clojure vocabulary, minus clojure.core (SCI's builtin core is the
   interpreter's own and must not be shadowed) and babashka.nrepl (a host
   server, not a script API), plus the fixed bundled extension set
   (kmet.app.extensions.libs — the same shared copies extension contexts get,
   so a script's structural-editing and formatting vocabulary matches an
   extension's) and the script library ports (script-lib-namespaces). All
   other kmet namespaces stay out."
  [ns-sym]
  (let [n (str ns-sym)]
    (and (or (str/starts-with? n "clojure.")
             (str/starts-with? n "babashka.")
             (@bundled-lib-namespace? n)
             (contains? script-lib-namespaces ns-sym))
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
  "Map of the sandbox base namespaces: the host's babashka/clojure
   namespaces plus the script library ports, copied for the per-call context —
   the `full babashka script` surface (clojure.pprint, java.io, clojure.zip,
   babashka http/process/… all `require`-able) and the kmet.libs data/HTTP
   seams. Rebuilt each call so a script's redefinitions stay call-local; the
   copy costs a few ms (measured ~3ms copy + ~9ms sci/init)."
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
   script library ports are loaded for the same reason."
  []
  (require 'babashka.fs)
  (require 'babashka.process)
  (require 'kmet.app.extensions.libs)
  (require 'kmet.libs.http)
  (require 'kmet.libs.json)
  (require 'kmet.libs.markdown)
  (require 'kmet.libs.yaml)
  nil)

(defn- unreadable-namespace
  "The boundary error for a namespace the sandbox cannot serve."
  [tool namespace]
  (throw (ex-info (str tool " cannot load namespace " namespace
                       "; it is not part of the sandbox's script surface"
                       " (kmet's own namespaces are excluded)")
                  {:type :script/unreadable :namespace (str namespace)})))

(defn- sandbox-load-fn
  "SCI's :load-fn for the sandbox. SCI consults it even for a `:reload` of an
   already-loaded namespace, so a known namespace gets the no-op empty source
   (the loader the sandbox used before returned its existing handle the same
   way — a reload changed nothing); anything else gets the boundary error."
  [ctx-holder tool]
  (fn [{:keys [namespace]}]
    (if (and @ctx-holder (sci/find-ns @ctx-holder (symbol namespace)))
      {:file (str namespace) :source ""}
      (unreadable-namespace tool namespace))))

(defn- sandbox-context
  "Build the SCI context: NAMESPACES plus the sandbox's features, the host
   class table (a full babashka script resolves classes), load-fn and
   interrupt-fn. A caller that keeps the context (`eval`) builds it once;
   `codemode` builds one per call."
  [namespaces interrupt-fn tool]
  (let [holder (atom nil)
        ctx (sci/init {:namespaces namespaces
                       :classes (host-class-map)
                       :features (if (host/jolt?) #{:jolt} #{:bb})
                       :load-fn (sandbox-load-fn holder tool)
                       :interrupt-fn interrupt-fn})]
    (reset! holder ctx)
    ctx))

(defn base-namespaces
  "The host's babashka/clojure namespaces, copied by value — the part of the
   sandbox surface that does not change between calls, so a kept context
   (`eval`) copies it once and `codemode` copies it per call."
  []
  (require-host-namespaces!)
  (full-babashka-namespaces))

(defn- verb-failed!
  [verb result]
  (throw (ex-info (str (:content result))
                  {:type :script/verb-failed :verb verb :result result})))

(defn read-text
  "Read PATH through the read tool's implementation; OPTS may carry :offset
   and :limit. Throws on a read error; use emit-image for images."
  [path opts signal]
  (let [{:keys [content is-error images]}
        (read-tool/execute (merge {:path (str path)} opts) nil signal nil)]
    (cond
      (seq images)
      (throw (ex-info (str "read reads text; use (emit-image "
                           (pr-str (str path)) ") for images")
                      {:type :script/read-image :path (str path)}))
      is-error
      (throw (ex-info (str content) {:type :script/read-error :path (str path)}))
      :else content)))

(defn write-result
  "Write CONTENT to PATH through the write tool's implementation; returns its
   result map."
  [path content signal]
  (write-tool/write-file path (str content) signal))

(defn edit-result
  "Replace OLD-TEXT with NEW-TEXT in PATH through the edit tool's
   implementation; returns its result map."
  [path old-text new-text signal]
  (edit-tool/edit-file path [{:old-text (str old-text)
                              :new-text (str new-text)}] signal))

(defn write-text
  "Write CONTENT to PATH and return the result content, throwing on error."
  [path content signal]
  (let [result (write-result path content signal)]
    (if (:is-error result) (verb-failed! "write" result) (:content result))))

(defn edit-text
  "Replace OLD-TEXT with NEW-TEXT in PATH and return the result content,
   throwing on error."
  [path old-text new-text signal]
  (let [result (edit-result path old-text new-text signal)]
    (if (:is-error result) (verb-failed! "edit" result) (:content result))))

(defn bash-text
  "Run COMMAND through the bash tool's implementation; OPTS may carry
   :timeout. Throws on error."
  [command opts signal]
  (binding [bash-tool/*cancel-signal* signal]
    (let [result (bash-tool/run-bash (merge {:command (str command)} opts) nil {})]
      (if (:is-error result) (verb-failed! "bash" result) (:content result)))))

(declare tools-namespace)

(defn call-namespaces
  "The per-call half of the sandbox namespaces: SCI core patches
   (slurp/spit/file-seq/pmap), the cwd/pid-tracking process wrappers, the
   `core` namespace (cwd/emit/spawn helpers plus the direct `read`, `write`,
   `edit`, `bash` and `sh` verbs, which call the tool implementations, not the
   tool bridge), the direct `clj/edit-form`, `clj/replace-sexp` and
   `clj/paren-repair` verbs (which call `kmet.libs.clj-edit` directly), and —
   unless the caller opted out with `:tools? false` — the `tools` namespace
   (discovery, dispatch, the result readers and one var per active tool).
   The functions read `*call-state*` at call time, so a kept context's
   definitions follow the current call; `seed!` layers them over
   `base-namespaces` for the call. `:source-namespaces` — the eval source
   layer's current values — merges into the same namespace maps, so a source
   value and a built-in `user` helper share one namespace."
  [{:keys [surface source-namespaces tools?]}]
  (let [tools? (not (false? tools?))
        process (process-fns)
        runtime (core-namespace-fns)
        namespaces (cond-> {'clojure.core (merge {'pmap (deref #'pmap)}
                                                 (core-fns)
                                                 (extra-core-vars-map))
                            'babashka.process process
                            'core (merge runtime
                                         {'read (fn [path & [opts]]
                                                  (read-text path opts
                                                             (:cancelled *call-state*)))
                                          'write (fn [path content]
                                                   (write-text path content
                                                               (:cancelled *call-state*)))
                                          'edit (fn [path old-text new-text]
                                                  (edit-text path old-text new-text
                                                             (:cancelled *call-state*)))
                                          'bash (fn [command & [opts]]
                                                  (bash-text command opts
                                                             (:cancelled *call-state*)))})
                            'clj {'edit-form (fn [args]
                                               (clj-edit/edit-form
                                                (assoc args :cwd (call-cwd))))
                                  'replace-sexp (fn [args]
                                                  (clj-edit/replace-sexp
                                                   (assoc args :cwd (call-cwd))))
                                  'paren-repair (fn [args]
                                                  (clj-edit/paren-repair
                                                   (assoc args :cwd (call-cwd))))}}
                     (contains? process 'sh) (assoc-in ['core 'sh] (get process 'sh))
                     tools? (assoc 'tools (tools-namespace (or surface {}))))]
    (reduce-kv (fn [acc ns-name ns-map]
                 (update acc ns-name #(merge (or % {}) ns-map)))
               namespaces
               (or source-namespaces {}))))

(defn context
  "Build an SCI context over NAMESPACES. The per-call namespaces are
   installed later with `seed!`; INTERRUPT-FN is created from the holder the
   caller keeps (`interrupt-fn`), and TOOL names the tool in boundary errors."
  [namespaces interrupt-fn tool]
  (sandbox-context namespaces interrupt-fn tool))

(defn seed!
  "Install STATE's per-call namespaces into CTX. The fixed namespaces merge
   into what a kept context already carries; `tools` replaces its map instead,
   because its key set follows the call's surface — a tool that left the
   surface must not keep a var in a reused context. A state with `:tools?
   false` installs no `tools` namespace at all."
  [ctx state]
  (let [namespaces (call-namespaces state)]
    (doseq [[ns-name ns-map] namespaces]
      (sci/add-namespace! ctx ns-name ns-map))
    (when-let [tools (get namespaces 'tools)]
      (swap! (:env ctx) assoc-in [:namespaces 'tools] tools))
    ctx))

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
                           :exclude script-tool-names})))

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
  ;;
  ;; Kept process-wide on purpose (landing 9b's decision): this is a *bound*,
  ;; not a setting. Everything an inner call needs to know — the cwd, the
  ;; shell, the skill resolver, the cancel signal, the chain — travels per
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
   call runs through the shared tool point (kmet.app.tools.invoke) with the
   run's chain (*tool-hook*): a hook that settles the call keeps its result and
   nothing executes, exactly as the loop's own batches behave. Inner calls
   carry a synthetic tool-call id and the turn's assistant message. A call
   picked up after the script aborted — or whose hook ran past the abort — is
   settled as cancelled and never executes: the pool queue cannot fire side
   effects past the deadline."
  [{:keys [tool surface execute-tool signal ctx cwd session-env-fn shell skill-resolver hook
           check assistant-message trace on-update]} name args]
  (let [name (tool-name name)
        p (promise)]
    (if-not (contains? surface name)
      (deliver p {:is-error true
                  :content (str "Tool not active in this " tool " call: " name
                                ". Active tools: " (str/join ", " (keys surface)))})
      (let [idx (dec (count (swap! trace conj {:tool name :ok false
                                               :error "incomplete"})))
            cancelled-result
            (fn []
              {:is-error true
               :content (str tool " cancelled before " name " ran")})
            settle-cancelled!
            (fn []
              (try
                (swap! trace assoc-in [idx] {:tool name :ok false :error "cancelled"})
                (catch Throwable _ nil))
              (deliver p (cancelled-result)))
            run
            (fn []
              (if @signal
                (settle-cancelled!)
                (let [;; a call whose hook ran past the abort never executes:
                      ;; the pool queue cannot fire side effects past the deadline
                      execute (fn [op]
                                (if @signal
                                  (cancelled-result)
                                  (invoke/execute-tool-call
                                   execute-tool
                                   {:tool-name name
                                    :args (:args op)
                                    :signal signal
                                    :ctx ctx
                                    :on-update on-update
                                    :tools surface
                                    :bindings {:signal signal
                                               :session-env-fn session-env-fn
                                               :shell shell
                                               :skill-resolver skill-resolver
                                               :cwd cwd}})))
                      result (invoke/tool-point
                              {:chain hook
                               :check check
                               :execute execute}
                              (invoke/tool-op {:tool-name name
                                               :args args
                                               :tool-call-id (str tool "-" idx)
                                               :assistant-message assistant-message
                                               :signal signal}))]
                  (try
                    (swap! trace assoc-in [idx]
                           (cond-> {:tool name
                                    :ok (not (:is-error result))}
                             (:is-error result)
                             (assoc :error (preview (:content result)))))
                    (catch Throwable _ nil))
                  (deliver p result))))]
        (.offer @bridge-queue run)
        p))))

(def ^:private tool-var-name-pattern
  "A tool name the Clojure reader keeps whole — letters, digits and the
   symbol punctuation, not starting with a digit. A name it would mangle or
   reject (`a/b`, `a.b`, a leading digit) is not callable as `(tools/NAME …)`,
   so it keeps `call` as its only handle."
  #"[a-zA-Z_][a-zA-Z0-9_?!*+\-]*")

(def ^:private result-readers
  "Readers over a settled bridge result, so a script does not spell the map out
   at every call site: `ok?` (a result map that did not fail), `text` (its
   :content) and `error` (its :content only when it failed, nil otherwise)."
  {'ok? (fn [r] (and (map? r) (not (:is-error r))))
   'text (fn [r] (:content r))
   'error (fn [r] (when (:is-error r) (:content r)))})

(defn- dispatch-current!
  "Dispatch through the current call's bridge. A definition evaluated outside
   a call (the rebuild) gets an inert promise instead of a stale bridge."
  [name args]
  (if-let [state *call-state*]
    (dispatch! (assoc (:bridge state) :surface (:surface state)) name args)
    (let [p (promise)]
      (deliver p {:is-error true
                  :content "tools are not available outside a script call"})
      p)))

(defn- tools-namespace
  "The `tools` namespace a script sees: discovery, the dispatch fns and the
   result readers in one map, so what a script lists, calls and can name by
   var cannot drift.

   `call` returns the call's promise; `call!` returns the settled result and
   throws when it failed, so a script that cannot continue stops there; and
   every active tool whose name is a plain symbol is also a var
   (`(tools/edit {…})`) that dispatches exactly like `call`. The namespace's
   own names win — they are identities, a tool name is data — so a tool that
   is itself called `text` stays reachable through `call`. Dispatch reads the
   current call's bridge, so a kept definition calls this call's tools."
  [surface]
  (let [;; variadic so `call` is this fn itself, not a wrapper around it
        dispatch (fn [name & [args]] (dispatch-current! name args))
        discovery (discovery-fns)
        dispatch-fns
        {'call dispatch
         'call! (fn [name & [args]]
                  (let [name (tool-name name)
                        result @(dispatch name args)
                        tool-name (or (:tool *call-state*) "eval")]
                    (if (:is-error result)
                      (throw (ex-info (str tool-name ": tool call failed: "
                                           name " — " (:content result))
                                      {:type (tool-error-type tool-name "tool-call-failed")
                                       :tool name
                                       :result result}))
                      result)))}
        tool-vars (into {}
                        (keep (fn [name]
                                (when (re-matches tool-var-name-pattern name)
                                  [(symbol name) (fn [& [args]] (dispatch name args))])))
                        (keys surface))]
    ;; merge order is the shadowing rule: a tool name never displaces one of
    ;; the namespace's own (a tool called `text` stays reachable via `call`)
    (merge tool-vars dispatch-fns result-readers discovery)))

;; ─── Result assembly ──────────────────────────────────────────────────────

(defn- truncation-notice [tool t]
  (str "[" tool " output truncated: " (:total-lines t) " lines / "
       (bash-out/format-size (:total-bytes t))
       " total, showing the last " (:shown-lines t)
       " lines. Print less or return distilled data instead.]"))

(defn- bound-content
  "Cap BODY at the per-call byte/line budget (bash-output's tail truncation
   — the end holds the return value). TOTALS carries the capture's full
   byte/line counts, which the retained tail no longer knows."
  [tool body totals max-bytes max-lines]
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
                     (truncation-notice tool {:total-lines total-lines
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

(defn- error-message [t]
  (str (some-> t class .getSimpleName) ": " (or (ex-message t) (str t))))

;; ─── Interrupt ────────────────────────────────────────────────────────────

(defn interrupt-holder
  "A mutable holder for one context's current call: the combined signal, the
   deadline and the abort reason. SCI's :interrupt-fn is created from the
   holder, so a kept context (`eval`) re-aims it per call with `rearm!`
   instead of rebuilding the context."
  []
  (atom nil))

(defn rearm!
  "Point HOLDER at this call's STATE — the combined :cancelled signal, the
   :deadline and the :abort atom. HOLDER must be the one SCI-CTX's
   :interrupt-fn was created with, or the call's deadline reaches nothing.
   Returns HOLDER."
  [holder state]
  (reset! holder {:signal (:cancelled state)
                  :deadline (:deadline state)
                  :abort (:abort state)})
  holder)

(defn interrupt-fn
  "SCI's :interrupt-fn over HOLDER — called on every interpreted fn/loop
   entry. Sets the abort reason (so the result reports signal/deadline) and
   throws to unwind the interpreter. Host-native code is not interruptible
   (the documented runaway limitation); the host also waits with a timeout."
  [holder tool]
  (fn []
    (let [{:keys [abort signal deadline]} @holder]
      (cond
        (some? @abort)
        (throw (ex-info (str tool " interrupted")
                        {:type (tool-error-type tool "interrupt") :reason @abort}))

        (and signal @signal)
        (do (reset! abort :aborted)
            (throw (ex-info (str tool " interrupted")
                            {:type (tool-error-type tool "interrupt") :reason :aborted})))

        (and deadline (>= (concurrent/monotonic-ms) deadline))
        (do (reset! abort :timeout)
            (throw (ex-info (str tool " interrupted")
                            {:type (tool-error-type tool "interrupt") :reason :timeout})))))))

(defn- form-evaluator
  "The one-form evaluator over SCI-CTX. With NS-ATOM, each form runs in the
   remembered namespace and the namespace it ended in is stored back; without
   it, a form runs in the thread's current namespace (the codemode path)."
  [sci-ctx ns-atom]
  (if ns-atom
    (fn [form]
      (let [{:keys [val ns]} (sci/eval-string+ sci-ctx form {:ns @ns-atom})]
        (reset! ns-atom ns)
        val))
    (fn [form] (sci/eval-string* sci-ctx form))))

(defn eval-source!
  "Evaluate one SOURCE string in a kept SCI context during an environment
   rebuild: the forms start in NS-ATOM's namespace and the namespace they end
   in is stored back. Returns `{:value v}` or `{:error message}` — a broken
   stored definition is data to report, never a reason to fail the call that
   asked for the environment. The caller serializes calls."
  [sci-ctx source ns-atom]
  (try
    (let [{:keys [val ns]} (sci/eval-string+ sci-ctx source {:ns @ns-atom})]
      (reset! ns-atom ns)
      {:value val})
    (catch Throwable t
      {:error (error-message t)})))

(defn- start-eval-thread!
  "Start the daemon eval thread. The prelude runs here, on the eval thread,
   immediately before the user code: SCI's *ns* is per-thread, so aliases
   registered on the caller thread would not necessarily land in the
   namespace the script evaluates in. The prepared STATE is bound as
   *call-state* for the whole run, so kept definitions use this call's
   emit/tools/cwd. Returns {:done promise :result atom}; the thread closes the
   writers in its finally, which ends the readers."
  [sci-ctx code prelude capture ns-atom state]
  (let [done (promise)
        result (atom nil)
        thread (Thread. (fn []
                          (try
                            (let [eval-form (form-evaluator sci-ctx ns-atom)
                                  v (binding [*call-state* state
                                              tool-util/*cwd* (:cwd state)
                                              ;; direct host tool executes from eval
                                              ;; code (extension source verbs)
                                              bash-tool/*cancel-signal* (:cancelled state)
                                              bash-tool/*shell* (get-in state [:bridge :shell])
                                              bash-tool/*session-env-fn* (get-in state [:bridge :session-env-fn])
                                              read-tool/*skill-resolver* (get-in state [:bridge :skill-resolver])]
                                      (eval-with-io (:out-w capture) (:err-w capture)
                                                    (fn []
                                                      (when prelude
                                                        (eval-form prelude))
                                                      (eval-form code))))]
                              (reset! result {:status :ok :value v}))
                            (catch Throwable t
                              (let [reason (:reason (ex-data t))]
                                (reset! result (if reason
                                                 {:status reason}
                                                 {:status :error
                                                  :error (error-message t)}))))
                            (finally
                              (try (.close (:out-w capture)) (catch Throwable _ nil))
                              (try (.close (:err-w capture)) (catch Throwable _ nil))
                              (reset! (:closed? capture) true)
                              (deliver done true)))))]
    (.setDaemon thread true)
    (.start thread)
    {:done done :result result}))

(defn- await-script
  "Wait for DONE, the call's cancel signal, or DEADLINE. DEADLINE nil waits
   for DONE or cancellation. Polls in cancel-poll-ms slices so Escape ends a
   host-blocked script (which SCI's interrupt cannot reach) instead of waiting
   for the model's timeout. A signal that fired by the time DONE resolves
   reports :cancelled even when the script finished. Returns :done,
   :cancelled, or :timeout."
  [done cancelled deadline]
  (loop []
    (let [slice (if deadline
                  (max 1 (min cancel-poll-ms
                              (- deadline (concurrent/monotonic-ms))))
                  cancel-poll-ms)]
      (cond
        (not= ::pending (deref done slice ::pending))
        (if @cancelled :cancelled :done)

        @cancelled :cancelled

        (and deadline (>= (concurrent/monotonic-ms) deadline)) :timeout

        :else (recur)))))

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
  [{:keys [tool res emitted images timeout-ms trace abort-reason elapsed-ms
           output-bytes output-lines capture-bytes extra-details]}]
  (let [status (or abort-reason (:status res))
        error-text (case status
                     :timeout (str tool " timed out after " (ms->sec timeout-ms) "s")
                     :aborted (str tool " aborted")
                     :output-limit (str tool " output exceeded "
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
        {:keys [content truncation]} (bound-content tool body totals output-bytes output-lines)
        calls @trace]
    (cond-> {:content content
             :is-error (not= :ok status)
             ;; :timeout in seconds (the unit it was asked for; nil = no
             ;; deadline), :elapsed-ms the measured total — the UI's Took
             ;; line reads it
             :details (merge extra-details
                             (cond-> {:timeout (some-> timeout-ms ms->sec)
                                      :elapsed-ms elapsed-ms}
                               (not= :ok status) (assoc :error status)
                               (seq calls) (assoc :calls calls)
                               (seq images) (assoc :images (count images))))}
      truncation (assoc :truncation truncation)
      (seq images) (assoc :images images))))

;; ─── The run ──────────────────────────────────────────────────────────────

(defn prepare
  "Resolve the per-call state the sandbox runs on: the callable surface, the
   bridge, the emit/capture channels, the abort/trace atoms, the budgets and
   the dynamic values the loop bound for this run. CWD defaults to the
   runtime cwd; LIMITS is `resolve-limits`' map (or the default). `:tools?
   false` omits the surface and bridge, so the call has no `tools` namespace
   and cannot reach a tool (the `eval` sandbox)."
  [{:keys [tool cwd timeout limits signal ctx on-update
           get-all-tools get-contributed-tools select-tools execute-tool
           source-namespaces tools?]
    :or {tools? true}}]
  (require-tool! tool)
  (let [cwd (or cwd (tool-util/cwd))
        timeout-ms (normalize-timeout timeout)
        limits (or limits (resolve-limits nil))
        live? (atom true)
        on-update (when on-update
                    (fn [evt] (when @live? (on-update evt))))
        surface (when tools?
                  (active-surface select-tools
                                  (get-all-tools)
                                  (if get-contributed-tools (get-contributed-tools) {})
                                  (when *enabled-tools-fn* (*enabled-tools-fn*))))
        abort (atom nil)
        cancelled (combined-signal signal abort)
        emitted (atom [])
        emitted-bytes (atom 0)
        images (atom [])
        trace (atom [])
        writers (atom nil)
        emit-state {:tool tool
                    :emitted emitted
                    :emitted-bytes emitted-bytes
                    :images images
                    :on-update on-update
                    :abort abort
                    :capture-limit (:capture-bytes limits)}
        bridge-opts (when tools?
                      {:tool tool
                       :surface surface
                       :execute-tool execute-tool
                       :signal cancelled
                       :ctx ctx
                       :cwd cwd
                       :session-env-fn bash-tool/*session-env-fn*
                       :shell bash-tool/*shell*
                       :skill-resolver read-tool/*skill-resolver*
                       :hook *tool-hook*
                       :check *tool-check*
                       :assistant-message *assistant-message*
                       :on-update on-update
                       :trace trace})]
    {:tool tool
     :cwd cwd
     :timeout-ms timeout-ms
     :limits limits
     :signal signal
     :cancelled cancelled
     :ctx ctx
     :on-update on-update
     :tools? tools?
     :surface surface
     :source-namespaces (or source-namespaces {})
     :abort abort
     :emitted emitted
     :emitted-bytes emitted-bytes
     :images images
     :trace trace
     :writers writers
     :live? live?
     :bridge bridge-opts
     :emit-state emit-state}))

(defn with-prime-context
  "Seed CTX with the fixed call surface and the standard aliases, then run F
   with a prime call state bound. A stored definition evaluated inside F
   compiles against the same per-call vocabulary a real call gets; the prime
   state gives top-level code a cwd, a discarded emit channel and, when the
   sandbox has one, an inert tool surface until the next call binds the real
   state."
  [ctx state f]
  (seed! ctx state)
  (binding [*call-state* state]
    ;; the aliases belong to the user namespace the rebuild evaluates stored
    ;; definitions in; do not depend on the host thread's last SCI namespace
    (sci/eval-string* ctx (alias-source "user"))
    (f)))

(defn- sandbox-realm
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

(defn- run*
  [{:keys [tool code prelude sci-ctx state interrupt started-at deadline ctx ns-atom]
    :as opts}]
  (let [started-at (or started-at (concurrent/monotonic-ms))
        state (or state (prepare (assoc opts :ctx ctx)))
        timeout-ms (:timeout-ms state)
        deadline (if (contains? opts :deadline)
                   deadline
                   (when timeout-ms (+ started-at timeout-ms)))
        state (assoc state :deadline deadline)]
    (try
      (let [interrupt (or interrupt (interrupt-holder))
            _ (rearm! interrupt state)
            sci-ctx (if sci-ctx
                      sci-ctx
                      (context (base-namespaces)
                        (interrupt-fn interrupt tool)
                        tool))
            _ (seed! sci-ctx state)
            prelude (if (contains? opts :prelude) prelude alias-prelude)
            capture (start-capture (:on-update state)
                                   (:abort state)
                                   (:capture-bytes (:limits state)))
            _ (reset! (:writers state) capture)
            {:keys [done result]} (start-eval-thread! sci-ctx code prelude capture ns-atom state)
            ;; Escape must end the call whether or not the model set a
            ;; deadline; the grace lets interpreted code unwind.
            outcome (await-script done (:cancelled state) deadline)]
        (when-not (= :done outcome)
          (compare-and-set! (:abort state) nil
                            (if (= :cancelled outcome) :aborted :timeout))
          (deref done interrupt-grace-ms false))
        (let [res (or @result {:status (or @(:abort state) :timeout)})]
          (assemble-result {:tool tool
                            :res res
                            :emitted @(:emitted state)
                            :images @(:images state)
                            :timeout-ms timeout-ms
                            :output-bytes (:bytes (:limits state))
                            :output-lines (:lines (:limits state))
                            :capture-bytes (:capture-bytes (:limits state))
                            :trace (:trace state)
                            :abort-reason @(:abort state)
                            :elapsed-ms (- (concurrent/monotonic-ms) started-at)
                            :extra-details (:extra-details opts)})))
      (finally
        (when-let [capture @(:writers state)]
          (finish-capture! capture))
        (reset! (:live? state) false)))))

(defn run
  "Run CODE in the script sandbox. A fresh context is built for the call
   unless SCI-CTX is given (the `eval` service keeps one); the call's
   namespaces are installed with `seed!` and the interrupt holder is re-armed
   for the call. The call's inner tool calls execute in a sandbox realm, which
   is detached when the script returns; a fire-and-forget inner call still
   holds the ctx value, which keeps resolving exactly as it did.

   TOOL names the tool in messages and error types. SCI-CTX pairs with
   INTERRUPT, the holder its :interrupt-fn was created from — a different
   holder would re-arm nothing. STATE, when the caller built it with
   `prepare`, is this call's state. PRELUDE defaults to the alias prelude.
   STARTED-AT/DEADLINE let a caller that serializes calls put the clock where
   the work starts; an explicit :deadline nil means no deadline (otherwise a
   :timeout computes it). NS-ATOM, when given, holds the namespace object a
   kept context's previous successful call ended in — the prelude and the code
   start there and the final namespace is stored back. EXTRA-DETAILS merges
   into the result's :details."
  [{:keys [ctx] :as opts}]
  (require-tool! (:tool opts))
  (let [realm (when-not (false? (:tools? opts)) (sandbox-realm ctx))
        opts (if realm (update opts :ctx assoc :srce-ctx realm) opts)]
    (try
      (run* opts)
      (finally
        (when realm
          ;; a detach failure is a leak in the tree, not the script's result:
          ;; reported, never thrown over the call
          (try (srce/detach! realm)
               (catch Throwable t
                 (debug/log "script: sandbox realm detach failed: " t))))))))

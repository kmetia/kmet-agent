(ns kmet.app.runtime
  "kmet2's boot and its submission point.

   `boot` turns a composed row list into a live ctx: one root, one fiber per
   row, mounted through `srce/plugin!` so a row's dependencies are declared
   rather than sequenced. It is the only place kmet2 mounts a plugin, and it
   fails loudly — a fiber that is not `:active` once the mounts settle is a
   composition bug (a name nothing provides, an `apply` that threw), not a run
   to start.

   The kernel is synchronous and single-threaded by rule (`srce.md` §8): plugin
   code runs on the thread that owns the root, and the one concurrency
   primitive is the pass flag. A thread that must change the kernel — a tool
   thread, a socket reader — therefore **submits**: `submit!` appends a thunk
   to the root's queue from any thread, and `boundary!` runs the queue and
   settles the kernel, one boundary at a time per root. That is the submission
   point; the loop is where it is called — at `:agent-settled`, before the event
   is emitted, so a front end that hears the settle sees the applied change
   (`kmet.app.loop/settle-boundary!`).

   The queue lives in the root ctx's own map under `::submissions`, so a child
   ctx reaches it by `srce/attr` and nothing holds a pointer back to the
   root."
  (:require [clojure.string :as str]
            [kmet.app.events :as events]
            [kmet.app.extensions.rows :as extension-rows]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]))

;; ── submissions ───────────────────────────────────────────────────────────

(def queue-key
  "Where a root ctx keeps its submission queue: one vector of thunks, drained
   by `boundary!`. A ctx key rather than a service, because a submission is
   process plumbing — it has no plugin, no lifetime and nothing to inject."
  ::submissions)

(defn- queue
  "The submission queue visible from CTX, or nil when CTX is not a booted
   kmet2 runtime."
  [ctx]
  (srce/attr ctx queue-key))

(defn submitting?
  "Can CTX take a submission (is it inside a booted runtime)?"
  [ctx]
  (some? (queue ctx)))

(defn- no-queue!
  "The one error a ctx outside a booted runtime gets, whichever half of the
   submission point was called."
  []
  (throw (ex-info "ctx is not a booted kmet2 runtime: no submission queue"
                  {:type :kmet.app/no-submissions})))

(defn submit!
  "Queue THUNK for the next boundary, from any thread. One append, so
   concurrent submitters are safe; nothing runs here. Throws
   :kmet.app/no-submissions outside a booted runtime — silently dropping a
   change is the failure mode this exists to prevent."
  [ctx thunk]
  (if-let [q (queue ctx)]
    (do (swap! q conj thunk) nil)
    (no-queue!)))

(defn- take-submissions!
  "Atomically drain the queue. A concurrent append during the drain lands in
   the next round (an append is safe; the boundary re-checks)."
  [q]
  (loop []
    (let [v @q]
      (if (compare-and-set! q v [])
        v
        (recur)))))

(defn drain!
  "Run every queued thunk in submission order; returns how many ran. A
   throwing thunk is logged and does not stop the rest — the queue carries
   independent submissions, not a transaction.

   This is the drain `boundary!` runs, without the boundary's one-at-a-time
   rule: use `boundary!` from app code, and reach for this only when the caller
   already holds the boundary or has no kernel pass to run."
  [ctx]
  (let [thunks (take-submissions! (queue ctx))]
    (doseq [thunk thunks]
      (try
        (thunk)
        (catch Throwable t
          (srce/log! ctx :error "submitted change threw" t))))
    (count thunks)))

(def max-rounds
  "The boundary's round budget: a submitted change may submit another, so the
   drain repeats until the queue is empty. A queue that has not drained after
   eight rounds is a churn — report it and return, exactly as the kernel's
   refresh pass does."
  8)

(def teardown-timeout-ms
  "How long `shutdown!` waits for a disposer that handed back work it could not
   finish: one budget for the whole subtree, spent in monotonic slices. Zero or
   a negative value makes the wait immediate, which is what a test that means
   \"does not block\" needs."
  5000)

(def startup-timeout-ms
  "How long a mount waits for a plugin whose `apply` handed back a promise
   (the fiber's `:setup`). A row that has not settled by then is a boot error,
   not a hang — `inactive-rows` shows it as `:starting`. One budget for the
   whole boot, and `teardown-timeout-ms` is the exit counterpart."
  5000)

(defn boundary!
  "The turn boundary: run what other threads submitted, then settle the
   kernel's provider changes, repeating while submissions arrive. Returns
   `{:applied n :drains n}` — drains, not the kernel's fixpoint rounds, which
   `settle!` reports for itself. Idempotent, and cheap when nothing was
   submitted.

   **One boundary at a time per root.** A submitted thunk may mount, and the
   kernel runs plugin code on one thread at a time (`srce.md` §8) — two agents
   settling on two run threads would otherwise mount concurrently, which the
   pass flag does not prevent (a `plugin!` that loses the pass runs its `apply`
   inline). The queue's own atom is the monitor: no new state, and a second root
   is a second lock."
  [ctx]
  (if-let [q (queue ctx)]
    (locking q
      (loop [round 0 applied 0]
        (let [n (drain! ctx)
              rounds (inc round)]
          (srce/settle! ctx)
          (if (and (seq @q) (< rounds max-rounds))
            (recur rounds (+ applied n))
            (do
              (when (seq @q)
                (srce/log! ctx :warn ":kmet.app/churn" {:drains rounds}))
              {:applied (+ applied n) :drains rounds})))))
    (no-queue!)))

;; ── boot ──────────────────────────────────────────────────────────────────

(defn- row-source
  "The plugin source ROW names: a var symbol resolved off the classpath, a var,
   or a plugin map as it is. An `:ext/<id>` keyword resolves to the adapter
   plugin that loads the installed extension (`kmet.app.extensions.rows`); any
   other keyword is a bad row."
  [row]
  (let [source (:plugin row)]
    (cond
      (or (var? source) (map? source)) source

      (extension-rows/source? source) (extension-rows/plugin source)

      (symbol? source)
      (try
        (or (requiring-resolve source)
            (throw (ex-info (str "unresolved plugin source: " source)
                            {:type :kmet.app/unresolved-source :row row})))
        (catch Throwable t
          (throw (ex-info (str "cannot load the plugin for row " (pr-str (:id row))
                               ": " (or (ex-message t) (str t)))
                          {:type :kmet.app/unresolved-source :row row} t))))

      (keyword? source)
      (throw (ex-info (str "row " (pr-str (:id row)) " names " (pr-str source)
                           " — a keyword source must be an :ext/… extension id")
                      {:type :kmet.app/bad-row :row row}))

      :else
      (throw (ex-info (str "row " (pr-str (:id row)) " needs a :plugin source, got "
                           (pr-str source))
                      {:type :kmet.app/bad-row :row row})))))

(defn await-setups!
  "Wait for the row setups that ran asynchronously and re-settle, so the caller
   sees decided fibers. A setup that starts another one (a dependent's own
   `apply`) is waited for in the next round, and every round spends what is left
   of one `startup-timeout-ms` budget. Expiry is the caller's to report: the
   fiber is still `:starting` and `inactive-rows` names it."
  [ctx]
  (let [deadline (+ (concurrent/monotonic-ms) startup-timeout-ms)]
    (loop [round 0]
      (let [handles (into [] (keep :setup) (srce/fibers ctx))]
        (when (and (seq handles) (< round max-rounds))
          (concurrent/await-all handles (max 0 (- deadline (concurrent/monotonic-ms))))
          (srce/settle! ctx)
          (recur (inc round))))))
  nil)

(defn mount-rows!
  "Mount ROWS under CTX and return one fiber per row, in composition order —
   mounted, settled, and with any asynchronous setup awaited, so every caller
   reads decided fibers (`inactive-rows`, `check-mounts!`).

   `boot` is this on a fresh root; a **preset** is this on an agent's child ctx
   (`kmet.app.presets`). The mount point, not the row shape, is what a profile
   and a preset differ in — one composition language, two places to compose."
  [ctx rows]
  (let [fibers (mapv (fn [row] (srce/plugin! ctx (row-source row) (:config row))) rows)]
    (srce/settle! ctx)
    (await-setups! ctx)
    fibers))

(defn inactive-rows
  "ROWS whose fiber is not `:active` after the mounts settled, as plain data:
   what a failed mount is reported in terms of."
  [rows fibers]
  (into []
        (keep (fn [[row fiber]]
                (let [{:keys [state error waiting-for]} (srce/fiber-info fiber)]
                  (when-not (= :active state)
                    {:id (:id row) :state state :error error
                     :waiting-for (vec (sort waiting-for))}))))
        (map vector rows fibers)))

(defn- declaration-conflicts
  "Event names that two mounted fibers declare differently, as
   `[{:event :declarations [{:plugin :spec …} …]} …]`. A name may be re-declared
   (an app-wide vocabulary row and the plugin that later claims an event), but
   only identically — anything else is two plugins disagreeing about one
   event."
  [fibers]
  (->> fibers
       (mapcat (fn [fiber]
                 (let [{:keys [name events]} (srce/fiber-info fiber)]
                   (map (fn [[event spec]] [event name spec]) events))))
       (group-by first)
       (keep (fn [[event decls]]
               (when (> (count (set (map peek decls))) 1)
                 {:event event
                  :declarations (mapv (fn [[_ plugin spec]] {:plugin plugin :spec spec}) decls)})))
       vec))

(defn- named-reason
  "`<row id> (<error message>)` for an inactive row, or the bare id when it
   failed without a message. The row's own error is the actionable half — a
   boot message that only named the row left the reason in a log line the
   operator has to find. A `:starting` row is named as an unsettled setup: it
   has no error, and \"not active\" alone would not say what was still running."
  [{:keys [id error state]}]
  (let [msg (or (when (instance? Exception error) (ex-message error))
                (when (= :starting state) "setup did not settle"))]
    (if msg (str (pr-str id) " (" msg ")") (pr-str id))))

(defn- check-mounts!
  "Throw when any of ROWS is not `:active` in FIBERS, or when two of
   ALL-FIBERS (the boot's plus the just-mounted) declare one event differently —
   the two rules a boot holds every mount to. Returns the inactive rows, which
   only a diagnostic boot (`:diagnose? true`) can see: every other call has
   thrown when there is one. `boot` and `mount-deferred!` share the rules, so a
   mode mounted after the boot fails the same way a world row does."
  [ctx rows fibers all-fibers diagnose?]
  (let [failed (inactive-rows rows fibers)]
    (when (and (seq failed) (not diagnose?))
      (doseq [f failed]
        (srce/log! ctx :error "plugin did not mount" f))
      (throw (ex-info (str "kmet2 boot failed: "
                           (str/join ", " (map named-reason failed))
                           " not active")
                      {:type :kmet.app/boot-failed :fibers failed})))
    (let [conflicts (declaration-conflicts all-fibers)]
      (when (seq conflicts)
        (doseq [c conflicts]
          (srce/log! ctx :error "two plugins declare one event differently" c))
        (throw (ex-info (str "kmet2 boot failed: conflicting event declarations "
                             (str/join ", " (map (comp pr-str :event) conflicts)))
                        {:type :kmet.app/event-conflict :conflicts conflicts}))))
    failed))

(defn- deferred-row?
  "Is ROW one a boot leaves for its owner? A row declares `:defer` when only
   someone outside the composition can mount it — the process world waits for
   the entry, which is the only caller with the invocation, and a diagnostic or
   test boot must not read the machine's settings on the way past."
  [row]
  (true? (:defer row)))

(defn boot
  "Mount COMPOSED's rows under a fresh root ctx and return `{:ctx :fibers
   :rows n :deferred […] :failed […]}`. Rows mount in composition order and
   settle once; every fiber must be `:active` afterwards, and no two fibers may
   declare one event differently, or boot throws `:kmet.app/boot-failed` naming
   the rows and their waiting-for, or the events they disagree about.

   A row that declares `:defer` is not mounted: it comes back in `:deferred`
   for `mount-deferred!` (`deferred-row?`). `{:defer (fn [row])}` holds back
   more rows on top of that rule — the entry adds the mode row, whose `serve`
   starts the run, so everything else mounts and settles first. The rule is not
   replaced: a deferred row is the caller's to mount, never the boot's.

   `{:diagnose? true}` returns the same map with the mounted-but-inactive rows
   in `:failed` instead of throwing: a diagnostic that cannot show the tree
   explaining the failure is useless exactly when it is needed, and the tree
   (`kmet.app.inspect`) is where a `:pending` row's `waiting-for` and a
   `:failed` row's error are. A boot that is meant to run keeps the hard
   failure — the two answers differ in the option, not in the mounting.

   The root is named `:app`, which is the first segment of every unit path the
   inspection renderer prints; CTX-META may name it differently (a root a test
   builds, a profile with a second tree)."
  ([composed] (boot composed nil))
  ([{:keys [rows]} {:keys [ctx-meta diagnose? defer]}]
   (let [ctx (srce/root (merge {queue-key (atom []) :name :app} ctx-meta))
         defer? #(or (deferred-row? %) (and defer (defer %)))
         deferred (filterv defer? rows)
         mounted (into [] (remove defer?) rows)
         fibers (mount-rows! ctx mounted)
         failed (check-mounts! ctx mounted fibers fibers diagnose?)
         _ (events/declare-mounted! fibers)]
     {:ctx ctx :fibers fibers :rows (count mounted) :deferred deferred
      :failed failed})))

(defn mount-deferred!
  "Mount the deferred rows BOOTED holds that PICK? matches, TRANSFORM applied to
   each first, and settle. Returns BOOTED with those rows moved into `:fibers`
   (and out of `:deferred`), so a later `shutdown!` disposes them with the
   rest; the rows PICK? leaves come back in a later call. The entry mounts the
   world first, because what the load installs is what the mode's input
   carries, and only then the mode.

   The transform is where the entry merges the invocation into a row's config —
   the values the boot had to install before the row could be told them (a
   `--model` an extension provider names). Throws like `boot` when a row does
   not become active."
  [{:keys [ctx fibers deferred] :as booted} pick? transform]
  (let [chosen (filterv pick? deferred)]
    (if (empty? chosen)
      booted
      (let [remaining (filterv (complement pick?) deferred)
            rows (mapv transform chosen)
            mounted (mount-rows! ctx rows)
            all-fibers (into (vec fibers) mounted)
            _ (check-mounts! ctx rows mounted all-fibers false)
            _ (events/declare-mounted! mounted)]
        (assoc booted
               :fibers all-fibers
               :rows (+ (:rows booted) (count rows))
               :deferred remaining)))))

(defn shutdown!
  "Dispose everything `boot` mounted, children first, then wait for the disposers
   that handed back work they could not finish (`dispose!`'s `:undoing`), bounded
   by `teardown-timeout-ms`. Returns nil.

   This is the one place the app awaits a teardown: at exit nothing else is left
   to be late for. Expiry is a warning, not a failure — the kernel's rule that a
   teardown never throws is worth more than a hard deadline, and a disposer that
   outlives it is a plugin bug the log names."
  [{:keys [ctx]}]
  (let [{:keys [undoing]} (srce/dispose-subtree! ctx)
        expired (set (concurrent/await-all (mapv :handle undoing) teardown-timeout-ms))]
    (doseq [{:keys [label handle]} undoing
            :when (contains? expired handle)]
      (srce/log! ctx :warn ":kmet.app/teardown-timeout" {:label label}))
    nil))

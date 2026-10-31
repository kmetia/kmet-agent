(ns kmet.app.test-support
  "Shared helpers for the app's entry-point tests."
  (:require [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.libs.concurrent :as concurrent]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]
            [kmet.app.events :as events]
            [kmet.app.runtime :as runtime]
            [kmet.app.session.core :as session]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

(defn run-main-into
  "`main` on ARGS with stdout going to OUT, a writer the caller keeps. A mode
   that streams while it runs (rpc) is read *during* the run this way: the
   test watches OUT for the record it is waiting for and only then does what it
   was holding back (usually: closing stdin). Returns `{:code :err}`."
  [args out]
  (let [code (atom nil)
        err (java.io.StringWriter.)]
    (binding [*out* out
              *err* err]
      (reset! code (core/main args)))
    {:code @code :err (str err)}))

(defn run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS — `main` returns the exit
   code and prints, so a command is read without forking a process."
  [args]
  (let [out (java.io.StringWriter.)
        {:keys [code err]} (run-main-into args out)]
    {:code code :out (str out) :err err}))

(defn run-main-isolated
  "`run-main` with the entry's ambient composition layers dropped.

   For a test whose subject is the entry's own rows: the agent-dir and project
   patch.edn this machine has would otherwise mount the developer's extension
   rows into the test process — an extension load per boot, and on Jolt the
   native loader evicts the classpath namespaces the rest of the suite links
   against. A test that exercises the layers stubs `cfg/patch-paths` itself."
  [args]
  (with-redefs [cfg/patch-paths (fn [] [])]
    (run-main args)))

(defn wait-until
  "Poll PRED every 5ms until it is true or TIMEOUT-MS (default 5s) pass, then
   answer whether it became true. For a test waiting on another thread's work:
   a bounded wait for the actual condition, not a sleep hoping it happened. The
   deadline is the monotonic clock, as every duration in kmet is."
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (concurrent/monotonic-ms) (long timeout-ms))]
     (loop []
       (cond
         (pred) true
         (< (concurrent/monotonic-ms) deadline) (do (Thread/sleep 5) (recur))
         :else false)))))

;; ── mode runs ─────────────────────────────────────────────────────────────
;;
;; A mode test boots the real composition and drives one mode through `main`,
;; so the helpers here are the ones every mode needs: a settable model, a
;; stubbed provider, and a session log under target/ (an entry-point test must
;; not write to the agent dir a developer's kmet reads).

(def ^:dynamic *sessions-dir*
  "Where the mode run in progress writes its session log. `with-mode-run`
   binds a fresh directory per run: a mode test with a stalled provider can
   still be finishing a run when the next test begins, and a shared directory
   with a cleanup beforehand turned that into an unrelated failure — the
   lingering run's publish lost its parent directory, or the delete itself hit
   a file the run had just written. `target/` is thrown away by `bb clean`, so
   the directories are left for inspection."
  "target/test-modes")

(defn sessions-dir
  "The session log directory of the run in progress (see *sessions-dir*)."
  []
  *sessions-dir*)

(defn settings
  "What a mode run loads: a provider and model that resolve, over the run's
   SESSION-DIR (default: the test-support root, see *sessions-dir*)."
  ([] (settings *sessions-dir*))
  ([session-dir]
   {:session-dir (str session-dir)
    :provider :opencode-go
    :model "deepseek-v4-flash"}))

(defn with-mode-run
  "Run F with the model stubbed by SEND-MESSAGE (a `send-message` stub) and the
   `settings` above.

   Every run gets its own session directory (`target/test-modes/<uuid>`): the
   stubbed `load-config` closes over it, so a run that outlives its test keeps
   writing where it started and the next run neither deletes that directory nor
   shares one with it. The directory is also bound for F, whose own assertions
   read it through `sessions-dir` — the binding is not how the run learns it,
   because a mode's threads are not all created under this one.

   The entry's ambient composition layers are dropped (`patch-paths` empty):
   the run would otherwise mount whatever extension rows the agent dir's or the
   checkout's own `.kmet/patch.edn` names, which is the developer's machine,
   not the test — and each of those rows is an extension load per boot."
  [send-message f]
  (let [dir (str (fs/path "target" "test-modes" (str (random-uuid))))
        settings (settings dir)]
    (fs/create-dirs dir)
    (binding [*sessions-dir* dir]
      (with-redefs [cfg/load-config (fn [& _] settings)
                    cfg/get-api-key (fn [_] "test-key")
                    cfg/patch-paths (fn [] [])
                    llm/send-message send-message]
        (f)))))

(defn answering
  "A `send-message` stub that streams TEXT and stops."
  [text]
  (fn [opts]
    (future
      (when-let [on-text (:on-text opts)] (on-text text))
      (when-let [on-done (:on-done opts)] (on-done :stop))
      :done)))

(defn with-model-catalog
  "Run F with the catalog a `--model` pattern resolves against: one reasoning
   model."
  [f]
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] [{:provider :opencode-go
                                              :id "deepseek-v4-flash"
                                              :reasoning true}])]
    (f)))

;; ── the composition ───────────────────────────────────────────────────────
;;
;; A test whose subject is the composed app (the rows, the process registries,
;; the extension host the rows bind) boots it once for the namespace. The ctx is
;; the boot's root: it holds every service a front end would `srce/get`, which
;; is what kmet1's `app-runtime/ctx` used to hand out through the shim.

(def ^:dynamic *composition*
  "The boot map `with-composition` bound, or nil outside it."
  nil)

(defn with-composition
  "clojure.test :once fixture: boot the app's composition for the namespace and
   dispose it after. Read its root with `composition-ctx`.

   The boot is the test process's own: a namespace that themes or binds the
   extension host publishes a process registry, so it must not share a
   composition with another namespace's fixture. It composes the profile, not a
   pinned settings map — the rows read the developer's config at mount, so a
   test that depends on a resource set pins that config (or stubs the lookup),
   as `test-lifecycle`'s resource test stubs `io/resource`."
  [f]
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})]
    (try
      (binding [*composition* boot]
        (f))
      (finally
        (srce/dispose-subtree! (:ctx boot))))))

(defn composition-ctx
  "The composed root ctx `with-composition` bound."
  []
  (:ctx *composition*))

(defn create-agent-handle!
  "A real handle created through the composition's `:agents` factory — the
   value a front end drives. A session is created in a scratch dir under
   target/ unless OPTS names one. OPTS: `:session`, `:config` (merged over
   the run defaults), `:cwd`."
  ([] (create-agent-handle! {}))
  ([{:keys [session config cwd]}]
   (let [ctx (composition-ctx)
         agents (srce/get ctx :agents)
         dir (str (fs/path "target" "test-handles" (str (random-uuid))))
         sess (or session
                  (do (fs/create-dirs dir)
                      (session/create-session dir {:cwd (str (fs/cwd))})))
         handle ((:create! agents)
                 ctx
                 {:session sess
                  :config (merge {:provider :opencode-go
                                  :model "deepseek-v4-flash"
                                  :session-dir dir}
                                 config)})]
     (when (some? cwd)
       (reset! (:cwd handle) cwd))
     handle)))

(defn on-event
  "Register F for TYPE on the composition's root ctx — the ctx a booted run's
   agents hang under. The 3-arity carries an owner, as the app's `on!` does."
  ([type f] (events/on! (composition-ctx) type f))
  ([type owner f]
   (events/on! (composition-ctx) type (when owner {:owner owner}) f)))

(defn clear-listeners!
  "Drop every listener on the composition's root ctx."
  []
  (tu/clear-listeners! (composition-ctx)))

(defn with-host
  "clojure.test :each fixture: this namespace needs a bound extension host.

   The loop's tool path and the render path ask the host for its hooks, and a
   bare test process has no composition to bind one. `kmet.test-utils` owns the
   fixture (a fresh registry over a bare ctx, restored and disposed in its
   `finally`), so the app's tests and the root tree's bind the same thing."
  [f]
  (tu/with-extension-host f))

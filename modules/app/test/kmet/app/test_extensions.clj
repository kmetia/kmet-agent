(ns kmet.app.test-extensions
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.app.commands :as commands]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.app.extensions :as row]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

(defn- with-restored-host
  "Run F with the host unbound and no process handle published, restoring both
   afterwards: the binding and the published handle are process-wide and one
   owner at a time, so a test that leaves either behind hands the next owner a
   stale tree."
  [f]
  (let [previous (extensions/bind-services! nil)
        published (registry/publish-process-handle! nil)]
    (try (f)
         (finally
           (extensions/bind-services! previous)
           (registry/publish-process-handle! published)))))

(defn- mounted
  "A root with the host's row mounted, as the composition mounts it."
  []
  (let [root (srce/root {:name :app})
        fiber (srce/plugin! root row/plugin)]
    {:root root :fiber fiber}))

(deftest the-row-binds-the-host
  (with-restored-host
    (fn []
      (mounted)
      (is (= [] (extensions/get-tool-hooks)) "the host answers: no hooks yet")
      ;; the host's own tags land in the handle this row made — the thing that
      ;; makes the binding worth a row, since a run's hooks resolve here
      (extensions/register-tool-hook! :owner/fn (fn [op next] (next op)))
      (is (= 1 (count (extensions/get-tool-hooks)))
          "a registration the host accepted is one its bound handle holds")
      (is (fn? (:register! (extensions/resources :skills)))
          "and the row binds the resource operations itself, backed by the
           composition's registries — a kmet2 process has no base binding to
           inherit them from"))))

(deftest the-row-binds-every-key-the-host-reads
  ;; The overlay is gone, so the row must supply the whole contract: a key the
  ;; host grows and the row forgets would fail only where an extension's init
  ;; touches it, far from here.
  (with-restored-host
    (fn []
      (let [{:keys [fiber]} (mounted)]
        (try
          (is (= (set registries/host-service-keys)
                 (set (keys ((extensions/host-binding)))))
              "the binding is exactly the host's contract, not a subset")
          (finally (srce/dispose! fiber)))))))

(deftest a-second-composition-puts-the-first-binding-back
  ;; The binding is process-wide and one owner at a time: mounting replaces it,
  ;; disposing puts back what was there, so a composition that goes away cannot
  ;; leave the host pointing at a root nothing owns.
  (with-restored-host
    (fn []
      (let [{:keys [fiber]} (mounted)]
        (extensions/register-tool-hook! :owner/first (fn [op next] (next op)))
        (let [{second :fiber} (mounted)]
          (is (= [] (extensions/get-tool-hooks))
              "the second mount binds its own handle")
          (srce/dispose! second)
          (is (= 1 (count (extensions/get-tool-hooks)))
              "and disposing it restores the one it replaced"))
        (srce/dispose! fiber))
      (is (= :kmet/no-host-services
             (try (extensions/get-tool-hooks)
                  nil
                  (catch Exception e (:type (ex-data e)))))
          "disposing the last one unbinds: a host with no owner is a named error"))))

(deftest an-extensions-command-is-the-compositions-command
  ;; 5l-ii's publisher gate: the row publishes the composition's registry as the
  ;; process handle and binds the resource operations the host calls, so a
  ;; contribution made through the host is the `:commands` service the run reads
  ;; — with nothing bound before the row (a kmet2 process), and with a base
  ;; binding (kmet1's process host) replaced, then restored on dispose.
  (with-restored-host
    (fn []
      (let [root (srce/root {:name :app})
            commands-fiber (srce/plugin! root registries/commands)
            fiber (srce/plugin! root row/plugin)]
        (try
          (is (fn? (extensions/host-binding)) "the composition owns the host")
          ((:register! (extensions/resources :commands))
           {:name "gate-cmd" :description "d" :handler (fn [_ _] nil)}
           [:app :test-owner])
          (is (some? (registry/entry (srce/get root :commands)
                                     :kmet/command "gate-cmd"))
              "the contribution lands in the composition's :commands service")
          (is (some? (commands/find-command "gate-cmd"))
              "and a reader with no ctx sees it through the published handle")
          (srce/dispose! fiber)
          (is (nil? (extensions/host-binding))
              "disposing the row puts back the binding it replaced")
          (is (nil? (commands/find-command "gate-cmd"))
              "and the shared reader no longer answers from that composition")
          (finally
            (srce/dispose! commands-fiber)))))))

(def ^:private probe-extension
  "A single-file extension whose only contribution is a tool, so the test needs
   no resource registries and exercises the tool path alone."
  (str "(ns tool-route-probe\n"
       "  (:require [kmet.extension :as ext]))\n\n"
       "(defn init [api]\n"
       "  (ext/register-tool! api {:name \"tool-route-probe\"\n"
       "                           :description \"probe\"\n"
       "                           :params {:x {:type :string}}\n"
       "                           :execute (fn [args] {:content (str (:x args))})}))\n\n"
       "(def plugin {:name :ext/tool-route-probe\n"
       "             :apply (fn [_ctx api] (init api) nil)})\n"))

(deftest extension-tools-reach-the-compositions-tool-table
  ;; The host's :registry is a tag router: :kmet/tool (and :kmet/tool-source)
  ;; go to the composition's :tools service, which is what the loop's table,
  ;; the /tools selector and run_code read. Held in the row's own handle they
  ;; would register fine and be invisible — the Clojure extension's edit/eval
  ;; tools did exactly that.
  (with-restored-host
    (fn []
      (let [root (srce/root {:name :app})
            ;; the composition's order: the host row mounts before :tools, so
            ;; the route has to resolve the service per call, not at mount
            ext-fiber (srce/plugin! root row/plugin)
            tools-fiber (srce/plugin! root registries/tools)
            dir (test-tmp/temp-dir! "test-ext-tool-route-")
            path (str dir "/probe_ext.clj")]
        (try
          (fs/delete-tree dir)
          (fs/create-dirs dir)
          (spit path probe-extension)
          (let [result (extensions/load-extension! path)
                handle (srce/get root :tools)]
            (is (nil? (:error result)) (str "loaded: " (:error result)))
            (is (some? (tools/get-tool handle "tool-route-probe"))
                "the extension's tool is in the composition's :tools service")
            (extensions/unload-all-extensions!)
            (is (nil? (tools/get-tool handle "tool-route-probe"))
                "unload sweeps it out of the same service"))
          (finally
            (extensions/unload-all-extensions!)
            (srce/dispose! ext-fiber)
            (srce/dispose! tools-fiber)))))))

(deftest a-bound-agent-wins-over-the-process-install
  ;; A run binds the agent it is executing as; the host reads that binding
  ;; before the process slot, so an extension handler sees the run's session and
  ;; thinking level rather than whichever front end installed last. The context
  ;; thunk is called per handler, so a mid-run change is visible like it is
  ;; through a front end's `:build-context`. Outside a run the process install
  ;; is still the answer — kmet1's front end and an extension's own fiber depend
  ;; on it.
  (let [previous (extensions/get-session)
        previous-runtime (:runtime (extensions/front-end-surface))
        level (atom :low)]
    (extensions/install-session! {:id "process"})
    (extensions/bind-runtime-capabilities! {:get-active-tools (fn [] [:process])})
    (try
      (is (= "process" (:id (extensions/get-session))))
      (binding [extensions/*agent-context*
                {:session (fn [] {:id "run"})
                 :runtime {:get-thinking-level (fn [] :high)}
                 :context (fn [] {:thinking-level @level})}]
        (is (= "run" (:id (extensions/get-session)))
            "the run's session, not the process's")
        (is (= :high (extensions/runtime-call :get-thinking-level))
            "the run's capability")
        (is (= [:process] (extensions/runtime-call :get-active-tools))
            "a capability the run does not answer falls back to the process")
        (is (= :low (:thinking-level (extensions/build-extension-context))))
        (reset! level :high)
        (is (= :high (:thinking-level (extensions/build-extension-context)))
            "the context is rebuilt per call, not captured at run start")
        (is (= "run" (:id (extensions/get-session)))
            "the session facades read the run's session at call time"))
      (binding [extensions/*agent-context* {:session (fn [] nil)}]
        (is (nil? (extensions/get-session))
            "an agent with no log does not read the process's"))
      (is (= "process" (:id (extensions/get-session)))
          "outside the run the process install answers again")
      (finally
        (extensions/clear-runtime!)
        (extensions/install-session! previous)
        (extensions/bind-runtime-capabilities! previous-runtime)))))


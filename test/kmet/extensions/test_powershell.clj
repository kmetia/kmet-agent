(ns kmet.extensions.test-powershell
  "The powershell extension's two entry points — the tool and the
   `ext.powershell/run` eval verb — share one execute. The nullable api's
   bash-tool fixture stands in for the engine, so no PowerShell is spawned
   and every test runs on every host."
  (:require [clojure.test :as t]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.registry :as tools]
            [kmet.extension :as ext]))

(defn- with-extension
  "Load the single-file extension for F, removing its namespace afterwards."
  [f]
  (load-file "extensions/powershell.clj")
  (try
    (f)
    (finally
      (remove-ns 'kmet.extensions.powershell))))

(t/use-fixtures :once
  (fn [f]
    (with-extension f)))

(defn- ext-var [sym]
  (ns-resolve 'kmet.extensions.powershell sym))

(defn- with-shell
  "Run F with the extension's PowerShell resolution pinned to SHELL."
  [shell f]
  (with-redefs-fn {(ext-var 'resolve-powershell) (constantly shell)}
    f))

(defn- fake-tool
  "A fake bash-tool constructor whose EXECUTE stands in for the engine."
  [execute]
  (fn [_] {:name "powershell" :execute execute}))

(defn- init!
  "Init against a nullable api, optionally replacing the fake tool constructor.
   Answers the registered tool, eval source, source fn and verb."
  ([] (init! nil))
  ([create-tool]
   (let [{:keys [api state]} (ext/create-nullable-api)
         api (if create-tool (assoc api :create-bash-tool create-tool) api)
         _ ((ext-var 'init) api)
         source-fn (get-in @state [:eval-sources :powershell])
         source (source-fn {})]
     {:state @state
      :source-fn source-fn
      :source source
      :tool (get-in @state [:tools "powershell"])
      :verb (get-in source [:ns 'ext.powershell 'run])})))

(defn- sandbox-env
  "A real eval environment with SOURCE-FN registered as :powershell."
  [source-fn]
  (let [env (support/eval-ctx)]
    (support/eval! env ":warm")
    (tools/register-eval-source! (:tools env) :powershell source-fn
                                 {:owner [:test :powershell]})
    env))

(t/deftest test-tool-and-verb-share-the-execute
  (with-shell "/fake/pwsh.exe"
    (fn []
      (let [{:keys [state tool verb source]} (init!)
            opts (first (:bash-tool-opts state))]
        (t/is (= "/fake/pwsh.exe" (:shell-path opts)))
        (t/is (= ["-NoProfile" "-NonInteractive" "-ExecutionPolicy"
                  "Bypass" "-Command"]
                 (:shell-args opts)))
        (t/is (= "PowerShell execution (Windows)." (:doc source)))
        (t/is (= "Get-ChildItem"
                 (:content ((:execute tool) {:command "Get-ChildItem"}))))
        (t/is (= "Get-ChildItem" (verb "Get-ChildItem")))
        (t/is (= "Get-ChildItem" (verb "Get-ChildItem" {:timeout 5})))))))

(t/deftest test-errors-reach-both-entry-points
  (with-shell nil
    (fn []
      (let [{:keys [tool verb]} (init!)
            result ((:execute tool) {:command "Get-ChildItem"})
            thrown (try
                     (verb "Get-ChildItem")
                     nil
                     (catch Exception e e))]
        (t/is (:is-error result))
        (t/is (= (:content result) (ex-message thrown))))))
  (with-shell "/fake/pwsh.exe"
    (fn []
      (let [{:keys [verb]}
            (init! (fake-tool (fn [_ & _]
                                {:content "boom" :is-error true})))
            thrown (try
                     (verb "Get-ChildItem")
                     nil
                     (catch Exception e e))]
        (t/is (= "boom" (ex-message thrown)))
        (t/is (= :powershell/command-failed (:type (ex-data thrown))))))))

(t/deftest test-the-eval-verb-runs-and-observes-the-cancel-signal
  (with-shell "/fake/pwsh.exe"
    (fn []
      (let [mode (atom :ok)
            started (promise)
            release (promise)
            signal (atom false)
            {:keys [source-fn]}
            (init! (fake-tool
                    (fn [args & _]
                      (if (= :ok @mode)
                        {:content (:command args) :is-error false}
                        (do (deliver started bash-tool/*cancel-signal*)
                            @release
                            {:content "cancelled" :is-error true})))))
            env (sandbox-env source-fn)]
        (t/is (= "Get-ChildItem"
                 (:content (support/eval! env
                                          "(ext.powershell/run \"Get-ChildItem\")"))))
        (let [source (first (filter #(= :powershell (:id %))
                                    ((:sources (:service env)))))]
          (t/is (= "PowerShell execution (Windows)." (:doc source)))
          (t/is (= ["ext.powershell/run"] (mapv :name (:verbs source))))
          (t/is (= '([command] [command opts])
                   (:arglists (first (:verbs source))))))
        (reset! mode :block)
        (let [call (future
                     (support/eval! env
                                    "(ext.powershell/run \"Get-ChildItem\")"
                                    {:signal signal}))]
          (try
            (let [bound (deref started 1000 nil)]
              (t/is (some? bound))
              (reset! signal true)
              (t/is (true? (some-> bound deref)))
              (deliver release true)
              (t/is (not= ::timeout (deref call 3000 ::timeout))))
            (finally
              (reset! signal true)
              (deliver release true))))))))

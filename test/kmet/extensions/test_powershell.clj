(ns kmet.extensions.test-powershell
  "The powershell extension's two entry points — the tool and the
   `ext.powershell/run` eval verb — share one execute. The nullable api's
   bash-tool fixture stands in for the engine, so no PowerShell is spawned
   and every test runs on every host."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.test-eval-support :as support]
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

(defn- ext-var
  "The loaded extension's private var named SYM."
  [sym]
  (ns-resolve 'kmet.extensions.powershell sym))

(defn- with-shell
  "Run F with the extension's PowerShell resolution pinned to SHELL."
  [shell f]
  (with-redefs-fn {(ext-var 'resolve-powershell) (constantly shell)}
    f))

(defn- init!
  "Init the extension against a nullable api and answer the registered tool,
   eval source, and eval verb. OPTS may override :create-bash-tool (the fake
   engine constructor) for a failure case."
  ([] (init! {}))
  ([{:keys [create-bash-tool]}]
   (let [{:keys [api state]} (ext/create-nullable-api)
         api (cond-> api
               create-bash-tool (assoc :create-bash-tool create-bash-tool))
         _ ((ext-var 'init) api)
         source-fn (get-in @state [:eval-sources :powershell])
         source (source-fn {})
         verb (get-in source [:ns 'ext.powershell 'run])]
     {:state @state
      :source-fn source-fn
      :tool (get-in @state [:tools "powershell"])
      :verb verb
      :source source})))

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
        (t/is (fn? verb))
        (t/is (= "Get-ChildItem"
                 (:content ((:execute tool) {:command "Get-ChildItem"}))))
        (t/is (= "Get-ChildItem" (verb "Get-ChildItem")))
        (t/is (= "Get-ChildItem" (verb "Get-ChildItem" {:timeout 5})))))))

(t/deftest test-unavailable-powershell-reports-the-same-error-both-ways
  (with-shell nil
    (fn []
      (let [{:keys [tool verb]} (init!)
            result ((:execute tool) {:command "Get-ChildItem"})
            thrown (try
                     (verb "Get-ChildItem")
                     nil
                     (catch Exception e e))]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "PowerShell"))
        (t/is (str/includes? (ex-message thrown) "PowerShell"))
        (t/is (= (:content result) (ex-message thrown))
              "the verb throws the tool's own unavailable message")))))

(t/deftest test-a-failing-tool-result-throws
  (with-shell "/fake/pwsh.exe"
    (fn []
      (let [{:keys [tool verb]}
            (init! {:create-bash-tool
                    (fn [_]
                      {:name "powershell"
                       :execute (fn [_args & _]
                                  {:content "boom" :is-error true})})})
            thrown (try
                     (verb "Get-ChildItem")
                     nil
                     (catch Exception e e))]
        (t/is (= {:content "boom" :is-error true}
                 ((:execute tool) {:command "Get-ChildItem"})))
        (t/is (= "boom" (ex-message thrown)))
        (t/is (= :powershell/command-failed (:type (ex-data thrown))))
        (t/is (= "boom" (get-in (ex-data thrown) [:result :content])))))))

(t/deftest test-the-eval-verb-is-registered-and-runs-in-the-sandbox
  (with-shell "/fake/pwsh.exe"
    (fn []
      (let [{:keys [source-fn]} (init!)
            env (support/eval-ctx)]
        (support/eval! env ":warm")
        (tools/register-eval-source! (:tools env) :powershell source-fn
                                     {:owner [:test :powershell]})
        (t/is (= "Get-ChildItem"
                 (:content (support/eval! env
                                          "(ext.powershell/run \"Get-ChildItem\")"))))
        (let [sources ((:sources (:service env)))
              source (first (filter #(= :powershell (:id %)) sources))]
          (t/is (= "PowerShell execution (Windows)." (:doc source)))
          (t/is (= ["ext.powershell/run"] (mapv :name (:verbs source))))
          (t/is (= '([command] [command opts])
                   (:arglists (first (:verbs source))))))))))

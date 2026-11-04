(ns kmet.app.test-eval
  "The `:eval` service and tool: one persistent environment per agent,
   definitions that survive calls, isolation between agents and from
   run_code, the sticky namespace, and disposal."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval :as eval-service]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.script :as script]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

(defn- eval-ctx []
  (let [ctx (srce/root {:name :test-eval})
        handle (tu/tools-service! ctx)
        service (eval-service/value ctx)]
    (srce/provide! ctx :eval service)
    (tu/mount-builtin-tools! ctx)
    {:ctx ctx :tools handle :service service}))

(defn- eval! [service code & [opts]]
  ((:eval! service) (merge {:code code
                            :limits (script/resolve-limits nil)}
                           opts)))

(t/deftest test-a-definition-survives-a-later-call
  (let [{:keys [service]} (eval-ctx)]
    (t/is (= "42" (:content (eval! service "(do (def kept 42) kept)"))))
    (t/is (= "43" (:content (eval! service "(+ kept 1)"))))))

(t/deftest test-two-services-are-two-agents
  (let [{a :service} (eval-ctx)
        {b :service} (eval-ctx)]
    (t/is (= "1" (:content (eval! a "(do (def only-a 1) only-a)"))))
    (t/is (= "1" (:content (eval! a "only-a"))))
    (t/is (= "false"
             (:content (eval! b "(emit (some? (resolve 'only-a)))")))
          "a definition from another agent's environment is invisible")))

(t/deftest test-a-kept-context-is-not-visible-to-run-code
  (let [{:keys [tools service]} (eval-ctx)]
    (eval! service "(def kept 1)")
    (let [r (tools/execute-tool tools "run_code"
                                {:code "(emit (some? (resolve 'kept)))"}
                                {})]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "false" (:content r))
            "run_code keeps its fresh-context contract"))))

(t/deftest test-the-sticky-namespace-is-re-entered
  (let [{:keys [service]} (eval-ctx)]
    (t/is (= "41" (:content (eval! service "(ns demo) (def x 41) x"))))
    (t/is (= "42" (:content (eval! service "(+ x 1)")))
          "the next call starts in the namespace the previous call ended in")))

(t/deftest test-the-tool-uses-the-agents-service
  (let [{:keys [ctx tools]} (eval-ctx)
        call (fn [code]
               (tools/execute-tool tools "eval" {:code code}
                                   {:ctx {:srce-ctx ctx}}))]
    (t/is (= "9" (:content (call "(do (def via-tool 9) via-tool)"))))
    (t/is (= "10" (:content (call "(+ via-tool 1)"))))))

(t/deftest test-the-tool-reports-a-missing-service
  (let [{:keys [tools]} (tu/tool-ctx)
        r (tools/execute-tool tools "eval" {:code "1"} {})]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) ":eval service"))))

(t/deftest test-the-tool-passes-the-output-budget
  (let [{:keys [ctx tools]} (eval-ctx)
        r (tools/execute-tool tools "eval"
                              {:code "(apply str (repeat 100 \"x\"))"
                               :max-output-bytes 20}
                              {:ctx {:srce-ctx ctx}})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (= 20 (get-in r [:truncation :max-bytes])))))

(t/deftest ^:slow test-the-tool-passes-the-timeout
  (let [{:keys [ctx tools]} (eval-ctx)
        r (tools/execute-tool tools "eval"
                              {:code "(loop [] (recur))" :timeout 0.1}
                              {:ctx {:srce-ctx ctx}})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 0.1 (get-in r [:details :timeout])))))

(t/deftest ^:slow test-a-parallel-call-waits-and-starts-its-clock-after-the-lock
  (let [{:keys [tools service]} (eval-ctx)
        started (promise)]
    (tools/register-tool! tools {:name "eval-test-marker"
                                 :description "Signals that the first call started"
                                 :parameters {:type "object" :properties {}}
                                 :execute (fn [_]
                                            (deliver started true)
                                            {:content "ok"})})
    (try
      (let [first-call (future
                         (eval! service
                                (str "(deref (tools/call \"eval-test-marker\" {}))"
                                     " (sandbox/sleep 300) :first")))
            _ (t/is (deref started 2000 false))
            second-call (eval! service ":second" {:timeout 0.1})]
        (t/is (not (:is-error second-call)) (:content second-call))
        (t/is (= ":second" (:content second-call)))
        (t/is (< (get-in second-call [:details :elapsed-ms]) 200)
              "the clock starts after the lock, not while waiting for it")
        (t/is (= ":first" (:content @first-call))))
      (finally
        (tools/unregister-tool! tools "eval-test-marker")))))

(t/deftest test-dispose-stops-the-service
  (let [{:keys [service]} (eval-ctx)]
    ((:dispose! service))
    (t/is (= [[] [] []]
             [((:kept service)) ((:sources service)) ((:failures service))]))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                            (eval! service "1")))))

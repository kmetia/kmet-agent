(ns kmet.app.test-eval
  "The `:eval` service and tool: one persistent environment per session,
   definitions that survive calls and reloads, isolation between sessions and
   from run_code, the sticky namespace, and disposal."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.script :as script]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(t/deftest test-a-definition-survives-a-later-call
  (let [env (support/eval-ctx)]
    (t/is (= "42" (:content (support/eval! env "(do (def kept 42) kept)"))))
    (t/is (= "43" (:content (support/eval! env "(+ kept 1)"))))))

(t/deftest test-two-services-are-two-agents
  (let [a (support/eval-ctx)
        b (support/eval-ctx)]
    (t/is (= "1" (:content (support/eval! a "(do (def only-a 1) only-a)"))))
    (t/is (= "1" (:content (support/eval! a "only-a"))))
    (t/is (= "false"
             (:content (support/eval! b "(emit (some? (resolve 'only-a)))")))
          "a definition from another agent's environment is invisible")))

(t/deftest test-two-sessions-of-one-agent-do-not-share-definitions
  (let [env (support/eval-ctx)
        other (session/create-session (test-tmp/temp-dir! "eval-other-")
                                      {:cwd (:cwd env)})]
    (t/is (= "1" (:content (support/eval! env "(do (def session-only 1) session-only)"))))
    (t/is (= "false"
             (:content ((:eval! (:service env))
                        {:code "(emit (some? (resolve 'session-only)))"
                         :session other
                         :cwd (:cwd env)
                         :limits (script/resolve-limits nil)})))
          "a second session starts without the first session's definitions")))

(t/deftest test-a-kept-context-is-not-visible-to-run-code
  (let [env (support/eval-ctx)]
    (support/eval! env "(def kept 1)")
    (let [r (support/call-tool env "run_code" {:code "(emit (some? (resolve 'kept)))"})]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "false" (:content r))
            "run_code keeps its fresh-context contract"))))

(t/deftest test-the-sticky-namespace-is-re-entered
  (let [env (support/eval-ctx)]
    (t/is (= "41" (:content (support/eval! env "(ns demo) (def x 41) x"))))
    (t/is (= "42" (:content (support/eval! env "(+ x 1)")))
          "the next call starts in the namespace the previous call ended in")))

(t/deftest test-the-tool-uses-the-agents-service
  (let [env (support/eval-ctx)]
    (t/is (= "9" (:content (support/call-tool env "eval"
                                              {:code "(do (def via-tool 9) via-tool)"}))))
    (t/is (= "10" (:content (support/call-tool env "eval" {:code "(+ via-tool 1)"}))))))

(t/deftest test-the-tool-reports-a-missing-service
  (let [{:keys [tools]} (tu/tool-ctx)
        r (tools/execute-tool tools "eval" {:code "1"} {})]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) ":eval service"))))

(t/deftest test-the-tool-passes-the-output-budget
  (let [env (support/eval-ctx)
        r (support/call-tool env "eval"
                             {:code "(apply str (repeat 100 \"x\"))"
                              :max-output-bytes 20})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (= 20 (get-in r [:truncation :max-bytes])))))

(t/deftest ^:slow test-the-tool-passes-the-timeout
  (let [env (support/eval-ctx)
        r (support/call-tool env "eval"
                             {:code "(loop [] (recur))" :timeout 0.1})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 0.1 (get-in r [:details :timeout])))))

(t/deftest ^:slow test-a-parallel-call-waits-and-starts-its-clock-after-the-lock
  (let [env (support/eval-ctx)
        {:keys [tools service session cwd]} env
        started (promise)]
    (tools/register-tool! tools {:name "eval-test-marker"
                                 :description "Signals that the first call started"
                                 :parameters {:type "object" :properties {}}
                                 :execute (fn [_]
                                            (deliver started true)
                                            {:content "ok"})})
    (try
      (let [first-call (future
                         ((:eval! service)
                          {:code (str "(deref (tools/call \"eval-test-marker\" {}))"
                                      " (sandbox/sleep 300) :first")
                           :session session :cwd cwd
                           :limits (script/resolve-limits nil)}))
            _ (t/is (deref started 2000 false))
            second-call ((:eval! service)
                         {:code ":second" :session session :cwd cwd
                          :timeout 0.1 :limits (script/resolve-limits nil)})]
        (t/is (not (:is-error second-call)) (:content second-call))
        (t/is (= ":second" (:content second-call)))
        (t/is (< (get-in second-call [:details :elapsed-ms]) 200)
              "the clock starts after the lock, not while waiting for it")
        (t/is (= ":first" (:content @first-call))))
      (finally
        (tools/unregister-tool! tools "eval-test-marker")))))

(t/deftest test-dispose-stops-the-service
  (let [env (support/eval-ctx)
        service (:service env)]
    ((:dispose! service))
    (t/is (= [[] [] []]
             [((:kept service)) ((:sources service)) ((:failures service))]))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                            (support/eval! env "1")))))

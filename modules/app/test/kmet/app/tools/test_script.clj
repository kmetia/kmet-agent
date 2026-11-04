(ns kmet.app.tools.test-script
  "The shared script layer's kept-context seams: a definition survives a call,
   `seed!` replaces the tools namespace's key set per call, and a timeout
   leaves the next call clean."
  (:require [clojure.test :as t]
            [kmet.app.tools.script :as script]))

(defn- tool-def [name]
  {:name name
   :description (str name " test tool")
   :parameters {:type "object" :properties {}}
   :execute (fn [_] {:content name})})

(defn- kept-context []
  (let [holder (script/interrupt-holder)
        ctx (script/context (script/base-namespaces)
              (script/interrupt-fn holder "eval")
              "eval")]
    {:ctx ctx :holder holder}))

(defn- run-opts
  "The script tool's seams over TOOLS, the only callable tools."
  [tools code]
  {:tool "eval"
   :code code
   :get-all-tools (constantly tools)
   :get-contributed-tools (constantly {})
   :select-tools (fn [all _] all)
   :execute-tool (fn [& _] {:content "not dispatched"})})

(defn- run-in [{:keys [ctx holder]} tools code]
  (script/run (assoc (run-opts tools code)
                     :sci-ctx ctx
                     :interrupt holder)))

(t/deftest test-a-kept-context-keeps-a-definition
  (let [s (kept-context)]
    (t/is (= "42" (:content (run-in s {} "(do (def kept 42) kept)"))))
    (t/is (= "43" (:content (run-in s {} "(+ kept 1)"))))))

(t/deftest test-seed-replaces-the-tools-namespace
  (let [s (kept-context)]
    (t/is (= "true"
             (:content (run-in s {"a" (tool-def "a")}
                               "(emit (contains? (ns-publics 'tools) 'a))"))))
    (t/is (= "[false true true]"
             (:content (run-in s {"b" (tool-def "b")}
                               (str "(emit [(contains? (ns-publics 'tools) 'a)"
                                    " (contains? (ns-publics 'tools) 'b)"
                                    " (contains? (ns-publics 'tools) 'call)])")))))))

(t/deftest test-a-run-needs-a-tool-name
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/prepare {})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/run {:code "1"}))))

(t/deftest ^:slow test-a-timeout-leaves-the-next-call-clean
  (let [s (kept-context)
        r (script/run (assoc (run-opts {} "(loop [] (recur))")
                             :timeout 0.1
                             :sci-ctx (:ctx s)
                             :interrupt (:holder s)))]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= "42" (:content (run-in s {} "(+ 40 2)"))))))

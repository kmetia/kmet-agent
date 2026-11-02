(ns kmet.app.tools.test-bash
  "The bash tool's result: what the model is handed. A call that never ran (a
   spawn failure) must arrive as an error result, not as a success whose text
   merely contains an error. The spawn half is the executor's injectable
   `:operations`, so no case here starts a process."
  (:require [clojure.test :as t]
            [kmet.app.tools.bash :as bash]))

(defn- run
  "The result the model sees for ARGS, through a bash tool whose spawn is OPS
   (pi: BashOperations)."
  [ops args]
  ((:execute (bash/create-tool {:operations ops})) args))

(defn- output-ops
  "Operations that stream TEXT as stdout and answer EXIT-CODE: the executor
   builds the result from the stream itself, not from the operations' return
   value."
  [text exit-code]
  (fn [{:keys [on-data]}]
    (let [bytes (.getBytes text "UTF-8")]
      (on-data bytes 0 (alength bytes) :stdout))
    {:exit-code exit-code}))

(t/deftest test-bash-spawn-failure-is-an-error
  (t/testing "a spawn that throws (the command never ran) is an error result"
    (let [result (run (fn [_]
                        (throw (ex-info "Cannot run program \"bash\"" {:type :spawn})))
                      {:command "echo hi"})]
      (t/is (true? (:is-error result)))
      (t/is (= "Error: Cannot run program \"bash\"" (:content result))))))

(t/deftest test-bash-exit-code-classification
  (t/testing "a non-zero exit is an error, with the status line after the output"
    (let [result (run (output-ops "boom\n" 2) {:command "false"})]
      (t/is (true? (:is-error result)))
      (t/is (= "boom\n\n\nCommand exited with code 2" (:content result)))))
  (t/testing "an exit code that could not be observed is an error, not a success"
    (let [result (run (output-ops "" nil) {:command "x"})]
      (t/is (true? (:is-error result)))
      (t/is (= "Command terminated without an exit code" (:content result)))))
  (t/testing "a zero exit stays a success"
    (let [result (run (output-ops "" 0) {:command "true"})]
      (t/is (false? (:is-error result)))
      (t/is (= "(no output)" (:content result))))))

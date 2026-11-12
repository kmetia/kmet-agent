(ns kmet.app.tools.test-bash
  "The bash tool's contract and result: the declaration the model sees and
   what it is handed. A call that never ran (a spawn failure) must arrive as
   an error result, not as a success whose text merely contains an error. The
   spawn half is the executor's injectable `:operations`, so most cases here
   start no process."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
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

(defn- throw-ops
  "Operations that throw MESSAGE before producing output."
  [message]
  (fn [_] (throw (ex-info message {}))))

(t/deftest test-bash-tool-contract
  (t/testing "name, label, description, snippet and guideline match pi"
    (let [tool (bash/create-tool)]
      (t/is (= "bash" (:name tool)))
      (t/is (= "bash" (:label tool)))
      (t/is (= (str "Execute a bash command in the current working directory. "
                    "Returns stdout and stderr. Output is truncated to last "
                    "2000 lines or 50KB (whichever is hit first). If truncated, "
                    "full output is saved to a temp file. Optionally provide a "
                    "timeout in seconds.")
               (:description tool)))
      (t/is (= "Execute bash commands (ls, grep, find, etc.)" (:prompt-snippet tool)))
      (t/is (= ["You can inspect KMET_* environment variables for current model and session details."]
               (:prompt-guidelines tool)))
      (t/is (= {:type :json-schema :strict :prefer}
               (:constrained-sampling tool)))))
  (t/testing "the input schema is pi's: required command, optional timeout"
    (let [schema (:parameters (bash/create-tool))]
      (t/is (= "object" (:type schema)))
      (t/is (= ["command"] (:required schema)))
      (t/is (= {:type "string" :description "Shell command to execute"}
               (get-in schema [:properties "command"])))
      (t/is (= {:type "number"
                :description "Timeout in seconds (optional, no default timeout)"}
               (get-in schema [:properties "timeout"])))))
  (t/testing "expose-session-env? false drops pi's guideline"
    (t/is (nil? (:prompt-guidelines
                 (bash/create-tool {:expose-session-env? false}))))))

(t/deftest test-bash-spawn-failure-is-an-error
  (t/testing "a spawn that throws (the command never ran) is an error result"
    (let [result (run (throw-ops "Cannot run program \"bash\"")
                      {:command "echo hi"})]
      (t/is (true? (:is-error result)))
      (t/is (= "Cannot run program \"bash\"" (:content result))))))

(t/deftest test-bash-exit-code-classification
  (t/testing "a non-zero exit is an error, with the status line after the output"
    (let [result (run (output-ops "boom\n" 2) {:command "false"})]
      (t/is (true? (:is-error result)))
      (t/is (= "boom\n\n\nCommand exited with code 2" (:content result)))))
  (t/testing "an empty non-zero exit still shows pi's (no output)"
    (let [result (run (output-ops "" 1) {:command "false"})]
      (t/is (true? (:is-error result)))
      (t/is (= "(no output)\n\nCommand exited with code 1" (:content result)))))
  (t/testing "an exit code that could not be observed is an error, not a success"
    (let [result (run (output-ops "" nil) {:command "x"})]
      (t/is (true? (:is-error result)))
      (t/is (= "(no output)\n\nCommand terminated without an exit code"
               (:content result)))))
  (t/testing "a zero exit stays a success"
    (let [result (run (output-ops "" 0) {:command "true"})]
      (t/is (false? (:is-error result)))
      (t/is (= "(no output)" (:content result))))))

(t/deftest test-bash-timeout-and-abort-content
  (t/testing "a timeout keeps pi's status, and no (no output) prefix"
    (let [result (run (throw-ops "timeout:2") {:command "sleep 5"})]
      (t/is (true? (:is-error result)))
      (t/is (= "Command timed out after 2 seconds" (:content result)))))
  (t/testing "an abort is pi's Command aborted"
    (let [result (run (throw-ops "aborted") {:command "sleep 5"})]
      (t/is (true? (:is-error result)))
      (t/is (= "Command aborted" (:content result))))))

(t/deftest test-bash-truncation-footer
  (t/testing "the line-cut footer names the shown range and the full-output file"
    (let [text (str/join "\n" (map #(str "line " %) (range 1 2002)))
          result (run (output-ops text 0) {:command "seq"})
          path (get-in result [:truncation :full-output-path])]
      (t/is (= 2001 (get-in result [:truncation :total-lines])))
      (t/is (= :lines (get-in result [:truncation :truncated-by])))
      (t/is (str/ends-with?
             (:content result)
             (str "[Showing lines 2-2001 of 2001. Full output: " path "]")))
      (fs/delete path)))
  (t/testing "a byte-cut single line names its partial size"
    (let [text (apply str (repeat 60000 "x"))
          result (run (output-ops text 0) {:command "one line"})
          path (get-in result [:truncation :full-output-path])]
      (t/is (true? (get-in result [:truncation :last-line-partial])))
      (t/is (= 60000 (get-in result [:truncation :last-line-bytes])))
      (t/is (= 51200 (get-in result [:truncation :output-bytes])))
      (t/is (str/includes?
             (:content result)
             (str "[Showing last 50.0KB of line 1 (line is 58.6KB). Full output: "
                  path "]")))
      (fs/delete path)))
  (t/testing "a non-zero exit keeps pi's truncation details"
    (let [text (str/join "\n" (map #(str "line " %) (range 1 2002)))
          result (run (output-ops text 2) {:command "seq"})
          path (get-in result [:truncation :full-output-path])]
      (t/is (true? (:is-error result)))
      (t/is (= :lines (get-in result [:truncation :truncated-by])))
      (t/is (str/includes? (:content result)
                           (str "[Showing lines 2-2001 of 2001. Full output: " path "]")))
      (t/is (str/ends-with? (:content result) "Command exited with code 2"))
      (fs/delete path))))

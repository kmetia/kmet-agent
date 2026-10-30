(ns kmet.app.loop.test-message
  "The loop's wire-shape transforms: pi's convertToLlm
   (kmet.app.loop.message/to-llm-messages) and the image blocking applied per
   request."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.loop.message :as message]))

(t/deftest test-to-llm-messages
  ;; pi: convertToLlm — compaction/branch-summary AgentMessages become user
  ;; messages carrying the wrapped summary text at the wire; other roles
  ;; pass through untouched
  (let [input [{:role :user :content [{:type :text :text "hi"}]}
               {:role :compaction :summary "SUM" :tokens-before 123}
               {:role :tool :content [{:type :tool_result :tool_use_id "t1"}]}
               {:role :branch-summary :summary "BRANCH"}]
        msgs (message/to-llm-messages input)
        text-of (fn [m] (-> m :content first :text))]
    (t/is (= [:user :user :tool :user] (mapv :role msgs)))
    (t/is (= "SUM" (:summary (nth input 1)))
          "conversion is non-destructive: the input role map keeps its summary")
    (t/is (str/includes? (text-of (nth msgs 1)) "compacted into the following summary"))
    (t/is (str/includes? (text-of (nth msgs 1)) "<summary>\nSUM\n</summary>"))
    (t/is (str/includes? (text-of (nth msgs 3)) "summary of a branch"))
    (t/is (str/includes? (text-of (nth msgs 3)) "<summary>\nBRANCH\n</summary>"))
    (t/is (= "hi" (text-of (first msgs))))))

(t/deftest test-to-llm-messages-agent-roles
  (t/testing "a bash execution becomes a user message; an excluded one is dropped
            (pi: convertToLlm bashExecution)"
    (let [msgs (message/to-llm-messages
                [{:role :bash :command "ls" :output "a\nb" :exit-code 0
                  :exclude-from-context? false}
                 {:role :bash :command "rm -rf /" :output "" :exit-code 0
                  :exclude-from-context? true}])]
      (t/is (= 1 (count msgs)) "the excluded execution never reaches a provider")
      (t/is (= :user (:role (first msgs))))
      (t/is (= "Ran `ls`\n```\na\nb\n```" (-> msgs first :content first :text))
            "pi: bashExecutionToText shape")
      (t/is (= [:user 1] [(-> msgs first :role) (count msgs)]))))
  (t/testing "a custom message becomes a user message with its own content
            (pi: convertToLlm custom→user)"
    (let [msgs (message/to-llm-messages
                [{:role :custom :custom-type :note :content "plain string"}
                 {:role :custom-message
                  :content [{:type :text :text "block"}]}])]
      (t/is (= [:user :user] (mapv :role msgs)))
      (t/is (= [{:type :text :text "plain string"}] (-> msgs first :content))
            "string content is normalized to a block vector")))
  (t/testing "provider roles pass through untouched"
    (let [convo [{:role :system :content [{:type :text :text "sys"}]}
                 {:role :user :content [{:type :text :text "hi"}]}
                 {:role :assistant :content [{:type :text :text "yo"}]}
                 {:role :tool :content [{:type :tool_result :tool_use_id "t"}]}]]
      (t/is (= convo (message/to-llm-messages convo))))))

(t/deftest test-bash-entry-text
  (t/testing "derived from the entry's command/output (pi: bashExecutionToText)"
    (let [text (message/bash-entry-text {:command "ls" :output "a\nb" :exit-code 0})]
      (t/is (str/includes? text "Ran `ls`"))
      (t/is (str/includes? text "a\nb")))
    (t/is (str/includes? (message/bash-entry-text {:command "ls" :output "o" :exit-code 3})
                         "Command exited with code 3")
          "a non-zero exit is reported")
    (t/is (str/includes? (message/bash-entry-text {:command "ls" :output "o" :cancelled true})
                         "(command cancelled)")))
  (t/testing "an explicit text block vector wins — a context edit's replacement"
    (t/is (= "replacement"
             (message/bash-entry-text {:command "ls" :output "original"
                                       :content [{:type :text :text "replacement"}]})))
    (t/is (str/includes? (message/bash-entry-text
                          {:command "ls" :content [{:content ""}]})
                         "Ran `ls`")
          "other block shapes still derive from the command")
    (t/is (= "" (message/bash-entry-text
                 {:command "ls" :output "original" :content [{:type :text :text ""}]}))
          "an empty text replacement is still a replacement (the block type,
           not the emptiness, decides)")))

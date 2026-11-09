(ns kmet.app.compaction.test-vcc
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.compaction.vcc :as vcc]))

(defn- user [text]
  {:role :user :content [{:type :text :text text}]})

(defn- assistant [text & [tool-calls]]
  (cond-> {:role :assistant :content [{:type :text :text text}]}
    (seq tool-calls) (assoc :tool-calls (vec tool-calls))))

(defn- tool-result [id content]
  {:role :tool
   :tool-name "read"
   :is-error false
   :content [{:type :tool_result :tool_use_id id :content content}]})

(defn- bash [command output & [exit-code]]
  {:role :bash :command command :output output :exit-code exit-code})

(defn- summarize [messages & [opts]]
  (vcc/summarize nil
                 (merge {:messages messages
                         :previous-summary nil
                         :tokens-before 100}
                        opts)
                 nil :manual))

(t/deftest summary-has-the-vcc-sections-and-is-deterministic
  (let [messages [(user "first request")
                  (assistant "reading the file"
                             [{:id "r1" :name "read" :arguments {:path "/src/a.clj"}}])
                  (tool-result "r1" "file data")
                  (assistant "editing the file"
                             [{:id "e1" :name "edit" :arguments {:path "/src/b.clj"}}])
                  (tool-result "e1" "edited")
                  (bash "git commit -m \"fix: thing\"" "[master abc1234] fix: thing" 0)
                  (user "last request")]
        result (summarize messages)
        summary (:summary result)]
    (t/is (str/includes? summary "[Early Session Requests]"))
    (t/is (str/includes? summary "- first request"))
    (t/is (str/includes? summary "[Files And Changes]"))
    (t/is (str/includes? summary "- Modified: /src/b.clj"))
    (t/is (str/includes? summary "- Read: /src/a.clj"))
    (t/is (str/includes? summary "[Commits]"))
    (t/is (str/includes? summary "- abc1234: fix: thing"))
    (t/is (str/includes? summary "[User Messages]"))
    (t/is (str/includes? summary "[user] last request"))
    (t/is (str/includes? summary "* read \"/src/a.clj\""))
    (t/is (= :vcc (get-in result [:details :compactor])))
    (t/is (= 7 (get-in result [:details :source-message-count])))
    (t/is (= result (summarize messages))
          "the same preparation produces the same summary")))

(t/deftest summary-merges-a-previous-vcc-summary
  (let [previous (str "[Early Session Requests]\n- original request\n\n"
                      "[Files And Changes]\n- Modified: /old.clj\n\n"
                      "[User Messages]\n- original request\n\n"
                      "---\n\n[user] old line")
        messages [(user "new request")
                  (assistant "writing" [{:id "w1" :name "write" :arguments {:path "/new.clj"}}])
                  (tool-result "w1" "written")
                  (user "newest request")]
        summary (:summary (summarize messages {:previous-summary previous}))]
    (t/is (str/includes? summary "- original request")
          "a previous early request is preserved")
    (t/is (str/includes? summary "- Modified: /old.clj"))
    (t/is (str/includes? summary "- Modified: /new.clj"))
    (t/is (str/includes? summary "[user] old line")
          "the previous brief is merged with the fresh one")
    (t/is (str/includes? summary "- newest request"))))

(t/deftest summary-preserves-an-llm-format-previous-summary
  (let [previous "## Goal\nKeep the API stable\n\n## Next Steps\n1. Ship it"
        summary (:summary (summarize [(user "new request")] {:previous-summary previous}))]
    (t/is (str/includes? summary "[Previous Summary]"))
    (t/is (str/includes? summary "## Goal"))
    (t/is (str/includes? summary "Keep the API stable"))))

(t/deftest summary-dedupes-files-newest-and-caps-user-messages
  (let [messages (into [(user "first")]
                       (mapcat (fn [i]
                                 [(assistant (str "step " i)
                                             [{:id (str "t" i) :name "read"
                                               :arguments {:path "/src/a.clj"}}])
                                  (tool-result (str "t" i) "contents")
                                  (user (str "request " i))])
                               (range 20)))
        summary (:summary (summarize messages))]
    (t/is (= 1 (count (re-seq #"- Read: /src/a.clj" summary)))
          "a repeated path appears once")
    (t/is (str/includes? summary "- request 19"))
    (t/is (not (str/includes? summary "[User Messages]\n- request 0\n"))
          "only the newest 15 user messages are listed")))

(t/deftest summary-keeps-the-brief-bounded
  (let [messages (mapv #(user (str "message " % " " (apply str (repeat 200 "x"))))
                       (range 300))
        summary (:summary (summarize messages))
        brief (second (str/split summary #"\[Transcript\]\n\n"))]
    (t/is (<= (count (str/split-lines brief)) 120)
          "the brief stays within its line cap")
    (t/is (<= (count summary) 12000) "the whole summary stays within its char cap")))

(t/deftest summary-never-empty
  (let [result (summarize [(tool-result "r1" "only a tool result")])]
    (t/is (str/includes? (:summary result) "(no renderable user messages)"))
    (t/is (true? (get-in result [:details :empty?])))))

(t/deftest skipped-custom-types-never-reach-the-summary
  (let [messages [{:role :custom :custom-type :tool-sources
                   :content [{:type :text :text "deferred sources listing"}]}
                  {:role :custom :custom-type :eval-library
                   :content [{:type :text :text "eval library listing"}]}
                  {:role :custom :custom-type :ext/state
                   :content [{:type :text :text "extension note"}]}]
        summary (:summary (summarize messages))]
    (t/is (not (str/includes? summary "deferred sources listing")))
    (t/is (not (str/includes? summary "eval library listing")))
    (t/is (str/includes? summary "[extension:ext/state] extension note"))))

(t/deftest summary-preserves-branch-summaries
  (let [summary (:summary (summarize [(user "hello")
                                      {:role :branch-summary
                                       :summary "BRANCH CONTEXT"}]))]
    (t/is (str/includes? summary "[Branch Summary]"))
    (t/is (str/includes? summary "BRANCH CONTEXT"))))

(t/deftest summary-merges-a-previous-branch-summary
  (let [previous (str "[Branch Summary]\nold branch context\n\n"
                      "[User Messages]\n- old user")
        summary (:summary (summarize [(user "new")] {:previous-summary previous}))]
    (t/is (str/includes? summary "old branch context"))
    (t/is (str/includes? summary "new"))))

(t/deftest failed-tool-results-are-marked-and-not-files
  (let [messages [(assistant "editing"
                             [{:id "e1" :name "edit"
                               :arguments {:path "/src/x.clj"}}])
                  {:role :tool
                   :tool-name "edit"
                   :is-error true
                   :content [{:type :tool_result
                              :tool_use_id "e1"
                              :content "String not found"}]}
                  (user "try again")]
        summary (:summary (summarize messages))]
    (t/is (not (str/includes? summary "- Modified: /src/x.clj"))
          "a failed edit is not a file change")
    (t/is (str/includes? summary "[failed: String not found]")
          "the tool error is visible in the brief")))

(t/deftest failed-commits-are-not-listed
  (let [messages [(assistant "committing"
                             [{:id "c1" :name "bash"
                               :arguments {:command "git commit -m \"fix\""}}])
                  {:role :tool
                   :tool-name "bash"
                   :is-error true
                   :content [{:type :tool_result
                              :tool_use_id "c1"
                              :content "nothing to commit"}]}]
        summary (:summary (summarize messages))]
    (t/is (not (str/includes? summary "[Commits]"))
          "a failed commit is not a commit")))

(t/deftest commit-parsing-handles-message-forms-and-skips-noise
  (t/testing "--message in the space form is parsed"
    (let [summary (:summary (summarize
                             [(assistant "c"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "git commit --message \"space form\""}}])
                              {:role :tool :tool-name "bash" :is-error false
                               :content [{:type :tool_result :tool_use_id "c1"
                                          :content "[master abc1234] space form"}]}]))]
      (t/is (str/includes? summary "abc1234: space form"))))
  (t/testing "a commit without a parsed message is skipped"
    (let [summary (:summary (summarize
                             [(assistant "c"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "git commit -F msg.txt"}}])
                              {:role :tool :tool-name "bash" :is-error false
                               :content [{:type :tool_result :tool_use_id "c1"
                                          :content "[master def5678] from file"}]}]))]
      (t/is (not (str/includes? summary "[Commits]")))))
  (t/testing "--amend --no-edit is named"
    (let [summary (:summary (summarize
                             [(assistant "c"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "git commit --amend --no-edit"}}])
                              {:role :tool :tool-name "bash" :is-error false
                               :content [{:type :tool_result :tool_use_id "c1"
                                          :content "[master abc9999] amended"}]}]))]
      (t/is (str/includes? summary "abc9999: amend"))))
  (t/testing "git commit-tree is not a commit"
    (let [summary (:summary (summarize
                             [(assistant "c"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "git commit-tree -m tree"}}])
                              {:role :tool :tool-name "bash" :is-error false
                               :content [{:type :tool_result :tool_use_id "c1"
                                          :content "[master 1234567] tree"}]}]))]
      (t/is (not (str/includes? summary "[Commits]"))))))

(t/deftest llm-summary-with-a-separator-is-preserved
  (let [previous "## Goal\nkeep the API stable\n\n---\n\nmore context"
        summary (:summary (summarize [(user "new")] {:previous-summary previous}))]
    (t/is (str/includes? summary "[Previous Summary]"))
    (t/is (str/includes? summary "## Goal"))
    (t/is (str/includes? summary "keep the API stable"))
    (t/is (str/includes? summary "more context"))))

(t/deftest tagged-previous-summaries-survive-ambiguous-content
  (let [noisy "## Goal\n---\n[User Messages]\nkeep this"
        first (summarize [(user "x")] {:previous-summary noisy})
        second (summarize [(user "y")] {:previous-summary (:summary first)})
        summary (:summary second)]
    (t/is (str/includes? summary "<previous-summary>"))
    (t/is (str/includes? summary "## Goal"))
    (t/is (str/includes? summary "keep this"))
    (t/is (str/includes? summary "[Transcript]"))))

(t/deftest tagged-branch-summaries-survive-a-reparse
  (let [first (summarize [(user "x")
                          {:role :branch-summary :summary "branch context"}])
        second (summarize [(user "y")] {:previous-summary (:summary first)})
        summary (:summary second)]
    (t/is (str/includes? summary "<branch-summary>"))
    (t/is (str/includes? summary "branch context"))))

(t/deftest a-summary-with-a-header-after-the-first-line-is-not-vcc
  (let [summary (:summary (summarize
                           [(user "x")]
                           {:previous-summary "intro\n[User Messages]\nkeep"}))]
    (t/is (str/includes? summary "intro"))
    (t/is (str/includes? summary "keep"))))

(t/deftest commit-detection-requires-a-command-position
  (t/testing "a commit mentioned inside an echo is not a commit"
    (let [summary (:summary (summarize
                             [(assistant "echo"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "echo \"git commit -m nope\""}}])
                              (tool-result "c1" "[master abc1234] nope")]))]
      (t/is (not (str/includes? summary "[Commits]")))))
  (t/testing "a commit after a command separator is a commit"
    (let [summary (:summary (summarize
                             [(assistant "commit"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "cd repo && git commit -m \"real\""}}])
                              (tool-result "c1" "[master abc1234] real")]))]
      (t/is (str/includes? summary "abc1234: real"))))
  (t/testing "an -m before the commit command does not become its subject"
    (let [summary (:summary (summarize
                             [(assistant "commit"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "echo \"-m fake\" && git commit -m \"real\""}}])
                              (tool-result "c1" "[master abc1234] real")]))]
      (t/is (str/includes? summary "abc1234: real"))
      (t/is (not (str/includes? summary "abc1234: fake"))))))

(t/deftest heredoc-commit-messages-are-skipped
  (let [command (str "git commit -m \"$(cat <<'EOF'\n"
                     "subject\n"
                     "EOF\n"
                     ")\"")
        summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command command}}])
                            (tool-result "c1" "[master abc1234] subject")]))]
    (t/is (not (str/includes? summary "[Commits]")))))

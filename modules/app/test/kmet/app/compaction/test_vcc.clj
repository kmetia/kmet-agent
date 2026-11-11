(ns kmet.app.compaction.test-vcc
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.compaction.vcc :as vcc]
            [kmet.app.session.core :as session]))

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
                  (user "second request")
                  (assistant "reading the file"
                             [{:id "r1" :name "read" :arguments {:path "/src/a.clj"}}])
                  (tool-result "r1" "file data")
                  (assistant "editing the file"
                             [{:id "e1" :name "edit" :arguments {:path "/src/b.clj"}}])
                  (tool-result "e1" "edited")
                  (bash "git commit -m \"fix: thing\"" "[master abc1234] fix: thing" 0)
                  (user "third request")
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
    (t/is (str/includes? summary "[User Messages]\n- last request"))
    (t/is (not (str/includes? summary "[User Messages]\n- first request"))
          "early requests are not repeated in User Messages")
    (t/is (str/includes? summary "third request\nlast request"))
    (t/is (str/includes? summary "* read \"/src/a.clj\""))
    (t/is (= :vcc (get-in result [:details :compactor])))
    (t/is (= 9 (get-in result [:details :source-message-count])))
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
    (t/is (str/includes? summary "- Created: /new.clj"))
    (t/is (str/includes? summary "[user] old line")
          "the previous brief is merged with the fresh one")
    (t/is (str/includes? summary "- newest request"))))

(t/deftest tracked-commands-survive-a-merge
  (let [previous (str "[Early Session Requests]\n- original request\n\n"
                      "[Commits]\n- abc1234: real commit\n\n"
                      "[Tracked Commands]\n- docker: docker ps\n\n"
                      "[User Messages]\n- later message\n")
        summary (:summary (summarize [(user "new request here")]
                                     {:previous-summary previous}))]
    (t/is (str/includes? summary "- abc1234: real commit"))
    (t/is (str/includes? summary "[Tracked Commands]"))
    (t/is (str/includes? summary "- docker: docker ps"))))

(t/deftest previous-early-requests-are-capped-at-three
  (let [previous "[Early Session Requests]\n- one\n- two\n- three\n- four\n"
        summary (:summary (summarize [(user "new request here")]
                                     {:previous-summary previous}))]
    (t/is (str/includes? summary "- one"))
    (t/is (str/includes? summary "- three"))
    (t/is (not (str/includes? summary "- four")))))

(t/deftest transcript-headers-stay-in-the-transcript
  (let [previous (str "[Early Session Requests]\n- original request\n\n"
                      "---\n\n"
                      "[Commits]\n- fake: transcript line\n\n"
                      "[user] hello")
        summary (:summary (summarize [(user "new request here")]
                                     {:previous-summary previous}))
        header (first (str/split summary #"\n\n---\n\n"))]
    (t/is (not (str/includes? header "[Commits]"))
          "a transcript line never becomes a section of the merged header")
    (t/is (str/includes? summary "[Commits]\n- fake: transcript line")
          "the previous brief itself is preserved")))

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
        brief (second (str/split summary #"\n\n---\n\n"))]
    (t/is (<= (count (str/split-lines brief)) 120)
          "the ranked brief stays within its block cap")
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

(t/deftest a-commit-without-a-tool-result-is-not-listed
  (let [summary (:summary (summarize
                           [(assistant "committing"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command "git commit -m \"fix\""}}])]))]
    (t/is (not (str/includes? summary "[Commits]")))))

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
    (t/is (str/includes? summary "Use `session_recall`"))))

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
      (t/is (not (str/includes? summary "abc1234: fake")))))
  (t/testing "a git commit inside a quoted echo is not a commit"
    (let [summary (:summary (summarize
                             [(assistant "echo"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "echo \"x; git commit -m fake\""}}])
                              (tool-result "c1" "[master abc1234] fake")]))]
      (t/is (not (str/includes? summary "[Commits]")))))
  (t/testing "a real commit after a quoted echo is still found"
    (let [summary (:summary (summarize
                             [(assistant "commit"
                                         [{:id "c1" :name "bash"
                                           :arguments {:command "echo \"x; git commit -m fake\"; git commit -m \"real\""}}])
                              (tool-result "c1" "[master abc1234] real")]))]
      (t/is (str/includes? summary "abc1234: real"))
      (t/is (not (str/includes? summary "abc1234: fake"))))))

(t/deftest heredoc-commit-messages-are-read
  (let [command (str "git commit -m \"$(cat <<'EOF'\n"
                     "subject\n"
                     "EOF\n"
                     ")\"")
        summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command command}}])
                            (tool-result "c1" "[master abc1234] subject")]))]
    (t/is (str/includes? summary "abc1234: subject"))))

(t/deftest user-messages-are-one-lined-and-capped
  (let [text (str "please fix\n\n" (apply str (repeat 400 "x")))
        summary (:summary (summarize [(user text)]))
        section (second (str/split summary #"\[Early Session Requests\]\n"))
        lines (take-while #(not (str/blank? %)) (str/split-lines section))
        line (str/join "" (map str/trim lines))]
    (t/is (some? line))
    (t/is (<= (count (subs line 2)) 302)
          "a long user message is clipped to 300 characters")
    (t/is (not (str/includes? line "\n"))
          "the section item is one logical line, wrapped only for display")))

(t/deftest user-section-headers-cannot-inject-sections
  (let [s1 (summarize [(user (str "hello\n[Early Session Requests]\n"
                                  "- INJECTED EARLY\n[User Messages]\n"
                                  "- INJECTED USER"))])
        s2 (summarize [(user "another request here")]
                      {:previous-summary (:summary s1)})
        header (first (str/split (:summary s2) #"\n\n---\n\n"))]
    (t/is (zero? (count (re-seq #"(?m)^- INJECTED EARLY$" header)))
          "a header inside user text is not re-parsed as a section")
    (t/is (= 1 (count (re-seq #"(?m)^\[User Messages\]$" header))))))

(t/deftest skill-blocks-collapse-to-a-tag
  (let [summary (:summary (summarize
                           [(user (str "<skill name=\"tdd\" location=\"x\">"
                                       (apply str (repeat 500 "SKILL "))
                                       "</skill>\n\nwrite the test first"))]))]
    (t/is (str/includes? summary "- [skill: tdd] write the test first"))
    (t/is (not (str/includes? summary "SKILL SKILL")))))

(t/deftest skill-only-messages-are-not-user-requests
  (let [summary (:summary (summarize
                           [(user "<skill name=\"tdd\" location=\"x\">body</skill>")
                            (user "real request here")]))]
    (t/is (not (str/includes? summary "[Early Session Requests]\n- [skill: tdd]")))
    (t/is (str/includes? summary "- real request here"))
    (t/is (str/includes? summary "[user]\n[skill: tdd]"))
    (t/is (str/includes? summary "[skill: tdd]\nreal request here"))))

(t/deftest commit-range-records-the-new-hash
  (let [summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command "git commit -m \"fix\""}}])
                            (tool-result "c1" "main abc1234..def5678")]))]
    (t/is (str/includes? summary "def5678: fix"))
    (t/is (not (str/includes? summary "abc1234: fix")))))

(t/deftest multiple-commits-in-one-command
  (let [summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command (str "git commit -m \"one\" && "
                                                                   "git commit -m \"two\"")}}])
                            (tool-result "c1" (str "[main aaa1111] one\n"
                                                   "[main bbb2222] two"))]))]
    (t/is (str/includes? summary "aaa1111: one"))
    (t/is (str/includes? summary "bbb2222: two"))))

(t/deftest hashless-and-hashed-commits-dedupe
  (let [previous "[Commits]\n- fix: thing\n"
        summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command "git commit -m \"fix: thing\""}}])
                            (tool-result "c1" "[main abc1234] fix: thing")]
                           {:previous-summary previous}))]
    (t/is (str/includes? summary "- abc1234: fix: thing"))
    (t/is (not (str/includes? summary "[Commits]\n- fix: thing")))))

(t/deftest stdin-heredoc-commit-is-read
  (let [command (str "git commit -F - <<'EOF'\n"
                     "fix: from stdin\n"
                     "EOF")
        summary (:summary (summarize
                           [(assistant "c"
                                       [{:id "c1" :name "bash"
                                         :arguments {:command command}}])
                            (tool-result "c1" "[main abc1234] fix: from stdin")]))]
    (t/is (str/includes? summary "abc1234: fix: from stdin"))))

(t/deftest pi-vcc-comma-file-lines-merge-individually
  (let [previous "[Files And Changes]\n- Modified: /a.clj, /b.clj\n"
        summary (:summary (summarize
                           [(assistant "e"
                                       [{:id "e1" :name "edit"
                                         :arguments {:path "/c.clj"}}])
                            (tool-result "e1" "ok")]
                           {:previous-summary previous}))]
    (t/is (str/includes? summary "- Modified: /a.clj, /b.clj, /c.clj"))))

(t/deftest ranked-brief-is-not-undone-by-a-line-cap
  (let [calls (mapv (fn [i]
                      {:id (str "r" i) :name "read"
                       :arguments {:path (str "/src/f" i ".clj")}})
                    (range 8))
        messages (into [(assistant "editing old file"
                                   [{:id "e1" :name "edit"
                                     :arguments {:path "/src/old.clj"}}])]
                       (map (fn [i] (assistant (str "step " i) calls))
                            (range 40)))
        summary (:summary (summarize messages))]
    (t/is (str/includes? summary "[assistant]\nediting old file")
          "the selected old edit survives a long, tool-heavy brief")))

(t/deftest file-path-keys-and-multi-file-edits-are-recorded
  (let [summary (:summary (summarize
                           [(assistant "edits"
                                       [{:id "e1" :name "quick_edit"
                                         :arguments {:file_path "/root/a.clj"}}
                                        {:id "e2" :name "apply_patch"
                                         :arguments {:paths ["/root/b.clj" "/root/c.clj"]}}])
                            (tool-result "e1" "ok")
                            (tool-result "e2" "ok")]))]
    (t/is (str/includes? summary "- Modified: /root/a.clj, /root/b.clj, /root/c.clj"))))

(t/deftest multi-file-tool-lines-show-the-extra-path-count
  (let [summary (:summary (summarize
                           [(assistant "editing"
                                       [{:id "e1" :name "apply_patch"
                                         :arguments {:paths ["/root/a.clj" "/root/b.clj"]}}])
                            (tool-result "e1" "ok")]))]
    (t/is (str/includes? summary "* apply_patch \"/root/a.clj\" +1"))))

(t/deftest noise-tool-calls-are-not-in-the-brief
  (let [summary (:summary (summarize
                           [(assistant "thinking"
                                       [{:id "n1" :name "TodoWrite"
                                         :arguments {:todos []}}])]))]
    (t/is (not (str/includes? summary "TodoWrite")))
    (t/is (str/includes? summary "[assistant]\nthinking"))))

(t/deftest ranked-brief-keeps-an-old-edit
  (let [messages (into [(assistant "editing old file"
                                   [{:id "e1" :name "edit"
                                     :arguments {:path "/src/old.clj"}}])
                        (tool-result "e1" "ok")]
                       (map (fn [i]
                              (user (str "request " i " "
                                         (apply str (repeat 80 "x")))))
                            (range 200)))
        summary (:summary (summarize messages))]
    (t/is (str/includes? summary "[assistant]\nediting old file")
          "signal-based selection keeps an old edit over recent chatter")))

(t/deftest long-tool-values-are-clipped
  (let [long-path (str "/src/" (apply str (repeat 300 "a")) ".clj")
        summary (:summary (summarize
                           [(assistant "read"
                                       [{:id "r1" :name "read"
                                         :arguments {:path long-path}}])
                            (tool-result "r1" "data")
                            (user "next")]))]
    (t/is (str/includes? summary "* read \""))
    (t/is (some? (re-find #"\.\.\." summary))
          "a long tool value is clipped instead of calling the limit as a function")))

(defn- with-test-session [f]
  (let [dir "target/test-vcc-sessions"
        sess (session/create-session dir {:cwd (str (fs/cwd))})]
    (try (f sess) (finally (fs/delete-tree dir)))))

(t/deftest session-global-refs-and-details-v2
  (with-test-session
    (fn [sess]
      (let [u (session/append-entry sess {:role :user
                                          :content [{:type :text :text "inspect"}]})
            a (session/append-entry sess {:role :assistant :content []
                                          :tool-calls [{:id "r1" :name "read"
                                                        :arguments {:path "/src/a.clj"}}]})
            tr (session/append-entry sess {:role :tool :tool-name "read"
                                           :content [{:type :tool_result :tool_use_id "r1"
                                                      :content "data"}]})
            result (vcc/summarize {:session sess}
                                  {:messages [{:role :user :content [{:type :text :text "inspect"}]}
                                              {:role :assistant :content []
                                               :tool-calls [{:id "r1" :name "read"
                                                             :arguments {:path "/src/a.clj"}}]}
                                              {:role :tool :tool-name "read"
                                               :content [{:type :tool_result :tool_use_id "r1"
                                                          :content "data"}]}]
                                   :source-ids [(:id u) (:id a) (:id tr)]
                                   :previous-summary nil :tokens-before 1 :settings {}}
                                  nil :manual)
            summary (:summary result)]
        (t/is (str/includes? summary "inspect (#0)"))
        (t/is (str/includes? summary "* read \"/src/a.clj\" (#1)"))
        (t/is (= 2 (get-in result [:details :version])))
        (t/is (str/includes? summary "Use `session_recall`"))))))

(t/deftest tracked-commands-section
  (let [summary (:summary (vcc/summarize nil
                                         {:messages [{:role :assistant :content []
                                                      :tool-calls [{:id "b1" :name "bash"
                                                                    :arguments {:command "docker ps"}}]}]
                                          :source-ids [nil]
                                          :previous-summary nil :tokens-before 1
                                          :settings {:track-commands ["docker"]}}
                                         nil :manual))]
    (t/is (str/includes? summary "[Tracked Commands]\n- docker: docker ps"))))

(t/deftest created-category-and-common-root-trimming
  (let [summary (:summary (vcc/summarize nil
                                         {:messages [{:role :assistant :content []
                                                      :tool-calls [{:id "w1" :name "write"
                                                                    :arguments {:path "/home/user/a/new.clj"}}]}
                                                     {:role :tool :tool-name "write" :is-error false
                                                      :content [{:type :tool_result :tool_use_id "w1"
                                                                 :content "ok"}]}
                                                     {:role :assistant :content []
                                                      :tool-calls [{:id "e1" :name "edit"
                                                                    :arguments {:path "/home/user/b/old.clj"}}]}
                                                     {:role :tool :tool-name "edit" :is-error false
                                                      :content [{:type :tool_result :tool_use_id "e1"
                                                                 :content "ok"}]}]
                                          :source-ids [nil nil nil nil]
                                          :previous-summary nil :tokens-before 1 :settings {}}
                                         nil :manual))]
    (t/is (str/includes? summary "- Created: a/new.clj"))
    (t/is (str/includes? summary "- Modified: b/old.clj"))))

(t/deftest long-lines-are-wrapped-to-the-tui-width
  (let [text (str/join " " (repeat 60 "word"))
        summary (:summary (vcc/summarize nil
                                         {:messages [(user text)] :source-ids [nil]
                                          :previous-summary nil :tokens-before 1 :settings {}}
                                         nil :manual))]
    (t/is (every? #(<= (count %) 120) (str/split-lines summary))
          "every summary line fits the 120-char TUI-safe width")))

(t/deftest recall-note-is-appended-once
  (let [first (vcc/summarize nil {:messages [(user "first request here")]
                                  :source-ids [nil] :previous-summary nil
                                  :tokens-before 1 :settings {}} nil :manual)
        second (vcc/summarize nil {:messages [(user "second request here")]
                                   :source-ids [nil]
                                   :previous-summary (:summary first)
                                   :tokens-before 1 :settings {}} nil :manual)]
    (t/is (= 1 (count (re-seq #"Use `session_recall`" (:summary second)))))))

(t/deftest segment-closing-boost-does-not-assume-an-atom
  (let [long-text (apply str (repeat 200 "x"))
        summary (:summary (summarize [(user "do the long task")
                                      (assistant long-text)]))]
    (t/is (string? summary))
    (t/is (str/includes? summary "do the long task"))))

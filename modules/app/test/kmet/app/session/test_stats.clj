(ns kmet.app.session.test-stats
  "Statistics: prompt history, message counts, usage totals, and per-tool
   token attribution."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.session.core :as s]
            [kmet.app.session.test-support :as support]))

(t/use-fixtures :once support/with-clean-test-dir)

(t/deftest test-session-first-message
  ;; pi: buildSessionInfo firstMessage — the first user message text
  (let [session (s/create-session support/test-dir)]
    (t/is (= "(no messages)" (s/get-first-message session)) "empty session")
    (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "hi"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "steered"}]})
    (t/is (= "hello" (s/get-first-message session)) "first user message wins")))

(t/deftest test-session-prompt-history
  ;; Prompt history (editor Up/Down) is reconstituted from :user entries of
  ;; the ACTIVE branch on session resume (pi: buildContextEntries — abandoned
  ;; branches and pre-compaction messages excluded), matching the editor's
  ;; conventions: empty skipped, consecutive duplicates collapsed, newest 100
  ;; kept (pi: addToHistory).
  (let [session (s/create-session support/test-dir)]
    (t/is (empty? (s/get-prompt-history session)) "empty session")
    (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "hi"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "how are you"}]})
    (s/append-entry session {:role :bash :command "ls" :output "" :exit-code 0})
    (s/append-entry session {:role :user :content [{:type :text :text ""}]})
    (t/is (= ["hello" "how are you"] (s/get-prompt-history session))
          "user messages in order, empty messages skipped")
    ;; consecutive duplicates are collapsed (editor-push-history! dedupes)
    (s/append-entry session {:role :user :content [{:type :text :text "how are you"}]})
    (t/is (= ["hello" "how are you"] (s/get-prompt-history session))
          "consecutive duplicate collapsed")))

(t/deftest test-session-prompt-history-active-branch-only
  ;; Prompts from an abandoned branch are never in the restored history —
  ;; the active branch (root→leaf) is the only source (pi: buildContextEntries).
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "kept"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})
    ;; branch point: "old" prompt + its assistant reply, then branch back
    (let [branch-at (:id (s/append-entry session {:role :user :content [{:type :text :text "old"}]}))]
      (s/append-entry session {:role :assistant :content [{:type :text :text "b"}]})
      (s/branch! session branch-at)
      (s/append-entry session {:role :user :content [{:type :text :text "new"}]})
      (s/append-entry session {:role :assistant :content [{:type :text :text "c"}]})
      (t/is (= ["kept" "old" "new"] (s/get-prompt-history session))
            "active branch only — 'old' is on the path, abandoned siblings excluded")))
  ;; compaction: pre-compaction prompts are excluded (pi: buildContextEntries
  ;; returns [compaction, ...from first-kept-id])
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "old prompt"}]})
    (let [kept (:id (s/append-entry session {:role :assistant :content [{:type :text :text "x"}]}))]
      (s/append-entry session {:role :user :content [{:type :text :text "new prompt"}]})
      (s/append-entry session {:role :assistant :content [{:type :text :text "y"}]})
      (s/compact-with-summary! session "summary" kept)
      (t/is (= ["new prompt"] (s/get-prompt-history session))
            "pre-compaction prompts excluded"))))

(t/deftest test-session-message-count
  ;; pi: buildSessionInfo messageCount — message entries only. kmet's :bash
  ;; role is the EDN analogue of pi's tool-message entries (bash results are
  ;; stored as tool messages in pi), so :bash counts; display-only :info
  ;; entries don't (pi: custom_message is a separate entry type).
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "b"}]})
    (s/append-entry session {:role :session_info :name "t"})
    (s/append-entry session {:role :bash :command "ls" :output "" :exit-code 0})
    (s/append-entry session {:role :info :label "display-only" :content "x"})
    (t/is (= 3 (s/get-message-count session)))))

(t/deftest test-session-last-activity
  ;; pi: modified — the last entry's timestamp, falling back to file mtime
  (let [session (s/create-session support/test-dir)
        now (System/currentTimeMillis)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (let [ms (s/get-last-activity-ms session)]
      (t/is (number? ms))
      (t/is (>= ms (- now 5000)) "last activity is recent"))))

(t/deftest test-session-last-activity-empty
  ;; Unsaved (lazy) sessions have no file — last activity falls back to the
  ;; header created-at (G4), so the resume dialog can still format an age.
  (let [session (s/create-session support/test-dir)]
    (t/is (number? (s/get-last-activity-ms session)))
    (t/is (pos? (s/get-last-activity-ms session)))))

(t/deftest test-usage-totals
  (let [dir (str "target/test-sess-usage-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "hi"})
      (s/append-entry sess {:role :assistant :content "a"
                            :usage {:prompt_tokens 10 :completion_tokens 2}})
      (s/append-entry sess {:role :assistant :content "b"
                            :usage {:prompt_tokens 20 :completion_tokens 4
                                    :prompt_tokens_details {:cached_tokens 6}}})
      (s/append-entry sess {:role :assistant :content "c"
                            :usage {:prompt_tokens 100 :completion_tokens 0
                                    :cost {:input 0.001 :output 0.0
                                           :cache-read 0.0 :cache-write 0.0
                                           :total 0.001}}})
      (t/is (= {:input 124 :output 6 :cache-read 6 :cache-write 0 :cost 0.001}
               (s/usage-totals sess)))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-stats
  ;; G22 /session: get-session-stats aggregates over ALL entries (pi:
  ;; getSessionStats) — user/assistant counts, tool results (:tool and
  ;; :bash), tool calls from assistant :tool-calls, and usage totals.
  (let [dir (str "target/test-sess-stats-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "q1"})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "a1"}]
                            :tool-calls [{:name "read" :arguments {:path "x"}}]
                            :usage {:prompt_tokens 100 :completion_tokens 20
                                    :cost {:total 0.01}}})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "a2"}]
                            :usage {:prompt_tokens 50 :completion_tokens 10
                                    :cost {:total 0.005}}})
      (s/append-entry sess {:role :tool :content "result" :tool-name "read"})
      (s/append-entry sess {:role :bash :command "ls" :output "x" :exit-code 0})
      (s/append-entry sess {:role :info :content "display only"})
      (let [stats (s/get-session-stats sess)]
        (t/is (= 1 (:user-messages stats)))
        (t/is (= 2 (:assistant-messages stats)))
        (t/is (= 1 (:tool-calls stats)) "one tool call on the first assistant")
        (t/is (= 2 (:tool-results stats)) ":tool and :bash both count as results")
        (t/is (= 5 (:total-messages stats)) ":info is display-only, not a message")
        (t/is (= {:input 150 :output 30 :cache-read 0 :cache-write 0 :total 180}
                 (:tokens stats)))
        (t/is (= 0.015 (:cost stats))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-stats-empty
  (let [sess (s/create-session support/test-dir)
        stats (s/get-session-stats sess)]
    (t/is (= 0 (:user-messages stats)))
    (t/is (= 0 (:total-messages stats)))
    (t/is (= {:input 0 :output 0 :cache-read 0 :cache-write 0 :total 0}
             (:tokens stats)))
    (t/is (= 0.0 (:cost stats)))))

(t/deftest test-last-assistant-text
  ;; G22 /copy: last assistant message with non-empty text on the branch;
  ;; empty (aborted) assistant entries are skipped.
  (let [dir (str "target/test-sess-last-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (t/is (nil? (s/get-last-assistant-text sess)) "no messages yet")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :assistant :content []})  ;; aborted, no content
      (s/append-entry sess {:role :assistant :content [{:type :text :text "first"}]})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "  second  "}]})
      (t/is (= "second" (s/get-last-assistant-text sess)))
      (s/append-entry sess {:role :assistant :content []})  ;; aborted again
      (t/is (= "second" (s/get-last-assistant-text sess))
            "trailing empty assistant does not shadow the last text")
      (finally (fs/delete-tree dir)))))

(t/deftest test-usage-breakdown
  ;; G22 /session cost breakdown (pi: getUsageCostBreakdown) — assistant
  ;; usage attributed to the model active at that point (:model-change),
  ;; compaction/summary usage under "Tools/summaries", sorted by cost desc.
  (let [dir (str "target/test-sess-breakdown-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-model-change! sess :anthropic "claude-3")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :assistant :content "a"
                            :usage {:prompt_tokens 100 :completion_tokens 20 :cost {:total 0.01}}})
      (s/append-model-change! sess :openai "gpt-4o")
      (s/append-entry sess {:role :assistant :content "b"
                            :usage {:prompt_tokens 50 :completion_tokens 5 :cost {:total 0.02}}})
      (s/append-entry sess {:role :compaction :summary "x" :first-kept-id "y"
                            :usage {:input_tokens 10 :output_tokens 2 :cost {:total 0.001}}})
      (let [b (s/usage-breakdown sess)]
        (t/is (= ["openai/gpt-4o" "anthropic/claude-3" "Tools/summaries"]
                 (mapv :key b))
              "sorted by cost desc; compaction under Tools/summaries")
        (t/is (= 55 (:tokens (first b))))
        (t/is (= 12 (:tokens (last b)))))
      (t/is (= [] (s/usage-breakdown (s/create-session support/test-dir)))
            "no usage -> no buckets")
      (finally (fs/delete-tree dir)))))

(t/deftest test-usage-breakdown-model-stamps
  ;; pi parity: assistant entries carry their own :provider/:model provenance
  ;; (add-assistant-message!) and are attributed by it directly; the
  ;; :model-change walk is only the fallback for legacy unstamped entries.
  (let [dir (str "target/test-sess-breakdown-stamps-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      ;; no :model-change at all — initial-model usage must not be unknown
      (s/append-entry sess {:role :assistant :content "a"
                            :provider :anthropic :model "claude-3"
                            :usage {:prompt_tokens 100 :completion_tokens 10 :cost {:total 0.01}}})
      ;; mid-session switch: the walk says openai/gpt-4o, the stamp wins
      (s/append-model-change! sess :openai "gpt-4o")
      (s/append-entry sess {:role :assistant :content "b"
                            :provider :anthropic :model "claude-3"
                            :usage {:prompt_tokens 50 :completion_tokens 5 :cost {:total 0.02}}})
      (let [b (s/usage-breakdown sess)]
        (t/is (= ["anthropic/claude-3"] (mapv :key b))
              "stamped entries attribute by their own provider/model, stamp beats walk")
        (t/is (= 165 (:tokens (first b))))
        (t/is (== 0.03 (:cost (first b))) "usage and cost sum across both segments"))
      (finally (fs/delete-tree dir))))
  (t/testing "legacy files: no stamp and no switch yet lands under the unknown bucket"
    (let [dir (str "target/test-sess-breakdown-unknown-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (s/append-entry sess {:role :assistant :content "a"
                              :usage {:prompt_tokens 10 :completion_tokens 1}})
        (t/is (= ["unknown"] (mapv :key (s/usage-breakdown sess))))
        (finally (fs/delete-tree dir))))))

;; ─── Tool-result token attribution ─────────────────────────────────────────

(t/deftest test-session-tool-usage
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "hi"})
    (s/append-entry session {:role :tool
                             :tool-name "read"
                             :content [{:type :tool_result
                                        :tool_use_id "t1"
                                        :content (support/pad-string 400 \a)}]})
    (s/append-entry session {:role :tool
                             :tool-name "grep"
                             :content [{:type :tool_result
                                        :tool_use_id "t2"
                                        :content "ab"}]})
    ;; an assistant entry publishes the file (lazy creation G4)
    (s/append-entry session {:role :assistant :content "ok"})
    (t/testing "tool results carry an estimated :result-tokens (chars/4)"
      (let [read-entry (first (filter #(= "read" (:tool-name %))
                                      @(:entries session)))]
        (t/is (= 100 (:result-tokens read-entry)))))
    (t/testing "tool-usage derives per-tool totals + a :total"
      (let [u (s/tool-usage session)]
        (t/is (= {:calls 1 :tokens 100} (get u "read")))
        (t/is (= {:calls 1 :tokens 1} (get u "grep")))
        (t/is (= {:calls 2 :tokens 101} (:total u)))))
    (t/testing "the report names the tools and totals"
      (let [report (s/tool-usage-report session)]
        (t/is (str/includes? report "read"))
        (t/is (str/includes? report "TOTAL"))))
    (t/testing "loaded sessions report from the persisted entries"
      (let [loaded (s/load-session (:file session))]
        (t/is (= {:calls 2 :tokens 101} (:total (s/tool-usage loaded))))))
    (t/testing "unstamped tool entries (rebuilt contexts, legacy files) estimate on the fly"
      (let [rebuilt (s/create-session support/test-dir)]
        (s/replace-entries! rebuilt [{:role :tool
                                      :tool-name "bash"
                                      :content [{:type :tool_result
                                                 :tool_use_id "t3"
                                                 :content (support/pad-string 40 \b)}]}])
        (t/is (= {:calls 1 :tokens 10} (get (s/tool-usage rebuilt) "bash")))))))

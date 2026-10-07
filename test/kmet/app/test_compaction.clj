(ns kmet.app.test-compaction
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.compaction :as compaction]))

;; ─── Token estimation (pi: estimateTokens) ─────────────────────────────────

(t/deftest test-estimate-tokens
  (t/testing "string content ~ chars/4"
    (t/is (= 3 (compaction/estimate-tokens {:role :user :content "0123456789ab"}))))
  (t/testing "text blocks count, image blocks count fixed chars"
    (t/is (pos? (compaction/estimate-tokens {:role :user :content [{:type :text :text "hello"}]})))
    (t/is (pos? (compaction/estimate-tokens {:role :user :content [{:type :image :data "x"}]}))))
  (t/testing "tool results count their text"
    (t/is (pos? (compaction/estimate-tokens {:role :tool :content [{:type :tool_result :content "big output here"}]}))))
  (t/testing "assistant thinking and tool calls count"
    (t/is (pos? (compaction/estimate-tokens {:role :assistant
                                             :content [{:type :thinking :thinking "hmm..."}]
                                             :tool-calls [{:name "read" :arguments {:path "/x"}}]}))))
  (t/testing "info, session_info, and excluded bash contribute 0"
    (t/is (zero? (compaction/estimate-tokens {:role :info :content "x"})))
    (t/is (zero? (compaction/estimate-tokens {:role :session_info :name "my session"})))
    (t/is (zero? (compaction/estimate-tokens {:role :bash :command "ls" :output "out" :exclude-from-context? true}))))
  (t/testing "bash without exclusion counts command + output"
    (t/is (pos? (compaction/estimate-tokens {:role :bash :command "ls" :output "out"})))))

(t/deftest test-estimate-tokens-summary-roles
  (t/is (pos? (compaction/estimate-tokens
               {:role :branch-summary :summary "a fairly long summary of the abandoned branch"}))
        "branch_summary projects to a user message — its text is context")
  (t/is (pos? (compaction/estimate-tokens
               {:role :compaction :summary "a fairly long compaction summary"})))
  (t/is (zero? (compaction/estimate-tokens {:role :label :target-id "x" :label "l"}))
        "labels are navigation metadata — never in context"))

;; ─── Cut point (pi: findCutPoint) ──────────────────────────────────────────

(t/deftest test-find-cut-point
  (t/testing "empty entries"
    (t/is (= {:first-kept-index 0 :split-turn? false}
             (compaction/find-cut-point [] 100))))
  (t/testing "never cuts at tool results"
    (let [entries [{:role :user :content "aaaa"}          ;; 1 token
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbb"}]} ;; 2 tokens
                   {:role :user :content "cccc"}]         ;; 1 token
          cut (compaction/find-cut-point entries 1)]
      (t/is (not= 1 (:first-kept-index cut))
            "tool result index is not a valid cut point")))
  (t/testing "budget not reached → keep from first valid"
    (let [entries [{:role :user :content "a"}
                   {:role :assistant :content "b"}]]
      (t/is (= 0 (:first-kept-index (compaction/find-cut-point entries 1000))))))
  (t/testing "split-turn detection"
    (let [entries [{:role :user :content "q"}
                   {:role :assistant :content "aaaaaaaaaaaaaaaaaaaa"}  ;; 5 tokens
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbbbbbbbbbbbbbb"}]}
                   {:role :assistant :content "cccccccccccccccccccc"}] ;; 5 tokens
          cut (compaction/find-cut-point entries 8)]
      (t/is (true? (:split-turn? cut)) "cut lands mid-turn (not a turn start)"))))

;; ─── Preparation (pi: prepareCompaction) ───────────────────────────────────

(t/deftest test-prepare
  (t/testing "nothing to summarize → nil"
    (t/is (nil? (compaction/prepare [] 100)))
    (t/is (nil? (compaction/prepare [{:role :user :content "tiny"}] 100))))
  (t/testing "cut point selected; context-visible messages collected"
    (let [entries (vec (for [i (range 20)]
                         {:id (str "e" i)
                          :role :user
                          :content (apply str (repeat 100 (str i)))}))  ;; ~25 tokens each
          prep (compaction/prepare entries 100)]
      (t/is (some? prep))
      (t/is (string? (:first-kept-id prep)))
      (t/is (pos? (count (:messages prep))))
      (t/is (every? #(= :user (:role %)) (:messages prep)))))
  (t/testing "previous summary found and reported"
    (let [entries (into [{:id "s1" :role :system :summary "OLD SUMMARY"
                          :content [{:type :text :text "OLD SUMMARY"}]}]
                        (for [i (range 20)]
                          {:id (str "e" i)
                           :role :user
                           :content (apply str (repeat 100 (str i)))}))
          prep (compaction/prepare entries 100)]
      (t/is (= "OLD SUMMARY" (:previous-summary prep)))
      (t/is (not-any? #(= "s1" (:id %)) (:messages prep))
            "previous summary entry is not summarized again")))
  (t/testing "tokens-before is the total estimate"
    (let [entries [{:id "a" :role :user :content (apply str (repeat 16 "x"))}
                   {:id "b" :role :user :content (apply str (repeat 16 "y"))}
                   {:id "c" :role :user :content (apply str (repeat 16 "z"))}]
          prep (compaction/prepare entries 8)]
      (t/is (= 12 (:tokens-before prep))))))

(t/deftest test-prepare-boundary-after-compaction
  ;; pi: boundaryStart = the previous compaction's firstKeptEntryId — its kept
  ;; tail is re-summarizable. Under append-only the tail sits before the
  ;; compaction; a boundary of prev-idx+1 would skip it, dropping the kept
  ;; tail from context without summarizing it on the next compaction.
  (let [msgs (vec (for [i (range 10)]
                    {:id (str "m" i) :role :user
                     :content (apply str (repeat 100 (str i)))}))
        c1 {:id "c1" :role :compaction :summary "FIRST" :first-kept-id "m6"}
        tail (vec (for [i (range 10 13)]
                    {:id (str "m" i) :role :user
                     :content (apply str (repeat 100 (str i)))}))
        entries (into (conj msgs c1) tail)
        prep (compaction/prepare entries 100)]
    (t/is (some? prep))
    (t/is (= "FIRST" (:previous-summary prep)))
    (t/is (some #(contains? #{"m6" "m7" "m8" "m9"} (:id %)) (:messages prep))
          "previous kept tail is re-summarized, not dropped")
    (let [kept-idx (first (keep-indexed (fn [i e] (when (= (:id e) (:first-kept-id prep)) i))
                                        entries))]
      (t/is (>= kept-idx 6)
            "cut never lands before the previous first-kept"))))

(t/deftest test-prepare-guard-after-compaction
  ;; pi: prepareCompaction returns undefined when the newest entry is a
  ;; compaction — prevents immediate re-compaction (e.g. overflow-retry).
  (let [entries [{:id "m0" :role :user :content "hello"}
                 {:id "c1" :role :compaction :summary "SUM" :first-kept-id "m0"}]]
    (t/is (nil? (compaction/prepare entries 100)))))

;; ─── Serialization (pi: serializeConversation) ─────────────────────────────

(t/deftest test-serialize-conversation
  (t/testing "user/assistant/tool roles serialized"
    (let [entries [{:role :user :content "hello"}
                   {:role :assistant :content [{:type :text :text "hi there"}]
                    :tool-calls [{:name "read" :arguments {:path "/x"}}]}
                   {:role :tool :content [{:type :tool_result :content "result text"}]}]
          text (compaction/serialize-conversation entries)]
      (t/is (str/includes? text "[User]: hello"))
      (t/is (str/includes? text "[Assistant]: hi there"))
      (t/is (str/includes? text "read("))
      (t/is (str/includes? text "[Tool result]: result text"))))
  (t/testing "info and excluded bash are skipped"
    (let [text (compaction/serialize-conversation
                [{:role :info :content "ignored"}
                 {:role :bash :command "ls" :output "out" :exclude-from-context? true}])]
      (t/is (empty? text))))
  (t/testing "compaction/branch_summary entries serialize their summary (pi:
              convertToLlm maps both to user messages before serialization —
              they survive a later compaction)"
    (let [text (compaction/serialize-conversation
                [{:role :branch-summary :summary "abandoned branch"}
                 {:role :compaction :summary "old conversation"}])]
      (t/is (str/includes? text "[User]: abandoned branch"))
      (t/is (str/includes? text "[User]: old conversation")))))

;; ─── Summarization request (pi: generateSummaryWithUsage) ─────────────────

(t/deftest test-summarization-messages
  (t/testing "initial prompt without previous summary"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] nil nil)]
      (t/is (= :system (:role (first msgs))))
      (let [text (-> msgs second :content first :text)]
        (t/is (str/includes? text "<conversation>"))
        (t/is (str/includes? text "## Goal"))
        (t/is (not (str/includes? text "<previous-summary>"))))))
  (t/testing "update prompt with previous summary"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] "OLD" nil)
          text (-> msgs second :content first :text)]
      (t/is (str/includes? text "<previous-summary>\nOLD\n</previous-summary>"))
      (t/is (str/includes? text "PRESERVE all existing information"))))
  (t/testing "custom instructions appended"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] nil "focus on tests")]
      (t/is (str/includes? (-> msgs second :content first :text)
                           "Additional focus: focus on tests")))))

;; ─── Context token measurement (pi: estimateContextTokens) ────────────────

(t/deftest test-branch-summary-messages
  (t/testing "custom instructions without replace — appended as Additional focus"
    (let [msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                "focus on fallbacks" false)
          text (-> msgs second :content first :text)]
      (t/is (str/includes? text "Additional focus: focus on fallbacks"))))
  (t/testing "replaceInstructions true — custom replaces builtin prompt"
    (let [custom "Custom summary format: Section A, Section B."
          msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                custom true)
          text (-> msgs second :content first :text)]
      (t/is (str/includes? text custom))
      (t/is (not (str/includes? text "Create a structured summary of this conversation branch"))
            "builtin prompt replaced — not appended")
      (t/is (not (str/includes? text "Additional focus"))
            "no Additional focus wrapper when replacing")))
  (t/testing "replaceInstructions with no custom → still builtin"
    (let [msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                nil true)
          text (-> msgs second :content first :text)]
      (t/is (str/includes? text "Create a structured summary")
            "empty custom with replace=true falls back to builtin")
      (t/is (not (str/includes? text "Additional focus")))))
  (t/testing "two-arity overload still appends (BC)"
    (let [msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                "focus on tests")
          text (-> msgs second :content first :text)]
      (t/is (str/includes? text "Additional focus: focus on tests"))))
  (t/testing "empty custom never adds Additional focus"
    (let [msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                "" false)
          text (-> msgs second :content first :text)]
      (t/is (not (str/includes? text "Additional focus"))))
    (let [msgs (compaction/branch-summary-messages
                [{:role :user :content [{:type :text :text "hello"}]}]
                nil false)
          text (-> msgs second :content first :text)]
      (t/is (not (str/includes? text "Additional focus"))))))

(t/deftest test-prepare-branch-entries
  (t/testing "pi: prepareBranchEntries — whole entries newest-first within the
              token budget"
    (let [u (fn [id text] {:id id :role :user
                           :content [{:type :text :text text}]})
          entries [(u "1" (apply str (repeat 100 "a")))
                   (u "2" (apply str (repeat 100 "b")))
                   (u "3" (apply str (repeat 100 "c")))]]
      (t/is (= entries (compaction/prepare-branch-entries entries 0))
            "0 = no limit")
      (t/is (= entries (compaction/prepare-branch-entries entries nil)))
      (t/is (= entries (compaction/prepare-branch-entries entries 1000)))
      (t/is (= ["3"] (mapv :id (compaction/prepare-branch-entries entries 25)))
            "only the newest entry (25 tokens) fits")
      (t/is (= ["2" "3"] (mapv :id (compaction/prepare-branch-entries entries 50)))
            "whole entries, chronological order")))
  (t/testing "entries that project to no context message neither count nor end
              the walk"
    (let [entries [{:id "1" :role :user
                    :content [{:type :text :text (apply str (repeat 100 "a"))}]}
                   {:id "info" :role :info :content "display only"}
                   {:id "2" :role :user
                    :content [{:type :text :text (apply str (repeat 100 "b"))}]}]]
      (t/is (= ["1" "2"] (mapv :id (compaction/prepare-branch-entries entries 50)))
            "the :info entry is skipped without consuming budget")))
  (t/testing "a summary entry still joins while the total stays under 90% of
              the budget (pi)"
    (let [entries [{:id "c" :role :compaction :summary (apply str (repeat 200 "s"))}
                   {:id "1" :role :user
                    :content [{:type :text :text (apply str (repeat 100 "a"))}]}]]
      ;; user = 25 tokens, compaction = 50; budget 40: the compaction does not
      ;; fit, but 25 < 0.9 * 40
      (t/is (= ["c" "1"] (mapv :id (compaction/prepare-branch-entries entries 40)))
            "the summary joins even though it overflows the budget")
      ;; budget 27: 25 < 0.9 * 27 is false → dropped, and the walk ends
      (t/is (= ["1"] (mapv :id (compaction/prepare-branch-entries entries 27)))
            "past the 90% line it is dropped like anything else"))))

(t/deftest test-context-tokens
  (t/testing "measured usage of the latest assistant + estimate of trailing entries"
    (let [entries [{:role :assistant :content [{:type :text :text "a"}]
                    :usage {:prompt_tokens 1000 :completion_tokens 200
                            :prompt_tokens_details {:cached_tokens 300}}}
                   {:role :user :content [{:type :text :text "hello world"}]}]]
      ;; 700 input + 200 output + 300 cacheRead, plus ceil(11/4)=3 trailing
      (t/is (= 1203 (compaction/context-tokens entries)))))
  (t/testing "compaction entries carry summarization usage — never counted"
    (let [entries [{:role :compaction :summary "s"
                    :usage {:prompt_tokens 999 :completion_tokens 999}}
                   {:role :assistant :content [{:type :text :text "a"}]
                    :usage {:prompt_tokens 100 :completion_tokens 20
                            :prompt_tokens_details {:cached_tokens 30}}}]]
      ;; assistant only: 70+20+30 = 120
      (t/is (= 120 (compaction/context-tokens entries)))))
  (t/testing "pure estimate when no assistant reports usage"
    (let [entries [{:role :user :content [{:type :text :text "hello world"}]}]]
      (t/is (= 3 (compaction/context-tokens entries)))))
  (t/testing "unknown (nil) when no assistant responded after the latest compaction"
    (let [entries [{:role :assistant :content [{:type :text :text "old"}]
                    :usage {:prompt_tokens 1000 :completion_tokens 1}}
                   {:role :compaction :summary "s"}]]
      (t/is (nil? (compaction/context-tokens entries))
            "kept-tail usage predates the compaction and reflects the old context — pi: unknown until the next response")))
  (t/testing "kept-tail assistant usage is stale — only branch-post-compaction responses count"
    (let [entries [{:role :assistant :content [{:type :text :text "kept-tail"}]
                    :usage {:prompt_tokens 8000 :completion_tokens 100}}
                   {:role :compaction :summary "s" :first-kept-id "kept"}
                   {:role :user :content [{:type :text :text "kept"}]}]]
      (t/is (nil? (compaction/context-tokens entries))
            "the kept-tail assistant predates the compaction in the branch — its usage reflects the old, larger context"))))

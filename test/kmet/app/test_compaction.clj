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

;; ─── Cut point (pi: findProjectedCutPoint) ────────────────────────────────

(defn- projected
  "Raw entries as an unedited session projection (see
   session/project-entries): each entry projects to itself."
  [entries]
  (mapv (fn [e] {:source e :messages [e]}) entries))

(t/deftest test-find-projected-cut-point
  (t/testing "empty projection"
    (t/is (= {:first-kept-index 0 :split-turn? false}
             (compaction/find-projected-cut-point [] 100))))
  (t/testing "never cuts at tool results"
    (let [entries [{:role :user :content "aaaa"}          ;; 1 token
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbb"}]} ;; 2 tokens
                   {:role :user :content "cccc"}]         ;; 1 token
          cut (compaction/find-projected-cut-point (projected entries) 1)]
      (t/is (not= 1 (:first-kept-index cut))
            "tool result index is not a valid cut point")))
  (t/testing "budget not reached → keep from first valid"
    (let [entries [{:role :user :content "a"}
                   {:role :assistant :content "b"}]]
      (t/is (= 0 (:first-kept-index
                  (compaction/find-projected-cut-point (projected entries) 1000))))))
  (t/testing "split-turn detection"
    (let [entries [{:role :user :content "q"}
                   {:role :assistant :content "aaaaaaaaaaaaaaaaaaaa"}  ;; 5 tokens
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbbbbbbbbbbbbbb"}]}
                   {:role :assistant :content "cccccccccccccccccccc"}] ;; 5 tokens
          cut (compaction/find-projected-cut-point (projected entries) 8)]
      (t/is (true? (:split-turn? cut)) "cut lands mid-turn (not a turn start)")))
  (t/testing "an omitted (empty) projected entry is no cut point, and the cut
              walks back over it (pi: the while loop after the budget walk)"
    (let [entries [{:role :user :content "aaaa"}
                   {:role :user :content "bbbb"}
                   {:role :user :content "cccc"}]
          cut (compaction/find-projected-cut-point
               (assoc (projected entries) 1 {:source (second entries) :messages []}) 1)]
      (t/is (= 1 (:first-kept-index cut))
            "the empty entry is kept rather than summarized — it costs nothing"))))

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
    (let [entries (into [{:id "c1" :role :compaction :summary "OLD SUMMARY"
                          :first-kept-id "e0"}]
                        (for [i (range 20)]
                          {:id (str "e" i)
                           :role :user
                           :content (apply str (repeat 100 (str i)))}))
          prep (compaction/prepare entries 100)]
      (t/is (= "OLD SUMMARY" (:previous-summary prep)))
      (t/is (not-any? #(= "c1" (:id %)) (:messages prep))
            "the previous summary entry is carried as previous-summary, not
             summarized again (pi: getMessagesFromProjectedEntryForCompaction
             skips compaction sources)")))
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

(t/deftest test-prepare-follows-the-projection
  ;; pi: findProjectedCutPoint / getMessagesFromProjectedEntryForCompaction —
  ;; the compaction input is the projection: an omitted message is not
  ;; summarized, a rewritten one is summarized with its replacement text, and
  ;; :tokens-before counts only what the projection sends
  (t/testing "an omitted message is neither summarized nor counted"
    (let [entries (into [{:id "u1" :role :user :content (apply str (repeat 100 "s"))}
                         {:id "e1" :role :context-edit :target-id "u1" :replacement nil}]
                        (for [i (range 2 5)]
                          {:id (str "u" i) :role :user
                           :content (apply str (repeat 100 (str i)))}))
          prep (compaction/prepare entries 50)]
      (t/is (some? prep))
      (t/is (not-any? #(= "u1" (:id %)) (:messages prep))
            "the omitted message is not summarized")
      (t/is (= 75 (:tokens-before prep))
            "tokens-before follows the projection (3 visible messages x 25)")))
  (t/testing "a rewritten message is summarized with its replacement"
    (let [entries [{:id "u1" :role :user :content (apply str (repeat 100 "s"))}
                   {:id "e1" :role :context-edit :target-id "u1"
                    :replacement {:content [{:type :text :text "public note"}]}}
                   {:id "u2" :role :user :content (apply str (repeat 100 "a"))}
                   {:id "u3" :role :user :content (apply str (repeat 100 "b"))}]
          prep (compaction/prepare entries 50)
          text (compaction/serialize-conversation (:messages prep))]
      (t/is (some? prep))
      (t/is (str/includes? text "[User]: public note"))
      (t/is (not (str/includes? text "ssss"))
            "the original text is not summarized")
      (t/is (< (:tokens-before prep) 75)
            "the replacement counts instead of the original"))))

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
  (t/testing "bash results and custom messages serialize as user text
              (pi: convertToLlm maps both to user before serializeConversation)"
    (let [text (compaction/serialize-conversation
                [{:role :bash :command "ls" :output "out"}
                 {:role :custom :custom-type :note
                  :content [{:type :text :text "note text"}]}
                 {:role :custom-message :custom-type :note :content "raw note"}])]
      (t/is (str/includes? text "[User]: Ran `ls`\n```\nout\n```"))
      (t/is (str/includes? text "[User]: note text"))
      (t/is (str/includes? text "[User]: raw note")
            "a raw :custom-message entry (branch summaries) serializes too")))
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

(t/deftest test-projected-context-tokens
  ;; pi: estimateProjectedContextTokens — a usage-based measurement stays
  ;; valid only while its source message comes after the latest context edit
  ;; or compaction; otherwise the whole projection is estimated.
  (let [assistant {:id "a1" :role :assistant :content [{:type :text :text "a"}]
                   :usage {:prompt_tokens 1000 :completion_tokens 200
                           :prompt_tokens_details {:cached_tokens 300}}}
        user {:id "u1" :role :user :content [{:type :text :text "hello world"}]}
        edit {:id "x1" :role :context-edit :target-id "u1" :replacement nil}
        projected [{:source assistant :messages [assistant]}
                   {:source user :messages [user]}]]
    (t/testing "no edit: measured usage + trailing estimate, like context-tokens"
      ;; 700 input + 200 output + 300 cacheRead, plus ceil(11/4)=3 trailing
      (t/is (= 1203 (compaction/projected-context-tokens projected [assistant user]))))
    (t/testing "an edit after the usage source invalidates it — pure estimate"
      (t/is (= 4 (compaction/projected-context-tokens projected [assistant user edit]))
            "1 (assistant) + 3 (user), no usage"))
    (t/testing "an edit before the usage source leaves the measurement valid"
      (t/is (= 1203 (compaction/projected-context-tokens projected [edit assistant user]))))
    (t/testing "an omitted message is not counted"
      (t/is (= 1 (compaction/projected-context-tokens
                  [{:source assistant :messages [assistant]}]
                  [assistant user edit]))
            "the projection carries only the assistant"))
    (t/testing "a compaction after the usage source invalidates it"
      (let [compaction-entry {:id "c1" :role :compaction :summary "s"}
            branch [assistant compaction-entry user]
            proj [{:source assistant :messages [assistant]}
                  {:source compaction-entry :messages [compaction-entry]}
                  {:source user :messages [user]}]]
        (t/is (= 5 (compaction/projected-context-tokens proj branch))
              "1 + 1 + 3, estimated — the usage predates the compaction")))))


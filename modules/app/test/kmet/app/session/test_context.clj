(ns kmet.app.session.test-context
  "Message context: the compaction projection, append-only context
   edits (K), cache-miss detection, and the context settings derived
   from the branch."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.session.core :as s]
            [kmet.app.session.test-support :as support]))

(t/use-fixtures :once support/with-clean-test-dir)

;; ─── Append-only compaction: context build (pi: buildContextEntries) ─────

(t/deftest test-build-context-no-compaction
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "a"})
    (s/append-entry session {:role :assistant :content "b"})
    (t/is (= 2 (count (s/build-context session)))
          "no compaction on the path → full branch")))

(t/deftest test-build-context-empty
  (let [session (s/create-session support/test-dir)]
    (t/is (empty? (s/build-context session)))))

(t/deftest test-build-context-latest-compaction-wins
  (let [session (s/create-session support/test-dir)]
    (dotimes [i 6]
      (s/append-entry session {:role :user :content (str "m" i)}))
    (let [first-kept-1 (:id (nth (s/get-branch session) 2))]
      (s/compact-with-summary! session "FIRST" first-kept-1)
      (s/append-entry session {:role :user :content "m6"})
      (s/append-entry session {:role :user :content "m7"})
      (let [e2 (s/get-branch session)
            first-kept-2 (:id (nth e2 4))]
        (s/compact-with-summary! session "SECOND" first-kept-2)
        (let [context (s/build-context session)]
          (t/is (= "SECOND" (:summary (first context)))
                "latest compaction wins")
          (t/is (= first-kept-2 (:id (second context)))
                "its first-kept-id starts the tail"))))))

(t/deftest test-build-context-messages
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-entry session {:role :assistant :content "a"})
    (s/append-session-info! session "t")
    (s/append-entry session {:role :info :label "note" :content "x"})
    (s/compact-with-summary! session "SUM" (:id (first (s/get-branch session))))
    (let [msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= 3 (count msgs)))
      (t/is (= :compaction (:role (first msgs))))
      (t/is (= "SUM" (:summary (first msgs)))
            "compaction keeps its role + summary (pi: compactionSummary AgentMessage)")
      (t/is (= "q" (-> msgs second :content)))
      (t/is (= "a" (-> msgs last :content)))
      (t/is (not-any? #(contains? #{:info :session_info} (:role %)) msgs)
            ":info/:session_info are metadata, excluded from context"))))

(t/deftest test-compaction-retains-old-entries
  (let [session (s/create-session support/test-dir)]
    (dotimes [i 4]
      (s/append-entry session {:role :user :content (str "q" i)}))
    (let [branch (s/get-branch session)
          first-kept-id (:id (nth branch 2))]
      (s/compact-with-summary! session "SUM" first-kept-id)
      (s/append-entry session {:role :assistant :content "after"}))
    (let [loaded (s/load-session (:file session))]
      (t/is (= 6 (count @(:entries loaded))) "all entries persist on reload")
      (t/is (some #(= "q0" (:content %)) @(:entries loaded))
            "summarized history stays reachable (tree/fork)"))))

;; ─── Append-only context edits (K — pi: ContextEditEntry) ────────────────

(t/deftest test-append-context-edit-omits-message
  (let [session (s/create-session support/test-dir)
        q (s/append-entry session {:role :user :content "q"})
        _ (s/append-entry session {:role :assistant
                                   :content "a"
                                   :usage {:prompt_tokens 10 :completion_tokens 2}})
        usage-before (s/usage-totals session)
        edit (s/append-context-edit! session (:id q) nil)]
    (t/is (= :context-edit (:role edit)))
    (t/is (= (:id q) (:target-id edit)))
    (t/is (nil? (:replacement edit)))
    (t/is (= ["a"] (mapv :content (s/build-context-messages session)))
          "a nil replacement drops exactly the target from the projection")
    (t/is (= ["q" "a"]
             (mapv :content (filter #(contains? #{:user :assistant} (:role %))
                                    (s/get-branch session))))
          "the raw branch is unchanged")
    (t/is (= 3 (count @(:entries session)))
          "the branch is the two messages plus the edit itself")
    (t/is (= usage-before (s/usage-totals session))
          "usage totals are unchanged")
    (t/is (= 10 (get-in (s/get-session-stats session) [:tokens :input]))
          "usage is read from the raw entries, not the projection")
    (t/is (= 2 (:total-messages (s/get-session-stats session)))
          "message counts are unchanged by an edit")))

(t/deftest test-append-context-edit-replaces-content
  (let [session (s/create-session support/test-dir)
        _ (s/append-entry session {:role :user :content "original"})
        a (s/append-entry session {:role :assistant :content "answer"})
        _ (s/append-entry session {:role :user :content "next"})]
    (s/append-context-edit! session (:id a) {:content "replacement"})
    (let [msgs (s/build-context-messages session)]
      (t/is (= ["original" "next"] (mapv :content [(first msgs) (last msgs)]))
            "the untargeted messages are untouched")
      (t/is (= [{:type :text :text "replacement"}]
               (:content (second msgs)))
            "an assistant target takes a string as one text block (pi: appendContextEdit)"))
    (t/is (= "answer" (:content a))
          "the source entry is never mutated")))

(t/deftest test-append-context-edit-replaces-tool-result-text
  ;; kmet keeps the tool-call id inside the tool_result block (:tool_use_id)
  ;; where pi's ToolResultMessage carries toolCallId as a field — a content
  ;; replacement must keep the block, or the provider cannot pair call and
  ;; result (the converters read the id from that block)
  (let [session (s/create-session support/test-dir)
        _ (s/append-entry session {:role :user :content "q"})
        _ (s/append-entry session {:role :assistant :content ""
                                   :tool-calls [{:id "t1" :name "read" :arguments "{}"}]})
        r (s/append-entry session {:role :tool
                                   :tool-call-id "t1"
                                   :tool-name "read"
                                   :content [{:type :tool_result
                                              :tool_use_id "t1"
                                              :content "original output"}]})]
    (s/append-context-edit! session (:id r) {:content "replacement"})
    (let [msg (last (s/build-context-messages session))
          block (first (:content msg))]
      (t/is (= :tool_result (:type block)) "the block structure survives")
      (t/is (= "t1" (:tool_use_id block)) "the tool-call id survives")
      (t/is (= "replacement" (:content block)) "the result text is replaced")
      (t/is (= "original output" (-> r :content first :content))
            "the source entry is untouched"))
    (t/is (= :tool (-> (last (s/build-context-messages session)) :role)))
    (s/append-context-edit! session (:id r) nil)
    (t/is (= ["q" ""] (mapv :content (s/build-context-messages session)))
          "a nil replacement still drops the whole tool result")))

(t/deftest test-append-context-edit-block-replacement
  (let [session (s/create-session support/test-dir)
        u (s/append-entry session {:role :user :content "original"})]
    (s/append-context-edit! session (:id u)
                            {:content [{:type :text :text "a"}
                                       {:type :text :text "b"}]})
    (t/is (= [{:type :text :text "a"} {:type :text :text "b"}]
             (:content (first (s/build-context-messages session))))
          "a block replacement is installed verbatim for a user target")))

(t/deftest test-append-context-edit-last-wins
  (let [session (s/create-session support/test-dir)
        u (s/append-entry session {:role :user :content "original"})]
    (s/append-context-edit! session (:id u) {:content "first"})
    (s/append-context-edit! session (:id u) {:content "second"})
    (t/is (= [{:type :text :text "second"}]
             (:content (first (s/build-context-messages session))))
          "the latest edit of a target wins (pi: buildSessionProjection's Map.set)")
    (s/append-context-edit! session (:id u) nil)
    (t/is (empty? (s/build-context-messages session))
          "a later omit beats an earlier replacement")
    (s/append-context-edit! session (:id u) {:content "restored"})
    (t/is (= [{:type :text :text "restored"}]
             (:content (first (s/build-context-messages session))))
          "an edit after an omit restores the message (pi: session-context-edit.test)")))

(t/deftest test-append-context-edit-omits-tool-entries
  ;; A broken tool call/result pair can be omitted from future context
  ;; (pi's first use: dropping an error attempt's assistant + results)
  (let [session (s/create-session support/test-dir)
        _ (s/append-entry session {:role :user :content "q"})
        a (s/append-entry session {:role :assistant
                                   :content ""
                                   :stop-reason :error
                                   :tool-calls [{:id "t1" :name "read" :arguments "{}"}]})
        r (s/append-entry session {:role :tool
                                   :tool-call-id "t1"
                                   :tool-name "read"
                                   :content [{:type :tool_result :content "boom"}]})]
    (s/append-context-edit! session (:id a) nil)
    (s/append-context-edit! session (:id r) nil)
    (t/is (= ["q"] (mapv :content (s/build-context-messages session)))
          "both halves of the failed attempt are dropped")
    (t/is (= 3 (count (filter #(contains? #{:user :assistant :tool} (:role %))
                              (s/get-branch session))))
          "the raw branch keeps every message entry")))

(t/deftest test-append-context-edit-custom-message
  (let [session (s/create-session support/test-dir)
        _ (s/append-entry session {:role :user :content "q"})
        cm (s/append-custom-message-entry! session "note" "injected" true)]
    (s/append-context-edit! session (:id cm) {:content "edited"})
    (let [msgs (s/build-context-messages session)
          custom (last msgs)]
      (t/is (= :custom (:role custom)))
      (t/is (= [{:type :text :text "edited"}] (:content custom))
            "a custom message is editable and keeps its :custom role"))
    (s/append-context-edit! session (:id cm) nil)
    (t/is (= 1 (count (s/build-context-messages session)))
          "an omitted custom message contributes nothing")))

(t/deftest test-append-context-edit-bash-target
  ;; a shell result is an editable message too (pi stores ! results as
  ;; toolResult messages); the edit installs :content, which the wire renders
  ;; instead of the derived command text (bash-execution-text)
  (let [session (s/create-session support/test-dir)
        _ (s/append-entry session {:role :user :content "q"})
        b (s/record-bash-result! session "ls" {:output "a\nb" :exit-code 0} false)]
    (t/is (nil? (:content (last (s/build-context-messages session))))
          "un-edited, the projection keeps the command/output entry")
    (s/append-context-edit! session (:id b) {:content "replaced output"})
    (t/is (= [{:type :text :text "replaced output"}]
             (:content (last (s/build-context-messages session))))
          "the edit installs content on the projected bash message")
    (t/is (= "a\nb" (:output (last (s/build-context-messages session))))
          "the original fields stay on the entry (raw history)")
    (s/append-context-edit! session (:id b) nil)
    (t/is (= ["q"] (mapv :content (s/build-context-messages session)))
          "and a nil replacement omits it")))

(t/deftest test-append-context-edit-validation
  (let [session (s/create-session support/test-dir)
        u (s/append-entry session {:role :user :content "u"})
        _ (s/append-entry session {:role :assistant :content "a"})
        label (s/set-label! session (:id u) "l")]
    (t/is (= :invalid-context-edit
             (try (s/append-context-edit! session (:id u) {:nope 1})
                  :no-throw
                  (catch Exception e (:type (ex-data e)))))
          "a replacement without string/vector :content is rejected")
    (t/is (= :invalid-context-edit
             (try (s/append-context-edit! session (:id u) {:content 42})
                  :no-throw
                  (catch Exception e (:type (ex-data e))))))
    (t/is (= :entry-not-found
             (try (s/append-context-edit! session "missing" nil)
                  :no-throw
                  (catch Exception e (:type (ex-data e))))))
    (t/is (= :entry-not-editable
             (try (s/append-context-edit! session (:id label) nil)
                  :no-throw
                  (catch Exception e (:type (ex-data e)))))
          "labels carry no model content")
    (t/is (= :entry-not-editable
             (try (let [c (s/compact-with-summary! session "sum" (:id u))]
                    (s/append-context-edit! session (:id c) nil))
                  :no-throw
                  (catch Exception e (:type (ex-data e)))))
          "compaction entries carry no editable content")))

(t/deftest test-append-context-edit-off-branch
  (let [session (s/create-session support/test-dir)
        root (s/append-entry session {:role :user :content "root"})
        abandoned (s/append-entry session {:role :assistant :content "old"})]
    (s/branch! session (:id root))
    (s/append-entry session {:role :assistant :content "new"})
    (t/is (= :entry-not-on-branch
             (try (s/append-context-edit! session (:id abandoned) nil)
                  :no-throw
                  (catch Exception e (:type (ex-data e)))))
          "an abandoned branch's entry cannot be edited (pi: getBranch check)")
    (t/is (= :context-edit
             (:role (s/append-context-edit! session (:id root) nil)))
          "the active branch's entry can")))

(t/deftest test-context-edit-entry-is-state-not-message
  (let [session (s/create-session support/test-dir)
        u (s/append-entry session {:role :user :content "u"})]
    (s/append-context-edit! session (:id u) nil)
    (let [entries @(:entries session)]
      (t/is (empty? (s/context-messages (last entries)))
            "a :context-edit entry projects no message of its own"))
    (t/is (str/starts-with? (get-in (s/get-tree session) [0 :children 0 :summary])
                            "[context omit: ")
          "…but the entry shows its effect in the tree (pi: [context omit: id])")))

(t/deftest test-context-edit-persists-and-reloads
  (let [dir (str support/test-dir "/context-edit-reload-" (System/currentTimeMillis))
        session (s/create-session dir)
        u (s/append-entry session {:role :user :content "u"})
        _ (s/append-entry session {:role :assistant :content "a"})]
    (s/append-context-edit! session (:id u) nil)
    (let [loaded (s/load-session (:file session))]
      (t/is (= ["a"] (mapv :content (s/build-context-messages loaded)))
            "the edit round-trips through the session file")
      (t/is (= (set (map :id (s/get-branch session)))
               (set (map :id (s/get-branch loaded))))))))

(t/deftest test-project-context-stale-compaction-contributes-nothing
  ;; A compaction whose cut point lands on an earlier compaction entry keeps
  ;; it inside the retained range; only the newest compaction contributes a
  ;; summary message (pi: buildSessionProjection's index > 0 rule).
  (let [session (s/create-session support/test-dir)]
    (dotimes [i 4]
      (s/append-entry session {:role :user :content (str "q" i)}))
    (let [first-compaction (s/compact-with-summary!
                            session "FIRST" (:id (nth (s/get-branch session) 0)))]
      (s/append-entry session {:role :user :content "q4"})
      (s/compact-with-summary! session "SECOND" (:id first-compaction)))
    (let [projected (s/project-context session)]
      (t/is (= [1 0] [(count (:messages (first projected)))
                      (count (:messages (second projected)))])
            "the newest compaction projects its summary, the older none")
      (t/is (= "SECOND" (:summary (first (s/build-context-messages session))))
            "the newest compaction's summary reaches the provider")
      (t/is (= ["q4"] (mapv :content (rest (s/build-context-messages session))))
            "the kept tail follows it"))))

(t/deftest test-derive-context-settings
  ;; G6: model/thinking derived from the branch path — latest wins, defaults
  ;; when absent (pi: getSessionContextSettings)
  (let [session (s/create-session support/test-dir)]
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "empty session: defaults")
    (s/append-entry session {:role :user :content "q"})
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "no change entries: defaults")
    (s/append-model-change! session :anthropic "claude-sonnet")
    (s/append-thinking-level-change! session :medium)
    (t/is (= {:thinking-level :medium :model "claude-sonnet" :provider :anthropic}
             (s/derive-context-settings session)))
    (s/append-model-change! session :opencode-go "gpt-4o")
    (s/append-thinking-level-change! session :off)
    (t/is (= {:thinking-level :off :model "gpt-4o" :provider :opencode-go}
             (s/derive-context-settings session))
          "latest change entries win")))

(t/deftest test-derive-context-settings-branch-aware
  ;; derivation follows the ACTIVE branch — settings from abandoned branches
  ;; don't leak in (pi: getSessionContextSettings walks root→leaf)
  (let [session (s/create-session support/test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        _ (s/append-model-change! session :anthropic "claude-sonnet")
        _ (s/append-thinking-level-change! session :high)]
    (s/branch! session (:id q1))
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "branching away abandons the change entries"))
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/branch! session (:id (first (s/get-branch session))))
    (s/append-model-change! session :opencode-go "gpt-4o")
    (t/is (= {:thinking-level :off :model "gpt-4o" :provider :opencode-go}
             (s/derive-context-settings session))
          "new branch carries its own change entries")))

;; ─── Cache-miss detection (pi: cache-stats.ts detectMiss) ─────────────────

(t/deftest test-detect-cache-miss
  (let [with-usage (fn [role usage & [model]] {:role role :usage usage :model model})
        entries [{:role :model-change :model "m1"}
                 (with-usage :assistant {:input_tokens 10000 :output_tokens 100
                                         :input_tokens_details {:cached_tokens 9000}})
                 (with-usage :assistant {:input_tokens 10000 :output_tokens 100
                                         :input_tokens_details {:cached_tokens 0}})]]
    (t/testing "a drop from cached to uncached counts the miss"
      (let [miss (s/detect-cache-miss entries)]
        (t/is (some? miss))
        (t/is (= 10000 (:missed-tokens miss))))))
  (t/testing "no cache activity on the previous turn → nil"
    (let [entries [{:role :assistant :usage {:input_tokens 1000 :output_tokens 10}}
                   {:role :assistant :usage {:input_tokens 1000 :output_tokens 10}}]]
      (t/is (nil? (s/detect-cache-miss entries)))))
  (t/testing "fewer than two assistant messages → nil"
    (t/is (nil? (s/detect-cache-miss [{:role :assistant :usage {:input_tokens 1}}]))))
  (t/testing "model change between turns is reported"
    (let [entries [{:role :model-change :model "m1"}
                   {:role :assistant :usage {:input_tokens 10000 :output_tokens 100
                                             :input_tokens_details {:cached_tokens 9000}}}
                   {:role :model-change :model "m2"}
                   {:role :assistant :usage {:input_tokens 10000 :output_tokens 100
                                             :input_tokens_details {:cached_tokens 0}}}]]
      (t/is (true? (:model-changed (s/detect-cache-miss entries)))))))

;; ─── Turn markers (T1–T5) ───────────────────────────────────────────────────

(t/deftest test-turn-start-projects-to-no-message
  (t/testing "the marker is a log fact: it stays in the entries the tree reads
              and projects to nothing at the wire"
    (let [session (s/create-session support/test-dir)]
      (s/append-entry session {:role :turn-start :turn-id "t1" :reason :prompt})
      (s/append-entry session {:role :user :content "q"})
      (s/append-entry session {:role :assistant :content "a"})
      (let [ctx (s/build-context session)
            msgs (mapcat s/context-messages ctx)]
        (t/is (= 1 (count (filter #(= :turn-start (:role %)) ctx)))
              "build-context keeps the marker for the tree")
        (t/is (= 2 (count msgs)))
        (t/is (not-any? #(= :turn-start (:role %)) msgs))
        (t/is (empty? (s/context-messages {:role :turn-start :turn-id "t"})))))))

(t/deftest test-compaction-keeps-a-cut-boundary-marker
  (t/testing "a compaction whose kept boundary is a marker keeps it in the
              projection, so a later turn-granular caller still sees the
              boundary while the wire sends no message for it"
    (let [session (s/create-session support/test-dir)
          _ (s/append-entry session {:role :user :content "q0"})
          marker (s/append-entry session {:role :turn-start :turn-id "t1" :reason :prompt})
          _ (s/append-entry session {:role :user :content "q1"})
          _ (s/append-entry session {:role :assistant :content "a1"})]
      (s/compact-with-summary! session "SUM" (:id marker))
      (let [ctx (s/build-context session)]
        (t/is (some #(= (:id marker) (:id %)) ctx)
              "the kept boundary entry survives the cut")
        (t/is (not-any? #(= :turn-start (:role %))
                        (mapcat s/context-messages ctx)))))))

(t/deftest test-unmarked-prefix-is-one-turn
  (t/testing "a file written before the role existed loads unchanged, and its
              prefix simply has no boundary (no migration, no heuristic)"
    (let [session (s/create-session support/test-dir)
          _ (s/append-entry session {:role :user :content "old"})
          _ (s/append-entry session {:role :assistant :content "reply"})
          loaded (s/load-session (:file session))]
      (t/is (= 2 (count @(:entries loaded))))
      (t/is (not-any? #(= :turn-start (:role %)) @(:entries loaded)))
      (t/is (= 2 (count (mapcat s/context-messages (s/build-context loaded))))))))

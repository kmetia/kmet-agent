(ns kmet.ai.test-tool-changes
  "The wires that can anchor a declared-set change in place: Anthropic's
   inline tool blocks, the Responses family's additional_tools/tool-search
   items, and Kimi's tool-bearing system message. The app projects each
   recorded change to a :role :tool-change marker at its transcript position,
   so every test passes plain message vectors carrying those markers."
  (:require [clojure.test :as t]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.api.openai-codex-responses :as codex]
            [kmet.ai.api.openai-completions :as completions]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]))

(def ^:private base-tool
  {:name "base_tool" :description "base tool"
   :parameters {:type "object" :properties {}}})

(def ^:private late-tool
  {:name "late_tool" :description "late tool"
   :parameters {:type "object" :properties {}}})

(defn- change
  [added removed]
  {:role :tool-change :added added :removed removed})

(def ^:private messages
  [{:role :system :content [{:type :text :text "SYS"}]}
   {:role :user :content [{:type :text :text "hi"}]}])

(def ^:private history-messages
  [{:role :system :content [{:type :text :text "SYS"}]}
   (change [base-tool] [])
   {:role :user :content [{:type :text :text "hi"}]}
   (change [late-tool] [])])

(defn- anthropic-model [compat]
  (models/map->Model
   {:id "claude-sonnet-5-5" :name "Claude Sonnet 5.5" :provider :anthropic
    :api :anthropic-messages :base-url "https://api.anthropic.com"
    :reasoning true :input [:text]
    :cost {:input 3 :output 15 :cache-read 0.3 :cache-write 3.75}
    :context-window 200000 :max-tokens 8192
    :compat compat}))

(defn- native-anthropic []
  (anthropic-model {:supports-mid-convo-system-messages true
                    :supports-mid-convo-tool-changes true}))

(defn- responses-model [compat]
  (models/map->Model
   {:id "gpt-5.4" :name "GPT-5.4" :provider :openai
    :api :openai-responses :base-url "https://api.openai.com/v1"
    :reasoning true :input [:text]
    :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
    :context-window 272000 :max-tokens 128000
    :compat compat
    :thinking-level-map {:off "none" :low "low" :medium "medium"
                         :high "high" :xhigh "xhigh"}}))

(defn- completions-model [compat]
  (models/map->Model
   {:id "kimi-k3" :name "Kimi K3" :provider :moonshotai
    :api :openai-completions :base-url "https://api.moonshot.ai/v1"
    :reasoning false :input [:text] :max-tokens 8192 :compat compat}))

(defn- codex-model [compat]
  (models/map->Model
   {:id "gpt-5.4" :name "GPT-5.4" :provider :openai-codex
    :api :openai-codex-responses :base-url "https://chatgpt.com/backend-api"
    :reasoning true :input [:text]
    :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
    :context-window 272000 :max-tokens 128000
    :compat compat
    :thinking-level-map {:off "none" :low "low" :medium "medium"
                         :high "high" :xhigh "xhigh"}}))

;; ─── Anthropic ────────────────────────────────────────────────────────────

(t/deftest test-anthropic-declares-initial-tools-once-and-anchors-the-delta
  (let [payload (anthropic/anthropic-payload (native-anthropic) nil history-messages
                                             [base-tool late-tool] "claude-sonnet-5-5"
                                             :short)
        tools (:tools payload)]
    (t/is (= ["base_tool" "__kmet_deferred_placeholder__"] (mapv :name tools))
          "the top-level array holds the initial declaration and the placeholder,
           never the later tool")
    (t/is (= {:type "ephemeral"} (:cache_control (first tools)))
          "the cache breakpoint stays on the last initial tool")
    (t/is (nil? (:cache_control (second tools))))
    (t/is (true? (:defer_loading (second tools))))
    (let [update (last (:messages payload))]
      (t/is (= "system" (:role update)))
      (t/is (= ["tool_addition"] (mapv :type (:content update))))
      (t/is (= "late_tool"
               (get-in update [:content 0 :tool :definition :name])))
      (t/is (nil? (get-in update [:content 0 :tool :definition :defer_loading])))
      (t/is (= {:type "ephemeral"} (:cache_control (first (:content update))))
            "the trailing change takes the conversation breakpoint"))
    (t/is (true? (anthropic/anthropic-native-tool-changes? (native-anthropic)
                                                           history-messages
                                                           [base-tool late-tool]))
          "the request headers add the inline-tools beta")
    (t/is (= "inline-tools-2026-09-15"
             (get (anthropic/anthropic-request-headers
                   (native-anthropic) {:id :anthropic} "key" "sess" messages
                   :short true)
                  "anthropic-beta"))
          "native changes advertise the inline-tools beta")
    (t/is (nil? (get (anthropic/anthropic-request-headers
                      (native-anthropic) {:id :anthropic} "key" "sess" messages
                      :short false)
                     "anthropic-beta"))
          "no native change: no beta")))

(t/deftest test-anthropic-anchors-a-removal-and-a-redefinition
  (let [removal [{:role :system :content [{:type :text :text "SYS"}]}
                 (change [base-tool] [])
                 {:role :user :content [{:type :text :text "hi"}]}
                 (change [] ["base_tool"])]
        payload (anthropic/anthropic-payload
                 (native-anthropic) nil removal [base-tool] "claude-sonnet-5-5" :short)
        blocks (-> payload :messages last :content)]
    (t/is (= ["tool_removal"] (mapv :type blocks)))
    (t/is (= "base_tool" (get-in blocks [0 :tool :name]))))
  (let [redefined (assoc base-tool :description "changed")
        messages [{:role :system :content [{:type :text :text "SYS"}]}
                  (change [base-tool] [])
                  {:role :user :content [{:type :text :text "hi"}]}
                  (change [redefined] ["base_tool"])]
        payload (anthropic/anthropic-payload
                 (native-anthropic) nil messages [base-tool] "claude-sonnet-5-5" :short)
        blocks (-> payload :messages last :content)]
    (t/is (= ["tool_addition"] (mapv :type blocks))
          "a same-name redefinition is an addition, not a removal")
    (t/is (= "changed" (get-in blocks [0 :tool :definition :description])))))

(t/deftest test-anthropic-falls-back-to-the-current-list-without-an-initial-tool
  (let [messages [{:role :system :content [{:type :text :text "SYS"}]}
                  (change [] [])
                  {:role :user :content [{:type :text :text "hi"}]}
                  (change [late-tool] [])]
        payload (anthropic/anthropic-payload (native-anthropic) nil messages
                                             [late-tool] "claude-sonnet-5-5" :short)]
    (t/is (= [{:name "late_tool" :description "late tool"
               :input_schema {:type "object" :properties {}}
               :cache_control {:type "ephemeral"}}]
             (:tools payload))
          "no initial tool: the current list, no placeholder")
    (t/is (= ["user"] (mapv :role (:messages payload)))
          "and no inline change block")
    (t/is (false? (anthropic/anthropic-native-tool-changes? (native-anthropic)
                                                            messages [late-tool])))))

(t/deftest test-anthropic-without-the-compat-keys-sends-the-current-array
  (let [payload (anthropic/anthropic-payload (anthropic-model {}) nil history-messages
                                             [base-tool late-tool] "claude-sonnet-5-5"
                                             :short)]
    (t/is (= ["base_tool" "late_tool"] (mapv :name (:tools payload))))
    (t/is (= ["user"] (mapv :role (:messages payload))))
    (t/is (false? (anthropic/anthropic-native-tool-changes? (anthropic-model {})
                                                            history-messages
                                                            [base-tool late-tool])))))

;; ─── OpenAI Responses ─────────────────────────────────────────────────────

(defn- responses-payload [model messages]
  (responses/responses-payload model nil messages [base-tool late-tool]
                               "gpt-5.4" nil nil))

(t/deftest test-the-folded-tool-state-is-name-sorted
  (let [{:keys [request-tools]}
        (shared/resolve-request-tools [(change [late-tool base-tool] [])] :none [])]
    (t/is (= ["base_tool" "late_tool"] (mapv :name request-tools))
          "the top-level array must not depend on the history's insertion order")))

(t/deftest test-responses-changes-declares-every-tool-in-additional-tools
  (let [payload (responses-payload
                 (responses-model {:supports-mid-convo-system-messages true
                                   :supports-additional-tools true})
                 history-messages)
        input (:input payload)
        additional (filter #(= "additional_tools" (:type %)) input)]
    (t/is (nil? (:tools payload))
          "a later additional_tools item cannot replace a top-level tool")
    (t/is (= [["base_tool"] ["late_tool"]]
             (mapv #(mapv :name (:tools %)) additional)))
    (t/is (= "additional_tools" (:type (first input)))
          "the initial declaration leads the input")
    (t/is (some #(and (= "developer" (:role %))
                      (re-find #"incremental tools update" (str (:content %))))
                input))
    (t/is (some #(and (= "developer" (:role %))
                      (re-find #"latest definition replaces the earlier one"
                               (str (:content %))))
                input))))

(t/deftest test-responses-changes-announces-a-removal-as-text
  (let [messages [{:role :system :content [{:type :text :text "SYS"}]}
                  (change [base-tool] [])
                  {:role :user :content [{:type :text :text "hi"}]}
                  (change [] ["base_tool"])]
        payload (responses-payload
                 (responses-model {:supports-mid-convo-system-messages true
                                   :supports-additional-tools true})
                 messages)
        input (:input payload)]
    (t/is (not-any? #(and (= "additional_tools" (:type %))
                          (empty? (:tools %)))
                    input))
    (t/is (some #(and (= "developer" (:role %))
                      (re-find #"no longer available" (str (:content %))))
                input))))

(t/deftest test-responses-additions-maps-a-later-tool-to-tool-search
  (let [payload (responses-payload
                 (responses-model {:supports-tool-search true})
                 history-messages)
        input (:input payload)
        calls (filter #(= "tool_search_call" (:type %)) input)
        outputs (filter #(= "tool_search_output" (:type %)) input)]
    (t/is (= ["base_tool"] (mapv :name (:tools payload)))
          "the initial declaration is the top-level array")
    (t/is (= 1 (count calls)))
    (t/is (= 1 (count outputs)))
    (t/is (= (:call_id (first calls)) (:call_id (first outputs))))
    (t/is (every? #(true? (:defer_loading %))
                  (mapcat :tools outputs))
          "the loaded definitions are marked deferred")))

(t/deftest test-additions-folds-a-removal-into-the-top-level-list
  (let [messages [{:role :system :content [{:type :text :text "SYS"}]}
                  (change [base-tool late-tool] [])
                  {:role :user :content [{:type :text :text "hi"}]}
                  (change [] ["base_tool"])]
        payload (responses-payload
                 (responses-model {:supports-tool-search true})
                 messages)]
    (t/is (= ["late_tool"] (mapv :name (:tools payload)))
          "a removal cannot be anchored on an additions-only wire, so it moves
           into the top-level state")
    (t/is (not-any? #(= "tool_search_call" (:type %)) (:input payload))
          "nothing follows the folded removal to anchor")))

(t/deftest test-responses-none-sends-the-current-list
  (let [payload (responses-payload (responses-model {}) history-messages)]
    (t/is (= ["base_tool" "late_tool"] (mapv :name (:tools payload))))
    (t/is (not-any? #(contains? #{"additional_tools" "tool_search_call"}
                                (:type %))
                    (:input payload)))))

(t/deftest test-responses-drops-a-later-system-message
  ;; the leading system message is the prompt; a later one is not a
  ;; conversation role (kmet has no mid-conversation system prompt update),
  ;; and must not be converted into a user turn
  (let [payload (responses-payload
                 (responses-model {})
                 [{:role :system :content [{:type :text :text "SYS"}]}
                  {:role :user :content [{:type :text :text "hi"}]}
                  {:role :system :content [{:type :text :text "EXTRA"}]}
                  {:role :assistant :content [{:type :text :text "a"}]}])]
    (t/is (not-any? #(= "EXTRA" (str (:content %))) (:input payload)))
    (t/is (= ["developer" "user"] (mapv :role (take 2 (:input payload)))))))

;; ─── OpenAI Codex Responses ──────────────────────────────────────────────

(defn- codex-payload* [model messages]
  (codex/codex-payload model nil messages [base-tool late-tool] "gpt-5.4" nil))

(t/deftest test-codex-changes-uses-the-shared-head-and-anchored-deltas
  (let [payload (codex-payload* (codex-model {:supports-mid-convo-system-messages true
                                              :supports-additional-tools true})
                                history-messages)
        input (:input payload)
        additional (filter #(= "additional_tools" (:type %)) input)]
    (t/is (= "SYS" (:instructions payload))
          "the leading prompt goes to instructions")
    (t/is (not-any? #(= "SYS" (:content %)) input)
          "and not to a developer message")
    (t/is (nil? (:tools payload))
          "every tool is declared in an additional_tools item")
    (t/is (= "additional_tools" (:type (first input)))
          "the head item leads the input")
    (t/is (= [["base_tool"] ["late_tool"]]
             (mapv #(mapv :name (:tools %)) additional)))))

(t/deftest test-codex-additions-maps-a-later-tool-to-tool-search
  (let [payload (codex-payload* (codex-model {:supports-tool-search true})
                                history-messages)
        input (:input payload)]
    (t/is (= ["base_tool"] (mapv :name (:tools payload)))
          "the initial declaration is the top-level array")
    (t/is (= ["SYS"] [(:instructions payload)]))
    (t/is (some #(= "tool_search_call" (:type %)) input))
    (t/is (some #(= "tool_search_output" (:type %)) input))))

(t/deftest test-codex-none-sends-the-current-list
  (let [payload (codex-payload* (codex-model {}) history-messages)]
    (t/is (= ["base_tool" "late_tool"] (mapv :name (:tools payload))))
    (t/is (not-any? #(contains? #{"additional_tools" "tool_search_call"}
                                (:type %))
                    (:input payload)))))

;; ─── OpenAI Completions (Kimi) ───────────────────────────────────────────

(t/deftest test-kimi-anchors-additions-as-a-tool-bearing-system-message
  (let [payload (completions/openai-payload
                 (completions-model {:deferred-tools-mode :kimi})
                 nil history-messages [base-tool late-tool] "kimi-k3" nil nil)
        change (last (:messages payload))]
    (t/is (= ["base_tool"] (mapv (comp :name :function) (:tools payload)))
          "the top-level array is the initial declaration")
    (t/is (= "system" (:role change)))
    (t/is (= ["late_tool"] (mapv (comp :name :function) (:tools change))))
    (t/is (nil? (:content change))
          "the tool-bearing system message carries no text")))

(t/deftest test-completions-without-the-mode-sends-the-current-list
  (let [payload (completions/openai-payload
                 (completions-model {}) nil history-messages
                 [base-tool late-tool] "m1" nil nil)]
    (t/is (= ["base_tool" "late_tool"]
             (mapv (comp :name :function) (:tools payload))))
    (t/is (= ["system" "user"] (mapv :role (:messages payload))))))

;; ─── The growing request prefix ──────────────────────────────────────────

(defn- growing-prefix
  "Each payload's items starts with the previous payload's items."
  [items]
  (every? (fn [[before after]]
            (= before (subvec (vec after) 0 (count before))))
          (partition 2 1 items)))

(def ^:private conversation
  [{:role :system :content [{:type :text :text "SYS"}]}
   (change [base-tool] [])
   {:role :user :content [{:type :text :text "first"}]}])

(def ^:private next-turn
  [{:role :assistant :content [{:type :text :text "answer"}]}
   {:role :user :content [{:type :text :text "second"}]}])

(def ^:private later-change
  [(change [late-tool] [])
   {:role :user :content [{:type :text :text "third"}]}])

(t/deftest test-anthropic-keeps-a-growing-message-prefix
  (let [model (native-anthropic)
        payloads (mapv #(anthropic/anthropic-payload model nil %
                                                     [base-tool late-tool]
                                                     "claude-sonnet-5-5" :none)
                       [(vec conversation)
                        (into conversation next-turn)
                        (into conversation (concat next-turn later-change))])]
    (t/is (growing-prefix (mapv :messages payloads)))
    (t/is (= ["base_tool" "__kmet_deferred_placeholder__"]
             (mapv :name (:tools (last payloads))))
          "the top-level array stays fixed across the changes")))

(t/deftest test-responses-keeps-a-growing-input-prefix
  (let [model (responses-model {:supports-mid-convo-system-messages true
                                :supports-additional-tools true})
        payloads (mapv #(responses-payload model %)
                       [(vec conversation)
                        (into conversation next-turn)
                        (into conversation (concat next-turn later-change))])]
    (t/is (growing-prefix (mapv :input payloads)))
    (t/is (nil? (:tools (last payloads))))))

(t/deftest test-kimi-keeps-a-growing-message-prefix
  (let [model (completions-model {:deferred-tools-mode :kimi})
        payloads (mapv #(completions/openai-payload model nil %
                                                    [base-tool late-tool]
                                                    "kimi-k3" nil nil)
                       [(vec conversation)
                        (into conversation next-turn)
                        (into conversation (concat next-turn later-change))])]
    (t/is (growing-prefix (mapv :messages payloads)))
    (t/is (= ["base_tool"] (mapv (comp :name :function)
                                 (:tools (last payloads)))))))

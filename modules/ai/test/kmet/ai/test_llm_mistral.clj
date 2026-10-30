(ns kmet.ai.test-llm-mistral
  "Mistral conversations: message conversion and the streaming end-to-end."
  (:require [clojure.test :as t]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.mistral-conversations :as mistral]
            [kmet.app.tools.tool :as tools]))

;; ─── Mistral conversations ────────────────────────────────────────────────

(defn- mistral-model
  "Mistral model map for the payload/messages tests."
  [& {:keys [id reasoning tlm input max-tokens]
      :or {id "mistral-large" reasoning true input [:text] max-tokens 32000}}]
  {:id id :name "Mistral" :provider :mistral :api :mistral-conversations
   :base-url "https://api.mistral.ai" :reasoning reasoning
   :thinking-level-map tlm :input input :max-tokens max-tokens})

(t/deftest test-mistral-url
  (t/is (= "https://api.mistral.ai/v1/chat/completions"
           (@#'shared/endpoint-url :mistral-conversations "https://api.mistral.ai" "x"))))

(t/deftest test-mistral-tool-call-id-normalizer
  ;; 9-char ids pass through; longer/messy ids get a 9-char shortHash
  (let [normalize (@#'mistral/make-mistral-tool-call-id-normalizer)]
    (t/is (= "abc123xyz" (normalize "abc123xyz")))
    (t/is (= 9 (count (normalize "call_very-long-tool-call-id-12345"))))
    (t/is (re-matches #"[a-zA-Z0-9]{9}" (normalize "call_very-long-tool-call-id-12345")))
    (t/is (= (normalize "call-1") (normalize "call-1")) "deterministic per request")
    (t/is (not= (normalize "call-1") (normalize "call-2")) "distinct ids stay distinct")))

(t/deftest test-mistral-messages
  (let [normalize (fn [id] (if (= id "tool-1") "abc123xyz" id))
        msgs [{:role :system :content [{:type :text :text "sys"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :thinking "reasoning here"
               :tool-calls [{:id "tool-1" :name "read" :arguments {:path "/x"}}]}
              {:role :tool :content [{:content "done" :tool_use_id "tool-1"}]
               :tool-name "read"}]]
    (t/is (= [{:role "system" :content "sys"}
              {:role "user" :content [{:type "text" :text "hi"}]}
              {:role "assistant"
               :content [{:type "text" :text "answer"}
                         {:type "thinking" :thinking [{:type "text" :text "reasoning here"}]}]
               :tool_calls [{:id "abc123xyz" :type "function"
                             :function {:name "read" :arguments "{\"path\":\"/x\"}"} :index 0}]}
              {:role "tool" :tool_call_id "abc123xyz" :name "read"
               :content [{:type "text" :text "done"}]}]
             (@#'mistral/mistral-messages msgs (mistral-model) normalize)))))

(t/deftest test-mistral-messages-images
  (let [msgs [{:role :user :content [{:type :text :text "see"}
                                     {:type :image :mime-type "image/png" :data "AAAA"}]}
              {:role :tool :content [{:content "" :tool_use_id "t1"}]
               :tool-name "read" :images [{:mime-type "image/png" :data "BBBB"}]}]
        normalize identity]
    (t/testing "image-capable model keeps image_url parts"
      (t/is (= [{:role "user"
                 :content [{:type "text" :text "see"}
                           {:type "image_url" :image_url "data:image/png;base64,AAAA"}]}
                {:role "tool" :tool_call_id "t1" :name "read"
                 :content [{:type "text" :text "(see attached image)"}
                           {:type "image_url" :image_url "data:image/png;base64,BBBB"}]}]
               (@#'mistral/mistral-messages msgs (mistral-model :input [:text :image]) normalize))))
    (t/testing "text-only model drops the image parts (keeps text)"
      (t/is (= [{:role "user" :content [{:type "text" :text "see"}]}
                {:role "tool" :tool_call_id "t1" :name "read"
                 :content [{:type "text" :text "(image omitted: model does not support images)"}]}]
               (@#'mistral/mistral-messages msgs (mistral-model :input [:text]) normalize))))
    (t/testing "image-only user content degrades to pi's placeholder"
      (t/is (= [{:role "user"
                 :content [{:type "text" :text "(image omitted: model does not support images)"}]}]
               (@#'mistral/mistral-messages
                [{:role "user" :content [{:type :image :mime-type "image/png" :data "AAAA"}]}]
                (mistral-model :input [:text]) normalize))))))

(t/deftest test-mistral-thinking
  (t/is (= {:prompt_mode "reasoning"}
           (@#'mistral/mistral-thinking (mistral-model) :high))
        "default models use prompt_mode")
  (t/is (= {:reasoning_effort "high"}
           (@#'mistral/mistral-thinking (mistral-model :id "mistral-medium-3.5") :high))
        "effort models use reasoning_effort (default high)")
  (t/is (= {:reasoning_effort "medium"}
           (@#'mistral/mistral-thinking (mistral-model :id "mistral-medium-3.5"
                                                       :tlm {:medium "medium"}) :medium))
        "tlm value wins over the default")
  (t/is (= {} (@#'mistral/mistral-thinking (mistral-model) nil)) "off → no thinking params")
  (t/is (= {} (@#'mistral/mistral-thinking (mistral-model :reasoning false) :high))
        "non-reasoning model → no thinking params"))

(t/deftest test-mistral-payload
  (let [tools [(tools/make-tool :name "read" :label "Read" :description "Read a file"
                                :params {:path {:type :string :description "path"}})]]
    (t/is (= {:model "mistral-large" :stream true
              :messages [{:role "user" :content [{:type "text" :text "hi"}]}]
              :tools [{:type "function"
                       :function {:name "read" :description "Read a file"
                                  :parameters (:parameters (first tools)) :strict false}}]
              :prompt_mode "reasoning"
              :max_tokens 32000
              :prompt_cache_key "sess-1"}
             (@#'mistral/mistral-payload (mistral-model) :high
                                         [{:role "user" :content [{:type "text" :text "hi"}]}]
                                         tools "mistral-large" "sess-1" :short)))
    (t/testing "cache-retention :none drops the cache key"
      (t/is (nil? (:prompt_cache_key (@#'mistral/mistral-payload (mistral-model) nil
                                                                 [{:role "user" :content []}]
                                                                 nil "m" "s" :none)))))))


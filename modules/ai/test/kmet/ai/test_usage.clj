(ns kmet.ai.test-usage
  "Usage normalization (pi: normalizeUsage): every provider's usage shape
   maps onto the same {:input :output :cache-read :cache-write :cache-write-1h
   :cost} record."
  (:require [clojure.test :as t]
            [kmet.ai.usage :as usage]))

(t/deftest test-entry-usage
  (t/testing "OpenAI usage shape — input excludes cached tokens (pi normalizeUsage)"
    (t/is (= {:input 70 :output 20 :cache-read 30 :cache-write 0 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                 :prompt_tokens_details {:cached_tokens 30}}))))
  (t/testing "OpenAI cache-write tokens are split out and subtracted (OpenRouter-compatible)"
    (t/is (= {:input 65 :output 20 :cache-read 30 :cache-write 5 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                 :prompt_tokens_details {:cached_tokens 30
                                                         :cache_write_tokens 5}}))))
  (t/testing "Anthropic usage shape — input_tokens already excludes cache"
    (t/is (= {:input 100 :output 20 :cache-read 30 :cache-write 10 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20
                                 :cache_read_input_tokens 30
                                 :cache_creation_input_tokens 10}))))
  (t/testing "Anthropic 1h cache-write share (pi cacheWrite1h) is kept beside
             the total, which still includes it"
    (t/is (= {:input 100 :output 20 :cache-read 0 :cache-write 1000 :cache-write-1h 400 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20
                                 :cache_creation_input_tokens 1000
                                 :cache_creation {:ephemeral_5m_input_tokens 600
                                                  :ephemeral_1h_input_tokens 400}}))))
  (t/testing "a 1h breakdown without the total is not invented into :cache-write"
    (t/is (= 0 (:cache-write (usage/entry-usage {:input_tokens 10 :output_tokens 1
                                                 :cache_creation {:ephemeral_1h_input_tokens 400}})))))
  (t/testing "OpenAI Responses shape — input_tokens includes cache tokens,
             subtracted from the details sub-map (pi normalizeUsage)"
    (t/is (= {:input 65 :output 20 :cache-read 30 :cache-write 5 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20 :total_tokens 120
                                 :input_tokens_details {:cached_tokens 30
                                                        :cache_write_tokens 5}
                                 :output_tokens_details {:reasoning_tokens 10}}))))
  (t/testing "Responses usage without a cache details sub-map passes through"
    (t/is (= {:input 100 :output 20 :cache-read 0 :cache-write 0 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20 :total_tokens 120}))))
  (t/testing "Google's already-normalized shape (sse/google-usage)"
    (t/is (= {:input 10 :output 20 :cache-read 5 :cache-write 0 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:input 10 :output 20 :cache-read 5 :cache-write 0}))))
  (t/testing "fully-cached prompt → zero input, cache tokens still reported"
    (t/is (= {:input 0 :output 1 :cache-read 100 :cache-write 0 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 1
                                 :prompt_tokens_details {:cached_tokens 100}}))))
  (t/testing "cost is carried through from the breakdown attached by llm"
    (t/is (= 0.0042 (:cost (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                               :cost {:input 0.0014 :output 0.0028
                                                      :cache-read 0.0 :cache-write 0.0
                                                      :total 0.0042}})))))
  (t/testing "Bedrock ConverseStream shape — cache_read/write input tokens"
    (t/is (= {:input 7 :output 5 :cache-read 2 :cache-write 1 :cache-write-1h 0 :cost 0.0}
             (usage/entry-usage {:input_tokens 10 :output_tokens 5 :total_tokens 15
                                 :cache_read_input_tokens 2
                                 :cache_write_input_tokens 1}))))
  (t/testing "unknown shape returns nil"
    (t/is (nil? (usage/entry-usage {:foo 1})))
    (t/is (nil? (usage/entry-usage nil)))))

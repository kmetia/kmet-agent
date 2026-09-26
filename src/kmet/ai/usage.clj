(ns kmet.ai.usage
  "Usage normalization (pi: normalizeUsage — packages/ai). Normalizes a
   message :usage map (OpenAI chat, OpenAI responses, Anthropic, Google or
   Bedrock shapes) into {:input :output :cache-read :cache-write :cost},
   subtracting cached tokens from :input so they are not priced twice.
   Shared by the wire layer (api.shared), the session store, and the
   footer cost display.")

(defn entry-usage
  "Normalize a message :usage map (OpenAI chat, OpenAI responses, Anthropic,
   or Google shapes) into {:input :output :cache-read :cache-write :cost}.
   :input EXCLUDES cache tokens (pi normalizeUsage — otherwise cached tokens
   would be priced at both the input and cache-read rates): OpenAI's
   prompt_tokens (chat) and input_tokens (responses) include cached/
   cache-write tokens, so they're subtracted; Anthropic's input_tokens and
   Google's normalized :input already exclude them. :cost is the per-message
   USD total attached by llm (models/calculate-cost), 0 when the message
   predates cost tracking. Returns nil when the map has no recognizable
   token fields."
  [usage]
  (when (and usage (map? usage))
    (let [cache-read (or (get-in usage [:prompt_tokens_details :cached_tokens])
                         (get-in usage [:input_tokens_details :cached_tokens])
                         (:prompt_cache_hit_tokens usage)
                         (:cache_read_input_tokens usage)
                         (:cache-read usage))
          cache-write (or (get-in usage [:prompt_tokens_details :cache_write_tokens])
                          (get-in usage [:input_tokens_details :cache_write_tokens])
                          (:cache_creation_input_tokens usage)
                          (:cache_write_input_tokens usage)
                          (:cache-write usage))
          prompt (:prompt_tokens usage)
          ;; OpenAI responses' and Bedrock ConverseStream input_tokens include
          ;; cached/cache-write tokens — subtract them exactly like chat's
          ;; prompt_tokens (pi normalizeUsage; the details sub-map marks the
          ;; responses shape, the cache_*_input_tokens keys the bedrock one).
          responses? (or (get-in usage [:input_tokens_details :cached_tokens])
                         (get-in usage [:input_tokens_details :cache_write_tokens])
                         ;; Bedrock ConverseStream (anthropic's shape uses
                         ;; cache_creation_input_tokens for writes instead)
                         (contains? usage :cache_write_input_tokens))
          input (cond
                  responses? (max 0 (- (long (or (:input_tokens usage) 0))
                                       (long (or cache-read 0))
                                       (long (or cache-write 0))))
                  prompt (max 0 (- (long prompt)
                                   (long (or cache-read 0))
                                   (long (or cache-write 0))))
                  (:input_tokens usage) (:input_tokens usage)
                  (:input usage) (:input usage))
          output (or (:completion_tokens usage) (:output_tokens usage) (:output usage))
          cost (or (get-in usage [:cost :total]) 0)]
      (when (or input output cache-read cache-write)
        {:input (long (or input 0))
         :output (long (or output 0))
         :cache-read (long (or cache-read 0))
         :cache-write (long (or cache-write 0))
         :cost (double cost)}))))

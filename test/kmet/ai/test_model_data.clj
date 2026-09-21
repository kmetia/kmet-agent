(ns kmet.ai.test-model-data
  "Phase 1: offline strict validation of the committed provider catalogs +
   manifest hash match (pi check-model-data.ts / validateModelDataDirectory).

   The generator (kmet.ai.model-gen, entered via kmet.tasks.generate-models)
   is the single source of truth for the strict per-model validation; this
   test runs its offline half over the committed files, so
   drift (uncommitted regenerations, hand-edits) is caught in CI without
   network."
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.ai.model-gen :as mg]
            [kmet.libs.http :as http]
            [kmet.tasks.generate-models :as gen-models]))

(defn- validate-dir
  "Run the generator's offline validation over DIR (defaults to the committed
   catalog dir) — validate-committed! is a pure read of the catalog files."
  [& [dir]]
  (let [f gen-models/validate-committed!]
    (if dir (f dir) (f))))

(t/deftest test-committed-catalogs-valid
  (let [errors (validate-dir)]
    (t/is (empty? errors)
          (str "committed catalogs failed strict validation:\n"
               (str/join "\n" errors)))))

;; ─── Negative paths (the offline gate must fail loudly on broken data) ─────

(defn- with-scratch-data
  "Write FILE-CONTENTS into an isolated temp dir, run F with the dir."
  [file-contents f]
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-data-test-"}))]
    (doseq [[fname content] file-contents]
      (spit (str dir "/" fname) content))
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- gen-model
  "One valid generated-model map for PID/MID."
  [pid mid]
  {:id mid :name (str/upper-case mid) :provider pid
   :api :openai-completions :base-url "https://gen.example/v1"
   :reasoning false :input [:text]
   :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
   :context-window 1000 :max-tokens 100})

(defn- dir-mtimes
  "File name -> last-modified string for DIR's top level (stable ordering).
   Callers sleep past the filesystem mtime granularity before rewriting, so
   a rewritten file observably moves."
  [dir]
  (into (sorted-map)
        (for [f (fs/list-dir dir)]
          [(fs/file-name f) (str (fs/last-modified-time f))])))

(defn- valid-catalog
  []
  {:schema-version 1
   :generated-at "2026-08-08T00:00:00Z"
   :provider {:id :scratch :name "Scratch" :env-vars ["SCRATCH_KEY"]
              :default-model "m1"}
   :models {:openai-completions
            {"m1" {:id "m1" :name "M1" :provider :scratch
                   :api :openai-completions :base-url "https://scratch.example/v1"
                   :reasoning false :input [:text]
                   :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                   :context-window 1000 :max-tokens 100}}}})

(t/deftest test-validate-committed-rejects-missing-manifest
  (with-scratch-data
    [["scratch.edn" (pr-str (valid-catalog))]]
    (fn [dir]
      (let [errors (validate-dir dir)]
        (t/is (some #(re-find #"manifest.edn is missing or incomplete" %) errors)
              (str "expected a manifest error, got: " errors))))))

(t/deftest test-validate-committed-rejects-broken-catalog
  (let [broken (assoc-in (valid-catalog) [:models :openai-completions "m1" :reasoning] "yes")]
    (with-scratch-data
      [["scratch.edn" (pr-str broken)]]
      (fn [dir]
        (let [errors (validate-dir dir)]
          (t/is (some #(re-find #"has no reasoning boolean" %) errors)
                (str "expected a reasoning error, got: " errors)))))))

(t/deftest test-openai-gpt-6-astra-context
  (let [model (some #(when (= "gpt-6-astra" (:id %)) %)
                    (#'mg/missing-openai-models #{}))
        normalized (#'mg/normalize-openai model)]
    (t/is (= 1050000 (:context-window model)))
    (t/is (= 1050000 (:context-window normalized))
          "the API context is not capped to the Codex subscription window")
    (t/is (= 272000 (get-in normalized [:cost :tiers 0 :input-tokens-above])))))

(t/deftest test-codex-gpt-6-astra
  "GPT-6 Astra is a static ChatGPT OAuth catalog entry, not a model exposed
   by the OAuth login response. Keep its Codex-specific wire metadata in
   lockstep with pi's hardcoded codexModels entry."
  (let [model (some #(when (= "gpt-6-astra" (:id %)) %)
                    (#'mg/process-codex))]
    (t/is (= "gpt-6-astra" (:id model)))
    (t/is (= "GPT-6 Astra" (:name model)))
    (t/is (= :openai-codex (:provider model)))
    (t/is (= :openai-codex-responses (:api model)))
    (t/is (= "https://chatgpt.com/backend-api" (:base-url model)))
    (t/is (= true (:reasoning model)))
    (t/is (= [:text :image] (:input model)))
    (t/is (= {:input 10 :output 50 :cache-read 1 :cache-write 12.5
              :tiers [{:input-tokens-above 272000
                       :input 20.0 :output 75.0 :cache-read 2.0 :cache-write 25.0}]}
             (:cost model)))
    (t/is (= 272000 (:context-window model)))
    (t/is (= 128000 (:max-tokens model)))
    (let [model (#'mg/apply-thinking-maps model nil)]
      (t/is (= {:off nil :minimal "low" :low "low" :medium "medium"
                :high "high" :xhigh "xhigh" :max "max"}
               (:thinking-level-map model)))
      (t/is (= {:supports-openai-grammar-tools true
                :supports-tool-search true
                :supports-additional-tools true}
               (:compat (#'mg/apply-compat-metadata model)))))))

(t/deftest test-commandcode-refs-transfer-capabilities
  "Regression: the canonical-ref lookup in process-commandcode (get-in
   grouped [provider model-id]) must receive the {provider -> {model-id ->
   model}} shape. When fed the bare group-by :provider vector shape it
   silently returns nil and every commandcode model falls back to
   conservative defaults (:reasoning false, :max-tokens 32768) — the
   deepseek-v4/claude/gpt entries lost thinking support. Drive the same
   path generate-models-data uses: provider-model-index over the canonical
   models, then process-commandcode."
  (let [canonical {:id "deepseek-flash" :provider :deepseek
                   :api :openai-completions :base-url "https://api.deepseek.com"
                   :reasoning true :input [:text]
                   :cost {:input 0.14 :output 0.28 :cache-read 0.0028 :cache-write 0}
                   :context-window 1000000 :max-tokens 384000
                   :thinking-level-map {:high "high" :low nil :max "max"
                                        :medium nil :minimal nil}
                   :compat {:thinking-format :deepseek}}
        fetched [{"id" "deepseek/deepseek-v4-flash"
                  "name" "DeepSeek V4 Flash (latest)"
                  "context_length" 1000000}]
        grouped (#'mg/provider-model-index [canonical])
        out (first (#'mg/process-commandcode fetched grouped {}))]
    (t/is (= true (:reasoning out))
          "canonical :reasoning must transfer to the commandcode model")
    (t/is (= 384000 (:max-tokens out))
          "canonical :max-tokens must transfer (was clamped to 32768)")
    (t/is (= {:high "high" :low nil :max "max" :medium nil :minimal nil}
             (:thinking-level-map out))
          "canonical thinking-level-map must transfer")
    (t/is (= :deepseek (:thinking-format (:compat out)))
          "model-bound compat keys must transfer")
    (t/is (= "deepseek/deepseek-v4-flash" (:id out)))))

(t/deftest ^:slow test-commandcode-prices-timeout-proceeds
  "A stalled CommandCode price fetch must not block generation: the bounded
   fetch-commandcode-prices wrapper gives up after the cap and returns nil,
   so generation proceeds with zero rates. Drives the real wrapper with a
   stubbed inner fetch (fast path asserts no regression on the happy path)."
  (with-redefs [mg/fetch-commandcode-prices*
                (fn [] {"x" {:input 1 :output 2 :cache-read 0 :cache-write 0}})]
    (t/is (= {"x" {:input 1 :output 2 :cache-read 0 :cache-write 0}}
             (mg/fetch-commandcode-prices))
          "fast inner fetch passes through"))
  (with-redefs [mg/fetch-commandcode-prices* (fn [] (Thread/sleep 300000))
                mg/commandcode-prices-timeout-ms 500]
    (let [t0 (System/currentTimeMillis)
          res (mg/fetch-commandcode-prices)
          dt (- (System/currentTimeMillis) t0)]
      (t/is (nil? res) "stalled fetch yields nil (zero rates downstream)")
      (t/is (< dt 30000) (str "wrapper must give up at the cap, took " dt "ms")))))

(t/deftest test-write-catalogs-skips-unchanged
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-gen-test-"}))
        catalogs {:gen-alpha [(gen-model :gen-alpha "a1")]
                  :gen-beta [(gen-model :gen-beta "b1")]}]
    (try
      ;; initial generation writes both catalogs + the manifest
      (t/is (= 3 (mg/write-catalogs! dir catalogs)))
      ;; rerun with identical data: nothing is written, no mtime moves
      (let [before (dir-mtimes dir)]
        (t/is (zero? (mg/write-catalogs! dir catalogs)))
        (t/is (= before (dir-mtimes dir))
              "an unchanged regeneration touches no file"))
      ;; changing ONE provider rewrites just its file + the manifest
      (let [before-bytes (into (sorted-map)
                               (for [f (fs/list-dir dir)]
                                 [(fs/file-name f) (slurp (str f))]))
            before-mtimes (dir-mtimes dir)
            bumped (update-in catalogs [:gen-alpha 0 :context-window] inc)
            n (mg/write-catalogs! dir bumped)]
        (t/is (= 2 n) "only the changed catalog + manifest")
        (let [after-bytes (into (sorted-map)
                                (for [f (fs/list-dir dir)]
                                  [(fs/file-name f) (slurp (str f))]))
              after-mtimes (dir-mtimes dir)]
          (t/is (not= (get before-bytes "gen-alpha.edn") (get after-bytes "gen-alpha.edn"))
                "changed provider rewrites")
          (t/is (= (get before-bytes "gen-beta.edn") (get after-bytes "gen-beta.edn"))
                "unchanged provider keeps its bytes")
          (t/is (= (get before-mtimes "gen-beta.edn") (get after-mtimes "gen-beta.edn"))
                "unchanged provider is not rewritten (mtime untouched)")
          (t/is (not= (get before-bytes "manifest.edn") (get after-bytes "manifest.edn"))
                "manifest follows the change"))
        ;; the result still passes the committed-data gate
        (t/is (empty? (mg/validate-committed! dir))))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-write-catalogs-keeps-per-file-timestamps
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-gen-test-"}))
        catalogs {:gen-alpha [(gen-model :gen-alpha "a1")]
                  :gen-beta [(gen-model :gen-beta "b1")]}
        gen-at (atom "2026-08-08T00:00:01Z")]
    (try
      (with-redefs [mg/generated-at (fn [] @gen-at)]
        ;; initial generation writes both catalogs + the manifest
        (t/is (= 3 (mg/write-catalogs! dir catalogs)))
        ;; partial change at a later timestamp: only alpha + manifest rewrite
        (reset! gen-at "2026-08-08T00:00:02Z")
        (let [bumped (update-in catalogs [:gen-alpha 0 :context-window] inc)]
          (t/is (= 2 (mg/write-catalogs! dir bumped)))
          ;; identical rerun at a still-later timestamp: nothing is written —
          ;; beta keeps its original timestamp instead of being restamped
          ;; with a timestamp-only diff
          (reset! gen-at "2026-08-08T00:00:03Z")
          (let [before (dir-mtimes dir)]
            (t/is (zero? (mg/write-catalogs! dir bumped))
                  "unchanged files keep their own timestamps (no timestamp-only diffs)")
            (t/is (= before (dir-mtimes dir))
                  "an unchanged regeneration touches no file"))
          (t/is (str/includes? (slurp (str dir "/gen-beta.edn")) "2026-08-08T00:00:01Z")
                "unchanged provider keeps its original timestamp")
          (t/is (str/includes? (slurp (str dir "/gen-alpha.edn")) "2026-08-08T00:00:02Z")
                "changed provider carries the update timestamp")
          (t/is (empty? (mg/validate-committed! dir)))))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-openrouter-thinking-level-map
  "pi getOpenRouterThinkingLevelMap (openrouter-reasoning-options.test.ts):
   OpenRouter's live reasoning metadata (supported_efforts + mandatory) →
   thinking-level-map. Shares the models.dev effort conversion; :off is
   \"none\" unless the model mandates reasoning (then nil)."
  (let [tlm @#'mg/openrouter-thinking-level-map]
    (t/is (= {:off nil :minimal nil :low "low" :medium nil
              :high "high" :xhigh nil :max "max"}
             (tlm {"mandatory" true "default_enabled" true
                   "supported_efforts" ["max" "high" "low"]
                   "default_effort" "max"}))
          "mandatory reasoning pins :off to nil, supported efforts pass through")
    (t/is (= {:off nil}
             (tlm {"mandatory" true}))
          "mandatory without effort metadata still marks :off unavailable")
    (t/is (= {:off "none" :minimal nil :low "low" :medium nil
              :high "high" :xhigh nil :max nil}
             (tlm {"mandatory" false "default_enabled" true
                   "supported_efforts" ["high" "low"]}))
          "optional models keep :off available, restricted to supported efforts")
    (t/is (nil? (tlm {"mandatory" false}))
          "optional models without effort controls get no metadata")
    (t/is (nil? (tlm nil)) "missing reasoning metadata → nil")
    (t/is (nil? (tlm "effort")) "non-map reasoning metadata → nil")))

(defn- openrouter-list-body
  "Fake OpenRouter /models payload with MODELS (string-keyed maps)."
  [models]
  (json/generate-string {"data" models}))

(defn- openrouter-list-entry
  "One fake OpenRouter model entry; REASONING is the live reasoning
   metadata map (or nil for models without it)."
  [id reasoning]
  (cond-> {"id" id "name" id
           "supported_parameters" ["tools" "reasoning"]
           "architecture" {"modality" "text"}
           "pricing" {"prompt" "0" "completion" "0"
                      "input_cache_read" "0" "input_cache_write" "0"}
           "context_length" 1000}
    reasoning (assoc "reasoning" reasoning)))

(t/deftest test-fetch-openrouter-models-attaches-thinking-map
  "pi fetchOpenRouterModels: the live reasoning metadata becomes the
   model's thinking-level-map at fetch time (mandatory muse-spark pins
   :off to nil); models without the metadata ship without a map."
  (with-redefs [http/get (fn [_url _opts]
                           {:status 200
                            :body (openrouter-list-body
                                   [(openrouter-list-entry
                                     "meta/muse-spark-1.3-contributor"
                                     {"mandatory" true
                                      "supported_efforts" ["xhigh" "high" "medium"
                                                           "low" "minimal"]
                                      "default_effort" "medium"})
                                    (openrouter-list-entry "plain/model" nil)])})]
    (let [by-id (into {} (map (juxt :id identity))
                      (#'mg/fetch-openrouter-models))
          spark (get by-id "meta/muse-spark-1.3-contributor")
          plain (get by-id "plain/model")]
      (t/is (= {:off nil :minimal "minimal" :low "low" :medium "medium"
                :high "high" :xhigh "xhigh" :max nil}
               (:thinking-level-map spark))
            "mandatory spark efforts transfer, :off pinned to nil")
      (t/is (not (contains? plain :thinking-level-map))
            "models without reasoning metadata get no map"))))

(t/deftest test-commandcode-muse-spark-13-contributor-ref
  "The contributor SKU resolves against its contributor counterpart: both
   1.2 and 1.3 point at the opencode-go entries carrying the verified
   models.dev effort maps (the 1.3 row briefly pointed at the then map-less
   openrouter entry and lost its thinking levels)."
  (let [refs @#'mg/commandcode-canonical-refs]
    (t/is (= [:opencode-go "muse-spark-1.2-contributor"]
             (get refs "meta/muse-spark-1.2-contributor")))
    (t/is (= [:opencode-go "muse-spark-1.3-contributor"]
             (get refs "meta/muse-spark-1.3-contributor"))))
  (let [canonical {:id "muse-spark-1.3-contributor" :provider :opencode-go
                   :api :openai-responses :base-url "https://opencode.ai/zen/go/v1"
                   :reasoning true :input [:text :image]
                   :cost {:input 0.1 :output 0.2 :cache-read 0.002 :cache-write 0}
                   :context-window 1048576 :max-tokens 131072
                   :thinking-level-map {:high "high" :low "low" :max nil
                                        :medium "medium" :minimal "minimal"
                                        :off nil :xhigh "xhigh"}}
        fetched [{"id" "meta/muse-spark-1.3-contributor"
                  "name" "Muse Spark 1.3 Contributor"
                  "context_length" 1048576}]
        grouped (#'mg/provider-model-index [canonical])
        out (first (#'mg/process-commandcode fetched grouped {}))]
    (t/is (= {:high "high" :low "low" :max nil
              :medium "medium" :minimal "minimal"
              :off nil :xhigh "xhigh"}
             (:thinking-level-map out))
          "the 1.3 contributor keeps its full effort map")))

(t/deftest test-commandcode-deepseek-v41-flash-ref-resolves
  "Regression: the commandcode deepseek-v4.1-flash ref must resolve against
   DeepSeek's own canonical catalog. It previously pointed at opencode-go's
   then-deepseek-flash id, which that aggregator renamed to
   deepseek-v4.1-flash (models bump 2026-09-11) — the ref resolved to nil
   and the endpoint entry silently reverted to conservative defaults
   (:reasoning false, text-only, 32768 max-tokens). pi 12f59336a carries the
   canonical model as :deepseek/deepseek-flash (V4.1 Flash); clamp there
   and guard both the ref value and the committed catalog entry."
  (let [refs @#'mg/commandcode-canonical-refs
        [rp rid] (get refs "deepseek/deepseek-v4.1-flash")
        catalogs (#'mg/read-catalogs mg/data-dir)
        canonical (get-in catalogs [rp :openai-completions rid])
        cc (get-in catalogs [:commandcode :openai-completions
                             "deepseek/deepseek-v4.1-flash"])]
    (t/is (= [:deepseek "deepseek-flash"] [rp rid])
          "the ref must clamp to DeepSeek's canonical v4.1 flash id")
    (t/is (some? canonical)
          (str "the referenced canonical model must exist in the committed "
               "catalog (" rp " " rid ")"))
    (t/is (some? cc) "the commandcode entry must exist")
    (t/is (= true (:reasoning cc))
          "the commandcode entry must carry thinking support")
    (t/is (= 384000 (:max-tokens cc))
          "canonical :max-tokens must transfer (not the 32768 fallback)")
    (t/is (= [:text :image] (:input cc))
          "canonical image input must transfer")
    (t/is (= (:thinking-level-map canonical) (:thinking-level-map cc))
          "thinking levels must transfer from the canonical entry")))

(t/deftest test-commandcode-deepseek-flash-aliases-clamp-to-deepseek
  "Every CommandCode deepseek-v4 flash SKU is the same weights as DeepSeek's
   canonical `deepseek-flash` (V4.1 Flash) — pi 12f59336a collapsed the
   retired v4-flash/-vision-exp aliases into it. Each ref must resolve to
   that canonical and the endpoint entry must carry thinking support."
  (let [refs @#'mg/commandcode-canonical-refs
        catalogs (#'mg/read-catalogs mg/data-dir)
        flash (get-in catalogs [:deepseek :openai-completions "deepseek-flash"])]
    (t/is (some? flash) "the canonical deepseek-flash entry must exist")
    (doseq [cid ["deepseek/deepseek-v4-flash"
                 "deepseek/deepseek-v4-flash-fast"
                 "deepseek/deepseek-v4.1-flash"]]
      (t/is (= [:deepseek "deepseek-flash"] (get refs cid))
            (str cid " must clamp to DeepSeek's canonical flash id"))
      (let [cc (get-in catalogs [:commandcode :openai-completions cid])]
        (t/is (some? cc) (str cid " must be listed"))
        (t/is (= true (:reasoning cc))
              (str cid " must carry thinking support"))))))

(t/deftest test-commandcode-retired-canonical-refs-repointed
  "Regression: four CommandCode canonical refs pointed at catalog ids that
   no longer exist — Moonshot retired kimi-k2.5 (Sep 2026 bump), the Qwen
   token-plan catalog never carried qwen3.7-flash/qwen3.6-max-preview, and
   NVIDIA NIM dropped thinkingmachines/inkling — so process-commandcode
   warned and those entries shipped conservative defaults (:reasoning
   false, text-only, 32768 max-tokens). Each ref now points at the same
   weights in a catalog that lists them, and the committed endpoint entry
   carries thinking support."
  (let [refs @#'mg/commandcode-canonical-refs
        catalogs (#'mg/read-catalogs mg/data-dir)
        check (fn [cid rp rid]
                (t/is (= [rp rid] (get refs cid))
                      (str cid " must point at " rp " " rid))
                (let [canonical (get-in catalogs [rp :openai-completions rid])
                      cc (get-in catalogs [:commandcode :openai-completions cid])]
                  (t/is (some? canonical)
                        (str "the referenced canonical model must exist (" rp " " rid ")"))
                  (t/is (some? cc) (str cid " must be listed"))
                  (t/is (= true (:reasoning cc))
                        (str cid " must carry thinking support"))
                  (t/is (= (:max-tokens canonical) (:max-tokens cc))
                        (str cid " must carry the canonical max-tokens"))
                  (t/is (= (:input canonical) (:input cc))
                        (str cid " must carry the canonical input modalities"))))]
    ;; Moonshot retired kimi-k2.5: Baseten still serves the same weights
    ;; (toggle thinking); its endpoint-bound :baseten format does not
    ;; transfer (commandcode-no-compat-refs).
    (check "moonshotai/Kimi-K2.5" :baseten "moonshotai/Kimi-K2.5")
    ;; The token-plan catalog never carried these two Qwen SKUs: OpenRouter does.
    (check "Qwen/Qwen3.6-Max-Preview" :openrouter "qwen/qwen3.6-max-preview")
    (check "Qwen/Qwen3.7-Flash" :openrouter "qwen/qwen3.7-flash")
    ;; NVIDIA NIM dropped inkling: Baseten still serves it.
    (check "thinkingmachines/inkling" :baseten "thinkingmachines/inkling")))

(t/deftest test-openai-completions-strict-metadata
  ;; pi 890f92088 + af7359b90: strict tools are explicit metadata — capable
  ;; providers get :supports-strict-mode true against the generator's
  ;; conservative false default, Cerebras is excluded (mixed strict/unstrict
  ;; tool usage 400s there).
  (let [detect @#'mg/detect-openai-completions-compat
        delta @#'mg/compat-delta
        model (fn [provider base-url]
                {:provider provider :base-url base-url :id "m" :api :openai-completions})]
    (t/is (false? (:supports-strict-mode @#'mg/openai-completions-default-compat)))
    (t/testing "capable providers keep an explicit true delta"
      (let [m (model :openrouter "https://openrouter.ai/api/v1")]
        (t/is (true? (:supports-strict-mode (detect m))))
        (t/is (true? (:supports-strict-mode (delta (detect m)))))))
    (t/testing "excluded providers stay on the conservative default"
      (doseq [[p url] [[:cerebras "https://api.cerebras.ai/v1"]
                       [:moonshotai "https://api.moonshot.cn/v1"]
                       [:together "https://api.together.xyz/v1"]
                       [:nvidia "https://integrate.api.nvidia.com/v1"]
                       [:cloudflare-ai-gateway "https://gateway.ai.cloudflare.com"]]]
        (t/is (nil? (:supports-strict-mode (delta (detect (model p url)))))
              (str p " must not emit strict tools"))))))


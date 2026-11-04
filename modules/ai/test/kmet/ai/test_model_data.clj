(ns kmet.ai.test-model-data
  "Phase 1: offline strict validation of the committed provider catalogs +
   manifest hash match (pi check-model-data.ts / validateModelDataDirectory).

   The generator (kmet.ai.model-gen, entered via kmet.tasks.generate-models)
   is the single source of truth for the strict per-model validation; this
   test runs its offline half over the committed files, so
   drift (uncommitted regenerations, hand-edits) is caught in CI without
   network."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.edn-writer :as edn-w]
            [kmet.ai.model-gen :as mg]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.tasks.generate-models :as gen-models]
            [kmet.test-tmp :as test-tmp]))

(defn- validate-dir
  "Run the generator's offline validation over DIR (defaults to the committed
   catalog dir) — validate-committed! is a pure read of the catalog files."
  [& [dir]]
  (let [f gen-models/validate-committed!]
    (if dir (f dir) (f))))

(t/deftest test-generator-detection-agrees-with-runtime
  ;; the generator keeps a build-time copy of the compat detection (its
  ;; :supports-strict-mode rule and together's reasoning-only exception are
  ;; deliberate); every cache/affinity/max-tokens key must agree with the
  ;; runtime detection, or a regenerated catalog overrides it silently —
  ;; :send-session-affinity-headers was hardcoded false and would have turned
  ;; the anthropic-format affinity headers off on every OpenRouter row.
  (let [gen @#'mg/detect-openai-completions-compat
        keys [:supports-store :supports-developer-role :supports-reasoning-effort
              :max-tokens-field :requires-reasoning-content-on-assistant-messages
              :send-session-affinity-headers :supports-long-cache-retention]
        model (fn [provider base-url id]
                {:id id :provider provider :base-url base-url})]
    (doseq [m [(model :openrouter "https://openrouter.ai/api/v1" "anthropic/claude-x")
               (model :openrouter "https://openrouter.ai/api/v1" "deepseek/deepseek-x")
               (model :custom "https://api.deepseek.com" "deepseek-v4-flash")
               (model :custom "https://api.example.com/v1" "m1")
               (model :together "https://api.together.xyz/v1" "m1")]]
      (t/is (= (select-keys (gen m) keys)
               (select-keys (shared/detect-openai-compat m) keys))
            (str (:provider m) "/" (:id m))))))

(t/deftest test-committed-catalogs-valid
  (let [errors (validate-dir)]
    (t/is (empty? errors)
          (str "committed catalogs failed strict validation:\n"
               (str/join "\n" errors)))))

;; ─── Negative paths (the offline gate must fail loudly on broken data) ─────

(defn- with-scratch-data
  "Write FILE-CONTENTS into an isolated temp dir, run F with the dir."
  [file-contents f]
  (let [dir (test-tmp/temp-dir! "model-data-test-")]
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

(t/deftest test-openai-gpt-6-context
  "Direct OpenAI API models retain their 1.05M context; the separate Codex
   catalog applies the ChatGPT subscription's 272K limit."
  (let [models (into {} (map (juxt :id identity) (#'mg/missing-openai-models #{})))]
    (doseq [id ["gpt-6-astra" "gpt-6-sol" "gpt-6-luna" "gpt-6.1-sol"]]
      (let [model (get models id)
            normalized (#'mg/normalize-openai model)]
        (t/is (= 1050000 (:context-window model)))
        (t/is (= 1050000 (:context-window normalized))
              "direct API context must not be capped to the Codex subscription limit")
        (t/is (= 272000 (get-in normalized [:cost :tiers 0 :input-tokens-above])))))))

(defn- compat-of [provider api id]
  (:compat (#'mg/apply-compat-metadata
            {:provider provider :api api :id id
             :base-url "https://example.test/v1"})))

(t/deftest test-mid-convo-capability-metadata
  (t/testing "direct Anthropic Claude models: system messages and inline tool changes"
    (t/is (= {:supports-mid-convo-system-messages true
              :supports-mid-convo-tool-changes true}
             (select-keys (compat-of :anthropic :anthropic-messages "claude-sonnet-5-5")
                          [:supports-mid-convo-system-messages
                           :supports-mid-convo-tool-changes]))))
  (t/testing "proxies that reject inline tool blocks get text only"
    (let [c (compat-of :github-copilot :anthropic-messages "claude-sonnet-5-5")]
      (t/is (true? (:supports-mid-convo-system-messages c)))
      (t/is (nil? (:supports-mid-convo-tool-changes c))))
    (t/is (nil? (:supports-mid-convo-system-messages
                 (compat-of :commandcode :anthropic-messages "claude-sonnet-5-5")))
          "an unverified Anthropic proxy claims nothing"))
  (t/testing "Kimi K3 accepts tool-bearing system messages; K2.6 text only"
    (t/is (= {:supports-mid-convo-system-messages true
              :supports-mid-convo-tool-additions true}
             (select-keys (compat-of :moonshotai :openai-completions "kimi-k3")
                          [:supports-mid-convo-system-messages
                           :supports-mid-convo-tool-additions])))
    (let [c (compat-of :moonshotai :openai-completions "kimi-k2.6")]
      (t/is (true? (:supports-mid-convo-system-messages c)))
      (t/is (nil? (:supports-mid-convo-tool-additions c)))))
  (t/testing "a known Responses model gets mid-conversation developer messages"
    (t/is (true? (:supports-mid-convo-system-messages
                  (compat-of :openai :openai-responses "gpt-5.4"))))
    (t/is (nil? (:supports-mid-convo-system-messages
                 (compat-of :openai :openai-responses "gpt-4o"))))))

(t/deftest test-codex-gpt-6-models
  "GPT-6 models are static ChatGPT OAuth catalog entries. Keep their
   Codex-specific wire metadata in lockstep with pi's codexModels entries."
  (let [models (into {} (map (juxt :id identity) (#'mg/process-codex)))]
    (doseq [[id name cost off]
            [["gpt-6-astra" "GPT-6 Astra"
              {:input 10 :output 50 :cache-read 1 :cache-write 12.5} nil]
             ["gpt-6-sol" "GPT-6 Sol"
              {:input 2 :output 10 :cache-read 0.2 :cache-write 2.5} "none"]
             ["gpt-6-luna" "GPT-6 Luna"
              {:input 0.1 :output 0.5 :cache-read 0.01 :cache-write 0.125} "none"]
             ["gpt-6.1-sol" "GPT-6.1 Sol"
              {:input 2 :output 10 :cache-read 0.1 :cache-write 2.5} nil]]]
      (let [model (get models id)
            expected-cost (assoc cost :tiers [{:input-tokens-above 272000
                                               :input (* 2.0 (:input cost))
                                               :output (* 1.5 (:output cost))
                                               :cache-read (* 2.0 (:cache-read cost))
                                               :cache-write (* 2.0 (:cache-write cost))}])]
        (t/is (= name (:name model)))
        (t/is (= :openai-codex (:provider model)))
        (t/is (= :openai-codex-responses (:api model)))
        (t/is (= "https://chatgpt.com/backend-api" (:base-url model)))
        (t/is (= true (:reasoning model)))
        (t/is (= [:text :image] (:input model)))
        (t/is (= expected-cost (:cost model)))
        (t/is (= 272000 (:context-window model)))
        (t/is (= 128000 (:max-tokens model)))
        (let [model (#'mg/apply-thinking-maps model nil)]
          (t/is (= {:off off :minimal "low" :low "low" :medium "medium"
                    :high "high" :xhigh "xhigh" :max "max"}
                   (:thinking-level-map model)))
          (t/is (= {:supports-openai-grammar-tools true
                    :supports-tool-search true
                    :supports-additional-tools true
                    :supports-mid-convo-system-messages true}
                   (:compat (#'mg/apply-compat-metadata model)))))))))

(t/deftest test-openai-gpt-56-standard-costs
  "Regression: the GPT-5.6 entries in the Codex catalog and the direct
   OpenAI fallback use the authoritative standard costs (pi
   OPENAI_STANDARD_COSTS), not the pre-reduction rates."
  (let [expected {"gpt-5.6-sol" {:input 4 :output 20 :cache-read 0.4 :cache-write 5}
                  "gpt-5.6-terra" {:input 2 :output 12 :cache-read 0.2 :cache-write 2.5}
                  "gpt-5.6-luna" {:input 0.2 :output 1.2 :cache-read 0.02 :cache-write 0.25}}
        base (fn [mm] (select-keys (:cost mm) [:input :output :cache-read :cache-write]))
        fallback (into {} (map (juxt :id identity) (#'mg/missing-openai-models #{})))
        codex (into {} (map (juxt :id identity) (#'mg/process-codex)))]
    (doseq [[id cost] expected]
      (t/is (= cost (base (get fallback id))) (str "openai fallback " id))
      (t/is (= cost (base (get codex id))) (str "codex " id)))))

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

(t/deftest test-open-design-refs-transfer-capabilities
  "Every open-design ref resolves against the committed canonical catalogs
   (a ref that drifts out of the catalog set is a generation-time error), the
   vendor limits transfer through it, the gateway list price wins over the
   ref's cost, and a :thinking-format is taken only from a vendor ref — a
   proxy ref's format is endpoint-bound and must not leak."
  (let [catalogs (#'mg/read-catalogs mg/data-dir)
        canonical (apply concat (vals (#'mg/flatten-catalogs catalogs)))
        grouped (#'mg/provider-model-index canonical)
        by-id (into {} (map (juxt :id identity)) (#'mg/process-open-design grouped))
        claude (get by-id "claude-opus-5.5")
        glm-flash (get by-id "glm-5.2")]
    (t/is (= :openai-completions (:api claude))
          "every model is catalogued on the Chat Completions wire")
    (t/is (= "https://amr-link.open-design.ai/v1" (:base-url claude)))
    (t/is (= 1000000 (:context-window claude)) "canonical limits transfer")
    (t/is (= 128000 (:max-tokens claude)))
    (t/is (= [:text :image] (:input claude)))
    (t/is (= {:force-adaptive-thinking true} (:compat claude))
          "a vendor ref contributes only the model-bound compat keys")
    (t/is (nil? (:compat (get by-id "glm-5.3-flashx")))
          "a proxy ref contributes no endpoint-bound compat")
    (t/is (= {:input 1.4 :output 4.4 :cache-read 0.26 :cache-write 0}
             (:cost glm-flash))
          "the gateway list price wins over the ref's (zai bills 0)")
    (t/is (= "GLM-5.3 Flash-X" (:name (get by-id "glm-5.3-flashx")))
          "an explicit name wins over the ref's")
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (doall (#'mg/process-open-design
                           (#'mg/provider-model-index []))))
          "an unresolvable ref throws instead of degrading silently")))

(t/deftest test-open-design-committed-catalog
  "The committed open-design catalog is one Chat Completions group on the
   amr-link base; the Hunyuan entry (the only model without a canonical ref)
   keeps the literal family limits, and every model carries usable limits."
  (let [data (edn/read-string (slurp (str mg/data-dir "/open-design.edn")))
        groups (:models data)
        models (mapcat vals (vals groups))
        by-id (into {} (map (juxt :id identity)) models)
        hunyuan (get by-id "hy4-preview")]
    (t/is (= #{:openai-completions} (set (keys groups))))
    (t/is (= 34 (count models)))
    (t/is (= #{"https://amr-link.open-design.ai/v1"} (set (map :base-url models))))
    (t/is (every? (fn [m] (and (boolean? (:reasoning m))
                               (pos? (:context-window m))
                               (pos? (:max-tokens m))
                               (every? #(number? (get-in m [:cost %]))
                                       [:input :output :cache-read :cache-write])))
                  models))
    (t/is (= {:input 0.834 :output 2.501 :cache-read 0.042 :cache-write 0}
             (:cost hunyuan)))
    (t/is (= [131072 16384]
             [(:context-window hunyuan) (:max-tokens hunyuan)]))
    (t/is (= "deepseek-v4.1-flash" (get-in data [:provider :default-model])))))

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
    (let [t0 (concurrent/monotonic-ms)
          res (mg/fetch-commandcode-prices)
          dt (- (concurrent/monotonic-ms) t0)]
      (t/is (nil? res) "stalled fetch yields nil (zero rates downstream)")
      (t/is (< dt 30000) (str "wrapper must give up at the cap, took " dt "ms")))))

(t/deftest test-write-catalogs-skips-unchanged
  (let [dir (test-tmp/temp-dir! "model-gen-test-")
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
  (let [dir (test-tmp/temp-dir! "model-gen-test-")
        catalogs {:gen-alpha [(gen-model :gen-alpha "a1")]
                  :gen-beta [(gen-model :gen-beta "b1")]}
        gen-at (atom "2026-08-08T00:00:01Z")]
    (try
      (with-redefs [edn-w/generated-at (fn [] @gen-at)]
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
  "Regression: three CommandCode canonical refs pointed at catalog ids that
   no longer exist — Moonshot retired kimi-k2.5 (Sep 2026 bump), the Qwen
   token-plan catalog never carried qwen3.7-flash, and NVIDIA NIM dropped
   thinkingmachines/inkling — so process-commandcode warned and those
   entries shipped conservative defaults (:reasoning false, text-only, 32768
   max-tokens). Each ref now points at the same weights in a catalog that
   lists them, and the committed endpoint entry carries thinking support."
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
    ;; Moonshot retired kimi-k2.5: Hugging Face still serves the same weights;
    ;; its endpoint-bound :baseten format does not transfer
    ;; (commandcode-no-compat-refs).
    (check "moonshotai/Kimi-K2.5" :huggingface "moonshotai/Kimi-K2.5")
    ;; The token-plan catalog never carried this Qwen SKU: OpenRouter does.
    (check "Qwen/Qwen3.7-Flash" :openrouter "qwen/qwen3.7-flash")
    ;; NVIDIA NIM dropped inkling: OpenRouter still serves it.
    (check "thinkingmachines/inkling" :openrouter "thinkingmachines/inkling")))

(t/deftest test-commandcode-laguna-ref-follows-openrouter-free-retirement
  "Regression: OpenRouter retired its poolside/laguna-s-2.1:free listing
   (the 2026-11-02 model bump dropped every poolside id), so the ref warned
   and the endpoint shipped conservative defaults (:reasoning false, 32768
   max-tokens). Vercel AI Gateway serves the same weights under the bare id,
   and its entry carries no :compat of its own, so no endpoint-bound
   thinking format transfers to the CommandCode route."
  (let [refs @#'mg/commandcode-canonical-refs
        catalogs (#'mg/read-catalogs mg/data-dir)
        cid "poolside/laguna-s-2.1-free"
        rp :vercel-ai-gateway
        rid "poolside/laguna-s-2.1"
        canonical (->> (get catalogs rp) vals (keep #(get % rid)) first)
        cc (get-in catalogs [:commandcode :openai-completions cid])]
    (t/is (= [rp rid] (get refs cid))
          (str cid " must point at " rp " " rid))
    (t/is (some? canonical)
          (str "the referenced canonical model must exist (" rp " " rid ")"))
    (t/is (some? cc) (str cid " must be listed"))
    (t/is (= true (:reasoning cc))
          (str cid " must carry thinking support"))
    (t/is (= (:max-tokens canonical) (:max-tokens cc))
          (str cid " must carry the canonical max-tokens"))
    (t/is (= (:input canonical) (:input cc))
          (str cid " must carry the canonical input modalities"))))

(t/deftest test-commandcode-cat-jam-ref-follows-beam-rename
  "Cat Jam was the stealth codename for reflection's Beam 1.0: OpenCode
   deprecated the cat-jam-free id in favour of beam-free (same limits and
   models.dev effort map), so the ref follows the rename instead of warning
   on every generation run. The newly listed reflection/beam:free endpoint
   id resolves against the same canonical.

   Only the live id can be checked against the generated catalog: the
   endpoint has since retired the cat-jam alias, and process-commandcode
   ships what the endpoint lists. The ref and the override stay in the
   generator so a reappearance transfers capabilities again."
  (let [refs @#'mg/commandcode-canonical-refs
        catalogs (#'mg/read-catalogs mg/data-dir)
        canonical (get-in catalogs [:opencode :openai-completions "beam-free"])
        cid "reflection/beam:free"
        cc (get-in catalogs [:commandcode :openai-completions cid])]
    (t/is (= [:opencode "beam-free"] (get refs "stealth/cat-jam:free"))
          "the retired cat-jam-free ref must follow the rename")
    (t/is (= [:opencode "beam-free"] (get refs "reflection/beam:free"))
          "the unveiled beam id resolves against the same canonical")
    (t/is (some? canonical) "the canonical beam-free entry must exist")
    (t/is (some? cc) (str cid " must be listed"))
    (t/is (= true (:reasoning cc))
          (str cid " must carry thinking support"))
    (t/is (= (:max-tokens canonical) (:max-tokens cc))
          (str cid " must carry the canonical max-tokens"))
    (t/is (= (:input canonical) (:input cc))
          (str cid " must carry the canonical input modalities"))
    (t/is (= (:thinking-level-map canonical) (:thinking-level-map cc))
          (str cid " must carry the canonical thinking levels"))))

(t/deftest test-commandcode-qwen36-max-preview-override
  "OpenRouter retired qwen/qwen3.6-max-preview in the 2026-10-12 model bump
   while the CommandCode endpoint kept serving the id: the dead ref was
   removed (no warning) and the profile the ref used to transfer is pinned
   in commandcode-overrides, so the entry keeps thinking support instead of
   falling back to conservative defaults."
  (let [refs @#'mg/commandcode-canonical-refs
        overrides @#'mg/commandcode-overrides
        catalogs (#'mg/read-catalogs mg/data-dir)
        cid "Qwen/Qwen3.6-Max-Preview"
        cc (get-in catalogs [:commandcode :openai-completions cid])]
    (t/is (nil? (get refs cid))
          "the retired ref must be gone — keeping it warns on every run")
    (t/is (some? (get overrides cid))
          "the pinned profile must live in commandcode-overrides")
    (t/is (some? cc) (str cid " must be listed"))
    (t/is (= true (:reasoning cc)) (str cid " must carry thinking support"))
    (t/is (= 65536 (:max-tokens cc))
          (str cid " must carry the pinned max-tokens"))
    (t/is (= [:text] (:input cc))
          (str cid " must carry the pinned input modalities"))
    (t/is (= {:thinking-format :openrouter} (:compat cc))
          (str cid " must carry the pinned thinking format"))))

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

(t/deftest test-apply-thinking-level-metadata-characterization
  (let [apply #'mg/apply-thinking-level-metadata]
    (t/testing "deepseek-v4 gets high/max thinking levels"
      (t/is (= {:minimal nil :low nil :medium nil :high "high" :max "max"}
               (:thinking-level-map (apply {:id "deepseek-v4" :provider :deepseek
                                            :api :openai-completions})))))
    (t/testing "gpt-5 responses cannot disable thinking"
      (t/is (= {:off nil}
               (:thinking-level-map (apply {:id "gpt-5" :provider :openai
                                            :api :openai-responses})))))
    (t/testing "gemini-3 pro gets LOW/HIGH thinking levels"
      (t/is (= {:off nil :minimal nil :low "LOW" :medium nil :high "HIGH"}
               (:thinking-level-map (apply {:id "gemini-3-pro" :provider :google
                                            :api :google-generative-ai})))))
    (t/testing "adaptive-max claude gets max"
      (t/is (= {:max "max"}
               (:thinking-level-map (apply {:id "claude-opus-4-6" :provider :anthropic
                                            :api :anthropic-messages})))))
    (t/testing "gpt-5.5-pro disables off/minimal/low"
      (t/is (= {:off nil :minimal nil :low nil :xhigh "xhigh"}
               (:thinking-level-map (apply {:id "gpt-5.5-pro" :provider :openai
                                            :api :openai-responses})))))
    (t/testing "non-matching model is unchanged"
      (t/is (nil? (:thinking-level-map (apply {:id "some-model" :provider :foo
                                               :api :bar})))))))

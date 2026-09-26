;; kmet.tasks.generate-image-models — the `bb generate-image-models` task entry:
;; regenerate src/kmet/ai/image_model_data/image-models.edn from the OpenRouter
;; API (pi: packages/ai/scripts/generate-image-models.ts). Task-only code under
;; tasks/ — outside both artifacts' roots (the uberjar walks src/, jolt embeds
;; its :embed roots), so the shipped app never carries it.
;;
;; Keeps models whose output modalities include "image"; input/output
;; modalities from architecture (defaulting input to [:text]); cost =
;; pricing × 1e6 with negative sentinels clamped to 0 (kmet chat openrouter
;; convention). Sorted by id, committed. --strict fails on an empty result.
;;
;; Run via: bb generate-image-models (network)

(ns kmet.tasks.generate-image-models
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.edn :as edn]
            [kmet.ai.edn-writer :as edn-w]
            [kmet.libs.http :as http]))

(def openrouter-base-url "https://openrouter.ai/api/v1")
(def catalog-path
  (str (fs/path (fs/cwd) "src" "kmet" "ai" "image_model_data" "image-models.edn")))

(defn- clamp-cost
  "Clamp a negative pricing sentinel to 0 (pi leaves -1e6 in the generated
   file; kmet's chat openrouter catalogs clamp, and negative rates would
   produce negative costs)."
  [v]
  (let [f (double v)]
    (if (neg? f) 0.0 f)))

(defn- modalities->keywords
  "pi's filter to the text/image modalities, deduped."
  [modalities]
  (distinct (keep #(case %
                     "text" :text
                     "image" :image
                     nil)
                  modalities)))

(defn parse-openrouter-image-models
  "Parse the OpenRouter /models payload into ImagesModel maps (pi
   parseOpenRouterImageModels): output must include \"image\", input
   defaults to [:text] when empty. STRICT fails on a missing/empty list."
  [payload strict]
  (let [data (when (map? payload) (:data payload))]
    (when (and strict (not (seq data)))
      (throw (ex-info "OpenRouter API returned a missing or empty image model list"
                      {:type :images-generation-failed})))
    (into []
          (keep (fn [m]
                  (when (map? m)
                    (let [architecture (:architecture m)
                          input (modalities->keywords (:input_modalities architecture))
                          output (modalities->keywords (:output_modalities architecture))]
                      (when (some #{:image} output)
                        (let [pricing (:pricing m)]
                          {:id (:id m)
                           :name (:name m)
                           :api :openrouter-images
                           :provider :openrouter
                           :base-url openrouter-base-url
                           :input (if (seq input) input [:text])
                           :output output
                           :cost {:input (clamp-cost (* 1000000 (or (parse-double (or (:prompt pricing) "0")) 0)))
                                  :output (clamp-cost (* 1000000 (or (parse-double (or (:completion pricing) "0")) 0)))
                                  :cache-read (clamp-cost (* 1000000 (or (parse-double (or (:input_cache_read pricing) "0")) 0)))
                                  :cache-write (clamp-cost (* 1000000 (or (parse-double (or (:input_cache_write pricing) "0")) 0)))}}))))))
          data)))

(defn- fetch-openrouter-image-models
  "GET the image-capable model list (pi fetchOpenRouterImageModels)."
  [strict]
  (let [response (http/get (str openrouter-base-url "/models?output_modalities=image")
                           {:throw? false :timeout 30000})]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info (str "OpenRouter API returned " (:status response))
                      {:type :images-generation-failed})))
    (parse-openrouter-image-models (json/parse-string (:body response) true) strict)))

(defn- write-catalog!
  "Deterministic EDN for the committed catalog via kmet.ai.edn-writer —
   the same canonical key order / normalized numbers / escaping as the
   provider catalogs (no hand-built string buffer). Sorted by id, one
   :generated-at timestamp. Content-sensitive: when only the timestamp
   would change, the file is left untouched (bytes + mtime) instead of
   stamping a timestamp-only diff."
  [models]
  (let [sorted-models (into (sorted-map) (map (fn [m] [(:id m) m]) models))
        existing (try (slurp catalog-path)
                      (catch Exception _ nil))
        old-generated (or (try (:generated-at (edn/read-string existing))
                               (catch Exception _ nil))
                          (str (java.time.Instant/now)))
        unchanged? (and existing
                        (= existing
                           (str (edn-w/render {:schema-version 1
                                               :generated-at old-generated
                                               :provider {:id :openrouter :name "OpenRouter"}
                                               :models sorted-models})
                                "\n")))]
    (if unchanged?
      (println (str catalog-path " already up to date — nothing written."))
      (let [blob {:schema-version 1
                  :generated-at (str (java.time.Instant/now))
                  :provider {:id :openrouter :name "OpenRouter"}
                  :models sorted-models}]
        (spit catalog-path (str (edn-w/render blob) "\n"))
        (println (str "Generated " catalog-path " (" (count models) " models)"))))))

(defn validate-committed!
  "Offline validation of the committed catalog (used by the offline test):
   parseable, provider block present, every model has the required fields
   with numeric cost rates, no duplicate ids. Returns a vector of error
   strings (empty when valid)."
  []
  (let [errors (atom [])
        data (try (edn/read-string (slurp catalog-path))
                  (catch Exception e
                    (swap! errors conj (str "unparseable: " (ex-message e)))
                    nil))]
    (when data
      (when-not (map? (:provider data))
        (swap! errors conj "missing provider block"))
      (let [models (:models data)]
        (when-not (map? models)
          (swap! errors conj "missing models map"))
        (doseq [[id m] models]
          (doseq [k [:id :name :api :provider :base-url :input :output :cost]]
            (when-not (contains? m k)
              (swap! errors conj (str id " missing " k))))
          (when (and (map? m) (contains? m :cost))
            (doseq [k [:input :output :cache-read :cache-write]]
              (when-not (number? (get-in m [:cost k]))
                (swap! errors conj (str id " cost " k " not a number"))))))
        (let [ids (keys models)]
          (when-not (= (count ids) (count (distinct ids)))
            (swap! errors conj "duplicate model ids")))))
    @errors))

(defn -main
  "Regenerate the committed image-model catalog. --strict fails on an empty
   result."
  [& args]
  (let [strict (boolean (some #{"--strict"} args))
        models (fetch-openrouter-image-models strict)]
    (when (and strict (empty? models))
      (throw (ex-info "OpenRouter API returned no usable image models"
                      {:type :images-generation-failed})))
    (write-catalog! models)))

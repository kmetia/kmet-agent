(ns kmet.app.maintenance
  "The flags that maintain the user's caches or print a table and exit — they
   are not a run.

   `generate-models!` writes the user-level caches a run reads, so it runs
   **before** the process world loads; `list-models` prints the table a run
   would choose from, so it runs **after** the load — extension providers are
   catalog entries only then (docs/architecture.md § Composition). Both entries
   answer the same flags with one implementation."
  (:require [clojure.string :as str]
            [kmet.ai.image-model-gen :as image-gen]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.model-gen :as model-gen]
            [kmet.ai.models :as models]
            [kmet.app.config :as cfg]))

(defn generate-models!
  "Fetch the current provider and image-model catalogs into the user-level
   caches (models.dev + live sources; `KMET_CODING_AGENT_DIR`-aware) and return
   the process exit code: 0 when both pipelines wrote, 1 otherwise. A pipeline
   that throws prints kmet1's `Error:` line and counts as a failure; nothing
   here loads the world or a registry."
  []
  (let [agent-dir (cfg/get-agent-dir)
        run (fn [generate target]
              (try (:ok (generate target))
                   (catch Exception e
                     (binding [*out* *err*]
                       (println "Error:" (ex-message e)))
                     false)))
        models-ok (run model-gen/generate-and-write!
                       (or models/*models-cache-dir*
                           (models/default-models-cache-dir agent-dir)))
        images-ok (run image-gen/generate-and-write!
                       (or image-models/*image-models-cache-dir*
                           (image-models/default-image-models-cache-dir agent-dir)))]
    (if (and models-ok images-ok) 0 1)))

(defn- format-token-count
  "pi formatTokenCount — 200000 → \"200K\", 1000000 → \"1M\", 1500000 →
   \"1.5M\", else the plain number."
  [n]
  (let [fmt (fn [div]
              (let [s (format "%.1f" (/ n div))]
                (if (str/ends-with? s ".0")
                  (subs s 0 (- (count s) 2))
                  s)))]
    (cond
      (>= n 1000000) (str (fmt 1000000.0) "M")
      (>= n 1000) (str (fmt 1000.0) "K")
      :else (str n))))

(defn- search-tokens
  "The whitespace/slash-separated tokens of SEARCH-PATTERN."
  [pattern]
  (->> (str/split (or pattern "") #"[\s/]+")
       (remove str/blank?)))

(defn- subsequence-match?
  "True when every character of PATTERN appears in TEXT in order. The caller
   lowercases both; `--list-models` only needs the match, because the fuzzy
   scorer's ranking is discarded by the provider/id sort below."
  [pattern text]
  (let [pl (count pattern)
        tl (count text)]
    (if (zero? pl)
      true
      (loop [pi 0 ti 0]
        (cond
          (>= pi pl) true
          (>= ti tl) false
          (= (nth pattern pi) (nth text ti)) (recur (inc pi) (inc ti))
          :else (recur pi (inc ti)))))))

(defn- model-search-match?
  "True when every token of SEARCH-PATTERN matches MODEL's provider + id."
  [search-pattern model]
  (let [text (str/lower-case (str (name (:provider model)) " " (:id model)))]
    (every? #(subsequence-match? (str/lower-case %) text)
            (search-tokens search-pattern))))

(defn list-models
  "Print the available models (provider | model | context | max-out | thinking
   | images), optionally search-filtered over provider + id (pi
   cli/list-models.ts). Call after the process world loads, so the table
   includes every provider the catalogs and extensions installed."
  [search-pattern]
  (when-let [err (models/get-model-config-error)]
    (binding [*out* *err*]
      (println "Warning: errors loading models.edn:" err)))
  (let [available (models/get-available)]
    (if (empty? available)
      (println "No models available. Use /login to configure a provider.")
      (let [filtered (filterv #(model-search-match? search-pattern %) available)]
        (if (empty? filtered)
          (println (str "No models matching \"" search-pattern "\""))
          (let [rows (mapv (fn [m]
                             {:provider (name (:provider m))
                              :model (:id m)
                              :context (format-token-count (or (:context-window m) 0))
                              :max-out (format-token-count (or (:max-tokens m) 0))
                              :thinking (if (:reasoning m) "yes" "no")
                              :images (if (some #{:image} (:input m)) "yes" "no")})
                           (sort-by (juxt :provider :id) filtered))
                headers {:provider "provider" :model "model" :context "context"
                         :max-out "max-out" :thinking "thinking" :images "images"}
                cols (keys headers)
                widths (into {}
                             (for [k cols]
                               [k (reduce max (count (get headers k))
                                          (map (comp count k) rows))]))
                line (fn [row]
                       (str/join "  " (for [k cols]
                                        (str (get row k)
                                             (apply str (repeat (max 0 (- (get widths k)
                                                                          (count (get row k))))
                                                                \space))))))]
            (println (line headers))
            (doseq [r rows] (println (line r)))))))))

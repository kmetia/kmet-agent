(ns kmet.libs.edn-writer
  "Deterministic EDN serialization for committed data files (the model-catalog
   generators): canonical key ordering, normalized number literals, and a
   stable width-limited layout — the same data always renders to the same
   bytes, which is what makes the sha256-per-file manifests meaningful across
   runs and machines.

   Why not pr-str / clojure.pprint: pr-str emits hash-map iteration order
   (unstable per JVM run) and pprint sorts keys alphabetically only while its
   wrapping can shift between Clojure versions — either would churn every
   committed file and invalidate its recorded hash without any data change."
  (:require [clojure.string :as str]))

(def ^:private width 80)

(def ^:private key-priority
  "Global canonical key order for the EDN output. Hash-map iteration order is
   not stable, so every map is rendered via sorted-entries; unknown keywords
   sort alphabetically after known ones, string keys last."
  (into {}
        (map-indexed (fn [i k] [k i]))
        [:schema-version :generated-at
         :id :name :env-vars :default-model
         :provider :api :base-url :reasoning :thinking-level-map :input :cost
         :tiers :input-tokens-above
         :context-window :max-tokens :headers :compat
         :output :cache-read :cache-write
         :supports-store :supports-developer-role :supports-reasoning-effort
         :requires-reasoning-content-on-assistant-messages :thinking-format
         :max-tokens-field :session-affinity-format
         :supports-long-cache-retention :supports-strict-mode
         :supports-openai-grammar-tools :supports-tool-search
         :supports-additional-tools :supports-explicit-prompt-cache-mode
         :zai-tool-stream :force-adaptive-thinking :allow-empty-signature
         :chat-template-args :chat-template-kwargs
         :send-session-affinity-headers :supports-cache-control-on-tools
         :supports-eager-tool-input-streaming
         :structure-hash :files]))

(defn- key-rank
  [k]
  (cond
    (keyword? k) (if (contains? key-priority k)
                   [0 (get key-priority k)]
                   [1 (name k)])
    :else [2 (str k)]))

(defn- sorted-entries
  [m]
  (sort-by (comp key-rank key) m))

(defn- num-str
  "Number → EDN literal: integers as longs, floats rounded to 6 decimals with
   trailing zeros stripped (pi roundCost → toFixed(6))."
  [v]
  (let [n (double v)]
    (if (== n (Math/floor n))
      (str (long n))
      (str/replace (str (.movePointLeft (BigDecimal. (Math/round (* n 1000000.0))) 6))
                   #"0+$" ""))))

(defn- key-str
  [k]
  (if (keyword? k) (str k) (pr-str k)))

(defn- spaces
  [n]
  (apply str (repeat n " ")))

(defn- flat
  "Single-line rendering (used for width checks and scalar/vector output)."
  [v]
  (cond
    (string? v) (pr-str v)
    (keyword? v) (str v)
    (symbol? v) (str v)
    (nil? v) "nil"
    (true? v) "true"
    (false? v) "false"
    (number? v) (num-str v)
    (vector? v) (str "[" (str/join " " (map flat v)) "]")
    (map? v) (str "{" (str/join " " (map (fn [[k v]] (str (key-str k) " " (flat v))) (sorted-entries v))) "}")
    :else (str v)))

(declare render-value)

(defn- render-map
  "Multi-line map: `{` + first entry inline, continuation entries aligned at
   col+1 (the committed catalog style)."
  [m col]
  (let [ecol (inc col)]
    (str "{" (str/join (for [[i [k v]] (map-indexed vector (sorted-entries m))]
                         (str (if (zero? i) "" (str "\n" (spaces ecol)))
                              (key-str k)
                              (render-value k v ecol))))
         "}")))

(defn- render-value
  [k v ecol]
  (cond
    (not (map? v)) (str " " (flat v))
    (<= (+ ecol (count (key-str k)) 1 (count (flat v))) width) (str " " (flat v))
    (every? #(not (map? %)) (vals v)) (str " " (render-map v (+ ecol (count (key-str k)) 1)))
    :else (str "\n" (spaces ecol) (render-map v ecol))))

(defn render
  "Serialize VALUE to an EDN string: maps wider than the layout width go
   multi-line, every map's entries are emitted in the canonical key order,
   floats are normalized to ≤6 decimals without trailing zeros. Pure —
   identical data renders to identical bytes on any run/machine."
  [v]
  (if (and (map? v) (> (count (flat v)) width))
    (render-map v 0)
    (flat v)))

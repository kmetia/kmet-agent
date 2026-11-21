(ns kmet.ui.tui.model-catalog
  "Shared model catalog/session helpers and selected-model details for the
   /model and /scoped-models overlays."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.app.session.runtime :as runtime]
            [kmet.tui.theme :as theme]))

(defn model-full-id
  "Full \"provider/id\" id of a Model record (pi: `${provider}/${id}`)."
  [model]
  (str (name (:provider model)) "/" (:id model)))

(defn- scoped-model-snapshot
  "Models matched against first (pi: session scoped models when set, else
   the available snapshot). Scoped entries that no longer resolve drop out."
  [engine]
  (let [scoped (runtime/scoped-models engine)]
    (if (seq scoped)
      (vec (keep (fn [id]
                   (let [slash (str/index-of id "/")]
                     (when slash
                       (models/get-model (keyword (subs id 0 slash))
                                         (subs id (inc slash))))))
                 scoped))
      (models/get-available))))

(defn scoped-or-available-models
  "pi: session scoped models when set, else the available snapshot (feeds
   cycling, /model, and the footer provider count)."
  [engine]
  (if (seq (runtime/scoped-models engine))
    (scoped-model-snapshot engine)
    (models/get-available)))

(defn update-available-provider-count!
  "Footer provider count from the scoped models when set, else the available
   snapshot (pi updateAvailableProviderCount)."
  [cs]
  (fdp/fdp-set-provider-count!
   (:footer-provider cs)
   (count (distinct
           (map :provider
                (scoped-or-available-models (:engine cs)))))))

(defn- fmt-usd-rate
  "$/M rate as a compact string (0.44 → \"$0.44\", 15 → \"$15\"); the
   per-million-tokens unit is implied by the context. nil when absent
   or zero."
  [value]
  (when (and (number? value) (pos? value))
    (let [s (-> (format "%.4g" (double value))
                (str/replace #"(\.\d*?)0+$" "$1")
                (str/replace #"\.$" ""))]
      (str "$" s))))

(defn- cost-line
  "The cost line for a (non-nil) model: the priced rates marked ↑/↓ and
   C↑/C↓, or \"Cost: free\" when it carries no priced rates (pi doesn't
   show cost in the selectors; kmet adds it opportunistically)."
  [model]
  (let [cost (:cost model)
        input-rate (fmt-usd-rate (:input cost))
        output-rate (fmt-usd-rate (:output cost))
        cache-read-rate (fmt-usd-rate (:cache-read cost))
        cache-write-rate (fmt-usd-rate (:cache-write cost))
        parts (cond-> []
                input-rate (conj (str "↑" input-rate))
                output-rate (conj (str "↓" output-rate))
                cache-read-rate (conj (str "C↑" cache-read-rate))
                cache-write-rate (conj (str "C↓" cache-write-rate)))]
    (if (seq parts)
      (str "  Cost: " (str/join " · " parts))
      "  Cost: free")))

(defn model-info-lines
  "The muted-styled info lines under the selectors' model rows for the
   selected model: the name line plus the cost line — or just
   \"Model unavailable\" for a nil model (an enabled id with no catalog
   entry in the scoped selector). Each string renders as one text row."
  [model]
  (let [th (theme/get-current-theme)]
    (if model
      [(theme/fg th :muted (str "  Model Name: " (:name model)))
       (theme/fg th :muted (cost-line model))]
      [(theme/fg th :muted "  Model unavailable")])))

(defn model-info-rows
  "MODEL-INFO-LINES as keyed text rows — the selectors' info block under the
   list."
  [model]
  (map-indexed (fn [i line]
                 [:text {:key [:info i] :padding-x 1 :padding-y 0 :text line}])
               (model-info-lines model)))

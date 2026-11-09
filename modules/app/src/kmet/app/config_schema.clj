(ns kmet.app.config-schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Resources
  "The `:resources` selection map: per-type entry lists, nil when a type key
   was cleared."
  [:map {:closed false}
   [:tools {:optional true} [:or :nil [:sequential :string]]]
   [:skills {:optional true} [:or :nil [:sequential :string]]]
   [:prompts {:optional true} [:or :nil [:sequential :string]]]])

(def Config
  [:map {:closed false}
   [:provider {:optional true} [:or :keyword :string :nil]]
   [:model {:optional true} [:or :string :nil]]
   [:theme {:optional true} [:or :string :nil]]
   [:loop-guard {:optional true}
    [:map {:closed false}
     [:enabled {:optional true} :boolean]
     [:threshold {:optional true} [:or :nil :int]]]]
   [:images {:optional true}
    [:map {:closed false}
     [:block-images {:optional true} :boolean]]]
   [:compactor {:optional true} [:enum :llm :vcc]]
   [:http-idle-timeout-ms {:optional true} [:or :nil :int]]
   [:http-total-timeout-ms {:optional true} [:or :nil :int]]
   [:resources {:optional true} Resources]])

(defn validate-config?
  [c]
  (m/validate Config c))

(defn explain-config
  [c]
  (me/humanize (m/explain Config c)))

(defn validate-resource-settings?
  "True when SETTINGS — a raw `:resources` map, or nil when the key is absent
   — is shape-valid."
  [settings]
  (or (nil? settings) (m/validate Resources settings)))

(defn explain-resource-settings
  "Human-readable problems for SETTINGS, a raw `:resources` map."
  [settings]
  (me/humanize (m/explain Resources settings)))
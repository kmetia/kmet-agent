(ns hello-ext
  "Sample single-file extension for tests."
  (:require [kmet.extension :as ext]))

;; Tag alias contributed by the extension (kmet.tui.alias): the var holds
;; the KEYWORD so the extension's own tree sites can write [chip …], and
;; registration goes through the api so unload removes it (a defalias would
;; register directly and survive the unload).
(def chip :hello-ext/chip)

(defn chip-view
  "The alias fn: (fn [attrs children] tree)."
  [{:keys [label]} _children]
  [:text {:padding-x 0 :padding-y 0} (or label "chip")])

(defn init [api]
  (ext/register-command! api
                         {:name "hello-ext"
                          :description "Test extension command"
                          :handler (fn [_cs args] (str "hello-ext:" args))})
  (ext/on-event api :session-start (fn [_ev] nil))
  (ext/register-flag! api "ext-hello" {:type :boolean :default false})
  (ext/register-alias! api chip chip-view)
  (ext/register-tool! api {:name "hello-ext-tool"
                           :description "test tool"
                           :params {:x {:type :string}}
                           :execute (fn [args] {:content (str "tool:" (:x args))})})
  (ext/ui-set-status api "hello-ext" "loaded"))

(defn shutdown [api]
  (ext/ui-set-status api "hello-ext" nil))

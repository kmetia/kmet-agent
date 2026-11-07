(ns kmet.ui.tui.tools-selector
  "The builtin `/tools` session selector.

   pi's tools example extension was a settings editor: it toggled the
   persistent tool lists and wrote them on Ctrl+S. kmet's builtin replaces it
   with the session-only selector: every row is a live model-facing tool, a
   toggle updates `set-active-tools!`, and nothing here writes settings. The
   rows start from the current effective selection; persistent selection
   belongs to `kmet.app.resources`."
  (:require [kmet.app.session.runtime :as runtime]
            [kmet.hikap.core :as engine]
            [kmet.libs.theme :as th]
            [kmet.tui.core :as tui]
            [kmet.tui.hikap :as h]
            [kmet.ui.tui.dock :as dock]))

(defn- tool-rows
  "SettingsList rows for TOOLS, marking each name in ENABLED. The table is
   the model-facing one: a sandbox-only contribution is not a selectable
   row."
  [tools enabled]
  (mapv (fn [{:keys [name description]}]
          {:id name
           :label name
           :value (if (contains? enabled name) "enabled" "disabled")
           :values ["enabled" "disabled"]
           :description description})
        (sort-by :name tools)))

(defn- apply-toggle!
  "Switch NAME to VALUE in ENABLED-ATOM and apply the resulting set as this
   session's tool selection. Session-only: no settings or resources write."
  [engine enabled-atom name value]
  (let [enabled (swap! enabled-atom (if (= "enabled" value) conj disj) name)]
    (runtime/set-active-tools! engine (vec (sort enabled)))
    nil))

(defn show-tools-selector
  "Open the builtin `/tools` selector over the engine's live tool table.
   Toggling takes effect immediately for this session; Esc closes without
   touching the resources selection."
  [cs]
  (let [engine (:engine cs)
        table (or (runtime/tool-table engine) {})
        enabled-atom (atom (or (runtime/enabled-tool-names engine) #{}))
        frame-atom (atom nil)
        close! (fn []
                 (dock/close-atom! cs frame-atom)
                 (tui/tui-request-render (:tui cs)))
        sl-ref (engine/ref)
        frame (h/compile-tree
               [:container {}
                [:text {:padding-x 1 :padding-y 0
                        :text (str (th/bold "Tools") "  "
                                   (th/dim "session selection · Enter/Space toggle · Esc close"))}]
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]
                [:settings-list {:ref sl-ref
                                 :items (tool-rows (vals table) @enabled-atom)
                                 :enable-search true
                                 :max-visible (min (+ (count table) 2) 15)
                                 ;; a model-facing tool description can run to
                                 ;; paragraphs; three lines keep the dialog
                                 ;; inside the terminal
                                 :max-description-lines 3
                                 :on-change (fn [name value]
                                              (apply-toggle! engine enabled-atom name value))
                                 :on-escape close!}]
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]])
        settings @sl-ref]
    (reset! frame-atom frame)
    (dock/mount! cs frame {:focus-target settings})))

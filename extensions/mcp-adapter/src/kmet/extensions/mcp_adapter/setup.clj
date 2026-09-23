(ns kmet.extensions.mcp-adapter.setup
  "The setup panel (pi: mcp-setup-panel.ts, adapted to kmet's component
   model): add a known server (presets), add a custom server
   (name/command-or-url/args/auth form), import host configs
   (Cursor/Claude/Codex/opencode/windsurf/vscode mcp.json — adopted into
   the project file), and scaffold the project config. Built directly on
   kmet.tui.* and mounted via ui-custom, like the McpPanel.

   A duck-typed component (no defcomponent — it delegates to kmet.tui
   components per screen) driven by a screen atom:
     :actions — SelectList of the four actions
     :presets — SelectList of known-server presets
     :imports — SelectList of discovered host configs (space toggles,
                Enter adopts)
     :form    — labeled Input rows; Enter advances/submits, Esc goes back
   CALLBACKS (from the entry, over the extension state):
     :presets          — [{:id :name :summary}]
     :discover-imports — (fn [] -> [{:kind :path :server-count}])
     :add-known        — (fn [preset] -> {:ok bool :message str})
     :add-server       — (fn [name entry] -> {:ok bool :message str})
     :adopt-imports    — (fn [kinds] -> {:ok bool :message str})
     :scaffold         — (fn [] -> {:ok bool :message str})
   DONE — (fn [result]) with {:cancelled bool}; KB — the ui-custom
   factory keybindings manager (unused for now, kept for signature
   parity with the McpPanel)."
  (:require [clojure.string :as str]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.select-list :as select-list]))

;; ─── Styling (same palette as the McpPanel) ───────────────────────────────

(def ^:private setup-theme
  {:border "2" :title "2" :selected "36" :accent "32" :hint "2" :error "31"})

(defn- fg [code s]
  (if (or (nil? code) (str/blank? code)) s (str (u/sgr code) s (u/sgr 0))))

(defn- italic [s] (str (u/sgr 3) s (u/sgr 23)))

(defn- border-line [inner-w title]
  (let [title-text (str " " (or title "MCP setup") " ")
        border-len (- inner-w (u/visible-width title-text))
        left-b (quot border-len 2)
        right-b (- border-len left-b)]
    (str (fg (:border setup-theme) (str "╭" (apply str (repeat left-b "─"))))
         (fg (:title setup-theme) title-text)
         (fg (:border setup-theme) (str (apply str (repeat right-b "─")) "╮")))))

(defn- empty-row [inner-w]
  (fg (:border setup-theme) (str "│" (apply str (repeat inner-w " ")) "│")))

(defn- content-row [line inner-w]
  (let [line (if (>= (u/visible-width line) inner-w)
               (u/truncate-to-width line inner-w)
               line)
        pad (- inner-w (u/visible-width line))]
    (str (fg (:border setup-theme) "│")
         line
         (apply str (repeat (max 0 pad) " "))
         (fg (:border setup-theme) "│"))))

(defn- bottom-line [inner-w hint]
  (str (fg (:border setup-theme) (str "╰" (apply str (repeat inner-w "─")) "╯"))
       (when hint (str "\n" (fg (:hint setup-theme) hint)))))

(defn- select-theme
  "SelectListTheme record — selected rows highlighted like the McpPanel
   rows, description dimmed."
  []
  (select-list/map->SelectListTheme
   {:selected-prefix (fn [s] (fg (:selected setup-theme) (str "› " s)))
    :selected-text (fn [s] (fg (:selected setup-theme) s))
    :description (fn [s] (fg (:hint setup-theme) s))
    :scroll-info (fn [s] (fg (:hint setup-theme) s))
    :no-match (fn [s] (fg (:error setup-theme) s))}))

;; ─── Screens ──────────────────────────────────────────────────────────────

(declare switch-screen!)

(defn- close-panel!
  [panel]
  (when-let [cb @(:close-fn panel)]
    (cb {:cancelled true})))

(defn- actions-items
  []
  [{:label "Add a known server" :value :presets
    :description "One-click presets (DeepWiki, Context7, Notion, GitHub, Chrome DevTools)"}
   {:label "Add a custom server" :value :form
    :description "Command or URL, auth mode, then test the connection"}
   {:label "Import host configs" :value :imports
    :description "Adopt servers from Cursor/Claude/Codex/opencode/windsurf/vscode mcp.json"}
   {:label "Scaffold project config" :value :scaffold
    :description "Create .kmet/mcp.edn when missing"}])

(defn- make-select
  [items on-select on-escape & [on-key]]
  (select-list/make-select-list
   items
   :height 8
   :theme (select-theme)
   :on-select on-select
   :on-escape on-escape
   :on-key on-key))

(defn- make-actions-screen
  [panel]
  (make-select
   (actions-items)
   (fn [item]
     (if (= :scaffold (:value item))
       (let [result ((:scaffold (:callbacks panel)))]
         (reset! (:notice-atom panel) {:message (:message result)
                                       :ok (:ok result)}))
       (switch-screen! panel (:value item))))
   (fn [] (close-panel! panel))))

(defn- make-presets-screen
  [panel]
  (make-select
   (mapv (fn [preset]
           {:label (:name preset) :value preset :description (:summary preset)})
         (:presets (:callbacks panel)))
   (fn [item]
     (let [result ((:add-known (:callbacks panel)) (:value item))]
       (reset! (:notice-atom panel) {:message (:message result)
                                     :ok (:ok result)})
       (switch-screen! panel :actions)))
   (fn [] (switch-screen! panel :actions))))

(defn- make-imports-screen
  [panel]
  (let [discoveries ((:discover-imports (:callbacks panel)))]
    (reset! (:imports-selected panel)
            (set (map :kind discoveries)))
    (make-select
     (mapv (fn [discovery]
             {:label (str (:kind discovery) " — " (:path discovery))
              :value discovery
              :description (str (:server-count discovery) " server"
                                (when (not= 1 (:server-count discovery)) "s"))})
           discoveries)
     ;; Enter: adopt the selected kinds (all pre-selected by default)
     (fn [_items]
       (let [result ((:adopt-imports (:callbacks panel))
                     (vec @(:imports-selected panel)))]
         (reset! (:notice-atom panel) {:message (:message result)
                                       :ok (:ok result)})
         (switch-screen! panel :actions)))
     (fn [] (switch-screen! panel :actions))
     ;; space toggles a kind in/out of the adoption set
     (fn [sl data]
       (when (keys/matches-key? data "space")
         (when-let [item (select-list/select-list-get-selected sl)]
           (swap! (:imports-selected panel)
                  (fn [s]
                    (if (contains? s (:kind (:value item)))
                      (disj s (:kind (:value item)))
                      (conj s (:kind (:value item))))))
           true))))))

(defn- make-form-screen
  "The custom-server form as a duck-typed component: labeled Input rows.
   Enter on a row stores the value and advances; Enter on the last row
   submits; Esc goes back one row / cancels. The auth field accepts
   none | bearer | oauth."
  [panel]
  (let [fields (atom [{:key :name :label "Server name" :value ""}
                      {:key :command :label "Command (stdio) or URL (http)" :value ""}
                      {:key :args :label "Args (space-separated, optional)" :value ""}
                      {:key :auth :label "Auth (none | bearer | oauth)" :value "none"}])
        inputs (mapv (fn [_] (input/make-input)) @fields)
        focus (atom 0)
        field-value (fn [k]
                      (get (first (filter #(= k (:key %)) @fields)) :value))
        submit (fn []
                 (let [name (str/trim (field-value :name))
                       command-or-url (str/trim (field-value :command))
                       args (str/trim (field-value :args))
                       auth (keyword (str/lower-case (str/trim (field-value :auth))))
                       url? (str/includes? command-or-url "://")
                       parts (str/split command-or-url #"\s+")
                       entry (cond-> {}
                               (and (seq command-or-url) url?)
                               (assoc :url command-or-url)
                               (and (seq command-or-url) (not url?))
                               (assoc :command (first parts)
                                      :args (vec (rest parts)))
                               (and (seq args) (not url?))
                               (assoc :args (vec (str/split args #"\s+")))
                               (contains? #{:bearer :oauth} auth)
                               (assoc :auth auth))]
                   (cond
                     (str/blank? name)
                     (reset! (:notice-atom panel)
                             {:message "Server name is required." :ok false})
                     (str/blank? command-or-url)
                     (reset! (:notice-atom panel)
                             {:message "A command or URL is required." :ok false})
                     :else
                     (let [result ((:add-server (:callbacks panel)) name entry)]
                       (reset! (:notice-atom panel) {:message (:message result)
                                                     :ok (:ok result)})
                       (when (:ok result)
                         (switch-screen! panel :actions))))))
        set-focus! (fn [i]
                     (protocols/set-focused! (nth inputs @focus) false)
                     (reset! focus i)
                     (protocols/set-focused! (nth inputs i) true))]
    (doseq [[i inp] (map-indexed vector inputs)]
      (input/input-set-on-submit!
       inp (fn [v]
             (swap! fields assoc-in [i :value] v)
             (if (< i (dec (count @fields)))
               (set-focus! (inc i))
               (submit))))
      (input/input-set-on-escape!
       inp (fn []
             (if (pos? @focus)
               (set-focus! (dec @focus))
               (switch-screen! panel :actions)))))
    (protocols/set-focused! (first inputs) true)
    {:kind :form
     :fields fields :inputs inputs :focus focus
     :render (fn [width]
               (let [inner-w (max 1 (- width 2))
                     render-row (fn [row]
                                  (content-row row inner-w))]
                 (vec (mapcat (fn [field]
                                [(render-row (fg (:hint setup-theme) (:label field)))
                                 (render-row (first (protocols/render
                                                     (nth inputs @focus) inner-w)))])
                              @fields))))
     :handle-input (fn [data]
                     (protocols/handle-input (nth inputs @focus) data))
     :invalidate (fn []
                   (doseq [inp inputs] (protocols/invalidate inp)))}))

(defn- switch-screen!
  [panel screen]
  (reset! (:screen-atom panel) screen)
  (reset! (:content-atom panel)
          (case screen
            :actions (make-actions-screen panel)
            :presets (make-presets-screen panel)
            :imports (make-imports-screen panel)
            :form (make-form-screen panel))))

(defn- render
  [this width]
  (let [inner-w (max 1 (- width 2))
        screen @(:screen-atom this)
        content @(:content-atom this)
        notice @(:notice-atom this)
        content-lines (protocols/render content (- width 2))
        hint (case screen
               :actions "Enter selects · Esc closes"
               :presets "Enter adds the server · Esc back"
               :imports "Space toggles · Enter adopts · Esc back"
               :form "Enter advances / submits · Esc goes back")]
    (vec (concat
          [(border-line inner-w "MCP setup")
           (empty-row inner-w)]
          content-lines
          [(empty-row inner-w)]
          (when notice
            [(content-row (fg (if (:ok notice) (:accent setup-theme)
                                  (:error setup-theme))
                              (:message notice))
                          inner-w)])
          [(bottom-line inner-w (str (italic "↑↓") " navigate · " hint))]))))

(defn- handle-input
  [this data]
  (let [content @(:content-atom this)
        kmgr (kb/get-global-keybindings)]
    (cond
      (kb/matches-key kmgr data "tui.select.cancel")
      (do (close-panel! this) nil)

      :else
      (protocols/handle-input content data))))

(defn- invalidate
  [this]
  (when-let [content @(:content-atom this)]
    (protocols/invalidate content)))

(defn- component
  "The duck-typed component surface (the entry mounts it via ui-custom)."
  [panel]
  (assoc panel
         :render (fn [width] (render panel width))
         :handle-input (fn [data] (handle-input panel data))
         :invalidate (fn [] (invalidate panel))))

(defn make-setup-panel
  "Create the setup panel component (duck-typed: :render/:handle-input/
   :invalidate). CALLBACKS per the ns docstring; DONE receives
   {:cancelled bool}; KB is the keybindings manager (unused, parity)."
  [callbacks done kb]
  (let [panel {:callbacks callbacks
               :done-fn done
               :keys-manager kb
               :screen-atom (atom :actions)
               :content-atom (atom nil)
               :notice-atom (atom nil)
               :imports-selected (atom #{})
               :close-fn (atom nil)}]
    (switch-screen! panel :actions)
    (component panel)))

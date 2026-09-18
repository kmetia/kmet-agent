(ns kmet.app.ui.auth-selector
  "Auth provider selector (pi: modes/interactive/components/oauth-selector.ts
   OAuthSelectorComponent): the /login and /logout provider picker — a
   visible search filter over \"name id auth-type method-name\", clamped
   (non-wrapping) arrow navigation, per-provider auth status indicators
   (\"✓ configured\" / \"✓ env: VAR\" / \"• unconfigured\") and muted
   [subscription]/[API key] type labels when both types are listed."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.libs.reakt :as r]
            [kmet.tui.hiccup :as h]
            [kmet.tui.components.input :as input]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(def ^:private max-visible 8)

(defn- format-auth-type
  "pi formatAuthSelectorProviderType — the human label for an entry's auth
   type."
  [auth-type]
  (if (= :oauth auth-type) "subscription" "API key"))

(defn- search-text
  "pi fuzzyFilter getText — `${name} ${id} ${authType} ${method?.name}`."
  [entry]
  (str (:name entry) " " (:id entry) " "
       (name (:auth-type entry)) " " (or (:method-name entry) "")))

(defn- filtered-entries
  "Entries matching the search string (pi filterProviders — no query means
   all entries in their given order)."
  [entries query]
  (if (str/blank? query)
    (vec entries)
    (fuzzy/fuzzy-filter entries query search-text)))

(defn- status-indicator
  "pi formatStatusIndicator — the trailing auth-status segment of a row:
   unconfigured (muted), a type mismatch warning, or ✓ configured / ✓ env:
   VARS / ✓ <source> (success)."
  [th {:keys [status auth-type]}]
  (cond
    (not status)
    (theme/fg th :muted " • unconfigured")

    (not= (:type status) auth-type)
    (str (theme/fg th :muted " • ")
         (theme/fg th :warning
                   (if (= :oauth (:type status))
                     "subscription configured" "API key configured")))

    (or (str/blank? (:source status))
        (#{"OAuth" "stored credential"} (:source status)))
    (theme/fg th :success " ✓ configured")

    (re-matches #"[A-Z][A-Z0-9_]*(?:, [A-Z][A-Z0-9_]*)*" (:source status))
    (theme/fg th :success (str " ✓ env: " (:source status)))

    :else
    (theme/fg th :success (str " ✓ " (:source status)))))

(defcomponent AuthSelector nil
              [root search-input state-atom mode
               on-select-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered (filtered-entries (:entries st) (:search st))
          n (count filtered)]
      (cond
        ;; Up/Down — clamp at the ends, no wrap (pi OAuthSelectorComponent)
        (or (kb/matches-key kmgr data "tui.select.up")
            (kb/matches-key kmgr data "tui.select.down"))
        (do (when (pos? n)
              (let [idx (min (:selected-idx st) (dec n))
                    next (if (kb/matches-key kmgr data "tui.select.up")
                           (max 0 (dec idx))
                           (min (dec n) (inc idx)))]
                (when-not (= idx next)
                  (swap! state-atom assoc :selected-idx next))))
            nil)

        ;; Enter — select the highlighted provider (pi tui.select.confirm;
        ;; the search input's onSubmit does the same)
        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when-let [entry (when (pos? n)
                               (nth filtered (min (:selected-idx st) (dec n))))]
              (when-let [cb @on-select-atom]
                (cb (:id entry) (:auth-type entry))))
            nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input (the visible filter, pi)
        :else
        (do (protocols/handle-input search-input data)
            (let [value (input/input-get-value search-input)]
              (when (not= value (:search st))
                ;; pi filterProviders: clamp the selection to the new
                ;; filtered count — it does not reset to the top
                (swap! state-atom assoc :search value))
              nil)))))

  ;; the root's reaction and the foreign search input are the selector's
  ;; lifecycle: the dock close paths unwind both
  (dispose [this]
    (protocols/dispose (:search-input this))
    (protocols/dispose (:root this))))

;; ─── List rendering (pi updateList) ────────────────────────────────────────

(defn- row-elements
  "Visible rows + scroll counter + the empty state (pi updateList): a
   viewport of MAX-VISIBLE rows centered on the selection, muted
   [subscription]/[API key] labels when both types are present, the status
   indicator, and the empty-state message. Keyed by [id auth-type] — a
   provider may be listed once per auth type."
  [th mode entries filtered selected show-types? start-idx end-idx]
  (let [n (count filtered)]
    (concat
     (map (fn [i]
            (let [entry (nth filtered i)
                  is-selected (= i selected)
                  name-text (if is-selected
                              (str (theme/fg th :accent "→ ")
                                   (theme/fg th :accent (:name entry)))
                              (str "  " (theme/fg th :text (:name entry))))
                  type-label (when show-types?
                               (theme/fg th :muted
                                         (str " [" (format-auth-type (:auth-type entry)) "]")))
                  status (status-indicator th entry)]
              [:truncated-text {:key [(:id entry) (:auth-type entry)]
                                :padding-x 1
                                :text (str name-text type-label status)}]))
          (range start-idx end-idx))
     (when (or (pos? start-idx) (< end-idx n))
       (list [:truncated-text {:key ::position :padding-x 1
                               :text (theme/fg th :muted
                                               (str "  (" (inc selected) "/" n ")"))}]))
     (when (zero? n)
       (list [:truncated-text
              {:key ::empty :padding-x 1
               :text (theme/fg th :muted
                               (str "  " (if (empty? entries)
                                           (if (= :login mode)
                                             "No providers available"
                                             "No providers logged in. Use /login first.")
                                           "No matching providers")))}])))))

(defn- auth-body
  "The selector tree as a reactive body (dsl.md): the header, the rows and
   the scroll/empty rows re-derive from the state atom; the search input
   splices foreign. BORDER-FN is created once per selector so the border's
   :color-fn keeps identity across passes."
  [state-atom search-input title mode border-fn]
  (fn [_props]
    (let [th (theme/get-current-theme)
          st (r/tracked-deref state-atom)
          entries (:entries st)
          filtered (filtered-entries entries (:search st))
          n (count filtered)
          selected (max 0 (min (:selected-idx st) (max 0 (dec n))))
          show-types? (< 1 (count (distinct (map :auth-type filtered))))
          start-idx (max 0 (min (- selected (quot max-visible 2))
                                (- n max-visible)))
          end-idx (min (+ start-idx max-visible) n)]
      [:container {}
       [:dynamic-border {:color-fn border-fn}]
       [:spacer {:lines 1}]
       [:text {:padding-x 1 :padding-y 0} (theme/fg th :accent (theme/bold title))]
       [:spacer {:lines 1}]
       search-input
       [:spacer {:lines 1}]
       (row-elements th mode entries filtered selected show-types? start-idx end-idx)
       [:spacer {:lines 1}]
       [:dynamic-border {:color-fn border-fn}]])))

(defn make-auth-selector
  "Create the auth provider selector (pi OAuthSelectorComponent).
   MODE — :login or :logout (title + empty-state wording); ENTRIES —
   {:id :name :auth-type :method-name? :status?} maps; ON-SELECT receives
   [provider-id auth-type]; ON-CANCEL fires on escape. SEARCH pre-fills the
   filter (pi initialSearchInput)."
  [mode entries on-select on-cancel & [search]]
  (let [entries (vec entries)
        search-input (input/make-input)
        title (if (= :login mode)
                "Select provider to configure:"
                "Select provider to logout:")
        ;; stable identity across passes — a fresh color-fn per body run
        ;; would decline the border patch and rebuild it every time
        border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
        st (atom {:entries entries
                  :search (or search "")
                  :selected-idx 0})
        sel (map->AuthSelector
             {:root nil
              :search-input search-input
              :state-atom st
              :mode mode
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    (when (seq search)
      (input/input-set-value! search-input search))
    ;; rows are a tracked root body — no refresh call, the state change alone
    ;; re-derives
    (assoc sel :root (h/root (auth-body st search-input title mode border-fn)))))

;; ─── IFocusable — forward to the search input (IME cursor positioning) ─────

(extend-type AuthSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)))

;; ─── Auth-method selector (pi ExtensionSelectorComponent) ─────────────────
;; The plain string-option list showLoginAuthTypeSelector renders: no
;; filtering, all options visible, clamped navigation, and the
;; "↑↓ navigate  enter select  escape cancel" hint line.

(defn- method-hint-str
  "pi ExtensionSelectorComponent hint row: rawKeyHint(↑↓, navigate) +
   keyHint(confirm, select) + keyHint(cancel, cancel), two spaces apart."
  []
  (let [th (theme/get-current-theme)
        hint (fn [k desc]
               (str (theme/fg th :dim k)
                    (theme/fg th :muted (str " " desc))))]
    (str (hint "↑↓" "navigate") "  "
         (hint (app-kb/key-text "tui.select.confirm") "select") "  "
         (hint (app-kb/key-text "tui.select.cancel") "cancel"))))

(defcomponent AuthMethodSelector nil
              [root options selected-idx-atom
               on-select-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          n (count options)]
      (cond
        ;; Up/k — clamp at the top (pi ExtensionSelectorComponent)
        (or (kb/matches-key kmgr data "tui.select.up") (= data "k"))
        (do (swap! selected-idx-atom #(max 0 (dec %)))
            nil)

        ;; Down/j — clamp at the bottom
        (or (kb/matches-key kmgr data "tui.select.down") (= data "j"))
        (do (when (pos? n)
              (swap! selected-idx-atom #(min (dec n) (inc %))))
            nil)

        ;; Enter — select (pi tui.select.confirm / "\n"; pi guards on the
        ;; selected option's truthiness — nth on an empty/stale index must
        ;; not throw here)
        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when (and (pos? n) (< @selected-idx-atom n))
              (when-let [cb @on-select-atom]
                (cb (nth options @selected-idx-atom))))
            nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        :else nil)))

  ;; the root's reaction is the selector's lifecycle: the dock close paths
  ;; unwind it
  (dispose [this]
    (protocols/dispose (:root this))))

(extend-type AuthMethodSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

(defn- auth-method-body
  "The method selector tree as a reactive body (dsl.md): option rows
   re-derive from the selection atom, the chrome is static. BORDER-FN is
   created once per selector so the border's :color-fn keeps identity
   across passes."
  [title options selected-idx-atom border-fn]
  (fn [_props]
    (let [th (theme/get-current-theme)
          idx (r/tracked-deref selected-idx-atom)]
      [:container {}
       [:dynamic-border {:color-fn border-fn}]
       [:spacer {:lines 1}]
       [:text {:padding-x 1 :padding-y 0} (theme/fg th :accent (theme/bold title))]
       [:spacer {:lines 1}]
       (map-indexed (fn [i option]
                      [:text {:key i :padding-x 1 :padding-y 0
                              :text (if (= i idx)
                                      (str (theme/fg th :accent "→ ")
                                           (theme/fg th :accent option))
                                      (str "  " (theme/fg th :text option)))}])
                    options)
       [:spacer {:lines 1}]
       [:text {:padding-x 1 :padding-y 0 :text (method-hint-str)}]
       [:spacer {:lines 1}]
       [:dynamic-border {:color-fn border-fn}]])))

(defn make-auth-method-selector
  "Create the auth-method selector (pi showLoginAuthTypeSelector's
   ExtensionSelectorComponent). TITLE — dialog title; OPTIONS — vector of
   label strings; ON-SELECT receives the chosen string; ON-CANCEL fires on
   escape."
  [title options on-select on-cancel]
  (let [options (vec options)
        ;; stable identity across passes — a fresh color-fn per body run
        ;; would decline the border patch and rebuild it every time
        border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
        sel (map->AuthMethodSelector
             {:root nil
              :options options
              :selected-idx-atom (atom 0)
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    ;; rows are a tracked root body — no refresh call, the selection atom
    ;; alone re-derives
    (assoc sel :root
           (h/root (auth-method-body title options (:selected-idx-atom sel) border-fn)))))

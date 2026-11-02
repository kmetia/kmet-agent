(ns kmet.ui.tui.auth-selector
  "Auth provider selector (pi: modes/interactive/components/oauth-selector.ts
   OAuthSelectorComponent): the /login and /logout provider picker — a
   visible search filter over \"name id auth-type method-name\", clamped
   (non-wrapping) arrow navigation, per-provider auth status indicators
   (\"✓ configured\" / \"✓ env: VAR\" / \"• unconfigured\") and muted
   [subscription]/[API key] type labels when both types are listed."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.ui.tui.subs :as s]
            [kmet.reakt.core :as r]
            [kmet.tui.components.filter-panel :as fp]
            [kmet.tui.hikap :as h]
            [kmet.libs.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]))

(def ^:private max-visible 8)

(defn format-auth-type
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

(defn make-auth-selector
  "Create the auth provider selector (pi OAuthSelectorComponent).
   MODE — :login or :logout (title + empty-state wording); ENTRIES —
   {:id :name :auth-type :method-name? :status?} maps; ON-SELECT receives
   [provider-id auth-type]; ON-CANCEL fires on escape. SEARCH pre-fills the
   filter (pi initialSearchInput)."
  [mode entries on-select on-cancel & [search]]
  (let [entries (vec entries)
        title (if (= :login mode)
                "Select provider to configure:"
                "Select provider to logout:")
        empty-text (if (empty? entries)
                     (if (= :login mode)
                       "No providers available"
                       "No providers logged in. Use /login first.")
                     "No matching providers")
        render-row-fn (fn [th st entry _ selected?]
                        (let [filtered (filtered-entries (:entries st) (:search st))
                              show-types? (< 1 (count (distinct (map :auth-type filtered))))
                              name-text (if selected?
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
        chrome-fn (fn [th _st]
                    {:header [[:text {:padding-x 1 :padding-y 0}
                               (theme/fg th :accent (theme/bold title))]]})
        render-empty (fn [th _st]
                       [:truncated-text {:key ::empty :padding-x 1
                                         :text (theme/fg th :muted
                                                         (str "  " empty-text))}])]
    (fp/make-panel (fn [st] (filtered-entries (:entries st) (:search st)))
                   render-row-fn
                   :chrome chrome-fn
                   :render-empty render-empty
                   :nav :clamp
                   :state {:entries entries
                           :mode mode
                           :selected-idx 0}
                   :max-visible max-visible
                   :search-text (or search "")
                   :search-resets-selection? false
                   :on-select (when on-select
                                (fn [entry] (on-select (:id entry) (:auth-type entry))))
                   :on-cancel on-cancel)))

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
        (do (fp/select-step! selected-idx-atom :clamp -1 n)
            nil)

        ;; Down/j — clamp at the bottom
        (or (kb/matches-key kmgr data "tui.select.down") (= data "j"))
        (do (fp/select-step! selected-idx-atom :clamp 1 n)
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
    (let [th (r/tracked-deref s/theme-sub)
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
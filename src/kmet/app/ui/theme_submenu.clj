(ns kmet.app.ui.theme-submenu
  "Theme submenu (pi: ThemeSubmenu in modes/interactive/components/
   settings-selector.ts): single-theme selection with an Automatic option,
   and the automatic mode's light/dark theme pickers. Values are pi's
   settings strings — a theme name, or the \"light-theme/dark-theme\"
   automatic setting."
  (:require [clojure.string :as str]
            [kmet.app.ui.settings-submenu :as submenu]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

;; ─── pi helpers (ThemeSubmenu file-private functions) ─────────────────────

(def ^:private automatic-value
  "pi: AUTOMATIC_THEME_VALUE — the single-mode entry that switches to the
   automatic light/dark mode. A name, not a real theme: it can never
   collide with a theme key."
  "/")

(defn- preferred-theme
  "pi: preferredTheme — PREFERRED when available, else FALLBACK when
   available, else the first available theme (or FALLBACK)."
  [available preferred fallback]
  (cond
    (and preferred (some #{preferred} available)) preferred
    (some #{fallback} available) fallback
    :else (or (first available) fallback)))

(defn- default-automatic-themes
  "pi: defaultAutomaticThemes — the light/dark pair for CURRENT-SETTING: a
   valid automatic setting passes through; a fixed theme seeds both sides."
  [current-setting available]
  (if-let [auto (theme/parse-auto-theme-setting current-setting)]
    auto
    (let [fixed (when (and (string? current-setting)
                           (not (str/includes? current-setting "/")))
                  current-setting)
          theme-name (preferred-theme available fixed "dark")]
      {:light-theme theme-name :dark-theme theme-name})))

(defn- active-automatic-theme
  "pi: getActiveAutomaticTheme — the automatic mode's theme for the current
   terminal appearance."
  [light-theme dark-theme terminal-theme]
  (if (= terminal-theme :light) light-theme dark-theme))

(defn- automatic-setting
  "pi: getAutomaticThemeSetting — the persisted \"light/dark\" string."
  [light-theme dark-theme]
  (str light-theme "/" dark-theme))

(defn- theme-setting
  "pi: getThemeSetting — the setting the current mode represents."
  [st]
  (if (= :automatic (:mode st))
    (automatic-setting (:light-theme st) (:dark-theme st))
    (:single-theme st)))

(defn- theme-items
  "pi: themeItems — themes with the current one check-marked."
  [available current]
  (mapv (fn [name]
          {:value name
           :label (str (if (= name current) "✓ " "  ") name)})
        available))

;; ─── Menu builders (ctx: state/callbacks shared by both modes) ────────────

(defn- preview! [ctx setting-or-name]
  (when-let [cb (:on-preview ctx)]
    (cb setting-or-name)))

(defn- commit!
  "Close the submenu, committing SETTING (pi: apply → onDone(setting))."
  [ctx setting]
  ((:done ctx) setting))

(defn- close!
  "Close the submenu without a change (pi: onDone())."
  [ctx]
  ((:done ctx)))

(defn- theme-select
  "pi: createThemeSelect — a themed SelectSubmenu whose picks preview live
   and close through SUB-DONE (the automatic menu's sub-submenu callback);
   Escape restores the current automatic setting's preview."
  [ctx title description current-value on-select sub-done]
  (submenu/make-select-submenu
   title description
   (theme-items (:available ctx) current-value)
   current-value
   :on-select on-select
   :on-cancel (fn []
                (preview! ctx (theme-setting @(:state ctx)))
                (sub-done))
   :on-selection-change (fn [value] (preview! ctx value))))

(declare automatic-menu)

(defn- single-menu
  "pi: showSingleMenu — Automatic + one row per theme."
  [ctx]
  (let [st (:state ctx)
        single-theme (:single-theme @st)]
    (submenu/make-select-submenu
     "Theme"
     "Select a theme, or choose Automatic to follow terminal appearance."
     (into [{:value automatic-value
             :label "  Automatic"
             :description "Use separate themes for light and dark terminal appearance"}]
           (theme-items (:available ctx) single-theme))
     single-theme
     :on-select (fn [value]
                  (if (= value automatic-value)
                    (do (swap! st assoc :mode :automatic)
                        (preview! ctx (theme-setting @st))
                        ((:switch! ctx) (automatic-menu ctx)))
                    (do (swap! st assoc :single-theme value)
                        (commit! ctx value))))
     :on-cancel (fn []
                  (preview! ctx (:original @st))
                  (close! ctx))
     :on-selection-change
     (fn [value]
       (preview! ctx (if (= value automatic-value)
                       (automatic-setting (:light-theme @st) (:dark-theme @st))
                       value))))))

(defn- automatic-menu
  "pi: showAutomaticMenu — the light/dark pickers, Apply, and the switch
   back to single mode."
  [ctx]
  (let [st (:state ctx)
        {:keys [light-theme dark-theme]} @st
        terminal-theme (:terminal-theme ctx)
        light-sub (fn [_current-value sub-done]
                    (theme-select
                     ctx
                     "Light Theme"
                     "Select the theme to use for light terminal appearance"
                     (:light-theme @st)
                     (fn [value]
                       (swap! st assoc :light-theme value)
                       (preview! ctx (theme-setting @st))
                       (sub-done value))
                     sub-done))
        dark-sub (fn [_current-value sub-done]
                   (theme-select
                    ctx
                    "Dark Theme"
                    "Select the theme to use for dark terminal appearance"
                    (:dark-theme @st)
                    (fn [value]
                      (swap! st assoc :dark-theme value)
                      (preview! ctx (theme-setting @st))
                      (sub-done value))
                    sub-done))
        items [{:id :light-theme
                :label "Light theme"
                :description "Theme to use in automatic mode when the terminal is light"
                :value light-theme
                :submenu light-sub}
               {:id :dark-theme
                :label "Dark theme"
                :description "Theme to use in automatic mode when the terminal is dark"
                :value dark-theme
                :submenu dark-sub}
               {:id :theme-apply
                :label "Apply"
                :description "Save and go back"
                :value "save and go back"
                :values ["save and go back"]}
               {:id :theme-single-mode
                :label "Change mode"
                :description "Switch to one theme for light and dark"
                :value "switch to single theme"
                :values ["switch to single theme"]}]
        th (theme/get-current-theme)
        sl-ref (h/ref)
        root (h/compile-tree
              [:container {}
               [:text {:padding-x 0 :padding-y 0}
                (theme/fg th :accent (theme/bold "Automatic Theme"))]
               [:spacer {:lines 1}]
               [:text {:padding-x 0 :padding-y 0}
                (theme/fg th :muted "Choose themes for terminal light and dark appearance.")]
               [:text {:padding-x 0 :padding-y 0}
                (theme/fg th :muted "Light/dark detection requires terminal support.")]
               [:spacer {:lines 1}]
               [:settings-list
                {:ref sl-ref
                 :items items
                 :max-visible (min (count items) 10)
                 :on-change
                 (fn [id _value]
                   (case id
                     :theme-apply
                     (commit! ctx (automatic-setting (:light-theme @st)
                                                     (:dark-theme @st)))
                     :theme-single-mode
                     (do (swap! st assoc
                                :mode :single
                                :single-theme (active-automatic-theme
                                               (:light-theme @st)
                                               (:dark-theme @st)
                                               terminal-theme))
                         (preview! ctx (:single-theme @st))
                         ((:switch! ctx) (single-menu ctx)))
                     nil))
                 :on-escape
                 (fn []
                   (preview! ctx (:original @st))
                   (close! ctx))}]])]
    (submenu/panel root sl-ref)))

;; ─── ThemeSubmenu component ────────────────────────────────────────────────

(defcomponent ThemeSubmenu nil [state-atom child-atom cache-atom]
  (render [_this width]
    (protocols/render (deref child-atom) width))

  (handle-input [_this data]
    (when-let [child (deref child-atom)]
      (protocols/handle-input child data)
      nil))

  (dispose [_this]
    (when-let [child (deref child-atom)]
      (protocols/dispose child))))

(defn make-theme-submenu
  "Create the theme submenu (pi: ThemeSubmenu). CURRENT-SETTING is the
   settings :theme value (a name or \"light/dark\"); TERMINAL-THEME the
   current :light/:dark appearance; AVAILABLE-THEMES the theme keys. DONE is
   the SettingsList submenu done callback: (done setting) commits the row,
   (done) closes without a change. ON-PREVIEW (fn [setting-or-name])
   live-previews one (pi: onThemePreview)."
  [current-setting terminal-theme available-themes done
   & {:keys [on-preview]}]
  (let [available (vec available-themes)
        st (let [auto (theme/parse-auto-theme-setting current-setting)
                 automatic (default-automatic-themes current-setting available)
                 fixed (when (and (string? current-setting)
                                  (not (str/includes? current-setting "/")))
                         current-setting)]
             (atom {:mode (if auto :automatic :single)
                    :light-theme (:light-theme automatic)
                    :dark-theme (:dark-theme automatic)
                    :single-theme (preferred-theme
                                   available
                                   (or fixed
                                       (when auto
                                         (active-automatic-theme
                                          (:light-theme automatic)
                                          (:dark-theme automatic)
                                          terminal-theme)))
                                   "dark")
                    :original current-setting}))
        ts (map->ThemeSubmenu {:state-atom st
                               :child-atom (atom nil)
                               :cache-atom (atom nil)})
        switch! (fn [child]
                  (let [old (deref (:child-atom ts))]
                    (reset! (:child-atom ts) child)
                    (when (and old (not (identical? old child)))
                      (protocols/dispose old))))
        ctx {:state st
             :terminal-theme terminal-theme
             :available available
             :done done
             :on-preview on-preview
             :switch! switch!}]
    (switch! (if (= :automatic (:mode @st))
               (automatic-menu ctx)
               (single-menu ctx)))
    ts))

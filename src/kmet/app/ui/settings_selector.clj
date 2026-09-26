(ns kmet.app.ui.settings-selector
  "Settings selector panel (pi: showSettingsSelector +
   settings-selector.ts) — pi's rows backed by kmet machinery: thinking
   level (the current model's available levels), hide-thinking, auto-compact,
   inline images (show-images / image-width, terminal-gated, plus the
   ungated block-images), steering/follow-up queue modes, HTTP idle timeout, cache-miss notices,
   tree filter mode, editor/output padding, autocomplete max items, hardware
   cursor, the retry block (settings.edn :retry — enabled / max-retries /
   base-delay-ms, applied live to the agent), the repeat-loop guard
   (settings.edn :loop-guard — enabled / threshold, plus the thinking
   repeat guard toggle), the HTTP transport row (:platform http-client vs
   :curl, applied live to kmet.libs.http), and a theme row."
  (:require [kmet.app.loop :as agent]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.model-selector :as model-selector]
            [kmet.app.ui.theme-submenu :as theme-submenu]
            [kmet.config :as cfg]
            [kmet.libs.http :as http]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.hiccup :as h]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]))

;; pi: HTTP_IDLE_TIMEOUT_CHOICES (http-dispatcher.ts)
(def ^:private http-idle-timeout-choices
  [{:label "30 sec" :ms 30000}
   {:label "1 min" :ms 60000}
   {:label "2 min" :ms 120000}
   {:label "5 min" :ms 300000}
   {:label "disabled" :ms 0}])

;; pi: timeoutMs ?? httpIdleTimeoutMs — the whole-request total deadline.
;; The default (nil) is "use idle" rendered as its own choice; explicit
;; values use the same set as the idle choices; 0 means disabled (uses idle
;; anyway via the fallback, so it renders the same as the default).
(def ^:private http-total-timeout-choices
  (conj http-idle-timeout-choices {:label "use idle" :ms nil}))

(defn- format-idle-timeout
  "pi: formatHttpIdleTimeoutMs — a known choice shows its label; anything
   else renders as \"N sec\"."
  [ms]
  (or (:label (some #(when (= ms (:ms %)) %) http-idle-timeout-choices))
      (str (quot ms 1000) " sec")))

(defn- format-total-timeout
  "pi: formatHttpIdleTimeoutMs — a known choice shows its label; nil renders
   the default 'use idle'; anything else renders as \"N sec\"."
  [ms]
  (if (nil? ms)
    "use idle"
    (format-idle-timeout ms)))

(defn- set-editor-setting!
  "Apply an editor live setting via APPLY! to the app editor and, when it
   is protocol-backed and different, to the currently mounted editor
   (pi applies to the active editor)."
  [cs apply!]
  (when-let [ed (:editor cs)]
    (apply! ed))
  (let [cur (some-> cs :current-editor-atom deref)]
    (when (and cur (not= cur (:editor cs))
               (satisfies? protocols/IEditorComponent cur))
      (apply! cur))))

(defn- bool-row
  "A true/false toggle row (pi: values [\"true\" \"false\"]).
   DESCRIPTION is the muted line pi shows under the selected row."
  ([id label v] (bool-row id label v nil))
  ([id label v description]
   (cond-> {:id id :label label
            :value (if v "true" "false")
            :values ["true" "false"]}
     description (assoc :description description))))

(defn- image-rows
  "Settings rows for inline images (pi: only shown when the terminal
   supports images): the show-images toggle and the image-width choices,
   with values from the live image-settings atom."
  []
  (when (:images (timg/get-capabilities))
    [{:id :show-images
      :label "Show images"
      :description "Render images inline in terminal"
      :value (if (:show-images @subs/image-settings-atom) "true" "false")
      :values ["true" "false"]}
     {:id :image-width-cells
      :label "Image width"
      :description "Preferred inline image width in terminal cells"
      :value (str (:image-width-cells @subs/image-settings-atom))
      :values ["60" "80" "120"]}]))

(defn show-settings
  "Settings selector (pi: showSettingsSelector) — rows for every setting
   with a kmet backend; each change applies live and persists to the global
   settings.edn (pi: SettingsManager setters)."
  [cs]
  (let [sel-atom (atom nil)
        ;; late binding: the escape callback disposes the frame, built below
        frame-atom (atom nil)
        ag @(:agent-state cs)
        model (models/get-model @(:provider ag) @(:model ag))
        levels (if model (shared/get-supported-thinking-levels model) [:off])
        current (or (some #{(keyword @(:thinking ag))} levels) (first levels))
        config (:config cs)
        retry-atom (atom (cfg/get-retry-settings-live config))
        ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard block
        lg-atom (atom (cfg/get-loop-guard-settings-live config))
        apply-lg! (fn []
                    (let [lg @lg-atom]
                      (swap! (:cfg ag) assoc :loop-guard-enabled (:enabled lg))
                      (swap! (:cfg ag) assoc :loop-guard-threshold (:threshold lg))))
        save-lg! (fn [path value]
                   (cfg/save-setting! path value)
                   (apply-lg!))

        ;; or-guard: an explicit nil in settings.edn must not reach quot
        idle-ms (or (cfg/get-setting-live config :http-idle-timeout-ms 300000)
                    300000)
        ;; live value: nil (absent) = use idle; explicit = override
        total-ms (cfg/get-setting-live config :http-total-timeout-ms nil)
        apply-retry! (fn []
                       (let [r @retry-atom]
                         (swap! (:cfg ag) assoc :max-retries (if (:enabled r) (:max-retries r) 0))
                         (swap! (:cfg ag) assoc :base-delay-ms (:base-delay-ms r))))
        save-retry! (fn [path value]
                      (cfg/save-setting! path value)
                      (apply-retry!))
        ;; image rows (pi: after autocompact, only with terminal support)
        img-rows (image-rows)
        ;; the clear-on-shrink row needs the live tui (tests build a cs
        ;; without one; pi: clearOnShrink after autocomplete-max-visible)
        clear-on-shrink-row (when (:tui cs)
                              (bool-row :clear-on-shrink "Clear on shrink"
                                        (tui/tui-get-clear-on-shrink (:tui cs))
                                        "Clear empty rows when content shrinks (may cause flicker)"))
        base-items (into []
                         (concat [(bool-row :auto-compact "Auto-compact" (:auto-compact @(:cfg ag))
                                            "Automatically compact context when it gets too large")]
                                 img-rows
                                 [(bool-row :block-images "Block images" (:block-images @(:cfg ag))
                                            "Prevent images from being sent to LLM providers")
                                  (bool-row :skill-commands "Skill commands"
                                            (cfg/get-enable-skill-commands config)
                                            "Register skills as /skill:name commands")
                                  {:id :steering-mode
                                   :label "Steering mode"
                                   :description "Enter while streaming queues steering messages. 'one-at-a-time': deliver one, wait for response. 'all': deliver all at once."
                                   :value (name (:steering-mode @(:cfg ag)))
                                   :values ["one-at-a-time" "all"]}
                                  {:id :follow-up-mode
                                   :label "Follow-up mode"
                                   :description "Ctrl+Enter queues follow-up messages until the agent stops. 'one-at-a-time': deliver one, wait for response. 'all': deliver all at once."
                                   :value (name (:follow-up-mode @(:cfg ag)))
                                   :values ["one-at-a-time" "all"]}
                                  {:id :http-idle-timeout
                                   :label "HTTP idle timeout"
                                   :description "Maximum idle gap while waiting for HTTP headers or body chunks. Disable for local models that pause longer than five minutes."
                                   :value (format-idle-timeout idle-ms)
                                   :values (mapv :label http-idle-timeout-choices)}
                                  {:id :http-total-timeout
                                   :label "HTTP total timeout"
                                   :description "Whole-request deadline; 'use idle' follows the idle timeout."
                                   :value (format-total-timeout total-ms)
                                   :values (mapv :label http-total-timeout-choices)}
                                  {:id :http-transport
                                   :label "HTTP transport"
                                   :description "Outbound HTTP transport: platform (http-client, curl fallback) or curl for every request"
                     ;; the runtime knob is the truth (applied at config
                     ;; load and on change) — not the settings file
                                   :value (name (http/get-transport))
                                   :values (mapv name http/transport-modes)}
                                  (bool-row :cache-miss-notices "Cache miss notices"
                                            (cfg/get-show-cache-miss-notices config)
                                            "Show transcript notices for cache costs and provider recovery diagnostics")
                                  {:id :tree-filter-mode
                                   :label "Tree filter mode"
                                   :description "Default filter when opening /tree"
                                   :value (name (cfg/get-tree-filter-mode config))
                                   :values ["default" "no-tools" "user-only" "labeled-only" "all"]}
                                  {:id :thinking
                                   :label "Thinking level"
                                   :description "Thinking level for the current model"
                                   :value current
                                   :values levels}
                                  {:id :hide-thinking
                                   :label "Hide thinking"
                                   :description "Hide thinking blocks in assistant responses"
                     ;; the live chat-history flag, not the startup config
                     ;; snapshot — Ctrl+T toggles it at runtime
                                   :value (if (chat-history/chat-history-get-thinking-hidden (:chat-history cs)) "on" "off")
                                   :values ["off" "on"]}
                                  {:id :tool-display-mode
                                   :label "Tool display"
                                   :description "How tool calls render: collapsed, expanded, or quiet"
                     ;; the live chat-history mode, not a startup snapshot
                     ;; — ctrl+o cycles it at runtime
                                   :value (name (or (when (:chat-history cs)
                                                      (chat-history/chat-history-get-tool-display-mode (:chat-history cs)))
                                                    :collapsed))
                                   :values ["collapsed" "expanded" "quiet"]}
                                  {:id :editor-padding
                                   :label "Editor padding"
                                   :description "Horizontal padding for input editor (0-3)"
                                   :value (cfg/get-editor-padding-x config)
                                   :values [0 1 2 3]}
                                  {:id :output-padding
                                   :label "Output padding"
                                   :description "Horizontal padding for user messages, assistant messages, and thinking"
                                   :value (cfg/get-output-pad config)
                                   :values [0 1]}
                                  {:id :autocomplete-max-visible
                                   :label "Autocomplete max items"
                                   :description "Max visible items in autocomplete dropdown (3-20)"
                                   :value (cfg/get-autocomplete-max-visible config)
                                   :values [3 5 7 10 15 20]}]
                                 (when clear-on-shrink-row
                                   [clear-on-shrink-row])
                                 [(bool-row :terminal-progress "Terminal progress"
                                            (cfg/get-show-terminal-progress config)
                                            "Show OSC 9;4 progress indicators in the terminal tab bar")
                                  {:id :auto-retry
                                   :label "Auto retry"
                                   :description "Retry failed provider calls automatically"
                                   :value (:enabled @retry-atom)
                                   :values [true false]}
                                  {:id :max-retries
                                   :label "Max retries"
                                   :description "Maximum retry attempts"
                                   :value (:max-retries @retry-atom)
                                   :values [0 1 2 3 5 8 10]}
                                  {:id :base-delay-ms
                                   :label "Base delay (ms)"
                                   :description "Initial retry delay (exponential backoff)"
                                   :value (:base-delay-ms @retry-atom)
                                   :values [500 1000 2000 4000 8000]}
                                  {:id :loop-guard-enabled
                                   :label "Repeat guard"
                                   :description "Abort repeated identical tool calls (repeat-loop guard)"
                                   :value (:loop-guard-enabled @(:cfg ag))
                                   :values [true false]}
                                  {:id :loop-guard-threshold
                                   :label "Repeat threshold"
                                   :description "Repeated calls before the guard fires"
                                   :value (:loop-guard-threshold @(:cfg ag))
                                   :values [2 3 4 5]}
                                  {:id :thinking-loop-guard-enabled
                                   :label "Thinking repeat guard"
                                   :description "Also abort repeated identical thinking"
                                   :value (:thinking-loop-guard-enabled @(:cfg ag))
                                   :values [true false]}]))
        ;; hardware-cursor row needs the live tui, theme row the theme
        ;; controller (tests build a minimal cs without them)
        items (cond-> (if (:tui cs)
                        (conj base-items
                              (bool-row :show-hardware-cursor "Show hardware cursor"
                                        (tui/tui-get-show-hardware-cursor (:tui cs))
                                        "Show the terminal cursor while still positioning it for IME support"))
                        base-items)
                (:theme-controller cs)
                (conj {:id :theme
                       :label "Theme"
                       :description "Color theme for the interface"
                       :value (theme-ctrl/get-theme-selection (:theme-controller cs))
                       ;; pi: the Theme row opens the ThemeSubmenu (single
                       ;; names + the Automatic light/dark mode)
                       :submenu (fn [current-value done]
                                  (theme-submenu/make-theme-submenu
                                   current-value
                                   (theme-ctrl/get-terminal-theme (:theme-controller cs))
                                   (sort (keys (th/get-all-themes)))
                                   done
                                   :on-preview #(theme-ctrl/preview (:theme-controller cs) %)))}))
        ;; pi: SettingsSelector's onChange — one branch per row id, each
        ;; applying live and persisting (hoisted so the [:settings-list]
        ;; element below stays readable)
        on-change (fn [id value]
                    (case id
                      :auto-compact
                      (let [on? (= value "true")]
                        (agent/set-auto-compact! ag on?)
                        (cfg/save-setting! [:auto-compact] on?)
                        (when-let [f (:footer-comp cs)]
                          (footer/footer-set-auto-compact! f on?)))
                      :show-images
                      (let [on? (= value "true")]
                        (swap! subs/image-settings-atom assoc :show-images on?)
                        (cfg/save-setting! [:terminal :show-images] on?))
                      :image-width-cells
                      (let [w (parse-long value)]
                        (swap! subs/image-settings-atom assoc :image-width-cells w)
                        (cfg/save-setting! [:terminal :image-width-cells] w))
                      :block-images
                      (let [blocked? (= value "true")]
                        (agent/set-block-images! ag blocked?)
                        (cfg/save-setting! [:images :block-images] blocked?))
                      :skill-commands
                      ;; the autocomplete provider reads the setting live when
                      ;; it opens (pi: setupAutocompleteProvider on change)
                      (cfg/set-enable-skill-commands! (= value "true"))
                      :clear-on-shrink
                      (let [on? (= value "true")]
                        (tui/tui-set-clear-on-shrink! (:tui cs) on?)
                        (cfg/set-clear-on-shrink! on?))
                      :terminal-progress
                      (cfg/set-show-terminal-progress! (= value "true"))
                      :steering-mode
                      (let [mode (keyword value)]
                        (swap! (:cfg ag) assoc :steering-mode mode)
                        (cfg/save-setting! [:steering-mode] mode))
                      :follow-up-mode
                      (let [mode (keyword value)]
                        (swap! (:cfg ag) assoc :follow-up-mode mode)
                        (cfg/save-setting! [:follow-up-mode] mode))
                      :http-idle-timeout
                      (let [ms (:ms (some #(when (= value (:label %)) %)
                                          http-idle-timeout-choices))]
                        (agent/set-http-idle-timeout-ms! ag ms)
                        (cfg/save-setting! [:http-idle-timeout-ms] ms))
                      :http-total-timeout
                      (let [ms (:ms (some #(when (= value (:label %)) %)
                                          http-total-timeout-choices))]
                        (agent/set-http-total-timeout-ms! ag ms)
                        (cfg/save-setting! [:http-total-timeout-ms] ms))
                      :http-transport
                      (let [mode (keyword value)]
                        (http/set-transport! mode)
                        (cfg/save-setting! [:http-transport] mode))
                           ;; read live at emit time — no runtime state needed
                      :cache-miss-notices
                      (cfg/save-setting! [:show-cache-miss-notices] (= value "true"))
                      :tree-filter-mode
                      (cfg/save-setting! [:tree-filter-mode] (keyword value))
                      :theme
                      ;; pi: onThemeChange — persist the setting (a name or
                      ;; the automatic "light/dark" string) and apply it;
                      ;; auto settings enable color-scheme sync
                      (do (theme-ctrl/set-theme-setting! (:theme-controller cs) value)
                          (cfg/save-setting! [:theme] value))
                      :thinking
                      (let [level (keyword value)]
                        (agent/set-thinking-level! ag level)
                        (cfg/save-setting! [:thinking] level)
                        (model-selector/sync-footer-model! cs))
                      :hide-thinking
                      (let [hidden? (= value "on")]
                        (chat-history/chat-history-set-thinking-hidden!
                         (:chat-history cs) hidden?)
                        (cfg/set-hide-thinking-block! hidden?))
                      :tool-display-mode
                      (let [mode (keyword value)]
                        (chat-history/chat-history-set-tool-display-mode!
                         (:chat-history cs) mode)
                        (cfg/set-tool-display-mode! mode)
                        (when-let [hdr (:header-comp cs)]
                          (expandable-text/expandable-text-set-expanded!
                           hdr (= :expanded mode)))
                        (when-let [lr (:loaded-resources-comp cs)]
                          (loaded-resources/loaded-resources-set-expanded!
                           lr (= :expanded mode)))
                        (when (:tui cs)
                          (tui/tui-request-render (:tui cs) true)))
                      :editor-padding
                      (do (set-editor-setting! cs #(protocols/editor-set-padding-x! % value))
                          (cfg/save-setting! [:editor-padding-x] value))
                      :output-padding
                      (do (chat-history/chat-history-set-output-pad! (:chat-history cs) value)
                          (cfg/save-setting! [:output-pad] value))
                      :autocomplete-max-visible
                      (do (set-editor-setting!
                           cs #(protocols/editor-set-autocomplete-max-visible! % value))
                          (cfg/save-setting! [:autocomplete-max-visible] value))
                      :show-hardware-cursor
                      (let [on? (= value "true")]
                        (tui/tui-set-show-hardware-cursor! (:tui cs) on?)
                        (cfg/set-show-hardware-cursor! on?))
                      :auto-retry
                      (do (swap! retry-atom assoc :enabled (boolean value))
                          (save-retry! [:retry :enabled] (boolean value)))
                      :max-retries
                      (do (swap! retry-atom assoc :max-retries value)
                          (save-retry! [:retry :max-retries] value))
                      :base-delay-ms
                      (do (swap! retry-atom assoc :base-delay-ms value)
                          (save-retry! [:retry :base-delay-ms] value))
                      :loop-guard-enabled
                      (do (swap! lg-atom assoc :enabled (boolean value))
                          (save-lg! [:loop-guard :enabled] (boolean value)))
                      :loop-guard-threshold
                      (do (swap! lg-atom assoc :threshold value)
                          (save-lg! [:loop-guard :threshold] value))
                      :thinking-loop-guard-enabled
                      (do (swap! (:cfg ag) assoc :thinking-loop-guard-enabled (boolean value))
                          (cfg/save-setting! [:thinking-loop-guard-enabled] (boolean value)))))
        on-escape (fn []
                    ;; pi: done() — restore the editor and unwind the panel:
                    ;; the tree owns the chrome AND the settings list, so one
                    ;; dispose-tree! releases both (the frame is late-bound —
                    ;; built below)
                    ((:done @sel-atom))
                    (when-let [frame @frame-atom]
                      (h/dispose-tree! frame))
                    (tui/tui-request-render (:tui cs)))
        ;; Frame the list like pi's SettingsSelectorComponent (DynamicBorder +
        ;; SettingsList + DynamicBorder); the list is the focus target (pi:
        ;; showSelector's focus) since the frame container is inert chrome.
        ;; The frame is a compiled hiccup tree (dsl.md): the border chrome
        ;; and the settings list are both DSL-owned, and the instance is read
        ;; back through the ref right after compile (tui.md §2.4).
        th (th/get-current-theme)
        sl-ref (h/ref)
        frame (h/compile-tree
               [:container {}
                [:dynamic-border {:color-fn #(th/fg th :accent %)}]
                [:settings-list {:ref sl-ref
                                 :items items
                                 :enable-search true
                                 :on-change on-change
                                 :on-escape on-escape}]
                [:dynamic-border {:color-fn #(th/fg th :accent %)}]])
        sl @sl-ref]
    (reset! frame-atom frame)
    ;; pi: showSelector — mount the framed panel, focus the list
    ;; (focus: the interactive child)
    (reset! sel-atom {:done (dock/mount! cs frame sl)})))

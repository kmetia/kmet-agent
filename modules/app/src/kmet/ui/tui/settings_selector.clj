(ns kmet.ui.tui.settings-selector
  "Settings selector panel (pi: showSettingsSelector +
   settings-selector.ts) — pi's rows backed by kmet machinery: thinking
   level (the current model's available levels), thinking display, auto-compact,
   the context compactor (:llm | :vcc),
   inline images (show-images / image-width, terminal-gated, plus the
   ungated block-images), steering/follow-up queue modes, HTTP idle/total timeouts, cache-miss notices,
   tree filter mode, editor/output padding, autocomplete max items, hardware
   cursor, the retry block (settings.edn :retry — enabled / max-retries /
   base-delay-ms, applied live to the agent), the repeat-loop guard
   (settings.edn :loop-guard — enabled / threshold, plus the thinking
   repeat guard toggle), the HTTP transport row (:platform http-client vs
   :curl, applied live to kmet.libs.http), and a theme row."
  (:require [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.ai.models :as models]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.footer :as footer]
            [kmet.ui.tui.loaded-resources :as loaded-resources]
            [kmet.ui.tui.subs :as subs]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.model-selector :as model-selector]
            [kmet.ui.tui.theme-submenu :as theme-submenu]
            [kmet.app.config :as cfg]
            [kmet.libs.http :as http]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.hikap.core :as engine]
            [kmet.tui.hikap :as h]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as th]))

;; pi: HTTP_IDLE_TIMEOUT_CHOICES (http-dispatcher.ts)
(def ^:private http-idle-timeout-choices
  [{:label "30 sec" :ms 30000}
   {:label "1 min" :ms 60000}
   {:label "2 min" :ms 120000}
   {:label "5 min" :ms 300000}
   {:label "disabled" :ms 0}])

;; The default (nil) is "use idle" rendered as its own choice; 0 is a
;; separate choice that disables the whole-request deadline.
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

(defn- enabled-value?
  "A boolean row's chosen value — pi's boolean rows carry the \"true\" /
   \"false\" strings."
  [value]
  (= value "true"))

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

(defn- display-row-handlers
  "Settings rows that touch only the display stack: images, transcript
   rendering, padding, and the hardware cursor."
  [{:keys [cs engine]}]
  {:show-images
   (fn [value]
     (let [on? (enabled-value? value)]
       (swap! subs/image-settings-atom assoc :show-images on?)
       (cfg/save-setting! [:terminal :show-images] on?)))

   :image-width-cells
   (fn [value]
     (let [w (parse-long value)]
       (swap! subs/image-settings-atom assoc :image-width-cells w)
       (cfg/save-setting! [:terminal :image-width-cells] w)))

   :block-images
   (fn [value]
     (let [blocked? (enabled-value? value)]
       (runtime/set-block-images! engine blocked?)
       (cfg/save-setting! [:images :block-images] blocked?)))

   :skill-commands
   ;; the autocomplete provider reads the setting live when it opens
   ;; (pi: setupAutocompleteProvider on change)
   (fn [value]
     (cfg/set-enable-skill-commands! (enabled-value? value)))

   :clear-on-shrink
   (fn [value]
     (let [on? (enabled-value? value)]
       (tui/tui-set-clear-on-shrink! (:tui cs) on?)
       (cfg/set-clear-on-shrink! on?)))

   :terminal-progress
   (fn [value]
     (cfg/set-show-terminal-progress! (enabled-value? value)))

   :thinking-mode
   (fn [value]
     (let [mode (keyword value)]
       (chat-history/chat-history-set-thinking-mode!
        (:chat-history cs) mode)
       (cfg/set-thinking-mode! mode)))

   :tool-display-mode
   (fn [value]
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
         (tui/tui-request-render (:tui cs) true))))

   :editor-padding
   (fn [value]
     (set-editor-setting! cs #(protocols/editor-set-padding-x! % value))
     (cfg/save-setting! [:editor-padding-x] value))

   :output-padding
   (fn [value]
     (chat-history/chat-history-set-output-pad! (:chat-history cs) value)
     (cfg/save-setting! [:output-pad] value))

   :autocomplete-max-visible
   (fn [value]
     (set-editor-setting!
      cs #(protocols/editor-set-autocomplete-max-visible! % value))
     (cfg/save-setting! [:autocomplete-max-visible] value))

   :show-hardware-cursor
   (fn [value]
     (let [on? (enabled-value? value)]
       (tui/tui-set-show-hardware-cursor! (:tui cs) on?)
       (cfg/set-show-hardware-cursor! on?)))

   ;; read live at emit time — no runtime state needed
   :cache-miss-notices
   (fn [value]
     (cfg/save-setting! [:show-cache-miss-notices] (enabled-value? value)))})

(defn- agent-row-handlers
  "Settings rows that change the agent runtime's live options."
  [{:keys [cs engine]}]
  {:auto-compact
   (fn [value]
     (let [on? (enabled-value? value)]
       (runtime/set-auto-compact! engine on?)
       (cfg/save-setting! [:auto-compact] on?)
       (when-let [f (:footer-comp cs)]
         (footer/footer-set-auto-compact! f on?))))

   :compactor
   (fn [value]
     (let [compactor (keyword value)]
       (runtime/set-compactor! engine compactor)
       (cfg/save-setting! [:compactor] compactor)))

   :steering-mode
   (fn [value]
     (let [mode (keyword value)]
       (runtime/set-steering-mode! engine mode)
       (cfg/save-setting! [:steering-mode] mode)))

   :follow-up-mode
   (fn [value]
     (let [mode (keyword value)]
       (runtime/set-follow-up-mode! engine mode)
       (cfg/save-setting! [:follow-up-mode] mode)))

   :tree-filter-mode
   (fn [value]
     (cfg/save-setting! [:tree-filter-mode] (keyword value)))

   :thinking
   (fn [value]
     (let [level (keyword value)]
       (runtime/set-thinking-level! engine level)
       (cfg/save-setting! [:thinking] level)
       (model-selector/sync-footer-model! cs)))

   :thinking-loop-guard-enabled
   (fn [value]
     (runtime/set-run-options! engine {:thinking-loop-guard-enabled (boolean value)})
     (cfg/save-setting! [:thinking-loop-guard-enabled] (boolean value)))})

(defn- http-row-handlers
  "The HTTP transport rows: the timeouts and the transport mode."
  [{:keys [engine]}]
  {:http-idle-timeout
   (fn [value]
     (let [ms (:ms (some #(when (= value (:label %)) %)
                         http-idle-timeout-choices))]
       (runtime/set-http-idle-timeout-ms! engine ms)
       (cfg/save-setting! [:http-idle-timeout-ms] ms)))

   :http-total-timeout
   (fn [value]
     (let [ms (:ms (some #(when (= value (:label %)) %)
                         http-total-timeout-choices))]
       (runtime/set-http-total-timeout-ms! engine ms)
       (cfg/save-setting! [:http-total-timeout-ms] ms)))

   :http-transport
   (fn [value]
     (let [mode (keyword value)]
       (http/set-transport! mode)
       (cfg/save-setting! [:http-transport] mode)))})

(defn- retry-row-handlers
  "The retry and repeat-loop-guard rows: the panel's volatiles hold the
   edited block, its saver applies and persists it."
  [{:keys [retry-atom save-retry! lg-atom save-lg!]}]
  {:auto-retry
   (fn [value]
     (swap! retry-atom assoc :enabled (boolean value))
     (save-retry! [:retry :enabled] (boolean value)))

   :max-retries
   (fn [value]
     (swap! retry-atom assoc :max-retries value)
     (save-retry! [:retry :max-retries] value))

   :base-delay-ms
   (fn [value]
     (swap! retry-atom assoc :base-delay-ms value)
     (save-retry! [:retry :base-delay-ms] value))

   :loop-guard-enabled
   (fn [value]
     (swap! lg-atom assoc :enabled (boolean value))
     (save-lg! [:loop-guard :enabled] (boolean value)))

   :loop-guard-threshold
   (fn [value]
     (swap! lg-atom assoc :threshold value)
     (save-lg! [:loop-guard :threshold] value))})

(defn- theme-row-handlers
  "pi: onThemeChange — persist the setting (a name or the automatic
   \"light/dark\" string) and apply it; auto settings enable color-scheme
   sync."
  [{:keys [cs]}]
  {:theme
   (fn [value]
     (theme-ctrl/set-theme-setting! (:theme-controller cs) value)
     (cfg/save-setting! [:theme] value))})

(defn- row-handlers
  "The settings panel's per-row change handlers (pi: SettingsSelector's
   onChange): one entry per row id, each applying the value live and
   persisting it. CTX carries the panel's runtime pieces — :engine, :cs, and
   the retry / repeat-guard volatiles with their savers — so a handler never
   reads the surrounding let. Built per panel, since the volatiles are."
  [ctx]
  (merge (display-row-handlers ctx)
         (agent-row-handlers ctx)
         (http-row-handlers ctx)
         (retry-row-handlers ctx)
         (theme-row-handlers ctx)))

(defn- apply-row-change!
  "Dispatch one settings row change to its handler. An unknown row id is a
   programming error — the panel's items and the handler table derive from
   the same row set — so it fails loudly instead of silently dropping the
   change (the same IllegalArgumentException a `case` raises)."
  [handlers id value]
  (if-let [handler (get handlers id)]
    (handler value)
    (throw (IllegalArgumentException.
            (str "no settings handler for row " id)))))

(defn show-settings
  "Settings selector (pi: showSettingsSelector) — rows for every setting
   with a kmet backend; each change applies live and persists to the global
   settings.edn (pi: SettingsManager setters)."
  [cs]
  (let [;; late binding: the escape callback disposes the frame, built below
        frame-atom (atom nil)
        engine (:engine cs)
        model (runtime/model-record engine)
        levels (if model (models/get-supported-thinking-levels model) [:off])
        current (or (some #{(keyword (runtime/thinking-level engine))} levels) (first levels))
        config (runtime/config engine)
        retry-atom (atom (cfg/get-retry-settings-live config))
        ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard block
        lg-atom (atom (cfg/get-loop-guard-settings-live config))
        apply-lg! (fn []
                    (let [lg @lg-atom]
                      (runtime/set-run-options! engine
                                                {:loop-guard-enabled (:enabled lg)
                                                 :loop-guard-threshold (:threshold lg)})))
        save-lg! (fn [path value]
                   (cfg/save-setting! path value)
                   (apply-lg!))

        ;; or-guard: an explicit nil in settings.edn must not reach quot
        idle-ms (or (cfg/get-setting-live config :http-idle-timeout-ms 120000) 120000)
        ;; live value: nil (absent) = use idle; explicit = override
        total-ms (cfg/get-setting-live config :http-total-timeout-ms 0)
        apply-retry! (fn []
                       (let [r @retry-atom]
                         (runtime/set-run-options!
                          engine
                          {:max-retries (if (:enabled r) (:max-retries r) 0)
                           :base-delay-ms (:base-delay-ms r)
                           :max-agent-delay-ms (:max-agent-delay-ms r)})))
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
                         (concat [(bool-row :auto-compact "Auto-compact" (:auto-compact (runtime/run-options engine))
                                            "Automatically compact context when it gets too large")
                                  {:id :compactor
                                   :label "Compactor"
                                   :description "Context compaction strategy: LLM summary or deterministic VCC"
                                   :value (name (or (:compactor (runtime/run-options engine)) :llm))
                                   :values ["llm" "vcc"]}]
                                 img-rows
                                 [(bool-row :block-images "Block images" (:block-images (runtime/run-options engine))
                                            "Prevent images from being sent to LLM providers")
                                  (bool-row :skill-commands "Skill commands"
                                            (cfg/get-enable-skill-commands config)
                                            "Register skills as /skill:name commands")
                                  {:id :steering-mode
                                   :label "Steering mode"
                                   :description "Enter while streaming queues steering messages. 'one-at-a-time': deliver one, wait for response. 'all': deliver all at once."
                                   :value (name (:steering-mode (runtime/run-options engine)))
                                   :values ["one-at-a-time" "all"]}
                                  {:id :follow-up-mode
                                   :label "Follow-up mode"
                                   :description "Ctrl+Enter queues follow-up messages until the agent stops. 'one-at-a-time': deliver one, wait for response. 'all': deliver all at once."
                                   :value (name (:follow-up-mode (runtime/run-options engine)))
                                   :values ["one-at-a-time" "all"]}
                                  {:id :http-idle-timeout
                                   :label "HTTP idle timeout"
                                   :description "Maximum idle gap while waiting for HTTP headers or body chunks. Disable for local models that pause longer than two minutes."
                                   :value (format-idle-timeout idle-ms)
                                   :values (mapv :label http-idle-timeout-choices)}
                                  {:id :http-total-timeout
                                   :label "HTTP total timeout"
                                   :description "Whole-request deadline; 'use idle' follows the idle timeout, while 'disabled' leaves only the idle timeout."
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
                                  {:id :thinking-mode
                                   :label "Thinking display"
                                   :description "Thinking blocks: visible, the live tail while thinking (label after), or hidden"
                     ;; the live chat-history mode, not the startup config
                     ;; snapshot — Ctrl+T cycles it at runtime
                                   :value (name (if-let [ch (:chat-history cs)]
                                                  (chat-history/chat-history-get-thinking-mode ch)
                                                  :visible))
                                   :values ["visible" "stream-last" "hidden"]}
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
                                   :value (:loop-guard-enabled (runtime/run-options engine))
                                   :values [true false]}
                                  {:id :loop-guard-threshold
                                   :label "Repeat threshold"
                                   :description "Repeated calls before the guard fires"
                                   :value (:loop-guard-threshold (runtime/run-options engine))
                                   :values [2 3 4 5]}
                                  {:id :thinking-loop-guard-enabled
                                   :label "Thinking repeat guard"
                                   :description "Also abort repeated identical thinking"
                                   :value (:thinking-loop-guard-enabled (runtime/run-options engine))
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
        ;; pi: SettingsSelector's onChange — the row table (one handler per
        ;; row id, each applying live and persisting) lives at module level;
        ;; an unknown id is a programming error, not a silent no-op.
        handlers (row-handlers {:engine engine
                                :cs cs
                                :retry-atom retry-atom
                                :save-retry! save-retry!
                                :lg-atom lg-atom
                                :save-lg! save-lg!})
        on-change (fn [id value] (apply-row-change! handlers id value))
        on-escape (fn []
                    ;; restore the editor and unwind the panel: the tree owns
                    ;; the chrome AND the settings list, so one dock/dispose!
                    ;; (leave then dispose) releases both (the frame is
                    ;; late-bound — built below)
                    (dock/close-atom! cs frame-atom)
                    (tui/tui-request-render (:tui cs)))
        ;; Frame the list like pi's SettingsSelectorComponent (DynamicBorder +
        ;; SettingsList + DynamicBorder); the list is the focus target (pi:
        ;; showSelector's focus) since the frame container is inert chrome.
        ;; The frame is a compiled hiccup tree (dsl.md): the border chrome
        ;; and the settings list are both DSL-owned, and the instance is read
        ;; back through the ref right after compile (tui.md §2.4).
        sl-ref (engine/ref)
        frame (h/compile-tree
               [:container {}
                ;; the border color is read at render time: the frame is
                ;; compiled once, and a theme switch while the panel is open
                ;; must restyle the chrome (tui.md §9)
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]
                [:settings-list {:ref sl-ref
                                 :items items
                                 :enable-search true
                                 :on-change on-change
                                 :on-escape on-escape}]
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]])
        sl @sl-ref]
    (reset! frame-atom frame)
    ;; pi: showSelector — mount the framed panel, focus the list
    ;; (focus: the interactive child)
    (dock/mount! cs frame {:focus-target sl})))
(ns kmet.modes.interactive.ui-registry
  "The ExtensionUIContext implementation for the interactive mode: widget
   areas, custom components, dialogs, and editor transfer (pi:
   ExtensionUIContext)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.debug :as debug]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.libs.context :as context]
            [kmet.libs.reakt :as r]
            [kmet.ai.models :as models]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions :as extensions]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-get-expanded
                                                 editor-text-set!]]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.model-selector :refer [sync-footer-model!]]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.modes.interactive.commands :as builtins]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]
            [kmet.modes.interactive.turn :as turn]))

;; ─── Extension UI registry (pi: ExtensionUIContext) ────────────────────────
;; build-layout installs this registry after the layout is live; extensions
;; call the kmet.app.extensions ui-* fns, which dispatch through it. All
;; closures capture the layout pieces they mutate.

(defn make-widget-area-above
  "The above-editor widget strip as a fn component (dsl.md stage 4, pi:
   renderWidgets): a leading spacer plus the registered widgets. The widget
   map is read tracked — a :set-widget swap re-derives exactly once; the
   spacer is a tree element reused via the equal-props fast-path, widgets
   splice as foreign records (owned by the extension flow)."
  [widgets-atom]
  (fn [_props]
    ;; SEQ of sibling roots — widgets must be SIBLINGS of the spacer, not
    ;; its children (:spacer is a leaf tag; children on a leaf throws)
    (concat [[:spacer {:lines 1}]]
            (vals (r/tracked-deref widgets-atom)))))

(defn make-widget-area-below
  "Below-editor widget area (pi: renderWidgets) — the registered widgets
   only, no leading spacer."
  [widgets-atom]
  (fn [_props]
    (vals (r/tracked-deref widgets-atom))))

(defn- dispose-dialog-component!
  "Dispose an extension dialog/widget value (kmet.app.ui.custom-dialog-adapter/
   dispose-component! — duck-typed :dispose, else the protocol multimethod)."
  [component]
  (cda/dispose-component! component))

(defn- make-extension-widget-component
  "Widget content forms (pi: renderWidgets' map values):
   - hiccup element tree → compiled once to a stamped component (spliceable
     into the widget strips; its dispose unwinds owned cleanups)
   - factory fn → (content t theme); a duck-typed render map result is
     adapted to a CustomDialogAdapter record so it splices into the strips'
     trees — component/record results pass through untouched"
  [t content]
  (if (fn? content)
    (let [c (content t (th/get-current-theme))]
      ;; a duck-typed map cannot splice into the widget strips' trees —
      ;; adapt it to a record; components/trees pass through untouched
      (if (and (map? c) (not (record? c)) (fn? (:render c)))
        (cda/map->CustomDialogAdapter
         {:render-fn (:render c)
          :handle-input-fn (:handle-input c)
          :invalidate-fn (:invalidate c)
          :dispose-fn (:dispose c)})
        c))
    (hiccup/compile-tree content)))

(defn- normalize-custom-component
  "Accept an IComponent, a plain render map {:render :handle-input
   :invalidate :dispose} (pi: custom() accepts both a Component and a
   duck-typed object), or a hiccup element tree — maps and trees are
   wrapped in a CustomDialogAdapter RECORD so the result always splices
   into hiccup trees by record? (reify wrappers would trip reconcile:
   bb's satisfies? misses reifies from other evaluation contexts even
   though dispatch works on them). Trees compile once here and the
   adapter's dispose unwinds them."
  [x]
  (cond
    ;; structural branches first: maps/trees are recognized reliably,
    ;; satisfies? only decides for foreign component objects (records
    ;; satisfy robustly; a hostile reify failing it fails LOUD at the
    ;; ui-custom call site instead of crashing a render pass)
    (and (map? x) (not (record? x)) (fn? (:render x)))
    (cda/map->CustomDialogAdapter
     {:render-fn (:render x)
      :handle-input-fn (:handle-input x)
      :invalidate-fn (:invalidate x)
      :dispose-fn (:dispose x)})
    (vector? x)
    (let [comp (hiccup/compile-tree x)]
      ;; static trees take no input; invalidate clears the compiled caches
      (cda/map->CustomDialogAdapter
       {:render-fn #(protocols/render comp %)
        :invalidate-fn #(protocols/invalidate comp)
        :dispose-fn #(hiccup/dispose-tree! comp)}))
    (satisfies? tui/IComponent x) x))

(defn- transfer-editor!
  "Copy the app editor's wiring onto a custom editor component (pi:
   setCustomEditorComponent). Components implementing IEditorComponent get
   the method-based transfer (pi: setText/setPaddingX/setAutocomplete…);
   others get pi's duck-typed property copy of the record fields, plus the
   CustomEditor action-handler/keybinding extras in both cases."
  [app-ed custom-ed keybindings]
  (if (satisfies? protocols/IEditorComponent custom-ed)
    (do (protocols/editor-set-on-submit! custom-ed @(:on-submit app-ed))
        (protocols/editor-set-on-change! custom-ed @(:on-change app-ed))
        (protocols/editor-set-padding-x! custom-ed @(:padding-x app-ed))
        (protocols/editor-set-autocomplete-max-visible!
         custom-ed @(:autocomplete-max-visible app-ed))
        (when-let [p @(:autocomplete-provider app-ed)]
          (protocols/editor-set-autocomplete-provider! custom-ed p)))
    (doseq [field [:on-submit :on-change :padding-x
                   :autocomplete-provider :terminal-rows-atom]]
      (when (contains? custom-ed field)
        (reset! (get custom-ed field) @(get app-ed field)))))
  ;; pi: appearance properties are assigned whenever the target has them,
  ;; regardless of protocol (borderColor, kmet's dynamic-height source)
  (doseq [field [:border-fn :terminal-rows-atom]]
    (when (contains? custom-ed field)
      (reset! (get custom-ed field) @(get app-ed field))))
  (when (contains? custom-ed :action-handlers)
    (doseq [[action-id f] @(:action-handlers app-ed)]
      (swap! (:action-handlers custom-ed) assoc action-id f)))
  (when (contains? custom-ed :keybindings)
    (reset! (:keybindings custom-ed) keybindings))
  nil)

(defn- normalize-autocomplete-provider
  "Accept either an AutocompleteProvider or a duck-typed map with
   :get-suggestions (fn [state]) and optional :apply-completion,
   :should-trigger-file-completion, :get-trigger-characters (pi-style
   object). Returns a provider or nil for anything else."
  [x]
  (cond
    (satisfies? ac/AutocompleteProvider x) x
    (map? x) (reify ac/AutocompleteProvider
               (get-suggestions [_ lines cursor-line cursor-col opts]
                 (when-let [f (:get-suggestions x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :opts opts})))
               (apply-completion [_ lines cursor-line cursor-col item prefix]
                 (if-let [f (:apply-completion x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :item item :prefix prefix})
                   ;; default: replace the prefix with the item value
                   (let [line (nth lines cursor-line "")
                         start (max 0 (- cursor-col (count prefix)))
                         new-line (str (subs line 0 start) (:value item)
                                       (subs line cursor-col))]
                     {:lines (assoc lines cursor-line new-line)
                      :cursor-line cursor-line
                      :cursor-col (+ start (count (:value item)))})))
               (should-trigger-file-completion [_ lines cursor-line cursor-col]
                 (boolean (and (:should-trigger-file-completion x)
                               ((:should-trigger-file-completion x)
                                {:lines lines :cursor-line cursor-line
                                 :cursor-col cursor-col}))))
               (get-trigger-characters [_]
                 (vec (:get-trigger-characters x []))))
    :else nil))

(defn build-extension-ui-registry
  "Create the ExtensionUIContext implementation for the live layout
   (pi: createExtensionUIContext). Returns the capability map installed via
   extensions/set-ui-registry!."
  [{:keys [tui cs]}
   {:keys [ed ftr hdr ch sp1 fdp header-container
           widgets-above-atom widgets-below-atom]}
   theme-controller]
  (let [t tui
        custom-footer-atom (atom nil)
        custom-header-atom (atom nil)
        custom-dialog-comp (atom nil)
        ;; the ACTIVE editor — the default or a swapped-in custom editor
        ;; (pi: this.editor is rebound by setCustomEditorComponent); the atom
        ;; lives on CoreState so action handlers outside this closure (e.g.
        ;; the external-editor flow) see the active editor too
        current-editor-atom (:current-editor-atom cs)
        editor-factory-atom (atom nil)
        extension-autocomplete-factories (atom [])
        terminal-input-unsubscribers (atom [])
        hide-dialog (fn []
                      ;; the dialog's own close disposes it (its custody) —
                      ;; clear! only unwinds what a selector mode-switch left.
                      ;; Focus needs no restore here: the dock's
                      ;; ::focus-guard watch hands input back to the active
                      ;; editor the moment the occupant leaves, and the
                      ;; tracked dock atom re-derives the area
                      (dock/clear! cs)
                      (tui/tui-request-render t))
        rebuild-autocomplete-provider! (fn []
                                         ;; pi: setupAutocompleteProvider — each
                                         ;; extension factory wraps the provider
                                         ;; chain; nil results keep the base
                                         (let [base (ac/make-combined-provider
                                                     :commands-fn #(vec (concat
                                                                         (commands/get-commands)
                                                                         (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                         ;; pi: enableSkillCommands — read live at
                                                                         ;; autocomplete open
                                                                         (when (cfg/get-enable-skill-commands (:config cs))
                                                                           (skills/as-command-maps (skills/get-skills)))))
                                                     :base-path #(fdp/fdp-get-cwd fdp))
                                               provider (reduce (fn [prov factory]
                                                                  (or (normalize-autocomplete-provider
                                                                       (factory prov))
                                                                      prov))
                                                                base
                                                                @extension-autocomplete-factories)]
                                           (when (contains? @current-editor-atom
                                                            :autocomplete-provider)
                                             (editor/editor-set-autocomplete-provider!
                                              @current-editor-atom provider))
                                           nil))
        footer-data {:get-git-branch (fn [] (fdp/fdp-get-git-branch fdp))
                     :get-extension-statuses (fn []
                                               @(:extension-statuses-atom ftr))
                     :on-branch-change (fn [_f] (fn []))}
        registry
        {:notify (fn [message _type]
                   (tui/tui-flash! t message)
                   nil)
         ;; /session-style output for extension commands: append an :info
         ;; message to the chat history — part of the live transcript, no
         ;; overlay and nothing to dismiss (never sent to the LLM, never
         ;; persisted to the session; pi has no equivalent — kmet-specific).
         :chat-info (fn [label content]
                      (chat-history/chat-history-add-message!
                       ch {:role :info :label label :content (str content)})
                      (tui/tui-request-render t)
                      nil)
         ;; pi: ctx.ui.custom — mount an extension-built component (built
         ;; with kmet.tui.*) as an overlay or in the editor dock; the
         ;; factory gets (tui theme keybindings close) and close resolves
         ;; the returned promise
         :custom (fn [factory {:keys [overlay overlay-options on-handle]}]
                   (let [p (promise)
                         saved-text (editor-text-get @current-editor-atom)
                         closed (atom false)
                         close (fn [result]
                                 (when-not @closed
                                   (reset! closed true)
                                   ;; pi: dispose?() runs when the dialog
                                   ;; closes, before the component is
                                   ;; removed — a map/record :dispose fn
                                   (when-let [component @custom-dialog-comp]
                                     (dispose-dialog-component! component))
                                   (reset! custom-dialog-comp nil)
                                   (if overlay
                                     (tui/tui-hide-overlay t)
                                     (do (hide-dialog)
                                         (editor-text-set!
                                          @current-editor-atom saved-text)))
                                   (deliver p result)))]
                     (try
                       ;; pi: custom() accepts a Promise<Component> — deref
                       ;; with a timeout; a timeout or nil factory result
                       ;; hits the same error path as a throwing factory
                       (let [raw (factory t (th/get-current-theme) (tui-kb/get-global-keybindings) close)
                             raw (if (instance? clojure.lang.IDeref raw)
                                   (deref raw 5000 ::timeout)
                                   raw)
                             component (normalize-custom-component raw)]
                         (when (or (nil? component) (= ::timeout raw))
                           (when-not @closed
                             (throw (ex-info "ui-custom factory returned no component (or timed out)" {}))))
                         (when-not @closed
                           ;; a previous live dialog (defensive — normal flow
                           ;; closes first) unwinds exactly like widget replace
                           (when-let [prev @custom-dialog-comp]
                             (dispose-dialog-component! prev))
                           (reset! custom-dialog-comp component)
                           (if overlay
                             (let [opts (if (fn? overlay-options)
                                          (overlay-options)
                                          overlay-options)
                                   handle (tui/tui-show-overlay t component opts)]
                               (when on-handle (on-handle handle)))
                             (dock/mount! cs component nil {:borrowed? true}))))
                       (catch Exception e
                         (when-not @closed
                           (reset! closed true)
                           (when-not overlay (hide-dialog))
                           (tui/tui-flash! t (str "Extension UI error: " (ex-message e)))
                           (deliver p nil))))
                     p))
         ;; footer-set-extension-status! swaps a track!-watched atom —
         ;; the watch schedules the frame (§3.4), no manual poke
         :set-status (fn [key text]
                       (footer/footer-set-extension-status! ftr key text))
         :set-widget (fn [key content options]
                       (let [placement (or (:placement options) :above-editor)
                             m (if (= :below-editor placement) widgets-below-atom widgets-above-atom)
                             existing (get @m key)]
                         ;; pi: removeExisting disposes the old widget on
                         ;; replace AND remove — skipping :remove would leak
                         ;; its cleanups. Duck-typed maps carry :dispose;
                         ;; compiled trees are IComponents.
                         (when existing
                           (dispose-dialog-component! existing))
                         (swap! m dissoc key)
                         (when content
                           (swap! m assoc key
                                  (make-extension-widget-component t content)))
                         ;; the area roots track the widget maps — the swap
                         ;; alone re-derives them and schedules the frame
                         ;; (§3.4); pre-first-frame registrations land in the
                         ;; roots' first render anyway
                         nil))
         :set-footer (fn [factory]
                       (when-let [cf @custom-footer-atom]
                         (when-let [dispose (:dispose cf)]
                           (try (dispose) (catch Exception _)))
                         (tui/tui-remove-child t cf))
                       (tui/tui-remove-child t ftr)
                       (if factory
                         (let [cf (factory t (th/get-current-theme) footer-data)]
                           (reset! custom-footer-atom cf)
                           (tui/tui-add-child t cf))
                         (do (reset! custom-footer-atom nil)
                             (tui/tui-add-child t ftr)))
                       (tui/tui-request-render t))
         :set-header (fn [factory]
                       (when @custom-header-atom
                         (when-let [dispose (:dispose @custom-header-atom)]
                           (try (dispose) (catch Exception _)))
                         (reset! custom-header-atom nil))
                       (let [child (if factory (factory t (th/get-current-theme)) hdr)]
                         (container/container-clear header-container)
                         (container/container-add-child header-container sp1)
                         (container/container-add-child header-container child)
                         (container/container-add-child header-container sp1)
                         (when-not factory
                           (expandable-text/expandable-text-rebuild! hdr))
                         (tui/tui-request-render t)))
         :set-title (fn [title]
                      (when-let [term @(:terminal t)] (term/set-title! term title)))
         :on-terminal-input (fn [handler]
                              (tui/tui-add-input-listener t handler)
                              (let [unsub (fn [] (tui/tui-remove-input-listener t handler))]
                                (swap! terminal-input-unsubscribers conj unsub)
                                unsub))
         :set-editor-text (fn [text]
                            (editor-text-set! @current-editor-atom text)
                            (tui/tui-request-render t))
         :get-editor-text (fn [] (editor-text-get-expanded @current-editor-atom))
         :paste-to-editor (fn [text]
                            (tui/handle-input @current-editor-atom
                                              (str "\u001b[200~" text "\u001b[201~")))
         :set-working-indicator (fn [options]
                                  (spinner/spinner-set-indicator!
                                   (:spinner (:status-indicator cs)) options)
                                  (tui/tui-request-render t))
         :set-working-message (fn [message]
                                (status-indicator/status-indicator-set-text! (:status-indicator cs)
                                                                             (or message "Working"))
                                (tui/tui-request-render t))
         :set-working-visible (fn [visible?]
                                ;; Pi: setWorkingVisible — clearStatusIndicator("working")
                                ;; when hiding (kind-gated: a transient retry/
                                ;; compaction/share indicator stays), re-show the
                                ;; working indicator when showing (only while the
                                ;; turn runs, and only when it isn't already the
                                ;; active status — pi checks the active kind, so
                                ;; re-showing never restarts a spinning clock).
                                ;; both branches schedule through the
                                ;; guarded :status-current swap when real
                                (if visible?
                                  (when (and @(:running-turn? cs)
                                             (not (status-indicator/status-indicator-active?
                                                   (:status-indicator cs))))
                                    (status/activate-working-indicator! cs))
                                  (status/clear-status-indicator! cs :working)))
         :set-hidden-thinking-label (fn [label]
                                      ;; one reset! on the shared label atom;
                                      ;; assistant messages' watches schedule
                                      (chat-history/chat-history-set-hidden-thinking-label!
                                       ch label))
         :set-editor-component (fn [factory]
                                 (let [current-text (editor-text-get @current-editor-atom)]
                                   ;; pi parity: setCustomEditorComponent runs
                                   ;; disposeActiveSelector() then clears the dock —
                                   ;; the swap disposes whatever it held, and a
                                   ;; displaced selector's done() goes inert
                                   (dock/clear! cs)
                                   (if factory
                                     (let [new-ed (factory t (th/get-current-theme) (tui-kb/get-global-keybindings))]
                                       (transfer-editor! ed new-ed (tui-kb/get-global-keybindings))
                                       (editor-text-set! new-ed current-text)
                                       (tui/tui-set-focus t new-ed)
                                       ;; tracked by the dock area: the swap alone re-derives
                                       (reset! current-editor-atom new-ed))
                                     (do (editor-text-set! ed current-text)
                                         (tui/tui-set-focus t ed)
                                         (reset! current-editor-atom ed)))
                                   (reset! editor-factory-atom factory)
                                   (tui/tui-request-render t)))
         :add-autocomplete-provider (fn [factory]
                                      (swap! extension-autocomplete-factories conj factory)
                                      (rebuild-autocomplete-provider!))
         :set-theme (fn [theme-or-name]
                      ;; pi: setTheme — Theme instances go through
                      ;; setThemeInstance; names through setThemeName (which
                      ;; disables auto-sync). kmet has no settings write
                      ;; path — the switch is applied live only.
                      (if (instance? kmet.tui.theme.Theme theme-or-name)
                        (theme-ctrl/set-theme-instance! theme-controller theme-or-name)
                        (theme-ctrl/set-theme-name! theme-controller theme-or-name true)))
         :get-tools-expanded (fn [] (chat-history/chat-history-get-tool-expanded ch))
         :set-tools-expanded (fn [expanded?]
                               ;; the flag swap invalidates every tool
                               ;; component's watch, which schedules the frame
                               (let [current? (chat-history/chat-history-get-tool-expanded ch)]
                                 (when (not= current? expanded?)
                                   (chat-history/chat-history-toggle-tool-expanded! ch)
                                   (turn/request-global-reflow-render! cs))))
         :get-tool-display-mode (fn [] (chat-history/chat-history-get-tool-display-mode ch))
         :set-tool-display-mode (fn [mode]
                                  (when (not= mode (chat-history/chat-history-get-tool-display-mode ch))
                                    (chat-history/chat-history-set-tool-display-mode! ch mode)
                                    (try (cfg/set-tool-display-mode! mode)
                                         (catch Exception e
                                           (debug/log "Failed to persist tool-display-mode: " e)))
                                    (expandable-text/expandable-text-set-expanded!
                                     (:header-comp cs) (= :expanded mode))
                                    (loaded-resources/loaded-resources-set-expanded!
                                     (:loaded-resources-comp cs) (= :expanded mode))
                                    (turn/request-global-reflow-render! cs)))
         ;; pi: registerShortcut — a raw key-id bound as a priority editor
         ;; action, checked before every builtin app binding (escape
         ;; included). The keybinding definition is registered on the global
         ;; manager so key-hints and user overrides resolve. Last
         ;; registration of a key wins; deregistration removes only its own.
         :register-shortcut! (fn [key-id {:keys [description handler]}]
                               (let [kmgr (tui-kb/get-global-keybindings)
                                     key-id (str/lower-case (str key-id))]
                                 (tui-kb/register-definition!
                                  kmgr key-id
                                  {:default-keys [key-id]
                                   :description (or description "Extension shortcut")})
                                 (editor/editor-set-priority-action!
                                  ed key-id
                                  (fn []
                                    (try
                                      (handler (extensions/build-extension-context))
                                      (catch Exception e
                                        (tui/tui-flash!
                                         t (str "Extension shortcut error: " (ex-message e)))))))
                                 (fn []
                                   (tui-kb/unregister-definition! kmgr key-id)
                                   (editor/editor-set-priority-action! ed key-id nil))))
         ;; Agent control (pi: ctx.setModel / getThinkingLevel /
         ;; setThinkingLevel / sendUserMessage / getActiveTools /
         ;; setActiveTools)
         :set-model (fn [model]
                      (if (and model (models/has-configured-auth model))
                        (let [ag @(:agent-state cs)
                              old-model (models/get-model @(:provider ag) @(:model ag))]
                          (reset! (:provider ag) (:provider model))
                          (agent/set-model! ag (:id model))
                          (let [new-thinking (agent/switch-thinking-level old-model model @(:thinking ag) nil)]
                            (agent/set-thinking-level! ag new-thinking)
                            (cfg/set-default-model! (:provider model) (:id model))
                            (sync-footer-model! cs)
                            (state/update-editor-border-color! cs new-thinking)
                            (tui/tui-request-render (:tui cs)))
                          true)
                        false))
         :set-thinking-level (fn [level]
                               (when (contains? #{:off :minimal :low :medium
                                                  :high :xhigh :max} level)
                                 (agent/set-thinking-level! @(:agent-state cs) level)
                                 (sync-footer-model! cs)
                                 (state/update-editor-border-color! cs level)
                                 (tui/tui-request-render (:tui cs)))
                               nil)
         :get-thinking-level (fn []
                               @(:thinking @(:agent-state cs)))
         :send-user-message (fn [text & [{:keys [deliver-as expand-prompt-templates?]}]]
                              (let [ag @(:agent-state cs)
                                    ;; pi: prompt() throws while compaction is
                                    ;; in progress — extension messages cannot
                                    ;; queue into the UI compaction queue.
                                    _ (when @(:compacting? ag)
                                        (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                        {:type :compaction-in-progress})))
                                    ;; pi: prompt() with expandPromptTemplates —
                                    ;; extension commands execute immediately
                                    ;; (consuming the message), then skill
                                    ;; commands + prompt templates expand.
                                    ;; kmet defaults to NO expansion (the
                                    ;; existing behavior); opt in explicitly.
                                    text (if expand-prompt-templates?
                                           (turn/expand-user-message-text cs text)
                                           text)]
                                (when text
                                  (if (= :idle @(:status ag))
                                    ;; pi: sendUserMessage always triggers a
                                    ;; turn when idle
                                    (turn/start-agent-run! cs text)
                                    (if (= :steer deliver-as)
                                      (agent/steer! ag text)
                                      (agent/follow-up! ag text))))
                                ;; both updates schedule their own frames
                                (status/update-pending-messages! cs)
                                (state/update-footer! cs)
                                nil))
         ;; pi: sendMessage — a custom message: persisted as a custom_message
         ;; session entry, injected into the agent context (sent to the LLM
         ;; as a user message; rendered when :display) and optionally
         ;; triggering a turn. Idle + trigger-turn starts the run (the
         ;; message is already in context); busy queues via deliver-as
         ;; (:steer injects immediately — the next LLM call sees it;
         ;; anything else defers to the next turn).
         :send-message! (fn [message & [opts]]
                          (let [ag @(:agent-state cs)
                                ;; pi: sendMessage → prompt — custom messages
                                ;; cannot be submitted while compaction is in
                                ;; progress (the compaction queue is UI-only).
                                _ (when @(:compacting? ag)
                                    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                    {:type :compaction-in-progress})))
                                custom-type (or (:custom-type message) :custom)
                                display (if (nil? (:display message)) true (:display message))
                                msg {:role :custom
                                     :custom-type custom-type
                                     :content (:content message)
                                     :display display
                                     :details (:details message)}]
                            (when (:session ag)
                              (session/append-custom-message-entry!
                               (:session ag) custom-type (:content message)
                               display (:details message)))
                            (agent/add-context-message! ag msg)
                            (when (:trigger-turn opts)
                              (if (= :idle @(:status ag))
                                (turn/start-agent-run! cs)
                                (if (= :steer (:deliver-as opts))
                                  ;; already in context — the next LLM call
                                  ;; sees it (pi: steer into the current run)
                                  nil
                                  (agent/follow-up! ag msg))))
                            ;; both updates schedule their own frames
                            (status/update-pending-messages! cs)
                            (state/update-footer! cs)
                            true))
         :get-active-tools (fn []
                             @(:enabled-tools @(:agent-state cs)))
         :set-active-tools (fn [names]
                             ;; agent state only — no display depends on
                             ;; this synchronously
                             (agent/set-active-tools! @(:agent-state cs) names)
                             nil)
         ;; Extension context (pi: ExtensionContext) — captures the live
         ;; layout/agent state per call; the headless default in
         ;; extensions.clj covers everything else (mode/has-ui/…)
         :build-context (fn []
                          ;; capture the agent-state ATOM: session swaps
                          ;; assoc a NEW record onto the atom, so the old
                          ;; record's :session field goes stale (compact);
                          ;; the atom fields are shared and always current
                          (let [ag-atom (:agent-state cs)
                                ag @ag-atom]
                            {:mode :interactive
                             :has-ui true
                             :cwd (fdp/fdp-get-cwd fdp)
                             :model @(:model ag)
                             :scoped-models @(:scoped-models ag)
                             :thinking-level @(:thinking ag)
                             :is-idle (fn [] (= :idle @(:status @ag-atom)))
                             :has-pending-messages (fn []
                                                     (boolean
                                                      (agent/has-queued-messages?
                                                       @ag-atom)))
                             :signal (fn [] @(:signal @ag-atom))
                             :abort (fn []
                                      (when-not (= :idle @(:status @ag-atom))
                                        ;; pi: ctx.abort() restores queued
                                        ;; steering/follow-up messages to the
                                        ;; editor before aborting — a message
                                        ;; only reaches the chat once the loop
                                        ;; consumes it, so clearing the queues
                                        ;; without restoring would drop it
                                        ;; entirely (restoreQueuedMessagesToEditor
                                        ;; {abort:true})
                                        (turn/restore-queued-messages! cs)
                                        (agent/cancel-turn @ag-atom)))
                             :shutdown (fn [] (tui/tui-stop t))
                             :get-context-usage (fn []
                                                  ;; pi: null without an
                                                  ;; active session
                                                  (when-let [_ (fdp/fdp-get-session fdp)]
                                                    (let [tokens (fdp/fdp-context-tokens fdp)
                                                          window (fdp/fdp-get-context-window fdp)]
                                                      {:tokens tokens
                                                       :context-window window
                                                       :percent (when (and tokens window
                                                                           (pos? window))
                                                                  (int (* 100.0 (/ tokens window))))})))
                             :compact (fn [& [{:keys [custom-instructions
                                                      on-complete on-error]}]]
                                        (future
                                          (try
                                            (let [r (agent/compact-context! @ag-atom custom-instructions :manual)]
                                              (if (and (= :failed r) on-error)
                                                ;; pi: compact() throws on
                                                ;; summarization failure →
                                                ;; onError fires
                                                (on-error (ex-info "Context compaction failed: the summarization call did not return a summary."
                                                                   {:type :compaction-failed}))
                                                (when on-complete (on-complete {:result r}))))
                                            (catch Exception e
                                              (when on-error (on-error e))))))
                             :get-system-prompt (fn [] @(:system @ag-atom))
                             :get-system-prompt-options (fn []
                                                          (let [config (:config cs)]
                                                            ;; pi: getSystemPromptOptions — the prompt's build
                                                            ;; inputs; cwd is the runtime one (a switched
                                                            ;; session's), context files stay the launch dir's
                                                            {:custom-prompt (cfg/get-custom-prompt config)
                                                             :append-prompt (cfg/get-append-system-prompt config)
                                                             :cwd (state/runtime-cwd cs)
                                                             :context-files (context/load-project-context-files
                                                                             (cfg/get-agent-dir)
                                                                             (str (fs/cwd)))}))
                             :wait-for-idle (fn []
                                              (if (= :idle @(:status @ag-atom))
                                                nil
                                                (let [p (promise)]
                                                  (future
                                                    (loop []
                                                      (if (= :idle @(:status @ag-atom))
                                                        (deliver p true)
                                                        (do (Thread/sleep 100) (recur)))))
                                                  p)))
                             :reload (fn [] (builtins/handle-reload cs nil))
                             :new-session (fn [& _]
                                            (session-admin/handle-new-session cs)
                                            {:cancelled false})
                             :fork (fn [entry-id & _]
                                     (if entry-id
                                       (do (session-admin/fork-at! cs entry-id) {:cancelled false})
                                       {:cancelled true}))
                             :navigate-tree (fn [target-id & [{:keys [summarize
                                                                      custom-instructions
                                                                      replace-instructions
                                                                      label]}]]
                                              ;; a run in flight would render its events into
                                              ;; the new branch (the /tree command refuses too)
                                              (if (state/turn-running? cs)
                                                {:cancelled true}
                                                (if-let [sess @(:session-atom cs)]
                                                  (if-let [entry (session/get-entry sess
                                                                                    target-id)]
                                                    (do (session-admin/navigate-tree! cs sess entry
                                                                                      (boolean summarize)
                                                                                      custom-instructions
                                                                                      (boolean replace-instructions)
                                                                                      label)
                                                        {:cancelled false})
                                                    {:cancelled true})
                                                  {:cancelled true})))
                             :switch-session (fn [session-path & _]
                                               ;; a run in flight would render its events into the
                                               ;; switched session (the /resume command refuses too)
                                               (if (state/turn-running? cs)
                                                 {:cancelled true}
                                                 (try
                                                   (let [sess (session/load-session session-path)
                                                         ;; pi: emitBeforeSwitch (reason :resume) —
                                                         ;; extensions may cancel the switch
                                                         result (event-bus/emit-event!
                                                                 {:type :session-before-switch
                                                                  :reason :resume
                                                                  :target-session-file session-path})]
                                                     (if (:cancel result)
                                                       {:cancelled true}
                                                       (do (session-admin/restore-session! cs sess true)
                                                           {:cancelled false})))
                                                   (catch Exception _ {:cancelled true}))))
                             :is-project-trusted (fn [] false)}))
         :reset (fn []
                  ;; pi: resetExtensionUI — dispose widgets, restore
                  ;; footer/header/editor, clear statuses + working
                  ;; customization, drop terminal input listeners
                  (doseq [m [widgets-above-atom widgets-below-atom]]
                    (doseq [w (vals @m)]
                      (when-let [dispose (:dispose w)]
                        (try (dispose) (catch Exception _)))))
                  (reset! widgets-above-atom {})
                  (reset! widgets-below-atom {})
                  (when @custom-footer-atom
                    (when-let [dispose (:dispose @custom-footer-atom)]
                      (try (dispose) (catch Exception _)))
                    (tui/tui-remove-child t @custom-footer-atom)
                    (reset! custom-footer-atom nil)
                    (tui/tui-add-child t ftr))
                  (when @custom-header-atom
                    (when-let [dispose (:dispose @custom-header-atom)]
                      (try (dispose) (catch Exception _)))
                    (reset! custom-header-atom nil)
                    (container/container-clear header-container)
                    (container/container-add-child header-container sp1)
                    (container/container-add-child header-container hdr)
                    (container/container-add-child header-container sp1)
                    (expandable-text/expandable-text-rebuild! hdr))
                  (when-let [component @custom-dialog-comp]
                    (dispose-dialog-component! component)
                    (reset! custom-dialog-comp nil))
                  (doseq [unsub @terminal-input-unsubscribers]
                    (try (unsub) (catch Exception _)))
                  (reset! terminal-input-unsubscribers [])
                  (doseq [key (keys @(:extension-statuses-atom ftr))]
                    (footer/footer-set-extension-status! ftr key nil))
                  (spinner/spinner-set-indicator! (:spinner (:status-indicator cs)) nil)
                  (status-indicator/status-indicator-set-text! (:status-indicator cs) "Working")
                  (chat-history/chat-history-set-hidden-thinking-label! ch nil)
                  (reset! extension-autocomplete-factories [])
                  (rebuild-autocomplete-provider!)
                  (when @editor-factory-atom
                    (let [current-text (editor-text-get @current-editor-atom)]
                      (editor-text-set! ed current-text)
                      (tui/tui-set-focus t ed)
                      (reset! current-editor-atom ed))
                    (reset! editor-factory-atom nil))
                  ;; restore any open dialog
                  (dock/clear! cs)
                  (tui/tui-set-focus t ed)
                  (reset! current-editor-atom ed)
                  (when (tui/tui-has-overlay? t) (tui/tui-hide-overlay t))
                  (tui/tui-request-render t))}]
    (extensions/set-ui-registry! registry)
    ;; Live session + context injection for extensions (pi:
    ;; ctx.sessionManager / custom messages flowing through the agent loop).
    ;; Installed once with the registry; reload mutates the same CoreState
    ;; (agent, session atom), so the wiring stays valid across reloads —
    ;; only /new, /resume, fork and clone re-register the session.
    (extensions/set-session! @(:session-atom cs))
    (extensions/set-context-sink!
     (fn [msg] (agent/add-context-message! @(:agent-state cs) msg)))
    (extensions/set-entry-sink!
     (fn [entry]
       (when-let [rendered (session-admin/run-entry-renderer
                            (extensions/get-entry-renderer (:custom-type entry)) entry)]
         (chat-history/chat-history-add-message!
          (:chat-history cs)
          (session-admin/renderer-result->message rendered))
         ;; The sink appends to the untracked chat message vector; request
         ;; a frame after the append rather than relying on an older watch.
         (when t
           (tui/tui-request-render t)))))
    registry))

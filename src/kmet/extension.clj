(ns kmet.extension
  "The kmet extension contract — the root namespace an extension may depend
   on (psi: the extension-api contract; pi: the `pi` object passed to the
   extension factory). Extensions may also use the shared library layers
   kmet.tui.* (generic TUI components; pi: @earendil-works/pi-tui) and
   kmet.libs.* (generic, self-contained utilities) — injected by reference,
   never re-evaluated. Everything else in kmet.* is off-limits.

   **Building UI**: extensions build their own components with kmet.tui.*
   (Container/Text/SelectList/Input/... + kmet.tui.theme for styling) and
   mount them with `ui-custom` — the one host bridge, pi's ctx.ui.custom:
   the factory receives (tui theme keybindings close) and returns a
   component (a defcomponent, or a duck-typed map {:render :handle-input
   :invalidate} like extensions/tools.clj); the host mounts it (overlay or
   editor dock), feeds it input, and `close` dismisses it. The api's :ui
   map carries only host-owned bridges: :custom, :notify (the flash — the
   TUI instance is host-owned), and integrations with the host layout,
   editor, status indicator, and theme controller. There are no host-built
   dialog capabilities — select/input/editor dialogs are kmet.tui
   components you compose yourself (pi parity: pi-mcp-adapter ships its
   own panel). kmet.tui.theme exposes get-theme/get-all-themes/
   get-theme-by-name/get-current-theme directly.

   An extension is a Clojure namespace defining:
     (defn init [api] ...)     — required; register everything here
     (defn shutdown [api] ...) — optional; teardown on unload/reload
   api is a map of runtime-bound capabilities. The wrapper fns in this
   namespace take api as their first argument and dispatch to the runtime
   implementation, so extensions never require kmet internals.

   Layout: extensions are single .clj files, directories containing an
   extension.edn manifest, or .jar/.zip archives with the same layout at
   the archive root:
     {:name \"my-ext\" :entry my.ext.main}
   — :entry is a namespace symbol resolved by strict ns-path lookup
   (namespace a.b/c lives at a/b/c.clj under the root); internal namespaces
   are required from there. An extension directory may also carry a deps.edn
   declaring library dependencies; kmet serves them to that extension only
   (each extension runs in its own isolated context, so different
   extensions may pin different versions of the same library).

   Lifecycle: loaded at startup (and via /reload), reloadable and unloadable
   at runtime. Unload calls shutdown (if defined), then unregisters
   everything the extension registered. Registration calls therefore return
   deregister fns where meaningful (e.g. on-event).

   All api fns are safe to call at any time — the ui/agent-control ones
   dispatch through a runtime registry and are inert before the interactive
   layout exists (and in headless/print mode).")

;; ─── API key docs ─────────────────────────────────────────────────────────
;; The api map passed to init/shutdown carries:
;;   :extension-name, :extension-path, :extension-dir   — identity/context
;;     (:extension-dir is nil for jar/zip artifacts — a jar has no directory;
;;     read bundled resources via clojure.java.io/resource instead)
;;   :agent-dir                                          — host agent dir
;;     (KMET_CODING_AGENT_DIR-aware; pi: getAgentDir) — extension state
;;     (configs, caches) belongs under it
;;   :register-command! :unregister-command! :get-commands
;;   :register-tool! :unregister-tool! :get-all-tools :create-bash-tool
;;   :get-active-tools :set-active-tools
;;   :on-event :emit-event!                             — event bus
;;   :on-input :on-before-agent-start                   — hooks
;;   :on-tool-call :on-tool-result                      — tool transforms
;;   :register-flag! :get-flag
;;   :register-shortcut! :register-markdown-transformer! :send-message!
;;   :register-entry-renderer! :register-message-renderer!
;;   :register-skill! :register-prompt!                 — bundled resources
;;   :set-model :get-thinking-level :set-thinking-level :send-user-message
;;   :exec
;;   :ui      — map of UI capabilities — only host-owned bridges: :custom
;;              (mount extension-built components; pi: ctx.ui.custom),
;;              :notify (flash), and layout/editor/status/theme-controller
;;              integrations. Build components with kmet.tui.* and host
;;              them via ui-custom — the api carries no host-built dialogs
;;              or theme lookups (kmet.tui.theme is shared) — inert before
;;              the layout exists / in headless mode
;;   :models  — provider/model facades (get-all, find, auth status, …)
;;   :session — live session (append entry/message, labels, name)

;; ─── Wrappers (extensions call these; api is the map from init) ───────────

(defn register-command!
  "Register a slash command. The handler receives (ctx args): ctx is the
   extension context (pi: ExtensionContext) — mode/has-ui, cwd, current
   model, is-idle/abort/compact/reload/session control, … Build it at call
   time when you need a fresh view; the values are captured per call."
  [api cmd] ((:register-command! api) cmd))
(defn unregister-command! [api name] ((:unregister-command! api) name))
(defn get-commands [api] ((:get-commands api)))

(defn register-tool! [api tool] ((:register-tool! api) tool))
(defn unregister-tool! [api name] ((:unregister-tool! api) name))
(defn get-all-tools [api] ((:get-all-tools api)))
(defn get-active-tools [api] ((:get-active-tools api)))
(defn set-active-tools [api names] ((:set-active-tools api) names))

(defn create-bash-tool
  "Build a bash tool (a plain tool map for register-tool!) with spawn options
   — pi: createBashTool/createShellToolDefinition. The built-in bash tool is
   this constructor with default options; registering the result under a name
   replaces that tool, so a custom bash tool keeps the built-in rendering.
   OPTS:
     :spawn-hook          — (fn [{:keys [command cwd env]}] → same map) run
                            before spawn, after the KMET_* session env is
                            injected (pi: BashSpawnHook) — rewrite the command
                            (source a profile, wrap with a sandbox), the cwd,
                            or the env
     :expose-session-env? — inject the run's KMET_* session env
                            (KMET_SESSION_ID/SESSION_FILE/PROVIDER/MODEL/
                            REASONING_LEVEL) and the matching prompt guideline
                            (default true; false disables both — pi:
                            exposeSessionEnvironment)
     :command-prefix      — line prepended to every command (pi:
                            commandPrefix; default: the :shell-command-prefix
                            setting)
     :shell-path          — custom shell binary (pi: shellPath; default: the
                            :shell-path setting)
     :operations          — custom executor, {:command :cwd :on-data :signal
                            :timeout :env} → {:exit-code :cleanup} (pi:
                            BashOperations — delegate to a remote host)
     :name :label :description — tool-facing overrides (defaults: 'bash',
                            'Execute command', pi's bash description)"
  [api opts]
  ((:create-bash-tool api) opts))

(defn on-event
  "Register a handler for an event type (e.g. :session-start, :agent-end,
   :message-end). Handlers always receive (event ctx) — the ctx is the
   extension context, built fresh per event. Returns a deregister fn."
  [api event-type handler]
  ((:on-event api) event-type handler))
(defn emit-event! [api event] ((:emit-event! api) event))

(defn on-input [api hook] ((:on-input api) hook))
(defn on-before-agent-start [api hook] ((:on-before-agent-start api) hook))
(defn on-tool-call [api hook] ((:on-tool-call api) hook))
(defn on-tool-result [api hook] ((:on-tool-result api) hook))

(defn register-flag! [api name & [opts]] ((:register-flag! api) name opts))
(defn get-flag [api name] ((:get-flag api) name))

(defn register-shortcut! [api key-id & [opts]] ((:register-shortcut! api) key-id opts))
(defn register-markdown-transformer! [api transformer]
  ((:register-markdown-transformer! api) transformer))
(defn send-message! [api message & [opts]] ((:send-message! api) message opts))

(defn register-entry-renderer! [api custom-type renderer]
  ((:register-entry-renderer! api) custom-type renderer))
(defn register-message-renderer! [api custom-type renderer]
  ((:register-message-renderer! api) custom-type renderer))

(defn register-skill!
  "Register a skill from the extension's bundled SKILL.md content string.
   Read the resource with clojure.java.io/resource (works for dir and
   jar/zip artifacts alike) and hand the content over — the host parses,
   validates and stores the body in memory, so jarred skills need no
   filesystem path. OPTS: :location (display locator, e.g.
   `my-ext:skills/mcp/SKILL.md`), :fallback-name (when frontmatter has no
   name). Jar skills must be self-contained single files (no relative
   refs). The host serves the body through the read tool under :location
   and through /skill:name expansion. Returns a deregister fn."
  [api raw-content & [opts]]
  ((:register-skill! api) raw-content opts))

(defn register-prompt!
  "Register a prompt template from the extension's bundled .md content.
   PROMPT: {:name :content ...} (+ optional :location display locator).
   Same content contract as register-skill!: self-contained, no relative
   refs for jar/zip artifacts. Returns a deregister fn."
  [api prompt & [opts]]
  ((:register-prompt! api) prompt opts))

(defn set-model [api model] ((:set-model api) model))
(defn get-thinking-level [api] ((:get-thinking-level api)))
(defn set-thinking-level [api level] ((:set-thinking-level api) level))
(defn send-user-message [api text & [opts]] ((:send-user-message api) text opts))
(defn exec [api command args & [opts]] ((:exec api) command args opts))

(defn get-agent-dir
  "The host agent dir (pi: getAgentDir — honors KMET_CODING_AGENT_DIR).
   Keep extension state (configs, caches) under it so a sandboxed agent
   dir relocates it too."
  [api]
  (:agent-dir api))

(defn ui
  "The UI capability map (dialogs/status/widgets/editor/theme/...). Calls are
   inert before the layout exists and in headless mode."
  [api]
  (:ui api))

;; ─── UI wrappers (dispatch to (:ui api) capabilities) ────────────────────
;; Only host-owned bridges are api capabilities: :custom mounts
;; extension-built components (pi: ctx.ui.custom), :notify flashes, and the
;; layout/editor/status/theme integrations touch host-owned state. Build
;; components with the shared kmet.tui.* layer instead — the api carries no
;; host-built dialogs or theme lookups (kmet.tui.theme is shared).

(defn ui-notify [api message & [type]] ((:notify (ui api)) message type))
(defn ui-custom [api factory & [opts]] ((:custom (ui api)) factory opts))

(defn ui-chat-info
  "Append a persistent :info message to the TUI chat history — the
   /session display style: LABEL renders bracketed above CONTENT, the
   message stays in the live transcript with nothing to dismiss, and it is
   never sent to the LLM nor persisted across restarts. No-op before the
   layout exists / headless (check ctx :mode for a fallback). Returns nil."
  [api label & [content]]
  ((:chat-info (ui api)) label (or content ""))
  nil)
(defn ui-set-status [api key text] ((:set-status (ui api)) key text))
(defn ui-set-widget [api key content & [{:keys [placement]}]]
  ((:set-widget (ui api)) key content {:placement placement}))
(defn ui-set-footer [api factory] ((:set-footer (ui api)) factory))
(defn ui-set-header [api factory] ((:set-header (ui api)) factory))
(defn ui-set-title [api title] ((:set-title (ui api)) title))
(defn ui-set-editor-text [api text] ((:set-editor-text (ui api)) text))
(defn ui-get-editor-text [api] ((:get-editor-text (ui api))))
(defn ui-paste-to-editor [api text] ((:paste-to-editor (ui api)) text))
(defn ui-set-working-indicator [api options] ((:set-working-indicator (ui api)) options))
(defn ui-set-working-message [api message] ((:set-working-message (ui api)) message))
(defn ui-set-working-visible [api visible?] ((:set-working-visible (ui api)) visible?))
(defn ui-set-hidden-thinking-label [api label] ((:set-hidden-thinking-label (ui api)) label))
(defn ui-set-editor-component [api factory] ((:set-editor-component (ui api)) factory))
(defn ui-add-autocomplete-provider [api factory] ((:add-autocomplete-provider (ui api)) factory))
(defn ui-set-theme [api theme-or-name] ((:set-theme (ui api)) theme-or-name))
(defn ui-get-tools-expanded [api] ((:get-tools-expanded (ui api))))
(defn ui-set-tools-expanded [api expanded?] ((:set-tools-expanded (ui api)) expanded?))
(defn ui-get-tool-display-mode [api] ((:get-tool-display-mode (ui api))))
(defn ui-set-tool-display-mode [api mode] ((:set-tool-display-mode (ui api)) mode))
(defn ui-on-terminal-input [api handler] ((:on-terminal-input (ui api)) handler))

(defn models
  "Provider/model facades: :get-all :get-available :find :has-configured-auth
   :get-provider-auth-status :get-api-key-and-headers
   :get-registered-provider-config :get-registered-provider-ids
   :register-provider! :unregister-provider!."
  [api]
  (:models api))

(defn models-register-provider!
  "Register/replace an extension provider (pi: ctx.registerProvider):
   validated eagerly — a broken config throws without touching stored
   state — then recomposes builtin + models.edn + extension layers.
   The registration is removed automatically when the extension unloads."
  [api provider-id config]
  ((:register-provider! (models api)) provider-id config))

(defn models-unregister-provider!
  "Remove an extension provider registration (pi: ctx.unregisterProvider):
   the provider falls back to its builtin (or disappears when it had
   none), keeping the models.edn layer."
  [api provider-id]
  ((:unregister-provider! (models api)) provider-id))

(defn session
  "The live session facades: :append-entry! :append-message! :get-entries
   :get-all-entries :get-branch :get-leaf-id :get-entry :set-label!
   :get-label :set-name! :get-name (pi: ctx.sessionManager)."
  [api]
  (:session api))

;; ─── Nullable API (test fixture) ──────────────────────────────────────────
;; Enables narrow tests of an extension's init/shutdown without the kmet
;; runtime: load the extension against a fake api and assert what it
;; registered (psi: create-nullable-extension-api).

(defn create-nullable-api
  "A test-fixture api that captures registrations into a state atom.
   Returns {:api ... :state atom}. State shape:
     {:commands {name cmd} :tools {name tool}
      :handlers {event-type [handler ...]} :flags {name opts}
      :entry-renderers {custom-type renderer} :message-renderers {custom-type renderer}
      :skills [{:content opts}] :prompts [prompt]
      :tool-call-hooks [...] :tool-result-hooks [...]
      :input-hooks [...] :before-agent-start-hooks [...]
      :bash-tool-opts [opts...]
      :ui-calls [args...] :emitted [events...] :model-calls [...]}}
   Deregister fns remove the corresponding registrations (unload replay).
   OPTS: :agent-dir — the value (get-agent-dir api) returns (default nil;
   pass a temp dir in tests that exercise agent-dir state)."
  ([] (create-nullable-api {}))
  ([{:keys [agent-dir]}]
   (let [state (atom {:commands {} :tools {} :handlers {}
                      :flags {} :entry-renderers {} :message-renderers {}
                      :skills [] :prompts []
                      :tool-call-hooks [] :tool-result-hooks []
                      :input-hooks [] :before-agent-start-hooks []
                      :bash-tool-opts []
                      :ui-calls [] :emitted [] :model-calls []})
         api {:extension-name "nullable" :extension-path "test" :extension-dir "test"
              :agent-dir agent-dir
              :register-command! (fn [cmd]
                                   (swap! state assoc-in [:commands (:name cmd)] cmd)
                                   (fn [] (swap! state update :commands dissoc (:name cmd))))
              :unregister-command! (fn [name] (swap! state update :commands dissoc name))
              :get-commands (fn [] (vals (:commands @state)))
              :register-tool! (fn [tool]
                                (swap! state assoc-in [:tools (:name tool)] tool)
                                (fn [] (swap! state update :tools dissoc (:name tool))))
              :unregister-tool! (fn [name] (swap! state update :tools dissoc name))
              :get-all-tools (fn [] (vals (:tools @state)))
              :get-active-tools (fn [] (keys (:tools @state)))
              :set-active-tools (fn [names] (swap! state assoc :active-tools names))
              ;; pi: createBashTool — the fixture records the options and
              ;; returns an inert tool map (extension init code can register it)
              :create-bash-tool (fn [opts]
                                  (swap! state update :bash-tool-opts conj opts)
                                  {:name (or (:name opts) "bash")
                                   :label "Execute command"
                                   :description "bash (nullable)"
                                   :parameters {:type "object"
                                                :properties {"command" {:type "string"
                                                                        :description "Shell command to execute"}}
                                                :required ["command"]}
                                   :execute (fn [args & _] {:content (str (:command args)) :is-error false})
                                   :streams? true})
              :on-event (fn [event-type handler]
                          (swap! state update-in [:handlers event-type] (fnil conj []) handler)
                          (fn [] (swap! state update-in [:handlers event-type]
                                        (fn [hs] (remove #(identical? % handler) hs)))))
              :emit-event! (fn [event] (swap! state update :emitted conj event))
              :on-input (fn [hook]
                          (swap! state update :input-hooks conj hook)
                          (fn [] (swap! state update :input-hooks
                                        (fn [hs] (remove #(identical? % hook) hs)))))
              :on-before-agent-start (fn [hook]
                                       (swap! state update :before-agent-start-hooks conj hook)
                                       (fn [] (swap! state update :before-agent-start-hooks
                                                     (fn [hs] (remove #(identical? % hook) hs)))))
              :on-tool-call (fn [hook]
                              (swap! state update :tool-call-hooks conj hook)
                              (fn [] (swap! state update :tool-call-hooks
                                            (fn [hs] (remove #(identical? % hook) hs)))))
              :on-tool-result (fn [hook]
                                (swap! state update :tool-result-hooks conj hook)
                                (fn [] (swap! state update :tool-result-hooks
                                              (fn [hs] (remove #(identical? % hook) hs)))))
              :register-flag! (fn [name opts]
                                (swap! state assoc-in [:flags name] opts)
                                (fn [] (swap! state update :flags dissoc name)))
              :get-flag (fn [name] (get-in @state [:flags name]))
              :register-shortcut! (fn [key-id opts]
                                    (swap! state assoc-in [:shortcuts key-id] opts)
                                    (fn [] (swap! state update :shortcuts dissoc key-id)))
              :register-markdown-transformer! (fn [transformer]
                                                (swap! state update :markdown-transformers conj transformer)
                                                (fn [] (swap! state update :markdown-transformers
                                                              (fn [ts] (remove #(identical? % transformer) ts)))))
              :send-message! (fn [message opts]
                               (swap! state update :ui-calls conj [:send-message! message opts]))
              :register-entry-renderer! (fn [custom-type renderer]
                                          (swap! state assoc-in [:entry-renderers custom-type] renderer)
                                          (fn [] (swap! state update :entry-renderers dissoc custom-type)))
              :register-message-renderer! (fn [custom-type renderer]
                                            (swap! state assoc-in [:message-renderers custom-type] renderer)
                                            (fn [] (swap! state update :message-renderers dissoc custom-type)))
              :register-skill! (fn [raw-content & [opts]]
                                 (swap! state update :skills conj {:content raw-content :opts opts})
                                 (fn [] (swap! state update :skills
                                               (fn [ss] (remove #(= raw-content (:content %)) ss)))))
              :register-prompt! (fn [prompt & [opts]]
                                  (swap! state update :prompts conj (assoc prompt :opts opts))
                                  (fn [] (swap! state update :prompts
                                                (fn [ps] (remove #(= prompt (dissoc % :opts)) ps)))))
              :set-model (fn [model]
                           (swap! state update :model-calls conj [:set-model model])
                           (boolean model))
              :get-thinking-level (fn [] :off)
              :set-thinking-level (fn [level] (swap! state assoc :thinking level))
              :send-user-message (fn [text opts]
                                   (swap! state update :ui-calls conj [:send-user-message text opts]))
              :exec (fn [command args opts]
                      (swap! state update :ui-calls conj [:exec command args opts]))
              :ui (into {} (for [[k _] {:notify 1 :custom 1
                                        :set-status 1 :set-widget 1 :set-footer 1 :set-header 1
                                        :set-editor-text 1 :get-editor-text 1 :paste-to-editor 1
                                        :set-theme 1 :set-working-indicator 1
                                        :set-working-message 1 :set-working-visible 1
                                        :on-terminal-input 1 :set-tools-expanded 1
                                        :get-tools-expanded 1
                                        :set-tool-display-mode 1
                                        :get-tool-display-mode 1}]
                             [k (fn [& args] (swap! state update :ui-calls conj (into [k] args)))]))
              :models {:get-all (fn [] (swap! state update :model-calls conj [:get-all]) [])
                       :get-available (fn [] [])
                       :find (fn [provider model-id]
                               (swap! state update :model-calls conj [:find provider model-id])
                               nil)
                       :has-configured-auth (fn [model] (boolean model))
                       :get-model-auth (fn [_model] {:configured false :source nil})
                       :get-provider-auth-status (fn [_provider] {:configured false :source nil})
                       :get-api-key-and-headers (fn [_model] {:ok false :error "nullable"})
                       :get-registered-provider-config (fn [_provider-id] nil)
                       :get-registered-provider-ids (fn [] [])
                       :register-provider! (fn [provider-id config]
                                             (swap! state update :model-calls
                                                    conj [:register-provider! provider-id config])
                                             {:name (str provider-id)})
                       :unregister-provider! (fn [provider-id]
                                               (swap! state update :model-calls
                                                      conj [:unregister-provider! provider-id])
                                               nil)}
              :session {:append-entry! (fn [custom-type data]
                                         (swap! state update :ui-calls conj [:append-entry custom-type data]))
                        :append-message! (fn [custom-type _content display _details]
                                           (swap! state update :ui-calls conj [:append-message custom-type display]))
                        :get-entries (fn [_custom-type] [])
                        :get-all-entries (fn [_custom-type] [])
                        :get-branch (fn [] [])
                        :get-leaf-id (fn [] nil)
                        :get-entry (fn [_id] nil)
                        :set-label! (fn [id label] (swap! state assoc-in [:labels id] label))
                        :get-label (fn [id] (get-in @state [:labels id]))
                        :set-name! (fn [name] (swap! state assoc :session-name name))
                        :get-name (fn [] (:session-name @state))}}]
     {:api api :state state})))

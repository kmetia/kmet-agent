(ns kmet.app.extensions
  "Extension runtime: discovers, loads, reloads and unloads Clojure
   extensions. The extension contract lives in kmet.extension — extensions
   depend on that namespace plus the shared library layers kmet.tui.*
   (the generic TUI layer; the port of pi's @earendil-works/pi-tui) and
   kmet.libs.* (generic, self-contained utilities) — all shared by
   reference; this runtime wires the api capabilities to the registries
   and the interactive/loop surfaces.

   An extension is a .clj file defining (defn init [api]) in its namespace,
   a directory containing an extension.edn manifest, or a .jar/.zip archive
   with the same layout at its root:
     {:name \"my-ext\" :entry my.ext.main}
   The manifest lists only the initial namespace (:entry, a symbol);
   everything else is required from there. Each extension evaluates in its
   own isolated SCI context: internal namespaces are served from the
   extension artifact (dir or jar) by strict ns-path lookup,
   declared libraries from its deps.edn (resolved in-process via
   borkdude.deps) — so
   different extensions can use different versions of the same library, and
   unloading an extension releases everything it pulled in. Optional
   (defn shutdown [api]) runs on unload, which also unregisters everything
   the extension registered (each registration tracks its deregister fn).

   Extensions load at startup (core.clj), are re-loaded by /reload, and can
   be unloaded/reloaded at runtime via unload-extension! /
   reload-extensions!."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            #?@(:bb [[borkdude.deps :as bdeps]])
            [sci.core :as sci]
            [kmet.ai.models :as models]
            [kmet.ai.hooks :as ai-hooks]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.config :as cfg]
            [kmet.tui.theme :as theme]
            [kmet.extension]))

;; ─── Provider-event bridges (pi: context / before_provider_request /
;; ─── before_provider_headers / after_provider_response) ────────────────
;; The ai layer exposes injectable hooks (kmet.ai.api.shared) — it cannot
;; depend on kmet.app (the event bus). These bridges translate bus events
;; into hook results. The bus returns the LAST non-nil handler result;
;; pi chains handler results (each handler sees the previous one's
;; replacement) — kmet's approximation: handlers see the original event
;; and the last non-nil result wins. before-provider-headers handlers
;; return the replacement header map (Clojure maps are immutable — the
;; return value IS the mutation; pi mutates in place).

(defn- install-provider-event-bridges!
  "Wire the bus to the ai-layer hooks once (idempotent)."
  []
  (ai-hooks/set-context-hook!
   (fn [messages]
     (let [result (event-bus/emit-event! {:type :context :messages messages})]
       (if (and result (contains? result :messages))
         (:messages result)
         messages))))
  (ai-hooks/set-before-provider-request-hook!
   (fn [payload]
     (let [result (event-bus/emit-event! {:type :before-provider-request
                                          :payload payload})]
       (if (some? result) result payload))))
  (ai-hooks/set-before-provider-headers-hook!
   (fn [headers]
     (let [result (event-bus/emit-event! {:type :before-provider-headers
                                          :headers headers})]
       (if (map? result) result headers))))
  (ai-hooks/set-after-provider-response-hook!
   (fn [{:keys [status headers]}]
     (event-bus/emit-event! {:type :after-provider-response
                             :status status
                             :headers headers})))
  nil)

(install-provider-event-bridges!)

;; ─── Extension records ────────────────────────────────────────────────────
(defrecord Extension [name path kind entry-ns ctx jars api deregister-fns initialized?])

(defn- extension-dir-of
  "The extension's own directory: for a dir extension :path IS the
   directory; for a single-file extension it's the file, so the dir is its
   parent. nil for jar extensions — a jar has no directory; resources are
   accessed via io/resource instead (see jar-ext.md)."
  [ext]
  (when-not (= :jar (:kind ext))
    (str (if (fs/directory? (:path ext))
           (:path ext)
           (fs/parent (:path ext))))))

;; ─── Registries (the storage; api capabilities wire into these) ──────────
(defonce ^:private extensions (atom []))
(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))
(defonce ^:private entry-renderers (atom {}))
(defonce ^:private message-renderers (atom {}))
(defonce ^:private tool-call-hooks (atom []))
(defonce ^:private tool-result-hooks (atom []))
(defonce ^:private markdown-transformers (atom []))
(defonce ^:private flags (atom {}))
(defonce ^:private cli-flags (atom {}))
(defonce ^:private ui-registry (atom {}))
(defonce ^:private session-atom (atom nil))
(defonce ^:private context-sink-atom (atom nil))
(defonce ^:private entry-sink-atom (atom nil))
(defonce ^:private applied-resource-paths (atom #{}))

;; ─── Input / before-agent-start hooks (pi: pi.on('input') / ──────────────
;; ─── 'before_agent_start'; applied by modes.interactive + app.loop) ──────

(defn register-input-hook!
  "Register an input hook (extension api: on-input). Fires for agent
   messages submitted from the interactive input path. Hook:
   (fn [{:keys [text source streaming-behavior images]}]) returning
   {:action :handled} to consume, {:action :transform :text ... :images ...}
   to rewrite, or nil. Returns a deregister fn."
  [hook]
  (swap! input-hooks conj hook)
  (fn [] (swap! input-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn register-before-agent-start-hook!
  "Register a before-agent-start hook (extension api: on-before-agent-start).
   Hook: (fn [{:keys [prompt system-prompt]}]) returning a map with
   :system-prompt and/or :message, or nil. Returns a deregister fn."
  [hook]
  (swap! before-agent-start-hooks conj hook)
  (fn [] (swap! before-agent-start-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn apply-input-hooks
  "Run all input hooks in registration order over text and images
   (pi: emitInput). Returns {:action :handled} | {:action :transform ...}
   | {:action :pass ...}."
  [text source & [{:keys [streaming-behavior images]}]]
  (let [initial-images (or images [])]
    (loop [hooks @input-hooks
           current text
           current-images initial-images]
      (if-let [hook (first hooks)]
        (let [result (try
                       (hook {:text current :source source
                              :streaming-behavior streaming-behavior
                              :images current-images})
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Warning: input hook error:" (ex-message e)))
                         nil))]
          (cond
            (= :handled (:action result)) {:action :handled}
            (= :transform (:action result))
            (recur (next hooks) (:text result current)
                   (if (contains? result :images) (:images result) current-images))
            :else (recur (next hooks) current current-images)))
        (if (and (= current text) (= current-images initial-images))
          {:action :pass :text text :images current-images}
          {:action :transform :text current :images current-images})))))

(defn apply-before-agent-start-hooks
  "Run all before-agent-start hooks in registration order.
   Returns {:system-prompt string-or-nil :messages [msg ...]}."
  [prompt system-prompt]
  (loop [hooks @before-agent-start-hooks
         current-prompt system-prompt
         messages []]
    (if-let [hook (first hooks)]
      (let [result (try
                     (hook {:prompt prompt :system-prompt current-prompt})
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Warning: before-agent-start hook error:" (ex-message e)))
                       nil))]
        (recur (next hooks)
               (if (and result (contains? result :system-prompt))
                 (:system-prompt result)
                 current-prompt)
               (if (and result (:message result))
                 (conj messages (:message result))
                 messages)))
      {:system-prompt (when (not= current-prompt system-prompt) current-prompt)
       :messages messages})))

(defn clear-input-hooks! [] (reset! input-hooks []))
(defn clear-before-agent-start-hooks! [] (reset! before-agent-start-hooks []))

;; ─── Renderers + tool hooks (extension api) ───────────────────────────────

(defn register-entry-renderer!
  "Register a renderer for a custom entry type (extension api:
   register-entry-renderer!). RENDERER — (fn [entry]) returning a chat
   message map (or bare component) or nil. Returns a deregister fn."
  [custom-type renderer]
  (swap! entry-renderers assoc custom-type renderer)
  (fn [] (swap! entry-renderers dissoc custom-type)))

(defn get-entry-renderer [custom-type] (get @entry-renderers custom-type))

(defn register-message-renderer!
  "Register a renderer for a custom MESSAGE type (extension api:
   register-message-renderer!). Returns a deregister fn."
  [custom-type renderer]
  (swap! message-renderers assoc custom-type renderer)
  (fn [] (swap! message-renderers dissoc custom-type)))

(defn get-message-renderer [custom-type] (get @message-renderers custom-type))

(defn register-tool-call-hook!
  "Register a tool-call hook (extension api: on-tool-call): (fn [ctx]) →
   nil | {:block true :reason} | {:args transformed}. Returns a deregister fn."
  [hook]
  (swap! tool-call-hooks conj hook)
  (fn [] (swap! tool-call-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn register-tool-result-hook!
  "Register a tool-result hook (extension api: on-tool-result): (fn [ctx])
   → nil | {:content ... :is-error ...} overrides. Returns a deregister fn."
  [hook]
  (swap! tool-result-hooks conj hook)
  (fn [] (swap! tool-result-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn get-tool-call-hooks [] @tool-call-hooks)
(defn get-tool-result-hooks [] @tool-result-hooks)

;; ─── Shortcuts + markdown transformers (extension api) ────────────────────

(declare ui-call)

(defn register-shortcut!
  "Register a keyboard shortcut (extension api: register-shortcut! — pi:
   registerShortcut). KEY-ID is a raw key string (\"ctrl+alt+x\", \"f5\", …);
   opts: {:description str :handler (fn [ctx])}. The interactive mode
   installs it as a priority editor action — checked before every builtin
   app binding (pi: onExtensionShortcut runs first) — and registers the
   keybinding definition on the global manager (key-hints resolve, user
   overrides apply). The last registration of the same key wins (pi: last
   extension wins). Returns a deregister fn; no-op headless."
  [key-id & [{:keys [description handler]}]]
  (or (ui-call :register-shortcut! key-id {:description description :handler handler})
      ;; headless: no-op dereg (unload must never NPE on it)
      (fn [] nil)))

(defn register-markdown-transformer!
  "Register a markdown transformer (extension api:
   register-markdown-transformer! — pi: registerMarkdownTransformer):
   (fn [markdown {:keys [message-type is-streaming available-width]}])
   → string, applied to user/assistant message markdown before rendering,
   in registration order. Transformers must be idempotent (they re-run per
   render — streaming chunks re-transform the accumulated text). A
   transformer that throws is skipped (pi: keep the current markdown and
   continue). Returns a deregister fn."
  [transformer]
  (swap! markdown-transformers conj transformer)
  (fn [] (swap! markdown-transformers
                (fn [ts] (remove #(identical? % transformer) ts)))))

(defn get-markdown-transformers
  "Registered markdown transformers in registration order."
  []
  @markdown-transformers)

(defn apply-markdown-transformers
  "Apply the registered markdown transformers to MARKDOWN in registration
   order (pi: applyMarkdownTransformers); a transformer that throws is
   skipped and the chain continues with the current markdown. CTX:
   {:message-type :user|:assistant :is-streaming bool :available-width int}."
  [markdown ctx]
  (reduce (fn [acc t]
            (try
              (let [r (t acc ctx)]
                (if (string? r) r acc))
              (catch Exception _ acc)))
          markdown
          @markdown-transformers))

;; ─── CLI flags (extension api: register-flag! / get-flag) ─────────────────

(defn register-flag!
  "Register a CLI flag (extension api: register-flag!). NAME — without the
   leading --; opts: :type (:boolean|:string) :default. Returns a deregister
   fn."
  [name & [{:keys [type default]}]]
  (swap! flags assoc name {:type (or type :string) :default default})
  (fn [] (swap! flags dissoc name)))

(defn set-cli-flags! [flag-map]
  (reset! cli-flags (or flag-map {}))
  nil)

(defn get-flag
  "Value of a registered CLI flag: the argv value coerced by the registered
   :type, falling back to :default. nil for unregistered flags."
  [name]
  (let [{:keys [type default]} (get @flags name)]
    (when (contains? @flags name)
      (let [raw (get @cli-flags name)]
        (case type
          :boolean (let [v (if (nil? raw) default raw)]
                     (if (string? v)
                       (not (contains? #{"false" "0" ""} v))
                       (boolean v)))
          :string (or (when (string? raw) raw) default)
          raw)))))

;; ─── UI registry (the interactive installs live implementations) ─────────

(defn set-ui-registry! [registry]
  (reset! ui-registry registry)
  nil)

(defn clear-ui-registry! [] (reset! ui-registry {}) nil)

(defn ui-call
  "Dispatch a UI capability call through the registry. No-op (nil) when the
   interactive mode has not installed the registry yet (headless/print)."
  [capability & args]
  (when-let [f (get @ui-registry capability)]
    (apply f args)))

(declare api-session)

(defn- default-extension-context
  "Static context for headless/print mode (pi: ExtensionContext): the
   interactive :build-context capability overrides these per call. Every
   key is a value or zero-arg fn so handlers always receive a callable
   map. Built per call so it can reference the session facades (defined
   below) — the fns read the live session atom at call time."
  []
  {:mode :print
   :has-ui false
   :cwd (System/getProperty "user.dir")
   :model nil
   :scoped-models []
   :thinking-level nil
   :is-idle (fn [] true)
   :has-pending-messages (fn [] false)
   :signal (fn [] nil)
   :abort (fn [] nil)
   :shutdown (fn [] (System/exit 0))
   :get-context-usage (fn [] nil)
   :compact (fn [& _] nil)
   :get-system-prompt (fn [] nil)
   :get-system-prompt-options (fn [] nil)
   :wait-for-idle (fn [] nil)
   :reload (fn [] nil)
   :new-session (fn [& _] {:cancelled true})
   :fork (fn [& _] {:cancelled true})
   :navigate-tree (fn [& _] {:cancelled true})
   :switch-session (fn [& _] {:cancelled true})
   :is-project-trusted (fn [] false)
   ;; pi: ctx.sessionManager — read facades over the live session (set by
   ;; the interactive mode / headless tests via set-session!)
   :session (api-session)})

(defn build-extension-context
  "Build the extension context (pi: ExtensionContext) for the current
   runtime: the interactive mode's live :build-context capability merged
   over the headless default. Returns a fresh map per call — the fns
   capture live state at call time. Used for command handlers (replacing
   the internal CoreState leak) and event handler ctx args.

   Session control fns (new-session/fork/navigate-tree/switch-session/
   reload) run the interactive flows synchronously — call them from
   user-initiated command handlers, never from agent-loop event handlers
   (pi restricts these to the command ctx; they can re-enter the run loop)."
  []
  (merge (default-extension-context) (ui-call :build-context)))

(defn wrap-event-handler
  "Adapt an extension event handler to the bus: handlers always receive
   (event ctx) — pi parity, ctx built fresh per event. Fixed arity-2
   contract (no legacy shim): a handler that takes fewer args fails fast
   with an ArityException, which the bus logs as a handler error."
  [handler]
  (fn [event]
    (handler event (build-extension-context))))

(defn ui-notify [message & [type]] (ui-call :notify message type))
(defn ui-custom [factory & [opts]]
  (ui-call :custom factory opts))
(defn ui-chat-info [label content] (ui-call :chat-info label content))
(defn ui-on-terminal-input [handler] (ui-call :on-terminal-input handler))
(defn ui-set-status [key text] (ui-call :set-status key text))
(defn ui-set-widget [key content & [{:keys [placement]}]] (ui-call :set-widget key content {:placement placement}))
(defn ui-set-footer [factory] (ui-call :set-footer factory))
(defn ui-set-header [factory] (ui-call :set-header factory))
(defn ui-set-title [title] (ui-call :set-title title))
(defn ui-set-editor-text [text] (ui-call :set-editor-text text))
(defn ui-get-editor-text [] (ui-call :get-editor-text))
(defn ui-paste-to-editor [text] (ui-call :paste-to-editor text))
(defn ui-set-working-indicator [options] (ui-call :set-working-indicator options))
(defn ui-set-working-message [message] (ui-call :set-working-message message))
(defn ui-set-working-visible [visible?] (ui-call :set-working-visible visible?))
(defn ui-set-hidden-thinking-label [label] (ui-call :set-hidden-thinking-label label))
(defn ui-set-editor-component [factory] (ui-call :set-editor-component factory))
(defn ui-add-autocomplete-provider [factory] (ui-call :add-autocomplete-provider factory))
(defn ui-set-theme [theme-or-name] (ui-call :set-theme theme-or-name))
(defn ui-get-tools-expanded [] (ui-call :get-tools-expanded))
(defn ui-set-tools-expanded [expanded?] (ui-call :set-tools-expanded expanded?))
(defn ui-reset! [] (ui-call :reset))

;; ─── Agent control (dispatches through the ui registry; extension api) ───

(defn set-model [model] (ui-call :set-model model))
(defn get-thinking-level [] (ui-call :get-thinking-level))
(defn set-thinking-level [level] (ui-call :set-thinking-level level) nil)
(defn send-user-message
  "Extension api: send-user-message (pi: sendUserMessage) — send text to
   the agent. OPTIONS: {:deliver-as :steer | :follow-up (queue while
   streaming), :expand-prompt-templates? bool (pi:
   expandPromptTemplates — extension commands execute immediately and
   consume the message, then skill commands and prompt templates expand;
   kmet defaults to no expansion)."
  [text & [{:keys [deliver-as expand-prompt-templates?]}]]
  (ui-call :send-user-message text {:deliver-as deliver-as
                                    :expand-prompt-templates? expand-prompt-templates?})
  nil)

(declare append-custom-message!)

(defn send-message!
  "Extension api: send-message! (pi: sendMessage). MESSAGE:
   {:custom-type :content :display :details} — appended to the session as a
   custom_message entry (persisted), injected into the agent context (sent
   to the LLM as a user message; rendered in the chat when :display), and
   optionally triggering a turn. OPTIONS: {:trigger-turn bool :deliver-as
   :steer | :follow-up | :next-turn}. Idle + trigger-turn starts the run
   (the custom message is already in context); busy queues per deliver-as
   (:steer injects into the current run immediately, :follow-up/:next-turn
   defer to the next turn). Headless (no UI registry): persists + injects
   via the sinks. Returns nil."
  [message & [opts]]
  (if (nil? (ui-call :send-message! message opts))
    ;; headless fallback: persist + inject through the sinks
    (append-custom-message! (or (:custom-type message) :custom)
                            (:content message)
                            (if (nil? (:display message)) true (:display message))
                            (:details message))
    nil)
  nil)
(defn get-active-tools [] (ui-call :get-active-tools))
(defn set-active-tools [names] (ui-call :set-active-tools names) nil)

;; ─── Model / session facades (extension api: models / session) ───────────

(defn get-all-models [] (models/get-models))
(defn get-available-models [] (models/get-available))
(defn find-model [provider-id model-id] (models/get-model provider-id model-id))
(defn has-configured-auth [model] (models/has-configured-auth model))
(defn get-provider-auth-status [provider-id] (models/get-provider-auth-status provider-id))
(defn get-api-key-and-headers [model] (models/get-api-key-and-headers model))
(defn get-registered-provider-config [provider-id] (models/get-registered-provider-config provider-id))
(defn get-registered-provider-ids [] (models/get-registered-provider-ids))
(defn register-provider! [provider-id config] (models/register-provider-config! provider-id config))
(defn unregister-provider! [provider-id] (models/unregister-provider-config! provider-id))

(defn- exec
  "Execute a shell command and return {:exit n :out str :err str}
   (extension api: exec). Options: :dir :env :timeout-ms."
  [command args & [{:keys [dir env timeout-ms]}]]
  (let [p (proc/process (concat [command] args)
                        (cond-> {:out :string :err :string}
                          dir (assoc :dir dir)
                          env (assoc :env env)
                          timeout-ms (assoc :timeout timeout-ms)))]
    {:exit (:exit @p) :out (:out @p) :err (:err @p)}))

(defn set-session! [session] (reset! session-atom session) nil)
(defn get-session [] @session-atom)
(defn set-context-sink! [f] (reset! context-sink-atom f) nil)
(defn set-entry-sink! [f] (reset! entry-sink-atom f) nil)

(defn append-custom-entry!
  "Append a custom entry (extension state, never in LLM context) to the live
   session (extension api: session :append-entry!). Returns the entry id."
  [custom-type & [data]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-entry! sess custom-type data)]
      (when-let [sink @entry-sink-atom]
        (sink entry))
      (:id entry))))

(defn append-custom-message!
  "Append a custom message that participates in LLM context (extension api:
   session :append-message!). Returns the entry id."
  [custom-type content display & [details]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-message-entry! sess custom-type
                                                      content display details)]
      (when-let [sink @context-sink-atom]
        (sink {:role :custom
               :custom-type custom-type
               :content content
               :display display
               :details details}))
      (:id entry))))

(defn get-custom-entries [custom-type]
  (if-let [sess @session-atom]
    (session/get-custom-entries sess custom-type)
    []))

(defn get-branch-entries
  ([]
   (if-let [sess @session-atom]
     (session/get-branch sess)
     []))
  ([from-id]
   (if-let [sess @session-atom]
     (session/get-branch sess from-id)
     [])))

(defn get-leaf-id []
  (when-let [sess @session-atom]
    @(:leaf-id sess)))

(defn get-entry [entry-id]
  (when-let [sess @session-atom]
    (session/get-entry sess entry-id)))

(defn set-label! [entry-id label]
  (when-let [sess @session-atom]
    (session/set-label! sess entry-id label)))

(defn get-label [entry-id]
  (when-let [sess @session-atom]
    (session/get-label sess entry-id)))

;; ─── Extension API construction ──────────────────────────────────────────

(defn- track-deregister!
  "Record a deregister fn on the extension; unload runs them all."
  [ext f]
  (swap! (:deregister-fns ext) conj f))

(defn- api-ui
  "The :ui capability map — dispatches through the runtime registry, so it
   is inert before the layout exists and in headless mode. Only host-owned
   bridges live here: mounting extension-built components (:custom), the
   flash (:notify), chat-history info messages (:chat-info), and
   integrations with host layout/editor/status state. Extensions build
   their own components with the shared kmet.tui.* layer
   (pi: ctx.ui.custom hosting extension-built pi-tui components) — the api
   carries no host-built dialogs or theme lookups (kmet.tui.theme is
   shared directly)."
  []
  {:notify ui-notify
   :custom ui-custom
   :chat-info ui-chat-info
   :on-terminal-input ui-on-terminal-input
   :set-status ui-set-status
   :set-widget ui-set-widget
   :set-footer ui-set-footer
   :set-header ui-set-header
   :set-title ui-set-title
   :set-editor-text ui-set-editor-text
   :get-editor-text ui-get-editor-text
   :paste-to-editor ui-paste-to-editor
   :set-working-indicator ui-set-working-indicator
   :set-working-message ui-set-working-message
   :set-working-visible ui-set-working-visible
   :set-hidden-thinking-label ui-set-hidden-thinking-label
   :set-editor-component ui-set-editor-component
   :add-autocomplete-provider ui-add-autocomplete-provider
   :set-theme ui-set-theme
   :get-tools-expanded ui-get-tools-expanded
   :set-tools-expanded ui-set-tools-expanded})

(defn- api-models
  "The :models capability map — ctx.models facades. TRACK records deregister
   fns so unload removes exactly what this extension added (provider
   registrations leak across unload/reload otherwise — they live in the
   global extension-providers atom, unlike ephemeral UI state which
   reload resets in bulk)."
  [track]
  {:get-all get-all-models
   :get-available get-available-models
   :find find-model
   :has-configured-auth has-configured-auth
   :get-provider-auth-status get-provider-auth-status
   :get-api-key-and-headers get-api-key-and-headers
   :get-registered-provider-config get-registered-provider-config
   :get-registered-provider-ids get-registered-provider-ids
   :register-provider! (fn [provider-id config]
                         (let [result (register-provider! provider-id config)]
                           ;; track only after a successful register — a broken
                           ;; config throws without touching stored state
                           (track (fn [] (unregister-provider! provider-id)))
                           result))
   :unregister-provider! unregister-provider!})

(defn- api-session
  "The :session capability map — live session facades (pi:
   ctx.sessionManager — getBranch/getLeafId/getEntry included)."
  []
  {:append-entry! append-custom-entry!
   :append-message! append-custom-message!
   :get-entries get-custom-entries
   :get-branch get-branch-entries
   :get-leaf-id get-leaf-id
   :get-entry get-entry
   :set-label! set-label!
   :get-label get-label
   :set-name! (fn [name]
                (when-let [sess @session-atom]
                  (session/append-session-info! sess (session/sanitize-session-name name))))
   :get-name (fn [] (when-let [sess @session-atom] (session/get-session-name sess)))})

(defn- create-extension-api
  "Build the api map for an extension. Every registration records its
   deregister fn so unload removes exactly what this extension added."
  [ext]
  (let [track (fn [f] (track-deregister! ext f) f)
        name (:name ext)]
    {:extension-name name
     :extension-path (:path ext)
     ;; the extension's own directory (dir ext = the dir itself, file ext
     ;; = the file's parent) — nil for jar extensions, which have no
     ;; directory (use io/resource instead)
     :extension-dir (extension-dir-of ext)
     ;; host agent dir (KMET_CODING_AGENT_DIR-aware; pi: getAgentDir) —
     ;; extensions keep their configs/caches under it
     :agent-dir (cfg/get-agent-dir)
     :register-command! (fn [cmd]
                          ;; the handler is stored under :extension-handler so
                          ;; the runner can pass it the extension context
                          ;; (pi: handler(args, ctx)) while builtin commands
                          ;; keep receiving CoreState
                          (let [cmd (assoc cmd :extension-handler (:handler cmd))]
                            (commands/register-command! cmd)
                            (track (fn [] (commands/unregister-command! (:name cmd))))))
     :unregister-command! commands/unregister-command!
     ;; sanitized — other extensions must not see :handler/:extension-handler
     :get-commands #(mapv (fn [c] (select-keys c [:name :description]))
                          (commands/get-commands))
     :register-tool! (fn [tool]
                       (tools/register-tool! tool)
                       (track (fn [] (tools/unregister-tool! (:name tool)))))
     :unregister-tool! tools/unregister-tool!
     :get-all-tools #(vals (tools/get-all-tools))
     :get-active-tools get-active-tools
     :set-active-tools set-active-tools
     :on-event (fn [event-type handler]
                 (let [dereg (event-bus/on-event event-type
                                                 (wrap-event-handler handler))]
                   (track dereg)
                   dereg))
     :emit-event! event-bus/emit-event!
     :on-input (fn [hook]
                 (register-input-hook! hook)
                 (track (fn [] (swap! input-hooks
                                      (fn [hs] (remove #(identical? % hook) hs))))))
     :on-before-agent-start (fn [hook]
                              (register-before-agent-start-hook! hook)
                              (track (fn [] (swap! before-agent-start-hooks
                                                   (fn [hs] (remove #(identical? % hook) hs))))))
     :on-tool-call (fn [hook]
                     (register-tool-call-hook! hook)
                     (track (fn [] (swap! tool-call-hooks
                                          (fn [hs] (remove #(identical? % hook) hs))))))
     :on-tool-result (fn [hook]
                       (register-tool-result-hook! hook)
                       (track (fn [] (swap! tool-result-hooks
                                            (fn [hs] (remove #(identical? % hook) hs))))))
     :register-flag! (fn [flag-name & [opts]]
                       (register-flag! flag-name opts)
                       (track (fn [] (swap! flags dissoc flag-name))))
     :get-flag get-flag
     :register-shortcut! (fn [key-id & [opts]]
                           (let [dereg (register-shortcut! key-id opts)]
                             (track dereg)
                             dereg))
     :register-markdown-transformer! (fn [transformer]
                                       (let [dereg (register-markdown-transformer! transformer)]
                                         (track dereg)
                                         dereg))
     :register-entry-renderer! (fn [custom-type renderer]
                                 (register-entry-renderer! custom-type renderer)
                                 (track (fn [] (swap! entry-renderers dissoc custom-type))))
     :register-message-renderer! (fn [custom-type renderer]
                                   (register-message-renderer! custom-type renderer)
                                   (track (fn [] (swap! message-renderers dissoc custom-type))))
     :register-skill! (fn [raw-content & [opts]]
                        ;; jar-ext.md §5: the extension reads its own bundled
                        ;; SKILL.md via io/resource and hands the content over,
                        ;; so jarred skills need no filesystem path
                        (let [dereg (skills/register-extension-skill!
                                     raw-content
                                     (assoc opts :extension name))]
                          (track dereg)
                          dereg))
     :register-prompt! (fn [prompt & [opts]]
                         (let [dereg (prompts/register-prompt-template!
                                      (assoc (merge opts prompt) :extension name))]
                           (track dereg)
                           dereg))
     :set-model set-model
     :get-thinking-level get-thinking-level
     :set-thinking-level set-thinking-level
     :send-user-message send-user-message
     :send-message! send-message!
     :exec exec
     :ui (api-ui)
     :models (api-models track)
     :session (api-session)}))

;; ─── Isolated extension contexts (sci) ───────────────────────────────────
;; Each extension evaluates inside its own sci context: a private namespace
;; registry plus a per-extension loader that serves (1) the extension's own
;; files, (2) the jars its deps.edn declares (the complete transitive
;; closure, resolved in-process via borkdude.deps), and (3) anything else on
;; the classpath. Global namespaces the extension may touch — kmet.extension
;; (the contract), clojure.*, babashka.*, and the shared library layers
;; kmet.tui.* (pi: @earendil-works/pi-tui) and kmet.libs.* (generic
;; self-contained utilities) — are injected as shared references, never
;; re-evaluated, so kmet's registries and protocols are not duplicated.
;; kmet.* namespaces outside that set (app internals) are not served at
;; all: re-evaluating them in a context would create context-local
;; copies of their registries, and sharing them would break the layer
;; boundary.

(declare unload-extension!)

(def ^:private bb-imports
  "babashka's default imports (babashka.impl.classes/imports): the
   unqualified classnames lib sources may use."
  '{AbstractMethodError java.lang.AbstractMethodError
    Appendable java.lang.Appendable
    ArithmeticException java.lang.ArithmeticException
    AssertionError java.lang.AssertionError
    BigDecimal java.math.BigDecimal
    BigInteger java.math.BigInteger
    Boolean java.lang.Boolean
    Byte java.lang.Byte
    Callable java.util.concurrent.Callable
    Character java.lang.Character
    CharSequence java.lang.CharSequence
    Class java.lang.Class
    ClassCastException java.lang.ClassCastException
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Compiler clojure.lang.Compiler
    Double java.lang.Double
    Error java.lang.Error
    Exception java.lang.Exception
    ExceptionInInitializerError java.lang.ExceptionInInitializerError
    IndexOutOfBoundsException java.lang.IndexOutOfBoundsException
    IllegalArgumentException java.lang.IllegalArgumentException
    IllegalStateException java.lang.IllegalStateException
    Integer java.lang.Integer
    InterruptedException java.lang.InterruptedException
    Iterable java.lang.Iterable
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    LinkageError java.lang.LinkageError
    Math java.lang.Math
    NullPointerException java.lang.NullPointerException
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runnable java.lang.Runnable
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    SecurityException java.lang.SecurityException
    Short java.lang.Short
    StackOverflowError java.lang.StackOverflowError
    StackTraceElement java.lang.StackTraceElement
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    ThreadLocal java.lang.ThreadLocal
    Thread$UncaughtExceptionHandler java.lang.Thread$UncaughtExceptionHandler
    Throwable java.lang.Throwable
    VirtualMachineError java.lang.VirtualMachineError
    ThreadDeath java.lang.ThreadDeath
    UnsupportedOperationException java.lang.UnsupportedOperationException})

(def ^:private bb-bundled-libs
  "Libraries babashka ships adapted (SCI implementations baked into the
   binary) whose raw Maven versions generally fail in bb. Extensions should
   omit them from deps.edn and use the bundled copy. Plain-bundled libs
   whose Maven copies run fine (tools.cli, data.json, ...) are not listed —
   they resolve to declared versions normally."
  #{"org.clojure/core.async"
    "org.clojure/core.cache"
    "org.clojure/core.memoize"
    "org.clojure/core.rrb-vector"
    "potemkin/potemkin"
    "rewrite-clj/rewrite-clj"
    "borkdude/edamame"
    "ring/ring-core"
    "selmer/selmer"})

(def ^:private bundled-port-namespaces
  "bb-bundled namespaces that are reduced custom ports, not the Maven
   sources: bb pre-loads them and serves them under its own require, and
   the raw Maven copies fail under SCI (tools.reader 1.3+ has deftypes
   implementing the java.io.Closeable interface, which SCI's deftype
   rejects). Injected by reference like the adapted libs — a declared
   Maven version cannot win for these, because it would not evaluate."
  '#{clojure.tools.reader
     clojure.tools.reader.edn
     clojure.tools.reader.reader-types})

(def ^:private bb-shared-namespaces
  "bb pre-loads these adapted-lib namespaces at startup (rewrite-clj ports,
    edamame, the data.xml family) and their Maven copies cannot run under
    SCI: rewrite-clj/edamame require clojure.tools.reader.impl.* (impl.inspect
    dispatches on the removed PersistentArrayMap$Seq class), and data.xml's
    copy uses definline (unsupported by SCI). Injected by reference into
    extension contexts like the custom ports, so extensions resolving them
    get the bundled copy and must not declare the Maven libs in deps.edn."
  '#{rewrite-clj.node
     rewrite-clj.parser
     rewrite-clj.paredit
     rewrite-clj.zip
     rewrite-clj.zip.subedit
     edamame.core
     clojure.data.xml})

(defn- jolt?
  "True on the Jolt host (runtime check, not a reader conditional — the
   SCI context options below are identical on both hosts except :features)."
  []
  (boolean (find-var 'clojure.core/*jolt-version*)))

(def ^:private missing-classname-re
  "Matches SCI's analysis error for an unregistered class:
   `Unable to resolve classname: fq.Name`."
  #"Unable to resolve classname: (\S+)")

(def ^:private missing-symbol-re
  "Matches SCI's analysis error for an unresolvable qualified symbol,
   `Unable to resolve symbol: Qualifier/member`, where the qualifier may
   itself be dotted: short-name statics surface here (`Thread/sleep` —
   the analyzer resolves the qualifier through :imports before it ever
   consults :classes), and so do BARE fully-qualified class names
   (`java.net.http.HttpTimeoutException` alone, as opposed to
   `fq.Name/member`), which carry no member part at all."
  #"Unable to resolve symbol: ([^/\s]+?)(?:/\S+)?$")

(defn- try-add-class!
  "Class/forName FQ-NAME and register it on CTX via sci/add-class!. True
   on success; false when the host cannot load the name (ClassNotFound,
   or a runtime-registered provider class like MessageDigest on Jolt).
   Both hosts load ordinary JDK classes by name; factory-made runtime
   classes (MessageDigest delegates) are NOT forName-loadable and stay on
   the explicit runtime-classes fallback below."
  [ctx fq-name]
  (try
    (sci/add-class! ctx (symbol fq-name) (Class/forName ^String fq-name))
    true
    (catch Throwable _ false)))

(defn- try-add-class-short!
  "Register a bb-imports short name (e.g. StringBuilder) as well as its
   FQ name. SCI resolves a SHORT type hint (`^StringBuilder`) through
   class->opts by the short symbol, falling back to the env :imports map
   — a fallback that is host-dependent. Registering both keys makes
   short hints resolve identically on bb and Jolt. Returns true when
   either registration succeeded."
  [ctx short-sym fq-name]
  (let [fq-ok? (try-add-class! ctx fq-name)]
    (when-let [^Class c (try (Class/forName ^String fq-name) (catch Throwable _ nil))]
      (try (sci/add-class! ctx short-sym c) (catch Throwable _ nil)))
    fq-ok?))

(def ^:private runtime-classes
  "Live Class objects for classes Class/forName cannot load: JDK factory
   methods return internal wrappers (e.g. MessageDigest/getInstance
   returns a $Delegate$CloneableDelegate) whose names are not loadable.
   Seeded per context after the bb-imports sweep (try-add-class! covers
   every ordinary class). Extend when another factory-made class is
   needed — ordinary classes need no entry here."
  (delay [(class (java.security.MessageDigest/getInstance "SHA-256"))
          (.getSuperclass (class (java.security.MessageDigest/getInstance "SHA-256")))]))

(def ^:private tui-library-namespaces
  "The generic TUI layer and the supported app-level tool renderers shared
   with extension contexts. Required once before per-extension contexts are
   built; injected by reference so component and protocol identity is shared."
  '[kmet.tui.autocomplete
    kmet.tui.border
    kmet.tui.core
    kmet.tui.fuzzy
    kmet.tui.hiccup
    kmet.tui.keybindings
    kmet.tui.keys
    kmet.tui.macros
    kmet.tui.protocols
    kmet.tui.theme
    kmet.tui.timers
    kmet.tui.utils
    kmet.tui.components.alt-screen-flash
    kmet.tui.components.box
    kmet.tui.components.cancellable-loader
    kmet.tui.components.container
    kmet.tui.components.dynamic-border
    kmet.tui.components.editing
    kmet.tui.components.editor
    kmet.tui.components.expandable-text
    kmet.tui.components.h-stack
    kmet.tui.components.image
    kmet.tui.components.input
    kmet.tui.components.markdown
    kmet.tui.components.scroll-view
    kmet.tui.components.select-list
    kmet.tui.components.settings-list
    kmet.tui.components.spacer
    kmet.tui.components.spinner
    kmet.tui.components.stack
    kmet.tui.components.text
    kmet.tui.components.v-stack
    kmet.tui.components.truncated-text
    kmet.app.ui.tool-renderers
    kmet.app.keybindings])

(def ^:private libs-library-namespaces
  "The generic kmet.libs.* layer shared with extension contexts. Every lib
   is self-contained (enforced by kmet.libs.test-self-contained), so the
   whole prefix is whitelisted. Required once here so they exist when a
   per-extension context is built — injection is by reference, never
   re-evaluated, so any protocols they define keep their identity. Keep in
   sync with src/kmet/libs/ when a lib is added or removed."
  '[kmet.libs.archive
    kmet.libs.aws-sigv4
    kmet.libs.clipboard
    kmet.libs.concurrent
    kmet.libs.context
    kmet.libs.crypto
    kmet.libs.diff
    kmet.libs.dynamic-value
    kmet.libs.edit-diff
    kmet.libs.edn-store
    kmet.libs.edn-writer
    kmet.libs.hash
    kmet.libs.highlight
    kmet.libs.hooks
    kmet.libs.host
    kmet.libs.http
    kmet.libs.json
    kmet.libs.jsonrpc
    kmet.libs.markdown
    kmet.libs.num
    kmet.libs.oauth
    kmet.libs.process
    kmet.libs.reakt
    kmet.libs.sse
    kmet.libs.terminal
    kmet.libs.terminal-image
    kmet.libs.usage
    kmet.libs.yaml])

(defn- ns-path
  "The classpath path for NS-SYM: namespace-munged (dashes → underscores),
   dots as slashes — matching how jars and source dirs store files."
  [ns-sym]
  (str/replace (namespace-munge (str ns-sym)) "." "/"))

(def ^:private source-extensions
  "Source file suffixes probed (in order) for a strict ns-path lookup."
  [".cljc" ".clj" ".bb"])

(defn- entry-name-ok?
  "True when RAW (a jar entry name) is a safe relative path: not absolute,
   no .. segments. Normalizes \\ → / first (the zip spec allows both;
   mirrors kmet.libs.archive/entry-target)."
  [raw]
  (let [rel (str/replace (str raw) "\\" "/")]
    (not (or (str/blank? rel)
             (str/starts-with? rel "/")
             (some #(= ".." %) (str/split rel #"/"))))))

(defn- jar-entry-source
  "The source string of ENTRY-NAME inside the zip at JAR-PATH, or nil.
   Opens and closes the ZipFile per call — no handles are held, so unload
   needs no cleanup."
  [jar-path entry-name]
  (let [jar (java.util.jar.JarFile. (str jar-path))]
    (try
      (when-let [entry (.getJarEntry jar ^String entry-name)]
        (when-not (.isDirectory entry)
          (with-open [is (.getInputStream jar entry)]
            (slurp is))))
      (finally (.close jar)))))

(defn- jar-entry-names
  "The set of safe relative entry names in the zip at JAR-PATH."
  [jar-path]
  (let [jar (java.util.jar.JarFile. (str jar-path))]
    (try
      (into #{}
            (comp (map (fn [^java.util.jar.JarEntry e] (.getName e)))
                  (map #(str/replace % "\\" "/"))
                  (filter entry-name-ok?))
            (enumeration-seq (.entries jar)))
      (finally (.close jar)))))

(defn- jar-namespaces
  "The entry-name set + namespace-symbol set of the jar at JAR-PATH,
   collected once at load: {:entries #{...} :namespaces #{...}}. Namespace
   symbols reverse-map from the safe .clj/.cljc/.bb entry names."
  [jar-path]
  (let [entries (jar-entry-names jar-path)]
    {:entries entries
     :namespaces (into #{}
                       (comp (filter #(re-find #"\.(cljc|clj|bb)$" %))
                             (map #(str/replace % #"\.(cljc|clj|bb)$" ""))
                             (map #(str/replace % "_" "-"))
                             (map #(str/replace % "/" "."))
                             (map symbol))
                       entries)}))

(defn- artifact-source
  "The source of NS-SYM in ARTIFACT ({:kind :dir/:jar :root}), or nil.
   Strict ns-path lookup: dirs probe <root>/<ns-path>.<ext> (direct fs
   probes need no follow-links handling — symlinked roots resolve through
   the fs); jars probe entry names through a per-call ZipFile. Returns
   {:source :display}."
  [{:keys [kind root]} ns-sym]
  (let [base (ns-path ns-sym)]
    (if (= :jar kind)
      (some (fn [ext]
              (when-let [source (jar-entry-source root (str base ext))]
                {:source source :display (str root "!/" base ext)}))
            source-extensions)
      (some (fn [ext]
              (let [f (io/file (str root) (str base ext))]
                (when (.exists f)
                  {:source (slurp f) :display (str f)})))
            source-extensions))))

(defn- artifact-owns-ns?
  "True when NS-SYM is one of ARTIFACT's own namespaces. Strict layout, so
   membership derives from paths, never file contents: dirs probe the fs,
   jars check JAR-INFO (the entry/namespace sets collected once at load;
   nil for dirs)."
  [{:keys [kind root]} jar-info ns-sym]
  (if (= :jar kind)
    (contains? (:namespaces jar-info) ns-sym)
    (boolean (some (fn [ext]
                     (.exists (io/file (str root) (str (ns-path ns-sym) ext))))
                   source-extensions))))

(defn- deps-of-root
  "The :deps map from deps.edn under DIR (an artifact-root dir file), or nil."
  [dir]
  (let [f (io/file (str dir) "deps.edn")]
    (when (.exists f)
      (:deps (edn/read-string (slurp f))))))

(defn- extension-resource-fn
  "A clojure.java.io/resource replacement scoped to one extension artifact:
   own artifact first (dir: file URL when present; jar: jar:file:...!/entry
   URL when the entry exists), then the deps.edn closure jars, then the
   host classpath. Both arities ([path] [path loader] — the loader is
   ignored). Host slurp opens file: and jar: URLs via openStream, so
   extension code reads bundled resources with no extraction. JAR-INFO is
   the jar-namespaces map collected once at load (nil for dirs) — the zip
   is never re-enumerated per lookup."
  [artifact jar-info deps-resolver]
  (let [host-resource (deref #'clojure.java.io/resource)
        own (fn [rel]
              (let [{:keys [kind root]} artifact]
                (if (= :jar kind)
                  (when (contains? (:entries jar-info) rel)
                    (java.net.URL. (str "jar:" (.toURL (.toURI (io/file root))) "!/" rel)))
                  (let [f (io/file (str root) rel)]
                    (when (.exists f)
                      (io/as-url f))))))]
    (letfn [(find-it [path]
              (let [rel (str path)]
                (or (own rel)
                    (some (fn [entry]
                            (if (fs/directory? (str entry))
                              ;; jolt: dep roots are extracted source dirs
                              (let [f (io/file (str entry) rel)]
                                (when (.exists f) (io/as-url f)))
                              (when (jar-entry-source entry rel)
                                (java.net.URL. (str "jar:" (.toURL (.toURI (io/file entry))) "!/" rel)))))
                          (when deps-resolver (deps-resolver)))
                    (host-resource rel))))]
      (fn
        ([path] (find-it path))
        ([path _loader] (find-it path))))))

(defn- shared-var-map
  "The SCI namespace map for one host namespace: every public var copied
   to a sci.lang.Var via sci/copy-var* (ns-interns, not ns-publics —
   some load-bearing vars are private, e.g.
   clojure.spec.alpha/check-spec-asserts, and extensions resolve
   against the full interns surface anyway).
   Host Var objects cannot cross: bb's SCI namespaces already hold
   sci.lang.Vars (ns-interns works there by accident), but Jolt
   namespaces hold clojure.lang.Vars and SCI's analyzer calls
   vars/isMacro on the value (`No method isMacro` otherwise). copy-var*
   preserves :macro/:arglists/:doc metadata (macros keep expanding) and
   the live root (atoms stay deref'able, fns stay callable). Never
   deref: a deref'd fn loses macro metadata and a deref'd atom loses its
   identity."
  [ns-sym]
  (let [sci-ns (sci/create-ns ns-sym)]
    (into {}
          (keep (fn [[k v]]
                  ;; bb's clojure.repl/print-doc is a future, not a var —
                  ;; deref would block-then-cast. Only Vars cross.
                  (when (var? v)
                    [k (sci/copy-var* v sci-ns)])))
          (ns-interns ns-sym))))

(defn- build-context-namespaces
  "The shared namespace map for extension contexts: kmet.extension (the
   contract), the clojure.*/babashka.* builtins (incl. slurp/spit, which
   SCI's builtin clojure.core lacks but bb's env has), and the shared
   library layers kmet.tui.* and kmet.libs.*. Rebuilt per context so
   namespaces required since the last build (the shared library layers)
   are included. RESOURCE-FN replaces clojure.java.io/resource with a
   per-extension artifact-scoped lookup (io/resource shadowing — see
   extension-resource-fn). Values are deref'd (see shared-var-map): host
   Var objects cannot enter a SCI context."
  [& [resource-fn]]
  (into {'kmet.extension (shared-var-map 'kmet.extension)
         ;; slurp/spit/file-seq are absent from SCI's builtin clojure.core —
         ;; inject the host fns so extensions can read/write files directly
         ;; (the mcp-adapter used to work around this with babashka.fs
         ;; read-all-lines/write-bytes; file-seq is needed by libs such as
         ;; cljfmt.io's FileEntity protocol). sci merges these into its core.
         'clojure.core {'slurp (deref #'slurp)
                        'spit (deref #'spit)
                        'file-seq (deref #'file-seq)}}
        (keep (fn [ns-obj]
                (let [n (str (ns-name ns-obj))]
                  (when (and (not (str/starts-with? n "sci."))
                             (not= n "clojure.core")
                             ;; bundled libraries whose Maven versions run under
                             ;; SCI (clojure.tools.cli, data.json, data.csv,
                             ;; ...) are NOT injected — they resolve through
                             ;; the load-fn, so a declared Maven version wins
                             ;; over the bundled copy. The adapted libs
                             ;; (core.async, data.json, ...), the custom ports
                             ;; (bundled-port-namespaces), bb-shared-namespaces
                             ;; and the data.xml family stay injected: their
                             ;; Maven copies fail under SCI (data.xml uses
                             ;; definline), so the bundled copy is the only
                             ;; working one.
                             (not (or (and (str/starts-with? n "clojure.data.")
                                           (not (or (= n "clojure.data.xml")
                                                    (str/starts-with? n
                                                                      "clojure.data.xml."))))
                                      (and (str/starts-with? n "clojure.tools.")
                                           (not (contains? bundled-port-namespaces
                                                           (ns-name ns-obj))))))
                             (or (str/starts-with? n "clojure.")
                                 (str/starts-with? n "babashka.")
                                 (contains? bb-shared-namespaces (ns-name ns-obj))
                                 (str/starts-with? n "kmet.tui.")
                                 (= n "kmet.app.ui.tool-renderers")
                                 (= n "kmet.app.keybindings")
                                 (str/starts-with? n "kmet.libs.")))
                    [(ns-name ns-obj)
                     (if (and (= n "clojure.java.io") resource-fn)
                       (assoc (shared-var-map (ns-name ns-obj)) 'resource resource-fn)
                       (shared-var-map (ns-name ns-obj)))])))
              (all-ns))))

(defn- ns-form-of-source
  "The (ns ...) form at the start of the SOURCE string, or nil when there
   is none (or it can't be read)."
  [source]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader (.getBytes ^String source "UTF-8")))]
      (let [form (read rdr)]
        (when (and (list? form) (= 'ns (first form)))
          form)))
    (catch Exception _ nil)))

(defn- jar-archive?
  "True when F is a regular .jar/.zip file path."
  [f path]
  (and (fs/regular-file? f)
       (let [lower (str/lower-case (str path))]
         (or (str/ends-with? lower ".jar")
             (str/ends-with? lower ".zip")))))

(defn- temp-root
  "The platform temp dir for build-time caches: $TMPDIR first (Termux has
   no /tmp; java.io.tmpdir is unreliable on babashka), else java.io.tmpdir."
  []
  (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(def ^:private jar-cache-root
  (delay (str (fs/path (temp-root) "kmet-ext-jars"))))

(defn- materialize-jar!
  "Extract the jar/zip at PATH into a cached directory and return it. Jolt
   has no java.util.zip (kmet.libs.archive is bb-only), so jar artifacts
   are materialized with the host unzip and then treated as DIRECTORY
   artifacts — code, extension.edn, deps.edn and io/resource all use the
   ordinary fs path. The cache is keyed by jar path + mtime, so a changed
   jar re-extracts and reloads reuse the extraction. bb keeps the
   unexpanded ZipFile path (jar-ext.md §1)."
  [path]
  (let [root (str (fs/path @jar-cache-root
                           (str (fs/file-name (str path)) "-"
                                (fs/last-modified-time (str path)))))
        marker (str (fs/path root ".ok"))]
    (when-not (fs/exists? marker)
      (fs/create-dirs root)
      (let [{:keys [exit err]} (proc/sh ["unzip" "-o" "-q" (str path) "-d" root])]
        (when-not (zero? exit)
          (throw (ex-info (str "failed to extract extension archive " path
                               ": " (str/trim (str err)))
                          {:path path}))))
      (spit marker ""))
    root))

(defn- resolve-extension
  "Resolve PATH into {:name str :kind :file/:dir/:jar :artifact map-or-nil
   :entry-ns symbol-or-nil :file io.File}. :artifact ({:kind :dir/:jar
   :root str}) is the strict-layout root for manifest extensions; :entry-ns
   is the manifest :entry symbol. A directory must contain extension.edn;
   a jar must carry it at its root. A plain file is the entry itself
   (:entry-ns nil — its ns is read from the file at load)."
  [path]
  (let [f (io/file path)]
    (cond
      (jar-archive? f path)
      (let [unexpanded? (not (jolt?))
            entries (when unexpanded? (jar-entry-names (str f)))]
        ;; jolt materializes the archive to a directory (no java.util.zip) and
        ;; continues as a :dir artifact; bb keeps the per-call ZipFile path.
        (when (and unexpanded? (not (contains? entries "extension.edn")))
          (throw (ex-info (str "Extension archive " path " has no extension.edn")
                          {:path path})))
        (let [root (if unexpanded? (str f) (materialize-jar! (str f)))
              manifest (if unexpanded?
                         (jar-entry-source root "extension.edn")
                         (let [mf (io/file root "extension.edn")]
                           (when (.exists mf) (slurp mf))))
              _ (when-not manifest
                  (throw (ex-info (str "Extension archive " path " has no extension.edn")
                                  {:path path})))
              m (edn/read-string manifest)
              entry-ns (:entry m)]
          (when-not (symbol? entry-ns)
            (throw (ex-info (str "extension.edn :entry must be a namespace symbol, got: "
                                 (pr-str entry-ns))
                            {:path path :manifest m})))
          {:name (or (:name m) (fs/file-name f))
           :kind :jar
           :artifact {:kind (if unexpanded? :jar :dir) :root root}
           :entry-ns entry-ns}))

      (.isDirectory f)
      (let [manifest-file (io/file f "extension.edn")]
        (when-not (.exists manifest-file)
          (throw (ex-info (str "Extension dir " path " has no extension.edn")
                          {:path path})))
        (let [m (edn/read-string (slurp manifest-file))
              entry-ns (:entry m)]
          (when-not (symbol? entry-ns)
            (throw (ex-info (str "extension.edn :entry must be a namespace symbol, got: "
                                 (pr-str entry-ns))
                            {:path path :manifest m})))
          {:name (or (:name m) (fs/file-name f))
           :kind :dir
           :artifact {:kind :dir :root (str f)}
           :entry-ns entry-ns}))

      :else
      {:name (fs/file-name f)
       :kind :file
       :artifact nil
       :entry-ns nil
       :file f})))

(defn- ns-clause
  "The (:require ...) / (:use ...) / (:require-macros ...) reference form of
   an ns form, or nil (ns forms: (ns name docstring? attr-map? & refs))."
  [ns-form clause-key]
  (some #(when (and (seq? %) (= clause-key (first %))) %)
        (filter #(not (or (string? %) (map? %))) (nnext ns-form))))

(defn- require-libspec-libs
  "The library symbols of an ns :require / :require-macros / :use clause
   (each libspec is a bare symbol or a [lib ...] vector)."
  [clause]
  (keep (fn [spec]
          (cond
            (symbol? spec) spec
            (and (vector? spec) (seq spec) (symbol? (first spec))) (first spec)
            :else nil))
        clause))

(defn- validate-entry-requires!
  "Fail fast with an actionable error when NS-FORM (the entry ns form, or
   any internal extension ns form validated by the load-fn) requires a
   kmet.* namespace outside the shared set (kmet.extension + kmet.tui.* +
   kmet.libs.*) or the extension's own internal namespaces (OWNS-NS?, a
   path-derived predicate — those resolve regardless of their prefix),
   or requires babashka.http-client directly (outbound HTTP must
   go through kmet.libs.http). Without this the error would be silent:
   sci's require machinery NPEs on a load-fn failure and swallows the
   original exception."
  [ext-name ns-form tui-namespaces libs-namespaces owns-ns?]
  ;; NOTE: :refer [defcomponent] (a macro referred without a namespaced
  ;; use) leaves no libspec — the ns symbol never appears. SCI resolves
  ;; referred macros through the already-required ns, so validation only
  ;; needs the :require entries; nothing extra to check here.
  (doseq [clause-key [:require :require-macros :use]
          lib (require-libspec-libs (ns-clause ns-form clause-key))]
    (let [s (str lib)]
      (cond
        ;; the extension's own internal namespace — always resolvable
        ;; (strict layout: membership derives from paths, not contents)
        (owns-ns? lib) nil
        (str/starts-with? s "kmet.tui.")
        ;; tui-namespaces covers what is loaded NOW; the library-root list
        ;; covers what CAN be shared (lazily-loaded internal nss validate
        ;; before their requires are loaded — e.g. review/dialogs needing
        ;; kmet.tui.macros on second load).
        (when-not (or (contains? tui-namespaces lib)
                      (contains? (set tui-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.tui.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        ;; the shared set is the injection allowlist PLUS the full library
        ;; roots: tui-namespaces/libs-namespaces only cover namespaces
        ;; loaded at context-build time, but a lazily-loaded internal ns
        ;; (e.g. review/dialogs requiring kmet.tui.macros on second load)
        ;; must validate against what CAN be shared, not what happens to
        ;; be loaded. The load-fn serves kmet.tui.* from the host anyway.
        (#{"kmet.app.ui.tool-renderers" "kmet.app.keybindings"} s)
        (when-not (or (contains? tui-namespaces lib)
                      (contains? (set tui-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the shared renderer library surface")
                  {:extension ext-name :ns lib})))

        (str/starts-with? s "kmet.libs.")
        (when-not (or (contains? libs-namespaces lib)
                      (contains? (set libs-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.libs.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        ;; direct outbound HTTP is not available to extensions — the
        ;; proxy-aware kmet.libs.http boundary is shared by reference
        (= s "babashka.http-client")
        (throw (ex-info
                (str "Extension " ext-name " requires babashka.http-client"
                     " — extensions must use kmet.libs.http (the proxy-aware"
                     " outbound-HTTP boundary) instead")
                {:extension ext-name :ns lib}))

        (and (str/starts-with? s "kmet.")
             (not= lib 'kmet.extension))
        (throw (ex-info
                (str "Extension " ext-name " requires " lib
                     " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")
                {:extension ext-name :ns lib}))))))

(defn- shared-tui-namespaces
  "The set of TUI and supported renderer namespace symbols currently loaded
   (what the context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (let [n (str (ns-name ns-obj))]
                 (when (or (str/starts-with? n "kmet.tui.")
                           (= n "kmet.app.ui.tool-renderers")
                           (= n "kmet.app.keybindings"))
                   (ns-name ns-obj))))
             (all-ns))))

(defn- shared-libs-namespaces
  "The set of kmet.libs.* namespace symbols currently loaded (what the
   context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (let [n (str (ns-name ns-obj))]
                 (when (str/starts-with? n "kmet.libs.")
                   (ns-name ns-obj))))
             (all-ns))))

#?(:jolt
   (defn- closure-jars
     "The dependency source ROOTS for DEPS-MAP on Jolt — the equivalent of
      bb's jar closure. jolt.deps/resolve-deps (the tools.deps expansion
      engine, AOT'd into the jolt binary) fetches Maven/git deps, EXTRACTS
      them, and returns the extraction dirs plus :local/root paths; the
      load-fn then serves namespaces with plain fs probes instead of
      ZipFile (see dep-source). Resolution failures throw, mirroring the bb
      branch. Results are plain directories, so unload drops them with the
      context like jars on bb."
     [deps-map]
     (let [resolve-deps (requiring-resolve 'jolt.deps/resolve-deps)
           base (System/getProperty "user.dir")
           roots (:roots (resolve-deps deps-map base))]
       (vec roots)))
   :bb
   (do
     (def ^:private bundled-artifacts
       "Artifacts babashka ships (clojure + spec are always bundled) — excluded
   from extension closures, matching bb's add-deps classpath-overrides."
       #{"org.clojure/clojure"
         "org.clojure/spec.alpha"
         "org.clojure/core.specs.alpha"})

     (defn- bundled-artifact?
       "True when ENTRY is a jar of one of the artifacts babashka ships (clojure
   + spec are always bundled), which must not be served to extension
   contexts — the SCI-incompatible Maven copies would be evaluated instead
   of bb's bundled ports. Matches the m2 layout: only the group is
   slash-munged, the artifact name keeps its dots (org.clojure/spec.alpha
   lives at repository/org/clojure/spec.alpha/)."
       [entry]
       (some (fn [ga]
               (let [[g a] (str/split ga #"/" 2)]
                 (str/includes? entry (str "repository/" (str/replace g "." "/") "/" a "/"))))
             bundled-artifacts))

     (defn- closure-jars
       "The complete transitive jar set for DEPS-MAP, computed in-process via
        borkdude.deps (the tools.deps port) — no subprocess, no global classpath
        changes, nothing written outside ~/.m2. Resolution failures throw
        (borkdude.deps' default *exit-fn* would kill the process)."
       [deps-map]
       (let [cp (with-out-str
                  (binding [*print-namespace-maps* false
                            bdeps/*exit-fn* (fn [{:keys [message]}]
                                              (throw (ex-info (or message "deps resolution failed")
                                                              {:deps deps-map})))]
                    (bdeps/-main "-Srepro" "-Spath"
                                 "-Sdeps" (pr-str {:deps deps-map
                                                   :mvn/repos {"clojars" {:url "https://repo.clojars.org/"}}})
                                 "-Sdeps-file" "__kmet_no_deps__.edn")))]
         (->> (str/split (str/trim cp) (re-pattern (System/getProperty "path.separator")))
              (filter #(or (str/includes? % ".m2") (str/includes? % ".gitlibs")))
              (remove bundled-artifact?)
              vec)))))

(defonce ^:private jars-cache (atom {}))

(defn- jars-for
  "The jar set for DEPS-MAP, cached by the map across loads (reloads and
   extensions sharing the same deps reuse it; a deps.edn change is a new
   key and re-resolves)."
  [deps-map]
  (let [key (pr-str deps-map)]
    (or (get @jars-cache key)
        (let [jars (closure-jars deps-map)]
          (swap! jars-cache assoc key jars)
          jars))))

(defn- make-deps-resolver
  "Memoized per-extension closure resolver: resolves the extension's jar
   set on first library require (via jars-for), records it on the record's
   :jars (for introspection), reuses it after. nil when the extension has
   no deps.edn."
  [deps-map jars-atom]
  (when deps-map
    (let [resolved (volatile! nil)]
      (fn []
        (or @resolved
            (let [jars (jars-for deps-map)]
              (reset! jars-atom jars)
              (vreset! resolved jars)))))))

(defn- dep-source
  "The {:file :source} of NS-SYM inside one closure ENTRY of the
   extension's deps.edn resolution, or nil. bb entries are jars (per-call
   ZipFile probes); jolt entries are extracted source ROOTS (directories —
   jolt.deps/resolve-deps materializes Maven/git deps to disk), so those
   get a plain strict ns-path fs probe."
  [entry ns-sym]
  (let [base (ns-path ns-sym)]
    (if (fs/directory? (str entry))
      (some (fn [ext]
              (let [f (io/file (str entry) (str base ext))]
                (when (.exists f)
                  {:file (str f) :source (slurp f)})))
            source-extensions)
      (some (fn [ext]
              (when-let [source (jar-entry-source entry (str base ext))]
                {:file (str entry "!/" base ext) :source source}))
            source-extensions))))

(defn- resource-source
  "The source of NS-SYM from the classpath, or nil."
  [ns-sym]
  (let [base (ns-path ns-sym)]
    (some (fn [ext] (when-let [r (io/resource (str base ext))]
                      {:file (str r) :source (slurp r)}))
          source-extensions)))

(defn- register-source-classes!
  "Pre-register classes a load-fn SOURCE needs: scan the source text for
   fully-qualified static/ctor positions (`(fq.Name/...`, `(fq.Name.`),
   bare class positions (`(instance? fq.Name`), and type hints
   (`^StringBuilder`, `^java.io.InputStream` — hinted classes never appear
   in a callable position, so the scan collects them separately, resolving
   short hints through bb-imports). load-fn sources bypass
   eval-source-with-retry! (SCI evaluates the returned :source directly),
   so without this the first internal-namespace use of an unseeded JDK
   class fails the whole load. Short-name statics need no scan — the seed
   covers bb-imports. Same lazy forName as the entry path; unresolvable
   names are skipped (the analysis error surfaces them)."
  [ctx source]
  (let [text (str source)
        ctor-pat (re-pattern (str "\\(" "([a-z][a-z0-9_]*"
                                  "(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+)" "[/.]"))
        inst-pat (re-pattern (str "\\(instance\\?\\s+" "([a-z][a-z0-9_]*"
                                  "(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+)"))
        hint-pat (re-pattern "\\^[a-zA-Z_][a-zA-Z0-9_.]*\\s+")
        ;; NOTE: hint-pat has no capture group, so re-seq returns strings
        ;; (not vectors) — destructure as m, not [m] (a [m] destructure
        ;; binds the leading \^ Character and subs throws
        ;; "Character cannot be cast to String"). Trim the trailing
        ;; whitespace before the bb-imports lookup.
        ;; hints are collected as [short-or-nil fq] so the short alias is
        ;; registered too (see try-add-class-short!).
        hints (keep (fn [m]
                      (let [sym (str/trim (subs m 1))]
                        (if (str/includes? sym ".")
                          [nil sym]
                          (when-let [fq (get bb-imports (symbol sym))]
                            [(symbol sym) (str fq)]))))
                    (re-seq hint-pat text))]
    (doseq [[_ fq] (concat (re-seq ctor-pat text)
                           (re-seq inst-pat text))]
      (try-add-class! ctx fq))
    (doseq [[short fq] hints]
      (if short
        (try-add-class-short! ctx short fq)
        (try-add-class! ctx fq))))
  nil)

(defn- make-load-fn
  "Per-extension namespace resolver, evaluated inside the extension's
   context: own artifact, declared deps (closure resolved lazily on first
   library require), then bb-bundled classpath namespaces. kmet.* beyond
   the contract and undeclared non-bundled libraries are rejected with
   actionable errors — extensions must depend only on kmet.extension.

   Every own-artifact source is require-validated on load (not just the
   entry namespace): a forbidden/misspelled kmet.* require or a direct
   babashka.http-client require in an internal namespace fails with the
   same actionable messages as the entry check. The source's (ns ...)
   must also match the requested symbol (strict layout is enforced at
   load, not just pack time) — otherwise the failure surfaces later as
   a missing init fn."
  [ext-name artifact owns-ns? deps-resolver tui-namespaces libs-namespaces ctx-holder]
  (fn [{:keys [namespace]}]
    (or (when-let [{:keys [source display]} (and artifact (artifact-source artifact namespace))]
          (let [ns-form (ns-form-of-source source)]
            (when-not (= namespace (second ns-form))
              (throw (ex-info (str "Extension " ext-name " strict layout violation: "
                                   display " declares " (second ns-form)
                                   ", expected " namespace)
                              {:extension ext-name :ns namespace})))
            (validate-entry-requires! ext-name ns-form
                                      tui-namespaces libs-namespaces
                                      owns-ns?))
          (when-let [ctx @ctx-holder]
            (register-source-classes! ctx source))
          {:file display :source source})
        (when-let [entries (and deps-resolver (deps-resolver))]
          (some (fn [e] (dep-source e namespace))
                entries))
        (when-not (str/starts-with? (str namespace) "kmet.")
          (resource-source namespace))
        (throw (ex-info
                (cond
                  (or (str/starts-with? (str namespace) "kmet.tui.")
                      (= (str namespace) "kmet.app.ui.tool-renderers")
                      (= (str namespace) "kmet.app.keybindings"))
                  (str "Extension " ext-name " requires " namespace
                       " — the shared TUI/renderer library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str namespace) "kmet.libs.")
                  (str "Extension " ext-name " requires " namespace
                       " — the kmet.libs.* library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str namespace) "kmet.")
                  (str "Extension " ext-name " requires " namespace
                       " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")

                  :else
                  (str "Extension " ext-name " requires " namespace
                       " — not declared in deps.edn and not a babashka-bundled library"))
                {:extension ext-name :ns namespace})))))

(def ^:private spec-port-namespaces
  "bb-bundled clojure.spec ports (spec.alpha and, transitively, its
   spec.gen.alpha / core.specs.alpha deps). bb does not preload them at
   startup (unlike tools.reader / rewrite-clj), and the Maven copies fail
   under SCI — spec.gen.alpha's locking2 macro expands to
   monitor-enter/monitor-exit, which SCI's core lacks — so they are
   required here (bb serves its own ports) and injected by reference into
   extension contexts by the all-ns scan in build-context-namespaces.
   Extensions (e.g. cljfmt.config) get a working clojure.spec.alpha
   without deps.edn pins."
  '[clojure.spec.alpha])

(defn- seed-context-classes!
  "Pre-register every bb-imports FQ name plus the runtime-classes (factory-made
   classes Class/forName cannot load) on CTX. Ordinary JDK classes resolve by
   name on both hosts, so this sweep makes the common System/File/Thread
   references first-try hits; anything else resolves lazily via
   eval-source-with-retry! / the load-fn path. Same call on both hosts."
  [ctx]
  (doseq [[short fq] bb-imports]
    (try-add-class-short! ctx short (str fq)))
  (doseq [^Class c @runtime-classes]
    (try
      (sci/add-class! ctx (symbol (.getName c)) c)
      (catch Throwable _ nil)))
  nil)

(defn- host-requires!
  "Require the shared library layers before a per-extension context is
   built, so the all-ns scan in build-context-namespaces finds them.
   bb-only ports (clojure.spec, rewrite-clj, tools.reader, data.xml —
   SCI-incompatible Maven sources with bb-bundled replacements) are
   required only on bb: Jolt has no bundled copies (its require fails),
   and their namespaces stay absent from Jolt contexts — extensions
   requiring them there get the actionable load-fn error."
  []
  (apply require (concat tui-library-namespaces libs-library-namespaces))
  (when-not (jolt?)
    (apply require (concat spec-port-namespaces bb-shared-namespaces)))
  nil)

(defn- create-context
  "Build the isolated sci context for one extension: lazy host-delegated
   classes ({:allow :all} for instances + Class/forName seeding and
   miss-retry for statics/ctors — identical on bb and Jolt), the bb
   short-name :imports, shared global namespaces (contract + builtins +
   the kmet.tui.* TUI library + the kmet.libs.* library layer, required
   first so they exist for the injection), and the per-extension load-fn
   that checks deps — own artifact, declared deps (resolved lazily on
   first library require), bb-bundled namespaces, with actionable errors
   for everything else. RESOURCE-FN replaces clojure.java.io/resource
   with an artifact-scoped lookup (nil keeps the host resource)."
  [ext-name artifact owns-ns? deps-resolver resource-fn]
  (host-requires!)
  ;; ctx-holder lets the load-fn pre-register source classes on the context
  ;; being built: the load-fn runs after init returns, so the atom is set by
  ;; then (single-threaded load — no race).
  (let [ctx-holder (atom nil)
        ctx (sci/init {:classes {:allow :all}
                       :imports bb-imports
                       :features (if (jolt?) #{:jolt :clj} #{:bb :clj})
                       :namespaces (build-context-namespaces resource-fn)
                       :load-fn (make-load-fn ext-name artifact owns-ns?
                                              deps-resolver
                                              (shared-tui-namespaces)
                                              (shared-libs-namespaces)
                                              ctx-holder)})]
    (reset! ctx-holder ctx)
    (seed-context-classes! ctx)
    ctx))

(defn- missing-class-of
  "The class to register for an eval failure with message MSG, or nil.
   Three miss shapes: `Unable to resolve classname: fq.Name` (FQN —
   forName it directly); `Unable to resolve symbol: Short/method`
   (short-name static — resolve Short through bb-imports first); and
   `Unable to resolve symbol: fq.Name/member` (a dotted FQ qualifier in
   a class position such as instance? — forName the qualifier directly).
   Returns the FQ name or nil when the message is not a resolvable class
   miss."
  [msg]
  (or (second (re-find missing-classname-re (str msg)))
      (when-let [[_ qualifier] (re-find missing-symbol-re (str msg))]
        (if (str/includes? qualifier ".")
          qualifier
          (when-let [fq (get bb-imports (symbol qualifier))]
            (str fq))))))

(defn- eval-source-with-retry!
  "eval-string* SOURCE in CTX, registering lazily-missed classes until the
   source evaluates or a failure is not a resolvable class miss. SCI
   analyzes the whole source before evaluating, so one source can miss
   several classes in sequence (each retry surfaces the next); the loop
   caps at 100 registrations — past that the source is pathological and
   the last error propagates. Re-evaluation is safe: SCI keeps the
   partially-loaded namespaces in the context, so already-evaluated
   top-level forms re-evaluate idempotently (defs re-def, requires
   no-op). Tracked to avoid re-adding: each miss registers a new class,
   so the loop always makes progress."
  [ctx source]
  (loop [attempt 0]
    (let [result (try
                   (sci/eval-string* ctx source)
                   (catch Exception e e))]
      (if-not (instance? Throwable result)
        result
        (let [fq (missing-class-of (ex-message result))]
          (if (and fq (< attempt 100) (try-add-class! ctx fq))
            (recur (inc attempt))
            (throw result)))))))

(defn- eval-source!
  "Evaluate the SOURCE string in CTX via eval-string* (one call — SCI binds
   its own current-ns per form, so (ns ...) switches work on both hosts;
   the old host-read + eval-form loop needed sci/binding on Jolt, which is
   broken on bb). DISPLAY names the origin (file path or jar!/entry) in
   error messages."
  [ctx source display]
  (try
    (eval-source-with-retry! ctx source)
    (catch Exception e
      (throw (ex-info (str (ex-message e) " (" display ")")
                      (assoc (ex-data e) :extension-file display)
                      e)))))

(defn- extension-var
  "The value of VAR-NAME in ENTRY-NS of EXT's context, or nil."
  [ext entry-ns var-name]
  (when-let [ctx @(:ctx ext)]
    (get-in @(:env ctx) [:namespaces entry-ns var-name])))

(defn load-extension!
  "Load a single extension from PATH (.clj file, dir with extension.edn,
   or .jar/.zip archive with the same layout at its root). Each extension
   evaluates in its own isolated context; deps.edn jars are served only to
   that context, so different extensions may pin different versions of the
   same library. Calls the extension's init with its api. On failure
   everything is rolled back and {:extension nil :path PATH :error MSG} is
   returned (PATH names what failed — the result map has no extension name
   to report)."
  [path]
  (let [f (io/file path)
        {:keys [name kind artifact entry-ns file]} (resolve-extension path)
        ext (map->Extension
             {:name name
              :path (str (fs/canonicalize f))
              :kind kind
              :entry-ns (atom nil)
              :ctx (atom nil)
              :jars (atom [])
              :api (atom nil)
              :deregister-fns (atom [])
              :initialized? (atom false)})]
    (try
      (let [jar-artifact? (and artifact (= :jar (:kind artifact)))
            deps (when artifact
                   (if jar-artifact?
                     (:deps (edn/read-string
                             (or (jar-entry-source (:root artifact) "deps.edn") "{}")))
                     (deps-of-root (:root artifact))))
            jar-info (when jar-artifact? (jar-namespaces (:root artifact)))
            owns-ns? (if artifact
                       (fn [ns-sym] (artifact-owns-ns? artifact jar-info ns-sym))
                       (constantly false))
            deps-resolver (make-deps-resolver deps (:jars ext))
            ctx (create-context name artifact owns-ns?
                                deps-resolver
                                (when artifact (extension-resource-fn artifact jar-info deps-resolver)))]
        (doseq [lib (keys deps)]
          (when (contains? bb-bundled-libs (str lib))
            (binding [*out* *err*]
              (println "Warning: extension" (:name ext) "pins" lib
                       "which babashka bundles — the Maven copy may not run;"
                       "omit it from deps.edn to use the bundled version."))))
        (reset! (:ctx ext) ctx)
        (if artifact
          (let [{:keys [source display]} (artifact-source artifact entry-ns)]
            (when-not source
              (throw (ex-info (str "extension.edn :entry not found: " entry-ns)
                              {:path path :entry entry-ns})))
            ;; fail fast on forbidden/misspelled kmet.* requires — sci's
            ;; require machinery swallows the load-fn error into an NPE.
            ;; The entry source must also declare :entry-ns itself (strict
            ;; layout is enforced at load, not just pack time).
            (let [ns-form (ns-form-of-source source)]
              (when-not (= entry-ns (second ns-form))
                (throw (ex-info (str "Extension " name " strict layout violation: "
                                     display " declares " (second ns-form)
                                     ", expected " entry-ns)
                                {:path path :entry entry-ns})))
              (validate-entry-requires! name ns-form
                                        (shared-tui-namespaces)
                                        (shared-libs-namespaces)
                                        owns-ns?))
            (eval-source! ctx source display)
            (let [init-var (extension-var ext entry-ns 'init)]
              (when-not init-var
                (throw (ex-info (str "Extension " (:name ext)
                                     " does not define an init fn")
                                {:path path})))
              (reset! (:entry-ns ext) entry-ns)))
          ;; single-file extension: the file is the entry itself
          (let [source (slurp file)]
            (validate-entry-requires! name (ns-form-of-source source)
                                      (shared-tui-namespaces)
                                      (shared-libs-namespaces)
                                      owns-ns?)
            (eval-source! ctx source (str file))
            (let [ns-sym (some-> (ns-form-of-source source) second)]
              (when-not ns-sym
                (throw (ex-info (str "Extension " (:name ext)
                                     " file does not start with (ns ...)")
                                {:path path})))
              (let [init-var (extension-var ext ns-sym 'init)]
                (when-not init-var
                  (throw (ex-info (str "Extension " (:name ext)
                                       " does not define an init fn")
                                  {:path path}))))
              (reset! (:entry-ns ext) ns-sym))))
        (let [api (create-extension-api ext)]
          (reset! (:api ext) api)
          ((extension-var ext @(:entry-ns ext) 'init) api)
          (reset! (:initialized? ext) true)))
      (swap! extensions conj ext)
      {:extension (:name ext) :error nil}
      (catch Exception e
        (unload-extension! ext)
        {:extension nil
         :path path
         :error (or (ex-message e)
                    (str "load failed: " (.getName (class e))))}))))

(defn unload-extension!
  "Unload an extension: shutdown (if initialized), deregister everything it
   registered, then drop its isolated context — namespaces and jars become
   unreachable, nothing global is touched. Expects the Extension record
   (from the registry / reload-extensions!); nil is a no-op — the load
   result map carries only its :extension name, and (deref nil) would
   otherwise surface as a cryptic NPE."
  [ext]
  (when ext
    (when (and @(:initialized? ext) @(:entry-ns ext))
      (when-let [shutdown (extension-var ext @(:entry-ns ext) 'shutdown)]
        (try (shutdown @(:api ext))
             (catch Exception e
               (binding [*out* *err*]
                 (println "Warning: extension shutdown error:" (ex-message e)))))))
    (doseq [f @(:deregister-fns ext)]
      (try (f) (catch Exception _)))
    (reset! (:ctx ext) nil)
    (reset! (:jars ext) [])
    (swap! extensions (fn [exts] (remove #(identical? % ext) exts)))
    nil))

(defn unload-all-extensions!
  "Unload every loaded extension (reverse order)."
  []
  (doseq [ext (reverse @extensions)]
    (unload-extension! ext))
  nil)

(defn get-loaded-extensions
  "Loaded extensions as {:name str :path str :kind :file/:dir/:jar
   :entry-ns symbol :extension-dir str-or-nil} maps (extension-dir = the
   extension's own directory; nil for jar extensions — see jar-ext.md)."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)
                   :kind (:kind ext)
                   :entry-ns @(:entry-ns ext)
                   :extension-dir (extension-dir-of ext)})
        @extensions))

(defn extension-jars
  "Jar paths of the named loaded extension (its deps.edn closure), or nil."
  [name]
  (some-> (first (filter #(= name (:name %)) @extensions))
          :jars deref))

(defn discover-resources!
  "pi: extendResourcesFromExtensions — fire the :resources-discover event
   (payload {:cwd :reason}) after :session-start and apply every handler's
   contributed resource paths: :skill-paths / :prompt-paths load into the
   skills/prompts registries, :theme-paths into the theme store (pi:
   resourceLoader.extendResources → updateSkills/Prompts/ThemesFromPaths;
   paths are directories). ALL handler results are collected (pi collects
   each handler's contribution — see emit-event-collect!). Paths already
   applied since the last extension reload are skipped — discovery fires
   after every session-start (pi re-scans with mergePaths dedup; kmet's
   prompt loader would duplicate without the tracking). A throwing handler
   is skipped (its paths are lost). Returns the collected
   {:skill-paths [...] :prompt-paths [...] :theme-paths [...]}."
  [reason]
  (let [results (event-bus/emit-event-collect!
                 {:type :resources-discover
                  :cwd (str (fs/cwd))
                  :reason reason})
        paths (reduce (fn [acc r]
                        (cond-> acc
                          (seq (:skill-paths r))
                          (update :skill-paths into (:skill-paths r))
                          (seq (:prompt-paths r))
                          (update :prompt-paths into (:prompt-paths r))
                          (seq (:theme-paths r))
                          (update :theme-paths into (:theme-paths r))))
                      {:skill-paths [] :prompt-paths [] :theme-paths []}
                      results)]
    (doseq [p (:skill-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (skills/load-skills-from-dir p)
        (swap! applied-resource-paths conj p)))
    (doseq [p (:prompt-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (prompts/load-prompt-templates-from-dir p)
        (swap! applied-resource-paths conj p)))
    (doseq [p (:theme-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (theme/load-themes-from-dir p)
        (swap! applied-resource-paths conj p)))
    paths))

(defn clear-extensions!
  "Unload all extensions (used by /reload and tests)."
  []
  ;; extension-contributed resources are re-discovered on the next
  ;; session-start after a reload (the loaders are cleared too)
  (reset! applied-resource-paths #{})
  (unload-all-extensions!))

(defn registered-extensions
  "Currently loaded Extension records (the registry vector)."
  []
  @extensions)

(defn extension-artifact-paths
  "Extension artifact paths inside container DIR (a directory): top-level
   .clj files, .jar/.zip archives, and subdirectories containing
   extension.edn (a directory without the manifest is an extension's own
   layout, not an extension). Sorted by path. [] for a missing dir."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (->> (fs/list-dir d)
           (sort-by str)
           (keep (fn [entry]
                   (let [path (str entry)
                         lower (str/lower-case path)]
                     (cond
                       (and (fs/regular-file? entry) (str/ends-with? path ".clj")) path
                       (and (fs/regular-file? entry)
                            (or (str/ends-with? lower ".jar")
                                (str/ends-with? lower ".zip"))) path
                       (and (fs/directory? entry)
                            (fs/exists? (io/file (str entry) "extension.edn"))) path
                       :else nil))))
           vec))))

(defn load-extension-paths!
  "Load extensions from explicit artifact paths (.clj/.jar/.zip files or
   extension.edn directories — the package-resource unit, pi: package
   extensions load). Returns the list of per-extension {:extension name
   :error} results; failures are also printed as warnings."
  [paths]
  (mapv (fn [path]
          (let [result (load-extension! path)]
            (when (and result (:error result))
              (binding [*out* *err*]
                (println "Warning: Failed to load extension" path ":"
                         (:error result))))
            result))
        paths))

(defn load-extensions-from-dir
  "Load all extensions in DIR (a container): top-level .clj files, .jar/.zip
   archives, and subdirectories containing extension.edn. Returns the list of
   per-extension {:extension name :error} results; failures are also printed
   as warnings."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (load-extension-paths! (extension-artifact-paths (str d))))))

(defn reload-extensions!
  "Unload all loaded extensions, then load from DIRS. Returns the list of
   per-extension {:extension name :error} results. Extension UI (widgets,
   custom footer/header/editor, dialogs) is reset first (pi: reload calls
   resetExtensionUI before reloading)."
  [dirs]
  (ui-call :reset)
  (unload-all-extensions!)
  ;; EAGER (vec around the mapcat): loading is a side effect, and a lazy
  ;; result means a caller that ignores the return value never loads
  ;; anything. bb's apply/concat realizes the seq anyway; jolt defers,
  ;; so the same code silently loaded nothing there.
  (vec (mapcat load-extensions-from-dir dirs)))

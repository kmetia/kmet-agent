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
     {:name \"my-ext\" :entry my.ext.main :loader [:jolt :sci]}
   The manifest lists the initial namespace (:entry, a symbol) and the
   loader backends the extension supports (:loader — a non-empty vector of
   :sci/:jolt). The host picks its own preference among the declared kinds
   (`select-loader-kind`): babashka loads :sci, Jolt prefers its native
   :jolt loader and falls back to :sci; an extension declaring none of the
   host's backends is skipped, not failed. A manifest without :loader (a
   legacy extension) is treated as [:sci :jolt] with a warning; a
   single-file extension implicitly supports both. Everything else is
   required from there. Bundled extensions resolve
   through a descriptor instead of a path (kmet.app.bundled-extensions): a
   checkout's real artifact root, or — in a built artifact — a resource
   tree under extensions/<root>. On Jolt with embedded loader roots, a
   resource-directory descriptor carries an `embed:<prefix>` root and a
   single-file descriptor maps its namespace directly to the exact embedded
   key; both load through the native backend. Older Jolt releases fall back
   to SCI. Each extension evaluates in its own isolated
   context: a fork of one shared SCI base carrying the injected host layers
   and the seeded classes (see shared-context) on babashka, or a set of real
   Jolt namespaces served by the runtime's native loader when the manifest
   offers :jolt there. Internal namespaces are served from the
   extension artifact (dir or jar) by strict ns-path lookup,
   declared libraries from its deps.edn (resolved in-process via
   clojure.tools.deps, bundled with babashka) — so
   different extensions can use different versions of the same library, and
   unloading an extension releases everything it pulled in. Loading goes
   through kmet.loader.core: each extension gets a Loader on the selected
   backend (kmet.loader.sci-loader / kmet.loader.jolt-loader) whose source
   provider is that artifact / deps / bundled lookup, and the backend's own
   requires route back through the loader — so nested requires share the
   link table, unload closes the
   loader, and the loader itself stays out of the shared set (host
   machinery, not contract). Optional
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
            [kmet.ai.models :as models]
            [kmet.ai.hooks :as ai-hooks]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.config :as cfg]
            [kmet.debug :as debug]
            [kmet.tui.theme :as theme]
            [kmet.libs.host :as host]
            [kmet.loader.core :as loader]
            ;; Jolt only: pulls the fixed bundled extension set into the
            ;; app's require closure (jolt dist AOT-compiles it). bb/JVM
            ;; load the same namespace in host-requires! on the first
            ;; extension load (its requires resolve to the bb ports there).
            #?(:jolt [kmet.app.extension-libs])
            #?(:jolt [kmet.loader.jolt-loader :as loader-jolt])
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
(defrecord Extension [name path kind loader-kind bundled? artifact entry-ns loader jars api deregister-fns initialized? jar-files])

(defn- extension-dir-of
  "The extension's own directory: for a dir extension :path IS the
   directory; for a single-file extension it's the file, so the dir is its
   parent. nil for jar and bundled resource extensions — a jar has no
   directory and a bundled resource artifact has no stable one (state goes
   under the agent dir); resources are accessed via io/resource."
  [ext]
  (when-not (contains? #{:jar :resource-dir :resource-file} (:kind ext))
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
(defn ui-get-tool-display-mode [] (ui-call :get-tool-display-mode))
(defn ui-set-tool-display-mode [mode] (ui-call :set-tool-display-mode mode))
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
   (extension api: exec). Options: :dir :env :timeout-ms. The child runs in
   the runtime cwd unless :dir overrides it (pi: options?.cwd ?? cwd) — the
   session's project after a switch; headless falls back to the process cwd."
  [command args & [{:keys [dir env timeout-ms]}]]
  (let [dir (or dir (:cwd (build-extension-context)))
        p (proc/process (concat [command] args)
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

(defn get-all-custom-entries [custom-type]
  (if-let [sess @session-atom]
    (session/get-all-custom-entries sess custom-type)
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
   :set-tools-expanded ui-set-tools-expanded
   :get-tool-display-mode ui-get-tool-display-mode
   :set-tool-display-mode ui-set-tool-display-mode})

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
   :get-all-entries get-all-custom-entries
   :get-branch get-branch-entries
   :get-leaf-id get-leaf-id
   :get-entry get-entry
   :set-label! set-label!
   :get-label get-label
   :set-name! (fn [name]
                (when-let [sess @session-atom]
                  (session/append-session-info! sess (session/sanitize-session-name name))))
   :get-name (fn [] (when-let [sess @session-atom] (session/get-session-name sess)))})

(declare loader-aware loader-aware-map)

(defn- create-extension-api
  "Build the api map for an extension. Every registration records its
   deregister fn so unload removes exactly what this extension added."
  [ext]
  (let [track (fn [f] (track-deregister! ext f) f)
        ;; tool-source ids this extension registered — re-registering the
        ;; same id (catalog sync) must not stack deregister fns
        source-ids (atom #{})
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
                          (let [cmd (loader-aware-map
                                     ext (assoc cmd :extension-handler
                                                (:handler cmd)))]
                            (commands/register-command! cmd)
                            (track (fn [] (commands/unregister-command! (:name cmd))))))
     :unregister-command! commands/unregister-command!
     ;; sanitized — other extensions must not see :handler/:extension-handler
     :get-commands #(mapv (fn [c] (select-keys c [:name :description]))
                          (commands/get-commands))
     :register-tool! (fn [tool]
                       (tools/register-tool! (loader-aware-map ext tool))
                       (track (fn [] (tools/unregister-tool! (:name tool)))))
     :unregister-tool! tools/unregister-tool!
     ;; sandbox-only tools: contributed into the script tool's surface, never
     ;; the model's tool set (mcp-adapter's MCP catalog — script.md T2)
     :register-tool-source! (fn [id tools-fn]
                              (tools/register-tool-source! id tools-fn)
                              (if (contains? @source-ids id)
                                nil
                                (do (swap! source-ids conj id)
                                    (track (fn [] (tools/unregister-tool-source! id))))))
     :unregister-tool-source! (fn [id]
                                (swap! source-ids disj id)
                                (tools/unregister-tool-source! id))
     ;; pi: createBashTool — the bash tool constructor with its options
     ;; (spawnHook / exposeSessionEnvironment / commandPrefix / shellPath /
     ;; operations). The built-in bash tool is the same constructor with
     ;; default options; registering the result under a name replaces it.
     :create-bash-tool bash-tool/create-tool
     :get-all-tools #(vals (tools/get-all-tools))
     :get-active-tools get-active-tools
     :set-active-tools set-active-tools
     :on-event (fn [event-type handler]
                 (let [dereg (event-bus/on-event event-type
                                                 (wrap-event-handler
                                                  (loader-aware ext handler)))]
                   (track dereg)
                   dereg))
     :emit-event! event-bus/emit-event!
     :on-input (fn [hook]
                 (let [h (loader-aware ext hook)]
                   (register-input-hook! h)
                   (track (fn [] (swap! input-hooks
                                        (fn [hs] (remove #(identical? % h) hs)))))))
     :on-before-agent-start (fn [hook]
                              (let [h (loader-aware ext hook)]
                                (register-before-agent-start-hook! h)
                                (track (fn []
                                         (swap! before-agent-start-hooks
                                                (fn [hs] (remove #(identical? % h) hs)))))))
     :on-tool-call (fn [hook]
                     (let [h (loader-aware ext hook)]
                       (register-tool-call-hook! h)
                       (track (fn [] (swap! tool-call-hooks
                                            (fn [hs] (remove #(identical? % h) hs)))))))
     :on-tool-result (fn [hook]
                       (let [h (loader-aware ext hook)]
                         (register-tool-result-hook! h)
                         (track (fn []
                                  (swap! tool-result-hooks
                                         (fn [hs] (remove #(identical? % h) hs)))))))
     :register-flag! (fn [flag-name & [opts]]
                       (register-flag! flag-name opts)
                       (track (fn [] (swap! flags dissoc flag-name))))
     :get-flag get-flag
     :register-shortcut! (fn [key-id & [opts]]
                           (let [dereg (register-shortcut!
                                        key-id
                                        (cond-> opts
                                          (:handler opts) (update :handler #(loader-aware ext %))))]
                             (track dereg)
                             dereg))
     :register-markdown-transformer! (fn [transformer]
                                       (let [dereg (register-markdown-transformer!
                                                    (loader-aware ext transformer))]
                                         (track dereg)
                                         dereg))
     :register-entry-renderer! (fn [custom-type renderer]
                                 (register-entry-renderer! custom-type renderer)
                                 (track (fn [] (swap! entry-renderers dissoc custom-type))))
     :register-message-renderer! (fn [custom-type renderer]
                                   (register-message-renderer! custom-type renderer)
                                   (track (fn [] (swap! message-renderers dissoc custom-type))))
     :register-skill! (fn [raw-content & [opts]]
                        ;; The extension reads its own bundled SKILL.md via
                        ;; io/resource and hands the content over, so jarred
                        ;; skills need no filesystem path
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

;; ─── Load orchestration + entry points ──────────────────────────────────
;; The isolation machinery (jars, class seeding, SCI base, loaders,
;; evaluation) lives in kmet.app.extensions.context; the functions here
;; resolve extensions, wire the API, and register/unregister them.

(declare unload-extension!)

(defn- loader-aware
  "Wrap HANDLER so that whenever it runs, EXT's context is in effect.
   Native-Jolt handlers run with the loader's ambient tier pointing at
   EXT's context (`clojure.java.io/resource` and `RT.baseLoader` resolve
   through the extension's own loader — the native equivalent of the SCI
   path's injected artifact-scoped resource fn); SCI handlers on Jolt run
   with SCI's *out*/*err* bound (with-sci-io), and on babashka are already
   closures over a context whose io vars are bound, so those are identity.

   Everything an extension *registers* is wrapped here, because those
   callbacks run long after the load, invoked by the app, with nothing else
   to tell them which context they belong to. Renderer factories are the one
   exception: they are stored and compared by identity (see
   register-entry-renderer!), so wrapping them would break that contract —
   they read no resources at registration."
  [ext handler]
  #?(:jolt (if (fn? handler)
             (case (:loader-kind ext)
               :jolt (fn [& args]
                       (loader-jolt/with-loader* @(:loader ext)
                         (fn [] (apply handler args))))
               :sci (fn [& args] (context/with-sci-io #(apply handler args))))
             handler)
     :default handler))

(defn- loader-aware-map
  "M with every fn value wrapped by `loader-aware`. The map-shaped
   registrations (commands, tools) carry callbacks besides their main one —
   :get-argument-completions, :render-call/:render-result, :title,
   :prepare-arguments, … — and the app invokes each of them long after the
   load, so each needs the ambient binding as much as the handler does.
   Non-fn values (:description, :parameters, :render-shell :self) pass
   through; a record stays a record (the reduce walks the map itself)."
  [ext m]
  (reduce-kv (fn [acc k v]
               (cond-> acc (fn? v) (assoc k (loader-aware ext v))))
             m m))

(defn- extension-var
  "The value of VAR-NAME in ENTRY-NS of EXT's context, or nil. Backend-
   agnostic: a :var request through the loader (the SCI backend answers from
   its context, the Jolt native backend from the namespace link it installed
   when the namespace loaded). A missing var, or an unloaded loader, is nil."
  [ext entry-ns var-name]
  (when-let [l @(:loader ext)]
    (try
      (loader/load l {:kind :var :name (str entry-ns "/" var-name)})
      (catch Throwable _ nil))))

(defn- load-extension*
  "Load RESOLVED — the map resolve-extension / resolve-extension-descriptor
   answers — with PATH naming it in results and errors. See load-extension!
   for the contract; the descriptor wrapper below shares this body. A name
   already in the registry (D11: the bundled layer ranks last, so a user's
   own copy loads first) is returned skipped, and no second context is
   built."
  [resolved path]
  (let [{:keys [name kind artifact entry-ns file declared-loaders legacy-loader?
                bundled?]} resolved
        loader-kind (if (context/resource-kind? kind)
                      (context/forced-loader-kind resolved declared-loaders)
                      (context/select-loader-kind declared-loaders))]
    (if (some #(= name (:name %)) @extensions)
      ;; same-name dedupe: the earlier copy wins, the loaders print the note
      {:extension name
       :path path
       :error nil
       :skipped true
       :reason :duplicate-name}
      (if-not loader-kind
        {:extension name
         :path path
         :error nil
         :skipped true
         :reason :unsupported-loader
         :declared-loaders declared-loaders
         :available-loaders (context/host-loader-preference)}
        (let [ext (map->Extension
                   {:name name
                    :path (:path resolved)
                    :kind kind
                    :loader-kind loader-kind
                    :bundled? (boolean bundled?)
                    :artifact artifact
                    :entry-ns (atom nil)
                    :loader (atom nil)
                    :jars (atom [])
                    :api (atom nil)
                    :deregister-fns (atom [])
                    :initialized? (atom false)
                    :jar-files (atom {})})]
          (try
            (when legacy-loader?
              (binding [*out* *err*]
                (println "Warning: extension" name
                         "has no :loader in extension.edn — assuming"
                         (pr-str context/default-loaders))))
            (let [jar? (context/jar-artifact? artifact)
                  deps (when artifact
                         (if jar?
                           (:deps (edn/read-string
                                   (or (context/jar-entry-source (:root artifact) "deps.edn") "{}")))
                           (context/deps-of-root artifact)))
                  jar-info (when jar? (context/jar-namespaces (:root artifact)))
                  owns-ns? (if artifact
                             (fn [ns-sym] (context/artifact-owns-ns? artifact jar-info ns-sym))
                             (constantly false))
                  deps-resolver (context/make-deps-resolver deps (:jars ext))
            ;; Force the closure resolution HERE, in host scope: during the
            ;; context's SCI eval, require/requiring-resolve run through the
            ;; loader's source provider (bb hosts the interpreter), and
            ;; clojure.tools.deps internally loads its dep-extension
            ;; namespaces via requiring-resolve — resolving lazily from
            ;; inside the eval re-enters this resolver until the stack
            ;; overflows. Resolved up front, the provider only reads the
            ;; cache.
                  _ (when deps-resolver (deps-resolver))
            ;; Single-file extensions have no artifact: the file itself is
            ;; the entry namespace, served as a literal source (its ns is
            ;; read here so the loader knows what to ask for).
                  file-source (when-not artifact (slurp file))
                  file-ns (when-not artifact
                            (or (some-> (context/ns-form-of-source file-source) second)
                                (throw (ex-info (str "Extension " name
                                                     " file does not start with (ns ...)")
                                                {:path path}))))
                  l (context/create-loader loader-kind ext artifact owns-ns? deps-resolver
                                           (when-not artifact
                                             {file-ns {:file (str file) :source file-source}}))]
              ;; bb-only: a native bb-bundled lib's Maven copy is served to
              ;; SCI contexts (dep-source wins over the host classpath) and
              ;; fails there on classes the GraalVM image does not expose;
              ;; the bundled implementation is not injected either — use a
              ;; kmet.libs.* seam or a pure-Clojure alternative. Libs whose
              ;; bundled port replaces the artifact wholesale were dropped
              ;; from the closure already (bundled-artifacts).
              #?(:bb
                 (doseq [lib (keys deps)
                         :let [lib (str lib)]
                         :when (and (contains? context/bb-bundled-libs lib)
                                    (not (contains? context/bundled-artifacts lib)))]
                   (binding [*out* *err*]
                     (println "Warning: extension" (:name ext) "pins" lib
                              "which babashka bundles natively — its Maven copy"
                              "cannot run under SCI and the bundled one is not"
                              "shared with extension contexts; use the relevant"
                              "kmet.libs.* seam."))))
              (reset! (:loader ext) l)
              (if artifact
                (do
                  (when-not (:source (context/artifact-source artifact entry-ns))
                    (throw (ex-info (str "extension.edn :entry not found: " entry-ns)
                                    {:path path :entry entry-ns})))
            ;; the source provider validates the entry's ns form (strict
            ;; layout + allowed requires) at locate, before anything
            ;; evaluates
                  (loader/load l {:kind :ns :name (str entry-ns)})
                  (reset! (:entry-ns ext) entry-ns))
                (do
                  (loader/load l {:kind :ns :name (str file-ns)})
                  (reset! (:entry-ns ext) file-ns)))
              (let [init-var (extension-var ext @(:entry-ns ext) 'init)]
                (when-not init-var
                  (throw (ex-info (str "Extension " (:name ext)
                                       " does not define an init fn")
                                  {:path path})))
                (let [api (create-extension-api ext)]
                  (reset! (:api ext) api)
                  ((loader-aware ext (deref init-var)) api)
                  (reset! (:initialized? ext) true))))
            (swap! extensions conj ext)
            (debug/log "extension loaded: " name " kind=" (clojure.core/name kind)
                       " loader=" (clojure.core/name loader-kind)
                       " bundled=" (boolean bundled?))
            {:extension (:name ext) :error nil
             :loader-kind loader-kind
             :bundled (boolean bundled?)}
            (catch Exception e
              (unload-extension! ext)
              {:extension nil
               :path path
               :error (or (ex-message e)
                          (str "load failed: " (.getName (class e))))})))))))

(defn load-extension!
  "Load a single extension from PATH (.clj file, dir with extension.edn,
   or .jar/.zip archive with the same layout at its root). Each extension
   evaluates in its own isolated context; deps.edn jars are served only to
   that context, so different extensions may pin different versions of the
   same library. Calls the extension's init with its api. A successful load
   returns {:extension NAME :error nil :loader-kind :sci/:jolt}; an
   extension whose declared :loader this host does not offer is returned
   skipped ({:extension NAME :skipped true :reason :unsupported-loader
   :declared-loaders [...] :available-loaders [...]}); a name that is
   already loaded is returned skipped with :reason :duplicate-name. On
   failure everything is rolled back and {:extension nil :path PATH :error
   MSG} is returned (PATH names what failed — the result map has no
   extension name to report)."
  [path]
  (try
    (load-extension* (context/resolve-extension path) path)
    (catch Exception e
      {:extension nil
       :path path
       :error (or (ex-message e)
                  (str "load failed: " (.getName (class e))))})))

(defn load-extension-descriptor!
  "Load a single bundled extension from DESCRIPTOR
   (kmet.app.bundled-extensions/artifacts). Same contract as
   load-extension!: in a source checkout the descriptor resolves to the
   real extensions/ path and loads exactly as load-extension! would; in a
   built artifact the files come from the extensions/ resource prefix. On
   Jolt with embedded loader roots, directory descriptors use their native
   `embed:<prefix>` root and single-file descriptors map their namespace to
   the exact embedded key; older Jolt releases use SCI (see
   forced-loader-kind)."
  [descriptor]
  (let [path (:path descriptor)]
    (try
      (load-extension* (context/resolve-extension-descriptor descriptor) path)
      (catch Exception e
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
        (try ((loader-aware ext (deref shutdown)) @(:api ext))
             (catch Exception e
               (binding [*out* *err*]
                 (println "Warning: extension shutdown error:" (ex-message e)))))))
    (doseq [f @(:deregister-fns ext)]
      (try (f) (catch Exception _)))
    (when-let [l @(:loader ext)]
      (loader/unload! l))
    (reset! (:loader ext) nil)
    (when-let [jar-files (:jar-files ext)]
      #?(:jolt (reset! jar-files {})
         :default (let [jars @jar-files]
                    (doseq [[_ jf] jars]
                      (try (.close ^java.io.Closeable jf) (catch Exception _)))
                    (reset! jar-files {}))))
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
   :loader-kind :sci/:jolt :entry-ns symbol :extension-dir str-or-nil
   :bundled bool} maps (extension-dir = the extension's own directory; nil
   for jar and bundled resource extensions)."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)
                   :kind (:kind ext)
                   :loader-kind (:loader-kind ext)
                   :entry-ns @(:entry-ns ext)
                   :bundled (boolean (:bundled? ext))
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

(defn- report-load-result!
  "Print the warning/note for a load RESULT (the load functions write
   nothing themselves); returns RESULT. A :duplicate-name skip names the
   earlier copy; an :unsupported-loader skip names the declared and
   available backends; an error is a warning."
  [label result]
  (when (and result (:error result))
    (binding [*out* *err*]
      (println "Warning: Failed to load extension" label ":"
               (:error result))))
  (when (:skipped result)
    (binding [*out* *err*]
      (if (= :duplicate-name (:reason result))
        (println "Note: extension" (:extension result) "from" label
                 "is already loaded — skipping (the earlier copy wins)")
        (println "Note: extension" (:extension result) "declares loaders"
                 (pr-str (:declared-loaders result)) "but" (host/runtime-name)
                 "offers" (pr-str (:available-loaders result)) "— skipping"))))
  result)

(defn load-extension-paths!
  "Load extensions from explicit artifact paths (.clj/.jar/.zip files or
   extension.edn directories — the package-resource unit, pi: package
   extensions load). Returns the list of per-extension {:extension name
   :error} results (:skipped true for an extension whose declared loaders
   this host does not offer, or whose name is already loaded); failures
   and skips are also printed as notes."
  [paths]
  (mapv (fn [path] (report-load-result! path (load-extension! path)))
        paths))

(defn load-extension-descriptors!
  "Load bundled extension DESCRIPTORS
   (kmet.app.bundled-extensions/artifacts) — load-extension-paths!'s
   descriptor counterpart: the same result maps and notes, with :path the
   descriptor's synthetic identity."
  [descriptors]
  (mapv (fn [descriptor]
          (report-load-result! (:path descriptor)
                               (load-extension-descriptor! descriptor)))
        descriptors))

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
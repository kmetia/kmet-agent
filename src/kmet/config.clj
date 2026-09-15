(ns kmet.config
  "Configuration loading for kmet.
   Loads settings from ~/.kmet/agent/settings.edn and .kmet/settings.edn
   (project-local overrides)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.theme :as theme]
            [kmet.ai.auth :as auth]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.libs.http :as http]
            [kmet.libs.edn-store :as eds]))

;; ─── Defaults ───────────────────────────────────────────────────────────────

(def default-config
  {:provider :opencode-go
   :model "deepseek-v4-flash"
   :theme "dark"
   :session-dir "~/.kmet/sessions"
   ;; pi: auto-compaction is token-based against the context window
   :compact-token-threshold nil
   ;; pi: autoCompact — gates proactive compaction (overflow recovery stays on)
   :auto-compact true
   ;; pi: reserveTokens — tokens reserved for prompt + response
   :compact-reserve-tokens 16384
   :keep-recent-tokens 20000
   :retry {:enabled true :max-retries 3 :base-delay-ms 2000}
   ;; loop guard: repeat-loop circuit breaker (tool-call + thinking)
   :loop-guard {:enabled true :threshold 3}
   :thinking-loop-guard-enabled true
   :models []
   :http-idle-timeout-ms 300000
   ;; pi: timeoutMs ?? httpIdleTimeoutMs — the whole-request deadline the
   ;; transport enforces; nil = use the idle timeout, 0 disables (idle fallback)
   :http-total-timeout-ms nil
   ;; outbound HTTP transport: :platform (default — babashka.http-client
   ;; where possible, curl fallback) or :curl (everything through curl)
   :http-transport :platform
   ;; pi: shellPath / shellCommandPrefix — a custom shell binary for bash
   ;; execution and a line prepended to every bash command (settings.edn)
   :shell-path nil
   :shell-command-prefix nil
   :show-cache-miss-notices false
   ;; pi: queue drain modes (:all | :one-at-a-time)
   :steering-mode :all
   :follow-up-mode :all
   ;; pi: outputPad | editorPaddingX | autocompleteMaxVisible
   :output-pad 1
   :editor-padding-x 0
   :autocomplete-max-visible 5
   ;; pi: treeFilterMode — default filter when opening /tree
   :tree-filter-mode :default
   ;; pi: terminal.showImages / terminal.imageWidthCells — inline image
   ;; display (terminal capability is consulted at render time)
   :terminal {:show-images true :image-width-cells 60}
   ;; pi: images.blockImages — true strips every image from provider calls
   ;; (transcript untouched; see kmet.app.loop/block-message-images)
   :images {:block-images false}
   :system-prompt nil
   :append-system-prompt nil
   :thinking :off
   :extensions []
   :skills []
   :prompts []
   :themes []})

;; ─── Path expansion ────────────────────────────────────────────────────────

(def expand-path eds/expand-path)

;; ─── Deep merge & scope-relative paths ─────────────────────────────────────

(def ^:private path-keys
  "Config keys whose values are filesystem paths. Resolved relative to their
   scope dir (pi: paths in ~/.pi/agent/settings.json resolve relative to
   ~/.pi/agent; in .pi/settings.json relative to .pi)."
  #{:session-dir})

(def ^:private retired-dir-keys
  "Retired resource-dir keys (:extensions-dir etc.). The auto roots are
   fixed (pi: join(agentDir, type) + join(cwd, CONFIG_DIR_NAME, type));
   a settings file still carrying one gets a migration warning at load."
  #{:extensions-dir :skills-dir :prompts-dir :themes-dir})

(defn- warn-retired-dir-keys!
  "Warn once per retired :*-dir key found in the user/project settings maps
   (nil = file missing/unreadable, skipped). Points at the top-level
   resource entries that replace them."
  [user-config project-config]
  (doseq [[cfg scope] [[user-config "global"] [project-config "project"]]]
    (doseq [k (sort (filter #(contains? (or cfg {}) %) retired-dir-keys))]
      (binding [*out* *err*]
        (println (str "Warning: " (name k) " in " scope " settings is retired — "
                      "resource dirs are fixed (agent + .kmet); move the path "
                      "into a top-level :" (subs (name k) 0 (- (count (name k)) 4))
                      " entry."))))))

(def deep-merge eds/deep-merge)

;; load-config (above) applies the merged :http-transport and the shell
;; settings to their runtime knobs; the accessors live with the others below.
(declare get-http-transport get-shell-path get-shell-command-prefix)

(defn- resolve-path
  "Resolve a path value relative to its scope dir. ~ and absolute paths pass
   through unchanged."
  [base-dir path]
  (let [s (str path)]
    (cond
      (str/starts-with? s "~") (expand-path s)
      (fs/absolute? s) s
      :else (str (fs/path base-dir s)))))

(defn- resolve-scope-paths
  "Resolve path values in a config map relative to base-dir; other values
   pass through. nil config → {}."
  [config base-dir]
  (reduce-kv (fn [acc k v]
               (assoc acc k (if (and (contains? path-keys k) (string? v))
                              (resolve-path base-dir v)
                              v)))
             {}
             (or config {})))

;; ─── Config loading ────────────────────────────────────────────────────────

(defn- load-edn-file
  "Load an EDN file, returning nil if it doesn't exist or is invalid."
  [path]
  (let [f (io/file (expand-path path))]
    (when (fs/exists? f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (binding [*out* *err*]
            (println "Warning: Failed to load" path ":" (ex-message e)))
          nil)))))

;; ─── Auth (Phase 3: kmet.ai.auth owns auth.edn + the env-var table) ──────

(defn get-api-key
  "API key for a provider (pi: getApiKey) — auth.edn credential first, then
   the provider's env vars in pi order. Delegates to kmet.ai.auth."
  [provider]
  (auth/resolve-api-key provider))

;; ─── Config loading continued ──────────────────────────────────────────────

(def ^:private valid-tree-filter-modes
  #{:default :no-tools :user-only :labeled-only :all})

(defn get-tree-filter-mode
  "Default /tree filter mode (pi: treeFilterMode). Invalid values fall back
   to :default."
  [config]
  (let [v (get config :tree-filter-mode :default)]
    (if (contains? valid-tree-filter-modes v) v :default)))

(defn get-editor-padding-x
  "Horizontal input-editor padding clamped to pi's 0..3 range
   (pi: editorPaddingX — default 0)."
  [config]
  (-> (get config :editor-padding-x 0) long (max 0) (min 3)))

(defn get-autocomplete-max-visible
  "Max visible autocomplete items clamped to pi's documented 3..20 range
   (pi: autocompleteMaxVisible — default 5)."
  [config]
  (-> (get config :autocomplete-max-visible 5) long (max 3) (min 20)))

(defn get-output-pad
  "Horizontal padding for boxed messages: 0 or 1 (pi: outputPad —
   default 1)."
  [config]
  (if (zero? (long (get config :output-pad 1))) 0 1))

(defn get-show-images
  "Whether images render inline in the terminal (pi: terminal.showImages —
   default true). Only consulted when the terminal reports image support;
   otherwise the render paths fall back to the text indicator."
  [config]
  (let [v (get-in config [:terminal :show-images])]
    (if (nil? v) true (boolean v))))

(defn get-image-width-cells
  "Preferred inline image width in terminal cells (pi:
   terminal.imageWidthCells — default 60, clamped to >= 1)."
  [config]
  (let [w (get-in config [:terminal :image-width-cells])]
    (if (number? w) (max 1 (long w)) 60)))

(defn get-block-images
  "Whether image blocks are stripped from provider calls — the transcript
   still shows them (pi: images.blockImages — default false). Read live per
   request by the agent loop, so a mid-session toggle takes effect on the
   next call."
  [config]
  (boolean (get-in config [:images :block-images])))

(defn load-config
  "Load and merge configuration from user and project directories.
   Path values are resolved per scope before merging (global paths relative
   to the agent dir, project paths relative to .kmet), then deep-merged:
   defaults < user < project, with nested maps merged key-by-key (pi: project
   settings override global, nested objects merge). AGENT-DIR pins the scope
   dir for the global settings file, auth.edn, and models.edn
   (KMET_CODING_AGENT_DIR sandboxing); nil resolves via auth/resolve-agent-dir.
   Warns once per retired :*-dir key present in a settings file.
   Returns merged map."
  [& {:keys [no-env? no-settings? agent-dir]}]
  (let [agent-dir (or agent-dir (auth/resolve-agent-dir))
        user-config (when-not no-settings? (load-edn-file (str (fs/path agent-dir "settings.edn"))))
        project-config (when-not no-settings? (load-edn-file ".kmet/settings.edn"))
        _ (auth/load-auth! agent-dir)
        _ (warn-retired-dir-keys! user-config project-config)
        global-dir agent-dir
        project-dir (str (fs/absolutize ".kmet"))
        env-provider (when-not no-env?
                       (or (some-> (System/getenv "KMET_PROVIDER") keyword)
                           (when (auth/resolve-api-key :opencode-go) :opencode-go)))
        env-model (System/getenv "KMET_MODEL")
        base (deep-merge (resolve-scope-paths default-config global-dir)
                         (resolve-scope-paths user-config global-dir)
                         (resolve-scope-paths project-config project-dir))
        with-env (cond-> base
                   env-provider (assoc :provider env-provider)
                   env-model (assoc :model env-model))]
    ;; Runtime knobs from the merged config (precedent: auth/load-auth!
    ;; above — load-config is the single choke point every entry mode
    ;; passes through, so the transport setting applies in interactive
    ;; and print mode alike).
    (http/set-transport! (get-http-transport with-env))
    ;; pi: shellPath/shellCommandPrefix — read by the bash tool and by
    ;; executeBash in pi; applied here so both paths (and extension-built
    ;; bash tools) pick the settings up without per-call config reads.
    (bash-exec/set-shell-options! {:shell-path (get-shell-path with-env)
                                   :command-prefix (get-shell-command-prefix with-env)})
    with-env))

;; ─── Config accessors ──────────────────────────────────────────────────────

(defn get-provider [config]
  (:provider config))

(defn get-model [config]
  (:model config))

(defn get-session-dir [config]
  (expand-path (:session-dir config)))

(defn get-agent-dir
  "The global agent directory. Defaults to ~/.kmet/agent; honors the
   KMET_CODING_AGENT_DIR env override (pi: getAgentDir + ENV_AGENT_DIR).
   Delegates to auth/resolve-agent-dir — auth owns the root so ai
   namespaces (which must not require kmet.config) share it."
  []
  (auth/resolve-agent-dir))

;; ─── Settings persistence (pi: SettingsManager setters) ───────────────────
;; The config module reads settings.edn at load; these fns persist individual
;; fields back to the global file (pi: setHideThinkingBlock etc. write to
;; ~/.pi/agent/settings.json via SettingsManager.save).

(defn- read-settings-file
  "The parsed settings map at PATH, or nil when the file is missing,
   unreadable, or not a map."
  [path]
  (let [file (io/file path)]
    (when (fs/exists? file)
      (try (let [parsed (edn/read-string (slurp file))]
             (when (map? parsed) parsed))
           (catch Exception _ nil)))))

(defn global-settings-path
  "Path of the global settings file (~/.kmet/agent/settings.edn,
   KMET_CODING_AGENT_DIR-aware). Pass AGENT-DIR to pin the scope dir
   (KMET_CODING_AGENT_DIR sandboxing); nil resolves via get-agent-dir."
  ([] (global-settings-path nil))
  ([agent-dir] (str (fs/path (or agent-dir (get-agent-dir)) "settings.edn"))))

(defn project-settings-path
  "Path of the project settings file (.kmet/settings.edn, resolved against
   the current working directory)."
  []
  (str (fs/path (fs/cwd) ".kmet" "settings.edn")))

(defn project-dir
  "The project settings directory (.kmet, resolved against the current
   working directory) — the base dir project-scoped package paths and
   resource entries resolve against (pi: join(cwd, CONFIG_DIR_NAME))."
  []
  (str (fs/path (fs/cwd) ".kmet")))

(defn save-setting!
  "Persist a setting to the global settings.edn (pi: SettingsManager.save —
   only the given field is merged into the current file content, nested keys
   merged leaf-wise). PATH is a key path (e.g. [:hide-thinking-block]); only
   that leaf is merged, so unrelated keys survive. Delegates to
   kmet.libs.edn-settings/save-edn-setting!."
  [path value]
  (eds/save-edn-setting! (global-settings-path) path value))

(defn save-project-setting!
  "Persist a setting to the project settings.edn (.kmet/settings.edn) with
   the same semantics as save-setting! (pi: SettingsManager project-scope
   setters). Creates the .kmet directory as needed."
  [path value]
  (eds/save-edn-setting! (project-settings-path) path value))

(defn read-global-settings-map
  "The parsed global settings map (~/.kmet/agent/settings.edn), or nil when
   the file is missing, unreadable, or not a map."
  []
  (read-settings-file (global-settings-path)))

(defn read-project-settings-map
  "The parsed project settings map (.kmet/settings.edn), or nil when the
   file is missing, unreadable, or not a map."
  []
  (read-settings-file (project-settings-path)))

(defn get-http-transport
  "Outbound HTTP transport mode (see kmet.libs.http/set-transport!):
   :platform (default) — babashka.http-client where possible, curl
   fallback where it cannot serve (SOCKS/https-scheme proxies; live
   streams on Jolt); :curl — every request through curl. Invalid values
   fall back to :platform."
  [config]
  (let [v (get config :http-transport :platform)]
    (if (contains? #{:platform :curl} v) v :platform)))

(defn get-shell-path
  "Custom shell binary for bash execution (pi: getShellPath — a Cygwin/
   Git-Bash override on Windows). A leading ~ expands; nil = the platform
   default (pi: getShellConfig — bash, /bin/bash, PATH, then sh)."
  [config]
  (some-> (:shell-path config) str expand-path))

(defn get-shell-command-prefix
  "Line prepended to every bash command (pi: getShellCommandPrefix, e.g.
   \"shopt -s expand_aliases\" for alias support); nil = none."
  [config]
  (:shell-command-prefix config))

(defn get-hide-thinking-block
  "Pi: getHideThinkingBlock — whether thinking blocks are hidden by default."
  [config]
  (boolean (:hide-thinking-block config)))

(defn set-hide-thinking-block!
  "Pi: setHideThinkingBlock — persist the thinking-block hidden flag to the
   global settings file (applied by Ctrl+T / app.thinking.toggle)."
  [hidden?]
  (save-setting! [:hide-thinking-block] (boolean hidden?)))

(defn get-tool-display-mode
  "Tool display mode (kmet deviation — pi has only the boolean
   toolOutputExpanded): :collapsed (default) | :expanded | :quiet.
   Missing and invalid values fall back to :collapsed."
  [config]
  (let [v (:tool-display-mode config :collapsed)]
    (if (contains? #{:collapsed :expanded :quiet} v) v :collapsed)))

(defn set-tool-display-mode!
  "Persist the tool display mode to the global settings file
   (applied by Ctrl+O / app.tools.expand and the /settings row)."
  [mode]
  (save-setting! [:tool-display-mode] mode))

(defn get-enabled-models
  "Enabled model patterns for Ctrl+P cycling (pi: settingsManager
   enabledModels — same format as the --models flag). nil = all enabled."
  [config]
  (:enabled-models config))

(defn- read-global-settings
  "The parsed global settings map, or nil when the file is missing,
   unreadable, or not a map."
  []
  (read-settings-file (global-settings-path)))

(defn get-setting-live
  "Live top-level KEY from the global settings file, falling back to the
   CONFIG value (then DEFAULT) when absent — the in-memory config is a
   startup snapshot, so /settings rows re-read what they persisted
   (pi: the SettingsManager holds mutable settings)."
  ([config key] (get-setting-live config key nil))
  ([config key default]
   (let [fallback (get config key default)]
     (if-let [settings (read-global-settings)]
       (if (contains? settings key) (get settings key) fallback)
       fallback))))

(defn get-show-cache-miss-notices
  "Whether to show transcript notices for significant prompt-cache misses
   (pi: showCacheMissNotices — default false). Read live from the global
   settings file so a /settings toggle takes effect immediately; falls back
   to the CONFIG snapshot (project overrides) when the key is absent."
  [config]
  (boolean (get-setting-live config :show-cache-miss-notices)))

(defn get-show-hardware-cursor
  "Whether the hardware terminal cursor is visible (pi: showHardwareCursor).
   An unset setting falls back to the KMET_HARDWARE_CURSOR=1 env default."
  [config]
  (if (nil? (:show-hardware-cursor config))
    (= (System/getenv "KMET_HARDWARE_CURSOR") "1")
    (boolean (:show-hardware-cursor config))))

(defn set-show-hardware-cursor!
  "Persist the hardware-cursor flag (pi: settingsManager
   setShowHardwareCursor)."
  [enabled?]
  (save-setting! [:show-hardware-cursor] (boolean enabled?)))

(defn get-show-terminal-progress
  "Whether to show the OSC 9;4 terminal progress indicator (pi:
   showTerminalProgress — default false). An unset setting falls back to
   the KMET_TERMINAL_PROGRESS=1 env default."
  [config]
  (if (nil? (:show-terminal-progress config))
    (= (System/getenv "KMET_TERMINAL_PROGRESS") "1")
    (boolean (:show-terminal-progress config))))

(defn set-show-terminal-progress!
  "Persist the terminal-progress flag (pi: settingsManager
   setShowTerminalProgress)."
  [enabled?]
  (save-setting! [:show-terminal-progress] (boolean enabled?)))

(defn get-enabled-models-live
  "Live :enabled-models patterns from the global settings file (pi: the
   SettingsManager holds mutable settings — kmet's in-memory config is a
   startup snapshot, so /scoped-models re-reads after a Ctrl+S persist).
   Falls back to the CONFIG value when the file is missing, unreadable, or
   lacks the key (project-level overrides survive)."
  [config]
  (if-let [settings (read-global-settings)]
    ;; the file wins only for keys it has — an absent key falls back to the
    ;; config (which may carry a project-level .kmet override); a present
    ;; nil (Ctrl+S all-enabled) reads as nil via get's stored value
    (get settings :enabled-models (:enabled-models config))
    (:enabled-models config)))

(defn set-enabled-models!
  "Persist the enabled-model patterns to the global settings file (pi:
   settingsManager.setEnabledModels). nil removes the filter — all models
   enabled."
  [patterns]
  (save-setting! [:enabled-models] patterns))

(defn set-default-model!
  "Persist the default model and provider to the global settings file (pi:
   settingsManager.setDefaultModelAndProvider). Called when the user picks
   a model via /model, the selector, or Ctrl+P cycling."
  [provider model-id]
  (save-setting! [:provider] provider)
  (save-setting! [:model] (str model-id)))

(defn get-retry-settings
  "Retry settings (pi: settings-manager retry block — enabled, maxRetries,
   baseDelayMs). Returns {:enabled bool :max-retries n :base-delay-ms n};
   the deep-merged config may carry a partial :retry map."
  [config]
  (let [retry (:retry config)]
    {:enabled (if (contains? retry :enabled) (:enabled retry) true)
     :max-retries (or (:max-retries retry) 3)
     :base-delay-ms (or (:base-delay-ms retry) 2000)}))

(defn get-retry-settings-live
  "Live :retry settings from the global settings file (the in-memory config
   is a startup snapshot — /settings re-reads after a same-session change,
   like get-enabled-models-live). Falls back to the CONFIG value when the
   file is missing, unreadable, or lacks :retry (project overrides)."
  [config]
  (get-retry-settings (if-let [settings (read-global-settings)]
                        ;; the file wins only for keys it has — an absent
                        ;; :retry falls back to the config (project override)
                        (assoc config :retry (get settings :retry (:retry config)))
                        config)))

;; ─── Loop guard (repeat-loop circuit breaker) ────────────────────────────

(defn get-loop-guard-settings
  "Loop-guard settings (pi: no counterpart — kmet's repeat-loop circuit
   breaker; mirrors the :retry block shape). Returns {:enabled bool
   :threshold n}; the deep-merged config may carry a partial :loop-guard
   map. Threshold is clamped to >= 2 (a 1-call threshold would suppress
   the second occurrence of any repeated call — too aggressive)."
  [config]
  (let [lg (:loop-guard config)]
    {:enabled (if (contains? lg :enabled) (:enabled lg) true)
     :threshold (max 2 (or (:threshold lg) 3))}))

(defn get-loop-guard-settings-live
  "Live :loop-guard settings from the global settings file (the in-memory
   config is a startup snapshot — /settings re-reads after a same-session
   change, like get-retry-settings-live). Falls back to the CONFIG value
   when the file is missing, unreadable, or lacks :loop-guard (project
   overrides)."
  [config]
  (get-loop-guard-settings (if-let [settings (read-global-settings)]
                             ;; the file wins only for keys it has — an absent
                             ;; :loop-guard falls back to the config
                             (assoc config :loop-guard (get settings :loop-guard (:loop-guard config)))
                             config)))

;; ─── System prompt sources (pi: resolvePromptInput + discoverSystemPromptFile) ──

(defn- resolve-prompt-input
  "pi: resolvePromptInput — a value naming an existing file is read as
   content; anything else is used inline."
  [input]
  (when (seq input)
    (let [p (str input)]
      (if (fs/exists? p)
        (try (slurp p) (catch Exception _ p))
        p))))

(defn- prompt-file-candidates
  "Absolute candidate paths for a prompt file: project (.kmet) first, then
   the agent dir — pi checks the project before the agent dir."
  [filename]
  [(str (fs/path (fs/cwd) ".kmet" filename))
   (str (fs/path (get-agent-dir) filename))])

(defn- discover-prompt-file
  "First existing candidate for a prompt file, or nil."
  [filename]
  (some #(when (fs/exists? %) %) (prompt-file-candidates filename)))

(defn get-custom-prompt
  "Custom system prompt source (pi: systemPromptSource ??
   discoverSystemPromptFile, then resolvePromptInput): the :system-prompt
   config value (read as a file when it names an existing one), else
   .kmet/SYSTEM.md, else ~/.kmet/agent/SYSTEM.md.
   Deviations from pi: no project-trust gate."
  [config]
  (resolve-prompt-input (or (:system-prompt config)
                            (discover-prompt-file "SYSTEM.md"))))

(defn get-append-system-prompt
  "Append system prompt source (pi: appendSystemPromptSource ??
   discoverAppendSystemPromptFile): the :append-system-prompt config value
   (read as a file when it names an existing one), else .kmet/APPEND_SYSTEM.md,
   else ~/.kmet/agent/APPEND_SYSTEM.md.
   Deviations from pi: no project-trust gate."
  [config]
  (resolve-prompt-input (or (:append-system-prompt config)
                            (discover-prompt-file "APPEND_SYSTEM.md"))))

(defn apply-cli-overrides
  "Apply CLI opts onto a config map (pi: CLI flags override settings).
   Handles :model, :provider, :thinking, :models (scoped list), :system-prompt
   (last wins), :session-dir, and :append-system-prompt (repeatable, joined with newlines
   like pi)."
  [config opts]
  (cond-> config
    (:model opts) (assoc :model (:model opts))
    (:provider opts) (assoc :provider (:provider opts))
    (:thinking opts) (assoc :thinking (:thinking opts))
    (:models opts) (assoc :models (:models opts))
    (:session-dir opts) (assoc :session-dir (:session-dir opts))
    (:system-prompt opts) (assoc :system-prompt (:system-prompt opts))
    (:append-system-prompt opts) (assoc :append-system-prompt
                                        (str/join "\n\n" (:append-system-prompt opts)))))

(defn get-theme-name [config]
  (:theme config "dark"))

(defn get-theme [config]
  (theme/get-theme (get-theme-name config)))

(defn auto-resource-dirs
  "The fixed auto-discovery roots for a resource type (pi: RESOURCE_TYPES +
   join(agentDir, type) + join(cwd, CONFIG_DIR_NAME, type)): the agent-dir
   dir and the project-local .kmet dir, existing dirs only, in pi load order
   (global first, so global wins name collisions). AGENT-DIR pins the global
   root (KMET_CODING_AGENT_DIR sandboxing); nil resolves via get-agent-dir."
  ([resource-type] (auto-resource-dirs resource-type nil))
  ([resource-type agent-dir]
   (let [roots [(str (fs/path (or agent-dir (get-agent-dir)) (name resource-type)))
                (str (fs/path (fs/cwd) ".kmet" (name resource-type)))]]
     (filterv fs/directory? roots))))

;; ─── Initialization ─────────────────────────────────────────────────────────

(defn init!
  "Load config. Returns the loaded config map. Call once at startup.
   AGENT-DIR pins the scope dir (KMET_CODING_AGENT_DIR sandboxing); nil
   resolves via auth/resolve-agent-dir. Themes are NOT loaded here — the
   unified resolution (packages/load-themes!) loads them with the other
   resources, so settings toggles apply."
  ([] (init! nil))
  ([agent-dir]
   (let [agent-dir (or agent-dir (auth/resolve-agent-dir))
         config (load-config :agent-dir agent-dir)]
     (fs/create-dirs (get-session-dir config))
     config)))

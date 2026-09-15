(ns kmet.app.tools.bash
  "Bash tool implementation — delegates to kmet.app.bash-executor for execution.
   Pi: bash tool wraps createLocalBashOperations, same engine as ! commands.
   create-tool is pi's createBashTool/createShellToolDefinition — the built-in
   registry entry and extension-built bash tools are the same record.
   Streams live output via an optional on-update callback (pi: onUpdate)."
  (:require [clojure.string :as str]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]))

(defn title
  "Quiet one-liner body for the bash tool: verb + `$ <cmd>` — pure data, or nil when the command is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [cmd (tool-util/title-str-arg args :command)]
    (str "bash $ " cmd)))

(def ^:private update-throttle-ms 100)  ;; pi: BASH_UPDATE_THROTTLE_MS
(def ^:private max-live-bytes (* 50 1024))  ;; pi: DEFAULT_MAX_BYTES

(def ^:dynamic *cancel-signal*
  "Cancel-signal atom for bash execution (pi passes an AbortSignal to tool
   execute). Bound by kmet.app.loop around each agent run and by the interactive
   ! flow around the user-bash emit, so Escape cancels bash everywhere — the
   loop's own tool futures AND extension code (custom tool executes, event
   handlers) that calls execute-tool. nil outside those paths."
  nil)

(def ^:dynamic *session-env-fn*
  "0-arg fn returning the KMET_* session env map for the current run, or nil
   (pi: exposeSessionEnvironment + the execute ctx — see session-env). Bound
   by kmet.app.loop around each agent run and by the interactive ! flow; nil
   outside those paths. A fn, not a map: the values resolve per execution
   (pi reads ctx per call), so a mid-run model/thinking change is reflected."
  nil)

(defn session-env
  "KMET_* session metadata for a bash command's environment (pi:
   resolveSpawnContext): KMET_SESSION_ID, KMET_SESSION_FILE (when the session
   is persisted), KMET_PROVIDER, KMET_MODEL, KMET_REASONING_LEVEL (including
   :off — pi sets it whenever a level is present). Keys whose value is absent
   are omitted; nil returns {}."
  [{:keys [session provider model thinking-level]}]
  (cond-> {}
    (:id session) (assoc "KMET_SESSION_ID" (str (:id session)))
    (:file session) (assoc "KMET_SESSION_FILE" (str (:file session)))
    (some? provider) (assoc "KMET_PROVIDER" (name provider))
    (some? model) (assoc "KMET_MODEL" (str model))
    (some? thinking-level) (assoc "KMET_REASONING_LEVEL" (name thinking-level))))

(defn- byte-length
  "UTF-8 byte length (pi: Buffer.byteLength)."
  [s]
  (alength (.getBytes ^String s "UTF-8")))

(def ^:private default-description
  ;; Pi: bashToolConfig description — the shellName/truncate-defaults
  ;; interpolation of core/tools/bash.ts.
  (str "Execute a bash command in the current working directory. "
       "Returns stdout and stderr. Output is truncated to last "
       bash-exec/DEFAULT-MAX-LINES " lines or "
       (quot bash-exec/DEFAULT-MAX-BYTES 1024)
       "KB (whichever is hit first). If truncated, full output "
       "is saved to a temp file. Optionally provide a timeout "
       "in seconds."))

(def ^:private session-env-guideline
  ;; Pi: bashToolSystemPromptContribution.guidelines (PI_* → KMET_*)
  "You can inspect KMET_* environment variables for current model and session details.")

(defn- run-bash
  "Execute through the shared executor with the tool's spawn options (pi: the
   BashToolOptions an execute closure closes over)."
  [args on-update {:keys [command-prefix shell-path spawn-hook
                          expose-session-env? operations]}]
  (let [{:keys [command timeout]} args
        live-chunks (atom [])  ;; whole decoded chunks — no mid-string truncation
        live-bytes (atom 0)
        last-update (atom 0)
        trim-live! (fn []
                     ;; Pi: OutputAccumulator rolling tail — drop leading chunks
                     ;; until the live buffer fits in max-live-bytes
                     (loop []
                       (when (and (> @live-bytes max-live-bytes) (seq @live-chunks))
                         (let [c (first @live-chunks)]
                           (swap! live-chunks subvec 1)
                           (swap! live-bytes - (byte-length c))
                           (recur)))))
        send-update (fn []
                      (when (and on-update (pos? @live-bytes))
                        (let [now (System/currentTimeMillis)]
                          (when (>= (- now @last-update) update-throttle-ms)
                            (reset! last-update now)
                            (on-update {:content (apply str @live-chunks)
                                        :is-partial true})))))]
    (try
      (let [result (bash-exec/execute-bash
                    {:command command
                     :cwd (or (System/getProperty "user.dir") ".")
                     :timeout timeout  ;; nil = no timeout (pi: optional, no default)
                     :signal *cancel-signal*  ;; agent-loop cancel (pi: AbortSignal)
                     :env (when (and expose-session-env? *session-env-fn*)
                            (*session-env-fn*))  ;; pi: resolveSpawnContext
                     :command-prefix command-prefix  ;; pi: commandPrefix
                     :shell-path shell-path  ;; pi: shellPath
                     :spawn-hook spawn-hook  ;; pi: BashSpawnHook
                     :operations operations  ;; pi: BashOperations
                     :on-chunk (fn [chunk]
                                 (swap! live-chunks conj chunk)
                                 (swap! live-bytes + (byte-length chunk))
                                 (trim-live!)
                                 (send-update))
                     :max-lines bash-exec/DEFAULT-MAX-LINES
                     :max-bytes bash-exec/DEFAULT-MAX-BYTES})
            {:keys [output exit-code cancelled truncated truncation full-output-path timed-out]} result
            ;; Pi: [Showing lines X-Y of Z. Full output: path] footer for the LLM
            ;; (stripped from the TUI display by the bash render-result, which
            ;; shows the truncation warning instead)
            footer (when (and truncation full-output-path)
                     (let [total-lines (:total-lines truncation)
                           output-lines (:output-lines truncation)
                           start-line (+ (- total-lines output-lines) 1)
                           end-line total-lines
                           limit-str (when (= (:truncated-by truncation) :bytes)
                                       (str " (" (bash-exec/format-size
                                                  (or (:max-bytes truncation)
                                                      bash-exec/DEFAULT-MAX-BYTES))
                                            " limit)"))]
                       (str "\n\n[Showing lines " start-line "-" end-line " of " total-lines
                            limit-str ". Full output: " full-output-path "]")))]
        (if cancelled
          ;; Pi: appendStatus — only adds \n\n separator when there is output
          {:content (str (when (seq (str output footer)) (str output footer "\n\n")) "Command aborted")
           :is-error true}
          (let [is-error (or timed-out (and exit-code (not= exit-code 0)))
                ;; Pi: formatOutput — empty successful output shows "(no output)"
                base (if (and (not is-error) (empty? output)) "(no output)" output)
                ;; Pi: appendStatus(outputText, status) — status follows the output
                status (cond
                         timed-out (str "Command timed out after " (or timeout "?") " seconds")
                         is-error (str "Command exited with code " exit-code)
                         :else nil)
                content (if status
                          (str (when (seq (str base footer))
                                 (str base footer "\n\n"))
                               status)
                          (str base footer))]
            (if truncated
              ;; Pi: details are lost when the tool throws, so errors carry no
              ;; truncation metadata (the footer stays in the content instead)
              (cond-> {:content content
                       :is-error is-error}
                (not is-error)
                (assoc :truncation {:total-lines (:total-lines truncation)
                                    :total-bytes (:total-bytes truncation)
                                    :shown-lines (:output-lines truncation)
                                    :truncated-by (:truncated-by truncation)
                                    :max-bytes (:max-bytes truncation)
                                    :max-lines (:max-lines truncation)
                                    :full-output-path full-output-path}))
              {:content content
               :is-error is-error}))))
      (catch Exception e
        (let [msg (ex-message e)]
          (if (str/includes? msg "timeout")
            {:content (str "Command timed out after " (or timeout "?") " seconds") :is-error true}
            {:content (str "Error: " msg) :is-error true}))))))

(defn create-tool
  "Build a bash Tool record (pi: createBashTool/createShellToolDefinition —
   the built-in registry entry is (create-tool)). OPTS mirrors pi's
   BashToolOptions plus the tool-facing fields:
     :name                — tool name (default \"bash\"; registering that name
                            replaces the built-in tool)
     :label               — display label (default \"Execute command\")
     :description         — provider-facing description (default: pi's)
     :command-prefix      — line prepended to every command (pi: commandPrefix;
                            default: the :shell-command-prefix setting)
     :shell-path          — custom shell binary (pi: shellPath; default: the
                            :shell-path setting)
     :spawn-hook          — (fn [{:keys [command cwd env]}] → same map) run
                            before spawn, after the KMET_* session env is
                            injected (pi: BashSpawnHook)
     :expose-session-env? — inject the run's KMET_* session env and the prompt
                            guideline (default true, pi:
                            exposeSessionEnvironment)
     :operations          — custom executor (pi: BashOperations)
   The closure keeps the built-in streaming contract (:streams? — a
   (fn [args on-update]) execute)."
  ([] (create-tool {}))
  ([{:keys [name label description command-prefix shell-path spawn-hook
            expose-session-env? operations]}]
   (let [expose-session-env? (if (some? expose-session-env?) expose-session-env? true)]
     (tool/make-tool
      :name (or name "bash")
      :label (or label "Execute command")
      :description (or description default-description)
      :prompt-snippet "Execute bash commands (ls, grep, find, etc.)"
      :prompt-guidelines (when expose-session-env? [session-env-guideline])
      :params {:command {:type :string :description "Shell command to execute"}
               :timeout {:type :number :description "Timeout in seconds (optional, no default timeout)" :optional? true}}
      :execute (fn [args & [on-update]]
                 (run-bash args on-update
                           {:command-prefix command-prefix
                            :shell-path shell-path
                            :spawn-hook spawn-hook
                            :expose-session-env? expose-session-env?
                            :operations operations}))
      :streams? true
      :title title))))

(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.ai.models :as models]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.app.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.extensions :as extensions]
            [kmet.app.packages :as packages]
            [kmet.config :as cfg]))

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — print mode wires
   them like interactive mode, so write-reject / edit-warn hooks fire in
   headless runs too. Returns nil | {:block true :reason} | {:args ...}."
  [ctx]
  (loop [hooks (extensions/get-tool-call-hooks)
         blocked nil
         args (:args ctx)]
    (if-let [hook (first hooks)]
      (let [r (try (hook (assoc ctx :args args))
                   (catch Exception e
                     {:block true
                      :reason (str "tool-call hook error: " (ex-message e))}))]
        (cond
          (:block r)
          (recur (next hooks) (or blocked {:block true :reason (:reason r)}) args)
          (contains? r :args)
          (recur (next hooks) blocked (:args r))
          :else
          (recur (next hooks) blocked args)))
      (or blocked (when (not= args (:args ctx)) {:args args})))))

(defn- extension-after-tool-call
  "Chain extension tool-result hooks (pi: afterToolCall) — print mode wires
   them like interactive mode. Returns the (possibly rewritten) result."
  [ctx]
  (reduce (fn [result hook]
            (if-let [r (try (hook (assoc ctx :result result
                                         :is-error (:is-error result false)))
                            (catch Exception e
                              {:content (str "tool-result hook error: " (ex-message e))
                               :is-error true}))]
              (cond-> result
                (:content r) (assoc :content (:content r))
                (contains? r :is-error) (assoc :is-error (:is-error r)))
              result))
          (:result ctx)
          (extensions/get-tool-result-hooks)))

(defn run
  "Run in non-interactive mode: send message, print response, exit.
   opts: :model, :provider, :messages, :system-prompt, :append-system-prompt, :config"
  [opts]
  (let [{:keys [model provider messages config]} opts
        config (cfg/apply-cli-overrides (or config (cfg/load-config :no-env? true)) opts)
        _ (packages/load-skills!)
        _ (packages/load-prompts!)
        system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                            :append-prompt (cfg/get-append-system-prompt config)
                            :context-files (context/load-project-context-files
                                            (cfg/get-agent-dir) (str (fs/cwd)))
                            :tools (vals (tools/get-all-tools))}
        system-prompt (apply skills/build-system-prompt
                             (mapcat identity system-prompt-opts))
        resolved-provider (or provider (cfg/get-provider config))
        resolved-model (or model (models/resolve-config-model config))
        ;; A session record (pi print-mode's AgentSessionRuntime always has
        ;; one — sessionManager.getSessionId drives the opencode
        ;; x-opencode-session attribution headers and the openai-responses
        ;; prompt_cache_key; the zen free tier rejects requests without a
        ;; session id: "OpenCode's free tier can only be used in OpenCode")
        session (session/create-session (cfg/get-session-dir config))
        ag (agent/make-agent-state
            :model resolved-model
            :provider resolved-provider
            :system system-prompt
            :system-prompt-opts system-prompt-opts
            :session session
            :before-tool-call extension-before-tool-call
            :after-tool-call extension-after-tool-call
            ;; pi: retry settings (settings.edn :retry block — enabled gates
            ;; max-retries to 0)
            :max-retries (let [retry (cfg/get-retry-settings config)]
                           (if (:enabled retry) (:max-retries retry) 0))
            :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
            ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard
            ;; block — enabled gates threshold to 0 (off)
            :loop-guard-enabled (:enabled (cfg/get-loop-guard-settings config))
            :loop-guard-threshold (:threshold (cfg/get-loop-guard-settings config))
            :thinking-loop-guard-enabled (get config :thinking-loop-guard-enabled true)
            ;; pi: images.blockImages — stripped per request in call-llm (pi
            ;; applies it at the SDK level, so --print honors it too)
            :block-images (cfg/get-block-images config))
        _ (agent/init-scoped-models! ag config)
        result-promise (promise)
        ;; pi: session.prompt expands skill commands + prompt templates
        message (-> (str/join " " messages)
                    (skills/expand-skill-command)
                    (prompts/expand-prompt-template (prompts/get-prompt-templates)))]
    (agent/run-agent-turn ag
                          {:message message
                           :on-text (fn [t] (print t) (flush))
                           :on-done (fn [text] (println) (deliver result-promise text))
                           :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                                       (deliver result-promise nil))})
    @result-promise))

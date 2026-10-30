(ns kmet.app.compaction.stage
  "Proactive and reactive conversation compaction — the builtin implementation
   behind the `:compaction` contract (`kmet.app.compaction`), which is what the
   loop and the session handle call.

   The compaction stage of the agent loop (owner: kmet.app.compaction.core
   is what compaction is — algorithms, cut point, prompts; this namespace is
   when the loop runs it; kmet.app.session.core owns the append-only
   compact-with-summary! entry). The session is compacted proactively
   (measured usage vs. the model's context window) and reactively after a
   context-overflow error (compact once, then retry). Compaction summarizes
   the pre-cut conversation via the LLM (pi: core/compaction) and replaces it
   with a summary entry. The summarization call is retried with the configured
   retry policy (pi: completeSummarization → retryAssistantCall), reports
   :summarization-retry-* events while it backs off, and reasons at the
   session's thinking level; a failure surfaces its cause behind pi's reason
   prefix (kmet.app.compaction.core/compaction-failure-message)."
  (:require [clojure.string :as str]
            [kmet.app.llm :as llm]
            [kmet.app.compaction.core :as core]
            [kmet.app.debug :as debug]
            [kmet.app.retry :as retry]
            [kmet.libs.concurrent :as concurrent]
            [kmet.app.loop.state :as state]
            [kmet.app.loop.completion :as completion]
            [kmet.app.loop.stages :as loop-stages]
            [kmet.app.session.core :as session]
            [kmet.app.extensions.core :as extensions]
            [kmet.ai.models :as models]
            [kmet.libs.http :as libs-http]
            [kmet.app.config :as cfg]))

(defn reset-compaction-blocked!
  "Clear the per-prompt compaction gate (the driver calls this at run start;
   compact-context! sets it when a compaction is already blocked)."
  [agent]
  (reset! (:compaction-blocked agent) false))

(defn abort-compaction!
  "Abort the in-flight compaction without cancelling the agent run (pi:
   session.abortCompaction — escape during compaction touches only the
   compaction controllers). The run continues on the pre-compaction context;
   :compaction-end reports :aborted. A run cancel (cancel-turn) aborts the
   compaction too."
  [agent]
  (reset! (:compaction-signal agent) true)
  nil)

(defn- compaction-aborted?
  "True when the in-flight compaction must stop: its own signal fired (escape
   during compaction) or the whole run was cancelled (pi: abort() calls
   abortCompaction(), so a run cancel still aborts a live compaction)."
  [agent]
  (or @(:signal agent) @(:compaction-signal agent)))

(defn- summarization-retry-callbacks
  "The callbacks a summarization retry reports through (pi:
   _summarizationRetryCallbacks — summarization_retry_scheduled /
   _attempt_start / _finished drive the UI's retry countdown). SOURCE is
   {:source :compaction :reason reason} or {:source :branch-summary}."
  [agent source]
  {:on-retry-scheduled
   (fn [attempt max-attempts delay-ms error-message]
     (state/emit agent {:type :summarization-retry-scheduled
                        :attempt attempt
                        :max-attempts max-attempts
                        :delay-ms delay-ms
                        :error-message error-message}))

   :on-retry-attempt-start
   (fn [] (state/emit agent (merge {:type :summarization-retry-attempt-start} source)))

   :on-retry-finished
   (fn [& _] (state/emit agent {:type :summarization-retry-finished}))})

(defn- summarization-call
  "One summarization LLM call (pi: completeSummarization — the single call
   the retry driver wraps). SIGNALS are the raw cancel atoms to watch (the
   transport polls their OR-view); THINKING (optional) is the reasoning
   level to request (pi: a compaction passes the session's thinking level,
   branch summarization passes none). Returns the result map retry-call!
   classifies: {:stop-reason kw :error-message str :tool-calls [...] :text
   str :usage map}, :stop-reason :aborted when a cancel signal fired (a
   signal that already fired short-circuits before auth resolution and the
   request), or :stop-reason :error when the call threw synchronously (auth
   resolution, the context hook, request setup) — the stream function
   reports failures, it never throws into the retry driver (pi:
   completeSummarization)."
  [agent messages {:keys [signals thinking max-tokens]}]
  (let [ep (completion/resolve-endpoint agent)
        signal (apply concurrent/or-signal signals)
        ;; Whole-request deadline, like a normal call; the deref below uses
        ;; the same value as its backstop when enabled, while an explicitly
        ;; disabled deadline waits for completion or cancellation.
        deadline (retry/llm-total-timeout-ms @(:cfg agent))
        done (promise)
        text-buf (atom "")
        usage-buf (atom nil)
        tc-buf (atom [])
        partial (fn [stop & [msg]]
                  (cond-> {:stop-reason stop
                           :text @text-buf
                           :tool-calls @tc-buf
                           :usage @usage-buf}
                    msg (assoc :error-message msg)))
        deliver! (fn [result]
                   (when-not (realized? done) (deliver done result)))
        watch-key (str "kmet/summarization-cancel" (random-uuid))]
    ;; The stream may not deliver an event on cancel (killed curl), so the
    ;; watches are what make escape abort the call promptly.
    (doseq [s signals]
      (when s
        (add-watch s watch-key (fn [_ _ _ v] (when v (deliver! (partial :aborted)))))))
    (try
      (if @signal
        ;; A signal that already fired needs no call: report the abort
        ;; without resolving auth or opening a request. send-message's own
        ;; no-key error delivery is asynchronous, so without this check it
        ;; can win the race against the post-call abort below and report
        ;; the missing key instead of the abort (pi: an aborted stream
        ;; never starts).
        (partial :aborted)
        (do
          (llm/send-message agent
                            (cond-> {:provider @(:provider agent)
                                     :api-type (:api-type ep)
                                     :model @(:model agent)
                                     :api-key (completion/resolve-api-key agent)
                                     :base-url (:base-url ep)
                                     :messages messages
                                     :signal signal
                                     :idle-timeout-ms (:http-idle-timeout-ms @(:cfg agent))
                                     :total-timeout-ms deadline
                                     :session-id (some-> (:session agent) :id)
                                     :cache-retention :none
                                     :hooks (extensions/provider-hooks (:ctx agent))
                                     :max-tokens max-tokens
                                     :on-text (fn [t] (swap! text-buf str t))
                                     :on-tool-call (fn [tc] (swap! tc-buf conj tc))
                                     :on-usage (fn [u] (reset! usage-buf u))
                                     :on-done (fn [reason] (deliver! (partial (or reason :stop))))
                                     :on-error (fn [msg] (deliver! (partial :error msg)))}
                              thinking (assoc :thinking thinking)))
          (when @signal (deliver! (partial :aborted)))
          (let [result (if (pos? deadline) (deref done deadline :timeout) @done)]
            (if (map? result)
              result
              (partial :error (str "Stream timed out after " deadline "ms"))))))
      (catch Exception e
        ;; A synchronous throw (auth resolution, the context hook, request
        ;; setup) still has to reach the retry driver as a result: the
        ;; caller's failure reporting and the UI's status cleanup hinge on
        ;; it. The abort check comes first — a transport aborted by escape
        ;; can throw instead of delivering (pi: a stream fn reports, never
        ;; throws).
        (if @signal
          (partial :aborted)
          (partial :error (libs-http/transport-error-message e))))
      (finally
        (doseq [s signals]
          (when s (remove-watch s watch-key)))))))

(defn- compaction-settings
  "Effective compaction settings for the agent's current model (pi:
   settings-manager getCompactionSettings — the exact \"provider/modelId\"
   :compact-model-overrides entry beats the ordinary
   :compact-reserve-tokens / :keep-recent-tokens, each field
   independently). Resolved per call, so a model switch takes effect on the
   next check or compaction; :enabled mirrors the live :auto-compact
   toggle."
  [agent]
  (cfg/get-compaction-settings
   (assoc (select-keys agent [:compact-reserve-tokens :keep-recent-tokens
                              :compact-model-overrides])
          :auto-compact (boolean (:auto-compact @(:cfg agent))))
   @(:provider agent)
   @(:model agent)))

(defn- summarization-max-tokens
  "The summarization call's output cap (pi: generateSummaryWithUsage's
   maxTokens plus adjustMaxTokensForThinking): floor(0.8 * SETTINGS'
   :reserve-tokens) for the summary text, plus the session's thinking
   budget when MODEL reasons (models/thinking-budget-for), bounded by
   MODEL's own maximum output. Reasoning tokens count against the same
   ceiling, so without the allowance a deep-thinking session spends the
   summary's budget on reasoning and generation stops `length` — an
   incomplete summary the caller rejects."
  [settings model thinking]
  (let [base (long (Math/floor (* 0.8 (:reserve-tokens settings))))
        cap (+ base (models/thinking-budget-for model thinking))]
    (min cap (or (:max-tokens model) cap))))

(defn stage-retry-policy
  "The flat policy {:max-retries :base-delay-ms :max-agent-delay-ms} every
   retry path in this namespace spends: the run's options under the ctx's
   :retry-policy stage config, so the budget the loop reads is the budget
   spent. The 2-arity reuses the attempt's one stage read; the 1-arity takes
   the compaction path's own, which tolerates a ctx-less state — the run's
   options stand alone."
  ([agent]
   (stage-retry-policy agent (loop-stages/for-ctx (:ctx agent))))
  ([agent stages]
   (merge (select-keys @(:cfg agent) [:max-retries :base-delay-ms :max-agent-delay-ms])
          (:config (:retry-policy stages)))))

(defn- summarize!
  "LLM summarization of the pre-cut entries (pi: generateSummaryWithUsage via
   completeSummarization, retried with the configured retry policy). Returns
   {:summary str :usage usage-map} — usage is the summarization call's
   cost-attached provider usage, recorded on the compaction entry so the
   footer totals include it (pi: CompactionEntry.usage) — {:error message}
   when the call failed (pi: the `Summarization failed: …` the caller
   wraps), or nil when a core/run cancel fired mid-call (the session
   is left untouched)."
  [agent prep & [custom-instructions reason]]
  (let [provider @(:provider agent)
        max-tokens (when-let [mrec (models/get-model provider @(:model agent))]
                     (summarization-max-tokens (:settings prep) mrec @(:thinking agent)))
        signals [(:signal agent) (:compaction-signal agent)]
        msgs (core/summarization-messages
              (:messages prep) (:previous-summary prep) custom-instructions)
        result (retry/retry-call!
                #(summarization-call agent msgs
                                     {:signals signals
                                      :thinking @(:thinking agent)
                                      :max-tokens max-tokens})
                (stage-retry-policy agent)
                (apply concurrent/or-signal signals)
                (summarization-retry-callbacks agent {:source :compaction
                                                      :reason reason}))]
    (cond
      (= :aborted (:stop-reason result)) nil
      (seq (:tool-calls result))
      {:error "Summarization attempted to call a tool"}
      :else
      (if-let [failure (core/summarization-failure result "Summarization")]
        {:error failure}
        (do
          ;; pi appends whatever contentText returned — an empty string
          ;; included; log it so an empty summary stays diagnosable
          (when (str/blank? (:text result))
            (debug/log "Warning: summarization returned an empty summary"))
          {:summary (:text result) :usage (:usage result)})))))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation (pi:
   generateBranchSummary, retried with the configured retry policy).
   Returns {:summary str :usage usage-map} with the branch preamble applied
   (usage = the summarization call's cost-attached provider usage, recorded
   on the branch-summary entry so the footer totals include it — pi:
   BranchSummaryEntry.usage), {:aborted true} when the signal fired
   mid-call, or {:error message} when the call failed (pi:
   BranchSummaryResult.error — surfaces as `Branch summarization failed:
   …`). SIGNAL (optional, defaults to the agent's cancel signal) aborts the
   call. REPLACE-INSTRUCTIONS? (pi: replaceInstructions) makes
   CUSTOM-INSTRUCTIONS replace the builtin branch-summary prompt instead of
   being appended."
  [agent entries & [custom-instructions signal replace-instructions?]]
  (let [provider @(:provider agent)
        model (models/get-model provider @(:model agent))
        ;; pi: branch summaries cap their output at a flat 4096 tokens
        ;; (branch-summarization.ts maxTokens) — independent of the
        ;; compaction reserve
        max-tokens (min 4096 (or (:max-tokens model) 4096))
        ;; pi: the summarized entries are budgeted to the model's context
        ;; window minus branchSummary.reserveTokens (prepareBranchEntries
        ;; keeps whole entries newest-first)
        entries (core/prepare-branch-entries
                 entries
                 (- (or (:context-window model) 128000)
                    (or (:branch-summary-reserve-tokens agent) 16384)))
        signal (or signal (:signal agent))]
    (if (empty? entries)
      ;; pi: nothing fits the budget — no summarization call
      {:summary "No content to summarize"}
      (let [msgs (core/branch-summary-messages
                  (vec (mapcat session/context-messages entries))
                  custom-instructions
                  replace-instructions?)
            result (retry/retry-call!
                    #(summarization-call agent msgs {:signals [signal]
                                                     :max-tokens max-tokens})
                    (stage-retry-policy agent)
                    signal
                    (summarization-retry-callbacks agent {:source :branch-summary}))]
        (cond
          (= :aborted (:stop-reason result)) {:aborted true}
          (seq (:tool-calls result))
          {:error "Branch summarization attempted to call a tool"}
          :else
          (if-let [failure (core/summarization-failure result "Branch summarization")]
            {:error failure}
            (do
              ;; pi prepends the preamble to whatever contentText returned —
              ;; an empty response yields the preamble alone; log it so an
              ;; empty summary stays diagnosable
              (when (str/blank? (:text result))
                (debug/log "Warning: branch summarization returned an empty summary"))
              {:summary (str core/branch-summary-preamble (:text result))
               :usage (:usage result)})))))))

(defn omit-recovery-attempt!
  "Durably omit a failed attempt's session entry from every future model
   projection (pi: _omitRecoveryAttempt): a nil context edit keeps the raw
   entry in the branch while dropping it from the projection, then the live
   context is rebuilt from the projection (pi: _refreshFinalizedContext), so
   a retry, the recovery compaction, or a later rebuild cannot re-send it.
   pi also omits the attempt's terminal tool results; an errored response's
   tool calls never execute, so there are none to target. ENTRY is the
   errored assistant entry record-abandoned-attempt! persisted (nil without
   a session)."
  [agent entry]
  (when-let [sess (:session agent)]
    (when-let [id (:id entry)]
      (session/append-context-edit! sess id nil))
    (state/refresh-context-from-session! agent)))

(defn- record-compaction!
  "Append the compaction entry for SUMMARY-RESULT (pi: appendCompaction),
   rebuild the in-memory context to mirror it, and return the result map
   (pi: CompactionResult — :summary, :first-kept-id, :tokens-before,
   :estimated-tokens-after, :usage, :details, plus the appended session
   :entry). Returns nil when FIRST-KEPT-ID is no longer in the branch, so the
   caller can report a failure instead of a silent no-op. FROM-EXTENSION?
   records the extension origin on the entry (pi:
   CompactionEntry.fromHook).

   Mirrors the new context into the UI (pi: compaction_end → the interactive
   mode clears the chat and re-renders the compacted context, showing the
   compaction summary entry). Without this the transcript keeps the
   pre-compaction messages and the compaction is invisible until the session
   is reloaded. The rebuild uses context order (summary first, then kept
   tail), so the live view matches the reloaded view; pi's live path appends
   the summary at the bottom (its own replay renders it first) — kmet keeps
   one order."
  [agent sess summary-result first-kept-id tokens-before from-extension?]
  (when-let [entry (session/compact-with-summary!
                    sess
                    (:summary summary-result)
                    first-kept-id
                    (cond-> {:tokens-before tokens-before}
                      (:usage summary-result)
                      (assoc :usage (:usage summary-result))
                      (:details summary-result)
                      (assoc :details (:details summary-result))
                      from-extension?
                      (assoc :from-hook true)))]
    (state/refresh-context-from-session! agent)
    (state/emit agent {:type :context-replaced :messages @(:messages agent)})
    (debug/log "compacted session with LLM summary")
    {:summary (:summary summary-result)
     :first-kept-id first-kept-id
     :tokens-before tokens-before
     :estimated-tokens-after (core/estimated-tokens
                              (session/build-context-messages sess))
     :usage (:usage summary-result)
     :details (:details summary-result)
     :entry entry}))

(defn- finish-compaction!
  "Emit compact-context! completion events and return its terminal value."
  [agent reason result from-extension? aborted? failed? succeeded? error-message]
  (reset! (:compaction-error agent) error-message)
  (when succeeded?
    (state/emit agent {:type :session-compact
                       :compaction-entry (:entry result)
                       :from-extension from-extension?
                       :reason reason
                       :will-retry (= reason :overflow)}))
  (state/emit agent {:type :compaction-end
                     :reason reason
                     :from-extension from-extension?
                     :aborted aborted?
                     :result (when succeeded? (dissoc result :entry))
                     :error-message error-message
                     :will-retry (and succeeded? (= reason :overflow))})
  (when (or failed? aborted?)
    (state/emit agent {:type :session-compact-failed
                       :reason reason
                       :error-message error-message
                       :from-extension from-extension?
                       :aborted aborted?
                       :will-retry false})
    (reset! (:compaction-blocked agent) true))
  (cond
    (= result ::cancelled) false
    succeeded? true
    :else result))

(defn- prepare-compaction
  "Gather the compaction plan for REASON (pi: prepareCompaction): the branch
   entries, the settings, and the prepared cut. Returns nil when there is
   nothing to compact, {:problem msg} for pi's manual already-compacted case,
   and {:entries es :prep p} when there is something to summarize."
  [agent reason]
  (let [sess (:session agent)
        entries (session/get-branch sess)
        settings (compaction-settings agent)
        prep (some-> (core/prepare entries (:keep-recent-tokens settings))
                     (assoc :settings settings))]
    (if (or (nil? prep) (empty? (:messages prep)))
      ;; pi: compact() separates an already-compacted session from one with
      ;; nothing to summarize
      (when (and (= :manual reason)
                 (= :compaction (:role (last entries))))
        {:problem "Already compacted"})
      {:entries entries :prep prep})))

(defn- ask-before-compact!
  "Fire the :session-before-compact boundary (pi: session_before_compact):
   extensions may cancel the compaction or supply its content.
   Returns {:cancelled true}, else {:supplied <the handler's :compaction map,
   or nil>}."
  [agent reason custom-instructions entries prep]
  (let [before (state/emit agent {:type :session-before-compact
                                  :preparation prep
                                  :branch-entries entries
                                  :custom-instructions custom-instructions
                                  :reason reason
                                  :will-retry (= reason :overflow)
                                  :signal (:compaction-signal agent)})]
    (if (:cancel before)
      {:cancelled true}
      {:supplied (:compaction before)})))

(defn- extension-summary
  "The summary a :session-before-compact handler supplied, in summarize!'s
   shape; nil when it carries neither a summary nor a cut point (pi: the
   handler must supply both to replace the summarizer)."
  [supplied]
  (when (and supplied
             (or (:summary supplied)
                 (:first-kept-id supplied)))
    {:summary (str (:summary supplied))
     :first-kept-id (:first-kept-id supplied)
     :tokens-before (:tokens-before supplied)
     :usage (:usage supplied)
     :details (:details supplied)}))

(defn- apply-summary!
  "Apply SUMMARY-RESULT to SESS: :aborted when a signal fired, :failed
   (recording why in FAILURE) when the summarizer failed or the cut entry
   vanished from the branch, else the compaction result map."
  [agent sess prep summary-result failure from-extension]
  (cond
    (compaction-aborted? agent)
    :aborted

    (:error summary-result)
    (do (debug/log "Warning: summarization failed; compaction skipped")
        (vreset! failure (:error summary-result))
        :failed)

    :else
    (if-let [compacted
             (record-compaction!
              agent sess summary-result
              (or (:first-kept-id summary-result)
                  (:first-kept-id prep))
              (or (:tokens-before summary-result)
                  (:tokens-before prep))
              @from-extension)]
      compacted
      ;; the first-kept entry vanished from the branch — nothing was
      ;; appended, so this is a failure, not a silent no-op
      (do (debug/log
           "Warning: compaction target entry not found; compaction skipped")
          (vreset! failure
                   "the first kept entry is no longer in the session")
          :failed))))

(defn- compact-with-session!
  "The compaction body (pi: prepareCompaction → session_before_compact →
   summarize → appendCompaction) for SESS. Returns the compaction result map,
   false when there is nothing to compact, :failed, :aborted, or ::cancelled
   when a handler cancelled. FAILURE and FROM-EXTENSION are the caller's
   reporting volatiles — they outlive this body, since the caller's catch
   reports into FAILURE too."
  [agent sess reason custom-instructions failure from-extension]
  (if (compaction-aborted? agent)
    :aborted
    (let [{:keys [entries prep problem] :as plan} (prepare-compaction agent reason)]
      (cond
        problem
        (do (vreset! failure problem) :failed)

        (nil? plan)
        false

        :else
        (let [{:keys [cancelled supplied]}
              (ask-before-compact! agent reason custom-instructions entries prep)]
          (if cancelled
            ::cancelled
            (let [supplied-summary (extension-summary supplied)
                  summary-result
                  (if supplied-summary
                    (do (vreset! from-extension true)
                        supplied-summary)
                    (summarize! agent prep custom-instructions reason))]
              (apply-summary! agent sess prep summary-result failure
                              from-extension))))))))

(defn- compaction-outcome
  "The reporting view of RESULT for finish-compaction!: what status to report
   (:succeeded? / :aborted? / :failed?) and the failure message."
  [reason result failure]
  (let [failed? (= result :failed)]
    {:succeeded? (map? result)
     :aborted? (or (= result :aborted) (= result ::cancelled))
     :failed? failed?
     :error-message (when failed?
                      (core/compaction-failure-message reason failure))}))

(defn compact-context!
  "LLM-based compaction (pi: prepareCompaction → compact): summarize the
   pre-cut entries, append a compaction entry (append-only — summarized
   entries stay in the file, build-context excludes them), and rebuild the
   in-memory context to mirror it. Also the manual /compact path
   (pi: session.compact) — custom-instructions are appended to the
   summarization prompt, and a manual compaction (reason :manual) starts
   with a fresh abort state: Escape's run-cancel signal is cleared, so a
   stale one cannot abort it. A manual compaction of an already-compacted
   session fails with pi's `Already compacted`; the automatic paths stay
   silent for both that and an empty session.

   Emits :compaction-start/:compaction-end around the work (pi:
   compaction_start/compaction_end — the end carries the pi-shaped result
   map, and :from-extension true when the summary came from a
   :session-before-compact handler's :compaction result), and
   :session-compact on success (pi: session_compact, carrying the appended
   entry). A failed or aborted compaction ends with :compaction-end
   :result nil, :will-retry false, and a :session-compact-failed follow-up;
   the end carries :aborted true when the compaction's own signal fired
   mid-compaction (escape — pi: session.abortCompaction), the run's cancel
   signal fired (a run cancel aborts compaction too), or an extension's
   :session-before-compact handler returned {:cancel true} (pi: aborted
   compaction_end), in which cases the session is left untouched. An
   exception escaping the compaction body — the summarization call reports
   rather than throws — is caught the way pi's compact() catches: the
   events still fire (reported :failed, or :aborted when a cancel signal
   had fired) and the error is logged, so an unexpected failure can never
   leave the UI on the compaction indicator.

   Returns true when a compaction happened, false when there was nothing to
   compact (or compaction is already in progress, or an extension cancelled
   it), and :aborted when the user cancelled mid-compaction. A compaction
   that did not complete — :failed, :aborted, or an extension cancel —
   blocks this prompt's automatic checks (see maybe-compact!); the next
   prompt starts fresh."
  [agent & [custom-instructions reason]]
  (if @(:compacting? agent)
    false
    (let [reason (if custom-instructions :manual (or reason :auto))]
      ;; A manual compaction is a fresh operation (pi: session.compact aborts
      ;; the current operation, then creates a new AbortController): clear the
      ;; run's cancel signal, which a previous Escape leaves set until the
      ;; next run — a stale true would abort the compaction immediately. The
      ;; auto paths keep it: an Escape at run start must still abort them.
      (when (= :manual reason)
        (state/set-run-signal! agent false))
      ;; Every compaction starts with a fresh abort state (pi: a new
      ;; AbortController per run) — the previous compaction's signal must not
      ;; leak into this one.
      (reset! (:compaction-signal agent) false)
      ;; Flag first: the :compaction-start emit is synchronous and shows the
      ;; UI indicator, so an escape arriving with that indicator must already
      ;; see the compaction as in progress (pi: the controller is installed
      ;; before compaction_start fires); the try keeps the flag from leaking
      ;; when the emit throws.
      (reset! (:compacting? agent) true)
      (try
        (state/emit agent {:type :compaction-start :reason reason})
        (let [failure (volatile! nil)
              from-extension (volatile! false)
              ;; pi: compact() / _runAutoCompaction's catch — an unexpected
              ;; error still ends the compaction (compaction_end +
              ;; session_compact_failed) instead of escaping: the caller's
              ;; future swallows it and the UI would stay on the compaction
              ;; indicator. A throw after the abort signal fired reports as
              ;; an aborted compaction (pi: aborted = signal.aborted);
              ;; otherwise its message rides the reason-prefixed failure
              ;; report.
              result
              (try
                (if-let [sess (:session agent)]
                  (compact-with-session! agent sess reason custom-instructions
                                         failure from-extension)
                  false)
                (catch Exception e
                  (debug/log-error "compaction failed unexpectedly: " e)
                  (let [aborted? (compaction-aborted? agent)]
                    (when-not aborted?
                      (vreset! failure (or (ex-message e) (str e))))
                    (if aborted? :aborted :failed))))
              {:keys [succeeded? aborted? failed? error-message]}
              (compaction-outcome reason result @failure)]
          (finish-compaction! agent reason result @from-extension
                              aborted? failed? succeeded? error-message))
        (finally
          (reset! (:compacting? agent) false))))))

(defn maybe-compact!
  "Proactively compact before a run (pi: _checkCompaction — threshold case).
   Triggers when the measured context usage comes within reserve tokens of
   the model's context window (pi: contextTokens > contextWindow -
   reserveTokens) — the default trigger — or when an explicit
   :compact-token-threshold (estimated tokens) is configured. Gated by the
   :auto-compact flag (pi: autoCompact); overflow recovery is separate and
   always available. A compaction that aborted or failed earlier in this
   prompt stands down too (:compaction-blocked — pi: skipAbortedCheck,
   extended). Returns true when compaction happened."
  [agent]
  ;; nil session, auto-compact off, or a blocked compaction this prompt →
  ;; no proactive compaction
  (if-let [sess (and (not @(:compaction-blocked agent))
                     (:auto-compact @(:cfg agent))
                     (:session agent))]
    (let [projected (session/project-context sess)
          branch (session/get-branch sess)
          projected-msgs (vec (mapcat :messages projected))
          token-threshold (:compact-token-threshold agent)
          window (:context-window @(:cfg agent))
          reserve (:reserve-tokens (compaction-settings agent))
          window-reserve (when window (- window reserve))
          ;; Measure the context that would be sent, not the whole file:
          ;; compaction is append-only, so the full branch never shrinks —
          ;; counting it would re-trigger compaction every turn after the
          ;; first one (pi: the threshold is evaluated against the context).
          ;; Context edits change what the provider sees, so an edited
          ;; projection measures itself (pi: estimateProjectedContextTokens;
          ;; a stale edit usage source falls back to a pure estimate).
          measured (if (some #(= :context-edit (:role (:source %))) projected)
                     (core/projected-context-tokens projected branch)
                     (core/context-tokens branch))
          has-usage? (boolean (some core/assistant-usage-tokens branch))
          tokens (or measured (core/estimated-tokens projected-msgs))]
      (if (or (and token-threshold (>= tokens token-threshold))
              ;; the window check requires a positive window-reserve and fresh
              ;; measured usage: right after a compaction the last measured
              ;; usage reflects the old context (context-tokens is nil), and
              ;; without any usage data the estimate is too unreliable (pi:
              ;; estimate with lastUsageIndex null → no compaction)
              (and window-reserve (pos? window-reserve)
                   has-usage? measured (>= measured window-reserve)))
        (let [result (compact-context! agent nil :threshold)]
          (and (not= :aborted result) result))
        false))
    false))

(defn compact-for-overflow!
  "Force-compact after a context-overflow error so the retried call fits
   (pi: _checkCompaction — overflow case). Returns true when compaction
   happened."
  [agent]
  (when-let [sess (:session agent)]
    (when (pos? (count @(:entries sess)))
      (compact-context! agent nil :overflow))))

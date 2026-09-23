(ns kmet.app.tools.invoke
  "The shared tool-invocation pipeline — one implementation for the agent
   loop's batches and the script sandbox's bridge:

     prepare-tool-call  — the before hook (pi: beforeToolCall): pass, rewrite
                          args, or block the call with a result
     execute-tool-call  — execute through execute-tool under the caller's
                          worker bindings; exceptions become error results
     finish-tool-call   — the after hook (pi: afterToolCall): result overrides
     run-tool-call      — execute + finish (the script bridge's per-call path)

   Each caller keeps its own concurrency and bookkeeping: the loop emits
   events and appends context/session entries (and splits prepare from
   execute so a batch prepares sequentially and runs concurrently), the
   script bridge returns promises and keeps a call trace. The registry's
   `execute-tool` remains the per-tool dispatcher (argument normalization,
   :prepare-arguments, :contextual?/:streams? shapes); it is passed in rather
   than required so kmet.app.tools.script can use this namespace without a
   require cycle (the registry requires script)."
  (:require [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.util :as tool-util]))

(defn blocked-result
  "The canonical result of a blocked call (pi: beforeToolCall :block). The
   hook's :terminate rides through — the loop's batch handling reads it; the
   script bridge drops it (there is no batch)."
  [hook-result]
  (cond-> {:content (or (:reason hook-result) "Tool execution was blocked")
           :is-error true}
    (:terminate hook-result) (assoc :terminate true)))

(defn hook-payload
  "The context a tool hook receives (pi: beforeToolCall/afterToolCall). The
   script bridge has no wire id for an inner call and no per-call assistant
   message: TOOL-CALL-ID is synthetic there and ASSISTANT-MESSAGE may be
   nil, so both keys are omitted when absent."
  [{:keys [tool-name args tool-call-id assistant-message]}]
  (cond-> {:tool-name tool-name :args args}
    tool-call-id (assoc :tool-call-id tool-call-id)
    assistant-message (assoc :assistant-message assistant-message)))

(defn prepare-tool-call
  "Run the before hook for one call. Returns nil when there is no hook or it
   passed (keep the original args), {:args rewritten} when it rewrote them,
   and {:block result} when it blocked — the caller settles the call with
   RESULT without executing. A throwing hook blocks with the error as the
   reason (pi parity)."
  [{:keys [before-hook] :as call}]
  (when before-hook
    (let [r (try
              (before-hook (hook-payload call))
              (catch Exception e
                {:block true
                 :reason (str "before-tool-call hook error: " (ex-message e))}))]
      (cond
        (:block r) {:block (blocked-result r)}
        (contains? r :args) {:args (:args r)}))))

(defn execute-tool-call
  "Execute one call through EXECUTE-TOOL. BINDINGS, when given, are applied
   around execution ({:signal :session-env-fn :cwd}) — a raw worker thread
   conveys no dynamic bindings, which is the script bridge's pool-worker
   case; the loop's futures already convey the run-wide ones. Returns the
   result map; never throws — a tool exception becomes an error result."
  [execute-tool {:keys [tool-name args signal ctx on-update tools bindings]}]
  (let [execute #(execute-tool tool-name args
                               (cond-> {}
                                 signal (assoc :signal signal)
                                 ctx (assoc :ctx ctx)
                                 on-update (assoc :on-update on-update)
                                 tools (assoc :tools tools)))]
    (try
      (if bindings
        (binding [bash-tool/*cancel-signal* (:signal bindings)
                  bash-tool/*session-env-fn* (:session-env-fn bindings)
                  tool-util/*cwd* (:cwd bindings)]
          (execute))
        (execute))
      (catch Throwable t
        {:content (str "Error executing " tool-name ": " (ex-message t))
         :is-error true}))))

(defn finish-tool-call
  "Run the after hook over RESULT: nil (or a nil return) leaves it unchanged;
   {:content ...} / {:is-error ...} override; a throwing hook replaces the
   result with the error (pi parity)."
  [call result]
  (if-let [hook (:after-hook call)]
    (try
      (if-let [r (hook (assoc (hook-payload call)
                              :result result
                              :is-error (:is-error result false)))]
        (cond-> result
          (:content r) (assoc :content (:content r))
          (contains? r :is-error) (assoc :is-error (:is-error r)))
        result)
      (catch Exception e
        {:content (str "after-tool-call hook error: " (ex-message e))
         :is-error true}))
    result))

(defn run-tool-call
  "execute-tool-call + finish-tool-call — the script bridge's whole per-call
   pipeline (its before hook runs through prepare-tool-call first)."
  [execute-tool call]
  (finish-tool-call call (execute-tool-call execute-tool call)))

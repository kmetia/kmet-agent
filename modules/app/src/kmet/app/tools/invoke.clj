(ns kmet.app.tools.invoke
  "The shared tool-invocation pipeline — one implementation for the agent
   loop's batches and the codemode sandbox's bridge:

     tool-point        — the one point: the run's around hooks outside, the
                         permission check at the innermost `next`, the body
                         beneath it (chain → check → execute)
     execute-tool-call — execute through execute-tool under the caller's
                         worker bindings; a throwing tool becomes an error
                         result

   Each caller keeps its own concurrency and bookkeeping: the loop emits
   events and appends context/session entries, the script layer's bridge
   returns promises and keeps a call trace. The registry's `execute-tool`
   remains the per-tool dispatcher (argument normalization, :prepare-arguments,
   :contextual?/:streams? shapes); it is passed in rather than required so
   kmet.app.tools.script can use this namespace without a require cycle (the
   registry builds the executor the codemode tool hands it)."
  (:require [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.util :as tool-util]))

(def execute-tool-contract
  "The injected per-tool dispatcher. `kmet.app.tools.registry/execute-tool`
   satisfies it (the registry builds the codemode record's seam from it), and
   the loop's tool executor presents the same shape:

     (fn [tool-name args {:keys [signal ctx on-update tools]}]
       {:content … :is-error …})

   Passed in rather than required: the executor is a value, so this
   namespace stays below the registry and the tool layer shares one pipeline
   without a require cycle. A missing executor is a programming error, not a
   tool failure, so it throws a named error rather than degrading into one."
  nil)

(defn- executor
  "EXECUTE-TOOL, or the named error for a caller that forgot to pass one."
  [execute-tool]
  (or execute-tool
      (throw (ex-info (str "invoke/execute-tool-call requires an execute-tool fn; "
                           "see execute-tool-contract")
                      {:cause ::missing-execute-tool}))))

(defn- blocked-result
  "The canonical result of a settled call (pi: beforeToolCall :block) — the
   shape a hook and the permission gate answer with. The hook's :terminate
   rides through: the loop's batch handling reads it, and a script's inner call
   reports it to the script."
  [hook-result]
  (cond-> {:content (or (:reason hook-result) "Tool execution was blocked")
           :is-error true}
    (:terminate hook-result) (assoc :terminate true)))

(defn tool-op
  "The op a tool point runs: what a hook receives, plus the run's cancel signal
   so the permission check can abort an `:ask` with the run. Keys absent from
   CALL are omitted: TOOL-CALL-ID is synthetic for a scripted inner call,
   ASSISTANT-MESSAGE is nil outside the loop's batch (extension code calling
   the tool directly)."
  [{:keys [tool-name args tool-call-id assistant-message signal]}]
  (cond-> {:tool-name tool-name :args args}
    tool-call-id (assoc :tool-call-id tool-call-id)
    assistant-message (assoc :assistant-message assistant-message)
    signal (assoc :signal signal)))

(defn tool-point
  "THE tool point: CHAIN over EXECUTE with CHECK innermost, one dispatch for the
   loop's batches and the codemode sandbox.

   CHAIN is the run's around hooks, `(fn [op next] → result)`, nil meaning no
   hooks. CHECK — `(fn [op])` → nil when the call may proceed, else the block
   result — sits at the chain's innermost `next`, never as an element of it: a
   hook registered after the gate must not be able to rewrite the arguments the
   policy judged. EXECUTE runs one call over the effective op and returns its
   result.

   A hook that settles the call with a truthy `:block` — `{:block true
   :reason …}`, the shape the permission check answers and the extensions'
   veto — gets the canonical blocked result, so one convention covers both.
   No tool result carries :block, so a settlement is told apart from one."
  [{:keys [chain check execute]} op]
  (let [body (fn [op] (or (when check (check op)) (execute op)))
        result ((or chain (fn [op next] (next op))) op body)]
    (if (and (map? result) (:block result))
      (blocked-result result)
      result)))

(defn execute-tool-call
  "Execute one call through EXECUTE-TOOL. BINDINGS, when given, are applied
   around execution ({:signal :session-env-fn :shell :skill-resolver :cwd}) — a raw worker thread
   conveys no dynamic bindings, which is the codemode bridge's pool-worker
   case; the loop's futures already convey the run-wide ones. Returns the
   result map; never throws for a tool failure — a throwing tool becomes an
   error result (a missing EXECUTE-TOOL does throw: see
   execute-tool-contract). Catches Throwable, not just Exception: a pool
   worker must settle its promise whatever the tool throws, and the loop gets
   the same per-call isolation (a tool Error cannot take the run down)."
  [execute-tool {:keys [tool-name args signal ctx on-update tools bindings]}]
  (let [execute-tool (executor execute-tool)
        execute #(execute-tool tool-name args
                               (cond-> {}
                                 signal (assoc :signal signal)
                                 ctx (assoc :ctx ctx)
                                 on-update (assoc :on-update on-update)
                                 tools (assoc :tools tools)))]
    (try
      (if bindings
        (binding [bash-tool/*cancel-signal* (:signal bindings)
                  bash-tool/*session-env-fn* (:session-env-fn bindings)
                  bash-tool/*shell* (:shell bindings)
                  read-tool/*skill-resolver* (:skill-resolver bindings)
                  tool-util/*cwd* (:cwd bindings)]
          (execute))
        (execute))
      (catch Throwable t
        {:content (str "Error executing " tool-name ": " (ex-message t))
         :is-error true}))))

(ns kmet.app.eval
  "The `:eval` service: one persistent SCI environment per agent.

   The environment is built on the first call and reused: definitions made by
   one call are visible to the next, and the last namespace a successful call
   ended in is re-entered at the start of the next. One lock serializes
   parallel calls, and the deadline clock starts after it is taken; the
   environment is disposed with its agent (`:dispose!`).

   This is the memory-only stage: nothing is written to disk, the kept index
   and eval sources are empty, and `kmet.app.tools.eval` exposes only `code`,
   `timeout` and the output budget. The store, kept definitions and sources
   land in later steps."
  (:require [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.srce.core :as srce]))

(defn value
  "The `:eval` service for AGENT-CTX. The context, interrupt holder and sticky
   namespace are owned here; the `:tools` handle is resolved from AGENT-CTX on
   the first call, so the script surface is the agent's own table."
  [agent-ctx]
  (let [lock (Object.)
        interrupt (script/interrupt-holder)
        sci-ctx (atom nil)
        sticky-ns (atom nil)
        disposed? (atom false)
        sci-context (fn []
                      (or @sci-ctx
                          (reset! sci-ctx
                                  (script/context (script/base-namespaces)
                                    (script/interrupt-fn interrupt "eval")
                                    "eval"))))
        eval! (fn [{:keys [code timeout limits signal on-update ctx]}]
                (locking lock
                  (when @disposed?
                    (throw (ex-info "the agent's eval service is disposed"
                                    {:type :eval/disposed})))
                  (let [handle (or (srce/get agent-ctx :tools)
                                   (throw (ex-info "the eval service needs the agent's :tools service"
                                                   {:type :eval/no-tools})))]
                    (script/run {:tool "eval"
                                 :code code
                                 :timeout timeout
                                 :limits limits
                                 :signal signal
                                 :ctx ctx
                                 :on-update on-update
                                 :sci-ctx (sci-context)
                                 :interrupt interrupt
                                 :ns-atom sticky-ns
                                 :get-all-tools #(registry/get-all-tools handle)
                                 :get-contributed-tools #(registry/get-contributed-tools handle)
                                 :select-tools registry/select-tools
                                 :execute-tool #(registry/execute-tool handle %1 %2 %3)}))))]
    {:eval! eval!
     :kept (constantly [])
     :sources (constantly [])
     :failures (constantly [])
     :dispose! (fn []
                 (locking lock
                   (reset! disposed? true)
                   (reset! sci-ctx nil)
                   (reset! sticky-ns nil)
                   nil))}))

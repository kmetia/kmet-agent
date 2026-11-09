(ns kmet.app.invocation
  "The invocation surface: what the command line asks a run for, in the two
   shapes the entry point and a mode each need.

   The run's two halves stay apart: `resolve-overrides` is the entry's half —
   `--model`/`--models` resolve against the catalog after the process world
   loads, and the rest of the run flags — thinking, the system prompt, the
   session dir — reach the loaded config through `cfg/apply-cli-overrides`,
   which every front end calls. `resolve-overrides` is the pattern half —
   patterns become the catalog's ids after the process world loads (an
   extension provider is a catalog entry only then) and before the mode mounts —
   and `create-opts` is a mode's, the map `create!` takes so the driver applies
   the overrides to the settings it loads.

   Both are pure over their inputs; the catalog is `kmet.ai.models`', as it is
   for kmet1. The session-lifecycle flags are absent on purpose: which session a
   run opens is the mode's, and it says so on its row (`:session-lifecycle`)."
  (:require [kmet.app.model-resolver :as resolver]
            [kmet.ai.models :as models]))

(def run-override-keys
  "kmet1's run flags that ride the agent, not the process: a mode takes them as
   `create!` options and the driver applies them over the config it loads
   (`cfg/apply-cli-overrides`). `--model` is the one the factory resolves
   itself, per creation, so an agent's model is an option of the agent; the
   entry has already resolved it for a mode that runs kmet1's front end.
   `--tools` rides here as a session override: the driver applies it to the
   created agent exactly as `/tools` does, never to the persisted selection.
   `--no-context-files` rides here too: the driver skips project-context
   discovery for the agent's system prompt."
  [:model :provider :thinking :models :tools :no-context-files :session-dir :system-prompt
   :append-system-prompt])

(defn create-opts
  "The `create!` options CONFIG carries: the run overrides, and nothing of the
   mode's own (`:prompt`, `:format`, `:on-result`). An invocation that named
   none keeps the settings' values, because the keys are simply absent."
  [config]
  (select-keys config run-override-keys))

(defn- warn! [message]
  (binding [*out* *err*]
    (println "Warning:" message)))

(defn resolve-overrides
  "OPTS with kmet1's two model flags resolved against the catalog — after the
   process world loads, so an extension-registered provider is a catalog entry,
   and before the mode mounts.

   `--model` is a pattern (`provider/model[:thinking]`, the provider inferred
   when absent) and becomes the provider it names, the catalog's model id, and
   the pattern's thinking level when it carried one — unless the invocation set
   `--thinking` explicitly, which wins. `--models` is the session's scope and
   becomes model ids, with a warning for each pattern nothing matches (kmet1's
   `resolveModelScope`).

   A model the catalog does not have is `:kmet.app/unknown-model` with kmet1's
   wording — the entry point reports it as a configuration error, exit 1, not a
   request that dies later with nothing to say. An invocation that named
   neither flag is returned untouched and never reads the catalog."
  [opts]
  (let [opts (if (nil? (:model opts))
               opts
               (let [{:keys [provider model-id thinking-level warning error]}
                     (resolver/resolve-cli-model (:provider opts) (:model opts)
                                                 (models/get-models))]
                 (when error
                   (throw (ex-info error {:type :kmet.app/unknown-model
                                          :model (:model opts)})))
                 (when warning (warn! warning))
                 (cond-> opts
                   provider (assoc :provider provider)
                   model-id (assoc :model model-id)
                   (and thinking-level (nil? (:thinking opts)))
                   (assoc :thinking thinking-level))))
        opts (if (nil? (:models opts))
               opts
               (let [{resolved :models warnings :warnings}
                     (resolver/resolve-model-scope (:models opts)
                                                   (models/get-models))]
                 (doseq [w warnings] (warn! w))
                 (assoc opts :models resolved)))]
    opts))

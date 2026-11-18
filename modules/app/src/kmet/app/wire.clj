(ns kmet.app.wire
  "The wire projection of an event for a headless consumer.

   A mode that serializes (`print`'s edn output, `rpc`, `server`) writes what
   this namespace answers instead of the event as emitted: transport belongs
   with the transport, not with the bus (`kmet.app.events`). The vocabulary
   itself is `kmet.app.events`; this namespace knows no event names beyond the
   projection rules.")

(defn wire-error
  "An event's error payload as plain data: a Throwable becomes its message
   string — the wire carries no exception type and pr-str of a Throwable is
   not EDN-readable (in-process events keep the object). Other values pass
   through."
  [error]
  (if (instance? Exception error)
    (or (ex-message error) (str error))
    error))

(defn- update-error-slot
  "Apply the wire-error projection to K when EVENT carries it; an event
   without the slot passes through untouched."
  [event k]
  (cond-> event
    (contains? event k) (update k wire-error)))

(defn wire-event
  "The wire projection of EVENT for a headless consumer (print mode's edn
   output, rpc/server): the event as emitted, minus the cumulative assistant
   snapshot :message-update carries — the delta already names a tool call's
   id/name, and the finalized message follows as :message-end, so the snapshot
   only repeats bytes per delta (pi: toJsonEvent) — with error payloads
   stringified (see wire-error). A live control object never crosses: `:signal`
   (an abort atom a boundary or hook may carry in-process) is dropped, because
   the wire is EDN. Every other event passes through.

   Extension-only events (`:public false` in the vocabulary) never reach here:
   `kmet.app.events/on-every` does not subscribe them."
  [event]
  (dissoc (case (:type event)
            :message-update (dissoc event :message)
            :error (update-error-slot event :message)
            :compaction-flush-failed (update-error-slot event :error)
            :auto-retry-end (update-error-slot event :final-error)
            event)
          :signal))

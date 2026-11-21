(ns kmet.ui.tui.pending-messages
  "PendingMessages — queued steering/follow-up message display between the
   chat and the status indicator (pi: updatePendingMessagesDisplay).
   Renders nothing when both queues are empty; otherwise a leading blank
   line, one dim line per queued message (\"Steering: ...\" /
   \"Follow-up: ...\"), and a dequeue hint (\"↳ <key> to edit all queued
   messages\"). Lines are TruncatedText-shaped: truncated ANSI-aware with an
   ellipsis instead of wrapping. The caller keeps pending bash components
   in the same container."
  (:require [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent PendingMessages nil [steering-atom follow-up-atom hint-atom cache-atom]
  (render [this width]
    (track! this width
      (let [steering @steering-atom
            follow-up @follow-up-atom]
        (if (and (empty? steering) (empty? follow-up))
          []
          (into [""]
                (concat
                 (mapv #(u/truncate-to-width (theme/dim (str "Steering: " %)) width "...")
                       steering)
                 (mapv #(u/truncate-to-width (theme/dim (str "Follow-up: " %)) width "...")
                       follow-up)
                 [(u/truncate-to-width (theme/dim (str "↳ " @hint-atom " to edit all queued messages"))
                                       width "...")])))))))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-pending-messages
  "Create a PendingMessages component.
   :hint — dequeue key display text (e.g. \"Alt+Up\")."
  [& {:keys [hint] :or {hint "Alt+Up"}}]
  (map->PendingMessages {:steering-atom (atom [])
                         :follow-up-atom (atom [])
                         :hint-atom (atom hint)
                         :cache-atom (atom nil)}))

(defn pending-messages-set-queues!
  "Replace the queued steering/follow-up message lists."
  [comp steering follow-up]
  (reset! (:steering-atom comp) (vec steering))
  (reset! (:follow-up-atom comp) (vec follow-up))
  nil)
(ns kmet.ui.tui.messagebox
  "Shared box-theme plumbing for custom message components whose children are rebuilt when theme or expansion changes."
  (:require [kmet.tui.components.box :as box]
            [kmet.tui.theme :as theme]))

(defn themed-box!
  "Set the box background for THM and rebuild COMP's content. REBUILD is the component-specific callback."
  [comp thm rebuild]
  (box/box-set-bg-fn @(:box comp) #(theme/bg thm :custom-message-bg %))
  (rebuild comp @(:expanded-atom comp)))

(defn apply-theme-once!
  "Apply THM to COMP's box at most once per distinct THM, tracking via
   APPLIED-THEME-ATOM. Rebuilds content via REBUILD on a palette change."
  [comp applied-theme-atom thm rebuild]
  (when-not (identical? thm @applied-theme-atom)
    (reset! applied-theme-atom thm)
    (themed-box! comp thm rebuild)))

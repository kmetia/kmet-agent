(ns kmet.tui.components.filler
  "Fill — blank lines that take the screen height the rest of the document
   leaves unused.

   A fill renders blank lines like Spacer, but the caller does not choose the
   count: the document pass (kmet.tui.core/compose-document) sizes it per
   frame, because only the pass knows the natural height of the components
   around it and the terminal height. A fill is therefore a direct TUI child,
   not a node inside a container, and a document mounts one — the pass sizes
   the first and renders any other empty. The interactive layout mounts it
   between the transcript and the editor dock, so the dock stays at the
   screen end: the transcript grows down from the top, the fill absorbs what
   is left, and a transcript that loses a line in a streaming reflow grows
   the fill instead of pushing the editor up and leaving blank rows below
   it."
  (:require [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent Fill nil [lines-atom cache-atom]
  (render [this width]
    (track! this width
      (vec (repeat @lines-atom "")))))

(defn make-fill
  "An empty fill; compose-document sizes it on the next frame."
  []
  (map->Fill {:lines-atom (atom 0) :cache-atom (atom nil)}))

(defn fill?
  "True for a Fill component — the document pass sizes those."
  [component]
  (instance? Fill component))

(defn fill-set-lines!
  "Set the number of blank lines FILL renders. A real change invalidates the
   render cache (the tracked atom), so the next render returns a fresh blank
   vector; an unchanged count leaves the cached vector — and the document
   pass's part identity — alone."
  [fill n]
  (when (not= n @(:lines-atom fill))
    (reset! (:lines-atom fill) n)))

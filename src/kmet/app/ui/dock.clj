(ns kmet.app.ui.dock
  "Editor-dock mounting for selectors (pi: interactive-mode showSelector).
   All full-panel selectors mount the way pi's do: the component replaces
   the editor in the editor dock and takes focus; the returned done fn
   restores the previously active editor and focus (pi: done()).

   The dock also owns the *displacement* half of a panel's lifecycle (pi:
   disposeActiveSelector before every showSelector): mounting or clearing
   disposes the panel it lifts out, unless that panel was mounted
   :borrowed? — its owner re-mounts or disposes it itself (pi: the login
   dialog and extension dialogs live in editorContainer outside
   disposeActiveSelector's reach)."
  (:require [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.libs.reakt :as r]
            [kmet.tui.core :as tui]))

(def ^:private dock-generation
  "pi: activeSelectorToken — only the most recently mounted selector may
  restore the editor, so a stale done() from a replaced selector is inert
  instead of yanking the newer one out of the dock."
  (atom 0))

(defn invalidate-pending!
  "Invalidate every pending done() without touching the current dock
   contents (pi: disposeActiveSelector clears activeSelectorToken). Call
   when something else takes over the dock wholesale — a selector restored
   afterwards must not yank the new occupant out."
  []
  (swap! dock-generation inc))

(defn- install-focus-guard!
  "Ensure the ::focus-guard watch on DOCK-CURRENT. The dock is the single
   owner of the editor slot, so it is also the chokepoint that decides what
   happens to input when a panel leaves it (pi: disposeActiveSelector's
   restore). The watch — not each close path — is what makes the invariant
   hold: an occupant that leaves the dock (the slot goes empty) while still
   holding focus gets input handed to the resolver's fallback, whether
   clear! ran, a session reset wrote the atom, or some future path forgets.
   Same shape as the TUI's ::ghost-guard on the overlay stack: a watch
   cannot be bypassed by construction. Idempotent (a fixed watch key),
   never throws (it runs inside swap! on the input dispatch path).

   Displacement (a new occupant) is NOT handled here: mount! takes focus
   explicitly because it also knows the focus *target* (a selector's inner
   list), which no watch could guess."
  [cs]
  (add-watch (:dock-current cs) ::focus-guard
             (fn [_ _ old new]
               (try
                 ;; a panel left the dock (clear!, a session reset, a future
                 ;; path) — if it held the TUI's focus, hand input to the
                 ;; resolver's fallback: the topmost capturing overlay, else
                 ;; the app's focus home (dock-aware: a mounted selector
                 ;; outranks the active editor)
                 (when (and (nil? (:component new))
                            (some? (:tui cs)))
                   (tui/tui-release-focus! (:tui cs) (:component old)))
                 (catch Throwable _ nil))))
  nil)

(defn make-dock-area
  "The editor dock as a fn component (dsl.md stage 4, pi: the editorDock
   container): renders whichever panel is recorded in DOCK-CURRENT
   ({:component c} or nil), else the active editor from CURRENT-EDITOR-ATOM
   (the default or a swapped-in custom editor). Both reads are tracked, so
   mount/unmount and custom-editor swaps re-derive the tree exactly once;
   records splice foreign — reconcile swaps identity, disposes nothing.
   Panel lifecycles belong to mount!/clear! (displacement) and to the
   panel's owner (its own close)."
  [dock-current current-editor-atom]
  (fn [_props]
    (or (:component (r/tracked-deref dock-current))
        (r/tracked-deref current-editor-atom))))

(defn- dispose-displaced!
  "Dispose the occupant a new mount or clear! lifts out of the dock, unless
   it is borrowed — a borrowed panel's lifetime stays with its owner."
  [occupant]
  (when-let [c (and occupant (not (:borrowed? occupant)) (:component occupant))]
    (cda/dispose-component! c)))

(defn- displace!
  "Dispose DOCK-CURRENT's occupant unless it is COMPONENT itself (a
   re-mount) or borrowed."
  [dock-current component]
  (let [occupant (deref dock-current)]
    (when-not (identical? (:component occupant) component)
      (dispose-displaced! occupant))))

(defn mount!
  "Swap COMPONENT into CS's editor dock: record it on the :dock-current atom
   and take focus. FOCUS-TARGET (pi: showSelector's `focus`) is the component
   that receives keys — the interactive child of the panel when COMPONENT
   itself is inert chrome (pi: `focus: selector.getMessageList()`); defaults
   to COMPONENT. Returns DONE — a zero-arg fn restoring the active editor
   (current-editor-atom, so custom editors survive) and focus; re-running it
   is idempotent (equal-value reset no-op), so an accidental double call is
   harmless.

   Mounting disposes the displaced panel (pi: disposeActiveSelector at the
   top of showSelector) so a selector that never runs its own done — an
   editor swap, a session reset, another panel taking the dock — still
   unwinds its root reaction and foreign children. Pass :borrowed? true for
   a panel whose owner keeps custody and re-mounts or disposes it (the auth
   dialog a prompt selector temporarily replaces, an extension dialog the
   registry closes): the dock then leaves it alone.

   The dock also owns what happens to *input* when a panel leaves it: the
   ::focus-guard watch on :dock-current (installed here) hands focus back
   when the departing occupant held it, so no close path has to remember a
   restore. Mounting takes focus explicitly — it alone knows the focus
   target (a selector's inner list, not the chrome)."
  ([cs component]
   (mount! cs component nil {}))
  ([cs component focus-target]
   (mount! cs component focus-target {}))
  ([cs component focus-target {:keys [borrowed?]}]
   (install-focus-guard! cs)
   (displace! (:dock-current cs) component)
   (let [gen (swap! dock-generation inc)
         tui* (:tui cs)
         done (fn []
                ;; stale done() (a newer selector was mounted meanwhile)
                ;; must not restore the editor over it (pi:
                ;; activeSelectorToken check in done())
                (when (= gen @dock-generation)
                  (reset! (:dock-current cs) nil)
                  (tui/tui-set-focus tui* @(:current-editor-atom cs))
                  (tui/tui-request-render tui*)))]
     (reset! (:dock-current cs) {:component component :borrowed? (boolean borrowed?)})
     (tui/tui-set-focus tui* (or focus-target component))
     (tui/tui-request-render tui*)
     done)))

(defn clear!
  "Take the editor dock back wholesale (pi: disposeActiveSelector +
   editorContainer.clear()): pending done()s go inert and the occupant is
   disposed unless it was mounted :borrowed?. Use when something that is
   not itself a dock mount takes the editor slot — a custom-editor swap, a
   session reset, an extension dialog closing.

   Focus needs no restoring here: the ::focus-guard watch sees the occupant
   leave and hands input to the resolver's fallback (the active editor when
   nothing else captures) — including for a caller that never installs the
   guard because it never mounted anything."
  [cs]
  (install-focus-guard! cs)
  (invalidate-pending!)
  (dispose-displaced! (deref (:dock-current cs)))
  (reset! (:dock-current cs) nil))

(ns kmet.app.ui.dock
  "The editor dock: a stack of panels rendered in front of the editor
   (pi: interactive-mode showSelector + editorContainer).

   DOCK-STACK on CoreState holds entries
   {:component c :focus-target f :borrowed? b}; the top one renders and
   the entries below stay mounted but hidden, so removing what covers a
   surface reveals it again — the same shape as the overlay stack. The
   editor itself is not an entry: make-dock-area falls back to
   CURRENT-EDITOR-ATOM when the stack is empty.

   Entering:
   - mount! replaces the top (pi showSelector + disposeActiveSelector):
     the displaced top leaves the stack and is disposed unless it was
     mounted :borrowed? (the login dialog, an extension dialog the
     registry owns). Entries below it stay, so the surface a selector was
     opened from is revealed again when the selector closes.
   - cover! pushes without touching anything below (pi showAuthSelect's
     temporary editorContainer swap): the login dialog's method selector
     covers it, and closing the selector reveals the dialog.
   Both take {:focus-target f :borrowed? b}; closing is release! by
   identity, not a mount handle.

   Leaving is by membership, never by a mount token:
   - release! removes a component from wherever it sits in the stack; a
     component that is already gone is inert — the replacement semantics
     pi gets from clearing editorContainer.
   - clear! empties the stack wholesale (a custom-editor swap, a session
     reset, an extension teardown), disposing every non-borrowed entry.

   Disposal stays with the owner: removals never dispose, because a
   docked panel is often composed of several owners' parts (a compiled
   frame plus a spliced foreign list) and only the owner knows the parts.
   dispose! is the owner's ordered close for a panel: it guarantees the
   component left the stack first (see its docstring) so the dock can
   never keep rendering a corpse — the dead-on-screen class of issue #5.

   Focus: the ::focus-guard watch on DOCK-STACK is the chokepoint. When
   the focused component leaves the stack it hands input to the new top's
   :focus-target, else to the resolver's fallback (the focus home: the
   top dock target or the active editor). mount!/cover! take focus
   explicitly before publishing the stack — only they know the new
   panel's target (a selector's inner list, not its chrome))."
  (:require [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.debug :as debug]
            [kmet.libs.reakt :as r]
            [kmet.tui.core :as tui]))

(defn- entry
  [component focus-target borrowed?]
  {:component component
   :focus-target (or focus-target component)
   :borrowed? (boolean borrowed?)})

(defn top-component
  "The component the dock currently shows (the stack's top), or nil when
   the editor is all there is."
  [stack]
  (:component (peek stack)))

(defn top-focus-target
  "The component that should receive input while the dock's top renders —
   a selector's inner list, or the panel itself when it was mounted
   without an explicit focus target. Nil when the stack is empty."
  [stack]
  (:focus-target (peek stack)))

(defn- stack-has?
  "True when C is an entry's component or focus target in STACK."
  [stack c]
  (boolean (some (fn [e]
                   (or (identical? c (:component e))
                       (identical? c (:focus-target e))))
                 stack)))

(defn- ensure-focus-guard!
  "Ensure the ::focus-guard watch on DOCK-STACK — installed by
   update-stack!, the single write path, so no dock change can bypass it.
   The dock owns the editor slot, so it is also the chokepoint that
   decides what happens to input when a panel leaves it (pi:
   disposeActiveSelector's restore). The watch — not each close path —
   makes the invariant hold: whenever the component holding focus was in
   the old stack and is gone from the new one, input goes to the new top's
   focus target, else to the resolver's fallback. That covers a top
   removal revealing a covered surface, a removal from any depth, clear!,
   a bare atom reset and any future path. Same shape as the TUI's
   ::ghost-guard on the overlay stack: a watch cannot be bypassed by
   construction. Idempotent (a fixed watch key), never throws (it runs
   inside swap! on the input dispatch path).

   Taking focus does NOT happen here: a mount knows the new panel's focus
   target (a selector's inner list), which no watch could guess, so
   mount!/cover! call tui-set-focus before publishing."
  [cs]
  (when-let [dock (:dock-stack cs)]
    (add-watch dock ::focus-guard
               (fn [_ _ old new]
                 (try
                   (let [tui* (:tui cs)
                         focused (when (some? tui*) (tui/tui-focused-component tui*))
                         old (or old [])
                         new (or new [])]
                     (when (and (some? focused)
                                (stack-has? old focused)
                                (not (stack-has? new focused)))
                       (if-let [top (peek new)]
                         ;; a covered surface is revealed: input goes to its
                         ;; focus target
                         (tui/tui-set-focus tui* (:focus-target top))
                         ;; the dock emptied: the resolver's fallback
                         ;; (topmost capturing overlay, else the focus home)
                         (tui/tui-release-focus! tui* focused))))
                   (catch Throwable _ nil)))))
  nil)

(defn- update-stack!
  "Atomically replace CS's dock stack with (f stack); installs the focus
   guard first so every write path is guarded. Returns {:old old :new
   new} — the watcher has already seen :new by the time this returns, so
   callers use the pair for focus and disposal. A CS without a dock
   (minimal test states) is treated as an empty one."
  [cs f]
  (if-let [dock (:dock-stack cs)]
    (do
      (ensure-focus-guard! cs)
      (loop []
        (let [old @dock
              new (f old)]
          (if (compare-and-set! dock old new)
            {:old old :new new}
            (recur)))))
    {:old nil :new nil}))

(defn- without-component
  "STACK without every entry whose :component is COMPONENT (identity).
   Returns STACK itself when nothing matched, so a no-op removal does not
   swap the atom."
  [stack component]
  (let [kept (filterv #(not (identical? component (:component %))) stack)]
    (if (= (count kept) (count stack)) stack kept)))

(defn make-dock-area
  "The editor dock as a fn component (dsl.md stage 4, pi: the editorDock
   container): renders the stack's top component if any, else the active
   editor from CURRENT-EDITOR-ATOM (the default or a swapped-in custom
   editor). Both reads are tracked, so stack pushes/pops and custom-editor
   swaps re-derive the tree exactly once; records splice foreign —
   reconcile swaps identity, disposes nothing. Panel lifecycles belong to
   the dock ops (displacement, clear!) and to the panel's owner (its own
   close)."
  [dock-stack current-editor-atom]
  (fn [_props]
    (or (top-component (r/tracked-deref dock-stack))
        (r/tracked-deref current-editor-atom))))

(defn release!
  "Owner-callable leave: remove COMPONENT from CS's dock stack, wherever
   it sits (identity, not a mount generation). Returns true when it
   removed anything. A component that is no longer in the stack is inert,
   so a stale owner cannot yank a newer occupant.

   Removals never dispose — the owner does (see dispose!). Focus needs no
   restoring here: the ::focus-guard watch hands input to the revealed
   surface, or to the focus home."
  [cs component]
  (let [{:keys [old new]} (update-stack! cs #(without-component % component))]
    (when-not (identical? old new)
      (tui/tui-request-render (:tui cs))
      true)))

(defn dispose!
  "Dispose COMPONENT — the owner's ordered close for a panel it mounted.
   The invariant: a component is disposed only after it left every surface
   it was shown on, or the UI keeps rendering a corpse whose keys reach
   nothing (issue #5). The leave is tried first — release! for the editor
   dock, tui-hide-overlay by identity for the overlay stack — and a hit is
   a lifecycle bug: the caller skipped that surface's leave, so dispose!
   removes it (the visible failure cannot happen), records the violation in
   kmet.error.log, and with --debug throws so development trips on it
   instead of shipping another stranded panel. Handles nil."
  [cs component]
  (when (some? component)
    (let [was-mounted? (or (release! cs component)
                           (tui/tui-hide-overlay (:tui cs) component))]
      (when was-mounted?
        (debug/log-error
         "disposed a component that was still mounted (removed first):"
         (type component)))
      (cda/dispose-component! component)
      (when (and was-mounted? (debug/enabled?))
        (throw (ex-info "Component disposed while still mounted"
                        {:type :dock/disposed-while-mounted
                         :component (type component)}))))))

(defn close!
  "Leave the dock and dispose COMPONENT — the pair dispose!'s invariant asks
   for (release!, then dispose!), for a caller holding the component itself.
   No-op for nil. Returns nil."
  [cs component]
  (when (some? component)
    (release! cs component)
    (dispose! cs component))
  nil)

(defn close-atom!
  "close! whatever COMP-ATOM holds — the recorded-panel shape, where the
   never-mounted / already-closed case is a no-op. Returns nil."
  [cs comp-atom]
  (when-let [component @comp-atom]
    (close! cs component)))

(defn- enter!
  "The shared mount!/cover! path: take focus before publishing the stack
   (only the caller knows the new panel's target; no watch could guess a
   selector's inner list), publish it, and when REPLACE? dispose the
   displaced top unless it was borrowed."
  [cs component focus-target borrowed? replace?]
  (let [e (entry component focus-target borrowed?)]
    (tui/tui-set-focus (:tui cs) (:focus-target e))
    (let [{:keys [old]} (update-stack! cs (if replace?
                                            #(conj (vec (butlast %)) e)
                                            #(conj % e)))
          displaced (peek old)]
      (when (and replace?
                 displaced
                 (not (identical? (:component displaced) component))
                 (not (:borrowed? displaced)))
        (dispose! cs (:component displaced)))
      (tui/tui-request-render (:tui cs)))))

(defn mount!
  "Swap COMPONENT in as the dock's top: replace and dispose the displaced
   top unless it was borrowed, keep the entries below (pi: showSelector +
   disposeActiveSelector). OPTS: :focus-target (pi: showSelector's
   `focus`) is the component that receives keys — the interactive child
   when COMPONENT itself is inert chrome; defaults to COMPONENT — and
   :borrowed? true for a panel whose owner keeps custody and re-mounts or
   disposes it (the auth dialog, an extension dialog); the dock then never
   disposes it.

   Focus is taken before the stack is published because only the caller
   knows the target; the ::focus-guard watch handles every close path."
  ([cs component]
   (mount! cs component {}))
  ([cs component {:keys [focus-target borrowed?]}]
   (enter! cs component focus-target borrowed? true)))

(defn cover!
  "Push COMPONENT on top of the dock without touching what is below (pi:
   showAuthSelect swaps the login dialog out and back; a temporary surface
   covers its owner). OPTS as in mount!. Covering a component that is
   already in the stack is a no-op — a second entry would make one
   release! take out both — and does not move a buried component to the
   top; release! + cover! does that."
  ([cs component]
   (cover! cs component {}))
  ([cs component {:keys [focus-target borrowed?]}]
   (if (when-let [dock (:dock-stack cs)]
         (some #(identical? component (:component %)) @dock))
     nil
     (enter! cs component focus-target borrowed? false))))

(defn clear!
  "Take the editor dock back wholesale (pi: disposeActiveSelector +
   editorContainer.clear()): empty the stack, disposing every non-borrowed
   entry and leaving borrowed ones to their owner. Use when something that
   is not itself a dock mount takes the editor slot — a custom-editor
   swap, a session reset, an extension teardown.

   Focus needs no explicit restore: the ::focus-guard watch sees the
   focused occupant leave and hands input to the resolver's fallback —
   the guard is installed by the stack write itself, so even a caller that
   never mounted anything is covered."
  [cs]
  (let [{:keys [old]} (update-stack! cs (constantly []))]
    (doseq [e old :when (not (:borrowed? e))]
      (dispose! cs (:component e)))))

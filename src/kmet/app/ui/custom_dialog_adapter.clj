(ns kmet.app.ui.custom-dialog-adapter
  "Adapter mounting foreign dialog implementations (pi: ctx.ui.custom's
   Component / duck-typed object) into kmet's TUI surfaces.

   Extension factories may return a plain {:render :handle-input
   :invalidate :dispose} map or a hiccup element tree; ui-custom wraps
   either in a CustomDialogAdapter so the dock/widget strips hold a
   defcomponent RECORD. Records splice into hiccup trees by record? —
   no protocol-identity checks involved. That matters because bb's
   satisfies? can return false for reified components created in other
   evaluation contexts even though method dispatch works on them (the
   documented SCI gotcha), so a reify reaching reconcile as a bare tree
   node crashes the render loop instead of splicing.

   The adapter is a transparent delegate over closures it does not own:
   nothing to cache (the wrapped component manages its own state), and
   its lifetime stays with ui-custom's dialog bookkeeping — trees never
   dispose it (foreign splice contract).

   dispose-component! delegates to kmet.tui.protocols/dispose-component!,
   THE disposal entry point for values of any shape (records, reifies,
   duck-typed maps, sequences) — shared by the dialog bookkeeping and the
   tool-execution renderer children."
  (:require [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]))

(defn dispose-component!
  "Dispose a component of ANY shape — a defcomponent record, a reify,
   a duck-typed {:render … :dispose …} map, or a sequence of these.
   Delegates to kmet.tui.protocols/dispose-component!, THE disposal entry
   point for foreign values (shared by the dialog bookkeeping and the
   tool-execution renderer children); kept here for the existing callers."
  [component]
  (protocols/dispose-component! component))

(defcomponent CustomDialogAdapter nil [render-fn handle-input-fn
                                       invalidate-fn dispose-fn]
  (render [_this width]
    (when render-fn (render-fn width)))
  (handle-input [_this data]
    (when handle-input-fn (handle-input-fn data)))
  (invalidate [_this]
    (when invalidate-fn (invalidate-fn)))
  (dispose [_this]
    ;; idempotent by convention: ui-custom clears its reference after
    ;; disposing, and double-close guards live in the close closure
    (when dispose-fn (dispose-fn))))

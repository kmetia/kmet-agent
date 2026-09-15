(ns kmet.tui.hiccup
  "Hiccup-style construction + reconciliation layer for the TUI DSL
   (tui.md §2).

   Trees are plain data: [:tag {props} children...] or [my-fn {props}]
   function heads. Compilation walks the tree through a CLOSED TAG TABLE —
   there is no registry; custom composition uses fn components (wrapped in
   ComponentFn, their bodies running inside reactions with auto-discovered
   deps) or raw components spliced into the tree (identity preserved,
   never disposed — they are owned elsewhere): defcomponent/defrecord
   instances AND reified or deftype'd IComponent objects (hand-rolled
   components from widgets or extension code) splice identically.

   Reconciliation (tui.md §2.3): ONE keyed diff drives everything — the
   ComponentFn wrapper's child list AND the fill of every host container
   (through per-tag children lenses). Matching is explicit :key first
   (from the props map or, reagent-style, from the element vector's own
   metadata — ^{:key k} [:text …]; props win when both are given),
   fallback match-kind (tag / fn value / record payload / string);
   unmatched previous children are disposed (children-first contract),
   matched ones are reused — identity survives reorders by key, so stateful
   subtrees (editors, fn components with with-let state) live across
   passes. Ownership rides the :dsl/meta stamp: everything the DSL
   constructs carries it; foreign records never do and are never disposed.

   Props: display leaves (text/markdown/spacer/string) are rebuilt when
   their props change — identity-free, their caches absorb rendering;
   equal props short-circuit to the same instance. Stateful tags declare
   an :apply path (see the tag table): a changed prop patches the live
   instance — state and focus survive — and only a prop the tag cannot
   express rebuilds it. Containers keep their instance across passes and
   reconcile their children in place; their structural props
   (padding/gap) are live through their own :apply (which must be TOTAL —
   a container never rebuilds, since a fresh construct starts with an
   empty child pool and would take the whole subtree's state with it).

   Validation is loud per the v1 error contract: unknown tags throw with a
   did-you-mean suggestion, children on a leaf tag throw, duplicate :keys
   throw, stack-entry maps outside a stack tag throw.

   Mounting goes through hiccup/root — the one public constructor from a
   tree to a mounted, disposable IComponent (a ComponentFn). render-lines
   gives the headless surface: pure data in, lines out, no terminal."
  (:refer-clojure :exclude [ref])
  (:require
   [kmet.tui.components.alt-screen-flash :as alt-screen-flash]
   [kmet.tui.components.box :as box]
   [kmet.tui.components.cancellable-loader :as cancellable-loader]
   [kmet.tui.components.container :as container]
   [kmet.tui.components.dynamic-border :as dynamic-border]
   [kmet.tui.components.editor :as editor]
   [kmet.tui.components.expandable-text :as expandable-text]
   [kmet.tui.components.h-stack :as h-stack]
   [kmet.tui.components.image :as image]
   [kmet.tui.components.input :as input]
   [kmet.tui.components.markdown :as markdown]
   [kmet.tui.components.scroll-view :as scroll-view]
   [kmet.tui.components.select-list :as select-list]
   [kmet.tui.components.settings-list :as settings-list]
   [kmet.tui.components.spacer :as spacer]
   [kmet.tui.components.spinner :as spinner]
   [kmet.tui.components.stack :as stack]
   [kmet.tui.components.text :as text]
   [kmet.tui.components.truncated-text :as truncated-text]
   [kmet.tui.components.v-stack :as v-stack]
   [kmet.tui.fuzzy :as fuzzy]
   [kmet.tui.macros :as macros :refer [defcomponent]]
   [kmet.libs.reakt :as r]
   [kmet.tui.protocols :as protocols]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Refs — the imperative escape hatch (tui.md §2.4)
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol DslRef
  "Internal surface of hiccup refs. Not for external use — reconcile
   fills and clears refs; consumers only deref."
  (-fill-ref! [this c] "Point the ref at C (nil clears it)."))

(defn ref
  "Create a ref handle: deref it (outside render bodies — handlers,
   effects) to reach the mounted record of the tree element that declares
   it as a :ref prop. Nil until reconcile first constructs that element,
   cleared when the element is disposed. Filled by reconciliation only —
   treat as read-only (a core reset! on this reify would silently no-op
   under babashka, so there is deliberately nothing to intercept: the
   type simply isn't an IAtom). One ref per element instance."
  []
  (let [cell (atom nil)]
    (reify
      DslRef
      (-fill-ref! [_ c] (reset! cell c) nil)
      clojure.lang.IDeref
      (deref [_] @cell))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dynamics + observability (tui.md §2.5)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *width*
  "The current render-pass width, bound around ComponentFn bodies — for
  status bars/dialog headers that must truncate at the real width. Read
  only; nil outside a component render.")

(def ^:dynamic *comp*
  "The ComponentFn whose body is running. nil outside one.")

;; Behind --debug these surface the inline-callback trap (§2.5): a body
;; invoked on frames where nothing it derefs changed shows up as
;; bodies-run climbing while bodies-skipped stays flat.
(def ^:private zero-counters
  {:bodies-run 0 :bodies-skipped 0 :constructs 0 :reuses 0 :applies 0
   :disposals 0 :computes 0})

(defonce ^:private counters-atom (atom zero-counters))

(defn counters
  "Per-frame fn-invocation/reconcile counters ({:bodies-run :bodies-skipped
   :constructs :reuses :applies :disposals :computes}). Process-wide;
   reset-counters! in tests."
  [] @counters-atom)

(defn reset-counters!
  "Back to all zeros (the immutable zero map is shared with the initial
   value — reset! swaps whole values, so no aliasing concerns)."
  []
  (reset! counters-atom zero-counters)
  nil)

(defn- bump! [k] (swap! counters-atom update k (fnil inc 0)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tag table — the closed set of host elements (tui.md §2.2)
;; ═══════════════════════════════════════════════════════════════════════════

;; Spec fields:
;;   :ctor       — adapter fn from normalized PROPS to a fresh component
;;                 (keeps unmigrated host constructors usable; collapses
;;                 when components adopt the uniform props/state shape)
;;   :primary    — positional-shorthand target: [:text "hi"] compiles to
;;                 props {:text "hi"}
;;   :lens       — present iff the tag takes children; get/put fns over the
;;                 component's child STORAGE. get returns the raw stored
;;                 children (components; entry maps for stacks) —
;;                 itemization happens once, in reconcile-into. put installs
;;                 the reconciled items back (:item wins — stacks store
;;                 entry maps, other containers components).
;;   :entries?   — children of this tag may be stack entry maps
;;   :apply      — optional prop→state patch path for stateful tags:
;;                 (fn [comp prev-props props]) called on a matched leaf
;;                 whose props CHANGED; truthy = patch applied, the
;;                 instance is kept (state and focus survive the change),
;;                 falsy = the tag cannot express these props on the live
;;                 instance and the leaf is retired and rebuilt as usual.
;;                 Check structural props FIRST and return falsy before
;;                 mutating; compare them in their CONSTRUCTED form (a nil
;;                 prop and its default are the same component) so a
;;                 spelling change does not churn a rebuild. Tags without
;;                 :apply always rebuild on change (display leaves are
;;                 identity-free by design). CONTAINER tags (:box,
;;                 :v-stack, :h-stack, :scroll-view) are the exception:
;;                 a container NEVER rebuilds (its children would lose
;;                 their state), so a container :apply must be TOTAL —
;;                 express every prop, or (with no :apply on the tag)
;;                 leave the structural props as constructed.
;;
;;                 Inside an :apply, a STATE-CARRYING prop is written
;;                 through only when it differs from PREV-PROPS, and then
;;                 coerced the way construction coerces it (nil ⇒ the
;;                 default). An unchanged prop never overwrites live state
;;                 — that is what lets typing, selection and expansion
;;                 survive an unrelated prop change — while a changed prop
;;                 is an instruction and wins.
;;
;; Adapter ctors destructure known props and ignore extras, EXCEPT the
;; pseudo-props :key/:ref which parse strips before ctors ever see them.

(defn- scroll-view-props
  "Resolve :scroll-view props exactly as construction does — an ABSENT key
   takes the default, so :follow-end can be declared false (the old
   (or follow-end true) coerced an explicit false back to true). The ctor
   and the tag's :apply path share this, so a patch compares and coerces
   identically."
  [{:keys [follow-end primary overscroll scrollbar scrollbar-style
           scrollbar-hide-delay-ms] :as props}]
  {:follow-end (if (contains? props :follow-end) (boolean follow-end) true)
   :primary (boolean primary)
   :overscroll (or overscroll :chain)
   :scrollbar (or scrollbar :hidden)
   :scrollbar-style (or scrollbar-style scroll-view/default-scrollbar-style)
   :scrollbar-hide-delay-ms (or scrollbar-hide-delay-ms 1000)})

(def ^:private tags
  {:text      {:ctor (fn [{:keys [text padding-x padding-y bg-fn]}]
                       (text/make-text text
                                       (or padding-x 1)
                                       (or padding-y 1)
                                       bg-fn))
               :primary :text}
   :markdown  {:ctor (fn [{:keys [text theme padding-x default-style transform border]}]
                       (markdown/make-markdown text
                                               :theme theme
                                               :padding-x (or padding-x 1)
                                               :default-style default-style
                                               :transform transform
                                               :border border))
               :primary :text}
   :spacer    {:ctor (fn [{:keys [lines]}] (spacer/make-spacer (or lines 1)))}
   :dynamic-border {:ctor (fn [{:keys [color-fn border]}]
                            (dynamic-border/make-dynamic-border color-fn border))
                    :primary :color-fn}
   :truncated-text {:ctor (fn [{:keys [text padding-x padding-y]}]
                            (truncated-text/make-truncated-text text
                                                                :padding-x (or padding-x 0)
                                                                :padding-y (or padding-y 0)))
                    :primary :text}
   :spinner      {:ctor (fn [{:keys [text active prefix frames interval-ms
                                     spinner-color-fn message-color-fn]}]
                          (spinner/make-spinner :text (or text "")
                                                :active (boolean active)
                                                :prefix (or prefix "  ")
                                                :frames (or frames spinner/default-frames)
                                                :interval-ms (or interval-ms 100)
                                                :spinner-color-fn spinner-color-fn
                                                :message-color-fn message-color-fn))
                  :primary :text
                  :apply (fn [s prev props]
                           ;; frames/interval have no faithful setter (the only
                           ;; one, set-indicator!, switches the spinner to
                           ;; verbatim rendering and drops the color fn) — a
                           ;; change there rebuilds. Everything else patches,
                           ;; and the animation timer is left alone unless
                           ;; :active actually flips (a rebuild would restart
                           ;; the animation on every text tick).
                           (if (or (not= (or (:frames prev) spinner/default-frames)
                                         (or (:frames props) spinner/default-frames))
                                   (not= (or (:interval-ms prev) 100)
                                         (or (:interval-ms props) 100)))
                             false
                             (do
                               (when (not= (boolean (:active props))
                                           (boolean (:active prev)))
                                 (if (:active props)
                                   (spinner/spinner-start! s)
                                   (spinner/spinner-stop! s)))
                               (when (not= (or (:text props) "")
                                           (or (:text prev) ""))
                                 (spinner/spinner-set-text! s (or (:text props) "")))
                               (when (not= (or (:prefix props) "  ")
                                           (or (:prefix prev) "  "))
                                 (spinner/spinner-set-prefix! s (or (:prefix props) "  ")))
                               (when (not= (:spinner-color-fn props)
                                           (:spinner-color-fn prev))
                                 (spinner/spinner-set-spinner-color-fn!
                                  s (:spinner-color-fn props)))
                               (when (not= (:message-color-fn props)
                                           (:message-color-fn prev))
                                 (spinner/spinner-set-message-color-fn!
                                  s (:message-color-fn props)))
                               true)))}
   :input        {:ctor (fn [{:keys [value cursor on-submit on-escape on-change]}]
                          (let [i (input/make-input)]
                            (when value (input/input-set-value! i value))
                            (when (some? cursor) (input/input-set-cursor! i cursor))
                            (when on-submit (input/input-set-on-submit! i on-submit))
                            (when on-escape (input/input-set-on-escape! i on-escape))
                            (when on-change (input/input-set-on-change! i on-change))
                            i))
                  :primary :value
                  :apply (fn [i prev props]
                           ;; a state-carrying prop is written through only
                           ;; when IT changed, and then coerced like
                           ;; construction (nil ⇒ empty for :value; nil
                           ;; :cursor is unmanaged): an unchanged :value or
                           ;; :cursor never overwrites live state, so typing
                           ;; survives an unrelated prop change. Callbacks
                           ;; always apply — configuration, not state.
                           (when (not= (:value props) (:value prev))
                             (let [v (or (:value props) "")]
                               (when (not= v (input/input-get-value i))
                                 (input/input-set-value! i v))))
                           (when (and (not= (:cursor props) (:cursor prev))
                                      (some? (:cursor props)))
                             (input/input-set-cursor! i (:cursor props)))
                           (input/input-set-on-submit! i (:on-submit props))
                           (input/input-set-on-escape! i (:on-escape props))
                           (input/input-set-on-change! i (:on-change props))
                           true)}
   :expandable-text {:ctor (fn [{:keys [collapsed-fn expanded-fn
                                        expanded? padding-x padding-y]}]
                             (expandable-text/make-expandable-text
                              collapsed-fn expanded-fn
                              :expanded? (boolean expanded?)
                              :padding-x (or padding-x 0)
                              :padding-y (or padding-y 0)))
                     :apply (fn [et prev props]
                              ;; content fns are baked into the inner Text at
                              ;; construction (a padding/fn change rebuilds;
                              ;; compared in constructed form, so a missing
                              ;; padding key equals its 0 default)
                              (if (or (not= (:collapsed-fn prev) (:collapsed-fn props))
                                      (not= (:expanded-fn prev) (:expanded-fn props))
                                      (not= (or (:padding-x prev) 0)
                                            (or (:padding-x props) 0))
                                      (not= (or (:padding-y prev) 0)
                                            (or (:padding-y props) 0)))
                                false
                                (do
                                  ;; :expanded? is the live half, written only
                                  ;; when IT changed (nil ⇒ collapsed, the
                                  ;; construct default)
                                  (when (not= (:expanded? props) (:expanded? prev))
                                    (expandable-text/expandable-text-set-expanded!
                                     et (boolean (:expanded? props))))
                                  true)))}
   :image        {:ctor (fn [{:keys [base64-data mime-type theme
                                     max-width-cells max-height-cells
                                     filename image-id]}]
                          (image/make-image base64-data mime-type theme
                                            :max-width-cells max-width-cells
                                            :max-height-cells max-height-cells
                                            :filename filename
                                            :image-id image-id))}
   :select-list  {:ctor (fn [{:keys [items height theme header no-match-text
                                     min-primary-column-width
                                     max-primary-column-width truncate-primary
                                     on-select on-escape on-selection-change
                                     on-key]}]
                          (select-list/make-select-list
                           (or items [])
                           :height (or height 10)
                           :theme (or theme select-list/default-theme)
                           :header header
                           :no-match-text (or no-match-text "  No matching commands")
                           :min-primary-column-width min-primary-column-width
                           :max-primary-column-width max-primary-column-width
                           :truncate-primary truncate-primary
                           :on-select on-select
                           :on-escape on-escape
                           :on-selection-change on-selection-change
                           :on-key on-key))
                  :primary :items
                  :apply (fn [sl prev props]
                           ;; items patch only on a real change, and they
                           ;; patch PRESERVING the filter + selection: an
                           ;; items refresh is the same question re-asked,
                           ;; so it must not eat the user's typed filter
                           (when (not= (:items prev) (:items props))
                             (select-list/select-list-set-items!
                              sl (or (:items props) [])
                              {:preserve-state? true}))
                           (select-list/select-list-set-height!
                            sl (or (:height props) 10))
                           (select-list/select-list-set-theme!
                            sl (or (:theme props) select-list/default-theme))
                           (select-list/select-list-set-header! sl (:header props))
                           (select-list/select-list-set-no-match-text!
                            sl (or (:no-match-text props) "  No matching commands"))
                           (select-list/select-list-set-column-bounds!
                            sl (:min-primary-column-width props)
                            (:max-primary-column-width props))
                           (select-list/select-list-set-truncate-primary!
                            sl (:truncate-primary props))
                           (select-list/select-list-set-on-select! sl (:on-select props))
                           (select-list/select-list-set-on-escape! sl (:on-escape props))
                           (select-list/select-list-set-on-selection-change!
                            sl (:on-selection-change props))
                           (select-list/select-list-set-on-key! sl (:on-key props))
                           true)}
   :settings-list {:ctor (fn [{:keys [items theme on-change on-escape
                                      enable-search max-visible]}]
                           (let [sl (settings-list/make-settings-list
                                     (or items [])
                                     :theme (or theme settings-list/default-theme)
                                     :on-change on-change
                                     :enable-search (boolean enable-search)
                                     :max-visible (or max-visible 10))]
                             (when on-escape
                               (settings-list/settings-list-set-on-escape! sl
                                                                           on-escape))
                             sl))
                   :primary :items
                   :apply (fn [sl prev props]
                            ;; the search input is built at construction
                            ;; (enable-search) — toggling it rebuilds
                            (if (not= (boolean (:enable-search prev))
                                      (boolean (:enable-search props)))
                              false
                              (do
                                ;; items patch only on a real change (nil ⇒ []),
                                ;; preserving the query + selection — an items
                                ;; refresh must not eat the typed search
                                (when (not= (:items prev) (:items props))
                                  (settings-list/settings-list-set-items!
                                   sl (or (:items props) [])
                                   {:preserve-state? true}))
                                (settings-list/settings-list-set-theme!
                                 sl (or (:theme props) settings-list/default-theme))
                                (settings-list/settings-list-set-on-change!
                                 sl (:on-change props))
                                (settings-list/settings-list-set-on-escape!
                                 sl (:on-escape props))
                                (settings-list/settings-list-set-max-visible!
                                 sl (or (:max-visible props) 10))
                                true)))}
   :editor       {:ctor (fn [{:keys [text height padding-x border-fn border
                                     keybindings terminal-rows
                                     on-submit on-change]}]
                          (let [ed (editor/make-editor :height (or height 12)
                                                       :padding-x (or padding-x 0)
                                                       :border-fn border-fn
                                                       :border border
                                                       :keybindings keybindings
                                                       :terminal-rows terminal-rows)]
                            (when text (editor/editor-set-text! ed text))
                            (when on-submit (editor/editor-set-on-submit! ed on-submit))
                            (when on-change (editor/editor-set-on-change! ed on-change))
                            ed))
                  :primary :text
                  :apply (fn [ed prev props]
                           ;; structural props have no setters — a change here
                           ;; rebuilds (checked before anything is mutated;
                           ;; a nil border and its :normal default are the
                           ;; same component, so they compare equal)
                           (if (or (not= (or (:border prev) :normal)
                                         (or (:border props) :normal))
                                   (not= (:border-fn prev) (:border-fn props))
                                   (not= (:keybindings prev) (:keybindings props)))
                             false
                             (do
                               ;; :text is written only when IT changed (nil ⇒
                               ;; empty, the construct default) and differs from
                               ;; live text: editor-set-text! at an equal text
                               ;; would still reset scroll/undo bookkeeping, and
                               ;; an unchanged prop must not clobber live typing
                               (when (not= (:text props) (:text prev))
                                 (let [t (or (:text props) "")]
                                   (when (not= t (editor/editor-get-text ed))
                                     (editor/editor-set-text! ed t))))
                               (editor/editor-set-height! ed (or (:height props) 12))
                               (editor/editor-set-padding-x!
                                ed (or (:padding-x props) 0))
                               (editor/editor-set-terminal-rows!
                                ed (:terminal-rows props))
                               (editor/editor-set-on-submit! ed (:on-submit props))
                               (editor/editor-set-on-change! ed (:on-change props))
                               true)))}
   :cancellable-loader {:ctor (fn [{:keys [spinner on-abort text]}]
                                (cancellable-loader/make-cancellable-loader
                                 :spinner (or spinner
                                              (spinner/make-spinner :text text
                                                                    :active true))
                                 :on-abort on-abort))
                        :apply (fn [cl prev props]
                                 ;; a changed :spinner prop is a child swap —
                                 ;; the field has no setter, and assoc-ing one
                                 ;; would bypass construction (nothing would
                                 ;; own the replacement), so it rebuilds
                                 (if (not= (:spinner prev) (:spinner props))
                                   false
                                   (do
                                     ;; :text feeds the DEFAULT spinner at
                                     ;; construction — with a :spinner prop it
                                     ;; is ignored, exactly as in the ctor
                                     (when (and (nil? (:spinner props))
                                                (not= (or (:text props) "")
                                                      (or (:text prev) "")))
                                       (spinner/spinner-set-text!
                                        (:spinner cl) (or (:text props) "")))
                                     (when (not= (:on-abort props) (:on-abort prev))
                                       (cancellable-loader/cancellable-loader-set-on-abort!
                                        cl (:on-abort props)))
                                     true)))}
   :alt-screen-flash
   {:ctor (fn [{:keys [request-render]}]
            (alt-screen-flash/make-alt-screen-flash request-render))
    :primary :request-render
    :apply (fn [fl prev props]
             ;; the callback is configuration, not state: patch it through
             ;; the setter so the pending entries (and their expiry timers)
             ;; survive — a fresh closure per pass is the norm, and a
             ;; rebuild would dispose the container and clear the entries
             (when (not= (:request-render props) (:request-render prev))
               (alt-screen-flash/alt-screen-flash-set-request-render!
                fl (:request-render props)))
             true)}
   :box       {:ctor (fn [{:keys [padding-x padding-y bg-fn]}]
                       (box/make-box
                        (or padding-x 1) (or padding-y 1) bg-fn))
               :apply (fn [b prev props]
                        ;; container :apply is TOTAL — every prop is
                        ;; expressible, so the subtree is never rebuilt over
                        ;; a prop change (children keep their state). Props
                        ;; coerce like the ctor (nil ⇒ default).
                        (let [px (or (:padding-x props) 1)
                              py (or (:padding-y props) 1)]
                          (when (not= px (or (:padding-x prev) 1))
                            (box/box-set-padding-x! b px))
                          (when (not= py (or (:padding-y prev) 1))
                            (box/box-set-padding-y! b py))
                          (when (not= (:bg-fn props) (:bg-fn prev))
                            (box/box-set-bg-fn b (:bg-fn props)))
                          true))
               :lens {:get (fn [c] (vec @(:children c)))
                      :put (fn [c items] (reset! (:children c) (mapv :c items)))}}
   :container {:ctor (fn [_props] (container/make-container []))
               :lens {:get (fn [c] (vec @(:children c)))
                      :put (fn [c items] (reset! (:children c) (mapv :c items)))}}
   :v-stack   {:ctor (fn [{:keys [gap]}]
                       (v-stack/make-v-stack [] :gap (or gap 0)))
               ;; :entries? — children of this tag may be stack entry maps
               :entries? true
               :apply (fn [vs prev props]
                        ;; total, like :box — v-stack-set-gap! normalizes
                        ;; exactly as the ctor does
                        (when (not= (or (:gap props) 0) (or (:gap prev) 0))
                          (v-stack/v-stack-set-gap! vs (or (:gap props) 0)))
                        true)
               :lens {:get (fn [c] (vec @(:entries-atom c)))
                      :put (fn [c items]
                             (reset! (:entries-atom c)
                                     (mapv #(or (:item %) (:c %)) items)))}}
   :h-stack   {:ctor (fn [{:keys [gap align]}]
                       (h-stack/make-h-stack []
                                             :gap (or gap 0)
                                             :align (or align :stretch)))
               :entries? true
               :apply (fn [hs prev props]
                        ;; total — the setters normalize as the ctor does
                        (when (not= (or (:gap props) 0) (or (:gap prev) 0))
                          (h-stack/h-stack-set-gap! hs (or (:gap props) 0)))
                        (when (not= (or (:align props) :stretch)
                                    (or (:align prev) :stretch))
                          (h-stack/h-stack-set-align! hs (or (:align props) :stretch)))
                        true)
               :lens {:get (fn [c] (vec @(:entries-atom c)))
                      :put (fn [c items]
                             (reset! (:entries-atom c)
                                     (mapv #(or (:item %) (:c %)) items)))}}
   ;; Single-child container — the lens keeps exactly one child; more than
   ;; one throws loudly (a scroll view over several roots is a bug, not a
   ;; layout).
   :scroll-view {:ctor (fn [props]
                         (let [{:keys [follow-end primary overscroll scrollbar
                                       scrollbar-style scrollbar-hide-delay-ms]}
                               (scroll-view-props props)]
                           (scroll-view/make-scroll-view
                            nil
                            :follow-end follow-end
                            :primary primary
                            :overscroll overscroll
                            :scrollbar scrollbar
                            :scrollbar-style scrollbar-style
                            :scrollbar-hide-delay-ms scrollbar-hide-delay-ms)))
                 :apply (fn [sv prev props]
                          ;; total — every prop has a setter that coerces like
                          ;; the ctor (both sides through scroll-view-props)
                          (let [np (scroll-view-props props)
                                pp (scroll-view-props prev)]
                            (when (not= (:follow-end np) (:follow-end pp))
                              (scroll-view/scroll-view-set-follow-end!
                               sv (:follow-end np)))
                            (when (not= (:primary np) (:primary pp))
                              (scroll-view/scroll-view-set-primary!
                               sv (:primary np)))
                            (when (not= (:overscroll np) (:overscroll pp))
                              (scroll-view/scroll-view-set-overscroll!
                               sv (:overscroll np)))
                            (when (not= (:scrollbar np) (:scrollbar pp))
                              (scroll-view/set-scrollbar! sv (:scrollbar np)))
                            (when (not= (:scrollbar-style np) (:scrollbar-style pp))
                              (scroll-view/scroll-view-set-scrollbar-style!
                               sv (:scrollbar-style np)))
                            (when (not= (:scrollbar-hide-delay-ms np)
                                        (:scrollbar-hide-delay-ms pp))
                              (scroll-view/scroll-view-set-scrollbar-hide-delay-ms!
                               sv (:scrollbar-hide-delay-ms np)))
                            true))
                 :lens {:get (fn [c] (if-let [ch @(:child-atom c)] [ch] []))
                        :put (fn [c items]
                               (when (> (count items) 1)
                                 (throw (ex-info
                                         ":scroll-view takes exactly one child"
                                         {:count (count items)})))
                               (reset! (:child-atom c)
                                       (when (seq items) (:c (first items)))))}}})

(defn- known-tags [] (vec (sort (keys tags))))

(defn- nearest-tag
  "Best fuzzy match for TAG among the known tags, for did-you-mean
   (lower score is a better match)."
  [tag]
  (let [name (subs (str tag) 1)]
    (->> (keys tags)
         (keep (fn [t]
                 (let [{:keys [matches score]}
                       (fuzzy/fuzzy-match name (subs (str t) 1))]
                   (when matches [score t]))))
         (sort-by first)
         first
         second)))

(defn- unknown-tag!
  "Throw the unknown-tag error: names the tag, lists the closed set, and
   suggests the nearest known tag (did-you-mean via the fuzzy matcher)."
  [tag]
  (let [hint (when-some [near (nearest-tag tag)]
               (str " Did you mean :" (name near) "?"))]
    (throw (ex-info (str "kmet.tui.hiccup: unknown tag " tag
                         ". Known tags: " (pr-str (known-tags)) "."
                         hint)
                    {:tag tag :known-tags (known-tags)}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Parsing — tree nodes → desired items (validation happens HERE, loudly)
;; ═══════════════════════════════════════════════════════════════════════════

;; Desired/previous items share one shape:
;;   :kind   ::host | ::fncomp | ::record | ::entry | ::string
;;   :mkey   match-kind — what groups this item with its previous self
;;           (explicit key wins; else tag / fn value / payload identity /
;;           ::string constant)
;;   :key    explicit :key pseudo-prop (nil mostly)
;;   :ref    explicit :ref pseudo-prop (nil mostly)
;;   :c      the component (records: the payload; entries: the inner)
;;   :item   the raw object placed back into a container (:item for stack
;;           entry maps, :c for everything else)
;;   :owned  true iff the DSL constructed it — only owned items are disposed
;; plus construction inputs on desired items:
;;   ::host    :tag :spec :props :nodes
;;   ::fncomp  :f :props :ctree

(declare check-ref!)

(defn- mounted-component?
  "A non-record object that plausibly dispatches as an IComponent — a
  reified or deftype'd component spliced into a tree by host code (the
  ui-custom dialog wrapper, widget factories). Best-effort by necessity:
  bb's satisfies? can return false for reifies created in other
  evaluation contexts even though method dispatch works on them (the
  documented SCI gotcha), and the protocol method table misses those
  too — so both signals are OR-ed and anything slipping past still
  fails LOUDLY here instead of corrupting a render pass. Records never
  reach this predicate (spliced earlier); ui-custom hands the dock
  records in the first place (CustomDialogAdapter)."
  [node]
  (try
    (or (satisfies? protocols/IComponent node)
        (contains? (methods protocols/render) (class node)))
    (catch Exception _ false)))

(defn- normalize-element
  "[TAG ...] → [spec props children]: an optional MAP right after the tag
   is the props; everything else is children. When the tag declares
   :primary, the FIRST child becomes that prop — with or without a props
   map ([:text \"hi\"] and [:text {:padding-x 0} \"hi\"] both fill :text);
   remaining children stay children. Pseudo-props :key/:ref are extracted
   separately — they belong to reconciliation, never to constructors.
   NB: defcomponent records ARE maps — a spliced component must never be
   mistaken for the props map; neither may a stack-entry map ({:component
   c} is never a legal prop shape)."
  [tag content]
  (let [spec (or (get tags tag) (unknown-tag! tag))
        props-map? (let [first-content (first content)]
                     (and (map? first-content)
                          (not (record? first-content))
                          ;; {:component c} is the stack-entry shape —
                          ;; always a CHILD; consuming it as props would
                          ;; silently drop the element it wraps
                          (not (contains? first-content :component))))
        base-props (if props-map? (first content) {})
        children (if props-map? (rest content) content)
        primary? (:primary spec)
        take-primary? (boolean (and primary?
                                    (seq children)
                                    (or (not props-map?)
                                        (nil? (:lens spec)))))
        ;; a ref handle in the PRIMARY slot is almost certainly
        ;; [:widget (h/ref)] missing the props map — every other child
        ;; position rejects refs loudly, so must this one too (checked
        ;; against PRE-split children: take-primary consumes the head)
        _ (when (and take-primary? (satisfies? DslRef (first children)))
            (throw (ex-info
                    (str "kmet.tui.hiccup: bare (hiccup/ref) as the value "
                         "child of " tag " — did you mean [" (name tag)
                         " {:ref ...} ...]?")
                    {:tag tag})))
        [props children] (if take-primary?
                           [(assoc base-props primary? (first children))
                            (rest children)]
                           [base-props children])]
    [spec (dissoc props :key :ref) (vec children)
     (select-keys base-props [:key :ref])]))

(declare make-component-fn)

(defn- parse-host
  "[:tag ...] → desired item. Validates before any construction: unknown
   tags and children-on-leaf throw here. META-KEY is the element vector's
   ^{:key k} metadata (nil when absent); an explicit :key prop wins over
   it, since props are the more specific form."
  [tag content meta-key]
  (let [[spec props children meta] (normalize-element tag content)
        key (or (:key meta) meta-key)]
    (when (and (seq children) (nil? (:lens spec)))
      (throw (ex-info
              (str "kmet.tui.hiccup: children given to leaf tag " tag
                   " — leaves take only props")
              {:tag tag :children children})))
    (check-ref! tag (:ref meta))
    {:kind ::host :mkey (if-some [k key] {::user-key k ::kind ::host} tag)
     :key key :ref (:ref meta)
     :tag tag :spec spec :props props :nodes children}))

(defn- check-ref!
  ":ref must be a handle from hiccup/ref — anything else (a keyword, a
   string) is a typo that would otherwise detonate later at fill time."
  [where r]
  (when (and (some? r) (not (satisfies? DslRef r)))
    (throw (ex-info
            (str "kmet.tui.hiccup: :ref must be a (hiccup/ref) handle, got "
                 (pr-str r) (when (keyword? where) (str " on tag " where)))
            {:ref r}))))

(defn- parse-node
  "One tree node → desired item, or nil to skip (nil nodes — the
   when/when-let support). Seqs never reach here (flattened earlier).
   An element's key may come from its props (:key) or, reagent-style, from
   the vector's own metadata (^{:key k} [:text …]) — props win; metadata on
   a non-vector child (record, string, entry map) is ignored, those match
   by identity or kind."
  [node stack?]
  (cond
    (nil? node) nil
    (string? node)
    {:kind ::string :mkey ::string :props {:text node}}
    (vector? node)
    (let [tag (first node)
          meta-key (:key (meta node))]
      (cond
        (keyword? tag) (parse-host tag (vec (rest node)) meta-key)
        (fn? tag)
        (let [content (rest node)
              props-map? (and (map? (first content))
                              (not (record? (first content))))
              base-props (if props-map? (first content) {})
              children (vec (if props-map? (rest content) content))
              key (or (:key base-props) meta-key)
              ref (:ref base-props)]
          (check-ref! tag ref)
          {:kind ::fncomp :mkey (if-some [k key] {::user-key k ::kind ::fncomp} tag) :key key :ref ref
           :f tag :props (dissoc base-props :key :ref)
           :ctree (when (seq children) children)})
        :else (throw
               (ex-info
                (str "kmet.tui.hiccup: invalid element head " (pr-str tag)
                     " — expected a keyword tag or a function component")
                {:head tag}))))
    (record? node)
    {:kind ::record :mkey node :c node :item node :owned false}
    (mounted-component? node)
    ;; reified/deftype'd IComponent — same contract as the record splice:
    ;; identity preserved, owned elsewhere, never disposed by the tree
    {:kind ::record :mkey node :c node :item node :owned false}
    (map? node)
    (if (contains? node :component)
      (do
        (when-not stack?
          (throw
           (ex-info
            (str "kmet.tui.hiccup: stack entry map {:component c} outside "
                 "a stack tag (:v-stack/:h-stack) — entries are stack "
                 "children only")
            {:entry node})))
        {:kind ::entry :mkey (stack/entry-component node)
         :c (stack/entry-component node) :item node :owned false})
      (throw
       (ex-info
        (str "kmet.tui.hiccup: bare map child — maps pass through "
             "only as stack entries {:component c}; got "
             (pr-str (keys node)))
        {:map node})))
    :else (throw
           (ex-info (str "kmet.tui.hiccup: cannot compile tree node "
                         (pr-str node)
                         " — for function roots use hiccup/root")
                    {:node node}))))

(defn- flatten-nodes
  "Splice seqs into a flat node list — VECTORS are single elements and
   must be checked FIRST (they are sequential too; checking sequential?
   alone would shred every element into its own head/props/content).
   Nils survive flattening; parse drops them."
  [nodes acc]
  (reduce (fn [acc n]
            (cond
              (vector? n) (conj acc n)
              (sequential? n) (flatten-nodes n acc)
              :else (conj acc n)))
          acc nodes))

(defn- parse-nodes
  "Tree nodes → desired items, seqs spliced, nils dropped, duplicate
   explicit keys throwing (two spliced siblings sharing a key makes reuse
   undefined — throwing beats silent subtree loss, tui.md §2.3). STACK?
   licenses entry maps (true only for stack-tag children)."
  [nodes stack?]
  (loop [flat (flatten-nodes nodes [])
         parsed []
         seen #{}]
    ;; NB: seq-destructure, NOT if-some on the head — a nil CHILD must be
    ;; skipped, not end the parse
    (if-let [[n & more] (seq flat)]
      (if-some [d (parse-node n stack?)]
        (do
          (when (and (:key d) (contains? seen (:key d)))
            (throw (ex-info
                    (str "kmet.tui.hiccup: duplicate :key " (pr-str (:key d))
                         " among siblings — keys must uniquely identify a "
                         "child for reuse")
                    {:key (:key d)})))
          (recur more (conj parsed d) (cond-> seen (:key d) (conj (:key d)))))
        (recur more parsed seen))
      parsed)))

(declare reconcile!)

;; ═══════════════════════════════════════════════════════════════════════════
;; ComponentFn — the fn-component wrapper (tui.md §2.5)
;; ═══════════════════════════════════════════════════════════════════════════

(defcomponent ComponentFn nil [f props kids store rx ctree last-width]
  (render [_this width]
    (binding [*width* width *comp* _this]
      ;; Uncached by design (transparent-parent allowlist): the memoization
      ;; boundary is the REACTION — deps auto-discovered at deref time, and
      ;; an idle reaction hands back its cached tree without running the
      ;; body (zero fn invocations on frames where nothing changed).
      (let [rx (or @rx
                   (let [nr (r/make-reaction
                             (fn []
                               (macros/with-store store
                                 (macros/begin-pass! store)
                                 (bump! :bodies-run)
                                 (let [p (r/tracked-deref props)
                                       ;; tracked: a parent adding/removing the
                                       ;; element's children changes CTREE alone,
                                       ;; with equal props — must still re-derive
                                       ct (r/tracked-deref ctree)
                                       tree (if ct (f p ct) (f p))]
                                   (reconcile! kids tree))))
                             {:auto-run? (fn [_] (macros/schedule-frame!))
                              ;; Bodies reading only untracked values (bare
                              ;; @app-atom closures, static trees) must never
                              ;; cache stale output — they re-derive per pass,
                              ;; exactly like the pre-stage-3 batched root.
                              ;; Consequence, pinned by test: bodies whose ONLY
                              ;; inputs are props/ctree also re-derive each pass
                              ;; (correct — the props watch still dirties on real
                              ;; changes; equal-prop frames just cost a body run
                              ;; + diff). Reactive inputs beyond these go through
                              ;; cursors, slices, or tracked-deref and get narrow
                              ;; memoization for free.
                              :rerun-without-deps? true
                              :implicit-deps [props ctree]})]
                     (reset! rx nr)
                     nr))]
        ;; Width participates in memoization: it shapes body output (*width*
        ;; is read inside bodies) but is a dynamic var, not a tracked dep —
        ;; so a width change forces one re-derive of an idle reaction, which
        ;; then re-caches: track!'s per-width cache contract, one level up.
        (when (and (not= width @last-width)
                   (= :idle (:state (r/reaction-state rx))))
          (r/invalidate! rx))
        (reset! last-width width)
        (when (= :idle (:state (r/reaction-state rx)))
          (bump! :bodies-skipped))
        ;; Bring current: unrun/dirty → the body reconciles synchronously;
        ;; idle → the cached tree's items render without re-running it.
        (deref rx)
        (let [items @kids]
          (if (seq items)
            (->> items
                 (mapv #(protocols/render (:c %) width))
                 (apply concat)
                 vec)
            [])))))
  (dispose [_this]
    ;; Contractual order (tui.md §5.1): children first — their cleanups
    ;; still see intact parent state; then the reaction (unwatches deps),
    ;; then the store's cleanups LIFO, leaving nothing registered.
    (doseq [it @kids :when (:owned it)]
      (protocols/dispose (:c it)))
    (when-some [r @rx]
      (r/dispose! r))
    (macros/destroy-store! store)))

(defn make-component-fn
  "Wrap F in a ComponentFn. CTREE is the element's raw child tree — when
   present, F is called as (f props children) instead of (f props)."
  ([f] (make-component-fn f nil))
  ([f ctree]
   (map->ComponentFn {:f f
                      :props (atom {})
                      :kids (atom [])
                      :store (macros/new-store)
                      :rx (atom nil)
                      :ctree (atom ctree)
                      :last-width (atom nil)})))

;; ═══════════════════════════════════════════════════════════════════════════
(declare stamped-meta remember-ref! reconcile-into)

;; ═══════════════════════════════════════════════════════════════════════════
;; The diff — one keyed reconcile for wrapper children AND container fill
;; ═══════════════════════════════════════════════════════════════════════════

(defn- itemize-prev
  "Raw stored child (from a container's lens or a wrapper's kid list) →
   previous item. Kind/ownership inferred: our stamps mark DSL-built
   hosts, ComponentFn instances are always ours, foreign records/entries
   are never owned (and so never disposed by reconcile)."
  [raw]
  (letfn [(mk [fallback kind key]
            (if-some [k key] {::user-key k ::kind kind} fallback))]
    (cond
      (and (map? raw) (contains? raw :component))
      {:kind ::entry :mkey (stack/entry-component raw)
       :c (stack/entry-component raw) :item raw :owned false}
      ;; our stamps come FIRST: an explicit :key must override the
      ;; tag/fn fallback on the previous side too, or keyed matching dies
      ;; at the second pass (::fncomponent marks fn wrappers)
      (and (record? raw) (some? (:dsl/meta raw)))
      (let [{:keys [tag key ref]} (stamped-meta raw)]
        (if (= tag ::fncomponent)
          {:kind ::fncomp :mkey (mk (:f raw) ::fncomp key) :key key :ref ref
           :c raw :item raw :owned true}
          {:kind ::host :mkey (mk tag ::host key) :key key :ref ref
           :c raw :item raw :owned true}))
      (instance? ComponentFn raw)
      {:kind ::fncomp :mkey (:f raw) :c raw :item raw :owned true}
      (record? raw)
      {:kind ::record :mkey raw :c raw :item raw :owned false}
      (mounted-component? raw)
      ;; reified/deftype'd IComponent stored by a previous pass — foreign,
      ;; like the record splice it mirrors
      {:kind ::record :mkey raw :c raw :item raw :owned false}
      :else
      (throw (ex-info
              (str "kmet.tui.hiccup: unexpected child in container storage: "
                   (pr-str raw))
              {:child raw})))))

(defn- retire-item!
  "Dispose an owned previous item (clearing its ref first — dispose owns
   the ref lifecycle, on the handle AND in the stamp). Foreign items are
   left alone: they are owned elsewhere and merely leave the child list."
  [{:keys [c ref owned]}]
  (when owned
    (bump! :disposals)
    (when ref
      (-fill-ref! ref nil)
      (when-some [m (:dsl/meta c)]
        (swap! m assoc :ref nil)))
    (protocols/dispose c)))

(defn- stamp!
  "Mark a freshly-constructed component as DSL-owned. The stamp is an ATOM
   (record identity must never change, yet reconciliation must remember
   per-instance facts across passes): :tag kinds the component, :props is
   the leaf reuse fast-path comparison, :key the explicit key (match-kind
   must survive into the next pass), :ref the last ref handle pointed at
   this instance — cleared when the instance leaves its tree. Records
   tolerate the extra key."
  ([c tag props] (stamp! c tag props nil))
  ([c tag props key]
   (assoc c :dsl/meta (atom {:tag tag :props props :key key :ref nil}))))

(defn- stamped-meta
  "The deref'd DSL stamp of C (callers guarantee presence)."
  [c]
  @(:dsl/meta c))

(defn- remember-ref!
  "Record R as C's live ref handle (so removal can clear it later)."
  [c r]
  (when-some [m (:dsl/meta c)]
    (swap! m assoc :ref r)))

(defn- construct-item
  "Build a fresh component for a desired item (construction inputs on the
   item; containers recursively reconcile their children here — licensed
   for entry maps by the TAG's own :entries?, never the parent context)."
  [{:keys [kind tag spec props nodes f ctree c item] :as d}]
  (case kind
    ::host
    (let [comp (stamp! ((:ctor spec) props) tag props (:key d))]
      (bump! :constructs)
      (when (and (:lens spec) (seq nodes))
        (reconcile-into (:lens spec) comp nodes (boolean (:entries? spec))))
      (when-some [r (:ref d)]
        (-fill-ref! r comp)
        (remember-ref! comp r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c comp :item comp :owned true})
    ::fncomp
    (let [wrapper (assoc (make-component-fn f ctree)
                         :dsl/meta (atom {:tag ::fncomponent
                                          :key (:key d) :ref nil}))]
      (reset! (:props wrapper) props)
      (bump! :constructs)
      (when-some [r (:ref d)]
        (-fill-ref! r wrapper)
        (remember-ref! wrapper r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c wrapper :item wrapper :owned true})
    ::string
    (let [comp (stamp! (text/make-text (:text props) 0 0)
                       ::string props (:key d))]
      (bump! :constructs)
      {:kind kind :mkey ::string :c comp :item comp :owned true})
    ;; ::record / ::entry pass through — never constructed, never owned
    {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
     :c c :item item :owned false}))

(defn- reuse-or-build
  "Matched pair → [kept-item retired-prev?]. Display leaves rebuild when
   their props changed (identity-free; the equal-props fast path keeps
   unchanged frames free); everything else keeps the previous instance.
   A tag with an :apply spec gets the third path: props changed, but the
   live instance can express them — patch in place and keep it (state and
   focus survive), falling back to rebuild when :apply declines."
  [d prev]
  (letfn [(keep [c]
            {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d)
                    :ref (:ref d) :c c :item c :owned true}
             :retire nil})
          (fill-ref! [c]
            ;; The element's PREVIOUS handle is stale once the element
            ;; declares a different one (or none): clear it, or an
            ;; abandoned handle derefs a live component forever. The
            ;; stamp remembers the handle that must be cleared when the
            ;; element later leaves (retire-item!).
            (let [prev-ref (:ref (stamped-meta c))
                  new-ref (:ref d)]
              (when (and (some? prev-ref) (not (identical? prev-ref new-ref)))
                (-fill-ref! prev-ref nil))
              (when-some [r new-ref]
                (-fill-ref! r c))
              (when-not (identical? prev-ref new-ref)
                (remember-ref! c new-ref))))]
    (case (:kind d)
      ::host
      (let [container? (some? (:lens (:spec d)))
            apply-fn (:apply (:spec d))
            prev-props (:props (stamped-meta (:c prev)))
            props-same? (= (:props d) prev-props)]
        (cond
          ;; Containers always keep their instance and reconcile their
          ;; children in place; their structural props patch through :apply
          ;; when the tag declares one (§4's props/state migration —
          ;; padding, gap), staying as constructed otherwise. A container
          ;; must never take the rebuild branch: a fresh construct starts
          ;; with an empty child pool, so the whole subtree (and all
          ;; descendant state) would be lost.
          container?
          (do (bump! :reuses)
              (reconcile-into (:lens (:spec d)) (:c prev) (:nodes d)
                              (boolean (:entries? (:spec d))))
              (when (and apply-fn (not props-same?)
                         (apply-fn (:c prev) prev-props (:props d)))
                (bump! :applies)
                (swap! (:dsl/meta (:c prev)) assoc :props (:props d)))
              (fill-ref! (:c prev))
              (keep (:c prev)))

          props-same?
          (do (bump! :reuses)
              (fill-ref! (:c prev))
              (keep (:c prev)))

          ;; changed props on a patchable tag: patch the live instance and
          ;; keep it. The stamp's :props is the next pass's comparison
          ;; base, so the patched instance must remember the new props.
          (and apply-fn (apply-fn (:c prev) prev-props (:props d)))
          (do (bump! :applies)
              (swap! (:dsl/meta (:c prev)) assoc :props (:props d))
              (fill-ref! (:c prev))
              (keep (:c prev)))

          ;; leaf with different props: rebuild. Retire FIRST — the old
          ;; instance's stamp holds the same ref handle the fresh construct
          ;; is about to fill; retiring afterwards would wipe it. Returns
          ;; the full new item map (no retire left for the caller).
          :else
          (do (retire-item! prev)
              {:item (construct-item d) :retire nil})))
      ::fncomp
      (let [w (:c prev)]
        (bump! :reuses)
        (reset! (:props w) (:props d))
        (reset! (:ctree w) (:ctree d))
        (fill-ref! w)
        (keep w))
      ;; ::string — a display leaf like a host leaf: rebuild when the text
      ;; changed (the parsed item has no :c; falling through to the
      ;; passthrough below would install a nil child), keep otherwise
      ::string
      (let [prev-props (:props (stamped-meta (:c prev)))]
        (if (= (:props d) prev-props)
          (do (bump! :reuses)
              (keep (:c prev)))
          (do (retire-item! prev)
              {:item (construct-item d) :retire nil})))
      ;; ::record / ::entry — passthrough, take the DESIRED payload (the
      ;; bucket matched structically; the tree's own object is canonical)
      {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d) :ref (:ref d)
              :c (:c d) :item (:item d) :owned false}
       :retire nil})))

(defn- split-bucket
  "Pop the oldest previous item off MKEY's bucket (order-stable matching
   within a match-kind: i-th desired ↔ i-th previous). Buckets carry a
   consumption cursor, so popping is O(1) — the previous `(vec (rest ...))`
   copy made long unkeyed lists superlinear."
  [buckets mkey]
  (if-some [{:keys [v i]} (get buckets mkey)]
    (if (< i (count v))
      [(nth v i) (assoc buckets mkey {:v v :i (inc i)})]
      [nil buckets])
    [nil buckets]))

(defn- diff-items
  "The keyed diff (tui.md §2.3): walk desired items in order, reusing the
   matching previous item per match-kind bucket; leftovers are retired
   (disposed iff owned, ref cleared). Returns the kept items in desired
   order."
  [prev-items desired]
  (let [buckets (volatile!
                 (reduce (fn [m it]
                           (update m (:mkey it)
                                   (fn [b]
                                     (if b
                                       (update b :v conj it)
                                       {:v [it] :i 0}))))
                         {} prev-items))
        out (volatile! [])]
    (doseq [d desired]
      (let [[prev buckets'] (split-bucket @buckets (:mkey d))]
        (vreset! buckets buckets')
        (if prev
          (let [{:keys [item retire]} (reuse-or-build d prev)]
            (when retire (retire-item! retire))
            (vswap! out conj item))
          (vswap! out conj (construct-item d)))))
    (doseq [{:keys [v i]} (vals @buckets)
            prev (subvec v i)]
      (retire-item! prev))
    (vec @out)))

(defn- reconcile-into
  "Reconcile NODES into CONTAINER through SPEC's lens: read the current
   raw children, diff, install the result back. The one children
   mechanism — used by host containers at every nesting level (tui.md §2.3)."
  [lens container nodes stack?]
  (let [prev (mapv itemize-prev ((:get lens) container))
        kept (diff-items prev (parse-nodes nodes stack?))]
    ((:put lens) container kept)))

(defn- as-roots
  "TREE → sequence of root nodes. An element VECTOR is ONE root; only a
  seq splices as multiple roots (vectors ARE sequential — check vector?
  first; this exact trap produced three bugs during stage 3)."
  [tree]
  (cond
    (nil? tree) []
    (vector? tree) [tree]
    (sequential? tree) tree
    :else [tree]))

(defn reconcile!
  "Diff TREE against KIDS-ATOM's current items and install the result —
   the ComponentFn render pass (tui.md §2.3). TREE may be one element, a
   sequence of roots, or nil. Returns the kept items."
  [kids-atom tree]
  (let [kept (diff-items @kids-atom (parse-nodes (as-roots tree) false))]
    (reset! kids-atom kept)
    kept))

;; ═══════════════════════════════════════════════════════════════════════════
;; Public compile + render surfaces
;; ═══════════════════════════════════════════════════════════════════════════

(defn compile-element
  "Compile one tree node into a live component (fresh construction —
   reconcile against an empty previous pool). Returns nil for nil.
   Mounted components pass through untouched (records and reified
   IComponent instances alike — identity preserved); stack-entry maps
   ({:component c ...}) are rejected outside stack tags; strings become
   bare zero-padding Text. A SEQ input throws — splicing multiple roots
   is compile-tree's business."
  [el]
  (when (and (sequential? el) (not (vector? el)))
    (throw (ex-info
            (str "kmet.tui.hiccup: seq passed to compile-element — a seq "
                 "splices as CHILDREN of an element or ROOTS via compile-tree")
            {:node el})))
  (let [parsed (parse-nodes [el] false)]
    (when (seq parsed)
      (:c (first (diff-items [] parsed))))))

(defn compile-tree
  "Compile a whole tree: one element VECTOR, or a sequence of elements
   spliced as multiple roots (a list/seq — a vector is always ONE element).
   Returns a single component, a vector of components, or nil."
  [tree]
  ;; nil → as-roots [] → zero items → nil: the empty case falls through
  (let [items (diff-items [] (parse-nodes (as-roots tree) false))]
    (case (count items)
      0 nil
      1 (:c (first items))
      (mapv :c items))))

(defn- render-compiled [compiled width]
  (cond
    (nil? compiled) []
    (sequential? compiled) (->> compiled
                                (mapv #(protocols/render % width))
                                (apply concat)
                                vec)
    :else (vec (protocols/render compiled width))))

(defn- owned?
  "DSL-constructed: fn wrappers always, host tags via their stamp. The
   stamp check is guarded by record? — a spliced reified component isn't
   associative and contains? would throw."
  [c]
  (or (instance? ComponentFn c)
      (and (record? c) (contains? c :dsl/meta))))

(defn dispose-tree!
  "Dispose every DSL-owned component inside COMPS — a compiled component,
   a sequence of them, or nil. Foreign spliced records are left alone.
   Complements compile-tree for lifetimes OUTSIDE the reconcile pool:
   widget factories and dialog builders compile once, hold the result,
   and call this when replaced or closed (the widget contract's :dispose
   hook is the natural trigger)."
  [comps]
  (doseq [c (cond
              (nil? comps) []
              (sequential? comps) comps
              :else [comps])
          :when (owned? c)]
    (protocols/dispose c)))

(defn render-lines
  "Headless render: compile TREE and return the exact lines the frame loop
   would draw. No tty, no sleeps — fast-path test material. DSL-built
   components are disposed afterwards (their reactions unwound); foreign
   spliced records are left alone."
  [tree width]
  (let [compiled (compile-tree tree)]
    (try
      (render-compiled compiled width)
      (finally
        (dispose-tree! compiled)))))

(defn compute
  "A derived reactive ref over DEPS (tui.md §3.3): kmet.libs.reakt/derive
   plus two TUI-side conveniences — creation counting (--debug :computes)
   and auto-disposal when created during a component render pass (macros/*store*
   bound under with-let): the reaction is disposed with the component, so
   per-instance computes need no manual cleanup. Top-level shared computes
   (no enclosing store) live forever, which is the point. Create computes
   ONCE per instance — one built bare inside a component body leaks a
   reaction per pass, visible as :computes climbing in hiccup's --debug
   counters. See kmet.libs.reakt/derive for the full semantics (dep
   seeding, =-gated notification, batched re-runs)."
  [deps f]
  ;; Count creations: computes are created ONCE per instance (top-level def
  ;; or with-let init). A compute built bare inside a component body leaks a
  ;; reaction per render pass — the counter climbing frame over frame makes
  ;; that visible (tui.md §3.3).
  (let [_ (bump! :computes)
        rx (r/derive deps f)]
    (when macros/*store*
      (macros/register-cleanup!
       (gensym "compute-")
       #(r/dispose! rx)))
    rx))

;; root — the one mount path (tui.md §2.6)
;; ═══════════════════════════════════════════════════════════════════════════

(defn root
  "Mount TREE-OR-FN as an IComponent — the one way trees enter the TUI.
   An element (or sequence of elements) is compiled/reconciled on render
   passes; a vector whose first item is itself a vector counts as that
   sequence of roots; a bare FN is shorthand for [f {}] — called with the
   props map each time its reaction re-runs, so closures over app atoms
   are tracked at deref time and the body only re-runs when they actually
   change (memoized; the idle UI runs zero bodies). The owner mounts the
   returned record anywhere a component is accepted and calls dispose
   when it leaves."
  [tree-or-fn]
  (if (fn? tree-or-fn)
    ;; bare fn: shorthand for [f {}] — mounted directly, no extra wrapper
    (make-component-fn tree-or-fn)
    (let [tree (if (and (vector? tree-or-fn)
                        (vector? (first tree-or-fn)))
                 ;; vector of element vectors = spliced roots
                 (seq tree-or-fn)
                 tree-or-fn)]
      (make-component-fn (constantly tree)))))

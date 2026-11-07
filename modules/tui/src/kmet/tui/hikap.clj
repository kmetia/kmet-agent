(ns kmet.tui.hikap
  "TUI backend for the generic hiccup engine (kmet.hikap.core): the entry
   points bound to the TUI backend — compile-element/compile-tree/root,
   reconcile!, materialize-ref!, and the headless render-lines surface —
   plus what the engine takes from this backend: the closed host tag
   table, the width-bound render context (*width*/*comp*), and the
   IComponent protocol extension for the engine's ComponentFn.

   Backend-independent engine surface stays in kmet.hikap.core and is
   required directly: ref handles (kmet.hikap.core/ref), derived refs
   (kmet.hikap.core/compute), counters. This namespace forwards nothing."
  (:refer-clojure :exclude [ref])
  (:require
   [kmet.hikap.core :as engine]
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
   [kmet.libs.fuzzy :as fuzzy]
   [kmet.tui.protocols :as protocols]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dynamics — the TUI render context (tui.md §2.5)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *width*
  "The current render-pass width, bound around ComponentFn bodies — for
  status bars/dialog headers that must truncate at the real width. Read
  only; nil outside a component render.")

(def ^:dynamic *comp*
  "The ComponentFn whose body is running. nil outside one.")
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
                                                :interval-ms (or interval-ms spinner/default-interval-ms)
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
                                   (not= (or (:interval-ms prev) spinner/default-interval-ms)
                                         (or (:interval-ms props) spinner/default-interval-ms)))
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
   :input        {:ctor (fn [{:keys [value cursor focused? on-submit on-escape on-change]}]
                          (let [i (input/make-input)]
                            (when value (input/input-set-value! i value))
                            (when (some? cursor) (input/input-set-cursor! i cursor))
                            (when (some? focused?) (protocols/set-focused! i (boolean focused?)))
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
                           ;; :focused? is the element's own emphasis flag
                           ;; (cursor + key eligibility), NOT input routing —
                           ;; the DSL has no TUI. Written when it changes, so
                           ;; a host's imperative set-focused! is never
                           ;; fought by a re-render of unchanged props (the
                           ;; equal-props pass skips :apply entirely).
                           (when (not= (:focused? props) (:focused? prev))
                             (protocols/set-focused! i (boolean (:focused? props))))
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
                                      enable-search max-visible
                                      max-description-lines]}]
                           (let [sl (settings-list/make-settings-list
                                     (or items [])
                                     :theme (or theme settings-list/default-theme)
                                     :on-change on-change
                                     :enable-search (boolean enable-search)
                                     :max-visible (or max-visible 10)
                                     :max-description-lines max-description-lines)]
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
                                (settings-list/settings-list-set-max-description-lines!
                                 sl (:max-description-lines props))
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

;; ═══════════════════════════════════════════════════════════════════════════
;; The backend map
;; ═══════════════════════════════════════════════════════════════════════════

(defn- mounted-component?
  "A non-record object that plausibly dispatches as an IComponent — a
  reified or deftype'd component spliced into a tree by host code (the
  ui-custom dialog wrapper, widget factories). Best-effort by necessity:
  bb's satisfies? can return false for reifies created in other
  evaluation contexts even though method dispatch works on them (the
  documented SCI gotcha), and the protocol method table misses those
  too — so both signals are OR-ed and anything slipping past still
  fails LOUDLY in the engine instead of corrupting a render pass."
  [node]
  (try
    (or (satisfies? protocols/IComponent node)
        (contains? (methods protocols/render) (class node)))
    (catch Exception _ false)))

(defn- nearest-of
  "Best fuzzy match for NAME among keyword CANDIDATES, for did-you-mean
   (lower score is a better match)."
  [name candidates]
  (->> candidates
       (keep (fn [t]
               (let [{:keys [matches score]}
                     (fuzzy/fuzzy-match name (subs (str t) 1))]
                 (when matches [score t]))))
       (sort-by first)
       first
       second))

(defn- did-you-mean
  "The backend's did-you-mean hook: nearest tag when one is close enough."
  [name candidates]
  (if-some [near (nearest-of name candidates)]
    (str " Did you mean " near "?")
    ""))

(def tui-backend
  "The TUI host backend for kmet.hikap.core: tag table, width-bound
   context, line rendering through protocols/IComponent."
  {:name :tui
   :tags tags
   :text-leaf (fn [text _props] (text/make-text text 0 0))
   :component? mounted-component?
   :entry-component stack/entry-component
   :dispose! protocols/dispose-component!
   :render-items (fn [items ctx] (protocols/render-all items ctx :c))
   :render (fn [c ctx] (vec (protocols/render c ctx)))
   :render-all (fn [components ctx] (protocols/render-all components ctx))
   :bind-context (fn [component ctx thunk]
                   (binding [*width* ctx *comp* component] (thunk)))
   :did-you-mean did-you-mean})

;; The engine owns the fn-component wrapper; the TUI side teaches it the
;; IComponent protocol (render to lines, no input, no extra cache state).
(extend-type kmet.hikap.core.ComponentFn
  protocols/IComponent
  (render [this width] (engine/render-component this width))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil)
  (dispose [this] (engine/dispose-component this)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Public facade — the TUI-bound entry points
;; ═══════════════════════════════════════════════════════════════════════════

(defn compile-element
  "Compile one tree node into a live component (fresh construction —
   reconcile against an empty previous pool). Returns nil for nil."
  [el]
  (engine/compile-element tui-backend el))

(defn compile-tree
  "Compile a whole tree: one element VECTOR, or a sequence of elements
   spliced as multiple roots. Returns a single component, a vector of
   components, or nil."
  [tree]
  (engine/compile-tree tui-backend tree))

(defn dispose-tree!
  "Dispose every DSL-owned component inside COMPS — a compiled component,
   a sequence of them, or nil. Foreign spliced records are left alone."
  [comps]
  (engine/dispose-tree! tui-backend comps))

(defn reconcile!
  "Diff TREE against KIDS-ATOM's current items and install the result —
   the ComponentFn render pass."
  [kids-atom tree]
  (engine/reconcile! tui-backend kids-atom tree))

(defn- render-compiled [compiled width]
  (engine/render tui-backend compiled width))

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

(defn materialize-ref!
  "The component REF points at, compiling OWNER's tree first when the ref is
   still empty. WIDTH (default 80) only shapes the discarded render;
   structure is what the ref needs. Returns nil when the element is
   genuinely absent from the tree."
  ([owner ref] (materialize-ref! owner ref 80))
  ([owner ref width]
   (engine/materialize-ref! tui-backend owner ref width)))

(defn root
  "Mount TREE-OR-FN as an IComponent — the one way trees enter the TUI.
   See kmet.hikap.core/root."
  [tree-or-fn]
  (engine/root tui-backend tree-or-fn))



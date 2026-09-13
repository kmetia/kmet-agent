(ns kmet.tui.core
  "TUI - Main class for managing terminal UI with differential rendering.
   Primary entry point for kmet.tui — all public symbols are re-exported
   from this namespace for convenience."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.reakt :as reakt]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.timers :as timers]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as utils]
            [kmet.libs.terminal-image :as img]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.markdown :as markdown]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.scroll-view :as scroll-view]
            [kmet.tui.components.stack :as stack]
            ;; pi-parity components (previously stubs — now implemented)
            [kmet.tui.components.alt-screen-flash :as alt-screen-flash]
            [kmet.tui.components.cancellable-loader :as cancellable-loader]
            [kmet.tui.components.truncated-text :as truncated-text]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.components.v-stack :as v-stack]
            [kmet.tui.components.dynamic-border :as dynamic-border]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Protocol re-exports
;; ═══════════════════════════════════════════════════════════════════════════

(def IComponent protocols/IComponent)
(def IFocusable protocols/IFocusable)
(def IEditorComponent protocols/IEditorComponent)
(defn render
  "One headless frame: drain the reaction batch queue, then render C at
   WIDTH. Mirrors the live loop (flush! runs at the tick before the render
   gate reads the caches), so queued recomputes — computes and reactions
   dirtied since the last frame — are always current in what gets drawn.
   The flush is a no-op while nothing is queued."
  [c width]
  (reakt/flush!)
  (protocols/render c width))
(def handle-input protocols/handle-input)
(def invalidate protocols/invalidate)
(def focused protocols/focused)
(def set-focused! protocols/set-focused!)

;; ═══════════════════════════════════════════════════════════════════════════
;; Cursor
;; ═══════════════════════════════════════════════════════════════════════════

(defn- extract-cursor-position
  "Find CURSOR-MARKER in rendered lines (viewport only), strip it from output,
   and return {:lines cleared-lines :cursor {:row r :col c}}.
   Returns {:lines original-lines :cursor nil} when no marker found.
   Only scans the bottom `height` lines (visible viewport), matching Pi's approach."
  [lines height]
  (let [viewport-top (max 0 (- (count lines) height))]
    (loop [i (dec (count lines))]
      (if (>= i viewport-top)
        (let [line (nth lines i)]
          (if-let [marker-idx (clojure.string/index-of line utils/CURSOR-MARKER)]
            (let [before (subs line 0 marker-idx)
                  after (subs line (+ marker-idx (count utils/CURSOR-MARKER)))
                  col (utils/visible-width before)
                  new-line (str before after)
                  new-lines (assoc lines i new-line)]
              {:lines new-lines :cursor {:row i :col col}})
            (recur (dec i))))
        {:lines lines :cursor nil}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; CSI 2026 sync
;; ═══════════════════════════════════════════════════════════════════════════

(def CSI-2026-H terminal/CSI-2026-SYNC-ON)
(def CSI-2026-L terminal/CSI-2026-SYNC-OFF)

;; ═══════════════════════════════════════════════════════════════════════════
;; TUI
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord TUI [terminal components focused-component
                input-listeners previous-lines
                previous-width render-requested? force-redraw?
                running? stopped? overlays
                render-loop input-reader current-reader
                flashes focus-order-counter
                show-hardware-cursor? keyboard-protocol-pushed?
                negotiation-buffer negotiation-timer
                previous-height max-lines-rendered clear-on-shrink?
                full-redraw-count scrollback-dirty? previous-kitty-image-ids
                pending-osc-11? osc-11-queries
                color-scheme-listeners terminal-response-buffer
                terminal-response-timer color-scheme-notifications-enabled?
                debug-redraw? tui-debug?
                input-generation incomplete-flush-timer
                focus-home])

(declare tui-request-render tui-stop set-focused-component!
         overlay-visible? overlay-handle process-input-buffer! tui-invalidate
         ghost-restore!)

(defn create-tui [terminal]
  (let [tui (map->TUI {:terminal (atom terminal)
                       :components (atom [])
                       :focused-component (atom nil)
                       :input-listeners (atom [])
                       :previous-lines (atom [])
                       :previous-width (atom 0)
                       :render-requested? (atom false)
                       :force-redraw? (atom false)
                       :running? (atom false)
                       :stopped? (atom false)
                       :overlays (atom [])
                       :render-loop (atom nil)
                       :input-reader (atom nil)
                       :current-reader (atom nil)
                       :flashes (atom nil)
                       :focus-order-counter (atom 0)
                       :show-hardware-cursor? (atom (= (System/getenv "KMET_HARDWARE_CURSOR") "1"))
                       :keyboard-protocol-pushed? (atom false)
                       :negotiation-buffer (atom "")
                       :negotiation-timer (atom nil)
                       :previous-height (atom 0)
                       :max-lines-rendered (atom 0)
                       :clear-on-shrink? (atom (= (System/getenv "KMET_CLEAR_ON_SHRINK") "1"))
                       :full-redraw-count (atom 0)
                       :scrollback-dirty? (atom false)
                       :previous-kitty-image-ids (atom #{})
                       :pending-osc-11? (atom false)
                       :osc-11-queries (atom [])
                       :color-scheme-listeners (atom #{})
                       :terminal-response-buffer (atom "")
                       :terminal-response-timer (atom nil)
                       :color-scheme-notifications-enabled? (atom false)
                       :debug-redraw? (atom (= (System/getenv "KMET_DEBUG_REDRAW") "1"))
                       :tui-debug? (atom (= (System/getenv "KMET_TUI_DEBUG") "1"))
                       :input-generation (atom 0)
                       :incomplete-flush-timer (atom nil)
                       ;; terminal focus fallback, registered by the app
                       ;; layer (tui-set-focus-home!) — see resolve-focus-home
                       :focus-home (atom nil)})]
    ;; Ghost-focus guard: every overlay removal is a swap! on this atom,
    ;; so the watch is the one chokepoint that cannot be bypassed by a
    ;; future removal path forgetting to restore focus (see
    ;; ghost-restore!).
    (add-watch (:overlays tui) ::ghost-guard
               (fn [_ _ old new] (ghost-restore! tui old new)))
    ;; AltScreenFlashContainer owned by the TUI (pi: TuiAltScreen owns its
    ;; flash container) — the render loop composites flash lines over the
    ;; screen window; tui-flash! / tui-flash-dispose! are the public API.
    (reset! (:flashes tui)
            (alt-screen-flash/make-alt-screen-flash #(tui-request-render tui)))
    tui))

(defn tui-add-child [tui c] (swap! (:components tui) conj c))
(defn tui-remove-child [tui c]
  (swap! (:components tui) (fn [v] (vec (remove #(identical? % c) v))))
  ;; a removed component is leaving the tree — release its resources
  ;; (dispose is idempotent, tui.md §5.1)
  (protocols/dispose c))
(defn tui-clear [tui]
  (doseq [c @(:components tui)] (protocols/dispose c))
  (reset! (:components tui) []))

(defn tui-set-focus
  "Point input at COMPONENT (pi: public setFocus)."
  [tui component]
  (set-focused-component! tui component))

(defn tui-set-focus-home!
  "Register the terminal focus fallback as a THUNK resolved lazily at
   restore time; it must return a mounted component or nil. Interactive
   points it through dock state plus the ACTIVE editor, so custom-editor
   swaps stay live and a mounted dock selector outranks the editor; when
   an overlay stops capturing and no other capturing overlay sits below
   it, input lands here. Pass nil to unregister. Returns nil."
  [tui f]
  (reset! (:focus-home tui) f)
  nil)

(defn- resolve-focus-home
  "Invoke the registered focus home; nil when none is registered or the
   thunk throws. Catches Throwable: this runs inside dispatch-input!, and
   an Error escaping there kills the reader loop - losing one restore is
   always cheaper than dead input."
  [tui]
  (when-let [home @(:focus-home tui)]
    (try (home) (catch Throwable _ nil))))

(defn tui-get-show-hardware-cursor
  "Whether the hardware terminal cursor is visible (pi: getShowHardwareCursor)."
  [tui]
  @(:show-hardware-cursor? tui))

(defn tui-set-show-hardware-cursor!
  "Enable/disable the visible hardware cursor (pi: setShowHardwareCursor).
   Disabling hides the cursor immediately; both paths request a re-render."
  [tui enabled?]
  (when (not= enabled? @(:show-hardware-cursor? tui))
    (reset! (:show-hardware-cursor? tui) enabled?)
    (when-not enabled?
      (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
    (tui-request-render tui)))

(defn tui-add-input-listener [tui f]
  (swap! (:input-listeners tui) conj f))
(defn tui-remove-input-listener [tui f]
  (swap! (:input-listeners tui) (fn [v] (vec (remove #(= % f) v)))))

;; ─── Flashes (pi: TuiAltScreen.flash — transient messages) ─────────────────

(defn tui-flash!
  "Show a transient inverse-video message over the bottom of the screen for
   DURATION-MS (default 1000), then remove it (pi: flash)."
  [tui message & {:keys [duration-ms]}]
  (alt-screen-flash/alt-screen-flash! @(:flashes tui) message :duration-ms duration-ms))

(defn tui-flash-dispose!
  "Clear all pending flashes immediately."
  [tui]
  (alt-screen-flash/alt-screen-flash-dispose! @(:flashes tui)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Overlays
;; ═══════════════════════════════════════════════════════════════════════════

;; Overlay stack entry. Mutable fields hold atoms so the handle keeps
;; identity across set-hidden!/focus/retarget mutations (kmet convention:
;; atoms for mutable state).
(defrecord Overlay [component options hidden? focus-order])

;; ─── Layout resolution (pi: resolveOverlayLayout + anchors) ────────────────

(defn- parse-size-value
  "Parse a size value: absolute number or percentage string (\"50%\") of
   REFERENCE-SIZE (pi: parseSizeValue). Unparseable values → nil."
  [v reference-size]
  (cond
    (nil? v) nil
    (number? v) v
    (string? v) (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" v)]
                  (int (Math/floor (* reference-size (/ (Double/parseDouble pct) 100.0))))
                  nil)
    :else nil))

(defn- resolve-anchor-row
  "Anchor → top row offset (pi: resolveAnchorRow)."
  [anchor height avail-height margin-top]
  (case anchor
    (:top-left :top-center :top-right) margin-top
    (:bottom-left :bottom-center :bottom-right) (+ margin-top (- avail-height height))
    ;; :left-center / :center / :right-center
    (+ margin-top (int (Math/floor (/ (- avail-height height) 2))))))

(defn- resolve-anchor-col
  "Anchor → left column offset (pi: resolveAnchorCol)."
  [anchor width avail-width margin-left]
  (case anchor
    (:top-left :left-center :bottom-left) margin-left
    (:top-right :right-center :bottom-right) (+ margin-left (- avail-width width))
    ;; :top-center / :center / :bottom-center
    (+ margin-left (int (Math/floor (/ (- avail-width width) 2))))))

(defn- resolve-overlay-layout
  "Resolve overlay sizing and position from OPTIONS (pi: resolveOverlayLayout).
   Returns {:width w :row r :col c :max-height mh-or-nil}."
  [options overlay-height term-width term-height]
  (let [opt (or options {})
        margin (if (number? (:margin opt))
                 {:top (:margin opt) :right (:margin opt)
                  :bottom (:margin opt) :left (:margin opt)}
                 (:margin opt))
        margin-top (max 0 (:top margin 0))
        margin-right (max 0 (:right margin 0))
        margin-bottom (max 0 (:bottom margin 0))
        margin-left (max 0 (:left margin 0))
        avail-width (max 1 (- term-width margin-left margin-right))
        avail-height (max 1 (- term-height margin-top margin-bottom))
        width (or (parse-size-value (:width opt) term-width) (min 80 avail-width))
        width (if (some? (:min-width opt)) (max width (:min-width opt)) width)
        width (max 1 (min width avail-width))
        max-height (parse-size-value (:max-height opt) term-height)
        max-height (when (some? max-height) (max 1 (min max-height avail-height)))
        effective-height (if (some? max-height) (min overlay-height max-height) overlay-height)
        row (if (some? (:row opt))
              (if (string? (:row opt))
                (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" (:row opt))]
                  (let [max-row (max 0 (- avail-height effective-height))]
                    (+ margin-top (int (Math/floor (* max-row (/ (Double/parseDouble pct) 100.0))))))
                  (resolve-anchor-row :center effective-height avail-height margin-top))
                (:row opt))
              (resolve-anchor-row (:anchor opt :center) effective-height avail-height margin-top))
        col (if (some? (:col opt))
              (if (string? (:col opt))
                (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" (:col opt))]
                  (let [max-col (max 0 (- avail-width width))]
                    (+ margin-left (int (Math/floor (* max-col (/ (Double/parseDouble pct) 100.0))))))
                  (resolve-anchor-col :center width avail-width margin-left))
                (:col opt))
              (resolve-anchor-col (:anchor opt :center) width avail-width margin-left))
        row (+ row (or (:offset-y opt) 0))
        col (+ col (or (:offset-x opt) 0))
        row (max margin-top (min row (- term-height margin-bottom effective-height)))
        col (max margin-left (min col (- term-width margin-right width)))]
    {:width width :row row :col col :max-height max-height}))

(defn- normalize-overlay-options
  "Map legacy kmet option keys (:x :y :height) onto pi OverlayOptions
   (:col :row :max-height). Other keys pass through unchanged."
  [options]
  (cond-> (dissoc options :x :y :height)
    (contains? options :x) (assoc :col (:x options))
    (contains? options :y) (assoc :row (:y options))
    (contains? options :height) (assoc :max-height (:height options))))

(defn tui-show-overlay
  "Show an overlay component with configurable positioning and sizing
   (pi: TUI.showOverlay). OPTIONS (pi: OverlayOptions):
     :width / :min-width / :max-height — number or percentage string
       (e.g. \"50%\"); width defaults to min(80, available)
     :anchor — :center :top-left :top-center :top-right :left-center
       :right-center :bottom-left :bottom-center :bottom-right (default :center)
     :offset-x / :offset-y — offsets from the anchor position
     :row / :col — absolute number or percentage string (overrides :anchor)
     :margin — number (all sides) or {:top :right :bottom :left}
     :visible — (fn [term-width term-height]) responsive visibility
     :non-capturing — true to render without taking keyboard focus
   Legacy kmet keys :x / :y / :height map to :col / :row / :max-height.
   Returns an OverlayHandle map (pi: OverlayHandle): :hide, :set-hidden!,
   :is-hidden?, :focus, :unfocus (with {:target comp} or nil), :is-focused?."
  [tui component & {:as options}]
  (let [options (normalize-overlay-options options)
        entry (map->Overlay {:component component
                             :options options
                             :hidden? (atom false)
                             :focus-order (atom (swap! (:focus-order-counter tui) inc))})]
    (swap! (:overlays tui) conj entry)
    (when-not (:non-capturing options)
      (when (overlay-visible? tui entry)
        (tui-set-focus tui component)))
    (when-let [term @(:terminal tui)] (terminal/hide-cursor! term))
    (tui-request-render tui)
    (overlay-handle tui entry)))

;; ─── Visibility + focus restore state machine (pi) ─────────────────────────

(defn- overlay-visible?
  "True when an overlay entry is not hidden and its :visible callback (when
   provided) passes for the current terminal size (pi: isOverlayVisible)."
  [tui entry]
  (and (not @(:hidden? entry))
       (if-let [visible-fn (:visible (:options entry))]
         (if-let [term @(:terminal tui)]
           (visible-fn (terminal/columns term) (terminal/rows term))
           true)
         true)))

(defn- get-topmost-visible-overlay
  "Visual-frontmost visible capturing overlay by focus order
   (pi: getTopmostVisibleOverlay)."
  [tui]
  (reduce (fn [top o]
            (if (and (not (:non-capturing (:options o)))
                     (overlay-visible? tui o)
                     (or (nil? top) (> @(:focus-order o) @(:focus-order top))))
              o
              top))
          nil
          @(:overlays tui)))

(defn- set-focused-component!
  "Point focused-component at COMPONENT, flipping the IFocusable flag on
   both sides. Modality is not this fn's business - dispatch-input!
   enforces who holds input.

   satisfies? is sound here by construction: everything focusable is a
   defcomponent record (editor, dock selectors, overlay dialogs), and the
   one entry point for foreign components - normalize-custom-component -
   admits only records, duck-typed maps (wrapped in an adapter record)
   and trees, rejecting reifies whose satisfies? could lie under bb. If
   that ever loosens, flip this to probe-and-call like
   dispose-dialog-component!."
  [tui component]
  (let [prev @(:focused-component tui)]
    (when (satisfies? IFocusable prev)
      (set-focused! prev false))
    (reset! (:focused-component tui) component)
    (when (satisfies? IFocusable component)
      (set-focused! component true))))

;; ─── OverlayHandle (pi: OverlayHandle) ─────────────────────────────────────

(defn- overlay-restore-target
  "Where input should land once ENTRY stops capturing: topmost visible
   overlay other than ENTRY, then the app-registered focus home
   (tui-set-focus-home!) - nil when nothing live. Never an arbitrary root
   child: the old last-root-child fallback landed on the footer, which
   silently swallowed all input after overlay close."
  [tui entry]
  (or (when-let [top (get-topmost-visible-overlay tui)]
        (when-not (identical? top entry) (:component top)))
      (resolve-focus-home tui)))

(defn- ghost-restore!
  "Watch callback on the overlay stack: a swap! that removed an entry
   whose component currently holds focus means a removal path forgot to
   restore - hand input to the next capturing surface or home before the
   ghost swallows keys. Healthy paths restore explicitly right after
   their swap, but the watch runs first (inside the swap), so for them
   this IS the restore and their own call becomes a no-op. Never throws -
   catches Throwable because watches run inside swap! on the reader's
   dispatch path, and an Error here would kill input entirely."
  [tui old new]
  (try
    (let [fc @(:focused-component tui)]
      (when-let [removed (some (fn [e]
                                 (when (and (identical? (:component e) fc)
                                            (not (some #(identical? e %) new)))
                                   e))
                               old)]
        (tui-set-focus tui (overlay-restore-target tui removed))))
    (catch Throwable _ nil)))

(defn- overlay-handle
  "Return the OverlayHandle map for ENTRY: {:hide :set-hidden! :is-hidden?
   :focus :unfocus :is-focused?} (pi: OverlayHandle)."
  [tui entry]
  {:hide (fn []
           ;; removal restores via the ::ghost-guard watch on the stack
           (when (some #(identical? % entry) @(:overlays tui))
             (swap! (:overlays tui) (fn [v] (vec (remove #(identical? % entry) v))))
             (when (empty? @(:overlays tui))
               (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
             (tui-request-render tui)))
   :set-hidden! (fn [hidden?]
                  (when (not= hidden? @(:hidden? entry))
                    (reset! (:hidden? entry) hidden?)
                    (if hidden?
                      ;; hidden overlays stop capturing immediately;
                      ;; null focus is fine - keys drop at the dispatch
                      ;; guard instead of reaching an invisible dialog
                      (when (identical? (:component entry) @(:focused-component tui))
                        (tui-set-focus tui (overlay-restore-target tui entry)))
                      (when (and (not (:non-capturing (:options entry)))
                                 (overlay-visible? tui entry))
                        (reset! (:focus-order entry) (swap! (:focus-order-counter tui) inc))
                        (tui-set-focus tui (:component entry))))
                    (tui-request-render tui)))
   :is-hidden? (fn [] @(:hidden? entry))
   :focus (fn []
            (when (and (some #(identical? % entry) @(:overlays tui))
                       (overlay-visible? tui entry))
              (reset! (:focus-order entry) (swap! (:focus-order-counter tui) inc))
              (tui-set-focus tui (:component entry))
              (tui-request-render tui)))
   ;; Releases input now; the dispatch modality guard re-snaps to a still
   ;; visible capturing overlay on the next key unless {:target comp} was
   ;; given AND comp holds focus... it does not stick: modality wins.
   :unfocus (fn [& [unfocus-options]]
              (when (identical? (:component entry) @(:focused-component tui))
                (tui-set-focus tui (if (some? unfocus-options)
                                     (:target unfocus-options)
                                     (overlay-restore-target tui entry)))
                (tui-request-render tui)))
   :is-focused? (fn [] (identical? (:component entry) @(:focused-component tui)))})

(defn tui-hide-overlay
  "Hide the topmost overlay and restore focus - the visible overlay below
   it, else the focus home (pi: TUI.hideOverlay; the ::ghost-guard watch
   on the stack performs the restore)."
  [tui]
  (when (peek @(:overlays tui))
    (swap! (:overlays tui) pop)
    (when (empty? @(:overlays tui))
      (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
    (tui-request-render tui)))

(defn tui-has-overlay?
  "True when any visible overlay is on the stack (pi: TUI.hasOverlay)."
  [tui]
  (boolean (some #(overlay-visible? tui %) @(:overlays tui))))

(defn- composite-overlays
  "Composite all visible overlays (sorted by focus order, later = on top)
   onto the rendered base LINES (pi: TUI.compositeOverlays). Each overlay is
   positioned by resolve-overlay-layout; content rows are padded so overlay
   rows land in the terminal viewport; overlay lines are truncated to their
   declared width before compositing."
  [tui lines term-width term-height]
  (let [visible-entries (->> @(:overlays tui)
                             (filter #(overlay-visible? tui %))
                             (sort-by #(deref (:focus-order %))))]
    (if (empty? visible-entries)
      lines
      (let [rendered (mapv (fn [entry]
                             (let [options (:options entry)
                                   layout0 (resolve-overlay-layout options 0 term-width term-height)
                                   width (:width layout0)
                                   max-height (:max-height layout0)
                                   overlay-lines (vec (render (:component entry) width))
                                   overlay-lines (if (and max-height (> (count overlay-lines) max-height))
                                                   (subvec overlay-lines 0 max-height)
                                                   overlay-lines)
                                   layout (resolve-overlay-layout options
                                                                  (count overlay-lines)
                                                                  term-width term-height)]
                               {:lines overlay-lines
                                :row (:row layout)
                                :col (:col layout)
                                :width width}))
                           visible-entries)
            min-lines-needed (reduce (fn [m {:keys [row lines]}]
                                       (max m (+ row (count lines))))
                                     (count lines) rendered)
            working-height (max (count lines) term-height min-lines-needed)
            result (into (vec lines) (repeat (max 0 (- working-height (count lines))) ""))
            viewport-start (max 0 (- working-height term-height))]
        (reduce (fn [acc {:keys [lines row col width]}]
                  (reduce (fn [acc2 [i overlay-line]]
                            (let [idx (+ viewport-start row i)]
                              (if (and (>= idx 0) (< idx (count acc2)))
                                (let [line (if (> (utils/visible-width overlay-line) width)
                                             (:text (utils/slice-with-width overlay-line 0 width :strict? true))
                                             overlay-line)]
                                  (assoc acc2 idx
                                         (utils/composite-line (nth acc2 idx) line col width term-width)))
                                acc2)))
                          acc
                          (map-indexed vector lines)))
                result
                rendered)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════

(defn- composite-flashes
  "Overlay transient flash lines (AltScreenFlashContainer) onto the screen
   window — the bottom HEIGHT content rows — right-aligned, one flash per
   row (pi: compositeFlashes). Takes the LAST HEIGHT flash lines so a flash
   flood stays on-screen (pi: .slice(-height)). Returns LINES unchanged
   when nothing flashes."
  [flashes lines width height]
  (let [flash-lines (protocols/render flashes width)
        flash-lines (if (> (count flash-lines) height)
                      (vec (take-last height flash-lines))
                      flash-lines)
        n (count flash-lines)
        top (max 0 (- (count lines) height))]
    (if (zero? n)
      lines
      (loop [row 0, lines lines]
        (if (>= row n)
          lines
          (let [line (nth flash-lines row)
                fw (utils/visible-width line)]
            (if (pos? fw)
              (let [idx (+ top row)
                    out (utils/composite-line (if (< idx (count lines)) (nth lines idx) "")
                                              line (- width fw) fw width)]
                (recur (inc row)
                       (if (< idx (count lines))
                         (assoc lines idx out)
                         (conj lines out))))
              (recur (inc row) lines))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Kitty image diff support (pi: expandChangedRangeForKittyImages /
;; deleteChangedKittyImages / getKittyImageReservedRows)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- kitty-image-reserved-rows
  "Rows the image at INDEX occupies: the declared rows, bounded by the
   consecutive blank rows after it and by MAX-INDEX (pi:
   getKittyImageReservedRows). Must be computed on the raw, unpadded lines —
   padded spaces would terminate the walk immediately."
  [lines index & [max-index]]
  (let [rows (img/extract-kitty-image-rows (or (nth lines index nil) ""))
        max-rows (min rows
                      (if max-index
                        (- max-index index -1)
                        (count lines))
                      (- (count lines) index))]
    (if (<= rows 1)
      1
      (loop [reserved 1]
        (if (>= reserved max-rows)
          reserved
          (let [line (or (nth lines (+ index reserved) nil) "")]
            (if (or (img/is-image-line line) (pos? (utils/visible-width line)))
              reserved
              (recur (inc reserved)))))))))

(defn- collect-kitty-image-ids
  "All Kitty image ids referenced by LINES (pi: collectKittyImageIds)."
  [lines]
  (reduce (fn [acc line] (reduce conj acc (img/extract-kitty-image-ids line)))
          #{} lines))

(defn- expand-changed-range-for-kitty-images
  "Port of pi's expandChangedRangeForKittyImages: widen the changed range so it
   covers every image block that touches it in either prev or new lines."
  [first-changed last-changed prev lines]
  (let [expanded (volatile! [first-changed last-changed])
        expand-for (fn [ls]
                     (doseq [i (range (count ls))]
                       (when (seq (img/extract-kitty-image-ids (nth ls i)))
                         (let [block-end (+ i (kitty-image-reserved-rows ls i) -1)]
                           (when (or (>= i first-changed)
                                     (and (<= i last-changed) (>= block-end first-changed)))
                             (vswap! expanded
                                     (fn [[f l]] [(min f i) (max l block-end)])))))))]
    (expand-for prev)
    (expand-for lines)
    @expanded))

(defn- delete-changed-kitty-images
  "Port of pi's deleteChangedKittyImages: the delete sequence for all image ids
   in PREV within the changed range."
  [first-changed last-changed prev]
  (let [ids (reduce (fn [acc i]
                      (reduce conj acc (img/extract-kitty-image-ids (nth prev i ""))))
                    #{}
                    (range first-changed (min (inc last-changed) (count prev))))]
    (apply str (map img/delete-kitty-image ids))))

;; ─── Crash + debug logs (pi: pi-crash.log / PI_DEBUG_REDRAW / PI_TUI_DEBUG)

(defn- log-path [filename]
  (str (io/file (System/getProperty "user.dir") filename)))

(defn- append-log!
  "Append TEXT to a file at PATH, ignoring errors (pi: appendFileSync)."
  [path text]
  (try
    (with-open [w (io/writer path :append true)]
      (.write w text))
    (catch Exception _)))

(defn- write-crash-log!
  "Write all rendered lines + overflow info to kmet-crash.log in the cwd
   (pi: pi-crash.log)."
  [lines w idx vw]
  (let [path (log-path "kmet-crash.log")
        data (str "Crash at " (java.time.LocalDateTime/now) "\n"
                  "Terminal width: " w "\n"
                  "Line " idx " visible width: " vw "\n"
                  "\n"
                  "=== All rendered lines ===\n"
                  (apply str (map-indexed (fn [i l]
                                            (str "[" i "] (w=" (utils/visible-width l) ") " l "\n"))
                                          lines))
                  "\n")]
    (try
      (spit path data)
      (catch Exception _))))

(defn- tui-debug-dump!
  "Write a render frame dump to $TMPDIR/tui/ (or java.io.tmpdir/tui/ when
   TMPDIR is unset; /tmp/tui/ on hosts where java.io.tmpdir is /tmp) when
   KMET_TUI_DEBUG=1 (pi: PI_TUI_DEBUG render dumps)."
  [prev lines buffer w h viewport-top hardware-cursor-row]
  (try
    (let [dir (str (io/file (or (System/getenv "TMPDIR")
                                (System/getProperty "java.io.tmpdir"))
                            "tui"))]
      (.mkdirs (io/file dir))
      (let [path (str (io/file dir (str "render-" (System/nanoTime) "-"
                                        (rand-int 0x7fffffff) ".log")))]
        (spit path (str "viewportTop: " viewport-top "\n"
                        "hardwareCursorRow: " hardware-cursor-row "\n"
                        "height: " h "\n"
                        "width: " w "\n"
                        "newLines.length: " (count lines) "\n"
                        "previousLines.length: " (count prev) "\n"
                        "\n"
                        "=== newLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) lines))
                        "\n"
                        "=== previousLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) prev))
                        "\n"
                        "=== buffer ===\n" buffer "\n"))))
    (catch Exception _)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input reader
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private PASTE-START terminal/PASTE-START)
(def ^:private PASTE-END terminal/PASTE-END)

;; ─── Kitty protocol negotiation interception (pi: setupStdinBuffer) ────────

(defn- clear-negotiation-timer!
  "Cancel a pending negotiation flush timer."
  [tui]
  (when-let [t @(:negotiation-timer tui)]
    (future-cancel t)
    (reset! (:negotiation-timer tui) nil)))

(defn- schedule-negotiation-flush!
  "Port of pi's scheduleKeyboardProtocolNegotiationBufferFlush: after
   NEGOTIATION-FLUSH-TIMEOUT-MS, flush any still-held negotiation fragment
   back into the input buffer so it is not swallowed forever.
   Guarded by the input generation (like schedule-incomplete-flush!): a
   fragment is only flushed after true idleness — if a new char arrived
   while sleeping, the response is still coming and the flush is skipped
   (a stale flush would append the fragment after the fresh char and the
   re-process would dispatch the char as a key)."
  [tui read-fn buf]
  (when (and (nil? @(:negotiation-timer tui))
             (seq @(:negotiation-buffer tui)))
    (let [gen @(:input-generation tui)]
      (reset! (:negotiation-timer tui)
              (future
                (try
                  (Thread/sleep terminal/NEGOTIATION-FLUSH-TIMEOUT-MS)
                  (when (and (seq @(:negotiation-buffer tui))
                             (= gen @(:input-generation tui)))
                    (swap! buf str @(:negotiation-buffer tui))
                    (reset! (:negotiation-buffer tui) "")
                    (process-input-buffer! tui read-fn buf))
                  (catch Exception _))
                (reset! (:negotiation-timer tui) nil))))))

(defn- intercept-keyboard-negotiation!
  "Port of pi's setupStdinBuffer negotiation interception: while the Kitty
   protocol query is outstanding, hold response fragments in a separate
   buffer so the response is consumed (never dispatched as input). Returns
   :consumed / :pending / nil (not negotiation input — proceed normally).
   On nil the input buffer is left UNTOUCHED: blanking it would drop the
   fragment just examined (see process-input-buffer!)."
  [tui read-fn buf]
  (if-not @(:keyboard-protocol-pushed? tui)
    nil
    (let [held @(:negotiation-buffer tui)
          combined (str held @buf)
          parsed (terminal/parse-negotiation-sequence combined)]
      (cond
        parsed
        (do (clear-negotiation-timer! tui)
            (reset! (:negotiation-buffer tui) "")
            (reset! buf "")
            (terminal/handle-negotiation-sequence! @(:terminal tui) parsed)
            (tui-request-render tui)
            :consumed)

        (terminal/negotiation-prefix? combined)
        (do (reset! (:negotiation-buffer tui) combined)
            (reset! buf "")
            (schedule-negotiation-flush! tui read-fn buf)
            :pending)

        :else
        (do (when (seq held)
              (clear-negotiation-timer! tui)
              (reset! buf combined)
              (reset! (:negotiation-buffer tui) ""))
            nil)))))

;; ─── Terminal response interception (pi: consumeOsc11BackgroundResponse /
;;      consumeTerminalColorSchemeReport / consumeCellSizeResponse) ──────────

(defn- clear-terminal-response-timer!
  "Cancel a pending terminal-response flush timer."
  [tui]
  (when-let [t @(:terminal-response-timer tui)]
    (future-cancel t)
    (reset! (:terminal-response-timer tui) nil)))

(defn- schedule-terminal-response-flush!
  "Flush a held terminal-response fragment back into the input buffer after
   the flush timeout so it is never swallowed forever (mirrors the
   negotiation flush). Guarded by the input generation: only flush after
   true idleness, never while a response is still arriving."
  [tui read-fn buf]
  (when (and (nil? @(:terminal-response-timer tui))
             (seq @(:terminal-response-buffer tui)))
    (let [gen @(:input-generation tui)]
      (reset! (:terminal-response-timer tui)
              (future
                (try
                  (Thread/sleep terminal/NEGOTIATION-FLUSH-TIMEOUT-MS)
                  (when (and (seq @(:terminal-response-buffer tui))
                             (= gen @(:input-generation tui)))
                    (swap! buf str @(:terminal-response-buffer tui))
                    (reset! (:terminal-response-buffer tui) "")
                    (process-input-buffer! tui read-fn buf))
                  (catch Exception _))
                (reset! (:terminal-response-timer tui) nil))))))

(defn- settle-osc-11-query!
  "Settle the OSC 11 query identified by PROMISE with COLOR (nil on
   timeout). Drops already-settled queries; clears the pending flag before
   resolving so observers never see a stale flag after the promise settles."
  [tui color promise]
  (let [settled? (volatile! false)]
    (swap! (:osc-11-queries tui)
           (fn [qs]
             (if-let [q (first (filter #(identical? (:promise %) promise) qs))]
               (do (when (compare-and-set! (:settled? q) false true)
                     (future-cancel (:timer q))
                     (vreset! settled? true))
                   (vec (remove #(identical? (:promise %) promise) qs)))
               qs)))
    (when (empty? @(:osc-11-queries tui))
      (reset! (:pending-osc-11? tui) false))
    (when @settled?
      (deliver promise color))))

(defn- settle-osc-11-response!
  "Settle the oldest unsettled OSC 11 query with COLOR (pi: shift + resolve)."
  [tui color]
  (let [settled? (volatile! false)
        q (first (filter #(not @(:settled? %)) @(:osc-11-queries tui)))]
    (when q
      (when (compare-and-set! (:settled? q) false true)
        (future-cancel (:timer q))
        (vreset! settled? true))
      (swap! (:osc-11-queries tui)
             (fn [qs] (vec (remove #(identical? (:promise %) (:promise q)) qs)))))
    (when (empty? @(:osc-11-queries tui))
      (reset! (:pending-osc-11? tui) false))
    (when (and q @settled?)
      (deliver (:promise q) color))))

(defn- intercept-terminal-response!
  "Consume terminal query responses — cell size (\u001b[6;h;wt), OSC 11
   background color, color scheme report (\u001b[?997;Nn) — before key
   parsing; pi consumes them in handleInput before listeners. Cell size is
   ungated (pi: consumeCellSizeResponse has no pending query); OSC 11 is
   gated on an outstanding query. Returns :consumed (handled), :pending
   (fragment held), or nil (not a response). On nil the input buffer is left
   UNTOUCHED: blanking it would drop the fragment just examined (see
   process-input-buffer!)."
  [tui read-fn buf]
  (let [held @(:terminal-response-buffer tui)
        combined (str held @buf)
        cell-size (terminal/parse-cell-size-response combined)
        osc-11 (when @(:pending-osc-11? tui)
                 (terminal/parse-osc-11-background-response combined))
        scheme (terminal/parse-terminal-color-scheme-report combined)]
    (cond
      cell-size
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (img/set-cell-dimensions! cell-size)
          ;; Invalidate all components so images re-render at the new size
          ;; (pi: consumeCellSizeResponse — ungated, no pending query).
          (tui-invalidate tui)
          (tui-request-render tui)
          :consumed)

      osc-11
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (settle-osc-11-response! tui osc-11)
          :consumed)

      scheme
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (doseq [l @(:color-scheme-listeners tui)]
            (try (l scheme)
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "color scheme listener:" (ex-message e))))))
          :consumed)

      ;; Prefix hold — only when the fragment is UNAMBIGUOUSLY a response
      ;; head. A bare "\u001b[" or a lone "\u001b" is also an arrow-key /
      ;; modifyOtherKeys prefix: holding it while a stalled remainder is in
      ;; flight (WSL/conpty splits sequences 50ms+ apart) corrupts ctrl+arrow
      ;; and ctrl+letter keys into escapes + literal text. Only longer,
      ;; response-shaped fragments are held. (cell-size needs the ';' — a
      ;; bare "\u001b[6" also prefixes keys; same rule as
      ;; negotiation-prefix? for the kitty "\u001b[?" introducer.)
      (or (terminal/cell-size-response-prefix? combined)
          (and @(:pending-osc-11? tui) (terminal/osc-11-response-prefix? combined))
          (terminal/color-scheme-report-prefix? combined))
      (do (reset! (:terminal-response-buffer tui) combined)
          (reset! buf "")
          (schedule-terminal-response-flush! tui read-fn buf)
          :pending)

      :else
      (do (when (seq held)
            (clear-terminal-response-timer! tui)
            (reset! buf combined)
            (reset! (:terminal-response-buffer tui) ""))
          nil))))

(defn- dispatch-input!
  "Port of pi's TUI input routing: listeners first, the modality guard
   (input belongs to the topmost visible capturing overlay), then delivery
   to the focused component. Key release events are filtered unless the
   component opts in via a :wants-key-release? field (pi:
   Component.wantsKeyRelease). FLUSH-GEN is the input generation captured when
   a flush timer armed (nil for reader-path dispatches): a lone ESC armed by
   a stale timer is dropped when the generation moved on — input arrived after
   the arm, so the ESC is the head of a stalled key sequence (WSL/conpty
   splits sequences 50ms+ apart), never an Escape keypress. Checked at
   delivery, under dispatch-lock: a stale timer racing the reader's own pass
   must not corrupt the sequence even when it wins the lock."
  [tui data & [flush-gen]]
  ;; pi: input listeners run as a chain — each may :consume (stop dispatch)
  ;; or return transformed :data for the later listeners and the focused
  ;; component (pi: handleInput listener loop).
  (let [{:keys [data] :as chained}
        (reduce (fn [{:keys [data] :as acc} l]
                  (if (:consumed acc)
                    acc
                    (let [result (try (l data)
                                      (catch Exception e
                                        (binding [*out* *err*]
                                          (println "input listener error:" (ex-message e)))
                                        nil))]
                      (cond
                        (:consume result) (assoc acc :consumed true)
                        (map? result) (assoc acc :data (:data result data))
                        :else acc))))
                {:data data :consumed false}
                @(:input-listeners tui))]
    ;; pi: a listener chain that transforms data to an empty string drops
    ;; the event entirely. Everything below runs app/component code on the
    ;; reader thread - wrapped in Throwable so a buggy listener, component
    ;; or focus thunk costs one key event, never the input loop (an Error
    ;; escaping here would kill the reader future: keys dead, process
    ;; alive).
    (when-not (or (:consumed chained) (empty? data))
      (try
        ;; Modality invariant: while a visible capturing overlay exists,
        ;; input belongs to its component - whatever else grabbed focus
        ;; (including a hidden/removed overlay) hands it back before
        ;; delivery. Replaces pi's blocked/eligible restore machine.
        (if-let [cap (get-topmost-visible-overlay tui)]
          (when-not (identical? (:component cap) @(:focused-component tui))
            (tui-set-focus tui (:component cap)))
          (let [fc @(:focused-component tui)]
            (when-let [entry (some #(when (identical? (:component %) fc) %)
                                   @(:overlays tui))]
              (when-not (overlay-visible? tui entry)
                (tui-set-focus tui (overlay-restore-target tui entry))))))
        (when-let [fc @(:focused-component tui)]
          ;; pi: input goes only to the focused leaf; key release events are
          ;; filtered unless the component opts in via a :wants-key-release?
          ;; field (pi: Component.wantsKeyRelease)
          (when (or (not (keys/is-key-release? data))
                    (:wants-key-release? fc))
            ;; Stale-timer Escape guard (see docstring): a lone ESC whose
            ;; arming generation is stale is a split sequence's head whose
            ;; tail the reader owns — drop it so the tail dispatches whole.
            ;; Listeners still observe it (it is genuinely ambiguous at
            ;; dispatch time); only the focused component is shielded.
            (when-not (and (= data "\u001b")
                           (some? flush-gen)
                           (not= flush-gen @(:input-generation tui)))
              (handle-input fc data))))
        (tui-request-render tui)
        (catch Throwable e
          (binding [*out* *err*]
            (println "input dispatch:" (str (class e) ":"
                                            (ex-message e)))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input buffer (pi: stdin-buffer.ts)
;; ═══════════════════════════════════════════════════════════════════════════

;; pi: DEFAULT_SEQUENCE_TIMEOUT_MS — a partial CSI/OSC/mouse sequence gets
;; 50ms for its remainder. The earlier flat 10ms fired mid-sequence under
;; ordinary reader stalls (observed at 11-15ms on Android): the paste-marker
;; ESC was flushed as a phantom Escape keypress and "[200~" leaked in as
;; literal text, splitting every bracketed paste whose bytes crossed a stall.
(def ^:private SEQUENCE-FLUSH-MS 50)

;; Lone-ESC flush timeout. Pi uses 10ms here (DEFAULT_ESCAPE_TIMEOUT_MS), but
;; WSL/conpty stalls split escape sequences 50ms+ apart — ESC arrives alone
;; and the rest much later. A 10ms timeout consumes the ESC as an Escape key
;; before the tail arrives, orphaning the tail ("[27;5;97~" leaks as literal
;; text instead of ctrl+a). 100ms still feels instant for a genuine Escape
;; press and covers observed conpty stall gaps; tmux uses 500ms by default
;; (escape-time) for the same reason.
(def ^:private ESCAPE-FLUSH-MS 100)

;; Serializes input dispatch. pi's StdinBuffer timeouts run on the Node main
;; thread, so a flush can never interleave with the next key's processing;
;; here the flush futures are separate threads, and without this lock two
;; threads could be inside dispatch-input!/handle-input at once (e.g. the
;; reader inserting a pasted char while a stale timer dispatches Escape —
;; both racing the editor's read-modify-swap on its state atom).
(def ^:private dispatch-lock (Object.))

(defn- clear-incomplete-flush!
  "Cancel a pending incomplete-buffer flush timer."
  [tui]
  (when-let [t @(:incomplete-flush-timer tui)]
    (future-cancel t)
    (reset! (:incomplete-flush-timer tui) nil)))

(defn- dispatch-buffer!
  "Flush-timer dispatch (see schedule-incomplete-flush!).

   Dispatch input from BUF-ATOM as a single input sequence. The buffer is
   re-read under dispatch-lock and consumed only when dispatched: flush timers
   must never blank input another thread owns. A lone ESC always dispatches
   here (the caller holds dispatch-lock and re-checked idleness under it);
   the stale-timer guard lives at DELIVERY in dispatch-input!, which drops the
   ESC from the focused component when the arming generation went stale while
   the timer raced the lock — a split sequence's head (WSL/conpty stalls split
   sequences 50ms+ apart) whose tail the reader owns. Dispatching it to the
   component would corrupt the key (phantom Escape + literal-text leak, e.g.
   ctrl+a arriving as Esc then \"[27;5;97~\"). Consume only the lone ESC
   itself: a non-lone buffer is never consumed here (partial CSI/mouse
   prefixes stay buffered for the remainder; see schedule-incomplete-flush!).
   A complete dispatchable sequence always dispatches, and complete but
   unrecognized garbage is cleared here. Returns true when the buffer was
   consumed (dispatched OR garbage-cleared — the caller clears again,
   idempotently), false when the fragment stays pending. Buffers holding
   paste markers are never garbage-collected (the paste leg owns them)."
  [tui buf-atom armed-gen]
  (let [content @buf-atom]
    (when (seq content)
      (cond
        ;; Lone ESC — see docstring.
        (= content "\u001b")
        (do (reset! buf-atom "")
            (dispatch-input! tui content armed-gen)
            true)

        ;; A COMPLETE sequence (structurally finished AND recognized — the
        ;; structural check comes first so a partial CSI prefix that happens
        ;; to parse as alt+[ can never dispatch early and swallow the rest).
        (and (keys/complete-sequence? content)
             (or (keys/parse-key content)
                 (keys/mouse-sequence? content)
                 (keys/focus-sequence? content)))
        (do (reset! buf-atom "")
            (dispatch-input! tui content)
            true)

        ;; Paste markers are owned by the paste leg (pi emits paste content
        ;; outside the timeout path): never garbage-collect a buffer holding
        ;; them — hold for the reader instead.
        (or (clojure.string/includes? content PASTE-START)
            (clojure.string/includes? content PASTE-END))
        false

        ;; Complete but unrecognized ESC sequence — garbage. Clear it here
        ;; (the caller also clears on a true return — idempotent) so the
        ;; buffer can never grow and swallow subsequent input (see the ESC
        ;; branch of process-input-buffer!). complete-sequence? already
        ;; excludes partial CSI prefixes (a bare "\u001b[" parses as alt+[
        ;; but is structurally incomplete), so they can never take this
        ;; branch and corrupt a stalled sequence.
        (and (not= content "\u001b")
             (keys/complete-sequence? content))
        (do (reset! buf-atom "")
            true)

        :else false))))

(defn- schedule-incomplete-flush!
  "Port of pi's StdinBuffer timeout: when the buffer holds an incomplete
   ESC-prefixed sequence (e.g. a lone ESC or a partial CSI prefix), wait for
   the rest — ESCAPE-FLUSH-MS for a lone ESC, SEQUENCE-FLUSH-MS otherwise
   (pi: escapeTimeout/timeout). On expiry a lone ESC is dispatched as the
   Escape key and a complete sequence is dispatched; a partial CSI/mouse
   fragment stays buffered (it must not leak as text).
   The flush is GENERATION-guarded: it fires only when no input arrived while
   it slept. A stale content claim is racy — the buffer may transiently hold
   the armed fragment again while the reader owns it — so dispatch-buffer!
   re-reads and consumes under dispatch-lock instead. Re-armed on every
   accumulation. Unlike pi this timer runs on a separate thread, so it must
   never race the reader's own handling."
  [tui buf]
  ;; Only the LATEST arm owns the timer slot. A stale disowned timer that
  ;; wakes late must neither dispatch (generation guard) nor disturb the
  ;; fresh arm — so a fresh schedule cancels the previous timer, and a wakeup
  ;; clears the slot only when it still holds itself. Two timers can still
  ;; coexist briefly (fresh arm scheduled while a stale one sleeps); the
  ;; generation guard disowns all but the latest.
  (clear-incomplete-flush! tui)
  (when (seq @buf)
    (let [escape? (= @buf "\u001b")
          gen @(:input-generation tui)
          fut-box (atom nil)
          fut (future
                (try
                  (Thread/sleep (if escape? ESCAPE-FLUSH-MS SEQUENCE-FLUSH-MS))
                  ;; Generation check #1 (cheap): skip when input arrived
                  ;; while sleeping — the reader owns the buffer. The lone-ESC
                  ;; idleness check itself lives in dispatch-buffer!
                  ;; (generation check #2 under the lock), closing the race
                  ;; where input lands between this check and the dispatch.
                  ;; The return is honored (belt-and-braces: dispatch-buffer!
                  ;; already clears what it consumes, so this second clear
                  ;; is a no-op) so a consumed buffer can never stall
                  ;; until the next key.
                  (when (= gen @(:input-generation tui))
                    (locking dispatch-lock
                      ;; Re-check idleness UNDER the lock, then let
                      ;; dispatch-buffer! re-read + consume under this same
                      ;; lock, so the buffer can neither be stolen from the
                      ;; reader nor dispatched twice.
                      (when (= gen @(:input-generation tui)) (when (dispatch-buffer! tui buf gen) (reset! buf "")))))
                  (catch Exception _))
                (when (identical? @fut-box @(:incomplete-flush-timer tui))
                  (reset! (:incomplete-flush-timer tui) nil)))]
      (reset! fut-box fut)
      (reset! (:incomplete-flush-timer tui) fut))))

(defn- process-input-buffer!
  "Process buffered input. Dispatch is STRUCTURAL (pi: extractCompleteSequences):
   a sequence is dispatched only when complete — parse-key/mouse/focus matched
   AND structurally finished (complete-sequence?). Incomplete ESC-prefixed
   buffers stay in BUF and are flushed after SEQUENCE-FLUSH-MS / ESCAPE-FLUSH-MS
   if no further characters arrive, so a lone ESC still fires as the Escape key.
   READ-FN is accepted for the interception flush timers but never used for
   per-character waiting — the reader thread does all reading (JLine's timed
   reads race the NonBlockingReader pump and can lose characters).

   The whole pass runs under dispatch-lock: flush-timer futures re-enter this
   fn from their own threads (pi: single-threaded Node, no such interleaving),
   so without the lock a timer could process/dispatch a fragment concurrently
   with the reader's char — splitting sequences and racing the focused
   component's state updates."
  [tui read-fn buf]
  ;; Kitty protocol negotiation responses are intercepted first and never
  ;; reach the normal dispatch path (pi: setupStdinBuffer); terminal query
  ;; responses (cell size / OSC 11 / color scheme) are consumed next (pi:
  ;; handleInput consumes them before listeners). NOTE: interceptors that
  ;; hold a fragment MUST NOT reset BUF to "" on a nil return — the reader
  ;; appends to BUF across passes, and blanking it drops the fragment the
  ;; interceptor just examined (stalled split sequences then corrupt into
  ;; literal text). Only the interceptor that consumed or held the fragment
  ;; may clear it.
  (locking dispatch-lock
    (when (nil? (intercept-keyboard-negotiation! tui read-fn buf))
      (when (nil? (intercept-terminal-response! tui read-fn buf))
        (let [s @buf]
          (cond
            (empty? s) nil

            ;; Paste markers — dispatch in arrival order with surrounding
            ;; text (pi emits pre-marker sequences, then enters paste mode
            ;; with everything after the marker). Text before the marker is
            ;; re-processed first so it can never land inside the paste;
            ;; text after the marker is re-processed right after dispatching
            ;; it, so a paste sharing one batch is committed in the same
            ;; pass instead of waiting for the next keypress. A lone split
            ;; head (WSL/conpty stalls deliver "\u001b" alone) falls
            ;; through to the ESC branch and waits for its remainder there.
            (or (clojure.string/includes? s PASTE-START)
                (clojure.string/includes? s PASTE-END))
            (let [start-idx (clojure.string/index-of s PASTE-START)
                  end-idx (clojure.string/index-of s PASTE-END)
                  [marker idx] (cond
                                 (and (some? start-idx) (some? end-idx))
                                 (if (<= start-idx end-idx)
                                   [PASTE-START start-idx]
                                   [PASTE-END end-idx])
                                 (some? start-idx) [PASTE-START start-idx]
                                 (some? end-idx) [PASTE-END end-idx])
                  before (subs s 0 idx)
                  after (subs s (+ idx (count marker)))]
              ;; Text before the marker drains first so it can never land
              ;; inside the paste; the marker is then committed against an
              ;; empty buffer (a held partial CSI prefix there waits through
              ;; the ESC branch, never dispatching early). After dispatching
              ;; the marker, the text after it re-processes in the same pass
              ;; — a paste sharing one batch commits now instead of waiting
              ;; for the next keypress. When `before` is still waiting (a
              ;; held partial), the marker + after stay buffered behind it
              ;; so no marker overtakes a pending key. Each recursion
              ;; consumes a marker, so this terminates at the empty case.
              (reset! buf before)
              (when (seq @buf)
                (process-input-buffer! tui read-fn buf))
              (cond
                ;; A lone ESC ahead of a marker is a pressed Escape key (an
                ;; ESC that were a key head would carry its tail next, not
                ;; marker bytes) — deliver it now, then the marker, so a
                ;; fast Escape-then-paste batch can't starve behind the
                ;; hold guard below (the armed lone-ESC flush would
                ;; otherwise garbage-collect the combined buffer).
                (= @buf "\u001b")
                (do (clear-incomplete-flush! tui)
                    (reset! buf "")
                    (dispatch-input! tui "\u001b")
                    (dispatch-input! tui marker)
                    (reset! buf after)
                    (when (seq @buf)
                      (process-input-buffer! tui read-fn buf)))
                (seq @buf)
                (swap! buf str marker after)
                :else
                (do (dispatch-input! tui marker)
                    (swap! buf str after)
                    (when (seq @buf)
                      (process-input-buffer! tui read-fn buf)))))

          ;; ESC-prefixed: dispatch only complete sequences (pi: a complete
          ;; CSI/SS3/OSC/mouse sequence, or a meta key). The buffer may hold
          ;; MORE than one sequence (e.g. a printable run, then an arrow key,
          ;; then more text) — the LEADING complete sequence dispatches, the
          ;; remainder re-processes. Incomplete prefixes ("\u001b",
          ;; "\u001b[", "\u001b[<...") wait for more characters; the lone-ESC
          ;; case is flushed after ESCAPE-FLUSH-MS, other partial sequences
          ;; after SEQUENCE-FLUSH-MS. A COMPLETE leading sequence that nothing
          ;; recognizes (e.g. a terminal response the interceptors missed —
          ;; Termux's \u001b[?64;...c DA or a kitty push response in an
          ;; unparsed format) is garbage: dropping it prevents the buffer from
          ;; growing and swallowing all subsequent input ("kmet frozen, keys
          ;; dead, no crash log").
            (= (first s) \u001b)
            ;; Find the longest complete leading escape sequence by scanning
            ;; prefixes (pi: extractCompleteSequences). The buffer may hold
            ;; MORE than one thing — a run then an arrow key, or a sequence
            ;; then text — so only the LEADING complete sequence dispatches
            ;; and the remainder re-processes. A complete but unrecognized
            ;; leading sequence is garbage: dropping it prevents the buffer
            ;; from growing and swallowing all subsequent input ("kmet
            ;; frozen, keys dead, no crash log").
            (let [seq-len (loop [n 2]
                            (if (<= n (count s))
                              (if (keys/complete-sequence? (subs s 0 n))
                                n
                                (recur (inc n)))
                              nil))]
              (cond
                ;; lone ESC — wait for the escape timeout. The flush timer
                ;; dispatches it as Escape ONLY when the input generation is
                ;; still idle (no arrival while it slept): a lone ESC that a
                ;; stalled remainder (WSL/conpty splits sequences 50ms+
                ;; apart) turns into "\u001b[" or longer before the timer
                ;; fires is the head of a key sequence, never an Escape key.
                (= s "\u001b")
                (schedule-incomplete-flush! tui buf)

                ;; no complete leading sequence — hold for more input.
                (nil? seq-len)
                (schedule-incomplete-flush! tui buf)

                ;; complete leading sequence — the structural check ran
                ;; first, so a partial CSI prefix that happens to parse as
                ;; alt+[ can never take this branch and swallow the rest.
                ;; WezTerm kitty quirk (pi extractCompleteSequences): ESC
                ;; arrives as a raw press byte with the release as a full
                ;; CSI-u sequence ("\u001b\u001b[27;...u"). "\u001b\u001b"
                ;; alone parses as ctrl+alt+[ — split it so the CSI-u tail
                ;; survives when another ESC-prefixed sequence follows.
                :else
                (let [seq-str (if (and (clojure.string/starts-with? s "\u001b\u001b")
                                       (> (count s) 2)
                                       (contains? #{\[ \] \O \P \_} (nth s 2)))
                                "\u001b"
                                (subs s 0 seq-len))
                      rest-s (subs s (count seq-str))]
                  (if (or (keys/mouse-sequence? seq-str)
                          (keys/focus-sequence? seq-str)
                          (keys/parse-key seq-str))
                    ;; recognized sequence: dispatch it, re-process the rest
                    (do (reset! buf rest-s)
                        (dispatch-input! tui seq-str)
                        (when (seq rest-s)
                          (process-input-buffer! tui read-fn buf)))
                    ;; unrecognized complete sequence: drop the garbage and
                    ;; re-process any trailing text
                    (do (reset! buf rest-s)
                        (when (seq rest-s)
                          (process-input-buffer! tui read-fn buf)))))))

          ;; Non-ESC — dispatch printable runs in bulk.
            :else
            ;; Non-ESC — dispatch printable runs in bulk. A run is everything
            ;; up to the first control char (<= 31) or ESC (27): the editor
            ;; inserts the whole run at once, so a large paste is ONE insertion
            ;; instead of n per-char insertions into a growing buffer (O(n^2)
            ;; for 100K chars — measured ~0.64s per 50K). ESC ends the run so a
            ;; stalled sequence's tail (e.g. "[" after a consumed ESC head)
            ;; is never dispatched as literal text — it re-processes through
            ;; the ESC branch where it waits for its remainder. The remainder
            ;; is re-processed RECURSIVELY — never dispatched raw, or an ESC
            ;; sequence (arrow key, paste marker) after the run would be split
            ;; into literal text (the phantom-ESC class this buffer exists to
            ;; prevent).
            (let [brk (loop [i 0]
                        (when (< i (count s))
                          (let [c (int (.charAt ^String s i))]
                            (if (or (<= c 31) (= c 27))
                              i
                              (recur (inc i))))))
                  run-len (or brk (count s))
                  run (subs s 0 run-len)
                  rest-s (subs s run-len)]
              (when (seq run)
                (dispatch-input! tui run))
              (cond
                (empty? rest-s)
                (reset! buf "")

                ;; a leading ESC re-processes through the ESC branch (never
                ;; dispatched as text): it is either a complete sequence or a
                ;; stalled head waiting for its remainder. MUST NOT consume
                ;; it here — consuming the ESC then re-processing the tail
                ;; alone would leak the tail as literal text (WSL/conpty
                ;; split-sequence corruption).
                (= \u001b (first rest-s))
                (do (reset! buf rest-s)
                    (process-input-buffer! tui read-fn buf))

                ;; a leading control char dispatches alone (CR/LF/Tab need
                ;; per-char editor handling), then the remainder re-processes.
                ;; MUST consume the control: a buffer starting with a control
                ;; and followed by text (e.g. "\nhi") would otherwise recurse
                ;; with the identical buffer forever (run-len 0, rest-s
                ;; unchanged).
                (zero? run-len)
                (do (reset! buf (subs rest-s 1))
                    (dispatch-input! tui (subs rest-s 0 1))
                    (when (seq (subs rest-s 1))
                      (process-input-buffer! tui read-fn buf)))

                ;; exactly one control char after the run: dispatch it, done
                (= 1 (count rest-s))
                (do (reset! buf "")
                    (dispatch-input! tui rest-s))

                ;; multi-char remainder after the run: keep it buffered and
                ;; re-process (handles ESC sequences, paste markers, further
                ;; runs)
                :else
                (do (reset! buf rest-s)
                    (process-input-buffer! tui read-fn buf))))))))))

;; ─── Unbracketed paste detection (paste-like bursts) ────────────────────────
;; Bracketed paste (mode 2004) delivers paste content verbatim, but input
;; paths that bypass bracketing — Android IME text injection, terminals
;; without bracketed-paste support, tmux send-keys — deliver the raw bytes as
;; ordinary key events. A paste line ending then arrives as a lone CR and the
;; editor submits, executing pasted "/cmd" or "!cmd" text without the user
;; pressing Enter. Human typing cannot reach paste speed, so a CR that ends a
;; paste-like burst (several chars within a few ms) is rewritten to \n; only
;; an isolated CR (a real Enter press) keeps submitting. The rewritten CR's
;; LF half (CRLF line endings) is then swallowed so a \r\n pair still
;; produces a single newline (matching normalize-paste-text).

(def ^:private paste-burst-ms 100)
(def ^:private paste-burst-chars 4)
(def ^:private paste-lf-swallow-ms 50)

(defn- cr-in-paste-burst?
  "True when the CR arriving at NOW ends an unbracketed paste: at least
   PASTE-BURST-CHARS chars (the CR included) arrived within the last
   PASTE-BURST-MS and at least one of them is not itself a CR — an Enter key
   repeat stream of CRs must keep submitting. RECENT is a vector of
   [timestamp char] pairs including the current char."
  [recent now]
  (let [in-window (filter (fn [[ts _]] (>= ts (- now paste-burst-ms))) recent)]
    (boolean (and (>= (count in-window) paste-burst-chars)
                  (some #(not= \return (second %)) in-window)))))

(defn- paste-input-decision
  "Decide how to process the input char C arriving at NOW. RECENT holds
   [timestamp char] pairs of previously read chars, SWALLOW-LF the timestamp
   of a recently rewritten paste CR (nil when none). Returns a map with
   :append (the char to buffer), :drop (true when the char is swallowed — the
   LF half of a rewritten CRLF), and :new-swallow-lf (the flag value to keep)."
  [c now recent swallow-lf]
  (cond
    (and (= c \return) (cr-in-paste-burst? recent now))
    {:append "\n" :new-swallow-lf now}

    (and (= c \newline) swallow-lf (<= (- now swallow-lf) paste-lf-swallow-ms))
    {:drop true :new-swallow-lf nil}

    :else
    {:append (str c) :new-swallow-lf nil}))

(defn- normalize-input-batch!
  "Apply the paste-burst decision to a whole drained BATCH at once: returns
   the string to append to the input buffer (CRs that end a paste-like burst
   rewritten to \n; the LF half of a rewritten CRLF dropped). RECENT-CHARS
   and SWALLOW-LF are updated as a side effect, mirroring the per-char loop
   they replaced. Doing this once per batch instead of per char keeps large
   pastes O(n) — the per-char path appended to a growing string (O(n^2))."
  [batch recent-chars swallow-lf]
  (let [out (StringBuilder.)
        now (System/currentTimeMillis)]
    (doseq [c batch]
      (let [{:keys [append drop new-swallow-lf]}
            (paste-input-decision c now @recent-chars @swallow-lf)]
        (reset! swallow-lf new-swallow-lf)
        (swap! recent-chars
               (fn [ts]
                 (-> (conj ts [now c])
                     (->> (filter (fn [[t _]] (>= t (- now paste-burst-ms)))))
                     vec)))
        (when-not drop
          (.append out append))))
    (str out)))

(defn- start-input-reader [tui]
  (let [term @(:terminal tui)
        read-fn (fn [timeout-ms] (terminal/read-input term timeout-ms))]
    ;; Track the current terminal so a stale reader (from a suspended TUI
    ;; session) exits as soon as a fresh backend is installed by resume.
    (reset! (:current-reader tui) term)
    (let [f (future
              (let [buf (atom "")
                    ;; [timestamp char] pairs of recently read chars, pruned
                    ;; to the paste-burst window on every read so the vector
                    ;; stays bounded; swallow-lf remembers a rewritten paste
                    ;; CR whose LF half may still arrive.
                    recent-chars (atom [])
                    swallow-lf (atom nil)]
                (while (and @(:running? tui) (not @(:stopped? tui))
                            (identical? term @(:current-reader tui)))
                  (try
                    ;; Bounded read: data arrives immediately, but the idle
                    ;; timeout lets the loop re-check the stop conditions and
                    ;; lets the backend's close/restore proceed promptly. A
                    ;; blocking read here deadlocks JLine's FFM terminal close
                    ;; on aarch64 Linux (jline3 #1909 — close waits for the
                    ;; in-flight read; the FFM pump thread never wakes it); the
                    ;; native backend's poll(read) honors the same bound.
                    (let [ch (terminal/read-input term 100)]
                      (when (>= ch 0)
                        ;; Drain everything ALREADY QUEUED behind the first
                        ;; char (pi: stdin 'data' events deliver chunks, not
                        ;; single bytes). Feeding one byte per pass exposed
                        ;; every multi-byte sequence to a scheduling stall at
                        ;; each byte boundary — a >10ms stall between ESC and
                        ;; "[" of a paste marker flushed a phantom Escape and
                        ;; leaked the rest as literal text. Draining keeps a
                        ;; burst (escape sequence, bracketed paste, fast
                        ;; typing) in ONE process pass; the 1ms drain read only
                        ;; picks up bytes that are already queued, so idle
                        ;; latency is unaffected. A multibyte UTF-8 char whose
                        ;; second half is still in flight yields -2 here and
                        ;; simply finishes on the next loop iteration.
                        (let [batch (loop [acc (doto (StringBuilder.)
                                                 (.append (char ch)))]
                                      (let [more (terminal/read-input term 1)]
                                        (if (>= more 0)
                                          (recur (.append acc (char more)))
                                          (str acc))))]
                          ;; Normalize the whole batch once (paste-burst CR
                          ;; rewriting / LF swallowing), then a SINGLE append
                          ;; + process pass. Per-char processing made large
                          ;; pastes O(n^2): n appends to a growing buffer, n
                          ;; process passes each copying the remainder. An
                          ;; exception during processing still loses the
                          ;; remaining chars of the batch (they were already
                          ;; consumed from the reader) — the per-char try
                          ;; guard from the drain fix is kept around the
                          ;; process call.
                          (try
                            (let [append (normalize-input-batch! batch
                                                                 recent-chars
                                                                 swallow-lf)]
                              (when (seq append)
                                ;; Every read bumps the generation — including
                                ;; reads that dispatch immediately. The flush
                                ;; timers (incomplete/negotiation/terminal-
                                ;; response) may only fire after true input
                                ;; idleness: any arrival proves the remainder
                                ;; is still in flight (WSL/conpty stalls split
                                ;; sequences 50ms+ apart), so a stale timer
                                ;; must never dispatch a lone ESC early and
                                ;; corrupt the sequence into literal text.
                                ;; Bump BEFORE processing: the pass below arms
                                ;; the fresh flush timer with this generation,
                                ;; which disowns any stale timer still sleeping
                                ;; (it can neither dispatch nor clear the fresh
                                ;; arm — the wakeup only clears its own slot).
                                (swap! (:input-generation tui) inc)
                                (swap! buf str append)
                                (process-input-buffer! tui read-fn buf)))
                            (catch Exception e
                              (binding [*out* *err*]
                                (println "input:" (ex-message e))))))))
                    (catch Exception e
                      (when (and @(:running? tui)
                                 (identical? term @(:current-reader tui)))
                        (binding [*out* *err*]
                          (println "input:" (ex-message e)))))))))]
      (reset! (:input-reader tui) f))))
;; ═══════════════════════════════════════════════════════════════════════════
;; Start / Stop
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-request-render
  "Request a render on the next frame. With FORCE (pi: requestRender(force)),
   the next frame is a clearing full redraw regardless of diffing: the flag is
   taken by the render loop, which clears its own previous-frame state. Doing
   that reset here would race the loop's state reads (prev=[] with an old
   width reads as a non-clearing first render), and a force arriving
   mid-frame would be overwritten by the in-flight frame's state write."
  [tui & [force]]
  (when force
    (reset! (:force-redraw? tui) true))
  (reset! (:render-requested? tui) true))

(defn tui-stop [tui]
  (reset! (:stopped? tui) true)
  (reset! (:running? tui) false)
  ;; Detach the §3.4 scheduler hook before anything else unwinds: disposed
  ;; components' reactions may still fire watches during teardown.
  (macros/set-frame-hook! nil)
  ;; §6.1: no timer outlives the session it was armed in. Components may
  ;; still cancel their own ids afterwards — cancel! is idempotent.
  (timers/cancel-all!)
  ;; pi: TuiAltScreen.dispose() clears pending flashes on close
  (tui-flash-dispose! tui))

(defn tui-invalidate
  "Invalidate all components and overlays (pi: TUI.invalidate — used after
   cell-size changes so images re-render at the new dimensions)."
  [tui]
  (doseq [c @(:components tui)] (protocols/invalidate c))
  (doseq [o @(:overlays tui)] (protocols/invalidate (:component o))))

(defn tui-get-clear-on-shrink [tui] @(:clear-on-shrink? tui))

(defn tui-set-clear-on-shrink!
  "Enable/disable clearing empty rows when content shrinks (pi:
   setClearOnShrink)."
  [tui enabled?]
  (reset! (:clear-on-shrink? tui) enabled?))

(defn tui-get-full-redraw-count
  "Number of full redraws performed (pi: getFullRedrawCount)."
  [tui]
  @(:full-redraw-count tui))

(defn tui-scrollback-dirty?
  "True when a change above the visible window was left un-repainted since
   the last clearing full redraw — the terminal scrollback holds stale (or
   misaligned) lines above the window. Heal it at a streaming-free boundary
   with tui-heal-scrollback!. A TUI without dirty tracking (test stubs) is
   clean."
  [tui]
  (boolean (some-> (:scrollback-dirty? tui) deref)))

(defn tui-heal-scrollback!
  "Rebuild the scrollback when it is dirty (tui-scrollback-dirty?): request
   a clearing full redraw (ESC[3J + re-emit), which also clears the dirty
   flag. Call only at a boundary where nothing is streaming and the viewport
   is assumed to be at the document end (in kmet: at the end of a turn,
   `on-agent-done` / `on-agent-error`, plus idle input), so the scrollback
   clear's viewport jump lands on a screen transition instead of mid-stream.
   No-op when clean. Returns nil."
  [tui]
  (when (tui-scrollback-dirty? tui)
    (tui-request-render tui true)))

(defn tui-query-terminal-background-color
  "Query the terminal's default background color via OSC 11; returns a
   promise resolving to {:r :g :b} or nil on timeout (pi:
   queryTerminalBackgroundColor)."
  [tui & {:keys [timeout-ms] :or {timeout-ms 500}}]
  (let [p (promise)]
    (swap! (:osc-11-queries tui)
           conj {:settled? (atom false)
                 :promise p
                 :timer (future
                          (Thread/sleep timeout-ms)
                          (settle-osc-11-query! tui nil p))})
    (reset! (:pending-osc-11? tui) true)
    (when-let [term @(:terminal tui)]
      (terminal/query-osc-11-background! term))
    p))

(defn tui-on-terminal-color-scheme-change
  "Register a listener called with :dark/:light on terminal color scheme
   reports (CSI ? 997 n). Returns an unsubscribe fn (pi:
   onTerminalColorSchemeChange)."
  [tui f]
  (swap! (:color-scheme-listeners tui) conj f)
  (fn [] (swap! (:color-scheme-listeners tui) disj f)))

(defn tui-query-terminal-color-scheme
  "Query the terminal color scheme preference (CSI ? 996 n); returns a
   promise resolving to :dark / :light or nil on timeout (pi:
   queryTerminalColorScheme)."
  [tui & {:keys [timeout-ms] :or {timeout-ms 500}}]
  (let [p (promise)
        unsub (tui-on-terminal-color-scheme-change
               tui (fn [scheme]
                     (when-not (realized? p)
                       (deliver p scheme))))]
    (future
      (Thread/sleep timeout-ms)
      (deliver p nil)
      (unsub))
    (when-let [term @(:terminal tui)]
      (terminal/query-color-scheme! term))
    p))

(defn tui-set-terminal-color-scheme-notifications
  "Enable/disable unsolicited terminal color scheme reports (CSI ? 2031 h/l),
   written immediately when the TUI is running (pi:
   setTerminalColorSchemeNotifications)."
  [tui enabled?]
  (reset! (:color-scheme-notifications-enabled? tui) enabled?)
  (when @(:running? tui)
    (when-let [term @(:terminal tui)]
      (terminal/set-color-scheme-notifications! term enabled?))))

(defn- run-render-loop!
  "Start the terminal (raw mode) and run the render loop until the TUI is
   suspended (tui-suspend!) or stopped (tui-stop). Restores and closes the
   terminal on exit; tui-resume! creates a fresh one."
  [tui]
  ;; Activate the negotiation interception BEFORE the terminal starts: the
  ;; reader thread may process the terminal's response to JLine's own DA
  ;; query (written inside start!) before the query below is sent — the flag
  ;; must already be set or those bytes would be parsed as keys (pi sets
  ;; keyboardProtocolPushed before writing the query).
  (reset! (:keyboard-protocol-pushed? tui) true)
  (reset! (:negotiation-buffer tui) "")
  (clear-negotiation-timer! tui)
  (let [started (terminal/start! @(:terminal tui)
                                 (fn [_] nil)
                                 (fn [] (tui-request-render tui)))
        hardware-cursor-row (atom 0)
        previous-viewport-top (atom 0)]
    ;; Make the started record (with the live writer) visible to the input
    ;; path: negotiation / OSC 11 / color-scheme handlers write through
    ;; @(:terminal tui), and the unstarted record's writer is nil — writes
    ;; would be silently dropped.
    (reset! (:terminal tui) started)
    (terminal/hide-cursor! started)
    ;; Pi: no clear-screen on start — preserves prior terminal output above the TUI
    (tui-request-render tui)
    ;; Kitty keyboard protocol negotiation (pi: queryAndEnableKittyProtocol) —
    ;; responses are intercepted by the input reader; modifyOtherKeys is the
    ;; fallback for terminals without Kitty support. The flag was set before
    ;; start! so even JLine's own DA response is intercepted.
    (terminal/query-kitty-protocol! started)
    (when @(:color-scheme-notifications-enabled? tui)
      (terminal/set-color-scheme-notifications! started true))
    ;; Query the cell size (CSI 16 t) when the terminal supports images — the
    ;; response is consumed ungated by the input path (pi: queryCellSize /
    ;; consumeCellSizeResponse).
    (when (:images (img/get-capabilities))
      (terminal/query-cell-size! started))
    (try
      (loop []
        (when @(:running? tui)
          (let [w (terminal/columns started)
                h (terminal/rows started)]
            ;; Loop-owned timers (tui.md §6.1): fire whatever is due
            ;; BEFORE the flush, so an atom a thunk just mutated is brought
            ;; current in this same tick. Thunks run here, on the loop
            ;; thread — the only thread allowed to touch widgets.
            (timers/pump!)
            ;; Frame flush: drain the reaction batch queue (kmet.libs.reakt)
            ;; at the loop's ~16ms cadence — Reagent's animation-frame
            ;; batching. A no-op while nothing is queued (headless tests, no
            ;; reactions in use yet); dirty reactions brought current here
            ;; invalidate their subscribers' caches before the render gate
            ;; below reads them.
            (reakt/flush!)
            ;; Terminal resize detection. The JLine backend's native WINCH
            ;; handling does not work under babashka's GraalVM native image
            ;; (no native signal handlers are registered), so its on-resize
            ;; callback is never invoked and nothing re-renders on resize —
            ;; the editor keeps wrapping at the pre-resize width until the
            ;; next input event (pi: terminal.on("resize") → requestRender).
            ;; columns/rows are live backend queries, so polling them here
            ;; (16ms cadence) catches the change reliably; the existing
            ;; width-changed/height-changed logic then does the full redraw.
            (when (and (pos? @(:previous-width tui)) (not= @(:previous-width tui) w))
              (tui-request-render tui))
            (when (and (pos? @(:previous-height tui)) (not= @(:previous-height tui) h))
              (tui-request-render tui))
            (when @(:render-requested? tui)
              (reset! (:render-requested? tui) false)
              ;; Forced render (tui-request-render with force: tui-resume!,
              ;; the scrollback heal): clear the previous-frame state here, on
              ;; the loop thread, so it cannot tear against this frame's reads.
              (when @(:force-redraw? tui)
                (reset! (:force-redraw? tui) false)
                (reset! (:previous-lines tui) [])
                (reset! (:previous-width tui) -1)
                (reset! (:previous-height tui) -1)
                (reset! (:max-lines-rendered tui) 0)
                (reset! (:previous-kitty-image-ids tui) #{}))
              ;; Base content: the whole UI is one flat document — the stack
              ;; layout renders every component at natural height, so the total
              ;; may exceed the screen and the render loop scrolls the overflow
              ;; into the native terminal scrollback (pi: main-screen model).
              ;; Visible overlays are then composited on top (pi: compositeOverlays).
              (let [base-lines (stack/render-stack @(:components tui) w)
                    raw-lines (composite-overlays tui base-lines w h)
                    cursor-result (extract-cursor-position raw-lines h)
                    cursor (:cursor cursor-result)
                    lines (:lines cursor-result)
                    ;; pi: normalizeTerminalOutput — Thai/Lao AM decomposition +
                    ;; tab expansion — runs before applyLineResets (pi
                    ;; normalizes in applyLineResets, after cursor extraction).
                    ;; Lines are written at their natural width like pi (no
                    ;; global padding): the diff's \x1b[2K clears each rewritten
                    ;; line before the write, so full-width padding is not
                    ;; needed for clean rewrites — and it actively broke
                    ;; kitty-image-reserved-rows, whose blank-row walk must run
                    ;; on raw unpadded lines (padded spaces terminated it
                    ;; immediately, collapsing every image block to one row).
                    lines (mapv utils/normalize-terminal-output lines)
                    lines (composite-flashes @(:flashes tui) lines w h)
                    ;; pi: applyLineResets — every non-image line ends with a
                    ;; full SGR + OSC 8 reset (SEGMENT_RESET) so a truncated
                    ;; line can never leave active attributes or an open
                    ;; hyperlink bleeding into the next line. It is applied at
                    ;; emit time (emit-line!) rather than to the whole LINES
                    ;; vector: rebuilding every line's string each frame would
                    ;; defeat the diff scan's identical? fast path (unchanged
                    ;; lines stay the same objects that the component caches
                    ;; return).
                    prev @(:previous-lines tui)
                    prev-w @(:previous-width tui)
                    prev-h @(:previous-height tui)
                    prev-count (count prev)
                    new-count (count lines)
                    width-changed (and (not (zero? prev-w)) (not= prev-w w))
                    height-changed (and (not (zero? prev-h)) (not= prev-h h))
                    termux? (boolean (System/getenv "TERMUX_VERSION"))
                    first-render? (and (empty? prev)
                                       (not width-changed)
                                       (not height-changed))
                    ;; One write per frame: the whole frame's output (sync markers,
                    ;; cursor moves, line rewrites, cursor hide) accumulates into SB
                    ;; and is written+flushed once (pi: one write per render).
                    ;; Pi-state: the viewport top persists across frames
                    prev-buffer-length (if (pos? prev-h) (+ @previous-viewport-top prev-h) h)
                    prev-viewport-top (atom (if height-changed
                                              (max 0 (- prev-buffer-length h))
                                              @previous-viewport-top))
                    viewport-top (atom @prev-viewport-top)
                    ;; The frame buffer is an atom so a mid-diff full-redraw
                    ;; fallback can discard the partial diff output (pi: the
                    ;; diff buffer is a local that fullRender replaces).
                    sb (atom (StringBuilder.))
                    emit! (fn [s] (.append @sb s))
                    emit-line! (fn [line]
                                 (emit! (if (img/is-image-line line)
                                          line
                                          (str line utils/SEGMENT-RESET))))
                    debug-redraw? @(:debug-redraw? tui)
                    log-redraw! (fn [reason]
                                  (when debug-redraw?
                                    (append-log!
                                     (log-path "kmet-debug-render.log")
                                     (str "[" (java.time.LocalDateTime/now) "] fullRender: "
                                          reason " (prev=" prev-count ", new=" new-count
                                          ", height=" h ")\n"))))
                    compute-line-diff (fn [target-row]
                                        (- (- target-row @viewport-top)
                                           (- @hardware-cursor-row @prev-viewport-top)))
                    position-hardware-cursor (fn [cursor-pos total-lines]
                                               (if (or (nil? cursor-pos) (<= total-lines 0))
                                                 (emit! "\u001b[?25l")
                                                 (let [target-row (max 0 (min (:row cursor-pos) (dec total-lines)))
                                                       target-col (max 0 (:col cursor-pos))
                                                       row-delta (- target-row @hardware-cursor-row)
                                                       buf (str (cond
                                                                  (pos? row-delta) (str "\u001b[" row-delta "B")
                                                                  (neg? row-delta) (str "\u001b[" (- row-delta) "A")
                                                                  :else "")
                                                                "\u001b[" (inc target-col) "G")]
                                                   (when (seq buf) (emit! buf))
                                                   (reset! hardware-cursor-row target-row)
                                                   (if @(:show-hardware-cursor? tui)
                                                     (emit! "\u001b[?25h")
                                                     (emit! "\u001b[?25l")))))
                    do-full-redraw (fn do-full-redraw [clear?]
                                     (swap! (:full-redraw-count tui) inc)
                                     (emit! CSI-2026-H)
                                     (when clear?
                                       (reset! (:scrollback-dirty? tui) false)
                                       (doseq [id @(:previous-kitty-image-ids tui)]
                                         (emit! (img/delete-kitty-image id)))
                                       ;; The full redraw re-emits the whole
                                       ;; transcript below, so the scrollback MUST
                                       ;; be cleared too: 2J alone leaves the old
                                       ;; transcript in the scrollback and the
                                       ;; re-emit appends after it, duplicating
                                       ;; every line (pi issue #6050: "without
                                       ;; clearing scrollback the whole history
                                       ;; duplicates"). Windows Terminal scrolls
                                       ;; to the top on 3J (microsoft/terminal
                                       ;; #20370, being fixed upstream) — pi
                                       ;; accepts that over duplicated output.
                                       (emit! "\u001b[2J\u001b[H\u001b[3J"))
                                     (loop [i 0]
                                       (when (< i new-count)
                                         (when (pos? i) (emit! "\r\n"))
                                         (let [rows (if (img/is-image-line (nth lines i))
                                                      (kitty-image-reserved-rows lines i)
                                                      1)]
                                           (if (and (> rows 1) (<= rows h))
                                             (do (dotimes [_ (dec rows)] (emit! "\r\n"))
                                                 (emit! (str "\u001b[" (dec rows) "A"))
                                                 (emit-line! (nth lines i))
                                                 (emit! (str "\u001b[" (dec rows) "B"))
                                                 (recur (+ i rows)))
                                             (do (emit-line! (nth lines i))
                                                 (recur (inc i)))))))
                                     (emit! CSI-2026-L)
                                     (reset! hardware-cursor-row (max 0 (dec new-count)))
                                     (reset! viewport-top (max 0 (- (max h new-count) h)))
                                     (if clear?
                                       (reset! (:max-lines-rendered tui) new-count)
                                       (swap! (:max-lines-rendered tui) max new-count))
                                     (position-hardware-cursor cursor new-count))
                    main-diff (fn main-diff []
                                (let [max-lines (max new-count prev-count)
                                      [first-changed last-changed]
                                      (loop [i 0, fc -1, lc -1]
                                        (if (< i max-lines)
                                          (let [old-line (if (< i prev-count) (nth prev i) "")
                                                new-line (if (< i new-count) (nth lines i) "")]
                                            ;; identical? first: unchanged lines are the
                                            ;; same objects the component caches returned
                                            ;; (nothing rewrites them per frame), so the
                                            ;; common case is an O(1) pointer compare
                                            ;; instead of a full string =
                                            (if (or (identical? old-line new-line)
                                                    (= old-line new-line))
                                              (recur (inc i) fc lc)
                                              (recur (inc i) (if (neg? fc) i fc) i)))
                                          [fc lc]))
                                      appended? (> new-count prev-count)
                                      [first-changed last-changed]
                                      (if appended?
                                        [(if (neg? first-changed) prev-count first-changed)
                                         (dec new-count)]
                                        [first-changed last-changed])
                                      [first-changed last-changed]
                                      (if (not (neg? first-changed))
                                        (expand-changed-range-for-kitty-images
                                         first-changed last-changed prev lines)
                                        [first-changed last-changed])
                                      append-start? (and appended?
                                                         (= first-changed prev-count)
                                                         (pos? first-changed))
                                      ;; A terminal has no addressable scrollback: a
                                      ;; change that starts above the window can only be
                                      ;; repainted from the window top, and the stale
                                      ;; scrollback lines above it stay as they are. Doing
                                      ;; that instead of the destructive full redraw keeps
                                      ;; ESC[3J out of the streaming path — Termux and
                                      ;; Windows Terminal yank the viewport to the top when
                                      ;; it is scrolled up and a clear scrollback arrives
                                      ;; (microsoft/terminal#20370; pi #4506/#6502). A
                                      ;; change *entirely* above the window has nothing
                                      ;; visible to repaint at all. Only the same-height
                                      ;; and growing cases take this path; a shrink that
                                      ;; starts above the window keeps the full-redraw
                                      ;; fallback (the extra-line cleanup in the diff
                                      ;; renderer assumes the change began inside the
                                      ;; window).
                                      scrollback-only-change? (and (not (neg? first-changed))
                                                                   (= new-count prev-count)
                                                                   (< first-changed @prev-viewport-top)
                                                                   (< last-changed @prev-viewport-top)
                                                                   (>= new-count @prev-viewport-top))
                                      viewport-clamp? (and (not scrollback-only-change?)
                                                           (not (neg? first-changed))
                                                           (< first-changed @prev-viewport-top)
                                                           (>= new-count prev-count)
                                                           (>= new-count @prev-viewport-top))
                                      first-changed (if viewport-clamp? @prev-viewport-top first-changed)
                                      ;; Both paths below leave the changed lines above
                                      ;; the window un-repainted (the terminal has no
                                      ;; addressable scrollback), so the history is stale
                                      ;; until a clearing full redraw. Record it; the app
                                      ;; heals at a streaming-free boundary
                                      ;; (tui-heal-scrollback!).
                                      _ (when (or scrollback-only-change? viewport-clamp?)
                                          (reset! (:scrollback-dirty? tui) true))
                                      mid-full-redraw! (fn [reason]
                                                         (log-redraw! reason)
                                                         (reset! sb (StringBuilder.))
                                                         (do-full-redraw true))]
                                  (cond
                                    (neg? first-changed)
                                    (do (position-hardware-cursor cursor new-count)
                                        (reset! viewport-top @prev-viewport-top))

                                    scrollback-only-change?
                                    (do (log-redraw! (str "firstChanged < viewportTop, scrollback only ("
                                                          last-changed " < " @prev-viewport-top
                                                          "), no visible change"))
                                        (reset! viewport-top @prev-viewport-top))

                                    (>= first-changed new-count)
                                    (if (> prev-count new-count)
                                      (let [target-row (max 0 (dec new-count))]
                                        (if (< target-row @prev-viewport-top)
                                          (mid-full-redraw! (str "deleted lines moved viewport up ("
                                                                 target-row " < " @prev-viewport-top ")"))
                                          (let [line-diff (compute-line-diff target-row)
                                                extra-lines (- prev-count new-count)]
                                            (if (> extra-lines h)
                                              (mid-full-redraw! (str "extraLines > height ("
                                                                     extra-lines " > " h ")"))
                                              (let [clear-start-offset (if (zero? new-count) 0 1)
                                                    move-back (max 0 (- (+ extra-lines clear-start-offset) 1))]
                                                (emit! CSI-2026-H)
                                                (emit! (delete-changed-kitty-images
                                                        first-changed last-changed prev))
                                                (when (pos? line-diff)
                                                  (emit! (str "\u001b[" line-diff "B")))
                                                (when (neg? line-diff)
                                                  (emit! (str "\u001b[" (- line-diff) "A")))
                                                (emit! "\r")
                                                (when (and (pos? extra-lines) (pos? clear-start-offset))
                                                  (emit! (str "\u001b[" clear-start-offset "B")))
                                                (dotimes [i extra-lines]
                                                  (emit! "\r\u001b[2K")
                                                  (when (< i (dec extra-lines))
                                                    (emit! "\u001b[1B")))
                                                (when (pos? move-back)
                                                  (emit! (str "\u001b[" move-back "A")))
                                                (emit! CSI-2026-L)
                                                (reset! hardware-cursor-row target-row)
                                                (position-hardware-cursor cursor new-count)
                                                (reset! viewport-top @prev-viewport-top))))))
                                      (do (position-hardware-cursor cursor new-count)
                                          (reset! viewport-top @prev-viewport-top)))

                                    ;; Reached by any shrink (new content shorter than the
                                    ;; old) whose first change is above the window — the
                                    ;; diff renderer's extra-line cleanup assumes the
                                    ;; change began inside the window, so rebuild the screen
                                    ;; (e.g. compaction replacing the transcript).
                                    (< first-changed @prev-viewport-top)
                                    (mid-full-redraw! (str "firstChanged < viewportTop ("
                                                           first-changed " < " @prev-viewport-top ")"))

                                    :else
                                    (let [prev-viewport-bottom (+ @prev-viewport-top h -1)
                                          move-target-row (if append-start? (dec first-changed) first-changed)]
                                      (when (> move-target-row prev-viewport-bottom)
                                        (let [current-screen-row (max 0 (min (dec h)
                                                                             (- @hardware-cursor-row @prev-viewport-top)))
                                              move-to-bottom (- (dec h) current-screen-row)
                                              scroll (- move-target-row prev-viewport-bottom)]
                                          (when (pos? move-to-bottom)
                                            (emit! (str "\u001b[" move-to-bottom "B")))
                                          (emit! (apply str (repeat scroll "\r\n")))
                                          (swap! prev-viewport-top + scroll)
                                          (reset! viewport-top @prev-viewport-top)
                                          (reset! hardware-cursor-row move-target-row)))
                                      (let [line-diff (compute-line-diff move-target-row)
                                            render-end (min last-changed (dec new-count))
                                            final-cursor-row (volatile! render-end)]
                                        (emit! CSI-2026-H)
                                        (emit! (delete-changed-kitty-images
                                                first-changed last-changed prev))
                                        (when (pos? line-diff)
                                          (emit! (str "\u001b[" line-diff "B")))
                                        (when (neg? line-diff)
                                          (emit! (str "\u001b[" (- line-diff) "A")))
                                        (emit! (if append-start? "\r\n" "\r"))
                                        (loop [i first-changed]
                                          (when (<= i render-end)
                                            (when (> i first-changed) (emit! "\r\n"))
                                            (let [line (nth lines i)
                                                  is-image (img/is-image-line line)
                                                  rows (if is-image
                                                         (kitty-image-reserved-rows lines i render-end)
                                                         1)]
                                              (if (> rows 1)
                                                (let [image-start-screen-row (- i @viewport-top)]
                                                  (if (or (< image-start-screen-row 0)
                                                          (> (+ image-start-screen-row rows) h))
                                                    (mid-full-redraw! (str "kitty image pre-clear would scroll ("
                                                                           image-start-screen-row " + " rows " > " h ")"))
                                                    (do (emit! "\u001b[2K")
                                                        (dotimes [_ (dec rows)] (emit! "\r\n\u001b[2K"))
                                                        (emit! (str "\u001b[" (dec rows) "A"))
                                                        (emit-line! line)
                                                        (emit! (str "\u001b[" (dec rows) "B"))
                                                        (recur (+ i rows)))))
                                                (do (emit! "\u001b[2K")
                                                    ;; A line wider than the terminal must NEVER kill the app — that
                                                    ;; leaves a frozen frame on screen and a dead reader ("fully
                                                    ;; stuck, had to kill it from the OS"). Log it and truncate the
                                                    ;; line instead so the TUI keeps running; the ANSI-aware slice
                                                    ;; drops the pending SGR/OSC reset, so emit-line! re-appends it.
                                                    (let [line (if (and (not is-image)
                                                                        (> (utils/visible-width line) w))
                                                                 (do (write-crash-log! lines w i (utils/visible-width line))
                                                                     (:text (utils/slice-with-width line 0 w :strict? true)))
                                                                 line)]
                                                      (emit-line! line)
                                                      (recur (inc i))))))))
                                        (when (> prev-count new-count)
                                          (when (< render-end (dec new-count))
                                            (emit! (str "\u001b[" (- (dec new-count) render-end) "B"))
                                            (vreset! final-cursor-row (dec new-count)))
                                          (let [extra-lines (- prev-count new-count)]
                                            (dotimes [_ extra-lines]
                                              (emit! "\r\n\u001b[2K"))
                                            (emit! (str "\u001b[" extra-lines "A"))))
                                        (emit! CSI-2026-L)
                                        (reset! hardware-cursor-row @final-cursor-row)
                                        (swap! (:max-lines-rendered tui) max new-count)
                                        (reset! viewport-top
                                                (max @prev-viewport-top
                                                     (- @final-cursor-row h -1)))
                                        (position-hardware-cursor cursor new-count))))))]
                (cond
                  first-render?
                  (do (log-redraw! "first render")
                      (do-full-redraw false))
                  width-changed
                  (do (log-redraw! (str "terminal width changed (" prev-w " -> " w ")"))
                      (do-full-redraw true))
                  (and height-changed (not termux?))
                  (do (log-redraw! (str "terminal height changed (" prev-h " -> " h ")"))
                      (do-full-redraw true))
                  (and @(:clear-on-shrink? tui)
                       (< new-count @(:max-lines-rendered tui))
                       (empty? @(:overlays tui)))
                  (do (log-redraw! (str "clearOnShrink (maxLinesRendered="
                                        @(:max-lines-rendered tui) ")"))
                      (do-full-redraw true))
                  :else
                  (main-diff))
                (reset! previous-viewport-top @viewport-top)
                (when @(:tui-debug? tui)
                  (tui-debug-dump! prev lines (str @sb) w h @viewport-top @hardware-cursor-row))
                (when (pos? (.length @sb))
                  (terminal/write-output started (str @sb)))
                (reset! (:previous-lines tui) lines)
                (reset! (:previous-width tui) w)
                (reset! (:previous-height tui) h)
                (reset! (:previous-kitty-image-ids tui)
                        (if (some img/is-image-line lines)
                          (collect-kitty-image-ids lines)
                          #{})))))

          (Thread/sleep 16)
          (recur)))
      (finally
        (reset! (:running? tui) false)
        ;; Disable the keyboard protocols: drain on final stop so late key
        ;; release sequences don't leak to the parent shell (pi: drainInput);
        ;; on suspend only disable so the external program inherits a clean
        ;; terminal (pi: stop disables, drain is quit-only).
        (reset! (:keyboard-protocol-pushed? tui) false)
        (reset! (:negotiation-buffer tui) "")
        (clear-negotiation-timer! tui)
        (clear-terminal-response-timer! tui)
        (reset! (:terminal-response-buffer tui) "")
        (reset! (:pending-osc-11? tui) false)
        (when @(:color-scheme-notifications-enabled? tui)
          (terminal/set-color-scheme-notifications! started false))
        (if @(:stopped? tui)
          (terminal/drain-input! started)
          (terminal/disable-kitty-protocol! started))
        ;; Pi: on final stop position the cursor at end of content so the
        ;; shell prompt appears below and the user can scroll up to review
        ;; the session. On suspend the position is left untouched for the
        ;; external program that takes over the terminal.
        (when @(:stopped? tui)
          (let [prev-lines @(:previous-lines tui)]
            (when (seq prev-lines)
              (let [target-row (count prev-lines)  ;; row past last content line
                    row-delta (- target-row @hardware-cursor-row)]
                (terminal/write-output started " ")
                (when (pos? row-delta)
                  (terminal/write-output started (str "\u001b[" row-delta "B")))
                (terminal/write-output started "\r\n")))))
        (terminal/show-cursor! started)
        (terminal/stop! started)))))
(defn tui-start
  "Start the TUI: enter raw mode, start the input reader and render loop.
   Blocks the calling thread until tui-stop is called."
  [tui]
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  ;; §3.4 scheduler hook: dependency changes request a frame through here
  ;; (ComponentFn's auto-run callback); cleared on stop so late watchers
  ;; can't poke a dead loop.
  (macros/set-frame-hook! #(tui-request-render tui))
  (start-input-reader tui)
  (reset! (:render-loop tui) (future
                               (try (run-render-loop! tui)
                                    (catch Throwable t
                                      ;; A render crash must not leave the app
                                      ;; hanging with a dead render loop and a
                                      ;; dead reader: log the stack, then stop
                                      ;; so the caller unwinds and -main reports
                                      ;; the error (pi: the render loop never
                                      ;; crashes silently).
                                      (try (with-open [w (java.io.FileWriter. "render-crash.log" true)]
                                             (.write w (str t "\n" (clojure.string/join "\n" (.getStackTrace t)) "\n")))
                                           (catch Exception _))
                                      (tui-stop tui)))))
  (tui-request-render tui)
  ;; Block until a final stop is requested
  (loop []
    (when-not @(:stopped? tui)
      (Thread/sleep 50)
      (recur)))
  ;; Final stop: join both loops (the render loop's finally restores the
  ;; terminal; closing it unblocks the input reader so it exits too)
  (reset! (:running? tui) false)
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  (when-let [f @(:input-reader tui)]
    (deref f 3000 nil))
  (reset! (:input-reader tui) nil)
  nil)

(defn tui-suspend!
  "Suspend the TUI: stop the render loop and input reader, restore the
   terminal to its normal (non-raw) state, and close it so a spawned
   external program (e.g. $EDITOR) has exclusive access to the terminal.
   Call tui-resume! to restart. May be called from the input reader thread
   (the thread that drives the external program afterwards)."
  [tui]
  (reset! (:running? tui) false)
  ;; The render loop's finally restores and closes the terminal, which also
  ;; unblocks any blocked input read. Wait for it to finish.
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  nil)

(defn tui-resume!
  "Restart the TUI after tui-suspend!: create a fresh terminal, re-enter raw
   mode, and restart the render loop and input reader. Forces a clearing full
   redraw: the external program that took over the terminal restores the
   pre-suspend frame on exit, so the re-emit must clear the stale screen and
   scrollback first or old and new content overlap (pi: requestRender(true)
   after ui.start() — the forced flag routes the first frame through the
   clearing full-redraw path)."
  [tui]
  (reset! (:terminal tui) (terminal/create-terminal))
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  ;; Drop any pending input flush from before the suspend (pi:
  ;; beforeTerminalStart resets selection state).
  (clear-incomplete-flush! tui)
  (start-input-reader tui)
  (reset! (:render-loop tui) (future (run-render-loop! tui)))
  (tui-request-render tui true)
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Re-exports — convenience aliases for all public symbols.
;; ═══════════════════════════════════════════════════════════════════════════

;; Terminal
(def ITerminal terminal/ITerminal)
(def create-terminal terminal/create-terminal)

;; Keys
(def matches-key? keys/matches-key?)
(def parse-key keys/parse-key)
(def is-key-release? keys/is-key-release?)
(def is-key-repeat? keys/is-key-repeat?)
(def set-kitty-active! keys/set-kitty-active!)
(def kitty-active? keys/kitty-active?)
(def KEY-UP keys/KEY-UP)
(def KEY-DOWN keys/KEY-DOWN)
(def KEY-LEFT keys/KEY-LEFT)
(def KEY-RIGHT keys/KEY-RIGHT)
(def KEY-ENTER keys/KEY-ENTER)
(def KEY-ESC keys/KEY-ESC)
(def KEY-TAB keys/KEY-TAB)
(def KEY-BACKSPACE keys/KEY-BACKSPACE)
(def KEY-DELETE keys/KEY-DELETE)
(def KEY-HOME keys/KEY-HOME)
(def KEY-END keys/KEY-END)
(def KEY-PAGE-UP keys/KEY-PAGE-UP)
(def KEY-PAGE-DOWN keys/KEY-PAGE-DOWN)
(def KEY-SPACE keys/KEY-SPACE)
(def KEY-INSERT keys/KEY-INSERT)
(def ctrl keys/ctrl)
(def shift keys/shift)
(def alt keys/alt)

;; Utils
(def visible-width utils/visible-width)
(def truncate-to-width utils/truncate-to-width)
(def wrap-text-with-ansi utils/wrap-text-with-ansi)
(def apply-background-to-line utils/apply-background-to-line)
(def strip-ansi-codes utils/strip-ansi-codes)
(def sgr utils/sgr)
(def slice-by-column utils/slice-by-column)

;; Components
(def make-text text/make-text)
(def text-set! text/text-set!)
(def make-spacer spacer/make-spacer)
(def make-container container/make-container)
(def container-add-child container/container-add-child)
(def container-remove-child container/container-remove-child)
(def container-clear container/container-clear)
(def container-set-children! container/container-set-children!)
(def container-replace-children! container/container-replace-children!)
(def make-box box/make-box)
(def box-add-child box/box-add-child)
(def box-remove-child box/box-remove-child)
(def box-clear box/box-clear)
(def box-set-bg-fn box/box-set-bg-fn)
(def box-set-padding-x! box/box-set-padding-x!)
(def box-set-padding-y! box/box-set-padding-y!)
(def make-input input/make-input)
(def input-set-value! input/input-set-value!)
(def input-get-value input/input-get-value)
(def input-set-on-submit! input/input-set-on-submit!)
(def input-set-on-escape! input/input-set-on-escape!)
(def make-editor editor/make-editor)
;; IEditorComponent members dispatch through the protocol when the target
;; implements it (custom editors from extensions), else the field-based fn
;; (Editor-shaped records). `ed` avoids shadowing the `editor` ns alias.
(defn editor-set-text! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-text! ed text)
    (editor/editor-set-text! ed text)))
(defn editor-get-text [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-text ed)
    (editor/editor-get-text ed)))
(defn editor-set-on-submit! [ed f]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-on-submit! ed f)
    (editor/editor-set-on-submit! ed f)))
(defn editor-set-on-change! [ed f]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-on-change! ed f)
    (editor/editor-set-on-change! ed f)))
(defn editor-get-expanded-text [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-expanded-text ed)
    (editor/editor-get-expanded-text ed)))
(defn editor-set-autocomplete-provider! [ed provider]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-autocomplete-provider! ed provider)
    (editor/editor-set-autocomplete-provider! ed provider)))
(defn editor-set-autocomplete-max-visible! [ed n]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-autocomplete-max-visible! ed n)
    (editor/editor-set-autocomplete-max-visible! ed n)))
(defn editor-set-padding-x! [ed n]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-padding-x! ed n)
    (editor/editor-set-padding-x! ed n)))
(defn editor-insert-text-at-cursor! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-insert-text-at-cursor! ed text)
    (editor/editor-insert-text-at-cursor! ed text)))
(defn editor-add-to-history! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-add-to-history! ed text)
    (editor/editor-push-history! ed text)))
(def editor-set-on-action! editor/editor-set-on-action!)
(def editor-set-on-tab! editor/editor-set-on-tab!)
(def editor-set-autocomplete-theme! editor/editor-set-autocomplete-theme!)
(def editor-autocomplete-active? editor/editor-autocomplete-active?)
(def editor-push-history! editor/editor-push-history!)
(def editor-get-history editor/editor-get-history)
(def editor-set-history! editor/editor-set-history!)
(def editor-get-paste editor/editor-get-paste)
(def editor-set-height! editor/editor-set-height!)
(def editor-set-terminal-rows! editor/editor-set-terminal-rows!)
(def editor-get-text-length editor/editor-get-text-length)

;; SelectList
(def make-select-list select-list/make-select-list)
(def select-list-set-items! select-list/select-list-set-items!)
(def select-list-get-selected select-list/select-list-get-selected)
(def select-list-set-theme! select-list/select-list-set-theme!)
(def default-theme select-list/default-theme)

;; SettingsList
(def make-settings-list settings-list/make-settings-list)
(def settings-list-set-on-escape! settings-list/settings-list-set-on-escape!)
(def settings-list-get-item settings-list/settings-list-get-item)
(def settings-list-set-value! settings-list/settings-list-set-value!)

;; Markdown
(def make-markdown markdown/make-markdown)
(def markdown-set-text! markdown/markdown-set-text!)
(def markdown-append! markdown/markdown-append!)
(def markdown-set-theme! markdown/markdown-set-theme!)
(def markdown-set-padding-x! markdown/markdown-set-padding-x!)
(def markdown-get-text markdown/markdown-get-text)

;; Spinner
(def make-spinner spinner/make-spinner)
(def spinner-start! spinner/spinner-start!)
(def spinner-set-start! spinner/spinner-set-start!)
(def spinner-stop! spinner/spinner-stop!)
(def spinner-active? spinner/spinner-active?)
(def spinner-set-text! spinner/spinner-set-text!)
(def spinner-set-prefix! spinner/spinner-set-prefix!)
(def spinner-set-spinner-color-fn! spinner/spinner-set-spinner-color-fn!)
(def spinner-set-message-color-fn! spinner/spinner-set-message-color-fn!)

;; ScrollView + stack layout
(def IScrollView scroll-view/IScrollView)
(def make-scroll-view scroll-view/make-scroll-view)
(def scroll-view-update-layout! scroll-view/update-layout!)
(def scroll-view-render-window scroll-view/render-window)
(def scroll-view-scroll-by! scroll-view/scroll-by!)
(def scroll-view-scroll-to! scroll-view/scroll-to!)
(def scroll-view-scroll-to-start! scroll-view/scroll-to-start!)
(def scroll-view-scroll-to-end! scroll-view/scroll-to-end!)
(def scroll-view-scroll-top scroll-view/scroll-top)
(def scroll-view-follows-end? scroll-view/follows-end?)
(def scroll-view-set-follow-end! scroll-view/scroll-view-set-follow-end!)
(def scroll-view-set-primary! scroll-view/scroll-view-set-primary!)
(def scroll-view-set-overscroll! scroll-view/scroll-view-set-overscroll!)
(def scroll-view-set-scrollbar-style! scroll-view/scroll-view-set-scrollbar-style!)
(def scroll-view-set-scrollbar-hide-delay-ms!
  scroll-view/scroll-view-set-scrollbar-hide-delay-ms!)
(def render-stack stack/render-stack)

;; AltScreenFlash — transient messages composited over the screen bottom
(def make-alt-screen-flash alt-screen-flash/make-alt-screen-flash)
(def alt-screen-flash! alt-screen-flash/alt-screen-flash!)
(def alt-screen-flash-dispose! alt-screen-flash/alt-screen-flash-dispose!)

;; CancellableLoader — Loader cancellable with Escape (pi: BorderedLoader)
(def make-cancellable-loader cancellable-loader/make-cancellable-loader)
(def cancellable-loader-aborted? cancellable-loader/cancellable-loader-aborted?)
(def cancellable-loader-signal cancellable-loader/cancellable-loader-signal)
(def cancellable-loader-set-on-abort! cancellable-loader/cancellable-loader-set-on-abort!)
(def cancellable-loader-dispose! cancellable-loader/cancellable-loader-dispose!)

;; TruncatedText — single-line truncating text
(def make-truncated-text truncated-text/make-truncated-text)
(def truncated-text-set-text! truncated-text/truncated-text-set-text!)

;; DynamicBorder — theme-colored border line (pi: DynamicBorder)
(def make-dynamic-border dynamic-border/make-dynamic-border)

;; HStack / VStack — flexbox-style horizontal / vertical stacks
(def make-h-stack h-stack/make-h-stack)
(def h-stack-add-child! h-stack/h-stack-add-child!)
(def h-stack-remove-child! h-stack/h-stack-remove-child!)
(def h-stack-clear! h-stack/h-stack-clear!)
(def h-stack-set-gap! h-stack/h-stack-set-gap!)
(def h-stack-set-align! h-stack/h-stack-set-align!)
(def make-v-stack v-stack/make-v-stack)
(def v-stack-add-child! v-stack/v-stack-add-child!)
(def v-stack-remove-child! v-stack/v-stack-remove-child!)
(def v-stack-clear! v-stack/v-stack-clear!)
(def v-stack-set-gap! v-stack/v-stack-set-gap!)

;; Stack sizing (pi: allocateStackSizes) — shared by HStack/VStack
(def stack-entry? stack/stack-entry?)
(def entry-component stack/entry-component)
(def visible-stack-entries stack/visible-stack-entries)
(def allocate-stack-sizes stack/allocate-stack-sizes)


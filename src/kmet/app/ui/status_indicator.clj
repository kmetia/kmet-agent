(ns kmet.app.ui.status-indicator
  "Status indicators — the working spinner and the transient session
   statuses (retry, compaction, branch summarization).
   The default editor embeds the active status in its own first line (its
   top border) through editor-top-border, so no separate status row sits
   above the editor (pi: status-indicator.ts + CustomEditor.renderTopBorder
   — the embedWorkingStatus opt-in). A custom editor (extension
   :set-editor-component) has no top-border fn, so make-status-area renders
   the standalone layer instead: the working indicator shows a spinner
   while the agent is active and the idle two rows otherwise; Retry and
   Compaction/BranchSummary indicators are transient swaps shown via
   show-status-indicator! / clear-status-indicator!
   (pi: showStatusIndicator/clearStatusIndicator). All standalone indicators
   render the same two-row shape (leading blank + content) so the editor and
   footer below never jump."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.reakt :as r]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.macros :refer [defcomponent]]))

(def ^:private SPINNER-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- frame-at
  "Braille frame for the given elapsed millis — self-animating on re-render
   (no timer needed; the interactive mode's anim timer drives renders)."
  [elapsed]
  (nth SPINNER-FRAMES (mod (quot (max 0 elapsed) 100) (count SPINNER-FRAMES))))

(defcomponent StatusIndicator nil [spinner active-atom border-color-fn]
  (render [this width]
    (if @active-atom
      ;; The Spinner renders the pi Loader shape itself (leading blank +
      ;; animated line); indent only the content line for chat alignment.
      (let [lines (protocols/render (:spinner this) width)]
        (into [(first lines)] (mapv #(str " " %) (rest lines))))
      ;; Pi: IdleStatus — always occupy the same two rows so the editor and
      ;; footer below don't jump when the indicator appears/disappears.
      ["" ""]))
  (invalidate [this]
    (protocols/invalidate (:spinner this))))

;; ─── Construction ────────────────────────────────────────────────────────

(defn- install-colors!
  "Point the spinner's color fns at the current source: while the active
   editor embeds the status, the working line blends with the editor's
   border color — resolved through the indicator's :border-color-fn at call
   time, so a thinking level change recolors without reinstalling (pi:
   showWorkingStatusIndicator's colorFn closure over editor.borderColor);
   standalone (custom editor) it keeps the accent/muted pair."
  [indicator th]
  (let [provider (:border-color-fn indicator)
        pick (fn [fallback-key]
               (fn [s]
                 (if-let [border-fn (when provider (provider))]
                   (border-fn s)
                   (theme/fg th fallback-key s))))]
    (spinner/spinner-set-spinner-color-fn! (:spinner indicator) (pick :accent))
    (spinner/spinner-set-message-color-fn! (:spinner indicator) (pick :muted))))

(defn make-status-indicator
  "Create the default working StatusIndicator.
   When active, shows a spinner with the given message.
   When inactive, renders nothing (the idle two rows).
   Options key-value pairs:
     :text  — message text (default \"Working\")
     :theme — theme map (default dark-theme)
     :border-color-fn — 0-arg fn returning the editor's border color fn
       while the status lives in the editor border, nil otherwise (the
       app wires the active editor's :border-fn here); absent/answering
       nil keeps the accent/muted standalone colors (pi:
       showWorkingStatusIndicator's colorFn)."
  [& {:keys [text theme border-color-fn]
      :or {text "Working" theme theme/dark-theme}}]
  (let [sp (spinner/make-spinner
            :text text
            :active false
            :prefix ""
            :frames SPINNER-FRAMES
            :interval-ms 100)
        indicator (map->StatusIndicator {:spinner sp
                                         :active-atom (atom false)
                                         :border-color-fn border-color-fn})]
    (install-colors! indicator theme)
    indicator))

;; ─── Public API ──────────────────────────────────────────────────────────

(defn status-indicator-active?
  "True while the working indicator is showing its spinner."
  [indicator]
  (spinner/spinner-active? (:spinner indicator)))

(defn status-indicator-start!
  "Activate the status indicator — shows the animated working spinner."
  [indicator]
  (spinner/spinner-start! (:spinner indicator))
  (reset! (:active-atom indicator) true))

(defn status-indicator-stop!
  "Deactivate the status indicator — hides the spinner (idle two rows)."
  [indicator]
  (spinner/spinner-stop! (:spinner indicator))
  (reset! (:active-atom indicator) false))

(defn status-indicator-set-text!
  "Set the message text displayed next to the spinner."
  [indicator text]
  (spinner/spinner-set-text! (:spinner indicator) text))

(defn status-indicator-set-theme!
  "Update the theme colors on the underlying spinner."
  [indicator th]
  (install-colors! indicator th))

(defn editor-embeds-status?
  "True when EDITOR renders the session status in its own top border: the
   app's default editor installs a top-border fn at layout; other editors
   (an extension :set-editor-component swap) don't (pi: isWorkingStatusEditor
   — the embedWorkingStatus opt-in)."
  [editor]
  (boolean (when-some [cell (:top-border-fn-atom editor)] @cell)))

(defn make-status-area
  "The status layer as a fn component (dsl.md stage 4, pi: statusContainer):
   renders the transient indicator recorded in CURRENT-ATOM ({:kind k
   :indicator c} or nil), else the default WORKING-INDICATOR record —
   unless the ACTIVE editor (EDITOR-ATOM) carries the status in its own
   first line, in which case the layer renders nothing: the status lives
   in the editor border (pi: embedWorkingStatus), not in a reserved row
   above it. A custom editor keeps the standalone layer.

   All three atoms are tracked even when the rendered branch ignores them,
   so a status swap or the working indicator's activation schedules the
   frame that repaints the editor border (an idle UI re-derives nothing);
   indicator records are spliced foreign — reconcile swaps identity on
   change and never disposes them (their lifecycle stays with the swapper,
   dsl.md §5)."
  [current-atom working-indicator editor-atom]
  (fn [_props]
    (let [embed? (editor-embeds-status? (r/tracked-deref editor-atom))
          current (r/tracked-deref current-atom)
          _ (when-some [active (:active-atom working-indicator)]
              (r/tracked-deref active))]
      (if embed?
        nil
        (or (:indicator current) working-indicator)))))

;; ─── Transient indicators (pi: Retry/CompactionStatusIndicator) ───────────
;; Self-animating from elapsed time; colors read from the current theme at
;; render time (the interactive mode re-renders continuously while active).

(defcomponent RetryStatusIndicator nil [start-atom attempt-atom max-attempts-atom
                                        delay-ms-atom cancel-hint-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          remaining (max 0 (long (Math/ceil (/ (- @delay-ms-atom elapsed) 1000.0))))
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          message (str "Retrying (" @attempt-atom "/" @max-attempts-atom ") in "
                       remaining "s... (" @cancel-hint-atom " to cancel)")
          line (str (theme/fg th :warning frame) " " (theme/fg th :muted message))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-retry-status-indicator
  "Retry countdown indicator (pi: RetryStatusIndicator + CountdownTimer —
   the countdown is computed from elapsed time on each render instead of a
   timer). cancel-hint — key display text (e.g. \"Escape\")."
  [attempt max-attempts delay-ms & {:keys [cancel-hint]
                                    :or {cancel-hint "Escape"}}]
  (map->RetryStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                              :attempt-atom (atom attempt)
                              :max-attempts-atom (atom max-attempts)
                              :delay-ms-atom (atom delay-ms)
                              :cancel-hint-atom (atom cancel-hint)
                              :cache-atom (atom nil)}))

(defcomponent CompactionStatusIndicator nil [start-atom message-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          line (str (theme/fg th :accent frame) " " (theme/fg th :muted @message-atom))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-compaction-status-indicator
  "Compaction progress indicator (pi: CompactionStatusIndicator). kmet's
   compaction is not cancellable, so no cancel hint is shown."
  [& {:keys [message] :or {message "Compacting context..."}}]
  (map->CompactionStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                                   :message-atom (atom message)
                                   :cache-atom (atom nil)}))

(defcomponent BranchSummaryStatusIndicator nil [start-atom message-atom
                                                cancel-hint-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          message (str @message-atom " (" @cancel-hint-atom " to cancel)")
          line (str (theme/fg th :accent frame) " " (theme/fg th :muted message))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-branch-summary-status-indicator
  "Branch summarization progress indicator (pi: BranchSummaryStatusIndicator)
   with a cancel hint — escape aborts the summarization via the editor's
   interrupt action."
  [& {:keys [message cancel-hint]
      :or {message "Summarizing branch..." cancel-hint "Escape"}}]
  (map->BranchSummaryStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                                      :message-atom (atom message)
                                      :cancel-hint-atom (atom cancel-hint)
                                      :cache-atom (atom nil)}))

;; ─── Editor-border status (pi: StatusIndicator.renderInBorder) ─────────────
;; The default editor embeds the active status in its top border — the
;; interactive mode wires the editor's top-border fn to editor-top-border.
;; Every indicator renders two shapes: its full status text and, for the
;; fallback when the text no longer fits, just the colored frame.

(defprotocol IBorderStatus
  (render-in-border [indicator width]
    "The indicator's status text as it belongs in the editor's top border:
     the standalone line without its leading indent, trailing space
     trimmed, at most WIDTH visible columns (no ellipsis — the border owns
     the geometry).")
  (render-spinner-in-border [indicator width]
    "The indicator's colored animation frame alone, at most WIDTH visible
     columns (pi: StatusIndicator.renderSpinnerInBorder)."))

(defn- border-status-text
  "The standalone status line rendered through the editor-border rules
   (pi: StatusIndicator.renderInBorder — render two columns wider, drop
   the leading indent, trim, cut to WIDTH with no ellipsis)."
  [indicator width]
  (let [line (or (second (protocols/render indicator (+ width 2))) "")
        line (if (str/starts-with? line " ") (subs line 1) line)]
    (u/truncate-to-width (str/trimr line) width "")))

(defn- border-frame
  "The elapsed-time indicator's colored frame (pi:
   StatusIndicator.renderSpinnerInBorder — the frame color follows the
   record's spinner color: warning for retry, accent for compaction and
   branch summarization)."
  [start-atom color-key width]
  (let [th (theme/get-current-theme)]
    (u/truncate-to-width
     (theme/fg th color-key (frame-at (- (System/currentTimeMillis) @start-atom)))
     width "")))

(extend-type StatusIndicator
  IBorderStatus
  (render-in-border [indicator width] (border-status-text indicator width))
  (render-spinner-in-border [indicator width]
    (u/truncate-to-width (spinner/spinner-rendered-indicator (:spinner indicator))
                         width "")))

;; The /share flow records a bare Spinner as the active status
;; (make-spinner :text "Creating gist..."), so the plain spinner must embed
;; too — its single animated line, without the standalone "  " prefix
;; (embedded status aligns with the working indicator's frame column).
(extend-type kmet.tui.components.spinner.Spinner
  IBorderStatus
  (render-in-border [indicator width]
    (u/truncate-to-width (str/trim (spinner/spinner-render-line indicator)) width ""))
  (render-spinner-in-border [indicator width]
    (u/truncate-to-width (spinner/spinner-rendered-indicator indicator) width "")))

(extend-type RetryStatusIndicator
  IBorderStatus
  (render-in-border [indicator width] (border-status-text indicator width))
  (render-spinner-in-border [indicator width]
    (border-frame (:start-atom indicator) :warning width)))

(extend-type CompactionStatusIndicator
  IBorderStatus
  (render-in-border [indicator width] (border-status-text indicator width))
  (render-spinner-in-border [indicator width]
    (border-frame (:start-atom indicator) :accent width)))

(extend-type BranchSummaryStatusIndicator
  IBorderStatus
  (render-in-border [indicator width] (border-status-text indicator width))
  (render-spinner-in-border [indicator width]
    (border-frame (:start-atom indicator) :accent width)))

(defn editor-top-border
  "The editor's first line with the session status embedded (pi:
   CustomEditor.renderTopBorder). Returns nil when there is nothing to
   show — the editor then draws its default rule (with any scroll label).

   MAP keys:
     :indicator         the IBorderStatus indicator (nil → nil)
     :width             the full line width
     :hidden-line-count lines hidden above the viewport (> 0 keeps the
                        centered ` ↑ N more ` label beside the status)
     :rule              the editor's unstyled rule glyph
     :border-fn         the editor's border color fn (styles the border
                        runs; the working status carries its own dynamic
                        colors, which resolve this same fn) — nil renders
                        unstyled

   Layout (pi): `── <status> ───...`; on a scrolled viewport the status
   shares the line with the centered scroll label when both fit, collapsing
   to the bare spinner when they don't; on a narrow line only the spinner
   and rule stubs remain. The status is never cut with an ellipsis — the
   border geometry wins."
  [{:keys [indicator width hidden-line-count rule border-fn]}]
  (when (and indicator (pos? width))
    (let [style (or border-fn identity)
          run (fn [n] (apply str (repeat (max 0 n) rule)))
          overflow? (pos? (or hidden-line-count 0))
          label (when overflow? (str " ↑ " hidden-line-count " more "))
          label-w (if label (u/visible-width label) 0)
          overflow-start (quot (- width label-w) 2)
          fits-overflow? (fn [status-w]
                           (and overflow?
                                (<= (+ label-w 2) width)
                                (>= (- overflow-start (+ 3 status-w 1)) 1)))
          full (render-in-border indicator (max 1 (- width 5)))
          full-w (u/visible-width full)]
      (when (pos? full-w)
        (let [[status status-w] (if (and overflow? (not (fits-overflow? full-w)))
                                  (let [sp (render-spinner-in-border indicator width)]
                                    [sp (u/visible-width sp)])
                                  [full full-w])]
          (cond
            ;; status + centered scroll label: `── <status> ── ↑ N more ──`
            (fits-overflow? status-w)
            (str (style (str rule rule " "))
                 status
                 (style (str " "
                             (run (- overflow-start (+ 3 status-w 1)))
                             label
                             (run (- width overflow-start label-w)))))

            ;; room for the status and a rule tail: `── <status> ───...`
            (>= width (+ status-w 5))
            (str (style (str rule rule " ")) status
                 (style (str " " (run (- width status-w 4)))))

            ;; too narrow for the text: spinner only, rule stubs around it
            :else
            (let [sp (render-spinner-in-border indicator width)
                  sp-w (u/visible-width sp)
                  prefix-w (min 3 (max 0 (- width sp-w)))]
              (str (style (run prefix-w))
                   sp
                   (style (run (max 0 (- width prefix-w sp-w))))))))))))

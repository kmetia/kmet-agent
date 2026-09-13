(ns kmet.tui.components.spinner
  "Animated spinner component — shows a cycling frame animation with a text message.
   Equivalent to Pi's Loader component. When active, renders a leading blank
   line above the animated line (pi Loader: a blank line then the text lines);
   renders nothing when inactive.
   Supports theme-aware color functions for spinner frame and message text."
  (:require
   [kmet.tui.macros :refer [defcomponent]]
   [kmet.tui.utils :as u]))

(def ^:private DEFAULT-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def default-frames
  "The default braille indicator frames — the :frames default of
   make-spinner, public so the hiccup :spinner tag can apply it
   explicitly (an explicit nil prop bypasses make-spinner's :or)."
  DEFAULT-FRAMES)

(def ^:private CYN "\u001b[36m")
(def ^:private RST "\u001b[0m")

(defn- raw-indicator
  "SPINNER's current animation frame, unstyled — the clock and frames
   without the color fn."
  [spinner]
  (let [elapsed (- (System/nanoTime) @(:start-atom spinner))
        interval-ms @(:interval-ms-atom spinner)
        frames @(:frames-atom spinner)]
    (if (seq frames)
      (nth frames (mod (long (/ elapsed (* interval-ms 1000000))) (count frames)))
      "")))

(defn spinner-rendered-indicator
  "The current animation frame, styled by the spinner's color fn (pi:
   Loader.getRenderedIndicator). Verbatim indicators (custom frames through
   spinner-set-indicator!) return the raw frame; an empty frame returns \"\"."
  [spinner]
  (let [frame (raw-indicator spinner)]
    (if @(:verbatim-atom spinner)
      frame
      ((or @(:spinner-color-fn-atom spinner) (fn [s] (str CYN s RST))) frame))))

(defn spinner-render-line
  "The spinner's animated line — frame, space, message — without the
   leading blank; \"\" while inactive. The editor border embeds this line
   directly (see the editor's top-border fn)."
  [spinner]
  (if-not @(:active-atom spinner)
    ""
    (let [frame (spinner-rendered-indicator spinner)
          msg-fn (or @(:message-color-fn-atom spinner) identity)]
      (str @(:prefix-atom spinner)
           (when (seq frame) (str frame " "))
           (msg-fn @(:text-atom spinner))))))

(defcomponent Spinner nil [active-atom text-atom start-atom frames-atom interval-ms-atom
                           prefix-atom spinner-color-fn-atom message-color-fn-atom
                           verbatim-atom]
  (render [_this width]
    (if-not @active-atom
      []  ;; invisible when inactive
      ;; pi Loader: leading blank line above the animated line
      ["" (u/truncate-to-width (spinner-render-line _this) width)])))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-spinner
  "Create a new Spinner component (Pi's Loader equivalent).
   Options key-value pairs:
     :text            — message text (e.g. \"Working...\")
     :active          — whether to show the spinner initially
     :prefix          — string prepended before the spinner (default \"  \")
     :frames          — vector of frame strings (default braille spinner)
     :interval-ms     — animation interval in ms (default 100)
     :spinner-color-fn — (fn [frame-str] colored-frame-str) (default cyan)
     :message-color-fn — (fn [message-str] colored-message-str) (default identity)"
  [& {:keys [text active prefix frames interval-ms spinner-color-fn message-color-fn]
      :or {text "" active false prefix "  " frames DEFAULT-FRAMES interval-ms 100}}]
  (map->Spinner {:active-atom (atom active)
                 :text-atom (atom text)
                 :start-atom (atom (System/nanoTime))
                 :frames-atom (atom frames)
                 :interval-ms-atom (atom interval-ms)
                 :prefix-atom (atom prefix)
                 :spinner-color-fn-atom (atom spinner-color-fn)
                 :message-color-fn-atom (atom message-color-fn)
                 :verbatim-atom (atom false)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn spinner-start!
  "Activate the spinner and reset its animation timer."
  [spinner]
  (reset! (:start-atom spinner) (System/nanoTime))
  (reset! (:active-atom spinner) true))

(defn spinner-set-start!
  "Reset the animation timer."
  [spinner]
  (reset! (:start-atom spinner) (System/nanoTime)))

(defn spinner-stop!
  "Deactivate the spinner."
  [spinner]
  (reset! (:active-atom spinner) false))

(defn spinner-active?
  "Returns true if the spinner is currently active."
  [spinner]
  @(:active-atom spinner))

(defn spinner-set-text!
  "Set the message text displayed next to the spinner."
  [spinner text]
  (reset! (:text-atom spinner) text))

(defn spinner-set-prefix!
  "Set the prefix before the spinner."
  [spinner prefix]
  (reset! (:prefix-atom spinner) prefix))

(defn spinner-set-spinner-color-fn!
  "Set the color function for the spinner frame (fn frame → colored-frame)."
  [spinner f]
  (reset! (:spinner-color-fn-atom spinner) f))

(defn spinner-set-message-color-fn!
  "Set the color function for the message text (fn msg → colored-msg)."
  [spinner f]
  (reset! (:message-color-fn-atom spinner) f))

(defn spinner-set-indicator!
  "Set the animated indicator frames and interval (pi:
   Loader.setIndicator({frames, intervalMs})). Options map with
   :frames (vector of frame strings) and/or :interval-ms. When options
   are provided the indicator renders VERBATIM — custom frames are used
   as-is without the spinner color fn, and an empty frames vector hides
   the indicator (message only). nil options restore the default frames,
   interval, and color-fn rendering. Returns the spinner."
  [spinner & [options]]
  (if (nil? options)
    (do (reset! (:verbatim-atom spinner) false)
        (reset! (:frames-atom spinner) DEFAULT-FRAMES)
        (reset! (:interval-ms-atom spinner) 100)
        spinner)
    (let [{:keys [frames interval-ms]} options]
      (reset! (:verbatim-atom spinner) true)
      (when (some? frames)
        (reset! (:frames-atom spinner) (vec frames)))
      (when (and (some? interval-ms) (pos? interval-ms))
        (reset! (:interval-ms-atom spinner) interval-ms))
      (reset! (:start-atom spinner) (System/nanoTime))
      spinner)))

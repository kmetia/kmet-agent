(ns kmet.ui.tui.assistant-message
  "AssistantMessageComponent component — Pi's AssistantMessageComponent.
   Top pad-y=1 only (Pi-style Spacer(1) at top when content present).
   No bottom padding — next component provides its own top spacing.
   Spacer between thinking and text blocks when text follows thinking.
   Content lives in the message map's data atoms (shared into this
   record); appends are pure swaps on the LLM thread and re-wrapping
   happens lazily in render (the stale check), so streaming deltas never
   block (dsl.md §3.2 Stage 5). Styling subscribes to ui.subs/theme-sub.
   Does NOT include a working spinner — the working indicator is a separate
   StatusIndicator in a dedicated layout layer between chat and editor (Pi-style)."
  (:require [clojure.string :as str]
            [kmet.ui.tui.subs :as s]
            [kmet.libs.theme :as theme]
            [kmet.tui.components.markdown :as md]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.app.extensions.core :as extensions]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- make-assistant-transform
  "Extension markdown transform for assistant messages (pi:
   createMarkdownTransform(\"assistant\", this.isStreaming)): transformers
   apply in registration order at reflow time (every streaming chunk
   re-runs them), throwing transformers are skipped; the transformer list is
   read at apply time so late registrations take effect on the next reflow.
   STREAMING-ATOM — the component's streaming flag (true while the response
   streams, false once finalized)."
  [streaming-atom]
  (fn [text {:keys [available-width]}]
    (extensions/apply-markdown-transformers
     text {:message-type :assistant
           :is-streaming (boolean @streaming-atom)
           :available-width available-width})))

(defn- render-text-to-width
  "Render assistant text as markdown (pi: Markdown + getMarkdownTheme, which
   includes syntax-highlighting the code fences). Plain text is left unstyled
   — terminal default, exactly like pi's assistant messages. CACHE-ATOM makes
   the render incremental: a streaming delta only re-renders the changed
   block (see kmet.tui.components.markdown/render-lines)."
  [text cw left-pad theme transform cache-atom]
  (when (seq text)
    (mapv #(str left-pad %)
          (md/render-lines text cw
                           :theme (theme/get-markdown-theme theme)
                           :transform transform
                           :padding-x 0
                           ;; the app theme identifies the markdown theme /
                           ;; default style the lines were built with
                           :cache-key [cw theme :text]
                           :cache-atom cache-atom))))

(def ^:private stream-last-tail-lines
  "Rendered lines kept by :stream-last — the tail of the block the model is
   still thinking. Counted in display lines (wrapping included), so the block
   never occupies more than this many rows."
  6)

(defn- thinking-label-line
  "The hidden-thinking label line (pi: hiddenThinkingLabel) — what :hidden
   shows for every block, and :stream-last once the block's live phase
   ended."
  [left-pad theme hidden-label]
  (str left-pad (theme/italic (theme/fg theme :thinking-text hidden-label))))

(defn- render-thinking-to-width
  "Render thinking text as markdown tinted thinkingText + italic (pi:
   Markdown with defaultTextStyle {color: thinkingText, italic: true}). Code
   fences inside thinking highlight, like pi. MODE picks the projection:
   :visible renders the whole block, :hidden renders the hidden-thinking
   label instead (pi: hiddenThinkingLabel), :stream-last renders the trailing
   STREAM-LAST-TAIL-LINES while THINKING-LIVE? — the model is still thinking
   — and falls back to the same label once that phase ended, so a finished
   block reads exactly as it does under :hidden and at most one live tail is
   on screen. The tail is a slice of the rendered lines, not of the source:
   the incremental block cache stays intact, a fence never starts mid-marker,
   and the window keeps its height while the block streams (a take-last of
   the growing block, never a re-trimmed window)."
  [text cw left-pad theme mode thinking-live? hidden-label transform cache-atom]
  (if (not (seq text))
    []
    (if (= mode :hidden)
      [(thinking-label-line left-pad theme hidden-label)]
      (let [lines (mapv #(str left-pad %)
                        (md/render-lines text cw
                                         :theme (theme/get-markdown-theme theme)
                                         :default-style (fn [s]
                                                          (theme/italic (theme/fg theme :thinking-text s)))
                                         :transform transform
                                         :padding-x 0
                                         ;; the style kind is in the key so the
                                         ;; text and thinking block caches
                                         ;; never cross
                                         :cache-key [cw theme :thinking]
                                         :cache-atom cache-atom))]
        (case mode
          :stream-last (if thinking-live?
                         (vec (take-last stream-last-tail-lines lines))
                         [(thinking-label-line left-pad theme hidden-label)])
          lines)))))

;; The rendered-* atoms are the render body's OWN outputs — reflow-all!
;; writes them on a cache miss, after the values the cache recorded. A
;; tracked read therefore goes stale against them whenever the reflow
;; changed them (a width change always does), track! discards the frame it
;; just built, and the next render re-parses the markdown. The tracked
;; inputs (text/thinking/streaming/tool-calls/hide/label/pad/theme) already
;; invalidate the cache whenever a reflow is due; these readers only feed
;; the stale check and the result.
(defn- rendered-text [c] @(:rendered-text-atom c))
(defn- rendered-thinking [c] @(:rendered-thinking-atom c))
(defn- rendered-streaming? [c] @(:rendered-streaming-atom c))
(defn- rendered-mode [c] @(:rendered-mode-atom c))
(defn- rendered-thinking-active? [c] @(:rendered-thinking-active-atom c))
(defn- rendered-hidden-label [c] @(:rendered-hidden-label-atom c))
(defn- rendered-pad [c] @(:rendered-pad-atom c))
(defn- rendered-theme [c] @(:rendered-theme-atom c))
(defn- rendered-text-lines [c] @(:rendered-text-lines-atom c))
(defn- rendered-thinking-lines [c] @(:rendered-thinking-lines-atom c))
(defn- last-render-width [c] @(:last-render-width-atom c))

(declare reflow-all!)

;; ─── Record ────────────────────────────────────────────────────────────────

;; No custom dispose: stores no child components — only string lines and
;; data atoms (the incremental block caches included). Markdown rendering is
;; the pure render-lines walk, so no transient component is constructed per
;; reflow and no track! registry entry outlives the frame.
(defcomponent AssistantMessageComponent :assistant
              [text-atom thinking-text-atom
               output-pad-atom thinking-mode-atom thinking-active-atom
               hidden-label-atom
               streaming-atom
               tool-calls-atom          ;; message carries tool calls (pi: hasToolCalls)
               rendered-text-lines-atom
               rendered-thinking-lines-atom
               rendered-text-atom        ;; text source of the cached lines (stale check)
               rendered-thinking-atom    ;; thinking source of the cached lines (stale check)
               rendered-streaming-atom   ;; streaming flag of the cached lines (stale check)
               rendered-mode-atom        ;; thinking display mode the cached lines were built with
               rendered-thinking-active-atom ;; live-thinking flag the cached lines were built with
               rendered-hidden-label-atom ;; label the cached lines were built with
               rendered-pad-atom        ;; output pad the cached lines were wrapped at
               rendered-theme-atom       ;; theme the cached lines were built with (theme-sub)
               last-render-width-atom
               text-block-cache-atom     ;; incremental md blocks of the text
               thinking-block-cache-atom ;; incremental md blocks of the thinking
               cache-atom]
  (render [this width]
    (track! this width
      (let [prev-width (last-render-width this)
            ;; Pi trims each content block (content.text.trim()); whitespace-only
            ;; blocks render nothing (and get no Spacer(1)).
            text (let [t (str/trim (or @text-atom ""))] (when (seq t) t))
            thinking (let [t (str/trim (or @thinking-text-atom ""))] (when (seq t) t))
            streaming? (boolean @streaming-atom)
            ;; the pad atom is shared by every message the chat history owns:
            ;; reading it here is what re-wraps the cached lines when it moves
            pad (deref output-pad-atom)
            ;; Read in the track! body: marking tool calls on a finalized
            ;; empty message invalidates the placeholder away.
            tool-calls? (boolean @tool-calls-atom)
            ;; Read in the track! body so a flip of the (possibly SHARED)
            ;; flag/label atoms invalidates this cache like any other input;
            ;; theme-sub is the shared palette subscription (Stage 5).
            theme (deref s/theme-sub)
            mode @thinking-mode-atom
            thinking-live? (boolean @thinking-active-atom)
            hidden-label @hidden-label-atom
            text-empty? (nil? text)
            thinking-empty? (nil? thinking)
            ;; Reflow lazily on the render thread: appends only swap the text
            ;; atom (never blocking the LLM stream); a render re-wraps when the
            ;; width changed, new text arrived, or the streaming flag flipped
            ;; (finalize must re-run transformers with is-streaming false)
            ;; since the cached lines.
            stale? (or (and prev-width (not= prev-width width))
                       (not= text (rendered-text this))
                       (not= thinking (rendered-thinking this))
                       (not= streaming? (rendered-streaming? this))
                       (not= mode (rendered-mode this))
                       (not= thinking-live? (rendered-thinking-active? this))
                       (not= hidden-label (rendered-hidden-label this))
                       (not= pad (rendered-pad this))
                       (not= theme (rendered-theme this)))
            _ (when stale? (reflow-all! this width))
            text-lines (rendered-text-lines this)
            thinking-lines (rendered-thinking-lines this)]
        ;; Pi-style: no visible content → render nothing while STREAMING (the
        ;; working indicator covers the wait) and render nothing for a FINALIZED
        ;; response that carried tool calls — its ToolExecutionComponents are
        ;; the visuals (pi: hasToolCalls; loop-continue assistant messages are
        ;; usually tool-call-only and must not bubble). Only a genuinely silent
        ;; final stop (:stop with no text/thinking/tool calls) renders the muted
        ;; placeholder, with the same top pad-y=1 spacer as content-bearing
        ;; messages so it doesn't glue to the box above.
        (if (and text-empty? thinking-empty?)
          (when-not (or streaming? tool-calls?)
            (let [pad-x @output-pad-atom
                  left-pad (apply str (repeat pad-x \space))
                  pad-line (apply str (repeat width \space))]
              [pad-line
               (str left-pad (theme/dim (theme/fg theme :muted "(no response)")))]))
          ;; Normal: render with reactive cache + top pad-y=1 only (Pi-style Spacer(1) equivalent)
          (let [pad-y 1
                empty (apply str (repeat width \space))
                ;; Pi-style: spacer between thinking and text blocks when text
                ;; follows — keyed on the RENDERED thinking lines, so a thinking
                ;; block that renders nothing leaves no spacer
                thinking-text-spacer? (and (seq thinking-lines) (seq text))
                content (vec (concat thinking-lines
                                     (when thinking-text-spacer? [empty])
                                     text-lines))]
            ;; Pi-style: top padding only (Spacer(1) equivalent).
            ;; No bottom padding — next component provides its own top spacing.
            (vec (concat (repeat pad-y empty)
                         content))))))))

;; ─── Internal: reflow both text and thinking into the line atoms ──────────

(defn- reflow-all!
  "Re-wrap/render all text and thinking, storing into line atoms plus the
   source text they were wrapped from (the render's stale check). Both block
   renders are incremental: their per-message caches keep the rendered lines
   of every block but the streaming tail."
  [comp width]
  (let [theme (deref s/theme-sub)
        output-pad @(:output-pad-atom comp)
        mode @(:thinking-mode-atom comp)
        thinking-live? (boolean @(:thinking-active-atom comp))
        hidden-label @(:hidden-label-atom comp)
        pad-x output-pad
        cw (max 1 (- width (* 2 pad-x)))
        left-pad (apply str (repeat pad-x \space))
        text (str/trim (or @(:text-atom comp) ""))
        thinking (str/trim (or @(:thinking-text-atom comp) ""))
        streaming? (boolean @(:streaming-atom comp))
        transform (make-assistant-transform (:streaming-atom comp))]
    (reset! (:rendered-text-lines-atom comp)
            (render-text-to-width text cw left-pad theme transform
                                  (:text-block-cache-atom comp)))
    (reset! (:rendered-thinking-lines-atom comp)
            (render-thinking-to-width thinking cw left-pad theme mode thinking-live?
                                      hidden-label transform
                                      (:thinking-block-cache-atom comp)))
    (reset! (:rendered-text-atom comp) text)
    (reset! (:rendered-thinking-atom comp) thinking)
    (reset! (:rendered-streaming-atom comp) streaming?)
    (reset! (:rendered-mode-atom comp) mode)
    (reset! (:rendered-thinking-active-atom comp) thinking-live?)
    (reset! (:rendered-hidden-label-atom comp) hidden-label)
    (reset! (:rendered-pad-atom comp) pad-x)
    (reset! (:rendered-theme-atom comp) theme)
    (reset! (:last-render-width-atom comp) width)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  "THEME is no longer taken: styling subscribes to ui.subs/theme-sub and
   follows palette changes live (Stage 5). :output-pad takes a number,
   :output-pad-atom a caller-owned atom (the chat history shares one)."
  [& {:keys [text thinking text-atom thinking-atom output-pad output-pad-atom
             thinking-mode thinking-mode-atom
             thinking-active? thinking-active-atom
             hidden-label hidden-label-atom tool-calls?]
      :or {text "" thinking ""
           output-pad 1 thinking-mode :visible thinking-active? false}}]
  ;; Optional SHARED atoms: when the chat history passes its own
  ;; thinking-mode/hidden-label atoms, every message built from it reads the
  ;; same state — a toggle is a single reset! that track! watches on all
  ;; messages invalidate at once (pi: hideThinkingBlock, extended with
  ;; :stream-last). THINKING-ACTIVE-ATOM is the per-message live-thinking
  ;; flag: :stream-last shows its tail only while it is true. Callers
  ;; passing a nil label fall back to the default.
  ;; TEXT-ATOM/THINKING-ATOM are the data-layer content homes (Stage 5,
  ;; dsl.md §3.2): the chat history owns them on the message map and appends
  ;; swap them directly — no component-facing mutation API. When absent the
  ;; component creates its own (standalone constructors).
  (let [hidden-label (or hidden-label "Thinking...")
        comp (map->AssistantMessageComponent {:kind :assistant
                                              :text-atom (or text-atom (atom text))
                                              :thinking-text-atom (or thinking-atom (atom thinking))
                                              :output-pad-atom (or output-pad-atom
                                                                   (atom output-pad))
                                              :thinking-mode-atom (or thinking-mode-atom
                                                                      (atom thinking-mode))
                                              :thinking-active-atom (or thinking-active-atom
                                                                        (atom (boolean thinking-active?)))
                                              :hidden-label-atom (or hidden-label-atom
                                                                     (atom hidden-label))
                                              :streaming-atom (atom false)
                                              :tool-calls-atom (atom (boolean tool-calls?))
                                              :rendered-text-lines-atom (atom [])
                                              :rendered-thinking-lines-atom (atom [])
                                              :rendered-text-atom (atom nil)
                                              :rendered-thinking-atom (atom nil)
                                              :rendered-streaming-atom (atom nil)
                                              :rendered-mode-atom (atom nil)
                                              :rendered-thinking-active-atom (atom nil)
                                              :rendered-hidden-label-atom (atom nil)
                                              :rendered-pad-atom (atom nil)
                                              :rendered-theme-atom (atom nil)
                                              :last-render-width-atom (atom nil)
                                              :text-block-cache-atom (atom nil)
                                              :thinking-block-cache-atom (atom nil)
                                              :cache-atom (atom nil)})]
    ;; Lines are built lazily on the first render, at the width it is given:
    ;; eagerly reflowing here parsed every message at a fixed width 80 that
    ;; a first render at any other width cannot reuse, so replaying a large
    ;; session paid two full markdown parses per message.
    comp))

;; ─── Public API ────────────────────────────────────────────────────────
;; Content lives in the data-layer atoms (the message map's :text-atom/
;; :thinking-atom, shared into this record) — there is deliberately NO
;; component-facing text/thinking setter, appender or getter: the app swaps
;; and reads the atoms directly; track!'s watches invalidate the cache and
;; schedule the frame on every real change (dsl.md §3.2 Stage 5).

(defn assistant-message-set-streaming!
  "Set the streaming flag (pi: this.isStreaming) — true while the response
   streams, false once finalized. Flips the markdown transformers'
   :is-streaming context and forces a reflow on the next render."
  [comp streaming?]
  (reset! (:streaming-atom comp) (boolean streaming?)))

(defn assistant-message-set-tool-calls!
  "Flag the message as carrying tool calls (pi: hasToolCalls) — a finalized
   tool-call-only response renders nothing (its ToolExecutionComponents are
   the visuals), suppressing the '(no response)' placeholder. Flipping the
   atom invalidates the reactive cache like any other tracked input."
  [comp tool-calls?]
  (reset! (:tool-calls-atom comp) (boolean tool-calls?)))

(defn assistant-message-set-thinking-mode!
  "Set this message's thinking display mode (standalone construction and
   tests; the chat history flips the SHARED mode atom instead). Reflows
   eagerly when the message has rendered at least once."
  [comp mode]
  (reset! (:thinking-mode-atom comp) mode)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))
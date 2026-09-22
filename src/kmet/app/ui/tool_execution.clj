(ns kmet.app.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize).
   Quiet mode (:quiet display) short-circuits before any renderer runs:
   one dimmed title line (like hidden thinking), styled once and
   reading only name/args/is-error/ended-ness — custom renderers never run, so quiet
   can never be overridden and stays immutable."
  (:require [clojure.string :as str]
            [kmet.app.ui.subs :as s]
            [kmet.libs.reakt :as reakt]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.timers :as timers]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.utils :as utils]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.app.ui.image-block :as image-block]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.macros :refer [track! defcomponent]]))

;; ─── Renderer dispatch ─────────────────────────────────────────────────────
;; Built-in renderer functions live in kmet.app.ui.tool-renderers so supported
;; extensions can reuse them directly. One data table keyed by tool name holds
;; every built-in per-tool fact (:call / :result renderers, :shell mode); the
;; render method resolves custom override → builtin entry → default through a
;; single path instead of per-field case ladders.

(def ^:private builtin-renderers
  {"read"  {:call renderers/render-read-call
            :result renderers/render-read-result}
   "write" {:call renderers/render-write-call
            :result renderers/render-write-result}
   "edit"  {:call renderers/render-edit-call
            :result renderers/render-edit-result
            ;; pi: edit renders its own diff framing, no outer Box
            :shell :self}
   "bash"  {:call renderers/render-bash-call
            :result renderers/render-bash-result}
   "script" {:call renderers/render-script-call
             :result renderers/render-script-result}})

;; ─── Render context helper ─────────────────────────────────────────────────

(defn- last-call-component
  "Read the previous render-call component WITHOUT tracking (the render body
   resets this atom on every cache miss; a tracked read would self-invalidate
   the track! render cache and re-render every frame)."
  [comp]
  @(:last-call-component-atom comp))

(defn- last-result-component
  "Read the previous render-result component WITHOUT tracking (see
   last-call-component)."
  [comp]
  @(:last-result-component-atom comp))

(defn- last-image-children
  "Read the previous pass's image children WITHOUT tracking (see
   last-call-component): the render body replaces this atom on every cache
   miss, so a tracked read could never equal the stored value — the cache
   would miss every frame and the image children would be rebuilt (new kitty
   image ids, new allocations) on every render."
  [comp]
  @(:image-children-atom comp))

(defn- as-component
  "Normalize a renderer's output: an IComponent (the normal case), nil (the
   renderer contributes nothing), or a duck-typed {:render …} map — the
   ui-custom component contract, accepted here for symmetry. A duck-typed
   map is wrapped in a CustomDialogAdapter RECORD: the container/box dispose
   chain releases children through the IComponent protocol, which cannot see
   a raw map's :dispose key (and would throw)."
  [c]
  (if (and (map? c) (not (record? c)) (fn? (:render c)))
    (cda/map->CustomDialogAdapter
     {:render-fn (:render c)
      :handle-input-fn (:handle-input c)
      :invalidate-fn (:invalidate c)
      :dispose-fn (:dispose c)})
    c))

(defn- tool-execution-context
  "Build a ToolRenderContext map for the given component and last-component.
   SHOW-IMAGES is whether images render (the :show-images setting AND
   terminal support — pi: ToolRenderContext showImages, made effective).

   :last-component is the previous pass's renderer output. A renderer that
   keeps its own instance returns it back to REUSE it (the identical
   instance is left alone); anything else the renderer returns replaces the
   old output, which is then disposed — the same drop-disposes contract as
   every other component in the tree (tui.md §5.1) — so a renderer holding
   a dropped component across passes must not expect it to stay live."
  [comp last-comp show-images]
  {:display-mode (or (some-> (:tools-expanded-atom comp) deref) :collapsed)
   :args @(:args-atom comp)
   :tool-call-id @(:tool-call-id-atom comp)
   ;; invalidation schedules the frame itself (§3.4 hook) — extension
                 ;; renderers need no injected render callback
   :invalidate (fn [] (protocols/invalidate comp))
   :last-component last-comp
   :state @(:renderer-state-atom comp)
   :set-state! (fn [new-state]
                 (reset! (:renderer-state-atom comp) new-state))
   :cwd @(:cwd-atom comp)
   :execution-started (some? @(:started-at-atom comp))
   :args-complete @(:args-complete-atom comp)
   :details @(:details-atom comp)
   :is-partial (nil? @(:ended-at-atom comp))
   ;; quiet projects to collapsed for renderers (quiet never reaches them
   ;; — the branch below returns first — but a mid-pass mode flip must
   ;; not leak a truthy keyword into a boolean test either)
   :expanded (boolean (or @(:expanded-atom comp)
                          (= :expanded (some-> (:tools-expanded-atom comp) deref))))
   :show-images show-images
   :is-error @(:is-error-atom comp)})

;; ─── Record ────────────────────────────────────────────────────────────────
;; Pi matching: ToolExecutionComponent manages its own timing.
;; started-at is set on first set-content! call (execution start).
;; ended-at is set on set-error! or on final full-content set-content!.

(defn- quiet-one-line
  "Collapse a quiet title to a single visual line: newlines (with
   surrounding horizontal whitespace) become one space, remaining tabs
   expand to 3 spaces (like the renderers). truncate-to-width counts
   columns, not lines — an embedded newline (e.g. a multiline bash
   command) would wrap the quiet line into two."
  [s]
  (-> s
      (str/replace #"[ \t]*[\r\n]+[ \t\r\n]*" " ")
      (str/replace "\t" "   ")))

(defn- quiet-title-for
  "Resolve the quiet title body for NAME/ARGS: the tool's :title fn (pure
   plain-text data, nil-safe over partial streaming args), or the tool
   name when the tool defines none or it returns blank. The result is
   collapsed to one line (see quiet-one-line). Never throws — a
   throwing title degrades to the name (a broken title must not take the
   frame down)."
  [title-fn name args]
  (let [t (try (when (fn? title-fn) (title-fn args)) (catch Exception _ nil))]
    (if (and (string? t) (seq (str/trim t))) (quiet-one-line t) (quiet-one-line name))))

(defcomponent ToolExecutionComponent :tool
              [name-atom args-atom content-atom is-error-atom
               output-pad-atom expanded-atom
               tools-expanded-atom ;; chat-history-wide display-mode atom (:collapsed | :expanded | :quiet), or nil (unlinked)
               custom-render-call-atom custom-render-result-atom title-fn-atom
               started-at-atom ended-at-atom
               truncation-atom tool-call-id-atom
               details-atom        ;; result :details map (pi: result.details), e.g. edit diff
               args-complete-atom
               render-shell-atom   ;; pi: ToolDefinition.renderShell — :self renders without the outer Box
               image-data-atom       ;; vector of {:data str :mime-type str}
               image-children-atom   ;; the previous pass's image children (disposed on rebuild)
               last-call-component-atom   ;; component from previous render-call
               last-result-component-atom ;; component from previous render-result
               renderer-state-atom        ;; persistent state for custom renderers
               cwd-atom                ;; current working directory
               box             ;; outer Box (padding + bg)
               inner-container ;; Container for call/result children
               cache-atom]     ;; render cache (track!)
  (render [this width]
    (track! this width
      (let [mode (or (some-> tools-expanded-atom reakt/tracked-deref) :collapsed)
            quiet? (= :quiet mode)
            ;; tracked read of the shared palette sub: a theme switch
            ;; re-derives this cache exactly once (Stage 5, dsl.md §3.2)
            theme (deref s/theme-sub)
            is-error @is-error-atom
            ;; tracked ended-ness only (never the timestamp): one re-render
            ;; on completion flips ... to done/(!), then immutable again
            running? (nil? @ended-at-atom)
            ;; tracked read: the pad atom is shared by every message the chat
            ;; history owns — one reset! re-pads them all (the box setter
            ;; no-ops when the value is unchanged)
            output-pad (deref output-pad-atom)
            _ (box/box-set-padding-x! @box output-pad)
            name @name-atom
            args @args-atom
            title-fn @title-fn-atom]
      ;; Quiet short-circuit — BEFORE any renderer runs (custom renderers
      ;; never execute in quiet: it cannot be overridden). Reads only
      ;; name/args/is-error/ended-ness/output-pad/theme/mode — no content,
      ;; details, truncation, images, timing, cwd or renderer state — so a
      ;; quiet
      ;; line never re-invalidates and stays immutable in scrollback.
        (if quiet?
          (do (let [prev-call (last-call-component this)
                    prev-result (last-result-component this)
                    ;; identity-deduped (records compare field-wise): a
                    ;; renderer returning one instance for both slots must
                    ;; not be disposed twice
                    obsolete (if (and prev-call prev-result
                                      (identical? prev-call prev-result))
                               [prev-call]
                               (remove nil? [prev-call prev-result]))]
                (doseq [c obsolete]
                  (cda/dispose-component! c)))
              (doseq [c (last-image-children this)]
                (cda/dispose-component! c))
              (reset! (:last-call-component-atom this) nil)
              (reset! (:last-result-component-atom this) nil)
              (reset! (:image-children-atom this) [])
              (container/container-clear @(:inner-container this))
              ;; the bash Elapsed ticker is cancelled without a tracked read,
              ;; so quiet never resubscribes to renderer state
              (let [[state] (swap-vals! (:renderer-state-atom this) dissoc :timer-id)]
                (when-let [id (:timer-id state)]
                  (timers/cancel! id)))
              (let [content-width (max 1 (- width (* 2 output-pad)))
                    raw (str (cond is-error "(!) " running? "... ") (quiet-title-for title-fn name args))
                    line (str (apply str (repeat output-pad \space))
                              (theme/italic (theme/fg theme :thinking-text
                                                      ;; plain text first, then style once:
                                                      ;; styling survives truncation, but
                                                      ;; plain-first keeps width math simple
                                                      (utils/truncate-to-width raw content-width "..."))))]
                [line]))
          (let [container @inner-container
                last-call-component-atom (:last-call-component-atom this)
                last-result-component-atom (:last-result-component-atom this)
                image-children-atom (:image-children-atom this)
            ;; tracked non-quiet inputs: everything the collapsed/expanded
            ;; forms read (the quiet branch above reads none of these)
                content @content-atom
                expanded? (boolean (or @expanded-atom (= :expanded mode)))
                started-at @started-at-atom
                ended-at @ended-at-atom
            ;; tracked read of the shared image settings: a /settings change
            ;; (show-images / image-width-cells) re-renders every tool box
                image-settings (deref s/image-settings-sub)
                show-images? (image-block/images-enabled? image-settings)
      ;; Re-check empty — only when no call component rendered and no result
                builtin (get builtin-renderers name)
                render-call-fn (or @custom-render-call-atom
                                   (:call builtin)
                                   renderers/render-default-call)
                render-result-fn (or @custom-render-result-atom
                                     (:result builtin)
                                     renderers/render-default-result)
                render-shell (or @render-shell-atom (:shell builtin) :default)
                content-width (max 1 (- width (* 2 output-pad)))
            ;; Previous pass's renderer outputs: passed to the renderers as
            ;; :last-component so an extension renderer may reuse its own
            ;; instance, and disposed below unless the renderer returned the
            ;; same one back (renderers may return IComponent or nil).
                prev-call (last-call-component this)
                call-context (tool-execution-context this prev-call show-images?)
                call-comp (as-component (render-call-fn name args theme content-width call-context))
                _ (reset! last-call-component-atom call-comp)
                truncation @truncation-atom
                prev-result (last-result-component this)
                result-context (tool-execution-context this prev-result show-images?)
                result-comp (as-component (render-result-fn content is-error theme content-width expanded? started-at ended-at truncation result-context))
                _ (reset! last-result-component-atom result-comp)
                image-data @image-data-atom
                prev-image-children (last-image-children this)
            ;; identity-deduped: a renderer returning one instance for both
            ;; slots must not be disposed twice
                obsolete (reduce (fn [acc prev]
                                   (if (or (nil? prev)
                                           (identical? prev call-comp)
                                           (identical? prev result-comp)
                                           (some #(identical? % prev) acc))
                                     acc
                                     (conj acc prev)))
                                 []
                                 [prev-call prev-result])]
      ;; Pi: hide component when no call/render content and no images
            (if (and (nil? call-comp) (nil? result-comp) (not (seq image-data)))
              (do
            ;; nothing renders — drop the dropped children (a stale child
            ;; would keep its track! watches alive; the renderers may return
            ;; duck-typed maps, so disposal goes through dispose-component!)
                (doseq [c obsolete]
                  (cda/dispose-component! c))
                (doseq [c prev-image-children]
                  (cda/dispose-component! c))
                (reset! image-children-atom [])
                (container/container-clear container)
                [])
              (do
          ;; Build inner container
                (container/container-clear container)
                (doseq [c obsolete]
                  (cda/dispose-component! c))
                (when call-comp
                  (container/container-add-child container call-comp))
                (when result-comp
                  (container/container-add-child container result-comp))
          ;; Build image components from raw data (Pi: spacer + ImageComponent).
          ;; image-block renders the terminal image or, when display is off /
          ;; unsupported, the styled text indicator (pi: getTextOutput).
          ;; The previous pass's image children are disposed with the other
          ;; dropped children: an ImageBlock subscribes to the
          ;; image-settings/theme subs, so a dropped instance would keep its
          ;; track! watches alive forever (zombie watchers, tui.md §5.1).
                (let [children (into []
                                     (mapcat (fn [img]
                                               [(spacer/make-spacer 1)
                                                (image-block/make-image-block
                                                 (:data img) (:mime-type img)
                                                 :fallback-style (fn [thm s]
                                                                   (theme/fg thm :tool-output s)))]))
                                     image-data)]
                  (doseq [c prev-image-children]
                    (cda/dispose-component! c))
                  (reset! image-children-atom children)
                  (doseq [c children]
                    (container/container-add-child container c)))
          ;; Pi: render-shell :self skips outer Box (tool renders its own framing)
                (if (= :self render-shell)
                  (let [content-lines (protocols/render container width)]
                    (if (seq content-lines)
                      (into [""] content-lines)
                      []))
                  (let [bg-key (cond
                           ;; Pi: isPartial=true until result arrives; ended-at=nil = pending
                                 (nil? ended-at) :tool-pending-bg
                                 is-error :tool-error-bg
                                 :else :tool-success-bg)
                        _ (box/box-set-bg-fn @box #(theme/bg theme bg-key %))
                        box-lines (protocols/render @box width)]
                    (if (seq box-lines)
                      (into [""] box-lines)
                      []))))))))))
  (invalidate [_this]
    (protocols/invalidate @box))
  (dispose [_this]
    ;; Idempotent: cancel the running-tool repaint timer (§6.1) — a
    ;; component dropped from the chat (e.g. /new while a tool runs) must
    ;; not keep a zombie tick invalidating forever. swap-vals! makes the
    ;; read+remove atomic: a render racing dispose cannot re-arm a timer
    ;; between the deref and the dissoc. timers/cancel! is idempotent.
    (let [[state] (swap-vals! (:renderer-state-atom _this) dissoc :timer-id)]
      (when-let [id (:timer-id state)]
        (timers/cancel! id)))
    (protocols/dispose @box)
    ;; image children still outside the container (the hide path cleared it)
    ;; — disposal is idempotent, so the container-owned ones are harmless to
    ;; touch again
    (doseq [c @(:image-children-atom _this)]
      (cda/dispose-component! c))
    (reset! (:image-children-atom _this) [])))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  "THEME is no longer taken: the box background subscribes to
   ui.subs/theme-sub and follows palette changes live (Stage 5)."
  [& {:keys [name args content is-error output-pad output-pad-atom expanded? tools-expanded-atom render-call-fn render-result-fn title-fn truncation details cwd render-shell]
      :or {name "" args {} content "" is-error false
           output-pad 1 expanded? false truncation nil details nil
           cwd (or (System/getProperty "user.dir") ".")}}]
  (let [inner-container (container/make-container)
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        pad-atom (or output-pad-atom (atom output-pad))
        b (box/make-box @pad-atom 1 #(theme/bg (theme/get-current-theme) bg-key %))]
    (box/box-add-child b inner-container)
    (map->ToolExecutionComponent {:kind :tool
                                  :name-atom (atom name)
                                  :args-atom (atom args)
                                  :content-atom (atom content)
                                  :is-error-atom (atom is-error)
                                  :output-pad-atom pad-atom
                                  :expanded-atom (atom expanded?)
                                  :tools-expanded-atom tools-expanded-atom
                                  :started-at-atom (atom nil)
                                  :ended-at-atom (atom nil)
                                  :truncation-atom (atom truncation)
                                  :tool-call-id-atom (atom nil)
                                  :details-atom (atom details)
                                  :args-complete-atom (atom false)
                                  :render-shell-atom (atom render-shell)
                                  :custom-render-call-atom (atom render-call-fn)
                                  :custom-render-result-atom (atom render-result-fn)
                                  :title-fn-atom (atom title-fn)
                                  :image-data-atom (atom [])
                                  :image-children-atom (atom [])
                                  :last-call-component-atom (atom nil)
                                  :last-result-component-atom (atom nil)
                                  :renderer-state-atom (atom {})
                                  :cwd-atom (atom cwd)
                                  :box (atom b)
                                  :inner-container (atom inner-container)
                                  :cache-atom (atom nil)})))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-error!
  "Mark errored; pi: error marks execution ended (stamps ended-at once) and
   clears the elapsed ticker on completion — a component dropped from the
   chat (e.g. /new while a tool runs) must not keep a zombie interval
   invalidating forever."
  [comp is-error]
  (reset! (:is-error-atom comp) is-error)
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  (let [state @(:renderer-state-atom comp)]
    (when-let [id (:timer-id state)]
      (timers/cancel! id))
    (when (contains? state :timer-id)
      (reset! (:renderer-state-atom comp) (dissoc state :timer-id)))))

(defn tool-execution-mark-execution-started!
  "Mark that tool execution has started (Pi: markExecutionStarted()).
   Sets started-at so pending background and timer activate from tool start
   rather than waiting for first content delivery. This is the ONLY thing
   that may stamp started-at: content updates are plain resets on the
   :content-atom — if they stamped too, replayed results (restore / -c)
   would show a fabricated \"Took 0.0s\" (pi renders replayed tools without
   a duration: startedAt stays undefined, updateResult never touches it)."
  [comp]
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis))))

(defn tool-execution-set-args-complete!
  "Mark that all tool arguments have been received.
   Pi: setArgsComplete() — affects render context :args-complete."
  [comp]
  (reset! (:args-complete-atom comp) true))

(defn tool-execution-set-images!
  "Set image content blocks for this tool execution.
   images — vector of {:data str :mime-type str}
   Stores raw image data; ImageComponents are built at render time."
  [comp images]
  (let [image-data (mapv (fn [img] {:data (:data img) :mime-type (:mime-type img)}) images)]
    (reset! (:image-data-atom comp) image-data)))


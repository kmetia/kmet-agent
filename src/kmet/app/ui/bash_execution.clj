(ns kmet.app.ui.bash-execution
  "BashExecutionComponent — TUI component for displaying !/!! bash command execution
   with streaming output, expand/collapse, animated loader, duration, and status info.
   Port of pi's BashExecutionComponent.

   Hiccup implementation: a thin uncached record owns a state atom and a
   mounted fn-component root. The body re-derives only when the state (or
   the shared expansion toggle / theme) changes; the spliced spinner leaf
   re-renders fresh on every driven frame, so an 80ms frame driver animates
   it like pi's Loader setInterval while output chunks stream through the
   reaction."
  (:require [kmet.libs.reakt :as r]
            [kmet.tui.border :as border]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.macros :as macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.timers :as timers]
            [kmet.tui.utils :as u]
            [kmet.tui.components.spinner :as spinner]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.app.ui.subs :as s]
            [clojure.string :as str]))

;; ─── Preview line limit ────────────────────────────────────────────────────
(def ^:private PREVIEW-LINES 20)

;; ─── Frame driver cadence ──────────────────────────────────────────────────
(def ^:private FRAME-INTERVAL-MS 80) ;; pi Loader: 80ms setInterval

;; ─── Tree helpers ──────────────────────────────────────────────────────────

(defn- header-tree
  "Command header element."
  [t color-key command]
  [:text {:padding-x 1 :padding-y 0}
   (theme/fg t color-key (theme/bold (str "$ " command)))])

(defn- output-tree
  "Output element: full output when expanded, last PREVIEW-LINES visual
   lines otherwise. Nil when there is nothing to show (skipped by the DSL).
   WIDTH is the content width (borders excluded); the preview truncates at
   the Text inner width (padding-x 1 each side) so the element's own
   wrapping cannot push it past PREVIEW-LINES."
  [t display-lines preview-lines expanded? width]
  (when (seq display-lines)
    (if expanded?
      (let [styled (mapv #(theme/fg t :tool-output %) display-lines)]
        [:text {:padding-x 1 :padding-y 0}
         (str "\n" (str/join "\n" styled))])
      (let [inner-width (max 1 (- width 2))
            styled-preview (mapv #(theme/fg t :tool-output %) preview-lines)
            preview-text (str "\n" (str/join "\n" styled-preview))
            visual (:visual-lines
                    (u/truncate-to-visual-lines preview-text PREVIEW-LINES inner-width))]
        [:text {:padding-x 1 :padding-y 0}
         (str "\n" (str/join "\n" visual))]))))

(defn- status-tree
  "Status element for a finished run: hidden-lines hint + exit status +
   Took duration + truncation warning. Nil when there is nothing to show
   (only called for finished runs, so ended-at is always set)."
  [t status exit-code hidden-line-count expanded?
   truncated truncation full-output-path started-at ended-at]
  (when (not= status :running)
    (let [hint (when (pos? hidden-line-count)
                 (if expanded?
                   (str (theme/fg t :muted "(")
                        (app-kb/key-hint "app.tools.expand" "to toggle")
                        (theme/fg t :muted ")"))
                   (str (theme/fg t :muted
                                  (str "... " hidden-line-count " more lines ("))
                        (app-kb/key-hint "app.tools.expand" "to toggle")
                        (theme/fg t :muted ")"))))
          exit-part (case status
                      :cancelled (theme/fg t :warning "(cancelled)")
                      :error (theme/fg t :error (str "(exit " exit-code ")"))
                      nil)
          elapsed-ms (max 0 (- ended-at started-at))
          duration (theme/fg t :muted
                             (str "Took "
                                  (format "%.1f" (float (/ elapsed-ms 1000)))
                                  "s"))
          was-truncated (or truncated (:truncated truncation))
          trunc-part (when (and was-truncated full-output-path)
                       (theme/fg t :warning
                                 (str "Output truncated. Full output: "
                                      full-output-path)))
          parts (vec (remove nil? [hint exit-part duration trunc-part]))]
      (when (seq parts)
        [:text {:padding-x 1 :padding-y 0}
         (str "\n" (str/join "\n" parts))]))))

(defn- elapsed-tree
  "Live elapsed-time element for a running command: muted Elapsed Xs
   under the spinner, ticking once a second via the :now-ms stamp."
  [t started-at now-ms]
  (let [elapsed-ms (max 0 (- (or now-ms started-at) started-at))]
    [:text {:padding-x 1 :padding-y 0}
     (str "\n" (theme/fg t :muted
                         (str "Elapsed "
                              (format "%.1f" (float (/ elapsed-ms 1000)))
                              "s")))]))

(defn- stop-tickers!
  "Cancel the 80ms frame driver and the 1s elapsed ticker, if present.
   Shared by set-complete! and dispose. timers/cancel! is idempotent, so a
   double stop is harmless."
  [comp]
  (doseq [k [:ticker-id-atom :elapsed-ticker-id-atom]]
    (when-let [id @(get comp k)]
      (timers/cancel! id)
      (reset! (get comp k) nil))))

;; ─── Fn-component body ─────────────────────────────────────────────────────
;; Reads the state atom (plus the expansion toggle and theme) through
;; tracked derefs, so swaps re-derive the tree exactly once and an idle UI
;; re-derives nothing. The spinner splices foreign — reconcile keeps its
;; identity across passes and never disposes it.

(defn- bash-body
  "The bash transcript as a hiccup tree over STATE-ATOM. NOW-ATOM
   re-stamps once a second while running (pi: the Loader interval) so
   the Elapsed line ticks during silent runs. SPINNER-COMP is the
   long-lived spinner record, spliced while running."
  [state-atom now-atom expanded-atom tools-expanded-atom spinner-comp]
  (fn [_props]
    (let [st (r/tracked-deref state-atom)
          command (:command st)
          output-lines (:output-lines st)
          status (:status st)
          exit-code (:exit-code st)
          truncation (:truncation st)
          full-output-path (:full-output-path st)
          started-at (:started-at st)
          ended-at (:ended-at st)
          exclude? (:exclude? st)
          now-ms (r/tracked-deref now-atom)
          ;; user-invoked (!/!!) executions are exempt from quiet —
          ;; it projects to collapsed here, like the info banner and
          ;; loaded resources
          expanded? (boolean (or (r/tracked-deref expanded-atom)
                                 (= :expanded (when tools-expanded-atom
                                                (r/tracked-deref tools-expanded-atom)))))
          t (r/tracked-deref s/theme-sub)
          color-key (if exclude? :dim :bash-mode)
          full-output (str/join "\n" output-lines)
          trunc-result (bash-exec/truncate-tail full-output)
          content (:content trunc-result)
          truncated (:truncated trunc-result)
          display-lines (if (seq content) (str/split-lines content) [])
          preview-lines (take-last PREVIEW-LINES display-lines)
          hidden-line-count (- (count display-lines) (count preview-lines))
          ;; *width* is always bound inside a ComponentFn render (tui.md
          ;; §2.5); the fallback only covers direct calls in tests.
          width (or hiccup/*width* 80)]
      [:container {}
       (header-tree t color-key command)
       (output-tree t display-lines preview-lines expanded? width)
       (if (= status :running)
         [:container {}
          spinner-comp
          (elapsed-tree t started-at now-ms)]
         (status-tree t status exit-code hidden-line-count expanded?
                      truncated truncation full-output-path
                      started-at ended-at))])))

;; ─── Record ────────────────────────────────────────────────────────────────
;; Transparent wrapper (tui.md section 3.2): uncached, so the spinner leaf
;; paints on every driven frame. The memoization boundary is the root's
;; reaction. Pi's component keeps the bordered box too (DynamicBorder top
;; + content + DynamicBorder bottom); the box frame is chrome around the
;; running spinner, not a pending/error background like tool calls have.

(defcomponent BashExecutionComponent :bash
              [state-atom
               now-atom
               expanded-atom tools-expanded-atom
               ;; Set-once children in atoms, like sibling message
               ;; components (:box holds (atom b)) — never swapped after
               ;; construction, but uniformly dereferenced.
               spinner-comp
               root
               ticker-id-atom
               elapsed-ticker-id-atom
               done-atom
               ;; resolved kmet.tui.border set for the chrome (R5), or nil
               ;; for :none (no frame at all — content lines only)
               border-atom]
  (render [_this width]
    (let [st @state-atom
          thm (theme/get-current-theme)
          color-key (if (:exclude? st) :dim :bash-mode)
          border-color (fn [s] (theme/fg thm color-key s))
          b @border-atom
          cw (max 1 (- width 2))
          content-lines (protocols/render @root cw)
          pad-line (fn [line]
                     (let [vis (u/visible-width line)]
                       (if (>= vis cw)
                         line
                         (str line (apply str (repeat (- cw vis) \space))))))]
      (if b
        (conj (into [(border-color (border/top-line b width))]
                    (map #(border/mid-line b (pad-line %) border-color)
                         content-lines))
              (border-color (border/bottom-line b width)))
        content-lines)))
  (dispose [_this]
    ;; Idempotent: safe to call twice (chat-history-clear! disposes message
    ;; components, and the record may also be disposed directly).
    (reset! done-atom true)
    (stop-tickers! _this)
    (protocols/dispose @root)))

;; ─── Construction ─────────────────────────────────────────────────────────

(defn make-bash-execution
  "Create a BashExecutionComponent with animated spinner. Styling follows
   the shared theme subscription live — no theme is taken.
   Options:
     :command                — the shell command string
     :exclude-from-context?  — boolean (!! vs !)
     :tools-expanded-atom    — chat-wide expansion toggle atom, or nil
     :border                 — a kmet.tui.border set for the frame
                               (default :normal; :ascii draws it with -|+,
                               :hidden keeps the footprint without ink,
                               :none drops the frame entirely), resolved
                               here so an unknown style throws at
                               construction"
  [& {:keys [command exclude-from-context? tools-expanded-atom border]
      :or {command "" exclude-from-context? false}}]
  (let [state-atom (atom {:command command
                          :output-lines []
                          :status :running
                          :exit-code nil
                          :truncation nil
                          :full-output-path nil
                          :started-at (System/currentTimeMillis)
                          :ended-at nil
                          :exclude? (boolean exclude-from-context?)})
        expanded-atom (atom false)
        color-key (if exclude-from-context? :dim :bash-mode)
        cancel-key (or (tui-kb/key-text (tui-kb/get-global-keybindings) "app.interrupt")
                       "Esc")
        ;; Long-lived spinner, spliced foreign into the tree: identity (and
        ;; the animation start) survives body re-derives. Color fns read the
        ;; current theme at render time so palette switches apply instantly.
        sp (spinner/make-spinner
            :text (str "Running... (" cancel-key " to cancel)")
            :active true
            :prefix ""
            :interval-ms FRAME-INTERVAL-MS
            :spinner-color-fn (fn [x] (theme/fg (theme/get-current-theme) color-key x))
            :message-color-fn (fn [x] (theme/fg (theme/get-current-theme) :muted x)))
        now-atom (atom (System/currentTimeMillis))
        root (hiccup/root (bash-body state-atom now-atom expanded-atom
                                     tools-expanded-atom sp))
        done-atom (atom false)
        comp (map->BashExecutionComponent
              {:kind :bash
               :state-atom state-atom
               :now-atom now-atom
               :expanded-atom expanded-atom
               :tools-expanded-atom tools-expanded-atom
               :spinner-comp (atom sp)
               :root (atom root)
               :ticker-id-atom (atom nil)
               :elapsed-ticker-id-atom (atom nil)
               :done-atom done-atom
               :border-atom (atom (border/resolve border))})]
    ;; Pi Loader parity: drive frames at 80ms while :running, on the loop's
    ;; own timer registry (§6.1) instead of a parked thread. The root's
    ;; reaction stays idle (no body re-runs) — each driven frame just
    ;; re-renders the uncached spinner leaf, so output chunks and the
    ;; animation paint promptly even with no output. The thunk self-cancels
    ;; on completion; set-complete!/dispose cancel it promptly, and the
    ;; done check stops the 80ms wakeups once the component leaves the chat
    ;; (e.g. /new while a run is in flight) instead of poking a cleared
    ;; frame hook until the bash process exits.
    (reset! (:ticker-id-atom comp)
            (timers/every! FRAME-INTERVAL-MS
                           (fn []
                             (if (or @done-atom (not= :running (:status @state-atom)))
                               (timers/cancel! @(:ticker-id-atom comp))
                               (macros/schedule-frame!)))))
    ;; Pi renderResult parity: re-stamp :now-ms once a second while
    ;; running so the Elapsed line ticks during silent runs (the body
    ;; reads it tracked, so only these stamps re-derive the tree).
    ;; Self-cancels on completion; set-complete!/dispose cancel it.
    (reset! (:elapsed-ticker-id-atom comp)
            (timers/every! 1000
                           (fn []
                             (if (or @done-atom (not= :running (:status @state-atom)))
                               (timers/cancel! @(:elapsed-ticker-id-atom comp))
                               (reset! now-atom (System/currentTimeMillis))))))
    comp))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn bash-execution-append-output!
  "Append a chunk of output text. Handles incomplete line continuation
   when a chunk doesn't end with a newline (matching pi's appendOutput).
   The state swap dirties the root's reaction, which schedules the frame."
  [comp chunk]
  (let [clean (-> chunk
                  (str/replace #"\r\n" "\n")
                  (str/replace #"\r" "\n"))
        ;; Pi: split(\"\n\") keeps a trailing \"\" for newline-terminated chunks;
        ;; split-lines drops it, which breaks line-continuation detection.
        new-lines (str/split clean #"\n" -1)]
    (swap! (:state-atom comp)
           (fn [st]
             (let [current (:output-lines st)]
               (assoc st :output-lines
                      (if (and (seq current) (seq new-lines))
                        ;; Pi: outputLines[last] += newLines[0];
                        ;; outputLines.push(...newLines.slice(1))
                        (let [base (vec (butlast current))
                              merged (str (last current) (first new-lines))]
                          (into (conj base merged) (rest new-lines)))
                        (into (vec current) new-lines))))))))

(defn bash-execution-set-complete!
  "Mark the bash command as complete.
   Stops the spinner, records exit code and duration.

   exit-code     — int or nil (nil if cancelled)
   cancelled?    — boolean
   :truncation   — {:total-lines int :shown-lines int ...} or nil
   :full-output-path — string or nil"
  [comp exit-code cancelled? & {:keys [truncation full-output-path]}]
  (swap! (:state-atom comp)
         (fn [st]
           (cond-> (assoc st
                          :ended-at (System/currentTimeMillis)
                          :exit-code exit-code
                          :status (cond
                                    cancelled? :cancelled
                                    (and exit-code (not= exit-code 0)) :error
                                    :else :complete))
             truncation (assoc :truncation truncation)
             full-output-path (assoc :full-output-path full-output-path))))
  ;; Stop the spinner and both tickers. done-atom stays false: a
  ;; second set-complete! is not a legal call (the run is over), and the
  ;; status checks already exit the drivers.
  (when-let [sp @(:spinner-comp comp)]
    (spinner/spinner-stop! sp))
  (stop-tickers! comp))

(defn dispose-pending-bash!
  "Dispose pending bash components parked outside the chat (e.g. /new while
   the agent streams: the comps live in the pending-messages container, not
   in the chat history, so chat-history-clear! never sees them). Stops their
   80ms frame drivers — otherwise they fire into the TUI until the bash
   process exits. Idempotent; individual dispose failures are swallowed."
  [comps]
  (doseq [c comps]
    (try (protocols/dispose c) (catch Exception _))))

(ns kmet.tui.components.alt-screen-flash
  "AltScreenFlashContainer — transient messages composited by the host
   renderer (pi: components/alt-screen-flash.ts). Each flash renders as a
   single inverse-video line (message padded with one space each side,
   truncated ANSI-aware to the viewport width). Entries auto-expire after
   their duration and the host re-renders. The render loop composites flash
   lines over the screen window (right-aligned at the bottom, pi:
   compositeFlashes); the container itself just holds and expires entries."
  (:require
   [kmet.tui.macros :refer [defcomponent]]
   [kmet.tui.timers :as timers]
   [kmet.tui.utils :as u]
   [kmet.tui.theme :as theme]))

(def ^:private DEFAULT-DURATION-MS 1000)

(declare alt-screen-flash-dispose!)

(defcomponent AltScreenFlashContainer nil [request-render-fn-atom entries-atom next-id-atom]
  (render [_this width]
    (mapv (fn [entry]
            (let [message (u/truncate-to-width (str " " (:message entry) " ") width "")]
              (theme/inverse message)))
          @entries-atom))
  (dispose [this] (alt-screen-flash-dispose! this)))

(defn- request-render!
  "Call the host's re-render callback when present."
  [this]
  (when-let [f @(:request-render-fn-atom this)]
    (f)))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-alt-screen-flash
  "Create an AltScreenFlashContainer. REQUEST-RENDER (nil = none) is called
   whenever a flash appears or expires so the host can repaint."
  [request-render]
  (map->AltScreenFlashContainer {:request-render-fn-atom (atom request-render)
                                 :entries-atom (atom [])
                                 :next-id-atom (atom 0)}))

(defn alt-screen-flash-set-request-render!
  "Replace the host's re-render callback (nil = none). The callback is
   configuration, not state — pending entries and their expiry timers
   survive, which is what the hiccup :apply path patches through (tui.md
   §2.2) instead of rebuilding the container over a changed :request-render."
  [this f]
  (reset! (:request-render-fn-atom this) f))

(defn alt-screen-flash!
  "Show a transient inverse-video message for DURATION-MS (default 1000),
   then remove it and request a re-render (pi: flash). Expiry rides the
   loop-owned timer registry (§6.1), so it fires on the loop thread while
   the loop runs and cannot outlive the session (tui-stop cancels all
   timers)."
  [this message & {:keys [duration-ms]}]
  (let [duration-ms (or duration-ms DEFAULT-DURATION-MS)
        id (swap! (:next-id-atom this) inc)
        timer-id (timers/after! duration-ms
                                (fn []
                                  (swap! (:entries-atom this)
                                         (fn [es] (vec (remove #(= id (:id %)) es))))
                                  (request-render! this)))]
    (swap! (:entries-atom this) conj {:id id :message message :timer-id timer-id})
    (request-render! this)
    nil))

(defn alt-screen-flash-dispose!
  "Clear all pending flashes immediately (pi: dispose). Cancels each
   pending expiry — a flash whose container is gone must not fire a
   zombie render-request. timers/cancel! is idempotent."
  [this]
  (doseq [{:keys [timer-id]} @(:entries-atom this)]
    (when timer-id (timers/cancel! timer-id)))
  (reset! (:entries-atom this) [])
  (request-render! this)
  nil)

;; Full-document render benchmark: replay a stored session and time the
;; cold/warm full renders plus the global-reflow interactions, or (mode
;; `roles`) the per-role cold split of every message component.
;;
;; Usage:
;;   bb   scripts/kmet_render_bench.clj <session-file> [width] [roles]
;;   jolt scripts/kmet_render_bench.clj <session-file> [width] [roles]
;;
;; The session file is a `~/.kmet/sessions/` .ednl (or its path); width
;; defaults to 100. Numbers are wall-clock and single-run by design — this is
;; the harness perf.md §10/§12's tables were measured with, not a test.
(ns kmet-render-bench
  (:require [clojure.string :as str]
            [kmet.app.session :as session]
            [kmet.app.ui :as ui]
            [kmet.modes.interactive :as inter]
            [kmet.tui.core :as core]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(defn- now [] (System/nanoTime))
(defn- ms [n] (double (/ n 1e6)))

(defn- timed
  "Run F, print `LABEL: <ms> ms[, <lines> lines]`, return F's value."
  [label f]
  (let [t (now)
        v (f)]
    (if (coll? v)
      (println (format "%-22s %9.1f ms, %d lines" label (ms (- (now) t)) (count v)))
      (println (format "%-22s %9.1f ms" label (ms (- (now) t)))))
    v))

(defn- render-all [ch width] (core/render ch width))

(defn- roles-profile
  "Time every message component's cold render, summed per role and — for
   tool messages — per tool name: the profile behind the full render,
   before any full pass warms caches."
  [ch width]
  (let [{:keys [roles tools]}
        (reduce (fn [acc m]
                  (let [t (now)
                        ls (protocols/render (:component m) width)
                        d (ms (- (now) t))
                        chars (reduce + 0 (map count ls))]
                    (-> acc
                        (update-in [:roles (:role m) :ms] (fnil + 0.0) d)
                        (update-in [:roles (:role m) :lines] (fnil + 0) (count ls))
                        (update-in [:roles (:role m) :chars] (fnil + 0) chars)
                        (cond->
                          (= :tool (:role m))
                          (-> (update-in [:tools (:name m) :ms] (fnil + 0.0) d)
                              (update-in [:tools (:name m) :n] (fnil inc 0))
                              (update-in [:tools (:name m) :lines] (fnil + 0) (count ls))
                              (update-in [:tools (:name m) :chars] (fnil + 0) chars))))))
                {:roles {} :tools {}}
                @(:messages-atom ch))]
    (doseq [[role s] (sort-by (comp - :ms val) roles)]
      (println (format "  %-12s %9.1f ms  %6d lines  %8d chars"
                       (str (name role) ":") (:ms s) (:lines s) (:chars s))))
    (println (format "  %-12s %9.1f ms" "TOTAL:" (reduce + (map :ms (vals roles)))))
    (when (seq tools)
      (println "  tools:")
      (doseq [[n s] (sort-by (comp - :ms val) tools)]
        (println (format "    %-10s %9.1f ms  n=%-4d lines=%-6d chars=%d"
                         (str n) (:ms s) (:n s) (:lines s) (:chars s)))))))

(defn -main [& args]
  (let [session-file (first args)
        width (Integer/parseInt (or (second args) "100"))
        mode (or (nth args 2 nil) "render")
        host (if (resolve 'clojure.core/*jolt-version*) "jolt" "bb")]
    (when-not session-file
      (println "usage: kmet_render_bench <session-file> [width] [roles]")
      (System/exit 1))
    (println (str "host: " host
                  "  session: " (last (str/split session-file #"/"))
                  "  width: " width
                  "  mode: " mode))
    (let [sess (session/load-session session-file)
          ch (ui/make-chat-history :tool-display-mode :expanded)
          cs (inter/map->CoreState {:chat-history ch})]
      (timed "replay" (fn [] ((var inter/replay-branch!) cs sess) nil))
      (if (= mode "roles")
        (roles-profile ch width)
        (do
          (timed "render #1 (cold)" #(render-all ch width))
          (timed "render #2 (warm)" #(render-all ch width))
          (timed "Ctrl+O -> collapsed"
                 (fn [] (ui/chat-history-set-tool-display-mode! ch :collapsed)
                        (render-all ch width)))
          (timed "Ctrl+O -> expanded"
                 (fn [] (ui/chat-history-set-tool-display-mode! ch :expanded)
                        (render-all ch width)))
          ;; Theme switch: a modified copy of the live theme, so the swap is
          ;; a real change whatever theme is configured.
          (let [cur (theme/get-current-theme)
                other (assoc cur :name "probe-theme" :text "#ff0000")]
            (timed "theme switch" (fn [] (theme/set-theme-instance! other)
                                    (render-all ch width)))
            (timed "theme switch back" (fn [] (theme/set-theme-instance! cur)
                                         (render-all ch width))))
          ;; Output-pad change: every boxed message re-wraps.
          (timed "output-pad 1 -> 2"
                 (fn [] (reset! (:output-pad-atom ch) 2) (render-all ch width)))
          (timed "output-pad 2 -> 1"
                 (fn [] (reset! (:output-pad-atom ch) 1) (render-all ch width)))
          (timed "warm frame" #(render-all ch width)))))))

(apply -main *command-line-args*)

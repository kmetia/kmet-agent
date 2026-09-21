;; Primitive microbenchmarks behind perf.md's tables (§3.3, §5, §9.1, §9.2,
;; §6.6, §11, §12). Same script on both hosts:
;;
;;   bb   scripts/kmet_perf_bench.clj              # full sweep
;;   jolt scripts/kmet_perf_bench.clj
;;   bb   scripts/kmet_perf_bench.clj cold-regex   # fresh-process first re-find
;;   bb   scripts/kmet_perf_bench.clj cold-regex-grouped
;;   bb   scripts/kmet_perf_bench.clj md-session <session-file>
;;   bb   scripts/kmet_perf_bench.clj kitty-scan <session-file>
;;
;; Steady-state per-op medians with warmups (§6.2b): warming rounds matter,
;; irregex's first rounds run 3-4x steady state. Numbers are single-process
;; medians of 5 rounds (unless a round is seconds-long); report them as
;; same-run host ratios, not absolutes across machines.
(ns kmet-perf-bench
  (:require [clojure.string :as str]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as utils]
            [kmet.libs.markdown :as md]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.markdown :as markdown]
            [kmet.tui.protocols :as protocols]))

;; Same pattern as kmet.tui.utils' (private) ANSI-CODE-RE, for the A/B.
(def ANSI-CODE-RE
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")

(defn- now [] (System/nanoTime))
(defn- median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))

(defn per-call
  "Median per-call microseconds over ROUNDS timed rounds of N calls, after
   2 warming rounds. Prints a row when PRINT."
  ([label n f] (per-call label n f {}))
  ([label n f {:keys [rounds print] :or {rounds 5 print true}}]
   (dotimes [_ 2] (dotimes [_ n] (f)))
   (let [ts (doall (for [_ (range rounds)]
                     (let [t (now)]
                       (dotimes [_ n] (f))
                       (/ (double (- (now) t)) n 1000.0))))
         us (median ts)]
     (when print
       (println (format "%-46s %s"
                        label
                        (if (>= us 1000)
                          (format "%9.2f ms" (/ us 1000.0))
                          (format "%9.3f us" us)))))
     us)))

;; ─── Fixture strings ────────────────────────────────────────────────────────

(def plain100 (apply str (repeat 100 "a")))
(def one-char "x")
(def styled100 (str "\u001b[38;2;1;2;3m" plain100 "\u001b[0m"))
(def mdline (str "\u001b[1mbold text\u001b[0m plain text and " (apply str (repeat 50 "word"))))
(def boxline (str "─── ⎿ … — " (apply str (repeat 80 "a"))))
(def emojiline (str "⏺ ✻ ✓ " (apply str (repeat 80 "a"))))
(def cjkline (str (apply str (repeat 30 "日本語")) " tail"))
(def lines100 (vec (for [i (range 100)] (str "line " i " with some plain text on it"))))

;; ─── A/B helpers ────────────────────────────────────────────────────────────

(defn- unguarded-normalize
  "The pre-b9408c5 normalize body on a line with no tab: the two Thai/Lao
   replaces run unconditionally."
  [s]
  (-> s
      (str/replace "\u0e33" "\u0e4d\u0e32")
      (str/replace "\u0eb3" "\u0ecd\u0eb2")))

;; ─── Markdown fixtures ──────────────────────────────────────────────────────

(def md2k
  (str "# Heading\n\n"
       "Some paragraph text with **bold** and `code` and a [link](https://example.com).\n\n"
       "- item one\n- item two\n- item three\n\n"
       "```clojure\n(defn foo [x]\n  (* x 2))\n```\n\n"
       (apply str (repeat 20 "A paragraph with some words to fill out the text. "))
       "\n"))

(def md21k (apply str (repeat 11 md2k)))

;; ─── Editor / markdown components ───────────────────────────────────────────

(def ed-single (editor/make-editor :height 12))
(editor/editor-set-text! ed-single (apply str (repeat 500 "x")))
(def ed-20 (editor/make-editor :height 12))
(editor/editor-set-text! ed-20 (str/join "\n" (map #(str "line " % " with some text") (range 20))))

;; The Markdown component's render caches on its tracked atoms (track!), so a
;; same-text re-render is a cache hit — alternate two variants to force the
;; parse+style path every call (the streaming shape).
(def md-comp (markdown/make-markdown md2k))
(def md-a (str md2k "\n"))
(def md-b (str md2k "\n\n"))
(def md-toggle (atom 0))

(defn- render-md2k []
  (markdown/markdown-set-text! md-comp (if (zero? (swap! md-toggle #(- 1 %))) md-a md-b))
  (protocols/render md-comp 80))

;; ─── Compute fixtures ───────────────────────────────────────────────────────

(defn- fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(def kwmap {:a 1 :b 2 :c 3})
(def swap-cell (atom 0))
(def chords
  ["ctrl+c" "ctrl+d" "ctrl+l" "enter" "escape" "up" "down" "left" "right"
   "tab" "shift+tab" "ctrl+p" "ctrl+o" "ctrl+t" "alt+enter" "ctrl+u" "ctrl+a"
   "ctrl+e" "backspace" "delete" "home" "end" "pageup" "pagedown" "ctrl+r"
   "ctrl+k" "ctrl+y" "ctrl+w" "alt+b" "alt+f" "ctrl+left" "ctrl+right"
   "shift+up" "shift+down" "ctrl+/" "ctrl+;" "ctrl+space" "ctrl+z" "f1"
   "shift+enter"])

(defn full-sweep []
  (println "── widths / strings ──────────────────────────────────────────")
  (per-call "visible-width plain 100" 20000 #(utils/visible-width plain100))
  (per-call "visible-width 1-char" 200000 #(utils/visible-width one-char))
  (per-call "visible-width styled 100" 10000 #(utils/visible-width styled100))
  (per-call "visible-width md-styled line" 10000 #(utils/visible-width mdline))
  (per-call "visible-width box-drawing line" 2000 #(utils/visible-width boxline))
  (per-call "visible-width emoji line" 2000 #(utils/visible-width emojiline))
  (per-call "visible-width CJK line" 1000 #(utils/visible-width cjkline))
  (per-call "strip-ansi-codes styled (host path)" 10000 #(utils/strip-ansi-codes styled100))
  (per-call "strip-ansi-native styled (scanner)" 10000 #(utils/strip-ansi-native styled100))
  (per-call "str/replace ANSI-CODE-RE styled" 10000 #(str/replace styled100 ANSI-CODE-RE ""))
  (per-call "str/replace ANSI-CODE-RE plain" 10000 #(str/replace plain100 ANSI-CODE-RE ""))
  (per-call "ansi-code-at hit at 0" 50000 #(utils/ansi-code-at styled100 0))
  (per-call "re-find ASCII class plain 100" 20000 #(re-find #"[^\u0020-\u007e]" plain100))
  (println "── per-line frame work ───────────────────────────────────────")
  (per-call "normalize-terminal-output plain (guarded)" 20000 #(utils/normalize-terminal-output plain100))
  (per-call "normalize: two Thai replaces only" 20000 #(unguarded-normalize plain100))
  (per-call "SEGMENT-RESET concat alone" 100000 #(str plain100 utils/SEGMENT-RESET))
  (per-call "mapv identity over 100 lines" 2000 #(mapv identity lines100))
  (per-call "mapv (str line + SEGMENT-RESET) 100" 2000 #(mapv (fn [l] (str l utils/SEGMENT-RESET)) lines100))
  (println "── keys ──────────────────────────────────────────────────────")
  (per-call "keys/parse-key single char" 50000 #(keys/parse-key "a"))
  (per-call "keys/parse-key arrow" 50000 #(keys/parse-key "\u001b[A"))
  (per-call "keys/matches-key? ctrl+c" 50000 #(keys/matches-key? "c" "ctrl+c"))
  (per-call "one keystroke: 40 chord checks" 500 #(doseq [c chords] (keys/matches-key? "c" c)))
  (println "── components ────────────────────────────────────────────────")
  (per-call "editor render 500-char line" 200 #(protocols/render ed-single 80))
  (per-call "editor render 20 lines" 500 #(protocols/render ed-20 80))
  (per-call "md/parse 2KB" 20 #(md/parse md2k))
  (per-call "md/parse 21.6KB" 5 #(md/parse md21k))
  (per-call "markdown render 2KB (cache-miss)" 50 #(render-md2k))
  (println "── pure compute ──────────────────────────────────────────────")
  (per-call "fib 27" 1 #(fib 27) {:rounds 3})
  (per-call "keyword lookup x1e6" 1 #(dotimes [_ 1000000] (:a kwmap)) {:rounds 3})
  (per-call "subs x1e5" 1 #(dotimes [_ 100000] (subs plain100 0 50)) {:rounds 3})
  (per-call "assoc-in x1e5" 1 #(dotimes [_ 100000] (assoc-in {} [:a :b] 1)) {:rounds 3})
  (per-call "swap! x1e5" 1 #(dotimes [_ 100000] (swap! swap-cell inc)) {:rounds 3})
  (per-call "mapv inc x1e5" 1 #(dotimes [_ 100000] (mapv inc [1 2 3 4 5])) {:rounds 3}))

;; ─── Fresh-process cold regex ───────────────────────────────────────────────

(defn cold-regex [grouped?]
  (require 'kmet.app.loop)
  (let [rre (var-get (ns-resolve 'kmet.app.loop 'retryable-error-regex))
        rx (if grouped?
             (re-pattern (str "(" (.pattern rre) ")"))
             rre)
        msg (apply str (repeat 40 "some ordinary log line without a retry keyword\n"))
        t (now)
        r (re-find rx msg)
        cold (/ (double (- (now) t)) 1e6)
        ;; steady state: the same miss, now warm.
        _ (dotimes [_ 50] (re-find rx msg))
        t2 (now)
        _ (dotimes [_ 100] (re-find rx msg))
        warm (/ (double (- (now) t2)) 100 1e6)]
    (println (format "retryable-error-regex %s: %d chars, msg %d chars"
                     (if grouped? "(grouped/backtracker)" "(group-free/DFA)")
                     (count (.pattern rre)) (count msg)))
    (println (format "cold first re-find: %8.2f ms   match=%s" cold (boolean r)))
    (println (format "steady re-find:     %8.3f ms" warm))))

;; ─── Markdown breakdown over a session's assistant texts ────────────────────

(defn- parse-stats [label texts]
  (let [chars (reduce + 0 (map count texts))
        t (now)
        blocks (reduce + 0 (map #(count (md/parse %)) texts))
        ms (/ (double (- (now) t)) 1e6)]
    (println (format "  %-9s %4d msgs, %7d chars, %5d blocks: %7.1f ms (%.3f us/char)"
                     label (count texts) chars blocks ms (/ (* ms 1000.0) (max 1 chars))))))

(defn md-session [file]
  (require 'kmet.app.session 'kmet.app.ui 'kmet.modes.interactive)
  (let [session ((resolve 'kmet.app.session/load-session) file)
        ch ((resolve 'kmet.app.ui/make-chat-history) :tool-display-mode :expanded)
        cs ((resolve 'kmet.modes.interactive/map->CoreState) {:chat-history ch})]
    ((resolve 'kmet.modes.interactive/replay-branch!) cs session)
    (let [messages @(:messages-atom ch)
          parts (fn [k] (keep (fn [m] (when (= :assistant (:role m)) @(k (:component m)))) messages))]
      (println "md/parse over the assistant messages' markdown sources:")
      (parse-stats "text" (vec (parts :text-atom)))
      (parse-stats "thinking" (vec (parts :thinking-text-atom))))))

;; ─── Kitty-image whole-document scan cost (the diff hot path) ───────────────

(defn kitty-scan [file]
  (require 'kmet.app.session 'kmet.app.ui 'kmet.modes.interactive 'kmet.tui.core
           'kmet.libs.terminal-image)
  (let [session ((resolve 'kmet.app.session/load-session) file)
        ch ((resolve 'kmet.app.ui/make-chat-history) :tool-display-mode :expanded)
        cs ((resolve 'kmet.modes.interactive/map->CoreState) {:chat-history ch})
        _ ((resolve 'kmet.modes.interactive/replay-branch!) cs session)
        lines ((resolve 'kmet.tui.core/render) ch 100)
        avg (/ (double (reduce + 0 (map count lines))) (max 1 (count lines)))
        ek (resolve 'kmet.libs.terminal-image/extract-kitty-image-ids)
        scan (fn [] (doseq [l lines] (ek l)))]
    (println (format "lines=%d avg-len=%.0f" (count lines) avg))
    (dotimes [_ 2] (scan))
    (let [t (now)
          _ (dotimes [_ 3] (scan))
          ms (/ (double (- (now) t)) 3.0 1e6)]
      (println (format "kitty scan: %.2f ms/pass; expand runs it on prev+lines => %.2f ms/changed frame"
                       ms (* 2 ms))))))

;; ─── Main ───────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [mode (first args)
        host (if (resolve 'clojure.core/*jolt-version*) "jolt" "bb")]
    (println (str "host: " host "  mode: " (or mode "sweep")))
    (case mode
      "cold-regex" (cold-regex false)
      "cold-regex-grouped" (cold-regex true)
      "md-session" (md-session (second args))
      "kitty-scan" (kitty-scan (second args))
      (full-sweep))))

(apply -main *command-line-args*)

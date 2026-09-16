(ns kmet.tui.test-terminal-response
  "Terminal query response tests — OSC 11 background color, color scheme
   report (CSI ? 997 n), cell size (CSI 6;h;wt) parsing + the input-path
   interception (pi: terminal-colors.ts + consumeCellSizeResponse /
   consumeOsc11BackgroundResponse / consumeTerminalColorSchemeReport)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]
            [kmet.libs.terminal :as lib]
            [kmet.libs.terminal-image :as img]))

(defn- recording-terminal
  "ITerminal stub recording written output."
  []
  (let [writes (atom [])]
    {:terminal (reify term/ITerminal
                 (start! [_ _ _] nil)
                 (stop! [_] nil)
                 (started? [_] true)
                 (write-output [_ s] (swap! writes conj s))
                 (read-input [_ _] -1)
                 (columns [_] 80)
                 (rows [_] 24)
                 (set-progress! [_ _] nil))
     :writes writes}))

(defn- recording-tui
  "A TUI backed by the recording-terminal stub."
  []
  (core/create-tui (:terminal (recording-terminal))))

(defn- intercept [tui buf]
  ((var core/intercept-terminal-response!) tui nil buf))

(t/use-fixtures :each
  (fn [f]
    (img/set-cell-dimensions! img/default-cell-dimensions)
    (f)))

;; ─── Parsing (pi: terminal-colors.ts) ─────────────────────────────────────

(deftest test-parse-osc-11-background
  (testing "#rrggbb form"
    (t/is (= {:r 0x1a :g 0x2b :b 0x3c}
             (lib/parse-osc-11-background-response "\u001b]11;#1a2b3c\u0007"))))
  (testing "#rrrrggggbbbb form (scaled channels)"
    (t/is (= {:r 0xff :g 0x80 :b 0x00}
             (lib/parse-osc-11-background-response "\u001b]11;#ffff80000000\u0007"))))
  (testing "rgb: channel form with ST terminator"
    (t/is (= {:r 0x1a :g 0x2b :b 0x3c}
             (lib/parse-osc-11-background-response "\u001b]11;rgb:1a1a/2b2b/3c3c\u001b\\"))))
  (testing "rgba: form ignores the alpha channel"
    (t/is (= {:r 0xff :g 0xff :b 0xff}
             (lib/parse-osc-11-background-response "\u001b]11;rgba:ffff/ffff/ffff/0000\u0007"))))
  (testing "uppercase hex is accepted (pi regexes are case-insensitive)"
    (t/is (= {:r 0x1a :g 0x2b :b 0x3c}
             (lib/parse-osc-11-background-response "\u001b]11;#1A2B3C\u0007")))
    (t/is (= {:r 0x1a :g 0x2b :b 0x3c}
             (lib/parse-osc-11-background-response "\u001b]11;RGB:1a1a/2b2b/3c3c\u0007"))))
  (testing "non-responses"
    (t/is (nil? (lib/parse-osc-11-background-response "not a response")))
    (t/is (nil? (lib/parse-osc-11-background-response "\u001b]11;?\u0007")) "a query is not a response")
    (t/is (nil? (lib/parse-osc-11-background-response "\u001b]10;#1a2b3c\u0007")) "foreground color is not background")))

(deftest test-parse-terminal-color-scheme-report
  (testing "dark = 1, light = 2 (pi: parseTerminalColorSchemeReport)"
    (t/is (= :dark (lib/parse-terminal-color-scheme-report "\u001b[?997;1n")))
    (t/is (= :light (lib/parse-terminal-color-scheme-report "\u001b[?997;2n")))
    (t/is (nil? (lib/parse-terminal-color-scheme-report "\u001b[?997;3n")))
    (t/is (nil? (lib/parse-terminal-color-scheme-report "a"))))
  (testing "batched reports — the last one wins (pi: (?: ESC [ ?997;(1|2)n )+)"
    (t/is (= :dark (lib/parse-terminal-color-scheme-report "\u001b[?997;1n\u001b[?997;1n")))
    (t/is (= :light (lib/parse-terminal-color-scheme-report "\u001b[?997;1n\u001b[?997;2n")))
    (t/is (nil? (lib/parse-terminal-color-scheme-report "\u001b[?997;3n\u001b[?997;1n"))
          "an invalid report in the batch rejects the whole buffer")))

(deftest test-parse-cell-size-response
  (testing "CSI 16 t reply: CSI 6;height;width t (pi: consumeCellSizeResponse)"
    (t/is (= {:width-px 100 :height-px 30}
             (lib/parse-cell-size-response "\u001b[6;30;100t")))
    (t/is (nil? (lib/parse-cell-size-response "\u001b[6;0;100t")) "zero sizes are rejected")
    (t/is (nil? (lib/parse-cell-size-response "\u001b[6~")) "PageDown is not a cell size response")
    (t/is (nil? (lib/parse-cell-size-response "a")))))

(deftest test-split-terminal-response
  (testing "a leading response splits off the remainder of the batch"
    (t/is (= {:kind :cell-size :value {:width-px 100 :height-px 30} :rest ""}
             (lib/split-terminal-response "\u001b[6;30;100t")))
    (t/is (= {:kind :cell-size :value {:width-px 100 :height-px 30} :rest "\u001b[A"}
             (lib/split-terminal-response "\u001b[6;30;100t\u001b[A"))
          "a key sharing the batch stays in the remainder")
    (t/is (= {:kind :osc-11 :value {:r 0x1a :g 0x2b :b 0x3c} :rest "\u001b[?997;2n"}
             (lib/split-terminal-response "\u001b]11;#1a2b3c\u0007\u001b[?997;2n")))
    (t/is (= {:kind :color-scheme :value :light :rest "\u001b]11;#1a2b3c\u0007"}
             (lib/split-terminal-response "\u001b[?997;2n\u001b]11;#1a2b3c\u0007"))
          "a color scheme report first splits too")
    (t/is (= {:kind :osc-11 :value {:r 0x1a :g 0x2b :b 0x3c} :rest ""}
             (lib/split-terminal-response "\u001b]11;rgb:1a1a/2b2b/3c3c\u001b\\"))
          "ST-terminated OSC 11 splits too"))
  (testing "non-responses and rejected values stay untouched"
    (t/is (nil? (lib/split-terminal-response "a")))
    (t/is (nil? (lib/split-terminal-response "\u001b[6~")) "PageDown is not a cell size response")
    (t/is (nil? (lib/split-terminal-response "\u001b[6;")) "an incomplete fragment is not a response")
    (t/is (nil? (lib/split-terminal-response "\u001b[6;0;100t"))
          "a zero cell size is rejected, never split")))

(deftest test-response-prefixes
  (testing "fragments that could still become responses"
    (t/is (true? (lib/cell-size-response-prefix? "\u001b[6;")))
    (t/is (true? (lib/cell-size-response-prefix? "\u001b[6;30;10")))
    (t/is (false? (lib/cell-size-response-prefix? "\u001b[6~")) "PageDown never matches")
    (t/is (true? (lib/osc-11-response-prefix? "\u001b]11;rgb:")))
    (t/is (false? (lib/osc-11-response-prefix? "\u001b]11;rgb:\u0007")) "complete responses are not prefixes")
    (t/is (true? (lib/color-scheme-report-prefix? "\u001b[?997;")))
    (t/is (true? (lib/color-scheme-report-prefix? "\u001b[?997;1")))
    (t/is (false? (lib/color-scheme-report-prefix? "\u001b[?99")) "shorter prefixes fall through to key parsing")))

;; ─── Interception (pi: consume* in handleInput) ────────────────────────────

(deftest test-intercept-cell-size
  (testing "a cell size response updates cell dimensions and invalidates"
    (img/set-cell-dimensions! {:width-px 9 :height-px 18})
    (let [tui (recording-tui)
          buf (atom "\u001b[6;30;100t")
          invalidated (atom 0)
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ _] nil)
              (invalidate [_] (swap! invalidated inc)))]
      (core/tui-add-child tui c)
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf) "response removed from the input buffer")
      (t/is (= {:width-px 100 :height-px 30} (img/get-cell-dimensions)))
      (t/is (= 1 @invalidated) "components invalidated for re-render")))
  (testing "fragments are held until complete (ungated, like pi)"
    (let [tui (recording-tui)
          buf (atom "\u001b[6;")]
      (t/is (= :pending (intercept tui buf)))
      (t/is (= "" @buf) "fragment moved to the hold buffer")
      (reset! buf "30;100t")
      (t/is (= :consumed (intercept tui buf))))))

(deftest test-intercept-cell-size-gated
  (testing "consumption is ungated like pi (consumeCellSizeResponse)"
    (let [tui (recording-tui)
          buf (atom "\u001b[6;30;100t")]
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf))))
  (testing "PageDown (\u001b[6~) is not a cell size response and passes through"
    (let [tui (recording-tui)
          buf (atom "\u001b[6~")]
      (t/is (nil? (intercept tui buf)))
      (t/is (= "\u001b[6~" @buf) "buffer untouched — normal key handling"))))

(deftest ^:slow test-intercept-osc-11
  (testing "a response settles the oldest query; timeout settles with nil"
    (let [tui (recording-tui)
          p (core/tui-query-terminal-background-color tui :timeout-ms 200)
          buf (atom "\u001b]11;#1a2b3c\u0007")]
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= {:r 0x1a :g 0x2b :b 0x3c} (deref p 1000 ::timeout))
            "promise resolves to the parsed color")
      (let [p (core/tui-query-terminal-background-color tui :timeout-ms 50)]
        (t/is (nil? (deref p 2000 ::timeout)) "timeout resolves with nil")
        (t/is (false? @(:pending-osc-11? tui)) "pending flag cleared when the queue empties")))))

(deftest test-intercept-color-scheme
  (testing "reports notify listeners (ungated — also covers 2031 notifications)"
    (let [tui (recording-tui)
          schemes (atom [])]
      (core/tui-on-terminal-color-scheme-change tui #(swap! schemes conj %))
      (t/is (= :consumed (intercept tui (atom "\u001b[?997;2n"))))
      (t/is (= :consumed (intercept tui (atom "\u001b[?997;1n"))))
      (t/is (= [:light :dark] @schemes)))
    (testing "unsubscribe removes the listener"
      (let [tui (recording-tui)
            schemes (atom [])
            unsub (core/tui-on-terminal-color-scheme-change tui #(swap! schemes conj %))]
        (unsub)
        (intercept tui (atom "\u001b[?997;2n"))
        (t/is (empty? @schemes))))))

(deftest test-intercept-query-color-scheme
  (testing "tui-query-terminal-color-scheme resolves from the report"
    (let [tui (recording-tui)
          p (core/tui-query-terminal-color-scheme tui :timeout-ms 500)]
      (intercept tui (atom "\u001b[?997;2n"))
      (t/is (= :light (deref p 1000 ::timeout))))))

(deftest test-intercept-batched-terminal-responses
  (testing "an OSC 11 reply and a color scheme report in one batch are each consumed"
    (let [tui (recording-tui)
          schemes (atom [])
          p (core/tui-query-terminal-background-color tui :timeout-ms 200)
          buf (atom "\u001b]11;#1a2b3c\u0007\u001b[?997;2n")]
      (core/tui-on-terminal-color-scheme-change tui #(swap! schemes conj %))
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf) "both responses removed from the input buffer")
      (t/is (= {:r 0x1a :g 0x2b :b 0x3c} (deref p 1000 ::timeout))
            "the OSC 11 promise settles from the same batch")
      (t/is (= [:light] @schemes) "the color scheme report after it still fires"))))

(deftest test-intercept-batched-cell-size-then-key
  (testing "a key sharing the batch with a cell size response dispatches once"
    (img/set-cell-dimensions! {:width-px 9 :height-px 18})
    (let [tui (recording-tui)
          dispatched (atom [])
          buf (atom "\u001b[6;30;100t\u001b[A")]
      (swap! (:input-listeners tui)
             conj (fn [data] (swap! dispatched conj data) nil))
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf))
      (t/is (= {:width-px 100 :height-px 30} (img/get-cell-dimensions)))
      (t/is (= ["\u001b[A"] @dispatched) "the arrow key survives and is not duplicated"))))

(deftest test-intercept-non-response-passthrough
  (testing "ordinary input is never held or consumed"
    (let [tui (recording-tui)
          buf (atom "a")]
      (t/is (nil? (intercept tui buf)))
      (t/is (= "a" @buf))))
  (testing "a held fragment is flushed back when input stops matching"
    (let [tui (recording-tui)
          buf (atom "\u001b[6;")]
      (t/is (= :pending (intercept tui buf)))
      (reset! buf "~")  ;; \u001b[6~ = PageDown, not a response
      (t/is (nil? (intercept tui buf)))
      (t/is (= "\u001b[6;~" @buf) "held fragment + new input restored to the buffer")
      (t/is (= "" @(:terminal-response-buffer tui))))))

(deftest test-request-render-force
  (testing "force flags the next frame; the render loop clears the previous frame
            state itself so the reset cannot race its own state reads"
    (let [tui (recording-tui)]
      (reset! (:previous-lines tui) ["a" "b"])
      (core/tui-request-render tui true)
      (t/is (true? @(:force-redraw? tui)) "force flag set for the loop")
      (t/is (true? @(:render-requested? tui)))
      (t/is (= ["a" "b"] @(:previous-lines tui))
            "the caller does not touch previous state — the loop owns that reset"))
    (let [tui (recording-tui)]
      (core/tui-request-render tui)
      (t/is (false? @(:force-redraw? tui)) "a plain request does not force"))))

(deftest test-clear-on-shrink-accessors
  (testing "get/set with default off (pi parity — PI_CLEAR_ON_SHRINK=1)"
    (let [tui (recording-tui)]
      (t/is (false? (core/tui-get-clear-on-shrink tui)))
      (core/tui-set-clear-on-shrink! tui true)
      (t/is (true? (core/tui-get-clear-on-shrink tui))))))

(deftest test-full-redraw-count
  (testing "full redraws are counted (pi: getFullRedrawCount)"
    (let [tui (recording-tui)]
      (t/is (= 0 (core/tui-get-full-redraw-count tui)))
      (swap! (:full-redraw-count tui) inc)
      (t/is (= 1 (core/tui-get-full-redraw-count tui))))))

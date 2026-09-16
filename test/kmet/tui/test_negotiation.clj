(ns kmet.tui.test-negotiation
  "Kitty keyboard protocol negotiation tests — response parsing, prefix
   detection, protocol enable/disable, and the input-reader interception
   (pi: terminal.ts negotiation + setupStdinBuffer)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.libs.terminal :as lib]
            [kmet.tui.terminal :as term]))

(defn- recording-writer
  "Returns a write-fn recording written output plus the writes atom."
  []
  (let [writes (atom [])]
    {:write-fn #(swap! writes conj %)
     :writes writes}))

(defn- recording-terminal
  "ITerminal stub for the reader-interception tests."
  []
  (reify term/ITerminal
    (start! [_ _ _] nil)
    (stop! [_] nil)
    (started? [_] true)
    (write-output [_ _] nil)
    (read-input [_ _] -1)
    (columns [_] 80)
    (rows [_] 24)
    (set-progress! [_ _] nil)))

(defn- reset-protocol-state!
  "Reset the module-level protocol flags between tests."
  []
  (keys/set-kitty-active! false)
  (lib/query-kitty-protocol! (fn [_])))

(t/use-fixtures :each (fn [f] (reset-protocol-state!) (f)))

;; ─── Response parsing (pi: parseKeyboardProtocolNegotiationSequence) ──────

(deftest test-parse-negotiation-sequence
  (testing "kitty flags, device attributes, and garbage"
    (t/is (= {:type :kitty-flags :flags 7} (lib/parse-negotiation-sequence "\u001b[?7u")))
    (t/is (= {:type :kitty-flags :flags 0} (lib/parse-negotiation-sequence "\u001b[?0u")))
    (t/is (= {:type :device-attributes} (lib/parse-negotiation-sequence "\u001b[?1;2c")))
    (t/is (= {:type :device-attributes} (lib/parse-negotiation-sequence "\u001b[?c")))
    (t/is (nil? (lib/parse-negotiation-sequence "a")))
    (t/is (nil? (lib/parse-negotiation-sequence "\u001b[A")) "arrow keys are not negotiation responses")
    (t/is (nil? (lib/parse-negotiation-sequence "\u001b[?7u\u001b[?1;2c"))
          "multi-sequence chunks never parse whole — split-negotiation-response handles them")))

(deftest test-split-negotiation-response
  (testing "a leading response splits off the remainder of the batch"
    (t/is (= {:parsed {:type :kitty-flags :flags 7} :rest ""}
             (lib/split-negotiation-response "\u001b[?7u")))
    (t/is (= {:parsed {:type :kitty-flags :flags 7} :rest "\u001b[?1;2c"}
             (lib/split-negotiation-response "\u001b[?7u\u001b[?1;2c")))
    (t/is (= {:parsed {:type :device-attributes} :rest "\u001b[?7u"}
             (lib/split-negotiation-response "\u001b[?1;2c\u001b[?7u"))
          "DA1 first is split too")
    (t/is (= {:parsed {:type :kitty-flags :flags 7} :rest "\u001b[A"}
             (lib/split-negotiation-response "\u001b[>7;1;2u\u001b[A"))
          "the push response form splits as well"))
  (testing "non-negotiation input is untouched"
    (t/is (nil? (lib/split-negotiation-response "\u001b[A")))
    (t/is (nil? (lib/split-negotiation-response "\u001b[?7")))
    (t/is (nil? (lib/split-negotiation-response "\u001b[?u")))))

(deftest test-negotiation-prefix
  (testing "fragments that could still become a response"
    (t/is (false? (lib/negotiation-prefix? "\u001b["))
          "bare CSI is also an arrow-key prefix — never held")
    (t/is (false? (lib/negotiation-prefix? "\u001b[?"))
          "query introducer without digits is held by nothing")
    (t/is (true? (lib/negotiation-prefix? "\u001b[?7")))
    (t/is (true? (lib/negotiation-prefix? "\u001b[?1;2")))
    (t/is (false? (lib/negotiation-prefix? "\u001b[A")) "arrow prefix is not negotiation")
    (t/is (false? (lib/negotiation-prefix? "\u001b[?7u")) "complete responses are not prefixes")))

;; ─── Protocol handling (pi: handleKeyboardProtocolNegotiationSequence) ─────

(deftest test-handle-kitty-flags-enables-kitty
  (testing "non-zero flags enable the Kitty protocol"
    (try
      (keys/set-kitty-active! false)
      (let [{:keys [write-fn writes]} (recording-writer)]
        ;; fallback enabled first, then kitty wins → fallback disabled
        (lib/handle-negotiation-sequence! write-fn {:type :device-attributes})
        (lib/handle-negotiation-sequence! write-fn {:type :kitty-flags :flags 7})
        (t/is (true? (keys/kitty-active?)))
        (t/is (= ["\u001b[>4;2m" "\u001b[>4;0m"] @writes)
              "modifyOtherKeys enabled then disabled when kitty wins"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-handle-zero-flags-falls-back
  (testing "zero flags enable the modifyOtherKeys fallback"
    (let [{:keys [write-fn writes]} (recording-writer)]
      (lib/handle-negotiation-sequence! write-fn {:type :kitty-flags :flags 0})
      (t/is (false? (keys/kitty-active?)))
      (t/is (= ["\u001b[>4;2m"] @writes) "modifyOtherKeys fallback enabled"))))

(deftest test-handle-device-attributes-fallback
  (testing "a device-attributes report (DA sentinel) enables the fallback
            when kitty is not active"
    (let [{:keys [write-fn writes]} (recording-writer)]
      (lib/handle-negotiation-sequence! write-fn {:type :device-attributes})
      (t/is (= ["\u001b[>4;2m"] @writes) "DA sentinel enables the fallback"))))

(deftest test-disable-kitty-protocol
  (testing "disable writes the disable sequence and clears state"
    (try
      (keys/set-kitty-active! true)
      (let [{:keys [write-fn writes]} (recording-writer)]
        (lib/disable-kitty-protocol! write-fn)
        (t/is (= ["\u001b[<u"] @writes)
              "modifyOtherKeys was never enabled here, so only the kitty disable is written")
        (t/is (false? (keys/kitty-active?))))
      (finally (keys/set-kitty-active! false)))))

;; ─── Input-reader interception (pi: setupStdinBuffer) ──────────────────────

(defn- stub-read-fn
  "A read-fn that never yields more input (immediate timeout)."
  [_timeout-ms]
  -2)

(defn- intercept [tui buf]
  ((var core/intercept-keyboard-negotiation!) tui stub-read-fn buf))

(deftest test-intercept-consumes-batched-responses
  (testing "responses sharing one read batch are each consumed"
    ;; The reader drains every queued byte into one batch, so kitty's flags
    ;; report and the DA1 report arrive concatenated. Before this, the
    ;; whole-string parse failed, the flags report was dropped as garbage,
    ;; kitty-active stayed false and every release event dispatched as a
    ;; second keypress (issue #4: Linux double input).
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (recording-terminal))
            buf (atom "\u001b[?7u\u001b[?1;2c")]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (t/is (= :consumed (intercept tui buf)))
        (t/is (= "" @buf) "both responses removed from the input buffer")
        (t/is (true? (keys/kitty-active?)) "kitty enabled from the batched response"))
      (finally (keys/set-kitty-active! false))))
  (testing "a DA1-first batch is consumed in order: fallback on, kitty wins"
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (recording-terminal))
            buf (atom "\u001b[?1;2c\u001b[?7u")]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (t/is (= :consumed (intercept tui buf)))
        (t/is (= "" @buf))
        (t/is (true? (keys/kitty-active?))))
      (finally (keys/set-kitty-active! false)))))

(deftest test-intercept-batched-response-then-key-dispatches-once
  (testing "a key sharing the batch with the response still dispatches once"
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (recording-terminal))
            buf (atom "\u001b[?7u\u001b[?1;2c\u001b[A")
            dispatched (atom [])]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (swap! (:input-listeners tui)
               conj (fn [data] (swap! dispatched conj data) nil))
        ((var core/process-input-buffer!) tui stub-read-fn buf)
        (t/is (= ["\u001b[A"] @dispatched)
              "the arrow key survives and is not duplicated")
        (t/is (true? (keys/kitty-active?))))
      (finally (keys/set-kitty-active! false)))))

(deftest test-intercept-consumes-response
  (testing "a complete response is consumed, never dispatched as input"
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (recording-terminal))
            buf (atom "\u001b[?7u")]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (t/is (= :consumed (intercept tui buf)))
        (t/is (= "" @buf) "response removed from the input buffer")
        (t/is (true? (keys/kitty-active?)) "kitty enabled from the reader path"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-intercept-holds-fragments
  (testing "split responses are held across reads, then consumed"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?7")]
      (reset! (:keyboard-protocol-pushed? tui) true)
      (t/is (= :pending (intercept tui buf)))
      (t/is (= "" @buf) "fragment moved to the negotiation buffer")
      (reset! buf "u")
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf)))))

(deftest test-intercept-flushes-non-negotiation
  (testing "held fragments flush back into the buffer when input stops
            matching the response shape"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?7")]
      (reset! (:keyboard-protocol-pushed? tui) true)
      (t/is (= :pending (intercept tui buf)))
      (reset! buf "A")  ;; user pressed up while the response was pending
      (t/is (nil? (intercept tui buf)) "not negotiation input")
      (t/is (= "\u001b[?7A" @buf) "held fragment + new input restored"))))

(deftest test-intercept-inactive-when-not-pushed
  (testing "no interception before the query is sent"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?7u")]
      (t/is (nil? (intercept tui buf)))
      (t/is (= "\u001b[?7u" @buf)))))

;; ─── ESC-wait loop interception (regression: DA response leaking into the
;;      editor as "?1;2;4c") ────────────────────────────────────────────────

(defn- process-chars
  "Drive process-input-buffer! the way the app reader loop does: one char per
   read (the next char of CHARS available immediately to the ESC-wait loop's
   timed reads), then assert the final state. Increments :input-generation
   per char like the real reader so the interception flush timers (guarded
   by the generation) never fire mid-sequence."
  [tui chars]
  (let [pending (atom (seq chars))
        read-fn (fn [_timeout-ms]
                  (if-let [c (first @pending)]
                    (do (swap! pending rest) (int c))
                    -2))
        dispatched (atom [])
        buf (atom "")]
    (swap! (:input-listeners tui)
           conj (fn [data] (swap! dispatched conj data) nil))
    (reset! (:keyboard-protocol-pushed? tui) true)
    (doseq [_ (range (count chars))]
      ;; the ESC-wait loop may have consumed some chars already, so reads
      ;; can time out (-2) before the loop finishes
      (let [ch (read-fn 0)]
        (when (>= ch 0)
          (swap! buf str (char ch))
          (swap! (:input-generation tui) inc)
          ((var core/process-input-buffer!) tui read-fn buf))))
    {:dispatched @dispatched :buf @buf}))

(deftest test-esc-loop-consumes-split-da-response
  (testing "a device-attributes response arriving char-by-char through the
            ESC-wait loop is held and consumed — never dispatched as keys
            (regression: the loop parsed \"\u001b[\" as alt+[ and leaked the
            remaining \"?1;2;4c\" into the focused editor)"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[?1;2;4c")]
      (t/is (= [] dispatched) "no response char reaches the input path")
      (t/is (= "" buf) "response fully consumed")
      (t/is (false? (keys/kitty-active?)) "DA fallback: kitty stays off")))
  (testing "a complete kitty-flags response is consumed the same way"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[?7u")]
      (t/is (= [] dispatched))
      (t/is (= "" buf))
      (t/is (true? (keys/kitty-active?)) "kitty enabled from the ESC path")))
  (testing "an arrow key is NOT held by the interception — it dispatches"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[A")]
      (t/is (= ["\u001b[A"] dispatched) "arrow key reaches the input path")
      (t/is (= "" buf)))))

(deftest test-negotiation-parses-kitty-push-response
  ;; Termux answers the kitty push query (\u001b[>7u) with \u001b[>...u —
  ;; this format must be parsed as kitty flags so it is consumed by the
  ;; negotiation intercept (previously unrecognized, it leaked into the
  ;; input buffer and swallowed every subsequent key).
  (t/is (= {:type :kitty-flags :flags 7}
           (lib/parse-negotiation-sequence "\u001b[>7u")))
  (t/is (= {:type :kitty-flags :flags 7}
           (lib/parse-negotiation-sequence "\u001b[>7;1;2u"))
        "push response with mods/event-type flags parses")
  (t/is (true? (lib/negotiation-prefix? "\u001b[>"))
        "push prefix is held by the negotiation interception")
  (t/is (true? (lib/negotiation-prefix? "\u001b[>7;1")))
  (testing "the push response is consumed through the ESC loop, not dispatched"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[>7;1;2u")]
      (t/is (= [] dispatched) "push response never reaches the input path")
      (t/is (= "" buf))
      (t/is (true? (keys/kitty-active?)) "kitty enabled from the push response"))))

(deftest test-unparseable-complete-sequence-does-not-swallow-input
  ;; A complete ESC sequence that nothing recognizes (neither negotiation,
  ;; parse-key, mouse nor focus) must be DROPPED, not held in the buffer —
  ;; holding it appended every subsequent char to it and swallowed all input
  ;; forever (the reported freeze: app alive, keys dead, no crash log).
  (testing "an unrecognized complete sequence is dropped; later chars dispatch"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[?99;5uabc")]
      (t/is (= ["a" "b" "c"] dispatched)
            "subsequent characters reach the input path")
      (t/is (= "" buf) "the garbage sequence is gone from the buffer")))
  (testing "a known complete sequence still dispatches normally"
    (let [{:keys [dispatched]} (process-chars (core/create-tui (recording-terminal))
                                              "\u001b[A")]
      (t/is (= ["\u001b[A"] dispatched)))))

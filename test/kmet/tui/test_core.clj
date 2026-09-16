(ns kmet.tui.test-core
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.libs.reakt :as reakt]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.input :as input]))

(defn- leaf
  "A focusable leaf component with a focused?-atom (like the editor)."
  []
  (let [focused? (atom false)]
    {:comp (reify core/IComponent
             core/IFocusable
             (render [_ _] [""])
             (handle-input [_ _] nil)
             (dispose [_] nil)
             (invalidate [_])
             (focused [_] @focused?)
             (set-focused! [_ v] (reset! focused? v)))
     :focused? focused?}))

(t/deftest test-work-pending-sees-render-and-batch-work
  (testing "a requested render pends"
    (reakt/flush!)  ; isolate from anything a previous test left queued
    (let [tui (core/create-tui nil)]
      (t/is (false? ((var core/work-pending?) tui)) "idle: nothing pending")
      (core/tui-request-render tui)
      (t/is (true? ((var core/work-pending?) tui)) "a requested render pends")))
  (testing "a queued reaction pends even with no render requested"
    ;; the race the recheck closes: a reaction enqueued between the loop's
    ;; flush and its wait must keep the loop off the wait (a monitor wakeup
    ;; with nobody waiting is dropped)
    (let [tui (core/create-tui nil)
          a (atom 0)
          d (reakt/derive [a] identity)]
      @d
      (swap! a inc)
      (t/is (false? @(:render-requested? tui)) "no render was requested")
      (t/is (true? ((var core/work-pending?) tui)) "the queued reaction pends")
      (reakt/flush!)
      (t/is (false? ((var core/work-pending?) tui)) "flushed clean")
      (reakt/dispose! d))))

(t/deftest test-overlay-focus-restores-previous
  (testing "hiding an overlay returns input to the focus home"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          c (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-add-child tui (:comp b))
      ;; b plays the editor: focused before the overlay, designated home
      (core/tui-set-focus tui (:comp b))
      (core/tui-set-focus-home! tui (fn [] (:comp b)))
      (core/tui-show-overlay tui (:comp c) :width 10 :height 5)
      (t/is (identical? (:comp c) @(:focused-component tui)))
      (t/is (true? @(:focused? c)))
      (t/is (false? @(:focused? b)) "home loses the flag while covered")
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp b) @(:focused-component tui))
            "focus returns home")
      (t/is (true? @(:focused? b)) "focus flag restored"))))

(t/deftest test-overlay-stacked-focus
  (testing "hiding the top overlay focuses the overlay below, not the base"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          c (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus tui (:comp a))
      (core/tui-show-overlay tui (:comp b) :width 10 :height 5)
      (core/tui-show-overlay tui (:comp c) :width 10 :height 5)
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp b) @(:focused-component tui))
            "lower overlay gets focus when the top one closes"))))

(t/deftest test-overlay-focus-fallback
  (testing "no previous focus → null focus without a home; home when registered"
    (let [tui (core/create-tui nil)
          a (leaf)
          o (leaf)]
      ;; a is mounted but INERT — focusing it would swallow keys silently
      ;; (the footer-swallowed-all-input regression)
      (core/tui-add-child tui (:comp a))
      (core/tui-show-overlay tui (:comp o) :width 10 :height 5)
      (core/tui-hide-overlay tui)
      (t/is (nil? @(:focused-component tui))
            "no home: null focus, never an arbitrary root child"))
    (let [tui (core/create-tui nil)
          home (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp home))
      (core/tui-set-focus-home! tui (fn [] (:comp home)))
      (core/tui-show-overlay tui (:comp o) :width 10 :height 5)
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp home) @(:focused-component tui))
            "registered home receives focus"))))

(t/deftest test-flash-api
  (testing "tui-flash! shows a flash and tui-flash-dispose! clears it"
    (let [tui (core/create-tui nil)]
      (core/tui-flash! tui "Copied!" :duration-ms 60000)
      (t/is (= 1 (count (core/render @(:flashes tui) 20))))
      (core/tui-flash-dispose! tui)
      (t/is (= [] (core/render @(:flashes tui) 20))))))

(t/deftest test-tui-stop-disposes-flashes
  (testing "stopping the TUI clears pending flashes (pi: dispose on close)"
    (let [tui (core/create-tui nil)]
      (core/tui-flash! tui "x" :duration-ms 60000)
      (t/is (= 1 (count (core/render @(:flashes tui) 20))))
      (core/tui-stop tui)
      (t/is (= [] (core/render @(:flashes tui) 20))))))

(t/deftest test-overlay-stale-previous-focus
  (testing "a removed previous-focus falls back to the focus home, not a\n           guessed sibling"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-add-child tui (:comp b))
      ;; the app designates the editor-equivalent (a) as home
      (core/tui-set-focus-home! tui (fn [] (:comp a)))
      (core/tui-set-focus tui (:comp b))
      (core/tui-show-overlay tui (:comp o) :width 10 :height 5)
      (core/tui-remove-child tui (:comp b))
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp a) @(:focused-component tui))
            "home resolves the restore target"))))

(defn- dispatch!
  "Call the private input dispatcher (pi: TUI input routing)."
  [tui data]
  ((var kmet.tui.core/dispatch-input!) tui data))

(t/deftest test-dispatch-no-focus-drops-input
  (testing "input with no focused component is dropped (pi: no fallback)"
    (let [tui (core/create-tui nil)
          got (atom [])
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ data] (swap! got conj data))
              (invalidate [_]))]
      (core/tui-add-child tui c)
      (dispatch! tui "a")
      (t/is (empty? @got) "nothing delivered without a focused component"))))

(t/deftest test-dispatch-filters-key-releases
  (testing "key release events are filtered by sequence shape, flag or not"
    ;; No kitty-active required (pi: isKeyRelease is shape-based): a lost
    ;; negotiation reply must not make releases dispatch as presses.
    (keys/set-kitty-active! false)
    (let [tui (core/create-tui nil)
          got (atom [])
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ data] (swap! got conj data))
              (invalidate [_]))]
      (core/tui-add-child tui c)
      (core/tui-set-focus tui c)
      (dispatch! tui "a")
      (dispatch! tui "\u001b[97;1:3u")  ;; kitty release event
      (t/is (= ["a"] @got) "release events are filtered by default"))))

(t/deftest test-dispatch-filters-releases-with-the-flag-on-too
  (testing "the negotiated flag does not change release filtering"
    (keys/set-kitty-active! true)
    (try
      (let [tui (core/create-tui nil)
            got (atom [])
            c (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ data] (swap! got conj data))
                (invalidate [_]))]
        (core/tui-add-child tui c)
        (core/tui-set-focus tui c)
        (dispatch! tui "a")
        (dispatch! tui "\u001b[97;1:3u")
        (t/is (= ["a"] @got)))
      (finally (keys/set-kitty-active! false)))))

(t/deftest test-batched-kitty-negotiation-keeps-releases-filtered
  ;; Issue #4: the terminal answers the startup kitty query with the flags
  ;; report and DA1 back-to-back and the reader's drain coalesces them into
  ;; one batch. The whole-buffer negotiation parse used to miss both, the
  ;; flags report was dropped as garbage and kitty-active stayed false —
  ;; every release event then dispatched as a second keypress (completion
  ;; menu moved two steps per arrow, ctrl+o toggled twice).
  (testing "a batched flags + DA1 response still enables release filtering"
    (keys/set-kitty-active! false)
    (try
      (let [stub (reify core/ITerminal
                   (start! [_ _ _] nil)
                   (stop! [_] nil)
                   (started? [_] true)
                   (write-output [_ _] nil)
                   (read-input [_ _] -1)
                   (columns [_] 80)
                   (rows [_] 24)
                   (set-progress! [_ _] nil))
            tui (core/create-tui stub)
            got (atom [])
            c (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ data] (swap! got conj data))
                (invalidate [_]))
            buf (atom "")]
        (core/tui-add-child tui c)
        (core/tui-set-focus tui c)
        (reset! (:keyboard-protocol-pushed? tui) true)
        (swap! buf str "\u001b[?7u\u001b[?1;2c")
        ((var core/process-input-buffer!) tui (fn [_] -2) buf)
        (t/is (true? (keys/kitty-active?)) "kitty enabled from the batched response")
        (swap! buf str "\u001b[A\u001b[1;1:3A")
        ((var core/process-input-buffer!) tui (fn [_] -2) buf)
        (t/is (= ["\u001b[A"] @got)
              "only the press reaches the focused component, never the release"))
      (finally (keys/set-kitty-active! false)))))

(defrecord WantsReleases [wants-key-release? log]
  core/IComponent
  (render [_ _] [""])
  (handle-input [_ data] (swap! log conj data))
  (invalidate [_]))

(t/deftest test-dispatch-wants-key-release-opt-in
  (testing "a component with :wants-key-release? true receives releases (pi: wantsKeyRelease)"
    (keys/set-kitty-active! true)
    (try
      (let [tui (core/create-tui nil)
            log (atom [])
            opt-in (map->WantsReleases {:wants-key-release? true :log log})]
        (core/tui-add-child tui opt-in)
        (core/tui-set-focus tui opt-in)
        (dispatch! tui "\u001b[97;1:3u")
        (t/is (= ["\u001b[97;1:3u"] @log)
              "opt-in component receives the release event"))
      (finally (keys/set-kitty-active! false)))))

(t/deftest test-dispatch-listener-chain
  (testing "input listeners chain: :data transforms feed later listeners,
            :consume stops dispatch (pi: InputListener chain)"
    (let [tui (core/create-tui nil)
          got (atom [])
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ data] (swap! got conj data))
              (invalidate [_]))]
      (core/tui-add-child tui c)
      (core/tui-set-focus tui c)
      (core/tui-add-input-listener tui (fn [data] {:data (str data "!")}))
      (core/tui-add-input-listener tui (fn [data] (swap! got conj [:l2 data])))
      (dispatch! tui "x")
      (t/is (= [:l2 "x!"] (first @got))
            "second listener sees the transformed data")
      (t/is (= "x!" (second @got))
            "focused component receives the final transformed data")
      ;; consume stops later listeners AND focus delivery (pi semantics:
      ;; earlier listeners already ran)
      (reset! got [])
      (core/tui-add-input-listener tui (fn [_] {:consume true}))
      (dispatch! tui "y")
      (t/is (= [[:l2 "y!"]] @got)
            "consume drops the event for later listeners and focus"))))

;; ─── Unbracketed paste detection (paste-like bursts) ──────────────────────
;; Terminals/IMEs without bracketed-paste support deliver paste content as
;; ordinary key events, so a paste line ending arrives as a lone CR and the
;; editor would submit it (executing pasted /cmd or !cmd without Enter). The
;; dispatcher rewrites a CR that ends a paste-like burst to \n; only an
;; isolated CR (a real Enter press) submits.

(defn- cr-in-paste-burst?
  "Test helper for the private predicate."
  [recent now]
  ((var kmet.tui.core/cr-in-paste-burst?) recent now))

(defn- paste-burst-step
  "Test helper for the private per-unit burst decision."
  [state data now]
  ((var kmet.tui.core/paste-burst-step) state data now))

(defn- burst-chars
  "N chars arriving BURST-APART ms apart, ending with LAST, all within the
   paste-burst window ending at NOW. Entries are [timestamp char] pairs like
   the burst state's :recent tracking."
  [n burst-apart last now]
  (conj (mapv (fn [i]
                [(- now (* (- n i) burst-apart))
                 (char (+ (int \a) i))])
              (range n))
        [now last]))

(t/deftest test-cr-in-paste-burst
  (testing "a CR ends a paste-like burst"
    (t/is (true? (cr-in-paste-burst? (burst-chars 3 5 \return 1000) 1000))
          "4 chars (incl CR) within 100ms")
    (t/is (true? (cr-in-paste-burst? (burst-chars 8 5 \return 1000) 1000))
          "any paste-sized burst"))
  (testing "slow input is typing, not a paste"
    (t/is (false? (cr-in-paste-burst? (burst-chars 2 5 \return 1000) 1000))
          "fewer than 4 chars")
    (t/is (false? (cr-in-paste-burst? (burst-chars 3 200 \return 1000) 1000))
          "chars arrive slower than the burst window"))
  (testing "an Enter key repeat stream (all CRs) keeps submitting"
    (t/is (false? (cr-in-paste-burst? (mapv (fn [i] [(- 1000 (* i 30)) \return])
                                            (range 4))
                                      1000)))))

(t/deftest test-paste-burst-text-runs-pass-through
  (let [empty-state {:recent [] :swallow-lf-at nil :in-paste? false}]
    (testing "multibyte and plain text runs pass through unchanged"
      (doseq [s ["ф" "привет" "café" "abc" ""]]
        (t/is (= s (second (paste-burst-step empty-state s 1000))))))
    (testing "a text run feeds the burst window"
      (let [[state out] (paste-burst-step empty-state "abc" 1000)]
        (t/is (= "abc" out))
        (t/is (= [[1000 \a] [1000 \b] [1000 \c]] (:recent state)))))
    (testing "escape sequences never feed the burst window"
      ;; Issue: with the Kitty protocol enabled every non-text key sends a
      ;; CSI-u press plus a release; counting those bytes made one arrow key
      ;; a paste-sized burst, and the next Enter was rewritten to a newline.
      (let [s1 (first (paste-burst-step empty-state "\u001b[1;1:3A" 1000))
            s2 (first (paste-burst-step s1 "\u001b[A" 1005))
            s3 (first (paste-burst-step s2 "\u001b[27u" 1010))]
        (t/is (= [] (:recent s3)) "releases, arrows and escape stay invisible")
        (t/is (= "\r" (second (paste-burst-step s3 "\r" 1050)))
              "an isolated CR after a key still submits")))
    (testing "bracketed-paste content never feeds the burst window"
      (let [s1 (first (paste-burst-step empty-state "\u001b[200~" 1000))
            s2 (first (paste-burst-step s1 "abc" 1005))
            s3 (first (paste-burst-step s2 "\r" 1010))
            s4 (first (paste-burst-step s3 "\u001b[201~" 1015))]
        (t/is (= [] (:recent s4)) "paste content and its CR are not typed text")
        (t/is (= "\r" (second (paste-burst-step s4 "\r" 1020)))
              "the Enter after the paste submits")))))

(t/deftest test-paste-burst-step
  (let [state {:recent [] :swallow-lf-at nil :in-paste? false}]
    (testing "CR ending a text burst becomes a newline and arms the LF swallow"
      (let [[s1 _] (paste-burst-step state "abc" 1000)
            [s2 out] (paste-burst-step s1 "\r" 1005)]
        (t/is (= "\n" out))
        (t/is (= 1005 (:swallow-lf-at s2)))))
    (testing "the LF half of a rewritten CRLF is dropped"
      (let [[s1 _] (paste-burst-step state "abc" 1000)
            [s2 _] (paste-burst-step s1 "\r" 1005)
            [s3 out] (paste-burst-step s2 "\n" 1006)]
        (t/is (nil? out) "immediate LF swallowed")
        (t/is (nil? (:swallow-lf-at s3))))
      (let [[s1 _] (paste-burst-step state "abc" 1000)
            [s2 _] (paste-burst-step s1 "\r" 1005)
            [_ out] (paste-burst-step s2 "\n" 1100)]
        (t/is (= "\n" out) "a late LF is a real newline")))
    (testing "an isolated CR (real Enter) is untouched"
      (t/is (= "\r" (second (paste-burst-step state "\r" 1050)))))
    (testing "an Enter key repeat stream (all CRs) keeps submitting"
      (loop [s state i 0]
        (when (< i 4)
          (let [[s' out] (paste-burst-step s "\r" (+ 1000 (* i 30)))]
            (t/is (= "\r" out))
            (recur s' (inc i))))))
    (testing "a lost paste-END marker cannot stick the in-paste flag forever"
      (let [s1 (first (paste-burst-step state "\u001b[200~" 1000))]
        (t/is (true? (:in-paste? s1)))
        (t/is (= 61000 (:paste-deadline s1)) "deadline armed at START")
        (let [[s2 out] (paste-burst-step s1 "abc" 20000)]
          (t/is (= "abc" out) "in-paste content still passes")
          (t/is (true? (:in-paste? s2)))
          (t/is (= 80000 (:paste-deadline s2)) "in-paste content renews the deadline")
          (let [[s3 out3] (paste-burst-step s2 "abc" 1000000)]
            (t/is (= "abc" out3) "the stale-paste unit is processed normally")
            (t/is (false? (:in-paste? s3)) "the idle timeout cleared the flag")
            (t/is (nil? (:paste-deadline s3)))))
        (let [[s2 _] (paste-burst-step s1 "\u001b[201~" 20000)]
          (t/is (false? (:in-paste? s2)))
          (t/is (nil? (:paste-deadline s2)) "END clears the deadline"))))))

;; ─── Kitty printable-input duplicates ─────────────────────────────────────

(t/deftest test-kitty-printable-guard
  (let [tui (core/create-tui nil)
        dup? #((var kmet.tui.core/drop-kitty-printable-duplicate!) tui %)]
    (t/is (false? (dup? "\u001b[120u")) "unmodified printable CSI-u arms the guard")
    (t/is (true? (dup? "x")) "the raw char duplicating it is dropped")
    (t/is (false? (dup? "x")) "the guard disarms after one drop")
    (t/is (false? (dup? "\u001b[120;2u")) "a modified CSI-u arms nothing")
    (t/is (false? (dup? "x")) "so the next raw char passes")
    (t/is (false? (dup? "\u001b[27u")) "a control codepoint arms nothing")
    (t/is (false? (dup? "x")))
    (t/is (false? (dup? "\u001b[120u")))
    (t/is (false? (dup? "xy")) "a multi-char run is never a duplicate")
    (t/is (false? (dup? "x")) "and it disarms the pending codepoint")))

(t/deftest test-trace-escape
  (let [esc #((var kmet.tui.core/trace-escape) %)]
    (t/is (= "\"abc\"" (esc "abc")))
    (t/is (= "\"\\u001b[A\"" (esc "\u001b[A")) "ESC and controls become \\uXXXX text")
    (t/is (= "\"a\\r\\nb\"" (esc "a\r\nb")))
    (t/is (= "\"\"" (esc "")) "empty is not nil")
    (t/is (= "-" (esc nil)))
    (t/is (str/ends-with? (esc (apply str (repeat 500 "x"))) "...(500 chars)")
          "long units are capped")))

(defn- reader-feed!
  "Simulate the app reader loop (start-input-reader) for CHARS: each entry is
   [char ts] with TS a monotonic timestamp. The burst clock is driven through
   the test seam so dispatch sees the simulated arrival times."
  [tui chars]
  (let [read-fn (fn [_timeout-ms] -2)
        buf (atom "")]
    (doseq [[c ts] chars]
      (binding [core/*paste-burst-now-ms* ts]
        (swap! (:input-generation tui) inc)
        (swap! buf str c)
        ((var kmet.tui.core/process-input-buffer!) tui read-fn buf)))
    {:buf @buf}))

(defn- pasted-editor
  "TUI with a focused editor; feeds CHARS through the simulated reader loop
   and returns {:editor ed :submitted submitted}."
  [chars]
  (let [tui (core/create-tui nil)
        ed (editor/make-editor)
        submitted (atom [])]
    (editor/editor-set-on-submit! ed (fn [t] (swap! submitted conj t)))
    (core/tui-add-child tui ed)
    (core/tui-set-focus tui ed)
    (reader-feed! tui chars)
    {:editor ed :submitted submitted}))

(defn- paste-chars
  "CHARS arriving BURST-APART ms apart starting at TS."
  [chars burst-apart ts]
  (mapv (fn [i c] [c (+ ts (* i burst-apart))]) (range) chars))

(t/deftest test-unbracketed-paste-does-not-submit
  (testing "an unbracketed paste with CR line endings inserts text, no submit"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "abc\rdef\r" 5 1000))]
      (t/is (= [] @submitted) "paste CRs never submit")
      (t/is (= "abc\ndef\n" (editor/editor-get-text editor)) "CRs became newlines")))
  (testing "CRLF line endings collapse to a single newline each"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "abc\r\ndef\r" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "abc\ndef\n" (editor/editor-get-text editor)))))
  (testing "a pasted slash command is text, not a command"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "/model DGG hhh\r" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "/model DGG hhh\n" (editor/editor-get-text editor))))))

(t/deftest test-isolated-enter-still-submits
  (testing "a lone CR (real Enter press) submits"
    (let [{:keys [submitted]} (pasted-editor (paste-chars "abc\r" 150 1000))]
      (t/is (= ["abc"] @submitted) "slow typing then Enter submits")))
  (testing "an Enter key repeat stream (all CRs) keeps submitting"
    (let [{:keys [submitted]} (pasted-editor (paste-chars "\r\r\r\r" 30 1000))]
      (t/is (= 4 (count @submitted)) "repeated Enters are not rewritten"))))

(t/deftest test-kitty-printable-duplicate-not-typed-twice
  ;; Some terminals emit both the CSI-u sequence and the raw char for a
  ;; printable key (pi: pendingKittyPrintableCodepoint); the raw duplicate
  ;; must not insert a second copy.
  (keys/set-kitty-active! true)
  (try
    (let [{:keys [editor]}
          (pasted-editor [["\u001b[120u" 1000] ["x" 1001]
                          ["\u001b[121u" 1002] ["y" 1003]])]
      (t/is (= "xy" (editor/editor-get-text editor))))
    (finally (keys/set-kitty-active! false))))

(t/deftest test-kitty-key-then-enter-still-submits
  ;; The reported bug: with the Kitty protocol enabled (Windows Terminal 1.25,
  ;; kitty, Konsole) every non-text key arrives as a CSI-u press plus a
  ;; release, and the release bytes counted as a paste-like burst — so any
  ;; arrow/Ctrl/Esc key shortly before Enter rewrote the Enter to a newline.
  (keys/set-kitty-active! true)
  (try
    (testing "arrow press+release then Enter"
      (let [{:keys [editor submitted]}
            (pasted-editor [["x" 1000]
                            ["\u001b[A" 1010] ["\u001b[1;1:3A" 1015]
                            ["\r" 1050]])]
        (t/is (= ["x"] @submitted))
        (t/is (= "x" (editor/editor-get-text editor)))))
    (testing "ctrl chord press+release then Enter"
      (let [{:keys [submitted]}
            (pasted-editor [["x" 1000]
                            ["\u001b[111;5u" 1010] ["\u001b[111;5:3u" 1015]
                            ["\r" 1050]])]
        (t/is (= ["x"] @submitted))))
    (testing "held backspace (DEL repeats) then Enter"
      (let [{:keys [submitted]}
            (pasted-editor [["x" 1000] ["y" 1010]
                            ["\u007f" 1020] ["\u007f" 1030]
                            ["\r" 1050]])]
        (t/is (= [""] @submitted)
              "DELs are not burst text; the Enter submits what remains")))
    (testing "bracketed paste then Enter"
      (let [{:keys [submitted]}
            (pasted-editor [["\u001b[200~abc" 1000] ["\u001b[201~" 1010] ["\r" 1030]])]
        (t/is (= ["abc"] @submitted))))
    (finally (keys/set-kitty-active! false))))

(t/deftest test-bracketed-paste-unaffected
  (testing "bracketed paste still buffers and normalizes via handle-paste"
    ;; pi split("\n"): the trailing \r normalizes to a trailing newline,
    ;; which split keeps as an empty final line.
    (let [{:keys [editor submitted]}
          (pasted-editor (paste-chars "\u001b[200~ab\r\ncd\r\u001b[201~" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "ab\ncd\n" (editor/editor-get-text editor))))))

;; ─── Paste-marker buffering (regression: text sharing a buffer pass with
;;      a marker was dropped) ────────────────────────────────────────────────

(defn- feed-buf!
  "Run one process-input-buffer! pass over BUF (as the reader/flush timers
   do), collecting dispatched data through an input listener."
  [tui buf]
  (let [dispatched (atom [])]
    (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
    ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
    @dispatched))

(t/deftest test-split-paste-marker-head-waits-for-remainder
  ;; WSL/conpty stalls can split "\u001b[200~" across reads (the lone
  ;; "\u001b" head arrives, the tail 50ms+ later). The head must wait
  ;; for its remainder in the ESC branch — never dispatch as Escape, or it
  ;; corrupts into a phantom Escape + literal-text leak ("[200~" typed,
  ;; paste swallowed until a later key).
  (testing "a lone ESC head is held, not dispatched"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b")]
      (t/is (= [] (feed-buf! tui buf)) "head held for the remainder")
      (t/is (= "\u001b" @buf) "head stays buffered")
      (swap! buf str "[200~")
      (t/is (= ["\u001b[200~"] (feed-buf! tui buf)) "tail completes the marker")
      (t/is (= "" @buf) "buffer drained")))
  (testing "a partial marker prefix is held, not dispatched"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[20")]
      (t/is (= [] (feed-buf! tui buf)) "prefix held for the remainder")
      (t/is (= "\u001b[20" @buf) "prefix stays buffered")
      (swap! buf str "0~hello")
      (t/is (= ["\u001b[200~" "hello"] (feed-buf! tui buf)) "marker then text, in order")
      (t/is (= "" @buf) "buffer drained")))
  (testing "an ambiguous \u001b[2 still takes the ESC branch (F12, not a marker)"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[2")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 80)
      (t/is (= [] @dispatched) "incomplete prefix never dispatches")
      (t/is (= "\u001b[2" @buf) "still buffered")
      (swap! buf str "4~")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= ["\u001b[24~"] @dispatched) "F12 dispatched as one sequence"))))

(t/deftest test-lone-esc-still-fires-as-escape
  (testing "a lone ESC with no follow-up still fires as Escape"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (let [deadline (+ (System/currentTimeMillis) 600)]
        (while (and (empty? @dispatched) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (t/is (= ["\u001b"] @dispatched) "genuine Escape still works")
      (t/is (= "" @buf) "buffer consumed"))))

(defn- feed-batches!
  "Feed each BATCH whole in one pass (like the real reader's drain)."
  [tui buf batches]
  (doseq [b batches]
    (swap! (:input-generation tui) inc)
    (swap! buf str b)
    ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)))

(defn- batched-editor
  "TUI with a focused editor; feeds each BATCH whole in one pass (like the
   real reader's drain) and returns {:editor ed :buf buf :submitted}."
  [batches]
  (let [tui (core/create-tui nil)
        ed (editor/make-editor)
        buf (atom "")
        submitted (atom nil)]
    (core/tui-add-child tui ed)
    (core/tui-set-focus tui ed)
    (editor/editor-set-on-submit! ed (fn [t] (reset! submitted t)))
    (feed-batches! tui buf batches)
    {:editor ed :buf buf :submitted submitted}))

(t/deftest test-batched-bracketed-paste-commits-in-one-pass
  ;; The reported bug: a whole paste coalesced into one reader batch showed
  ;; nothing until later keys were pressed (the remainder sat unprocessed
  ;; in buf). Every split below must commit in the same pass — and later
  ;; keys must insert only themselves.
  (testing "whole paste in one batch commits immediately"
    (let [{:keys [editor buf]} (batched-editor ["\u001b[200~hello\u001b[201~"])]
      (t/is (= "hello" (editor/editor-get-text editor)))
      (t/is (= "" @buf) "nothing left waiting for a later key")))
  (testing "START+content in one batch, END split off"
    (let [{:keys [editor buf]} (batched-editor ["\u001b[200~hello" "\u001b[201~"])]
      (t/is (= "hello" (editor/editor-get-text editor)))
      (t/is (= "" @buf))))
  (testing "a following keypress inserts only itself"
    (let [{:keys [editor buf]} (batched-editor ["\u001b[200~hello\u001b[201~" "x" "y"])]
      (t/is (= "helloxy" (editor/editor-get-text editor)) "no fused paste+keys")
      (t/is (= "" @buf))))
  (testing "a pasted CR becomes a newline in the editor, not a submit"
    (let [{:keys [editor buf submitted]} (batched-editor ["\u001b[200~line1\rline2\u001b[201~" "x"])]
      (t/is (nil? @submitted) "paste never submits")
      (t/is (= "line1\nline2x" (editor/editor-get-text editor))
            "CR splits lines, x appends after the paste")
      (t/is (= "" @buf))))
  (testing "text before START stays outside the paste"
    (let [{:keys [editor buf]} (batched-editor ["ab\u001b[200~cd\u001b[201~ef"])]
      (t/is (= "abcdef" (editor/editor-get-text editor)))
      (t/is (= "" @buf))))
  (testing "an arrow key sharing a batch with a paste keeps arrival order"
    ;; A complete key followed by a paste in one buffer: the key drains
    ;; through the ESC branch first, then the paste commits — all in the
    ;; same pass.
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[A\u001b[200~hi\u001b[201~")
          dispatched (feed-buf! tui buf)]
      (t/is (= ["\u001b[A" "\u001b[200~" "hi" "\u001b[201~"] dispatched)
            "key first, then the paste, in order")
      (t/is (= "" @buf) "buffer drained")))
  (testing "a held key fragment ahead of the paste is not overtaken"
    ;; A stalled CSI head (e.g. an arrow key split across reads) holds the
    ;; buffer through the ESC branch; a paste marker arriving behind it
    ;; must stay buffered there too — the marker never overtakes a
    ;; pending key. Once the tail arrives the key dispatches first, then
    ;; the paste behind it.
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[\u001b[200~hi\u001b[201~")]
      (t/is (= [] (feed-buf! tui buf)) "partial head holds everything")
      (t/is (= "\u001b[\u001b[200~hi\u001b[201~" @buf) "nothing dispatched early")
      (swap! buf str "A")
      ;; Tail "A" lands after the paste bytes, so the stranded head can
      ;; never complete "A" anymore: the ESC branch fires it as Escape via
      ;; its flush timer once the test's later input bumps the generation.
      ;; What matters here: nothing dispatched early and nothing overtakes.
      (t/is (= [] (feed-buf! tui buf)) "still held, no overtake")
      (t/is (= "\u001b[\u001b[200~hi\u001b[201~A" @buf)
            "arrival order preserved")))
  (testing "Escape-then-paste in one batch can't starve behind the hold"
    ;; A pressed Escape immediately followed by a paste (one coalesced
    ;; batch): the lone ESC drains as Escape first, then the paste commits
    ;; in the same pass — and nothing is left for the flush timer to
    ;; garbage-collect into a freeze.
    (let [{:keys [editor buf]} (batched-editor ["\u001b\u001b[200~hi\u001b[201~"])]
      (Thread/sleep 150)
      (t/is (= "hi" (editor/editor-get-text editor)) "paste committed")
      (t/is (= "" @buf) "buffer drained, flush timer has nothing to eat")))
  (testing "the input box commits a coalesced paste in the same pass"
    ;; The bug hits both editor and input box via the shared reader path.
    (let [tui (core/create-tui nil)
          inp (input/make-input)
          buf (atom "")]
      (core/tui-add-child tui inp)
      (core/tui-set-focus tui inp)
      (feed-batches! tui buf ["\u001b[200~hello\u001b[201~" "x"])
      (t/is (= "hellox" (input/input-get-value inp)) "no fused paste+keys")
      (t/is (= "" @buf) "nothing left waiting for a later key")))
  (testing "back-to-back pastes in one batch both commit"
    (let [{:keys [editor buf]} (batched-editor ["\u001b[200~one\u001b[201~\u001b[200~two\u001b[201~"])]
      (t/is (= "onetwo" (editor/editor-get-text editor)))
      (t/is (= "" @buf))))
  (testing "a nested START inside paste content does not drop content"
    ;; Everything between the first START and first END is paste data: a
    ;; nested START must not reset the buffer. (The editor additionally
    ;; strips control bytes, so the raw ESC byte does not survive there.)
    (let [{:keys [editor buf]} (batched-editor ["\u001b[200~a\u001b[200~b\u001b[201~"])]
      (t/is (= "a[200~b" (editor/editor-get-text editor))
            "content kept, control bytes filtered")
      (t/is (= "" @buf))))
  (testing "a nested START is literal in the input box too"
    (let [tui (core/create-tui nil)
          inp (input/make-input)
          buf (atom "")]
      (core/tui-add-child tui inp)
      (core/tui-set-focus tui inp)
      (feed-batches! tui buf ["\u001b[200~a\u001b[200~b\u001b[201~"])
      (t/is (= "a\u001b[200~b" (input/input-get-value inp))
            "nested marker preserved literally")
      (t/is (= "" @buf)))))

(t/deftest test-split-bracketed-paste-inserts-text
  ;; A bracketed paste split across reads (marker head, content, end marker
  ;; each arriving separately) still inserts the pasted text — the paste is
  ;; never swallowed waiting for a later key.
  (testing "head, content, and end marker each arrive alone"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          _ (do (core/tui-add-child tui ed)
                (core/tui-set-focus tui ed))
          buf (atom "\u001b")]
      (feed-buf! tui buf)
      (t/is (= "\u001b" @buf) "head held")
      (swap! buf str "[200~")
      (feed-buf! tui buf)
      (swap! buf str "pasted")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (swap! buf str "\u001b[201~")
      (feed-buf! tui buf)
      (t/is (= "pasted" (editor/editor-get-text ed))))))

(t/deftest test-paste-marker-preserves-surrounding-text
  ;; Text can share a buffer pass with a paste marker when a held interceptor
  ;; fragment flushes back into the buffer ahead of fresh input. Everything
  ;; around the marker is processed in arrival order in the same pass.
  (testing "content after the start marker is dispatched in the same pass"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[200~abc")]
      (t/is (= ["\u001b[200~" "abc"] (feed-buf! tui buf)) "marker then text")
      (t/is (= "" @buf) "buffer drained")))
  (testing "text before the marker is dispatched ahead of post-marker text"
    (let [tui (core/create-tui nil)
          buf (atom "q\u001b[200~hi")]
      (t/is (= ["q" "\u001b[200~" "hi"] (feed-buf! tui buf)))
      (t/is (= "" @buf) "arrival order kept, buffer drained")))
  (testing "the end marker completes the paste of everything between"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          _ (do (core/tui-add-child tui ed)
                (core/tui-set-focus tui ed))
          buf (atom "\u001b[200~hello")]
      (feed-buf! tui buf)                 ; marker + text in one pass
      ;; deliver the end marker
      (reset! buf "\u001b[201~")
      (feed-buf! tui buf)
      (t/is (= "hello" (editor/editor-get-text ed))))))

(t/deftest test-run-then-esc-sequence-not-corrupted
  ;; The bulk-run path (O(n) paste fix) must not split an ESC sequence that
  ;; follows a printable run in the same buffer — "hi\u001b[A" used to
  ;; dispatch the arrow key's bytes as literal text ("[A" typed into the
  ;; editor), the exact split-sequence class the input buffer prevents.
  (testing "arrow key after a printable run dispatches as a key, not text"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          buf (atom "hi\u001b[A")
          dispatched (atom [])]
      (core/tui-add-child tui ed)
      (core/tui-set-focus tui ed)
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= "hi" (editor/editor-get-text ed)) "the run inserted as text")
      (t/is (= "\u001b[A" (last @dispatched)) "the arrow key dispatched as a sequence")
      (t/is (= "" @buf) "buffer drained")))
  (testing "leading control then run then arrow terminates (no infinite recursion)"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          buf (atom "\nhi\u001b[A")
          dispatched (atom [])]
      (core/tui-add-child tui ed)
      (core/tui-set-focus tui ed)
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= "\nhi" (editor/editor-get-text ed)) "control + run inserted")
      (t/is (= "\u001b[A" (last @dispatched)) "arrow key dispatched")
      (t/is (= "" @buf) "buffer drained")))
  (testing "WezTerm kitty quirk: raw ESC + CSI-u release splits (pi parity)"
    ;; WezTerm sends Escape press as a raw ESC byte and release as a full
    ;; CSI-u sequence; "\u001b\u001b" alone parses as ctrl+alt+[, which
    ;; would swallow the CSI-u tail as literal text. Split when another
    ;; ESC-prefixed sequence follows (pi extractCompleteSequences).
    (let [tui (core/create-tui nil)
          buf (atom "\u001b\u001b[27;5;97~")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= ["\u001b" "\u001b[27;5;97~"] @dispatched)
            "ESC first, release sequence intact")
      (t/is (= "" @buf) "buffer drained")
      (t/is (= "escape" (keys/parse-key "\u001b")) "head is Escape")
      (t/is (some? (keys/parse-key "\u001b[27;5;97~")) "tail still parses")))
  (testing "a bare ESC ESC pair still parses as one key (no over-split)"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b\u001b")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= ["\u001b\u001b"] @dispatched) "pair dispatched whole")
      (t/is (= "" @buf) "buffer drained"))))

(t/deftest test-incomplete-sequence-flush-timeouts
  ;; A lone ESC fires as Escape after ESCAPE-FLUSH-MS (100ms, not pi's 10ms:
  ;; WSL/conpty stalls split sequences 50ms+ apart, so 10ms consumed a lone
  ;; ESC head before its tail arrived), but a partial CSI sequence waits
  ;; SEQUENCE-FLUSH-MS (50ms) — the flat 10ms fired MID-SEQUENCE under
  ;; ordinary reader stalls (observed at 11-15ms on Android), flushing a
  ;; phantom Escape and leaking the rest of a bracketed paste as text.
  (testing "a lone ESC dispatches as Escape shortly after"
    (let [tui (core/create-tui nil)
          buf (atom "")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (swap! buf str "\u001b")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (let [deadline (+ (System/currentTimeMillis) 600)]
        (while (and (empty? @dispatched) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (t/is (= ["\u001b"] @dispatched) "Escape dispatched")
      (t/is (= "" @buf) "buffer cleared")))
  (testing "a partial CSI prefix is never flushed as keys"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[2")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 80)
      (t/is (= [] @dispatched) "incomplete prefix never dispatches")
      (t/is (= "\u001b[2" @buf) "still buffered, waiting for the remainder")))
  (testing "a flush-timer fire does not break later sequence completion"
    ;; The timer claims \u001b[200 after SEQUENCE-FLUSH-MS, dispatch declines
    ;; (incomplete), and the fragment must go BACK into the buffer — a late ~
    ;; still completes the paste marker instead of leaking as literal text.
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[200")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 80)               ; timer fires, claims, declines, restores
      (swap! buf str "~")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= ["\u001b[200~"] @dispatched) "marker completed from restored fragment")
      (t/is (= "" @buf))))
  (testing "a stale flush leaves owned input buffered"
    ;; dispatch-buffer! consumes only what it dispatches: a lone ESC armed
    ;; before a read arrived is owned by the reader, not by the stale timer.
    (let [tui (core/create-tui nil)
          dispatched (atom [])
          buf (atom "\u001b[")]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (swap! (:input-generation tui) inc)
      (t/is (false? ((var kmet.tui.core/dispatch-buffer!) tui buf 0))
            "incomplete prefix never dispatches")
      (t/is (= [] @dispatched))
      (t/is (= "\u001b[" @buf) "failed flush leaves the buffer intact"))))

(t/deftest test-flush-timer-clears-complete-garbage
  (testing "pi parity: StdinBuffer.flush emits the whole buffer, so complete garbage can never stall"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[999~")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (t/is (true? ((var kmet.tui.core/dispatch-buffer!) tui buf @(:input-generation tui)))
            "garbage reports consumed")
      (t/is (= [] @dispatched) "garbage is dropped, not dispatched")
      (t/is (= "" @buf) "buffer cleared — nothing stalls until the next key")))
  (testing "a flush timer never garbage-collects a buffer holding markers"
    (let [tui (core/create-tui nil)
          buf (atom "q\u001b[200~hi")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (t/is (false? ((var kmet.tui.core/dispatch-buffer!) tui buf @(:input-generation tui)))
            "marker buffer reports pending")
      (t/is (= [] @dispatched) "nothing dispatched")
      (t/is (= "q\u001b[200~hi" @buf) "buffer intact for the paste leg"))))

(t/deftest test-flush-timer-never-steals-reader-input
  ;; A stale flush still dispatches the ambiguous byte to listeners, but the
  ;; delivery guard in dispatch-input! shields the focused component when the
  ;; arming generation went stale: the ESC is a split sequence's head whose
  ;; tail the reader owns.
  (testing "a stale flush never reaches the focused component"
    (let [tui (core/create-tui nil)
          dispatched (atom [])
          buf (atom "\u001b")]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ;; armed-gen is stale (a read bumped the generation after arming) — the
      ;; lone ESC is the head of a sequence whose tail is in flight, not an
      ;; Escape keypress.
      (swap! (:input-generation tui) inc)
      (t/is (true? ((var kmet.tui.core/dispatch-buffer!) tui buf 0)))
      (t/is (= ["\u001b"] @dispatched) "listeners still observe the byte")
      (t/is (= "" @buf) "buffer consumed — the tail stays whole")))
  (testing "dispatch-buffer! dispatches a genuinely idle lone ESC"
    (let [tui (core/create-tui nil)
          dispatched (atom [])
          buf (atom "\u001b")]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (t/is (true? ((var kmet.tui.core/dispatch-buffer!) tui buf @(:input-generation tui))))
      (t/is (= ["\u001b"] @dispatched) "Escape dispatched")
      (t/is (= "" @buf) "buffer consumed"))))

(t/deftest test-split-sequence-across-reads-never-corrupts
  ;; WSL/conpty stalls split escape sequences across reads with 50ms+ gaps:
  ;; the head arrives, the tail much later. The sequence flush timer (50ms)
  ;; must NOT dispatch the head as keys while the remainder is in flight — or
  ;; the head corrupts and the tail leaks as literal text ("27;5;97~" typed
  ;; into the editor instead of ctrl+a moving to line start). Any input
  ;; arrival disowns stale flush timers via the input generation guard
  ;; (checked at delivery in dispatch-input!).
  (testing "head then tail 50ms+ apart still dispatches one sequence"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          buf (atom "")]
      (core/tui-add-child tui ed)
      (core/tui-set-focus tui ed)
      (doseq [c "hello"] (core/handle-input ed (str c)))
      ;; read 1: ESC + "[" together (conpty delivers the head in one
      ;; chunk, the tail after a 50ms+ stall). Schedules the 50ms
      ;; sequence flush.
      (swap! (:input-generation tui) inc)
      (swap! buf str "\u001b[")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      ;; the stale sequence timer fires here — the delivery guard must drop
      ;; the phantom Escape; then read 2 completes the sequence after a
      ;; WSL-style stall
      (Thread/sleep 80)
      (swap! (:input-generation tui) inc)
      (swap! buf str "27;5;97~")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 60)
      (t/is (= "hello" (editor/editor-get-text ed)) "no literal text leaked")
      (t/is (= 0 (:cursor-col @(:state-atom ed))) "ctrl+a moved to line start")))
  (testing "a lone ESC with no follow-up still fires as Escape"
    (let [tui (core/create-tui nil)
          dispatched (atom [])
          buf (atom "")]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (swap! (:input-generation tui) inc)
      (swap! buf str "\u001b")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (let [deadline (+ (System/currentTimeMillis) 600)]
        (while (and (empty? @dispatched) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (t/is (= ["\u001b"] @dispatched) "genuine Escape still works"))))

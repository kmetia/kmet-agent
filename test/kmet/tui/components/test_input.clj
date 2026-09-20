(ns kmet.tui.components.test-input
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.components.input :as input]))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-input-create
  (let [inp (input/make-input)]
    (t/is (satisfies? core/IComponent inp))
    (t/is (satisfies? core/IFocusable inp))
    (t/is (= "" (input/input-get-value inp)))
    (t/is (not (core/focused inp)))))

(t/deftest test-input-set-value
  (let [inp (input/make-input)]
    (input/input-set-value! inp "hello")
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-focus
  (let [inp (input/make-input)]
    (core/set-focused! inp true)
    (t/is (core/focused inp))
    (core/set-focused! inp false)
    (t/is (not (core/focused inp)))))

;; ─── Basic typing ──────────────────────────────────────────────────────────

(t/deftest test-input-typing
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-typing-multi-char
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (t/is (= "hello world" (input/input-get-value inp)))))

;; ─── Cursor movement ───────────────────────────────────────────────────────

(t/deftest test-input-cursor-left-right
  (let [inp (input/make-input)]
    (doseq [c "abcd"] (core/handle-input inp (str c)))
    ;; left 2, insert X
    (core/handle-input inp "\u001b[D")
    (core/handle-input inp "\u001b[D")
    (core/handle-input inp "X")
    (t/is (= "abXcd" (input/input-get-value inp)))))

(t/deftest test-input-cursor-home-end
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "X")
    (t/is (= "Xhello" (input/input-get-value inp)))
    (core/handle-input inp "\u001b[F")  ;; end
    (core/handle-input inp "Z")
    (t/is (= "XhelloZ" (input/input-get-value inp)))))

;; ─── Backspace & Delete ────────────────────────────────────────────────────

(t/deftest test-input-backspace
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u007f")  ;; backspace
    (t/is (= "hell" (input/input-get-value inp)))
    (core/handle-input inp "\u007f")  ;; backspace
    (t/is (= "hel" (input/input-get-value inp)))))

(t/deftest test-input-delete
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "\u001b[3~")  ;; delete
    (t/is (= "ello" (input/input-get-value inp)))))

;; ─── Line editing ─────────────────────────────────────────────────────────

(t/deftest test-input-ctrl-u
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    ;; Move to after the space (position 6), then ctrl+u deletes "hello "
    (dotimes [_ 5] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 21)))  ;; ctrl+u
    (t/is (= "world" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-k
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    ;; Move to position 5 (the space), ctrl+k deletes " world"
    (dotimes [_ 6] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 11)))  ;; ctrl+k
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-w
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 23)))  ;; ctrl+w
    (t/is (= "hello " (input/input-get-value inp)))))

;; ─── Undo ─────────────────────────────────────────────────────────────────

(t/deftest test-input-undo-typing
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 31)))  ;; ctrl+-
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-undo-after-edit
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (dotimes [_ 6] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 11)))  ;; ctrl+k
    (t/is (= "hello" (input/input-get-value inp)))
    (core/handle-input inp (str (char 31)))  ;; ctrl+-
    (t/is (= "hello world" (input/input-get-value inp)))))

;; ─── Yank ─────────────────────────────────────────────────────────────────

(t/deftest test-input-yank
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp (str (char 11)))  ;; ctrl+k (kill to end)
    (t/is (= "" (input/input-get-value inp)))
    (core/handle-input inp (str (char 25)))  ;; ctrl+y
    (t/is (= "hello" (input/input-get-value inp)))))

;; ─── Word navigation ──────────────────────────────────────────────────────

(t/deftest test-input-word-left
  (let [inp (input/make-input)]
    (doseq [c "hello world foo"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001bb")  ;; alt+left
    (core/handle-input inp "|")
    (t/is (= "hello world |foo" (input/input-get-value inp)))
    (core/handle-input inp "\u001bb")
    (core/handle-input inp "|")
    (t/is (= "hello world| |foo" (input/input-get-value inp)))))

(t/deftest test-input-word-right
  (let [inp (input/make-input)]
    (doseq [c "hello world foo"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "\u001bf")  ;; alt+right
    (core/handle-input inp "|")
    (t/is (= "hello| world foo" (input/input-get-value inp)))))

;; ─── Submit & Escape ──────────────────────────────────────────────────────

(t/deftest test-input-submit
  (let [inp (input/make-input)
        submitted (atom nil)]
    (input/input-set-on-submit! inp (fn [v] (reset! submitted v)))
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\r")
    (t/is (= "hello" @submitted))))

(t/deftest test-input-escape
  (let [inp (input/make-input)
        escaped (atom false)]
    (input/input-set-on-escape! inp (fn [] (reset! escaped true)))
    (core/handle-input inp "\u001b")
    (t/is @escaped)))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-input-render-empty
  (let [inp (input/make-input)
        lines (core/render inp 10)]
    (t/is (= 1 (count lines)))
    (t/is (str/starts-with? (first lines) "> "))))

(t/deftest test-input-render-with-text
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (let [lines (core/render inp 15)]
      (t/is (= 1 (count lines)))
      (t/is (.contains (first lines) "hello")))))

;; ─── Edge cases ───────────────────────────────────────────────────────────

(t/deftest test-input-home-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[H")
    (core/handle-input inp "a")
    (t/is (= "a" (input/input-get-value inp)))))

(t/deftest test-input-backspace-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u007f")
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-delete-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[3~")
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-u-at-start
  (let [inp (input/make-input)]
    (core/handle-input inp (str (char 21)))
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-k-at-end
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 11)))
    (t/is (= "hello" (input/input-get-value inp)))))

;; ─── Paste (bracketed paste markers) ────────────────────────────────────────

(t/deftest test-input-paste-basic
  (let [inp (input/make-input)]
    (core/handle-input inp (str "\u001b[200~" "hello" "\u001b[201~"))
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-paste-removes-newlines
  ;; pi: handlePaste removes \r\n / \r / \n (single-line input)
  (let [inp (input/make-input)]
    (core/handle-input inp (str "\u001b[200~" "a\r\nb\nc" "\u001b[201~"))
    (t/is (= "abc" (input/input-get-value inp)))))

(t/deftest test-input-paste-tabs-to-spaces
  (let [inp (input/make-input)]
    (core/handle-input inp (str "\u001b[200~" "a\tb" "\u001b[201~"))
    (t/is (= "a    b" (input/input-get-value inp)))))

(t/deftest test-input-paste-at-cursor
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 2)))  ;; ctrl+b — cursor left
    (core/handle-input inp (str "\u001b[200~" "X" "\u001b[201~"))
    (t/is (= "hellXo" (input/input-get-value inp)))))

(t/deftest test-input-paste-streamed-per-char
  ;; the TUI dispatches the bracketed-paste markers and the content as
  ;; separate events (marker, then each char, then the end marker) — every
  ;; buffered char must be tolerated until the end marker lands (regression:
  ;; the end-marker index was nil mid-stream and NPE'd, leaving the input
  ;; stuck in :buffering)
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[200~")
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[201~")
    (t/is (= "hello" (input/input-get-value inp))))
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[200~")
    (doseq [c "a\r\nb"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[201~")
    (t/is (= "ab" (input/input-get-value inp)) "newlines stripped from the streamed paste")))

(t/deftest test-input-keys-resolve-through-the-manager
  (t/testing "P3: the input's keys go through the manager — a user override
              replaces the default chord"
    (let [prev (kb/get-global-keybindings)]
      (try
        (kb/set-global-keybindings!
         (kb/make-keybindings-manager kb/tui-keybinding-defs
                                      {"tui.editor.deleteCharBackward" "ctrl+x"}))
        (let [inp (input/make-input)]
          (doseq [c "ab"] (core/handle-input inp (str c)))
          (core/handle-input inp (str (char 127)))
          (t/is (= "ab" (input/input-get-value inp)) "backspace was rebound away")
          (core/handle-input inp (str (char 24)))
          (t/is (= "a" (input/input-get-value inp)) "ctrl+x deletes backward"))
        (finally
          (kb/set-global-keybindings! prev))))))

(t/deftest test-input-ctrl-h-is-deletecharbackwards-second-chord
  (t/testing "kitty ctrl+h (CSI-u) is part of tui.editor.deleteCharBackward"
    (let [inp (input/make-input)]
      (doseq [c "ab"] (core/handle-input inp (str c)))
      (core/handle-input inp "\u001b[104;5u")
      (t/is (= "a" (input/input-get-value inp)) "ctrl+h deletes backward"))))

(t/deftest test-input-kitty-printable-csi-u-inserts-text
  (t/testing "flag-1 terminals send CSI-u for printable keys (pi #3780)"
    (let [inp (input/make-input)]
      (core/handle-input inp "\u001b[97u")   ;; kitty 'a'
      (t/is (= "a" (input/input-get-value inp)))
      (core/handle-input inp "\u001b[224u")  ;; Italian-layout 'à'
      (t/is (= "aà" (input/input-get-value inp)))
      (core/handle-input inp "\u001b[97;5u") ;; kitty ctrl+a: a chord, not text
      (t/is (= "aà" (input/input-get-value inp))))))

(t/deftest test-input-on-change-fires-on-value-edits
  ;; editor parity: value-changing edits fire the callback with the value,
  ;; cursor-only moves do not, and a programmatic set-value! is not an edit
  (let [inp (input/make-input)
        seen (atom [])]
    (input/input-set-on-change! inp (fn [v] (swap! seen conj v)))
    (doseq [c "ab"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[D")
    (core/handle-input inp "\u007f")
    (t/is (= ["a" "ab" "b"] @seen)
          "typing/delete fire; a cursor move does not")
    (input/input-set-value! inp "prog")
    (t/is (= ["a" "ab" "b"] @seen) "set-value! stays silent")))

(t/deftest test-input-on-change-fires-on-undo-and-paste
  (let [inp (input/make-input)
        seen (atom [])]
    (input/input-set-on-change! inp (fn [v] (swap! seen conj v)))
    (doseq [c "ab"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 31)))  ;; ctrl+- undo
    (core/handle-input inp (str "\u001b[200~" "X" "\u001b[201~"))
    (t/is (= ["a" "ab" "" "X"] @seen)
          "undo and paste both report the resulting value")))

;; ─── Pair consistency under concurrent readers ─────────────────────────────
;; Input dispatch and the render loop are serialized by the TUI's
;; dispatch-lock, but the value/cursor atoms are also read (and written) by
;; code outside that lock — host setters, extensions. The pair must never be
;; observably inconsistent: a torn read (short value, old longer cursor)
;; made render-line's (subs value 0 cursor) throw, which tears down the whole
;; TUI (a throwing render body stops the render loop).

(defn- record-pairs!
  "Watch the input's value/cursor atoms, recording the pair every writer
   leaves behind: the value watch sees (new value, live cursor), the cursor
   watch (live value, new cursor) — exactly what a concurrent render could
   read between the two resets."
  [inp]
  (let [pairs (atom [])]
    (add-watch (:value-atom inp) ::value
               (fn [_ _ _ new] (swap! pairs conj [new @(:cursor-atom inp)])))
    (add-watch (:cursor-atom inp) ::cursor
               (fn [_ _ _ new] (swap! pairs conj [@(:value-atom inp) new])))
    pairs))

(t/deftest test-input-pair-writes-keep-the-cursor-in-bounds
  (let [inp (input/make-input)
        pairs (record-pairs! inp)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    ;; every value-changing edit, shrinking and growing
    (core/handle-input inp "\u007f")                                 ;; backspace
    (core/handle-input inp "\u001b[3~")                              ;; forward delete
    (core/handle-input inp "\u001b[H")                               ;; home
    (core/handle-input inp (str (char 11)))                          ;; ctrl+k kill to end
    (core/handle-input inp (str (char 25)))                          ;; ctrl+y yank
    (core/handle-input inp (str (char 23)))                          ;; ctrl+w a second kill
    (core/handle-input inp (str (char 25)))                          ;; ctrl+y yank again
    (core/handle-input inp "\u001b[121;3u")                          ;; alt+y yank-pop
    (core/handle-input inp (str (char 31)))                          ;; ctrl+- undo
    (core/handle-input inp "\u001b[F")                               ;; end
    (core/handle-input inp (str (char 21)))                          ;; ctrl+u delete to start
    (core/handle-input inp (str "\u001b[200~" "xy" "\u001b[201~")) ;; paste
    (input/input-set-value! inp "shrink")
    (input/input-set-value! inp "s")
    (t/is (seq @pairs) "the writer watches recorded pairs")
    (t/is (every? (fn [[v c]] (<= c (count v))) @pairs)
          (str "a writer exposed cursor past end: "
               (pr-str (remove (fn [[v c]] (<= c (count v))) @pairs))))
    (t/is (= "s" (input/input-get-value inp)))))

(t/deftest test-input-render-tolerates-a-torn-cursor
  ;; an external writer (extension poking :cursor-atom) can still leave a
  ;; cursor past the value's end — render must clamp, not throw: a throwing
  ;; render body stops the whole TUI (the render-crash contract)
  (let [inp (input/make-input)]
    (doseq [c "ab"] (core/handle-input inp (str c)))
    (reset! (:cursor-atom inp) 5)
    (t/is (some? (core/render inp 20)) "torn cursor renders")
    (t/is (str/includes? (first (core/render inp 20)) "ab")
          "the value renders despite the torn cursor")))

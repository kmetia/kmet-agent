(ns kmet.tui.components.test-editor
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.utils :as u]
            [kmet.tui.keybindings :as kb]
            [kmet.app.keybindings :as app-kb]
            [babashka.fs :as fs]))

;; Raw key sequences matching what parse-key expects
(def ^:const K-LEFT "\u001b[D")
(def ^:const K-RIGHT "\u001b[C")
(def ^:const K-UP "\u001b[A")
(def ^:const K-DOWN "\u001b[B")
(def ^:const K-DEL "\u001b[3~")
(def ^:const K-BS "\u007f")
(def ^:const K-TAB "\t")
(def ^:const K-ENTER "\r")
(def ^:const K-ESC "\u001b")
(def ^:const K-ALT-LEFT "\u001bb")
(def ^:const K-ALT-RIGHT "\u001bf")
;; Control characters
(def ctrl (comp str char))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-editor-create
  (let [e (editor/make-editor)]
    (t/is (satisfies? core/IComponent e))
    (t/is (satisfies? core/IFocusable e))
    (t/is (= "" (editor/editor-get-text e)))
    (t/is (not (core/focused e)))))

(t/deftest test-editor-create-with-height
  (let [e (editor/make-editor :height 5)]
    (t/is (= 5 @(:height-atom e)))))

(t/deftest test-editor-focus
  (let [e (editor/make-editor)]
    (core/set-focused! e true)
    (t/is (core/focused e))
    (core/set-focused! e false)
    (t/is (not (core/focused e)))))

;; ─── Text operations ──────────────────────────────────────────────────────

(t/deftest test-editor-typing
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= "hello" (editor/editor-get-text e)))))

(t/deftest test-editor-typing-multi-line
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 10))  ;; ctrl+j = newline
    (doseq [c "world"] (core/handle-input e (str c)))
    (t/is (= "hello\nworld" (editor/editor-get-text e)))))

(t/deftest test-editor-backspace
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e K-BS)
    (t/is (= "hell" (editor/editor-get-text e)))
    (core/handle-input e K-BS)
    (t/is (= "hel" (editor/editor-get-text e)))))

(t/deftest test-editor-delete
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (core/handle-input e K-DEL)
    (t/is (= "ello" (editor/editor-get-text e)))))

(t/deftest test-editor-newline
  (let [e (editor/make-editor)]
    (doseq [c "ab"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 10))  ;; ctrl+j = newline
    (doseq [c "cd"] (core/handle-input e (str c)))
    (t/is (= "ab\ncd" (editor/editor-get-text e)))))

;; ─── Cursor movement ──────────────────────────────────────────────────────

(t/deftest test-editor-cursor-left-right
  (let [e (editor/make-editor)]
    (doseq [c "abcd"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 2))  ;; ctrl+b = left
    (core/handle-input e (ctrl 2))  ;; ctrl+b = left
    (core/handle-input e "X")
    (t/is (= "abXcd" (editor/editor-get-text e)))))

(t/deftest test-editor-cursor-home-end
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (core/handle-input e "X")
    (t/is (= "Xhello" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 5))  ;; ctrl+e = end
    (core/handle-input e "Z")
    (t/is (= "XhelloZ" (editor/editor-get-text e)))))

(t/deftest test-editor-cursor-word-left-right
  (let [e (editor/make-editor)]
    (doseq [c "hello world foo"] (core/handle-input e (str c)))
    (core/handle-input e "[1;5D") ;; ctrl+left
    (core/handle-input e "|")
    (t/is (= "hello world |foo" (editor/editor-get-text e)))
    (core/handle-input e "[1;5D")
    (core/handle-input e "|")
    (t/is (= "hello world| |foo" (editor/editor-get-text e))))
  (let [e (editor/make-editor)]
    (doseq [c "hello world foo"] (core/handle-input e (str c)))
    (core/handle-input e "[H") ;; home
    (core/handle-input e "[1;5C") ;; ctrl+right
    (core/handle-input e "|")
    (t/is (= "hello| world foo" (editor/editor-get-text e))))
  (let [e (editor/make-editor)]
    (doseq [c "abc def"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 10)) ;; newline
    (doseq [c "ghi"] (core/handle-input e (str c)))
    (core/handle-input e "[1;5D")
    (core/handle-input e "[1;5D")
    (core/handle-input e "|")
    (t/is (= "abc |def\nghi" (editor/editor-get-text e)))))

;; ─── Undo / Redo ──────────────────────────────────────────────────────────

(t/deftest test-editor-undo-typing
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 31))  ;; ctrl+- = undo
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-undo-redo
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 31))  ;; undo
    (t/is (= "" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 26))  ;; ctrl+z = redo
    (t/is (= "hello" (editor/editor-get-text e)))))

;; ─── Line editing ─────────────────────────────────────────────────────────

(t/deftest test-editor-ctrl-u
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (dotimes [_ 5] (core/handle-input e K-LEFT))
    (core/handle-input e (ctrl 21))  ;; ctrl+u = kill to line start
    (t/is (= "world" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-k
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (dotimes [_ 6] (core/handle-input e K-LEFT))
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to line end
    (t/is (= "hello" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-w
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 23))  ;; ctrl+w = kill line
    (t/is (= "" (editor/editor-get-text e)))))

;; ─── Yank ─────────────────────────────────────────────────────────────────

(t/deftest test-editor-yank
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))   ;; home
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to end
    (t/is (= "" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 25))  ;; ctrl+y = yank
    (t/is (= "hello" (editor/editor-get-text e)))))

;; ─── Submit ───────────────────────────────────────────────────────────────

(t/deftest test-editor-submit
  (let [e (editor/make-editor)
        submitted (atom nil)]
    (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e K-ENTER)
    (t/is (= "hello" @submitted))))

(t/deftest test-editor-submit-empty
  (let [e (editor/make-editor)
        submitted (atom nil)]
    (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
    (core/handle-input e K-ENTER)
    (t/is (= "" @submitted))))

(t/deftest test-editor-escape
  (let [e (editor/make-editor)
        cancelled (atom true)]
    (editor/editor-set-on-submit! e (fn [v] (reset! cancelled (nil? v))))
    (core/handle-input e K-ESC)
    (t/is @cancelled)))

(t/deftest test-editor-priority-action-before-builtin
  (t/testing "extension shortcuts (priority handlers) run BEFORE every builtin
              app action — app.interrupt (escape) included (pi:
              onExtensionShortcut); deregistering restores the builtin"
    (let [kmgr (kb/make-tui-keybindings-manager)
          _ (kb/register-definition! kmgr "ext.shortcut"
                                     {:default-keys ["escape"] :description "ext"})
          _ (kb/register-definition! kmgr "app.interrupt"
                                     {:default-keys ["escape"] :description "interrupt"})
          e (editor/make-editor :keybindings kmgr)
          builtin-ran (atom false)
          prio-ran (atom false)
          _ (editor/editor-set-on-action! e "app.interrupt"
                                          (fn [] (reset! builtin-ran true)))
          _ (editor/editor-set-priority-action!
             e "ext.shortcut" (fn [] (reset! prio-ran true)))]
      (core/handle-input e K-ESC)
      (t/is @prio-ran "priority handler ran on escape")
      (t/is (not @builtin-ran)
            "builtin app.interrupt did not run — the shortcut consumed the key")
      (editor/editor-set-priority-action! e "ext.shortcut" nil)
      (core/handle-input e K-ESC)
      (t/is @builtin-ran "after deregistration the builtin binding handles escape"))))

;; ─── set-text / get-text ──────────────────────────────────────────────────

(t/deftest test-editor-set-text
  (let [e (editor/make-editor)]
    (editor/editor-set-text! e "hello\nworld")
    (t/is (= "hello\nworld" (editor/editor-get-text e)))))

(t/deftest test-editor-set-text-empty
  (let [e (editor/make-editor)]
    (editor/editor-set-text! e "")
    (t/is (= "" (editor/editor-get-text e)))))

;; ─── History ──────────────────────────────────────────────────────────────

(t/deftest test-editor-history
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "world")
    (t/is (= ["hello" "world"] (editor/editor-get-history e)))))

(t/deftest test-editor-history-cycles-with-up-down
  ;; Up/Down cycle through submitted messages (pi: navigateHistory) — Up
  ;; leaves the cursor at the start so repeated Ups keep navigating; Down
  ;; leaves it at the end so repeated Downs keep navigating.
  (let [e (editor/make-editor)
        st #(deref (:state-atom e))]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "world")
    ;; Up → newest entry, cursor at start
    (core/handle-input e K-UP)
    (t/is (= ["world"] (:lines (st))))
    (t/is (= [0 0] [(:cursor-line (st)) (:cursor-col (st))]))
    ;; Up again → older entry (this failed before: the cursor was left at
    ;; the end, so Up moved up a line instead of navigating history)
    (core/handle-input e K-UP)
    (t/is (= ["hello"] (:lines (st))))
    (t/is (= [0 0] [(:cursor-line (st)) (:cursor-col (st))]))
    ;; Down → newer entry, then past the newest → back to the draft
    (core/handle-input e K-DOWN)
    (t/is (= ["world"] (:lines (st))))
    (t/is (= 1 @(:history-idx e)))
    (core/handle-input e K-DOWN)
    (t/is (= [""] (:lines (st))))
    (t/is (= -1 @(:history-idx e)))))

(t/deftest test-editor-history-multi-line-cursor-placement
  ;; Down navigation leaves the cursor at the END of the entry (pi:
  ;; cursorPlacement "end") so repeated Downs keep navigating from the
  ;; last visual line.
  (let [e (editor/make-editor)
        st #(deref (:state-atom e))]
    (editor/editor-push-history! e "line one\nline two")
    (core/handle-input e K-UP)
    (t/is (= [0 0] [(:cursor-line (st)) (:cursor-col (st))])
          "Up places the cursor at the start")
    ;; Down from the first visual line moves down (not history) until the
    ;; last visual line is reached
    (core/handle-input e K-DOWN)
    (t/is (= [1 0] [(:cursor-line (st)) (:cursor-col (st))])
          "Down keeps the preferred column while moving through lines")
    (t/is (= 0 @(:history-idx e)) "still browsing")
    ;; Down from the last visual line navigates past the newest → draft
    (core/handle-input e K-DOWN)
    (t/is (= [""] (:lines (st)))
          "Down past the newest entry restores the draft")
    (t/is (= -1 @(:history-idx e)))))

(t/deftest test-editor-history-no-duplicates
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "hello")
    (t/is (= ["hello"] (editor/editor-get-history e)))))

(t/deftest test-editor-set-history
  (let [e (editor/make-editor)]
    (editor/editor-set-history! e ["a" "b" "c"])
    (t/is (= ["a" "b" "c"] (editor/editor-get-history e)))))

;; ─── Height ───────────────────────────────────────────────────────────────

(t/deftest test-editor-set-height
  (let [e (editor/make-editor :height 10)]
    (t/is (= 10 @(:height-atom e)))
    (editor/editor-set-height! e 20)
    (t/is (= 20 @(:height-atom e)))))

;; ─── On-change callback ───────────────────────────────────────────────────

(t/deftest test-editor-on-change
  (let [e (editor/make-editor)
        changes (atom [])]
    (editor/editor-set-on-change! e (fn [t] (swap! changes conj t)))
    (doseq [c "ab"] (core/handle-input e (str c)))
    (t/is (= 2 (count @changes)))
    (t/is (= "a" (first @changes)))
    (t/is (= "ab" (second @changes)))))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-editor-render
  (let [e (editor/make-editor :height 5 :padding-x 1)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (let [lines (core/render e 20)]
      (t/is (pos? (count lines)))
      (t/is (str/starts-with? (first lines) "───"))
      (t/is (str/ends-with? (last lines) "───")))))

(t/deftest test-editor-render-cursor-shows-character-under-it
  ;; Regression: moving the cursor onto the line's LAST character must
  ;; highlight that character, not replace it with a blank block. The
  ;; at-end branch is selected by an empty grapheme at the cursor position
  ;; (pi: `after.length > 0`), not by an empty tail after it — a cursor on
  ;; the last character has an empty tail too.
  (let [e (editor/make-editor :height 3)
        visible (fn [line]
                  (u/strip-ansi-codes (str/replace line u/CURSOR-MARKER "")))]
    (core/set-focused! e true)
    (doseq [c "abc"] (core/handle-input e (str c)))
    ;; cursor on the last character
    (core/handle-input e K-LEFT)
    (let [line (second (core/render e 20))]
      (t/is (str/includes? line "\u001b[7mc\u001b[0m")
            "the character under the cursor is shown inverted")
      (t/is (= "abc" (str/trim (visible line)))
            "no character is dropped from the line"))
    ;; mid-line cursor
    (core/handle-input e K-LEFT)
    (let [line (second (core/render e 20))]
      (t/is (str/includes? line "\u001b[7mb\u001b[0m")
            "mid-line cursor highlights its character too")
      (t/is (= "abc" (str/trim (visible line)))
            "no character is dropped from the line"))
    ;; cursor at the end: the block sits on an added space
    (dotimes [_ 2] (core/handle-input e K-RIGHT))
    (let [line (second (core/render e 20))]
      (t/is (str/includes? line "\u001b[7m \u001b[0m")
            "at end the block sits on a highlighted space")
      (t/is (= "abc" (str/trim (visible line)))
            "text is intact at end"))))

(t/deftest test-editor-render-empty
  (let [e (editor/make-editor :height 3)
        lines (core/render e 20)]
    (t/is (pos? (count lines)))
    (t/is (> (count lines) 1))))

;; ─── Text length ──────────────────────────────────────────────────────────

(t/deftest test-editor-text-length
  (let [e (editor/make-editor)]
    (t/is (zero? (editor/editor-get-text-length e)))
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= 5 (editor/editor-get-text-length e)))))

;; ─── Editor with border function ──────────────────────────────────────────

(t/deftest test-editor-border-fn
  (let [e (editor/make-editor :height 3 :border-fn (fn [_] "="))]
    (doseq [c "hi"] (core/handle-input e (str c)))
    (let [lines (core/render e 10)]
      (t/is (str/starts-with? (first lines) "=="))
      (t/is (str/ends-with? (last lines) "==")))))

(t/deftest test-editor-border-set
  ;; the rule glyph above and below the text comes from a kmet.tui.border
  ;; set (R5); :none keeps the default bar, since a rule component with no
  ;; glyph would have nothing to draw
  (let [rule (fn [style] (first (core/render (editor/make-editor :height 3
                                                                 :border style)
                                             10)))]
    (t/is (= "──────────" (rule nil)) "default is :normal")
    (t/is (= "──────────" (rule :normal)))
    (t/is (= "----------" (rule :ascii)))
    (t/is (= "          " (rule :hidden)) "same footprint, no ink")
    (t/is (= "──────────" (rule :none)) "a rule keeps its default glyph")
    (t/testing "an unknown style fails at construction"
      (t/is (thrown-with-msg? Exception #"unknown border style"
                              (editor/make-editor :border :asci))))))

(t/deftest test-editor-border-fn-styles-the-chosen-glyph
  ;; border-fn wraps whatever glyph the set provides
  (let [e (editor/make-editor :height 3 :border :ascii :border-fn (fn [s] (str "<" s ">")))]
    (t/is (str/starts-with? (first (core/render e 6)) "<->"))))

(t/deftest test-editor-top-border-fn
  ;; An installed top-border fn renders the first line (the app's
  ;; status-in-border hook, pi: CustomEditor.renderTopBorder); returning
  ;; nil falls back to the default rule.
  (let [seen (atom nil)
        e (editor/make-editor :height 3 :border-fn (fn [s] (str "<" s ">")))]
    (editor/editor-set-top-border-fn!
     e
     (fn [props]
       (reset! seen props)
       (str "STATUS " (apply str (repeat (- (:width props) 7) (:rule props))))))
    (t/is (= "STATUS ─────" (first (core/render e 12))))
    (t/is (= 12 (:width @seen)))
    (t/is (= 0 (:hidden-line-count @seen)))
    (t/is (= "─" (:rule @seen)))
    (t/is (fn? (:border-fn @seen)) "the editor's border color fn reaches the hook")
    (t/testing "a declining hook falls back to the default rule"
      (editor/editor-set-top-border-fn! e (fn [_] nil))
      (t/is (str/starts-with? (first (core/render e 12)) "<─><─>")))
    (t/testing "clearing the hook restores the default"
      (editor/editor-set-top-border-fn! e nil)
      (t/is (str/starts-with? (first (core/render e 12)) "<─><─>")))
    (t/testing "the hook sees the scroll count and composes with the scrolled view"
      (let [e (editor/make-editor :height 3)
            lines (map #(str "line " %) (range 10))
            props (atom nil)]
        (core/handle-input e (str "\u001b[200~" (str/join "\n" lines) "\u001b[201~"))
        (editor/editor-set-top-border-fn! e (fn [p] (reset! props p) nil))
        (core/render e 40)
        (t/is (pos? (:hidden-line-count @props)) "the hook learns about hidden lines")
        (editor/editor-set-top-border-fn! e
                                          (fn [p] (str "H" (:hidden-line-count p) " " (:width p))))
        (t/is (= (str "H" (:hidden-line-count @props) " 40") (first (core/render e 40))))))))

(t/deftest test-editor-scroll-border-centered
  ;; pi: createScrollBorder — the ` ↑ N more ` label is centered on the line
  (let [e (editor/make-editor :height 3)
        lines (map #(str "line " %) (range 10))
        props (atom nil)]
    (core/handle-input e (str "\u001b[200~" (str/join "\n" lines) "\u001b[201~"))
    (editor/editor-set-top-border-fn! e (fn [p] (reset! props p) nil))
    (core/render e 46)
    (let [hidden (:hidden-line-count @props)
          label (str " ↑ " hidden " more ")
          top (first (core/render e 46))]
      (t/is (pos? hidden) "the view is scrolled")
      (t/is (= (quot (- 46 (count label)) 2) (str/index-of top label))
            "the scroll label is centered")
      (t/is (= 46 (u/visible-width top))))))

(t/deftest test-editor-bottom-scroll-border-centered
  ;; the bottom border uses the same centered label for lines below the view
  (let [e (editor/make-editor :height 3)
        lines (map #(str "line " %) (range 10))]
    (core/handle-input e (str "\u001b[200~" (str/join "\n" lines) "\u001b[201~"))
    (dotimes [_ 8] (core/handle-input e K-UP))
    (let [bottom (last (core/render e 46))
          m (re-find #"↓ (\d+) more" bottom)]
      (t/is (some? m) "content below the viewport shows the ↓ label")
      (let [label (str " ↓ " (second m) " more ")]
        (t/is (= (quot (- 46 (count label)) 2) (str/index-of bottom label))
              "the bottom label is centered")
        (t/is (= 46 (u/visible-width bottom)))))))

;; ─── Edge cases ──────────────────────────────────────────────────────────

(t/deftest test-editor-backspace-at-start
  (let [e (editor/make-editor)]
    (core/handle-input e K-BS)
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-delete-at-end
  (let [e (editor/make-editor)]
    (doseq [c "hi"] (core/handle-input e (str c)))
    (core/handle-input e K-DEL)
    (t/is (= "hi" (editor/editor-get-text e)))))

(t/deftest test-editor-undo-empty
  (let [e (editor/make-editor)]
    (core/handle-input e (ctrl 31))  ;; ctrl+-
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-u-at-start
  (let [e (editor/make-editor)]
    (core/handle-input e (ctrl 21))
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-k-at-end
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 11))
    (t/is (= "hello" (editor/editor-get-text e)))))

;; ─── Regression: grapheme operations with cached BreakIterator ───────────

(t/deftest test-editor-grapheme-cursor-movement
  (let [e (editor/make-editor)]
    ;; Type text
    (doseq [c "cafe"] (core/handle-input e (str c)))
    (t/is (= "cafe" (editor/editor-get-text e)))
    ;; LEFT x3: cursor at index 1 (after 'c', before 'a')
    (dotimes [_ 3] (core/handle-input e K-LEFT))
    ;; Insert 'X' at cursor → "cXafe"
    (core/handle-input e "X")
    (t/is (= "cXafe" (editor/editor-get-text e)) "Insert at cursor position")
    ;; HOME → move cursor to start
    (core/handle-input e (ctrl 1))  ;; home
    (core/handle-input e "X")
    (t/is (= "XcXafe" (editor/editor-get-text e)) "Home then insert works")
    ;; END → move to end, BACKSPACE deletes last char
    (core/handle-input e (ctrl 5))  ;; end
    (core/handle-input e K-BS)
    (t/is (= "XcXaf" (editor/editor-get-text e)) "Backspace removes last char")))

(t/deftest test-editor-kill-and-yank-with-graphemes
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))   ;; home
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to end
    (t/is (= "" (editor/editor-get-text e)) "Kill line removes all text")
    (core/handle-input e (ctrl 25))  ;; ctrl+y = yank
    (t/is (= "hello" (editor/editor-get-text e)) "Yank restores killed text")))

;; ─── Paste ────────────────────────────────────────────────────────────────

(t/deftest test-editor-paste-small
  (let [e (editor/make-editor)]
    (core/handle-input e (str "\u001b[200~" "hello world" "\u001b[201~"))
    (t/is (= "hello world" (editor/editor-get-text e)))
    (t/is (= :idle @(:paste-state e)) "paste state returns to idle")
    ;; Cursor lands after the pasted text, so typing continues after it
    (let [{:keys [cursor-col]} @(:state-atom e)]
      (t/is (= (count "hello world") cursor-col))))
  (let [e (editor/make-editor)]
    ;; Paste into the middle of an existing line
    (doseq [c "abc"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 2))  ;; ctrl+b = left
    (core/handle-input e (str "\u001b[200~" "XY" "\u001b[201~"))
    (t/is (= "abXYc" (editor/editor-get-text e)))
    (t/is (= 4 (:cursor-col @(:state-atom e))) "cursor after the pasted text")))

(t/deftest test-editor-paste-multi-line-streamed
  ;; \r (enter) mid-paste must be buffered, not trigger submit/newline
  (let [e (editor/make-editor)
        submitted (atom false)]
    (editor/editor-set-on-submit! e (fn [_] (reset! submitted true)))
    (core/handle-input e "\u001b[200~")
    (core/handle-input e "line1\r\n")
    (core/handle-input e "line2\u001b[201~")
    (t/is (= "line1\nline2" (editor/editor-get-text e)))
    (t/is (not @submitted))))

(t/deftest test-editor-paste-lone-cr-normalized
  ;; Terminals in raw mode send CR for line breaks; a paste of \r-separated
  ;; lines must split like \n (pi editor.ts normalizeText: \r\n and \r -> \n)
  (let [e (editor/make-editor)
        submitted (atom false)]
    (editor/editor-set-on-submit! e (fn [_] (reset! submitted true)))
    (core/handle-input e "\u001b[200~line1\rline2\rline3\u001b[201~")
    (t/is (= "line1\nline2\nline3" (editor/editor-get-text e)))
    (t/is (not @submitted) "lone CR must not trigger submit")
    (t/is (= :idle @(:paste-state e))))
  ;; tabs expand to 4 spaces (pi normalizeText)
  (let [e (editor/make-editor)]
    (core/handle-input e "\u001b[200~a\tb\u001b[201~")
    (t/is (= "a    b" (editor/editor-get-text e)))))

(t/deftest test-editor-scroll-border-fits-width
  ;; Regression: the "─── ↑ N more ───" border overflowed by one column for
  ;; single-digit N (6 + digits + 6 fixed chars vs width-12 dashes), tripping
  ;; the render loop's width-overflow crash when the editor scrolled.
  (let [e (editor/make-editor :height 3)
        lines (vec (for [i (range 10)] (str "line " i)))]
    (core/handle-input e (str "\u001b[200~" (clojure.string/join "\n" lines) "\u001b[201~"))
    (doseq [width [10 20 46 100]]
      (let [rendered (core/render e width)]
        (t/is (seq rendered))
        (t/is (some #(clojure.string/includes? % "↑") rendered)
              (str "scrolled editor shows ↑ border at width " width))
        (doseq [line rendered]
          (t/is (<= (u/visible-width line) width)
                (str "rendered line fits width " width ": " (pr-str line))))))))

(t/deftest test-editor-paste-marker-created
  (let [e (editor/make-editor)
        big (clojure.string/join "\n" (repeat 15 "line"))]
    (core/handle-input e (str "\u001b[200~" big "\u001b[201~"))
    (t/is (clojure.string/includes? (editor/editor-get-text e) "[paste #1 +15 lines"))
    (t/is (= 1 (count @(:paste-store e))))
    (t/is (= big (editor/editor-get-paste e 1))))
  ;; pi: large paste = >10 lines OR >1000 chars (chars variant marker)
  (let [e (editor/make-editor)
        big (apply str (repeat 1200 "x"))]
    (core/handle-input e (str "\u001b[200~" big "\u001b[201~"))
    (t/is (clojure.string/includes? (editor/editor-get-text e) "[paste #1 1200 chars"))
    (t/is (= big (editor/editor-get-paste e 1))))
  ;; 10 lines / 1000 chars exactly is still inline (pi: strictly greater)
  (let [e (editor/make-editor)
        ten (clojure.string/join "\n" (repeat 10 "ab"))]
    (core/handle-input e (str "\u001b[200~" ten "\u001b[201~"))
    (t/is (= ten (editor/editor-get-text e)) "boundary stays inline")
    (t/is (empty? @(:paste-store e)))))

(t/deftest test-editor-paste-marker-atomic-backspace
  (let [e (editor/make-editor)
        big (clojure.string/join "\n" (repeat 15 "line"))]
    (core/handle-input e (str "\u001b[200~" big "\u001b[201~"))
    ;; Cursor sits at end of the marker — one backspace removes it entirely
    (core/handle-input e K-BS)
    (t/is (= "" (editor/editor-get-text e)))
    (t/is (empty? @(:paste-store e)))))

(t/deftest test-editor-paste-marker-atomic-cursor
  (let [e (editor/make-editor)
        big (clojure.string/join "\n" (repeat 15 "line"))]
    (core/handle-input e (str "\u001b[200~" big "\u001b[201~"))
    (let [marker (editor/editor-get-text e)]
      (core/handle-input e (ctrl 1))  ;; ctrl+a = home
      (core/handle-input e K-RIGHT)   ;; right arrow skips the whole marker
      (t/is (= (count marker) (:cursor-col @(:state-atom e))))
      ;; left arrow from end also skips the whole marker
      (core/handle-input e K-LEFT)
      (t/is (= 0 (:cursor-col @(:state-atom e)))))))

(t/deftest test-editor-paste-renumbering
  (let [e (editor/make-editor)
        big1 (clojure.string/join "\n" (repeat 15 "one"))
        big2 (clojure.string/join "\n" (repeat 15 "two"))
        big3 (clojure.string/join "\n" (repeat 15 "three"))]
    (core/handle-input e (str "\u001b[200~" big1 "\u001b[201~"))
    (core/handle-input e (str "\u001b[200~" big2 "\u001b[201~"))
    (core/handle-input e (str "\u001b[200~" big3 "\u001b[201~"))
    (t/is (= #{1 2 3} (set (keys @(:paste-store e)))))
    ;; Delete the first marker with forward-delete
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (core/handle-input e K-DEL)
    (let [text (editor/editor-get-text e)]
      (t/is (= "[paste #1 +15 lines — ctrl+o to expand][paste #2 +15 lines — ctrl+o to expand]"
               text)
            "remaining markers renumbered to close the gap")
      (t/is (= #{1 2} (set (keys @(:paste-store e)))))
      (t/is (= big2 (editor/editor-get-paste e 1)))
      (t/is (= big3 (editor/editor-get-paste e 2))))))

(t/deftest test-editor-paste-csi-u
  (let [e (editor/make-editor)]
    (core/handle-input e (str "\u001b[200~" "abc\u001b[97;5u" "def" "\u001b[201~"))
    (t/is (= "abcdef" (editor/editor-get-text e))
          "decoded ctrl byte filtered (pi: decode, then <32 filter)")))

(t/deftest test-editor-paste-smart-path-spacing
  (let [e (editor/make-editor)]
    (doseq [c "cd"] (core/handle-input e (str c)))
    (core/handle-input e (str "\u001b[200~" "/tmp/x" "\u001b[201~"))
    (t/is (= "cd /tmp/x" (editor/editor-get-text e))))
  (let [e (editor/make-editor)]
    (doseq [c "cd "] (core/handle-input e (str c)))
    (core/handle-input e (str "\u001b[200~" "/tmp/x" "\u001b[201~"))
    (t/is (= "cd /tmp/x" (editor/editor-get-text e)) "no double space after existing space")))

;; ─── History draft ────────────────────────────────────────────────────────

(t/deftest test-editor-history-draft
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "world")
    (doseq [c "draft"] (core/handle-input e (str c)))
    (t/is (= "draft" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 16))  ;; ctrl+p = history-backward
    (t/is (= "world" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 14))  ;; ctrl+n = history-forward
    (t/is (= "draft" (editor/editor-get-text e)) "typed draft restored after round-trip")
    ;; Browsing again, Ctrl+Z mid-browse returns to the draft
    (core/handle-input e (ctrl 16))
    (core/handle-input e (ctrl 16))  ;; one entry deeper
    (t/is (= "hello" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 31))  ;; ctrl+- = undo → back to draft
    (t/is (= "draft" (editor/editor-get-text e)) "Ctrl+Z while browsing restores the draft")))

(t/deftest test-editor-history-draft-empty-history
  (let [e (editor/make-editor)]
    (doseq [c "draft"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 16))
    (t/is (= "draft" (editor/editor-get-text e)) "no history, no change")))

(t/deftest test-editor-paste-exits-history-browsing
  ;; pi handlePaste calls exitHistoryBrowsing: pasting while browsing
  ;; history commits to the browsed text and leaves browsing mode.
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "world")
    (doseq [c "draft"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 16))
    (t/is (= "world" (editor/editor-get-text e)))
    (core/handle-input e (str "\u001b[200~" "PASTE" "\u001b[201~"))
    (t/is (= "PASTEworld" (editor/editor-get-text e))
          "paste lands at cursor (history browse parks cursor at col 0)")
    (t/is (= -1 @(:history-idx e)) "browsing mode exited")
    (t/is (nil? @(:history-draft e)) "draft discarded")
    (core/handle-input e (ctrl 16))
    (t/is (= "world" (editor/editor-get-text e))
          "next browse starts from history top, not the paste")
    (t/is (= ["PASTEworld"] (:lines @(:history-draft e))) "fresh draft captured")))

;; ─── Dynamic height ───────────────────────────────────────────────────────

(t/deftest test-editor-dynamic-height
  (let [text (clojure.string/join "\n" (repeat 30 "line"))]
    (let [e (editor/make-editor :terminal-rows (fn [] 40))]
      (editor/editor-set-text! e text)
      (t/is (= 14 (count (core/render e 40))) "40 rows → 30% = 12 visible + 2 borders"))
    (let [e (editor/make-editor :terminal-rows (fn [] 8))]
      (editor/editor-set-text! e text)
      (t/is (= 7 (count (core/render e 40))) "8 rows → min 5 visible + 2 borders"))
    (let [e (editor/make-editor :height 6)]
      (editor/editor-set-text! e text)
      (t/is (= 8 (count (core/render e 40))) "fixed :height fallback 6 + 2 borders"))))

(t/deftest test-editor-terminal-rows-setter
  (let [e (editor/make-editor)]
    (editor/editor-set-terminal-rows! e (fn [] 30))
    (t/is (= 30 ((deref (:terminal-rows-atom e)))))
    (editor/editor-set-terminal-rows! e nil)
    (t/is (nil? @(:terminal-rows-atom e)))))

(t/deftest test-editor-render-marker-atomic-wrap
  ;; Word-wrap must not break inside a paste marker, even when the marker
  ;; is wider than the wrap width (marker overflows to its own chunk)
  (let [e (editor/make-editor)
        big (clojure.string/join "\n" (repeat 30 "line"))]
    ;; Create the marker the real way (paste populates the store)
    (core/handle-input e (str "\u001b[200~" big "\u001b[201~"))
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (doseq [c "aaa bbb ccc "] (core/handle-input e (str c)))
    (let [m "[paste #1 +30 lines — ctrl+o to expand]"
          text (editor/editor-get-text e)
          lines (core/render e 30)]
      (t/is (clojure.string/includes? text m) "text contains the whole marker")
      (t/is (some #(clojure.string/includes? % m) lines)
            "marker appears whole on one visual line")
      (t/is (not-any? #(and (clojure.string/includes? % "[paste #")
                            (not (clojure.string/includes? % m)))
                      lines)
            "no partial marker fragments"))))

(t/deftest test-word-wrap-line-backtrack
  ;; Word-boundary backtracking must keep words intact and never exceed
  ;; max-width: the running width after backtracking is cw - wrapOppWidth
  ;; (not the emitted chunk's width), and the break lands AFTER the trailing
  ;; whitespace (pi: wrapOppIndex = next.index). The old math emitted an
  ;; extra mid-word hard break right after every word-boundary wrap — the
  ;; editor "wrapped strangely" (e.g. splitting "quiet" as q / uiet) even
  ;; before any terminal resize.
  (let [wrap (fn [text w] (mapv :text (#'editor/word-wrap-line text w #{})))]
    (t/testing "word-boundary backtrack: trailing space stays on the wrapped line"
      (t/is (= ["abc " "def " "ghi"] (wrap "abc def ghi" 5))))
    (t/testing "no mid-word break after a backtrack"
      (t/is (= ["The quick brown fox jumps " "over the lazy dog while the "
                "rain falls gently on the " "quiet meadow"]
               (wrap "The quick brown fox jumps over the lazy dog while the rain falls gently on the quiet meadow" 30))))
    (t/testing "wrapping at the editor layout width keeps long words intact"
      (t/is (= ["The quick brown fox jumps over the lazy dog while the rain falls gently on the "
                "quiet meadow"]
               (wrap "The quick brown fox jumps over the lazy dog while the rain falls gently on the quiet meadow" 79))))
    (t/testing "every chunk fits within max-width"
      (let [text (clojure.string/join " " (map #(str "word" %) (range 40)))
            chunks (wrap text 17)]
        (t/is (seq chunks))
        (t/is (every? #(<= (count %) 17) chunks) "no chunk exceeds max-width")
        (t/is (= text (apply str chunks)) "wrapping preserves the full text")
        (t/is (every? #(not (clojure.string/starts-with? % " ")) chunks)
              "no chunk starts with whitespace")))))

(t/deftest test-editor-undo-restores-paste-store
  (let [e (editor/make-editor)
        big1 (clojure.string/join "\n" (repeat 15 "one"))
        big2 (clojure.string/join "\n" (repeat 15 "two"))]
    (core/handle-input e (str "\u001b[200~" big1 "\u001b[201~"))
    (core/handle-input e (str "\u001b[200~" big2 "\u001b[201~"))
    ;; Delete the first marker → #2 renumbered to #1, store {1: big2}
    (core/handle-input e (ctrl 1))
    (core/handle-input e K-DEL)
    (t/is (= "[paste #1 +15 lines — ctrl+o to expand]" (editor/editor-get-text e)))
    (t/is (= #{1} (set (keys @(:paste-store e)))))
    ;; Undo restores both markers AND the original store entries
    (core/handle-input e (ctrl 31))
    (t/is (= "[paste #1 +15 lines — ctrl+o to expand][paste #2 +15 lines — ctrl+o to expand]"
             (editor/editor-get-text e)))
    (t/is (= #{1 2} (set (keys @(:paste-store e)))) "undo restores the original store")
    (t/is (= big1 (editor/editor-get-paste e 1)))
    (t/is (= big2 (editor/editor-get-paste e 2)))))

;; ─── Autocomplete ──────────────────────────────────────────────────────────

(def ^:private ac-test-dir (str (or (System/getenv "TMPDIR")
                                    (System/getProperty "user.home"))
                                "/kmet-editor-ac-test"))

(defn- with-ac-files
  [f]
  (babashka.fs/delete-tree ac-test-dir)
  (babashka.fs/create-dirs ac-test-dir)
  (spit (str ac-test-dir "/alpha.txt") "a")
  (spit (str ac-test-dir "/beta.txt") "b")
  (ac/invalidate-file-cache!)
  (try
    (f)
    (finally
      (babashka.fs/delete-tree ac-test-dir))))

(def ^:private ac-commands
  [{:name "model" :description "Switch model" :argument-hint "<provider:model>"}
   {:name "theme" :description "Switch theme"
    :get-argument-completions (fn [_] [{:value "dark"} {:value "light"}])}
   {:name "new" :description "Start a new session"}])

(defn- make-ac-editor
  []
  (let [e (editor/make-editor)]
    (core/editor-set-autocomplete-provider! e
                                            (ac/make-combined-provider
                                             :commands-fn (constantly ac-commands)
                                             :base-path ac-test-dir))
    e))

(t/deftest test-autocomplete-tab-without-provider-inserts-spaces
  (let [e (editor/make-editor)]
    (core/handle-input e K-TAB)
    (t/is (= "    " (editor/editor-get-text e)))))

(t/deftest test-autocomplete-slash-opens-dropdown
  (let [e (make-ac-editor)]
    (core/handle-input e "/")
    (t/is (= :regular @(:autocomplete-state e)))
    (t/is (some? @(:autocomplete-list e)))
    (t/is (= "/" @(:autocomplete-prefix e)))
    (t/is (= "/" (editor/editor-get-text e)) "dropdown does not insert extra text")))

(t/deftest test-autocomplete-plain-text-no-dropdown
  (let [e (make-ac-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (nil? @(:autocomplete-state e)))
    (t/is (= "hello" (editor/editor-get-text e)))))

(t/deftest test-autocomplete-letters-filter-dropdown
  (let [e (make-ac-editor)]
    (doseq [c "/th"] (core/handle-input e (str c)))
    (t/is (= :regular @(:autocomplete-state e)))
    (let [sl @(:autocomplete-list e)]
      (t/is (= "theme" (:value (select-list/select-list-get-selected sl)))
            "selection resolves from filtered items"))))

(t/deftest test-autocomplete-tab-applies-selection
  (let [e (make-ac-editor)]
    (doseq [c "/mo"] (core/handle-input e (str c)))
    (core/handle-input e K-TAB)
    (t/is (= "/model " (editor/editor-get-text e)))
    (t/is (nil? @(:autocomplete-state e)) "dropdown closes after apply")))

(t/deftest test-autocomplete-enter-applies-and-submits-slash
  (let [e (make-ac-editor)
        submitted (atom nil)]
    (core/editor-set-on-submit! e #(reset! submitted %))
    (doseq [c "/mo"] (core/handle-input e (str c)))
    (core/handle-input e K-ENTER)
    (t/is (= "/model " @submitted) "slash completion applies then submits")
    (t/is (nil? @(:autocomplete-state e)))))

(t/deftest test-autocomplete-escape-cancels
  (let [e (make-ac-editor)
        submitted (atom :none)]
    (core/editor-set-on-submit! e #(reset! submitted %))
    (core/handle-input e "/")
    (core/handle-input e K-ESC)
    (t/is (nil? @(:autocomplete-state e)))
    (t/is (= :none @submitted) "escape does not submit")))

(t/deftest test-autocomplete-at-trigger-file-completion
  (with-ac-files
    (fn []
      (let [e (make-ac-editor)]
        (core/handle-input e "@")
        (t/is (= :regular @(:autocomplete-state e)))
        (core/handle-input e "b")
        (t/is (= :regular @(:autocomplete-state e)))
        (core/handle-input e K-TAB)
        (t/is (= "@beta.txt " (editor/editor-get-text e)))))))

(t/deftest test-autocomplete-new-at-token-and-message-boundary-invalidate
  (with-ac-files
    (fn []
      (let [e (make-ac-editor)
            calls (atom 0)]
        (with-redefs [ac/invalidate-file-cache! (fn [] (swap! calls inc))]
          (core/handle-input e "@")
          (t/is (= 1 @calls) "an @ at line start walks fresh")
          (core/handle-input e " ")
          (doseq [c "foo"] (core/handle-input e (str c)))
          (core/handle-input e "@")
          (t/is (= 1 @calls) "a mid-word @ starts no mention token")
          (core/handle-input e " ")
          (core/handle-input e "@")
          (t/is (= 2 @calls) "an @ after whitespace walks fresh")
          (core/handle-input e "b")
          (t/is (= 2 @calls) "continuation reuses the token's snapshot")
          ;; the previous dropdown is still open here: the refresh must not
          ;; depend on the dropdown-state branch
          (core/handle-input e " ")
          (core/handle-input e "@")
          (t/is (= 3 @calls) "a new @ token refreshes even with the dropdown open")
          (core/handle-input e " ")
          (core/handle-input e "#")
          (t/is (= 3 @calls) "a # token has no file snapshot to refresh")
          (core/handle-input e K-ESC)
          (core/editor-set-on-submit! e (fn [_] nil))
          (core/handle-input e K-ENTER)
          (t/is (= 4 @calls) "submit starts the next message fresh")
          (core/editor-set-text! e "")
          (t/is (= 5 @calls) "clearing the editor also ends the message"))))))

(t/deftest test-autocomplete-new-at-token-sees-files-added-mid-message
  (with-ac-files
    (fn []
      (let [e (make-ac-editor)]
        (core/handle-input e "@")
        (doseq [c "beta"] (core/handle-input e (str c)))
        ;; exists only after the first token's snapshot was taken
        (spit (str ac-test-dir "/omega.txt") "o")
        (core/handle-input e " ")
        (core/handle-input e "@")
        (doseq [c "omeg"] (core/handle-input e (str c)))
        (core/handle-input e K-TAB)
        (t/is (str/includes? (editor/editor-get-text e) "@omega.txt")
              "the second @ token's fresh walk sees omega.txt")))))

(t/deftest test-autocomplete-tab-file-completion-single-match-applies
  (with-ac-files
    (fn []
      (let [e (make-ac-editor)]
        (doseq [c "alp"] (core/handle-input e (str c)))
        (core/handle-input e K-TAB)
        (t/is (= "alpha.txt" (editor/editor-get-text e))
              "single file match applies immediately")
        (t/is (nil? @(:autocomplete-state e)))))))

(t/deftest test-autocomplete-up-down-navigates
  (let [e (make-ac-editor)]
    (core/handle-input e "/")
    (let [sl @(:autocomplete-list e)]
      (t/is (= 0 @(:selected-idx-atom sl)))
      (core/handle-input e K-DOWN)
      (t/is (= 1 @(:selected-idx-atom sl)))
      (core/handle-input e K-UP)
      (t/is (= 0 @(:selected-idx-atom sl))))))

(t/deftest test-autocomplete-set-text-cancels
  (let [e (make-ac-editor)]
    (core/handle-input e "/")
    (t/is (= :regular @(:autocomplete-state e)))
    (core/editor-set-text! e "clear")
    (t/is (nil? @(:autocomplete-state e)))
    (t/is (= "clear" (editor/editor-get-text e)))))

;; ─── App actions (pi: CustomEditor) ────────────────────────────────────────

(defn- make-action-editor
  "Editor wired with the app-level KeybindingsManager so app action IDs
   (app.interrupt, app.exit, app.tools.expand, ...) resolve to keys."
  []
  (editor/make-editor
   :keybindings (app-kb/make-agent-keybindings-manager)))

(t/deftest test-editor-action-register
  (let [e (editor/make-editor)
        called (atom 0)]
    (editor/editor-set-on-action! e "app.clear" (fn [] (swap! called inc)))
    (t/is (= 1 (count @(:action-handlers e))))
    (t/is (fn? (get @(:action-handlers e) "app.clear")))
    ;; Replace
    (editor/editor-set-on-action! e "app.clear" (fn [] (swap! called inc)))
    (t/is (= 1 (count @(:action-handlers e))))
    (editor/editor-set-on-action! e "app.clear" nil)
    (t/is (empty? @(:action-handlers e)))))

(t/deftest test-editor-action-other-action-fires
  ;; ctrl+o (app.tools.expand) dispatches the registered handler
  (let [e (make-action-editor)
        fired (atom nil)]
    (editor/editor-set-on-action! e "app.tools.expand" (fn [] (reset! fired true)))
    (core/handle-input e (ctrl 15))  ;; ctrl+o
    (t/is @fired)
    (t/is (= "" (editor/editor-get-text e)) "action does not insert text")))

(t/deftest test-editor-action-ctrl-d-exit-when-empty
  (let [e (make-action-editor)
        exited (atom 0)]
    (editor/editor-set-on-action! e "app.exit" (fn [] (swap! exited inc)))
    (core/handle-input e (ctrl 4))  ;; ctrl+d on empty editor → exit
    (t/is (= 1 @exited))))

(t/deftest test-editor-action-ctrl-d-deletes-when-not-empty
  ;; pi: app.exit only fires when the editor is empty; otherwise ctrl+d
  ;; falls through to delete-char-forward
  (let [e (make-action-editor)
        exited (atom 0)]
    (editor/editor-set-on-action! e "app.exit" (fn [] (swap! exited inc)))
    (doseq [c "hi"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))  ;; home
    (core/handle-input e (ctrl 4))  ;; ctrl+d → forward delete
    (t/is (zero? @exited))
    (t/is (= "i" (editor/editor-get-text e)))))

(t/deftest test-editor-action-escape-interrupt
  (let [e (make-action-editor)
        cancelled (atom 0)]
    (editor/editor-set-on-action! e "app.interrupt" (fn [] (swap! cancelled inc)))
    (core/handle-input e K-ESC)
    (t/is (= 1 @cancelled))
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-action-escape-cancels-dropdown-first
  ;; pi: escape with the autocomplete dropdown open cancels the dropdown,
  ;; it does not trigger app.interrupt
  (let [e (make-action-editor)
        cancelled (atom 0)]
    (core/editor-set-autocomplete-provider! e
                                            (ac/make-combined-provider
                                             :commands-fn (constantly ac-commands)
                                             :base-path ac-test-dir))
    (editor/editor-set-on-action! e "app.interrupt" (fn [] (swap! cancelled inc)))
    (core/handle-input e "/")
    (t/is (= :regular @(:autocomplete-state e)))
    (core/handle-input e K-ESC)
    (t/is (zero? @cancelled) "escape closes the dropdown, not the action")
    (t/is (nil? @(:autocomplete-state e)))))

(t/deftest test-editor-action-typing-unaffected
  (let [e (make-action-editor)]
    (editor/editor-set-on-action! e "app.tools.expand" (fn []))
    (editor/editor-set-on-action! e "app.thinking.toggle" (fn []))
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= "hello" (editor/editor-get-text e)))
    (core/handle-input e K-ENTER)  ;; submit still works with actions registered
    (t/is (= "hello" (editor/editor-get-text e)))))

;; ─── Expanded text (pi: Editor.getExpandedText) ───────────────────────────

(t/deftest test-editor-get-expanded-text
  (let [e (editor/make-editor)
        big1 (clojure.string/join "\n" (repeat 15 "one"))
        big2 (clojure.string/join "\n" (repeat 15 "two"))]
    (core/handle-input e (str "\u001b[200~" big1 "\u001b[201~"))
    (core/handle-input e (str "\u001b[200~" big2 "\u001b[201~"))
    (t/is (clojure.string/includes? (editor/editor-get-text e) "[paste #1"))
    (t/is (= (str big1 big2) (editor/editor-get-expanded-text e))
          "all paste markers expanded to their stored content")))

(t/deftest test-editor-get-expanded-text-no-markers
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= "hello" (editor/editor-get-expanded-text e))))

  ;; Literal marker text with no store entry stays as-is
  (let [e (editor/make-editor)]
    (doseq [c "[paste #9 +5 lines]"] (core/handle-input e (str c)))
    (t/is (= "[paste #9 +5 lines]" (editor/editor-get-expanded-text e)))))

;; ─── setText undo snapshot (pi: Editor.setText pushes undo snapshot) ───────

(t/deftest test-editor-set-text-undoable
  ;; programmatic setText is undoable back to the previous content (pi parity)
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (editor/editor-set-text! e "world")
    (t/is (= "world" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 31))  ;; ctrl+- = undo
    (t/is (= "hello" (editor/editor-get-text e)) "undo restores pre-setText content")
    (core/handle-input e (ctrl 31))  ;; undo again → initial empty state
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-set-text-undo-redo
  (let [e (editor/make-editor)]
    (doseq [c "ab"] (core/handle-input e (str c)))
    (editor/editor-set-text! e "xy")
    (core/handle-input e (ctrl 31))  ;; undo → "ab"
    (t/is (= "ab" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 26))  ;; ctrl+z = redo → "xy"
    (t/is (= "xy" (editor/editor-get-text e)))))

(t/deftest test-editor-set-text-same-content-no-undo
  ;; setting identical content must not push a spurious undo snapshot
  (let [e (editor/make-editor)]
    (doseq [c "hi"] (core/handle-input e (str c)))
    (editor/editor-set-text! e "hi")
    (core/handle-input e (ctrl 31))  ;; undo → empty (only the typing snapshot)
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-set-text-empty-undoable
  ;; submit-clear (setText "") is undoable (pi parity)
  (let [e (editor/make-editor)]
    (doseq [c "msg"] (core/handle-input e (str c)))
    (editor/editor-set-text! e "")
    (t/is (= "" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 31))
    (t/is (= "msg" (editor/editor-get-text e)) "cleared text restored by undo")))

;; ─── IEditorComponent protocol ───────────────────────────────────────────────

(t/deftest test-editor-implements-ieditorcomponent
  (let [e (editor/make-editor)]
    (t/is (satisfies? core/IEditorComponent e))
    (t/is (satisfies? core/IComponent e))))

(t/deftest test-ieditorcomponent-text-access
  (let [e (editor/make-editor)]
    (core/editor-set-text! e "line1\nline2")
    (t/is (= "line1\nline2" (core/editor-get-text e)))
    (t/is (= "line1\nline2" (core/editor-get-expanded-text e)))))

(t/deftest test-ieditorcomponent-expanded-text
  ;; paste markers are expanded (pi: getExpandedText) while getText is raw
  (let [e (editor/make-editor)]
    (core/editor-set-text! e "see [paste #1] here")
    (swap! (:paste-store e) assoc 1 "the content")
    (t/is (clojure.string/includes? (core/editor-get-text e) "[paste #1]"))
    (t/is (= "see the content here" (core/editor-get-expanded-text e)))))

(t/deftest test-ieditorcomponent-callbacks
  (let [e (editor/make-editor)
        submitted (atom nil)
        changed (atom nil)]
    (core/editor-set-on-submit! e (fn [t] (reset! submitted t)))
    (core/editor-set-on-change! e (fn [t] (reset! changed t)))
    (doseq [c "hi"] (core/handle-input e (str c)))
    (t/is (= "hi" @changed))
    (core/handle-input e "\r")
    (t/is (= "hi" @submitted))))

(t/deftest test-ieditorcomponent-appearance
  (let [e (editor/make-editor)]
    (core/editor-set-padding-x! e 4)
    (t/is (= 4 @(:padding-x e)))
    (core/editor-set-autocomplete-max-visible! e 9)
    (t/is (= 9 @(:autocomplete-max-visible e)))))

(t/deftest test-ieditorcomponent-history
  (let [e (editor/make-editor)]
    (core/editor-add-to-history! e "one")
    (core/editor-add-to-history! e "two")
    (t/is (= ["one" "two"] (editor/editor-get-history e)))))

(t/deftest test-ieditorcomponent-insert-at-cursor
  (let [e (editor/make-editor)]
    (core/editor-set-text! e "abc")
    ;; cursor is at the end after set-text; move left, then insert
    (core/handle-input e K-LEFT)
    (core/editor-insert-text-at-cursor! e "X")
    (t/is (= "abXc" (editor/editor-get-text e)))
    (t/is (= 3 (:cursor-col @(:state-atom e))))))

(t/deftest test-custom-ieditorcomponent-component
  ;; an extension-style editor implementing the protocol must be drivable
  ;; through protocol dispatch (the swap path uses these methods)
  (let [state-atom (atom {:text "" :cursor 0})
        custom (reify core/IEditorComponent
                 core/IComponent
                 (render [_ _] [])
                 (handle-input [_ _] nil)
                 (invalidate [_] nil)
                 (editor-get-text [_] (:text @state-atom))
                 (editor-set-text! [_ t] (swap! state-atom assoc :text t))
                 (editor-get-expanded-text [_] (:text @state-atom))
                 (editor-add-to-history! [_ _] nil)
                 (editor-insert-text-at-cursor! [_ t]
                   (swap! state-atom assoc :text (str (:text @state-atom) t)))
                 (editor-set-autocomplete-provider! [_ _] nil)
                 (editor-set-autocomplete-max-visible! [_ _] nil)
                 (editor-set-padding-x! [_ _] nil)
                 (editor-set-on-submit! [_ _] nil)
                 (editor-set-on-change! [_ _] nil))]
    (core/editor-set-text! custom "hello")
    (t/is (= "hello" (core/editor-get-text custom)))
    (core/editor-insert-text-at-cursor! custom "!")
    (t/is (= "hello!" (core/editor-get-text custom)))))

(t/deftest test-ieditorcomponent-insert-undoable
  ;; insert-text-at-cursor! pushes an undo snapshot and clears redo
  (let [e (editor/make-editor)]
    (core/editor-set-text! e "abc")
    (core/handle-input e K-LEFT)
    (core/editor-insert-text-at-cursor! e "X")
    (core/handle-input e (str (char 31)))  ;; ctrl+- undo
    (t/is (= "abc" (editor/editor-get-text e)) "insert undone")))

(t/deftest test-core-re-exports-duck-fallback
  ;; the core re-exports fall back to the field-based fns for records that
  ;; do not implement the protocol (Editor-shaped duck-typing)
  (let [e (editor/make-editor)]
    (t/is (satisfies? core/IEditorComponent e))
    (core/editor-set-text! e "x")
    (t/is (= "x" (core/editor-get-text e)))
    (core/editor-add-to-history! e "h")
    (t/is (= ["h"] (editor/editor-get-history e)))
    (core/editor-set-padding-x! e 2)
    (t/is (= 2 @(:padding-x e)))
    (core/editor-insert-text-at-cursor! e "y")
    (t/is (= "xy" (core/editor-get-text e)))))

(t/deftest test-autocomplete-enter-applies-arg-completion
  ;; pi tui.select.confirm with the dropdown open: the selection is applied
  ;; ("/theme light" → "/theme <selected>") and NOT submitted — a second
  ;; Enter submits. Only command-name completions fall through to submit.
  (let [e (make-ac-editor)
        submitted (atom nil)]
    (core/editor-set-on-submit! e #(reset! submitted %))
    (doseq [c "/theme light"] (core/handle-input e (str c)))
    (t/is (some? @(:autocomplete-list e)) "arg-completion dropdown open")
    (core/handle-input e K-ENTER)
    (t/is (nil? @submitted) "argument completion does not submit (pi)")
    (t/is (nil? @(:autocomplete-list e)) "dropdown closed after applying")
    (t/is (str/starts-with? (editor/editor-get-text e) "/theme ")
          "completion applied over the typed args")
    ;; a second Enter submits the completed line
    (core/handle-input e K-ENTER)
    (t/is (= (editor/editor-get-text e) @submitted))))

(t/deftest test-editor-keys-resolve-through-the-manager
  (t/testing "P3: editor-internal keys go through the injected manager — a
              user override replaces the default chord"
    (let [kmgr (app-kb/make-agent-keybindings-manager {"tui.input.submit" "ctrl+y"})
          e (editor/make-editor :keybindings kmgr)
          submitted (atom nil)]
      (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
      (doseq [c "hi"] (core/handle-input e (str c)))
      (core/handle-input e K-ENTER)
      (t/is (nil? @submitted) "enter was rebound away from submit")
      (core/handle-input e (ctrl 25))
      (t/is (= "hi" @submitted) "ctrl+y submits"))))

(t/deftest test-editor-history-binding-beats-app-action
  (t/testing "pi custom-editor: explicit history bindings take precedence over
              app actions — a user can bind ctrl+p even though it cycles models
              by default"
    (let [kmgr (app-kb/make-agent-keybindings-manager
                {"tui.editor.historyPrevious" "ctrl+p"})
          e (editor/make-editor :keybindings kmgr)
          cycled (atom 0)]
      (editor/editor-set-on-action! e "app.model.cycleForward"
                                    (fn [] (swap! cycled inc)))
      (editor/editor-push-history! e "old prompt")
      (doseq [c "draft"] (core/handle-input e (str c)))
      (core/handle-input e (ctrl 16))
      (t/is (zero? @cycled) "the history binding consumed the key")
      (t/is (= "old prompt" (editor/editor-get-text e)) "history navigated"))
    (t/testing "unbound history ids leave the chord to app actions"
      (let [kmgr (app-kb/make-agent-keybindings-manager)
            e (editor/make-editor :keybindings kmgr)
            cycled (atom 0)]
        (editor/editor-set-on-action! e "app.model.cycleForward"
                                      (fn [] (swap! cycled inc)))
        (core/handle-input e (ctrl 16))
        (t/is (= 1 @cycled))))))

(t/deftest test-editor-interrupt-and-exit-beat-history-bindings
  (t/testing "pi custom-editor order: app.interrupt/app.exit are checked
              before the history bindings, so binding history to escape
              cannot swallow cancel"
    (let [kmgr (app-kb/make-agent-keybindings-manager
                {"tui.editor.historyPrevious" "escape"
                 "tui.editor.historyNext" "ctrl+d"})
          e (editor/make-editor :keybindings kmgr)
          interrupted (atom 0)
          exited (atom 0)]
      (editor/editor-set-on-action! e "app.interrupt" (fn [] (swap! interrupted inc)))
      (editor/editor-set-on-action! e "app.exit" (fn [] (swap! exited inc)))
      (editor/editor-push-history! e "old prompt")
      (core/handle-input e K-ESC)
      (t/is (= 1 @interrupted) "escape interrupted instead of browsing history")
      (t/is (= "" (editor/editor-get-text e)) "history did not load")
      (core/handle-input e (ctrl 4))
      (t/is (= 1 @exited) "ctrl+d exited the empty editor instead of history"))))

(t/deftest test-editor-extension-ids-and-cancel-are-managed
  (t/testing "kmet extension ids: redo (ctrl+z) and kill-line (ctrl+w)"
    (let [e (editor/make-editor)]
      (doseq [c "ab"] (core/handle-input e (str c)))
      (core/handle-input e (ctrl 23))
      (t/is (= "" (editor/editor-get-text e)) "ctrl+w kills the line")
      (core/handle-input e (ctrl 31))
      (t/is (= "ab" (editor/editor-get-text e)) "ctrl+- undoes the kill")
      (core/handle-input e (ctrl 26))
      (t/is (= "" (editor/editor-get-text e)) "ctrl+z redoes it")))
  (t/testing "rebinding killLine away hands ctrl+w back to pi's deleteWordBackward"
    (let [kmgr (app-kb/make-agent-keybindings-manager {"tui.editor.killLine" "ctrl+x"})
          e (editor/make-editor :keybindings kmgr)]
      (editor/editor-set-text! e "one two")
      (core/handle-input e (ctrl 24))
      (t/is (= "" (editor/editor-get-text e)) "ctrl+x kills the line")
      (editor/editor-set-text! e "one two")
      (core/handle-input e (ctrl 23))
      (t/is (= "one " (editor/editor-get-text e)) "ctrl+w deletes a word (pi)")))
  (t/testing "escape cancels a standalone editor through tui.select.cancel"
    (let [submitted (atom ::none)
          e (editor/make-editor)]
      (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
      (core/handle-input e K-ESC)
      (t/is (nil? @submitted) "escape reached on-submit nil")))
  (t/testing "ctrl+c stays with the parent (pi input.copy), never cancels"
    (let [submitted (atom ::none)
          e (editor/make-editor)]
      (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
      (core/handle-input e (ctrl 3))
      (t/is (= ::none @submitted) "standalone ctrl+c is a no-op")
      (t/is (= "" (editor/editor-get-text e))))))

(t/deftest test-editor-kitty-ctrl-h-and-ctrl-i-follow-their-ids
  (t/testing "ctrl+h is deleteCharBackward's second chord, ctrl+i input.tab's
              (kitty CSI-u encodings) — and a rebind moves both"
    (let [e (editor/make-editor)]
      (doseq [c "ab"] (core/handle-input e (str c)))
      (core/handle-input e "\u001b[104;5u")   ;; kitty ctrl+h
      (t/is (= "a" (editor/editor-get-text e)) "ctrl+h deletes backward")
      (core/handle-input e "\u001b[105;5u")   ;; kitty ctrl+i
      (t/is (= "a    " (editor/editor-get-text e)) "ctrl+i tabs (no provider)"))
    (let [kmgr (app-kb/make-agent-keybindings-manager
                {"tui.editor.deleteCharBackward" "ctrl+x"
                 "tui.input.tab" "ctrl+t"})
          e (editor/make-editor :keybindings kmgr)]
      (doseq [c "ab"] (core/handle-input e (str c)))
      (core/handle-input e "\u001b[104;5u")
      (t/is (= "ab" (editor/editor-get-text e)) "ctrl+h was rebound away")
      (core/handle-input e (ctrl 24))         ;; ctrl+x
      (t/is (= "a" (editor/editor-get-text e)) "the new chord deletes")
      (core/handle-input e "\u001b[105;5u")
      (t/is (= "a" (editor/editor-get-text e)) "ctrl+i was rebound away")
      (core/handle-input e (ctrl 20))         ;; ctrl+t
      (t/is (= "a    " (editor/editor-get-text e)) "the new chord tabs"))))

(t/deftest test-editor-kitty-ctrl-and-alt-enter-are-newline-chords
  (t/testing "ctrl+enter and alt+enter ride tui.input.newLine"
    (let [e (editor/make-editor)]
      (doseq [c "ab"] (core/handle-input e (str c)))
      (core/handle-input e "\u001b[13;5u")    ;; kitty ctrl+enter
      (t/is (= "ab\n" (editor/editor-get-text e)) "ctrl+enter inserts a newline")
      (core/handle-input e "\u001b[13;3u")    ;; kitty alt+enter
      (t/is (= "ab\n\n" (editor/editor-get-text e)) "alt+enter inserts a newline"))
    (let [kmgr (app-kb/make-agent-keybindings-manager {"tui.input.newLine" "ctrl+n"})
          e (editor/make-editor :keybindings kmgr)]
      (doseq [c "ab"] (core/handle-input e (str c)))
      (core/handle-input e "\u001b[13;5u")
      (t/is (= "ab" (editor/editor-get-text e)) "ctrl+enter was rebound away")
      (core/handle-input e (ctrl 14))         ;; ctrl+n
      (t/is (= "ab\n" (editor/editor-get-text e)) "the new chord inserts a newline"))))

(t/deftest test-editor-kitty-printable-csi-u-inserts-text
  (t/testing "flag-1 terminals send CSI-u for printable keys (pi #3780)"
    (let [e (editor/make-editor)]
      (core/handle-input e "\u001b[97u")     ;; kitty 'a'
      (t/is (= "a" (editor/editor-get-text e)))
      (core/handle-input e "\u001b[224u")    ;; Italian-layout 'à'
      (t/is (= "aà" (editor/editor-get-text e)))
      (core/handle-input e "\u001b[64;2u")   ;; shift+'2' → '@'
      (t/is (= "aà@" (editor/editor-get-text e)))
      (core/handle-input e "\u001b[27;2;64~") ;; modifyOtherKeys '@'
      (t/is (= "aà@@" (editor/editor-get-text e))))
    (t/testing "control chords are still chords, not text"
      (let [e (editor/make-editor)]
        (core/handle-input e "ab")
        (core/handle-input e "\u001b[97;5u")  ;; kitty ctrl+a
        (t/is (= "ab" (editor/editor-get-text e)) "ctrl+a is a chord")))))







(ns kmet.tui.components.input
  "Single-line text input with horizontal scrolling and cursor.
   Port of @earendil-works/pi-tui Input."
  (:require [clojure.string :as str]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]
            [kmet.tui.components.editing :as edit]))

;; ─── Key dispatch ─────────────────────────────────────────────────────────

(defn- match?
  "Resolve DATA against keybinding ID through the global manager (pi:
   getKeybindings().matches) so user overrides apply to the input's keys."
  [data keybinding-id]
  (kb/global-match? data keybinding-id))

;; ─── Grapheme helpers and kill ring ──────────────────────────────────────
;; Imported from kmet.tui.components.editing

;; ─── Undo stack ─────────────────────────────────────────────────────────────

(defrecord UndoStack [stack])

(defn make-undo-stack []
  (map->UndoStack {:stack (atom [])}))

(defn- undo-push [us value]
  (swap! (:stack us) conj value))

(defn- undo-pop [us]
  (let [s @(:stack us)]
    (when (seq s)
      (let [snapshot (peek s)]
        (swap! (:stack us) pop)
        snapshot))))

(defn- notify-input-change!
  "Fire the :on-change callback with the input's current value (parity
   with the editor's per-edit notification). Called by every helper that
   changes the text; cursor-only moves stay silent. A no-op with no
   callback installed."
  [input]
  (when-let [cb @(:on-change input)] (cb @(:value-atom input))))

;; ─── Word navigation helpers ────────────────────────────────────────────────
;; Imported from kmet.tui.components.editing

;; ─── Input action helpers ───────────────────────────────────────────────────
;; Defined before defrecord so method bodies can reference them.

(defn- insert-character [input char]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (or (re-find #"^\s" char)
              (not= @(:last-action input) :type-word))
      (undo-push (:undo-stack input) {:value value :cursor cursor}))
    (reset! (:last-action input) :type-word)
    (let [new-val (str (subs value 0 cursor) char (subs value cursor))
          new-cursor (+ cursor (count char))]
      (reset! (:value-atom input) new-val)
      (reset! (:cursor-atom input) new-cursor)
      (notify-input-change! input))))

(defn- handle-backspace [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (reset! (:last-action input) nil)
    (when (> cursor 0)
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [glen (edit/grapheme-left value cursor)]
        (reset! (:value-atom input)
                (str (subs value 0 glen) (subs value cursor)))
        (reset! (:cursor-atom input) glen)
        (notify-input-change! input)))))

(defn- handle-forward-delete [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (reset! (:last-action input) nil)
    (when (< cursor (count value))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [nxt (edit/grapheme-right value cursor)]
        (reset! (:value-atom input)
                (str (subs value 0 cursor) (subs value nxt)))
        (notify-input-change! input)))))

(defn- delete-to-line-start [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (pos? cursor)
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [deleted (subs value 0 cursor)]
        (edit/kill-ring-push (:kill-ring input) deleted :prepend true
                             :accumulate (= @(:last-action input) :kill))
        (reset! (:last-action input) :kill)
        (reset! (:value-atom input) (subs value cursor))
        (reset! (:cursor-atom input) 0)
        (notify-input-change! input)))))

(defn- delete-to-line-end [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (< cursor (count value))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [deleted (subs value cursor)]
        (edit/kill-ring-push (:kill-ring input) deleted :prepend false
                             :accumulate (= @(:last-action input) :kill))
        (reset! (:last-action input) :kill)
        (reset! (:value-atom input) (subs value 0 cursor))
        (notify-input-change! input)))))

(defn- delete-word-backwards [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (pos? cursor)
      (let [was-kill (= @(:last-action input) :kill)]
        (undo-push (:undo-stack input) {:value value :cursor cursor})
        (let [old-cursor cursor
              new-cursor (edit/word-boundary-left value cursor)
              deleted (subs value new-cursor old-cursor)]
          (edit/kill-ring-push (:kill-ring input) deleted :prepend true
                               :accumulate was-kill)
          (reset! (:last-action input) :kill)
          (reset! (:value-atom input)
                  (str (subs value 0 new-cursor) (subs value old-cursor)))
          (reset! (:cursor-atom input) new-cursor)
          (notify-input-change! input))))))

(defn- delete-word-forward [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (< cursor (count value))
      (let [was-kill (= @(:last-action input) :kill)]
        (undo-push (:undo-stack input) {:value value :cursor cursor})
        (let [old-cursor cursor
              new-cursor (edit/word-boundary-right value cursor)
              deleted (subs value old-cursor new-cursor)]
          (edit/kill-ring-push (:kill-ring input) deleted :prepend false
                               :accumulate was-kill)
          (reset! (:last-action input) :kill)
          (reset! (:value-atom input)
                  (str (subs value 0 old-cursor) (subs value new-cursor)))
          (notify-input-change! input))))))

(defn- yank-action [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)
        text (edit/kill-ring-peek (:kill-ring input))]
    (when text
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [new-val (str (subs value 0 cursor) text (subs value cursor))]
        (reset! (:value-atom input) new-val)
        (reset! (:cursor-atom input) (+ cursor (count text)))
        (reset! (:last-action input) :yank)
        (notify-input-change! input)))))

(defn- yank-pop-action [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)
        kr (:kill-ring input)]
    (when (and (= @(:last-action input) :yank)
               (> (edit/kill-ring-length kr) 1))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [prev-text (or (edit/kill-ring-peek kr) "")
            after-remove (subs value cursor)
            new-cursor (- cursor (count prev-text))
            stripped (str (subs value 0 new-cursor) after-remove)]
        (edit/kill-ring-rotate kr)
        (let [text (or (edit/kill-ring-peek kr) "")
              new-val (str stripped text after-remove)]
          (reset! (:value-atom input) new-val)
          (reset! (:cursor-atom input) (+ new-cursor (count text)))
          (reset! (:last-action input) :yank)
          (notify-input-change! input))))))

;; ─── Render helper ──────────────────────────────────────────────────────────

(defn- render-line
  "Render a single line of input with cursor marker at cursor position.
   Returns the line string WITHOUT the prompt."
  [value cursor focused? _scrolled?]
  (let [at-end? (>= cursor (count value))
        at-char (if at-end? " " (edit/grapheme-at value cursor))
        char-len (count at-char)
        before (subs value 0 cursor)
        after (subs value (min (count value) (+ cursor char-len)))]
    (str before
         (when focused? u/CURSOR-MARKER)
         "\u001b[7m" at-char "\u001b[27m"
         after)))

;; ─── Input component ────────────────────────────────────────────────────────

(defcomponent Input nil [value-atom cursor-atom on-submit on-escape on-change focused?
                         paste-buffer paste-state kill-ring last-action undo-stack]

  (render [_this width]
    (let [prompt "> "
          prompt-len (count prompt)
          available (- width prompt-len)]
      (if (<= available 0)
        [prompt]
        (let [value @value-atom
              cursor @cursor-atom
              total-width (u/visible-width value)]
          (if (< total-width available)
            ;; Everything fits
            (let [line (render-line value cursor @focused? false)
                  padding (apply str (repeat (max 0 (- available (u/visible-width line))) \space))]
              [(str prompt line padding)])
            ;; Need horizontal scrolling
            (let [scroll-width (if (== cursor (count value))
                                 (max 1 (dec available))
                                 available)
                  cursor-col (u/visible-width (subs value 0 cursor))
                  total (u/visible-width value)]
              (if (<= scroll-width 0)
                [(str prompt (apply str (repeat available \space)))]
                (let [half (quot scroll-width 2)
                      start-col (cond
                                  (< cursor-col half) 0
                                  (> cursor-col (- total half))
                                  (max 0 (- total scroll-width))
                                  :else (max 0 (- cursor-col half)))
                      visible-text (u/slice-by-column value start-col scroll-width true)
                      before-txt (subs (u/slice-by-column value start-col (max 0 (- cursor-col start-col)) true) 0)
                      cursor-display (u/visible-width before-txt)
                      line (render-line visible-text cursor-display @focused? true)
                      padding (apply str (repeat (max 0 (- available (u/visible-width line))) \space))]
                  [(str prompt line padding)]))))))))

  (handle-input [this data]
    (let [value @value-atom
          cursor @cursor-atom]
      (cond
        ;; Paste start marker. A nested START while already buffering is
        ;; literal paste content (pi treats everything between the first
        ;; START and first END as data): fall through to the buffering leg
        ;; instead of resetting. pi strips only the first START
        ;; (String.replace with a string pattern).
        (and (clojure.string/includes? data "\u001b[200~")
             (not= @paste-state :buffering))
        (do (reset! paste-state :buffering)
            (reset! paste-buffer "")
            (let [remaining (clojure.string/replace-first data "\u001b[200~" "")]
              (when (seq remaining)
                (protocols/handle-input this remaining)))
            nil)

        ;; Inside paste buffer. Trailing input after END (pi: remaining +
        ;; recursive handleInput) re-enters dispatch instead of dropping.
        (= @paste-state :buffering)
        (do (swap! paste-buffer str data)
            (let [buf @paste-buffer
                  end-idx (clojure.string/index-of buf "\u001b[201~")]
              (when (and end-idx (>= end-idx 0))
                (let [paste-text (subs buf 0 end-idx)
                      remaining (subs buf (+ end-idx (count "\u001b[201~")))
                      ;; pi: handlePaste removes newlines (single-line input),
                      ;; tabs become 4 spaces
                      clean (clojure.string/replace paste-text #"\r\n|\r|\n" "")
                      clean (clojure.string/replace clean "\t" "    ")]
                  (undo-push undo-stack {:value value :cursor cursor})
                  (reset! last-action nil)
                  (reset! value-atom (str (subs value 0 cursor) clean (subs value cursor)))
                  (reset! cursor-atom (+ cursor (count clean)))
                  (notify-input-change! this)
                  ;; Only leave buffering once the end marker arrives
                  (reset! paste-state :idle)
                  (reset! paste-buffer "")
                  (when (seq remaining)
                    (protocols/handle-input this remaining)))))
            nil)

        ;; Escape / Cancel
        (match? data "tui.select.cancel")
        (do (when-let [cb @on-escape] (cb)) nil)

        ;; Undo
        (match? data "tui.editor.undo")
        (do (when-let [snapshot (undo-pop undo-stack)]
              (reset! value-atom (:value snapshot))
              (reset! cursor-atom (:cursor snapshot))
              (reset! last-action nil)
              (notify-input-change! this))
            nil)

        ;; Submit
        (match? data "tui.input.submit")
        (do (when-let [cb @on-submit] (cb @value-atom)) nil)

        ;; Backspace
        (match? data "tui.editor.deleteCharBackward")
        (do (handle-backspace this) nil)

        ;; Forward delete
        (match? data "tui.editor.deleteCharForward")
        (do (handle-forward-delete this) nil)

        ;; Delete word backward
        (match? data "tui.editor.deleteWordBackward")
        (do (delete-word-backwards this) nil)

        ;; Delete word forward
        (match? data "tui.editor.deleteWordForward")
        (do (delete-word-forward this) nil)

        ;; Delete to line start
        (match? data "tui.editor.deleteToLineStart")
        (do (delete-to-line-start this) nil)

        ;; Delete to line end
        (match? data "tui.editor.deleteToLineEnd")
        (do (delete-to-line-end this) nil)

        ;; Yank
        (match? data "tui.editor.yank")
        (do (yank-action this) nil)

        ;; Yank pop
        (match? data "tui.editor.yankPop")
        (do (yank-pop-action this) nil)

        ;; Cursor left
        (match? data "tui.editor.cursorLeft")
        (do (reset! last-action nil)
            (reset! cursor-atom (edit/grapheme-left value cursor))
            nil)

        ;; Cursor right
        (match? data "tui.editor.cursorRight")
        (do (reset! last-action nil)
            (reset! cursor-atom (edit/grapheme-right value cursor))
            nil)

        ;; Cursor line start
        (match? data "tui.editor.cursorLineStart")
        (do (reset! last-action nil)
            (reset! cursor-atom 0)
            nil)

        ;; Cursor line end
        (match? data "tui.editor.cursorLineEnd")
        (do (reset! last-action nil)
            (reset! cursor-atom (count @value-atom))
            nil)

        ;; Cursor word left
        (match? data "tui.editor.cursorWordLeft")
        (do (reset! last-action nil)
            (reset! cursor-atom (edit/word-boundary-left @value-atom @cursor-atom))
            nil)

        ;; Cursor word right
        (match? data "tui.editor.cursorWordRight")
        (do (reset! last-action nil)
            (reset! cursor-atom (edit/word-boundary-right @value-atom @cursor-atom))
            nil)

        ;; Kitty CSI-u printable character (e.g. \u001b[97u for 'a'):
        ;; flag-1 terminals send CSI-u for printable keys too, and the
        ;; sequence contains ESC — decode before the control check or the
        ;; character is discarded (pi: Input.handleInput).
        :else
        (if-let [printable (keys/decode-kitty-printable data)]
          (insert-character this printable)
          (let [has-ctrl? (some #(let [c (int %)]
                                   (or (< c 32) (== c 127)
                                       (and (>= c 128) (<= c 159))))
                                data)]
            (when-not has-ctrl?
              (insert-character this data))))))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-input
  "Create a new Input component."
  []
  (map->Input {:value-atom (atom "")
               :cursor-atom (atom 0)
               :on-submit (atom nil)
               :on-escape (atom nil)
               :on-change (atom nil)
               :focused? (atom false)
               :paste-buffer (atom "")
               :paste-state (atom :idle)
               :kill-ring (edit/make-kill-ring)
               :last-action (atom nil)
               :undo-stack (make-undo-stack)}))

(defn input-set-value! [input value]
  (reset! (:value-atom input) value)
  (reset! (:cursor-atom input) (min (count value) @(:cursor-atom input))))

(defn input-set-cursor! [input pos]
  (reset! (:cursor-atom input) (max 0 (min (count @(:value-atom input)) pos))))

(defn input-get-value [input]
  @(:value-atom input))

(defn input-set-on-submit! [input f]
  (reset! (:on-submit input) f))

(defn input-set-on-escape! [input f]
  (reset! (:on-escape input) f))

(defn input-set-on-change!
  "Install F as the change callback (parity with the editor's setter). F
   receives the current value after every edit that changes the text;
   programmatic value/cursor setters never fire it."
  [input f]
  (reset! (:on-change input) f))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type Input
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

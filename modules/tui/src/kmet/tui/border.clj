(ns kmet.tui.border
  "Box-drawing glyph sets as data (tui.md §2.8): one place that says what
   a border looks like, so components drawing frames, rules and tables
   cannot drift apart.

   A border is a map of eight frame parts and five junctions:

     :top :bottom :left :right
     :top-left :top-right :bottom-left :bottom-right
     :tee-down (┬) :tee-up (┴) :tee-left (┤) :tee-right (├) :cross (┼)

   The junctions connect a frame's edges across a row or column — a table's
   `┬`/`┼`/`┴` separator rows — and are named for the direction the branch
   leaves: `:tee-right` is `├` (vertical bar, branch to the right).

   Sets are chosen by keyword ({:border :rounded}); a map merges over
   :normal so a partial border ({:top \"-\" :bottom \"-\"}) keeps its sides.
   Two sets are about footprint rather than looks: :hidden draws spaces —
   the frame still costs its cells, so a column of frames stays aligned —
   and :none resolves to nil, meaning no border at all (the caller skips
   the glyphs entirely). Unknown keywords throw with a did-you-mean, the
   same loud contract the hiccup tag table uses."
  (:refer-clojure :exclude [resolve])
  (:require [kmet.tui.fuzzy :as fuzzy]))

(defn border
  "A border from its thirteen parts. Anything omitted is a space, which is
   what makes a partial border (say, only a top edge) come out aligned."
  [{:keys [top bottom left right top-left top-right bottom-left bottom-right
           tee-down tee-up tee-left tee-right cross]}]
  {:top (or top " ") :bottom (or bottom " ")
   :left (or left " ") :right (or right " ")
   :top-left (or top-left " ") :top-right (or top-right " ")
   :bottom-left (or bottom-left " ") :bottom-right (or bottom-right " ")
   :tee-down (or tee-down " ") :tee-up (or tee-up " ")
   :tee-left (or tee-left " ") :tee-right (or tee-right " ")
   :cross (or cross " ")})

(def normal
  (border {:top "─" :bottom "─" :left "│" :right "│"
           :top-left "┌" :top-right "┐" :bottom-left "└" :bottom-right "┘"
           :tee-down "┬" :tee-up "┴" :tee-left "┤" :tee-right "├" :cross "┼"}))

(def rounded
  (assoc normal
         :top-left "╭" :top-right "╮" :bottom-left "╰" :bottom-right "╯"))

(def thick
  (border {:top "━" :bottom "━" :left "┃" :right "┃"
           :top-left "┏" :top-right "┓" :bottom-left "┗" :bottom-right "┛"
           :tee-down "┳" :tee-up "┻" :tee-left "┫" :tee-right "┣" :cross "╋"}))

(def double-line
  (border {:top "═" :bottom "═" :left "║" :right "║"
           :top-left "╔" :top-right "╗" :bottom-left "╚" :bottom-right "╝"
           :tee-down "╦" :tee-up "╩" :tee-left "╣" :tee-right "╠" :cross "╬"}))

(def block
  (border {:top "█" :bottom "█" :left "█" :right "█"
           :top-left "█" :top-right "█" :bottom-left "█" :bottom-right "█"
           :tee-down "█" :tee-up "█" :tee-left "█" :tee-right "█" :cross "█"}))

(def ascii
  "For a terminal that cannot draw the rest: a serial console, a console
   host in a codepage that predates Unicode, or TERM=vt100."
  (border {:top "-" :bottom "-" :left "|" :right "|"
           :top-left "+" :top-right "+" :bottom-left "+" :bottom-right "+"
           :tee-down "+" :tee-up "+" :tee-left "+" :tee-right "+" :cross "+"}))

(def hidden
  "Spaces: the frame still costs its cells, so a hidden border and a visible
   one lay out identically. Use it to keep a row of frames aligned when one
   of them has nothing to say."
  (border {}))

(def styles
  "Border sets by name, for a :border prop."
  {:normal normal :rounded rounded :thick thick :double double-line
   :block block :ascii ascii :hidden hidden})

(defn- nearest-style
  "Best fuzzy match for KEY among the known style names, for did-you-mean
   (lower score is a better match)."
  [k]
  (let [name (subs (str k) 1)]
    (->> (keys styles)
         (keep (fn [s]
                 (let [{:keys [matches score]} (fuzzy/fuzzy-match name (subs (str s) 1))]
                   (when matches [score s]))))
         (sort-by first)
         first
         second)))

(defn resolve
  "The border map for B, which may already be one. nil means :normal,
   :none means no border at all (returns nil, so the caller can skip the
   glyphs), a keyword names a set, a map merges over :normal so a partial
   border keeps its unmentioned parts. An unknown keyword throws with a
   did-you-mean suggestion rather than silently drawing the wrong frame."
  [b]
  (cond
    (nil? b) normal
    (= :none b) nil
    (map? b) (merge normal b)
    (keyword? b) (or (get styles b)
                     (let [hint (when-some [near (nearest-style b)]
                                  (str " Did you mean :" (name near) "?"))]
                       (throw (ex-info
                               (str "kmet.tui.border: unknown border style " b
                                    ". Known styles: " (pr-str (sort (keys styles)))
                                    "." hint)
                               {:border b :known-styles (sort (keys styles))}))))
    :else (throw (ex-info (str "kmet.tui.border: :border must be a keyword, map "
                               "or :none, got " (pr-str b))
                          {:border b}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Composition — assembled lines over a resolved border
;; ═══════════════════════════════════════════════════════════════════════════

(defn rule
  "The horizontal glyph of B — what a full-width rule (dynamic-border, an
   editor's top edge) draws."
  [b]
  (:top b))

(defn rule-line
  "N copies of B's horizontal glyph. Zero (or negative) N is empty."
  [b n]
  (apply str (repeat (max 0 n) (:top b))))

(defn top-line
  "The top edge of a frame N cells wide, corners included."
  [b n]
  (when (pos? n)
    (if (= 1 n)
      (:top b)
      (str (:top-left b) (rule-line b (- n 2)) (:top-right b)))))

(defn bottom-line
  "The bottom edge of a frame N cells wide, corners included."
  [b n]
  (when (pos? n)
    (if (= 1 n)
      (:bottom b)
      (str (:bottom-left b) (rule-line b (- n 2)) (:bottom-right b)))))

(defn mid-line
  "A frame's middle row: INNER (already padded to the content width) between
   B's side glyphs. EDGE-FN, when given, styles the two side glyphs — a
   frame's edges are usually a different colour than what they enclose, and
   styling the assembled line instead would tint the content too."
  ([b inner] (str (:left b) inner (:right b)))
  ([b inner edge-fn]
   (str (edge-fn (:left b)) inner (edge-fn (:right b)))))

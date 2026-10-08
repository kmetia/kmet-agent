(ns kmet.tui.fuzzy
  "Fuzzy matching utilities. Port of @earendil-works/pi-tui fuzzy.ts.
   Matches if all query characters appear in order (not necessarily
   consecutive). Lower score = better match."
  (:require [clojure.string :as str]))

(defn- word-boundary?
  "True when the character at index i of text is at a word boundary
   (start of string or preceded by a separator char)."
  [text i]
  (or (zero? i)
      (boolean (re-find #"[\s\-_./:]" (subs text (dec i) i)))))

(defn- match-query
  "Score a normalized (lowercase) query against normalized text.
   Returns {:matches bool :score n}."
  [query text]
  (cond
    (empty? query)
    {:matches true :score 0}

    (> (count query) (count text))
    {:matches false :score 0}

    :else
    (loop [qi 0
           i 0
           score 0
           last-match -1
           consecutive 0]
      (if (or (>= i (count text)) (>= qi (count query)))
        (if (>= qi (count query))
          {:matches true :score (if (= query text) (- score 100) score)}
          {:matches false :score 0})
        (if (= (nth text i) (nth query qi))
          (let [consecutive-match? (= last-match (dec i))
                consecutive' (if consecutive-match? (inc consecutive) 0)
                score' (cond-> score
                         consecutive-match? (- (* consecutive' 5))
                         (and (not consecutive-match?) (>= last-match 0))
                         (+ (* (- i last-match 1) 2))
                         (word-boundary? text i) (- 10)
                         :always (+ (* i 0.1)))]
            (recur (inc qi) (inc i) score' i consecutive'))
          (recur qi (inc i) score last-match consecutive))))))

(defn subsequence-match?
  "True when every character of PATTERN appears in TEXT in order (not
   necessarily consecutively) — the plain filter the list widgets share.
   Case-sensitive: callers lowercase the strings they compare."
  [pattern text]
  (let [pl (count pattern) tl (count text)]
    (if (zero? pl)
      true
      (loop [pi 0 ti 0]
        (if (>= pi pl)
          true
          (if (>= ti tl)
            false
            (if (= (nth pattern pi) (nth text ti))
              (recur (inc pi) (inc ti))
              (recur pi (inc ti)))))))))

(defn fuzzy-match
  "Fuzzy match pattern against text (case-insensitive).
   Returns {:matches bool :score n} — lower score is a better match.
   Falls back to an alphanumeric-swapped query (e.g. \"ab12\" vs \"12ab\")
   with a small penalty."
  [query text]
  (let [query-lower (clojure.string/lower-case query)
        text-lower (clojure.string/lower-case text)
        primary (match-query query-lower text-lower)]
    (if (:matches primary)
      primary
      (if-let [m (or (re-matches #"^([a-z]+)([0-9]+)$" query-lower)
                     (re-matches #"^([0-9]+)([a-z]+)$" query-lower))]
        (let [swapped (str (nth m 2) (nth m 1))
              swapped-match (match-query swapped text-lower)]
          (if (:matches swapped-match)
            {:matches true :score (+ (:score swapped-match) 5)}
            primary))
        primary))))

(defn fuzzy-filter
  "Filter and sort items by fuzzy match quality (best matches first).
   Supports whitespace- and slash-separated tokens: all tokens must match
   for an item to be included. get-text extracts the searchable string
   from an item."
  [items query get-text]
  (if (clojure.string/blank? query)
    (vec items)
    (let [tokens (->> (clojure.string/split query #"[\s/]+")
                      (remove clojure.string/blank?))
          results
          (reduce
           (fn [acc item]
             (let [text (get-text item)
                   [matched? total]
                   (reduce (fn [[ok s] tok]
                             (if-not ok
                               [false s]
                               (let [m (fuzzy-match tok text)]
                                 (if (:matches m)
                                   [true (+ s (:score m))]
                                   [false s]))))
                           [true 0] tokens)]
               (if matched?
                 (conj acc {:item item :score total})
                 acc)))
           [] items)]
      (mapv :item (sort-by :score results)))))

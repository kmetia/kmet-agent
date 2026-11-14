(ns kmet.libs.text
  "Small text helpers shared by the app, the TUI and extensions."
  (:require [clojure.string :as str]))

(defn first-nonblank-line
  "The first non-blank line of S, or nil when S has none. Multi-line script
   arguments may open with a newline; a caller that wants the first real line
   must not settle for the empty one."
  [s]
  (when (string? s)
    (first (remove str/blank? (str/split-lines s)))))

(defn title-line
  "The first non-blank line of S shortened to MAX characters (80 by default)
   with a trailing ellipsis, or nil when S has no non-blank line. The shared
   quiet-title body for tools whose argument is a multi-line script."
  ([s] (title-line s 80))
  ([s max-chars]
   (when-let [line (first-nonblank-line s)]
     (if (> (count line) max-chars)
       (str (subs line 0 max-chars) "…")
       line))))

(defn splits-surrogate-pair?
  "True when cutting S before index CUT would split a surrogate pair: the
   character before CUT is a high surrogate. Only bb/JVM strings are UTF-16;
   a Jolt string holds code points, so a cut between them never splits a
   pair."
  [s cut]
  (and (pos? cut)
       (<= 0xD800 (int (nth s (dec cut))) 0xDBFF)))

(defn trim-leading-blank-lines
  "S without its leading blank lines (lines that are empty or hold only
   spaces, tabs or carriage returns). The first non-blank line keeps its own
   indentation; a non-string S passes through."
  [s]
  (if (string? s)
    (str/replace s #"\A(?:[ \t\r]*\n)+" "")
    s))

(defn edit-distance
  "How many single-character edits turn A into B. An insertion, deletion,
   substitution, or a swap of two neighbours each count one, so `porfile` is
   one edit from `profile`."
  [a b]
  (let [a (vec a)
        b (vec b)
        n (count a)
        m (count b)]
    (loop [i 1
           prev-prev nil
           prev (vec (range (inc m)))]
      (if (> i n)
        (nth prev m)
        (let [row (loop [j 1
                         cur [(inc i)]]
                    (if (> j m)
                      cur
                      (let [cost (if (= (nth a (dec i)) (nth b (dec j))) 0 1)
                            value (min (inc (nth prev j))
                                       (inc (nth cur (dec j)))
                                       (+ (nth prev (dec j)) cost))
                            value (if (and (> i 1) (> j 1)
                                           (= (nth a (dec i)) (nth b (- j 2)))
                                           (= (nth a (- i 2)) (nth b (dec j))))
                                    (min value (inc (nth prev-prev (- j 2))))
                                    value)]
                        (recur (inc j) (conj cur value)))))]
          (recur (inc i) prev row))))))

(defn nearest-words
  "The CANDIDATES a mistyped WORD most likely meant, in candidate order, at
   most LIMIT. A candidate qualifies when its edit distance is within
   MAX-DISTANCE (default one third of WORD's length, at least one); ties keep
   candidate order. With no close candidate, the single longer CANDIDATE that
   WORD prefixes is returned. Returns [] when nothing is close enough.

   Case-sensitive: callers that want a case-insensitive surface lowercase
   their input and candidates before calling."
  ([word candidates]
   (nearest-words word candidates nil))
  ([word candidates {:keys [limit max-distance] :or {limit 3}}]
   (if (and (string? word) (pos? (count word)))
     (let [candidates (vec (distinct (filter string? candidates)))
           allowed (if (some? max-distance)
                     max-distance
                     (max 1 (quot (count word) 3)))
           scored (->> candidates
                       (keep (fn [candidate]
                               (let [distance (edit-distance word candidate)]
                                 (when (<= distance allowed)
                                   [distance candidate]))))
                       vec)]
       (if (seq scored)
         (let [best (apply min (map first scored))]
           (->> scored
                (filter (fn [[distance _]] (= best distance)))
                (take limit)
                (mapv second)))
         (let [begun (filterv (fn [candidate]
                                (and (>= (count word) 2)
                                     (> (count candidate) (count word))
                                     (str/starts-with? candidate word)))
                              candidates)]
           (if (= 1 (count begun)) begun []))))
     [])))

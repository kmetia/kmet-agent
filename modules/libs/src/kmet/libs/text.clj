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

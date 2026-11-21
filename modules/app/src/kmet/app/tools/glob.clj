(ns kmet.app.tools.glob
  "Shell-style glob matching for `/`-separated paths.

   `*` matches within one path segment, `**` crosses segments, `**/` also
   matches zero directories, `?` matches one non-slash character, `[abc]` and
   `[!abc]` are character classes (`[!abc]` negates; `[^abc]` includes `^`
   literally), and `{a,b}` expands to alternatives, including nested groups;
   braces inside `[...]` are literal. Other regex metacharacters are literal.
   Callers normalize host separators before matching. A malformed character
   class throws ex-info with :type ::invalid-pattern."
  (:require [clojure.string :as str]))

(defn- brace-open
  "Index of the first `{` outside a character class, or nil."
  [pattern]
  (loop [i 0 in-class? false]
    (when (< i (count pattern))
      (let [c (nth pattern i)]
        (cond
          in-class? (recur (inc i) (not= c \]))
          (= c \[) (recur (inc i) true)
          (= c \{) i
          :else (recur (inc i) false))))))

(defn- brace-parts
  "The index after the `}` matching OPEN and the brace body split on top-level
   commas, or nil when OPEN is unmatched. Braces and commas inside `[...]`
   are ignored."
  [pattern open]
  (loop [i (inc open) depth 1 in-class? false start (inc open) parts []]
    (when (< i (count pattern))
      (let [c (nth pattern i)]
        (cond
          in-class? (recur (inc i) depth (not= c \]) start parts)
          (= c \[) (recur (inc i) depth true start parts)
          (= c \{) (recur (inc i) (inc depth) false start parts)
          (= c \}) (if (= depth 1)
                     [(inc i) (conj parts (subs pattern start i))]
                     (recur (inc i) (dec depth) false start parts))
          (and (= c \,) (= depth 1))
          (recur (inc i) depth false (inc i) (conj parts (subs pattern start i)))
          :else (recur (inc i) depth false start parts))))))

(defn- expand-braces
  "PATTERN with its brace groups expanded into one pattern per alternative.
   Braces inside `[...]` are literal."
  [pattern]
  (if-let [open (brace-open pattern)]
    (if-let [[end parts] (brace-parts pattern open)]
      (let [prefix (subs pattern 0 open)
            suffix (subs pattern end)]
        (mapcat #(expand-braces (str prefix % suffix)) parts))
      [pattern])
    [pattern]))

(def ^:private regex-special
  (set "\\.[]{}()*+?^$|"))

(defn- star-token
  "The `*` or `**` token at I in PATTERN: `[regex next-index]`."
  [pattern i n]
  (if (and (< (inc i) n) (= \* (nth pattern (inc i))))
    (let [slash? (and (< (+ i 2) n) (= \/ (nth pattern (+ i 2))))]
      [(if slash? "(?:.*/)?" ".*")
       (if slash? (+ i 3) (+ i 2))])
    ["[^/]*" (inc i)]))

(defn- class-token
  "The character-class token at I in PATTERN: `[regex next-index]`."
  [pattern i]
  (if-let [end (str/index-of pattern "]" (inc i))]
    (let [body (subs pattern (inc i) end)
          body (cond
                 (str/starts-with? body "!") (str "^" (subs body 1))
                 (str/starts-with? body "^") (str "\\" body)
                 :else body)]
      [(str "[" body "]") (inc end)])
    ["\\[" (inc i)]))

(defn- char->regex
  "C as a regex atom, escaped when it is a regex metacharacter."
  [c]
  (if (contains? regex-special c) (str "\\" c) (str c)))

(defn- glob->regex
  "PATTERN as a regex."
  [pattern]
  (let [n (count pattern)]
    (loop [i 0 parts []]
      (if (>= i n)
        (re-pattern (apply str parts))
        (let [c (nth pattern i)
              [part next-i] (case c
                              \* (star-token pattern i n)
                              \? ["[^/]" (inc i)]
                              \[ (class-token pattern i)
                              [(char->regex c) (inc i)])]
          (recur next-i (conj parts part)))))))

(defn- compile-one
  "Compile one pattern, wrapping a regex-engine error with its source."
  [pattern]
  (try
    (mapv glob->regex (expand-braces pattern))
    (catch Exception e
      (throw (ex-info (str "Invalid glob pattern: " pattern)
                      {:type ::invalid-pattern :pattern pattern}
                      e)))))

(defn compile-matcher
  "Compile PATTERNS — a pattern string or a seq of pattern strings — into a
   matcher for `matches?`. A malformed pattern throws ex-info with
   :type ::invalid-pattern and :pattern."
  [patterns]
  (vec (mapcat compile-one (if (string? patterns) [patterns] patterns))))

(defn matches?
  "True when `/`-separated PATH matches MATCHER from `compile-matcher`."
  [matcher path]
  (boolean (some #(re-matches % path) matcher)))
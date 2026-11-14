(ns kmet.libs.glob
  "Shell-style glob matching for `/`-separated paths.

   `*` matches within one path segment, `**` crosses segments, `**/` also
   matches zero directories, `?` matches one non-slash character, `[abc]` and
   `[!abc]` are character classes, and `{a,b}` expands to alternatives. Other
   regex metacharacters are literal. Callers normalize host separators before
   matching."
  (:require [clojure.string :as str]))

(defn- expand-braces
  "PATTERN with the first `{a,b}` expanded into one pattern per choice."
  [pattern]
  (if-let [open (str/index-of pattern "{")]
    (if-let [close (str/index-of pattern "}" open)]
      (let [body (subs pattern (inc open) close)
            prefix (subs pattern 0 open)
            suffix (subs pattern (inc close))]
        (mapcat #(expand-braces (str prefix % suffix))
                (str/split body #"," -1)))
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
  [pattern i n]
  (loop [j (inc i)]
    (cond
      (>= j n) ["\\[" (inc i)]
      (= \] (nth pattern j))
      (let [body (subs pattern (inc i) j)
            body (if (str/starts-with? body "!") (str "^" (subs body 1)) body)]
        [(str "[" body "]") (inc j)])
      :else (recur (inc j)))))

(defn- char->regex
  "C as a regex atom, escaped when it is a regex metacharacter."
  [c]
  (if (contains? regex-special c) (str "\\" c) (str c)))

(defn- glob->regex
  "PATTERN as a regex string."
  [pattern]
  (let [n (count pattern)]
    (loop [i 0 parts []]
      (if (>= i n)
        (re-pattern (apply str parts))
        (let [c (nth pattern i)
              [part next-i] (cond
                              (= c \*) (star-token pattern i n)
                              (= c \?) ["[^/]" (inc i)]
                              (= c \[) (class-token pattern i n)
                              :else [(char->regex c) (inc i)])]
          (recur next-i (conj parts part)))))))

(defn compile-pattern
  "Compile PATTERN to a matcher for `matches?`."
  [pattern]
  (mapv glob->regex (expand-braces pattern)))

(defn compile-patterns
  "Compile PATTERNS into one matcher. Nil and an empty seq match nothing."
  [patterns]
  (into [] (mapcat compile-pattern) patterns))

(defn matches?
  "True when `/`-separated PATH matches MATCHER from `compile-pattern` or
   `compile-patterns`."
  [matcher path]
  (boolean (some #(re-matches % path) matcher)))

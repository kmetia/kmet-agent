(ns kmet.tui.autocomplete
  "Autocomplete providers for the multi-line editor.
   Port of @earendil-works/pi-tui autocomplete.ts — a combined provider
   handling slash commands, command arguments, and file path completion.
   Synchronous (kmet runs single-threaded); pi's async AbortController
   and debounce machinery are unnecessary because each request computes
   fresh from the current editor state."
  (:require [kmet.tui.fuzzy :as fuzzy]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;; ─── Records ───────────────────────────────────────────────────────────────

(defrecord AutocompleteItem [value label description])

(defprotocol AutocompleteProvider
  (get-suggestions
    [this lines cursor-line cursor-col opts]
    "Return suggestions for the current text/cursor position, or nil when
     no suggestions are available. The result is {:items [AutocompleteItem]
     :prefix string}. opts is a map with :force (true for explicit Tab
     file completion).")
  (apply-completion
    [this lines cursor-line cursor-col item prefix]
    "Apply the selected item, returning {:lines :cursor-line :cursor-col}
     for the new editor state.")
  (should-trigger-file-completion
    [this lines cursor-line cursor-col]
    "True when Tab should attempt file completion at the current position.")
  (get-trigger-characters
    [this]
    "Characters (beyond the default @ #) that auto-trigger this provider
     at token boundaries."))

;; ─── Path prefix parsing ───────────────────────────────────────────────────

(def ^:private path-delimiters #{" " "\t" "\"" "'" "="})

(defn- find-last-delimiter
  "Index of the last path delimiter in text, or -1."
  [text]
  (loop [i (dec (count text))]
    (if (neg? i)
      -1
      (if (contains? path-delimiters (subs text i (inc i)))
        i
        (recur (dec i))))))

(defn- find-unclosed-quote-start
  "Index of an unclosed double quote in text, or nil."
  [text]
  (loop [i 0 in-quotes? false quote-start -1]
    (if (>= i (count text))
      (when in-quotes? quote-start)
      (if (= (nth text i) \")
        (recur (inc i) (not in-quotes?) (if in-quotes? quote-start i))
        (recur (inc i) in-quotes? quote-start)))))

(defn- is-token-start?
  [text index]
  (or (zero? index)
      (contains? path-delimiters (subs text (dec index) index))))

(defn- extract-quoted-prefix
  "Prefix from an unclosed quote back to its start (or back to an @ before
   the quote). Returns nil when the quote is not at a token start."
  [text]
  (when-let [quote-start (find-unclosed-quote-start text)]
    (cond
      (and (pos? quote-start)
           (= (subs text (dec quote-start) quote-start) "@")
           (is-token-start? text (dec quote-start)))
      (subs text (dec quote-start))

      (is-token-start? text quote-start)
      (subs text quote-start)

      :else nil)))

(defn- parse-path-prefix
  "Split a completion prefix into {:raw-prefix :is-at-prefix
   :is-quoted-prefix}."
  [prefix]
  (cond
    (str/starts-with? prefix "@\"")
    {:raw-prefix (subs prefix 2) :is-at-prefix true :is-quoted-prefix true}

    (str/starts-with? prefix "\"")
    {:raw-prefix (subs prefix 1) :is-at-prefix false :is-quoted-prefix true}

    (str/starts-with? prefix "@")
    {:raw-prefix (subs prefix 1) :is-at-prefix true :is-quoted-prefix false}

    :else
    {:raw-prefix prefix :is-at-prefix false :is-quoted-prefix false}))

(defn- expand-home-path
  "Expand a leading ~ to the user's home directory, preserving any
   trailing slash."
  [path]
  (cond
    (str/starts-with? path "~/")
    (let [home (System/getProperty "user.home")
          expanded (str home (subs path 1))]
      (if (and (str/ends-with? path "/") (not (str/ends-with? expanded "/")))
        (str expanded "/")
        expanded))
    (= path "~")
    (System/getProperty "user.home")
    :else path))

(defn- build-completion-value
  [path {:keys [is-at-prefix is-quoted-prefix]}]
  (let [needs-quotes? (or is-quoted-prefix (str/includes? path " "))
        prefix (if is-at-prefix "@" "")]
    (if needs-quotes?
      (str prefix "\"" path "\"")
      (str prefix path))))

;; ─── File completion (synchronous directory listing) ───────────────────────

(defn- relative-path
  "Build the completion path for an entry name given the raw prefix,
   preserving ~/, absolute, and ./ forms (pi: getFileSuggestions)."
  [raw-prefix name]
  (cond
    (str/ends-with? raw-prefix "/")
    (str raw-prefix name)

    (or (str/includes? raw-prefix "/") (str/includes? raw-prefix "\\"))
    (cond
      (str/starts-with? raw-prefix "~/")
      (let [home-rel (subs raw-prefix 2)
            d (or (fs/parent home-rel) "")]
        (str "~/" (if (or (nil? d) (= d "") (= d ".")) name (str d "/" name))))

      (str/starts-with? raw-prefix "/")
      (let [d (or (fs/parent raw-prefix) "/")]
        (if (= d "/") (str "/" name) (str d "/" name)))

      :else
      (let [d (or (fs/parent raw-prefix) "")
            rp (if (or (nil? d) (= d "") (= d ".")) name (str d "/" name))]
        (if (and (str/starts-with? raw-prefix "./") (not (str/starts-with? rp "./")))
          (str "./" rp)
          rp)))

    (str/starts-with? raw-prefix "~")
    (str "~/" name)

    :else name))

(defn- base-path-value
  "The provider's base path: a string, or a 0-arg fn resolved per call. The
   app passes a fn so the runtime cwd is picked up — pi rebuilds the
   autocomplete provider on session replacement (setupAutocompleteProvider),
   kmet resolves it live."
  [provider]
  (let [b (:base-path provider)]
    (if (fn? b) (b) b)))

(defn- get-file-suggestions
  "Directory listing for the given path prefix (pi: readdirSync approach).
   Returns a vector of AutocompleteItem maps."
  [base-path prefix]
  (try
    (let [{:keys [raw-prefix is-at-prefix is-quoted-prefix]} (parse-path-prefix prefix)
          expanded (expand-home-path raw-prefix)
          absolute? (or (str/starts-with? raw-prefix "~") (str/starts-with? expanded "/"))
          root-prefix? (or (= raw-prefix "") (= raw-prefix "./") (= raw-prefix "../")
                           (= raw-prefix "~") (= raw-prefix "~/") (= raw-prefix "/")
                           (and is-at-prefix (= raw-prefix "")))
          [search-dir search-prefix]
          (cond
            (or root-prefix? (str/ends-with? raw-prefix "/"))
            [(if absolute? expanded (str base-path "/" expanded)) ""]

            :else
            (let [d (or (fs/parent expanded) "")]
              [(if absolute? d (str base-path "/" d)) (fs/file-name expanded)]))]
      (if (or (nil? search-dir) (not (fs/exists? search-dir)))
        []
        (let [entries (fs/list-dir search-dir)
              matches (filterv (fn [p]
                                 (let [n (fs/file-name p)]
                                   (str/starts-with? (str/lower-case n)
                                                     (str/lower-case search-prefix))))
                               entries)
              suggestions
              (mapv (fn [p]
                      (let [name (fs/file-name p)
                            is-dir? (fs/directory? p)
                            rel (relative-path raw-prefix name)
                            value (build-completion-value
                                   (str rel (when is-dir? "/"))
                                   {:is-directory is-dir?
                                    :is-at-prefix is-at-prefix
                                    :is-quoted-prefix is-quoted-prefix})]
                        {:value value
                         :label (str name (when is-dir? "/"))
                         :description nil}))
                    matches)]
          (sort-by (fn [s] [(not (str/ends-with? (:value s) "/")) (:label s)])
                   suggestions))))
    (catch Exception _
      [])))

;; ─── Slash command suggestions ─────────────────────────────────────────────

(defn- command-name
  [cmd]
  (or (:name cmd) (:value cmd)))

(defn- get-command-suggestions
  "Suggestions for a slash command context (text-before-cursor starts with
   \"/\"). Before the first space: fuzzy-match command names. After a
   space: delegate to the command's :get-argument-completions fn."
  [commands text-before-cursor]
  (let [space-idx (str/index-of text-before-cursor " ")]
    (if (nil? space-idx)
      (let [prefix (subs text-before-cursor 1)
            command-items (mapv (fn [cmd]
                                  (let [name (command-name cmd)
                                        hint (:argument-hint cmd)
                                        desc (str (or (:description cmd) ""))
                                        full-desc (cond
                                                    (and (seq hint) (seq desc)) (str hint " — " desc)
                                                    (seq hint) hint
                                                    (seq desc) desc
                                                    :else nil)]
                                    {:name name :label name :description full-desc}))
                                commands)
            filtered (fuzzy/fuzzy-filter command-items prefix #(or (:name %) ""))
            items (mapv #(map->AutocompleteItem {:value (:name %)
                                                 :label (:label %)
                                                 :description (:description %)})
                        filtered)]
        (when (seq items)
          {:items items :prefix text-before-cursor}))
      (let [cmd-name (subs text-before-cursor 1 space-idx)
            arg-text (subs text-before-cursor (inc space-idx))
            command (first (filter #(= (command-name %) cmd-name) commands))]
        (when (and command (:get-argument-completions command))
          (let [args (or ((:get-argument-completions command) arg-text) [])]
            (when (seq args)
              {:items (mapv #(if (map? %) (map->AutocompleteItem %) %) args)
               :prefix arg-text})))))))

;; ─── Prefix extraction ─────────────────────────────────────────────────────

(defn- extract-at-prefix
  "Completion prefix starting with @ at a token boundary, or nil."
  [text]
  (let [quoted (extract-quoted-prefix text)]
    (if (and quoted (str/starts-with? quoted "@\""))
      quoted
      (let [last-delim (find-last-delimiter text)
            token-start (if (= last-delim -1) 0 (inc last-delim))]
        (when (and (< token-start (count text))
                   (= (subs text token-start (inc token-start)) "@"))
          (subs text token-start))))))

(defn- extract-path-prefix
  "Path-like completion prefix for the text before the cursor. With
   force-extract? true (Tab) the whole token is treated as a path prefix."
  [text force-extract?]
  (if-let [quoted (extract-quoted-prefix text)]
    quoted
    (let [last-delim (find-last-delimiter text)
          path-prefix (if (= last-delim -1) text (subs text (inc last-delim)))]
      (cond
        force-extract? path-prefix

        (or (str/includes? path-prefix "/")
            (str/starts-with? path-prefix ".")
            (str/starts-with? path-prefix "~/"))
        path-prefix

        (and (= path-prefix "") (str/ends-with? text " "))
        path-prefix

        :else nil))))

;; ─── Gitignore matching ───────────────────────────────────────────────────
;;
;; Bundled here (not a separate namespace) since only @ completion uses it.
;; Wherever a .gitignore is found its rules apply to that subtree; an ignored
;; directory is pruned, which also carries git's rule that a file cannot be
;; re-included below an excluded directory. `.git/info/exclude` is honored
;; when the walk root is a repo root. Scopes are cwd-internal: `/…`, `C:/…`,
;; `~/…` and `..` escapes complete nothing. Limitations: no global
;; core.excludesFile, no escaped trailing spaces, no parent-repo discovery
;; above the walk root, no worktree .git-file gitdir resolution.

(defn- glob->regex
  "Translate a gitignore glob into a regex fragment. `**` crosses
   directories; `*`, `?` and `[...]` do not."
  [pattern]
  (loop [i 0 out ""]
    (if (>= i (count pattern))
      out
      (let [c (nth pattern i)]
        (cond
          (= c \\)
          (if (< (inc i) (count pattern))
            (let [ch (nth pattern (inc i))]
              (recur (+ i 2) (str out (if (contains? #{\* \? \[ \] \{ \} \( \) \. \+ \| \^ \$ \\} ch)
                                        (str "\\" ch)
                                        ch))))
            (recur (inc i) (str out "\\\\")))

          (= c \*)
          (if (and (< (inc i) (count pattern)) (= (nth pattern (inc i)) \*))
            (if (and (< (+ i 2) (count pattern)) (= (nth pattern (+ i 2)) \/))
              (recur (+ i 3) (str out "(?:.*/)?"))
              (recur (+ i 2) (str out ".*")))
            (recur (inc i) (str out "[^/]*")))

          (= c \?)
          (recur (inc i) (str out "[^/]"))

          (= c \[)
          (if-let [end (str/index-of pattern "]" (inc i))]
            (let [class (subs pattern (inc i) end)
                  class (if (str/starts-with? class "!") (str "^" (subs class 1)) class)]
              (recur (inc end) (str out "[" class "]")))
            (recur (inc i) (str out "\\[")))

          (contains? #{\\ \. \+ \( \) \| \^ \$ \{ \} \]} c)
          (recur (inc i) (str out "\\" c))

          :else
          (recur (inc i) (str out c)))))))

(defn- pattern->rule
  "One gitignore line -> {:regex :negated? :dir-only?}, or nil for a line
   that carries no pattern (or whose pattern does not compile — one bad line
   must not discard the rest of the file)."
  [line]
  (let [negated? (str/starts-with? line "!")
        line (if negated? (subs line 1) line)
        dir-only? (str/ends-with? line "/")
        line (str/replace line #"/+$" "")
        leading? (str/starts-with? line "/")
        line (if leading? (subs line 1) line)]
    (when (seq line)
      (try
        (let [anchored? (or leading? (str/includes? line "/"))]
          {:regex (re-pattern (str (if anchored? "^" "(?:^|/)")
                                   (glob->regex line)
                                   "$"))
           :negated? negated?
           :dir-only? dir-only?})
        (catch Exception _ nil)))))

(defn- parse-gitignore-rules
  "Parse TEXT into a vector of rule maps (gitignore: last match wins)."
  [text]
  (into []
        (keep (fn [line]
                (let [line (str/trimr line)]
                  (when-not (or (str/blank? line) (str/starts-with? line "#"))
                    (pattern->rule line)))))
        (str/split-lines text)))

(defn- load-rules-file
  "Rules from F, or nil when it is missing, unreadable or empty."
  [f]
  (when (fs/exists? f)
    (try
      (let [rules (parse-gitignore-rules (slurp f))]
        (when (seq rules) rules))
      (catch Exception _ nil))))

(defn- path-ancestors
  "Ancestor directories of slash path REL, shallowest first:
   \"a/b/c\" -> (\"a\" \"a/b\")."
  [rel]
  (let [parts (str/split rel #"/")]
    (mapv #(str/join "/" %) (butlast (rest (reductions conj [] parts))))))

(defn- ignored-by-rules?
  "Last matching rule wins; nil when no rule matches."
  [rules rel dir?]
  (let [ancestors (path-ancestors rel)
        targets (if dir? (conj ancestors rel) ancestors)]
    (reduce (fn [verdict {:keys [regex dir-only? negated?]}]
              (if (or (some #(re-find regex %) targets)
                      (and (not dir-only?) (re-find regex rel)))
                (if negated? :unignored :ignored)
                verdict))
            nil
            rules)))

(defn- ignored?
  "Verdict for REL (slash path relative to the walk root) against the
   root->leaf STACK of {:up :prefix :rules} layers; deeper rules win. :up is
   the walk root's path relative to the layer's directory (blank for layers
   at or inside the walk root). Returns :ignored, :unignored or nil."
  [stack rel dir?]
  (reduce (fn [verdict {:keys [prefix rules up]}]
            (let [rel' (if (str/blank? up) rel (str up rel))
                  r (cond
                      (str/blank? prefix) rel'
                      (str/starts-with? rel' (str prefix "/")) (subs rel' (inc (count prefix)))
                      :else nil)]
              (if (and r (seq rules))
                (or (ignored-by-rules? rules r dir?) verdict)
                verdict)))
          nil
          stack))

;; ─── @-mention file search (walk + snapshot cache + scoring) ──────────────

(def ^:private max-walk-entries
  "Visited-entry budget for one walk. Not a pi knob: fd is unbounded-fast, a
   single-threaded babashka walk is not — this bounds the worst case."
  10000)

(def ^:private max-candidates
  "Candidate matches scored per query (pi: fd --max-results 100)."
  100)

(def ^:private max-suggestions
  "Suggestions shown (pi: slice(0, 20))."
  20)

(defonce ^:private file-cache (atom {}))

(def ^:private file-cache-max-entries
  "Snapshot cap: the least recently created snapshot is evicted first,
   bounding retained walks regardless of how many scopes are visited."
  8)

(defn invalidate-file-cache!
  "Drop the @-completion tree snapshots. The editor calls this when a new @
   token starts — first on the line or after whitespace — so every mention
   walks fresh — and when a message is submitted or the editor is cleared
   (no snapshot outlives its message); within one token, keystrokes reuse
   the same walks."
  []
  (reset! file-cache {}))

(defn- walk-entries
  "BFS over DIR (absolute) honoring .gitignore files found in any directory:
   prunes ignored directories and .git, follows symlinked dirs (cycle-guarded),
   stops after max-walk-entries visited entries. SEED holds ignore layers
   that apply to DIR from above (scoped walks — see seed-stack). Returns a
   vector of {:rel \"slash/path\" :dir? bool} relative to DIR."
  [dir seed]
  (let [root-stack (vec seed)
        visited (atom #{(try (str (fs/canonicalize dir)) (catch Exception _ (str dir)))})
        seen (atom 0)
        entries (atom [])]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY
                       {:dir dir :rel "" :stack root-stack})]
      (if (or (empty? queue) (>= @seen max-walk-entries))
        @entries
        (let [{:keys [dir rel stack]} (peek queue)
              child-stack (if-let [rules (load-rules-file (str dir "/.gitignore"))]
                            (conj stack {:prefix rel :rules rules})
                            stack)
              children (try
                         (fs/list-dir dir)
                         (catch Exception _ nil))]
          (if (nil? children)
            (recur (pop queue))
            (recur
             (reduce
              (fn [q entry]
                (let [name (fs/file-name entry)
                      entry-rel (if (empty? rel) name (str rel "/" name))
                      dir? (try (fs/directory? entry) (catch Exception _ false))]
                  (swap! seen inc)
                  (cond
                    (>= @seen max-walk-entries)
                    (reduced q)

                    (or (= name ".git")
                        (= :ignored (ignored? child-stack entry-rel dir?)))
                    q

                    dir?
                    (let [canon (try (str (fs/canonicalize entry)) (catch Exception _ entry-rel))]
                      (if (contains? @visited canon)
                        q
                        (do (swap! visited conj canon)
                            (swap! entries conj {:rel entry-rel :dir? true})
                            (conj q {:dir entry :rel entry-rel :stack child-stack}))))

                    :else
                    (do (swap! entries conj {:rel entry-rel :dir? false})
                        q))))
              (pop queue)
              children))))))))

(defn- cache-key
  [dir]
  (try (str (fs/canonicalize dir)) (catch Exception _ (str dir))))

(defn- seed-stack
  "Ignore layers from BASE down to SCOPE's parent, for a walk rooted at
   SCOPE, so parent .gitignore files (and BASE's .git/info/exclude) keep
   applying to a scoped @ search. Each layer's :up maps a SCOPE-relative path
   to the path relative to that layer's directory. Returns [] when SCOPE is
   not inside BASE (ancestor rules are unavailable there)."
  [base scope]
  (try
    (let [base-abs (fs/normalize (fs/absolutize base))
          scope-abs (fs/normalize (fs/absolutize scope))
          rel (str/replace (str (fs/relativize base-abs scope-abs)) "\\" "/")
          rel (str/replace rel #"^\./" "")
          rel (if (= rel ".") "" rel)]
      (if (or (str/starts-with? rel "..") (str/starts-with? rel "/"))
        []
        (let [parts (if (str/blank? rel) [] (str/split rel #"/"))
              idxs (if (empty? parts) [0] (range (count parts)))]
          (vec
           (keep (fn [i]
                   (let [dir-rel (str/join "/" (take i parts))
                         dir (if (str/blank? dir-rel) (str base-abs) (str base-abs "/" dir-rel))
                         tail (drop i parts)
                         up (if (seq tail) (str (str/join "/" tail) "/") "")
                         rules (into []
                                     (concat
                                      (when (zero? i)
                                        (load-rules-file (str base-abs "/.git/info/exclude")))
                                       ;; the walk root's own .gitignore is
                                       ;; loaded by walk-entries itself
                                      (when (or (pos? i) (not (str/blank? up)))
                                        (load-rules-file (str dir "/.gitignore")))))]
                     (when (seq rules)
                       {:up up :prefix "" :rules rules})))
                 idxs)))))
    (catch Exception _ [])))

(defn- evict-oldest-snapshot
  "Drop the snapshot with the smallest :at (creation time)."
  [cache]
  (if-let [oldest (first (sort-by (comp :at val) cache))]
    (dissoc cache (key oldest))
    cache))

(defn- cached-walk-entries
  "walk-entries behind the per-message snapshot cache (see
   invalidate-file-cache!); the oldest snapshot is evicted at
   file-cache-max-entries. The ancestor seed-stack is only computed on a
   miss — a hit costs two cache-key computations and the scoring pass."
  [dir base-path]
  (let [k [(cache-key dir) (cache-key base-path)]
        hit (get @file-cache k)]
    (if hit
      (:entries hit)
      (let [entries (walk-entries dir (seed-stack base-path dir))]
        (swap! file-cache
               (fn [cache]
                 (let [cache (if (and (not (contains? cache k))
                                      (>= (count cache) file-cache-max-entries))
                               (evict-oldest-snapshot cache)
                               cache)]
                   (assoc cache k {:at (System/currentTimeMillis) :entries entries}))))
        entries))))

(defn- inside-base?
  "True when slash path P (relative to the base directory) does not climb
   above the base — `..` segments below depth 0 escape the project cwd."
  [p]
  (loop [segs (str/split p #"/") depth 0]
    (if (empty? segs)
      true
      (let [s (first segs)]
        (cond
          (= s "..") (if (zero? depth) false (recur (rest segs) (dec depth)))
          (or (str/blank? s) (= s ".")) (recur (rest segs) depth)
          :else (recur (rest segs) (inc depth)))))))

(defn- resolve-scope
  "Split the @ raw prefix into the search directory, the display prefix kept
   in completion values, and the query; nil when the prefix leaves the
   project cwd or the directory does not exist. Only base-relative paths
   complete: `/…`, `C:/…`, `~/…` and `..` escapes yield no suggestions.
   Examples: \"au\" -> base, \"\"; \"src/kmet/au\" -> <base>/src/kmet,
   \"src/kmet/\"."
  [base-path raw-prefix]
  (let [slash (str/last-index-of raw-prefix "/")
        dir-part (when slash (subs raw-prefix 0 slash))
        query (if slash (subs raw-prefix (inc slash)) raw-prefix)]
    (when (and (not (str/starts-with? raw-prefix "/"))
               (not (re-find #"(?i)^[a-z]:[\\/]" raw-prefix))
               (not (str/starts-with? raw-prefix "~"))
               (or (nil? dir-part) (inside-base? dir-part)))
      (if (str/blank? dir-part)
        {:scope-dir base-path :display-prefix "" :query query}
        (let [scope-dir (str base-path "/" dir-part)]
          (when (and (fs/exists? scope-dir) (fs/directory? scope-dir))
            {:scope-dir scope-dir
             :display-prefix (str dir-part "/")
             :query query}))))))

(defn- fuzzy-fallback-score
  "Subsequence match on the basename (kmet.tui.fuzzy), scored below pi's
   literal substring tiers; any positive score means \"include\"."
  [rel query]
  (let [{:keys [matches score]} (fuzzy/fuzzy-match query (last (str/split rel #"/")))]
    (when matches
      (max 1 (min 25 (- 30 score))))))

(defn- score-entry
  "pi scoreEntry without the full-path tier — candidates are name matches
   (pi: fd matches the entry name), so a path-only hit is not a candidate —
   plus the subsequence fallback; directories get +10."
  [rel query dir?]
  (let [name (last (str/split rel #"/"))
        lname (str/lower-case name)
        lquery (str/lower-case query)
        base (cond
               (= lname lquery) 100
               (str/starts-with? lname lquery) 80
               (str/includes? lname lquery) 50
               :else (or (fuzzy-fallback-score rel query) 0))]
    (if (and (pos? base) dir?) (+ base 10) base)))

(defn- get-fuzzy-file-suggestions
  "Fuzzy @-mention suggestions: cached walk (seeded with ancestor ignore
   layers for scoped queries) -> score against the query -> pi's candidate
   cap -> best 20. A blank query lists the first 20 walk entries, so a bare
   `@` (or `@dir/`) shows the same gitignore-pruned tree view. Descriptions
   are the display paths."
  [base-path at-prefix]
  (let [{:keys [raw-prefix is-quoted-prefix]} (parse-path-prefix at-prefix)]
    (when-let [{:keys [scope-dir display-prefix query]} (resolve-scope base-path raw-prefix)]
      (let [entries (cached-walk-entries scope-dir base-path)
            picked (if (str/blank? query)
                     (take max-suggestions
                           (map (fn [{:keys [rel dir?]}] [0 rel dir?]) entries))
                     (->> entries
                          (keep (fn [{:keys [rel dir?]}]
                                  (let [score (score-entry rel query dir?)]
                                    (when (pos? score)
                                      [score rel dir?]))))
                          (take max-candidates)
                          (sort-by (fn [[score rel _]] [(- score) rel]))
                          (take max-suggestions)))]
        (when (seq picked)
          {:items (mapv (fn [[_ rel dir?]]
                          (let [display (str display-prefix rel)]
                            {:value (build-completion-value
                                     (str display (when dir? "/"))
                                     {:is-at-prefix true :is-quoted-prefix is-quoted-prefix})
                             :label (str (last (str/split rel #"/")) (when dir? "/"))
                             :description display}))
                        picked)
           :prefix at-prefix})))))

;; ─── Combined provider ─────────────────────────────────────────────────────

(defrecord CombinedAutocompleteProvider [commands-fn base-path trigger-chars]
  AutocompleteProvider

  (get-trigger-characters [_this]
    trigger-chars)

  (get-suggestions [this lines cursor-line cursor-col {:keys [force]}]
    (let [line (or (nth lines cursor-line) "")
          cursor-col (min cursor-col (count line))
          before (subs line 0 cursor-col)
          base-path (base-path-value this)]
      (if-let [at-prefix (extract-at-prefix before)]
        (get-fuzzy-file-suggestions base-path at-prefix)
        (if (and (not force) (str/starts-with? before "/"))
          (get-command-suggestions (commands-fn) before)
          (when-let [path-prefix (extract-path-prefix before (boolean force))]
            (let [suggestions (get-file-suggestions base-path path-prefix)]
              (when (seq suggestions)
                {:items suggestions :prefix path-prefix})))))))

  (apply-completion [_this lines cursor-line cursor-col item prefix]
    (let [line (or (nth lines cursor-line) "")
          cursor-col (min cursor-col (count line))
          before-prefix (subs line 0 (max 0 (- cursor-col (count prefix))))
          after-cursor (subs line cursor-col)
          is-quoted-prefix? (or (str/starts-with? prefix "\"")
                                (str/starts-with? prefix "@\""))
          has-leading-quote-after? (str/starts-with? after-cursor "\"")
          has-trailing-quote? (str/ends-with? (or (:value item) "") "\"")
          adjusted-after (if (and is-quoted-prefix? has-trailing-quote? has-leading-quote-after?)
                           (subs after-cursor 1)
                           after-cursor)
          value (:value item)
          is-slash-command? (and (str/starts-with? prefix "/")
                                 (str/blank? (str/trim before-prefix))
                                 (not (str/includes? (subs prefix 1) "/")))
          is-at-prefix? (str/starts-with? prefix "@")
          is-dir? (str/ends-with? (or (:label item) (:value item) "") "/")
          cursor-offset (if (and is-dir? has-trailing-quote?) (dec (count value)) (count value))
          [new-line new-col]
          (cond
            is-slash-command?
            [(str before-prefix "/" value " " adjusted-after)
             (+ (count before-prefix) (count value) 2)]

            is-at-prefix?
            (let [suffix (if is-dir? "" " ")]
              [(str before-prefix value suffix adjusted-after)
               (+ (count before-prefix) cursor-offset (count suffix))])

            :else
            [(str before-prefix value adjusted-after)
             (+ (count before-prefix) cursor-offset)])]
      {:lines (assoc lines cursor-line new-line)
       :cursor-line cursor-line
       :cursor-col new-col}))

  (should-trigger-file-completion [_this lines cursor-line cursor-col]
    (let [line (or (nth lines cursor-line) "")
          before (str/trim (subs line 0 cursor-col))]
      ;; Don't offer file completion for a bare slash command name
      ;; (pi: trim() both sides, so "/model " is also blocked)
      (not (and (str/starts-with? before "/")
                (not (str/includes? before " ")))))))

(defn make-combined-provider
  "Create a CombinedAutocompleteProvider.
   :commands-fn — thunk returning the current slash commands (maps with
                  :name, :description, optional :argument-hint and
                  :get-argument-completions).
   :base-path — directory that relative path completion resolves against —
                a string or a 0-arg fn resolved per call (the app passes a
                fn so a session's runtime cwd is picked up).
   :trigger-chars — extra auto-trigger characters (default none)."
  [& {:keys [commands-fn base-path trigger-chars]
      :or {commands-fn (constantly []) trigger-chars []}}]
  (map->CombinedAutocompleteProvider
   {:commands-fn commands-fn
    :base-path (or base-path (System/getProperty "user.dir"))
    :trigger-chars (vec trigger-chars)}))

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
        (let [suggestions (get-file-suggestions base-path at-prefix)]
          (when (seq suggestions)
            {:items suggestions :prefix at-prefix}))
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

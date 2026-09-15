(ns kmet.extensions.tree-sitter.tools
  "The five structural code-intelligence tools. Each execute returns
   {:content text-for-the-model :details {...} :is-error bool} — details
   feed the renderers (SPEC.md §Tool renderers); errors are normal results,
   never exceptions. All five provision grammar + binary on demand
   (auto-download design) and steer agents away from grep for structure
   questions in their descriptions."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.grammars :as grammars]
            [kmet.extensions.tree-sitter.render :as render]
            [kmet.extensions.tree-sitter.symbols :as symbols]))

(def ^:private skip-dirs
  #{".git" "node_modules" "target" "dist" "build" ".kmet" "out" ".cpcache"
    ".lsp" ".clj-kondo" ".shadow-cljs" "__pycache__" "vendor"})

(defn- search-exts
  "Extensions scanned by project-wide tools — derived from the language
   table so the two can never drift apart."
  []
  (->> (grammars/languages)
       vals
       (mapcat :file-types)
       (map str/lower-case)
       set))

(defn- err [msg] {:content msg :is-error true})

(defn- ext-of [path]
  (some-> (fs/extension path) str/lower-case))

(defn- param
  "Trimmed string value for args key k, or nil when absent/blank."
  [args k]
  (let [v (get args k)]
    (when-not (str/blank? (str v))
      (str v))))

(defn- tracked-files
  "Files under root whose extension is in exts. Inside a git repo,
   git ls-files decides (respects ignore rules); otherwise a recursive walk
   that skips common junk dirs. Returns absolute path strings."
  [root exts]
  (let [root (str (fs/absolutize root))
        ext-set (set exts)
        keep? #(contains? ext-set (ext-of %))]
    (if (fs/exists? (fs/path root ".git"))
      (let [res (cli/process! ["git" "-C" root "ls-files"] nil)]
        (if (zero? (or (:exit res) 1))
          (->> (:out res) str/split-lines
               (map #(str (fs/path root %)))
               (filter #(and (fs/regular-file? %) (keep? %))))
          (throw (ex-info (str "git ls-files failed: " (:reason res))
                          {:type ::ls-files-failed}))))
      (->> (tree-seq (fn [d] (and (fs/directory? d)
                                  (not (contains? skip-dirs (str (fs/file-name d))))))
                     (fn [d] (filter fs/directory?
                                     (remove #(contains? skip-dirs
                                                         (str (fs/file-name %)))
                                             (fs/list-dir d))))
                     root)
           (mapcat fs/list-dir)
           (filter #(and (fs/regular-file? %) (keep? %)))
           (map str)))))

(defn- ensure-ready!
  "Resolve lang for the file extension and make binary+grammar available.
   Throws on infra failure / unsupported extension; returns lang string."
  [path opts]
  (let [lang (or (grammars/resolve-lang (ext-of path))
                 (throw (ex-info (str "no tree-sitter grammar configured for ."
                                      (ext-of path) " files")
                                 {:type ::no-grammar})))]
    (grammars/ensure-grammar! lang (select-keys opts [:base]))
    lang))

(defn- project-files
  "Tracked source files under ROOT (git ls-files when in a repo, else a
   skip-dirs walk), or nil when none — callers turn that into an error."
  [root]
  (let [files (tracked-files root (search-exts))]
    (when (seq files)
      (group-by (fn [f] (grammars/resolve-lang (ext-of f))) files))))

(defn- analyze-chunks
  "Run symbols/file-symbols-and-calls over every file in paths, batching
   parses. Yields [path tree src-lines lang] tuples; unreadable files are
   skipped rather than failing the whole batch."
  [paths lang opts]
  (for [chunk (partition-all symbols/batch-size paths)
        :let [trees (symbols/parse-files! chunk lang opts)]
        p chunk
        :let [tree (get trees p)
              lines (when tree
                      (try (str/split-lines (slurp p))
                           (catch Exception _ ::unreadable)))]
        :when (and tree (not= ::unreadable lines))]
    [p tree lines lang]))

;; ─── formatting ───────────────────────────────────────────────────────────

(defn- fmt-symbol [{:keys [name kind line]}]
  (format "%s %s (line %d)" (or kind "symbol") name line))

(defn- fmt-hit [file {:keys [name kind line]}]
  (format "%s:%d — %s %s" file line (or kind "symbol") name))

(defn- fmt-call-site [{:keys [file enclosing name line]}]
  (format "%s (%s:%d)" (or enclosing name) file line))

(defn- fmt-callee [{:keys [name file line]}]
  (format "%s (%s:%d)" name file line))

(defn- details-common [label count file-count queried-name]
  (cond-> {:count count :label label}
    file-count (assoc :file-count file-count)
    queried-name (assoc :name queried-name)))

;; ─── tool implementations (opts-injectable second arity for tests) ────────

(defn- existing-file!
  "Path when it exists and is a regular file; nil otherwise."
  [path]
  (when (and (fs/exists? path) (not (fs/directory? path)))
    path))

(defn list-symbols*
  ([args] (list-symbols* args {}))
  ([args opts]
   (if-some [path (param args :path)]
     (if-not (existing-file! path)
       (err (str "File not found: " path))
       (let [lang (ensure-ready! path opts)
             {:keys [symbols]} (symbols/analyze-file! path lang opts)]
         (if (seq symbols)
           {:content (str/join "\n" (map fmt-symbol symbols))
            :details (details-common "symbols" (count symbols) 1 nil)}
           (err (format "No symbols found in %s" path)))))
     (err "Missing required parameter: path"))))

(defn find-definition*
  ([args] (find-definition* args {}))
  ([args opts]
   (if-some [symbol (param args :symbol)]
     (let [root (or (param args :root) ".")]
       (if-some [by-lang (project-files root)]
         (let [hits (->> by-lang
                         (mapcat (fn [[lang paths]]
                                   (for [[p tree lines] (analyze-chunks paths lang opts)
                                         sym (:symbols (symbols/file-symbols-and-calls
                                                        tree lines lang p))
                                         :when (= symbol (:name sym))]
                                     (fmt-hit p sym))))
                         vec)]
           (if (seq hits)
             {:content (str/join "\n" hits)
              :details (details-common "definitions" (count hits) nil symbol)}
             (err (format "No definition found for '%s'" symbol))))
         (err (str "no supported source files under " root))))
     (err "Missing required parameter: symbol"))))

(defn get-symbol-body*
  ([args] (get-symbol-body* args {}))
  ([args opts]
   (if-some [path (param args :path)]
     (if-some [symbol (param args :symbol)]
       (if-not (existing-file! path)
         (err (str "File not found: " path))
         (let [lang (ensure-ready! path opts)
               {:keys [symbols src-lines]} (symbols/analyze-file! path lang opts)
               def-sym (some #(when (= symbol (:name %)) %) symbols)]
           (if-not def-sym
             (err (format "Symbol '%s' not found in %s" symbol path))
             (let [lines (subvec src-lines
                                 (dec (:line def-sym))
                                 (min (count src-lines) (:end-line def-sym)))
                   body (str/join "\n" lines)]
               {:content body
                :details {:name symbol
                          :line-count (count lines)
                          :path (str path)
                          :body body}}))))
       (err "Missing required parameter: symbol"))
     (err "Missing required parameter: path"))))

(defn find-callers*
  ([args] (find-callers* args {}))
  ([args opts]
   (if-some [symbol (param args :symbol)]
     (let [root (or (param args :root) ".")]
       (if-some [by-lang (project-files root)]
         (let [sites (->> by-lang
                          (mapcat (fn [[lang paths]]
                                    (for [[p tree lines] (analyze-chunks paths lang opts)
                                          c (:calls (symbols/file-symbols-and-calls
                                                     tree lines lang p))
                                          :when (= symbol (:name c))]
                                      {:file p :name (:name c)
                                       :enclosing (:enclosing c)
                                       :line (:line c)})))
                          (sort-by (juxt :file :line))
                          vec)
               callers (distinct (map :enclosing sites))]
           (if (seq sites)
             {:content (str/join "\n" (map fmt-call-site sites))
              :details (details-common "callers" (count callers) nil symbol)}
             (err (format "No callers found for '%s'" symbol))))
         (err (str "no supported source files under " root))))
     (err "Missing required parameter: symbol"))))

(defn find-callees*
  ([args] (find-callees* args {}))
  ([args opts]
   (if-some [path (param args :path)]
     (if-some [symbol (param args :symbol)]
       (if-not (existing-file! path)
         (err (str "File not found: " path))
         (let [lang (ensure-ready! path opts)
               {:keys [calls]} (symbols/analyze-file! path lang opts)
               uniq (->> (filter #(= symbol (:enclosing %)) calls)
                         (sort-by :line)
                         ;; first call site per distinct callee name
                         (reduce (fn [[seen acc] c]
                                   (if (contains? seen (:name c))
                                     [seen acc]
                                     [(conj seen (:name c))
                                      (conj acc {:file (str path)
                                                 :name (:name c)
                                                 :enclosing symbol
                                                 :line (:line c)})]))
                                 [#{} []])
                         peek
                         vec)
               sites (map fmt-callee uniq)]
           (if (seq sites)
             {:content (str/join "\n" sites)
              :details (details-common "callees" (count uniq) 1 symbol)}
             (err (format "No callees found for '%s'" symbol)))))
       (err "Missing required parameter: symbol"))
     (err "Missing required parameter: path"))))

;; ─── registration ─────────────────────────────────────────────────────────

(def ^:private common-guidelines
  "Guidelines shared by all five tools — steer agents from grep to
   structural navigation and set expectations for first-use latency."
  ["Prefer the tree-sitter structure tools over grep for structural questions — they match parsed code shape, not text."
   "find_definition and find_callers scan the whole project (root defaults to cwd); get_symbol_body and find_callees are file-scoped (path + symbol)."
   "All five parse on demand; the first use auto-downloads the tree-sitter binary + grammar (one-time latency). With a cold cache and no network the call fails with a clear error and retries on the next call."])

(defn- safe
  "Never-throw wrapper: infra/logic failures become normal error results."
  [f]
  (fn [args]
    (try (f args)
         (catch Exception e
           (err (str "tree-sitter: "
                     (or (not-empty (some-> e ex-message)) (str e))))))))

(def ^:private param-file
  {:type :string :description "File to inspect"})

(def ^:private param-root
  {:type :string :description "Project root directory (default: cwd)"})

(def ^:private param-symbol
  {:type :string :description "Exact symbol name"})

(defn tool-defs
  "The five tool definition maps for register-tool!. Descriptions steer
   agents from grep to structural navigation."
  []
  [{:name "list_symbols"
    :description "List all definitions (functions, classes, vars, methods) in a source file with line numbers and kinds. Prefer over grep when asked 'what's in this file'."
    :prompt-snippet "List all definitions in a source file (functions, classes, vars) with line numbers and kinds"
    :prompt-guidelines common-guidelines
    :params {"path" param-file}
    :render-call render/render-call
    :render-result render/render-result
    :execute (safe list-symbols*)
    :title render/title-list-symbols}
   {:name "find_definition"
    :description "Find where a named function/class/var is defined across the project. Prefer over grep: matches definitions structurally, not text occurrences."
    :prompt-snippet "Find where a named function/class/var is defined across the project"
    :prompt-guidelines common-guidelines
    :params {"symbol" param-symbol
             "root" param-root}
    :render-call render/render-call
    :render-result render/render-result
    :execute (safe find-definition*)
    :title render/title-find-definition}
   {:name "get_symbol_body"
    :description "Read the complete source body of one definition identified by file path and exact symbol name. Prefer over re-reading whole files."
    :prompt-snippet "Read the complete source body of one definition (path + symbol)"
    :prompt-guidelines common-guidelines
    :params {"path" param-file
             "symbol" param-symbol}
    :render-call render/render-call
    :render-result render/render-result
    :execute (safe get-symbol-body*)
    :title render/title-get-symbol-body}
   {:name "find_callers"
    :description "List every call site of a named function/method across the project, shown as caller(file:line). Prefer over grep for 'who uses X' questions."
    :prompt-snippet "List every call site of a named function/method across the project"
    :prompt-guidelines common-guidelines
    :params {"symbol" param-symbol
             "root" param-root}
    :render-call render/render-call
    :render-result render/render-result
    :execute (safe find-callers*)
    :title render/title-find-callers}
   {:name "find_callees"
    :description "List what one function calls (unique callees with first call site), scoped to that function's body in the given file."
    :prompt-snippet "List what one function calls — unique callees with first call site"
    :prompt-guidelines common-guidelines
    :params {"path" param-file
             "symbol" param-symbol}
    :render-call render/render-call
    :render-result render/render-result
    :execute (safe find-callees*)
    :title render/title-find-callees}])

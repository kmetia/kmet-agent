(ns kmet.app.packages
  "Package manager + resource resolution (pi: core/package-manager.ts + the
   package part of core/resource-loader.ts — the local directory/file source
   subset; npm and git installs are deliberately out of scope, see
   src/kmet/development/pi-alignment.md).

   A fifth, app-provided layer sits after packages: the bundled extensions
   (kmet.app.bundled-extensions) — the shipped
   `extensions/` set, present in every run mode and disabled unless the
   `:bundled-extensions` settings entries enable it. Bundled items rank
   last (5), are not `:packages` entries (kmet install/remove cannot touch
   them), and load through their descriptor (a checkout path in dev, a
   resource prefix in a built artifact) instead of a plain path.

   `resolve-package-items` is pi's full resolve pipeline: top-level settings
   resource entries (`:extensions`/`:skills`/`:prompts`/`:themes` — plain
   paths expand, `!glob`/`+path`/`-path` patterns filter), the fixed
   auto-dir scans (agent dir + .kmet), then packages, accumulated in pi's
   insertion order and finalized with pi's precedence sort (project-local 0,
   project-auto 1, user-local 2, user-auto 3, package 4, bundled 5) +
   canonical-path
   dedupe. `load-extensions!`/`load-skills!`/`load-prompts!`/`load-themes!`
   load the enabled items of a type.

   A package is a `:packages` entry in the global settings.edn
   (~/.kmet/agent/settings.edn, scope :user) or the project settings.edn
   (.kmet/settings.edn, scope :project). Entries are source strings or maps

     {:source \"../ext\"          ;; source (pi: PackageSource)
      :autoload false            ;; project delta over the user entry
      :extensions [\"+x.clj\"]     ;; per-type filter patterns}

   Sources are local files or directories only. A file loads as a single
   extension (pi: \"If the path is a file, it loads as a single extension\").
   A directory loads with pi's package rules: a directory with an
   extension.edn manifest is one kmet extension; otherwise resources come
   from the conventional extensions/ skills/ prompts/ themes/ subdirectories
   (each a container loaded with that resource type's own discovery rules);
   a directory with none of those is an extension container itself.

   Filter patterns (pi packages.md \"Package Filtering\"): plain patterns
   include matching resources, `!glob` excludes, `+path`/`-path` force
   include/exclude exact paths relative to the package root, an empty array
   disables all resources of that type, an absent key loads all. A project
   entry with :autoload false is a delta over the global entry of the same
   identity: only the resources its patterns mention are affected.

   Item identity (pi \"Scope and Deduplication\"): local packages are
   identified by their resolved absolute path; when the same package appears
   in both settings files the project entry wins (unless it is a
   :autoload false delta, which applies on top of the user entry). Resources
   with the same canonical path are loaded once (first wins)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.bundled-extensions :as bundled-extensions]
            [kmet.app.extensions :as extensions]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]
            [kmet.tui.theme :as theme]))

(def ^:private resource-types
  "The four package resource types (pi: RESOURCE_TYPES)."
  [:extensions :skills :prompts :themes])

(def ^:private resource-type-set (set resource-types))

;; ─── Source parsing (pi: isLocalPath / parseSource) ───────────────────────

(def ^:private remote-prefixes
  "Protocol/source prefixes that make a source non-local (pi: isLocalPath)."
  ["npm:" "git:" "github:" "http:" "https:" "ssh:"])

(defn local-source?
  "True when SOURCE is a local path (pi: isLocalPath — anything that does
   not start with npm:/git:/github:/http:/https:/ssh:; `file://` URLs are
   local paths exactly like pi, while `git://` URLs are remote)."
  [source]
  (let [s (str/trim (str source))]
    (not (some #(str/starts-with? s %) remote-prefixes))))

(defn parse-source
  "Parse a source string (pi: parseSource). Returns
   {:kind :local :path PATH} or {:kind :remote :prefix P :spec S}."
  [source]
  (let [s (str/trim (str source))]
    (if-let [p (some #(when (str/starts-with? s %) %) remote-prefixes)]
      {:kind :remote :prefix p :spec s}
      {:kind :local :path s})))

;; ─── Path helpers (pi: resolvePath / normalize / relative) ────────────────

(defn- expand-home
  "Expand a leading ~ (pi: normalizePath's expandTilde)."
  [s]
  (let [home (System/getProperty "user.home")]
    (cond
      (= s "~") home
      (str/starts-with? s "~/") (str home (subs s 1))
      :else s)))

(defn- file-url?
  "True when S is a file: URL (pi treats file: URLs as local paths)."
  [s]
  (str/starts-with? s "file://"))

(defn- local-path
  "pi normalizePath for local sources: trim, ~ expansion, file: URL decode."
  [s]
  (let [trimmed (str/trim (str s))
        expanded (expand-home (if (file-url? trimmed)
                                (subs trimmed (count "file://"))
                                trimmed))]
    expanded))

(defn resolve-path
  "Resolve a local source PATH against BASE-DIR (lexical: ~, absolute paths
   and `..` segments normalize without touching the filesystem — pi
   resolvePath). Returns the absolute string."
  [path base-dir]
  (let [s (local-path path)]
    (if (fs/absolute? s)
      (str (fs/normalize s))
      (str (fs/normalize (fs/path (str base-dir) s))))))

(defn normalize-abs
  "Lexically normalize an absolute path for identity comparisons (pi:
   resolvePath without filesystem access)."
  [p]
  (str (fs/normalize (str p))))

(defn canonicalize
  "pi canonicalizePath — resolve symlinks; unresolvable paths pass through
   unchanged."
  [p]
  (try (str (fs/canonicalize (str p)))
       (catch Exception _ (normalize-abs p))))

(defn- posix
  "Normalize path separators to / for pattern storage and matching
   (pi: toPosixPath)."
  [p]
  (str/replace (str p) "\\" "/"))

(defn- relative-posix
  "POSIX-relative path from BASE to TARGET: \".\" when TARGET is BASE, the
   absolute TARGET when the two share no root (Windows drives — pi:
   path.relative, which returns an absolute path then)."
  [base target]
  (try
    (let [rel (fs/relativize (fs/path (str base)) (fs/path (str target)))]
      (if (str/blank? (str rel)) "." (posix rel)))
    (catch Exception _
      (posix target))))

(defn base-dir-for-scope
  "The directory local sources of SCOPE resolve against (pi:
   getBaseDirForScope): the agent dir for user packages, the .kmet project
   dir for project packages."
  [scope]
  (if (= scope :project)
    (cfg/project-dir)
    (cfg/get-agent-dir)))

(defn resolve-source-path
  "Resolve SOURCE (a local source string) against the base dir of SCOPE."
  [source scope]
  (resolve-path (local-path source) (base-dir-for-scope scope)))

;; ─── Settings entries (pi: PackageSource read/write) ──────────────────────

(defn source-of
  "The source string of a settings entry (pi: getPackageSourceString)."
  [entry]
  (if (map? entry) (str (:source entry)) (str entry)))

(defn autoload-disabled?
  "True when ENTRY is an object entry with :autoload false (pi: a project
   delta over the global entry of the same identity)."
  [entry]
  (and (map? entry) (false? (:autoload entry))))

(defn package-identity
  "The identity of a package for dedupe (pi: getPackageIdentity): local
   packages are their resolved absolute path, remote ones their spec."
  [source scope]
  (let [parsed (parse-source source)]
    (if (= :local (:kind parsed))
      (str "local:" (resolve-path (:path parsed) (base-dir-for-scope scope)))
      (str "remote:" (:spec parsed)))))

(defn normalize-source-for-settings
  "The form a local SOURCE takes in the settings file of SCOPE: the path
   relative to the scope base dir when possible, otherwise a relative
   `../..` path, never the input verbatim (pi:
   normalizePackageSourceForSettings — relative(baseDir, resolve(source)))."
  [source scope]
  (let [parsed (parse-source source)]
    (if (= :remote (:kind parsed))
      (str source)
      (relative-posix (base-dir-for-scope scope)
                      (resolve-path (:path parsed) (str (fs/cwd)))))))

(defn- packages-of
  "The :packages entries of a settings map (pi: settings.packages ?? [])."
  [settings]
  (vec (or (:packages settings) [])))

(defn user-settings-map
  "The parsed global settings map, or {} (pi: getGlobalSettings)."
  []
  (or (cfg/read-global-settings-map) {}))

(defn project-settings-map
  "The parsed project settings map, or {} (pi: getProjectSettings)."
  []
  (or (cfg/read-project-settings-map) {}))

(defn user-packages
  "Configured user-scope packages from the global settings file."
  []
  (packages-of (user-settings-map)))

(defn project-packages
  "Configured project-scope packages from the project settings file."
  []
  (packages-of (project-settings-map)))

(defn- packages-source-match?
  "True when EXISTING-SOURCE (a configured entry in EXISTING-SCOPE) and
   INPUT-SOURCE name the same package (pi: packageSourcesMatch — the input
   side resolves against the current working directory, the configured side
   against its own scope base)."
  [existing-source existing-scope input-source]
  (let [parsed (parse-source input-source)]
    (if (= :remote (:kind parsed))
      (= (str existing-source) (:spec parsed))
      (= (resolve-source-path existing-source existing-scope)
         (resolve-path (:path parsed) (str (fs/cwd)))))))

(defn add-package-to-settings!
  "Add SOURCE to the :packages of the global (default) or project settings
   file (pi: addSourceToSettings). The stored form is normalized relative to
   the scope base dir; a matching entry updates in place (object entries
   keep their filters). Returns true when the settings file changed."
  [source & [{:keys [local]}]]
  (let [scope (if local :project :user)
        save! (if local cfg/save-project-setting! cfg/save-setting!)
        current (if local (project-packages) (user-packages))
        normalized (normalize-source-for-settings source scope)
        idx (first (keep-indexed (fn [i entry]
                                   (when (packages-source-match? (source-of entry) scope
                                                                 source)
                                     i))
                                 current))]
    (if idx
      (if (= (source-of (nth current idx)) normalized)
        false
        (let [existing (nth current idx)
              next (assoc current idx
                          (if (map? existing)
                            (assoc existing :source normalized)
                            normalized))]
          (save! [:packages] next)
          true))
      (do (save! [:packages] (conj current normalized))
          true))))

(defn remove-package-from-settings!
  "Remove every package matching SOURCE from the :packages of the global
   (default) or project settings file (pi: removeSourceFromSettings).
   Returns true when something was removed, false otherwise."
  [source & [{:keys [local]}]]
  (let [scope (if local :project :user)
        save! (if local cfg/save-project-setting! cfg/save-setting!)
        current (if local (project-packages) (user-packages))
        next (vec (remove #(packages-source-match? (source-of %) scope source)
                          current))]
    (if (not= (count next) (count current))
      (do (save! [:packages] next)
          true)
      false)))

(defn get-installed-path
  "The resolved path of SOURCE in SCOPE when it exists on disk, else nil
   (pi: getInstalledPath)."
  [source scope]
  (let [parsed (parse-source source)]
    (when (= :local (:kind parsed))
      (let [p (resolve-source-path (:path parsed) scope)]
        (when (fs/exists? p) p)))))

(defn list-configured-packages
  "All configured packages from both settings files with their scope and
   installed path (pi: listConfiguredPackages). Order: user entries first,
   then project entries."
  []
  (concat
   (for [entry (user-packages)]
     {:source (source-of entry)
      :scope :user
      :filtered (map? entry)
      :installed-path (get-installed-path (source-of entry) :user)})
   (for [entry (project-packages)]
     {:source (source-of entry)
      :scope :project
      :filtered (map? entry)
      :installed-path (get-installed-path (source-of entry) :project)})))

;; ─── Pattern matching (pi: applyPatterns & friends) ───────────────────────
;; Pattern kinds (pi packages.md): plain glob = include, `!glob` = exclude,
;; `+path`/`-path` = force include/exclude by exact path. Glob support is
;; minimatch's common subset: `*` (no /), `?`, `**` (any depth) and
;; `[...]` character classes; `*`/`?`/`**` do not match a leading dot
;; segment unless the pattern segment starts with a dot.

(defn- class-end
  "Index of the `]` closing the character class opened at IDX, or nil when
   the class is unclosed or contains a path separator — minimatch splits
   the pattern on `/` before parsing, so `[.../...]` is literal text.
   minimatch: a `]` directly after `[`, `[!` or `[^` is a literal member,
   not the close; `\\]` is escaped."
  [pattern idx]
  (let [start (+ idx (if (and (< (inc idx) (count pattern))
                              (contains? #{\! \^} (nth pattern (inc idx))))
                       2
                       1))
        from (if (= \] (get pattern start)) (inc start) start)
        end (loop [i from
                   esc? false]
              (when (< i (count pattern))
                (let [c (nth pattern i)]
                  (cond
                    esc? (recur (inc i) false)
                    (= c \\) (recur (inc i) true)
                    (= c \]) i
                    :else (recur (inc i) false)))))]
    (when (and end (not (str/includes? (subs pattern idx end) "/")))
      end)))

(defn- char-escape
  "Escape a literal character for a Java regex character class."
  [c]
  (if (re-find #"[\\\]\[\^&.\-]" (str c)) (str "\\" c) (str c)))

(defn- class-members
  "Parse a class body (no leading `!`/`^`) into member specs — `[:char C]`
   or `[:range A B]` — with minimatch's semantics: `\\X` is the literal X,
   `x-y` is a range, `x-x` is the single character, a reversed range is
   dropped. Returns nil when no member remains (minimatch poisons the glob
   then: it never matches)."
  [body]
  (let [n (count body)
        read-el (fn [i]
                  (if (and (= \\ (nth body i)) (< (inc i) n))
                    [(nth body (inc i)) (+ i 2) true]
                    [(nth body i) (inc i) false]))]
    (loop [i 0
           members []]
      (if (>= i n)
        (seq members)
        (let [[c after esc?] (read-el i)
              [c2 after2] (when (and (not esc?)
                                     (< (inc after) n)
                                     (= \- (nth body after)))
                            (read-el (inc after)))]
          (cond
            (nil? c2) (recur after (conj members [:char c]))
            (> (int c2) (int c)) (recur after2 (conj members [:range c c2]))
            (= c2 c) (recur after2 (conj members [:char c]))
            :else (recur after2 members)))))))

(defn- class-string
  "The Java regex of parsed class MEMBERS (negated? NEG?)."
  [members neg?]
  (str "[" (when neg? "^")
       (str/join (map (fn [[kind a b]]
                        (if (= kind :range)
                          (str (char-escape a) "-" (char-escape b))
                          (char-escape a)))
                      members))
       "]"))

(defn- class-regex
  "Compile the character class opened at IDX in PATTERN. Returns nil when
   the class is unclosed (minimatch: a literal `[` step), else
   {:regex R :next-idx N :guard? G} where after-index N resumes scanning
   and G requests the hidden-segment guard — minimatch omits it for a
   single positive member like `[.]`, which compiles to a literal."
  [pattern idx]
  (when-let [end (class-end pattern idx)]
    (let [cls (subs pattern (inc idx) end)
          neg? (or (str/starts-with? cls "!") (str/starts-with? cls "^"))
          members (class-members (if neg? (subs cls 1) cls))]
      (cond
        (nil? members)
        {:regex "(?!)" :next-idx (inc end) :guard? false}

        (and (not neg?) (= 1 (count members)) (= :char (first (first members))))
        {:regex (java.util.regex.Pattern/quote (str (second (first members))))
         :next-idx (inc end)
         :guard? false}

        :else
        {:regex (class-string members neg?) :next-idx (inc end) :guard? true}))))

(defn- glob->regex
  "Compile a glob PATTERN to a regex string (segment-aware: `**` may cross
   separators, `*`/`?` may not, and — minimatch's defaults — no wildcard
   matches a hidden segment unless the pattern writes its dot; a class
   whose single member is a dot, like `[.]`, writes that dot)."
  [pattern]
  (let [sb (StringBuilder. "^")]
    (loop [i 0
           seg-start? true]
      (if (>= i (count pattern))
        (str sb "$")
        (let [c (nth pattern i)
              next? (fn [n] (when (< (+ i n) (count pattern)) (nth pattern (+ i n))))
              globstar? (and (= c \*) (= \* (next? 1)) seg-start?
                             (or (nil? (next? 2)) (= \/ (next? 2))))]
          (cond
            globstar?
            (if (= \/ (next? 2))
              (do (.append sb "(?:(?!\\.)[^/]*/)*")
                  ;; minimatch: `**/` swallows its trailing slash
                  (recur (+ i 3) true))
              (do (.append sb "(?:(?!\\.)[^/]*/)*(?!\\.)[^/]*")
                  (recur (+ i 2) false)))

            (= c \*)
            (do (.append sb (if seg-start? "(?!\\.)" ""))
                (.append sb "[^/]*")
                (recur (inc i) false))

            (= c \?)
            (do (.append sb (if seg-start? "(?!\\.)" ""))
                (.append sb "[^/]")
                (recur (inc i) false))

            (= c \[)
            (if-let [{:keys [regex next-idx guard?]} (class-regex pattern i)]
              (do (when (and guard? seg-start?) (.append sb "(?!\\.)"))
                  (.append sb regex)
                  (recur next-idx false))
              (do (.append sb (java.util.regex.Pattern/quote (str c)))
                  (recur (inc i) false)))

            :else
            (do (.append sb (java.util.regex.Pattern/quote (str c)))
                (recur (inc i) (= c \/)))))))))

(defn- glob-matcher
  "A fn of one path string returning true when the path matches PATTERN
   (path separators normalized to / before matching)."
  [pattern]
  (let [re (re-pattern (glob->regex (posix pattern)))]
    (fn [path] (boolean (re-matches re (posix path))))))

(defn- match-forms
  "The path forms a pattern is matched against (pi: matchesAnyPattern):
   the path relative to BASE-DIR, its file name, and the absolute path —
   plus the parent-dir forms when the file is a SKILL.md (patterns may name
   the skill root directory)."
  [path base-dir]
  (let [rel (posix (fs/relativize (fs/path (str base-dir)) (fs/path (str path))))
        name (fs/file-name (str path))
        abs (posix path)
        is-skill? (= name "SKILL.md")]
    (cond-> [rel name abs]
      is-skill? (into [(posix (fs/relativize (fs/path (str base-dir))
                                             (fs/path (str (fs/parent path)))))
                       (fs/file-name (fs/parent (str path)))
                       (posix (str (fs/parent path)))]))))

(defn- matches-any-pattern?
  "True when any PATTERN (a glob) matches PATH relative to BASE-DIR (pi:
   matchesAnyPattern)."
  [patterns path base-dir]
  (let [forms (match-forms path base-dir)]
    (boolean (some (fn [p] (some (glob-matcher p) forms)) patterns))))

(defn- exact-form
  "The canonical exact form of a pattern/path for +/- matching (pi:
   normalizeExactPattern — strips a ./ prefix, posix separators)."
  [s]
  (let [s (str s)]
    (if (or (str/starts-with? s "./") (str/starts-with? s ".\\"))
      (posix (subs s 2))
      (posix s))))

(defn- matches-any-exact-pattern?
  "True when any PATTERN exactly names PATH (pi: matchesAnyExactPattern)."
  [patterns path base-dir]
  (let [forms (match-forms path base-dir)
        exacts (map exact-form patterns)]
    (boolean (some #(some (fn [f] (= % f)) forms) exacts))))

(defn apply-patterns
  "pi applyPatterns — apply filter patterns to PATHS (relative to BASE-DIR)
   and return the set of enabled paths. Steps: plain includes narrow (or
   everything when none); `!` globs exclude; `+` exact paths force-include;
   `-` exact paths force-exclude."
  [paths patterns base-dir]
  (let [patterns (mapv str patterns)
        override? #(or (str/starts-with? % "+") (str/starts-with? % "-") (str/starts-with? % "!"))
        includes (filterv #(not (override? %)) patterns)
        excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "!") patterns))
        force-includes (mapv #(subs % 1) (filterv #(str/starts-with? % "+") patterns))
        force-excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "-") patterns))
        step1 (if (seq includes)
                (filterv #(matches-any-pattern? includes % base-dir) paths)
                (vec paths))
        step2 (if (seq excludes)
                (filterv #(not (matches-any-pattern? excludes % base-dir)) step1)
                step1)
        step3 (if (seq force-includes)
                (into step2 (filter #(matches-any-exact-pattern? force-includes % base-dir)
                                    paths))
                step2)
        step4 (if (seq force-excludes)
                (filterv #(not (matches-any-exact-pattern? force-excludes % base-dir))
                         step3)
                step3)]
    (set step4)))

(defn apply-autoload-disabled-patterns
  "pi applyAutoloadDisabledPatterns — the delta view of a :autoload false
   entry: returns {path enabled} for exactly the resources its PATTERNS
   mention (+/plain patterns enable, -/! patterns disable)."
  [paths patterns base-dir]
  (reduce (fn [acc pattern]
            (let [target (if (str/starts-with? pattern "+")
                           (subs pattern 1)
                           (if (str/starts-with? pattern "-")
                             (subs pattern 1)
                             (if (str/starts-with? pattern "!")
                               (subs pattern 1)
                               pattern)))
                  enabled? (not (or (str/starts-with? pattern "-")
                                    (str/starts-with? pattern "!")))
                  exact? (or (str/starts-with? pattern "+")
                             (str/starts-with? pattern "-"))]
              (reduce (fn [acc path]
                        (if (if exact?
                              (matches-any-exact-pattern? [target] path base-dir)
                              (matches-any-pattern? [target] path base-dir))
                          (assoc acc path enabled?)
                          acc))
                      acc paths)))
          {}
          patterns))

;; ─── Item discovery (pi: collectResourceFiles per type) ───────────────────
;; Kmet resource units inside a container dir: extensions = extension
;; artifacts (top-level .clj/.jar/.zip files and extension.edn dirs), skills
;; = skill files (SKILL.md roots and flat .md, recursive), prompts = .md
;; files (top level), themes = .edn files (top level). The walkers are the
;; same ones the directory loaders use.

(defn- discover-items-in-dir
  "All item paths of TYPE inside container dir D (pi:
   collectResourceFiles — the same walkers the directory loaders use)."
  [type d]
  (case type
    :extensions (extensions/extension-artifact-paths d)
    :skills (skills/discover-skill-files d)
    :prompts (prompts/prompt-template-files-in-dir d)
    :themes (vec (or (theme/theme-files-in-dir d) []))))

;; ─── Resolution (pi: resolvePackageSources + toResolvedPaths) ─────────────

(defrecord PackageItem [path enabled resource-type metadata])

(defn- entry-filter
  "The per-type filter map of an object entry, nil for strings."
  [entry]
  (when (map? entry)
    (into {} (filter (fn [[k]] (contains? resource-type-set k))) entry)))

(defn- dedupe-entries
  "pi dedupePackages — project entries first; a same-identity pair keeps
   the project entry (it wins), unless the project entry is a
   :autoload false delta, in which case the user entry is kept too (delta
   first, applied on top of the user entry at resolve time)."
  [entries]
  (let [ordered (sort-by (fn [{:keys [scope]}] (if (= scope :project) 0 1)) entries)]
    (loop [remaining ordered
           result []
           seen {}]
      (if-let [{:keys [entry scope] :as item} (first remaining)]
        (let [id (package-identity (source-of entry) scope)
              idx (get seen id)]
          (if (nil? idx)
            (recur (rest remaining)
                   (conj result item)
                   (assoc seen id (count result)))
            (let [existing (nth result idx)]
              (cond
                (and (= :project (:scope existing)) (= :user scope)
                     (autoload-disabled? (:entry existing)))
                ;; project delta over the user entry: keep both
                (recur (rest remaining) (conj result item) seen)

                (= :project scope)
                ;; project entry replaces the user entry of the same identity
                (recur (rest remaining) (assoc result idx item) seen)

                :else
                ;; duplicate within the same scope: first wins
                (recur (rest remaining) result seen)))))
        result))))

(defn- resource-rank
  "pi resourcePrecedenceRank — lower rank wins: 0 project + settings entry,
   1 project + auto-discovered, 2 user + settings entry, 3 user +
   auto-discovered, 4 package resource, 5 bundled resource."
  [item]
  (let [{:keys [origin scope source]} (:metadata item)]
    (cond
      (= :bundled origin) 5
      (= :package origin) 4
      :else (+ (if (= :project scope) 0 2)
               (if (= "local" source) 0 1)))))

(defn- add-resource!
  "Accumulate one resolved resource (pi: addResource — first add wins for a
   given path string, insertion order kept; the finalize pass then applies
   pi's rank sort + canonical dedupe across all layers)."
  [acc type item]
  (let [by-type (get @acc type)
        key (str (:path item))]
    (when-not (contains? (:items by-type) key)
      (swap! acc update type
             (fn [t] (-> t
                         (assoc-in [:items key] item)
                         (update :order conj key)))))))

(defn- finalize-items
  "pi toResolvedPaths for one type: stable-sort the accumulated items by
   precedence rank, then dedupe by canonical path (first wins). Sorting is
   stable, so equal-rank items keep their layer insertion order."
  [{:keys [order items]}]
  (let [sorted (sort-by resource-rank (map items order))
        {result :result}
        (reduce (fn [{:keys [seen] :as acc} item]
                  (let [k (canonicalize (:path item))]
                    (if (contains? seen k)
                      acc
                      (-> acc
                          (update :seen conj k)
                          (update :result conj item)))))
                {:seen #{} :result []}
                sorted)]
    (vec result)))

(defn- collect-files-from-paths
  "pi collectFilesFromPaths — PATHS (files and/or directories) expanded to
   item paths of TYPE: a file contributes itself, a directory contributes
   its discovered items. Missing paths are skipped."
  [paths type]
  (vec (mapcat (fn [p]
                 (cond
                   (fs/regular-file? p) [(str p)]
                   (fs/directory? p) (discover-items-in-dir type (str p))
                   :else []))
               paths)))

(defn- pattern-entry?
  "pi isPattern — an entry carrying glob/override syntax (`*`, `?`, or a
   `!`/`+`/`-` prefix) as opposed to a plain path."
  [entry]
  (let [s (str entry)]
    (or (str/starts-with? s "!")
        (str/starts-with? s "+")
        (str/starts-with? s "-")
        (str/includes? s "*")
        (str/includes? s "?"))))

(defn- enabled-by-overrides?
  "pi isEnabledByOverrides — auto-dir enabled state: `!` globs exclude,
   `+` exact paths force-include, `-` exact paths force-exclude; the set
   starts enabled. Only override entries participate — plain includes are
   ignored (auto dirs have no narrowing step). pi applies the checks in
   sequence (exclude → force-include → force-exclude), so a later check
   wins: `+` overrides `!`, `-` overrides `+`."
  [path patterns base-dir]
  (let [patterns (mapv str patterns)
        excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "!") patterns))
        force-includes (mapv #(subs % 1) (filterv #(str/starts-with? % "+") patterns))
        force-excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "-") patterns))]
    (cond
      (and (seq force-excludes) (matches-any-exact-pattern? force-excludes path base-dir)) false
      (and (seq force-includes) (matches-any-exact-pattern? force-includes path base-dir)) true
      (and (seq excludes) (matches-any-pattern? excludes path base-dir)) false
      :else true)))

(defn- resolve-local-entries
  "pi resolveLocalEntries — top-level settings ENTRIES of TYPE into items:
   plain entries resolve against BASE-DIR and expand to files (a file
   contributes itself, a directory its discovered items); the collected
   files are then filtered by the pattern entries via apply-patterns
   (plain includes narrow, `!`/`+`/`-` adjust). Items carry origin
   :top-level, source \"local\"."
  [acc type entries metadata base-dir]
  (let [entries (mapv str (or entries []))
        {:keys [plain patterns]} (group-by #(if (pattern-entry? %) :patterns :plain) entries)
        resolved (mapv #(resolve-path % base-dir) (or plain []))
        files (collect-files-from-paths resolved type)
        ;; pi's pattern stage: only the override/glob entries filter the
        ;; collected files — plain entries are not includes.
        enabled (apply-patterns files patterns base-dir)]
    (doseq [path files]
      (add-resource! acc type
                     (->PackageItem path (contains? enabled path) type metadata)))))

(defn- resolve-auto-dir
  "pi addAutoDiscoveredResources (one dir) — discovered items of TYPE in
   DIR with override-only enabled state from the same-type top-level
   entries (isEnabledByOverrides against BASE-DIR, the scope root). Items
   carry origin :top-level, source \"auto\"."
  [acc type dir metadata base-dir overrides]
  (doseq [path (discover-items-in-dir type dir)]
    (add-resource! acc type
                   (->PackageItem path (enabled-by-overrides? path overrides base-dir)
                                  type metadata))))

(defn- resolve-bundled-layer
  "pi addAutoDiscoveredResources' trailing analogue — the bundled
   extensions appended after every other layer. ITEMS are already
   PackageItem-shaped and enabled-stamped (bundled-items); rank 5 (see
   resource-rank) keeps them last in the finalized order, so a user's own
   copy of the same extension loads first."
  [acc items]
  (doseq [item items]
    (add-resource! acc (:resource-type item) item)))

(defn- discover-package-dir
  "pi collectPackageResources — resource items of a local package
   directory. Returns {type [absolute item paths]}: the conventional
   extensions/ skills/ prompts/ themes/ subdirectories when any exist,
   otherwise the directory itself as an extension container. A directory
   with an extension.edn manifest is one extension and is handled by the
   caller."
  [root]
  (let [subdirs (into {}
                      (for [t resource-types
                            :let [d (str (io/file root (name t)))]
                            :when (fs/directory? d)]
                        [t d]))]
    (if (seq subdirs)
      (into {} (for [[t d] subdirs] [t (discover-items-in-dir t d)]))
      {:extensions (extensions/extension-artifact-paths root)})))

(defn- enabled-paths
  "pi applyPackageFilter/collectDefaultResources — PATTERNS nil = every
   PATH; an empty array disables all of the type; otherwise pi's 4-step
   pattern application relative to BASE-DIR (the package root). Returns the
   enabled path set."
  [paths patterns base-dir]
  (cond
    (nil? patterns) (set paths)
    (empty? patterns) #{}
    :else (apply-patterns paths patterns base-dir)))

(defn- resolve-local-entry
  "Resolve one local package entry into items (pi:
   resolveLocalExtensionSource + collectPackageResources). BASE is the
   resolved package path; missing paths are skipped."
  [acc base entry scope source-string]
  (let [base (str base)
        root-file? (fs/regular-file? base)
        ext-dir? (fs/exists? (io/file base "extension.edn"))
        metadata {:source source-string
                  :scope scope
                  :origin :package
                  :single-extension (or root-file? ext-dir?)
                  :base-dir (if root-file? (str (fs/parent base)) base)}
        filter (entry-filter entry)
        delta? (autoload-disabled? entry)]
    (cond
      (or root-file? ext-dir?)
      ;; a file (or an extension.edn directory) is a single extension —
      ;; filters don't apply (pi: file sources bypass package filters; kmet
      ;; extension dirs are the single-extension unit)
      (add-resource! acc :extensions
                     (->PackageItem base true :extensions metadata))

      :else
      (let [discovered (discover-package-dir base)]
        (doseq [[type paths] discovered]
          (let [patterns (get filter type)
                ;; pi: filtered-out items stay in the resolution with
                ;; :enabled false (the config list shows them unchecked);
                ;; delta entries contribute only the paths their patterns
                ;; mention. Iterate PATHS (discovery order) — the state maps
                ;; are not insertion-ordered once they exceed 8 entries.
                states (if delta?
                         (apply-autoload-disabled-patterns paths patterns base)
                         (let [enabled (enabled-paths paths patterns base)]
                           (into {} (map (fn [p] [p (contains? enabled p)])) paths)))]
            (doseq [path paths
                    :when (contains? states path)]
              (add-resource! acc type
                             (->PackageItem path (get states path) type metadata)))))))))

(defn- top-level-metadata
  "Item metadata for top-level settings resources (pi: PathMetadata with
   origin \"top-level\"): SOURCE is \"local\" (explicit settings entry)
   or \"auto\" (auto-dir scan); BASE-DIR is the scope root the entry
   patterns resolve against."
  [source scope base-dir]
  {:scope scope :origin :top-level :source source :base-dir base-dir})

(defn- resolve-top-level-layer
  "pi resolve (the top-level part), in pi's insertion order: project
   settings entries, user settings entries, then auto-dir scans (project
   before user). The precedence sort happens once in finalize-items.
   PROJECT-SCOPE? false is pi's global config view (untrusted settings
   manager): project entries and project auto dirs are skipped entirely."
  [acc user-settings project-settings agent-dir project-scope?]
  (let [user-settings (or user-settings {})
        project-settings (or project-settings {})
        project-base (cfg/project-dir)
        user-base (or agent-dir (cfg/get-agent-dir))]
    (when project-scope?
      (doseq [type resource-types]
        (resolve-local-entries acc type (get project-settings type)
                               (top-level-metadata "local" :project project-base)
                               project-base)))
    (doseq [type resource-types]
      (resolve-local-entries acc type (get user-settings type)
                             (top-level-metadata "local" :user user-base)
                             user-base))
    ;; Scope is decided by exact root equality, not path prefix: the agent
    ;; dir can live inside the project root (cwd = $HOME puts the agent at
    ;; $HOME/.kmet/agent under the project root $HOME/.kmet), and a prefix
    ;; test would mislabel every user auto root as project scope.
    (doseq [project? (if project-scope? [true false] [false])
            type resource-types
            :let [scope-base (if project? project-base user-base)
                  root (str (fs/path scope-base (name type)))]
            d (cfg/auto-resource-dirs type agent-dir)
            :when (= (str d) root)]
      (resolve-auto-dir acc type d
                        (top-level-metadata "auto" (if project? :project :user)
                                            scope-base)
                        scope-base
                        (get (if project? project-settings user-settings) type)))))

(declare bundled-items)

(defn resolve-package-items
  "Resolve USER-SETTINGS and PROJECT-SETTINGS (settings maps or nil) into
   per-type PackageItem vectors (pi: resolve — top-level settings entries,
   auto-dir scans, then packages, then the bundled layer). Insertion order
   is pi precedence (project-local > project-auto > user-local > user-auto
   > packages > bundled); first-wins per canonical path. AGENT-DIR pins the
   user scope root (KMET_CODING_AGENT_DIR sandboxing); nil resolves via
   cfg/get-agent-dir. PROJECT-SCOPE? false resolves the user scope only —
   pi's global config view (SettingsManager untrusted: no project settings,
   no project auto scans). BUNDLED is the optional fifth-layer item vector
   (see bundled-items) — only the 5-arity includes it, so callers that want
   the shipped extensions pass it explicitly (resolve-configured-packages
   and the config screen do)."
  ([user-settings project-settings]
   (resolve-package-items user-settings project-settings nil true []))
  ([user-settings project-settings agent-dir]
   (resolve-package-items user-settings project-settings agent-dir true []))
  ([user-settings project-settings agent-dir project-scope?]
   (resolve-package-items user-settings project-settings agent-dir project-scope? []))
  ([user-settings project-settings agent-dir project-scope? bundled]
   (let [user-settings (or user-settings {})
         ;; PROJECT-SCOPE? false ignores project settings outright — the
         ;; caller may still pass the file map (read-views does)
         project-settings (if project-scope? (or project-settings {}) {})
         entries (concat (for [entry (packages-of project-settings)]
                           {:entry entry :scope :project})
                         (for [entry (packages-of user-settings)]
                           {:entry entry :scope :user}))
         acc (atom (into {} (for [type resource-types]
                              [type {:order [] :items {}}])))]
     (resolve-top-level-layer acc user-settings project-settings agent-dir project-scope?)
     (doseq [{:keys [entry scope]} (dedupe-entries entries)
             :let [source (source-of entry)
                   parsed (parse-source source)]]
       (if (= :remote (:kind parsed))
         (binding [*out* *err*]
           (println "Warning: package" source "is a remote source; kmet supports"
                    "local directories and files only — skipping"))
         (let [delta? (and (= scope :project) (autoload-disabled? entry))
               ;; pi findAutoloadDeltaBase: a delta entry resolves its path
               ;; from the user entry of the same identity; without one it
               ;; resolves against its own scope like any other entry
               delta-base (when delta?
                            (some (fn [{:keys [entry scope]}]
                                    (when (and (= scope :user)
                                               (= (package-identity (source-of entry) :user)
                                                  (package-identity source :project)))
                                      (source-of entry)))
                                  entries))
               resolved (resolve-source-path (or delta-base source)
                                             (if delta-base :user scope))]
           (when (fs/exists? resolved)
             (resolve-local-entry acc resolved entry scope source)))))
     (resolve-bundled-layer acc bundled)
     (into {} (for [[type {:keys [order items]}] @acc]
                [type (finalize-items {:order order :items items})])))))

(defn resolve-configured-packages
  "Resolve the configured packages read from both settings files (the
   merged user + project view, pi: the project-trusted resolve). Now the
   unified resolution — top-level entries, auto-dir scans and the bundled
   layer included — so the loaders below see every layer; AGENT-DIR pins
   the user scope root (KMET_CODING_AGENT_DIR sandboxing)."
  ([] (resolve-configured-packages nil))
  ([agent-dir]
   (let [user (user-settings-map)
         project (project-settings-map)]
     (resolve-package-items user project agent-dir true
                            (bundled-items user project)))))

;; ─── Loading (pi: resource-loader — the unified resolution) ───────────────
;; Every layer (top-level entries, auto dirs, packages, bundled) resolves
;; into one ordered view, so loading is a single enabled-path pass per type
;; — no separate auto/package phases and no cross-phase dedupe. Registries
;; still dedupe by identity (extension path, skill name, ...) so a second
;; load call (print mode, /reload) only adds what is new.

(defn- enabled-item-paths
  "Enabled item paths of TYPE from a resolved view."
  [resolved type]
  (->> (get resolved type)
       (filter :enabled)
       (mapv :path)))

(defn load-extensions!
  "Load every enabled extension in the unified resolution (top-level
   entries, auto dirs, packages, bundled — pi precedence, first-wins per
   canonical path). Bundled items load through their descriptor (a checkout
   path or a resource prefix), everything else through its path. Returns
   the per-extension results of the new loads ({:extension name :error}
   maps, failures also warn — same shape as load-extensions-from-dir).
   AGENT-DIR pins the user scope root."
  ([] (load-extensions! nil))
  ([agent-dir]
   (let [loaded (set (map (comp canonicalize :path) (extensions/registered-extensions)))
         fresh (remove #(contains? loaded (canonicalize (:path %)))
                       (filter :enabled
                               (get (resolve-configured-packages agent-dir) :extensions)))
         ;; bundled items carry their descriptor in metadata and load
         ;; through it (checkout paths / resource prefixes); everything
         ;; else keeps the plain path load
         descriptors (filterv #(get-in % [:metadata :artifact]) fresh)
         paths (mapv :path (remove #(get-in % [:metadata :artifact]) fresh))]
     (into (extensions/load-extension-paths! paths)
           (extensions/load-extension-descriptors!
            (mapv #(get-in % [:metadata :artifact]) descriptors))))))

(defn load-skills!
  "Load every enabled skill in the unified resolution (first-wins by name
   in the registry). AGENT-DIR pins the user scope root."
  ([] (load-skills! nil))
  ([agent-dir]
   (let [loaded (into #{} (keep :file-path) (skills/get-skills))
         paths (remove #(contains? loaded %) (enabled-item-paths (resolve-configured-packages agent-dir) :skills))]
     (skills/load-skills-from-files! (vec paths)))))

(defn load-prompts!
  "Load every enabled prompt template in the unified resolution.
   AGENT-DIR pins the user scope root."
  ([] (load-prompts! nil))
  ([agent-dir]
   (let [loaded (into #{} (keep :file-path) (prompts/get-prompt-templates))
         paths (remove #(contains? loaded %) (enabled-item-paths (resolve-configured-packages agent-dir) :prompts))]
     (prompts/load-prompt-template-files! (vec paths)))))

(defn load-themes!
  "Register every enabled theme in the unified resolution. AGENT-DIR pins
   the user scope root."
  ([] (load-themes! nil))
  ([agent-dir]
   (let [loaded (into #{} (keep :source-path) (vals (theme/get-all-themes)))
         paths (remove #(contains? loaded %) (enabled-item-paths (resolve-configured-packages agent-dir) :themes))]
     (theme/load-theme-paths! (vec paths)))))

;; ─── Config model (pi: config-selector.ts) ─────────────────────────────────
;; The resource-config TUI (kmet config) toggles resources across all layers
;; (the bundled set first, then packages and top-level — pi buildGroups over
;; the unified resolution). Package toggle writes follow pi exactly:
;; per-type +/- patterns on the package's settings entry, object-entry
;; conversion, and — in project scope — :autoload false delta entries for
;; inherited user packages with an inherit/load/unload tri-state cycle.
;; Top-level items write +/- patterns into the scope's settings resource
;; array instead (pi toggleTopLevelResource / setProjectTopLevelOverride).

(def resource-type-labels
  "pi RESOURCE_TYPE_LABELS."
  {:extensions "Extensions" :skills "Skills" :prompts "Prompts" :themes "Themes"})

(defn item-key
  "pi getResourceItemKey — resourceType + canonical path."
  [item]
  (str (name (:resource-type item)) ":" (canonicalize (:path item))))

(defn pattern-target
  "pi getPatternEntryTarget — strip the !/+/− prefix."
  [entry]
  (let [s (str entry)]
    (if (str/starts-with? s "!")
      (subs s 1)
      (if (str/starts-with? s "+")
        (subs s 1)
        (if (str/starts-with? s "-")
          (subs s 1)
          s)))))

;; ─── Bundled extensions ──────────────────────────
;; The app-provided fifth layer: descriptors from
;; kmet.app.bundled-extensions, PackageItem-shaped here, enabled through
;; the :bundled-extensions settings key. The key shares the resource-array
;; vocabulary (plain/+name enables, -name/!name disables); a project entry
;; is a delta over the global one, so the same tri-state cycle as the
;; other resources applies.

(defn bundled-item?
  "True when ITEM is an app-provided bundled extension (origin :bundled)."
  [item]
  (= :bundled (get-in item [:metadata :origin])))

(defn bundled-item-name
  "The manifest name of bundled ITEM — the identity the settings entries
   target."
  [item]
  (or (get-in item [:metadata :display-name])
      (get-in item [:metadata :artifact :name])))

(defn bundled-entry-state
  "The state ENTRIES (a :bundled-extensions settings vector) assigns to
   NAME: :load for a plain/+ entry, :unload for -/!; :inherit when no entry
   targets it. Later entries win."
  [entries name]
  (reduce (fn [state entry]
            (if (= (pattern-target entry) name)
              (if (or (str/starts-with? (str entry) "-")
                      (str/starts-with? (str entry) "!"))
                :unload
                :load)
              state))
          :inherit
          (or entries [])))

(defn bundled-enabled?
  "Is bundled extension NAME enabled? The global setting's state is the
   base (missing = disabled — the default), a project entry overrides it
   (:load/:unload), and :inherit falls back to the global base. An absent
   project entry is :inherit."
  [name user-settings project-settings]
  (let [base (case (bundled-entry-state (:bundled-extensions user-settings) name)
               :load true
               (:unload :inherit) false)
        proj (bundled-entry-state (:bundled-extensions project-settings) name)]
    (case proj
      :load true
      :unload false
      :inherit base)))

(defn bundled-items
  "PackageItem-shaped items for the bundled extension layer, one per
   descriptor of the current run mode (kmet.app.bundled-extensions/artifacts
   — checkout paths in dev, resource prefixes in a built artifact). Enabled
   per the :bundled-extensions entries of both settings scopes; default
   disabled. AGENT-DIR is accepted for symmetry with the other resolvers
   and is unused (bundled artifacts are app-provided, not scope-rooted)."
  ([user-settings project-settings] (bundled-items user-settings project-settings nil))
  ([user-settings project-settings _agent-dir]
   (mapv (fn [d]
           (->PackageItem (:path d)
                          (bundled-enabled? (:name d)
                                            (or user-settings {})
                                            (or project-settings {}))
                          :extensions
                          {:origin :bundled
                           :scope :user
                           :source "bundled"
                           :base-dir (or (:root d) (:prefix d) (:path d))
                           :display-name (:name d)
                           :artifact d}))
         (bundled-extensions/artifacts))))

(defn apply-bundled-toggle!
  "Global-scope toggle of bundled ITEM: rewrite the global
   :bundled-extensions vector in place — ENABLED writes the plain name,
   disabled writes -name, replacing any entry targeting the name."
  [item enabled]
  (let [name (bundled-item-name item)
        current (vec (or (:bundled-extensions (user-settings-map)) []))
        filtered (filterv #(not= (pattern-target %) name) current)
        updated (conj filtered (str (if enabled "" "-") name))]
    (cfg/save-setting! [:bundled-extensions] updated)
    true))

(defn bundled-override-state-of
  "The project override state of bundled ITEM from the live project
   settings (:inherit when no entry targets its name)."
  [item]
  (bundled-entry-state (:bundled-extensions (project-settings-map))
                       (bundled-item-name item)))

(defn apply-bundled-project-override!
  "Project-scope override of bundled ITEM: set STATE (:load → +name,
   :unload → -name, :inherit → remove) in the project :bundled-extensions
   vector, replacing any entry targeting the name. An empty vector is
   written rather than deleting the key — read-equivalent to absent
   (:inherit)."
  [item state]
  (let [name (bundled-item-name item)
        current (vec (or (:bundled-extensions (project-settings-map)) []))
        filtered (filterv #(not= (pattern-target %) name) current)
        updated (if (= state :inherit)
                  filtered
                  (conj filtered (str (if (= state :load) "+" "-") name)))]
    (cfg/save-project-setting! [:bundled-extensions] updated)
    true))

(defn item-pattern
  "pi getPackageResourcePattern — the item's path relative to its package
   base dir (the string the toggle writes as a +/- pattern)."
  [item]
  (let [base (get-in item [:metadata :base-dir])]
    (if base
      (posix (fs/relativize (fs/path base) (fs/path (:path item))))
      (posix (:path item)))))

(defn single-extension-item?
  "True when ITEM is the single-extension unit of its package source — a
   file or an extension.edn directory. Such packages ignore per-type
   filters (pi: file sources bypass package filters), so they cannot be
   toggled off: the config screen marks them “always loaded”."
  [item]
  (boolean (get-in item [:metadata :single-extension])))

(defn next-override-state
  "pi getNextOverrideState — the tri-state cycle (inherit → load/unload →
   inherit...) driven by the inherited (global) enabled state."
  [state inherited-enabled]
  (cond
    (= state :inherit) (if inherited-enabled :unload :load)
    (= state :unload) (if inherited-enabled :load :inherit)
    :else (if inherited-enabled :inherit :unload)))

(defn type-array-of
  "The filter array of TYPE on a package ENTRY (pi: pkg[type] ?? [])."
  [entry type]
  (if (map? entry) (vec (or (get entry type) [])) []))

(defn override-state-from-array
  "pi getOverrideStateFromEntries for one package: :inherit when no entry
   targets PATTERN, :load for +/plain entries, :unload for −/! entries; an
   empty array reads as :unload (the whole type is disabled)."
  [type-array pattern empty-array-unload?]
  (if (and (empty? type-array) empty-array-unload?)
    :unload
    (reduce (fn [state entry]
              (if (= (pattern-target entry) pattern)
                (if (or (str/starts-with? (str entry) "-")
                        (str/starts-with? (str entry) "!"))
                  :unload
                  :load)
                state))
            :inherit
            type-array)))

(defn- source-matches-scope
  "pi packageSourceStringMatches — string equality, or two local sources
   whose resolved paths (at their own scope bases) are equal."
  [a a-scope b b-scope]
  (or (= (str a) (str b))
      (and (local-source? a) (local-source? b)
           (= (resolve-source-path a a-scope)
              (resolve-source-path b b-scope)))))

(defn override-state-of
  "pi getProjectOverrideState — the project-scope override state of ITEM
   given the project PACKAGES entries."
  [item packages]
  (let [entry (some (fn [e]
                      (when (source-matches-scope (source-of e) :project
                                                  (get-in item [:metadata :source])
                                                  (get-in item [:metadata :scope]))
                        e))
                    packages)]
    (if-not (map? entry)
      :inherit
      (let [array (type-array-of entry (:resource-type item))]
        (override-state-from-array array (item-pattern item)
                                   (not (false? (:autoload entry))))))))

(defn- set-type-array!
  "pi per-type filter surgery: PACKAGES with entry at IDX gets PATTERN
   added/removed for TYPE. STATE :inherit removes the item's pattern
   entries; :load appends +pattern; :unload appends −pattern. A type array
   emptied by the surgery drops its key; an entry with no type keys left
   returns to its plain source string — unless it is an :autoload false
   delta, which is dropped entirely (pi: setProjectPackageOverride cleanup).
   Returns the next packages vector."
  [packages idx type pattern state]
  (let [entry (nth packages idx)
        entry (if (map? entry) entry {:source entry})
        current (vec (or (get entry type) []))
        target (pattern-target pattern)
        filtered (vec (remove #(= (pattern-target %) target) current))
        with-sign (cond-> filtered
                    (not= state :inherit)
                    (conj (str (if (= state :load) "+" "-") pattern)))
        entry (cond-> entry
                (seq with-sign) (assoc type with-sign)
                (empty? with-sign) (dissoc entry type))
        type-keys (filter #(contains? resource-type-set %) (keys entry))]
    (cond
      (seq type-keys)
      (assoc packages idx entry)

      (and (map? (nth packages idx)) (false? (:autoload entry)))
      (vec (concat (subvec packages 0 idx) (subvec packages (inc idx))))

      :else
      (assoc packages idx (:source entry)))))

(defn top-level-item?
  "True when ITEM comes from a top-level settings entry or an auto-dir
   scan (pi: metadata.origin === \"top-level\"), as opposed to a package."
  [item]
  (= :top-level (get-in item [:metadata :origin])))

(defn top-level-base-dir
  "pi getTopLevelBaseDir — the scope root top-level patterns of SCOPE
   resolve against: the agent dir for user items, the .kmet project dir
   for project items."
  [scope]
  (if (= scope :project) (cfg/project-dir) (cfg/get-agent-dir)))

(defn top-level-pattern
  "pi getResourcePatternForScope (same-scope case) — ITEM's path relative
   to its scope root (its metadata base-dir, falling back to the scope
   root when absent). relative-posix mirrors pi's path.relative (a dot for
   the base itself, the absolute path for unrelated roots — Windows
   drives)."
  [item]
  (let [base (or (get-in item [:metadata :base-dir])
                 (top-level-base-dir (get-in item [:metadata :scope] :user)))]
    (relative-posix base (:path item))))

(defn top-level-override-patterns
  "pi getTopLevelOverridePatterns — every pattern form that can target
   ITEM in SCOPE: relative to the scope root, the absolute path, and
   relative to the item's own base dir. Built with set — the forms can
   evaluate to the same string (a project item's base dir IS the project
   scope root), and a set literal with duplicate evaluated elements throws
   on babashka/SCI."
  [item scope]
  (let [base (top-level-base-dir scope)
        patterns (set [(top-level-pattern item)
                       (posix (:path item))
                       (relative-posix base (:path item))])]
    (cond-> patterns
      (get-in item [:metadata :base-dir])
      (conj (relative-posix (get-in item [:metadata :base-dir]) (:path item))))))

(defn- top-level-array-of
  "The settings resource array of TYPE in scope SCOPE's settings map."
  [type scope]
  (vec (or (get (if (= scope :project) (project-settings-map) (user-settings-map)) type) [])))

(defn apply-top-level-toggle!
  "pi toggleTopLevelResource — flip ITEM's enabled state by writing
   +/−PATTERN (relative to the scope root) into the scope's settings
   resource array for the item's type. Existing entries targeting the same
   pattern are replaced. Always true (the array always exists)."
  [item enabled]
  (let [scope (get-in item [:metadata :scope] :user)
        type (:resource-type item)
        pattern (top-level-pattern item)
        current (top-level-array-of type scope)
        filtered (filterv #(not= (pattern-target %) pattern) current)
        updated (conj filtered (str (if enabled "+" "-") pattern))
        save! (if (= scope :project) cfg/save-project-setting! cfg/save-setting!)]
    (save! [type] updated)
    true))

(defn top-level-override-state-of
  "pi getProjectOverrideState (top-level branch) — ITEM's project override
   state from the project settings resource array."
  [item]
  (let [array (top-level-array-of (:resource-type item) :project)
        patterns (top-level-override-patterns item :project)]
    (reduce (fn [state entry]
              (if (contains? patterns (pattern-target entry))
                (if (or (str/starts-with? (str entry) "-")
                        (str/starts-with? (str entry) "!"))
                  :unload
                  :load)
                state))
            :inherit
            array)))

(defn apply-project-top-level-override!
  "pi setProjectTopLevelOverride — set ITEM's project override STATE
   (:inherit/:load/:unload) in the project settings resource array. An
   inherited user item writes its absolute path plus the +/- pattern;
   :inherit removes the override entries again. Always true."
  [item state]
  (let [type (:resource-type item)
        inherited? (= :user (get-in item [:metadata :scope] :user))
        pattern (if inherited? (posix (:path item)) (top-level-pattern item))
        patterns (top-level-override-patterns item :project)
        current (top-level-array-of type :project)
        override? #(or (str/starts-with? (str %) "!")
                       (str/starts-with? (str %) "+")
                       (str/starts-with? (str %) "-"))
        filtered (filterv (fn [entry]
                            (let [target (pattern-target entry)]
                              (not (or (and (override? entry) (contains? patterns target))
                                       (and (= state :inherit) inherited? (= target pattern))))))
                          current)
        updated (if (not= state :inherit)
                  (let [with-plain (if (and inherited? (not (some #(= % pattern) filtered)))
                                     (conj filtered pattern)
                                     filtered)]
                    (conj with-plain (str (if (= state :load) "+" "-") pattern)))
                  filtered)]
    (cfg/save-project-setting! [type] updated)
    true))

(defn apply-global-toggle!
  "pi toggleResource (global branch) — flip ITEM's enabled state. Package
   items write +/−PATTERN into the matching package entry (string entries
   become object entries; entries with no remaining filters return to
   strings); top-level items write into the scope's settings resource
   array; bundled items are not :packages entries and write nothing here
   (the config screen's bundled branch owns their settings key). No-op when
   the package is not found, and false for single-extension sources —
   their filters are ignored at resolve time, so a pattern write would
   silently do nothing."
  [item enabled]
  (if (bundled-item? item)
    false
    (if (top-level-item? item)
      (apply-top-level-toggle! item enabled)
      (if (single-extension-item? item)
        false
        (let [current (user-packages)
              source (get-in item [:metadata :source])
              idx (first (keep-indexed (fn [i e]
                                         (when (= (source-of e) source) i))
                                       current))]
          (when idx
            (cfg/save-setting!
             [:packages]
             (set-type-array! current idx (:resource-type item) (item-pattern item)
                              (if enabled :load :unload)))
            true))))))

(defn- override-source-entry
  "pi createPackageOverrideSource — a project-scope delta entry over the
   user package ITEM belongs to: the same local source normalized against
   the project base dir, marked :autoload false."
  [item]
  (let [source (get-in item [:metadata :source])
        item-scope (get-in item [:metadata :scope] :user)
        resolved (resolve-source-path source item-scope)]
    {:source (relative-posix (base-dir-for-scope :project) resolved)
     :autoload false}))

(defn apply-project-override!
  "pi setProjectResourceOverride — set ITEM's project override STATE
   (:inherit/:load/:unload) in the project settings file. Top-level items
   write into the project settings resource array; package items write
   +/−patterns into the project :packages entry (inherited user packages
   without a project entry get a fresh :autoload false delta entry;
   cycling back to :inherit removes the override again); bundled items are
   not :packages entries and write nothing here (the config screen's
   bundled branch owns their settings key). False for single-extension
   sources (their filters are ignored at resolve time)."
  [item state]
  (if (bundled-item? item)
    false
    (if (top-level-item? item)
      (apply-project-top-level-override! item state)
      (if (single-extension-item? item)
        false
        (when (not= state (override-state-of item (project-packages)))
          (let [current (project-packages)
                item-scope (get-in item [:metadata :scope] :user)
                idx (first (keep-indexed (fn [i e]
                                           (when (source-matches-scope (source-of e) :project
                                                                       (get-in item [:metadata :source])
                                                                       item-scope)
                                             i))
                                         current))]
            (if (and (nil? idx) (= state :inherit))
              false
              (let [packages (if idx
                               current
                               (conj current (override-source-entry item)))
                    idx (or idx (dec (count packages)))]
                (cfg/save-project-setting! [:packages]
                                           (set-type-array! packages idx
                                                            (:resource-type item)
                                                            (item-pattern item)
                                                            state))
                true))))))))

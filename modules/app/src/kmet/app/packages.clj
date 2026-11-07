(ns kmet.app.packages
  "Package manager + resource resolution (pi: core/package-manager.ts + the
   package part of core/resource-loader.ts — local directory/file sources
   plus managed git checkouts; npm installs are deliberately out of scope,
   see src/kmet/pi-alignment.md.

   A fifth, app-provided layer sits after packages: the bundled extensions
   (kmet.app.extensions.bundled) — the shipped `extensions/` set, present in
   every run mode and available to the composition. Bundled items rank last
   (5), are not `:packages` entries (kmet install/remove cannot touch them),
   and load through their descriptor (a checkout path in dev, a resource
   prefix in a built artifact) instead of a plain path.

   Extensions are plugins: this namespace discovers the installed set, and
   only a composition's `:ext/<id>` rows load an extension
   (kmet.app.extensions.rows). Skills, prompts and themes keep the
   enabled-path loaders below.

   `resolve-package-items` is pi's resolve pipeline: top-level settings
   resource entries (`:extensions`/`:skills`/`:prompts`/`:themes` — plain
   paths expand; signed/glob entries are ignored), the fixed auto-dir scans
   (agent dir + .kmet), then packages (whose per-type filters use
   `!glob`/`+path`/`-path`), accumulated in pi's insertion order and
   finalized with pi's precedence sort (project-local 0, project-auto 1,
   user-local 2, user-auto 3, package 4, bundled 5) + canonical-path
   dedupe. `load-skills!`/`load-prompts!`/`load-themes!` load the enabled
   items of a type; extensions load through composition rows.

   A package is a `:packages` entry in the global settings.edn
   (~/.kmet/agent/settings.edn, scope :user) or the project settings.edn
   (.kmet/settings.edn, scope :project). Entries are source strings or maps

     {:source \"../ext\"          ;; source (pi: PackageSource)
      :autoload false            ;; project delta over the user entry
      :extensions [\"+x.clj\"]     ;; per-type filter patterns}

   Sources are local files or directories, or git repositories installed as
   managed checkouts (kmet.app.git-packages; a checkout lives under the
   scope's git/ root, and a `git:…//subdir` source is one package directory
   inside it). A file loads as a single
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
            [kmet.app.extensions.bundled :as bundled-extensions]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.git-packages :as git]
            [kmet.app.prompts :as prompts]
            [kmet.app.resources :as app-resources]
            [kmet.app.skills :as skills]
            [kmet.app.config :as cfg]))

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
  "Parse a source string (pi: parseSource). Returns {:kind :local :path
   PATH}, {:kind :git :spec S :git <parsed git source>}, or
   {:kind :remote :prefix P :spec S} for the sources kmet cannot install
   (npm among them)."
  [source]
  (let [s (str/trim (str source))]
    (cond
      (str/starts-with? s "npm:") {:kind :remote :prefix "npm:" :spec s}
      (local-source? s) {:kind :local :path s}
      :else
      (if-let [parsed (git/parse-git-url s)]
        {:kind :git :spec s :git parsed}
        (if-let [p (some #(when (str/starts-with? s %) %) remote-prefixes)]
          {:kind :remote :prefix p :spec s}
          {:kind :local :path s})))))

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
   packages are their resolved absolute path, git packages host/path (every
   URL form and ref of one repository matches), other remote ones their
   spec."
  [source scope]
  (let [parsed (parse-source source)]
    (case (:kind parsed)
      :local (str "local:" (resolve-path (:path parsed) (base-dir-for-scope scope)))
      :git (str "remote:" (git/git-identity (:git parsed)))
      (str "remote:" (:spec parsed)))))

(defn normalize-source-for-settings
  "The form a SOURCE takes in the settings file of SCOPE: a remote source
   (git among them) is stored verbatim (pi), a local path relative to the
   scope base dir when possible — otherwise a relative `../..` path, never
   the input verbatim (pi: normalizePackageSourceForSettings —
   relative(baseDir, resolve(source)))."
  [source scope]
  (let [parsed (parse-source source)]
    (if (= :local (:kind parsed))
      (relative-posix (base-dir-for-scope scope)
                      (resolve-path (:path parsed) (str (fs/cwd))))
      (str source))))

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
  (let [parsed (parse-source input-source)
        existing (parse-source (str existing-source))]
    (case (:kind parsed)
      ;; git sources match by repository identity (pi), so SSH/HTTPS/
      ;; shorthand forms and a ref change update the same settings entry
      :git (and (= :git (:kind existing))
                (= (git/git-identity (:git parsed))
                   (git/git-identity (:git existing))))
      :remote (= (str existing-source) (:spec parsed))
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

(defn git-install-root
  "The managed git checkout root of SCOPE (pi: getGitInstallRoot): the
   scope base dir's git/ subdirectory — ~/.kmet/agent/git for user packages,
   .kmet/git for project packages."
  [scope]
  (str (fs/path (base-dir-for-scope scope) "git")))

(defn get-installed-path
  "The resolved path of SOURCE in SCOPE when it exists on disk, else nil
   (pi: getInstalledPath). For a git source that is its managed checkout."
  [source scope]
  (let [parsed (parse-source source)]
    (case (:kind parsed)
      :local (let [p (resolve-source-path (:path parsed) scope)]
               (when (fs/exists? p) p))
      :git (let [p (git/package-dir (:git parsed) (git-install-root scope))]
             (when (fs/exists? p) p))
      nil)))

(defn- package-root
  "The directory a parsed package SOURCE resolves to for SCOPE: a local
   path resolved against the scope base dir, or the package directory of a
   git source (its checkout, or checkout/subdir for `//subdir`). nil for
   sources kmet cannot install (npm)."
  [parsed scope]
  (case (:kind parsed)
    :local (resolve-source-path (:path parsed) scope)
    :git (git/package-dir (:git parsed) (git-install-root scope))
    nil))

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
    :themes (vec (or (when (fs/directory? d)
                       (->> (fs/list-dir d)
                            (filter #(and (fs/regular-file? %)
                                          (str/ends-with? (fs/file-name %) ".edn")))
                            (map str)
                            vec))
                     []))))

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

(defn- resolve-entries
  "Resolve ENTRIES of TYPE into enabled items using METADATA's scope root
   as BASE-DIR: a file contributes itself, a directory its discovered
   items. Callers pass top-level settings paths or an auto-dir root.
   Signed/glob entries are ignored — selection is the `:resources`
   service, not the path list."
  [acc type entries {:keys [base-dir] :as metadata}]
  (doseq [entry entries
          :let [path (resolve-path entry base-dir)]
          item (if (fs/directory? path)
                 (discover-items-in-dir type path)
                 (when (fs/regular-file? path) [path]))]
    (add-resource! acc type (->PackageItem item true type metadata))))

(defn- resolve-bundled-layer
  "pi addAutoDiscoveredResources' trailing analogue — the bundled
   extensions appended after every other layer. ITEMS are already
   PackageItem-shaped (bundled-items); rank 5 (see
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
   or \"auto\" (auto-dir scan); BASE-DIR is the scope root the entry paths
   resolve against."
  [source scope base-dir]
  {:scope scope :origin :top-level :source source :base-dir base-dir})

(defn- resolve-top-level-layer
  "pi resolve (the top-level part), in pi's insertion order: project
   settings entries, user settings entries, then auto-dir scans (project
   before user). The precedence sort happens once in finalize-items.
   PROJECT-SCOPE? false is pi's global config view (untrusted settings
   manager): project entries and project auto dirs are skipped entirely."
  [acc user-settings project-settings agent-dir project-scope?]
  (let [project-base (cfg/project-dir)
        user-base (or agent-dir (cfg/get-agent-dir))]
    (when project-scope?
      (doseq [type resource-types]
        (resolve-entries acc type (get project-settings type)
                         (top-level-metadata "local" :project project-base))))
    (doseq [type resource-types]
      (resolve-entries acc type (get user-settings type)
                       (top-level-metadata "local" :user user-base)))
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
      (resolve-entries acc type [d]
                       (top-level-metadata "auto" (if project? :project :user)
                                           scope-base)))))

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
   and the plugin screen do)."
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
           (println "Warning: package" source "is a remote source; kmet installs"
                    "local directories/files and git: repositories — skipping"))
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
               root (package-root (parse-source (or delta-base source))
                                  (if delta-base :user scope))]
           (if (and root (fs/exists? root))
             (resolve-local-entry acc root entry scope source)
             (when (= :git (:kind parsed))
               (binding [*out* *err*]
                 (let [root-scope (if delta-base :user scope)
                       dir (get-in parsed [:git :dir])
                       checkout (when dir
                                  (git/install-path (git-install-root root-scope)
                                                    (:git parsed)))]
                   (if (and dir (some-> checkout fs/exists?))
                     (println "Warning: git package" source "has no"
                              (str "\"" dir "\"") "in its checkout — skipping")
                     (println "Warning: git package" source "is not installed — run"
                              (str "\"kmet install " source "\"")
                              "(or \"kmet update\") to install it; skipping")))))))))
     (resolve-bundled-layer acc bundled)
     (into {} (for [[type {:keys [order items]}] @acc]
                [type (finalize-items {:order order :items items})])))))

(defn resolve-configured-packages
  "Resolve the configured packages read from both settings files (the
   merged user + project view, pi: the project-trusted resolve). Now the
   unified resolution — top-level entries, auto-dir scans and the bundled
   layer included — so the extension-row resolver and the resource loaders
   below see every layer; AGENT-DIR pins the user scope root
   (KMET_CODING_AGENT_DIR sandboxing)."
  ([] (resolve-configured-packages nil))
  ([agent-dir]
   (let [user (user-settings-map)
         project (project-settings-map)]
     (resolve-package-items user project agent-dir true
                            (bundled-items)))))

;; ─── Loading (pi: resource-loader — the unified resolution) ───────────────
;; Every layer (top-level entries, auto dirs, packages, bundled) resolves
;; into one ordered view, so loading is a single enabled-path pass per type
;; — no separate auto/package phases and no cross-phase dedupe. Registries
;; still dedupe by identity (skill name, prompt name, ...) so a second load
;; call (print mode, /reload) only adds what is new. Extensions are not here:
;; a composition row loads them (kmet.app.extensions.rows).

(defn- enabled-item-paths
  "Enabled item paths of TYPE from a resolved view."
  [resolved type]
  (->> (get resolved type)
       (filter :enabled)
       (mapv :path)))

(defn load-skills!
  "Load every enabled skill in the unified resolution into the app's registry
   (first-wins by name). AGENT-DIR pins the user scope root."
  ([] (load-skills! nil))
  ([agent-dir]
   (let [loaded (into #{} (keep :file-path) (skills/get-skills))
         paths (remove #(contains? loaded %) (enabled-item-paths (resolve-configured-packages agent-dir) :skills))]
     (skills/load-skills-from-files! (vec paths)))))

(defn load-prompts!
  "Load every enabled prompt template in the unified resolution into the app's
   registry. AGENT-DIR pins the user scope root."
  ([] (load-prompts! nil))
  ([agent-dir]
   (let [loaded (into #{} (keep :file-path) (prompts/get-prompt-templates))
         paths (remove #(contains? loaded %) (enabled-item-paths (resolve-configured-packages agent-dir) :prompts))]
     (prompts/load-prompt-template-files! (vec paths)))))

(defn load-resources!
  "Every enabled skill and prompt template of the unified resolution into the
   app's registries — what a front end loads once per process, before the first
   prompt is built. A front end passes this as the Engine's `:load-resources!`,
   so the Engine knows nothing about where a process keeps its packages."
  []
  (load-skills!)
  (load-prompts!))

(defn expand-input
  "kmet1's input expansion: an extension-registered skill command or a prompt
   template in TEXT is replaced by what it stands for. RESOURCES is the
   `:resources` service value; when non-nil, a disabled skill or template does
   not expand (nil is a process without the row, which keeps every
   registration). A front end passes this as the Engine's `:expand-input`, so
   the Engine knows the chain's shape but not where a process keeps its skills
   or templates."
  ([text] (expand-input text nil))
  ([text resources]
   (-> text
       (skills/expand-skill-command
        (when resources ((:enabled-names resources) :skill)))
       (prompts/expand-prompt-template
        (app-resources/enabled resources :prompt (prompts/get-prompt-templates))))))

(defn resolve-theme-paths
  "Return every enabled theme path in the unified resolution. AGENT-DIR pins
   the user scope root. Side-effect-free; the caller registers the paths with
   the front end it has."
  ([] (resolve-theme-paths nil))
  ([agent-dir]
   (enabled-item-paths (resolve-configured-packages agent-dir) :themes)))

;; ─── Bundled extensions ──────────────────────────
;; The app-provided fifth layer: descriptors from
;; kmet.app.extensions.bundled, PackageItem-shaped here. Bundled artifacts are
;; always available to the composition; whether a run loads one is decided by
;; its `:ext/…` rows (kmet.app.extensions.rows), not by settings.

(defn bundled-item?
  "True when ITEM is an app-provided bundled extension (origin :bundled)."
  [item]
  (= :bundled (get-in item [:metadata :origin])))

(defn bundled-items
  "PackageItem-shaped items for the bundled extension layer, one per
   descriptor of the current run mode (kmet.app.extensions.bundled/artifacts
   — checkout paths in dev, resource prefixes in a built artifact). Every
   item is available: the composition decides what loads."
  []
  (mapv (fn [d]
          (->PackageItem (:path d)
                         true
                         :extensions
                         {:origin :bundled
                          :scope :user
                          :source "bundled"
                          :base-dir (or (:root d) (:prefix d) (:path d))
                          :display-name (:name d)
                          :artifact d}))
        (bundled-extensions/artifacts)))

(ns kmet.app.skills
  "Skills system for kmet, aligned with pi's implementation (pi: core/skills.js,
   docs/skills.md). A skill is a directory containing SKILL.md with YAML
   frontmatter, or a flat .md file. Skills are discovered recursively,
   validated per the Agent Skills spec, and listed in the system prompt as an
   <available_skills> XML block; the agent reads the SKILL.md file on demand
   via the read tool (progressive disclosure). Also builds the full system
   prompt (pi: core/system-prompt.js buildSystemPrompt).

   Deviations from pi: no .gitignore/.ignore/.fdignore support (kmet scans only
   its own skills dirs); frontmatter parses via kmet.libs.yaml (a minimal YAML
   subset parser — babashka has no YAML lib).

   Skills live in the app root's `:skills` registry, under the `[:app
   :skills]` owner address, keyed by `[:kmet/skill <name>]`: visible to every
   engine, outliving one, and disposed with their owner rather than copied into
   an engine and reset by hand. A name holds one skill — the
   key is the name — so the existing first-wins collision rule is enforced
   before registering. Extension registrations carry the extension's own
   owner path, so unloading it disposes the skill."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.debug :as debug]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as app-resources]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.libs.yaml :as yaml]))

;; ─── Constants (pi: MAX_NAME_LENGTH / MAX_DESCRIPTION_LENGTH) ─────────────

(def ^:private max-name-length 64)
(def ^:private max-description-length 1024)

;; ─── Skill registry ───────────────────────────────────────────────────────

(def skills-owner
  "The app's skill registry owner — the registry's address; unloading it
   takes every skill it contributed."
  [:app :skills])

(defn- registered-skills
  "The tree's skills, in registration order."
  []
  (mapv second (registry/entries (registry/current) :kmet/skill)))

(defn- skill-for
  "The skill named NAME, or nil (see `kmet.app.registry/entry` for the
   owner rule)."
  [name]
  (registry/entry (registry/current) :kmet/skill name))

;; ─── Validation (pi: validateName / validateDescription) ──────────────────

(defn- validate-name
  "Per Agent Skills spec. Returns seq of error messages (empty if valid)."
  [name]
  (cond-> []
    (> (count name) max-name-length)
    (conj (str "name exceeds " max-name-length " characters (" (count name) ")"))
    (not (re-matches #"[a-z0-9-]+" name))
    (conj "name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)")
    (str/starts-with? name "-")
    (conj "name must not start or end with a hyphen")
    (str/ends-with? name "-")
    (conj "name must not start or end with a hyphen")
    (str/includes? name "--")
    (conj "name must not contain consecutive hyphens")))

(defn- validate-description
  "Per Agent Skills spec. Returns seq of error messages (empty if valid)."
  [description]
  (cond-> []
    (> (count description) max-description-length)
    (conj (str "description exceeds " max-description-length " characters ("
               (count description) ")"))))

;; ─── Loading (pi: loadSkillFromFile / loadSkillsFromDirInternal) ──────────

(defn- parse-skill-content
  "Parse/validate SKILL.md content RAW into a skill map. FALLBACK-NAME is
   used when frontmatter has no name (file skills: the parent dir name — pi:
   frontmatter.name || parentDirName). LOCATION is the display locator
   (a file path, or an `ext-name:relative/path` locator for extension
   skills). EXTENSION is the owning extension name, or nil. Skills with a
   missing description are not loaded. Returns {:skill map-or-nil
   :diagnostics [warning-maps]}."
  [raw fallback-name location extension]
  (try
    (let [{:keys [frontmatter body]} (yaml/parse-frontmatter raw)
          name (str (or (get frontmatter "name") fallback-name))
          description (some-> (get frontmatter "description") str str/trim)
          errors (concat (if (str/blank? description)
                           ["description is required"]
                           (validate-description description))
                         (validate-name name))
          diagnostics (mapv #(hash-map :type "warning" :message % :path (str location)) errors)]
      (if (str/blank? description)
        {:skill nil :diagnostics diagnostics}
        {:skill {:name name
                 :description description
                 :body body
                 :raw raw
                 :location (str location)
                 :extension extension
                 :file-path (when (nil? extension) (str location))
                 :base-dir (when (nil? extension)
                             (str (fs/parent (str location))))
                 :disable-model-invocation (true? (get frontmatter "disable-model-invocation"))}
         :diagnostics diagnostics}))
    (catch Exception e
      {:skill nil
       :diagnostics [{:type "warning" :message (ex-message e) :path (str location)}]})))

(defn register-skill!
  "Register a programmatic skill (no backing file, so the model cannot read it
   on demand — pi has no equivalent; kept for kmet's API and tests) on NODE
   (the app root for an app-wide registration, a child node a run passes for
   its own tree).
   Returns the registration's deregister fn."
  [name description]
  (registry/register! (registry/current)
                      :kmet/skill name
                      {:name name
                       :description description
                       :body nil
                       :raw nil
                       :location nil
                       :extension nil
                       :file-path nil
                       :base-dir nil
                       :disable-model-invocation false}
                      skills-owner))

(defn- load-skill-from-file
  "Load a skill from a SKILL.md (or flat .md) file. Returns {:skill
   map-or-nil :diagnostics [warning-maps]}."
  [file-path]
  (try
    (parse-skill-content (slurp file-path)
                         (fs/file-name (fs/parent file-path))
                         (str file-path)
                         nil)
    (catch Exception e
      {:skill nil
       :diagnostics [{:type "warning" :message (ex-message e) :path (str file-path)}]})))

(defn- skill-files-in-dir
  "pi: loadSkillsFromDirInternal discovery walk — a dir containing SKILL.md
   is a skill root (load it, do not recurse); otherwise direct .md children
   and then subdirectories (recursion). Hidden entries and node_modules are
   skipped. Returns the ordered skill file paths."
  [dir]
  (let [;; sorted by file name: `add-skill!` keeps the first registration of a
        ;; name, and an unsorted `list-dir` would make which file is "first" a
        ;; property of the filesystem's directory hashing
        entries (sort-by fs/file-name
                         (try (fs/list-dir dir) (catch Exception _ [])))
        skill-md (some #(when (= "SKILL.md" (fs/file-name %)) %) entries)]
    (if skill-md
      [(str skill-md)]
      (concat (map str
                   (filter #(and (fs/regular-file? %)
                                 (str/ends-with? (fs/file-name %) ".md")
                                 (not (str/starts-with? (fs/file-name %) ".")))
                           entries))
              (mapcat skill-files-in-dir
                      (filter #(and (fs/directory? %)
                                    (not (str/starts-with? (fs/file-name %) "."))
                                    (not= "node_modules" (fs/file-name %)))
                              entries))))))

(defn- load-skills-dir
  "Parse every discovered skill file under DIR (see skill-files-in-dir).
   Returns seq of {:skill ... :diagnostics ...}."
  [dir]
  (mapv load-skill-from-file (skill-files-in-dir dir)))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn- skill-collision
  "The collision diagnostic for SKILL at LOCATION, whose name the already
   registered skill returned by `skill-for` holds (pi: the winner keeps the
   name, the loser is reported)."
  [skill location]
  (let [winner (skill-for (:name skill))]
    {:type "collision"
     :message (str "name \"" (:name skill) "\" collision")
     :path (str location)
     :winner-path (str (or (:location winner) (:file-path winner)))
     :loser-path (str location)}))

(defn- add-skill!
  "Add SKILL to the registry under OWNER, or refuse it in favour of the owner
   that already holds the name (first wins, pi): a name holds one skill, and
   the first owner to register it keeps it. A re-registration under the same
   owner replaces it, which is what makes re-applying a directory (resource
   discovery) or a reload idempotent. Returns {:deregister f} when it was
   registered — f removes exactly this registration — else {:collision
   diagnostic}."
  [owner skill location]
  (let [by-owner (registry/by-owner (registry/current) :kmet/skill (:name skill))]
    (if (and (seq by-owner) (not (contains? by-owner owner)))
      {:collision (skill-collision skill location)}
      {:deregister (registry/register! (registry/current)
                                       :kmet/skill (:name skill) skill owner)})))

(defn- add-skill-results!
  "Register parsed skill results ({:skill ... :diagnostics ...}) under OWNER,
   collecting collision diagnostics, then print all diagnostics to stderr and
   return them. The first skill of a name wins within one batch too (pi's
   discovery walk): a later file of the same scan is reported as a collision
   rather than replacing it."
  [owner results]
  (let [collisions (volatile! [])
        seen (volatile! #{})]
    (doseq [{:keys [skill]} results]
      (when skill
        (let [nm (:name skill)
              duplicate? (contains? @seen nm)]
          (vswap! seen conj nm)
          (if duplicate?
            (vswap! collisions conj (skill-collision skill (:file-path skill)))
            (when-let [collision (:collision (add-skill! owner skill (:file-path skill)))]
              (vswap! collisions conj collision))))))
    (let [diagnostics (into (vec (mapcat :diagnostics results)) @collisions)]
      (doseq [{:keys [type message path]} diagnostics]
        (binding [*out* *err*]
          (println (str "Warning: skill " type " at " path ": " message))))
      diagnostics)))

(defn clear-skills!
  "Remove every loaded skill from NODE (pi: resourceLoader.reload
   re-discovers from scratch). Used by /reload."
  []
  (registry/clear! (registry/current) :kmet/skill))

(defn discover-skill-files
  "Discover skill files under DIR using pi's discovery rules (a dir
   containing SKILL.md is a skill root and is not recursed; otherwise direct
   .md children and subdirectories are walked). Returns the ordered vector
   of skill file paths (SKILL.md files and flat .md files). Used by the
   package resource resolver for per-file enable/disable; load-skills-from-dir
   is the same discovery plus registration."
  [dir]
  (skill-files-in-dir (str dir)))

(defn load-skills-from-dir
  "Load skills from a directory using pi's discovery rules. Skills are added
   to the registry under OWNER (the app's registry by default; an extension or
   a resource contribution passes its own owner path so the skills go with
   it); on name collision another owner's skill wins (pi: keep
   the first, emit a collision diagnostic) while the same owner's is
   replaced. Returns the diagnostics for this dir (warning/collision maps),
   printed to stderr."
  ([dir] (load-skills-from-dir dir skills-owner))
  ([dir owner]
   (let [d (io/file dir)]
     (if-not (fs/directory? d)
       []
       (add-skill-results! (or owner skills-owner) (load-skills-dir (str d)))))))

(defn load-skills-from-files!
  "Load skills from explicit skill file paths (SKILL.md files or flat .md
   files — the package-resource unit, pi: package skills load) under OWNER
   (the app's registry by default). Same registration, collision and warning
   behavior as load-skills-from-dir. Returns the diagnostics."
  ([file-paths] (load-skills-from-files! file-paths skills-owner))
  ([file-paths owner]
   (add-skill-results! (or owner skills-owner)
                       (mapv load-skill-from-file file-paths))))

(defn register-extension-skill!
  "Register a skill from an extension's bundled SKILL.md content string
   (the extension reads its own resource via io/resource and hands the
   content over, so jarred skills need no filesystem path).
   OPTS: :location (display locator, e.g. `my-ext:skills/mcp/SKILL.md`),
   :fallback-name (when frontmatter has no name), :extension (owner name),
   :owner (the owner path the registration belongs to — an extension passes
   `extension-owner`, so unloading it disposes the skill; the app's registry
   by default).
   Same validation/collision rules as dir loading (another owner's skill wins
   the name, the same owner's is replaced). The body is
   served through the read tool under :location (pass it verbatim) as well
   as /skill:name expansion. Returns a
   deregister fn removing exactly this skill."
  [raw-content {:keys [location fallback-name extension owner]}]
  (let [{:keys [skill diagnostics]} (parse-skill-content raw-content
                                                         (or fallback-name
                                                             (str (or extension "skill")))
                                                         (or location
                                                             (str (or extension "skill")))
                                                         extension)]
    (doseq [{:keys [type message path]} diagnostics]
      (binding [*out* *err*]
        (println (str "Warning: skill " type " at " path ": " message))))
    (if skill
      (let [{:keys [collision deregister]} (add-skill! (or owner skills-owner)
                                                       skill location)]
        (when collision
          (binding [*out* *err*]
            (println (str "Warning: skill collision at " (:path collision) ": "
                          (:message collision)))))
        (or deregister (fn [] nil)))
      (fn [] nil))))

(defn skill-content-for-path
  "The SKILL.md text for RAW-PATH when SKILL-LIST holds an extension skill
   with that locator, or nil. Matches the exact <location> (e.g.
   `my-ext:skills/foo/SKILL.md`) and a cwd-joined absolute form (the model
   may join the location onto the working directory). Backslashes normalize
   to `/` first so Windows-joined paths resolve. File skills need no
   handling — they exist on disk."
  [skill-list raw-path]
  (let [p (str/replace (str raw-path) "\\" "/")]
    (some (fn [s]
            (when (and (:extension s) (seq (:location s)))
              (let [loc (str (:location s))]
                (when (or (= p loc)
                          (str/ends-with? p (str "/" loc)))
                  (or (:raw s) (:body s))))))
          skill-list)))

(defn content-resolver
  "A fn serving extension skill bodies from SKILLS, a `:skills` registry handle:
   raw read path → SKILL.md text, or nil.

   The handle is the argument (not the process registry) so a run binds the
   resolver from the agent's own `:skills` service into
   `kmet.app.tools.read/*skill-resolver*` — per run, so two agents may hold
   different skill sets, and no require cycle: read never names this namespace."
  [skills]
  (fn [raw-path]
    (skill-content-for-path
     (mapv second (registry/entries skills :kmet/skill))
     raw-path)))

(defn get-skills
  []
  (registered-skills))

(defn get-skill
  [name]
  (skill-for name))

(defn expand-skill-command
  "Expand /skill:name args into the skill body wrapped in a <skill> block
   (pi: agent-session _expandSkillCommand). Extension skills disclose from
   their in-memory body; file skills re-slurp their SKILL.md. ENABLED-NAMES,
   when non-nil, is the set of skill names the `:resources` selection enables;
   a skill outside it does not expand. Returns text unchanged when not a
   /skill: command, the skill is unknown or not enabled, or the file cannot be
   read. Reading the block back is `kmet.app.skill-block/parse` (the format's
   own namespace, below every front end)."
  ([text] (expand-skill-command text nil))
  ([text enabled-names]
   (if-not (str/starts-with? text "/skill:")
     text
     (let [space-idx (str/index-of text " ")
           skill-name (if (nil? space-idx) (subs text 7) (subs text 7 space-idx))
           args (if (nil? space-idx) "" (str/trim (subs text (inc space-idx))))]
       (if-let [skill (and (or (nil? enabled-names)
                               (contains? enabled-names skill-name))
                           (get-skill skill-name))]
         (try
           (let [raw-body (or (:body skill)
                              (:body (yaml/parse-frontmatter (slurp (:file-path skill)))))
                 body (str/trim (or raw-body ""))
                 location (or (:location skill) (:file-path skill))
                 refs-line (if (:extension skill)
                             "This skill is self-contained; it has no skill directory.\n\n"
                             (str "References are relative to " (:base-dir skill) ".\n\n"))
                 block (str "<skill name=\"" (:name skill) "\" location=\"" location "\">\n"
                            refs-line
                            body "\n</skill>")]
             (if (seq args) (str block "\n\n" args) block))
           (catch Exception e
             (debug/log "skill expansion failed: " e)
             text))
         text)))))

(defn as-command-maps
  "Skills in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode skillCommandList — /skill:name commands, enabled
   by default)."
  [skill-list]
  (mapv (fn [s] {:name (str "skill:" (:name s))
                 :description (:description s)})
        skill-list))

(defn enabled-command-maps
  "The skills the `:resources` selection enables, in slash-command shape. A
   nil RESOURCES (the composition mounted no row) keeps every skill."
  [resources]
  (as-command-maps
   (app-resources/enabled resources :skill (get-skills))))

;; ─── System prompt ────────────────────────────────────────────────────────
;; The builder itself lives in `kmet.app.system-prompt`, where every
;; input is an argument: an engine builds its prompt from the ctx it holds.
;; These two are kmet1's process-bound wrappers — the same names and arities
;; its callers and tests already use, defaulting `:tools` from the process
;; tool table and `:skills` from the process skill registry.

(defn format-skills-for-prompt
  "Format skills as an <available_skills> XML block (see
   `kmet.app.system-prompt/format-skills-for-prompt`)."
  [skill-list]
  (system-prompt/format-skills-for-prompt skill-list))

(defn build-system-prompt
  "Build the system prompt with tools, guidelines, context files, and skills
   (see `kmet.app.system-prompt/build-system-prompt` for the options).

   The process-bound defaults are this wrapper's: `:tools` from the process
   tool table, `:skills` from the process skill registry, and — when the
   caller passes the `:resources` service value — only the skills that
   selection enables. A caller that holds its own (kmet2's `:systemPrompt` row)
   calls the app namespace directly."
  [& {:keys [tools skills resources] :as opts}]
  (let [skill-list (or skills
                       (app-resources/enabled resources :skill
                                              (registered-skills)))]
    (apply system-prompt/build-system-prompt
           (mapcat identity
                   (-> opts
                       (dissoc :resources)
                       (assoc :tools (or tools
                                         (vals (tools-registry/get-all-tools
                                                (registry/current))))
                              :skills skill-list))))))

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
   subset parser — babashka has no YAML lib)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.debug :as debug]
            [kmet.app.tools.read :as read]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.libs.yaml :as yaml]))

;; ─── Constants (pi: MAX_NAME_LENGTH / MAX_DESCRIPTION_LENGTH) ─────────────

(def ^:private max-name-length 64)
(def ^:private max-description-length 1024)

;; ─── Skill state ──────────────────────────────────────────────────────────

(defonce ^:private skills (atom []))

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
   on demand — pi has no equivalent; kept for kmet's API and tests)."
  [name description]
  (swap! skills conj {:name name
                      :description description
                      :body nil
                      :raw nil
                      :location nil
                      :extension nil
                      :file-path nil
                      :base-dir nil
                      :disable-model-invocation false}))

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
  (let [entries (try (fs/list-dir dir) (catch Exception _ []))
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

(defn- add-skill!
  "Add SKILL to the registry unless its name collides (first wins, pi).
   Returns a collision diagnostic map, or nil."
  [skill location]
  (if-let [existing (first (filter #(= (:name %) (:name skill)) @skills))]
    {:type "collision"
     :message (str "name \"" (:name skill) "\" collision")
     :path (str location)
     :winner-path (str (or (:location existing) (:file-path existing)))
     :loser-path (str location)}
    (do (swap! skills conj skill)
        nil)))

(defn- add-skill-results!
  "Register parsed skill results ({:skill ... :diagnostics ...}), collecting
   collision diagnostics, then print all diagnostics to stderr and return
   them (pi: keep the first skill on a name collision)."
  [results]
  (let [collisions (volatile! [])]
    (doseq [{:keys [skill]} results]
      (when skill
        (when-let [collision (add-skill! skill (:file-path skill))]
          (vswap! collisions conj collision))))
    (let [diagnostics (into (vec (mapcat :diagnostics results)) @collisions)]
      (doseq [{:keys [type message path]} diagnostics]
        (binding [*out* *err*]
          (println (str "Warning: skill " type " at " path ": " message))))
      diagnostics)))

(defn clear-skills!
  "Remove all loaded skills and reset the registry (pi: resourceLoader.reload
   re-discovers from scratch). Used by /reload."
  []
  (reset! skills []))

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
   to the registry; on name collision the first skill found wins (pi: keep the
   first, emit a collision diagnostic). Returns the diagnostics for this dir
   (warning/collision maps), printed to stderr."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (add-skill-results! (load-skills-dir (str d))))))

(defn load-skills-from-files!
  "Load skills from explicit skill file paths (SKILL.md files or flat .md
   files — the package-resource unit, pi: package skills load). Same
   registration, collision and warning behavior as load-skills-from-dir.
   Returns the diagnostics."
  [file-paths]
  (add-skill-results! (mapv load-skill-from-file file-paths)))

(defn register-extension-skill!
  "Register a skill from an extension's bundled SKILL.md content string
   (jar-ext.md §5 — the extension reads its own resource via io/resource
   and hands the content over, so jarred skills need no filesystem path).
   OPTS: :location (display locator, e.g. `my-ext:skills/mcp/SKILL.md`),
   :fallback-name (when frontmatter has no name), :extension (owner name).
   Same validation/collision rules as dir loading (first wins). The body is
   served through the read tool under :location (pass it verbatim) as well
   as /skill:name expansion. Returns a
   deregister fn removing exactly this skill."
  [raw-content {:keys [location fallback-name extension]}]
  (let [{:keys [skill diagnostics]} (parse-skill-content raw-content
                                                         (or fallback-name
                                                             (str (or extension "skill")))
                                                         (or location
                                                             (str (or extension "skill")))
                                                         extension)]
    (doseq [{:keys [type message path]} diagnostics]
      (binding [*out* *err*]
        (println (str "Warning: skill " type " at " path ": " message))))
    (when skill
      (when-let [collision (add-skill! skill location)]
        (binding [*out* *err*]
          (println (str "Warning: skill collision at " (:path collision) ": "
                        (:message collision))))))
    (fn [] (swap! skills (fn [ss] (remove #(identical? % skill) ss))))))

(defn- skill-content-for-path
  "The SKILL.md text for RAW-PATH when it names a registered extension
   skill locator, or nil. Matches the exact <location> (e.g.
   `my-ext:skills/foo/SKILL.md`) and a cwd-joined absolute form (the model
   may join the location onto the working directory). Backslashes normalize
   to `/` first so Windows-joined paths resolve. File skills need no
   handling — they exist on disk."
  [raw-path]
  (let [p (str/replace (str raw-path) "\\" "/")]
    (some (fn [s]
            (when (and (:extension s) (seq (:location s)))
              (let [loc (str (:location s))]
                (when (or (= p loc)
                          (str/ends-with? p (str "/" loc)))
                  (or (:raw s) (:body s))))))
          @skills)))

;; tools.read cannot require this namespace (skills -> registry -> read
;; would cycle), so the locator resolver is pushed there at load time.
(read/set-skill-content-resolver! (fn [p] (skill-content-for-path p)))

(defn get-skills
  []
  @skills)

(defn get-skill
  [name]
  (first (filter #(= name (:name %)) @skills)))

(defn expand-skill-command
  "Expand /skill:name args into the skill body wrapped in a <skill> block
   (pi: agent-session _expandSkillCommand). Extension skills disclose from
   their in-memory body; file skills re-slurp their SKILL.md. Returns text
   unchanged when not a /skill: command, the skill is unknown, or the file
   cannot be read."
  [text]
  (if-not (str/starts-with? text "/skill:")
    text
    (let [space-idx (str/index-of text " ")
          skill-name (if (nil? space-idx) (subs text 7) (subs text 7 space-idx))
          args (if (nil? space-idx) "" (str/trim (subs text (inc space-idx))))]
      (if-let [skill (get-skill skill-name)]
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
        text))))

(defn parse-skill-block
  "Parse a skill block out of message text — the inverse of
   expand-skill-command (pi: parseSkillBlock). Returns
   {:name :location :content :user-message}, with :user-message nil when the
   text carries no trailing message, or nil when TEXT is not a skill block.

   The UI uses this to render an invocation as a dedicated message instead
   of dumping the XML wrapper and the whole skill body into the transcript;
   the session still stores the expanded text, so replay re-parses it."
  [text]
  (when (string? text)
    (when-let [m (re-matches #"(?s)^<skill name=\"([^\"]+)\" location=\"([^\"]+)\">\n(.*?)\n</skill>(?:\n\n(.*))?$"
                             text)]
      (let [[_ name location content user-message] m
            user-message (some-> user-message str/trim)]
        {:name name
         :location location
         :content content
         :user-message (when (seq user-message) user-message)}))))

(defn as-command-maps
  "Skills in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode skillCommandList — /skill:name commands, enabled
   by default)."
  [skill-list]
  (mapv (fn [s] {:name (str "skill:" (:name s))
                 :description (:description s)})
        skill-list))

;; ─── System prompt (pi: formatSkillsForPrompt + buildSystemPrompt) ─────────

(defn- escape-xml
  "XML-escape a string for use in element text."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn format-skills-for-prompt
  "Format skills as an <available_skills> XML block per the Agent Skills
   standard (pi: formatSkillsForPrompt). Skills with
   disable-model-invocation=true are excluded — they can only be invoked
   explicitly. Returns \"\" when no visible skills."
  [skill-list]
  (let [visible (remove :disable-model-invocation skill-list)]
    (if (empty? visible)
      ""
      (str "\n\nThe following skills provide specialized instructions for specific tasks.\n"
           "Use the read tool with the skill's exact <location> path to load its SKILL.md when the task matches its description.\n"
           "When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n"
           "Extension skills (locations like `ext-name:relative/path`) are served by the read tool from memory — pass the <location> string verbatim as the read path; they have no skill directory for relative refs.\n"
           "\n<available_skills>\n"
           (str/join "\n"
                     (map (fn [s]
                            (str "  <skill>\n"
                                 "    <name>" (escape-xml (:name s)) "</name>\n"
                                 "    <description>" (escape-xml (:description s)) "</description>\n"
                                 (when (or (:location s) (:file-path s))
                                   (str "    <location>" (escape-xml (or (:location s) (:file-path s))) "</location>\n"))
                                 "  </skill>"))
                          visible))
           "\n</available_skills>"))))

;; ─── System prompt (pi: formatSkillsForPrompt + buildSystemPrompt) ─────────

(def ^:private default-base-prompt
  "Default base prompt when no custom prompt is configured (pi: the default
   prompt text embedded in buildSystemPrompt, adapted for kmet)."
  "You are an expert coding assistant operating inside kmet, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.")

(defn- format-project-context
  "Wrap context files in a <project_context> block (pi: buildSystemPrompt's
   project context section)."
  [context-files]
  (str "\n\n<project_context>\n\n"
       "Project-specific instructions and guidelines:\n\n"
       (str/join "\n"
                 (map (fn [{:keys [path content]}]
                        (str "<project_instructions path=\"" path "\">\n"
                             content
                             "\n</project_instructions>"))
                      context-files))
       "\n\n</project_context>\n"))

(defn- build-guidelines
  "Assemble the de-duplicated Guidelines list (pi: buildSystemPrompt): the
   bash file-exploration rule when bash is the only such tool, then the tool
   and config guidelines, then the always-on ones."
  [selected-tools guidelines]
  (let [tool-set (set selected-tools)]
    (-> []
        (cond-> (and (contains? tool-set "bash")
                     (not (some tool-set ["grep" "find" "ls"])))
          (conj "Use bash for file operations like ls, rg, find"))
        (into (map (fn [g] (str/trim (str g)))
                   (filter (fn [g] (seq (str/trim (str g)))) guidelines)))
        (conj "Be concise in your responses")
        (conj "Show file paths clearly when working with files")
        distinct)))

(defn build-system-prompt
  "Build the system prompt with tools, guidelines, context files, and skills
   (pi: core/system-prompt.js buildSystemPrompt).

   opts:
     :custom-prompt — replaces the default base prompt (pi: customPrompt)
     :append-prompt — text appended after the main prompt (pi: appendSystemPrompt)
     :cwd           — current working directory (default: actual cwd)
     :context-files — seq of {:path ... :content ...} (AGENTS.md/CLAUDE.md)
     :tools         — seq of Tool records (default: all registered tools)
     :prompt-guidelines — extra guidelines, e.g. from config
     :skills        — seq of skill maps (default: (get-skills))

   The skills section is appended only when the read tool is available, since
   skills are loaded on demand via read (pi: hasRead check). Deviations from
   pi: no pi-docs section (kmet ships no bundled docs); the bash-exploration
   guideline fires only when bash is active and none of grep/find/ls is — as
   of T0 grep/find are built in, so it fires only when they are disabled
   (script.md)."
  [& {:keys [custom-prompt append-prompt cwd context-files tools
             prompt-guidelines skills]
      :or {cwd (str (fs/cwd))}}]
  (let [tools (or tools (vals (tools-registry/get-all-tools)))
        selected-tools (mapv :name tools)
        tool-set (set selected-tools)
        has-read (contains? tool-set "read")
        snippets (into {} (keep (fn [t]
                                  (when (seq (:prompt-snippet t))
                                    [(:name t) (:prompt-snippet t)])))
                       tools)
        tool-guidelines (mapcat #(or (:prompt-guidelines %) []) tools)
        skills (or skills (get-skills))
        context-files (or context-files [])
        append-section (if (seq append-prompt) (str "\n\n" append-prompt) "")
        project-context (when (seq context-files)
                          (format-project-context context-files))
        skills-block (when (and has-read (seq skills))
                       (format-skills-for-prompt skills))
        prompt-cwd (str/replace (str cwd) #"\\" "/")]
    (if (seq custom-prompt)
      (str custom-prompt
           append-section
           project-context
           skills-block
           "\nCurrent working directory: " prompt-cwd)
      (let [visible-tools (filter #(contains? snippets %) selected-tools)
            tools-list (if (seq visible-tools)
                         (str/join "\n" (map (fn [name]
                                               (str "- " name ": " (get snippets name)))
                                             visible-tools))
                         "(none)")
            guidelines (str/join "\n"
                                 (map #(str "- " %)
                                      (build-guidelines selected-tools
                                                        (concat tool-guidelines
                                                                prompt-guidelines))))]
        (str default-base-prompt
             "\n\nAvailable tools:\n" tools-list
             "\n\nIn addition to the tools above, you may have access to other custom tools depending on the project."
             "\n\nGuidelines:\n" guidelines
             append-section
             project-context
             skills-block
             "\nCurrent working directory: " prompt-cwd)))))

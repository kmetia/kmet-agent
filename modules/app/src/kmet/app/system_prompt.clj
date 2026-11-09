(ns kmet.app.system-prompt
  "The `:systemPrompt` row and the prompt builder it provides: the ordered
   sections (base text, tools, guidelines, project context, the skills block —
   pi: core/system-prompt.js buildSystemPrompt), plus the builder a loop driver
   hands its state as `:system-prompt-fn`, over the `:skills` registry.

   The builder is pure: every registry-shaped input is an argument (`:tools`,
   `:skills`), so an engine builds its prompt from the ctx it holds — its
   `:tools` handle, its `:prompts`/`:skills` services — and never from a process
   registry. The row fills the inputs services own: the skills a run may be
   told about, filtered by the `:resources` selection. Everything else is the
   caller's, because only the caller knows which agent it is building a prompt
   for; a tool table read here would be a process default, which is the thing
   the driver exists to replace.

   The row provides the builder itself rather than a record around it, so a
   driver reads one value and calls it — the same keyword-argument shape the
   pure builder takes, which is also the shape `:system-prompt-fn` promises.

   The only default is `:cwd`, which falls back to the process working
   directory — the right answer unless a session carries its own, in which case
   the caller passes it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as app-resources]
            [kmet.srce.core :as srce]))

;; ─── Skills section (pi: formatSkillsForPrompt) ───────────────────────────

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

;; ─── The prompt ───────────────────────────────────────────────────────────

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
  "Assemble the de-duplicated Guidelines list (pi: buildSystemPrompt's
   buildRules): the shell file-exploration rule when a shell tool is selected
   and no dedicated search tool (grep/find/ls) is, then the tool and config
   guidelines, then the always-on ones. The parallel-batching rule is kmet's
   addition — pi has no parallel-tool-call guidance, while kmet's loop runs a
   batch's calls in parallel by default
   (kmet.app.loop.tool-exec/execute-tool-calls-parallel!)."
  [selected-tools guidelines]
  (let [tool-set (set selected-tools)
        has-bash? (contains? tool-set "bash")
        has-powershell? (contains? tool-set "powershell")
        has-search-tool? (boolean (some tool-set ["grep" "find" "ls"]))]
    (-> []
        (cond-> (and (or has-bash? has-powershell?) (not has-search-tool?))
          (conj (cond
                  (and has-bash? has-powershell?)
                  "Use bash or PowerShell for file operations like listing, searching, and finding files"
                  has-powershell?
                  "Use PowerShell for file operations like listing, searching, and finding files"
                  :else
                  "Use bash for file operations like ls, rg, find")))
        (into (map (fn [g] (str/trim (str g)))
                   (filter (fn [g] (seq (str/trim (str g)))) guidelines)))
        (conj (str "When a turn needs several tool calls, batch the independent ones "
                   "into one message — they run in parallel, except eval calls, "
                   "which serialize; put eval work in one call. Wait for a call's "
                   "result only before using it."))
        (conj "Be concise in your responses")
        (conj "Show file paths clearly when working with files")
        distinct)))

(defn build-system-prompt
  "Build the system prompt with tools, guidelines, context files, and skills
   (pi: core/system-prompt.js buildSystemPrompt).

   opts:
     :custom-prompt — replaces the default base prompt (pi: customPrompt)
     :append-prompt — text appended after the main prompt (pi: appendSystemPrompt)
     :cwd           — current working directory (default: the process's)
     :date          — the date line's value (default: the local date, YYYY-MM-DD)
     :context-files — seq of {:path ... :content ...} (AGENTS.md/CLAUDE.md)
     :tools         — seq of Tool records; nil means none (an engine passes
                      its ctx's tool table)
     :prompt-guidelines — extra guidelines, e.g. from config
     :skills        — seq of skill maps; nil means none

   The skills section is appended only when the read tool is available, since
   skills are loaded on demand via read (pi: hasRead check). Deviations from
   pi: no pi-docs section (kmet ships no bundled docs); the parallel-batching
   guideline is kmet's addition (pi has no parallel-tool-call guidance); the
   `Current date` line is kept — pi dropped it because the daily change
   invalidates the cached prompt prefix (pi f4e9ca746, #6621), while kmet
   wants the model to know the date without running `date`. It closes the
   prompt and is defaulted at call time, so a prompt rebuild picks up a new
   day."
  [& {:keys [custom-prompt append-prompt cwd context-files tools
             prompt-guidelines skills date]
      :or {cwd (str (fs/cwd))
           date (str (java.time.LocalDate/now))}}]
  (let [selected-tools (mapv :name tools)
        tool-set (set selected-tools)
        has-read (contains? tool-set "read")
        snippets (into {} (keep (fn [t]
                                  (when (seq (:prompt-snippet t))
                                    [(:name t) (:prompt-snippet t)])))
                       tools)
        tool-guidelines (mapcat #(or (:prompt-guidelines %) []) tools)
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
           "\nCurrent working directory: " prompt-cwd
           "\nCurrent date: " date)
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
             "\nCurrent working directory: " prompt-cwd
             "\nCurrent date: " date)))))

(defn- registered-skills
  "SKILLS' effective skills in display order — one per name, from the owner
   that took it, so the prompt and a lookup name the same skill."
  [skills]
  (mapv second (registry/effective-entries skills :kmet/skill)))

(defn builder
  "A prompt builder over SKILLS (a `:skills` handle) and RESOURCES (the
   `:resources` service value, or nil): `build-system-prompt`'s keyword
   options with `:skills` defaulting to the registered skills the selection
   enables. A caller that names its own `:skills` keeps them — an
   agent-scoped list, or a test's empty one."
  [skills resources]
  (fn [& opts]
    (let [opts (apply hash-map opts)
          skill-list (or (:skills opts)
                         (app-resources/enabled resources :skill
                                                (registered-skills skills)))]
      (apply build-system-prompt
             (mapcat identity
                     (-> opts
                         (dissoc :resources)
                         (assoc :skills skill-list)))))))

(defn- serve
  "The `:systemPrompt` row: the builder over the injected `:skills` registry,
   filtered by the `:resources` selection when that row is mounted, provided
   into the mount site's scope so a sibling row (the loop driver) sees it while
   the entry stays this fiber's effect."
  [ctx _config]
  (srce/provide! ctx :system-prompt
                 (builder (srce/get ctx :skills) (srce/get ctx :resources))
                 {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :system-prompt
   :inject {:skills nil :resources :optional}
   :provide #{:system-prompt}
   :apply serve})

(ns kmet.app.system-prompt
  "The `:systemPrompt` row and the prompt builder it provides: the ordered
   sections (base text, tools, rules, addendum, project context, skills, cwd —
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

   The prompt is pi's, section for section (base, tools, rules, addendum,
   project context, skills, cwd): same text, same tags, same order. Two
   deliberate differences: pi's docs section is omitted because kmet ships no
   bundled docs at those paths, and a `Current date` line follows the cwd
   section so the model knows the date without running `date`.

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
   explicitly. FILE-READ-TOOL names the tool the model uses to load a skill
   (\"read\", \"bash\", or \"indirect\" when the reader is hidden); it
   selects the hint line. Returns \"\" when no visible skills."
  ([skill-list] (format-skills-for-prompt skill-list "read"))
  ([skill-list file-read-tool]
   (let [visible (remove :disable-model-invocation skill-list)]
     (if (empty? visible)
       ""
       (str "\n\nThe following skills provide specialized instructions for specific tasks.\n"
            (case file-read-tool
              "read" "Use the read tool to load a skill's file when the task matches its description."
              "bash" "Use bash to load a skill's file when the task matches its description."
              "Load a skill's file when the task matches its description.")
            "\nResolve relative paths against the skill's directory (parent of SKILL.md). Use absolute paths in tool calls.\n"
            "\n<available_skills>\n"
            (str/join "\n"
                      (map (fn [s]
                             (str "  <skill>\n"
                                  "    <name>" (escape-xml (:name s)) "</name>\n"
                                  "    <description>" (escape-xml (:description s)) "</description>\n"
                                  "    <location>" (escape-xml (or (:location s) (:file-path s))) "</location>\n"
                                  "  </skill>"))
                           visible))
            "\n</available_skills>")))))

;; ─── The prompt ───────────────────────────────────────────────────────────

(def ^:private default-base-prompt
  "Default base prompt when no custom prompt is configured (pi: the default
   prompt text embedded in buildSystemPrompt, with pi → kmet)."
  "You are a coding assistant running inside kmet, a coding agent harness. Help users inspect files, run commands, and modify code.")

(defn- format-project-context
  "Render context files as pi's project_context section body
   (pi: renderProjectContext)."
  [context-files]
  (str "Project-specific instructions and guidelines:\n\n"
       (str/join "\n\n"
                 (map (fn [{:keys [path content]}]
                        (str "<project_instructions path=\"" path "\">\n"
                             content
                             "\n</project_instructions>"))
                      context-files))))

(defn- tagged-section
  "Wrap CONTENT in the tag NAME, as pi's buildSystemPromptSections does."
  [name content]
  (str "<" name ">\n" content "\n</" name ">"))

(defn- shell-rule
  "Pi's shell file-exploration rule, or nil when a dedicated search tool makes
   it unnecessary."
  [tool-set]
  (when-not (some tool-set ["grep" "find" "ls"])
    (cond
      (and (tool-set "bash") (tool-set "powershell"))
      "Use bash or PowerShell for file operations like listing, searching, and finding files"

      (tool-set "powershell")
      "Use PowerShell for file operations like listing, searching, and finding files"

      (tool-set "bash")
      "Use bash for file operations like ls, rg, find")))

(defn- build-guidelines
  "Assemble the de-duplicated rules list (pi: buildRules): the shell rule, the
   tool and config guidelines, then the always-on ones."
  [selected-tools guidelines]
  (distinct
   (concat
    (when-let [rule (shell-rule (set selected-tools))] [rule])
    (keep (fn [g]
            (let [g (str/trim (str g))]
              (when (seq g) g)))
          guidelines)
    ["Be concise in your responses"
     "Show file paths clearly when working with files"])))

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

   The sections are pi's — preamble, <tools>, <rules>, addendum,
   <project_context>, <skills>, <cwd> — with two differences: pi's <docs>
   section is omitted, and the prompt ends with a `Current date` line. The
   skills section is emitted when a reader is selected: read or bash loads the
   skill file (pi: skillFileReadTool), so bash-only sessions see skills too."
  [& {:keys [custom-prompt append-prompt cwd context-files tools
             prompt-guidelines skills date]
      :or {cwd (str (fs/cwd))
           date (str (java.time.LocalDate/now))}}]
  (let [selected-tools (mapv :name tools)
        tool-set (set selected-tools)
        file-read-tool (some tool-set ["read" "bash"])
        snippets (into {} (keep (fn [t]
                                  (when (seq (:prompt-snippet t))
                                    [(:name t) (:prompt-snippet t)])))
                       tools)
        tool-guidelines (mapcat #(or (:prompt-guidelines %) []) tools)
        context-files (or context-files [])
        prompt-cwd (str/replace (str cwd) #"\\" "/")
        project-context (when (seq context-files)
                          (format-project-context context-files))
        skills-block (when (and file-read-tool (seq skills))
                       (str/trim (format-skills-for-prompt skills file-read-tool)))
        cwd-section (tagged-section "cwd" prompt-cwd)
        date-line (str "Current date: " date)
        common-sections [(when (seq append-prompt)
                           (tagged-section "addendum" append-prompt))
                         (when project-context
                           (tagged-section "project_context" project-context))
                         (when (seq skills-block)
                           (tagged-section "skills" skills-block))
                         cwd-section
                         date-line]]
    (str/join
     "\n\n"
     (remove empty?
             (if (seq custom-prompt)
               (into [custom-prompt] common-sections)
               (let [visible-tools (filter #(contains? snippets %) selected-tools)
                     tools-list (if (seq visible-tools)
                                  (str/join "\n"
                                            (map (fn [name]
                                                   (str "- " name ": " (get snippets name)))
                                                 visible-tools))
                                  "(none)")
                     guidelines (str/join "\n"
                                          (map #(str "- " %)
                                               (build-guidelines
                                                selected-tools
                                                (concat tool-guidelines
                                                        prompt-guidelines))))]
                 (into [default-base-prompt
                        (tagged-section "tools"
                                        (str tools-list
                                             "\n\nAdditional tools may be available."))
                        (tagged-section "rules" guidelines)]
                       common-sections)))))))

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

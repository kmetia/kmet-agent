(ns kmet.app.test-skills
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]))

;; ─── Skills ────────────────────────────────────────────────────────────────

(t/deftest test-register-skill
  (let [name "test-skill"
        description "A skill for testing."]
    (skills/register-skill! name description)
    (let [loaded (skills/get-skill name)]
      (t/is (some? loaded))
      (t/is (= name (:name loaded)))
      (t/is (= description (:description loaded))))))

(t/deftest test-get-skill-not-found
  (t/is (nil? (skills/get-skill "nonexistent"))))

(t/deftest test-build-system-prompt
  (t/testing "default prompt mirrors pi structure"
    (let [result (skills/build-system-prompt :cwd "/tmp")]
      (t/is (str/starts-with? result
                              "You are an expert coding assistant operating inside kmet, a coding agent harness."))
      (t/is (str/includes? result "Available tools:"))
      (t/is (str/includes? result "In addition to the tools above, you may have access to other custom tools depending on the project."))
      (t/is (str/includes? result "Guidelines:"))
      (t/is (str/includes? result "- Be concise in your responses"))
      (t/is (str/includes? result "- Show file paths clearly when working with files"))
      (t/is (str/ends-with? result "Current working directory: /tmp"))))
  (t/testing "tools are listed with one-line snippets"
    (let [result (skills/build-system-prompt :cwd "/tmp")]
      (t/is (str/includes? result "- read: Read file contents"))
      (t/is (str/includes? result "- bash: Execute bash commands (ls, grep, find, etc.)"))))
  (t/testing "tool guidelines appear in the Guidelines list (pi)"
    (let [result (skills/build-system-prompt :cwd "/tmp")]
      (t/is (str/includes? result "- Use read to examine files instead of cat or sed."))
      (t/is (str/includes? result "- Use write only for new files or complete rewrites."))
      (t/is (str/includes? result "- Use edit for precise changes (edits[].oldText must match exactly)"))
      (t/is (str/includes? result "- Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions."))))
  (t/testing "parallel tool-call batching guideline (kmet addition)"
    (let [result (skills/build-system-prompt :cwd "/tmp")]
      (t/is (str/includes? result "- When a turn needs several tool calls, batch the independent ones into one message"))
      (t/is (str/includes? result "- Use script to collapse a multi-step workflow into one call"))))
  (t/testing "skills are listed as available_skills XML"
    (let [name "test-prompt-skill"
          description "Prompt skill description."
          _ (skills/register-skill! name description)
          result (skills/build-system-prompt :cwd "/tmp")]
      (t/is (str/includes? result "<available_skills>"))
      (t/is (str/includes? result name))
      (t/is (str/includes? result description))))
  (t/testing "custom-prompt replaces the default base prompt (pi)"
    (let [result (skills/build-system-prompt :custom-prompt "Custom base" :cwd "/tmp")]
      (t/is (str/starts-with? result "Custom base"))
      (t/is (not (str/includes? result "Available tools:")))))
  (t/testing "append-prompt is appended after the main prompt (pi)"
    (let [result (skills/build-system-prompt :cwd "/tmp"
                                             :append-prompt "Extra instructions.")]
      (t/is (str/includes? result "Extra instructions."))))
  (t/testing "context files are wrapped in <project_context> (pi)"
    (let [result (skills/build-system-prompt :cwd "/tmp"
                                             :context-files [{:path "/x/AGENTS.md" :content "# Rules"}])]
      (t/is (str/includes? result "<project_context>"))
      (t/is (str/includes? result "<project_instructions path=\"/x/AGENTS.md\">"))
      (t/is (str/includes? result "# Rules"))))
  (t/testing "tools without a snippet are hidden, list shows (none) (pi)"
    (let [result (skills/build-system-prompt :cwd "/tmp"
                                             :tools [(tools/make-tool :name "no-snippet" :description "d")])]
      (t/is (str/includes? result "(none)"))
      (t/is (not (str/includes? result "no-snippet")))))
  (t/testing "skills block is omitted when read tool is not available (pi)"
    (let [result (skills/build-system-prompt :cwd "/tmp"
                                             :tools [(tools/make-tool :name "bash" :description "d"
                                                                      :prompt-snippet "Execute bash commands")])]
      (t/is (not (str/includes? result "<available_skills>"))))))

(t/deftest test-get-skills-returns-list
  (let [name "test-gs"
        description "GS description"]
    (skills/register-skill! name description)
    (let [all (skills/get-skills)]
      (t/is (sequential? all))
      (t/is (some #(= name (:name %)) all)))))

(t/deftest test-load-skills-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (empty? (skills/load-skills-from-dir "/nonexistent/skills")))))

(t/deftest test-load-skills-from-dir
  (t/testing "SKILL.md directories are discovered as skill roots"
    (let [tmp-dir (str "target/test-skills-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/my-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (try
        (io/make-parents skill-file)
        (spit skill-file
              "---\nname: my-skill\ndescription: Test skill for discovery.\n---\n# My Skill\nDo the thing.")
        (spit (str tmp-dir "/note.txt") "not a skill")
        (skills/load-skills-from-dir tmp-dir)
        (let [loaded (skills/get-skill "my-skill")]
          (t/is (some? loaded))
          (t/is (= "my-skill" (:name loaded)))
          (t/is (= "Test skill for discovery." (:description loaded))))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-flat-md-fallback
  (t/testing "flat .md files load when a dir has no SKILL.md (pi discovery)"
    (let [tmp-dir (str "target/test-skills-flat-" (System/currentTimeMillis))
          f (str tmp-dir "/flat-skill.md")]
      (try
        (io/make-parents f)
        (spit f "---\nname: flat-skill\ndescription: Flat skill description.\n---\n# Flat Skill")
        (skills/load-skills-from-dir tmp-dir)
        (let [loaded (skills/get-skill "flat-skill")]
          (t/is (some? loaded))
          (t/is (= "Flat skill description." (:description loaded))))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-skips-missing-description
  (t/testing "skills without a description are not loaded (pi validation)"
    (let [tmp-dir (str "target/test-skills-nodesc-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/no-desc-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (try
        (io/make-parents skill-file)
        (spit skill-file "---\nname: no-desc-skill\n---\n# No description")
        ;; missing-description diagnostic is printed to stderr — suppress it.
        (binding [*err* (java.io.StringWriter.)]
          (skills/load-skills-from-dir tmp-dir))
        (t/is (nil? (skills/get-skill "no-desc-skill")))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-name-fallback
  (t/testing "name falls back to parent dir name when frontmatter has none (pi)"
    (let [tmp-dir (str "target/test-skills-fallback-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/fallback-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (try
        (io/make-parents skill-file)
        (spit skill-file "---\ndescription: No name in frontmatter.\n---\n# Fallback")
        (skills/load-skills-from-dir tmp-dir)
        (let [loaded (skills/get-skill "fallback-skill")]
          (t/is (some? loaded))
          (t/is (= "fallback-skill" (:name loaded))))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-empty-frontmatter
  (t/testing "empty frontmatter (---\n---) does not crash; skill skipped (no description)"
    (let [tmp-dir (str "target/test-skills-emptyfm-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/empty-fm")
          skill-file (str skill-dir "/SKILL.md")]
      (try
        (io/make-parents skill-file)
        (spit skill-file "---\n---\n# Empty frontmatter")
        (let [diags (binding [*err* (java.io.StringWriter.)]
                      (skills/load-skills-from-dir tmp-dir))]
          (t/is (some #(= "description is required" (:message %)) diags)))
        (t/is (nil? (skills/get-skill "empty-fm")))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-nested-metadata
  (t/testing "nested YAML parses into its own structure without polluting top-level fields"
    (let [tmp-dir (str "target/test-skills-nested-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/nested-meta")
          skill-file (str skill-dir "/SKILL.md")]
      (try
        (io/make-parents skill-file)
        (spit skill-file
              "---\nname: nested-meta\ndescription: Real description.\nmetadata:\n  description: FAKE\n  name: FAKE\n---\n# Nested")
        (skills/load-skills-from-dir tmp-dir)
        (let [loaded (skills/get-skill "nested-meta")]
          (t/is (some? loaded))
          (t/is (= "nested-meta" (:name loaded)))
          (t/is (= "Real description." (:description loaded))))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-skills-collision-first-wins
  (t/testing "same name from two files keeps the first (pi) and reports collision"
    (let [tmp-dir (str "target/test-skills-collision-" (System/currentTimeMillis))
          f1 (str tmp-dir "/dup-one/SKILL.md")
          f2 (str tmp-dir "/dup-two/SKILL.md")]
      (try
        (io/make-parents f1)
        (io/make-parents f2)
        (spit f1 "---\nname: collision-skill\ndescription: First version.\n---\n# One")
        (spit f2 "---\nname: collision-skill\ndescription: Second version.\n---\n# Two")
        (let [diags (binding [*err* (java.io.StringWriter.)]
                      (skills/load-skills-from-dir tmp-dir))
              loaded (skills/get-skill "collision-skill")]
          (t/is (= "First version." (:description loaded)))
          (t/is (some #(= "collision" (:type %)) diags)))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-expand-skill-command
  (let [tmp-dir (str "target/test-skills-expand-" (System/currentTimeMillis))
        skill-dir (str tmp-dir "/expand-target")
        skill-file (str skill-dir "/SKILL.md")]
    (try
      (io/make-parents skill-file)
      (spit skill-file "---\nname: expand-target\ndescription: Test.\n---\n# My Skill\nDo the thing.")
      (skills/load-skills-from-dir tmp-dir)
      (t/testing "/skill:name expands to a <skill> block (frontmatter stripped)"
        (let [expanded (skills/expand-skill-command "/skill:expand-target")]
          (t/is (str/includes? expanded "<skill name=\"expand-target\""))
          (t/is (str/includes? expanded "References are relative to"))
          (t/is (str/includes? expanded "# My Skill\nDo the thing."))
          (t/is (str/ends-with? expanded "</skill>"))))
      (t/testing "args are appended raw after the block (pi)"
        (let [expanded (skills/expand-skill-command "/skill:expand-target arg1 \"a b\"")]
          (t/is (str/includes? expanded "Do the thing.\n</skill>\n\narg1 \"a b\""))))
      (t/testing "unknown skill passes through"
        (t/is (= "/skill:nope x" (skills/expand-skill-command "/skill:nope x"))))
      (t/testing "non-skill text passes through"
        (t/is (= "hello" (skills/expand-skill-command "hello"))))
      (finally (fs/delete-tree tmp-dir)))))

(t/deftest test-as-command-maps
  (let [skills-list [{:name "code-review" :description "Review code."}
                     {:name "hidden" :description "Hidden." :disable-model-invocation true}]]
    (t/is (= [{:name "skill:code-review" :description "Review code."}
              {:name "skill:hidden" :description "Hidden."}]
             (skills/as-command-maps skills-list)))))

(t/deftest test-format-skills-for-prompt
  (let [skill {:name "xml-skill"
               :description "Uses & <angle> \"quotes\""
               :file-path "/skills/xml-skill/SKILL.md"
               :disable-model-invocation false}
        hidden (assoc skill :name "hidden-skill" :disable-model-invocation true)
        prompt (skills/format-skills-for-prompt [skill hidden])]
    (t/testing "disable-model-invocation skills are excluded"
      (t/is (str/includes? prompt "xml-skill"))
      (t/is (not (str/includes? prompt "hidden-skill"))))
    (t/testing "name, description, location are XML-escaped"
      (t/is (str/includes? prompt "Uses &amp; &lt;angle&gt; &quot;quotes&quot;"))
      (t/is (str/includes? prompt "<location>/skills/xml-skill/SKILL.md</location>")))
    (t/testing "empty input yields empty output"
      (t/is (= "" (skills/format-skills-for-prompt []))))))

(t/deftest test-clear-skills
  (skills/register-skill! "clear-me" "Will be cleared.")
  (skills/clear-skills!)
  (t/is (nil? (skills/get-skill "clear-me"))))

(t/deftest test-register-extension-skill
  (t/testing "content registration parses, validates and expands from memory"
    (skills/clear-skills!)
    (let [dereg (skills/register-extension-skill!
                 "---\nname: ext-mem\ndescription: In-memory skill\n---\n# Body\nDo it."
                 {:location "my-ext:skills/ext-mem/SKILL.md" :extension "my-ext"})]
      (let [loaded (skills/get-skill "ext-mem")]
        (t/is (some? loaded))
        (t/is (= "# Body\nDo it." (:body loaded)))
        (t/is (= "my-ext:skills/ext-mem/SKILL.md" (:location loaded)))
        (t/is (= "my-ext" (:extension loaded)))
        (t/is (nil? (:file-path loaded))))
      (t/is (str/includes? (skills/expand-skill-command "/skill:ext-mem") "Do it."))
      (t/is (str/includes? (skills/expand-skill-command "/skill:ext-mem")
                           "self-contained")
            "extension skills note they have no skill directory")
      (t/is (str/includes? (skills/format-skills-for-prompt (skills/get-skills))
                           "my-ext:skills/ext-mem/SKILL.md"))
      (dereg)
      (t/is (nil? (skills/get-skill "ext-mem")) "deregister removes the skill")))
  (t/testing "first wins on collision with a file skill"
    (skills/clear-skills!)
    (let [tmp-dir (str "target/test-skills-extcoll-" (System/currentTimeMillis))]
      (try
        (io/make-parents (str tmp-dir "/s/SKILL.md"))
        (spit (str tmp-dir "/s/SKILL.md")
              "---\nname: dup\ndescription: File version.\n---\nFile body")
        (skills/load-skills-from-dir tmp-dir)
        (binding [*err* (java.io.StringWriter.)]
          (skills/register-extension-skill! "---\nname: dup\ndescription: Ext.\n---\nExt body"
                                            {:location "e:dup/SKILL.md" :extension "e"}))
        (t/is (= "File version." (:description (skills/get-skill "dup"))))
        (finally (fs/delete-tree tmp-dir)
                 (skills/clear-skills!)))))
  (t/testing "missing description is rejected"
    (skills/clear-skills!)
    (binding [*err* (java.io.StringWriter.)]
      (skills/register-extension-skill! "---\nname: nodesc\n---\nNo desc"
                                        {:location "e:nodesc/SKILL.md" :extension "e"}))
    (t/is (nil? (skills/get-skill "nodesc")))
    (skills/clear-skills!)))

(t/deftest test-extension-skill-readable-via-read-tool
  (t/testing "extension skill bodies load through the read tool under their location"
    (skills/clear-skills!)
    (let [dereg (skills/register-extension-skill!
                 "---\nname: read-me\ndescription: Readable skill\n---\n# Body\nDo it."
                 {:location "my-ext:skills/read-me/SKILL.md" :extension "my-ext"})]
      (try
        (t/testing "exact location resolves"
          (let [result (tools/execute-tool "read" {:path "my-ext:skills/read-me/SKILL.md"})]
            (t/is (not (:is-error result)))
            (t/is (str/includes? (:content result) "Do it."))))
        (t/testing "cwd-joined absolute locator resolves (model verbatim join)"
          (let [result (tools/execute-tool "read" {:path (str (fs/cwd) "/my-ext:skills/read-me/SKILL.md")})]
            (t/is (not (:is-error result)))
            (t/is (str/includes? (:content result) "Do it."))))
        (t/testing "backslash-joined locator resolves (Windows path join)"
          (let [result (tools/execute-tool "read" {:path "C:\\work\\my-ext:skills/read-me/SKILL.md"})]
            (t/is (not (:is-error result)))
            (t/is (str/includes? (:content result) "Do it."))))
        (t/testing "offset/limit apply to skill reads"
          (let [result (tools/execute-tool "read" {:path "my-ext:skills/read-me/SKILL.md" :offset 1 :limit 1})]
            (t/is (not (:is-error result)))
            (t/is (str/includes? (:content result) "---"))))
        (t/testing "unknown locator still reports File not found"
          (let [result (tools/execute-tool "read" {:path "my-ext:skills/nope/SKILL.md"})]
            (t/is (:is-error result))
            (t/is (str/includes? (:content result) "File not found"))))
        (t/testing "deregistered skill no longer resolves"
          (dereg)
          (let [result (tools/execute-tool "read" {:path "my-ext:skills/read-me/SKILL.md"})]
            (t/is (:is-error result))))
        (finally (skills/clear-skills!))))))

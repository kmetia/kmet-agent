(ns kmet.app.test-system-prompt
  "The `:systemPrompt` row and the pure builder it provides: the row fills the
   one input a service owns (the skills registry) and hands the builder out as a
   value; the builder itself takes every registry-shaped input as an argument, so
   two engines build two prompts and nothing is read from a process registry.
   `kmet.app.skills/build-system-prompt` is kmet1's wrapper over the builder —
   it supplies the process defaults and is covered by `kmet.app.test-skills`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.app.resources :as resources]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.srce.core :as srce]))

(defn- mounted
  "The root ctx with the row over the skills registry."
  []
  (let [ctx (srce/root {:name :app})]
    (srce/plugin! ctx registries/skills)
    (srce/plugin! ctx system-prompt/plugin)
    ctx))

(def ^:private read-tool
  "The prompt carries skills only when a read tool is in the table: a skill is
   loaded on demand through it (pi: hasRead)."
  {:name "read"})

(defn- register-skill!
  ([ctx name] (register-skill! ctx name nil))
  ([ctx name description]
   (registry/register! (srce/get ctx :skills) :kmet/skill
                       name
                       {:name name
                        :description (or description (str "The " name " skill."))
                        :location (str "skills/" name "/SKILL.md")}
                       :test)))

(deftest the-row-mounts-over-the-skills-registry
  (let [ctx (srce/root {:name :app})]
    (srce/plugin! ctx registries/skills)
    (let [row (srce/plugin! ctx system-prompt/plugin)]
      (is (= :active (:state @row)))
      (is (= #{:system-prompt} (:provides @row)))
      (is (fn? (srce/get ctx :system-prompt)) "the service value is the builder")
      (is (string? ((srce/get ctx :system-prompt) :cwd "/work" :tools [read-tool]))
          "and it answers the builder's keyword options")
      (srce/dispose! row)
      (is (nil? (srce/get ctx :system-prompt)) "the entry goes with the fiber"))))

(deftest the-builder-fills-skills-from-the-registry
  (let [ctx (mounted)
        builder (srce/get ctx :system-prompt)]
    (register-skill! ctx "clojure-edit" "Structure-aware Clojure edits.")
    (testing "a read tool in the table means the skills section"
      (let [prompt (builder :cwd "/work" :tools [read-tool])]
        (is (str/includes? prompt "<available_skills>"))
        (is (str/includes? prompt "<name>clojure-edit</name>"))
        (is (str/includes? prompt "Structure-aware Clojure edits."))))
    (testing "and no read tool means none"
      (is (not (str/includes? (builder :cwd "/work" :tools []) "<available_skills>"))))
    (testing "the list is read per call, not captured at mount"
      (registry/deregister! (srce/get ctx :skills) :kmet/skill "clojure-edit" :test)
      (is (not (str/includes? (builder :cwd "/work" :tools [read-tool])
                              "clojure-edit"))))))

(deftest a-caller-that-names-skills-keeps-them
  (let [ctx (mounted)
        builder (srce/get ctx :system-prompt)]
    (register-skill! ctx "from-the-registry")
    (testing "an explicit empty list is no skills, not the defaults"
      (is (not (str/includes? (builder :cwd "/work" :tools [read-tool] :skills [])
                              "from-the-registry"))))
    (testing "an explicit list replaces the registry's"
      (let [prompt (builder :cwd "/work"
                            :tools [read-tool]
                            :skills [{:name "mine" :description "Mine only."
                                      :location "skills/mine/SKILL.md"}])]
        (is (str/includes? prompt "<name>mine</name>"))
        (is (not (str/includes? prompt "from-the-registry")))))))

(deftest a-service-that-is-not-mounted-keeps-every-skill
  (let [ctx (mounted)
        builder (srce/get ctx :system-prompt)]
    (register-skill! ctx "from-the-registry")
    (is (str/includes? (builder :cwd "/work" :tools [read-tool])
                       "from-the-registry")
        "the optional :resources inject does not gate the row")))

(deftest the-selection-filters-the-prompt-skills
  (let [ctx (srce/root {:name :app})]
    (doseq [row [registries/tools registries/skills registries/prompts]]
      (srce/plugin! ctx row))
    (srce/settle! ctx)
    (srce/plugin! ctx resources/plugin)
    (srce/plugin! ctx system-prompt/plugin)
    (srce/settle! ctx)
    (register-skill! ctx "kept")
    (register-skill! ctx "dropped")
    (with-redefs [cfg/read-global-settings-map
                  (constantly {:resources {:skills ["-dropped"]}})
                  cfg/read-project-settings-map (constantly {})]
      (let [prompt ((srce/get ctx :system-prompt) :cwd "/work" :tools [read-tool])]
        (is (str/includes? prompt "<name>kept</name>"))
        (is (not (str/includes? prompt "dropped"))
            "a disabled skill is not offered to the model")))))

;; ── the builder itself ────────────────────────────────────────────────────

(defn- tool
  [name snippet & [guidelines]]
  {:name name :prompt-snippet snippet :prompt-guidelines guidelines})

(defn- prompt
  [& opts]
  (apply system-prompt/build-system-prompt opts))

(deftest the-tool-table-comes-from-the-caller
  (let [a (prompt :tools [(tool "read" "Read a file") (tool "grep" "Search files")]
                  :skills [])
        b (prompt :tools [(tool "bash" "Run a command")] :skills [])]
    (is (str/includes? a "- read: Read a file"))
    (is (str/includes? a "- grep: Search files"))
    (is (not (str/includes? a "- bash:")))
    (is (str/includes? b "- bash: Run a command"))
    (is (not (str/includes? b "- read:")))
    (is (str/includes? a "When a turn needs several tool calls")
        "the always-on guidelines are not a tool's")))

(deftest no-tool-table-means-no-tools
  ;; nil is the caller's "this engine has none", not "use the process's": the
  ;; builder has no registry to fall back to.
  (let [p (prompt :skills [])]
    (is (str/includes? p "Available tools:\n(none)"))
    (is (not (str/includes? p "bash")))))

(deftest the-skills-block-needs-the-read-tool
  (let [skills [{:name "deploy" :description "Ship it" :location "/tmp/deploy/SKILL.md"}
                {:name "secret" :description "Hidden" :disable-model-invocation true}]
        with-read (prompt :tools [(tool "read" "Read a file")] :skills skills)
        without-read (prompt :tools [(tool "bash" "Run a command")] :skills skills)]
    (is (str/includes? with-read "<available_skills>"))
    (is (str/includes? with-read "<name>deploy</name>"))
    (is (not (str/includes? with-read "secret"))
        "disable-model-invocation skills are never listed")
    (is (not (str/includes? without-read "<available_skills>"))
        "skills load through read, so no read tool means no block")))

(deftest the-date-line-ends-the-prompt
  (testing "the builder's default is the local date"
    (is (re-find #"\nCurrent date: \d{4}-\d{2}-\d{2}$" (prompt :skills []))
        "YYYY-MM-DD as the last line"))
  (testing "a caller can pin it"
    (let [p (prompt :date "2026-08-03" :cwd "/work" :skills [])]
      (is (str/ends-with? p "\nCurrent date: 2026-08-03"))
      (is (str/includes? p "Current working directory: /work"))
      (is (< (.indexOf p "Current working directory:") (.indexOf p "Current date:")))))
  (testing "a custom prompt carries it too"
    (is (str/ends-with? (prompt :custom-prompt "CUSTOM" :date "2026-08-03" :skills [])
                        "\nCurrent date: 2026-08-03"))))

(deftest a-custom-prompt-still-carries-context-and-skills
  (let [p (prompt :custom-prompt "CUSTOM"
                  :append-prompt "APPENDED"
                  :cwd "/work"
                  :context-files [{:path "AGENTS.md" :content "rules"}]
                  :tools [(tool "read" "Read a file")]
                  :skills [{:name "deploy" :description "Ship it"}])]
    (is (str/starts-with? p "CUSTOM"))
    (is (str/includes? p "APPENDED"))
    (is (str/includes? p "<project_instructions path=\"AGENTS.md\">\nrules\n"))
    (is (str/includes? p "<name>deploy</name>"))
    (is (str/includes? p "Current working directory: /work"))
    (is (not (str/includes? p "Available tools:"))
        "a custom prompt replaces the built one, not just its first line")))

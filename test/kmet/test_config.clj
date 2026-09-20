(ns kmet.test-config
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.libs.http :as http]
            [kmet.ai.auth :as auth]))

;; ─── Defaults ──────────────────────────────────────────────────────────────

(t/deftest test-default-config
  (let [c cfg/default-config]
    (t/is (map? c))
    (t/is (= :opencode-go (:provider c)))
    (t/is (= "dark" (:theme c)))
    (t/is (contains? c :session-dir))
    (t/is (= "deepseek-v4-flash" (:model c)))))

(t/deftest test-default-config-has-no-provider-map
  ;; Phase 0: the :providers map is replaced by the models registry — the
  ;; provider defaults live in the catalog EDN, not in default-config.
  (t/is (not (contains? cfg/default-config :providers))))

;; ─── Load config ──────────────────────────────────────────────────────────

(t/deftest test-load-config-defaults
  (let [c (cfg/load-config :no-env? true :no-settings? true)]
    (t/is (map? c))
    (t/is (= :opencode-go (:provider c)))
    (t/is (= "dark" (:theme c)))))

;; ─── Accessors ─────────────────────────────────────────────────────────────

(t/deftest test-get-provider
  (let [c cfg/default-config]
    (t/is (= :opencode-go (cfg/get-provider c)))))

(t/deftest test-get-model-from-default
  (t/is (= "deepseek-v4-flash" (cfg/get-model cfg/default-config))))

(t/deftest test-get-model-explicit
  (let [c (assoc cfg/default-config :model "gpt-4o-mini")]
    (t/is (= "gpt-4o-mini" (cfg/get-model c)))))

(t/deftest test-get-model-no-provider-fallback
  ;; Phase 0: get-model no longer falls back through :providers — provider
  ;; defaults come from the models registry (models/resolve-config-model).
  (let [c (assoc cfg/default-config :provider :anthropic :model nil)]
    (t/is (nil? (cfg/get-model c)))))

(t/deftest test-get-session-dir
  (let [c (assoc cfg/default-config :session-dir "/tmp/kmet-sessions")]
    (t/is (= "/tmp/kmet-sessions" (cfg/get-session-dir c)))))

(t/deftest test-get-session-dir-tilde-expanded
  (let [home (System/getProperty "user.home")
        c (assoc cfg/default-config :session-dir "~/.kmet/sessions")
        dir (cfg/get-session-dir c)]
    (t/is (str/starts-with? dir home))
    (t/is (str/ends-with? dir "/.kmet/sessions"))))

;; ─── Shell settings (pi: shellPath / shellCommandPrefix) ──────────────────

(defn- shell-options []
  ;; the executor's private settings atom (pi: SettingsManager getShellPath /
  ;; getShellCommandPrefix)
  @@#'bash-exec/shell-options)

(t/deftest test-get-shell-path
  (t/is (nil? (cfg/get-shell-path cfg/default-config)))
  (t/is (= "/custom/bash" (cfg/get-shell-path (assoc cfg/default-config :shell-path "/custom/bash"))))
  (t/testing "a leading ~ expands (pi: normalizePath on getShellPath)"
    (let [home (System/getProperty "user.home")]
      (t/is (= (str home "/bin/bash")
               (cfg/get-shell-path (assoc cfg/default-config :shell-path "~/bin/bash")))))))

(t/deftest test-get-shell-command-prefix
  (t/is (nil? (cfg/get-shell-command-prefix cfg/default-config)))
  (t/is (= "shopt -s expand_aliases"
           (cfg/get-shell-command-prefix
            (assoc cfg/default-config :shell-command-prefix "shopt -s expand_aliases")))))

(t/deftest test-load-config-applies-shell-settings
  (t/testing "load-config applies the shell settings to bash execution (pi: getShellPath/getShellCommandPrefix)"
    (let [tmp (str (fs/create-temp-dir {:dir "target" :prefix "shell-settings-test-"}))
          saved (shell-options)]
      (try
        (spit (str (fs/path tmp "settings.edn"))
              (pr-str {:shell-path "~/bin/bash"
                       :shell-command-prefix "shopt -s expand_aliases"}))
        (cfg/load-config :no-env? true :agent-dir tmp)
        (t/is (= (str (System/getProperty "user.home") "/bin/bash")
                 (:shell-path (shell-options))))
        (t/is (= "shopt -s expand_aliases" (:command-prefix (shell-options))))
        (t/testing "the defaults clear the knobs"
          (cfg/load-config :no-env? true :no-settings? true)
          (t/is (= {:shell-path nil :command-prefix nil} (shell-options))))
        (finally
          (bash-exec/set-shell-options! saved)
          (fs/delete-tree tmp))))))

(t/deftest test-get-theme-name
  (let [c (assoc cfg/default-config :theme "light")]
    (t/is (= "light" (cfg/get-theme-name c)))))

(t/deftest test-get-theme-name-default
  (let [c (dissoc cfg/default-config :theme)]
    (t/is (= "dark" (cfg/get-theme-name c)))))

;; ─── Scope-relative path resolution ────────────────────────────────────────

(t/deftest test-scope-path-resolution
  (let [resolve-paths @#'cfg/resolve-scope-paths
        home (System/getProperty "user.home")]
    (t/testing "relative paths resolve against their scope dir"
      (let [global (resolve-paths {:session-dir "sessions" :model "x"} "/g/base")
            project (resolve-paths {:session-dir "sessions"} "/p/base")]
        (t/is (= "/g/base/sessions" (:session-dir global)))
        (t/is (= "x" (:model global)))
        (t/is (= "/p/base/sessions" (:session-dir project)))))
    (t/testing "retired :*-dir keys and resource entries pass through unresolved"
      (let [res (resolve-paths {:extensions-dir "/custom/ext"
                                :extensions ["extra"]} "/base")]
        (t/is (= "/custom/ext" (:extensions-dir res)))
        (t/is (= ["extra"] (:extensions res)))))
    (t/testing "tilde and absolute paths pass through"
      (let [res (resolve-paths {:session-dir "~/.kmet/sessions"} "/base")]
        (t/is (str/starts-with? (:session-dir res) home))))
    (t/testing "non-string values and nil config pass through"
      (t/is (= {} (resolve-paths nil "/base")))
      (t/is (= {:model "x"} (resolve-paths {:model "x"} "/base")))
      (t/is (= {:session-dir nil} (resolve-paths {:session-dir nil} "/base"))))))

;; ─── Provider config ───────────────────────────────────────────────────────
;; Phase 0: provider-configs / get-provider-config / get-provider-base-url /
;; get-provider-api-type are deleted — base-url/api-type come from the models
;; registry (kmet.ai.models), not from config (covered by test_models).

(t/deftest test-auto-resource-dirs
  (let [sandbox (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")}))
        agent-dir (str (fs/path sandbox "agent"))
        cwd (str (fs/path sandbox "project"))
        global (fn [type] (str (fs/path agent-dir (name type))))
        project (fn [type] (str (fs/path cwd ".kmet" (name type))))]
    (try
      (t/testing "fixed roots: agent-dir + .kmet, global first (pi load order)"
        (with-redefs [cfg/get-agent-dir (fn [] agent-dir)
                      fs/cwd (fn [] cwd)]
          (doseq [type [:extensions :skills :prompts :themes]]
            (fs/create-dirs (global type))
            (fs/create-dirs (project type)))
          (t/is (= [(global :skills) (project :skills)]
                   (cfg/auto-resource-dirs :skills)))))
      (t/testing "missing dirs are skipped"
        (with-redefs [fs/cwd (fn [] "/does/not/exist")]
          (t/is (= [] (cfg/auto-resource-dirs :extensions "/does/not/exist")))))
      (t/testing "explicit agent-dir pins the global root (sandboxing)"
        (let [pinned (str (fs/path sandbox "pinned"))
              ext (str (fs/path pinned "extensions"))]
          (fs/create-dirs ext)
          (with-redefs [fs/cwd (fn [] "/does/not/exist")]
            (t/is (= [ext] (cfg/auto-resource-dirs :extensions pinned))))))
      (finally (fs/delete-tree sandbox)))))

(t/deftest test-agent-dir-override
  (t/testing "default is ~/.kmet/agent"
    ;; env stubbed so the assertions hold on hosts that set the override
    (with-redefs [auth/getenv (fn [_] nil)]
      (t/is (str/ends-with? (auth/resolve-agent-dir) "/.kmet/agent"))
      (t/is (= (auth/resolve-agent-dir) (cfg/get-agent-dir)))))
  (t/testing "KMET_CODING_AGENT_DIR wins; blanks fall back"
    (with-redefs [auth/getenv (fn [_] nil)]
      (t/is (str/ends-with? (auth/resolve-agent-dir) "/.kmet/agent")))
    (with-redefs [auth/getenv (fn [_] "/sandbox/agent")]
      (t/is (= "/sandbox/agent" (auth/resolve-agent-dir)))
      (t/is (= "/sandbox/agent/auth.edn" (auth/auth-file-path)))
      (t/is (= "/sandbox/agent/settings.edn" (cfg/global-settings-path))))
    (with-redefs [auth/getenv (fn [_] "   ")]
      (t/is (str/ends-with? (auth/resolve-agent-dir) "/.kmet/agent")))
    (with-redefs [auth/getenv (fn [_] "~/custom")]
      (t/is (str/starts-with? (auth/resolve-agent-dir) (System/getProperty "user.home"))))))

(t/deftest test-retired-dir-keys-warn
  (t/testing "retired :*-dir keys warn with the replacement entry"
    (let [out (java.io.StringWriter.)]
      (binding [*err* out]
        (#'cfg/warn-retired-dir-keys! {:extensions-dir "/x" :theme "dark"} {:skills-dir "/y"}))
      (let [s (str out)]
        (t/is (str/includes? s ":extensions entry"))
        (t/is (str/includes? s ":skills entry"))
        (t/is (not (str/includes? s ":theme")))))))

;; ─── API key ───────────────────────────────────────────────────────────────

(t/deftest test-get-api-key-returns-string-or-nil
  ;; get-api-key returns a string (if key available) or nil
  (let [key (cfg/get-api-key :deepseek)]
    (t/is (or (nil? key) (string? key)))))

(t/deftest test-get-api-key-unknown-provider
  ;; providers without an env entry → nil (auth.edn aside); env lookup is
  ;; pinned to nil so the result doesn't depend on the host environment
  (with-redefs [auth/getenv (fn [_] nil)]
    (t/is (nil? (cfg/get-api-key :nonexistent)))
    (t/is (nil? (cfg/get-api-key :openai)))))

;; ─── get-theme ─────────────────────────────────────────────────────────────

(t/deftest test-get-theme
  (let [t (cfg/get-theme cfg/default-config)]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-get-theme-light-config
  (let [c (assoc cfg/default-config :theme "light")
        t (cfg/get-theme c)]
    (t/is (= "light" (:name t)))))

;; ─── System prompt sources (pi: SYSTEM.md / APPEND_SYSTEM.md) ─────────────

(t/deftest test-get-custom-prompt
  (t/testing "config value wins over files"
    (t/is (= "Custom" (cfg/get-custom-prompt {:system-prompt "Custom"}))))
  (t/testing "config value naming an existing file is read as content (pi resolvePromptInput)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-" (System/currentTimeMillis)))))
          f (str tmp "/prompt.md")]
      (io/make-parents f)
      (spit f "From file")
      (try
        (t/is (= "From file" (cfg/get-custom-prompt {:system-prompt f})))
        (finally (fs/delete-tree tmp)))))
  (t/testing "no config value, no files yields nil"
    (with-redefs [cfg/prompt-file-candidates (fn [_] [])]
      (t/is (nil? (cfg/get-custom-prompt {}))))))

(t/deftest test-get-custom-prompt-file-discovery
  (t/testing "project file wins over global file (pi order)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-dir-" (System/currentTimeMillis)))))
          project-file (str tmp "/.kmet/SYSTEM.md")
          global-file (str tmp "/agent/SYSTEM.md")]
      (io/make-parents project-file)
      (io/make-parents global-file)
      (spit project-file "Project prompt")
      (spit global-file "Global prompt")
      (try
        (with-redefs [cfg/prompt-file-candidates (fn [_] [project-file global-file])]
          (t/is (= "Project prompt" (cfg/get-custom-prompt {}))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "global file used when no project file"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-dir2-" (System/currentTimeMillis)))))
          global-file (str tmp "/agent/SYSTEM.md")]
      (io/make-parents global-file)
      (spit global-file "Global prompt")
      (try
        (with-redefs [cfg/prompt-file-candidates (fn [_] [global-file])]
          (t/is (= "Global prompt" (cfg/get-custom-prompt {}))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-get-append-system-prompt
  (t/testing "config value wins"
    (t/is (= "Extra" (cfg/get-append-system-prompt {:append-system-prompt "Extra"}))))
  (t/testing "no config value, no files yields nil"
    (with-redefs [cfg/prompt-file-candidates (fn [_] [])]
      (t/is (nil? (cfg/get-append-system-prompt {}))))))

(t/deftest test-apply-cli-overrides
  (let [base {:model "a" :provider :openai}
        opts {:model "b"
              :provider :anthropic
              :system-prompt "Custom"
              :append-system-prompt ["One" "Two"]}]
    (t/is (= "b" (:model (cfg/apply-cli-overrides base opts))))
    (t/is (= :anthropic (:provider (cfg/apply-cli-overrides base opts))))
    (t/is (= "Custom" (:system-prompt (cfg/apply-cli-overrides base opts))))
    (t/testing "repeatable append-system-prompt joins with newlines (pi)"
      (t/is (= "One\n\nTwo" (:append-system-prompt (cfg/apply-cli-overrides base opts)))))
    (t/testing "absent keys pass through untouched"
      (t/is (= base (cfg/apply-cli-overrides base {}))))))

;; ─── hide-thinking-block (pi: hideThinkingBlock in settings.json) ─────────

(t/deftest test-get-hide-thinking-block
  (t/is (false? (cfg/get-hide-thinking-block {})))
  (t/is (false? (cfg/get-hide-thinking-block {:hide-thinking-block false})))
  (t/is (true? (cfg/get-hide-thinking-block {:hide-thinking-block true}))))

(t/deftest test-get-tool-display-mode
  (t/is (= :collapsed (cfg/get-tool-display-mode {})) "missing → default")
  (t/is (= :collapsed (cfg/get-tool-display-mode {:tool-display-mode :collapsed})))
  (t/is (= :expanded (cfg/get-tool-display-mode {:tool-display-mode :expanded})))
  (t/is (= :quiet (cfg/get-tool-display-mode {:tool-display-mode :quiet})))
  (t/is (= :collapsed (cfg/get-tool-display-mode {:tool-display-mode :bogus})) "invalid → default")
  (t/is (= :collapsed (cfg/get-tool-display-mode {:tool-display-mode "quiet"})) "string → default"))

(t/deftest test-get-enabled-models
  (t/is (nil? (cfg/get-enabled-models {})))
  (t/is (= ["a" "b"] (cfg/get-enabled-models {:enabled-models ["a" "b"]}))))

(t/deftest test-get-enabled-models-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-enabled-models-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the file (pi: mutable settings object)"
          (spit settings-file "{:enabled-models [\"a\"]}\n")
          (t/is (= ["a"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "missing file falls back to the config value"
          (fs/delete-tree tmp)
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "file without the key falls back to the config (project override)"
          (fs/create-dirs tmp)
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "unreadable file falls back to the config value"
          (fs/create-dirs tmp)
          (spit settings-file "not-edn[")
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]})))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-get-setting-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-setting-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the persisted key"
          (spit settings-file "{:http-idle-timeout-ms 60000}\n")
          (t/is (= 60000 (cfg/get-setting-live {:http-idle-timeout-ms 300000}
                                               :http-idle-timeout-ms 300000))))
        (t/testing "missing file/key falls back to config, then default"
          (fs/delete-tree tmp)
          (t/is (= 120000 (cfg/get-setting-live {:http-idle-timeout-ms 120000}
                                                :http-idle-timeout-ms 0)))
          (t/is (true? (cfg/get-setting-live {} :auto-compact true)))))
      (finally (when (fs/exists? tmp) (fs/delete-tree tmp))))))

(t/deftest test-get-show-cache-miss-notices
  (t/testing "config-snapshot fallback (default off)"
    (t/is (false? (cfg/get-show-cache-miss-notices {})))
    (t/is (true? (cfg/get-show-cache-miss-notices {:show-cache-miss-notices true})))))

(t/deftest test-ui-padding-getters
  (t/testing "editor padding clamps to pi's 0..3"
    (t/is (= 0 (cfg/get-editor-padding-x {})))
    (t/is (= 2 (cfg/get-editor-padding-x {:editor-padding-x 2})))
    (t/is (= 3 (cfg/get-editor-padding-x {:editor-padding-x 9})))
    (t/is (= 0 (cfg/get-editor-padding-x {:editor-padding-x -1}))))
  (t/testing "autocomplete max items clamps to pi's 3..20"
    (t/is (= 5 (cfg/get-autocomplete-max-visible {})))
    (t/is (= 3 (cfg/get-autocomplete-max-visible {:autocomplete-max-visible 1})))
    (t/is (= 20 (cfg/get-autocomplete-max-visible {:autocomplete-max-visible 50}))))
  (t/testing "output pad is 0 or 1"
    (t/is (= 1 (cfg/get-output-pad {})))
    (t/is (= 0 (cfg/get-output-pad {:output-pad 0})))
    (t/is (= 1 (cfg/get-output-pad {:output-pad 7})))))

(t/deftest test-image-display-getters
  (t/testing "show-images defaults to true (pi: terminal.showImages)"
    (t/is (true? (cfg/get-show-images {})))
    (t/is (true? (cfg/get-show-images cfg/default-config)))
    (t/is (true? (cfg/get-show-images {:terminal {:show-images true}})))
    (t/is (false? (cfg/get-show-images {:terminal {:show-images false}})))
    (t/is (true? (cfg/get-show-images {:terminal {:show-images nil}}))
          "an explicit nil falls back to the default"))
  (t/testing "image width defaults to 60 and clamps to >= 1 (pi: terminal.imageWidthCells)"
    (t/is (= 60 (cfg/get-image-width-cells {})))
    (t/is (= 60 (cfg/get-image-width-cells cfg/default-config)))
    (t/is (= 80 (cfg/get-image-width-cells {:terminal {:image-width-cells 80}})))
    (t/is (= 1 (cfg/get-image-width-cells {:terminal {:image-width-cells 0}})))
    (t/is (= 60 (cfg/get-image-width-cells {:terminal {:image-width-cells "wide"}}))
          "non-numeric values fall back to the default")))

(t/deftest test-get-block-images
  (t/testing "block-images defaults to false (pi: images.blockImages)"
    (t/is (false? (cfg/get-block-images {})))
    (t/is (false? (cfg/get-block-images cfg/default-config)))
    (t/is (false? (cfg/get-block-images {:images {:block-images false}})))
    (t/is (true? (cfg/get-block-images {:images {:block-images true}})))))

(t/deftest test-get-tree-filter-mode
  (t/is (= :default (cfg/get-tree-filter-mode {})))
  (t/is (= :no-tools (cfg/get-tree-filter-mode {:tree-filter-mode :no-tools})))
  (t/is (= :all (cfg/get-tree-filter-mode {:tree-filter-mode :all})))
  (t/is (= :default (cfg/get-tree-filter-mode {:tree-filter-mode :bogus}))
        "invalid values fall back to :default"))

;; ─── HTTP transport (pi: no counterpart — kmet's transport knob) ──────────

(t/deftest test-get-http-transport
  (t/is (= :platform (cfg/get-http-transport {}))
        "defaults to :platform")
  (t/is (= :platform (cfg/get-http-transport cfg/default-config)))
  (t/is (= :curl (cfg/get-http-transport {:http-transport :curl})))
  (t/is (= :platform (cfg/get-http-transport {:http-transport :platform})))
  (t/is (= :platform (cfg/get-http-transport {:http-transport :bogus}))
        "invalid values fall back to :platform")
  (t/is (= :platform (cfg/get-http-transport {:http-transport nil}))
        "nil falls back to :platform"))

(t/deftest test-load-config-applies-http-transport
  ;; load-config is the startup choke point — it applies the merged
  ;; :http-transport to the runtime knob (kmet.libs.http/set-transport!)
  (try
    (http/set-transport! :curl)
    (cfg/load-config :no-env? true :no-settings? true)
    (t/is (= :platform (http/get-transport))
          "default config reapplies :platform")
    (http/set-transport! :platform)
    (with-redefs-fn {#'cfg/load-edn-file (fn [_] {:http-transport :curl})}
      (fn []
        (cfg/load-config :no-env? true)
        (t/is (= :curl (http/get-transport))
              "user :http-transport :curl is applied")))
    (finally (http/set-transport! :platform))))

(t/deftest test-get-retry-settings
  (t/testing "defaults when :retry is absent"
    (t/is (= {:enabled true :max-retries 3 :base-delay-ms 2000}
             (cfg/get-retry-settings {}))))
  (t/testing "partial :retry maps merge with defaults (deep-merged config)"
    (t/is (= {:enabled false :max-retries 5 :base-delay-ms 2000}
             (cfg/get-retry-settings {:retry {:enabled false :max-retries 5}})))
    (t/is (= {:enabled true :max-retries 0 :base-delay-ms 500}
             (cfg/get-retry-settings {:retry {:max-retries 0 :base-delay-ms 500}}))
          "0 is a valid max-retries (off)")))

(t/deftest test-get-retry-settings-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-retry-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the file :retry block"
          (spit settings-file "{:retry {:enabled false :max-retries 10}}\n")
          (t/is (= {:enabled false :max-retries 10 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "missing file falls back to the config value"
          (fs/delete-tree tmp)
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "file without :retry falls back to the config (project override)"
          (fs/create-dirs tmp)
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "unreadable file falls back to the config value"
          (fs/create-dirs tmp)
          (spit settings-file "not-edn[")
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}})))))
      (finally (fs/delete-tree tmp)))))

;; ─── Loop guard (repeat-loop circuit breaker) ─────────────────────────────

(t/deftest test-get-loop-guard-settings
  (t/testing "defaults when :loop-guard is absent"
    (t/is (= {:enabled true :threshold 3}
             (cfg/get-loop-guard-settings {}))))
  (t/testing "partial :loop-guard maps merge with defaults"
    (t/is (= {:enabled false :threshold 3}
             (cfg/get-loop-guard-settings {:loop-guard {:enabled false}})))
    (t/is (= {:enabled true :threshold 5}
             (cfg/get-loop-guard-settings {:loop-guard {:threshold 5}}))
          "threshold passes through"))
  (t/testing "threshold clamps to >= 2"
    (t/is (= {:enabled true :threshold 2}
             (cfg/get-loop-guard-settings {:loop-guard {:threshold 1}}))
          "a 1-call threshold is too aggressive — clamped")))

(t/deftest test-get-loop-guard-settings-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-lg-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the file :loop-guard block"
          (spit settings-file "{:loop-guard {:enabled false :threshold 5}}\n")
          (t/is (= {:enabled false :threshold 5}
                   (cfg/get-loop-guard-settings-live {:loop-guard {:threshold 3}}))))
        (t/testing "missing file falls back to the config value"
          (fs/delete-tree tmp)
          (t/is (= {:enabled true :threshold 3}
                   (cfg/get-loop-guard-settings-live {:loop-guard {:threshold 3}}))))
        (t/testing "file without :loop-guard falls back to the config (project override)"
          (fs/create-dirs tmp)
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (= {:enabled true :threshold 3}
                   (cfg/get-loop-guard-settings-live {:loop-guard {:threshold 3}})))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-set-enabled-models!
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-enabled-models-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (cfg/set-enabled-models! ["opencode-go/deepseek-v4-flash"])
        (t/is (= {:enabled-models ["opencode-go/deepseek-v4-flash"]}
                 (edn/read-string (slurp settings-file))))
        (t/testing "nil removes the filter (all enabled)"
          (cfg/set-enabled-models! nil)
          (t/is (= {:enabled-models nil} (edn/read-string (slurp settings-file))))
          (t/is (nil? (cfg/get-enabled-models (edn/read-string (slurp settings-file)))))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-set-hide-thinking-block!
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "writes to global settings file, merging existing keys"
          (spit settings-file "{:provider :openai}\n")
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:provider :openai :hide-thinking-block true}
                   (edn/read-string (slurp settings-file))))
          (t/testing "second toggle updates the same key"
            (cfg/set-hide-thinking-block! false)
            (t/is (= {:provider :openai :hide-thinking-block false}
                     (edn/read-string (slurp settings-file))))))
        (t/testing "creates the file when missing"
          (fs/delete-tree tmp)
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:hide-thinking-block true} (edn/read-string (slurp settings-file)))))
        (t/testing "non-map file content is replaced, not merged"
          (spit settings-file "[1 2 3]\n")
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:hide-thinking-block true} (edn/read-string (slurp settings-file))))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-concurrent-setting-saves
  (t/testing "lock serializes writes — no lost update (pi: proper-lockfile)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-lock-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (let [futs (doall (for [[k v] [[:hide-thinking-block true] [:provider :anthropic]]]
                              (future (cfg/save-setting! k v))))]
            (doseq [f futs] @f))
          (t/is (= {:hide-thinking-block true :provider :anthropic}
                   (edn/read-string (slurp settings-file)))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-show-terminal-progress-setting
  ;; the getter live-reads the global settings file — pin the path so a
  ;; developer's own settings.edn cannot flip the expectation
  (let [missing (str (fs/absolutize (fs/file "target" (str "test-show-terminal-progress-missing-"
                                                           (System/currentTimeMillis))))
                     "/settings.edn")]
    (with-redefs [cfg/global-settings-path (fn [] missing)]
      (t/testing "show-terminal-progress defaults off (pi: showTerminalProgress
             default false), env-overridable, and settable"
        (t/is (false? (cfg/get-show-terminal-progress
                       (assoc cfg/default-config :show-terminal-progress nil)))
              "unset → false")
        (t/is (true? (cfg/get-show-terminal-progress
                      (assoc cfg/default-config :show-terminal-progress true)))
              "explicit true")
        (t/is (false? (cfg/get-show-terminal-progress
                       (assoc cfg/default-config :show-terminal-progress false)))
              "explicit false")))))

(t/deftest test-clear-on-shrink-setting
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-clear-on-shrink-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "defaults off (pi: terminal.clearOnShrink), env-overridable"
          (t/is (false? (cfg/get-clear-on-shrink cfg/default-config))))
        (t/testing "the config snapshot seeds the value"
          (t/is (true? (cfg/get-clear-on-shrink {:terminal {:clear-on-shrink true}}))))
        (t/testing "the persisted setting wins over the snapshot"
          (spit settings-file "{:terminal {:clear-on-shrink false}}\n")
          (t/is (false? (cfg/get-clear-on-shrink {:terminal {:clear-on-shrink true}}))))
        (t/testing "a sibling :terminal key does not shadow the snapshot"
          (spit settings-file "{:terminal {:show-images false}}\n")
          (t/is (true? (cfg/get-clear-on-shrink {:terminal {:clear-on-shrink true}}))))
        (t/testing "the setter persists under [:terminal :clear-on-shrink]"
          (spit settings-file "{:terminal {:show-images false}}\n")
          (cfg/set-clear-on-shrink! true)
          (t/is (true? (get-in (edn/read-string (slurp settings-file))
                               [:terminal :clear-on-shrink])))
          (t/is (false? (get-in (edn/read-string (slurp settings-file))
                                [:terminal :show-images]))
                "sibling :terminal keys survive the nested merge")))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-enable-skill-commands-setting
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-enable-skill-commands-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "defaults on (pi: enableSkillCommands default true)"
          (t/is (true? (cfg/get-enable-skill-commands cfg/default-config))))
        (t/testing "the persisted setting wins"
          (spit settings-file "{:enable-skill-commands false}\n")
          (t/is (false? (cfg/get-enable-skill-commands cfg/default-config))))
        (t/testing "config snapshot fallback (project override)"
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (false? (cfg/get-enable-skill-commands {:enable-skill-commands false}))))
        (t/testing "the setter persists"
          (cfg/set-enable-skill-commands! false)
          (t/is (false? (:enable-skill-commands (edn/read-string (slurp settings-file)))))))
      (finally (fs/delete-tree tmp)))))

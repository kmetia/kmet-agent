(ns kmet.extensions.mcp-adapter.config-test
  "Config loading (§6.1-6.6, §12.4): key normalization, the global →
   project precedence merge, the url-bound credential rule, the template,
   the enable/disable write, direct-tools persistence, host-config
   extraction/adoption and the known-server presets.

   Every path is redirected into a temp agent dir (tu/with-agent-dir); the
   real ~/.kmet/agent/mcp.edn is never read or written."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.json :as json]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Key normalization + precedence merge ─────────────────────────────────

(deftest camel-keys-and-precedence
  (tu/with-agent-dir
    (fn [{:keys [agent project]}]
      (let [global (config/global-config-path)]
        (is (= (str (fs/path agent "mcp.edn")) global))
        (spit global
              "{:settings {:directTools true :toolPrefix \"server\" :disableProxyTool false}
                :mcpServers {\"alpha\" {:command \"npx\" :args [\"-y\" \"pkg\"] :lifecycle \"lazy\"
                                        :requestTimeoutMs 90000}
                             \"shared\" {:url \"https://a.example/mcp\" :headers {\"X-A\" \"1\"}
                                         :auth :bearer :bearerToken \"tok-a\"}}}")
        (let [cfg (config/load-config)]
          (testing "camel keys normalize to kebab"
            (is (true? (get-in cfg [:settings :direct-tools])))
            (is (= :server (get-in cfg [:settings :tool-prefix])))
            (is (= 90000 (get-in cfg [:mcp-servers "alpha" :request-timeout-ms]))))
          (testing "string keyword-values normalize to keywords"
            (is (= :lazy (get-in cfg [:mcp-servers "alpha" :lifecycle])))))
        (spit project "{:mcp-servers {\"alpha\" {:args [\"-y\" \"other\"]}
                                     \"beta\" {:command \"bb\" :lifecycle :eager}}}")
        (let [cfg (config/load-config)]
          (testing "the project file merges per field"
            (is (= "npx" (get-in cfg [:mcp-servers "alpha" :command])))
            (is (= ["-y" "other"] (get-in cfg [:mcp-servers "alpha" :args])))
            (is (= 90000 (get-in cfg [:mcp-servers "alpha" :request-timeout-ms]))))
          (testing "the project file adds a server"
            (is (contains? (:mcp-servers cfg) "beta"))
            (is (= :eager (get-in cfg [:mcp-servers "beta" :lifecycle])))))))))

(deftest url-change-drops-inherited-auth
  (tu/with-agent-dir
    (fn [{:keys [project]}]
      (spit (config/global-config-path)
            "{:mcpServers {\"shared\" {:url \"https://a.example/mcp\"
                                       :headers {\"X-A\" \"1\"}
                                       :auth :bearer :bearerToken \"tok-a\"}}}")
      (testing "the inherited credentials apply while the url is unchanged"
        (is (= "tok-a" (get-in (config/load-config)
                               [:mcp-servers "shared" :bearer-token]))))
      (spit project "{:mcp-servers {\"shared\" {:url \"https://b.example/mcp\"}}}")
      (let [shared (get-in (config/load-config) [:mcp-servers "shared"])]
        (testing "repointing the url drops headers, tokens and oauth"
          (is (= "https://b.example/mcp" (:url shared)))
          (is (nil? (:headers shared)))
          (is (nil? (:bearer-token shared)))
          (is (nil? (:oauth shared))))))))

(deftest parse-errors-carry-the-path
  (tu/with-agent-dir
    (fn [_]
      (testing "an unparsable config throws with the path"
        (spit (config/global-config-path) "{:mcp-servers")
        (let [e (try (config/load-config) nil (catch Exception e e))]
          (is (some? e))
          (is (= :mcp-config-parse-error (:type (ex-data e))))
          (is (= (config/global-config-path) (:path (ex-data e))))
          (is (str/includes? (ex-message e) "Failed to read MCP config"))))
      (testing "a non-map root throws with the path"
        (spit (config/global-config-path) "[1 2 3]")
        (let [e (try (config/load-config) nil (catch Exception e e))]
          (is (= :mcp-config-parse-error (:type (ex-data e))))
          (is (str/includes? (ex-message e) "root value must be a map"))))
      (testing "unknown keys pass through unmodified"
        (spit (config/global-config-path)
              "{:mcpServers {\"s\" {:command \"bb\" :someFutureKey 1}}}")
        (is (= 1 (get-in (config/load-config) [:mcp-servers "s" :someFutureKey])))))))

(deftest host-config-discovery-is-lowest
  (tu/with-agent-dir
    (fn [_]
      (spit (config/global-config-path)
            "{:settings {:hostConfigDiscovery :on}
              :mcp-servers {\"mine\" {:command \"explicit\"}}}")
      (with-redefs [config/host-configs-config
                    (fn [& _]
                      {:mcp-servers {"mine" {:command "discovered"}
                                     "theirs" {:command "discovered"}}})]
        (let [cfg (config/load-config)]
          (testing "an explicit EDN definition wins over a discovered one"
            (is (= "explicit" (get-in cfg [:mcp-servers "mine" :command]))))
          (testing "a discovered-only server is merged in"
            (is (= "discovered" (get-in cfg [:mcp-servers "theirs" :command])))))))))

(deftest agent-dir-defaults
  (let [orig @#'config/agent-dir-atom]
    (try
      (config/set-agent-dir! "/tmp/agent-dir-fixture")
      (is (= (str (fs/path "/tmp/agent-dir-fixture" "mcp.edn"))
             (config/global-config-path)))
      (testing "the project path honors an explicit cwd"
        (is (= (str (fs/path "/work" ".kmet" "mcp.edn"))
               (config/project-config-path "/work"))))
      (finally
        (if (some? orig)
          (config/set-agent-dir! orig)
          (reset! @#'config/agent-dir-atom nil))))))

;; ─── Template ─────────────────────────────────────────────────────────────

(deftest template
  (tu/with-agent-dir
    (fn [{:keys [agent]}]
      (testing "the template is created when the file is missing"
        (is (= (str (fs/path agent "mcp.edn")) (config/ensure-global-template!))))
      (testing "the template parses and carries a starter server"
        (let [raw (read-string (slurp (config/global-config-path)))]
          (is (map? raw))
          (is (contains? (:mcp-servers raw) "filesystem"))))
      (testing "an existing file is never re-created"
        (is (nil? (config/ensure-global-template!)))))))

;; ─── Enable / disable write (§6.5) ────────────────────────────────────────

(deftest enable-disable-write
  (tu/with-agent-dir
    (fn [{:keys [project]}]
      (let [{:keys [path changed]} (config/set-server-disabled! "alpha" true)]
        (testing "disable writes the project file"
          (is (= project path))
          (is changed)
          (is (true? (get-in (tu/read-edn-file project)
                             [:mcp-servers "alpha" :disabled])))))
      (testing "disable is idempotent"
        (is (not (:changed (config/set-server-disabled! "alpha" true)))))
      (testing "enable removes the key"
        (is (:changed (config/set-server-disabled! "alpha" false)))
        (is (nil? (get-in (tu/read-edn-file project)
                          [:mcp-servers "alpha" :disabled]))))
      (testing "enable is idempotent"
        (is (not (:changed (config/set-server-disabled! "alpha" false)))))
      (testing "enable writes explicit false when the lower source disables it"
        (spit (config/global-config-path)
              "{:mcp-servers {\"alpha\" {:command \"x\" :disabled true}}}")
        (spit project "{:mcp-servers {\"alpha\" {:command \"x\"}}}")
        (let [{:keys [changed]} (config/set-server-disabled! "alpha" false)]
          (is changed)
          (is (false? (get-in (tu/read-edn-file project)
                              [:mcp-servers "alpha" :disabled])))))
      (testing "enabling an entry that becomes empty removes the server key"
        (spit project "{:mcp-servers {\"ghost\" {:disabled true}}}")
        (let [{:keys [changed]} (config/set-server-disabled! "ghost" false)]
          (is changed)
          (is (nil? (get-in (tu/read-edn-file project) [:mcp-servers "ghost"]))))))))

(deftest enable-disable-rejects-a-non-map-entry
  (tu/with-agent-dir
    (fn [{:keys [project]}]
      (spit project "{:mcp-servers {\"bad\" [1 2]}}")
      (let [e (try (config/set-server-disabled! "bad" true) nil (catch Exception e e))]
        (is (= :mcp-config-parse-error (:type (ex-data e))))
        (is (str/includes? (ex-message e) "must be a map"))))))

;; ─── Direct-tools persistence (§6.6) ─────────────────────────────────────

(deftest write-direct-tools
  (tu/with-agent-dir
    (fn [{:keys [project]}]
      (testing "true, false and a name list persist per server"
        (let [{:keys [changed]} (config/write-direct-tools!
                                 {"a" true "b" false "c" ["t1" "t2"]})]
          (is changed)
          (is (= {"a" true "b" false "c" ["t1" "t2"]}
                 (update-vals (get (tu/read-edn-file project) :mcp-servers)
                              :direct-tools)))))
      (testing "the write is a no-op with no changes"
        (is (not (:changed (config/write-direct-tools! {})))))
      (testing "the project file keeps unrelated content"
        (tu/write-edn! project {:mcp-servers {"a" {:command "x"}}})
        (config/write-direct-tools! {"b" true})
        (let [raw (tu/read-edn-file project)]
          (is (= {:command "x"} (get-in raw [:mcp-servers "a"])))
          (is (true? (get-in raw [:mcp-servers "b" :direct-tools]))))))))

;; ─── Host-config extraction (pi extractServers) ───────────────────────────

(deftest extract-host-servers
  (let [dir (tu/temp-dir! "host-cfg")]
    (try
      (let [path (str (fs/path dir "mcp.json"))]
        (spit path (json/generate-string
                    {:mcpServers {"host-git" {:command "npx -y server-git"}
                                  "host-web" {:url "https://h/mcp"}
                                  "host-bad" {:type "http"}
                                  "host-vec" {:command ["npx" "-y" "pkg"]}}}))
        (let [servers (config/extract-host-servers path)]
          (testing "a string command splits into command + args"
            (is (= {:command "npx" :args ["-y" "server-git"]}
                   (get servers "host-git"))))
          (testing "a url-only entry passes through"
            (is (= "https://h/mcp" (get-in servers ["host-web" :url]))))
          (testing "a vector command passes through"
            (is (= ["npx" "-y" "pkg"] (get-in servers ["host-vec" :command]))))
          (testing "an entry with neither command nor url is dropped"
            (is (not (contains? servers "host-bad"))))))
      (testing "a missing or unparsable file yields no servers"
        (is (= {} (config/extract-host-servers (str (fs/path dir "nope.json")))))
        (spit (str (fs/path dir "broken.json")) "{not json")
        (is (= {} (config/extract-host-servers (str (fs/path dir "broken.json"))))))
      (finally (tu/delete-tree! dir)))))

(deftest host-config-discovery-and-adoption
  (tu/with-agent-dir
    (fn [_]
      (let [dir (tu/temp-dir! "host-disc")
            vscode (str (fs/path dir ".vscode" "mcp.json"))
            vscode-discoveries (fn []
                                 (filter #(= :vscode (:kind %))
                                         (config/host-config-discoveries dir)))]
        (try
          (fs/create-dirs (fs/parent vscode))
          (spit vscode (json/generate-string
                        {:mcpServers {"host-git" {:command "npx -y server-git"}
                                      "host-web" {:url "https://h/mcp"}
                                      "host-bad" {:type "http"}}}))
          (testing "the vscode host file is discovered with its server count"
            (let [discoveries (vscode-discoveries)]
              (is (= 1 (count discoveries)))
              (is (= 2 (:server-count (first discoveries))))
              (is (= (str (fs/canonicalize vscode))
                     (str (fs/canonicalize (:path (first discoveries))))))))
          (testing "extracted host servers are keyed by string name, like EDN"
            (let [servers (config/extract-host-servers
                           (str (fs/path dir ".vscode" "mcp.json")))]
              (is (= #{"host-git" "host-web"}
                     (set (keys servers))))
              (is (= {:command "npx" :args ["-y" "server-git"]}
                     (get servers "host-git")))))
          (testing "adoption adds the discovered servers"
            (let [{:keys [added skipped]} (config/adopt-host-configs! (vscode-discoveries))]
              (is (= #{"host-git" "host-web"} (set added)))
              (is (empty? skipped))))
          (testing "adoption is idempotent — the project entry now wins"
            (let [{:keys [added skipped]} (config/adopt-host-configs! (vscode-discoveries))]
              (is (empty? added))
              (is (= #{"host-git" "host-web"} (set skipped)))))
          (testing "the adopted entries load as normalized EDN servers"
            (let [cfg (config/load-config)
                  git (get-in cfg [:mcp-servers "host-git"])]
              (is (= "npx" (:command git)))
              (is (= ["-y" "server-git"] (:args git)))
              (is (= "https://h/mcp" (get-in cfg [:mcp-servers "host-web" :url])))))
          (testing "write-server-entry! replaces one project entry"
            (is (:changed (config/write-server-entry! "custom" {:command "x"})))
            (is (= {:command "x"}
                   (get-in (config/load-config) [:mcp-servers "custom"])))
            (is (not (:changed (config/write-server-entry! "custom" {:command "x"})))))
          (finally (tu/delete-tree! dir)))))))

;; ─── Phase-2 keys ─────────────────────────────────────────────────────────

(deftest phase-2-config-keys
  (tu/with-agent-dir
    (fn [_]
      (tu/write-edn! (config/global-config-path)
                     {:mcp-servers
                      {"srv" {:command "npx"
                              :includeTools ["srv_*"]
                              :excludeTools ["srv_secret"]
                              :searchKeywords {"srv_*" ["capture" "snapshot"]}
                              :idleTimeout 5
                              :exposeResources false
                              :outputGuard {:maxBytes 1024 :maxLines 10}
                              :scriptMode true}}})
      (let [definition (get-in (config/load-config) [:mcp-servers "srv"])]
        (testing "camel include/exclude normalize"
          (is (= ["srv_*"] (:include-tools definition)))
          (is (= ["srv_secret"] (:exclude-tools definition))))
        (testing "searchKeywords and the scalar options normalize"
          (is (= ["capture" "snapshot"] (get-in definition [:search-keywords "srv_*"])))
          (is (= 5 (:idle-timeout definition)))
          (is (false? (:expose-resources definition))))
        (testing "a nested output-guard map normalizes its keys"
          (is (= 1024 (get-in definition [:output-guard :max-bytes])))
          (is (= 10 (get-in definition [:output-guard :max-lines]))))
        (testing "script-mode reads as a boolean regardless of spelling"
          (is (true? (:script-mode definition))))))))

(deftest known-server-presets
  (testing "the presets the setup panel offers are the documented five"
    (is (= #{"deepwiki" "context7" "notion" "github" "chrome-devtools"}
           (set (map :id config/known-server-presets)))))
  (testing "every preset carries a name, a summary and a usable entry"
    (doseq [{:keys [id name summary entry]} config/known-server-presets]
      (is (string? name) id)
      (is (string? summary) id)
      (is (or (:url entry) (:command entry)) id))))

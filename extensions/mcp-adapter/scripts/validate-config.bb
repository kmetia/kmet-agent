#!/usr/bin/env bb
;; Config + extension-load validation for the mcp-adapter extension (§12.4,
;; §12.6): precedence merge (global → project), camel/kebab normalization,
;; template creation, enable/disable write round-trip (incl. the
;; lower-source-disabled case), metadata cache, and loading the entry
;; against create-nullable-api (proxy tool, /mcp command, event
;; registrations, register-skill! skill).
;;
;; Config paths are redirected to temp files via with-redefs — the real
;; ~/.kmet/agent/mcp.edn is never touched.
(require '[clojure.string :as str]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[kmet.libs.json :as json]
         '[kmet.extension :as ext]
         '[kmet.extensions.mcp-adapter.config :as config]
         '[kmet.extensions.mcp-adapter.metadata :as metadata]
         '[kmet.extensions.mcp-adapter.core :as mcp])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn temp-file [name]
  (str (System/getProperty "user.dir") "/.mcp-" name "-" (System/nanoTime) ".edn"))

(defn with-redirected-config
  "Run F with the global + project config paths redirected to temp files
   (deleted afterwards)."
  [f]
  (let [global (temp-file "global")
        project (temp-file "project")]
    (with-redefs [config/global-config-path (delay global)
                  config/project-config-path (fn [& _] project)]
      (try
        (f global project)
        (finally
          (io/delete-file global true)
          (io/delete-file project true))))))

;; ─── key normalization + precedence merge ─────────────────────────────────

(defn test-config [global project]
  (println "\n── config ──")
  ;; global: camel keys, string keyword-values
  (spit global
        "{:settings {:directTools true :toolPrefix \"server\" :disableProxyTool false}
          :mcpServers {\"alpha\" {:command \"npx\" :args [\"-y\" \"pkg\"] :lifecycle \"lazy\"
                                  :requestTimeoutMs 90000}
                       \"shared\" {:url \"https://a.example/mcp\" :headers {\"X-A\" \"1\"}
                                   :auth :bearer :bearerToken \"tok-a\"}}}")
  (let [cfg (config/load-config)]
    (check "camel keys normalized"
           (and (true? (get-in cfg [:settings :direct-tools]))
                (= :server (get-in cfg [:settings :tool-prefix]))
                (= 90000 (get-in cfg [:mcp-servers "alpha" :request-timeout-ms]))))
    (check "string keyword-values normalized"
           (= :lazy (get-in cfg [:mcp-servers "alpha" :lifecycle]))))

  ;; project overrides per-field
  (spit project "{:mcp-servers {\"alpha\" {:args [\"-y\" \"other\"]}
                               \"beta\" {:command \"bb\" :lifecycle :eager}}}")
  (let [cfg (config/load-config)]
    (check "project per-field merge"
           (and (= "npx" (get-in cfg [:mcp-servers "alpha" :command]))
                (= ["-y" "other"] (get-in cfg [:mcp-servers "alpha" :args]))
                (= 90000 (get-in cfg [:mcp-servers "alpha" :request-timeout-ms]))
                (= :eager (get-in cfg [:mcp-servers "beta" :lifecycle]))))
    (check "project adds server" (contains? (:mcp-servers cfg) "beta")))

  ;; url change drops url-bound auth material (pi SECURITY note)
  (spit project "{:mcp-servers {\"shared\" {:url \"https://b.example/mcp\"}}}")
  (let [cfg (config/load-config)
        shared (get-in cfg [:mcp-servers "shared"])]
    (check "url change drops inherited auth"
           (and (nil? (:headers shared))
                (nil? (:bearer-token shared))
                (nil? (:oauth shared))))))

;; ─── template + enable/disable write ──────────────────────────────────────

(defn test-template-and-writes [global project]
  (println "\n── template + enable/disable ──")
  (io/delete-file global true)
  (check "template created when missing" (config/ensure-global-template!))
  (check "template not re-created" (nil? (config/ensure-global-template!)))
  (io/delete-file @config/global-config-path)

  (let [{:keys [path changed]} (config/set-server-disabled! "alpha" true)]
    (check "disable writes project file" (and path changed))
    (let [raw (read-string (slurp project))]
      (check "disable sets :disabled true" (true? (get-in raw [:mcp-servers "alpha" :disabled])))))
  (let [{:keys [changed]} (config/set-server-disabled! "alpha" true)]
    (check "disable idempotent" (not changed)))
  (let [{:keys [changed]} (config/set-server-disabled! "alpha" false)]
    (check "enable removes :disabled" changed))
  (let [{:keys [changed]} (config/set-server-disabled! "alpha" false)]
    (check "enable idempotent" (not changed)))
  ;; enable when the LOWER source has it disabled → writes :disabled false
  (spit @config/global-config-path "{:mcp-servers {\"alpha\" {:command \"x\" :disabled true}}}")
  (spit project "{:mcp-servers {\"alpha\" {:command \"x\"}}}")
  (let [{:keys [changed]} (config/set-server-disabled! "alpha" false)
        raw (read-string (slurp project))]
    (check "enable with lower disabled writes explicit false"
           (and changed (false? (get-in raw [:mcp-servers "alpha" :disabled])))))
  ;; enable on an entry that becomes empty → key removed
  (spit project "{:mcp-servers {\"ghost\" {:disabled true}}}")
  (let [{:keys [changed]} (config/set-server-disabled! "ghost" false)
        raw (read-string (slurp project))]
    (check "enable empty entry removes key"
           (and changed (nil? (get-in raw [:mcp-servers "ghost"]))))))

;; ─── metadata cache ───────────────────────────────────────────────────────

(defn test-metadata [_global _project]
  (println "\n── metadata cache ──")
  (let [definition {:command "npx" :args ["-y" "x"]}
        settings {}
        _ (metadata/update-entry! nil "srv" definition settings
                                  [{:name "tool-a" :description "d" :inputSchema {}}]
                                  [{:name "brief" :description "b"
                                    :arguments [{:name "topic" :required true}]}]
                                  [{:name "README" :uri "file:///r"}])]
    (check "cache round-trip"
           (some? (metadata/server-entry (metadata/load-cache) "srv" definition settings)))
    (check "cache stale on config change"
           (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                        {:command "other"} settings)))
    (check "cache stale on include-tools change"
           (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                        (assoc definition :include-tools ["a*"])
                                        settings)))
    (check "prompts cached"
           (= "brief" (get-in (metadata/load-cache) [:servers "srv" :prompts 0 :name])))
    (check "resources cached"
           (= "README" (get-in (metadata/load-cache) [:servers "srv" :resources 0 :name])))
    (check "all-tools"
           (= [{:server "srv" :tool {:name "tool-a"}}]
              (mapv #(update % :tool select-keys [:name])
                    (metadata/all-tools (metadata/load-cache)
                                        {:mcp-servers {"srv" definition}} settings))))))

;; ─── Phase 2 config: include/exclude, keywords, host adoption ─────────────

(defn test-phase2-config [global _project]
  (println "\n── phase-2 config ──")
  (spit global (pr-str {:mcp-servers
                        {"srv" {:command "npx"
                                :includeTools ["srv_*"]
                                :excludeTools ["srv_secret"]
                                :searchKeywords {"srv_*" ["capture" "snapshot"]}
                                :idleTimeout 5
                                :exposeResources false}}}))
  (let [cfg (config/load-config)
        defn (get-in cfg [:mcp-servers "srv"])]
    (check "camel include/exclude normalized"
           (and (= ["srv_*"] (:include-tools defn))
                (= ["srv_secret"] (:exclude-tools defn))))
    (check "searchKeywords normalized"
           (= ["capture" "snapshot"] (get-in defn [:search-keywords "srv_*"])))
    (check "idleTimeout normalized" (= 5 (:idle-timeout defn)))
    (check "exposeResources normalized" (false? (:expose-resources defn))))
  ;; host config discovery + adoption (fake vscode file — relative to cwd,
  ;; unlike cursor/claude which live under the home dir)
  (let [host-path (str (System/getProperty "user.dir") "/.vscode/mcp.json")]
    (try
      (fs/create-dirs (fs/parent host-path))
      (spit host-path (json/generate-string
                       {:mcpServers {"host-git" {:command "npx -y server-git"}
                                     "host-web" {:url "https://h/mcp"}
                                     "host-bad" {:type "http"}}}))
      (let [discoveries (config/host-config-discoveries)]
        (check "host discovery"
               (and (= 1 (count discoveries))
                    (= :vscode (:kind (first discoveries)))
                    (= 2 (:server-count (first discoveries))))))
      (let [{:keys [added skipped]} (config/adopt-host-configs!)]
        (check "adopt adds new servers"
               (= #{"host-git" "host-web"} (set added)))
        (check "adopt skips none first time" (empty? skipped))
        (let [{:keys [added skipped]} (config/adopt-host-configs!)]
          (check "adopt idempotent"
                 (and (empty? added) (= #{"host-git" "host-web"} (set skipped)))))
        (check "adopt wrote project file"
               (let [cfg (config/load-config)
                     git (get-in cfg [:mcp-servers "host-git"])]
                 (and (= "npx" (:command git))
                      (= ["-y" "server-git"] (:args git))
                      (= "https://h/mcp" (get-in cfg [:mcp-servers "host-web" :url]))))))
      ;; write-server-entry! (setup panel save path)
      (let [{:keys [changed]} (config/write-server-entry! "custom" {:command "x"})]
        (check "write-server-entry!"
               (and changed
                    (= {:command "x"} (get-in (config/load-config)
                                              [:mcp-servers "custom"])))))
      (finally
        (io/delete-file host-path true)
        (fs/delete-tree (fs/parent host-path)))))
  ;; presets
  (check "known presets"
         (= #{"deepwiki" "context7" "notion" "github" "chrome-devtools"}
            (set (map :id config/known-server-presets)))))

;; ─── extension load against the nullable api ──────────────────────────────

(defn test-extension-load []
  (println "\n── extension load (nullable api) ──")
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (mcp/init api)
    (let [s @state]
      (check "proxy tool registered" (contains? (:tools s) "mcp"))
      (check "mcp command registered" (contains? (:commands s) "mcp"))
      (check "session-start handler" (seq (get-in s [:handlers :session-start])))
      (check "session-shutdown handler" (seq (get-in s [:handlers :session-shutdown])))
      (check "no resources-discover handler (skills self-register)"
             (empty? (get-in s [:handlers :resources-discover])))
      (let [skill (first (:skills s))]
        (check "mcp skill self-registered"
               (and (some? skill)
                    (= "mcp-adapter:skills/mcp/SKILL.md"
                       (get-in skill [:opts :location]))
                    (str/includes? (str (:content skill)) "name: mcp"))))
      (let [cmd (get-in s [:commands "mcp"])]
        (check "mcp command completions"
               (let [items ((:get-argument-completions cmd) "con")]
                 (and (seq items) (= "connect" (:value (first items))))))
        (check "mcp status handler runs"
               (str/includes? (with-out-str
                                ;; kmet dispatch contract: (ctx args)
                                ((:handler cmd) {:has-ui false} ""))
                              "settings:")))
      (mcp/shutdown api)
      (mcp/shutdown api)
      (check "shutdown idempotent" true))))

(with-redirected-config
  (fn [global project]
    (test-config global project)
    (test-template-and-writes global project)
    (test-metadata global project)
    (test-phase2-config global project)))
(test-extension-load)
(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))

#!/usr/bin/env bb
;; Scripted-MCP end-to-end validation (§12.6 Phase 2, script.md T2): load the
;; extension against create-nullable-api with a config pointing at the fake
;; stdio server, bridge the contributed MCP tool source into the real
;; registry, then drive kmet's builtin `script` tool: discovery, calls,
;; error results, the gate, timeout, streaming, and the call trace.
;;
;; The adapter no longer ships its own runtime — `mcpScript` retired into the
;; shared script engine (extensions/mcp-adapter/src/extensions/mcp_adapter/
;; tool_source.clj). What this validates is exactly that wiring: the catalog
;; in the sandbox surface, calls through proxy/call-mcp-tool, and the
;; extension's :script-mode gate.
;;
;; Usage: bb -cp ../../src:src scripts/validate-script.bb scripts/fake-mcp-server.bb
;; (the script adds org.clojure/data.json from ~/.m2 to the classpath when the
;; runner didn't already include it)
(require '[babashka.classpath :as bcp]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; kmet.libs.json needs org.clojure/data.json; a bare `bb -cp ../../src:src`
;; (the documented invocation) doesn't resolve bb.edn deps, so when the
;; namespace isn't loadable, add the version the root bb.edn pins (2.4.0 —
;; 2.5.x uses definterface, which babashka's native image rejects) from the
;; local Maven cache.
(defn- ensure-data-json! []
  (try (require 'clojure.data.json)
       (catch Exception _
         (let [base (str (fs/home) "/.m2/repository/org/clojure/data.json")
               jar (or (first (fs/glob (str base "/2.4.0") "*.jar"))
                       (last (sort (fs/glob base "**/*.jar"))))]
           (when jar (bcp/add-classpath (str jar)))
           (try (require 'clojure.data.json)
                (catch Exception _
                  (println "org.clojure/data.json is not loadable and no usable jar was found in ~/.m2.")
                  (println "Re-run with one added to the classpath, e.g.:")
                  (println "  bb -cp ../../src:src:$HOME/.m2/repository/org/clojure/data.json/2.4.0/data.json-2.4.0.jar \\")
                  (println "     scripts/validate-script.bb scripts/fake-mcp-server.bb")
                  (System/exit 2)))))))

(ensure-data-json!)

(require '[kmet.extension :as ext]
         '[kmet.app.tools.registry :as registry]
         '[extensions.mcp-adapter :as mcp]
         '[extensions.mcp-adapter.config :as config])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(def fake-stdio (or (first *command-line-args*)
                    (do (println "Usage: bb validate-script.bb <fake-mcp-server.bb>")
                        (System/exit 2))))

;; temp configs live in TMPDIR (a crashed run must not litter the repo)
(def tmp-root (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))
(def global (str tmp-root "/.mcp-script-g-" (System/nanoTime) ".edn"))
(def project (str tmp-root "/.mcp-script-p-" (System/nanoTime) ".edn"))
(def global-off (str tmp-root "/.mcp-script-off-g-" (System/nanoTime) ".edn"))
(def project-off (str tmp-root "/.mcp-script-off-p-" (System/nanoTime) ".edn"))

;; the fake server is a bare bb child: hand it this process's classpath so it
;; can require kmet.libs.json (it adds the src dir itself)
(spit global (pr-str {:settings {}
                      :mcp-servers {"fake" {:command "bb" :args [fake-stdio]
                                            :env {"BABASHKA_CLASSPATH" (System/getProperty "java.class.path")}}}}))
(spit project "{}\n")
(spit global-off (pr-str {:settings {:script-mode false}
                          :mcp-servers {"fake" {:command "bb" :args [fake-stdio]}}}))
(spit project-off "{}\n")

(defn- script-exec
  "Run CODE through the real script tool; returns the tool result map."
  [code & [{:keys [on-update timeout]}]]
  (registry/execute-tool "script"
                         (cond-> {:code code}
                           timeout (assoc :timeout timeout))
                         (cond-> {}
                           on-update (assoc :on-update on-update))))

(with-redefs [config/global-config-path (fn [] global)
              config/project-config-path (fn [& _] project)]
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (mcp/init api)
    (let [source-fn (get-in @state [:tool-sources :mcp])]
      (check "mcpScript retired (no registered tool)" (nil? (get-in @state [:tools "mcpScript"])))
      (check "tool source registered by the extension" (fn? source-fn))
      ;; bridge the fixture's source into the real registry (the fixture api
      ;; captures registrations; the shared script tool reads the registry)
      (registry/register-tool-source! :mcp source-fn))

    ;; connect the fake server (fills the metadata cache the source reads)
    (let [proxy-tool (get-in @state [:tools "mcp"])
          r ((:execute proxy-tool) {:connect "fake"})]
      (check "proxy connect for cache" (false? (:is-error r)))
      (when (:is-error r) (println "  →" (:content r))))

    (println "\n── script execution over the contributed MCP catalog ──")
    ;; discovery: the cached catalog is in the sandbox surface
    (let [r (script-exec "(let [names (tools/list)] [(boolean (some #{\"fake_echo\"} names)) (boolean (some #{\"script\"} names))])")]
      (check "tools/list carries the MCP catalog, never script"
             (= "[true false]" (:content r))))
    ;; describe returns the contributed record's schema (not :execute)
    (let [r (script-exec "(let [d (tools/describe \"fake_echo\")] [(:name d) (:label d) (contains? d :execute) (str/includes? (pr-str (:parameters d)) \"message\")])")]
      (check "tools/describe carries name/label/parameters, no :execute"
             (= "[\"fake_echo\" \"MCP: echo\" false true]" (:content r))))
    ;; call: deref the promise, branch on the kmet result map
    (let [r (script-exec "@(tools/call \"fake_echo\" {:message \"hi from script\"})")]
      (check "tools/call result"
             (and (not (:is-error r))
                  (str/includes? (:content r) "echo: hi from script"))))
    ;; call error: server marks the result isError
    (let [r (script-exec "@(tools/call \"fake_boom\" {})")]
      (check "MCP error result reaches the script"
             (and (not (:is-error r)) (str/includes? (:content r) ":is-error true"))))
    ;; gate: unknown names never dispatch
    (let [r (script-exec "@(tools/call \"nope\" {})")]
      (check "unknown tool gated"
             (and (not (:is-error r)) (str/includes? (:content r) "not active"))))
    ;; the registry shadows a colliding contribution
    (let [r (script-exec "(:label (tools/describe \"read\"))")]
      (check "registry wins name collisions" (= "Read file" (:content r))))
    ;; stdout + return value ride the shared capture
    (let [r (script-exec "(println \"captured\") (+ 1 2)")]
      (check "stdout + return value"
             (and (not (:is-error r))
                  (str/includes? (:content r) "captured")
                  (str/includes? (:content r) "3"))))
    ;; timeout (:timeout is seconds — bash's unit)
    (let [r (script-exec "(loop [] (recur))" {:timeout 1.5})]
      (check "timeout"
             (and (true? (:is-error r))
                  (= :timeout (get-in r [:details :error]))
                  (str/includes? (:content r) "timed out after 1.5s"))))
    ;; call trace
    (let [r (script-exec "@(tools/call \"fake_add\" {:a 1 :b 2})")]
      (check "details :calls"
             (some (fn [c] (and (= "fake_add" (:tool c)) (true? (:ok c))))
                   (get-in r [:details :calls]))))
    ;; progress notifications stream through the script's on-update
    (let [partials (atom [])
          r (script-exec "@(tools/call \"fake_slow\" {:ms 600})"
                         {:on-update (fn [partial] (swap! partials conj (:content partial)))})]
      (check "inner progress partials arrive" (seq @partials))
      (check "slow call result" (str/includes? (:content r) "slept")))
    ;; removal: the generation bump drops the catalog from the next script
    (registry/unregister-tool-source! :mcp)
    (let [r (script-exec "(tools/list)")]
      (check "unregister removes the catalog"
             (not (str/includes? (:content r) "fake_echo"))))
    (mcp/shutdown api)
    (check "shutdown after scripts" true))

  ;; :script-mode false → the extension contributes nothing
  (with-redefs [config/global-config-path (fn [] global-off)
                config/project-config-path (fn [& _] project-off)]
    (let [{:keys [api state]} (ext/create-nullable-api)]
      (mcp/init api)
      (check ":script-mode false drops the tool source"
             (nil? (get-in @state [:tool-sources :mcp])))
      (mcp/shutdown api))))

(doseq [f [global project global-off project-off]]
  (io/delete-file f true))
(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))

#!/usr/bin/env bb
;; End-to-end smoke: load the extension against the nullable api with a
;; real config pointing at the fake stdio server, then drive the proxy
;; tool: status → search → connect → call → disconnect → status. With the
;; optional second argument (the HTTP fake) it also proves the SEP-2243
;; adapter path: a lazy first call by raw name connects and still mirrors
;; the Mcp-Param-* headers its inputSchema derives (the fake validates
;; them against the body), and the invalid definition is filtered out of
;; the catalog the adapter serves.
;; Usage: bb e2e.bb <fake-mcp-server.bb> [fake-http-mcp-server.bb]
(require '[babashka.process :as proc]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[kmet.extension :as ext]
         '[kmet.extensions.mcp-adapter.client :as client]
         '[kmet.extensions.mcp-adapter.core :as mcp]
         '[kmet.extensions.mcp-adapter.config :as config]
         '[kmet.extensions.mcp-adapter.metadata :as metadata])

(def failures (atom 0))
(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn- start-http-server!
  "Start the HTTP fake; it prints 'PORT <n>' on stdout (captured to a file
   so the caller can poll it while the process runs)."
  [script]
  (let [out-file (str (System/getProperty "user.dir") "/.e2e-http-" (System/nanoTime) ".out")
        p (proc/process ["bb" script] {:in :discard :out out-file :err :discard})]
    (loop [waits 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (do (Thread/sleep 100)
              (if (< waits 50)
                (recur (inc waits))
                (throw (ex-info (str "HTTP fake did not start: " out)
                                {:type :server-start-failed})))))))))

(defn- stop-http-server! [{:keys [proc out-file]}]
  (try (proc/destroy-tree proc) (catch Exception _ nil))
  (when out-file (io/delete-file out-file true)))

(let [[fake-stdio fake-http] *command-line-args*
      fake-stdio (str (System/getProperty "user.dir") "/" fake-stdio)
      http-server (when fake-http
                    (start-http-server! (str (System/getProperty "user.dir") "/" fake-http)))
      global (str (System/getProperty "user.dir") "/.e2e-global-" (System/nanoTime) ".edn")
      cache-file (str (System/getProperty "user.dir") "/.e2e-cache-" (System/nanoTime) ".edn")]
  (spit global (pr-str {:mcp-servers
                        (cond->
                         {"e2e" {:command "bb" :args [fake-stdio] :lifecycle :lazy
                                 ;; the fake server requires kmet.libs.json (data.json)
                                 :env {"BABASHKA_CLASSPATH" (System/getProperty "java.class.path")}
                                 :direct-tools true}
                          ;; a 2026-07-28 server: the connect opens
                          ;; subscriptions/listen instead of relying on the
                          ;; broadcast list_changed the legacy fake sends
                          "modern" {:command "bb" :args [fake-stdio "--era" "modern"]
                                    :lifecycle :lazy
                                    :env {"BABASHKA_CLASSPATH" (System/getProperty "java.class.path")}}
                          "bad" {:command "sh" :args ["-c" "exit 3"] :lifecycle :lazy}}
                          http-server
                          (assoc "http" {:url (str "http://127.0.0.1:" (:port http-server)
                                                   "/mcp?era=modern")
                                          :http-transport :streamable-http
                                          :lifecycle :lazy}))}))
  (with-redefs [config/global-config-path (fn [] global)
                config/project-config-path (fn [& _] (str global ".project"))
                metadata/cache-path (constantly cache-file)]
    (try
      (let [{:keys [api state]} (ext/create-nullable-api)
            _ (mcp/init api)
            proxy-tool (get-in @state [:tools "mcp"])
            execute (:execute proxy-tool)
            s (fn [params] (execute params))
            result (s {})]
        (check "status text lists server"
               (str/includes? (:content result) "e2e"))
        ;; /mcp slash command — the interactive mode dispatches extension
        ;; command handlers as (handler ctx args) (kmet contract; pi passes
        ;; (args ctx)). The mcp handler used to reverse them, so args was
        ;; the context map and str/trim threw a ClassCastException on every
        ;; /mcp run — dispatch exactly like the interactive mode here.
        (let [cmd (get-in @state [:commands "mcp"])
              r (with-out-str ((:handler cmd) {:has-ui false} "status"))]
          (check "/mcp command dispatches (ctx args)"
                 (str/includes? r "e2e")))
        (let [r (s {:search "echo"})]
          (check "search finds tool"
                 (str/includes? (:content r) "echo")))
        (let [r (s {:search {"query" "echo"}})]
          (check "non-string search -> readable error"
                 (and (:is-error r)
                      (str/includes? (:content r) "mcp({ search")
                      (str/includes? (:content r) "expected a string"))))
        (let [r (s {:limit "5"})]
          (check "non-number limit -> readable error"
                 (and (:is-error r)
                      (str/includes? (:content r) "mcp({ limit")
                      (str/includes? (:content r) "expected a number"))))
        (let [r (s {:search "echo" :regex "false"})]
          (check "string 'false' regex flag is falsy"
                 (and (not (:is-error r)) (str/includes? (:content r) "echo"))))
        (let [r (s {:connect "e2e"})]
          (check "connect lists tools"
                 (and (not (:is-error r)) (str/includes? (:content r) "e2e_ping_mid"))))
        (let [r (s {:tool "echo" :args {:message "hello"}})]
          (check "tool call through proxy"
                 (str/includes? (:content r) "echo: hello")))
        (let [r (s {:tool "boom" :args {}})]
          (check "tool error surfaces" (and (:is-error r) (= "kaboom" (:content r)))))
        (let [r (s {:describe "echo"})]
          (check "describe shows params"
                 (str/includes? (:content r) "message")))
        (let [r (s {:disconnect "e2e"})]
          (check "disconnect" (str/includes? (:content r) "Disconnected")))
        ;; pi failure backoff: a failed connect is recorded; lazy uses
        ;; report the backoff window, explicit connect bypasses it
        (let [r (s {:connect "bad"})]
          (check "failing server explicit connect errors"
                 (str/includes? (:content r) "Failed to connect")))
        (let [r (s {:tool "x" :server "bad"})]
          (check "lazy use inside backoff window"
                 (str/includes? (:content r) "not available (last failed")))
        (let [r (s {})]
          (check "status shows failed"
                 (and (re-find #"failed \d+s ago" (:content r))
                      (str/includes? (:content r) "bad ("))))
        (let [r (s {:connect "bad"})]
          (check "explicit connect bypasses backoff"
                 (str/includes? (:content r) "Failed to connect")))
        (let [r (s {:server "e2e"})]
          (check "server list after disconnect (cached)"
                 (str/includes? (:content r) "not connected, cached")))
        ;; direct-tools bootstrap (pi init.ts direct-tools-bootstrap): the
        ;; e2e server has :direct-tools and starts without a cache entry —
        ;; init background-connects it and registers the direct tool
        (Thread/sleep 2000)
        (check "bootstrap registered the direct tool"
               (contains? (:tools @state) "e2e_echo"))
        ;; tools/list_changed: the server adds a tool and notifies — the
        ;; extension must re-list and register it with no manual connect
        ;; or refresh (client.clj dispatch -> core.clj resync)
        (let [r (s {:tool "e2e_add_tool" :args {}})]
          (check "list_changed trigger tool called"
                 (str/includes? (:content r) "added echo2")))
        (check "resync registered the added direct tool"
               (loop [waits 0]
                 (cond
                   (contains? (:tools @state) "e2e_echo2") true
                   (< waits 40) (do (Thread/sleep 100) (recur (inc waits)))
                   :else false)))
        (let [r (s {:search "echo2"})]
          (check "resync refreshed the proxy search"
                 (str/includes? (:content r) "echo2")))
        ;; 2026-07-28 subscription (subscriptions/listen): the connect opens
        ;; one from the advertised listChanged capabilities, the fake emits
        ;; its list_changed on that stream (never a broadcast), and the
        ;; adapter resyncs through the same path as above
        (let [r (s {:connect "modern"})]
          (check "modern server connects"
                 (and (not (:is-error r)) (str/includes? (:content r) "modern_echo"))))
        (let [r (s {:tool "modern_add_tool" :args {}})]
          (check "modern list_changed trigger called"
                 (str/includes? (:content r) "added echo2")))
        ;; the modern fake notifies open subscriptions only (a modern
        ;; server never broadcasts), so the catalog changing with no manual
        ;; refresh proves the adapter's subscription carried it — a missing
        ;; subscriptions/listen would leave the catalog stale. The connect
        ;; opens the subscription on a background thread, so the trigger can
        ;; race its acknowledgment: retry it while waiting (add-tool is
        ;; idempotent, and a trigger after the ack is delivered for sure)
        (check "subscription-driven resync registered the change"
               (loop [tries 0]
                 (cond
                   (str/includes? (:content (s {:server "modern"})) "echo2") true
                   (< tries 5) (do (s {:tool "modern_add_tool" :args {}})
                                   (Thread/sleep 800)
                                   (recur (inc tries)))
                   :else false)))
        (let [r (s {:disconnect "modern"})]
          (check "modern server disconnects" (str/includes? (:content r) "Disconnected")))
        ;; SEP-2243 adapter path (when the HTTP fake was supplied): the
        ;; first call is by raw name on a never-connected lazy server, so no
        ;; catalog record exists when the call starts — the schema must come
        ;; from the catalog read back after the connect. The fake validates
        ;; every Mcp-Param-* header against the body and answers 400 -32020
        ;; when one is missing, so a success proves the adapter derived and
        ;; sent them.
        (when http-server
          (let [r (s {:tool "http-region" :server "http"
                      :args {:region "us-west1" :priority 42 :dryRun true}})]
            (check "adapter mirrors Mcp-Param-* on a lazy first call"
                   (= "region=us-west1 priority=42 dryRun=true" (:content r))))
          (let [r (s {:tool "http-region" :args {:region "Héllo, 世界"}})]
            (check "adapter Base64-encodes unsafe Mcp-Param values"
                   (= "region=Héllo, 世界 priority= dryRun=" (:content r))))
          (let [r (s {:server "http"})]
            (check "adapter catalog omits the invalid x-mcp-header tool"
                   (and (str/includes? (:content r) "http_http_region")
                        (not (str/includes? (:content r) "http_bad"))))))
        ;; a resource template (file:///{path}) registers a read tool
        ;; whose {path} variable expands into the resources/read URI
        (check "resource template registered a read tool"
               (loop [waits 0]
                 (cond
                   (contains? (:tools @state) "e2e_read_project_files") true
                   (< waits 40) (do (Thread/sleep 100) (recur (inc waits)))
                   :else false)))
        (let [r (s {:server "e2e"})]
          (check "list shows the resource template"
                 (str/includes? (:content r) "file:///{path}")))
        ;; a template whose display name needs resource-tool-name
        ;; normalization registers and lists under the same tool name
        ;; (a raw sanitize of "read_issues (all)" would double the
        ;; separators and the listed name would not address the tool)
        (check "spec-char template registered"
               (loop [waits 0]
                 (cond
                   (contains? (:tools @state) "e2e_read_issues_all") true
                   (< waits 40) (do (Thread/sleep 100) (recur (inc waits)))
                   :else false)))
        (let [r (s {:server "e2e"})]
          (check "list shows the normalized template name"
                 (str/includes? (:content r) "e2e_read_issues_all")))
        (when-let [execute (get-in @state [:tools "e2e_read_project_files" :execute])]
          (let [r (execute {:path "src/main.clj"})]
            (check "template read tool expands and reads"
                   (str/includes? (:content r) "(ns main)"))))
        ;; a list_changed storm must not turn into an unbounded resync
        ;; loop: the fake answers every tools/list with another
        ;; notification. The client runs the owning resync plus at most
        ;; one deferred pass (2 x 2 tools/list pages) and then stops — a
        ;; client that resyncs per notification never settles and the
        ;; count runs away
        (let [before (s {:tool "e2e_list_count" :args {}})
              _ (s {:tool "e2e_storm" :args {}})
              _ (Thread/sleep 2000)
              after (s {:tool "e2e_list_count" :args {}})
              calls (- (Long/parseLong (str/trim (:content after)))
                       (Long/parseLong (str/trim (:content before))))]
          (check "list_changed storm does not resync without bound"
                 (< calls 6)))
        (mcp/shutdown api)
        (check "shutdown" true))
      (finally
        (io/delete-file global true)
        (io/delete-file cache-file true)
        (when http-server (stop-http-server! http-server)))))
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))

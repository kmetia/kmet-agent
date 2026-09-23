#!/usr/bin/env bb
;; End-to-end smoke: load the extension against the nullable api with a
;; real config pointing at the fake stdio server, then drive the proxy
;; tool: status → search → connect → call → disconnect → status.
;; Usage: bb e2e.bb <fake-mcp-server.bb>
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[kmet.extension :as ext]
         '[kmet.extensions.mcp-adapter.core :as mcp]
         '[kmet.extensions.mcp-adapter.config :as config]
         '[kmet.extensions.mcp-adapter.metadata :as metadata])

(def failures (atom 0))
(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(let [[fake-stdio] *command-line-args*
      fake-stdio (str (System/getProperty "user.dir") "/" fake-stdio)
      global (str (System/getProperty "user.dir") "/.e2e-global-" (System/nanoTime) ".edn")
      cache-file (str (System/getProperty "user.dir") "/.e2e-cache-" (System/nanoTime) ".edn")]
  (spit global (pr-str {:mcp-servers
                        {"e2e" {:command "bb" :args [fake-stdio] :lifecycle :lazy
                                ;; the fake server requires kmet.libs.json (data.json)
                                :env {"BABASHKA_CLASSPATH" (System/getProperty "java.class.path")}
                                :direct-tools ["echo"]}
                         "bad" {:command "sh" :args ["-c" "exit 3"] :lifecycle :lazy}}}))
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
        (mcp/shutdown api)
        (check "shutdown" true))
      (finally
        (io/delete-file global true)
        (io/delete-file cache-file true))))
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))

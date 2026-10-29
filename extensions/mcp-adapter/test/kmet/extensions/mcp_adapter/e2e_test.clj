(ns kmet.extensions.mcp-adapter.e2e-test
  "End-to-end smoke through the proxy tool (§9.2, §12.6): status → search →
   connect → call → disconnect over a real stdio server, the failure
   backoff, the direct-tools bootstrap, the system-prompt snippets, the
   resource templates, the list_changed storm bound, the modern
   subscription path and the SEP-2243 adapter path over HTTP. Spawns
   fixtures (^:slow)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.core :as mcp]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(defn- classpath []
  (System/getProperty "java.class.path"))

(defn- stdio-entry [& args]
  {:command "bb"
   :args (into [(tu/fixture "fake-mcp-server.bb")] args)
   :lifecycle :lazy
   ;; the fixture requires kmet.libs.json (data.json)
   :env {"BABASHKA_CLASSPATH" (classpath)}})

(defn- mcp-call [state params]
  ((get-in @state [:tools "mcp" :execute]) params))

(defn- config! [servers]
  (tu/write-edn! (config/global-config-path) {:mcp-servers servers}))

(defn- wait-for-tool [state name]
  (tu/wait-until #(contains? (:tools @state) name) 8000))

;; ─── stdio proxy smoke ────────────────────────────────────────────────────

(deftest ^:slow proxy-over-stdio
  (tu/with-agent-dir
    (fn [_]
      (config! {"e2e" (assoc (stdio-entry) :direct-tools true)
                "bad" {:command "sh" :args ["-c" "exit 3"] :lifecycle :lazy}})
      (let [{:keys [api state]} (ext/create-nullable-api)
            s (partial mcp-call state)]
        (try
          (mcp/init api)
          (testing "the status lists the configured servers"
            (is (str/includes? (:content (s {})) "e2e")))
          (testing "the /mcp command dispatches as (ctx args)"
            (let [cmd (get-in @state [:commands "mcp"])
                  out (with-out-str ((:handler cmd) {:has-ui false} "status"))]
              (is (str/includes? out "e2e"))))
          (testing "search finds a cached tool"
            (is (str/includes? (:content (s {:search "echo"})) "echo")))
          (testing "a non-string search is a readable error"
            (let [r (s {:search {"query" "echo"}})]
              (is (true? (:is-error r)))
              (is (str/includes? (:content r) "mcp({ search"))
              (is (str/includes? (:content r) "expected a string"))))
          (testing "a non-number limit is a readable error"
            (let [r (s {:limit "5"})]
              (is (true? (:is-error r)))
              (is (str/includes? (:content r) "mcp({ limit"))
              (is (str/includes? (:content r) "expected a number"))))
          (testing "the string 'false' regex flag is falsy"
            (let [r (s {:search "echo" :regex "false"})]
              (is (false? (:is-error r)))
              (is (str/includes? (:content r) "echo"))))
          (testing "connect lists the server's tools"
            (let [r (s {:connect "e2e"})]
              (is (false? (:is-error r)))
              (is (str/includes? (:content r) "e2e_ping_mid"))))
          (testing "a tool call through the proxy reaches the server"
            (is (str/includes? (:content (s {:tool "echo" :args {:message "hello"}}))
                               "echo: hello")))
          (testing "a tool error surfaces as an error result"
            (let [r (s {:tool "boom" :args {}})]
              (is (true? (:is-error r)))
              (is (= "kaboom" (:content r)))))
          (testing "describe shows the parameter lines"
            (is (str/includes? (:content (s {:describe "echo"})) "message")))
          (testing "disconnect answers"
            (is (str/includes? (:content (s {:disconnect "e2e"})) "Disconnected")))

          (testing "a failed connect is recorded and holds a lazy use back"
            (is (str/includes? (:content (s {:connect "bad"})) "Failed to connect"))
            (is (str/includes? (:content (s {:tool "x" :server "bad"}))
                               "not available (last failed"))
            (let [out (:content (s {}))]
              (is (re-find #"failed \d+s ago" out))
              (is (str/includes? out "bad (")))
            (testing "an explicit connect still bypasses the backoff"
              (is (str/includes? (:content (s {:connect "bad"})) "Failed to connect"))))

          (testing "the direct-tools bootstrap registers the tool without a manual connect"
            (is (wait-for-tool state "e2e_echo")))

          (testing "every registered snippet is a single line"
            (let [tool (get-in @state [:tools "e2e_echo"])]
              (is (= (catalog/prompt-snippet (:description tool) 100)
                     (:prompt-snippet tool)))
              (is (not (str/includes? (str (:prompt-snippet tool)) "\n")))
              (is (every? (fn [t]
                            (let [snippet (:prompt-snippet t)]
                              (or (nil? snippet) (not (str/includes? snippet "\n")))))
                          (vals (:tools @state))))))

          (testing "the legacy connect stored its era"
            (is (= {:era :legacy :version "2025-11-25"}
                   (get-in (metadata/load-cache) [:servers "e2e" :protocol-era]))))

          (testing "a list_changed trigger resyncs the catalog"
            (is (str/includes? (:content (s {:tool "e2e_add_tool" :args {}}))
                               "added echo2"))
            (is (wait-for-tool state "e2e_echo2"))
            (is (str/includes? (:content (s {:search "echo2"})) "echo2")))

          (testing "a resource template registers a read tool"
            (is (wait-for-tool state "e2e_read_project_files"))
            (is (str/includes? (:content (s {:server "e2e"})) "file:///{path}")))
          (testing "a template whose name needs normalization registers once"
            (is (wait-for-tool state "e2e_read_issues_all"))
            (is (str/includes? (:content (s {:server "e2e"})) "e2e_read_issues_all")))
          (testing "the template read tool expands and reads"
            (let [execute (get-in @state [:tools "e2e_read_project_files" :execute])]
              (is (str/includes? (:content (execute {:path "src/main.clj"}))
                                 "(ns main)"))))

          (testing "a list_changed storm does not resync without bound"
            (let [before (s {:tool "e2e_list_count" :args {}})
                  _ (s {:tool "e2e_storm" :args {}})
                  _ (Thread/sleep 2000)
                  after (s {:tool "e2e_list_count" :args {}})
                  calls (- (Long/parseLong (str/trim (:content after)))
                           (Long/parseLong (str/trim (:content before))))]
              (is (< calls 6))))
          (finally (mcp/shutdown api)))))))

;; ─── modern subscription path ─────────────────────────────────────────────

(deftest ^:slow modern-subscription-resync
  (tu/with-agent-dir
    (fn [_]
      (config! {"modern" (stdio-entry "--era" "modern")})
      (let [{:keys [api state]} (ext/create-nullable-api)
            s (partial mcp-call state)
            settings {}
            definition (get-in (config/load-config) [:mcp-servers "modern"])]
        (try
          (mcp/init api)
          ;; seed a stale era hint before init loads the cache: the connect
          ;; must discard it, re-probe and store the era actually used
          (metadata/update-entry! nil "modern" definition settings
                                  :tools [] :prompts [] :resources []
                                  :resource-templates []
                                  :protocol-era {:era :legacy :version "2025-11-25"})
          (testing "a modern server connects and lists its tools"
            (let [r (s {:connect "modern"})]
              (is (false? (:is-error r)))
              (is (str/includes? (:content r) "modern_echo"))))
          (testing "the stale era hint was re-probed to modern"
            (is (= {:era :modern :version "2026-07-28"}
                   (get-in (metadata/load-cache) [:servers "modern" :protocol-era]))))
          (testing "the subscription carries a catalog change with no refresh"
            ;; the fake notifies open subscriptions only, so a changed
            ;; catalog proves the adapter's subscriptions/listen carried it;
            ;; the connect opens the subscription in the background, so the
            ;; trigger can race its ack — retry (add-tool is idempotent)
            (is (loop [tries 0]
                  (cond
                    (str/includes? (:content (s {:server "modern"})) "echo2") true
                    (< tries 5) (do (s {:tool "modern_add_tool" :args {}})
                                    (Thread/sleep 800)
                                    (recur (inc tries)))
                    :else false))))
          (testing "the modern server disconnects"
            (is (str/includes? (:content (s {:disconnect "modern"})) "Disconnected")))
          (finally (mcp/shutdown api)))))))

;; ─── SEP-2243 adapter path over HTTP ──────────────────────────────────────

(deftest ^:slow http-adapter-param-headers
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-agent-dir
      (fn [_]
        (config! {"http" {:url (str "http://127.0.0.1:" (:port server)
                                    "/mcp?era=modern")
                          :http-transport :streamable-http
                          :lifecycle :lazy}})
        (let [{:keys [api state]} (ext/create-nullable-api)
              s (partial mcp-call state)]
          (try
            (mcp/init api)
            (testing "a lazy first call by raw name mirrors the Mcp-Param-* headers"
              ;; the fake validates every Mcp-Param-* header against the body
              ;; and answers 400 -32020 when one is missing, so success proves
              ;; the adapter derived and sent them from the catalog read back
              ;; after the connect
              (is (= "region=us-west1 priority=42 dryRun=true"
                     (:content (s {:tool "http-region" :server "http"
                                   :args {:region "us-west1" :priority 42
                                          :dryRun true}})))))
            (testing "unsafe Mcp-Param values are Base64-encoded"
              (is (= "region=Héllo, 世界 priority= dryRun="
                     (:content (s {:tool "http-region"
                                   :args {:region "Héllo, 世界"}})))))
            (testing "the served catalog omits the invalid x-mcp-header tool"
              (let [out (:content (s {:server "http"}))]
                (is (str/includes? out "http_http_region"))
                (is (not (str/includes? out "http_bad")))))
            (testing "the modern era is recorded for the HTTP server"
              (is (= {:era :modern :version "2026-07-28"}
                     (get-in (metadata/load-cache)
                             [:servers "http" :protocol-era]))))
            (finally (mcp/shutdown api))))))))

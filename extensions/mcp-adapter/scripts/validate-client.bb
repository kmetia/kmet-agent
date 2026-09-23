#!/usr/bin/env bb
;; Client validation for the mcp-adapter extension (§12.3) — no test
;; framework, plain asserts. Exercises the stdio, streamable-http and SSE
;; transports against the fake servers:
;;
;;   bb validate-client.bb <fake-stdio.bb> <fake-http.bb> [--no-sse]
;;
;; Covers: connect/handshake, tools/list pagination, tools/call (echo +
;; error), a notification mid-request, request timeout, process-exit
;; error, and disconnect kills the process tree.
(require '[babashka.process :as proc]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[kmet.extensions.mcp-adapter.client :as client]
         '[kmet.extensions.mcp-adapter.config :as config])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn spawn-server!
  "Start a bb script server; returns {:proc :port}. The script prints
   'PORT <n>' on stdout — captured to a temp file so the caller can poll
   it while the process keeps running (:out :string would block on exit)."
  [script & args]
  (let [out-file (str (System/getProperty "user.dir") "/.mcp-fake-" (System/nanoTime) ".out")
        p (proc/process (into ["bb" script] args)
                        {:in :discard :out out-file :err :discard})]
    (loop [waits 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m))}
          (do (Thread/sleep 100)
              (if (< waits 50)
                (recur (inc waits))
                (throw (ex-info (str "server did not start: " out)
                                {:type :server-start-failed})))))))))

(defn stop-server! [{:keys [proc]}]
  (try (proc/destroy-tree proc) (catch Exception _ nil)))

;; ─── stdio transport ──────────────────────────────────────────────────────

(defn test-stdio [fake-stdio]
  (println "\n── stdio transport ──")
  (let [definition {:command "bb" :args [fake-stdio] :lifecycle :lazy}
        {:keys [conn tools protocol-version server-info]}
        (client/connect! definition {})]
    (check "handshake protocol-version" (= "2025-06-18" protocol-version))
    (check "handshake server-info" (= "fake-mcp-server" (:name server-info)))
    (check "tools/list pagination" (= 5 (count tools)))
    (check "tool names" (= #{"echo" "add" "slow" "boom" "ping-mid"}
                           (set (map :name tools))))
    (let [result (client/request! conn "tools/call"
                                  {:name "echo" :arguments {:message "hi"}})]
      (check "tools/call echo" (str/includes? (:text (client/format-result result))
                                              "echo: hi")))
    (let [result (client/request! conn "tools/call"
                                  {:name "add" :arguments {:a 2 :b 3}})]
      (check "tools/call add" (= "5" (:text (client/format-result result)))))
    ;; Phase 2: prompts/resources capability + progress streaming
    (let [result (client/connect! definition {})]
      (check "prompts/list" (= #{"brief" "review"}
                               (set (map :name (:prompts result)))))
      (check "resources/list" (= #{"README" "schema"}
                                 (set (map :name (:resources result))))))
    (let [result (client/get-prompt conn "brief" {"topic" "clojure"})]
      (check "prompts/get args"
             (str/includes? (get-in result [:messages 0 :content :text])
                            "Briefly summarize: clojure")))
    (let [result (client/read-resource conn "file:///README.md")]
      (check "resources/read"
             (str/includes? (get-in result [:contents 0 :text]) "# Fake README")))
    (let [progress (atom [])
          result (client/request! conn "tools/call"
                                  {:name "slow" :arguments {:ms 400}}
                                  {:timeout-ms 5000
                                   :on-notification
                                   (fn [n] (swap! progress conj
                                                  (get-in n [:params :progress])))})
          formatted (client/format-result result)]
      (check "progress notifications streamed" (= [25 50 75] @progress))
      (check "slow call after progress" (= "slept" (:text formatted))))
    ;; notification mid-request must be dropped, response still arrives
    (let [result (client/request! conn "tools/call" {:name "ping-mid" :arguments {}})]
      (check "notification mid-request dropped"
             (= "pong" (:text (client/format-result result)))))
    ;; error result surfaces as :is-error
    (let [formatted (client/format-result
                     (client/request! conn "tools/call" {:name "boom" :arguments {}}))]
      (check "error result is-error" (true? (:is-error formatted)))
      (check "error result text" (= "kaboom" (:text formatted))))
    ;; JSON-RPC error
    (check "json-rpc error"
           (try (client/request! conn "tools/call" {:name "nope" :arguments {}})
                false
                (catch Exception e (str/includes? (ex-message e) "MCP error -32602"))))
    ;; timeout
    (check "request timeout"
           (try (client/request! conn "tools/call" {:name "slow" :arguments {:ms 5000}}
                                 {:timeout-ms 300})
                false
                (catch Exception e (str/includes? (ex-message e) "timed out after 300ms"))))
    ;; process-exit error: kill the server, next request fails with stderr
    (check "alive?" (client/alive? conn))
    (proc/destroy-tree (:proc conn))
    (Thread/sleep 300)
    (check "dead process detected" (not (client/alive? conn)))
    (check "process-exit error"
           (try (client/request! conn "tools/list" {})
                false
                (catch Exception e (str/includes? (ex-message e) "process exited"))))
    ;; disconnect kills the process tree
    (let [conn2 (client/connect! {:command "bb" :args [fake-stdio]} {})
          p2 (:proc (:conn conn2))]
      (client/close! (:conn conn2))
      (Thread/sleep 300)
      (check "close kills process" (not (proc/alive? p2))))))

;; ─── streamable-http transport ────────────────────────────────────────────

(defn test-http [fake-http]
  (println "\n── streamable-http transport ──")
  (let [{:keys [proc port]} (spawn-server! fake-http)
        definition {:url (str "http://127.0.0.1:" port "/mcp")
                    :http-transport :streamable-http}]
    (try
      (let [{:keys [conn tools protocol-version]}
            (client/connect! definition {})]
        (check "http handshake" (= "2025-06-18" protocol-version))
        (check "http tools/list" (= 3 (count tools)))
        (check "http session-id captured" (string? @(:session-id conn)))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-echo" :arguments {:message "hey"}})]
          (check "http tools/call" (str/includes? (:text (client/format-result result))
                                                  "http-echo: hey")))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-add" :arguments {:a 40 :b 2}})]
          (check "http add" (= "42" (:text (client/format-result result)))))
        (let [result (client/connect! definition {})]
          (check "http prompts/list"
                 (= #{"http-brief"} (set (map :name (:prompts result)))))
          (check "http resources/list"
                 (= #{"HTTP doc"} (set (map :name (:resources result))))))
        (let [result (client/get-prompt conn "http-brief" {"topic" "x"})]
          (check "http prompts/get"
                 (str/includes? (get-in result [:messages 0 :content :text])
                                "http brief: x")))
        ;; SSE responses stream progress notifications before the result
        (let [progress (atom [])
              result (client/request! conn "tools/call"
                                      {:name "http-slow" :arguments {}}
                                      {:timeout-ms 5000
                                       :on-notification
                                       (fn [n] (swap! progress conj
                                                      (get-in n [:params :progress])))})
              formatted (client/format-result result)]
          (check "http progress via sse body" (= [10 50] @progress))
          (check "http slow result" (= "finally" (:text formatted))))
        (check "http timeout"
               (try (client/request! conn "tools/call" {:name "http-slow" :arguments {}}
                                     {:timeout-ms 300})
                    false
                    (catch Exception e (str/includes? (ex-message e) "timed out after 300ms"))))
        (check "http conn dead after timeout" (not (client/alive? conn)))
        (client/close! conn))
      (finally
        (stop-server! {:proc proc})))))

;; ─── SSE responses on streamable-http (Accept: text/event-stream) ────────

(defn test-http-sse-response [fake-http]
  (println "\n── streamable-http with SSE response bodies ──")
  (let [{:keys [proc port]} (spawn-server! fake-http)]
    (try
      ;; the auth-headers merge overrides the base Accept, forcing the
      ;; server's SSE response path
      (let [{:keys [conn]} (client/connect! {:url (str "http://127.0.0.1:" port "/mcp")
                                             :http-transport :streamable-http}
                                            {:auth-headers (fn [] {"Accept" "text/event-stream"})})]
        (let [result (client/request! conn "tools/call"
                                      {:name "http-echo" :arguments {:message "sse"}})]
          (check "sse-body tools/call" (str/includes? (:text (client/format-result result))
                                                      "http-echo: sse")))
        (client/close! conn))
      (finally
        (stop-server! {:proc proc})))))

;; ─── legacy SSE transport ─────────────────────────────────────────────────

(defn test-sse [fake-http]
  (println "\n── legacy SSE transport ──")
  (let [{:keys [proc port]} (spawn-server! fake-http)]
    (try
      (let [{:keys [conn tools]}
            (client/connect! {:url (str "http://127.0.0.1:" port "/sse")
                              :http-transport :sse}
                             {})]
        (check "sse tools/list" (= 3 (count tools)))
        (check "sse endpoint resolved" (str/ends-with? @(:endpoint-atom conn) "/sse"))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-echo" :arguments {:message "stream"}})]
          (check "sse tools/call" (str/includes? (:text (client/format-result result))
                                                 "http-echo: stream")))
        (client/close! conn)
        (check "sse closed" (not (client/alive? conn))))
      (finally
        (stop-server! {:proc proc})))))

;; ─── main ─────────────────────────────────────────────────────────────────

(let [[fake-stdio fake-http] *command-line-args*]
  (when-not (and fake-stdio fake-http)
    (println "Usage: bb validate-client.bb <fake-stdio.bb> <fake-http.bb>")
    (System/exit 1))
  (test-stdio fake-stdio)
  (test-http fake-http)
  (test-http-sse-response fake-http)
  (test-sse fake-http)
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))

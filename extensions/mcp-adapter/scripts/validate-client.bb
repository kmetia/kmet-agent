#!/usr/bin/env bb
;; Client validation for the mcp-adapter extension (§12.3) — no test
;; framework, plain asserts. Exercises the stdio, streamable-http and SSE
;; transports against the fake servers:
;;
;;   bb validate-client.bb <fake-stdio.bb> <fake-http.bb> [--no-sse]
;;
;; Covers: connect/handshake, protocol version negotiation + rejection,
;; tools/list pagination, tools/call (echo + error), a notification
;; mid-request, request timeout, process-exit error, and disconnect kills
;; the process tree. The modern (2026-07-28) streamable-HTTP connect is
;; validated against the fake's header checks (MCP-Protocol-Version,
;; Mcp-Method, Mcp-Name) and its no-session rule, and the modern
;; subscriptions/listen flow (ack first, list_changed, catalog resync,
;; close) is validated over both stdio and streamable HTTP.
(require '[babashka.process :as proc]
         '[clojure.string :as str]
         '[kmet.libs.json :as json]
         '[clojure.java.io :as io]
         '[kmet.extensions.mcp-adapter.client :as client]
         '[kmet.extensions.mcp-adapter.config :as config])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn spawn-server!
  "Start a bb script server; returns {:proc :port :out-file}. The script
   prints 'PORT <n>' on stdout — captured to a temp file so the caller can
   poll it while the process keeps running (:out :string would block on
   exit); stop-server! deletes the file."
  [script & args]
  (let [out-file (str (System/getProperty "user.dir") "/.mcp-fake-" (System/nanoTime) ".out")
        p (proc/process (into ["bb" script] args)
                        {:in :discard :out out-file :err :discard})]
    (loop [waits 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (do (Thread/sleep 100)
              (if (< waits 50)
                (recur (inc waits))
                (throw (ex-info (str "server did not start: " out)
                                {:type :server-start-failed})))))))))

(defn stop-server! [{:keys [proc out-file]}]
  (try (proc/destroy-tree proc) (catch Exception _ nil))
  (when out-file (io/delete-file out-file true)))

;; ─── stdio transport ──────────────────────────────────────────────────────

(defn test-stdio [fake-stdio]
  (println "\n── stdio transport ──")
  (let [definition {:command "bb" :args [fake-stdio] :lifecycle :lazy}
        {:keys [conn tools protocol-version server-info]}
        (client/connect! definition {})]
    (check "handshake protocol-version" (= "2025-11-25" protocol-version))
    (check "handshake server-info" (= "fake-mcp-server" (:name server-info)))
    (check "tools/list pagination" (= 9 (count tools)))
    (check "tool names" (= #{"echo" "add" "slow" "boom" "ping-mid"
                             "who-asks" "add-tool" "storm" "list-count"}
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
                                 (set (map :name (:resources result)))))
      (check "resources/templates/list"
             (= #{"file:///{path}" "file:///issues/{state}"
                  "github://repo/{owner}/{repo}/issues/{number}"}
                (set (map :uriTemplate (:resource-templates result))))))
    (let [result (client/get-prompt conn "brief" {"topic" "clojure"})]
      (check "prompts/get args"
             (str/includes? (get-in result [:messages 0 :content :text])
                            "Briefly summarize: clojure")))
    (let [result (client/read-resource conn "file:///README.md")]
      (check "resources/read"
             (str/includes? (get-in result [:contents 0 :text]) "# Fake README")))
    ;; a level-1 URI template expands into a concrete resources/read URI:
    ;; '/' survives inside a variable, '?' '#' '%' and spaces are escaped
    (check "uri template expansion"
           (= "file:///src/main.clj"
              (client/expand-uri-template "file:///{path}" {:path "src/main.clj"})))
    (check "uri template escapes unsafe characters"
           (= "file:///a%20b%3F.txt"
              (client/expand-uri-template "file:///{path}" {:path "a b?.txt"})))
    (check "uri template leaves unknown variables"
           (= "file:///{path}"
              (client/expand-uri-template "file:///{path}" {})))
    (let [uri (client/expand-uri-template "file:///{path}" {:path "src/main.clj"})
          result (client/read-resource conn uri)]
      (check "resources/read through a template"
             (= "(ns main)" (get-in result [:contents 0 :text]))))
    (let [progress (atom [])
          result (client/request! conn "tools/call"
                                  {:name "slow" :arguments {:ms 400}
                                   :_meta {:progressToken "t"}}
                                  {:timeout-ms 5000
                                   :on-notification
                                   (fn [n] (swap! progress conj
                                                  (get-in n [:params :progress])))})
          formatted (client/format-result result)]
      (check "progress notifications streamed" (= [25 50 75] @progress))
      (check "slow call after progress" (= "slept" (:text formatted))))
    ;; no _meta.progressToken → the server sends no progress at all
    (let [progress (atom [])
          result (client/request! conn "tools/call"
                                  {:name "slow" :arguments {:ms 100}}
                                  {:timeout-ms 5000
                                   :on-notification
                                   (fn [n] (swap! progress conj n))})]
      (check "no progress without a token" (empty? @progress))
      (check "unprogressed call result" (= "slept" (:text (client/format-result result)))))
    ;; notification mid-request must be dropped, response still arrives
    (let [result (client/request! conn "tools/call"
                                  {:name "ping-mid" :arguments {}
                                   :_meta {:progressToken "t"}})]
      (check "notification mid-request dropped"
             (= "pong" (:text (client/format-result result)))))
    ;; server->client requests: ping is answered, an unimplemented
    ;; optional client feature is refused with -32601 (never dropped)
    (let [formatted (client/format-result
                     (client/request! conn "tools/call"
                                      {:name "who-asks" :arguments {}}
                                      {:timeout-ms 5000}))
          answers (json/parse-string (:text formatted) true)]
      ;; json object keys arrive keywordized (":9001" — the ids are numbers)
      (check "client answers server ping" (= {} (get-in answers [:9001 :result])))
      (check "client refuses roots/list"
             (= -32601 (get-in answers [:9002 :error :code]))))
    ;; two live requests on one stdio conn must not consume (and drop)
    ;; each other's responses — request! serializes them, so a background
    ;; list_changed resync queues behind an in-flight tool call
    (let [a (future (try (client/request! conn "tools/call"
                                          {:name "who-asks" :arguments {}}
                                          {:timeout-ms 5000})
                         :ok
                         (catch Exception e (ex-message e))))
          _ (Thread/sleep 150)
          b (future (try (client/request! conn "tools/call"
                                          {:name "echo" :arguments {:message "hi"}}
                                          {:timeout-ms 5000})
                         :ok
                         (catch Exception e (ex-message e))))]
      (check "concurrent requests both complete"
             (and (= :ok @a) (= :ok @b))))
    ;; a list_changed notification reaches the conn-level handler
    (let [notifications (atom [])
          {:keys [conn]} (client/connect! definition
                                          {:on-notification (fn [_conn msg]
                                                              (swap! notifications conj
                                                                     (:method msg)))})]
      (client/request! conn "tools/call" {:name "add-tool" :arguments {}})
      (Thread/sleep 200)
      (check "list_changed notification dispatched"
             (some #{"notifications/tools/list_changed"} @notifications))
      (check "re-list sees the added tool"
             (some #(= "echo2" (:name %))
                   (client/list-all-tools conn)))
      (client/close! conn))
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
    ;; a dropped connection is not a cancellation — an abandoned request
    ;; must carry notifications/cancelled
    (check "abandoned request cancelled"
           (let [log-file (str (System/getProperty "user.dir") "/.mcp-cancel-"
                               (System/nanoTime) ".log")]
             (try
               (let [{:keys [conn]} (client/connect!
                                     {:command "bb" :args [fake-stdio] :env {"FAKE_LOG" log-file}}
                                     {})]
                 (try
                   (client/request! conn "tools/call" {:name "slow" :arguments {:ms 1200}}
                                    {:timeout-ms 200})
                   (catch Exception _ nil))
                 ;; the fake reads stdin on its loop, which is inside the slow
                 ;; call — the cancel lands once that returns
                 (Thread/sleep 1600)
                 (let [logged (try (slurp log-file) (catch Exception _ ""))]
                   (client/close! conn)
                   (and (str/includes? logged "notifications/cancelled")
                        (str/includes? logged "tools/call"))))
               (finally
                 (io/delete-file log-file true)))))
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
  (let [{:keys [proc port] :as server} (spawn-server! fake-http)
        definition {:url (str "http://127.0.0.1:" port "/mcp")
                    :http-transport :streamable-http}]
    (try
      (let [{:keys [conn tools protocol-version]}
            (client/connect! definition {})]
        (check "http handshake" (= "2025-11-25" protocol-version))
        (check "http tools/list" (= 4 (count tools)))
        (check "http session-id captured" (string? @(:session-id conn)))
        (check "http protocol-version on conn" (= "2025-11-25" @(:protocol-version conn)))
        ;; the negotiated revision rides on every POST after the handshake
        (let [result (client/request! conn "tools/call"
                                      {:name "http-headers" :arguments {}})]
          (check "MCP-Protocol-Version header sent"
                 (= "2025-11-25" (:text (client/format-result result)))))
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
                 (= #{"HTTP doc"} (set (map :name (:resources result)))))
          (check "http resources/templates/list"
                 (= #{"http://fake/page/{id}"}
                    (set (map :uriTemplate (:resource-templates result))))))
        (let [result (client/get-prompt conn "http-brief" {"topic" "x"})]
          (check "http prompts/get"
                 (str/includes? (get-in result [:messages 0 :content :text])
                                "http brief: x")))
        ;; SSE responses stream progress notifications before the result
        (let [progress (atom [])
              result (client/request! conn "tools/call"
                                      {:name "http-slow" :arguments {}
                                       :_meta {:progressToken "t"}}
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
        ;; a conn that already died on timeout is not DELETEd on close (the
        ;; session expires server-side; a DELETE to the stalled server
        ;; would make the next use wait on it)
        (client/close! conn)
        ;; a healthy session IS released with an HTTP DELETE (the fake
        ;; prints a marker on its stdout)
        (let [{:keys [conn]} (client/connect! definition {})]
          (client/close! conn)
          (check "close deletes the http session"
                 (loop [waits 0]
                   (let [out (try (slurp (:out-file server)) (catch Exception _ ""))]
                     (cond
                       (str/includes? out "SESSION DELETED") true
                       (< waits 30) (do (Thread/sleep 100) (recur (inc waits)))
                       :else false))))))
      (finally
        (stop-server! server)))))

;; ─── streamable-http: modern (2026-07-28) ─────────────────────────────────

(defn test-http-modern [fake-http]
  (println "\n── streamable-http: modern (2026-07-28) ──")
  (let [{:keys [port] :as server} (spawn-server! fake-http)
        definition {:url (str "http://127.0.0.1:" port "/mcp?era=modern")
                    :http-transport :streamable-http}]
    (try
      (let [{:keys [conn tools protocol-version server-info]}
            (client/connect! definition {})]
        (check "modern http discovers the revision" (= "2026-07-28" protocol-version))
        (check "modern http era recorded" (true? (client/modern? conn)))
        (check "modern http serverInfo from _meta"
               (= "fake-http-mcp-server" (:name server-info)))
        (check "modern http tool catalog"
               (= #{"http-echo" "http-add" "http-slow" "http-headers" "http-add-tool"}
                  (set (map :name tools))))
        (check "modern http mints no session" (nil? @(:session-id conn)))
        ;; the fake answers 400 -32020 on a missing/wrong routing or
        ;; version header, so these calls prove the headers
        (let [result (client/request! conn "tools/call"
                                      {:name "http-headers" :arguments {}})]
          (check "modern http MCP-Protocol-Version header"
                 (= "2026-07-28" (:text (client/format-result result)))))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-echo" :arguments {:message "hey"}})]
          (check "modern http tools/call (Mcp-Name)"
                 (= "http-echo: hey" (:text (client/format-result result)))))
        (let [result (client/request! conn "resources/read" {:uri "http://fake/doc"})]
          (check "modern http resources/read (Mcp-Name)"
                 (= "http resource content" (get-in result [:contents 0 :text]))))
        (check "modern http prompts/get (Mcp-Name)"
               (str/includes? (get-in (client/request! conn "prompts/get"
                                                       {:name "http-brief"
                                                        :arguments {:topic "x"}})
                                      [:messages 0 :content :text])
                              "http brief: x"))
        (client/close! conn)
        (Thread/sleep 300)
        (check "modern http close deletes no session"
               (not (str/includes? (try (slurp (:out-file server))
                                        (catch Exception _ ""))
                                   "SESSION DELETED"))))
      (finally
        (stop-server! server)))))

;; ─── 2026-07-28 subscriptions ────────────────────────────────────────────

(defn- wait-for-list-changed
  "Wait up to 5s for a list_changed notification (the resync trips it, so
   it may arrive a moment after the trigger call returns)."
  [seen]
  (loop [waits 0]
    (cond
      (some #(str/includes? (str (:method %)) "list_changed") @seen) true
      (< waits 50) (do (Thread/sleep 100) (recur (inc waits)))
      :else false)))

(defn- test-subscription
  "One modern subscription against a fake: the ack is the first frame, the
   agreed filter is visible, the server's list_changed reaches the client's
   notification handler, and the catalog re-listed afterwards contains the
   tool the trigger added."
  [label definition trigger added-tool]
  (println (str "\n── subscriptions/listen (" label ", 2026-07-28) ──"))
  (let [seen (atom [])
        {:keys [conn]}
        (client/connect! definition {:on-notification (fn [_ msg] (swap! seen conj msg))})]
    (try
      (let [listener (client/listen! conn {:toolsListChanged true})]
        (Thread/sleep 800)
        (check (str label " subscription is live") (true? (client/listening? conn)))
        (check (str label " ack is the first frame")
               (true? (:first-ack? @listener)))
        (check (str label " agreed filter")
               (= {:toolsListChanged true} (:agreed @listener)))
        (client/request! conn "tools/call" {:name trigger :arguments {}})
        (check (str label " list_changed reached the handler")
               (wait-for-list-changed seen))
        (check (str label " catalog re-listed after the change")
               (contains? (set (map :name (client/list-all-tools conn))) added-tool)))
      (finally
        (client/close! conn)))
    (check (str label " close stops the subscription")
           (false? (client/listening? conn)))))

(defn test-subscriptions [fake-stdio fake-http]
  (test-subscription
   "stdio"
   {:command "bb" :args [fake-stdio "--era" "modern"] :lifecycle :lazy}
   "add-tool" "echo2")
  (let [server (spawn-server! fake-http)]
    (try
      (test-subscription
       "streamable-http"
       {:url (str "http://127.0.0.1:" (:port server) "/mcp?era=modern")
        :http-transport :streamable-http}
       "http-add-tool" "http-echo2")
      (finally
        (stop-server! server)))))

;; ─── SSE responses on streamable-http (Accept: text/event-stream) ────────

(defn test-http-sse-response [fake-http]
  (println "\n── streamable-http with SSE response bodies ──")
  (let [{:keys [proc port] :as server} (spawn-server! fake-http)]
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
        (stop-server! server)))))

;; ─── legacy SSE transport ─────────────────────────────────────────────────

(defn test-sse [fake-http]
  (println "\n── legacy SSE transport ──")
  (let [{:keys [proc port] :as server} (spawn-server! fake-http)]
    (try
      (let [{:keys [conn tools]}
            (client/connect! {:url (str "http://127.0.0.1:" port "/sse")
                              :http-transport :sse}
                             {})]
        (check "sse tools/list" (= 4 (count tools)))
        (check "sse endpoint resolved" (str/ends-with? @(:endpoint-atom conn) "/sse"))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-echo" :arguments {:message "stream"}})]
          (check "sse tools/call" (str/includes? (:text (client/format-result result))
                                                 "http-echo: stream")))
        (client/close! conn)
        (check "sse closed" (not (client/alive? conn))))
      (finally
        (stop-server! server)))))

;; ─── protocol version negotiation ────────────────────────────────────────

(defn test-version-negotiation [fake-http]
  (println "\n── protocol version negotiation ──")
  (let [{:keys [proc port] :as server} (spawn-server! fake-http)
        base (str "http://127.0.0.1:" port "/mcp")]
    (try
      ;; a server on an older SDK answers with its own latest revision —
      ;; that stays usable, and the header carries the negotiated revision
      (let [{:keys [conn protocol-version]}
            (client/connect! {:url (str base "?version=2025-06-18")
                              :http-transport :streamable-http}
                             {})]
        (check "downgrade accepted" (= "2025-06-18" protocol-version))
        (check "downgrade stored on conn" (= "2025-06-18" @(:protocol-version conn)))
        (let [result (client/request! conn "tools/call"
                                      {:name "http-headers" :arguments {}})]
          (check "downgrade header" (= "2025-06-18"
                                       (:text (client/format-result result)))))
        (client/close! conn))
      (let [oldest (client/connect! {:url (str base "?version=2024-11-05")
                                     :http-transport :streamable-http}
                                    {})]
        (check "oldest supported revision" (= "2024-11-05" (:protocol-version oldest)))
        (client/close! (:conn oldest)))
      ;; a revision outside the supported list is rejected outright
      (check "unsupported revision rejected"
             (try (client/connect! {:url (str base "?version=2026-07-28")
                                    :http-transport :streamable-http}
                                   {})
                  false
                  (catch Exception e
                    (str/includes? (ex-message e)
                                   "unsupported protocol version 2026-07-28"))))
      (finally
        (stop-server! server)))))

;; ─── main ─────────────────────────────────────────────────────────────────

(let [[fake-stdio fake-http] *command-line-args*]
  (when-not (and fake-stdio fake-http)
    (println "Usage: bb validate-client.bb <fake-stdio.bb> <fake-http.bb>")
    (System/exit 1))
  (test-stdio fake-stdio)
  (test-http fake-http)
  (test-http-modern fake-http)
  (test-subscriptions fake-stdio fake-http)
  (test-http-sse-response fake-http)
  (test-sse fake-http)
  (test-version-negotiation fake-http)
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))

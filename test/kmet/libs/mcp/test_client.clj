(ns kmet.libs.mcp.test-client
  "Client-level helpers that need no transport: URI-template expansion,
   the capability-gated establish! flow, the connect! conn-ref hand-off
   and the era/MRTR request core (request!/notify!/transports
   redefined)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport.http :as http]
            [kmet.libs.mcp.transport.stdio :as stdio]))

;; ─── URI templates ────────────────────────────────────────────────────────

(deftest expand-uri-template-basics
  (testing "string and keyword keys"
    (is (= "file:///a/b" (mcp/expand-uri-template "file:///{path}" {:path "a/b"})))
    (is (= "file:///a/b" (mcp/expand-uri-template "file:///{path}" {"path" "a/b"}))))
  (testing "multiple variables"
    (is (= "db://t/users/42"
           (mcp/expand-uri-template "db://{table}/users/{id}" {:table "t" :id 42}))))
  (testing "a missing variable is left in place (the server answers)"
    (is (= "file:///{path}" (mcp/expand-uri-template "file:///{path}" {})))
    (is (= "file:///x/{y}" (mcp/expand-uri-template "file:///{x}/{y}" {:x "x"}))))
  (testing "values are escaped so they cannot change the URI"
    (is (= "x/a%20b" (mcp/expand-uri-template "x/{v}" {:v "a b"})))
    (is (= "x/a%3Fb" (mcp/expand-uri-template "x/{v}" {:v "a?b"})))
    (is (= "x/a%23b" (mcp/expand-uri-template "x/{v}" {:v "a#b"})))
    (is (= "x/100%25" (mcp/expand-uri-template "x/{v}" {:v "100%"}))))
  (testing "no placeholders, nil args"
    (is (= "plain" (mcp/expand-uri-template "plain" nil)))))

;; ─── Modern era: _meta + MRTR ─────────────────────────────────────────────

(defn- era-conn
  "A conn that needs no transport: :streamable-http skips request!'s
   serialization lock; the era atom follows protocol/modern-conn?."
  [era & [version]]
  {:transport :streamable-http
   :era (atom (when era {:era era :version (or version "2026-07-28")}))})

(deftest modern?-cases
  (is (true? (mcp/modern? (era-conn :modern))))
  (is (false? (mcp/modern? (era-conn :legacy))))
  (is (false? (mcp/modern? (era-conn nil))))
  (is (false? (mcp/modern? {:transport :stdio}))
      "a bare transport map (no era slot) is legacy"))

(deftest request!-merges-modern-meta
  (let [calls (atom [])]
    (with-redefs [http/request!
                  (fn [_conn _method params _timeout _on-notification]
                    (swap! calls conj params)
                    {:ok true})]
      (testing "a modern conn carries the era _meta"
        (is (= {:ok true}
               (mcp/request! (era-conn :modern) "tools/call" {:name "t"})))
        (is (= {:name "t"
                :_meta {(protocol/meta-key "protocolVersion") "2026-07-28"
                        (protocol/meta-key "clientInfo") protocol/client-info
                        (protocol/meta-key "clientCapabilities") {}}}
               (first @calls))))
      (testing "caller _meta keys survive"
        (reset! calls [])
        (mcp/request! (era-conn :modern) "tools/call"
                      {:_meta {:progressToken "p1"}})
        (is (= "p1" (get-in (first @calls) [:_meta :progressToken])))
        (is (= "2026-07-28"
               (get-in (first @calls) [:_meta (protocol/meta-key "protocolVersion")]))))
      (testing "a legacy conn sends no _meta"
        (reset! calls [])
        (mcp/request! (era-conn :legacy) "tools/list" {})
        (is (= {} (first @calls))))
      (testing "a conn without an era slot sends no _meta"
        (reset! calls [])
        (mcp/request! {:transport :streamable-http} "tools/list" {:x 1})
        (is (= {:x 1} (first @calls)))))))

(deftest notify!-merges-modern-meta
  (let [sent (atom [])]
    (with-redefs [http/send-async! (fn [_conn msg] (swap! sent conj msg))]
      (mcp/notify! (era-conn :modern) "notifications/cancelled" {:requestId 7})
      (is (= {:requestId 7
              :_meta {(protocol/meta-key "protocolVersion") "2026-07-28"
                      (protocol/meta-key "clientInfo") protocol/client-info
                      (protocol/meta-key "clientCapabilities") {}}}
             (-> @sent first :params)))
      (reset! sent [])
      (mcp/notify! (era-conn :legacy) "notifications/initialized" {})
      (is (= {} (-> @sent first :params)))
      (reset! sent [])
      (mcp/notify! {:transport :streamable-http} "notifications/initialized" {})
      (is (= {} (-> @sent first :params))
          "a conn without an era slot is untouched"))))

(deftest request!-retries-request-state-only-mrtr
  (let [calls (atom [])
        rounds (atom [{:resultType "input_required" :requestState "s1"}
                      {:resultType "input_required" :requestState "s2"}
                      {:ok true}])]
    (with-redefs [http/request!
                  (fn [_conn _method params _timeout _on-notification]
                    (swap! calls conj params)
                    (let [r (first @rounds)] (swap! rounds rest) r))]
      (is (= {:ok true}
             (mcp/request! (era-conn :modern) "tools/call" {:name "t"})))
      (is (= 3 (count @calls)) "two retries")
      (is (= "s1" (:requestState (nth @calls 1))) "the first state is echoed")
      (is (= "s2" (:requestState (nth @calls 2))) "the latest state is echoed")
      (is (= {:name "t"} (dissoc (nth @calls 1) :requestState :_meta))
          "the same request is retried")
      (is (= "2026-07-28"
             (get-in (nth @calls 2) [:_meta (protocol/meta-key "protocolVersion")]))
          "retries keep the era _meta"))))

(deftest request!-bounds-mrtr-retries
  (let [calls (atom 0)]
    (with-redefs [http/request!
                  (fn [_conn _method _params _timeout _on-notification]
                    (swap! calls inc)
                    {:resultType "input_required" :requestState "same"})]
      (let [e (try (mcp/request! (era-conn :modern) "tools/call" {:name "t"})
                   nil
                   (catch Exception e e))]
        (is (some? e))
        (is (= :mcp-error (:type (ex-data e))))
        (is (= :input-required (:result-type (ex-data e))))
        (is (= 2 (:retries (ex-data e))))
        (is (= 3 @calls) "two retries, then the error")))))

(deftest request!-refuses-input-requests
  (let [calls (atom 0)
        result {:resultType "input_required"
                :inputRequests {"confirm" {:method "elicitation/create"}
                                "sample" {:method "sampling/createMessage"}}}]
    (with-redefs [http/request!
                  (fn [_conn _method _params _timeout _on-notification]
                    (swap! calls inc)
                    result)]
      (let [e (try (mcp/request! (era-conn :modern) "tools/call" {})
                   nil
                   (catch Exception e e))]
        (is (= 1 @calls) "no retry when the server asked for input")
        (is (= :mcp-error (:type (ex-data e))))
        (is (= :input-required (:result-type (ex-data e))))
        (is (= (:inputRequests result) (:input-requests (ex-data e))))
        (is (re-find #"confirm" (ex-message e)))
        (is (re-find #"elicitation/create" (ex-message e)))
        (is (re-find #"sampling/createMessage" (ex-message e)))))))

(deftest request!-passes-through-result-types
  (testing "absent and complete results pass through"
    (with-redefs [http/request! (fn [_ _ _ _ _] {:ok 1})]
      (is (= {:ok 1} (mcp/request! (era-conn :modern) "tools/call" {}))))
    (with-redefs [http/request! (fn [_ _ _ _ _]
                                  {:resultType "complete" :ok 1})]
      (is (= {:resultType "complete" :ok 1}
             (mcp/request! (era-conn :modern) "tools/call" {})))))
  (testing "a legacy conn does not interpret resultType"
    (with-redefs [http/request! (fn [_ _ _ _ _]
                                  {:resultType "input_required"
                                   :requestState "s"})]
      (is (= {:resultType "input_required" :requestState "s"}
             (mcp/request! (era-conn :legacy) "tools/call" {}))))))

;; ─── Era detection (stdio) ────────────────────────────────────────────────

(defn- stdio-conn
  "A stdio conn for establish! tests: the era and revision atoms the
   transports carry in production."
  []
  {:transport :stdio
   :era (atom nil)
   :protocol-version (atom nil)})

(defn- discover-result
  "A minimal DiscoverResult."
  [& [versions]]
  {:supportedVersions (or versions ["2026-07-28"])
   :capabilities {:tools {}}
   :_meta {:io.modelcontextprotocol/serverInfo {:name "modern" :version "1"}}})

(deftest establish!-detects-modern-with-one-discover
  (let [methods (atom [])
        conn (stdio-conn)]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover" (discover-result)
                      "tools/list" {:tools [{:name "t"}]}
                      (throw (ex-info "unexpected request" {:method method}))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! conn)]
        (is (= "2026-07-28" (:protocol-version est)))
        (is (= {:name "modern" :version "1"} (:server-info est)))
        (is (= [{:name "t"}] (:tools est)))
        (is (mcp/modern? conn))
        (is (= "2026-07-28" (protocol/era-version conn)))
        (is (= "2026-07-28" @(:protocol-version conn)))
        (is (= ["server/discover" "tools/list"] @methods)
            "detection's round trip is reused by establish!; no initialize")))))

(deftest establish!-falls-back-to-initialize
  (let [methods (atom [])
        conn (stdio-conn)]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover"
                      (throw (protocol/mcp-error "MCP error -32601: Method not found"
                                                 {:code -32601}))
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}
                      (throw (ex-info "unexpected request" {:method method}))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! conn)]
        (is (= "2025-11-25" (:protocol-version est)))
        (is (not (mcp/modern? conn)))
        (is (= "2025-11-25" (protocol/era-version conn)))
        (is (= "2025-11-25" @(:protocol-version conn)))
        (is (= ["server/discover" "initialize"] @methods))))))

(deftest establish!-dual-era-discover-falls-back
  ;; a server that answers server/discover but advertises only legacy
  ;; revisions is dual-era: run the handshake instead of erroring
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover" (discover-result ["2025-11-25"])
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [conn (stdio-conn)]
        (is (= "2025-11-25" (:protocol-version (mcp/establish! conn))))
        (is (not (mcp/modern? conn)))
        (is (= ["server/discover" "initialize"] @methods))))))

(deftest establish!-negotiates-32022
  (testing "a legacy overlap in data.supported falls back to the handshake"
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (case method
                      "server/discover"
                      (throw (protocol/mcp-error "unsupported"
                                                 {:code -32022
                                                  :data {:supported ["2025-11-25"]}}))
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                  mcp/notify! (fn [_ _ _] nil)]
      (is (= "2025-11-25" (:protocol-version (mcp/establish! (stdio-conn)))))))
  (testing "no overlap in data.supported errors"
    (with-redefs [mcp/request!
                  (fn [_ _method _params & _]
                    (throw (protocol/mcp-error "unsupported"
                                               {:code -32022
                                                :data {:supported ["1999-01-01"]}})))
                  mcp/notify! (fn [_ _ _] nil)]
      (is (thrown-with-msg? Exception #"no protocol revision"
                            (mcp/establish! (stdio-conn))))))
  (testing "a header/era rejection surfaces"
    (with-redefs [mcp/request!
                  (fn [_ _method _params & _]
                    (throw (protocol/mcp-error "header mismatch" {:code -32020})))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [e (try (mcp/establish! (stdio-conn)) nil (catch Exception e e))]
        (is (= -32020 (:code (ex-data e))))))))

(deftest establish!-probe-timeout-falls-back
  (with-redefs [mcp/request!
                (fn [_ method _params & _]
                  (case method
                    "server/discover"
                    (throw (protocol/mcp-error "timed out" {:timeout-ms 10
                                                            :method method}))
                    "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                mcp/notify! (fn [_ _ _] nil)]
    (let [conn (stdio-conn)]
      (is (= "2025-11-25" (:protocol-version (mcp/establish! conn))))
      (is (not (mcp/modern? conn))))))

(deftest establish!-recovers-a-slow-modern-server
  ;; probe timeout, then initialize fails with an error: era-ambiguous, so
  ;; discover gets one more try with the full timeout
  (let [discovers (atom 0)
        methods (atom [])]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover"
                      (if (= 1 (swap! discovers inc))
                        (throw (protocol/mcp-error "timed out" {:timeout-ms 10
                                                                :method method}))
                        (discover-result))
                      "initialize"
                      (throw (protocol/mcp-error "MCP error -32601: Method not found"
                                                 {:code -32601}))
                      "tools/list" {:tools []}))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [conn (stdio-conn)
            est (mcp/establish! conn)]
        (is (mcp/modern? conn))
        (is (= "2026-07-28" (:protocol-version est)))
        (is (= ["server/discover" "initialize" "server/discover" "tools/list"]
               @methods)
            "the recovery probe is the second discover")))))

(deftest establish!-honors-the-era-hint
  (testing ":legacy skips the probe"
    (let [methods (atom [])]
      (with-redefs [mcp/request!
                    (fn [_ method _params & _]
                      (swap! methods conj method)
                      {:protocolVersion "2025-11-25" :capabilities {}})
                    mcp/notify! (fn [_ _ _] nil)]
        (is (= "2025-11-25"
               (:protocol-version (mcp/establish! (stdio-conn) {:protocol-era :legacy}))))
        (is (= ["initialize"] @methods)))))
  (testing ":modern runs discover directly"
    (let [methods (atom [])]
      (with-redefs [mcp/request!
                    (fn [_ method _params & _]
                      (swap! methods conj method)
                      (case method
                        "server/discover" (discover-result)
                        "tools/list" {:tools []}))
                    mcp/notify! (fn [_ _ _] nil)]
        (is (= "2026-07-28"
               (:protocol-version (mcp/establish! (stdio-conn) {:protocol-era :modern}))))
        (is (= ["server/discover" "tools/list"] @methods))))))

;; ─── Era detection (streamable HTTP) ──────────────────────────────────────

(defn- http-conn
  "A streamable-HTTP conn for establish! tests: the era, revision and
   session atoms the transport carries in production."
  []
  {:transport :streamable-http
   :url "http://server"
   :era (atom nil)
   :protocol-version (atom nil)
   :session-id (atom nil)})

(deftest establish!-detects-modern-http-with-one-discover
  (let [methods (atom [])
        conn (http-conn)]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover" (discover-result)
                      "tools/list" {:tools [{:name "t"}]}
                      (throw (ex-info "unexpected request" {:method method}))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! conn)]
        (is (mcp/modern? conn))
        (is (= "2026-07-28" (:protocol-version est)))
        (is (= [{:name "t"}] (:tools est)))
        (is (= "2026-07-28" @(:protocol-version conn)))
        (is (= ["server/discover" "tools/list"] @methods)
            "detection's round trip is reused by establish!; no initialize")))))

(deftest establish!-seeds-the-http-probe-version
  ;; the probe POST must carry MCP-Protocol-Version; a legacy initialize
  ;; POST must not
  (let [seen (atom [])
        conn (http-conn)]
    (with-redefs [mcp/request!
                  (fn [conn method _params & _]
                    (swap! seen conj [method @(:protocol-version conn)])
                    (case method
                      "server/discover" (throw (protocol/mcp-error
                                                "HTTP 404" {:code -32601
                                                            :status 404}))
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                  mcp/notify! (fn [_ _ _] nil)]
      (is (= "2025-11-25" (:protocol-version (mcp/establish! conn))))
      (is (= [["server/discover" "2026-07-28"] ["initialize" nil]] @seen)
          "the legacy initialize POST dropped the probe's version header"))))

(deftest establish!-http-32022-negotiates
  (testing "a -32022 naming a modern revision continues modern"
    (let [discovers (atom 0)]
      (with-redefs [mcp/request!
                    (fn [_ method _params & _]
                      (case method
                        "server/discover"
                        (if (= 1 (swap! discovers inc))
                          (throw (protocol/mcp-error
                                  "unsupported"
                                  {:code -32022
                                   :data {:supported ["2026-07-28"]}}))
                          (discover-result))
                        "tools/list" {:tools []}))
                    mcp/notify! (fn [_ _ _] nil)]
        (let [conn (http-conn)]
          (is (mcp/modern? (do (mcp/establish! conn) conn)))
          (is (= 2 @discovers))))))
  (testing "a legacy overlap in data.supported falls back to the handshake"
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (case method
                      "server/discover"
                      (throw (protocol/mcp-error "unsupported"
                                                 {:code -32022
                                                  :data {:supported ["2025-11-25"]}}))
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [conn (http-conn)]
        (is (= "2025-11-25" (:protocol-version (mcp/establish! conn))))
        (is (not (mcp/modern? conn))))))
  (testing "no overlap in data.supported errors"
    (with-redefs [mcp/request!
                  (fn [_ _method _params & _]
                    (throw (protocol/mcp-error "unsupported"
                                               {:code -32022
                                                :data {:supported ["1999-01-01"]}})))
                  mcp/notify! (fn [_ _ _] nil)]
      (is (thrown-with-msg? Exception #"no protocol revision"
                            (mcp/establish! (http-conn))))))
  (testing "a header/era rejection surfaces"
    (with-redefs [mcp/request!
                  (fn [_ _method _params & _]
                    (throw (protocol/mcp-error "header mismatch"
                                               {:code -32020 :status 400})))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [e (try (mcp/establish! (http-conn)) nil (catch Exception e e))]
        (is (= -32020 (:code (ex-data e))))))))

(deftest establish!-http-non-modern-error-falls-back
  ;; a 404 + JSON-RPC body is not an era signal: run the handshake
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (case method
                      "server/discover" (throw (protocol/mcp-error
                                                "HTTP 404" {:code -32601
                                                            :status 404}))
                      "initialize" {:protocolVersion "2025-11-25" :capabilities {}}))
                  mcp/notify! (fn [_ _ _] nil)]
      (is (= "2025-11-25" (:protocol-version (mcp/establish! (http-conn)))))
      (is (= ["server/discover" "initialize"] @methods)))))

(deftest establish!-http-auth-failure-aborts
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fn [_ method _params & _]
                    (swap! methods conj method)
                    (throw (protocol/mcp-error "MCP connect failed: HTTP 401: unauthorized"
                                               {:status 401})))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [e (try (mcp/establish! (http-conn)) nil (catch Exception e e))]
        (is (= 401 (:status (ex-data e))))
        (is (= ["server/discover"] @methods)
            "authentication is not an era signal: no initialize attempt")))))

(deftest establish!-http-recovers-a-slow-modern-server
  (let [discovers (atom 0)
        seen (atom [])]
    (with-redefs [mcp/request!
                  (fn [conn method _params & _]
                    (swap! seen conj [method @(:protocol-version conn)])
                    (case method
                      "server/discover"
                      (if (= 1 (swap! discovers inc))
                        (throw (protocol/mcp-error "timed out" {:timeout-ms 10
                                                                :method method}))
                        (discover-result))
                      "initialize" (throw (protocol/mcp-error
                                           "HTTP 404" {:code -32601
                                                       :status 404}))
                      "tools/list" {:tools []}))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [conn (http-conn)]
        (is (mcp/modern? (do (mcp/establish! conn) conn)))
        (is (= [["server/discover" "2026-07-28"]
                ["initialize" nil]
                ["server/discover" "2026-07-28"]
                ["tools/list" "2026-07-28"]]
               @seen)
            "the recovery probe re-seeds the version header")))))

(deftest establish!-http-honors-the-era-hint
  (testing ":modern runs discover directly (no probe)"
    (let [methods (atom [])]
      (with-redefs [mcp/request!
                    (fn [_ method _params & _]
                      (swap! methods conj method)
                      (case method
                        "server/discover" (discover-result)
                        "tools/list" {:tools []}))
                    mcp/notify! (fn [_ _ _] nil)]
        (is (= "2026-07-28"
               (:protocol-version (mcp/establish! (http-conn) {:protocol-era :modern}))))
        (is (= ["server/discover" "tools/list"] @methods)))))
  (testing ":legacy runs the handshake"
    (let [methods (atom [])]
      (with-redefs [mcp/request!
                    (fn [_ method _params & _]
                      (swap! methods conj method)
                      {:protocolVersion "2025-11-25" :capabilities {}})
                    mcp/notify! (fn [_ _ _] nil)]
        (is (= "2025-11-25"
               (:protocol-version (mcp/establish! (http-conn) {:protocol-era :legacy}))))
        (is (= ["initialize"] @methods))))))

;; ─── establish! capability gating ─────────────────────────────────────────

(defn- fake-conn [methods result-for]
  (fn [_conn method _params & _opts]
    (swap! methods conj method)
    (result-for method)))

(deftest establish!-queries-only-advertised-capabilities
  (let [methods (atom [])
        notified (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :serverInfo {:name "fake" :version "1"}
                                               :capabilities {:tools {} :prompts {} :resources {}}}
                                 "tools/list" {:tools [{:name "t"}]}
                                 "prompts/list" {:prompts [{:name "p"}]}
                                 "resources/list" {:resources [{:name "r" :uri "u"}]}
                                 "resources/templates/list"
                                 {:resourceTemplates [{:name "rt" :uriTemplate "u/{x}"}]}
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_conn method _params] (swap! notified conj method))]
      (let [est (mcp/establish! :conn)]
        (is (= "2025-11-25" (:protocol-version est)))
        (is (= {:name "fake" :version "1"} (:server-info est)))
        (is (= [{:name "t"}] (:tools est)))
        (is (= [{:name "p"}] (:prompts est)))
        (is (= [{:name "r" :uri "u"}] (:resources est)))
        (is (= [{:name "rt" :uriTemplate "u/{x}"}] (:resource-templates est)))
        (is (= ["notifications/initialized"] @notified))
        (is (= #{"initialize" "tools/list" "prompts/list"
                 "resources/list" "resources/templates/list"}
               (set @methods)))))))

(deftest establish!-skips-unadvertised-capabilities
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :capabilities {}}
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! :conn)]
        (is (= [] (:tools est)))
        (is (= [] (:prompts est)))
        (is (= [] (:resources est)))
        (is (= [] (:resource-templates est)))
        (is (= ["initialize"] @methods)
            "only the handshake runs without advertised capabilities")))))

(deftest establish!-rejects-unsupported-revisions
  (with-redefs [mcp/request! (fn [_ _ _ & _] {:protocolVersion "1999-01-01"})
                mcp/notify! (fn [_ _ _] nil)]
    (let [e (try (mcp/establish! :conn) nil (catch Exception e e))]
      (is (some? e))
      (is (= :mcp-error (:type (ex-data e))))
      (is (re-find #"unsupported protocol version" (ex-message e))))))

(deftest establish!-tolerates-missing-template-support
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :capabilities {:resources {}}}
                                 "resources/list" {:resources [{:name "r" :uri "u"}]}
                                 "resources/templates/list"
                                 (throw (ex-info "Method not found" {:code -32601}))
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! :conn)]
        (is (= [{:name "r" :uri "u"}] (:resources est)))
        (is (= [] (:resource-templates est)))))))

(deftest establish!-propagates-other-resource-errors
  (with-redefs [mcp/request!
                (fn [_ method _ & _]
                  (case method
                    "initialize" {:protocolVersion "2025-11-25"
                                  :capabilities {:resources {}}}
                    "resources/list" {:resources []}
                    "resources/templates/list"
                    (throw (ex-info "boom" {:code -32000}))
                    nil))
                mcp/notify! (fn [_ _ _] nil)]
    (is (thrown? Exception (mcp/establish! :conn)))))

;; ─── connect! conn-ref hand-off ───────────────────────────────────────────

(deftest connect!-repoints-the-transport-conn-ref
  ;; The transports' internal callbacks (stdio's notification handler)
  ;; capture the conn before :capabilities is assoc'd on; connect! must
  ;; repoint the transport's :conn-ref at the map the caller stores, or
  ;; e.g. the extension's list_changed handler sees no capabilities.
  (let [conn-ref (atom nil)
        fake-conn {:transport :stdio :conn-ref conn-ref}]
    (with-redefs [stdio/connect! (fn [_ _] fake-conn)
                  mcp/establish! (fn [_ _]
                                   {:protocol-version "2025-11-25"
                                    :capabilities {:tools {}}})]
      (let [{:keys [conn]} (mcp/connect! {:command "x"} {})]
        (is (identical? conn @conn-ref)
            "the transport's callback sees the returned conn")
        (is (= {:tools {}} (:capabilities @conn-ref)))))))

;; ─── Listeners (2026-07-28 subscriptions) ─────────────────────────────────

(defn- listener-conn
  "A conn shaped like client/connect!'s for a modern streamable-HTTP
   server: the transport keys the listener touches plus its slots."
  []
  (assoc (http/connect! "http://server" {})
         :era (atom {:era :modern :version protocol/modern-protocol-version})
         :listener (atom nil)))

(defn- wait-for
  "Poll PRED for up to 2s — the listener runs on its own thread."
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (< n 200) (do (Thread/sleep 10) (recur (inc n)))
      :else false)))

(defn- listen-state
  [conn]
  @(deref (:listener conn)))

(defn- subscription-meta
  [id]
  {:_meta {(protocol/meta-key "subscriptionId") id}})

(defn- ack-notification
  [id agreed]
  {:jsonrpc "2.0"
   :method protocol/ack-method
   :params (merge {:notifications agreed} (subscription-meta id))})

(deftest listen!-requires-a-modern-streamable-conn
  (is (thrown-with-msg? Exception #"requires the 2026-07-28 era"
                        (mcp/listen! (assoc (http/connect! "http://server" {})
                                            :listener (atom nil))
                                     {:toolsListChanged true})))
  (is (thrown-with-msg? Exception #"not available over sse"
                        (mcp/listen! (assoc (listener-conn) :transport :sse)
                                     {:toolsListChanged true}))))

(deftest listen!-observes-the-frames-and-the-agreed-filter
  (let [conn (listener-conn)
        observed (atom [])
        filters (atom [])
        ended (promise)]
    (with-redefs [http/listen! (fn [_conn filter on-frame]
                                 (swap! filters conj filter)
                                 (on-frame (ack-notification 7 {:toolsListChanged true}))
                                 (on-frame {:jsonrpc "2.0"
                                            :method "notifications/tools/list_changed"
                                            :params (subscription-meta 7)})
                                 {:stop! (fn [] (deliver ended :stopped))
                                  :ended ended})]
      (mcp/listen! conn {:toolsListChanged true :promptsListChanged true}
                   {:on-frame (fn [msg] (swap! observed conj (:method msg)))})
      (is (true? (mcp/listening? conn)))
      (is (wait-for #(= 2 (:frames (listen-state conn)))))
      (is (= [{:toolsListChanged true :promptsListChanged true}] @filters))
      (is (true? (:first-ack? (listen-state conn))))
      (is (= {:toolsListChanged true} (:agreed (listen-state conn)))
          "the filter the server dropped stays visible")
      (is (= [protocol/ack-method "notifications/tools/list_changed"] @observed)
          "the observer sees every frame of the subscription")
      (mcp/close! conn)
      (is (false? (mcp/listening? conn)))
      (is (true? @(:closed conn))))))

(deftest listen!-re-establishes-with-a-bounded-budget
  (let [conn (listener-conn)
        calls (atom 0)
        restored (atom 0)]
    (with-redefs-fn {#'mcp/listen-backoff-ms [0 0 0]}
      (fn []
        (with-redefs [http/listen! (fn [_ _ _]
                                     (swap! calls inc)
                                     {:stop! (fn [])
                                      :ended (doto (promise) (deliver :ended))})]
          (mcp/listen! conn {:toolsListChanged true}
                       {:on-restored (fn [] (swap! restored inc))})
          (is (wait-for #(true? (:failed? (listen-state conn)))))
          (is (= 3 @calls) "the immediate open plus two backed-off re-listens")
          (is (= 1 @restored) "one resync per listener, after the first gap")
          (is (false? (mcp/listening? conn)))
          (is (true? @(:closed conn))
              "the budget is spent: the next use reconnects from scratch"))))))

(deftest listen!-close-stops-a-live-listener
  (let [conn (listener-conn)
        stops (atom 0)
        ended (promise)]
    (with-redefs [http/listen! (fn [_ _ _]
                                 {:stop! (fn [] (swap! stops inc)
                                           (deliver ended :stopped))
                                  :ended ended})]
      (mcp/listen! conn {:toolsListChanged true})
      (is (wait-for #(some? (:stop! (listen-state conn)))))
      (mcp/close! conn)
      (is (= 1 @stops) "close! aborts the subscription")
      (is (false? (mcp/listening? conn)))
      (is (wait-for #(nil? (:stop! (listen-state conn))))
          "no re-listen after a stop"))))

(deftest listen!-close-during-the-open-still-stops
  ;; close! may land while the attempt is still opening: the attempt must
  ;; notice the stop flag itself instead of parking on a stream nobody
  ;; will ever stop (the caller's stop finds no :stop! to call yet)
  (let [conn (listener-conn)
        stops (atom 0)
        ended (promise)
        opening (promise)
        proceed (promise)]
    (with-redefs [http/listen! (fn [_ _ _]
                                 (deliver opening true)
                                 (deref proceed 2000 nil)
                                 {:stop! (fn [] (swap! stops inc)
                                           (deliver ended :stopped))
                                  :ended ended})]
      (mcp/listen! conn {:toolsListChanged true})
      (is (true? (deref opening 1000 false)) "the attempt opened")
      (mcp/close! conn)
      (deliver proceed true)
      (is (= :stopped (deref ended 2000 ::timeout))
          "the attempt ends itself when the caller's stop finds no stop!")
      (is (wait-for #(= 1 @stops)))
      (is (false? (mcp/listening? conn))))))

(deftest listen!-dispatches-by-transport
  (let [conn (assoc (listener-conn) :transport :stdio :listen (atom nil))
        filter (atom nil)]
    (with-redefs-fn {#'mcp/listen-backoff-ms [0 0]}
      (fn []
        (with-redefs [stdio/listen! (fn [_conn f _on-frame]
                                      (reset! filter f)
                                      {:stop! (fn [])
                                       :ended (doto (promise) (deliver :ended))})
                      stdio/close! (fn [_] nil)]
          (mcp/listen! conn {:toolsListChanged true})
          (is (wait-for #(= {:toolsListChanged true} @filter)))
          (is (wait-for #(true? (:failed? (listen-state conn))))))))))

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
                  mcp/establish! (fn [_]
                                   {:protocol-version "2025-11-25"
                                    :capabilities {:tools {}}})]
      (let [{:keys [conn]} (mcp/connect! {:command "x"} {})]
        (is (identical? conn @conn-ref)
            "the transport's callback sees the returned conn")
        (is (= {:tools {}} (:capabilities @conn-ref)))))))

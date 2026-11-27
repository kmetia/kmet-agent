(ns kmet.mcp.test-transport
  "Wire constants, pure protocol helpers and the connection contract:
   constants, error construction and tools/call result formatting."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.mcp.transport :as transport]))

(deftest constants
  (testing "revisions and identity"
    (is (string? transport/protocol-version))
    (is (some #{transport/protocol-version} transport/supported-protocol-versions)
        "the requested revision is supported")
    (is (apply distinct? transport/supported-protocol-versions))
    (is (= transport/protocol-version (first transport/supported-protocol-versions))
        "newest first — a server picking an older revision is still valid")
    (is (every? string? transport/supported-protocol-versions)))
  (testing "timeouts"
    (is (pos? transport/default-request-timeout-ms))
    (is (pos? transport/initialize-timeout-ms))
    (is (pos? transport/list-page-timeout-ms)))
  (testing "clientInfo"
    (is (string? (:name transport/client-info)))
    (is (string? (:version transport/client-info)))))

(deftest modern-constants
  (testing "revisions"
    (is (string? transport/modern-protocol-version))
    (is (some #{transport/modern-protocol-version} transport/modern-supported-versions)
        "the requested modern revision is supported")
    (is (apply distinct? transport/modern-supported-versions))
    (is (every? string? transport/modern-supported-versions))
    (is (not (some #{transport/modern-protocol-version}
                   transport/supported-protocol-versions))
        "the modern revision is not a handshake revision"))
  (testing "probe timeout is seconds, not the 60s handshake"
    (is (<= 10000 transport/probe-timeout-ms 15000)))
  (testing "error codes"
    (is (= #{-32020 -32021 -32022} transport/modern-error-codes))))

(deftest modern-meta
  (testing "meta-key namespaces the key"
    (is (= :io.modelcontextprotocol/clientInfo (transport/meta-key "clientInfo")))
    (is (= :io.modelcontextprotocol/clientInfo (transport/meta-key :clientInfo))))
  (testing "request meta carries revision, identity and empty capabilities"
    (let [m (transport/modern-request-meta "2026-07-28")]
      (is (= "2026-07-28" (:io.modelcontextprotocol/protocolVersion m)))
      (is (= transport/client-info (:io.modelcontextprotocol/clientInfo m)))
      (is (= {} (:io.modelcontextprotocol/clientCapabilities m))))))

(deftest modern-conn-era
  (testing "anything without a modern :era atom is legacy"
    (is (not (transport/modern-conn? {})))
    (is (not (transport/modern-conn? {:era (atom nil)})))
    (is (not (transport/modern-conn? {:era (atom {:era :legacy :version "2025-11-25"})}))))
  (testing "a modern conn supplies its negotiated revision"
    (is (transport/modern-conn? {:era (atom {:era :modern :version "2026-07-28"})}))
    (is (= "2026-07-28"
           (:io.modelcontextprotocol/protocolVersion
            (transport/conn-meta {:era (atom {:era :modern :version "2026-07-28"})}))))
    (is (= transport/modern-protocol-version
           (:io.modelcontextprotocol/protocolVersion
            (transport/conn-meta {:era (atom {:era :modern})})))
        "before a negotiation records a revision, the base modern one is used")))

(deftest modern-error-code-cases
  (is (transport/modern-error? {:code -32020}))
  (is (transport/modern-error? {:code -32022 :message "unsupported"}))
  (is (not (transport/modern-error? {:code -32601})))
  (is (not (transport/modern-error? {})))
  (is (not (transport/modern-error? nil))))

(deftest negotiate-version-cases
  (testing "modern wins over legacy"
    (is (= {:era :modern :version "2026-07-28"}
           (transport/negotiate-version ["2025-06-18" "2026-07-28"]))))
  (testing "the server's ordering is ignored"
    (is (= {:era :modern :version "2026-07-28"}
           (transport/negotiate-version ["2026-07-28" "2025-11-25"]))))
  (testing "legacy fallback picks our newest common revision"
    (is (= {:era :legacy :version "2025-11-25"}
           (transport/negotiate-version ["2025-03-26" "2025-11-25"]))))
  (testing "no overlap is nil"
    (is (nil? (transport/negotiate-version ["1999-01-01"])))
    (is (nil? (transport/negotiate-version [])))
    (is (nil? (transport/negotiate-version nil)))))

(deftest result-type-cases
  (is (= "complete" (transport/result-type {})))
  (is (= "complete" (transport/result-type nil)))
  (is (= "complete" (transport/result-type {:resultType "complete"})))
  (is (= "input_required" (transport/result-type {:resultType "input_required"})))
  (is (= "future_kind" (transport/result-type {:resultType "future_kind"}))))

(deftest progress-tokens-are-unique
  (let [tokens (repeatedly 100 transport/progress-token)]
    (is (every? string? tokens))
    (is (apply distinct? tokens))))

(deftest mcp-error-shape
  (let [e (transport/mcp-error "boom")]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom" (ex-message e)))
    (is (= :mcp-error (:type (ex-data e)))))
  (let [e (transport/mcp-error "timeout" {:timeout-ms 5 :method "tools/call"})]
    (is (= :mcp-error (:type (ex-data e))))
    (is (= 5 (:timeout-ms (ex-data e))))
    (is (= "tools/call" (:method (ex-data e))))))

(deftest format-result-cases
  (testing "text blocks are joined"
    (is (= {:text "a\nb" :is-error false}
           (transport/format-result
            {:content [{:type "text" :text "a"}
                       {:type "text" :text "b"}]}))))
  (testing "isError is surfaced"
    (is (= {:text "nope" :is-error true}
           (transport/format-result
            {:content [{:type "text" :text "nope"}] :isError true}))))
  (testing "image blocks are summarized, not rendered"
    (let [r (transport/format-result
             {:content [{:type "image" :mimeType "image/png" :data "YWJj"}]})]
      (is (str/includes? (:text r) "image/png"))
      (is (str/includes? (:text r) "3 bytes") "the decoded size, not the base64 length")
      (is (false? (:is-error r)))))
  (testing "audio blocks are summarized like images"
    (is (= "[audio: audio/mpeg, 3 bytes — not rendered]"
           (:text (transport/format-result
                   {:content [{:type "audio" :mimeType "audio/mpeg" :data "YWJj"}]})))))
  (testing "an embedded resource renders its text"
    (is (= "hi" (:text (transport/format-result
                        {:content [{:type "resource"
                                    :resource {:uri "file:///x" :text "hi"}}]})))))
  (testing "an embedded resource blob is summarized"
    (is (= "[resource file:///x: application/octet-stream, 3 bytes — not rendered]"
           (:text (transport/format-result
                   {:content [{:type "resource"
                               :resource {:uri "file:///x" :blob "YWJj"
                                          :mimeType "application/octet-stream"}}]})))))
  (testing "a resource link renders its name or uri"
    (is (= "[resource link: file:///x]"
           (:text (transport/format-result
                   {:content [{:type "resource_link" :uri "file:///x"}]}))))
    (is (= "[resource link: readme]"
           (:text (transport/format-result
                   {:content [{:type "resource_link" :name "readme"
                               :uri "file:///x"}]})))))
  (testing "structuredContent is pretty JSON when there is no text"
    (let [r (transport/format-result
             {:content [] :structuredContent {:a [1 2]}})]
      (is (str/includes? (:text r) "\"a\""))
      (is (str/includes? (:text r) "1"))))
  (testing "empty content falls back"
    (is (= {:text "(no text content)" :is-error false}
           (transport/format-result {:content []})))
    (is (= "(no text content)" (:text (transport/format-result {})))))
  (testing "text wins over structuredContent"
    (is (= "hi" (:text (transport/format-result
                        {:content [{:type "text" :text "hi"}]
                         :structuredContent {:a 1}}))))))

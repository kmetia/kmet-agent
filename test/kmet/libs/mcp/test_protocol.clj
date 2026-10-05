(ns kmet.libs.mcp.test-protocol
  "Pure protocol helpers: constants, error construction and tools/call
   result formatting."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.libs.mcp.protocol :as protocol]))

(deftest constants
  (testing "revisions and identity"
    (is (string? protocol/protocol-version))
    (is (some #{protocol/protocol-version} protocol/supported-protocol-versions)
        "the requested revision is supported")
    (is (apply distinct? protocol/supported-protocol-versions))
    (is (= protocol/protocol-version (first protocol/supported-protocol-versions))
        "newest first — a server picking an older revision is still valid")
    (is (every? string? protocol/supported-protocol-versions)))
  (testing "timeouts"
    (is (pos? protocol/default-request-timeout-ms))
    (is (pos? protocol/initialize-timeout-ms))
    (is (pos? protocol/list-page-timeout-ms)))
  (testing "clientInfo"
    (is (string? (:name protocol/client-info)))
    (is (string? (:version protocol/client-info)))))

(deftest modern-constants
  (testing "revisions"
    (is (string? protocol/modern-protocol-version))
    (is (some #{protocol/modern-protocol-version} protocol/modern-supported-versions)
        "the requested modern revision is supported")
    (is (apply distinct? protocol/modern-supported-versions))
    (is (every? string? protocol/modern-supported-versions))
    (is (not (some #{protocol/modern-protocol-version}
                   protocol/supported-protocol-versions))
        "the modern revision is not a handshake revision"))
  (testing "probe timeout is seconds, not the 60s handshake"
    (is (<= 10000 protocol/probe-timeout-ms 15000)))
  (testing "error codes"
    (is (= #{-32020 -32021 -32022} protocol/modern-error-codes))))

(deftest modern-meta
  (testing "meta-key namespaces the key"
    (is (= :io.modelcontextprotocol/clientInfo (protocol/meta-key "clientInfo")))
    (is (= :io.modelcontextprotocol/clientInfo (protocol/meta-key :clientInfo))))
  (testing "request meta carries revision, identity and empty capabilities"
    (let [m (protocol/modern-request-meta "2026-07-28")]
      (is (= "2026-07-28" (:io.modelcontextprotocol/protocolVersion m)))
      (is (= protocol/client-info (:io.modelcontextprotocol/clientInfo m)))
      (is (= {} (:io.modelcontextprotocol/clientCapabilities m))))))

(deftest modern-conn-era
  (testing "anything without a modern :era atom is legacy"
    (is (not (protocol/modern-conn? {})))
    (is (not (protocol/modern-conn? {:era (atom nil)})))
    (is (not (protocol/modern-conn? {:era (atom {:era :legacy :version "2025-11-25"})}))))
  (testing "a modern conn supplies its negotiated revision"
    (is (protocol/modern-conn? {:era (atom {:era :modern :version "2026-07-28"})}))
    (is (= "2026-07-28"
           (:io.modelcontextprotocol/protocolVersion
            (protocol/conn-meta {:era (atom {:era :modern :version "2026-07-28"})}))))
    (is (= protocol/modern-protocol-version
           (:io.modelcontextprotocol/protocolVersion
            (protocol/conn-meta {:era (atom {:era :modern})})))
        "before a negotiation records a revision, the base modern one is used")))

(deftest modern-error-code-cases
  (is (protocol/modern-error? {:code -32020}))
  (is (protocol/modern-error? {:code -32022 :message "unsupported"}))
  (is (not (protocol/modern-error? {:code -32601})))
  (is (not (protocol/modern-error? {})))
  (is (not (protocol/modern-error? nil))))

(deftest negotiate-version-cases
  (testing "modern wins over legacy"
    (is (= {:era :modern :version "2026-07-28"}
           (protocol/negotiate-version ["2025-06-18" "2026-07-28"]))))
  (testing "the server's ordering is ignored"
    (is (= {:era :modern :version "2026-07-28"}
           (protocol/negotiate-version ["2026-07-28" "2025-11-25"]))))
  (testing "legacy fallback picks our newest common revision"
    (is (= {:era :legacy :version "2025-11-25"}
           (protocol/negotiate-version ["2025-03-26" "2025-11-25"]))))
  (testing "no overlap is nil"
    (is (nil? (protocol/negotiate-version ["1999-01-01"])))
    (is (nil? (protocol/negotiate-version [])))
    (is (nil? (protocol/negotiate-version nil)))))

(deftest result-type-cases
  (is (= "complete" (protocol/result-type {})))
  (is (= "complete" (protocol/result-type nil)))
  (is (= "complete" (protocol/result-type {:resultType "complete"})))
  (is (= "input_required" (protocol/result-type {:resultType "input_required"})))
  (is (= "future_kind" (protocol/result-type {:resultType "future_kind"}))))

(deftest progress-tokens-are-unique
  (let [tokens (repeatedly 100 protocol/progress-token)]
    (is (every? string? tokens))
    (is (apply distinct? tokens))))

(deftest mcp-error-shape
  (let [e (protocol/mcp-error "boom")]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom" (ex-message e)))
    (is (= :mcp-error (:type (ex-data e)))))
  (let [e (protocol/mcp-error "timeout" {:timeout-ms 5 :method "tools/call"})]
    (is (= :mcp-error (:type (ex-data e))))
    (is (= 5 (:timeout-ms (ex-data e))))
    (is (= "tools/call" (:method (ex-data e))))))

(deftest format-result-cases
  (testing "text blocks are joined"
    (is (= {:text "a\nb" :is-error false}
           (protocol/format-result
            {:content [{:type "text" :text "a"}
                       {:type "text" :text "b"}]}))))
  (testing "isError is surfaced"
    (is (= {:text "nope" :is-error true}
           (protocol/format-result
            {:content [{:type "text" :text "nope"}] :isError true}))))
  (testing "image blocks are summarized, not rendered"
    (let [r (protocol/format-result
             {:content [{:type "image" :mimeType "image/png" :data "YWJj"}]})]
      (is (str/includes? (:text r) "image/png"))
      (is (str/includes? (:text r) "3 bytes") "the decoded size, not the base64 length")
      (is (false? (:is-error r)))))
  (testing "audio blocks are summarized like images"
    (is (= "[audio: audio/mpeg, 3 bytes — not rendered]"
           (:text (protocol/format-result
                   {:content [{:type "audio" :mimeType "audio/mpeg" :data "YWJj"}]})))))
  (testing "an embedded resource renders its text"
    (is (= "hi" (:text (protocol/format-result
                        {:content [{:type "resource"
                                    :resource {:uri "file:///x" :text "hi"}}]})))))
  (testing "an embedded resource blob is summarized"
    (is (= "[resource file:///x: application/octet-stream, 3 bytes — not rendered]"
           (:text (protocol/format-result
                   {:content [{:type "resource"
                               :resource {:uri "file:///x" :blob "YWJj"
                                          :mimeType "application/octet-stream"}}]})))))
  (testing "a resource link renders its name or uri"
    (is (= "[resource link: file:///x]"
           (:text (protocol/format-result
                   {:content [{:type "resource_link" :uri "file:///x"}]}))))
    (is (= "[resource link: readme]"
           (:text (protocol/format-result
                   {:content [{:type "resource_link" :name "readme"
                               :uri "file:///x"}]})))))
  (testing "structuredContent is pretty JSON when there is no text"
    (let [r (protocol/format-result
             {:content [] :structuredContent {:a [1 2]}})]
      (is (str/includes? (:text r) "\"a\""))
      (is (str/includes? (:text r) "1"))))
  (testing "empty content falls back"
    (is (= {:text "(no text content)" :is-error false}
           (protocol/format-result {:content []})))
    (is (= "(no text content)" (:text (protocol/format-result {})))))
  (testing "text wins over structuredContent"
    (is (= "hi" (:text (protocol/format-result
                        {:content [{:type "text" :text "hi"}]
                         :structuredContent {:a 1}}))))))

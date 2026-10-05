(ns kmet.libs.mcp.protocol
  "MCP wire constants and pure protocol helpers: the revisions this client
   speaks, the client identity, error construction and result formatting.

   Shared by the transports (kmet.libs.mcp.transport.*) and the client
   (kmet.libs.mcp.client). No I/O."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]))

(def default-request-timeout-ms
  "Per-request timeout when the caller passes none."
  120000)

(def initialize-timeout-ms
  "Timeout for the initialize handshake (60s — servers may be cold-started)."
  60000)

(def list-page-timeout-ms
  "Timeout for each catalog list page (tools/prompts/resources/templates)."
  30000)

(def probe-timeout-ms
  "Timeout for the 2026-07-28 era probe (server/discover before any
   handshake). Shorter than initialize-timeout-ms: a legacy server that
   answers unknown methods resolves immediately, and a silent one stalls
   seconds instead of a minute before the legacy fallback."
  10000)

(def protocol-version
  "The revision this client requests in initialize."
  "2025-11-25")

(def supported-protocol-versions
  "Legacy (handshake) revisions this client speaks. A server that answers
   `initialize` with a version outside this list is rejected — servers on
   older SDKs answer with their own latest revision, so all handshake-based
   revisions stay usable. The stateless 2026-07-28 revision and later are
   negotiated through modern-supported-versions instead."
  ["2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"])

(def client-info
  "The clientInfo sent in the initialize handshake."
  {:name "kmet-mcp-adapter" :version "0.1.0"})

(def modern-protocol-version
  "The 2026-07-28 revision: stateless (no initialize handshake), with
   per-request _meta and a resultType on every result."
  "2026-07-28")

(def modern-supported-versions
  "Modern revisions this client can send, newest first."
  [modern-protocol-version])

(def meta-ns
  "The namespace reserved for protocol-reserved _meta keys."
  "io.modelcontextprotocol")

(defn meta-key
  "A protocol-reserved _meta key: (meta-key \"clientInfo\") →
   :io.modelcontextprotocol/clientInfo."
  [k]
  (keyword meta-ns (name k)))

(def listen-method
  "The 2026-07-28 replacement for the broadcast list_changed
   notifications: one long-lived subscriptions/listen request per conn."
  "subscriptions/listen")

(def ack-method
  "The first frame of a subscription stream: the server's acknowledgment
   names the subscription id and the filter subset it honors."
  "notifications/subscriptions/acknowledged")

(defn subscription-id
  "The subscription id a server stamps on a subscription's notifications
   (_meta.io.modelcontextprotocol/subscriptionId), or nil for an unscoped
   message."
  [msg]
  (get-in msg [:params :_meta (meta-key "subscriptionId")]))

(defn modern-request-meta
  "The _meta map a 2026-07-28 request or notification carries inside its
   params: VERSION (the negotiated revision), the client identity, and
   empty client capabilities — kmet implements no elicitation, sampling or
   roots, so a conforming server never sends an inputRequests entry."
  [version]
  {(meta-key "protocolVersion") version
   (meta-key "clientInfo") client-info
   (meta-key "clientCapabilities") {}})

(defn modern-conn?
  "True when CONN's :era atom records the 2026-07-28 era (the atom and its
   shape are documented in kmet.libs.mcp.client); a conn without one — a
   bare transport map, a test fake — is legacy."
  [conn]
  (boolean (when-let [era (:era conn)]
             (= :modern (:era @era)))))

(defn era-version
  "The revision recorded on CONN's :era atom, or nil while the era is
   still unknown."
  [conn]
  (:version (some-> (:era conn) deref)))

(defn conn-meta
  "The _meta map to merge into a modern CONN's request/notification
   params: the revision recorded on its :era atom, else the base modern
   revision (before negotiation has recorded one)."
  [conn]
  (modern-request-meta (or (era-version conn) modern-protocol-version)))

(def modern-error-codes
  "JSON-RPC codes the 2026-07-28 revision defines for era negotiation:
   -32020 header mismatch, -32021 missing client capability, -32022
   unsupported protocol version."
  #{-32020 -32021 -32022})

(defn modern-error?
  "True when EX-DATA carries an era-negotiation code."
  [data]
  (contains? modern-error-codes (:code data)))

(defn negotiate-version
  "The era and revision to use with a server advertising the revisions in
   SUPPORTED: the newest modern revision in common, else the newest legacy
   revision, else nil (callers decide whether an overlap of only unknown
   revisions is an error or a legacy fallback). The server's ordering is
   ignored."
  [supported]
  (let [supported (set supported)]
    (or (when-let [v (some #(when (supported %) %) modern-supported-versions)]
          {:era :modern :version v})
        (when-let [v (some #(when (supported %) %) supported-protocol-versions)]
          {:era :legacy :version v}))))

(defn result-type
  "The 2026-07-28 resultType of RESULT: an absent value is the default
   \"complete\"."
  [result]
  (or (:resultType result) "complete"))

(def ^:private progress-counter (atom 0))

(defn progress-token
  "A correlation id for the _meta.progressToken of one request.
   Progress is opt-in per request — a server only emits
   notifications/progress for requests carrying the token, so every call
   the client wants streamed must send one (spec, progress). Monotonic:
   the id only has to be unique within the session."
  []
  (str "kmet-" (swap! progress-counter inc)))

(defn mcp-error
  "ex-info with the §7.7 message patterns (extensions match on :mcp-error,
   the data map carries :status/:code/:timeout-ms where applicable)."
  ([message] (ex-info message {:type :mcp-error}))
  ([message data] (ex-info message (assoc data :type :mcp-error))))

;; ─── Result formatting (§7.6) ─────────────────────────────────────────────

(defn- base64-byte-size
  "Decoded byte length of a padded base64 string, or nil when S is not a
   well-padded base64 string (a malformed payload is summarized by its
   character count instead)."
  [s]
  (when (string? s)
    (let [n (count s)
          pad (cond (str/ends-with? s "==") 2
                    (str/ends-with? s "=") 1
                    :else 0)]
      (when (and (pos? n) (zero? (mod n 4)))
        (- (/ (* 3 n) 4) pad)))))

(defn- byte-label
  "The '<n> bytes' label of a base64 payload — the decoded size when the
   payload is well-formed, else its character count."
  [data]
  (str (or (base64-byte-size data) (count (str data))) " bytes"))

(defn- resource-block
  "The text of an embedded resource content block: the resource's text,
   else a summary of its blob; nil when the block carries neither."
  [b]
  (let [r (:resource b)
        uri (or (:uri r) "?")]
    (cond
      (string? (:text r)) (:text r)
      (some? (:blob r)) (str "[resource " uri ": " (or (:mimeType r) "?") ", "
                             (byte-label (:blob r)) " — not rendered]")
      :else nil)))

(defn format-result
  "Flatten a tools/call result into text (§7.6): text/error content blocks
   joined with newlines; image/audio → '[<kind>: <mimeType>, <n> bytes —
   not rendered]' (a base64 payload reports its decoded size); an embedded
   resource → its text, else a blob summary; a resource link → its name or
   uri; empty content with structuredContent → pretty JSON; fallback
   '(no text content)'. Returns {:text str :is-error bool}."
  [result]
  (let [blocks (or (:content result) [])
        texts (keep (fn [b]
                      (case (:type b)
                        ("text" "error") (:text b)
                        "image" (str "[image: " (or (:mimeType b) "?") ", "
                                     (byte-label (:data b)) " — not rendered]")
                        "audio" (str "[audio: " (or (:mimeType b) "?") ", "
                                     (byte-label (:data b)) " — not rendered]")
                        "resource" (resource-block b)
                        "resource_link" (str "[resource link: "
                                             (or (:name b) (:uri b) "?") "]")
                        nil))
                    blocks)
        text (cond
               (seq texts) (str/join "\n" texts)
               (seq (:structuredContent result))
               (json/generate-string (:structuredContent result) {:pretty true})
               :else "(no text content)")]
    {:text text :is-error (true? (:isError result))}))

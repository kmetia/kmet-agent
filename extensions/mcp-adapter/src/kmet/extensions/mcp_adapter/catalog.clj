(ns kmet.extensions.mcp-adapter.catalog
  "Shared read view over the adapter's state for the proxy layer: server
   definitions and settings, the failure-backoff window, the cached catalog,
   assigned display names and tool lookup, plus the small result/truncation
   helpers the search, status and tool-proxy namespaces share. core.clj owns
   the state atom and the connection lifecycle; nothing here connects."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.names :as names]))

(def ^:private failure-backoff-ms 60000)

;; ─── Tool/state helpers ────────────────────────────────────────────────

(defn server-definition
  "The effective definition for a server (per-server merge over settings)."
  [state name]
  (get-in state [:config :mcp-servers name]))

(defn settings
  "The adapter settings map (global :tool-prefix, :direct-tools, ...)."
  [state]
  (:settings (:config state)))

(defn server-state
  "The runtime state map for one server (:conn, :error, :failed-at, ...)."
  [state name]
  (get-in state [:servers name]))

(defn failure-age-seconds
  "Seconds since the server's last recorded connect failure, or nil
   outside the 60s backoff window (pi getFailureAgeSeconds — a failed
   lazy use does not retry inside the window)."
  [state name]
  (when-let [failed-at @(get-in state [:servers name :failed-at])]
    (let [age (- (concurrent/monotonic-ms) failed-at)]
      (when (< age failure-backoff-ms)
        (quot age 1000)))))

(defn disabled?
  "True when the server is disabled in config."
  [state name]
  (true? (:disabled (server-definition state name))))

(defn misconfigured?
  "A server with neither :command nor :url (§6.2)."
  [definition]
  (and (not (:command definition)) (not (:url definition))))

(defn truncate-at-word
  "Truncate S to LENGTH chars at a word boundary (pi truncateAtWord)."
  [s length]
  (let [s (or s "")]
    (if (<= (count s) length)
      s
      (let [cut (subs s 0 length)
            space (str/last-index-of cut " ")]
        (str (if space (subs cut 0 space) cut) "…")))))

(defn cached-tools
  "The cached tools for a server (fresh + fingerprint-valid), or nil."
  [state name]
  (when-let [entry (metadata/server-entry (:cache state)
                                          name
                                          (server-definition state name)
                                          (settings state))]
    (:tools entry)))

(defn- tool-prefix-mode
  "The effective prefix mode for a server (per-server over settings)."
  [state server-name]
  (names/effective-mode (server-definition state server-name) (settings state)))

(defn display-name
  "The assigned name for a tool/resource (§10.5): the deterministic
   assignment from the last catalog sync, else the composed name before an
   assignment exists (startup) or for an entry outside the registered
   catalog."
  [state server-name kind id raw-name]
  (or (get (:tool-names state) [server-name kind id])
      (names/prefixed-name server-name raw-name
                           (tool-prefix-mode state server-name))))

(defn return-error
  "A kmet tool result carrying MESSAGE as an error."
  [message]
  {:content message :is-error true})

(defn find-tool
  "Find a tool by (prefixed) name across servers. Exact (raw or assigned)
   matches win; otherwise every addressable spelling is accepted
   (tool-name-candidates — legacy dash→underscore and the other prefix
   modes). Returns {:server :tool} or :ambiguous when the name matches
   multiple enabled servers."
  [state tool-name]
  (let [matches (fn [match?]
                  (for [[name _] (:mcp-servers (:config state))
                        :when (not (disabled? state name))
                        tool (or (cached-tools state name) [])
                        :when (match? name tool)]
                    {:server name :tool tool}))
        exact (seq (matches (fn [name tool]
                              (or (= tool-name (:name tool))
                                  (= tool-name (display-name state name :tool (:name tool)
                                                             (:name tool)))))))
        all (or exact
                (seq (matches (fn [name tool]
                                (contains? (names/tool-name-candidates name (:name tool))
                                           tool-name)))))]
    (cond
      (> (count all) 1) :ambiguous
      (seq all) (first all)
      :else nil)))

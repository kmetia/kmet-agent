(ns kmet.extensions.mcp-adapter.eval-source
  "The adapter's eval source: an `ext.mcp` gateway plus one verb per
   direct tool. Only direct tools appear in `(eval/sources)`; the rest of
   the catalog stays reachable through `search`/`describe` and `call`.
   Every call carries the eval call's cancel signal."
  (:require [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.extensions.mcp-adapter.tool-surface :as surface]))

(def ^:private gateway-names
  "Names reserved by the gateway; a direct tool with one of these names is
   not exposed as a verb (call still reaches it)."
  #{"call" "call!" "search" "describe" "status" "connect" "disconnect"})

(def ^:private source-description
  (str "MCP tools: (ext.mcp/search \"query\") ranks tools and prints a call "
       "example per hit; (ext.mcp/describe \"tool\") shows parameters; "
       "(ext.mcp/call \"tool\" args) calls one. Direct tools are also "
       "ext.mcp/<name> verbs."))

(defn- verb-symbol
  "NAME as a readable Clojure symbol, or nil when it is not one (a bare
   numeric-leading tool name under :tool-prefix :none)."
  [name]
  (when (re-matches #"[A-Za-z_][A-Za-z0-9_]*" (str name))
    (symbol name)))

(defn- text
  "The :content of a result, throwing on :is-error."
  [result]
  (if (:is-error result)
    (throw (ex-info (str (:content result))
                    {:type :mcp/call-failed :result result}))
    (:content result)))

(defn- gateway-result
  "Run a gateway mode through the proxy and return its text."
  ([state params] (gateway-result state params nil))
  ([state params opts] (text (proxy/execute state params nil opts))))

(defn- call
  [state signal tool args opts]
  (proxy/execute state
                 (cond-> {:tool tool :args args}
                   (:server opts) (assoc :server (:server opts)))
                 nil
                 {:signal signal :surface :eval}))

(defn- search
  [state query opts]
  (gateway-result state
                  (cond-> {:search query}
                    (:server opts) (assoc :server (:server opts))
                    (some? (:regex opts)) (assoc :regex (:regex opts))
                    (some? (:include-schemas opts)) (assoc :includeSchemas
                                                           (:include-schemas opts))
                    (some? (:limit opts)) (assoc :limit (:limit opts))
                    (some? (:offset opts)) (assoc :offset (:offset opts)))
                  {:surface :eval}))

(defn- call-spec
  "Normalize the flexible call forms to [tool args opts]. A map as the
   first argument is the unambiguous {:tool :args :server} form."
  [tool args opts]
  (if (map? tool)
    [(:tool tool) (:args tool) (select-keys tool [:server])]
    [tool args opts]))

(defn- call-verb
  "The exposed call / call! fn; CALL! selects content-or-throw."
  [state signal call!]
  (let [finish (if call! text identity)]
    (fn
      ([tool] (let [[tool args opts] (call-spec tool nil nil)]
                (finish (call state signal tool args opts))))
      ([tool args] (finish (call state signal tool args nil)))
      ([tool args opts] (finish (call state signal tool args opts))))))

(defn- direct-verb
  [state signal spec]
  (with-meta
    (fn
      ([] (surface/execute-spec state spec nil {:signal signal}))
      ([args] (surface/execute-spec state spec args {:signal signal})))
    {:doc (or (:description spec) (str "Call MCP tool " (:original spec) "."))
     :arglists '([] [args])}))

(defn source
  "The eval source value for STATE and the eval call's SIGNAL: a small
   `ext.mcp` gateway plus one verb per direct tool. Non-direct tools stay
   reachable through `search`/`describe` and `call`, not through the help
   listing."
  [state signal]
  (let [direct (into {}
                     (keep (fn [spec]
                             (let [name (:prefixed spec)
                                   sym (verb-symbol name)]
                               (when (and sym (not (contains? gateway-names name)))
                                 [sym (direct-verb state signal spec)]))))
                     (surface/direct-tools-specs state))]
    {:doc source-description
     :ns {'ext.mcp
          (into
           {'call (with-meta
                    (call-verb state signal false)
                    {:doc "Call an MCP tool by name (raw or prefixed), or pass one map {:tool :args :server}. OPTS: {:server \"name\"} to disambiguate or lazy-connect an uncached tool. Returns the kmet result map."
                     :arglists '([tool] [tool args] [tool args opts])})
            'call! (with-meta
                     (call-verb state signal true)
                     {:doc "Like call, but returns the content string and throws when the call fails."
                      :arglists '([tool] [tool args] [tool args opts])})
            'search (with-meta
                      (fn
                        ([query] (search state query nil))
                        ([query opts] (search state query opts)))
                      {:doc "Ranked MCP tool search; each hit prints its ext.mcp/call example. OPTS: :server :regex :include-schemas :limit :offset. Returns text."
                       :arglists '([query] [query opts])})
            'describe (with-meta
                        (fn [tool]
                          (gateway-result state {:describe tool} {:surface :eval}))
                        {:doc "Show one MCP tool's parameters and call example by name."
                         :arglists '([tool])})
            'status (with-meta
                      (fn [] (gateway-result state {} {:surface :eval}))
                      {:doc "List configured MCP servers and their connection state."
                       :arglists '([])})
            'connect (with-meta
                       (fn [server]
                         (gateway-result state {:connect server}
                                         {:signal signal :surface :eval}))
                       {:doc "Connect a server now and refresh its catalog."
                        :arglists '([server])})
            'disconnect (with-meta
                          (fn [server] (gateway-result state {:disconnect server}))
                          {:doc "Disconnect a server."
                           :arglists '([server])})}
           direct)}}))

(defn sync!
  "Register or drop the eval source from the adapter STATE: present unless
   settings :eval-source is false. Re-registering replaces the source; the
   source reads the catalog per eval call, so no catalog sync is needed."
  [api state]
  (if (not= false (:eval-source (:settings (:config @state))))
    (ext/register-eval-source! api :mcp
                               (fn [ctx]
                                 (source state (:signal ctx)))
                               {:description source-description})
    (ext/unregister-eval-source! api :mcp)))

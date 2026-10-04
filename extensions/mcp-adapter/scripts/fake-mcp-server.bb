#!/usr/bin/env bb
;; Fake stdio MCP server for validating the mcp-adapter client
;; (extensions/mcp-adapter/scripts/fake-mcp-server.bb — §12.1).
;;
;; Implements: initialize (negotiation), notifications/initialized echo,
;; tools/list (with a cursor page), tools/call (echo + error path), a
;; notification mid-request, server->client requests (ping / an
;; unimplemented method) answered mid-call, a list_changed notification
;; that adds a tool, notifications/cancelled logging, and clean exit on
;; SIGTERM/EOF.
;;
;; Additive modern mode (`--era modern` / `--era=modern` /
;; KMET_FAKE_ERA=modern, revision 2026-07-28): server/discover, per-request
;; _meta enforcement, resultType "complete" on every result,
;; subscriptions/listen (ack-first, subscriptionId-stamped notifications),
;; and list_changed delivered to subscriptions from the add-tool call.
;; Legacy behavior is unchanged.
;;
;; Usage: bb fake-mcp-server.bb [--era modern]  (speaks JSON-RPC over
;; stdin/stdout)
;; Locate the kmet source tree (the fakes run as bare bb children with no
;; -cp) so JSON can go through kmet.libs.json like everywhere else.
(require '[babashka.fs :as fs]
         '[babashka.classpath :as bcp])
(let [src (str (fs/normalize (fs/path (fs/parent *file*) ".." ".." ".." "src")))]
  (when (fs/directory? src)
    (bcp/add-classpath src)))
(require '[kmet.libs.json :as json]
         '[clojure.string :as str])

;; ─── era ─────────────────────────────────────────────────────────────────
;; legacy (default) — the initialize handshake, unchanged; modern
;; (`--era modern` / `--era=modern` / KMET_FAKE_ERA=modern) — revision
;; 2026-07-28: no handshake, per-request _meta, resultType on every result,
;; subscriptions/listen with an ack-first stream.

(def modern-version "2026-07-28")
(def modern-supported-versions [modern-version])

(def era
  (let [args (vec (or *command-line-args* []))
        i (first (keep-indexed (fn [i a] (when (= a "--era") (inc i))) args))
        inline (some #(when (str/starts-with? % "--era=") (subs % 6)) args)]
    (or inline
        (when (and i (< i (count args))) (nth args i))
        (System/getenv "KMET_FAKE_ERA")
        "legacy")))

(def modern? (= era "modern"))

(def tools
  [{:name "echo" :description "Echo the message back"
    :inputSchema {:type "object"
                  :properties {"message" {:type "string" :description "Text to echo"}}
                  :required ["message"]}}
   {:name "add" :description "Add two numbers"
    :inputSchema {:type "object"
                  :properties {"a" {:type "number"} "b" {:type "number"}}
                  :required ["a" "b"]}}
   {:name "slow" :description "Sleeps then returns"
    :inputSchema {:type "object"
                  :properties {"ms" {:type "number" :description "Sleep ms"}}
                  :required []}}
   {:name "boom" :description "Always fails with an error result"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "ping-mid" :description "Sends a notification mid-request"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "who-asks" :description "Asks the client ping + roots/list mid-call"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "add-tool" :description "Adds a tool and sends tools/list_changed"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "storm" :description "list_changed storm: every tools/list re-notifies"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "list-count" :description "How many tools/list calls this process served"
    :inputSchema {:type "object" :properties {} :required []}}])

;; set by the add-tool call: tools/list then carries one more tool
(def extra-tool? (atom false))

;; answers to the server->client requests the who-asks tool sends
(def answers (atom {}))

;; storm mode: every tools/list answers with another list_changed, so a
;; client that resyncs per notification never stops
(def storm? (atom false))
(def list-calls (atom 0))

;; cancellation log (env FAKE_LOG) so a test can see the client's
;; notifications/cancelled for an abandoned request
(def log-file (System/getenv "FAKE_LOG"))

(defn- log! [line]
  (when log-file
    (spit log-file (str line "\n") :append true)))

(def prompts
  [{:name "brief" :description "Summarize a topic briefly"
    :arguments [{:name "topic" :description "The topic" :required true}]}
   {:name "review" :description "Review code"
    :arguments [{:name "path" :description "File path" :required true}
                {:name "focus" :description "Focus area" :required false}]}])

(def resources
  [{:name "README" :uri "file:///README.md" :description "The project readme"}
   {:name "schema" :uri "file:///schema.json" :description "JSON schema"}])

(def resource-templates
  [{:name "project files" :uriTemplate "file:///{path}"
    :description "Read a file in the project" :mimeType "text/plain"}
   ;; a display name whose resource-tool-name normalization differs from a
   ;; raw sanitize (spaces + parens) — /mcp list must show the registered
   ;; tool name
   {:name "issues (all)" :uriTemplate "file:///issues/{state}"
    :description "All tracked issues"}
   {:name "issues" :uriTemplate "github://repo/{owner}/{repo}/issues/{number}"
    :description "A tracked issue"}])

(defn- send! [msg]
  (println (json/generate-string msg))
  (flush))

(defn- send-result! [id result]
  (send! {:jsonrpc "2.0" :id id
          :result (cond-> result modern? (assoc :resultType "complete"))}))

(defn- send-error!
  ([id code message] (send-error! id code message nil))
  ([id code message data]
   (send! {:jsonrpc "2.0" :id id
           :error (cond-> {:code code :message message} data (assoc :data data))})))

(defn- modern-meta-error
  "Validation error for a modern (2026-07-28) request, or nil: _meta with
   the version, clientInfo and clientCapabilities is mandatory; a version
   outside the supported list is -32022 with the spec's data shape."
  [msg]
  (let [meta (get-in msg [:params :_meta])
        version (get meta :io.modelcontextprotocol/protocolVersion)]
    (cond
      (nil? meta) {:code -32602 :message "Missing required _meta"}
      (nil? version)
      {:code -32602
       :message "Missing io.modelcontextprotocol/protocolVersion in _meta"}
      (not (some #{version} modern-supported-versions))
      {:code -32022 :message "Unsupported protocol version"
       :data {:supported modern-supported-versions :requested version}}
      (not (contains? meta :io.modelcontextprotocol/clientInfo))
      {:code -32602 :message "Missing io.modelcontextprotocol/clientInfo in _meta"}
      (not (contains? meta :io.modelcontextprotocol/clientCapabilities))
      {:code -32602
       :message "Missing io.modelcontextprotocol/clientCapabilities in _meta"})))

;; active subscriptions: listen request id → agreed filter
(def subscriptions (atom {}))

(defn- notify-subscriptions!
  "Send METHOD to every subscription that agreed to toolsListChanged,
   stamped with the subscription id (a modern server never broadcasts a
   list_changed notification)."
  [method]
  (doseq [[sid filter] @subscriptions
          :when (:toolsListChanged filter)]
    (send! {:jsonrpc "2.0" :method method
            :params {:_meta {:io.modelcontextprotocol/subscriptionId sid}}})))

(defn- with-cache-fields
  "ttlMs/cacheScope on modern list results (spec, caching)."
  [result]
  (cond-> result modern? (assoc :ttlMs 60000 :cacheScope "private")))

(defn- handle-call [id params]
  (let [name (:name params)
        args (:arguments params)
        ;; like a real SDK server: progress is emitted ONLY for requests
        ;; carrying _meta.progressToken
        token (get-in params [:_meta :progressToken])]
    (case name
      "echo" (send-result! id {:content [{:type "text" :text (str "echo: " (:message args))}]})
      "add" (send-result! id {:content [{:type "text" :text (str (+ (:a args) (:b args)))}]})
      "slow" (do (doseq [i [25 50 75]]
                   (when token
                     (send! {:jsonrpc "2.0" :method "notifications/progress"
                             :params {:progress i :total 100
                                      :progressToken token
                                      :message (str "working " i "%")}}))
                   (Thread/sleep (long (/ (or (:ms args) 100) 4))))
                 (send-result! id {:content [{:type "text" :text "slept"}]}))
      "boom" (send-result! id {:content [{:type "text" :text "kaboom"}]
                               :isError true})
      "ping-mid"
      (do (when token
            (send! {:jsonrpc "2.0" :method "notifications/progress"
                    :params {:progress 0.5 :progressToken token}}))
          (Thread/sleep 50)
          (send-result! id {:content [{:type "text" :text "pong"}]}))
      ;; server->client requests mid-call: ping MUST be answered, the
      ;; optional client features (roots) must be refused, not dropped
      "who-asks"
      (do (send! {:jsonrpc "2.0" :id 9001 :method "ping" :params {}})
          (send! {:jsonrpc "2.0" :id 9002 :method "roots/list" :params {}})
          (Thread/sleep 400)
          (send-result! id {:content [{:type "text"
                                       :text (json/generate-string @answers)}]}))
      ;; add a tool and tell the client its catalog changed
      "add-tool"
      (do (reset! extra-tool? true)
          (if modern?
            (notify-subscriptions! "notifications/tools/list_changed")
            (send! {:jsonrpc "2.0" :method "notifications/tools/list_changed"}))
          (send-result! id {:content [{:type "text" :text "added echo2"}]}))
      ;; three notifications in a row, then every tools/list re-notifies
      "storm"
      (do (reset! storm? true)
          (dotimes [_ 3]
            (if modern?
              (notify-subscriptions! "notifications/tools/list_changed")
              (send! {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))
          (send-result! id {:content [{:type "text" :text "storming"}]}))
      "list-count"
      (send-result! id {:content [{:type "text" :text (str @list-calls)}]})
      (send-error! id -32602 (str "Unknown tool: " name)))))

(defn- handle-line [line]
  (when (seq (str/trim line))
    (let [msg (json/parse-string line true)
          id (:id msg)
          method (:method msg)]
      (cond
        ;; a response to one of our own requests (the client's answer to
        ;; ping / roots/list) — record it, never answer it
        (and id (nil? method))
        (swap! answers assoc id (if (:error msg)
                                  {:error (:error msg)}
                                  {:result (:result msg)}))

        (= method "notifications/cancelled")
        (do (log! (json/generate-string (select-keys msg [:method :params])))
            (swap! subscriptions dissoc (get-in msg [:params :requestId])))

        :else
        (let [meta-error (when (and modern? id) (modern-meta-error msg))]
          (cond
            ;; a modern-only server rejects the legacy handshake, naming the
            ;; versions it speaks (spec, Versioning: Backward Compatibility)
            (and modern? (= method "initialize"))
            (send-error! id -32601
                         (str "Method not found: initialize (this server speaks only "
                              (str/join ", " modern-supported-versions) ")")
                         {:supported modern-supported-versions})

            meta-error
            (let [{:keys [code message data]} meta-error]
              (send-error! id code message data))

            :else
            (case method
              "initialize"
              (do (send-result! id {:protocolVersion (:protocolVersion (:params msg))
                                    :capabilities {:tools {:listChanged true}
                                                   :prompts {:listChanged false}
                                                   :resources {:listChanged false}}
                                    :serverInfo {:name "fake-mcp-server" :version "1.0.0"}})
                  (send! {:jsonrpc "2.0" :method "notifications/initialized" :params {}}))
          ;; modern discovery (spec, server/discover)
              "server/discover"
              (if modern?
                (send-result! id
                              {:supportedVersions modern-supported-versions
                               :capabilities {:tools {:listChanged true}
                                              :prompts {:listChanged true}
                                              :resources {}}
                               :instructions "Fake modern MCP server (kmet validation)"
                               :ttlMs 3600000
                               :cacheScope "public"
                               :_meta {:io.modelcontextprotocol/serverInfo
                                       {:name "fake-mcp-server" :version "1.0.0"}}})
                (send-error! id -32601 (str "Method not found: " method)))
          ;; one long-lived subscription per request id; the ack is the first
          ;; frame and reflects the subset this server honors
              "subscriptions/listen"
              (if modern?
                (let [filter (or (:notifications (:params msg)) {})
                      agreed (select-keys filter [:toolsListChanged])]
                  (swap! subscriptions assoc id agreed)
                  (send! {:jsonrpc "2.0"
                          :method "notifications/subscriptions/acknowledged"
                          :params {:_meta {:io.modelcontextprotocol/subscriptionId id}
                                   :notifications agreed}}))
                (send-error! id -32601 (str "Method not found: " method)))
              "notifications/initialized" nil
              "tools/list"
              (let [_ (swap! list-calls inc)
                    _ (when @storm?
                        (if modern?
                          (notify-subscriptions! "notifications/tools/list_changed")
                          (send! {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))
                    cursor (:cursor (:params msg))
                    all (cond-> tools @extra-tool? (conj {:name "echo2"
                                                          :description "Second echo"
                                                          :inputSchema {:type "object"
                                                                        :properties {}
                                                                        :required []}}))
                    page1 (subvec (vec all) 0 2)
                    page2 (subvec (vec all) 2)]
                (if (nil? cursor)
                  (send-result! id (with-cache-fields {:tools page1 :nextCursor "p2"}))
                  (send-result! id (with-cache-fields {:tools page2}))))
        ;; who-asks blocks while it waits for the client's answers, so it
        ;; runs off the read loop (the loop keeps reading responses); the
        ;; thread is returned so the loop can join it before exiting
              "tools/call"
              (if (= "who-asks" (get-in msg [:params :name]))
          ;; the answer map is cleared here, before the thread sends its
          ;; requests — clearing it inside the thread would race the read
          ;; loop, which may already have recorded the answers
                (do (reset! answers {})
                    (doto (Thread. (fn []
                                     (try (handle-call id (:params msg))
                                          (catch Exception e
                                            (send-error! id -32603 (ex-message e))))))
                      (.start)))
                (handle-call id (:params msg)))
              "prompts/list" (send-result! id (with-cache-fields {:prompts prompts}))
              "prompts/get"
              (let [name (:name (:params msg))
                    args (or (:arguments (:params msg)) {})]
                (case name
                  "brief" (send-result! id {:description "Summarize a topic briefly"
                                            :messages [{:role "user"
                                                        :content {:type "text"
                                                                  :text (str "Briefly summarize: " (:topic args))}}]})
                  "review" (send-result! id {:description "Review code"
                                             :messages [{:role "user"
                                                         :content {:type "text"
                                                                   :text (str "Review " (:path args))}}
                                                        {:role "assistant"
                                                         :content {:type "text"
                                                                   :text (str "Focus: " (or (:focus args) "overall"))}}]})
                  (send-error! id -32602 (str "Unknown prompt: " name))))
              "resources/list" (send-result! id (with-cache-fields {:resources resources}))
              "resources/templates/list"
              (send-result! id (with-cache-fields {:resourceTemplates resource-templates}))
              "resources/read"
              (let [uri (:uri (:params msg))]
                (case uri
                  "file:///README.md" (send-result! id {:contents [{:type "text"
                                                                    :uri uri
                                                                    :text "# Fake README\ncontent"}]})
                  "file:///schema.json" (send-result! id {:contents [{:type "text"
                                                                      :uri uri
                                                                      :text "{\"type\": \"object\"}"}]})
            ;; a template read arrives already expanded by the client
                  "file:///src/main.clj" (send-result! id {:contents [{:type "text"
                                                                       :uri uri
                                                                       :text "(ns main)"}]})
                  "file:///a b.txt" (send-result! id {:contents [{:type "text"
                                                                  :uri uri
                                                                  :text "spaced path"}]})
                  (send-error! id -32602 (str "Unknown resource: " uri))))
              (send-error! id -32601 (str "Method not found: " method)))))))))

;; clean exit on EOF (client killed the pipe) — call threads are joined
;; first so a handler still waiting on the client's answers gets its
;; result out before the process exits
(let [pending (atom [])]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try
      ;; handle-line yields a thread only for the off-loop call handler
      (let [t (handle-line line)]
        (when (instance? java.lang.Thread t) (swap! pending conj t)))
      (catch Exception e (println "ERR" (ex-message e)))))
  (doseq [t @pending] (.join t 10000)))

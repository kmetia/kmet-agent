#!/usr/bin/env bb
;; Minimal content-length-framed LSP server for lsp-adapter validation.
;; Self-contained except for kmet.libs.json, whose source roots it adds from
;; the checkout it lives in (the client runs it with its cwd set to the
;; workspace root, where repo-relative classpaths do not resolve — the script
;; path is absolute, and the app's sources live under modules/<module>/src).
;;
;; Exercises the client: initialize handshake, a server->client
;; workspace/configuration probe whose reply writes the marker file passed
;; as argv[1], canned results for definition/hover/documentSymbol/
;; workspaceSymbol/references/call hierarchy, one publishDiagnostics push
;; per didOpen, and the shutdown->exit dance.
;;
;; Run: bb scripts/fake-lsp-server.bb <marker-path>
(require '[babashka.fs :as fs]
         '[babashka.classpath :as bcp])
(let [root (fs/path (fs/parent *file*) ".." ".." "..")]
  (doseq [dir (fs/glob root "modules/*/src")]
    (when (fs/directory? dir)
      (bcp/add-classpath (str dir)))))
(require '[kmet.libs.json :as json]
         '[clojure.string :as str])

(def marker (first *command-line-args*))
(def in System/in)
(def out System/out)

(defn send! [msg]
  (let [payload (json/generate-string msg)
        body (.getBytes payload "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                          "UTF-8")]
    (.write out header 0 (alength header))
    (.write out body 0 (alength body))
    (.flush out)))

(defn read-line-raw []
  (let [buf (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) nil
          (= b 10) (.toString buf "UTF-8")
          :else (do (.write buf b) (recur)))))))

(defn read-frame []
  (let [headers (loop [acc []]
                  (let [line (str/trim (read-line-raw))]
                    (if (or (nil? line) (str/blank? line))
                      acc
                      (recur (conj acc line)))))
        len (some (fn [h]
                    (when-let [[_ v] (re-find #"(?i)^content-length:\s*(\d+)" h)]
                      (parse-long v)))
                  headers)]
    (when len
      (let [buf (byte-array len)]
        (loop [off 0]
          (when (< off len)
            (let [n (.read in buf off (- len off))]
              (when (neg? n) (throw (java.io.EOFException.)))
              (recur (+ off n)))))
        (.toString (doto (java.io.ByteArrayOutputStream.)
                     (.write buf 0 len))
                   "UTF-8")))))

(def opened (atom #{}))

(loop []
  (let [payload (try (read-frame) (catch Exception _ ::eof))]
    (when-not (= payload ::eof)
      (let [msg (json/parse-string payload true)
            {:keys [id method params]} msg]
        (cond
          ;; reply from the client (no :method) - our configuration probe
          (and id (not method))
          (when (and marker (= 900 id)) (spit marker "replied"))

          method
          (case method
            "initialize"
            (do (send! {:jsonrpc "2.0" :id id
                        :result
                        {:capabilities
                         {:textDocumentSync 1
                          :definitionProvider true
                          :hoverProvider true
                          :documentSymbolProvider true
                          :workspaceSymbolProvider true
                          :referencesProvider true
                          :implementationProvider true
                          :callHierarchyProvider true}
                         :serverInfo {:name "fake-lsp" :version "0.1"}}})
                ;; exercise the client's server-request policy right away:
                ;; it must answer; we signal via the marker file
                (send! {:jsonrpc "2.0" :id 900
                        :method "workspace/configuration"
                        :params {:items [{:section "lsp"}]}}))

            "initialized" nil

            "shutdown" (send! {:jsonrpc "2.0" :id id :result nil})

            "exit" (System/exit 0)

            "textDocument/didOpen"
            (let [uri (get-in params [:textDocument :uri])]
              (when-not (contains? @opened uri)
                (swap! opened conj uri)
                (send! {:jsonrpc "2.0"
                        :method "textDocument/publishDiagnostics"
                        :params {:uri uri :version 1
                                 :diagnostics
                                 [{:range {:start {:line 0 :character 0}
                                           :end {:line 0 :character 5}}
                                   :severity 1
                                   :message "fake diagnostic"
                                   :source "fake-lsp"}]}})))

            ("textDocument/didChange" "textDocument/didSave"
                                      "textDocument/didClose") nil

            "textDocument/definition"
            ;; a definition request without a position is the argv-style
            ;; silent-shape bug class: fail loudly instead of echoing
            (if (:position params)
              (send! {:jsonrpc "2.0" :id id
                      :result {:uri (get-in params [:textDocument :uri])
                               :range {:start {:line 4 :character 2}
                                       :end {:line 4 :character 8}}}})
              (send! {:jsonrpc "2.0" :id id
                      :error {:code -32603
                              :message "fake-lsp: definition without :position"}}))

            "textDocument/references"
            (let [u (get-in params [:textDocument :uri])]
              (send! {:jsonrpc "2.0" :id id
                      :result [{:uri u
                                :range {:start {:line 9 :character 0}
                                        :end {:line 9 :character 3}}}
                               {:uri u
                                :range {:start {:line 11 :character 1}
                                        :end {:line 11 :character 4}}}]}))

            "textDocument/hover"
            (send! {:jsonrpc "2.0" :id id
                    :result {:contents {:value "hover docs for fake"}}})

            "textDocument/documentSymbol"
            (send! {:jsonrpc "2.0" :id id
                    :result [{:name "alpha" :kind 12
                              :range {:start {:line 1 :character 0}
                                      :end {:line 1 :character 10}}}
                             {:name "beta" :kind 13
                              :range {:start {:line 2 :character 0}
                                      :end {:line 2 :character 6}}}]})

            "workspace/symbol"
            (send! {:jsonrpc "2.0" :id id
                    :result [{:name "sym" :kind 12
                              :location {:uri "file:///x/y.txt"
                                         :range {:start {:line 3 :character 0}
                                                 :end {:line 3 :character 3}}}}]})

            "textDocument/prepareCallHierarchy"
            (let [u (get-in params [:textDocument :uri])]
              (send! {:jsonrpc "2.0" :id id
                      :result [{:name "callee" :kind 12 :uri u
                                :range {:start {:line 7 :character 0}
                                        :end {:line 7 :character 9}}}]}))

            ("callHierarchy/incomingCalls" "callHierarchy/outgoingCalls")
            (send! {:jsonrpc "2.0" :id id
                    :result [{:from {:name "caller" :kind 12
                                     :uri "file:///x/y.txt"
                                     :range {:start {:line 15 :character 0}
                                             :end {:line 15 :character 9}}}
                              :fromRanges [{:start {:line 20 :character 2}
                                            :end {:line 20 :character 9}}]}]})

            ;; default clause: unknown notifications/methods are no-ops
            ;; (never kill the server mid-session)
            (when id
              (send! {:jsonrpc "2.0" :id id :result {}}))))
        (recur)))))

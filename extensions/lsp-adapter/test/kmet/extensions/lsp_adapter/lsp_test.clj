(ns kmet.extensions.lsp-adapter.lsp-test
  "The LSP wire helpers: path ↔ URI round-trips (including the extension's
   sci sandbox, whose class registry lacks the JDK Path classes), the
   initialize payload, the full-text sync builders, the 1-based coordinate
   conversion, the server→client policy and the graceful close sequence."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.host :as host]
            [kmet.libs.jsonrpc :as jrpc]
            [kmet.extensions.lsp-adapter.lsp :as lsp]
            [kmet.extensions.lsp-adapter.test-util :as tu]))

;; ─── path ↔ uri ───────────────────────────────────────────────────────────

(def ^:private hostile-samples
  ["plain.clj" "a b.clj" "hash#tag.clj" "quest?.clj" "100%.clj"
   "ünï cödé.clj" "nested/dir/file.bb"])

(deftest path-uri-round-trip
  (let [paths (map #(str (fs/absolutize %)) hostile-samples)]
    (testing "path->uri matches java.nio's Path#toUri on every sample"
      (doseq [p paths]
        (if (host/jolt?)
          ;; Jolt's Path#toUri renders `file:/x` and leaves non-ASCII
          ;; literal; path->uri answers the JDK/RFC 8089 form (`file:///x`,
          ;; percent-encoded) the round-trip and encoding cases below pin, so
          ;; the host renderer is no oracle here
          (is (str/starts-with? (lsp/path->uri p) "file:///") p)
          (is (= (str (.toUri (fs/path p))) (lsp/path->uri p)) p))))
    (testing "uri->path inverts path->uri"
      (doseq [p paths]
        (is (= p (lsp/uri->path (lsp/path->uri p))) p)))
    (testing "spaces and non-ASCII characters are percent-encoded"
      (is (str/ends-with? (lsp/path->uri (str (fs/absolutize "a b.clj")))
                          "/a%20b.clj"))
      (is (str/ends-with? (lsp/path->uri (str (fs/absolutize "ünï cödé.clj")))
                          "/%C3%BCn%C3%AF%20c%C3%B6d%C3%A9.clj")))))

(deftest ^:bb-only path-uri-survives-the-sci-sandbox
  ;; kmet evaluates extension source inside an isolated sci context whose
  ;; class registry lacks sun.nio.fs.UnixPath, and bb's native image does
  ;; not reflect Path methods either — this is the regression guard.
  ;; babashka.classes and sci.core are bb-bundled: resolved lazily so the
  ;; namespace still loads on the other hosts (where this test is skipped)
  (let [all-classes (requiring-resolve 'babashka.classes/all-classes)
        sci-init (requiring-resolve 'sci.core/init)
        sci-eval (requiring-resolve 'sci.core/eval-form)
        src (slurp (str (fs/path tu/extension-dir "src/kmet/extensions/lsp_adapter/lsp.clj")))
        forms (remove #(and (seq? %) (= 'ns (first %)))
                      (read-string (str "[\n" src "\n]")))
        ctx (sci-init
             {:classes (into {}
                             (map (fn [^Class c] [(symbol (.getName c)) {:class c}]))
                             (remove #(str/starts-with? (.getName ^Class %) "[")
                                     (all-classes)))
              :imports '{StringBuilder java.lang.StringBuilder
                         Thread java.lang.Thread}
              :namespaces {'babashka.fs (ns-publics 'babashka.fs)
                           'kmet.libs.jsonrpc (ns-publics 'kmet.libs.jsonrpc)}})]
    ;; establish the aliases inside the ctx's default ns — never eval an
    ;; (ns ...) form here: nested sci contexts share the global current-ns
    (sci-eval ctx '(require '[clojure.string :as str]
                            '[babashka.fs :as fs]
                            '[kmet.libs.jsonrpc :as jrpc]))
    (doseq [f forms] (sci-eval ctx f))
    (testing "path->uri answers inside the sandbox"
      (let [cwd (str (fs/cwd))
            spaced (str cwd "/with space.clj")
            unicode (str cwd "/ünï cödé.clj")
            m (try (sci-eval ctx '(let [inputs [(str (fs/cwd) "/plain.clj")
                                                (str (fs/cwd) "/with space.clj")
                                                (str (fs/cwd) "/ünï cödé.clj")]]
                                    (zipmap inputs (map path->uri inputs))))
                   (catch Exception _ nil))]
        (is (map? m))
        (is (= (lsp/path->uri spaced) (get m spaced)))
        (is (str/ends-with? (get m spaced "") "/with%20space.clj"))
        (is (str/ends-with? (get m unicode "") "/%C3%BCn%C3%AF%20c%C3%B6d%C3%A9.clj"))))))

;; ─── Handshake ────────────────────────────────────────────────────────────

(deftest initialize-params
  (let [params (lsp/initialize-params "/proj/root" 4242 {:strict true})]
    (testing "the root is sent as rootUri and as a workspace folder"
      (is (= (lsp/path->uri "/proj/root") (:rootUri params)))
      (is (= "/proj/root" (:rootPath params)))
      (is (= [{:uri (lsp/path->uri "/proj/root") :name "root"}]
             (:workspaceFolders params))))
    (testing "the pid ties the server's life to ours"
      (is (= 4242 (:processId params))))
    (testing "initialization options pass through"
      (is (= {:strict true} (:initializationOptions params))))
    (testing "the capabilities ask for full sync and push diagnostics"
      (is (false? (get-in params [:capabilities :textDocument
                                  :synchronization :dynamicRegistration])))
      (is (true? (get-in params [:capabilities :textDocument
                                 :publishDiagnostics :versionSupport])))
      (is (true? (get-in params [:capabilities :workspace :configuration]))))))

(deftest shutdown-dance
  (is (= {:request "shutdown" :notification "exit"} lsp/shutdown-dance)))

;; ─── Server→client policy ─────────────────────────────────────────────────

(deftest on-request-policy
  (let [handler (lsp/make-on-request)]
    (testing "configuration answers per-item defaults"
      (is (= [] (handler "workspace/configuration" {}))))
    (testing "capability registration is accepted and ignored"
      (is (= {} (handler "client/registerCapability" {}))))
    (testing "progress creation is accepted"
      (is (= {} (handler "window/workDoneProgress/create" {}))))
    (testing "workspace/applyEdit is refused by policy"
      (is (= {:applied false :failureReason "kmet edits files itself"}
             (handler "workspace/applyEdit" {}))))
    (testing "an unknown method answers nil so the lib replies -32601"
      (is (nil? (handler "roots/list" {})))
      (is (nil? (handler "something/else" {}))))))

;; ─── Document sync ────────────────────────────────────────────────────────

(deftest document-sync-builders
  (let [path "/p/f.clj"]
    (testing "didOpen carries the language, version 1 and the text"
      (is (= {:textDocument {:uri (lsp/path->uri path)
                             :languageId "clojure"
                             :version 1
                             :text "(+ 1 1)"}}
             (lsp/did-open path "clojure" "(+ 1 1)"))))
    (testing "didChange is a single full-text content change"
      (is (= {:textDocument {:uri (lsp/path->uri path) :version 3}
              :contentChanges [{:text "new"}]}
             (lsp/did-change-full path 3 "new"))))
    (testing "didClose names the document"
      (is (= {:textDocument {:uri (lsp/path->uri path)}} (lsp/did-close path))))))

(deftest text-document-position
  (testing "the 1-based tool coordinates convert to 0-based wire positions"
    (is (= {:textDocument {:uri (lsp/path->uri "/p/f.clj")}
            :position {:line 0 :character 0}}
           (lsp/text-document-position "/p/f.clj" 1 1)))
    (is (= 234 (get-in (lsp/text-document-position "/p/f.clj" 235 7)
                       [:position :line])))
    (is (= 6 (get-in (lsp/text-document-position "/p/f.clj" 235 7)
                     [:position :character])))))

;; ─── Handshake failures ───────────────────────────────────────────────────

(deftest ^:slow handshake-failure-carries-stderr
  (testing "a server that dies during startup reports its stderr"
    (is (try (lsp/start! {:command ["bash" "-c"
                                    "echo 'boom: no tmp here' >&2; exit 1"]}
                         tu/extension-dir nil 5000)
             false
             (catch Exception e
               (and (str/includes? (ex-message e) "boom: no tmp here")
                    (vector? (:server-stderr (ex-data e)))))))))

(deftest ^:slow connect-does-not-leak-opts-into-argv
  (testing "the child sees zero extra arguments"
    ;; the child exits 0 only when spawned with no args; the historical
    ;; apply-spread bug fed it the opts map as argv
    (let [conn (jrpc/connect-stdio {:command ["bash" "-c" "test \"$#\" -eq 0"]
                                    :cwd tu/extension-dir})]
      (try
        (is (= 0 (:exit (deref (:proc conn) 10000 {:exit ::no-exit}))))
        (finally (try (jrpc/close! conn) (catch Exception _ nil)))))))

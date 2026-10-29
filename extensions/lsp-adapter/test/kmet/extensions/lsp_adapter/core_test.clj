(ns kmet.extensions.lsp-adapter.core-test
  "The extension entry point: what init registers against a nullable api
   (the `lsp` tool with its renderers, the /lsp command and its
   completions, the shutdown handler), the footer status line, and the
   command's headless reports. Nothing spawns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.extensions.lsp-adapter.core :as entry]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.runtime :as runtime]
            [kmet.extensions.lsp-adapter.test-util :as tu]))

(defn- with-extension [f]
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (try
      (let [st (entry/init api)]
        (f api state st))
      (finally (entry/shutdown api)))))

(defn- command [state]
  (get-in @state [:commands "lsp"]))

(defn- run-command
  "Run the /lsp command headlessly and return what it printed."
  [state args]
  (with-out-str ((:handler (command state)) {:has-ui false} args)))

;; ─── Registration ─────────────────────────────────────────────────────────

(deftest init-registers-the-tool
  (with-extension
    (fn [_api state _st]
      (let [tool (get-in @state [:tools "lsp"])]
        (testing "the lsp tool is registered with a complete schema"
          (is (some? tool))
          (is (= "LSP" (:label tool)))
          (is (true? (:contextual? tool)))
          (is (= ["operation" "filePath"] (get-in tool [:parameters :required])))
          (is (= ["definition" "references" "hover" "documentSymbol"
                  "workspaceSymbol" "implementation" "prepareCallHierarchy"
                  "incomingCalls" "outgoingCalls" "diagnostics"]
                 (get-in tool [:parameters :properties "operation" :enum]))))
        (testing "the transcript renderers are wired"
          (is (fn? (:render-call tool)))
          (is (fn? (:render-result tool)))
          (is (fn? (:title tool))))
        (testing "the prompt snippet is one line"
          (is (string? (:prompt-snippet tool)))
          (is (not (str/includes? (:prompt-snippet tool) "\n"))))))))

(deftest init-registers-the-command-and-shutdown-handler
  (with-extension
    (fn [_api state _st]
      (testing "the /lsp command answers with a description and completions"
        (is (str/includes? (:description (command state)) "Language server"))
        (is (fn? (:handler (command state))))
        (is (fn? (:get-argument-completions (command state)))))
      (testing "exactly one session-shutdown handler is registered"
        (is (seq (get-in @state [:handlers :session-shutdown])))
        (is (= 1 (count (get-in @state [:handlers :session-shutdown]))))))))

(deftest completions
  (with-extension
    (fn [_api state _st]
      (let [complete (:get-argument-completions (command state))]
        (testing "a bare prefix completes the subcommands"
          (is (= ["status"] (mapv :value (complete "sta"))))
          (is (= #{"status" "list" "restart" "refresh"}
                 (set (map :value (complete ""))))))
        (testing "restart completes server names"
          (is (some #(= "restart clojure-lsp" (:value %)) (complete "restart c"))))
        (testing "a subcommand that takes no server completes nothing"
          (is (empty? (complete "status x"))))))))

;; ─── Footer status ────────────────────────────────────────────────────────

(deftest footer-status
  (let [st (tu/new-state {:servers {}})]
    (testing "an idle fleet is not worth footer space"
      (is (nil? (#'entry/status-text st))))
    (testing "one connected server of the builtin fleet shows as N/M"
      (let [conn {:name "clojure-lsp" :root "/p"
                  :docs (atom {}) :diags (atom {})}]
        (reset! (:conns st) {["clojure-lsp" "/p"] conn})
        (let [text (#'entry/status-text st)]
          (is (some? text))
          (is (re-find #"^LSP 1/\d+$" text))
          (is (= (str "LSP 1/" (count (detect/effective-servers {})))
                 (str text))))))))

;; ─── Command reports ──────────────────────────────────────────────────────

(deftest command-list-report
  (with-extension
    (fn [_api state _st]
      (let [out (run-command state "list")]
        (testing "list names every effective server with its claims and roots"
          (is (str/includes? out "clojure-lsp — "))
          (is (str/includes? out "roots: deps.edn"))
          (is (str/includes? out "bash-language-server — ")))))))

(deftest command-index-and-mutation-reports
  (with-extension
    (fn [_api state st]
      (testing "status with no servers connected says so"
        (is (str/includes? (run-command state "status")
                           "no language servers connected")))
      (testing "restart without a server prints the usage"
        (is (str/includes? (run-command state "restart")
                           "usage: /lsp restart <server>")))
      (testing "restart names the server and clears its broken mark"
        (runtime/mark-broken! st "clojure-lsp" ":not installed")
        (let [out (run-command state "restart clojure-lsp")]
          (is (str/includes? out "Restarted \"clojure-lsp\""))
          (is (nil? (runtime/broken st "clojure-lsp")))))
      (testing "refresh reloads the config and clears the broken set"
        (runtime/mark-broken! st "gopls" ":spawn failed")
        (is (str/includes? (run-command state "refresh") ".kmet/lsp.edn reloaded."))
        (is (empty? @(:broken st))))
      (testing "an unknown subcommand lists the valid ones"
        (is (str/includes? (run-command state "nonsense")
                           "unknown /lsp subcommand: nonsense (status list restart refresh)"))))))

(deftest shutdown-stops-the-reaper-and-closes-conns
  (let [{:keys [api]} (ext/create-nullable-api)
        st (entry/init api)]
    (reset! (:conns st) {["clojure-lsp" "/p"]
                         {:name "clojure-lsp" :root "/p"
                          :docs (atom {}) :diags (atom {})
                          :client {:last-used (atom 0) :closed (atom false)}}})
    (entry/shutdown api)
    (testing "shutdown marks the reaper stopped and empties the conns"
      (is (true? @(:reaper-stop st)))
      (is (empty? @(:conns st))))
    (testing "a second shutdown is harmless"
      (is (nil? (entry/shutdown api))))))

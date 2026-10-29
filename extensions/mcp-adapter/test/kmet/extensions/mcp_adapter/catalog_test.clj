(ns kmet.extensions.mcp-adapter.catalog-test
  "The shared read view (catalog.clj) and the system-prompt snippet rule:
   description flattening/truncation, word truncation, the failure-backoff
   window, tool lookup and the composed display name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent :as concurrent]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── prompt-snippet (the system prompt's one-line tool entry) ─────────────

(defn- snippet [description] (catalog/prompt-snippet description 100))

(deftest prompt-snippet-flattens-descriptions
  (testing "a single-line description passes through"
    (is (= "Echo the message back" (snippet "Echo the message back"))))
  (testing "a multi-line description collapses onto one line"
    (is (= "Fetch a page. Returns clean markdown."
           (snippet "Fetch a page.\n\n   Returns clean markdown."))))
  (testing "no newline survives a CRLF + tab description"
    (is (not (str/includes? (snippet "a\r\nb\tc  d") "\n"))))
  (testing "newlines, tabs and repeated spaces become one space"
    (is (= "a b c d e" (snippet "a\n\n\t b\r\n  c\td  e"))))
  (testing "leading and trailing whitespace is trimmed"
    (is (= "trimmed" (snippet "\n\n  trimmed  \n"))))
  (testing "already-flattened text keeps single spaces"
    (is (= "one two three" (snippet "one two three")))))

(deftest prompt-snippet-truncates
  (testing "text at the limit is not truncated"
    (is (= "12345" (catalog/prompt-snippet "12345" 5))))
  (testing "long text is cut to N chars plus an ellipsis"
    (is (= "12345…" (catalog/prompt-snippet "1234567890" 5))))
  (testing "the cut applies to the flattened text"
    (let [s (snippet (apply str (repeat 30 "word ")))]
      (is (= 101 (count s)))
      (is (str/ends-with? s "…"))
      (is (not (str/includes? s "\n")))))
  (testing "a description that only overflows with its newlines still flattens"
    (is (= "alpha beta" (catalog/prompt-snippet "alpha\n\nbeta" 100)))))

(deftest prompt-snippet-missing-descriptions
  (is (= "" (snippet nil)))
  (is (= "" (snippet "")))
  (testing "whitespace-only stays empty, not a space"
    (is (= "" (snippet " \n\t ")))))

;; ─── truncate-at-word ─────────────────────────────────────────────────────

(deftest truncate-at-word
  (testing "short text is returned unchanged"
    (is (= "short" (catalog/truncate-at-word "short" 20)))
    (is (= "" (catalog/truncate-at-word nil 20))))
  (testing "long text cuts at the last space before the limit"
    (is (= "one two…" (catalog/truncate-at-word "one two three four" 10))))
  (testing "with no space it cuts at the character limit"
    (is (= "abcdefghij…" (catalog/truncate-at-word "abcdefghijklmno" 10)))))

;; ─── Definitions and runtime cells ────────────────────────────────────────

(deftest misconfigured-and-disabled
  (let [state (tu/relay-state
               {:settings {}
                :mcp-servers {"cmd" {:command "bb"}
                              "url" {:url "https://x/mcp"}
                              "none" {}
                              "off" {:command "bb" :disabled true}}})]
    (is (false? (catalog/misconfigured? (catalog/server-definition state "cmd"))))
    (is (false? (catalog/misconfigured? (catalog/server-definition state "url"))))
    (is (true? (catalog/misconfigured? (catalog/server-definition state "none"))))
    (is (true? (catalog/disabled? state "off")))
    (is (false? (catalog/disabled? state "cmd")))
    (testing "settings answers the config's settings map"
      (is (= {} (catalog/settings state))))
    (testing "server-state answers the runtime cells, nil when unknown"
      (is (some? (catalog/server-state state "cmd")))
      (is (nil? (catalog/server-state state "absent"))))))

(deftest failure-backoff-window
  (let [state (tu/relay-state {:settings {} :mcp-servers {"srv" {:command "bb"}}})
        failed-at (get-in state [:servers "srv" :failed-at])]
    (testing "no failure recorded means no backoff"
      (is (nil? (catalog/failure-age-seconds state "srv"))))
    (testing "a recent failure reports its age in whole seconds"
      (reset! failed-at (- (concurrent/monotonic-ms) 5000))
      (is (= 5 (catalog/failure-age-seconds state "srv"))))
    (testing "a failure older than the window no longer holds the server"
      (reset! failed-at (- (concurrent/monotonic-ms) 90000))
      (is (nil? (catalog/failure-age-seconds state "srv"))))))

(deftest return-error
  (is (= {:content "boom" :is-error true} (catalog/return-error "boom"))))

;; ─── Lookup and display names ─────────────────────────────────────────────

(def ^:private lookup-config
  {:settings {:tool-prefix :server}
   :mcp-servers {"alpha" {:command "bb"}
                 "beta" {:command "bb"}
                 "off" {:command "bb" :disabled true}}})

(def ^:private lookup-tools
  {"alpha" {:tools [{:name "echo" :description "Echo"}
                    {:name "read-file" :description "Dash name"}]
            :resource-templates [{:name "issues" :uriTemplate "file:///{x}"}]}
   "beta" {:tools [{:name "shared" :description "Both servers expose this"}]}
   "off" {:tools [{:name "hidden" :description "Disabled server"}]}})

(deftest find-tool
  (tu/with-agent-dir
    (fn [_]
      (let [state (tu/cached-state lookup-config lookup-tools {:tool-prefix :server})]
        (testing "an exact raw name resolves to its server"
          (is (= {:server "alpha" :tool {:name "echo" :description "Echo"}}
                 (catalog/find-tool state "echo"))))
        (testing "the composed display name resolves too"
          (is (= "alpha" (:server (catalog/find-tool state "alpha_echo")))))
        (testing "the sanitized spelling of a dash-named tool resolves"
          (is (= "alpha" (:server (catalog/find-tool state "read_file")))))
        (testing "a disabled server's tool is invisible"
          (is (nil? (catalog/find-tool state "hidden"))))
        (testing "an unknown name yields nil"
          (is (nil? (catalog/find-tool state "nope"))))))))

(deftest find-tool-ambiguous
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {:tool-prefix :none}
                    :mcp-servers {"a" {:command "bb"} "b" {:command "bb"}}}
            tools {"a" {:tools [{:name "echo" :description "Echo"}]}
                   "b" {:tools [{:name "echo" :description "Echo"}]}}
            state (tu/cached-state config tools {:tool-prefix :none})]
        (testing "the same raw name on two servers is ambiguous"
          (is (= :ambiguous (catalog/find-tool state "echo"))))))))

(deftest display-name
  (tu/with-agent-dir
    (fn [_]
      (let [state (tu/cached-state lookup-config lookup-tools {:tool-prefix :server})]
        (testing "without an assignment the composed name is used"
          (is (= "alpha_echo"
                 (catalog/display-name state "alpha" :tool "echo" "echo"))))
        (testing "an assignment wins over the composed name"
          (is (= "assigned_name"
                 (catalog/display-name (assoc state :tool-names
                                              {["alpha" :tool "echo"] "assigned_name"})
                                       "alpha" :tool "echo" "echo"))))
        (testing ":none keeps the bare name"
          (let [none-state (tu/cached-state
                            {:settings {:tool-prefix :none}
                             :mcp-servers {"alpha" {:command "bb"}}}
                            {"alpha" {:tools [{:name "echo" :description "E"}]}}
                            {:tool-prefix :none})]
            (is (= "echo" (catalog/display-name none-state "alpha" :tool "echo" "echo")))))))))

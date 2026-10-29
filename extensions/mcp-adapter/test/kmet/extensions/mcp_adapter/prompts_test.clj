(ns kmet.extensions.mcp-adapter.prompts-test
  "MCP prompts as slash commands (pi prompts.ts): command naming, bash-style
   argument parsing, the required-argument usage, result formatting, the
   cache-backed listing and the diff-based command registration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.prompts :as prompts]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Naming ───────────────────────────────────────────────────────────────

(deftest command-name
  (is (= "mcp__demo__brief"
         (prompts/command-name "demo" "brief" :server)))
  (testing "the server part is sanitized"
    (is (= "mcp__my_server__brief"
           (prompts/command-name "My Server" "brief" :server))))
  (testing ":short trims a trailing -mcp, :mcp is literal, :none keeps the name"
    (is (= "mcp__demo__x" (prompts/command-name "demo-mcp" "x" :short)))
    (is (= "mcp__mcp__x" (prompts/command-name "demo" "x" :mcp)))
    (is (= "mcp__demo__x" (prompts/command-name "demo" "x" :none))))
  (testing "the prompt part drops unsafe characters and is never empty"
    (is (= "mcp__demo__My_Prompt" (prompts/command-name "demo" "My Prompt!" :server)))
    (is (= "mcp__demo__prompt" (prompts/command-name "demo" "!!" :server))))
  (testing "a digit-leading prompt name is prefixed"
    (is (= "mcp__demo___2fa" (prompts/command-name "demo" "2fa" :server)))))

;; ─── Argument parsing ─────────────────────────────────────────────────────

(deftest parse-args
  (testing "whitespace splits positional arguments"
    (is (= {:positional ["a" "b" "c"] :named {}}
           (prompts/parse-args "a b   c"))))
  (testing "key=value tokens become named"
    (is (= {:positional ["rest"] :named {"day" "today"}}
           (prompts/parse-args "day=today rest"))))
  (testing "quotes group a value with spaces"
    (is (= {:positional ["important tasks"] :named {}}
           (prompts/parse-args "\"important tasks\"")))
    (is (= {:positional [] :named {"topic" "a b"}}
           (prompts/parse-args "topic=\"a b\""))))
  (testing "an empty value is kept as an empty string"
    (is (= "" (get-in (prompts/parse-args "topic=") [:named "topic"]))))
  (testing "an empty input is empty"
    (is (= {:positional [] :named {}} (prompts/parse-args "")))
    (is (= {:positional [] :named {}} (prompts/parse-args nil)))))

(deftest resolve-args
  (let [arguments [{:name "topic" :required true}
                   {:name "style"}]
        resolved (fn [input]
                   (prompts/resolve-args arguments (prompts/parse-args input) "cmd"))]
    (testing "positional values fill the declared slots in order"
      (is (= {:ok true :args {"topic" "a" "style" "b"}} (resolved "a b"))))
    (testing "named wins over positional for its slot"
      (is (= {:ok true :args {"topic" "named" "style" "b"}}
             (resolved "topic=named b"))))
    (testing "an undeclared named argument passes through"
      (is (= {:ok true :args {"topic" "a" "extra" "x"}}
             (resolved "a extra=x"))))
    (testing "a missing required argument yields a usage message"
      (is (= {:ok false
              :error "Missing required argument: topic.\nUsage: /cmd <topic> [style]"}
             (resolved "style=b")))))
  (testing "several missing arguments are pluralized"
    (let [r (prompts/resolve-args [{:name "a" :required true}
                                   {:name "b" :required true}]
                                  {:positional [] :named {}}
                                  "cmd")]
      (is (false? (:ok r)))
      (is (str/includes? (:error r) "Missing required arguments: a, b")))))

;; ─── Result formatting ────────────────────────────────────────────────────

(deftest format-result
  (testing "a single user message is returned bare"
    (is (= "Summarize this"
           (prompts/format-result
            {:messages [{:role "user" :content {:type "text" :text "Summarize this"}}]}))))
  (testing "mixed roles keep inline markers"
    (is (= "[user] hi\n\n[assistant] there"
           (prompts/format-result
            {:messages [{:role "user" :content {:type "text" :text "hi"}}
                        {:role "assistant" :content {:type "text" :text "there"}}]}))))
  (testing "an embedded resource keeps its uri and text"
    (is (= "[resource file:///r]\nbody"
           (prompts/format-result
            {:messages [{:role "user"
                         :content {:type "resource"
                                   :resource {:uri "file:///r" :text "body"}}}]}))))
  (testing "a resource link, image and audio are summarized"
    (is (= "[resource_link file:///r — doc]"
           (prompts/format-result
            {:messages [{:role "user"
                         :content {:type "resource_link" :uri "file:///r" :name "doc"}}]})))
    (is (= "[image image/png (embedded)]"
           (prompts/format-result
            {:messages [{:role "user"
                         :content {:type "image" :mimeType "image/png" :data "xx"}}]})))
    (is (= "[audio audio/wav]"
           (prompts/format-result
            {:messages [{:role "user" :content {:type "audio" :mimeType "audio/wav"}}]}))))
  (testing "blank messages are dropped, the rest keep their role marker"
    (is (= "[user] kept"
           (prompts/format-result
            {:messages [{:role "user" :content {:type "text" :text "  "}}
                        {:role "user" :content {:type "text" :text "kept"}}]}))))
  (testing "an empty result is empty"
    (is (= "" (prompts/format-result {})))
    (is (= "" (prompts/format-result {:messages []})))))

;; ─── Cache-backed listing + registration ─────────────────────────────────

(def ^:private config
  {:settings {:tool-prefix :server}
   :mcp-servers {"demo" {:command "bb"}
                 "off" {:command "bb" :disabled true}}})

(def ^:private cache-tools
  {"demo" {:prompts [{:name "brief" :description "Briefly summarize a topic"
                      :arguments [{:name "topic" :required true}]}
                     {:name "review"}]
           :tools [{:name "echo" :description "Echo"}]}
   "off" {:prompts [{:name "secret" :description "Never registered"}]}})

(defn- prompt-state
  "A state map with the cache seeded and the nullable api attached, as the
   extension's init builds it."
  [api]
  (let [state (tu/cached-state config cache-tools {:tool-prefix :server})]
    (atom (assoc state :api api :registered-prompts (atom {})))))

(deftest prompts-text
  (tu/with-agent-dir
    (fn [_]
      (let [state (prompt-state nil)
            out (prompts/prompts-text state)]
        (testing "cached prompts list by command name"
          (is (str/includes? out "- /mcp__demo__brief — Briefly summarize a topic"))
          (is (str/includes? out "- /mcp__demo__review")))
        (testing "a disabled server's prompts are never listed"
          (is (not (str/includes? out "secret")))))
      (testing "an empty cache explains how to fill it"
        (is (= "No MCP prompts cached. Connect a server with /mcp connect to discover prompts."
               (prompts/prompts-text (atom (assoc (tu/relay-state
                                                   {:settings {} :mcp-servers {}})
                                                  :registered-prompts (atom {}))))))))))

(deftest sync-prompt-commands
  (tu/with-agent-dir
    (fn [_]
      (let [{:keys [api state]} (ext/create-nullable-api)
            st (prompt-state api)]
        (prompts/sync-prompt-commands! st)
        (testing "every cached prompt becomes a command"
          (is (= #{"mcp__demo__brief" "mcp__demo__review"}
                 (set (keys (:commands @state)))))
          (is (= #{"mcp__demo__brief" "mcp__demo__review"}
                 (set (keys @(:registered-prompts @st))))))
        (testing "the description is prefixed and truncated to one line"
          (is (str/starts-with? (get-in @state [:commands "mcp__demo__brief" :description])
                                "MCP: Briefly summarize")))
        (testing "a disabled server registers nothing"
          (is (not (contains? (:commands @state) "mcp__off__secret"))))
        (testing "a re-sync with unchanged specs re-registers nothing"
          (let [registered (:commands @state)]
            (prompts/sync-prompt-commands! st)
            (is (= registered (:commands @state)))))
        (testing "a prompt that disappears is unregistered"
          (swap! st assoc :cache {:version 1 :servers {}})
          (prompts/sync-prompt-commands! st)
          (is (empty? (:commands @state)))
          (is (empty? @(:registered-prompts @st))))))))

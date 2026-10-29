(ns kmet.extensions.mcp-adapter.search-test
  "The proxy search (§9.3): the weighted ranking port, the regex mode, the
   paging footer, the per-tool schema lines and the server filter. Reads
   the metadata cache only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.mcp-adapter.search :as search]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(def ^:private settings {:tool-prefix :server})

(def ^:private config
  {:settings settings
   :mcp-servers {"fs" {:command "bb"
                       :search-keywords {"fs_*" ["capture" "snapshot"]}}
                 "web" {:command "bb"}
                 "off" {:command "bb" :disabled true}}})

(def ^:private cache-tools
  {"fs" {:tools [{:name "read_file"
                  :description "Read a file from disk"
                  :inputSchema {:type "object"
                                :properties {"path" {:type "string"
                                                     :description "File to read"}
                                             "limit" {:type "integer" :default 100}}
                                :required ["path"]}}
                 {:name "write_file"
                  :description "Write a file to disk"
                  :inputSchema {}}]}
   "web" {:tools [{:name "fetch_page"
                   :description "Fetch a page and return clean markdown"
                   :inputSchema {}}]}
   "off" {:tools [{:name "hidden_tool" :description "Should never appear"}]}})

(defn- state []
  (tu/cached-state config cache-tools settings))

(defn- text
  ([query] (text query {}))
  ([query opts]
   (:content (search/search-text (state) query (:regex opts)
                                 (:server opts) (:schemas opts)
                                 (:limit opts) (:offset opts)))))

(deftest ranked-search
  (tu/with-agent-dir
    (fn [_]
      (let [out (text "read")]
        (testing "the matching tool is listed with server and display name"
          (is (str/starts-with? out "Found 1 tool matching \"read\":"))
          (is (str/includes? out "fs: fs_read_file — Read a file from disk")))
        (testing "its schema renders one indented line per parameter"
          (is (str/includes? out "  limit (integer, optional, default: 100)"))
          (is (str/includes? out "  path (string, required) — File to read")))
        (testing "a disabled server never contributes a hit"
          (is (not (str/includes? out "hidden_tool"))))))))

(deftest search-without-schemas
  (tu/with-agent-dir
    (fn [_]
      (let [out (text "read" {:schemas false})]
        (is (str/includes? out "fs: fs_read_file — Read a file from disk"))
        (is (not (str/includes? out "path (string, required)")))))))

(deftest search-multiplicity-and-footer
  (tu/with-agent-dir
    (fn [_]
      (testing "a plural count is reported"
        (is (str/starts-with? (text "file") "Found 2 tools matching \"file\":")))
      (testing "a page says how to get the rest"
        (let [out (text "file" {:limit 1})]
          (is (= 1 (count (re-seq #"(?m)^\S+: \S+" out))))
          (is (str/includes? out "1 of 2 — offset: 1 for more"))))
      (testing "the offset skips earlier hits"
        (let [first-page (text "file" {:limit 1})
              second-page (text "file" {:limit 1 :offset 1})]
          (is (not= (second (re-find #"(?m)^(\S+: \S+)" first-page))
                    (second (re-find #"(?m)^(\S+: \S+)" second-page)))))))))

(deftest search-empty-and-missing
  (tu/with-agent-dir
    (fn [_]
      (testing "an empty query without a server is an error"
        (is (= {:content "Search query cannot be empty" :is-error true}
               (search/search-text (state) "  " false nil true nil nil))))
      (testing "an empty query with a server lists that server"
        (let [out (text "" {:server "fs"})]
          (is (str/includes? out "fs_read_file"))
          (is (str/includes? out "fs_write_file"))
          (is (not (str/includes? out "fetch_page")))))
      (testing "no match is not an error"
        (let [out (text "definitelynothinghere")]
          (is (str/includes? out "No tools matching \"definitelynothinghere\""))))
      (testing "a server filter that matches nothing reports the server"
        (is (str/includes? (text "page" {:server "fs"})
                           "No tools matching \"page\" in \"fs\""))))))

(deftest search-regex-mode
  (tu/with-agent-dir
    (fn [_]
      (testing "regex mode matches names case-insensitively"
        (is (str/includes? (text "^fetch_" {:regex true}) "fetch_page")))
      (testing "an invalid regex is reported"
        (is (= {:content "Invalid regex: ([" :is-error true}
               (search/search-text (state) "([" true nil true nil nil))))
      (testing "an over-long query is refused before compiling"
        (is (true? (:is-error (search/search-text
                               (state) (apply str (repeat 300 "a"))
                               true nil true nil nil)))))
      (testing "regex mode honors the server filter"
        (is (not (str/includes? (text "^read" {:regex true :server "web"})
                                "read_file")))))))

(deftest search-keywords-boost
  (tu/with-agent-dir
    (fn [_]
      (testing "a configured searchKeyword makes the tool findable"
        (is (str/includes? (text "capture") "fs_read_file")))
      (testing "the keyword does not leak to a server without the option"
        (is (not (str/includes? (text "snapshot" {:server "web"}) "read_file")))))))

(deftest search-ranking-prefers-a-name-hit
  (tu/with-agent-dir
    (fn [_]
      (let [out (text "fetch_page")
            first-name (second (re-find #"(?m)^\S+: (\S+)" out))]
        (testing "an exact name hit ranks first"
          (is (= "web_fetch_page" first-name)))))))

(ns kmet.extensions.mcp-adapter.names-test
  "§10.5 / Phase 0 naming: sanitization, prefix modes, candidates and
   globs, and the deterministic assignment — including that display and
   registration read the same assignment."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.mcp-adapter.core :as core]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.names :as names]
            [kmet.extensions.mcp-adapter.status :as status]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Sanitization ─────────────────────────────────────────────────────────

(deftest sanitization
  (testing "tool names lowercase and map every non [a-z0-9_] to _"
    (is (= "read_file_" (names/sanitize-tool-name "Read File!")))
    (is (= "a_b" (names/sanitize-tool-name "a-b"))))
  (testing "server names sanitize like tool names"
    (is (= "my_server" (names/sanitize-server-name "My Server"))))
  (testing "resource names collapse separators and trim"
    (is (= "my_resource" (names/resource-tool-name "My Resource!")))
    (is (= "a_b" (names/resource-tool-name "a--b"))))
  (testing "empty and digit-leading resource names get the resource prefix"
    (is (= "resource" (names/resource-tool-name "")))
    (is (= "resource_9lives" (names/resource-tool-name "9lives"))))
  (testing "a display name with punctuation normalizes to one separator"
    (is (= "read_issues_all" (names/resource-tool-name "read_issues (all)")))))

;; ─── Prefix modes ─────────────────────────────────────────────────────────

(deftest prefix-modes
  (is (= "fs" (names/prefix-for "fs" :server)))
  (testing ":short trims a trailing -mcp, keeping a bare mcp"
    (is (= "fs" (names/prefix-for "fs-mcp" :short)))
    (is (= "mcp" (names/prefix-for "mcp" :short))))
  (is (= "" (names/prefix-for "x" :none)))
  (is (= "mcp" (names/prefix-for "x" :mcp)))
  (testing "effective mode: per-server wins, settings next, :server default"
    (is (= :none (names/effective-mode {:tool-prefix :none} {:tool-prefix :server})))
    (is (= :short (names/effective-mode nil {:tool-prefix :short})))
    (is (= :server (names/effective-mode nil nil))))
  (testing "prefixed-name composes prefix and sanitized tool name"
    (is (= "fs_read_file" (names/prefixed-name "fs" "read-file" :server)))
    (is (= "read_file" (names/prefixed-name "fs" "read-file" :none)))))

;; ─── Candidates + globs ───────────────────────────────────────────────────

(deftest candidates-and-globs
  (testing "candidates cover raw, legacy and every prefix mode"
    (let [c (names/tool-name-candidates "fs" "read-file")]
      (is (contains? c "read-file"))
      (is (contains? c "read_file"))
      (is (contains? c "fs_read_file"))
      (is (contains? c "mcp_read_file"))))
  (testing "include globs match a prefixed candidate only on that server"
    (is (names/tool-allowed? "fs" "read_file" ["fs_*"] []))
    (is (not (names/tool-allowed? "other" "read_file" ["fs_*"] []))))
  (testing "exact include matches the raw name"
    (is (names/tool-allowed? "fs" "echo" ["echo"] [])))
  (testing "exclude always wins; an empty include allows everything"
    (is (not (names/tool-allowed? "fs" "read_file" [] ["fs_*"])))
    (is (names/tool-allowed? "fs" "echo" [] [])))
  (testing "a ? pattern matches one character"
    (is (names/tool-allowed? "fs" "read_file" ["fs_read?file"] [])))
  (testing "regex metacharacters in a pattern are literal"
    (is (not (names/tool-allowed? "fs" "echo" ["fs.echo"] [])))))

;; ─── Assignment ───────────────────────────────────────────────────────────

(defn- assigned [entries modes]
  (names/assign-names entries modes))

(deftest assignment-without-collision
  (is (= {["fs" :tool "read_file"] "fs_read_file"}
         (assigned [{:server "fs" :kind :tool :id "read_file" :name "read_file"}]
                   {"fs" :server})))
  (testing "a resource tool under :none keeps its read_ name"
    (is (= {["s" :resource "file:///x"] "read_x"}
           (assigned [{:server "s" :kind :resource :id "file:///x" :name "read_x"}]
                     {"s" :none}))))
  (testing "a prefixed builtin name is untouched"
    (is (= {["a" :tool "read"] "mcp_read"}
           (assigned [{:server "a" :kind :tool :id "read" :name "read"}]
                     {"a" :mcp})))))

(deftest assignment-with-collision
  (let [collide [{:server "a" :kind :tool :id "echo" :name "echo"}
                 {:server "b" :kind :tool :id "echo" :name "echo"}]
        modes {"a" :none "b" :none}]
    (testing "a candidate shared by two servers sends both to the fallback"
      (is (= {["a" :tool "echo"] "a_echo" ["b" :tool "echo"] "b_echo"}
             (assigned collide modes))))
    (testing "no server keeps the contested plain name"
      (is (not-any? #(= "echo" %) (vals (assigned collide modes)))))
    (testing "order-independent"
      (is (= (assigned collide modes)
             (assigned (reverse collide) modes))))
    (testing "a bare builtin collides and falls back"
      (is (= {["a" :tool "read"] "a_read"}
             (assigned [{:server "a" :kind :tool :id "read" :name "read"}]
                       {"a" :none}))))
    (testing "per-server mode changes only that server's entries"
      (is (= {["a" :tool "echo"] "a_echo" ["b" :tool "echo"] "echo"}
             (assigned [{:server "a" :kind :tool :id "echo" :name "echo"}
                        {:server "b" :kind :tool :id "echo" :name "echo"}]
                       {"a" :server "b" :none}))))))

(deftest assignment-fallback-uniqueness
  (testing "server names that sanitize the same get a digest suffix"
    (let [vals* (vals (assigned
                       [{:server "my-server" :kind :tool :id "echo" :name "echo"}
                        {:server "my_server" :kind :tool :id "echo" :name "echo"}]
                       {"my-server" :none "my_server" :none}))]
      (is (= 2 (count vals*)))
      (is (every? #(re-matches #"my_server_echo_[0-9a-f]{8}" %) vals*))
      (is (apply distinct? vals*))))
  (testing "a tool/resource candidate collision stays unique"
    (let [vals* (vals (assigned
                       [{:server "s" :kind :tool :id "read_x" :name "read_x"}
                        {:server "s" :kind :resource :id "file:///x" :name "read_x"}]
                       {"s" :server}))]
      (is (= 2 (count vals*)))
      (is (every? #(re-matches #"s_read_x_[0-9a-f]{8}" %) vals*))
      (is (apply distinct? vals*))))
  (testing "every entry gets exactly one name"
    (let [entries [{:server "a" :kind :tool :id "echo" :name "echo"}
                   {:server "a" :kind :tool :id "read" :name "read"}
                   {:server "b" :kind :tool :id "echo" :name "echo"}
                   {:server "b" :kind :resource :id "file:///r" :name "read_r"}]
          result (assigned entries {"a" :none "b" :server})]
      (is (= (count entries) (count result)))
      (is (= (set (map (fn [e] [(:server e) (:kind e) (:id e)]) entries))
             (set (keys result)))))))

;; ─── Registration and display share the assignment ────────────────────────

(deftest registration-and-display-agree
  (tu/with-agent-dir
    (fn [_]
      (let [settings {:tool-prefix :none :direct-tools true}
            definitions {"alpha" {:command "bb" :args ["alpha"]}
                         "beta" {:command "bb" :args ["beta"]}}
            config {:settings settings :mcp-servers definitions}
            cache {:version 1
                   :servers
                   (into {}
                         (map (fn [[name definition]]
                                [name {:config-fingerprint
                                       (metadata/config-fingerprint name definition settings)
                                       :fetched-at (System/currentTimeMillis)
                                       :tools [{:name "echo" :description "Echo"
                                                :inputSchema {}}]}]))
                         definitions)}
            base-state {:config config
                        :servers {"alpha" {:conn (atom nil) :error (atom nil)
                                           :failed-at (atom nil)}
                                  "beta" {:conn (atom nil) :error (atom nil)
                                          :failed-at (atom nil)}}
                        :cache cache}
            entries (@#'core/catalog-entries (atom base-state))
            assigned (names/assign-names entries {"alpha" :none "beta" :none})
            state (assoc base-state :tool-names assigned)
            specs (@#'core/direct-tools-specs (atom state))]
        (testing "catalog-entries covers every cached tool"
          (is (= #{["alpha" :tool "echo"] ["beta" :tool "echo"]}
                 (set (map (fn [e] [(:server e) (:kind e) (:id e)]) entries)))))
        (testing "the contested pair goes to the fallback"
          (is (= {["alpha" :tool "echo"] "alpha_echo"
                  ["beta" :tool "echo"] "beta_echo"}
                 assigned)))
        (testing "direct-tools specs register the assigned names"
          (is (= #{"alpha_echo" "beta_echo"} (set (map :prefixed specs)))))
        (testing "describe and list show the assigned name"
          (is (str/starts-with? (:content (status/describe-text state "alpha_echo"))
                                "alpha_echo"))
          (is (str/starts-with? (:content (status/describe-text state "beta_echo"))
                                "beta_echo"))
          (is (str/includes? (:content (status/list-text state "beta")) "beta_echo")))
        (testing "the contested plain name is reported ambiguous"
          (is (str/includes? (:content (status/describe-text state "echo"))
                             "matches multiple servers")))))))

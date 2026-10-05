#!/usr/bin/env bb
;; Names validation for the mcp-adapter (§10.5 / Phase 0): sanitization,
;; prefix modes, candidates and globs, plus the deterministic assignment
;; (display == registered for every collision case). Pure — no servers, no
;; state.
(require '[clojure.string :as str]
         '[kmet.extensions.mcp-adapter.core :as core]
         '[kmet.extensions.mcp-adapter.metadata :as metadata]
         '[kmet.extensions.mcp-adapter.names :as names]
         '[kmet.extensions.mcp-adapter.status :as status])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(println "── sanitization ──")
(check "tool name: lowercase, [^a-z0-9_] → _"
       (= "read_file_" (names/sanitize-tool-name "Read File!")))
(check "server name sanitized like a tool name"
       (= "my_server" (names/sanitize-server-name "My Server")))
(check "resource: separators collapsed and trimmed"
       (= "my_resource" (names/resource-tool-name "My Resource!")))
(check "resource: empty → resource"
       (= "resource" (names/resource-tool-name "")))
(check "resource: digit start → resource_"
       (= "resource_9lives" (names/resource-tool-name "9lives")))
(check "resource: run collapse"
       (= "a_b" (names/resource-tool-name "a--b")))

(println "\n── prefix modes ──")
(check ":server → sanitized server name"
       (= "fs" (names/prefix-for "fs" :server)))
(check ":short trims a trailing -mcp"
       (= "fs" (names/prefix-for "fs-mcp" :short)))
(check ":short of a bare mcp keeps mcp"
       (= "mcp" (names/prefix-for "mcp" :short)))
(check ":none → no prefix" (= "" (names/prefix-for "x" :none)))
(check ":mcp → mcp" (= "mcp" (names/prefix-for "x" :mcp)))
(check "per-server mode wins over settings"
       (= :none (names/effective-mode {:tool-prefix :none} {:tool-prefix :server})))
(check "settings mode when the server has none"
       (= :short (names/effective-mode nil {:tool-prefix :short})))
(check "default mode is :server"
       (= :server (names/effective-mode nil nil)))
(check "prefixed-name composes"
       (= "fs_read_file" (names/prefixed-name "fs" "read-file" :server)))
(check "prefixed-name without a prefix"
       (= "read_file" (names/prefixed-name "fs" "read-file" :none)))

(println "\n── candidates + globs ──")
(check "candidates cover raw, legacy and every prefix mode"
       (let [c (names/tool-name-candidates "fs" "read-file")]
         (and (contains? c "read-file") (contains? c "read_file")
              (contains? c "fs_read_file") (contains? c "mcp_read_file"))))
(check "include glob matches a prefixed candidate"
       (names/tool-allowed? "fs" "read_file" ["fs_*"] []))
(check "include glob does not match another server"
       (not (names/tool-allowed? "other" "read_file" ["fs_*"] [])))
(check "exact include matches the raw name"
       (names/tool-allowed? "fs" "echo" ["echo"] []))
(check "exclude always wins"
       (not (names/tool-allowed? "fs" "read_file" [] ["fs_*"])))
(check "empty include allows everything"
       (names/tool-allowed? "fs" "echo" [] []))

(println "\n── assignment: no collision ──")
(check "single tool keeps its composed name"
       (= {["fs" :tool "read_file"] "fs_read_file"}
          (names/assign-names [{:server "fs" :kind :tool :id "read_file" :name "read_file"}]
                              {"fs" :server})))
(check "resource read tool under :none keeps read_<name>"
       (= {["s" :resource "file:///x"] "read_x"}
          (names/assign-names [{:server "s" :kind :resource :id "file:///x" :name "read_x"}]
                              {"s" :none})))
(check "prefixed builtin name is untouched"
       (= {["a" :tool "read"] "mcp_read"}
          (names/assign-names [{:server "a" :kind :tool :id "read" :name "read"}]
                              {"a" :mcp})))

(println "\n── assignment: collisions ──")
(def collide
  [{:server "a" :kind :tool :id "echo" :name "echo"}
   {:server "b" :kind :tool :id "echo" :name "echo"}])

(check "a candidate shared by two servers sends BOTH to the fallback"
       (= {["a" :tool "echo"] "a_echo" ["b" :tool "echo"] "b_echo"}
          (names/assign-names collide {"a" :none "b" :none})))
(check "no server keeps the contested plain name"
       (not-any? #(= "echo" %) (vals (names/assign-names collide {"a" :none "b" :none}))))
(check "order-independent"
       (= (names/assign-names collide {"a" :none "b" :none})
          (names/assign-names (reverse collide) {"a" :none "b" :none})))
(check "bare builtin collides and falls back"
       (= {["a" :tool "read"] "a_read"}
          (names/assign-names [{:server "a" :kind :tool :id "read" :name "read"}]
                              {"a" :none})))
(check "per-server mode changes only that server"
       (= {["a" :tool "echo"] "a_echo" ["b" :tool "echo"] "echo"}
          (names/assign-names [{:server "a" :kind :tool :id "echo" :name "echo"}
                               {:server "b" :kind :tool :id "echo" :name "echo"}]
                              {"a" :server "b" :none})))

(println "\n── assignment: fallback uniqueness ──")
(let [assigned (names/assign-names
                [{:server "my-server" :kind :tool :id "echo" :name "echo"}
                 {:server "my_server" :kind :tool :id "echo" :name "echo"}]
                {"my-server" :none "my_server" :none})
      vals* (vals assigned)]
  (check "sanitized server-name collision gets a digest suffix"
         (and (= 2 (count vals*))
              (every? #(re-matches #"my_server_echo_[0-9a-f]{8}" %) vals*)
              (apply distinct? vals*))))

(let [assigned (names/assign-names
                [{:server "s" :kind :tool :id "read_x" :name "read_x"}
                 {:server "s" :kind :resource :id "file:///x" :name "read_x"}]
                {"s" :server})
      vals* (vals assigned)]
  (check "tool/resource candidate collision stays unique"
         (and (= 2 (count vals*))
              (every? #(re-matches #"s_read_x_[0-9a-f]{8}" %) vals*)
              (apply distinct? vals*))))

(check "every entry gets exactly one name"
       (let [entries [{:server "a" :kind :tool :id "echo" :name "echo"}
                      {:server "a" :kind :tool :id "read" :name "read"}
                      {:server "b" :kind :tool :id "echo" :name "echo"}
                      {:server "b" :kind :resource :id "file:///r" :name "read_r"}]
             assigned (names/assign-names entries {"a" :none "b" :server})]
         (and (= (count entries) (count assigned))
              (= (set (map (fn [e] [(:server e) (:kind e) (:id e)]) entries))
                 (set (keys assigned))))))

(println "\n── registration and display share the assignment ──")
(let [settings {:tool-prefix :none :direct-tools true}
      definitions {"alpha" {:command "bb" :args ["alpha"]}
                   "beta" {:command "bb" :args ["beta"]}}
      config {:settings settings :mcp-servers definitions}
      cache {:version 1
             :servers
             (into {}
                   (map (fn [[name definition]]
                          [name {:config-fingerprint (metadata/config-fingerprint
                                                      name definition settings)
                                 :fetched-at (System/currentTimeMillis)
                                 :tools [{:name "echo" :description "Echo" :inputSchema {}}]}]))
                   definitions)}
      base-state {:config config
                  :servers {"alpha" {:conn (atom nil) :error (atom nil) :failed-at (atom nil)}
                            "beta" {:conn (atom nil) :error (atom nil) :failed-at (atom nil)}}
                  :cache cache}
      entries (@#'core/catalog-entries (atom base-state))
      assigned (names/assign-names entries {"alpha" :none "beta" :none})
      state (assoc base-state :tool-names assigned)
      specs (@#'core/direct-tools-specs (atom state))]
  (check "catalog-entries covers every cached tool"
         (= #{["alpha" :tool "echo"] ["beta" :tool "echo"]}
            (set (map (fn [e] [(:server e) (:kind e) (:id e)]) entries))))
  (check "assign-names sends the contested pair to the fallback"
         (= {["alpha" :tool "echo"] "alpha_echo" ["beta" :tool "echo"] "beta_echo"}
            assigned))
  (check "direct-tools specs register the assigned names"
         (= #{"alpha_echo" "beta_echo"} (set (map :prefixed specs))))
  (check "describe shows the assigned name for alpha"
         (str/starts-with? (:content (status/describe-text state "alpha_echo")) "alpha_echo"))
  (check "describe shows the assigned name for beta"
         (str/starts-with? (:content (status/describe-text state "beta_echo")) "beta_echo"))
  (check "the contested plain name is reported ambiguous"
         (str/includes? (:content (status/describe-text state "echo"))
                        "matches multiple servers"))
  (check "list shows the assigned name"
         (str/includes? (:content (status/list-text state "beta")) "beta_echo")))

(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))

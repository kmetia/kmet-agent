(ns kmet.extensions.lsp-adapter.tools-test
  "The `lsp` tool's shaping and dispatch: the token-economical result
   summaries, the diagnostics report (push state only, no round-trip), and
   the usage errors a bad call earns. No server is spawned."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.lsp-adapter.lsp :as lsp]
            [kmet.extensions.lsp-adapter.runtime :as runtime]
            [kmet.extensions.lsp-adapter.test-util :as tu]
            [kmet.extensions.lsp-adapter.tools :as tools]))

(defn- usage-error
  "The ex-info a usage mistake throws, or nil."
  [thunk]
  (try (thunk) nil (catch Exception e e)))

;; ─── Result shaping ───────────────────────────────────────────────────────

(deftest shape-locations
  (let [loc (fn [path line col]
              {:uri (lsp/path->uri path) :range {:start {:line line :character col}}})
        shaped (@#'tools/shape-result :definition [(loc "/p/a.clj" 0 1)
                                                   (loc "/p/a.clj" 4 2)
                                                   (loc "/p/a.clj" 4 2)])]
    (testing "locations render as 1-based path:line:col and de-duplicate"
      (is (= ["/p/a.clj:1:2" "/p/a.clj:5:3"] (:lines shaped)))
      (is (= 2 (count (:items shaped)))))
    (testing "a single location (not in an array) is accepted"
      (is (= ["/p/a.clj:1:2"]
             (:lines (@#'tools/shape-result :definition (loc "/p/a.clj" 0 1))))))
    (testing "an empty result shapes to nil"
      (is (nil? (@#'tools/shape-result :definition nil)))
      (is (nil? (@#'tools/shape-result :definition []))))
    (testing "the display list is capped at 50 entries"
      (let [many (mapv #(loc "/p/a.clj" % 0) (range 60))
            shaped (@#'tools/shape-result :references many)]
        (is (= 51 (count (:lines shaped))))
        (is (= "… and 10 more" (last (:lines shaped))))
        (is (= 10 (:more shaped)))))))

(deftest shape-hover
  (testing "a string payload is the hover text"
    (is (= ["(defn f [])"]
           (:lines (@#'tools/shape-result :hover {:contents "(defn f [])"})))))
  (testing "a markup payload uses :value"
    (is (= ["doc"] (:lines (@#'tools/shape-result :hover
                                                  {:contents {:kind "markdown"
                                                              :value "doc"}})))))
  (testing "an array of payloads joins with newlines"
    (is (= ["a\nb"] (:lines (@#'tools/shape-result :hover
                                                   {:contents [{:value "a"} "b"]})))))
  (testing "blank contents shape to nil"
    (is (nil? (@#'tools/shape-result :hover {:contents ""})))
    (is (nil? (@#'tools/shape-result :hover nil))))
  (testing "hover text is capped at 2000 characters"
    (let [text (apply str (repeat 2500 "x"))
          line (first (:lines (@#'tools/shape-result :hover {:contents text})))]
      (is (= 2001 (count line)))
      (is (str/ends-with? line "…")))))

(deftest shape-symbols
  (let [tree [{:name "ns-one" :kind 3
               :children [{:name "alpha" :kind 12 :children [{:name "inner" :kind 13}]}
                          {:name "beta" :kind 13}]}]
        shaped (@#'tools/shape-result :documentSymbol tree)]
    (testing "the tree flattens with depth and kind names"
      (is (= [{:depth 0 :name "ns-one" :kind "namespace"}
              {:depth 1 :name "alpha" :kind "fn"}
              {:depth 2 :name "inner" :kind "var"}
              {:depth 1 :name "beta" :kind "var"}]
             (:items shaped))))
    (testing "the display lines derive from the items"
      (is (= ["ns-one namespace" "  alpha fn" "    inner var" "  beta var"]
             (:lines shaped))))))

(deftest shape-hierarchy
  (let [res [{:from {:name "caller" :kind 12
                     :uri (lsp/path->uri "/p/c.clj")
                     :range {:start {:line 9 :character 0}}}
              :fromRanges [{:start {}} {:start {}}]}
             {:from {:name "caller" :kind 12
                     :uri (lsp/path->uri "/p/c.clj")
                     :range {:start {:line 9 :character 0}}}
              :fromRanges [{:start {}} {:start {}}]}]
        shaped (@#'tools/shape-result :incomingCalls res)]
    (testing "repeated resolution contexts collapse to one site row"
      (is (= ["caller fn @ /p/c.clj:10 (2 sites)"] (:lines shaped)))
      (is (= 1 (count (:items shaped)))))))

(deftest kind-name-fallback
  (is (= "method" (@#'tools/kind-name 6)))
  (is (= "kind:99" (@#'tools/kind-name 99))))

(deftest shape-prepare-call-hierarchy
  (let [res [{:name "target" :kind 6
              :uri (lsp/path->uri "/p/t.clj")
              :range {:start {:line 2 :character 0}}}]
        shaped (@#'tools/shape-result :prepareCallHierarchy res)]
    (is (= ["target method @ /p/t.clj:3"] (:lines shaped)))
    (is (= 0 (:more shaped)))
    (is (nil? (@#'tools/shape-result :prepareCallHierarchy [])))))

;; ─── Diagnostics report ───────────────────────────────────────────────────

(defn- diag [severity line message & [source]]
  {:severity severity :message message :source source
   :range {:start {:line (dec line) :character 0} :end {:start {} :end {}}}})

(defn- conn-with-diags [name root diags-by-uri]
  {:name name :root root :diags (atom diags-by-uri)
   :docs (atom {}) :client {:last-used (atom 0)}})

(deftest diagnostics-report
  (let [dir (tu/temp-dir! "diags")
        file (str dir "/sample.txt")
        uri (lsp/path->uri file)
        conn (conn-with-diags "fake" dir
                              {uri {:diagnostics [(diag 1 1 "boom" "fake")
                                                  (diag 2 3 "meh")]}})]
    (try
      (with-redefs [runtime/claiming-specs (fn [_ _] [{:id "fake" :root dir}])
                    runtime/all-conns (fn [_] [conn])]
        (let [{:keys [text sections]} (tools/diagnostics-report nil file)]
          (testing "the queried file's diagnostics render with severity and source"
            (is (str/includes? text "ERROR [1:1] boom (fake)"))
            (is (str/includes? text "WARN [3:1] meh")))
          (testing "the section is labelled with the server and its root"
            (is (some #(str/includes? (:label %) "fake @ ") sections))
            (is (= :diagnostics (:kind (first sections))))
            (is (= 2 (count (:items (first sections)))))))
        (testing "a fleet with no diagnostics reports (no results)"
          (let [clean (conn-with-diags "fake" dir {})
                other (str dir "/clean.txt")]
            (with-redefs [runtime/claiming-specs (fn [_ _] [{:id "fake" :root dir}])
                          runtime/all-conns (fn [_] [clean])]
              (is (= "(no results)" (:text (tools/diagnostics-report nil other))))))))
      (finally (tu/delete-tree! dir)))))

(deftest diagnostics-report-project-errors
  (let [dir (tu/temp-dir! "diags-proj")
        file (str dir "/sample.txt")
        other (str dir "/other.txt")
        other-uri (lsp/path->uri other)
        conn (conn-with-diags "fake" dir
                              {other-uri {:diagnostics [(diag 1 1 "bad")
                                                        (diag 1 2 "worse")]}})]
    (try
      (with-redefs [runtime/claiming-specs (fn [_ _] [{:id "fake" :root dir}])
                    runtime/all-conns (fn [_] [conn])]
        (let [{:keys [text sections]} (tools/diagnostics-report nil file)]
          (testing "errors in other files become a project summary"
            (is (str/includes? text "── project (files with errors) ──"))
            (is (str/includes? text "other.txt: 2 errors"))
            (is (= :project-errors (:kind (last sections)))))))
      (finally (tu/delete-tree! dir)))))

(deftest diagnostics-flood-is-suppressed
  (let [dir (tu/temp-dir! "diags-flood")
        file (str dir "/sample.txt")
        uri (lsp/path->uri file)
        conn (conn-with-diags "fake" dir
                              {uri {:diagnostics (mapv #(diag 1 % "x") (range 1 60))}})]
    (try
      (with-redefs [runtime/claiming-specs (fn [_ _] [{:id "fake" :root dir}])
                    runtime/all-conns (fn [_] [conn])]
        (let [{:keys [text sections]} (tools/diagnostics-report nil file)]
          (testing "a flood collapses to a single line, with no items"
            (is (str/includes? text "59 diagnostics (flood suppressed)"))
            (is (empty? (:items (first sections)))))))
      (finally (tu/delete-tree! dir)))))

(deftest per-file-cap
  (let [dir (tu/temp-dir! "diags-cap")
        file (str dir "/sample.txt")
        uri (lsp/path->uri file)
        conn (conn-with-diags "fake" dir
                              {uri {:diagnostics (mapv #(diag 1 % "x") (range 1 20))}})]
    (try
      (with-redefs [runtime/claiming-specs (fn [_ _] [{:id "fake" :root dir}])
                    runtime/all-conns (fn [_] [conn])]
        (let [{:keys [text]} (tools/diagnostics-report nil file)]
          (testing "only the first eight per file are shown"
            (is (= 8 (count (re-seq #"ERROR \[" text))))
            (is (str/includes? text "… and 11 more")))))
      (finally (tu/delete-tree! dir)))))

;; ─── Dispatch usage errors ────────────────────────────────────────────────

(deftest execute-usage-errors
  (let [st (tu/new-state {:servers {}})]
    (testing "the operation is required"
      (let [e (usage-error #(tools/execute st nil {}))]
        (is (= :kmet.extensions.lsp-adapter.tools/usage (:type (ex-data e))))
        (is (str/includes? (ex-message e) "operation is required"))
        (is (str/includes? (ex-message e) "usage: lsp("))))
    (testing "an unknown operation is named"
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "nope"})))
                         "unknown operation: nope")))
    (testing "filePath is required"
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "definition"})))
                         "filePath is required")))
    (testing "a position operation needs numeric 1-based coordinates"
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "definition"
                                                                   :filePath "/p/a.clj"})))
                         "requires numeric 1-based line and character"))
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "definition"
                                                                   :filePath "/p/a.clj"
                                                                   :line 1})))
                         "requires numeric 1-based line and character")))
    (testing "workspaceSymbol needs a query"
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "workspaceSymbol"
                                                                   :filePath "/p/a.clj"})))
                         "workspaceSymbol requires query")))
    (testing "a vanished file is reported"
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "hover"
                                                                   :filePath "/definitely/not/here.clj"
                                                                   :line 1 :character 1})))
                         "no such file")))))

(deftest execute-aliases-and-abort
  (let [st (tu/new-state {:servers {}})]
    (testing "the camelCase aliases resolve to their operation"
      ;; an alias reaching the position check proves the mapping (the file
      ;; never has to exist)
      (is (str/includes? (ex-message (usage-error #(tools/execute st nil
                                                                  {:operation "goToDefinition"
                                                                   :filePath "/p/a.clj"})))
                         "definition requires numeric 1-based line and character")))
    (testing "an aborted signal skips the round-trip"
      (is (= {:content "(aborted)"}
             (tools/execute st (atom true)
                            {:operation "hover" :filePath "/p/a.clj"
                             :line 1 :character 1}))))))

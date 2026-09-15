(ns kmet.extensions.tree-sitter.renderers-test
  "Headless tests for the tree-sitter tool renderers: collapsed summaries,
   expanded listings/bodies, fallback previews — ANSI-stripped like the
   built-in renderer tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.extensions.tree-sitter.render :as render]
            [kmet.tui.theme :as theme]))

(def ^:private th theme/dark-theme)

(defn- plain
  "Render a renderer component headlessly, ANSI-stripped."
  [comp width]
  (when comp
    (mapv #(clojure.string/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "")
          (core/render comp width))))

(deftest render-call-test
  (testing "file tools show symbol + path"
    (let [lines (plain (render/render-call "get_symbol_body"
                                           {:path "src/core.clj" :symbol "init"}
                                           th 60 {})
                       60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "get_symbol_body"))
      (is (str/includes? (first lines) "init"))
      (is (str/includes? (first lines) "src/core.clj"))))
  (testing "search tools show root when given"
    (let [lines (plain (render/render-call "find_callers"
                                           {:symbol "helper" :root "/home/u/proj"}
                                           th 60 {})
                       60)]
      (is (str/includes? (first lines) "find_callers"))
      (is (str/includes? (first lines) "helper"))
      (is (str/includes? (first lines) "in /home/u/proj"))))
  (testing "partial args are safe mid-stream"
    (is (= 1 (count (plain (render/render-call "list_symbols" {} th 40 {}) 40))))))

(def ^:private summary-details
  {:count 2 :label "symbols" :name "greet" :file-count 1})

(deftest summary-result-test
  (testing "collapsed hit summary: count + label + name + file count"
    (let [c (render/render-result "ignored" false th 60 false nil nil nil
                                  {:details summary-details :cwd "."})
          lines (plain c 60)]
      (is (some #(str/includes? % "✓ 2 symbols") lines))
      (is (some #(str/includes? % "for 'greet'") lines))
      (is (some #(str/includes? % "across 1 file ") lines))))
  (testing "zero hits dim line with queried name"
    (let [c (render/render-result "none" false th 60 false nil nil nil
                                  {:details {:count 0 :label "callers"
                                             :name "lonely"}})
          lines (plain c 60)]
      (is (some #(str/includes? % "No callers found") lines))
      (is (some #(str/includes? % "for 'lonely'") lines))))
  (testing "expanded shows the full listing"
    (let [content (str/join "\n" ["function a (line 1)" "function b (line 2)"])
          c (render/render-result content false th 60 true nil nil nil
                                  {:details (assoc summary-details :count 2)})
          lines (plain c 60)]
      (is (some #(str/includes? % "function a") lines))
      (is (some #(str/includes? % "function b") lines)))))

(deftest body-result-test
  (let [details {:name "double-it" :line-count 2
                 :path "src/core.clj"
                 :body "(defn double-it [x]\n  (* 2 x))"}]
    (testing "collapsed: one-line ✓ summary with line count + path"
      (let [lines (plain (render/render-result "ignored" false th 60 false
                                               nil nil nil {:details details})
                         60)]
        (is (some #(str/includes? % "✓ double-it (2 lines)") lines))
        (is (some #(str/includes? % "core.clj") lines))))
    (testing "expanded: highlighted body text is present (ansi stripped)"
      (let [lines (plain (render/render-result "ignored" false th 60 true
                                               nil nil nil {:details details})
                         80)]
        (is (some #(str/includes? % "double-it") lines))
        (is (some #(str/includes? % "* 2 x") lines))))))

(deftest fallback-result-test
  (testing "error results pass through in the preview"
    (let [c (render/render-result "tree-sitter: boom" true th 60 false
                                  nil nil nil {})
          lines (plain c 60)]
      (is (some #(str/includes? % "boom") lines))))
  (testing "no details (replayed session) falls back to plain preview"
    (let [c (render/render-result "plain text result" false th 60 true
                                  nil nil nil {})
          lines (plain c 60)]
      (is (some #(str/includes? % "plain text result") lines)))))

(deftest quiet-titles-test
  (testing "list_symbols"
    (is (= "list_symbols /a/b.clj" (render/title-list-symbols {:path "/a/b.clj"})))
    (is (nil? (render/title-list-symbols {})))
    (is (nil? (render/title-list-symbols nil))))
  (testing "find_definition — root shown only when non-default"
    (is (= "find_definition foo" (render/title-find-definition {:symbol "foo"})))
    (is (= "find_definition foo" (render/title-find-definition {:symbol "foo" :root "."})))
    (is (= "find_definition foo in /sub" (render/title-find-definition {:symbol "foo" :root "/sub"})))
    (is (= "find_definition foo" (render/title-find-definition {"symbol" "foo"})) "string keys")
    (is (nil? (render/title-find-definition {}))))
  (testing "get_symbol_body"
    (is (= "get_symbol_body foo in /a.clj"
           (render/title-get-symbol-body {:symbol "foo" :path "/a.clj"})))
    (is (nil? (render/title-get-symbol-body {:symbol "foo"}))))
  (testing "find_callers"
    (is (= "find_callers foo" (render/title-find-callers {:symbol "foo"})))
    (is (= "find_callers foo in /sub" (render/title-find-callers {:symbol "foo" :root "/sub"})))
    (is (nil? (render/title-find-callers {}))))
  (testing "find_callees"
    (is (= "find_callees f in /a.clj" (render/title-find-callees {:symbol "f" :path "/a.clj"})))
    (is (nil? (render/title-find-callees {:symbol "f"}))))
  (testing "home folded to ~"
    (let [home (System/getProperty "user.home" "")]
      (is (= "list_symbols ~/b.clj" (render/title-list-symbols {:path (str home "/b.clj")}))))))

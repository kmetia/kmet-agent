(ns kmet.extensions.lsp-adapter.render-test
  "The `lsp` tool's transcript renderers: the one-line call summary, and the
   structured result (one block per section kind) with the plain-preview
   fallback and the collapsed expand hint."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.theme :as theme]
            [kmet.tui.core :as core]
            [kmet.extensions.lsp-adapter.render :as render]))

(def ^:private t theme/dark-theme)

(defn- plain
  "Rendered lines with ANSI SGR and OSC-8 hyperlinks removed."
  [comp width]
  (mapv (fn [line]
          (-> (str line)
              (str/replace #"\x1b\]8;;[^\x1b]*\x1b\\\\?" "")
              (str/replace #"\x1b\[[0-9;]*m" "")))
        (core/render comp width)))

(defn- render-result
  [content is-error details & {:keys [expanded? width]
                               :or {expanded? true width 100}}]
  (plain (render/render-lsp-result content is-error t width expanded?
                                   nil nil nil
                                   {:details details :cwd "/proj"})
         width))

(defn- text [lines] (str/join "\n" lines))

;; ─── Call renderer ────────────────────────────────────────────────────────

(deftest render-call
  (testing "a position operation shows the path and 1-based coordinates"
    (let [out (text (plain (render/render-lsp-call
                            "lsp" {:operation "references" :filePath "/proj/src/core.clj"
                                   :line 235 :character 7}
                            t 120 {:cwd "/proj"})
                           120))]
      (is (str/includes? out "lsp references"))
      (is (str/includes? out "src/core.clj:235:7"))))
  (testing "a symbol query shows the quoted query"
    (let [out (text (plain (render/render-lsp-call
                            "lsp" {:operation "workspaceSymbol" :query "shape/area"}
                            t 120 {:cwd "/proj"})
                           120))]
      (is (str/includes? out "lsp workspaceSymbol"))
      (is (str/includes? out "\"shape/area\""))))
  (testing "partial args render what is there (mid-stream safety)"
    (let [out (text (plain (render/render-lsp-call "lsp" {} t 120 {:cwd "/proj"}) 120))]
      (is (str/includes? out "lsp ?"))))
  (testing "string keys are accepted like keyword keys"
    (is (str/includes? (text (plain (render/render-lsp-call
                                     "lsp" {"operation" "hover" "query" "x"}
                                     t 120 {:cwd "/proj"})
                                    120))
                       "\"x\""))))

(deftest title-lsp
  (is (= "lsp definition /proj/src/core.clj:12:3"
         (render/title-lsp {:operation "definition"
                            :filePath "/proj/src/core.clj" :line 12 :character 3})))
  (is (= "lsp workspaceSymbol \"q\""
         (render/title-lsp {:operation "workspaceSymbol" :query "q"})))
  (testing "no operation means no title"
    (is (nil? (render/title-lsp {})))
    (is (nil? (render/title-lsp {:operation ""})))))

;; ─── Result renderer ──────────────────────────────────────────────────────

(deftest result-locations
  (let [detail {:sections [{:server "clojure-lsp" :root "/proj" :kind :locations
                            :more 0
                            :items [{:path "/proj/src/core.clj" :line 12 :col 3}
                                    {:path "/proj/src/core.clj" :line 40 :col 1}]}]}
        out (text (render-result "(no reults)" false detail))]
    (testing "the section header names the server and its short root"
      (is (str/includes? out "── clojure-lsp ("))
      (is (str/includes? out "src/core.clj")))
    (testing "one path header groups its locations"
      (is (str/includes? out "12:3"))
      (is (str/includes? out "40:1")))))

(deftest result-symbols-and-hover
  (testing "symbols render a colored kind glyph per depth"
    (let [detail {:sections [{:server "s" :root "/proj" :kind :symbols
                              :items [{:depth 0 :name "ns" :kind "namespace"}
                                      {:depth 1 :name "f" :kind "fn"}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "ns"))
      (is (str/includes? out "f"))))
  (testing "hover strips fences and backticks, emphasizing the first line"
    (let [detail {:sections [{:server "s" :root "/proj" :kind :hover
                              :items [{:text "```clj\n(defn f [])\n```\n\n`x` docs"}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "(defn f [])"))
      (is (str/includes? out "x docs"))
      (is (not (str/includes? out "```"))))))

(deftest result-hierarchy-and-diagnostics
  (testing "hierarchy rows carry the site count"
    (let [detail {:sections [{:server "s" :root "/proj" :kind :hierarchy
                              :items [{:name "caller" :kind "fn"
                                       :path "/proj/c.clj" :line 10 :sites 2}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "caller"))
      (is (str/includes? out "(2 sites)"))))
  (testing "diagnostics rows carry severity, coordinates and source"
    (let [detail {:sections [{:label "fake @ ." :kind :diagnostics
                              :items [{:severity 1 :line 4 :col 2
                                       :message "boom" :source "fake"}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "── fake @ . ──"))
      (is (str/includes? out "[4:2]"))
      (is (str/includes? out "boom"))
      (is (str/includes? out "(fake)"))))
  (testing "project errors summarize per file"
    (let [detail {:sections [{:kind :project-errors
                              :items [{:path "/proj/a.clj" :errors 2}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "project (files with errors)"))
      (is (str/includes? out "a.clj"))
      (is (str/includes? out "2 errors"))))
  (testing "server errors render as one line each"
    (let [detail {:sections [{:kind :error :items [{:name "s" :message "not installed"}]}]}
          out (text (render-result "x" false detail))]
      (is (str/includes? out "s: not installed")))))

(deftest result-collapsed-shows-the-expand-hint
  (let [items (mapv #(hash-map :path "/proj/a.clj" :line (inc %) :col 1) (range 20))
        detail {:sections [{:server "s" :root "/proj" :kind :locations :items items}]}
        collapsed (render-result "x" false detail :expanded? false)]
    (testing "only the preview window is shown, with a hint"
      (is (< (count collapsed) (inc 20)))
      (is (str/includes? (text collapsed) "more lines")))
    (testing "expanded shows every row and no hint"
      (let [expanded (render-result "x" false detail)]
        (is (not (str/includes? (text expanded) "more lines")))
        (is (>= (count expanded) 20))))))

(deftest result-fallback-without-details
  (testing "without :details the renderer degrades to a plain preview"
    (let [content (str/join "\n" (map #(str "line-" %) (range 12)))
          lines (render-result content false nil :expanded? false)]
      (is (str/includes? (text lines) "line-0"))
      (is (not (str/includes? (text lines) "line-11")))
      (is (str/includes? (text lines) "more lines"))))
  (testing "expanded fallback shows everything"
    (let [content (str/join "\n" (map #(str "line-" %) (range 12)))
          lines (render-result content false nil :expanded? true)]
      (is (str/includes? (text lines) "line-11"))
      (is (not (str/includes? (text lines) "more lines")))))
  (testing "an error result falls back even with sections present"
    (let [detail {:sections [{:server "s" :root "/proj" :kind :locations
                              :items [{:path "/proj/a.clj" :line 1 :col 1}]}]}
          lines (render-result "MCP call failed: boom" true detail)]
      (is (str/includes? (text lines) "MCP call failed: boom"))))
  (testing "the no-results marker is rendered muted"
    (let [detail {:sections [{:server "s" :root "/proj" :kind :locations :items []}]}
          lines (render-result "(no results)" false detail)]
      (is (str/includes? (text lines) "(no results)")))))

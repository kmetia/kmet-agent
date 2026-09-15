(ns kmet.app.ui.test-tool-renderers
  "Headless tests for the hiccup-compiled built-in tool renderers: the
   element-tree conversions must produce the same visible output the
   imperative builders did (line caps, expand hints, truncation warns,
   error text)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]
            [kmet.app.ui.tool-renderers :as r]
            [kmet.tui.theme :as theme]))

(def ^:private th theme/dark-theme)

(defn- plain
  "Render a renderer result headlessly, ANSI-stripped."
  [comp width]
  (when comp
    (mapv utils/strip-ansi-codes
          (core/render comp width))))

(deftest test-bash-call
  (testing "short command renders as one line, timeout suffix intact"
    (let [lines (plain (r/render-bash-call "bash" {:command "ls -la" :timeout 60}
                                           th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "$ ls -la (timeout 60s)"))))
  (testing "collapsed caps a multiline command at the head + hint"
    (let [cmd (str "python3 - <<'EOF'\n"
                   (str/join "\n" (mapv #(str "body " %) (range 10)))
                   "\nEOF")
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded false}) 60)]
      (is (< (count lines) 10) "the payload does not render in full")
      (is (str/starts-with? (first lines) "$ python3 - <<'EOF'"))
      (is (str/includes? (peek lines) "more lines,"))
      (is (not-any? #(str/includes? % "body 9") lines) "tail is cut")))
  (testing "collapsed caps a long single-line command at the wrapped head"
    (let [cmd (str "echo " (str/join " " (repeat 60 "word")))
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded false}) 60)]
      (is (= 4 (count lines)) "3 visual lines + hint")
      (is (str/includes? (peek lines) "more lines,"))))
  (testing "expanded renders the command verbatim (pi parity)"
    (let [cmd (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "missing command keeps the `$ ...` placeholder"
    (let [lines (plain (r/render-bash-call "bash" {} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "$ ...")))))

(deftest test-read-call
  (testing "full call shows name + path"
    (let [lines (plain (r/render-read-call "read" {:file_path "a/b.txt"} th 40 {}) 40)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "read a/b.txt"))))
  (testing "compact classification when not expanded"
    (let [comp (r/render-read-call "read" {:file_path "/home/u/proj/SKILL.md"} th 40
                                   {:cwd "/home/u" :expanded false})
          line (first (plain comp 40))]
      (is (str/includes? line "proj") "compact skill label shows parent dir")
      (is (not (str/includes? line "read ")) "no full call form"))))

(deftest test-read-result-line-cap-and-hint
  (testing "collapsed without error renders nothing (call line is the summary)"
    (is (nil? (r/render-read-result "x" false th 60 false nil nil nil {}))))
  (testing "expanded shows every line, no hint"
    (let [content (str/join "\n" (mapv #(str "line-" %) (range 30)))
          lines (plain (r/render-read-result content false th 60 true nil nil nil {}) 60)]
      (is (= 31 (count lines)) "spacer + 30 lines")
      (is (str/starts-with? (second lines) "line-0"))
      (is (not-any? #(str/includes? % "more lines") lines))))
  (testing "error + collapsed caps at 10 lines with hint"
    (let [content (str/join "\n" (mapv #(str "line-" %) (range 30)))
          lines (plain (r/render-read-result content true th 60 false nil nil nil {}) 60)]
      (is (= 12 (count lines)) "spacer + 10 lines + more-hint")
      (is (str/starts-with? (second lines) "line-0"))
      (is (str/includes? (peek lines) "... (20 more lines,"))))
  (testing "truncation warn renders on the visible path"
    (let [content (str/join "\n" (mapv #(str "l" %) (range 5)))
          truncation {:truncated-by :lines :output-lines 5 :total-lines 99 :max-lines 5}
          lines (plain (r/render-read-result content true th 60 false nil nil truncation {}) 60)]
      (is (some #(str/includes? % "[Truncated: showing 5 of 99") lines)))))

(deftest test-read-result-highlight
  (testing "expanded read highlights by path language (pi: highlightCode)"
    (let [content "(defn foo [x] (+ x 1))"
          render (fn [path is-error ctx-extra]
                   (-> (r/render-read-result content is-error th 60 true
                                             nil nil nil
                                             (merge {:args {:path path}} ctx-extra))
                       (core/render 60)
                       (->> (apply str))))
          gray-esc (theme/get-fg-ansi th :tool-output)
          kw-esc (theme/get-fg-ansi th :syntax-keyword)]
      (testing "known language uses syntax colors, not toolOutput gray"
        (let [s (render "a.clj" false {})]
          (is (str/includes? s kw-esc) ".clj output is syntax-highlighted")
          (is (not (str/includes? s gray-esc)) ".clj output skips toolOutput gray")))
      (testing "extension case does not matter (canonical language key)"
        (let [s (render "a.CLJ" false {})]
          (is (str/includes? s kw-esc) ".CLJ output is syntax-highlighted")))
      (testing "unknown language falls back to toolOutput gray"
        (let [s (render "a.txt" false {})]
          (is (str/includes? s gray-esc) ".txt output is toolOutput gray")))
      (testing "errors fall back to toolOutput even with a known language"
        (let [s (render "a.clj" true {})]
          (is (str/includes? s gray-esc) "error output is toolOutput gray")
          (is (not (str/includes? s kw-esc)) "error output is not highlighted")))
      (testing "missing path falls back to toolOutput (backward compat)"
        (let [s (-> (r/render-read-result content false th 60 true nil nil nil {})
                    (core/render 60)
                    (->> (apply str)))]
          (is (str/includes? s gray-esc)))))))

(deftest test-write-call-and-result
  (testing "invalid content arg surfaces error text"
    (let [lines (plain (r/render-write-call "write" {:file_path "f" :content :bad} th 40 {}) 40)]
      (is (some #(str/includes? % "[invalid content arg") lines))))
  (testing "content preview capped at 10 + hint"
    (let [content (str/join "\n" (mapv #(str "w-" %) (range 25)))
          lines (plain (r/render-write-call "write" {:file_path "f" :content content} th 60 {}) 60)]
      (is (= 14 (count lines)) "title + 2 spacers + 10 lines + hint")
      (is (str/includes? (peek lines) "... (15 more lines,"))))
  (testing "error result renders content"
    (let [lines (plain (r/render-write-result "disk full" true th 40 false) 40)]
      (is (some #(str/includes? % "disk full") lines)))
    (is (nil? (r/render-write-result "ok" false th 40 false))))
  (testing "known-language paths highlight; unknown use toolOutput color"
    (let [content "defn foo [x]\n  (println \"hi\")"
          render (fn [p] (-> (r/render-write-call "write" {:file_path p :content content} th 60 {:expanded true})
                             (core/render 60)
                             ;; drop title + 2 spacers, keep content lines only
                             (subvec 3)))
          clj-lines (render "a.clj")
          txt-lines (render "a.txt")
          gray-esc (theme/get-fg-ansi th :tool-output)]
      (is (some #(str/includes? % gray-esc) txt-lines) ".txt content is toolOutput gray")
      (is (not-any? #(str/includes? % gray-esc) clj-lines) ".clj content uses syntax colors, not toolOutput"))))

(deftest test-default-renderers
  (testing "default call joins args, truncated to width"
    (let [lines (plain (r/render-default-call "grep" {:pattern "x" :path "y"} th 30 {}) 30)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "grep"))))
  (testing "default result previews 5 lines with expand hint"
    (let [content (str/join "\n" (mapv #(str "d-" %) (range 9)))
          lines (plain (r/render-default-result content false th 60 false) 60)]
      (is (= 7 (count lines)) "spacer + 5 lines + hint")
      (is (str/includes? (peek lines) "... (4 more lines,")))
    (testing "expanded shows everything"
      (let [content (str/join "\n" (mapv #(str "d-" %) (range 9)))
            lines (plain (r/render-default-result content false th 60 true) 60)]
        (is (= 10 (count lines)))))))

(deftest test-edit-box-bg-states
  ;; build-edit-box is private; exercise via render-edit-call with a
  ;; complete-args context that skips preview (unrenderable path → pending bg)
  (let [comp (r/render-edit-call "edit" {"file_path" "f.txt"} th 60
                                 {:cwd "." :args-complete false :state {}
                                  :set-state! (fn [_])})
        raw (core/render comp 60)
        ansi (first raw)]
    (testing "pending bg while nothing rendered yet"
      (is (re-find #"\u001b\[48;" (or ansi "")) "box paints a background"))))

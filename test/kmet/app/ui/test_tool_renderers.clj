(ns kmet.app.ui.test-tool-renderers
  "Headless tests for the hiccup-compiled built-in tool renderers: the
   element-tree conversions must produce the same visible output the
   imperative builders did (line caps, expand hints, truncation warns,
   error text)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
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

(deftest test-bash-result
  (testing "collapsed keeps the tail of the output, capped, with the expand hint"
    (let [content (str/join "\n" (mapv #(str "r-" %) (range 12)))
          lines (plain (r/render-bash-result content false th 60 false 1000 2000 nil {}) 60)]
      (is (= 9 (count lines)) "spacer + hint + 5 lines + spacer + took")
      (is (str/starts-with? (second lines) "... (7 earlier lines,"))
      (is (str/starts-with? (nth lines 2) "r-7") "the tail is what is kept")
      (is (str/includes? (peek lines) "Took 1.0s"))))
  (testing "expanded renders every line, no hint"
    (let [content (str/join "\n" (mapv #(str "r-" %) (range 7)))
          lines (plain (r/render-bash-result content false th 60 true 1000 2000 nil {}) 60)]
      (is (= 10 (count lines)) "spacer + 7 lines + spacer + took")
      (is (not-any? #(str/includes? % "earlier lines") lines))))
  (testing "no output and no timing renders an empty body"
    (is (= [] (plain (r/render-bash-result "" false th 60 false nil nil nil {}) 60)))
    (is (= [] (plain (r/render-bash-result nil false th 60 false nil nil nil {}) 60))))
  (testing "truncation warns, and the runtime's own footer is stripped first"
    (let [trunc {:truncated-by :lines :shown-lines 2 :total-lines 99
                 :full-output-path "/tmp/full.txt"}
          content "a\nb\n\n[Full output: /tmp/full.txt. Truncated: showing 2 of 99 lines]"
          lines (plain (r/render-bash-result content false th 100 true 1000 2000 trunc {}) 100)
          warn "[Full output: /tmp/full.txt. Truncated: showing 2 of 99 lines]"]
      (is (= ["a" "b"] (mapv str/trim (subvec lines 1 3)))
          "the footer copy inside the body is gone — the warn line is rebuilt")
      (is (some #(= warn (str/trimr %)) lines) "it is rebuilt from the truncation data")
      (is (= 1 (count (filter #(str/includes? % warn) lines))) "…and renders once")))
  (testing "the elapsed label flips to Took once the execution ended"
    (let [partial (plain (r/render-bash-result "x" false th 60 true 1000 nil nil {}) 60)
          done (plain (r/render-bash-result "x" false th 60 true 1000 3000 nil {}) 60)]
      (is (some #(str/includes? % "Elapsed ") partial))
      (is (some #(str/includes? % "Took 2.0s") done)))))

(deftest test-edit-result-error-leg
  (testing "error content renders indented, one column in (pi: new Text(content, 1, 0))"
    (let [lines (plain (r/render-edit-result "tool blew up" true th 60 false nil nil nil {}) 60)]
      (is (= 2 (count lines)))
      (is (= "" (first lines)))
      (is (= " tool blew up" (str/trimr (second lines))))))
  (testing "an error the preview already showed is suppressed (pi dedup)"
    (is (nil? (r/render-edit-result "same" true th 60 false nil nil nil
                                    {:state {:edit-preview {:error "same"}}}))))
  (testing "success renders nothing"
    (is (nil? (r/render-edit-result "ok" false th 60 false nil nil nil {})))))

(deftest test-edit-result-preview-state
  (let [render (fn [state details]
                 (let [st (atom state)]
                   (r/render-edit-result "ok" false th 60 false nil nil nil
                                         {:state @st :details details
                                          :set-state! (fn [s] (reset! st s))})
                   @st))]
    (testing "a result diff correcting the preview replaces the cached one"
      (is (= {:edit-preview {:success? true :diff "a\nb" :diff-lines ["a" "b"]}}
             (render {} {:diff "a\nb"}))))
    (testing "a matching diff leaves state alone"
      (is (= {:edit-preview {:success? true :diff "d"}}
             (render {:edit-preview {:success? true :diff "d"}} {:diff "d"}))))
    (testing "a failed preview is cleared on a successful result"
      (is (= {} (render {:edit-preview {:success? false :error "e"}} nil))))))

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

(deftest test-edit-preview-follows-the-runtime-cwd
  (testing "a relative edit path previews the file in the render context's
            cwd — the edit tool resolves there (resolve-tool-path), so the
            preview must too, or a switched session previews the launch
            directory's file (or reports File not found)"
    (let [dir (str (fs/absolutize (str "target/test-edit-preview-" (System/currentTimeMillis))))]
      (try
        (fs/create-dirs dir)
        (spit (str dir "/note.txt") "one\n")
        (let [comp (r/render-edit-call "edit"
                                       {:path "note.txt"
                                        :edits [{:oldText "one" :newText "two"}]}
                                       th 60
                                       {:cwd dir :args-complete true :state {}
                                        :set-state! (fn [_])})
              lines (plain comp 60)]
          (is (some #(str/includes? % "two") lines)
              "the diff is computed from the cwd's file")
          (is (not-any? #(str/includes? % "File not found") lines)
              "no spurious not-found from the process cwd"))
        (finally (fs/delete-tree dir))))))

(deftest test-edit-call-prefers-the-recorded-result-diff
  (testing "a call already carrying the result's :details diff renders that
            diff on the first pass: no filesystem preview computation, and
            render-edit-result agrees with the cached preview (no correction
            frame). Replayed sessions hit this — the filesystem preview would
            read today's file and settle one frame later, marking the
            scrollback dirty"
    (let [dir (str (fs/absolutize (str "target/test-edit-recorded-diff-"
                                       (System/currentTimeMillis))))]
      (try
        (fs/create-dirs dir)
        ;; the current file would preview a lowercase "two"
        (spit (str dir "/note.txt") "one\n")
        (let [st (atom {})
              invalidated (atom 0)
              context {:cwd dir :args-complete true
                       :details {:diff "-1 one\n+1 TWO"}
                       :state @st
                       :set-state! (fn [s] (reset! st s))
                       :invalidate (fn [] (swap! invalidated inc))}
              lines (plain (r/render-edit-call "edit"
                                               {:path "note.txt"
                                                :edits [{:oldText "one" :newText "two"}]}
                                               th 60 context) 60)
              recorded {:success? true :diff "-1 one\n+1 TWO"
                        :diff-lines ["-1 one" "+1 TWO"]}]
          (is (some #(str/includes? % "TWO") lines)
              "the recorded result diff renders")
          (is (not-any? #(str/includes? % "two") lines)
              "the filesystem preview is not used")
          (is (= recorded (:edit-preview @st))
              "the recorded diff is cached as the preview")
          (r/render-edit-result "ok" false th 60 false nil nil nil (assoc context :state @st))
          (is (= recorded (:edit-preview @st)) "no correction rewrites state")
          (is (zero? @invalidated)
              "the result agrees with the recorded preview — no settle frame"))
        (finally (fs/delete-tree dir))))))

(deftest test-edit-call-skips-the-preview-for-an-errored-result
  (testing "a finished errored edit (no recorded diff) renders without the
            filesystem preview: it could only re-derive the failure, and a
            replayed call would read a file from today's worktree. The error
            then renders on the result side (no preview error to dedup)"
    (let [dir (str (fs/absolutize (str "target/test-edit-errored-preview-"
                                       (System/currentTimeMillis))))]
      (try
        (fs/create-dirs dir)
        ;; today's file would preview a lowercase "two"
        (spit (str dir "/note.txt") "one\n")
        (let [st (atom {})
              context {:cwd dir :args-complete true :is-partial false
                       :is-error true
                       :state {}
                       :set-state! (fn [s] (reset! st s))}
              lines (plain (r/render-edit-call "edit"
                                               {:path "note.txt"
                                                :edits [{:oldText "one" :newText "two"}]}
                                               th 60 context) 60)]
          (is (nil? (:edit-preview @st))
              "no filesystem preview is computed or cached")
          (is (not-any? #(str/includes? % "two") lines)
              "the file's would-be diff never reaches the render")
          (let [err-lines (plain (r/render-edit-result
                                  "Could not find the exact text in note.txt."
                                  true th 60 false nil nil nil
                                  (assoc context :state @st)) 60)]
            (is (some #(str/includes? % "Could not find") err-lines)
                "the recorded error renders on the result side")
            (is (nil? (:edit-preview @st)) "and no preview state is written")))
        (finally (fs/delete-tree dir))))))

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

(deftest test-script-call
  (testing "short code renders as one line"
    (let [lines (plain (r/render-script-call "script" {:code "(+ 1 2)"} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "script (+ 1 2)"))))
  (testing "an explicit timeout renders as a suffix"
    (let [lines (plain (r/render-script-call "script" {:code "1" :timeout 5} th 60 {}) 60)]
      (is (str/includes? (first lines) "(5s)"))))
  (testing "collapsed caps a multiline script at the head + hint"
    (let [code (str/join "\n" (mapv #(str "(println " % ")") (range 20)))
          lines (plain (r/render-script-call "script" {:code code} th 60 {:expanded false}) 60)]
      (is (< (count lines) 20) "the payload does not render in full")
      (is (str/starts-with? (first lines) "script (println 0)"))
      (is (str/includes? (peek lines) "more lines,"))))
  (testing "expanded renders the script verbatim"
    (let [code (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (r/render-script-call "script" {:code code} th 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "missing code keeps the `script ...` placeholder"
    (let [lines (plain (r/render-script-call "script" {} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "script ...")))))

(deftest test-script-result
  (testing "output preview, inner-call summary and Took all render"
    (let [context {:details {:calls [{:tool "read" :ok true}
                                     {:tool "bash" :ok true}
                                     {:tool "bash" :ok false}]}}
          lines (plain (r/render-script-result "a\nb" false th 60 true 1000 2000 nil context) 60)]
      (is (some #(= "a" (str/trim %)) lines))
      (is (= "3 tool calls: bash ×2, read, 1 failed"
             (some #(when (str/includes? % "tool call") (str/trim %)) lines)))
      (is (str/includes? (peek lines) "Took 1.0s"))))
  (testing "no inner calls → no summary line"
    (let [lines (plain (r/render-script-result "x" false th 60 true 1000 2000 nil {}) 60)]
      (is (not-any? #(str/includes? % "tool call") lines))
      (is (str/includes? (peek lines) "Took 1.0s"))))
  (testing "truncation warns like bash"
    (let [trunc {:truncated-by :lines :shown-lines 2 :total-lines 99}
          lines (plain (r/render-script-result "a\nb" false th 80 true 1000 2000 trunc {}) 80)]
      (is (some #(str/includes? % "Truncated: showing 2 of 99 lines") lines))))
  (testing "the script's own truncation notice is stripped from the body"
    (let [trunc {:truncated-by :lines :shown-lines 2 :total-lines 99}
          content (str "a\nb\n\n[Script output truncated: 99 lines / 1.0 KiB total, "
                       "showing the last 2 lines. Print less or return distilled data instead.]")
          lines (plain (r/render-script-result content false th 100 true 1000 2000 trunc {}) 100)]
      (is (not-any? #(str/includes? % "Print less") lines)
          "the model-facing notice does not render twice")
      (is (some #(str/includes? % "Truncated: showing 2 of 99 lines") lines))))
  (testing "the measured :elapsed-ms wins over the component timestamp span"
    (let [context {:details {:elapsed-ms 3400}}
          lines (plain (r/render-script-result "a" false th 60 true 1000 2000 nil context) 60)]
      (is (str/includes? (peek lines) "Took 3.4s")
          "the tool's own measurement, not the 1.0s start/end span")))
  (testing "a replayed result (no timestamps) still reports the measured time"
    (let [context {:details {:elapsed-ms 3400}}
          lines (plain (r/render-script-result "a" false th 60 true nil nil nil context) 60)]
      (is (str/includes? (peek lines) "Took 3.4s")))))

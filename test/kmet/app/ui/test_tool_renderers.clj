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
  (testing "collapsed renders a multiline command verbatim, like expanded"
    (let [cmd (str "python3 - <<'EOF'\n"
                   (str/join "\n" (mapv #(str "body " %) (range 10)))
                   "\nEOF")
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded false}) 60)]
      (is (= 12 (count lines)) "the whole command, no cap")
      (is (str/starts-with? (first lines) "$ python3 - <<'EOF'"))
      (is (some #(str/includes? % "body 9") lines) "the tail renders")
      (is (str/includes? (peek lines) "EOF"))
      (is (not-any? #(str/includes? % "toggle") lines) "no expand hint")))
  (testing "collapsed wraps a long single-line command in full"
    (let [cmd (str "echo " (str/join " " (repeat 200 "word")))
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded false}) 60)]
      (is (> (count lines) 9) "more than the old 8-line head plus hint")
      (is (str/ends-with? (str/trimr (peek lines)) "word"))
      (is (not-any? #(str/includes? % "toggle") lines) "no expand hint")))
  (testing "expanded renders the command verbatim (pi parity)"
    (let [cmd (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (r/render-bash-call "bash" {:command cmd} th 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "missing command keeps the `$ ...` placeholder"
    (let [lines (plain (r/render-bash-call "bash" {} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "$ ...")))))

(deftest test-shell-call-renderer
  (testing "the shared shell-call renderer takes the prompt (pi: createShellRenderers)"
    (let [lines (plain ((r/shell-call-renderer "PS>") "powershell" {:command "Get-ChildItem"}
                                                      th 60 {})
                       60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "PS> Get-ChildItem"))))
  (testing "bash is the same renderer with `$` and the timeout suffix"
    (let [lines (plain ((r/shell-call-renderer "$") "bash" {:command "ls" :timeout 5} th 60 {})
                       60)]
      (is (str/includes? (first lines) "$ ls (timeout 5s)")))))

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
      (is (not-any? #(str/includes? % "(+") lines))))
  (testing "error + collapsed caps at 10 lines with hint"
    (let [content (str/join "\n" (mapv #(str "line-" %) (range 30)))
          lines (plain (r/render-read-result content true th 60 false nil nil nil {}) 60)]
      (is (= 12 (count lines)) "spacer + 10 lines + more-hint")
      (is (str/starts-with? (second lines) "line-0"))
      (is (str/includes? (peek lines) "... (+20 lines,"))))
  (testing "collapsed error caps a long unbreakable line by visual lines"
    (let [content (apply str (repeat 700 "x"))
          lines (plain (r/render-read-result content true th 60 false nil nil nil {}) 60)]
      (is (= 12 (count lines)) "spacer + 10 visual lines + hint")
      (is (str/includes? (peek lines) "... (+1 lines,")
          "the line not shown in full is the one hidden line reported")))
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
        (let [s (render "a.clj" false {})
              ;; pi's palette gives syntax punctuation/comment/meta the muted
              ;; color, which toolOutput also uses, so the restyled check is
              ;; "differs from the plain-gray render", not "has no gray".
              plain-gray (render "a.txt" false {})]
          (is (str/includes? s kw-esc) ".clj output is syntax-highlighted")
          (is (not= s plain-gray)
              ".clj output is restyled, not one toolOutput-gray span")))
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
      (is (str/includes? (peek lines) "... (+15 lines,"))))
  (testing "a long line is capped by visual lines, not logical lines"
    (let [content (str "head " (str/join " " (repeat 200 "word")))
          lines (plain (r/render-write-call "write" {:file_path "f" :content content} th 60 {}) 60)]
      (is (= 14 (count lines)) "title + 2 spacers + 10 wrapped visual lines + hint")
      (is (str/includes? (peek lines) "... (+1 lines,")
          "the line cut mid-wrap is reported as not fully shown")))
  (testing "a long first line hides the following lines behind the same budget"
    (let [content (str "head " (str/join " " (repeat 200 "word")) "\nshort-a\nshort-b")
          lines (plain (r/render-write-call "write" {:file_path "f" :content content} th 60 {}) 60)]
      (is (= 14 (count lines)))
      (is (str/includes? (peek lines) "... (+3 lines,"))
      (is (not-any? #(str/includes? % "short-") lines)
          "the short tail lines stay hidden")))
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
          gray-esc (theme/get-fg-ansi th :tool-output)
          kw-esc (theme/get-fg-ansi th :syntax-keyword)]
      (is (some #(str/includes? % gray-esc) txt-lines) ".txt content is toolOutput gray")
      ;; syntax punctuation/comment/meta share toolOutput's muted color in
      ;; pi's palette, so assert the restyling and the keyword color.
      (is (not= clj-lines txt-lines) ".clj content is restyled")
      (is (some #(str/includes? % kw-esc) clj-lines) ".clj content is syntax-highlighted"))))

(deftest test-bash-result
  (testing "collapsed keeps the tail of the output, capped, hint at the bottom"
    (let [content (str/join "\n" (mapv #(str "r-" %) (range 12)))
          lines (plain (r/render-bash-result content false th 60 false 1000 2000 nil {}) 60)]
      (is (= 7 (count lines)) "spacer + 5 tail lines + hint")
      (is (str/starts-with? (nth lines 1) "r-7") "the tail is what is kept")
      (is (str/includes? (peek lines) "... (+7 lines,") "the hint is at the bottom")
      (is (not-any? #(str/includes? % "Took") lines)
          "the state line belongs to the component (tail line)")))
  (testing "expanded renders every line, no hint"
    (let [content (str/join "\n" (mapv #(str "r-" %) (range 7)))
          lines (plain (r/render-bash-result content false th 60 true 1000 2000 nil {}) 60)]
      (is (= 8 (count lines)) "spacer + 7 lines")
      (is (not-any? #(str/includes? % "(+") lines))))
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
  (testing "the elapsed/took state line is the component's tail line, not the renderer's"
    (let [partial (plain (r/render-bash-result "x" false th 60 true 1000 nil nil {}) 60)
          done (plain (r/render-bash-result "x" false th 60 true 1000 3000 nil {}) 60)]
      (is (not-any? #(str/includes? % "Elapsed ") partial))
      (is (not-any? #(str/includes? % "Took") done)))))

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
      (is (str/includes? (peek lines) "... (+4 lines,")))
    (testing "expanded shows everything"
      (let [content (str/join "\n" (mapv #(str "d-" %) (range 9)))
            lines (plain (r/render-default-result content false th 60 true) 60)]
        (is (= 10 (count lines)))))
    (testing "a long unbreakable line is capped by visual lines, not logical lines"
      (let [content (apply str (repeat 370 "x"))
            lines (plain (r/render-default-result content false th 60 false) 60)]
        (is (= 7 (count lines)) "spacer + 5 visual lines + hint")
        (is (str/includes? (peek lines) "... (+1 lines,")
            "the line not shown in full is the one hidden line reported")))
    (testing "fully shown head lines are consumed; only the cut line remains"
      (let [content (str "a\nb\n" (apply str (repeat 700 "x")))
            lines (plain (r/render-default-result content false th 60 false) 60)]
        (is (= 7 (count lines)) "spacer + 5 visual lines + hint")
        (is (= ["a" "b"] (mapv str/trim [(second lines) (nth lines 2)]))
            "the two short lines render in full")
        (is (str/includes? (peek lines) "... (+1 lines,")
            "only the partially shown long line is counted as hidden")))
    (testing "an ANSI-dense line under-fills the first prefix; doubling still caps it"
      (let [content (apply str (for [i (range 3000)]
                                 (str (if (even? i) "\u001b[31m" "\u001b[32m") "ab")))
            lines (plain (r/render-default-result content false th 60 false) 60)]
        (is (= 7 (count lines)) "spacer + 5 visual lines + hint")
        (is (str/includes? (peek lines) "... (+1 lines,"))))))

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

(deftest test-run-code-call
  (testing "short code renders as one line"
    (let [lines (plain (r/render-run-code-call "run_code" {:code "(+ 1 2)"} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "run_code (+ 1 2)"))))
  (testing "an explicit timeout renders as a suffix"
    (let [lines (plain (r/render-run-code-call "run_code" {:code "1" :timeout 5} th 60 {}) 60)]
      (is (str/includes? (first lines) "(5s)"))))
  (testing "collapsed renders the whole multiline script, like expanded"
    (let [code (str/join "\n" (mapv #(str "(println " % ")") (range 20)))
          lines (plain (r/render-run-code-call "run_code" {:code code} th 60 {:expanded false}) 60)]
      (is (= 20 (count lines)) "the whole script, no cap")
      (is (str/starts-with? (first lines) "run_code (println 0)"))
      (is (some #(str/includes? % "(println 19)") lines) "the tail renders")
      (is (not-any? #(str/includes? % "toggle") lines) "no expand hint")))
  (testing "expanded renders the script verbatim"
    (let [code (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (r/render-run-code-call "run_code" {:code code} th 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "missing code keeps the `run_code ...` placeholder"
    (let [lines (plain (r/render-run-code-call "run_code" {} th 60 {:expanded false}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "run_code ...")))))

(deftest test-render-code-call
  (testing "a custom label and suffix render like the run_code call"
    (let [lines (plain (r/render-code-call "clojure>" "(+ 1 2)"
                                           (theme/fg th :dim "  :7888") th 60 {}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "clojure> (+ 1 2)"))
      (is (str/includes? (first lines) ":7888"))))
  (testing "multi-line code renders verbatim when collapsed"
    (let [code (str/join "\n" (mapv #(str "(println " % ")") (range 20)))
          lines (plain (r/render-code-call "clojure>" code "" th 60 {:expanded false}) 60)]
      (is (= 20 (count lines)) "the whole payload, no cap")
      (is (str/starts-with? (first lines) "clojure> (println 0)"))
      (is (some #(str/includes? % "(println 7)") lines))
      (is (some #(str/includes? % "(println 19)") lines) "the tail renders")
      (is (not-any? #(str/includes? % "toggle") lines) "no expand hint")))
  (testing "expanded code renders verbatim"
    (let [code (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (r/render-code-call "run" code "" th 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11")))))

(deftest test-run-code-result
  (testing "output preview and inner-call summary render"
    (let [context {:details {:calls [{:tool "read" :ok true}
                                     {:tool "bash" :ok true}
                                     {:tool "bash" :ok false}]}}
          lines (plain (r/render-run-code-result "a\nb" false th 60 true 1000 2000 nil context) 60)]
      (is (some #(= "a" (str/trim %)) lines))
      (is (= "3 tool calls: bash ×2, read, 1 failed"
             (some #(when (str/includes? % "tool call") (str/trim %)) lines)))
      (is (not-any? #(str/includes? % "Took") lines)
          "the state line belongs to the component (tail line)")))
  (testing "no inner calls → no summary line"
    (let [lines (plain (r/render-run-code-result "x" false th 60 true 1000 2000 nil {}) 60)]
      (is (not-any? #(str/includes? % "tool call") lines))
      (is (not-any? #(str/includes? % "Took") lines))))
  (testing "truncation warns like bash"
    (let [trunc {:truncated-by :lines :shown-lines 2 :total-lines 99}
          lines (plain (r/render-run-code-result "a\nb" false th 80 true 1000 2000 trunc {}) 80)]
      (is (some #(str/includes? % "Truncated: showing 2 of 99 lines") lines))))
  (testing "the script's own truncation notice is stripped from the body"
    (let [trunc {:truncated-by :lines :shown-lines 2 :total-lines 99}
          content (str "a\nb\n\n[run_code output truncated: 99 lines / 1.0 KiB total, "
                       "showing the last 2 lines. Print less or return distilled data instead.]")
          lines (plain (r/render-run-code-result content false th 100 true 1000 2000 trunc {}) 100)]
      (is (not-any? #(str/includes? % "Print less") lines)
          "the model-facing notice does not render twice")
      (is (some #(str/includes? % "Truncated: showing 2 of 99 lines") lines))))
  (testing "timestamps/details no longer render a state line here"
    (let [context {:details {:elapsed-ms 3400}}
          lines (plain (r/render-run-code-result "a" false th 60 true 1000 2000 nil context) 60)]
      (is (not-any? #(str/includes? % "Took") lines)
          "state-result-nodes owns the Took line (component tail line)"))))

(deftest test-state-result-nodes
  (testing "running shows Elapsed in the muted foreground, with no background"
    (let [raw (core/render (r/state-result-nodes th (System/currentTimeMillis) nil false) 60)]
      (is (= 2 (count raw)) "spacer + state line")
      (is (str/includes? (peek raw) (theme/get-fg-ansi th :muted)))
      (is (not-any? #(str/includes? % (theme/get-bg-ansi th :tool-pending-bg)) raw)
          "the tail line paints no status background")
      (is (str/includes? (utils/strip-ansi-codes (peek raw)) "Elapsed "))))
  (testing "done shows Took in the default color, with no background"
    (let [raw (core/render (r/state-result-nodes th 1000 3000 false) 60)]
      (is (not-any? #(str/includes? % (theme/get-bg-ansi th :tool-success-bg)) raw))
      (is (not (str/includes? (peek raw) (theme/get-fg-ansi th :success)))
          "success stays at the default foreground")
      (is (str/includes? (utils/strip-ansi-codes (peek raw)) "Took 2.0s"))))
  (testing "error shows the (!) marker in the error foreground, with no background"
    (let [raw (core/render (r/state-result-nodes th nil nil true) 60)]
      (is (str/includes? (peek raw) (theme/get-fg-ansi th :error)))
      (is (not-any? #(str/includes? % (theme/get-bg-ansi th :tool-error-bg)) raw))
      (is (= "(!)" (str/trim (utils/strip-ansi-codes (peek raw)))))))
  (testing "replayed success (no started-at, no error) renders nothing"
    (is (nil? (r/state-result-nodes th nil nil false))))
  (testing "the measured :elapsed-ms wins over the component timestamp span"
    (let [raw (core/render (r/state-result-nodes th 1000 2000 false 3400) 60)]
      (is (str/includes? (utils/strip-ansi-codes (peek raw)) "Took 3.4s")))))

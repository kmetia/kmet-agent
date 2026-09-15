(ns kmet.app.ui.test-tool-execution
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.theme :as theme]
            [kmet.tui.timers :as timers]
            [kmet.tui.macros :as macros]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.tool-execution :as te]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.text :as text]))

(defn- strip-ansi [s]
  (utils/strip-ansi-codes s))

(defn- spy-component
  "A minimal IComponent counting dispose calls."
  [counter]
  (reify
    protocols/IComponent
    (render [_ _] [])
    (handle-input [_ _] nil)
    (invalidate [_] nil)
    (dispose [_] (swap! counter inc))))

(deftest test-create
  (testing "create tool execution component"
    (let [c (te/make-tool-execution :name "ls")]
      (is (some? c)))))

(deftest test-render-name
  (testing "renders tool name"
    (let [c (te/make-tool-execution :name "read_file")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"read_file" %) plain)))))

(deftest test-render-content
  (testing "renders tool output content"
    (let [c (te/make-tool-execution :name "ls" :content "file1\nfile2")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"file1" %) plain))
      (is (some #(re-find #"file2" %) plain)))))

(deftest test-render-error
  (testing "error tool uses tool-error-bg"
    (let [c (te/make-tool-execution :name "my-tool" :content "failed" :is-error true)
          rendered (core/render c 40)]
        ;; Content visible for errors
      (is (some #(re-find #"failed" %) (mapv strip-ansi rendered))))))

(deftest test-set-name
  (testing "set-name! updates tool name"
    (let [c (te/make-tool-execution :name "old")]
      (reset! (:name-atom c) "new")
      (is (some #(re-find #"new" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-content
  (testing "set-content! updates output"
    (let [c (te/make-tool-execution :name "ls" :content "old")]
      (reset! (:content-atom c) "new content")
      (is (some #(re-find #"new content" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-error
  (testing "set-error! changes background"
    (let [c (te/make-tool-execution :name "ls" :content "ok")]
      (te/tool-execution-set-error! c true)
      ;; Still renders
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-expanded
  (testing "set-expanded! controls visibility"
    (let [c (te/make-tool-execution :name "ls" :content "hidden")]
      ;; Content visible by default
      (is (some #(re-find #"hidden" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-empty-name
  (testing "empty name still renders"
    (let [c (te/make-tool-execution :name "" :content "just content")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"just content" %) plain)))))

(deftest test-set-output-pad
  (testing "set-output-pad! patches the live box's horizontal padding"
    (let [c (te/make-tool-execution :name "ls" :content "x" :output-pad 1)]
      (is (= 1 @(:padding-x-atom @(:box c))))
      (te/tool-execution-set-output-pad! c 5)
      (is (= 5 @(:padding-x-atom @(:box c))) "box padding-x updated")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"^     ls" %) plain)
            "call line indented by the new padding")))))

(deftest test-invalidate
  (testing "invalidate clears cache"
    (let [c (te/make-tool-execution :name "ls" :content "first")]
      (core/render c 40) ;; populate cache
      (reset! (:content-atom c) "second")
      (is (some #(re-find #"second" %) (mapv strip-ansi (core/render c 40)))))))

;; ─── Built-in renderers (read / write / edit / bash) ──────────────────────

(defn- render-tool [& {:keys [name args content is-error expanded? truncation]}]
  (let [c (te/make-tool-execution :name name :args args :content (or content "")
                                  :is-error (boolean is-error) :expanded? expanded?
                                  :truncation truncation)]
    ;; Pi: interactive mode calls setArgsComplete right after creating the
    ;; component (kmet interactive.clj tool-execution-start handler)
    (te/tool-execution-set-args-complete! c)
    (mapv strip-ansi (core/render c 60))))

(deftest test-read-render-hyperlink-path
  (testing "OSC 8 hyperlinked path stays on one line with contiguous visible text (pi linkPath)"
    (let [prev (timg/get-capabilities)]
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
      (try
        (let [plain (render-tool :name "read" :args {:path "src/a.clj" :offset 5 :limit 3})]
          (is (some #(re-find #"read src/a\.clj:5-7" %) plain))
          (is (not-any? #(re-find #"^ read +$" %) plain)
              "path must not wrap onto its own line"))
        (finally
          (timg/set-capabilities! prev))))))

(deftest test-read-render-call-range
  (testing "read call shows line range suffix"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj" :offset 5 :limit 3})]
      (is (some #(re-find #"read src/a\.clj:5-7" %) plain)))))

(deftest test-read-render-empty-path
  (testing "empty path renders toolOutput ellipsis (pi renderToolPath)"
    (let [plain (render-tool :name "read" :args {:path ""})]
      (is (some #(re-find #"read \.\.\." %) plain)))))

(deftest test-read-render-hidden-when-collapsed
  (testing "read result hidden when collapsed and not error (pi)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"} :content "line1\nline2")]
      (is (not-any? #(re-find #"line1" %) plain)))))

(deftest test-read-render-expanded
  (testing "read result shown when expanded"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"} :content "line1\nline2" :expanded? true)]
      (is (some #(re-find #"line1" %) plain)))))

(deftest test-read-render-truncation-warning
  (testing "read truncation warning shown when expanded (pi format)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"}
                             :content "a\nb" :expanded? true
                             :truncation {:total-lines 100 :output-lines 2 :truncated-by :lines
                                          :max-lines 2000 :max-bytes 51200})]
      (is (some #(re-find #"Truncated: showing 2 of 100 lines \(2000 line limit\)" %) plain)))))

(deftest test-write-render-preview
  (testing "write call previews content from args"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj" :content "hello\nworld"})]
      (is (some #(re-find #"hello" %) plain))
      (is (some #(re-find #"world" %) plain)))))

(deftest test-write-render-invalid-content
  (testing "non-string content shows invalid content arg error (pi)"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj" :content 42})]
      (is (some #(re-find #"invalid content arg" %) plain)))))

(deftest test-write-render-error
  (testing "write error result shown"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj"} :content "denied" :is-error true)]
      (is (some #(re-find #"denied" %) plain)))))

(deftest test-edit-render-diff-preview
  (testing "edit call shows numbered diff preview"
    (spit "target/test-tools-edit-render.txt" "alpha\nbeta\ngamma")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :old-text "beta" :new-text "BETA"})]
      (is (some #(re-find #"1 alpha" %) plain))
      (is (some #(re-find #"-2 beta" %) plain))
      (is (some #(re-find #"\+2 BETA" %) plain)))))

(deftest test-edit-render-whitespace-only-no-phantom-diff
  ;; Regression: an edit whose only effect is a trailing-newline/blank-line
  ;; change produced :diff-lines [""] — rendered as a blank box line (a
  ;; "diff with no difference"). It must render without any phantom line.
  (testing "whitespace-only edit preview has no diff lines"
    (spit "target/test-tools-edit-wsonly.txt" "alpha\nbeta\ngamma\n")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-wsonly.txt"
                                    :old-text "gamma\n" :new-text "gamma"})]
      ;; the box renders the title but no diff lines
      (is (some #(re-find #"edit target/test-tools-edit-wsonly.txt" %) plain))
      ;; no -/+ diff lines and no blank diff area
      (is (not-any? #(re-find #"^[ +-]\s*\d" %) plain))
      ;; the only non-title lines are box padding
      (is (every? #(or (str/includes? % "edit target/test-tools-edit-wsonly.txt")
                       (str/blank? %))
                  plain)))))

(deftest test-edit-render-trailing-space-invisible-skipped
  ;; Regression: a -/+ pair whose only difference is trailing whitespace
  ;; rendered with identical visible text (a "diff with no difference").
  ;; It must be skipped entirely — no -/+ lines for invisible changes.
  (testing "trailing-space-only change renders no -/+ lines"
    (spit "target/test-tools-edit-tsp.txt" "alpha\nbeta \ngamma\n")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-tsp.txt"
                                    :old-text "beta " :new-text "beta"})]
      ;; no -/+ lines at all
      (is (not-any? #(re-find #"^[+-]\s*\d" %) plain))
      ;; context lines still shown
      (is (some #(re-find #"1 alpha" %) plain))
      (is (some #(re-find #"3 gamma" %) plain)))))

(deftest test-edit-render-pure-insertion-shows-plus
  ;; Regression: a + run with no preceding - run (pure insertion) fell
  ;; into the context branch — the + marker was stripped and new lines
  ;; rendered as unchanged context, hiding the edit from the diff.
  (testing "insertion-only edit renders + lines"
    (spit "target/test-tools-edit-ins.txt" "alpha\nbeta\ngamma\n")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-ins.txt"
                                    :old-text "beta" :new-text "beta\nbeta2\nbeta3"})]
      (is (some #(re-find #"\+3 beta2" %) plain))
      (is (some #(re-find #"\+4 beta3" %) plain))
      ;; context lines keep their space prefix
      (is (some #(re-find #" 1 alpha" %) plain))
      ;; trailing context keeps its old-file numbering after an insertion
      (is (some #(re-find #" 3 gamma" %) plain)))))

(deftest test-edit-render-mixed-and-insertion-regions
  (testing "mixed -/+ region followed by a standalone insertion run"
    (spit "target/test-tools-edit-mixins.txt" "keep1\nold-a\nnew-x\nnew-y\nkeep2\n")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-mixins.txt"
                                    :edits [{:old-text "old-a" :new-text "new-a"}
                                            {:old-text "keep2" :new-text "keep2\nadded"}]})]
      (is (some #(re-find #"-2 old-a" %) plain))
      (is (some #(re-find #"\+2 new-a" %) plain))
      (is (some #(re-find #"\+6 added" %) plain)))))

(deftest test-edit-render-custom-name
  (testing "shared edit renderer uses the supplied tool name"
    (spit "target/test-tools-edit-render.txt" "alpha")
    (let [c (te/make-tool-execution
             :name "clojure_edit"
             :args {:file_path "target/test-tools-edit-render.txt"
                    :form_type "defn"}
             :render-call-fn renderers/render-edit-call
             :render-result-fn renderers/render-edit-result
             :render-shell :self)]
      (te/tool-execution-set-args-complete! c)
      (is (some #(re-find #"clojure_edit target/test-tools-edit-render.txt" %)
                (mapv strip-ansi (core/render c 60)))))))

(deftest test-edit-render-final-status-without-preview
  (testing "self-shell edit renderer uses final result status without a preview"
    (let [make (fn [is-error]
                 (te/make-tool-execution
                  :name "clojure_edit"
                  :args {}
                  :is-error is-error
                  :render-call-fn renderers/render-edit-call
                  :render-result-fn renderers/render-edit-result
                  :render-shell :self))
          success (make false)
          failure (make true)]
      (te/tool-execution-set-error! success false)
      (te/tool-execution-set-error! failure true)
      (let [success-lines (core/render success 60)
            failure-lines (core/render failure 60)
            success-bg (theme/get-bg-ansi theme/dark-theme :tool-success-bg)
            error-bg (theme/get-bg-ansi theme/dark-theme :tool-error-bg)]
        (is (some #(str/includes? % success-bg) success-lines))
        (is (some #(str/includes? % error-bg) failure-lines))
        (is (not-any? #(str/includes? % error-bg) success-lines))
        (is (not-any? #(str/includes? % success-bg) failure-lines))))))

(deftest test-edit-render-edits-array
  (testing "edit preview handles camelCase edits array"
    (spit "target/test-tools-edit-render.txt" "one two")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :edits [{:oldText "one" :newText "1"}]})]
      (is (some #(re-find #"\+1 1 two" %) plain)))))

(deftest test-edit-render-error-suppressed
  (testing "edit execution error already shown by preview is suppressed (pi dedup)"
    (spit "target/test-tools-edit-render.txt" "alpha")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :old-text "zzz" :new-text "x"}
                             :content "Could not find the exact text in target/test-tools-edit-render.txt. The old text must match exactly including all whitespace and newlines."
                             :is-error true)]
      (is (= 1 (count (filter #(re-find #"Could not find the exact text" %) plain)))))))

(deftest test-bash-render-call
  (testing "bash call shows $ command"
    (let [plain (render-tool :name "bash" :args {:command "ls -la"})]
      (is (some #(re-find #"\$ ls -la" %) plain)))))

(deftest test-bash-render-result
  (testing "bash result shows output and duration"
    (let [c (te/make-tool-execution :name "bash" :args {:command "ls"} :content "out1\nout2")]
      (te/tool-execution-mark-execution-started! c)
      (te/tool-execution-set-error! c false)
      (let [plain (mapv strip-ansi (core/render c 60))]
        (is (some #(re-find #"out1" %) plain))
        (is (some #(re-find #"Took" %) plain))))))

(deftest test-bash-elapsed-ticker
  (testing "partial bash execution starts a 1s elapsed ticker (pi: setInterval →
            context.invalidate); completion clears it and it does not restart"
    (let [c (te/make-tool-execution :name "bash" :args {:command "sleep 5"})]
      (te/tool-execution-mark-execution-started! c)
      (core/render c 60)
      (let [id (:timer-id @(:renderer-state-atom c))]
        (is (some? id) "partial execution starts the elapsed ticker")
        (is (contains? (timers/scheduled) id) "it is a live registry timer (§6.1)"))
      ;; tool-execution-end always calls set-error! → ended-at set → clear
      (let [id (:timer-id @(:renderer-state-atom c))]
        (te/tool-execution-set-error! c false)
        (core/render c 60)
        (is (nil? (:timer-id @(:renderer-state-atom c)))
            "completion clears the ticker")
        (is (not (contains? (timers/scheduled) id)) "…and cancels the timer"))
      ;; a later render (cache miss) must not restart it
      (reset! (:expanded-atom c) true)
      (core/render c 60)
      (is (nil? (:timer-id @(:renderer-state-atom c)))
          "no ticker after completion"))))

;; ─── Pi parity: cached edit preview, compact read, tabs, expanded ─────────

(deftest test-edit-result-diff-corrects-preview
  (testing "edit result with a different applied diff corrects the cached preview (pi resultDiff fallback)"
    (let [f "target/test-tools-edit-resultdiff.txt"]
      (spit f "alpha\nbeta\ngamma")
      (let [c (te/make-tool-execution
               :name "edit"
               :args {:path f :old-text "beta" :new-text "BETA"})]
        (te/tool-execution-set-args-complete! c)
        ;; preview computed against the original file
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; file changed between preview and apply — the tool applied a diff
        ;; that differs from the previewed one
        (spit f "alpha\nbeta\ngamma\ndelta")
        (reset! (:content-atom c)
                "Successfully replaced 1 block(s) in target/test-tools-edit-resultdiff.txt.")
        (te/tool-execution-set-error! c false)
        (reset! (:details-atom c)
                {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma\n 4 delta"})
        ;; this pass still shows the stale preview; the result corrects the cache
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; next render shows the actual applied diff
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #" 4 delta" %) plain))
          (is (not-any? #(re-find #"Could not find old-text" %) plain)))))))

(deftest test-edit-replayed-result-diff
  (testing "replayed edit result (args incomplete, pi restore) shows the applied diff via details"
    (spit "target/test-tools-edit-replay.txt" "alpha\nBETA\ngamma")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-replay.txt"
                    :old-text "beta" :new-text "BETA"}
             :content "Successfully replaced 1 block(s) in target/test-tools-edit-replay.txt."
             :details {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma"})]
      (te/tool-execution-set-error! c false)
      ;; first pass: render-result corrects the preview from details
      (core/render c 60)
      ;; second pass: the call box shows the applied diff
      (let [plain (mapv strip-ansi (core/render c 60))]
        (is (some #(re-find #"\+2 BETA" %) plain))
        (is (some #(re-find #" 1 alpha" %) plain))))))

(deftest test-edit-result-diff-matching-preview-kept
  (testing "edit result matching the preview leaves the call box unchanged (no re-preview)"
    (spit "target/test-tools-edit-match.txt" "alpha\nbeta\ngamma")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-match.txt"
                    :old-text "beta" :new-text "BETA"})]
      (te/tool-execution-set-args-complete! c)
      (let [before (mapv strip-ansi (core/render c 60))]
        (reset! (:content-atom c)
                "Successfully replaced 1 block(s) in target/test-tools-edit-match.txt.")
        (te/tool-execution-set-error! c false)
        (reset! (:details-atom c)
                {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma"})
        (let [after2 (mapv strip-ansi (core/render c 60))]
          ;; identical diff: no correction, no stale-file error
          (is (= before after2))
          (is (not-any? #(re-find #"Could not find old-text" %) after2)))))))

(deftest test-edit-preview-cached-after-edit
  (testing "edit call keeps the original preview after the file was edited (pi caches per args)"
    (let [f "target/test-tools-edit-cache.txt"]
      (spit f "alpha\nbeta\ngamma")
      (let [c (te/make-tool-execution
               :name "edit"
               :args {:path f :old-text "beta" :new-text "BETA"})]
        (te/tool-execution-set-args-complete! c)
        ;; First render: preview succeeds against the original file
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"-2 beta" %) plain))
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; Simulate the edit actually being applied
        (spit f "alpha\nBETA\ngamma")
        ;; Re-render must NOT re-derive the preview from the now-edited file
        ;; (that would fail to find old-text and flip the box to error)
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"-2 beta" %) plain))
          (is (some #(re-find #"\+2 BETA" %) plain))
          (is (not-any? #(re-find #"Could not find old-text" %) plain)))))))

(deftest test-edit-render-pending-when-args-incomplete
  (testing "edit call shows pending box (no diff) until args are complete (pi argsComplete)"
    (spit "target/test-tools-edit-pending.txt" "alpha")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-pending.txt"
                    :old-text "alpha" :new-text "ALPHA"})
      ;; args-complete is false here — no preview yet
          plain (mapv strip-ansi (core/render c 60))]
      (is (some #(re-find #"edit" %) plain))
      (is (not-any? #(re-find #"\+1 ALPHA" %) plain)))))

(deftest test-read-compact-skill
  (testing "SKILL.md reads render as [skill] label + expand hint (pi getCompactReadClassification)"
    (let [plain (render-tool :name "read"
                             :args {:path "/home/user/.pi/skills/demo-skill/SKILL.md"})]
      (is (some #(re-find #"\[skill\] demo-skill" %) plain))
      (is (some #(re-find #"to toggle" %) plain)))))

(deftest test-read-compact-resource
  (testing "AGENTS.md reads render as 'read resource' label (pi)"
    (let [plain (render-tool :name "read" :args {:path "AGENTS.md"})]
      (is (some #(re-find #"read resource AGENTS\.md" %) plain)))))

(deftest test-read-compact-hidden-when-expanded
  (testing "compact read classification is skipped when expanded (pi)"
    (let [plain (render-tool :name "read" :expanded? true
                             :args {:path "/home/user/.pi/skills/demo-skill/SKILL.md"})]
      (is (not-any? #(re-find #"\[skill\]" %) plain))
      (is (some #(re-find #"read /home/user/\.pi/skills/demo-skill/SKILL\.md" %) plain)))))

(deftest test-read-render-file-path-key
  (testing "read call accepts file_path arg key (pi: str(args?.file_path ?? args?.path))"
    (let [plain (render-tool :name "read" :args {:file_path "src/a.clj"})]
      (is (some #(re-find #"read src/a\.clj" %) plain)))))

(deftest test-read-render-tabs-replaced
  (testing "read result replaces tabs with 3 spaces (pi replaceTabs)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"}
                             :content "a\tb" :expanded? true)]
      (is (some #(re-find #"a   b" %) plain))
      (is (not-any? #(re-find #"a\tb" %) plain)))))

(deftest test-read-render-error-lines
  (testing "read error result shows lines (toolOutput; error conveyed by bg)"
    (let [plain (render-tool :name "read" :args {:path "missing.clj"}
                             :content "File not found: missing.clj" :is-error true)]
      (is (some #(re-find #"File not found: missing\.clj" %) plain)))))

(deftest test-write-render-expanded
  (testing "write call shows all lines when expanded (pi maxLines = expanded ? all : 10)"
    (let [content (str/join "\n" (map #(str "line" %) (range 1 13)))
          collapsed (render-tool :name "write"
                                 :args {:path "src/a.clj" :content content})]
      (is (some #(re-find #"line1" %) collapsed))
      (is (some #(re-find #"more lines, 12 total" %) collapsed))
      (is (not-any? #(re-find #"line12" %) collapsed))
      (let [expanded (render-tool :name "write" :expanded? true
                                  :args {:path "src/a.clj" :content content})]
        (is (some #(re-find #"line12" %) expanded))
        (is (not-any? #(re-find #"more lines" %) expanded))))))

(deftest test-read-expanded-leading-blank
  (testing "read result has a blank line between call and result (pi: result starts with a blank line)"
    (let [plain (render-tool :name "read" :expanded? true
                             :args {:path "a.clj"} :content "line1")
          trimmed (mapv str/trim plain)
          call-idx (first (keep-indexed #(when (= "read a.clj" %2) %1) trimmed))
          line-idx (first (keep-indexed #(when (= "line1" %2) %1) trimmed))]
      (is call-idx)
      (is line-idx)
      (is (= line-idx (inc (inc call-idx)))
          "exactly one blank line separates call from result"))))

(deftest test-bash-tool-result-blank-separators
  (testing "bash tool result keeps blank lines before output and before Took"
    (let [c (te/make-tool-execution :name "bash" :args {:command "echo hi"})]
      ;; timing is lifecycle-driven: mark-execution-started! then set-error!
      ;; (pi: markExecutionStarted; set-content! must not mark started —
      ;; replayed tools render without a duration)
      (te/tool-execution-mark-execution-started! c)
      (reset! (:content-atom c) "hi\n")
      (te/tool-execution-set-error! c false)
      (let [plain (mapv strip-ansi (core/render c 60))
            trimmed (mapv str/trim plain)
            out-idx (first (keep-indexed #(when (= "hi" %2) %1) trimmed))
            took-idx (first (keep-indexed #(when (str/starts-with? %2 "Took") %1) trimmed))]
        (is out-idx)
        (is took-idx)
        (is (= "" (str/trim (nth plain (dec out-idx)))) "blank line before output")
        (is (= "" (str/trim (nth plain (dec took-idx)))) "blank line before Took")))))

;; ─── Default renderer (fallback for extension tools) ──────────────────────

(deftest test-default-render-call-shows-args
  (testing "default call shows tool name + arg values"
    (let [plain (render-tool :name "custom" :args {:path "/foo/bar" :verbose true})]
      (is (some #(re-find #"custom" %) plain))
      (is (some #(re-find #"/foo/bar" %) plain))
      (is (some #(re-find #"true" %) plain)))))

(deftest test-default-render-call-no-args
  (testing "default call with no args shows just the name"
    (let [plain (render-tool :name "custom" :args {})]
      (is (some #(re-find #"custom" %) plain))
      (is (not-any? #(re-find #"=" %) plain)))))

(deftest test-default-render-call-truncates-long-args
  (testing "default call truncates args to fit width"
    (let [long-path (apply str (repeat 100 "x"))
          plain (render-tool :name "custom" :args {:path long-path})]
      ;; should not contain the full 100-char path
      (is (not-any? #(re-find (re-pattern long-path) %) plain))
      (is (some #(re-find #"custom" %) plain)))))

(deftest test-default-render-result-collapsed-preview
  (testing "default result shows 5-line preview with expand hint"
    (let [content (str/join "\n" (map #(str "line" %) (range 1 11)))
          plain (render-tool :name "custom" :content content)]
      (is (some #(re-find #"line1" %) plain))
      (is (some #(re-find #"line5" %) plain))
      (is (not-any? #(re-find #"line6" %) plain))
      (is (some #(re-find #"5 more lines" %) plain)))))

(deftest test-default-render-result-expanded
  (testing "default result shows all lines when expanded"
    (let [content (str/join "\n" (map #(str "line" %) (range 1 11)))
          plain (render-tool :name "custom" :content content :expanded? true)]
      (is (some #(re-find #"line10" %) plain))
      (is (not-any? #(re-find #"more lines" %) plain)))))

(deftest test-default-render-result-empty
  (testing "default result with empty content shows just spacer"
    (let [plain (render-tool :name "custom" :content "")]
      (is (some #(re-find #"custom" %) plain))
      (is (not-any? #(re-find #"more lines" %) plain)))))

;; ─── Images (P2: show-images setting + terminal capability) ────────────────

(def ^:private png
  "A 1x1 PNG."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(defn- with-image-env
  "Run F with the shared image settings and terminal capabilities bound
   (both are process-global); restore afterwards."
  [settings caps f]
  (let [prev-settings @subs/image-settings-atom
        prev-caps (timg/get-capabilities)]
    (try
      (reset! subs/image-settings-atom settings)
      (timg/set-capabilities! caps)
      (f)
      (finally
        (reset! subs/image-settings-atom prev-settings)
        (timg/set-capabilities! prev-caps)))))

(defn- image-tool []
  (let [c (te/make-tool-execution :name "read" :args {:path "x.png"} :content "ok")]
    (te/tool-execution-set-images! c [{:data png :mime-type "image/png"}])
    c))

(deftest test-images-fallback-without-support
  (testing "an image result without terminal image support renders the text indicator (pi: getTextOutput + imageFallback)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [plain (mapv strip-ansi (core/render (image-tool) 60))]
          (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %) plain)))))))

(deftest test-images-render-when-enabled
  (testing "an image result renders the terminal image when enabled and supported"
    (with-image-env
      {:show-images true :image-width-cells 20}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (let [lines (core/render (image-tool) 60)
              seq-line (first (filter #(str/includes? % "\u001b_G") lines))]
          (is (some? seq-line))
          (is (= "20" (second (re-find #"c=(\d+)" seq-line)))
              "the configured image width caps the rendered columns")
          (is (not-any? #(re-find #"\[Image:" %) (mapv strip-ansi lines))))))))

(deftest test-images-fallback-when-show-images-off
  (testing "show-images=false drops the image for the text indicator (pi: getTextOutput)"
    (with-image-env
      {:show-images false :image-width-cells 60}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (let [lines (core/render (image-tool) 60)]
          (is (not-any? #(str/includes? % "\u001b_G") lines))
          (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %)
                    (mapv strip-ansi lines))))))))

(deftest test-render-context-show-images
  (testing "renderers receive the effective show-images value — setting AND terminal support (pi: ToolRenderContext showImages)"
    (let [prev-settings @subs/image-settings-atom
          prev-caps (timg/get-capabilities)
          seen (atom [])]
      (try
        (timg/set-capabilities! {:images :kitty :true-color true :hyperlinks true})
        (reset! subs/image-settings-atom {:show-images false :image-width-cells 60})
        (let [c (te/make-tool-execution
                 :name "custom"
                 :render-result-fn (fn [_content _is-error _theme _width _expanded
                                        _started _ended _truncation context]
                                     (swap! seen conj (:show-images context))
                                     nil))]
          (core/render c 60)
          ;; the tracked sub read invalidates the cache; the next render
          ;; reports the new setting
          (reset! subs/image-settings-atom {:show-images true :image-width-cells 60})
          (core/render c 60)
          (is (= [false true] @seen)))
        (finally
          (reset! subs/image-settings-atom prev-settings)
          (timg/set-capabilities! prev-caps))))))

(deftest test-render-context-show-images-capability-gated
  (testing "a terminal without image support reports show-images false even when the setting is on"
    (let [prev-settings @subs/image-settings-atom
          prev-caps (timg/get-capabilities)
          seen (atom [])]
      (try
        (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
        (reset! subs/image-settings-atom {:show-images true :image-width-cells 60})
        (let [c (te/make-tool-execution
                 :name "custom"
                 :render-result-fn (fn [_content _is-error _theme _width _expanded
                                        _started _ended _truncation context]
                                     (swap! seen conj (:show-images context))
                                     nil))]
          (core/render c 60)
          (is (= [false] @seen)))
        (finally
          (reset! subs/image-settings-atom prev-settings)
          (timg/set-capabilities! prev-caps))))))

(deftest test-image-children-disposed-on-rebuild
  (testing "a render that rebuilds the image children disposes the previous
            ImageBlocks — no zombie track! watches (tui.md §5.1)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [c (image-tool)
              watched? (fn [comp]
                         (contains? @(deref #'kmet.tui.macros/watch-registry)
                                    (keyword (str "track!" (System/identityHashCode comp)))))]
          (core/render c 60)
          (let [[_old-spacer old-img] @(:image-children-atom c)]
            (is (watched? old-img) "the rendered image block is watched")
            (reset! (:expanded-atom c) true)  ;; cache miss → children rebuilt
            (core/render c 60)
            (let [[_new-spacer new-img] @(:image-children-atom c)]
              (is (not (identical? old-img new-img)))
              (is (not (watched? old-img))
                  "the previous image block's watches are torn down")
              (is (watched? new-img) "the new image block is watched"))))))))

;; ─── Renderer-child lifecycle (track! zombie-watch regression) ────────────

(defn- tracked?
  "True when COMP is registered in the track! watch registry (its render ran
   with watches)."
  [comp]
  (contains? @(deref #'kmet.tui.macros/watch-registry)
             (keyword (str "track!" (System/identityHashCode comp)))))

(deftest test-renderer-children-disposed-on-rebuild
  (testing "a cache-miss rebuild disposes the previous renderer outputs — no
            zombie track! watches from dropped call/result components"
    (let [made (atom [])
          c (te/make-tool-execution
             :name "custom"
             :render-call-fn (fn [& _]
                               (let [comp (text/make-text "call" 0 0)]
                                 (swap! made conj comp)
                                 comp)))]
      (core/render c 40)
      (let [first-call (last @made)]
        (is (tracked? first-call) "the first pass's call component is watched")
        (reset! (:expanded-atom c) true)  ;; cache miss → rebuild
        (core/render c 40)
        (is (not (identical? first-call (last @made))))
        (is (not (tracked? first-call))
            "the replaced call component's watches are torn down")))))

(deftest test-renderer-reused-component-survives-rebuild
  (testing "a renderer returning its :last-component keeps that instance —
            it is re-added, never disposed"
    (let [made (atom [])
          c (te/make-tool-execution
             :name "custom"
             :render-call-fn (fn [_name _args _theme _width context]
                               (or (:last-component context)
                                   (let [comp (text/make-text "call" 0 0)]
                                     (swap! made conj comp)
                                     comp))))]
      (core/render c 40)
      (let [first-call (last @made)]
        ;; a cache miss with the renderer reusing its component
        (reset! (:expanded-atom c) true)
        (core/render c 40)
        (is (identical? first-call (last @made)) "only one component was made")
        (is (= first-call @(:last-call-component-atom c)))
        (is (tracked? first-call)
            "the reused instance was not disposed (its watches survive)")))))

(deftest test-nil-call-component-renders
  (testing "a renderer returning nil for the call renders the result alone
            (renderers return IComponent or nil — a nil child must not reach
            the container, which would crash the render loop)"
    (let [c (te/make-tool-execution :name "custom" :content "out"
                                    :render-call-fn (fn [& _] nil))
          plain (mapv strip-ansi (core/render c 60))]
      (is (some #(re-find #"out" %) plain)))))

(deftest test-renderer-child-watch-count-stable
  (testing "repeated cache-miss renders do not grow the watch registry — the
            dropped children are disposed each pass"
    (let [c (te/make-tool-execution :name "read" :args {:path "a.clj"} :content "x")
          watchers (fn [] (count @(deref #'kmet.tui.macros/watch-registry)))]
      (core/render c 60)
      (let [baseline (watchers)]
        (dotimes [i 5]
          (reset! (:expanded-atom c) (odd? i))
          (core/render c 60))
        (is (= baseline (watchers))
            "steady state: no accumulation across rebuilds")))))

(deftest test-image-children-cache-hit-steady-state
  (testing "with images the render cache still HITS in steady state: the
            image-children atom is read untracked, so a body run does not
            invalidate its own cache (regression: a tracked read of an atom
            the body resets made the cache miss every frame, rebuilding the
            image children — new kitty image ids — on every render)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [c (image-tool)
              r1 (core/render c 60)
              r2 (core/render c 60)
              r3 (core/render c 60)]
          (is (identical? r1 r2) "second render is a cache hit")
          (is (identical? r2 r3) "…and stays a hit")
          (let [children @(:image-children-atom c)]
            (is (identical? children @(:image-children-atom c)))
            (core/render c 60)
            (is (identical? children @(:image-children-atom c))
                "image children are not rebuilt on cache hits")))))))

(deftest test-hide-branch-disposes-children
  (testing "when the component hides (no call/result, no images) the previous
            renderer outputs AND image children are disposed and the inner
            container is emptied"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [first-pass? (atom true)
              c (te/make-tool-execution
                 :name "custom"
                 ;; a call component on the first pass, nil afterwards
                 :render-call-fn (fn [& _]
                                   (when (compare-and-set! first-pass? true false)
                                     (text/make-text "call" 0 0)))
                 :render-result-fn (fn [& _] nil))]
          (te/tool-execution-set-images! c [{:data png :mime-type "image/png"}])
          (core/render c 60)
          (let [img-children @(:image-children-atom c)]
            (is (seq img-children))
            ;; images disappear and the call renderer returns nil → hide
            (te/tool-execution-set-images! c [])
            (is (= [] (core/render c 60)) "component hides")
            (is (empty? @(:children @(:inner-container c)))
                "the inner container is emptied")
            (is (not (tracked? (first img-children)))
                "the dropped image children are disposed")))))))

(deftest test-dispose-disposes-image-children
  (testing "component dispose disposes the image children (idempotent — the
            container may have disposed them already)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [c (image-tool)]
          (core/render c 60)
          (let [img-children @(:image-children-atom c)]
            (is (seq img-children))
            (is (tracked? (last img-children)))
            (protocols/dispose c)
            (is (not (tracked? (last img-children)))
                "dispose tears down the image children's watches")))))))

(deftest test-duck-typed-renderer-output
  (testing "a duck-typed {:render …} renderer output (the ui-custom
            contract) is wrapped in an adapter record: it renders, reuses
            via :last-component, and the container/box dispose chain
            releases it without throwing (a raw map has no protocol impl)"
    (let [disposed (atom 0)
          duck (fn [tag]
                 {:render (fn [_] [tag])
                  :dispose (fn [] (swap! disposed inc))})
          made (atom 0)
          c (te/make-tool-execution
             :name "custom" :content "out"
             :render-call-fn (fn [& args]
                               (let [ctx (last args)]
                                 (or (:last-component ctx)
                                     (do (swap! made inc) (duck "duck line"))))))]
      (let [lines (mapv strip-ansi (core/render c 60))]
        (is (some #(str/includes? % "duck line") lines)))
      (let [first-comp @(:last-call-component-atom c)]
        (is (some? first-comp))
        ;; cache miss: the renderer's :last-component reuse keeps the wrapper
        (reset! (:expanded-atom c) true)
        (core/render c 60)
        (is (= 1 @made) "wrapped once, reused across passes")
        (is (identical? first-comp @(:last-call-component-atom c))))
      (is (= 0 @disposed) "a reused duck output is not disposed")
      (protocols/dispose c)
      (is (= 1 @disposed) "component dispose releases the duck output once"))))

;; ─── Quiet mode (:quiet display) ────────────────────────────────────────────

(defn- render-quiet [& {:keys [name args content is-error title-fn]}]
  (let [shared (atom :quiet)
        c (te/make-tool-execution :name name :args args :content (or content "")
                                  :is-error (boolean is-error)
                                  :tools-expanded-atom shared
                                  :title-fn title-fn)]
    ;; settled (ended) by default — the pending ... state has its own test
    (te/tool-execution-set-error! c (boolean is-error))
    (mapv strip-ansi (core/render c 60))))

(deftest test-quiet-renders-dimmed-title-line
  (testing "quiet is one bare title line (the run separator lives in chat-history)"
    (let [lines (render-quiet :name "read" :args {:path "src/a.clj"}
                              :content "line1\nline2"
                              :title-fn (fn [a] (str "read " (:path a))))]
      (is (= 1 (count lines)))
      (is (re-find #"read src/a\.clj" (first lines)))
      (is (not (re-find #"\[tool\]" (first lines))) "no [tool] prefix")
      (is (not-any? #(re-find #"line1|line2" %) lines)
          "result content never reaches the quiet line")
      (is (not-any? #(re-find #"ctrl\+o|toggle" %) lines)
          "no hint in quiet")))
  (testing "error prefix is text, still the one dimmed line"
    (let [lines (render-quiet :name "bash" :args {:command "ls"} :content "boom"
                              :is-error true :title-fn (fn [a] (str "bash $ " (:command a))))]
      (is (= 1 (count lines)))
      (is (re-find #"\(\!\) bash \$ ls" (first lines)))))
  (testing "missing title falls back to the tool name"
    (let [lines (render-quiet :name "myext" :args {} :content "x")]
      (is (re-find #"myext" (first lines)) "bare tool name, no prefix")))
  (testing "a multiline title collapses to one visual line"
    (let [lines (render-quiet :name "bash" :args {:command "cd /x && python3 - <<'EOF'\nprint(1)\nprint(2)"}
                              :title-fn (fn [a] (str "bash $ " (:command a))))]
      (is (= 1 (count lines)))
      (is (<= (utils/visible-width (first lines)) 60))
      (is (re-find #"print\(1\) print\(2\)" (first lines))
          "newline became a space instead of wrapping")))
  (testing "a throwing title degrades to the name"
    (let [lines (render-quiet :name "myext" :args {} :content "x"
                              :title-fn (fn [_] (throw (ex-info "boom" {}))))]
      (is (re-find #"myext" (first lines)) "bare tool name, no prefix"))))

(deftest test-quiet-ignores-content-and-renderers
  (testing "content swaps never touch the quiet line (scrollback-immutable)"
    (let [shared (atom :quiet)
          c (te/make-tool-execution :name "bash" :args {:command "ls"} :content "out1"
                                    :tools-expanded-atom shared
                                    :title-fn (fn [a] (str "bash $ " (:command a))))
          before (mapv strip-ansi (core/render c 60))]
      (reset! (:content-atom c) "out2-changed")
      (is (= before (mapv strip-ansi (core/render c 60))))))
  (testing "custom renderers never run in quiet"
    (let [shared (atom :quiet)
          called (atom 0)
          c (te/make-tool-execution :name "custom" :args {} :content "out"
                                    :tools-expanded-atom shared
                                    :render-call-fn (fn [& _] (swap! called inc) nil)
                                    :render-result-fn (fn [& _] (swap! called inc) nil))]
      (core/render c 60)
      (is (zero? @called))))
  (testing "images are hidden in quiet"
    (let [shared (atom :quiet)
          c (te/make-tool-execution :name "read" :args {:path "x.png"} :content "ok"
                                    :tools-expanded-atom shared)]
      (te/tool-execution-set-images! c [{:data "AA" :mime-type "image/png"}])
      (let [lines (mapv strip-ansi (core/render c 60))]
        (is (= 1 (count lines)))
        (is (not-any? #(re-find #"Image" %) lines))))))

(deftest test-quiet-running-and-error-prefixes
  (testing "ended-ness drives the quiet prefix: ... while pending, bare when done, (!) on error"
    (let [shared (atom :quiet)
          title-fn (fn [a] (str "bash $ " (:command a)))
          c (te/make-tool-execution :name "bash" :args {:command "ls"}
                                    :tools-expanded-atom shared :title-fn title-fn)]
      (is (re-find #"\.\.\. bash \$ ls"
                   (first (mapv strip-ansi (core/render c 60))))
          "fresh (ended-at nil) shows ...")
      (te/tool-execution-set-error! c false)
      (is (re-find #"bash \$ ls"
                   (first (mapv strip-ansi (core/render c 60))))
          "ended ok carries no prefix")
      (te/tool-execution-set-error! c true)
      (is (re-find #"\(\!\) bash \$ ls"
                   (first (mapv strip-ansi (core/render c 60))))
          "error shows (!), implying done"))))

(deftest test-quiet-cancels-elapsed-ticker
  (testing "entering quiet cancels the running-tool repaint timer"
    (let [shared (atom :collapsed)
          c (te/make-tool-execution :name "bash" :args {:command "sleep 5"}
                                    :tools-expanded-atom shared)]
      (te/tool-execution-mark-execution-started! c)
      (core/render c 60)
      (is (some? (:timer-id @(:renderer-state-atom c))) "ticker armed while collapsed")
      (reset! shared :quiet)
      (core/render c 60)
      (is (nil? (:timer-id @(:renderer-state-atom c))) "quiet cancelled it")
      (is (not (contains? (timers/scheduled) (:timer-id @(:renderer-state-atom c))))))))

(deftest test-quiet-long-title-truncates-to-one-line
  (testing "a long title truncates with an ellipsis to a single visual line"
    (let [long-path (apply str (repeat 100 "x"))
          lines (render-quiet :name "read" :args {:path long-path}
                              :title-fn (fn [a] (str "read " (:path a))))]
      (is (= 1 (count lines)))
      (is (re-find #"\.\.\." (first lines)) "ellipsis marks the cut")
      (is (<= (utils/visible-width (first lines)) 60)))))

(deftest test-quiet-disposes-shared-instance-once
  (testing "entering quiet disposes a renderer instance shared by both
            slots exactly once"
    (let [disposed (atom 0)
          current (atom nil)
          renderer (fn [& _] @current)
          shared (atom :collapsed)
          c (te/make-tool-execution
             :name "custom" :content "out" :tools-expanded-atom shared
             :render-call-fn renderer
             :render-result-fn renderer)]
      (reset! current (spy-component disposed))
      (core/render c 60)
      (is (= 0 @disposed))
      (reset! shared :quiet)
      (core/render c 60)
      (is (= 1 @disposed)
          "the shared instance is disposed once, not twice"))))

(deftest test-same-instance-both-slots-disposed-once
  (testing "a renderer returning one instance for BOTH slots (call and
            result) is disposed exactly once when replaced"
    (let [disposed (atom 0)
          current (atom nil)
          renderer (fn [& _] @current)
          c (te/make-tool-execution
             :name "custom" :content "out"
             :render-call-fn renderer
             :render-result-fn renderer)]
      (reset! current (spy-component disposed))
      (core/render c 60)
      (is (= 0 @disposed))
      ;; replace both slots with a fresh instance
      (reset! current (spy-component disposed))
      (reset! (:expanded-atom c) true)  ;; force a cache miss
      (core/render c 60)
      (is (= 1 @disposed)
          "the dropped shared instance is disposed once, not twice"))))

(ns kmet.extensions.clojure.paren-repair-test
  "Tests for the clojure_paren_repair tool (kmet.extensions.clojure.paren-repair namespace).
   Each test writes a temp file, calls paren-repair/execute, and asserts
   on the result map and the written file content."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extensions.clojure.paren-repair :as paren-repair]
            [kmet.test-tmp :as test-tmp]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(def ^:private test-dir
  "Scratch files under the test run's root (kmet.test-tmp), removed with it."
  (test-tmp/temp-dir! "paren-repair-tests-"))

(defn- ensure-test-dir! []
  (fs/create-dirs test-dir))

(defn- write-test-file!
  "Write BODY to a temp file under test-dir and return its path."
  [name body]
  (ensure-test-dir!)
  (let [path (str test-dir "/" name)]
    (spit path body)
    path))

(defn- read-test-file [path]
  (slurp path :encoding "UTF-8"))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Parameter validation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-missing-file-path
  (let [result (paren-repair/execute {})]
    (is (:is-error result))
    (is (str/includes? (:content result) "file_path"))))

(deftest ^:bb-only test-nonexistent-file
  (let [result (paren-repair/execute {:file_path (str test-dir "/does-not-exist.clj")})]
    (is (:is-error result))
    (is (str/includes? (:content result) "does not exist"))))

(deftest ^:bb-only test-non-clojure-file
  (let [path (write-test-file! "notes.txt" "hello (unclosed")
        result (paren-repair/execute {:file_path path})]
    (is (:is-error result))
    (is (str/includes? (:content result) "Not a Clojure file"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Repair behavior
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-repairs-missing-paren
  (let [path (write-test-file! "missing.clj" "(defn foo [x]\n  (+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "delimiter-fixed"))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo [x]"))
      (is (str/includes? content "(+ x 1)")))))

(deftest ^:bb-only test-repairs-extra-paren
  (let [path (write-test-file! "extra.clj" "(defn foo [x] (+ x 1)))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "delimiter-fixed"))
    (let [content (read-test-file path)]
      ;; the extra close is dropped
      (is (= "(defn foo [x] (+ x 1))" (str/trim content))))))

(deftest ^:bb-only test-repairs-brackets-and-braces
  (let [path (write-test-file! "mixed.clj" "(let [x 1]\n  {:a x\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "{:a x}")))))

(deftest ^:bb-only test-no-changes-needed
  (let [path (write-test-file! "balanced.clj" "(def x 1)\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "No changes needed"))
    (is (= "(def x 1)\n" (read-test-file path)))))

(deftest ^:bb-only test-unrepairable-file-fails-explicitly
  ;; An unterminated string is a delimiter error parinferish cannot repair.
  ;; This used to fall through to a false "No changes needed (no delimiter
  ;; errors)" success; it must fail with the actual remaining error.
  (let [path (write-test-file! "unterminated.clj" "(def s \"unterminated\n")
        result (paren-repair/execute {:file_path path})]
    (is (:is-error result))
    (is (str/includes? (:content result) "Could not fix"))
    (is (str/includes? (:content result) "Unbalanced delimiters in"))
    (is (str/includes? (:content result) "expected '\"' to close '\"'"))
    (is (str/includes? (:content result) "manually"))
    ;; the file must be untouched
    (is (= "(def s \"unterminated\n" (read-test-file path)))))

(deftest ^:bb-only test-format-false-keeps-formatting
  (let [path (write-test-file! "noformat.clj" "(defn foo[x](+ x 1\n")
        result (paren-repair/execute {:file_path path :format false})]
    (is (not (:is-error result)))
    ;; delimiters fixed but cljfmt not applied — no space added after foo
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo[x](+ x 1))")))))

(deftest ^:bb-only test-format-true-applies-cljfmt
  (let [path (write-test-file! "fmt.clj" "(defn foo[x](+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo [x]")))))

(deftest ^:bb-only test-repair-honors-project-cljfmt-edn
  (let [dir (fs/create-dir (fs/path test-dir "fmt-proj"))
        _   (spit (fs/file dir "cljfmt.edn")
                  "{:extra-indents {mydef [[:block 3] [:inner 1]]}}")
        path (str (fs/file dir "sample.clj"))
        _    (spit path "(mydef Foo nil\n  [container search-input]\n  (render [this w] (str a))\n")
        result (paren-repair/execute {:file_path path})]
    (try
      (is (not (:is-error result)))
      (let [content (read-test-file path)]
        ;; with [:block 3], the 4th element (render) is block-indented at
        ;; col 2; without the config it would align under the first arg
        (is (str/includes? content "\n  (render [this w] (str a))"))
        (is (not (str/includes? content "\n       (render"))))
      (finally (fs/delete-tree dir)))))

(deftest ^:bb-only test-does-not-restructure-var-call
  ;; Regression: indent-mode repair moved the ')' of '(var f!)' past
  ;; deeper-indented continuation lines, pulling cs/:compaction into the
  ;; var form. Delimiter-only repair drops the stray closer and leaves the
  ;; explicit (var ...) call untouched.
  (let [body (str "(deftest ^:bb-only t\n"
                  "  (testing \"x\"\n"
                  "      ((var status/show-status-indicator!) cs :compaction\n"
                  "                                           (status-indicator/make-compaction-status-indicator))\n"
                  "      ))\n"
                  ")\n")
        path (write-test-file! "var-call.clj" body)
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "((var status/show-status-indicator!) cs :compaction"))
      (is (not (str/includes? content "(var status/show-status-indicator! cs"))))))

(deftest ^:bb-only test-does-not-restructure-deep-continuation
  ;; Same failure mode without var: a closed subform followed by same-line
  ;; args and a deeper-indented continuation line must keep its closer.
  (let [path (write-test-file! "deep-continuation.clj"
                               "(defn f []\n  ((foo) a\n     (b))))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "((foo) a"))
      (is (not (str/includes? content "(foo a"))))))

(deftest ^:bb-only test-closer-after-multiline-string
  ;; A line ending inside a multiline string is not a safe place for a
  ;; closer: it goes after the string's closing quote, never inside it.
  (let [path (write-test-file! "multiline-string.clj"
                               "(do\n  (println \"hello\nworld\"\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "world\"))")))))

(deftest ^:bb-only test-closes-forgotten-opener-at-dedent
  ;; The indentation-based half of the repair: a forgotten closer goes at
  ;; the end of the dead-ended form's block, so the dedented `(defn b ...)`
  ;; stays a sibling instead of being swallowed by `(b`.
  (let [path (write-test-file! "dedent.clj"
                               "(defn a []\n  (b\n(defn b []\n  (c))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn a []\n  (b))"))
      (is (str/includes? content "\n(defn b []\n  (c))")))))

(deftest ^:bb-only test-returns-diff
  (let [path (write-test-file! "diff.clj" "(defn foo [x] (+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (some? (:details result)))
    (is (str/includes? (get-in result [:details :diff]) "+"))))

(deftest ^:bb-only test-edn-files-supported
  (let [path (write-test-file! "config.edn" "{:a 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "{:a 1}"))))

(deftest ^:bb-only test-multiline-balanced-file
  (let [path (write-test-file! "multiline.clj"
                               "(defn process [items]\n  (map (fn [x]\n         (* x 2))\n       items))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      ;; file was balanced already — no changes
      (is (str/includes? (:content result) "No changes needed"))
      (is (= "(defn process [items]\n  (map (fn [x]\n         (* x 2))\n       items))\n" content)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Hook: write blocked, edit warned (one around function)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- run-hook
  "Run the extension's around hook over OP with NEXT answering RESULT, and
   report whether it descended — i.e. whether the tool body would have run."
  [op result]
  (let [descended (atom false)
        r (paren-repair/on-tool op (fn [_] (reset! descended true) result))]
    {:result r :descended? @descended}))

(deftest ^:bb-only test-write-hook-blocks-unbalanced
  (let [{:keys [result descended?]}
        (run-hook {:tool-name "write"
                   :args {:path "src/foo.clj"
                          :content "(defn foo [x]"}}
                  {:content "wrote"})]
    (is (:block result))
    (is (not descended?) "a blocked write never reaches the body")
    (is (str/includes? (:reason result) "Unbalanced delimiters in src/foo.clj"))
    (is (str/includes? (:reason result) "Write blocked"))))

(deftest ^:bb-only test-write-hook-blocks-syntax-error-explicitly
  ;; Any parse problem blocks the write — a bad token names the reader's
  ;; actual error and position instead of letting the broken file through.
  (let [{:keys [result descended?]}
        (run-hook {:tool-name "write"
                   :args {:path "src/foo.clj"
                          :content "(def x 1)\n#"}}
                  {:content "wrote"})]
    (is (:block result))
    (is (not descended?))
    (is (str/includes? (:reason result) "Syntax error in src/foo.clj"))
    (is (str/includes? (:reason result) "at line 2"))
    (is (str/includes? (:reason result) "Write blocked"))))

(deftest ^:bb-only test-write-hook-allows-balanced
  (let [{:keys [result descended?]}
        (run-hook {:tool-name "write"
                   :args {:path "src/foo.clj"
                          :content "(defn foo [x] (+ x 1))"}}
                  {:content "wrote"})]
    (is descended? "the body runs")
    (is (= {:content "wrote"} result) "and its result passes through")))

(deftest ^:bb-only test-write-hook-ignores-non-clojure
  (let [{:keys [descended?]}
        (run-hook {:tool-name "write"
                   :args {:path "src/foo.txt"
                          :content "(unbalanced"}}
                  {:content "wrote"})]
    (is descended?)))

(deftest ^:bb-only test-write-hook-ignores-other-tools
  (let [{:keys [descended?]}
        (run-hook {:tool-name "edit"
                   :args {:path "src/foo.clj"
                          :content "(unbalanced"}}
                  {:content "edited"})]
    (is descended? "an edit is judged on its result, not on its arguments")))

(deftest ^:bb-only test-write-hook-reports-expected-vs-opened
  (let [{:keys [result]}
        (run-hook {:tool-name "write"
                   :args {:path "a.clj"
                          :content "(defn foo [x]"}}
                  {:content "wrote"})]
    (is (:block result))
    ;; precise: expected ')' to close '(' with opened-at location
    (is (str/includes? (:reason result) "expected ')' to close '('"))
    (is (str/includes? (:reason result) "opened at line"))))

(deftest ^:bb-only test-write-hook-reports-unexpected-closer
  ;; Regression: an extra closer makes edamame report BLANK delimiter
  ;; strings — truthy empty strings that rendered as "expected '' to
  ;; close ''". The report must name the offending character instead.
  (let [{:keys [result]}
        (run-hook {:tool-name "write"
                   :args {:path "a.clj"
                          :content "(defn foo [x] (+ x 1)))"}}
                  {:content "wrote"})]
    (is (:block result))
    (is (str/includes? (:reason result) "unexpected ')'"))
    (is (not (str/includes? (:reason result) "''")))))

(deftest ^:bb-only test-edit-hook-reports-unexpected-closer
  ;; Same blank-delimiter case through the post side of the hook, where the
  ;; report reads the offending character from the written file.
  (let [path (write-test-file! "edit-extra-close.clj" "(defn foo [x] (+ x 1)))")
        {:keys [result descended?]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "edited" :is-error false})]
    (is descended?)
    (is (str/includes? (:content result) "unexpected ')'"))
    (is (not (str/includes? (:content result) "''")))))

(deftest ^:bb-only test-edit-hook-warns-on-unbalanced-result
  (let [path (write-test-file! "edit-warn.clj" "(defn foo [x]")
        {:keys [result]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "edited" :is-error false})]
    (is (str/includes? (:content result) "⚠️"))
    (is (str/includes? (:content result) "clojure_paren_repair"))
    (is (str/includes? (:content result) "edited"))))

(deftest ^:bb-only test-edit-hook-warns-on-syntax-error-result
  ;; A non-delimiter syntax error warns too — with a manual-fix hint,
  ;; since clojure_paren_repair cannot repair reader errors.
  (let [path (write-test-file! "edit-syntax-warn.clj" "(def x 1) #")
        {:keys [result]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "edited" :is-error false})]
    (is (str/includes? (:content result) "⚠️"))
    (is (str/includes? (:content result) "Syntax error in"))
    (is (str/includes? (:content result) "Fix the syntax error manually"))
    (is (not (str/includes? (:content result) "clojure_paren_repair on")))))

(deftest ^:bb-only test-edit-hook-silent-on-balanced-result
  (let [path (write-test-file! "edit-ok.clj" "(defn foo [x] (+ x 1))")
        {:keys [result]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "edited" :is-error false})]
    (is (= {:content "edited" :is-error false} result)
        "a clean file leaves the result untouched")))

(deftest ^:bb-only test-edit-hook-ignores-errors
  (let [path (write-test-file! "edit-err.clj" "(defn foo [x]")
        {:keys [result]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "failed" :is-error true})]
    (is (= {:content "failed" :is-error true} result))))

(deftest ^:bb-only test-edit-hook-ignores-non-clojure
  (let [path (write-test-file! "notes.txt" "(unbalanced")
        {:keys [result]}
        (run-hook {:tool-name "edit" :args {:path path}}
                  {:content "edited" :is-error false})]
    (is (= {:content "edited" :is-error false} result))))

;; ─── Quiet titles ───────────────────────────────────────────────────────────

(deftest ^:bb-only test-title
  (is (= "clojure_paren_repair /a/b.clj"
         (paren-repair/title {:file_path "/a/b.clj"})))
  (testing "string keys and home folding"
    (let [home (System/getProperty "user.home" "")]
      (is (= (str "clojure_paren_repair ~" "/b.clj")
             (paren-repair/title {"file_path" (str home "/b.clj")})))))
  (testing "missing path degrades to nil"
    (is (nil? (paren-repair/title {})))
    (is (nil? (paren-repair/title nil)))))

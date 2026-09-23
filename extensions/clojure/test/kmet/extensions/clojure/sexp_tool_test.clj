(ns kmet.extensions.clojure.sexp-tool-test
  "Tests for the clojure_edit_replace_sexp tool (kmet.extensions.clojure.sexp-tool namespace).
   Each test writes a temp .clj file, calls sexp-tool/execute, and asserts
   on the result map and the written file content."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extensions.clojure.sexp-tool :as sexp-tool]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(def ^:private test-dir "target/sexp-tool-tests")

(defn- ensure-test-dir! []
  (fs/create-dirs test-dir))

(defn- write-test-file!
  "Write BODY to a temp .clj file under test-dir and return its path."
  [name body]
  (ensure-test-dir!)
  (let [path (str test-dir "/" name ".clj")]
    (spit path body)
    path))

(defn- read-test-file [path]
  (slurp path :encoding "UTF-8"))

(defn- sexp-opts
  "Shorthand for building an execute opts map."
  [file_path match_form new_form & {:keys [operation replace_all]}]
  (cond-> {:file_path  file_path
           :match_form match_form
           :new_form   new_form
           :operation  (or operation "replace")}
    (some? replace_all) (assoc :replace_all replace_all)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Parameter validation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-missing-file-path
  (let [result (sexp-tool/execute {:match_form "(+ x 1)"
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "file_path"))))

(deftest test-file-not-found
  (let [result (sexp-tool/execute {:file_path "target/nonexistent-xyz.clj"
                                   :match_form "(+ x 1)"
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "not found"))))

(deftest test-non-clojure-file
  (let [path (do (ensure-test-dir!)
                 (str test-dir "/notes.txt"))
        _    (spit path "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "Not a Clojure file"))))

(deftest test-missing-match-form
  (let [path (write-test-file! "missing-match" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "match_form"))))

(deftest test-missing-new-form
  (let [path (write-test-file! "missing-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "new_form"))))

(deftest test-match-form-whitespace-only
  (let [path (write-test-file! "whitespace-match" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "   \n  "
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    ;; Blank match_form is treated as missing
    (is (str/includes? (:content result) "match_form"))))

(deftest test-match-form-comment-only
  (let [path (write-test-file! "comment-match" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form ";; just a comment"
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "S-expression"))))

(deftest test-new-form-invalid
  ;; Unbalanced new_form ("(defn foo [") is rejected — no auto-repair —
  ;; with the actual error: kind, location, opened-at.
  (let [path (write-test-file! "invalid-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(defn foo ["
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    (is (str/includes? (:content result) "Unbalanced delimiters in new_form"))
    (is (str/includes? (:content result) "at line 1"))
    (is (str/includes? (:content result) "opened at line 1, col 11"))
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

(deftest test-match-form-syntax-error-reports-location
  ;; A balanced-looking but unparsable match_form names the reader error
  ;; and position instead of failing later as a confusing not-found.
  (let [path (write-test-file! "syntax-match" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "foo #"
                                   :new_form "(+ x 2)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "match_form must be complete and balanced"))
    (is (str/includes? (:content result) "Syntax error in match_form"))
    (is (str/includes? (:content result) "at line 1, col 6"))))

(deftest test-new-form-unrepairable
  (let [path (write-test-file! "invalid-new2" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "#_"
                                   :operation "replace"})]
    ;; #_ alone is not a valid expression even after repair
    (is (:is-error result))))

(deftest test-new-form-unbalanced-rejected
  ;; Unbalanced new_form is rejected outright — no auto-repair.
  (let [path (write-test-file! "unbalanced-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(* x 2"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

(deftest test-match-form-unbalanced-rejected
  ;; Unbalanced match_form is rejected outright — no auto-repair.
  (let [path (write-test-file! "unbalanced-match" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(* x 2"
                                   :new_form "(+ x 1)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

(deftest test-new-form-broken-defn-rejected
  ;; A broken definition fragment like "(defn- filter-kind" (unbalanced,
  ;; missing the arg vector) is rejected — it used to be auto-repaired into
  ;; a bodyless "(defn- filter-kind)" and silently written to the file.
  (let [path (write-test-file! "broken-defn-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(defn- filter-kind"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

(deftest test-new-form-incomplete-def-rejected
  ;; A balanced-but-incomplete def ("(def foo)" with no value) is rejected
  ;; by structural validation — and the validator's actual reason is
  ;; spelled out instead of a generic rejection.
  (let [path (write-test-file! "incomplete-def-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(def foo)"
                                   :operation "replace"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "Invalid new_form"))
    (is (str/includes? (:content result) "expected (def name value)"))
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

(deftest test-new-form-valid-def-allowed
  ;; Valid definition forms still work.
  (let [path (write-test-file! "valid-def-new" "(+ x 1)")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(+ x 1)"
                                   :new_form "(def foo 3)"
                                   :operation "replace"})]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(def foo 3)"))))

(deftest test-match-form-auto-repaired
  (let [path (write-test-file! "auto-repair-match" "(defn foo [] (* x 2))")
        result (sexp-tool/execute {:file_path path
                                   :match_form "(* x 2"
                                   :new_form "(* x 3)"
                                   :operation "replace"})]
    ;; unbalanced match_form is rejected, not repaired
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    (is (str/includes? (read-test-file path) "(* x 2)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Basic replace
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-simple-expression
  (let [path (write-test-file! "replace-simple"
                               "(+ x 1)\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ x 1)" "(+ x 10)"))]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "Edit applied"))
    (is (str/includes? (read-test-file path) "(+ x 10)"))
    (is (not (str/includes? (read-test-file path) "(+ x 1)")))))

(deftest test-replace-in-function-body
  (let [path (write-test-file! "replace-in-fn"
                               "(defn compute [x]\n  (+ x 1))\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ x 1)" "(* x 2)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(* x 2)"))))

(deftest test-replace-function-call
  (let [path (write-test-file! "replace-call"
                               "(println \"hello\")\n")
        result (sexp-tool/execute
                (sexp-opts path "(println \"hello\")" "(println \"world\")"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "\"world\""))))

(deftest test-replace-let-binding
  (let [path (write-test-file! "replace-let"
                               "(let [x 1 y 2]\n  (+ x y))\n")
        result (sexp-tool/execute
                (sexp-opts path "y 2" "y 20"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "y 20"))))

(deftest test-replace-keyword
  (let [path (write-test-file! "replace-keyword"
                               "{:name \"Alice\" :age 30}\n")
        result (sexp-tool/execute
                (sexp-opts path ":name" ":full-name"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content ":full-name"))
      (is (not (str/includes? content ":name"))))))

(deftest test-replace-vector-element
  (let [path (write-test-file! "replace-vector"
                               "[1 2 3 4 5]\n")
        result (sexp-tool/execute
                (sexp-opts path "3" "99"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "99"))))

(deftest test-replace-map-entry
  (let [path (write-test-file! "replace-map"
                               "{:a 1 :b 2 :c 3}\n")
        result (sexp-tool/execute
                (sexp-opts path ":b 2" ":b 200"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) ":b 200"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Whitespace-normalized matching
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-whitespace-insensitive-match
  (testing "extra whitespace in source doesn't prevent matching"
    (let [path (write-test-file! "ws-match"
                                 "(+   x    1)\n")
          result (sexp-tool/execute
                  (sexp-opts path "(+ x 1)" "(+ x 99)"))]
      (is (not (:is-error result)))
      (is (str/includes? (read-test-file path) "(+ x 99)")))))

(deftest test-whitespace-insensitive-multiline
  (testing "multiline source matches with single-line match"
    (let [path (write-test-file! "ws-multiline"
                                 "(+ x\n   1)\n")
          result (sexp-tool/execute
                  (sexp-opts path "(+ x 1)" "(* x 1)"))]
      (is (not (:is-error result)))
      (is (str/includes? (read-test-file path) "(* x 1)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Match not found
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-match-not-found
  (let [path (write-test-file! "not-found"
                               "(+ x 1)\n(- y 2)\n")
        result (sexp-tool/execute
                (sexp-opts path "(* a b)" "(+ a b)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "Could not find"))))

(deftest test-match-partial-not-enough
  (testing "a partial match that doesn't cover the full sexp is not found"
    (let [path (write-test-file! "partial"
                                 "(+ x 1 y 2)\n")
          result (sexp-tool/execute
                  (sexp-opts path "(+ x)" "(+ x 0)"))]
      (is (:is-error result))
      (is (str/includes? (:content result) "Could not find")))))

(deftest test-match-not-found-repair-note
  (testing "unbalanced match_form is rejected — no repair, no match attempt"
    (let [path (write-test-file! "not-found-unbalanced"
                                 "(when (and (a? x) (b? y))\n  (c))\n")
          result (sexp-tool/execute
                  (sexp-opts path "(when (and (a? x) (b? y))"
                             "(when x 1)"))]
      (is (:is-error result))
      (is (str/includes? (:content result) "unbalanced"))
      (is (not (str/includes? (:content result) "Could not find"))))))

(deftest test-match-not-found-no-repair-note
  (testing "balanced match_form that simply isn't there does not claim a repair"
    (let [path (write-test-file! "not-found-balanced"
                                 "(+ x 1)\n")
          result (sexp-tool/execute
                  (sexp-opts path "(+ x 2)" "(+ x 3)"))]
      (is (:is-error result))
      (is (str/includes? (:content result) "Could not find"))
      (is (not (str/includes? (:content result) "auto-repaired"))))))

(deftest test-edit-success-repair-note
  (testing "unbalanced new_form is rejected — no edit, no repair"
    (let [path (write-test-file! "edit-repair-note"
                                 "(defn foo [x] (+ x 1))\n")
          result (sexp-tool/execute
                  (sexp-opts path "(defn foo [x] (+ x 1))"
                             "(defn foo [x] (+ x 1)"))]
      (is (:is-error result))
      (is (str/includes? (:content result) "unbalanced"))
      (is (str/includes? (read-test-file path) "(defn foo [x] (+ x 1))")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; replace_all
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-all
  (let [path (write-test-file! "replace-all"
                               "(defn a [] (inc x))\n(defn b [] (inc y))\n(defn c [] (inc z))\n")
        result (sexp-tool/execute
                (sexp-opts path "(inc x)" "(+ x 1)" :replace_all true))]
    ;; Only (inc x) is replaced, not (inc y) or (inc z)
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(+ x 1)")))))

(deftest test-replace-all-rename-symbol
  (let [path (write-test-file! "replace-all-rename"
                               "(defn a [] (my-fn 1))\n(defn b [] (my-fn 2))\n")
        result (sexp-tool/execute
                (sexp-opts path "my-fn" "renamed-fn" :replace_all true))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "renamed-fn"))
      (is (not (str/includes? content "my-fn"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Multi-expression matching
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-multi-expression-match
  (let [path (write-test-file! "multi-match"
                               "(defn foo []\n  (validate x)\n  (transform x)\n  x)\n")
        result (sexp-tool/execute
                (sexp-opts path "(validate x)\n(transform x)"
                           "(-> x validate transform)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(-> x validate transform)"))))

(deftest test-multi-expression-match-with-extra-whitespace
  (let [path (write-test-file! "multi-ws"
                               "(do  (validate  x)  (transform  x))\n")
        result (sexp-tool/execute
                (sexp-opts path "(validate x)\n(transform x)"
                           "(combined x)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(combined x)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Insert before
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-insert-before
  (let [path (write-test-file! "insert-before"
                               "(defn second [] 2)\n")
        result (sexp-tool/execute
                (sexp-opts path "(defn second [] 2)"
                           "(defn first [] 1)"
                           :operation "insert_before"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (< (.indexOf content "first")
             (.indexOf content "second"))))))

(deftest test-insert-before-expression
  (let [path (write-test-file! "insert-before-expr"
                               "(+ y 1)\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ y 1)"
                           "(println \"before\")"
                           :operation "insert_before"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "println"))
      (is (< (.indexOf content "println")
             (.indexOf content "+ y"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Insert after
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-insert-after
  (let [path (write-test-file! "insert-after"
                               "(defn first [] 1)\n")
        result (sexp-tool/execute
                (sexp-opts path "(defn first [] 1)"
                           "(defn second [] 2)"
                           :operation "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (< (.indexOf content "first")
             (.indexOf content "second"))))))

(deftest test-insert-after-expression
  (let [path (write-test-file! "insert-after-expr"
                               "(prn \"done\")\n")
        result (sexp-tool/execute
                (sexp-opts path "(prn \"done\")"
                           "(println \"after\")"
                           :operation "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "println"))
      (is (< (.indexOf content "prn")
             (.indexOf content "println"))))))

(deftest test-insert-after-keeps-trailing-comment
  ;; Regression: insert_after used to land between the matched expression
  ;; and its same-line trailing comment, detaching the comment onto the
  ;; inserted form. The comment stays with the match, and the inserted
  ;; form gets its own line (it was glued to the next form before).
  (let [path (write-test-file! "insert-after-trailing-cmt"
                               "(prn \"a\") ;; log line
(prn \"b\")\n")
        result (sexp-tool/execute
                (sexp-opts path "(prn \"a\")"
                           "(prn \"inserted\")"
                           :operation "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(prn \"a\") ;; log line")
          "trailing comment stays with the matched form")
      (is (< (.indexOf content "log line")
             (.indexOf content "inserted")))
      (is (str/includes? content "(prn \"inserted\")\n(prn \"b\")")
          "inserted form on its own line, not glued to the next"))))

(deftest test-insert-after-separates-forms
  ;; insert_after must separate the inserted form from the next one with a
  ;; newline — it used to glue them onto one line (cljfmt only added a space).
  (let [path (write-test-file! "insert-after-sep"
                               "(defn first [] 1)\n")
        result (sexp-tool/execute
                (sexp-opts path "(defn first [] 1)"
                           "(defn second [] 2)"
                           :operation "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn first [] 1)\n\n(defn second [] 2)")
          "inserted form is newline-separated from the next"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; replace_all forced false for insert operations
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-all-forced-false-on-insert
  (testing "replace_all is forced false for insert_before/insert_after"
    (let [path (write-test-file! "replace-all-insert"
                                 "(+ x 1)\n(+ x 2)\n")
          result (sexp-tool/execute
                  (sexp-opts path "(+ x 1)" "(new-x)"
                             :operation "insert_before" :replace_all true))]
      (is (not (:is-error result)))
      ;; Should only insert once (replace_all ignored for insert)
      (let [content (read-test-file path)]
        (is (str/includes? content "(new-x)"))
        (is (= 1 (count (re-seq #"\(new-x\)" content))))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting is applied
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-formatting-applied
  (let [path (write-test-file! "formatting"
                               "(defn foo []\n  (+ x 1))\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ x 1)"
                           "(let [a 1\n      b 2]\n(+ a b))"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "let")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Diff is included in result (when available)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-diff-in-result
  (let [path (write-test-file! "diff-result"
                               "(foo)\n")
        result (sexp-tool/execute
                (sexp-opts path "(foo)" "(bar)"))]
    (is (not (:is-error result)))
    (let [diff (get-in result [:details :diff])]
      (is (string? diff))
      (is (str/includes? diff "-1 (foo)"))
      (is (str/includes? diff "+1 (bar)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Identical replacement (no-op)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-identical-replacement
  (let [path (write-test-file! "identical"
                               "(+ x 1)\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ x 1)" "(+ x 1)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(+ x 1)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Nested expressions
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-nested-expression-replace
  (let [path (write-test-file! "nested"
                               "(when (pred? x)\n  (do-something x))\n")
        result (sexp-tool/execute
                (sexp-opts path "(do-something x)" "(do-something x :extra)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) ":extra"))))

(deftest test-deeply-nested-replace
  (let [path (write-test-file! "deep-nested"
                               "(defn f []\n  (let [x (g (+ 1 2))]\n    (h x)))\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ 1 2)" "(+ 1 2 3)"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(+ 1 2 3)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Comments in source
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-match-with-comments-in-source
  (let [path (write-test-file! "comments"
                               ";; before\n(+ x 1) ;; inline\n;; after\n")
        result (sexp-tool/execute
                (sexp-opts path "(+ x 1)" "(* x 2)"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(* x 2)"))
      (is (str/includes? content ";; before")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Regex and string literals
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-regex-literal
  (let [path (write-test-file! "regex"
                               "(re-pattern \"\\\\d+\")\n")
        result (sexp-tool/execute
                (sexp-opts path "(re-pattern \"\\\\d+\")"
                           "(re-pattern \"[0-9]+\")"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "[0-9]+"))))

(deftest test-replace-string-literal
  (let [path (write-test-file! "string-lit"
                               "\"hello world\"\n")
        result (sexp-tool/execute
                (sexp-opts path "\"hello world\"" "\"goodbye world\""))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "\"goodbye world\""))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Multiple forms in file — only match is replaced
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-targeted-only-replaced
  (let [path (write-test-file! "targeted"
                               "(+ x 1)\n(- y 2)\n(* z 3)\n")
        result (sexp-tool/execute
                (sexp-opts path "(- y 2)" "(+ y 2)"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(+ x 1)"))
      (is (str/includes? content "(+ y 2)"))
      (is (str/includes? content "(* z 3)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; UTF-8 content
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-utf8-preserved
  (let [path (write-test-file! "utf8"
                               "\"Привет\"\n")
        result (sexp-tool/execute
                (sexp-opts path "\"Привет\"" "\"你好\""))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "你好"))))

;; ─── Quiet titles ───────────────────────────────────────────────────────────

(deftest test-title
  (testing "match preview"
    (is (= "clojure_edit_replace_sexp replace (+ x 1) in /a.clj"
           (sexp-tool/title {:file_path "/a.clj" :match_form "(+ x 1)\n(+ y 2)"
                             :operation "replace"}))))
  (testing "long match capped at 60 chars"
    (let [long-match (apply str (repeat 100 "x"))
          t (sexp-tool/title {:file_path "/a.clj" :match_form long-match})]
      (is (str/includes? t (str (apply str (repeat 60 "x")) "…")))))
  (testing "missing path degrades to nil"
    (is (nil? (sexp-tool/title {})))
    (is (nil? (sexp-tool/title nil)))))

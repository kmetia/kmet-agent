(ns kmet.extensions.clojure.edit-tool-test
  "Tests for the clojure_edit tool (kmet.extensions.clojure.edit-tool namespace).
   Each test writes a temp .clj file, calls edit-tool/execute, and asserts
   on the result map and the written file content."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extensions.clojure.edit-tool :as edit-tool]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(def ^:private test-dir "target/edit-tool-tests")

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

(defn- edit-opts
  "Shorthand for building an execute opts map."
  [file_path form_type form_identifier content & [operation]]
  (cond-> {:file_path       file_path
           :form_type       form_type
           :form_identifier form_identifier
           :content         content}
    operation (assoc :operation operation)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Parameter validation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-missing-file-path
  (let [result (edit-tool/execute {:form_type "defn"
                                   :form_identifier "foo"
                                   :content "(defn foo [])"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "file_path"))))

(deftest test-missing-form-type
  (let [path (write-test-file! "missing-form-type" "(defn foo [] nil)")
        result (edit-tool/execute {:file_path path
                                   :form_identifier "foo"
                                   :content "(defn foo [] nil)"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "form_type"))))

(deftest test-missing-form-identifier
  (let [path (write-test-file! "missing-form-id" "(defn foo [] nil)")
        result (edit-tool/execute {:file_path path
                                   :form_type "defn"
                                   :content "(defn foo [] nil)"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "form_identifier"))))

(deftest test-missing-content
  ;; content stays required (same convention as the edit tool's newText):
  ;; omitting it is an error, deletion uses an explicit empty string.
  (let [path (write-test-file! "missing-content" "(defn foo [] nil)")
        result (edit-tool/execute {:file_path path
                                   :form_type "defn"
                                   :form_identifier "foo"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "content"))
    (is (str/includes? (:content result) "empty string"))
    (is (str/includes? (read-test-file path) "(defn foo [] nil)"))))

(deftest test-delete-with-empty-content
  ;; Deletion follows the edit tool's convention: a blank content with
  ;; operation "replace" removes the form and its line.
  (let [path (write-test-file! "delete-empty"
                               "(defn a [] 1)\n\n(defn b [] 2)\n\n(defn c [] 3)\n")
        result (edit-tool/execute (edit-opts path "defn" "b" ""))]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "Edit applied"))
    (let [content (read-test-file path)]
      (is (not (str/includes? content "defn b")))
      (is (str/includes? content "(defn a [] 1)\n\n(defn c [] 3)")))))

(deftest test-delete-first-form
  (let [path (write-test-file! "delete-first"
                               "(defn a [] 1)\n\n(defn b [] 2)\n")
        result (edit-tool/execute (edit-opts path "defn" "a" ""))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (not (str/includes? content "defn a")))
      (is (str/includes? content "(defn b [] 2)")))))

(deftest test-delete-last-form
  (let [path (write-test-file! "delete-last"
                               "(defn a [] 1)\n\n(defn b [] 2)\n")
        result (edit-tool/execute (edit-opts path "defn" "b" ""))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (not (str/includes? content "defn b")))
      (is (str/includes? content "(defn a [] 1)")))))

(deftest test-insert-with-empty-content-rejected
  ;; Insert operations need content; an empty content cannot insert.
  (doseq [op ["insert_before" "insert_after"]]
    (testing op
      (let [path (write-test-file! (str "insert-empty-" op)
                                   "(defn foo [] nil)\n")
            result (edit-tool/execute (edit-opts path "defn" "foo" "" op))]
        (is (:is-error result))
        (is (str/includes? (:content result) "content"))
        (is (str/includes? (read-test-file path) "(defn foo [] nil)"))))))

(deftest test-file-not-found
  (let [result (edit-tool/execute {:file_path "target/nonexistent-xyz.clj"
                                   :form_type "defn"
                                   :form_identifier "foo"
                                   :content "(defn foo [] nil)"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "not found"))))

(deftest test-non-clojure-file
  (let [path (do (ensure-test-dir!)
                 (str test-dir "/notes.txt"))
        _    (spit path "(defn foo [])")
        result (edit-tool/execute {:file_path path
                                   :form_type "defn"
                                   :form_identifier "foo"
                                   :content "(defn foo [] nil)"})]
    (is (:is-error result))
    ;; the rejection names the actual reason: the file's real extension
    (is (str/includes? (:content result) "Not a Clojure file"))
    (is (str/includes? (:content result) "has extension '.txt'"))))

(deftest test-comment-form-type-rejected
  (let [path (write-test-file! "comment-reject" "(defn foo [] nil)")
        result (edit-tool/execute {:file_path path
                                   :form_type "comment"
                                   :form_identifier "foo"
                                   :content "(comment foo)"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "comment"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defn
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defn
  (let [path (write-test-file! "replace-defn"
                               "(defn greet [name]\n  (str \"Hello \" name))\n")
        result (edit-tool/execute (edit-opts path "defn" "greet"
                                             "(defn greet [name]\n  (str \"Hi \" name))"))]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "Edit applied"))
    (is (str/includes? (read-test-file path) "\"Hi \""))))

(deftest test-replace-defn-multi-line
  (let [path (write-test-file! "replace-defn-ml"
                               "(defn compute\n  [x y]\n  (+ x y))\n")
        result (edit-tool/execute
                (edit-opts path "defn" "compute"
                           "(defn compute\n  [x y]\n  (* x y))"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "* x y"))
      (is (not (str/includes? content "+ x y"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a def
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-def
  (let [path (write-test-file! "replace-def"
                               "(def max-retries 3)\n")
        result (edit-tool/execute
                (edit-opts path "def" "max-retries"
                           "(def max-retries 5)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "5"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defn-
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defn-private
  (let [path (write-test-file! "replace-defn-priv"
                               "(defn- helper [x] (inc x))\n")
        result (edit-tool/execute
                (edit-opts path "defn-" "helper"
                           "(defn- helper [x] (+ x 2))"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "+ x 2"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defmacro
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defmacro
  (let [path (write-test-file! "replace-defmacro"
                               "(defmacro when-let* [binding & body]\n  `(let ~binding (when ~(first binding) ~@body)))\n")
        result (edit-tool/execute
                (edit-opts path "defmacro" "when-let*"
                           "(defmacro when-let* [binding & body]\n  `(let ~binding ~@body))"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "defmacro"))
      (is (str/includes? content "`(let ~binding ~@body)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a deftest
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-deftest
  (let [path (write-test-file! "replace-deftest"
                               "(deftest my-test\n  (is (= 1 1)))\n")
        result (edit-tool/execute
                (edit-opts path "deftest" "my-test"
                           "(deftest my-test\n  (is (= 2 2)))"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(= 2 2)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace an ns declaration
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-ns
  (let [path (write-test-file! "replace-ns"
                               "(ns my.app.core\n  (:require [clojure.string :as str]))\n")
        result (edit-tool/execute
                (edit-opts path "ns" "my.app.core"
                           "(ns my.app.core\n  (:require [clojure.string :as str]\n            [clojure.set :as set]))"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "clojure.set")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defrecord
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defrecord
  (let [path (write-test-file! "replace-defrecord"
                               "(defrecord Point [x y])\n")
        result (edit-tool/execute
                (edit-opts path "defrecord" "Point"
                           "(defrecord Point [x y z])"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "[x y z]"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defprotocol
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defprotocol
  (let [path (write-test-file! "replace-defprotocol"
                               "(defprotocol IRender\n  (render [this width]))\n")
        result (edit-tool/execute
                (edit-opts path "defprotocol" "IRender"
                           "(defprotocol IRender\n  (render [this width])\n  (invalidate [this]))"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "invalidate"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defmethod (unqualified name, dispatch from content)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defmethod-unqualified
  (let [path (write-test-file! "replace-defmethod-uq"
                               "(defmethod area :square\n  [{:keys [w]}] (* w w))\n")
        result (edit-tool/execute
                (edit-opts path "defmethod" "area :square"
                           "(defmethod area :square\n  [{:keys [side]}] (* side side))"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "side"))
      (is (not (str/includes? content ":keys [w]"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace a defmethod (qualified name)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-defmethod-qualified
  (let [path (write-test-file! "replace-defmethod-q"
                               "(defmethod shape/area :rectangle\n  [{:keys [w h]}] (* w h))\n")
        result (edit-tool/execute
                (edit-opts path "defmethod" "shape/area :rectangle"
                           "(defmethod shape/area :rectangle\n  [{:keys [w h]}] (long (* w h)))"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "long (* w h)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Insert before
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-insert-before-defn
  (let [path (write-test-file! "insert-before"
                               "(defn second-fn [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "second-fn"
                           "(defn first-fn [] 42)"
                           "insert_before"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      ;; first-fn should appear before second-fn
      (is (< (.indexOf content "first-fn")
             (.indexOf content "second-fn"))))))

(deftest test-insert-before-with-comment
  (let [path (write-test-file! "insert-before-cmt"
                               ";; some comment\n(defn target [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "target"
                           "(defn before-target [] 1)"
                           "insert_before"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "before-target")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Insert after
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-insert-after-defn
  (let [path (write-test-file! "insert-after"
                               "(defn first-fn [] 42)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "first-fn"
                           "(defn second-fn [] 99)"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (< (.indexOf content "first-fn")
             (.indexOf content "second-fn"))))))

(deftest test-insert-after-keeps-trailing-comment
  ;; Regression: insert_after used to land between the anchor form and its
  ;; same-line trailing comment, detaching the comment onto the inserted
  ;; form. The comment must stay on the anchor's line, with the inserted
  ;; form following on its own line, blank-separated on both sides.
  (let [path (write-test-file! "insert-after-trailing-cmt"
                               "(defn probe-a [x] (* x 2)) ;; trailing comment on a\n\n(defn probe-b [x] (+ x 1))\n")
        result (edit-tool/execute
                (edit-opts path "defn" "probe-a"
                           "(defn probe-c [x] (- x 3))"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn probe-a [x] (* x 2)) ;; trailing comment on a")
          "trailing comment stays on the anchor form's line")
      (is (< (.indexOf content "trailing comment on a")
             (.indexOf content "probe-c"))
          "inserted form comes after the anchor's comment")
      (is (< (.indexOf content "probe-c")
             (.indexOf content "probe-b"))
          "next form still follows"))))

(deftest test-insert-after-keeps-next-form-doc-comment
  ;; A comment starting on its own line leads the NEXT form — the inserted
  ;; form goes above it, the comment stays with its form.
  (let [path (write-test-file! "insert-after-doc-cmt"
                               "(defn probe-a [x] (* x 2))\n\n;; doc for probe-b\n(defn probe-b [x] (+ x 1))\n")
        result (edit-tool/execute
                (edit-opts path "defn" "probe-a"
                           "(defn probe-c [x] (- x 3))"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (< (.indexOf content "probe-c")
             (.indexOf content "doc for probe-b"))
          "own-line comment stays with the next form"))))

(deftest test-insert-after-trailing-comment-at-eof
  (let [path (write-test-file! "insert-after-cmt-eof"
                               "(defn probe-a [x] (* x 2)) ;; last form\n")
        result (edit-tool/execute
                (edit-opts path "defn" "probe-a"
                           "(defn probe-c [x] (- x 3))"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn probe-a [x] (* x 2)) ;; last form")
          "comment stays with the anchor at EOF"))))

(deftest test-alias-qualified-macro-matched-by-plain-tag
  ;; form_type "deftest" must match (t/deftest ...): the tag check compares
  ;; the unqualified name (consistent with the similar-matches hint) — it
  ;; used to fail with "Could not find form" unless the full "t/deftest"
  ;; was given.
  (let [path (write-test-file! "qualified-deftest"
                               "(ns foo (:require [clojure.test :as t]))\n\n(t/deftest my-test\n  (t/is (= 1 1)))\n")
        result (edit-tool/execute
                (edit-opts path "deftest" "my-test"
                           "(t/deftest other-test\n  (t/is (= 2 2)))"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "other-test")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Not found — error + similar matches
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-form-not-found
  (let [path (write-test-file! "not-found"
                               "(defn alpha [] 1)\n(defn beta [] 2)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "gamma"
                           "(defn gamma [] 3)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "Could not find"))
    (is (str/includes? (:content result) "gamma"))))

(deftest test-similar-matches-reported
  (testing "defmethod with same base name reports similar dispatch values"
    (let [path (write-test-file! "similar-dm"
                                 "(defmethod area :square\n  [{:keys [w]}] (* w w))\n(defmethod area :rectangle\n  [{:keys [w h]}] (* w h))\n")
          result (edit-tool/execute
                  (edit-opts path "defmethod" "area :circle"
                             "(defmethod area :circle\n  [{:keys [r]}] (* 3.14 r r))"))]
      (is (:is-error result))
      (is (str/includes? (:content result) "Similar forms found"))
      (is (str/includes? (:content result) "area")))))

(deftest test-no-similar-for-different-names
  (testing "forms with different base names are not reported as similar"
    (let [path (write-test-file! "no-similar"
                                 "(defn alpha [] 1)\n(defn beta [] 2)\n")
          result (edit-tool/execute
                  (edit-opts path "defn" "gamma"
                             "(defn gamma [] 3)"))]
      (is (:is-error result))
      (is (not (str/includes? (:content result) "Similar"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Form not found for defmethod
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-defmethod-not-found
  (let [path (write-test-file! "defmethod-not-found"
                               "(defmethod area :square\n  [{:keys [w]}] (* w w))\n")
        result (edit-tool/execute
                (edit-opts path "defmethod" "area :circle"
                           "(defmethod area :circle\n  [{:keys [r]}] (* Math/PI r r))"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "Could not find"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Default operation is replace
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-default-operation-is-replace
  (let [path (write-test-file! "default-op"
                               "(defn old [] 0)\n")
        ;; No :operation key — should default to replace
        result (edit-tool/execute {:file_path path
                                   :form_type "defn"
                                   :form_identifier "old"
                                   :content "(defn new [] 1)"})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "new"))
      (is (not (str/includes? content "old"))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting is applied
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-formatting-applied
  (let [path (write-test-file! "formatting"
                               "(defn foo []\n  42)\n")
        ;; Malformed content — too much indentation
        result (edit-tool/execute
                (edit-opts path "defn" "foo"
                           "(defn foo []\n        (let [x 1]\n          (+ x 2)))"))]
    (is (not (:is-error result)))
    ;; The file should have formatted code (less indentation)
    (let [content (read-test-file path)]
      (is (str/includes? content "let")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Multiple forms in the same file — only target is replaced
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-targeted-replacement
  (let [path (write-test-file! "targeted"
                               "(defn alpha [] 1)\n(defn beta [] 2)\n(defn gamma [] 3)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "beta"
                           "(defn beta [] 99)"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      ;; alpha and gamma unchanged
      (is (str/includes? content "(defn alpha [] 1)"))
      (is (str/includes? content "(defn gamma [] 3)"))
      ;; beta changed
      (is (str/includes? content "(defn beta [] 99)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Diff is included in result (when available — may be nil on read-only tmpfs)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-diff-in-result
  (let [path (write-test-file! "diff-result"
                               "(defn old-fn [] 0)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "old-fn"
                           "(defn new-fn [] 1)"))]
    (is (not (:is-error result)))
    (let [diff (get-in result [:details :diff])]
      (is (string? diff))
      (is (str/includes? diff "-1 (defn old-fn [] 0)"))
      (is (str/includes? diff "+1 (defn new-fn [] 1)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; No-op replacement (identical content) — still succeeds
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-identical-replacement
  (let [path (write-test-file! "identical"
                               "(defn same [] 42)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "same"
                           "(defn same [] 42)"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "(defn same [] 42)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; UTF-8 content preserved
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-utf8-preserved
  (let [path (write-test-file! "utf8"
                               "(defn greeting [] \"Привет мир\")\n")
        result (edit-tool/execute
                (edit-opts path "defn" "greeting"
                           "(defn greeting [] \"你好世界\")"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "你好世界"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; String and regex literals in content
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-string-and-regex-content
  (let [path (write-test-file! "string-regex"
                               "(defn pattern [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "pattern"
                           "(defn pattern []\n  (re-pattern \"\\\\d+\"))"))]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "re-pattern"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Replace with a comment (deletion)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-with-comment
  (let [path (write-test-file! "replace-comment"
                               "(defn obsolete [] nil)\n(defn keep [] 1)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "obsolete"
                           ";; obsolete function removed"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "obsolete function removed"))
      (is (str/includes? content "(defn keep [] 1)")))))

(deftest test-fragment-content-rejected
  ;; A fragment like "(defn- filter-kind" (missing the arg vector) is
  ;; unbalanced and rejected outright — it used to be auto-repaired into a
  ;; bodyless "(defn- filter-kind)" and silently written to the file.
  (let [path (write-test-file! "fragment-reject"
                               "(defn- filter-kind\n  [f]\n  :ok)\n")
        result (edit-tool/execute
                (edit-opts path "defn-" "filter-kind"
                           "(defn- filter-kind"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "filter-kind\n  [f]"))))

(deftest test-wrong-form-type-rejected
  ;; Content that parses but is not the requested form type is rejected.
  (let [path (write-test-file! "wrong-type-reject"
                               "(defn target [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "target"
                           "(+ x 1)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "does not start with"))
    (is (str/includes? (read-test-file path) "(defn target [] nil)"))))

(deftest test-incomplete-def-rejected
  ;; A complete-but-incomplete "def" form like "(def foo)" (no value) is
  ;; rejected by structural validation.
  (let [path (write-test-file! "incomplete-def"
                               "(def foo 1)\n")
        result (edit-tool/execute
                (edit-opts path "def" "foo"
                           "(def foo)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "expected (def name value)"))
    (is (str/includes? (read-test-file path) "(def foo 1)"))))

(deftest test-unbalanced-content-rejected
  ;; Unbalanced content is rejected outright — no auto-repair. The
  ;; rejection names the actual error: kind, location, opened-at.
  (let [path (write-test-file! "unbalanced-reject"
                               "(defn foo [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "foo"
                           "(defn foo [x] (* x 2)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "unbalanced"))
    (is (str/includes? (:content result) "Unbalanced delimiters in content"))
    (is (str/includes? (:content result) "at line 1"))
    (is (str/includes? (:content result) "expected ')' to close '('"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "(defn foo [] nil)"))))

(deftest test-syntax-error-content-rejected-explicitly
  ;; Balanced-but-unparsable content used to slip past the unbalance check
  ;; and fail later with a confusing pipeline error; it is now rejected up
  ;; front with the reader's actual message and position.
  (let [path (write-test-file! "syntax-reject"
                               "(defn foo [] nil)\n")
        result (edit-tool/execute
                (edit-opts path "defn" "foo"
                           "(def x 1) #"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "invalid syntax"))
    (is (str/includes? (:content result) "Syntax error in content"))
    (is (str/includes? (:content result) "at line 1"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "(defn foo [] nil)"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Insert after defmethod with qualified name
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-empty-ns-rejected
  ;; "(ns)" (no namespace name) is structurally broken — must be rejected,
  ;; not silently written over the file's real ns declaration.
  (let [path (write-test-file! "empty-ns"
                               "(ns foo.bar (:require [clojure.string :as str]))\n")
        result (edit-tool/execute
                (edit-opts path "ns" "foo.bar"
                           "(ns)"))]
    (is (:is-error result))
    (is (str/includes? (:content result) "namespace name"))
    ;; the file must be untouched
    (is (str/includes? (read-test-file path) "foo.bar"))))

(deftest test-insert-after-defmethod-qualified
  (let [path (write-test-file! "insert-after-dm"
                               "(defmethod shape/area :square\n  [{:keys [w]}] (* w w))\n")
        result (edit-tool/execute
                (edit-opts path "defmethod" "shape/area :square"
                           "(defmethod shape/area :circle\n  [{:keys [r]}] (* 3.14 r r))"
                           "insert_after"))]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "shape/area :circle"))
      (is (< (.indexOf content ":square")
             (.indexOf content ":circle"))))))

;; ─── Quiet titles ───────────────────────────────────────────────────────────

(deftest test-title
  (testing "full args"
    (is (= "clojure_edit replace defn foo in /a/b.clj"
           (edit-tool/title {:file_path "/a/b.clj" :form_type "defn"
                             :form_identifier "foo" :operation "replace"}))))
  (testing "string keys and home folding"
    (let [home (System/getProperty "user.home" "")]
      (is (= (str "clojure_edit replace defn foo in ~" "/b.clj")
             (edit-tool/title {"file_path" (str home "/b.clj") "form_type" "defn"
                               "form_identifier" "foo" "operation" "replace"})))))
  (testing "defaults"
    (is (= "clojure_edit replace in /a/b.clj"
           (edit-tool/title {:file_path "/a/b.clj"}))))
  (testing "missing path degrades to nil"
    (is (nil? (edit-tool/title {})))
    (is (nil? (edit-tool/title nil)))))

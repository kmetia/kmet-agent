(ns kmet.extensions.clojure.edit-util-test
  "Tests for the shared edit-util namespace."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extensions.clojure.edit-util :as util]
            [kmet.libs.edit-diff :as edit-diff]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-slurp-utf8
  (fs/create-dirs "target/util-tests")
  (spit "target/util-tests/utf8.clj" "(def x 42)\n" :encoding "UTF-8")
  (is (= "(def x 42)\n" (util/slurp-utf8 "target/util-tests/utf8.clj"))))

(deftest test-spit-utf8
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/out.clj" "(def y 99)\n")
  (is (= "(def y 99)\n" (slurp "target/util-tests/out.clj" :encoding "UTF-8"))))

(deftest test-utf8-roundtrip
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/rt.clj" "(def greeting \"Привет\")\n")
  (is (= "(def greeting \"Привет\")\n" (util/slurp-utf8 "target/util-tests/rt.clj"))))

(deftest test-clojure-file-predicate
  (testing "accepts all Clojure-family extensions, case-insensitively"
    (is (true? (util/clojure-file? "a.clj")))
    (is (true? (util/clojure-file? "a.CLJS")))
    (is (true? (util/clojure-file? "a.cljc")))
    (is (true? (util/clojure-file? "a.cljd")))
    (is (true? (util/clojure-file? "a.bb")))
    (is (true? (util/clojure-file? "a.edn")))
    (is (true? (util/clojure-file? "a.lpy")))
    (is (true? (util/clojure-file? "/deep/path/to/a.clj"))))
  (testing "rejects non-Clojure and extensionless paths"
    (is (false? (util/clojure-file? "a.txt")))
    (is (false? (util/clojure-file? "a.md")))
    (is (false? (util/clojure-file? "a")))
    (is (false? (util/clojure-file? "a.clj.bak")))
    (is (false? (util/clojure-file? "a.clj/notes.txt"))))
  (testing "nil/blank are rejected (nil predicate returns nil, not false)"
    (is (nil? (util/clojure-file? nil)))
    (is (false? (util/clojure-file? "")))))

(deftest test-not-clojure-file-msg
  (testing "names the offending extension"
    (is (= "Not a Clojure file: foo.txt has extension '.txt' — clojure_edit only operates on .clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy files."
           (util/not-clojure-file-msg "clojure_edit" "foo.txt"))))
  (testing "extensionless paths say so explicitly"
    (is (str/includes? (util/not-clojure-file-msg "clojure_edit" "notes") "has no file extension")))
  (testing "the tool name and accepted list are stated"
    (let [msg (util/not-clojure-file-msg "clojure_paren_repair" "a.clj.bak")]
      (is (str/includes? msg ".bak"))
      (is (str/includes? msg "clojure_paren_repair"))
      (is (str/includes? msg ".clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint repair
;; ═══════════════════════════════════════════════════════════════════════════════

;; ═══════════════════════════════════════════════════════════════════════════════
;; Repair (used by clojure_paren_repair only)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-repair-delimiters
  (testing "nil / empty input is safe"
    (is (= [nil false] (util/repair-delimiters nil)))
    (is (= ["" false] (util/repair-delimiters ""))))
  (testing "already balanced"
    (is (= ["(a b)" false] (util/repair-delimiters "(a b)"))))
  (testing "repaired result is balanced"
    (let [[code fixed?] (util/repair-delimiters "(defn f [x]")]
      (is fixed?)
      (is (false? (util/delimiter-error? code))))))

(deftest test-delimiter-error?
  (testing "balanced code has no delimiter error"
    (is (false? (util/delimiter-error? "(defn foo [x] (+ x 1))"))))
  (testing "unbalanced opens are detected"
    (is (true? (util/delimiter-error? "(defn foo [x] (+ x 1")))
    (is (true? (util/delimiter-error? "(defn foo [x]"))))
  (testing "extra closes are detected"
    (is (true? (util/delimiter-error? "(defn foo [x] (+ x 1)))"))))
  (testing "strings containing delimiters are not fooled"
    (is (false? (util/delimiter-error? "(def s \"(unclosed\")"))))
  (testing "comments containing delimiters are not fooled"
    (is (false? (util/delimiter-error? "(def x 1) ; (comment"))))
  (testing "reader conditionals parse"
    (is (false? (util/delimiter-error? "#?(:clj (def x 1) :cljs (def x 2))"))))
  (testing "regex literals parse"
    (is (false? (util/delimiter-error? "(re-find #\"[0-9]+\" \"abc123\")"))))
  (testing "non-delimiter parse errors are not delimiter errors"
    ;; a genuinely bad token is not a delimiter error — edamame throws
    ;; without :opened-delimiter
    (is (false? (util/delimiter-error? "(def x 1) @")))))

(deftest test-parse-problem
  (testing "clean source has no problem"
    (is (nil? (util/parse-problem "x" "(defn f [x] (+ x 1))"))))
  (testing "unbalanced opener: delimiter kind + precise report"
    (let [{:keys [kind report]} (util/parse-problem "content" "(defn foo [x]")]
      (is (= :delimiter kind))
      (is (str/includes? report "Unbalanced delimiters in content"))
      (is (str/includes? report "at line 1, col 14"))
      (is (str/includes? report "expected ')' to close '('"))
      (is (str/includes? report "opened at line 1, col 1"))))
  (testing "stray closer names the offending character, never ''"
    (let [{:keys [report]} (util/parse-problem "a.clj" "(def x (+ 1 2)))")]
      (is (str/includes? report "unexpected ')'"))
      (is (not (str/includes? report "''")))))
  (testing "non-delimiter reader error: syntax kind + location + message"
    (let [{:keys [kind report]} (util/parse-problem "match_form" "foo #")]
      (is (= :syntax kind))
      (is (str/includes? report "Syntax error in match_form"))
      (is (str/includes? report "at line 1, col 6"))
      (is (str/includes? report "Unexpected EOF"))))
  (testing "unterminated string is a delimiter problem (parinferish-relevant)"
    (let [{:keys [kind]} (util/parse-problem
                          "f.clj" "(def s \"unterminated")]
      (is (= :delimiter kind))))
  (testing "raw non-edamame failures yield no problem (not classifiable)"
    ;; #^meta on a literal makes edamame throw a raw ClassCastException
    (is (nil? (util/parse-problem "f.clj" "(def x #^bad 1)")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-diff-display-identical
  (is (nil? (edit-diff/generate-display-diff "hello" "hello"))))

(deftest test-diff-display-different
  (let [result (edit-diff/generate-display-diff "line1\n" "line2\n")]
    (is (string? result))
    (is (str/includes? result "-1 line1"))
    (is (str/includes? result "+1 line2"))))

(deftest test-diff-display-trailing-newline
  (testing "trailing-newline-only difference yields nil (no crash, no empty diff)"
    (is (nil? (edit-diff/generate-display-diff "(defn foo [] nil)" "(defn foo [] nil)\n")))
    (is (nil? (edit-diff/generate-display-diff "a\n" "a\n\n")))))

(deftest test-diff-display-pure-add-del
  (testing "pure line addition/deletion produce proper diffs"
    (let [add (edit-diff/generate-display-diff "a\n" "a\nb\n")]
      (is (str/includes? add "+2 b")))
    (let [del (edit-diff/generate-display-diff "a\nb\n" "a\n")]
      (is (str/includes? del "-2 b")))))

(deftest test-diff-display-empty-content
  (testing "empty vs content transitions"
    (is (str/includes? (edit-diff/generate-display-diff "" "x\n") "+1 x"))
    (is (str/includes? (edit-diff/generate-display-diff "x\n" "") "-1 x"))
    (is (nil? (edit-diff/generate-display-diff "" "")))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-format-source-string
  (testing "formats malformatted Clojure"
    (let [formatted (util/format-source-string "(defn foo[x](+ x 1))")]
      (is (string? formatted))
      (is (str/includes? formatted "defn")))))

(deftest test-format-source-string-invalid
  (testing "returns original on parse error"
    (let [input "(defn foo ["]
      (is (= input (util/format-source-string input))))))

(deftest test-re-indent-to-column
  (is (= "foo\n  bar\n  baz"
         (util/re-indent-to-column "foo\nbar\nbaz" 3)))
  (testing "col 1 is no-op"
    (is (= "foo\nbar" (util/re-indent-to-column "foo\nbar" 1)))))

(deftest test-format-form-in-isolation
  (let [result (util/format-form-in-isolation "(defn foo[](let [x 1](+ x 2)))" 5)]
    (is (string? result))
    (is (str/includes? result "let"))))

(deftest test-project-fmt-opts
  (testing "finds cljfmt.edn walking up from the file's directory"
    (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
          _   (spit (fs/file dir "cljfmt.edn")
                    "{:extra-indents {mydef [[:block 3] [:inner 1]]}}")
          clj (fs/file dir "sample.clj")]
      (spit clj "")
      (try
        (let [opts (util/project-fmt-opts (str clj))]
          (is (= [[:block 3] [:inner 1]]
                 (get (:extra-indents opts) 'mydef)))
          ;; the tool defaults still apply (cljfmt's own defaults merged)
          (is (true? (:remove-consecutive-blank-lines? opts))))
        (finally (fs/delete-tree dir)))))
  (testing "no config file — plain cljfmt defaults"
    (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
          clj (fs/file dir "sample.clj")]
      (spit clj "")
      (try
        (let [opts (util/project-fmt-opts (str clj))]
          (is (not (contains? (:extra-indents opts) 'mydef))))
        (finally (fs/delete-tree dir)))))
  (testing "bare filename — searches from the current directory"
    (is (map? (util/project-fmt-opts "sample.clj")))))

(deftest test-format-honors-project-extra-indents
  (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
        _   (spit (fs/file dir "cljfmt.edn")
                  "{:extra-indents {mydef [[:block 3] [:inner 1]]}}")
        clj (fs/file dir "sample.clj")]
    (spit clj "")
    (try
      (let [opts (util/project-fmt-opts (str clj))
            src  "(mydef Foo nil\n[container search-input list-container state-atom]\n(render [this w] (str a))\n(handle-input [this d] (cond\n(keys/matches-key? d \"x\") (do (foo this) nil)\n:else nil)))\n"
            out  (util/format-source-string src opts)]
        ;; [:block 3]: method forms at index >= 3 start on their own line
        ;; at block indent, NOT aligned under the first argument
        (is (str/includes? out "\n  (render [this w] (str a))"))
        (is (str/includes? out "\n  (handle-input [this d] (cond"))
        (is (not (str/includes? out "\n       (render"))))
      (finally (fs/delete-tree dir)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-walk-back-to-non-comment
  (testing "walks backward past comments and whitespace to a non-comment node"
    ;; walk-back-to-non-comment uses z/prev* which traverses the full tree.
    ;; Verify it finds a non-comment node going backward.
    (let [parsed   (p/parse-string-all ";; comment\n;; another\n(+ x 1)")
          zloc     (z/of-node parsed)
          ;; Navigate into the list node: down goes to the first child (+)
          list-loc (-> zloc z/down z/right) ;; skip comment, get to list
          back     (util/walk-back-to-non-comment list-loc)]
      (is (some? back))
      (is (not (#{:whitespace :comment} (node/tag (z/node back)))))))
  (testing "returns the node itself when no preceding comment"
    (let [zloc (z/of-string "(+ x 1)")
          expr (z/down zloc)
          back (util/walk-back-to-non-comment expr)]
      (is (some? back)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper form replacement
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-form-normal
  (let [zloc  (z/of-string "(+ x 1)")
        replaced (util/replace-form zloc "(+ x 2)")]
    (is (= "(+ x 2)" (z/root-string replaced)))))

(deftest test-replace-form-with-comment
  (testing "replacing with a comment removes adjacent comments"
    (let [zloc     (z/of-string ";; old\n(+ x 1)")
          ;; Navigate to the (+ x 1) form
          expr     (-> zloc z/down z/right)
          replaced (util/replace-form expr ";; replaced")]
      (is (str/includes? (z/root-string replaced) "replaced")))))

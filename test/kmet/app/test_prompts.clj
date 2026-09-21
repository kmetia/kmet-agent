(ns kmet.app.test-prompts
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.prompts :as prompts]))

;; ─── parse-command-args (pi: parseCommandArgs) ─────────────────────────────

(t/deftest test-parse-command-args
  (t/is (= ["a" "b"] (prompts/parse-command-args "a b")))
  (t/is (= [] (prompts/parse-command-args "")))
  (t/is (= [] (prompts/parse-command-args "   ")))
  (t/is (= ["a" "b"] (prompts/parse-command-args "  a   b  ")))
  (t/testing "JS /\\s/ whitespace (NBSP-family, BOM) splits args"
    (t/is (= ["a" "b"] (prompts/parse-command-args "a\u00a0b")))
    (t/is (= ["a" "b"] (prompts/parse-command-args "a\ufeffb")))
    (t/is (= ["a" "b"] (prompts/parse-command-args "a\u3000b")))
    (t/is (= ["a\u2027b"] (prompts/parse-command-args "a\u2027b"))))
  (t/testing "quoted strings keep their content"
    (t/is (= ["a" "b c"] (prompts/parse-command-args "a \"b c\"")))
    (t/is (= ["a" "b c"] (prompts/parse-command-args "a 'b c'")))
    (t/is (= ["a"] (prompts/parse-command-args "a \"\"")))
    (t/is (= ["ab"] (prompts/parse-command-args "\"a\"\"b\"")))
    (t/is (= ["a b" "c d" "e"] (prompts/parse-command-args "'a b' \"c d\" e"))))
  (t/testing "unterminated quote accumulates to end (pi)"
    (t/is (= ["a" "b c"] (prompts/parse-command-args "a \"b c")))))

;; ─── substitute-args (pi: substituteArgs) ─────────────────────────────────

(t/deftest test-substitute-args
  (let [args ["one" "two three"]]
    (t/testing "positional args"
      (t/is (= "one" (prompts/substitute-args "$1" args)))
      (t/is (= "two three" (prompts/substitute-args "$2" args)))
      (t/is (= "" (prompts/substitute-args "$9" args))))
    (t/testing "all args"
      (t/is (= "one two three" (prompts/substitute-args "$@" args)))
      (t/is (= "one two three" (prompts/substitute-args "$ARGUMENTS" args))))
    (t/testing "defaults"
      (t/is (= "one" (prompts/substitute-args "${1:-def}" args)))
      (t/is (= "def" (prompts/substitute-args "${2:-def}" ["one"])))
      (t/is (= "def" (prompts/substitute-args "${2:-def}" ["one" ""])))
      (t/is (= "one two three" (prompts/substitute-args "${@:-none}" args)))
      (t/is (= "none" (prompts/substitute-args "${@:-none}" [])))
      (t/is (= "none" (prompts/substitute-args "${ARGUMENTS:-none}" []))))
    (t/testing "slicing"
      (t/is (= "two three" (prompts/substitute-args "${@:2}" args)))
      (t/is (= "one two three" (prompts/substitute-args "${@:1}" args)))
      (t/is (= "two three" (prompts/substitute-args "${@:2:1}" args)))
      (t/is (= "" (prompts/substitute-args "${@:9}" args)))
      (t/is (= "one two three" (prompts/substitute-args "${@:0}" args))))
    (t/testing "no recursive substitution"
      (t/is (= "x${2}y x" (prompts/substitute-args "x${2}y $1" ["x" "y"]))))
    (t/testing "literal (unmatched) placeholder forms stay as-is (pi regex)"
      (t/is (= "${@}" (prompts/substitute-args "${@}" args)))
      (t/is (= "${1}" (prompts/substitute-args "${1}" args)))
      (t/is (= "x$" (prompts/substitute-args "x$" args))))
    (t/testing "$0 and empty all-args"
      (t/is (= "" (prompts/substitute-args "$0" ["a"])))
      (t/is (= "" (prompts/substitute-args "$@" [])))
      (t/is (= "" (prompts/substitute-args "$ARGUMENTS" [])))
      (t/is (= "x" (prompts/substitute-args "${1:-x}" []))))
    (t/testing "overflowing indices do not throw (pi parseInt clamps)"
      (t/is (= "" (prompts/substitute-args "$99999999999999999999" args)))
      (t/is (= "def" (prompts/substitute-args "${99999999999999999999:-def}" args)))
      (t/is (= "" (prompts/substitute-args "${@:99999999999999999999}" args)))
      (t/is (= "one two three" (prompts/substitute-args "${@:1:99999999999999999999}" args))))
    (t/testing "no placeholders"
      (t/is (= "plain text" (prompts/substitute-args "plain text" args))))))

;; ─── expand-prompt-template (pi: expandPromptTemplate) ─────────────────────

(t/deftest test-expand-prompt-template
  (let [tpls [{:name "review" :content "Review the staged changes."}
              {:name "comp" :content "Create component $1 with ${@:2}"}]]
    (t/is (= "Review the staged changes."
             (prompts/expand-prompt-template "/review" tpls)))
    (t/is (= "Create component Button with a b"
             (prompts/expand-prompt-template "/comp Button a b" tpls)))
    (t/is (= "Create component Button with x y"
             (prompts/expand-prompt-template "/comp Button \"x y\"" tpls)))
    (t/is (= "/unknown x" (prompts/expand-prompt-template "/unknown x" tpls)))
    (t/is (= "plain /review" (prompts/expand-prompt-template "plain /review" tpls)))
    (t/is (= "" (prompts/expand-prompt-template "" tpls)))))

;; ─── Loading (pi: loadTemplatesFromDir / loadTemplateFromFile) ─────────────

(t/deftest test-load-prompt-templates-from-dir
  (let [tmp-dir (str "target/test-prompts-" (System/currentTimeMillis))
        base (fn [rel] (str tmp-dir "/" rel))]
    (try
      (io/make-parents (base "review.md"))
      (spit (base "review.md")
            "---\ndescription: Review staged git changes\n---\nReview the staged changes.")
      (spit (base "comp.md")
            "---\ndescription: Create a component\nargument-hint: \"<name>\"\n---\nCreate component $1")
      (spit (base "emptyhint.md")
            "---\ndescription: Empty hint\nargument-hint: \"\"\n---\nEmpty hint body")
      (spit (base "notes.txt") "not a template")
      ;; non-recursive: subdir templates are not discovered
      (io/make-parents (base "sub/nested.md"))
      (spit (base "sub/nested.md") "---\ndescription: Nested\n---\nNested body")
      (let [loaded (prompts/load-prompt-templates-from-dir tmp-dir)]
        (t/is (= ["comp" "emptyhint" "review"] (mapv :name (sort-by :name loaded))))
        (let [review (prompts/get-prompt-template "review")
              comp (prompts/get-prompt-template "comp")]
          (t/is (= "Review staged git changes" (:description review)))
          (t/is (= "Review the staged changes." (:content review)))
          (t/is (= "Create a component" (:description comp)))
          (t/is (= "<name>" (:argument-hint comp)))
          (t/is (nil? (prompts/get-prompt-template "nested")))
          (t/is (nil? (prompts/get-prompt-template "notes")))))
      (t/testing "empty argument-hint is dropped (pi falsy check)"
        (t/is (nil? (:argument-hint (prompts/get-prompt-template "emptyhint")))))
      (finally (fs/delete-tree tmp-dir)))))

(t/deftest test-load-prompt-templates-extra-cases
  (let [tmp-dir (str "target/test-prompts-x-" (System/currentTimeMillis))
        base (fn [rel] (str tmp-dir "/" rel))]
    (try
      (io/make-parents (base ".hidden.md"))
      (spit (base ".hidden.md") "---\ndescription: Hidden file template\n---\nHidden body")
      (io/make-parents (base "crlf.md"))
      (spit (base "crlf.md") "---\r\ndescription: CRLF template\r\n---\r\nBody line one.\r\n")
      (io/make-parents (base "nofm.md"))
      (spit (base "nofm.md") "First line without frontmatter.\nSecond line.")
      (io/make-parents (base "dotted.name.md"))
      (spit (base "dotted.name.md") "---\ndescription: Dotted\n---\nDotted")
      (let [loaded (prompts/load-prompt-templates-from-dir tmp-dir)]
        (t/is (= [".hidden" "crlf" "dotted.name" "nofm"] (mapv :name (sort-by :name loaded))))
        (t/testing "hidden .md files load (pi has no hidden check in prompts)"
          (t/is (= "Hidden file template" (:description (prompts/get-prompt-template ".hidden")))))
        (t/testing "CRLF frontmatter and body are normalized"
          (t/is (= "CRLF template" (:description (prompts/get-prompt-template "crlf"))))
          (t/is (= "Body line one." (:content (prompts/get-prompt-template "crlf")))))
        (t/testing "no frontmatter: description falls back to first body line"
          (t/is (= "First line without frontmatter." (:description (prompts/get-prompt-template "nofm")))))
        (t/testing "dotted filename becomes the template name"
          (t/is (= "Dotted" (:description (prompts/get-prompt-template "dotted.name"))))))
      (finally (fs/delete-tree tmp-dir)))))

(t/deftest test-load-prompt-templates-desc-fallback
  (t/testing "description falls back to the first non-empty body line"
    (let [tmp-dir (str "target/test-prompts-desc-" (System/currentTimeMillis))
          long-line (str/join (repeat 80 "x"))]
      (try
        (io/make-parents (str tmp-dir "/plain.md"))
        (spit (str tmp-dir "/plain.md") "\n\nFirst meaningful line.\nSecond line.")
        (io/make-parents (str tmp-dir "/long.md"))
        (spit (str tmp-dir "/long.md") long-line)
        (prompts/load-prompt-templates-from-dir tmp-dir)
        (t/is (= "First meaningful line." (:description (prompts/get-prompt-template "plain"))))
        (t/is (= (str (subs long-line 0 60) "...")
                 (:description (prompts/get-prompt-template "long"))))
        (finally (fs/delete-tree tmp-dir))))))

(t/deftest test-load-prompt-templates-from-dir-non-existent
  (t/is (= [] (prompts/load-prompt-templates-from-dir "/nonexistent/prompts"))))

(t/deftest test-load-prompt-templates-warns-on-unreadable-file
  (prompts/clear-prompt-templates!)
  (let [tmp-dir (str "target/test-prompts-warn-" (System/currentTimeMillis))
        good (str tmp-dir "/good.md")
        missing (str tmp-dir "/missing.md")
        err (java.io.StringWriter.)]
    (try
      (io/make-parents good)
      (spit good "---\ndescription: Loads fine.\n---\nGood body.")
      (binding [*err* err]
        (prompts/load-prompt-template-files! [good missing]))
      (t/testing "the valid template still loads"
        (t/is (= "Loads fine." (:description (prompts/get-prompt-template "good")))))
      (t/testing "the unreadable template is reported, not silently skipped"
        (t/is (str/includes? (str err) (str "Warning: prompt template at " missing))))
      (finally
        (prompts/clear-prompt-templates!)
        (fs/delete-tree tmp-dir)))))

(t/deftest test-load-prompt-templates-non-string-frontmatter
  (prompts/clear-prompt-templates!)
  (let [tmp-dir (str "target/test-prompts-types-" (System/currentTimeMillis))]
    (try
      (io/make-parents (str tmp-dir "/typed.md"))
      (spit (str tmp-dir "/typed.md")
            "---\ndescription: true\nargument-hint: 42\n---\nReal first line.")
      (prompts/load-prompt-templates-from-dir tmp-dir)
      (let [tpl (prompts/get-prompt-template "typed")]
        (t/testing "non-string frontmatter values are ignored (pi: typeof checks)"
          (t/is (= "Real first line." (:description tpl)))
          (t/is (nil? (:argument-hint tpl)))))
      (finally
        (prompts/clear-prompt-templates!)
        (fs/delete-tree tmp-dir)))))

;; ─── Autocomplete shape (pi: interactive-mode templateCommands) ────────────

(t/deftest test-as-command-maps
  (let [tpls [{:name "comp" :description "Create" :argument-hint "<name>"}
              {:name "plain" :description "No hint"}]]
    (t/is (= [{:name "comp" :description "Create" :argument-hint "<name>"}
              {:name "plain" :description "No hint"}]
             (prompts/as-command-maps tpls)))))

(t/deftest test-clear-prompt-templates
  (let [tmp-dir (str "target/test-prompts-clear-" (System/currentTimeMillis))
        f (str tmp-dir "/clear-me.md")]
    (try
      (io/make-parents f)
      (spit f "---\ndescription: Template to clear.\n---\nBody.")
      (prompts/load-prompt-templates-from-dir tmp-dir)
      (t/is (some? (prompts/get-prompt-template "clear-me")))
      (prompts/clear-prompt-templates!)
      (t/is (nil? (prompts/get-prompt-template "clear-me")))
      (finally (fs/delete-tree tmp-dir)))))

(t/deftest test-register-prompt-template
  (prompts/clear-prompt-templates!)
  (let [dereg (prompts/register-prompt-template!
               {:name "ext-tpl" :content "---\ndescription: Ext template\n---\nHello $1"
                :location "my-ext:prompts/ext-tpl.md" :extension "my-ext"})]
    (t/is (some? (prompts/get-prompt-template "ext-tpl")))
    (t/is (= "Hello hi" (prompts/expand-prompt-template "/ext-tpl hi"
                                                        (prompts/get-prompt-templates))))
    (dereg)
    (t/is (nil? (prompts/get-prompt-template "ext-tpl")) "deregister removes the template"))
  (prompts/clear-prompt-templates!))

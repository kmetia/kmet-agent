(ns kmet.app.test-yaml
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.yaml :as yaml]))

;; ─── Plain scalars ─────────────────────────────────────────────────────────

(deftest test-plain-strings
  (is (= {"a" "foo"} (yaml/parse "a: foo")))
  (is (= {"a" "foo bar baz"} (yaml/parse "a: foo bar baz")))
  (is (= {"a" "http://x"} (yaml/parse "a: http://x")))
  (is (= {"description" "see http://x for details"}
         (yaml/parse "description: see http://x for details")))
  (is (= {"a" "x#y"} (yaml/parse "a: x#y")))
  (is (= {"a" {"colon" "inside"}} (yaml/parse "a: colon: inside")))
  (is (= {"a" "spaced"} (yaml/parse "a:   spaced  ")))  ; plain scalars are trimmed
  (is (= {"a" "with"} (yaml/parse "a: with # in middle")))  ; # after space = comment
  (is (= {"a" "-dash"} (yaml/parse "a: -dash"))))

(deftest test-booleans-and-null
  (is (= {"a" true} (yaml/parse "a: true")))
  (is (= {"a" true} (yaml/parse "a: True")))
  (is (= {"a" true} (yaml/parse "a: TRUE")))
  (is (= {"a" false} (yaml/parse "a: false")))
  (is (= {"a" false} (yaml/parse "a: False")))
  (is (= {"a" false} (yaml/parse "a: FALSE")))
  (is (= {"a" nil} (yaml/parse "a: null")))
  (is (= {"a" nil} (yaml/parse "a: Null")))
  (is (= {"a" nil} (yaml/parse "a: NULL")))
  (is (= {"a" nil} (yaml/parse "a: ~")))
  (is (= {"a" nil} (yaml/parse "a:")))
  (is (= {"a" nil} (yaml/parse "a: # comment")))
  (testing "YAML 1.2 core: yes/no/on/off stay strings"
    (is (= {"a" "yes"} (yaml/parse "a: yes")))
    (is (= {"a" "no"} (yaml/parse "a: no")))
    (is (= {"a" "on"} (yaml/parse "a: on")))
    (is (= {"a" "off"} (yaml/parse "a: off")))))

(deftest test-numbers
  (is (= {"a" 42} (yaml/parse "a: 42")))
  (is (= {"a" -7} (yaml/parse "a: -7")))
  (is (= {"a" 5} (yaml/parse "a: +5")))
  (is (= {"a" 0} (yaml/parse "a: 0")))
  (is (= {"a" 3.14} (yaml/parse "a: 3.14")))
  (is (= {"a" -2.5} (yaml/parse "a: -2.5")))
  (is (= {"a" 0.5} (yaml/parse "a: .5")))
  (is (= {"a" 100000.0} (yaml/parse "a: 1e5")))
  (is (= {"a" 100000.0} (yaml/parse "a: 1E5")))
  (is (= {"a" 0.0015} (yaml/parse "a: 1.5e-3")))
  (testing "underscores are NOT digit separators (pi yaml keeps 1_000 a string)"
    (is (= {"a" "1_000"} (yaml/parse "a: 1_000")))
    (is (= {"a" "1_000.5"} (yaml/parse "a: 1_000.5"))))
  (testing "non-numbers stay strings"
    (is (= {"a" "1."} (yaml/parse "a: 1.")))
    (is (= {"a" "1.2.3"} (yaml/parse "a: 1.2.3")))
    (is (= {"a" "123abc"} (yaml/parse "a: 123abc")))
    (is (= {"a" "0x1F"} (yaml/parse "a: 0x1F")))
    (is (= {"a" "1,000"} (yaml/parse "a: 1,000")))
    (testing "overflowing ints fall back to the string (bb parse-long returns nil)"
      (is (= {"a" "99999999999999999999999"} (yaml/parse "a: 99999999999999999999999"))))))

;; ─── Quoted scalars ────────────────────────────────────────────────────────

(deftest test-double-quoted
  (is (= {"a" "plain"} (yaml/parse "a: \"plain\"")))
  (is (= {"a" "line\nbreak"} (yaml/parse "a: \"line\\nbreak\"")))
  (is (= {"a" "tab\tchar"} (yaml/parse "a: \"tab\\tchar\"")))
  (is (= {"a" "carriage\rreturn"} (yaml/parse "a: \"carriage\\rreturn\"")))
  (is (= {"a" "quote\"inside"} (yaml/parse "a: \"quote\\\"inside\"")))
  (is (= {"a" "back\\slash"} (yaml/parse "a: \"back\\\\slash\"")))
  (is (= {"a" "a/b"} (yaml/parse "a: \"a\\/b\"")))
  (is (= {"a" "A"} (yaml/parse "a: \"\\x41\"")))
  (is (= {"a" "A"} (yaml/parse "a: \"\\u0041\"")))
  (is (= {"a" "a # b"} (yaml/parse "a: \"a # b\"")))
  (is (= {"a" "a: b"} (yaml/parse "a: \"a: b\"")))
  (testing "unknown escapes keep the char (lenient; pi throws)"
    (is (= {"a" "q"} (yaml/parse "a: \"\\q\"")))))

(deftest test-single-quoted
  (is (= {"a" "plain"} (yaml/parse "a: 'plain'")))
  (is (= {"a" "it's"} (yaml/parse "a: 'it''s'")))
  (testing "single quotes do not process backslash escapes (YAML)"
    (is (= {"a" "no \\n escape"} (yaml/parse "a: 'no \\n escape'")))))

(deftest test-quote-leniency
  (testing "unterminated/trailing-junk quotes fall back to plain (lenient)"
    (is (= {"a" "\"foo"} (yaml/parse "a: \"foo")))
    (is (= {"a" "\"foo\" bar"} (yaml/parse "a: \"foo\" bar"))))
  (testing "quotes open only at token start (mid-word ' is literal)"
    (is (= {"a" "it's"} (yaml/parse "a: it's # comment")))
    (is (= {"a" "it's fine"} (yaml/parse "a: it's fine"))))
  (testing "# inside block scalar content is literal, not a comment"
    (is (= {"a" "use #hash tags\n"} (yaml/parse "a: |\n  use #hash tags\n")))
    (is (= {"a" "he said \"hi\"\n"} (yaml/parse "a: |\n  he said \"hi\"\n"))))
  (testing "quoted list item with a colon stays a string"
    (is (= ["x: y" "z"] (yaml/parse "- \"x: y\"\n- z")))))

;; ─── Comments ──────────────────────────────────────────────────────────────

(deftest test-comments
  (is (= {"a" "foo"} (yaml/parse "a: foo # comment")))
  (is (= {"a" "foo#bar"} (yaml/parse "a: foo#bar # real comment")))
  (is (= {"a" "foo"} (yaml/parse "# leading comment\na: foo")))
  (is (nil? (yaml/parse "# only comment")))
  (is (= {"a" "foo"} (yaml/parse "a: foo\t# tab before comment")))
  (is (= {"a" "x#y"} (yaml/parse "a: x#y")))
  (is (= {"a" "quoted #"} (yaml/parse "a: \"quoted #\""))))

;; ─── Block scalars ─────────────────────────────────────────────────────────

(deftest test-literal-block-scalars
  (testing "clip chomping (default): exactly one trailing newline"
    (is (= {"a" "x\ny\n"} (yaml/parse "a: |\n  x\n  y\n")))
    (is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n")))
    (is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n\n")))
    (is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n\n\n"))))
  (testing "strip chomping (-)"
    (is (= {"a" "x\ny"} (yaml/parse "a: |-\n  x\n  y\n")))
    (is (= {"a" "x"} (yaml/parse "a: |-\n  x\n\n"))))
  (testing "keep chomping (+): trailing newlines preserved"
    (is (= {"a" "x\n"} (yaml/parse "a: |+\n  x\n")))
    (is (= {"a" "x\n\n"} (yaml/parse "a: |+\n  x\n\n"))))
  (testing "explicit indent indicator (relative to key)"
    (is (= {"a" "x\ny\n"} (yaml/parse "a: |2\n  x\n  y\n"))))
  (testing "indent indicator combined with chomping"
    (is (= {"a" "x\ny"} (yaml/parse "a: |2-\n  x\n  y\n"))))
  (testing "empty block"
    (is (= {"a" ""} (yaml/parse "a: |\n")))
    (is (= {"a" ""} (yaml/parse "a: |-\n")))))

(deftest test-folded-block-scalars
  (is (= {"a" "x y\n"} (yaml/parse "a: >\n  x\n  y\n")))
  (is (= {"a" "x y z\n"} (yaml/parse "a: >\n  x\n  y\n  z\n")))
  (is (= {"a" "x\ny\n"} (yaml/parse "a: >\n  x\n\n  y\n")))
  (is (= {"a" "x\n\ny\n"} (yaml/parse "a: >\n  x\n\n\n  y\n")))
  (is (= {"a" "x y"} (yaml/parse "a: >-\n  x\n  y\n"))))

(deftest test-block-scalars-in-context
  (is (= ["x\ny\n" "plain"] (yaml/parse "- |\n  x\n  y\n- plain")))
  (is (= {"meta" {"desc" "line1\nline2\n"}}
         (yaml/parse "meta:\n  desc: |\n    line1\n    line2\n"))))

;; ─── Structure ─────────────────────────────────────────────────────────────

(deftest test-nested-maps
  (is (= {"meta" {"a" "b"}} (yaml/parse "meta:\n  a: b")))
  (is (= {"a" {"b" {"c" "d"}}} (yaml/parse "a:\n  b:\n    c: d")))
  (is (= {"a" {"b" "c"} "d" "e"} (yaml/parse "a: b: c\nd: e")))
  (is (= {"a" {"b" {"c" "d"}}} (yaml/parse "a: b: c: d")))
  (testing "compact mapping in list items continues with deeper entries"
    (is (= [{"name" "a", "age" 1} {"name" "b"}]
           (yaml/parse "- name: a\n  age: 1\n- name: b")))
    (is (= [{"name" "a", "meta" {"x" 1}} {"name" "b"}]
           (yaml/parse "- name: a\n  meta:\n    x: 1\n- name: b")))))

(deftest test-lists
  (is (= ["a" "b"] (yaml/parse "- a\n- b")))
  (is (= [1 "two" true] (yaml/parse "- 1\n- two\n- true")))
  (is (= [["a" "b"] "c"] (yaml/parse "- - a\n  - b\n- c")))
  (is (= [nil "b"] (yaml/parse "-\n- b")))
  (is (= [nil] (yaml/parse "-")))
  (is (= {"tags" ["a" "b"]} (yaml/parse "tags:\n  - a\n  - b")))
  (is (= {"matrix" [["x" "y"] ["z"]]}
         (yaml/parse "matrix:\n  - - x\n    - y\n  - - z")))
  (testing "list item with nested block value"
    (is (= [{"name" "a"} {"name" "b"}]
           (yaml/parse "- name: a\n- name: b")))))

;; ─── Keys ──────────────────────────────────────────────────────────────────

(deftest test-keys
  (is (= {"my key" "v"} (yaml/parse "\"my key\": v")))
  (is (= {"k'e'y" "v"} (yaml/parse "'k'e'y': v")))
  (testing "keys stay strings (pi JS object keys)"
    (is (= {"123" "v"} (yaml/parse "123: v")))
    (is (= {"true" "v"} (yaml/parse "true: v"))))
  (is (= {"a" "first" "b" "second"} (yaml/parse "a: first\nb: second")))
  (testing "duplicate keys: last wins (pi throws)"
    (is (= {"a" "second"} (yaml/parse "a: first\na: second")))))

;; ─── Input edge cases ──────────────────────────────────────────────────────

(deftest test-input-edge-cases
  (is (nil? (yaml/parse nil)))
  (is (nil? (yaml/parse "")))
  (is (nil? (yaml/parse "   ")))
  (is (nil? (yaml/parse "\n\n")))
  (is (nil? (yaml/parse "# comment only")))
  (testing "CRLF and lone CR normalized"
    (is (= {"a" "b"} (yaml/parse "a: b\r\n")))
    (is (= {"a" "b", "c" "d"} (yaml/parse "a: b\rc: d"))))
  (is (= {"a" "b"} (yaml/parse "a: b\n\n")))
  (is (= {"a" "b"} (yaml/parse "a: b\n   ")))
  (is (= {"a" "b"} (yaml/parse "a: b"))))

;; ─── Real-world frontmatter ────────────────────────────────────────────────

(deftest test-real-world-frontmatter
  (is (= {"name" "my-skill"
          "description" "This skill does something\nvery useful across lines.\n"
          "license" "MIT"
          "metadata" {"category" "dev", "tags" ["cli" "docs"]}
          "allowed-tools" ["read" "edit"]
          "disable-model-invocation" false}
         (yaml/parse (str "name: my-skill\n"
                          "description: |\n"
                          "  This skill does something\n"
                          "  very useful across lines.\n"
                          "license: MIT\n"
                          "metadata:\n"
                          "  category: dev\n"
                          "  tags:\n"
                          "    - cli\n"
                          "    - docs\n"
                          "allowed-tools:\n"
                          "  - read\n"
                          "  - edit\n"
                          "disable-model-invocation: false\n"))))
  (is (= {"name" "s2", "description" "One line."}
         (yaml/parse "---\nname: s2\ndescription: One line.\n---\n# body"))))

;; ─── Frontmatter (kmet.app.yaml, pi: utils/frontmatter.js) ───────────────

(deftest no-frontmatter
  (is (= {:frontmatter {} :body "hello\nworld"}
         (yaml/parse-frontmatter "hello\nworld"))))

(deftest basic-frontmatter
  (is (= {:frontmatter {"name" "foo" "description" "bar baz"}
          :body "Body text"}
         (yaml/parse-frontmatter "---\nname: foo\ndescription: bar baz\n---\nBody text"))))

(deftest empty-frontmatter
  (is (= {:frontmatter {} :body "Body text"}
         (yaml/parse-frontmatter "---\n---\nBody text"))))

(deftest crlf-normalized
  (is (= {:frontmatter {"name" "foo"} :body "Body"}
         (yaml/parse-frontmatter "---\r\nname: foo\r\n---\r\nBody"))))

(deftest unterminated-frontmatter-is-body
  (is (= {:frontmatter {} :body "---\nname: foo\n"}
         (yaml/parse-frontmatter "---\nname: foo\n"))))

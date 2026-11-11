(ns kmet.libs.test-text
  "Unit tests for the shared text helpers (quiet titles and call-line heads)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.text :as text]))

(deftest first-nonblank-line-test
  (testing "the first line carrying content, skipping leading blank lines"
    (is (= "(+ 1 2)" (text/first-nonblank-line "(+ 1 2)\n(+ 3 4)")))
    (is (= "(+ 1 2)" (text/first-nonblank-line "\n  \n(+ 1 2)")))
    (is (= "  (indented)" (text/first-nonblank-line "\n  (indented)\n")))
    (is (nil? (text/first-nonblank-line "\n  \n")))
    (is (nil? (text/first-nonblank-line "")))
    (is (nil? (text/first-nonblank-line nil)))
    (is (nil? (text/first-nonblank-line 42)))))

(deftest title-line-test
  (testing "the first non-blank line, shortened to the limit"
    (is (= "(+ 1 2)" (text/title-line "\n(+ 1 2)\n(+ 3 4)")))
    (is (= "abc" (text/title-line "abc" 3)))
    (is (= "abc…" (text/title-line "abcd" 3)))
    (is (nil? (text/title-line "\n \n")))))

(deftest splits-surrogate-pair-test
  (testing "a cut between characters or at the ends never splits a pair"
    (is (not (text/splits-surrogate-pair? "abc" 0)))
    (is (not (text/splits-surrogate-pair? "abc" 1)))
    (is (not (text/splits-surrogate-pair? "abc" 3))))
  (testing "the emoji boundary"
    (is (not (text/splits-surrogate-pair? "a😀b" 1)))
    (is (not (text/splits-surrogate-pair? "a😀b" 3)))
    (if (= 2 (count "😀"))
      (is (text/splits-surrogate-pair? "a😀b" 2))
      (is (not (text/splits-surrogate-pair? "a😀b" 2))))))

(deftest trim-leading-blank-lines-test
  (testing "leading blank lines go, indentation and inner blanks stay"
    (is (= "(+ 1 2)" (text/trim-leading-blank-lines "\n(+ 1 2)")))
    (is (= "(+ 1 2)" (text/trim-leading-blank-lines "\n \t\r\n\n(+ 1 2)")))
    (is (= "  (indented)\n\n(+ 3 4)"
           (text/trim-leading-blank-lines "  (indented)\n\n(+ 3 4)")))
    (is (= "" (text/trim-leading-blank-lines "\n  \n")))
    (is (= "(- 1 2)" (text/trim-leading-blank-lines "(- 1 2)")))
    (is (nil? (text/trim-leading-blank-lines nil)))
    (is (= 42 (text/trim-leading-blank-lines 42)))))

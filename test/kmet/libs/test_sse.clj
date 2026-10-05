(ns kmet.libs.test-sse
  (:require [clojure.test :as t]
            [kmet.libs.sse :as sse]))

;; ─── Event parsing ─────────────────────────────────────────────────────────

(t/deftest test-parse-sse-line
  (t/is (= [nil "hi"] (sse/parse-sse-line "data: hi")))
  (t/is (= [nil "hi"] (sse/parse-sse-line "data:hi")))
  (t/is (= ["event" nil] (sse/parse-sse-line "event: event")))
  (t/is (= [nil nil] (sse/parse-sse-line "")))
  (t/is (= [nil "  hi"] (sse/parse-sse-line "data:   hi"))
        "only the one space after the colon is stripped")
  (t/is (= [nil "hi "] (sse/parse-sse-line "data: hi "))
        "trailing whitespace is part of the value")
  (t/is (= [nil nil] (sse/parse-sse-line ":comment"))))

(t/deftest test-append-data
  (t/is (= "a" (sse/append-data "" "a")))
  (t/is (= "a\nb" (sse/append-data "a" "b")))
  (t/is (= "a\n" (sse/append-data "a" "")))
  (t/is (= "" (sse/append-data "" ""))))

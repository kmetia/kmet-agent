(ns kmet.libs.test-sse
  (:require [clojure.test :as t]
            [kmet.libs.sse :as sse]))

;; ─── Event parsing ─────────────────────────────────────────────────────────

(t/deftest test-parse-sse-line
  (t/is (= [nil "hi"] (sse/parse-sse-line "data: hi")))
  (t/is (= ["event" nil] (sse/parse-sse-line "event: event")))
  (t/is (= [nil nil] (sse/parse-sse-line "")))
  (t/is (= [nil "hi"] (sse/parse-sse-line "data:   hi")))
  (t/is (= [nil nil] (sse/parse-sse-line ":comment"))))

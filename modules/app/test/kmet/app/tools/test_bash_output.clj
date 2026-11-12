(ns kmet.app.tools.test-bash-output
  "Unit tests for the bash tool's pure output half: sizing, UTF-8-safe
   truncation and binary-output sanitizing (pi: bash-executor.ts formatSize /
   truncateHead / truncateTail)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.tools.bash-output :as output]))

(t/deftest test-format-size
  (t/testing "bytes, kilobytes and megabytes use one decimal, pi's suffixes"
    (t/is (= "0B" (output/format-size 0)))
    (t/is (= "1023B" (output/format-size 1023)))
    (t/is (= "1.0KB" (output/format-size 1024)))
    (t/is (= "12.5KB" (output/format-size 12800)))
    (t/is (= "1.5MB" (output/format-size (* 1024 1024 3/2))))))

(t/deftest test-byte-length
  (t/testing "UTF-8 byte length, not character count"
    (t/is (zero? (output/byte-length "")))
    (t/is (= 3 (output/byte-length "abc")))
    (t/is (= 2 (output/byte-length "é")))
    (t/is (= 9 (output/byte-length "日本語")))))

(t/deftest test-truncate-tail-surrogate-boundary
  (t/testing "byte-cut landing mid-UTF8 keeps whole char boundary (pi truncateStringToBytesFromEnd)"
    (let [s (str (apply str (repeat 9 "x")) "😀" (apply str (repeat 9 "y")))
          ;; UTF-8 bytes = 9 + 4 + 9 = 22; max-bytes 10 → pi slices last 10 bytes
          ;; skipping the partial emoji bytes at the boundary (byte 10 is inside emoji)
          result (output/truncate-tail s :max-bytes 10)
          content (:content result)]
      (t/is (:truncated result))
      ;; pi skips the incomplete UTF-8 char, so the cut lands on a whole
      ;; char boundary: only the 9 trailing y's survive (9 bytes ≤ 10).
      ;; (No lone-surrogate regex: Jolt strings are codepoint-indexed, so a
      ;; surrogate code point there is not half of a UTF-16 pair.)
      (t/is (= "yyyyyyyyy" content))
      ;; pi skips the incomplete UTF-8 char, so emoji is dropped
      (t/is (not (str/includes? content "\uFFFD")) "no replacement char")
      (t/is (<= (output/byte-length content) 10) "output fits byte limit"))))

(t/deftest test-truncate-tail
  (t/testing "a result inside both budgets passes through untouched"
    (let [r (output/truncate-tail "a\nb")]
      (t/is (= "a\nb" (:content r)))
      (t/is (false? (:truncated r)))
      (t/is (nil? (:truncated-by r)))
      (t/is (= 2 (:total-lines r)))
      (t/is (= 3 (:total-bytes r)))))

  (t/testing "a trailing newline is not a line (pi splitLinesForCounting)"
    (let [r (output/truncate-tail "a\nb\n")]
      (t/is (false? (:truncated r)))
      (t/is (= 2 (:total-lines r)))))

  (t/testing "the line budget keeps the last lines"
    (let [r (output/truncate-tail "1\n2\n3\n4\n5" :max-lines 2)]
      (t/is (= "4\n5" (:content r)))
      (t/is (= :lines (:truncated-by r)))
      (t/is (= 2 (:output-lines r)))
      (t/is (= 5 (:total-lines r)))
      (t/is (false? (:last-line-partial r)))))

  (t/testing "a single over-long line keeps its byte-safe tail and marks the partial line"
    (let [r (output/truncate-tail "xxxxxxxxxx" :max-bytes 4)]
      (t/is (= "xxxx" (:content r)))
      (t/is (= :bytes (:truncated-by r)))
      (t/is (true? (:last-line-partial r)))
      (t/is (= 10 (:last-line-bytes r)) "the full last line's size"))))

(t/deftest test-truncate-head
  (t/testing "a result inside both budgets passes through untouched"
    (let [r (output/truncate-head "a\nb")]
      (t/is (= "a\nb" (:content r)))
      (t/is (false? (:truncated r)))
      (t/is (false? (:first-line-exceeds-limit r)))))

  (t/testing "the line budget keeps the first lines, never a partial one"
    (let [r (output/truncate-head "1\n2\n3\n4\n5" :max-lines 2)]
      (t/is (= "1\n2" (:content r)))
      (t/is (= :lines (:truncated-by r)))
      (t/is (= 2 (:output-lines r)))))

  (t/testing "a line that cannot fit the byte budget yields no content"
    (let [r (output/truncate-head "xxxxxxxxxx" :max-bytes 4)]
      (t/is (= "" (:content r)))
      (t/is (true? (:first-line-exceeds-limit r)))
      (t/is (zero? (:output-lines r)))))

  (t/testing "the byte budget stops before the line that would overflow it"
    (let [r (output/truncate-head "aaaa\nbbbb" :max-bytes 6)]
      (t/is (= "aaaa" (:content r)))
      (t/is (= :bytes (:truncated-by r))))))

(t/deftest test-sanitize-output
  (t/testing "ANSI sequences are stripped (pi sanitizeBinaryOutput)"
    (t/is (= "red" (output/sanitize-output "\u001b[31mred\u001b[0m"))))

  (t/testing "control characters go, tabs and newlines stay"
    (t/is (= "ab" (output/sanitize-output "a\u0000b")))
    (t/is (= "a\tb\nc" (output/sanitize-output "a\tb\nc"))))

  (t/testing "CRLF and lone CR normalize to LF"
    (t/is (= "a\nb" (output/sanitize-output "a\r\nb")))
    (t/is (= "a\nb" (output/sanitize-output "a\rb"))))

  (t/testing "Unicode format characters (U+FFF9-FFFB) are removed"
    (t/is (= "hi" (output/sanitize-output "\uFFF9hi\uFFFB")))))

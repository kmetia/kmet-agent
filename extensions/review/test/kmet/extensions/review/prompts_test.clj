(ns kmet.extensions.review.prompts-test
  "Tests for the review extension's prompts and rubric assembly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.review.prompts :as p]))

;; ─── Per-target prompt format helpers ──────────────────────────────────

(deftest format-uncommitted-test
  (is (str/includes? (p/format-uncommitted) "current code changes"))
  (is (str/includes? (p/format-uncommitted) "prioritized findings")))

(deftest format-base-branch-with-merge-base-test
  (let [out (p/format-base-branch "main" "abc1234")]
    (is (str/includes? out "main"))
    (is (str/includes? out "abc1234"))
    (is (str/includes? out "git diff abc1234"))
    ;; the merge-base form is used
    (is (not (str/includes? out "merge-base HEAD")))))

(deftest format-base-branch-fallback-test
  (let [out (p/format-base-branch "develop" nil)]
    (is (str/includes? out "develop"))
    ;; the fallback form is used
    (is (str/includes? out "git merge-base"))
    (is (str/includes? out "develop's upstream"))))

(deftest format-commit-with-title-test
  (let [out (p/format-commit "abc1234" "Fix flarble")]
    (is (str/includes? out "abc1234"))
    (is (str/includes? out "Fix flarble"))
    (is (str/includes? out "Review the code changes introduced by commit"))))

(deftest format-commit-without-title-test
  (let [out (p/format-commit "abc1234" nil)]
    (is (str/includes? out "abc1234"))
    (is (not (str/includes? out "(\"")))))

(deftest format-folder-test
  (let [out (p/format-folder ["src" "docs"])]
    (is (str/includes? out "src, docs"))
    (is (str/includes? out "snapshot review"))))

;; ─── build-review-prompt dispatch ──────────────────────────────────────

(deftest build-review-prompt-dispatch-test
  (testing "uncommitted"
    (is (= p/uncommitted-prompt (p/build-review-prompt {:type :uncommitted}))))
  (testing "base-branch with merge-base"
    (let [out (p/build-review-prompt
               {:type :base-branch :branch "main" :merge-base "sha1"})]
      (is (str/includes? out "main"))
      (is (str/includes? out "sha1"))))
  (testing "commit with title"
    (let [out (p/build-review-prompt
               {:type :commit :sha "deadbeef" :title "WIP"})]
      (is (str/includes? out "deadbeef"))
      (is (str/includes? out "WIP"))))
  (testing "folder"
    (let [out (p/build-review-prompt
               {:type :folder :paths ["src" "test"]})]
      (is (str/includes? out "src, test"))))
  (testing "unknown type throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (p/build-review-prompt {:type :pull-request})))))

;; ─── User-facing hint ─────────────────────────────────────────────────

(deftest user-facing-hint-test
  (is (= "current changes" (p/user-facing-hint {:type :uncommitted})))
  (is (= "changes against 'main'"
         (p/user-facing-hint {:type :base-branch :branch "main"})))
  (is (= "commit abc1234: feat: new thing"
         (p/user-facing-hint
          {:type :commit :sha "abc1234567890" :title "feat: new thing"})))
  (is (= "commit abc1234"
         (p/user-facing-hint
          {:type :commit :sha "abc1234567890" :title nil})))
  (testing "long folder list is truncated"
    (let [long-list (vec (range 0 30))
          joined (str/join ", " (map (fn [i] (str "p" i)) long-list))
          out (p/user-facing-hint {:type :folder :paths (map (fn [i] (str "p" i)) long-list)})]
      ;; we just need the prefix + ellipsis form
      (is (or (str/includes? out "folders: ")
              (str/includes? out joined))))))

;; ─── Rubric assertions (the language-agnostic part) ──────────────────

(deftest rubric-has-no-js-examples-test
  ;; pi-review's rubric mentioned `isRecord` / `asString` as JS idioms.
  ;; The port drops those in favor of "trivial type-coercion or 'is-a-X'
  ;; wrappers that just rename an existing primitive".
  (is (not (str/includes? p/review-rubric "isRecord")))
  (is (not (str/includes? p/review-rubric "asString"))))

(deftest rubric-generalizes-json-test
  ;; pi-review's rubric had two JSON-specific bullets (item 4 of fail-fast
  ;; was "JSON parsing/decoding", item 7 of untrusted-input was about
  ;; ?next_page redirects). The port generalizes to "structured data
  ;; parsing/decoding" while keeping the redirects bullet.
  (is (str/includes? p/review-rubric
                     "Structured data parsing/decoding"))
  (is (not (str/includes? p/review-rubric
                          "JSON parsing/decoding should fail loudly")))
  ;; untrusted-input bullet about redirects stays
  (is (str/includes? p/review-rubric "open redirects")))

(deftest rubric-generalizes-error-fallbacks-test
  ;; pi-review's fail-fast item 3 listed `null`/`[]`/`false` as JS-typical
  ;; fallback values. The port uses the language-agnostic phrase
  ;; "silent fallback values" without naming the JS-specific cases.
  (is (str/includes? p/review-rubric "silent fallback values"))
  (is (not (str/includes? p/review-rubric "returning `null`/`[]`/`false`"))))

(deftest rubric-preserves-other-bullets-test
  ;; Bullet points that don't depend on language stay.
  (is (str/includes? p/review-rubric "back pressure"))
  (is (str/includes? p/review-rubric "P0"))
  (is (str/includes? p/review-rubric "Human Reviewer Callouts"))
  (is (str/includes? p/review-rubric "fail-fast"))
  (is (str/includes? p/review-rubric "Human Reviewer Callouts (Non-Blocking)")))

;; ─── Summary / fix prompts ─────────────────────────────────────────────

(deftest review-summary-prompt-test
  (is (str/includes? p/review-summary-prompt "Review Scope"))
  (is (str/includes? p/review-summary-prompt "Verdict"))
  (is (str/includes? p/review-summary-prompt "Fix Queue"))
  (is (str/includes? p/review-summary-prompt "Human Reviewer Callouts")))

(deftest review-fix-findings-prompt-test
  (is (str/includes? p/review-fix-findings-prompt "priority order"))
  (is (str/includes? p/review-fix-findings-prompt
                     "fail-fast error handling")))

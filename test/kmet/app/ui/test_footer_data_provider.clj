(ns kmet.app.ui.test-footer-data-provider
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.app.session :as session]
            [kmet.app.ui.footer-data-provider :as fdp]))

(defn- with-session
  "Create a session in target/ and run f, cleaning up the dir after."
  [f]
  (let [dir (str "target/kmet-fdp-test-" (System/currentTimeMillis) "-" (rand-int 999))
        sess (session/create-session dir)]
    (try
      (f sess)
      (finally (fs/delete-tree dir)))))

(deftest test-basics
  (testing "constructs with defaults"
    (let [p (fdp/make-footer-data-provider)]
      (is (some? p))
      (is (nil? (fdp/fdp-get-session p)))
      (is (= 1 (fdp/fdp-get-provider-count p)))
      (is (nil? (fdp/fdp-get-context-window p))))))

(deftest test-cwd-swap
  (testing "cwd swaps for a session switch (pi: setCwd), and an injected
            atom is shared — one write switches the footer, ctx.cwd, and
            tool components"
    (let [p (fdp/make-footer-data-provider :cwd "/one")]
      (is (= "/one" (fdp/fdp-get-cwd p)))
      (fdp/fdp-set-cwd! p "/two")
      (is (= "/two" (fdp/fdp-get-cwd p))))
    (let [shared (atom "/one")
          p (fdp/make-footer-data-provider :cwd-atom shared)]
      (is (identical? shared (:cwd-atom p)))
      (fdp/fdp-set-cwd! p "/two")
      (is (= "/two" @shared) "the injected atom is the single source"))))

(deftest test-session-swap
  (testing "session is swappable"
    (let [p (fdp/make-footer-data-provider)]
      (is (nil? (fdp/fdp-get-session p)))
      (fdp/fdp-set-session! p :a-session)
      (is (= :a-session (fdp/fdp-get-session p))))))

(deftest test-provider-count-swap
  (testing "provider count is swappable (pi updateAvailableProviderCount)"
    (let [p (fdp/make-footer-data-provider)]
      (is (= 1 (fdp/fdp-get-provider-count p)))
      (fdp/fdp-set-provider-count! p 3)
      (is (= 3 (fdp/fdp-get-provider-count p)))
      (fdp/fdp-set-provider-count! p 0)
      (is (= 0 (fdp/fdp-get-provider-count p))))))

(deftest test-usage-totals
  (testing "usage totals sum across session entries"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "hi"}]})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "hello"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 20
                                            :prompt_tokens_details {:cached_tokens 30}}})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "again"}]
                                    :usage {:input_tokens 200 :output_tokens 40
                                            :cache_read_input_tokens 50
                                            :cache_creation_input_tokens 10
                                            :cost {:input 0.002 :output 0.0008
                                                   :cache-read 0.0001 :cache-write 0.0002
                                                   :total 0.0031}}})
        (let [p (fdp/make-footer-data-provider :session sess)
              totals (fdp/fdp-usage-totals p)]
          (is (= {:input 270 :output 60 :cache-read 80 :cache-write 10 :cost 0.0031}
                 totals)))))))

(deftest test-context-tokens
  (testing "context tokens estimate the session branch"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "hello world"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "hi there"}]})
        (let [p (fdp/make-footer-data-provider :session sess)]
          (is (pos? (fdp/fdp-context-tokens p))))))))

(deftest test-context-tokens-after-compaction
  ;; Regression: compaction is append-only, so fdp-context-tokens must
  ;; measure the context (build-context), not the full branch — otherwise the
  ;; footer shows a token count/percentage that compaction never relieves.
  (testing "after compaction without a post-compaction response, tokens are unknown (pi: ?/window)"
    (with-session
      (fn [sess]
        (dotimes [i 10]
          (session/append-entry sess {:role :user :content [{:type :text :text (str "message body number " i " with plenty of words")}]}))
        (let [p (fdp/make-footer-data-provider :session sess)
              before (fdp/fdp-context-tokens p)
              branch (session/get-branch sess)
              first-kept-id (:id (nth branch 6))]
          (session/compact-with-summary! sess "SUMMARY" first-kept-id)
          (is (pos? before))
          (is (nil? (fdp/fdp-context-tokens p))
              "no assistant response after the compaction → unknown (pi: getContextUsage null percent)")))))
  (testing "a post-compaction assistant response makes the count measurable again"
    (with-session
      (fn [sess]
        (dotimes [i 10]
          (session/append-entry sess {:role :user :content [{:type :text :text (str "message body number " i " with plenty of words")}]}))
        (let [branch (session/get-branch sess)
              first-kept-id (:id (nth branch 6))]
          (session/compact-with-summary! sess "SUMMARY" first-kept-id)
          (session/append-entry sess {:role :assistant
                                      :content [{:type :text :text "post-compaction reply"}]
                                      :usage {:prompt_tokens 5000 :completion_tokens 100
                                              :prompt_tokens_details {:cached_tokens 2000}}})
          (let [p (fdp/make-footer-data-provider :session sess)]
            (is (= 5100 (fdp/fdp-context-tokens p))
                "measured usage of the latest assistant: input(5000-2000)+output+cacheRead")))))))

(deftest test-context-tokens-uses-last-usage
  (testing "measured usage of the latest assistant + chars/4 estimate of trailing entries (pi: estimateContextTokens)"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                                    :usage {:prompt_tokens 1000 :completion_tokens 200
                                            :prompt_tokens_details {:cached_tokens 300}}})
        (session/append-entry sess {:role :user :content [{:type :text :text "hello world"}]})
        (let [p (fdp/make-footer-data-provider :session sess)]
          ;; 700 input + 200 output + 300 cacheRead, plus ceil(11/4)=3 for the trailing user entry
          (is (= 1203 (fdp/fdp-context-tokens p))))))))

(deftest test-compaction-usage-in-totals
  ;; pi: compaction entries carry the summarization call's usage and the
  ;; footer sums it into the totals (#6671 "add usage info to branch summary,
  ;; compaction and tool result entries").
  (testing "compaction usage is included in usage totals (cost + tokens)"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "q1"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 20
                                            :prompt_tokens_details {:cached_tokens 30}
                                            :cost {:total 0.005}}})
        (let [branch (session/get-branch sess)
              first-kept-id (:id (nth branch 1))]
          (session/compact-with-summary! sess "SUMMARY" first-kept-id
                                         {:usage {:prompt_tokens 500 :completion_tokens 100
                                                  :prompt_tokens_details {:cached_tokens 200}
                                                  :cost {:total 0.01}}})
          (let [p (fdp/make-footer-data-provider :session sess)
                totals (fdp/fdp-usage-totals p)]
            (is (= {:input 370 :output 120 :cache-read 230 :cache-write 0 :cost 0.015}
                   totals)
                "assistant (70+20+30) + compaction (300+100+200) usage summed"))))))
  (testing "compaction usage is not post-compaction assistant usage"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "q1"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "old"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 50}}})
        (session/append-entry sess {:role :user :content [{:type :text :text "q2"}]})
        (let [branch (session/get-branch sess)
              first-kept-id (:id (nth branch 2))]
          ;; kept tail starts at q2 — no assistant after the compaction
          (session/compact-with-summary! sess "SUMMARY" first-kept-id
                                         {:usage {:prompt_tokens 500 :completion_tokens 100
                                                  :prompt_tokens_details {:cached_tokens 100}
                                                  :cost {:total 0.01}}})
          (let [p (fdp/make-footer-data-provider :session sess)]
            (is (nil? (fdp/fdp-context-tokens p))
                "still unknown until an assistant responds (pi)")))))
    (testing "hit rate and context tokens come from the assistant, not the compaction"
      (with-session
        (fn [sess]
          (session/append-entry sess {:role :user :content [{:type :text :text "q1"}]})
          (session/append-entry sess {:role :assistant :content [{:type :text :text "old"}]
                                      :usage {:prompt_tokens 100 :completion_tokens 1
                                              :prompt_tokens_details {:cached_tokens 50}}})
          (session/append-entry sess {:role :user :content [{:type :text :text "q2"}]})
          (let [branch (session/get-branch sess)
                first-kept-id (:id (nth branch 1))]
          ;; kept tail starts at the assistant — the compaction stays in context
            (session/compact-with-summary! sess "SUMMARY" first-kept-id
                                           {:usage {:prompt_tokens 500 :completion_tokens 100
                                                    :prompt_tokens_details {:cached_tokens 100}
                                                    :cost {:total 0.01}}})
            (let [p (fdp/make-footer-data-provider :session sess)]
              (is (= 50.0 (fdp/fdp-latest-cache-hit-rate p))
                  "assistant rate (50/100), not the compaction's (100/500) — pi: assistant messages only")
              (is (nil? (fdp/fdp-context-tokens p))
                  "kept-tail assistant predates the compaction in the branch — its usage reflects the old context; unknown until the next response (pi)"))))))))

(deftest test-latest-cache-hit-rate
  (testing "cache hit rate comes from the most recent usage entry (input excludes cache, pi)"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 50}}})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "b"}]
                                    :usage {:prompt_tokens 200 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 100}}})
        (let [p (fdp/make-footer-data-provider :session sess)]
          (is (= 50.0 (fdp/fdp-latest-cache-hit-rate p))
              "100 cached / (100 input + 100 cached) — pi: cacheRead / (input+cacheRead+cacheWrite)"))))))

(deftest test-latest-cache-hit-rate-after-compaction
  ;; Regression: append-only compaction keeps pre-compaction messages in the
  ;; branch; the rate must come from the context (build-context), so a stale
  ;; pre-compaction rate (reflecting the old context) is not shown.
  (testing "stale pre-compaction usage is not picked up"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "q1"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "old"}]
                                    :usage {:prompt_tokens 1000 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 0}}})
        (session/append-entry sess {:role :user :content [{:type :text :text "q2"}]})
        (let [branch (session/get-branch sess)
              first-kept-id (:id (nth branch 2))]
          (session/compact-with-summary! sess "SUMMARY" first-kept-id)
          (let [p (fdp/make-footer-data-provider :session sess)]
            (is (nil? (fdp/fdp-latest-cache-hit-rate p))
                "no post-compaction usage → nil, not the stale 0% pre-compaction rate")))))))

(deftest test-model-provider-thinking
  (testing "model/provider/thinking/reasoning accessors"
    (let [p (fdp/make-footer-data-provider :model "m" :provider :openai :thinking :high :reasoning true)]
      (is (= "m" (fdp/fdp-get-model p)))
      (is (= :openai (fdp/fdp-get-provider p)))
      (is (= :high (fdp/fdp-get-thinking p)))
      (is (true? (fdp/fdp-get-reasoning p))))
    (testing "reasoning defaults to false and is swappable"
      (let [p (fdp/make-footer-data-provider)]
        (is (false? (fdp/fdp-get-reasoning p)))
        (fdp/fdp-set-reasoning! p true)
        (is (true? (fdp/fdp-get-reasoning p)))))))

(ns kmet.app.compaction.test-stage
  "The compaction orchestrator's planning step (prepare-compaction): what it
   reports for a compactable branch, the manual already-compacted case, and
   an empty session. The orchestration itself is covered by
   kmet.app.loop.test-compact."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.app.compaction.stage :as stage]
            [kmet.app.loop :as loop]
            [kmet.app.session.core :as session]
            [kmet.test-tmp :as test-tmp]))

(defn- with-session-agent
  "Run F with a fresh session and an agent over it that keeps a tiny recent
   tail, so any real history offers something to summarize."
  [f]
  (let [dir (test-tmp/temp-dir! "sess-")
        sess (session/create-session dir)]
    (try
      (f sess (loop/make-agent-state :session sess :keep-recent-tokens 5))
      (finally
        (fs/delete-tree dir)))))

(defn- append-messages!
  "Append N token-heavy user messages to SESS."
  [sess n]
  (dotimes [i n]
    (session/append-entry
     sess
     {:role :user
      :content [{:type :text
                 :text (str "Message number " i " with plenty of words so the "
                            "estimated token count exceeds the tiny recent tail.")}]})))

(deftest prepare-compaction-plans-a-compactable-branch
  (testing "history before the kept tail yields the entries and the prepared cut"
    (with-session-agent
      (fn [sess agent]
        (append-messages! sess 12)
        (let [{:keys [entries prep]} ((var stage/prepare-compaction) agent :auto)]
          (is (= 12 (count entries))
              "the branch entries ride along for the boundary event")
          (is (= 12 (count (session/get-branch sess)))
              "planning never mutates the session")
          (is (map? prep))
          (is (seq (:messages prep)) "there is something to summarize")
          (is (string? (:first-kept-id prep)))
          (is (pos? (:tokens-before prep))))))))

(deftest prepare-compaction-reports-an-already-compacted-branch
  (testing "a manual compaction of an already-compacted session reports pi's reason"
    (with-session-agent
      (fn [sess agent]
        (append-messages! sess 3)
        (session/append-entry sess {:role :compaction :summary "old"})
        (is (= {:problem "Already compacted"}
               ((var stage/prepare-compaction) agent :manual)))
        (testing "the automatic paths stay silent about it"
          (is (nil? ((var stage/prepare-compaction) agent :auto)))
          (is (nil? ((var stage/prepare-compaction) agent :threshold))))))))

(deftest prepare-compaction-passes-over-an-empty-session
  (testing "nothing to summarize plans nothing (both paths)"
    (with-session-agent
      (fn [_sess agent]
        (is (nil? ((var stage/prepare-compaction) agent :manual)))
        (is (nil? ((var stage/prepare-compaction) agent :auto)))))))

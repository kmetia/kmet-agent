(ns kmet.app.tools.test-session-recall
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.session.core :as session]
            [kmet.app.tools.session-recall :as recall]))

(defn- with-session [f]
  (let [dir "target/test-session-recall"
        sess (session/create-session dir {:cwd (str (fs/cwd))})]
    (try (f sess) (finally (fs/delete-tree dir)))))

(defn- add-turn! [sess user-text assistant-text]
  (session/append-entry sess {:role :user :content [{:type :text :text user-text}]})
  (session/append-entry sess {:role :assistant
                              :content [{:type :text :text assistant-text}]}))

(t/deftest search-finds-an-earlier-turn-and-skips-the-current-one
  (with-session
    (fn [sess]
      (add-turn! sess "fix the auth token refresh bug" "checking session.ts")
      (add-turn! sess "now add a test" "working on it")
      (let [text (recall/run-recall sess {:query "auth token"})]
        (t/is (str/includes? text "#0 [user]"))
        (t/is (str/includes? text "auth token refresh"))
        (t/is (not (str/includes? text "working on it")))))))

(t/deftest range-expand-touched-and-drill-down
  (with-session
    (fn [sess]
      (session/append-entry sess {:role :user :content [{:type :text :text "edit it"}]})
      (session/append-entry sess {:role :assistant :content []
                                  :tool-calls [{:id "c1" :name "edit"
                                                :arguments {:path "/src/a.ts"
                                                            :oldText "old" :newText "new"}}]})
      (session/append-entry sess {:role :tool :tool-name "edit" :is-error false
                                  :content [{:type :tool_result :tool_use_id "c1"
                                             :content "ok"}]})
      (t/is (str/includes? (recall/run-recall sess {:range [0 2]}) "Range #0..#2"))
      (let [expanded (recall/run-recall sess {:expand [1]})]
        (t/is (str/includes? expanded "Expanded #1"))
        (t/is (str/includes? expanded "[edit] ok")))
      (t/is (str/includes? (recall/run-recall sess {:mode "touched"}) "a.ts"))
      (t/is (str/includes? (recall/run-recall sess {:query "#1:a.ts"}) "old")))))

(t/deftest scope-all-reaches-an-abandoned-branch
  (with-session
    (fn [sess]
      (let [u (session/append-entry sess {:role :user
                                          :content [{:type :text :text "abandoned decision"}]})]
        (session/append-entry sess {:role :assistant :content [{:type :text :text "old answer"}]})
        (session/branch! sess (:id u))
        (session/append-entry sess {:role :assistant :content [{:type :text :text "active answer"}]})
        (t/is (str/includes? (recall/run-recall sess {:query "abandoned decision"
                                                      :scope "all"})
                             "abandoned decision"))
        (t/is (str/includes? (recall/run-recall sess {:query "abandoned decision"})
                             "No earlier matches"))))))

(t/deftest params-are-repaired-or-named
  (let [p (recall/normalize-params {:expand "#1, 2" :page "2" :scope "ALL"
                                    :bogus 1})]
    (t/is (= [1 2] (:expand p)))
    (t/is (= 2 (:page p)))
    (t/is (= "all" (:scope p)))
    (t/is (some #(str/includes? % "bogus") (:_vcc-dropped p)))))

(t/deftest out-of-range-pages-explain-themselves
  (with-session
    (fn [sess]
      (add-turn! sess "one request here" "answer one")
      (add-turn! sess "two request here" "answer two")
      (t/is (str/includes? (recall/run-recall sess {:range [0 3] :page 9})
                           "outside the available range"))
      (t/is (str/includes? (recall/run-recall sess {:mode "touched" :page 9})
                           "No file operations found")))))

(t/deftest output-cap-does-not-split-a-surrogate-pair
  (let [cap @#'recall/output-cap
        text (str (apply str (repeat (dec cap) "x"))
                  (char 0xD83D) (char 0xDE00))
        clipped (#'recall/cap-output text)]
    (t/is (not (re-find (re-pattern (str "[" (char 0xD800) "-"
                                         (char 0xDBFF) "]\\n"))
                        clipped)))))

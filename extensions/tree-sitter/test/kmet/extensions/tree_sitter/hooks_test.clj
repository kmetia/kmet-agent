(ns kmet.extensions.tree-sitter.hooks-test
  "Hook behavior with stubbed validators (offline) plus one guarded
   integration through the real CLI."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]
            [kmet.extensions.tree-sitter.hooks :as hooks]
            [kmet.extensions.tree-sitter.test-util :as tu]
            [kmet.extensions.tree-sitter.validate :as validate]))

(def ^:private py-problems
  [{:kind :error :line 2 :col 3 :expected nil :snippet "x = (1"}])

(defmacro with-stub
  "Redef parse-problems! for the body: PROBLEMS vector or nil."
  [problems & body]
  `(with-redefs [validate/parse-problems!
                 (fn [& _#] (when-some [p# ~problems] {:problems p#}))]
     ~@body))

(defn- run-hook
  "Run the extension's around hook over OP with NEXT answering RESULT, and
   report whether it descended — i.e. whether the tool body would have run."
  [{:keys [tool-name args]} result]
  (let [descended (atom false)
        r (hooks/on-tool {:tool-name tool-name :args args}
                         (fn [_] (reset! descended true) result))]
    {:result r :descended? @descended}))

(defn- call-write [args]
  (run-hook {:tool-name "write" :args args} {:content "wrote"}))

(defn- call-edit-result [f result]
  (run-hook {:tool-name "edit" :args {:path f}} result))

(deftest ^:bb-only write-block-format-test
  (with-stub py-problems
    (let [{:keys [result descended?]} (call-write {:path "/tmp/x.py" :content "x = (1 +"})]
      (is (some? result))
      (is (:block result))
      (is (not descended?) "a blocked write never reaches the body")
      (is (str/includes? (:reason result) "line 2, col 3"))
      (is (str/includes? (:reason result) "Write blocked — ")))))

(deftest ^:bb-only write-clean-passes-test
  (with-stub nil
    (let [{:keys [result descended?]} (call-write {:path "/tmp/ok.py"
                                                   :content "def f():\n    return 1\n"})]
      (is descended? "a clean file runs the body")
      (is (= {:content "wrote"} result) "and its result passes through"))))

(deftest ^:bb-only only-write-intercepted-test
  (testing "edit is handled after the body, not before it"
    (with-stub py-problems
      (let [{:keys [descended?]} (run-hook {:tool-name "edit"
                                            :args {:path "/tmp/x.py" :content "x = (1 +"}}
                                           {:content "edited" :is-error false})]
        (is descended?)))))

(deftest ^:bb-only unknown-ext-passes-test
  (is (:descended? (call-write {:path "/tmp/notes.md" :content "# hello )"}))))

(deftest ^:bb-only clojure-deferred-to-paren-repair-test
  ;; clojure extension present -> tree-sitter never gates clojure files,
  ;; even when the delimiter scanner would find problems
  (dispatch/set-api! {:get-all-tools (fn [] [{:name "clojure_edit"}])})
  (with-stub [{:kind :unclosed :line 1 :col 1 :expected ")" :snippet "("}]
    (is (:descended? (call-write {:path "/tmp/broken.clj" :content "(defn f [x]"}))))
  (testing "without the clojure extension the delimiter fallback blocks"
    (dispatch/set-api! {:get-all-tools (fn [])})
    (with-stub [{:kind :unclosed :line 1 :col 1 :expected ")" :snippet "("}]
      (let [{:keys [result descended?]} (call-write {:path "/tmp/broken.clj"
                                                     :content "(defn f [x]"})]
        (is (not descended?))
        (is (:block result))
        (is (str/includes? (:reason result) "never closed")))))
  (testing "balanced clojure content passes the delimiter fallback"
    (dispatch/set-api! {:get-all-tools (fn [])})
    (is (:descended? (call-write {:path "/tmp/ok.clj"
                                  :content "(defn f [x]\n  (inc x))\n"})))))

(deftest ^:bb-only never-throw-test
  (with-redefs [validate/parse-problems!
                (fn [& _] (throw (ex-info "boom" {})))]
    (is (:descended? (call-write {:path "/tmp/x.py" :content "whatever"})))))

;; ─── edit post-hook ───────────────────────────────────────────────────────

(defn- broken-file-fixture! []
  (let [dir (tu/temp-dir! "ts-hooks")
        f (str (fs/path dir "svc.py"))]
    (spit f "def load(uid):\n    return db_fetch(uid)\n")
    [dir f]))

(deftest ^:bb-only edit-warn-appends-test
  (let [[dir f] (broken-file-fixture!)]
    (try
      (with-stub [{:kind :error :line 2 :col 5
                   :expected nil :snippet "oops"}]
        (let [{:keys [result descended?]} (call-edit-result f {:content "Edited successfully."})]
          (is descended?)
          (is (str/includes? (:content result) "⚠️"))
          (is (str/includes? (:content result) "Edited successfully."))))
      (finally (fs/delete-tree dir)))))

(deftest ^:bb-only edit-clean-no-warning-test
  (let [[dir f] (broken-file-fixture!)]
    (try
      (with-stub nil
        (let [{:keys [result]} (call-edit-result f {:content "ok"})]
          (is (= {:content "ok"} result) "a clean file leaves the result untouched")))
      (finally (fs/delete-tree dir)))))

(deftest ^:bb-only edit-error-result-not-inspected-test
  (let [{:keys [result descended?]}
        (run-hook {:tool-name "edit" :args {:path "/tmp/x.py"}}
                  {:content "" :is-error true})]
    (is descended?)
    (is (= {:content "" :is-error true} result))))

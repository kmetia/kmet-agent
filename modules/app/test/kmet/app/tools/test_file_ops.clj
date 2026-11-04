(ns kmet.app.tools.test-file-ops
  "The file ops the tools and the eval verbs share: the same bytes and the
   same :edit-error messages through either entry point."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
            [kmet.app.tools.edit :as edit]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.tools.write :as write]
            [kmet.test-utils :as tu]
            [kmet.test-tmp :as test-tmp]))

(def ^:private reg
  "One registry for the namespace: the built-in rows, as a run's tools resolve."
  (tu/tool-registry))

(defn- with-dir [f]
  (let [dir (str (fs/absolutize (test-tmp/temp-dir! "test-file-ops-")))]
    (fs/create-dirs dir)
    (try (binding [tool-util/*cwd* dir] (f dir))
         (finally (fs/delete-tree dir)))))

(defn- tool-call [name args]
  (tools/execute-tool reg name args {}))

(t/deftest test-edit-file-and-the-tool-produce-the-same-bytes
  (with-dir
    (fn [dir]
      (let [path "edit.txt"
            content "alpha\nbeta\n"
            edits [{:old-text "alpha" :new-text "ALPHA"}]
            _ (spit (str dir "/" path) content)
            tool-result (tool-call "edit" {:path path :edits edits})
            tool-bytes (slurp (str dir "/" path))
            _ (spit (str dir "/" path) content)
            op-result (edit/edit-file path edits nil)]
        (t/is (not (:is-error tool-result)) (:content tool-result))
        (t/is (= "ALPHA\nbeta\n" tool-bytes))
        (t/is (= tool-bytes (slurp (str dir "/" path)))
              "the op writes the same bytes as the tool")
        (t/is (= (get-in tool-result [:details :diff]) (:diff op-result))
              "and reports the same applied diff")
        (t/is (some? (:diff op-result)))
        (t/is (= 1 (:edits op-result)))))))

(t/deftest test-write-file-and-the-tool-produce-the-same-bytes
  (with-dir
    (fn [dir]
      (let [path "nested/out.txt"
            content "hello"
            tool-result (tool-call "write" {:path path :content content})
            tool-bytes (slurp (str dir "/" path))]
        (fs/delete-tree (str dir "/nested"))
        (let [op-result (write/write-file path content nil)]
          (t/is (not (:is-error tool-result)) (:content tool-result))
          (t/is (= content tool-bytes) "the tool writes the content")
          (t/is (= tool-bytes (slurp (str dir "/" path)))
                "the op writes the same bytes, creating the parent directory")
          (t/is (= 5 op-result)))))))

(def ^:private edit-error-cases
  [{:case :not-found
    :path "missing.txt"
    :edits [{:old-text "a" :new-text "b"}]}
   {:case :duplicate
    :path "duplicate.txt"
    :content "x x"
    :edits [{:old-text "x" :new-text "y"}]}
   {:case :no-change
    :path "no-change.txt"
    :content "abc"
    :edits [{:old-text "abc" :new-text "abc"}]}
   {:case :empty-edits
    :path "empty.txt"
    :content "abc"
    :edits []}])

(t/deftest test-edit-errors-match-through-both-entry-points
  (with-dir
    (fn [dir]
      (doseq [{:keys [case path content edits]} edit-error-cases]
        (when content
          (spit (str dir "/" path) content))
        (let [tool-result (tool-call "edit" {:path path :edits edits})
              op-error (try
                         (edit/edit-file path edits nil)
                         nil
                         (catch Exception e e))]
          (t/is (:is-error tool-result) (str case))
          (t/is (instance? clojure.lang.ExceptionInfo op-error) (str case))
          (t/is (= :edit-error (:type (ex-data op-error))) (str case))
          (t/is (= (ex-message op-error) (:content tool-result))
                "the op and the tool report the same :edit-error message"))))))

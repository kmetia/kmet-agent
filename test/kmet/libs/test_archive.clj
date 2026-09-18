(ns kmet.libs.test-archive
  ;; Zip extraction tests for kmet.libs.archive (java.util.zip on both
  ;; hosts).
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kmet.libs.archive :as archive]))

(defn- make-zip!
  "Write a zip with {entry-name content-string} entries; returns its path."
  [zip-path entries]
  (with-open [zos (java.util.zip.ZipOutputStream.
                   (io/output-stream (fs/file zip-path)))]
    (doseq [[entry content] entries]
      (.putNextEntry zos (java.util.zip.ZipEntry. entry))
      (.write zos (.getBytes content "UTF-8"))
      (.closeEntry zos)))
  zip-path)

(deftest extract-zip!-happy-path
  (let [tmp (str (fs/create-dirs "target/test-archive-extract") "")]
    (try
      (let [zip (make-zip! (str (fs/path tmp "a.zip"))
                           {"bin/tool" "#!/bin/sh\necho hi\n"
                            "nested/dir/readme.txt" "contents"})
            out (fs/path tmp "out")
            extracted (archive/extract-zip! zip out)]
        (is (= 2 (count extracted)))
        (is (= "#!/bin/sh\necho hi\n" (slurp (str (fs/path out "bin/tool")))))
        (is (= "contents" (slurp (str (fs/path out "nested/dir/readme.txt"))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest extract-zip!-zip-slip-guard
  (let [tmp (str (fs/create-dirs "target/test-archive-slip") "")]
    (try
      (doseq [entry ["../evil.txt"
                     "/abs/evil.txt"
                     (str ".." (char 92) "evil")
                     (str "sub" (char 92) ".." (char 92) ".." (char 92) "evil")]]
        (let [zip (make-zip! (str (fs/path tmp "evil.zip")) {entry "nope"})
              out (fs/path tmp (str "out-" (count entry) "-" (rand-int 1000000)))]
          (is (thrown-with-msg? Exception #"zip entry escapes target dir"
                                (archive/extract-zip! zip out))
              (str "entry " (pr-str entry) " throws ::zip-slip"))
          (when (fs/exists? out)
            (is (empty? (filter #(not (fs/directory? %)) (fs/list-dir out)))
                (str "no file written for " (pr-str entry))))))
      (finally
        (fs/delete-tree tmp)))))

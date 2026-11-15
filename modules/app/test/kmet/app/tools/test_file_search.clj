(ns kmet.app.tools.test-file-search
  "The eval sandbox's ignore-aware glob and grep verbs."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.file-search :as search]
            [kmet.test-tmp :as test-tmp]))

(defn- fixture
  "Create DIR with FILES, a map of relative path to content."
  [files]
  (let [dir (str (fs/absolutize (test-tmp/temp-dir! "file-search-")))]
    (doseq [[path content] files]
      (let [file (fs/path dir path)]
        (fs/create-dirs (fs/parent file))
        (spit (str file) content)))
    dir))

(t/deftest test-glob-matches-patterns-braces-and-limits
  (let [dir (fixture {"root.clj" ""
                      "src/a.clj" ""
                      "src/b.edn" ""
                      "test/c.clj" ""
                      "target/d.clj" ""})]
    (t/is (= #{"root.clj" "src/a.clj" "test/c.clj" "target/d.clj"}
             (set (:paths (search/glob dir "**/*.clj" {:path dir})))))
    (t/is (= ["src/a.clj"] (:paths (search/glob dir "src/*.clj" {:path dir}))))
    (t/is (= ["src/a.clj" "src/b.edn"]
             (:paths (search/glob dir "src/*.{clj,edn}" {:path dir}))))
    (let [r (search/glob dir "**/*.clj" {:path dir :max 2})]
      (t/is (= 4 (:total r)))
      (t/is (= 2 (count (:paths r))))
      (t/is (:truncated? r)))
    (t/is (= #{"root.clj" "src/a.clj"}
             (set (:paths (search/glob dir "**/*.clj"
                                       {:path dir :exclude ["test/**" "target/**"]})))))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be relative"
                            (search/glob dir "**/*.clj"
                                         {:path dir
                                          :exclude (str (fs/path dir "target"))})))))

(t/deftest test-grep-matches-options-and-path-display
  (let [dir (fixture {"src/a.clj" "(def needle 1)\n(needle)\n"
                      "src/b.edn" "needle\n"
                      "README.md" "Needle\n"})]
    (let [r (search/grep dir "needle" {:path dir})]
      (t/is (= 3 (:files r)))
      (t/is (= 3 (count (:matches r))))
      (t/is (every? #(not (str/starts-with? (:path %) "/")) (:matches r))))
    (let [r (search/grep dir "needle" {:path dir :glob "**/*.clj"})]
      (t/is (= 2 (count (:matches r))))
      (t/is (every? #(= "src/a.clj" (:path %)) (:matches r))))
    (let [r (search/grep dir "needle" {:path dir :glob "README.md" :ignore-case? true})]
      (t/is (= 1 (count (:matches r))))
      (t/is (= "Needle" (:text (first (:matches r))))))
    (let [r (search/grep dir "needle" {:path dir :max 1})]
      (t/is (= 1 (count (:matches r))))
      (t/is (:truncated? r)))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (search/grep dir "needle"
                                         {:path (str (fs/path dir "missing"))})))))

(t/deftest test-grep-skips-binary-and-oversized-files
  (let [dir (fixture {"a.txt" "needle\n"
                      "bin.dat" (str "needle" (char 0) "tail")
                      "big.txt" "needle\n"})]
    (let [r (search/grep dir "needle" {:path dir})]
      (t/is (some #(= "binary" (:reason %)) (:skipped r)))
      (t/is (pos? (:skipped-count r))))
    (let [r (search/grep dir "needle" {:path dir :max-file-bytes 1})]
      (t/is (= 0 (:files r)))
      (t/is (pos? (:skipped-count r)))
      (t/is (every? #(str/includes? (:reason %) "larger than") (:skipped r))))))

(t/deftest test-glob-metacharacters-in-patterns-and-grep-filters
  (let [dir (fixture {"root.txt" "needle\n"
                      "src/a.clj" "needle\n"
                      "src/b.clj" "needle\n"
                      "src/c.edn" "needle\n"})]
    (doseq [[pattern expected] [["src/?.clj" ["src/a.clj" "src/b.clj"]]
                                ["src/[ab].clj" ["src/a.clj" "src/b.clj"]]
                                ["src/[!a].clj" ["src/b.clj"]]
                                ["**/*.{clj,edn}" ["src/a.clj" "src/b.clj" "src/c.edn"]]]]
      (t/testing pattern
        (t/is (= expected (:paths (search/glob dir pattern nil))))
        (t/is (= expected
                 (mapv :path (:matches (search/grep dir "needle" {:glob pattern})))))))
    (t/is (= ["root.txt" "src/c.edn"]
             (mapv :path (:matches (search/grep dir "needle" {:exclude "**/*.clj"})))))))

(t/deftest test-native-separators-and-spaces-in-search-paths
  (let [dir (fixture {"project space/code/src/a.txt" "Needle\r\nneedle\r\n"
                      "project space/code/src/b.txt" "needle\r\n"
                      "project space/code/~notes.txt" "needle\r\n"})
        base (str (fs/path dir "project space" "code"))
        expected [{:path "project space/code/src/a.txt" :line 1 :text "Needle"}
                  {:path "project space/code/src/a.txt" :line 2 :text "needle"}]]
    (doseq [opts [{:path (str (fs/path "project space" "code"))
                   :glob (str "src" fs/file-separator "?.txt")
                   :exclude (str "**" fs/file-separator "b.*")}
                  {:path (str/replace base fs/file-separator "/")
                   :glob "src/?.txt" :exclude ["**/b.*"]}]]
      (let [globbed (search/glob dir (:glob opts) (dissoc opts :glob))
            grepped (search/grep dir "needle" (assoc opts :ignore-case? true))]
        (t/is (= ["project space/code/src/a.txt"] (:paths globbed)))
        (t/is (= expected (:matches grepped)))))
    (let [single (search/grep dir "needle"
                              {:path (str (fs/path base "src" "a.txt"))
                               :ignore-case? true})]
      (t/is (= expected (:matches single))))
    (t/is (= ["project space/code/~notes.txt"]
             (:paths (search/glob dir "~notes.txt" {:path base}))))))

(t/deftest test-search-patterns-must-be-relative-on-every-platform
  (let [dir (fixture {"a.txt" "needle\n"})]
    (doseq [pattern ["/**/*.txt" "C:/*.txt" "c:\\src\\?.txt"
                     "\\\\server\\share\\**\\*.txt" "//server/share/**/*.txt"
                     "\\root\\*.txt" "C:*.txt" "~" "~/*.txt" "~\\*.txt"]
            [option run] [["PATTERN" #(search/glob dir pattern nil)]
                          [":glob" #(search/grep dir "needle" {:glob pattern})]
                          [":exclude" #(search/glob dir "**/*" {:exclude pattern})]
                          [":exclude" #(search/grep dir "needle" {:exclude [pattern]})]]]
      (let [error (try (run) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (t/is (= {:type :kmet.app.tools.file-search/absolute-pattern
                  :option option :pattern pattern}
                 error)
              (str option " " (pr-str pattern)))))))

(t/deftest ^:slow test-gitignore-and-explicit-ignored-directory
  (when (fs/which "git")
    (let [dir (fixture {"src/a.clj" "needle\n"
                        "build/y.clj" "needle\n"
                        "ignored.txt" "needle\n"
                        "target/x.clj" "needle\n"})]
      (spit (str (fs/path dir ".gitignore")) "ignored.txt\ntarget/\n")
      @(proc/process ["git" "-C" dir "init"] {:out :discard :err :discard})
      (let [r (search/grep dir "needle" {:path dir})]
        (t/is (= #{"src/a.clj" "build/y.clj"}
                 (set (map :path (:matches r))))))
      (let [r (search/grep dir "needle" {:path (str (fs/path dir "target"))})]
        (t/is (= #{"target/x.clj"} (set (map :path (:matches r))))))
      (let [r (search/grep dir "needle" {:path dir :gitignore? false})]
        (t/is (some #(= "ignored.txt" (:path %)) (:matches r)))
        (t/is (some #(= "target/x.clj" (:path %)) (:matches r)))))))

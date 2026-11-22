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

(t/deftest test-glob-accepts-absolute-patterns
  (let [dir (fixture {"root.clj" ""
                      "src/a.clj" ""
                      "src/b.edn" ""})]
    (t/is (= #{"root.clj" "src/a.clj"}
             (set (:paths (search/glob dir (str (fs/path dir "**" "*.clj")) nil)))))
    (t/is (= ["src/a.clj"]
             (:paths (search/glob dir (str (fs/path dir "src" "*.clj")) nil))))
    (t/is (= ["root.clj"]
             (:paths (search/glob dir (str (fs/path dir "root.clj")) nil))))
    (t/is (= #{"root.clj" "src/a.clj" "src/b.edn"}
             (set (:paths (search/glob dir (str dir "/") nil))))
          "a trailing separator lists the directory's files")
    (t/is (= ["src/a.clj"]
             (:paths (search/glob dir (str (fs/path dir "src" "*.clj"))
                                  {:path (str (fs/path dir "missing"))})))
          ":path is ignored for an absolute PATTERN")))

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

(t/deftest test-grep-argument-and-regex-errors-are-clear
  (let [dir (fixture {"a.txt" "needle\n"})]
    (let [error (try (search/grep dir {:path dir :pattern "needle"} nil)
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (instance? clojure.lang.ExceptionInfo error))
      (t/is (= :kmet.app.tools.file-search/bad-arguments (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) "PATTERN first"))
      (t/is (str/includes? (ex-message error) ":pattern")))
    (let [error (try (search/grep dir "(" {:path dir})
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (instance? clojure.lang.ExceptionInfo error))
      (t/is (= :kmet.app.tools.file-search/invalid-regex (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) "Invalid grep regex")))))

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

(t/deftest test-search-filters-must-stay-relative
  (let [dir (fixture {"a.txt" "needle\n"})
        absolute (str (fs/path dir "**" "*.txt"))]
    (doseq [pattern [absolute "/**/*.txt" "C:/*.txt"
                     "\\\\server\\share\\**\\*.txt" "//server/share/**/*.txt"
                     "\\root\\*.txt" "C:*.txt" "~" "~/*.txt" "~\\*.txt"]
            [option run] [["PATTERN" #(search/glob dir pattern nil)]
                          [":glob" #(search/grep dir "needle" {:glob pattern})]
                          [":exclude" #(search/glob dir "**/*" {:exclude pattern})]
                          [":exclude" #(search/grep dir "needle" {:exclude [pattern]})]]
            :when (or (not= option "PATTERN")
                      (not (fs/absolute? pattern)))]
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

(t/deftest test-grep-context-lines
  (let [dir (fixture {"src/a.clj" "one\nneedle two\nthree\nneedle four\nfive\n"
                      "src/b.edn" "needle\n"})]
    (let [r (search/grep dir "needle" {:path dir :context 1})]
      (t/is (= [{:path "src/a.clj" :line 2 :text "needle two"}
                {:path "src/a.clj" :line 4 :text "needle four"}
                {:path "src/b.edn" :line 1 :text "needle"}]
               (:matches r)))
      (t/is (= ["src/a.clj-1- one"
                "src/a.clj:2: needle two"
                "src/a.clj-3- three"
                "src/a.clj:4: needle four"
                "src/a.clj-5- five"
                "src/b.edn:1: needle"]
               (:lines r))))
    (t/is (not (contains? (search/grep dir "needle" {:path dir}) :lines)))
    (t/is (not (contains? (search/grep dir "needle" {:path dir :context 0}) :lines)))))

(t/deftest test-grep-context-after-max-stays-context
  (let [dir (fixture {"edges.txt" "needle one\ntwo\nneedle three\nneedle four\nfive\n"})]
    (let [r (search/grep dir "needle" {:path dir :context 1 :max 2})]
      (t/is (= [1 3] (mapv :line (:matches r))))
      (t/is (:truncated? r))
      (t/is (= ["edges.txt:1: needle one"
                "edges.txt-2- two"
                "edges.txt:3: needle three"
                "edges.txt-4- needle four"]
               (:lines r))))
    (let [r (search/grep dir "needle" {:path dir :context 1 :max 10})]
      (t/is (not (:truncated? r)))
      (t/is (= ["edges.txt:1: needle one"
                "edges.txt-2- two"
                "edges.txt:3: needle three"
                "edges.txt:4: needle four"
                "edges.txt-5- five"]
               (:lines r))))))

(t/deftest test-grep-context-with-filters-and-skips
  (let [dir (fixture {"src/a.clj" "x\nneedle\ny\n"
                      "src/b.edn" "needle\n"
                      "bin.dat" (str "needle" (char 0) "tail")})]
    (let [r (search/grep dir "needle" {:path dir :glob "**/*.clj" :context 1})]
      (t/is (= ["src/a.clj-1- x" "src/a.clj:2: needle" "src/a.clj-3- y"]
               (:lines r))))
    (let [r (search/grep dir "needle" {:path dir :exclude "**/*.edn" :context 1})]
      (t/is (not-any? #(= "src/b.edn" (:path %)) (:matches r)))
      (t/is (some #(= "bin.dat" (:path %)) (:skipped r))))))

(t/deftest test-glob-stat-entries
  (let [dir (fixture {"a.txt" "abc" "sub/b.txt" ""})
        mtime 1609459200000]
    (fs/set-last-modified-time (fs/path dir "a.txt")
                               (fs/millis->file-time mtime))
    (fs/set-last-modified-time (fs/path dir "sub" "b.txt")
                               (fs/millis->file-time mtime))
    (let [r (search/glob dir "**/*.txt" {:path dir :stat true})]
      (t/is (= ["a.txt" "sub/b.txt"] (:paths r)))
      (t/is (= (mapv :path (:entries r)) (:paths r)))
      (t/is (= [3 0] (mapv :size (:entries r))))
      (t/is (every? (fn [{:keys [modified]}]
                      (<= -2000 (- mtime modified) 2000))
                    (:entries r)))
      (t/is (= 2 (:total r)))
      (t/is (not (:truncated? r))))
    (let [r (search/glob dir "**/*.txt" {:path dir})]
      (t/is (not (contains? r :entries)))
      (t/is (= ["a.txt" "sub/b.txt"] (:paths r))))
    (let [r (search/glob dir "**/*.txt" {:path dir :stat true :max 1})]
      (t/is (= ["a.txt"] (:paths r)))
      (t/is (= ["a.txt"] (mapv :path (:entries r))))
      (t/is (= 2 (:total r)))
      (t/is (:truncated? r)))))

(t/deftest test-glob-stat-survives-stat-failures
  (let [dir (fixture {"a.txt" "abc" "b.txt" "de"})]
    (with-redefs [fs/size (fn [_] (throw (ex-info "no size" {})))
                  fs/last-modified-time (fn [_] (throw (ex-info "no mtime" {})))]
      (let [r (search/glob dir "**/*.txt" {:path dir :stat true})]
        (t/is (= ["a.txt" "b.txt"] (:paths r)))
        (t/is (= ["a.txt" "b.txt"] (mapv :path (:entries r))))
        (t/is (every? #(and (nil? (:size %)) (nil? (:modified %)))
                      (:entries r)))))))

(t/deftest test-grep-context-clamps-at-file-edges
  (let [dir (fixture {"edges.txt" "needle one\nmid\nlast needle\n"})]
    (let [r (search/grep dir "needle" {:path dir :context 3})]
      (t/is (= ["edges.txt:1: needle one"
                "edges.txt-2- mid"
                "edges.txt:3: last needle"]
               (:lines r))))
    (let [r (search/grep dir "needle" {:path dir :context -2})]
      (t/is (not (contains? r :lines)))
      (t/is (= [1 3] (mapv :line (:matches r)))))))

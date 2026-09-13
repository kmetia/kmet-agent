(ns kmet.tasks.format-test
  "Tests for the format task wrapper: the managed-file enumeration excludes
   build output and the generated provider catalogs, and the cljfmt
   invocation runs — on both hosts (the rewrite-clj pin makes jolt's
   parser complete; see jolt-bugs.md#978)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.tasks.format :as format]))

(defn- dir-segments [p]
  (str/split (str p) #"[\\/]"))

(t/deftest test-source-paths
  (let [paths (#'format/source-paths)]
    (t/is (contains? (set paths) (str (fs/file "src" "kmet" "core.clj")))
          "source files are included")
    (t/is (contains? (set paths) (str (fs/file "test" "kmet" "app" "test_loop.clj")))
          "test files are included")
    (t/is (some #(str/ends-with? (str %) (str (fs/file "format.clj"))) paths)
          "task files are included")
    (t/is (not-any? #(some #{"model_data" "image_model_data"} (dir-segments %)) paths)
          "the generated provider catalogs are excluded")
    (t/is (not-any? #(some #{"target"} (dir-segments %)) paths)
          "build output is excluded")))

(t/deftest test-cljfmt-runs
  (t/testing "the wrapper resolves cljfmt.tool and checks a file"
    (let [dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
          f (fs/create-temp-file {:prefix "kmet-fmt-" :suffix ".clj" :dir dir})]
      (try
        (spit (str f) "(ns t)\n\n(defn f [x]\n  x)\n")
        (t/is (nil? (format/format-check! [(str f)])))
        (finally (fs/delete-if-exists f))))))

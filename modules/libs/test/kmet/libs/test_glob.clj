(ns kmet.libs.test-glob
  "The shared glob matcher."
  (:require [clojure.test :as t]
            [kmet.libs.glob :as glob]))

(defn- match?
  [pattern path]
  (glob/matches? (glob/compile-pattern pattern) path))

(t/deftest test-star-stays-in-segment
  (t/is (match? "*.clj" "core.clj"))
  (t/is (not (match? "*.clj" "src/core.clj")))
  (t/is (match? "src/*.clj" "src/core.clj"))
  (t/is (not (match? "src/*.clj" "src/app/core.clj")))
  (t/is (match? "a*b" "ab")))

(t/deftest test-double-star-crosses-segments
  (t/is (match? "**/*.clj" "core.clj"))
  (t/is (match? "**/*.clj" "src/core.clj"))
  (t/is (match? "**/*.clj" "src/app/core.clj"))
  (t/is (match? "src/**" "src/core.clj"))
  (t/is (match? "src/**" "src/app/core.clj"))
  (t/is (not (match? "src/**" "src")))
  (t/is (match? "src/**/core.clj" "src/core.clj"))
  (t/is (match? "src/**/core.clj" "src/app/core.clj")))

(t/deftest test-question-and-character-classes
  (t/is (match? "?.clj" "a.clj"))
  (t/is (not (match? "?.clj" "ab.clj")))
  (t/is (not (match? "?.clj" "a/b.clj")))
  (t/is (match? "file[0-9].clj" "file7.clj"))
  (t/is (not (match? "file[0-9].clj" "filex.clj")))
  (t/is (match? "file[!0-9].clj" "filex.clj"))
  (t/is (not (match? "file[!0-9].clj" "file7.clj")))
  (t/is (match? "[abc" "[abc")))

(t/deftest test-braces-expand
  (t/is (match? "src/*.{clj,cljc}" "src/core.clj"))
  (t/is (match? "src/*.{clj,cljc}" "src/core.cljc"))
  (t/is (not (match? "src/*.{clj,cljc}" "src/core.edn")))
  (t/is (match? "{src,test}/**/*.clj" "test/core.clj"))
  (t/is (match? "{src,test}/**/*.clj" "src/app/core.clj")))

(t/deftest test-regex-metacharacters-are-literal
  (t/is (match? "a.b" "a.b"))
  (t/is (not (match? "a.b" "axb")))
  (t/is (match? "a+b" "a+b"))
  (t/is (match? "(x)" "(x)")))

(t/deftest test-compile-patterns
  (let [matcher (glob/compile-patterns ["*.clj" "*.cljc"])]
    (t/is (glob/matches? matcher "core.clj"))
    (t/is (glob/matches? matcher "core.cljc"))
    (t/is (not (glob/matches? matcher "core.edn"))))
  (t/is (not (glob/matches? (glob/compile-patterns nil) "core.clj")))
  (t/is (not (glob/matches? (glob/compile-patterns []) "core.clj"))))

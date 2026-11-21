(ns kmet.app.tools.test-glob
  "The file-search glob matcher."
  (:require [clojure.test :as t]
            [kmet.app.tools.glob :as glob]))

(defn- match?
  [pattern path]
  (glob/matches? (glob/compile-matcher pattern) path))

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
  (t/is (match? "[^abc]" "^"))
  (t/is (match? "[^abc]" "a"))
  (t/is (not (match? "[^abc]" "d")))
  (t/is (match? "[!^abc]" "d"))
  (t/is (not (match? "[!^abc]" "^")))
  (t/is (not (match? "[!^abc]" "a")))
  (t/is (match? "[abc" "[abc")))

(t/deftest test-braces-expand
  (t/is (match? "src/*.{clj,cljc}" "src/core.clj"))
  (t/is (match? "src/*.{clj,cljc}" "src/core.cljc"))
  (t/is (not (match? "src/*.{clj,cljc}" "src/core.edn")))
  (t/is (match? "{src,test}/**/*.clj" "test/core.clj"))
  (t/is (match? "{src,test}/**/*.clj" "src/app/core.clj"))
  (t/is (match? "{src,{test,dev}}/**/*.clj" "dev/core.clj"))
  (t/is (match? "{src,{test,dev}}/**/*.clj" "src/app/core.clj"))
  (t/is (match? "a{b,{c,d}}e" "abe"))
  (t/is (match? "a{b,{c,d}}e" "ace"))
  (t/is (match? "a{b,{c,d}}e" "ade"))
  (t/is (not (match? "a{b,{c,d}}e" "ae")))
  (t/is (match? "{a,b}{c,d}" "ac"))
  (t/is (match? "{a,b}{c,d}" "bd")))

(t/deftest test-braces-in-classes-are-literal
  (t/is (match? "[{}]" "{"))
  (t/is (match? "[{}]" "}"))
  (t/is (not (match? "[{}]" "{}")))
  (t/is (match? "[a{b,c}]" "{"))
  (t/is (match? "[a{b,c}]" ","))
  (t/is (match? "{a,[,],b}" "a"))
  (t/is (match? "{a,[,],b}" ","))
  (t/is (match? "{a,[,],b}" "b"))
  (t/is (match? "{a,[}],b}" "}")))

(t/deftest test-regex-metacharacters-are-literal
  (t/is (match? "a.b" "a.b"))
  (t/is (not (match? "a.b" "axb")))
  (t/is (match? "a+b" "a+b"))
  (t/is (match? "(x)" "(x)")))

(t/deftest test-compile-matcher
  (let [matcher (glob/compile-matcher ["*.clj" "*.cljc"])]
    (t/is (glob/matches? matcher "core.clj"))
    (t/is (glob/matches? matcher "core.cljc"))
    (t/is (not (glob/matches? matcher "core.edn"))))
  (t/is (not (glob/matches? (glob/compile-matcher nil) "core.clj")))
  (t/is (not (glob/matches? (glob/compile-matcher []) "core.clj"))))

(t/deftest test-invalid-patterns
  (doseq [pattern ["[]" "[!]" "[z-a]"]]
    (let [error (try
                  (glob/compile-matcher pattern)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error) pattern)
      (t/is (= ::glob/invalid-pattern (:type (ex-data error))) pattern)
      (t/is (= pattern (:pattern (ex-data error))) pattern))))

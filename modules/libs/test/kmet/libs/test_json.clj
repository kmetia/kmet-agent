(ns kmet.libs.test-json
  "kmet.libs.json — the single JSON seam. The vars alias clojure.data.json's
   fns exactly (identical value, so behavior can never drift); these tests
   pin the surface the app relies on (parse arities/keywordization, encode
   arities/opts) so an engine swap behind the seam cannot silently change
   them."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.libs.json :as json]))

(deftest aliases-data-json
  (testing "the seam exposes clojure.data.json's own fns (no wrapper layer)"
    (is (identical? json/parse-string (var-get #'json/parse-string)))
    (is (identical? json/generate-string (var-get #'json/generate-string)))))

(deftest parse-string-arities
  (testing "1-arity keeps string keys"
    (is (= {"a" 1} (json/parse-string "{\"a\":1}")))
    (is (= {"a" [1 2.5 true nil "x"]}
           (json/parse-string "{\"a\":[1,2.5,true,null,\"x\"]}"))))
  (testing "2-arity keywordizes object keys"
    (is (= {:a {:b 1}} (json/parse-string "{\"a\":{\"b\":1}}" true)))
    (is (= {:a [1 {:b 2}]} (json/parse-string "{\"a\":[1,{\"b\":2}]}" true))))
  (testing "scalars parse per data.json (ints, floats, lazy arrays)"
    (is (= 1 (json/parse-string "1")))
    (is (= 1.5 (json/parse-string "1.5")))
    (is (= [1 2] (vec (json/parse-string "[1,2]"))))
    (is (nil? (json/parse-string "null")))))

(deftest generate-string-arities
  (testing "1-arity"
    (is (= "{\"a\":1}" (json/generate-string {:a 1})))
    (is (= "null" (json/generate-string nil)))
    (is (= "[1,2]" (json/generate-string [1 2]))))
  (testing "2-arity passes opts through"
    (is (= "{\"a\":1}" (json/generate-string {:a 1} {:pretty true})))
    (is (= "{\"a\":\"h\\u00e9llo\"}"
           (json/generate-string {:a "héllo"} {:escape-unicode true})))
    (is (= "{\"a\":1}" (json/generate-string {:a 1} {:pretty false})))))

(deftest astral-round-trip
  (testing "non-BMP text crosses the seam as raw UTF-8, not a backslash-u escape"
    ;; :escape-unicode false is the seam's contract: a code point above U+FFFF
    ;; goes out raw (four UTF-8 bytes) rather than as a surrogate-pair escape,
    ;; and round-trips intact.
    (let [emoji (String. (Character/toChars 128077))]
      (is (= (str "{\"a\":\"" emoji "\"}")
             (json/generate-string {:a emoji})))
      (is (= emoji (:a (json/parse-string (json/generate-string {:a emoji}) true)))))))

(deftest round-trips
  (testing "keyword-keyed data round-trips through generate"
    (is (= "{\"a b\":1}" (json/generate-string (json/parse-string "{\"a b\":1}" true))))
    (is (= "{\"a/b\":1}" (json/generate-string (json/parse-string "{\"a/b\":1}" true))))))

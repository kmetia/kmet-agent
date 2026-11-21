(ns kmet.tui.test-terminal-image
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.tui.terminal-image :as img]))

;; 1x1 red PNG as base64 (generated with Python zlib+struct)
(def ^:const test-png
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

(deftest test-png-dimensions
  (testing "PNG dimension parsing"
    (let [dims (img/get-png-dimensions test-png)]
      (is (some? dims))
      (is (= 1 (:width-px dims)))
      (is (= 1 (:height-px dims))))))

(deftest test-encode-kitty
  (testing "Kitty encoding with all params"
    (let [encoded (img/encode-kitty test-png :columns 10 :rows 10 :image-id 42)]
      (is (str/starts-with? encoded "\u001b_G"))
      (is (str/ends-with? encoded "\u001b\\"))
      (is (str/includes? encoded "c=10"))
      (is (str/includes? encoded "r=10"))
      (is (str/includes? encoded "i=42"))))
  (testing "Kitty encoding without cursor movement"
    (let [no-move (img/encode-kitty test-png :move-cursor false)]
      (is (str/includes? no-move "C=1"))))
  (testing "Kitty encoding native format codes"
    (is (str/includes? (img/encode-kitty test-png :mime-type "image/png") "f=100"))
    (is (str/includes? (img/encode-kitty test-png :mime-type "image/jpeg") "f=27"))
    (is (str/includes? (img/encode-kitty test-png :mime-type "image/gif") "f=28"))
    (is (str/includes? (img/encode-kitty test-png) "f=100")))
  (testing "Kitty chunked encoding for large payloads"
    (let [large (apply str (repeat 10000 "A"))
          chunked (img/encode-kitty large :image-id 1)]
      (is (> (count (clojure.string/split chunked #"\u001b\\")) 2)))))

(deftest test-image-dimensions
  (testing "get-image-dimensions by MIME type"
    (is (some? (img/get-image-dimensions test-png "image/png")))
    (is (nil? (img/get-image-dimensions test-png "image/gif")))
    (is (nil? (img/get-image-dimensions test-png "image/webp")))
    (is (nil? (img/get-image-dimensions test-png "image/unknown")))))

(deftest test-cell-size
  (testing "calculate-image-cell-size"
    (let [cs (img/calculate-image-cell-size {:width-px 800 :height-px 600} 80)]
      (is (pos? (:columns cs)))
      (is (pos? (:rows cs))))))

(deftest test-fallback
  (testing "image-fallback formatting"
    (let [fb (img/image-fallback "image/png"
                                 :dimensions {:width-px 800 :height-px 600}
                                 :filename "test.png")]
      (is (str/includes? fb "test.png"))
      (is (str/includes? fb "800x600"))
      (is (str/includes? fb "image/png")))))

(deftest test-image-id
  (testing "allocate-image-id"
    (let [id1 (img/allocate-image-id)
          id2 (img/allocate-image-id)]
      (is (pos? id1))
      (is (not= id1 id2)))))

(deftest test-cleanup
  (testing "delete-kitty-image"
    (let [del (img/delete-kitty-image 42)]
      (is (str/includes? del "i=42"))))
  (testing "delete-all-kitty-images"
    (let [del-all (img/delete-all-kitty-images)]
      (is (str/includes? del-all "d=A")))))

(deftest test-is-image-line
  (testing "is-image-line detection"
    (is (img/is-image-line "\u001b_Ga=T;data\u001b\\"))
    (is (not (img/is-image-line "regular text")))))

(deftest test-kitty-image-header
  (testing "parse-kitty-image-header extracts ids and rows (pi: parseKittyImageHeader)"
    (let [seq (img/encode-kitty "Cg==" :rows 10 :image-id 42)]
      (is (= {:ids [42] :rows 10} (img/parse-kitty-image-header seq)))
      (is (= [42] (img/extract-kitty-image-ids seq)))
      (is (= 10 (img/extract-kitty-image-rows seq)))))
  (testing "no header on non-image lines"
    (is (nil? (img/parse-kitty-image-header "plain text")))
    (is (= [] (img/extract-kitty-image-ids "plain text")))
    (is (= 1 (img/extract-kitty-image-rows "plain text"))))
  (testing "multi-chunk sequences parse from the first chunk only"
    (let [big (img/encode-kitty (apply str (repeat 5000 "A")) :rows 3 :image-id 7)]
      (is (= [7] (img/extract-kitty-image-ids big)))
      (is (= 3 (img/extract-kitty-image-rows big)))))
  (testing "image id embedded in a styled line"
    (let [line (str "\u001b[1m" (img/encode-kitty "Cg==" :image-id 99) "\u001b[0m")]
      (is (= [99] (img/extract-kitty-image-ids line))))))

(deftest test-capabilities
  (testing "capability detection returns map with expected keys"
    (let [caps (img/get-capabilities)]
      (is (contains? caps :images))
      (is (contains? caps :true-color))
      (is (contains? caps :hyperlinks)))))

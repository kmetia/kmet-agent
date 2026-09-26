(ns kmet.tasks.test-slop
  "Tests for kmet.tasks.slop (the `bb slop` task): tokenizer and clone
   detection, Clojure CC extraction, source discovery,
   parse-failure handling and the reference comparison on small temp trees."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.tasks.slop :as slop]))

(defn- make-tree
  "Create a temp tree under target/ from a {relative-path content} map."
  [files]
  (fs/create-dirs "target")
  (let [dir (fs/create-temp-dir {:dir "target" :prefix "slop-test-"})]
    (doseq [[name content] files]
      (let [f (fs/path dir name)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) content)))
    dir))

(defn- in-tree
  "Call THUNK with DIR bound to a temp tree built from a {path content} map."
  [files thunk]
  (let [dir (make-tree files)]
    (try
      (thunk dir)
      (finally (fs/delete-tree dir)))))

(deftest tokenize-drops-comments-keeps-literals
  (let [{:keys [tokens lines]} (slop/tokenize ";; lead\n(def x \"a;b\") ; tail\n")]
    (is (= ["(" "def" "x" "\"a;b\"" ")"] tokens))
    (is (= [2 2 2 2 2] lines))))

(deftest identical-functions-are-clones
  (let [src "(defn f [v] (let [s (clean v)] (println s) s))\n"]
    (in-tree {"a.clj" src "b.clj" src}
             (fn [dir]
               (let [report (slop/scan dir {})]
                 (is (pos? (:clone-lines report)))
                 (is (pos? (:verbosity report)))
                 (is (seq (:outlier-files report))))))))

(deftest unique-short-code-is-not-cloned
  (in-tree {"a.clj" "(defn f [x] (inc x))"
            "b.clj" "(defn g [y] (* y 2))"}
           (fn [dir]
             (is (zero? (:clone-lines (slop/scan dir {})))))))

(deftest renamed-functions-and-literals-are-structural-clones
  (in-tree {"a.clj" "(defn first [left right] (let [r (+ left 1)] (println r) (* r 2)))\n"
            "b.clj" "(defn second [alpha beta] (let [t (+ alpha 2)] (println t) (* t 2)))\n"}
           (fn [dir]
             (is (pos? (:clone-lines (slop/scan dir {})))))))

(deftest different-operators-are-not-clones
  (in-tree {"a.clj" "(defn add [a b] (let [r (+ a b)] (println r) r))\n"
            "b.clj" "(defn sub [a b] (let [r (- a b)] (println r) r))\n"}
           (fn [dir]
             (is (zero? (:clone-lines (slop/scan dir {})))))))

(deftest single-statement-bodies-do-not-clone
  (in-tree {"a.clj" "(defn f [x] (g x))\n"
            "b.clj" "(defn h [y] (g y))\n"}
           (fn [dir]
             (is (zero? (:clone-lines (slop/scan dir {})))))))

(deftest partial-overlap-in-non-candidate-code-is-not-a-clone
  ;; The reference node set has no plain-block type: shared let scaffolding
  ;; inside bodies that differ elsewhere is a partial overlap, not a clone.
  (in-tree {"a.clj" (str "(defn f [x]\n"
                         "  (let [y (inc x)\n"
                         "        z (str y)]\n"
                         "    (println y)\n"
                         "    (+ z 1)))\n")
            "b.clj" (str "(defn g [x]\n"
                         "  (let [y (inc x)\n"
                         "        z (str y)]\n"
                         "    (println y)\n"
                         "    (+ z 1)\n"
                         "    (println y)))\n")}
           (fn [dir]
             (is (zero? (:clone-lines (slop/scan dir {})))))))

(deftest nested-block-duplicates-clone-inside-different-functions
  ;; Candidate blocks are matched independently of their enclosing function,
  ;; so a duplicated if body is a clone even when the defns differ.
  (in-tree {"a.clj" "(defn f [x] (println x) (if x (do (println x) (inc x)) 0))\n"
            "b.clj" "(defn g [y] (println y) (println y) (if y (do (println y) (inc y)) 0))\n"}
           (fn [dir]
             (is (pos? (:clone-lines (slop/scan dir {})))))))

(deftest docstrings-do-not-affect-clone-hashes
  (in-tree {"a.clj" "(defn first \"doc\" [v] (let [x (inc v)] (println x) x))\n"
            "b.clj" "(defn second [v] (let [x (inc v)] (println x) x))\n"}
           (fn [dir]
             (is (pos? (:clone-lines (slop/scan dir {})))))))

(deftest clone-lines-are-sloc-lines-of-the-clone-span
  ;; The cloned defn spans lines 1-4 in a.clj, but only lines 1 and 4 hold
  ;; code - the comment and blank line inside the span are not clone lines.
  (in-tree {"a.clj" "(defn f [v]\n  ;; note\n\n  (let [s (clean v)] (println s) s))\n"
            "b.clj" "(defn g [v] (let [s (clean v)] (println s) s))\n"}
           (fn [dir]
             (is (= 3 (:clone-lines (slop/scan dir {})))))))

(deftest verbosity-counts-rule-flagged-lines
  (in-tree {"sloppy.clj" "(defn f [x] (if (not x) 1 2))\n"}
           (fn [dir]
             (let [report (slop/scan dir {})]
               (is (zero? (:clone-lines report)))
               (is (pos? (:rule-lines report)))
               (is (pos? (:verbosity report)))
               (is (contains? (set (map first (:rule-counts report))) "if-not"))))))

(deftest erosion-is-the-high-cc-mass-share
  (let [ifs (str/join "" (repeat 12 "(if x "))
        body (str ifs "1" (str/join "" (repeat 12 " 2)")))
        src (str "(defn big [x] " body ")\n")]
    (in-tree {"big.clj" src}
             (fn [dir]
               (let [report (slop/scan dir {})]
                 (is (= 1 (:functions report)))
                 (is (= 13 (:max-cc report)))
                 (is (= 1 (:high-cc report)))
                 (is (pos? (:erosion report)))
                 (is (= 1 (count (:outliers report)))))))))

(deftest cc-counts-clojure-decision-heads
  (in-tree {"heads.clj" (str "(defn f [a b] (and a (or b a)))\n"
                             "(defn g [x] (cond x 1 :else 2))\n"
                             "(defn h [x] (case x 1 :a 2 :b :other))\n")}
           (fn [dir]
             (let [report (slop/scan dir {})]
               (is (= 3 (:functions report)))
               (is (= 3 (:max-cc report)))))))

(deftest nested-functions-count-separately
  (in-tree {"nest.clj" "(defn outer [x] (fn [y] (if y (when y 1) 2)))\n"}
           (fn [dir]
             (let [report (slop/scan dir {})]
               (is (= 2 (:functions report)))
               (is (= 3 (:max-cc report)))))))

(deftest sloc-skips-blank-and-comment-lines
  (in-tree {"s.clj" ";; comment\n(ns s)\n\n(defn f [x]\n  ;; inner\n  (inc x))\n"}
           (fn [dir]
             (is (= 3 (:sloc (slop/scan dir {})))))))

(deftest parse-failures-are-reported-not-thrown
  (in-tree {"broken.clj" "(defn broken"}
           (fn [dir]
             (let [report (slop/scan dir {})]
               (is (= 1 (count (:failures report))))
               (is (= 0 (:functions report)))))))

(deftest source-discovery-skips-build-dirs-and-other-extensions
  (in-tree {"src/a.clj" "(ns a)"
            "target/b.clj" "(ns b)"
            "data.edn" "{:x 1}"}
           (fn [dir]
             (is (= ["src/a.clj"]
                    (mapv #(str (fs/relativize dir %)) (slop/source-files dir)))))))

(deftest report-shows-references-and-outliers-only
  (in-tree {"clean.clj" "(defn f [x] (inc x))"}
           (fn [dir]
             (let [text (slop/format-report (slop/scan dir {}))]
               (is (str/includes? text "VERBOSITY"))
               (is (str/includes? text "EROSION"))
               (is (str/includes? text "reference: human 0.19"))
               (is (str/includes? text "reference: human 0.34"))
               (is (str/includes? text "below human mean"))
               (is (str/includes? text "outlier files: none"))
               (is (str/includes? text "outlier functions: none"))))))

(ns rewrite-clj-parse-perf
  "Minimal benchmark: rewrite-clj parsing is much slower on Jolt than on the
   JVM or babashka, while clojure.core/read-string is not.

   Run twice on Jolt — the first run AOT-compiles the script and its deps:

     clojure -M scripts/repros/rewrite_clj_parse_perf.clj
     bb                 scripts/repros/rewrite_clj_parse_perf.clj
     jolt               scripts/repros/rewrite_clj_parse_perf.clj

   The tiny benchmark parses one small form 5000 times (a per-call overhead
   check). The corpus benchmark parses one 1.28 MB document of 4000 defns
   (the shape of a real source tree). The core read-string benchmark is the
   control: it reads the same tiny form the same number of times."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as tools-reader]
            [rewrite-clj.parser :as parser]))

(def ^:private tiny-form
  "(defn f [x] (let [y (inc x)] (when (pos? y) (* y 2))))")
(def ^:private tiny-iterations 5000)
(def ^:private corpus-forms 4000)
(def ^:private iterations 3)

(defn- runtime []
  (cond
    (find-var 'clojure.core/*jolt-version*)
    (str "Jolt " @(find-var 'clojure.core/*jolt-version*))

    (System/getProperty "babashka.version")
    (str "babashka " (System/getProperty "babashka.version"))

    :else
    (str "JVM " (System/getProperty "java.version"))))

(defn- once-ms [f]
  (let [t0 (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) t0) 1e6)))

(defn- bench [label f]
  (f) ; warm
  (let [runs (vec (repeatedly iterations #(once-ms f)))]
    (println (format "%-34s min %8.1f ms  median %8.1f ms  runs %s"
                     label (apply min runs) (nth (sort runs) (quot iterations 2))
                     (str/join " " (map #(format "%.0f" %) runs))))))

(defn- source-form [i]
  (str "(defn handler-" i " [request]\n"
       "  (let [id (get-in request [:params :id])\n"
       "        tags #{:alpha :beta :gamma}\n"
       "        payload {:id id :tags tags :label \"item-" i "\"}]\n"
       "    (when (and id (contains? tags :alpha))\n"
       "      (->> (range 16)\n"
       "           (mapv (fn [n] (* n " i ")))\n"
       "           (filter odd?)\n"
       "           (reduce + 0)))))"))

(let [texts (mapv source-form (range corpus-forms))
      text (str/join "\n\n" texts)]
  (println "runtime:" (runtime))
  (println "clojure:" (clojure-version))
  (println "os     :" (System/getProperty "os.name") (System/getProperty "os.arch"))
  (println (format "corpus : %d forms, %.2f MB" (count texts) (/ (count text) 1e6)))
  (println)
  (bench (format "rewrite-clj parse-string x%d" tiny-iterations)
         #(dotimes [_ tiny-iterations] (parser/parse-string tiny-form)))
  (bench "rewrite-clj parse-string-all"
         #(parser/parse-string-all text))
  (bench (format "tools.reader/read-string x%d" tiny-iterations)
         #(dotimes [_ tiny-iterations] (tools-reader/read-string tiny-form)))
  (bench (format "core read-string x%d (control)" tiny-iterations)
         #(dotimes [_ tiny-iterations] (read-string tiny-form))))

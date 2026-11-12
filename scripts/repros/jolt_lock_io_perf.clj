(ns jolt-lock-io-perf
  "Repro for jolt#1654: lock + blocking file I/O from many futures is
   ~7-11x slower than the JVM on Jolt.

   See scripts/repros/jolt_lock_io_perf.md for the ticket text.

   Run under each runtime (twice on Jolt; the first run AOT-compiles):

     jolt   scripts/repros/jolt_lock_io_perf.clj
     bb     scripts/repros/jolt_lock_io_perf.clj
     clojure -M scripts/repros/jolt_lock_io_perf.clj

   400 futures take a ReentrantLock and either increment an atom or append
   one line through io/output-stream :append. Jolt is ~5-8x slower than the
   JVM/bb only when the lock is held across the file I/O."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]))

(def ^:private futures 400)
(def ^:private iterations 3)
(def ^:private payload (.getBytes "x\n" "UTF-8"))

(defn- runtime []
  (cond
    (find-var 'clojure.core/*jolt-version*)
    (str "Jolt " @(find-var 'clojure.core/*jolt-version*))

    (System/getProperty "babashka.version")
    (str "babashka " (System/getProperty "babashka.version"))

    :else
    (str "JVM " (System/getProperty "java.version"))))

(defn- ms [t0]
  (/ (- (System/nanoTime) t0) 1e6))

(defn- bench [label f]
  (f)
  (let [runs (vec (repeatedly iterations #(let [t0 (System/nanoTime)] (f) (ms t0))))
        sorted (sort runs)]
    (println (format "%-38s min %8.1f ms  median %8.1f ms"
                     label (first sorted) (nth sorted (quot iterations 2))))))

(defn- run-futures [f]
  (let [vs (doall (for [_ (range futures)] (future (f))))]
    (doseq [v vs] @v)))

(defn- lock-inc [lock a]
  (.lock lock)
  (try (swap! a inc) (finally (.unlock lock))))

(defn- lock-append [file lock]
  (.lock lock)
  (try
    (with-open [os (io/output-stream file :append true)]
      (.write os payload))
    (finally (.unlock lock))))

(defn- append-no-lock [file]
  (with-open [os (io/output-stream file :append true)]
    (.write os payload)))

(let [dir (str (fs/create-temp-dir))
      file (str (fs/path dir "session.log"))]
  (try
    (println "runtime:" (runtime))
    (bench "futures + lock + atom inc"
           #(let [lock (java.util.concurrent.locks.ReentrantLock.)
                  a (atom 0)]
              (run-futures (fn [] (lock-inc lock a)))))
    (bench "futures + lock + file append"
           #(let [lock (java.util.concurrent.locks.ReentrantLock.)]
              (fs/delete-if-exists file)
              (run-futures (fn [] (lock-append file lock)))))
    (bench "futures + file append, no lock"
           #(do (fs/delete-if-exists file)
                (run-futures (fn [] (append-no-lock file)))))
    (finally (fs/delete-tree dir))))

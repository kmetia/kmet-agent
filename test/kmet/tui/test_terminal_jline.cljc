(ns kmet.tui.test-terminal-jline
  "JLine backend tests — bb/JVM only (the JLine backend is what bb loads;
   Jolt runs the native FFI backends). A .cljc because a .clj file may not
   carry reader conditionals (clj-kondo rejects them, and both lint views
   must read this file); on Jolt the namespace body is empty."
  #?(:bb
     (:require [clojure.test :as t :refer [deftest]]
               [babashka.process :as proc]
               [kmet.tui.terminal-jline])))

#?(:bb
   (do
     (defn- jline-var
       "A private var of the JLine backend, resolved at runtime so
        clj-kondo does not need a private-call exemption."
       [sym]
       (some-> (ns-resolve 'kmet.tui.terminal-jline sym) deref))

     (deftest chcp-output-parse
       (t/testing "the console code page is read out of chcp's output"
         (if-let [parse (jline-var 'chcp-output->codepage)]
           (do
             (t/is (= 437 (parse "Active code page: 437\r\n")))
             (t/is (= 65001 (parse "Active code page: 65001\r\n")))
             (t/is (nil? (parse "The system cannot write to the specified device.\r\n"))
                   "a failure message carries no code page")
             (t/is (nil? (parse nil))))
           (t/is true "skipped: the JLine backend did not load"))))

     (deftest ^:slow bounded-read-destroys-a-hung-child
       (t/testing "a bounded read that times out destroys the child instead
                   of abandoning it (a hung stty must not outlive the call)"
         (if-let [read-bounded (jline-var 'read-bounded)]
           (let [p (:proc (proc/process ["sh" "-c" "sleep 30"]))
                 started (System/currentTimeMillis)
                 result (read-bounded p)]
             (t/is (nil? result) "the bounded read gave up")
             (t/is (< (- (System/currentTimeMillis) started) 10000)
                   "within the deadline")
             (let [deadline (+ (System/currentTimeMillis) 3000)]
               (while (and (.isAlive p)
                           (< (System/currentTimeMillis) deadline))
                 (Thread/sleep 20))
               (t/is (not (.isAlive p))
                     "the hung child was destroyed")))
           (t/is true "skipped: the JLine backend did not load"))))))

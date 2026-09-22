(ns kmet.tui.test-terminal-jline
  "JLine backend tests — bb/JVM only (the JLine backend is what bb loads;
   Jolt runs the native FFI backends). A .cljc because a .clj file may not
   carry reader conditionals (clj-kondo rejects them, and both lint views
   must read this file); on Jolt the namespace body is empty."
  #?(:bb
     (:require [clojure.test :as t :refer [deftest]]
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
           (t/is true "skipped: the JLine backend did not load"))))))

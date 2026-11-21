(ns kmet.tasks.test-help
  "kmet.tasks.help — the task-level --help wrapper behind `bb <task> --help`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.help :as help]))

(defn- with-fake-task
  "Run F with `babashka.tasks/*task*` bound to TASK, the map both hosts bind
   while a task runs."
  [task f]
  (with-bindings {(requiring-resolve 'babashka.tasks/*task*) task}
    (f)))

(defn- out-str-lf
  "with-out-str with CRLF normalized to LF: bb's capture translates the
   newline on Windows, Jolt's does not, so the line-ending assertions compare
   through this on both hosts."
  [f]
  (str/replace (with-out-str (f)) "\r\n" "\n"))

(deftest help-requested?-test
  (testing "both help spellings count, wherever they appear"
    (is (help/help-requested? ["--help"]))
    (is (help/help-requested? ["kmet.tui.test-fuzzy" "-h"])))
  (testing "ordinary arguments do not"
    (is (not (help/help-requested? [])))
    (is (not (help/help-requested? ["--dry-run" "src"])))))

(deftest ^:bb-only with-help-test
  ;; bb-only: the fake task binds babashka.tasks/*task*, which only the
  ;; babashka host has (the wrapper itself is host-neutral)
  (testing "--help prints the whole task doc and skips the body"
    (with-fake-task {:name 'demo :doc "summary\n\ndetails"}
      (fn []
        (binding [*command-line-args* ["--help"]]
          (is (= "summary\n\ndetails\n"
                 (out-str-lf #(help/with-help (println "RAN")))))))))
  (testing "without --help the body runs"
    (with-fake-task {:name 'demo :doc "summary\n\ndetails"}
      (fn []
        (binding [*command-line-args* []]
          (is (= "RAN\n"
                 (out-str-lf #(help/with-help (println "RAN"))))))))))

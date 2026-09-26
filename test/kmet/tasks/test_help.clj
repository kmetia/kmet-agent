(ns kmet.tasks.test-help
  "kmet.tasks.help — the task-level --help wrapper behind `bb <task> --help`."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.tasks.help :as help]))

(defn- with-fake-task
  "Run F with `babashka.tasks/*task*` bound to TASK, the map both hosts bind
   while a task runs."
  [task f]
  (with-bindings {(requiring-resolve 'babashka.tasks/*task*) task}
    (f)))

(deftest help-requested?-test
  (testing "both help spellings count, wherever they appear"
    (is (help/help-requested? ["--help"]))
    (is (help/help-requested? ["kmet.tui.test-fuzzy" "-h"])))
  (testing "ordinary arguments do not"
    (is (not (help/help-requested? [])))
    (is (not (help/help-requested? ["--dry-run" "src"])))))

(deftest with-help-test
  (testing "--help prints the whole task doc and skips the body"
    (with-fake-task {:name 'demo :doc "summary\n\ndetails"}
      (fn []
        (binding [*command-line-args* ["--help"]]
          (is (= "summary\n\ndetails\n"
                 (with-out-str (help/with-help (println "RAN")))))))))
  (testing "without --help the body runs"
    (with-fake-task {:name 'demo :doc "summary\n\ndetails"}
      (fn []
        (binding [*command-line-args* []]
          (is (= "RAN\n"
                 (with-out-str (help/with-help (println "RAN"))))))))))

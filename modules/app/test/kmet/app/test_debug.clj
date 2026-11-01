(ns kmet.app.test-debug
  "Diagnostics logging: the log directory, the size cap that bounds the files,
   the always-on error log, the `--debug` gate, and the `kmet.libs` sink the
   switch installs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.debug :as debug]
            [kmet.libs.log :as libs-log]
            [kmet.test-tmp :as test-tmp]))

(defn- scratch []
  (test-tmp/temp-dir! "kmet-logs-"))

(defn- error-file [dir] (str (fs/path dir "kmet-error.log")))
(defn- debug-file [dir] (str (fs/path dir "kmet-debug.log")))

(deftest the-programmatic-log-dir-overrides-the-default
  ;; The test runner points diagnostics at target/test-logs so a run that
  ;; reports an error never appends to the checkout's logs/.
  (let [previous (System/getProperty "kmet.log.dir")]
    (try
      (System/setProperty "kmet.log.dir" "example-logs")
      (is (= "example-logs" (debug/logs-dir)))
      (finally
        (System/setProperty "kmet.log.dir" (or previous ""))))))

(deftest the-error-log-is-bounded-by-rotation
  (let [dir (scratch)]
    (try
      (with-redefs [debug/logs-dir (constantly dir)
                    debug/max-log-bytes 120]
        (debug/log-error (apply str (repeat 80 "x")))
        (is (fs/exists? (error-file dir)) "the first line lands in the file")
        (debug/log-error (apply str (repeat 80 "y")))
        (is (fs/exists? (str (error-file dir) ".1")) "the old file rotates away")
        (is (str/includes? (slurp (str (error-file dir) ".1")) "xxxx")
            "the previous line is in .1")
        (is (str/includes? (slurp (error-file dir)) "yyyy")
            "the new line is in the live file"))
      (finally
        (debug/disable!)
        (fs/delete-tree dir)))))

(deftest the-error-log-writes-with-debug-off
  (let [dir (scratch)]
    (try
      (with-redefs [debug/logs-dir (constantly dir)]
        (debug/disable!)
        (debug/log-error "unhandled")
        (is (str/includes? (slurp (error-file dir)) "unhandled")
            "an unhandled error is always written"))
      (finally
        (debug/disable!)
        (fs/delete-tree dir)))))

(deftest debug-lines-follow-the-switch-and-install-the-libs-sink
  (let [dir (scratch)]
    (try
      (with-redefs [debug/logs-dir (constantly dir)]
        (debug/disable!)
        (debug/log "hidden")
        (is (not (fs/exists? (debug-file dir)))
            "the debug file is absent until the switch is on")
        (debug/enable!)
        (libs-log/log! "from-libs")
        (is (str/includes? (slurp (debug-file dir)) "from-libs")
            "enable! installs the sink lower layers log through")
        (debug/log "visible")
        (is (str/includes? (slurp (debug-file dir)) "visible"))
        (debug/disable!)
        (let [after-disable (slurp (debug-file dir))]
          (libs-log/log! "after-disable")
          (is (= after-disable (slurp (debug-file dir)))
              "disable! clears the sink")))
      (finally
        (debug/disable!)
        (fs/delete-tree dir)))))

(deftest an-unwritable-log-path-is-reported-once-and-never-throws
  (let [dir (scratch)]
    (try
      (fs/create-dirs (error-file dir))     ; a directory where the file goes
      (reset! @#'debug/write-failure-reported? false)
      (let [w (java.io.StringWriter.)]
        (with-redefs [debug/logs-dir (constantly dir)]
          (binding [*err* w]
            (is (nil? (debug/log-error "boom")))
            (is (nil? (debug/log-error "again")))))
        (is (str/includes? (str w) "cannot write diagnostics log")
            "the first failure names the path")
        (is (= 1 (count (re-seq #"cannot write diagnostics log" (str w))))
            "later failures stay quiet"))
      (finally
        (fs/delete-tree dir)))))

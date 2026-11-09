(ns kmet.libs.test-process
  "kill-process-tree! must take the whole tree, not just the direct child:
   the descendant's delayed side effect proves it — it runs after the
   parent is already gone, so a direct-child-only kill leaves the marker
   behind. Windows exercises the taskkill /F /T branch, Unix the ps-walk
   fallback."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.test :as t]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.process :as process]
            [kmet.test-tmp :as test-tmp]))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (test-tmp/temp-dir! "test-process-")))]
    (fs/create-dirs dir)
    dir))

(defn- write-windows-parent!
  "parent.cmd starts child.cmd, then blocks. The child reports in
   (STARTED), waits ~2s, then touches MARKER — so a descendant that
   survives the kill lands the marker after it."
  [dir]
  (let [parent (fs/path dir "parent.cmd")]
    (spit (str parent)
          (str "@echo off\r\n"
               "cd /d \"%~dp0\"\r\n"
               "start /b \"\" child.cmd \"%~1\" \"%~2\"\r\n"
               "ping -n 30 127.0.0.1 >NUL\r\n"))
    (spit (str (fs/path dir "child.cmd"))
          (str "@echo off\r\n"
               "echo x>\"%~2\"\r\n"
               "ping -n 3 127.0.0.1 >NUL\r\n"
               "echo x>\"%~1\"\r\n"))
    parent))

(defn- write-unix-parent!
  "parent.sh backgrounds the same child (a subshell), then blocks."
  [dir]
  (let [parent (fs/path dir "parent.sh")]
    (spit (str parent)
          (str "#!/bin/sh\n"
               "(touch \"$2\"; sleep 2; touch \"$1\") &\n"
               "sleep 30\n"))
    parent))

(t/deftest ^:slow kill-process-tree-kills-descendants
  (let [dir (temp-dir)
        marker (str (fs/path dir "marker"))
        started (str (fs/path dir "started"))
        spawned (atom nil)]
    (try
      (let [_ (if process/windows-os?
                (write-windows-parent! dir)
                (write-unix-parent! dir))
            p (proc/process (if process/windows-os?
                              ["cmd.exe" "/c" "parent.cmd" marker started]
                              ["sh" "parent.sh" marker started])
                            {:dir dir :out :discard :err :discard})]
        (reset! spawned p)
        (let [pid (process/process-pid p)]
          (t/is (some? pid) "the spawned shell exposes its pid")
          ;; wait for the descendant to exist, so the kill cannot race the
          ;; tree's creation and pass vacuously
          (let [deadline (+ (concurrent/monotonic-ms) 5000)]
            (while (and (not (fs/exists? started))
                        (< (concurrent/monotonic-ms) deadline))
              (Thread/sleep 50)))
          (t/is (fs/exists? started) "the descendant reported in before the kill")
          (process/kill-process-tree! pid)
          (t/is (not= ::timeout (deref p 5000 ::timeout))
                "the killed parent exited")
          ;; a survivor would touch the marker ~2s after reporting in
          (Thread/sleep 3000)
          (t/is (not (fs/exists? marker))
                "the killed descendant never touched the marker")))
      (finally
        (when-let [p @spawned]
          (try (proc/destroy-tree p) (catch Exception _ nil)))
        (try (fs/delete-tree dir) (catch Exception _ nil))))))

(t/deftest ^:slow writer-over-process-stdin-reaches-the-child
  ;; io/writer over a process stdin must flush through to the child on both
  ;; hosts (jolt#1379: it used to buffer without ever flushing the stream)
  (let [p (proc/process ["cat"] {:in :pipe :out :pipe})
        w (io/writer (:in p) :encoding "UTF-8")]
    (try
      (.write w "héllo\n")
      (.flush w)
      (t/is (= "héllo" (.readLine (io/reader (:out p))))
            "UTF-8 text reached the child")
      (.close w)
      (t/is (nil? (.readLine (io/reader (:out p)))) "close sends EOF")
      (t/is (nil? (process/close-stdin! p)) "closing again is a no-op")
      (finally
        (try (proc/destroy p) (catch Exception _ nil))))))

(t/deftest suspend-to-background-signals-the-caller-process-group
  (let [spawned (atom nil)]
    (with-redefs [proc/process (fn [argv opts]
                                 (reset! spawned [argv opts])
                                 (delay {:exit 0}))
                  process/windows-os? false]
      (t/is (true? (process/suspend-to-background!)))
      (t/is (= ["kill" "-s" "TSTP" "0"] (first @spawned))
            "pid 0 targets the job's process group, so children stop too")
      (t/is (= {:out :discard :err :discard} (second @spawned))))
    (with-redefs [proc/process (fn [& _] (throw (ex-info "must not spawn" {})))
                  process/windows-os? true]
      (t/is (false? (process/suspend-to-background!))
            "Windows has no job-control stop"))))

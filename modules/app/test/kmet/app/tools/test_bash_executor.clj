(ns kmet.app.tools.test-bash-executor
  "The bash executor's spawn and stream half, exercised for real: one subprocess
   per case (hence ^:slow). Covers the result shape the tool turns into a
   message — exit code, stdout and stderr capture, the rolling tail's
   truncation and its spill file, the timeout tree kill, the cancel signal, the
   spawn hook and env merge, and the argv transports create-default-ops
   chooses."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as sh]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.process :as process]
            [kmet.app.tools.bash-executor :as exec]))

(defn- execute!
  "Execute OPTS with the executor's defaults."
  [opts]
  (exec/execute-bash opts))

(defn- spawn-script
  "A shell script that prints each argv element on its own line, so a test can
   observe the argv the executor built without running a shell."
  []
  (let [path (str (fs/create-temp-file {:prefix "kmet-argv-" :suffix ".sh"
                                        :dir (doto "target" fs/create-dirs)}))]
    (spit path "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    path))

(def ^:private spill-survival-probe
  "Child process for the spill-survival case: run one truncating command and
   report the footer path, whether it still exists at exit, and its line count.
   `bb` inherits this project's classpath through bb.edn."
  (str/join
   "\n"
   ["(require '[babashka.fs :as fs] '[clojure.string :as str]"
    "         '[kmet.app.tools.bash-executor :as exec])"
    "(let [{:keys [full-output-path]}"
    "      (exec/execute-bash {:command \"seq 1 200\" :max-lines 5})]"
    "  (println (pr-str {:path full-output-path"
    "                    :exists (fs/exists? full-output-path)"
    "                    :lines (count (str/split-lines (str/trim (slurp full-output-path))))})))"]))

;; Every case below drives a real shell with POSIX commands (/bin/sh, sleep,
;; seq, $VAR redirection), so the namespace registers none of them on Windows.
;; The executor's Windows paths (NUL stdin, cmd /c, the pipe-close drain) are
;; not reachable from these and need their own cases.
(when-not process/windows-os?
  (t/deftest ^:slow command-output-and-exit-code
    (t/testing "a successful command returns its output and a zero exit code"
      (let [r (execute! {:command "echo hello"})]
        (t/is (= 0 (:exit-code r)))
        (t/is (= "hello\n" (:output r)))
        (t/is (not (:cancelled r)))
        (t/is (not (:truncated r))))))

  (t/deftest ^:slow stderr-is-captured-into-the-same-output
    (t/testing "stderr is part of the output, not dropped"
      (let [r (execute! {:command "echo out; echo err >&2"})]
        (t/is (= 0 (:exit-code r)))
        (t/is (str/includes? (:output r) "out"))
        (t/is (str/includes? (:output r) "err")))))

  (t/deftest ^:slow nonzero-exit-code-is-reported
    (t/testing "a failing command still returns its output"
      (let [r (execute! {:command "echo before; exit 3"})]
        (t/is (= 3 (:exit-code r)))
        (t/is (str/includes? (:output r) "before")))))

  (t/deftest ^:slow truncation-keeps-the-tail-and-spills-the-full-output
    (t/testing "the tail is kept, the full output lands in a spill file"
      (let [r (execute! {:command "seq 1 200" :max-lines 5})]
        (t/is (:truncated r))
        (t/is (= 0 (:exit-code r)))
        (t/is (= 5 (count (str/split-lines (str/trim (:output r))))))
        (t/is (str/ends-with? (str/trim (:output r)) "200"))
        (let [path (:full-output-path r)]
          (t/is (some? path))
          (t/is (fs/exists? path))
          (t/is (= 200 (count (str/split-lines (str/trim (slurp path))))))
          (fs/delete path)))))

  (t/deftest ^:slow timeout-kills-the-tree-and-is-classified
    (t/testing "a command past its timeout is killed and reported as timed out"
      (let [started (System/currentTimeMillis)
            r (execute! {:command "sleep 5" :timeout 0.2})
            elapsed (- (System/currentTimeMillis) started)]
        (t/is (true? (:timed-out r)))
        (t/is (< elapsed 4000) "the wait did not run to the command's own length"))))

  (t/deftest ^:slow cancel-signal-aborts-the-run
    (t/testing "a raised cancel signal is reported as cancelled"
      (let [r (execute! {:command "sleep 5" :signal (atom true)})]
        (t/is (true? (:cancelled r)))
        (t/is (not (:timed-out r))))))

  (t/deftest ^:slow missing-working-directory-is-an-error
    (t/testing "a cwd that does not exist is an error, not a spawn"
      (let [r (execute! {:command "echo hi"
                         :cwd "/definitely/not/a/directory/kmet-test"})]
        (t/is (str/includes? (str (:output r)) "Working directory does not exist"))
        (t/is (true? (:failed r)) "flagged, so the tool reports :is-error")
        (t/is (nil? (:exit-code r))))))

  (t/deftest ^:slow spawn-hook-and-env-merge-reach-the-command
    (t/testing "the spawn hook can rewrite the command; env is merged in"
      (let [r (execute! {:command "echo $KMET_TEST_ENV"
                         :env {"KMET_TEST_ENV" "merged"}
                         :spawn-hook (fn [{:keys [command cwd env]}]
                                       {:command command :cwd cwd
                                        :env (assoc env "KMET_TEST_ENV" "hooked")})})]
        (t/is (= 0 (:exit-code r)))
        (t/is (= "hooked\n" (:output r))))))

  (t/deftest ^:slow command-prefix-runs-before-the-command
    (t/testing "the prefix is resolved into the same shell invocation"
      (let [r (execute! {:command "echo $KMET_PREFIXED"
                         :command-prefix "KMET_PREFIXED=yes"})]
        (t/is (= "yes\n" (:output r))))))

  (t/deftest ^:slow shell-args-append-the-command-as-the-last-argv-element
    (t/testing "an explicit shell-args template replaces the platform heuristics,
            with the command as the final argv element (pi: ShellConfig args)"
      (let [script (spawn-script)]
        (try
          (let [r (execute! {:command "the-command"
                             :shell-path script
                             :shell-args ["--flag" "--other"]})]
            (t/is (= 0 (:exit-code r)))
            (t/is (= ["--flag" "--other" "the-command"]
                     (str/split-lines (str/trim (:output r))))))
          (finally
            (fs/delete script))))))

  (t/deftest ^:slow spill-file-holds-the-exact-output
    (t/testing "when the output outgrows the in-memory budget, the spill file is
            the whole output byte for byte (the switch must write the chunk
            that tripped it exactly once)"
      (let [expected (str (apply str (map #(str % "\n") (range 1 2001))))
            r (execute! {:command "seq 1 2000" :max-bytes 4096 :max-lines 100000})]
        (t/is (:truncated r) "the same budget truncates the returned content")
        (let [path (:full-output-path r)]
          (t/is (some? path))
          (t/is (= expected (slurp path)) "no duplicated chunk at the switch")
          (fs/delete path)))))

  (t/deftest ^:slow spill-file-survives-the-producing-process
    (t/testing "the footer's path resolves after the process that wrote it exits
            (pi's output-files.ts never unlinks; delete-on-exit was kmet-only)"
      (let [script (fs/create-temp-file {:prefix "kmet-spill-probe-"
                                         :suffix ".clj"
                                         :dir (doto "target" fs/create-dirs)})
            out (str script ".out")]
        (try
          (spit (str script) spill-survival-probe)
          (let [{:keys [exit]} (sh/check
                                (sh/process ["bb" (str script)]
                                            {:in :discard :out out :err :inherit}))
                {:keys [path exists lines]}
                (edn/read-string (last (str/split-lines (slurp out))))]
            (t/is (zero? exit))
            (t/is exists "the child saw the spill file while it ran")
            (t/is (fs/exists? path) "and the path still resolves after it exited")
            (t/is (= 200 lines) "with the whole output in it")
            (fs/delete-if-exists path))
          (finally
            (fs/delete-if-exists script)
            (fs/delete-if-exists out))))))

  (t/deftest ^:slow custom-shell-path-must-exist
    (t/testing "create-default-ops refuses a missing custom shell up front"
      (t/is (thrown? Exception
                     (exec/create-default-ops
                      :shell-path "/definitely/not/a/shell"))
            "the constructor validates, not the first spawn"))))

(t/deftest settle-grace-is-capped
  ;; Regression: each growth of the tail bought another 100 ms with no total
  ;; cap, so a command that kept writing after its operations completed (a
  ;; tailed log, a spinner) kept the turn from settling. The grace is one
  ;; budget; growth only spends it. A fake clock keeps the test instant.
  (let [clock (atom 0)
        tail (reify clojure.lang.IDeref
               ;; Grows on every read until the fake clock passes 3000, so an
               ;; uncapped grace would keep extending its deadline past it.
               (deref [_] (if (< @clock 3000) (inc @clock) 0)))]
    (with-redefs [concurrent/monotonic-ms (fn [] (swap! clock + 10))]
      (#'exec/settle-grace! {:tail-bytes tail}))
    (t/is (< @clock 1500)
          (str "the grace ended inside its budget (clock " @clock ")"))))

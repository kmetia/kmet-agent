(ns kmet.libs.process
  "Process tree management: collect descendants, kill a process tree,
   suspend the process group for shell job control, and a pid registry for
   cleanup on shutdown. No project dependencies — mirrors the tree-kill
   npm package / pkill -P."
  (:require [clojure.string :as str]
            [babashka.process :as proc]))

(def windows-os?
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn collect-descendant-pids
  "Collect descendant pids of pid by walking the process table.
   Returns a vector ordered parents-first (direct children before
   grandchildren). Portable across Linux and macOS (no /proc dependency
   on macOS)."
  [pid]
  (try
    (let [result @(proc/process ["ps" "-eo" "pid=,ppid="] {:out :string :err :discard})
          lines (str/split-lines (:out result))
          parent-map (into {}
                           (keep (fn [line]
                                   (when-let [[_ c pp] (re-find #"^\s*(\d+)\s+(\d+)" line)]
                                     [(Long/parseLong (str/trim c)) (Long/parseLong (str/trim pp))])))
                           lines)]
      (loop [frontier #{pid} found []]
        (let [children (filterv (fn [[c p]]
                                  (and (contains? parent-map c)
                                       (contains? frontier p)
                                       (not (contains? frontier c))
                                       (not (some #{c} found))))
                                parent-map)]
          (if (empty? children)
            found
            (let [level (mapv first children)]
              (recur (set level) (into found level)))))))
    (catch Exception _ [])))

(def setsid-path
  "Path to the setsid executable, or nil when unavailable. Spawning a
   command via setsid makes it its own session/process-group leader, so
   kill-process-tree! can group-kill it in one shot — including background
   jobs that shells reparent outside the ppid tree (mksh on Termux forks
   `cmd &` from the sh's parent). Resolved lazily; nil on Windows and
   macOS (no setsid there)."
  (delay
    (when-not windows-os?
      (try
        (let [result @(proc/process ["sh" "-c" "command -v setsid"] {:out :string :err :discard})
              path (str/trim (:out result))]
          (when (seq path) path))
        (catch Exception _ nil)))))

(defn- group-kill
  "SIGKILL the whole process group of pid (pid must be a group leader).
   Returns the kill exit code (0 = the group existed and was signaled).
   POSIX `kill` (a shell builtin) is used rather than the external
   util-linux/binary `kill` because the latter parses a leading `-' arg
   as an option and silently succeeds without signaling anything."
  [pid]
  (try
    (:exit @(proc/process ["sh" "-c" "kill -9 -\"$1\"" "sh" (str pid)]
                          {:out :discard :err :discard}))
    (catch Exception _ -1)))

(defn kill-process-tree!
  "Kill a process and all its descendants.
   Group kill first: when the process was spawned via setsid (see
   setsid-path) it is its own group leader, so `kill -9 -PID` reaps the
   whole tree in one shot — including background jobs reparented outside
   the ppid tree (mksh on Termux forks `cmd &` from the sh's parent, so
   ppid-walking alone misses them). For non-leaders the group kill fails
   (ESRCH) and we fall back to walking the process table and killing the
   root then each descendant parents-first: killing a shell's running
   child can make that shell advance to its next command, so ancestors
   must die first. This also covers ProcessBuilder children that share
   the app's process group."
  [pid]
  (if windows-os?
    ;; Windows: taskkill /T kills the tree natively
    (try
      @(proc/process ["taskkill" "/F" "/T" "/PID" (str pid)]
                     {:out :discard :err :discard})
      (catch Exception _e nil))
    (if (zero? (group-kill pid))
      nil
      (doseq [p (cons pid (collect-descendant-pids pid))]
        (try
          @(proc/process ["kill" "-9" (str p)] {:out :discard :err :discard})
          (catch Exception _e nil))))))

(defn process-pid
  "The pid of a babashka process map, or nil. The process record has no
   :pid key, and .pid is not callable from extension sci contexts — this
   host-side accessor is the shared seam."
  [p]
  (try
    (-> p :proc .pid)
    (catch Exception _ nil)))

(defn suspend-to-background!
  "Stop this process group with SIGTSTP (the job-control stop Ctrl+Z means
   in a shell) and block the calling thread until the shell resumes it —
   fg/bg send SIGCONT to the whole group, which also releases the `kill`
   child that delivered the signal. Returns true when the stop was
   requested, false on Windows (no job-control suspension; callers report
   that instead). The caller must hand the terminal back before calling
   (tui-suspend!) and reclaim it after the return (tui-resume!); the stop
   pauses every thread. pid 0 signals the caller's process group, so a
   running child (e.g. a bash tool) stops and resumes with kmet."
  []
  (if windows-os?
    false
    (do
      @(proc/process ["kill" "-s" "TSTP" "0"] {:out :discard :err :discard})
      true)))

;; ─── Pid registry ──────────────────────────────────────────────────────────
;; Processes spawned by the app, killed in bulk on shutdown.

(defonce ^:private tracked-pids (atom #{}))
(defn track-pid! [pid] (swap! tracked-pids conj pid))
(defn untrack-pid! [pid] (swap! tracked-pids disj pid))
(defn kill-tracked-children! []
  (doseq [pid @tracked-pids]
    (try (kill-process-tree! pid) (catch Exception _ nil)))
  (reset! tracked-pids #{}))

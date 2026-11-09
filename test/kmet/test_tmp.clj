(ns kmet.test-tmp
  "One scratch root per test run, deleted when the run ends.

   Every scratch directory or file a test needs comes from here, so a run
   leaves nothing behind whether its tests passed, failed or threw: the whole
   root goes away in one sweep instead of each test cleaning up after itself
   (which one failing assertion is enough to skip).

   The root lives under `target/test-tmp/`, inside the checkout rather than in
   TMPDIR: residue from a killed process stays visible, `bb clean` removes it,
   and the next run prunes what such a run left behind. A suite run without
   the runner (an extension project's own `bb test`, a REPL session) opens its
   root on first use and removes it through a shutdown hook.

   The runner (`kmet.tasks.runner`) opens a run root up front, so one root
   serves a whole `bb test` invoke and its diagnostics, and the hook drops it
   on the way out — including the exit path that `System/exit` takes."
  (:require [babashka.fs :as fs]))

(def ^:private run-root
  "The run's scratch root, once opened (see root)."
  (atom nil))

(declare end-run!)

(def ^:private parent
  "Scratch roots live here, one per run, inside the repo so `bb clean` and a
   human can see them."
  "target/test-tmp")

(def ^:private stale-ms
  "A sibling root older than this belongs to a run that died without cleaning
   up: a day is far longer than any suite and far shorter than an annoyance.
   The cutoff keeps concurrent runs (`bb test` beside `jolt test-ext`) from
   deleting each other's scratch."
  (* 24 60 60 1000))

(defn- run-root-name []
  (str "run-" (System/currentTimeMillis) "-"
       (format "%04x" (bit-and (System/nanoTime) 0xffff))))

(defn- stale-root?
  "True when DIR is a run root whose start timestamp is older than stale-ms."
  [dir]
  (let [now (System/currentTimeMillis)]
    (if-let [[_ ms] (re-matches #"run-(\d+)-[0-9a-f]+" (fs/file-name dir))]
      (> (- now (Long/parseLong ms)) stale-ms)
      false)))

(defn root
  "The run's scratch root, opening it on first use. A test asks for a
   directory (temp-dir!) rather than for this path."
  []
  (or @run-root
      (locking run-root
        (or @run-root
            (let [dir (str (fs/absolutize (fs/create-dirs (fs/path parent (run-root-name)))))
                  hook (Thread. (fn [] (end-run!)) "kmet-test-tmp-cleanup")]
              (reset! run-root dir)
              (try (.addShutdownHook (Runtime/getRuntime) hook)
                   (catch Throwable _ nil))
              dir)))))

(defn begin-run!
  "Open this run's scratch root, first pruning roots of runs that died before
   their cleanup. Answers the root."
  []
  (when-not @run-root
    (doseq [dir (when (fs/directory? parent) (fs/list-dir parent))
            :when (stale-root? dir)]
      (fs/delete-tree dir))
    (root)))

(defn end-run!
  "Delete this run's scratch root, and the shared parent once it is empty.
   Idempotent: the runner calls it on the way out and the shutdown hook calls
   it again."
  []
  (locking run-root
    (when-let [dir @run-root]
      (reset! run-root nil)
      (try (fs/delete-tree dir)
           (catch Throwable _ nil))
      (try (when (and (fs/directory? parent)
                      (empty? (fs/list-dir parent)))
             (fs/delete-tree parent))
           (catch Throwable _ nil)))))

(defn temp-dir!
  "A fresh directory under the run root. PREFIX names it for whoever reads a
   failure, and defaults to `t-`."
  ([] (temp-dir! "t-"))
  ([prefix]
   (str (fs/create-temp-dir {:dir (root) :prefix (str prefix)}))))

(defn temp-file!
  "A fresh empty file under the run root. SUFFIX is the file's extension (or
   any trailing marker the test needs), and defaults to none."
  ([] (temp-file! ""))
  ([suffix]
   (str (fs/create-temp-file {:dir (root) :prefix "t-" :suffix (str suffix)}))))

(defn delete!
  "Remove PATH (a file, a directory tree, or nothing) early, for a test whose
   subject is what remains on disk. Absence is success, and so is losing the
   race against code under test that still writes into the tree (a session
   flush landing mid-walk): the retries make that unlikely, and the run root's
   cleanup drops whatever is left either way. An exception here would report a
   test error nobody can act on."
  [path]
  (dotimes [_ 3]
    (try (fs/delete-tree path)
         (catch Throwable _ nil))))

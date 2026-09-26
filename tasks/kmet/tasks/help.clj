(ns kmet.tasks.help
  "Task-level `--help` support for the bb.edn tasks.

   `bb tasks` / `jolt tasks` print only the first line of a task's `:doc`, so
   every task doc is two-part: a summary line, a blank line, then the
   details. Task bodies that do not own their `--help` wrap in `with-help`:
   with `-h`/`--help` among the arguments the wrapper prints the running
   task's whole `:doc` (the same text `bb --doc <task>` shows) instead of
   running the body. The doc is read back from the task map both hosts bind
   as `babashka.tasks/*task*`, so bb.edn stays the single source of truth.

   run, dist, clean and slop implement `--help` in their own entry points and
   are deliberately not wrapped: their option reference is more specific than
   the bb.edn summary.")

(defn help-requested?
  "True when ARGS contains `-h` or `--help`."
  [args]
  (boolean (some #{"-h" "--help"} args)))

(defn- current-task
  "The running task map (`babashka.tasks/*task*`), or nil outside a task (the
   tests bind it themselves)."
  []
  (some-> (resolve 'babashka.tasks/*task*) deref))

(defn print-task-help!
  "Print the running task's `:doc`; no-op when no task is running or the task
   has no doc."
  []
  (when-let [doc (:doc (current-task))]
    (println doc)))

(defmacro with-help
  "Task-body wrapper: evaluate BODY unless the task arguments ask for help,
   in which case print the task's full `:doc` and return nil."
  [& body]
  `(if (help-requested? *command-line-args*)
     (print-task-help!)
     (do ~@body)))

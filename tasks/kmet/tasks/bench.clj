(ns kmet.tasks.bench
  "The `bb bench-tools` task: run agent tasks through kmet print mode in
   isolated workspaces, once per tool mode, and write session-derived results.

   The task owns the benchmark, not the app: it starts the checkout's
   `kmet.sh` as a subprocess, gives every run a clean workspace and session
   directory, and reads the session EDNL back through `kmet.app.session.stats`.
   The default pair is bash-only and eval-only; `--patch` is how a prompt,
   guideline or tool-schema change enters the comparison."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [kmet.app.session.store :as store]
            [kmet.app.version :as version]
            [kmet.libs.concurrent :as concurrent]
            [kmet.tasks.bench.metrics :as metrics]
            [kmet.tasks.bench.report :as report]))

(def bench-version 1)
(def default-modes ["bash=bash" "eval=eval"])
(def default-suite "scripts/bench/agent-suite")

(def ^:private source-file
  "The file this namespace was loaded from, captured at load time: *file* is
   dynamic and would otherwise name the caller's source."
  *file*)

(defn- repo-root []
  (or (some-> source-file fs/absolutize fs/parent fs/parent fs/parent fs/parent str)
      (str (fs/cwd))))

(defn- bad-args [message]
  (throw (ex-info message {:type :kmet.bench/bad-args})))

(defn- truncate [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- safe-name [s]
  (str/replace (str s) #"[^A-Za-z0-9._-]" "_"))

(defn- value [flag xs]
  (when-not (second xs)
    (bad-args (str flag " needs a value")))
  [(second xs) (nnext xs)])

(def ^:private value-options
  {"--suite" [:suite identity]
   "--tasks" [:tasks identity]
   "--modes" [:modes identity]
   "--model" [:model identity]
   "--thinking" [:thinking identity]
   "--repeats" [:repeats parse-long]
   "--out" [:out identity]
   "--timeout" [:timeout parse-long]
   "--kmet" [:kmet identity]
   "--agent-dir" [:agent-dir identity]
   "--label" [:label identity]})

(defn- split-flag [arg]
  (if-let [i (and (str/starts-with? arg "--")
                  (str/index-of arg "="))]
    [(subs arg 0 i) (subs arg (inc i))]
    [arg nil]))

(defn- take-value [flag inline xs]
  (if inline
    [inline (next xs)]
    (value flag xs)))

(defn- value-option [opts flag inline xs]
  (let [[k coerce] (get value-options flag)
        [v more] (take-value flag inline xs)
        v (if (identical? coerce identity)
            v
            (or (coerce v)
                (bad-args (str flag " needs a number, got " (pr-str v)))))]
    [(assoc opts k v) more]))

(defn- patch-option [opts inline xs]
  (let [[v more] (take-value "--patch" inline xs)]
    [(update opts :patches conj v) more]))

(defn- parse-args [args]
  (loop [xs (seq args)
         opts {:command nil :positional [] :modes default-modes
               :repeats 1 :thinking "off" :patches []}]
    (if-let [a (first xs)]
      (let [[flag inline] (split-flag a)
            [opts more]
            (cond
              (contains? #{"-h" "--help"} flag)
              [(assoc opts :help true) (next xs)]

              (contains? #{"run" "list" "compare" "analyze"} flag)
              [(assoc opts :command flag) (next xs)]

              (= flag "--patch")
              (patch-option opts inline xs)

              (= "--dry-run" flag)
              [(assoc opts (keyword (subs flag 2)) true) (next xs)]

              (contains? value-options flag)
              (value-option opts flag inline xs)

              (str/starts-with? a "--")
              (bad-args (str "unknown option " a))

              :else
              [(update opts :positional conj a) (next xs)])]
        (recur more opts))
      (assoc opts :command (or (:command opts) "run")))))

(defn- validate-args [opts]
  (case (:command opts)
    "compare"
    (when-not (= 2 (count (:positional opts)))
      (bad-args "compare needs two result files: baseline and candidate"))

    "analyze"
    (when-not (= 1 (count (:positional opts)))
      (bad-args "analyze needs one result file or session path"))

    "list"
    (when (seq (:positional opts))
      (bad-args "list takes no positional arguments"))

    (when (seq (:positional opts))
      (bad-args (str "unexpected argument " (pr-str (first (:positional opts)))))))
  opts)

(defn- resolve-path [root path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/file root path))))

(defn- resolve-executable [root path]
  (if (or (fs/absolute? path) (not (str/includes? (str path) "/")))
    (str path)
    (resolve-path root path)))

(defn- base-env []
  (merge (into {} (System/getenv))
         {"NO_COLOR" "1" "TERM" "dumb"}))

(defn- run-env [opts]
  (cond-> (base-env)
    (:agent-dir opts) (assoc "KMET_CODING_AGENT_DIR" (:agent-dir opts))))

(defn- run-process [cmd {:keys [dir env timeout-ms]}]
  (let [started (concurrent/monotonic-ms)
        p (process/process cmd (cond-> {:in "" :out :string :err :string}
                                 dir (assoc :dir dir)
                                 env (assoc :env env)))
        result (deref p timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do
        (process/destroy-tree p)
        (let [r (deref p 5000 nil)]
          {:exit 124 :timeout? true
           :out (str (:out r)) :err (str (:err r))
           :duration-ms (- (concurrent/monotonic-ms) started)}))
      {:exit (:exit result) :timeout? false
       :out (str (:out result)) :err (str (:err result))
       :duration-ms (- (concurrent/monotonic-ms) started)})))

(defn- kmet-command [opts]
  (let [path (or (:kmet opts) (str (fs/file (repo-root) "kmet.sh")))]
    (if (str/ends-with? path ".sh") ["bash" path] [path])))

(defn- parse-mode [spec]
  (let [[label tools] (if-let [i (str/index-of spec "=")]
                        [(subs spec 0 i) (subs spec (inc i))]
                        [spec spec])]
    (when (or (str/blank? label) (str/blank? tools))
      (bad-args (str "bad mode " (pr-str spec)
                     " — use label=tool1+tool2 or label=tool")))
    {:label label :tools (str/replace tools "+" ",")}))

(defn- load-task [dir]
  (let [task-file (fs/file dir "task.edn")
        check-file (fs/file dir "check.sh")]
    (when (fs/exists? task-file)
      (when-not (fs/exists? check-file)
        (bad-args (str "task " dir " has task.edn but no check.sh")))
      (let [task (edn/read-string (slurp (str task-file)))
            id (or (:id task) (str (fs/file-name dir)))]
        (when-not (and (string? id) (string? (:prompt task)))
          (bad-args (str "task " dir " needs string :id and :prompt")))
        (assoc task
               :id id
               :dir (str dir)
               :workspace (str (fs/file dir "workspace"))
               :check (str check-file))))))

(defn- load-suite [suite-dir]
  (let [tasks-dir (fs/file suite-dir "tasks")]
    (when-not (fs/directory? tasks-dir)
      (bad-args (str "no tasks directory under " suite-dir)))
    (->> (fs/list-dir tasks-dir)
         (filter fs/directory?)
         (sort-by str)
         (keep load-task)
         vec)))

(defn- filter-tasks [tasks spec]
  (if (str/blank? (str spec))
    tasks
    (let [ids (->> (str/split spec #",")
                   (map str/trim)
                   (remove str/blank?)
                   set)
          selected (filterv #(contains? ids (:id %)) tasks)
          found (set (map :id selected))]
      (when-let [missing (seq (sort (remove found ids)))]
        (bad-args (str "unknown task(s): " (str/join ", " missing))))
      selected)))

(defn- prepare-workspace! [task run-dir]
  (when (fs/exists? run-dir)
    (fs/delete-tree run-dir))
  (fs/create-dirs run-dir)
  (let [workspace (fs/file run-dir "workspace")]
    (if (fs/directory? (:workspace task))
      (fs/copy-tree (:workspace task) workspace)
      (fs/create-dirs workspace))
    (str workspace)))

(defn- session-file [dir]
  (when (fs/directory? dir)
    (some->> (fs/glob dir "**.ednl")
             (filter fs/regular-file?)
             (sort-by #(.toMillis (fs/last-modified-time %)))
             last
             str)))

(defn- run-command [opts task mode session-dir]
  (cond-> (into (kmet-command opts)
                ["--mode" "text"
                 "--thinking" (:thinking opts)
                 "--no-context-files"
                 "--session-dir" session-dir
                 "--tools" (:tools mode)
                 "--append-system-prompt"
                 (str "Benchmark nonce: " (random-uuid))])
    (:model opts) (into ["--model" (:model opts)])
    (seq (:patches opts)) (into (mapcat (fn [p] ["--patch" p]) (:patches opts)))
    true (conj (:prompt task))))

(defn- run-check [opts task mode workspace run-dir stdout-file stderr-file session]
  (let [env (merge (run-env opts)
                   {"KMET_BENCH_WORKSPACE" workspace
                    "KMET_BENCH_STDOUT" stdout-file
                    "KMET_BENCH_STDERR" stderr-file
                    "KMET_BENCH_SESSION" (or session "")
                    "KMET_BENCH_RUN_DIR" run-dir
                    "KMET_BENCH_TASK" (:id task)
                    "KMET_BENCH_MODE" (:label mode)})
        r (run-process ["bash" (:check task)]
                       {:dir workspace
                        :env env
                        :timeout-ms (* 1000 (or (:check-timeout-seconds task) 120))})]
    (assoc r :ok? (and (not (:timeout? r)) (zero? (:exit r))))))

(defn- run-timeout-ms [opts task]
  (* 1000 (or (:timeout-seconds task) (:timeout opts) 900)))

(defn- progress-line [index total task mode repeat ok? m r]
  (format "[%d/%d] %-18s %-8s r%d  %-4s  turns=%-3s calls=%-3s tokens=%-9s cost=%-9s %ss"
          index total (:id task) (:label mode) repeat
          (if ok? "PASS" "FAIL")
          (or (:turns m) 0)
          (or (:tool-calls m) 0)
          (or (get-in m [:tokens :total]) 0)
          (format "$%.4f" (double (or (:cost m) 0)))
          (format "%.1f" (/ (double (:duration-ms r)) 1000.0))))

(defn- run-result [opts task mode repeat workspace cmd started session-path m check
                   stdout-file stderr-file r]
  (let [ok? (and (zero? (:exit r)) (:ok? check) (some? m))
        result {:task (:id task)
                :mode (:label mode)
                :tools (:tools mode)
                :provider (or (:provider m) (:provider opts))
                :model (or (:model m) (:model opts))
                :repeat repeat
                :ok? ok?
                :exit (:exit r)
                :timeout? (:timeout? r)
                :check {:ok? (:ok? check)
                        :exit (:exit check)
                        :timeout? (:timeout? check)
                        :output (truncate (str (:out check) (:err check)) 4000)}
                :metrics m
                :duration-ms (:duration-ms r)
                :started-at started
                :finished-at (str (java.time.Instant/now))
                :workspace workspace
                :session session-path
                :stdout-file stdout-file
                :stderr-file stderr-file
                :command cmd}]
    (cond-> result
      (nil? session-path) (assoc :harness-error "no session log")
      (:timeout? r) (assoc :harness-error "kmet timed out")
      (and session-path (nil? m)) (assoc :harness-error "session log could not be read"))))

(defn- run-one [opts task mode repeat run-root index total]
  (let [run-dir (str (fs/file run-root
                              (safe-name (:id task))
                              (safe-name (:label mode))
                              (str "r" repeat)))
        session-dir (str (fs/file run-dir "sessions"))
        cmd (run-command opts task mode session-dir)]
    (if (:dry-run opts)
      (do
        (println (format "[%d/%d] DRY %s %s r%d: %s"
                         index total (:id task) (:label mode) repeat
                         (str/join " " cmd)))
        {:task (:id task) :mode (:label mode) :repeat repeat :dry-run? true})
      (let [workspace (prepare-workspace! task run-dir)
            _ (fs/create-dirs session-dir)
            started (str (java.time.Instant/now))
            r (run-process cmd {:dir workspace
                                :env (run-env opts)
                                :timeout-ms (run-timeout-ms opts task)})
            stdout-file (str (fs/file run-dir "stdout.txt"))
            stderr-file (str (fs/file run-dir "stderr.txt"))
            _ (spit stdout-file (:out r))
            _ (spit stderr-file (:err r))
            session-path (session-file session-dir)
            session (when session-path
                      (try (store/load-session session-path)
                           (catch Exception _ nil)))
            m (when session (metrics/session-metrics session))
            check (run-check opts task mode workspace run-dir
                             stdout-file stderr-file session-path)
            result (run-result opts task mode repeat workspace cmd started
                               session-path m check stdout-file stderr-file r)]
        (println (progress-line index total task mode repeat (:ok? result) m r))
        result))))

(defn- schedule [tasks modes repeats]
  (vec (for [repeat (range 1 (inc repeats))
             task tasks
             mode (if (odd? repeat) modes (reverse modes))]
         {:task task :mode mode :repeat repeat})))

(defn- write-edn! [path data]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (pprint/pprint data))))

(defn- write-text! [path text]
  (fs/create-dirs (fs/parent path))
  (spit path text))

(defn- shell-line [cmd]
  (try
    (let [out (:out (process/sh cmd))]
      (when-not (str/blank? (str out)) (str/trim (str out))))
    (catch Exception _ nil)))

(defn- environment [root]
  {:git-sha (shell-line ["git" "-C" root "rev-parse" "--short" "HEAD"])
   :git-dirty? (boolean (shell-line ["git" "-C" root "status" "--porcelain"]))
   :kmet-version (version/kmet-version)})

(defn- mode-specs [opts]
  (if (string? (:modes opts))
    (str/split (:modes opts) #",")
    (:modes opts)))

(defn- default-out [root stamp]
  (resolve-path root (str (fs/file "target" "bench-tools" (str stamp ".edn")))))

(defn- report-path [out]
  (str (if (str/ends-with? out ".edn")
         (subs out 0 (- (count out) 4))
         out)
       ".md"))

(defn- run-paths [root opts]
  (let [stamp (str (System/currentTimeMillis) "-" (subs (str (random-uuid)) 0 8))
        out (resolve-path root (or (:out opts) (default-out root stamp)))]
    {:out out
     :report-file (report-path out)
     :work-dir (str out ".work")}))

(defn- resolve-run-opts [root opts suite-dir]
  (assoc opts
         :kmet (resolve-executable root (or (:kmet opts)
                                            (str (fs/file root "kmet.sh"))))
         :patches (mapv #(resolve-path root %) (:patches opts))
         :agent-dir (when-let [d (:agent-dir opts)] (resolve-path root d))
         :suite suite-dir))

(defn- validate-run-inputs! [tasks opts]
  (when-not (seq tasks) (bad-args "no tasks selected"))
  (when-not (pos? (:repeats opts))
    (bad-args "--repeats must be a positive number")))

(defn- make-result [opts suite-dir tasks modes runs root]
  {:type :kmet.bench
   :version bench-version
   :label (or (:label opts) "local")
   :created-at (str (java.time.Instant/now))
   :suite suite-dir
   :model (:model opts)
   :thinking (:thinking opts)
   :agent-dir (:agent-dir opts)
   :kmet (:kmet opts)
   :patches (:patches opts)
   :modes modes
   :tasks (mapv #(select-keys % [:id :prompt :tags]) tasks)
   :runs runs
   :summary {:overall (report/aggregate runs)
             :by-mode (report/aggregate-by-mode runs)}
   :environment (environment root)})

(defn- emit-result! [out report-file result]
  (let [markdown (report/render-run-report result)]
    (write-edn! out result)
    (write-text! report-file markdown)
    (println)
    (println markdown)
    (println (str "Wrote " out))
    (println (str "Wrote " report-file))))

(defn- harness-error-result [entry index total e]
  (let [task (:task entry)
        mode (:mode entry)]
    (println (format "[%d/%d] %-18s %-8s r%d  FAIL  harness: %s"
                     (inc index) total (:id task) (:label mode) (:repeat entry)
                     (or (ex-message e) (str e))))
    {:task (:id task)
     :mode (:label mode)
     :tools (:tools mode)
     :repeat (:repeat entry)
     :ok? false
     :harness-error (or (ex-message e) (str e))}))

(defn- run-suite [opts]
  (let [root (repo-root)
        suite-dir (resolve-path root (or (:suite opts) default-suite))
        tasks (filter-tasks (load-suite suite-dir) (:tasks opts))
        modes (mapv parse-mode (mode-specs opts))
        _ (validate-run-inputs! tasks opts)
        {:keys [out report-file work-dir]} (run-paths root opts)
        opts (resolve-run-opts root opts suite-dir)
        scheduled (schedule tasks modes (:repeats opts))
        total (count scheduled)
        runs (mapv (fn [entry index]
                     (try
                       (run-one opts (:task entry) (:mode entry) (:repeat entry)
                                work-dir (inc index) total)
                       (catch Exception e
                         (harness-error-result entry index total e))))
                   scheduled
                   (range))]
    (if (:dry-run opts)
      (do
        (println "Dry run: no kmet process was started.")
        0)
      (let [result (make-result opts suite-dir tasks modes runs root)]
        (emit-result! out report-file result)
        0))))

(defn- list! [opts]
  (let [root (repo-root)
        suite-dir (resolve-path root (or (:suite opts) default-suite))
        tasks (filter-tasks (load-suite suite-dir) (:tasks opts))]
    (println "id                 tags            prompt")
    (doseq [task tasks]
      (println (format "%-18s %-15s %s"
                       (:id task)
                       (str/join "," (map name (:tags task)))
                       (truncate (str/replace (str (:prompt task)) #"\s+" " ") 100)))))
  0)

(defn- read-result [path]
  (let [data (edn/read-string (slurp path))]
    (when-not (= :kmet.bench (:type data))
      (bad-args (str path " is not a bench-tools result")))
    data))

(defn- compare! [opts]
  (let [[base-path cand-path] (:positional opts)
        root (repo-root)
        base (read-result (resolve-path root base-path))
        cand (read-result (resolve-path root cand-path))]
    (println (report/render-compare-report base cand))
    0))

(defn- analyze-session! [file]
  (let [session (store/load-session file)
        m (metrics/session-metrics session)]
    (println (str "# " file))
    (println)
    (println (format "- turns: %d" (:turns m)))
    (println (format "- tool calls: %d" (:tool-calls m)))
    (println (format "- tokens: %d (input %d, output %d, cache-read %d, cache-write %d)"
                     (get-in m [:tokens :total])
                     (get-in m [:tokens :input])
                     (get-in m [:tokens :output])
                     (get-in m [:tokens :cache-read])
                     (get-in m [:tokens :cache-write])))
    (println (format "- cost: $%.4f" (double (or (:cost m) 0))))
    (println (format "- first prompt: %s" (or (:first-prompt-tokens m) "—")))
    (println (format "- tool result tokens: %d" (:tool-result-tokens m)))
    (println (format "- declared tools: %s" (str/join ", " (:declared-tools m))))
    (println (format "- errors: %d %s" (:errors m) (pr-str (:error-kinds m))))
    (println)
    (println "## By tool")
    (doseq [[tool {:keys [calls tokens]}] (:by-tool m)]
      (println (format "- %s: %d calls, %d result tokens" tool calls tokens)))
    (println)
    (println "## Eval profile")
    (println (pr-str (:eval m)))))

(defn- analyze! [opts]
  (let [root (repo-root)
        path (resolve-path root (first (:positional opts)))]
    (cond
      (fs/directory? path)
      (doseq [file (sort (map str (fs/glob path "**.ednl")))]
        (analyze-session! file))

      (and (fs/regular-file? path) (str/ends-with? path ".ednl"))
      (analyze-session! path)

      (fs/regular-file? path)
      (let [data (edn/read-string (slurp path))]
        (if (= :kmet.bench (:type data))
          (println (report/render-run-report data))
          (bad-args (str path " is neither a session log nor a bench-tools result"))))

      :else
      (bad-args (str "no such file or directory: " path))))
  0)

(def ^:private usage-lines
  ["bb bench-tools [run|list|compare|analyze] [options]"
   ""
   "Run (default): execute the suite in clean workspaces, once per mode."
   "  --suite DIR           task suite (default scripts/bench/agent-suite)"
   "  --tasks IDS           comma-separated task ids (default: all)"
   "  --modes SPECS         label=tool1+tool2 entries, comma separated (default bash=bash,eval=eval)"
   "  --repeats N           paired repetitions per task and mode (default 1; use 3+)"
   "  --model PATTERN       kmet --model value, provider included (default: settings)"
   "  --thinking LEVEL      kmet --thinking value (default off)"
   "  --patch FILE          composition layer passed to kmet (repeatable)"
   "  --label NAME          name this run in reports"
   "  --out FILE            result EDN (default target/bench-tools/<stamp>.edn)"
   "                        report is <out>.md; workspaces live under <out>.work"
   "  --timeout SECONDS     per-run kmet timeout (default 900)"
   "  --kmet PATH           runner (default this checkout's kmet.sh)"
   "  --agent-dir DIR       KMET_CODING_AGENT_DIR for the run"
   "  --dry-run             print the planned commands without running them"
   ""
   "List:    bb bench-tools list [--suite DIR] [--tasks IDS]"
   "Compare: bb bench-tools compare BASELINE.edn CANDIDATE.edn"
   "Analyze: bb bench-tools analyze PATH   (result EDN, session EDNL, or a directory)"
   ""
   "The task suite is described in scripts/bench/README.md."])

(defn- usage [] (str/join "\n" usage-lines))
(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (if (:help opts)
        (do (println (usage)) 0)
        (let [opts (validate-args opts)]
          (case (:command opts)
            "list" (list! opts)
            "compare" (compare! opts)
            "analyze" (analyze! opts)
            (run-suite opts)))))
    (catch Exception e
      (binding [*out* *err*]
        (println (or (ex-message e) (str e))))
      (when (= :kmet.bench/bad-args (:type (ex-data e)))
        (println (usage)))
      (System/exit 1))))

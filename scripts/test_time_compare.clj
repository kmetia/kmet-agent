;; Per-namespace test-time comparison between kmet's two hosts: runs
;; `bb test` and `jolt test` (or `test-ext` with --slow), tees each run's
;; combined output into a log, parses the runner's "Testing <ns>" /
;; "<n> tests ... (<time>)" pairs and prints the joined table. Each
;; namespace shows two measures per host: the time the runner itself reports
;; and the wall time measured here from the output line arrival (Testing
;; header -> summary), both at µs resolution for sub-millisecond runs; the
;; totals add both per-namespace sums and each run's process wall clock
;; (which also carries namespace loading).
;;
;; Usage:
;;   bb scripts/test_time_compare.clj [options] [test-filter ...]
;;
;; Options:
;;   -s, --slow          compare the ^:slow suite (test-ext) instead of test
;;       --only HOST     run only HOST (bb|jolt); logs already on disk still join
;;       --reuse         don't run anything — render the table from the logs
;;       --quiet         capture without echoing the runs' output
;;       --log-dir DIR   logs + run metadata (default: target/test-time)
;;       --bb PATH       bb executable (default: bb from PATH)
;;       --jolt PATH     jolt executable (default: jolt from PATH)
;;       --sort KEY      jolt (default) | bb | ratio | name
;;   -h, --help          this help
;;
;; Filters are passed through to both runners verbatim (a plain test var
;; name, an ns/var pair, or a whole namespace). Logs and run metadata stay
;; under --log-dir, so `--reuse` re-renders the table without re-running.
;; On the ^:slow suite ratios mislead where a test sleeps — the wall-clock
;; wait dominates; read those columns as absolute per-namespace times.

(ns test-time-compare
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ─── runner-log parsing ────────────────────────────────────────────────────

(def ^:private testing-re #"^Testing (.+)$")
(def ^:private summary-re #"^  (\d+) tests?.* \((\d+(?:\.\d+)?) (µs|ms|s)\)$")
(def ^:private timeout-re #"^  TIMED OUT after (\d+(?:\.\d+)?) (µs|ms|s)")
(def ^:private error-re #"^  ERROR: (.*)$")
(def ^:private unloaded-re #"^  ([A-Za-z][\w.\-]*) — (.*)$")
(def ^:private total-re #"^Ran (\d+) tests containing (\d+) assertions in (.+)\.$")
(def ^:private duration-re #"(\d+(?:\.\d+)?) (µs|ms|s)")

(defn- parse-duration
  "Runner-formatted \"234 µs\" / \"12 ms\" / \"1.2 s\" to microseconds."
  [n unit]
  (long (* (Double/parseDouble n)
           (case unit "s" 1000000.0 "ms" 1000.0 1.0))))

(defn- parse-lines
  "Walk the runner output as [at-us line] pairs (AT nil when parsing a
   saved log). Returns
   {:nss {ns {:status :ran|:timeout|:error :us int :tests int :message str
              :wall-us int}}
    :unloaded {ns reason}
    :total {:tests int :assertions int :us int}|nil}, all times in
   microseconds. :wall-us on a namespace is the arrival gap between its
   Testing header and its summary — the externally measured run of that
   namespace, next to :us, the time the runner itself reports (both engines
   print the header before running the namespace)."
  [pairs]
  (reduce
   (fn [acc [at line]]
     (let [cur (:cur acc)
           done (fn [entry]
                  (-> acc
                      (assoc-in [:nss cur] (cond-> entry
                                             (and at (:cur-at acc))
                                             (assoc :wall-us (- at (:cur-at acc)))))
                      (assoc :cur-at nil)))]
       (cond
         (re-matches testing-re line)
         (assoc acc
                :cur (second (re-matches testing-re line))
                :cur-at at)

         (re-matches summary-re line)
         (if cur
           (let [[_ tests n unit] (re-matches summary-re line)]
             (done {:status :ran
                    :tests (parse-long tests)
                    :us (parse-duration n unit)}))
           acc)

         (re-matches timeout-re line)
         (if cur
           (let [[_ n unit] (re-matches timeout-re line)]
             (done {:status :timeout :us (parse-duration n unit)}))
           acc)

         (re-matches error-re line)
         (if cur
           (done {:status :error :message (second (re-matches error-re line))})
           acc)

         (re-matches unloaded-re line)
         (let [[_ ns-sym reason] (re-matches unloaded-re line)]
           (assoc-in acc [:unloaded ns-sym] reason))

         (re-matches total-re line)
         (let [[_ tests assertions dur] (re-matches total-re line)
               [_ n unit] (re-matches duration-re dur)]
           (assoc acc :total {:tests (parse-long tests)
                              :assertions (parse-long assertions)
                              :us (when n (parse-duration n unit))}))

         :else acc)))
   {:nss {} :unloaded {} :total nil :cur nil :cur-at nil}
   pairs))

(defn- parse-log
  "Parse a saved runner log. Saved lines carry no timestamps, so per-namespace
   wall times come from the run metadata instead (see run-host!)."
  [text]
  (parse-lines (map (fn [line] [nil line]) (str/split-lines text))))

;; ─── formatting ────────────────────────────────────────────────────────────

(defn- fmt-us
  "Compact duration from microseconds (nil → em dash), mirroring the
   runner's fmt-duration: µs below 1 ms, ms below 1 s (one decimal under
   10 ms), else seconds."
  [us]
  (cond
    (nil? us) "—"
    (< us 1000) (str (long us) " µs")
    (< us 1000000) (let [ms (/ us 1000.0)]
                     (if (< ms 10)
                       (format "%.1f ms" ms)
                       (str (long ms) " ms")))
    :else (format "%.1f s" (/ us 1000000.0))))

(defn- fmt-cell
  "Table cell for one host column: time, TIMEOUT/ERROR, or em dash."
  [entry]
  (case (:status entry)
    :ran (fmt-us (:us entry))
    :timeout "TIMEOUT"
    :error "ERROR"
    "—"))

(defn- ratio
  "JOLT-MS / BB-MS; nil when either side is missing."
  [bb-ms jolt-ms]
  (when (and bb-ms jolt-ms)
    (if (zero? bb-ms)
      (if (zero? jolt-ms) 1.0 Double/POSITIVE_INFINITY)
      (/ (double jolt-ms) bb-ms))))

(defn- fmt-ratio [r]
  (cond
    (nil? r) "—"
    (Double/isInfinite r) "∞"
    :else (format "%.2fx" r)))

(defn- fmt-by-host
  "Group [host item] pairs into `host: item, item; host2: item`."
  [pairs]
  (str/join "; "
            (for [[host xs] (group-by first pairs)]
              (str (name host) ": " (str/join ", " (map second xs))))))

;; ─── running the hosts ─────────────────────────────────────────────────────

(defn- log-file [log-dir host] (fs/file log-dir (str (name host) ".log")))
(defn- meta-file [log-dir] (fs/file log-dir "run.edn"))

(defn- run-host!
  "Run CMD, echo (unless QUIET?) and tee its combined output to LOG-FILE.
   Lines are timestamped (nanoTime, µs) as they arrive, which yields each
   namespace's externally measured wall time (the gap between its Testing
   header and its summary — both engines print the header before running the
   namespace). Returns {:cmd :exit :wall-us :wall-ns-us {ns us}}."
  [cmd log-file quiet?]
  (let [t0 (System/nanoTime)
        proc (p/process cmd {:in :inherit :out :pipe :err :out})
        pairs (with-open [w (io/writer log-file)]
                (into []
                      (keep (fn [line]
                              (let [at (quot (- (System/nanoTime) t0) 1000)]
                                (when-not quiet?
                                  (println line)
                                  (flush))
                                (.write w (str line "\n"))
                                [at line])))
                      (line-seq (io/reader (:out proc)))))
        {:keys [exit]} @proc
        wall-us (quot (- (System/nanoTime) t0) 1000)
        parsed (parse-lines pairs)]
    {:cmd (vec cmd) :exit exit :wall-us wall-us
     :wall-ns-us (into {} (keep (fn [[ns entry]]
                                  (when-let [w (:wall-us entry)] [ns w])))
                       (:nss parsed))}))

(defn- run-wall-us
  "RUN's total wall microseconds, accepting pre-µs metadata (ms × 1000)."
  [run]
  (or (:wall-us run)
      (when-let [ms (:wall-ms run)] (* 1000 ms))))

(defn- run-wall-ns
  "RUN's per-namespace wall microseconds, accepting pre-µs metadata."
  [run]
  (or (:wall-ns-us run)
      (when-let [m (:wall-ns run)]
        (into {} (map (fn [[ns ms]] [ns (* 1000 ms)])) m))
      {}))

(defn- read-meta
  "Load the run metadata, or nil when absent/unreadable."
  [f]
  (when (fs/exists? f)
    (try (edn/read-string (slurp f))
         (catch Throwable e
           (println (str "warning: ignoring unreadable " f ": " (.getMessage e)))
           nil))))

;; ─── report ────────────────────────────────────────────────────────────────

(defn- table-rows
  "Join both hosts' parsed per-namespace entries over the union of namespaces.
   RUNS carries each run's externally measured per-namespace wall times
   (:wall-ns), recorded by run-host! at capture time — saved logs have no
   timestamps."
  [parsed runs]
  (let [bb (get-in parsed [:bb :nss] {})
        jolt (get-in parsed [:jolt :nss] {})
        wall (fn [host ns] (get (run-wall-ns (get runs host)) ns))]
    (mapv (fn [ns]
            [ns {:bb (get bb ns)
                 :jolt (get jolt ns)
                 :bb-wall (wall :bb ns)
                 :jolt-wall (wall :jolt ns)}])
          (sort (distinct (concat (keys bb) (keys jolt)))))))

(defn- row-ratio [[_ {:keys [bb jolt]}]] (ratio (:us bb) (:us jolt)))

(defn- sort-rows [rows sort-k]
  (case sort-k
    :bb (sort-by (fn [[_ {:keys [bb]}]] (- (or (:us bb) -1))) rows)
    :ratio (sort-by (fn [row] (- (or (row-ratio row) -1.0))) rows)
    :name (sort-by first rows)
    (sort-by (fn [[_ {:keys [jolt]}]] (- (or (:us jolt) -1))) rows)))

(defn- host-stats [host parsed runs]
  (let [{:keys [nss total]} (get parsed host)
        ran (filter #(= :ran (:status (val %))) nss)]
    {:n (count ran)
     :sum-us (reduce + 0 (map (comp :us val) ran))
     :sum-wall (reduce + 0 (vals (run-wall-ns (get runs host))))
     :reported-us (:us total)
     :wall-us (run-wall-us (get runs host))
     :exit (get-in runs [host :exit])}))

(defn- print-ratio-line
  "One `jolt/bb LABEL : median ..., max ... (ns), slower on jolt k/n` line
   over the ratios ROW-KEY extracts from ROWS."
  [label row-key rows]
  (let [rs (keep row-key rows)]
    (when (seq rs)
      (let [sorted (vec (sort rs))
            median (nth sorted (quot (count sorted) 2))
            [top-ns _] (apply max-key (fn [row] (or (row-key row) -1.0)) rows)]
        (println (format "  %-20s: median %s, max %s (%s), slower on jolt %d/%d"
                         (str "jolt/bb " label) (fmt-ratio median) (fmt-ratio (last sorted))
                         top-ns (count (filter #(> % 1.0) rs)) (count rs)))))))

(defn- print-totals [hosts parsed runs rows]
  (let [stats (into {} (map (fn [h] [h (host-stats h parsed runs)])) hosts)
        col (fn [f] (str/join " | " (map #(str/join " " [(name %) (f (get stats %))]) hosts)))]
    (println "\ntotals")
    (println (str "  namespaces ran      : " (col :n)))
    (println (str "  self-reported sum   : " (col #(fmt-us (:sum-us %)))))
    (println (str "  self-reported total : " (col #(fmt-us (:reported-us %)))))
    (println (str "  wall sum of ns      : " (col #(fmt-us (:sum-wall %)))))
    (println (str "  wall clock (task)   : " (col #(fmt-us (:wall-us %)))))
    (println (str "  exit code           : " (col :exit)))
    (when (= 2 (count hosts))
      (print-ratio-line "(self)" row-ratio rows)
      (print-ratio-line "(wall)" (fn [[_ m]] (ratio (:bb-wall m) (:jolt-wall m))) rows))))

(defn- print-notes [hosts parsed rows]
  (let [status-entries (fn [status]
                         (for [h hosts
                               [ns e] (get-in parsed [h :nss])
                               :when (= status (:status e))]
                           [h (if (:message e) (str ns " (" (:message e) ")") ns)]))
        timeouts (status-entries :timeout)
        errors (status-entries :error)
        unloaded (for [h hosts, [ns r] (get-in parsed [h :unloaded])]
                   [h (str ns " (" r ")")])
        run-in (fn [h] (set (keys (get-in parsed [h :nss]))))
        only (for [h hosts
                   :let [other (if (= h :bb) :jolt :bb)]
                   :when (contains? parsed other)
                   ns (remove (run-in other) (run-in h))]
               [h ns])
        count-diff (for [[ns {:keys [bb jolt]}] rows
                         :let [bt (:tests bb) jt (:tests jolt)]
                         :when (and bt jt (not= bt jt))]
                     [ns (str ns " (bb " bt " / jolt " jt ")")])]
    (when (or (seq timeouts) (seq errors) (seq unloaded) (seq only) (seq count-diff))
      (println "\nnotes")
      (when (seq timeouts) (println (str "  timed out        : " (fmt-by-host timeouts))))
      (when (seq errors) (println (str "  errors           : " (fmt-by-host errors))))
      (when (seq unloaded) (println (str "  not loaded       : " (fmt-by-host unloaded))))
      (when (seq only) (println (str "  ran on one host  : " (fmt-by-host only))))
      (when (seq count-diff)
        (println (str "  test count diff  : " (str/join ", " (map second count-diff))))))))

(defn- report
  "Print the joined table, totals and notes from META's runs and their logs."
  [{:keys [log-dir sort]} meta]
  (let [runs (:runs meta)
        hosts (vec (filter (fn [h] (and (contains? runs h)
                                        (fs/exists? (log-file log-dir h))))
                           [:bb :jolt]))
        parsed (into {} (map (fn [h] [h (parse-log (slurp (log-file log-dir h)))])) hosts)]
    (if (empty? hosts)
      (println "No logs to report — run without --reuse first (or check --log-dir).")
      (let [rows (vec (sort-rows (table-rows parsed runs) sort))
            width (max 30 (reduce max 0 (map (comp count first) rows)))
            row-fmt (str "%-" width "s %10s %10s %9s %10s %10s")]
        (println (str "\nPer-namespace test times — task `" (:task meta) "`"
                      (if (seq (:filters meta))
                        (str ", filters: " (str/join " " (:filters meta)))
                        "")))
        (println "  bb/jolt = the runner's self-reported times; wall = measured from line arrival")
        (doseq [h hosts]
          (let [r (get runs h)]
            (println (format "  %-4s %s  — wall %s, exit %s"
                             (name h)
                             (str/join " " (or (:cmd r) [(name h) (:task meta)]))
                             (fmt-us (run-wall-us r)) (:exit r)))))
        (println)
        (println (format row-fmt "namespace" "bb" "jolt" "jolt/bb" "wall bb" "wall jolt"))
        (println (apply str (repeat (+ width 1 10 1 10 1 9 1 10 1 10) "─")))
        (doseq [[ns {:keys [bb jolt bb-wall jolt-wall]}] rows]
          (println (format row-fmt ns (fmt-cell bb) (fmt-cell jolt)
                           (fmt-ratio (ratio (:us bb) (:us jolt)))
                           (fmt-us bb-wall) (fmt-us jolt-wall))))
        (print-totals hosts parsed runs rows)
        (print-notes hosts parsed rows)))))

;; ─── CLI ───────────────────────────────────────────────────────────────────

(def ^:private flag-opts
  {"-s" :slow? "--slow" :slow? "--reuse" :reuse?
   "--quiet" :quiet? "-h" :help "--help" :help})

(def ^:private value-opts
  {"--only" :only "--log-dir" :log-dir "--bb" :bb "--jolt" :jolt "--sort" :sort})

(defn- usage []
  (println
   (str/join
    "\n"
    ["Usage: bb scripts/test_time_compare.clj [options] [test-filter ...]"
     ""
     "Runs `bb test` and `jolt test` (or `test-ext` with --slow) and prints each"
     "namespace's runner-reported and externally measured wall times side by side."
     ""
     "Options:"
     "  -s, --slow          compare the ^:slow suite (test-ext) instead of test"
     "      --only HOST     run only HOST (bb|jolt); logs already on disk still join"
     "      --reuse         don't run anything — render the table from the logs"
     "      --quiet         capture without echoing the runs' output"
     "      --log-dir DIR   logs + run metadata (default: target/test-time)"
     "      --bb PATH       bb executable (default: bb from PATH)"
     "      --jolt PATH     jolt executable (default: jolt from PATH)"
     "      --sort KEY      jolt (default) | bb | ratio | name"
     "  -h, --help          this help"])))

(defn- parse-args
  "Parse ARGS; unknown options and missing option values are fatal (exit 2)."
  [args]
  (loop [args (vec args)
         opts {:slow? false :only nil :reuse? false :quiet? false
               :log-dir "target/test-time" :bb "bb" :jolt "jolt"
               :sort :jolt :filters []}]
    (if (empty? args)
      opts
      (let [a (first args)]
        (cond
          (contains? flag-opts a)
          (recur (subvec args 1) (assoc opts (flag-opts a) true))

          (contains? value-opts a)
          (if (>= (count args) 2)
            (recur (subvec args 2) (assoc opts (value-opts a) (second args)))
            (do (println (str a " needs a value")) (usage) (System/exit 2)))

          (str/starts-with? a "-")
          (do (println (str "unknown option: " a)) (usage) (System/exit 2))

          :else
          (recur (subvec args 1) (update opts :filters conj a)))))))

(defn- normalize-opts
  "Validate/normalize parsed options; fatal on bad values."
  [{:keys [only sort] :as opts}]
  (when (:help opts)
    (usage)
    (System/exit 0))
  (when (and only (not (#{"bb" "jolt"} only)))
    (println (str "--only expects bb or jolt, got: " only))
    (System/exit 2))
  (let [sort-k (keyword sort)]
    (when-not (#{:jolt :bb :ratio :name} sort-k)
      (println (str "--sort expects jolt, bb, ratio or name, got: " sort))
      (System/exit 2))
    (assoc opts :only (some-> only keyword) :sort sort-k)))

(defn -main [& args]
  (let [{:keys [slow? only reuse? quiet? filters] :as opts} (normalize-opts (parse-args args))
        task (if slow? "test-ext" "test")
        log-dir (:log-dir opts)
        exe-for (fn [host] (if (= host :bb) (:bb opts) (:jolt opts)))
        run-hosts (cond reuse? []
                        only [only]
                        :else [:bb :jolt])
        _ (fs/create-dirs log-dir)
        runs (into {}
                   (keep (fn [host]
                           (let [exe (exe-for host)]
                             (if-not (fs/which exe)
                               (do (println (str "warning: `" exe "` not found — skipping the " (name host) " run"))
                                   nil)
                               (let [cmd (into [exe task] filters)
                                     lf (log-file log-dir host)]
                                 (println (str "\n==> " (str/join " " cmd) "\n    log: " lf))
                                 (flush)
                                 [host (run-host! cmd lf quiet?)])))))
                   run-hosts)
        mf (meta-file log-dir)
        old (read-meta mf)
        meta (cond
               (empty? runs) old
               (and old (= (:task old) task) (= (vec (:filters old)) (vec filters)))
               (assoc old :runs (merge (:runs old) runs))
               :else {:task task :filters (vec filters) :runs runs})]
    (when (seq runs)
      (spit mf (pr-str meta)))
    (when (or (nil? meta) (empty? (:runs meta)))
      (println "No logs to report — run without --reuse first.")
      (System/exit 1))
    (when (and (empty? runs) old
               (or (not= (:task old) task)
                   (and (seq filters) (not= (vec (:filters old)) (vec filters)))))
      (println (str "note: run metadata is for `" (:task old) "`"
                    (if (seq (:filters old))
                      (str " with filters: " (str/join " " (:filters old)))
                      "")
                    " — reporting those logs")))
    (report opts meta)
    ;; red suite red script: any compared run that exited nonzero fails this one
    (when (some #(pos? (or (get-in meta [:runs % :exit]) 0)) (keys (:runs meta)))
      (System/exit 1))))

(apply -main *command-line-args*)

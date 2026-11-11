(ns kmet.tasks.bench.report
  "Aggregation, comparison and Markdown rendering for the agent tool-mode
   benchmark."
  (:require [clojure.string :as str]))

;; ── numbers ───────────────────────────────────────────────────────────────

(defn- n [x] (double (or x 0)))

(defn mean [xs]
  (let [xs (remove nil? xs)]
    (if (seq xs)
      (/ (reduce + (map n xs)) (count xs))
      0.0)))

(defn median [xs]
  (let [xs (vec (sort (remove nil? xs)))
        count (count xs)]
    (cond
      (zero? count) 0.0
      (odd? count) (n (nth xs (quot count 2)))
      :else (/ (+ (n (nth xs (dec (quot count 2))))
                  (n (nth xs (quot count 2))))
               2.0))))

(defn sum [xs] (reduce + 0 (map n xs)))

;; ── aggregation ───────────────────────────────────────────────────────────

(defn- add-tokens [acc tokens]
  (merge-with + acc tokens))

(defn- add-by-tool [acc by-tool]
  (merge-with (fn [a b] (merge-with + a b)) acc by-tool))

(defn- add-eval [acc e]
  (-> acc
      (update :calls + (:calls e 0))
      (update :code-chars + (:code-chars e 0))
      (update :no-output + (:no-output e 0))
      (update :object-leaks + (:object-leaks e 0))
      (update :actions #(merge-with + % (:actions e)))))

(defn aggregate
  "Sums, means and medians over RUNS. Each run carries :metrics from
   kmet.tasks.bench.metrics and :ok? from the task's check."
  [runs]
  (let [runs (vec runs)
        metrics (map :metrics runs)]
    {:runs (count runs)
     :verified (count (filter :ok? runs))
     :failed (count (remove :ok? runs))
     :tokens (reduce add-tokens
                     {:input 0 :output 0 :cache-read 0 :cache-write 0 :total 0}
                     (map :tokens metrics))
     :cost (sum (map :cost metrics))
     :turns (sum (map :turns metrics))
     :tool-calls (sum (map :tool-calls metrics))
     :tool-result-tokens (sum (map :tool-result-tokens metrics))
     :first-prompt-tokens (mean (map :first-prompt-tokens metrics))
     :tool-schema-chars (mean (map :tool-schema-chars metrics))
     :duration-ms (mean (map :duration-ms runs))
     :by-tool (reduce add-by-tool {} (map :by-tool metrics))
     :errors (sum (map :errors metrics))
     :error-kinds (reduce (fn [acc m] (merge-with + acc (:error-kinds m)))
                          {}
                          metrics)
     :eval (reduce add-eval
                   {:calls 0 :code-chars 0 :no-output 0 :object-leaks 0
                    :actions {}}
                   (map :eval metrics))
     :declared-tools (->> runs
                          (mapcat #(get-in % [:metrics :declared-tools]))
                          distinct
                          vec)
     :mean {:tokens (mean (map #(get-in % [:tokens :total]) metrics))
            :cost (mean (map :cost metrics))
            :turns (mean (map :turns metrics))
            :tool-calls (mean (map :tool-calls metrics))}
     :median {:tokens (median (map #(get-in % [:tokens :total]) metrics))
              :cost (median (map :cost metrics))
              :turns (median (map :turns metrics))}}))

(defn aggregate-by-mode [runs]
  (into (sorted-map)
        (map (fn [[mode rs]] [mode (aggregate rs)]))
        (group-by :mode runs)))

(defn metric-totals [runs]
  (select-keys (aggregate runs)
               [:runs :verified :failed :tokens :cost :turns :tool-calls
                :tool-result-tokens :first-prompt-tokens :tool-schema-chars
                :duration-ms :errors]))

(defn ratio [base candidate]
  (when (and (number? base) (number? candidate) (pos? (double base)))
    (/ (double candidate) (double base))))

;; ── paired comparison ─────────────────────────────────────────────────────

(defn- run-key [run] [(:task run) (:mode run) (:repeat run)])

(defn- compare-index [runs]
  (into {} (map (juxt run-key identity)) runs))

(defn- common-keys [base candidate]
  (let [b (compare-index base)
        c (compare-index candidate)]
    (sort (filter #(contains? c %) (keys b)))))

(defn- compare-groups [base candidate group-key]
  (let [b (compare-index base)
        c (compare-index candidate)
        ks (common-keys base candidate)]
    (into (sorted-map)
          (map (fn [[group group-keys]]
                 [group {:pairs (count group-keys)
                         :baseline (metric-totals (map b group-keys))
                         :candidate (metric-totals (map c group-keys))}]))
          (group-by group-key ks))))

(defn compare-runs
  "Pair BASE and CAND by [task mode repeat] and compare only the common runs.
   Missing runs are reported, never treated as zero."
  [base candidate]
  (let [b (compare-index base)
        c (compare-index candidate)
        ks (common-keys base candidate)]
    {:pairs (count ks)
     :missing {:baseline (- (count base) (count ks))
               :candidate (- (count candidate) (count ks))}
     :baseline (metric-totals (map b ks))
     :candidate (metric-totals (map c ks))}))

(defn compare-by-mode [base candidate]
  (compare-groups base candidate second))

(defn compare-by-task [base candidate]
  (compare-groups base candidate (juxt first second)))

(defn paired-runs
  "The common runs of BASE and CAND, in key order."
  [base candidate]
  (let [b (compare-index base)
        c (compare-index candidate)
        ks (common-keys base candidate)]
    {:baseline (mapv b ks)
     :candidate (mapv c ks)
     :keys ks}))

(defn- surface-prefix [runs]
  (let [surface (filter #(= "surface" (:task %)) runs)]
    (when (seq surface)
      (mean (map #(get-in % [:metrics :first-prompt-tokens]) surface)))))

;; ── formatting ────────────────────────────────────────────────────────────

(defn- fmt-int [x]
  (if (nil? x) "—" (format "%,d" (long x))))

(defn- fmt-money [x]
  (if (nil? x) "—" (format "$%.4f" (double x))))

(defn- fmt-seconds [ms]
  (if (nil? ms) "—" (format "%.1f" (/ (double ms) 1000.0))))

(defn- fmt-ratio [r]
  (if r (format "%.2fx" (double r)) "—"))

(defn- yes-no [x] (if x "yes" "no"))

(defn- fmt-error-kinds [m]
  (if (seq m)
    (str/join ", " (map (fn [[k v]] (str (name k) "=" v)) (sort-by key m)))
    "—"))

(defn- table [headers rows]
  (str "| " (str/join " | " headers) " |\n"
       "| " (str/join " | " (repeat (count headers) "---")) " |\n"
       (str/join "\n" (map #(str "| " (str/join " | " %) " |") rows))))

(defn- over? [r x] (and r (> (double r) (double x))))
(defn- under? [r x] (and r (< (double r) (double x))))

(defn- comparison-table [base-label cand-label base-runs cand-runs]
  (let [b (metric-totals base-runs)
        c (metric-totals cand-runs)
        bp (surface-prefix base-runs)
        cp (surface-prefix cand-runs)
        row (fn [label fmt getter]
              [label
               (fmt (getter b))
               (fmt (getter c))
               (fmt-ratio (ratio (getter b) (getter c)))])
        totals [(row "tokens" fmt-int #(get-in % [:tokens :total]))
                (row "input" fmt-int #(get-in % [:tokens :input]))
                (row "output" fmt-int #(get-in % [:tokens :output]))
                (row "cache-read" fmt-int #(get-in % [:tokens :cache-read]))
                (row "cache-write" fmt-int #(get-in % [:tokens :cache-write]))
                (row "cost" fmt-money :cost)
                (row "turns" fmt-int :turns)
                (row "tool calls" fmt-int :tool-calls)
                (row "tool result tokens" fmt-int :tool-result-tokens)
                (row "tool schema chars" fmt-int :tool-schema-chars)
                ["first prompt (surface)"
                 (if bp (fmt-int bp) "—")
                 (if cp (fmt-int cp) "—")
                 (fmt-ratio (ratio bp cp))]
                (row "duration (s)" fmt-seconds :duration-ms)
                ["verified"
                 (str (:verified b) "/" (:runs b))
                 (str (:verified c) "/" (:runs c))
                 "—"]]]
    (table ["metric" base-label cand-label (str cand-label "/" base-label)]
           totals)))

;; ── diagnostics ───────────────────────────────────────────────────────────

(defn- error-totals [runs]
  (reduce (fn [acc run]
            (merge-with + acc (get-in run [:metrics :error-kinds])))
          {}
          runs))

(defn- unexpected-tools [runs]
  (for [run runs
        :let [allowed (set (str/split (str (:tools run)) #","))
              declared (set (get-in run [:metrics :declared-tools]))
              extra (sort (remove allowed declared))]
        :when (seq extra)]
    [(:mode run) extra]))

(defn- diagnostic-context [base cand]
  (let [b (metric-totals base)
        c (metric-totals cand)
        actions (get-in (aggregate cand) [:eval :actions])
        cand-count (max 1 (count cand))]
    {:b b
     :c c
     :token-ratio (ratio (get-in b [:tokens :total]) (get-in c [:tokens :total]))
     :turn-ratio (ratio (:turns b) (:turns c))
     :call-ratio (ratio (:tool-calls b) (:tool-calls c))
     :result-ratio (ratio (:tool-result-tokens b) (:tool-result-tokens c))
     :prefix-ratio (ratio (surface-prefix base) (surface-prefix cand))
     :base-errors (error-totals base)
     :cand-errors (error-totals cand)
     :base-models (set (keep :model base))
     :cand-models (set (keep :model cand))
     :extra-tools (unexpected-tools cand)
     :actions actions
     :per (fn [k] (/ (double (get actions k 0)) cand-count))}))

(defn- correctness-line [{:keys [b c]}]
  (when (< (:verified c) (:verified b))
    (format "Correctness: baseline %d/%d, candidate %d/%d. Inspect the failed checks before reading the token ratios."
            (:verified b) (:runs b) (:verified c) (:runs c))))

(defn- tokens-line [{:keys [token-ratio]}]
  (cond
    (over? token-ratio 1.10)
    (format "Total tokens are %.2fx baseline. Split the next run by input, output, cache and tool-result size before changing the prompt." token-ratio)

    (under? token-ratio 0.90)
    (format "Total tokens are %.2fx baseline (lower is better); confirm the win with more repetitions." token-ratio)))

(defn- turns-line [{:keys [turn-ratio]}]
  (when (over? turn-ratio 1.10)
    (format "Model turns are %.2fx baseline. Extra round trips with a flat tool-call count usually mean discovery, syntax retries, or a result the model cannot use." turn-ratio)))

(defn- calls-line [{:keys [call-ratio]}]
  (when (over? call-ratio 1.10)
    (format "Tool calls are %.2fx baseline. Check whether the candidate repeats reads/searches or needs several calls where the baseline needs one." call-ratio)))

(defn- results-line [{:keys [result-ratio]}]
  (when (over? result-ratio 1.10)
    (format "Tool-result tokens are %.2fx baseline. Large raw results are re-sent every turn; prefer ranged reads, summaries, or smaller default output." result-ratio)))

(defn- prefix-line [{:keys [prefix-ratio]}]
  (when (over? prefix-ratio 1.10)
    (format "The fixed prefix is %.2fx larger on the surface task. Trim the base prompt, tool descriptions/snippets, or prompt guidelines before tuning behavior." prefix-ratio)))

(defn- errors-line [{:keys [base-errors cand-errors]}]
  (when (> (sum (vals cand-errors)) (sum (vals base-errors)))
    (format "Candidate tool errors rose: %s vs %s. Fix the most frequent category first."
            (fmt-error-kinds cand-errors)
            (fmt-error-kinds base-errors))))

(defn- models-line [{:keys [base-models cand-models]}]
  (when (and (seq base-models) (seq cand-models) (not= base-models cand-models))
    (format "The observed models differ: baseline %s, candidate %s. A model change is not a tool/prompt comparison."
            (pr-str base-models) (pr-str cand-models))))

(defn- extra-tools-line [{:keys [extra-tools]}]
  (when (seq extra-tools)
    (format "Declared tools outside the mode's selection: %s. Isolate the agent directory (or remove the ambient extension) before comparing modes."
            (pr-str (vec extra-tools)))))

(defn- help-line [{:keys [per]}]
  (when (pos? (per :eval-help))
    (format "(eval/help) appears %.1f times per run. That is discovery inside the hot loop; move the essentials into the tool description/snippet or the first-call help summary." (per :eval-help))))

(defn- sh-line [{:keys [actions cand-errors]}]
  (when (and (pos? (get actions :sh 0))
             (pos? (get cand-errors :process 0)))
    "sh is being used with shell syntax. State the split plainly: sh runs one command; bash is the shell helper."))

(defn- slurp-line [{:keys [actions]}]
  (when (> (get actions :slurp 0) (get actions :read 0))
    "Whole-file slurp outnumbers ranged read. Add a line-range example and make the read verb the obvious way to inspect a large file."))

(defn- bridge-line [{:keys [actions]}]
  (when (pos? (get actions :tool-call 0))
    "The eval script still reaches through the tools bridge. Check whether the bridged tool has a local sandbox twin (fs, sh, slurp) the prompt should name."))

(defn- parity-line [{:keys [token-ratio turn-ratio]}]
  (when (and (<= (or token-ratio 1.0) 1.0)
             (<= (or turn-ratio 1.0) 1.0))
    "Parity or better on the headline ratios. Rerun with more repetitions and a cold prefix before locking it in."))

(def ^:private diagnostic-rules
  [correctness-line
   tokens-line
   turns-line
   calls-line
   results-line
   prefix-line
   errors-line
   models-line
   extra-tools-line
   help-line
   sh-line
   slurp-line
   bridge-line
   parity-line])

(defn diagnostics
  "Plain-language hypotheses from two run sets. BASE is the reference, CAND
   the variant under test; these are prompts for investigation, not verdicts."
  [base cand]
  (let [context (diagnostic-context base cand)
        lines (keep #(% context) diagnostic-rules)]
    (if (seq lines)
      (vec lines)
      ["No large regression or dominant failure pattern. Rerun with more repetitions before locking the change in."])))

;; ── run report ────────────────────────────────────────────────────────────

(defn- header-field
  ([label value] (header-field label value "—"))
  ([label value default]
   (str "- " label ": " (or value default) "\n")))

(defn- observed-models-line [models]
  (when (seq models)
    (str "- models observed: " (str/join ", " models) "\n")))

(defn- revision-line [environment]
  (when environment
    (str "- revision: " (or (:git-sha environment) "—")
         (when (:git-dirty? environment) " (dirty)")
         "\n")))

(defn- run-header [{:keys [label created-at model thinking modes tasks
                           runs suite kmet environment]}
                   models]
  (str "# kmet agent tool-mode benchmark\n\n"
       (header-field "label" label)
       (header-field "created" created-at)
       (header-field "model" model "settings default")
       (header-field "thinking" thinking "settings default")
       (observed-models-line models)
       (header-field "suite" suite)
       (header-field "kmet" kmet)
       (header-field "modes" (str/join ", " (map #(str (:label %) "=" (:tools %)) modes)))
       (header-field "tasks" (str/join ", " (map :id tasks)))
       (header-field "runs" (count runs))
       (revision-line environment)))

(defn- mode-summary-table [runs by-mode]
  (table
   ["mode" "runs" "pass" "tokens" "cost" "turns" "tool calls"
    "result tokens" "first prompt" "median tokens"]
   (for [[mode a] by-mode]
     [mode
      (fmt-int (:runs a))
      (str (:verified a) "/" (:runs a))
      (fmt-int (get-in a [:tokens :total]))
      (fmt-money (:cost a))
      (fmt-int (:turns a))
      (fmt-int (:tool-calls a))
      (fmt-int (:tool-result-tokens a))
      (if-let [p (surface-prefix (filter #(= mode (:mode %)) runs))]
        (fmt-int p)
        "—")
      (fmt-int (get-in a [:median :tokens]))])))

(defn- eval-vs-bash-section [bash-runs eval-runs]
  (when (and (seq bash-runs) (seq eval-runs))
    (str "\n\n## eval vs bash\n\n"
         (comparison-table "bash" "eval" bash-runs eval-runs))))

(defn- per-task-table [runs]
  (table
   ["task" "mode" "r" "pass" "turns" "calls" "tokens" "cost"
    "result tokens" "first prompt" "time (s)"]
   (for [run (sort-by (juxt :task :mode :repeat) runs)
         :let [m (:metrics run)]]
     [(:task run)
      (:mode run)
      (str (:repeat run))
      (yes-no (:ok? run))
      (fmt-int (:turns m))
      (fmt-int (:tool-calls m))
      (fmt-int (get-in m [:tokens :total]))
      (fmt-money (:cost m))
      (fmt-int (:tool-result-tokens m))
      (fmt-int (:first-prompt-tokens m))
      (fmt-seconds (:duration-ms run))])))

(defn- tool-mix-table [by-mode]
  (table
   ["mode" "tool" "calls" "result tokens"]
   (for [[mode a] by-mode
         [tool m] (:by-tool a)]
     [mode tool (fmt-int (:calls m)) (fmt-int (:tokens m))])))

(defn- eval-profile-table [by-mode]
  (table
   ["mode" "eval calls" "code chars" "read" "slurp" "bash" "sh"
    "emit" "eval/help" "tools bridge" "network" "no output" "#object"]
   (for [[mode a] by-mode
         :let [e (:eval a)
               actions (:actions e)]]
     [mode
      (fmt-int (:calls e))
      (fmt-int (:code-chars e))
      (fmt-int (get actions :read 0))
      (fmt-int (get actions :slurp 0))
      (fmt-int (get actions :bash 0))
      (fmt-int (get actions :sh 0))
      (fmt-int (get actions :emit 0))
      (fmt-int (get actions :eval-help 0))
      (fmt-int (get actions :tool-call 0))
      (fmt-int (get actions :network 0))
      (fmt-int (:no-output e))
      (fmt-int (:object-leaks e))])))

(defn- errors-table [by-mode]
  (table
   ["mode" "errors" "kinds"]
   (for [[mode a] by-mode]
     [mode (fmt-int (:errors a)) (fmt-error-kinds (:error-kinds a))])))

(defn- diagnostics-section [diag]
  (when (seq diag)
    (str "\n\n## Diagnostics\n\n"
         (str/join "\n" (map #(str "- " %) diag))
         "\n")))

(defn render-run-report
  "Render a run result as Markdown. A run with bash and eval modes also gets
   the direct eval/bash comparison and the diagnostic hypotheses."
  [result]
  (let [{:keys [runs]} result
        by-mode (aggregate-by-mode runs)
        models (->> runs (keep :model) distinct sort)
        bash-runs (filter #(= "bash" (:mode %)) runs)
        eval-runs (filter #(= "eval" (:mode %)) runs)
        diag (when (and (seq bash-runs) (seq eval-runs))
               (diagnostics bash-runs eval-runs))]
    (str (run-header result models)
         "\n## Mode summary\n\n"
         (mode-summary-table runs by-mode)
         (eval-vs-bash-section bash-runs eval-runs)
         "\n\n## Per task\n\n"
         (per-task-table runs)
         "\n\n## Tool mix\n\n"
         (tool-mix-table by-mode)
         "\n\n## Eval profile\n\n"
         (eval-profile-table by-mode)
         "\n\n## Errors\n\n"
         (errors-table by-mode)
         (diagnostics-section diag))))

;; ── comparison report ─────────────────────────────────────────────────────

(defn- compare-header [base-result cand-result cmp base-models cand-models]
  (str "# tool-mode variant comparison\n\n"
       "- baseline: " (or (:label base-result) "—")
       " (" (or (:created-at base-result) "—") ")\n"
       "- candidate: " (or (:label cand-result) "—")
       " (" (or (:created-at cand-result) "—") ")\n"
       "- paired runs: " (:pairs cmp)
       " (missing: baseline " (get-in cmp [:missing :baseline])
       ", candidate " (get-in cmp [:missing :candidate]) ")\n"
       "- models: baseline "
       (if (seq base-models)
         (str/join ", " base-models)
         (or (:model base-result) "settings default"))
       ", candidate "
       (if (seq cand-models)
         (str/join ", " cand-models)
         (or (:model cand-result) "settings default"))
       "\n"
       "- suites: baseline " (or (:suite base-result) "—")
       ", candidate " (or (:suite cand-result) "—") "\n"))

(defn- compare-by-mode-table [by-mode]
  (table
   ["mode" "pairs" "tokens base" "tokens cand" "ratio"
    "turns base" "turns cand" "ratio" "pass base" "pass cand"]
   (for [[mode x] by-mode
         :let [b (:baseline x) c (:candidate x)]]
     [mode
      (fmt-int (:pairs x))
      (fmt-int (get-in b [:tokens :total]))
      (fmt-int (get-in c [:tokens :total]))
      (fmt-ratio (ratio (get-in b [:tokens :total])
                        (get-in c [:tokens :total])))
      (fmt-int (:turns b))
      (fmt-int (:turns c))
      (fmt-ratio (ratio (:turns b) (:turns c)))
      (str (:verified b) "/" (:runs b))
      (str (:verified c) "/" (:runs c))])))

(defn- compare-by-task-table [by-task]
  (table
   ["task" "mode" "pairs" "tokens base" "tokens cand" "ratio"
    "turns base" "turns cand" "ratio" "pass base" "pass cand"]
   (for [[[task mode] x] by-task
         :let [b (:baseline x) c (:candidate x)]]
     [task
      mode
      (fmt-int (:pairs x))
      (fmt-int (get-in b [:tokens :total]))
      (fmt-int (get-in c [:tokens :total]))
      (fmt-ratio (ratio (get-in b [:tokens :total])
                        (get-in c [:tokens :total])))
      (fmt-int (:turns b))
      (fmt-int (:turns c))
      (fmt-ratio (ratio (:turns b) (:turns c)))
      (str (:verified b) "/" (:runs b))
      (str (:verified c) "/" (:runs c))])))

(defn render-compare-report
  "Render the comparison of two result maps (same suite, modes and tasks)."
  [base-result cand-result]
  (let [base-runs (:runs base-result)
        cand-runs (:runs cand-result)
        base-models (->> base-runs (keep :model) distinct sort)
        cand-models (->> cand-runs (keep :model) distinct sort)
        paired (paired-runs base-runs cand-runs)
        cmp (compare-runs base-runs cand-runs)
        diag (diagnostics (:baseline paired) (:candidate paired))]
    (str (compare-header base-result cand-result cmp base-models cand-models)
         "\n## Paired totals\n\n"
         (comparison-table "baseline" "candidate"
                           (:baseline paired) (:candidate paired))
         "\n\n## By mode\n\n"
         (compare-by-mode-table (compare-by-mode base-runs cand-runs))
         "\n\n## By task\n\n"
         (compare-by-task-table (compare-by-task base-runs cand-runs))
         (diagnostics-section diag))))

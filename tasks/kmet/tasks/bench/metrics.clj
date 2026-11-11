(ns kmet.tasks.bench.metrics
  "Session-derived metrics for the agent tool-mode benchmark.

   The session log is the source of truth: usage, cost, tool calls and result
   sizes are read through `kmet.app.session.stats`, the same path `/session`
   uses. The benchmark-specific additions are the fixed-prefix cost (the first
   request's prompt tokens), the final declared tool set and its schema size,
   and a small action/error profile over eval calls."
  (:require [clojure.string :as str]
            [kmet.ai.usage :as usage]
            [kmet.app.session.stats :as stats]))

(defn- request-usage
  "The normalized usage of an assistant ENTRY, or nil for any other role."
  [entry]
  (when (= :assistant (:role entry))
    (usage/entry-usage (:usage entry))))

(defn- prompt-tokens
  "The prompt the provider billed: uncached input plus cache reads and
   cache writes. This is the first request's full prefix for a fresh session."
  [u]
  (when u
    (+ (long (or (:input u) 0))
       (long (or (:cache-read u) 0))
       (long (or (:cache-write u) 0)))))

(defn declared-tools
  "The model-facing declarations after folding ENTRIES' :tool-change records,
   as name → declaration. A later record can add or remove a name."
  [entries]
  (reduce (fn [tools entry]
            (if (= :tool-change (:role entry))
              (reduce dissoc
                      (reduce (fn [acc tool]
                                (assoc acc (:name tool) tool))
                              tools
                              (:added entry))
                      (:removed entry))
              tools))
          {}
          entries))

(defn initial-declared-tools
  "The declared set at the first request: fold only the records before the
   first assistant entry. Later promotions or configuration changes are not
   the run's starting surface, which is what a tool-mode comparison needs.
   A resumed or legacy log with no pre-request records falls back to the whole
   session's final set."
  [entries]
  (let [initial (declared-tools (take-while #(not= :assistant (:role %)) entries))]
    (if (seq initial) initial (declared-tools entries))))

(defn content-text
  "A tool result's text, for the error and output profile."
  [content]
  (cond
    (string? content) content
    (vector? content)
    (str/join "\n"
              (keep (fn [block]
                      (cond
                        (string? block) block
                        (map? block) (or (:content block) (:text block))
                        :else (str block)))
                    content))
    :else (str content)))

(defn error-kind
  "A coarse category for a failed tool result, from its text."
  [text]
  (let [s (str text)]
    (cond
      (re-find #"(?i)unable to resolve symbol|could not resolve symbol" s) :unresolved-symbol
      (re-find #"(?i)cannot run program|no such file or directory" s) :process
      (re-find #"(?i)not allowed|permission denied" s) :permission
      (re-find #"(?i)unknown tool|tool .* not found" s) :unknown-tool
      (re-find #"(?i)timed out|timeout" s) :timeout
      (re-find #"(?i)read line range|read expects" s) :read-args
      (re-find #"(?i)exception|error" s) :exception
      :else :other)))

(def ^:private eval-action-patterns
  "What one eval call's code uses. These are counts of evidence, not a
   partition: one call can match several patterns."
  {:emit #"\(emit\b"
   :slurp #"\(slurp\b"
   :read #"\((?:core/)?read\b"
   :bash #"\((?:core/)?bash\b"
   :sh #"\((?:core/)?sh\b"
   :tool-call #"\(tools/(?:call!?|describe|list)\b"
   :eval-help #"\(eval/help\b"
   :network #"(?i)\((?:curl|http/get|http/post|http/request)"})

(def ^:private no-diagnosis-pattern
  "A result that tells the model nothing: the sandbox's own `(no output)`. A
   blank result counts the same way."
  #"\(no output\)")

(def ^:private object-leak-pattern
  "A host object printed into a result (`#object[...]`): unreadable, and a sign
   the sandbox printed a Path, a Process or a test fixture instead of a value."
  #"#object\[")

(defn result-quality
  "Count the results in ENTRIES that cost a turn and returned no diagnosis:
   `:no-output` for a silent result (`(no output)` or blank, e.g. an edit that
   reports nothing or a dropped `println`), `:object-leaks` for an unreadable
   host object."
  [entries]
  (let [texts (->> entries
                   (filter #(= :tool (:role %)))
                   (map #(str (content-text (:content %)))))]
    {:no-output (count (filter #(or (str/blank? (str/trim %))
                                    (re-find no-diagnosis-pattern %))
                               texts))
     :object-leaks (count (filter #(re-find object-leak-pattern %) texts))}))

(defn eval-profile
  "The shape of the eval calls in ENTRIES: call count, code size, how often
   each verb or discovery helper appears, and how many results were silent or
   unreadable."
  [entries]
  (let [code (->> entries
                  (mapcat :tool-calls)
                  (keep (fn [call]
                          (when (= "eval" (:name call))
                            (str (get-in call [:arguments :code] ""))))))
        actions (into (sorted-map)
                      (map (fn [[k re]]
                             [k (count (filter #(re-find re %) code))]))
                      eval-action-patterns)]
    (merge {:calls (count code)
            :code-chars (reduce + 0 (map count code))
            :max-code-chars (reduce max 0 (map count code))
            :actions actions}
           (result-quality entries))))

(defn session-metrics
  "Metrics for a loaded Session. The caller adds wall time, exit status and
   the check verdict."
  [session]
  (let [entries @(:entries session)
        assistants (filter #(= :assistant (:role %)) entries)
        stat (stats/get-session-stats session)
        tool-usage (stats/tool-usage session)
        first-usage (some request-usage assistants)
        declared (initial-declared-tools entries)
        tool-entries (filter #(= :tool (:role %)) entries)
        error-entries (filter :is-error tool-entries)]
    (assoc stat
           :turns (:assistant-messages stat)
           :provider (some :provider assistants)
           :model (some :model assistants)
           :first-prompt-tokens (prompt-tokens first-usage)
           :first-output-tokens (:output first-usage)
           :tool-result-tokens (get-in tool-usage [:total :tokens] 0)
           :tool-results (get-in tool-usage [:total :calls] 0)
           :by-tool (into (sorted-map) (dissoc tool-usage :total))
           :declared-tools (vec (sort (keys declared)))
           :tool-schema-chars (reduce + 0 (map #(count (pr-str %)) (vals declared)))
           :errors (count error-entries)
           :error-kinds (frequencies (map #(error-kind (content-text (:content %)))
                                          error-entries))
           :eval (eval-profile entries))))

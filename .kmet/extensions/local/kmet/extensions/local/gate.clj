(ns kmet.extensions.local.gate
  "Project gates: fix formatting, then lint and tests, and return findings by category."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [kmet.libs.process :as process]))

(defn- gate*
  "Run project gates: fix formatting, then lint and tests, and return every
   finding grouped by category.

   Changed files only by default. Formatting is checked, fixed with
   `bb format[-changed]`, and re-checked; fixed files are reported separately.
   Every selected gate runs even after an earlier one fails.

   OPTS:
     :full     (default false) run the full tree; false checks changed files only
     :tasks    :format, :lint, :test, :test-ext, or a collection of them
               (default [:format :lint :test]; :test-ext is opt-in)
     :host     test host: :bb (default), :jolt or :jvm. Lint and formatting
               always use the bb tasks; :jvm runs bb test*-jvm.
     :timeout  seconds per command (default 1800; nil disables the timeout)

   Invalid option keys, hosts, task ids or timeouts throw ex-info with :type
   ::unknown-options, ::invalid-host, ::invalid-tasks or ::invalid-timeout.

   Returns {:ok? bool :scope :changed|:full :host :bb|:jolt|:jvm
            :issue-count int :fixed-count int :formatted [file]
            :findings {category [issue ...]} :gates [gate-result ...]
            :report string}. Test issues carry the exact :namespace plus
            :test and :where; unloaded namespaces and test-side problems
            appear as :test-problem with :namespace and :message. :report is
            the human-readable category report."
  ([cwd] (gate* cwd {}))
  ([cwd {:keys [full tasks host timeout]
         :as opts
         :or {full false tasks [:format :lint :test] host :bb timeout 1800}}]
   (letfn [(relative-path [path]
             (if-not path
               path
               (let [root (str cwd)
                     prefixes [(str root "/") (str root "\\")]]
                 (if-let [prefix (some #(when (str/starts-with? path %) %) prefixes)]
                   (subs path (count prefix))
                   path))))
           (failure-blocks [lines]
             (loop [lines lines, acc [], cur nil, ns-sym nil]
               (if-let [line (first lines)]
                 (cond
                   (re-find #"^Testing (\S+)$" line)
                   (recur (rest lines)
                          (if cur (conj acc [ns-sym cur]) acc)
                          nil
                          (second (re-find #"^Testing (\S+)$" line)))
                   (re-find #"^(FAIL|ERROR) in " line)
                   (recur (rest lines) (if cur (conj acc [ns-sym cur]) acc) [line] ns-sym)
                   (and cur (or (str/blank? line)
                                (re-find #"^(Ran |Results:|No test vars matched|\s+\d+ tests?, )" line)))
                   (recur (rest lines) (conj acc [ns-sym cur]) nil ns-sym)
                   cur (recur (rest lines) acc (conj cur line) ns-sym)
                   :else (recur (rest lines) acc nil ns-sym))
                 (if cur (conj acc [ns-sym cur]) acc))))
           (section-problems [lines]
             (mapcat
              (fn [header]
                (->> lines
                     (drop-while #(not= (str/trim %) header))
                     rest
                     (take-while #(not (str/blank? %)))
                     (keep (fn [line]
                             (when-let [[_ ns-sym message]
                                        (re-matches #"^\s+(\S+)\s+—\s+(.+)$" line)]
                               {:kind :test-problem
                                :namespace ns-sym
                                :message message
                                :detail (str ns-sym " — " message)})))))
              ["Namespaces that could not load (skipped — the run fails):"
               "Requested namespace(s) failed to load (no tests could run):"
               "Tests wrote the host agent dir — point their state at a temp dir (kmet.test-tmp) instead:"]))
           (test-issues [out]
             (let [lines (str/split-lines out)]
               (into []
                     (concat
                      (for [[ns-sym block] (failure-blocks lines)
                            :let [head (first block)
                                  raw-where (second (re-find #"\(([^()]+:\d+)\)\s*$" head))
                                  where (relative-path raw-where)
                                  test-name (second (re-find #"^(?:FAIL|ERROR) in \((.+?)\)" head))
                                  detail (str (when ns-sym (str ns-sym ": "))
                                              (str/join "\n" block))
                                  detail (if (and raw-where where (not= raw-where where))
                                           (str/replace detail raw-where where)
                                           detail)]]
                        {:kind (if (str/starts-with? head "FAIL") :assertion-failure :test-error)
                         :namespace ns-sym
                         :test test-name
                         :where where
                         :detail detail})
                      (section-problems lines)
                      (for [line lines
                            :when (re-find #"^\s+TIMED OUT after " line)]
                        {:kind :timeout :detail (str/trim line)})))))
           (lint-issues [out]
             (into []
                   (keep (fn [line]
                           (when-let [[_ file row col level message]
                                      (re-matches #"^(.+):(\d+):(\d+): (error|warning|info): (.*)$" line)]
                             {:kind :lint-finding
                              :file file
                              :line (read-string row)
                              :col (read-string col)
                              :level (keyword level)
                              :message message
                              :detail line})))
                   (str/split-lines out)))
           (format-issues [out]
             (into []
                   (keep (fn [line]
                           (when-let [[_ file] (re-matches #"^(.+) has incorrect formatting$" line)]
                             {:kind :format-residual
                              :file file
                              :detail (str file " has incorrect formatting")})))
                   (str/split-lines out)))
           (summary-of [out]
             (let [lines (str/split-lines (str out))]
               (or (last (keep #(re-find #"^(?:Results: .*|Ran \d+ tests.*|linting took .*|\d+ file\(s\) formatted incorrectly|All source files formatted correctly)$" %) lines))
                   (last (remove str/blank? lines)))))
           (run-command [command]
             (let [started (try {:proc (p/process (str/split command #" ")
                                                  {:dir cwd
                                                   :out :string
                                                   :err :string})}
                                (catch Exception e {:error e}))
                   error (:error started)
                   proc (:proc started)]
               (if error
                 {:command command
                  :output (str "could not run " command ": " (ex-message error))
                  :exit-code -1
                  :timed-out? false}
                 (let [timeout-ms (when timeout (* 1000 (long timeout)))
                       result (if timeout-ms
                                (let [r (deref proc timeout-ms ::timeout)]
                                  (if (= ::timeout r)
                                    (do (when-let [pid (process/process-pid proc)]
                                          (process/kill-process-tree! pid))
                                        (assoc @proc :timed-out? true))
                                    (assoc r :timed-out? false)))
                                (assoc @proc :timed-out? false))]
                   {:command command
                    :output (str (:out result) (:err result))
                    :exit-code (:exit result)
                    :timed-out? (:timed-out? result)}))))
           (task-failure [command {:keys [output exit-code timed-out?]}]
             (let [lines (str/split-lines (str output))
                   excerpt (if (<= (count lines) 30)
                             (str/join "\n" lines)
                             (str/join "\n" (concat (take 10 lines)
                                                    ["..."]
                                                    (take-last 15 lines))))]
               {:kind :task-failure
                :detail (cond
                          timed-out? (str command " timed out after " timeout "s")
                          (not= 0 exit-code) (str command " exited " exit-code)
                          :else (str command " failed"))
                :excerpt excerpt}))
           (run-plain [{:keys [gate command]}]
             (let [res (run-command command)
                   out (:output res)
                   issues (case gate
                            (:test :test-ext) (test-issues out)
                            :lint (lint-issues out))
                   issues (cond-> issues
                            (or (:timed-out? res)
                                (and (not= 0 (:exit-code res)) (empty? issues)))
                            (conj (task-failure command res)))]
               {:gate gate
                :command command
                :ok? (empty? issues)
                :exit-code (:exit-code res)
                :timed-out? (:timed-out? res)
                :summary (summary-of out)
                :issues issues}))
           (run-format [{:keys [gate check-command fix-command]}]
             (let [pre (run-command check-command)
                   pre-issues (format-issues (:output pre))
                   need-fix? (or (:timed-out? pre) (not= 0 (:exit-code pre)) (seq pre-issues))
                   fix (when need-fix? (run-command fix-command))
                   post (when fix (run-command check-command))
                   final (or post pre)
                   final-issues (format-issues (:output final))
                   fixed (when post
                           (->> pre-issues
                                (map :file)
                                (remove (set (map :file final-issues)))
                                distinct
                                vec))
                   clean? (and (not (:timed-out? final))
                               (= 0 (:exit-code final))
                               (empty? final-issues))
                   fix-failure (when (and fix
                                          (not clean?)
                                          (or (:timed-out? fix) (not= 0 (:exit-code fix))))
                                 (task-failure fix-command fix))
                   issues (when-not clean?
                            (concat (when fix-failure [fix-failure])
                                    (if (seq final-issues)
                                      final-issues
                                      [(task-failure check-command final)])))

                   summary (cond
                             (not clean?) (or (summary-of (:output final))
                                              (str check-command " still failing"))
                             (seq fixed) (str "fixed " (count fixed) " file(s) via " fix-command ", now clean")
                             :else (summary-of (:output pre)))]
               {:gate gate
                :command check-command
                :fix-command (when fix fix-command)
                :fix-exit-code (when fix (:exit-code fix))
                :fix-timed-out? (when fix (:timed-out? fix))
                :ok? clean?
                :exit-code (:exit-code final)
                :timed-out? (:timed-out? final)
                :summary summary
                :formatted (vec fixed)
                :issues (vec issues)}))
           (issue-text [{:keys [detail excerpt]}]
             (str detail (when (seq excerpt) (str "\n" excerpt))))
           (category [gate]
             (get {:format {:key :formatting :title "Formatting"}
                   :lint {:key :lint :title "Lint"}
                   :test {:key :test :title "Tests"}
                   :test-ext {:key :test-ext :title "Slow tests"}}
                  gate))
           (render-report [scope host results]
             (let [total (count results)
                   passed (count (filter :ok? results))
                   problems (mapcat :issues results)
                   fixed (mapcat #(or (:formatted %) []) results)
                   host-label (if (some #(contains? #{:test :test-ext} (:gate %)) results)
                                (name host)
                                "bb")]
               (str/join
                "\n"
                (concat
                 [(str "gate " (name scope) " (" host-label "): "
                       (if (= passed total) "PASS" "FAIL")
                       " (" passed "/" total " gates passed, " (count problems) " issue(s), "
                       (count fixed) " file(s) fixed)")]
                 (mapcat
                  (fn [{:keys [gate command ok? exit-code timed-out? summary formatted issues]}]
                    (concat
                     [(str "## " (:title (category gate)))
                      (str (if ok? "PASS " "FAIL ") command
                           (cond
                             timed-out? " — timed out"
                             (not= 0 exit-code) (str " — exit " exit-code)
                             :else "")
                           (if (seq summary) (str " — " summary) ""))]
                     (for [file formatted] (str "  - fixed " file))
                     (for [issue issues]
                       (str "  - " (str/replace (issue-text issue) "\n" "\n    ")))))
                  results)))))]
     (let [scope (if full :full :changed)
           host (or host :bb)
           selected (set (if (coll? tasks) tasks [tasks]))
           unknown-options (remove #{:full :tasks :host :timeout} (keys opts))
           _ (when (seq unknown-options)
               (throw (ex-info (str "gate: unknown option(s) " (pr-str (vec unknown-options)))
                               {:type ::unknown-options :options (vec unknown-options)})))
           unknown (remove #{:format :lint :test :test-ext} selected)
           _ (when-not (#{:bb :jolt :jvm} host)
               (throw (ex-info (str "gate: unknown :host " (pr-str host))
                               {:type ::invalid-host :host host})))
           _ (when (or (seq unknown) (empty? selected))
               (throw (ex-info
                       (if (seq unknown)
                         (str "gate: unknown :tasks " (pr-str (vec unknown)))
                         "gate: :tasks selects no gates")
                       {:type ::invalid-tasks :tasks tasks})))
           _ (when (and (some? timeout)
                        (not (and (integer? timeout) (pos? timeout))))
               (throw (ex-info (str "gate: invalid :timeout " (pr-str timeout)
                                    " (expected a positive integer of seconds or nil)")
                               {:type ::invalid-timeout :timeout timeout})))
           suffix (if full "" "-changed")
           host-prefix (case host :jolt "jolt " "bb ")
           jvm-suffix (if (= host :jvm) "-jvm" "")
           test-command (str host-prefix "test" suffix jvm-suffix)
           test-ext-command (str host-prefix "test-ext" suffix jvm-suffix)
           gate-tasks (->> [{:gate :format
                             :check-command (str "bb format-check" suffix)
                             :fix-command (str "bb format" suffix)}
                            {:gate :lint :command (str "bb lint" suffix)}
                            {:gate :test :command test-command}
                            {:gate :test-ext :command test-ext-command}]
                           (filter #(contains? selected (:gate %))))
           results (mapv (fn [task]
                           (if (= :format (:gate task))
                             (run-format task)
                             (run-plain task)))
                         gate-tasks)
           findings (into (array-map)
                          (for [{:keys [gate issues]} results]
                            [(:key (category gate)) (vec issues)]))
           problems (mapcat :issues results)
           formatted (vec (mapcat #(or (:formatted %) []) results))
           ok? (every? :ok? results)]
       {:ok? ok?
        :scope scope
        :host host
        :issue-count (count problems)
        :fixed-count (count formatted)
        :formatted formatted
        :findings findings
        :gates results
        :report (render-report scope host results)}))))

(defn gate-verb
  "Return the local/gate eval verb bound to CWD."
  [cwd]
  (with-meta
    (fn
      ([] (gate* cwd {}))
      ([opts] (gate* cwd opts)))
    {:doc (:doc (meta #'gate*))
     :arglists '([] [opts])}))

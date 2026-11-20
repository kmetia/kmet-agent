(ns kmet.extensions.local.gate
  "Project test gates: run test suites and return findings by category."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [kmet.libs.process :as process]))

(defn- gate*
  "Run project test gates and return every finding grouped by category.

   Changed files only by default; :tasks selects the gates (default [:test]).
   Every selected gate runs even after an earlier one fails.

   OPTS:
     :full     (default false) run the full tree; false checks changed files only
     :tasks    :test (default), :test-ext, or a collection; :test-ext is opt-in
     :host     test host: :bb (default), :jolt or :jvm; :jvm runs bb test*-jvm
     :timeout  seconds per command (default 1800; nil disables the timeout)

   Invalid option keys, hosts, task ids or timeouts throw ex-info with :type
   ::unknown-options, ::invalid-host, ::invalid-tasks or ::invalid-timeout.

   Returns {:ok? bool :scope :changed|:full :host :bb|:jolt|:jvm
            :issue-count int :findings {category [issue ...]}
            :gates [gate-result ...] :report string}. Test issues carry the
            exact :namespace plus :test and :where; unloaded namespaces and
            test-side problems appear as :test-problem with :namespace and
            :message. :report is the human-readable category report."
  ([cwd] (gate* cwd {}))
  ([cwd {:keys [full tasks host timeout]
         :as opts
         :or {full false tasks [:test] host :bb timeout 1800}}]
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
           (summary-of [out]
             (let [lines (str/split-lines (str out))]
               (or (last (keep #(re-find #"^(?:Results: .*|Ran \d+ tests.*)$" %) lines))
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
           (run-tests [{:keys [gate command]}]
             (let [res (run-command command)
                   out (:output res)
                   issues (test-issues out)
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
           (issue-text [{:keys [detail excerpt]}]
             (str detail (when (seq excerpt) (str "\n" excerpt))))
           (category [gate]
             (get {:test {:key :test :title "Tests"}
                   :test-ext {:key :test-ext :title "Slow tests"}}
                  gate))
           (render-report [scope host results]
             (let [total (count results)
                   passed (count (filter :ok? results))
                   problems (mapcat :issues results)]
               (str/join
                "\n"
                (concat
                 [(str "gate " (name scope) " (" (name host) "): "
                       (if (= passed total) "PASS" "FAIL")
                       " (" passed "/" total " gates passed, " (count problems) " issue(s)")]
                 (mapcat
                  (fn [{:keys [gate command ok? exit-code timed-out? summary issues]}]
                    (concat
                     [(str "## " (:title (category gate)))
                      (str (if ok? "PASS " "FAIL ") command
                           (cond
                             timed-out? " — timed out"
                             (not= 0 exit-code) (str " — exit " exit-code)
                             :else "")
                           (if (seq summary) (str " — " summary) ""))]
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
           unknown (remove #{:test :test-ext} selected)
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
           gate-tasks (->> [{:gate :test :command test-command}
                            {:gate :test-ext :command test-ext-command}]
                           (filter #(contains? selected (:gate %))))
           results (mapv run-tests gate-tasks)
           findings (into (array-map)
                          (for [{:keys [gate issues]} results]
                            [(:key (category gate)) (vec issues)]))
           problems (mapcat :issues results)
           ok? (every? :ok? results)]
       {:ok? ok?
        :scope scope
        :host host
        :issue-count (count problems)
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

(ns kmet.ai.config-value
  "Configuration value resolution (pi: resolve-config-value.ts): shell
   commands, environment interpolation, and literals — used by models.edn
   :api-key and :headers values.

   Syntax:
     !command        → run the rest as a shell command, stdout trimmed
     $NAME / ${NAME} → interpolate the named env var; a template with a
                       missing var resolves to nil
     $$              → literal $
     $!              → literal !
     anything else   → literal value

   Shell command results are cached per command string for the process
   lifetime (pi). The plain resolution fns never throw — unresolvable
   values are nil; the -or-throw variants raise ex-info instead."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]))

(def ^:private env-var-name-re #"^[A-Za-z_][A-Za-z0-9_]*$")
(def ^:private env-var-name-prefix-re #"^[A-Za-z_][A-Za-z0-9_]*")

(def ^:private getenv
  "Env lookup — indirected so tests can redef it without touching the real
   environment (babashka cannot set env vars)."
  (fn [k] (System/getenv k)))

;; ─── Parsing (pi parseConfigValueTemplate / parseConfigValueReference) ─────

(defn parse-config-value
  "Parse CONFIG into a reference (pi parseConfigValueReference):
   {:type :command :command str} for values starting with !, else
   {:type :template :parts [part ...]} with :literal / :env parts."
  [config]
  (if (str/starts-with? config "!")
    {:type :command :command config}
    (loop [index 0 parts []]
      (if (>= index (count config))
        {:type :template :parts parts}
        (let [dollar (str/index-of config "$" index)]
          (if (nil? dollar)
            (recur (count config)
                   (if (pos? (- (count config) index))
                     (conj parts {:type :literal :value (subs config index)})
                     parts))
            (let [parts (if (pos? (- dollar index))
                          (conj parts {:type :literal :value (subs config index dollar)})
                          parts)
                  next-char (get config (inc dollar))]
              (cond
                (or (= next-char \$) (= next-char \!))
                (recur (+ dollar 2) (conj parts {:type :literal :value (str next-char)}))

                (= next-char \{)
                (let [end (str/index-of config "}" (+ dollar 2))]
                  (if (nil? end)
                    (recur (inc dollar) (conj parts {:type :literal :value "$"}))
                    (let [name (subs config (+ dollar 2) end)]
                      (if (re-matches env-var-name-re name)
                        (recur (inc end) (conj parts {:type :env :name name}))
                        (recur (inc end) (conj parts {:type :literal :value (subs config dollar (inc end))}))))))

                :else
                (if-let [n (re-find env-var-name-prefix-re (subs config (inc dollar)))]
                  (recur (+ dollar 1 (count n)) (conj parts {:type :env :name n}))
                  (recur (inc dollar) (conj parts {:type :literal :value "$"})))))))))))

(defn- resolve-env-config-value
  "Env value for NAME: the explicit ENV map first, then the process env
   (pi resolveEnvConfigValue: env?.[name] || process.env[name])."
  [name env]
  (or (get env name) (getenv name)))

(defn- template-env-var-names
  "Env var names referenced by template parts, deduped, in order."
  [parts]
  (into []
        (distinct
         (keep (fn [p] (when (= :env (:type p)) (:name p))) parts))))

(defn- resolve-template
  "Concatenate template parts, interpolating env vars; nil when any
   referenced var is missing (pi resolveTemplate)."
  [parts env]
  (reduce (fn [resolved p]
            (if (= :literal (:type p))
              (str resolved (:value p))
              (if-let [v (resolve-env-config-value (:name p) env)]
                (str resolved v)
                (reduced nil))))
          ""
          parts))

(defn get-config-value-env-var-name
  "The env var name when CONFIG is exactly one $VAR reference, else nil
   (pi getConfigValueEnvVarName)."
  [config]
  (let [{:keys [type parts]} (parse-config-value config)]
    (when (and (= :template type) (= 1 (count parts)) (= :env (:type (first parts))))
      (:name (first parts)))))

(defn get-config-value-env-var-names
  "Env var names referenced by CONFIG, deduped, in order (pi
   getConfigValueEnvVarNames). Empty for literals and !commands."
  [config]
  (let [{:keys [type parts]} (parse-config-value config)]
    (if (= :template type)
      (template-env-var-names parts)
      [])))

(defn get-missing-config-value-env-var-names
  "Env var names referenced by CONFIG that resolve to neither ENV nor the
   process env (pi getMissingConfigValueEnvVarNames)."
  [config & [env]]
  (into [] (remove #(resolve-env-config-value % env))
        (get-config-value-env-var-names config)))

(defn is-command-config-value?
  "True when CONFIG starts with ! (pi isCommandConfigValue)."
  [config]
  (= :command (:type (parse-config-value config))))

(defn is-config-value-configured?
  "True when CONFIG has no missing env vars: literals and !commands are
   always configured; a $VAR template needs the var present (pi
   isConfigValueConfigured)."
  [config & [env]]
  (empty? (get-missing-config-value-env-var-names config env)))

;; ─── Command execution (pi executeCommand, cached) ─────────────────────────

(defonce ^:private command-result-cache (atom {}))

(def ^:private command-timeout-ms 10000)

(defn- shell-path
  "Shell for !command execution (pi: spawnSync with the platform shell —
   kmet mirrors the bash-executor resolution; a plain synchronous run with
   a timeout, no TUI process-tree concerns)."
  []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (when (and fs/windows? (fs/exists? "C:\\Windows\\System32\\cmd.exe"))
        "cmd.exe")
      "sh"))

(defn- execute-command-uncached
  "Run COMMAND-CONFIG (starting with !) through the platform shell with a
   10s timeout (pi executeCommandUncached — slices the ! like pi): stdout
   trimmed; nil on timeout, non-zero exit, or empty stdout."
  [command-config]
  (let [command (subs command-config 1)
        shell (shell-path)
        args (if (str/includes? shell "cmd") [shell "/c" command] [shell "-c" command])]
    (try
      ;; :discard drains stderr at the OS level; an unrecognized keyword
      ;; (like :ignore) would leave a raw pipe nobody reads, so a command
      ;; writing more than the pipe buffer would stall until the timeout.
      (let [p (proc/process args {:out :string :err :discard})
            ;; deref blocks until exit and returns a copy of the record with
            ;; :exit (int) and :out (string) realized
            done (deref p command-timeout-ms nil)]
        (if done
          (when (zero? (:exit done))
            (let [out (str/trim (:out done))]
              (when (seq out) out)))
          (do (proc/destroy-tree p) nil)))
      (catch Exception _ nil))))

(defn- execute-command
  "Cached command execution (pi executeCommand — the cache persists for the
   process lifetime, so slow/expensive commands run once)."
  [command-config]
  (if (contains? @command-result-cache command-config)
    (get @command-result-cache command-config)
    (let [result (execute-command-uncached command-config)]
      (swap! command-result-cache assoc command-config result)
      result)))

(defn clear-config-value-cache!
  "Clear the !command result cache (pi clearConfigValueCache; exported for
   tests)."
  []
  (reset! command-result-cache {}))

;; ─── Resolution (pi resolveConfigValue et al.) ─────────────────────────────

(defn resolve-config-value-uncached
  "Like resolve-config-value but always re-runs !commands (pi
   resolveConfigValueUncached)."
  [config & [env]]
  (let [{:keys [type parts command]} (parse-config-value config)]
    (if (= :command type)
      (execute-command-uncached command)
      (resolve-template parts env))))

(defn resolve-config-value
  "Resolve CONFIG to its value: !commands execute once (cached), $VAR
   templates interpolate, everything else is a literal. nil when
   unresolvable (missing env var, failed command, timeout) (pi
   resolveConfigValue)."
  [config & [env]]
  (let [{:keys [type parts command]} (parse-config-value config)]
    (if (= :command type)
      (execute-command command)
      (resolve-template parts env))))

(defn resolve-config-value-or-throw
  "Like resolve-config-value but throws ex-info when the value cannot be
   resolved, naming the missing env vars or the failed command (pi
   resolveConfigValueOrThrow — the command runs uncached). DESCRIPTION
   names the owning entity in the error message."
  [config description & [env]]
  (let [resolved (resolve-config-value-uncached config env)]
    (if (some? resolved)
      resolved
      (let [{:keys [type command]} (parse-config-value config)]
        (throw
         (ex-info
          (cond
            (= :command type)
            (str "Failed to resolve " description " from shell command: "
                 (subs command 1))
            (= :template type)
            (let [missing (get-missing-config-value-env-var-names config env)]
              (cond
                (= 1 (count missing))
                (str "Failed to resolve " description " from environment variable: "
                     (first missing))
                (< 1 (count missing))
                (str "Failed to resolve " description " from environment variables: "
                     (str/join ", " missing))
                :else
                (str "Failed to resolve " description)))
            :else
            (str "Failed to resolve " description))
          {:type :config-value-unresolved :config config}))))))

(defn resolve-headers
  "Resolve each header value with the same rules as api keys; values that
   resolve to nil or the empty string are dropped (pi resolveHeaders).
   nil when no headers remain."
  [headers & [env]]
  (when headers
    (let [resolved (into {}
                         (keep (fn [[k v]]
                                 (when-let [rv (resolve-config-value v env)]
                                   (when (seq rv) [k rv]))))
                         headers)]
      (when (seq resolved) resolved))))

(defn resolve-headers-or-throw
  "Like resolve-headers but throws on any unresolvable value, with
   DESCRIPTION naming the owning entity (pi resolveHeadersOrThrow)."
  [headers description & [env]]
  (when headers
    (let [resolved (into {}
                         (for [[k v] headers]
                           [k (resolve-config-value-or-throw v
                                                             (str description " header \"" k "\"")
                                                             env)]))]
      (when (seq resolved) resolved))))

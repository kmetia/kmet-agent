;; kmet.extensions.clojure.eval-tool — clojure_eval, nREPL evaluation for kmet.
;;
;; Result shaping follows clojure-mcp-light's clj-nrepl-eval (EPL-2.0) and
;; samizdat's eval tool: values print as `=> v` interleaved with the output
;; that produced them, evaluation failures are an explicit "Eval error", and a
;; delimiter repair is reported instead of silently correcting the model's
;; code.

(ns kmet.extensions.clojure.eval-tool
  "clojure_eval — evaluate Clojure code in a running nREPL server."
  (:require [clojure.string :as str]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.extension :as ext]
            [kmet.extensions.clojure.edit-util :as util]
            [kmet.extensions.clojure.nrepl :as nrepl]
            [kmet.tui.theme :as theme]))

;; ─── Args ─────────────────────────────────────────────────────────────────

(defn- parse-ms [v]
  (cond
    (number? v) (long v)
    (string? v) (nrepl/parse-long* v)
    :else nil))

;; ─── Result rendering ─────────────────────────────────────────────────────

(defn- render-events
  "The events in arrival order: `=> value` for values, output text as sent
   (trailing newlines trimmed so joins stay compact)."
  [events]
  (keep (fn [[kind v]]
          (case kind
            :value (str "=> " v)
            :out (let [s (str/replace (str v) #"\n+$" "")] (when (seq s) s))
            :err (let [s (str/replace (str v) #"\n+$" "")] (when (seq s) s))
            nil))
        events))

(defn render-content
  "The model-facing result text: optional notices, then the interleaved
   output/value stream."
  [{:keys [ok? events ex timeout? cancelled?]} repaired? timeout-ms]
  (let [notices (cond-> []
                  repaired? (conj "note: repaired unbalanced delimiters before evaluating")
                  timeout? (conj (str "Evaluation timed out after " timeout-ms
                                      " ms; sent an nREPL interrupt."))
                  cancelled? (conj "Evaluation cancelled (abort requested).")
                  (and (not ok?) (not timeout?) (not cancelled?))
                  (conj (str "Eval error" (when ex (str " (" ex ")")))))]
    (str/join "\n" (concat notices (render-events events)))))

(defn- details
  [result code repaired?]
  {:code code
   :values (vec (:values result))
   :out (:out result)
   :err (:err result)
   :ns (:ns result)
   :env (name (or (:env result) :unknown))
   :session (:session result)
   :repaired? repaired?
   :timed-out? (boolean (:timeout? result))
   :cancelled? (boolean (:cancelled? result))
   :error? (not (:ok? result))})

(defn- no-server-message
  [host cwd]
  (str "No nREPL server found for host " host ".\n"
       "Looked for port files in " (or cwd "the process working directory") ": "
       (str/join ", " nrepl/port-files) ",\n"
       "then probed the common nREPL ports (" (str/join ", " nrepl/common-ports) ").\n"
       "Start an nREPL (e.g. `bb nrepl`, `clj -M:nrepl`, or your project's REPL), "
       "or pass `port` explicitly."))

;; ─── Tool execute ─────────────────────────────────────────────────────────

(defn- signal-cancelled?
  "True when the run's abort SIGNAL is set. Accepts the contextual tool's
   signal atom (deref) or a 0-arg predicate (the extension ctx's :signal)."
  [signal]
  (when signal
    (try
      (boolean (if (fn? signal) (signal) @signal))
      (catch Exception _ false))))

(defn execute
  "Tool entry point: discover the target (unless `port` is given), repair any
   delimiter imbalance, evaluate against the persistent session, and shape the
   result. The contextual 4-arity receives the run's abort signal and the
   extension context — its :cwd scopes port-file discovery."
  ([args] (execute args nil nil nil))
  ([args _on-update signal ctx]
   (let [code (util/title-arg args :code)
         host (or (util/title-arg args :host) "127.0.0.1")
         port (util/title-arg args :port)
         ns (util/title-arg args :ns)
         cwd (:cwd ctx)
         timeout-ms (or (parse-ms (util/title-arg args :timeout))
                        nrepl/default-timeout-ms)
         cancel (when signal (fn [] (signal-cancelled? signal)))]
     (if (str/blank? code)
       {:content "Missing required parameter: code" :is-error true}
       (let [[fixed repaired?] (util/repair-delimiters (str code))]
         (try
           (if-let [target (if port
                             {:host host :port port :source :parameter}
                             (nrepl/discover-port host cwd))]
             (let [result (nrepl/eval-code {:host (:host target)
                                            :port (:port target)
                                            :ns ns
                                            :timeout-ms timeout-ms
                                            :cancel cancel
                                            :code fixed})]
               {:content (render-content result repaired? timeout-ms)
                :is-error (not (:ok? result))
                :details (details result (str code) repaired?)})
             {:content (no-server-message host cwd) :is-error true})
           (catch Exception e
             {:content (str "nREPL error: " (or (ex-message e) (str e)))
              :is-error true
              :details {:code (str code) :error? true
                        :host host :port port}})))))))

;; ─── Renderers ────────────────────────────────────────────────────────────

(defn render-eval-call
  "Call line: `clojure> <code>` (+ the target port when one was given), via
   the shared code-call renderer — capped to a head window when collapsed,
   verbatim when expanded."
  [_name args theme width context]
  (let [code (or (:code args) (get args "code"))
        port (or (:port args) (get args "port"))
        suffix (when port (theme/fg theme :dim (str "  :" port)))]
    (renderers/render-code-call "clojure>" code suffix theme width context)))

(defn title
  "Quiet-mode one-liner: `clj $ <first line of the code>`, shortened."
  [args]
  (let [code (or (:code args) (get args "code"))]
    (when (string? code)
      (let [line (first (str/split-lines code))
            line (if (> (count line) 80) (str (subs line 0 80) "…") line)]
        (when (seq line) (str "clj $ " line))))))

;; ─── Registration ─────────────────────────────────────────────────────────

(defn register!
  "Register clojure_eval as a kmet tool."
  [api]
  (ext/register-tool! api
                      {:name "clojure_eval"
                       :label "Clojure Eval"
                       :contextual? true
                       :description
                       (str "Evaluate Clojure code in a running nREPL server and return the resulting values plus stdout/stderr.\n\n"
                            "The nREPL server must already be running: start one with `bb nrepl`, `clj -M:nrepl`, `lein repl`, "
                            "or your project's REPL. When `port` is omitted the tool discovers "
                            (str/join ", " (map #(str "`" % "`") nrepl/port-files)) " in the session's working directory, then probes the common nREPL ports (" (str/join ", " nrepl/common-ports) ").\n\n"
                            "Session state persists per host and port: vars and namespaces defined in one call are visible "
                            "in later calls until the server restarts or the extension reloads. This is the primary feedback "
                            "loop for Clojure work — require a namespace with `:reload` after editing it, then call its functions.\n\n"
                            "Unbalanced delimiters in `code` are repaired (delimiter-only) before evaluation and the result "
                            "says so. The read timeout defaults to " nrepl/default-timeout-ms
                            " ms; on expiry the tool sends an nREPL interrupt. A cancelled run closes the "
                            "connection and returns an `Evaluation cancelled (abort requested)` result.")
                       :prompt-snippet "Evaluate Clojure code in a running nREPL server"
                       :prompt-guidelines
                       ["Use clojure_eval to verify Clojure code in a running nREPL server — after editing a namespace, evaluate `(require '[my.ns :as ns] :reload)` and call the changed functions."
                        "The nREPL server must already be running: clojure_eval connects to `bb nrepl`, `clj -M:nrepl`, `lein repl`, or the project's REPL; it never starts one."
                        "Sessions persist per host and port, so a var defined or namespace required in one clojure_eval call is visible in the next."
                        "Pass `ns` to evaluate in a target namespace; otherwise the session's current namespace is used."
                        (str "Pass `port` when more than one nREPL is running; otherwise the tool discovers the session's port files (.nrepl-port, shadow-cljs, CIDER) in the session's working directory, then probes the common nREPL ports (" (str/join ", " nrepl/common-ports) ").")
                        "clojure_eval repairs unbalanced delimiters before evaluating and reports the repair — do not rely on it, but expect it when a form is truncated."
                        "Use `timeout` (milliseconds) to bound a long-running evaluation; on expiry the tool sends an nREPL interrupt."
                        "A cancelled run closes the connection and returns a cancelled result; an evaluation already under way on the server may still complete."]
                       :parameters
                       {:type "object"
                        :required ["code"]
                        :properties
                        {"code" {:type "string"
                                 :description "Clojure code to evaluate (one or more forms)"}
                         "port" {:type "integer"
                                 :description (str "nREPL port; auto-discovered from the session's port files or the common ports (" (str/join ", " nrepl/common-ports) ") when omitted")}
                         "host" {:type "string"
                                 :description "nREPL host (default: 127.0.0.1)"}
                         "ns" {:type "string"
                               :description "Target namespace for the evaluation (default: the session's current namespace)"}
                         "timeout" {:type "integer"
                                    :description (str "Read timeout in milliseconds (default: "
                                                      nrepl/default-timeout-ms ")")}}}
                       :render-call render-eval-call
                       :render-result renderers/render-bash-result
                       :title title
                       :execute execute}))

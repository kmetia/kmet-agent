;; Result shaping follows clojure-mcp-light's clj-nrepl-eval (EPL-2.0) and
;; samizdat's eval tool: values print as `=> v` interleaved with the output
;; that produced them, evaluation failures are an explicit "Eval error", and a
;; delimiter repair is reported instead of silently correcting the model's
;; code.

(ns kmet.libs.clj-eval
  "nREPL evaluation shared by the clojure extension's clojure_eval tool and
   eval's clj/nrepl-eval verb: target discovery, delimiter repair, and the
   model-facing result shape."
  (:require [clojure.string :as str]
            [kmet.libs.clj-edit :as clj-edit]
            [kmet.libs.nrepl :as nrepl]))

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
            (:out :err) (let [s (str/replace (str v) #"\n+$" "")]
                          (when (seq s) s))
            nil))
        events))

(defn- render-content
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

;; ─── Evaluation ───────────────────────────────────────────────────────────

(defn evaluate
  "Discover the nREPL target (unless `port` is given), repair any delimiter
   imbalance, evaluate against the persistent session, and shape the result.
   ARGS is the clojure_eval argument map (:code, :port, :host, :ns,
   :timeout) plus :cwd, which scopes port-file discovery. SIGNAL is the
   run's abort signal atom. Returns {:content :is-error :details}."
  [args signal]
  (let [code (clj-edit/title-arg args :code)
        host (or (clj-edit/title-arg args :host) "127.0.0.1")
        port (clj-edit/title-arg args :port)
        ns (clj-edit/title-arg args :ns)
        cwd (:cwd args)
        timeout-ms (or (parse-ms (clj-edit/title-arg args :timeout))
                       nrepl/default-timeout-ms)
        cancel (when signal (fn [] (boolean @signal)))]
    (if (str/blank? code)
      {:content "Missing required parameter: code" :is-error true}
      (let [[fixed repaired?] (clj-edit/repair-delimiters (str code))]
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
                       :host host :port port}}))))))

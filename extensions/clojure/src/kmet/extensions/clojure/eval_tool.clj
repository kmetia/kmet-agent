;; kmet.extensions.clojure.eval-tool — clojure_eval, nREPL evaluation for kmet.
;;
;; The execution and result shape live in kmet.libs.clj-eval, shared with the
;; eval sandbox's clj/nrepl-eval verb; this namespace is the tool record,
;; schema and renderers.

(ns kmet.extensions.clojure.eval-tool
  "clojure_eval — evaluate Clojure code in a running nREPL server."
  (:require [clojure.string :as str]
            [kmet.libs.clj-eval :as clj-eval]
            [kmet.libs.nrepl :as nrepl]
            [kmet.libs.text :as text]
            [kmet.libs.theme :as theme]
            [kmet.ui.tui.tool-renderers :as renderers]))

;; ─── Tool execute ─────────────────────────────────────────────────────────

(defn execute
  "Tool entry point: delegate to kmet.libs.clj-eval/evaluate. The contextual
   4-arity receives the run's abort signal and the extension context — its
   :cwd scopes port-file discovery."
  ([args] (clj-eval/evaluate args nil))
  ([args _on-update signal ctx]
   (clj-eval/evaluate (assoc args :cwd (:cwd ctx)) signal)))

;; ─── Renderers ────────────────────────────────────────────────────────────

(defn render-eval-call
  "Call line: `clj eval $ <code>` (+ the target port when one was given), via
   the shared code-call renderer — Clojure-highlighted; rendered verbatim,
   whatever the display mode."
  [_name args theme width context]
  (let [code (or (:code args) (get args "code"))
        port (or (:port args) (get args "port"))
        suffix (when port (theme/fg theme :dim (str "  :" port)))]
    (renderers/render-code-call "clj eval $" code suffix theme width context)))

(defn title
  "Quiet-mode one-liner: `clj eval $ <first non-blank code line>`, shortened."
  [args]
  (let [code (or (:code args) (get args "code"))]
    (when-let [line (text/title-line code)]
      (str "clj eval $ " line))))

;; ─── Registration ─────────────────────────────────────────────────────────

(defn definition
  "The clojure_eval tool record — session-shaped (it needs a running nREPL),
   registered as a `:deferred` source so its schema is not sent until
   `tool_search` loads it (see `kmet.extensions.clojure.core/init`)."
  []
  {:name "clojure_eval"
   :label "Clojure Eval"
   :contextual? true
                       ;; the eval runner: keep the Elapsed/Took tail line
                       ;; (tool-execution :status)
   :status true
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
   :execute execute})

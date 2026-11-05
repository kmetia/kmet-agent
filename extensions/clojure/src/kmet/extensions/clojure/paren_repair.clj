;; kmet.extensions.clojure.paren-repair — Delimiter repair for Clojure files.
;;
;; The repair engine lives in `kmet.libs.clj-edit`; this namespace keeps the
;; tool schema, the around-tool hooks and the tool entry point.

(ns kmet.extensions.clojure.paren-repair
  (:require [babashka.fs :as fs]
            [kmet.libs.clj-edit :as util]
            [kmet.libs.path :as path]
            [kmet.ui.tui.tool-renderers :as renderers]))

(defn title
  [args]
  (let [p (util/title-arg args :file_path)]
    (when (and (string? p) (seq p))
      (str "clojure_paren_repair " (path/home-relative p)))))

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  ([args] (util/paren-repair args))
  ([args _on-update _signal ctx]
   (util/paren-repair (assoc args :cwd (:cwd ctx)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool registration
;; ═══════════════════════════════════════════════════════════════════════════════

;; ── Hook arg helpers ──────────────────────────────────────────────────────

(defn- hook-arg
  "Read ARG key from tool args handling both keyword and string keys."
  [args k]
  (or (get args k) (get args (name k))))

(defn- write-path [args]
  (or (hook-arg args :path) (hook-arg args :file_path)))

(defn- write-content [args]
  (hook-arg args :content))

;; ── Hook: write blocked, edit warned ──────────────────────────────────────
;; write content IS the file — validate it before the call runs and settle the
;; call with a precise report (what failed, where, why). edit newText in
;; isolation is NOT the file — an edit like ")" closing a form opened elsewhere
;; is unbalanced alone but correct in context — so validate the RESULTING file
;; after the edit and warn (non-blocking) with the same report + fix hint. Both
;; cover every edamame-detectable parse problem: delimiter imbalances AND other
;; reader errors. No backup, no simulation, no duplication of the edit engine.

(defn- write-veto
  "The veto for a `write` whose content does not parse, or nil."
  [op]
  (when (= "write" (:tool-name op))
    (let [path    (write-path (:args op))
          content (write-content (:args op))]
      (when (and (util/clojure-file? path) (string? content))
        (when-let [problem (util/parse-problem path content)]
          {:block true
           :reason (str (:report problem)
                        "\nWrite blocked — "
                        (case (:kind problem)
                          :delimiter "fix the delimiters or use clojure_edit / clojure_paren_repair."
                          "fix the syntax error before writing."))})))))

(defn- edit-warning
  "The warning to append to a successful `edit`'s result when the file it left
   does not parse, or nil."
  [op result]
  (when (and (= "edit" (:tool-name op)) (not (:is-error result)) (map? result))
    (let [path (write-path (:args op))]
      (when (util/clojure-file? path)
        (try
          (when (fs/exists? path)
            (let [content (util/slurp-utf8 path)]
              (when-let [problem (util/parse-problem path content)]
                (let [hint (case (:kind problem)
                             :delimiter (str "Run clojure_paren_repair on " path " to auto-fix, or correct the delimiters manually.")
                             (str "Fix the syntax error manually (e.g. with the edit tool) — " path " does not parse."))]
                  (str (or (:content result) "")
                       "\n\n⚠️ " (:report problem)
                       "\n" hint)))))
          (catch Exception _ nil))))))

(defn on-tool
  "The around tool hook: blocks an unparseable `write` before it runs, warns
   when an `edit` left a file that does not parse, and passes every other call
   straight through."
  [op next]
  (case (:tool-name op)
    "write" (or (write-veto op) (next op))
    "edit" (let [result (next op)]
             (if-some [warning (edit-warning op result)]
               (assoc result :content warning)
               result))
    (next op)))

(defn definition
  "The clojure_paren_repair tool record — the recovery path after a broken
   edit, contributed as a `:deferred` source. The paren-check hooks are wired
   separately (`kmet.extensions.clojure.core/init`)."
  []
  {:name            "clojure_paren_repair"
   :label           "Repair delimiters"
   :description
   "Fix delimiter errors (unbalanced parentheses, brackets, braces) in a Clojure file (.clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy; other file types are rejected) using a delimiter-only, indentation-based repair.\n\nUse this tool when:\n- A file has unbalanced delimiters causing parse errors\n- You need to repair a file after an errant edit\n- The file won't compile due to unbalanced parens/brackets\n\nDetection is via edamame (a parse error carrying an unclosed opener); repair is delimiter-only and indentation-based: unclosed openers are closed at the end of their indentation block, and stray or mismatched closers are dropped; explicit closers always win and existing code is never moved or restructured. The file is then formatted with cljfmt (honoring the project's cljfmt.edn) unless format=false.\n\nReturns a status message and diff showing what changed."
   :render-call renderers/render-edit-call
   :render-result renderers/render-edit-result
   :render-shell :self
   :contextual? true
   :prompt-snippet "Fix unbalanced delimiters (parens/brackets/braces) in a Clojure file"
   :prompt-guidelines
   ["Use clojure_paren_repair when a Clojure file has unbalanced delimiters causing parse errors — after an errant edit or when the file won't compile."
    "The tool detects delimiter errors with edamame and repairs them delimiter-only (parinferish's tokenizer with indentation-based closing; existing code is never moved or restructured), then formats with cljfmt honoring the project's cljfmt.edn."
    "Pass format=false to only fix delimiters without reformatting."
    "The tool returns a diff of the changes."]
   :parameters
   {:type       "object"
    :required   ["file_path"]
    :properties
    {"file_path" {:type        "string"
                  :description "Path to the Clojure file to repair (.clj, .cljs, .cljc, .cljd, .bb, .edn, .lpy)"}
     "format"    {:type        "boolean"
                  :description "Format the file with cljfmt after repairing delimiters (default: true)"}}}
   :execute execute :title title})

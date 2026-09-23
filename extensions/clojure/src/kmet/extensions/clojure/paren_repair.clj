;; kmet.extensions.clojure.paren-repair — Delimiter repair for Clojure files.
;;
;; Port of clojure-mcp paren_repair/{core,tool}.clj: detects unbalanced
;; delimiters with edamame, repairs with parinferish (indent mode), then
;; formats with cljfmt (honoring the project's cljfmt.edn) and writes the
;; file back with a unified diff.

(ns kmet.extensions.clojure.paren-repair
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.clojure.edit-util :as util]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.extension :as ext]
            [kmet.libs.edit-diff :as edit-diff]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Core repair logic
;; ═══════════════════════════════════════════════════════════════════════════════

(defn repair-string
  "Repair delimiters in SOURCE and optionally format with cljfmt using
   FMT-OPTS (resolved from the file's directory by the caller, so the
   project's cljfmt.edn rules apply). Returns {:content str
   :delimiter-fixed? bool :formatted? bool}."
  [source format? fmt-opts]
  (let [[repaired delimiter-fixed?] (util/repair-delimiters source)
        formatted (if format?
                    (util/format-source-string repaired fmt-opts)
                    repaired)]
    {:content formatted
     :delimiter-fixed? delimiter-fixed?
     :formatted? (not= repaired formatted)}))

(defn repair-file!
  "Repair delimiter errors in FILE-PATH and optionally format.
   Returns {:success bool :message str :diff str-or-nil
            :delimiter-fixed? bool :formatted? bool}.
   A delimiter error that parinferish cannot repair (or a file that does
   not parse at all) is an explicit failure carrying the precise report —
   never a silent \"no changes\" success."
  [file-path format?]
  (cond
    (not (fs/exists? file-path))
    {:success false :message (str "File does not exist: " file-path)}

    (not (util/clojure-file? file-path))
    {:success false :message (util/not-clojure-file-msg "clojure_paren_repair" file-path)}

    :else
    (try
      (let [original (util/slurp-utf8 file-path)
            fmt-opts (util/project-fmt-opts file-path)
            result (repair-string original format? fmt-opts)
            final-content (:content result)
            changed? (not= original final-content)]
        (if changed?
          (do (util/spit-utf8 file-path final-content)
              (let [diff-str (edit-diff/generate-display-diff original final-content)
                    status-parts (cond-> []
                                   (:delimiter-fixed? result) (conj "delimiter-fixed")
                                   (:formatted? result) (conj "formatted"))]
                {:success true
                 :message (str "Fixed [" (str/join ", " status-parts) "]")
                 :delimiter-fixed? (:delimiter-fixed? result)
                 :formatted? (:formatted? result)
                 :diff diff-str}))
          ;; Nothing changed — either the file is clean or the problem is
          ;; beyond indent-mode repair. Say which, with the actual error.
          (if-let [problem (util/parse-problem file-path final-content)]
            {:success false
             :message (str "Could not fix " file-path " — it still does not parse:\n"
                           (:report problem)
                           "\n"
                           (case (:kind problem)
                             :delimiter "parinferish only closes end-of-line openers and drops stray closers — fix this one manually (e.g. with the edit tool)."
                             "Fix the syntax error manually (e.g. with the edit tool)."))
             :delimiter-fixed? false
             :formatted? false
             :diff nil}
            {:success true
             :message "No changes needed (parses cleanly; no delimiter errors)"
             :delimiter-fixed? false
             :formatted? false
             :diff nil})))
      (catch Exception e
        {:success false
         :message (str "Error: " (ex-message e))
         :delimiter-fixed? false
         :formatted? false
         :diff nil}))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool execute
;; ═══════════════════════════════════════════════════════════════════════════════

(defn title
  [args]
  (let [p (util/title-arg args :file_path)]
    (when (and (string? p) (seq p))
      (let [home (System/getProperty "user.home" "")]
        (str "clojure_paren_repair "
             (if (and (seq home)
                      (or (= p home) (clojure.string/starts-with? p (str home "/"))))
               (str "~" (subs p (count home)))
               p))))))

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  [{:keys [file_path format]}]
  (let [format? (if (nil? format) true (boolean format))]
    (cond
      (str/blank? file_path)
      {:content "Missing required parameter: file_path" :is-error true}

      :else
      (let [result (repair-file! file_path format?)]
        (if (:success result)
          (cond-> {:content (:message result)}
            (:diff result) (assoc :details {:diff (:diff result)}))
          {:content (:message result) :is-error true})))))

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

;; ── Hooks: write (pre, reject) + edit (post, warn) ────────────────────────
;; write content IS the file — validate directly in the pre-hook and block
;; with a precise report (what failed, where, why). edit newText in
;; isolation is NOT the file — an edit like ")" closing a form opened
;; elsewhere is unbalanced alone but correct in context — so validate the
;; RESULTING file in the post-hook and warn (non-blocking) with the same
;; report + fix hint. Both cover every edamame-detectable parse problem:
;; delimiter imbalances AND other reader errors. No backup, no simulation,
;; no duplication of the edit engine.

(defn on-tool-call
  "Before-tool hook: only intercepts `write` on Clojure files. Blocks any
   write whose content does not parse, stating exactly what is wrong and
   where."
  [{:keys [tool-name args]}]
  (when (= "write" tool-name)
    (let [path    (write-path args)
          content (write-content args)]
      (when (and (util/clojure-file? path) (string? content))
        (when-let [problem (util/parse-problem path content)]
          {:block true
           :reason (str (:report problem)
                        "\nWrite blocked — "
                        (case (:kind problem)
                          :delimiter "fix the delimiters or use clojure_edit / clojure_paren_repair."
                          "fix the syntax error before writing."))})))))

(defn on-tool-result
  "After-tool hook: only inspects `edit` on Clojure files after success.
   Warns (non-blocking) when the resulting file no longer parses, naming
   the actual error; delimiters get a clojure_paren_repair hint, other
   syntax errors point at manual fixing."
  [{:keys [tool-name args result is-error]}]
  (when (and (= "edit" tool-name) (not is-error) (map? result))
    (let [path (write-path args)]
      (when (util/clojure-file? path)
        (try
          (when (fs/exists? path)
            (let [content (util/slurp-utf8 path)]
              (when-let [problem (util/parse-problem path content)]
                (let [hint (case (:kind problem)
                             :delimiter (str "Run clojure_paren_repair on " path " to auto-fix, or correct the delimiters manually.")
                             (str "Fix the syntax error manually (e.g. with the edit tool) — " path " does not parse."))]
                  {:content (str (or (:content result) "")
                                 "\n\n⚠️ " (:report problem)
                                 "\n" hint)}))))
          (catch Exception _ nil))))))

(defn register!
  "Register clojure_paren_repair as a kmet tool and wire the paren-check
   hooks: write (pre, reject) + edit (post, warn). No backup, no
   simulation — write content IS the file; edit is validated on the
   resulting file after the engine applied it."
  [api]
  ((:register-tool! api)
   {:name            "clojure_paren_repair"
    :label           "Repair delimiters"
    :description
    "Fix delimiter errors (unbalanced parentheses, brackets, braces) in a Clojure file (.clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy; other file types are rejected) using parinferish.\n\nUse this tool when:\n- A file has unbalanced delimiters causing parse errors\n- You need to repair a file after an errant edit\n- The file won't compile due to unbalanced parens/brackets\n\nDetection is via edamame (a parse error carrying an unclosed opener); repair is indentation-based: opens at the end of a line are closed, and stray closes are dropped. The file is then formatted with cljfmt (honoring the project's cljfmt.edn) unless format=false.\n\nReturns a status message and diff showing what changed."
    :render-call renderers/render-edit-call
    :render-result renderers/render-edit-result
    :render-shell :self
    :prompt-snippet "Fix unbalanced delimiters (parens/brackets/braces) in a Clojure file"
    :prompt-guidelines
    ["Use clojure_paren_repair when a Clojure file has unbalanced delimiters causing parse errors — after an errant edit or when the file won't compile."
     "The tool detects delimiter errors with edamame and repairs them with parinferish (indent mode), then formats with cljfmt honoring the project's cljfmt.edn."
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
  (ext/on-tool-call api on-tool-call)
  (ext/on-tool-result api on-tool-result))

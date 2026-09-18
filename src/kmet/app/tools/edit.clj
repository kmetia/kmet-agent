(ns kmet.app.tools.edit
  "Edit tool implementation — precise text replacement in files.
   Accepts :edits (vector of maps, pi's oldText/newText or kebab-case),
   a JSON-string edits arg, or the legacy top-level old-text/new-text pair.
   Pi: edit.ts — prepareEditArguments + validateEditInput.
   Returns the applied display diff in :details.diff (pi: EditToolDetails.diff)
   so the TUI can correct its preview when the file changed between preview and apply."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [kmet.libs.edit-diff :as edit-diff]
            [kmet.app.tools.util :as tool-util]))

(defn title
  "Quiet one-liner body for the edit tool: verb + `~`-shortened path — pure data, or nil when the path is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (str "edit " (tool-util/shorten-title-path raw-path))))

(defn- normalize-edits
  "Pi: prepareEditArguments — normalize the args into {:edits [...]}.
   Handles: edits as a JSON string, camelCase oldText/newText keys,
   and the legacy top-level old-text/new-text pair (appended)."
  [{:keys [edits old-text new-text oldText newText] :as args}]
  (let [parsed (cond
                 (string? edits) (try (let [p (json/parse-string edits true)]
                                        ;; parses arrays lazily — realize
                                        ;; inside the guard so a malformed JSON
                                        ;; string degrades to nil instead of
                                        ;; throwing later in the mapv below
                                        (when (sequential? p) (vec p)))
                                      (catch Exception _ nil))
                 (sequential? edits) edits
                 :else nil)
        kebab (mapv (fn [e]
                      {:old-text (or (:old-text e) (:oldText e))
                       :new-text (or (:new-text e) (:newText e))})
                    parsed)
        legacy (when (and (or old-text oldText) (or new-text newText))
                 {:old-text (or old-text oldText) :new-text (or new-text newText)})]
    (cond-> (assoc args :edits (seq kebab))
      legacy (update :edits (fnil conj []) legacy))))

(defn execute
  "Precise text replacement in a file (pi: edit.ts execute).
   Strips BOM, normalizes line endings, matches with exact-then-fuzzy
   semantics (pi: applyEditsToNormalizedContent — not-found, duplicate,
   overlap and no-change conditions), restores the original line endings,
   and returns the applied diff in :details.diff."
  [{:keys [path] :as args}]
  (let [{:keys [edits]} (normalize-edits args)]
    (if (or (nil? edits) (empty? edits))
      ;; Pi: validateEditInput — at least one replacement required
      {:content "Edit tool input is invalid. edits must contain at least one replacement."
       :is-error true}
      (try
        ;; relative paths resolve against the runtime cwd (pi: resolveToCwd),
        ;; not the process cwd — a session resumed from another project
        (let [f (io/file (tool-util/resolve-tool-path path))]
          (if-not (fs/exists? f)
            {:content (str "File not found: " path) :is-error true}
            (let [raw (fs/read-all-bytes f)
                  ;; Jolt's (String. bytes "UTF-8") strips a leading BOM
                  ;; (Chez transcoded port); detect it on the raw bytes so
                  ;; strip-bom/restore still round-trips on both hosts.
                  has-bom (and (>= (alength raw) 3)
                               (= -17 (aget raw 0)) (= -69 (aget raw 1)) (= -65 (aget raw 2)))
                  content (String. (if has-bom
                                     (java.util.Arrays/copyOfRange raw 3 (alength raw))
                                     raw)
                                   "UTF-8")
                  {:keys [text]} (edit-diff/strip-bom content)
                  bom (if has-bom "\uFEFF" "")
                  original-ending (edit-diff/detect-line-ending text)
                  normalized (edit-diff/normalize-to-lf text)
                  {:keys [new-content]}
                  (edit-diff/apply-edits-to-normalized-content normalized edits path)
                  final (str bom (edit-diff/restore-line-endings new-content original-ending))
                  {:keys [diff]} (edit-diff/format-diff-lines
                                  (str/split-lines normalized)
                                  (str/split-lines new-content))
                  diff (when-not (str/blank? diff) diff)]
              (spit f final)
              {:content (str "Successfully replaced " (count edits) " block(s) in " path ".")
               ;; Pi: EditToolDetails.diff — the actually-applied display diff,
               ;; used by the TUI render-result to correct a stale preview
               :details (when diff {:diff diff})})))
        (catch Exception e
          (if (= :edit-error (:type (ex-data e)))
            ;; Pi: edit matching errors surface with their own message
            {:content (ex-message e) :is-error true}
            {:content (str "Error editing " path ": " (ex-message e)) :is-error true}))))))

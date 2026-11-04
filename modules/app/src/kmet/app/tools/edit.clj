(ns kmet.app.tools.edit
  "Edit tool implementation — precise text replacement in files.
   `edit-file` is the shared read-modify-write op the tool and the eval verbs
   call; `execute` adds the tool's argument normalization and rendering.
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
            [kmet.app.tools.mutation :as mutation]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.path :as path]
            [kmet.srce.core :as srce]))

(defn title
  "Quiet one-liner body for the edit tool: verb + `~`-shortened path — pure data, or nil when the path is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (str "edit " (path/home-relative raw-path))))

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

(defn- apply-edits-to-resolved
  "Read-modify-write of RESOLVED for PATH: one section under the mutation
   lock, so two parallel edits of one file cannot both match against the
   pre-edit text. Returns {:edits <count> :diff <display diff>}; a missing
   file is an :edit-error, the same message the tool reports."
  [resolved path edits]
  (let [f (io/file resolved)]
    (when-not (fs/exists? f)
      (throw (ex-info (str "File not found: " path)
                      {:type :edit-error :path path})))
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
      {:edits (count edits)
       ;; Pi: EditToolDetails.diff — the actually-applied display diff,
       ;; used by the TUI render-result to correct a stale preview
       :diff diff})))

(defn edit-file
  "Apply NORMALIZED-EDITS (a non-empty seq of {:old-text … :new-text …}) to
   PATH under the file's mutation lock: strip the BOM, normalize line endings,
   match with exact-then-fuzzy semantics, restore the original endings, write,
   and return {:edits <count> :diff <display diff>}. PATH resolves against the
   runtime cwd. Throws ex-info {:type :edit-error} for an empty edits list, a
   missing file, an empty old text, a not-found or duplicate match,
   overlapping edits, or no change — the tool's own error messages, shared
   with the eval verbs."
  [path edits signal]
  (when-not (seq edits)
    ;; Pi: validateEditInput — at least one replacement required
    (throw (ex-info "Edit tool input is invalid. edits must contain at least one replacement."
                    {:type :edit-error})))
  (let [resolved (tool-util/resolve-tool-path path)]
    (mutation/with-file-lock resolved signal
      #(apply-edits-to-resolved resolved path edits))))

(defn execute
  "Precise text replacement in a file (pi: edit.ts execute).
   Strips BOM, normalizes line endings, matches with exact-then-fuzzy
   semantics (pi: applyEditsToNormalizedContent — not-found, duplicate,
   overlap and no-change conditions), restores the original line endings,
   and returns the applied diff in :details.diff. The read-modify-write runs
   under the file's mutation lock (pi: withFileMutationQueue)."
  [{:keys [path] :as args} _on-update signal _ctx]
  (let [{:keys [edits]} (normalize-edits args)]
    (try
      (let [result (edit-file path edits signal)]
        {:content (str "Successfully replaced " (:edits result) " block(s) in " path ".")
         :details (when-let [diff (:diff result)] {:diff diff})})
      (catch Exception e
        (if (= :edit-error (:type (ex-data e)))
          ;; Pi: edit matching errors surface with their own message
          {:content (ex-message e) :is-error true}
          {:content (str "Error editing " path ": " (ex-message e)) :is-error true})))))

;; ─── The tool and its row ──────────────────────────────────────────────────

(def definition
  "The `edit` tool's record — what the `:tool/edit` row mounts."
  (tool/make-tool
   :name "edit"
   :label "Edit file"
   :description "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes."
   :prompt-snippet "Make precise file edits with exact text replacement, including multiple disjoint edits in one call"
   :prompt-guidelines ["Use edit for precise changes (edits[].oldText must match exactly)"
                       "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls"
                       "Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit."
                       "Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions."]
   :render-shell :self
   ;; Pi: editSchema — edits is an array of {oldText, newText}
   :parameters {:type "object"
                :properties {"path" {:type "string"
                                     :description "Path to the file to edit (relative or absolute)"}
                             "edits" {:type "array"
                                      :items {:type "object"
                                              :properties {"oldText" {:type "string"
                                                                      :description "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call."}
                                                           "newText" {:type "string"
                                                                      :description "Replacement text for this targeted edit."}}
                                              :required ["oldText" "newText"]}
                                      :description "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead."}}
                :required ["path" "edits"]}
   :execute execute
   ;; the cancel signal is the third execute argument (see execute)
   :contextual? true
   :title title))

(srce/defplugin plugin
  {:name :tool/edit
   :inject [:tools]
   :apply (fn [ctx config] (registry/mount-tool! ctx definition config))})

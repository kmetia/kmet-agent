(ns kmet.extensions.grep-tool
  "grep — search file contents for a pattern (opt-in extension).

   kmet's builtin tool set is read/write/edit/bash; this extension ships a
   \"search, don't read\" path as an opt-in example: matching lines
   only (file:line: text), never whole files, capped at 100 matches. Relative
   paths resolve against the runtime cwd from the tool context (:contextual?
   ctx — pi resolves tool paths against the cwd the tool definitions were
   created with), falling back to the process cwd. Results render through the
   bash result renderer: plain-text tool output, collapsed to a line window
   with an expand hint.

   Enable by symlinking/copying into ~/.kmet/agent/extensions/ or
   .kmet/extensions/, then restart or /reload — see extensions/README.md."

  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.app.ui.tool-renderers :as renderers]))

(def ^:private max-traverse-files
  "Traversal cap shared with the retired builtin (10k files)."
  10000)

(def ^:private max-matches
  "Matches shown before the result is marked truncated."
  100)

(defn- resolve-path
  "PATH against the runtime cwd from the tool context; absolute paths pass
   through, no ctx (or no :cwd) falls back to the process cwd."
  [ctx path]
  (let [p (str (or path "."))]
    (if (fs/absolute? p)
      p
      (str (fs/normalize
            (fs/path (or (:cwd ctx) (System/getProperty "user.dir")) p))))))

(defn- safe-file-seq
  "Like file-seq with symlink-cycle protection, a max-files cap and `.git`
   skipped — a repo's object store is never source-search material."
  [dir-path]
  (let [visited (atom #{})]
    (take max-traverse-files
          (filter fs/regular-file?
                  (tree-seq
                   (fn [f]
                     (and (fs/directory? f)
                          (not= ".git" (fs/file-name f))
                          (let [cp (fs/canonicalize f)]
                            (when-not (contains? @visited cp)
                              (swap! visited conj cp)
                              true))))
                   (fn [d] (fs/list-dir d))
                   (fs/file dir-path))))))

(defn- search-file!
  "Append FILE's matching lines to RESULTS; an unreadable file is recorded
   in SKIPPED instead (a binary blob must not abort the search)."
  [results skipped re file]
  (try
    (with-open [rdr (io/reader (str file))]
      (doseq [[idx line] (map-indexed vector (line-seq rdr))]
        (when (re-find re line)
          (vswap! results conj (str file ":" (inc idx) ": " line)))))
    (catch Exception _e
      (vswap! skipped conj (str file)))))

(defn- execute
  [{:keys [pattern path]} _on-update _signal ctx]
  (try
    (let [re (re-pattern pattern)
          f (io/file (resolve-path ctx path))
          results (volatile! [])
          skipped (volatile! [])]
      (if (fs/regular-file? f)
        (search-file! results skipped re f)
        (doseq [file (safe-file-seq f)]
          (search-file! results skipped re file)))
      (let [r @results
            sk @skipped]
        (if (and (empty? r) (empty? sk))
          {:content (str "No matches for \"" pattern "\"")}
          (let [base (str/join "\n" (take max-matches r))
                sk-msg (when (seq sk)
                         (str "\n\n[Skipped " (count sk) " unreadable files]"))]
            {:content (str base (or sk-msg ""))
             :truncated (> (count r) max-matches)}))))
    (catch Exception e
      {:content (str "Error searching: " (ex-message e)) :is-error true})))

(defn init
  "Register the grep tool (an opt-in search tool)."
  [api]
  (ext/register-tool! api
                      {:name "grep"
                       :label "Grep"
                       :description "Search file contents for a pattern (regex). Returns matching lines as file:line: text — matching lines only, never whole files. Prefer over read when locating code. Output is capped at 100 matches."
                       :prompt-snippet "Search files for a pattern, returning matching lines only"
                       :prompt-guidelines ["Use grep to locate code across files, then read only the file or range you need."]
                       :params {:pattern {:type :string :description "Search pattern (regex)"}
                                :path {:type :string :description "Directory or file to search (default: current directory)" :optional? true}}
                       :contextual? true
                       ;; plain-text output — the bash result body fits
                       ;; (styled lines, collapsed line window with expand
                       ;; hint, elapsed line)
                       :render-result renderers/render-bash-result
                       :execute execute}))

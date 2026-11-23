(ns kmet.app.tools.util
  "Shared utilities for tool implementations — safe file traversal,
   quiet-title path shortening, the one-line call names a title supplies, etc."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.path :as path]))

(defn safe-file-seq
  "Like file-seq but with symlink cycle protection and a max-files limit.
   Skips `.git` directories — a repo's object store is never source-search
   material."
  [dir-path]
  (path/safe-file-seq dir-path))

(defn positive-integer?
  "True when X is an integer greater than zero."
  [x]
  (and (integer? x) (pos? x)))

;; ─── Tool-selection vocabulary ────────────────────────────────────────────

(defn tool-selection-modifier?
  "True when a selection entry is a +name/-name modifier (pi:
   isToolModifier). Shared by the settings merge, the tool resolver and the
   resource resolver, so the entry grammar has one home."
  [entry]
  (and (string? entry)
       (or (str/starts-with? entry "+")
           (str/starts-with? entry "-"))))

;; ─── Tool-call arguments ────────────────────────────────────────────────

(defn args-map
  "A tool call's :arguments as a map: a map as-is, EDN text when it parses to
   a map, else {}."
  [tool-call]
  (let [a (:arguments tool-call)]
    (cond
      (map? a) a
      (string? a) (try (let [v (edn/read-string a)]
                         (if (map? v) v {}))
                       (catch Exception _ {}))
      :else {})))

(defn arg
  "TOOL-CALL's argument under K, accepting the string spelling too."
  [tool-call k]
  (let [a (args-map tool-call)]
    (or (get a k) (get a (name k)))))

(defn tool-name
  "TOOL-CALL's :name, lower-cased; empty when absent."
  [tool-call]
  (str/lower-case (str (or (:name tool-call) ""))))

(defn tool-paths
  "Every file a tool call names: its single path arg, else a :paths list."
  [tool-call]
  (let [one (some #(let [v (arg tool-call %)]
                     (when (string? v) v))
                  [:path :file_path :filePath :file])
        many (arg tool-call :paths)]
    (cond
      one [one]
      (sequential? many) (vec (filter #(and (string? %) (seq %)) many))
      :else [])))

;; ─── Runtime working directory ────────────────────────────────────────────

(def ^:dynamic *cwd*
  "The working directory tool paths resolve against (pi: the cwd the tool
   definitions were created with — createAllToolDefinitions(cwd, …); pi's
   read/write/edit resolve relative paths against the runtime cwd, not the
   process cwd). Bound by kmet.app.loop around each agent run and by the
   interactive ! flow, from the active session's cwd. nil outside those
   paths — cwd falls back to the process cwd."
  nil)

(defn cwd
  "The runtime working directory: *cwd* when a run or command bound one,
   else the process cwd."
  []
  (or *cwd* (System/getProperty "user.dir") "."))

(defn resolve-tool-path
  "Resolve PATH against the runtime working directory — pi: resolveToCwd.
   Absolute paths pass through; a relative path becomes CWD/PATH, so the
   file tools follow the session's cwd instead of the process cwd (a
   session resumed or imported from another project)."
  [path]
  (let [p (str path)]
    (if (fs/absolute? p)
      p
      (str (fs/normalize (fs/path (cwd) p))))))

(defn title-path-arg
  "First non-blank string among the :path / :file_path arg spellings, or
   nil. Nil-safe over partial streaming args (non-map, missing, blank)."
  [args]
  (when (map? args)
    (let [v (or (:path args) (:file_path args))]
      (when (and (string? v) (seq v)) v))))

(defn title-str-arg
  "Non-blank string value of arg K, or nil. Accepts keyword and string
   keys; nil-safe over partial streaming args."
  [args k]
  (when (map? args)
    (let [v (or (get args k) (get args (name k)))]
      (when (and (string? v) (seq v)) v))))

;; ─── Shell command display ────────────────────────────────────────────────

(def ^:private cd-prefix
  "A leading `cd DIR &&` — the working-directory hop a shell call often
   opens with. DIR is double-quoted, single-quoted or bare."
  #"\A\s*cd\s+(?:--\s+)?(?:\"([^\"]*)\"|'([^']*)'|(\S+))\s*&&\s*")

(defn- display-dir
  "DIR resolved against BASE and normalized for comparison: `~` expanded,
   a relative DIR taken from BASE, and lower-cased on Windows (paths are
   case-insensitive there)."
  [dir base]
  (let [p (fs/expand-home (str dir))
        abs (if (fs/absolute? p) p (fs/path base p))
        s (str (fs/normalize abs))]
    (if (fs/windows?) (str/lower-case s) s)))

(defn strip-cwd-cd-prefix
  "COMMAND without its leading `cd DIR &&` when DIR points at CWD — the
   shell already starts in the session's working directory, so the hop is
   noise in a one-line display. Quoted, relative and `~`-rooted DIRs
   resolve; a cd to any other directory, a command left blank, a missing
   CWD, or a separator other than `&&` leaves COMMAND untouched. A
   non-string COMMAND passes through."
  [command cwd]
  (if-not (and (string? command) (string? cwd) (seq cwd))
    command
    (let [[match double-quoted single-quoted bare] (re-find cd-prefix command)
          dir (or double-quoted single-quoted bare)
          rest-command (when match (str/triml (subs command (count match))))]
      (if (and (seq rest-command)
               (= (display-dir dir cwd) (display-dir cwd cwd)))
        rest-command
        command))))

;; ─── One-line call names ──────────────────────────────────────────────────

(defn title-body
  "The plain-text body of a tool definition's :title for ARGS, collapsed to
   one visual line (newline runs become one space, tabs expand — one-line
   displays count columns, so a multiline shell command would otherwise wrap
   them in two). Nil when TITLE-FN is not a fn, throws, or returns blank; never
   throws itself, because a broken title must not take a transcript line or a
   dialog down."
  [title-fn args]
  (when (fn? title-fn)
    (let [t (try (title-fn args) (catch Throwable _ nil))]
      (when (and (string? t) (seq (str/trim t)))
        (-> t
            (str/replace #"[ \t]*[\r\n]+[ \t\r\n]*" " ")
            (str/replace "\t" "   "))))))

(defn string-entry-name
  "The display name of a `strings` key: JSON decoding keywordizes object
   keys, so a keyword loses the one colon its original name did not have."
  [name]
  (if (keyword? name) (subs (str name) 1) (str name)))

(defn strings-entries
  "ARGS' `strings` map — the named dense text eval and codemode read with
   ($ \"name\") — as a vector of {:name display-name :text value} sorted by
   displayed name. Keywordized JSON keys are restored and non-string values
   skipped. Nil when ARGS carries no well-formed entries."
  [args]
  (let [strings (when (map? args) (or (:strings args) (get args "strings")))]
    (when (and (map? strings) (seq strings))
      (let [entries (for [[name value] (sort-by (comp string-entry-name key) strings)
                          :when (string? value)]
                      {:name (string-entry-name name) :text value})]
        (when (seq entries) (vec entries))))))

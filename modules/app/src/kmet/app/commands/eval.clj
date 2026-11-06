(ns kmet.app.commands.eval
  "The `/eval` slash command: the human-facing view of the eval library.

   Verbs: `list [pattern]`, `search <text>`, `describe <name>`,
   `drop <name>…`, `move <name> global|cwd`, `sources` and `help`; entries
   show their cwd/global scope and whether an entry is shadowed. An eval
   source may contribute a verb through
   the service's `:source-commands` map; built-in names are reserved. The command is
   registered with the engine-portable builtins, independent of the
   `:tool/eval` row."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.eval.format :as eval-format]
            [kmet.app.session.runtime :as runtime]
            [kmet.srce.core :as srce]))

(defn- out
  "Call a shared command-UI capability; an absent capability is inert."
  [ui capability & args]
  (when-let [f (get ui capability)]
    (apply f args)))

(defn- service [engine]
  (some-> (runtime/agent-ctx engine) (srce/get :eval)))

(defn- session [engine]
  (runtime/session engine))

(defn- cwd [engine]
  (or (runtime/cwd engine) "."))

(defn- split-verb
  "`\"search defn\"` → `[\"search\" \"defn\"]`; no args means `list`."
  [args]
  (let [args (str/trim (or args ""))]
    (if (str/blank? args)
      ["list" ""]
      (let [i (str/index-of args " ")]
        (if i
          [(subs args 0 i) (str/trim (subs args (inc i)))]
          [args ""])))))

(defn- kind-name [kind]
  (name (or kind :def)))

(defn- entry-path [cwd file]
  (if file (eval-files/display-path cwd file) "(no file)"))

(defn- scope-tag [{:keys [scope active]}]
  (str "(" (name (or scope :global))
       (when (false? active) ", shadowed") ")"))

(defn- entry-line
  "One definition listing line: name, kind, arglists, scope and the first
   docstring line. The file is omitted — the scope names its root."
  [{:keys [name kind arglists doc] :as entry}]
  (let [arg (eval-format/arglists-text arglists)
        doc-line (eval-format/doc-line doc)]
    (str name "  " (kind-name kind) (or arg "")
         "  " (scope-tag entry)
         (when (and (nil? doc-line) (nil? arg)) "  (no docstring)")
         (when doc-line (str "  — " doc-line)))))

(defn- indented
  "TEXT with every line indented four spaces."
  [text]
  (str/join "\n" (map #(str "    " %) (str/split-lines (str text)))))

(defn- describe-text
  "The human view of one `(eval/describe)` entry: its interface, file, full
   docstring and source."
  [cwd {:keys [name kind doc arglists file scope source]}]
  (let [arg (eval-format/arglists-text arglists)
        scope (if (some? scope)
                (str "(" (clojure.core/name scope) ")")
                "(source)")
        source (if (str/blank? (str source)) "(not recorded)" source)]
    (str name "  " (kind-name kind) (or arg "") "  " scope
         "\n  file: " (entry-path cwd file)
         "\n  doc: " (or doc "(no docstring)")
         "\n  source:\n" (indented source))))

(defn- name-match? [{:keys [name ns]} p]
  (or (str/includes? (str/lower-case (str name)) p)
      (str/includes? (str/lower-case (str ns)) p)))

(defn- filter-entries [entries pattern]
  (if (str/blank? pattern)
    entries
    (let [p (str/lower-case pattern)]
      (filter #(name-match? % p) entries))))

(defn- failure-note [failures]
  (when-let [n (seq failures)]
    (let [n (count n)]
      (str n " file" (when (not= 1 n) "s") " failed to load"))))

(defn- list-text [entries failures]
  (let [lines (map #(str "* " (entry-line %)) entries)
        n (count lines)
        note (failure-note failures)]
    (if (zero? n)
      (str "No eval definitions." (when note (str " (" note ")")))
      (str/join "\n" (concat lines
                             [(str n " definition" (when (not= 1 n) "s"))]
                             (when note [note]))))))

(defn- source-line [file p]
  (try
    (some (fn [line]
            (when (str/includes? (str/lower-case line) p)
              (str/trim line)))
          (str/split-lines (slurp file)))
    (catch Throwable _ nil)))

(defn- search-line [cwd p {:keys [name file] :as entry}]
  (let [line (str name "  " (scope-tag entry) "  " (entry-path cwd file))
        hit (when (and file (not (name-match? entry p)))
              (source-line file p))]
    (if hit
      (str line "\n    " hit)
      line)))

(defn- search-text [entries cwd pattern]
  (if (str/blank? pattern)
    "Usage: /eval search <text>"
    (let [p (str/lower-case pattern)
          matches (filter (fn [{:keys [file] :as entry}]
                            (or (name-match? entry p)
                                (some? (when file (source-line file p)))))
                          entries)]
      (if (seq matches)
        (str/join "\n" (map #(search-line cwd p %) matches))
        (str "No eval definitions match " (pr-str pattern) ".")))))

(defn- names-arg
  "Parse the names after `drop`: whitespace-separated, or EDN strings when a
   name contains spaces (a `defmethod` address, e.g. \"user/area :rect\")."
  [arg]
  (let [arg (str/trim arg)]
    (if (str/blank? arg)
      []
      (try
        (mapv str (edn/read-string (str "[" arg "]")))
        (catch Throwable _
          (remove str/blank? (str/split arg #"\s+")))))))

(defn- drop! [svc sess names cwd]
  (try
    ((:drop! svc) {:session sess :drop (vec names) :cwd cwd})
    (catch Exception e
      {:is-error true :content (or (ex-message e) (str e))})))

(defn- move-args
  "Parse `<name> global|cwd`, allowing a quoted defmethod address."
  [arg]
  (let [arg (str/trim arg)
        i (str/last-index-of arg " ")]
    (when (and i (pos? i))
      (let [names (names-arg (str/trim (subs arg 0 i)))]
        (when (= 1 (count names))
          [(first names) (str/trim (subs arg (inc i)))])))))

(defn- failure-line [failure]
  (str "  failure: " (or (:file failure) (:name failure) (:source failure)
                         (:root failure) "?")
       (when-let [scope (:scope failure)] (str " (" (name scope) ")"))
       ": " (or (:error failure) "unknown error")))

(defn- sources-text [svc cwd sess]
  (let [sources ((:sources svc) {:cwd cwd})
        entries (:entries ((:index svc) {:cwd cwd :session sess}))
        failures ((:failures svc))
        n (count entries)]
    (str/join "\n"
              (concat
               (if (seq sources)
                 (map eval-format/source-line sources)
                 ["No eval sources."])
               [(str n " definition" (when (not= 1 n) "s") " loaded")]
               (map entry-line entries)
               (map failure-line failures)))))

(defn- source-commands [svc cwd]
  (if-let [f (:source-commands svc)] (f {:cwd cwd}) {}))

(defn- display-verb [verb]
  (if (namespace verb)
    (str (namespace verb) "/" (name verb))
    (name verb)))

(defn- help-text [svc cwd]
  (let [verbs ["/eval list [pattern]     List library definitions"
               "/eval search <text>     Search names and source"
               "/eval describe <name>   Describe one definition or source verb"
               "/eval drop <name>…      Delete actual files and unbind the live names"
               "/eval drop names sharing a file together; a partial file is refused"
               "/eval move <name> global|cwd  Move a definition to the other scope"
               "/eval sources           List eval sources and loaded definitions"
               "/eval help              This help"]
        contributed (map (fn [[verb {:keys [description]}]]
                           (str "/eval " (display-verb verb) "  " (or description "")))
                         (sort-by key (source-commands svc cwd)))]
    (str/join "\n" (concat verbs contributed))))

(defn handle
  "The `/eval` handler: `ctx` is the command context (`:runtime` engine and
   `:ui`), `args` the text after `/eval`."
  [{:keys [ui] engine :runtime} args]
  (let [svc (service engine)
        sess (session engine)
        cwd (cwd engine)
        [verb arg] (split-verb args)]
    (cond
      (nil? svc)
      (out ui :warning "The eval service is not available.")

      (= verb "list")
      (let [{:keys [entries failures]} ((:index svc) {:cwd cwd :session sess})]
        (out ui :info "Eval" (list-text (filter-entries entries arg) failures)))

      (= verb "search")
      (out ui :info "Eval" (search-text (:entries ((:index svc) {:cwd cwd :session sess}))
                                        cwd arg))

      (= verb "describe")
      (if (str/blank? arg)
        (out ui :warning "Usage: /eval describe <name>")
        (if-let [describe (:describe svc)]
          (try
            (out ui :info "Eval"
                 (describe-text cwd (describe {:session sess :cwd cwd :name arg})))
            (catch Exception e
              (out ui :warning (or (ex-message e) (str e)))))
          (out ui :warning "This eval service cannot describe definitions.")))

      (= verb "drop")
      (let [names (names-arg arg)]
        (cond
          (empty? names)
          (out ui :warning "Usage: /eval drop <name>…")

          (nil? sess)
          (out ui :warning "No active session.")

          :else
          (let [{:keys [is-error content]} (drop! svc sess names cwd)]
            (if is-error
              (out ui :warning content)
              (out ui :info "Eval" content)))))

      (= verb "move")
      (if-let [[name scope] (move-args arg)]
        (try
          (let [result ((:move! svc) {:session sess :name name :scope scope
                                      :cwd cwd})]
            (out ui :info "Eval" (:content result)))
          (catch Exception e
            (out ui :warning (or (ex-message e) (str e)))))
        (out ui :warning "Usage: /eval move <name> global|cwd"))

      (= verb "sources")
      (out ui :info "Eval" (sources-text svc cwd sess))

      (contains? #{"help" "--help" "-h"} verb)
      (out ui :info "Eval" (help-text svc cwd))

      :else
      (if-let [cmd (get (source-commands svc cwd) (keyword verb))]
        (try
          (let [output ((:run cmd) {:args arg :session sess :cwd cwd :service svc})]
            (when-not (str/blank? (str output))
              (out ui :info "Eval" (str output))))
          (catch Throwable t
            (out ui :warning (str "eval verb failed: "
                                  (or (ex-message t) (str t))))))
        (out ui :warning (str "Unknown verb: " verb ". Try /eval help."))))))

(def command
  "The `/eval` command record, registered with the engine-portable builtins."
  {:name "eval"
   :description "List, search, describe, move or drop eval library definitions"
   :argument-hint "<list|search|describe|move|drop|sources|help> [args]"
   :handler handle})

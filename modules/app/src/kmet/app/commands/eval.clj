(ns kmet.app.commands.eval
  "The `/eval` slash command: the human-facing view of the eval library.

   Verbs: `list [pattern]`, `search <text>`, `drop <name>…`, `sources` and
   `help`. An eval source may contribute a verb through the service's
   `:source-commands` map; built-in names are reserved. The command is
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

(defn- entry-line [cwd {:keys [name kind file arglists doc]}]
  (let [path (entry-path cwd file)
        arg (eval-format/arglists-text arglists)
        doc-line (eval-format/doc-line doc)]
    (str name "  " (kind-name kind) (or arg "")
         (if (and (nil? doc-line) (nil? arg))
           (str "  (no docstring; see " path ")")
           (str "  " path))
         (when doc-line (str "  — " doc-line)))))

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

(defn- list-text [entries failures cwd]
  (let [lines (map #(entry-line cwd %) entries)
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
  (let [line (str name "  " (entry-path cwd file))
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

(defn- source-id [s]
  (let [id (if (map? s) (:id s) s)]
    (if (keyword? id) (name id) (str id))))

(defn- source-report-line [s]
  (let [id (source-id s)
        error (when (map? s) (:error s))
        doc (when (map? s) (:doc s))
        verbs (when (map? s) (:verbs s))]
    (cond
      (seq error) (str "- " id ": " error)
      :else (str "- " id
                 (when (seq doc) (str ": " doc))
                 (when (seq verbs)
                   (str "\n" (str/join "\n"
                                       (map eval-format/verb-line verbs))))))))

(defn- failure-line [failure]
  (str "  failure: " (or (:file failure) (:name failure) (:source failure) "?")
       ": " (or (:error failure) "unknown error")))

(defn- sources-text [svc cwd]
  (let [sources ((:sources svc) {:cwd cwd})
        entries (:entries ((:index svc)))
        failures ((:failures svc))
        n (count entries)]
    (str/join "\n"
              (concat
               (if (seq sources)
                 (map source-report-line sources)
                 ["No eval sources."])
               [(str n " definition" (when (not= 1 n) "s") " loaded")]
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
               "/eval drop <name>…      Delete files and unbind the live names"
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
      (out ui :warning "Eval" "The eval service is not available.")

      (= verb "list")
      (let [{:keys [entries failures]} ((:index svc))]
        (out ui :info "Eval" (list-text (filter-entries entries arg) failures cwd)))

      (= verb "search")
      (out ui :info "Eval" (search-text (:entries ((:index svc))) cwd arg))

      (= verb "drop")
      (let [names (names-arg arg)]
        (cond
          (empty? names)
          (out ui :warning "Eval" "Usage: /eval drop <name>…")

          (nil? sess)
          (out ui :warning "Eval" "No active session.")

          :else
          (let [result (drop! svc sess names cwd)]
            (out ui (if (:is-error result) :warning :info)
                 "Eval" (:content result)))))

      (= verb "sources")
      (out ui :info "Eval" (sources-text svc cwd))

      (contains? #{"help" "--help" "-h"} verb)
      (out ui :info "Eval" (help-text svc cwd))

      :else
      (if-let [cmd (get (source-commands svc cwd) (keyword verb))]
        (try
          (let [output ((:run cmd) {:args arg :session sess :cwd cwd :service svc})]
            (when-not (str/blank? (str output))
              (out ui :info "Eval" (str output))))
          (catch Throwable t
            (out ui :warning "Eval" (str "eval verb failed: "
                                         (or (ex-message t) (str t))))))
        (out ui :warning "Eval" (str "Unknown verb: " verb ". Try /eval help."))))))

(def command
  "The `/eval` command record, registered with the engine-portable builtins."
  {:name "eval"
   :description "List, search or drop eval library definitions"
   :argument-hint "<list|search|drop|sources|help> [args]"
   :handler handle})

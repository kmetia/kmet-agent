(ns kmet.app.commands.eval
  "The `/eval` slash command: the human-facing view of the eval library and
   the session's live-only definitions.

   Verbs: `list [pattern]`, `index <text>`, `describe <name>`, `drop <name>…`,
   `move <name> global|cwd`, `reload`, `sources` and `help [topic|name]`.
   `list`, `describe`, `sources`, `drop` and `move` work from the disk and
   source layer even when the session has no live environment; their
   output then starts with an `Eval inactive` notice. `help` and `index` need
   the live sandbox and report the inactive state instead of falling back to
   the disk. `reload` rebuilds the session's sandbox from both library roots.
   Entries show their cwd/global scope (or `live`) and whether an entry is
   shadowed.
   Output is Markdown — headings, bullets and fenced code — rendered by the
   TUI's custom-message component.
   `/eval <name>` is a `describe` shorthand when the name is defined and is
   not a known verb. An eval source may contribute a verb through the
   service's `:source-commands` map; built-in names are reserved. The
   `:tool/eval` plugin registers this command and the eval tool together, so
   the row is the one switch for both."
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
  "`\"index defn\"` → `[\"index\" \"defn\"]`; no args means `list`."
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

(defn- inline-code
  "S as a Markdown code span. The renderer reads single-backtick spans only,
   so an internal backtick is shown as an apostrophe."
  [s]
  (str "`" (str/replace (str s) "`" "'") "`"))

(defn- code-block
  "S as a fenced Markdown code block tagged LANG. The renderer reads
   three-backtick fences, so a literal triple-backtick run in S is split with
   a zero-width space."
  [lang s]
  (str "```" lang "\n" (str/replace (str s) "```" "``\u200b`") "\n```"))

(defn- entry-line
  "One Markdown definition list item: name, kind, arglists, scope and the
   first docstring line. The file is omitted — the scope names its root."
  [{:keys [name kind arglists doc] :as entry}]
  (let [arg (some-> (eval-format/arglists-text arglists) str/trim not-empty)
        doc-line (eval-format/doc-line doc)]
    (str "- " (inline-code name)
         " · " (inline-code (kind-name kind))
         (when arg (str " " (inline-code arg)))
         " · " (inline-code (scope-tag entry))
         " — " (or doc-line "*no docstring*"))))

(defn- describe-text
  "The Markdown view of one `(eval/describe)` entry: its interface, file,
   source, and the docstring only when the entry has no source."
  [cwd {:keys [name kind doc arglists file scope source]}]
  (let [arg (some-> (eval-format/arglists-text arglists) str/trim not-empty)
        has-source? (not (str/blank? (str source)))
        scope-label (cond
                      (some? scope) (str "(" (clojure.core/name scope) ")")
                      has-source? "(source)"
                      :else "(built-in)")
        doc (when (and (seq (str doc)) (not has-source?)) doc)]
    (str/join
     "\n\n"
     (remove nil?
             [(str "### " (inline-code name))
              (str (inline-code (kind-name kind))
                   (when arg (str " " (inline-code arg)))
                   " · " (inline-code scope-label))
              (str "**File:** "
                   (if file (inline-code (entry-path cwd file)) "*none*"))
              doc
              (when has-source? (code-block "clojure" source))]))))

(defn- describe-outcome
  "Resolve NAME for `/eval`: `{:text ...}` when it is defined, `{:not-found
   message}` when it is unknown, `{:error message}` for another failure, and
   `{:unavailable true}` when the service cannot describe."
  [svc sess cwd name]
  (if-let [describe (:describe svc)]
    (try
      {:text (describe-text cwd (describe {:session sess :cwd cwd :name name}))}
      (catch Exception e
        (let [message (or (ex-message e) (str e))]
          (if (= :eval/bad-request (:type (ex-data e)))
            {:not-found message}
            {:error message}))))
    {:unavailable true}))

(defn- name-match? [{:keys [name ns]} p]
  (or (str/includes? (str/lower-case (str name)) p)
      (str/includes? (str/lower-case (str ns)) p)))

(defn- filter-entries [entries pattern]
  (if (str/blank? pattern)
    entries
    (let [p (str/lower-case pattern)]
      (filter #(name-match? % p) entries))))

(defn- list-order
  "The user-facing order: saved global definitions, saved cwd definitions,
   then the session's live-only definitions."
  [entries live]
  (concat (sort-by (juxt #(if (= :global (:scope %)) 0 1) :name) entries)
          (sort-by :name (map #(assoc % :scope :live) live))))

(defn- failure-line
  "One Markdown failure bullet: the file, scope and error."
  [failure]
  (str "- " (inline-code (or (:file failure) (:name failure) (:source failure)
                             (:root failure) "?"))
       (when-let [scope (:scope failure)]
         (str " · " (inline-code (str "(" (name scope) ")"))))
       " — " (or (:error failure) "unknown error")))

(defn- list-text [entries failures]
  (let [entries (vec entries)
        n (count entries)]
    (str/join
     "\n\n"
     (concat
      ["### Eval library"
       (if (pos? n)
         (str/join "\n" (map entry-line entries))
         "*No eval definitions.*")]
      (when (pos? n)
        [(str "**" n " definition" (when (not= 1 n) "s") "**")])
      (when (seq failures)
        ["### Failures"
         (str/join "\n" (map failure-line failures))])))))

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

(defn- sources-text [svc cwd sess]
  (let [sources ((:sources svc) {:cwd cwd})
        entries (:entries ((:index svc) {:cwd cwd :session sess}))
        failures ((:failures svc))
        n (count entries)]
    (str/join
     "\n\n"
     (concat
      ["### Eval sources"
       (if (seq sources)
         (str/join "\n" (map eval-format/source-line sources))
         "*No registered eval sources.*")
       "### Eval library"
       (if (pos? n)
         (str/join "\n" (map entry-line entries))
         "*No eval definitions.*")]
      (when (pos? n)
        [(str "**" n " saved definition" (when (not= 1 n) "s") "**")])
      (when (seq failures)
        ["### Failures"
         (str/join "\n" (map failure-line failures))])))))

(defn- source-commands [svc cwd]
  (if-let [f (:source-commands svc)] (f {:cwd cwd}) {}))

(defn- display-verb [verb]
  (if (namespace verb)
    (str (namespace verb) "/" (name verb))
    (name verb)))

(defn- inactive-text [text]
  (str "**Eval inactive** — no live environment for this session. "
       "Saved definitions are read from disk; run an eval or "
       "`/eval reload` to build the sandbox.\n\n"
       text))

(defn- active? [svc sess]
  (if-let [active-fn (:active? svc)]
    (boolean (active-fn {:session sess}))
    false))

(defn- help-arg
  "Normalize a help target: `:core`, `\"core\"` and `core` all name the topic."
  [arg]
  (let [arg (str/trim arg)]
    (cond
      (str/starts-with? arg ":") (subs arg 1)
      (and (str/starts-with? arg "\"") (str/ends-with? arg "\""))
      (subs arg 1 (dec (count arg)))
      :else arg)))

(defn- command-lines [verbs]
  (str/join "\n" (map (fn [[cmd desc]]
                        (str "- " (inline-code cmd) " — " desc))
                      verbs)))

(defn- command-help-text [svc cwd]
  (let [verbs [["/eval list [pattern]" "List global, cwd, then live definitions"]
               ["/eval index <text>" "Search the live sandbox by name, docstring or source"]
               ["/eval describe <name>" "Describe one definition or source verb"]
               ["/eval <name>" "Describe a definition (describe shorthand)"]
               ["/eval drop <name>…" "Delete actual files and unbind the live names"]
               ["/eval move <name> global|cwd" "Move a definition to the other scope"]
               ["/eval reload" "Rebuild the sandbox from the library files"]
               ["/eval sources" "List eval sources and saved definitions"]
               ["/eval help [topic|name]" "Index, sandbox topic, or one definition"]]
        contributed (map (fn [[verb {:keys [description]}]]
                           [(str "/eval " (display-verb verb))
                            (or description "")])
                         (sort-by key (source-commands svc cwd)))]
    (str/join
     "\n\n"
     (concat
      ["### /eval"
       (command-lines verbs)
       "> A file shared by several definitions must be dropped together; a partial file is refused."]
      (when (seq contributed)
        ["**Source verbs**"
         (command-lines contributed)])))))

(defn- status-text [live? text]
  (if live? text (inactive-text text)))

(defn- list-command
  [{:keys [svc sess cwd live? arg ui]}]
  (let [{:keys [entries failures]} ((:index svc) {:cwd cwd :session sess})
        live-defs (if-let [live-fn (:live svc)]
                    (live-fn {:cwd cwd :session sess})
                    [])]
    (out ui :info "Eval"
         (status-text live?
                      (list-text (filter-entries (list-order entries live-defs) arg)
                                 failures)))))

(defn- help-command
  [{:keys [svc sess cwd live? arg ui]}]
  (let [help-fn (:help svc)]
    (cond
      (not (str/blank? arg))
      (cond
        (not live?)
        (out ui :warning
             (str "Eval inactive — sandbox help needs a live environment. "
                  "Run an eval or `/eval reload` first."))

        help-fn
        (try
          (out ui :info "Eval" (help-fn {:session sess
                                         :what (help-arg arg)}))
          (catch Exception e
            (out ui :warning (or (ex-message e) (str e)))))

        :else
        (out ui :warning "This eval service cannot show sandbox help."))

      (not live?)
      (out ui :info "Eval" (inactive-text (command-help-text svc cwd)))

      help-fn
      (try
        (out ui :info "Eval" (str (command-help-text svc cwd)
                                  "\n\n### Sandbox help\n\n"
                                  (help-fn {:session sess})))
        (catch Exception e
          (out ui :warning (or (ex-message e) (str e)))))

      :else
      (out ui :warning "This eval service cannot show sandbox help."))))

(defn- index-command
  [{:keys [svc sess live? arg ui]}]
  (cond
    (str/blank? arg)
    (out ui :warning "Usage: /eval index <text>")

    (not live?)
    (out ui :warning
         (str "Eval inactive — `/eval index` searches the live environment. "
              "Run an eval or `/eval reload` first."))

    (nil? (:index-text svc))
    (out ui :warning "This eval service cannot index the sandbox.")

    :else
    (try
      (out ui :info "Eval" ((:index-text svc) {:session sess :text arg}))
      (catch Exception e
        (out ui :warning (or (ex-message e) (str e)))))))

(defn- describe-command
  [{:keys [svc sess cwd live? arg ui]}]
  (if (str/blank? arg)
    (out ui :warning "Usage: /eval describe <name>")
    (let [{:keys [text not-found error unavailable]}
          (describe-outcome svc sess cwd arg)]
      (cond
        text (out ui :info "Eval" (status-text live? text))
        unavailable (out ui :warning
                         "This eval service cannot describe definitions.")
        error (out ui :warning error)
        not-found (out ui :warning not-found)))))

(defn- drop-command
  [{:keys [svc sess cwd live? arg ui]}]
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
          (out ui :info "Eval" (status-text live? content)))))))

(defn- move-command
  [{:keys [svc sess cwd live? arg ui]}]
  (if-let [[name scope] (move-args arg)]
    (try
      (let [result ((:move! svc) {:session sess :name name :scope scope
                                  :cwd cwd})]
        (out ui :info "Eval" (status-text live? (:content result))))
      (catch Exception e
        (out ui :warning (or (ex-message e) (str e)))))
    (out ui :warning "Usage: /eval move <name> global|cwd")))

(defn- reload-command
  [{:keys [svc sess cwd ui]}]
  (if-let [reload (:reload! svc)]
    (let [{:keys [is-error content]}
          (try
            (reload {:session sess :cwd cwd})
            (catch Exception e
              {:is-error true :content (or (ex-message e) (str e))}))]
      (if is-error
        (out ui :warning content)
        (out ui :info "Eval" content)))
    (out ui :warning "This eval service cannot reload.")))

(defn- sources-command
  [{:keys [svc sess cwd live? ui]}]
  (out ui :info "Eval" (status-text live? (sources-text svc cwd sess))))

(defn- fallback-command
  [{:keys [svc sess cwd live? verb arg ui]}]
  (if-let [cmd (get (source-commands svc cwd) (keyword verb))]
    (try
      (let [output ((:run cmd) {:args arg :session sess :cwd cwd :service svc})]
        (when-not (str/blank? (str output))
          (out ui :info "Eval" (str output))))
      (catch Throwable t
        (out ui :warning (str "eval verb failed: "
                              (or (ex-message t) (str t))))))
    (let [name (str/trim (str verb (when (seq arg) (str " " arg))))
          {:keys [text error]} (describe-outcome svc sess cwd name)]
      (cond
        text (out ui :info "Eval" (status-text live? text))
        error (out ui :warning error)
        :else (out ui :warning
                   (str "Unknown verb: " verb ". Try /eval help."))))))

(def ^:private command-handlers
  {"list" list-command
   "index" index-command
   "describe" describe-command
   "drop" drop-command
   "move" move-command
   "reload" reload-command
   "sources" sources-command
   "help" help-command
   "--help" help-command
   "-h" help-command})

(defn handle
  "The `/eval` handler: `ctx` is the command context (`:runtime` engine and
   `:ui`), `args` the text after `/eval`."
  [{:keys [ui] engine :runtime} args]
  (let [svc (service engine)
        sess (session engine)
        [verb arg] (split-verb args)
        ctx {:svc svc
             :sess sess
             :cwd (cwd engine)
             :live? (active? svc sess)
             :verb verb
             :arg arg
             :ui ui}]
    (if (nil? svc)
      (out ui :warning "The eval service is not available.")
      (if-let [handler (get command-handlers verb)]
        (handler ctx)
        (fallback-command ctx)))))

(def command
  "The `/eval` command record, mounted by the `:tool/eval` plugin with the
   eval tool."
  {:name "eval"
   :description "List, index, describe, move, drop or reload eval definitions"
   :argument-hint "<list|index|describe|move|drop|reload|sources|help> [args], or <name>"
   :handler handle})

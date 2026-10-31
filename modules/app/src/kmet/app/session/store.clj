(ns kmet.app.session.store
  "Durable EDNL session storage: the Session record, entry values, the
   line reader/parser with torn-tail repair, atomic rewrite, and the append
   journal callers mutate a session through. Each entry is an EDN map on one
   line: {:id str :parent-id str-or-nil :role keyword ...}
   Port of @earendil-works/pi-agent session storage."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.app.session.schema :as session-schema]
            [kmet.libs.fs :as kfs])
  (:import [java.io InputStreamReader]))

;; ─── Diagnostics ───────────────────────────────────────────────────────────
;; The load path reports what it *repairs* (a torn tail, a malformed line)
;; unconditionally. The strict pass is separate: with this on, an entry that
;; parses but fails the schema is reported and skipped, and a header that fails
;; its schema is reported — kmet1's `--debug` behaviour, preserved exactly.
;; Off, the same entry loads: a session file that parses is trusted, and
;; dropping data is opt-in. An atom rather than a dynamic var: a session loads
;; on whichever thread asked for it, and the switch is process-wide like debug
;; logging itself. The host owns the switch (`kmet.app.debug/enable!`), which
;; is why this layer does not require the logger.

(defonce ^:private report-wrong-shape (atom false))

(defn report-wrong-shapes!
  "Turn the wrong-shape entry/header diagnostic on or off. Returns nil."
  [on?]
  (reset! report-wrong-shape (boolean on?))
  nil)

(defn report-wrong-shapes?
  "Is the strict load pass on? The host's debug switch asks nothing else."
  []
  @report-wrong-shape)

;; ─── Session record ─────────────────────────────────────────────────────────
;; :lock (a ReentrantLock) serializes all file mutations (pi:
;; JsonlSessionStorage.enqueue — a promise chain serializes
;; appends/rewrites). Without it, two concurrent appends (e.g. a ! bash
;; result landing on its future thread while a submitted message appends on
;; the agent thread) both read the same leaf and write sibling entries — the
;; branch walk then orphans one of them, so a restored session silently
;; loses messages.

(defrecord Session [file id header entries leaf-id lock])

(defmacro with-session-lock
  "Run BODY under the session's mutation lock (pi: storage.enqueue).
   Reentrant, so nested session operations inside the body are safe.
   Internal — the session modules use it; the façade does not export it."
  [session & body]
  `(let [lock# (:lock ~session)]
     (.lock lock#)
     (try ~@body
          (finally (.unlock lock#)))))

(defn generate-id
  "Generate a unique entry id: hex timestamp + 8-hex random, collision
   checked against the session's existing entry ids (pi: generateId —
   collision-checked against the index). The timestamp prefix keeps ids
   time-ordered across processes; the 32-bit random component makes
   same-ms cross-process collisions negligible (G12 — the 16-bit rand
   allowed 1/65536 same-ms collisions).
   Internal — called by session.tree."
  [entries]
  (let [existing (into #{} (map :id) entries)]
    (loop []
      (let [id (str (format "%x" (System/currentTimeMillis))
                    "-" (format "%04x%04x" (rand-int 0x10000) (rand-int 0x10000)))]
        (if (contains? existing id)
          (recur)
          id)))))

(defn timestamp
  "Now, as an Instant, for a new entry's :timestamp.
   Internal — called by session.tree."
  []
  (java.time.Instant/now))

(defn- build-entry
  "Pure: assign id (collision-checked against entries), parent-id (current
   leaf), timestamp."
  [entries leaf-id entry]
  (assoc entry
         :id (generate-id entries)
         :parent-id leaf-id
         :timestamp (str (timestamp))))

(defn- publish-file!
  "Atomically write the session file: the header line followed by the
   entries, via temp file + rename so a crash mid-write can't corrupt the
   file (pi: temp-file publication; kmet.libs.fs/publish! creates the
   session dir). Callers must hold the session lock."
  [session entries]
  (kfs/publish! (:file session)
                (apply str (map prn-str (cons (:header session) entries)))))

(defn- persist-if-needed!
  "Lazy file creation (pi: _persist — G4): write the file the first time the
   branch contains an assistant message; before that, entries accumulate in
   memory only and no file exists on disk. No-op once the file exists.
   Callers must hold the session lock."
  [session]
  (when (and (not (fs/exists? (:file session)))
             (some #(= :assistant (:role %)) @(:entries session)))
    (publish-file! session @(:entries session))))

(defn write-entries!
  "Atomically replace the session file's contents and in-memory state (pi:
   temp-file publication). Rewrites whenever the file exists; a
   not-yet-persisted session (lazy creation — G4) is only written once the
   branch has an assistant message. Entries are taken as given — the append
   path builds them here, while forking copies the source entries verbatim
   (pi: createBranchedSession/forkFrom), so ids, parents and timestamps are
   whatever the caller assembled. Callers must hold the session lock.
   Internal — called by session.tree (fork/fork-from)."
  [session entries]
  (let [entries (vec entries)]
    (when (or (fs/exists? (:file session))
              (some #(= :assistant (:role %)) entries))
      (publish-file! session entries))
    (reset! (:entries session) entries)
    (reset! (:leaf-id session) (some-> entries last :id))))

;; ─── Layout (pi: cwd-encoded session dirs) ───────────────────────────────

(defn encode-cwd
  "Encode a cwd path into a safe directory name: `--` + the cwd with its
   leading slash stripped and / \\ : replaced by - + `--` (pi:
   getDefaultSessionDirPath — sessions/<--cwd-->/)."
  [cwd]
  (str "--" (-> (str cwd)
                (str/replace #"^[/\\]" "")
                (str/replace #"[/\\:]" "-"))
       "--"))

(defn session-dir-for-cwd
  "Session directory for a cwd under a base sessions dir: BASE/<--cwd-->/
   (pi: default layout sessions/<--cwd-->/). A blank BASE is refused: the
   encoded name would be a relative path, created under the process cwd
   instead of a sessions dir."
  [base-dir cwd]
  (when (str/blank? (str base-dir))
    (throw (ex-info "a session directory needs a base sessions dir"
                    {:type :kmet.app/no-session-dir :cwd (str cwd)})))
  (str (fs/path base-dir (encode-cwd cwd))))

;; ─── CRUD ───────────────────────────────────────────────────────────────────

(defn create-session
  "Create a new session record in DIR. No file is written until the first
   assistant message (pi: newSession + lazy _persist — G4); the record
   carries a :header {:type :session :version 1 :id :created-at :cwd
   :parent-session?} (G1) and the computed :file
   <timestamp>_<id>.ednl. opts: :cwd (defaults to the process cwd),
   :parent-session (source session file, for forks)."
  ([dir] (create-session dir nil))
  ([dir {:keys [cwd parent-session]}]
   (let [id (generate-id [])
         ts (str (timestamp))
         header (cond-> {:type :session
                         :version 1
                         :id id
                         :created-at ts
                         :cwd (or cwd (str (fs/cwd)))}
                  parent-session (assoc :parent-session parent-session))
         file (io/file dir (str (str/replace ts #"[:.]" "-") "_" id ".ednl"))]
     (fs/create-dirs dir)
     (map->Session {:file (str (fs/canonicalize file))
                    :id id
                    :header header
                    :entries (atom [])
                    :leaf-id (atom nil)
                    :lock (java.util.concurrent.locks.ReentrantLock.)}))))

;; ─── Loading (G13 — pi: loadEntriesFromFile / readSessionHeader) ──────────

(def ^:private read-buffer-size
  "Chunk size (chars) for streaming session file reads (pi:
   SESSION_READ_BUFFER_SIZE — 1 MB)."
  (* 1024 1024))

(defn- read-physical-lines
  "Read PATH's physical lines with READ-BUFFER-SIZE chunked, line-oriented
   reads (pi: loadEntriesFromFile), so the file is never materialized as a
   single string. The reader decodes UTF-8 incrementally, so a multi-byte
   character spanning a chunk boundary stays intact (pi's StringDecoder).
   Returns {:lines [..] :ends-with-newline? bool}; the final line is
   included even without a trailing newline."
  [path]
  (with-open [r (InputStreamReader. (io/input-stream path) "UTF-8")]
    (let [cbuf (char-array read-buffer-size)]
      (loop [pending "" lines []]
        (let [n (.read r cbuf)]
          (if (neg? n)
            {:lines (if (empty? pending) lines (conj lines pending))
             :ends-with-newline? (empty? pending)}
            (let [parts (str/split (str pending (String. cbuf 0 n))
                                   #"\n" -1)]
              (recur (last parts) (into lines (butlast parts))))))))))

(defn- parse-physical-line
  "Parse one physical line into a session entry. Blank lines return nil;
   malformed lines warn to stderr and return ::invalid (pi v3 skips
   malformed lines with a warning)."
  [path line]
  (let [trimmed (str/trim line)]
    (when (seq trimmed)
      (try (let [e (edn/read-string trimmed)]
             (if (and @report-wrong-shape (map? e)
                      (not (session-schema/validate-entry? e)))
               (do (binding [*out* *err*]
                     (println "Warning: Skipping wrong-shape entry in" path ":"
                              (pr-str (session-schema/explain-entry e))))
                   ::invalid)
               e))
           (catch Exception ex
             (binding [*out* *err*]
               (println "Warning: Skipping invalid entry in" path ":" (ex-message ex)))
             ::invalid)))))

(defn parse-silently
  "Parse one physical line into a session entry, nil for blank or malformed
   lines without warning (pi: buildSessionInfo skips malformed lines
   silently — only load-session reports them).
   Internal — called by session.listing."
  [line]
  (let [trimmed (str/trim line)]
    (when (seq trimmed)
      (try (edn/read-string trimmed)
           (catch Exception _ nil)))))

(defn reduce-physical-lines
  "Reduce F over the physical lines of PATH (chunked, line-oriented reads —
   pi: createReadStream + readline; UTF-8 sequences spanning chunk
   boundaries decode intact). F is (fn [acc line]); the final line is
   included even without a trailing newline. Streams aggregates without
   materializing the file (used by build-session-info, G15). When F returns
   (reduced nil), the read stops and nil is returned (early exit for
   headerless files — nil is never a valid accumulator).
   Internal — called by session.listing."
  [path f init]
  (with-open [r (InputStreamReader. (io/input-stream path) "UTF-8")]
    (let [cbuf (char-array read-buffer-size)]
      (loop [pending "" acc init]
        (let [n (.read r cbuf)]
          (if (neg? n)
            (if (seq pending)
              (let [acc (f acc pending)]
                (if (reduced? acc) @acc acc))
              acc)
            (let [parts (str/split (str pending (String. cbuf 0 n))
                                   #"\n" -1)
                  acc (reduce (fn [a line]
                                (let [a (f a line)]
                                  (if (reduced? a) (reduced @a) a)))
                              acc (butlast parts))]
              (if (nil? acc)
                nil
                (recur (last parts) acc)))))))))

(defn- parseable-line?
  "True when LINE is blank or parses as EDN — i.e. not a torn tail."
  [line]
  (let [trimmed (str/trim line)]
    (or (empty? trimmed)
        (try (edn/read-string trimmed) true
             (catch Exception _ false)))))

(defn- repair-torn-tail!
  "Drop a torn tail — a partial final line left by a crashed append — by
   atomically publishing the valid prefix (all physical lines except the
   last) via temp file + rename (pi v4 torn-tail repair: the tail is an
   unacknowledged partial append; malformed middle lines are preserved).
   Returns the repaired lines. Runs during load, before any session exists."
  [file lines]
  (let [valid (subvec lines 0 (dec (count lines)))]
    (kfs/publish! file (str (str/join "\n" valid) "\n")))
  (subvec lines 0 (dec (count lines))))

(defn load-session
  "Load an existing session from file path. Returns Session record.
   A leading :session header line (G1) is parsed into :header and excluded
   from the entries; legacy files without a header load with :header nil
   and :id derived from the filename.

   Loading streams the file in 1 MB chunks (G13) and repairs two crash
   artifacts atomically (pi v4): a torn tail — a partial final line from a
   crashed append — is dropped by publishing the valid prefix via temp file
   + rename, and a missing trailing newline is appended so a future append
   can't glue onto the last line. Malformed non-tail lines keep the v3
   skip-with-warning behavior."
  [path]
  (let [file (io/file path)
        {:keys [lines ends-with-newline?]} (read-physical-lines (str file))
        torn? (and (seq lines)
                   (let [last-line (peek lines)]
                     (and (seq (str/trim last-line))
                          (not (parseable-line? last-line)))))
        lines (if torn?
                (do (binding [*out* *err*]
                      (println "Warning: Repairing torn session tail in" path))
                    (repair-torn-tail! file lines))
                lines)
        ;; a final line without a newline would glue the next append onto it
        ;; (pi v4: unterminated-tail repair)
        _ (when (and (not ends-with-newline?) (seq lines) (not torn?))
            (kfs/append-line! file "\n"))
        parsed (vec (keep (fn [line]
                            (let [e (parse-physical-line path line)]
                              (when-not (= ::invalid e) e)))
                          lines))
        header? (and (seq parsed) (= :session (:type (first parsed))))
        header (when header? (first parsed))
        _ (when (and @report-wrong-shape header?
                     (not (session-schema/validate-header? header)))
            (binding [*out* *err*]
              (println "Warning: Wrong-shape session header in" path ":"
                       (pr-str (session-schema/explain-header header)))))
        entries (if header? (subvec parsed 1) parsed)
        leaf-id (some-> entries last :id)]
    (map->Session {:file (str (fs/canonicalize file))
                   :id (or (:id header)
                           (str/replace (fs/file-name file) #"\.ednl$" ""))
                   :header header
                   :entries (atom entries)
                   :leaf-id (atom leaf-id)
                   :lock (java.util.concurrent.locks.ReentrantLock.)})))

;; ─── Tool result token attribution ──────────────────────────────────────────
;; Every tool-result entry carries an estimated :result-tokens (chars/4, the
;; compaction convention). The totals are DERIVED from the entries, so loaded
;; and forked sessions report without extra bookkeeping.

(defn- tool-result-chars
  "Character count of a tool entry's result content — a string, or a block
   vector of tool_result/text blocks."
  [entry]
  (let [content (:content entry)]
    (cond
      (string? content) (count content)
      (vector? content) (reduce + 0
                                (for [b content]
                                  (cond
                                    (string? b) (count b)
                                    (map? b) (count (str (or (:content b) (:text b) "")))
                                    :else 0)))
      :else 0)))

(defn estimate-result-tokens
  "Estimated tokens (chars/4) of a tool entry's result.
   Internal — used by append-entry and session.stats."
  [entry]
  (quot (+ (tool-result-chars entry) 3) 4))

(defn append-entry
  "Append an entry to the session atom, persisting it to the file (pi:
   storage.appendEntry is enqueued; _persist defers the file write until the
   first assistant message — lazy creation G4). Serialized per session so
   concurrent appends (bash-result future + agent loop) can't produce
   orphaned sibling entries.

   Tool-result entries get an estimated :result-tokens stamped onto them
   (see tool-usage; `/session` reports the per-tool totals)."
  [session entry]
  (with-session-lock session
    (let [entry (build-entry @(:entries session) @(:leaf-id session) entry)
          entry (if (and (= :tool (:role entry))
                         (not (contains? entry :result-tokens)))
                  (assoc entry :result-tokens (estimate-result-tokens entry))
                  entry)
          file (:file session)]
      (swap! (:entries session) conj entry)
      (reset! (:leaf-id session) (:id entry))
      (if (fs/exists? file)
        (kfs/append-line! file (prn-str entry))
        (persist-if-needed! session))
      entry)))

(defn replace-entries!
  "Atomically replace the session with a fresh linear branch built from raw
   entries: ids/parents/timestamps assigned in order (pi: the session file is
   rewritten to mirror a replaced context). Serialized and published via
   temp-file + rename so a crash mid-write can't corrupt the file."
  [session raw-entries]
  (with-session-lock session
    (write-entries! session
                    (loop [raw (seq raw-entries) leaf nil built []]
                      (if-let [e (first raw)]
                        (let [e (build-entry built leaf e)]
                          (recur (next raw) (:id e) (conj built e)))
                        built)))))

(defn get-branch
  "Get entries from root to the active leaf (or to FROM-ID when given)."
  ([session] (get-branch session @(:leaf-id session)))
  ([session from-id]
   (let [entries @(:entries session)]
     (if (empty? entries)
       []
       (let [index (reduce (fn [m e] (assoc m (:id e) e)) {} entries)
             path (volatile! [])]
         (loop [id from-id]
           (when id
             (when-let [e (get index id)]
               (vswap! path conj e)
               (recur (:parent-id e)))))
         (vec (reverse @path)))))))

(defn get-entry
  "Get a single entry by id (pi: getEntry), nil when not found."
  [session entry-id]
  (some #(when (= (:id %) entry-id) %) @(:entries session)))

(defn session-entry-text
  "Plain trimmed text of an entry's content blocks — the fork/tree editor
   restore (pi: extractUserMessageText), the first user message, the prompt
   history and the per-message activity times (pi: extractTextContent)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

;; ─── Branching (pi: SessionManager.branch / resetLeaf) ────────────────────

(defn branch!
  "Move the leaf pointer to an existing entry (pi: branch). The next append
   becomes a child of that entry, forming a new branch; existing entries are
   untouched (append-only). Throws when ENTRY-ID is not in the session."
  [session entry-id]
  (with-session-lock session
    (when-not (some #(= (:id %) entry-id) @(:entries session))
      (throw (ex-info (str "Entry " entry-id " not found")
                      {:type :entry-not-found :id entry-id})))
    (reset! (:leaf-id session) entry-id)))

(defn reset-leaf!
  "Reset the leaf pointer to nil (pi: resetLeaf). The next append creates a
   new root entry (parent-id nil)."
  [session]
  (with-session-lock session
    (reset! (:leaf-id session) nil)))

(defn append-custom-entry!
  "Append a custom entry (extension state) as a child of the current leaf
   (pi: appendCustomEntry). Custom entries never participate in LLM
   context — extensions use them for durable state, read back with
   get-custom-entries on reload. Returns the entry."
  [session custom-type & [data]]
  (append-entry session {:role :custom :custom-type custom-type :data data}))

(defn append-custom-message-entry!
  "Append a custom_message entry that participates in LLM context (pi:
   appendCustomMessageEntry): its content projects into the context as a
   :custom-role message (sent to the LLM as a user message — pi:
   convertToLlm custom→user), :display controls whether it renders in the
   TUI, :details is extension metadata not sent to the LLM. Returns the
   entry."
  [session custom-type content display & [details]]
  (append-entry session {:role :custom-message
                         :custom-type custom-type
                         :content content
                         :display display
                         :details details}))

(defn get-custom-entries
  "All :custom entries of CUSTOM-TYPE along the active branch (pi:
   extensions persist state as custom entries and restore it on reload)."
  [session custom-type]
  (filter #(and (= :custom (:role %)) (= custom-type (:custom-type %)))
          (get-branch session)))

(defn get-all-custom-entries
  "All :custom entries of CUSTOM-TYPE in the session, abandoned branches
   included (pi: sessionManager.getEntries filtered by customType). Use for
   extension settings that outlive a branch jump; get-custom-entries stays
   scoped to the active branch (pi: getBranch)."
  [session custom-type]
  (filter #(and (= :custom (:role %)) (= custom-type (:custom-type %)))
          @(:entries session)))

;; ─── Model & thinking changes (G6 — pi: appendModelChange / appendThinkingLevelChange) ──

(defn append-model-change!
  "Append a :model-change entry recording a model/provider switch (pi:
   appendModelChange). Returns the entry."
  [session provider model]
  (append-entry session {:role :model-change :provider provider :model model}))

(defn append-thinking-level-change!
  "Append a :thinking-level-change entry recording a thinking level switch
   (pi: appendThinkingLevelChange). Returns the entry."
  [session thinking-level]
  (append-entry session {:role :thinking-level-change :thinking-level thinking-level}))

(defn sanitize-session-name
  "Sanitize a session display name: collapse newlines to spaces, trim
   (pi: appendSessionInfo — [\\r\\n]+ → space)."
  [name]
  (-> (str name)
      (str/replace #"[\r\n]+" " ")
      str/trim))

(defn append-session-info!
  "Set the session display name: appends a session_info entry carrying the
   sanitized name (pi: appendSessionInfo). Returns the entry."
  [session name]
  (append-entry session {:role :session_info :name (sanitize-session-name name)}))

(defn get-session-name
  "Current session display name from the latest session_info entry, or nil.
   Empty names explicitly clear the title (pi: getSessionName — walks
   entries in reverse; the latest session_info entry wins, even when its
   name is empty)."
  [session]
  (loop [entries (reverse @(:entries session))]
    (when-let [e (first entries)]
      (if (= :session_info (:role e))
        (let [n (str/trim (str (:name e "")))]
          (when (seq n) n))
        (recur (rest entries))))))

(defn session-cwd
  "The working directory a session works in (pi: AgentSession._cwd — a
   resumed or imported session brings its recorded cwd): the header's :cwd
   as an absolute path when it is set and the directory still exists, else
   nil. pi throws (MissingSessionCwdError) and the interactive mode asks /
   falls back; kmet keeps the current cwd, so nil reads as \"no opinion\"."
  [session]
  (when-let [cwd (get-in session [:header :cwd])]
    (let [abs (-> (str cwd) fs/expand-home fs/absolutize fs/normalize str)]
      (when (fs/directory? abs) abs))))

(defn compact-with-summary!
  "Append a compaction entry summarizing everything before first-kept-id (pi:
   appendCompaction). Append-only: the summarized entries stay in the file;
   build-context returns [compaction, ...from first-kept-id], so old content
   stays reachable (tree, fork) while being excluded from the LLM context.
   opts may carry :tokens-before, :usage, :details, :from-hook (true when an
   extension supplied the summary — pi: CompactionEntry.fromHook).
   Returns the compaction entry, or nil when first-kept-id is not found.
   The compaction policy that calls this lives in kmet.app.compaction.stage;
   the entry shape belongs here, next to make-bash-entry."
  [session summary first-kept-id & [opts]]
  (when (some #(= (:id %) first-kept-id) @(:entries session))
    (append-entry session
                  (merge {:role :compaction
                          :summary summary
                          :first-kept-id first-kept-id}
                         (select-keys opts [:tokens-before :usage :details :from-hook])))))

(defn make-bash-entry
  "Build the session entry for a !/!! bash command result.
   exclude-from-context? mirrors pi's !! behavior (output not sent to the LLM)."
  [command result exclude-from-context?]
  {:role :bash
   :command command
   :output (:output result "")
   :exit-code (:exit-code result)
   :cancelled (:cancelled result false)
   :exclude-from-context? exclude-from-context?
   :truncated (:truncated result false)
   :full-output-path (:full-output-path result)})

(defn record-bash-result!
  "Record the result of a !/!! bash command in the session.
   When exclude-from-context? is true, the output is not included in
   the conversation history visible to the LLM (matching pi's !! behavior)."
  [session command result exclude-from-context?]
  (append-entry session (make-bash-entry command result exclude-from-context?)))

;; ─── Usage tracking ────────────────────────────────────────────────────────

(defn message-entry?
  "True for conversation message entries (pi: buildSessionInfo counts
   entry.type === \"message\" — user/assistant/tool, incl. bash results
   stored as tool messages in pi). Display-only :info entries don't count
   (pi: custom_message is a separate entry type, never counted).
   Internal — called by session.listing and session.stats."
  [e]
  (contains? #{:user :assistant :tool :bash} (:role e)))

(defn timestamp-ms
  "Parse an ISO-8601 timestamp string into epoch ms, nil when absent or
   unparseable (pi: new Date(ts).getTime() with NaN → undefined).
   Internal — called by session.listing."
  [ts]
  (when ts
    (try (-> (java.time.Instant/parse ts) (.toEpochMilli))
         (catch Exception _ nil))))

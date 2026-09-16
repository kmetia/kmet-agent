(ns kmet.app.session
  "EDNL session storage — line-delimited EDN with parent-child IDs for tree branching.
   Each entry is an EDN map on one line: {:id str :parent-id str-or-nil :role keyword ...}
   Port of @earendil-works/pi-agent session storage."
  (:require [kmet.libs.usage :as usage]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs])
  (:import [java.io InputStreamReader]))

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
   Reentrant, so nested session operations inside the body are safe."
  [session & body]
  `(let [lock# (:lock ~session)]
     (.lock lock#)
     (try ~@body
          (finally (.unlock lock#)))))

(defn- generate-id
  "Generate a unique entry id: hex timestamp + 8-hex random, collision
   checked against the session's existing entry ids (pi: generateId —
   collision-checked against the index). The timestamp prefix keeps ids
   time-ordered across processes; the 32-bit random component makes
   same-ms cross-process collisions negligible (G12 — the 16-bit rand
   allowed 1/65536 same-ms collisions)."
  [entries]
  (let [existing (into #{} (map :id) entries)]
    (loop []
      (let [id (str (format "%x" (System/currentTimeMillis))
                    "-" (format "%04x%04x" (rand-int 0x10000) (rand-int 0x10000)))]
        (if (contains? existing id)
          (recur)
          id)))))

(defn- timestamp []
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
   file (pi: temp-file publication). Creates the session dir. Callers must
   hold the session lock."
  [session entries]
  (let [file (:file session)
        tmp (str file ".tmp")]
    (fs/create-dirs (fs/parent file))
    (spit tmp (apply str (map prn-str (cons (:header session) entries))))
    (fs/move tmp file {:replace-existing true})))

(defn- persist-if-needed!
  "Lazy file creation (pi: _persist — G4): write the file the first time the
   branch contains an assistant message; before that, entries accumulate in
   memory only and no file exists on disk. No-op once the file exists.
   Callers must hold the session lock."
  [session]
  (when (and (not (fs/exists? (:file session)))
             (some #(= :assistant (:role %)) @(:entries session)))
    (publish-file! session @(:entries session))))

(defn- write-entries!
  "Atomically replace the session file's contents and in-memory state (pi:
   temp-file publication). Rewrites whenever the file exists; a
   not-yet-persisted session (lazy creation — G4) is only written once the
   branch has an assistant message. Callers must hold the session lock."
  [session entries]
  (let [entries (vec entries)]
    (when (or (fs/exists? (:file session))
              (some #(= :assistant (:role %)) entries))
      (publish-file! session entries))
    (reset! (:entries session) entries)
    (reset! (:leaf-id session) (some-> entries last :id))))

(defn- write-entries-verbatim!
  "Write pre-built entries (ids/parents/timestamps already assigned) to the
   session file and in-memory state, publishing via temp-file + rename.
   Used by forks, which copy source entries verbatim (pi:
   createBranchedSession/forkFrom). Lazy creation: nothing is written until
   the branch contains an assistant message. Callers must hold the session
   lock."
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
   (pi: default layout sessions/<--cwd-->/)."
  [base-dir cwd]
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
      (try (edn/read-string trimmed)
           (catch Exception ex
             (binding [*out* *err*]
               (println "Warning: Skipping invalid entry in" path ":" (ex-message ex)))
             ::invalid)))))

(defn- parse-silently
  "Parse one physical line into a session entry, nil for blank or malformed
   lines without warning (pi: buildSessionInfo skips malformed lines
   silently — only load-session reports them)."
  [line]
  (let [trimmed (str/trim line)]
    (when (seq trimmed)
      (try (edn/read-string trimmed)
           (catch Exception _ nil)))))

(defn- reduce-physical-lines
  "Reduce F over the physical lines of PATH (chunked, line-oriented reads —
   pi: createReadStream + readline; UTF-8 sequences spanning chunk
   boundaries decode intact). F is (fn [acc line]); the final line is
   included even without a trailing newline. Streams aggregates without
   materializing the file (used by build-session-info, G15). When F returns
   (reduced nil), the read stops and nil is returned (early exit for
   headerless files — nil is never a valid accumulator)."
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
  (let [valid (subvec lines 0 (dec (count lines)))
        tmp (str file ".tmp")]
    (spit tmp (str (str/join "\n" valid) "\n"))
    (fs/move tmp file {:replace-existing true}))
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
            (spit (str file) "\n" :append true))
        parsed (vec (keep (fn [line]
                            (let [e (parse-physical-line path line)]
                              (when-not (= ::invalid e) e)))
                          lines))
        header? (and (seq parsed) (= :session (:type (first parsed))))
        header (when header? (first parsed))
        entries (if header? (subvec parsed 1) parsed)
        leaf-id (some-> entries last :id)]
    (map->Session {:file (str (fs/canonicalize file))
                   :id (or (:id header)
                           (str/replace (fs/file-name file) #"\.ednl$" ""))
                   :header header
                   :entries (atom entries)
                   :leaf-id (atom leaf-id)
                   :lock (java.util.concurrent.locks.ReentrantLock.)})))

(defn append-entry
  "Append an entry to the session atom, persisting it to the file (pi:
   storage.appendEntry is enqueued; _persist defers the file write until the
   first assistant message — lazy creation G4). Serialized per session so
   concurrent appends (bash-result future + agent loop) can't produce
   orphaned sibling entries."
  [session entry]
  (with-session-lock session
    (let [entry (build-entry @(:entries session) @(:leaf-id session) entry)
          file (:file session)]
      (swap! (:entries session) conj entry)
      (reset! (:leaf-id session) (:id entry))
      (if (fs/exists? file)
        (spit file (prn-str entry) :append true)
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
  "Plain trimmed text of an entry's content blocks (pi:
   extractUserMessageText — used for fork/tree editor restore)."
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

(defn common-ancestor-id
  "The deepest entry on both the OLD-LEAF-ID and TARGET-ID paths (pi:
   collectEntriesForBranchSummary's commonAncestorId), nil when OLD-LEAF-ID
   is nil or the paths are disjoint."
  [session old-leaf-id target-id]
  (when old-leaf-id
    (let [old-path (set (map :id (get-branch session old-leaf-id)))]
      (some (fn [e] (when (contains? old-path (:id e)) (:id e)))
            (reverse (get-branch session target-id))))))

(defn branch-summary-entries
  "The abandoned-branch entries a summary captures when navigating from
   OLD-LEAF-ID to TARGET-ID (pi: collectEntriesForBranchSummary): the path
   from the old leaf up to, excluding, the common ancestor with the target
   path, in chronological order. Empty when OLD-LEAF-ID is nil."
  [session old-leaf-id target-id]
  (when old-leaf-id
    (let [index (into {} (map (juxt :id identity)) @(:entries session))
          common (common-ancestor-id session old-leaf-id target-id)]
      (loop [id old-leaf-id result ()]
        (if (and id (not= id common))
          (if-let [e (get index id)]
            (recur (:parent-id e) (cons e result))
            (vec result))
          (vec result))))))

(defn branch-with-summary!
  "Move the leaf to BRANCH-FROM-ID (nil = root) and append a :branch-summary
   entry capturing the abandoned path (pi: branchWithSummary). The summary
   entry is a child of the new leaf position and projects into the LLM
   context as a user message on the next context build (context-messages).
   opts: :details, :usage, :from-hook. Returns the summary entry. Throws
   when BRANCH-FROM-ID is not in the session."
  [session branch-from-id summary & [opts]]
  (with-session-lock session
    (when (and branch-from-id
               (not (some #(= (:id %) branch-from-id) @(:entries session))))
      (throw (ex-info (str "Entry " branch-from-id " not found")
                      {:type :entry-not-found :id branch-from-id})))
    (reset! (:leaf-id session) branch-from-id)
    (append-entry session
                  (merge {:role :branch-summary
                          :summary summary
                          :from-id (or branch-from-id "root")}
                         (select-keys opts [:details :usage :from-hook])))))

;; ─── Labels (pi: appendLabelChange / getLabel) ────────────────────────────

(defn- resolve-labels
  "Latest-wins label resolution over :label entries: {target-id {:label str
   :timestamp str}} for targets whose latest :label entry set (not cleared)
   the label (pi: labelsById + labelTimestampsById)."
  [entries]
  (reduce (fn [m e]
            (if (= :label (:role e))
              (if (seq (str (:label e "")))
                (assoc m (:target-id e)
                       {:label (str (:label e)) :timestamp (:timestamp e)})
                (dissoc m (:target-id e)))
              m))
          {}
          entries))

(defn get-label
  "Current label of an entry id (pi: getLabel); nil when unlabeled or
   cleared by a later label entry."
  [session entry-id]
  (get-in (resolve-labels @(:entries session)) [entry-id :label]))

(defn get-labels
  "All resolved labels {entry-id {:label str :timestamp str}} in one pass
   (pi: labelsById + labelTimestampsById); targets whose latest :label entry
   cleared the label are absent."
  [session]
  (resolve-labels @(:entries session)))

(defn set-label!
  "Set or clear a label on an entry (pi: appendLabelChange): appends a
   :label entry as a child of the current leaf. Labels are user-defined
   markers shown in the session tree; pass nil or \"\" to clear. Throws when
   TARGET-ID is not an entry. Returns the label entry."
  [session target-id label]
  (with-session-lock session
    (when-not (some #(= (:id %) target-id) @(:entries session))
      (throw (ex-info (str "Entry " target-id " not found")
                      {:type :entry-not-found :id target-id})))
    (append-entry session {:role :label
                           :target-id target-id
                           :label (when (seq (str label)) (str label))})))

;; ─── Custom entries (G9/G10 — pi: appendCustomEntry / appendCustomMessageEntry) ──

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

(defn derive-context-settings
  "Derive {:thinking-level :model :provider} from the branch root→leaf (pi:
   getSessionContextSettings): the latest :thinking-level-change entry wins
   (:off when none is on the path), the latest :model-change entry wins
   (nil when none). Settings from abandoned branches are never seen — the
   derivation follows the active leaf."
  [session]
  (reduce (fn [acc e]
            (case (:role e)
              :thinking-level-change (assoc acc :thinking-level (:thinking-level e))
              :model-change (assoc acc :model (:model e) :provider (:provider e))
              acc))
          {:thinking-level :off :model nil :provider nil}
          (get-branch session)))

;; ─── Context build (pi: buildSessionContext) ─────────────────────────────

(defn context-entries
  "Pure port of pi buildContextEntries over a branch vector (entries in
   root→leaf order). With a compaction on the path: [compaction, ...entries
   from its first-kept-id] — summarized history is excluded from context but
   stays in the file. Without one: the full branch. Multiple compactions:
   the latest wins (pi walks the path and keeps the last compaction seen)."
  [branch]
  (let [compaction-idx (last (keep-indexed (fn [i e] (when (= :compaction (:role e)) i))
                                           branch))]
    (if (nil? compaction-idx)
      branch
      (let [compaction (nth branch compaction-idx)
            kept (subvec branch 0 compaction-idx)
            kept-tail (drop-while #(not= (:id %) (:first-kept-id compaction)) kept)
            after-compaction (subvec branch (inc compaction-idx))]
        (into [compaction] (concat kept-tail after-compaction))))))

(defn build-context
  "Context entries along the active branch (pi: buildContextEntries — see
   context-entries)."
  [session]
  (context-entries (get-branch session)))

(defn context-messages
  "Project a session entry into LLM context messages (pi:
   sessionEntryToContextMessages). Message entries pass through unchanged;
   compaction and branch_summary entries also keep their roles (pi:
   compactionSummary/branchSummary AgentMessages) — the provider edge
   converts them to user messages carrying the wrapped summary text (pi:
   convertToLlm). custom_message entries become a :custom-role message
   (sent to the LLM as a user message — pi: convertToLlm custom→user) with
   string content normalized to a text block; :info, :session_info,
   :model-change, :thinking-level-change and :custom are metadata/state and
   excluded. Excluded :bash entries are kept here and dropped later by the
   LLM conversion (pi: convertToLlm filters excludeFromContext)."
  [entry]
  (case (:role entry)
    (:compaction :branch-summary) [entry]
    :custom-message
    [{:role :custom
      :custom-type (:custom-type entry)
      ;; pi: createCustomMessage — string content becomes a text block, nil
      ;; defaults to an empty block vector
      :content (cond
                 (string? (:content entry)) [{:type :text :text (:content entry)}]
                 (nil? (:content entry)) []
                 :else (:content entry))
      :display (:display entry)
      :details (:details entry)}]
    (:user :assistant :tool :bash) [entry]
    []))

;; ─── Cache-miss detection (pi: cache-stats.ts detectMiss) ────────────────

(defn detect-cache-miss
  "Compare consecutive assistant messages' usage to spot a significant
   prompt-cache miss (pi: detectMiss): the newer turn's prompt minus its
   cache-read falls far below the previous turn's prompt (noise floor 1024
   tokens, pi NOISE_FLOOR_TOKENS). The previous message must have reported
   cache activity — a provider that never reports caching counts nothing.
   The model per message is derived from the :model-change entries (kmet
   stores model changes separately; pi's messages carry their model).
   Returns {:missed-tokens n :model-changed bool} or nil."
  [entries]
  (let [{:keys [result]}
        (reduce (fn [acc e]
                  (case (:role e)
                    :model-change (assoc acc :model (:model e))
                    :assistant (if (some? (:usage e))
                                 (update acc :result conj {:usage (:usage e)
                                                           :model (:model acc)})
                                 acc)
                    acc))
                {:model nil :result []}
                entries)
        msgs (take-last 2 result)]
    (when (= 2 (count msgs))
      (let [[prev msg] msgs
            pu (usage/entry-usage (:usage prev))
            mu (usage/entry-usage (:usage msg))
            prev-reported (and pu (pos? (+ (:cache-read pu) (:cache-write pu))))]
        (when (and pu mu prev-reported)
          (let [prev-tokens (+ (:input pu) (:cache-read pu) (:cache-write pu))
                msg-tokens (+ (:input mu) (:cache-read mu) (:cache-write mu))
                missed (- (min prev-tokens msg-tokens) (:cache-read mu))]
            (when (and (pos? prev-tokens) (pos? msg-tokens) (> missed 1024))
              {:missed-tokens missed
               :model-changed (not= (:model prev) (:model msg))})))))))

;; ─── Session display name (pi: /name command) ─────────────────────────────

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

(defn get-tree
  "Build a tree structure from session entries.
   Returns map of {:id info, :children [...]}. O(n): children are grouped
   by parent in a single pass over entries."
  [session]
  (let [entries @(:entries session)
        labels (resolve-labels entries)
        by-parent (group-by :parent-id entries)]
    (letfn [(build-node [entry]
              {:id (:id entry)
               :role (:role entry)
               :label (get-in labels [(:id entry) :label])
               :summary (let [content (:content entry)
                              text (if (string? content) content
                                       (str/join (map :text (filter #(= (:type %) :text) content))))
                              trimmed (str/trim text)]
                          (cond
                            (seq trimmed) (subs trimmed 0 (min 60 (count trimmed)))
                            ;; session_info entries carry the display name
                            ;; instead of message content (pi: tree shows
                            ;; "[title: name]")
                            (= (:role entry) :session_info) (or (:name entry) "(empty)")
                            ;; compaction entries carry their summary text
                            (:summary entry) (subs (:summary entry) 0 (min 60 (count (:summary entry))))
                            ;; model/thinking change entries carry no content — show the switch
                            (= (:role entry) :model-change)
                            (str "[model: " (name (:provider entry)) "/" (:model entry) "]")
                            (= (:role entry) :thinking-level-change)
                            (str "[thinking: " (name (:thinking-level entry)) "]")
                            ;; custom entries carry no content — show the extension type
                            (= (:role entry) :custom)
                            (str "[custom: " (name (:custom-type entry)) "]")
                            (= (:role entry) :custom-message)
                            (str "[custom: " (name (:custom-type entry)) "]")
                            ;; pi getEntryDisplayText: an empty assistant entry
                            ;; (a recorded empty completion) is labeled
                            ;; "(no content)" — its only trace anywhere
                            (= (:role entry) :assistant) "(no content)"
                            :else "(empty)"))
               :children (mapv build-node (get by-parent (:id entry)))})]
      (mapv build-node (get by-parent nil)))))

(defn compact-with-summary!
  "Append a compaction entry summarizing everything before first-kept-id (pi:
   appendCompaction). Append-only: the summarized entries stay in the file;
   build-context returns [compaction, ...from first-kept-id], so old content
   stays reachable (tree, fork) while being excluded from the LLM context.
   opts may carry :tokens-before, :usage, :details (pi: CompactionEntry).
   Returns the compaction entry, or nil when first-kept-id is not found."
  [session summary first-kept-id & [opts]]
  (when (some #(= (:id %) first-kept-id) @(:entries session))
    (append-entry session
                  (merge {:role :compaction
                          :summary summary
                          :first-kept-id first-kept-id}
                         (select-keys opts [:tokens-before :usage :details])))))

(defn fork-session
  "Create a new session containing the branch root→ENTRY-ID (pi:
   createBranchedSession — /fork and /clone). The fork lives in the same
   session directory; its header links :parent-session to the source file
   and keeps the source cwd. Entry ids and timestamps are preserved so
   label/extension references stay valid. Label entries are real tree
   entries: they are filtered out of the retained path (parents re-chained)
   and recreated as a chain off the last entry (pi: label re-chaining —
   later entries can be children of labels, so removing them would orphan
   subtrees). Returns the new Session record, or nil when ENTRY-ID is not
   found."
  [session entry-id]
  (let [entries @(:entries session)
        target (get-entry session entry-id)]
    (when target
      (let [path (get-branch session entry-id)
            ;; Labels out of the path, parents re-chained along the rest
            [retained _] (reduce (fn [[acc last-id] e]
                                   (if (= :label (:role e))
                                     [acc last-id]
                                     [(conj acc (assoc e :parent-id last-id)) (:id e)]))
                                 [[] nil]
                                 path)
            retained-ids (into #{} (map :id) retained)
            ;; Recreate labels targeting retained entries, chained off the
            ;; last retained entry, with their original timestamps
            [label-entries _] (reduce (fn [[acc parent-id] [target-id {:keys [label timestamp]}]]
                                        (let [le {:role :label
                                                  :id (generate-id (into #{} (map :id) (concat retained acc)))
                                                  :parent-id parent-id
                                                  :timestamp timestamp
                                                  :target-id target-id
                                                  :label label}]
                                          [(conj acc le) (:id le)]))
                                      [[] (some-> retained last :id)]
                                      (keep (fn [[target-id l]]
                                              (when (contains? retained-ids target-id)
                                                [target-id l]))
                                            (resolve-labels entries)))
            fork (create-session (str (fs/parent (:file session)))
                                 {:cwd (get-in session [:header :cwd])
                                  :parent-session (:file session)})]
        (with-session-lock fork
          (write-entries-verbatim! fork (into retained label-entries)))
        fork))))

(defn clone-session
  "Duplicate the session at its current position (pi: /clone → fork at the
   current leaf). Returns the fork, or nil when the session has no leaf yet
   (nothing to clone)."
  [session]
  (when-let [leaf @(:leaf-id session)]
    (fork-session session leaf)))

(defn fork-from
  "Fork a session from another project into TARGET-CWD (pi: forkFrom): a new
   session in TARGET-CWD's session dir under BASE-DIR with a new header
   linking :parent-session to the source; all non-header entries are copied
   verbatim (ids, parents, timestamps preserved). Returns the new Session
   record. Throws when the source file is empty or has no header."
  [source-path target-cwd base-dir]
  (let [source (load-session source-path)]
    (when (empty? @(:entries source))
      (throw (ex-info (str "Cannot fork: source session file is empty or invalid: " source-path)
                      {:type :fork-error :path source-path})))
    (when (nil? (:header source))
      (throw (ex-info (str "Cannot fork: source session has no header: " source-path)
                      {:type :fork-error :path source-path})))
    (let [dir (session-dir-for-cwd base-dir (str (fs/absolutize target-cwd)))
          fork (create-session dir {:cwd (str (fs/absolutize target-cwd))
                                    :parent-session (:file source)})]
      (with-session-lock fork
        (write-entries-verbatim! fork @(:entries source)))
      fork)))

;; ─── Bash result recording ─────────────────────────────────────────────────

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

(defn usage-totals
  "Sum normalized usage (incl. USD :cost) across session entries carrying
   :usage (pi: FooterComponent accumulates usage from all session entries)."
  [session]
  (reduce (fn [totals e]
            (if-let [u (usage/entry-usage (:usage e))]
              (merge-with + totals u)
              totals))
          {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
          @(:entries session)))

;; ─── Convenience ───────────────────────────────────────────────────────────

(defn- entry-text
  "Plain trimmed text of an entry's content blocks (pi: extractTextContent)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

(defn get-first-message
  "First user message text of the session (pi: buildSessionInfo firstMessage
   — the first user message with text content, else \"(no messages)\")."
  [session]
  (or (some (fn [e]
              (when (= :user (:role e))
                (let [t (entry-text e)]
                  (when (seq t) t))))
            @(:entries session))
      "(no messages)"))

(defn get-prompt-history
  "User message texts of the session's active branch (root→leaf,
  compaction-aware — pi: buildContextEntries), matching the editor's
  prompt-history convention (pi: addToHistory — empty messages skipped,
  consecutive duplicates collapsed, newest 100 kept). Used to repopulate
  the editor's Up/Down history when resuming a session; kmet sessions are
  append-only, so the history is reconstituted from :user entries rather
  than stored separately."
  [session]
  (loop [ts (->> (context-entries (get-branch session))
                 (filter #(= :user (:role %)))
                 (keep (fn [e] (let [t (entry-text e)] (when (seq t) t)))))
         acc []]
    (if-let [t (first ts)]
      (recur (rest ts)
             (if (= t (peek acc))
               acc
               (let [acc (conj acc t)]
                 (if (> (count acc) 100) (subvec acc 1) acc))))
      acc)))

(defn- message-entry?
  "True for conversation message entries (pi: buildSessionInfo counts
   entry.type === \"message\" — user/assistant/tool, incl. bash results
   stored as tool messages in pi). Display-only :info entries don't count
   (pi: custom_message is a separate entry type, never counted)."
  [e]
  (contains? #{:user :assistant :tool :bash} (:role e)))

(defn get-message-count
  "Number of message entries in the session (pi: buildSessionInfo
   messageCount — message entries only). kmet's :bash role is the EDN
   analogue of pi's tool messages (which ARE message entries in pi), so
   :bash counts; display-only :info entries don't (pi: custom_message)."
  [session]
  (count (filter message-entry? @(:entries session))))

(defn get-session-stats
  "Session statistics for /session (pi: getSessionStats). Aggregates over
   ALL entries (including history compacted away), so token/cost totals
   reflect what was actually billed. Message roles: :user counts user,
   :assistant counts assistant, :tool and :bash count tool results (pi
   stores bash results as tool messages in message entries). Tool calls are
   the :tool-calls vectors on assistant entries. Tokens/cost come from
   usage-totals (assistant + tool usage, incl. compaction/branch-summary
   usage carried on entries). Returns {:file :id :user-messages
   :assistant-messages :tool-calls :tool-results :total-messages :tokens
   {:input :output :cache-read :cache-write :total} :cost}."
  [session]
  (let [{:keys [input output cache-read cache-write cost]} (usage-totals session)
        counts (reduce (fn [acc e]
                         (case (:role e)
                           :user (update acc :user inc)
                           :assistant (update acc :assistant inc)
                           (:tool :bash) (update acc :tool-results inc)
                           acc))
                       {:user 0 :assistant 0 :tool-results 0}
                       @(:entries session))
        tool-calls (reduce (fn [n e]
                             (if (= :assistant (:role e))
                               (+ n (count (:tool-calls e)))
                               n))
                           0
                           @(:entries session))]
    {:file (:file session)
     :id (:id session)
     :user-messages (:user counts)
     :assistant-messages (:assistant counts)
     :tool-calls tool-calls
     :tool-results (:tool-results counts)
     :total-messages (get-message-count session)
     :tokens {:input (long input) :output (long output) :cache-read (long cache-read) :cache-write (long cache-write)
              :total (+ input output cache-read cache-write)}
     :cost (double cost)}))

(defn get-last-assistant-text
  "Text content of the last assistant message on the branch (pi:
   getLastAssistantText — /copy). Scans the branch (not the full entry
   list, matching pi's live messages) for the last assistant entry with
   non-empty text; aborted/empty assistant entries (no text blocks) are
   skipped. Returns the trimmed text or nil."
  [session]
  (some (fn [e]
          (when (= :assistant (:role e))
            (let [t (entry-text e)]
              (when (seq t) t))))
        (reverse (get-branch session))))

(defn- provider-model-key
  "\"provider/model\" attribution key for an entry carrying :provider/:model
   (the model id may itself contain slashes — pi keys the same way)."
  [entry]
  (str (name (:provider entry)) "/" (:model entry)))

(defn usage-breakdown
  "Per-model cost/token attribution for /session (pi: getUsageCostBreakdown).
   An assistant entry is attributed to its own :provider/:model stamp
   (add-assistant-message! records provenance — pi messages carry the same);
   entries without a stamp (legacy files) fall back to the latest preceding
   :model-change entry, then \"unknown\" — old sessions never recorded their
   initial model. Tool-result and compaction/branch-summary usage groups
   under \"Tools/summaries\". Returns [{:key str :cost double :tokens long}
   ...] sorted by cost desc, filtered to buckets with cost or tokens."
  [session]
  (let [;; Walk entries in order: an assistant entry uses its own stamp when
        ;; present, else the latest preceding :model-change, else "unknown".
        {buckets :buckets}
        (reduce (fn [{:keys [model] :as acc} e]
                  (case (:role e)
                    :model-change
                    (assoc acc :model (provider-model-key e))

                    :assistant
                    (if-let [u (usage/entry-usage (:usage e))]
                      (let [key (cond
                                  (and (:provider e) (:model e)) (provider-model-key e)
                                  model model
                                  :else "unknown")]
                        (update-in acc [:buckets key] #(merge-with + % u)))
                      acc)

                    (:tool :bash :compaction :branch-summary)
                    (if-let [u (usage/entry-usage (:usage e))]
                      (update-in acc [:buckets "Tools/summaries"] #(merge-with + % u))
                      acc)

                    acc))
                {:model nil :buckets {}}
                @(:entries session))]
    (->> buckets
         (map (fn [[key {:keys [input output cache-read cache-write cost]}]]
                {:key key
                 :cost cost
                 :tokens (+ input output cache-read cache-write)}))
         (filter #(or (pos? (:cost %)) (pos? (:tokens %))))
         (sort-by :cost >)
         vec)))

(defn get-last-activity-ms
  "Last message activity time as epoch ms (pi: modified — the latest message
   timestamp), falling back to the file mtime, then the header :created-at
   (a lazy session has no file until the first assistant message — G4).
   An unparseable timestamp (corrupt/legacy file) falls back rather than
   throwing."
  [session]
  (let [last-ts (some-> (filter #(contains? % :timestamp) @(:entries session))
                        last
                        :timestamp)]
    (or (when last-ts
          (try (-> (java.time.Instant/parse last-ts) (.toEpochMilli))
               (catch Exception _ nil)))
        (when (fs/exists? (:file session))
          (.toMillis (fs/last-modified-time (:file session))))
        (when-let [created (:created-at (:header session))]
          (try (-> (java.time.Instant/parse created) (.toEpochMilli))
               (catch Exception _ nil)))
        0)))

(defn list-sessions
  "List all session files under a session directory, newest first. When DIR
   is a base sessions dir, its cwd-encoded subdirectories are walked too
   (pi: listAll — sessions/<--cwd-->/); when DIR is a single cwd-encoded
   dir, it is listed flat. Returns canonical absolute paths."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (let [dirs (cons d (filter fs/directory? (fs/list-dir d)))]
        (->> dirs
             (mapcat (fn [sub]
                       (->> (fs/list-dir sub)
                            (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                                          (fs/regular-file? %)))
                            (map #(str (fs/canonicalize %))))))
             (sort-by #(.toMillis (fs/last-modified-time %)) >)
             vec)))))

;; ─── Listing (G15 — pi: buildSessionInfo / buildSessionInfosWithConcurrency) ──

(def ^:private max-concurrent-session-info-loads
  "Concurrent per-file session-info loads (pi:
   MAX_CONCURRENT_SESSION_INFO_LOADS — 10)."
  10)

(defn- timestamp-ms
  "Parse an ISO-8601 timestamp string into epoch ms, nil when absent or
   unparseable (pi: new Date(ts).getTime() with NaN → undefined)."
  [ts]
  (when ts
    (try (-> (java.time.Instant/parse ts) (.toEpochMilli))
         (catch Exception _ nil))))

(defn build-session-info
  "Streaming per-file session info (pi: buildSessionInfo): a map with
   :path :id :cwd :name :parent-session-path :created :modified
   :message-count :first-message :all-messages-text. Streams the file in
   1 MB chunks, keeping only aggregates (G15). The header must be a
   :session entry — legacy headerless files yield nil (pi returns null).
   :name is the latest session_info entry (incl. explicit clears → nil);
   :modified is the latest message activity time as epoch ms, falling back
   to the header :created-at, then the file mtime (pi: buildSessionInfo);
   :message-count counts message entries only (same set as
   get-message-count); :first-message is the first user message text
   (\"(no messages)\" when none); :all-messages-text joins all
   user/assistant message texts. Returns nil for headerless, corrupt, or
   unreadable files."
  [path]
  (try
    (let [file (io/file path)
          init {:header nil :name nil :message-count 0 :first-message nil
                :all-messages [] :last-activity nil}
          acc (reduce-physical-lines
               (str file)
               (fn [acc line]
                 (let [e (parse-silently line)]
                   (cond
                     ;; blank/malformed lines are skipped (pi: !entry → continue)
                     (nil? e) acc
                     ;; the first parseable entry must be a session header
                     (nil? (:header acc))
                     (if (= :session (:type e))
                       (assoc acc :header e)
                       (reduced nil))
                     ;; latest session_info wins, incl. explicit clears
                     (= :session_info (:role e))
                     (assoc acc :name (let [n (str/trim (str (:name e "")))]
                                        (when (seq n) n)))
                     (message-entry? e)
                     (let [text (entry-text e)
                           ts (timestamp-ms (:timestamp e))
                           ;; pi: getMessageActivityTime — only user/assistant
                           ;; messages with content advance the activity time
                           activity? (and (seq text)
                                          (contains? #{:user :assistant} (:role e)))]
                       (cond-> (update acc :message-count inc)
                         (and (seq text)
                              (contains? #{:user :assistant} (:role e)))
                         (update :all-messages conj text)
                         (and (seq text) (= :user (:role e))
                              (nil? (:first-message acc)))
                         (assoc :first-message text)
                         (and activity? ts (> ts (long (or (:last-activity acc) 0))))
                         (assoc :last-activity ts)))
                     :else acc)))
               init)
          header (:header acc)
          created-ms (timestamp-ms (:created-at header))]
      (when (and header (:id header))
        {:path (str (fs/canonicalize file))
         :id (:id header)
         :cwd (:cwd header)
         :name (:name acc)
         :parent-session-path (:parent-session header)
         :created created-ms
         :modified (or (:last-activity acc)
                       created-ms
                       (.toMillis (fs/last-modified-time file)))
         :message-count (:message-count acc)
         :first-message (or (:first-message acc) "(no messages)")
         :all-messages-text (str/join " " (:all-messages acc))}))
    (catch Exception _ nil)))

(defn build-session-infos
  "Build session info for FILES with at most 10 concurrent loads (pi:
   buildSessionInfosWithConcurrency — a sliding window capped at
   MAX_CONCURRENT_SESSION_INFO_LOADS). ON-LOADED is called once per file
   that actually finished (progress callback; may be nil). Returns a vector
   aligned with FILES — nil for files that yielded no info
   (headerless/corrupt). Each iteration waits up to 5 s for the first
   in-flight future, then collects every completed future — completed
   siblings still count toward progress while a slow slot is pending, and
   no result is dropped or counted early. (A permanently stuck read stalls
   the listing, same as pi's Promise.race.)"
  [files on-loaded]
  (let [n (count files)
        results (atom (vec (repeat n nil)))
        in-flight (atom #{})
        next-idx (atom 0)]
    (letfn [(start-next! []
              (let [i @next-idx]
                (when (< i n)
                  (swap! next-idx inc)
                  (swap! in-flight conj (future
                                          (try
                                            (swap! results assoc i
                                                   (build-session-info (nth files i)))
                                            (catch Exception _ nil)))))))]
      (loop []
        (while (and (< @next-idx n)
                    (< (count @in-flight) max-concurrent-session-info-loads))
          (start-next!))
        (if (seq @in-flight)
          (let [f (first @in-flight)
                ;; wait for this slot (bounded — a stuck read can't hang the
                ;; caller forever); only completed futures count below
                _ (deref f 5000 nil)
                done (vec (filter future-done? @in-flight))]
            (swap! in-flight #(reduce disj % done))
            (doseq [_ done] (when on-loaded (on-loaded)))
            (recur))
          @results)))))

(defn list-sessions-info
  "Session info for all session files under a session directory, newest
   modified first (pi: SessionManager.listAll + buildSessionInfosWith-
   Concurrency). When DIR is a base sessions dir, its cwd-encoded
   subdirectories are walked (pi: listAll); when DIR is a single
   cwd-encoded dir, it is listed flat. Files are streamed (build-session-
   info) at most 10 concurrent; ON-PROGRESS (fn [loaded total]) is called
   as files complete. Legacy headerless files are excluded. Returns [] on
   any error (pi: listSessionsFromDir catches and returns empty — a broken
   dir must not take down the resume overlay)."
  [dir & [on-progress]]
  (try
    (let [d (io/file dir)]
      (if-not (fs/directory? d)
        []
        (let [dirs (cons d (filter fs/directory? (fs/list-dir d)))
              files (vec (mapcat (fn [sub]
                                   (->> (fs/list-dir sub)
                                        (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                                                      (fs/regular-file? %)))
                                        (map #(str (fs/canonicalize %)))))
                                 dirs))
              total (count files)
              loaded (atom 0)
              infos (build-session-infos
                     files
                     (fn []
                       (swap! loaded inc)
                       (when on-progress (on-progress @loaded total))))]
          (->> infos
               (remove nil?)
               (sort-by :modified >)
               vec))))
    (catch Exception _ [])))

(def ^:private header-read-buffer-size
  "Chunk size (chars) for the bounded header scan (pi:
   SESSION_HEADER_READ_BUFFER_SIZE — 4 KB)."
  4096)

(def ^:private max-header-scan-chars
  "Upper bound (chars) for the header scan (pi:
   MAX_SESSION_HEADER_SCAN_BYTES — 1 MB), so discovery never reads an
   oversized/garbage file to the end."
  (* 1024 1024))

(defn- parse-header-candidate
  "Inspect a single physical line while searching for a session header (pi:
   parseSessionHeaderCandidate). Blank and malformed lines keep scanning
   (::continue); a parsed non-header entry stops the scan with nil; a
   session header returns the header map."
  [line]
  (let [trimmed (str/trim line)]
    (if (empty? trimmed)
      ::continue
      (let [e (try (edn/read-string trimmed)
                   (catch Exception _ ::invalid))]
        (cond
          (= ::invalid e) ::continue
          (and (map? e) (= :session (:type e))) e
          :else nil)))))

(defn- consume-header-lines
  "Consume complete lines from TEXT through parse-header-candidate. Returns
   [decision remaining]: decision is a header map, nil (a parsed non-header
   entry), or ::continue; remaining is the unprocessed tail (a partial final
   line without a newline, carried to the next chunk)."
  [text]
  (loop [text text]
    (if-let [nl (str/index-of text "\n")]
      (let [decision (parse-header-candidate (subs text 0 nl))]
        (if (= ::continue decision)
          (recur (subs text (inc nl)))
          [decision (subs text (inc nl))]))
      [::continue text])))

(defn- read-session-header
  "Best-effort bounded header read for discovery (pi:
   readSessionHeaderForDiscovery): 4096-char chunked reads up to a 1 MB
   scan limit, line-oriented so a header spanning a chunk boundary is
   assembled (UTF-8 is decoded incrementally — split multi-byte sequences
   stay intact). Blank/malformed leading lines are skipped; the scan stops
   at the first parseable non-header entry (nil) or a session header.
   Returns nil for headerless, corrupt, oversized (no decision within the
   scan limit), or unreadable files."
  [path]
  (try
    (with-open [r (InputStreamReader. (io/input-stream path) "UTF-8")]
      (let [sb (StringBuilder.)
            cbuf (char-array header-read-buffer-size)]
        (loop [scanned 0]
          (if (>= scanned max-header-scan-chars)
            ;; At the limit a final header ending exactly here (no further
            ;; characters) is still accepted — probe EOF (pi: readSessionHeader).
            (if (neg? (.read r))
              (let [decision (parse-header-candidate (str sb))]
                (when-not (= ::continue decision) decision))
              nil)
            (let [n (.read r cbuf)]
              (if (neg? n)
                (let [decision (parse-header-candidate (str sb))]
                  (when-not (= ::continue decision) decision))
                ;; pending never holds a newline between iterations (complete
                ;; lines are consumed eagerly), so scanning the chunk alone is
                ;; enough — the whole-builder concat stays O(n) even for a
                ;; 1 MB single-line header
                (let [chunk (String. cbuf 0 n)]
                  (if (str/includes? chunk "\n")
                    (let [[decision remaining]
                          (consume-header-lines (str (.append sb chunk)))]
                      (if (= ::continue decision)
                        (do (.setLength sb 0)
                            (.append sb remaining)
                            (recur (+ scanned n)))
                        decision))
                    (do (.append sb chunk)
                        (recur (+ scanned n)))))))))))
    (catch Exception _ nil)))

(defn find-most-recent-session
  "Most recent session file in DIR whose header :cwd matches CWD (pi:
   findMostRecentSession — header-based discovery, best-effort; continue is
   scoped to the current project). Returns the canonical path, or nil when
   DIR holds no matching session. Legacy headerless files and files from
   other cwds are excluded — no fallback."
  [dir cwd]
  (let [resolved-cwd (str (fs/absolutize cwd))
        d (io/file dir)]
    (when (fs/directory? d)
      (->> (fs/list-dir d)
           (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                         (fs/regular-file? %)))
           (keep (fn [f]
                   (let [path (str (fs/canonicalize f))
                         header (read-session-header path)]
                     (when (and header
                                (seq (:cwd header))
                                (= (str (fs/absolutize (:cwd header))) resolved-cwd))
                       path))))
           (sort-by #(.toMillis (fs/last-modified-time %)) >)
           first))))

(defn delete-session!
  "Delete a session file."
  [session]
  (let [f (:file session)]
    (when (fs/exists? f) (fs/delete f))))

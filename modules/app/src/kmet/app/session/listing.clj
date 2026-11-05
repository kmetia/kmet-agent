(ns kmet.app.session.listing
  "On-disk session discovery: file candidates, header-only scans, the
   session list/metadata projection, and delete/import. Everything here reads
   and writes the files directly; entry values and durability live in
   kmet.app.session.store."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.app.session.store :as store]
            [kmet.libs.edn-store :as eds])
  (:import [java.io InputStreamReader]))

(defn- session-file-candidates
  "Every .ednl file under DIR — its cwd-encoded subdirectories included,
   DIR itself too — as {:path canonical-path :mtime ms} maps, newest
   first: file mtime descending, file name descending as the tiebreak.
   The resume selector streams candidates in this order, so the sessions a
   user is most likely to want are loaded and selectable first (pi:
   discovery candidates sort; listSessionsFromDir)."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (let [dirs (cons d (filter fs/directory? (fs/list-dir d)))]
        (->> dirs
             (mapcat (fn [sub]
                       (->> (fs/list-dir sub)
                            (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                                          (fs/regular-file? %)))
                            (map (fn [f]
                                   {:path (str (fs/canonicalize f))
                                    :mtime (try (.toMillis (fs/last-modified-time f))
                                                (catch Exception _ 0))})))))
             (sort (fn [a b]
                     (let [c (compare (long (:mtime b)) (long (:mtime a)))]
                       (if (zero? c)
                         (compare (fs/file-name (:path b)) (fs/file-name (:path a)))
                         c))))
             vec)))))

(defn list-sessions
  "List all session files under a session directory, newest first. When DIR
   is a base sessions dir, its cwd-encoded subdirectories are walked too
   (pi: listAll — sessions/<encoded-cwd>/); when DIR is a single cwd-encoded
   dir, it is listed flat. Returns canonical absolute paths."
  [dir]
  (some->> (session-file-candidates dir) (mapv :path)))

;; ─── Listing (G15 — pi: buildSessionInfo / buildSessionInfosWithConcurrency) ──

;; ─── Listing (G15 — pi: buildSessionInfo / buildSessionInfosWithConcurrency) ──

(def ^:private max-concurrent-session-info-loads
  "Concurrent per-file session-info loads (pi:
   MAX_CONCURRENT_SESSION_INFO_LOADS — 10)."
  10)

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
          acc (store/reduce-physical-lines
               (str file)
               (fn [acc line]
                 (let [e (store/parse-silently line)]
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
                     (store/message-entry? e)
                     (let [text (store/session-entry-text e)
                           ts (store/timestamp-ms (:timestamp e))
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
          created-ms (store/timestamp-ms (:created-at header))]
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
   that actually finished, with the info map (nil for headerless/corrupt
   files) and the file's index in FILES; it runs on the coordinating
   thread, so callbacks never interleave (progress callback; may be nil).
   STOP? is polled before scheduling each file — a truthy return stops
   scheduling new loads and returns the results collected so far without
   awaiting the rest (pi: AbortSignal). Returns a vector aligned with
   FILES — nil for files that yielded no info (headerless/corrupt). Each
   iteration waits up to 5 s for the first in-flight future, then collects
   every completed future — completed siblings still count toward progress
   while a slow slot is pending, and no result is dropped or counted
   early. (A permanently stuck read stalls the listing, same as pi's
   Promise.race.)"
  [files on-loaded stop?]
  (let [n (count files)
        results (atom (vec (repeat n nil)))
        in-flight (atom [])
        next-idx (atom 0)
        stopped? (fn [] (boolean (and stop? (stop?))))]
    (letfn [(start-next! []
              (let [i @next-idx]
                (when (< i n)
                  (swap! next-idx inc)
                  (swap! in-flight conj
                         [i (future
                              (try
                                (swap! results assoc i (build-session-info (nth files i)))
                                (catch Exception _ nil)))]))))]
      (loop []
        (while (and (not (stopped?))
                    (< @next-idx n)
                    (< (count @in-flight) max-concurrent-session-info-loads))
          (start-next!))
        (cond
          (stopped?) @results
          (seq @in-flight)
          (let [[_ f] (first @in-flight)]
            ;; wait for this slot (bounded — a stuck read can't hang the
            ;; caller forever); only completed futures count below
            (deref f 5000 nil)
            (let [done (vec (filter (fn [[_ fut]] (future-done? fut)) @in-flight))]
              (swap! in-flight (fn [inflight] (vec (remove (set done) inflight))))
              (doseq [[i _] done]
                (when on-loaded (on-loaded (nth @results i) i)))
              (recur)))
          :else @results)))))

(def ^:private partial-listing-publish-interval
  "Publish the partial snapshot on the first completed file, every 10th,
   and the last (pi: CURRENT_SESSION_LIST_PUBLISH_INTERVAL)."
  10)

(defn list-sessions-info
  "Session info for all session files under a session directory, newest
   modified first (pi: SessionManager.listAll + buildSessionInfosWith-
   Concurrency). When DIR is a base sessions dir, its cwd-encoded
   subdirectories are walked (pi: listAll); when DIR is a single
   cwd-encoded dir, it is listed flat. Files are discovered newest-first
   (mtime descending) and streamed at most 10 concurrent. OPTS:
     :on-progress — (fn [loaded total partial]) called as files complete.
       PARTIAL is the sorted newest-first snapshot of the sessions loaded
       so far, or nil between publishes; the first, every 10th, and the
       final update carry it (pi: SessionListProgress partialSessions).
       Headerless legacy files count toward LOADED/TOTAL but never enter a
       snapshot.
     :stop? — (fn []) polled before each file is scheduled; a truthy return
       stops the listing and returns the sessions loaded so far, so closing
       the resume overlay ends a scan in flight.
     :publish-every — publish cadence in completed files (default 10; the
       all-projects scope passes 100 — pi: ALL_SESSION_LIST_PUBLISH_INTERVAL).
   Returns [] on any error (pi: listSessionsFromDir catches and returns
   empty — a broken dir must not take down the resume overlay)."
  [dir & [opts]]
  (let [{:keys [on-progress stop? publish-every]} opts
        publish-every (or publish-every partial-listing-publish-interval)]
    (try
      (let [files (mapv :path (session-file-candidates dir))]
        (if (empty? files)
          []
          (let [total (count files)
                done (atom (vec (repeat total nil)))
                loaded (atom 0)]
            (build-session-infos
             files
             (fn [info index]
               (swap! done assoc index info)
               (let [n (swap! loaded inc)]
                 (when on-progress
                   (on-progress n total
                                (when (or (= 1 n)
                                          (= total n)
                                          (zero? (mod n publish-every)))
                                  (->> @done
                                       (remove nil?)
                                       (sort-by :modified >)
                                       vec))))))
             stop?)
            (->> @done (remove nil?) (sort-by :modified >) vec))))
      (catch Exception _ []))))

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

(defn resolve-session-path
  "Resolve a --session argument to a session file path (pi: main.ts
   resolveSessionPath). Path-like values (containing / \\ or ending with
   .ednl/.jsonl) resolve relative to the process cwd; anything else is a
   full session id or id prefix searched among all sessions under BASE-DIR
   (cwd-encoded subdirectories and legacy flat files; an exact id wins over
   a prefix). Returns the canonical path, or nil when nothing matches."
  [arg base-dir]
  (let [arg (eds/expand-path (str arg))
        cwd (str (fs/cwd))]
    (if (or (str/includes? arg "/")
            (str/includes? arg "\\")
            (str/ends-with? arg ".ednl")
            (str/ends-with? arg ".jsonl"))
      (let [p (if (fs/absolute? arg) arg (str (fs/path cwd arg)))]
        (when (fs/exists? p)
          (str (fs/canonicalize p))))
      (let [infos (list-sessions-info base-dir)
            exact (some #(when (= (:id %) arg) (:path %)) infos)
            prefix (some #(when (str/starts-with? (:id %) arg) (:path %)) infos)]
        (or exact prefix)))))

(defn- trash-error-hint
  "The first line of the trash CLI's stderr (capped) for a failed delete."
  [stderr]
  (when-let [line (first (str/split-lines (str/trim (str stderr))))]
    (when (seq line)
      (str "trash: " (subs line 0 (min 200 (count line)))))))

(defn- unlink-session-file
  "Permanently delete PATH. A file already gone is a success (a concurrent
   delete wins); a failure carries HINT when one explains it."
  [path hint]
  (if-not (fs/exists? path)
    {:ok true :method :unlink}
    (try
      (fs/delete path)
      {:ok true :method :unlink}
      (catch Exception e
        {:ok false :method :unlink
         :error (if hint
                  (str (ex-message e) " (" hint ")")
                  (ex-message e))}))))

(defn delete-session-file
  "Delete the session FILE at PATH, trying the `trash` CLI first and falling
   back to permanent deletion (pi: deleteSessionFile). The one deletion
   policy: the session service and the resume selector both call it, so a
   session trashed from the selector is trashed from `/session` too.
   Returns {:ok bool :method :trash|:unlink :error str?}; a file already gone
   is a success (a concurrent delete wins)."
  [path]
  (if-not (fs/which "trash")
    (unlink-session-file path nil)
    (let [args (if (str/starts-with? path "-") ["--" path] [path])
          result (try @(proc/process (into ["trash"] args)
                                     {:out :string :err :string})
                      (catch Exception _ nil))]
      (if (or (and result (zero? (:exit result)))
              (not (fs/exists? path)))
        {:ok true :method :trash}
        (unlink-session-file path (some-> result :err trash-error-hint))))))

(defn delete-session!
  "Delete a session file with the shared policy (trash first, unlink
   fallback). Throws when neither worked, so a backend caller cannot report
   a deletion that did not happen."
  [session]
  (let [f (:file session)
        {:keys [ok error]} (delete-session-file f)]
    (when-not ok
      (throw (ex-info (str "cannot delete session " f ": " error)
                      {:type :session-delete/failed :path f})))
    nil))

;; ─── Import (pi: agent-session-runtime importFromJsonl) ───────────────────

;; ─── Import (pi: agent-session-runtime importFromJsonl) ───────────────────

(defn- unique-destination
  "DEST-DIR/FILE-NAME, `-1`/`-2`/… appended to the stem while the candidate
   is taken (pi: importFromJsonl — the exclusive create retries with a
   suffix)."
  [dest-dir file-name]
  (let [stem (str/replace file-name #"(?i)\.ednl$" "")
        ext (subs file-name (count stem))]
    (loop [n 0]
      (let [candidate (str (fs/path dest-dir (if (zero? n) file-name (str stem "-" n ext))))]
        (if (fs/exists? candidate)
          (recur (inc n))
          candidate)))))

(defn plan-session-import
  "Resolve SOURCE-PATH for import into DEST-DIR (pi: importFromJsonl): the
   source's basename lands in DEST-DIR, `-1`/`-2`/… suffixed when that name
   is taken, and a source already stored there stays in place
   (`:already-stored?` — pi: sourceAlreadyStored). Returns
   {:source <abs path> :path <destination> :already-stored? bool} and has no
   side effects, so the caller can confirm and let extensions cancel
   (:session-before-switch) before anything is written.

   Throws ex-info {:type :session-import/file-not-found} for a missing or
   non-regular source (pi: SessionImportFileNotFoundError) and
   {:type :session-import/not-a-session} for a file that is not a kmet
   session (no :session header — the header carries the identity resume
   lists sessions by, and kmet's sessions are EDN, not pi's JSONL)."
  [source-path dest-dir]
  (let [source (-> (fs/expand-home (str source-path)) fs/absolutize fs/normalize str)
        dest-dir (-> (str dest-dir) fs/absolutize fs/normalize str)]
    (when-not (fs/regular-file? source)
      (throw (ex-info (str "File not found: " source)
                      {:type :session-import/file-not-found :path source})))
    (when-not (read-session-header source)
      (throw (ex-info (str "Not a kmet session file (no :session header): " source)
                      {:type :session-import/not-a-session :path source})))
    (let [dest (-> (fs/path dest-dir (fs/file-name source)) fs/normalize str)
          already-stored? (= source dest)]
      {:source source
       :path (if already-stored? dest (unique-destination dest-dir (fs/file-name source)))
       :already-stored? already-stored?})))

(defn copy-imported-session!
  "Copy an import PLAN's source into place (pi: importFromJsonl —
   copyFileSync + COPYFILE_EXCL): creates the destination directory and
   fails rather than overwrite a file that appeared meanwhile; an
   already-stored source is a no-op. Returns the stored path. Call only
   once :session-before-switch has passed — a cancelled import leaves no
   copy behind."
  [plan]
  (when-not (:already-stored? plan)
    (fs/create-dirs (fs/parent (:path plan)))
    (fs/copy (:source plan) (:path plan)))
  (:path plan))
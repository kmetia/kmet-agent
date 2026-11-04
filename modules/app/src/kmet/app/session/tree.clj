(ns kmet.app.session.tree
  "Session tree operations: branch summaries, labels, the tree projection
   the UI renders, and fork/clone/fork-from."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.session.context :as context]
            [kmet.app.session.store :as store]))

(defn common-ancestor-id
  "The deepest entry on both the OLD-LEAF-ID and TARGET-ID paths (pi:
   collectEntriesForBranchSummary's commonAncestorId), nil when OLD-LEAF-ID
   is nil or the paths are disjoint."
  [session old-leaf-id target-id]
  (when old-leaf-id
    (let [old-path (set (map :id (store/get-branch session old-leaf-id)))]
      (some (fn [e] (when (contains? old-path (:id e)) (:id e)))
            (reverse (store/get-branch session target-id))))))

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
  (store/with-session-lock session
    (when (and branch-from-id
               (not (some #(= (:id %) branch-from-id) @(:entries session))))
      (throw (ex-info (str "Entry " branch-from-id " not found")
                      {:type :entry-not-found :id branch-from-id})))
    (reset! (:leaf-id session) branch-from-id)
    (store/append-entry session
                        (merge {:role :branch-summary
                                :summary summary
                                :from-id (or branch-from-id "root")}
                               (select-keys opts [:details :usage :from-hook])))))

;; ─── Labels (pi: appendLabelChange / getLabel) ────────────────────────────

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
  (store/with-session-lock session
    (when-not (some #(= (:id %) target-id) @(:entries session))
      (throw (ex-info (str "Entry " target-id " not found")
                      {:type :entry-not-found :id target-id})))
    (store/append-entry session {:role :label
                                 :target-id target-id
                                 :label (when (seq (str label)) (str label))})))

(defn get-tree
  "Build a tree structure from session entries.
   Returns a vector of {:id :role :label :summary :children [...]}. O(n):
   children are grouped by parent in one pass, each node map is built once
   and levels are resolved deepest-first — a recursive build overflows the
   stack on a long chain (a 2000-entry session is real), and levels keep
   children ordered like the recursive mapv did."
  [session]
  (let [entries @(:entries session)
        labels (resolve-labels entries)
        by-parent (group-by :parent-id entries)
        base-node (fn [entry]
                    {:id (:id entry)
                     :role (:role entry)
                     :label (get-in labels [(:id entry) :label])
                     :summary (let [content (:content entry)
                                    text (if (string? content)
                                           content
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
                                  (= (:role entry) :tool-change)
                                  (str "[tools: "
                                       (str/join " "
                                                 (concat (map #(str "+" (:name %)) (:added entry))
                                                         (map #(str "-" %) (:removed entry))))
                                       "]")
                                  ;; context edits carry their effect instead of content
                                  ;; (pi: tree-selector's [context omit|replace: id])
                                  (= (:role entry) :context-edit)
                                  (context/context-edit-label entry)
                                  ;; pi getEntryDisplayText: an empty assistant entry
                                  ;; (a recorded empty completion) is labeled
                                  ;; "(no content)" — its only trace anywhere
                                  (= (:role entry) :assistant) "(no content)"
                                  :else "(empty)"))})
        ;; breadth-first levels from the roots; SEEN drops re-visits so a
        ;; malformed cycle cannot loop forever
        levels (loop [level (vec (get by-parent nil))
                      acc []
                      seen #{}]
                 (if (empty? level)
                   acc
                   (let [seen' (into seen (map :id) level)]
                     (recur (into [] (comp (mapcat #(get by-parent (:id %)))
                                           (remove #(contains? seen' (:id %))))
                                  level)
                            (conj acc level)
                            seen'))))
        nodes (reduce (fn [m level]
                        (reduce (fn [m entry]
                                  (assoc m (:id entry)
                                         (assoc (base-node entry)
                                                :children (mapv #(get m (:id %))
                                                                (get by-parent (:id entry))))))
                                m
                                level))
                      {}
                      (rseq levels))]
    (mapv #(get nodes (:id %)) (get by-parent nil))))

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
        target (store/get-entry session entry-id)]
    (when target
      (let [path (store/get-branch session entry-id)
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
                                                  :id (store/generate-id (into #{} (map :id) (concat retained acc)))
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
            fork (store/create-session (str (fs/parent (:file session)))
                                       {:cwd (get-in session [:header :cwd])
                                        :parent-session (:file session)})]
        (store/with-session-lock fork
          (store/write-entries! fork (into retained label-entries)))
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
  (let [source (store/load-session source-path)]
    (when (empty? @(:entries source))
      (throw (ex-info (str "Cannot fork: source session file is empty or invalid: " source-path)
                      {:type :fork-error :path source-path})))
    (when (nil? (:header source))
      (throw (ex-info (str "Cannot fork: source session has no header: " source-path)
                      {:type :fork-error :path source-path})))
    (let [dir (store/session-dir-for-cwd base-dir (str (fs/absolutize target-cwd)))
          fork (store/create-session dir {:cwd (str (fs/absolutize target-cwd))
                                          :parent-session (:file source)})]
      (store/with-session-lock fork
        (store/write-entries! fork @(:entries source)))
      fork)))
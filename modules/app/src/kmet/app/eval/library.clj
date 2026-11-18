(ns kmet.app.eval.library
  "The eval library's model-facing listing: each loaded record reduced to
   its interface keys, the cwd-over-global active flag, the saved/loaded/
   failed counts, and the session's unwritten live entries.")

(defn library-entry
  "One loaded record reduced to its listed interface keys; a failed load is
   marked `:load-status :failed` with its error."
  [record]
  (cond-> (select-keys record [:name :ns :kind :doc :arglists :file :scope])
    (= :failed (:load-status record))
    (assoc :load-status :failed :load-error (:load-error record))))

(defn library-entries
  "The service's `(eval/library)` value: every loaded record, with `:scope`
   and `:active` set. A cwd record shadows the global record of the same
   name."
  [env]
  (->> @(:library env)
       (mapcat (fn [[_ stack]]
                 (map-indexed (fn [i record]
                                (assoc (library-entry record)
                                       :active (= i (dec (count stack)))))
                              stack)))
       (sort-by (juxt :name #(if (= :cwd (:scope %)) 0 1)))
       vec))

(defn failed-entry?
  "True when ENTRY is a saved definition whose file failed to load."
  [entry]
  (= :failed (:load-status entry)))

(defn library-counts
  "The saved/loaded/failed split for library ENTRIES, plus the
   `N saved (L loaded, K failed)` text."
  [entries]
  (let [saved (count entries)
        failed (count (filter failed-entry? entries))
        loaded (- saved failed)]
    {:loaded loaded
     :failed failed
     :text (str saved " saved (" loaded " loaded"
                (when (pos? failed) (str ", " failed " failed")) ")")}))

(defn session-only-entries
  "The session's unwritten live definitions as listing entries: they have no
   library file, so they are discoverable through (eval/help) and
   (eval/describe) but not through (eval/library)."
  [env]
  (->> @(:unwritten-records env)
       (map (fn [[name record]]
              (assoc (library-entry record) :name name :active true)))
       (sort-by :name)
       vec))

(ns kmet.libs.catalog-cache
  "Shared announce-once cache loading for AI model catalogs.")

(defn try-cached
  "Try to READ cache, announcing it on first adoption. Returns nil (and prints
   a warning) when the cache exists but cannot be read."
  [announced-atom cache announce-msg read-fn warn-msg]
  (try
    (when (not= cache @announced-atom)
      (reset! announced-atom cache)
      (println announce-msg cache))
    (read-fn cache)
    (catch Exception e
      (binding [*out* *err*]
        (println warn-msg (ex-message e)))
      nil)))

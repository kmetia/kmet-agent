(ns kmet.app.tools.grep
  "Grep tool implementation — search file contents with a pattern."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.util :as util]))

(defn execute
  "Search file contents with a pattern."
  [{:keys [pattern path]}]
  (try
    (let [f (io/file (util/resolve-tool-path (or path ".")))
          results (volatile! [])
          skipped (volatile! [])]
      (if (fs/regular-file? f)
        (with-open [rdr (io/reader f)]
          (doseq [[idx line] (map-indexed vector (line-seq rdr))]
            (when (re-find (re-pattern pattern) line)
              (vswap! results conj (str f ":" (inc idx) ": " line)))))
        (doseq [file (util/safe-file-seq f)]
          (try
            (with-open [rdr (io/reader (str file))]
              (doseq [[idx line] (map-indexed vector (line-seq rdr))]
                (when (re-find (re-pattern pattern) line)
                  (vswap! results conj (str file ":" (inc idx) ": " line)))))
            (catch Exception _e
              (vswap! skipped conj (str file))))))
      (let [r @results
            sk @skipped]
        (if (and (empty? r) (empty? sk))
          {:content (str "No matches for \"" pattern "\"")}
          (let [base (str/join "\n" (take 100 r))
                sk-msg (when (seq sk)
                         (str "\n\n[Skipped " (count sk) " unreadable files]"))]
            {:content (str base (or sk-msg ""))
             :truncated (> (count r) 100)}))))
    (catch Exception e
      {:content (str "Error searching: " (ex-message e)) :is-error true})))

(ns kmet.app.tools.find
  "Find tool implementation — find files matching a pattern."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.util :as util]))

(defn execute
  "Find files matching a pattern."
  [{:keys [pattern path]}]
  (try
    (let [dir (io/file (util/resolve-tool-path (or path ".")))
          results (volatile! [])]
      (doseq [file (util/safe-file-seq dir)]
        (let [name (fs/file-name file)]
          (when (or (re-find (re-pattern pattern) name)
                    (re-find (re-pattern pattern) (str file)))
            (vswap! results conj (str file)))))
      (let [r @results]
        (if (empty? r)
          {:content (str "No files matching \"" pattern "\"")}
          {:content (str/join "\n" (take 200 r))
           :truncated (> (count r) 200)})))
    (catch Exception e
      {:content (str "Error finding: " (ex-message e)) :is-error true})))

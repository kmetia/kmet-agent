#!/usr/bin/env bb
;; Measures run_code adoption from kmet EDNL session logs.
;;
;;   bb scripts/bench/run_code_adoption.bb [sessions-dir]
;;
;; Prints the tool-call mix, how run_code scripts are shaped (sandbox
;; primitives vs the tools bridge), where the bridge is used, and the result
;; tokens each tool's results cost. The numbers quoted in
;; docs/architecture.md § Tools and the script sandbox come from this script.
;;
;; The directory defaults to ~/.kmet/sessions. Every line is one EDN entry; a
;; line that does not read is counted as bad and skipped, so an older log
;; format or a truncated tail cannot stop the scan.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def sessions-dir (or (first *command-line-args*) (str (fs/home) "/.kmet/sessions")))

(def script-patterns
  "What one run_code script uses. :bridge matches a dispatch; :batch matches
   the retired call-many/await-all helper (old logs still carry it);
   :discovery is the bridge-local listing."
  {:emit        #"\bemit\b"
   :slurp       #"\bslurp\b"
   :sh          #"\(sh |\bp/sh|babashka\.process"
   :fs          #"\bfs/"
   :regex       #"re-seq|re-find"
   :pmap        #"\(pmap "
   :concurrency #"\(future|\(spawn "
   :batch       #"call-many|await-all"
   :bridge      #"\(tools/(call|await)"
   :discovery   #"\(tools/(list|describe)"})

(def state
  (atom {:files 0 :entries 0 :bad 0 :calls {} :tokens {} :token-entries 0
         :tool-results 0 :run-code 0 :script {} :bridge-sites []
         :bridge-tools {} :bridge-sessions #{} :run-code-sessions #{}}))

(defn note-entry! [file e]
  (swap! state update :entries inc)
  (doseq [{:keys [name arguments]} (:tool-calls e)]
    (swap! state update-in [:calls name] (fnil inc 0))
    (when (= name "run_code")
      (let [code (str (:code arguments))]
        (swap! state update :run-code inc)
        (swap! state update :run-code-sessions conj file)
        (doseq [[k re] script-patterns]
          (when (re-find re code) (swap! state update-in [:script k] (fnil inc 0))))
        (when (re-find (:bridge script-patterns) code)
          (swap! state update :bridge-sessions conj file)
          ;; one site per dispatch call, not per entry
          (doseq [target (map second (re-seq #"\(tools/(?:call|call-many)[^\"]*\"([^\"]+)\"" code))]
            (swap! state update :bridge-sites conj {:file file :tool target})
            (swap! state update-in [:bridge-tools target] (fnil inc 0)))))))
  (when (= :tool (:role e))
    (swap! state update :tool-results inc)
    (when (and (:tool-name e) (number? (:result-tokens e)))
      (swap! state update :token-entries inc)
      (swap! state update-in [:tokens (:tool-name e)] (fnil + 0) (:result-tokens e)))))

(defn scan-file! [f]
  (swap! state update :files inc)
  (doseq [line (str/split-lines (slurp (str f)))
          :when (not (str/blank? line))]
    (let [e (try (edn/read-string line) (catch Exception _ nil))]
      (if (map? e)
        (note-entry! (str (fs/file-name (str f))) e)
        (swap! state update :bad inc)))))

(doseq [f (fs/glob sessions-dir "**.ednl")] (scan-file! f))

(let [{:keys [files entries bad calls tokens run-code script bridge-sites
              bridge-tools bridge-sessions run-code-sessions token-entries
              tool-results]} @state
      total (reduce + 0 (vals calls))
      pct (fn [n d] (if (pos? d) (* 100.0 (/ n d)) 0.0))
      top (fn [m n] (->> m (sort-by val >) (take n)))]
  (println (format "sessions: %d files, %d entries (%d unreadable)" files entries bad))
  (println (format "tool calls: %d" total))
  (doseq [[name n] (top calls 8)]
    (println (format "  %-12s %7d  %5.1f%%" name n (pct n total))))
  (println)
  (println (format "run_code: %d calls in %d sessions (%.2f%% of tool calls)"
                   run-code (count run-code-sessions) (pct run-code total)))
  (println (format "  bridge: %d call sites in %d sessions (%.1f%% of calls), batches: %d"
                   (count bridge-sites) (count bridge-sessions)
                   (pct (count bridge-sites) run-code) (get script :batch 0)))
  (println "  bridge targets:" (if (seq bridge-tools)
                                 (into (sorted-map) (top bridge-tools 6))
                                 "none"))
  (println (format "  script shape: %s"
                   (->> script
                        (sort-by (comp - val))
                        (map (fn [[k n]] (str (name k) "=" n)))
                        (str/join "  "))))
  (println)
  (let [token-total (reduce + 0 (vals tokens))]
    (println (format "result tokens: %d (%d of %d tool results carry the field)"
                     token-total token-entries tool-results))
    (doseq [[name n] (top tokens 6)]
      (println (format "  %-12s %9d  %5.1f%%" name n (pct n token-total))))))

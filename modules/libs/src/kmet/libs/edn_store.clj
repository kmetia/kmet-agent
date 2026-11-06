(ns kmet.libs.edn-store
  "EDN settings and credential store primitives: file locking, pretty-
   printing, deep merge, path expansion, text-surgery persistence, and
   lenient read/write/update under file lock.

   Combines the former kmet.libs.file-lock, kmet.libs.edn-settings, and
   kmet.libs.credential-store into a single module — they form a tight
   dependency chain for EDN file persistence."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── File locking ──────────────────────────────────────────────────────────

(def ^:private lock-stale-ms 10000)
(def ^:private lock-acquire-attempts 10)
(def ^:private lock-acquire-delay-ms 20)

(defn- acquire-lock!
  [lock-path]
  (loop [attempt 1]
    (cond
      (try (fs/create-dir lock-path) true (catch Exception _ false)) :ok
      ;; Stale lock (mtime older than LOCK-STALE-MS): break it and retry —
      ;; the attempt counter MUST advance, or a single failed delete-tree
      ;; would loop forever on the calling thread (a frozen app with no
      ;; crash log; e.g. a lock dir left by a force-killed run that cannot
      ;; be deleted). Bounded either way below.
      (and (< attempt lock-acquire-attempts)
           (try (> (- (System/currentTimeMillis) (.toMillis (fs/last-modified-time lock-path)))
                   lock-stale-ms)
                (catch Exception _ false)))
      (do (fs/delete-tree lock-path)
          (Thread/sleep lock-acquire-delay-ms)
          (recur (inc attempt)))
      (< attempt lock-acquire-attempts)
      (do (Thread/sleep lock-acquire-delay-ms) (recur (inc attempt)))
      :else (throw (ex-info "Timed out acquiring file lock"
                            {:type :file-lock-timeout :path lock-path})))))

(defn with-file-lock
  "Run F with the lock at LOCK-PATH held (directory lock; stale locks older
   than 10s are broken)."
  [lock-path f]
  (acquire-lock! lock-path)
  (try (f)
       (finally (fs/delete-tree lock-path))))

;; ─── Path expansion ────────────────────────────────────────────────────────

(defn expand-path
  "Expand a ~ prefix to user.home. Absolute and relative paths pass through."
  [path]
  (let [s (str path)]
    (if (str/starts-with? s "~")
      (str (System/getProperty "user.home") (subs s 1))
      s)))

;; ─── Deep merge ────────────────────────────────────────────────────────────

(defn deep-merge
  "Recursively merge maps: nested maps merge key-by-key, non-map values from
   later maps win. Vectors/lists are replaced, not merged (pi: 'Nested
   objects are merged' — only objects merge)."
  [& maps]
  (reduce (fn [acc m]
            (if (map? m)
              (merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) acc m)
              acc))
          {} maps))

;; ─── Pretty EDN ────────────────────────────────────────────────────────────

(defn pretty-edn
  "Format a map as EDN with each top-level entry on its own line and
   the closing brace on its own line (pi: JSON.stringify(settings, null, 2))."
  [m]
  (str "{" (str/join "\n " (for [[k v] m] (str (pr-str k) " " (pr-str v)))) "\n}\n"))

;; ─── Lenient parsing ───────────────────────────────────────────────────────

(defn safe-parse-edn-map
  "Parse text as an EDN map, nil when malformed or not a map."
  [text]
  (when-let [parsed (try (edn/read-string text) (catch Exception _ nil))]
    (when (map? parsed) parsed)))

;; ─── Text-surgery persistence ──────────────────────────────────────────────

(defn- setting-line
  "EDN text for one pretty entry, e.g. \" :thinking-mode :hidden\"."
  [key value]
  (str " " (pr-str key) " " (pr-str value)))

(defn- top-level-key-line?
  "True when LINE is a top-level KEY entry of an EDN map (key at
   line start, possibly indented)."
  [key line]
  (boolean (re-matches (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote (pr-str key)) "\\s+.*"))
                       line)))

(defn update-setting-text
  "Return EDN text with the top-level KEY entry set to VALUE, preserving
   unrelated lines (hand-written comments): the key's line is replaced when
   present, otherwise a new line is inserted with the closing brace on its own
   line (canonical pretty format), so later updates stay in-place."
  [text key value]
  (let [line (setting-line key value)
        lines (str/split-lines text)
        idx (first (keep-indexed (fn [i l] (when (top-level-key-line? key l) i)) lines))
        trailing-nl? (str/ends-with? text "\n")]
    (if idx
      (str (str/join "\n" (assoc (vec lines) idx line))
           (when trailing-nl? "\n"))
      (when-let [i (str/last-index-of text "}")]
        (if (= \newline (get text (dec i)))
          (str (subs text 0 (dec i)) "\n" line "\n}" (subs text (inc i)))
          (str (subs text 0 i) "\n" line "\n}" (subs text (inc i))))))))

(defn save-edn-setting!
  "Persist a single top-level setting to FILE-PATH. PATH is a key path
   (e.g. [:thinking-mode]); only that leaf is merged, so unrelated
   keys survive. Hand-written comments are preserved via line surgery; the
   file is rewritten in a canonical pretty format when surgery is unsafe
   (malformed or one-line files, nested paths). File is locked during write."
  [file-path path value]
  (let [path (if (vector? path) path [path])
        file (io/file file-path)]
    (fs/create-dirs (fs/parent file-path))
    (with-file-lock (str file-path ".lock")
      (fn []
        (if-not (fs/exists? file)
          (spit file-path (pretty-edn (assoc-in {} path value)))
          (let [text (slurp file-path)
                base (or (safe-parse-edn-map text) {})
                edited (when (= 1 (count path))
                         (update-setting-text text (peek path) value))
                edited-ok? (and edited
                                (let [parsed (safe-parse-edn-map edited)]
                                  (and parsed
                                       (= parsed (assoc-in base path value)))))]
            (if edited-ok?
              (spit file-path edited)
              (spit file-path
                    (pretty-edn (assoc-in base path value))))))))))

(defn- dissoc-in
  "Remove PATH's leaf from M, pruning maps left empty by the removal."
  [m [k & ks]]
  (if ks
    (let [child (get m k)]
      (if (map? child)
        (let [child' (dissoc-in child ks)]
          (if (empty? child')
            (dissoc m k)
            (assoc m k child')))
        m))
    (dissoc m k)))

(defn remove-edn-setting!
  "Remove PATH's leaf from FILE-PATH, pruning maps left empty by the removal;
   a missing file or path is a no-op. Unrelated keys survive, but a nested
   path rewrites the file in canonical pretty format (the same fallback
   `save-edn-setting!` uses for nested paths). File is locked during write."
  [file-path path]
  (let [path (if (vector? path) path [path])]
    (when (fs/exists? file-path)
      (with-file-lock (str file-path ".lock")
        (fn []
          (let [base (or (safe-parse-edn-map (slurp file-path)) {})
                updated (dissoc-in base path)]
            (when (not= base updated)
              (spit file-path (pretty-edn updated)))))))))

;; ─── EDN map store (read/write/update under lock) ─────────────────────────

(defn read-edn-map
  "Parse an EDN file as a map, nil when missing or malformed. When VALIDATE
   is provided, only entries passing it are kept (invalid entries are
   silently dropped — startup leniency)."
  ([path] (read-edn-map path nil))
  ([path validate]
   (let [f (fs/file path)]
     (when (fs/exists? f)
       (try (let [parsed (edn/read-string (slurp f))]
              (when (map? parsed)
                (if validate
                  (into {} (filter (fn [[_ v]] (validate v))) parsed)
                  parsed)))
            (catch Exception _ nil))))))

(defn write-edn-map!
  "Write a map to PATH as pretty EDN, under a file lock. Creates parent
   directories as needed."
  [path m]
  (fs/create-dirs (fs/parent path))
  (with-file-lock (str path ".lock")
    (fn [] (spit path (pretty-edn m)))))

(defn update-edn-map!
  "Read-modify-write: apply F to the current map on disk, persist the result
   under the file lock, return the new map. The read and write are both
   inside the lock so concurrent callers can't lose each other's updates.
   When VALIDATE is provided, the read pass filters entries through it."
  ([path f] (update-edn-map! path f nil))
  ([path f validate]
   (fs/create-dirs (fs/parent path))
   (with-file-lock (str path ".lock")
     (fn []
       (let [current (or (read-edn-map path validate) {})
             updated (f current)]
         (spit path (pretty-edn updated))
         updated)))))

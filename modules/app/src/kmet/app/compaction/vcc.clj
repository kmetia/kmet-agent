(ns kmet.app.compaction.vcc
  "Deterministic VCC compactor: a no-LLM summary of the prepared prefix.

   The compactor consumes the projection-aware preparation produced by
   kmet.app.compaction.core (the same cut both builtin compactors use) and
   emits fixed sections — compaction instructions, early requests, files and
   changes, commits, user messages, branch summaries, an optional preserved
   previous summary, and a brief transcript. It never calls the model and never re-reads the
   session. Caps keep the output bounded; merging keeps a previous VCC summary
   alive across compactions. A previous summary in another format (for example
   the LLM compactor's Goal/Progress checkpoint) is preserved verbatim in
   [Previous Summary] rather than discarded."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.compaction.core :as core]
            [kmet.app.session.context :as context]
            [kmet.srce.core :as srce]))

(def ^:private max-summary-chars 12000)
(def ^:private max-brief-lines 120)
(def ^:private max-early 3)
(def ^:private max-users 15)
(def ^:private max-files 10)
(def ^:private max-commits 8)
(def ^:private max-tool-lines 8)
(def ^:private max-instructions-chars 1000)
(def ^:private max-previous-chars 4000)
(def ^:private max-branch-chars 4000)
(def ^:private max-branch-entry-chars 2000)
(def ^:private skip-custom-types
  #{context/tool-sources-custom-type context/eval-library-custom-type})

;; ─── Text ────────────────────────────────────────────────────────────────

(defn- text-of-content
  "Plain text of a message content string or block vector. Tool-result blocks
   carry their text in :content (a string or a nested block vector)."
  [content]
  (cond
    (string? content) content
    (nil? content) ""
    (vector? content)
    (str/join "\n"
              (keep (fn [b]
                      (case (:type b)
                        :text (:text b)
                        :tool_result (text-of-content (:content b))
                        nil))
                    content))
    :else (str content)))

(defn- content-summary
  "A message's text plus a compact image marker."
  [content]
  (let [text (text-of-content content)
        images (if (vector? content)
                 (count (filter #(= :image (:type %)) content))
                 0)]
    (str/join " "
              (cond-> []
                (seq (str/trim text)) (conj text)
                (pos? images) (conj (str "[" images " image(s)]"))))))

(defn- sanitize
  "Strip ANSI/control bytes and normalize line endings."
  [s]
  (-> (or s "")
      (str/replace #"\u001b\[[0-9;?]*[ -/]*[@-~]" "")
      (str/replace #"[\u0000-\u0008\u000B\u000C\u000E-\u001F]" "")
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")
      str/trim))

(defn- one-line [s]
  (str/trim (str/replace (sanitize s) #"\s+" " ")))

(defn- clip
  "One line, clipped head/tail to MAX characters."
  [s max]
  (let [s (one-line s)]
    (if (<= (count s) max)
      s
      (let [head (max 1 (- max 20))]
        (str (subs s 0 head) " ... " (subs s (- (count s) 15)))))))

(defn- clip-block
  "Multi-line text clipped to MAX characters."
  [s max]
  (let [s (sanitize s)]
    (if (<= (count s) max)
      s
      (str (subs s 0 (- max 30)) "\n...[text truncated]"))))

;; ─── Tool calls ──────────────────────────────────────────────────────────

(defn- args-map [tc]
  (let [a (:arguments tc)]
    (cond
      (map? a) a
      (string? a) (try (edn/read-string a) (catch Exception _ {}))
      :else {})))

(defn- arg [tc k]
  (let [a (args-map tc)]
    (or (get a k) (get a (name k)))))

(defn- tool-name [tc]
  (str/lower-case (str (or (:name tc) ""))))

(defn- tool-value [tc]
  (or (arg tc :path)
      (arg tc :command)
      (arg tc :pattern)
      (arg tc :query)))

(defn- tool-call-line
  "One tool-call line, marked when its result failed."
  [tc result]
  (let [v (tool-value tc)
        line (str "* " (or (:name tc) "tool")
                  (when (seq (str v))
                    (str " \"" (clip (str v) 160) "\"")))]
    (if (:is-error result)
      (let [error (clip (:text result) 120)]
        (str line " [failed" (when (seq error) (str ": " error)) "]"))
      line)))

;; ─── Normalization ───────────────────────────────────────────────────────

(defn- tool-result-id [m]
  (let [c (:content m)]
    (when (vector? c) (-> c first :tool_use_id))))

(defn- tool-result-text [m]
  (text-of-content (:content m)))

(defn- results-by-id [messages]
  (into {}
        (keep (fn [m]
                (when (= :tool (:role m))
                  (when-let [id (tool-result-id m)]
                    [id {:text (tool-result-text m)
                         :is-error (boolean (:is-error m))}]))))
        messages))

(defn- normalize-blocks
  "Session projection messages to the block model the summary sections read.
   Tool results are collected separately for commit hashes, failed-file
   filtering, and brief failure markers."
  [messages]
  (reduce (fn [acc m]
            (case (:role m)
              :user
              (let [t (content-summary (:content m))]
                (if (seq t) (conj acc {:role :user :text t}) acc))

              :assistant
              (let [t (content-summary (:content m))
                    tcs (vec (:tool-calls m))]
                (if (or (seq t) (seq tcs))
                  (conj acc {:role :assistant :text t :tool-calls tcs})
                  acc))

              :bash
              (if (:exclude-from-context? m)
                acc
                (let [edited (when (and (vector? (:content m))
                                        (seq (text-of-content (:content m))))
                               (text-of-content (:content m)))]
                  (conj acc {:role :bash
                             :command (:command m)
                             :text (or edited (:command m))
                             :output (:output m)
                             :exit-code (:exit-code m)
                             :cancelled (:cancelled m)})))

              :branch-summary
              (let [t (sanitize (:summary m))]
                (if (seq t)
                  (conj acc {:role :branch-summary :text t})
                  acc))

              :custom
              (if (contains? skip-custom-types (:custom-type m))
                acc
                (let [t (content-summary (:content m))]
                  (if (seq t)
                    (conj acc {:role :custom
                               :custom-type (:custom-type m)
                               :text t})
                    acc)))

              :tool acc
              acc))
          [] messages))

;; ─── Sections ────────────────────────────────────────────────────────────

(defn- user-texts [blocks]
  (vec (keep #(when (= :user (:role %)) (:text %)) blocks)))

(defn- file-lists [blocks results]
  (reduce (fn [acc b]
            (if (= :assistant (:role b))
              (reduce (fn [acc tc]
                        (let [name (tool-name tc)
                              path (some-> (arg tc :path) str)
                              result (get results (:id tc))
                              failed? (:is-error result)]
                          (if (and (seq path)
                                   (some? result)
                                   (not failed?)
                                   (contains? #{"read" "edit" "write"} name))
                            (update acc (if (= "read" name) :read :modified)
                                    conj path)
                            acc)))
                      acc
                      (:tool-calls b))
              acc))
          {:modified [] :read []}
          blocks))

(defn- git-commit? [cmd]
  (boolean (re-find #"(?:^|[;&|(\n])\s*(?:sudo\s+)?git\s+commit(?=\s|$)"
                    (or cmd ""))))

(def ^:private commit-subject-patterns
  [#"-[a-zA-Z]*m\s+\"([^\"]*)\""
   #"-[a-zA-Z]*m\s+'([^']*)'"
   #"-[a-zA-Z]*m\s+(\S+)"
   #"--message=\s*\"([^\"]*)\""
   #"--message=\s*'([^']*)'"
   #"--message=\s*(\S+)"
   #"--message\s+\"([^\"]*)\""
   #"--message\s+'([^']*)'"
   #"--message\s+(\S+)"])

(defn- commit-subject [cmd]
  (some #(second (re-find % cmd)) commit-subject-patterns))

(def ^:private commit-hash-patterns
  [#"\[[^\]]*?\b([0-9a-f]{7,40})\b[^\]]*\]"
   #"\b([0-9a-f]{7,40})\.\.([0-9a-f]{7,40})\b"
   #"^\s*([0-9a-f]{7,40})\b"])

(defn- commit-hash [text]
  (let [text (or text "")]
    (some #(second (re-find % text)) commit-hash-patterns)))

(defn- commit-line [cmd result-text]
  (when (git-commit? cmd)
    (let [subject (some-> (commit-subject cmd) (str/split #"\n") first str/trim)
          amend? (boolean (re-find #"--amend\b" cmd))
          hash (commit-hash result-text)
          subject (or subject (when amend? "amend"))]
      (when subject
        (str "- "
             (when (seq hash) (str hash ": "))
             subject)))))

(defn- commit-lines [blocks results]
  (reduce (fn [acc b]
            (case (:role b)
              :assistant
              (into acc
                    (keep (fn [tc]
                            (when (= "bash" (tool-name tc))
                              (let [cmd (or (arg tc :command) "")
                                    result (get results (:id tc))]
                                (when (and (git-commit? cmd)
                                           (some? result)
                                           (not (:is-error result)))
                                  (commit-line cmd (:text result)))))))
                    (:tool-calls b))

              :bash
              (if (and (not (:cancelled b))
                       (or (nil? (:exit-code b))
                           (zero? (:exit-code b)))
                       (git-commit? (:command b)))
                (if-let [line (commit-line (:command b) (:output b))]
                  (conj acc line)
                  acc)
                acc)

              acc))
          [] blocks))

(defn- sections-present [merged brief]
  (cond-> []
    (seq (:instructions merged)) (conj :instructions)
    (seq (:early merged)) (conj :early)
    (or (seq (get-in merged [:files :modified]))
        (seq (get-in merged [:files :read]))) (conj :files)
    (seq (:commits merged)) (conj :commits)
    (seq (:users merged)) (conj :users)
    (seq (:branch merged)) (conj :branch)
    (seq (:previous merged)) (conj :previous)
    (seq brief) (conj :brief)))

;; ─── Previous-summary parsing and merging ────────────────────────────────

(def ^:private section-headers
  {"[Early Session Requests]" :early
   "[Files And Changes]" :files
   "[Commits]" :commits
   "[User Messages]" :users
   "[Compaction Instructions]" :instructions
   "[Branch Summary]" :branch
   "[Previous Summary]" :previous
   "[Transcript]" :brief})

(def ^:private transcript-separator "\n\n[Transcript]\n\n")

(defn- tagged
  [tag text]
  (when text (str "<" tag ">\n" text "\n</" tag ">")))

(defn- extract-tag
  "Extract the first <TAG>...</TAG> block from S, returning [content S'].
   Content may contain section headers or separators without confusing the
   line parser."
  [s tag]
  (let [pattern (re-pattern (str "(?s)<" tag ">\\s*(.*?)\\s*</" tag ">"))]
    (if-let [[_ content] (re-find pattern s)]
      [content (str/replace s pattern "")]
      [nil s])))

(defn- parse-previous
  "Parse a previous VCC summary. Multi-line sections are read from their
   <previous-summary>/<branch-summary> tags, so their content can contain
   headers or separators. A summary without a VCC header or tag is preserved
   verbatim under :previous."
  [summary]
  (let [s (sanitize summary)
        [previous-block s1] (extract-tag s "previous-summary")
        [branch-block s2] (extract-tag s1 "branch-summary")
        lines (str/split-lines s2)
        first-line (some #(when (seq (str/trim %)) (str/trim %)) lines)
        header? (contains? section-headers first-line)]
    (when (seq s)
      (if-not (or header? previous-block branch-block)
        {:previous (clip-block s max-previous-chars)}
        (let [parsed
              (reduce (fn [acc line]
                        (let [line (str/trim line)]
                          (cond
                            (contains? section-headers line)
                            (assoc acc :current (section-headers line))

                            (and (= line "---") (:current acc))
                            (assoc acc :current :brief)

                            (empty? line) acc

                            :else
                            (let [current (:current acc)
                                  value (if (str/starts-with? line "- ")
                                          (subs line 2)
                                          line)]
                              (case current
                                :early (update acc :early conj value)
                                :files
                                (cond
                                  (str/starts-with? line "- Modified: ")
                                  (update-in acc [:files :modified] conj (subs line 12))
                                  (str/starts-with? line "- Read: ")
                                  (update-in acc [:files :read] conj (subs line 8))
                                  :else acc)
                                :commits (update acc :commits conj line)
                                :users (update acc :users conj value)
                                :instructions (update acc :instructions conj value)
                                :branch (update acc :branch conj line)
                                :previous (update acc :previous conj line)
                                :brief (update acc :brief conj line)
                                acc)))))
                      {:current nil
                       :early []
                       :files {:modified [] :read []}
                       :commits []
                       :users []
                       :instructions []
                       :branch []
                       :previous []
                       :brief []}
                      lines)
              previous (or (when (seq previous-block)
                             (clip-block previous-block max-previous-chars))
                           (when (seq (:previous parsed))
                             (clip-block (str/join "\n" (:previous parsed))
                                         max-previous-chars)))
              branch (cond
                       (seq branch-block)
                       (clip-block branch-block max-branch-chars)

                       (seq (:branch parsed))
                       (clip-block (str/join "\n" (:branch parsed))
                                   max-branch-chars)
                       :else nil)]
          (-> parsed
              (assoc :previous previous
                     :branch (when branch [branch]))
              (dissoc :current)))))))

(defn- merge-newest
  "Union OLD then NEW, move a repeated key to the newest position, keep CAP."
  [old new key-fn cap]
  (->> (concat old new)
       (reduce (fn [acc x]
                 (conj (vec (remove #(= (key-fn %) (key-fn x)) acc)) x))
               [])
       (take-last cap)
       vec))

(defn- commit-key [line]
  (or (second (re-find #"^- ([0-9a-f]{7,40}):" line)) line))

(defn- merge-summary [previous fresh custom-instructions]
  (let [prev (or previous {})
        files (get prev :files {})]
    {:instructions (cond
                     (seq custom-instructions)
                     [(clip custom-instructions max-instructions-chars)]
                     (seq (:instructions prev)) (:instructions prev)
                     :else [])
     :early (if (seq (:early prev)) (:early prev) (:early fresh))
     :files {:modified (merge-newest (vec (:modified files)) (:modified (:files fresh))
                                     identity max-files)
             :read (merge-newest (vec (:read files)) (:read (:files fresh))
                                 identity max-files)}
     :commits (merge-newest (:commits prev) (:commits fresh) commit-key max-commits)
     :users (merge-newest (:users prev) (:users fresh) identity max-users)
     :branch (let [old (vec (:branch prev))
                   new (vec (:branch fresh))]
               (when (seq (concat old new))
                 [(clip-block (str/join "\n\n" (concat old new))
                              max-branch-chars)]))
     :previous (or (:previous prev) (:previous fresh))}))

;; ─── Brief transcript and formatting ─────────────────────────────────────

(defn- brief-lines [blocks results]
  (mapcat (fn [b]
            (case (:role b)
              :user [(str "[user] " (clip (:text b) 400))]

              :assistant
              (let [text (:text b)
                    tcs (:tool-calls b)
                    kept (take-last max-tool-lines tcs)
                    omitted (- (count tcs) (count kept))]
                (cond-> []
                  (seq text) (conj (str "[assistant] " (clip text 400)))
                  (pos? omitted) (conj (str "* (" omitted
                                            " earlier tool-call entries omitted)"))
                  (seq kept) (into (map #(tool-call-line % (get results (:id %))) kept))))

              :bash
              [(str "$ " (clip (or (:text b) (:command b) "") 300)
                    (when (and (some? (:exit-code b))
                               (not (zero? (:exit-code b))))
                      (str " (exit " (:exit-code b) ")")))]

              :custom
              [(str "[extension:"
                    (let [ct (:custom-type b)]
                      (if (keyword? ct) (subs (str ct) 1) (str ct)))
                    "] " (clip (:text b) 400))]

              []))
          blocks))

(defn- collapse-runs [lines]
  (->> (reduce (fn [acc line]
                 (if (and (seq acc) (= line (:line (peek acc))))
                   (update acc (dec (count acc)) update :n inc)
                   (conj acc {:line line :n 1})))
               []
               lines)
       (mapv (fn [{:keys [line n]}]
               (if (> n 1) (str line " (x" n ")") line)))))

(defn- cap-lines [lines max]
  (if (<= (count lines) max)
    lines
    (let [kept (take-last (dec max) lines)]
      (into [(str "...(" (- (count lines) (count kept))
                  " earlier lines omitted)")]
            kept))))

(defn- fit-brief
  "Keep the newest brief lines that fit BUDGET, adding an omission note when
   older lines were dropped."
  [lines budget]
  (loop [i (dec (count lines))
         remaining budget
         kept []]
    (if (neg? i)
      (let [dropped (- (count lines) (count kept))
            note (str "...(" dropped " earlier lines omitted)")
            note-cost (+ (count note) (if (seq kept) 1 0))]
        (if (and (pos? dropped) (<= note-cost remaining))
          (cons note kept)
          kept))
      (let [line (nth lines i)
            cost (+ (count line) (if (seq kept) 1 0))]
        (if (<= cost remaining)
          (recur (dec i) (- remaining cost) (cons line kept))
          (if (and (zero? (count kept))
                   (pos? budget))
            ;; a single line longer than the whole budget: clip it once
            (let [clipped (clip line (max 1 (- budget 3)))]
              (recur -1 remaining [clipped]))
            (recur -1 remaining kept)))))))

(defn- section [title lines]
  (when (seq lines)
    (str title "\n" (str/join "\n" lines))))

(defn- render-sections [merged]
  (let [file-lines (concat (map #(str "- Modified: " %)
                                (get-in merged [:files :modified]))
                           (map #(str "- Read: " %)
                                (get-in merged [:files :read])))]
    (str/join "\n\n"
              (keep identity
                    [(section "[Compaction Instructions]"
                              (map #(str "- " %) (:instructions merged)))
                     (section "[Early Session Requests]"
                              (map #(str "- " %) (:early merged)))
                     (section "[Files And Changes]" file-lines)
                     (section "[Commits]" (:commits merged))
                     (section "[User Messages]"
                              (map #(str "- " %) (:users merged)))
                     (section "[Branch Summary]"
                              (when-let [branch (first (:branch merged))]
                                [(tagged "branch-summary" branch)]))
                     (section "[Previous Summary]"
                              (when-let [previous (:previous merged)]
                                [(tagged "previous-summary" previous)]))]))))

(defn- format-summary [merged brief]
  (let [body (render-sections merged)]
    (str body
         (when (seq brief)
           (str (when (seq body) "\n\n") "[Transcript]\n\n"
                (str/join "\n" brief))))))

(defn- build-summary [merged brief]
  (let [sections (render-sections merged)
        budget (- max-summary-chars (count sections)
                  (if (seq sections) (count transcript-separator) 0))
        fitted (if (seq brief) (fit-brief brief budget) [])
        summary (format-summary merged fitted)
        summary (if (> (count summary) max-summary-chars)
                  (str (subs summary 0 (- max-summary-chars 22))
                       "\n...[summary truncated]")
                  summary)
        brief-text (when (seq fitted) (str/join "\n" fitted))
        included (when (and brief-text (str/ends-with? summary brief-text))
                   fitted)]
    {:summary summary :brief included}))

(defn- empty-summary []
  "[User Messages]\n- (no renderable user messages)")

;; ─── The compactor ───────────────────────────────────────────────────────

(defn summarize
  "Deterministic summary of PREP's projected messages. CUSTOM-INSTRUCTIONS
   are rejected by the orchestrator for VCC; they are accepted here only so
   a direct caller cannot lose text silently: non-blank text becomes the
   [Compaction Instructions] section."
  [_agent prep custom-instructions _reason]
  (let [messages (:messages prep)
        results (results-by-id messages)
        blocks (normalize-blocks messages)
        previous (parse-previous (:previous-summary prep))
        users (user-texts blocks)
        fresh {:early (vec (take max-early users))
               :users (vec (take-last max-users users))
               :files (file-lists blocks results)
               :commits (commit-lines blocks results)
               :branch (vec (keep #(when (= :branch-summary (:role %))
                                     (clip-block (:text %)
                                                 max-branch-entry-chars))
                                  blocks))}
        merged (merge-summary previous fresh custom-instructions)
        fresh-brief (cap-lines (collapse-runs (brief-lines blocks results))
                               max-brief-lines)
        brief (cap-lines (concat (:brief previous) fresh-brief)
                         max-brief-lines)
        built (build-summary merged brief)
        summary (:summary built)
        empty? (str/blank? summary)
        summary (if empty? (empty-summary) summary)]
    {:summary summary
     :details {:compactor :vcc
               :version 1
               :sections (sections-present merged (:brief built))
               :source-message-count (count messages)
               :summary-chars (count summary)
               :empty? empty?}}))

(def strategy
  "The builtin VCC compactor: the shared cut plus the deterministic summary.
   It cannot apply free-form instructions; the orchestrator rejects them via
   :supports-custom-instructions?."
  {:prepare core/prepare-with-settings
   :summarize summarize
   :supports-custom-instructions? false})

(defn apply-row
  "Provide the VCC compactor as the `:compactor/vcc` service. No :config:
   the compactor has no settings of its own."
  [ctx config]
  (when (some? config)
    (throw (ex-info "the :compactor/vcc row takes no :config"
                    {:type :kmet.app/bad-compactor-config
                     :config config})))
  (srce/provide! ctx :compactor/vcc strategy {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :compactor/vcc
   :provide #{:compactor/vcc}
   :apply apply-row})

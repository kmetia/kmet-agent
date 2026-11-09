(ns kmet.app.compaction.vcc
  "Deterministic VCC compactor: a no-LLM summary of the prepared prefix.

   The compactor consumes the projection-aware preparation produced by
   kmet.app.compaction.core (the same cut both builtin compactors use) and
   emits fixed sections — compaction instructions, early requests, files and
   changes, commits, user messages, branch summaries, an optional preserved
   previous summary, and a brief transcript. It never calls the model and never re-reads the
   session. Caps keep the output bounded; user messages are one-lined and
   clipped, skill blocks collapse to a tag, and the brief is ranked by signal
   under a size-relative budget. Merging keeps a previous VCC summary alive
   across compactions. A previous summary in another format (for example the
   LLM compactor's Goal/Progress checkpoint) is preserved verbatim in
   [Previous Summary] rather than discarded."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.compaction.core :as core]
            [kmet.app.session.context :as context]
            [kmet.srce.core :as srce]))

(def ^:private max-summary-chars 12000)
(def ^:private max-early 3)
(def ^:private max-users 15)
(def ^:private max-files 10)
(def ^:private max-commits 8)
(def ^:private max-tool-lines 8)
(def ^:private max-instructions-chars 1000)
(def ^:private max-previous-chars 4000)
(def ^:private max-branch-chars 4000)
(def ^:private max-branch-entry-chars 2000)
(def ^:private max-user-chars 300)
(def ^:private max-ranked-blocks 80)
(def ^:private preserve-recent-blocks 16)
(def ^:private brief-budget-floor-chars 4400)
(def ^:private brief-budget-ceiling-chars 8000)
(def ^:private brief-chars-per-block 60)
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
  "One line, clipped head/tail to LIMIT characters."
  [s limit]
  (let [s (one-line s)
        limit (clojure.core/max 1 (long limit))]
    (if (<= (count s) limit)
      s
      (let [head (clojure.core/max 1 (- limit 20))
            tail (clojure.core/max 0 (clojure.core/min 15 (- limit head 5)))]
        (if (pos? tail)
          (str (subs s 0 head) " ... " (subs s (- (count s) tail)))
          (subs s 0 limit))))))

(defn- clip-block
  "Multi-line text clipped to MAX characters."
  [s max]
  (let [s (sanitize s)]
    (if (<= (count s) max)
      s
      (str (subs s 0 (- max 30)) "\n...[text truncated]"))))

(defn- clip-head
  "One line clipped at LIMIT characters, keeping the beginning."
  [s limit]
  (let [s (one-line s)]
    (if (<= (count s) limit) s (subs s 0 limit))))

;; ─── Skill and noise filtering (pi-vcc: skill-collapse/filter-noise) ─────

(def ^:private skill-block-pattern
  #"(?s)<skill\s+name=\"([^\"]+)\"[^>]*>.*?(?:</skill>|$)")

(defn- collapse-skill-text
  "Replace each <skill name=\"X\">…</skill> block with [skill: X]."
  [text]
  (str/replace (or text "") skill-block-pattern "[skill: $1]"))

(def ^:private noise-tool-names
  #{"todowrite" "todoread" "toolsearch" "websearch" "askuser"
    "exitspecmode" "generatedroid"})

(def ^:private noise-user-strings
  ["Continue from where you left off."
   "No response requested."
   "IMPORTANT: TodoWrite was not called yet."])

(def ^:private xml-wrapper-pattern
  #"(?s)<(system-reminder|ide_opened_file|command-message|context-window-usage)[^>]*>.*?</\1>")

(defn- clean-user-text
  "Remove injected XML wrappers and surrounding whitespace from user text."
  [text]
  (-> (or text "")
      (str/replace xml-wrapper-pattern "")
      str/trim))

(defn- noise-user-text?
  "True when TEXT is injected boilerplate rather than the user's words."
  [text]
  (let [trimmed (str/trim (or text ""))]
    (or (some #(str/includes? trimmed %) noise-user-strings)
        (empty? (clean-user-text trimmed)))))

(defn- word-count
  "Approximate word count: whitespace-separated words, with CJK runs counted
   per character so a two-character reply is not treated as one word."
  [text]
  (reduce +
          0
          (for [segment (re-seq #"[\p{L}\p{N}]+" text)]
            (if (re-find #"[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]" segment)
              (count segment)
              1))))

(defn- user-line
  "The user's own words in one message, on one line (pi-vcc: userLine).
   Skill-only messages and tool-injected text are not user turns; one-word
   replies are dropped, and a long message keeps its first 300 characters."
  [text]
  (let [skills (mapv second (re-seq skill-block-pattern text))
        rest (-> text
                 (str/replace skill-block-pattern " ")
                 clean-user-text
                 (str/replace #"\s+" " ")
                 str/trim)]
    (cond
      (str/starts-with? rest "<") ""
      (< (word-count rest) 2) ""
      :else (clip-head (if (seq skills)
                         (str "[skill: " (str/join ", " skills) "] " rest)
                         rest)
                       max-user-chars))))

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

(defn- tool-paths
  "Every file a tool call names: its single path arg, else a `paths` list."
  [tc]
  (let [one (some #(let [v (arg tc %)] (when (string? v) v))
                  [:path :file_path :filePath :file])
        many (arg tc :paths)]
    (cond
      one [one]
      (sequential? many) (vec (filter #(and (string? %) (seq %)) many))
      :else [])))

(defn- tool-value [tc]
  (or (first (tool-paths tc))
      (arg tc :command)
      (arg tc :pattern)
      (arg tc :query)))

(def ^:private read-tools #{"read" "read_file" "view"})
(def ^:private edit-tools
  #{"edit" "write" "edit_file" "write_file" "multiedit" "quick_edit"
    "target_edit" "apply_patch"})

(defn- tool-call-line
  "One tool-call line, marked when its result failed."
  [tc result]
  (let [v (tool-value tc)
        paths (tool-paths tc)
        more (when (> (count paths) 1) (str " +" (dec (count paths))))
        line (str "* " (or (:name tc) "tool")
                  (when (seq (str v))
                    (str " \"" (clip (str v) 160) "\"" more)))]
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
   filtering, and brief failure markers. Noise tool calls and injected user
   boilerplate are dropped before any section looks at the blocks."
  [messages]
  (reduce (fn [acc m]
            (case (:role m)
              :user
              (let [raw (sanitize (content-summary (:content m)))]
                (if (or (noise-user-text? raw)
                        (empty? (clean-user-text raw)))
                  acc
                  (conj acc {:role :user :text (clean-user-text raw)})))

              :assistant
              (let [t (sanitize (content-summary (:content m)))
                    tcs (->> (:tool-calls m)
                             (remove #(contains? noise-tool-names (tool-name %)))
                             vec)]
                (if (or (seq t) (seq tcs))
                  (conj acc {:role :assistant :text t :tool-calls tcs})
                  acc))

              :bash
              (if (:exclude-from-context? m)
                acc
                (conj acc {:role :bash
                           :command (:command m)
                           :text (:command m)
                           :output (:output m)
                           :exit-code (:exit-code m)
                           :cancelled (:cancelled m)}))

              :branch-summary
              (let [t (sanitize (:summary m))]
                (if (seq t)
                  (conj acc {:role :branch-summary :text t})
                  acc))

              :custom
              (if (contains? skip-custom-types (:custom-type m))
                acc
                (let [t (-> (:content m) content-summary sanitize collapse-skill-text)]
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
  (vec (keep #(when (= :user (:role %))
                (let [line (user-line (:text %))]
                  (when-not (str/blank? line) line)))
             blocks)))

(defn- file-lists [blocks results]
  (let [{:keys [modified read]}
        (reduce (fn [acc b]
                  (if (= :assistant (:role b))
                    (reduce (fn [acc tc]
                              (let [name (tool-name tc)
                                    result (get results (:id tc))
                                    paths (tool-paths tc)]
                                (if (and (seq paths)
                                         (some? result)
                                         (not (:is-error result)))
                                  (cond
                                    (contains? read-tools name)
                                    (update acc :read into paths)

                                    (contains? edit-tools name)
                                    (update acc :modified into paths)

                                    :else acc)
                                  acc)))
                            acc
                            (:tool-calls b))
                    acc))
                {:modified [] :read []}
                blocks)]
    {:modified (vec (distinct modified))
     :read (vec (distinct read))}))

(def ^:private git-commit-pattern
  #"(?:^|[;&|(\n])\s*((?:sudo\s+)?git\s+commit)(?=\s|$)")

(defn- inside-quotes?
  "True when POS in S is inside an unclosed single or double quote."
  [s pos]
  (loop [i 0 quote nil escaped? false]
    (if (>= i pos)
      (some? quote)
      (let [c (.charAt ^String s i)]
        (cond
          escaped? (recur (inc i) quote false)
          (= c \\) (recur (inc i) quote true)
          (and quote (= c quote)) (recur (inc i) nil false)
          (and (nil? quote) (or (= c \") (= c \'))) (recur (inc i) c false)
          :else (recur (inc i) quote false))))))

(defn- git-commit-starts
  "The indexes of git commit commands outside shell quotes."
  [cmd]
  (let [m (re-matcher git-commit-pattern (or cmd ""))]
    (loop [acc []]
      (if (.find m)
        (let [start (.start m 1)]
          (recur (if (inside-quotes? cmd start) acc (conj acc start))))
        acc))))

(defn- git-commit-start [cmd]
  (first (git-commit-starts cmd)))

(defn- git-commit? [cmd]
  (some? (git-commit-start cmd)))

(defn- command-end
  "The index where the commit command ends: the next ;, &, |, or newline
   outside single/double quotes."
  [s start]
  (loop [i start quote nil escaped? false]
    (if (>= i (count s))
      i
      (let [c (.charAt ^String s i)]
        (cond
          escaped? (recur (inc i) quote false)
          (= c \\) (recur (inc i) quote true)
          (and quote (= c quote)) (recur (inc i) nil false)
          (and (nil? quote) (contains? #{\; \& \| \newline} c)) i
          (and (nil? quote) (contains? #{\" \'} c)) (recur (inc i) c false)
          :else (recur (inc i) quote false))))))

(defn- commit-segments [cmd]
  (let [cmd (or cmd "")
        starts (git-commit-starts cmd)]
    (mapv (fn [i]
            (subs cmd (nth starts i)
                  (or (nth starts (inc i) nil) (count cmd))))
          (range (count starts)))))

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

(def ^:private commit-m-heredoc-pattern
  #"(?s)^(?:sudo\s+)?git\s+commit[^\n\"'&;|]*?\s-[a-zA-Z]*m\s+\"\$\(cat\s+<<-?\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_]\w*))\s*\n(.*)")

(def ^:private commit-stdin-heredoc-pattern
  #"(?s)^(?:sudo\s+)?git\s+commit[^\n]*?(?:-[a-zA-Z]*F|--file)(?:\s+|=)-(?=\s)[^\n]*?<<-?\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_]\w*))[^\n]*\n(.*)")

(defn- first-heredoc-body-line
  [body delimiter]
  (loop [lines (str/split-lines (or body ""))]
    (when-let [line (first lines)]
      (let [t (str/trim (str/replace line #"^\t+" ""))]
        (cond
          (= t delimiter) nil
          (seq t) t
          :else (recur (rest lines)))))))

(defn- heredoc-commit-subject
  [segment]
  (when-let [[_ d1 d2 d3 body] (re-find commit-m-heredoc-pattern segment)]
    (first-heredoc-body-line body (or d1 d2 d3))))

(defn- stdin-heredoc-subject
  [segment]
  (when-let [[_ d1 d2 d3 body] (re-find commit-stdin-heredoc-pattern segment)]
    (first-heredoc-body-line body (or d1 d2 d3))))

(defn- commit-subject-of
  [segment]
  (or (heredoc-commit-subject segment)
      (stdin-heredoc-subject segment)
      (let [command (subs segment 0 (command-end segment 0))]
        (when-let [subject (some #(second (re-find % command)) commit-subject-patterns)]
          (let [subject (str/trim subject)]
            (when-not (or (str/starts-with? subject "$(")
                          (str/includes? subject "<<"))
              (first (str/split-lines subject))))))))

(defn- commit-hash [text]
  (let [text (or text "")]
    (or (second (re-find #"\[[^\]]*?\b([0-9a-f]{7,40})\b[^\]]*\]" text))
        (when-let [[_ _old new] (re-find #"\b([0-9a-f]{7,40})\.\.([0-9a-f]{7,40})\b" text)]
          new)
        (second (re-find #"^\s*([0-9a-f]{7,40})\b" text)))))

(def ^:private commit-hash-subject-line-pattern
  #"(?:\[[^\]]*\s+([0-9a-f]{7,40})\]|^\s*([0-9a-f]{7,40})(?:\s+\([^)]*\))?)\s+(.*)")

(defn- hash-for-subject [texts subject]
  (some (fn [text]
          (some (fn [line]
                  (when-let [[_ bracket bare line-subject]
                             (re-find commit-hash-subject-line-pattern line)]
                    (when (= (str/trim line-subject) subject)
                      (or bracket bare))))
                (str/split-lines (or text ""))))
        texts))

(defn- commit-lines-for [cmd result-texts]
  (let [segments (commit-segments cmd)]
    (keep (fn [segment]
            (let [subject (or (commit-subject-of segment)
                              (when (re-find #"--amend\b" segment) "amend"))
                  hash (if (= 1 (count segments))
                         (some commit-hash result-texts)
                         (hash-for-subject result-texts subject))]
              (when subject
                (str "- "
                     (when (seq hash) (str hash ": "))
                     subject))))
          segments)))

(defn- commit-lines [blocks results]
  (reduce (fn [acc b]
            (case (:role b)
              :assistant
              (into acc
                    (mapcat (fn [tc]
                              (when (= "bash" (tool-name tc))
                                (let [cmd (or (arg tc :command) "")
                                      result (get results (:id tc))]
                                  (when (and (git-commit? cmd)
                                             (some? result)
                                             (not (:is-error result)))
                                    (commit-lines-for cmd [(:text result)]))))))
                    (:tool-calls b))

              :bash
              (if (and (not (:cancelled b))
                       (or (nil? (:exit-code b))
                           (zero? (:exit-code b)))
                       (git-commit? (:command b)))
                (into acc (commit-lines-for (:command b) [(:output b)]))
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
   "[Tracked Commands]" :tracked
   "[User Messages]" :users
   "[Compaction Instructions]" :instructions
   "[Branch Summary]" :branch
   "[Previous Summary]" :previous
   "[Transcript]" :brief})

(def ^:private transcript-header "[Transcript]\n\n")
(def ^:private transcript-separator (str "\n\n" transcript-header))

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

(defn- strip-cap-marker
  "Remove the (+N earlier)/(+N more) marker from a merged category line."
  [text]
  (-> text
      (str/replace #"^\s*\(\+\d+ earlier\)\s*" "")
      (str/replace #"\s*\(\+\d+ (?:more|earlier)\)\s*$" "")))

(defn- split-file-paths [text]
  (vec (keep (fn [path]
               (let [path (str/trim path)]
                 (when (seq path) path)))
             (str/split (strip-cap-marker text) #","))))

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
                            (and (not= :brief (:current acc))
                                 (contains? section-headers line))
                            (assoc acc :current (section-headers line))

                            (and (not= :brief (:current acc))
                                 (= line "---")
                                 (:current acc))
                            (assoc acc :current :brief)

                            (empty? line) acc

                            :else
                            (let [current (:current acc)
                                  value (if (str/starts-with? line "- ")
                                          (subs line 2)
                                          line)]
                              (case current
                                :early (if (str/blank? value)
                                         acc
                                         (update acc :early conj value))
                                :files
                                (cond
                                  (str/starts-with? line "- Modified: ")
                                  (update-in acc [:files :modified] into
                                             (split-file-paths (subs line 12)))
                                  (str/starts-with? line "- Read: ")
                                  (update-in acc [:files :read] into
                                             (split-file-paths (subs line 8)))
                                  :else acc)
                                :commits (update acc :commits conj line)
                                :tracked acc
                                :users (if (str/blank? value)
                                         acc
                                         (update acc :users conj value))
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

(defn- bare-commit [line]
  (str/replace line #"^- [0-9a-f]{7,40}: " "- "))

(defn- dedup-commit-lines
  "One line per commit (pi-vcc: dedupCommitLines). A hashless line is the
   same commit as a hashed line with the same message; keep the hashed one in
   the earlier line's place."
  [lines]
  (reduce (fn [out line]
            (cond
              (some #(= line %) out) out

              (re-find #"^- [0-9a-f]{7,40}: " line)
              (if-let [idx (first (keep-indexed
                                   (fn [i existing]
                                     (when (= existing (bare-commit line)) i))
                                   out))]
                (assoc out idx line)
                (conj out line))

              (some #(= (bare-commit %) (bare-commit line)) out) out

              :else (conj out line)))
          []
          lines))

(defn- merge-summary [previous fresh custom-instructions]
  (let [prev (or previous {})
        files (get prev :files {})]
    {:instructions (cond
                     (seq custom-instructions)
                     [(clip custom-instructions max-instructions-chars)]
                     (seq (:instructions prev)) (:instructions prev)
                     :else [])
     :early (vec (take max-early
                       (if (seq (:early prev))
                         (:early prev)
                         (:early fresh))))
     :files {:modified (merge-newest (vec (:modified files)) (:modified (:files fresh))
                                     identity max-files)
             :read (merge-newest (vec (:read files)) (:read (:files fresh))
                                 identity max-files)}
     :commits (vec (take-last max-commits
                              (dedup-commit-lines (concat (:commits prev)
                                                          (:commits fresh)))))
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
              :user [(str "[user] " (clip (collapse-skill-text (:text b)) 400))]

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

;; ─── Ranked brief selection (pi-vcc: rank.ts) ────────────────────────────

(def ^:private edit-tool-pattern
  #"^(edit|write|multiedit|quick_edit|target_edit|apply_patch)$")

(def ^:private read-tool-pattern
  #"^(read|glob|grep|ls|find|semantic_query|semantic_grep|semantic_show)$")

(def ^:private test-command-pattern
  #"(?i)\b(?:bun|npm|pnpm|yarn|node|pytest|cargo|go|mvn|gradle)\b[^\n]*(?:test|spec|check|lint|build|tsc)")

(def ^:private workflow-command-pattern
  #"(?i)(?:^|\s)(?:gh\s+(?:pr|issue)\s+[a-z-]+|git\s+(?:commit|push|merge|rebase|revert|cherry-pick|tag|reset|checkout|branch)\b)")

(def ^:private gh-pr-poll-pattern
  #"(?i)(?:^|\s)gh\s+pr\s+(?:view|checks)\s+(\d+)\b")

(def ^:private trivial-bash-line-pattern
  #"^(?:set\s+[-+]|cd(?:\s+\S+)?$|export\s+\w+=|(?:source|\.)\s+\S+|pwd$|true$|:$|#|ls(?:\s|$)|echo\b|clear$|sleep\b)")

(defn- block-commands [b]
  (if (= :bash (:role b))
    (when-let [cmd (:command b)] [cmd])
    (keep #(arg % :command) (:tool-calls b))))

(defn- block-paths [b]
  (if (= :assistant (:role b))
    (mapcat tool-paths (:tool-calls b))
    []))

(defn- trivial-bash? [cmd]
  (let [lines (remove str/blank? (map str/trim (str/split-lines (or cmd ""))))]
    (and (seq lines)
         (every? #(re-find trivial-bash-line-pattern %) lines))))

(defn- tool-call-score [tc]
  (let [name (tool-name tc)
        cmd (arg tc :command)]
    (cond
      (re-find edit-tool-pattern name) 34
      (and cmd (re-find test-command-pattern cmd)) 26
      (re-find read-tool-pattern name) 6
      :else 12)))

(defn- rank-block [b index total modified read]
  (let [recency (if (<= total 1)
                  0
                  (long (Math/round (* 12.0 (/ index (dec total))))))
        role-score (case (:role b)
                     :user 18
                     :assistant 10
                     :bash 8
                     :custom 8
                     0)
        tcs (:tool-calls b)
        tool-score (if (seq tcs) (apply max (map tool-call-score tcs)) 0)
        commands (block-commands b)
        bash? (= :bash (:role b))
        nonzero? (and bash?
                      (some? (:exit-code b))
                      (not (zero? (:exit-code b))))
        workflow? (some #(re-find workflow-command-pattern %) commands)
        test? (some #(re-find test-command-pattern %) commands)
        trivial? (and bash?
                      (:command b)
                      (trivial-bash? (:command b))
                      (not nonzero?))
        paths (block-paths b)
        file-score (cond
                     (some #(contains? modified %) paths) 18
                     (some #(contains? read %) paths) 6
                     :else 0)]
    {:block b
     :index index
     :score (+ recency role-score tool-score file-score
               (if workflow? 14 0)
               (if (and test? bash?) 22 0)
               (if nonzero? 24 0)
               (if trivial? -16 0))}))

(defn- block-dedup-key [b]
  (let [cmd (first (block-commands b))]
    (cond
      (and cmd (re-find gh-pr-poll-pattern cmd))
      (str "gh-pr-poll:" (second (re-find gh-pr-poll-pattern cmd)))

      cmd
      (let [normalized (str/trim (str/replace cmd #"\s+" " "))]
        (when (seq normalized) (str "bash:" normalized)))

      (= :assistant (:role b))
      (when-let [tc (first (:tool-calls b))]
        (when-let [path (first (tool-paths tc))]
          (str "tool:" (tool-name tc) ":" path)))

      :else nil)))

(defn- block-cost [b results]
  (inc (reduce + 0 (map count (brief-lines [b] results)))))

(defn- select-ranked-blocks
  "Select the highest-signal blocks under a size-relative character budget
   (pi-vcc: selectRankedBriefBlocks). The newest blocks are kept first for
   local continuity; older blocks are added by score until the budget or the
   block cap is reached."
  [blocks results modified read]
  (let [blocks (vec (remove #(= :branch-summary (:role %)) blocks))
        total (count blocks)
        budget (max brief-budget-floor-chars
                    (min brief-budget-ceiling-chars
                         (* brief-chars-per-block total)))
        modified (set modified)
        read (set read)
        costs (mapv #(block-cost % results) blocks)
        ranked (mapv #(rank-block %1 %2 total modified read)
                     blocks
                     (range total))
        recent (min preserve-recent-blocks max-ranked-blocks)
        candidates (concat (range (dec total)
                                  (max -1 (- total recent 1))
                                  -1)
                           (map :index (sort (fn [a b]
                                               (if (= (:score a) (:score b))
                                                 (> (:index a) (:index b))
                                                 (> (:score a) (:score b))))
                                             ranked)))
        chosen (reduce (fn [{:keys [selected used seen] :as acc} idx]
                         (let [block (nth blocks idx)
                               cost (nth costs idx)
                               key (block-dedup-key block)]
                           (if (or (contains? selected idx)
                                   (and key (contains? seen key))
                                   (> (+ used cost) budget)
                                   (>= (count selected) max-ranked-blocks))
                             acc
                             {:selected (conj selected idx)
                              :used (+ used cost)
                              :seen (if key (conj seen key) seen)})))
                       {:selected #{} :used 0 :seen #{}}
                       candidates)]
    (mapv #(nth blocks %) (sort (:selected chosen)))))

(defn- collapse-runs [lines]
  (->> (reduce (fn [acc line]
                 (if (and (seq acc) (= line (:line (peek acc))))
                   (update acc (dec (count acc)) update :n inc)
                   (conj acc {:line line :n 1})))
               []
               lines)
       (mapv (fn [{:keys [line n]}]
               (if (> n 1) (str line " (x" n ")") line)))))

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
           (str (when (seq body) "\n\n") transcript-header
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
        has-early? (seq (:early previous))
        files (file-lists blocks results)
        fresh {:early (if has-early? [] (vec (take max-early users)))
               :users (vec (take-last max-users
                                      (if has-early? users (drop max-early users))))
               :files files
               :commits (commit-lines blocks results)
               :branch (vec (keep #(when (= :branch-summary (:role %))
                                     (clip-block (:text %)
                                                 max-branch-entry-chars))
                                  blocks))}
        merged (merge-summary previous fresh custom-instructions)
        selected (select-ranked-blocks blocks results
                                       (:modified files) (:read files))
        fresh-brief (collapse-runs (brief-lines selected results))
        brief (concat (:brief previous) fresh-brief)
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

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
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.compaction.core :as core]
            [kmet.app.config :as cfg]
            [kmet.app.debug :as debug]
            [kmet.app.session.context :as context]
            [kmet.app.session.core :as session]
            [kmet.libs.json :as json]
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

;; ─── Text budgets (pi-vcc: brief.ts) ────────────────────────────────────

(def ^:private stop-words
  #{"a" "an" "the" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "shall" "can" "need" "must"
    "to" "of" "in" "for" "on" "with" "at" "by" "from" "as"
    "into" "through" "during" "before" "after" "above" "below"
    "between" "under" "over"
    "and" "but" "or" "nor" "not" "so" "yet" "both" "either"
    "neither" "each" "every" "all" "any" "few" "more" "most"
    "other" "some" "such" "no"
    "that" "this" "these" "those" "it" "its"
    "i" "me" "my" "we" "our" "you" "your" "he" "him" "his"
    "she" "her" "they" "them" "their" "who" "which" "what"
    "if" "then" "than" "when" "where" "how" "just" "also"})

(defn- normalize-for-token-budget [text]
  (-> (or text "")
      (str/replace #"\r\n?" "\n")
      (str/replace #"[^\S\n]+" " ")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- token-spans
  "Every letter/digit run in TEXT with its character span."
  [text]
  (loop [s text offset 0 spans []]
    (if-let [t (re-find #"[\p{L}\p{N}]+" s)]
      (let [at (str/index-of s t)]
        (recur (subs s (+ at (count t)))
               (+ offset at (count t))
               (conj spans {:start (+ offset at)
                            :end (+ offset at (count t))
                            :text t})))
      spans)))

(defn- significant-spans [text]
  (->> (token-spans text)
       (remove #(stop-words (str/lower-case (:text %))))
       vec))

(defn- truncate-tokens
  "Keep the first LIMIT significant words (pi-vcc: truncateTokens)."
  [text limit]
  (let [flat (normalize-for-token-budget text)
        words (significant-spans flat)]
    (if (<= (count words) limit)
      flat
      (str (subs flat 0 (:end (nth words (dec limit))))
           "...(truncated)"))))

(defn- truncate-tokens-head-tail
  "Keep HEAD and TAIL significant words, marking the cut (pi-vcc:
   truncateTokensHeadTail)."
  [text head tail]
  (let [flat (normalize-for-token-budget text)
        words (significant-spans flat)]
    (if (<= (count words) (+ head tail))
      flat
      (str (subs flat 0 (:end (nth words (dec head))))
           "\n...(middle truncated)...\n"
           (subs flat (:start (nth words (- (count words) tail))))))))

(def ^:private self-talk-prefix
  #"^\s*(?:hmm|wait|actually|oh|okay|ok|well|so)[,.!\s-]+")

(defn- strip-self-talk [text]
  (loop [t (or text "") n 0]
    (if (>= n 2)
      t
      (let [next-t (str/replace t self-talk-prefix "")]
        (if (= next-t t) t (recur next-t (inc n)))))))

(def ^:private bash-cap 240)
(def ^:private pipe-tail-re
  #"\s*\|\s*(?:head|tail|sort|wc|column|tr|cut|awk|uniq|python3|node|bun)(?:\s[^|]*)?$")
(def ^:private trivial-line-re
  #"^(?:set\s+[-+]|cd\s+\S+$|export\s+\w+=|(?:source|\.)\s+\S+|pwd$|true$|:$|#)")
(def ^:private heredoc-open-re
  #"(?<![\w)])<<-?\s*[\"']?([A-Za-z_]\w*)[\"']?")
(def ^:private filewriter-heredoc-re #"(?:^|[|&;]\s*)(?:cat|tee|dd)\b")
(def ^:private heredoc-body-cap 80)
(def ^:private body-noise-re
  #"^(?:import\s|from\s+\S+\s+import|require\(|const\s+\w+\s*=\s*require|#|\/\/|\"\"\"|'''|\"use strict\"|use\s+strict|<\?php)")

(defn- heredoc-close-index
  "The line index of the heredoc terminator opened at I, or -1 when the
   opener has no closing line downstream (pi-vcc: a stray << is not a
   heredoc)."
  [lines i]
  (when-let [[_ term] (re-find heredoc-open-re (nth lines i))]
    (loop [j (inc i)]
      (when (< j (count lines))
        (if (= (str/trim (nth lines j)) term) j (recur (inc j)))))))

(defn- strip-cd-prefix [line]
  (str/trim (str/replace line #"^cd\s+\S+\s*&&\s*" "")))

(defn- strip-pipe-tail [line]
  (loop [c line n 0]
    (if (>= n 3)
      (str/trim c)
      (let [next-c (str/replace c pipe-tail-re "")]
        (if (= next-c c) (str/trim c) (recur next-c (inc n)))))))

(defn- compress-bash
  "One-line semantic compression of a bash command (pi-vcc: compressBash):
   heredoc bodies are dropped or previewed, scaffolding-only lines filtered,
   and cd/pipe wrappers stripped."
  [raw]
  (let [raw (or raw "")
        raw-lines (str/split-lines raw)
        kept (loop [i 0 acc []]
               (if (>= i (count raw-lines))
                 acc
                 (let [line (nth raw-lines i)
                       close (heredoc-close-index raw-lines i)
                       preview (when (and close
                                          (not (re-find filewriter-heredoc-re line)))
                                 (first (keep (fn [j]
                                                (let [t (str/trim (nth raw-lines j))]
                                                  (when (and (seq t)
                                                             (not (re-find body-noise-re t)))
                                                    t)))
                                              (range (inc i) close))))
                       line (if preview
                              (str (str/trim line) " "
                                   (let [t (if (> (count preview) heredoc-body-cap)
                                             (str (subs preview 0 (dec heredoc-body-cap)) "…")
                                             preview)] t))
                              line)]
                   (recur (if close (inc close) (inc i))
                          (conj acc line)))))
        lines (->> kept (map str/trim) (remove str/blank?) vec)]
    (if (empty? lines)
      (str/trim raw)
      (let [meaningful (->> lines
                            (remove #(re-find trivial-line-re %))
                            (map #(strip-pipe-tail (strip-cd-prefix %)))
                            (remove str/blank?))
            chosen (if (seq meaningful)
                     meaningful
                     [(strip-pipe-tail (strip-cd-prefix (first lines)))])
            cmd (str/join "; " chosen)]
        (if (> (count cmd) bash-cap)
          (str (subs cmd 0 (- bash-cap 3)) "...")
          cmd)))))

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

(def ^:private read-tools #{"read" "read_file" "view"})
(def ^:private edit-tools
  #{"edit" "write" "edit_file" "write_file" "multiedit" "quick_edit"
    "target_edit" "apply_patch"})

(def ^:private tool-summary-fields
  {"read" "file_path" "edit" "file_path" "write" "file_path"
   "glob" "pattern" "grep" "pattern"})

(defn- tool-call-line
  "One tool-call line, marked when its result failed (pi-vcc: toolOneLiner)."
  [tc result]
  (let [name (or (:name tc) "tool")
        lname (tool-name tc)
        paths (tool-paths tc)
        field (tool-summary-fields lname)
        field-value (when field (arg tc (keyword field)))
        v (cond
            (string? field-value) field-value
            (seq paths) (first paths)
            (= "bash" lname) (compress-bash (or (arg tc :command) ""))
            :else (or (arg tc :query) (arg tc :command)
                      (arg tc :pattern)))
        more (when (> (count paths) 1) (str " +" (dec (count paths))))
        line (str "* " name
                  (when (seq (str v))
                    (str " \"" (clip (str v) 160) "\"" more)))]
    (if (:is-error result)
      (let [error (clip (:text result) 120)]
        (str line " [failed" (when (seq error) (str ": " error)) "]"))
      line)))

;; ─── Tracked commands (pi-vcc: extract/tracked-commands.ts) ─────────────

(def ^:private command-separator " | ")
(def ^:private commands-per-name 10)
(def ^:private max-entry-chars 80)

(defn- cap-items
  "Join ITEMS oldest-first, keeping the newest LIMIT behind a leading
   (+N earlier) marker (pi-vcc: capItems)."
  [items limit join-with]
  (let [items (vec items)]
    (if (<= (count items) limit)
      (str/join join-with items)
      (str "(+" (- (count items) limit) " earlier) "
           (str/join join-with (subvec items (- (count items) limit)))))))

(defn- unquoted-separators
  "Indexes of unquoted shell separators (;, &, |, newline) in CMD."
  [cmd]
  (let [n (count cmd)]
    (loop [i 0 quote nil seps []]
      (if (>= i n)
        seps
        (let [c (nth cmd i)]
          (cond
            quote
            (cond
              (and (= c \\) (= quote \")) (recur (+ i 2) quote seps)
              (= c quote) (recur (inc i) nil seps)
              :else (recur (inc i) quote seps))

            (= c \\) (recur (+ i 2) nil seps)

            (or (= c \') (= c \")) (recur (inc i) c seps)

            (and (= c \#)
                 (or (zero? i)
                     (contains? #{\space \; \& \| \( \newline} (nth cmd (dec i)))))
            (recur (loop [j (inc i)]
                     (if (and (< j n) (not= (nth cmd j) \newline))
                       (recur (inc j))
                       j))
                   nil seps)

            (and (= c \&)
                 (or (and (pos? i) (contains? #{\> \<} (nth cmd (dec i))))
                     (and (< (inc i) n) (= (nth cmd (inc i)) \>))))
            (recur (inc i) nil seps)

            (contains? #{\; \& \| \newline} c)
            (recur (inc i) nil (conj seps i))

            :else (recur (inc i) nil seps)))))))

(defn- capture-entry [text]
  (let [seps (unquoted-separators text)]
    (str/trim (if (seq seps) (subs text 0 (first seps)) text))))

(def ^:private wrappers #{"sudo" "env" "nohup" "time" "command"})
(def ^:private wrapper-value-flags
  {"sudo" #"^-[ugpCDRT]$"
   "env" #"^-[uS]$"})

(defn- token-at [cmd pos]
  (let [n (count cmd)
        pos (loop [p pos]
              (if (and (< p n) (contains? #{\space \tab} (nth cmd p)))
                (recur (inc p))
                p))
        t (or (re-find #"^\S+" (subs cmd pos)) "")]
    [t pos]))

(defn- skip-to-command [cmd pos]
  (loop [pos pos]
    (let [[tok at] (token-at cmd pos)]
      (cond
        (empty? tok) at
        (re-find #"^[A-Za-z_]\w*=" tok) (recur (+ at (count tok)))
        (not (contains? wrappers tok)) at
        :else
        (recur
         (loop [p (+ at (count tok))]
           (let [[flag fat] (token-at cmd p)]
             (if (str/starts-with? flag "-")
               (let [p (+ fat (count flag))
                     p (if (and (wrapper-value-flags tok)
                                (re-find (wrapper-value-flags tok) flag))
                         (let [[value vat] (token-at cmd p)]
                           (+ vat (count value)))
                         p)]
                 (recur p))
               p))))))))

(defn- find-top-level-invocations
  "Start positions of top-level NAME invocations (pi-vcc:
   findTopLevelInvocations)."
  [cmd name]
  (let [bounds (cons 0 (map inc (unquoted-separators cmd)))]
    (vec (keep (fn [bound]
                 (let [pos (skip-to-command cmd bound)
                       after (when (<= (+ pos (count name)) (count cmd))
                               (nth cmd (+ pos (count name)) nil))]
                   (when (and (str/starts-with? (subs cmd (min pos (count cmd))) name)
                              (or (nil? after)
                                  (contains? #{\space \tab \newline \return} after)))
                     bound)))
               bounds))))

(def ^:private ssh-bool-flag #"^-[46ACfGgKkMNTtVvXxYynaq]+$")

(defn- ssh-remote-command
  "Everything after ssh's target token, trimmed (pi-vcc: sshRemoteCommand)."
  [after-ssh]
  (let [tokens (loop [s after-ssh offset 0 acc []]
                 (if-let [t (re-find #"\S+" s)]
                   (let [at (str/index-of s t)]
                     (recur (subs s (+ at (count t)))
                            (+ offset at (count t))
                            (conj acc {:text t :start (+ offset at)})))
                   acc))]
    (loop [i 0]
      (if (and (< i (count tokens))
               (str/starts-with? (:text (nth tokens i)) "-"))
        (recur (+ i (if (re-find ssh-bool-flag (:text (nth tokens i))) 1 2)))
        (when-let [target (nth tokens i nil)]
          (let [rest (str/trim (subs after-ssh (+ (:start target)
                                                  (count (:text target)))))]
            (when (seq rest) rest)))))))

(defn- render-tracked-entry [entry]
  (let [line (-> entry
                 (str/replace #"\s+" " ")
                 (str/replace command-separator " \\| "))]
    (if (> (count line) max-entry-chars)
      (str (subs line 0 max-entry-chars) "…")
      line)))

(defn- tracked-commands
  "NAME -> entries seen, in the configured names' order. Only bash tool calls
   are read; when ssh is tracked, its remote command is scanned too."
  [blocks track-commands]
  (let [names (vec track-commands)]
    (if (empty? names)
      []
      (let [track-ssh? (some #(= "ssh" %) names)
            commands (for [b blocks
                           :when (= :assistant (:role b))
                           tc (:tool-calls b)
                           :when (= "bash" (tool-name tc))
                           :let [cmd (arg tc :command)]
                           :when (and (string? cmd) (seq cmd))]
                       cmd)
            state (atom (zipmap names (repeat [])))]
        (doseq [cmd commands
                name names
                start (find-top-level-invocations cmd name)]
          (let [item (capture-entry (str/trim (subs cmd start)))]
            (when (seq item)
              (swap! state update name
                     (fn [entries]
                       (conj (vec (remove #(= % item) entries)) item))))))
        (when track-ssh?
          (doseq [cmd commands
                  bound (find-top-level-invocations cmd "ssh")]
            (let [seg (subs cmd bound)
                  pos (skip-to-command seg 0)
                  remote (ssh-remote-command (subs seg (+ pos 3)))]
              (when remote
                (let [unquoted (str/replace remote #"['\"]" "")]
                  (doseq [name names
                          :when (not= name "ssh")
                          start (find-top-level-invocations unquoted name)]
                    (let [item (capture-entry (str/trim (subs unquoted start)))]
                      (when (seq item)
                        (swap! state update name
                               (fn [entries]
                                 (conj (vec (remove #(= % item) entries))
                                       item)))))))))))
        (->> names
             (keep (fn [name]
                     (let [entries (get @state name)]
                       (when (seq entries)
                         {:name name
                          :entries (mapv render-tracked-entry
                                         (take-last commands-per-name entries))}))))
             vec)))))

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
   Tool results are kept as blocks for ranking and are hidden from the brief.
   Noise tool calls and injected user boilerplate are dropped before any
   section looks at the blocks. SOURCE-IDS is parallel to MESSAGES and
   INDEX-BY-ID is the session-global #N map; an unresolved id renders no ref."
  [messages source-ids index-by-id skip-custom-types]
  (reduce (fn [acc [i m]]
            (let [source-id (get source-ids i)
                  ref (when index-by-id (get index-by-id source-id))]
              (case (:role m)
                :user
                (let [raw (sanitize (content-summary (:content m)))]
                  (if (or (noise-user-text? raw)
                          (empty? (clean-user-text raw)))
                    acc
                    (conj acc {:role :user :text (clean-user-text raw)
                               :source-index ref})))

                :assistant
                (let [t (sanitize (content-summary (:content m)))
                      tcs (->> (:tool-calls m)
                               (remove #(contains? noise-tool-names (tool-name %)))
                               vec)]
                  (if (or (seq t) (seq tcs))
                    (conj acc {:role :assistant :text t :tool-calls tcs
                               :source-index ref})
                    acc))

                :bash
                (if (:exclude-from-context? m)
                  acc
                  (conj acc {:role :bash
                             :command (:command m)
                             :text (:command m)
                             :output (:output m)
                             :exit-code (:exit-code m)
                             :cancelled (:cancelled m)
                             :source-index ref}))

                :branch-summary
                (let [t (sanitize (:summary m))]
                  (if (seq t)
                    (conj acc {:role :branch-summary :text t
                               :source-index ref})
                    acc))

                :custom
                (if (contains? skip-custom-types (:custom-type m))
                  acc
                  (let [t (-> (:content m) content-summary sanitize collapse-skill-text)]
                    (if (seq t)
                      (conj acc {:role :custom
                                 :custom-type (:custom-type m)
                                 :text t
                                 :source-index ref})
                      acc)))

                :tool
                (let [t (tool-result-text m)]
                  (conj acc {:role :tool-result
                             :name (:tool-name m)
                             :text t
                             :is-error (boolean (:is-error m))
                             :source-index ref}))

                acc)))
          [] (map-indexed vector messages)))

;; ─── Sections ────────────────────────────────────────────────────────────

(defn- user-texts [blocks]
  (vec (keep #(when (= :user (:role %))
                (let [line (user-line (:text %))]
                  (when-not (str/blank? line) line)))
             blocks)))

(def ^:private create-tools #{"write" "write_file"})

(defn- longest-common-dir-prefix
  "The longest common absolute directory prefix ending in /, or nil when
   fewer than two absolute paths share at least /a/b (pi-vcc: files.ts)."
  [paths]
  (let [abs (filter #(str/starts-with? % "/") paths)]
    (when (>= (count abs) 2)
      (let [split (mapv #(str/split % #"/") abs)
            min-len (apply min (map count split))]
        (loop [i 0]
          (if (< i (dec min-len))
            (let [seg (nth (first split) i)]
              (if (every? #(= seg (nth % i)) split)
                (recur (inc i))
                (when (>= i 3)
                  (str (str/join "/" (subvec (first split) 0 i)) "/"))))
            (when (>= i 3)
              (str (str/join "/" (subvec (first split) 0 i)) "/"))))))))

(defn- trim-paths [paths prefix]
  (if-not prefix
    paths
    (mapv #(if (str/starts-with? % prefix) (subs % (count prefix)) %)
          paths)))

(defn- file-lists [blocks results file-ops]
  (let [{:keys [modified read created]}
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

                                    (contains? create-tools name)
                                    (update acc :created into paths)

                                    (contains? edit-tools name)
                                    (update acc :modified into paths)

                                    :else acc)
                                  acc)))
                            acc
                            (:tool-calls b))
                    acc))
                {:modified (vec (:modified file-ops))
                 :read (vec (:read file-ops))
                 :created (vec (:created file-ops))}
                blocks)
        created (remove (set modified) created)
        modified (vec (distinct modified))
        created (vec (distinct created))
        read (vec (distinct read))
        all (concat modified created read)
        prefix (longest-common-dir-prefix all)]
    {:modified (trim-paths modified prefix)
     :created (trim-paths created prefix)
     :read (trim-paths read prefix)
     :raw {:modified modified
           :created created
           :read read}}))

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
        (seq (get-in merged [:files :created]))
        (seq (get-in merged [:files :read]))) (conj :files)
    (seq (:commits merged)) (conj :commits)
    (seq (:tracked merged)) (conj :tracked)
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

(def ^:private recall-note
  "Use `session_recall` to search for prior work, decisions, and context from before this summary. Do not redo work already completed.")

(defn- strip-recall-note
  "Remove every copy of the closing recall note, including notes wrapped
   across lines by an older summary (pi-vcc: stripRecallNote)."
  [text]
  (-> (or text "")
      (str/replace
       #"(?s)\n*(?:---\n+)?Use\s+`session_recall`\s+to\s+search.*?Do not\s+redo\s+work\s+already\s+completed\.\n*"
       "\n\n")
      str/trim))

(defn- split-header-brief
  "Split a summary into its header area and brief transcript. The current
   format separates them with ---; older kmet summaries used [Transcript]."
  [text]
  (or (when-let [i (str/index-of text "\n\n---\n\n")]
        [(subs text 0 i) (subs text (+ i 7))])
      (when-let [i (str/index-of text "\n\n[Transcript]\n\n")]
        [(subs text 0 i) (subs text (+ i (count "\n\n[Transcript]\n\n")))])
      [text ""]))

(defn- unwrap-header-lines
  "Join a wrapped item's indented continuation lines back onto its line, so
   the merge reads whole paths and messages (pi-vcc: unwrapSection)."
  [lines]
  (reduce (fn [acc line]
            (if (and (seq acc)
                     (re-find #"^[ \t]+\S" line)
                     (not (re-find #"^\[" line)))
              (conj (pop acc) (str (peek acc) " " (str/trim line)))
              (conj acc line)))
          [] lines))

(defn- parse-tracked-line [line]
  (when-let [[_ name entries] (re-matches #"^- ([^:]+): (.*)$" line)]
    {:name (str/trim name)
     :entries (->> (str/split entries #"\s+\|\s+")
                   (map str/trim)
                   (remove str/blank?)
                   vec)}))

(defn- parse-previous
  "Parse a previous VCC summary. Header items may be wrapped; continuation
   lines are rejoined before merging. Multi-line sections are read from their
   <previous-summary>/<branch-summary> tags, so their content can contain
   headers or separators. A summary without a VCC header or tag is preserved
   verbatim under :previous."
  [summary]
  (let [s (strip-recall-note (sanitize summary))
        [previous-block s1] (extract-tag s "previous-summary")
        [branch-block s2] (extract-tag s1 "branch-summary")
        [head brief] (split-header-brief s2)
        head-lines (unwrap-header-lines (str/split-lines head))
        first-line (some #(when (seq (str/trim %)) (str/trim %)) head-lines)
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
                                  (str/starts-with? line "- Created: ")
                                  (update-in acc [:files :created] into
                                             (split-file-paths (subs line 11)))
                                  (str/starts-with? line "- Read: ")
                                  (update-in acc [:files :read] into
                                             (split-file-paths (subs line 8)))
                                  :else acc)
                                :commits (update acc :commits conj line)
                                :tracked
                                (if-let [entry (parse-tracked-line line)]
                                  (if-let [i (first (keep-indexed
                                                     (fn [i e]
                                                       (when (= (:name e) (:name entry)) i))
                                                     (:tracked acc)))]
                                    (update-in acc [:tracked i :entries] into
                                               (:entries entry))
                                    (update acc :tracked conj entry))
                                  acc)
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
                       :files {:modified [] :created [] :read []}
                       :commits []
                       :tracked []
                       :users []
                       :instructions []
                       :branch []
                       :previous []
                       :brief []}
                      head-lines)
              parsed (if (seq (str/trim brief))
                       (update parsed :brief into (str/split-lines brief))
                       parsed)
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

(defn- merge-tracked [old new]
  (let [names (distinct (concat (map :name old) (map :name new)))]
    (->> names
         (keep (fn [name]
                 (let [entries (concat (some->> old (filter #(= name (:name %))) first :entries)
                                       (some->> new (filter #(= name (:name %))) first :entries))]
                   (when (seq entries)
                     {:name name
                      :entries (->> entries
                                    (reduce (fn [acc e]
                                              (conj (vec (remove #(= % e) acc)) e))
                                            [])
                                    (take-last commands-per-name)
                                    vec)}))))
         vec)))

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
     :files {:modified (merge-newest (vec (:modified files))
                                     (:modified (:files fresh))
                                     identity max-files)
             :created (merge-newest (vec (:created files))
                                    (:created (:files fresh))
                                    identity max-files)
             :read (merge-newest (vec (:read files))
                                 (:read (:files fresh))
                                 identity max-files)}
     :tracked (merge-tracked (:tracked prev) (:tracked fresh))
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

(def ^:private max-brief-lines 120)

(defn- cap-brief-to-lines
  "Keep the newest MAX-LINES lines, starting at a section header when one is
   in the kept tail (pi-vcc: capBrief)."
  [text max-lines]
  (if (or (str/blank? text) (<= max-lines 0))
    ""
    (let [lines (str/split-lines text)]
      (if (<= (count lines) max-lines)
        text
        (let [kept (subvec lines (- (count lines) max-lines))
              first-header (first (keep-indexed
                                   (fn [i l] (when (re-find #"^\[.+\]" l) i))
                                   kept))
              clean (if (and first-header (pos? first-header))
                      (subvec kept first-header)
                      kept)
              omitted (- (count lines) (count clean))]
          (str "...(" omitted " earlier lines omitted)\n\n"
               (str/join "\n" clean)))))))

(defn- merge-brief
  "Merge a previous brief with the fresh one under the 120-line budget
   (pi-vcc: mergeBriefTranscriptWithFreshBudget)."
  [prev fresh]
  (cond
    (str/blank? prev) fresh
    (str/blank? fresh) (cap-brief-to-lines prev max-brief-lines)
    :else
    (let [fresh-lines (count (str/split-lines fresh))
          remaining (max 0 (- max-brief-lines fresh-lines))
          prev-tail (cap-brief-to-lines prev remaining)]
      (if (seq prev-tail)
        (str prev-tail "\n\n" fresh)
        fresh))))

;; ─── Brief transcript and formatting ─────────────────────────────────────

(defn- ref-suffix [ref]
  (when (some? ref) (str " (#" ref ")")))

(defn- push-line [sections header line]
  (if (and (seq sections) (= header (:header (peek sections))))
    (update-in sections [(dec (count sections)) :lines] conj line)
    (conj sections {:header header :lines [line]})))

(defn- push-text [sections header text ref]
  (let [lines (str/split-lines (or text ""))]
    (if (empty? lines)
      sections
      (let [lines (conj (vec (butlast lines))
                        (str (last lines) (or (ref-suffix ref) "")))]
        (reduce #(push-line %1 header %2) sections lines)))))

(defn- next-renderable-block [blocks index]
  (first (drop-while #(= :tool-result (:role %)) (subvec blocks (inc index)))))

(defn- segment-closing-assistant? [blocks index]
  (and (= :assistant (:role (nth blocks index)))
       (let [next (next-renderable-block blocks index)]
         (or (nil? next) (= :user (:role next))))))

(defn- tool-one-liner [tc result ref]
  (let [line (tool-call-line tc result)]
    (if ref (str line (ref-suffix ref)) line)))

(defn- assistant-line [text blocks index]
  (let [text (strip-self-talk text)]
    (if (segment-closing-assistant? blocks index)
      (truncate-tokens-head-tail text 120 120)
      (truncate-tokens-head-tail text 80 120))))

(declare collapse-tool-lines cap-tool-calls)

(defn- brief-sections
  "Build pi-vcc's brief sections: user actions, assistant text, compressed
   tool one-liners, and no tool-result bodies. SOURCE refs are attached by
   the block's :source-index."
  [blocks results]
  (let [blocks (vec blocks)]
    (->> (map-indexed vector blocks)
         (reduce
          (fn [sections [i b]]
            (case (:role b)
              :user
              (let [text (truncate-tokens (collapse-skill-text (:text b)) 256)]
                (if (seq text) (push-text sections "[user]" text (:source-index b)) sections))

              :assistant
              (let [sections (if (seq (:text b))
                               (push-text sections "[assistant]"
                                          (assistant-line (:text b) blocks i)
                                          (:source-index b))
                               sections)]
                (reduce (fn [s tc]
                          (push-line s "[assistant]"
                                     (tool-one-liner tc (get results (:id tc))
                                                     (:source-index b))))
                        sections
                        (:tool-calls b)))

              :bash
              (if (:exclude-from-context? b)
                sections
                (push-line sections "[user]"
                           (str "$ " (compress-bash (or (:text b) (:command b) ""))
                                (when (and (some? (:exit-code b))
                                           (not (zero? (:exit-code b))))
                                  (str " (exit " (:exit-code b) ")"))
                                (or (ref-suffix (:source-index b)) ""))))

              :custom
              (push-line sections "[assistant]"
                         (str "[extension:"
                              (let [ct (:custom-type b)]
                                (if (keyword? ct) (subs (str ct) 1) (str ct)))
                              "] " (clip (:text b) 400)
                              (or (ref-suffix (:source-index b)) "")))

              sections))
          [])
         (collapse-tool-lines)
         (cap-tool-calls))))

(defn- collapse-tool-lines
  "Collapse identical tool-call lines inside one [assistant] section, keeping
   their refs and a count (pi-vcc: buildBriefSections collapse)."
  [sections]
  (mapv (fn [sec]
          (if (not= "[assistant]" (:header sec))
            sec
            (let [out (reduce
                       (fn [out line]
                         (if-not (str/starts-with? line "* ")
                           (conj out line)
                           (let [[_ base ref] (re-matches #"^(.*?) \(#(\d+)\)$" line)]
                             (if-not ref
                               (conj out line)
                               (let [last-line (peek out)
                                     [_ last-base refs n] (when last-line
                                                            (re-matches #"^(.*?) \(([#\d, ]+)\) x(\d+)$" last-line))]
                                 (cond
                                   (and last-base (= last-base base)
                                        (not (str/includes? line "[failed")))
                                   (assoc out (dec (count out))
                                          (str base " (" refs ", #" ref ") x"
                                               (inc (parse-long n))))

                                   (and last-line
                                        (= (str/replace last-line #"\s*\(#\d+\)$" "")
                                           base)
                                        (str/ends-with? last-line ")")
                                        (re-matches #"^.*\(#\d+\)$" last-line))
                                   (let [prev-ref (second (re-find #"\(#(\d+)\)$" last-line))]
                                     (assoc out (dec (count out))
                                            (str base " (#" prev-ref ", #" ref ") x2")))

                                   :else (conj out line)))))))
                       []
                       (:lines sec))]
              (assoc sec :lines out))))
        sections))

(defn- cap-tool-calls
  "Keep at most MAX-TOOL-LINES tool calls per assistant section, newest last
   (pi-vcc: TOOL_CALLS_PER_TURN)."
  [sections]
  (mapv (fn [sec]
          (if (not= "[assistant]" (:header sec))
            sec
            (let [lines (:lines sec)
                  tool-idxs (keep-indexed (fn [i l] (when (str/starts-with? l "* ") i)) lines)]
              (if (<= (count tool-idxs) max-tool-lines)
                sec
                (let [drop-n (- (count tool-idxs) max-tool-lines)
                      drop-set (set (take drop-n tool-idxs))
                      first-kept (nth tool-idxs drop-n)
                      next-lines (loop [i 0 out [] inserted? false]
                                   (if (>= i (count lines))
                                     out
                                     (let [line (nth lines i)]
                                       (cond
                                         (contains? drop-set i) (recur (inc i) out inserted?)
                                         (and (not inserted?) (= i first-kept))
                                         (recur (inc i)
                                                (conj out (str "* (" drop-n
                                                               " earlier tool-call entries omitted)"))
                                                true)
                                         :else (recur (inc i) (conj out line) inserted?)))))]
                  (assoc sec :lines next-lines))))))
        sections))

(defn- stringify-brief [sections]
  (let [out (reduce (fn [out [i sec]]
                      (if (zero? i)
                        (into out (cons (:header sec) (:lines sec)))
                        (let [prev (nth sections (dec i))
                              prev-tools? (and (= "[assistant]" (:header prev))
                                               (every? #(str/starts-with? % "* ")
                                                       (:lines prev)))
                              cur-tools? (and (= "[assistant]" (:header sec))
                                              (every? #(str/starts-with? % "* ")
                                                      (:lines sec)))]
                          (cond-> out
                            (not (and prev-tools? cur-tools?)) (conj "")
                            true (into (cons (:header sec) (:lines sec)))))))
                    []
                    (map-indexed vector sections))]
    (str/join "\n" out)))

(defn- brief-text [blocks results]
  (stringify-brief (brief-sections blocks results)))

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
  (cond
    (= :assistant (:role b)) (mapcat tool-paths (:tool-calls b))
    (= :bash (:role b))
    (when-let [m (re-find #"(?:^|\s)([\w./-]+\.[\w-]+)(?:\s|$)"
                          (or (:command b) ""))]
      [(second m)])
    :else []))

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
                     :tool-result 1
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
                     :else 0)
        long-result? (and (= :tool-result (:role b))
                          (> (count (or (:text b) "")) 1000))]
    {:block b
     :index index
     :score (+ recency role-score tool-score file-score
               (if workflow? 14 0)
               (if (and test? bash?) 22 0)
               (if nonzero? 24 0)
               (if trivial? -16 0)
               (if long-result? -8 0))}))

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
  (if (= :tool-result (:role b))
    0
    (inc (count (brief-text [b] results)))))

(defn- important-block? [r]
  (let [b (:block r)
        tcs (:tool-calls b)
        commands (block-commands b)]
    (or (>= (:score r) 34)
        (some #(re-find edit-tool-pattern (tool-name %)) tcs)
        (some #(re-find test-command-pattern %) commands)
        (and (= :bash (:role b))
             (some? (:exit-code b))
             (not (zero? (:exit-code b)))))))

(defn- boost-adjacency [ranked]
  (let [n (count ranked)
        important (filterv #(important-block? (nth ranked %)) (range n))]
    (reduce
     (fn [r i]
       (let [r (loop [j (dec i) r r]
                 (if (>= j (max 0 (- i 8)))
                   (if (= :user (:role (:block (nth r j))))
                     (update-in r [j :score] + 10)
                     (recur (dec j) r))
                   r))
             r (loop [j (dec i) r r]
                 (if (>= j (max 0 (- i 4)))
                   (if (= :assistant (:role (:block (nth r j))))
                     (update-in r [j :score] + 7)
                     (recur (dec j) r))
                   r))]
         (loop [j (inc i) r r]
           (if (<= j (min (dec n) (+ i 4)))
             (let [role (:role (:block (nth r j)))]
               (if (or (= :assistant role) (= :bash role))
                 (update-in r [j :score] + 5)
                 (recur (inc j) r)))
             r))))
     (vec ranked)
     important)))

(defn- next-renderable-ranked [ranked index]
  (first (drop-while #(= :tool-result (:role (:block %)))
                     (subvec ranked (inc index)))))

(defn- boost-segment-closing [ranked]
  (reduce (fn [ranked i]
            (let [b (:block (nth ranked i))]
              (if (and (= :assistant (:role b))
                       (>= (count (str/trim (or (:text b) ""))) 120)
                       (let [next (next-renderable-ranked ranked i)]
                         (or (nil? next) (= :user (:role (:block next))))))
                (update-in ranked [i :score] + 14)
                ranked)))
          (vec ranked)
          (range (count ranked))))

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
        ranked (-> (mapv #(rank-block %1 %2 total modified read)
                         blocks
                         (range total))
                   boost-adjacency
                   boost-segment-closing)
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
                           (if (or (= :tool-result (:role block))
                                   (contains? selected idx)
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
  (let [file-line (fn [label limit paths]
                    (when (seq paths)
                      (str "- " label ": "
                           (cap-items paths limit ", "))))
        file-lines (keep identity
                         [(file-line "Modified" max-files
                                     (get-in merged [:files :modified]))
                          (file-line "Created" max-files
                                     (get-in merged [:files :created]))
                          (file-line "Read" max-files
                                     (get-in merged [:files :read]))])
        tracked-lines (map (fn [{:keys [name entries]}]
                             (str "- " name ": "
                                  (cap-items entries commands-per-name
                                             command-separator)))
                           (:tracked merged))]
    (str/join "\n\n"
              (keep identity
                    [(section "[Compaction Instructions]"
                              (map #(str "- " %) (:instructions merged)))
                     (section "[Early Session Requests]"
                              (map #(str "- " %) (:early merged)))
                     (section "[Files And Changes]" file-lines)
                     (section "[Commits]" (:commits merged))
                     (section "[Tracked Commands]" tracked-lines)
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
           (str (when (seq body) "\n\n---\n\n")
                (str/join "\n" brief))))))

(defn- wrap-long-lines
  "Wrap lines longer than WIDTH at word boundaries, keeping list indentation
   (pi-vcc: wrapLongLines)."
  ([text] (wrap-long-lines text 120))
  ([text width]
   (letfn [(wrap-line [line]
             (if (<= (count line) width)
               [line]
               (let [indent (or (re-find #"^\s*(?:[-*]\s+|\d+\.\s+)?" line) "")
                     continuation (apply str (repeat (min (count indent) 8) \space))]
                 (loop [remaining line prefix "" out []]
                   (if (<= (+ (count prefix) (count remaining)) width)
                     (conj out (str prefix remaining))
                     (let [available (max 20 (- width (count prefix)))
                           window (subs remaining 0 (min available (count remaining)))
                           split-at (let [i (str/last-index-of window " ")]
                                      (if (and i (pos? i) (>= i (quot available 2)))
                                        i
                                        (max 1 available)))]
                       (recur (str/trim (subs remaining (min split-at (count remaining))))
                              continuation
                              (conj out (str prefix (str/trimr (subs remaining 0 (min split-at (count remaining)))))))))))))]
     (->> (str/split-lines (or text ""))
          (mapcat wrap-line)
          (str/join "\n")))))

(defn- build-summary [merged brief]
  (let [sections (render-sections merged)
        budget (- max-summary-chars (count sections)
                  (if (seq sections) (count "\n\n---\n\n") 0))
        brief-lines (if (seq brief)
                      (str/split-lines (cap-brief-to-lines brief max-brief-lines))
                      [])
        fitted (if (seq brief-lines) (fit-brief brief-lines budget) [])
        body (format-summary merged fitted)
        body (if (seq (str/trim body))
               (str body "\n\n---\n\n" recall-note)
               body)
        summary (wrap-long-lines body)
        summary (if (> (count summary) max-summary-chars)
                  (str (subs summary 0 (- max-summary-chars 22))
                       "\n...[summary truncated]")
                  summary)]
    {:summary summary
     :brief (when (seq fitted) (str/join "\n" fitted))}))

(defn- empty-summary []
  "[User Messages]\n- (no renderable user messages)")

;; ─── The compactor ───────────────────────────────────────────────────────

(defn- global-index-by-id
  "The session-global #N map for AGENT's live session."
  [agent]
  (when-let [sess (:session agent)]
    (session/global-index-by-id @(:entries sess))))

(defn- session-file-ops
  "File activity before the cut, read from the live branch. This is the
   authoritative seed pi-vcc gets from preparation.fileOps: a write is
   Created, an edit/read is Modified/Read (pi-vcc: extract/files.ts)."
  [agent prep]
  (let [empty-ops {:modified [] :created [] :read []}]
    (if-let [sess (:session agent)]
      (try
        (let [entries (session/get-branch sess)
              cut-id (:first-kept-id prep)
              cut (or (first (keep-indexed
                              (fn [i e] (when (= (:id e) cut-id) i))
                              entries))
                      (count entries))
              before (subvec entries 0 cut)]
          (reduce (fn [ops e]
                    (if (= :assistant (:role e))
                      (reduce (fn [ops tc]
                                (let [name (tool-name tc)
                                      paths (tool-paths tc)]
                                  (if (seq paths)
                                    (cond
                                      (contains? read-tools name)
                                      (update ops :read into paths)

                                      (contains? create-tools name)
                                      (update ops :created into paths)

                                      (contains? edit-tools name)
                                      (update ops :modified into paths)

                                      :else ops)
                                    ops)))
                              ops (:tool-calls e))
                      ops))
                  empty-ops before))
        (catch Exception e
          (debug/log (str "VCC session file-ops unavailable: " (ex-message e)))
          empty-ops))
      empty-ops)))

(defn- write-debug!
  "Write the pi-vcc debug snapshot when :vcc-debug is on. The path is the
   agent dir, not /tmp (Termux has no /tmp)."
  [prep summary details]
  (when (get-in prep [:settings :debug])
    (try
      (let [path (fs/path (cfg/get-agent-dir) "pi-vcc-debug.json")]
        (fs/create-dirs (fs/parent path))
        (spit (str path)
              (json/generate-string
               {:compactor :vcc
                :messages (count (:messages prep))
                :previous-summary-length (count (or (:previous-summary prep) ""))
                :tokens-before (:tokens-before prep)
                :summary-length (count summary)
                :summary-preview (subs summary 0 (min 500 (count summary)))
                :details details}
               {:pretty true})))
      (catch Exception e
        (debug/log (str "VCC debug snapshot failed: " (ex-message e)))
        nil))))

(defn summarize
  "Deterministic summary of PREP's projected messages, with pi-vcc's
   session-global #N refs, Created/Tracked Commands sections, ranked brief,
   previous-summary merge and 120-char line wrapping. CUSTOM-INSTRUCTIONS are
   unused here: the orchestrator turns them into a post-compaction follow-up
   prompt for VCC; a direct caller's non-blank text still becomes the
   [Compaction Instructions] section rather than being lost."
  [agent prep custom-instructions reason]
  (let [messages (:messages prep)
        results (results-by-id messages)
        settings (:settings prep)
        index-by-id (global-index-by-id agent)
        skip-types (or (:skip-custom-types settings)
                       #{context/tool-sources-custom-type
                         context/eval-library-custom-type})
        blocks (normalize-blocks messages (:source-ids prep) index-by-id skip-types)
        previous (parse-previous (:previous-summary prep))
        users (user-texts blocks)
        has-early? (seq (:early previous))
        file-ops (session-file-ops agent prep)
        files (file-lists blocks results file-ops)
        raw-files (:raw files)
        fresh {:early (if has-early? [] (vec (take max-early users)))
               :users (vec (take-last max-users
                                      (if has-early? users (drop max-early users))))
               :files files
               :commits (commit-lines blocks results)
               :tracked (tracked-commands blocks (or (:track-commands settings) []))
               :branch (vec (keep #(when (= :branch-summary (:role %))
                                     (clip-block (:text %)
                                                 max-branch-entry-chars))
                                  blocks))}
        merged (merge-summary previous fresh custom-instructions)
        selected (select-ranked-blocks blocks results
                                       (:modified raw-files) (:read raw-files))
        fresh-brief (brief-text selected results)
        brief (merge-brief (str/join "\n" (get-in previous [:brief])) fresh-brief)
        built (build-summary merged brief)
        summary (:summary built)
        empty? (str/blank? summary)
        summary (if empty? (empty-summary) summary)
        details {:compactor :vcc
                 :version 2
                 :sections (sections-present merged (:brief built))
                 :source-message-count (count messages)
                 :previous-summary-used (boolean (seq (:previous-summary prep)))
                 :reason reason
                 :summary-chars (count summary)
                 :empty? empty?}]
    (write-debug! prep summary details)
    {:summary summary
     :details details}))

(def ^:private smart-tail-min-tokens 5000)
(def ^:private smart-tail-max-tokens 25000)
(def ^:private oversized-tail-factor 2.5)

(defn- prepare
  "The VCC cut (pi-vcc: buildOwnCut + resolveSmartKeepUserTurns +
   applyTailBudget). An explicit keep:N is respected absolutely and compacts
   all when it cannot form a safe boundary. Otherwise the default is keep:1;
   when that tail is at most 5k tokens, smart-keep grows it to the largest
   user-turn tail under 25k. A tail above 2.5x25k is re-cut to the token
   budget, and a window with no user anchor uses the token cut directly."
  [entries settings]
  (if (some? (:keep-user-turns settings))
    (or (core/prepare-keep-n entries (:keep-user-turns settings))
        (core/prepare-keep-n entries 0))
    (let [base (core/prepare-keep-n entries 1)]
      (cond
        (nil? base)
        (core/prepare entries smart-tail-max-tokens)

        (and (:smart-keep-tail settings)
             (<= (or (:tail-tokens base 0) 0) smart-tail-min-tokens))
        (loop [n 2
               best base]
          (if-let [p (core/prepare-keep-n entries n)]
            (if (<= (or (:tail-tokens p 0) 0) smart-tail-max-tokens)
              (recur (inc n) p)
              best)
            best))

        (> (or (:tail-tokens base 0) 0)
           (* smart-tail-max-tokens oversized-tail-factor))
        (core/prepare entries smart-tail-max-tokens)

        :else base))))

(def strategy
  "The builtin VCC compactor: pi-vcc's cut and smart keep plus the
   deterministic summary. It cannot apply free-form instructions; the
   orchestrator turns them into a post-compaction follow-up prompt via
   :supports-custom-instructions?."
  {:prepare prepare
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

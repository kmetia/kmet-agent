(ns kmet.libs.edit-diff
  "Pure edit matching and display-diff helpers shared by the built-in edit tool, its renderer, and extensions.
   The display diff format is line-numbered and intended for terminal rendering."
  (:require [clojure.string :as str]
            [kmet.libs.diff :as diff]))

(def diff-context-lines 4)  ;; pi: contextLines = 4

(defn- drop-trailing-empty
  "Pi: generateDiffString pops the trailing '' from split(\"\\n\")."
  [lines]
  (let [lines (vec lines)]
    (if (and (seq lines) (= "" (peek lines)))
      (pop lines)
      lines)))

(defn- emit-context
  "Emit unchanged lines with line numbers; returns {:acc :old-num :new-num}."
  [lines old-num new-num acc pad]
  (loop [lines lines old-num old-num new-num new-num acc acc]
    (if (empty? lines)
      {:acc acc :old-num old-num :new-num new-num}
      (let [line (first lines)]
        (recur (rest lines) (inc old-num) (inc new-num)
               (conj acc (str " " (pad old-num) " " line)))))))

(defn format-diff-lines
  "Generate pi-style numbered diff lines from full old/new line vectors
   (pi: generateDiffString). Context (diff-context-lines) around each change
   region; ' ...' skip markers for large unchanged gaps. Returns {:diff str}.
   Identical vectors (after dropping trailing empty lines) yield {:diff \"\"} —
   the Myers pass cannot handle two empty changed-regions."
  [old-lines new-lines]
  (let [old-lines (drop-trailing-empty old-lines)
        new-lines (drop-trailing-empty new-lines)
        n (count old-lines) m (count new-lines)
        width (count (str (max n m)))
        pad (fn [num] (let [s (str num)] (str (apply str (repeat (- width (count s)) \space)) s)))
        skip-marker (str " " (apply str (repeat width \space)) " ...")
        parts (if (= old-lines new-lines)
                []
                (diff/line-diff old-lines new-lines))
        acc (loop [i 0 old-num 1 new-num 1 last-was-change? false acc []]
              (if (>= i (count parts))
                acc
                (let [{:keys [type lines]} (nth parts i)
                      rn (count lines)]
                  (if (not= :eq type)
                    ;; Changed part — emit +/- lines (pi: added/removed)
                    (let [{:keys [acc old-num new-num]}
                          (loop [lines lines old-num old-num new-num new-num acc acc]
                            (if (empty? lines)
                              {:acc acc :old-num old-num :new-num new-num}
                              (let [line (first lines)]
                                (if (= :add type)
                                  (recur (rest lines) old-num (inc new-num)
                                         (conj acc (str "+" (pad new-num) " " line)))
                                  (recur (rest lines) (inc old-num) new-num
                                         (conj acc (str "-" (pad old-num) " " line)))))))]
                      (recur (inc i) old-num new-num true acc))
                    ;; Unchanged part — pi's context rules
                    (let [next-change? (and (< (inc i) (count parts))
                                            (not= :eq (:type (nth parts (inc i)))))
                          has-leading? last-was-change?
                          has-trailing? next-change?]
                      (cond
                        (and has-leading? has-trailing?)
                        (if (<= rn (* diff-context-lines 2))
                          (let [{:keys [acc old-num new-num]}
                                (emit-context lines old-num new-num acc pad)]
                            (recur (inc i) old-num new-num false acc))
                          (let [leading (take diff-context-lines lines)
                                trailing (take-last diff-context-lines lines)
                                skipped (- rn (count leading) (count trailing))
                                {:keys [acc old-num new-num]}
                                (emit-context leading old-num new-num acc pad)
                                acc (conj acc skip-marker)
                                {:keys [acc old-num new-num]}
                                (emit-context trailing (+ old-num skipped) (+ new-num skipped) acc pad)]
                            (recur (inc i) old-num new-num false acc)))
                        (and has-leading? (not has-trailing?))
                        (let [shown (take diff-context-lines lines)
                              skipped (- rn (count shown))
                              {:keys [acc old-num new-num]}
                              (emit-context shown old-num new-num acc pad)]
                          (recur (inc i) (+ old-num skipped) (+ new-num skipped) false
                                 (if (pos? skipped) (conj acc skip-marker) acc)))
                        (and has-trailing? (not has-leading?))
                        (let [skipped (max 0 (- rn diff-context-lines))
                              acc (if (pos? skipped) (conj acc skip-marker) acc)
                              {:keys [acc old-num new-num]}
                              (emit-context (drop skipped lines)
                                            (+ old-num skipped) (+ new-num skipped) acc pad)]
                          (recur (inc i) old-num new-num false acc))
                        :else
                        ;; No adjacent changes — skip entirely (pi)
                        (recur (inc i) (+ old-num rn) (+ new-num rn) false acc)))))))]
    {:diff (str/join "\n" acc)}))

;; ─── Line-ending / BOM handling (pi: edit-diff.ts) ─────────────────────────

(defn strip-bom
  "Strip a UTF-8 BOM, returning {:bom str :text str} (pi: stripBom)."
  [content]
  (if (str/starts-with? content "\uFEFF")
    {:bom "\uFEFF" :text (subs content 1)}
    {:bom "" :text content}))

(defn detect-line-ending
  "Detect the dominant line ending of content: \"\\r\\n\" or \"\\n\"
   (pi: detectLineEnding — first occurrence wins)."
  [content]
  (let [crlf (str/index-of content "\r\n")
        lf (str/index-of content "\n")]
    (cond
      (nil? lf) "\n"
      (nil? crlf) "\n"
      :else (if (< crlf lf) "\r\n" "\n"))))

(defn normalize-to-lf
  "Normalize \\r\\n and \\r to \\n (pi: normalizeToLF)."
  [text]
  (-> text
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")))

(defn restore-line-endings
  "Convert \\n back to ENDING (pi: restoreLineEndings)."
  [text ending]
  (if (= ending "\r\n")
    (str/replace text "\n" "\r\n")
    text))

(defn generate-display-diff
  "Generate the standard numbered display diff for two source strings.
   Returns nil when the contents are identical — either as raw strings, or
   at the line level after dropping trailing empty lines (a trailing-newline
   or trailing-blank-line difference produces no visible +/- lines)."
  [old-content new-content]
  (let [old-text (normalize-to-lf (:text (strip-bom old-content)))
        new-text (normalize-to-lf (:text (strip-bom new-content)))]
    (when (not= old-text new-text)
      (let [result (format-diff-lines (str/split-lines old-text)
                                      (str/split-lines new-text))]
        (when-not (str/blank? (:diff result))
          (:diff result))))))

;; ─── Fuzzy matching (pi: normalizeForFuzzyMatch + fuzzyFindText) ───────────

(def ^:private fuzzy-normalize-chars
  "One scan for every character the quote/dash/space replaces below rewrite
   (smart quotes, dashes/minus, Unicode spaces) — a pure-ASCII file skips all
   four. A plain character class: no anchor, so it stays fast on every host."
  #"[\u2018\u2019\u201A\u201B\u201C\u201D\u201E\u201F\u2010\u2011\u2012\u2013\u2014\u2015\u2212\u00A0\u2002-\u200A\u202F\u205F\u3000]")

(def ^:private ascii-only-re
  "Full-match ASCII. NFKC is the identity on ASCII and the quote/dash/space
   replaces below cannot match it either, so one scan makes a pure-ASCII text
   skip both the Normalizer and all four replaces."
  #"[\x00-\x7F]*")

(defn- normalize-for-fuzzy-match
  "Pi: normalizeForFuzzyMatch — NFKC normalize, strip trailing whitespace per
   line, and normalize smart quotes/dashes/spaces to ASCII."
  [text]
  (let [ascii? (boolean (re-matches ascii-only-re text))
        text (if ascii?
               text
               (java.text.Normalizer/normalize text java.text.Normalizer$Form/NFKC))]
    (-> text
        (as-> s (->> (str/split-lines s)
                     ;; str/trimr, not a per-line (str/replace line #"\s+$" ""):
                     ;; jolt's anchored-regex scan was quadratic on the
                     ;; look-behind (jolt#1062, fixed upstream after this
                     ;; landed) and trimr trims the same ASCII whitespace, plus
                     ;; the Unicode trailing whitespace pi's JS \s covers.
                     (map str/trimr)
                     (str/join "\n")))
        (as-> s (if (and (not ascii?) (re-find fuzzy-normalize-chars s))
                  (-> s
                      (str/replace #"[\u2018\u2019\u201A\u201B]" "'")
                      (str/replace #"[\u201C\u201D\u201E\u201F]" "\"")
                      (str/replace #"[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]" "-")
                      (str/replace #"[\u00A0\u2002-\u200A\u202F\u205F\u3000]" " "))
                  s)))))

(defn- fuzzy-find-text-with
  "FIND-TEXT over an injected NORMALIZE: a caller applying several edits may
   memoize the normalizer so the same content is normalized once per pass
   (the fuzzy path otherwise re-normalizes the whole content per edit)."
  [normalize content old-text]
  (if-let [idx (str/index-of content old-text)]
    {:found true :index idx :match-length (count old-text)
     :used-fuzzy? false :content-for-replacement content}
    (let [fuzzy-content (normalize content)
          fuzzy-old (normalize old-text)
          idx (str/index-of fuzzy-content fuzzy-old)]
      (if (nil? idx)
        {:found false :index -1 :match-length 0
         :used-fuzzy? false :content-for-replacement content}
        {:found true :index idx :match-length (count fuzzy-old)
         :used-fuzzy? true :content-for-replacement fuzzy-content}))))

(defn fuzzy-find-text
  "Pi: fuzzyFindText — exact match first; falls back to matching in the
   fuzzy-normalized space. Returns {:found bool :index int :match-length int
   :used-fuzzy? bool :content-for-replacement str}."
  [content old-text]
  (fuzzy-find-text-with normalize-for-fuzzy-match content old-text))

(defn- count-occurrences
  "Pi: countOccurrences — number of (fuzzy) matches of old-text in content."
  [normalize content old-text]
  (let [fuzzy-content (normalize content)
        fuzzy-old (normalize old-text)]
    (- (count (str/split fuzzy-content
                         (re-pattern (java.util.regex.Pattern/quote fuzzy-old))
                         -1))
       1)))

;; ─── Replacement application (pi: edit-diff.ts applyReplacements) ──────────

(defn- split-lines-with-endings
  "Pi: splitLinesWithEndings — lines including their trailing newline."
  [content]
  (vec (or (re-seq #"[^\n]*\n|[^\n]+" content) [])))

(defn- get-line-spans
  "Pi: getLineSpans — vector of {:start int :end int} per line (with endings)."
  [content]
  (loop [offset 0 lines (split-lines-with-endings content) acc []]
    (if (empty? lines)
      acc
      (let [line (first lines)
            span {:start offset :end (+ offset (count line))}]
        (recur (:end span) (rest lines) (conj acc span))))))

(defn- apply-replacements
  "Pi: applyReplacements — apply replacements in reverse match order so
   offsets stay stable. Each: {:match-index int :match-length int :new-text str}.
   OFFSET is subtracted from match-index (for group slices)."
  [content replacements offset]
  (loop [result content
         reps (reverse replacements)]
    (if (empty? reps)
      result
      (let [{:keys [match-index match-length new-text]} (first reps)
            idx (- match-index offset)]
        (recur (str (subs result 0 idx) new-text (subs result (+ idx match-length)))
               (rest reps))))))

(defn- get-replacement-line-range
  "Pi: getReplacementLineRange — inclusive line range [start end) of a
   replacement in the base content's line spans."
  [lines replacement]
  (let [n (count lines)
        rstart (:match-index replacement)
        rend (+ rstart (:match-length replacement))
        start-line (loop [i 0]
                     (if (and (< i n)
                              (let [{:keys [start end]} (nth lines i)]
                                (not (and (>= rstart start) (< rstart end)))))
                       (recur (inc i))
                       i))]
    (if (or (>= start-line n)
            (let [{:keys [start end]} (nth lines start-line)]
              (not (and (>= rstart start) (< rstart end)))))
      (throw (ex-info "Replacement range is outside the base content."
                      {:type :edit-error}))
      (let [end-line (loop [j start-line]
                       (if (and (< j n) (< (:end (nth lines j)) rend))
                         (recur (inc j))
                         j))]
        (if (>= end-line n)
          (throw (ex-info "Replacement range is outside the base content."
                          {:type :edit-error}))
          {:start-line start-line :end-line (inc end-line)})))))

(defn- apply-replacements-preserving-unchanged-lines
  "Pi: applyReplacementsPreservingUnchangedLines — apply replacements matched
   against BASE-CONTENT to ORIGINAL-CONTENT while preserving unchanged line
   blocks from the original (trailing whitespace, Unicode quotes, ...)."
  [original-content base-content replacements]
  (let [original-lines (split-lines-with-endings original-content)
        base-lines (get-line-spans base-content)]
    (when (not= (count original-lines) (count base-lines))
      (throw (ex-info "Cannot preserve unchanged lines because the base content has a different line count."
                      {:type :edit-error})))
    (let [groups (loop [reps (vec (sort-by :match-index replacements)) groups []]
                   (if (empty? reps)
                     groups
                     (let [rep (first reps)
                           {:keys [start-line end-line]}
                           (get-replacement-line-range base-lines rep)]
                       (if-let [current (peek groups)]
                         (if (< start-line (:end-line current))
                           (recur (rest reps)
                                  (conj (pop groups)
                                        (-> current
                                            (update :end-line max end-line)
                                            (update :replacements conj rep))))
                           (recur (rest reps)
                                  (conj groups {:start-line start-line
                                                :end-line end-line
                                                :replacements [rep]})))
                         (recur (rest reps)
                                (conj groups {:start-line start-line
                                              :end-line end-line
                                              :replacements [rep]}))))))
          result (StringBuilder.)]
      (loop [g 0 original-line-index 0]
        (if (>= g (count groups))
          (do (.append result (apply str (subvec original-lines original-line-index)))
              (str result))
          (let [{:keys [start-line end-line replacements]} (nth groups g)
                group-start (get-in base-lines [start-line :start])
                group-end (get-in base-lines [(dec end-line) :end])
                slice (subs base-content group-start group-end)]
            (.append result (apply str (subvec original-lines original-line-index start-line)))
            (.append result (apply-replacements slice replacements group-start))
            (recur (inc g) end-line)))))))

;; ─── Edit application (pi: applyEditsToNormalizedContent) ──────────────────

(defn apply-edits-to-normalized-content
  "Pi: applyEditsToNormalizedContent — apply one or more exact-text
   replacements to LF-normalized content. Tries exact match first, then fuzzy
   (normalized) matching. Validates empty oldText, not-found, duplicate,
   overlap, and no-change conditions; errors throw ex-info {:type :edit-error}.
   Returns {:base-content str :new-content str}."
  [normalized-content edits path]
  (let [total (count edits)
        single? (= total 1)
        ;; One normalizer per apply pass: the fuzzy path probes every edit
        ;; against the content, then re-checks the winning base, and would
        ;; otherwise re-normalize the whole content each time (NFKC + the
        ;; char-class replaces are the cost — jolt-bugs.md tracks it).
        normalize (memoize normalize-for-fuzzy-match)
        fuzzy-find (fn [content old-text]
                     (fuzzy-find-text-with normalize content old-text))
        count-fuzzy (fn [content old-text]
                      (count-occurrences normalize content old-text))
        normalized-edits (mapv (fn [e]
                                 {:old-text (normalize-to-lf (:old-text e))
                                  :new-text (normalize-to-lf (:new-text e))})
                               edits)]
    ;; Empty oldText validation (pi: getEmptyOldTextError)
    (doseq [[i e] (map-indexed vector normalized-edits)]
      (when (zero? (count (:old-text e)))
        (throw (ex-info
                (if single?
                  (str "oldText must not be empty in " path ".")
                  (str "edits[" i "].oldText must not be empty in " path "."))
                {:type :edit-error}))))
    (let [used-fuzzy? (boolean
                       (some :used-fuzzy?
                             (map #(fuzzy-find normalized-content (:old-text %))
                                  normalized-edits)))
          replacement-base (if used-fuzzy?
                             (normalize normalized-content)
                             normalized-content)
          matched (loop [i 0 acc []]
                    (if (>= i total)
                      (vec (sort-by :match-index acc))
                      (let [edit (nth normalized-edits i)
                            match (fuzzy-find replacement-base (:old-text edit))]
                        (if-not (:found match)
                          ;; Pi: getNotFoundError
                          (throw (ex-info
                                  (if single?
                                    (str "Could not find the exact text in " path
                                         ". The old text must match exactly including all whitespace and newlines.")
                                    (str "Could not find edits[" i "] in " path
                                         ". The oldText must match exactly including all whitespace and newlines."))
                                  {:type :edit-error}))
                          (let [occurrences (count-fuzzy replacement-base (:old-text edit))]
                            (when (> occurrences 1)
                              ;; Pi: getDuplicateError
                              (throw (ex-info
                                      (if single?
                                        (str "Found " occurrences " occurrences of the text in " path
                                             ". The text must be unique. Please provide more context to make it unique.")
                                        (str "Found " occurrences " occurrences of edits[" i "] in " path
                                             ". Each oldText must be unique. Please provide more context to make it unique."))
                                      {:type :edit-error})))
                            (recur (inc i)
                                   (conj acc {:edit-index i
                                              :match-index (:index match)
                                              :match-length (:match-length match)
                                              :new-text (:new-text edit)})))))))
          ;; Overlap detection (pi: adjacent matched edits must be disjoint)
          _ (loop [i 1]
              (when (< i (count matched))
                (let [prev (nth matched (dec i))
                      curr (nth matched i)]
                  (when (> (+ (:match-index prev) (:match-length prev))
                           (:match-index curr))
                    (throw (ex-info
                            (str "edits[" (:edit-index prev) "] and edits[" (:edit-index curr)
                                 "] overlap in " path
                                 ". Merge them into one edit or target disjoint regions.")
                            {:type :edit-error})))
                  (recur (inc i)))))
          new-content (if used-fuzzy?
                        (apply-replacements-preserving-unchanged-lines
                         normalized-content replacement-base matched)
                        (apply-replacements replacement-base matched 0))]
      ;; Pi: getNoChangeError
      (when (= normalized-content new-content)
        (throw (ex-info
                (if single?
                  (str "No changes made to " path
                       ". The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected.")
                  (str "No changes made to " path ". The replacements produced identical content."))
                {:type :edit-error})))
      {:base-content normalized-content :new-content new-content})))

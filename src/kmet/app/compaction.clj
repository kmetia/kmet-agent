(ns kmet.app.compaction
  "LLM-based context compaction aligned with pi (pi: core/compaction/compaction.js).
   Token estimation, cut-point selection, conversation serialization, and the
   summarization prompts. Session replacement happens in kmet.app.session
   (compact-with-summary!), orchestration in kmet.app.loop. Depends on
   session/context-entries for the context-token estimate (pi: compaction.ts
   imports buildSessionContext from session-manager).

   Deviations from pi: no file-operation tracking (kmet does not record
   read/modified files); a split turn is summarized in a single call (pi uses a
   dedicated turn-prefix prompt and merges two summaries)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [kmet.app.session :as session]
            [kmet.libs.usage :as usage]))

(def ^:private image-chars 4800)
(def ^:private tool-result-max-chars 2000)

;; ─── Token estimation (pi: estimateTokens) ─────────────────────────────────

(defn- content-text
  "Plain text of a content string or block vector (tool_result blocks carry
   their text in :content)."
  [content]
  (if (string? content)
    content
    (str/join (for [b content
                    :when (or (= :text (:type b))
                              (= :tool_result (:type b)))]
                (or (:text b) (:content b) "")))))

(defn- text-chars
  "Char count of a content string or block vector (images count fixed chars,
   pi: estimateTextAndImageContentChars)."
  [content]
  (if (string? content)
    (count content)
    (reduce + 0
            (for [b content]
              (case (:type b)
                :image image-chars
                :text (count (or (:text b) ""))
                :thinking (count (or (:thinking b) ""))
                :tool_result (count (or (:content b) ""))
                0)))))

(defn estimate-tokens
  "Rough token estimate for a session entry (pi: estimateTokens, chars/4).
   :info, :session_info, and excluded-from-context :bash entries contribute 0
   — they never reach the LLM."
  [entry]
  (let [chars (case (:role entry)
                :info 0
                :session_info 0
                :bash (if (:exclude-from-context? entry)
                        0
                        (+ (count (or (:command entry) ""))
                           (count (or (:output entry) ""))))
                ;; summary entries keep their role in context; the summary
                ;; text is what the wire conversion sends (pi: convertToLlm)
                (:compaction :branch-summary) (count (or (:summary entry) ""))
                (+ (text-chars (:content entry))
                   (reduce + 0
                           (for [tc (:tool-calls entry)]
                             (+ (count (or (:name tc) ""))
                                (count (str (or (:arguments tc) ""))))))))]
    (quot (+ chars 3) 4))) ;; ceil(chars / 4)

;; ─── Context token measurement (pi: calculateContextTokens / estimateContextTokens) ──

(defn assistant-usage-tokens
  "Total context tokens reported by an assistant entry's normalized usage
   (pi: calculateContextTokens — input+output+cacheRead+cacheWrite), or nil
   when the entry is not an assistant or carries no usable usage.
   Assistant-only: compaction entries carry the summarization call's usage,
   which does not reflect the context."
  [entry]
  (when (and (= :assistant (:role entry))
             (some? (:usage entry)))
    (when-let [u (usage/entry-usage (:usage entry))]
      (let [n (+ (:input u) (:output u) (:cache-read u) (:cache-write u))]
        (when (pos? n) n)))))

(defn context-tokens
  "Measured token count of the active context (pi: getContextUsage →
   estimateContextTokens): the latest usage-carrying assistant entry's
   measured usage plus a chars/4 estimate of the entries after it; a pure
   estimate when no assistant entry reports usage. Entries are a session
   BRANCH. Returns nil only when the count is unknown: after the latest
   compaction only assistant responses that came after it in the branch are
   trusted — kept-tail entries carry pre-compaction usage reflecting the
   old, larger context (pi: unknown until the next LLM response — the
   footer then shows ?/window, and the compaction check must not re-trigger
   on the stale size)."
  [branch]
  (let [comp-idx (last (keep-indexed (fn [i e] (when (= :compaction (:role e)) i))
                                     branch))
        post (if comp-idx (subvec branch (inc comp-idx)) branch)
        known? (boolean (some assistant-usage-tokens post))
        ctx (session/context-entries branch)
        n (count ctx)
        usage-idx (when known?
                    (loop [i (dec n)]
                      (cond
                        (< i 0) nil
                        (assistant-usage-tokens (nth ctx i)) i
                        :else (recur (dec i)))))]
    (cond
      known? (if usage-idx
               (+ (assistant-usage-tokens (nth ctx usage-idx))
                  (reduce + 0 (map estimate-tokens (subvec ctx (inc usage-idx)))))
               (reduce + 0 (map estimate-tokens ctx)))
      (nil? comp-idx) (reduce + 0 (map estimate-tokens ctx))
      :else nil)))

;; ─── Cut-point selection (pi: findCutPoint) ────────────────────────────────

(defn- context-visible?
  "True when an entry contributes to the LLM context. Tool results and
   display-only entries are never valid cut points (pi: isCutPointMessage —
   branch/compaction summaries count: they become user messages at the wire,
   pi: convertToLlm)."
  [entry]
  (case (:role entry)
    :user true
    :assistant true
    :system true
    :compaction true
    :branch-summary true
    :bash (not (:exclude-from-context? entry))
    false))

(defn- turn-start?
  "True when an entry begins a turn (pi: isTurnStartEntry)."
  [entry]
  (contains? #{:user :bash} (:role entry)))

(defn find-cut-point
  "Find the entry index to start keeping from (pi: findCutPoint). Walks
   backwards from the newest entry accumulating estimated tokens; cuts at the
   closest valid cut point at/after the position where the budget
   keep-recent-tokens is reached. Never cuts at a tool result.
   Returns {:first-kept-index i :split-turn? bool}."
  [entries keep-recent-tokens]
  (let [n (count entries)
        valid (filterv (fn [i] (context-visible? (nth entries i))) (range n))]
    (if (empty? valid)
      {:first-kept-index 0 :split-turn? false}
      (let [acc (volatile! 0)
            cut (volatile! (first valid))
            stop (volatile! false)]
        (loop [i (dec n)]
          (when (and (>= i 0) (not @stop))
            (let [tokens (estimate-tokens (nth entries i))]
              (when (pos? tokens)
                (vswap! acc + tokens))
              (when (>= @acc keep-recent-tokens)
                (when-let [c (first (filter #(>= % i) valid))]
                  (vreset! cut c))
                (vreset! stop true)))
            (recur (dec i))))
        (let [cut-idx @cut]
          {:first-kept-index cut-idx
           :split-turn? (not (turn-start? (nth entries cut-idx)))})))))

;; ─── Preparation (pi: prepareCompaction) ───────────────────────────────────

(defn prepare
  "Compute what to compact (pi: prepareCompaction). The boundary starts at
   the previous compaction's first-kept entry, so its kept tail is
   re-summarizable (pi: boundaryStart = previous firstKeptEntryId — under
   append-only compaction the kept tail sits before the compaction and must
   not be skipped or it is dropped from context without being summarized).
   Finds the cut point and collects the context-visible entries before it.
   Returns {:first-kept-id str :messages [entries] :previous-summary
   str-or-nil :tokens-before int} or nil when there is nothing to summarize
   (including when the newest entry is a compaction — right after one just
   finished, pi guards this to avoid immediate re-compaction)."
  [entries keep-recent-tokens]
  (when (and (seq entries)
             (not= :compaction (:role (last entries))))
    (let [prev-idx (last (keep-indexed (fn [i e] (when (:summary e) i)) entries))
          previous-summary (when prev-idx (:summary (nth entries prev-idx)))
          boundary-start (if prev-idx
                           (let [idx (first (keep-indexed
                                             (fn [i e]
                                               (when (= (:id e) (:first-kept-id (nth entries prev-idx)))
                                                 i))
                                             entries))]
                             (if idx idx (inc prev-idx)))
                           0)
          cut (find-cut-point (subvec entries boundary-start) keep-recent-tokens)
          cut-idx (+ boundary-start (:first-kept-index cut))
          first-kept-id (:id (nth entries cut-idx))
          msgs (vec (filter context-visible? (subvec entries boundary-start cut-idx)))]
      (when (and first-kept-id (seq msgs))
        {:first-kept-id first-kept-id
         :messages msgs
         :previous-summary previous-summary
         ;; pi: tokensBefore = context tokens before compaction (the context
         ;; excludes previously-summarized entries)
         :tokens-before (reduce + 0 (map estimate-tokens (session/context-entries entries)))}))))

;; ─── Conversation serialization (pi: serializeConversation) ────────────────

(defn- truncate-for-summary
  "Truncate text to tool-result-max-chars, keeping the beginning with a
   truncation marker (pi: truncateForSummary)."
  [text]
  (if (<= (count text) tool-result-max-chars)
    text
    (str (subs text 0 tool-result-max-chars)
         "\n\n[... " (- (count text) tool-result-max-chars)
         " more characters truncated]")))

(defn- tool-call-str
  "Render a tool call as name(k=v, ...) (pi: serializeConversation). Args
   may be a parsed map or a string to parse."
  [tc]
  (let [args (or (:arguments tc) {})
        args (if (map? args) args
                 (try (edn/read-string (str args)) (catch Exception _ {})))
        args-str (str/join ", "
                           (for [[k v] args]
                             (str (name k) "=" (pr-str v))))]
    (str (:name tc) "(" args-str ")")))

(defn serialize-conversation
  "Serialize context-visible entries to text for summarization (pi:
   serializeConversation). Tool results are truncated to 2000 chars."
  [entries]
  (str/join "\n\n"
            (keep (fn [e]
                    (case (:role e)
                      :user (let [t (content-text (:content e))]
                              (when (seq t) (str "[User]: " t)))
                      :assistant
                      (let [thinking (str/join "\n"
                                               (for [b (:content e) :when (= :thinking (:type b))]
                                                 (:thinking b)))
                            text (content-text (:content e))
                            tcs (:tool-calls e)
                            parts (cond-> []
                                    (seq thinking) (conj (str "[Assistant thinking]: " thinking))
                                    (seq text) (conj (str "[Assistant]: " text))
                                    (seq tcs) (conj (str "[Assistant tool calls]: "
                                                         (str/join "; " (map tool-call-str tcs)))))]
                        (when (seq parts) (str/join "\n" parts)))
                      :tool (let [t (content-text (:content e))]
                              (when (seq t)
                                (str "[Tool result]: " (truncate-for-summary t))))
                      ;; compaction/branch_summary entries project to user
                      ;; messages in context — serialize their summary text so
                      ;; it survives a later compaction (pi: convertToLlm maps
                      ;; both to user messages before serializeConversation)
                      (:compaction :branch-summary)
                      (let [t (str/trim (or (:summary e) ""))]
                        (when (seq t) (str "[User]: " t)))
                      nil))
                  entries)))

;; ─── Summarization prompts (pi: SUMMARIZATION_PROMPT / UPDATE_SUMMARIZATION_PROMPT) ──

(def ^:private summarization-system-prompt
  "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.")

(def ^:private summarization-prompt
  "The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or \"(none)\" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or \"(none)\" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(def ^:private update-summarization-prompt
  "The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from \"In Progress\" to \"Done\" when completed
- UPDATE \"Next Steps\" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(defn summarization-request
  "Build the single user message for a summarization call (pi:
   generateSummaryWithUsage): <conversation> tags, optional <previous-summary>
   tags, then the (initial or update) prompt with optional custom instructions."
  [messages previous-summary custom-instructions]
  (let [prompt (if previous-summary
                 update-summarization-prompt
                 summarization-prompt)
        prompt (if (seq custom-instructions)
                 (str prompt "\n\nAdditional focus: " custom-instructions)
                 prompt)]
    {:role :user
     :content [{:type :text
                :text (str "<conversation>\n"
                           (serialize-conversation messages)
                           "\n</conversation>\n\n"
                           (when previous-summary
                             (str "<previous-summary>\n" previous-summary "\n</previous-summary>\n\n"))
                           prompt)}]}))

(defn summarization-messages
  "The full message list for a summarization call: system prompt + request."
  [messages previous-summary custom-instructions]
  [{:role :system :content [{:type :text :text summarization-system-prompt}]}
   (summarization-request messages previous-summary custom-instructions)])

;; ─── Branch summarization (pi: compaction/branch-summarization.ts) ────────

(def branch-summary-preamble
  "pi: BRANCH_SUMMARY_PREAMBLE — prepended to a generated branch summary so
   the plain summary reads as context about a branch the conversation came
   back from."
  "The user explored a different conversation branch before returning here.
Summary of that exploration:

")

(def ^:private branch-summary-prompt
  "pi: BRANCH_SUMMARY_PROMPT — structured format for summarizing an
   abandoned conversation branch."
  "Create a structured summary of this conversation branch for context when returning later.

Use this EXACT format:

## Goal
[What was the user trying to accomplish in this branch?]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned]
- [Or \"(none)\" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Work that was started but not finished]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [What should happen next to continue this work]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(defn branch-summary-messages
  "The full message list for a branch summarization call (pi:
   generateBranchSummary): the summarization system prompt + a single user
   message carrying the serialized abandoned-branch entries in
   <conversation> tags and the structured-summary prompt, with optional
   custom instructions appended. REPLACE-INSTRUCTIONS? (pi:
   replaceInstructions) makes CUSTOM-INSTRUCTIONS replace the builtin
   branch-summary prompt entirely instead of being appended as
   \"Additional focus\" — used by extensions (e.g. /end-review) that ship
   their own complete summary format."
  ([entries custom-instructions]
   (branch-summary-messages entries custom-instructions false))
  ([entries custom-instructions replace-instructions?]
   [{:role :system :content [{:type :text :text summarization-system-prompt}]}
    {:role :user
     :content [{:type :text
                :text (str "<conversation>\n"
                           (serialize-conversation entries)
                           "\n</conversation>\n\n"
                           (cond
                             (and replace-instructions? (seq custom-instructions))
                             custom-instructions

                             (seq custom-instructions)
                             (str branch-summary-prompt "\n\nAdditional focus: " custom-instructions)

                             :else
                             branch-summary-prompt))}]}]))

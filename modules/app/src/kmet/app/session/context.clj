(ns kmet.app.session.context
  "Context projection: the session's linear message context, the hidden prompt
   listings (deferred tool sources and the eval library), append-only context
   edits (K), and cache-miss detection. Reads and appends through
   kmet.app.session.store."
  (:require [clojure.string :as str]
            [kmet.app.eval.format :as eval-format]
            [kmet.app.session.store :as store]
            [kmet.ai.usage :as usage]))

(def tool-sources-custom-type
  "The custom-message type that tells the model which deferred tool sources
   exist. One type, so the listing's own last entry can be found and compared
   without re-deriving what it said."
  :tool-sources)

(def ^:private max-source-description-chars
  "Characters of one source's description in the listing (pi:
   MAX_SERVER_DESCRIPTION_CHARS — Codex's cap for deferred namespaces)."
  250)

(def ^:private max-listing-chars
  "Characters of one hidden prompt listing (deferred tool sources or the eval
   library). The text stays under it by eliding descriptions/docs first and
   dropping whole entries second, so a large set cannot push an unbounded
   message into the conversation (pi: MAX_SERVERS_SECTION_CHARS for the source
   half)."
  4096)

(defn- truncate
  "S within MAX characters, elided with `…` when it does not fit (pi:
   truncate)."
  [s max]
  (cond
    (<= (count s) max) s
    (<= max 1) ""
    :else (str (str/trimr (subs s 0 (dec max))) "…")))

(defn- source-line
  "One listing line: `- <id>: <description>`, the description elided to
   DESCRIPTION-CHARS, or the bare id when there is none (pi: a server line)."
  [source description-chars]
  (let [head (str "- " (name (:id source)))
        description (truncate (str/trim (str (:description source))) description-chars)]
    (if (str/blank? description) head (str head ": " description))))

(defn- omitted-line
  "The closing line standing for DROPPED sources, naming the discovery paths
   OPTS makes available (pi: the `… N more servers` line)."
  [dropped {:keys [run-code? eval? tool-search?]}]
  (let [script? (or run-code? eval?)]
    (str "- … " dropped " more source" (when (not= 1 dropped) "s")
         (cond
           (and script? tool-search?)
           "; find their tools with tool_search or (tools/list) in a script"

           script?
           "; (tools/list) in a script shows their tools"

           tool-search?
           "; find their tools with tool_search"))))

(defn- listing-text
  "INTRO, the first KEPT sources' lines at DESCRIPTION-CHARS, and the omission
   line standing for the rest."
  [intro sources kept description-chars opts]
  (str intro
       (str/join "\n" (map #(source-line % description-chars) (take kept sources)))
       (when (< kept (count sources))
         (str "\n" (omitted-line (- (count sources) kept) opts)))))

(defn- share
  "Characters one description may take when KEPT sources share ROOM — 2 of each
   line go to the separator after the id — and 0 when there is no room left."
  [room kept]
  (if (pos? kept)
    (max 0 (min max-source-description-chars (- (quot room kept) 2)))
    0))

(defn- listing-fit
  "The listing for KEPT sources with the descriptions sharing what the head
   lines leave of `max-listing-chars`."
  [intro sources kept opts]
  (listing-text intro sources kept
                (share (- max-listing-chars (count (listing-text intro sources kept 0 opts)))
                       kept)
                opts))

(defn- compose-listing
  "INTRO plus one line per SOURCE within `max-listing-chars`. Descriptions shrink
   to share the room first — every source stays listed, because being listed is
   the point of the message — and only heads that cannot fit on their own drop
   the rest into the closing line (pi: the per-server budget, then the
   `… N more servers` line). A set with room to spare renders once; a set whose
   head lines alone overflow walks the count down and renders again per step,
   which is why the loop is only affordable while sources are the registered
   extensions of a process (tens), not an unbounded input."
  [intro sources opts]
  (loop [kept (count sources)]
    (let [text (listing-fit intro sources kept opts)]
      (if (or (<= (count text) max-listing-chars) (zero? kept))
        text
        (recur (dec kept))))))

(defn- discovery-text
  "The sentence naming how OPTS lets the model reach a deferred source's tools;
   nil when no discovery path is selected. OPTS names the paths themselves, so
   an eval-only or run_code-only selection never mentions the other tool."
  [{:keys [run-code? eval? tool-search?]}]
  (let [script (cond
                 (and run-code? eval?) "run_code or eval"
                 run-code? "run_code"
                 eval? "eval")]
    (cond
      (and script tool-search?)
      (str "Their tools are callable from a script (" script "); "
           "(tools/list) shows them, and tool_search loads one tool so you can "
           "call it directly.")

      script
      (str "Their tools are callable from a script (" script "); (tools/list) shows them.")

      tool-search?
      "tool_search loads one tool so you can call it directly.")))

(defn deferred-sources-listing
  "The text that tells the model which deferred tool sources exist: one line per
   source (its id and its one line), and what it can do about them. OPTS names
   the available discovery paths (`:run-code?`, `:eval?`, `:tool-search?`); with
   none, returns nil — there is nothing the model can do with the list. Once
   something was listed, an empty set says so rather than leaving the stale
   message standing. The text is bounded (`max-listing-chars`) and
   deterministic — a set that did not change produces the same bytes, which is
   what lets `sync-tool-sources-listing!` compare it with the previous entry.

   pi keeps an `mcp_servers` section in the system prompt and *appends* a new
   one when it changes, so earlier messages stay cached; kmet has no
   mid-conversation system role, so this is appended as a custom message
   (`state/sync-tool-sources-listing!`) instead of rebuilding the leading one."
  ([sources] (deferred-sources-listing sources {:run-code? true
                                                :eval? true
                                                :tool-search? true}))
  ([sources opts]
   (if (empty? sources)
     "No tool sources are deferred."
     (when-let [discovery (discovery-text opts)]
       (compose-listing
        (str (count sources) " deferred tool source"
             (when (not= 1 (count sources)) "s")
             ". " discovery "\n\n")
        sources opts)))))

(def eval-library-custom-type
  "The custom-message type that tells the model which eval-library definitions
   this session has: its built environment's loaded records when one exists,
   else the files saved for its first eval. One type, so the branch's last
   manifest can be found and compared."
  :eval-library)

(def ^:private max-eval-doc-chars
  "Characters of one definition's first doc line in the manifest."
  160)

(defn- eval-listing-line
  "One manifest line: `- name [arglists] (scope[, shadowed]) — first doc
   line`."
  [entry]
  (str "- " (:name entry)
       (eval-format/arglists-text (:arglists entry))
       " (" (name (or (:scope entry) :global))
       (when (false? (:active entry)) ", shadowed") ")"
       (when-let [line (eval-format/doc-line (:doc entry))]
         (str " — " (truncate line max-eval-doc-chars)))))

(defn- eval-listing-text
  "INTRO plus definition LINES, dropping whole lines from the end until the
   text fits `max-listing-chars`; the dropped count and the discovery pointers
   are always stated. Deterministic: the same entries render the same bytes."
  [intro lines]
  (let [total (count lines)]
    (loop [kept total]
      (let [omitted (- total kept)
            text (str intro
                      (str/join "\n" (take kept lines))
                      "\n"
                      (when (pos? omitted)
                        (str "… " omitted " more; "))
                      "(eval/library) lists them; (eval/help) lists the source verbs.")]
        (if (or (<= (count text) max-listing-chars) (zero? kept))
          text
          (recur (dec kept)))))))

(defn eval-library-listing
  "The `:eval-library` manifest text for MANIFEST (`{:loaded? :entries}`):
   one line per definition with its arglists, scope and first doc line, or an
   empty-library correction. LOADED? distinguishes this session's built
   environment from the files a first eval will load; the text says which.
   Bounded and deterministic, so `sync-eval-library-listing!` can compare it
   with the branch's previous manifest byte for byte."
  [{:keys [loaded? entries]}]
  (let [entries (vec entries)
        n (count entries)]
    (if (empty? entries)
      "The eval library is empty."
      (eval-listing-text
       (if loaded?
         (str "Eval library loaded in this session: " n
              " definition" (when (not= 1 n) "s") ".\n")
         (str "Eval library: " n " definition"
              (when (not= 1 n) "s")
              " saved; " (if (= 1 n) "it loads" "they load")
              " on this session's first eval.\n"))
       (mapv eval-listing-line entries)))))

(defn derive-context-settings
  "Derive {:thinking-level :model :provider} from the branch root→leaf (pi:
   getSessionContextSettings): the latest :thinking-level-change entry wins
   (:off when none is on the path), the latest :model-change entry wins
   (nil when none). Settings from abandoned branches are never seen — the
   derivation follows the active leaf."
  [session]
  (reduce (fn [acc e]
            (case (:role e)
              :thinking-level-change (assoc acc :thinking-level (:thinking-level e))
              :model-change (assoc acc :model (:model e) :provider (:provider e))
              acc))
          {:thinking-level :off :model nil :provider nil}
          (store/get-branch session)))

;; ─── Append-only context edits (pi: ContextEditEntry) ─────────────────────

;; ─── Append-only context edits (pi: ContextEditEntry) ─────────────────────

(defn- context-editable?
  "True when ENTRY contributes editable model content — the targets
   append-context-edit! accepts: :user, :assistant, :tool (pi's toolResult;
   :bash is a shell result pi stores as a tool message) and :custom-message
   (pi: appendContextEdit's editable check)."
  [entry]
  (contains? #{:user :assistant :tool :bash :custom-message} (:role entry)))

(defn- normalize-context-content
  "A string replacement becomes one text block, so every projected message
   keeps kmet's block content shape (pi: appendContextEdit/projectContextEntry
   apply the same wrap to assistant/toolResult targets; kmet applies it to
   every editable role — its messages are always block-based)."
  [content]
  (if (string? content) [{:type :text :text content}] content))

(defn- valid-context-replacement?
  "True when REPLACEMENT is nil (omit the target) or a map whose :content is
   a string or block vector (pi: appendContextEdit's shape check)."
  [replacement]
  (or (nil? replacement)
      (and (map? replacement)
           (let [content (:content replacement)]
             (or (string? content) (vector? content))))))

(defn- normalize-context-replacement
  "Canonicalize REPLACEMENT: a string content becomes one text block,
   anything else passes through (pi: appendContextEdit's
   normalizedReplacement)."
  [replacement]
  (if (and replacement (string? (:content replacement)))
    (assoc replacement :content (normalize-context-content (:content replacement)))
    replacement))

(defn append-context-edit!
  "Append an append-only edit to one earlier entry's contribution to model
   context (pi: appendContextEdit): a nil REPLACEMENT omits TARGET-ID from
   every future provider context, {:content string-or-blocks} rewrites its
   content. The session — raw branch, usage totals, replay and the UI — keeps
   the target entry untouched; only the context projection changes (see
   build-context-messages). TARGET-ID must exist, lie on the active branch,
   and be a message entry contributing editable model content (:user,
   :assistant, :tool, :bash, :custom-message). Returns the appended
   :context-edit entry.

   Throws ex-info {:type :invalid-context-edit} for a malformed replacement,
   {:type :entry-not-found}, {:type :entry-not-on-branch}, or
   {:type :entry-not-editable}."
  [session target-id replacement]
  (store/with-session-lock session
    (when-not (valid-context-replacement? replacement)
      (throw (ex-info "Context edit replacement must be nil or contain string/array content"
                      {:type :invalid-context-edit
                       :target-id target-id
                       :replacement replacement})))
    (let [target (store/get-entry session target-id)]
      (when-not target
        (throw (ex-info (str "Entry " target-id " not found")
                        {:type :entry-not-found :id target-id})))
      (when-not (some #(= (:id %) target-id) (store/get-branch session))
        (throw (ex-info (str "Entry " target-id " is not on the active branch")
                        {:type :entry-not-on-branch :id target-id})))
      (when-not (context-editable? target)
        (throw (ex-info (str "Entry " target-id " does not contribute editable model content")
                        {:type :entry-not-editable :id target-id :role (:role target)})))
      (store/append-entry session
                          {:role :context-edit
                           :target-id target-id
                           :replacement (normalize-context-replacement replacement)}))))

;; ─── Context build (pi: buildSessionContext) ─────────────────────────────

;; ─── Context build (pi: buildSessionContext) ─────────────────────────────

(defn context-entries
  "Pure port of pi buildContextEntries over a branch vector (entries in
   root→leaf order). With a compaction on the path: [compaction, ...entries
   from its first-kept-id] — summarized history is excluded from context but
   stays in the file. Without one: the full branch. Multiple compactions:
   the latest wins (pi walks the path and keeps the last compaction seen)."
  [branch]
  (let [compaction-idx (last (keep-indexed (fn [i e] (when (= :compaction (:role e)) i))
                                           branch))]
    (if (nil? compaction-idx)
      branch
      (let [compaction (nth branch compaction-idx)
            kept (subvec branch 0 compaction-idx)
            kept-tail (drop-while #(not= (:id %) (:first-kept-id compaction)) kept)
            after-compaction (subvec branch (inc compaction-idx))]
        (into [compaction] (concat kept-tail after-compaction))))))

(defn build-context
  "Context entries along the active branch (pi: buildContextEntries — see
   context-entries)."
  [session]
  (context-entries (store/get-branch session)))

;; ─── The declared tool set (pi: transcript tool state) ────────────────────

(defn tool-state
  "The declared tool set along ENTRIES as name -> declaration: every
   :tool-change entry's removals are deleted first and its additions installed
   after, so a same-name redefinition wins (pi: getCurrentTools). The result is
   a sorted map — a replay of the same branch renders the same wire order."
  [entries]
  (into (sorted-map)
        (reduce (fn [state {:keys [role added removed]}]
                  (if (= :tool-change role)
                    (into (reduce dissoc state removed)
                          (map (juxt :name identity)) added)
                    state))
                {}
                entries)))

(defn session-tool-state
  "The declared tool set along SESSION's active branch (see `tool-state`),
   or nil when the branch holds no :tool-change entry — an empty map is a
   recorded empty set, not an absent record."
  [session]
  (let [entries (filter #(= :tool-change (:role %)) (store/get-branch session))]
    (when (seq entries)
      (tool-state entries))))

(defn tool-state-changes
  "The delta between two name -> declaration maps, in pi's shape (pi:
   getToolStateChanges): a changed definition is a removal plus an addition."
  [previous current]
  {:added (vec (keep (fn [[name declaration]]
                       (when (not= declaration (get previous name))
                         declaration))
                     current))
   :removed (vec (keep (fn [[name declaration]]
                         (when (not= declaration (get current name))
                           name))
                       previous))})

(defn context-messages
  "Project a session entry into LLM context messages (pi:
   sessionEntryToContextMessages). Message entries pass through unchanged;
   compaction and branch_summary entries also keep their roles (pi:
   compactionSummary/branchSummary AgentMessages) — the provider edge
   converts them to user messages carrying the wrapped summary text (pi:
   convertToLlm). custom_message entries become a :custom-role message
   (sent to the LLM as a user message — pi: convertToLlm custom→user) with
   string content normalized to a text block; :info, :session_info,
   :model-change, :thinking-level-change, :custom, :label, :turn-start and
   :context-edit are metadata/state and excluded (a :context-edit entry's
   effect is applied by build-context-messages, not as a message of its own;
   a :turn-start marker is a log fact for the tree — the model never sees a
   turn boundary). A :tool-change entry projects to a wire-only marker at the
   entry's position — {:role :tool-change :added [...] :removed [...]} — so
   the provider sees the declared-set delta where it was recorded; the chat
   replay, export and tree still skip it. Excluded :bash entries are kept here
   and dropped later by the LLM conversion (pi: convertToLlm filters
   excludeFromContext)."
  [entry]
  (case (:role entry)
    (:compaction :branch-summary) [entry]
    :tool-change
    [{:role :tool-change
      :added (:added entry)
      :removed (:removed entry)}]
    :custom-message
    [{:role :custom
      :custom-type (:custom-type entry)
      ;; pi: createCustomMessage — string content becomes a text block, nil
      ;; defaults to an empty block vector
      :content (cond
                 (string? (:content entry)) [{:type :text :text (:content entry)}]
                 (nil? (:content entry)) []
                 :else (:content entry))
      :display (:display entry)
      :details (:details entry)}]
    (:user :assistant :tool :bash) [entry]
    []))

(defn context-edit-label
  "One-line description of a :context-edit entry: what it does to its target
   (pi: the tree renders [context omit|replace: id])."
  [entry]
  (str "[context " (if (nil? (:replacement entry)) "omit" "replace")
       ": " (:target-id entry) "]"))

(defn- context-edits
  "Map of target-id → the latest :context-edit entry for it, over
   CONTEXT-ENTRIES in order (pi: buildSessionProjection's edits map — a
   later edit of the same target wins)."
  [context-entries]
  (reduce (fn [edits entry]
            (if (= :context-edit (:role entry))
              (assoc edits (:target-id entry) entry)
              edits))
          {}
          context-entries))

(defn- replacement-text
  "Plain text of a replacement's content — a string or text blocks."
  [content]
  (if (string? content)
    content
    (str/join (for [b content :when (= :text (:type b))] (:text b)))))

(defn- replace-message-content
  "MESSAGE with the edit's CONTENT installed (pi: projectContextEntry).
   A tool result keeps its block structure: kmet carries the tool-call id
   inside the :tool_result block (:tool_use_id) where pi's ToolResultMessage
   carries toolCallId as its own field, so only the block's text is replaced —
   pi's `{...message, content}` would drop the id kmet needs to pair call and
   result at the wire."
  [message content]
  (if (= :tool (:role message))
    (if (vector? (:content message))
      (assoc message :content
             (mapv (fn [b]
                     (if (= :tool_result (:type b))
                       (assoc b :content (replacement-text content))
                       b))
                   (:content message)))
      message)
    (assoc message :content (normalize-context-content content))))

(defn- context-messages-with-edit
  "The messages ENTRY projects into after EDIT (pi: projectContextEntry): a
   nil replacement drops them, a replacement swaps the content of the
   editable message roles (:user, :assistant, :tool, :bash, :custom)."
  [entry edit]
  (let [messages (context-messages entry)]
    (cond
      (nil? edit) messages
      (nil? (:replacement edit)) []
      :else (let [content (:content (:replacement edit))]
              (mapv (fn [message]
                      (if (contains? #{:user :assistant :tool :bash :custom} (:role message))
                        (replace-message-content message content)
                        message))
                    messages)))))

(defn project-entries
  "Project context-ordered ENTRIES (session/context-entries order) into a
   session projection: a vector of {:source entry :messages [...]} (pi:
   buildSessionProjection). Context edits are applied to the entries they
   target, and only the newest compaction contributes a summary message — an
   older compaction retained inside the newest retained range no longer
   summarizes anything (pi: buildSessionProjection's index > 0 rule)."
  [entries]
  (let [edits (context-edits entries)]
    (vec (map-indexed
          (fn [index entry]
            {:source entry
             :messages (if (and (= :compaction (:role entry)) (pos? index))
                         []
                         (context-messages-with-edit entry (get edits (:id entry))))})
          entries))))

(defn project-context
  "The active branch's context as projected entries (see project-entries)."
  [session]
  (project-entries (build-context session)))

(defn build-context-messages
  "Projected context messages along the active branch (pi:
   buildSessionProjection → messages): build-context with every append-only
   context edit applied. The one projection the provider request, the
   in-memory context and the token estimates are built from."
  [session]
  (vec (mapcat :messages (project-context session))))

;; ─── Cache-miss detection (pi: cache-stats.ts detectMiss) ────────────────

;; ─── Cache-miss detection (pi: cache-stats.ts detectMiss) ────────────────

(defn detect-cache-miss
  "Compare consecutive assistant messages' usage to spot a significant
   prompt-cache miss (pi: detectMiss): the newer turn's prompt minus its
   cache-read falls far below the previous turn's prompt (noise floor 1024
   tokens, pi NOISE_FLOOR_TOKENS). The previous message must have reported
   cache activity — a provider that never reports caching counts nothing.
   The model per message is derived from the :model-change entries (kmet
   stores model changes separately; pi's messages carry their model).
   Returns {:missed-tokens n :model-changed bool} or nil."
  [entries]
  (let [{:keys [result]}
        (reduce (fn [acc e]
                  (case (:role e)
                    :model-change (assoc acc :model (:model e))
                    :assistant (if (some? (:usage e))
                                 (update acc :result conj {:usage (:usage e)
                                                           :model (:model acc)})
                                 acc)
                    acc))
                {:model nil :result []}
                entries)
        msgs (take-last 2 result)]
    (when (= 2 (count msgs))
      (let [[prev msg] msgs
            pu (usage/entry-usage (:usage prev))
            mu (usage/entry-usage (:usage msg))
            prev-reported (and pu (pos? (+ (:cache-read pu) (:cache-write pu))))]
        (when (and pu mu prev-reported)
          (let [prev-tokens (+ (:input pu) (:cache-read pu) (:cache-write pu))
                msg-tokens (+ (:input mu) (:cache-read mu) (:cache-write mu))
                missed (- (min prev-tokens msg-tokens) (:cache-read mu))]
            (when (and (pos? prev-tokens) (pos? msg-tokens) (> missed 1024))
              {:missed-tokens missed
               :model-changed (not= (:model prev) (:model msg))})))))))

(ns kmet.app.ui.chat-history
  "ChatHistoryComponent — data-driven chat history.
   Holds messages as plain maps in a single messages-atom (the single source
   of truth); each message map carries its :component. Render derives the
   component tree from the data on each pass, delegating to the per-message
   component caches. There is no parallel children bookkeeping and no
   child→message reverse-engineering — persistence reads the atom directly."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.container :as container]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.user-message :as um]
            [kmet.app.ui.assistant-message :as am]
            [kmet.app.ui.tool-execution :as te]
            [kmet.app.ui.custom-message :as cm]
            [kmet.app.ui.summary-message :as summary-message]
            [kmet.app.ui.image-block :as image-block]
            [kmet.app.ui.skill-message :as skill-message]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.tui.macros :refer [track! track-deps defcomponent]]))

;; ─── Info component at top ─────────────────────────────────────────────────

(defn- make-info-msg
  "Create a CustomMessageComponent for the top info banner.
   Supports :collapsed-content / :expanded-content variants (pi: ExpandableText),
   an :expanded? flag to restore a previously expanded banner, and :images."
  [msg output-pad]
  (when msg
    (let [comp (cm/make-custom-message :label (:label msg)
                                       :content (:content msg "")
                                       :images (:images msg)
                                       :output-pad output-pad)]
      (when (and (some? (:collapsed-content msg))
                 (some? (:expanded-content msg)))
        (cm/custom-message-set-collapsible-content! comp
                                                    (:collapsed-content msg) (:expanded-content msg))
        (when (:expanded? msg)
          (cm/custom-message-set-expanded! comp true)))
      comp)))

;; ─── Render helpers (defined before the record) ────────────────────────────

(declare content->user-text)

(defn- full-skill-block-role?
  "True when a :user message renders as a BARE skill invocation (its
   content parses as a skill block with no trailing user message) — in
   quiet mode it joins the tool run instead of breaking it. A block with
   trailing args also renders a user box below the skill line, so the run
   must break there. Plain data check on the message map (no component
   reverse-engineering)."
  [m]
  (and (= :user (:role m))
       (boolean
        (when-let [block (skills/parse-skill-block
                          (content->user-text (:content m "")))]
          (empty? (:user-message block))))))

(defn- silent-tool-call-assistant?
  "True when an :assistant message carries tool calls but no text/thinking
   of its own — it renders nothing (its ToolExecutionComponents are the
   visuals, pi: hasToolCalls), so in quiet mode it must neither open nor
   break the tool run. Plain data check (the finalized shape carries plain
   strings; the live shape carries content atoms)."
  [m]
  (and (= :assistant (:role m))
       (or (seq (:tool-calls m)) (:tool-calls? m))
       (let [text (if-let [a (:text-atom m)] @a (:content m ""))
             thinking (if-let [a (:thinking-atom m)] @a (:thinking m ""))]
         (and (empty? (str/trim (str text)))
              (empty? (str/trim (str thinking)))))))

(defn- render-messages
  "Render message components. A user message that follows any earlier
   content gets a leading blank line (the Spacer(1) the old container
   model stored explicitly). The info banner counts as earlier content,
   so the first user message after the banner gets the same separator as
   subsequent ones — the banner's box padding alone doesn't read as a
   visible gap between two boxed messages. In quiet mode a tool run
   renders as bare grouped lines (no per-entry separator of its own),
   so the separator moves here: a quiet run-opening entry gets the blank
   line, while continuation lines inside the run render with no separator.
   A :user message that renders as a bare skill invocation joins the run."
  [msgs width banner-present? quiet?]
  (persistent!
   (loop [msgs msgs, seen-any? banner-present?, in-quiet-run? false, acc (transient [])]
     (if-let [m (first msgs)]
       (let [lines (protocols/render (:component m) width)
             run-member? (and quiet?
                              (or (#{:tool :skill} (:role m))
                                  (full-skill-block-role? m)))
             ;; a tool-call-only assistant message renders nothing (its
             ;; tools are the visuals) — invisible, so it neither opens
             ;; nor breaks the run (data check, not a render-emptiness
             ;; probe: a mid-stream orchestration artifact must not depend
             ;; on what this width happens to render)
             invisible? (silent-tool-call-assistant? m)
             run-open? (and run-member? (not in-quiet-run?))
             sep? (or (and (= :user (:role m)) seen-any? (not run-member?))
                      run-open?)
             acc (if sep? (conj! acc "") acc)]
         (recur (rest msgs) true
                (if invisible? in-quiet-run? run-member?)
                (reduce conj! acc lines)))
       acc))))

;; ─── ChatHistoryComponent record ───────────────────────────────────────────

(defcomponent ChatHistoryComponent nil
              [messages-atom  ;; atom of vec of message maps, each with :component
               info-comp-atom  ;; atom of CustomMessageComponent or nil
               output-pad-atom
               cwd-fn          ;; 0-arg fn → the runtime cwd for tool components
                               ;; (pi: ToolRenderContext.cwd — path displays are
                               ;; relative to the session's cwd)
               streaming-atom  ;; atom of streaming message map or nil
               tools-expanded-atom   ;; tool display mode: :collapsed | :expanded | :quiet
                                      ;; (pi: toolOutputExpanded, extended with quiet)
               thinking-hidden-atom  ;; flag: thinking blocks hidden (pi: hideThinkingBlock)
               hidden-label-atom]    ;; label shown in place of hidden thinking (pi: hiddenThinkingLabel)

  (render [_this width]
    (let [msgs @messages-atom
          info-lines (when-let [i @info-comp-atom] (protocols/render i width))
          msg-lines (render-messages msgs width (some? @info-comp-atom) (= :quiet @tools-expanded-atom))]
      ;; Without a banner (the common case) return the flat message vector
      ;; directly — copying the whole transcript again here is pure waste.
      (if (seq info-lines)
        (into (vec info-lines) msg-lines)
        msg-lines)))

  (invalidate [_this]
    (when-let [i @info-comp-atom] (protocols/invalidate i))
    (doseq [m @messages-atom] (protocols/invalidate (:component m)))))

;; ─── Construction ──────────────────────────────────────────────────────────

(declare normalize-tool-display-mode)

(defn make-chat-history
  "Create a ChatHistoryComponent. Message components subscribe to
   ui.subs/theme-sub themselves — no theme is threaded through (Stage 5).
   Options:
     :output-pad       — horizontal padding for boxed messages (default 1)
     :thinking-hidden  — initial thinking-blocks hidden flag (default false;
                         pi: hideThinkingBlock loaded from settings at startup)
     :tool-display-mode — initial tool display mode (default :collapsed;
                         loaded from settings at startup)
     :cwd-fn           — 0-arg fn returning the runtime working directory for
                         tool components (default: the process cwd)"
  [& {:keys [output-pad thinking-hidden tool-display-mode cwd-fn]
      :or {output-pad 1 thinking-hidden false tool-display-mode :collapsed
           cwd-fn #(or (System/getProperty "user.dir") ".")}}]
  (map->ChatHistoryComponent {:messages-atom (atom [])
                              :info-comp-atom (atom nil)
                              :output-pad-atom (atom output-pad)
                              :cwd-fn cwd-fn
                              :streaming-atom (atom nil)
                              :tools-expanded-atom (atom (normalize-tool-display-mode tool-display-mode))
                              :thinking-hidden-atom (atom (boolean thinking-hidden))
                              :hidden-label-atom (atom "Thinking...")}))

;; ─── Adding messages ──────────────────────────────────────────────────────

(defn- content->display-text
  "Convert message content (string or block vector) to a display string.
   Handles :text blocks, :tool_result blocks (:content) and image blocks
   (pi renders image thumbnails; here a placeholder)."
  [content]
  (cond
    (string? content) content
    (nil? content) ""
    :else
    (str/join "\n"
              (for [b content]
                (cond
                  (or (= (:type b) :image) (= (:type b) "image"))
                  (str "[image " (or (:mime-type b) "?") "]")
                  :else
                  (or (:content b) (:text b) ""))))))

(defn- content->user-text
  "Display text of a user message's content: text blocks only — image
   blocks render as their own image elements (image-block), not as inline
   placeholders (pi: getUserMessageText)."
  [content]
  (cond
    (string? content) content
    (nil? content) ""
    :else (str/join "\n" (for [b content
                               :when (not (contains? #{:image "image"} (:type b)))]
                           (or (:content b) (:text b) "")))))

(defn- make-plain-msg
  "Create a Spacer(1) + plain Text pair — pi's showError/showWarning: a
   dim/error/warning line with no background box."
  [text]
  (let [c (container/make-container)]
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c (text/make-text text 1 0))
    c))

(defn- make-plain-md-msg
  "Spacer(1) + Markdown tinted with DEFAULT-STYLE-FN — pi's compaction
   summaries and unknown-role content render as Markdown."
  [text theme default-style-fn]
  (let [c (container/make-container)]
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c
                                   (md/make-markdown text
                                                     :theme (theme/get-markdown-theme theme)
                                                     :default-style default-style-fn
                                                     :padding-x 0))
    c))

;; ─── Status line (pi: showStatus) ──────────────────────────────────────────

;; StatusLine — a dim status entry appended to the chat (pi: showStatus
;; appends Spacer(1) + Text to the chat container). Like pi's Text, long
;; statuses wrap instead of truncating. No component kind — kind-based
;; dispatch (toggles, theme application) returns nil for it.
(defcomponent StatusLine nil [spacer-atom text-atom cache-atom]
  (render [this width]
    (track! this width
      (let [sp @spacer-atom
            tt @text-atom]
        ;; The inner Text's text atom changes on status updates
        ;; (text/text-set!) — track it so the cache invalidates.
        (track-deps @(:text-atom tt))
        (into [] (concat (protocols/render sp width)
                         (protocols/render tt width))))))
  (invalidate [_this]
    (protocols/invalidate @spacer-atom)
    (protocols/invalidate @text-atom))
  (dispose [_this]
    (protocols/dispose @spacer-atom)
    (protocols/dispose @text-atom)))

(defn- make-status-line
  "Create a StatusLine for a status message (pi: showStatus — a Spacer(1)
   plus a dim, wrapping line)."
  [message]
  (map->StatusLine {:spacer-atom (atom (spacer/make-spacer 1))
                    :text-atom (atom (text/make-text (theme/dim message) 1 0))
                    :cache-atom (atom nil)}))

(defn- make-user-msg
  "The component for a :user message. A message whose content is an expanded
   skill block (`/skill:name …`) renders as a skill invocation message
   instead of dumping the XML wrapper and the whole skill body into the
   transcript — the trailing args (if any) stay a normal user message below
   it, exactly as pi splits the two (pi: parseSkillBlock +
   SkillInvocationMessageComponent). Attached image blocks render inline."
  [msg output-pad tools-expanded-atom]
  (let [text (content->user-text (:content msg ""))
        ;; live messages carry image blocks inside :content; the session
        ;; replay path attaches them as the message's :images (its content is
        ;; already flattened to text) — accept both
        images (into (vec (:images msg))
                     (image-block/content-images (:content msg)))]
    (if-let [block (skills/parse-skill-block text)]
      (let [args (:user-message block)]
        (skill-message/make-skill-invocation-message
         :skill-block block
         :tools-expanded-atom tools-expanded-atom
         :output-pad output-pad
         ;; the trailing args render as a user message below; images attached
         ;; to the invocation ride along with it (image-only attachments get
         ;; one too, with empty text)
         :user-message (when (or (seq args) (seq images))
                         (um/make-user-message :text (or args "")
                                               :images images
                                               :output-pad output-pad))))
      (um/make-user-message :text text :images images :output-pad output-pad))))

(defn- content->custom-text
  "Display text of a custom message's content (pi: CustomMessageComponent
   default rendering — text blocks only; strings pass through)."
  [content]
  (cond
    (string? content) content
    (nil? content) ""
    :else (str/join (for [b content
                          :when (= :text (:type b))]
                      (:text b)))))

(defn- make-component-for-msg
  "Create the appropriate component for a message map.
   For tool messages, looks up render functions from the tool registry.
   Assistant messages SHARE the chat history's thinking-hidden/hidden-label
   atoms (pi: hideThinkingBlock / hiddenThinkingLabel) — a toggle is one
   reset! that invalidates every message at once; tool, skill, and summary
   components share the tools-expanded toggle atom the same way. A message
   carrying a pre-built :component (extension renderers returning a
   component directly, and replay-built :bash executions) uses it as-is.
   :custom messages render through the default labeled box (a registered
   message renderer arrives as :component) and honor the display flag, and
   :compaction / :branch-summary render as collapsible summary boxes."
  [msg output-pad tools-expanded-atom thinking-hidden-atom hidden-label-atom cwd-fn]
  (let [thm @subs/theme-sub]
    (cond
    ;; Pre-built component — extension entry/message renderers may return a
    ;; bare component (pi: renderers produce components); the interactive
    ;; wraps those as {:component comp}. :bash messages carry theirs too.
      (:component msg) (:component msg)

      :else
      (case (:role msg)
        :user (make-user-msg msg output-pad tools-expanded-atom)
        :assistant (am/make-assistant-message
                  ;; content atoms come from the message map (with-assistant-data
                  ;; created them) — one home, owned by the data layer (§3.2)
                    :text-atom (:text-atom msg)
                    :thinking-atom (:thinking-atom msg)
                    :tool-calls? (boolean (seq (:tool-calls msg)))
                    :output-pad output-pad
                    :thinking-hidden-atom thinking-hidden-atom
                    :hidden-label-atom hidden-label-atom)
        :tool (let [tool (tools/get-tool (:name msg ""))
                    comp (te/make-tool-execution
                          :name (:name msg "")
                          :args (:args msg {})
                          :content (content->display-text (:content msg ""))
                          :is-error (:is-error msg false)
                          :truncation (:truncation msg)
                          :details (:details msg)
                          :output-pad output-pad
                          ;; pi: ToolRenderContext.cwd — path displays resolve
                          ;; against the runtime cwd (the session's), not the
                          ;; process cwd
                          :cwd (when cwd-fn (cwd-fn))
                          :tools-expanded-atom tools-expanded-atom
                        ;; pi: ToolDefinition.renderCall/renderResult — the
                        ;; record's fns (extension tools) win over the
                        ;; builtin renderers; both get the ToolRenderContext
                        ;; map (tool-execution-context). :title feeds the
                        ;; quiet one-liner (nil → the tool name).
                          :render-call-fn (:render-call tool)
                          :render-result-fn (:render-result tool)
                          :render-shell (:render-shell tool)
                          :title-fn (:title tool))]
            ;; Pi: replayed/persisted tool results are final — mark ended so
            ;; they render with success/error bg, footer strip, and Took.
            ;; Live pending messages (content "" + is-error false) are skipped.
                (when (or (seq (:content msg)) (:is-error msg))
                  (te/tool-execution-set-error! comp (:is-error msg false)))
                (when-let [images (:images msg)]
                  (te/tool-execution-set-images! comp images))
                comp)
        :bash (:component msg)  ;; Already-constructed BashExecutionComponent
      ;; pi: addMessageToChat case "custom" — the default labeled box. Image
      ;; blocks embedded in :content render like the call-site paths' do
      ;; (image-block/content-images); the :images key is the flattened
      ;; replay shape. The wire/stream/replay paths gate :display before
      ;; dispatching (pi: display required); a direct add without one has
      ;; nothing to gate on and renders.
        :custom (when-not (false? (:display msg))
                  (cm/make-custom-message :label (:custom-type msg)
                                          :content (content->custom-text (:content msg))
                                          :images (into (vec (:images msg))
                                                        (image-block/content-images (:content msg)))
                                          :output-pad output-pad))
      ;; pi: CompactionSummaryMessageComponent / BranchSummaryMessageComponent
      ;; — collapsible summary boxes (collapsed by default, expansion via
      ;; the shared ctrl+o mode atom).
        :compaction (summary-message/make-summary-message
                     :variant :compaction
                     :summary (:summary msg)
                     :tokens-before (:tokens-before msg)
                     :tools-expanded-atom tools-expanded-atom
                     :output-pad output-pad)
        :branch-summary (summary-message/make-summary-message
                         :variant :branch
                         :summary (:summary msg)
                         :tokens-before (:tokens-before msg)
                         :tools-expanded-atom tools-expanded-atom
                         :output-pad output-pad)
        :info (cm/make-custom-message :label (:label msg)
                                      :content (:content msg "")
                                      :images (:images msg)
                                      :output-pad output-pad)
      ;; one-shot styled entries take the palette snapshot at creation —
      ;; they are plain Text, there is nothing to re-theme
        :error (make-plain-msg (theme/fg thm :error (str "Error: " (:content msg ""))))
        :warning (make-plain-msg (theme/fg thm :warning (str "Warning: " (:content msg ""))))
        :notice (make-plain-msg (theme/fg thm (:style msg :warning) (str (:content msg ""))))
        :status (make-status-line (:content msg ""))
    ;; Fallback for roles with no dedicated component (unknown roles from
    ;; session data): render content as markdown rather than dropping it.
        (make-plain-md-msg (content->display-text (:content msg "")) thm
                           (fn [c] (theme/fg thm :text c)))))))

(defn- with-assistant-data
  "Attach the data-layer content atoms to an assistant message map
   (dsl.md §3.2 Stage 5): ONE home for text/thinking. The component shares
   them at construction; appends and reads go through the map — no
   component-facing mutation API. Non-assistant messages pass through.
   Caller-supplied atom keys win (merge order)."
  [msg]
  (if (= :assistant (:role msg))
    (merge {:text-atom (atom (content->display-text (:content msg "")))
            :thinking-atom (atom (:thinking msg ""))}
           msg)
    msg))

(defn chat-history-add-message!
  "Add a message to the chat history.
   Creates the appropriate component and appends the message map (with its
   :component, plus assistant content atoms) to messages-atom. Returns the
   created component (or nil)."
  [ch msg]
  (let [msg (with-assistant-data msg)
        comp (make-component-for-msg msg @(:output-pad-atom ch)
                                     (:tools-expanded-atom ch) (:thinking-hidden-atom ch)
                                     (:hidden-label-atom ch) (:cwd-fn ch))]
    (when comp
      (swap! (:messages-atom ch) conj (assoc msg :component comp)))
    comp))

(defn chat-history-insert-before-streaming!
  "Insert a message immediately before the current streaming message.
   Used for before-agent-start injected messages, which are input context
   that belongs above the assistant response. Falls back to appending when
   no streaming message exists. Returns the created component (or nil)."
  [ch msg]
  (let [msg (with-assistant-data msg)
        comp (make-component-for-msg msg @(:output-pad-atom ch)
                                     (:tools-expanded-atom ch) (:thinking-hidden-atom ch)
                                     (:hidden-label-atom ch) (:cwd-fn ch))
        streaming @(:streaming-atom ch)]
    (when comp
      (let [entry (assoc msg :component comp)]
        (swap! (:messages-atom ch)
               (fn [msgs]
                 (let [idx (if streaming (max 0 (dec (count msgs))) (count msgs))]
                   (vec (concat (subvec msgs 0 idx) [entry] (subvec msgs idx))))))))
    comp))

(defn- drop-trailing-statuses
  "Pop trailing :status entries (UI-only status lines) off the end of MSGS."
  [msgs]
  (loop [msgs msgs]
    (if (= :status (:role (peek msgs)))
      (recur (pop msgs))
      msgs)))

(defn chat-history-remove-streaming-placeholder!
  "Remove the current streaming placeholder from the chat if still present.
   The placeholder is matched by IDENTITY, never by position: a tool
   execution or a consumed steering/follow-up user message can be appended
   after it, and popping the last entry would delete that message instead
   (pi: agent_end removes the streamingComponent by reference). Returns
   true when the placeholder was removed (and the streaming state cleared)."
  [ch]
  (let [streaming @(:streaming-atom ch)]
    (when streaming
      (let [removed? (volatile! false)]
        (swap! (:messages-atom ch)
               (fn [msgs]
                 (let [msgs' (drop-trailing-statuses msgs)]
                   (cond
                     (identical? (peek msgs') streaming)
                     (do (vreset! removed? true) (pop msgs'))

                     (some #(identical? % streaming) msgs')
                     (do (vreset! removed? true)
                         (vec (remove #(identical? % streaming) msgs')))

                     :else msgs))))
        (when @removed?
          (reset! (:streaming-atom ch) nil)
          true)))))

;; ─── Streaming ────────────────────────────────────────────────────────────

(defn chat-history-start-streaming!
  "Start a new streaming assistant message.
   Creates the data atoms (on the message map) and the component sharing
   them, appends the message map to messages-atom, and returns the message
   map (callers can use chat-history-append-* to feed it — pure swaps).
   Shares the thinking-hidden/hidden-label atoms with the new message
   (pi: hideThinkingBlock)."
  [ch]
  (let [msg (with-assistant-data {:role :assistant :content ""})
        comp (am/make-assistant-message
              :text-atom (:text-atom msg)
              :thinking-atom (:thinking-atom msg)
              :output-pad @(:output-pad-atom ch)
              :thinking-hidden-atom (:thinking-hidden-atom ch)
              :hidden-label-atom (:hidden-label-atom ch))]
    (am/assistant-message-set-streaming! comp true)
    (let [entry (assoc msg :component comp :streaming? true)]
      (swap! (:messages-atom ch) conj entry)
      (reset! (:streaming-atom ch) entry)
      entry)))

(defn chat-history-append-streaming-text!
  "Append text to the current streaming response — a pure swap on the
   message map's text atom; the component's track! watch invalidates its
   cache and schedules the frame (§3.4). If there's no streaming message,
   creates one."
  [ch text]
  (let [msg (or @(:streaming-atom ch)
                (chat-history-start-streaming! ch))]
    (swap! (:text-atom msg) str text)))

(defn chat-history-append-thinking-text!
  "Append text to the current thinking display — a pure swap on the message
   map's thinking atom. If there's no streaming message, creates one."
  [ch text]
  (let [msg (or @(:streaming-atom ch)
                (chat-history-start-streaming! ch))]
    (swap! (:thinking-atom msg) str text)))

(defn chat-history-mark-streaming-tool-calls!
  "Mark the current streaming assistant message as carrying tool calls
   (pi: hasToolCalls). Call BEFORE chat-history-finalize-streaming! when the
   message's first tool starts executing: a tool-call-only assistant message
   must not settle into the '(no response)' placeholder — its tool components
   are the visuals. The flag lives on the message map too (the component
   alone is not data): the quiet run-grouping reads it to keep silent
   assistant entries from breaking a tool run. No-op without a streaming
   message."
  [ch]
  (when-let [msg @(:streaming-atom ch)]
    (am/assistant-message-set-tool-calls! (:component msg) true)
    ;; keep :streaming-atom in lockstep with the assoc'd entry: finalize
    ;; finds the streaming message by identity, so the map stored in
    ;; messages-atom must be the one the atom points at
    (let [updated (assoc msg :tool-calls? true)]
      (reset! (:streaming-atom ch) updated)
      (swap! (:messages-atom ch)
             (fn [msgs]
               (mapv (fn [m]
                       (if (identical? m msg)
                         updated
                         m))
                     msgs))))))

(defn chat-history-finalize-streaming!
  "Finalize the current streaming message: materialize the live content
   atoms into the map's plain :content/:thinking values and strip the atoms
   (post-finalize shape = replayed shape), marking it non-streaming.
   Returns the component (or nil if no streaming)."
  [ch]
  (when-let [msg @(:streaming-atom ch)]
    (let [comp (:component msg)
          text @(:text-atom msg)
          thinking @(:thinking-atom msg)]
      ;; mark non-streaming so transformers re-run with is-streaming false
      (am/assistant-message-set-streaming! comp false)
      (swap! (:messages-atom ch)
             (fn [msgs]
               (mapv (fn [m]
                       (if (identical? m msg)
                         (-> (assoc m :content text :streaming? false)
                             (dissoc :text-atom :thinking-atom)
                             (cond-> (seq thinking) (assoc :thinking thinking)))
                         m))
                     msgs)))
      (reset! (:streaming-atom ch) nil)
      comp)))

(defn chat-history-finalize-thinking!
  "Clear the thinking buffer on the streaming component (no-op with new architecture
   since thinking is stored in the message component itself)."
  [_ch]
  ;; Pi doesn't separately clear thinking — it's captured in the component.
  ;; If there's a streaming component, the thinking is part of it.
  nil)

(defn chat-history-get-streaming-text
  "Get the current streaming text (read from the message map's data atom)."
  [ch]
  (if-let [msg @(:streaming-atom ch)]
    (deref (:text-atom msg))
    ""))

(defn chat-history-streaming-empty?
  "True when the current streaming placeholder carries neither text nor
   thinking content (the drop-the-placeholder check in error paths)."
  [ch]
  (when-let [msg @(:streaming-atom ch)]
    (and (empty? (deref (:text-atom msg)))
         (empty? (deref (:thinking-atom msg))))))

;; ─── Info message ─────────────────────────────────────────────────────────

(defn chat-history-set-info-msg!
  "Set or clear the info message at the top.
   Pass {:label \"...\" :content \"...\"} or nil to clear. A replaced banner is
   disposed (its children's track! watches must not outlive it — the banner
   may be replaced by lifecycle events, e.g. loaded-resources updates)."
  [ch msg]
  (when-let [prev @(:info-comp-atom ch)]
    (try (protocols/dispose prev) (catch Exception _)))
  (if msg
    (when-let [comp (make-info-msg msg @(:output-pad-atom ch))]
      (reset! (:info-comp-atom ch) comp))
    (reset! (:info-comp-atom ch) nil)))

;; ─── Toggles ─────────────────────────────────────────────────────────────

(defn- kind-of
  "Kind-as-data dispatch (dsl.md §5): the component's stamped :kind field
   (set by defcomponent). nil for components without one — same semantics
   as the old IComponentKind satisfies? guard, without the protocol."
  [child]
  (:kind child))

(def valid-tool-display-modes
  "The tool display modes ctrl+o cycles (pi: toolOutputExpanded, extended
   with quiet): :collapsed (5-line preview + hint), :expanded (full
   output), :quiet (one dimmed title line, no box/hint — and
   `[skill] name` for skill invocations; !/!! bash executions keep the
   collapsed preview since they are user-invoked)."
  #{:collapsed :expanded :quiet})

(defn normalize-tool-display-mode
  "Missing and invalid values fall back to :collapsed."
  [v]
  (if (contains? valid-tool-display-modes v) v :collapsed))

(defn chat-history-get-tool-display-mode
  "The tracked tool display mode (:collapsed | :expanded | :quiet)."
  [ch]
  (normalize-tool-display-mode @(:tools-expanded-atom ch)))

(defn chat-history-set-tool-display-mode!
  "Set the tool display mode directly (settings row, extension API).
   Throws on an unknown mode. The collapsible info banner is not quiet —
   it follows the expanded/collapsed projection, like loaded resources.
   Returns the mode set."
  [ch mode]
  (when-not (contains? valid-tool-display-modes mode)
    (throw (ex-info (str "Unknown tool display mode: " (pr-str mode)
                         " (expected one of :collapsed :expanded :quiet)")
                    {:mode mode})))
  (reset! (:tools-expanded-atom ch) mode)
  (when-let [info @(:info-comp-atom ch)]
    (when (cm/custom-message-collapsible? info)
      (cm/custom-message-set-expanded! info (= mode :expanded))))
  mode)

(defn chat-history-cycle-tool-display!
  "Cycle the tool display mode (:collapsed → :quiet → :expanded → …).
   One reset! on the shared mode atom — tool and skill components read it
   lexically inside their track! bodies, so existing children re-derive
   and new ones inherit with no per-child push. Bash executions (!/!!)
   and the collapsible info banner treat :quiet as :collapsed. Returns
   the new mode."
  [ch]
  (let [next (case (chat-history-get-tool-display-mode ch)
               :collapsed :quiet
               :quiet :expanded
               :expanded :collapsed
               :collapsed)]
    (chat-history-set-tool-display-mode! ch next)))

(defn chat-history-toggle-tool-expanded!
  "Toggle tool output expansion (pi: toolOutputExpanded) — kept for the
   boolean extension API and old call sites: :expanded stays, anything
   else becomes :expanded and back to :collapsed. Prefer
   chat-history-cycle-tool-display! for the 3-state ctrl+o cycle. Returns
   the new expansion state (boolean)."
  [ch]
  (let [expanded? (not (= :expanded (chat-history-get-tool-display-mode ch)))]
    (chat-history-set-tool-display-mode! ch (if expanded? :expanded :collapsed))
    expanded?))

(defn chat-history-get-tool-expanded
  "Check if tool output is expanded (the tracked expansion flag) — :quiet
   reads as not expanded, like collapsed. Kept for the boolean extension
   API; new code reads chat-history-get-tool-display-mode."
  [ch]
  (= :expanded (chat-history-get-tool-display-mode ch)))

(defn chat-history-set-thinking-hidden!
  "Set thinking block visibility on all assistant messages — one reset! on
   the shared atom: every AssistantMessageComponent references it, so track!
   invalidates all caches at once and the next frame reflows lazily (pi:
   setHideThinkingBlock; new messages share the same atom at construction).
   Returns the value set."
  [ch hidden?]
  (let [hidden? (boolean hidden?)]
    (reset! (:thinking-hidden-atom ch) hidden?)
    hidden?))

(defn chat-history-toggle-thinking-hidden!
  "Toggle thinking-block visibility. Returns the new hidden state."
  [ch]
  (chat-history-set-thinking-hidden! ch (not @(:thinking-hidden-atom ch))))

(defn chat-history-get-thinking-hidden
  "Check if thinking blocks are hidden (the tracked hidden flag)."
  [ch]
  @(:thinking-hidden-atom ch))

(defn chat-history-set-hidden-thinking-label!
  "Set the label shown in place of hidden thinking blocks (pi:
   setHiddenThinkingLabel) — one reset! on the shared label atom; all
   assistant messages reference it. Pass nil to restore the default."
  [ch label]
  (reset! (:hidden-label-atom ch) (or label "Thinking..."))
  nil)

;; ─── Status message (pi: showStatus) ────────────────────────────────────────

(defn chat-history-show-status!
  "Show a dim status message at the end of the chat (pi: showStatus — appends
   a Spacer(1) + dim Text to the chat container). The status is a regular
   trailing entry, not a pinned bottom line: subsequent messages append after
   it and it scrolls away with the transcript. When the trailing entry is
   already a status, its text is updated in place so repeated toggles don't
   accumulate. Status entries are UI-only — chat-history-get-messages excludes
   them, so they are never persisted."
  [ch message]
  (let [last-msg (peek @(:messages-atom ch))]
    (if (and last-msg (= :status (:role last-msg)))
      (text/text-set! @(:text-atom (:component last-msg))
                      (theme/dim message))
      (chat-history-add-message! ch {:role :status :content message})))
  nil)

;; ─── Misc ─────────────────────────────────────────────────────────────────

(defn chat-history-clear!
  "Clear all messages, streaming state, and info. Disposes message
   components first so owned resources (e.g. bash frame drivers) stop
   instead of firing into the cleared frame hook."
  [ch]
  (doseq [m @(:messages-atom ch)]
    (when-let [c (:component m)]
      (try (protocols/dispose c) (catch Exception _))))
  (when-let [info @(:info-comp-atom ch)]
    (try (protocols/dispose info) (catch Exception _)))
  (reset! (:messages-atom ch) [])
  (reset! (:info-comp-atom ch) nil)
  (reset! (:streaming-atom ch) nil))

(defn chat-history-rebuild!
  "Rebuild the chat history from a new message vector (context replacement).
   Clears existing messages and streaming state, preserves the top info banner."
  [ch msgs]
  (let [info @(:info-comp-atom ch)
        info-msg (when info
                   (cond-> {:label @(:label-atom info)
                            :content @(:content-atom info)
                            :images @(:images-atom info)}
                     (cm/custom-message-collapsible? info)
                     (assoc :collapsed-content @(:collapsed-content-atom info)
                            :expanded-content @(:expanded-content-atom info)
                            :expanded? @(:expanded-atom info))))]
    (chat-history-clear! ch)
    (doseq [m msgs]
      (chat-history-add-message! ch m))
    (when info-msg
      (chat-history-set-info-msg! ch info-msg))))

(defn chat-history-get-messages
  "Get all stored messages as plain maps — the data source of the chat,
   read directly from messages-atom (no component reverse-engineering).
   Includes the info banner first (its :images carried through); excludes
   bash executions (!! / !), status lines and derived notice lines, which
   are UI-only, and strips the :component/:streaming? keys plus live
   assistant content atoms (dereferenced into plain :content/:thinking
   values)."
  [ch]
  (->> (concat
        (when-let [info @(:info-comp-atom ch)]
          [{:role :info
            :label @(:label-atom info)
            :content @(:content-atom info)
            :images @(:images-atom info)}])
        @(:messages-atom ch))
       (remove #(#{:bash :status :notice} (:role %)))
       ;; deref live assistant content atoms (mid-stream reads); finalized
       ;; and replayed messages carry plain strings already
       (mapv (fn [{:keys [text-atom thinking-atom] :as m}]
               (cond-> (dissoc m :component :streaming? :text-atom :thinking-atom)
                 text-atom (assoc :content @text-atom)
                 thinking-atom (assoc :thinking @thinking-atom))))))

(defn- message-comps
  "All message components plus the info banner component."
  [ch]
  (concat (map :component @(:messages-atom ch))
          (when-let [info @(:info-comp-atom ch)] [info])))

(defn- set-pad-on!
  "Set output padding on a child based on its kind."
  [child n]
  (case (kind-of child)
    :user (um/user-message-set-output-pad! child n)
    :assistant (am/assistant-message-set-output-pad! child n)
    :tool (te/tool-execution-set-output-pad! child n)
    :custom (cm/custom-message-set-output-pad! child n)
    :summary (summary-message/summary-message-set-output-pad! child n)
    :skill (skill-message/skill-message-set-output-pad! child n)
    nil))

(defn chat-history-set-output-pad!
  "Set horizontal padding on all messages and the info banner. Theme needs
   no equivalent walk: components subscribe to ui.subs/theme-sub themselves
   (Stage 5); output-pad is still a constructor-threaded value."
  [ch n]
  (reset! (:output-pad-atom ch) n)
  (doseq [child (message-comps ch)]
    (set-pad-on! child n)))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type ChatHistoryComponent
  protocols/IFocusable
  (focused [_this] false)
  (set-focused! [_this _val]))

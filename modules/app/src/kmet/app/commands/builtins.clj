(ns kmet.app.commands.builtins
  "Engine-portable builtin slash commands: they run against the command
   ctx's :runtime — a front end's agent handle — and the shared command-UI
   capabilities (:message, :info, :warning, :set-title), so any front end
   can dispatch them. The interactive mode registers its view-bound
   commands itself (kmet.ui.tui.interactive.builtins)."
  (:require [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.app.commands :as commands]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.session.core :as session]
            [kmet.app.session.export :as session-export]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.inspect :as inspect]))

(defn- out
  "Call a shared command-UI capability; an absent capability is inert (a
   front end need not implement every output channel)."
  [ui capability & args]
  (when-let [f (get ui capability)]
    (apply f args)))

(defn- handle-continue
  [{:keys [ui] engine :runtime} _]
  (let [agent-state (runtime/agent-state engine)]
    (cond
      ;; A run may have ended in :error (network failure, retries exhausted)
      ;; — that's exactly when /continue is useful, so only actively-running
      ;; states refuse. Both the UI turn flag (set synchronously on submit)
      ;; and the agent status (:thinking/:executing, set by the run future)
      ;; are checked.
      (or (runtime/turn-running? engine)
          (contains? #{:thinking :executing} @(:status agent-state)))
      (out ui :info "Continue" "Wait for the current response to finish before continuing.")

      @(:compacting? agent-state)
      (out ui :info "Continue" "Wait for the in-progress compaction to finish before continuing.")

      (empty? (loop-state/get-context agent-state))
      (out ui :info "Continue" "No conversation to continue.")

      :else
      (do (debug/log "/continue command")
          ;; The handle's :send! starts with no message, which is what
          ;; /continue means. A front end always hands its commands a handle;
          ;; without one there is no run to continue.
          (if-let [send! (:send! engine)]
            (send! nil {:target :next-turn})
            (out ui :warning "This front end cannot continue the session."))))))

(defn- handle-name
  [{:keys [ui] engine :runtime} args]
  (let [sess (runtime/session engine)]
    (cond
      (nil? sess)
      (out ui :message "No active session.")

      (seq args)
      (let [sanitized (runtime/set-session-name! engine args)]
        (out ui :set-title)
        (when-not (= args sanitized)
          ;; pi: warn when normalization changed the input
          (out ui :warning (str "Session name was normalized from " (pr-str args)
                                " to " (pr-str sanitized))))
        (out ui :info "Name" (str "Session name set: " sanitized)))

      :else
      (if-let [current (session/get-session-name sess)]
        (out ui :info "Name" (str "Session name: " current))
        (out ui :warning "Usage: /name <name>")))))

(defn- handle-export
  [{:keys [ui] engine :runtime} args]
  (let [sess (runtime/session engine)
        arg (session-export/parse-path-argument args)]
    (cond
      (nil? sess)
      (out ui :info "Export" "No active session.")

      (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
      (out ui :info "Export" "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>.")

      :else
      (try
        (let [path (runtime/export-html! engine {:path arg})]
          (out ui :info "Export" (str "Session exported to: " path)))
        (catch Exception e
          (out ui :info "Export" (str "Failed to export session: "
                                      (or (ex-message e) (str e)))))))))

(defn- parse-compact-args
  "Parse /compact's arguments: a leading keep:N is a structured cut option;
   the rest is custom instructions. keep:N alone is valid; a malformed
   keep: prefix is reported instead of being passed as prose."
  [args]
  (let [arg (str/trim (or args ""))]
    (cond
      (empty? arg)
      {:custom-instructions nil :keep-user-turns nil}

      (not (str/starts-with? (str/lower-case arg) "keep:"))
      {:custom-instructions arg :keep-user-turns nil}

      :else
      (if-let [[_ n rest] (re-matches #"(?i)keep:(\d+)(?:\s+(?s)(.*))?" arg)]
        (let [keep (parse-long n)
              instructions (let [r (str/trim (or rest ""))]
                             (when (seq r) r))]
          (if (some? keep)
            {:custom-instructions instructions :keep-user-turns keep}
            {:error "Usage: /compact [keep:N] [instructions]"}))
        {:error "Usage: /compact [keep:N] [instructions]"}))))

(defn- handle-compact
  [{:keys [ui] engine :runtime} args]
  (let [{:keys [custom-instructions keep-user-turns error]} (parse-compact-args args)]
    (if error
      (out ui :warning (str "Compact: " error))
      ;; A front end always hands its commands a handle; the handle owns the
      ;; deferred-compaction queue a running prompt fills.
      (if-let [request (:request-compaction! engine)]
        (try
          (case (request custom-instructions
                         {:keep-user-turns keep-user-turns
                          :on-complete
                          (fn [result]
                            (when-not (= :aborted result)
                              ;; pi: handleCompactCommand ignores the thrown
                              ;; compaction error — compaction_end surfaces it
                              ;; (showError for manual); the completion reply
                              ;; is only for success and nothing-to-compact.
                              (out ui :info "Compact"
                                   (if result
                                     "Session compacted."
                                     "Nothing to compact (session too small)."))))})
            ;; A run in flight defers the compaction (kmet's cancel is cooperative,
            ;; so compacting under a live run would race it — pi instead aborts the
            ;; run first).
            :scheduled
            (out ui :info "Compact"
                 "Compaction scheduled — it will run when the current response finishes.")

            :compacting
            (out ui :info "Compact" "Compaction already in progress.")

            ;; :started — the completion reply arrives through :on-complete
            nil)
          (catch Exception e
            (out ui :warning
                 (str "Compact: " (or (ex-message e) (str e))))))
        (out ui :warning "This front end cannot compact the session.")))))

(defn- handle-units
  "Show the process's unit tree: one flat line per ctx and per mounted fiber,
   with a pending fiber's `waiting-for` and a failed one's error, and each
   fiber's state, `inject`, `provides` and effect labels (`kmet.app.inspect`).

   ROOT-CTX-FN returns the ctx to render and is read per invocation, so a root
   booted after registration is the one rendered. It is the **process root**,
   not the command's agent: the rows `/units` exists to show are mounts under
   the root (the same tree `--dump-state` prints), while an agent's ctx holds
   what a preset composed on it. An engine with no root behind it renders its
   own agent ctx — the most specific tree a bare engine can answer."
  [root-ctx-fn {:keys [ui] engine :runtime} _]
  (out ui :info "Units"
       (inspect/units (or (root-ctx-fn) (runtime/agent-ctx engine)))))

(defn register-runtime-commands!
  "Register the engine-portable builtins (untagged: every front end
   dispatches them) on the app root. A front end calls this with its builtin
   registration and ROOT-CTX-FN, the process root ctx `/units` renders — read
   per invocation, so a root booted after registration is the one rendered."
  [root-ctx-fn]
  (commands/register-builtin!

   {:name "units"
    :description "List the ctx tree: mounted units, their state and what they wait for"
    :handler (partial handle-units root-ctx-fn)})
  (commands/register-builtin!

   {:name "continue"
    :description "Continue where the agent left off (e.g. after a network error)"
    :handler handle-continue})
  (commands/register-builtin!

   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler handle-name})
  (commands/register-builtin!

   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler handle-export})
  (commands/register-builtin!

   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "[keep:N] [instructions]"
    :handler handle-compact}))

(ns kmet.ui.tui.interactive.test-commands
  "Interactive slash commands: the builtin auth pair, /thinking, /continue, the
   status indicator's model swap, /scoped-models and /settings, and /followup."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.test-utils :as tu]
            [clojure.string :as str]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.core :as tui]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.app.commands :as commands]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.model-selector :as model-selector]
            [kmet.ui.tui.thinking-selector :as thinking-selector]
            [kmet.ai.models :as m]
            [kmet.ai.auth :as auth]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.app.loop :as agent]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.srce.core :as srce]
            [kmet.ui.tui.interactive.test-handle-support :as hs]
            [kmet.app.config :as cfg]
            [kmet.libs.concurrent :as concurrent]
            [kmet.ui.tui.interactive.test-support :as support]))

;; The interactive builtins register the app's whole slash registry; clear it
;; so a later namespace never sees the tree filled by this one.
(t/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

;; ─── Builtin auth commands (Phase 3) ───────────────────────────────────────

(deftest test-builtin-login-logout-registered
  (testing "login/logout are real builtins inside register-builtin-commands!
            (not dropped or left as top-level forms)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [login (commands/find-command "login")
          logout (commands/find-command "logout")]
      (t/is (some? login) "login registered")
      (t/is (= "Configure provider authentication" (:description login)))
      (t/is (some? (:handler login)) "login has a handler")
      (t/is (some? logout) "logout registered")
      (t/is (= "Remove provider authentication" (:description logout)))
      (t/is (some? (:handler logout)) "logout has a handler"))))

(deftest test-view-builtin-through-the-command-ctx
  (testing "the registry adapter unwraps :state for a view-bound builtin:
            /help dispatched through the real command ctx reaches the chat"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          msgs (atom [])
          cs (hs/core-state handle {:tui nil})]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (swap! msgs conj m))]
        ((:handler (commands/find-command "help")) (turn/command-context cs) ""))
      (t/is (some #(str/includes? (str (:content %)) "Available commands") @msgs)
            "the view-bound handler saw the CoreState through :state"))))

(deftest test-name-command-portable
  (testing "/name runs from the command ctx over a handle: it renames the live
            session the handle answers and reports through the shared UI
            capabilities (no CoreState)"
    (let [handle (hs/new-handle)
          sess (runtime/session handle)
          calls (atom [])]
      ;; the boot published the composition's registry as the process handle,
      ;; so the builtins go into the registry the handle reads
      (commands/clear-commands!)
      ((var builtins/register-builtin-commands!) cfg/default-config)
      ((:handler (commands/find-command "name"))
       {:runtime handle
        :ui {:info (fn [label content] (swap! calls conj [:info label content]))
             :set-title (fn [] (swap! calls conj [:set-title]))}}
       "  my session  ")
      (t/is (= "my session" (session/get-session-name sess)))
      (t/is (= [:set-title] (first @calls)) "the title refresh comes first")
      (t/is (= [:info "Name" "Session name set: my session"] (second @calls)))
      (t/is (= 2 (count @calls)) "no warning for an already-clean name"))))

(deftest test-builtins-do-not-clobber-extension-commands
  (testing "builtin registration skips names an extension already took
            (extensions load before the layout is built; the shipped /tools
            extension replaces the builtin tools listing)"
    (commands/clear-commands!)
    (commands/register-command!
     {:name "tools" :description "extension version" :handler (fn [_ _] nil)})
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (t/is (= "extension version" (:description (commands/find-command "tools")))
          "extension command survives builtin registration")
    (t/is (some? (commands/find-command "model")) "unclaimed builtins still register")))

(deftest test-login-logout-unmatched-reference
  (testing "an unmatched /login reference opens the provider selector pre-filled;
           /logout always opens the stored-credential selector (pi)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [sel-ref (atom nil)]
      (with-redefs [dock/mount! (fn [_ component & _] (reset! sel-ref component))
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/show-warning! (fn [_ _] nil)
                    auth/get-credentials (fn [] {:github-copilot {:type :oauth
                                                                  :access "a" :refresh "r"
                                                                  :expires 9e99}})]
        ((:handler (commands/find-command "login")) {} "nonexistent-provider")
        (t/is (some? @sel-ref) "provider selector mounted")
        (t/is (= :login (:mode @(:state-atom @sel-ref))))
        (t/is (= "nonexistent-provider" (:search @(:state-atom @sel-ref)))
              "the typed reference pre-fills the filter")
        (reset! sel-ref nil)
        ((:handler (commands/find-command "logout")) {} "")
        (t/is (some? @sel-ref) "logout selector mounted")
        (t/is (= :logout (:mode @(:state-atom @sel-ref))))))))

(deftest test-model-command-switches-model
  (testing "/model resolves provider/model patterns and switches the agent"
    (commands/clear-commands!)
    (m/load-catalogs! nil)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})]
      (with-redefs [auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    model-selector/sync-footer-model! (fn [_] nil)
                    cfg/set-default-model! (fn [_ _] nil)]
        (testing "provider/model pattern"
          ((:handler (commands/find-command "model")) cs "deepseek/deepseek-v4-pro")
          (t/is (= :deepseek @(:provider ag)))
          (t/is (= "deepseek-v4-pro" @(:model ag))))
        (testing ":thinking suffix sets the agent thinking level"
          ((:handler (commands/find-command "model")) cs "deepseek/deepseek-v4-pro:high")
          (t/is (= :high @(:thinking ag))))
        (testing "unmatched pattern opens the selector with the failed term
                  pre-filled (pi handleModelCommand — no catalog refresh:
                  kmet's catalogs are static)"
          (let [selector-term (atom nil)]
            (with-redefs [model-selector/show-model-selector (fn [_ & [term]] (reset! selector-term term))]
              ((:handler (commands/find-command "model")) cs "nope")
              (t/is (= "nope" @selector-term) "selector opened with the failed term"))))))))

;; ─── /thinking command ──────────────────────────────────────────────────────

(deftest test-thinking-command-registered
  (testing "/thinking is a real builtin inside register-builtin-commands!"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "thinking")]
      (t/is (some? c) "thinking registered")
      (t/is (= "Set thinking level" (:description c)))
      (t/is (= "<level>" (:argument-hint c)))
      (t/is (some? (:handler c)) "thinking has a handler")
      (t/is (some? (:get-argument-completions c))
            "thinking completes its level argument"))))

(deftest test-thinking-command-arg-sets-level
  (testing "/thinking <level> applies the level to the session AND saves it
            as the settings default — the choice survives a restart (kmet
            deviation: pi's direct command stays session-scoped)"
    (commands/clear-commands!)
    (m/load-catalogs! nil)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          status (atom nil)
          saved (atom nil)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :deepseek
                                    :model "deepseek-v4-pro"
                                    :tui nil})]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ m] (reset! status m))
                    model-selector/sync-footer-model! (fn [_] nil)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    tui/tui-request-render (fn [_])]
        (testing "a supported level applies and persists"
          ((:handler (commands/find-command "thinking")) cs "high")
          (t/is (= :high @(:thinking ag)) "agent thinking level set")
          (t/is (= "Default thinking level: high" @status)
                "status reports the saved default")
          (t/is (= [[:thinking] :high] @saved)
                "the level is written to settings.edn"))
        (testing "matching is case-insensitive"
          ((:handler (commands/find-command "thinking")) cs "MAX")
          (t/is (= :max @(:thinking ag)))
          (t/is (= [[:thinking] :max] @saved) "the matched level is persisted"))
        (testing "an unsupported level warns with the available list"
          (let [warning (atom nil)]
            (with-redefs [chat-history/show-warning! (fn [_ m] (reset! warning m))]
              ((:handler (commands/find-command "thinking")) cs "medium")
              (t/is (= "Unknown thinking level \"medium\". Available levels: off, high, max."
                       @warning)
                    "warns listing the model's levels")
              (t/is (= :max @(:thinking ag)) "thinking unchanged")
              (t/is (= [[:thinking] :max] @saved)
                    "a miss does not rewrite the saved default"))))))))

(deftest test-thinking-command-bare-opens-selector
  (testing "bare /thinking mounts the level selector for a reasoning model;
            a model without supported levels gets the cycle status"
    (commands/clear-commands!)
    (m/load-catalogs! nil)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [status (atom nil)
          sel-ref (atom nil)
          reasoning-cs (hs/core-state (hs/new-handle)
                                      {:config cfg/default-config
                                       :provider :deepseek
                                       :model "deepseek-v4-pro"
                                       :tui nil})
          plain-cs (hs/core-state (hs/new-handle)
                                  {:config cfg/default-config
                                   :provider :ghost
                                   :model "unknown-model"
                                   :tui nil})]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ m] (reset! status m))
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    ;; deterministic default — must not depend on the real
                    ;; ~/.kmet/agent/settings.edn on the dev machine
                    thinking-selector/default-thinking-level (fn [_] :off)
                    dock/mount! (support/capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (testing "reasoning model → the selector mounts"
          ((:handler (commands/find-command "thinking")) reasoning-cs "")
          (t/is (some? @sel-ref) "selector mounted")
          (t/is (= [:off :high :max] (thinking-selector/thinking-selector-get-levels @sel-ref))
                "selector lists the model's supported levels"))
        (testing "unknown model → only :off → the cycle status, no selector"
          ((:handler (commands/find-command "thinking")) plain-cs "")
          (t/is (= "Current model does not support thinking" @status)))))))

(deftest test-thinking-selector-persist-wiring
  (testing "the mounted selector's Ctrl+S path runs the mode's persist logic:
            agent level set + [:thinking] saved to settings (pi Ctrl+S →
            selectThinkingLevel(level, true))"
    (commands/clear-commands!)
    (m/load-catalogs! nil)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          saved (atom nil)
          sel-ref (atom nil)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :deepseek
                                    :model "deepseek-v4-pro"
                                    :tui nil})]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ _] nil)
                    thinking-selector/default-thinking-level (fn [_] :off)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    model-selector/sync-footer-model! (fn [_] nil)
                    dock/mount! (support/capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        ((:handler (commands/find-command "thinking")) cs "")
        (let [sel @sel-ref]
          (t/is (some? sel) "selector mounted")
          ;; current :off (default) → move to :high (index 1) → Ctrl+S
          (protocols/handle-input sel "\u001b[B")
          (protocols/handle-input sel "\u0013")
          (t/is (= [[:thinking] :high] @saved)
                "Ctrl+S saves [:thinking] <level> to settings")
          (t/is (= :high @(:thinking ag)) "agent thinking level set"))))))

;; ─── /continue command ─────────────────────────────────────────────────────

(deftest test-continue-registered
  (testing "/continue is a real builtin inside register-builtin-commands!"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "continue")]
      (t/is (some? c) "continue registered")
      (t/is (= "Continue where the agent left off (e.g. after a network error)"
               (:description c)))
      (t/is (some? (:handler c)) "continue has a handler"))))

(deftest test-continue-refuses-while-running
  (testing "/continue refuses while the agent is running"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          msg (atom nil)
          cs (hs/core-state handle {:turn-running? true
                                    :tui nil})]
      (reset! (:status ag) :thinking)
      (swap! (:messages ag) conj {:role :user :content [{:type :text :text "hi"}]})
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) (support/command-ctx cs) ""))
      (t/is (= "Wait for the current response to finish before continuing."
               (:content @msg))
            "refuses while a turn is running"))))

(deftest test-continue-refuses-empty-context
  (testing "/continue refuses when there is no conversation to continue"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          msg (atom nil)
          cs (hs/core-state handle {:turn-running? false
                                    :tui nil})]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) (support/command-ctx cs) ""))
      (t/is (= "No conversation to continue." (:content @msg))))))

(deftest test-continue-starts-through-the-handle
  (testing "/continue starts through the handle's :send!; without one the
            command reports that this front end cannot continue"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle* (hs/new-handle)
          ag (runtime/agent-state handle*)
          cs (hs/core-state handle* {:turn-running? false
                                     :tui nil})
          engine (:engine cs)]
      (swap! (:messages ag) conj {:role :user :content [{:type :text :text "hi"}]})
      (let [sent (atom nil)
            handle (assoc engine :send! (fn [msg opts] (reset! sent [msg opts])))]
        ((:handler (commands/find-command "continue"))
         (assoc (support/command-ctx cs) :runtime handle) "")
        (t/is (= [nil {:target :next-turn}] @sent)
              "the handle's send starts a message-less run"))
      (let [warned (atom nil)]
        (with-redefs [chat-history/show-warning! (fn [_ m] (reset! warned m))]
          ((:handler (commands/find-command "continue"))
           (assoc (support/command-ctx cs) :runtime (dissoc engine :send!)) ""))
        (t/is (= "This front end cannot continue the session." @warned)
              "an engine without :send! is reported, not run through a driver")))))

(deftest test-compact-schedules-while-running
  (testing "/compact schedules while a run is active even when the status atom
            already reads idle — the settle window (a post-run compaction or
            a pre-settle continuation) keeps the run flag true, and the run
            flag is what counts (pi: isStreaming)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          msg (atom nil)
          cs (hs/core-state handle {:turn-running? true
                                    :tui nil})]
      (t/is (= :idle @(:status ag))
            "precondition: the status atom already reads idle")
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "compact")) (support/command-ctx cs) ""))
      (t/is (= "Compaction scheduled — it will run when the current response finishes."
               (:content @msg))
            "the request is deferred, not refused")
      (t/is (= 1 (count (runtime/pending-compaction (:engine cs))))
            "the compaction is queued for the settle"))))

(deftest test-continue-starts-run-without-message
  (testing "/continue starts an agent run on the existing context with no new
            user message — the model picks up the interrupted turn (e.g. after
            a network error the last entry is an unanswered user message)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          _ (swap! (:messages ag) conj
                   {:role :user :content [{:type :text :text "fix the bug"}]})
          started (atom nil)
          cs (hs/core-state handle {:turn-running? false
                                    :tui nil})]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)
                    agent/run-agent-turn (fn [a opts]
                                           (reset! started [a opts])
                                           (future))
                    status/activate-working-indicator! (fn [_] nil)
                    status/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)]
        ((:handler (commands/find-command "continue")) (support/command-ctx cs) ""))
      (t/is (some? @started) "run-agent-turn called")
      (t/is (identical? ag (first @started)) "runs on the current agent state")
      (t/is (nil? (:message (second @started)))
            "no :message value — no new user message is added")
      (t/is (true? (runtime/turn-running? (:engine cs))) "UI turn flag set"))))

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The working indicator must survive mid-turn transient swaps: after a retry
;; backoff or compaction the next turn-start revives it, and a stale end event
;; (auto-retry-end / compaction-end) must not stop an indicator that was
;; already replaced (pi: clearStatusIndicator(kind) is kind-gated).

(defn- working-status? [cs]
  (let [lines (support/status-lines cs)]
    (and (= 2 (count lines))
         (boolean (some #(re-find #"Working" %) lines)))))

(defn- blank-status? [cs]
  (let [lines (support/status-lines cs)]
    (and (= 2 (count lines))
         (every? #(re-find #"^\s*$" %) lines))))

(deftest test-status-indicator-survives-retry
  (testing "the working indicator is revived after a retry backoff and kept
            by the kind-gated auto-retry-end (pi: agent_start after
            continue() re-shows WorkingStatusIndicator)"
    (let [cs (support/test-status-cs)]
      (testing "submit activates the working indicator"
        ((var status/activate-working-indicator!) cs)
        (t/is (working-status? cs)))
      (testing "auto-retry-start swaps in the retry countdown"
        ((var status/show-status-indicator!) cs :retry
                                             (status-indicator/make-retry-status-indicator 1 3 2000))
        (t/is (not (working-status? cs))))
      (testing "turn-start after the backoff revives the working indicator"
        ((var status/activate-working-indicator!) cs)
        (t/is (working-status? cs)))
      (testing "auto-retry-end no-ops once the working indicator is active"
        ((var status/clear-status-indicator!) cs :retry)
        (t/is (working-status? cs))
        (t/is (nil? @(:status-current cs))))
      (testing "turn end clears to the idle two rows"
        ((var status/clear-status-indicator!) cs)
        (t/is (blank-status? cs))))))

(deftest test-status-indicator-survives-compaction
  (testing "in-loop compaction clears at compaction-end and the following
            turn-start revives the working indicator"
    (let [cs (support/test-status-cs)]
      ((var status/activate-working-indicator!) cs)
      ((var status/show-status-indicator!) cs :compaction
                                           (status-indicator/make-compaction-status-indicator))
      (t/is (not (working-status? cs)))
      ((var status/clear-status-indicator!) cs :compaction)
      (t/is (blank-status? cs))
      ((var status/activate-working-indicator!) cs)
      (t/is (working-status? cs)))))

(deftest test-status-indicator-kind-gated-clear
  (testing "a stale end event cannot stop an indicator it didn't own"
    (let [cs (support/test-status-cs)]
      ((var status/activate-working-indicator!) cs)
      ((var status/show-status-indicator!) cs :compaction
                                           (status-indicator/make-compaction-status-indicator))
      ;; auto-retry-end arriving while compaction is active must no-op
      ((var status/clear-status-indicator!) cs :retry)
      (t/is (not (working-status? cs)))
      (t/is (= :compaction (:kind @(:status-current cs))))
      (testing "cancel during backoff clears unconditionally; the late
                retry-end no-ops"
        ((var status/clear-status-indicator!) cs)
        (t/is (blank-status? cs))
        ((var status/clear-status-indicator!) cs :retry)
        (t/is (blank-status? cs))
        (t/is (nil? @(:status-current cs)))))))

(deftest test-current-status-indicator
  (testing "the editor-border hook's status source: the transient swap when
            one is up, else the active working indicator, else nil"
    (let [cs (support/test-status-cs)]
      (t/is (nil? ((var status/current-status-indicator) cs)))
      ((var status/activate-working-indicator!) cs)
      (t/is (identical? (:status-indicator cs)
                        ((var status/current-status-indicator) cs)))
      (let [retry (status-indicator/make-retry-status-indicator 1 3 2000)]
        ((var status/show-status-indicator!) cs :retry retry)
        (t/is (identical? retry ((var status/current-status-indicator) cs))))
      ((var status/clear-status-indicator!) cs)
      (t/is (nil? ((var status/current-status-indicator) cs))))))

(deftest ^:slow test-transient-indicator-drives-frames
  (testing "a transient indicator shown outside an agent turn arms its own
            frame driver — the clock-driven frames only advance when
            something requests renders (manual /compact regression: without
            the driver the compaction spinner sits on one frame)"
    (let [cs (assoc (support/test-status-cs)
                    :tui {:running? (atom true)
                          :render-requested? (atom false)})
          tui (:tui cs)]
      ((var status/show-status-indicator!) cs :compaction
                                           (status-indicator/make-compaction-status-indicator))
      (let [first-driver (:driver @(:status-current cs))]
        (t/is (some? first-driver) "the driver is recorded on the status entry")
        (reset! (:render-requested? tui) false)
        (t/is (loop [deadline (+ (concurrent/monotonic-ms) 2000)]
                (cond
                  @(:render-requested? tui) true
                  (< (concurrent/monotonic-ms) deadline) (do (Thread/sleep 5) (recur deadline))
                  :else false))
              "frames are requested while it is up")
        (testing "swapping in the next indicator retires the previous driver"
          ((var status/show-status-indicator!) cs :retry
                                               (status-indicator/make-retry-status-indicator 1 3 2000))
          (let [next-driver (:driver @(:status-current cs))]
            (t/is (some? next-driver))
            (t/is (not (identical? first-driver next-driver)))
            (t/is (future-cancelled? first-driver))
            (testing "clearing cancels the driver with the status"
              ((var status/clear-status-indicator!) cs)
              (t/is (future-cancelled? next-driver)))))))))

(deftest test-clear-working-status
  (testing ":working clears the implicit working status — pi:
            setWorkingVisible(false) → clearStatusIndicator('working')"
    (let [cs (support/test-status-cs)]
      ((var status/activate-working-indicator!) cs)
      (t/is (working-status? cs))
      ((var status/clear-status-indicator!) cs :working)
      (t/is (blank-status? cs))
      (t/is (nil? @(:status-current cs)))))
  (testing "a transient indicator is not the working status — a :working
            clear leaves it"
    (let [cs (support/test-status-cs)]
      ((var status/show-status-indicator!) cs :compaction
                                           (status-indicator/make-compaction-status-indicator))
      ((var status/clear-status-indicator!) cs :working)
      (t/is (= :compaction (:kind @(:status-current cs)))))))

(deftest ^:slow test-release-background-status
  (testing "a background status (share/branch summary) releases its slot and
            gives the working spinner back while the turn still streams"
    (let [cs (support/test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var status/activate-working-indicator!) cs)
      ((var status/show-status-indicator!) cs :share share)
      (t/is (not (working-status? cs)))
      ((var status/release-background-status!) cs :share)
      (t/is (nil? @(:status-current cs)))
      (t/is (working-status? cs))))
  (testing "no revive when the turn ended"
    (let [cs (support/test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      (reset! (:turn-running? (:engine cs)) false)
      ((var status/show-status-indicator!) cs :share share)
      ((var status/release-background-status!) cs :share)
      (t/is (blank-status? cs))))
  (testing "a newer transient owns the slot — neither cleared nor displaced"
    (let [cs (support/test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var status/show-status-indicator!) cs :share share)
      ((var status/show-status-indicator!) cs :retry
                                           (status-indicator/make-retry-status-indicator 1 3 2000))
      ((var status/release-background-status!) cs :share)
      (t/is (= :retry (:kind @(:status-current cs))))))
  (testing "a same-kind replacement is released too (pi:
            clearStatusIndicator matches the active kind — a branch-summary
            retry re-shows the branch-summary indicator)"
    (let [cs (support/test-status-cs)
          first-share (spinner/make-spinner :text "one" :active true)
          second-share (spinner/make-spinner :text "two" :active true)]
      ((var status/show-status-indicator!) cs :share first-share)
      ((var status/show-status-indicator!) cs :share second-share)
      ((var status/release-background-status!) cs :share)
      (t/is (nil? @(:status-current cs)) "the active indicator was released")
      (t/is (working-status? cs) "the working spinner is given back")))
  (testing "an already-revived working spinner is not restarted (its
            animation clock survives)"
    (let [cs (support/test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var status/show-status-indicator!) cs :share share)
      ;; a :turn-start revived the working spinner while the background op
      ;; ran — the late release must not touch it
      ((var status/activate-working-indicator!) cs)
      (let [start @(:start-atom (:spinner (:status-indicator cs)))]
        (Thread/sleep 5)
        ((var status/release-background-status!) cs :share)
        (t/is (= start @(:start-atom (:spinner (:status-indicator cs))))))))
  (testing "a turn ending mid-revival must not leave a spinner behind (the
            post-revival re-check rolls it back)"
    (let [cs (support/test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)
          real (var-get (var status/activate-working-indicator!))]
      ((var status/show-status-indicator!) cs :share share)
      (with-redefs-fn {(var status/activate-working-indicator!)
                       ;; the revival lands in a turn that just ended: the run
                       ;; owns the flag and settles it before the teardown
                       ;; reaches the UI, so the engine is where to end it
                       (fn [c] (reset! (:turn-running? (:engine c)) false) (real c))}
        (fn [] ((var status/release-background-status!) cs :share)))
      (t/is (not (status-indicator/status-indicator-active? (:status-indicator cs)))
            "the revived spinner is stopped again"))))

;; ─── /scoped-models + /settings (missing slash commands) ───────────────────

(deftest test-scoped-models-settings-registered
  (testing "scoped-models and settings are real builtins"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [scoped (commands/find-command "scoped-models")
          settings (commands/find-command "settings")]
      (t/is (some? scoped))
      (t/is (= "Enable/disable models for Ctrl+P cycling" (:description scoped)))
      (t/is (some? (:handler scoped)))
      (t/is (some? settings))
      (t/is (some? (:handler settings))))))

(deftest test-hotkeys-registered-with-real-handler
  (testing "/hotkeys is a real builtin: its handler mounts the hiccup view,
            which shows wired actions only"
    (commands/clear-commands!)
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [hotkeys (commands/find-command "hotkeys")]
      (t/is (some? hotkeys) "hotkeys registered")
      (t/is (= "Show all keyboard shortcuts" (:description hotkeys)))
      (t/is (some? (:handler hotkeys)))
      (let [ed (editor/make-editor)
            ch (chat-history/make-chat-history)]
        ;; wire one app action; app.suspend / app.message.copy stay unwired
        ;; on this bare editor (the interactive layout installs app.suspend)
        (editor/editor-set-on-action! ed "app.tools.expand" (fn [] nil))
        ((:handler hotkeys) {:chat-history ch :editor ed} "")
        (let [msg (last @(:messages-atom ch))
              comp (:component msg)]
          (t/is (some? comp) "the hiccup view is the message component")
          (t/is (nil? (:content msg))
                "no fallback assistant text — the view renders the content")
          (let [lines (protocols/render comp 84)]
            (t/is (some #(str/includes? % "Toggle tool output expansion") lines)
                  "a wired app action has a row")
            (t/is (not (some #(str/includes? % "Suspend to background") lines))
                  "a declared-but-unwired action stays out"))
          (protocols/dispose comp))))))

(deftest test-hotkeys-wired-predicate
  (testing "wired-hotkey? answers from the editor's installed actions"
    (let [ed (editor/make-editor)]
      (editor/editor-set-on-action! ed "app.tools.expand" (fn [] nil))
      (let [wired? ((var builtins/make-hotkey-wired?) {:editor ed})]
        (t/is (wired? "tui.editor.cursorUp") "TUI ids are the editor's own")
        (t/is (wired? "app.tools.expand") "installed on the editor")
        (t/is (wired? "app.quit") "the global quit listener owns it")
        (t/is (not (wired? "app.suspend")) "not installed on this bare editor")
        (t/is (not (wired? "app.message.copy")) "tree-selector-only in kmet"))
      (testing "no editor: only the global-listener action survives"
        (let [wired? ((var builtins/make-hotkey-wired?) nil)]
          (t/is (wired? "app.quit"))
          (t/is (not (wired? "app.tools.expand"))))))))

;; ─── /followup command (pi: no equivalent — slash form of Alt+Enter) ──────

(deftest test-followup-command-registered
  (testing "/followup is a real builtin inside register-builtin-commands!"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "followup")]
      (t/is (some? c) "followup registered")
      (t/is (= "Queue a follow-up message (like Alt+Enter)" (:description c)))
      (t/is (= "<message>" (:argument-hint c)) "arg hint shown")
      (t/is (some? (:handler c)) "followup has a handler"))))

(deftest test-followup-command-queues-while-running
  (testing "/followup <text> while the agent runs queues the args as a
            follow-up (pi: handleFollowUp semantics) — nothing reaches the
            chat or context until the loop consumes it"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [cs (support/compaction-cs)]
      (reset! (:turn-running? (:engine cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)]
        ((:handler (commands/find-command "followup")) cs "do the thing"))
      (let [{:keys [steering follow-up]} (loop-state/queued-messages (runtime/agent-state (:engine cs)))]
        (t/is (= [] steering) "not steered into the running turn")
        (t/is (= ["do the thing"] follow-up) "args queued as follow-up"))
      (t/is (empty? @(:messages (runtime/agent-state (:engine cs))))
            "message NOT sent to the agent context yet"))))

(deftest test-followup-command-submits-when-idle
  (testing "/followup <text> while idle starts a run with the args as the
            message — Alt+Enter's idle path (pi: handleFollowUp → submit)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [cs (support/compaction-cs)
          started (atom [])]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)
                    agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    status/activate-working-indicator! (fn [_] nil)
                    status/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)]
        ((:handler (commands/find-command "followup")) cs "wrap it up"))
      (t/is (seq @started) "run-agent-turn called")
      (t/is (= "wrap it up" (get-in @started [1 :message]))
            "run carries the args as the message")
      (let [{:keys [steering follow-up]} (loop-state/queued-messages (runtime/agent-state (:engine cs)))]
        (t/is (empty? steering) "nothing queued")
        (t/is (empty? follow-up) "nothing queued")))))

(deftest test-followup-command-queues-during-compaction
  (testing "/followup <text> during compaction queues as follow-up (pi:
            handleFollowUp → queueCompactionMessage(text, followUp))"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [cs (support/compaction-cs)]
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)]
        ((:handler (commands/find-command "followup")) cs "later msg"))
      (t/is (= [{:text "later msg" :mode :follow-up}]
               (runtime/compaction-queued (:engine cs)))
            "queued with follow-up mode"))))

(deftest test-followup-command-blank-args-usage
  (testing "/followup with no args shows usage instead of queueing anything"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [cs (support/compaction-cs)
          msg (atom nil)]
      (reset! (:turn-running? (:engine cs)) true)
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "followup")) cs ""))
      (t/is (= "Usage: /followup <message>" (:content @msg))
            "usage info shown")
      (t/is (empty? (:follow-up (loop-state/queued-messages (runtime/agent-state (:engine cs)))))
            "nothing queued for blank args"))))

(deftest test-cancel-two-step-during-midrun-compaction
  (testing "escape during a mid-run compaction aborts the compaction first;
            a second escape then cancels the turn (pi: compaction_start
            swaps the escape handler to abortCompaction, restored at
            compaction_end — two-step escape)"
    (let [cs (support/compaction-cs)
          cancelled (atom 0)]
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (reset! (:signal (runtime/agent-state (:engine cs))) false)
      (reset! (:turn-running? (:engine cs)) true)
      (with-redefs [status/stop-anim-timer! (fn [_] nil)
                    status/clear-status-indicator! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)
                    chat-history/chat-history-finalize-streaming! (fn [_] nil)
                    chat-history/chat-history-finalize-thinking! (fn [_] nil)
                    chat-history/chat-history-remove-streaming-placeholder! (fn [_] nil)
                    agent/cancel-turn (fn [_] (swap! cancelled inc))]
        ;; first escape: compaction aborted only
        ((var turn/handle-cancel) cs)
        (t/is (true? @(:compaction-signal (runtime/agent-state (:engine cs)))) "compaction aborted")
        (t/is (false? @(:signal (runtime/agent-state (:engine cs)))) "the run is not cancelled")
        (t/is (true? (runtime/turn-running? (:engine cs))) "turn still running")
        (t/is (zero? @cancelled) "turn not cancelled")
        ;; compaction-end resets the flag; second escape cancels the turn
        (reset! (:compacting? (runtime/agent-state (:engine cs))) false)
        ((var turn/handle-cancel) cs)
        (t/is (= 1 @cancelled) "second escape cancels the turn")))))

(deftest test-tools-command-lists-the-engines-table
  (testing "/tools lists the table the engine runs on (4c-iii-c)"
    (commands/clear-commands!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          tools (srce/get (:ctx handle) :tools)
          _ (tools-registry/register-tool! tools
                                           {:name "probe" :label "Probe"
                                            :description "Says what it is"
                                            :params {:x {:type :string
                                                         :description "an x"
                                                         :required? true}}
                                            :execute (fn [_] {:content "ok"})})
          msg (atom nil)
          cs (hs/core-state handle)]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "tools")) (support/command-ctx cs) ""))
      (let [content (:content @msg)]
        (t/is (str/starts-with? content "Available tools (")
              "the listing is the tools section")
        (t/is (str/includes? content "  probe — Says what it is")
              "a tool the run's ctx holds is listed")
        (t/is (str/includes? content "    x (string) (required) — an x")
              "with its parameters and which are required")))))

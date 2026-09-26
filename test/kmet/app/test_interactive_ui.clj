(ns kmet.app.test-interactive-ui
  "Tests for the interactive-mode extension UI helpers: the autocomplete
   factory wrapper chain (pi: setupAutocompleteProvider) and the custom
   editor duck-typed transfer (pi: setCustomEditorComponent)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.macros :as macros]
            [kmet.tui.theme :as theme]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.core :as tui]
            [kmet.modes.interactive :as inter]
            [kmet.modes.interactive.state :as state]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.scoped-models-selector :as scoped-models-selector]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.app.ui.model-selector :as model-selector]
            [kmet.app.ui.thinking-selector :as thinking-selector]
            [kmet.ai.models :as m]
            [kmet.ai.auth :as auth]
            [kmet.app.loop :as agent]
            [kmet.libs.http :as http]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.ui.footer-data-provider :as fdp]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.app.event-bus :as event-bus]
            [kmet.test-utils :refer [slash]]))

(defn- capture-mount!
  "A dock/mount! stand-in for tests: records the component that receives
  keys (the focus target when given, else COMPONENT) in REF and returns a
  no-op done (pi: showSelector mounts into the editor dock and focuses the
  interactive child; the tests don't have a dock)."
  [ref]
  (fn [_ component & [focus]]
    (reset! ref (or focus component))
    (fn [])))

(def ^:private no-image-caps
  "Capabilities stub for the /settings tests: the image rows (Show images /
  Image width) exist only when the terminal reports image support
  (image-rows), so index-based row navigation must not depend on the
  developer's terminal. Stub get-capabilities alongside the other test
  redefs."
  {:images nil :true-color true :hyperlinks false})

(defn- transfer-editor! [app-ed custom-ed kb]
  ((var inter/transfer-editor!) app-ed custom-ed kb))

(defn- normalize [x]
  ((var inter/normalize-autocomplete-provider) x))

(deftest test-normalize-autocomplete-provider-protocol
  (testing "an AutocompleteProvider passes through unchanged"
    (let [p (ac/make-combined-provider :commands-fn (constantly []))]
      (t/is (identical? p (normalize p))))))

(deftest test-normalize-autocomplete-provider-map
  (testing "a duck-typed map provider is adapted to the protocol"
    (let [p (normalize {:get-suggestions (fn [_state] {:items [{:value "x" :label "X"}]
                                                       :prefix "x"})
                        :get-trigger-characters ["@"]})]
      (t/is (satisfies? ac/AutocompleteProvider p))
      (let [res (ac/get-suggestions p ["xa"] 0 1 {})]
        (t/is (= "x" (:prefix res)))
        (t/is (= "X" (-> res :items first :label))))
      (t/is (= ["@"] (ac/get-trigger-characters p)))
      ;; default apply-completion replaces the prefix
      (let [st (ac/apply-completion p ["xa"] 0 1 {:value "xyz" :label "xyz"} "x")]
        (t/is (= "xyza" (nth (:lines st) 0)))
        (t/is (= 3 (:cursor-col st)))))))

(deftest test-normalize-autocomplete-provider-nil
  (testing "non-provider values normalize to nil"
    (t/is (nil? (normalize nil)))
    (t/is (nil? (normalize 42)))))

(deftest test-transfer-editor!
  (testing "transfer-editor! copies text callbacks, appearance, provider,
            and action handlers onto a custom editor (pi duck-typing)"
    (let [app-ed (editor/make-editor)
          custom (editor/make-editor)
          _ (editor/editor-set-on-submit! app-ed (fn [t] (println t)))
          _ (editor/editor-set-on-action! app-ed "app.interrupt" (fn []))
          _ (editor/editor-set-autocomplete-provider!
             app-ed (ac/make-combined-provider :commands-fn (constantly [])))
          _ (reset! (:border-fn app-ed) (fn [s] s))
          _ (reset! (:terminal-rows-atom app-ed) (fn [] 24))
          _ (reset! (:padding-x app-ed) 3)]
      (transfer-editor! app-ed custom nil)
      (t/is (identical? @(:on-submit app-ed) @(:on-submit custom))
            "on-submit handler copied")
      (t/is (contains? @(:action-handlers custom) "app.interrupt")
            "app action handlers copied")
      (t/is (some? @(:autocomplete-provider custom))
            "autocomplete provider copied")
      (t/is (identical? @(:border-fn app-ed) @(:border-fn custom))
            "border fn copied (pi: borderColor property)")
      (t/is (= 3 @(:padding-x custom)) "padding copied")
      (t/is (some? @(:terminal-rows-atom custom))
            "dynamic-height source copied"))))

(deftest test-transfer-editor!-non-editor
  (testing "transfer to a non-editor component is a no-op"
    (let [app-ed (editor/make-editor)
          plain {:render (fn [_] [""])}]
      (t/is (nil? (transfer-editor! app-ed plain nil)))
      (t/is (= [:render] (keys plain)) "plain map untouched"))))

;; ─── Builtin auth commands (Phase 3) ───────────────────────────────────────

(deftest test-builtin-login-logout-registered
  (testing "login/logout are real builtins inside register-builtin-commands!
            (not dropped or left as top-level forms)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [login (commands/find-command "login")
          logout (commands/find-command "logout")]
      (t/is (some? login) "login registered")
      (t/is (= "Configure provider authentication" (:description login)))
      (t/is (some? (:handler login)) "login has a handler")
      (t/is (some? logout) "logout registered")
      (t/is (= "Remove provider authentication" (:description logout)))
      (t/is (some? (:handler logout)) "logout has a handler"))))

(deftest test-builtins-do-not-clobber-extension-commands
  (testing "builtin registration skips names an extension already took
            (extensions load before the layout is built; the shipped /tools
            extension replaces the builtin tools listing)"
    (commands/clear-commands!)
    (commands/register-command!
     {:name "tools" :description "extension version" :handler (fn [_ _] nil)})
    ((var inter/register-builtin-commands!) cfg/default-config)
    (t/is (= "extension version" (:description (commands/find-command "tools")))
          "extension command survives builtin registration")
    (t/is (some? (commands/find-command "model")) "unclaimed builtins still register")))

(deftest test-login-logout-unmatched-reference
  (testing "an unmatched /login reference opens the provider selector pre-filled;
           /logout always opens the stored-credential selector (pi)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [sel-ref (atom nil)]
      (with-redefs [dock/mount! (fn [_ component & _]
                                  (reset! sel-ref component)
                                  (fn []))
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/show-warning! (fn [_ _] nil)
                    auth/get-credentials (fn [] {:github-copilot {:type :oauth
                                                                  :access "a" :refresh "r"
                                                                  :expires 9e99}})]
        ((:handler (commands/find-command "login")) {} "nonexistent-provider")
        (t/is (some? @sel-ref) "provider selector mounted")
        (t/is (= :login (:mode @sel-ref)))
        (t/is (= "nonexistent-provider" (:search @(:state-atom @sel-ref)))
              "the typed reference pre-fills the filter")
        (reset! sel-ref nil)
        ((:handler (commands/find-command "logout")) {} "")
        (t/is (some? @sel-ref) "logout selector mounted")
        (t/is (= :logout (:mode @sel-ref)))))))

(deftest test-model-command-switches-model
  (testing "/model resolves provider/model patterns and switches the agent"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}]
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
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "thinking")]
      (t/is (some? c) "thinking registered")
      (t/is (= "Set thinking level" (:description c)))
      (t/is (= "<level>" (:argument-hint c)))
      (t/is (some? (:handler c)) "thinking has a handler")
      (t/is (some? (:get-argument-completions c))
            "thinking completes its level argument"))))

(deftest test-thinking-command-arg-sets-level
  (testing "/thinking <level> applies the level to the session (pi
            selectThinkingLevel without persist — no settings write)"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :deepseek :model "deepseek-v4-pro")
          status (atom nil)
          saved (atom ::none)
          cs {:agent-state (atom ag)
              :chat-history nil
              :editor (editor/make-editor)
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ m] (reset! status m))
                    model-selector/sync-footer-model! (fn [_] nil)
                    cfg/save-setting! (fn [_ _] (reset! saved ::called))
                    tui/tui-request-render (fn [_])]
        (testing "a supported level applies"
          ((:handler (commands/find-command "thinking")) cs "high")
          (t/is (= :high @(:thinking ag)) "agent thinking level set")
          (t/is (= "Thinking level: high" @status) "status reports the level")
          (t/is (= ::none @saved) "plain Enter does not persist the default"))
        (testing "matching is case-insensitive"
          ((:handler (commands/find-command "thinking")) cs "MAX")
          (t/is (= :max @(:thinking ag))))
        (testing "an unsupported level warns with the available list"
          (let [warning (atom nil)]
            (with-redefs [chat-history/show-warning! (fn [_ m] (reset! warning m))]
              ((:handler (commands/find-command "thinking")) cs "medium")
              (t/is (= "Unknown thinking level \"medium\". Available levels: off, high, max."
                       @warning)
                    "warns listing the model's levels")
              (t/is (= :max @(:thinking ag)) "thinking unchanged"))))))))

(deftest test-thinking-command-bare-opens-selector
  (testing "bare /thinking mounts the level selector for a reasoning model;
            a model without supported levels gets the cycle status"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [status (atom nil)
          sel-ref (atom nil)
          reasoning-cs {:agent-state (atom (agent/make-agent-state :provider :deepseek
                                                                   :model "deepseek-v4-pro"))
                        :chat-history nil
                        :editor (editor/make-editor)
                        :footer-comp nil
                        :footer-provider nil
                        :config cfg/default-config
                        :tui nil}
          plain-cs {:agent-state (atom (agent/make-agent-state :provider :ghost
                                                               :model "unknown-model"))
                    :chat-history nil
                    :editor (editor/make-editor)
                    :config cfg/default-config
                    :tui nil}]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ m] (reset! status m))
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    ;; deterministic default — must not depend on the real
                    ;; ~/.kmet/agent/settings.edn on the dev machine
                    thinking-selector/default-thinking-level (fn [_] :off)
                    dock/mount! (capture-mount! sel-ref)
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
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :deepseek :model "deepseek-v4-pro")
          saved (atom nil)
          sel-ref (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :editor (editor/make-editor)
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}]
      (with-redefs [chat-history/chat-history-show-status! (fn [_ _] nil)
                    thinking-selector/default-thinking-level (fn [_] :off)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    model-selector/sync-footer-model! (fn [_] nil)
                    dock/mount! (capture-mount! sel-ref)
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
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "continue")]
      (t/is (some? c) "continue registered")
      (t/is (= "Continue where the agent left off (e.g. after a network error)"
               (:description c)))
      (t/is (some? (:handler c)) "continue has a handler"))))

(deftest test-continue-refuses-while-running
  (testing "/continue refuses while the agent is running"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          msg (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom true)
              :tui nil}]
      (reset! (:status ag) :thinking)
      (swap! (:messages ag) conj {:role :user :content [{:type :text :text "hi"}]})
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (= "Wait for the current response to finish before continuing."
               (:content @msg))
            "refuses while a turn is running"))))

(deftest test-continue-refuses-empty-context
  (testing "/continue refuses when there is no conversation to continue"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          msg (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom false)
              :tui nil}]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (= "No conversation to continue." (:content @msg))))))

(deftest test-continue-starts-run-without-message
  (testing "/continue starts an agent run on the existing context with no new
            user message — the model picks up the interrupted turn (e.g. after
            a network error the last entry is an unanswered user message)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          _ (swap! (:messages ag) conj
                   {:role :user :content [{:type :text :text "fix the bug"}]})
          started (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom false)
              :tui nil
              :footer-comp nil
              :footer-provider nil
              :status-root nil
              :status-indicator nil
              :status-current (atom nil)
              :anim-timer (atom nil)}]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)
                    agent/run-agent-turn (fn [a opts]
                                           (reset! started [a opts])
                                           (future))
                    inter/activate-working-indicator! (fn [_] nil)
                    inter/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (some? @started) "run-agent-turn called")
      (t/is (identical? ag (first @started)) "runs on the current agent state")
      (t/is (not (contains? (second @started) :message))
            "no :message option — no new user message is added")
      (t/is (true? @(:running-turn? cs)) "UI turn flag set"))))

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The working indicator must survive mid-turn transient swaps: after a retry
;; backoff or compaction the next turn-start revives it, and a stale end event
;; (auto-retry-end / compaction-end) must not stop an indicator that was
;; already replaced (pi: clearStatusIndicator(kind) is kind-gated).

(defn- test-status-cs
  "A minimal CoreState-like map for the status swap helpers: the status
   layer is a hiccup/root-mounted fn component over a :status-current atom
   (dsl.md stage 4), so renders go through the root's reaction + reconcile.
   The editor atom holds no editor — a custom editor that cannot embed the
   status, so the standalone layer renders."
  []
  (let [si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ced (atom nil)]
    {:tui {:render-requested? (atom false)}
     :status-indicator si
     :status-current cur
     :current-editor-atom ced
     :status-root (hiccup/root (status-indicator/make-status-area cur si ced))
     :running-turn? (atom true)}))

(defn- status-lines [cs]
  (protocols/render (:status-root cs) 60))

(defn- working-status? [cs]
  (let [lines (status-lines cs)]
    (and (= 2 (count lines))
         (boolean (some #(re-find #"Working" %) lines)))))

(defn- blank-status? [cs]
  (let [lines (status-lines cs)]
    (and (= 2 (count lines))
         (every? #(re-find #"^\s*$" %) lines))))

(deftest test-status-indicator-survives-retry
  (testing "the working indicator is revived after a retry backoff and kept
            by the kind-gated auto-retry-end (pi: agent_start after
            continue() re-shows WorkingStatusIndicator)"
    (let [cs (test-status-cs)]
      (testing "submit activates the working indicator"
        ((var inter/activate-working-indicator!) cs)
        (t/is (working-status? cs)))
      (testing "auto-retry-start swaps in the retry countdown"
        ((var inter/show-status-indicator!) cs :retry
                                            (status-indicator/make-retry-status-indicator 1 3 2000))
        (t/is (not (working-status? cs))))
      (testing "turn-start after the backoff revives the working indicator"
        ((var inter/activate-working-indicator!) cs)
        (t/is (working-status? cs)))
      (testing "auto-retry-end no-ops once the working indicator is active"
        ((var inter/clear-status-indicator!) cs :retry)
        (t/is (working-status? cs))
        (t/is (nil? @(:status-current cs))))
      (testing "turn end clears to the idle two rows"
        ((var inter/clear-status-indicator!) cs)
        (t/is (blank-status? cs))))))

(deftest test-status-indicator-survives-compaction
  (testing "in-loop compaction clears at compaction-end and the following
            turn-start revives the working indicator"
    (let [cs (test-status-cs)]
      ((var inter/activate-working-indicator!) cs)
      ((var inter/show-status-indicator!) cs :compaction
                                          (status-indicator/make-compaction-status-indicator))
      (t/is (not (working-status? cs)))
      ((var inter/clear-status-indicator!) cs :compaction)
      (t/is (blank-status? cs))
      ((var inter/activate-working-indicator!) cs)
      (t/is (working-status? cs)))))

(deftest test-status-indicator-kind-gated-clear
  (testing "a stale end event cannot stop an indicator it didn't own"
    (let [cs (test-status-cs)]
      ((var inter/activate-working-indicator!) cs)
      ((var inter/show-status-indicator!) cs :compaction
                                          (status-indicator/make-compaction-status-indicator))
      ;; auto-retry-end arriving while compaction is active must no-op
      ((var inter/clear-status-indicator!) cs :retry)
      (t/is (not (working-status? cs)))
      (t/is (= :compaction (:kind @(:status-current cs))))
      (testing "cancel during backoff clears unconditionally; the late
                retry-end no-ops"
        ((var inter/clear-status-indicator!) cs)
        (t/is (blank-status? cs))
        ((var inter/clear-status-indicator!) cs :retry)
        (t/is (blank-status? cs))
        (t/is (nil? @(:status-current cs)))))))

(deftest test-current-status-indicator
  (testing "the editor-border hook's status source: the transient swap when
            one is up, else the active working indicator, else nil"
    (let [cs (test-status-cs)]
      (t/is (nil? ((var inter/current-status-indicator) cs)))
      ((var inter/activate-working-indicator!) cs)
      (t/is (identical? (:status-indicator cs)
                        ((var inter/current-status-indicator) cs)))
      (let [retry (status-indicator/make-retry-status-indicator 1 3 2000)]
        ((var inter/show-status-indicator!) cs :retry retry)
        (t/is (identical? retry ((var inter/current-status-indicator) cs))))
      ((var inter/clear-status-indicator!) cs)
      (t/is (nil? ((var inter/current-status-indicator) cs))))))

(deftest ^:slow test-transient-indicator-drives-frames
  (testing "a transient indicator shown outside an agent turn arms its own
            frame driver — the clock-driven frames only advance when
            something requests renders (manual /compact regression: without
            the driver the compaction spinner sits on one frame)"
    (let [cs (assoc (test-status-cs)
                    :tui {:running? (atom true)
                          :render-requested? (atom false)})
          tui (:tui cs)]
      ((var inter/show-status-indicator!) cs :compaction
                                          (status-indicator/make-compaction-status-indicator))
      (let [first-driver (:driver @(:status-current cs))]
        (t/is (some? first-driver) "the driver is recorded on the status entry")
        (reset! (:render-requested? tui) false)
        (Thread/sleep 120)
        (t/is (true? @(:render-requested? tui)) "frames are requested while it is up")
        (testing "swapping in the next indicator retires the previous driver"
          ((var inter/show-status-indicator!) cs :retry
                                              (status-indicator/make-retry-status-indicator 1 3 2000))
          (let [next-driver (:driver @(:status-current cs))]
            (t/is (some? next-driver))
            (t/is (not (identical? first-driver next-driver)))
            (t/is (future-cancelled? first-driver))
            (testing "clearing cancels the driver with the status"
              ((var inter/clear-status-indicator!) cs)
              (t/is (future-cancelled? next-driver)))))))))

(deftest test-clear-working-status
  (testing ":working clears the implicit working status — pi:
            setWorkingVisible(false) → clearStatusIndicator('working')"
    (let [cs (test-status-cs)]
      ((var inter/activate-working-indicator!) cs)
      (t/is (working-status? cs))
      ((var inter/clear-status-indicator!) cs :working)
      (t/is (blank-status? cs))
      (t/is (nil? @(:status-current cs)))))
  (testing "a transient indicator is not the working status — a :working
            clear leaves it"
    (let [cs (test-status-cs)]
      ((var inter/show-status-indicator!) cs :compaction
                                          (status-indicator/make-compaction-status-indicator))
      ((var inter/clear-status-indicator!) cs :working)
      (t/is (= :compaction (:kind @(:status-current cs)))))))

(deftest ^:slow test-release-background-status
  (testing "a background status (share/branch summary) releases its slot and
            gives the working spinner back while the turn still streams"
    (let [cs (test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var inter/activate-working-indicator!) cs)
      ((var inter/show-status-indicator!) cs :share share)
      (t/is (not (working-status? cs)))
      ((var inter/release-background-status!) cs :share share)
      (t/is (nil? @(:status-current cs)))
      (t/is (working-status? cs))))
  (testing "no revive when the turn ended"
    (let [cs (test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      (reset! (:running-turn? cs) false)
      ((var inter/show-status-indicator!) cs :share share)
      ((var inter/release-background-status!) cs :share share)
      (t/is (blank-status? cs))))
  (testing "a newer transient owns the slot — neither cleared nor displaced"
    (let [cs (test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var inter/show-status-indicator!) cs :share share)
      ((var inter/show-status-indicator!) cs :retry
                                          (status-indicator/make-retry-status-indicator 1 3 2000))
      ((var inter/release-background-status!) cs :share share)
      (t/is (= :retry (:kind @(:status-current cs))))))
  (testing "only the indicator the flow installed is released (a second
            /share keeps its spinner)"
    (let [cs (test-status-cs)
          first-share (spinner/make-spinner :text "one" :active true)
          second-share (spinner/make-spinner :text "two" :active true)]
      ((var inter/show-status-indicator!) cs :share first-share)
      ((var inter/show-status-indicator!) cs :share second-share)
      ((var inter/release-background-status!) cs :share first-share)
      (t/is (identical? second-share (:indicator @(:status-current cs))))))
  (testing "an already-revived working spinner is not restarted (its
            animation clock survives)"
    (let [cs (test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)]
      ((var inter/show-status-indicator!) cs :share share)
      ;; a :turn-start revived the working spinner while the background op
      ;; ran — the late release must not touch it
      ((var inter/activate-working-indicator!) cs)
      (let [start @(:start-atom (:spinner (:status-indicator cs)))]
        (Thread/sleep 5)
        ((var inter/release-background-status!) cs :share share)
        (t/is (= start @(:start-atom (:spinner (:status-indicator cs))))))))
  (testing "a turn ending mid-revival must not leave a spinner behind (the
            post-revival re-check rolls it back)"
    (let [cs (test-status-cs)
          share (spinner/make-spinner :text "Creating gist..." :active true)
          real (var-get (var inter/activate-working-indicator!))]
      ((var inter/show-status-indicator!) cs :share share)
      (with-redefs-fn {(var inter/activate-working-indicator!)
                       (fn [c] (reset! (:running-turn? c) false) (real c))}
        (fn [] ((var inter/release-background-status!) cs :share share)))
      (t/is (not (status-indicator/status-indicator-active? (:status-indicator cs)))
            "the revived spinner is stopped again"))))

;; ─── /scoped-models + /settings (missing slash commands) ───────────────────

(defn- install-app-keybindings!
  "Install the app keybindings manager (the interactive mode does this at
   startup; tests drive overlay keys, which match app.models.* ids)."
  []
  (tui-kb/set-global-keybindings!
   (app-kb/create-agent-keybindings-manager "target/test-interactive-ui-kb")))

(deftest test-scoped-models-settings-registered
  (testing "scoped-models and settings are real builtins"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
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
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [hotkeys (commands/find-command "hotkeys")]
      (t/is (some? hotkeys) "hotkeys registered")
      (t/is (= "Show all keyboard shortcuts" (:description hotkeys)))
      (t/is (some? (:handler hotkeys)))
      (let [ed (editor/make-editor)
            ch (chat-history/make-chat-history)]
        ;; wire one app action; app.suspend / app.message.copy stay unwired
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
      (let [wired? ((var inter/make-hotkey-wired?) {:editor ed})]
        (t/is (wired? "tui.editor.cursorUp") "TUI ids are the editor's own")
        (t/is (wired? "app.tools.expand") "installed on the editor")
        (t/is (wired? "app.quit") "the global quit listener owns it")
        (t/is (not (wired? "app.suspend")) "declared, never installed")
        (t/is (not (wired? "app.message.copy")) "tree-selector-only in kmet"))
      (testing "no editor: only the global-listener action survives"
        (let [wired? ((var inter/make-hotkey-wired?) nil)]
          (t/is (wired? "app.quit"))
          (t/is (not (wired? "app.tools.expand"))))))))

;; ─── /import (pi: handleImportCommand) ────────────────────────────────────

(defn- import-test-cs
  "CoreState for the /import tests: real chat/session/agent/footer state so
   the resume path runs for real (pi: importFromJsonl swaps the runtime),
   with only the dock and the render calls stubbed (no terminal in tests).
   The cwd atom is shared by the footer provider and the chat history, as in
   build-layout, and the system-prompt options carry a launch-project
   context file — the switch must keep them (only the cwd line follows the
   session's working directory)."
  [active-sess]
  (let [cwd-atom (atom (or (session/session-cwd active-sess) (str (fs/cwd))))
        ch (chat-history/make-chat-history :cwd-fn #(deref cwd-atom))
        prov (fdp/make-footer-data-provider :cwd-atom cwd-atom :session active-sess)
        ed (editor/make-editor)
        opts {:cwd (deref cwd-atom)
              :context-files [{:path "AGENTS.md" :content "LAUNCH-PROJECT CONTEXT"}]}
        ag (agent/make-agent-state
            :session active-sess
            :system-prompt-opts opts
            :system (apply skills/build-system-prompt (mapcat identity opts)))]
    (inter/map->CoreState
     {:agent-state (atom ag)
      :chat-history ch
      :editor ed
      :current-editor-atom (atom ed)
      :session-atom (atom active-sess)
      :compaction-queued (atom [])
      :running-turn? (atom false)
      :footer-provider prov
      :footer-comp (footer/make-footer :provider prov)})))

(defn- last-message [ch] (select-keys (peek @(:messages-atom ch)) [:role :content]))

(defn- append-message!
  "Persist a user+assistant pair — a session file is written lazily (G4),
   so it needs an assistant message to exist on disk."
  [sess text]
  (session/append-entry sess {:role :user :content [{:type :text :text text}]})
  (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]}))

(deftest test-import-path-argument
  (testing "pi: getPathCommandArgument (shared with /export) — quotes are
            stripped, trailing arguments ignored, an unterminated quote is
            no argument at all"
    (let [parse (var inter/parse-path-argument)]
      (t/is (nil? (parse "")))
      (t/is (nil? (parse "   ")))
      (t/is (= "a.ednl" (parse "a.ednl")))
      (t/is (= "a.ednl" (parse "a.ednl extra ignored")))
      (t/is (= "my file.ednl" (parse "  \"my file.ednl\" extra")))
      (t/is (= "my file.ednl" (parse "'my file.ednl'")))
      (t/is (nil? (parse "\"unterminated"))))))

(deftest test-import-registered-and-path-errors
  (testing "/import is a real builtin; pi's usage and file errors surface as
            Error: lines before any confirmation is mounted"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [cmd (commands/find-command "import")
          dir (str (fs/absolutize (str "target/test-import-errors-" (System/currentTimeMillis))))]
      (t/is (some? cmd) "registered — /import is no longer a placeholder")
      (t/is (= "Import and resume a session from a file" (:description cmd)))
      (t/is (= "<path>" (:argument-hint cmd)))
      (try
        (let [cs (import-test-cs (session/create-session dir))
              ch (:chat-history cs)
              sel-ref (atom nil)]
          (with-redefs [tui/tui-request-render (fn [_])
                        tui/tui-set-focus (fn [_ _])
                        dock/mount! (capture-mount! sel-ref)]
            (testing "no path → pi's usage error"
              ((:handler cmd) cs "")
              (t/is (= {:role :error :content "Usage: /import <path>"}
                       (last-message ch)))
              (t/is (nil? @sel-ref) "nothing is confirmed for a missing argument"))
            (testing "a missing file fails before the confirmation (pi:
                        SessionImportFileNotFoundError)"
              ((:handler cmd) cs (str dir "/ghost.ednl"))
              (t/is (= :error (:role (last-message ch))))
              (t/is (str/includes? (:content (last-message ch))
                                   "Failed to import session: File not found:"))
              (t/is (nil? @sel-ref)))
            (testing "a file that is not a kmet session fails the same way"
              (let [junk (str dir "/junk.ednl")]
                (spit junk "not a session\n")
                ((:handler cmd) cs junk)
                (t/is (str/includes? (:content (last-message ch))
                                     "Failed to import session: Not a kmet session file"))))
            (testing "an in-flight turn refuses — kmet's switch commands do
                        not abort (pi: teardownCurrent does)"
              (reset! (:running-turn? cs) true)
              ((:handler cmd) cs (str dir "/whatever.ednl"))
              (t/is (= "Wait for the current response to finish before importing."
                       (:content (last-message ch)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-import-confirm-flow
  (testing "/import copies the file into the active session's directory and
            resumes it when the Yes/No confirm is accepted"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-import-flow-" (System/currentTimeMillis))))
          foreign (str dir "/foreign-project")]
      (try
        (fs/create-dirs foreign)
        ;; the imported session was recorded in another project — pi's import
        ;; moves the runtime cwd there (createRuntime's cwd)
        (let [src (session/create-session (str dir "/src") {:cwd foreign})]
          (append-message! src "imported question")
          (let [active (session/create-session (str dir "/dest"))
                _ (append-message! active "hello")
                cs (import-test-cs active)
                ch (:chat-history cs)
                cmd (commands/find-command "import")
                sel-ref (atom nil)
                stored (str (fs/path (str dir "/dest") (fs/file-name (:file src))))]
            (with-redefs [tui/tui-request-render (fn [_])
                          tui/tui-set-focus (fn [_ _])
                          dock/mount! (capture-mount! sel-ref)]
              ((:handler cmd) cs (:file src))
              (let [dlg @sel-ref]
                (t/is (some? dlg) "the confirm replaces the editor dock (pi: showSelector)")
                (let [rendered (str/join "\n" (protocols/render dlg 200))]
                  (t/is (str/includes? rendered "Import session"))
                  (t/is (str/includes? rendered "Replace current session with"))
                  (t/is (str/includes? rendered (:file src)) "the path is named")
                  (t/is (str/includes? rendered "Yes"))
                  (t/is (str/includes? rendered "No")))
                ;; enter on the highlighted Yes (pi: the first option)
                (protocols/handle-input dlg "\r"))
              (t/is (fs/exists? stored) "the file is copied into the session dir")
              (t/is (= (slurp (:file src)) (slurp stored)) "verbatim copy")
              (t/is (= (:id src) (:id @(:session-atom cs)))
                    "the imported session is active")
              (t/is (= (:id src) (:id (:session @(:agent-state cs))))
                    "the agent's session swapped too")
              (t/is (= ["imported question"]
                       (mapv :content (filter #(= :user (:role %))
                                              @(:messages-atom ch))))
                    "the imported transcript is replayed")
              (t/is (= {:role :status
                        :content (str "Session imported from: " (:file src))}
                       (last-message ch))
                    "pi: showStatus 'Session imported from: …'")
              (t/is (= (slash foreign) (slash (fdp/fdp-get-cwd (:footer-provider cs))))
                    "the runtime cwd follows the imported session's")
              (t/is (= (slash foreign)
                       (slash (get-in @(:system-prompt-opts @(:agent-state cs)) [:cwd])))
                    "and the system prompt options")
              (t/is (str/includes? (slash @(:system @(:agent-state cs))) (slash foreign))
                    "the model is told where its tools will run")
              (t/is (= [{:path "AGENTS.md" :content "LAUNCH-PROJECT CONTEXT"}]
                       (get-in @(:system-prompt-opts @(:agent-state cs)) [:context-files]))
                    "project context files stay with the launch project")
              (t/is (str/includes? @(:system @(:agent-state cs)) "LAUNCH-PROJECT CONTEXT")
                    "and stay in the rebuilt prompt"))))
        (finally (fs/delete-tree dir))))))

(deftest test-import-cancellation-paths
  (testing "/import leaves the session and the filesystem alone when the
            confirmation is declined, escaped, or cancelled by an extension"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-import-cancel-" (System/currentTimeMillis))))]
      (try
        (let [src (session/create-session (str dir "/src"))]
          (append-message! src "question")
          (let [active (session/create-session (str dir "/dest"))
                _ (append-message! active "hello")
                cs (import-test-cs active)
                ch (:chat-history cs)
                cmd (commands/find-command "import")
                sel-ref (atom nil)
                stored (str (fs/path (str dir "/dest") (fs/file-name (:file src))))]
            (with-redefs [tui/tui-request-render (fn [_])
                          tui/tui-set-focus (fn [_ _])
                          dock/mount! (capture-mount! sel-ref)]
              (testing "escape shows pi's cancelled status and writes nothing"
                (reset! sel-ref nil)
                ((:handler cmd) cs (:file src))
                (protocols/handle-input @sel-ref "\u001b")
                (t/is (= {:role :status :content "Import cancelled"} (last-message ch)))
                (t/is (not (fs/exists? stored)) "no copy")
                (t/is (= (:id active) (:id @(:session-atom cs))) "same session"))
              (testing "selecting No cancels the same way"
                (reset! sel-ref nil)
                ((:handler cmd) cs (:file src))
                (protocols/handle-input @sel-ref "\u001b[B")  ;; down → No
                (protocols/handle-input @sel-ref "\r")
                (t/is (= {:role :status :content "Import cancelled"} (last-message ch)))
                (t/is (not (fs/exists? stored))))
              (testing "an extension cancelling :session-before-switch wins
                          even after Yes — no copy, no switch"
                (reset! sel-ref nil)
                (event-bus/clear-event-listeners!)
                (event-bus/on-event :session-before-switch (fn [_] {:cancel true}))
                ((:handler cmd) cs (:file src))
                (protocols/handle-input @sel-ref "\r")
                (t/is (= {:role :status :content "Import cancelled"} (last-message ch)))
                (t/is (not (fs/exists? stored)) "the copy is still pending")
                (t/is (= (:id active) (:id @(:session-atom cs)))))
              (testing "a source already in the session dir is not re-copied"
                (reset! sel-ref nil)
                (event-bus/clear-event-listeners!)
                ((:handler cmd) cs (:file active))
                (protocols/handle-input @sel-ref "\r")
                (t/is (= 1 (count (fs/list-dir (str dir "/dest"))))
                      "no suffixed duplicate")
                (t/is (= (:id active) (:id @(:session-atom cs))))))))
        (finally (fs/delete-tree dir))))))

(deftest test-import-keeps-the-current-cwd-when-the-recorded-one-is-gone
  (testing "a recorded cwd that no longer exists keeps the current runtime
            cwd — kmet says so and continues (pi: MissingSessionCwdError asks
            for a fallback cwd)"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-import-gone-cwd-" (System/currentTimeMillis))))
          gone (str dir "/gone-project")]
      (try
        (let [src (session/create-session (str dir "/src") {:cwd gone})]
          (append-message! src "from a deleted project")
          (let [active (session/create-session (str dir "/dest"))
                _ (append-message! active "hello")
                cs (import-test-cs active)
                ch (:chat-history cs)
                before (fdp/fdp-get-cwd (:footer-provider cs))
                sel-ref (atom nil)]
            (with-redefs [tui/tui-request-render (fn [_])
                          tui/tui-set-focus (fn [_ _])
                          dock/mount! (capture-mount! sel-ref)]
              ((:handler (commands/find-command "import")) cs (:file src))
              (protocols/handle-input @sel-ref "\r")
              (t/is (= (:id src) (:id @(:session-atom cs))) "the session still switches")
              (t/is (= before (fdp/fdp-get-cwd (:footer-provider cs)))
                    "the current cwd is kept")
              (t/is (some #(and (= :status (:role %))
                                (str/includes? (str (:content %)) "no longer exists"))
                          @(:messages-atom ch))
                    "and the user is told"))))
        (finally (fs/delete-tree dir))))))

(deftest test-export-defaults-to-the-runtime-cwd
  (testing "/export without a path writes into the session's runtime cwd —
            where its tools work (pi resolves its default against the process
            cwd; kmet's runtime cwd follows the session)"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-export-cwd-" (System/currentTimeMillis))))
          project (str dir "/project")]
      (try
        (fs/create-dirs project)
        (let [sess (session/create-session (str dir "/sessions") {:cwd project})]
          (append-message! sess "hello")
          (let [cs (import-test-cs sess)
                ch (:chat-history cs)]
            ((:handler (commands/find-command "export")) cs "")
            (let [msg (last-message ch)]
              (t/is (= :info (:role msg)))
              (t/is (str/includes? (slash (str (:content msg))) (slash project))
                    "the reported path is inside the session's project"))
            (let [files (vec (fs/list-dir project))]
              (t/is (= 1 (count files)) "one export, in the project dir")
              (t/is (str/starts-with? (fs/file-name (first files)) "kmet-session-"))
              (testing "a quoted-empty path is no path: the default is used"
                (with-redefs [tui/tui-request-render (fn [_])
                              tui/tui-set-focus (fn [_ _])]
                  ((:handler (commands/find-command "export")) cs "\"\""))
                (t/is (str/includes? (slash (str (:content (last-message ch)))) (slash project))
                      "not a 'Failed to export session' error")))))
        (finally (fs/delete-tree dir))))))

(deftest test-path-argument-empty-is-none
  (testing "a quoted but empty path argument reads as none (pi:
            getPathCommandArgument) — /export falls back to its default and
            /import to its usage line instead of resolving the process cwd"
    (let [parse (var inter/parse-path-argument)]
      (t/is (nil? (parse "")))
      (t/is (nil? (parse "   ")))
      (t/is (nil? (parse "\"\"")))
      (t/is (nil? (parse "''")))
      (t/is (nil? (parse "\"   \"")))
      (t/is (= "/tmp/x.ednl" (parse "\"/tmp/x.ednl\"")))
      (t/is (= "/tmp/x.ednl" (parse "/tmp/x.ednl extra"))))))

(deftest test-switch-commands-refuse-mid-turn
  (testing "/resume and /tree refuse while a response is streaming, like
            /import, /fork and /clone (pi: teardownCurrent aborts the run and
            switches; kmet waits instead)"
    (commands/clear-commands!)
    (install-app-keybindings!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-mid-turn-guards-"
                                       (System/currentTimeMillis))))]
      (try
        (let [ch (chat-history/make-chat-history)
              cs (inter/map->CoreState
                  {:running-turn? (atom true)
                   :chat-history ch
                   :session-atom (atom (session/create-session dir))
                   :agent-state (atom (agent/make-agent-state))})]
          ((:handler (commands/find-command "resume")) cs "")
          (t/is (= "Wait for the current response to finish before resuming."
                   (:content (last-message ch))))
          ((:handler (commands/find-command "tree")) cs "")
          (t/is (= "Wait for the current response to finish before navigating the tree."
                   (:content (last-message ch)))))
        (finally (fs/delete-tree dir))))))

(deftest test-tree-summarize-prompt-uses-the-editor-dock
  (testing "the /tree summarize prompt and custom-instruction editor mount in
            the editor dock (pi: showExtensionSelector / showExtensionEditor),
            not as floating overlays — the old overlay path rendered over the
            chat when the tree closed and the document shrank"
    (let [dir (str (fs/absolutize (str "target/test-tree-summary-dock-"
                                       (System/currentTimeMillis))))
          sel-ref (atom nil)
          strip-ansi #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "")]
      (try
        (let [sess (session/create-session dir)
              entry (session/append-entry sess
                                          {:role :user
                                           :content [{:type :text :text "q"}]})
              cs {:tui {:render-requested? (atom false)}
                  :session-atom (atom sess)}]
          (with-redefs [dock/mount! (capture-mount! sel-ref)
                        tui/tui-request-render (fn [_])]
            (testing "Summarize branch? selector is framed in the dock with all options"
              ((var inter/ask-branch-summary) cs sess entry)
              (let [text (str/join "\n" (map strip-ansi
                                             (protocols/render @sel-ref 80)))]
                (t/is (str/includes? text "Summarize branch?") "framed title")
                (t/is (str/includes? text "No summary"))
                (t/is (str/includes? text "Summarize with custom prompt")
                      "all three options render (a bare overlay with :height 3 cut the third)")
                (t/is (str/includes? text "─") "border drawn")))
            (testing "custom instructions open a framed input in the dock"
              ((var inter/prompt-custom-summary!) cs sess entry)
              (let [text (str/join "\n" (map strip-ansi
                                             (protocols/render @sel-ref 80)))]
                (t/is (str/includes? text "Custom branch summarization instructions"))
                (t/is (str/includes? text "submit")
                      "the input dialog's keybinding hint renders")))))
        (finally (fs/delete-tree dir))))))

(deftest test-same-cwd-spelled-differently-is-not-a-switch
  (testing "a session whose recorded cwd differs only in spelling (trailing
            slash) leaves the runtime cwd and the prompt alone — the
            comparison is spelling-insensitive"
    (let [dir (str (fs/absolutize (str "target/test-cwd-spelling-" (System/currentTimeMillis))))
          project (str dir "/project")
          spelled (str project "/")]
      (try
        (fs/create-dirs project)
        (let [sess (session/create-session (str dir "/sessions") {:cwd project})
              fdp* (fdp/make-footer-data-provider :cwd-atom (atom spelled))
              cs (inter/map->CoreState {:footer-provider fdp*
                                        :agent-state (atom (agent/make-agent-state))})]
          (t/is (= spelled ((var inter/apply-session-cwd!) cs sess))
                "the cwd in effect is returned unchanged")
          (t/is (= spelled @(:cwd-atom fdp*)) "and kept as spelled"))
        (finally (fs/delete-tree dir))))))

(deftest test-scoped-models-selector-initial-state
  (testing "/scoped-models opens the selector with session scoped models, then
            settings :enabled-models patterns, else nil (all enabled)"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sel-ref (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    cfg/get-enabled-models-live (fn [_] nil)
                    model-catalog/update-available-provider-count! (fn [_] nil)
                    dock/mount! (capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (testing "no session scoped models and no patterns → all enabled"
          ((:handler (commands/find-command "scoped-models")) cs "")
          (t/is (nil? (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref))))
        (testing "session scoped models win"
          (agent/set-scoped-models! ag ["opencode-go/deepseek-v4-flash"])
          ((:handler (commands/find-command "scoped-models")) cs "")
          (t/is (= ["opencode-go/deepseek-v4-flash"]
                   (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref))))
        (testing "settings :enabled-models patterns resolve"
          (agent/set-scoped-models! ag [])
          (with-redefs [cfg/get-enabled-models-live
                        (fn [_] ["opencode-go/deepseek-v4-flash"])]
            ((:handler (commands/find-command "scoped-models")) cs "")
            (t/is (= ["opencode-go/deepseek-v4-flash"]
                     (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref)))))
        (testing "unresolved patterns survive as [unavailable] rows alongside
                  resolved ones (pi: no-match diagnostics appended)"
          (agent/set-scoped-models! ag [])
          (with-redefs [cfg/get-enabled-models-live
                        (fn [_] ["opencode-go/deepseek-v4-flash" "ghost/model"])]
            ((:handler (commands/find-command "scoped-models")) cs "")
            (t/is (= ["opencode-go/deepseek-v4-flash" "ghost/model"]
                     (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref)))))))))

(deftest test-scoped-models-edit-updates-session
  (testing "selector edits write the session scoped list and clear on all-enabled"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sel-ref (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    model-catalog/update-available-provider-count! (fn [_] nil)
                    dock/mount! (capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        ((:handler (commands/find-command "scoped-models")) cs "")
        (let [sel @sel-ref]
          (protocols/handle-input sel "\r")  ;; enter — toggle the first model
          (t/is (seq @(:scoped-models ag))
                "session scoped list updated after an edit")
          ;; Ctrl+A (all enabled) clears the session scoping again
          (protocols/handle-input sel "\u0001")
          (t/is (= [] @(:scoped-models ag))
                "all-enabled clears the session scoped list (pi updateSessionModels)"))))))

(deftest test-settings-thinking-row
  (testing "/settings opens a settings list whose thinking row changes the
            session level and persists to settings"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                     :thinking :off)
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    model-selector/sync-footer-model! (fn [_] nil)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (capture-mount! sl-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        ((:handler (commands/find-command "settings")) cs "")
        (let [sl @sl-ref]
          (t/is (some? sl) "settings list shown")
          ;; select the thinking row by id (row order is data, not stable)
          (settings-list/settings-list-select-item! sl :thinking)
          ;; Enter (pi: activateItem) cycles the selected row
          (protocols/handle-input sl "\r")
          (t/is (not= :off @(:thinking ag)) "thinking row cycles the session level")
          (t/is (= [[:thinking] @(:thinking ag)] @saved)
                "thinking change persisted to settings (path + level)"))))))

(deftest test-settings-block-images-row
  (testing "/settings Block images row flips the agent knob and persists
            (pi: block-images, ungated — unlike the terminal image rows)"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (capture-mount! sl-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (t/is (false? (:block-images @(:cfg ag))) "default off at startup")
        ((:handler (commands/find-command "settings")) cs "")
        (let [sl @sl-ref]
          ;; select the block-images row by id (the terminal image rows are
          ;; stubbed away via no-image-caps; the skill-commands row follows)
          (settings-list/settings-list-select-item! sl :block-images)
          (protocols/handle-input sl "\r")  ;; enter — false -> true
          (t/is (true? (:block-images @(:cfg ag))) "row toggles the agent knob")
          (t/is (= [[:images :block-images] true] @saved) "blocked persisted")
          (protocols/handle-input sl "\r")  ;; enter — true -> false
          (t/is (false? (:block-images @(:cfg ag))) "cycling back unblocks")
          (t/is (= [[:images :block-images] false] @saved) "unblocked persisted"))))))

(deftest test-settings-retry-rows
  (testing "/settings retry rows apply live to the agent and persist"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/get-retry-settings-live
                    (fn [_] {:enabled true :max-retries 3 :base-delay-ms 2000})
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (capture-mount! sl-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (t/is (= 3 (:max-retries @(:cfg ag))) "default retry wired at startup")
        ((:handler (commands/find-command "settings")) cs "")
        (let [sl @sl-ref]
          ;; select rows by id (row order is data, not stable): retry block
          (settings-list/settings-list-select-item! sl :auto-retry)
          (protocols/handle-input sl "\r") ;; enter — auto-retry true -> false
          (t/is (= 0 (:max-retries @(:cfg ag))) "disabled retry gates max-retries to 0")
          (t/is (= [[:retry :enabled] false] @saved) "auto-retry persisted")
          (protocols/handle-input sl "\r") ;; enter — auto-retry back on
          (t/is (= 3 (:max-retries @(:cfg ag))) "re-enabled retry restores max-retries")
          (settings-list/settings-list-select-item! sl :max-retries)
          (protocols/handle-input sl "\r") ;; enter — 3 -> 5
          (t/is (= 5 (:max-retries @(:cfg ag))) "max-retries applies live")
          (t/is (= [[:retry :max-retries] 5] @saved) "max-retries persisted")
          (settings-list/settings-list-select-item! sl :base-delay-ms)
          (protocols/handle-input sl "\r") ;; enter — 2000 -> 4000
          (t/is (= 4000 (:base-delay-ms @(:cfg ag))) "base delay applies live")
          (t/is (= [[:retry :base-delay-ms] 4000] @saved) "base delay persisted"))))))

(deftest test-set-terminal-progress-clears-when-disabled
  (testing "a turn end clears even while the setting is off; activation
            stays gated (pi gates both ends, leaving its keepalive asserting
            after a mid-turn disable)"
    (let [missing (str (fs/absolutize (fs/file "target" (str "test-progress-missing-"
                                                             (System/currentTimeMillis))))
                       "/settings.edn")
          calls (atom [])
          term (reify terminal/ITerminal
                 (start! [_ _ _] nil)
                 (stop! [_] nil)
                 (started? [_] true)
                 (write-output [_ _] nil)
                 (read-input [_ _] -1)
                 (columns [_] 80)
                 (rows [_] 24)
                 (set-progress! [_ active] (swap! calls conj active)))
          cs {:config {:show-terminal-progress false}
              :tui {:terminal (atom term)}}]
      (with-redefs [cfg/global-settings-path (fn [] missing)]
        ((var inter/set-terminal-progress!) cs false)
        (t/is (= [false] @calls) "the clear passes while disabled")
        ((var inter/set-terminal-progress!) cs true)
        (t/is (= [false] @calls) "activation is still settings-gated")))))

(deftest test-settings-http-transport-row
  (testing "/settings HTTP transport row switches the runtime transport
            (platform → curl → platform) and persists"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (capture-mount! sl-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (try
          (http/set-transport! :platform)
          ((:handler (commands/find-command "settings")) cs "")
          (let [sl @sl-ref]
            ;; select the transport row by id (row order is data, not stable)
            (settings-list/settings-list-select-item! sl :http-transport)
            (protocols/handle-input sl "\r") ;; platform -> curl
            (t/is (= :curl (http/get-transport))
                  "row switches the runtime transport")
            (t/is (= [[:http-transport] :curl] @saved)
                  "transport persisted to settings")
            (protocols/handle-input sl "\r") ;; curl -> platform
            (t/is (= :platform (http/get-transport))
                  "cycling back to platform"))
          (finally (http/set-transport! :platform)))))))

;; ─── /theme command ───────────────────────────────────────────────────────

(deftest test-theme-command
  (testing "/theme takes the whole argument string (a string is a seq of
            chars — (first args) would yield the first character)"
    (install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [saved (atom nil)
          msg (atom nil)
          tc-ctrl (atom nil)]
      (with-redefs [tui/tui-on-terminal-color-scheme-change (fn [_ _] nil)
                    tui/tui-invalidate (fn [_] nil)
                    ;; the controller is built over a stub UI here (this test
                    ;; covers the /theme argument handling); stub the forced
                    ;; render notify-changed! now issues too
                    tui/tui-request-render (fn [& _] nil)
                    chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))]
        (reset! tc-ctrl (theme-ctrl/make-theme-controller {:theme "dark"} nil nil (fn [])))
        (let [cs {:config cfg/default-config
                  :chat-history nil
                  :theme-controller @tc-ctrl}]
          (testing "with a full theme name"
            ((:handler (commands/find-command "theme")) cs "light")
            (t/is (= "light" (get-in @saved [1])) "theme persisted as the full name")
            (t/is (= "light" (theme-ctrl/get-active-theme-name @tc-ctrl))
                  "theme switched to the full name")
            ;; restore: components subscribe to the shared theme atom
            ;; (Stage 5) — a leaked light theme re-themes later tests
            ((:handler (commands/find-command "theme")) cs "dark")
            (t/is (= "dark" (:name (theme/get-current-theme))))))))))

(deftest test-update-editor-border-color-uses-active-theme
  (testing "the editor's dynamic border follows the ACTIVE theme, not the
            config :theme snapshot — a /theme switch re-styles it"
    (let [bf (atom nil)
          cs {:editor {:border-fn bf}}
          rule (fn [theme-name]
                 ((theme/get-thinking-border-color (theme/get-theme theme-name) :max) "─"))]
      (reset! theme/theme-atom (theme/get-theme "dark"))
      ((var state/update-editor-border-color!) cs :max)
      (t/is (= (rule "dark") ((deref bf) "─")))
      (reset! theme/theme-atom (theme/get-theme "light"))
      (try
        ((var state/update-editor-border-color!) cs :max)
        (t/is (= (rule "light") ((deref bf) "─"))
              "after a theme switch the border uses the new theme's color, not
               the config snapshot it was constructed from")
        (t/is (not= (rule "dark") ((deref bf) "─")))
        (finally
          (reset! theme/theme-atom (theme/get-theme "dark")))))))

(deftest test-build-context-capability
  (testing "the interactive ui registry's :build-context captures live state"
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :config cfg/default-config
              :session-atom (atom nil)}
          registry ((var inter/build-extension-ui-registry)
                    {:tui nil :cs cs}
                    {:fdp (fdp/make-footer-data-provider)}
                    nil)
          ctx ((:build-context registry))]
      (t/is (= :interactive (:mode ctx)))
      (t/is (true? (:has-ui ctx)))
      (t/is (string? (:cwd ctx)))
      (t/is (= "deepseek-v4-flash" (:model ctx)))
      (t/is (= [] (:scoped-models ctx)))
      (t/is (true? ((:is-idle ctx))))
      (t/is (false? ((:has-pending-messages ctx))))
      (t/is (false? ((:signal ctx))))
      (t/is (string? ((:get-system-prompt ctx))))
      (t/is (nil? ((:wait-for-idle ctx))) "already idle → nil, not a promise")
      (t/is (nil? ((:get-context-usage ctx))) "no active session → nil (pi parity)")
      (t/is (= {:cancelled true} ((:fork ctx) nil)))
      (t/is (= {:cancelled true} ((:navigate-tree ctx) "missing-leaf")))
      (t/is (= {:cancelled true} ((:switch-session ctx) "/nonexistent-file.edn")))
      (t/is (false? ((:is-project-trusted ctx))))
      (testing ":navigate-tree opts reach the extension context. The
                full navigate-tree flow needs an editor + LLM; here we
                only test the wrapper's short-circuit: a missing target
                id returns :cancelled without emitting
                :session-before-tree (the prep-event contract is
                exercised by the manual /review run, not here)."
        (let [seen (atom nil)
              dereg (event-bus/on-event :session-before-tree
                                        (fn [ev] (reset! seen ev)))
              result ((:navigate-tree ctx) "missing-target"
                                           {:summarize false
                                            :custom-instructions "ci"
                                            :replace-instructions true
                                            :label "my-label"})]
          (t/is (= {:cancelled true} result)
                "navigate-tree with a missing target id returns :cancelled")
          (t/is (nil? @seen)
                "no :session-before-tree event was emitted for a missing target")
          (dereg)))
      (t/is (not (contains? ctx :cs)) "CoreState never leaks into the ctx")
      (testing "fns stay live across an agent swap (session swaps assoc a
                new record; only the :session field goes stale)"
        (let [sess (session/create-session (str (fs/cwd) "/target"))]
          (reset! (:agent-state cs) (assoc ag :session sess))
          (t/is (true? ((:is-idle ctx))))
          (t/is (nil? ((:wait-for-idle ctx))))))
      (testing "get-context-usage reports the active session (pi parity)"
        (let [fdp-provider (fdp/make-footer-data-provider)
              _ (fdp/fdp-set-session! fdp-provider
                                      (session/create-session
                                       (str (fs/cwd) "/target")))
              registry ((var inter/build-extension-ui-registry)
                        {:tui nil :cs cs}
                        {:fdp fdp-provider}
                        nil)
              usage ((:get-context-usage ((:build-context registry))))]
          (t/is (contains? usage :tokens))
          (t/is (contains? usage :context-window))
          (t/is (contains? usage :percent)))))
      ;; the registry install is a side effect — don't leak the fake cs
      ;; registry into later tests (build-extension-context would merge it)
    (extensions/clear-ui-registry!)))

;; ─── DSL stage 4 review: dock generation gate + widget-area reactivity ────

(deftest test-dock-generation-gate
  (testing "a stale done() from a replaced selector must not yank the newer
            one out of the dock (pi: activeSelectorToken); done() is
            idempotent and restores the CURRENT active editor"
    (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                  tui/tui-request-render (fn [_] nil)]
      (let [ed (editor/make-editor)
            cs {:tui {}
                :dock-current (atom nil)
                :current-editor-atom (atom ed)}
            panel-a (status-indicator/make-status-indicator)
            panel-b (editor/make-editor)
            ;; A mounts, then B replaces it
            done-a (dock/mount! cs panel-a)]
        (dock/mount! cs panel-b)
        (t/is (= panel-b (:component @(:dock-current cs))) "B recorded")
        (done-a)
        (t/is (= panel-b (:component @(:dock-current cs)))
              "stale done() is inert")
          ;; B's done restores the editor; a second call is harmless
        (let [done-b (dock/mount! cs panel-b)]
          (done-b)
          (t/is (nil? @(:dock-current cs)) "editor restored")
          (done-b)
          (t/is (nil? @(:dock-current cs)) "double done() stays nil"))))))

(defn- strip-ansi-lines [lines]
  (mapv #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "") lines))

(deftest test-widget-area-tracked-reactivity
  (testing "the widget strips re-derive from pure map swaps: registered
            widgets appear, removals disappear, the below strip renders
            nothing while empty, dispose unwinds cleanly"
    (let [above (atom {})
          below (atom {})
          mk (fn [label] ((var inter/make-extension-widget-component) nil
                                                                      [:text {:padding-x 1 :padding-y 0} label]))
          above-root (hiccup/root ((var inter/make-widget-area-above) above))
          below-root (hiccup/root ((var inter/make-widget-area-below) below))]
      (try
        (swap! above assoc :w1 (mk "widget one"))
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (some #(re-find #"widget one" %) lines) "widget rendered"))
        ;; a pure map swap re-derives on next render (tracked read)
        (swap! above assoc :w2 (mk "widget two"))
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (some #(re-find #"widget two" %) lines) "second widget shown")
          (t/is (some #(re-find #"widget one" %) lines) "first still shown"))
        ;; below strip renders nothing while empty, widgets once added
        (t/is (empty? (protocols/render below-root 40)) "empty below strip")
        (reset! below {:wb (mk "below widget")})
        (let [lines (strip-ansi-lines (protocols/render below-root 40))]
          (t/is (some #(re-find #"below widget" %) lines) "below widget shown"))
        ;; removal disappears on next render
        (swap! above dissoc :w1)
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (not-any? #(re-find #"widget one" %) lines) "removed widget gone"))
        (finally
          (protocols/dispose above-root)
          (protocols/dispose below-root))))))

(deftest test-widget-tree-content-and-dispose
  (testing "hiccup tree content compiles to a renderable wrapper whose
            :dispose unwinds owned cleanups (the set-widget replace/remove
            hook's contract)"
    (let [cleanups (atom 0)
          cleanup-fn (fn [_props]
                       (macros/with-let [_ (swap! cleanups inc)]
                         [:text {:padding-x 0 :padding-y 0} "owned"]
                         (finally (swap! cleanups dec))))
          w ((var inter/make-extension-widget-component)
             nil [:container {}
                  [:text {:padding-x 1 :padding-y 0} "tree widget"]
                  [cleanup-fn {}]])]
      ;; vector content compiles to a real stamped component — spliceable
      (t/is (satisfies? protocols/IComponent w))
      (let [lines (strip-ansi-lines (protocols/render w 40))]
        (t/is (some #(re-find #"tree widget" %) lines)))
      (t/is (= 1 @cleanups) "render initialized the owned subtree")
      (protocols/dispose w)
      (t/is (= 0 @cleanups) "component dispose unwound the owned subtree"))))

(deftest test-custom-component-tree-content
  (testing "normalize-custom-component accepts a hiccup element tree for
            ui-custom dialogs — compiles to a renderable IComponent whose
            dispose unwinds owned cleanups"
    (let [cleanups (atom 0)
          cleanup-fn (fn [_props]
                       (macros/with-let [_ (swap! cleanups inc)]
                         [:text {:padding-x 0 :padding-y 0} "dialog body"]
                         (finally (swap! cleanups dec))))
          comp (var-get #'inter/normalize-custom-component)
          c (comp [:container {}
                   [:text {:padding-x 1 :padding-y 0} "MCP OAuth"]
                   [cleanup-fn {}]])]
      ;; NOTE: satisfies? is unreliable for reify under SCI — assert via
      ;; protocol dispatch (render/dispose), which is what the host uses
      (let [lines (strip-ansi-lines (protocols/render c 40))]
        (t/is (some #(re-find #"MCP OAuth" %) lines))
        (t/is (some #(re-find #"dialog body" %) lines)))
      (t/is (= 1 @cleanups))
      ;; the host's close/replace/shutdown sites all funnel through
      ;; dispose-dialog-component! — prove THAT path unwinds tree dialogs
      ((var-get #'inter/dispose-dialog-component!) c)
      (t/is (= 0 @cleanups) "dispose unwinds on dialog close"))))

(deftest test-dispose-dialog-component-shapes
  (testing "dispose-dialog-component! (kmet.app.ui.custom-dialog-adapter/
            dispose-component!) handles every component shape: duck-typed
            maps via their :dispose key, records via the protocol, nil
            no-op — and a throwing foreign dispose never propagates"
    (let [dispose! (var-get #'inter/dispose-dialog-component!)
          called (atom 0)]
      (dispose! {:render (fn [_] ["duck"]) :dispose (fn [] (swap! called inc))})
      (t/is (= 1 @called) "duck-typed :dispose invoked")
      (dispose! {:render (fn [_] ["duck"]) :dispose (fn [] (throw (ex-info "boom" {})))})
      (t/is true "a throwing foreign dispose is swallowed")
      (dispose! {:render (fn [_] ["no dispose key"])})
      (t/is true "a map without :dispose does not propagate the dispatch error")
      (dispose! nil)
      (t/is true "nil is a no-op")
      (let [c (hiccup/compile-tree [:container {} [:text {:padding-x 0 :padding-y 0} "x"]])]
        (dispose! c)
        (t/is true "records dispatch through the protocol")))))

(deftest test-widget-string-vector-no-longer-lines
  (testing "breaking change: string vectors are NOT line lists anymore —
            they fail tree compilation loudly instead of silently rendering
            text lines"
    (t/is (thrown? Exception
                   ((var inter/make-extension-widget-component) nil ["just" "lines"])))))

;; ─── Compaction queue (pi: queueCompactionMessage / flushCompactionQueue) ──

(defn- compaction-cs
  "A CoreState-like map for compaction-queue tests."
  []
  (let [ag (agent/make-agent-state)
        ch (chat-history/make-chat-history)
        ed (editor/make-editor)
        si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ;; a plain editor (no top-border fn) cannot embed the status, so
        ;; the standalone layer renders for these tests
        ced (atom ed)]
    {:tui {:render-requested? (atom false)}
     :agent-state (atom ag)
     :chat-history ch
     :editor ed
     :current-editor-atom ced
     :compaction-queued (atom [])
     :running-turn? (atom false)
     :bash-running? (atom false)
     :bash-signal (atom false)
     :status-indicator si
     :status-current cur
     :status-root (hiccup/root (status-indicator/make-status-area cur si ced))
     :footer-comp nil
     :footer-provider nil
     :pending-messages-comp (pending-messages/make-pending-messages)
     :session-atom (atom (session/create-session
                          (str "target/test-compaction-queue-" (System/currentTimeMillis))))}))

(deftest test-submit-queues-during-compaction
  (testing "a plain message submitted during compaction queues as steer
            (pi: onSubmit → isCompacting → queueCompactionMessage(text, steer))"
    (let [cs (compaction-cs)]
      (reset! (:compacting? @(:agent-state cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var inter/handle-submit) cs "hello during compaction"))
      (t/is (= [{:text "hello during compaction" :mode :steer}]
               @(:compaction-queued cs))
            "message queued with steer mode")
      (t/is (empty? @(:messages @(:agent-state cs)))
            "message NOT sent to the agent"))))

(deftest test-follow-up-queues-during-compaction
  (testing "Alt+Enter during compaction queues as follow-up
            (pi: handleFollowUp → queueCompactionMessage(text, followUp))"
    (let [cs (compaction-cs)
          ed @(:current-editor-atom cs)]
      (editor/editor-set-text! ed "later message")
      (reset! (:compacting? @(:agent-state cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var inter/handle-follow-up) cs))
      (t/is (= [{:text "later message" :mode :follow-up}]
               @(:compaction-queued cs))
            "message queued with follow-up mode"))))

(deftest test-flush-compaction-queue-prompts-when-idle
  (testing "compaction_end flushes the queue: the first non-extension message
            starts a run when idle, the rest queue (pi: flushCompactionQueue)"
    (let [cs (compaction-cs)
          started (atom [])]
      (reset! (:compaction-queued cs)
              [{:text "first" :mode :steer}
               {:text "second" :mode :follow-up}])
      (with-redefs [agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    inter/activate-working-indicator! (fn [_] nil)
                    inter/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)]
        ((var inter/flush-compaction-queue!) cs false))
      (t/is (seq @started) "a run started for the first message")
      (t/is (= "first" (get-in @started [1 :message])) "run carries the first message")
      (t/is (empty? @(:compaction-queued cs)) "queue drained")
      (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))]
        (t/is (= [] steering) "second (follow-up) not steered")
        (t/is (= ["second"] follow-up) "second queued as follow-up into the run")))))

(deftest test-flush-compaction-queue-queues-when-running
  (testing "compaction_end during a running turn (overflow retry) queues every
            message into the turn (pi: flushCompactionQueue willRetry)"
    (let [cs (compaction-cs)]
      (reset! (:running-turn? cs) true)
      (reset! (:compaction-queued cs)
              [{:text "steer-msg" :mode :steer}
               {:text "follow-msg" :mode :follow-up}])
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var inter/flush-compaction-queue!) cs true))
      (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))]
        (t/is (= ["steer-msg"] steering) "steer-mode queued into steering")
        (t/is (= ["follow-msg"] follow-up) "follow-up-mode queued into follow-up"))
      (t/is (empty? @(:compaction-queued cs)) "queue drained"))))

(deftest test-extension-command-during-compaction-executes
  (testing "extension commands execute immediately during compaction
            (pi: isExtensionCommand → prompt() executes)"
    (commands/clear-commands!)
    (let [cs (compaction-cs)
          ran (atom nil)]
      (commands/register-command!
       {:name "my-ext-cmd"
        :description "test"
        :extension-handler (fn [_ctx args] (reset! ran args))})
      (reset! (:compacting? @(:agent-state cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    state/update-footer! (fn [_] nil)]
        ((var inter/handle-submit) cs "/my-ext-cmd arg1"))
      (t/is (= "arg1" @ran) "extension command executed immediately")
      (t/is (empty? @(:compaction-queued cs)) "not queued")
      (commands/clear-commands!))))

(deftest test-restore-queued-includes-compaction-queue
  (testing "Alt+Up / cancel restores the compaction queue alongside the
            session queues (pi: restoreQueuedMessagesToEditor → clearAllQueues)"
    (let [cs (compaction-cs)
          ed @(:current-editor-atom cs)]
      (agent/steer! @(:agent-state cs) "steer-msg")
      (reset! (:compaction-queued cs) [{:text "compact-msg" :mode :steer}])
      (editor/editor-set-text! ed "draft")
      (let [n ((var inter/restore-queued-messages!) cs)]
        (t/is (= 2 n) "both queues restored")
        (t/is (= "steer-msg\n\ncompact-msg\n\ndraft" (editor/editor-get-text ed))
              "messages combined with current editor text")
        (t/is (empty? @(:compaction-queued cs)) "compaction queue cleared")))))

(deftest test-compaction-end-handler-flushes
  (testing "the :compaction-end event handler flushes the queue"
    (let [cs (compaction-cs)
          h ((var inter/make-agent-event-handler)
             {:chat-history (:chat-history cs)
              :tui {:render-requested? (atom false)}
              :cs-ref (atom cs)
              :pending-tool-comps (atom {})})
          started (atom [])]
      (reset! (:compaction-queued cs) [{:text "after" :mode :steer}])
      (with-redefs [agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    inter/activate-working-indicator! (fn [_] nil)
                    inter/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)]
        (h {:type :compaction-end :reason :threshold :result true :will-retry false}))
      (t/is (seq @started) "queued message prompted a run after compaction"))))

(deftest test-cancel-during-compaction-keeps-turn
  (testing "escape during compaction aborts ONLY the compaction — a running
            turn is not cancelled (pi: compaction_start swaps the escape
            handler to abortCompaction; abortCompaction touches only the
            compaction controllers, never the agent run)"
    (let [cs (compaction-cs)]
      (reset! (:compacting? @(:agent-state cs)) true)
      (reset! (:signal @(:agent-state cs)) false)
      (reset! (:running-turn? cs) true)
      (with-redefs [inter/stop-anim-timer! (fn [_] nil)
                    inter/clear-status-indicator! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    agent/cancel-turn (fn [_] (throw (ex-info "must not cancel the turn" {})))]
        ((var inter/handle-cancel) cs))
      (t/is (true? @(:signal @(:agent-state cs))) "compaction aborted via signal")
      (t/is (true? @(:running-turn? cs)) "the turn is NOT cancelled"))))

(deftest test-cancel-idle-compaction-keeps-turn
  (testing "escape during compaction when no turn is running"
    (let [cs (compaction-cs)]
      (reset! (:compacting? @(:agent-state cs)) true)
      (reset! (:signal @(:agent-state cs)) false)
      (reset! (:running-turn? cs) false)
      (with-redefs [state/update-footer! (fn [_] nil)]
        ((var inter/handle-cancel) cs))
      (t/is (true? @(:signal @(:agent-state cs))) "compaction aborted"))))

(deftest test-follow-up-extension-command-executes-during-compaction
  (testing "Alt+Enter with an extension command during compaction executes
            immediately (pi: handleFollowUp → isExtensionCommand → prompt)"
    (commands/clear-commands!)
    (let [cs (compaction-cs)
          ed @(:current-editor-atom cs)
          ran (atom nil)]
      (commands/register-command!
       {:name "my-fu-cmd"
        :description "test"
        :extension-handler (fn [_ctx args] (reset! ran args))})
      (editor/editor-set-text! ed "/my-fu-cmd arg")
      (reset! (:compacting? @(:agent-state cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var inter/handle-follow-up) cs))
      (t/is (= "arg" @ran) "extension command executed immediately")
      (t/is (empty? @(:compaction-queued cs)) "not queued")
      (commands/clear-commands!))))

;; ─── /followup command (pi: no equivalent — slash form of Alt+Enter) ──────

(deftest test-followup-command-registered
  (testing "/followup is a real builtin inside register-builtin-commands!"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
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
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [cs (compaction-cs)]
      (reset! (:running-turn? cs) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)]
        ((:handler (commands/find-command "followup")) cs "do the thing"))
      (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))]
        (t/is (= [] steering) "not steered into the running turn")
        (t/is (= ["do the thing"] follow-up) "args queued as follow-up"))
      (t/is (empty? @(:messages @(:agent-state cs)))
            "message NOT sent to the agent context yet"))))

(deftest test-followup-command-submits-when-idle
  (testing "/followup <text> while idle starts a run with the args as the
            message — Alt+Enter's idle path (pi: handleFollowUp → submit)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [cs (compaction-cs)
          started (atom [])]
      (with-redefs [chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)
                    agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    inter/activate-working-indicator! (fn [_] nil)
                    inter/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)]
        ((:handler (commands/find-command "followup")) cs "wrap it up"))
      (t/is (seq @started) "run-agent-turn called")
      (t/is (= "wrap it up" (get-in @started [1 :message]))
            "run carries the args as the message")
      (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))]
        (t/is (empty? steering) "nothing queued")
        (t/is (empty? follow-up) "nothing queued")))))

(deftest test-followup-command-queues-during-compaction
  (testing "/followup <text> during compaction queues as follow-up (pi:
            handleFollowUp → queueCompactionMessage(text, followUp))"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [cs (compaction-cs)]
      (reset! (:compacting? @(:agent-state cs)) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)]
        ((:handler (commands/find-command "followup")) cs "later msg"))
      (t/is (= [{:text "later msg" :mode :follow-up}]
               @(:compaction-queued cs))
            "queued with follow-up mode"))))

(deftest test-followup-command-blank-args-usage
  (testing "/followup with no args shows usage instead of queueing anything"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [cs (compaction-cs)
          msg (atom nil)]
      (reset! (:running-turn? cs) true)
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "followup")) cs ""))
      (t/is (= "Usage: /followup <message>" (:content @msg))
            "usage info shown")
      (t/is (empty? (:follow-up (agent/queued-messages @(:agent-state cs))))
            "nothing queued for blank args"))))

(deftest test-cancel-two-step-during-midrun-compaction
  (testing "escape during a mid-run compaction aborts the compaction first;
            a second escape then cancels the turn (pi: compaction_start
            swaps the escape handler to abortCompaction, restored at
            compaction_end — two-step escape)"
    (let [cs (compaction-cs)
          cancelled (atom 0)]
      (reset! (:compacting? @(:agent-state cs)) true)
      (reset! (:signal @(:agent-state cs)) false)
      (reset! (:running-turn? cs) true)
      (with-redefs [inter/stop-anim-timer! (fn [_] nil)
                    inter/clear-status-indicator! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    chat-history/chat-history-show-status! (fn [_ _] nil)
                    chat-history/chat-history-finalize-streaming! (fn [_] nil)
                    chat-history/chat-history-finalize-thinking! (fn [_] nil)
                    chat-history/chat-history-remove-streaming-placeholder! (fn [_] nil)
                    agent/cancel-turn (fn [_] (swap! cancelled inc))]
        ;; first escape: compaction aborted only
        ((var inter/handle-cancel) cs)
        (t/is (true? @(:signal @(:agent-state cs))) "compaction aborted")
        (t/is (true? @(:running-turn? cs)) "turn still running")
        (t/is (zero? @cancelled) "turn not cancelled")
        ;; compaction-end resets the flag; second escape cancels the turn
        (reset! (:compacting? @(:agent-state cs)) false)
        ((var inter/handle-cancel) cs)
        (t/is (= 1 @cancelled) "second escape cancels the turn")))))

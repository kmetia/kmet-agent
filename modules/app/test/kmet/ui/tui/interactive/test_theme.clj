(ns kmet.ui.tui.interactive.test-theme
  "The /theme command: the theme registry, the dock's generated theme pane, reload
   fixtures, and cleanup of contributed widgets."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.app.events :as events]
            [kmet.test-utils :as tu]
            [kmet.app.debug :as debug]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.text :as text]
            [kmet.hikap.component :as component]
            [kmet.libs.theme :as theme]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.core :as tui]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.ui-registry :as ui-registry]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.app.commands :as commands]
            [kmet.app.extensions.core :as extensions]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ai.models :as m]
            [kmet.app.loop :as agent]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.ui.tui.interactive.test-handle-support :as hs]
            [kmet.ui.tui.interactive.test-support :as support]))

;; The interactive builtins register the app's whole slash registry; clear it
;; so a later namespace never sees the tree filled by this one.
(t/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

;; ─── /theme command ───────────────────────────────────────────────────────

(deftest test-theme-command
  (testing "/theme takes the whole argument string (a string is a seq of
            chars — (first args) would yield the first character)"
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs! nil)
    ((var builtins/register-builtin-commands!) cfg/default-config)
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
        (let [cs (assoc (hs/core-state (hs/new-handle) {:config cfg/default-config})
                        :theme-controller @tc-ctrl)]
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
          cs (hs/core-state (hs/new-handle) {:editor {:border-fn bf}})
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
    (let [model (m/map->Model {:provider :test-provider :id "test-model"
                               :context-window 32768})]
      (with-redefs [m/providers-atom (atom {:test-provider {:models [model]}})]
        (try
          (let [handle (hs/new-handle)
                ag (runtime/agent-state handle)
                cs (hs/core-state handle {:config cfg/default-config
                                          :provider :test-provider
                                          :model "test-model"})
                registry ((var ui-registry/build-extension-ui-registry)
                          {:tui nil :cs cs}
                          {:fdp (fdp/make-footer-data-provider)}
                          nil)
                ctx ((:build-context registry))]
            (t/is (= :interactive (:mode ctx)))
            (t/is (true? (:has-ui ctx)))
            (t/is (string? (:cwd ctx)))
            (t/is (identical? model (:model ctx)))
            (t/is (= :test-provider (:provider (:model ctx))))
            (t/is (identical? model ((:find (:models (extensions/build-extension-context)))
                                     :test-provider "test-model"))
                  "ctx :models reads the live registry (pi ctx.modelRegistry)")
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
                    dereg (events/on! (hs/events-ctx handle) :session-before-tree
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
                (reset! (:agent-state (:engine cs)) (assoc ag :session sess))
                (t/is (true? ((:is-idle ctx))))
                (t/is (nil? ((:wait-for-idle ctx))))))
            (testing "get-context-usage reports the active session (pi parity)"
              (let [fdp-provider (fdp/make-footer-data-provider)
                    _ (fdp/fdp-set-session! fdp-provider
                                            (session/create-session
                                             (str (fs/cwd) "/target")))
                    registry ((var ui-registry/build-extension-ui-registry)
                              {:tui nil :cs cs}
                              {:fdp fdp-provider}
                              nil)
                    usage ((:get-context-usage ((:build-context registry))))]
                (t/is (contains? usage :tokens))
                (t/is (contains? usage :context-window))
                (t/is (contains? usage :percent)))))
          ;; Registry installation is a side effect: clean up even if a
          ;; capability throws, so later contexts cannot see the fake cs.
          (finally (tu/clear-extension-wiring!)))))))

(deftest test-build-context-idle-follows-the-run-and-compaction-flags
  (testing "the extension ctx's idle state, wait-for-idle and abort follow the
            run and compaction flags, not the status atom — the settle window
            (e.g. D's post-run compaction) has status idle while the prompt is
            still in flight (pi: isIdle / isStreaming / abort)"
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :turn-running? false})
          registry ((var ui-registry/build-extension-ui-registry)
                    {:tui nil :cs cs}
                    {:fdp (fdp/make-footer-data-provider)}
                    nil)]
      (try
        (let [ctx ((:build-context registry))]
          (t/is (= :idle @(:status ag)) "precondition: the status atom reads idle")
          (t/is (true? ((:is-idle ctx))))
          ;; The settle window: a prompt is in flight with the status idle
          (reset! (:turn-running? (:engine cs)) true)
          (t/is (false? ((:is-idle ctx))) "the run flag beats the status atom")
          (t/is (some? ((:wait-for-idle ctx))) "running → a wait promise")
          ((:abort ctx))
          (t/is (true? @(:signal ag)) "abort cancels the run even with the status idle")
          (reset! (:turn-running? (:engine cs)) false)
          (reset! (:signal ag) false)
          (t/is (true? ((:is-idle ctx))) "idle again once the run flag drops")
          ;; A compaction alone is not idle, and abort aborts it too
          (reset! (:compaction-signal ag) false)
          (reset! (:compacting? ag) true)
          (t/is (false? ((:is-idle ctx))) "a compaction is not idle (pi: isIdle)")
          (t/is (some? ((:wait-for-idle ctx))) "a compaction → a wait promise")
          ((:abort ctx))
          (t/is (true? @(:compaction-signal ag)) "abort aborts the compaction")
          (reset! (:compacting? ag) false)
          ;; fully idle → abort is a no-op, not a stale run cancel
          (reset! (:signal ag) false)
          ((:abort ctx))
          (t/is (false? @(:signal ag)) "idle → abort does nothing"))
        (finally
          (reset! (:turn-running? (:engine cs)) false)
          (tu/clear-extension-wiring!))))))

(deftest test-build-context-resolves-current-model
  (let [native (m/map->Model {:provider :test-native :id "test-model"
                              :base-url "https://native.test/v1"})
        adapter (m/map->Model {:provider :test-adapter :id "test-model"
                               :base-url "https://adapter.test/v1"
                               :headers {"X-Test" "yes"}})
        alternate (assoc adapter :id "other")
        handle (hs/new-handle)
        ag (runtime/agent-state handle)
        cs (hs/core-state handle {:config cfg/default-config
                                  :provider :test-native
                                  :model "test-model"})]
    (with-redefs [m/providers-atom (atom {:test-native {:models [native]}
                                          :test-adapter {:models [adapter alternate]}})]
      (try
        (ui-registry/build-extension-ui-registry
         {:tui nil :cs cs} {:fdp (fdp/make-footer-data-provider)} nil)
        (t/is (identical? native (:model (extensions/build-extension-context))))
        (testing "a provider switch resolves the record even when the model ID is unchanged"
          (reset! (:provider ag) :test-adapter)
          (let [model (:model (extensions/build-extension-context))]
            (t/is (identical? adapter model))
            (t/is (= "https://adapter.test/v1" (:base-url model)))
            (t/is (= {"X-Test" "yes"} (:headers model)))))
        (testing "model and session changes are read from the current agent"
          (reset! (:model ag) "other")
          (t/is (identical? alternate (:model (extensions/build-extension-context))))
          (reset! (:agent-state (:engine cs))
                  (agent/make-agent-state :provider :test-native :model "test-model"))
          (t/is (identical? native (:model (extensions/build-extension-context)))))
        (testing "unregistered selections have no resolved model"
          (reset! (:model (runtime/agent-state (:engine cs))) "missing")
          (t/is (nil? (:model (extensions/build-extension-context))))
          (reset! (:model (runtime/agent-state (:engine cs))) "test-model")
          (reset! (:provider (runtime/agent-state (:engine cs))) :missing)
          (t/is (nil? (:model (extensions/build-extension-context)))))
        (finally
          (tu/clear-extension-wiring!))))))

(deftest test-build-context-resolves-scoped-models
  (let [native (m/map->Model {:provider :test-native :id "test-model"})
        alternate (m/map->Model {:provider :test-native :id "other"})
        foreign (m/map->Model {:provider :test-adapter :id "test-model"})
        handle (hs/new-handle)
        ag (runtime/agent-state handle)
        cs (hs/core-state handle {:config cfg/default-config
                                  :provider :test-native
                                  :model "test-model"
                                  :scoped-models ["test-native/test-model"
                                                  "test-adapter/test-model"]})]
    (with-redefs [m/providers-atom (atom {:test-native {:models [native alternate]}
                                          :test-adapter {:models [foreign]}})]
      (try
        (ui-registry/build-extension-ui-registry
         {:tui nil :cs cs} {:fdp (fdp/make-footer-data-provider)} nil)
        (testing "full ids resolve to pi ScopedModel-shaped entries"
          (let [scoped (:scoped-models (extensions/build-extension-context))]
            (t/is (= #{:model} (set (keys (first scoped))))
                  "no :thinking-level — kmet drops scoped thinking levels")
            (t/is (identical? native (:model (first scoped))))
            (t/is (identical? foreign (:model (second scoped))))))
        (testing "duplicate entries collapse (pi modelsAreEqual dedupe)"
          (reset! (:scoped-models ag) ["test-native/test-model"
                                       "test-native/test-model"])
          (let [scoped (:scoped-models (extensions/build-extension-context))]
            (t/is (= 1 (count scoped)))
            (t/is (identical? native (:model (first scoped))))))
        (testing "bare ids resolve against the current provider"
          (reset! (:scoped-models ag) ["test-model"])
          (t/is (identical? native
                            (:model (first (:scoped-models (extensions/build-extension-context))))))
          (reset! (:provider ag) :test-adapter)
          (t/is (identical? foreign
                            (:model (first (:scoped-models (extensions/build-extension-context)))))))
        (testing "entries that no longer resolve drop out"
          (reset! (:scoped-models ag) ["test-native/missing" "also-missing"
                                       "test-native/test-model"])
          (let [scoped (:scoped-models (extensions/build-extension-context))]
            (t/is (= 1 (count scoped)))
            (t/is (identical? native (:model (first scoped))))))
        (testing "no scoping configured stays empty"
          (reset! (:scoped-models ag) [])
          (t/is (= [] (:scoped-models (extensions/build-extension-context)))))
        (finally
          (tu/clear-extension-wiring!))))))

(deftest test-extension-reset-closes-open-dialogs
  (testing "the registry :reset teardown closes an open ui-custom dialog
            through the ordered leave-then-dispose path on both surfaces:
            the dialog is disposed, its surface empty, and no
            disposed-while-mounted violation is logged (the follow-up had
            no harness for this path)"
    (let [ui (tui/create-tui nil)
          ed (editor/make-editor)
          cs (assoc (hs/core-state (hs/new-handle)
                                   {:tui ui
                                    :current-editor-atom (atom ed)
                                    :status-indicator (status-indicator/make-status-indicator)
                                    :config cfg/default-config})
                    :dock-stack (atom []))
          ch (chat-history/make-chat-history)
          disposed (atom [])
          logged (atom [])
          registry ((var ui-registry/build-extension-ui-registry)
                    {:tui ui :cs cs}
                    {:ftr {:extension-statuses-atom (atom {})}
                     :ed ed
                     :ch ch
                     :fdp (fdp/make-footer-data-provider)
                     :widgets-above-atom (atom {})
                     :widgets-below-atom (atom {})}
                    nil)
          factory (fn [_tui _theme _kb _close]
                    {:render (fn [_width] [])
                     :dispose (fn [] (swap! disposed conj :dialog))})]
      (try
        (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))]
          ;; dock surface: the path the release!-before-dispose fix covers
          ((:custom registry) factory {})
          (t/is (= 1 (count @(:dock-stack cs))) "the dialog docked")
          ((:reset registry))
          (t/is (= [:dialog] @disposed) "reset disposed the dock dialog")
          (t/is (empty? @(:dock-stack cs)) "the dock is empty")
          ;; overlay surface: the membership hide leaves before dispose
          (reset! disposed [])
          ((:custom registry) factory {:overlay true
                                       :overlay-options {:anchor :center}})
          (t/is (true? (tui/tui-has-overlay? ui)) "the dialog floated")
          ((:reset registry))
          (t/is (= [:dialog] @disposed) "reset disposed the overlay dialog")
          (t/is (false? (tui/tui-has-overlay? ui)) "the overlay stack is empty")
          (t/is (empty? @logged) "no disposed-while-mounted violation"))
        (finally
          (tu/clear-extension-wiring!))))))

(defn- build-extension-registry
  "The /reload fixture both widget-reset tests drive: an extension UI
   registry over fresh above/below widget atoms and the given components."
  [{:keys [ui ed above below hdr sp1 header-container ch]}]
  ((var ui-registry/build-extension-ui-registry)
   {:tui ui
    :cs (assoc (hs/core-state (hs/new-handle)
                              {:tui ui
                               :current-editor-atom (atom ed)
                               :status-indicator (status-indicator/make-status-indicator)
                               :config cfg/default-config})
               :dock-stack (atom []))}
   {:ftr {:extension-statuses-atom (atom {})}
    :ed ed
    :ch ch
    :fdp (fdp/make-footer-data-provider)
    :hdr hdr
    :sp1 sp1
    :header-container header-container
    :widgets-above-atom above
    :widgets-below-atom below}
   nil))

(defn- make-cleanup-widget
  "A widget factory whose owned subtree runs a with-let cleanup counted in
   CLEANUPS — the shared fixture of the /reload widget tests."
  [cleanups]
  (fn []
    ((var ui-registry/make-extension-widget-component)
     nil
     [:container {}
      [:text {:padding-x 0 :padding-y 0} "w"]
      [(fn [_props]
         (component/with-let [_ (swap! cleanups inc)]
           [:text {:padding-x 0 :padding-y 0} "owned"]
           (finally (swap! cleanups dec)))) {}]])))

(defn- make-reload-fixture
  "The /reload widget-test fixture: a TUI, the default editor, fresh widget
   atoms, header chrome and a chat history, with the extension registry over
   them and a cleanup counter the widget factory bumps."
  []
  (let [ui (tui/create-tui nil)
        ed (editor/make-editor)
        above (atom {})
        below (atom {})
        header-container (container/make-container [])
        hdr (expandable-text/make-expandable-text (fn [] "hdr") (fn [] "hdr"))
        sp1 (text/make-text " " 0 0)
        ch (chat-history/make-chat-history)
        cleanups (atom 0)]
    {:above above
     :hdr hdr
     :sp1 sp1
     :cleanups cleanups
     :widget (make-cleanup-widget cleanups)
     :registry (build-extension-registry
                {:ui ui :ed ed :above above :below below :hdr hdr
                 :sp1 sp1 :header-container header-container :ch ch})}))

(defn- dispose-reload-fixture!
  "Release MAKE-RELOAD-FIXTURE's header chrome and the installed extension
   context."
  [{:keys [hdr sp1]}]
  (protocols/dispose hdr)
  (protocols/dispose sp1)
  (tu/clear-extension-wiring!))

(deftest test-reset-disposes-extension-widgets-header-and-editor
  (testing "finding 1: the /reload :reset path, the header swap and the
            editor swap dispose the values they drop — records carry no
            :dispose key, so the old duck-typed probes lost them with the
            swap"
    (let [{:keys [registry above cleanups widget] :as fixture} (make-reload-fixture)
          headers (atom [])
          header-factory (fn [tag]
                           (fn [_t _theme]
                             {:render (fn [_] [])
                              :dispose (fn [] (swap! headers conj tag))}))]
      (try
        ;; widgets: the replace path already worked; reset must dispose too
        ((:set-widget registry) :w1 (widget) {})
        (protocols/render (get @above :w1) 40)
        (t/is (= 1 @cleanups) "widget subtree initialized by render")
        ((:set-widget registry) :w1 (widget) {})
        (t/is (= 0 @cleanups) "replace disposed the previous widget")
        (protocols/render (get @above :w1) 40)
        ((:reset registry))
        (t/is (= 0 @cleanups) "reset disposed the dropped widget")
        (t/is (empty? @above))
        ;; header: a swap and the reset must dispose the previous header
        ((:set-header registry) (header-factory :one))
        ((:set-header registry) (header-factory :two))
        (t/is (= [:one] @headers) "header swap disposed the previous header")
        ((:reset registry))
        (t/is (= [:one :two] @headers) "reset disposed the custom header")
        ;; footer: set-footer replace and reset dispose through
        ;; tui-remove-child's tolerant verb
        (let [footers (atom [])
              footer-factory (fn [tag]
                               (fn [_t _theme _data]
                                 {:render (fn [_] [])
                                  :dispose (fn [] (swap! footers conj tag))}))]
          ((:set-footer registry) (footer-factory :one))
          ((:set-footer registry) (footer-factory :two))
          (t/is (= [:one] @footers) "footer swap disposed the previous footer")
          ((:reset registry))
          (t/is (= [:one :two] @footers) "reset disposed the custom footer"))
        ;; editor: a swap must dispose the replaced custom editor (never the
        ;; default `ed`)
        (let [seen (atom [])
              ed-a (editor/make-editor)
              ed-b (editor/make-editor)]
          (with-redefs-fn
            (assoc {} #'ui-registry/dispose-dialog-component!
                   (fn [c] (swap! seen conj c)))
            (fn []
              ((:set-editor-component registry) (fn [_ _ _] ed-a))
              ((:set-editor-component registry) (fn [_ _ _] ed-b))))
          (t/is (= [ed-a] @seen)
                "the previous custom editor was disposed; the default ed was not"))
        (finally
          (dispose-reload-fixture! fixture))))))

(deftest ^:slow test-fifty-reload-cycles-leave-no-watches-behind
  (testing "50 widget set+render+reset cycles keep
            live-watch-count flat and unwind every with-let cleanup — a
            regression guard for the /reload drop sites"
    (let [baseline (component/live-watch-count)
          {:keys [registry above cleanups widget] :as fixture} (make-reload-fixture)]
      (try
        (dotimes [_ 50]
          ((:set-widget registry) :w1 (widget) {})
          (protocols/render (get @above :w1) 40)
          ((:reset registry)))
        (t/is (= 0 @cleanups) "every widget subtree unwound")
        (t/is (empty? @above))
        ;; <=, not =: the baseline is registry-wide, and a GC during the
        ;; cycles can clear unrelated stale entries from earlier tests.
        (t/is (<= (component/live-watch-count) baseline)
              "no watch entry survived the cycles")
        (finally
          (dispose-reload-fixture! fixture))))))


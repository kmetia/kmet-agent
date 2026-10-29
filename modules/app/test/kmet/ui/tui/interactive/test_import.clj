(ns kmet.ui.tui.interactive.test-import
  "The /import command: picking a session file, switching the live session, and the
   re-render that follows (pi: handleImportCommand)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.app.events :as events]
            [kmet.test-utils :as tu :refer [slash strip-ansi]]
            [clojure.string :as str]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.core :as tui]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.layout :as layout]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.app.commands :as commands]
            [kmet.ui.tui.scoped-models-selector :as scoped-models-selector]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.model-catalog :as model-catalog]
            [kmet.ui.tui.model-selector :as model-selector]
            [kmet.ai.models :as m]
            [kmet.ai.auth :as auth]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.tools.registry :as tools]
            [kmet.libs.http :as http]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.session.core :as session]
            [kmet.app.session.export :as session-export]
            [kmet.app.session.lifecycle :as lifecycle]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.skills :as skills]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.ui.tui.interactive.test-handle-support :as hs]
            [kmet.ui.tui.interactive.test-support :as support]))

;; The interactive builtins register the app's whole slash registry; clear it
;; so a later namespace never sees the tree filled by this one.
(t/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

(def ^:private no-image-caps
  "Capabilities stub for the /settings tests: the image rows (Show images /
  Image width) exist only when the terminal reports image support
  (image-rows), so index-based row navigation must not depend on the
  developer's terminal. Stub get-capabilities alongside the other test
  redefs."
  {:images nil :true-color true :hyperlinks false})

;; ─── /import (pi: handleImportCommand) ────────────────────────────────────

(defn- import-test-cs
  "CoreState for the /import tests over a real handle: real chat/session/
   agent/footer state so the resume path runs for real (pi: importFromJsonl
   swaps the runtime), with only the dock and the render calls stubbed (no
   terminal in tests).

   The footer provider and the chat history read the handle's runtime cwd, as
   in build-layout, and the state's system-prompt options carry a
   launch-project context file — the switch must keep them (only the cwd line
   follows the session's working directory)."
  [active-sess]
  (let [handle (hs/handle-with {:session active-sess})
        ch (chat-history/make-chat-history :cwd-fn #(runtime/cwd handle))
        prov (fdp/make-footer-data-provider :cwd-atom (:cwd handle) :session active-sess)
        ed (editor/make-editor)
        prompt-opts {:cwd (runtime/cwd handle)
                     :context-files [{:path "AGENTS.md" :content "LAUNCH-PROJECT CONTEXT"}]}
        ag (runtime/agent-state handle)]
    (reset! (:system-prompt-opts ag) prompt-opts)
    (reset! (:system ag) (apply skills/build-system-prompt (mapcat identity prompt-opts)))
    (hs/core-state handle {:tui nil
                           :chat-history ch
                           :editor ed
                           :current-editor-atom (atom ed)
                           :footer-provider prov})))

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
    (let [parse (var session-export/parse-path-argument)]
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
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
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
                        dock/mount! (support/capture-mount! sel-ref)]
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
              (reset! (:turn-running? (:engine cs)) true)
              ((:handler cmd) cs (str dir "/whatever.ednl"))
              (t/is (= "Wait for the current response to finish before importing."
                       (:content (last-message ch)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-import-confirm-flow
  (testing "/import copies the file into the active session's directory and
            resumes it when the Yes/No confirm is accepted"
    (commands/clear-commands!)
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
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
                          dock/mount! (support/capture-mount! sel-ref)]
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
              (t/is (= (:id src) (:id (runtime/session (:engine cs))))
                    "the imported session is active")
              (t/is (= (:id src) (:id (:session (runtime/agent-state (:engine cs)))))
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
                       (slash (get-in @(:system-prompt-opts (runtime/agent-state (:engine cs))) [:cwd])))
                    "and the system prompt options")
              (t/is (str/includes? (slash @(:system (runtime/agent-state (:engine cs)))) (slash foreign))
                    "the model is told where its tools will run")
              (t/is (= [{:path "AGENTS.md" :content "LAUNCH-PROJECT CONTEXT"}]
                       (get-in @(:system-prompt-opts (runtime/agent-state (:engine cs))) [:context-files]))
                    "project context files stay with the launch project")
              (t/is (str/includes? @(:system (runtime/agent-state (:engine cs))) "LAUNCH-PROJECT CONTEXT")
                    "and stay in the rebuilt prompt"))))
        (finally (fs/delete-tree dir))))))

(deftest test-session-switch-drops-runtime-tool-selection
  (testing "pi: a session switch (kmet -c at launch, /resume, /import)
            recreates the runtime from settings — the outgoing session's
            runtime tool selection must not leak into the resumed one"
    (let [dir (str (fs/absolutize (str "target/test-switch-tools-" (System/currentTimeMillis))))]
      (try
        (let [outgoing (session/create-session (str dir "/out"))
              _ (append-message! outgoing "hello")
              incoming (session/create-session (str dir "/in"))
              _ (append-message! incoming "imported question")
              cs (import-test-cs outgoing)
              ag (runtime/agent-state (:engine cs))
              active-names (fn [] (into #{} (map :name) (loop-state/active-tools ag)))
              prompt-tool-names (fn []
                                  (into #{}
                                        (map :name)
                                        (get-in @(:system-prompt-opts ag) [:tools])))]
          ;; the config-resolved surface at startup (-bash in settings.edn)
          (reset! (:default-tools ag) (tools/resolve-default-tools ["-bash"]))
          (t/is (not (contains? (active-names) "bash"))
                "the configured surface excludes bash")
          (testing "a /tools selection in the outgoing session enables bash"
            (loop-state/set-active-tools! ag (conj (vec (active-names)) "bash"))
            (t/is (contains? (active-names) "bash"))
            (t/is (contains? (prompt-tool-names) "bash")
                  "the system prompt options follow the runtime selection"))
          (with-redefs [tui/tui-request-render (fn [_])
                        tui/tui-set-focus (fn [_ _])]
            (session-admin/restore-session! cs incoming true))
          (t/is (nil? @(:enabled-tools ag))
                "the runtime allowlist does not carry over")
          (t/is (not (contains? (active-names) "bash"))
                "the configured surface governs the resumed session")
          (t/is (not (contains? (prompt-tool-names) "bash"))
                "and the rebuilt prompt options agree"))
        (finally (fs/delete-tree dir))))))

(deftest test-a-session-switch-installs-the-front-ends-session
  (testing "the handle's session ops install nothing; the front end installs
            the process-wide extension session after every move — a command
            handler runs outside a run and must act on the session the user
            chose, and a mode whose runtime moved without an install would
            leave the old session reachable"
    (let [dir (str (fs/absolutize (str "target/test-install-session-" (System/currentTimeMillis))))]
      (try
        (let [outgoing (session/create-session (str dir "/out"))
              _ (append-message! outgoing "hello")
              incoming (session/create-session (str dir "/in"))
              _ (append-message! incoming "imported")
              cs (import-test-cs outgoing)
              installed (atom [])]
          (with-redefs [tui/tui-request-render (fn [_])
                        tui/tui-set-focus (fn [_ _])
                        extensions/install-session! (fn [s] (swap! installed conj s))]
            (session-admin/restore-session! cs incoming true))
          (t/is (= [(:id incoming)] (mapv :id @installed))
                "exactly the front end installed: the runtime's own ops neither
                 install nor duplicate the session switch"))
        (finally (fs/delete-tree dir))))))

(deftest test-import-cancellation-paths
  (testing "/import leaves the session and the filesystem alone when the
            confirmation is declined, escaped, or cancelled by an extension"
    (commands/clear-commands!)
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
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
                          dock/mount! (support/capture-mount! sel-ref)]
              (testing "escape shows pi's cancelled status and writes nothing"
                (reset! sel-ref nil)
                ((:handler cmd) cs (:file src))
                (protocols/handle-input @sel-ref "\u001b")
                (t/is (= {:role :status :content "Import cancelled"} (last-message ch)))
                (t/is (not (fs/exists? stored)) "no copy")
                (t/is (= (:id active) (:id (runtime/session (:engine cs)))) "same session"))
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
                (tu/clear-listeners! (runtime/agent-ctx (:engine cs)))
                (events/on! (runtime/agent-ctx (:engine cs))
                            :session-before-switch (fn [_] {:cancel true}))
                ((:handler cmd) cs (:file src))
                (protocols/handle-input @sel-ref "\r")
                (t/is (= {:role :status :content "Import cancelled"} (last-message ch)))
                (t/is (not (fs/exists? stored)) "the copy is still pending")
                (t/is (= (:id active) (:id (runtime/session (:engine cs))))))
              (testing "a source already in the session dir is not re-copied"
                (reset! sel-ref nil)
                (tu/clear-listeners! (runtime/agent-ctx (:engine cs)))
                ((:handler cmd) cs (:file active))
                (protocols/handle-input @sel-ref "\r")
                (t/is (= 1 (count (fs/list-dir (str dir "/dest"))))
                      "no suffixed duplicate")
                (t/is (= (:id active) (:id (runtime/session (:engine cs)))))))))
        (finally (fs/delete-tree dir))))))

(deftest test-import-keeps-the-current-cwd-when-the-recorded-one-is-gone
  (testing "a recorded cwd that no longer exists keeps the current runtime
            cwd — kmet says so and continues (pi: MissingSessionCwdError asks
            for a fallback cwd)"
    (commands/clear-commands!)
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
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
                          dock/mount! (support/capture-mount! sel-ref)]
              ((:handler (commands/find-command "import")) cs (:file src))
              (protocols/handle-input @sel-ref "\r")
              (t/is (= (:id src) (:id (runtime/session (:engine cs)))) "the session still switches")
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
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-export-cwd-" (System/currentTimeMillis))))
          project (str dir "/project")]
      (try
        (fs/create-dirs project)
        (let [sess (session/create-session (str dir "/sessions") {:cwd project})]
          (append-message! sess "hello")
          (let [cs (import-test-cs sess)
                ch (:chat-history cs)]
            ((:handler (commands/find-command "export")) (support/command-ctx cs) "")
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
                  ((:handler (commands/find-command "export")) (support/command-ctx cs) "\"\""))
                (t/is (str/includes? (slash (str (:content (last-message ch)))) (slash project))
                      "not a 'Failed to export session' error")))))
        (finally (fs/delete-tree dir))))))

(deftest test-path-argument-empty-is-none
  (testing "a quoted but empty path argument reads as none (pi:
            getPathCommandArgument) — /export falls back to its default and
            /import to its usage line instead of resolving the process cwd"
    (let [parse (var session-export/parse-path-argument)]
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
    (support/install-app-keybindings!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [dir (str (fs/absolutize (str "target/test-mid-turn-guards-"
                                       (System/currentTimeMillis))))]
      (try
        (let [ch (chat-history/make-chat-history)
              cs (hs/core-state (hs/handle-with {:session (session/create-session dir)})
                                {:turn-running? true
                                 :chat-history ch})]
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
          sel-ref (atom nil)]
      (try
        (let [sess (session/create-session dir)
              entry (session/append-entry sess
                                          {:role :user
                                           :content [{:type :text :text "q"}]})
              cs (hs/core-state (hs/handle-with {:session sess}))]
          (with-redefs [dock/mount! (support/capture-mount! sel-ref)
                        tui/tui-request-render (fn [_])
                        ;; hermetic: no branchSummary.skipPrompt from the
                        ;; developer's settings file
                        cfg/global-settings-path (fn [] (str dir "/no-settings.edn"))]
            (testing "Summarize branch? selector is framed in the dock with all options"
              ((var session-admin/ask-branch-summary) cs sess entry)
              (let [text (str/join "\n" (map strip-ansi
                                             (protocols/render @sel-ref 80)))]
                (t/is (str/includes? text "Summarize branch?") "framed title")
                (t/is (str/includes? text "No summary"))
                (t/is (str/includes? text "Summarize with custom prompt")
                      "all three options render (a bare overlay with :height 3 cut the third)")
                (t/is (str/includes? text "─") "border drawn")))
            (testing "custom instructions open a framed input in the dock"
              ((var session-admin/prompt-custom-summary!) cs sess entry)
              (let [text (str/join "\n" (map strip-ansi
                                             (protocols/render @sel-ref 80)))]
                (t/is (str/includes? text "Custom branch summarization instructions"))
                (t/is (str/includes? text "submit")
                      "the input dialog's keybinding hint renders")))))
        (finally (fs/delete-tree dir))))))

(deftest test-tree-summarize-prompt-skip-prompt-setting
  (testing "branchSummary.skipPrompt skips the question and defaults to no
            summary (pi: getBranchSummarySkipPrompt)"
    (let [dir (str (fs/absolutize (str "target/test-tree-skip-prompt-"
                                       (System/currentTimeMillis))))
          settings-file (str dir "/settings.edn")
          sel-ref (atom nil)
          navigated (atom nil)]
      (fs/create-dirs dir)
      (try
        (spit settings-file "{:branch-summary {:skip-prompt true}}\n")
        (let [sess (session/create-session dir)
              entry (session/append-entry sess
                                          {:role :user
                                           :content [{:type :text :text "q"}]})
              cs (hs/core-state (hs/handle-with {:session sess}))]
          (with-redefs [dock/mount! (support/capture-mount! sel-ref)
                        tui/tui-request-render (fn [_])
                        cfg/global-settings-path (fn [] settings-file)
                        session-admin/navigate-tree!
                        (fn [& args] (reset! navigated args))]
            ((var session-admin/ask-branch-summary) cs sess entry))
          (t/is (some? @navigated) "the navigation runs without the question")
          (t/is (= [false nil false nil] (drop 3 @navigated))
                "defaults to no summary (pi: wantsSummary stays false)")
          (t/is (nil? @sel-ref) "no selector was mounted"))
        (finally (fs/delete-tree dir))))))

(deftest test-summarization-retry-event-handlers
  (testing "the summarization retry events drive the status area (pi:
            summarization_retry_scheduled → error line + RetryStatusIndicator,
            _attempt_start → the compaction/branch-summary indicator,
            _finished → the kind-gated retry clear)"
    (let [cs (support/test-status-cs)
          h ((var layout/make-agent-event-handler)
             {:chat-history (chat-history/make-chat-history)
              :tui {:render-requested? (atom false)}
              :cs-ref (atom cs)
              :pending-tool-comps (atom {})})]
      (h {:type :summarization-retry-scheduled
          :attempt 1 :max-attempts 3 :delay-ms 2000
          :error-message "rate limit exceeded"})
      (t/is (= :retry (:kind @(:status-current cs)))
            "the retry countdown took the status slot")
      (t/is (some #(str/includes? % "Retrying (1/3)") (support/status-lines cs))
            "pi's countdown text renders")
      (h {:type :summarization-retry-attempt-start :source :compaction
          :reason :threshold})
      (t/is (= :compaction (:kind @(:status-current cs)))
            "the retried compaction re-shows its indicator")
      (h {:type :summarization-retry-attempt-start :source :branch-summary})
      (t/is (= :branch-summary (:kind @(:status-current cs)))
            "a retried branch summarization re-shows the branch indicator")
      (h {:type :summarization-retry-finished})
      (t/is (= :branch-summary (:kind @(:status-current cs)))
            "the retry clear is kind-gated (pi: clearStatusIndicator)"))))

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
              engine (hs/handle-with {:session sess :cwd spelled})]
          (t/is (= spelled ((var lifecycle/apply-session-cwd!) engine sess))
                "the cwd in effect is returned unchanged")
          (t/is (= spelled (runtime/cwd engine)) "and kept as spelled"))
        (finally (fs/delete-tree dir))))))

(deftest test-scoped-models-selector-initial-state
  (testing "/scoped-models opens the selector with session scoped models, then
            settings :enabled-models patterns, else nil (all enabled)"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})
          sel-ref (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    cfg/get-enabled-models-live (fn [_] nil)
                    model-catalog/update-available-provider-count! (fn [_] nil)
                    dock/mount! (support/capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        (testing "no session scoped models and no patterns → all enabled"
          ((:handler (commands/find-command "scoped-models")) cs "")
          (t/is (nil? (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref))))
        (testing "session scoped models win"
          (loop-state/set-scoped-models! ag ["opencode-go/deepseek-v4-flash"])
          ((:handler (commands/find-command "scoped-models")) cs "")
          (t/is (= ["opencode-go/deepseek-v4-flash"]
                   (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref))))
        (testing "settings :enabled-models patterns resolve"
          (loop-state/set-scoped-models! ag [])
          (with-redefs [cfg/get-enabled-models-live
                        (fn [_] ["opencode-go/deepseek-v4-flash"])]
            ((:handler (commands/find-command "scoped-models")) cs "")
            (t/is (= ["opencode-go/deepseek-v4-flash"]
                     (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref)))))
        (testing "unresolved patterns survive as [unavailable] rows alongside
                  resolved ones (pi: no-match diagnostics appended)"
          (loop-state/set-scoped-models! ag [])
          (with-redefs [cfg/get-enabled-models-live
                        (fn [_] ["opencode-go/deepseek-v4-flash" "ghost/model"])]
            ((:handler (commands/find-command "scoped-models")) cs "")
            (t/is (= ["opencode-go/deepseek-v4-flash" "ghost/model"]
                     (scoped-models-selector/scoped-models-get-enabled-ids @sel-ref)))))))))

(deftest test-scoped-models-edit-updates-session
  (testing "selector edits write the session scoped list and clear on all-enabled"
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})
          sel-ref (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    model-catalog/update-available-provider-count! (fn [_] nil)
                    dock/mount! (support/capture-mount! sel-ref)
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

(deftest test-scoped-models-save-drops-models-no-longer-available
  (testing "Ctrl+S excludes models that became unavailable after opening the selector"
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (let [kept (m/map->Model {:provider :test-provider :id "kept"})
          disappeared (m/map->Model {:provider :test-provider :id "disappeared"})
          disabled (m/map->Model {:provider :test-provider :id "disabled"})
          initially-available [kept disappeared disabled]
          available-at-save [kept disabled]
          available-calls (atom 0)
          handle (hs/new-handle)
          ag (runtime/agent-state handle)
          _ (loop-state/set-scoped-models! ag ["test-provider/kept"
                                               "test-provider/disappeared"])
          cs (hs/core-state handle {:config cfg/default-config
                                    :tui nil})
          sel-ref (atom nil)
          saved (atom ::not-saved)]
      ((var builtins/register-builtin-commands!) cfg/default-config)
      (with-redefs [m/get-available (fn []
                                      (if (= 1 (swap! available-calls inc))
                                        initially-available
                                        available-at-save))
                    auth/configured? (fn [_] true)
                    chat-history/chat-history-add-message! (fn [_ _] nil)
                    cfg/get-enabled-models-live (fn [_] nil)
                    cfg/set-enabled-models! (fn [ids] (reset! saved ids))
                    model-catalog/update-available-provider-count! (fn [_] nil)
                    dock/mount! (support/capture-mount! sel-ref)
                    tui/tui-set-focus (fn [_ _])
                    tui/tui-request-render (fn [_])]
        ((:handler (commands/find-command "scoped-models")) cs "")
        (protocols/handle-input @sel-ref "\u0013")
        (t/is (= ["test-provider/kept"] @saved)
              "only still-available enabled models are persisted")))))

(deftest test-settings-thinking-row
  (testing "/settings opens a settings list whose thinking row changes the
            session level and persists to settings"
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4.1-flash"
                                    :thinking :off
                                    :tui nil})
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    model-selector/sync-footer-model! (fn [_] nil)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (support/capture-mount! sl-ref)
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
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (support/capture-mount! sl-ref)
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
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/get-retry-settings-live
                    (fn [_] {:enabled true :max-retries 3 :base-delay-ms 2000})
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (support/capture-mount! sl-ref)
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
          cs (hs/core-state (hs/new-handle)
                            {:config {:show-terminal-progress false}
                             :tui {:terminal (atom term)}})]
      (with-redefs [cfg/global-settings-path (fn [] missing)]
        ((var turn/set-terminal-progress!) cs false)
        (t/is (= [false] @calls) "the clear passes while disabled")
        ((var turn/set-terminal-progress!) cs true)
        (t/is (= [false] @calls) "activation is still settings-gated")))))

(deftest test-settings-http-transport-row
  (testing "/settings HTTP transport row switches the runtime transport
            (platform → curl → platform) and persists"
    (support/install-app-keybindings!)
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var builtins/register-builtin-commands!) cfg/default-config)
    (let [handle (hs/new-handle)
          cs (hs/core-state handle {:config cfg/default-config
                                    :provider :opencode-go
                                    :model "deepseek-v4-flash"
                                    :tui nil})
          sl-ref (atom nil)
          saved (atom nil)]
      (with-redefs [auth/configured? (fn [_] true)
                    timg/get-capabilities (constantly no-image-caps)
                    chat-history/chat-history-get-thinking-hidden (fn [_] false)
                    cfg/save-setting! (fn [path value] (reset! saved [path value]))
                    dock/mount! (support/capture-mount! sl-ref)
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


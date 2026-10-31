(ns kmet.ui.tui
  "The TUI as a mode row, and the `:ui` provider a terminal needs:
   `kmet --profile tui`, the kmet2 entry's default profile.

   The mode's tree moved here at 5l-iii, so a run is kmet2's own front end:
   the row mounts `kmet.ui.tui.interactive` over the composition the entry
   booted, the run reads the composition's `:tools`, `:sessions` and
   `:systemPrompt` rows through the ctx it is given, and the startup a
   *process* world needs — catalogs, config, extension packages, themes —
   arrives as the config the entry loads into this tree before mounting the
   row (`kmet.app.world`, 5l-ii).

   **The provider.** `provider` is the terminal implementation of
   `kmet.app.ui`'s contract: a dialog is drawn by the running TUI
   (`kmet.ui.tui.interactive/request!`), and `:render-tree` is a no-op because
   this provider *is* the tree — its run owns the screen instead of drawing a
   component a caller hands it. `provider-plugin` mounts it and the approval
   seam together: a permission `:ask` becomes the TUI's Yes/No dialog, which
   is what makes an `:ask-all` policy usable at a terminal (the minimal
   bundle's default denies because nobody could ask).

   **The agent is the composition's.** `build-layout` creates the run's agent
   through the composition's `:agents` registry (`:create!` with the resolved
   session, the run config and the presets the row named), so a TUI run is a
   handle like any other: its ctx is a child of the ctx the row mounted on, its
   settings and session ops write that agent, and the registry lists it
   (`:agent/created`). A **preset** — a bundle mounted per agent (7b-ii) — is
   named by the row's config (`{:presets [:bundle/gated]}`) and mounted under
   that child ctx before the agent exists; `:profile tui-ask` is the built-in
   that names the gate.

   **The invocation is the run's, and this row only maps it.** `-c`/
   `--continue`, `-r`/`--resume` and `--session <id>` arrive in the row's config
   and become `interactive/run`'s opts: that mode already resolves a session
   from those keys, so the row only has to say that the flags have a home here
   (`:session-lifecycle`, declared where the profile names the row). The run's
   overrides ride the same map — `--model` already resolved to its catalog id,
   `--provider`, `--thinking`, the `--models` scope, and the
   `--system-prompt`/`--append-system-prompt`/`--session-dir` config overrides —
   and `interactive/run` applies them to the config it loads, so a TUI run honors
   them as kmet1's front end always did (`run-opts`). A mode that creates its
   agent through `:agents` has no session option to give its factory yet, and
   `kmet.app.core` refuses the session flags there by name."
  (:require [kmet.app.invocation :as invocation]
            [kmet.app.permissions :as permissions]
            [kmet.app.ui :as ui]
            [kmet.srce.core :as srce]
            [kmet.ui.tui.interactive :as interactive]))

(defn- run-opts
  "The invocation's input this row applies, in kmet1's own spellings: the mode
   the world load and the run are told they are, the run's overrides — the model
   pattern `--model` resolved, `--provider`, `--thinking`, the scope
   `--models`, the prompt and session-dir overrides — and the session a run
   opens (`:session`, `:resume`, `:continue`). `interactive/run` applies the
   overrides to the config it loads (`cfg/apply-cli-overrides`) and resolves the
   session from those keys, so this row only maps names, and a key the
   invocation did not give stays absent. A run the command line did not ask to
   continue resolves a new session, as it always did."
  [config]
  (cond-> (merge {:mode :interactive}
                 (select-keys config invocation/run-override-keys))
    ;; the agent's per-agent world: a preset is mounted under the child ctx
    ;; `build-layout` creates (7b-ii's `:setup`), so the row's config is the
    ;; only place a profile names it — no flag, no code
    (:presets config) (assoc :presets (:presets config))
    (:session config) (assoc :session (:session config))
    (:resume config) (assoc :resume true)
    (:continue config) (assoc :continue true)))

(defn- run-tui!
  "Run the TUI on CTX with the entry's loaded world CONFIG until it quits, and
   report the exit.

   The entry point waits on `:on-result` (`kmet.app.core/run-mode`), so it fires
   on both paths: the mode owns its output, and a failing TUI reports the
   failure here instead of leaving the entry waiting for a promise that will
   never be delivered."
  [ctx config on-result]
  (try
    ;; the composition's ctx, not a process root: the run's tool table, system
    ;; prompt and session log are the ones the profile mounted, and the config
    ;; is the world the entry loaded into that same tree
    (interactive/run (:config config) (run-opts config) ctx)
    (on-result {:exit 0})
    (catch Throwable t
      (binding [*out* *err*]
        (println "Error:" (or (ex-message t) (str (class t))))
        (flush))
      (on-result {:exit 1}))))

(defn- serve
  "The `:mode-tui` row's apply.

   The TUI runs on a thread of its own, as print mode's run does: the mount has
   to finish so the composition can settle before the session starts, and the
   entry point blocks on `:on-result` rather than on the mount. A diagnostic
   mount (`--dump-state`) hands the row no `:on-result` and starts nothing.

   Answers nil: the run's future is neither a setup handle nor a disposer, and a
   body that returned it would be read as an asynchronous setup (an unrealized
   `IPending`, §4 of the kernel note) or called as a disposer."
  [ctx config]
  (when-let [on-result (:on-result config)]
    (when-not (:config config)
      (throw (ex-info (str "the TUI mode needs the entry's loaded world: "
                           "kmet --profile tui")
                      {:type :kmet.app/no-world})))
    ;; the app scope: the row's own ctx is a mount site, and every lookup the
    ;; run makes (:tools, :sessions, :skills) walks up from the scope it was
    ;; given. The function is resolved here, while the mount is running, so the
    ;; future starts what the mount saw: a redefinition in force around the
    ;; mount (`with-redefs` in a test) covers the hand-off, never a later one.
    (let [run run-tui!]
      (future (run (:parent ctx) config on-result))
      nil)))

(def provider
  "The terminal `:ui` provider. Dialogs are answered by the running TUI; there
   is no tree to render into because the TUI owns the screen, and the one
   optional capability it adds over headless is dialogues a human answers."
  {:kind :terminal
   :render-tree (fn [_tree] nil)
   :request! interactive/request!
   :capabilities #{:dialogs}})

(srce/defplugin provider-plugin
  {:name :ui/terminal
   ;; both keys are one human at one terminal: the generic dialog port and the
   ;; permission `:ask` answer through the same running mode
   :provide #{:ui :approvals}
   :apply (fn [ctx _]
            (ui/provide! ctx :ui/terminal provider)
            (srce/provide! ctx :approvals
                           (permissions/approver interactive/ask!)
                           {:in (:parent ctx)})
            nil)})

(srce/defplugin plugin
  {:name :mode-tui
   ;; the run's agent reads these from the ctx it is created on (`create!`'s
   ;; child ctx, a descendant of this mount site): declare them so a composition
   ;; that patched one out fails at the mount instead of at the first prompt
   :inject [:tools :system-prompt]
   :apply serve})

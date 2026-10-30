(ns kmet.ui.tui.test-tui
  "The TUI mode row (`kmet.ui.tui`): the plugin the `:tui` profile
   mounts, and the two properties a test can hold without a terminal — the row
   is named what the profile expects, and a diagnostic mount (no `:on-result`)
   starts nothing.

   The mode itself is the interactive entry (`kmet.ui.tui.interactive`) over
   the composition's ctx; running it needs a real terminal, which is what
   `scripts/capture/pty_capture.py` drives."
  (:require [clojure.test :refer [deftest is]]
            [kmet.app.core :as core]
            [kmet.app.runtime :as runtime]
            [kmet.app.ui :as ui]
            [kmet.srce.core :as srce]
            [kmet.ui.tui :as tui]
            [kmet.ui.tui.interactive :as interactive]))

(deftest the-row-is-the-tui-mode
  (is (= :mode-tui (:name tui/plugin))
      "the name is what a failed mount reports and what --dump-state shows")
  (is (= [:tools :system-prompt] (:inject tui/plugin))
      "the ctx services the run reads are declared, so a composition that patched
       one out fails at the mount rather than at the first prompt"))

(deftest the-terminal-provider-answers-the-ui-contract
  (is (= tui/provider (ui/check :ui/terminal tui/provider))
      "the provider answers every required key")
  (is (= :terminal (:kind tui/provider)))
  (is (contains? (:capabilities tui/provider) :dialogs)
      "dialogs are the optional capability a terminal declares")
  (is (= :ui/terminal (:name tui/provider-plugin)))
  (is (= #{:ui :approvals} (:provide tui/provider-plugin))
      "the row provides the dialog port and the approval seam together"))

(deftest a-terminal-without-a-run-answers-inertly
  ;; The provider is mounted before the TUI runs and after it ends: its port
  ;; must answer then too, with the ABI's defaults, and an approval must deny
  ;; rather than park a tool call on a dialog nobody can see.
  (is (false? (interactive/request! nil {:kind :confirm})))
  (is (nil? (interactive/request! nil {:kind :select})))
  (is (nil? (interactive/request! nil {:kind :input})))
  (is (= {:decision :deny :reason "no terminal is running — nobody can ask"}
         (interactive/ask! nil {:tool-name "bash"}))))

(deftest the-tui-profile-composes-a-terminal-provider
  ;; The gate 5l-iii names: the profile's ctx provides the terminal `:ui`
  ;; provider (with the ABI port a mode binds) and the approval seam a gate
  ;; reads — and with no terminal running the seam denies rather than parking.
  (let [booted (runtime/boot (core/compose-config {:profile :tui}))]
    (try
      (let [ctx (:ctx booted)
            ui (srce/get ctx :ui)
            approvals (srce/get ctx :approvals)]
        (is (= :terminal (:kind ui)))
        (is (fn? (get-in ui [:port :request]))
            "the row publishes the ABI port, so a mode can bind it")
        (is (fn? (:ask! approvals)))
        (is (= {:decision :deny :reason "no terminal is running — nobody can ask"}
               ((:ask! approvals) ctx {:tool-name "bash"}))))
      (finally (runtime/shutdown! booted)))))

(deftest a-diagnostic-mount-starts-nothing
  ;; `--dump-state` mounts every row and hands none of them an :on-result: a
  ;; diagnostic that started a terminal session would be the bug it is meant to
  ;; find.
  (let [root (srce/root {:name :app})]
    (try
      (is (nil? ((:apply tui/plugin) root {}))
          "no :on-result, so no run")
      (finally (srce/dispose-subtree! root)))))

(deftest a-live-mount-answers-nil-not-the-runs-future
  ;; `apply` answers nil: the run's future is neither a setup handle nor a
  ;; disposer. The kernel reads an unrealized IPending return as an asynchronous
  ;; setup, so handing the future back left the row `:starting` and the boot
  ;; failed with "setup did not settle".
  (let [root (srce/root {:name :app})]
    (try
      (is (nil? (with-redefs-fn {#'tui/run-tui! (fn [_ _ _] nil)}
                  #((:apply tui/plugin) root {:on-result (fn [_] nil) :config {}}))))
      (finally (srce/dispose-subtree! root)))))

(deftest the-invocations-run-flags-reach-kmet1s-run
  ;; The same hand-off one category over: the run's overrides are kmet1's run
  ;; flags, and `interactive/run` applies them to the config it loads
  ;; (`cfg/apply-cli-overrides`), so the row maps names and decides nothing.
  ;; Dropping one here is the defect 5d found in rpc one mode over: the run
  ;; silently uses the settings instead.
  (let [run-opts @#'tui/run-opts]
    (is (= {:mode :interactive
            :model "deepseek-v4-flash" :provider :opencode-go :thinking :high
            :models ["deepseek-v4-flash"] :session-dir "target/sessions"
            :system-prompt "SYS" :append-system-prompt ["MORE"]}
           (run-opts {:model "deepseek-v4-flash" :provider :opencode-go
                      :thinking :high :models ["deepseek-v4-flash"]
                      :session-dir "target/sessions"
                      :system-prompt "SYS" :append-system-prompt ["MORE"]})))
    (is (= {:mode :interactive :system-prompt "SYS"}
           (run-opts {:system-prompt "SYS"}))
        "only the keys the invocation gave are in kmet1's opts")))

(deftest the-invocations-session-reaches-kmet1s-run
  ;; The row's only work for `-c`/`-r`/`--session` is the hand-off: kmet1's
  ;; `interactive/run` resolves the session from those keys itself, so the row
  ;; passes them through in its own spellings and decides nothing. A run the
  ;; command line did not ask to continue creates a session, as it always did.
  (let [run-opts @#'tui/run-opts]
    (is (= {:mode :interactive} (run-opts {})))
    (is (= {:mode :interactive :continue true} (run-opts {:continue true})))
    (is (= {:mode :interactive :resume true} (run-opts {:resume true})))
    (is (= {:mode :interactive :session "1a001ea0a00"}
           (run-opts {:session "1a001ea0a00"})))
    (is (= {:mode :interactive :session "1a001ea0a00" :continue true}
           (run-opts {:session "1a001ea0a00" :continue true}))
        "an invocation that asked for both is the mode's to resolve")))

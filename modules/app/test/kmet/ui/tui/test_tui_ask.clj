(ns kmet.ui.tui.test-tui-ask
  "T-3: a preset reaches a TUI run's child ctx.

   The row's config names the bundles, `run-opts` carries the names into the
   run, and `build-layout`'s create opts mount them under the agent's child ctx
   before the agent exists (7b-ii's `:setup`). `:profile tui-ask` is the
   built-in that names the gate; the plain `:tui` profile names none, so the
   default TUI is unchanged.

   No terminal: the composition's terminal approver is what a gated agent's tool
   call asks, and its answer with no terminal running is the denial asserted
   here."
  (:require [clojure.test :refer [deftest is]]
            [kmet.app.core :as core]
            [kmet.app.permissions :as permissions]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]
            [kmet.ui.tui :as tui-row]
            [kmet.ui.tui.interactive :as interactive]
            [kmet.ui.tui.interactive.layout :as layout]))

(def ^:private config
  "Where the run's session goes: a disposable dir, never the developer's own."
  {:session-dir "target/test-tui-ask"})

(defn- mode-row
  "The row `:mode true` marks — the one the entry hands the invocation's input."
  [profile]
  (first (filter :mode (:rows (core/compose-config {:profile profile})))))

(def ^:private a-call
  "A tool call the gate can be asked about."
  {:tool-name "bash" :args {:command "echo hi"} :tool-call-id "call-1"})

(deftest the-terminal-answer-names-the-scope-a-human-chose
  (is (= ["Yes" "Always for this agent" "Always for this workspace" "No"]
         interactive/ask-choices)
      "the order a human reads: this call, then the remembered ones, then no")
  (is (= :allow (interactive/ask-answer "Yes"))
      "yes once carries no scope — `:once` is what an unscoped allow means")
  (is (= {:decision :allow :scope :agent}
         (interactive/ask-answer "Always for this agent")))
  (is (= {:decision :allow :scope :workspace}
         (interactive/ask-answer "Always for this workspace")))
  (is (= {:decision :deny :reason "denied at the terminal"}
         (interactive/ask-answer "No")))
  (is (= {:decision :deny :reason "denied at the terminal"}
         (interactive/ask-answer nil))
      "a cancelled dialog is a no, not a yes")
  (is (= (interactive/ask-answer "No")
         (interactive/ask-answer nil))
      "cancel is that same single No: this call denied, nothing remembered"))

(deftest the-profile-names-the-gate-and-the-plain-tui-names-none
  (is (= [:bundle/gated] (get-in (mode-row :tui-ask) [:config :presets]))
      "tui-ask: the row names the gate as a per-agent preset")
  (is (nil? (get-in (mode-row :tui) [:config :presets]))
      "the default TUI names no preset: its agent's world is the root's"))

(deftest one-name-or-a-list-and-an-empty-list-is-none
  ;; The row's config is EDN a profile author writes: one name is as natural as
  ;; a vector, and an empty vector must mean "no preset", not a setup that
  ;; mounts nothing.
  (let [session nil
        config {}
        setup-of (fn [presets] (:setup (layout/create-opts config session {:presets presets})))]
    (is (fn? (setup-of [:bundle/gated])))
    (is (fn? (setup-of :bundle/gated)) "one name needs no vector")
    (is (nil? (setup-of [])) "an empty list mounts nothing")
    (is (nil? (setup-of nil)) "and no key at all is the plain TUI")))

(deftest the-rows-preset-is-carried-into-the-run-and-mounted-per-agent
  (let [presets (get-in (mode-row :tui-ask) [:config :presets])
        opts ((var tui-row/run-opts) {:presets presets})]
    (is (= presets (:presets opts)) "the row's names reach the run opts")
    (let [boot (runtime/boot (core/compose-config {:profile :tui-ask}) {})
          ctx (:ctx boot)
          agents (srce/get ctx :agents)]
      (try
        (let [create-opts (layout/create-opts config nil opts)]
          (is (fn? (:setup create-opts))
              "the create opts carry a setup: the preset mounts before the agent")
          (let [plain ((:create! agents) ctx {:config config})
                gated ((:create! agents) ctx create-opts)]
            (is (nil? (permissions/check (:ctx plain) a-call))
                "a plain agent's call proceeds: no policy on its chain")
            (is (= "no terminal is running — nobody can ask"
                   (:reason (permissions/check (:ctx gated) a-call)))
                (str "the gated agent asks — the row's preset is that agent's,"
                     " and the composition's terminal approver has no terminal"))
            (is (nil? (srce/get ctx :permissions))
                "the policy row is the agent's, not the root's")))
        (finally (srce/dispose-subtree! ctx))))))

(deftest the-question-shows-the-call-being-approved
  ;; The transcript block sits behind the dialog and its call line can be
  ;; scrolled out of the visible window, so the question carries the call —
  ;; the tool's own one-line title (`$ <command>` for bash, the path for the
  ;; file tools). A human approves what they can see.
  (let [boot (runtime/boot (core/compose-config {:profile :tui-ask}) {})
        ctx (:ctx boot)]
    (try
      (is (= "Allow bash?\n$ echo hi"
             (interactive/ask-title ctx {:tool-name "bash"
                                         :args {:command "echo hi"}})))
      (is (= "Allow bash?\n$ echo hi\nask before every call"
             (interactive/ask-title ctx {:tool-name "bash"
                                         :args {:command "echo hi"}
                                         :reason "ask before every call"}))
          "the policy's reason stays under the call")
      (is (= "Allow read?\nread README.md"
             (interactive/ask-title ctx {:tool-name "read"
                                         :args {:path "README.md"}}))
          "a file tool shows the path its own title names")
      (is (= "Allow no-such-tool?"
             (interactive/ask-title ctx {:tool-name "no-such-tool"}))
          "a tool outside the table still names itself, with no call line")
      (finally (srce/dispose-subtree! ctx)))))


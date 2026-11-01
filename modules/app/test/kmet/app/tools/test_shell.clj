(ns kmet.app.tools.test-shell
  "Landing 9a's gate: the shell settings are a per-agent service, not a process
   atom, so two agents in one process run two shells.

   Every agent gets `:shell` from its own config on its own ctx (the kernel
   forbids a child shadowing an ancestor's service, so a root row could never be
   overridden per agent). The tool's spawn path is exercised through
   `:operations` — the executor's injectable BashOperations — which receives the
   command *after* the prefix has been resolved, so a prefix is observable
   without spawning a shell."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.core :as core]
            [kmet.app.runtime :as app-runtime]
            [kmet.app.tools.bash :as bash]
            [kmet.app.tools.shell :as shell]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

(def ^:private sessions-dir (test-tmp/temp-dir! "test-shell-"))

(def ^:private booted (atom nil))

(defn- with-boot
  "A real `:tui` composition per test: the agents factory under test is the
   loop driver's, so a hand-built ctx would assert against the fixture."
  [f]
  (let [boot (app-runtime/boot (core/compose-config {:profile :tui}) {})]
    (reset! booted boot)
    (try
      (f)
      (finally
        (app-runtime/shutdown! boot)
        (reset! booted nil)))))

(t/use-fixtures :each with-boot)

(defn- config-for
  [prefix]
  (cond-> {:provider :opencode-go
           :model "claude-haiku-5-5"
           :session-dir sessions-dir}
    prefix (assoc :shell-command-prefix prefix)))

(defn- create-agent
  "Create an agent through the composition's registry with PREFIX as its shell
   command prefix, and return the handle."
  [prefix]
  (let [ctx (:ctx @booted)
        agents (srce/get ctx :agents)]
    ((:create! agents) ctx {:config (config-for prefix)})))

(defn- recording-operations
  "BashOperations that records the command it was handed and answers a fixed
   result, so the prefix resolution is observable without a spawn."
  [seen]
  (fn [{:keys [command]}]
    (swap! seen conj command)
    {:output "" :exit-code 0 :cancelled false :truncated false}))

(deftest an-agents-shell-comes-from-its-config
  (testing "the agents factory provides :shell on the agent's own ctx, from the
            config that agent was created with"
    (let [handle (create-agent "AGENT=one")
          mounted (srce/get (:ctx handle) :shell)]
      (is (some? mounted) "every agent mounts :shell")
      (is (= "AGENT=one" (:command-prefix @mounted))
          "its config's prefix, not a process default")
      (is (= (cfg/get-shell-path (cfg/load-config)) (:shell-path @mounted))
          "and the config's path, with the same ~ expansion"))))

(deftest two-agents-run-two-shells
  (testing "the multi-session property: two agents of one process, two shells"
    (let [one (create-agent "ONE=1")
          two (create-agent "TWO=2")
          seen (atom [])
          tool (bash/create-tool {:operations (recording-operations seen)})]
      (is (not= (:command-prefix @(srce/get (:ctx one) :shell))
                (:command-prefix @(srce/get (:ctx two) :shell)))
          "each agent's ctx answers its own value")
      (doseq [handle [one two]]
        (binding [bash/*shell* (some-> (:ctx handle) (srce/get :shell) deref)]
          ((:execute tool) {:command "echo hi"})))
      (is (= ["ONE=1\necho hi" "TWO=2\necho hi"] @seen)
          "and the bash tool carries the shell of the agent its run belongs to"))))

(deftest a-tool-option-beats-the-run-value
  (testing "an explicit :command-prefix at creation wins (pi: BashToolOptions
            beat the settings), which is how an extension's own tool says what
            it wants"
    (let [seen (atom [])
          tool (bash/create-tool {:operations (recording-operations seen)
                                  :command-prefix "EXPLICIT=1"})]
      (binding [bash/*shell* {:command-prefix "RUN=1"}]
        ((:execute tool) {:command "echo hi"}))
      (is (= ["EXPLICIT=1\necho hi"] @seen)))))

(deftest refresh-re-seats-the-mounted-value
  (testing "/reload replaces the config, so the agent's value is re-seated from
            it rather than only changing when the agent is rebuilt"
    (let [handle (create-agent "BEFORE=1")
          ctx (:ctx handle)
          before @(srce/get ctx :shell)]
      (shell/refresh! ctx (assoc (cfg/load-config) :shell-command-prefix "AFTER=2"))
      (is (= "AFTER=2" (:command-prefix @(srce/get ctx :shell))))
      (is (not= (:command-prefix before) (:command-prefix @(srce/get ctx :shell)))))))

(deftest a-context-without-the-service-is-left-alone
  (testing "a bare ctx is not an error for the reload path"
    (let [bare (srce/root {:name :bare})]
      (is (nil? (shell/refresh! bare {})))
      (is (nil? (srce/get bare :shell))))))

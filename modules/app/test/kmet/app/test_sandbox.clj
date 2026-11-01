(ns kmet.app.test-sandbox
  "Landing 9c's gate: `run_code`'s tool bridge executes in an isolated realm.

   The realm is the agent's `:sandbox` value applied to the run's ctx, and it
   exists for the inner-call path only: the loop hands every tool call its own
   run's ctx (`:srce-ctx`), and a script's calls get a child that blocks the
   value's names, so a tool a script invokes cannot read the composition's
   `:tools`, `:shell` or `:workspace` from the ctx it was handed. What a script
   *does* reach — its surface, the run's shell, cwd and skills — travels in the
   invocation pipeline's bindings, which is what those bindings are for."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.ai.llm :as llm]
            [kmet.app.config :as cfg]
            [kmet.app.core :as core]
            [kmet.app.loop :as loop]
            [kmet.app.loop.test-support :as support]
            [kmet.app.runtime :as app-runtime]
            [kmet.app.sandbox :as sandbox]
            [kmet.app.tools.registry :as tools]
            [kmet.libs.json :as json]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

;; ─── The value and the realm ──────────────────────────────────────────────

(deftest a-value-normalizes-a-rows-config
  (is (= sandbox/default-isolate (:isolate (sandbox/value nil)))
      "no config is the default boundary")
  (is (= sandbox/default-isolate (:isolate (sandbox/value {})))
      "and so is a config that does not name one")
  (is (= #{:workspace} (:isolate (sandbox/value {:isolate [:workspace]})))
      "a row's collection becomes the set the realm is built from")
  (is (= #{} (:isolate (sandbox/value {:isolate []})))
      "an explicit empty boundary is a composition saying so, not an omission"))

(deftest a-realm-blocks-what-it-names
  (let [root (srce/root {:name :app})]
    (srce/provide! root :tools :host-table)
    (srce/provide! root :shell :host-shell)
    (testing "outside the realm the services resolve, through a plain child too"
      (is (= :host-table (srce/get root :tools)))
      (is (= :host-table (srce/get (srce/extend root {}) :tools))))
    (testing "the realm stops the walk for its own names only"
      (let [realm (sandbox/realm root (sandbox/value {:isolate [:tools]}))]
        (is (nil? (srce/get realm :tools)))
        (is (= :host-shell (srce/get realm :shell))
            "a name the value does not isolate still inherits")))
    (testing "a value that isolates nothing is the ctx itself"
      (is (identical? root (sandbox/realm root (sandbox/value {:isolate []})))))))

(deftest the-row-provides-where-its-mount-was-aimed
  ;; A preset mounts the row on the agent's child ctx; the value has to land in
  ;; the child's store (the mount's parent), or the agent's own lookups — which
  ;; walk up, never down — would never see it.
  (let [root (srce/root {:name :app})
        ;; `:agent/1` is not a keyword the JVM reader accepts — the loader
        ;; rejects it before the test can run
        agent (srce/extend root {:name :agent-1})]
    (srce/plugin! agent sandbox/plugin {:isolate [:workspace]})
    (srce/settle! agent)
    (is (= #{:workspace} (:isolate (srce/get agent :sandbox)))
        "the agent resolves the row's value")
    (is (nil? (srce/get root :sandbox))
        "and the composition does not: the boundary is the agent's")))

;; ─── The loop's tool context ──────────────────────────────────────────────

(defn- resolved
  "Does CTX provide NAME? A nil ctx resolves nothing (the tool context is
   absent when a caller runs a tool outside a run)."
  [ctx name]
  (boolean (and ctx (srce/get ctx name))))

(defn- probe
  "A `:contextual?` tool that records what the ctx it was handed resolves."
  [seen]
  {:name "sandbox-probe"
   :label "Sandbox probe"
   :description "Reports the services its ctx resolves"
   :contextual? true
   :execute (fn [_args _on-update _signal ctx]
              (let [c (:srce-ctx ctx)]
                (reset! seen {:tools (resolved c :tools)
                              :shell (resolved c :shell)
                              :workspace (resolved c :workspace)
                              :ctx? (some? c)}))
              {:content "probed"})})

(defn- stub-tool-call
  "send-message stub: the first call asks for TOOL with ARGS, every later call
   answers text."
  [tool args]
  (let [n (atom 0)]
    (fn [opts]
      (future
        (if (= 1 (swap! n inc))
          (do (when-let [on-tc (:on-tool-call opts)]
                (on-tc {:id "tc1" :name tool :arguments args :index 0}))
              (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
          (do (when-let [on-text (:on-text opts)] (on-text "ok"))
              (when-let [on-done (:on-done opts)] (on-done :stop))))
        :done))))

(defn- turn!
  "Run one loop turn on the test ctx whose model asks for TOOL, and return the
   captured tool context's report."
  [tool args]
  (let [seen (atom nil)
        agent (support/make-agent-state)]
    (tools/register-tool! (support/tool-table) (probe seen))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (stub-tool-call tool args)]
        @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])}))
      @seen
      (finally
        (tools/unregister-tool! (support/tool-table) "sandbox-probe")))))

(deftest the-loops-tool-calls-carry-the-runs-ctx
  ;; The loop is the only caller that knows which agent a call belongs to, so
  ;; it is the only place the run's ctx can enter the tool context. Without it
  ;; every tool would be ctx-less and the realm below would have nothing to
  ;; isolate from.
  (support/with-registry
    (fn []
      (srce/provide! (support/process-ctx) :shell (atom {}))
      (srce/provide! (support/process-ctx) :workspace :the-runs-workspace)
      (is (= {:tools true :shell true :workspace true :ctx? true}
             (turn! "sandbox-probe" "{}"))
          "a tool the loop calls resolves the agent's own services"))))

(deftest a-scripts-inner-call-runs-in-the-realm
  ;; The 9c gate: the same probe, called from inside a run_code script, sees a
  ;; realm — the host's table and seams do not resolve through the ctx the
  ;; bridge hands it, even though the run's tool context did.
  (support/with-registry
    (fn []
      (let [ctx (support/process-ctx)]
        (srce/provide! ctx :shell (atom {}))
        (srce/provide! ctx :workspace :the-runs-workspace)
        (let [children (count (srce/children ctx))]
          (is (= {:tools false :shell false :workspace false :ctx? true}
                 (turn! "run_code"
                        (json/generate-string
                         {:code "(emit (deref (tools/call \"sandbox-probe\" {})))"})))
              "a script's inner call cannot read the host's services")
          (is (= children (count (srce/children ctx)))
              "and the realm is detached when the script returns"))))))

(deftest a-value-that-isolates-nothing-leaves-the-run-visible
  ;; The row's value is the boundary: a composition that names an empty one
  ;; gets a script that sees its run's services, which is a decision the value
  ;; records rather than an accident.
  (support/with-registry
    (fn []
      (let [ctx (support/process-ctx)]
        (srce/provide! ctx :sandbox (sandbox/value {:isolate []}))
        (srce/provide! ctx :shell (atom {}))
        (srce/provide! ctx :workspace :the-runs-workspace)
        (is (= {:tools true :shell true :workspace true :ctx? true}
               (turn! "run_code"
                      (json/generate-string
                       {:code "(emit (deref (tools/call \"sandbox-probe\" {})))"})))
            "no boundary means the run's ctx, unreplaced")))))

;; ─── The value on an agent ────────────────────────────────────────────────

(defn- create-agent!
  "An agent of a real composition, created with SETUP as its `:setup`."
  [ctx setup]
  (let [agents (srce/get ctx :agents)]
    ((:create! agents) ctx
                       {:config {:provider :opencode-go
                                 :model "deepseek-v4-flash"
                                 :session-dir (test-tmp/temp-dir! "test-sandbox-sessions-")}
                        :setup setup})))

(deftest every-agent-carries-a-boundary-and-a-preset-can-narrow-it
  ;; The factory's default is the safe one — a composition that mounted nothing
  ;; still gives each script a realm — and a preset that provides the value
  ;; first wins, because a value is per agent and the kernel forbids shadowing
  ;; an ancestor's. Both halves are the placement rule 9a established for
  ;; :shell, applied to the boundary.
  (let [boot (app-runtime/boot (core/compose-config {:profile :tui}) {})
        ctx (:ctx boot)]
    (try
      (let [plain (create-agent! ctx nil)
            gated (create-agent! ctx
                                 (fn [child _]
                                   (srce/plugin! child sandbox/plugin
                                                 {:isolate [:workspace]})
                                   (srce/settle! child)))]
        (is (= sandbox/default-isolate
               (:isolate (srce/get (:ctx plain) :sandbox)))
            "an agent with no preset runs the default boundary")
        (is (= #{:workspace} (:isolate (srce/get (:ctx gated) :sandbox)))
            "a preset's value is the agent's, and the factory leaves it alone")
        (is (not (contains? (:isolate (srce/get (:ctx gated) :sandbox)) :shell))
            "the default's names are not merged in — one value per agent"))
      (finally
        (srce/dispose-subtree! ctx)
        (fs/delete-tree "target/test-sandbox-sessions")))))

(ns kmet.app.test-commands
  "Tests for kmet.app.commands — slash command registry, and the runtime
   builtins' registry-facing behavior."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.commands :as commands]
            [kmet.app.commands.builtins :as builtins]
            [kmet.app.registry :as registry]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]
            [kmet.app.test-support :as support]))
;; The commands service and the root ctx a builtin reads live on the
;; composition, so the namespace boots one (`support/with-composition`).
(t/use-fixtures :once support/with-composition)

;; The builtins' registry-facing tests fill the app tree's slash registry;
;; clear the process-global registrations before and after every test.
(t/use-fixtures :each tu/with-clean-app-registries)

(t/deftest register-and-find
  (commands/register-command!
   {:name "test" :description "Test command" :handler (fn [_ _] :ok)})
  (t/is (= "test" (:name (commands/find-command "test"))))
  (t/is (nil? (commands/find-command "missing"))))

(t/deftest register-replaces-by-name
  (commands/register-command!
   {:name "dup" :description "first" :handler (fn [_ _] :one)})
  (commands/register-command!
   {:name "dup" :description "second" :handler (fn [_ _] :two)})
  (t/is (= 1 (count (filter #(= (:name %) "dup") (commands/get-commands)))))
  (t/is (= "second" (:description (commands/find-command "dup")))))

(t/deftest unregister-removes
  (commands/register-command!
   {:name "gone" :description "x" :handler (fn [_ _] nil)})
  (commands/unregister-command!  "gone")
  (t/is (nil? (commands/find-command "gone"))))

(t/deftest a-registration-lands-in-one-tag-only
  ;; Each tag routes to the service that owns it (4c-ii), so a command cannot
  ;; sit in the tool registry the way a `:tools/hooks` key once sat in the
  ;; shared store: the interference the store guarded against by key shape is
  ;; unrepresentable now, and clearing one tag leaves the others alone.
  (let [name "only-command"]
    (try
      (commands/register-command!
       {:name name :description "x" :handler (fn [_ _] nil)})
      (t/is (= [name] (mapv :name (commands/get-commands))))
      (t/is (nil? (registry/entry (registry/current) :kmet/tool name))
            "the command is not a tool")
      (commands/clear-commands!)
      (t/is (empty? (commands/get-commands)))
      (finally (commands/unregister-command! name)))))

(t/deftest an-earlier-owners-command-owns-the-name
  ;; Owners are searched in first-registration order, so an extension loaded
  ;; before the builtins keeps a name it took — the rule register-builtin!
  ;; skips on. unregister-command! and clear-commands! reach every owner.
  (let [extension-owner [:app :extensions "demo"]
        demo {:name "taken" :description "extension" :handler (fn [_ _] nil)}]
    ;; start the owner order from here: first-registration order is what the
    ;; rule reads, and an earlier test may have touched the app's owner. The
    ;; extension registers through the same call it uses in production.
    (commands/clear-commands!)
    (commands/register-command!  demo extension-owner)
    (try
      (commands/register-command!
       {:name "taken" :description "builtin" :handler (fn [_ _] nil)})
      (t/is (= "extension" (:description (commands/find-command "taken"))))
      (t/is (= ["taken"] (mapv :name (commands/get-commands)))
            "one entry per name, not one per owner")
      (commands/unregister-command!  "taken")
      (t/is (nil? (commands/find-command "taken")) "every owner's entry went")
      (finally
        (commands/clear-commands!)))))

(t/deftest the-registry-lives-in-the-app-ctx
  ;; The registry is the app root ctx's `:commands` service, not an engine
  ;; field: a registration is readable through the service handle, and
  ;; clear-commands! removes it by tag.
  (commands/register-command!
   {:name "stored" :description "x" :handler (fn [_ _] nil)})
  (t/is (identical? (:handler (commands/find-command "stored"))
                    (:handler (registry/entry (srce/get (support/composition-ctx) :commands)
                                              :kmet/command "stored"))))
  (t/is (= 1 (count (commands/get-commands))))
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands)))
  (t/is (empty? (registry/entries (srce/get (support/composition-ctx) :commands)
                                  :kmet/command))))

(t/deftest get-commands-keeps-registration-order
  ;; The palette and :get-commands list in registration order, and replacing a
  ;; command moves it to the end — the vector registry's order, kept by the
  ;; store's per-owner order.
  (commands/register-command!  {:name "beta" :description "x" :handler (fn [_ _] nil)})
  (commands/register-command!  {:name "alpha" :description "x" :handler (fn [_ _] nil)})
  (t/is (= ["beta" "alpha"] (mapv :name (commands/get-commands))))
  (commands/register-command!  {:name "beta" :description "again" :handler (fn [_ _] nil)})
  (t/is (= ["alpha" "beta"] (mapv :name (commands/get-commands)))))

(t/deftest handler-receives-ctx-and-args
  (let [received (atom nil)]
    (commands/register-command!
     {:name "echo"
      :description "echo args"
      :handler (fn [ctx args] (reset! received [ctx args]))})
    (let [ctx {:runtime :engine :ui :ui :frontend :interactive}]
      ((:handler (commands/find-command "echo")) ctx "hello"))
    (t/is (= [{:runtime :engine :ui :ui :frontend :interactive} "hello"] @received))))

(t/deftest units-renders-the-unit-tree
  ;; `/units` renders the ctx tree (`kmet.app.inspect`): every mounted row is a
  ;; fiber line with its state, what it provides and its effect labels — the
  ;; store's owner walk and the per-registry `keys=[…]` lines are gone (4d).
  (builtins/register-runtime-commands! support/composition-ctx)
  (let [got (atom nil)]
    ((:handler (commands/find-command "units"))
     {:ui {:info (fn [label content] (reset! got [label content]))}} nil)
    (t/is (= "Units" (first @got)))
    (t/is (every? #(not (str/starts-with? % " "))
                  (str/split-lines (second @got)))
          "one flat line per unit, no indentation")
    (t/is (str/starts-with? (second @got) "[:app]")
          "the app root is the first line")
    (t/is (str/includes? (second @got) ":active") "a mounted row is active")
    (t/is (str/includes? (second @got) ":commands")
          "the registry rows are mounted units")
    (t/is (str/includes? (second @got) ":sessionPersistence")
          "a service name prints bare in inject=")))

(t/deftest units-renders-the-process-tree-over-the-agent-s-own-ctx
  ;; The command ctx carries the run's engine, whose agent runs on a child ctx
  ;; (`kmet.app.agents`, 4c-iii-b). The mounted rows this command exists to
  ;; show hang under the process root; an agent's ctx holds only what a preset
  ;; composed on it, so rendering that subtree answered with one line and no
  ;; units. The root wins; the agent line is a unit in its tree.
  (let [root (support/composition-ctx)
        _ (builtins/register-runtime-commands! (constantly root))
        agent-ctx (srce/extend root {:name :agent/probe})
        engine {:agent-state (atom {:ctx agent-ctx})}
        got (atom nil)]
    (try
      ((:handler (commands/find-command "units"))
       {:runtime engine
        :ui {:info (fn [label content] (reset! got [label content]))}} nil)
      (t/is (= "Units" (first @got)))
      (t/is (str/starts-with? (second @got) "[:app]")
            "the process root is the first line, not the agent's ctx")
      (t/is (str/includes? (second @got) "[:app :agent/probe]")
            "the agent is a unit in the process tree")
      (t/is (str/includes? (second @got) ":commands")
            "the app's mounted rows are there, which the agent subtree lacks")
      (finally
        (srce/detach! agent-ctx)))))

(t/deftest units-falls-back-to-the-agent-ctx-with-no-root-behind-it
  ;; A bare engine (a test, an embedded run) has no process root: its own agent
  ;; ctx is then the most specific tree the command can answer.
  (let [root (support/composition-ctx)
        _ (builtins/register-runtime-commands! (constantly nil))
        agent-ctx (srce/extend root {:name :agent/probe})
        engine {:agent-state (atom {:ctx agent-ctx})}
        got (atom nil)]
    (try
      ((:handler (commands/find-command "units"))
       {:runtime engine
        :ui {:info (fn [_ content] (reset! got content))}} nil)
      (t/is (str/starts-with? @got "[:app :agent/probe]")
            "the render is scoped to the agent's ctx")
      (t/is (not (str/includes? @got ":commands"))
            "the app's rows are above it, not in it")
      (finally
        (srce/detach! agent-ctx)))))

(t/deftest units-shows-a-pending-fiber-and-what-it-waits-for
  ;; The question the store could only answer as absence: a row that is not
  ;; active says so, and says what it is waiting for.
  (builtins/register-runtime-commands! support/composition-ctx)
  (let [ctx (support/composition-ctx)
        probe (srce/plugin! ctx {:name :probe/unit
                                 :inject [:nothing-provides-this]
                                 :apply (fn [_ _] nil)})
        got (atom nil)]
    (try
      ((:handler (commands/find-command "units"))
       {:ui {:info (fn [_ content] (reset! got content))}} nil)
      (t/is (str/includes? @got "[:app :probe/unit]")
            "the invocation ctx's subtree, at its full path")
      (t/is (str/includes? @got ":pending"))
      (t/is (str/includes? @got "waiting-for=[nothing-provides-this]"))
      (t/is (str/includes? @got "inject=[nothing-provides-this]"))
      (finally
        (srce/dispose! probe)))))

(t/deftest register-builtin-skips-a-taken-name
  (commands/register-command!
   {:name "mine" :description "extension version" :handler (fn [_ _] nil)})
  (commands/register-builtin!  {:name "mine" :description "builtin" :handler (fn [_ _] nil)})
  (commands/register-builtin!  {:name "fresh" :description "builtin" :handler (fn [_ _] nil)})
  (t/is (= "extension version" (:description (commands/find-command "mine"))))
  (t/is (= "builtin" (:description (commands/find-command "fresh"))))
  (t/is (nil? (:frontend (commands/find-command "fresh")))
        "engine-portable builtins carry no front-end tag"))

(t/deftest clear-removes-all
  (commands/register-command!
   {:name "a" :description "a" :handler (fn [_ _] nil)})
  (commands/register-command!
   {:name "b" :description "b" :handler (fn [_ _] nil)})
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands))))

(t/deftest compact-args-parse-the-structured-cut
  (let [parse #'builtins/parse-compact-args]
    (t/is (= {:custom-instructions nil :keep-user-turns nil}
             (parse "")))
    (t/is (= {:custom-instructions "focus on tests" :keep-user-turns nil}
             (parse "focus on tests")))
    (t/is (= {:custom-instructions nil :keep-user-turns 2}
             (parse "keep:2")))
    (t/is (= {:custom-instructions "focus on tests" :keep-user-turns 0}
             (parse "keep:0 focus on tests")))
    (t/is (= {:custom-instructions "do it" :keep-user-turns 3}
             (parse "KEEP:3 do it")))
    (t/is (contains? (parse "keep:x") :error)
          "a malformed keep: prefix is not passed as prose")
    (t/is (contains? (parse "keep:") :error))))

(t/deftest compact-rejects-a-malformed-keep-prefix
  (let [warnings (atom [])]
    ((var builtins/handle-compact)
     {:ui {:warning #(swap! warnings conj %)}
      :runtime {:request-compaction! (fn [& _]
                                       (throw (ex-info "must not request" {})))}}
     "keep:x")
    (t/is (= 1 (count @warnings)))
    (t/is (str/includes? (first @warnings) "Usage: /compact"))))

(t/deftest compact-leaves-failure-display-to-the-event
  (let [seen (atom nil)
        warnings (atom [])]
    ((var builtins/handle-compact)
     {:ui {:warning #(swap! warnings conj %)}
      :runtime {:request-compaction! (fn [_ opts]
                                       (reset! seen opts)
                                       :started)}}
     "keep:5")
    (t/is (nil? (:on-error @seen))
          "the command relies on :compaction-end for failure display")
    (t/is (fn? (:on-complete @seen)))
    (t/is (empty? @warnings))))

(t/deftest compact-reports-a-broken-request-capability
  (let [warnings (atom [])]
    ((var builtins/handle-compact)
     {:ui {:warning #(swap! warnings conj %)}
      :runtime {:request-compaction! 5}}
     "keep:5")
    (t/is (= 1 (count @warnings)))
    (t/is (re-find #"IFn|Long" (first @warnings))
          "a non-function request capability is reported, not thrown")))

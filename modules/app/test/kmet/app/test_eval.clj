(ns kmet.app.test-eval
  "The `:eval` service and tool: one persistent environment per session,
   definitions written to the cwd or global library and reloaded, isolation
   between agents, the sticky namespace, and disposal."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval.definitions :as eval-defs]
            [kmet.app.loop.tool-exec :as tool-exec]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.eval :as eval-tool]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.script :as script]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(t/deftest test-a-definition-survives-a-later-call
  (let [env (support/eval-ctx)
        result (support/eval! env "(def kept \"Kept.\" 42) kept")]
    (t/is (str/starts-with? (:content result) "42"))
    (t/is (str/includes? (:content result) "[eval: defined user/kept"))
    (t/is (= "43" (:content (support/eval! env "(+ kept 1)"))))))

(t/deftest test-two-services-are-two-agents
  (let [a (support/eval-ctx)
        b (support/eval-ctx)]
    (t/is (str/starts-with? (:content (support/eval! a "(def only-a \"Only A.\" 1) only-a"))
                            "1"))
    (t/is (str/includes? (:content (support/eval! a "only-a")) "1"))
    (t/is (= "1" (:content (support/eval! a "only-a"))))
    (t/is (= "false"
             (:content (support/eval! b "(emit (some? (resolve 'only-a)))")))
          "a definition from another agent's environment is invisible")))

(t/deftest test-a-new-session-loads-the-shared-library
  (let [env (support/eval-ctx)
        other (session/create-session (test-tmp/temp-dir! "eval-other-")
                                      {:cwd (:cwd env)})]
    (t/is (str/starts-with? (:content (support/eval! env "(def session-only \"Session.\" 1) session-only"))
                            "1"))
    (t/is (= "2"
             (support/script-content
              ((:eval! (:service env))
               {:code "(+ session-only 1)"
                :session other
                :cwd (:cwd env)
                :limits (script/resolve-limits nil)})))
          "a new session builds its environment from the library roots")))

(t/deftest test-a-definition-is-not-visible-to-run-code
  (let [env (support/eval-ctx)]
    (support/eval! env "(def library-only \"Library only.\" 1)")
    (let [r (support/call-tool env "run_code" {:code "(emit (some? (resolve 'library-only)))"})]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "false" (:content r))
            "run_code keeps its fresh-context contract"))))

(t/deftest test-the-eval-prompt-puts-one-call-first
  (t/is (str/includes? (:description eval-tool/definition)
                       "two eval calls never overlap"))
  (t/is (str/includes? (:prompt-snippet eval-tool/definition)
                       "One eval call at a time"))
  (t/is (str/includes? (first (:prompt-guidelines eval-tool/definition))
                       "Do not issue two eval calls")))

(t/deftest test-the-sticky-namespace-is-re-entered
  (let [env (support/eval-ctx)]
    (t/is (str/starts-with? (:content (support/eval! env "(ns demo) (def x \"X.\" 41) x"))
                            "41"))
    (t/is (= "42" (:content (support/eval! env "(+ x 1)")))
          "the next call starts in the namespace the previous call ended in")))

(t/deftest test-the-tool-uses-the-agents-service
  (let [env (support/eval-ctx)]
    (t/is (str/starts-with? (:content (support/call-tool env "eval"
                                                         {:code "(def via-tool \"Via tool.\" 9) via-tool"}))
                            "9"))
    (t/is (= "10" (:content (support/call-tool env "eval" {:code "(+ via-tool 1)"}))))))

(t/deftest test-an-eval-batch-runs-sequentially-when-it-defines
  ;; Regression: a later call can read a definition an earlier call made —
  ;; persisted or live-only — so a batch containing a definition must take the
  ;; sequential path. A definition-free call keeps the default parallel path.
  (t/is (nil? (:execution-mode eval-tool/definition))
        "eval keeps the loop's default execution mode")
  (t/is (= :eval (:serial-group eval-tool/definition))
        "eval calls queue in source order inside a parallel batch")
  (t/is (tool-exec/has-sequential-tool-call?
         [{:name "eval" :arguments {"code" "(defn f \"F.\" [] 1)"}}]
         {"eval" eval-tool/definition})
        "a documented verb definition makes the whole batch sequential")
  (t/is (tool-exec/has-sequential-tool-call?
         [{:name "eval" :arguments {"code" "(def live-only 1)"}}]
         {"eval" eval-tool/definition})
        "a live-only definition makes the whole batch sequential too")
  (t/is (tool-exec/has-sequential-tool-call?
         [{:name "eval" :arguments {"code" "(defn f [] 1)"}}]
         {"eval" eval-tool/definition})
        "an undocumented defn is live-only and still sequential")
  (t/is (not (tool-exec/has-sequential-tool-call?
              [{:name "eval" :arguments {"code" "(+ 1 2)"}}]
              {"eval" eval-tool/definition}))
        "a definition-free eval call stays parallel")
  (t/is (not (tool-exec/has-sequential-tool-call?
              [{:name "eval" :arguments {"code" "[def x 1]"}}]
              {"eval" eval-tool/definition}))
        "a top-level vector is data, not a definition")
  (t/is (not (tool-exec/has-sequential-tool-call?
              [{:name "eval" :arguments {"code" "[defn f \"F.\" [] 1]"}}]
              {"eval" eval-tool/definition}))
        "a vector spelling a documented definition is still data")
  (t/is (not (tool-exec/has-sequential-tool-call?
              [{:name "eval" :arguments {"code" "(defn broken"}}]
              {"eval" eval-tool/definition}))
        "unreadable code stays parallel and fails when the tool runs")
  (t/is (not (tool-exec/has-sequential-tool-call?
              [{:name "eval" :arguments {"code" "(def x 1)"}}]
              {}))
        "an undeclared call cannot change the batch mode"))

(t/deftest test-a-top-level-vector-is-not-a-definition
  (t/is (not (eval-defs/defines? "[def x 1]")))
  (t/is (not (eval-defs/defines? "[defn f \"F.\" [] 1]")))
  (t/is (empty? (:defs (eval-defs/parse "[def x 1]" "user"))))
  (t/is (= "user" (:ns (eval-defs/parse "[ns demo] (def x 1)" "user")))
        "a vector is not an ns form")
  (t/is (= "user"
           (:ns (first (:defs (eval-defs/parse "[ns demo] (def x 1)" "user")))))
        "a real definition after vector data keeps the caller's namespace")
  (t/is (nil? (:ns-form (eval-defs/split-ns "[ns demo] (def x 1)")))
        "a vector is not an ns form")
  (t/is (= "(ns demo)" (:ns-form (eval-defs/split-ns "(ns demo) (def x 1)")))))

(t/deftest test-the-tool-prompt-stands-alone
  (let [{:keys [description prompt-snippet prompt-guidelines]} eval-tool/definition
        text (str description "\n" prompt-snippet "\n"
                  (str/join "\n" prompt-guidelines))]
    (t/is (str/includes? text "SCI"))
    (t/is (str/includes? text "babashka"))
    (t/is (str/includes? text "fs"))
    (t/is (str/includes? text "top-level"))
    (t/is (str/includes? text "Eval creates verbs"))
    (t/is (str/includes? text "docstring"))
    (t/is (str/includes? text "live-only"))
    (t/is (str/includes? text "let/fn"))
    (t/is (str/includes? text "(eval/help)"))
    (t/is (str/includes? text "serialize"))
    (t/is (str/includes? text "(sandbox/spawn f)"))
    (t/is (not (str/includes? text "run_code"))
          "eval's prompt must stand alone when run_code is not selected")
    (t/is (not (str/includes? text "Jolt")))
    (t/is (not (str/includes? text "JVM")))))

(t/deftest test-the-tool-schema-exposes-both-scopes
  (t/is (= ["cwd" "global"]
           (get-in eval-tool/definition [:parameters :properties "scope" :enum]))))

(t/deftest test-the-tool-passes-scope-to-a-definition
  (let [env (support/eval-ctx)]
    (t/is (not (:is-error (support/call-tool env "eval"
                                             {:code "(defn g \"Global g.\" [] :g)"
                                              :scope "global"}))))
    (t/is (fs/exists? (support/library-file env :global "user" "g.clj")))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "g.clj"))))))

(t/deftest test-the-script-descriptions-share-the-sandbox-summary
  (let [env (support/eval-ctx)
        run-code (tools/get-tool (:tools env) "run_code")]
    (t/is (str/includes? (:description run-code) script/sandbox-summary))
    (t/is (str/includes? (:description eval-tool/definition)
                         script/sandbox-summary))))

(t/deftest test-the-tool-reports-a-missing-service
  (let [{:keys [tools]} (tu/tool-ctx)
        r (tools/execute-tool tools "eval" {:code "1"} {})]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) ":eval service"))))

(t/deftest test-the-tool-passes-the-output-budget
  (let [env (support/eval-ctx)
        r (support/call-tool env "eval"
                             {:code "(apply str (repeat 100 \"x\"))"
                              :max-output-bytes 20})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (= 20 (get-in r [:truncation :max-bytes])))))

(t/deftest ^:slow test-the-tool-passes-the-timeout
  (let [env (support/eval-ctx)
        r (support/call-tool env "eval"
                             {:code "(loop [] (recur))" :timeout 0.1})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 0.1 (get-in r [:details :timeout])))))

(t/deftest ^:slow test-a-parallel-call-waits-and-starts-its-clock-after-the-lock
  (let [env (support/eval-ctx)
        {:keys [tools service session cwd]} env
        started (promise)]
    (tools/register-tool! tools {:name "eval-test-marker"
                                 :description "Signals that the first call started"
                                 :parameters {:type "object" :properties {}}
                                 :execute (fn [_]
                                            (deliver started true)
                                            {:content "ok"})})
    (try
      (let [first-call (future
                         ((:eval! service)
                          {:code (str "(deref (tools/call \"eval-test-marker\" {}))"
                                      " (sandbox/sleep 300) :first")
                           :session session :cwd cwd
                           :limits (script/resolve-limits nil)}))
            _ (t/is (deref started 2000 false))
            second-call ((:eval! service)
                         {:code ":second" :session session :cwd cwd
                          :timeout 0.1 :limits (script/resolve-limits nil)})]
        (t/is (not (:is-error second-call)) (:content second-call))
        (t/is (= ":second" (:content second-call)))
        (t/is (< (get-in second-call [:details :elapsed-ms]) 200)
              "the clock starts after the lock, not while waiting for it")
        (t/is (= ":first" (:content @first-call))))
      (finally
        (tools/unregister-tool! tools "eval-test-marker")))))

(t/deftest test-dispose-stops-the-service
  (let [env (support/eval-ctx)
        service (:service env)]
    ((:dispose! service))
    (t/is (= [[] [] []]
             [((:library service)) ((:sources service)) ((:failures service))]))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                            (support/eval! env "1")))))

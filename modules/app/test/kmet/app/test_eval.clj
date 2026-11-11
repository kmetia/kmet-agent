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
            [kmet.libs.concurrent :as concurrent]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(t/deftest test-a-definition-survives-a-later-call
  (let [env (support/eval-ctx)
        result (support/eval! env "(def kept \"Kept.\" 42) kept")]
    (t/is (str/starts-with? (:content result) "42"))
    (t/is (str/includes? (:content result) "[eval: defined user/kept"))
    (t/is (= "43" (:content (support/eval! env "(+ kept 1)"))))))

(t/deftest test-a-session-only-definition-is-kept
  (let [env (support/eval-ctx)
        live (support/eval! env "(defn loose [] :ok)")]
    (t/is (str/includes? (:content live) "live-only"))
    (t/is (str/includes? (:content live) "(eval/keep 'user/loose"))
    (let [kept (support/eval! env "(eval/keep 'loose \"Loose doc.\")")]
      (t/is (not (:is-error kept)) (:content kept))
      (t/is (str/includes? (:content kept) "[eval: defined user/loose")))
    (t/is (fs/exists? (support/library-file env :cwd "user" "loose.clj")))
    (t/is (str/includes?
           (slurp (support/library-file env :cwd "user" "loose.clj"))
           "Loose doc."))
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'loose :doc)"))
                         "Loose doc."))
    (t/is (= ":ok" (:content (support/eval! env "(loose)"))))
    (t/is (= "true"
             (:content
              (support/eval! env
                             "(some #(= \"user/loose\" (:name %)) (eval/library))"))))))

(t/deftest test-a-defmethod-follows-its-saved-multimethod
  (let [env (support/eval-ctx)]
    (support/eval! env "(defmulti pick identity)")
    (support/eval! env "(defmethod pick :x [_] :x)")
    (let [kept-multi (support/eval! env "(eval/keep 'user/pick \"Pick.\")")
          kept-method (support/eval! env "(eval/keep \"user/pick :x\")")]
      (t/is (not (:is-error kept-multi)) (:content kept-multi))
      (t/is (not (:is-error kept-method)) (:content kept-method))
      (t/is (str/includes? (:content kept-method)
                           "[eval: defined user/pick :x")))
    (t/is (= ":x" (:content (support/eval! env "(pick :x)"))))
    (t/is (str/includes?
           (:content (support/eval! env "(eval/describe \"user/pick :x\")"))
           ":defmethod"))))

(t/deftest test-an-error-names-the-failing-form
  (let [env (support/eval-ctx)
        result (support/eval! env "(+ 1 2) (inc \"a\")")]
    (t/is (:is-error result))
    (t/is (str/includes? (:content result) "1 => 3"))
    (t/is (str/includes? (:content result) "ERROR in form 2 of 2"))
    (t/is (str/includes? (:content result) "form 1 evaluated and stands"))
    (t/is (str/includes? (:content result) "ClassCastException"))))

(t/deftest test-a-truncated-result-can-be-read
  (let [env (support/eval-ctx)
        result (support/eval! env "(apply str (repeat 20000 \"x\"))")]
    (t/is (str/includes? (:content result) "output truncated"))
    (t/is (str/includes? (:content result) "(eval/result 1 :offset 0)"))
    (t/is (str/includes?
           (:content (support/eval! env "(eval/result 1 :offset 0 :limit 20)"))
           "content"))
    (let [listed (:content (support/eval! env "(eval/result)"))]
      (t/is (str/includes? listed ":id 1"))
      (t/is (not (str/includes? listed ":id 2"))
            "result reads do not enter the ring"))
    (t/is (str/includes? (:content (support/eval! env "(eval/result :last)"))
                         "content"))))

(t/deftest test-help-explains-results
  (let [env (support/eval-ctx)
        text (:content (support/eval! env "(eval/help :results)"))]
    (t/is (str/includes? text "eval/result"))
    (t/is (str/includes? text ":last"))
    (t/is (str/includes? text "not stored"))))

(t/deftest test-two-services-are-two-agents
  (let [a (support/eval-ctx)
        b (support/eval-ctx)]
    (t/is (str/starts-with? (:content (support/eval! a "(def only-a \"Only A.\" 1) only-a"))
                            "1"))
    (t/is (str/includes? (:content (support/eval! a "only-a")) "1"))
    (t/is (= "1" (:content (support/eval! a "only-a"))))
    (t/is (= "false"
             (:content (support/eval! b "(core/emit (some? (resolve 'only-a)))")))
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

(t/deftest test-a-definition-is-not-visible-to-codemode
  (let [env (support/eval-ctx)]
    (support/eval! env "(def library-only \"Library only.\" 1)")
    (let [r (support/call-tool env "codemode" {:code "(core/emit (some? (resolve 'library-only)))"})]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "false" (:content r))
            "codemode keeps its fresh-context contract"))))

(t/deftest test-the-eval-prompt-puts-one-call-per-phase
  (t/is (str/includes? (:description eval-tool/definition)
                       "Calls serialize"))
  (t/is (str/includes? (:description eval-tool/definition)
                       "one call per phase"))
  (t/is (str/includes? (:prompt-snippet eval-tool/definition)
                       "one call per phase"))
  (t/is (str/includes? (:prompt-snippet eval-tool/definition)
                       "many forms per call"))
  (let [guidelines (str/join "\n" (:prompt-guidelines eval-tool/definition))]
    (t/is (str/includes? guidelines "reuse before you rebuild"))
    (t/is (str/includes? guidelines "one call per phase"))
    (t/is (str/includes? guidelines "read→edit→verify"))))

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

(t/deftest test-a-multi-form-call-reports-each-form-value
  (let [env (support/eval-ctx)]
    (t/is (= "1 => 3\n7"
             (:content (support/eval! env "(+ 1 2)\n(+ 3 4)"))))
    (t/is (= "3" (:content (support/eval! env "(+ 1 2)")))
          "a single form keeps the final-value rule")
    (t/is (= ":a" (:content (support/eval! env "(emit :a)\n(+ 1 2)")))
          "emit stays the curated channel")
    (let [r (support/eval! env "(defn f \"F.\" [] 1)\n(+ 1 2)")]
      (t/is (not (:is-error r)))
      (t/is (str/starts-with? (:content r) "3"))
      (t/is (str/includes? (:content r) "[eval: defined user/f")))
    (let [r (support/eval! env "(+ 1 2)\n(throw (ex-info \"boom\" {}))\n(+ 3 4)")]
      (t/is (:is-error r))
      (t/is (str/includes? (:content r) "1 => 3"))
      (t/is (str/includes? (:content r) "ERROR in form 2 of 3")))))

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
    (t/is (str/includes? text "Eval verbs"))
    (t/is (str/includes? text "docstring"))
    (t/is (str/includes? text "live-only"))
    (t/is (str/includes? text "Reuse definitions"))
    (t/is (str/includes? text "(eval/library) lists saved ones"))
    (t/is (str/includes? text "define what recurs"))
    (t/is (str/includes? text "let/fn"))
    (t/is (str/includes? text "(help)"))
    (t/is (str/includes? text "(describe 'name)"))
    (t/is (str/includes? text "serialize"))
    (t/is (str/includes? text "(spawn f)"))
    (t/is (str/includes? text "read, write, edit, bash"))
    (t/is (str/includes? text "core/<verb>"))
    (t/is (str/includes? text "no model tool calls"))
    (t/is (not (str/includes? text "tools/call")))
    (t/is (not (str/includes? text "tools/list")))
    (t/is (str/includes? text "Define in user"))
    (t/is (str/includes? text "Spend findings once"))
    (t/is (str/includes? text "one call per phase"))
    (t/is (str/includes? text "many forms per call"))
    (t/is (<= (count text) 1300) "the per-request prompt stays compact")
    (t/is (not (str/includes? text "codemode"))
          "eval's prompt must stand alone when codemode is not selected")
    (t/is (not (str/includes? text "Jolt")))
    (t/is (not (str/includes? text "JVM")))))

(t/deftest test-core-verbs-are-referred-unqualified
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (spit (str (fs/path cwd "note.txt")) "bare")
    (t/is (= ":ok" (:content (support/eval! env "(emit :ok)"))))
    (t/is (= "bare" (:content (support/eval! env "(read \"note.txt\")"))))
    (spit (str (fs/path cwd "lines.txt")) "one\ntwo\nthree\n")
    (t/is (str/includes? (:content (support/eval! env "(read \"lines.txt\" {:offset 2 :limit 1})"))
                         "two"))
    (support/eval! env "(write \"out.txt\" \"alpha\")")
    (support/eval! env "(edit \"out.txt\" \"alpha\" \"beta\")")
    (t/is (= "beta" (slurp (str (fs/path cwd "out.txt")))))
    (t/is (str/includes? (:content (support/eval! env "(bash \"printf bare-bash\")"))
                         "bare-bash"))
    (t/is (= "true" (:content (support/eval! env "(nil? (resolve 'sh))")))
          "sh is not part of the unqualified core surface")
    (t/is (= ":spawned"
             (:content (support/eval! env "@(spawn (fn [] (emit :spawned)))"))))))

(t/deftest test-a-nonzero-shell-exit-is-a-result
  (let [env (support/eval-ctx)
        result (support/eval! env "(bash \"printf bad; exit 3\")")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "bad"))
    (t/is (str/includes? (:content result) "Command exited with code 3"))))

(t/deftest test-a-timed-out-shell-command-is-an-error
  (let [env (support/eval-ctx)
        result (support/eval! env "(bash \"sleep 5\" {:timeout 0.1})")]
    (t/is (:is-error result) (:content result))
    (t/is (str/includes? (:content result) "timed out"))))

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

(t/deftest test-codemode-and-eval-prompts-are-distinct
  (let [env (support/eval-ctx)
        codemode (tools/get-tool (:tools env) "codemode")]
    (t/is (not= (:prompt-snippet codemode) (:prompt-snippet eval-tool/definition)))
    (t/is (not= (:description codemode) (:description eval-tool/definition)))
    (t/is (not (str/includes? (:description codemode) script/core-summary)))
    (t/is (not (str/includes? (:description eval-tool/definition) script/core-summary)))))

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

(t/deftest ^:slow test-cancel-ends-a-host-blocked-call-with-a-deadline
  ;; The eval tool shares the script layer's cancellation wait: Escape during
  ;; host-blocked code ends the call after the grace, not at the model's
  ;; timeout. The codemode test covers the same plumbing on the other tool.
  (let [env (support/eval-ctx)
        signal (atom false)
        started (concurrent/monotonic-ms)
        f (future (support/call-tool env "eval"
                                     {:code "(core/sleep 5000)" :timeout 10}
                                     {:signal signal}))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref f 7000 ::timeout)]
      (t/is (not= ::timeout r) "the call returns on Escape, not at the deadline")
      (t/is (< (- (concurrent/monotonic-ms) started) 4000)
            "abandoned after the grace, well before the 10s deadline")
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest ^:slow test-a-parallel-call-waits-and-starts-its-clock-after-the-lock
  (let [env (support/eval-ctx)
        {:keys [service session cwd]} env
        marker (str (fs/path cwd "started.txt"))
        first-call (future
                     ((:eval! service)
                      {:code "(spit \"started.txt\" \"1\") (core/sleep 300) :first"
                       :session session :cwd cwd
                       :limits (script/resolve-limits nil)}))
        _ (t/is (loop [n 0]
                  (cond (fs/exists? marker) true
                        (> n 200) false
                        :else (do (Thread/sleep 10) (recur (inc n))))))
        second-call ((:eval! service)
                     {:code ":second" :session session :cwd cwd
                      :timeout 0.1 :limits (script/resolve-limits nil)})]
    (t/is (not (:is-error second-call)) (:content second-call))
    (t/is (= ":second" (:content second-call)))
    (t/is (< (get-in second-call [:details :elapsed-ms]) 200)
          "the clock starts after the lock, not while waiting for it")
    (t/is (= ":first" (:content @first-call)))))

(t/deftest test-dispose-stops-the-service
  (let [env (support/eval-ctx)
        service (:service env)]
    ((:dispose! service))
    (t/is (= [[] [] []]
             [((:library service)) ((:sources service)) ((:failures service))]))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                            (support/eval! env "1")))))

(t/deftest test-session-recall-verb-shapes
  (let [env (support/eval-ctx)
        calls (atom [])
        stub (fn [params]
               (swap! calls conj params)
               "RECALL TEXT")]
    (t/is (= "RECALL TEXT"
             (:content (support/eval! env "(session-recall \"auth token\")"
                                      :session-recall stub))))
    (t/is (= "RECALL TEXT"
             (:content (support/eval! env "(core/session-recall {:query \"x\" :scope \"all\"})"
                                      :session-recall stub))))
    (t/is (= "RECALL TEXT"
             (:content (support/eval! env "(session-recall \"x\" :page 2)"
                                      :session-recall stub))))
    (t/is (= [{:query "auth token"}
              {:query "x" :scope "all"}
              {:query "x" :page 2}]
             @calls))))

(t/deftest test-session-recall-verb-uses-the-live-session
  (let [env (support/eval-ctx)
        sess (:session env)]
    (session/append-entry sess {:role :user
                                :content [{:type :text :text "auth token decision"}]})
    (session/append-entry sess {:role :assistant
                                :content [{:type :text :text "then later work"}]})
    (let [result (support/call-tool
                  env "eval" {:code "(session-recall {:range \"[0, 0]\"})"}
                  {:ctx {:srce-ctx (:ctx env)
                         :cwd (:cwd env)
                         :session-record sess}})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "auth token decision")))))

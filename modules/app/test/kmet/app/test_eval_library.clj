(ns kmet.app.test-eval-library
  "The eval library: every successful definition writes `<agent-dir>/eval`,
   sessions load it as a runtime cache, drops and `(eval/forget)` delete and
   unbind, and legacy project files or session entries are ignored."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval :as eval-service]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.script :as script]
            [kmet.test-tmp :as test-tmp]))

(defn- library-file [env & parts]
  (str (apply fs/path (:agent-dir env) "eval" parts)))

(t/deftest test-a-definition-writes-a-reviewable-file
  (let [env (support/eval-ctx)
        result (support/eval! env "(defn report [x] (str \"R\" x))")
        path (library-file env "user" "report.clj")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "[eval: defined user/report → "))
    (t/is (fs/exists? path))
    (t/is (str/includes? (slurp path) ";; kmet eval: written by the eval tool"))
    (t/is (str/includes? (slurp path) "(ns user)"))
    (t/is (str/includes? (slurp path) "(defn report [x] (str \"R\" x))"))
    (t/is (= [{:name "user/report" :ns "user" :kind :defn :file path}]
             (support/eval-library env)))
    (t/is (= [{:name "user/report" :ns "user" :kind :defn :file path}]
             (get-in result [:details :defined])))
    (t/is (str/includes? (:content (support/eval! env "(eval/library)"))
                         "user/report"))))

(t/deftest test-a-redefinition-overwrites-its-file
  (let [env (support/eval-ctx)
        path (library-file env "user" "f.clj")
        _ (support/eval! env "(defn f [] :one)")
        result (support/eval! env "(defn f [] :two)")
        content (slurp path)]
    (t/is (str/includes? (:content result) "[eval: defined user/f → "))
    (t/is (str/includes? content ":two"))
    (t/is (not (str/includes? content ":one")))
    (let [restarted (support/restart-service env)]
      (t/is (= ":two"
               (:content (support/eval-in! restarted (:session env)
                                           (:cwd env) "(f)")))
            "a restart loads the latest source"))))

(t/deftest test-a-pure-expression-writes-nothing
  (let [env (support/eval-ctx)]
    (t/is (= "3" (:content (support/eval! env "(+ 1 2)"))))
    (t/is (not (fs/exists? (library-file env)))
          "reading a missing library creates nothing")
    (t/is (= [] (support/eval-library env)))))

(t/deftest test-an-error-call-writes-nothing
  (let [env (support/eval-ctx)
        result (support/eval! env "(def x 1) (throw (ex-info \"boom\" {}))")]
    (t/is (:is-error result))
    (t/is (not (fs/exists? (library-file env "user" "x.clj")))
          "definitions are written only after a successful call")))

(t/deftest test-a-new-session-loads-the-library
  (let [env (support/eval-ctx)
        service (:service env)
        cwd (:cwd env)
        _ (support/eval! env "(defn shared [] :yes)")
        other (session/create-session (test-tmp/temp-dir! "eval-load-")
                                      {:cwd cwd})]
    (t/is (= ":yes"
             (:content (support/eval-in! service other cwd "(shared)")))
          "a session built after the write loads the library")))

(t/deftest test-an-already-built-session-does-not-refresh
  (let [env (support/eval-ctx)
        service (:service env)
        cwd (:cwd env)
        other (session/create-session (test-tmp/temp-dir! "eval-cache-")
                                      {:cwd cwd})]
    (support/eval-in! service other cwd "(emit 1)")
    (support/eval! env "(def later 7)")
    (t/is (= "false"
             (:content (support/eval-in! service other cwd
                                         "(emit (some? (resolve 'later)))")))
          "an environment is a runtime cache, not a live view of the library")
    (let [restarted (support/restart-service env)]
      (t/is (= "true"
               (:content (support/eval-in! restarted other cwd
                                           "(emit (some? (resolve 'later)))")))
            "a new process builds a new environment from the library"))))

(t/deftest test-a-restart-loads-the-library
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def x 41)")
        restarted (support/restart-service env)]
    (t/is (= "42"
             (:content (support/eval-in! restarted (:session env)
                                         (:cwd env) "(+ x 1)"))))))

(t/deftest test-drop-removes-the-file-and-unbinds-the-live-name
  (let [env (support/eval-ctx)
        path (library-file env "user" "doomed.clj")
        _ (support/eval! env "(defn doomed [] 1)")
        result (support/eval! env nil :drop ["user/doomed"])]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "[eval: forgot user/doomed]"))
    (t/is (not (fs/exists? path)))
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'doomed)))")))
          "the live var disappears in the same session")
    (t/is (= [] (support/eval-library env)))))

(t/deftest test-a-code-less-call-may-drop
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x 1)")
    (let [dropped (support/call-tool env "eval" {:drop ["user/x"]})]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/x]"))
      (t/is (not (fs/exists? path))))
    (let [empty-call (support/call-tool env "eval" {})]
      (t/is (:is-error empty-call))
      (t/is (str/includes? (:content empty-call) "No code or drop")))
    (let [unknown (support/call-tool env "eval" {:drop ["user/nope"]})]
      (t/is (:is-error unknown))
      (t/is (str/includes? (:content unknown) "no eval library definition")))))

(t/deftest test-drop-validation
  (let [env (support/eval-ctx)]
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no eval library definition"
                            (support/eval! env nil :drop ["user/nope"])))
    (support/eval! env "(def x 1)")
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"twice"
                            (support/eval! env nil :drop ["user/x" "user/x"])))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"defined and dropped"
                            (support/eval! env "(def x 2)" :drop ["user/x"])))
    (t/is (str/includes? (slurp (library-file env "user" "x.clj")) "(def x 1)")
          "the rejected call wrote nothing")))

(t/deftest test-eval-forget-deletes-and-unbinds
  (let [env (support/eval-ctx)
        path (library-file env "user" "f.clj")]
    (support/eval! env "(defn f [] 1)")
    (let [result (support/eval! env "(eval/forget 'user/f)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f]"))
      (t/is (not (fs/exists? path))))
    (support/eval! env "(defn f [] 2)")
    (support/eval! env "(eval/forget 'f)")
    (t/is (not (fs/exists? path)) "an unqualified name resolves in the sticky ns")
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'f)))"))))))

(t/deftest test-forget-of-an-unknown-name-errors
  (let [env (support/eval-ctx)
        result (support/eval! env "(eval/forget 'user/nope)")]
    (t/is (:is-error result))
    (t/is (str/includes? (:content result) "no eval library definition"))))

(t/deftest test-defmethod-addresses-are-distinct
  (let [env (support/eval-ctx)
        _ (support/eval! env (str "(defmulti area :shape)\n"
                                  "(defmethod area :rect [m] :rect)\n"
                                  "(defmethod area :circle [m] :circle)"))
        rect (library-file env "user" "area_rect.clj")
        circle (library-file env "user" "area_circle.clj")]
    (t/is (fs/exists? (library-file env "user" "area.clj")))
    (t/is (fs/exists? rect))
    (t/is (fs/exists? circle))
    (support/eval! env nil :drop ["user/area :rect"])
    (t/is (not (fs/exists? rect)))
    (t/is (fs/exists? circle))
    (t/is (= "true"
             (:content (support/eval! env "(emit (some? (resolve 'area)))")))
          "dropping one method leaves the multimethod live")
    (t/is (= ":circle" (:content (support/eval! env "(area {:shape :circle})")))
          "the other method stays live")
    (t/is (:is-error (support/eval! env "(area {:shape :rect})"))
          "the dropped method is gone")
    (let [restarted (support/restart-service env)]
      (t/is (= ":circle"
               (:content (support/eval-in! restarted (:session env) (:cwd env)
                                           "(area {:shape :circle})")))
            "the other method survives a reload"))))

(t/deftest test-a-broken-library-file-is-reported-and-skipped
  (let [env (support/eval-ctx)
        path (library-file env "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result (support/eval! env "(emit 1)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (= "1" (:content result)))
      (t/is (seq (get-in result [:details :failures])))
      (t/is (str/includes? (pr-str (get-in result [:details :failures])) path)))
    (let [next-result (support/eval! env "(emit 2)")]
      (t/is (= "2" (:content next-result)))
      (t/is (nil? (get-in next-result [:details :failures]))
            "a build failure is reported once, then cleared"))))

(t/deftest test-a-written-definition-uses-the-current-call-surface
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (spit (str (fs/path cwd "note.txt")) "library\n")
    (support/eval! env "(defn parse-json [s] (json/parse-string s))")
    (support/eval! env "(defn say [] (emit :hi))")
    (support/eval! env "(defn read-note [] (str/trim (slurp \"note.txt\")))")
    (support/eval! env (str "(defn echo-hi [] "
                            "(tools/text (deref (tools/call \"bash\" "
                            "{:command \"echo hi\"}))))"))
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (:content (support/eval-in! restarted (:session env) cwd
                                           "(get (parse-json \"{\\\"a\\\":1}\") \"a\")")))
            "the standard alias prelude is replayed")
      (t/is (= ":hi" (:content (support/eval-in! restarted (:session env) cwd "(say)"))))
      (t/is (= "library"
               (:content (support/eval-in! restarted (:session env) cwd "(read-note)")))
            "slurp resolves against the current call's cwd")
      (t/is (str/includes?
             (:content (support/eval-in! restarted (:session env) cwd "(echo-hi)"))
             "hi")
            "a library definition dispatches through the current call's tools"))))

(t/deftest test-a-written-definition-in-another-namespace-replays-aliases
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (support/eval! env "(ns demo) (def x 1)")
    (support/eval! env "(defn join [xs] (str/join \",\" xs))")
    (let [restarted (support/restart-service env)]
      (t/is (= "a,b"
               (:content (support/eval-in! restarted (:session env) cwd
                                           "(demo/join [\"a\" \"b\"])")))
            "the alias prelude is replayed in the definition's namespace")
      (let [other (support/eval-ctx {:cwd cwd
                                     :agent-dir (:agent-dir env)})]
        (t/is (= "c,d"
                 (:content (support/eval-in! (:service other) (:session other)
                                             cwd "(demo/join [\"c\" \"d\"])")))
              "another home reader loads the ns form before the aliases")))))

(t/deftest test-a-written-definition-compiles-with-a-disabled-tool
  (let [env (support/eval-ctx)]
    (support/eval! env (str "(defn echo-hi [] "
                            "(tools/text (deref (tools/bash {:command \"echo hi\"}))))"))
    (let [restarted (support/restart-service env)
          result (binding [script/*enabled-tools-fn* (constantly #{})]
                   (support/eval-in! restarted (:session env) (:cwd env)
                                     "(emit :ok)"))]
      (t/is (not (:is-error result)) (:content result))
      (t/is (nil? (get-in result [:details :failures]))
            "the rebuild surface keeps every registry tool, not only enabled ones"))))

(t/deftest test-a-namespace-cannot-escape-the-library-root
  (let [env (support/eval-ctx)
        safe (library-file env "_" "x.clj")
        escaped (str (fs/path (:agent-dir env) "x.clj"))]
    (support/eval! env "(ns ..) (def x 1)")
    (t/is (fs/exists? safe))
    (t/is (not (fs/exists? escaped)))))

(t/deftest test-project-files-and-session-entries-are-ignored
  (let [env (support/eval-ctx)
        session (:session env)
        cwd (:cwd env)
        project-file (str (fs/path cwd ".kmet" "eval" "user" "legacy.clj"))]
    (fs/create-dirs (fs/parent project-file))
    (spit project-file "(ns user)\n(def legacy 1)\n")
    (session/append-custom-entry!
     session :eval-definitions
     {:version 1
      :defs [{:name "user/entry-only" :ns "user" :kind :def
              :source "(def entry-only 2)"}]})
    (let [other (session/create-session (test-tmp/temp-dir! "eval-legacy-")
                                        {:cwd cwd})
          service (:service env)
          result (support/eval-in! service other cwd
                                   "(emit [(some? (resolve 'legacy)) (some? (resolve 'entry-only))])")]
      (t/is (= "[false false]" (:content result))
            "only the home library is loaded")
      (t/is (= [] (support/library-for service other))
            "only the home library is loaded"))))

(t/deftest test-a-write-failure-is-reported-and-leaves-the-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(def x 1)")
    (let [result (with-redefs [eval-files/write-definition!
                               (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                   (support/eval! env "(def x 2)"))]
      (t/is (:is-error result))
      (t/is (= :eval/write-failed (get-in result [:details :error])))
      (t/is (str/includes? (:content result) "[eval: define failed user/x:"))
      (t/is (seq (get-in result [:details :failures])))
      (t/is (= "2" (:content (support/eval! env "x")))
            "the live definition remains"))
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (:content (support/eval-in! restarted (:session env)
                                           (:cwd env) "x")))
            "the old file is authoritative after the failed write"))))

(t/deftest test-a-failed-write-can-still-be-forgotten
  (let [env (support/eval-ctx)
        result (with-redefs [eval-files/write-definition!
                             (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                 (support/eval! env "(def lost 1)"))]
    (t/is (:is-error result))
    (t/is (= "true"
             (:content (support/eval! env "(emit (some? (resolve 'lost)))")))
          "the live definition remains")
    (let [dropped (support/eval! env nil :drop ["user/lost"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/lost]")))
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'lost)))")))
          "a failed write is still forgettable")))

(t/deftest test-an-unqualified-drop-qualifies-the-sticky-ns
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x 1)")
    (let [dropped (support/eval! env nil :drop ["x"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/x]")))
    (t/is (not (fs/exists? path)))
    (t/is (= [] (support/eval-library env)))
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'x)))"))))))

(t/deftest test-a-definition-from-an-errored-call-can-be-forgotten
  (let [env (support/eval-ctx)
        result (support/eval! env "(def z 1) (throw (ex-info \"boom\" {}))")]
    (t/is (:is-error result))
    (t/is (= "true"
             (:content (support/eval! env "(emit (some? (resolve 'z)))")))
          "the definition evaluated before the throw is live")
    (let [dropped (support/eval! env nil :drop ["user/z"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/z]")))
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'z)))")))
          "an errored call's live definition is forgettable")))

(t/deftest test-a-definition-after-an-error-is-not-forgettable
  (let [env (support/eval-ctx)
        result (support/eval! env "(throw (ex-info \"boom\" {})) (def never 1)")]
    (t/is (:is-error result))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no eval library definition"
                            (support/eval! env nil :drop ["user/never"])))
    (support/eval! env "(throw (ex-info \"boom\" {})) (def map 1)")
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no eval library definition"
                            (support/eval! env nil :drop ["user/map"]))
          "an inherited clojure.core var is not mistaken for a definition")))

(t/deftest test-forget-uses-the-call-s-final-namespace
  (let [env (support/eval-ctx)
        path (library-file env "demo" "y.clj")]
    (eval-files/write-definition! (str (fs/path (:agent-dir env) "eval"))
                                  {:name "demo/y" :ns "demo" :kind :defn
                                   :source "(defn y [] 1)"})
    (let [result (support/eval! env "(ns demo) (eval/forget 'y)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot demo/y]")))
    (t/is (not (fs/exists? path)))))

(t/deftest test-a-partial-drop-reports-what-landed
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def a 1) (def b 2)")
        delete eval-files/delete-name!]
    (with-redefs [eval-files/delete-name!
                  (fn [root name]
                    (if (= "user/b" name)
                      (throw (ex-info "disk full" {:type :test}))
                      (delete root name)))]
      (let [result (support/eval! env "(emit :ok)" :drop ["user/a" "user/b"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: forgot user/a]"))
        (t/is (str/includes? (:content result) "[eval: failed: disk full]"))))))

(t/deftest test-an-unbind-failure-is-reported
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x 1)")
    (with-redefs [script/eval-source! (fn [& _] {:error "boom"})]
      (let [result (support/eval! env nil :drop ["user/x"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: forgot user/x]"))
        (t/is (str/includes? (:content result) "[eval: failed user/x: boom]"))
        (t/is (seq (get-in result [:details :failures])))))
    (t/is (not (fs/exists? path)))
    (t/is (= "true"
             (:content (support/eval! env "(emit (some? (resolve 'x)))")))
          "the live var remains when unbinding fails")))

(t/deftest test-a-code-less-drop-reports-partial-deletes
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def a 1) (def b 2)")
        delete eval-files/delete-name!]
    (with-redefs [eval-files/delete-name!
                  (fn [root name]
                    (if (= "user/b" name)
                      (throw (ex-info "disk full" {:type :test}))
                      (delete root name)))]
      (let [result (support/eval! env nil :drop ["user/a" "user/b"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: forgot user/a]"))
        (t/is (str/includes? (:content result) "[eval: failed user/b: disk full]"))
        (t/is (seq (get-in result [:details :failures])))))))

(t/deftest test-a-qualified-forget-of-a-definition-in-the-same-call-conflicts
  (let [env (support/eval-ctx)
        result (support/eval! env "(def x 1) (eval/forget 'user/x)")]
    (t/is (:is-error result))
    (t/is (str/includes? (:content result) "defined and dropped"))))

(t/deftest test-a-call-may-define-and-drop-independent-names
  (let [env (support/eval-ctx)
        a (library-file env "user" "a.clj")
        b (library-file env "user" "b.clj")]
    (support/eval! env "(def a 1)")
    (let [result (support/eval! env "(def b 2)" :drop ["user/a"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/a]"))
      (t/is (str/includes? (:content result) "[eval: defined user/b → "))
      (t/is (not (fs/exists? a)))
      (t/is (fs/exists? b)))))

(t/deftest ^:slow test-parallel-definitions-on-one-session-all-survive
  (let [env (support/eval-ctx)
        service (:service env)
        session (:session env)
        cwd (:cwd env)
        calls (mapv (fn [i]
                      (future
                        (support/eval-in! service session cwd
                                          (str "(sandbox/sleep 10) "
                                               "(def p" i " " i ") p" i))))
                    (range 8))]
    (doseq [call calls]
      (t/is (not (:is-error @call)) (:content @call)))
    (doseq [i (range 8)]
      (t/is (fs/exists? (library-file env "user" (str "p" i ".clj")))))
    (t/is (= "[true true true true true true true true]"
             (:content (support/eval-in! service session cwd
                                         (str "(emit (mapv #(some? (resolve %)) "
                                              "'[p0 p1 p2 p3 p4 p5 p6 p7]))"))))
          "all parallel definitions are live and written")))

(t/deftest ^:slow test-a-looping-library-file-is-interrupted
  (let [env (support/eval-ctx)
        path (library-file env "user" "spin.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path (eval-files/file-content
                {:ns "user" :source "(def spin (loop [] (recur)))"}))
    (with-redefs-fn {#'eval-service/build-timeout-ms 50}
      (fn []
        (let [result (support/eval! env "(emit :ok)")]
          (t/is (not (:is-error result)) (:content result))
          (t/is (seq (get-in result [:details :failures]))
                "a looping library definition is recorded and skipped"))))))

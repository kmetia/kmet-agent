(ns kmet.app.test-eval-persistence
  "Step 4 of the eval track: session scope, the lazy fold, shared `:keep`
   files, shadows, snapshots and the kept index."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.test-tmp :as test-tmp]))

(defn- project-file [env & parts]
  (str (apply fs/path (:cwd env) ".kmet" "eval" parts)))

(defn- global-file [env & parts]
  (str (apply fs/path (:agent-dir env) "eval" parts)))

(defn- kept-for [service session]
  (binding [extensions/*agent-context* {:session (constantly session)}]
    ((:kept service))))

(defn- entry-names [entry]
  (mapv :name (get-in entry [:data :defs])))

(t/deftest test-a-session-entry-is-appended-and-a-restart-folds-it
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(ns demo) (defn twice [x] (* x 2))")
        entries (support/definition-entries session)
        service (support/restart-service env)]
    (t/is (= 1 (count entries)))
    (t/is (= :eval-definitions (:custom-type (first entries))))
    (t/is (= ["demo/twice"] (entry-names (first entries))))
    (t/is (= "6"
             (:content (support/eval-in! service session (:cwd env)
                                         "(ns demo) (twice 3)")))
          "a new service over the same session restores its definitions")))

(t/deftest test-two-sessions-keep-separate-environments
  (let [env (support/eval-ctx)
        service (:service env)
        cwd (:cwd env)
        first (:session env)
        second (session/create-session (test-tmp/temp-dir! "eval-second-")
                                       {:cwd cwd})]
    (support/eval! env "(def x 1)")
    (t/is (= "false"
             (:content (support/eval-in! service second cwd
                                         "(emit (some? (resolve 'x)))")))
          "a new session starts empty")
    (support/eval-in! service second cwd "(def x 2)")
    (t/is (= "1" (:content (support/eval! env "x"))))
    (t/is (= "1" (:content (support/eval-in! service first cwd "x")))
          "switching back selects the first session's retained environment")))

(t/deftest test-a-branch-rebuilds-from-the-active-branch
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(defn f [] :one)")
        entry (first (support/definition-entries session))
        _ (session/reset-leaf! session)]
    (t/is (= "false"
             (:content (support/eval! env "(emit (some? (resolve 'f)))")))
          "a reset rebuilds without the definition on the abandoned branch")
    (session/branch! session (:id entry))
    (t/is (= ":one" (:content (support/eval! env "(f)")))
          "branching back rebuilds the definition")))

(t/deftest test-keep-writes-a-reviewable-file-that-another-session-loads
  (let [env (support/eval-ctx)
        session (:session env)
        cwd (:cwd env)
        _ (support/eval! env "(defn report [x] (str \"R\" x))")
        result (support/eval! env nil :keep [["user/report" "project"]])
        path (project-file env "user" "report.clj")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result)
                         "[eval: kept user/report → .kmet/eval/user/report.clj]"))
    (t/is (fs/exists? path))
    (t/is (str/includes? (slurp path) ";; kmet eval: kept by the eval tool"))
    (t/is (str/includes? (slurp path) "(ns user)"))
    (t/is (str/includes? (slurp path) "(defn report [x] (str \"R\" x))"))
    (t/is (= ["user/report"]
             (get-in (last (support/definition-entries session)) [:data :removed]))
          "a promoted session copy is removed from the fold")
    (let [second (session/create-session (test-tmp/temp-dir! "eval-shared-")
                                         {:cwd cwd})]
      (t/is (= "R2"
               (:content (support/eval-in! (:service env) second cwd "(report 2)")))
            "another session loads the project file")
      (t/is (= [{:name "user/report" :scope :project
                 :ns "user" :kind :defn :file path}]
               (kept-for (:service env) second))))))

(t/deftest test-a-shared-name-redefined-without-keep-is-a-session-shadow
  (let [env (support/eval-ctx)
        cwd (:cwd env)
        path (project-file env "user" "report.clj")
        _ (support/eval! env "(defn report [x] :saved)")
        _ (support/eval! env nil :keep [["user/report" "project"]])
        original (slurp path)
        second (session/create-session (test-tmp/temp-dir! "eval-shadow-")
                                       {:cwd cwd})
        result (support/eval-in! (:service env) second cwd
                                 "(defn report [x] :shadow)")]
    (t/is (str/includes? (:content result)
                         "[eval: shadowing user/report (session; file unchanged: .kmet/eval/user/report.clj)]"))
    (t/is (= original (slurp path)) "the shared file is untouched")
    (t/is (= ":shadow"
             (:content (support/eval-in! (:service env) second cwd "(report 1)")))
          "the session copy wins at load")
    (let [kept (kept-for (:service env) second)]
      (t/is (= [{:name "user/report" :scope :session :ns "user" :kind :defn
                 :shadowing {:scope :project :file path}}
                {:name "user/report" :scope :project :ns "user"
                 :kind :defn :file path}]
               kept)))
    (support/eval-in! (:service env) second cwd nil
                      :keep [["user/report" "project"]])
    (t/is (str/includes? (slurp path) ":shadow")
          "an explicit keep writes the session copy")
    (t/is (= []
             (get-in (last (support/definition-entries second)) [:data :defs]
                     [])))))

(t/deftest test-a-definition-moves-between-scopes-and-demotes
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(def x 1)")
        _ (support/eval! env nil :keep [["user/x" "global"]])
        global (global-file env "user" "x.clj")
        project (project-file env "user" "x.clj")]
    (t/is (fs/exists? global))
    (let [moved (support/eval! env nil :keep [["user/x" "project"]])]
      (t/is (not (:is-error moved)) (:content moved))
      (t/is (str/includes? (:content moved) "[eval: moved user/x "))
      (t/is (str/includes? (:content moved) " → .kmet/eval/user/x.clj]"))
      (t/is (not (fs/exists? global)))
      (t/is (fs/exists? project)))
    (let [demoted (support/eval! env nil :keep [["user/x" "session"]])]
      (t/is (not (:is-error demoted)) (:content demoted))
      (t/is (str/includes? (:content demoted) " → session]"))
      (t/is (not (fs/exists? project)))
      (t/is (= "1" (:content (support/eval! env "x"))))
      (t/is (= [{:name "user/x" :scope :session :ns "user" :kind :def}]
               (kept-for (:service env) session))))
    (let [dropped (support/eval! env nil :drop ["user/x"])
          service (support/restart-service env)]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: dropped user/x]"))
      (t/is (not (fs/exists? project)))
      (t/is (= "false"
               (:content (support/eval-in! service session (:cwd env)
                                           "(emit (some? (resolve 'x)))")))
            "a drop leaves nothing for a rebuild to restore"))))

(t/deftest test-a-call-may-define-and-keep-in-one-step
  (let [env (support/eval-ctx)
        path (project-file env "user" "report.clj")
        result (support/eval! env "(defn report [] :new)"
                              :keep [["user/report" "project"]])]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (slurp path) "(defn report [] :new)"))
    (t/is (empty? (get-in (last (support/definition-entries (:session env)))
                          [:data :defs]))
          "a kept definition is not also a session copy")))

(t/deftest test-keep-and-drop-validation
  (let [env (support/eval-ctx)]
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no tracked eval definition"
                            (support/eval! env nil :keep [["user/nope" "project"]])))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no tracked eval definition"
                            (support/eval! env nil :drop ["user/nope"])))
    (support/eval! env "(def x 1)")
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"both kept and dropped"
                            (support/eval! env "(def x 2)"
                                           :keep [["user/x" "project"]]
                                           :drop ["user/x"])))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"scope must be"
                            (support/eval! env nil :keep [["user/x" "elsewhere"]])))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"\[name scope\] pairs"
                            (support/eval! env nil :keep ["user/x"])))))

(t/deftest test-defmethod-addresses-are-distinct
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env (str "(defmulti area :shape)\n"
                                  "(defmethod area :rect [m] :rect)\n"
                                  "(defmethod area :circle [m] :circle)"))
        names (mapcat entry-names (support/definition-entries session))
        _ (support/eval! env nil :keep [["user/area" "project"]
                                        ["user/area :rect" "project"]
                                        ["user/area :circle" "project"]])
        service (support/restart-service env)]
    (t/is (some #{"user/area :rect"} names))
    (t/is (some #{"user/area :circle"} names))
    (t/is (fs/exists? (project-file env "user" "area_rect.clj")))
    (t/is (fs/exists? (project-file env "user" "area_circle.clj")))
    (t/is (= ":rect"
             (:content (support/eval-in! service session (:cwd env)
                                         "(area {:shape :rect})")))
          "both methods survive a reload")))

(t/deftest test-a-broken-shared-file-is-reported-and-skipped
  (let [env (support/eval-ctx)
        path (project-file env "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result (support/eval! env "(emit 1)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (= "1" (:content result)))
      (t/is (seq (get-in result [:details :failures])))
      (t/is (str/includes? (pr-str (get-in result [:details :failures])) path)))))

(t/deftest test-a-snapshot-bounds-the-fold
  (let [env (support/eval-ctx)
        session (:session env)
        service (:service env)
        _ (support/eval! env "(def a 1) (def b 2)")
        snapshot ((:snapshot! service) session)]
    (t/is (true? (get-in snapshot [:data :snapshot])))
    (t/is (= #{"user/a" "user/b"} (set (entry-names snapshot))))
    (support/eval! env nil :drop ["user/a"])
    (let [restarted (support/restart-service env)]
      (t/is (= "2" (:content (support/eval-in! restarted session (:cwd env) "b"))))
      (t/is (= "false"
               (:content (support/eval-in! restarted session (:cwd env)
                                           "(emit (some? (resolve 'a)))")))
            "the snapshot resets to its defs and the later removal still applies"))))

(t/deftest test-a-malformed-definition-entry-is-reported
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(def x 1)")
        _ (session/append-custom-entry! session defs/eval-definitions-type
                                        {:version 1 :defs :not-a-vector})
        service (support/restart-service env)
        result (support/eval-in! service session (:cwd env) "x")]
    (t/is (= "1" (:content result)) "the valid definition still loads")
    (t/is (seq (get-in result [:details :failures])))))

(t/deftest test-kept-lists-session-and-shared-definitions
  (let [env (support/eval-ctx)
        _ (support/eval! env "(defn report [] 1)")
        kept (support/eval! env "(eval/kept)")
        path (project-file env "user" "report.clj")]
    (t/is (str/includes? (:content kept) "user/report"))
    (t/is (str/includes? (:content kept) ":session"))
    (support/eval! env nil :keep [["user/report" "project"]])
    (let [kept (support/eval! env "(eval/kept)")]
      (t/is (str/includes? (:content kept) ":project"))
      (t/is (str/includes? (:content kept) path)))))

(t/deftest test-fork-and-import-fold-the-source-definitions
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(def x 41)")
        leaf @(:leaf-id session)
        forked (session/fork-session session leaf)
        service (support/restart-service env)]
    (t/is (some? forked))
    (t/is (= "42" (:content (support/eval-in! service forked (:cwd env) "(+ x 1)")))
          "a fork copies the definition entries and folds them")))

(t/deftest test-a-pure-expression-and-a-no-op-keep-append-no-entry
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(def x 1)")
        before (count (support/definition-entries session))]
    (support/eval! env "(+ 1 2)")
    (t/is (= before (count (support/definition-entries session)))
          "a pure expression writes no definition entry")
    (support/eval! env nil :keep [["user/x" "session"]])
    (t/is (= before (count (support/definition-entries session)))
          "a no-op keep writes no definition entry")
    (support/eval! env "(def x 1)")
    (t/is (= before (count (support/definition-entries session)))
          "redefining the same session source writes no entry")))

(t/deftest test-a-shared-keep-is-load-time-for-an-already-built-session
  (let [env (support/eval-ctx)
        service (:service env)
        cwd (:cwd env)
        other (session/create-session (test-tmp/temp-dir! "eval-load-time-")
                                      {:cwd cwd})]
    (support/eval-in! service other cwd "(emit 1)")
    (support/eval! env "(def x 7)")
    (support/eval! env nil :keep [["user/x" "project"]])
    (t/is (= "false"
             (:content (support/eval-in! service other cwd
                                         "(emit (some? (resolve 'x)))")))
          "a keep by another session is not re-read by an already-built env")
    (let [restarted (support/restart-service env)]
      (t/is (= "true"
               (:content (support/eval-in! restarted other cwd
                                           "(emit (some? (resolve 'x)))")))
            "a rebuilt env loads the shared file"))))

(t/deftest test-definition-entries-never-project-into-the-context
  (let [env (support/eval-ctx)]
    (support/eval! env "(def x 1)")
    (t/is (empty? (session/build-context-messages (:session env)))
          "a custom definition entry projects no LLM message")
    (t/is (some #(= defs/eval-definitions-type (:custom-type %))
                (session/get-branch (:session env)))
          "the entry is in the append-only log, not the LLM projection")))

(t/deftest test-a-branch-to-before-a-snapshot-sees-the-old-entries
  (let [env (support/eval-ctx)
        session (:session env)
        _ (support/eval! env "(def a 1) (def b 2)")
        before @(:leaf-id session)
        _ ((:snapshot! (:service env)) session)
        _ (support/eval! env nil :drop ["user/a"])]
    (session/branch! session before)
    (t/is (= "1" (:content (support/eval! env "a")))
          "the branch before the snapshot folds the original entry")
    (t/is (= "2" (:content (support/eval! env "b"))))))

(t/deftest test-a-failed-snapshot-append-leaves-the-old-entries
  (let [env (support/eval-ctx)
        session (:session env)
        service (:service env)
        _ (support/eval! env "(def x 1)")]
    (with-redefs [session/append-custom-entry!
                  (fn [& _] (throw (ex-info "disk full" {:type :test})))]
      (t/is (nil? ((:snapshot! service) session))))
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (:content (support/eval-in! restarted session (:cwd env) "x")))
            "the old definition entries still load"))))

(t/deftest test-keep-session-stores-a-redefinition
  (let [env (support/eval-ctx)
        session (:session env)]
    (support/eval! env "(def x 1)")
    (support/eval! env "(def x 2)" :keep [["user/x" "session"]])
    (let [restarted (support/restart-service env)]
      (t/is (= "2"
               (:content (support/eval-in! restarted session (:cwd env) "x")))
            "a redefinition kept in session scope updates the stored source"))))

(t/deftest test-an-eval-does-not-create-eval-directories
  (let [env (support/eval-ctx)
        project (str (fs/path (:cwd env) ".kmet" "eval"))
        global (str (fs/path (:agent-dir env) "eval"))]
    (support/eval! env "(+ 1 2)")
    (t/is (not (fs/exists? project)) "reading a missing project root creates nothing")
    (t/is (not (fs/exists? global)) "reading a missing global root creates nothing")
    (support/eval! env "(def y 1)")
    (support/eval! env nil :drop ["user/y"])
    (t/is (not (fs/exists? project)) "a session-only drop creates nothing")
    (t/is (not (fs/exists? global)))))

(t/deftest test-a-namespace-cannot-escape-the-eval-root
  (let [env (support/eval-ctx)
        escaped (str (fs/path (:cwd env) ".kmet" "x.clj"))
        safe (project-file env "_" "x.clj")]
    (support/eval! env "(ns ..) (def x 1)")
    (support/eval! env nil :keep [["../x" "project"]])
    (t/is (fs/exists? safe))
    (t/is (not (fs/exists? escaped)))))

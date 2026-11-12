(ns kmet.app.test-eval-library
  "The eval library: definitions write the cwd scope by default (or the active
   scope on redefinition), sessions load both roots as a runtime cache that
   only `/eval reload` refreshes, drops and `(eval/forget)` delete and unbind,
   and legacy project files or session entries are ignored."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval :as eval-service]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.script :as script]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.nrepl :as nrepl]
            [kmet.test-tmp :as test-tmp]))

(defn- library-file [env & parts]
  (apply support/library-file env :cwd parts))

(t/deftest test-a-definition-writes-a-reviewable-file
  (let [env (support/eval-ctx)
        result (support/eval! env "(defn report \"Render R.\" [x] (str \"R\" x))")
        path (library-file env "user" "report.clj")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "[eval: defined user/report (cwd)]"))
    (t/is (fs/exists? path))
    (t/is (= path (:content (support/eval! env "(eval/describe 'report :file)"))))
    (t/is (str/includes? (slurp path) ";; kmet eval: written by the eval tool"))
    (t/is (str/includes? (slurp path) "(ns user)"))
    (t/is (str/includes? (slurp path) "(defn report \"Render R.\" [x] (str \"R\" x))"))
    (t/is (= [{:name "user/report" :ns "user" :kind :defn
               :doc "Render R." :arglists '([x]) :file path
               :scope :cwd :active true}]
             (support/eval-library env)))
    (t/is (= [{:name "user/report" :ns "user" :kind :defn
               :doc "Render R." :arglists '([x]) :file path
               :scope :cwd :active true}]
             (get-in result [:details :defined])))
    (t/is (str/includes? (:content (support/eval! env "(eval/library)"))
                         "user/report"))))

(t/deftest test-a-library-definition-can-use-the-built-in-user-verbs (let [env (support/eval-ctx) path (library-file env "user" "uses-read.clj")] (fs/create-dirs (fs/parent path)) (spit path "(ns user)\n(defn uses-read \"Read with the built-in.\" [path] (core/read path))\n") (spit (str (fs/path (:cwd env) "marker.txt")) "MARKER") (let [result (support/eval! env "(uses-read \"marker.txt\")")] (t/is (not (:is-error result)) (:content result)) (t/is (str/includes? (:content result) "MARKER")) (t/is (some (fn [entry] (= "user/uses-read" (:name entry))) (support/eval-library env)) "the library definition loaded"))))

(t/deftest test-user-file-verbs-do-not-depend-on-the-tool-surface (let [env (support/eval-ctx) marker (str (fs/path (:cwd env) "marker.txt"))] (spit marker "MARKER") (binding [script/*enabled-tools-fn* (constantly #{})] (t/is (= "MARKER" (:content (support/eval! env "(core/read \"marker.txt\")")))) (support/eval! env "(core/write \"out.txt\" \"alpha\")") (support/eval! env "(core/edit \"out.txt\" \"alpha\" \"beta\")") (t/is (= "beta" (slurp (str (fs/path (:cwd env) "out.txt"))))))))

(t/deftest test-user-bash-does-not-depend-on-the-tool-surface (let [env (support/eval-ctx)] (binding [script/*enabled-tools-fn* (constantly #{})] (let [result (support/eval! env "(core/bash \"printf hi\")")] (t/is (not (:is-error result)) (:content result)) (t/is (str/includes? (:content result) "hi"))))))

(t/deftest test-system-namespaces-are-refused-and-extension-namespaces-are-live-only (let [env (support/eval-ctx)] (let [result (support/call-tool env "eval" {:code "(ns core) (defn read \"Mine.\" [path] :mine)"})] (t/is (:is-error result)) (t/is (str/includes? (:content result) "system-owned"))) (let [result (support/call-tool env "eval" {:code "(ns clj) (defn edit-form \"Mine.\" [args] :mine)"})] (t/is (:is-error result)) (t/is (str/includes? (:content result) "system-owned"))) (let [result (support/eval! env "(ns ext.license) (defn custom \"Doc.\" [] :ok)")] (t/is (not (:is-error result)) (:content result)) (t/is (str/includes? (:content result) "extension; live-only")) (t/is (not (fs/exists? (support/library-file env :cwd "ext.license" "custom.clj")))))))

(t/deftest test-clj-structure-verbs-do-not-depend-on-the-tool-surface (let [env (support/eval-ctx) path (str (fs/path (:cwd env) "edit-me.clj"))] (spit path "(ns edit-me)\n\n(defn f [] 1)\n") (binding [script/*enabled-tools-fn* (constantly #{})] (let [result (support/eval! env (str "(:content (clj/edit-form {:file_path \"edit-me.clj\" " ":form_type \"defn\" :form_identifier \"f\" " ":operation \"replace\" " ":content \"(defn f [] 2)\"}))"))] (t/is (= "Edit applied." (:content result))) (t/is (str/includes? (slurp path) "(defn f [] 2)"))) (let [result (support/eval! env (str "(:content (clj/replace-sexp {:file_path \"edit-me.clj\" " ":match_form \"(defn f [] 2)\" " ":new_form \"(defn f [] 3)\" " ":operation \"replace\"}))"))] (t/is (= "Edit applied." (:content result))) (t/is (str/includes? (slurp path) "(defn f [] 3)"))) (spit path "(defn f []\n") (let [result (support/eval! env "(:content (clj/paren-repair {:file_path \"edit-me.clj\" :format false}))")] (t/is (not (:is-error result)) (:content result)) (t/is (str/includes? (:content result) "Fixed"))))))

(t/deftest test-a-redefinition-overwrites-its-file
  (let [env (support/eval-ctx)
        path (library-file env "user" "f.clj")
        _ (support/eval! env "(defn f \"One.\" [] :one)")
        result (support/eval! env "(defn f \"Two.\" [] :two)")
        content (slurp path)]
    (t/is (str/includes? (:content result) "[eval: defined user/f (cwd)]"))
    (t/is (str/includes? content ":two"))
    (t/is (not (str/includes? content ":one")))
    (let [restarted (support/restart-service env)]
      (t/is (= ":two"
               (support/script-content
                (support/eval-in! restarted (:session env)
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
        _ (support/eval! env "(defn shared \"Shared.\" [] :yes)")
        other (session/create-session (test-tmp/temp-dir! "eval-load-")
                                      {:cwd cwd})]
    (t/is (= ":yes"
             (support/script-content
              (support/eval-in! service other cwd "(shared)")))
          "a session built after the write loads the library")))

(t/deftest test-an-already-built-session-does-not-refresh
  (let [env (support/eval-ctx)
        service (:service env)
        cwd (:cwd env)
        other (session/create-session (test-tmp/temp-dir! "eval-cache-")
                                      {:cwd cwd})]
    (support/eval-in! service other cwd "(core/emit 1)")
    (support/eval! env "(def later \"Later.\" 7)")
    (t/is (= "false"
             (:content (support/eval-in! service other cwd
                                         "(core/emit (some? (resolve 'later)))")))
          "an environment is a runtime cache, not a live view of the library")
    (let [restarted (support/restart-service env)]
      (t/is (= "true"
               (support/script-content
                (support/eval-in! restarted other cwd
                                  "(core/emit (some? (resolve 'later)))")))
            "a new process builds a new environment from the library"))))

(t/deftest test-an-explicit-reload-refreshes-the-library
  (let [env (support/eval-ctx)
        service (:service env)
        _ (support/eval! env "(core/emit 1)")
        _ (eval-files/write-definition!
           (support/cwd-root env)
           {:name "user/later" :ns "user" :kind :def
            :source "(def later \"Later.\" 7)"})
        _ (eval-files/write-definition!
           (support/global-root env)
           {:name "user/globally" :ns "user" :kind :def
            :source "(def globally \"Global.\" :g)"})]
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'later)))")))
          "automatic refresh is still off")
    (let [result ((:reload! service) {:session (:session env) :cwd (:cwd env)})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "[eval: reloaded 2 library definitions")))
    (t/is (= "7" (:content (support/eval! env "later"))))
    (t/is (= ":g" (:content (support/eval! env "globally"))))
    (eval-files/write-definition!
     (support/cwd-root env)
     {:name "user/later" :ns "user" :kind :def
      :source "(def later \"Later.\" 8)"})
    (let [result ((:reload! service) {:session (:session env) :cwd (:cwd env)})]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (= "8" (:content (support/eval! env "later")))
          "a changed file is rebuilt from disk")))

(t/deftest test-a-reload-replays-live-only-definitions
  (let [env (support/eval-ctx)
        service (:service env)
        _ (support/eval! env "(def live 41)")]
    (t/is (not (fs/exists? (library-file env "user" "live.clj")))
          "an undocumented definition is not written")
    (let [result ((:reload! service) {:session (:session env) :cwd (:cwd env)})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "replayed 1 live-only definition")))
    (t/is (= "41" (:content (support/eval! env "live"))))
    (t/is (not (fs/exists? (library-file env "user" "live.clj")))
          "the reload keeps it memory-only")))

(t/deftest test-a-failed-reload-keeps-the-current-environment
  (let [env (support/eval-ctx)
        service (:service env)
        _ (support/eval! env "(defn kept \"Kept.\" [] :kept)")]
    (fs/create-dirs (fs/parent (library-file env "user" "broken.clj")))
    (spit (library-file env "user" "broken.clj")
          "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result ((:reload! service) {:session (:session env) :cwd (:cwd env)})]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result)
                           "the current environment is unchanged")))
    (t/is (= ":kept" (:content (support/eval! env "(kept)")))
          "the old environment still runs")))

(t/deftest test-a-restart-loads-the-library
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def x \"X.\" 41)")
        restarted (support/restart-service env)]
    (t/is (= "42"
             (support/script-content
              (support/eval-in! restarted (:session env)
                                (:cwd env) "(+ x 1)"))))))

(t/deftest test-drop-removes-the-file-and-unbinds-the-live-name
  (let [env (support/eval-ctx)
        path (library-file env "user" "doomed.clj")
        _ (support/eval! env "(defn doomed \"Doomed.\" [] 1)")
        result (support/eval! env nil :drop ["user/doomed"])]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "[eval: forgot user/doomed (cwd)]"))
    (t/is (not (fs/exists? path)))
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'doomed)))")))
          "the live var disappears in the same session")
    (t/is (= [] (support/eval-library env)))))

(t/deftest test-a-code-less-call-is-refused
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x \"X.\" 1)")
    (let [dropped (support/eval! env "(eval/forget 'user/x)")]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/x (cwd)]"))
      (t/is (not (fs/exists? path))))
    (let [empty-call (support/call-tool env "eval" {})]
      (t/is (:is-error empty-call))
      (t/is (str/includes? (:content empty-call) "No code provided")))))

(t/deftest test-drop-validation
  (let [env (support/eval-ctx)]
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no eval library definition"
                            (support/eval! env nil :drop ["user/nope"])))
    (support/eval! env "(def x \"X.\" 1)")
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"twice"
                            (support/eval! env nil :drop ["user/x" "user/x"])))
    (let [result (support/eval! env "(def x 2)" :drop ["user/x"])]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "defined and dropped")))
    (t/is (str/includes? (slurp (library-file env "user" "x.clj")) "(def x \"X.\" 1)")
          "the rejected call wrote nothing")))

(t/deftest test-eval-forget-deletes-and-unbinds
  (let [env (support/eval-ctx)
        path (library-file env "user" "f.clj")]
    (support/eval! env "(defn f \"F one.\" [] 1)")
    (let [result (support/eval! env "(eval/forget 'user/f)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (cwd)]"))
      (t/is (not (fs/exists? path))))
    (support/eval! env "(defn f \"F two.\" [] 2)")
    (support/eval! env "(eval/forget 'f)")
    (t/is (not (fs/exists? path)) "an unqualified name resolves in the call's final ns")
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'f)))"))))))

(t/deftest test-forget-of-an-unknown-name-errors
  (let [env (support/eval-ctx)
        result (support/eval! env "(eval/forget 'user/nope)")]
    (t/is (:is-error result))
    (t/is (str/includes? (:content result) "no eval library definition"))))

(t/deftest test-defmethod-addresses-are-distinct
  (let [env (support/eval-ctx)
        _ (support/eval! env (str "(defmulti area \"Area.\" :shape)\n"
                                  "(defmethod area :rect [m] :rect)\n"
                                  "(defmethod area :circle [m] :circle)"))
        rect (eval-files/path-for-name (support/cwd-root env) "user/area :rect")
        circle (eval-files/path-for-name (support/cwd-root env) "user/area :circle")]
    (t/is (fs/exists? (library-file env "user" "area.clj")))
    (t/is (fs/exists? rect))
    (t/is (fs/exists? circle))
    (support/eval! env nil :drop ["user/area :rect"])
    (t/is (not (fs/exists? rect)))
    (t/is (fs/exists? circle))
    (t/is (= "true"
             (:content (support/eval! env "(core/emit (some? (resolve 'area)))")))
          "dropping one method leaves the multimethod live")
    (t/is (= ":circle" (:content (support/eval! env "(area {:shape :circle})")))
          "the other method stays live")
    (t/is (:is-error (support/eval! env "(area {:shape :rect})"))
          "the dropped method is gone")
    (let [restarted (support/restart-service env)]
      (t/is (= ":circle"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(area {:shape :circle})")))
            "the other method survives a reload"))))

(t/deftest test-a-broken-library-file-is-reported-and-skipped
  (let [env (support/eval-ctx)
        path (library-file env "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result (support/eval! env "(core/emit 1)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (= "1" (support/script-content result)))
      (t/is (seq (get-in result [:details :failures])))
      (t/is (str/includes? (pr-str (get-in result [:details :failures])) path)))
    (let [next-result (support/eval! env "(core/emit 2)")]
      (t/is (= "2" (:content next-result)))
      (t/is (nil? (get-in next-result [:details :failures]))
            "a build failure is reported once, then cleared"))))

(t/deftest test-a-file-without-a-loaded-record-can-still-be-dropped
  (let [env (support/eval-ctx)
        broken (library-file env "user" "broken.clj")
        late (library-file env "user" "late.clj")]
    (fs/create-dirs (fs/parent broken))
    (spit broken "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (t/is (seq (get-in (support/eval! env "(core/emit :ok)")
                       [:details :failures])))
    (fs/create-dirs (fs/parent late))
    (spit late "(ns user)\n(def late 1)\n")
    (let [dropped (support/eval! env nil :drop ["user/broken" "user/late"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/broken (cwd)]"))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/late (cwd)]")))
    (t/is (not (fs/exists? broken)))
    (t/is (not (fs/exists? late)))))

(t/deftest test-a-written-definition-uses-the-current-call-state
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (spit (str (fs/path cwd "note.txt")) "library\n")
    (support/eval! env "(defn parse-json \"Parse JSON.\" [s] (json/parse-string s))")
    (support/eval! env "(defn say \"Say hi.\" [] (core/emit :hi))")
    (support/eval! env "(defn read-note \"Read the note.\" [] (str/trim (slurp \"note.txt\")))")
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (support/script-content
                (support/eval-in! restarted (:session env) cwd
                                  "(get (parse-json \"{\\\"a\\\":1}\") \"a\")")))
            "the standard alias prelude is replayed")
      (t/is (= ":hi"
               (support/script-content
                (support/eval-in! restarted (:session env) cwd "(say)"))))
      (t/is (= "library"
               (support/script-content
                (support/eval-in! restarted (:session env) cwd "(read-note)")))
            "slurp resolves against the current call's cwd"))))

(t/deftest test-a-written-definition-replays-the-bare-core-refers
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (support/eval! env "(defn say-bare \"Say hi.\" [] (emit :hi-bare))")
    (let [restarted (support/restart-service env)]
      (t/is (= ":hi-bare"
               (support/script-content
                (support/eval-in! restarted (:session env) cwd "(say-bare)")))
            "the core refers are replayed with the alias prelude"))))

(t/deftest test-a-written-definition-in-another-namespace-replays-aliases
  (let [env (support/eval-ctx)
        cwd (:cwd env)]
    (support/eval! env "(ns demo) (require '[clojure.string :as str]) (def x \"X.\" 1) (defn join \"Join with commas.\" [xs] (str/join \",\" xs))")
    (let [restarted (support/restart-service env)]
      (t/is (= "a,b"
               (support/script-content
                (support/eval-in! restarted (:session env) cwd
                                  "(demo/join [\"a\" \"b\"])")))
            "the alias prelude is replayed in the definition's namespace")
      (let [other (support/eval-ctx {:cwd cwd
                                     :agent-dir (:agent-dir env)})]
        (t/is (= "c,d"
                 (support/script-content
                  (support/eval-in! (:service other) (:session other)
                                    cwd "(demo/join [\"c\" \"d\"])")))
              "another home reader loads the ns form before the aliases")))))

(t/deftest test-the-sandbox-has-no-tools-namespace
  (let [env (support/eval-ctx)]
    (t/is (= "false" (:content (support/eval! env "(some? (find-ns 'tools))"))))
    (t/is (= "false" (:content (support/eval! env "(some? (resolve 'tools/call))"))))
    (let [result (support/eval! env "(tools/call \"bash\" {})")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "tools"))
      (t/is (not (str/includes? (:content result) "run-bash"))
            "the form never reaches a tool implementation"))))

(t/deftest test-a-saved-definition-naming-tools-fails-to-load
  (let [env (support/eval-ctx)
        path (library-file env "user" "echo_hi.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path (str "(ns user)\n"
                    "(defn echo-hi \"Echo hi.\" [] "
                    "(tools/text (deref (tools/call \"bash\" {:command \"echo hi\"}))))\n"))
    (let [restarted (support/restart-service env)
          result (support/eval-in! restarted (:session env) (:cwd env)
                                   "(core/emit :ok)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (seq (get-in result [:details :failures]))
            "a saved definition that names the removed tools namespace fails to load"))))

(t/deftest test-a-failed-definition-repairs-its-own-file
  (let [env (support/eval-ctx)
        path (support/library-file env :global "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path (str "(ns user)\n"
                    "(defn broken \"Broken.\" [] (missing-symbol))\n"))
    (let [result (support/eval! env "(core/emit :ok)")
          entry (first (support/eval-library env))]
      (t/is (str/includes? (:content result) "[eval: load failed global"))
      (t/is (str/includes? (:content result) "broken.clj"))
      (t/is (= "user/broken" (:name entry)))
      (t/is (= :failed (:load-status entry)))
      (t/is (= :global (:scope entry)))
      (t/is (str/includes? (:load-error entry) "missing-symbol"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help)"))
                           "Library: 1 saved (0 loaded, 1 failed), 0 session-only")))
    (let [result (support/eval! env "(defn broken \"Fixed.\" [] 1)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "[eval: defined user/broken (global)]"))
      (t/is (str/includes? (slurp path) "Fixed."))
      (t/is (not (fs/exists? (support/library-file env :cwd "user" "broken.clj"))))
      (t/is (= [{:name "user/broken" :ns "user" :kind :defn
                 :doc "Fixed." :arglists '([]) :file path
                 :scope :global :active true}]
               (support/eval-library env))))
    (let [index (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? index "Library: 1 saved (1 loaded), 0 session-only")))))

(t/deftest test-forget-drops-a-failed-definition
  (let [env (support/eval-ctx)
        path (support/library-file env :global "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path (str "(ns user)\n"
                    "(defn broken \"Broken.\" [] (missing-symbol))\n"))
    (support/eval! env "(core/emit :ok)")
    (t/is (= :failed (:load-status (first (support/eval-library env)))))
    (let [result (support/eval! env "(eval/forget 'broken)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "[eval: forgot user/broken (global)]")))
    (t/is (not (fs/exists? path)))
    (t/is (empty? (support/eval-library env)))))

(t/deftest test-a-namespace-cannot-escape-the-library-root
  (let [env (support/eval-ctx)
        safe (library-file env "_" "x.clj")
        escaped (str (fs/path (:agent-dir env) "x.clj"))]
    (support/eval! env "(ns ..) (def x \"X.\" 1)")
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
                                   "(core/emit [(some? (resolve 'legacy)) (some? (resolve 'entry-only))])")]
      (t/is (= "[false false]" (:content result))
            "only the agent home's library roots are loaded")
      (t/is (= [] (support/library-for service other))
            "only the agent home's library roots are loaded"))))

(t/deftest test-a-write-failure-is-reported-and-leaves-the-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(def x \"X.\" 1)")
    (let [result (with-redefs [eval-files/write-definition!
                               (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                   (support/eval! env "(def x \"X two.\" 2)"))]
      (t/is (:is-error result))
      (t/is (= :eval/write-failed (get-in result [:details :error])))
      (t/is (str/includes? (:content result) "[eval: define failed user/x (cwd):"))
      (t/is (seq (get-in result [:details :failures])))
      (t/is (= "2" (:content (support/eval! env "x")))
            "the live definition remains"))
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (support/script-content
                (support/eval-in! restarted (:session env)
                                  (:cwd env) "x")))
            "the old file is authoritative after the failed write"))))

(t/deftest test-a-failed-write-can-still-be-forgotten
  (let [env (support/eval-ctx)
        result (with-redefs [eval-files/write-definition!
                             (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                 (support/eval! env "(def lost \"Lost.\" 1)"))]
    (t/is (:is-error result))
    (t/is (= "true"
             (:content (support/eval! env "(core/emit (some? (resolve 'lost)))")))
          "the live definition remains")
    (let [dropped (support/eval! env nil :drop ["user/lost"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/lost (unwritten)]")))
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'lost)))")))
          "a failed write is still forgettable")))

(t/deftest test-an-unqualified-drop-qualifies-the-call-s-final-ns
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x \"X.\" 1)")
    (let [dropped (support/eval! env nil :drop ["x"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/x (cwd)]")))
    (t/is (not (fs/exists? path)))
    (t/is (= [] (support/eval-library env)))
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'x)))"))))))

(t/deftest test-a-definition-from-an-errored-call-can-be-forgotten
  (let [env (support/eval-ctx)
        result (support/eval! env "(def z 1) (throw (ex-info \"boom\" {}))")]
    (t/is (:is-error result))
    (t/is (= "true"
             (:content (support/eval! env "(core/emit (some? (resolve 'z)))")))
          "the definition evaluated before the throw is live")
    (let [dropped (support/eval! env nil :drop ["user/z"])]
      (t/is (not (:is-error dropped)) (:content dropped))
      (t/is (str/includes? (:content dropped) "[eval: forgot user/z (unwritten)]")))
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'z)))")))
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
    (eval-files/write-definition! (support/global-root env)
                                  {:name "demo/y" :ns "demo" :kind :defn
                                   :source "(defn y [] 1)"})
    (let [result (support/eval! env "(ns demo) (eval/forget 'y)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot demo/y (global)]")))
    (t/is (not (fs/exists? path)))))

(t/deftest test-a-partial-drop-reports-what-landed
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def a \"A.\" 1) (def b \"B.\" 2)")
        delete eval-files/delete-file!]
    (with-redefs [eval-files/delete-file!
                  (fn [root path]
                    (if (str/includes? (str path) "b.clj")
                      (throw (ex-info "disk full" {:type :test}))
                      (delete root path)))]
      (let [result (support/eval! env "(core/emit :ok)" :drop ["user/a" "user/b"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: forgot user/a (cwd)]"))
        (t/is (str/includes? (:content result) "[eval: failed: disk full]"))))))

(t/deftest test-an-unbind-failure-is-reported
  (let [env (support/eval-ctx)
        path (library-file env "user" "x.clj")]
    (support/eval! env "(def x 1)")
    (with-redefs [script/eval-source! (fn [& _] {:error "boom"})]
      (let [result (support/eval! env nil :drop ["user/x"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: failed user/x: boom]"))
        (t/is (seq (get-in result [:details :failures])))))
    (t/is (not (fs/exists? path)))
    (t/is (= "true"
             (:content (support/eval! env "(core/emit (some? (resolve 'x)))")))
          "the live var remains when unbinding fails")))

(t/deftest test-a-code-less-drop-reports-partial-deletes
  (let [env (support/eval-ctx)
        _ (support/eval! env "(def a \"A.\" 1) (def b \"B.\" 2)")
        delete eval-files/delete-file!]
    (with-redefs [eval-files/delete-file!
                  (fn [root path]
                    (if (str/includes? (str path) "b.clj")
                      (throw (ex-info "disk full" {:type :test}))
                      (delete root path)))]
      (let [result (support/eval! env nil :drop ["user/a" "user/b"])]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "[eval: forgot user/a (cwd)]"))
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
    (support/eval! env "(def a \"A.\" 1)")
    (let [result (support/eval! env "(def b \"B.\" 2)" :drop ["user/a"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/a (cwd)]"))
      (t/is (str/includes? (:content result) "[eval: defined user/b (cwd)]"))
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
                                          (str "(core/sleep 10) "
                                               "(def p" i " \"P" i ".\" " i ") p" i))))
                    (range 8))]
    (doseq [call calls]
      (t/is (not (:is-error @call)) (:content @call)))
    (doseq [i (range 8)]
      (t/is (fs/exists? (library-file env "user" (str "p" i ".clj")))))
    (t/is (= "[true true true true true true true true]"
             (:content (support/eval-in! service session cwd
                                         (str "(core/emit (mapv #(some? (resolve %)) "
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
        (let [result (support/eval! env "(core/emit :ok)")]
          (t/is (not (:is-error result)) (:content result))
          (t/is (seq (get-in result [:details :failures]))
                "a looping library definition is recorded and skipped"))))))

(t/deftest ^:slow test-the-build-budget-bounds-the-whole-build
  (let [env (support/eval-ctx)]
    (doseq [i (range 3)]
      (let [path (library-file env "user" (str "spin-" i ".clj"))]
        (fs/create-dirs (fs/parent path))
        (spit path (eval-files/file-content
                    {:ns "user"
                     :source (str "(def spin-" i " (loop [] (recur)))")}))))
    (with-redefs-fn {#'eval-service/build-budget-ms 50}
      (fn []
        (let [started (concurrent/monotonic-ms)
              result (support/eval! env "(core/emit :ok)")]
          (t/is (not (:is-error result)) (:content result))
          (t/is (>= (count (get-in result [:details :failures])) 1))
          (t/is (< (- (concurrent/monotonic-ms) started) 5000)
                "one escaping file cannot spend the whole per-file budget"))))))

(t/deftest drop-uses-the-records-file
  (let [env (support/eval-ctx)
        path (library-file env "user" "misc.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(defn from-misc [] 1)\n")
    (t/is (= "1" (support/script-content (support/eval! env "(from-misc)"))))
    (let [result (support/eval! env nil :drop ["user/from-misc"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/from-misc (cwd)]")))
    (t/is (not (fs/exists? path)))
    (let [restarted (support/restart-service env)]
      (t/is (= "false"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(core/emit (some? (resolve 'from-misc)))")))))))

(t/deftest a-no-environment-drop-uses-the-disk-index
  (let [env (support/eval-ctx)
        path (library-file env "user" "misc.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(defn from-misc [] 1)\n")
    (let [result (support/eval! env nil :drop ["user/from-misc"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/from-misc (cwd)]")))
    (t/is (not (fs/exists? path)))))

(t/deftest drop-refuses-a-canonical-file-that-belongs-to-another-name
  (let [env (support/eval-ctx)
        path (library-file env "user" "f.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(def g 1)\n")
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not define"
                            (support/eval! env nil :drop ["user/f"])))
    (t/is (fs/exists? path))
    (t/is (= "1" (support/script-content (support/eval! env "g"))))
    (t/is (= "false"
             (:content (support/eval! env "(core/emit (some? (resolve 'f)))"))))))

(t/deftest drop-a-qualified-defmethod-deletes-its-actual-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(ns other.ns) (defmulti area \"Area.\" :shape)")
    (support/eval! env "(ns user) (defmethod other.ns/area :rect [m] :rect)")
    (let [path (:file (first (filter #(= "other.ns/area :rect" (:name %))
                                     (support/eval-library env))))]
      (t/is (fs/exists? path))
      (let [result (support/eval! env nil :drop ["other.ns/area :rect"])]
        (t/is (not (:is-error result)) (:content result)))
      (t/is (not (fs/exists? path)))
      (let [restarted (support/restart-service env)]
        (t/is (= "false"
                 (support/script-content
                  (support/eval-in! restarted (:session env) (:cwd env)
                                    "(core/emit (some? (get-method other.ns/area :rect)))"))))))))

(t/deftest a-broken-legacy-lossy-file-is-still-droppable
  (let [env (support/eval-ctx)
        path (library-file env "user" "a.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(defn a? [")
    (let [result (support/eval! env nil :drop ["user/a?"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/a? (cwd)]")))
    (t/is (not (fs/exists? path)))))

(t/deftest punctuation-names-do-not-share-a-file
  (let [env (support/eval-ctx)
        result (support/eval! env "(def a? \"A?\" 1) (def a! \"A!\" 2)")
        files (mapv :file (support/eval-library env))]
    (t/is (not (:is-error result)) (:content result))
    (t/is (= 2 (count (distinct files))))
    (let [restarted (support/restart-service env)
          content (support/script-content
                   (support/eval-in! restarted (:session env) (:cwd env)
                                     "(core/emit [(some? (resolve 'a?)) (some? (resolve 'a!)) a? a!])"))]
      (t/is (= "[true true 1 2]" content)))))

(t/deftest drop-refuses-a-file-with-unrequested-siblings
  (let [env (support/eval-ctx)
        path (library-file env "user" "pair.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(def a 1)\n(def b 2)\n")
    (t/is (= "3" (support/script-content (support/eval! env "(core/emit (+ a b))"))))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"also defines user/b"
                            (support/eval! env nil :drop ["user/a"])))
    (t/is (fs/exists? path))
    (let [result (support/eval! env nil :drop ["user/a" "user/b"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/a (cwd)]"))
      (t/is (str/includes? (:content result) "[eval: forgot user/b (cwd)]")))
    (t/is (not (fs/exists? path)))
    (let [restarted (support/restart-service env)]
      (t/is (= "[false false]"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(core/emit [(some? (resolve 'a)) (some? (resolve 'b))])")))))))

(t/deftest a-redefinition-keeps-a-noncanonical-file-and-its-siblings
  (let [env (support/eval-ctx)
        path (library-file env "user" "pair.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(def a 1)\n(def b 2)\n")
    (support/eval! env "(core/emit (+ a b))")
    (let [result (support/eval! env "(defn a \"A.\" [] :new)")]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (str/includes? (slurp path) "(defn a \"A.\" [] :new)"))
    (t/is (str/includes? (slurp path) "(def b 2)"))
    (let [restarted (support/restart-service env)]
      (t/is (= "[:new 2]"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(core/emit [(a) b])")))))))

(t/deftest an-unqualified-drop-uses-the-calls-final-namespace
  (let [env (support/eval-ctx)
        path (library-file env "demo" "x.clj")]
    (support/eval! env "(ns demo) (def x \"X.\" 1)")
    (support/eval! env "(ns user)")
    (let [result (support/eval! env "(ns demo) :ok" :drop ["x"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot demo/x (cwd)]")))
    (t/is (not (fs/exists? path)))))

(t/deftest the-nrepl-eval-verb-needs-no-tool-surface
  ;; clj/nrepl-eval is an eval verb over kmet.libs.clj-eval, not a tool bridge:
  ;; it works with every tool disabled.
  (let [env (support/eval-ctx)]
    (with-redefs [nrepl/eval-code
                  (fn [_opts]
                    {:ok? true :values ["3"] :out "" :err ""
                     :events [[:value "3"]] :env :clj :session "s"})]
      (binding [script/*enabled-tools-fn* (constantly #{})]
        (let [result (support/eval! env "(:content (clj/nrepl-eval {:code \"(+ 1 2)\" :port 7888}))")]
          (t/is (= "=> 3" (:content result))))))))

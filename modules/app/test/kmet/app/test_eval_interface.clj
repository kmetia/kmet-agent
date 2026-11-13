(ns kmet.app.test-eval-interface
  "The eval interface: `:doc`/`:arglists` extraction from a defining form,
   `(eval/describe)` for library definitions and source verbs, and the
   in-memory record of an unwritten live definition."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.script :as script]))

(defn- records [code]
  (:defs (defs/parse code "user")))

(defn- record-for [code name]
  (first (filter #(= name (:name %)) (records code))))

(defn- interface-of [code name]
  (select-keys (record-for code name) [:doc :arglists]))

(t/deftest test-definition-prose
  (t/is (= "why\nhow"
           (defs/definition-prose
             "(defn f \"Doc.\"\n  ;; why\n  ;; how\n  [x] x)")))
  (t/is (= "why"
           (defs/definition-prose
             "(defn f \"Line one.\nLine two.\"\n  ;; why\n  [x] x)"))
        "a multi-line docstring does not shift the scan")
  (t/is (nil? (defs/definition-prose
                "(defn f \"Doc.\"\n\n  ;; why\n  [x] x)"))
        "a blank line ends the prose")
  (t/is (nil? (defs/definition-prose "(defn f [x] x)")))
  (t/is (nil? (defs/definition-prose "(def f \"Doc.\" 1)"))))

(defn- describe-map [env form]
  (edn/read-string (:content (support/eval! env (str "(core/emit " form ")")))))

(t/deftest test-docstring-attr-map-and-symbol-metadata
  (t/is (= {:doc nil :arglists '([x])}
           (interface-of "(defn f [x] x)" "user/f")))
  (t/is (= {:doc "Doc." :arglists '([x])}
           (interface-of "(defn f \"Doc.\" [x] x)" "user/f")))
  (t/is (= {:doc "Attr." :arglists '([x])}
           (interface-of "(defn f {:doc \"Attr.\"} [x] x)" "user/f")))
  (t/is (= {:doc "Sym." :arglists '([x])}
           (interface-of "(defn ^{:doc \"Sym.\"} f [x] x)" "user/f")))
  (t/is (= {:doc "Doc." :arglists '([x])}
           (interface-of "(defn f \"Doc.\" {:added \"1.0\"} [x] x)" "user/f")))
  (t/is (= {:doc "Sym." :arglists '([x])}
           (interface-of "(defn ^{:doc \"Sym.\"} f {:doc 42} [x] x)" "user/f"))
        "a non-string attr-map :doc does not hide the name metadata"))

(t/deftest test-multi-arity-and-trailing-attr-map
  (t/is (= {:doc nil :arglists '([x] [x y])}
           (interface-of "(defn f ([x] x) ([x y] y))" "user/f")))
  (t/is (= {:doc "Trail." :arglists '([x] [x y])}
           (interface-of "(defn f ([x] x) ([x y] y) {:doc \"Trail.\"})" "user/f")))
  (t/is (= {:doc "Macro." :arglists '([x])}
           (interface-of "(defmacro m \"Macro.\" [x] x)" "user/m")))
  (t/is (= {:doc "Private." :arglists '([x])}
           (interface-of "(defn- f \"Private.\" [x] x)" "user/f"))))

(t/deftest test-def-docstring-needs-an-init
  (t/is (= {:doc nil :arglists nil}
           (interface-of "(def x \"value\")" "user/x")))
  (t/is (= {:doc "Doc." :arglists nil}
           (interface-of "(def x \"Doc.\" 1)" "user/x")))
  (t/is (= {:doc "Meta." :arglists nil}
           (interface-of "(def ^{:doc \"Meta.\"} x 1)" "user/x")))
  (t/is (nil? (:doc (record-for "(def config {:doc \"not a docstring\"})"
                                "user/config")))))

(t/deftest test-defmulti-option-map-is-not-an-attr-map
  (t/is (nil? (:doc (record-for "(defmulti d (fn [x] x) :default {:doc \"not a doc\"})"
                                "user/d")))
        "a defmulti option value is not a trailing attr-map"))

(t/deftest test-defmethod-tail-and-declare-names
  (let [method (record-for "(defmethod area :rect [m] :rect)" "user/area :rect")]
    (t/is (= :defmethod (:kind method)))
    (t/is (= '([m]) (:arglists method)))
    (t/is (nil? (:doc method))))
  (let [declared (records "(declare ^{:doc \"First.\"} a b)")]
    (t/is (= "First." (:doc (first (filter #(= "user/a" (:name %)) declared)))))
    (t/is (nil? (:doc (first (filter #(= "user/b" (:name %)) declared)))))))

(t/deftest test-describe-returns-the-interface-and-source
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "report.clj")
        source "(defn report \"Render a report.\" [x] (str x))"
        _ (support/eval! env source)
        described (describe-map env "(eval/describe 'user/report)")]
    (t/is (= {:name "user/report"
              :kind :defn
              :arglists '([x])
              :file path
              :scope :cwd
              :source source}
             described))
    (t/is (not (contains? described :doc))
          "the default view omits the docstring when the entry has a source")
    (t/is (= described (describe-map env "(eval/describe 'user/report nil)"))
          "a nil key returns the default view")
    (t/is (= described (describe-map env "(eval/describe 'report)"))
          "an unqualified name resolves in the call's final namespace")
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'report :doc)"))
                         "Render a report."))
    (t/is (= "([x])"
             (:content (support/eval! env "(core/emit (eval/describe 'report :arglists))"))))
    (t/is (= ":defn"
             (:content (support/eval! env "(core/emit (eval/describe 'report :kind))"))))
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'report :source)"))
                         source))
    (let [unknown (support/eval! env "(eval/describe 'user/nope)")]
      (t/is (:is-error unknown))
      (t/is (str/includes? (:content unknown) "no eval definition named")))))

(t/deftest test-help-on-an-unknown-name-is-the-index
  (let [env (support/eval-ctx)
        result (support/eval! env "(eval/help 'user/nope)")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "No help for user/nope"))
    (t/is (str/includes? (:content result) "Built-in verbs:"))
    (let [topic (support/eval! env "(eval/help :nope)")]
      (t/is (not (:is-error topic)) (:content topic))
      (t/is (str/includes? (:content topic) "No help for topic :nope"))
      (t/is (str/includes? (:content topic) "Built-in verbs:")))
    (t/is (= (:content (support/eval! env "(help :core)"))
             (:content (support/eval! env "(help \"CORE\")")))
          "a string names the same topic as its keyword")
    (let [miss (support/eval! env "(eval/help \"nope\")")]
      (t/is (not (:is-error miss)) (:content miss))
      (t/is (str/includes? (:content miss) "No help for topic or name \"nope\""))
      (t/is (str/includes? (:content miss) "Built-in verbs:")))
    (let [named (support/eval! env "(help \"read\")")]
      (t/is (not (:is-error named)) (:content named))
      (t/is (str/includes? (:content named) "read verb"))
      (t/is (str/includes? (:content named) "Read PATH as text")))))

(t/deftest test-an-existing-library-file-gains-its-interface
  (let [env (support/eval-ctx)
        path (str (fs/path (:agent-dir env) "eval" "user" "legacy.clj"))]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(defn legacy \"Legacy doc.\" [x] x)\n")
    (support/eval! env "(core/emit :loaded)")
    (t/is (= "Legacy doc."
             (:content (support/eval! env "(eval/describe 'legacy :doc)"))))
    (t/is (= "([x])"
             (:content (support/eval! env
                                      "(core/emit (eval/describe 'legacy :arglists))"))))))

(t/deftest test-describe-never-reads-a-changed-file
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "f.clj")
        _ (support/eval! env "(defn f \"Original.\" [] 1)")]
    (spit path "(ns user)\n(defn f \"Changed on disk.\" [] 2)\n")
    (t/is (= "Original."
             (:content (support/eval! env "(eval/describe 'f :doc)"))))
    (t/is (not (str/includes?
                (:content (support/eval! env "(eval/describe 'f :source)"))
                "Changed on disk.")))))

(t/deftest test-describe-follows-a-redefinition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn r \"First.\" [] 1)")
    (support/eval! env "(defn r \"Second.\" [] 2)")
    (t/is (= "Second."
             (:content (support/eval! env "(eval/describe 'r :doc)"))))
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'r :source)"))
                         "Second."))))

(t/deftest test-describe-defmethod-and-declare
  (let [env (support/eval-ctx)]
    (support/eval! env "(defmulti area :shape) (defmethod area :rect [m] :rect)")
    (t/is (= "([m])"
             (:content (support/eval! env
                                      "(core/emit (eval/describe (symbol \"user/area :rect\") :arglists))"))))
    (t/is (= "(defmethod area :rect [m] :rect)"
             (:content (support/eval! env
                                      "(eval/describe (symbol \"user/area :rect\") :source)"))))
    (support/eval! env "(declare early late)")
    (t/is (= ":declare"
             (:content (support/eval! env "(core/emit (eval/describe 'early :kind))"))))
    (t/is (= "(declare early late)"
             (:content (support/eval! env "(eval/describe 'late :source)"))))))

(t/deftest test-describe-reports-load-interface-and-help
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn documented \"Doc line.\\nMore.\" [x] x)")
    (support/eval! env "(defn undocumented [x] x)")
    (let [library (:content (support/eval! env "(eval/library)"))
          help (:content (support/eval! env "(eval/help :library)"))]
      (t/is (str/includes? library ":doc \"Doc line.\\nMore.\""))
      (t/is (str/includes? library ":arglists ([x])"))
      (t/is (str/includes? help "documented defn ([x]) (cwd) — Doc line."))
      (t/is (str/includes? help "undocumented defn ([x]) (cwd) — (no docstring)"))
      (t/is (= "true"
               (:content (support/eval! env
                                        "(core/emit (nil? (eval/describe 'undocumented :doc)))")))))
    (let [help (:content (support/eval! env "(eval/help 'documented)"))]
      (t/is (str/includes? help "doc:\n    Doc line.\n    More.")
            "help shows the whole docstring"))))

(t/deftest test-help-shows-definition-prose
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn documented \"Doc.\"\n  ;; Why it exists.\n  ;; How to use it.\n  [x] x)")
    (let [help (:content (support/eval! env "(eval/help 'documented)"))]
      (t/is (str/includes? help
                           "prose:\n    Why it exists.\n    How to use it."))
      (t/is (not (str/includes? help "(defn documented"))
            "help still points at the source instead of dumping it"))))

(t/deftest test-index-searches-definitions-and-sources
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn report \"Render report.\" [x] (str x \"needle\"))")
    (tools/register-eval-source!
     (:tools env) :license
     (fn [_]
       {:doc "License helpers"
        :ns {'ext.license
             {'add! (with-meta (fn [_] :added)
                      {:doc "Add a header."
                       :arglists '([path])
                       :source "(fn [path] (str path \"header\"))"})}}}))
    (let [by-name (:content (support/eval! env "(index \"report\")"))
          by-source (:content (support/eval! env "(index \"needle\")"))
          by-source-name (:content (support/eval! env "(index \"add!\")"))
          by-source-doc (:content (support/eval! env "(index \"License helpers\")"))
          missing (:content (support/eval! env "(index \"absent\")"))]
      (t/is (str/includes? by-name "report defn ([x]) (cwd) — Render report."))
      (t/is (str/includes? by-source "needle"))
      (t/is (str/includes? by-source-name "license/add! (source) — Add a header."))
      (t/is (str/includes? by-source-doc
                           "- :license: License helpers — 1 verb; (help :license)"))
      (t/is (= "No eval definitions or sources match \"absent\"." missing)))))

(t/deftest test-help-omits-the-definition-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(def plain 1)")
    (let [help (:content (support/eval! env "(eval/help :library)"))]
      (t/is (str/includes? help "plain def (cwd) — (no docstring)"))
      (t/is (not (str/includes? help "plain.clj"))))))

(t/deftest test-help-index-topics-and-one-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn documented \"Doc.\" [x] x)")
    (let [index (:content (support/eval! env "(eval/help)"))
          library (:content (support/eval! env "(eval/help :library)"))
          core (:content (support/eval! env "(eval/help :core)"))
          persistence (:content (support/eval! env "(eval/help :persistence)"))
          one (:content (support/eval! env "(eval/help 'user/documented)"))]
      (t/is (str/includes? index "Eval help:"))
      (t/is (str/includes? index "Library: 1 saved (1 loaded), 0 session-only"))
      (t/is (str/includes? library "documented defn ([x]) (cwd) — Doc."))
      (t/is (str/includes? library "Scope: cwd"))
      (t/is (not (str/includes? index "Scope: cwd")) "the index leaves roots to :library")
      (t/is (str/includes? core "Core:"))
      (t/is (str/includes? core "Ownership: define in user"))
      (t/is (str/includes? core "ext.* is live-only"))
      (t/is (str/includes? persistence "Persistence:"))
      (t/is (str/includes? persistence
                           "A redefinition of a saved definition with a docstring is written automatically"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help :core)"))
                           "forget"))
      (t/is (str/includes? one "documented defn ([x]) (cwd)"))
      (t/is (str/includes? one "doc: Doc."))
      (t/is (str/includes? one "inspect: (describe 'documented :source)"))
      (t/is (str/includes? one "(describe 'documented :file)"))
      (t/is (not (str/includes? one "(defn documented"))
            "help points at :source instead of dumping it")
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'core/read)"))
                           "read verb ([path] [path {:keys [offset limit tail footer]}]) (built-in)"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'core/read :doc)"))
                           "Read PATH as text"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'core/read :doc)"))
                           ":tail"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'core/edit)"))
                           "[path edits]"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'core/bash :doc)"))
                           ":max-lines"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'read)"))
                           "read verb ([path] [path {:keys [offset limit tail footer]}]) (built-in)"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'emit :doc)"))
                           "Report VALUE"))
      (t/is (str/includes? core "also referred unqualified"))
      (t/is (str/includes? index "  read ([path] [path {:keys [offset limit tail footer]}]) - "))
      (t/is (<= (count index) 4096) "the index stays one bounded discovery call")
      (t/is (str/includes? index "Topics:\n  :core — sandbox verbs"))
      (t/is (str/includes? index "  :surface — host, aliases"))
      (t/is (not (str/includes? index "core/sh")))
      (t/is (str/includes? index "bash ([command] [command {:keys [timeout cwd max-lines max-bytes as]}]) - "))
      (t/is (not (str/includes? index "tools/")))
      (t/is (not (str/includes? index "Active tools")))
      (t/is (str/includes? index "  library ([]) - "))
      (t/is (str/includes? index "  index ([text]) - Search"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'library)"))
                           "library verb ([]) (built-in)"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'clj/edit-form)"))
                           "clj/edit-form verb ([{:keys [file_path form_identifier form_type operation content]}]) (built-in)"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'clj/edit-form :doc)"))
                           "structured whole-form edit"))
      (t/is (str/includes? (:content (support/eval! env "(eval/help 'clj/nrepl-eval)"))
                           "clj/nrepl-eval verb ([{:keys [code port host ns timeout]}]) (built-in)"))
      (t/is (str/includes? (:content (support/eval! env "(eval/describe 'clj/nrepl-eval :doc)"))
                           "running nREPL server")))))

(t/deftest test-surface-help-names-the-copied-babashka-namespaces
  (let [env (support/eval-ctx)
        text (:content (support/eval! env "(help :surface)"))
        base (script/base-namespaces)
        babashka-names (->> (keys base)
                            (map str)
                            (filter #(str/starts-with? % "babashka."))
                            (remove (fn [n]
                                      (some #{"impl" "internal" "nrepl"}
                                            (str/split n #"\."))))
                            sort)]
    (t/is (str/includes? text (str "Surface (host: " (script/host-label) ")")))
    (t/is (str/includes? text "Aliases: fs -> babashka.fs"))
    (t/is (str/includes? text "kmet.libs.json"))
    (t/is (str/includes? text "clojure.core: SCI's own core"))
    (doseq [n babashka-names]
      (t/is (str/includes? text n) n))
    (t/is (str/includes? text (case (script/class-surface)
                                :none "Classes: none"
                                :subset "Classes: an explicit subset"
                                :full "Classes: the host's full class table")))))

(t/deftest test-bare-help-and-describe-aliases
  (let [env (support/eval-ctx)]
    (t/is (= (:content (support/eval! env "(help)"))
             (:content (support/eval! env "(eval/help)"))))
    (t/is (= (:content (support/eval! env "(help :results)"))
             (:content (support/eval! env "(eval/help :results)"))))
    (support/eval! env "(defn aliased \"Aliased doc.\" [x] x)")
    (t/is (= "Aliased doc." (:content (support/eval! env "(describe 'aliased :doc)"))))
    (support/eval! env "(ns demo)")
    (t/is (str/includes? (:content (support/eval! env "(help)")) "Built-in verbs:"))
    (t/is (= ":defn" (:content (support/eval! env "(describe 'user/aliased :kind)"))))))

(t/deftest test-an-unwritten-definition-is-described
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "lost.clj")
        failed (with-redefs [eval-files/write-definition!
                             (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                 (support/eval! env "(defn lost \"Lost doc.\" [x] x)"))]
    (t/is (:is-error failed))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'user/lost :file)))"))))
    (t/is (= "Lost doc."
             (:content (support/eval! env "(eval/describe 'user/lost :doc)"))))
    (t/is (str/includes?
           (:content (support/eval! env "(eval/describe 'user/lost :source)"))
           "Lost doc."))
    (support/eval! env "(defn lost \"Kept.\" [x] x)")
    (let [[entry] (support/eval-library env)]
      (t/is (= "Kept." (:doc entry)))
      (t/is (= path (:file entry))
            "a successful write clears the unwritten record"))
    (support/eval! env nil :drop ["user/lost"])
    (t/is (:is-error (support/eval! env "(eval/describe 'user/lost)"))
          "a drop clears the unwritten record")))

(t/deftest test-a-failed-redefinition-describes-the-live-record
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "stable.clj")
        _ (support/eval! env "(defn stable \"Old.\" [] 1)")]
    (with-redefs [eval-files/write-definition!
                  (fn [& _] (throw (ex-info "disk full" {:type :test})))]
      (support/eval! env "(defn stable \"New.\" [] 2)"))
    (t/is (= "New."
             (:content (support/eval! env "(eval/describe 'stable :doc)"))))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'stable :file)))"))))
    (support/eval! env "(defn stable \"Kept.\" [] 3)")
    (t/is (= path
             (:content (support/eval! env "(eval/describe 'stable :file)"))))
    (t/is (= "Kept."
             (:content (support/eval! env "(eval/describe 'stable :doc)"))))))

(t/deftest test-an-errored-call-s-record-is-described-until-dropped
  (let [env (support/eval-ctx)
        result (support/eval! env
                              "(defn boom \"Boom doc.\" [] 1) (throw (ex-info \"boom\" {}))")]
    (t/is (:is-error result))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'user/boom :file)))"))))
    (t/is (= "Boom doc."
             (:content (support/eval! env "(eval/describe 'user/boom :doc)"))))
    (support/eval! env nil :drop ["user/boom"])
    (t/is (:is-error (support/eval! env "(eval/describe 'user/boom)")))))

(defn- register! [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-interface id]}))

(t/deftest test-source-verbs-carry-their-interface
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_]
                 {:doc "License helpers"
                  :ns {'ext.license {'add! (with-meta
                                             (fn [_] :added)
                                             {:doc "Add a header."
                                              :arglists '([path])
                                              :source "(fn [path] ...)"})
                                     'doc-only (with-meta
                                                 (fn [] :doc-only)
                                                 {:doc "Doc only."})
                                     'bare (fn [] :bare)}}
                  :macros {'ext.license {'twice (with-meta
                                                  (fn [_ _ form] (list 'do form form))
                                                  {:doc "Twice."
                                                   :arglists '([form])})}}}))
    (let [described (describe-map env "(eval/describe 'ext.license/add!)")]
      (t/is (= "ext.license/add!" (:name described)))
      (t/is (= :verb (:kind described)))
      (t/is (= '([path]) (:arglists described)))
      (t/is (= "(fn [path] ...)" (:source described)))
      (t/is (not (contains? described :doc)))
      (t/is (not (contains? described :file)) "a nil file is omitted")
      (t/is (not (contains? described :scope)) "a nil scope is omitted")
      (t/is (= "Add a header."
               (:content (support/eval! env
                                        "(eval/describe 'ext.license/add! :doc)")))))
    (let [described (describe-map env "(eval/describe 'ext.license/doc-only)")]
      (t/is (= "Doc only." (:doc described))
            "doc is kept when there is no source to carry it")
      (t/is (not (contains? described :source))))
    (t/is (= "[\"Add a header.\" ([path])]"
             (:content (support/eval! env
                                      "(core/emit [(-> (meta #'ext.license/add!) :doc) (-> (meta #'ext.license/add!) :arglists)])"))))
    (t/is (= ":macro"
             (:content (support/eval! env "(core/emit (eval/describe 'ext.license/twice :kind))"))))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'ext.license/bare :source)))"))))
    (let [help (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? help
                           "- :license: License helpers — 4 verbs; (help :license)"))
      (t/is (not (str/includes? help "license/add!"))
            "the index leaves source verbs to the source topic"))
    (let [help (:content (support/eval! env "(eval/help :license)"))]
      (t/is (str/includes? help "Eval source license:"))
      (t/is (str/includes? help "License helpers"))
      (t/is (str/includes? help "license/add! ([path]) — Add a header."))
      (t/is (= help (:content (support/eval! env "(eval/help \"license\")")))
            "a string names a source topic"))
    (let [help (:content (support/eval! env "(eval/help 'license/add!)"))]
      (t/is (str/includes? help "license/add! verb ([path]) (source)"))
      (t/is (str/includes? help "doc: Add a header."))
      (t/is (str/includes? help "inspect: (describe 'license/add! :source)"))
      (t/is (not (str/includes? help "(fn [path] ...)"))
            "help points at :source instead of dumping it"))
    (let [sources (:content (support/eval! env "(eval/sources)"))]
      (t/is (str/includes? sources ":license"))
      (t/is (str/includes? sources "license/add!"))
      (t/is (str/includes? sources "(no docstring)")))))

(t/deftest test-an-undocumented-definition-stays-live-only
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "undocumented.clj")
        result (support/eval! env "(defn undocumented [] 1)")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result)
                         "[eval: defined user/undocumented (cwd; live-only:"))
    (t/is (str/includes? (:content result) "no docstring"))
    (t/is (not (fs/exists? path)))
    (t/is (= "1" (:content (support/eval! env "(undocumented)")))
          "the definition stays live in the session")
    (let [help (:content (support/eval! env "(eval/help :library)"))]
      (t/is (str/includes? help "Session-only definitions (live, not saved): 1"))
      (t/is (str/includes? help "undocumented defn ([]) (cwd) — (no docstring)")))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'undocumented :file)))"))))
    (let [documented (support/eval! env "(defn documented \"Doc.\" [] 1)")]
      (t/is (not (str/includes? (:content documented) "live only")))
      (t/is (fs/exists? (support/library-file env :cwd "user" "documented.clj"))))))

(t/deftest test-an-undocumented-redefinition-keeps-its-saved-file
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "kept.clj")
        _ (support/eval! env "(defn kept \"Old.\" [] :old)")
        result (support/eval! env "(defn kept [] :new)")]
    (t/is (not (:is-error result)) (:content result))
    (t/is (str/includes? (:content result) "live-only"))
    (t/is (str/includes? (:content result) "the saved file is unchanged"))
    (t/is (= ":new" (:content (support/eval! env "(kept)")))
          "the redefinition is live")
    (t/is (fs/exists? path))
    (t/is (str/includes? (slurp path) ":old"))
    (t/is (str/includes? (slurp path) "Old."))
    (t/is (not (str/includes? (slurp path) ":new")))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(core/emit (nil? (eval/describe 'kept :doc)))")))
          "describe shows the live undocumented record")
    (t/is (= "Old."
             (:doc (first (filter #(= "user/kept" (:name %))
                                  (support/eval-library env)))))
          "the saved record keeps its docstring")
    (let [restarted (support/restart-service env)]
      (t/is (= ":old"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env) "(kept)")))
            "a restart loads the saved documented version"))
    (let [saved (support/eval! env "(defn kept \"New.\" [] :newer)")]
      (t/is (not (str/includes? (:content saved) "live only")))
      (t/is (str/includes? (slurp path) ":newer"))
      (t/is (str/includes? (slurp path) "New.")))))

(t/deftest test-a-defmethod-is-saved-with-its-documented-multimethod
  (let [env (support/eval-ctx)]
    (support/eval! env "(defmulti area :shape)")
    (support/eval! env "(defmethod area :rect [m] :rect)")
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "area.clj")))
          "an undocumented multimethod stays live-only")
    (t/is (nil? (some #(= "user/area :rect" (:name %)) (support/eval-library env)))
          "its method stays live-only too")
    (let [result (support/eval! env "(defmulti shape \"Shape area.\" :shape)")
          method (support/eval! env "(defmethod shape :rect [m] :rect)")
          entry (first (filter #(= "user/shape :rect" (:name %))
                               (support/eval-library env)))]
      (t/is (not (:is-error result)) (:content result))
      (t/is (not (:is-error method)) (:content method))
      (t/is (str/includes? (:content result) "user/shape (cwd)]"))
      (t/is (str/includes? (:content method) "user/shape :rect (cwd)]"))
      (t/is (fs/exists? (:file entry)))
      (t/is (= "Shape area." (:doc (first (filter #(= "user/shape" (:name %))
                                                  (support/eval-library env)))))))))

(t/deftest test-a-blank-docstring-is-not-documentation
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "blank.clj")
        result (support/eval! env "(defn blank \"  \" [] 1)")]
    (t/is (str/includes? (:content result) "live-only"))
    (t/is (not (fs/exists? path)))))

(t/deftest test-builtin-help-is-generated-from-factory-metadata
  (let [env (support/eval-ctx)]
    (t/is (str/includes? (:content (support/eval! env "(eval/help 'eval/library)"))
                         "library verb ([]) (built-in)"))
    (t/is (str/includes? (:content (support/eval! env "(eval/help 'eval/library)"))
                         "List every loaded library definition"))
    (t/is (str/includes? (:content (support/eval! env "(eval/help 'eval/result)"))
                         "result verb ([] [id] [id {:keys [offset limit]}]"))
    (t/is (str/includes? (:content (support/eval! env "(eval/help 'clj/nrepl-eval)"))
                         "nrepl-eval verb ([{:keys [code port host ns timeout]}])"))))

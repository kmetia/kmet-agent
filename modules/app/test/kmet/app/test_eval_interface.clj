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
            [kmet.app.tools.registry :as tools]))

(defn- records [code]
  (:defs (defs/parse code "user")))

(defn- record-for [code name]
  (first (filter #(= name (:name %)) (records code))))

(defn- interface-of [code name]
  (select-keys (record-for code name) [:doc :arglists]))

(defn- describe-map [env form]
  (edn/read-string (:content (support/eval! env (str "(emit " form ")")))))

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
              :doc "Render a report."
              :arglists '([x])
              :file path
              :scope :cwd
              :source source}
             described))
    (t/is (= described (describe-map env "(eval/describe 'user/report nil)"))
          "a nil key returns the full map")
    (t/is (= described (describe-map env "(eval/describe 'report)"))
          "an unqualified name resolves in the sticky namespace")
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'report :doc)"))
                         "Render a report."))
    (t/is (= "([x])"
             (:content (support/eval! env "(emit (eval/describe 'report :arglists))"))))
    (t/is (= ":defn"
             (:content (support/eval! env "(emit (eval/describe 'report :kind))"))))
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'report :source)"))
                         source))
    (let [unknown (support/eval! env "(eval/describe 'user/nope)")]
      (t/is (:is-error unknown))
      (t/is (str/includes? (:content unknown) "no eval definition named")))))

(t/deftest test-an-existing-library-file-gains-its-interface
  (let [env (support/eval-ctx)
        path (str (fs/path (:agent-dir env) "eval" "user" "legacy.clj"))]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns user)\n(defn legacy \"Legacy doc.\" [x] x)\n")
    (support/eval! env "(emit :loaded)")
    (t/is (= "Legacy doc."
             (:content (support/eval! env "(eval/describe 'legacy :doc)"))))
    (t/is (= "([x])"
             (:content (support/eval! env
                                      "(emit (eval/describe 'legacy :arglists))"))))))

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
                                      "(emit (eval/describe (symbol \"user/area :rect\") :arglists))"))))
    (t/is (= "(defmethod area :rect [m] :rect)"
             (:content (support/eval! env
                                      "(eval/describe (symbol \"user/area :rect\") :source)"))))
    (support/eval! env "(declare early late)")
    (t/is (= ":declare"
             (:content (support/eval! env "(emit (eval/describe 'early :kind))"))))
    (t/is (= "(declare early late)"
             (:content (support/eval! env "(eval/describe 'late :source)"))))))

(t/deftest test-describe-reports-load-interface-and-help
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn documented \"Doc line.\\nMore.\" [x] x)")
    (support/eval! env "(defn undocumented [x] x)")
    (let [library (:content (support/eval! env "(eval/library)"))
          help (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? library ":doc \"Doc line.\\nMore.\""))
      (t/is (str/includes? library ":arglists ([x])"))
      (t/is (str/includes? help "user/documented defn ([x]) (cwd) — Doc line."))
      (t/is (str/includes? help "user/undocumented defn ([x]) (cwd) — (no docstring)"))
      (t/is (= "true"
               (:content (support/eval! env
                                        "(emit (nil? (eval/describe 'undocumented :doc)))")))))))

(t/deftest test-help-omits-the-definition-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(def plain 1)")
    (let [help (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? help "user/plain def (cwd) — (no docstring)"))
      (t/is (not (str/includes? help "plain.clj"))))))

(t/deftest test-an-unwritten-definition-is-described
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "lost.clj")
        failed (with-redefs [eval-files/write-definition!
                             (fn [& _] (throw (ex-info "disk full" {:type :test})))]
                 (support/eval! env "(defn lost \"Lost doc.\" [x] x)"))]
    (t/is (:is-error failed))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(emit (nil? (eval/describe 'user/lost :file)))"))))
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
                                      "(emit (nil? (eval/describe 'stable :file)))"))))
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
                                      "(emit (nil? (eval/describe 'user/boom :file)))"))))
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
                  :ns {'license {'add! (with-meta
                                         (fn [_] :added)
                                         {:doc "Add a header."
                                          :arglists '([path])
                                          :source "(fn [path] ...)"})
                                 'bare (fn [] :bare)}}
                  :macros {'license {'twice (with-meta
                                              (fn [_ _ form] (list 'do form form))
                                              {:doc "Twice."
                                               :arglists '([form])})}}}))
    (let [described (describe-map env "(eval/describe 'license/add!)")]
      (t/is (= "license/add!" (:name described)))
      (t/is (= :verb (:kind described)))
      (t/is (= "Add a header." (:doc described)))
      (t/is (= '([path]) (:arglists described)))
      (t/is (nil? (:file described)))
      (t/is (nil? (:scope described)))
      (t/is (= "(fn [path] ...)" (:source described))))
    (t/is (= "[\"Add a header.\" ([path])]"
             (:content (support/eval! env
                                      "(emit [(-> (meta #'license/add!) :doc) (-> (meta #'license/add!) :arglists)])"))))
    (t/is (= ":macro"
             (:content (support/eval! env "(emit (eval/describe 'license/twice :kind))"))))
    (t/is (= "true"
             (:content (support/eval! env
                                      "(emit (nil? (eval/describe 'license/bare :source)))"))))
    (let [help (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? help "license/add! ([path]) — Add a header."))
      (t/is (str/includes? help "license/bare — (no docstring)")))
    (let [sources (:content (support/eval! env "(eval/sources)"))]
      (t/is (str/includes? sources ":license"))
      (t/is (str/includes? sources "license/add!"))
      (t/is (str/includes? sources "(no docstring)")))
    (t/is (= ":verb"
             (:content (support/eval! env "(emit (eval/describe 'user/edit :kind))"))))
    (t/is (str/includes? (:content (support/eval! env "(eval/describe 'user/edit :source)"))
                         "edit-file"))))

(t/deftest test-core-ships-read-file
  (let [env (support/eval-ctx)]
    (t/is (= "([path] [path offset] [path offset limit])"
             (:content (support/eval! env "(emit (eval/describe 'user/read-file :arglists))"))))
    (t/is (str/includes?
           (:content (support/eval! env "(eval/describe 'user/read-file :source)"))
           "read-tool/execute"))
    (t/is (str/includes? (:content (support/eval! env "(eval/help)"))
                         "user/read-file ([path] [path offset] [path offset limit])"))))

(t/deftest test-an-undocumented-save-says-no-docstring
  (let [env (support/eval-ctx)
        result (support/eval! env "(defn undocumented [] 1)")]
    (t/is (str/includes? (:content result)
                         "[eval: defined user/undocumented (cwd) → "))
    (t/is (str/includes? (:content result) "(no docstring)"))
    (let [documented (support/eval! env "(defn documented \"Doc.\" [] 1)")]
      (t/is (not (str/includes? (:content documented) "(no docstring)"))))))

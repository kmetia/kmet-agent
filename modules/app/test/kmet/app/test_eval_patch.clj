(ns kmet.app.test-eval-patch
  "`(eval/patch 'name old new)`: structural subform replacement that
   re-evaluates before it writes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]))

(defn- register!
  [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-patch id]}))

(defn- definition-file [env]
  (support/library-file env :cwd "user" "greet.clj"))

(t/deftest patch-updates-the-live-definition-and-the-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [result (support/eval! env
                                (str "(eval/patch 'user/greet "
                                     "'(str \"Hello \" x) '(str \"Hi \" x))"))]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: patched user/greet")))
    (t/is (= "Hi A" (:content (support/eval! env "(greet \"A\")"))))
    (let [content (slurp (definition-file env))]
      (t/is (str/includes? content "(str \"Hi \" x)"))
      (t/is (not (str/includes? content "(str \"Hello \" x)"))))))

(t/deftest patch-replaces-a-value-form
  (let [env (support/eval-ctx)]
    (support/eval! env "(def answer \"Answer.\" 1)")
    (let [content (:content (support/eval! env "(eval/patch 'user/answer 1 42) answer"))]
      (t/is (str/includes? content "[eval: patched user/answer"))
      (t/is (str/starts-with? content "42")))
    (t/is (str/includes? (slurp (support/library-file env :cwd "user" "answer.clj"))
                         "(def answer \"Answer.\" 42)"))))

(t/deftest patch-not-found-leaves-file-and-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (support/eval! env
                                "(eval/patch 'user/greet '(missing-form) '(other))")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "no subform"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest patch-refuses-a-changed-definition-name
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (support/eval! env
                                (str "(eval/patch 'user/greet "
                                     "'(defn greet \"Greet.\" [x] (str \"Hello \" x)) "
                                     "'(defn other [x] x))"))]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "changed the definition name"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest patch-restores-the-live-definition-when-re-evaluation-fails
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (support/eval! env
                                (str "(eval/patch 'user/greet "
                                     "'(defn greet \"Greet.\" [x] (str \"Hello \" x)) "
                                     "'(def greet \"Greet.\" (throw (ex-info \"boom\" {}))))"))]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "patch evaluation failed"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest patch-is-scoped-to-the-named-definition
  (let [env (support/eval-ctx)
        path (str (fs/path (:agent-dir env) "eval" "user" "pair.clj"))]
    (fs/create-dirs (fs/parent path))
    (spit path ";; kmet eval: written by the eval tool\n(ns user)\n(def a \"A.\" 1)\n(def b \"B.\" 2)\n")
    (t/is (= "1" (support/script-content (support/eval! env "a"))))
    (let [before (slurp path)
          result (support/eval! env "(eval/patch 'user/a 2 3)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "no subform"))
      (t/is (= before (slurp path))))
    (support/eval! env "(eval/patch 'user/a 'user 1)")
    (t/is (str/includes? (slurp path) "(def b \"B.\" 2)"))
    (t/is (= "2" (:content (support/eval! env "b")))
          "a patch to a never reaches a sibling definition")))

(t/deftest patch-a-defmethod-body
  (let [env (support/eval-ctx)]
    (support/eval! env (str "(defmulti area \"Area.\" :shape)\n"
                            "(defmethod area :rect [m] :old)"))
    (let [result (support/eval! env
                                "(eval/patch \"user/area :rect\" :old :new)")]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (= ":new" (:content (support/eval! env "(area {:shape :rect})"))))))

(t/deftest patch-refuses-an-unwritten-live-definition
  (let [env (support/eval-ctx)]
    (with-redefs [eval-files/write-definition!
                  (fn [& _] (throw (ex-info "disk full" {})))]
      (t/is (:is-error (support/eval! env "(defn greet \"Greet.\" [x] x)"))))
    (let [result (support/eval! env "(eval/patch 'user/greet 1 2)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "not written")))))

(t/deftest patch-write-failure-keeps-the-old-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (with-redefs [eval-files/write-content!
                               (fn [& _] (throw (ex-info "disk full" {})))]
                   (support/eval! env "(eval/patch 'user/greet \"Hello \" \"Hi \")"))]
      (t/is (:is-error result))
      (t/is (= before (slurp path)))
      (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")")))
            "a failed patch write restores the old live definition"))))

(t/deftest patch-refuses-a-source-provided-key
  (let [env (support/eval-ctx)]
    (register! env :license (fn [_] {:ns {'license {'value 1}}}))
    (t/is (= "1" (:content (support/eval! env "(ns license) value"))))
    (let [result (support/eval! env "(eval/patch 'license/value 1 2)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "source-provided"))
      (t/is (= "1" (:content (support/eval! env "(ns license) value")))))))

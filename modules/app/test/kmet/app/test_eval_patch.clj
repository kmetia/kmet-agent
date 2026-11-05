(ns kmet.app.test-eval-patch
  "`(eval/patch 'name old new)`: structural subform replacement that
   re-evaluates before it writes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]))

(defn- register!
  [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-patch id]}))

(defn- definition-file [env]
  (str (fs/path (:agent-dir env) "eval" "user" "greet.clj")))

(t/deftest patch-updates-the-live-definition-and-the-file
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
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
    (support/eval! env "(def answer 1)")
    (t/is (= "42" (:content (support/eval! env "(eval/patch 'user/answer 1 42) answer"))))
    (t/is (str/includes? (slurp (str (fs/path (:agent-dir env)
                                              "eval" "user" "answer.clj")))
                         "(def answer 42)"))))

(t/deftest patch-not-found-leaves-file-and-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
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
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (support/eval! env
                                (str "(eval/patch 'user/greet "
                                     "'(defn greet [x] (str \"Hello \" x)) "
                                     "'(defn other [x] x))"))]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "changed the definition name"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest patch-restores-the-live-definition-when-re-evaluation-fails
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (support/eval! env
                                (str "(eval/patch 'user/greet "
                                     "'(defn greet [x] (str \"Hello \" x)) "
                                     "'(def greet (throw (ex-info \"boom\" {}))))"))]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "patch evaluation failed"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest patch-refuses-a-source-provided-key
  (let [env (support/eval-ctx)]
    (register! env :license (fn [_] {:ns {'license {'value 1}}}))
    (t/is (= "1" (:content (support/eval! env "(ns license) value"))))
    (let [result (support/eval! env "(eval/patch 'license/value 1 2)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "source-provided"))
      (t/is (= "1" (:content (support/eval! env "(ns license) value")))))))

(ns kmet.app.test-eval-edit-form
  "`(eval/edit-form 'name new-source)`: whole-definition replacement that
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
                               {:owner [:test :eval-edit-form id]}))

(defn- definition-file [env]
  (str (fs/path (:agent-dir env) "eval" "user" "greet.clj")))

(defn- edit-form! [env name source]
  (support/eval! env (str "(eval/edit-form '" name " " (pr-str source) ")")))

(t/deftest edit-form-updates-the-live-definition-and-the-file
  (let [env (support/eval-ctx)
        source "(defn greet [x] (str \"Hi \" x))"]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [result (edit-form! env "user/greet" source)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: edited user/greet")))
    (t/is (= "Hi A" (:content (support/eval! env "(greet \"A\")"))))
    (t/is (str/includes? (slurp (definition-file env)) source))))

(t/deftest edit-form-failed-evaluation-keeps-file-and-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet"
                             "(def greet (throw (ex-info \"boom\" {})))")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "edit-form evaluation failed"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-a-changed-definition-name
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet" "(defn other [x] x)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "changed the definition name"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-invalid-source
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet" "(defn greet [x]")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "cannot parse replacement"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-a-source-provided-key
  (let [env (support/eval-ctx)
        _ (register! env :license (fn [_] {:ns {'license {'value 1}}}))]
    (t/is (= "1" (:content (support/eval! env "license/value"))))
    (let [result (edit-form! env "license/value" "(def value 2)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "source-provided")))))

(t/deftest edit-form-write-failure-restores-the-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)]
      (with-redefs [eval-files/write-content!
                    (fn [& _] (throw (ex-info "disk full" {})))]
        (let [result (edit-form! env "user/greet" "(defn greet [x] \"new\")")]
          (t/is (:is-error result))
          (t/is (str/includes? (:content result) "edit-form write failed"))))
      (t/is (= before (slurp path)))
      (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")")))))))

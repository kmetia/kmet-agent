(ns kmet.app.test-eval-edit-form
  "`(eval/edit-form 'name new-source)`: whole-definition replacement that
   re-evaluates before it writes."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]))

(defn- register!
  [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-edit-form id]}))

(defn- definition-file [env]
  (support/library-file env :cwd "user" "greet.clj"))

(defn- edit-form! [env name source]
  (support/eval! env (str "(eval/edit-form '" name " " (pr-str source) ")")))

(t/deftest edit-form-updates-the-live-definition-and-the-file
  (let [env (support/eval-ctx)
        source "(defn greet \"Greet.\" [x] (str \"Hi \" x))"]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [result (edit-form! env "user/greet" source)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: edited user/greet")))
    (t/is (= "Hi A" (:content (support/eval! env "(greet \"A\")"))))
    (t/is (str/includes? (slurp (definition-file env)) source))
    (t/is (some #(= "user/greet" (:name %)) (support/eval-library env)))))

(t/deftest edit-form-accepts-a-form-as-well-as-source
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [result (support/eval! env
                                "(eval/edit-form 'user/greet '(defn greet \"Greet.\" [x] (str \"Hey \" x)))")]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (= "Hey A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-failed-evaluation-keeps-file-and-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet"
                             "(def greet \"Greet.\" (throw (ex-info \"boom\" {})))")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "edit-form evaluation failed"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-a-changed-definition-name
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet" "(defn other [x] x)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "changed the definition name"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-invalid-source
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet" "(defn greet [x]")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "cannot parse replacement"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

(t/deftest edit-form-refuses-a-source-provided-key
  (let [env (support/eval-ctx)
        _ (register! env :license (fn [_] {:ns {'ext.license {'value 1}}}))]
    (t/is (= "1" (:content (support/eval! env "ext.license/value"))))
    (let [result (edit-form! env "ext.license/value" "(def value 2)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "source-provided")))))

(t/deftest edit-form-write-failure-restores-the-live-definition
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)]
      (with-redefs [eval-files/write-content!
                    (fn [& _] (throw (ex-info "disk full" {})))]
        (let [result (edit-form! env "user/greet" "(defn greet \"New.\" [x] \"new\")")]
          (t/is (:is-error result))
          (t/is (str/includes? (:content result) "edit-form write failed"))))
      (t/is (= before (slurp path)))
      (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")")))))))

(t/deftest edit-form-refuses-a-docstringless-result
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn greet \"Greet.\" [x] (str \"Hello \" x))")
    (let [path (definition-file env)
          before (slurp path)
          result (edit-form! env "user/greet" "(defn greet [x] (str \"Hey \" x))")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "without a docstring"))
      (t/is (= before (slurp path))))
    (t/is (= "Hello A" (:content (support/eval! env "(greet \"A\")"))))))

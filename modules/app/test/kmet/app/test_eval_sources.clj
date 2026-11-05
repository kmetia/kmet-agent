(ns kmet.app.test-eval-sources
  "Eval sources: per-call values, the core file verbs, source-key ownership,
   refusal and unload."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]))

(defn- register!
  [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-source id]}))

(defn- source-errors [service]
  (filter :error ((:sources service))))

(t/deftest a-source-is-read-on-the-next-call
  (let [env (support/eval-ctx)]
    (support/eval! env ":warm")
    (register! env :license
               (fn [{:keys [cwd project-dir agent-dir signal verbs]}]
                 {:doc "License helpers"
                  :ns {'license {'where (fn [] cwd)
                                 'project (fn [] project-dir)
                                 'agent-home (fn [] agent-dir)
                                 'signalled? (fn [] (some? signal))
                                 'has-verbs? (fn [] (and (fn? (:edit verbs))
                                                         (fn? (:write-file verbs))))}}}))
    (t/is (= (:cwd env)
             (:content (support/eval! env "(license/where)"))))
    (t/is (= (str (fs/path (:cwd env) ".kmet"))
             (:content (support/eval! env "(license/project)"))))
    (t/is (= (:agent-dir env)
             (:content (support/eval! env "(license/agent-home)"))))
    (t/is (= "false" (:content (support/eval! env "(license/signalled?)"))))
    (t/is (= "true" (:content (support/eval! env "(license/has-verbs?)"))))))

(t/deftest source-verbs-match-the-file-tools
  (let [env (support/eval-ctx)
        _ (register! env :license
                     (fn [{:keys [verbs]}]
                       {:ns {'license {'write! (fn [path content]
                                                 ((:write-file verbs) path content))
                                       'edit! (fn [path old new]
                                                ((:edit verbs) path old new))}}}))]
    (support/eval! env "(license/write! \"source.txt\" \"alpha\")")
    (support/eval! env "(user/write-file \"tool.txt\" \"alpha\")")
    (t/is (= (slurp (str (:cwd env) "/source.txt"))
             (slurp (str (:cwd env) "/tool.txt"))))
    (support/eval! env "(license/edit! \"source.txt\" \"alpha\" \"beta\")")
    (support/eval! env "(user/edit \"tool.txt\" \"alpha\" \"beta\")")
    (t/is (= "beta" (slurp (str (:cwd env) "/source.txt"))))
    (t/is (= (slurp (str (:cwd env) "/source.txt"))
             (slurp (str (:cwd env) "/tool.txt"))))))

(t/deftest unloading-removes-source-names-and-keeps-model-definitions
  (let [env (support/eval-ctx)
        dereg (register! env :license
                         (fn [_]
                           {:ns {'license {'add! (fn [] :added)}}}))]
    (t/is (= ":added" (:content (support/eval! env "(license/add!)"))))
    (support/eval! env "(ns license) (def keep 5)")
    (dereg)
    (t/is (= "[false 5]"
             (:content (support/eval! env "(ns license) [(some? (resolve 'add!)) keep]"))))))

(t/deftest bad-sources-contribute-nothing-and-are-reported
  (let [env (support/eval-ctx)]
    (register! env :boom (fn [_] (throw (ex-info "boom" {}))))
    (register! env :bad-user (fn [_] {:ns {'user {'nope 1}}}))
    (register! env :bad-clj (fn [_] {:ns {'clj {'edit-form 1}}}))
    (register! env :bad-key (fn [_] {:ns {'eval {'library 1}}}))
    (register! env :bad-command (fn [_] {:commands {:list {:run (fn [_] nil)}}}))
    (let [result (support/eval! env "(+ 1 2)")
          errors (source-errors (:service env))
          ids (set (map :id errors))]
      (t/is (= "3" (:content result)))
      (t/is (= #{:boom :bad-user :bad-clj :bad-key :bad-command} ids))
      (t/is (seq (get-in result [:details :source-errors])))
      (t/is (= "false" (:content (support/eval! env "(some? (resolve 'user/nope))"))))
      (t/is (nil? (get (support/eval! env "(eval/sources)") :id))))))

(t/deftest a-model-redefinition-shadows-a-source-value
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:ns {'license {'value 1}}}))
    (let [result (support/eval! env "(ns license) (def value 2) value")
          path (str (fs/path (:agent-dir env) "eval" "license" "value.clj"))]
      (t/is (str/starts-with? (:content result) "2"))
      (t/is (str/includes? (:content result)
                           "[eval: redefined license/value (source; not stored)]"))
      (t/is (not (fs/exists? path))))
    (t/is (= "2" (:content (support/eval! env "(ns license) value"))))
    (let [result (support/eval! env "(ns license) (eval/forget 'value)")]
      (t/is (str/includes? (:content result) "[eval: forgot license/value]")))
    (t/is (= "1" (:content (support/eval! env "(ns license) value")))
          "forget restores the source value")
    (t/is (not (fs/exists? (str (fs/path (:agent-dir env) "eval" "license" "value.clj")))))))

(t/deftest a-code-less-drop-restores-a-source-value
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:ns {'license {'value 1}}}))
    (support/eval! env "(ns license) (def value 2)")
    (let [result ((:drop! (:service env))
                  {:session (:session env) :drop ["license/value"]})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot license/value]")))
    (t/is (= "1" (:content (support/eval! env "(ns license) value"))))))

(t/deftest a-source-key-stays-non-storable-after-unload
  (let [env (support/eval-ctx)
        dereg (register! env :license
                         (fn [_] {:ns {'license {'value 1}}}))
        path (str (fs/path (:agent-dir env) "eval" "license" "value.clj"))]
    (support/eval! env "(ns license) (def value 3)")
    (dereg)
    (t/is (str/starts-with?
           (:content (support/eval! env "(ns license) (def value 4) value"))
           "4"))
    (t/is (not (fs/exists? path)))
    (support/eval! env "(ns license) (eval/forget 'value)")
    (t/is (= "false" (:content (support/eval! env "(ns license) (some? (resolve 'value))"))))))

(t/deftest source-commands-are-collected-with-their-description
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_]
                 {:commands {:license-report
                             {:description "Report licenses"
                              :run (fn [{:keys [args cwd]}]
                                     (str "args=" args " cwd=" cwd))}}}))
    (let [commands ((:source-commands (:service env)))
          command (:license-report commands)]
      (t/is (= "Report licenses" (:description command)))
      (t/is (= "args=x cwd=/tmp" ((:run command) {:args "x" :cwd "/tmp"}))))))

(t/deftest eval-sources-and-help-list-the-source-layer
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:doc "License helpers"
                        :ns {'license {'add! (fn [] :added)}}}))
    (let [sources (:content (support/eval! env "(eval/sources)"))
          help (:content (support/eval! env "(eval/help)"))]
      (t/is (str/includes? sources ":license"))
      (t/is (str/includes? sources "License helpers"))
      (t/is (str/includes? sources ":core"))
      (t/is (str/includes? help "Eval sources"))
      (t/is (str/includes? help "Library definitions")))))

(t/deftest a-source-can-contribute-an-explicit-macro
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_]
                 {:macros {'license {'twice (fn [_ _ form] (list 'do form form))}}}))
    (t/is (= "3" (:content (support/eval! env "(license/twice (+ 1 2))"))))))

(t/deftest source-values-are-read-on-every-call
  (let [current (atom 1)
        env (support/eval-ctx)]
    (register! env :counter
               (fn [_]
                 {:ns {'counter {'value @current}}}))
    (t/is (= "1" (:content (support/eval! env "counter/value"))))
    (reset! current 2)
    (t/is (= "2" (:content (support/eval! env "counter/value"))))))

(ns kmet.app.test-eval-sources
  "Eval sources: per-call values, the built-in file verbs, source-key ownership,
   refusal and unload."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.libs.concurrent :as concurrent]))

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
                  :ns {'ext.license {'where (fn [] cwd)
                                     'project (fn [] project-dir)
                                     'agent-home (fn [] agent-dir)
                                     'signalled? (fn [] (some? signal))
                                     'has-verbs? (fn [] (and (fn? (:edit verbs))
                                                             (fn? (:write-file verbs))
                                                             (fn? (:read-file verbs))))}}}))
    (t/is (= (:cwd env)
             (:content (support/eval! env "(ext.license/where)"))))
    (t/is (= (str (fs/path (:cwd env) ".kmet"))
             (:content (support/eval! env "(ext.license/project)"))))
    (t/is (= (:agent-dir env)
             (:content (support/eval! env "(ext.license/agent-home)"))))
    ;; the combined run/abort view is always present for an eval call
    (t/is (= "true" (:content (support/eval! env "(ext.license/signalled?)"))))
    (t/is (= "true" (:content (support/eval! env "(ext.license/has-verbs?)"))))))

(t/deftest source-verbs-match-the-file-tools
  (let [env (support/eval-ctx)
        _ (register! env :license
                     (fn [{:keys [verbs]}]
                       {:ns {'ext.license {'write! (fn [path content]
                                                     ((:write-file verbs) path content))
                                           'edit! (fn [path old new]
                                                    ((:edit verbs) path old new))}}}))]
    (support/eval! env "(ext.license/write! \"source.txt\" \"alpha\")")
    (support/eval! env "(core/write \"tool.txt\" \"alpha\")")
    (t/is (= (slurp (str (:cwd env) "/source.txt"))
             (slurp (str (:cwd env) "/tool.txt"))))
    (support/eval! env "(ext.license/edit! \"source.txt\" \"alpha\" \"beta\")")
    (support/eval! env "(core/edit \"tool.txt\" \"alpha\" \"beta\")")
    (t/is (= "beta" (slurp (str (:cwd env) "/source.txt"))))
    (t/is (= (slurp (str (:cwd env) "/source.txt"))
             (slurp (str (:cwd env) "/tool.txt"))))))

(t/deftest user-read-shares-the-read-tool
  (let [env (support/eval-ctx)]
    (spit (str (fs/path (:cwd env) "lines.txt")) "alpha\nbeta\ngamma")
    (t/is (= "beta\ngamma"
             (:content (support/eval! env "(core/read \"lines.txt\" {:offset 2 :limit 2})"))))
    (let [missing (support/eval! env "(core/read \"nope.txt\")")]
      (t/is (:is-error missing))
      (t/is (str/includes? (:content missing) "File not found")))))

(t/deftest unloading-removes-source-names-and-keeps-model-definitions
  (let [env (support/eval-ctx)
        dereg (register! env :license
                         (fn [_]
                           {:ns {'ext.license {'add! (fn [] :added)}}}))]
    (t/is (= ":added" (:content (support/eval! env "(ext.license/add!)"))))
    (support/eval! env "(ns ext.license) (def keep 5)")
    (dereg)
    (t/is (= "[false 5]"
             (:content (support/eval! env "(ns ext.license) [(some? (resolve 'add!)) keep]"))))))

(t/deftest a-source-may-contribute-only-to-its-extension-namespace (let [env (support/eval-ctx)] (register! env :license (fn [_] {:ns {(quote nope) {(quote value) 1}}})) (let [result (support/eval! env "(+ 1 2)") errors (source-errors (:service env))] (t/is (= "3" (:content result))) (t/is (some (fn [error] (str/includes? (:error error) "may contribute only to ext.license")) errors)))))

(t/deftest bad-sources-contribute-nothing-and-are-reported
  (let [env (support/eval-ctx)]
    (register! env :boom (fn [_] (throw (ex-info "boom" {}))))
    (register! env :bad-user (fn [_] {:ns {'user {'nope 1}}}))
    (register! env :bad-clj (fn [_] {:ns {'clj {'edit-form 1}}}))
    (register! env :bad-macro (fn [_] {:macros {'user {'nope (fn [_ _] nil)}}}))
    (register! env :bad-key (fn [_] {:ns {'eval {'library 1}}}))
    (register! env :bad-fixed (fn [_] {:ns {'tools {'call 1}}}))
    (register! env :bad-http (fn [_] {:ns {'kmet.libs.http {'get 1}}}))
    (register! env :bad-command (fn [_] {:commands {:list {:run (fn [_] nil)}}}))
    (register! env :bad-move (fn [_] {:commands {:move {:run (fn [_] nil)}}}))
    (register! env :bad-describe
               (fn [_] {:commands {:describe {:run (fn [_] nil)}}}))
    (register! env :bad-reload
               (fn [_] {:commands {:reload {:run (fn [_] nil)}}}))
    (let [result (support/eval! env "(+ 1 2)")
          errors (source-errors (:service env))
          ids (set (map :id errors))]
      (t/is (= "3" (:content result)))
      (t/is (= #{:boom :bad-user :bad-clj :bad-macro :bad-key
                 :bad-fixed :bad-http :bad-command :bad-move :bad-describe
                 :bad-reload}
               ids))
      (t/is (seq (get-in result [:details :source-errors])))
      (t/is (str/includes? (pr-str (:details result)) "may not contribute to clj"))
      (t/is (str/includes? (pr-str (:details result)) "may not contribute to eval"))
      (t/is (str/includes? (pr-str (:details result))
                           "may not contribute to kmet.libs.http"))
      (t/is (= "false" (:content (support/eval! env "(some? (resolve 'user/nope))"))))
      (t/is (nil? (get (support/eval! env "(eval/sources)") :id))))))

(t/deftest a-model-redefinition-shadows-a-source-value
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:ns {'ext.license {'value 1}}}))
    (let [result (support/eval! env "(ns ext.license) (def value 2) value")
          path (support/library-file env :cwd "ext.license" "value.clj")]
      (t/is (str/starts-with? (:content result) "2"))
      (t/is (str/includes? (:content result)
                           "[eval: redefined ext.license/value (source; not stored)]"))
      (t/is (not (fs/exists? path))))
    (t/is (= "2" (:content (support/eval! env "(ns ext.license) value"))))
    (let [result (support/eval! env "(ns ext.license) (eval/forget 'value)")]
      (t/is (str/includes? (:content result) "[eval: restored ext.license/value (source)]")))
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value")))
          "forget restores the source value")
    (t/is (not (fs/exists? (support/library-file env :cwd "ext.license" "value.clj"))))))

(t/deftest a-code-less-drop-restores-a-source-value
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:ns {'ext.license {'value 1}}}))
    (support/eval! env "(ns ext.license) (def value 2)")
    (let [result ((:drop! (:service env))
                  {:session (:session env) :drop ["ext.license/value"]})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: restored ext.license/value (source)]")))
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value"))))))

(t/deftest a-library-file-and-a-source-value-share-a-key-safely
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "ext.license" "value.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(ns ext.license)\n(def value 2)\n")
    (register! env :license
               (fn [_] {:ns {'ext.license {'value 1}}}))
    (t/is (= "2" (support/script-content (support/eval! env "(ns ext.license) value")))
          "the saved definition wins at build")
    (let [result (support/eval! env nil :drop ["ext.license/value"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "[eval: forgot ext.license/value (cwd); source restored]")))
    (t/is (not (fs/exists? path)))
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value"))))
    (let [restarted (support/restart-service env)]
      (t/is (= "1"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(ns ext.license) value")))))))

(t/deftest a-source-with-two-library-layers-restores-records-before-the-source
  (let [env (support/eval-ctx)]
    (doseq [[scope value] [[:global 2] [:cwd 3]]]
      (let [path (support/library-file env scope "ext.license" "value.clj")]
        (fs/create-dirs (fs/parent path))
        (spit path (str "(ns ext.license)\n(def value " value ")\n"))))
    (register! env :license
               (fn [_] {:ns {'ext.license {'value 1}}}))
    (t/is (= "3" (support/script-content (support/eval! env "(ns ext.license) value"))))
    (let [result (support/eval! env nil :drop ["ext.license/value"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot ext.license/value (cwd)]")))
    (t/is (= "2" (support/script-content (support/eval! env "(ns ext.license) value")))
          "the lower record is restored before the source value")
    (let [result (support/eval! env nil :drop ["ext.license/value"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result)
                           "[eval: forgot ext.license/value (global); source restored]")))
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value"))))))

(t/deftest a-source-key-stays-non-storable-after-unload
  (let [env (support/eval-ctx)
        dereg (register! env :license
                         (fn [_] {:ns {'ext.license {'value 1}}}))
        path (support/library-file env :cwd "ext.license" "value.clj")]
    (support/eval! env "(ns ext.license) (def value 3)")
    (dereg)
    (t/is (str/starts-with?
           (:content (support/eval! env "(ns ext.license) (def value 4) value"))
           "4"))
    (t/is (not (fs/exists? path)))
    (support/eval! env "(ns ext.license) (eval/forget 'value)")
    (t/is (= "false" (:content (support/eval! env "(ns ext.license) (some? (resolve 'value))"))))))

(t/deftest a-scoped-forget-of-a-source-key-still-restores-it
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:ns {'ext.license {'value 1}}}))
    (support/eval! env "(ns ext.license) (def value 2)")
    (let [result (support/eval! env "(ns ext.license) (eval/forget 'value)"
                                :scope :global)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: restored ext.license/value (source)]")))
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value"))))))

(t/deftest a-code-less-drop-does-not-restore-an-unloaded-source
  (let [env (support/eval-ctx)
        dereg (register! env :license
                         (fn [_] {:ns {'ext.license {'value 1}}}))]
    (t/is (= "1" (:content (support/eval! env "(ns ext.license) value"))))
    (dereg)
    (let [result ((:drop! (:service env))
                  {:session (:session env)
                   :drop ["ext.license/value"]
                   :cwd (:cwd env)})]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (= "false"
             (:content (support/eval! env
                                      "(ns ext.license) (some? (resolve 'value))")))
          "the unloaded source's live value is gone, not restored")))

(t/deftest source-commands-are-collected-with-their-description
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_]
                 {:commands {:license-report
                             {:description "Report licenses"
                              :run (fn [{:keys [args cwd]}]
                                     (str "args=" args " cwd=" cwd))}
                             :foo/list
                             {:description "Namespaced verb"
                              :run (fn [_] "namespaced")}}}))
    (let [commands ((:source-commands (:service env)))
          command (:license-report commands)]
      (t/is (= "Report licenses" (:description command)))
      (t/is (= "args=x cwd=/tmp" ((:run command) {:args "x" :cwd "/tmp"})))
      (t/is (= "namespaced" ((:run (:foo/list commands)) {}))))))

(t/deftest eval-sources-and-help-list-the-source-layer
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_] {:doc "License helpers"
                        :ns {'ext.license {'add! (fn [] :added)}}}))
    (let [sources (:content (support/eval! env "(eval/sources)"))
          help (:content (support/eval! env "(eval/help)"))
          core (:content (support/eval! env "(eval/help :core)"))]
      (t/is (str/includes? sources ":license"))
      (t/is (str/includes? sources "License helpers"))
      (t/is (not (str/includes? sources ":core")))
      (t/is (str/includes? help "Eval sources"))
      (t/is (str/includes? help "Library:"))
      (t/is (str/includes? help "Built-in verbs:"))
      (t/is (str/includes? core "Core:"))
      (t/is (str/includes? core "(emit value)"))
      (t/is (str/includes? core "(core/emit value)")))))

(t/deftest a-source-can-contribute-an-explicit-macro
  (let [env (support/eval-ctx)]
    (register! env :license
               (fn [_]
                 {:macros {'ext.license {'twice (fn [_ _ form] (list 'do form form))}}}))
    (t/is (= "3" (:content (support/eval! env "(ext.license/twice (+ 1 2))"))))))

(t/deftest source-values-are-read-on-every-call
  (let [current (atom 1)
        reads (atom 0)
        env (support/eval-ctx)]
    (register! env :counter
               (fn [_]
                 (swap! reads inc)
                 {:ns {'ext.counter {'value @current}}}))
    (t/is (= "1" (:content (support/eval! env "ext.counter/value"))))
    (t/is (= 1 @reads) "the build's read is reused by the first call")
    (reset! current 2)
    (t/is (= "2" (:content (support/eval! env "ext.counter/value"))))
    (t/is (= 2 @reads))))

(t/deftest the-first-call-s-signal-reaches-the-build-source
  (let [env (support/eval-ctx)
        run-signal (atom false)
        captured (atom nil)]
    (register! env :probe
               (fn [{:keys [signal]}]
                 (reset! captured signal)
                 {:ns {'ext.probe {'warm (fn [] "warm")}}}))
    ;; the build source receives a live combined view
    (t/is (= "warm"
             (:content (support/eval! env "(ext.probe/warm)"
                                      :signal run-signal))))
    (t/is (some? @captured))
    ;; firing the run signal after the call is visible through the captured
    ;; build-time view
    (reset! run-signal true)
    (t/is (true? @(deref captured)))))

(t/deftest ^:slow eval-timeout-reaches-a-source-s-signal
  (let [env (support/eval-ctx)
        seen (atom nil)]
    (register! env :probe
               (fn [{:keys [signal]}]
                 (reset! seen signal)
                 {:ns {'ext.probe {'wait!
                                   (fn []
                                     (let [deadline (+ (concurrent/monotonic-ms) 5000)]
                                       (loop []
                                         (when-not @signal
                                           (when (< (concurrent/monotonic-ms) deadline)
                                             (Thread/sleep 10)
                                             (recur))))))}}}))
    (let [r (support/eval! env "(ext.probe/wait!)" :timeout 0.1)]
      (t/is (:is-error r))
      (t/is (= :timeout (get-in r [:details :error]))))
    (t/is (true? @(deref seen))
          "the source saw the script timeout signal")))

(t/deftest sources-are-reported-only-when-the-mounted-set-changes
  (let [env (support/eval-ctx)]
    (register! env :one (fn [_] {:ns {'ext.one {'x (fn [] 1)}}}))
    (let [first (support/eval! env "1")
          second (support/eval! env "1")]
      (t/is (= #{:one} (set (map :id (get-in first [:details :sources])))))
      (t/is (empty? (get-in second [:details :sources]))))
    (register! env :two (fn [_] {:ns {'ext.two {'y (fn [] 2)}}}))
    (let [third (support/eval! env "1")
          fourth (support/eval! env "1")]
      (t/is (= #{:one :two} (set (map :id (get-in third [:details :sources])))))
      (t/is (empty? (get-in fourth [:details :sources]))))))

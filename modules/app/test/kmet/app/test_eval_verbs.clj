(ns kmet.app.test-eval-verbs
  "Eval verb selection: disabled built-in and source verbs are stubs, the help
   surface omits them, and a /config write rebuilds the next environment."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.config :as cfg]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as resources]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]))

(defn- selection
  "A resources service whose `:verb` selection is read from DISABLED."
  [disabled]
  {:selected-names
   (fn [_kind names]
     (into #{} (remove #(contains? @disabled %)) names))})

(defn- register!
  [env id sources-fn]
  (tools/register-eval-source! (:tools env) id sources-fn
                               {:owner [:test :eval-source id]}))

(deftest disabled-builtin-verbs-are-stubs-and-hidden
  (let [disabled (atom #{"core/read"})
        env (support/eval-ctx {:resources (selection disabled)})]
    (let [result (support/eval! env "(read \"nope.txt\")")]
      (is (:is-error result))
      (is (str/includes? (:content result)
                         "verb core/read is disabled")))
    (is (= "3" (:content (support/eval! env "(+ 1 2)")))
        "the prelude still resolves every referred core verb")
    (let [help (:content (support/eval! env "(help)"))]
      (is (not (str/includes? help "read ([path]")))
      (is (str/includes? help "write ([")))
    (let [result (support/eval! env "(describe 'read)")]
      (is (:is-error result))
      (is (str/includes? (:content result) "is disabled")))))

(deftest disabled-eval-verbs-are-stubs
  (let [disabled (atom #{"eval/help" "eval/library"})
        env (support/eval-ctx {:resources (selection disabled)})]
    (doseq [[code name] [["(help)" "eval/help"]
                         ["(library)" "eval/library"]]]
      (let [result (support/eval! env code)]
        (is (:is-error result))
        (is (str/includes? (:content result)
                           (str "verb " name " is disabled")))))
    (is (= "3" (:content (support/eval! env "(+ 1 2)")))
        "the eval refer prelude still resolves every eval verb")))

(deftest source-verbs-group-by-the-registering-extension
  (let [env (support/eval-ctx {:resources (selection (atom #{}))})]
    (tools/register-eval-source! (:tools env) :mcp
                                 (fn [_]
                                   {:ns {'ext.mcp {'search (fn [] :ok)}}})
                                 {:owner [:app :extensions "mcp-adapter"]})
    (let [catalog ((:verbs (:service env)) {:cwd (:cwd env)})]
      (is (= {:kind :extension :id :ext/mcp-adapter}
             (get-in catalog ["ext.mcp/search" :provider]))
          "the group is the extension, not the source id")
      (is (= "mcp/search"
             (get-in catalog ["ext.mcp/search" :display-name]))))))

(deftest disabled-source-verbs-are-stubs-and-hidden
  (let [disabled (atom #{"ext.demo/drop"})
        env (support/eval-ctx {:resources (selection disabled)})
        _ (register! env :demo
                     (fn [_]
                       {:doc "Demo helpers"
                        :ns {'ext.demo {'keep (fn [] :kept)
                                        'drop (fn [] :dropped)}
                             'ext.demo.internal
                             {'nested (fn [] :nested)}}}))]
    (is (= ":kept" (:content (support/eval! env "(demo/keep)"))))
    (let [result (support/eval! env "(demo/drop)")]
      (is (:is-error result))
      (is (str/includes? (:content result)
                         "verb ext.demo/drop is disabled")))
    (let [sources (:content (support/eval! env "(eval/sources)"))]
      (is (str/includes? sources "keep"))
      (is (not (str/includes? sources "drop"))))
    (let [catalog ((:verbs (:service env)) {:cwd (:cwd env)})]
      (is (= "demo/drop" (get-in catalog ["ext.demo/drop" :display-name])))
      (is (= {:kind :extension :id :ext/demo}
             (get-in catalog ["ext.demo/drop" :provider])))
      (is (= "demo.internal/nested"
             (get-in catalog ["ext.demo.internal/nested" :display-name])))
      (is (= "read" (get-in catalog ["core/read" :display-name]))))))

(deftest a-real-selection-gates-the-sandbox
  (let [global (atom {})
        project (atom nil)
        resources (resources/service
                   {:tools (registry/atom-registry)
                    :skills (registry/atom-registry)
                    :prompts (registry/atom-registry)})]
    (with-redefs [cfg/read-global-settings-map (fn [] @global)
                  cfg/read-project-settings-map (fn [] @project)
                  cfg/save-setting! (fn [path value]
                                      (swap! global assoc-in path value)
                                      value)]
      (is (true? ((:selected? resources) :verb "core/read")))
      ((:set-global! resources) :verb "core/read" false)
      (is (false? ((:selected? resources) :verb "core/read")))
      (let [env (support/eval-ctx {:resources resources})
            result (support/eval! env "(read \"nope.txt\")")]
        (is (:is-error result))
        (is (str/includes? (:content result)
                           "verb core/read is disabled"))))))

(deftest a-verb-write-rebuilds-the-next-eval
  (let [disabled (atom #{})
        env (support/eval-ctx {:resources (selection disabled)})]
    (support/eval! env "(def live-value :ok)")
    (is (= ":ok" (:content (support/eval! env "live-value"))))
    (swap! disabled conj "core/read")
    ((:refresh-verbs! (:service env)))
    (is (= ":ok" (:content (support/eval! env "live-value")))
        "the rebuilt environment replays session-only definitions")
    (let [result (support/eval! env "(read \"nope.txt\")")]
      (is (:is-error result))
      (is (str/includes? (:content result)
                         "verb core/read is disabled")))))

(ns kmet.app.test-front-end-gates
  "Landing 8's gate tests: what a front end may rely on at the composition
   level, not the widget wiring (the modes' own tests stub the handle).

   A front end creates its agent through `:agents` and never owns it: the
   agent is a child of the ctx the front end mounts on, `:agent/created` says
   so, a settings write touches the agent it was called with and no other, two
   agents settle their own runs on their own contexts, and an image prompt
   reaches the model as an image block while a queued image is refused."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.session.runtime :as runtime]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]
            [kmet.app.events :as events]
            [kmet.app.runtime :as runtime-app]
            [kmet.app.test-support :as support]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

(def ^:private sessions-dir (test-tmp/temp-dir! "test-front-end-gates-"))

(def ^:private booted
  "The composition the current test runs against; the fixture owns it."
  (atom nil))

(defn- with-boot
  "Each test gets a fresh booted `:tui` composition — the real rows, so
   `:agents` is the loop driver's registry and the handles it creates are the
   ones a front end reads — and the fixture is the teardown a test cannot
   forget."
  [f]
  (fs/create-dirs sessions-dir)
  (let [boot (runtime-app/boot (core/compose-config {:profile :tui}) {})]
    (reset! booted boot)
    (try
      (f)
      (finally
        (runtime-app/shutdown! boot)
        (fs/delete-tree sessions-dir)
        (reset! booted nil)))))

(t/use-fixtures :each with-boot)

(defn- config-for
  [model]
  {:provider :opencode-go
   :model model
   :session-dir sessions-dir})

(defn- register-test-provider!
  "A provider of the test's own, so a model switch resolves through the
   catalog the run's ExtensionContext reads — the gate is a write a handler
   can see, not just a stored id."
  []
  (models/register-provider!
   (models/map->Provider {:id :gates-test
                          :name "Gates"
                          :api-types #{:openai-completions}
                          :models [{:id "gates-a" :name "Gates A" :provider :gates-test}
                                   {:id "gates-b" :name "Gates B" :provider :gates-test}
                                   {:id "gates-c" :name "Gates C" :provider :gates-test}]
                          :env-vars []
                          :default-model "gates-a"})))

(deftest a-created-agent-is-in-the-tree-and-announced
  (testing "a front end's agent is a child context of the ctx it mounted on —
            the `[:app :agent/<id>]` line --dump-state prints — and
            :agent/created announces it"
    (let [ctx (:ctx @booted)
          agents (srce/get ctx :agents)
          heard (atom [])
          off (events/on! ctx :agent/created (fn [ev] (swap! heard conj ev)))]
      (try
        (let [handle ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})]
          (is (= [(:id handle)] (mapv :id @heard))
              "the composition hears the agent it created, once")
          (is (contains? (set (map :name (srce/children ctx)))
                         (keyword "agent" (:id handle)))
              "the agent lives under the ctx the front end mounts on")
          (is (= (:id handle) (get-in ((:list agents) ctx) [0 :id]))
              "and the registry lists it"))
        (finally (off))))))

(deftest a-settings-write-touches-only-its-agent
  (testing "two agents of one composition hold their own model and thinking
            level; a write leaves the sibling's state and the context its
            handlers would build alone"
    (register-test-provider!)
    (try
      (let [ctx (:ctx @booted)
            agents (srce/get ctx :agents)
            gates-config (fn [model] {:provider :gates-test :model model
                                      :session-dir sessions-dir})
            a ((:create! agents) ctx {:config (gates-config "gates-a")})
            b ((:create! agents) ctx {:config (gates-config "gates-b")})
            b-thinking (runtime/thinking-level b)]
        (is (= "gates-b" (get-in (runtime/extension-context b) [:model :id]))
            "sanity: the sibling's context resolves its model")
        ((:set-model! agents) a (models/get-model :gates-test "gates-c"))
        (is (= "gates-c" (runtime/model a)))
        (is (= "gates-c" (get-in (runtime/extension-context a) [:model :id]))
            "the context a handler builds names the model the agent was given")
        (is (= "gates-b" (runtime/model b)) "the sibling's model is untouched")
        (is (= "gates-b" (get-in (runtime/extension-context b) [:model :id]))
            "and so is the context the sibling's handlers would build")
        ((:set-thinking-level! agents) a 5)
        (is (= 5 (runtime/thinking-level a)))
        (is (= 5 (get-in (runtime/extension-context a) [:thinking-level])))
        (is (= b-thinking (get-in (runtime/extension-context b) [:thinking-level]))
            "the sibling's thinking level is unchanged"))
      (finally (models/unregister-provider! :gates-test)))))

(deftest each-agent-settles-on-its-own-context
  (testing "two runs in one composition settle their own promises: a listener
            on an agent's ctx hears that agent only"
    (let [ctx (:ctx @booted)
          agents (srce/get ctx :agents)
          a ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})
          b ((:create! agents) ctx {:config (config-for "deepseek-v4-pro")})
          settled-a (promise)
          settled-b (promise)
          off-a (events/on! (:ctx a) :agent-settled (fn [ev] (deliver settled-a ev)))
          off-b (events/on! (:ctx b) :agent-settled (fn [ev] (deliver settled-b ev)))]
      (try
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (fn [opts]
                                         (future
                                           (when-let [done (:on-done opts)]
                                             (done :stop))))]
          ((:send! agents) a "to a" {:target :next-turn})
          ((:send! agents) b "to b" {:target :next-turn})
          (is (= :completed (:outcome (deref settled-a 20000 ::timeout))))
          (is (= :completed (:outcome (deref settled-b 20000 ::timeout))))
          (let [user-text (fn [h]
                            (->> @(:messages (:agent h))
                                 (filter #(= :user (:role %)))
                                 (mapv (comp :text first :content))))]
            (is (= ["to a"] (user-text a)) "each turn is the agent's own context")
            (is (= ["to b"] (user-text b)))))
        (finally
          (off-a)
          (off-b))))))

(deftest an-image-prompt-reaches-the-model
  (testing "an image sent with a prompt is an image block on the request the
            model sees; a queued image is refused by name rather than dropped"
    (let [ctx (:ctx @booted)
          agents (srce/get ctx :agents)
          handle ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})
          requests (atom [])
          image {:type :image :data "aGVsbG8=" :mime-type "image/png"}]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [opts]
                                       (swap! requests conj opts)
                                       (future
                                         (when-let [done (:on-done opts)]
                                           (done :stop))))]
        ;; the prompt is a message map when images ride along (the editor's
        ;; path), a plain string for text-only sends
        ((:send! agents) handle {:text "look at this" :images [image]}
                         {:target :next-turn})
        (is (support/wait-until #(seq @requests)) "the model was called")
        (let [content (-> @requests first :messages last :content)]
          (is (some #(= "aGVsbG8=" (:data %)) content)
              "the provider request carries the image bytes")
          (is (some #(= "look at this" (:text %)) content)
              "and the text rides with it"))
        (is (= :kmet.app/unsupported-images
               (try
                 ((:send! agents) handle {:text "queued" :images [image]}
                                  {:target :steering})
                 :none
                 (catch Exception e (:type (ex-data e)))))
            "a queued message carries text, so an image may only start a prompt")))))

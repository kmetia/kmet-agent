(ns kmet.app.test-tools
  "The `:tools` service: installed by one `provide!`, mutated through its
   handle, visible to a sibling row, and gone with the fiber."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.registries :as registries]
            [kmet.app.registry :as registry]
            [kmet.srce.core :as srce]))

(defn- mounted
  "The root ctx and the tools row's fiber."
  []
  (let [ctx (srce/root)
        fiber (srce/plugin! ctx registries/tools)]
    [ctx fiber]))

(deftest the-service-is-provided-once-and-mutated-through-its-handle
  (let [[ctx _] (mounted)
        handle (srce/get ctx :tools)]
    (is (satisfies? registry/Registry handle) "the service value is a handle")
    (testing "registration is a handle method, not a re-provide"
      (registry/register! handle :kmet/tool "probe" {:name "probe"} :test)
      (is (= {:name "probe"} (registry/entry (srce/get ctx :tools) :kmet/tool "probe"))
          "the same service value answers, so nothing was re-provided"))
    (testing "a row mounted below sees it"
      (let [child (srce/extend ctx {:name :agent-1})]
        (is (identical? handle (srce/get child :tools)))))
    (testing "a sibling row reads it through its own ctx"
      (let [sibling (srce/plugin! ctx {:name :consumer
                                       :inject {:tools nil}
                                       :apply (fn [_ _] nil)})]
        (is (= :active (:state @sibling)) "the inject gate is satisfied")
        (srce/dispose! sibling)))))

(deftest disposing-the-row-removes-the-service
  (let [[ctx fiber] (mounted)
        handle (srce/get ctx :tools)]
    (registry/register! handle :kmet/tool "probe" {:name "probe"} :test)
    (srce/dispose! fiber)
    (is (nil? (srce/get ctx :tools))
        "the provide! entry is an effect of the row, so the ctx can no longer reach it")
    (is (= :disposed (:state @fiber))
        "and the row that owned it is gone — the handle's atom with it, once the last reference drops")))

(deftest each-composition-gets-its-own-handle
  (let [[a _] (mounted)
        [b _] (mounted)]
    (is (not (identical? (srce/get a :tools) (srce/get b :tools)))))
  (is (not (identical? (registries/service) (registries/service)))))

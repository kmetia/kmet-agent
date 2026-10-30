(ns kmet.app.test-registries
  "The four registry rows — `:tools`, `:commands`, `:prompts`, `:skills` — as
   one shape: a handle provided into the app scope, empty at mount, mutated
   through the handle, and no tag bleeding between them."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]))

(defn- mounted
  "The root ctx with every registry row mounted."
  []
  (let [ctx (srce/root)]
    (doseq [row [registries/tools registries/commands registries/prompts registries/skills]]
      (srce/plugin! ctx row))
    ctx))

(deftest each-row-provides-its-own-empty-handle
  (let [ctx (mounted)]
    (doseq [service [:tools :commands :prompts :skills]]
      (testing (name service)
        (let [handle (srce/get ctx service)]
          (is (satisfies? registry/Registry handle) "the service value is a handle")
          (is (empty? (registry/entries handle :kmet/tool)) "it mounts empty"))))
    (is (not (identical? (srce/get ctx :tools) (srce/get ctx :commands)))
        "one handle per seam, so a per-agent override stays per-seam")))

(deftest registration-goes-through-the-handle-not-a-re-provide
  (let [ctx (mounted)
        commands (srce/get ctx :commands)]
    (registry/register! commands :kmet/command "units" {:name "units"} :test)
    (is (= {:name "units"} (registry/entry (srce/get ctx :commands) :kmet/command "units")))
    (is (empty? (registry/entries (srce/get ctx :tools) :kmet/command))
        "another seam's handle does not see it")))

(deftest a-row-below-sees-the-services
  (let [ctx (mounted)
        child (srce/extend ctx {:name :agent-1})]
    (doseq [service [:tools :commands :prompts :skills]]
      (is (identical? (srce/get ctx service) (srce/get child service))
          "a child ctx reads it through the chain"))))

(deftest disposing-a-row-removes-its-service
  (let [ctx (srce/root)
        fiber (srce/plugin! ctx registries/commands)]
    (is (some? (srce/get ctx :commands)))
    (srce/dispose! fiber)
    (is (nil? (srce/get ctx :commands)) "the provide! entry was the row's effect")))

(deftest a-row-builds-a-fresh-handle
  (is (not (identical? (registries/service) (registries/service)))))

(ns kmet.app.test-world
  "The process world loads once, in order, and publishes an entry's
   composition as the process root."
  (:require [clojure.test :as t]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.models :as models]
            [kmet.app.root :as root]
            [kmet.app.world :as world]
            [kmet.srce.core :as srce]
            [kmet.libs.theme :as theme]))

(t/deftest the-world-loads-in-order-and-publishes-its-root
  (let [calls (atom [])
        ctx (srce/root {:name :app})
        prev (root/reset-root!)]
    (try
      ;; the loaders take the agent dir the world passes them (the host's
      ;; root); the stub records the call and ignores the argument
      (with-redefs [models/load-catalogs! (fn [_] (swap! calls conj :catalogs))
                    image-models/load-image-catalogs! (fn [_] (swap! calls conj :image-catalogs))
                    models/load-models-config! (fn [_] (swap! calls conj :models-config))
                    models/get-model-config-error (constantly nil)
                    cfg/init! (fn [] (swap! calls conj :config) {:model "x"})
                    packages/resolve-theme-paths (constantly ["/t.edn"])
                    theme/load-theme-paths! (fn [paths] (swap! calls conj [:themes paths]))
                    extensions/set-cli-flags!
                    (fn [flags] (swap! calls conj [:flags flags]))]
        (t/is (= {:model "x"} (world/load! {:ext-flags {"probe" "1"}} ctx))
              "the loaded config is the world load's answer"))
      (t/is (= [:catalogs :image-catalogs :models-config :config
                [:themes ["/t.edn"]]
                [:flags {"probe" "1"}]]
               @calls)
            "catalogs first, then config (which publishes the root), then
             themes, then the flags; extensions are composition rows, not a
             world load")
      (t/is (identical? ctx (root/ctx)) "the entry's composition is the process root")
      (finally
        (srce/dispose-subtree! ctx)
        (root/restore-root! prev)))))

(t/deftest an-entry-without-a-composition-publishes-nothing
  (let [prev (root/reset-root!)]
    (try
      (with-redefs [models/load-catalogs! (constantly nil)
                    image-models/load-image-catalogs! (constantly nil)
                    models/load-models-config! (constantly nil)
                    models/get-model-config-error (constantly nil)
                    cfg/init! (constantly {})
                    packages/resolve-theme-paths (constantly [])
                    theme/load-theme-paths! (constantly nil)
                    extensions/set-cli-flags! (constantly nil)]
        (world/load! {}))
      (t/is (nil? (root/ctx))
            "a world load with no ctx publishes nothing")
      (finally
        (root/restore-root! prev)))))

(t/deftest the-world-is-a-row-that-loads-into-its-root-and-provides-it
  ;; The world is a plugin like any other: mounting it loads the process world
  ;; into the ctx it is mounted under and provides what it loaded, which is how
  ;; the entry (and only the entry) gets the config it hands the mode.
  (let [prev (root/reset-root!)
        calls (atom [])]
    (try
      (with-redefs [models/load-catalogs! (constantly nil)
                    image-models/load-image-catalogs! (constantly nil)
                    models/load-models-config! (constantly nil)
                    models/get-model-config-error (constantly nil)
                    cfg/init! (fn [] (swap! calls conj :config) {:model "x"})
                    packages/resolve-theme-paths (constantly [])
                    theme/load-theme-paths! (constantly nil)
                    extensions/get-flag (constantly nil)
                    extensions/set-cli-flags! #(swap! calls conj [:flags %])]
        (let [ctx (srce/root {:name :app})]
          (try
            (let [fiber (srce/plugin! ctx world/plugin
                                      {:invocation {:ext-flags {"probe" "1"}}})]
              (t/is (= :active (:state @fiber)))
              (t/is (= {:config {:model "x"}} (srce/get ctx :world))
                    "the row provides what the load answered, on the root")
              (t/is (identical? ctx (root/ctx))
                    "and the load published that root as the process tree")
              (t/is (= [[:flags {"probe" "1"}]] (filter vector? @calls))
                    "the row's config is where the invocation's flags come from"))
            (finally (srce/dispose-subtree! ctx)))))
      (finally (root/restore-root! prev)))))

(t/deftest the-world-row-refuses-a-mount-under-an-agent
  ;; A preset names a bundle, and `:bundle/minimal` carries this row. The world
  ;; is the process's, so mounting it under an agent's ctx must fail loudly
  ;; rather than load a second process world inside one agent.
  (let [prev (root/reset-root!)]
    (try
      (let [ctx (srce/root {:name :app})
            agent (srce/extend ctx {:name :agent})
            fiber (srce/plugin! agent world/plugin nil)]
        (try
          (t/is (= :failed (:state @fiber)) "the row does not mount")
          (t/is (= :kmet.app/world-not-root (:type (ex-data (:error @fiber))))
                "and says why, naming the boundary")
          (t/is (nil? (srce/get ctx :world)) "nothing was provided")
          (finally (srce/dispose-subtree! ctx))))
      (finally (root/restore-root! prev)))))

(t/deftest the-first-published-root-wins
  (let [prev (root/reset-root!)
        ours (srce/root {:name :app})
        other (srce/root {:name :other})]
    (try
      (t/is (identical? ours (root/publish! ours)))
      (t/is (identical? ours (root/publish! other))
            "a second publish cannot strand the tree the process is reading")
      (t/is (identical? ours (root/ctx)))
      (finally
        (srce/dispose-subtree! ours)
        (srce/dispose-subtree! other)
        (root/restore-root! prev)))))

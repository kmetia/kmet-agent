(ns kmet.app.loop.test-stages
  "The loop's stage map: what a ctx carries, what a run reads, and what a
   reload re-seats."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.loop.stages :as stages]
            [kmet.srce.core :as srce]))

(deftest a-composition-provides-the-stages
  (let [ctx (srce/root)
        replacement (fn [& _] :replacement)]
    (srce/provide! ctx :retry-policy {:call replacement :config {:max-retries 2}})
    (is (= #{:loop-guard :retry-policy} (set (keys (stages/base ctx)))))
    (is (identical? replacement (get-in (stages/base ctx) [:retry-policy :call]))
        "the mounted row is the implementation the loop reads")
    (is (= {:max-retries 2} (get-in (stages/base ctx) [:retry-policy :config])))
    (is (= (get-in stages/defaults [:loop-guard :call])
           (get-in (stages/base ctx) [:loop-guard :call]))
        "a stage with no row keeps its builtin")
    (is (= (stages/base ctx) (stages/for-ctx ctx))
        "a ctx with no run-seat of its own reads the composition")
    (testing "a run's own map still wins whole"
      (let [child (srce/extend ctx {:loop-stages (atom {:loop-guard {:call :own}})})]
        (is (= {:loop-guard {:call :own}} (stages/for-ctx child)))))))

(deftest the-stage-rows-provide-from-the-mount-sites-scope
  (let [world (srce/root)
        guard (get-in stages/defaults [:loop-guard :call])
        seen (atom ::unseen)
        probe {:name :probe
               :apply (fn [ctx _config] (reset! seen (srce/get ctx :loop-guard)))}]
    (srce/plugin! world #'stages/loop-guard-row nil)
    (srce/plugin! world #'stages/retry-policy-row {:max-retries 1})
    (srce/plugin! world probe)
    (is (= {:call guard} @seen)
        (str "a sibling row reads the stage from the shared scope — the mount "
             "site, which is where the loop and every agent ctx below it look"))
    (is (= {:max-retries 1} (:config (srce/get world :retry-policy)))
        "and a row's :config is the stage's config")
    (is (= (get-in stages/defaults [:retry-policy :call])
           (get-in (stages/base world) [:retry-policy :call]))
        "base composes both rows' values over their defaults")
    (testing "a config the stage cannot read is refused, not silently ignored"
      (is (thrown-with-msg? Exception #":config must be a map"
                            ((stages/apply-stage :loop-guard)
                             (srce/extend world {}) [:threshold 3]))))))

(deftest the-defaults-are-the-two-stages-the-loop-reads
  (is (= #{:loop-guard :retry-policy} (set (keys stages/defaults))))
  (is (every? (comp fn? :call val) stages/defaults))
  (is (every? (comp nil? :config val) stages/defaults)
      "the builtin stages carry no config: a run adds its own"))

(deftest a-ctxs-stage-map-rides-the-ctx-chain
  (testing "the nearest atom wins, and it is read whole — no per-stage merge"
    (let [parent (srce/root {:loop-stages (atom {:loop-guard {:call :parent}})})
          child (srce/extend parent {:loop-stages (atom {:retry-policy {:call :child}})})
          sibling (srce/extend parent {})]
      (is (= {:retry-policy {:call :child}} (stages/for-ctx child))
          "a ctx that carries a map with its own is the map; merging over the
           defaults is `with-defaults`, which the ctx builders call")
      (is (= {:loop-guard {:call :parent}} (stages/for-ctx sibling))
          "a child with no atom of its own reads the parent's")))
  (testing "a ctx with no stage atom anywhere, or none at all, reads the defaults"
    (is (= stages/defaults (stages/for-ctx (srce/root))))
    (is (= stages/defaults (stages/for-ctx nil))
        "a hand-built state reads the builtin stages")))

(deftest a-refresh-re-seats-only-the-ctx-it-names
  (let [changed (fn [& _] :changed)
        parent (srce/root {:loop-stages (atom {:loop-guard {:call :parent}})})
        child (srce/extend parent {:loop-stages (atom {:loop-guard {:call :child}})})]
    (try
      (with-redefs [stages/defaults (assoc stages/defaults :loop-guard {:call changed})]
        (stages/refresh! child)
        (is (identical? changed (get-in (stages/for-ctx child) [:loop-guard :call]))
            "the target's own atom is re-seated")
        (is (= :parent (get-in (stages/for-ctx parent) [:loop-guard :call]))
            "and nothing up the chain is reached — for-ctx inherits, a refresh names its target")
        (testing "a ctx that carries no stage atom is left alone"
          (stages/refresh! (srce/extend parent {}))
          (stages/refresh! nil)
          (is (= :parent (get-in (stages/for-ctx parent) [:loop-guard :call])))))
      (finally (stages/refresh! child)))
    (is (not (identical? changed (get-in (stages/for-ctx child) [:loop-guard :call])))
        "the real stage is back")))

(deftest with-defaults-merges-stage-by-stage
  ;; The two ctx builders (the legacy CLI's process shim and the loop test support) both
  ;; seat a run's stages over the builtin map; this is that one operation.
  (let [merged (stages/with-defaults {:retry-policy {:config {:max-retries 1}}})]
    (is (= {:max-retries 1} (get-in merged [:retry-policy :config]))
        "the override lands on its stage")
    (is (fn? (get-in merged [:retry-policy :call])) "and the default handle survives")
    (is (= (get-in stages/defaults [:loop-guard :call])
           (get-in merged [:loop-guard :call]))
        "a stage the override does not name keeps its default")
    (is (= (get-in stages/defaults [:loop-guard :call])
           (get-in (stages/with-defaults nil) [:loop-guard :call]))
        "and no override is the defaults")))
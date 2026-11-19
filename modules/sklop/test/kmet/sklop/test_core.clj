(ns kmet.sklop.test-core
  "kmet.sklop.core — the composition model: bundle stacking, patch by row id,
   config deep-merge, insert/remove, the errors a bad layer must raise, the
   --dump-config rendering, and a mount with a caller-supplied source policy."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.sklop.core :as sklop]
            [kmet.srce.core :as srce]))

;; Rows arrive described (the caller's describe policy fills :id/:name from
;; the source); these fixtures are already cards.
(def read-row
  {:id :tool/read :plugin 'sklop.test/read :name :tool/read
   :inject {} :provide [] :config {}})

(def write-row
  {:id :tool/write :plugin 'sklop.test/write :name :tool/write
   :inject {} :provide [] :config {}})

(def bash-row
  {:id :tool/bash :plugin 'sklop.test/bash :name :tool/bash
   :inject {:tools nil} :provide [:tool/bash]
   :config {:shell {:args ["-c"]}}})

(defn- error-type
  "The :type of the exception F throws, or nil."
  [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(defn- ids [composed]
  (mapv :id (:rows composed)))

(deftest layers-apply-in-order
  (let [table {:bundles {:bundle/a {:rows [read-row]}
                         :bundle/b {:rows [write-row]}}
               :profiles {:p {:bundles [[:bundle/a {}] [:bundle/b {}]]
                              :rows [bash-row]
                              :patch []}}}
        layers (sklop/layers table :p)]
    (is (= 3 (count layers)) "one layer per bundle, then the profile's own")
    (is (= [:tool/read :tool/write :tool/bash]
           (ids (sklop/compose layers)))
        "bundles in stack order, profile rows last")))

(deftest patch-merges-config-by-row-id
  (let [composed (sklop/compose
                  [{:rows [bash-row]}
                   {:patch [{:id :tool/bash
                             :config {:shell {:env {"A" "1"}} :timeout 100}}]}])]
    (is (= {:shell {:args ["-c"] :env {"A" "1"}} :timeout 100}
           (:config (first (:rows composed))))
        "config values deep-merge; the row is otherwise untouched")
    (is (= 1 (:patches composed)))))

(deftest patch-removes-a-row
  (is (= [:tool/read]
         (ids (sklop/compose [{:rows [read-row write-row]}
                              {:patch [{:id :tool/write :remove true}]}])))))

(deftest patch-inserts-by-anchor
  (testing "an unknown id with a :plugin inserts"
    (is (= [:tool/read :tool/ls :tool/write]
           (ids (sklop/compose [{:rows [read-row write-row]}
                                {:patch [{:id :tool/ls :plugin 'sklop.test/ls
                                          :after :tool/read}]}]))))
    (is (= [:tool/ls :tool/read]
           (ids (sklop/compose [{:rows [read-row]}
                                {:patch [{:id :tool/ls :plugin 'sklop.test/ls
                                          :before :tool/read}]}]))))
    (is (= [:tool/read :tool/ls]
           (ids (sklop/compose [{:rows [read-row]}
                                {:patch [{:id :tool/ls :plugin 'sklop.test/ls}]}]))))))

(deftest patch-replaces-a-source
  (let [row (first (:rows (sklop/compose
                           [{:rows [read-row]}
                            {:patch [{:id :tool/read :plugin 'sklop.test/read2
                                      :name :tool/read2}]}])))]
    (is (= 'sklop.test/read2 (:plugin row)))
    (is (= :tool/read2 (:name row)))))

(deftest removing-an-already-removed-row-is-a-no-op
  (let [row {:id :tool/read :plugin 'sklop.test/read}
        composed (sklop/compose
                  [{:rows [row] :patch [{:id :tool/read :remove true}]}
                   {:patch [{:id :tool/read :remove true}]}])]
    (is (empty? (:rows composed)))
    (is (= 2 (:patches composed))))
  (testing "a config overlay on a removed row is a no-op"
    (let [composed (sklop/compose
                    [{:rows [{:id :tool/read :plugin 'sklop.test/read}]
                      :patch [{:id :tool/read :remove true}]}
                     {:patch [{:id :tool/read :config {:x 1}}]}])]
      (is (empty? (:rows composed)))))
  (testing "an id that was never there is still an error"
    (is (= :kmet.sklop/unknown-row
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/nope :remove true}]}]))))))

(deftest layer-predicate
  (is (sklop/layer? {}) "both keys may be absent")
  (is (sklop/layer? {:rows [] :patch []}))
  (is (sklop/layer? {:rows (list {:id :tool/read :plugin 'sklop.test/read})})
      "a sequential collection is a layer, as the run reads it")
  (is (not (sklop/layer? {:rows [] :patch [] :schema 1}))
      "an extra top-level key is not a layer")
  (is (not (sklop/layer? {:rows 42})))
  (is (not (sklop/layer? [{:id :tool/read :plugin 'sklop.test/read}]))))

(deftest lenient-layers-drop-unknown-row-patches
  (testing "a stale remove/config patch is dropped and reported, not raised"
    (let [composed (sklop/compose
                    [{:rows [read-row]}
                     {:lenient? true :path "project/patch.edn"
                      :patch [{:id :tool/run_code :remove true}
                              {:id :tool/nope :config {:a 1}}]}])]
      (is (= [:tool/read] (ids composed)))
      (is (= [{:missing :tool/run_code
               :patch {:id :tool/run_code :remove true} :path "project/patch.edn"}
              {:missing :tool/nope
               :patch {:id :tool/nope :config {:a 1}} :path "project/patch.edn"}]
             (:dropped composed)))))
  (testing "an unknown insert anchor is dropped too"
    (let [composed (sklop/compose
                    [{:lenient? true :path "project/patch.edn"
                      :patch [{:id :tool/ls :plugin 'sklop.test/ls :after :tool/nope}]}])]
      (is (empty? (:rows composed)))
      (is (= [{:missing :tool/nope
               :patch {:id :tool/ls :plugin 'sklop.test/ls :after :tool/nope}
               :path "project/patch.edn"}]
             (:dropped composed)))))
  (testing "a strict layer keeps raising"
    (is (= :kmet.sklop/unknown-row
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/nope :remove true}]}]))))))

(deftest bad-patches-throw
  (testing "an unknown row targeted by a config-only patch"
    (is (= :kmet.sklop/unknown-row
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/nope :config {}}]}])))))
  (testing "an unknown insert anchor"
    (is (= :kmet.sklop/unknown-row
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/ls :plugin 'x/y :after :tool/nope}]}])))))
  (testing "a no-op patch"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose [{:patch [{:id :tool/read}]}])))))
  (testing "an unknown patch key"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/read :config {} :nope 1}]}])))))
  (testing "removing a row and changing its config at once"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/read :remove true :config {}}]}])))))
  (testing "after and before together"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/ls :plugin 'x/y
                                    :after :tool/read :before :tool/write}]}])))))
  (testing "an anchor on a patch that replaces an existing row"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose
                         [{:rows [read-row]}
                          {:patch [{:id :tool/read :config {:a 1}
                                    :after :tool/write}]}])))))
  (testing "removing a row and replacing its source at once"
    (is (= :kmet.sklop/bad-patch
           (error-type #(sklop/compose
                         [{:patch [{:id :tool/read :remove true
                                    :plugin 'x/y}]}]))))))

(deftest composition-errors-throw
  (testing "duplicate ids across stacked bundles"
    (is (= :kmet.sklop/duplicate-row
           (error-type #(sklop/compose [{:rows [read-row]}
                                        {:rows [read-row]}])))))
  (testing "a row without an id"
    (is (= :kmet.sklop/row-without-id
           (error-type #(sklop/compose [{:rows [{:plugin 'x/y}]}]))))))

(deftest layers-name-errors
  (is (= :kmet.sklop/unknown-profile
         (error-type #(sklop/layers {:profiles {}} :nope))))
  (is (= :kmet.sklop/unknown-bundle
         (error-type #(sklop/layers {:bundles {}
                                     :profiles {:p {:bundles [[:nope {}]]}}}
                                    :p))))
  (is (= :kmet.sklop/bad-bundle-entry
         (error-type #(sklop/layers {:bundles {:bundle/a {:rows []}}
                                     :profiles {:p {:bundles [:bundle/a]}}}
                                    :p)))
      "a bundle entry is [:bundle/name layer?], not a bare keyword"))

(deftest a-bundle-stack-can-patch-its-bundle
  (testing "the [:bundle/x extra] second element is a layer applied right after"
    (is (= {:shell {:args ["-c"]} :timeout 5}
           (:config (first (:rows
                            (sklop/compose
                             (sklop/layers
                              {:bundles {:bundle/a {:rows [bash-row]}}
                               :profiles {:p {:bundles [[:bundle/a
                                                         {:patch [{:id :tool/bash
                                                                   :config {:timeout 5}}]}]]}}}
                              :p)))))))))

(deftest render-prints-the-composition
  (let [text (sklop/render :tui
                           {:rows [bash-row
                                   {:id :ext/grep-tool :plugin :ext/grep-tool
                                    :name :ext/grep-tool}]
                            :patches 2})]
    (is (str/starts-with? text "profile :tui rows=2 patches=2\n"))
    (is (str/includes? text ":tool/bash"))
    (is (str/includes? text "sklop.test/bash"))
    (is (str/includes? text "inject={:tools nil} provide=[:tool/bash] config={:shell {:args [\"-c\"]}}"))
    (is (str/includes? text ":ext/grep-tool  :ext/grep-tool")
        "an :ext/… row prints its source and card like any other row")
    (is (not (str/includes? text "name=:tool/bash")) "the id is the name")
    (is (str/ends-with? text "\n"))))

;; ── the engine without an application's source policy ──────────────────────

(def alpha-plugin
  "A plugin map a generic composition can name by a keyword source."
  {:name :sklop/alpha
   :apply (fn [_ _] nil)})

(def beta-plugin
  "A second plugin map, so the test has a two-row profile."
  {:name :sklop/beta
   :provide [:beta]
   :apply (fn [ctx _]
            (srce/provide! ctx :beta true {:in (:parent ctx)}))})

(defn- source-plugin
  "The caller's mount policy: map a row's source keyword to a plugin map."
  [row]
  (get {:alpha alpha-plugin :beta beta-plugin} (:plugin row)))

(defn- source-card
  "The caller's describe policy: the card a plugin map declares."
  [row]
  (select-keys (source-plugin row) [:name :inject :provide]))

(deftest a-caller-supplied-policy-composes-and-mounts
  ;; This is the second-consumer test: compose + srce only, no app runtime,
  ;; catalog, events or extension rows.
  (let [table {:bundles {:bundle/base {:rows [{:id :alpha :plugin :alpha}]}}
               :profiles {:demo {:bundles [[:bundle/base {}]]
                                 :rows [{:id :beta :plugin :beta :config {:n 2}}]
                                 :patch [{:id :alpha :config {:n 1}}]}}}
        layers (sklop/layers table :demo)
        described (mapv #(sklop/describe-layer % {:describe-source source-card})
                        layers)
        {:keys [rows patches]} (sklop/compose described)
        ctx (srce/root {:name :sklop-test})
        fibers (sklop/mount-rows! ctx rows {:resolve-source source-plugin
                                            :timeout-ms 1000})]
    (try
      (is (= [:sklop/alpha :sklop/beta]
             (mapv (comp :name deref) fibers)))
      (is (= 1 patches))
      (is (= {:n 1} (:config (first rows)))
          "the patch deep-merged the bundle row's config")
      (is (= {:n 2} (:config (second rows))))
      (is (= [] (sklop/inactive-rows rows fibers)))
      (is (true? (srce/get ctx :beta)) "the second row provided into the root")
      (finally (srce/dispose-subtree! ctx)))))

(deftest a-caller-supplied-policy-owns-the-card
  (let [card (sklop/describe {:plugin :alpha :config {:x 1}}
                             {:describe-source (fn [_]
                                                 {:name :sklop/alpha
                                                  :inject [:tools]})})]
    (is (= :sklop/alpha (:id card)))
    (is (= {:tools nil} (:inject card))
        "the generic card normalizes the caller's inject declaration"))
  (is (= :row/alpha
         (:id (sklop/describe {:plugin :alpha}
                              {:describe-source (fn [_] {:name :sklop/alpha
                                                         :id :row/alpha})})))
      "a policy's card id wins over its name when the row names none"))

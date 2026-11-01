(ns kmet.app.extensions.test-layers
  "kmet.app.extensions.layers — the extension rows of the two ambient
   composition layers: what they compose, the per-scope state the config
   screen shows, and the writes its toggles make."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.layers :as layers]
            [kmet.app.profile :as profile]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-"))

(defn- with-layers
  "Run F with the two layer paths in a fresh temp dir: F gets the paths."
  [f]
  (let [dir (tmp-dir)
        global (str (fs/path dir "patch.edn"))
        project (str (fs/path dir "project-patch.edn"))]
    (with-redefs [cfg/global-patch-path (fn [] global)
                  cfg/project-patch-path (fn [] project)]
      (f {:global global :project project}))))

(defn- write-layer! [path data]
  (spit path (pr-str data))
  data)

(defn- read-layer [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(deftest composes-extension-rows-and-project-overrides
  (with-layers
    (fn [{:keys [global project]}]
      (write-layer! global {:rows [{:id :ext/a :plugin :ext/a}
                                   {:id :custom :plugin :ext/b}]
                            :patch [{:id :custom :remove true}]})
      (write-layer! project {:rows [{:id :custom-project :plugin :ext/c}]
                             :patch [{:id :ext/a :remove true}
                                     {:id :ext/b :plugin :ext/b}]})
      (let [composed (layers/composition (layers/layers))
            global-sources (layers/sources-of (:global composed))
            project-sources (layers/sources-of (:project composed))]
        (is (= #{:ext/a} global-sources)
            "a later row replaces an earlier one by id, and a remove patch drops it")
        (is (= #{:ext/b :ext/c} project-sources)
            "the project layer composes on top of the global one")
        (is (= {:enabled true :override-state :inherit :inherited-enabled true}
               (layers/item-state composed :ext/a :global)))
        (is (= {:enabled false :override-state :unload :inherited-enabled true}
               (layers/item-state composed :ext/a :project)))
        (is (= {:enabled false :override-state :inherit :inherited-enabled false}
               (layers/item-state composed :ext/b :global)))
        (is (= {:enabled true :override-state :load :inherited-enabled false}
               (layers/item-state composed :ext/c :project))
            "a project-only source inherits 'off', so its first toggle loads it")
        (is (= {:enabled true :override-state :load :inherited-enabled false}
               (layers/item-state composed :ext/b :project)))
        (is (= {:enabled false :override-state :inherit :inherited-enabled false}
               (layers/item-state composed :ext/missing :project)))))))

(deftest global-writers-compose-and-drop-a-row
  (with-layers
    (fn [{:keys [global]}]
      (testing "enable appends a row once"
        (is (true? (layers/enable-global! :ext/a)))
        (is (= {:rows [{:id :ext/a :plugin :ext/a}], :patch []}
               (read-layer global)))
        (is (false? (layers/enable-global! :ext/a)) "already composed"))
      (testing "an existing row for the source (any id) is not duplicated"
        (fs/delete global)
        (write-layer! global {:rows [{:id :mine :plugin :ext/a}] :patch []})
        (is (false? (layers/enable-global! :ext/a)))
        (is (= 1 (count (:rows (read-layer global))))))
      (testing "a patch-insert enablement is disabled the same way"
        (fs/delete global)
        (write-layer! global {:rows []
                              :patch [{:id :ext/a :plugin :ext/a :config {:y 1}}]})
        (is (true? (layers/disable-global! :ext/a)))
        (is (empty? (layers/sources-of
                     (:global (layers/composition (layers/layers))))))
        (is (true? (layers/enable-global! :ext/a)))
        (is (= [{:id :ext/a :plugin :ext/a :config {:y 1}}]
               (:patch (read-layer global)))
            "the insert and its config survive the cycle"))
      (testing "disable keeps the row and composes a remove patch"
        (fs/delete global)
        (write-layer! global {:rows [{:id :ext/a :plugin :ext/a
                                      :config {:x 1}}]
                              :patch []})
        (is (true? (layers/disable-global! :ext/a)))
        (is (= {:rows [{:id :ext/a :plugin :ext/a :config {:x 1}}]
                :patch [{:id :ext/a :remove true}]}
               (read-layer global)))
        (is (empty? (layers/sources-of
                     (:global (layers/composition (layers/layers))))))
        (is (false? (layers/disable-global! :ext/a)) "already off")
        (testing "enable drops the remove patch and keeps the config"
          (is (true? (layers/enable-global! :ext/a)))
          (is (= {:rows [{:id :ext/a :plugin :ext/a :config {:x 1}}]
                  :patch []}
                 (read-layer global)))
          (is (= #{:ext/a}
                 (layers/sources-of
                  (:global (layers/composition (layers/layers)))))))
        (testing "disable covers every row of the source, in composition order"
          (fs/delete global)
          (write-layer! global {:rows [{:id :first :plugin :ext/a}
                                       {:id :second :plugin :ext/a}]
                                :patch []})
          (is (true? (layers/disable-global! :ext/a)))
          (is (= [{:id :first :remove true} {:id :second :remove true}]
                 (:patch (read-layer global)))))))))

(deftest project-writers-compose-a-row-inherit-or-unload
  (with-layers
    (fn [{:keys [global project]}]
      (testing "load composes a project row when the global layer does not"
        (is (true? (layers/apply-project-state! :ext/a :load)))
        (is (= [{:id :ext/a :plugin :ext/a}]
               (:patch (read-layer project))))
        (is (= #{:ext/a}
               (layers/sources-of (:project (layers/composition (layers/layers))))))
        (is (true? (layers/apply-project-state! :ext/a :inherit)))
        (is (= [] (:patch (read-layer project)))))
      (testing "load is a no-op when the global layer already composes it"
        (write-layer! global {:rows [{:id :ext/a :plugin :ext/a}] :patch []})
        (is (false? (layers/apply-project-state! :ext/a :load)))
        (is (= [] (:patch (read-layer project))) "no project entry is written"))
      (testing "unload composes a remove patch for the global row"
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (= [{:id :ext/a :remove true}] (:patch (read-layer project))))
        (is (= #{}
               (layers/sources-of (:project (layers/composition (layers/layers)))))))
      (testing "a config patch on the global row survives the disable cycle"
        (fs/delete project)
        (write-layer! project {:rows [] :patch [{:id :ext/a :config {:x 1}}]})
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (true? (layers/apply-project-state! :ext/a :inherit)))
        (is (= [{:id :ext/a :config {:x 1}}]
               (:patch (read-layer project)))))
      (testing "unload targets the row's own id (a custom global id)"
        (fs/delete project)
        (write-layer! global {:rows [{:id :mine :plugin :ext/a}] :patch []})
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (= [{:id :mine :remove true}] (:patch (read-layer project))))
        (testing "inherit cleans that remove patch again"
          (is (true? (layers/apply-project-state! :ext/a :inherit)))
          (is (= [] (:patch (read-layer project)))))))))

(deftest the-written-layers-compose
  (with-layers
    (fn [{:keys [project]}]
      (testing "enable, project disable, then global disable"
        (layers/enable-global! :ext/a)
        (layers/apply-project-state! :ext/a :unload)
        (layers/disable-global! :ext/a)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (empty? (:rows composed))
              "a project remove of a globally disabled row does not dangle")))
      (testing "a preserved project config patch is harmless while the row is off"
        (fs/delete project)
        (spit project (pr-str {:patch [{:id :ext/a :config {:x 1}}]}))
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (empty? (:rows composed))))
        (layers/enable-global! :ext/a)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (= {:x 1} (:config (first (:rows composed))))
              "re-enabling the row applies the project config again")))
      (testing "a project load re-enables a globally disabled row"
        (layers/disable-global! :ext/a)
        (layers/apply-project-state! :ext/a :load)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (= [:ext/a] (mapv :plugin (:rows composed)))))))))

(deftest a-project-inserts-config-patch-goes-with-it
  (with-layers
    (fn [{:keys [global project]}]
      (testing "the insert and its config patch leave together"
        (write-layer! project {:patch [{:id :ext/x :plugin :ext/x}
                                       {:id :ext/x :config {:a 1}}]})
        (is (true? (layers/apply-project-state! :ext/x :inherit)))
        (is (= [] (:patch (read-layer project))))
        (let [parsed (layers/layers)]
          (is (= {:rows [] :patches 0}
                 (profile/compose [(:data (:global parsed))
                                   (:data (:project parsed))])))))
      (testing "a config patch on an id the global layer still composes stays"
        (write-layer! global {:rows [{:id :ext/x :plugin :ext/x}] :patch []})
        (write-layer! project {:patch [{:id :ext/x :plugin :ext/x}
                                       {:id :ext/x :config {:a 1}}]})
        (is (true? (layers/apply-project-state! :ext/x :inherit)))
        (is (= [{:id :ext/x :config {:a 1}}] (:patch (read-layer project)))
            "it now configures the global row")
        (let [parsed (layers/layers)]
          (is (= [{:id :ext/x :plugin :ext/x :config {:a 1}}]
                 (:rows (profile/compose [(:data (:global parsed))
                                          (:data (:project parsed))]))))))
      (testing "a project row's config patch follows the same rule"
        (write-layer! project {:rows [{:id :ext/x :plugin :ext/x}]
                               :patch [{:id :ext/x :config {:a 1}}]})
        (is (true? (layers/apply-project-state! :ext/x :inherit)))
        (is (= [] (:rows (read-layer project))) "the row is the project's enablement")
        (is (= [{:id :ext/x :config {:a 1}}] (:patch (read-layer project)))
            "the global row still composes that id, so its config survives")))))

(deftest a-project-row-without-a-global-target-takes-its-config-patch
  (with-layers
    (fn [{:keys [project]}]
      (write-layer! project {:rows [{:id :ext/x :plugin :ext/x}]
                             :patch [{:id :ext/x :config {:a 1}}]})
      (is (true? (layers/apply-project-state! :ext/x :inherit)))
      (is (= {:rows [] :patch []} (read-layer project))
          "the row and the config patch that only targeted it are gone")
      (let [parsed (layers/layers)]
        (is (= {:rows [] :patches 0}
               (profile/compose [(:data (:global parsed))
                                 (:data (:project parsed))])))))))

(deftest an-unknown-project-state-is-refused
  (with-layers
    (fn [{:keys [project]}]
      (let [error (try (layers/apply-project-state! :ext/a :loaded) nil
                       (catch Exception e e))]
        (is (= :kmet.app/bad-project-state (:type (ex-data error))))
        (is (nil? (read-layer project)) "nothing was written")))))

(deftest unload-removes-every-global-row-of-the-source
  (with-layers
    (fn [{:keys [global project]}]
      (write-layer! global {:rows [{:id :mine :plugin :ext/a}
                                   {:id :other :plugin :ext/a}] :patch []})
      (is (true? (layers/apply-project-state! :ext/a :unload)))
      (is (= [{:id :mine :remove true} {:id :other :remove true}]
             (:patch (read-layer project))))
      (is (= #{}
             (layers/sources-of (:project (layers/composition (layers/layers)))))))))

(deftest writes-keep-the-files-leading-comments
  (with-layers
    (fn [{:keys [global]}]
      (spit global ";; my global layer\n;; second line\n{:rows [] :patch []}\n")
      (is (true? (layers/enable-global! :ext/a)))
      (let [text (slurp global)]
        (is (str/starts-with? text ";; my global layer\n;; second line\n{:rows")
            "the header a hand-written layer carries survives the rewrite")
        (is (= [:ext/a] (mapv :plugin (:rows (read-layer global)))))))))

(deftest a-layer-the-run-refuses-is-not-editable-here-either
  (with-layers
    (fn [{:keys [global]}]
      (testing "an extra top-level key is a bad layer"
        (spit global "{:schema 1 :rows [] :patch []}\n")
        (is (some? (get-in (layers/layers) [:global :error])))
        (is (thrown? clojure.lang.ExceptionInfo (layers/enable-global! :ext/a))))
      (testing "a sequential (non-vector) rows value is a layer"
        (spit global "{:rows ({:id :ext/a :plugin :ext/a}) :patch []}\n")
        (is (nil? (get-in (layers/layers) [:global :error])))
        (is (= #{:ext/a}
               (layers/sources-of (:global (layers/composition (layers/layers))))))
        (is (true? (layers/disable-global! :ext/a)))
        (is (= {:rows [{:id :ext/a :plugin :ext/a}]
                :patch [{:id :ext/a :remove true}]}
               (read-layer global)))))))

(deftest a-malformed-layer-is-reported-and-never-overwritten
  (with-layers
    (fn [{:keys [global]}]
      (spit global "{:rows :nope}\n")
      (let [before (slurp global)
            read (layers/layers)]
        (is (some? (get-in read [:global :error])))
        (is (= {:rows {}}
               (:global (layers/composition read)))
            "a malformed layer composes as empty")
        (is (thrown? clojure.lang.ExceptionInfo
                     (layers/enable-global! :ext/a)))
        (is (= before (slurp global)) "the write refuses to clobber it")))))

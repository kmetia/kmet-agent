(ns kmet.app.extensions.test-layers
  "kmet.app.extensions.layers — the plugin rows of the two ambient
   composition layers: what they compose, the per-scope state the config
   screen shows, the writes its toggles make, and the startup pruning of rows
   naming an uninstalled extension."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.layers :as layers]
            [kmet.app.packages :as packages]
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

(deftest builtin-row-state-follows-the-ambient-layers
  (with-layers
    (fn [{:keys [global project]}]
      (let [state #(layers/builtin-item-state (layers/layers) :tool/bash %)]
        (is (= {:enabled true :override-state :inherit :inherited-enabled true}
               (state :global)))
        (is (= {:enabled true :override-state :inherit :inherited-enabled true}
               (state :project)))
        (write-layer! global {:patch [{:id :tool/bash :remove true}]})
        (is (= {:enabled false :override-state :inherit :inherited-enabled false}
               (state :global)))
        (is (= {:enabled false :override-state :inherit :inherited-enabled false}
               (state :project)))
        (write-layer! project {:patch [{:id :tool/bash
                                        :plugin 'kmet.app.tools.bash/plugin}]})
        (is (= {:enabled false :override-state :inherit :inherited-enabled false}
               (state :global)))
        (is (= {:enabled true :override-state :load :inherited-enabled false}
               (state :project)))
        (fs/delete global)
        (is (= {:enabled true :override-state :inherit :inherited-enabled true}
               (state :project)))))))

(deftest builtin-writers-remove-and-restore-the-base-row
  (with-layers
    (fn [{:keys [global project]}]
      (testing "a global toggle writes and drops a remove patch"
        (is (true? (layers/apply-global-builtin-state! :tool/bash false)))
        (is (= [{:id :tool/bash :remove true}] (:patch (read-layer global))))
        (is (false? (layers/apply-global-builtin-state! :tool/bash false))
            "already off")
        (is (true? (layers/apply-global-builtin-state! :tool/bash true)))
        (is (= [] (:patch (read-layer global)))))
      (testing "a later insert patch wins, so a toggle-off appends a remove"
        (fs/delete global)
        (write-layer! global
                      {:patch [{:id :tool/bash :remove true}
                               {:id :tool/bash
                                :plugin 'kmet.app.tools.bash/plugin}]})
        (is (= {:enabled true :override-state :inherit
                :inherited-enabled true}
               (layers/builtin-item-state (layers/layers) :tool/bash :global)))
        (is (true? (layers/apply-global-builtin-state! :tool/bash false)))
        (is (= [{:id :tool/bash :plugin 'kmet.app.tools.bash/plugin}
                {:id :tool/bash :remove true}]
               (:patch (read-layer global))))
        (is (= {:enabled false :override-state :inherit
                :inherited-enabled false}
               (layers/builtin-item-state (layers/layers) :tool/bash :global)))
        (is (true? (layers/apply-global-builtin-state! :tool/bash true)))
        (is (= [{:id :tool/bash :plugin 'kmet.app.tools.bash/plugin}]
               (:patch (read-layer global)))))
      (testing "the project layer unloads and inherits a composed row"
        (is (true? (layers/apply-project-builtin-state!
                    :tool/bash 'kmet.app.tools.bash/plugin :unload)))
        (is (= [{:id :tool/bash :remove true}] (:patch (read-layer project))))
        (is (true? (layers/apply-project-builtin-state!
                    :tool/bash 'kmet.app.tools.bash/plugin :inherit)))
        (is (= [] (:patch (read-layer project)))))
      (testing "the project layer loads a globally removed row"
        (is (true? (layers/apply-global-builtin-state! :tool/bash false)))
        (is (true? (layers/apply-project-builtin-state!
                    :tool/bash 'kmet.app.tools.bash/plugin :load)))
        (is (= [{:id :tool/bash :plugin 'kmet.app.tools.bash/plugin}]
               (:patch (read-layer project))))
        (let [parsed (layers/layers)
              composed (profile/compose
                        [{:rows [{:id :tool/bash
                                  :plugin 'kmet.app.tools.bash/plugin}]}
                         (:data (:global parsed))
                         (:data (:project parsed))])]
          (is (= ['kmet.app.tools.bash/plugin] (mapv :plugin (:rows composed)))))
        (is (true? (layers/apply-project-builtin-state!
                    :tool/bash 'kmet.app.tools.bash/plugin :inherit)))
        (is (= [] (:patch (read-layer project))))))))

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
          (is (= {:rows [] :patches 0 :dropped []}
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
        (is (= {:rows [] :patches 0 :dropped []}
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

;; ─── Startup healing ─────────────────────────────────────────────────────

(defn- with-installed
  "Run F with the installed extensions replaced by PATHS, so resolution never
   reads the developer's real extensions."
  [paths f]
  (with-redefs [packages/resolve-configured-packages
                (constantly {:extensions (mapv (fn [path]
                                                 {:path path :enabled true
                                                  :metadata {}})
                                               paths)})]
    (f)))

(deftest prunes-rows-naming-an-unknown-extension
  (with-installed ["test/fixtures/ext-single/hello_ext.clj"]
    (fn []
      (with-layers
        (fn [{:keys [global]}]
          (spit global (str ";; keep me\n"
                            "{:rows [{:id :ext/hello_ext :plugin :ext/hello_ext}\n"
                            "        {:id :gone :plugin :ext/gone}]\n"
                            " :patch [{:id :grep :plugin :ext/grep-tool}\n"
                            "         {:id :ext/gone :config {:a 1}}]}\n"))
          (is (= [{:path global :sources [:ext/gone :ext/grep-tool]}]
                 (layers/prune-unknown-extensions! [global]))
              "the unknown sources are reported in file order, once each")
          (is (= {:rows [{:id :ext/hello_ext :plugin :ext/hello_ext}]
                  :patch []}
                 (read-layer global))
              (str "the unknown rows and inserts are gone, and so is the"
                   " config patch that only targeted one"))
          (is (str/starts-with? (slurp global) ";; keep me")
              "the leading comment survives the write")
          (is (= [] (layers/prune-unknown-extensions! [global]))
              "a second run finds nothing to remove"))))))

(deftest pruning-leaves-a-missing-or-malformed-layer-alone
  (with-installed []
    (fn []
      (with-layers
        (fn [{:keys [global project]}]
          (is (= [] (layers/prune-unknown-extensions! [global])))
          (is (not (fs/exists? global)) "a missing layer is not created")
          (spit global "{:rows")
          (is (= [] (layers/prune-unknown-extensions! [global])))
          (is (= "{:rows" (slurp global)) "a malformed file is not rewritten")
          (testing "a malformed layer beside a healed one is not touched"
            (spit global (pr-str {:rows [{:id :gone :plugin :ext/gone}]
                                  :patch []}))
            (spit project "{:rows")
            (is (= [{:path global :sources [:ext/gone]}]
                   (layers/prune-unknown-extensions! [global project])))
            (is (= "{:rows" (slurp project)))
            (is (= {:rows [] :patch []} (read-layer global)))))))))

(deftest pruning-leaves-a-row-the-compose-rejects-for-another-reason
  (with-installed []
    (fn []
      (with-layers
        (fn [{:keys [global]}]
          (write-layer! global {:rows [{:id :ext/gone}] :patch []})
          (is (= [] (layers/prune-unknown-extensions! [global])))
          (is (= {:rows [{:id :ext/gone}] :patch []} (read-layer global))
              "a row the compose rejects for another reason is not silently dropped"))))))

(deftest pruning-drops-a-remove-patch-for-a-retired-extension
  (with-installed []
    (fn []
      (with-layers
        (fn [{:keys [global]}]
          (write-layer! global {:rows [] :patch [{:id :ext/tools :remove true}]})
          (is (= [{:path global :sources [:ext/tools]}]
                 (layers/prune-unknown-extensions! [global]))
              "the patch's own id names the missing extension")
          (is (= {:rows [] :patch []} (read-layer global))))))))

(deftest pruning-drops-patches-that-only-targeted-a-pruned-row
  (with-installed ["test/fixtures/ext-single/hello_ext.clj"]
    (fn []
      (with-layers
        (fn [{:keys [global project]}]
          (write-layer! global {:rows [{:id :ext/hello_ext :plugin :ext/hello_ext}
                                       {:id :gone :plugin :ext/gone}]
                                :patch []})
          (write-layer! project {:rows []
                                 :patch [{:id :gone :remove true}
                                         {:id :ext/hello_ext :config {:a 1}}]})
          (is (= [{:path global :sources [:ext/gone]}]
                 (layers/prune-unknown-extensions! [global project]))
              "only the layer a source left is reported")
          (is (= {:rows [{:id :ext/hello_ext :plugin :ext/hello_ext}]
                  :patch []}
                 (read-layer global)))
          (is (= {:rows [] :patch [{:id :ext/hello_ext :config {:a 1}}]}
                 (read-layer project))
              (str "a project remove patch dies with the global row it"
                   " targeted; a patch on a row that still composes stays")))))))

(deftest a-save-drops-patches-no-row-can-target
  (with-layers
    (fn [{:keys [global project]}]
      (write-layer! global {:rows [{:id :app/custom :plugin 'kmet.app.test/plugin}]
                            :patch [{:id :tool/run_code :remove true}
                                    {:id :tool/nope :config {:a 1}}
                                    {:id :app/custom :config {:a 1}}
                                    {:id :mode-tui :remove true}]})
      (is (true? (layers/apply-global-builtin-state! :tool/bash false)))
      (is (= {:rows [{:id :app/custom :plugin 'kmet.app.test/plugin}]
              :patch [{:id :app/custom :config {:a 1}}
                      {:id :mode-tui :remove true}
                      {:id :tool/bash :remove true}]}
             (read-layer global))
          (str "the patches no built-in row and no layer row names are gone;"
               " a layer row's target and a built-in row's target stay"))
      (testing "a project save heals the project layer the same way"
        (write-layer! project {:rows [] :patch [{:id :tool/run_code :config {}}]})
        (is (true? (layers/apply-project-state! :ext/a :load)))
        (is (= {:rows [] :patch [{:id :ext/a :plugin :ext/a}]}
               (read-layer project))
            "the stale project patch is dropped, the toggle's insert written")))))

(ns kmet.app.test-catalog
  "kmet.app.catalog — row cards read from the plugin map itself, and the
   `:ext/…` form resolved from the installed extensions by name."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.catalog :as catalog]
            [kmet.app.packages :as packages]
            [kmet.test-tmp :as test-tmp]))

(def not-a-plugin
  "A var a row can name that is not a plugin map."
  {:name "not a keyword"})

(def no-inject-plugin
  "A plugin that declares no dependencies: its card's :inject is `{}`."
  {:name :app/no-inject
   :apply (fn [_ctx _cfg] nil)})

(def vector-plugin
  "A plugin that declares its dependencies in the vector form — all required."
  {:name :app/vector-plugin
   :inject [:tools :events]
   :apply (fn [_ctx _cfg] nil)})

(def test-plugin
  "A plugin map a test row can name as a source — the shape a `defplugin` var
   holds (a qualified var symbol is all a row carries)."
  {:name :app/test-plugin
   :inject {:tools nil}
   :provide #{:x}
   :apply (fn [_ctx _cfg] nil)})

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(defn- with-probe-extension
  "Run F with the installed set replaced by one single-file extension, so
   resolution never reads the developer's real extensions."
  [f]
  (let [dir (test-tmp/temp-dir! "test-catalog-ext-")
        path (str dir "/probe_ext.clj")]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (spit path (str "(ns probe-ext)\n"
                    "(def plugin {:name :ext/probe :apply (fn [_ _] nil)})\n"))
    (try
      (with-redefs [packages/resolve-configured-packages
                    (constantly {:extensions [{:path path :enabled true
                                               :metadata {}}]})]
        (f))
      (finally (fs/delete-tree dir)))))

(deftest extension-sources-resolve-to-a-card
  (with-probe-extension
    (fn []
      (let [card (catalog/describe {:plugin :ext/probe_ext :config {:a 1}})]
        (is (= :ext/probe_ext (:id card)) "the id defaults to the source")
        (is (= :ext/probe_ext (:name card)))
        (is (= {} (:inject card)))
        (is (= {:a 1} (:config card))))
      (testing "an explicit :id wins"
        (is (= :probe (:id (catalog/describe {:plugin :ext/probe_ext :id :probe})))))
      (testing "an explicit :name wins, like a var source's card"
        (is (= :custom (:name (catalog/describe {:plugin :ext/probe_ext
                                                 :name :custom})))))
      (testing "an unknown id is a composition error"
        (is (= :kmet.app/unknown-extension
               (error-type #(catalog/describe {:plugin :ext/nope}))))))))

(deftest a-var-source-is-read-from-the-plugin-map
  (let [card (catalog/describe {:plugin 'kmet.app.test-catalog/test-plugin})]
    (is (= :app/test-plugin (:id card)) "the id defaults to the plugin's :name")
    (is (= :app/test-plugin (:name card)))
    (is (= {:tools nil} (:inject card)))
    (is (= #{:x} (:provide card))))
  (testing "an explicit :id wins over the plugin name"
    (is (= :read (:id (catalog/describe {:plugin 'kmet.app.test-catalog/test-plugin
                                         :id :read}))))))

(deftest a-load-failure-is-not-a-missing-plugin
  ;; The require error used to be swallowed, so a plugin namespace that fails
  ;; to compile read as an absent var — a syntax error reported itself as a
  ;; typo. The error now travels with the report: the message names its
  ;; file:line, :load-error holds it, and it stays the cause.
  (let [boom (ex-info "Unable to resolve symbol: helper in this context"
                      {:file "/repo/modules/app/src/kmet/app/compaction.clj"
                       :line 12})]
    (with-redefs [requiring-resolve (fn [_] (throw boom))]
      (let [e (try (catalog/describe {:plugin 'some.plugin/plugin})
                   (catch Exception e e))
            message (ex-message e)]
        (is (= :kmet.app/unknown-plugin (:type (ex-data e)))
            "the composition-error type is unchanged")
        (is (str/includes? message "some.plugin/plugin") "the source is named")
        (is (str/includes? message "Unable to resolve symbol: helper"))
        (is (= "/repo/modules/app/src/kmet/app/compaction.clj:12: Unable to resolve symbol: helper in this context"
               (:load-error (ex-data e)))
            "ex-data carries the text, not the throwable")
        (is (identical? boom (ex-cause e)) "the load error stays the cause"))))
  (with-redefs [requiring-resolve (fn [_] (throw (ex-info "no site" {})))]
    (is (str/includes? (try (catalog/describe {:plugin 'some.plugin/plugin})
                            (catch Exception e (ex-message e)))
                       "; loading it failed: no site")
        "a failure without a file:line still reports its message")))

(deftest inject-comes-back-normalized
  ;; The card must read like the fiber record: a vector declaration is
  ;; all-required, so it prints and compares as `{name nil}`. It is also what
  ;; `--dump-config` renders, which is how a vector form used to break the dump.
  (is (= {:tools nil :events nil}
         (:inject (catalog/describe {:plugin 'kmet.app.test-catalog/vector-plugin}))))
  (is (= {} (:inject (catalog/describe {:plugin 'kmet.app.test-catalog/no-inject-plugin})))
      "a row with no :inject says so")
  (is (= {:tools nil}
         (:inject (catalog/describe {:plugin 'kmet.app.test-catalog/test-plugin})))
      "a map declaration passes through")
  (is (= :srce/bad-inject
         (error-type #(catalog/describe
                       {:plugin 'kmet.app.test-catalog/vector-plugin
                        :inject {:tools :sometimes}})))
      "a malformed declaration fails at compose time, not at mount"))

(deftest unknown-sources-throw
  (is (= :kmet.app/unknown-plugin
         (error-type #(catalog/describe {:plugin 'no.such/plugin}))))
  (is (= :kmet.app/unknown-plugin
         (error-type #(catalog/describe {:plugin 'kmet.app.test-catalog/not-a-plugin})))
      "a var that is not a plugin map is not a card")
  (is (= :kmet.app/bad-row
         (error-type #(catalog/describe {:id :row})))
      "a row without a :plugin source is a bad row")
  (is (= :kmet.app/bad-row
         (error-type #(catalog/describe {:plugin :tool/bash})))
      "a keyword source is an :ext/… id, never a registry key"))

(deftest describe-layer-describes-rows-and-insert-patches
  (let [layer (catalog/describe-layer
               {:rows [{:plugin 'kmet.app.test-catalog/test-plugin}]
                :patch [{:id :tool/read :config {:a 1}}
                        {:id :tool/ls :plugin 'kmet.app.test-catalog/test-plugin
                         :after :tool/read}]})
        [config-patch insert-patch] (:patch layer)]
    (is (= :app/test-plugin (-> layer :rows first :id)))
    (is (= {:id :tool/read :config {:a 1}} config-patch)
        "a config-only patch is not a row")
    (is (= :app/test-plugin (:name insert-patch))
        "an insert patch's new row is described")))

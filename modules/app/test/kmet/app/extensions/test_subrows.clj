(ns kmet.app.extensions.test-subrows
  "Rows an extension mounts (`:rows` in its plugin map).

   An extension row is a **child** row: it mounts inside the extension's own
   ctx, so it lives and dies with the extension and a service it provides is
   visible to the extension rather than to the app — the app-wide services
   belong to the composition. What a composition row may be but an extension
   row cannot (`:ext/…` sources, a `:mode` flag, unknown keys) is refused at
   load, not silently ignored."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.test-support :as support]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :once support/with-composition)

(def ^:private dir (test-tmp/temp-dir! "test-ext-subrows-"))

(srce/defplugin store-row
  {:name :probe/store
   :provide #{:probe-store}
   :apply (fn [ctx _config]
            (srce/provide! ctx :probe-store {:from :row} {:in (:parent ctx)}))})

(srce/defplugin pending-row
  {:name :probe/pending
   :inject [:probe-nothing]
   :apply (fn [_ctx _config] nil)})

(defn- load-with-rows!
  "Write a single-file extension whose plugin declares ROWS (EDN text) and
   load it. Returns the load result."
  [rows]
  (let [path (str dir "/probe_subrows.clj")]
    (fs/create-dirs dir)
    (spit path (str "(ns probe_subrows)\n"
                    "(def plugin {:name :ext/subrows\n"
                    "             :apply (fn [_ctx _api] nil)\n"
                    "             :rows " rows "})\n"))
    (extensions/load-extension! path)))

(defn- record [result]
  (extensions/loaded-record (:extension result)))

(deftest an-extension-mounts-its-rows-under-its-own-context
  (extensions/clear-extensions!)
  (let [result (load-with-rows!
                "[{:id :probe/store :plugin 'kmet.app.extensions.test-subrows/store-row}]")]
    (try
      (is (nil? (:error result)) (str "loaded: " (:error result)))
      (let [ext (record result)
            ext-ctx (:ctx @@(:fiber ext))]
        (is (= {:from :row} (srce/get ext-ctx :probe-store))
            "the extension's ctx sees what its row provided")
        (is (nil? (srce/get (:parent ext-ctx) :probe-store))
            (str "the app ctx above it does not: an extension row provides into "
                 "the extension's subtree"))
        (testing "unloading takes the rows with it"
          (extensions/unload-extension! ext)
          (is (nil? (srce/get ext-ctx :probe-store))
              "the row's disposer ran with the extension's")))
      (finally (extensions/clear-extensions!)))))

(deftest an-extension-can-structure-itself-with-rows-from-its-own-namespace
  ;; the row's plugin lives in the extension's namespaces, which are in its
  ;; loader and not on the host classpath: the extension composes itself
  (extensions/clear-extensions!)
  (let [path (str dir "/selfrow_ext.clj")]
    (fs/create-dirs dir)
    (spit path (str "(ns selfrow-ext (:require [kmet.srce.core :as srce]))\n"
                    "(srce/defplugin row-plugin\n"
                    "  {:name :selfrow/store\n"
                    "   :provide #{:selfrow-store}\n"
                    "   :apply (fn [ctx _]\n"
                    "            (srce/provide! ctx :selfrow-store :from-extension\n"
                    "                           {:in (:parent ctx)}))})\n"
                    "(def plugin {:name :ext/selfrow\n"
                    "             :apply (fn [_ctx _api] nil)\n"
                    "             :rows [{:id :selfrow/store\n"
                    "                     :plugin 'selfrow-ext/row-plugin}]})\n"))
    (try
      (let [result (extensions/load-extension! path)]
        (is (nil? (:error result)) (str "loaded: " (:error result)))
        (let [ext (record result)]
          (is (= :from-extension
                 (srce/get (:ctx @@(:fiber ext)) :selfrow-store))
              "the row resolved through the extension's own loader")))
      (finally (extensions/clear-extensions!)))))

(deftest a-row-that-cannot-mount-fails-the-load
  (extensions/clear-extensions!)
  (try
    (let [result (load-with-rows!
                  "[{:id :probe/pending :plugin 'kmet.app.extensions.test-subrows/pending-row}]")]
      (is (some? (:error result)) "the load fails rather than half-mounting")
      (is (str/includes? (str (:error result)) "did not mount")
          "and names the row that did not")
      (is (str/includes? (str (:error result)) "probe-nothing")
          "with what it is waiting for"))
    (finally (extensions/clear-extensions!))))

(deftest a-row-a-composition-could-have-but-an-extension-cannot-is-refused
  (extensions/clear-extensions!)
  (try
    (doseq [[label rows expected]
            [["a mode row"
              "[{:id :probe/store :plugin 'kmet.app.extensions.test-subrows/store-row :mode true}]"
              ":mode"]
             ["another installed extension"
              "[{:id :probe/store :plugin :ext/other}]"
              "another installed extension"]
             ["a key outside the row shape"
              "[{:id :probe/store :plugin 'kmet.app.extensions.test-subrows/store-row :after :tools}]"
              ":after"]
             ["a row without a keyword id"
              "[{:id \"probe/store\" :plugin 'kmet.app.extensions.test-subrows/store-row}]"
              "keyword :id"]]]
      (testing label
        (let [result (load-with-rows! rows)]
          (is (some? (:error result)) "the load fails")
          (is (str/includes? (str (:error result)) expected)
              (str "and says why: " expected)))))
    (finally (extensions/clear-extensions!))))

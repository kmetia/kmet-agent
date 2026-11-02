(ns kmet.app.extensions.test-inject
  "An extension is a plugin: `extension.edn :plugin` names the var the kernel
   mounts, the plugin map's `:inject` decides when it activates, and unload
   disposes the fiber. Also the manifest's refusals — `:inject` and `:entry`
   belong to the plugin map / are gone."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.app.extensions.core :as extensions]
            [kmet.srce.core :as srce]
            [kmet.app.test-support :as support]
            [kmet.test-tmp :as test-tmp]))
;; the plugin mounts under the composition's ctx, so the namespace boots one
(t/use-fixtures :once support/with-composition)

(defn- mount-fiber
  "The fiber of the extension mount named NAME under the app root, or nil."
  [name]
  (some (fn [ctx] (when (= name (:name ctx)) @(:fiber ctx)))
        (srce/children (support/composition-ctx))))

(def ^:private hook-body
  "(ext/on-input api (fn [{:keys [text]}] {:action :transform :text (str text \"+injected\")}))")

(def ^:private ns-line
  "(ns %s\n  (:require %s))\n")

(defn- extension-dir!
  "Write DIR/extension.edn and DIR/<ns path>.clj for a directory extension
   named NAME: the manifest names its plugin var, INJECT is the plugin map's
   `:inject` form (nil omits the key) and BODY is the `:apply` body, with
   `api` bound and nil returned. :requires is the ns form's require vector
   (default: `kmet.extension`). :manifest-extra appends raw text to the
   manifest, to build the malformed cases; :manifest replaces it outright."
  [dir name & {:keys [inject body manifest manifest-extra requires]}]
  (let [stem (str/replace name "-" "_")
        entry-ns (str stem ".main")]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/" stem))
    (spit (str dir "/extension.edn")
          (or manifest
              (str "{:name \"" name "\" :plugin " entry-ns "/plugin"
                   " :loader [:sci]" (or manifest-extra "") "}\n")))
    (spit (str dir "/" stem "/main.clj")
          (str (format ns-line entry-ns (or requires "[kmet.extension :as ext]")) "\n"
               "(def plugin\n  {:name :ext/" name "\n"
               (when inject (str "   :inject " inject "\n"))
               "   :apply (fn [_ctx api]\n     " body "\n     nil)})\n"))
    dir))

(t/deftest an-inject-extension-waits-then-activates
  (testing "pending while the dependency is absent, active once it is there"
    (let [name "inject-pending"
          dir (extension-dir! "target/test-ext-inject-pending" name
                              :inject "[:kmet.inject-test/never]"
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (some #(= name (:name %)) (extensions/get-loaded-extensions))
                "a pending extension is still a loaded extension")
          (let [fiber (mount-fiber (keyword "ext" name))]
            (t/is (some? fiber) "the extension is mounted as a plugin fiber")
            (t/is (= :pending (:state fiber)) "waiting for its declared dependency")
            (t/is (contains? (:waiting-for fiber) :kmet.inject-test/never)))
          (let [loaded (some #(when (= name (:name %)) %)
                             (extensions/get-loaded-extensions))]
            (t/is (= :pending (:fiber-state loaded))
                  "the listing says the mount is pending")
            (t/is (= #{:kmet.inject-test/never} (:waiting-for loaded))
                  "and names what it waits for — what the UI shows"))
          (t/is (= "hi" (:text (extensions/apply-input-hooks "hi" :interactive)))
                "the body has not run: the extension registered nothing yet"))
        (t/is (nil? (extensions/unload-extension!
                     (some #(when (= name (:name %)) %)
                           (extensions/registered-extensions))))
              "unloading a pending extension disposes its fiber")
        (t/is (not (mount-fiber (keyword "ext" name))) "the mount is gone")
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest an-inject-extension-runs-when-its-dependency-exists
  (testing "a satisfied :inject activates the mount and runs the body"
    (let [name "inject-active"
          dir (extension-dir! "target/test-ext-inject-active" name
                              :inject "[:tools]"
                              :body hook-body)]
      (t/is (some? (srce/get (support/composition-ctx) :tools))
            ":tools is provided by the app composition")
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (let [fiber (mount-fiber (keyword "ext" name))]
            (t/is (some? fiber))
            (t/is (= :active (:state fiber)) "the dependency is satisfied"))
          (t/is (= "hi+injected" (:text (extensions/apply-input-hooks "hi" :interactive)))
                "the body ran and its registration is live"))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-mount-that-fails-is-a-load-error
  (testing "a throwing body inside the mount is reported, not swallowed"
    (let [name "inject-broken"
          dir (extension-dir! "target/test-ext-inject-broken" name
                              :inject "[:tools]"
                              :body "(throw (ex-info \"boom\" {}))")]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) "boom")
                "with the mount's own error"))
        (t/is (not (some #(= name (:name %)) (extensions/get-loaded-extensions)))
              "a failed mount is not a loaded extension")
        (t/is (not (some #{[(keyword "ext" name)]} (srce/children (support/composition-ctx))))
              "and its mount is gone")
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-manifest-inject-key-is-refused
  (testing ":inject belongs to the plugin map, not the manifest"
    (let [name "manifest-inject"
          dir (extension-dir! "target/test-ext-manifest-inject" name
                              :manifest-extra " :inject [:tools]"
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) ":inject")
                "and names the offending key"))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-legacy-entry-manifest-is-refused
  (testing ":entry is gone — the manifest names a plugin var"
    (let [name "legacy-entry"
          entry-ns (str (str/replace name "-" "_") ".main")
          ;; a manifest being migrated: :entry and no :plugin at all
          dir (extension-dir! "target/test-ext-legacy-entry" name
                              :manifest (str "{:name \"" name "\" :entry "
                                             entry-ns " :loader [:sci]}\n")
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) ":plugin")
                "and says what to write instead"))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-plugin-manifest-mounts-the-var
  (testing "the var the manifest names is the mounted plugin, active once loaded"
    (let [name "plugin-active"
          dir (extension-dir! "target/test-ext-plugin-active" name
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (let [loaded (some #(when (= name (:name %)) %)
                             (extensions/get-loaded-extensions))]
            (t/is (= :active (:fiber-state loaded))
                  "a plugin extension is a fiber, active once mounted"))
          (t/is (= "hi+injected" (:text (extensions/apply-input-hooks "hi" :interactive)))
                "the plugin's body ran"))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-plugin-declares-its-own-inject
  (testing "the plugin map's :inject holds the mount pending"
    (let [name "plugin-pending"
          dir (extension-dir! "target/test-ext-plugin-pending" name
                              :inject "{:kmet.inject-test/never nil}"
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (let [loaded (some #(when (= name (:name %)) %)
                             (extensions/get-loaded-extensions))]
            (t/is (= :pending (:fiber-state loaded)))
            (t/is (contains? (:waiting-for loaded) :kmet.inject-test/never))))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-defplugin-var-mounts-like-a-plain-map
  ;; the documented form: the extension requires kmet.srce.core and uses the
  ;; macro, whose expansion must reach public kernel API only (a private-ns
  ;; reference would fail here, inside the sandbox, not at the kernel's tests)
  (let [name "defplugin-ext"
        stem (str/replace name "-" "_")
        entry-ns (str stem ".main")
        dir (test-tmp/temp-dir! "test-ext-defplugin-")
        file (str dir "/" stem "/main.clj")]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/" stem))
    (spit (str dir "/extension.edn")
          (str "{:name \"" name "\" :plugin " entry-ns "/plugin"
               " :loader [:sci]}\n"))
    (spit file
          (str "(ns " entry-ns "\n"
               "  (:require [kmet.extension :as ext]\n"
               "            [kmet.srce.core :as srce]))\n\n"
               "(srce/defplugin plugin\n"
               "  {:name  :ext/defplugin-ext\n"
               "   :inject {:tools nil}\n"
               "   :apply (fn [_ctx api]\n"
               "            (ext/on-input api\n"
               "                          (fn [{:keys [text]}]\n"
               "                            {:action :transform :text (str text \"+macro\")}))\n"
               "            nil)})\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (let [fiber (mount-fiber :ext/defplugin-ext)]
          (t/is (some? fiber) "the defplugin var mounted as a fiber")
          (t/is (= :active (:state fiber)) "its :inject was satisfied"))
        (t/is (= "hi+macro" (:text (extensions/apply-input-hooks "hi" :interactive)))
              "the macro-defined body ran"))
      (finally
        (extensions/clear-extensions!)
        (fs/delete-tree dir)))))

(t/deftest a-malformed-plugin-manifest-is-refused-before-evaluation
  (testing ":plugin must be a qualified var"
    (doseq [[name plugin-form]
            [["plugin-unqualified" "plugin"]
             ["plugin-string" "\"main.clj\""]]]
      (let [dir (extension-dir! (str (test-tmp/temp-dir! "test-ext-") "-" name) name
                                :manifest (str "{:name \"" name "\" :plugin "
                                               plugin-form " :loader [:sci]}\n")
                                :body hook-body)]
        (try
          (let [result (extensions/load-extension! dir)]
            (t/is (some? (:error result)) (str name " must not load"))
            (t/is (str/includes? (str (:error result)) ":plugin must be a qualified")
                  (str name ": " (:error result))))
          (finally
            (extensions/clear-extensions!)
            (fs/delete-tree dir)))))))

(t/deftest a-missing-plugin-var-is-a-load-error
  (testing "the manifest names a var the namespace does not define"
    (let [name "plugin-missing"
          entry-ns (str (str/replace name "-" "_") ".main")
          dir (extension-dir! "target/test-ext-plugin-missing" name
                              :manifest (str "{:name \"" name "\" :plugin "
                                             entry-ns "/missing :loader [:sci]}\n")
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) "does not define")))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-bad-inject-in-the-plugin-map-fails-the-mount
  (testing "the kernel refuses a malformed :inject, and the load reports it"
    (let [name "plugin-bad-inject"
          dir (extension-dir! "target/test-ext-plugin-bad-inject" name
                              :inject "{:kmet.inject-test/never :not-a-flag}"
                              :body hook-body)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) ":inject")))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest what-an-extension-mounts-on-its-ctx-goes-with-it
  ;; (:ctx api) is the extension's own mount ctx, so a service it provides and
  ;; a plugin it mounts there are effects of the extension: unload releases
  ;; them. Handing out the app root instead leaked both across every reload.
  (testing "provide! and plugin! on the api ctx unwind with the unload"
    (let [name "ctx-own"
          dir (extension-dir!
               "target/test-ext-ctx-own" name
               :requires "[kmet.extension :as ext] [kmet.srce.core :as srce]"
               :body (str "(srce/provide! (:ctx api) :kmet.ctx-test/own :own)\n"
                          "     (srce/provide! (:ctx api) :kmet.ctx-test/published :published"
                          " {:in (:app-root api)})\n"
                          "     (srce/plugin! (:ctx api)\n"
                          "                   {:name :ext/child\n"
                          "                    :apply (fn [c _]\n"
                          "                             (srce/provide! c :kmet.ctx-test/child :child)\n"
                          "                             nil)})"))]
      (try
        (let [result (extensions/load-extension! dir)
              root (support/composition-ctx)
              ctx (:ctx (mount-fiber (keyword "ext" name)))]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (some? ctx) "the mount has its own ctx")
          (t/is (not (identical? root ctx)) "the api's ctx is not the app root")
          (t/is (= :own (srce/get ctx :kmet.ctx-test/own)) "provide! lands on it")
          (t/is (nil? (srce/get root :kmet.ctx-test/own)) "and not on the root")
          (t/is (= :published (srce/get root :kmet.ctx-test/published))
                "{:in (:app-root api)} publishes a service app-wide")
          (t/is (some #(= :ext/child (:name %)) (srce/fibers root))
                "a plugin the body mounts there is mounted")
          (extensions/unload-all-extensions!)
          (t/is (nil? (srce/get root :kmet.ctx-test/published))
                "the app-wide service unwinds with the extension")
          (t/is (nil? (srce/get ctx :kmet.ctx-test/own))
                "the ctx's own service went with the mount")
          (t/is (not (some #(= :ext/child (:name %)) (srce/fibers root)))
                "and the plugin the body mounted is disposed too"))
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

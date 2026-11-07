(ns kmet.ui.tui.test-plugins
  "The `kmet plugins` screen: extension plugin rows only, composition-layer
   writes, the global/project scope model and search."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.bundled :as bundled]
            [kmet.app.extensions.layers :as ext-layers]
            [kmet.ui.tui.plugins :as plugins]
            [kmet.tui.protocols :as protocols]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-plugins-"))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-PGUP "\u001b[5~")
(def ^:const K-PGDN "\u001b[6~")

(defn- extension-file!
  "Create a single-file extension NAME under ROOT/extensions and return its
   path."
  [root name]
  (let [dir (fs/path root "extensions")
        path (fs/path dir (str name ".clj"))]
    (fs/create-dirs dir)
    (spit (str path) (str "(ns " name ")\n"))
    (str path)))

(defn- skill-file!
  "Create a SKILL.md under ROOT/skills so a resolution has a non-extension
   resource the screen must not list."
  [root name]
  (let [dir (fs/path root "skills" name)
        path (fs/path dir "SKILL.md")]
    (fs/create-dirs dir)
    (spit (str path) (str "---\nname: " name "\ndescription: " name "\n---\nbody\n"))
    (str path)))

(defn- with-plugins
  "Run F with the settings and composition paths isolated. OPTS :user and
   :project are written as settings maps; :bundled? keeps the real bundled
   descriptors (the default stubs them out)."
  ([f] (with-plugins f {}))
  ([f {:keys [user project bundled?]}]
   (let [global-dir (tmp-dir)
         cwd (tmp-dir)
         project-dir (str (fs/path cwd ".kmet"))
         global-settings (str (fs/path global-dir "settings.edn"))
         project-settings (str (fs/path project-dir "settings.edn"))
         global-patch (str (fs/path global-dir "patch.edn"))
         project-patch (str (fs/path project-dir "patch.edn"))
         read-file (fn [path default]
                     (if (fs/exists? path)
                       (or (edn/read-string (slurp path)) default)
                       default))]
     (fs/create-dirs global-dir)
     (when user (spit global-settings (pr-str user)))
     (when project
       (fs/create-dirs project-dir)
       (spit project-settings (pr-str project)))
     (with-redefs-fn
       (cond-> {#'cfg/read-global-settings-map
                (fn [] (read-file global-settings {}))
                #'cfg/read-project-settings-map
                (fn [] (read-file project-settings {}))
                #'cfg/global-patch-path (fn [] global-patch)
                #'cfg/project-patch-path (fn [] project-patch)
                #'cfg/get-agent-dir (fn [] global-dir)
                #'cfg/project-dir (fn [] project-dir)
                #'fs/cwd (fn [] cwd)}
         (not bundled?) (assoc #'bundled/artifacts (constantly [])))
       (fn []
         (f {:global-dir global-dir
             :project-dir project-dir
             :global-settings global-settings
             :global-patch global-patch
             :project-patch project-patch}))))))

(defn- layer-of
  "The patch layer at PATH, normalized to {:rows [] :patch []}."
  [path]
  (if (fs/exists? path)
    (let [data (edn/read-string (slurp path))]
      {:rows (vec (or (:rows data) []))
       :patch (vec (or (:patch data) []))})
    {:rows [] :patch []}))

(defn- extension-rows
  "The :ext/… sources a layer names, in row then patch order."
  [layer]
  (->> (concat (:rows layer) (filter :plugin (:patch layer)))
       (keep :plugin)
       (filter #(and (keyword? %) (str/starts-with? (str %) ":ext/")))
       vec))

(defn- composed-sources
  "The extension sources the global layer composes (rows minus removes)."
  []
  (ext-layers/sources-of
   (:global (ext-layers/composition (ext-layers/layers)))))

(defn- render-lines [screen width]
  (protocols/render screen width))

(defn- item-rows [screen]
  (mapv :item (filter #(= :item (:kind %)) (plugins/screen-rows screen))))

(defn- first-item [screen]
  (first (item-rows screen)))

(t/deftest lists-extension-rows-only
  ;; An installed package contributes an extension and a skill; the plugin
  ;; screen must show the extension row and no skill, prompt, theme or package
  ;; row.
  (let [pkg (tmp-dir)
        _ (extension-file! pkg "pkg-ext")
        _ (skill-file! pkg "pkg-skill")]
    (with-plugins
      (fn [{:keys [global-settings]}]
        (let [screen (plugins/make-plugins-screen :rows 40)
              items (item-rows screen)
              lines (render-lines screen 100)]
          (t/is (= 1 (count items)))
          (t/is (= :extensions (:resource-type (first items))))
          (t/is (str/ends-with? (:path (first items)) "pkg-ext.clj"))
          (t/is (some #(str/includes? % "Global Plugins") lines))
          (t/is (not-any? #(str/includes? % "Skills") lines))
          (t/is (not-any? #(str/includes? % "Tools") lines))
          (t/is (= {:packages [pkg]}
                   (edn/read-string (slurp global-settings)))
                "the screen only reads settings")))
      {:user {:packages [pkg]}})))

(t/deftest global-toggle-writes-the-composition-not-settings
  (with-plugins
    (fn [{:keys [global-dir global-settings global-patch]}]
      (extension-file! global-dir "ext-a")
      (let [screen (plugins/make-plugins-screen :rows 40)
            row (first-item screen)]
        (t/is (false? (:enabled row)))
        (t/testing "space composes the extension row"
          (protocols/handle-input screen " ")
          (t/is (= [:ext/ext-a] (extension-rows (layer-of global-patch))))
          (t/is (= #{:ext/ext-a} (composed-sources)))
          (t/is (true? (:enabled (first-item screen)))))
        (t/testing "a later search keeps the toggled state"
          (plugins/screen-set-query! screen "ext-a")
          (t/is (true? (:enabled (first-item screen)))))
        (t/testing "space again disables with a remove patch"
          (protocols/handle-input screen " ")
          (t/is (empty? (composed-sources)))
          (t/is (false? (:enabled (first-item screen)))))
        (t/is (not (fs/exists? global-settings))
              "no settings entry is written")))))

(t/deftest project-scope-cycles-inherit-load-unload
  (with-plugins
    (fn [{:keys [global-dir project-patch]}]
      (extension-file! global-dir "ext-a")
      (let [screen (plugins/make-plugins-screen
                    :rows 40 :write-scope :project :project-mode? true)
            row (first-item screen)]
        (t/is (= :inherit (:override-state row)))
        (t/is (false? (:enabled row)))
        (t/testing "inherit -> load writes a project row"
          (protocols/handle-input screen " ")
          (t/is (= :load (:override-state (first-item screen))))
          (t/is (= [:ext/ext-a] (extension-rows (layer-of project-patch)))))
        (t/testing "load -> inherit drops the project row"
          (protocols/handle-input screen " ")
          (t/is (= :inherit (:override-state (first-item screen))))
          (t/is (empty? (extension-rows (layer-of project-patch)))))))))

(t/deftest project-scope-unloads-a-globally-composed-extension
  (with-plugins
    (fn [{:keys [global-dir global-patch project-patch]}]
      (extension-file! global-dir "ext-a")
      (spit global-patch
            (pr-str {:rows [{:id :ext/ext-a :plugin :ext/ext-a}] :patch []}))
      (let [screen (plugins/make-plugins-screen
                    :rows 40 :write-scope :project :project-mode? true)
            row (first-item screen)]
        (t/is (= :inherit (:override-state row)))
        (t/is (true? (:enabled row)))
        (t/testing "inherit -> unload composes a remove patch"
          (protocols/handle-input screen " ")
          (t/is (= :unload (:override-state (first-item screen))))
          (t/is (= [{:id :ext/ext-a :remove true}]
                   (:patch (layer-of project-patch))))
          (t/is (false? (:enabled (first-item screen)))))
        (t/testing "unload -> inherit drops the remove patch"
          (protocols/handle-input screen " ")
          (t/is (= :inherit (:override-state (first-item screen))))
          (t/is (empty? (:patch (layer-of project-patch)))))))))

(t/deftest bundled-extensions-are-plugin-rows
  (with-plugins
    (fn [{:keys [global-patch]}]
      (let [screen (plugins/make-plugins-screen :rows 40)
            items (item-rows screen)]
        (t/is (= (count (bundled/artifacts)) (count items)))
        (t/is (every? #(= :bundled (get-in % [:metadata :origin])) items))
        (t/is (every? #(false? (:enabled %)) items)
              "bundled extensions are opt-in")
        (let [source (:source (first items))]
          (protocols/handle-input screen " ")
          (t/is (= #{source} (composed-sources)))
          (t/is (= [source] (extension-rows (layer-of global-patch)))))))
    {:bundled? true}))

(t/deftest unreadable-extension-is-listed-off
  (with-plugins
    (fn [{:keys [project-dir]}]
      (let [dir (fs/path project-dir "extensions/broken")]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "extension.edn")) "{:name")
        (let [screen (plugins/make-plugins-screen
                      :rows 40 :write-scope :project :project-mode? true)
              item (first-item screen)]
          (t/is (:unreadable? item))
          (t/is (false? (:enabled item)))
          (t/is (false? (:inherited? item))
                "a project-scope item is not inherited"))))))

(t/deftest navigation-moves-between-items
  (with-plugins
    (fn [{:keys [global-dir]}]
      (doseq [name (map #(str "ext-" %) "abcdefgh")]
        (extension-file! global-dir name))
      (let [screen (plugins/make-plugins-screen :rows 16)
            selected-name (fn []
                            (let [row (nth (plugins/screen-rows screen)
                                           (plugins/screen-selected screen))]
                              (fs/file-name (:path (:item row)))))]
        (t/is (= "ext-a.clj" (selected-name)))
        (protocols/handle-input screen K-DOWN)
        (t/is (= "ext-b.clj" (selected-name)))
        (protocols/handle-input screen K-DOWN)
        (t/is (= "ext-c.clj" (selected-name)))
        (protocols/handle-input screen K-UP)
        (t/is (= "ext-b.clj" (selected-name)))
        (protocols/handle-input screen K-UP)
        (protocols/handle-input screen K-UP)
        (t/is (= "ext-a.clj" (selected-name)) "up at the first item stays")
        (protocols/handle-input screen K-PGDN)
        (t/is (= "ext-f.clj" (selected-name)))
        (protocols/handle-input screen K-PGUP)
        (t/is (= "ext-a.clj" (selected-name)))))))

(t/deftest terminal-resize-repaints-and-disposes
  (with-plugins
    (fn [{:keys [global-dir]}]
      (extension-file! global-dir "ext-a")
      (let [size-ref (atom {:rows 24})
            renders (atom 0)
            screen (plugins/make-plugins-screen
                    :rows 24 :size-ref size-ref
                    :request-render! #(swap! renders inc))]
        (t/is (some? (:rows-unwatch screen)))
        (reset! size-ref {:rows 30})
        (t/is (= 30 @(:rows-count screen)))
        (t/is (= 1 @renders))
        (reset! size-ref {:rows 30})
        (t/is (= 1 @renders) "an unchanged height does not repaint")
        ((:rows-unwatch screen))
        (reset! size-ref {:rows 40})
        (t/is (= 1 @renders) "the watch is gone after dispose")))))

(t/deftest tab-switches-the-project-scope
  (with-plugins
    (fn [{:keys [global-dir project-dir]}]
      (extension-file! global-dir "ext-a")
      (extension-file! project-dir "ext-b")
      (let [screen (plugins/make-plugins-screen :rows 40 :project-mode? true)
            names #(set (map (fn [item] (fs/file-name (:path item)))
                             (item-rows screen)))]
        (t/is (= :global (plugins/screen-write-scope screen)))
        (t/is (= #{"ext-a.clj"} (names)))
        (protocols/handle-input screen "\t")
        (t/is (= :project (plugins/screen-write-scope screen)))
        (t/is (= #{"ext-a.clj" "ext-b.clj"} (names)))
        (protocols/handle-input screen "\t")
        (t/is (= :global (plugins/screen-write-scope screen)))))))

(t/deftest search-filters-plugin-rows
  (with-plugins
    (fn [{:keys [global-dir]}]
      (extension-file! global-dir "ext-a")
      (extension-file! global-dir "ext-b")
      (let [screen (plugins/make-plugins-screen :rows 40)]
        (t/is (= 2 (count (item-rows screen))))
        (plugins/screen-set-query! screen "ext-b")
        (t/is (= 1 (count (item-rows screen))))
        (t/is (= ["ext-b.clj"] (map #(fs/file-name (:path %))
                                    (item-rows screen))))
        (plugins/screen-set-query! screen "nothing")
        (t/is (empty? (item-rows screen)))))))

(t/deftest scope-hint-names-the-written-layer
  (with-plugins
    (fn [_]
      (let [global (plugins/make-plugins-screen :rows 40)
            project (plugins/make-plugins-screen
                     :rows 40 :write-scope :project :project-mode? true)]
        (t/is (some #(str/includes? % "patch.edn")
                    (render-lines global 240)))
        (t/is (some #(str/includes? % ".kmet/patch.edn")
                    (render-lines project 240)))
        (t/is (not-any? #(str/includes? % "settings.edn")
                        (render-lines global 240)))))))

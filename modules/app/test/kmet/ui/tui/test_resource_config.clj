(ns kmet.ui.tui.test-resource-config
  "Resource-config screen tests (pi: config-selector.ts): row model,
   global/project scope toggles and their settings writes, tri-state
   cycling and search."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.extensions.bundled :as bundled]
            [kmet.app.packages :as pkgs]
            [kmet.app.extensions.layers :as ext-layers]
            [kmet.app.tools.config :as tools-config]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.ui.tui.resource-config :as rc]
            [kmet.app.config :as cfg]
            [kmet.hikap.core :as engine]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as th]
            [kmet.tui.utils :as u]
            [kmet.test-utils :refer [slash]]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-"))

(defn- make-package
  [root]
  (fs/create-dirs (str root "/extensions"))
  (fs/create-dirs (str root "/skills/root"))
  (spit (str root "/extensions/one.clj") "(ns pkg-one)\n")
  (spit (str root "/extensions/two.clj") "(ns pkg-two)\n")
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  root)

(defn- make-skill-package
  "A package with only a skill, for the settings-backed toggle tests."
  [root]
  (fs/create-dirs (str root "/skills/root"))
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  root)

(defn- with-settings
  "Run F with settings paths isolated and the given :user/:project settings
   maps written to the files. The read/save redefs are file-based, so
   read-after-write round-trips behave like production. CWD is a single
   stable temp dir whose .kmet IS the project dir (production layout), so
   project auto dirs created by a test actually resolve. The bundled layer
   and the built-in tools layer are stubbed out unless OPTS :bundled? /
   :tools? — the pre-existing tests assert exact row models of their own
   fixtures; the bundled/tools tests opt in and see the real layers."
  [f {:keys [user project bundled? tools?]}]
  (let [global-dir (tmp-dir)
        cwd (tmp-dir)
        project-dir (str (fs/path cwd ".kmet"))
        global-patch (str (fs/path global-dir "patch.edn"))
        project-patch (str (fs/path project-dir "patch.edn"))
        read-file (fn [path default]
                    (if (fs/exists? path)
                      (or (edn/read-string (slurp path)) default)
                      default))]
    (fs/create-dirs global-dir)
    (when user
      (spit (str (fs/path global-dir "settings.edn")) (pr-str user)))
    (when project
      (fs/create-dirs project-dir)
      (spit (str (fs/path project-dir "settings.edn")) (pr-str project)))
    (let [run (fn []
                (with-redefs-fn
                  (cond-> {#'cfg/read-global-settings-map (fn [] (read-file (str (fs/path global-dir "settings.edn")) {}))
                           #'cfg/read-project-settings-map (fn [] (read-file (str (fs/path project-dir "settings.edn")) {}))
                           #'cfg/save-setting! (fn [path value]
                                                 (spit (str (fs/path global-dir "settings.edn"))
                                                       (pr-str (assoc-in (read-file (str (fs/path global-dir "settings.edn")) {}) path value))))
                           #'cfg/save-project-setting! (fn [path value]
                                                         (fs/create-dirs project-dir)
                                                         (spit (str (fs/path project-dir "settings.edn"))
                                                               (pr-str (assoc-in (read-file (str (fs/path project-dir "settings.edn")) {}) path value))))
                           #'cfg/global-patch-path (fn [] global-patch)
                           #'cfg/project-patch-path (fn [] project-patch)
                           #'cfg/get-agent-dir (fn [] global-dir)
                           #'cfg/project-dir (fn [] project-dir)
                           #'fs/cwd (fn [] cwd)}
                    (not bundled?) (assoc #'bundled/artifacts (constantly []))
                    (not tools?) (assoc #'tools-config/tool-items (fn [_ _ & _] [])))
                  (fn []
                    (f {:global-dir global-dir :project-dir project-dir :cwd cwd
                        :global-patch global-patch :project-patch project-patch}))))]
      (run))))

(defn- layer-of
  "The patch layer at PATH, normalized to {:rows [] :patch []}."
  [path]
  (if (fs/exists? path)
    (let [data (edn/read-string (slurp path))]
      {:rows (vec (or (:rows data) []))
       :patch (vec (or (:patch data) []))})
    {:rows [] :patch []}))

(defn- extension-rows
  "The :ext/… sources a layer names, in row then patch order (a remove patch
   is not a row)."
  [layer]
  (->> (concat (:rows layer) (filter :plugin (:patch layer)))
       (keep :plugin)
       (filter #(and (keyword? %) (str/starts-with? (str %) ":ext/")))
       vec))

(defn- composed-sources
  "The extension sources the global layer composes (rows minus removes)."
  []
  (ext-layers/sources-of (:global (ext-layers/composition (ext-layers/layers)))))

(defn- render-lines [screen width]
  (protocols/render screen width))

(defn- project-file-packages [ctx]
  (let [f (str (fs/path (:project-dir ctx) "settings.edn"))]
    (if (fs/exists? f)
      (:packages (edn/read-string (slurp f)))
      [])))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-TAB "\t")
(def ^:const K-ESC "\u001b")
(def ^:const K-PGUP "\u001b[5~")
(def ^:const K-PGDN "\u001b[6~")

(defn- first-item-row
  "The first :item row of the screen."
  [screen]
  (first (filter #(= :item (:kind %)) (rc/screen-rows screen))))

(defn- item-rows [screen]
  (filterv #(= :item (:kind %)) (rc/screen-rows screen)))

;; ─── Global scope ─────────────────────────────────────────────────────────

(t/deftest test-screen-global-view
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)
              rows (rc/screen-rows screen)
              items (item-rows screen)]
          (t/is (= 3 (count items)))
          (t/is (= 2 (count (filter #(= :extensions (:resource-type (:item %))) items))))
          (t/is (some #(and (= :group (:kind %))
                            (str/includes? (:label (:group %)) (fs/file-name dir)))
                      rows))
          (t/is (every? (comp false? :enabled)
                        (filter #(= :extensions (:resource-type (:item %))) items))
                "extensions start uncomposed")
          (t/is (true? (:enabled (first (filter #(= :skills (:resource-type (:item %))) items))))
                "non-extension resources keep the settings state")
          (t/testing "header renders"
            ;; the scope hint is frame-truncated, and a Termux TMPDIR path
            ;; is wider than 80 columns: render wide enough that the whole
            ;; scope line is on screen
            (let [path (str (cfg/global-settings-path) " · " (cfg/global-patch-path))
                  w (max 80 (inc (u/visible-width path)))
                  lines (render-lines screen w)]
              (t/is (some #(str/includes? % "Global Resources") lines))
              (t/is (some #(str/includes? % "settings.edn") lines))
              (t/is (some #(str/includes? % "patch.edn") lines))
              (t/is (some #(str/includes? % "space toggle") lines))))
          (t/testing "item lines render the checkbox"
            (let [lines (render-lines screen 80)]
              (t/is (= 1 (count (filter #(re-find #"\[x\]" %) lines)))
                    "only the skill is enabled")))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-global-toggle-composes-extensions
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          ;; selection starts on the first item; space composes the extension
          (protocols/handle-input screen " ")
          (t/is (= [:ext/one] (extension-rows (layer-of (:global-patch ctx))))
                "the global scope writes the row")
          (t/is (true? (:enabled (first-item-row screen))))
          (t/is (= [dir] (:packages (edn/read-string
                                     (slurp (str (fs/path (:global-dir ctx) "settings.edn"))))))
                "settings are not the extension switch any more")
          (t/testing "space again disables it with a remove patch"
            (protocols/handle-input screen " ")
            (t/is (empty? (composed-sources)))
            (t/is (false? (:enabled (first-item-row screen)))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-keys-resolve-through-the-manager
  ;; Follow-up to P3: the screen's navigation/page/confirm/cancel resolve
  ;; through the global keybindings manager, so user overrides apply; the
  ;; kmet ctrl+p/ctrl+n alternates ride tui.select.up/down, so rebinding
  ;; them moves the alternates too. space and ctrl+c stay raw (pi's
  ;; config-selector matches those raw too).
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [kmgr (tui-kb/get-global-keybindings)
              selected-item (fn [screen]
                              (let [{:keys [rows selected]} @(:state-atom screen)]
                                (:item (nth rows selected))))]
          (tui-kb/set-user-bindings! kmgr {"tui.select.down" "ctrl+j"
                                           "tui.select.confirm" "ctrl+y"
                                           "tui.select.cancel" "ctrl+x"})
          (try
            (let [screen (rc/make-resource-config-screen :rows 40)
                  first-key (pkgs/item-key (selected-item screen))]
              ;; down (rebound) moves the selection to the second item row
              (protocols/handle-input screen "\u000a")        ;; ctrl+j
              (t/is (not= first-key (pkgs/item-key (selected-item screen)))
                    "ctrl+j moved the selection")
              (t/is (= (pkgs/item-key (:item (second (item-rows screen))))
                       (pkgs/item-key (selected-item screen)))
                    "…onto the second item")
              ;; confirm (rebound) toggles the selected item; enter no longer
              ;; does (it falls through to the search input, pi)
              (protocols/handle-input screen "\r")
              (t/is (false? (:enabled (first (item-rows screen))))
                    "enter was rebound away from confirm")
              (protocols/handle-input screen "\u0019")        ;; ctrl+y
              (t/is (true? (:enabled (first (item-rows screen))))
                    "ctrl+y toggles the selected item")
              ;; space stays a raw toggle (pi), and ctrl+n rides
              ;; tui.select.down — rebound away with it
              (protocols/handle-input screen " ")
              (t/is (false? (:enabled (first (item-rows screen))))
                    "space stays a raw toggle (pi)")
              (let [before (pkgs/item-key (selected-item screen))]
                (protocols/handle-input screen "\u000e")      ;; ctrl+n
                (t/is (= before (pkgs/item-key (selected-item screen)))
                      "ctrl+n was rebound away with tui.select.down")))
            (finally
              (tui-kb/set-user-bindings! kmgr {})))))
      {:user {:packages [dir]}})))

;; ─── Project scope ────────────────────────────────────────────────────────

(t/deftest test-screen-project-composition-toggle
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
          (t/is (= :inherit (:override-state (first-item-row screen))))
          (t/is (true? (:inherited? (first-item-row screen))))
          (t/is (false? (:enabled (first-item-row screen))))
          (t/testing "inherit → load composes a project row"
            (protocols/handle-input screen " ")
            (t/is (= [:ext/one] (extension-rows (layer-of (:project-patch ctx)))))
            (t/is (= :load (:override-state (first-item-row screen))))
            (t/is (true? (:enabled (first-item-row screen)))))
          (t/testing "load → inherit drops the project row"
            (protocols/handle-input screen " ")
            (t/is (empty? (extension-rows (layer-of (:project-patch ctx)))))
            (t/is (= :inherit (:override-state (first-item-row screen))))
            (t/is (false? (:enabled (first-item-row screen)))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-project-composition-unloads-a-composed-extension
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (spit (:global-patch ctx)
              (pr-str {:rows [{:id :ext/one :plugin :ext/one}] :patch []}))
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
          (t/is (= :inherit (:override-state (first-item-row screen))))
          (t/is (true? (:enabled (first-item-row screen))))
          (t/testing "inherit → unload writes a remove patch for the global row"
            (protocols/handle-input screen " ")
            (t/is (= [{:id :ext/one :remove true}]
                     (:patch (layer-of (:project-patch ctx)))))
            (t/is (= :unload (:override-state (first-item-row screen))))
            (t/is (false? (:enabled (first-item-row screen)))))
          (t/testing "unload → inherit drops the remove patch"
            (protocols/handle-input screen " ")
            (t/is (empty? (:patch (layer-of (:project-patch ctx)))))
            (t/is (= :inherit (:override-state (first-item-row screen))))
            (t/is (true? (:enabled (first-item-row screen)))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-project-scope-package-tri-state
  (let [dir (make-skill-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
          (t/is (every? #(not (:inherited? %)) (item-rows screen)))
         ;; first space: inherit → unload (writes -pattern, entry becomes an
         ;; object form)
          (protocols/handle-input screen " ")
          (let [entries (project-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (map? (first entries)))
            (t/is (str/starts-with? (first (:skills (first entries))) "-")))
          (t/is (= :unload (:override-state (first-item-row screen))))
         ;; second space: unload → load
          (protocols/handle-input screen " ")
          (let [entries (project-file-packages ctx)]
            (t/is (str/starts-with? (first (:skills (first entries))) "+")))
          (t/is (= :load (:override-state (first-item-row screen))))
         ;; third space: load → inherit; the entry returns to its plain
         ;; source string
          (protocols/handle-input screen " ")
          (t/is (= :inherit (:override-state (first-item-row screen))))
          (let [entries (project-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (not (map? (first entries)))))))
      {:project {:packages [dir]}})))

(t/deftest test-screen-single-extension-composes
  ;; a single-file package is one extension like any other: space composes it
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n(def plugin {:name :ext/single-row :apply (fn [_ _] nil)})\n")
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)
              item (:item (first-item-row screen))]
          (t/is (pkgs/single-extension-item? item))
          (protocols/handle-input screen " ")
          (t/is (= [:ext/ext] (extension-rows (layer-of (:global-patch ctx)))))
          (t/is (true? (:enabled (first-item-row screen))))))
      {:user {:packages [f]}})))

(t/deftest test-screen-project-only-extension-composes
  ;; an extension discovered only in the project scope has no item in the user
  ;; view; its inherited state is the composition's global one, so the first
  ;; space composes it in the project layer instead of trying to unload it
  (let [dir (tmp-dir)]
    (fs/create-dirs (str (fs/path dir "extensions")))
    (spit (str (fs/path dir "extensions" "one.clj")) "(ns pkg-one)\n")
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)
              row (first-item-row screen)]
          (t/is (= :extensions (:resource-type (:item row))))
          (t/is (false? (:inherited? row)) "no item in the user view")
          (t/is (false? (:inherited-enabled row))
                "the inherited state is the composition's, not 'settings say yes'")
          (protocols/handle-input screen " ")
          (t/is (= [{:id :ext/one :plugin :ext/one}]
                   (:patch (layer-of (:project-patch ctx)))))
          (t/is (true? (:enabled (first-item-row screen))))))
      {:project {:packages [dir]}})))

(t/deftest test-screen-unreadable-extension-is-marked
  ;; a manifest that cannot be resolved cannot be composed: the row says so
  ;; and the toggle is inert instead of silently doing nothing
  (let [dir (tmp-dir)]
    (spit (str (fs/path dir "extension.edn")) "{:name")
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)
              row (first-item-row screen)]
          (t/is (true? (get-in row [:item :unreadable?])))
          (t/is (some #(str/includes? % "unreadable manifest")
                      (render-lines screen 100)))
          (protocols/handle-input screen " ")
          (t/is (false? (:enabled (first-item-row screen))))))
      {:user {:packages [dir]}})))

;; ─── Search and scope switching ───────────────────────────────────────────

(t/deftest test-screen-query-filter
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (rc/screen-set-query! screen "two")
          (let [items (item-rows screen)]
            (t/is (= 1 (count items)))
            (t/is (str/includes? (:path (:item (first items))) "two.clj")))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-search-reuses-the-resolved-views
  ;; a keystroke only filters the rows the screen has: re-resolving would
  ;; re-read the settings and every extension manifest per character
  (let [dir (make-package (tmp-dir))
        real pkgs/resolve-package-items
        calls (atom 0)]
    (with-redefs [pkgs/resolve-package-items
                  (fn [& args] (swap! calls inc) (apply real args))]
      (with-settings
        (fn [_]
          (let [screen (rc/make-resource-config-screen :rows 40)
                built @calls]
            (t/is (pos? built))
            (rc/screen-set-query! screen "one")
            (t/is (= built @calls) "the query change reused the views")
            (rc/screen-set-write-scope! screen :project)
            (t/is (< built @calls) "a scope switch re-reads the files")))
        {:user {:packages [dir]}}))))

(t/deftest test-screen-tab-switches-scope
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40 :project-mode? true)]
          (t/is (= :global (rc/screen-write-scope screen)))
          (protocols/handle-input screen K-TAB)
          (t/is (= :project (rc/screen-write-scope screen)))
          (protocols/handle-input screen K-TAB)
          (t/is (= :global (rc/screen-write-scope screen)))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-rows-interleave
  ;; each package group is followed by its own subgroups and items (pi
  ;; buildFlatList) — not all group/subgroup headers first
  (let [a (str (fs/path (tmp-dir) "pkgA"))
        b (str (fs/path (tmp-dir) "pkgB"))]
    (doseq [root [a b]]
      (fs/create-dirs (str root "/extensions"))
      (spit (str root "/extensions/one.clj") "(ns x)\n"))
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (t/is (= [:group :subgroup :item :group :subgroup :item]
                   (mapv :kind (rc/screen-rows screen))))))
      {:user {:packages [a b]}})))

(t/deftest test-screen-paging
  ;; page up/down jump maxVisible item rows (pi pageUp/pageDown): the
  ;; target row is scanned inclusively and headers are skipped
  (let [dir (str (fs/path (tmp-dir) "pkg"))]
    (fs/create-dirs (str dir "/extensions"))
    (doseq [i (range 10)]
      (spit (str dir "/extensions/e" i ".clj") "(ns x)\n"))
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 16)]
         ;; 12 rows: group, subgroup, 10 items; maxVisible = 16 - 11 = 5
          (t/is (= 2 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGUP)
          (t/is (= 2 (rc/screen-selected screen)) "page-up at the first item keeps it")
          (protocols/handle-input screen K-PGDN)
          (t/is (= 7 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGDN)
          (t/is (= 11 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGUP)
          (t/is (= 6 (rc/screen-selected screen)))))
      {:user {:packages [dir]}})))

;; ─── Top-level / auto-dir groups (pi: top-level metadata) ──────────────────

(t/deftest test-screen-top-level-groups-and-toggle
  ;; the auto roots resolve as top-level groups (pi buildGroups over the
  ;; unified resolution); an auto-discovered extension toggles into the
  ;; composition, not the settings resource array
  (with-settings
    (fn [{:keys [global-dir global-patch]}]
      (fs/create-dirs (str global-dir "/extensions"))
      (spit (str global-dir "/extensions/e.clj") "(ns e)\n")
      (let [screen (rc/make-resource-config-screen :rows 40)
            rows (rc/screen-rows screen)
            items (item-rows screen)]
        (t/is (= 1 (count items)))
        (t/is (= :top-level (get-in (:item (first items)) [:metadata :origin])))
        (t/is (some #(and (= :group (:kind %))
                          (str/starts-with? (:label (:group %)) "User ("))
                    rows))
        (t/testing "space composes the extension in the global layer"
          (protocols/handle-input screen " ")
          (t/is (= [:ext/e] (extension-rows (layer-of global-patch))))
          (t/is (not (fs/exists? (str (fs/path global-dir "settings.edn"))))
                "no settings entry is written"))
        (t/is (true? (:enabled (first (item-rows screen)))))
        (t/testing "space again disables it with a remove patch"
          (protocols/handle-input screen " ")
          (t/is (empty? (composed-sources))))))
    {}))

(t/deftest test-screen-top-level-settings-group-labels
  ;; pi getGroupLabel: top-level settings-entry groups are "User settings" /
  ;; "Project settings"; only auto-dir groups render their base dir
  (with-settings
    (fn [{:keys [global-dir project-dir]}]
      (spit (str global-dir "/solo.clj") "(ns solo)\n")
      (spit (str project-dir "/proj.clj") "(ns proj)\n")
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            labels (keep #(when (= :group (:kind %)) (:label (:group %)))
                         (rc/screen-rows screen))]
        (t/is (some #(= "User settings" %) labels))
        (t/is (some #(= "Project settings" %) labels))))
    {:user {:extensions ["solo.clj"]}
     :project {:extensions ["proj.clj"]}}))

(t/deftest test-screen-project-top-level-tri-state
  ;; inherited user top-level skill: project scope cycles inherit/unload/load
  ;; and writes the absolute path + +/- pattern into the project settings
  ;; array (pi setProjectTopLevelOverride)
  (with-settings
    (fn [{:keys [global-dir project-dir]}]
      (spit (str global-dir "/solo.md")
            "---\nname: solo\ndescription: solo\n---\nbody\n")
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            item-path (str global-dir "/solo.md")]
        (t/is (true? (:inherited? (first (item-rows screen)))))
        (t/is (= :inherit (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [(slash item-path) (str "-" (slash item-path))] (slash (:skills settings)))))
        (t/is (= :unload (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [(slash item-path) (str "+" (slash item-path))] (slash (:skills settings)))))
        (t/is (= :load (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [] (:skills settings))))
        (t/is (= :inherit (:override-state (first (item-rows screen)))))))
    {:user {:skills ["solo.md"]}}))

;; ─── Bundled extensions ─────────────────────────────

(t/deftest test-screen-bundled-group-and-global-toggle
  ;; the app-provided set renders first as ONE fixed group (artifact base
  ;; dirs do not split it), all off by default; space composes the bundled
  ;; extension in the global layer (no settings entry)
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40)
            items (item-rows screen)
            first-row (first-item-row screen)]
        (t/is (= 11 (count items)))
        (t/is (every? #(= :bundled (get-in (:item %) [:metadata :origin])) items))
        (t/is (= "Bundled with kmet" (:label (:group first-row))))
        (t/is (= 1 (count (filter #(= :group (:kind %)) (rc/screen-rows screen)))))
        (t/is (= (set (map :name (bundled/artifacts)))
                 (set (map #(get-in (:item %) [:metadata :display-name]) items)))
              "manifest names are the row names")
        (t/is (every? #(false? (:enabled %)) items))
        (t/is (some #(str/includes? % "bundled") (render-lines screen 100)))
        (t/testing "space composes the selected bundled extension"
          (protocols/handle-input screen " ")
          (t/is (= [:ext/clojure] (extension-rows (layer-of (:global-patch ctx)))))
          (t/is (true? (:enabled (first-item-row screen))))
          (t/is (nil? (:bundled-extensions
                       (when (fs/exists? (str (fs/path (:global-dir ctx) "settings.edn")))
                         (edn/read-string (slurp (str (fs/path (:global-dir ctx) "settings.edn")))))))))
        (t/testing "space again disables it with a remove patch"
          (protocols/handle-input screen " ")
          (t/is (empty? (composed-sources)))
          (t/is (false? (:enabled (first-item-row screen)))))))
    {:bundled? true}))

(t/deftest test-screen-bundled-project-override-cycle
  ;; project scope: the bundled row is inherited from the composition or
  ;; from a global row; the toggle sets the opposite state in .kmet/patch.edn
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)]
        (t/is (= :inherit (:override-state (first-item-row screen))))
        (t/is (true? (:inherited? (first-item-row screen))))
        (t/is (some #(and (str/includes? % "clojure")
                          (str/includes? % "bundled"))
                    (render-lines screen 100)))
        (t/testing "inherit → load composes a project row"
          (protocols/handle-input screen " ")
          (t/is (= [:ext/clojure] (extension-rows (layer-of (:project-patch ctx)))))
          (t/is (= :load (:override-state (first-item-row screen)))))
        (t/testing "load → inherit drops the row"
          (protocols/handle-input screen " ")
          (t/is (empty? (extension-rows (layer-of (:project-patch ctx)))))
          (t/is (= :inherit (:override-state (first-item-row screen)))))))
    {:bundled? true}))

(t/deftest test-screen-bundled-project-unloads-a-composed-extension
  (with-settings
    (fn [ctx]
      (spit (:global-patch ctx)
            (pr-str {:rows [{:id :ext/clojure :plugin :ext/clojure}] :patch []}))
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)]
        (t/is (= :inherit (:override-state (first-item-row screen))))
        (t/is (true? (:enabled (first-item-row screen))))
        (t/testing "inherit → unload composes a remove patch"
          (protocols/handle-input screen " ")
          (t/is (= [{:id :ext/clojure :remove true}]
                   (:patch (layer-of (:project-patch ctx)))))
          (t/is (= :unload (:override-state (first-item-row screen))))
          (t/is (false? (:enabled (first-item-row screen)))))
        (t/testing "unload → inherit drops the remove patch"
          (protocols/handle-input screen " ")
          (t/is (empty? (:patch (layer-of (:project-patch ctx)))))
          (t/is (= :inherit (:override-state (first-item-row screen)))))))
    {:bundled? true}))

(t/deftest test-screen-global-composition-drives-the-rows
  ;; a global composition row shows up as enabled in both scopes
  (with-settings
    (fn [ctx]
      (spit (:global-patch ctx)
            (pr-str {:rows [{:id :ext/clojure :plugin :ext/clojure}] :patch []}))
      (let [screen (rc/make-resource-config-screen :rows 40)
            row (first (filter #(= "clojure" (get-in (:item %) [:metadata :display-name]))
                               (item-rows screen)))]
        (t/is (true? (:enabled row)))
        (t/is (some #(str/includes? % "clojure") (render-lines screen 100)))))
    {:bundled? true}))

;; ─── Built-in tools (under the bundled group, before extensions) ──

(t/deftest test-screen-builtin-tools-group-and-global-toggle
  ;; the built-in tools group joins the "Bundled with kmet" group
  ;; (before extensions); all on by default; space writes the global
  ;; :default-tools list. An unset setting keeps its modifier shape:
  ;; disabling writes -name so future built-ins inherit, enabling
  ;; drops the entry again.
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40)
            settings (fn [] (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
                              (when (fs/exists? f)
                                (edn/read-string (slurp f)))))
            items (item-rows screen)
            first-row (first-item-row screen)]
        (t/is (= 7 (count items)))
        (t/is (every? #(= :tools (:resource-type (:item %))) items))
        (t/is (= "Bundled with kmet" (:label (:group first-row))))
        (t/is (= "bash" (get-in (:item first-row) [:metadata :display-name]))
              "rows sort by name within the group")
        (t/is (every? :enabled
                      (remove #(#{"run_code" "eval"}
                                (get-in (:item %) [:metadata :display-name]))
                              items))
              "default-on rows are on; the opt-in rows are off")
        (t/is (not-any? :enabled
                        (filter #(#{"run_code" "eval"}
                                  (get-in (:item %) [:metadata :display-name]))
                                items)))
        (t/is (some #(str/includes? % "Bundled with kmet") (render-lines screen 100)))
        (t/testing "space disables the selected tool with a -name modifier"
          (protocols/handle-input screen " ")
          (t/is (= ["-bash"] (:default-tools (settings))))
          (t/is (false? (:enabled (first-item-row screen)))))
        (t/testing "space again removes the entry (unset = every built-in)"
          (protocols/handle-input screen " ")
          (t/is (nil? (:default-tools (settings))))
          (t/is (true? (:enabled (first-item-row screen)))))))
    {:tools? true}))

(t/deftest test-screen-builtin-tools-plain-selection-rewrite
  ;; a :default-tools list with plain names is rewritten as the effective
  ;; selection (plain names replace the whole set, pi: resolveDefaultTools)
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40)
            settings (fn [] (:default-tools (edn/read-string (slurp (str (fs/path (:global-dir ctx) "settings.edn"))))))
            row-of (fn [name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (true? (:enabled (row-of "read"))))
        (t/is (false? (:enabled (row-of "bash"))))
        (t/testing "disabling the only selected tool writes the empty selection"
          (rc/screen-set-query! screen "read")
          (protocols/handle-input screen " ")
          (t/is (= [] (settings)))
          (t/is (false? (:enabled (row-of "read")))))
        (t/testing "enabling one tool from the empty selection writes [name]"
          (rc/screen-set-query! screen "bash")
          (protocols/handle-input screen " ")
          (t/is (= ["bash"] (settings)))
          (t/is (true? (:enabled (row-of "bash")))))))
    {:user {:default-tools ["read"]}
     :tools? true}))

(t/deftest test-screen-builtin-tools-merged-state
  ;; the global view resolves the user list only; the project view resolves
  ;; the scope-merged entries (kmet.app.config/merge-tool-entries)
  (with-settings
    (fn [_]
      (let [rows-of (fn [screen]
                      (item-rows screen))
            row-of (fn [rows name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name])) rows)))
            global (rc/make-resource-config-screen :rows 40)
            project (rc/make-resource-config-screen :rows 40
                                                    :write-scope :project
                                                    :project-mode? true)]
        (t/is (true? (:enabled (row-of (rows-of global) "read"))))
        (t/is (false? (:enabled (row-of (rows-of global) "bash"))))
        (t/is (true? (:enabled (row-of (rows-of project) "read"))))
        (t/is (true? (:enabled (row-of (rows-of project) "bash"))))))
    {:user {:default-tools ["read"]}
     :project {:default-tools ["+bash"]}
     :tools? true}))

(t/deftest test-screen-builtin-tools-project-override-cycle
  ;; project scope: the built-in tool row is an inherited global setting;
  ;; the tri-state cycle writes +/- deltas into the project :default-tools
  ;; list and clears the key again at :inherit
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            settings (fn [] (let [f (str (fs/path (:project-dir ctx) "settings.edn"))]
                              (when (fs/exists? f)
                                (:default-tools (edn/read-string (slurp f))))))
            row (first-item-row screen)]
        (t/is (= :inherit (:override-state row)))
        (t/is (true? (:inherited? row)))
        (t/testing "inherit → unload writes -name"
          (protocols/handle-input screen " ")
          (t/is (= ["-bash"] (settings)))
          (t/is (= :unload (:override-state (first-item-row screen))))
          (t/is (false? (:enabled (first-item-row screen)))))
        (t/testing "unload → load writes +name"
          (protocols/handle-input screen " ")
          (t/is (= ["+bash"] (settings)))
          (t/is (= :load (:override-state (first-item-row screen))))
          (t/is (true? (:enabled (first-item-row screen)))))
        (t/testing "load → inherit clears the project key"
          (protocols/handle-input screen " ")
          (t/is (nil? (settings)))
          (t/is (= :inherit (:override-state (first-item-row screen)))))))
    {:tools? true}))

(t/deftest test-screen-builtin-tools-follow-the-composition-rows
  ;; a built-in tool is a composition row (`:tool/bash`), so a layer that
  ;; removes the row is a tool the screen must not offer — the run would not
  ;; mount it, whatever the :default-tools setting says
  (with-settings
    (fn [{:keys [project-patch]}]
      (fs/create-dirs (fs/parent project-patch))
      (spit project-patch "{:rows [] :patch [{:id :tool/bash :remove true}]}\n")
      (let [screen (rc/make-resource-config-screen :rows 40)
            row-of (fn [name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (= 7 (count (item-rows screen)))
              "the row is still listed, so the screen says why the tool is off")
        (t/is (false? (:enabled (row-of "bash")))
              "the layer removed the row, so the tool is not mounted")
        (t/is (true? (:enabled (row-of "read"))))
        (t/is (= :tool/bash (get-in (:item (row-of "bash")) [:metadata :row-id]))
              "and the item names the row it is")))
    {:tools? true}))

(t/deftest test-mounted-tool-names-folds-layers-in-order
  ;; the fold is compose's for the tool rows: a remove then a re-add leaves the
  ;; tool mounted
  (t/is (= ["read" "bash"]
           (tools-config/mounted-tool-names
            ["bash" "read"]
            [{:data {:patch [{:id :tool/bash :remove true}]}}
             {:data {:patch [{:id :tool/bash :plugin 'kmet.app.tools.bash/plugin}]}}]))
        "a later layer's row puts it back, at the end — as compose's insert does")
  (t/is (= ["read"]
           (tools-config/mounted-tool-names
            ["bash" "read"]
            [{:data {:patch [{:id :tool/bash :remove true}]}}]))
        "and a removal with nothing after it takes it out")
  (t/is (= (vec tools-registry/builtin-tool-names)
           (tools-config/mounted-tool-names [{:data {}}]))
        "the one-arity answers the built-in list when no layer touches a tool row"))

(t/deftest test-screen-builtin-tools-group-order
  ;; built-in tools sit in the single "Bundled with kmet" group,
  ;; Tools subgroup before Extensions
  (with-settings
    (fn [_]
      (let [screen (rc/make-resource-config-screen :rows 40)
            groups (filterv #(= :group (:kind %)) (rc/screen-rows screen))
            tools-rows (filter #(= :tools (:resource-type (:item %))) (item-rows screen))]
        (t/is (= 1 (count groups)))
        (t/is (= "Bundled with kmet" (:label (:group (first groups)))))
        (t/is (pos? (count tools-rows)))
        (t/is (every? #(= :tools (:resource-type (:item %))) tools-rows))))
    {:tools? true :bundled? true}))

;; ─── Opt-in built-in tools (:default-enabled? false) ──────────────────────

(t/deftest test-screen-opt-in-builtin-tools
  ;; run_code and eval are mounted rows with :default-enabled? false: the
  ;; config rows show off, enabling writes +name, disabling clears it
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40)
            settings (fn [] (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
                              (when (fs/exists? f)
                                (edn/read-string (slurp f)))))
            row-of (fn [name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (false? (:enabled (row-of "run_code"))))
        (t/is (false? (:enabled (row-of "eval"))))
        (rc/screen-set-query! screen "run_code")
        (t/testing "enabling writes +name, not an entry removal"
          (protocols/handle-input screen " ")
          (t/is (= ["+run_code"] (:default-tools (settings))))
          (t/is (true? (:enabled (row-of "run_code")))))
        (t/testing "disabling clears the entry again"
          (protocols/handle-input screen " ")
          (t/is (nil? (:default-tools (settings))))
          (t/is (false? (:enabled (row-of "run_code")))))))
    {:tools? true})
  (with-settings
    (fn [_]
      (let [screen (rc/make-resource-config-screen :rows 40)
            row-of (fn [name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (true? (:enabled (row-of "run_code")))
              "a plain :default-tools list names the opt-in explicitly")
        (t/is (false? (:enabled (row-of "read"))))))
    {:user {:default-tools ["run_code"]} :tools? true}))

;; ─── Non-built-in tool rows (:enabled-tools names and :meta :tools) ───────

(t/deftest test-screen-enabled-tools-row-toggle
  ;; :enabled-tools names a tool no built-in registry answers to: the row is
  ;; a static projection shown so the delta can be undone, and its toggle
  ;; writes :enabled-tools, never :default-tools
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40)
            settings (fn [] (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
                              (when (fs/exists? f)
                                (edn/read-string (slurp f)))))
            row-of (fn [name]
                     (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (some? (row-of "mcp_tool")))
        (t/is (false? (:enabled (row-of "mcp_tool"))))
        (t/is (= :enabled-tools
                 (get-in (:item (row-of "mcp_tool")) [:metadata :setting])))
        (rc/screen-set-query! screen "mcp_tool")
        (t/testing "enabling clears the -name delta"
          (protocols/handle-input screen " ")
          (t/is (nil? (:enabled-tools (settings))))
          (t/is (nil? (:default-tools (settings))))
          (t/is (true? (:enabled (row-of "mcp_tool")))))
        (t/testing "disabling writes it again (undo)"
          (protocols/handle-input screen " ")
          (t/is (= ["-mcp_tool"] (:enabled-tools (settings))))
          (t/is (false? (:enabled (row-of "mcp_tool")))))))
    {:user {:enabled-tools ["-mcp_tool"]} :tools? true}))

(t/deftest test-screen-enabled-tools-project-override-cycle
  ;; the row exists because the user selection names it; project scope
  ;; cycles its delta in .kmet/settings.edn, exactly like a built-in's
  (with-settings
    (fn [ctx]
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            settings (fn [] (let [f (str (fs/path (:project-dir ctx) "settings.edn"))]
                              (when (fs/exists? f)
                                (:enabled-tools (edn/read-string (slurp f))))))
            row-of (fn []
                     (first (filter #(= "mcp_tool"
                                        (get-in (:item %) [:metadata :display-name]))
                                    (item-rows screen))))]
        (t/is (= :inherit (:override-state (row-of))))
        (t/is (true? (:inherited? (row-of))))
        (t/is (true? (:enabled (row-of))))
        (rc/screen-set-query! screen "mcp_tool")
        (t/testing "inherit → unload writes -name"
          (protocols/handle-input screen " ")
          (t/is (= ["-mcp_tool"] (settings)))
          (t/is (= :unload (:override-state (row-of))))
          (t/is (false? (:enabled (row-of)))))
        (t/testing "unload → load writes +name"
          (protocols/handle-input screen " ")
          (t/is (= ["+mcp_tool"] (settings)))
          (t/is (= :load (:override-state (row-of)))))
        (t/testing "load → inherit clears the entry"
          (protocols/handle-input screen " ")
          (t/is (nil? (settings)))
          (t/is (= :inherit (:override-state (row-of)))))))
    {:user {:enabled-tools ["mcp_tool"]} :tools? true}))

(defn- make-meta-extension
  "A directory extension whose opaque :meta declares TOOLS; nothing loads
   it in these tests."
  [root tools]
  (fs/create-dirs root)
  (spit (str root "/extension.edn")
        (pr-str {:name "meta-ext"
                 :plugin 'meta-ext.main/plugin
                 :meta {:tools tools}}))
  root)

(t/deftest test-screen-extension-meta-tool-rows
  (let [ext (make-meta-extension (str (tmp-dir) "/meta-ext")
                                 [{:name "meta_tool" :doc "x"}
                                  {:name ""}
                                  {:name 42}
                                  "junk"])]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)
              settings (fn [] (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
                                (when (fs/exists? f)
                                  (edn/read-string (slurp f)))))
              row-of (fn [name]
                       (first (filter #(= name (get-in (:item %) [:metadata :display-name]))
                                      (item-rows screen))))]
          (t/is (some? (row-of "meta_tool")))
          (t/is (nil? (row-of "")))
          (t/is (nil? (row-of "junk")))
          (t/is (true? (:enabled (row-of "meta_tool"))))
          (t/is (= :package (get-in (:item (row-of "meta_tool"))
                                    [:metadata :origin])))
          (rc/screen-set-query! screen "meta_tool")
          (protocols/handle-input screen " ")
          (t/is (= ["-meta_tool"] (:enabled-tools (settings)))
                "the static extension row writes the delta")
          (t/is (nil? (:default-tools (settings))))))
      {:user {:packages [ext]} :tools? true})))

;; ─── Project auto-dir items (regression: SCI duplicate-key crash) ─────────

(t/deftest test-screen-project-auto-item-renders-and-toggles
  ;; regression: a project auto-dir item (base dir IS the project scope
  ;; root) used to crash the render — the override-patterns set literal
  ;; evaluated to duplicate strings, which babashka/SCI rejects.
  (with-settings
    (fn [{:keys [project-dir]}]
      (fs/create-dirs (str project-dir "/prompts"))
      (spit (str project-dir "/prompts/test.md") "# t\n")
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            items (item-rows screen)
            proj-item (first (filter #(= :project (get-in (:item %) [:metadata :scope]))
                                     items))]
        (t/is (some? proj-item))
        (t/is (= :inherit (:override-state proj-item)))
        (t/is (true? (:enabled proj-item))
              "project files are enabled by default (no trust gate)")
        (t/testing "renders without throwing"
          (t/is (pos? (count (render-lines screen 80)))))
        (t/testing "space cycles the project override"
          (rc/screen-set-query! screen "test.md")
          (protocols/handle-input screen " ")
          (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
            (t/is (= ["-prompts/test.md"] (:prompts settings))))
          (t/is (= :unload (:override-state (first (item-rows screen))))))))
    {}))

(t/deftest test-screen-global-view-excludes-project-scope
  ;; pi's global config view runs an untrusted settings manager: project
  ;; settings AND project auto dirs are absent, not just project entries.
  (with-settings
    (fn [{:keys [project-dir]}]
      (fs/create-dirs (str project-dir "/prompts"))
      (spit (str project-dir "/prompts/test.md") "# t\n")
      (let [screen (rc/make-resource-config-screen :rows 40)]
        (t/is (not-any? #(= :project (get-in (:item %) [:metadata :scope]))
                        (item-rows screen)))))
    {}))

;; ─── The search field (a tag-owned [:input]) ──────────────────────────────

(t/deftest test-search-field-is-tag-owned-and-filters-on-typing
  ;; the field is a [:input] element: state carries its text, keys are
  ;; forwarded into it and every forwarded key re-filters (pi:
  ;; searchInput.handleInput + filterItems)
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (t/testing "typing filters through the field"
            (doseq [k ["t" "w"]]
              (protocols/handle-input screen k))
            (let [items (item-rows screen)]
              (t/is (= 1 (count items)))
              (t/is (str/includes? (:path (:item (first items))) "two.clj")))
            (t/is (some #(str/includes? % "tw") (render-lines screen 80))
                  "the field renders what state holds"))
          (let [s2 (rc/make-resource-config-screen :rows 40)]
            (rc/screen-set-query! s2 "one")
            (t/is (some #(str/includes? % "one") (render-lines s2 80))
                  "state feeds the field — screen-set-query! shows up"))))
      {:user {:packages [dir]}})))

(t/deftest test-search-field-focus-and-caret
  ;; the screen is IFocusable now (pi: ConfigSelectorComponent.setFocused
  ;; forwards to searchInput.focused) — the tree derives the field's caret
  ;; from that flag, and the caret survives re-renders because the field is
  ;; fed :value/:cursor from state
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (t/testing "the focus flag round-trips"
            (t/is (false? (protocols/focused screen)))
            (protocols/set-focused! screen true)
            (t/is (true? (protocols/focused screen))))
          (t/testing "a focused field renders its caret"
            (protocols/set-focused! screen false)
            (t/is (not-any? #(str/includes? % u/CURSOR-MARKER)
                            (render-lines screen 80)))
            (protocols/set-focused! screen true)
            (t/is (some #(str/includes? % u/CURSOR-MARKER)
                        (render-lines screen 80))))
          (t/testing "…at the end of the typed text"
            (doseq [k ["t" "w"]] (protocols/handle-input screen k))
            (t/is (some #(str/includes? % (str "tw" u/CURSOR-MARKER))
                        (render-lines screen 80))))))
      {:user {:packages [dir]}})))

(t/deftest test-frame-memoizes-idle-and-pays-one-body-per-change
  ;; the frame is a mounted root whose body tracks the state atom: an idle
  ;; render re-derives nothing, a state change re-derives exactly once, and
  ;; navigation never rebuilds the chrome elements
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (render-lines screen 80)
          (engine/reset-counters!)
          (render-lines screen 80)
          (t/is (zero? (:bodies-run (engine/counters)))
                "an idle frame runs no body")
          (t/is (zero? (:constructs (engine/counters)))
                "…and rebuilds no element")
          (engine/reset-counters!)
          (protocols/handle-input screen K-DOWN)
          (render-lines screen 80)
          (t/is (= 1 (:bodies-run (engine/counters)))
                "one selection change re-derives the body once")
          (engine/reset-counters!)
          (protocols/handle-input screen K-DOWN)
          (protocols/handle-input screen K-DOWN)
          (render-lines screen 80)
          (t/is (= 1 (:bodies-run (engine/counters)))
                "any number of keys between frames still settles in one run")))
      {:user {:packages [dir]}})))

;; ─── Close paths and empty views ──────────────────────────────────────────

(t/deftest test-close-paths-call-on-close
  ;; escape and ctrl+c both close the screen (pi onExit) — the callback is
  ;; what stops the standalone TUI in run-config-screen
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [closed (atom [])
              screen (rc/make-resource-config-screen :rows 40
                                                     :on-close #(swap! closed conj :closed))]
          (protocols/handle-input screen K-ESC)
          (t/is (= [:closed] @closed) "escape closes")
          (protocols/handle-input screen "\u0003")
          (t/is (= [:closed :closed] @closed) "ctrl+c closes too")))
      {:user {:packages [dir]}})))

(t/deftest test-empty-and-unmatched-views-render-the-frame
  ;; the row math must survive zero rows: an unmatched filter and a
  ;; completely empty settings view both render the chrome, with nothing
  ;; selected and no clipped counter
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (rc/screen-set-query! screen "zzz-no-match")
          (t/is (= [] (rc/screen-rows screen)))
          (let [lines (render-lines screen 80)]
            (t/is (some #(str/includes? % "Global Resources") lines))
            (t/is (not-any? #(re-find #"\(\d+/\d+\)" %) lines)
                  "no clipped counter at all when nothing is clipped"))
          (protocols/handle-input screen K-DOWN)
          (protocols/handle-input screen " ")
          (t/is (= 0 (rc/screen-selected screen)) "navigation on an empty view is a no-op")))
      {:user {:packages [dir]}}))
  (with-settings
    (fn [_]
      (let [screen (rc/make-resource-config-screen :rows 40)]
        (t/is (= [] (rc/screen-rows screen)))
        (t/is (pos? (count (render-lines screen 80))) "the chrome still renders")))
    {}))

(t/deftest test-every-rendered-line-fits-the-frame
  ;; the frame truncates an over-width line *and* logs a kmet-crash.log
  ;; anomaly for it, so the screen must never emit one. The scope hint (the
  ;; agent-dir path) and the subgroup labels used to: they are longer than a
  ;; narrow frame and the old renderer emitted them untruncated.
  (let [dir (make-package (tmp-dir))]
    ;; a wide-character name (each char is 2 columns) must truncate by
    ;; visible width like any other
    (spit (str dir "/extensions/\u4e00\u952e.clj") "(ns wide)\n")
    (with-settings
      (fn [_]
        (doseq [[label screen]
                [["global" (rc/make-resource-config-screen :rows 24)]
                 ["project" (rc/make-resource-config-screen
                             :rows 24 :write-scope :project :project-mode? true)]]]
          (t/is (some #(str/includes? % "\u4e00\u952e") (render-lines screen 81))
                (str label ": the 2-column name is actually on screen"))
          ;; 2 is the floor that matters: a 0/1-column terminal also gets a
          ;; 1-column border and the input's 2-column "> " prompt in the
          ;; pre-conversion build (degenerate, unchanged, not this screen's)
          (doseq [w [2 3 4 5 6 7 8 9 10 11 20 21 30 31 44 45 60 61 80 81]]
            (t/is (every? #(<= (u/visible-width %) w) (render-lines screen w))
                  (str label ": every line fits " w " columns")))))
      {:user {:packages [dir]}})))

(t/deftest test-counter-line-truncates-at-a-huge-count
  ;; the clipped counter is truncated like every other line. Rows are
  ;; normally derived, so this injects a synthetic list to reach a count
  ;; whose "(1/120000)" is wider than a 9-column frame — without the
  ;; truncation that line would be a kmet-crash.log anomaly in the frame.
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 24)
              row {:kind :item
                   :item {:path "/p/e.clj" :resource-type :extensions}
                   :group {} :subgroup {}
                   :override-state :inherit :inherited? false :enabled true}
              rows (vec (repeat 120000 row))]
          (swap! (:state-atom screen) assoc :rows rows :item-rows rows :selected 0)
          (let [lines (render-lines screen 9)]
            (t/is (some #(str/includes? % "(1/1200") lines)
                  "the clipped counter renders")
            (t/is (every? #(<= (u/visible-width %) 9) lines)
                  "…clipped to the frame, not overflowing it"))))
      {:user {:packages [dir]}})))

(t/deftest test-tab-without-a-project-dir-is-a-no-op
  ;; projectModeAvailable false: tab must not switch scopes (pi)
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40 :write-scope :project)]
          (protocols/handle-input screen K-TAB)
          (t/is (= :project (rc/screen-write-scope screen)))
          (protocols/handle-input screen K-TAB)
          (t/is (= :project (rc/screen-write-scope screen)))))
      {:user {:packages [dir]}})))

(t/deftest test-window-math-at-a-tiny-terminal
  ;; max-visible floors at 5 (pi): a 10-row terminal renders 11 chrome lines
  ;; + 5 rows = 16 lines (both builds do — the frame scrolls; kept as parity)
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 10)]
          (t/is (= 16 (count (render-lines screen 80))))))
      {:user {:packages [dir]}})))

;; ─── Terminal resize (Termux keyboard) ────────────────────────────────────

(defn- many-extension-package
  "A package with N extension files — enough rows to keep the list clipped
   at the tested terminal heights."
  [n]
  (let [root (str (fs/path (tmp-dir) "pkg"))]
    (fs/create-dirs (str root "/extensions"))
    (doseq [i (range n)]
      (spit (str root "/extensions/e" (format "%02d" i) ".clj") "(ns x)\n"))
    root))

(t/deftest test-screen-resize-relayouts-to-the-new-height
  ;; the frame must follow a terminal resize: a height frozen at
  ;; construction keeps max-visible (and the frame's line count) from the
  ;; old terminal, so after Termux shrinks for the keyboard the frame is
  ;; taller than the screen and navigation paints over stale rows
  (let [dir (many-extension-package 15)]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 24)]
          (t/is (= 24 (count (render-lines screen 80))))
          (t/is (some #(re-find #"\(1/\d+\)" %) (render-lines screen 80)))
          (rc/screen-set-rows! screen 16)
          (let [lines (render-lines screen 80)]
            (t/is (= 16 (count lines)) "the frame re-lays out to the new height")
            (t/is (some #(str/includes? % "Global Resources") lines)
                  "the top chrome stays in the frame")
            (t/is (some #(re-find #"\(1/\d+\)" %) lines)
                  "the counter stays in the frame")))
        (t/testing "the selection stays visible after the shrink"
          (let [screen (rc/make-resource-config-screen :rows 24)]
            (rc/screen-set-rows! screen 16)
            (t/is (some #(str/starts-with? (u/strip-ansi-codes %) ">")
                        (render-lines screen 80))
                  "the cursor is inside the new 16-line frame"))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-tracks-the-terminal-size-ref
  ;; the standalone TUI publishes its live size and the screen follows the
  ;; ref: a height change re-lays the frame out and asks for a forced
  ;; repaint — a diff against the frame built for the old height would
  ;; leave stale rows and cursors on screen
  (let [dir (many-extension-package 15)]
    (with-settings
      (fn [_]
        (let [size (atom {:cols 80 :rows 24})
              repainted (promise)
              screen (rc/make-resource-config-screen
                      :rows 24
                      :size-ref size
                      :request-render! (fn [] (deliver repainted :repainted)))]
          (try
            (t/is (= 24 (count (render-lines screen 80))))
            (reset! size {:cols 80 :rows 16})
            (t/is (= :repainted (deref repainted 2000 ::timeout))
                  "the height change requested a repaint")
            (t/is (= 16 @(:rows-count screen)) "the layout picked up the new height")
            (t/is (= 16 (count (render-lines screen 80))))
            (t/testing "a width-only change keeps the height"
              (reset! size {:cols 100 :rows 16})
              (t/is (= 16 @(:rows-count screen))))
            (t/testing "dispose removes the size watch"
              (protocols/dispose screen)
              (reset! size {:cols 80 :rows 30})
              (t/is (= 16 @(:rows-count screen))))
            (finally
              (when-some [unwatch (:rows-unwatch screen)]
                (unwatch))))))
      {:user {:packages [dir]}})))

(t/deftest test-rows-watch-keys-are-unique-per-registration
  ;; finding-4 class: the rows watch key must not be derived from the screen
  ;; object — with System/identityHashCode two registrations share a key, so
  ;; the first cancel silently removes the other registration's watch
  (with-settings
    (fn [_]
      (let [screen (rc/make-resource-config-screen :rows 24)
            size (atom {:cols 80 :rows 24})
            cancel-1 (#'rc/watch-terminal-rows! screen size (fn []))
            cancel-2 (#'rc/watch-terminal-rows! screen size (fn []))]
        (try
          (t/is (= 2 (count (.getWatches size)))
                "each registration owns a distinct watch key")
          (cancel-1)
          (t/is (= 1 (count (.getWatches size)))
                "cancelling one registration leaves the other")
          (finally (cancel-2)))))
    {}))

(t/deftest test-theme-switch-restyles-the-frame
  ;; the frame body tracks the theme subscription: a live switch re-derives
  ;; the styled chrome (borders, title, hints), not just the rows
  (let [dir (many-extension-package 3)
        orig-name (th/get-current-theme-name)
        orig-theme (th/get-current-theme)]
    (try
      (with-settings
        (fn [_]
          (th/init-theme! "dark")
          (let [screen (rc/make-resource-config-screen :rows 30)
                dark (str/join "\n" (render-lines screen 80))]
            (t/is (str/includes? dark
                                 (th/get-fg-ansi (th/get-theme "dark") :accent))
                  "the dark theme's accent paints the borders")
            (th/init-theme! "light")
            (let [light (str/join "\n" (render-lines screen 80))]
              (t/is (not= dark light) "the frame followed the switch")
              (t/is (str/includes? light
                                   (th/get-fg-ansi (th/get-theme "light") :accent)))
              (t/is (not (str/includes? light
                                        (th/get-fg-ansi (th/get-theme "dark") :accent)))
                    "no construction-time accent survives"))))
        {:user {:packages [dir]}})
      (finally
        (th/set-theme-instance! orig-theme)
        (th/init-theme! orig-name)))))

(ns kmet.app.ui.test-resource-config
  "Resource-config screen tests (pi: config-selector.ts): row model,
   global/project scope toggles and their settings writes, tri-state
   cycling and search."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.packages :as pkgs]
            [kmet.app.ui.resource-config :as rc]
            [kmet.config :as cfg]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- make-package
  [root]
  (fs/create-dirs (str root "/extensions"))
  (fs/create-dirs (str root "/skills/root"))
  (spit (str root "/extensions/one.clj") "(ns pkg-one)\n")
  (spit (str root "/extensions/two.clj") "(ns pkg-two)\n")
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  root)

(defn- with-settings
  "Run F with settings paths isolated and the given :user/:project settings
   maps written to the files. The read/save redefs are file-based, so
   read-after-write round-trips behave like production. CWD is a single
   stable temp dir whose .kmet IS the project dir (production layout), so
   project auto dirs created by a test actually resolve."
  [f {:keys [user project]}]
  (let [global-dir (tmp-dir)
        cwd (tmp-dir)
        project-dir (str (fs/path cwd ".kmet"))
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
    (with-redefs [cfg/read-global-settings-map (fn [] (read-file (str (fs/path global-dir "settings.edn")) {}))
                  cfg/read-project-settings-map (fn [] (read-file (str (fs/path project-dir "settings.edn")) {}))
                  cfg/save-setting! (fn [path value]
                                      (spit (str (fs/path global-dir "settings.edn"))
                                            (pr-str (assoc-in (read-file (str (fs/path global-dir "settings.edn")) {}) path value))))
                  cfg/save-project-setting! (fn [path value]
                                              (fs/create-dirs project-dir)
                                              (spit (str (fs/path project-dir "settings.edn"))
                                                    (pr-str (assoc-in (read-file (str (fs/path project-dir "settings.edn")) {}) path value))))
                  cfg/get-agent-dir (fn [] global-dir)
                  cfg/project-dir (fn [] project-dir)
                  fs/cwd (fn [] cwd)]
      (f {:global-dir global-dir :project-dir project-dir :cwd cwd}))))

(defn- render-lines [screen width]
  (protocols/render screen width))

(defn- user-file-packages [ctx]
  (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
    (if (fs/exists? f)
      (:packages (edn/read-string (slurp f)))
      [])))

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
          (t/is (every? :enabled items))
          (t/testing "header renders"
            (let [lines (render-lines screen 80)]
              (t/is (some #(str/includes? % "Global Resources") lines))
              (t/is (some #(str/includes? % "settings.edn") lines))
              (t/is (some #(str/includes? % "space toggle") lines))))
          (t/testing "item lines render the checkbox"
            (let [lines (render-lines screen 80)]
              (t/is (= 3 (count (filter #(re-find #"\[x\]" %) lines))))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-global-toggle-disables
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)]
         ;; selection starts on the first item; one down → second item
          (protocols/handle-input screen K-DOWN)
          (protocols/handle-input screen " ")
          (let [entries (user-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (map? (first entries)))
            (t/is (= 1 (count (:extensions (first entries)))))
            (t/is (str/starts-with? (first (:extensions (first entries))) "-")))
          (t/testing "the screen re-reads the file — the item shows disabled"
            (t/is (false? (:enabled (second (item-rows screen))))))
         ;; toggle back on
          (protocols/handle-input screen " ")
          (let [entries (user-file-packages ctx)]
            (t/is (str/starts-with? (first (:extensions (first entries))) "+")))
          (t/is (every? :enabled (item-rows screen)))))
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
              (t/is (true? (:enabled (first (item-rows screen))))
                    "enter was rebound away from confirm")
              (protocols/handle-input screen "\u0019")        ;; ctrl+y
              (t/is (false? (:enabled (first (item-rows screen))))
                    "ctrl+y toggles the selected item")
              ;; space stays a raw toggle (pi), and ctrl+n rides
              ;; tui.select.down — rebound away with it
              (protocols/handle-input screen " ")
              (t/is (true? (:enabled (first (item-rows screen))))
                    "space stays a raw toggle (pi)")
              (let [before (pkgs/item-key (selected-item screen))]
                (protocols/handle-input screen "\u000e")      ;; ctrl+n
                (t/is (= before (pkgs/item-key (selected-item screen)))
                      "ctrl+n was rebound away with tui.select.down")))
            (finally
              (tui-kb/set-user-bindings! kmgr {})))))
      {:user {:packages [dir]}})))

;; ─── Project scope ────────────────────────────────────────────────────────

(t/deftest test-screen-project-inherited-tri-state
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
         ;; the user package shows as inherited global
          (t/is (= 3 (count (item-rows screen))))
          (t/is (every? :inherited? (item-rows screen)))
          (t/is (every? #(= :inherit (:override-state %)) (item-rows screen)))
          (t/testing "space cycles unload → writes a delta entry"
            (protocols/handle-input screen " ")
            (let [entries (project-file-packages ctx)]
              (t/is (= 1 (count entries)))
              (let [entry (first entries)]
                (t/is (map? entry))
                (t/is (false? (:autoload entry)))
                (t/is (str/starts-with? (first (:extensions entry)) "-"))))
            (t/is (= :unload (:override-state (first-item-row screen))))
            (t/testing "rows show the project unload suffix"
              (let [lines (render-lines screen 100)]
                (t/is (some #(str/includes? % "project unload") lines))))
           ;; cycle to load
            (protocols/handle-input screen " ")
            (let [entry (first (project-file-packages ctx))]
              (t/is (str/starts-with? (first (:extensions entry)) "+")))
            (t/is (= :load (:override-state (first-item-row screen))))
           ;; cycle back to inherit — the delta entry disappears
            (protocols/handle-input screen " ")
            (t/is (empty? (project-file-packages ctx)))
            (t/is (= :inherit (:override-state (first-item-row screen)))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-project-scope-package-tri-state
  (let [dir (make-package (tmp-dir))]
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
            (t/is (str/starts-with? (first (:extensions (first entries))) "-")))
          (t/is (= :unload (:override-state (first-item-row screen))))
         ;; second space: unload → load
          (protocols/handle-input screen " ")
          (let [entries (project-file-packages ctx)]
            (t/is (str/starts-with? (first (:extensions (first entries))) "+")))
          (t/is (= :load (:override-state (first-item-row screen))))
         ;; third space: load → inherit; the entry returns to its plain
         ;; source string
          (protocols/handle-input screen " ")
          (t/is (= :inherit (:override-state (first-item-row screen))))
          (let [entries (project-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (not (map? (first entries)))))))
      {:project {:packages [dir]}})))

(t/deftest test-screen-single-extension-not-toggleable
  ;; a file source (single extension) ignores per-type filters: the row is
  ;; marked always loaded and space writes nothing
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)
              item (:item (first-item-row screen))]
          (t/is (pkgs/single-extension-item? item))
          (t/is (some #(str/includes? % "always loaded") (render-lines screen 100)))
          (protocols/handle-input screen " ")
          (t/is (true? (:enabled (first-item-row screen))))
          (t/is (= [f] (user-file-packages ctx)))))
      {:user {:packages [f]}})))

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
  ;; unified resolution); a global-scope toggle writes the scope's settings
  ;; resource array (pi toggleTopLevelResource)
  (with-settings
    (fn [{:keys [global-dir]}]
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
        (t/testing "space writes a -pattern into the :extensions array"
          (protocols/handle-input screen " ")
          (let [settings (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))]
            (t/is (= ["-extensions/e.clj"] (:extensions settings)))))
        (t/is (false? (:enabled (first (item-rows screen)))))
        (t/testing "space again flips to +pattern"
          (protocols/handle-input screen " ")
          (let [settings (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))]
            (t/is (= ["+extensions/e.clj"] (:extensions settings)))))))
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
  ;; inherited user top-level item: project scope cycles inherit/unload/load
  ;; and writes the absolute path + +/- pattern into the project settings
  ;; array (pi setProjectTopLevelOverride)
  (with-settings
    (fn [{:keys [global-dir project-dir]}]
      (fs/create-dirs (str global-dir "/extensions"))
      (spit (str global-dir "/extensions/e.clj") "(ns e)\n")
      (let [screen (rc/make-resource-config-screen :rows 40
                                                   :write-scope :project
                                                   :project-mode? true)
            item-path (str global-dir "/extensions/e.clj")]
        (t/is (true? (:inherited? (first (item-rows screen)))))
        (t/is (= :inherit (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [item-path (str "-" item-path)] (:extensions settings))))
        (t/is (= :unload (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [item-path (str "+" item-path)] (:extensions settings))))
        (t/is (= :load (:override-state (first (item-rows screen)))))
        (protocols/handle-input screen " ")
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= [] (:extensions settings))))
        (t/is (= :inherit (:override-state (first (item-rows screen)))))))
    {}))

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
          (hiccup/reset-counters!)
          (render-lines screen 80)
          (t/is (zero? (:bodies-run (hiccup/counters)))
                "an idle frame runs no body")
          (t/is (zero? (:constructs (hiccup/counters)))
                "…and rebuilds no element")
          (hiccup/reset-counters!)
          (protocols/handle-input screen K-DOWN)
          (render-lines screen 80)
          (t/is (= 1 (:bodies-run (hiccup/counters)))
                "one selection change re-derives the body once")
          (hiccup/reset-counters!)
          (protocols/handle-input screen K-DOWN)
          (protocols/handle-input screen K-DOWN)
          (render-lines screen 80)
          (t/is (= 1 (:bodies-run (hiccup/counters)))
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
            (t/is (not-any? #(str/includes? % "(0/0)") lines)
                  "no counter when nothing is clipped"))
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
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen
                      :rows 24 :write-scope :project :project-mode? true)]
          (doseq [w [9 10 11 20 21 30 31 44 45 60 61 80 81]]
            (t/is (every? #(<= (u/visible-width %) w) (render-lines screen w))
                  (str "every line fits " w " columns")))))
      {:user {:packages [dir]}})))

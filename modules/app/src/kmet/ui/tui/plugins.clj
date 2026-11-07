(ns kmet.ui.tui.plugins
  "The `kmet plugins` composition screen: a static editor for plugin rows.

   It lists the built-in tool rows (`:tool/<name>`) and the bundled and
   installed extension rows (`:ext/<id>`), grouped by where they come from,
   and writes only the two ambient composition layers: the global
   `~/.kmet/agent/patch.edn` and the project `.kmet/patch.edn`
   (kmet.app.extensions.layers). It never loads extension code, never lists
   skills, prompts, themes or packages, and never writes settings or
   `:resources`.

   Space toggles the selected row: in global scope it composes or removes the
   row; in project scope it cycles inherit/load/unload as a delta over the
   global composition. Tab switches the write scope, escape closes, and the
   search input filters by name, type or path.

   The screen resolves the composition files when it opens and on a scope
   switch; a toggle updates the selected row in place, so what it shows
   matches the next process run. Nothing it writes is live:
   `kmet plugins` is its own process, so a toggle applies to the run the user
   starts next.

   The frame, search field, section rows, navigation and Tab scope switch
   come from the shared `kmet.ui.tui.grouped-selector` screen; this namespace
   owns the plugin items, their origin grouping, the checkbox lines and the
   composition writes."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.layers :as ext-layers]
            [kmet.app.extensions.rows :as ext-rows]
            [kmet.app.packages :as pkgs]
            [kmet.app.tools.registry :as tool-registry]
            [kmet.libs.path :as path]
            [kmet.libs.theme :as th]
            [kmet.tui.utils :as u]
            [kmet.ui.tui.grouped-selector :as grouped]))

;; ─── View model (pi: buildGroups / ResourceItem / filterItems) ────────────

(defn- display-name
  "The item's display name: a bundled item prefers its manifest name, and a
   file outside a conventional extensions/ directory names its parent too."
  [item]
  (or (get-in item [:metadata :display-name])
      (let [path (:path item)
            file (fs/file-name path)
            parent (fs/file-name (fs/parent path))]
        (if (and parent file (not= parent "extensions"))
          (str parent "/" file)
          (or file path)))))

(defn- group-label
  "The group heading for an item's origin: the built-in tool set, the
   bundled extensions, a package source with its scope, a settings-entry
   scope, or an auto-dir scope."
  [origin scope source base-dir agent-dir]
  (case origin
    :builtin "Built-in tools"
    :bundled "Bundled with kmet"
    :package (str source " (" (name scope) ")")
    (if (= source "auto")
      (str (if (= scope :user) "User" "Project")
           " (" (path/home-relative
                 (or base-dir (if (= scope :user) agent-dir ".kmet")))
           "/)")
      (str (if (= scope :user) "User" "Project") " settings"))))

(defn- build-groups
  "The plugin items grouped per origin/scope/source/base-dir. Built-in tools
   first, then bundled extensions, packages and top-level; user scope before
   project; items sorted by display name."
  [items agent-dir]
  (let [by-group (group-by (fn [item]
                             (let [origin (get-in item [:metadata :origin] :package)]
                               {:origin origin
                                :scope (get-in item [:metadata :scope])
                                :source (get-in item [:metadata :source])
                                ;; the built-in and bundled sets are one group
                                ;; each, whatever an artifact's base dir is
                                :base-dir (when-not (#{:builtin :bundled} origin)
                                            (get-in item [:metadata :base-dir]))}))
                           items)]
    (vec
     (for [[gk group-items] (sort-by (fn [[gk _]]
                                       [(case (:origin gk)
                                          :builtin 0 :bundled 1 :package 2 3)
                                        (if (= :user (:scope gk)) 0 1)
                                        (str (:source gk))
                                        (str (:base-dir gk))])
                                     by-group)]
       {:scope (:scope gk)
        :origin (:origin gk)
        :label (group-label (:origin gk) (:scope gk) (:source gk)
                            (:base-dir gk) agent-dir)
        :items (vec (sort-by display-name group-items))}))))

(defn- query-match?
  "pi filterItems — display name, resource type name or path contains the
   lower-cased QUERY."
  [item query]
  (or (str/blank? query)
      (str/includes? (str/lower-case (display-name item)) query)
      (str/includes? (name (:resource-type item)) query)
      (str/includes? (str/lower-case (:path item)) query)))

;; ─── Rendering helpers ────────────────────────────────────────────────────

(defn- group-line
  "Render a group heading."
  [t write-scope width group]
  (let [inherited? (and (= write-scope :project) (= :user (:scope group)))
        label (str "  " (th/bold (:label group))
                   (when inherited?
                     (str " · " (th/fg t :dim "inherited global"))))]
    (u/truncate-to-width
     (if inherited? (th/fg t :dim label) (th/fg t :accent label))
     width "")))

(defn- item-suffix
  "The annotations after an item name: bundled/unreadable markers and the
   project-scope override state."
  [t write-scope item]
  (str (when (pkgs/bundled-item? item)
         (th/fg t :dim "  bundled"))
       (when (:unreadable? item)
         (th/fg t :muted "  unreadable manifest"))
       (when (= write-scope :project)
         (case (:override-state item)
           :load (th/fg t :muted "  project load")
           :unload (th/fg t :muted "  project unload")
           (when (:inherited? item)
             (th/fg t :dim "  inherited global"))))))

(defn- item-line
  "Render one extension item row."
  [t write-scope width item selected?]
  (let [ov (:override-state item)
        inherited? (:inherited? item)
        dimmed? (and (= write-scope :project) inherited? (= ov :inherit))
        cursor (if selected? (th/fg t :accent "> ") "  ")
        name (display-name item)
        name (cond
               dimmed? (th/fg t :dim name)
               selected? (th/bold name)
               :else name)]
    (u/truncate-to-width
     (str cursor "    "
          (grouped/checkbox t write-scope ov (:enabled item))
          " " name (item-suffix t write-scope item))
     width "...")))

;; ─── Reading the composition ─────────────────────────────────────────────────────────

(defn- warn-layer-errors!
  "Report every unreadable composition layer on stderr, the way the run
   reports the same file at compose; the screen itself still opens."
  [layers]
  (doseq [layer (vals layers)
          :when (:error layer)]
    (binding [*out* *err*]
      (println "Warning: cannot read" (:path layer) "-"
               (get-in layer [:error :message])))))

(defn- builtin-tool-items
  "The built-in tool rows (`:tool/<name>`) as screen items. They come from
   the base composition, so `:path` is empty and `:metadata` carries the row
   id and plugin source the layer writers need."
  []
  (mapv (fn [{:keys [id plugin]}]
          {:path ""
           :resource-type :tools
           :metadata {:origin :builtin
                      :scope :user
                      :source "builtin"
                      :display-name (tool-registry/tool-row-name id)
                      :id id
                      :plugin plugin}})
        (tool-registry/builtin-tool-rows)))

(defn- read-items
  "Resolve the built-in tool and extension items for WRITE-SCOPE, each
   carrying its composition state. The global view is the user scope only;
   the project view merges the project scope over it. Nothing here loads an
   extension."
  [write-scope]
  (let [user-settings (cfg/read-global-settings-map)
        project? (= :project write-scope)
        project-settings (when project? (cfg/read-project-settings-map))
        layers (ext-layers/layers)
        composed (ext-layers/composition layers)
        _ (warn-layer-errors! layers)
        items (pkgs/resolve-package-items user-settings project-settings nil
                                          project? (pkgs/bundled-items))]
    (mapv (fn [item]
            (let [builtin? (= :builtin (get-in item [:metadata :origin]))
                  source (when-not builtin? (ext-rows/item-source item))
                  state (cond
                          builtin?
                          (ext-layers/builtin-item-state
                           layers (get-in item [:metadata :id]) write-scope)

                          source
                          (ext-layers/item-state composed source write-scope))]
              (if state
                (cond-> (assoc item
                               :enabled (:enabled state)
                               :inherited-enabled (:inherited-enabled state)
                               :inherited? (= :user (get-in item [:metadata :scope]))
                               :override-state (if project?
                                                 (:override-state state)
                                                 :inherit))
                  source (assoc :source source))
                (assoc item :enabled false :inherited-enabled false
                       :inherited? (= :user (get-in item [:metadata :scope]))
                       :override-state :inherit :unreadable? true))))
          (concat (builtin-tool-items) (:extensions items)))))

(defn- item-identity
  "The identity a row's cached item carries across a search re-filter: an
   extension's source or a built-in row's id."
  [item]
  (or (:source item) (get-in item [:metadata :id])))

(defn- apply-write!
  "Run WRITE (a scope's writer, answering true when it changed the file),
   reporting a failure on SCOPE's composition and answering false. A failed
   write leaves the row as it was."
  [scope write]
  (try (write)
       (catch Exception e
         (binding [*out* *err*]
           (println "Warning: cannot write the" (name scope) "composition:"
                    (or (ex-message e) (str e))))
         false)))

(defn- write-row!
  "Run WRITE for SCOPE and update ITEM in place when the writer changed the
   file."
  [screen item scope write changes]
  (when (apply-write! scope write)
    (grouped/screen-update-item! screen item changes)))

(defn- project-toggle-changes
  "The state changes a project toggle records: inherit cycles to unload for a
   globally composed row and to load for one the global layer removed; a
   project override returns to inherit."
  [item]
  (if (= :inherit (:override-state item))
    (if (:inherited-enabled item)
      {:override-state :unload :enabled false}
      {:override-state :load :enabled true})
    {:override-state :inherit :enabled (:inherited-enabled item)}))

(defn- toggle-item!
  "Toggle a plugin item in WRITE-SCOPE: the global scope composes or drops
   its row; the project scope cycles inherit/load/unload over the global
   composition. A built-in tool is restored by dropping its remove patch; a
   project load of one re-inserts its base row. No settings key changes."
  [screen item write-scope]
  (let [builtin? (= :builtin (get-in item [:metadata :origin]))
        id (get-in item [:metadata :id])
        source (:source item)
        enabled? (:enabled item)]
    (when (or builtin? source)
      (if (= :global write-scope)
        (write-row! screen item :global
                    (cond
                      builtin? #(ext-layers/apply-global-builtin-state!
                                 id (not enabled?))
                      enabled? #(ext-layers/disable-global! source)
                      :else #(ext-layers/enable-global! source))
                    {:enabled (not enabled?) :override-state :inherit})
        (let [changes (project-toggle-changes item)
              next-state (:override-state changes)]
          (write-row! screen item :project
                      (if builtin?
                        #(ext-layers/apply-project-builtin-state!
                          id (get-in item [:metadata :plugin]) next-state)
                        #(ext-layers/apply-project-state! source next-state))
                      changes))))))

;; ─── Construction & test helpers ──────────────────────────────────────────

(defn make-plugins-screen
  "Build the plugin-composition screen. OPTS:
   :write-scope     — :global (screen fallback) or :project; `kmet plugins`
                      passes :project when a `.kmet/` project exists (pi:
                      config -l starts in project-local mode)
   :project-mode?   — whether Tab may switch scopes (pi:
                      projectModeAvailable — kmet: the .kmet project dir
                      exists or -l was passed)
   :rows            — initial terminal height in rows
   :size-ref        — the TUI's reactive terminal-size ref
                      (kmet.tui.core/tui-terminal-size-ref); with
                      :request-render! the screen follows its rows and
                      forces a repaint on every height change
   :request-render! — 0-arg forced-repaint request (see :size-ref)
   :on-close        — called when the user closes the screen (escape or
                      ctrl+c)"
  [& {:keys [write-scope project-mode? rows size-ref request-render! on-close]}]
  (let [project-mode? (boolean project-mode?)]
    (grouped/make-screen
     {:write-scope (or write-scope :global)
      :can-switch-scope? project-mode?
      :rows rows
      :size-ref size-ref
      :request-render! request-render!
      :on-close on-close
      :read read-items
      :groups #(build-groups % (cfg/get-agent-dir))
      :filter-match query-match?
      :item-identity item-identity
      :render-group (fn [{:keys [group scope theme width]}]
                      (group-line theme scope width group))
      :render-item (fn [{:keys [item scope theme width selected?]}]
                     (item-line theme scope width item selected?))
      :on-activate (fn [{:keys [screen item scope]}]
                     (toggle-item! screen item scope))
      :title (fn [scope]
               (if (= scope :project) "Project Plugins" "Global Plugins"))
      :action-hint (fn [scope]
                     (if (= scope :project)
                       "space cycle inherit/load/unload"
                       "space compose/remove"))
      :scope-line (fn [scope]
                    (str (if (= scope :global)
                           (cfg/global-patch-path)
                           ".kmet/patch.edn")
                         (when (and (= scope :project) project-mode?)
                           " · inherited plugin rows are dimmed")))})))

(defn screen-write-scope
  "The screen's current write scope (tests)."
  [screen]
  (grouped/screen-write-scope screen))

(defn screen-rows
  "The screen's current flat rows (tests)."
  [screen]
  (grouped/screen-rows screen))

(defn screen-selected
  "The screen's selected row index (tests)."
  [screen]
  (grouped/screen-selected screen))

(defn screen-set-query!
  "Set the search query and re-filter (tests; typing drives the same path
   through the tag-owned field)."
  [screen query]
  (grouped/screen-set-query! screen query))

(ns kmet.ui.tui.plugins
  "The `kmet plugins` composition screen: a static editor for extension
   plugin rows.

   It lists bundled and installed extension rows (`:ext/<id>`), grouped by
   where they come from, and writes only the two ambient composition layers:
   the global `~/.kmet/agent/patch.edn` and the project `.kmet/patch.edn`
   (kmet.app.extensions.layers). It never loads extension code, never lists
   tools, skills, prompts, themes or packages, and never writes settings or
   `:resources`.

   Space toggles the selected row: in global scope it composes or removes the
   extension's row; in project scope it cycles inherit/load/unload as a delta
   over the global composition. Tab switches the write scope, escape closes,
   and the search input filters by name, type or path.

   The screen resolves the composition files when it opens and on a scope
   switch; a toggle updates the selected row in place, so what it shows
   matches the next process run. Nothing it writes is live:
   `kmet plugins` is its own process, so a toggle applies to the run the user
   starts next.

   The frame is a mounted hiccup root (tui.md §14.1): the two borders are
   [:dynamic-border] elements, the header/hint/row lines are
   [:truncated-text] leaves over already-styled strings, and the search field
   is a tag-owned [:input] whose text lives in state and whose emphasis and
   caret follow the screen's focus flag."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.layers :as ext-layers]
            [kmet.app.extensions.rows :as ext-rows]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as pkgs]
            [kmet.hikap.core :as engine]
            [kmet.libs.path :as path]
            [kmet.libs.theme :as th]
            [kmet.reakt.core :as r]
            [kmet.tui.components.input :as input]
            [kmet.tui.hikap :as hikap]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.ui.tui.list-screen :as ls]
            [kmet.ui.tui.subs :as s]))

(def ^:private chrome-lines
  "Fixed lines around the list rows (pi ConfigSelectorComponent chrome):
   top spacer, top border, spacer, two header lines, spacer, search input
   line, blank line, bottom spacer, bottom border."
  11)

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
  "The group heading for an item's origin: the bundled set, a package source
   with its scope, a settings-entry scope, or an auto-dir scope."
  [origin scope source base-dir agent-dir]
  (case origin
    :bundled "Bundled with kmet"
    :package (str source " (" (name scope) ")")
    (if (= source "auto")
      (str (if (= scope :user) "User" "Project")
           " (" (path/home-relative
                 (or base-dir (if (= scope :user) agent-dir ".kmet")))
           "/)")
      (str (if (= scope :user) "User" "Project") " settings"))))

(defn- build-groups
  "The extension items grouped per origin/scope/source/base-dir. Packages
   first, then top-level; user scope before project; items sorted by display
   name."
  [items agent-dir]
  (let [by-group (group-by (fn [item]
                             (let [origin (get-in item [:metadata :origin] :package)]
                               {:origin origin
                                :scope (get-in item [:metadata :scope])
                                :source (get-in item [:metadata :source])
                                ;; the bundled set is ONE group whatever each
                                ;; artifact's base dir is
                                :base-dir (when-not (= :bundled origin)
                                            (get-in item [:metadata :base-dir]))}))
                           items)]
    (vec
     (for [[gk group-items] (sort-by (fn [[gk _]]
                                       [(case (:origin gk) :bundled 0 :package 1 2)
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

(defn- build-flat
  "The filtered flat row list of GROUPS (pi buildFlatList + filterItems):
   group rows and item rows, keeping only groups with matching items."
  [groups query]
  (let [query (str/lower-case (str/trim (or query "")))]
    (vec (mapcat (fn [g]
                   (let [items (filterv #(query-match? % query) (:items g))
                         group (assoc g :items items)]
                     (when (seq items)
                       (cons {:kind :group :group group}
                             (map (fn [item] {:kind :item :group group :item item})
                                  items)))))
                 groups))))

;; ─── Rendering helpers ────────────────────────────────────────────────────

(defn- hint
  "A key hint: the current binding text for BINDING-ID (or FALLBACK) plus
   the action description (pi keyHint/rawKeyHint)."
  [binding-id fallback desc]
  (let [key-text (app-kb/key-text binding-id)]
    (str (if (seq key-text) key-text fallback) " " desc)))

(defn- render-checkbox
  "pi renderCheckbox."
  [t write-scope state enabled]
  (if (= write-scope :project)
    (case state
      :load (th/fg t :success "[+]")
      :unload (th/fg t :warning "[-]")
      (th/fg t :dim (if enabled "[x]" "[ ]")))
    (if enabled (th/fg t :success "[x]") (th/fg t :dim "[ ]"))))

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
        name (cond-> (display-name item)
               (and selected? (not dimmed?)) th/bold
               dimmed? (th/fg t :dim))]
    (u/truncate-to-width
     (str cursor "    "
          (render-checkbox t write-scope ov (:enabled item))
          " " name (item-suffix t write-scope item))
     width "...")))

(defn- row-line
  "Render one visible row: a group heading or an extension item."
  [t state width row selected?]
  (let [write-scope (:write-scope state)]
    (case (:kind row)
      :group (group-line t write-scope width (:group row))
      :item (item-line t write-scope width (:item row) selected?))))

;; ─── Screen state ─────────────────────────────────────────────────────────

(defn- warn-layer-errors!
  "Report every unreadable composition layer on stderr, the way the run
   reports the same file at compose; the screen itself still opens."
  [layers]
  (doseq [layer (vals layers)
          :when (:error layer)]
    (binding [*out* *err*]
      (println "Warning: cannot read" (:path layer) "-"
               (get-in layer [:error :message])))))

(defn- read-items
  "Resolve the extension items for WRITE-SCOPE, each carrying its composition
   state. The global view is the user scope only; the project view merges the
   project scope over it. Nothing here loads an extension."
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
            (if-let [source (ext-rows/item-source item)]
              (let [state (ext-layers/item-state composed source write-scope)]
                (assoc item
                       :source source
                       :enabled (:enabled state)
                       :inherited-enabled (:inherited-enabled state)
                       :inherited? (= :user (get-in item [:metadata :scope]))
                       :override-state (if project?
                                         (:override-state state)
                                         :inherit)))
              (assoc item :enabled false :inherited-enabled false
                     :inherited? (= :user (get-in item [:metadata :scope]))
                     :override-state :inherit :unreadable? true)))
          (:extensions items))))

(defn- rebuild-layout!
  "(Re)build the screen's flat rows from ITEMS (a fresh resolution when nil)
   and the current query. Construction and scope switches resolve; search
   reuses the items already read; a toggle updates one row in place."
  ([this] (rebuild-layout! this nil))
  ([this items]
   (let [st @(:state-atom this)
         items (or items (read-items (:write-scope st)))
         rows (build-flat (build-groups items (cfg/get-agent-dir)) (:query st))
         first-item (first (keep-indexed (fn [i r] (when (= :item (:kind r)) i))
                                         rows))
         selected (or first-item
                      (min (or (:selected st) 0) (max 0 (dec (count rows)))))]
     (swap! (:state-atom this) assoc
            :items items :rows rows :selected selected))))

(defn- refilter!
  "Re-filter the rows from the items the screen already has (a search change
   does not change the files)."
  [this]
  (rebuild-layout! this (:items @(:state-atom this))))

(defn- update-row-state!
  "Refresh the selected item after a toggle without re-resolving the layout.
   The item cache is updated too, so a later search cannot show the old
   checkbox."
  [this changes]
  (swap! (:state-atom this)
         (fn [{:keys [rows items selected] :as st}]
           (if (= :item (:kind (get rows selected)))
             (let [item-path (:path (:item (get rows selected)))]
               (assoc st
                      :rows (update rows selected update :item merge changes)
                      :items (mapv #(if (= item-path (:path %))
                                      (merge % changes)
                                      %)
                                   items)))
             st))))

(defn- item-indices
  "The indices of selectable item rows."
  [rows]
  (keep-indexed (fn [i row] (when (= :item (:kind row)) i)) rows))

(defn- move-selection!
  [this direction]
  (swap! (:state-atom this)
         (fn [{:keys [rows selected] :as st}]
           (let [items (vec (item-indices rows))]
             (if (seq items)
               (let [pos (count (take-while #(< % selected) items))
                     pos (max 0 (min (dec (count items)) (+ pos direction)))]
                 (assoc st :selected (nth items pos)))
               st)))))

(defn- page-target
  "The nearest item row at or beyond TARGET in DIRECTION, or nil when the
   scan leaves the rows."
  [rows target direction]
  (let [items (item-indices rows)
        candidates (if (pos? direction)
                     (filter #(>= % target) items)
                     (filter #(<= % target) items))]
    (if (pos? direction) (first candidates) (last candidates))))

(defn- page-selection!
  "pi pageUp/pageDown scan — jump by maxVisible rows, then to the nearest
   item row in DIRECTION (+1 forward, -1 backward)."
  [this direction]
  (swap! (:state-atom this)
         (fn [st]
           (let [rows (:rows st)
                 max-visible (max 5 (- @(:rows-count this) chrome-lines))
                 sel (:selected st)
                 n (count rows)
                 up? (= -1 direction)
                 target (if up?
                          (max 0 (- sel max-visible))
                          (min (max 0 (dec n)) (+ sel max-visible)))
                 found (page-target rows target direction)]
             (if found
               (assoc st :selected found)
               st)))))

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
  "Run WRITE for SCOPE and update the selected row in place when the writer
   changed the file."
  [this scope write changes]
  (when (apply-write! scope write)
    (update-row-state! this changes)))

(defn- toggle-extension!
  "Toggle an extension item in WRITE-SCOPE: the global scope composes or
   drops its row; the project scope cycles inherit/load/unload over the
   global composition. No settings key changes."
  [this item write-scope]
  (when-let [source (:source item)]
    (if (= :global write-scope)
      (let [enabled? (:enabled item)]
        (write-row! this :global
                    (if enabled?
                      #(ext-layers/disable-global! source)
                      #(ext-layers/enable-global! source))
                    {:enabled (not enabled?) :override-state :inherit}))
      (let [inherited? (:inherited-enabled item)
            next-state (if (= :inherit (:override-state item))
                         (if inherited? :unload :load)
                         :inherit)]
        (write-row! this :project
                    #(ext-layers/apply-project-state! source next-state)
                    {:override-state next-state
                     :enabled (case next-state
                                :load true
                                :unload false
                                inherited?)})))))

(defn- toggle-selected!
  "Space/enter on the selected item row; every row is an extension plugin."
  [this]
  (let [{:keys [rows selected write-scope]} @(:state-atom this)
        row (get rows selected)]
    (when (= :item (:kind row))
      (toggle-extension! this (:item row) write-scope))))

(defn- refresh-filter!
  "Apply the search field's text to the row list (pi: searchInput change →
   filterItems). The field is tag-owned, so the panel mirrors its live text
   into :query as it forwards — the tree feeds that value straight back,
   which is why an unchanged value never disturbs the caret."
  [this]
  (swap! (:state-atom this) assoc
         :query (input/input-get-value
                 (hikap/materialize-ref! this (:search-ref this))))
  (refilter! this))

(defn- switch-scope!
  "Tab: switch the write scope and rebuild the layout from the composition
   of the new scope (pi: setWriteScope)."
  [this]
  (swap! (:state-atom this) update :write-scope
         (fn [s] (if (= s :global) :project :global)))
  (rebuild-layout! this))

(defn- close!
  [this]
  (when-let [f @(:on-close-atom this)] (f)))

;; ─── The screen frame (a mounted hiccup root) ─────────────────────────────

(defn- line-leaf
  "One already-styled line as an element. [:truncated-text], not [:text]:
   the imperative renderer returned exact lines, and [:text] would re-wrap
   any line that is wider than the frame."
  [line]
  [:truncated-text {:text line :padding-x 0 :padding-y 0}])

(defn- header-line
  "The title and key hints, padded to the frame width."
  [t w write-scope project-mode?]
  (let [title (th/bold (if (= write-scope :project)
                         "Project Plugins"
                         "Global Plugins"))
        sep (th/fg t :muted " · ")
        switch-hint (when project-mode?
                      (hint "tui.input.tab" "tab" "switch mode"))
        action (if (= write-scope :project)
                 "space cycle inherit/load/unload"
                 "space compose/remove")
        hints (str (when switch-hint (str switch-hint sep))
                   action sep "esc close")]
    (u/truncate-to-width
     (str title (apply str (repeat (max 1 (- w (u/visible-width title)
                                             (u/visible-width hints)))
                                   " "))
          hints)
     w "")))

(defn- scope-line
  "The layer path the current scope writes, and its inheritance note."
  [t w write-scope project-mode?]
  (u/truncate-to-width
   (th/fg t :muted
          (str (if (= write-scope :global)
                 (cfg/global-patch-path)
                 ".kmet/patch.edn")
               (when (and (= write-scope :project) project-mode?)
                 " · inherited plugin rows are dimmed")))
   w ""))

(defn- frame-tree
  "The screen's element tree: spacers, accent borders, the title/hint/scope
   lines, the width-truncated row lines and the clipped counter, with the
   search field as a tag-owned [:input]. The height is tracked: a terminal
   resize re-lays out the frame (max-visible follows the live rows)."
  [screen]
  (let [w hikap/*width*
        ;; tracked: a theme switch re-derives the frame's styled chrome
        t (r/tracked-deref s/theme-sub)
        st (r/tracked-deref (:state-atom screen))
        rows-count (r/tracked-deref (:rows-count screen))
        write-scope (:write-scope st)
        project-mode? (:project-mode? screen)
        rows (:rows st)
        n (count rows)
        item-total (count (filterv #(= :item (:kind %)) rows))
        max-visible (max 5 (- rows-count chrome-lines))
        sel (min (or (:selected st) 0) (max 0 (dec n)))
        [start-idx end-idx] (ls/window sel max-visible n)
        visible (subvec rows start-idx end-idx)
        border-fn #(th/fg t :accent %)
        title-line (header-line t w write-scope project-mode?)
        scope-hint (scope-line t w write-scope project-mode?)
        row-lines (map-indexed (fn [rel row]
                                 (row-line t st w row
                                           (and (= :item (:kind row))
                                                (= (+ start-idx rel) sel))))
                               visible)
        clipped? (or (pos? start-idx) (< end-idx n))
        scroll-line (when clipped?
                      (let [cur (count (filterv #(= :item (:kind %))
                                                (subvec rows 0 (inc sel))))]
                        (u/truncate-to-width
                         (th/fg t :dim (str "  (" cur "/" item-total ")")) w "")))]
    [:container {}
     [:spacer {:lines 1}]
     [:dynamic-border {:color-fn border-fn}]
     [:spacer {:lines 1}]
     (line-leaf title-line)
     (line-leaf scope-hint)
     [:spacer {:lines 1}]
     [:input {:ref (:search-ref screen)
              :value (:query st)
              :focused? (r/tracked-deref (:focused? screen))}]
     [:spacer {:lines 1}]
     (map line-leaf row-lines)
     (when scroll-line (line-leaf scroll-line))
     [:spacer {:lines 1}]
     [:dynamic-border {:color-fn border-fn}]]))

(defcomponent PluginsScreen nil
              [state-atom search-ref on-close-atom rows-count project-mode?
               focused? rows-unwatch root]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)]
      (cond
        ;; Navigation (pi tui.select.up/down; ctrl+p/ctrl+n ride the ids)
        (kb/matches-key kmgr data "tui.select.up")
        (do (move-selection! this -1) nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (move-selection! this 1) nil)

        (kb/matches-key kmgr data "tui.select.pageUp")
        (do (page-selection! this -1) nil)

        (kb/matches-key kmgr data "tui.select.pageDown")
        (do (page-selection! this 1) nil)

        (kb/matches-key kmgr data "tui.select.cancel")
        (do (close! this) nil)

        ;; pi config-selector still matches ctrl+c raw (onExit), and space
        ;; raw; everything else goes through the manager
        (keys/matches-key? data "ctrl+c")
        (do (close! this) nil)

        (kb/matches-key kmgr data "tui.input.tab")
        (do (when (:project-mode? this) (switch-scope! this)) nil)

        (or (= data " ") (kb/matches-key kmgr data "tui.select.confirm"))
        (do (toggle-selected! this) nil)

        ;; Everything else — the search field, then the unconditional
        ;; re-filter (pi: searchInput.handleInput + filterItems); the
        ;; materialize helper compiles the tree when the host has not
        ;; painted yet, so a key is never dropped
        :else
        (do (protocols/handle-input
             (hikap/materialize-ref! this (:search-ref this)) data)
            (refresh-filter! this)
            nil))))

  ;; the root's reaction and the tag-owned search field are the screen's
  ;; lifecycle — unwinding the tree disposes the field with it; the
  ;; terminal-size watch (when one is attached) must not outlive the screen
  (dispose [this]
    (when-some [unwatch (:rows-unwatch this)]
      (unwatch))
    (protocols/dispose (:root this))))

;; ─── IFocusable — the panel's flag; the tree derives the field emphasis ────

(extend-type PluginsScreen
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

;; ─── Construction & test helpers ──────────────────────────────────────────

(defn- watch-terminal-rows!
  "Keep SCREEN's layout height in step with the TUI's terminal-size ref
   and force a repaint through REQUEST-RENDER! whenever the height changes.
   Returns a cancel fn for the screen's dispose."
  [screen size-ref request-render!]
  (let [k (keyword (gensym "plugins-rows-"))
        last-rows (atom @(:rows-count screen))]
    (add-watch size-ref k
               (fn [_ _ _ size]
                 (let [rows (:rows size)]
                   (when (not= rows @last-rows)
                     (reset! last-rows rows)
                     (reset! (:rows-count screen) rows)
                     (request-render!)))))
    (fn [] (remove-watch size-ref k))))

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
  (let [state-atom (atom {:write-scope (or write-scope :global)
                          :query ""
                          :selected 0
                          :rows []})
        screen (map->PluginsScreen
                {:state-atom state-atom
                 :search-ref (engine/ref)
                 :on-close-atom (atom on-close)
                 :rows-count (atom (or rows 24))
                 :project-mode? (boolean project-mode?)
                 :focused? (atom false)})
        root (hikap/root (fn [_props] (frame-tree screen)))
        screen (assoc screen :root root)]
    (rebuild-layout! screen)
    (cond-> screen
      (and size-ref request-render!)
      (assoc :rows-unwatch (watch-terminal-rows! screen size-ref request-render!)))))

(defn screen-write-scope
  "The screen's current write scope (tests)."
  [screen]
  (:write-scope @(:state-atom screen)))

(defn screen-rows
  "The screen's current flat rows (tests)."
  [screen]
  (:rows @(:state-atom screen)))

(defn screen-selected
  "The screen's selected row index (tests)."
  [screen]
  (:selected @(:state-atom screen)))

(defn screen-set-query!
  "Set the search query and re-filter (tests; typing drives the same path
   through the tag-owned field)."
  [screen query]
  (swap! (:state-atom screen) assoc :query (str query))
  (refilter! screen))

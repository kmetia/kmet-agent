(ns kmet.app.tools.config
  "Tool rows for the `kmet config` screen — the settings-backed sibling of
   the bundled/resources layers (pi has no built-in-tools group; pi's
   defaultTools setting is edited by hand).

   A built-in tool is a composition row (`:tool/read`, `:tool/bash`, …), so
   the group has two inputs: the `:default-tools` setting (the runtime
   selection, which is what a built-in toggle writes) and the ambient
   composition layers (`mounted-tool-names` — a layer can remove a tool row,
   and a screen that offered a removed tool would be describing a run nobody
   gets).

   The same group lists the tools no built-in registry answers to: a name
   either scope's `:enabled-tools` names, and every name an installed
   extension declares under its opaque manifest `:meta {:tools [{:name …}]}`.
   Those rows are a static projection (no plugin loads) and their toggle
   writes or clears the `:enabled-tools` delta; `/tools` stays the live
   authority. All rows are PackageItem-shaped for the screen's view model:
   :resource-type :tools, a `:setting` in metadata (`:default-tools` or
   `:enabled-tools`), display-name the tool name and row-id the row it is.

   Built-in state resolves the :default-tools entry list the same way startup
   does (kmet.app.config/merge-tool-entries +
   kmet.app.tools.registry/resolve-default-tools), and is false for a tool the
   layers do not mount. An unset setting resolves to `default-tool-names`, so
   a row marked `:default-enabled? false` (run_code, eval) shows off until
   selected. Extension rows resolve :enabled-tools over the names the static
   projection knows; no setting means every such tool is on (deltas, not
   snapshots).

   A default-tool row (a built-in) writes the :default-tools setting; an
   enabled-tool row (a name no built-in registry answers to) writes
   :enabled-tools. The global scope keeps a modifier-only list's shape —
   disabling writes -name so future tools inherit, enabling removes the entry
   (for an opt-in tool the inverse) — and rewrites a list with plain names as
   the effective selection (plain names replace the whole set). The project
   scope writes the +name/-name delta over the user list the other resources
   use (:load/:unload/:inherit). Both settings share the same writers."
  (:require [babashka.fs :as fs]
            [kmet.app.tools.registry :as registry]
            [kmet.app.config :as cfg]))

(defn- parse-entry
  "ENTRY parsed as {:name string :modifier :add|:remove|nil}; nil for a
   non-string or blank entry. The one place a +name/-name prefix is read."
  [entry]
  (when (and (string? entry) (seq entry))
    (let [prefix (subs entry 0 1)
          modifier (case prefix "+" :add "-" :remove nil)]
      {:name (if modifier (subs entry 1) entry)
       :modifier modifier})))

(defn- entry-target
  "The tool name ENTRY targets, modifier prefix stripped; nil when ENTRY is
   not a non-blank string."
  [entry]
  (:name (parse-entry entry)))

(defn- entry-vector [entries]
  (if (sequential? entries) (vec entries) []))

(defn- entry-targets
  "The names ENTRIES target, modifier prefixes stripped."
  [entries]
  (into #{} (keep entry-target) (entry-vector entries)))

(defn- modifier-style?
  "Whether ENTRIES keeps the modifier-only selection shape: nil (no setting —
   every built-in enabled / the live set unmodified) or a non-empty list of
   +name/-name entries. An empty list is plain style — it selects nothing."
  [entries]
  (or (nil? entries)
      (and (seq entries) (every? registry/tool-selection-modifier? entries))))

(defn- tool-setting
  "The settings key tool ITEM's toggle writes: :default-tools for a
   default-tool row (a built-in), :enabled-tools for an enabled-tool row."
  [item]
  (get-in item [:metadata :setting]))

(defn tool-item?
  "True when ITEM is a tool row: a default-tool row (a built-in, writing
   :default-tools) or an enabled-tool row (a name no built-in registry
   answers to, writing :enabled-tools)."
  [item]
  (and (= :tools (:resource-type item))
       (contains? #{:default-tools :enabled-tools} (tool-setting item))))

(defn tool-name
  "The tool name ITEM's settings entries target (its display name)."
  [item]
  (get-in item [:metadata :display-name]))

(defn mounted-tool-names
  "The tool names LAYERS leave mounted, over the built-in NAMES.

   A built-in tool is a row now (`:tool/read`, see
   `kmet.app.tools.registry/mount-tool!`), so a composition layer can take one
   out (`{:id :tool/bash :remove true}`) — and a screen that listed every
   built-in would offer a tool the run does not mount. LAYERS are the ambient
   composition layers in application order (`{:data {:rows … :patch …}}`), and
   the fold is compose's for the tool rows only: a `:remove` patch drops a name,
   a row or patch with `:plugin` puts it back. Nothing here loads a plugin — a
   row's name comes from its `:id`, which is why a tool row's id is the model's
   own spelling of the tool.

   A layer that inserts a `:tool/…` row with no explicit `:id` is not read: the
   name would come from the plugin var, and the screen does not load sources to
   list them (that is `--dump-config`'s job, and it resolves the composition)."
  ([layers] (mounted-tool-names registry/builtin-tool-names layers))
  ([names layers]
   (reduce (fn [mounted entry]
             (let [tool (registry/tool-row-name (:id entry))]
               (cond
                 (nil? tool) mounted
                 (:remove entry) (filterv #(not= tool %) mounted)
                 (:plugin entry) (cond-> mounted (not (some #{tool} mounted)) (conj tool))
                 :else mounted)))
           (vec names)
           (mapcat (fn [{:keys [data]}] (concat (:rows data) (:patch data))) layers))))

(defn- meta-tool-rows
  "The static `:meta :tools` rows EXTENSION-TOOLS declare, keyed by tool name.
   Each entry is `{:meta opaque-manifest-meta :metadata group-metadata}`. Only
   a sequential collection of maps with a non-empty string `:name` produces a
   row; invalid entries are dropped, and absent/malformed/empty means no rows.
   No warning: :meta is opaque and `/tools` stays the live authority. The
   first installed extension wins a duplicate name (pi precedence)."
  [extension-tools]
  (reduce (fn [rows {:keys [meta metadata]}]
            (let [tools (:tools meta)]
              (if (sequential? tools)
                (reduce (fn [rows tool]
                          (let [name (:name tool)]
                            (if (and (map? tool) (string? name) (seq name)
                                     (not (contains? rows name)))
                              (assoc rows name metadata)
                              rows)))
                        rows tools)
                rows)))
          {}
          extension-tools))

(defn tool-items
  "PackageItem-shaped rows for the config screen's tools group.

   A built-in tool gets one row, in registry order, resolved against the
   merged :default-tools entries (an unset setting resolves to
   `default-tool-names`, so an opt-in row shows off); its toggle writes
   :default-tools. A non-built-in name either scope's
   :enabled-tools names, and every valid name an installed extension declares
   under its opaque manifest `:meta {:tools [{:name …} …]}`, gets a row whose
   toggle writes/clears the :enabled-tools delta. Those rows are a static
   projection — no plugin loads, so a dynamically registered tool stays
   invisible unless a settings scope names it, and `/tools` remains the live
   authority.

   USER-SETTINGS and PROJECT-SETTINGS are the raw scope maps (nil
   PROJECT-SETTINGS is the global view: project entries are absent). MOUNTED
   is the tool names the composition layers leave mounted
   (`mounted-tool-names`), nil for every built-in: a built-in whose row the
   layers removed is listed but not enabled. EXTENSION-TOOLS are
   `{:meta manifest-meta :metadata group-metadata}` per installed extension
   (`ext-rows/item-meta`); their declared tools carry that metadata so they
   group with the extension that provides them. A name no manifest declares
   is grouped as a settings projection."
  ([user-settings project-settings]
   (tool-items user-settings project-settings nil nil))
  ([user-settings project-settings mounted extension-tools]
   (let [builtin-names (vec registry/builtin-tool-names)
         builtin-set (set builtin-names)
         entries (cfg/merge-tool-entries (:default-tools user-settings)
                                         (:default-tools project-settings))
         selection (if entries
                     (set (registry/resolve-default-tools entries))
                     (set registry/default-tool-names))
         mounted (when mounted (set mounted))
         meta-rows (meta-tool-rows extension-tools)
         enabled-entries (cfg/merge-tool-entries (:enabled-tools user-settings)
                                                 (:enabled-tools project-settings))
         named (entry-targets enabled-entries)
         extra-names (vec (sort (into (set (remove builtin-set named))
                                      (remove builtin-set (keys meta-rows)))))
         enabled (when enabled-entries
                   (set (registry/resolve-enabled-tools enabled-entries extra-names)))
         user-named (entry-targets (:enabled-tools user-settings))
         project-named (entry-targets (:enabled-tools project-settings))
         builtin-items
         (mapv (fn [name]
                 {:path (str "tools/" name)
                  :resource-type :tools
                  :enabled (and (contains? selection name)
                                (if mounted (contains? mounted name) true))
                  :metadata {:origin :bundled
                             :scope :user
                             :source "bundled"
                             :setting :default-tools
                             ;; the composition row this item is
                             :row-id (registry/port-name name)
                             :display-name name}})
               builtin-names)
         extra-items
         (mapv (fn [name]
                 (let [metadata (or (get meta-rows name)
                                    {:origin :tools
                                     :scope (if (and (contains? project-named name)
                                                     (not (contains? user-named name)))
                                              :project
                                              :user)
                                     :source "settings"})]
                   {:path (str "tools/" name)
                    :resource-type :tools
                    :enabled (if enabled (contains? enabled name) true)
                    :metadata (assoc metadata
                                     :setting :enabled-tools
                                     :row-id (registry/port-name name)
                                     :display-name name)}))
               extra-names)]
     (into builtin-items extra-items))))

(defn tool-override-state-of
  "The project override state of tool ITEM from the live project entries of
   its setting: :load for a plain/+ entry, :unload for -, and :inherit when
   none targets its name."
  [item]
  (reduce (fn [state entry]
            (let [parsed (parse-entry entry)]
              (if (and parsed (= (:name parsed) (tool-name item)))
                (if (= :remove (:modifier parsed)) :unload :load)
                state)))
          :inherit
          (or (get (cfg/read-project-settings-map) (tool-setting item)) [])))

;; ─── The :enabled-tools selection (S2) ─────────────────────────────────────

(defn default-scope
  "The scope a settings write defaults to: project when the cwd has `.kmet/`,
   else global (the config command's rule)."
  []
  (if (fs/exists? (str (fs/path (fs/cwd) ".kmet"))) :project :global))

(defn- tool-origins
  "Each live name's provenance from the raw settings entries — the enabling
   or disabling entry that decides its current state."
  [names global project]
  (let [project-targets (entry-targets project)
        global-targets (entry-targets global)
        project-absolute? (and (seq project)
                               (some #(not (registry/tool-selection-modifier? %)) project))
        global-absolute? (not (modifier-style? global))
        origin (fn [name]
                 (cond
                   (or project-absolute? (contains? project-targets name)) :project
                   (or global-absolute? (contains? global-targets name)) :global
                   :else :default))]
    (into {} (map (fn [name] [name (origin name)])) names)))

(defn tool-selection
  "The `/tools` dialog's one-call view: the raw `:enabled-tools` entries per
   scope, the effective ENABLED name set, and each live name's origin. SESSION
   is the runtime `set-active-tools!` set (nil = none); when present, every
   live name is sourced from the session. Returns
   `{:scope :entries {:global [...] :project [...]}
     :paths {:global <file> :project <file>}
     :enabled #{...} :origin {name :global|:project|:session|:default}}`."
  [live-names enabled {:keys [session]}]
  (let [global (:enabled-tools (cfg/read-global-settings-map))
        project (:enabled-tools (cfg/read-project-settings-map))
        live (set live-names)
        enabled (into #{} (filter live) enabled)]
    {:scope (default-scope)
     :entries {:global (entry-vector global)
               :project (entry-vector project)}
     :paths {:global (cfg/global-settings-path)
             :project (cfg/project-settings-path)}
     :enabled enabled
     :origin (if (some? session)
               (zipmap live (repeat :session))
               (tool-origins live global project))}))

(defn- delta-entry [name enabled]
  (str (if enabled "+" "-") name))

(def ^:private enabled->state
  "The project override state an :enabled-tools value encodes: true → :load,
   false → :unload, nil → :inherit."
  {true :load false :unload nil :inherit})

(defn- toggle-options
  "The `write-global-toggle!` options for SETTING-KEY: the tool's opt-in
   default, its resolver, and the base a plain list is rewritten over.
   :default-tools resolves over the built-in names; :enabled-tools resolves
   over LIVE-NAMES and rewrites the live list the save saw."
  [setting-key name live-names]
  (case setting-key
    :default-tools
    {:opt-in? (registry/default-off-tool? name)
     :resolve registry/resolve-default-tools
     :base (constantly registry/builtin-tool-names)}
    :enabled-tools
    {:opt-in? (registry/default-off-tool? name)
     :resolve #(registry/resolve-enabled-tools % live-names)
     :base (fn [entries]
             (distinct (concat (filterv string? (entry-vector entries))
                               (or live-names [])
                               [name])))}
    (throw (ex-info "not a tool setting"
                    {:type ::not-a-tool-setting :setting setting-key}))))

(defn- write-global-toggle!
  "Write NAME's ENABLED state into SETTING-KEY's global entry list. A
   modifier-only list keeps its shape: nil removes the entry, enabling a
   default-on tool removes it and disabling writes -name; for an OPT-IN? tool
   the inverse (enabling writes +name, disabling removes it). A plain list is
   rewritten as the effective selection of (RESOLVE entries) over (BASE
   entries). One writer serves both settings because they differ only in
   their resolver and base, not in their entry rule."
  [setting-key name enabled {:keys [opt-in? resolve base]}]
  (let [entries (get (cfg/read-global-settings-map) setting-key)]
    (if (modifier-style? entries)
      (let [kept (filterv #(not= name (entry-target %)) (entry-vector entries))
            updated (cond
                      (nil? enabled) kept
                      enabled (if opt-in? (conj kept (delta-entry name true)) kept)
                      opt-in? kept
                      :else (conj kept (delta-entry name false)))]
        (cfg/save-setting! [setting-key] (when (seq updated) updated)))
      (cfg/save-setting! [setting-key]
                         (filterv (cond-> (set (resolve entries))
                                    enabled (conj name)
                                    (not enabled) (disj name))
                                  (base entries))))))

(defn- write-project-override!
  "Set NAME's STATE (:load → +name, :unload → -name, :inherit → remove) in
   SETTING-KEY's project list, replacing any entry targeting NAME. An empty
   list clears the key — read-equivalent to absent
   (kmet.app.config/merge-tool-entries appends a project modifier-only list to
   the user list). One writer serves both settings."
  [setting-key name state]
  (when-not (contains? #{:load :unload :inherit} state)
    (throw (ex-info "state must be :load, :unload or :inherit"
                    {:type ::bad-override-state :name name :state state})))
  (let [current (entry-vector (get (cfg/read-project-settings-map) setting-key))
        kept (filterv #(not= name (entry-target %)) current)
        updated (if (= state :inherit)
                  kept
                  (conj kept (delta-entry name (= state :load))))]
    (cfg/save-project-setting! [setting-key] (when (seq updated) updated))))

(defn apply-tool-toggle!
  "Global-scope toggle of tool ITEM through its own setting: a default-tool
   row writes :default-tools, an enabled-tool row :enabled-tools. Both use the
   same writer, so an opt-in built-in enabled for the live set leaves +name.
   Always true."
  [item enabled]
  (let [setting (tool-setting item)
        name (tool-name item)]
    (write-global-toggle! setting name enabled
                          (toggle-options setting name nil)))
  true)

(defn apply-tool-project-override!
  "Project-scope override of tool ITEM: set STATE (:load → +name, :unload →
   -name, :inherit → remove) in its setting's project list. Always true."
  [item state]
  (write-project-override! (tool-setting item) (tool-name item) state)
  true)

(defn set-tool-enabled!
  "Persist NAME's enabled state in SCOPE: true enables, false disables, nil
   removes the scope's entry (inherit). OPTIONS: :scope (:global or :project;
   default `default-scope`), :live-names (the model-facing order a plain
   global list is rewritten in). Returns the merged :enabled-tools entry list
   after the write."
  [name enabled {:keys [scope live-names]}]
  (when-not (and (string? name) (seq name))
    (throw (ex-info "a tool name is required" {:type ::bad-tool-name :name name})))
  (when-not (or (nil? enabled) (boolean? enabled))
    (throw (ex-info "enabled must be true, false or nil"
                    {:type ::bad-enabled :name name :enabled enabled})))
  (let [scope (or scope (default-scope))]
    (case scope
      :global (write-global-toggle! :enabled-tools name enabled
                                    (toggle-options :enabled-tools name live-names))
      :project (write-project-override! :enabled-tools name
                                        (enabled->state enabled))
      (throw (ex-info "scope must be :global or :project"
                      {:type ::bad-scope :name name :scope scope}))))
  (cfg/merge-tool-entries (:enabled-tools (cfg/read-global-settings-map))
                          (:enabled-tools (cfg/read-project-settings-map))))

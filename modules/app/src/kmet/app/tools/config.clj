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

   Built-in toggles write the :default-tools setting. The global scope keeps a
   modifier-only list's shape — disabling writes -name so future built-ins
   inherit, enabling removes the entry — and rewrites a list with plain names
   as the effective selection (plain names replace the whole set). The project
   scope writes the +name/-name delta over the user list the other resources
   use (:load/:unload/:inherit). Non-built-in toggles write :enabled-tools
   with the same scope shapes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.config :as cfg]))

(defn- entry-target
  "The tool name ENTRY targets, modifier prefix stripped."
  [entry]
  (if (registry/tool-selection-modifier? entry) (subs (str entry) 1) (str entry)))

(defn- entry-vector [entries]
  (if (sequential? entries) (vec entries) []))

(defn- entry-targets
  "The names ENTRIES target, modifier prefixes stripped."
  [entries]
  (into #{}
        (keep (fn [entry]
                (when (and (string? entry) (seq entry))
                  (if (contains? #{"+" "-"} (subs entry 0 1))
                    (subs entry 1)
                    entry))))
        (entry-vector entries)))

(defn- modifier-style?
  "Whether ENTRIES keeps the modifier-only :default-tools shape: nil (no
   setting — every built-in enabled) or a non-empty list of +name/-name
   entries. An empty list is plain style — it selects no built-ins."
  [entries]
  (or (nil? entries)
      (and (seq entries) (every? registry/tool-selection-modifier? entries))))

(defn default-tool-item?
  "True when ITEM is a built-in tool row: its toggle writes :default-tools."
  [item]
  (and (= :tools (:resource-type item))
       (= :default-tools (get-in item [:metadata :setting]))))

(defn enabled-tool-item?
  "True when ITEM is a tool row whose toggle writes :enabled-tools — a name
   no built-in registry answers to."
  [item]
  (and (= :tools (:resource-type item))
       (= :enabled-tools (get-in item [:metadata :setting]))))

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
  ([user-settings project-settings mounted]
   (tool-items user-settings project-settings mounted nil))
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
  (let [name (tool-name item)]
    (reduce (fn [state entry]
              (if (and (string? entry) (= (entry-target entry) name))
                (if (str/starts-with? entry "-") :unload :load)
                state))
            :inherit
            (or (get (cfg/read-project-settings-map)
                     (get-in item [:metadata :setting]))
                []))))

(defn apply-builtin-tool-toggle!
  "Global-scope toggle of builtin ITEM. A modifier-only list (or an unset
   key) keeps its shape: for a default-on tool, enabling removes the entry and
   disabling writes -name; for an opt-in tool (`:default-enabled? false`),
   enabling writes +name and disabling clears the entry. An empty result clears
   the key. A list with plain names is rewritten as the effective selection in
   registry order — pi's plain names replace the whole set. Always true."
  [item enabled]
  (let [name (tool-name item)
        entries (:default-tools (cfg/read-global-settings-map))
        opt-in? (registry/default-off-tool? name)]
    (if (modifier-style? entries)
      (let [filtered (vec (remove #(= (entry-target %) name) entries))
            updated (cond
                      enabled (if opt-in?
                                (conj filtered (str "+" name))
                                filtered)
                      opt-in? filtered
                      :else (conj filtered (str "-" name)))]
        (cfg/save-setting! [:default-tools] (when (seq updated) updated))
        true)
      (let [selection (set (registry/resolve-default-tools entries))
            selection (if enabled (conj selection name) (disj selection name))
            updated (filterv selection registry/builtin-tool-names)]
        (cfg/save-setting! [:default-tools] updated)
        true))))

(defn apply-builtin-tool-project-override!
  "Project-scope override of builtin ITEM: set STATE (:load → +name,
   :unload → -name, :inherit → remove) in the project :default-tools list,
   replacing any entry targeting the name. An empty list clears the key —
   read-equivalent to absent (kmet.app.config/merge-tool-entries appends a
   project modifier-only list to the user list). Always true."
  [item state]
  (let [name (tool-name item)
        current (vec (or (:default-tools (cfg/read-project-settings-map)) []))
        filtered (filterv #(not= (entry-target %) name) current)
        updated (if (= state :inherit)
                  filtered
                  (conj filtered (str (if (= state :load) "+" "-") name)))]
    (cfg/save-project-setting! [:default-tools] (when (seq updated) updated))
    true))

;; ─── The generic :enabled-tools selection (S2) ─────────────────────────────

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

(defn- write-global-enabled!
  "Write NAME's state to the global :enabled-tools list. A modifier-only list
   keeps that shape: an opt-in built-in is enabled by +name and disabled by
   clearing the entry (its default is off), every other tool by clearing the
   entry and disabled by -name (the live-set default is on). nil clears the
   entry. A plain list is rewritten as the effective selection over
   LIVE-NAMES."
  [name enabled live-names]
  (let [entries (:enabled-tools (cfg/read-global-settings-map))
        opt-in? (registry/default-off-tool? name)]
    (if (modifier-style? entries)
      (let [kept (filterv #(not= name (entry-target %)) (entry-vector entries))
            updated (cond
                      (nil? enabled) kept
                      enabled (if opt-in? (conj kept (delta-entry name true)) kept)
                      opt-in? kept
                      :else (conj kept (delta-entry name false)))]
        (cfg/save-setting! [:enabled-tools] (when (seq updated) updated)))
      (let [selection (set (registry/resolve-enabled-tools entries live-names))
            selection (if enabled
                        (conj selection name)
                        (disj selection name))
            base (distinct (concat (filterv string? (entry-vector entries))
                                   (or live-names [])
                                   [name]))
            updated (filterv selection base)]
        (cfg/save-setting! [:enabled-tools] updated)))))

(defn- write-project-enabled!
  "Write NAME's state to the project :enabled-tools list: true → +name,
   false → -name, nil → remove the entry (inherit)."
  [name enabled]
  (let [current (entry-vector (:enabled-tools (cfg/read-project-settings-map)))
        kept (filterv #(not= name (entry-target %)) current)
        updated (if (nil? enabled)
                  kept
                  (conj kept (delta-entry name enabled)))]
    (cfg/save-project-setting! [:enabled-tools] (when (seq updated) updated))))

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
      :global (write-global-enabled! name enabled live-names)
      :project (write-project-enabled! name enabled)
      (throw (ex-info "scope must be :global or :project"
                      {:type ::bad-scope :name name :scope scope}))))
  (cfg/merge-tool-entries (:enabled-tools (cfg/read-global-settings-map))
                          (:enabled-tools (cfg/read-project-settings-map))))

(defn apply-enabled-tool-toggle!
  "Global-scope toggle of a non-built-in tool ITEM through :enabled-tools,
   in the merged list's own shape (a modifier-only list keeps +name/-name, a
   plain list is rewritten as the effective selection). Always true, so the
   screen's writer treats it like the built-in toggles."
  [item enabled]
  (set-tool-enabled! (tool-name item) enabled {:scope :global})
  true)

(defn apply-enabled-tool-project-override!
  "Project-scope override of non-built-in tool ITEM: set STATE (:load →
   +name, :unload → -name, :inherit → remove) in the project :enabled-tools
   list. Always true, like the built-in override writer."
  [item state]
  (set-tool-enabled! (tool-name item)
                     (case state :load true :unload false :inherit nil)
                     {:scope :project})
  true)

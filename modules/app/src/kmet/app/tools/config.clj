(ns kmet.app.tools.config
  "Built-in tool rows for the `kmet config` screen — the settings-backed
   sibling of the bundled/resources layers (pi has no built-in-tools group;
   pi's defaultTools setting is edited by hand).

   A built-in tool is a composition row (`:tool/read`, `:tool/bash`, …) now, so
   the group has two inputs: the `:default-tools` setting (the runtime
   selection, which is what a toggle writes) and the ambient composition layers
   (`mounted-tool-names` — a layer can remove a tool row, and a screen that
   offered a removed tool would be describing a run nobody gets).

   Each row is PackageItem-shaped for the screen's view model:
   :resource-type :tools, metadata origin :builtin-tools, display-name the
   tool name and row-id the row it is. The enabled state resolves the
   :default-tools entry list the same way startup does
   (kmet.app.config/merge-tool-entries +
   kmet.app.tools.registry/resolve-default-tools), and is false for a tool the
   layers do not mount.

   Toggles write the :default-tools setting. The global scope keeps a
   modifier-only list's shape — disabling writes -name so future built-ins
   inherit, enabling removes the entry — and rewrites a list with plain
   names as the effective selection (plain names replace the whole set).
   The project scope writes the +name/-name delta over the user list the
   other resources use (:load/:unload/:inherit)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.config :as cfg]))

(defn builtin-tool-item?
  "True when ITEM is a built-in tool row of the config screen."
  [item]
  (= :tools (:resource-type item)))

(defn builtin-tool-name
  "The tool name ITEM's settings entries target (its display name)."
  [item]
  (get-in item [:metadata :display-name]))

(defn- entry-target
  "The tool name ENTRY targets, modifier prefix stripped."
  [entry]
  (if (registry/tool-selection-modifier? entry) (subs (str entry) 1) (str entry)))

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

(defn builtin-tool-items
  "PackageItem-shaped rows for the built-in tools, one per registry
   built-in, in registry order. USER-SETTINGS and PROJECT-SETTINGS are the
   raw scope maps; each row's :enabled resolves their merged :default-tools
   entries (an unset setting enables every built-in). Pass nil
   PROJECT-SETTINGS for the global config view. Origin and source match
   the bundled layer so tools render in the same \"Bundled with kmet\"
   group, before the Extensions subgroup.

   MOUNTED is the tool names the composition layers leave mounted
   (`mounted-tool-names`), nil for every built-in: a tool whose row the layers
   removed is listed but not enabled — the run does not offer it, whatever the
   setting says."
  ([user-settings project-settings]
   (builtin-tool-items user-settings project-settings nil))
  ([user-settings project-settings mounted]
   (let [entries (cfg/merge-tool-entries (:default-tools user-settings)
                                         (:default-tools project-settings))
         selection (when entries
                     (set (registry/resolve-default-tools entries)))
         mounted (when mounted (set mounted))]
     (mapv (fn [name]
             {:path (str "tools/" name)
              :resource-type :tools
              :enabled (and (if selection (contains? selection name) true)
                            (if mounted (contains? mounted name) true))
              :metadata {:origin :bundled
                         :scope :user
                         :source "bundled"
                         ;; the composition row this item is
                         :row-id (registry/port-name name)
                         :display-name name}})
           registry/builtin-tool-names))))

(defn builtin-tool-override-state-of
  "The project override state of builtin ITEM from the live project
   :default-tools entries: :load for a plain/+ entry, :unload for -, and
   :inherit when none targets its name."
  [item]
  (let [name (builtin-tool-name item)]
    (reduce (fn [state entry]
              (if (and (string? entry) (= (entry-target entry) name))
                (if (str/starts-with? entry "-") :unload :load)
                state))
            :inherit
            (or (:default-tools (cfg/read-project-settings-map)) []))))

(defn- modifier-style?
  "Whether ENTRIES keeps the modifier-only :default-tools shape: nil (no
   setting — every built-in enabled) or a non-empty list of +name/-name
   entries. An empty list is plain style — it selects no built-ins."
  [entries]
  (or (nil? entries)
      (and (seq entries) (every? registry/tool-selection-modifier? entries))))

(defn apply-builtin-tool-toggle!
  "Global-scope toggle of builtin ITEM. A modifier-only list (or an unset
   key) keeps its shape: enabling removes the entry, disabling writes
   -name, and an empty result clears the key. A list with plain names is
   rewritten as the effective selection in registry order — pi's plain
   names replace the whole set. Always true."
  [item enabled]
  (let [name (builtin-tool-name item)
        entries (:default-tools (cfg/read-global-settings-map))]
    (if (modifier-style? entries)
      (let [filtered (vec (remove #(= (entry-target %) name) entries))
            updated (if enabled filtered (conj filtered (str "-" name)))]
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
  (let [name (builtin-tool-name item)
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

(defn- entry-vector [entries]
  (if (sequential? entries) (vec entries) []))

(defn- entry-actions
  "NAME → true/false for ENTRIES, the last entry targeting a name winning."
  [entries]
  (reduce (fn [m entry]
            (if-not (and (string? entry) (seq entry))
              m
              (let [prefix (subs entry 0 1)
                    target (if (contains? #{"+" "-"} prefix) (subs entry 1) entry)]
                (assoc m target (not= "-" prefix)))))
          {}
          entries))

(defn- tool-origins
  "Each live name's provenance from the raw settings entries — the enabling
   or disabling entry that decides its current state."
  [names global project]
  (let [project-actions (entry-actions project)
        global-actions (entry-actions global)
        project-absolute? (and (seq project)
                               (some #(not (registry/tool-selection-modifier? %)) project))
        global-absolute? (not (modifier-style? global))
        origin (fn [name]
                 (cond
                   project-absolute? :project
                   (contains? project-actions name) :project
                   global-absolute? :global
                   (contains? global-actions name) :global
                   :else :default))]
    (into {} (map (fn [name] [name (origin name)])) names)))

(defn tool-selection
  "The `/tools` dialog's one-call view: the raw `:enabled-tools` entries per
   scope, the effective ENABLED name set, and each live name's origin. SESSION
   is the runtime `set-active-tools!` set (nil = none); when present, every
   live name is sourced from the session. Returns
   `{:scope :entries {:global [...] :project [...]}
     :enabled #{...} :origin {name :global|:project|:session|:default}}`."
  [live-names enabled {:keys [session]}]
  (let [global (:enabled-tools (cfg/read-global-settings-map))
        project (:enabled-tools (cfg/read-project-settings-map))
        live (set live-names)
        enabled (into #{} (filter live) enabled)]
    {:scope (default-scope)
     :entries {:global (entry-vector global)
               :project (entry-vector project)}
     :enabled enabled
     :origin (if (some? session)
               (zipmap live (repeat :session))
               (tool-origins live global project))}))

(defn- merged-enabled-entries []
  (cfg/merge-tool-entries (:enabled-tools (cfg/read-global-settings-map))
                          (:enabled-tools (cfg/read-project-settings-map))))

(defn- write-global-enabled!
  "Write NAME's state to the global :enabled-tools list. A modifier-only list
   keeps that shape (false writes -name, true/nil remove it); a plain list is
   rewritten as the effective selection over LIVE-NAMES."
  [name enabled live-names]
  (let [entries (:enabled-tools (cfg/read-global-settings-map))]
    (if (modifier-style? entries)
      (let [kept (filterv #(not= name (entry-target %)) (entry-vector entries))
            updated (if (false? enabled) (conj kept (str "-" name)) kept)]
        (cfg/save-setting! [:enabled-tools] (when (seq updated) updated)))
      (let [selection (set (registry/resolve-enabled-tools entries live-names))
            selection (if (true? enabled)
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
                  (conj kept (str (if (true? enabled) "+" "-") name)))]
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
  (merged-enabled-entries))

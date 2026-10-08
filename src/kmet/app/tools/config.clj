(ns kmet.app.tools.config
  "Built-in tool rows for the `kmet config` screen — the settings-backed
   sibling of the bundled/resources layers (pi has no built-in-tools group;
   pi's defaultTools setting is edited by hand).

   Each row is PackageItem-shaped for the screen's view model:
   :resource-type :tools, metadata origin :builtin-tools, display-name the
   tool name. The enabled state resolves the :default-tools entry list the
   same way startup does (kmet.config/merge-default-tools +
   kmet.app.tools.registry/resolve-default-tools).

   Toggles write the :default-tools setting. The global scope keeps a
   modifier-only list's shape — disabling writes -name so future built-ins
   inherit, enabling removes the entry — and rewrites a list with plain
   names as the effective selection (plain names replace the whole set).
   The project scope writes the +name/-name delta over the user list the
   other resources use (:load/:unload/:inherit)."
  (:require [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.config :as cfg]))

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

(defn builtin-tool-items
  "PackageItem-shaped rows for the built-in tools, one per registry
   built-in, in registry order. USER-SETTINGS and PROJECT-SETTINGS are the
   raw scope maps; each row's :enabled resolves their merged :default-tools
   entries (an unset setting enables every built-in). Pass nil
   PROJECT-SETTINGS for the global config view. Origin and source match
   the bundled layer so tools render in the same \"Bundled with kmet\"
   group, before the Extensions subgroup."
  [user-settings project-settings]
  (let [entries (cfg/merge-default-tools (:default-tools user-settings)
                                         (:default-tools project-settings))
        selection (when entries
                    (set (registry/resolve-default-tools entries)))]
    (mapv (fn [name]
            {:path (str "tools/" name)
             :resource-type :tools
             :enabled (if selection (contains? selection name) true)
             :metadata {:origin :bundled
                        :scope :user
                        :source "bundled"
                        :display-name name}})
          registry/builtin-tool-names)))

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
   read-equivalent to absent (kmet.config/merge-default-tools appends a
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

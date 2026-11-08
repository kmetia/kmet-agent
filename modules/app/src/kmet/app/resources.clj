(ns kmet.app.resources
  "Resource selection: which available tools, skills and prompt templates a
   consumer uses.

   A resource is available when a provider mounted it in the current project
   run (a tool row or extension registration); selection is the settings
   layer's choice over that set. A consumer uses a resource only when it is
   both: `enabled?` is `(and (available? kind name) (selected? kind name))`.

   **The store is scoped.** The global settings map carries enable/disable
   entries per type (`:resources {:tools [...] :skills [...] :prompts [...]}`);
   the project map carries only `+name`/`-name` deltas over it. `selected?`
   resolves global first, then applies the project's last entry for a name:

     defaults -> global selection -> project delta

   A missing global type list inherits the defaults; an empty one selects
   nothing; a modifier-only list starts from defaults and applies `+name` and
   `-name` in order; a plain or mixed list uses its plain names as an absolute
   base, then applies the modifiers. A project list is valid only when every
   entry is a `+name`/`-name` modifier; a plain entry invalidates the whole
   type list (warned once, then ignored), and the project inherits global.
   `kmet.app.config` reads the raw scope maps and never deep-merges
   `:resources`.

   **Selection survives availability.** A stored entry for a provider that is
   not mounted in this run stays in settings and stays selected, but
   `available?`/`enabled?` are false and `catalog` has no row for it. Mounting
   the provider again makes the same entry apply, with no pruning and no
   cleanup.

   **Unavailable entries stay visible to the selector.** `unavailable` reports
   the stored names a scope mentions that no mounted provider offers, so
   `/config` can show them in a dimmed group and let the user forget them.
   Forgetting a global entry removes its plain or modifier form and clears the
   type key when the list empties (the type falls back to its defaults);
   forgetting a project entry clears that delta, so the project inherits
   global again.

   The service value is a map of fns (`(catalog kind)`, `(enabled-names kind)`,
   `(set-project! kind name state)`, …); the kind is `:tool`, `:skill` or
   `:prompt`, and its settings key is the plural (`:tools`, `:skills`,
   `:prompts`)."
  (:require [kmet.app.config :as cfg]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.util :as tool-util]
            [kmet.srce.core :as srce]))

;; ─── Types and settings keys ───────────────────────────────────────────────

(def ^:private type-info
  "Per-type settings key and registry tag."
  {:tool {:settings :tools :tag :kmet/tool}
   :skill {:settings :skills :tag :kmet/skill}
   :prompt {:settings :prompts :tag :kmet/prompt}})

(def types
  "The selectable resource types."
  (set (keys type-info)))

(defn settings-key
  "The `:resources` settings key for KIND."
  [kind]
  (get-in type-info [kind :settings]))

(defn- registry-tag
  "The registry tag KIND is read from."
  [kind]
  (get-in type-info [kind :tag]))

(def states
  "The project-scope states a resource can take."
  #{:inherit :enabled :disabled})

(def scopes
  "The two settings scopes a persisted entry can live in."
  #{:global :project})

(defn- check-scope
  "SCOPE when it is a settings scope; otherwise throw."
  [scope]
  (when-not (contains? scopes scope)
    (throw (ex-info (str "scope must be one of " (pr-str (vec (sort scopes))))
                    {:type ::bad-scope :scope scope})))
  scope)

(defn- check-kind
  "KIND when it is a selectable resource type; otherwise throw."
  [kind]
  (when-not (contains? types kind)
    (throw (ex-info (str "unknown resource type " (pr-str kind)
                         " — one of " (pr-str (vec (sort types))))
                    {:type ::bad-kind :kind kind})))
  kind)

(defn- check-name
  "NAME when it is a non-blank string; otherwise throw."
  [name]
  (when-not (and (string? name) (seq name))
    (throw (ex-info (str "a resource name is required, got " (pr-str name))
                    {:type ::bad-name :name name})))
  name)

(defn- raw-entries
  "The raw entry list for KIND in a `:resources` map: nil when absent, a
   vector when sequential, or ::invalid for a malformed value."
  [resource-settings kind]
  (let [v (get resource-settings (settings-key kind))]
    (cond
      (nil? v) nil
      (sequential? v) (vec v)
      :else ::invalid)))

(defn- global-entries
  "The global entry list for KIND; a malformed value reads as an empty list
   (it selects nothing, the same lenient reading the tool resolver uses)."
  [resource-settings kind]
  (let [v (raw-entries resource-settings kind)]
    (if (= ::invalid v) [] v)))

;; ─── Entry grammar ─────────────────────────────────────────────────────────

(defn- modifier?
  "True for a `+name`/`-name` entry."
  [entry]
  (tool-util/tool-selection-modifier? entry))

(defn- modifier-name
  "ENTRY's target name with the modifier prefix stripped."
  [entry]
  (subs entry 1))

(defn- valid-project-entry?
  "True when ENTRY is a `+name`/`-name` modifier with a non-empty name."
  [entry]
  (and (modifier? entry)
       (> (count entry) 1)))

(defn- stored-entry-names
  "The names ENTRIES target, keeping a plain name and stripping a modifier's
   prefix. Blank and non-string entries are dropped."
  [entries]
  (into []
        (keep (fn [entry]
                (when (string? entry)
                  (let [name (if (modifier? entry)
                               (when (< 1 (count entry)) (modifier-name entry))
                               entry)]
                    (when (and name (seq name)) name)))))
        entries))

;; ─── Project list validation ───────────────────────────────────────────────

(defonce ^:private warned-project-lists (atom #{}))

(defn- warn-invalid-project-list!
  "Warn once per KIND and ENTRY LIST that a project resource list has a plain
   or malformed entry. The caller then ignores that type's project list."
  [kind entries]
  (let [key [kind entries]]
    (when-not (contains? @warned-project-lists key)
      (swap! warned-project-lists conj key)
      (binding [*out* *err*]
        (println (str "Warning: project :resources/" (name (settings-key kind))
                      " has a plain or malformed entry; ignoring the project "
                      "list and inheriting the global selection."))))))

(defn- valid-project-list?
  "True when ENTRIES is a project delta: a sequential list of `+name`/`-name`
   entries with non-empty names."
  [entries]
  (and (sequential? entries)
       (every? valid-project-entry? entries)))

(defn- project-states
  "The project override map for KIND, name → :enabled/:disabled. A missing or
   empty list inherits; an invalid list is warned about once and ignored."
  [kind entries]
  (cond
    (nil? entries) {}
    (and (sequential? entries) (empty? entries)) {}
    (valid-project-list? entries)
    (reduce (fn [states entry]
              (assoc states (modifier-name entry)
                     (if (= \+ (first entry)) :enabled :disabled)))
            {}
            entries)
    :else
    (do (warn-invalid-project-list! kind entries) {})))

;; ─── Defaults and global resolution ───────────────────────────────────────

(defn- default-on?
  "Whether NAME is selected by default: every skill and prompt is, and a tool
   unless it is a built-in row marked `:default-enabled? false`."
  [kind name]
  (case kind
    :tool (not (tools/default-off-tool? name))
    (:skill :prompt) true))

(defn- default-names
  "The names of UNIVERSE selected by default."
  [kind universe]
  (filterv #(default-on? kind %) universe))

(defn- resolve-global
  "The effective global selection vector for KIND over UNIVERSE (available
   names plus names either scope's entries mention). ENTRIES nil inherits the
   defaults; an empty list selects nothing; a list with any plain name uses
   those names as its absolute base; modifiers apply in list order."
  [kind entries universe]
  (cond
    (nil? entries) (default-names kind universe)
    (not (sequential? entries)) []
    :else (tools/resolve-selection entries (default-names kind universe))))

;; ─── Availability ──────────────────────────────────────────────────────────

(defn- handle-for
  "The registry handle that owns KIND."
  [{:keys [tools skills prompts]} kind]
  (case kind
    :tool tools
    :skill skills
    :prompt prompts))

(defn- available-names
  "The names the providers mounted in this run make available for KIND, in
   registry order."
  [handles kind]
  (let [handle (handle-for handles kind)]
    (case kind
      :tool (vec (keys (tools/get-all-tools handle)))
      (:skill :prompt)
      (mapv first (registry/effective-entries handle (registry-tag kind))))))

(defn- extension-owner?
  "True for an extension's owner path, `[:app :extensions <name>]`."
  [owner]
  (and (vector? owner)
       (= :app (first owner))
       (= :extensions (second owner))
       (>= (count owner) 3)))

(defn- provider-of
  "The provider metadata for OWNER: an extension id, a built-in tool row id, or
   the app owner path."
  [owner]
  (cond
    (extension-owner? owner)
    {:kind :extension
     :id (keyword "ext" (str (nth owner 2)))}

    (and (keyword? owner) (= "tool" (namespace owner)))
    {:kind :builtin :id owner}

    (some? owner)
    {:kind :app :id owner}

    :else
    {:kind :app :id nil}))

(defn- owner-of
  "The owner that holds VALUE, the effective registration of NAME under TAG;
   nil when no registration holds it (a deferred source's tool, whose owner
   `provider` reads from the source)."
  [handle tag name value]
  (some (fn [[owner v]] (when (= v value) owner))
        (registry/by-owner handle tag name)))

(defn- tool-provider-owners
  "The owner path for every model-facing tool name: a direct registration's
   owner where one holds the name, otherwise the deferred source's owner.
   Read once per catalog, so `provider` is a map lookup."
  [handle]
  (reduce (fn [owners [name value]]
            (if-let [owner (owner-of handle :kmet/tool name value)]
              (assoc owners name owner)
              owners))
          (tools/deferred-tool-owners handle)
          (tools/get-all-tools handle)))

(defn- provider
  "Provider metadata for KIND's NAME, read from the effective value — the
   shadowing tool for :tool, the first-wins entry for skills and prompts.
   TOOL-OWNERS is the resolved name -> owner map for :tool."
  [handles kind name tool-owners]
  (let [handle (handle-for handles kind)
        tag (registry-tag kind)
        owner (if (= kind :tool)
                (get tool-owners name)
                (when-let [value (and handle (registry/entry handle tag name))]
                  (owner-of handle tag name value)))]
    (provider-of owner)))

;; ─── Selection snapshot ────────────────────────────────────────────────────

(defn- snapshot
  "The current selection view for KIND: availability, the resolved global
   vector and set, the project override map, and the names each scope's raw
   entry list mentions."
  [handles kind]
  (let [{:keys [global project]} (cfg/read-resource-settings)
        global-list (global-entries global kind)
        project-list (raw-entries project kind)
        available (available-names handles kind)
        universe (vec (distinct (concat available
                                        (stored-entry-names global-list)
                                        (stored-entry-names
                                         (when (valid-project-list? project-list)
                                           project-list)))))
        global-vector (resolve-global kind global-list universe)]
    {:available available
     :available-set (set available)
     :global-set (set global-vector)
     :global-stored (vec (distinct (stored-entry-names global-list)))
     :project-stored (vec (distinct
                           (stored-entry-names
                            (when (valid-project-list? project-list)
                              project-list))))
     :project (project-states kind project-list)}))

(defn- selected-in
  "The effective persisted selection in SNAPSHOT for NAME, ignoring
   availability."
  [{:keys [global-set project]} name]
  (case (get project name :inherit)
    :enabled true
    :disabled false
    (contains? global-set name)))

(defn- resource-row
  "One row for KIND's NAME in SNAP. AVAILABLE? tells whether a mounted
   provider offers it; PROVIDER is the provider metadata or the unavailable
   marker."
  [snap kind name provider available?]
  (let [selected? (selected-in snap name)]
    {:kind :resource
     :type kind
     :name name
     :available? available?
     :provider provider
     :global? (contains? (:global-set snap) name)
     :project-state (get (:project snap) name :inherit)
     :selected? selected?
     :enabled? (and available? selected?)}))

(defn- catalog-rows
  "The available rows for KIND with both scope states and the effective
   result, sorted by name."
  [handles kind]
  (let [{:keys [available] :as snap} (snapshot handles kind)
        tool-owners (when (= kind :tool)
                      (some-> (:tools handles) tool-provider-owners))]
    (->> available
         (map (fn [name]
                (resource-row snap kind name
                              (provider handles kind name tool-owners) true)))
         (sort-by :name)
         vec)))

(defn- unavailable-rows
  "The rows for KIND's stored entries in SCOPE that no mounted provider
   offers, sorted by name. A project list that is not a valid delta is
   already ignored by `project-states`, so it contributes none."
  [handles kind scope]
  (let [{:keys [available-set global-stored project-stored] :as snap}
        (snapshot handles kind)
        names (remove available-set
                      (if (= scope :global) global-stored project-stored))]
    (->> names
         (map (fn [name]
                (resource-row snap kind name
                              {:kind :unavailable :id nil} false)))
         (sort-by :name)
         vec)))

;; ─── Writers ───────────────────────────────────────────────────────────────

(defn- modifier-style?
  "Whether ENTRIES keeps the modifier-only selection shape: nil (no setting)
   or a non-empty list of +name/-name entries. An empty list is plain style —
   it selects nothing."
  [entries]
  (or (nil? entries)
      (and (seq entries) (every? modifier? entries))))

(defn- save-global!
  "Persist KIND's global entry list; nil removes the type key (defaults
   apply again)."
  [kind entries]
  (let [path [:resources (settings-key kind)]]
    (if (nil? entries)
      (cfg/remove-setting! path)
      (cfg/save-setting! path entries))))

(defn- save-project!
  "Persist KIND's project entry list; nil removes the type key (inherit)."
  [kind entries]
  (let [path [:resources (settings-key kind)]]
    (if (nil? entries)
      (cfg/remove-project-setting! path)
      (cfg/save-project-setting! path entries))))

(defn- forget-global!
  "Drop every global entry targeting NAME, plain or modifier. An emptied type
   list clears its key, so the type falls back to its defaults. Returns the
   stored entries (unchanged when NAME is not mentioned)."
  [kind name]
  (let [global (:global (cfg/read-resource-settings))
        current (global-entries global kind)]
    (if (empty? current)
      current
      (let [target (fn [entry]
                     (if (modifier? entry) (modifier-name entry) entry))
            kept (filterv #(not (and (string? %) (= name (target %))))
                          current)]
        (if (= kept current)
          current
          (let [updated (not-empty kept)]
            (save-global! kind updated)
            updated))))))

(defn- write-global!
  "Write NAME's ENABLED? state into KIND's global list. A missing or
   modifier-only list keeps its shape: enabling a default-on resource removes
   its entry, disabling it writes -name; for a default-off resource the inverse
   (enabling writes +name, disabling removes it). An empty/plain/mixed list is
   rewritten as its effective plain selection. Returns the stored entries."
  [kind name enabled?]
  (let [global (:global (cfg/read-resource-settings))
        current (global-entries global kind)
        updated
        (if (modifier-style? current)
          (let [kept (filterv #(not (and (modifier? %)
                                         (= name (modifier-name %))))
                              (or current []))
                default-on (default-on? kind name)]
            (not-empty (cond
                         (and enabled? default-on) kept
                         enabled? (conj kept (str "+" name))
                         default-on (conj kept (str "-" name))
                         :else kept)))
          (let [selection (resolve-global kind current [])]
            (if enabled?
              (if (some #{name} selection)
                selection
                (conj selection name))
              (filterv #(not= name %) selection))))]
    (save-global! kind updated)
    updated))

(defn- write-project!
  "Write NAME's STATE into KIND's project delta. The write drops any plain
   entries from the type list, replaces every entry targeting NAME, and clears
   the type key when the delta becomes empty. Returns the stored entries."
  [kind name state]
  (let [project (:project (cfg/read-resource-settings))
        current (raw-entries project kind)
        kept (filterv (fn [entry]
                        (and (valid-project-entry? entry)
                             (not= name (modifier-name entry))))
                      (if (sequential? current) current []))
        updated (case state
                  :inherit kept
                  :enabled (conj kept (str "+" name))
                  :disabled (conj kept (str "-" name)))
        updated (not-empty updated)]
    (save-project! kind updated)
    updated))

;; ─── Read-time filter ──────────────────────────────────────────────────────

(defn enabled
  "ITEMS (maps carrying a `:name`) filtered to the names RESOURCES, a
   `:resources` service value, enables for KIND. A nil RESOURCES (the
   composition mounted no row) keeps every item; an empty selection keeps
   none."
  [resources kind items]
  (if-some [names (and resources ((:enabled-names resources) kind))]
    (filterv #(contains? names (:name %)) items)
    (vec items)))

;; ─── The service ───────────────────────────────────────────────────────────

(defn service
  "The `:resources` service value over the live registry HANDLES
   (`{:tools :skills :prompts}`). Every read re-reads the settings files and
   the registries, so a project layer or a provider mounted later is visible
   without a restart.

   Keys:
     (catalog kind)                  ; available rows, both scope states
     (available? kind name)          ; a provider offers NAME
     (global-selected? kind name)    ; the global list selects NAME
     (project-state kind name)       ; :inherit | :enabled | :disabled
     (selected? kind name)           ; effective persisted selection
     (enabled? kind name)            ; available and selected
     (enabled-names kind)            ; available and selected names
     (unavailable kind scope)        ; stored entries no provider offers
     (set-global! kind name enabled-or-:inherit)
     (set-project! kind name state)"
  [handles]
  (letfn [(snap [kind] (snapshot handles kind))]
    {:catalog
     (fn [kind]
       (check-kind kind)
       (catalog-rows handles kind))

     :available?
     (fn [kind name]
       (check-kind kind)
       (boolean (some #(= name %) (available-names handles kind))))

     :global-selected?
     (fn [kind name]
       (check-kind kind)
       (contains? (:global-set (snap kind)) name))

     :project-state
     (fn [kind name]
       (check-kind kind)
       (get (:project (snap kind)) name :inherit))

     :selected?
     (fn [kind name]
       (check-kind kind)
       (selected-in (snap kind) name))

     :enabled?
     (fn [kind name]
       (check-kind kind)
       (let [state (snap kind)]
         (and (contains? (:available-set state) name)
              (selected-in state name))))

     :enabled-names
     (fn [kind]
       (check-kind kind)
       (let [{:keys [available] :as state} (snap kind)]
         (into #{} (filter #(selected-in state %)) available)))

     :unavailable
     (fn [kind scope]
       (check-kind kind)
       (check-scope scope)
       (unavailable-rows handles kind scope))

     :set-global!
     (fn [kind name enabled-or-inherit]
       (check-kind kind)
       (check-name name)
       (cond
         (= :inherit enabled-or-inherit)
         (forget-global! kind name)

         (boolean? enabled-or-inherit)
         (write-global! kind name enabled-or-inherit)

         :else
         (throw (ex-info "enabled? must be true, false, or :inherit"
                         {:type ::bad-enabled :name name
                          :enabled enabled-or-inherit}))))

     :set-project!
     (fn [kind name state]
       (check-kind kind)
       (check-name name)
       (when-not (contains? states state)
         (throw (ex-info (str "state must be one of " (pr-str (vec (sort states))))
                         {:type ::bad-state :name name :state state})))
       (write-project! kind name state))}))

;; ─── The row ───────────────────────────────────────────────────────────────

(defn- serve
  "The row's apply: provide the service over the registries injected into this
   mount site."
  [ctx _config]
  (srce/provide! ctx :resources
                 (service {:tools (srce/get ctx :tools)
                           :skills (srce/get ctx :skills)
                           :prompts (srce/get ctx :prompts)})
                 {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :resources
   :inject [:tools :skills :prompts]
   :provide #{:resources}
   :apply serve})

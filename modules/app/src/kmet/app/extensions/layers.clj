(ns kmet.app.extensions.layers
  "The plugin rows of the two ambient composition layers.

   Extensions are plugins: a run loads the ones its composition names
   (`:ext/<id>` rows) and nothing else. Built-in tools are rows too
   (`:tool/<name>` in `:bundle/minimal`), removed or restored by an ambient
   patch. The two files a user edits directly are the global
   `~/.kmet/agent/patch.edn` and the project `.kmet/patch.edn` (see
   `docs/profiles.md`); this namespace reads those layers, resolves the
   extension rows they compose, computes a built-in row's state, and writes
   the row or remove patch a `kmet plugins` toggle needs.

   Reading is data-only: a layer is an EDN map with `:rows` and `:patch`
   collections, and nothing here loads an extension. A malformed file is
   reported as `:error` on that layer's record and composes as empty, so the
   plugin screen still opens (the run itself fails at compose with the usual
   `:kmet.app/bad-patch-file`). Writing refuses to touch a malformed file.
   `prune-unknown-extensions!` is the one write a run makes without a toggle:
   a row naming an extension that is not installed is dropped from the layer
   (and the file saved) before the composition reads it, with the remove and
   config patches left dangling.

   The composition mirrors `kmet.app.profile/compose` for extension rows:
   rows add, a patch with a `:plugin` adds or replaces by id, and a `:remove`
   patch drops the row with that id. Other rows and patches are ignored here;
   the run's own compose still sees them. Built-in rows are tracked by id: the
   base row is composed until a `:remove` patch drops it, and a project
   `:plugin` insert restores one the global layer removed.

   A write re-renders the layer's EDN and keeps the file's leading comments;
   it runs under the EDN store's file lock, so two editors cannot lose each
   other's toggle. A write also drops the remove and config patches whose
   target no built-in row and no row in either ambient layer names — a retired
   row id or a typo the run drops in memory and warns about — so the editor's
   save heals the file a run only reports."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.rows :as rows]
            [kmet.app.profile :as profile]
            [kmet.libs.edn-store :as eds]))

(defn- layer-error
  [path message]
  {:type :kmet.app/bad-patch-file :path path :message message})

(defn- read-layer
  "One layer file as `{:path PATH :data MAP}`; a missing file is empty, a
   malformed or non-layer file carries `:error` and empty `:data`."
  [path]
  (if-not (fs/exists? path)
    {:path path :data {}}
    (try
      (let [data (edn/read-string (slurp path))]
        (if (profile/layer? data)
          {:path path :data data}
          {:path path :data {}
           :error (layer-error path
                               "a composition layer is a map with :rows and :patch collections")}))
      (catch Exception e
        {:path path :data {}
         :error (layer-error path (or (ex-message e) (str e)))}))))

(defn layers
  "The ambient layers in application order, `{:global LAYER :project LAYER}`.
   Both are read even when missing; `kmet plugins` edits them by name."
  []
  {:global (read-layer (cfg/global-patch-path))
   :project (read-layer (cfg/project-patch-path))})

(defn- row-id
  "A row's identity: its `:id`, or its source when the row omits one (the
   catalog defaults the id to the card name, which for an extension row is
   the source)."
  [row]
  (or (:id row) (:plugin row)))

(defn- ext-row?
  [row]
  (rows/source? (:plugin row)))

(defn- enables?
  "True when ENTRY (a row or a patch insert) composes SOURCE."
  [entry source]
  (and (ext-row? entry) (= source (:plugin entry))))

(defn- apply-layer
  "Apply one layer's extension rows and patches to STATE
   (`{:rows {id source}}`). Last row with an id wins, as in the run's
   compose: a later insert with an existing id replaces it."
  [state layer]
  (let [state (reduce (fn [s row]
                        (if (ext-row? row)
                          (assoc-in s [:rows (row-id row)] (:plugin row))
                          s))
                      state
                      (:rows layer))]
    (reduce (fn [s patch]
              (cond
                (:remove patch) (update s :rows dissoc (:id patch))
                (ext-row? patch) (assoc-in s [:rows (row-id patch)] (:plugin patch))
                :else s))
            state
            (:patch layer))))

(defn- source-ids
  "The row ids STATE composes SOURCE with (a row or a patch insert), as a
   vector in the state map's own order."
  [state source]
  (into [] (keep (fn [[id src]] (when (= src source) id)) (:rows state))))

(defn- enablement-ids
  "The row ids ROWS or patch inserts compose SOURCE with, ignoring remove
   patches (what a toggle-on must clear)."
  [rows patches source]
  (into #{} (comp (filter #(enables? % source)) (map row-id))
        (concat rows patches)))

(defn composition
  "The extension rows the two layers compose:
   `{:global {:rows {id source}} :project {:rows {id source}}}`. The project
   state is the global state with the project layer applied on top."
  [{:keys [global project]}]
  (let [global-state (apply-layer {:rows {}} (:data global))]
    {:global global-state
     :project (apply-layer global-state (:data project))}))

(defn sources-of
  "The set of `:ext/<id>` sources STATE composes."
  [state]
  (set (vals (:rows state))))

(defn- scoped-state
  "The item state for a row whose global and project presence are GLOBAL?
   and PROJECT?: `{:enabled bool :override-state :inherit|:load|:unload
   :inherited-enabled bool}`. `:inherited-enabled` is what a project override
   inherits, and the project override state is the difference from it. The
   plugin screen renders both."
  [global? project? scope]
  {:inherited-enabled global?
   :enabled (if (= scope :global) global? project?)
   :override-state (if (= scope :global)
                     :inherit
                     (cond
                       (and project? (not global?)) :load
                       (and global? (not project?)) :unload
                       :else :inherit))})

(defn item-state
  "The composition state of extension SOURCE in SCOPE (`:global` or
   `:project`). See `scoped-state` for the shape."
  [{:keys [global project]} source scope]
  (scoped-state (contains? (sources-of global) source)
                (contains? (sources-of project) source)
                scope))

(defn- row-present?
  "Whether LAYER leaves ID composed, starting from PRESENT?. All rows of a
   layer are appended before its patches, as in `kmet.app.profile/compose`:
   a row restores ID, a `:plugin` patch inserts or replaces it, and a
   `:remove` patch drops it."
  [present? layer id]
  (let [present (if (some #(= id (row-id %)) (:rows layer))
                  true
                  present?)]
    (reduce (fn [present patch]
              (if (= id (:id patch))
                (cond
                  (:remove patch) false
                  (:plugin patch) true
                  :else present)
                present))
            present
            (:patch layer))))

(defn builtin-item-state
  "The composition state of built-in row ID in SCOPE (`:global` or
   `:project`): `{:enabled bool :override-state :inherit|:load|:unload
   :inherited-enabled bool}`. The base composition supplies the row, so the
   global layer's state is 'removed by a `:remove` patch or not', and the
   project layer can remove a globally composed row or insert one the global
   layer removed. The shape matches `item-state`."
  [{:keys [global project]} id scope]
  (let [global? (row-present? true (:data global) id)
        project? (row-present? global? (:data project) id)]
    (scoped-state global? project? scope)))

;; ─── Writing ─────────────────────────────────────────────────────────────

(defn- read-data!
  "The parsed layer DATA, or `:kmet.app/bad-patch-file` — a write must never
   clobber a file it cannot read."
  [path]
  (let [{:keys [data error]} (read-layer path)]
    (when error
      (throw (ex-info (str "cannot edit " path ": " (:message error))
                      (assoc error :type :kmet.app/bad-patch-file))))
    data))

(defn- comment-header
  "TEXT's leading comment and blank lines, newline-terminated: the header a
   hand-written layer carries. Nil when the file starts with the EDN form."
  [text]
  (let [lines (take-while #(or (str/blank? %) (str/starts-with? (str/triml %) ";"))
                          (str/split-lines text))]
    (when (seq lines)
      (str (str/join \newline lines) \newline))))

(defn- write-layer!
  "Write DATA to PATH, keeping the file's leading comments."
  [path data]
  (let [header (when (fs/exists? path) (comment-header (slurp path)))]
    (spit path (str header (with-out-str (pprint/pprint data))))))

(defn- builtin-row-ids
  "Every row id the built-in table declares — each bundle's rows and each
   profile's layers — across all profiles. Ambient layers apply to every
   profile, so a row any of them composes is a patch target a save keeps; an
   id none of them declares (a retired row, a typo) can never apply. Data
   only."
  []
  (let [table (profile/builtin)]
    (into #{}
          (comp (mapcat :rows) (keep :id))
          (concat (vals (:bundles table))
                  (mapcat #(profile/layers table %) (keys (:profiles table)))))))

(defn- target-ids
  "The row ids a remove or config patch in the two ambient LAYER-DATAS may
   keep naming: the built-in rows, plus the id of every row and insert the
   readable layers themselves carry. An id outside this set names no row
   anywhere, so a save drops the patch instead of writing it back."
  [layer-datas]
  (into (builtin-row-ids)
        (comp (mapcat (fn [{:keys [rows patch]}] (concat rows patch)))
              (filter :plugin)
              (map row-id))
        layer-datas))

(defn- stale-patch?
  "True when PATCH is a remove or config patch whose :id is not in IDS."
  [ids patch]
  (and (map? patch)
       (contains? patch :id)
       (not (:plugin patch))
       (not (contains? ids (:id patch)))))

(defn- without-stale-patches
  "DATA with the remove and config patches whose target IDS does not name
   removed; every other key and entry is untouched."
  [data ids]
  (let [patch (:patch data)
        kept (into [] (remove #(stale-patch? ids %)) patch)]
    (if (= (vec patch) kept)
      data
      (assoc data :patch kept))))

(defn- update-layer!
  "Read PATH, drop the remove and config patches whose target no row in the
   two ambient layers or in the built-in table names (a save must not write a
   retired row id or a typo back), apply F to the remaining `[rows patch]` and
   write the result back when anything changed, keeping the layer's other keys
   and its leading comments. The read-modify-write runs under the EDN store's
   file lock. Returns true when the file changed."
  [path f]
  (when-let [dir (fs/parent path)]
    (fs/create-dirs dir))
  (eds/with-file-lock (str path ".lock")
    (fn []
      (let [original (read-data! path)
            data (without-stale-patches original
                                        (target-ids (map :data (vals (layers)))))
            rows (vec (or (:rows data) []))
            patch (vec (or (:patch data) []))
            [next-rows next-patch] (f rows patch)
            next (if (and (= rows next-rows) (= patch next-patch))
                   data
                   (assoc data :rows next-rows :patch next-patch))]
        (if (= original next)
          false
          (do (write-layer! path next)
              true))))))

(defn- uninstalled?
  "True when SOURCE is an `:ext/<id>` id no installed extension answers to —
   the source a compose would reject with `:kmet.app/unknown-extension`."
  [source]
  (and (rows/source? source)
       (nil? (rows/entry source))))

(defn- unknown-extension?
  "True when ENTRY composes an extension that is not installed."
  [entry]
  (uninstalled? (:plugin entry)))

(defn prune-unknown-extensions!
  "Remove from the readable layer files PATHS the rows and patch inserts whose
   `:ext/<id>` source names no installed extension, along with the remove and
   config patches whose id is left dangling by that removal (or is itself an
   absent `:ext/<id>`), and save each changed file. Returns
   `[{:path path :sources [source …]} …]` for the layers a source was removed
   from, in the order the files were read; a missing or malformed file is left
   alone (the compose reports a malformed one). The check is `rows/entry`, the
   resolver the compose uses, so the rows and inserts removed are exactly the
   unknown-extension entries that would fail the composition."
  [paths]
  (let [readable (filterv (complement :error) (mapv read-layer paths))
        entries (fn [layer] (concat (:rows (:data layer)) (:patch (:data layer))))
        unknowns (into {}
                       (map (fn [layer]
                              [(:path layer)
                               (filterv unknown-extension? (entries layer))]))
                       readable)
        ;; a remove or config patch naming an `:ext/…` id with no artifact
        ;; behind it, or targeting the id of a row a pruned entry carried
        stale-ids (into #{}
                        (comp (mapcat #(:patch (:data %)))
                              (remove :plugin)
                              (map :id)
                              (filter uninstalled?))
                        readable)
        doomed (into stale-ids (map row-id) (mapcat val unknowns))
        composed (into #{}
                       (comp (remove unknown-extension?)
                             (filter :plugin)
                             (map row-id))
                       (mapcat entries readable))
        dangling (into #{} (remove composed) doomed)]
    (into []
          (keep (fn [{:keys [path data]}]
                  (let [unknown-sources (into []
                                              (comp (map :plugin) (distinct))
                                              (get unknowns path))
                        gone (set unknown-sources)
                        stale (into []
                                    (comp (remove :plugin)
                                          (filter #(contains? dangling (:id %)))
                                          (map :id)
                                          (filter uninstalled?)
                                          (distinct))
                                    (:patch data))
                        sources (vec (distinct (concat unknown-sources stale)))
                        drop-row? #(contains? gone (:plugin %))
                        drop-patch? (fn [patch]
                                      (or (contains? gone (:plugin patch))
                                          (and (not (:plugin patch))
                                               (contains? dangling (:id patch)))))]
                    (when (and (or (seq sources) (seq dangling))
                               (fs/exists? path))
                      (update-layer!
                       path
                       (fn [rows patches]
                         [(into [] (remove drop-row?) rows)
                          (into [] (remove drop-patch?) patches)])))
                    (when (seq sources)
                      {:path path :sources sources})))
                readable))))

(defn- without-source
  "ROWS/PATCHES with this layer's own enablement of SOURCE removed, as
   `[rows patch]`: the extension rows and insert patches that compose it, and
   the patches on the ids they contributed — unless the global layer still
   composes that id (GLOBAL-ID-SET), where a config patch stays and applies
   to the global row instead."
  [rows patches source global-id-set]
  (let [enabling? #(enables? % source)
        enabled-ids (into #{} (comp (filter enabling?) (map row-id))
                          (concat rows patches))
        doomed (into #{} (remove #(contains? global-id-set %)) enabled-ids)]
    [(into [] (remove enabling?) rows)
     (into [] (remove #(or (enabling? %) (contains? doomed (:id %)))) patches)]))

(defn enable-global!
  "Compose SOURCE in the global layer: append its row unless the layer already
   composes it (as a row or a patch insert), and drop any remove patch that
   was carrying its disabled state (a toggle-off leaves the row, with its
   config, in place). Returns true when the file changed."
  [source]
  (update-layer!
   (cfg/global-patch-path)
   (fn [rows patches]
     (let [ids (enablement-ids rows patches source)]
       [(if (seq ids) rows (conj rows {:id source :plugin source}))
        (into [] (remove #(and (:remove %)
                               (or (= (:id %) source)
                                   (contains? ids (:id %)))))
              patches)]))))

(defn disable-global!
  "Disable SOURCE in the global layer without losing its row: a `:remove`
   patch targets the row's id, so the row (and its config) survives and a
   later enable only drops the patch. Returns true when the file changed."
  [source]
  (update-layer!
   (cfg/global-patch-path)
   (fn [rows patches]
     (let [ids (source-ids (apply-layer {} {:rows rows :patch patches}) source)
           id-set (set ids)
           removing? (fn [patch] (and (:remove patch) (contains? id-set (:id patch))))
           disabled? (fn [id] (some #(and (:remove %) (= (:id %) id)) patches))]
       (if (or (empty? ids) (every? disabled? ids))
         [rows patches]
         [rows (into [] (concat (remove removing? patches)
                                (map (fn [id] {:id id :remove true}) ids)))])))))

(defn- check-project-state!
  [state]
  (when-not (#{:inherit :load :unload} state)
    (throw (ex-info (str "unknown project state " (pr-str state))
                    {:type :kmet.app/bad-project-state :state state}))))

(defn apply-project-state!
  "Set SOURCE's project state (`:load`, `:unload` or `:inherit`) in the
   project layer. `:load` composes a row when the global layer does not
   already; `:unload` composes a remove patch for every global row of the
   source; `:inherit` drops the enablement entry (a config-only patch on an id
   the global layer still composes stays). Returns true when the file changed;
   anything but the three states is `:kmet.app/bad-project-state`."
  [source state]
  (check-project-state! state)
  (update-layer!
   (cfg/project-patch-path)
   (fn [rows patches]
     ;; the global layer is read inside the lock, so the ids a disable
     ;; targets are the ones a run would compose now
     (let [{:keys [global]} (composition (layers))
           global? (contains? (sources-of global) source)
           global-ids (source-ids global source)
           global-id-set (set global-ids)
           [rows patch] (without-source rows patches source global-id-set)
           ;; a remove patch for a composed global row is this scope's
           ;; disable state; a :config patch on that row is not, so it stays
           patch (into [] (remove (fn [p]
                                    (and (:remove p)
                                         (contains? global-id-set (:id p))))
                                  patch))]
       [rows (case state
               :load (if global?
                       patch
                       (conj patch {:id source :plugin source}))
               :unload (if (and global? (seq global-ids))
                         (into patch (map (fn [id] {:id id :remove true}) global-ids))
                         patch)
               :inherit patch)]))))

(defn apply-global-builtin-state!
  "Set built-in row ID's global state: ENABLED? false writes a `:remove`
   patch at the end of the layer, true drops the remove patches and lets the
   base composition (or the layer's own insert) supply the row again. The
   current state is read the way the run composes the layer, so a remove
   patch followed by an insert is enabled. Returns true when the file
   changed."
  [id enabled?]
  (update-layer!
   (cfg/global-patch-path)
   (fn [rows patches]
     (let [removing? #(and (:remove %) (= (:id %) id))
           present? (row-present? true {:rows rows :patch patches} id)
           kept (into [] (remove removing?) patches)]
       [rows (if (= enabled? present?)
               patches
               (if enabled?
                 kept
                 (conj kept {:id id :remove true})))]))))

(defn- without-builtin-state
  "ROWS/PATCHES with this layer's own enablement or disablement of ID
   removed. A config-only patch stays when GLOBAL? still composes ID; it has
   no row to apply to otherwise."
  [rows patches id global?]
  (let [project-row? #(= id (row-id %))
        project-patch? (fn [patch]
                         (and (= id (:id patch))
                              (or (:remove patch)
                                  (:plugin patch)
                                  (not global?))))]
    [(into [] (remove project-row?) rows)
     (into [] (remove project-patch?) patches)]))

(defn apply-project-builtin-state!
  "Set built-in row ID's project state (`:load`, `:unload` or `:inherit`) in
   the project layer. PLUGIN is the row's plugin source, used only when
   `:load` must re-insert a row the global layer removed. `:unload` removes a
   globally composed row; `:inherit` drops the project entry. Returns true
   when the file changed; anything but the three states is
   `:kmet.app/bad-project-state`."
  [id plugin state]
  (check-project-state! state)
  (update-layer!
   (cfg/project-patch-path)
   (fn [rows patches]
     ;; the global layer is read inside the lock, so the state a toggle
     ;; writes is the one a run would compose now
     (let [{:keys [global]} (layers)
           global? (row-present? true (:data global) id)
           [rows patch] (without-builtin-state rows patches id global?)]
       [rows (case state
               :load (if global?
                       patch
                       (conj patch {:id id :plugin plugin}))
               :unload (if global?
                         (conj patch {:id id :remove true})
                         patch)
               :inherit patch)]))))

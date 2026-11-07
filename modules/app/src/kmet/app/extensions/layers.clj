(ns kmet.app.extensions.layers
  "The extension plugin rows of the two ambient composition layers.

   Extensions are plugins: a run loads the ones its composition names
   (`:ext/<id>` rows) and nothing else. The two files a user edits directly
   are the global `~/.kmet/agent/patch.edn` and the project `.kmet/patch.edn`
   (see `docs/profiles.md`); this namespace reads those layers, resolves the
   extension rows they compose, and writes the row or remove patch a
   `kmet plugins` toggle needs.

   Reading is data-only: a layer is an EDN map with `:rows` and `:patch`
   collections, and nothing here loads an extension. A malformed file is
   reported as `:error` on that layer's record and composes as empty, so the
   plugin screen still opens (the run itself fails at compose with the usual
   `:kmet.app/bad-patch-file`). Writing refuses to touch a malformed file.

   The composition mirrors `kmet.app.profile/compose` for extension rows only:
   rows add, a patch with a `:plugin` adds or replaces by id, and a `:remove`
   patch drops the row with that id. Other rows and patches are ignored here;
   the run's own compose still sees them.

   A write re-renders the layer's EDN and keeps the file's leading comments;
   it runs under the EDN store's file lock, so two editors cannot lose each
   other's toggle."
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

(defn item-state
  "The composition state of SOURCE in SCOPE (`:global` or `:project`):
   `{:enabled bool :override-state :inherit|:load|:unload
   :inherited-enabled bool}`. `:inherited-enabled` is the global layer's
   state — what a project override inherits — and the project override state
   is the difference from it. The plugin screen renders both."
  [{:keys [global project]} source scope]
  (let [global? (contains? (sources-of global) source)
        project? (contains? (sources-of project) source)]
    {:inherited-enabled global?
     :enabled (if (= scope :global) global? project?)
     :override-state (if (= scope :global)
                       :inherit
                       (cond
                         (and project? (not global?)) :load
                         (and global? (not project?)) :unload
                         :else :inherit))}))

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

(defn- update-layer!
  "Read PATH, apply F to its `[rows patch]` and write the result back when
   either changed, keeping the layer's other keys and its leading comments.
   The read-modify-write runs under the EDN store's file lock. Returns true
   when the file changed."
  [path f]
  (when-let [dir (fs/parent path)]
    (fs/create-dirs dir))
  (eds/with-file-lock (str path ".lock")
    (fn []
      (let [data (read-data! path)
            rows (vec (or (:rows data) []))
            patch (vec (or (:patch data) []))
            [next-rows next-patch] (f rows patch)
            next (if (and (= rows next-rows) (= patch next-patch))
                   data
                   (assoc data :rows next-rows :patch next-patch))]
        (if (= data next)
          false
          (do (write-layer! path next)
              true))))))

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

(defn apply-project-state!
  "Set SOURCE's project state (`:load`, `:unload` or `:inherit`) in the
   project layer. `:load` composes a row when the global layer does not
   already; `:unload` composes a remove patch for every global row of the
   source; `:inherit` drops the enablement entry (a config-only patch on an id
   the global layer still composes stays). Returns true when the file changed;
   anything but the three states is `:kmet.app/bad-project-state`."
  [source state]
  (when-not (#{:inherit :load :unload} state)
    (throw (ex-info (str "unknown project state " (pr-str state))
                    {:type :kmet.app/bad-project-state :state state})))
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

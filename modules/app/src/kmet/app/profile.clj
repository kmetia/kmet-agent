(ns kmet.app.profile
  "Profiles are composition data: ordered plugin rows plus patches, layered.

   A **bundle** is a reusable row list — the analog of dsh's `dsh.bundle` —
   and a **profile** is the app entry: it stacks bundles in order, then adds
   its own rows and patches (`docs/architecture.md` § Composition). A row names
   a plugin **source** (`:plugin`) — a var, or an `:ext/<id>` for an installed
   extension (resolved before composition by `kmet.app.extensions.rows`) —
   never a keyword registry entry:
   `{:id :tool/bash :plugin kmet.app.tools.bash/plugin :config {}}`. `:id`
   is the row identity a patch targets and `--dump-config` prints; it defaults
   to the plugin's `:name`, which the catalog resolves before composition.

   A patch targets one row by `:id`:

     {:id :permissions :config {:policy :allow-all}}   ; deep-merge :config
     {:id :permissions :remove true}                   ; drop the row
     {:id :tool/ls :plugin … :after :tool/read}         ; insert (unknown id)

   Four row keys are not composition data:

   - `:mode true` marks the row `kmet.app.core` runs as the profile's mode,
     handing it the command line (`docs/architecture.md` § Composition). It travels with
     the row rather than with the profile so a patch that *replaces* the mode's
     plugin — an extension's own print mode — replaces what runs and not that it
     runs.
   - `:prompt true` says the mode takes the command line's remaining arguments
     as its input, so an empty one is a usage error before anything boots
     (print mode). A mode that reads nothing from the command line — rpc, a
     server — omits it, and a bare invocation of that profile is legal.
   - `:session-lifecycle true` says the mode opens the session itself, so
     kmet1's `-c`/`--continue`, `-r`/`--resume` and `--session <id>` are handed
     to it (the TUI row, which passes them to `interactive/run`). Every other
     mode creates its agent through `:agents`, whose factory takes no session
     option yet, and the entry point refuses those flags by name there rather
     than silently starting the fresh session the flag exists to prevent.
   - `:defer true` says a generic boot must not mount the row: only its owner
     can, once it has what the row waits for. The process world
     (`kmet.app.world`) is the one today — the entry mounts it with the
     invocation, and a diagnostic or test boot must not read the machine's
     settings. `kmet.app.runtime/boot` leaves such rows in `:deferred`, and the
     row marker travels with the row so a patch that replaces the plugin keeps
     the timing.

   Config *values* deep-merge; composition does not — a bundle's rows are
   appended as they are, and later layers patch by id. A patch that names an
   unknown id without a `:plugin` is an error, not a silent no-op.

   This namespace is data only: it never loads a plugin and never reads a
   process value. `kmet.app.catalog` turns rows into cards, `kmet.app.core`
   prints the composed result."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource
  "The committed built-in composition table (bundles + profiles)."
  "kmet/app/profiles.edn")

(def default-profile
  "The profile a bare run composes."
  :tui)

(def patch-keys
  "The keys a patch entry may carry. `:name :inject :provide` are the card
   keys an insert/replace patch gains when `kmet.app.catalog` describes it;
   the rest are the patch itself."
  #{:id :plugin :config :remove :after :before
    :name :inject :provide})

(defn layer?
  "True when X is a composition layer: a map whose only keys are `:rows` and
   `:patch`, each a sequential collection of entries (either key may be
   absent). `kmet.app.core` reads a `--patch` file with this predicate and
   `kmet.app.extensions.layers` the ambient ones, so a run and the config
   screen agree on what a readable layer is."
  [x]
  (and (map? x)
       (every? #{:rows :patch} (keys x))
       (or (nil? (:rows x)) (sequential? (:rows x)))
       (or (nil? (:patch x)) (sequential? (:patch x)))))

(defn builtin
  "The built-in composition table: `{:bundles {name bundle} :profiles {name
   profile}}`. A missing resource is a packaging bug, not an empty table."
  []
  (if-let [r (io/resource resource)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "missing built-in profile resource " resource)
                    {:type :kmet.app/missing-profiles}))))

(defn profile
  "The named PROFILE from TABLE; throws :kmet.app/unknown-profile."
  [table profile-name]
  (or (get (:profiles table) profile-name)
      (throw (ex-info (str "unknown profile: " (pr-str profile-name))
                      {:type :kmet.app/unknown-profile
                       :profile profile-name
                       :known (vec (sort (keys (:profiles table))))}))))

(defn- bundle-entry
  "Check one `:bundles` entry — `[:bundle/name layer?]` — and return its
   `[name layer]` pair."
  [entry]
  (if (and (vector? entry)
           (<= 1 (count entry) 2)
           (keyword? (first entry))
           (or (nil? (second entry)) (map? (second entry))))
    [(first entry) (second entry)]
    (throw (ex-info (str "a :bundles entry is [:bundle/name layer?]: " (pr-str entry))
                    {:type :kmet.app/bad-bundle-entry :entry entry}))))

(defn bundle
  "The named BUNDLE-NAME from TABLE; throws :kmet.app/unknown-bundle. Public
   because a bundle has two mount points: a profile stacks it at the root, a
   preset mounts it on one child ctx (`kmet.app.presets`)."
  [table bundle-name]
  (or (get (:bundles table) bundle-name)
      (throw (ex-info (str "unknown bundle: " (pr-str bundle-name))
                      {:type :kmet.app/unknown-bundle
                       :bundle bundle-name
                       :known (vec (sort (keys (:bundles table))))}))))

(defn layers
  "PROFILE-NAME's layers in application order: each bundle (followed by its
   per-stack extra layer, the `[:bundle/x {:rows … :patch …}]` second
   element), then the profile's own rows and patches."
  [table profile-name]
  (let [p (profile table profile-name)]
    (into []
          (concat
           (mapcat (fn [entry]
                     (let [[bundle-name extra] (bundle-entry entry)]
                       (cond-> [(bundle table bundle-name)]
                         (seq extra) (conj extra))))
                   (:bundles p))
           [(select-keys p [:rows :patch])]))))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn- row-index [rows id]
  (first (keep-indexed (fn [i row] (when (= (:id row) id) i)) rows)))

(defn- unknown-row [id patch]
  (throw (ex-info (str "patch targets an unknown row: " (pr-str id))
                  {:type :kmet.app/unknown-row :id id :patch patch})))

(defn- bad-patch
  "Throw :kmet.app/bad-patch about PATCH, with the entry it rejected appended."
  [patch message]
  (throw (ex-info (str message ": " (pr-str patch))
                  {:type :kmet.app/bad-patch :patch patch})))

(defn- check-patch!
  "Throw :kmet.app/bad-patch unless PATCH is a well-formed patch entry."
  [patch]
  (when-not (map? patch)
    (bad-patch patch "a patch entry must be a map"))
  (when-not (contains? patch :id)
    (bad-patch patch "a patch entry needs an :id"))
  (when-let [bad (seq (remove patch-keys (keys patch)))]
    (bad-patch patch (str "unknown patch keys " (vec bad))))
  (when-not (or (contains? patch :config) (contains? patch :plugin) (:remove patch))
    (bad-patch patch "a patch entry changes nothing"))
  (when (and (:remove patch) (contains? patch :config))
    (bad-patch patch "a :remove patch cannot also set :config"))
  (when (and (:remove patch) (contains? patch :plugin))
    (bad-patch patch "a :remove patch cannot also set :plugin"))
  (when (and (:after patch) (:before patch))
    (bad-patch patch "a patch insert takes :after or :before, not both"))
  patch)

(defn- patched-row
  "The row a source/config patch produces: its source and card replaced (an
   insert/replace patch arrives already described), its config deep-merged."
  [row patch]
  (let [row (merge row (select-keys patch [:plugin :name :inject :provide]))]
    (if-let [cfg (:config patch)]
      (update row :config #(deep-merge (or % {}) cfg))
      row)))

(defn- insert-row
  "Insert PATCH (a row with an explicit or defaulted :id) among ROWS,
   positioned by :after/:before or appended."
  [rows patch]
  (let [new-row (dissoc patch :after :before)
        anchor (or (:after patch) (:before patch))
        at (if anchor
             (if-let [i (row-index rows anchor)]
               (if (:before patch) i (inc i))
               (unknown-row anchor patch))
             (count rows))]
    (vec (concat (subvec rows 0 at) [new-row] (subvec rows at)))))

(defn- apply-patch
  "Apply PATCH to STATE (`{:rows [...] :removed #{ids}}`) and return the new
   state. Removing an id that an earlier patch removed is a no-op, so a
   project layer's disable of a row a global layer already disabled does not
   dangle; removing an id that was never there is still an error."
  [state patch]
  (let [rows (:rows state)
        removed (:removed state)
        patch (check-patch! patch)
        id (:id patch)
        idx (row-index rows id)]
    (cond
      (:remove patch)
      (cond
        idx {:rows (vec (concat (subvec rows 0 idx) (subvec rows (inc idx))))
             :removed (conj removed id)}
        (contains? removed id) state
        :else (unknown-row id patch))

      idx (do (when (or (:after patch) (:before patch))
                (bad-patch patch "a patch on an existing row takes no :after/:before"))
              {:rows (assoc rows idx (patched-row (nth rows idx) patch))
               :removed (disj removed id)})

      (contains? patch :plugin) {:rows (insert-row rows patch)
                                 :removed (disj removed id)}
      ;; a config overlay on a row an earlier patch removed has nothing to
      ;; apply; a never-present id is still a typo
      (contains? removed id) state
      :else (unknown-row id patch))))

(defn compose
  "Apply LAYERS in order to an empty composition → `{:rows [row …] :patches
   n}`. Every row must carry an `:id` (a card's, resolved by the catalog);
   duplicate ids across stacked bundles are an error. A patch targeting an
   id an earlier patch removed is a no-op (remove or config), so a project
   layer's disable of a globally disabled row does not dangle; an id that was
   never there is still an error."
  [layers]
  (let [composed (reduce (fn [state layer]
                           (reduce apply-patch
                                   {:rows (into (:rows state) (:rows layer))
                                    :removed (:removed state)}
                                   (:patch layer)))
                         {:rows [] :removed #{}}
                         layers)
        rows (:rows composed)
        patches (transduce (map #(count (:patch %))) + layers)
        missing (seq (remove :id rows))
        dupes (->> rows (map :id) frequencies (filter (fn [[_ n]] (> n 1))) (map key) sort)]
    (when missing
      (throw (ex-info "composed rows must carry an :id"
                      {:type :kmet.app/row-without-id :rows (vec missing)})))
    (when (seq dupes)
      (throw (ex-info (str "duplicate row ids: " (pr-str (vec dupes)))
                      {:type :kmet.app/duplicate-row :ids (vec dupes)})))
    {:rows rows :patches patches}))

(defn- pad [s width] (format (str "%-" width "s") s))

(defn- render-row
  "One row line: id and source aligned, then the card (name when it differs,
   inject, provide, config)."
  [id-w src-w {:keys [id plugin name inject provide config]}]
  (let [head (str (pad (pr-str id) id-w) "  " (pad (pr-str plugin) src-w) "  ")]
    (str head
         (when (not= name id) (str "name=" (pr-str name) " "))
         "inject=" (pr-str (into (sorted-map) (or inject {})))
         " provide=" (pr-str (vec (sort-by str (or provide []))))
         " config=" (pr-str (or config {})))))

(defn render
  "The `--dump-config` text for a composed row list: one line per row, ids and
   sources aligned, the card's inject/provide/config after them."
  [profile-name {:keys [rows patches]}]
  (let [id-w (reduce max 0 (map (comp count pr-str :id) rows))
        src-w (reduce max 0 (map (comp count pr-str :plugin) rows))]
    (str (str/join \newline
                   (cons (str "profile " (pr-str profile-name)
                              " rows=" (count rows) " patches=" patches)
                         (map (partial render-row id-w src-w) rows)))
         \newline)))

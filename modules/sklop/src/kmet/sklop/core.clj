(ns kmet.sklop.core
  "The composition engine: rows, bundles, profiles, patches, cards and mounts.

   A composition layer over the kernel: data in, a mounted srce subtree out.
   It knows the row language (a row names a plugin source and carries config; a
   bundle is a reusable row list; a profile stacks bundles and patches) and the
   mount contract (`srce/plugin!`), not an application's source policy or
   table. A caller supplies the policy:

   - `:describe-source` turns a row into a card (`describe`, `describe-layer`),
     which is how a var source, an `:ext/…` id or a plain map is read without
     mounting it;
   - `:resolve-source` turns a row into the source `srce/plugin!` takes
     (`mount-rows!`);
   - `:timeout-ms` is the budget for a mount's asynchronous setups.

   The language is data only: `compose` never loads a plugin and never reads a
   process value. The application supplies its own table and source policy
   (`kmet.app.profiles`, `kmet.app.catalog` and `kmet.app.runtime` are kmet's)."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]))

(def ^:private patch-keys
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

(defn profile
  "The named PROFILE from TABLE; throws :kmet.sklop/unknown-profile."
  [table profile-name]
  (or (get (:profiles table) profile-name)
      (throw (ex-info (str "unknown profile: " (pr-str profile-name))
                      {:type :kmet.sklop/unknown-profile
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
                    {:type :kmet.sklop/bad-bundle-entry :entry entry}))))

(defn bundle
  "The named BUNDLE-NAME from TABLE; throws :kmet.sklop/unknown-bundle. Public
   because a bundle has two mount points: a profile stacks it at the root, a
   preset mounts it on one child ctx (`kmet.app.presets`)."
  [table bundle-name]
  (or (get (:bundles table) bundle-name)
      (throw (ex-info (str "unknown bundle: " (pr-str bundle-name))
                      {:type :kmet.sklop/unknown-bundle
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
                  {:type :kmet.sklop/unknown-row :id id :patch patch})))

(defn- bad-patch
  "Throw :kmet.sklop/bad-patch about PATCH, with the entry it rejected appended."
  [patch message]
  (throw (ex-info (str message ": " (pr-str patch))
                  {:type :kmet.sklop/bad-patch :patch patch})))

(defn- check-patch!
  "Throw :kmet.sklop/bad-patch unless PATCH is a well-formed patch entry."
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

(defn- insert-at
  "The index PATCH inserts at among ROWS, or nil when its :after/:before
   anchor is absent. An anchor-less insert appends."
  [rows patch]
  (if-let [anchor (or (:after patch) (:before patch))]
    (when-let [i (row-index rows anchor)]
      (if (:before patch) i (inc i)))
    (count rows)))

(defn- insert-row
  "Insert PATCH (a row with an explicit or defaulted :id) among ROWS at AT."
  [rows at patch]
  (vec (concat (subvec rows 0 at) [(dissoc patch :after :before)] (subvec rows at))))

(defn- unknown-or-dropped
  "Strict composition: raise :kmet.sklop/unknown-row naming MISSING. A LAYER
   flagged `:lenient?` instead records the patch in STATE's `:dropped` —
   `{:missing missing :patch patch :path (:path layer)}` — and continues."
  [state layer missing patch]
  (if (:lenient? layer)
    (update state :dropped conj {:missing missing :patch patch :path (:path layer)})
    (unknown-row missing patch)))

(defn- apply-patch
  "Apply PATCH of LAYER to STATE (`{:rows [...] :removed #{ids} :dropped
   [...]}`) and return the new state. Removing an id that an earlier patch
   removed is a no-op, so a project layer's disable of a row a global layer
   already disabled does not dangle; removing an id that was never there is an
   error unless LAYER is lenient."
  [state layer patch]
  (let [rows (:rows state)
        removed (:removed state)
        patch (check-patch! patch)
        id (:id patch)
        idx (row-index rows id)]
    (cond
      (:remove patch)
      (cond
        idx (-> state
                (assoc :rows (vec (concat (subvec rows 0 idx) (subvec rows (inc idx)))))
                (update :removed conj id))
        (contains? removed id) state
        :else (unknown-or-dropped state layer id patch))

      idx (do (when (or (:after patch) (:before patch))
                (bad-patch patch "a patch on an existing row takes no :after/:before"))
              (-> state
                  (assoc :rows (assoc rows idx (patched-row (nth rows idx) patch)))
                  (update :removed disj id)))

      (contains? patch :plugin)
      (if-let [at (insert-at rows patch)]
        (-> state
            (assoc :rows (insert-row rows at patch))
            (update :removed disj id))
        (unknown-or-dropped state layer (or (:after patch) (:before patch)) patch))

      ;; a config overlay on a row an earlier patch removed has nothing to
      ;; apply; a never-present id is a typo (dropped when the layer is
      ;; lenient)
      (contains? removed id) state
      :else (unknown-or-dropped state layer id patch))))

(defn compose
  "Apply LAYERS in order to an empty composition → `{:rows [row …] :patches
   n :dropped [entry …]}`. Every row must carry an `:id` (a card's, resolved
   by the caller's describe policy); duplicate ids across stacked bundles are an error. A patch
   targeting an id an earlier patch removed is a no-op (remove or config), so
   a project layer's disable of a globally disabled row does not dangle; an id
   that was never there is an error.

   A layer flagged `:lenient? true` — the two ambient user layers as
   `kmet.app.core` reads them — drops a patch that names, or anchors on, a row
   that was never present instead of raising, and records it in `:dropped` as
   `{:missing missing :patch patch :path p}` (MISSING the absent row id, PATH
   the layer's own `:path`) so the entry can warn. The built-in layers and an
   explicit `--patch` file stay strict."
  [layers]
  (let [composed (reduce (fn [state layer]
                           (reduce (fn [state patch] (apply-patch state layer patch))
                                   {:rows (into (:rows state) (:rows layer))
                                    :removed (:removed state)
                                    :dropped (:dropped state)}
                                   (:patch layer)))
                         {:rows [] :removed #{} :dropped []}
                         layers)
        rows (:rows composed)
        patches (transduce (map #(count (:patch %))) + layers)
        missing (seq (remove :id rows))
        dupes (->> rows (map :id) frequencies (filter (fn [[_ n]] (> n 1))) (map key) sort)]
    (when missing
      (throw (ex-info "composed rows must carry an :id"
                      {:type :kmet.sklop/row-without-id :rows (vec missing)})))
    (when (seq dupes)
      (throw (ex-info (str "duplicate row ids: " (pr-str (vec dupes)))
                      {:type :kmet.sklop/duplicate-row :ids (vec dupes)})))
    {:rows rows :patches patches :dropped (:dropped composed)}))

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

;; ── cards ─────────────────────────────────────────────────────────────────

(defn- bad-row [row message]
  (throw (ex-info message {:type :kmet.sklop/bad-row :row row})))

(defn describe
  "The card for ROW: the row plus :id, :name, :inject and :provide.
   DESCRIBE-SOURCE is the caller's source policy: it receives ROW and returns
   the source's card (`{:name :inject :provide}`, plus :id when the policy has
   one). A row without a :plugin, or a source that does not describe as a
   card, is `:kmet.sklop/bad-row`."
  [row {:keys [describe-source]}]
  (when-not (fn? describe-source)
    (bad-row row "a card needs a :describe-source fn"))
  (when-not (:plugin row)
    (bad-row row (str "a row needs a :plugin source, got " (pr-str row))))
  (let [card (describe-source row)]
    (when-not (and (map? card) (keyword? (:name card)))
      (bad-row row (str "source " (pr-str (:plugin row))
                        " did not describe as a plugin card")))
    (let [described (merge card row)]
      (-> described
          (update :id #(or % (:id card) (:name card)))
          (update :inject srce/normalize-inject)))))

(defn describe-layer
  "Describe LAYER's rows and its insert/replace patches through
   DESCRIBE-SOURCE; config-only and remove patches pass through."
  [layer {:keys [describe-source]}]
  (let [opts {:describe-source describe-source}]
    (cond-> layer
      (:rows layer) (update :rows #(mapv (fn [row] (describe row opts)) %))
      (:patch layer) (update :patch #(mapv (fn [patch]
                                             (if (:plugin patch)
                                               (describe patch opts)
                                               patch))
                                           %)))))

;; ── mounts ────────────────────────────────────────────────────────────────

(def ^:private setup-max-rounds
  "The setup wait's round budget: a setup that starts another one is waited
   for in the next round; after this many rounds the wait returns, and the
   caller's inactive-row check reports what is still starting."
  8)

(defn- await-setups!
  "Wait for the row setups that ran asynchronously and re-settle, so the
   caller sees decided fibers. TIMEOUT-MS is the caller's budget for the whole
   wait, spent in monotonic slices; it is required so the policy stays with
   the composition. A setup that has not settled by the deadline is left as
   it is: the fiber is still :starting and `inactive-rows` names it."
  [ctx {:keys [timeout-ms]}]
  (when-not (number? timeout-ms)
    (throw (ex-info "await-setups! needs a :timeout-ms"
                    {:type :kmet.sklop/bad-mount})))
  (let [deadline (+ (concurrent/monotonic-ms) timeout-ms)]
    (loop [round 0]
      (let [handles (into [] (keep :setup) (srce/fibers ctx))]
        (when (and (seq handles) (< round setup-max-rounds))
          (concurrent/await-all handles
                                (max 0 (- deadline (concurrent/monotonic-ms))))
          (srce/settle! ctx)
          (recur (inc round))))))
  nil)

(defn mount-rows!
  "Mount ROWS under CTX and return one fiber per row, in composition order —
   mounted, settled, and with any asynchronous setup awaited against
   TIMEOUT-MS. RESOLVE-SOURCE is the caller's source policy: it receives ROW
   and returns what `srce/plugin!` takes (a var, a plugin map, or a keyword a
   resolver understands); the row's :config is passed as is. A fiber that is
   not :active is not an error here; `inactive-rows` reports it for the
   caller's policy."
  [ctx rows {:keys [resolve-source timeout-ms]}]
  (when-not (fn? resolve-source)
    (throw (ex-info "mount-rows! needs a :resolve-source fn"
                    {:type :kmet.sklop/bad-mount :rows (count rows)})))
  (when-not (number? timeout-ms)
    (throw (ex-info "mount-rows! needs a :timeout-ms"
                    {:type :kmet.sklop/bad-mount :rows (count rows)})))
  (let [fibers (mapv (fn [row]
                       (srce/plugin! ctx (resolve-source row) (:config row)))
                     rows)]
    (srce/settle! ctx)
    (await-setups! ctx {:timeout-ms timeout-ms})
    fibers))

(defn inactive-rows
  "ROWS whose fiber is not :active after the mounts settled, as plain data:
   what a failed mount is reported in terms of."
  [rows fibers]
  (into []
        (keep (fn [[row fiber]]
                (let [{:keys [state error waiting-for]} (srce/fiber-info fiber)]
                  (when-not (= :active state)
                    {:id (:id row) :state state :error error
                     :waiting-for (vec (sort waiting-for))}))))
        (map vector rows fibers)))

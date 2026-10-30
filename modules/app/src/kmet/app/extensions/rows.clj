(ns kmet.app.extensions.rows
  "The `:ext/<id>` row source: name an installed extension from a composition,
   and let the row own its load.

   An extension is already a plugin (`kmet.extension.md`); a row does not change
   that. It adds a **name** a composition can address and the row's `:config`,
   which the host carries to the extension's api map as `(:config api)`. The
   artifact still loads through the host's isolated context and mounts under
   the host ctx; the row owns its lifetime, so disposing the row unloads the
   extension.

   Resolution reads the same installed set the world loader reads
   (`kmet.app.packages/resolve-configured-packages`): top-level settings paths,
   the auto dirs, packages and bundled artifacts. It loads nothing, so
   `--dump-config` can name an extension without running it. The id is the
   manifest `:name`, or a single file's basename without `.clj`; the first
   match in pi precedence wins, so a user's copy shadows a bundled one.

   A row must mount after the `:extensions` host row (the first row of
   `:bundle/minimal`): loading an extension needs the host's service binding."
  (:require [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.app.extensions.context :as context]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.srce.core :as srce]))

(def ^:private prefix ":ext/")

(defn source?
  "True when X is an `:ext/<id>` row source."
  [x]
  (and (keyword? x) (str/starts-with? (str x) prefix)))

(defn- id-of
  "The extension id SOURCE (`:ext/<id>`) names."
  [source]
  (subs (str source) (count prefix)))

(defn- entry-id
  "The id an installed ITEM answers to, from its resolved artifact: the
   bundled display name, the manifest `:name`, or a single file's stem."
  [item resolved]
  (or (get-in item [:metadata :display-name])
      (let [name (:name resolved)]
        (if (= :file (:kind resolved))
          (str/replace name #"\.clj$" "")
          name))))

(defn- resolve-item
  "The artifact ITEM names, as `resolve-extension`/`-descriptor` answers it."
  [item]
  (if-let [descriptor (get-in item [:metadata :artifact])]
    (context/resolve-extension-descriptor descriptor)
    (context/resolve-extension (:path item))))

(defn- resolve-id
  "The installed extension named ID, or nil. An artifact that cannot be
   resolved is skipped: naming another extension must not depend on it, and the
   world load reports it where it belongs."
  [id]
  (some (fn [item]
          (try
            (let [resolved (resolve-item item)]
              (when (= id (entry-id item resolved))
                {:id id :item item}))
            (catch Exception e
              (debug/log "extension row: cannot resolve " (:path item) ": "
                         (ex-message e))
              nil)))
        (:extensions (packages/resolve-configured-packages))))

(defn entry
  "The installed extension SOURCE names, or nil: `{:id :item}`."
  [source]
  (when (source? source)
    (resolve-id (id-of source))))

(defn item-source
  "The `:ext/<id>` source ITEM (a resolved package item) answers to, or nil
   when its manifest cannot be read. The id rules are the resolver's: the
   bundled display name, the manifest `:name`, or a single file's basename
   without `.clj`."
  [item]
  (try
    (when-let [id (entry-id item (resolve-item item))]
      (keyword "ext" id))
    (catch Exception e
      (debug/log "extension item: cannot resolve " (:path item) ": "
                 (ex-message e))
      nil)))

(defn- unknown!
  [source]
  (throw (ex-info (str "unknown extension " (pr-str source)
                       " — no installed extension has that name")
                  {:type :kmet.app/unknown-extension :extension source})))

(defn- entry!
  "ENTRY for SOURCE, or `:kmet.app/unknown-extension`."
  [source]
  (or (entry source) (unknown! source)))

(defn card
  "The catalog card for an `:ext/<id>` ROW. Throws
   `:kmet.app/unknown-extension` before anything boots, like an unknown plugin
   var."
  [row]
  (let [source (:plugin row)]
    (entry! source)
    (merge {:name source} row {:id (or (:id row) source)})))

;; Extensions owned by mounted composition rows: `{id {:entry :config :record}}`.
;; A row's disposal unloads whatever record is current, so a `/reload` that
;; replaced the record is still owned by the row.
(defonce ^:private mounted (atom {}))

(defn- load-entry!
  "Load ENTRY's artifact with CONFIG on the extension api; throw
   `:kmet.app/extension-load` when it fails or is skipped. Returns the
   extension record the row must unload."
  [entry config]
  (let [{:keys [id item]} entry
        descriptor (get-in item [:metadata :artifact])
        result (if descriptor
                 (extensions/load-extension-descriptor! descriptor config)
                 (extensions/load-extension! (:path item) config))]
    (when (or (:error result) (:skipped result))
      (throw (ex-info (str "extension " (pr-str id) " did not load: "
                           (or (:error result)
                               (str "skipped " (name (:reason result)))))
                      {:type :kmet.app/extension-load
                       :extension id
                       :result result})))
    (or (extensions/loaded-record (:extension result))
        (throw (ex-info (str "extension " (pr-str id) " loaded without a record")
                        {:type :kmet.app/extension-load
                         :extension id
                         :result result})))))

(defn- mount!
  "Load ENTRY with CONFIG and record it as the row's current extension."
  [id entry config]
  (let [record (load-entry! entry config)]
    (swap! mounted assoc id {:entry entry :config config :record record})
    record))

(defn- registered?
  "True when RECORD is still in the extension registry. A wholesale
   `clear-extensions!` unloads records the row still has an entry for, so
   the entry's record can be stale."
  [record]
  (some #(identical? record %) (extensions/registered-extensions)))

(defn- unmount!
  "Forget ID's row extension and unload its current record (unless a
   wholesale reset already unloaded it)."
  [id]
  (let [old (get @mounted id)]
    (swap! mounted dissoc id)
    (when-let [record (:record old)]
      (when (registered? record)
        (extensions/unload-extension! record)))))

(defn- reload-one!
  "Unmount ID's current record and mount its ENTRY again with the same
   CONFIG; the result map `/reload` reports for it. A failure is reported on
   stderr and answers with the row id, since there is no loaded record to
   name.

   Waits for the outgoing record's release first: an extension whose `shutdown`
   handed back a promise frees its code asynchronously
   (`kmet.app.extensions.core/unload-extension!`), and mounting over a
   half-released instance is the one thing a reload must not do."
  [id {:keys [entry config]}]
  (when-let [released (unmount! id)]
    (extensions/await-release! (str "extension " id) released))
  (try
    (let [record (mount! id entry config)]
      {:extension (:name record) :error nil})
    (catch Exception e
      (let [message (or (ex-message e) (str e))]
        (binding [*out* *err*]
          (println "Warning: Failed to reload extension" id ":" message))
        {:extension id :error message}))))

(defn reload-all!
  "Unload and load again every extension a mounted composition row owns,
   keeping the row's config. Returns the per-extension result maps `/reload`
   reports (`{:extension name :error nil}` on success, `{:extension id
   :error MSG}` when the load fails); the rows themselves stay mounted and
   dispose the reloaded record."
  []
  (mapv (fn [[id row]] (reload-one! id row)) @mounted))

(defn plugin
  "The composition plugin for an `:ext/<id>` row: load the extension when the
   row mounts, and unload it when the row disposes. The row's `:config` reaches
   the extension as `(:config api)`. Throws `:kmet.app/unknown-extension`
   before the row is mounted."
  [source]
  (let [{:keys [id] :as entry} (entry! source)]
    {:name (keyword "ext-row" id) ; distinct from the extension's own plugin
     :apply (fn [ctx config]
              (mount! id entry config)
              (srce/effect ctx
                           (fn [] (fn [] (unmount! id)))
                           (str "unload extension " id))
              nil)}))

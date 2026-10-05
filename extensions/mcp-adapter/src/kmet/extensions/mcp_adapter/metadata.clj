(ns kmet.extensions.mcp-adapter.metadata
  "Persistent MCP metadata cache (§8 of the design contract — pi:
   metadata-cache.ts, adapted to EDN).

   Path: <agent-dir>/mcp-cache.edn (the host agent dir, KMET_CODING_AGENT_DIR-
   aware)
   Shape: {:version 1
           :servers {name {:config-fingerprint str
                           :fetched-at ms
                           :protocol-era {:era :modern|:legacy :version rev}
                           :tools [{:name :description :inputSchema}]
                           :prompts [{:name :description :arguments}]
                           :resources [{:name :uri :description :mimeType}]
                           :resource-templates
                           [{:name :uriTemplate :description :mimeType}]}}}

   :protocol-era is the era the recorded connect actually used; absent
   means an unknown era (probe): a fresh entry, or one written before
   3.7 — no version bump.

   Freshness: 7 days. server-entry returns nil when stale or the config
   fingerprint mismatches — callers fall back to a live connect. A config
   change (command/args/url/disabled/direct-tools/tool-prefix or the
   relevant settings) invalidates cached metadata.

   Writes merge with the existing file and go through temp-file + rename
   (atomic-ish); save-cache! serializes concurrent writers (a background
   list_changed resync and a foreground connect) with one process-wide
   lock."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [kmet.extensions.mcp-adapter.config :as config]))

(defn- read-text
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn- write-text
  [path text]
  (spit path (str text)))

(def ^:private cache-version 1)
(def ^:private max-age-ms (* 7 24 60 60 1000))

(def ^:private save-lock
  "Serializes save-cache! (one process): a background list_changed resync
   and a foreground connect can both refresh the cache, and the
   read-merge-write plus the shared temp path would otherwise lose an
   entry or race the rename."
  (Object.))

(defn cache-path
  "The metadata cache file (<agent-dir>/mcp-cache.edn; the host agent dir
   — KMET_CODING_AGENT_DIR-aware)."
  []
  (str (fs/path (config/agent-dir) "mcp-cache.edn")))

(defn- read-edn
  "Read an EDN map from PATH; nil when missing or unparsable."
  [path]
  (try
    (when (fs/exists? path)
      (let [raw (edn/read-string {:default (fn [_ _] nil)} (read-text path))]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn load-cache
  "Load the cache map {:version 1 :servers {...}} or nil when missing /
   unparsable / wrong version."
  []
  (let [raw (read-edn (cache-path))]
    (when (and raw (= cache-version (:version raw)) (map? (:servers raw)))
      raw)))

(defn save-cache!
  "Merge ENTRY-MAP {:servers {name entry}} into the existing cache file and
   write atomically (temp file + rename). Serialized: concurrent
   refreshes (a background list_changed resync and a connect) must not
   interleave the read-merge-write or the temp file."
  [entry-map]
  (locking save-lock
    (let [path (cache-path)
          existing (load-cache)
          merged {:version cache-version
                  :servers (merge (:servers existing) (:servers entry-map))}
          tmp (str path ".tmp")]
      (fs/create-dirs (fs/parent path))
      (write-text tmp (pr-str merged))
      (fs/move tmp path {:replace-existing true})
      nil)))

(defn config-fingerprint
  "Fingerprint of the config bits that affect which tools/prompts/
   resources a server exposes (§8): the server name, :command/:args/:url/
   :disabled/:direct-tools/:tool-prefix/:include-tools/:exclude-tools/
   :expose-resources, and the relevant settings. A config change
   invalidates cached metadata."
  [name definition settings]
  (pr-str [name
           (select-keys definition [:command :args :url :disabled
                                    :direct-tools :tool-prefix
                                    :include-tools :exclude-tools
                                    :expose-resources])
           (select-keys settings [:direct-tools :tool-prefix
                                  :disable-proxy-tool])]))

(defn server-entry
  "The cached tools entry for a server, or nil when missing / stale (7-day
   freshness) / fingerprint mismatch — callers fall back to a live
   connect. The entry also carries :protocol-era when a connect recorded
   one (:era/:version), the hint a later connect passes to skip era
   detection."
  [cache name definition settings]
  (when cache
    (let [entry (get-in cache [:servers name])]
      (when (and entry
                 (= (config-fingerprint name definition settings)
                    (:config-fingerprint entry))
                 (number? (:fetched-at entry))
                 (< (- (System/currentTimeMillis) (:fetched-at entry))
                    max-age-ms))
        entry))))

(defn update-entry!
  "Persist a fresh entry for a server (also returned). OPTS:
   :tools/:prompts/:resources/:resource-templates are the wire lists
   (nil is treated as empty; prompts keep :name/:description/:arguments,
   resources :name/:uri/:description/:mimeType, resource templates
   :uriTemplate/:name/:description/:mimeType); :protocol-era — the
   {:era :modern|:legacy :version rev} the connect actually used, stored
   for the next connect's hint (absent = unknown; the next connect
   probes)."
  [cache name definition settings & {:keys [tools prompts resources resource-templates
                                            protocol-era]}]
  (let [entry (cond-> {:config-fingerprint (config-fingerprint name definition settings)
                       :fetched-at (System/currentTimeMillis)
                       :tools (vec (mapv (fn [t]
                                           (select-keys t [:name :description :inputSchema]))
                                         (or tools [])))
                       :prompts (vec (mapv (fn [p]
                                             (select-keys p [:name :description :arguments]))
                                           (or prompts [])))
                       :resources (vec (mapv (fn [r]
                                               (select-keys r [:name :uri :description :mimeType]))
                                             (or resources [])))
                       :resource-templates
                       (vec (mapv (fn [t]
                                    (select-keys t [:uriTemplate :name :description :mimeType]))
                                  (or resource-templates [])))}
                protocol-era (assoc :protocol-era protocol-era))]
    (save-cache! {:servers {name entry}})
    (assoc-in (or cache {:version cache-version :servers {}})
              [:servers name] entry)))

(defn all-tools
  "Every cached tool across servers with fresh, non-disabled entries:
   [{:server str :tool {:name ... :description ... :inputSchema ...}}]."
  [cache config settings]
  (when cache
    (for [[name definition] (:mcp-servers config)
          :let [entry (server-entry cache name definition settings)]
          :when (and entry (not (true? (:disabled definition))))
          tool (:tools entry)]
      {:server name :tool tool})))

(defn all-prompts
  "Every cached prompt across servers with fresh, non-disabled entries:
   [{:server str :prompt {:name ... :description ... :arguments ...}}] —
   used by /mcp prompts and the panel's prompt count."
  [cache config settings]
  (when cache
    (for [[name definition] (:mcp-servers config)
          :let [entry (server-entry cache name definition settings)]
          :when (and entry (not (true? (:disabled definition))))
          prompt (:prompts entry)]
      {:server name :prompt prompt})))

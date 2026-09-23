(ns kmet.extensions.lsp-adapter.runtime
  "Connection ownership for the lsp-adapter extension (plan §10, Rev 2
   shape): conns keyed by [server-name, root], lazy connect under a per-
   server lock, sticky broken-set (a failed claim is never retried until an
   explicit reset — no time-based backoff), full-text document sync with
   disk re-read per touch, fan-out across claiming conns.

   Broken reasons: :not-installed (fs/which pre-check) and :spawn-failed
   (spawn or handshake error, stderr tail attached). Session switches touch
   nothing here — servers are workspace-scoped; /reload rebuilds."
  (:require [babashka.fs :as fs]
            [kmet.libs.jsonrpc :as jrpc]
            [kmet.libs.process :as process]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.lsp :as lsp]))

(def request-timeout-ms 30000)
(def initialize-timeout-ms 60000)
(def idle-timeout-minutes 15)

;; ─── State ────────────────────────────────────────────────────────────────

(defn new-state
  "One state MAP per extension instance whose mutable fields are atoms:
   CONNS keyed [name root-str], BROKEN {name {:reason :at}}, CONFIG, LOCKS
   created on demand under swap. ON-CHANGE (optional, set by the entry)
   is invoked with no args whenever anything footer-visible mutates -
   runtime can't reach the entry namespace, so the entry injects its
   status refresher here."
  [api config]
  {:api api
   :config (atom config)
   :conns (atom {})
   :broken (atom {})
   :locks (atom {})
   :reaper-stop (atom false)
   :on-change (atom nil)})

(defn- notify-change! [st]
  (when-let [f @(:on-change st)]
    (try (f) (catch Exception _ nil))))

(defn set-on-change! [st f] (reset! (:on-change st) f))

(defn set-config!
  [st config]
  (reset! (:config st) config)
  (notify-change! st))
(defn config [st] @(:config st))
(defn configured-servers
  "The :servers map from the active config - the input effective-servers
   expects (NOT the whole config map; passing that would hide all custom
   servers while builtins kept working)."
  [st]
  (get-in (config st) [:servers]))

(defn- setting
  "Effective SETTING-KEY: per-server entry override wins, then the
   settings block, then the built-in default."
  [st name k default]
  (or (get-in (config st) [:servers name k])
      (get-in (config st) [:settings k])
      default))

(defn- lock-for
  "Per-server monitor object, created race-safely under swap."
  [st name]
  (let [m (swap! (:locks st)
                 (fn [locks]
                   (cond-> locks (not (contains? locks name))
                           (assoc name (Object.)))))]
    (get m name)))

;; ─── Sticky broken-set ────────────────────────────────────────────────────

(defn mark-broken! [st name reason]
  (swap! (:broken st) assoc name {:reason reason :at (System/currentTimeMillis)})
  (notify-change! st))

(defn clear-broken! [st name]
  (swap! (:broken st) dissoc name)
  (notify-change! st))

(defn clear-all-broken! [st]
  (reset! (:broken st) {})
  (notify-change! st))
(defn broken [st name] (@(:broken st) name))

(defn broken-error
  "The ex-info thrown to callers when a server sits in the broken set —
   the cached failure returns instantly, no spawn attempt."
  [name {:keys [reason at]}]
  (ex-info (format "%s: %s (failed at %s — /lsp restart to retry)"
                   name reason
                   (.toString (java.time.Instant/ofEpochMilli at)))
           {:type ::server-broken :server name :reason reason}))

;; ─── Connections ─────────────────────────────────────────────────────────

(defn- diag-callback
  "publishDiagnostics notifications land in the owning conn's store,
   keyed by uri with a receive timestamp (freshness checks for Phase 2)."
  [diags]
  (fn [{:keys [params]}]
    (when-let [uri (:uri params)]
      (swap! diags assoc uri
             {:diagnostics (:diagnostics params [])
              :version (get-in params [:version])
              :received-at (System/currentTimeMillis)}))))

(defn ensure-conn!
  "Locked, idempotent connect for NAME/DESC at ROOT. Returns the live conn;
   throws (and marks broken) on which-miss, spawn or handshake failure.
   A failed claim stays in the broken set — queries return the cached
   failure instantly until restart/refresh/reload clears it."
  [st name desc root]
  (let [key [name (str root)]
        wl (lock-for st name)]
    (locking wl
      (or (get @(:conns st) key)
          (if-let [b (broken st name)]
            (throw (broken-error name b))
            (try
              (let [argv (:command desc)]
                (when-not (and (pos? (count argv)) (fs/which (first argv)))
                  (mark-broken! st name ":not installed")
                  (throw (ex-info "not installed"
                                  {:type ::server-broken :server name})))
                (let [docs (atom {})
                      diags (atom {})
                      conn (lsp/start!
                            {:command argv
                             :env (:env desc)
                             ;; run the server rooted where it's attached
                             :cwd (str root)
                             :on-notification
                             (fn [msg]
                               (case (:method msg)
                                 "textDocument/publishDiagnostics"
                                 ((diag-callback diags) msg)
                                 ;; log/showMessage et al land in stderr tail
                                 nil))
                             :on-request (lsp/make-on-request)
                             :pid nil}
                            root
                            (:initialization-options desc)
                            (setting st name :initialize-timeout-ms initialize-timeout-ms))
                      entry {:client conn
                             :name name
                             :root root
                             :docs docs
                             :diags diags}]
                  (when-let [p (jrpc/pid conn)] (process/track-pid! p))
                  (clear-broken! st name)
                  (swap! (:conns st) assoc key entry)
                  (notify-change! st)
                  entry))
              (catch Exception e
                (when-not (::server-broken (:type (ex-data e)))
                  (mark-broken! st name (str ":spawn failed — "
                                             (or (ex-message e) (str e)))))
                (throw e))))))))

(defn all-conns [st] (vals @(:conns st)))

(defn disconnect!
  "Graceful close of one conn and removal from the map. Safe on missing
   keys and already-dead transports (close! cleanup always runs)."
  [st key]
  (when-let [entry (get @(:conns st) key)]
    (swap! (:conns st) dissoc key)
    (notify-change! st)
    (try
      (jrpc/close! (:client entry) {:graceful lsp/shutdown-dance})
      (catch Exception _ nil))
    true))

(defn disconnect-server!
  "Disconnect every root of SERVER-NAME."
  [st name]
  (let [keys (filter (fn [[n _]] (= n name)) (keys @(:conns st)))]
    (doseq [k keys] (disconnect! st k))
    (pos? (count keys))))

(defn shutdown-all!
  [st]
  (doseq [k (keys @(:conns st))] (disconnect! st k)))

;; ─── Document sync ────────────────────────────────────────────────────────

(defn touch-file!
  "Sync PATH into the server's view: didOpen on first touch, full-text
   didChange after. Disk re-read per touch means kmet's own edits are
   picked up without watching the filesystem. Throws when the file has
   vanished."
  [conn path language-id]
  (let [text (slurp path)
        ver (-> (swap! (:docs conn)
                       (fn [docs]
                         (assoc docs path (inc (get docs path 0)))))
                (get path))]
    (if (= ver 1)
      (jrpc/notify! (:client conn) "textDocument/didOpen"
                    (lsp/did-open path language-id text))
      (jrpc/notify! (:client conn) "textDocument/didChange"
                    (lsp/did-change-full path ver text)))
    ver))

(defn open-docs [conn] (keys @(:docs conn)))
(defn last-used [conn] (jrpc/last-used (:client conn)))
(defn alive? [conn] (jrpc/alive? (:client conn)))
(defn diagnostics-for [conn] @(:diags conn))

;; ── Claiming & fan-out ─────────────────────────────────────────────────

(defn claiming-specs
  "[{:id :desc :root}] for PATH over the effective registry."
  [st path]
  (detect/claiming (detect/effective-servers (configured-servers st))
                   path (str (fs/cwd))))

(defn broken-claiming
  "Claiming servers currently sitting in the broken set — surfaced as
   instant errors alongside live results."
  [st path]
  (for [{:keys [id] :as spec} (claiming-specs st path)
        :when (broken st id)]
    (assoc spec :error (broken-error id (broken st id)))))

(defn for-file
  "Touch + F(conn) on every claiming conn, connecting lazily. Returns
   {:results [{:name :root :value}] :errors [{:name :message}]}. Per-server
   errors never abort siblings; a file nobody claims yields both lists
   empty."
  [st path f]
  (let [lang (detect/language-id path)
        specs (claiming-specs st path)
        out (for [{:keys [id desc root]} specs]
              ;; a broken server surfaces its CACHED failure every time -
              ;; that instant report is the point of the sticky set
              (if-let [b (broken st id)]
                {:error true :name id :message (:reason b)}
                (try
                  (let [conn (ensure-conn! st id desc root)]
                    (touch-file! conn path lang)
                    {:name id :root (str root) :value (f conn)})
                  (catch Exception e
                    {:error true
                     :name id
                     :message (or (ex-message e) (str e))}))))]
    (reduce (fn [acc {:keys [error name message root value]}]
              (cond
                error (update acc :errors conj {:name name :message message})
                :else (update acc :results conj {:name name :root root :value value})))
            {:results [] :errors []}
            out)))

(defn reaping?
  "True when CONN's server should be disconnected now (idle past its
   timeout). Never reaps mid-handshake: a conn only exists post-initialize."
  [st {:keys [name] :as conn}]
  (let [desc (some (fn [d] (when (= (:id d) name) d))
                   (detect/effective-servers (configured-servers st)))
        min* (setting st name :idle-timeout-minutes idle-timeout-minutes)
        idle-ms (- (System/currentTimeMillis) (last-used conn))]
    (and (pos? min*)
         (> idle-ms (* min* 60 1000))
         (not= :keep-alive (:lifecycle desc)))))

(defn reap-tick!
  "Disconnect idle conns. Best-effort per conn; runs on the reaper daemon."
  [st]
  (doseq [conn (all-conns st)
          :when (reaping? st conn)]
    (try (disconnect! st [(:name conn) (str (:root conn))])
         (catch Exception _ nil))))

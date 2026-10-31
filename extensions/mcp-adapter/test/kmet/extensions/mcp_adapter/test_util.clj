(ns kmet.extensions.mcp-adapter.test-util
  "Shared fixtures for the mcp-adapter suites: a temp agent dir (the global
   config, the metadata cache and the token store all live under it), the
   project-config redirect, a state builder that seeds the cache from
   fixtures, and the fake-server spawner the ^:slow suites use.

   Nothing here spawns a process except spawn-server!/stop-server!, which
   the subprocess suites call from their own ^:slow tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.libs.mcp.client :as mcp-client]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.tui.core :as core]))

;; ─── Paths ────────────────────────────────────────────────────────────────

(def ^:private this-file
  "This file's path below the extension root."
  "test/kmet/extensions/mcp_adapter/test_util.clj")

(defn- extension-root
  "The extension root that holds THIS-FILE, found from the file's own
   location where the host reports a full path (babashka, the JVM — five
   parents up), else from the checkout: Jolt answers `*file*` relative to the
   namespace's own source root, so its parents are no way back to the
   extension. The search covers a run from an extension's own directory (its
   `bb test`) and one from the repo root (the repo runner)."
  []
  (let [candidates (concat (take 8 (iterate fs/parent (fs/absolutize (or *file* this-file))))
                           [(fs/cwd)]
                           (when (fs/directory? "extensions") (fs/list-dir "extensions")))]
    (or (some->> candidates
                 (filter #(fs/exists? (fs/path % this-file)))
                 first
                 fs/absolutize
                 str)
        (throw (ex-info (str "cannot locate the extension root for " this-file)
                        {:type ::no-extension-root})))))

(def extension-dir
  "The extension root, derived from this file's own location
   (<ext>/test/kmet/extensions/mcp_adapter/test_util.clj)."
  (extension-root))

(defn fixture
  "Absolute path to a fake-server fixture under scripts/."
  [name]
  (str (fs/path extension-dir "scripts" name)))

(defn temp-dir!
  "A fresh temp dir. TMPDIR first (Termux has no /tmp), then the JVM
   tmpdir; the repo is never touched."
  [prefix]
  (let [root (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
        dir (str (fs/path root (str "kmet-" prefix "-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(defn delete-file!
  "Remove PATH, ignoring a missing file."
  [path]
  (try (fs/delete path) (catch Exception _ nil)))

(defn delete-tree!
  "Remove DIR, ignoring a missing tree."
  [dir]
  (try (fs/delete-tree dir) (catch Exception _ nil)))

;; ─── Config + cache redirection ───────────────────────────────────────────

(defn with-agent-dir
  "Run F with the agent dir (global config, metadata cache, token store)
   and the project config redirected into a fresh temp dir. F receives
   {:agent dir :project path}."
  [f]
  (let [dir (temp-dir! "mcp-agent")
        project (str (fs/path dir "project-mcp.edn"))]
    (try
      (with-redefs [config/agent-dir (fn [] dir)
                    config/project-config-path (fn [& _] project)]
        (f {:agent dir :project project}))
      (finally (delete-tree! dir)))))

(defn write-edn!
  "Write VALUE as EDN to PATH, creating the parent directory."
  [path value]
  (fs/create-dirs (fs/parent path))
  (spit path (str (pr-str value) "\n"))
  path)

(defn read-edn-file
  "Read PATH as EDN (nil when missing)."
  [path]
  (when (fs/exists? path)
    (read-string (slurp path))))

;; ─── State builders ───────────────────────────────────────────────────────

(defn- runtime-cells
  "The per-server runtime cells the views deref (:conn :error
   :failed-at)."
  [names]
  (into {}
        (map (fn [name]
               [name {:conn (atom nil)
                      :error (atom nil)
                      :failed-at (atom nil)}]))
        names))

(defn cached-state
  "A state map for the read-only views (status/search/catalog/prompts):
   each server's entry is written to the metadata cache first, so
   catalog/cached-tools finds it fresh. CONFIG is {:settings ..
   :mcp-servers {name definition}}; CACHE-TOOLS is {name {:tools [...]
   :prompts [...] :protocol-era {...} ..}."
  [config cache-tools settings]
  (let [cache (reduce-kv (fn [acc name entry]
                           (apply metadata/update-entry!
                                  acc name (get-in config [:mcp-servers name]) settings
                                  (mapcat identity entry)))
                         nil
                         cache-tools)]
    {:config config
     :cache cache
     :tool-names {}
     :servers (runtime-cells (keys (:mcp-servers config)))}))

(defn relay-state
  "A state map with definitions and no cache — the views that must degrade
   when nothing has been cached yet."
  [config]
  {:config config
   :cache nil
   :tool-names {}
   :servers (runtime-cells (keys (:mcp-servers config)))})

;; ─── Rendering ────────────────────────────────────────────────────────────

(def ^:private ansi
  #"\x1b\[[0-9;]*m")

(defn strip
  "S with its ANSI SGR sequences removed."
  [s]
  (str/replace (str s) ansi ""))

(defn render-lines
  "The component's rendered lines at WIDTH, ANSI stripped."
  [comp width]
  (mapv strip (core/render comp width)))

(defn all-same-width?
  "True when every rendered line has the same visible width (borders
   aligned)."
  [lines]
  (= 1 (count (distinct (map count lines)))))

;; ─── Fake servers (^:slow suites) ─────────────────────────────────────────

(def ^:private start-timeout-ms 10000)

(defn spawn-server!
  "Start a bb fixture server; returns {:proc :port :out-file}. The fixture
   prints 'PORT <n>' on stdout, captured to a temp file so this can poll
   while the process keeps running."
  [script & args]
  (let [out-file (str (temp-dir! "mcp-fake") "/server.out")
        p (proc/process (into ["bb" (fixture script)] args)
                        {:in :discard :out out-file :err :discard})]
    (loop [waited 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (if (< waited start-timeout-ms)
            (do (Thread/sleep 100) (recur (+ waited 100)))
            (do
              (try (proc/destroy-tree p) (catch Exception _ nil))
              (throw (ex-info (str "fixture did not start: " script " — " out)
                              {:type ::server-start-failed})))))))))

(defn stop-server!
  "Kill a fixture started by spawn-server! and remove its output file."
  [{:keys [proc out-file]}]
  (when proc
    (try (proc/destroy-tree proc) (catch Exception _ nil)))
  (when out-file
    (io/delete-file (io/file out-file) true)))

(defn server-output
  "Everything the fixture has printed so far (empty when unreadable)."
  [server]
  (try (slurp (:out-file server)) (catch Exception _ "")))

(defn wait-until
  "Poll PRED every 100ms up to TIMEOUT-MS."
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (loop [waited 0]
     (cond
       (pred) true
       (< waited timeout-ms) (do (Thread/sleep 100)
                                 (recur (+ waited 100)))
       :else false))))

;; ─── Lifecycle helpers (^:slow suites) ────────────────────────────────────
;; One try/finally per fixture, written once: the subprocess suites stay
;; flat, and a failing assertion can never leak a server process.

(defmacro with-server
  "Bind SYM to a spawned fixture server for BODY, killing it afterwards."
  [[sym script] & body]
  `(let [~sym (spawn-server! ~script)]
     (try
       ~@body
       (finally (stop-server! ~sym)))))

(defmacro with-connect
  "Bind SYM to the (client/connect! DEFINITION) result for BODY, closing
   the connection afterwards."
  [[sym definition] & body]
  `(with-connect-opts ~definition {}
     (fn [~sym]
       ~@body)))

(defn with-connect-opts
  "Call F with the (client/connect! DEFINITION OPTS) result, closing the
   connection afterwards. The function form takes an opts map without
   bending the binding-vector shape with-connect keeps for linting."
  [definition opts f]
  (let [result (mcp-client/connect! definition opts)]
    (try
      (f result)
      (finally (mcp-client/close! (:conn result))))))


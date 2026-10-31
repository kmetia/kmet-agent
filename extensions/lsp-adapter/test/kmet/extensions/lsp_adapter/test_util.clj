(ns kmet.extensions.lsp-adapter.test-util
  "Shared fixtures for the lsp-adapter suites: the extension root, temp
   dirs, a config/state builder, an ANSI-stripping renderer, the fake
   server path and its spawner."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.extensions.lsp-adapter.runtime :as runtime]))

(def ^:private this-file
  "This file's path below the extension root."
  "test/kmet/extensions/lsp_adapter/test_util.clj")

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
   (<ext>/test/kmet/extensions/lsp_adapter/test_util.clj)."
  (extension-root))

(defn fixture
  "Absolute path to a fixture under scripts/."
  [name]
  (str (fs/path extension-dir "scripts" name)))

(defn temp-dir!
  "A fresh temp dir. TMPDIR first (Termux has no /tmp), then the JVM
   tmpdir; the repo is never touched."
  [prefix]
  (let [root (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
        dir (str (fs/path root (str "kmet-lsp-" prefix "-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(defn delete-tree!
  [dir]
  (try (fs/delete-tree dir) (catch Exception _ nil)))

(def ^:private ansi
  #"\x1b\[[0-9;]*m")

(defn strip [s] (str/replace (str s) ansi ""))

(defn render-lines
  "The component's rendered lines at WIDTH, ANSI stripped."
  [comp width]
  (mapv strip (core/render comp width)))

;; ─── State ────────────────────────────────────────────────────────────────

(defn new-state
  "A runtime state over CFG (:servers / :settings), optionally with the
   given conns [{:name :root ...}] present in the map."
  ([cfg] (new-state cfg []))
  ([cfg conns]
   (let [st (runtime/new-state nil cfg)]
     (when (seq conns)
       (reset! (:conns st)
               (into {} (map (fn [c] [[(:name c) (str (:root c))] c])) conns)))
     st)))

(defn fake-entry
  "A config entry for the fake LSP server fixture (claims .txt files, root
   marked by root-marker.txt). Extra args are appended to the command."
  [& args]
  {:command (into ["bb" (fixture "fake-lsp-server.bb")] args)
   :extensions ["txt"]
   :root-markers ["root-marker.txt"]})

;; ─── Fake server (^:slow suites) ──────────────────────────────────────────

(defn spawn-server!
  "Start the HTTP-ish fixture that prints 'PORT <n>' (the OAuth-style
   helpers). The LSP fake is spawned by the runtime, not by tests."
  [script & args]
  (let [out-file (str (temp-dir! "fake") "/server.out")
        p (proc/process (into ["bb" (fixture script)] args)
                        {:in :discard :out out-file :err :discard})]
    (loop [waited 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (if (< waited 10000)
            (do (Thread/sleep 100) (recur (+ waited 100)))
            (do (try (proc/destroy-tree p) (catch Exception _ nil))
                (throw (ex-info (str "fixture did not start: " script)
                                {:type ::server-start-failed})))))))))

(defn stop-server! [{:keys [proc out-file]}]
  (when proc (try (proc/destroy-tree proc) (catch Exception _ nil)))
  (when out-file (io/delete-file (io/file out-file) true)))

(defn wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (loop [waited 0]
     (cond
       (pred) true
       (< waited timeout-ms) (do (Thread/sleep 100) (recur (+ waited 100)))
       :else false))))

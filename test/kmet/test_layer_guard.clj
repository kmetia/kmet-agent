(ns kmet.test-layer-guard
  "Guard (app layer boundary): the app's business logic (`kmet.app.*`, the
   `kmet/ui` front-end subtree excluded) must not require the terminal/Tk front
   ends or the interactive mode, and the front-end layers
   (`kmet.app.modes.**`, `kmet.ui.**`) drive the engine only through
   `kmet.app.session.runtime`.

   The `ui` module merged into `app`, so the split is a path rule here rather
   than a module rank: both trees live under `modules/app/src`. Its subject is
   the app source tree, not one module's tests, so it lives beside the other
   cross-cutting guards. One more rule is global: no module source may require
   the retired `kmet.app.session.driver` — it was deleted with the handle
   migration, and the name must not come back."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tasks.modules :as kmod]
            [kmet.tasks.source-reader :as sr]))

(defn- slash
  "PATH with the platform separator normalized to / — the guards strip and
   compare path fragments, which must not depend on the host separators."
  [path]
  (str/replace (str path) "\\" "/"))

(defn- source-files-under
  "Every Clojure source file under DIR, path-normalized and sorted. A missing
   DIR yields nothing instead of throwing: the callers assert which roots they
   expect files from (`<root> not scanned`), so a renamed or emptied tree fails
   by name rather than as a NoSuchFileException from the walk."
  [dir]
  (when (fs/exists? dir)
    (letfn [(walk [d]
              (mapcat (fn [f]
                        (if (fs/directory? f) (walk f) [f]))
                      (fs/list-dir d)))]
      (->> (walk dir)
           (map slash)
           (filter #(re-find #"\.clj[ca]?$" %))
           (sort)))))

(defn- app-source-files
  "The app's business logic: every source file under the business tree, minus
   the mode rows (the front-end layer the rules below constrain separately).
   The UI subtree is not under this root — it is `modules/app/src/kmet/ui`."
  []
  (->> (source-files-under "modules/app/src/kmet/app")
       (remove #(str/includes? % "/modes/"))))

(defn- ns-form [path]
  (sr/read-ns-form path "layer guard"))

(defn- required-kmet [path]
  (when-let [form (ns-form path)]
    (when (and (seq? form) (= 'ns (first form)))
      (->> (for [clause (rest form)
                 :when (and (seq? clause) (#{:require :require-macros} (first clause)))
                 spec (rest clause)]
             (cond
               (symbol? spec) spec
               (vector? spec) (first spec)
               (seq? spec) (first spec)))
           (map str)
           (filter #(str/starts-with? % "kmet."))
           distinct))))

(defn- path->ns
  "The namespace named by a source file path under a module's src tree."
  [path]
  (-> path
      (str/replace #"^modules/[^/]+/src/" "")
      (str/replace #"\.clj[ca]?$" "")
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(defn- mode-source-files
  "Every mode namespace file: the mode rows, which are what a front end is."
  []
  (source-files-under "modules/app/src/kmet/app/modes"))

(def ^:private ui-roots
  "Where the app-UI layer's files are, inside the app module since the `ui`
   module merged: the `kmet.ui.*` subtree."
  ["modules/app/src/kmet/ui"])

(defn- ui-source-files
  "Every app-UI namespace file. One layer, so every rule below applies to all
   of it: the rank guard sees no module edge inside `app`, so a `ui` file
   reaching `kmet.app.loop` is this rule's job."
  []
  (sort (mapcat source-files-under ui-roots)))

(defn- ui-mode-files
  "The front ends that live under `ui`: the TUI's provider/mode row
   (`kmet.ui.tui`) and its interactive cluster. They are modes — they own a run
   and drive the engine — so the widget-scoped `:ui` rules do not bind them."
  []
  (filter #(or (= "modules/app/src/kmet/ui/tui.clj" %)
               (str/starts-with? % "modules/app/src/kmet/ui/tui/interactive"))
          (ui-source-files)))

(defn- front-end-source-files
  "Every mode and app-UI namespace file (the front-end layers)."
  []
  (sort (concat (mode-source-files) (ui-source-files))))

(def ^:private mode-prefixes
  "The modes' namespace prefixes: the rows live in this module but are front
   ends, so a business file reaching one is the same inversion as reaching the
   TUI."
  ["kmet.app.modes."])

(deftest app-layer-does-not-require-front-ends
  (testing "no app business namespace reaches the TUI/Tk/UI layers or a mode"
    (doseq [f (app-source-files)
            :let [ns-name (path->ns f)
                  deps (required-kmet f)
                  forbidden (filter (fn [d]
                                      (or (str/starts-with? d "kmet.tui.")
                                          (str/starts-with? d "kmet.tk.")
                                          (str/starts-with? d "kmet.ui.")
                                          (some #(str/starts-with? d %) mode-prefixes)))
                                    deps)]]
      (is (empty? forbidden)
          (str ns-name " requires forbidden namespaces: " (vec (sort forbidden)))))))
(def ^:private front-end-forbidden
  "Prefixes a front end must not require, and the files the rule binds — the
   scope is the point:

   - `:front-ends` (every mode and UI file): the loop driver and its stages, and
     the compaction stage. A front end reaches the engine through the session
     runtime, not the machinery under it (the pure `kmet.app.compaction.core`
     helpers are fine — the footer reads tokens).
   - `:ui` (the app-UI widgets alone): the retired session engine's name. It
     started work — prompts, turns, session switching, disposal — and a mode
     owns a run and may drive it, while a widget reads the engine instead
     (`kmet.app.session.runtime`). The TUI mode entry and its interactive
     cluster are front ends that *are* a mode: they keep the `:front-ends` rule
     and this one skips them (`ui-mode-files`)."
  [{:scope :front-ends
    :prefixes ["kmet.app.loop" "kmet.app.compaction.stage"]
    :why "front ends reach the engine through the session runtime"}
   {:scope :ui
    :prefixes ["kmet.app.session.driver"]
    :why "the UI layer reads the engine; the driver starts work, and a mode owns a run"}])

(deftest the-retired-session-engine-is-not-required
  (testing "kmet.app.session.driver was retired with the handle migration;
            the production require must not come back"
    (let [files (->> (kmod/modules)
                     (mapcat (fn [{:keys [src]}] (source-files-under src)))
                     sort)
          offenders (for [f files
                          :when (some #{"kmet.app.session.driver"} (required-kmet f))]
                      f)]
      (is (seq files) "no module sources scanned — check the manifest")
      (is (empty? offenders)
          (str "src namespaces require the retired engine: " (vec offenders))))))

(deftest front-ends-drive-the-runtime-not-the-loop
  (testing "modes and app UI reach the engine through the session runtime"
    (let [files (front-end-source-files)]
      (is (seq files) "no front-end sources scanned — check the walk roots")
      (is (some #(str/starts-with? % "modules/app/src/kmet/app/modes/") files)
          "the mode rows are scanned")
      ;; every UI root must contribute a file, named by the root: a root that
      ;; stops matching (moved file, renamed dir) would otherwise narrow the
      ;; scan silently, and the rules below would pass on a smaller set
      (doseq [root ui-roots]
        (is (some #(str/starts-with? % root) files) (str root " not scanned")))
      (doseq [{:keys [scope prefixes why]} front-end-forbidden
              f (if (= :ui scope) (remove (set (ui-mode-files)) (ui-source-files)) files)
              :let [forbidden (filter (fn [d] (some #(str/starts-with? d %) prefixes))
                                      (required-kmet f))]]
        (is (empty? forbidden)
            (str f " requires " (vec forbidden) " — " why))))))

(deftest app-ui-does-not-require-entry-modes
  (testing "app UI components never reach up into a mode — the UI layer sits
            below the modes, so it takes its data from the caller or the
            runtime, never from a mode row or an entry mode"
    (let [files (ui-source-files)]
      (is (seq files) "no app UI sources scanned — check the walk roots")
      (doseq [f files
              :let [ns-name (path->ns f)
                    forbidden (filter #(some (fn [p] (str/starts-with? % p)) mode-prefixes)
                                      (required-kmet f))]]
        (is (empty? forbidden)
            (str ns-name " requires " (vec forbidden)))))))

(def ^:private loop-module-edges
  "The only allowed `kmet.app.loop.*` edges; a subset check, so the map can
   name modules that do not exist yet."
  {"kmet.app.loop.message" #{}
   "kmet.app.loop.state" #{"kmet.app.loop.message"}
   "kmet.app.loop.completion" #{"kmet.app.loop.state" "kmet.app.loop.message"}
   "kmet.app.loop.tool-exec" #{"kmet.app.loop.state" "kmet.app.loop.message"}
   "kmet.app.loop" #{"kmet.app.loop.message" "kmet.app.loop.state"
                     "kmet.app.loop.completion" "kmet.app.loop.tool-exec"
                     "kmet.app.loop.stages"}})

(defn- loop-source-files
  "The loop driver and its submodules."
  []
  (->> (app-source-files)
       (filter #(or (= "loop.clj" (fs/file-name %))
                    (str/includes? % "/app/loop/")))
       ;; the kmet2 run machinery beside them (`driver`, `stages`) is not a
       ;; loop submodule: it is the engine a front end drives
       (remove #(contains? #{"driver.clj" "stages.clj"} (fs/file-name %)))
       sort))

(deftest loop-modules-form-a-dag
  (testing "the loop submodules require only earlier modules (no cycles)"
    (let [files (loop-source-files)]
      (is (seq files) "no loop sources scanned — check the walk roots")
      (doseq [f files
              :let [ns-name (path->ns f)]]
        (is (contains? loop-module-edges ns-name)
            (str "no DAG entry for " ns-name))
        (doseq [dep (required-kmet f)
                :let [dep (str dep)]
                :when (str/starts-with? dep "kmet.app.loop")
                :when (not (contains? #{"kmet.app.loop-guard"
                                        "kmet.app.retry"} dep))]
          (is (contains? (get loop-module-edges ns-name #{}) dep)
              (str ns-name " may not require " dep)))))))

(deftest the-loop-entry-point-is-pinned
  (testing "kmet.app.loop exposes only the driver entry points"
    ;; ns-publics needs the namespace loaded: a filtered test run loads only
    ;; the requested namespaces, so require it here instead of relying on
    ;; another test namespace having pulled it in.
    (require 'kmet.app.loop)
    (is (= '#{make-agent-state run-agent-turn cancel-turn}
           (set (keys (ns-publics 'kmet.app.loop)))))))



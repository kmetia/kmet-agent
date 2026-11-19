(ns kmet.test-process-state
  "Guard: every process-global `defonce` and dynamic var is a closed,
   documented list.

   The ctx tree is the default owner. A `defonce` the process owns for its
   lifetime, or a dynamic var the loop binds per run, is an exception: it must
   appear here, so a new one is a deliberate decision rather than a quiet
   regression. Stale entries fail too: a removed exception leaves the list."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.source-reader :as sr]))

(def ^:private roots
  "The modules that share the ctx tree."
  ["modules/srce/src" "modules/sklop/src" "modules/app/src"])

(def ^:private allowed
  "Process-state exceptions: :process is a defonce owned for the process's
   lifetime (the root, registry and extension host are one owner at a time;
   caches and one-shot reports outlive a run), and :dynamic is bound per run by
   the loop. A ctx service cannot own either."
  {:process
   #{"kmet.app.debug/debug-enabled"
     "kmet.app.debug/write-failure-reported?"
     "kmet.app.debug/write-lock"
     "kmet.app.extensions.context/jars-cache"
     "kmet.app.extensions.context/shared-context-cache"
     "kmet.app.extensions.core/cli-flag-values"
     "kmet.app.extensions.core/context-sink-atom"
     "kmet.app.extensions.core/entry-sink-atom"
     "kmet.app.extensions.core/extension-binding"
     "kmet.app.extensions.core/extensions"
     "kmet.app.extensions.core/hook-seq"
     "kmet.app.extensions.core/services*"
     "kmet.app.extensions.core/session-atom"
     "kmet.app.extensions.rows/mounted"
     "kmet.app.modes.server/ask-route"
     "kmet.app.registry/nobody-published"
     "kmet.app.registry/process-handle"
     "kmet.app.resources/warned-project-lists"
     "kmet.app.root/app-root"
     "kmet.app.session.store/report-wrong-shape"
     "kmet.app.tools.mutation/tails"
     "kmet.app.tools.script/bridge-queue"
     "kmet.ui.tui.interactive/live-mode"
     "kmet.ui.tui.interactive/pending-answers"}
   :dynamic
   #{"kmet.app.extensions.core/*agent-context*"
     "kmet.app.loop.state/*run-token*"
     "kmet.app.tools.bash/*cancel-signal*"
     "kmet.app.tools.bash/*session-env-fn*"
     "kmet.app.tools.bash/*shell*"
     "kmet.app.tools.read/*skill-resolver*"
     "kmet.app.tools.script/*assistant-message*"
     "kmet.app.tools.script/*call-state*"
     "kmet.app.tools.script/*enabled-tools-fn*"
     "kmet.app.tools.script/*tool-check*"
     "kmet.app.tools.script/*tool-hook*"
     "kmet.app.tools.search/*run*"
     "kmet.app.tools.util/*cwd*"
     "kmet.srce.ctx/*dispose-failures*"
     "kmet.srce.ctx/*dispose-undoing*"}})

(def ^:private allowed-names
  (into #{} (mapcat val) allowed))

(defn- source-files
  "Every Clojure source file under the guarded roots."
  []
  (->> roots
       (mapcat #(fs/glob % "**/*.{clj,cljc}"))
       (map str)
       sort))

(defn- process-binding?
  "True when top-level FORM is a defonce or a dynamic var."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (or (= 'defonce (first form))
           (and (= 'def (first form))
                (true? (:dynamic (meta (second form))))))))

(defn- bindings-in
  "The qualified names of process bindings in PATH, both reader views."
  [path]
  (reduce
   (fn [found features]
     (let [forms (sr/read-forms path features "process-state guard")
           ns-sym (-> forms first second)]
       (when-not (symbol? ns-sym)
         (throw (ex-info (str "process-state guard cannot read the ns form: " path)
                         {:type ::bad-ns :path path})))
       (into found
             (keep (fn [form]
                     (when (process-binding? form)
                       (str ns-sym "/" (name (second form)))))
                   forms))))
   #{}
   sr/reader-views))

(deftest the-guard-sees-the-trees
  (is (every? fs/directory? roots) "every guarded root exists")
  (is (seq (source-files)) "the guard found source files"))

(deftest every-process-binding-is-listed
  (let [found (reduce (fn [acc f] (into acc (bindings-in f)))
                      #{}
                      (source-files))
        unexpected (sort (set/difference found allowed-names))
        stale (sort (set/difference allowed-names found))]
    (testing "the guard sees the state it covers"
      (is (seq found) "no process bindings found — check the scan roots"))
    (testing "a new atom or dynamic var is an explicit exception"
      (is (empty? unexpected)
          (str "new process state needs an entry in test-process-state: "
               (vec unexpected))))
    (testing "a removed exception leaves the list"
      (is (empty? stale)
          (str "process-state entries that no longer exist: " (vec stale))))))
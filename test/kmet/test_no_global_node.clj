(ns kmet.test-no-global-node
  "Guard: the shipped modules never reach for a process-global store.

   A session, a UI provider or a mode is a child context in the ctx tree
   (`docs/architecture.md`), not a registration on one process node. The store that did
   that (`kmet.libs.scope`) and the shim that resolved *the* root
   (`kmet.agent.app-runtime`) are deleted, so their names are tripwires here:
   a name that no longer exists is exactly what a reintroduction has to type.
   The ban is by name, in the modules that share the ctx tree — the kernel,
   the app and the front ends. Only the process-state edges are listed, not a
   prefix."

  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.tasks.changed :as changed]
            [kmet.tasks.source-reader :as reader]))

(def ^:private new-module-srcs
  "The modules that share the ctx tree."
  ["modules/srce/src" "modules/app/src"])

(def ^:private store-namespaces
  "kmet1's process-global state: the root-ctx resolver and the retired store."
  '#{kmet.agent.app-runtime kmet.agent.runtime kmet.libs.scope})

(defn- sources
  "Every source file in the existing kmet2 modules."
  []
  (->> new-module-srcs
       (filter #(fs/directory? %))
       (mapcat #(fs/glob % "**/*.{clj,cljc}"))
       (map str)
       sort))

(deftest the-guard-sees-the-new-modules
  (is (every? fs/directory? new-module-srcs)
      "every scanned module tree exists")
  (is (seq (sources)) "the guard found the kmet2 sources"))

(deftest no-new-module-requires-the-process-store
  (doseq [file (sources)
          :let [ns-form (reader/read-ns-form file "no-global-node guard")
                requires (if ns-form (changed/ns-requires ns-form) #{})
                store (filter store-namespaces requires)]]
    (is (some? ns-form) (str file " has no ns form — the guard cannot read it"))
    (is (empty? store)
        (str file " requires kmet1's process store " (vec store)
             " — kmet2 code takes its ctx"))))

(deftest no-new-module-calls-the-root-node
  (doseq [file (sources)]
    (is (not (str/includes? (slurp file) "runtime/node"))
        (str file " mentions runtime/node — the store is not a ctx"))))

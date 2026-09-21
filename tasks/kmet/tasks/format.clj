(ns kmet.tasks.format
  "Format tasks (bb format / bb format-check / the *-changed variants) over
   cljfmt — the same code path on both hosts. cljfmt is a tooling dep on
   both classpaths (bb.edn :deps for babashka, deps.edn for jolt, where
   org.clojure/spec.alpha and the rewrite-clj pin are declared too: jolt's
   resolver drops cljfmt's org.clojure/clojure dep, and the pin matches the
   rewrite-clj babashka bundles so both hosts parse identically — see
   deps.edn).
   Nothing here wraps the library: failures propagate as themselves.

   Throughput note: jolt formats at ~1.6 s/file to babashka's ~0.12 s (the
   interpreter runs rewrite-clj), so a full-tree jolt run is minutes — the
   changed-file task (`jolt format-check-changed`) and babashka remain the
   practical gates for the whole tree."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.tasks.changed :as changed]))

(defn- cljfmt-op
  "cljfmt.tool's FIX or CHECK fn. Loaded lazily — the format tasks are its
   only consumers, so no other task (or test) pays the load."
  [op]
  (require 'cljfmt.tool)
  (or (some-> (resolve (symbol "cljfmt.tool" (name op))) deref)
      (throw (ex-info (str "cljfmt.tool has no " (name op) " fn")
                      {:type ::missing-cljfmt-op :op op}))))

(defn- run-cljfmt!
  "Run cljfmt OP (:fix/:check) over PATHS. cljfmt's own report exits
   non-zero for unformatted files in :check, so the gates keep their
   semantics."
  [op paths]
  ((cljfmt-op op) {:paths (vec paths)}))

(defn- source-paths
  "Every cljfmt-managed file: src/test/tasks/extensions, minus build output
   (target/) and the generated provider catalogs ([image_]model_data/ —
   their exact bytes are sha256-manifested and owned by the model
   generators, not cljfmt)."
  []
  (->> ["src" "test" "tasks" "extensions"]
       (mapcat (fn [root] (fs/glob root "**.{clj,cljs,cljc,cljd,bb,edn,jolt}")))
       ;; the exclusion matches / — fs/glob yields the platform separator
       ;; (backslash on Windows), which would let the generated catalogs
       ;; through to cljfmt
       (remove (fn [path] (re-find #"/target/|/(image_)?model_data/"
                                   (str/replace (str path) "\\" "/"))))
       (mapv str)))

(defn format!
  "Fix formatting (bb format / jolt format). ARGS: specific files/dirs, else
   the whole managed tree."
  [args]
  (run-cljfmt! :fix (if (seq args) args (source-paths))))

(defn format-check!
  "Verify formatting (bb format-check / jolt format-check). ARGS as format!."
  [args]
  (run-cljfmt! :check (if (seq args) args (source-paths))))

(defn format-changed!
  "Fix formatting of the files changed since the last commit (bb
   format-changed)."
  []
  (let [paths (changed/changed-clj-files)]
    (if (seq paths)
      (run-cljfmt! :fix paths)
      (println "No changed files to format."))))

(defn format-check-changed!
  "Verify formatting of the files changed since the last commit (bb
   format-check-changed)."
  []
  (let [paths (changed/changed-clj-files)]
    (if (seq paths)
      (run-cljfmt! :check paths)
      (println "No changed files to check."))))

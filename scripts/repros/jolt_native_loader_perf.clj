(ns jolt-native-loader-perf
  "Repro for jolt#1653: the native loader recompiles context-private source
   namespaces on every load.

   Run under Jolt:
     jolt scripts/repros/jolt_native_loader_perf.clj

   Three fresh loaders compile the same 20-file source tree; each load takes
   roughly the same time instead of reusing the previous compilation."
  (:require [babashka.fs :as fs]
            [jolt.loader :as jl]))

(def ^:private files 20)
(def ^:private defns-per-file 100)

(defn- ms [t0] (/ (- (System/nanoTime) t0) 1e6))

(defn- body [i]
  (apply str
         (for [j (range defns-per-file)]
           (str "(defn f" j " [x] (let [y (inc x)]"
                " (when (pos? y) (* y " (inc i) "))))\n"))))

(defn- source [i]
  (str "(ns probe.ext.ns" i
       (when (pos? i)
         (str " (:require [probe.ext.ns" (dec i) "])"))
       ")\n"
       (body i)))

(let [root (str (fs/create-temp-dir))
      sources (mapv source (range files))]
  (doseq [[i text] (map-indexed vector sources)]
    (let [path (fs/path root "probe" "ext" (str "ns" i ".clj"))]
      (fs/create-dirs (fs/parent path))
      (spit (str path) text)))
  (println (format "%d source files, %.1f KB under %s"
                   files (/ (reduce + (map count sources)) 1024.0) root))
  (dotimes [_ 3]
    (let [l (jl/classpath [root])
          t0 (System/nanoTime)]
      (jl/load l {:kind :ns :name (str "probe.ext.ns" (dec files))})
      (println (format "fresh native loader load: %.1f ms" (ms t0)))
      (jl/unload! l))))

(ns kmet.app.bundled-extensions
  "Bundled extension artifacts — the shipped `extensions/` set, resolved in
   whichever form the current run can reach them (extension-bundle.md).

   `artifacts` answers one descriptor per manifest entry:

     {:name \"clojure\" :kind :dir           :root \"/repo/extensions/clojure/src\"}
     {:name \"tools\"   :kind :file          :root \"/repo/extensions/tools.clj\"}
     {:name \"clojure\" :kind :resource-dir  :prefix \"extensions/clojure/src\"}
     {:name \"tools\"   :kind :resource-file :path \"extensions/tools.clj\"}

   In a source checkout the manifest resource answers a `file:` URL (both
   hosts, verified on jolt dev) and the repo root derives from that URL —
   four `fs/parent` steps up — so the artifacts are the real `extensions/`
   files and load exactly as any directory/file extension does. In a built
   artifact (bb uberjar / jolt embed) the manifest is a resource and the
   artifact files are staged under the `extensions/` resource
   prefix, so the descriptors carry that prefix instead.

   This namespace is pure discovery: the manifest, the descriptors and the
   pure conversion helpers. Enablement (`:bundled-extensions` settings) and
   the PackageItem construction live in kmet.app.packages with the other
   toggle code, and the loading of resource descriptors in
   kmet.app.extensions. Nothing here throws: a missing or malformed
   manifest resolves to no artifacts plus a debug-log note (a hand-made
   build without the staging embed must still start)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kmet.debug :as debug]))

(def ^:private manifest-resource
  "Classpath key of the committed manifest (inside src/, so every mode has
   it: checkout classpath, bb uberjar, jolt embed)."
  "kmet/bundled-extensions/manifest.edn")

(def ^:private resource-prefix
  "The staging prefix the packagers land bundled artifacts under (see
   kmet.tasks.build/stage-bundled-extensions!)."
  "extensions")

(defn- valid-entry?
  [e]
  (and (map? e)
       (string? (:name e))
       (contains? #{:dir :file} (:kind e))
       (string? (:root e))))

(defn- valid-manifest?
  [m]
  (and (map? m)
       (vector? (:artifacts m))
       (every? valid-entry? (:artifacts m))
       (or (nil? (:exclude m))
           (and (vector? (:exclude m))
                (every? string? (:exclude m))))))

(defn- manifest-source
  "Where the manifest can be read from, or nil: the classpath resource
   (URL or filesystem path), else the checkout under the process cwd (a
   jolt run whose io/resource does not answer file URLs still has the
   sources on disk)."
  []
  (or (io/resource manifest-resource)
      (let [f (fs/path (fs/cwd) "src" "kmet" "bundled-extensions" "manifest.edn")]
        (when (fs/exists? f) (str f)))))

(defn manifest
  "The parsed bundled-extensions manifest, or nil when it is missing or
   malformed (never throws — a broken bundle must not keep the app from
   starting). Malformed input is recorded in the debug log."
  []
  (try
    (if-let [src (manifest-source)]
      (let [m (edn/read-string (slurp src))]
        (if (valid-manifest? m)
          m
          (do (debug/log "bundled extensions: malformed manifest at " (str src))
              nil)))
      (do (debug/log "bundled extensions: no manifest resource (" manifest-resource ")")
          nil))
    (catch Exception e
      (debug/log "bundled extensions: manifest unreadable: " e)
      nil)))

(defn- excluded?
  [manifest root]
  (boolean (some #(= % root) (:exclude manifest))))

(defn- repo-root
  "The repo root when F is the manifest file of a source checkout, else
   nil: four parent steps up (manifest.edn → bundled-extensions → kmet →
   src → repo) and an `extensions/` directory to prove it."
  [f]
  (try
    (let [repo (fs/parent (fs/parent (fs/parent (fs/parent (io/file f)))))]
      (when (and repo
                 (fs/exists? (fs/path repo "extensions")))
        (str repo)))
    (catch Exception _ nil)))

(defn- cwd-root
  "The checkout root under the process cwd, or nil."
  []
  (let [cwd (fs/cwd)]
    (when (and (fs/exists? (fs/path cwd "extensions"))
               (fs/exists? (fs/path cwd "src" "kmet" "bundled-extensions"
                                    "manifest.edn")))
      (str cwd))))

(defn checkout-root
  "The repo root when the manifest is reachable as a checkout file, else
   nil: derived from the manifest's own `file:` URL, or — when the resource
   is not file-based (a built artifact) — the checkout under the process
   cwd."
  []
  (let [u (io/resource manifest-resource)]
    (if (and u (= "file" (.getProtocol u)))
      (repo-root u)
      (cwd-root))))

(defn- checkout-descriptor
  "One checkout descriptor, or nil when the artifact root is missing or
   does not look like an extension artifact."
  [repo {:keys [name kind root]}]
  (let [p (fs/path repo "extensions" root)
        path (str (fs/absolutize p))]
    (if (= kind :dir)
      (when (fs/exists? (fs/path p "extension.edn"))
        {:name name :kind :dir :root path :path path :bundled? true})
      (when (fs/regular-file? p)
        {:name name :kind :file :root path :path path :bundled? true}))))

(defn checkout-descriptors
  "Descriptors for MANIFEST in a source checkout rooted at REPO: every
   `:root` resolves under REPO/extensions/. A root that is missing or does
   not look like an extension artifact is skipped with a debug note — the
   bundle gate (bb check-bundled-extensions) is where that fails loudly,
   not the runtime. Pure (no io/resource); test seam."
  [manifest repo]
  (into []
        (keep (fn [entry]
                (when-not (excluded? manifest (:root entry))
                  (let [d (checkout-descriptor repo entry)]
                    (when-not d
                      (debug/log "bundled extensions: missing artifact root "
                                 (str (fs/path repo "extensions" (:root entry)))))
                    d))))
        (:artifacts manifest)))

(defn- resource-descriptor
  "One artifact-mode descriptor, or nil when the resource probe misses."
  [{:keys [name kind root]}]
  (let [key (str resource-prefix "/" root)]
    (if (= kind :dir)
      (when (io/resource (str key "/extension.edn"))
        {:name name :kind :resource-dir :prefix key :path key :bundled? true})
      (when (io/resource key)
        {:name name :kind :resource-file :path key :bundled? true}))))

(defn resource-descriptors
  "Descriptors for MANIFEST in artifact mode: each artifact is probed
   through io/resource under the extensions/ prefix. A miss is
   skipped with a debug note (a hand-made build without the staging embed).
   Pure apart from io/resource; test seam."
  [manifest]
  (into []
        (keep (fn [entry]
                (when-not (excluded? manifest (:root entry))
                  (let [d (resource-descriptor entry)]
                    (when-not d
                      (debug/log "bundled extensions: resource missing for " (:name entry)))
                    d))))
        (:artifacts manifest)))

(defn artifacts
  "Descriptors for every bundled extension artifact in the current run
   mode: checkout paths when the manifest answers a file URL (dev runs),
   resource prefixes otherwise (bb uberjar / jolt embed). [] when the
   manifest is missing or malformed."
  []
  (if-let [m (manifest)]
    (if-let [repo (checkout-root)]
      (checkout-descriptors m repo)
      (resource-descriptors m))
    []))

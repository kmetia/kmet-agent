(ns kmet.libs.archive
  "Zip extraction shared by the build packager (kmet.tasks.build), extensions
   (e.g. the tree-sitter CLI download) and extension artifact loading.
   Host-evaluated with full Java interop and shared by reference, so
   extension SCI contexts — where instance methods on JDK inner classes
   such as ZipFile$ZipFileInflaterInputStream are not callable — get zip
   support through one audited zip-slip guard instead of reimplementing it.

   Portable: extraction runs on java.util.zip, which both hosts provide
   (babashka bundles the JDK's; Jolt's runtime implements it on the zlib
   every binary links)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- entry-target
  "Canonicalized extraction target for zip entry RAW under DEST, or throws
   ::zip-slip when it would land outside DEST. Zip entries may use \\ as a
   separator (the spec allows both); names are normalized to / first so
   Windows-style ..\\evil escapes are caught on every platform
   (canonicalize only resolves /-separated .. on unix, where \\ is a plain
   filename char)."
  [dest raw]
  (let [rel (str/replace (str raw) "\\" "/")
        out (when-not (or (str/blank? rel) (str/starts-with? rel "/"))
              (fs/canonicalize (fs/path dest rel) {:nofollow-links true}))]
    ;; canonicalize resolves ".." lexically, so the containment check is
    ;; effective (fs/starts-with? takes path prefix)
    (when (or (nil? out) (= out dest) (not (fs/starts-with? out dest)))
      (throw (ex-info (str "zip entry escapes target dir: " raw)
                      {:type ::zip-slip :entry raw})))
    out))

(defn extract-zip!
  "Extract every file entry of zip-path under dest-dir, creating nested
   dirs as needed. Returns the seq of extracted paths (no permission
   preservation — callers chmod as needed)."
  [zip-path dest-dir]
  (let [dest (fs/canonicalize dest-dir {:nofollow-links true})]
    (fs/create-dirs dest)
    (with-open [zf (java.util.zip.ZipFile. (fs/file zip-path))]
      (doall
       (for [entry (enumeration-seq (.entries zf))
             :when (not (.isDirectory entry))]
         (let [out (entry-target dest (.getName entry))]
           (fs/create-dirs (fs/parent out))
           (with-open [in (.getInputStream zf entry)
                       out-stream (io/output-stream (fs/file out))]
             (io/copy in out-stream))
           out))))))

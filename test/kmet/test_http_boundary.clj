(ns kmet.test-http-boundary
  "Strict guard for the outbound-HTTP boundary (http.md §7, phase 2):
   every production/script/extension/test namespace must route outbound
   HTTP through kmet.libs.http. Only kmet.libs.http itself may require
   babashka.http-client or spawn curl; the retired kmet.libs.proxy /
   kmet.ai.proxy namespaces are deleted, so any require of them (or of
   babashka.http-client) fails the build — preventing the abstraction
   from eroding later."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(def ^:private build-dir-names
  "Directories the walk never descends into. Their .clj files are build
   output or test residue (extension target/ fixtures, AOT output), so
   scanning them would make the guard's file set depend on whatever ran in
   the checkout before — and a stale fixture could trip the checks."
  #{"target" "dist" "node_modules" ".cpcache" ".jolt" ".git"})

(defn- source-files
  "Every .clj/.cljc file under the given ROOTS (default: src, scripts,
   extensions, test), at any depth — a recursive walk, not `fs/glob` with
   `**/`: the glob does not match files directly in a base dir, so it was
   silently skipping the scripts/ top level and `extensions/*.clj`, and it
   only looked at .clj, missing every .cljc (codemode, context, the win
   clipboard shim). Build output and cache dirs are skipped
   (build-dir-names)."
  ([] (source-files ["src" "scripts" "extensions" "test"]))
  ([roots]
   (letfn [(walk [dir]
             (mapcat (fn [f]
                       (when-not (and (fs/directory? f)
                                      (build-dir-names (fs/file-name f)))
                         (if (fs/directory? f) (walk f) [f])))
                     (fs/list-dir dir)))]
     (->> roots
          (mapcat #(when (fs/directory? %) (walk %)))
          (map str)
          (filter #(re-find #"\.clj[ca]?$" %))
          (sort)))))

(defn- ns-sym [path]
  (try
    (with-open [rdr (java.io.PushbackReader. (java.io.InputStreamReader.
                                              (java.io.FileInputStream. path)))]
      (let [form (read rdr)]
        (when (and (list? form) (= 'ns (first form)))
          (second form))))
    (catch Exception _ nil)))

(defn- required-libs
  "The library symbols in a file's ns require clauses."
  [path]
  (let [content (slurp path)
        ns-block (re-find #"(?s)\(ns\s+[\w.-]+(?:\s+.*?)?\)" content)]
    (when ns-block
      (->> (re-seq #"\[([\w.-]+)(?:\s+:as\s+\w+)?\]" ns-block)
           (map second)
           (map symbol)))))

(defn- offending-requires
  "The forbidden libs required by a file: babashka.http-client (only
   kmet.libs.http may use it) and the deleted kmet.*.proxy namespaces.
   A vector, so a failure message lists the namespaces instead of printing
   the lazy seq's default toString."
  [path]
  (let [n (ns-sym path)]
    (when-not (= n 'kmet.libs.http)
      (->> (required-libs path)
           (filter #(or (= % 'babashka.http-client)
                        (= % 'kmet.libs.proxy)
                        (= % 'kmet.ai.proxy)))
           (vec)))))

(defn- spawns-curl?
  "True when the file invokes curl directly (outside kmet.libs.http):
   curl as an argv head (`[\"curl\" ...]`) or the start of a shell string
   (`curl -sS ...`). A bare mention of the word (prose, test data) is not a
   spawn."
  [path]
  (and (not= (ns-sym path) 'kmet.libs.http)
       (let [content (slurp path)]
         (boolean (or (re-find #"\[\s*\"curl\"" content)
                      (re-find #"\"curl\s" content))))))

(deftest http-boundary-strict
  (doseq [path (source-files)]
    (let [bad (offending-requires path)]
      (is (empty? bad)
          (str path " requires " bad
               " — outbound HTTP must go through kmet.libs.http")))
    (is (not (spawns-curl? path))
        (str path " invokes curl directly — only kmet.libs.http may spawn curl"))))

(deftest source-files-skips-build-residue
  (let [root (fs/create-temp-dir {:dir (doto (fs/path "target") fs/create-dirs)
                                  :prefix "http-boundary-"})
        real (fs/path root "extensions" "pkg" "real.clj")
        residue (fs/path root "extensions" "pkg" "target" "residue.clj")]
    (try
      (fs/create-dirs (fs/parent real))
      (fs/create-dirs (fs/parent residue))
      (spit (str real) "(ns pkg.real)")
      (spit (str residue) "(ns pkg.residue (:require [babashka.http-client :as http]))")
      (is (= ['babashka.http-client] (offending-requires (str residue)))
          "the fixture is genuinely offending — only the skip keeps it out")
      (let [files (mapv str (source-files [(str root)]))]
        (is (some #(str/includes? % "real.clj") files)
            "real sources under the root are still scanned")
        (is (not-any? #(str/includes? % "residue.clj") files)
            "build-output residue is skipped"))
      (finally (fs/delete-tree root)))))

(ns kmet.libs.fs
  "Durable file writes for the app's state files: in-place UTF-8 writes and
   an atomic publish (temp file + rename), both retried briefly against
   Windows real-time AV, which opens a freshly written file to inspect it
   and holds its rename and delete until the scan ends.

   Why not spit? A publish needs its own rename: `publish!` writes a sibling
   temp and renames it over the target, while Jolt's spit (v0.8.24) and
   babashka's write the target in place. Using spit for the temp would add
   nothing to the byte-stream writer here, which writes explicit UTF-8
   rather than the host's default charset.

   The retry is kmet's own: it renames with fs/move
   (java.nio.file.Files/move on Jolt), which Jolt's own write paths do not
   cover. A rename landing while the AV handle is open fails with
   \"permission denied\" even though nothing is wrong with the paths — and
   the failed temp cannot be cleaned up either, the scanner holds it. Both
   publish steps are therefore retried on a short ladder before the write
   is allowed to fail."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.libs.log :as log]))

(def ^:private write-retry-delays-ms
  "Backoff before each retried write (5 retries, ~0.8s in total): long
   enough for a scanner to release a small file, short enough that a real
   permission problem still surfaces promptly."
  [25 50 100 200 400])

(defn- log-retry!
  "Best-effort debug line through the host's logger sink (see
   `kmet.libs.log`): the lib stays free of kmet.* requires
   (kmet.libs.test-self-contained)."
  [what err]
  (log/log! "fs: retrying " what " after: " (ex-message err)))

(defn- retry-write!
  "Run F, retrying while it throws and delays remain from DELAYS (default
   write-retry-delays-ms). A returned value is final; when the delays run
   out the last failure is rethrown."
  ([what f] (retry-write! what f write-retry-delays-ms))
  ([what f delays]
   (loop [delays delays]
     (let [err (try (f) nil (catch Exception e e))]
       (cond
         (nil? err) nil
         (seq delays) (do (log-retry! what err)
                          (Thread/sleep (first delays))
                          (recur (rest delays)))
         :else (throw err))))))

(defn- write-string!
  "Write TEXT to FILE as UTF-8, replacing its contents in place."
  [file text]
  (with-open [os (io/output-stream file)]
    (.write os (.getBytes text "UTF-8"))))

(defn append-line!
  "Append LINE to FILE as UTF-8 (FILE must exist). A failed attempt is
   retried only when it left FILE unchanged — a transient lock fails at open
   with nothing written, while a partial write (disk full) is left to the
   caller's crash repair rather than risking a duplicated line."
  [file line]
  (let [bytes (.getBytes line "UTF-8")]
    (loop [delays write-retry-delays-ms]
      (let [before (fs/size file)
            err (try (with-open [os (io/output-stream file :append true)]
                       (.write os bytes))
                     nil
                     (catch Exception e e))]
        (cond
          (nil? err) nil
          (not (and (seq delays) (= before (fs/size file)))) (throw err)
          :else (do (log-retry! (str "append to " file) err)
                    (Thread/sleep (first delays))
                    (recur (rest delays))))))))

(defn publish!
  "Publish CONTENT to FILE atomically: create FILE's parent directories,
   write a sibling temp file, then rename it over FILE (pi: temp-file
   publication), retrying the transient refusals (see the ns docstring).
   OPTS:
     :prepare — (fn [tmp]) run with the temp path after the temp write and
                before the rename, for fixups on the final bytes (e.g. 0600
                perms). Its throw is not retried.
   Returns nil."
  ([file content] (publish! file content nil))
  ([file content {:keys [prepare]}]
   (when-let [parent (fs/parent file)]
     (fs/create-dirs parent))
   (let [tmp (str file ".tmp")]
     (retry-write! (str "write of " tmp) #(write-string! tmp content))
     (when prepare (prepare tmp))
     (retry-write! (str "publish of " file) #(fs/move tmp file {:replace-existing true})))
   nil))

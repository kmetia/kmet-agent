(ns kmet.test-time-boundary
  "Guard for the duration clock (AGENTS.md, Time and durations):
   `System/currentTimeMillis` steps (NTP, Android radio time, VM resume), so
   a duration or deadline measured on it is wrong in both directions — a
   forward step fires timeouts early and kills work that has just started, a
   backward step stalls them. In the kmet.libs.* layer, elapsed time must
   come from kmet.libs.concurrent/monotonic-ms.

   The allowlisted files read the wall clock for values compared against
   externally-produced wall-clock data (JWT claims, a lock file's own
   mtime, persisted OAuth token expiries), not for elapsed durations.
   Anything else that needs the wall clock in this layer must move to
   monotonic-ms or argue for an allowlist entry here.

   Comment lines are exempt so prose can still name the call; other prose
   should write it without parentheses."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.test-utils :as tu]))

(def ^:private wall-clock-allowed
  "libs files where the wall clock is compared against externally-produced
   wall-clock data rather than an elapsed duration."
  #{"src/kmet/libs/crypto.clj"     ; JWT iat/exp claims
    "src/kmet/libs/edn_store.clj"  ; the lock file's own mtime
    "src/kmet/libs/mcp/auth.clj"}) ; persisted OAuth token expiry (epoch ms)

(defn- lib-files
  "Every lib source file (.clj/.cljc) under src/kmet/libs, at any depth.
   A recursive walk, not `fs/glob` with `**/`: the glob does not match files
   directly in the base dir (it would miss oauth, jsonrpc, http, sse, …)."
  []
  (letfn [(walk [dir]
            (mapcat (fn [f] (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (walk "src/kmet/libs")
         (map #(tu/slash (str %)))
         (filter #(re-find #"\.clj[ca]?$" %))
         (sort))))

(defn- wall-clock-calls
  "The non-comment source lines of PATH that call System/currentTimeMillis."
  [path]
  (->> (str/split-lines (slurp path))
       (remove #(str/starts-with? (str/trim %) ";"))
       (filter #(re-find #"\(System/currentTimeMillis\)" %))))

(deftest libs-measure-durations-monotonically
  (doseq [path (lib-files)]
    (when-not (contains? wall-clock-allowed path)
      (let [calls (wall-clock-calls path)]
        (is (empty? calls)
            (str path " reads the wall clock (" (first calls) ") — durations "
                 "and deadlines in kmet.libs.* must use "
                 "kmet.libs.concurrent/monotonic-ms (AGENTS.md, Time and "
                 "durations); allowlist an entry only for a value compared "
                 "against externally-produced wall-clock data"))))))

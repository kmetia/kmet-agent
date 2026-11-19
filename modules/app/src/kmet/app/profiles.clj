(ns kmet.app.profiles
  "kmet's committed composition table and the default profile.

   The table is data: `modules/app/src/kmet/app/profiles.edn` holds the
   bundles and profiles the shipped entry points compose. `kmet.sklop.core`
   owns the language and `kmet.app.catalog` the app's row sources; this
   namespace owns only where the built-in table lives and which profile a bare
   run selects."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def resource
  "The committed built-in composition table (bundles + profiles)."
  "kmet/app/profiles.edn")

(def default-profile
  "The profile a bare run composes."
  :tui)

(defn builtin
  "The committed composition table. A missing resource is a packaging bug, not
   an empty table."
  []
  (if-let [r (io/resource resource)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "missing built-in profile resource " resource)
                    {:type :kmet.app/missing-profiles}))))

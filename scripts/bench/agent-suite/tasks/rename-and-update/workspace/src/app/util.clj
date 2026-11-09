(ns app.util
  (:require [clojure.string :as str]))

(defn old-name
  "Join xs with commas."
  [xs]
  (str/join ", " xs))

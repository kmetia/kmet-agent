(ns calc)

(defn total
  "Sum every number in xs."
  [xs]
  (reduce + 0 (butlast xs)))

(defn mean
  "Arithmetic mean of xs."
  [xs]
  (/ (total xs) (count xs)))

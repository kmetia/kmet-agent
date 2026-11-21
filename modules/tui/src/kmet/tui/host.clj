(ns kmet.tui.host
  "The TUI's portable host predicates: the small slice it needs to choose a
   terminal or clipboard backend and validate numeric layout options. Kept in
   the package so the TUI has no dependency on `kmet.libs.host`; each
   implementation matches the shared layer's, so behavior cannot drift by
   host."
  (:require [babashka.fs :as fs]))

(defn windows?
  "True when the OS is Windows (the same predicate as `kmet.libs.host`)."
  []
  (fs/windows?))

(defn jolt?
  "True on the Jolt host: jolt defines `clojure.core/*jolt-version*`."
  []
  (boolean (find-var 'clojure.core/*jolt-version*)))

(defn finite?
  "True when V is a finite number — portable Double/isFinite, composing the
   isNaN/isInfinite statics Jolt provides."
  [v]
  (and (number? v)
       (let [d (double v)]
         (not (or (Double/isNaN d) (Double/isInfinite d))))))

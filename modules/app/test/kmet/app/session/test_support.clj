(ns kmet.app.session.test-support
  "Fixtures and helpers shared by the session tests: one scratch sessions dir
   per namespace run and a fast repeated-character builder for the large-payload
   cases."
  (:require [babashka.fs :as fs]
            [kmet.test-tmp :as test-tmp]))

(def test-dir
  "Scratch sessions dir: one directory per run under the test scratch root,
   removed with it. Several tests assert per-file counts, so a stale dir would
   grow the assertion count across runs — the fixture starts clean."
  (test-tmp/temp-dir! "test-sessions-"))

(defn with-clean-test-dir
  "Fixture: start each test namespace from an empty scratch dir."
  [f]
  (test-tmp/delete! test-dir)
  (fs/create-dirs test-dir)
  (f))

(defn pad-string
  "A string of N copies of C. char-array + Arrays/fill is one native call,
   ~100× faster than (apply str (repeat n c)) in babashka — used to build the
   >1 MB test payloads."
  [n c]
  (let [a (char-array n)]
    (java.util.Arrays/fill a c)
    (String. a)))

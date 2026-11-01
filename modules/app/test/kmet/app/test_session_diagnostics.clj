(ns kmet.app.test-session-diagnostics
  "The host side of the session loader's strict pass: `--debug` owns one
   switch, and turning it on is the only thing that makes the loader validate
   and skip. The loader's own tests live with it in modules/app; this one
   stays with the host, because it asserts the wiring of a kmet1 flag and
   goes away with kmet1."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.debug :as debug]
            [kmet.app.session.core :as s]
            [kmet.app.session.store :as store]
            [kmet.test-tmp :as test-tmp]))

(defn- scratch []
  (test-tmp/temp-dir! "test-"))

(defn- captured-stderr
  "Run F with stderr captured; `[result text]` — the cases that assert a
   diagnostic is absent unless the host asked for it."
  [f]
  (let [w (java.io.StringWriter.)
        result (binding [*err* w] (f))]
    [result (str w)]))

(t/deftest test-the-strict-pass-is-off-by-default-and-data-is-trusted
  ;; A header and an entry that parse as EDN but fail the schema. Off (the
  ;; default) a session file that parses is trusted and loads as written; on —
  ;; kmet1's `--debug` — the strict pass reports and skips the entry.
  (let [session (s/create-session (scratch))
        file (:file session)]
    (spit file (str (pr-str {:type :session :version 1 :id (:id session)
                             :created-at "2026-01-01T00:00:00Z" :cwd (scratch)}) "\n"))
    (spit file (str (pr-str {:id 7 :role :user}) "\n") :append true)
    (try
      (let [[loaded out] (captured-stderr #(s/load-session file))]
        (t/is (= "" out) "silent unless the host asked for diagnostics")
        (t/is (= [{:id 7 :role :user}] @(:entries loaded))
              "off, a parseable-but-wrong-shape entry is kept"))
      (store/report-wrong-shapes! true)
      (let [[loaded out] (captured-stderr #(s/load-session file))]
        (t/is (str/includes? out "Skipping wrong-shape entry") "the entry is reported")
        (t/is (str/includes? out ":id [\"should be a string\"]") "with the reason")
        (t/is (empty? @(:entries loaded)) "and skipped"))
      (finally
        (store/report-wrong-shapes! false)))))

(t/deftest test-the-debug-switch-owns-the-strict-pass
  ;; One place decides "diagnostics on"; a typo there would mean the strict
  ;; pass never runs. `log` is stubbed so the assertion writes no kmet-debug.log.
  (try
    (with-redefs [debug/log (fn [& _] nil)]
      (debug/enable!)
      (t/is (store/report-wrong-shapes?) "enable! turns the strict pass on")
      (debug/disable!)
      (t/is (not (store/report-wrong-shapes?)) "disable! turns it off"))
    (finally
      (with-redefs [debug/log (fn [& _] nil)] (debug/disable!)))))

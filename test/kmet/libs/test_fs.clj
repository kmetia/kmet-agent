(ns kmet.libs.test-fs
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.fs :as kfs]))

(defn- test-dir []
  (str "target/test-libs-fs-" (System/currentTimeMillis) "-" (rand-int 100000)))

(defn- with-dir [f]
  (let [dir (test-dir)]
    (fs/create-dirs dir)
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(t/deftest test-publish-creates-parents-and-replaces
  (with-dir
    (fn [dir]
      (let [file (str dir "/a/b/session.ednl")]
        (t/is (nil? (kfs/publish! file "one\n")) "publish! returns nil")
        (t/is (= "one\n" (slurp file)))
        (t/is (not (fs/exists? (str file ".tmp"))) "no temp is left behind")
        (kfs/publish! file "two\n")
        (t/is (= "two\n" (slurp file)) "an existing file is replaced")))))

(t/deftest test-publish-runs-prepare-on-the-temp-before-the-rename
  (with-dir
    (fn [dir]
      (let [file (str dir "/x.ednl")
            tmp (atom nil)]
        (kfs/publish! file "content\n"
                      {:prepare (fn [t]
                                  (reset! tmp (str t))
                                  (spit t "prepared\n" :append true))})
        (t/is (= (str file ".tmp") @tmp) "prepare sees the temp path")
        (t/is (= "content\nprepared\n" (slurp file))
              "the prepare fixup is in the published bytes")))))

(t/deftest test-append-line
  (with-dir
    (fn [dir]
      (let [file (str dir "/x.ednl")]
        (spit file "first\n")
        (kfs/append-line! file "second\n")
        (kfs/append-line! file "tricky ünïcode ✓\n")
        (t/is (= ["first" "second" "tricky ünïcode ✓"]
                 (str/split-lines (slurp file))))))))

(t/deftest test-retry-write-bounded
  ;; A transient failure (Windows AV holding a freshly written file) is
  ;; retried; a persistent one is rethrown once the delays run out.
  (let [calls (atom 0)]
    (#'kfs/retry-write! "transient"
                        (fn [] (when (= 1 (swap! calls inc))
                                 (throw (ex-info "locked" {:type :test}))))
                        [0 0])
    (t/is (= 2 @calls) "the failed attempt is retried"))
  (let [calls (atom 0)
        err (try
              (#'kfs/retry-write! "permanent"
                                  (fn [] (swap! calls inc)
                                    (throw (ex-info "denied" {:type :test})))
                                  [0 0])
              nil
              (catch Exception e e))]
    (t/is (= 3 @calls) "one retry per delay, then give up")
    (t/is (= "denied" (ex-message err)) "the last failure is rethrown")))

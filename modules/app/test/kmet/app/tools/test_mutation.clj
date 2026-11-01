(ns kmet.app.tools.test-mutation
  "Per-path serialization across the file tools (pi: file-mutation-queue.ts):
   writes and edits serialize, reads take the same lock, and a cancelled run
   stops waiting."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.mutation :as mutation]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.util :as tool-util]
            [kmet.test-utils :as tu]
            [kmet.test-tmp :as test-tmp]))

(def ^:private wait-ms
  "Bound for a deref that should complete on its own — a leaked lock then
   fails the test instead of hanging the suite."
  5000)

(def ^:private blocked-ms
  "Bound for a deref that is expected to block: short enough to keep the fast
   suite fast, long enough that an unlocked read would have returned."
  200)

(defn- temp-dir []
  (let [dir (str (fs/absolutize (test-tmp/temp-dir! "test-mutation-")))]
    (fs/create-dirs dir)
    dir))

(defn- with-dir [f]
  (let [dir (temp-dir)]
    (try (binding [tool-util/*cwd* dir] (f dir))
         (finally (fs/delete-tree dir)))))

(def ^:private reg
  "One registry for the namespace: the built-in rows, as a run's tools resolve."
  (tu/tool-registry))

(defn- tool-call
  "Execute a built-in through the registry — the path a run takes, including
   the contextual arity that carries the cancel signal."
  [name args signal]
  (tools/execute-tool reg name args (cond-> {} signal (assoc :signal signal))))

(defn- call-with-timeout
  "Run F on a future and give up after WAIT-MS — a stuck lock fails instead of
   hanging."
  [f]
  (deref (future (f)) wait-ms ::timeout))

(t/deftest ^:slow test-same-path-sections-do-not-overlap
  ;; the second section enters only after the first releases; the short sleep
  ;; gives it time to reach the lock, so an unlocked implementation fails
  ;; instead of slipping through
  (let [log (atom [])
        in-a (promise)
        release-a (promise)
        a (future (mutation/with-file-lock "same.txt" nil
                    (fn []
                      (deliver in-a true)
                      @release-a
                      (swap! log conj :a-done))))
        _ @in-a
        b (future (mutation/with-file-lock "same.txt" nil
                    (fn [] (swap! log conj :b-start))))]
    (Thread/sleep 20)
    (t/is (= [] @log) "b cannot enter while a holds the lock")
    (deliver release-a true)
    (t/is (not= ::timeout (deref a wait-ms ::timeout)))
    (t/is (not= ::timeout (deref b wait-ms ::timeout)))
    (t/is (= [:a-done :b-start] @log)
          "b enters after a, in arrival order")))

(t/deftest test-different-paths-run-concurrently
  (let [holding (promise)
        release-a (promise)
        a (future (mutation/with-file-lock "a.txt" nil
                    (fn [] (deliver holding true) @release-a)))
        _ @holding]
    (t/is (= :b-ran (call-with-timeout
                     #(mutation/with-file-lock "b.txt" nil (fn [] :b-ran))))
          "another path's section runs while a is held")
    (deliver release-a true)
    @a))

(t/deftest test-a-throw-releases-the-lock
  (t/is (thrown? Exception
                 (mutation/with-file-lock "throws.txt" nil
                   #(throw (ex-info "boom" {})))))
  (t/is (= :after (call-with-timeout
                   #(mutation/with-file-lock "throws.txt" nil (fn [] :after))))
        "the lock is free after a throwing section"))

(t/deftest test-a-cancelled-wait-gives-up
  ;; a run's deadline/Escape must not park a worker on another call's write
  (let [holding (promise)
        release (promise)
        cancelled (atom true)
        a (future (mutation/with-file-lock "cancel.txt" nil
                    (fn [] (deliver holding true) @release)))]
    (try
      @holding
      (t/is (= {:type :mutation/cancelled}
               (call-with-timeout
                #(try (mutation/with-file-lock "cancel.txt" cancelled (fn [] :ran))
                      (catch Exception e (ex-data e)))))
            "the waiter gives up when the signal fires")
      (finally (deliver release true) @a)))
  (t/is (= :ran (call-with-timeout
                 #(mutation/with-file-lock "cancel.txt" (atom false) (fn [] :ran))))
        "an uncancelled wait still runs"))

(t/deftest test-lock-key-follows-the-real-path
  ;; two spellings of one file share the lock, including a symlink
  (with-dir
    (fn [dir]
      (let [key #'mutation/lock-key
            target (str dir "/target.txt")]
        (spit target "x")
        (fs/create-sym-link (str dir "/link.txt") target)
        (t/is (= (key target) (key (str dir "/link.txt"))))
        (t/is (= (key (str dir "/./target.txt")) (key target)))
        (t/is (= (key (str dir "/missing.txt"))
                 (key (str dir "/./missing.txt")))
              "a missing file keys on its normalized path")))))

(t/deftest test-write-acquires-the-file-lock
  (with-dir
    (fn [dir]
      (let [locks (atom [])
            signal (atom false)]
        (with-redefs [mutation/with-file-lock
                      (fn [path sig f] (swap! locks conj [path sig]) (f))]
          (let [r (tool-call "write" {:path "out.txt" :content "hello"} signal)]
            (t/is (not (:is-error r)) (:content r))
            (t/is (= "hello" (slurp (str dir "/out.txt"))))
            (t/is (= 1 (count @locks)))
            (t/is (str/ends-with? (first (first @locks)) "out.txt"))
            (t/is (identical? signal (second (first @locks)))
                  "write passes the run's cancel signal to the lock")))))))

(t/deftest test-edit-acquires-the-file-lock
  (with-dir
    (fn [dir]
      (spit (str dir "/edit.txt") "alpha")
      (let [locks (atom [])]
        (with-redefs [mutation/with-file-lock
                      (fn [path _signal f] (swap! locks conj path) (f))]
          (let [r (tool-call "edit" {:path "edit.txt"
                                     :edits [{:old-text "alpha" :new-text "beta"}]}
                             nil)]
            (t/is (not (:is-error r)) (:content r))
            (t/is (= "beta" (slurp (str dir "/edit.txt"))))
            (t/is (= 1 (count @locks)))
            (t/is (str/ends-with? (first @locks) "edit.txt")
                  "edit takes the same lock for the same file")))))))

(t/deftest test-read-waits-for-a-write
  ;; a read must not observe a write's truncated half: it takes the same lock
  (with-dir
    (fn [dir]
      (spit (str dir "/read.txt") "complete")
      (let [holding (promise)
            release (promise)
            holder (future (mutation/with-file-lock (str dir "/read.txt") nil
                             (fn [] (deliver holding true) @release)))]
        @holding
        (try
          (t/is (= ::timeout
                   (deref (future (tool-call "read" {:path "read.txt"} nil))
                          blocked-ms ::timeout))
                "the read waits while a write holds the file")
          (finally (deliver release true) @holder))
        (t/is (str/includes? (:content (tool-call "read" {:path "read.txt"} nil))
                             "complete")
              "and sees the whole file once the lock is free")))))

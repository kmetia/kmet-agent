(ns kmet.libs.test-nrepl
  "Tests for the nREPL client (kmet.libs.nrepl): the bencode
   codec, message accumulation, and a fake in-process nREPL server for the
   socket/session/eval path."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.nrepl :as nrepl]))

;; ─── Codec helpers ─────────────────────────────────────────────────────────

(defn- source-of ^bytes [^bytes bs]
  (nrepl/byte-source (java.io.ByteArrayInputStream. bs)))

(defn- decode-one [bs]
  (nrepl/read-message (source-of bs)))

(defn- roundtrip [m]
  (decode-one (nrepl/encode-message m)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Bencode
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-roundtrip-op-message
  (is (= {:op "eval" :code "(+ 1 2)" :id "1"}
         (roundtrip {:op "eval" :code "(+ 1 2)" :id "1"}))))

(deftest ^:bb-only test-roundtrip-utf8
  (testing "string length is byte length, not char count"
    (let [m {:value "héllo → ünïcode"}]
      (is (= m (roundtrip m)))))
  (testing "non-ASCII payload decodes back byte-for-byte"
    (is (= {:v "λ"} (decode-one (nrepl/encode-message {:v "λ"}))))))

(deftest ^:bb-only test-roundtrip-integers-and-nested
  (is (= {:n 42} (roundtrip {:n 42})))
  (is (= {:n -7} (roundtrip {:n -7})))
  (is (= {:versions {"clojure" "1.12.0" "nrepl" "1.5.1"}}
         (roundtrip {:versions {"clojure" "1.12.0" "nrepl" "1.5.1"}})))
  (is (= {:list [1 "a" [2]]} (roundtrip {:list [1 "a" [2]]}))))

(deftest ^:bb-only test-decode-several-messages-from-one-stream
  (let [bs (nrepl/encode-message {:id "1" :out "a"})
        bs2 (nrepl/encode-message {:id "1" :status ["done"]})
        src (source-of (byte-array (concat (seq bs) (seq bs2))))]
    (is (= {:id "1" :out "a"} (nrepl/read-message src)))
    (is (= {:id "1" :status ["done"]} (nrepl/read-message src)))
    (is (nil? (nrepl/read-message src)) "clean EOF reads as nil")))

(deftest ^:bb-only test-decode-rejects-garbage
  (is (thrown? Exception (decode-one (byte-array [(unchecked-byte 0x78)])))))

(deftest ^:bb-only test-merge-messages
  (let [merged (nrepl/merge-messages
                [{:id "1" :out "ana" :value "1"}
                 {:id "1" :out "ly" :err "warn"}
                 {:id "1" :ns "user" :value "2" :status ["done"]}
                 {:id "1" :versions {"clojure" "1.12.0"}}])]
    (is (= ["1" "2"] (:values merged)))
    (is (= "analy" (:out merged)))
    (is (= "warn" (:err merged)))
    (is (= "user" (:ns merged)))
    (is (= {"clojure" "1.12.0"} (:versions merged)))
    (is (= #{"done"} (:status merged)))))

(deftest ^:bb-only test-message-events-preserve-arrival-order
  (is (= [[:out "hello\n"] [:value "3"] [:err "boom"]]
         (nrepl/message-events [{:out "hello\n"}
                                {:value "3"}
                                {:err "boom"}]))))

(deftest ^:bb-only test-env-from-describe
  (is (= :bb (nrepl/env-from-describe {:versions {"babashka" "1.3.0" "nrepl" "1.0"}})))
  (is (= :basilisp (nrepl/env-from-describe {:versions {"basilisp" "0.2"}})))
  (is (= :shadow (nrepl/env-from-describe {:versions {"shadow-cljs" "2.28"}})))
  (is (= :clj (nrepl/env-from-describe {:versions {"clojure" "1.12.0"}})))
  (is (= :clj (nrepl/env-from-describe {}))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Fake nREPL server (socket + session + eval path)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- write-msg! [out m]
  (.write out (nrepl/encode-message m))
  (.flush out))

(defn- serve-connection [conn counter {:keys [eval-sleep-ms ns-op-status on-eval]}]
  (try
    (let [in (.getInputStream conn)
          out (.getOutputStream conn)
          next-byte (nrepl/byte-source in)]
      (loop []
        (when-let [m (nrepl/read-message next-byte)]
          (let [id (:id m)
                session (:session m)]
            (case (:op m)
              "clone" (write-msg! out {:id id
                                       :new-session (str "sess-" (swap! counter inc))
                                       :status ["done"]})
              "describe" (write-msg! out {:id id
                                          :versions {"clojure" "1.12.0" "nrepl" "1.0"}
                                          :status ["done"]})
              "ns" (write-msg! out {:id id :session session :ns (:ns m)
                                    :status (or ns-op-status ["done"])})
              "eval" (do (when on-eval (on-eval (:code m)))
                         (when eval-sleep-ms (Thread/sleep eval-sleep-ms))
                         (write-msg! out {:id id :session session :out "out!\n"})
                         (write-msg! out {:id id :session session :ns "user" :value "3"})
                         (write-msg! out {:id id :session session :status ["done"]}))
              "interrupt" nil
              "close" (write-msg! out {:id id :session session :status ["done"]})
              (write-msg! out {:id id :status ["done"]})))
          (recur))))
    (catch Exception _ nil)
    (finally (try (.close conn) (catch Exception _ nil)))))

(defn- start-fake-nrepl!
  ([] (start-fake-nrepl! {}))
  ([opts]
   (let [server (java.net.ServerSocket. 0)
         counter (atom 0)
         running? (atom true)]
     (doto (Thread.
            (fn []
              (while @running?
                (try
                  (let [conn (.accept server)]
                    (doto (Thread. (fn [] (serve-connection conn counter opts)))
                      (.setDaemon true)
                      (.start)))
                  (catch Exception _ nil)))))
       (.setDaemon true)
       (.start))
     {:server server
      :port (.getLocalPort server)
      :stop! (fn []
               (reset! running? false)
               (try (.close server) (catch Exception _ nil)))})))

(deftest ^:slow ^:bb-only test-eval-code-against-fake-server
  (let [{:keys [port stop!]} (start-fake-nrepl!)]
    (try
      (let [result (nrepl/eval-code {:port port :code "(+ 1 2)"})]
        (is (:ok? result))
        (is (= ["3"] (:values result)))
        (is (= "out!\n" (:out result)))
        (is (= "user" (:ns result)))
        (is (= :clj (:env result)))
        (is (some? (:session result))))
      (testing "eval with a target namespace sends the ns op first"
        (let [result (nrepl/eval-code {:port port :ns "my.app" :code "1"})]
          (is (:ok? result))))
      (testing "the session is reused on the next call"
        (let [first-session (:session (nrepl/eval-code {:port port :code "1"}))
              second-session (:session (nrepl/eval-code {:port port :code "2"}))]
          (is (= first-session second-session))))
      (finally
        (nrepl/close-sessions!)
        (stop!)))))

(deftest ^:slow ^:bb-only test-eval-code-timeout-interrupts
  (let [{:keys [port stop!]} (start-fake-nrepl! {:eval-sleep-ms 1500})]
    (try
      (let [result (nrepl/eval-code {:port port :code "(Thread/sleep 1500)"
                                     :timeout-ms 400})]
        (is (:timeout? result))
        (is (not (:ok? result))))
      (finally
        (nrepl/close-sessions!)
        (stop!)))))

(deftest ^:slow ^:bb-only test-connect-failure-is-a-typed-error
  (let [e (try
            (nrepl/eval-code {:port 1 :code "1" :timeout-ms 200})
            nil
            (catch Exception e e))]
    (is (some? e))
    (is (= :nrepl-connect (:type (ex-data e))))))

(deftest ^:slow ^:bb-only test-ns-op-fallback-to-in-ns
  (testing "a server without the ns op still switches namespace via (in-ns …)"
    (let [codes (atom [])
          {:keys [port stop!]} (start-fake-nrepl! {:ns-op-status ["unknown-op" "done"]
                                                   :on-eval (fn [code] (swap! codes conj code))})]
      (try
        (let [result (nrepl/eval-code {:port port :ns "my.app" :code "(+ 1 2)"})]
          (is (:ok? result))
          (is (= ["3"] (:values result)))
          (is (= ["(do (clojure.core/in-ns 'my.app) nil)" "(+ 1 2)"]
                 @codes)
              "the fallback runs before the requested code, in the same session"))
        (finally
          (nrepl/close-sessions!)
          (stop!))))))

(deftest ^:bb-only test-eval-code-cancel-before-connect
  (let [result (nrepl/eval-code {:port 1 :code "1" :cancel (fn [] true)})]
    (is (:cancelled? result))
    (is (not (:ok? result)))))

(deftest ^:slow ^:bb-only test-eval-code-cancel-closes-a-blocked-read
  (let [{:keys [port stop!]} (start-fake-nrepl! {:eval-sleep-ms 5000})
        cancelled (atom false)]
    (doto (Thread. (fn []
                     (Thread/sleep 300)
                     (reset! cancelled true)))
      (.setDaemon true)
      (.start))
    (try
      (let [t0 (System/currentTimeMillis)
            result (nrepl/eval-code {:port port :code "(Thread/sleep 5000)"
                                     :timeout-ms 30000
                                     :cancel (fn [] @cancelled)})
            elapsed (- (System/currentTimeMillis) t0)]
        (is (:cancelled? result))
        (is (not (:ok? result)))
        (is (< elapsed 3000) "cancel must not wait for the eval deadline"))
      (finally
        (nrepl/close-sessions!)
        (stop!)))))

(deftest ^:slow ^:bb-only test-discover-port-in-directory
  (let [{:keys [port stop!]} (start-fake-nrepl!)]
    (try
      (let [dir "target/nrepl-tests"]
        (fs/create-dirs dir)
        (spit (str dir "/.nrepl-port") (str port "\n") :encoding "UTF-8")
        (try
          (with-redefs [nrepl/common-ports []]
            (is (= {:host "127.0.0.1" :port port :source ".nrepl-port"}
                   (nrepl/discover-port "127.0.0.1" dir)))
            (is (nil? (nrepl/discover-port "127.0.0.1" "target/no-such-nrepl-dir"))))
          (finally
            (fs/delete-tree dir))))
      (finally
        (nrepl/close-sessions!)
        (stop!)))))

(deftest ^:slow ^:bb-only test-discover-port-falls-back-to-common-ports
  (let [{:keys [port stop!]} (start-fake-nrepl!)]
    (try
      (testing "with no port file, a responding common port is used"
        (with-redefs [nrepl/common-ports [port]]
          (is (= {:host "127.0.0.1" :port port :source :common}
                 (nrepl/discover-port "127.0.0.1" "target/no-such-nrepl-dir")))))
      (testing "a port file is preferred over a responding common port"
        (let [dir "target/nrepl-tests-common"]
          (fs/create-dirs dir)
          (spit (str dir "/.nrepl-port") (str port "\n") :encoding "UTF-8")
          (try
            (with-redefs [nrepl/common-ports [port]]
              (is (= {:host "127.0.0.1" :port port :source ".nrepl-port"}
                     (nrepl/discover-port "127.0.0.1" dir))))
            (finally
              (fs/delete-tree dir)))))
      (finally
        (nrepl/close-sessions!)
        (stop!)))))

(deftest ^:slow ^:bb-only test-discover-port-falls-through-a-stale-port-file
  (let [{:keys [port stop!]} (start-fake-nrepl!)
        dead-port (with-open [socket (java.net.ServerSocket. 0)]
                    (.getLocalPort socket))
        dir "target/nrepl-tests-stale"]
    (fs/create-dirs dir)
    (spit (str dir "/.nrepl-port") (str dead-port "\n") :encoding "UTF-8")
    (try
      (with-redefs [nrepl/common-ports [port]]
        (is (= {:host "127.0.0.1" :port port :source :common}
               (nrepl/discover-port "127.0.0.1" dir))))
      (finally
        (fs/delete-tree dir)
        (nrepl/close-sessions!)
        (stop!)))))

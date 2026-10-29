(ns kmet.extensions.lsp-adapter.runtime-test
  "Connection ownership without spawning: the state map, config reloads,
   the sticky broken set (a failed claim is cached, never retried), the
   claiming/broken fan-out, disconnect bookkeeping and the idle reaper's
   predicate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent :as concurrent]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.runtime :as runtime]
            [kmet.extensions.lsp-adapter.test-util :as tu]))

(defn- fake-conn
  "A conn-shaped map with just what the runtime reads (no transport)."
  [name root & {:keys [last-used]}]
  {:name name :root root
   :docs (atom {})
   :diags (atom {})
   :client {:last-used (atom (or last-used (concurrent/monotonic-ms)))
            :closed (atom false)}})

;; ─── State ────────────────────────────────────────────────────────────────

(deftest state-and-config
  (let [st (runtime/new-state nil {:servers {"a" {:command ["a"]}}
                                   :settings {:idle-timeout 5}})]
    (testing "the config starts as given"
      (is (= {:servers {"a" {:command ["a"]}}
              :settings {:idle-timeout 5}}
             (runtime/config st)))
      (is (= {"a" {:command ["a"]}} (runtime/configured-servers st))))
    (testing "set-config! replaces it"
      (runtime/set-config! st {:servers {}})
      (is (= {} (runtime/configured-servers st)))
      (is (= {:servers {}} (runtime/config st))))
    (testing "the state starts with no conns and no broken servers"
      (is (empty? (runtime/all-conns st)))
      (is (empty? @(:broken st))))))

(deftest on-change-hook
  (let [st (tu/new-state {:servers {}})
        calls (atom 0)]
    (runtime/set-on-change! st (fn [] (swap! calls inc)))
    (runtime/mark-broken! st "a" ":not installed")
    (runtime/clear-broken! st "a")
    (testing "every footer-visible mutation notifies the hook"
      (is (= 2 @calls)))))

;; ─── Sticky broken set ────────────────────────────────────────────────────

(deftest broken-set
  (let [st (tu/new-state {:servers {}})]
    (testing "a marked server is broken with its reason"
      (runtime/mark-broken! st "clojure-lsp" ":not installed")
      (is (= ":not installed" (:reason (runtime/broken st "clojure-lsp")))))
    (testing "the cached failure is an ex-info naming the restart path"
      (let [e (runtime/broken-error "clojure-lsp" (runtime/broken st "clojure-lsp"))]
        (is (= :kmet.extensions.lsp-adapter.runtime/server-broken (:type (ex-data e))))
        (is (str/includes? (ex-message e) "clojure-lsp: :not installed"))
        (is (str/includes? (ex-message e) "/lsp restart to retry"))))
    (testing "clearing one leaves the others"
      (runtime/mark-broken! st "gopls" ":spawn failed")
      (runtime/clear-broken! st "clojure-lsp")
      (is (nil? (runtime/broken st "clojure-lsp")))
      (is (some? (runtime/broken st "gopls"))))
    (testing "clear-all-broken! empties the set"
      (runtime/clear-all-broken! st)
      (is (empty? @(:broken st))))))

;; ─── Claiming fan-out ─────────────────────────────────────────────────────

(deftest for-file-with-a-broken-server
  (let [dir (tu/temp-dir! "lsp-broken")
        file (str dir "/a.clj")]
    (try
      (spit (str dir "/deps.edn") "{}")
      (spit file "1")
      (let [st (tu/new-state {:servers {"clojure-lsp" {:command ["nope"]}}})]
        (runtime/mark-broken! st "clojure-lsp" ":not installed")
        (testing "a broken claiming server reports its cached failure"
          (let [{:keys [results errors]} (runtime/for-file st file identity)]
            (is (empty? results))
            (is (= ["clojure-lsp"] (mapv :name errors)))
            (is (str/includes? (first errors) ":not installed"))))
        (testing "the failure is returned instantly on every call"
          (let [t0 (concurrent/monotonic-ms)
                _ (runtime/for-file st file identity)]
            (is (< (- (concurrent/monotonic-ms) t0) 500)))))
      (finally (tu/delete-tree! dir)))))

(deftest for-file-nobody-claims
  (let [dir (tu/temp-dir! "lsp-unclaimed")]
    (try
      (let [st (tu/new-state {:servers {}})
            out (runtime/for-file st (str dir "/x.unknownext") identity)]
        (testing "an unclaimed file yields empty results and errors"
          (is (= {:results [] :errors []} out))))
      (finally (tu/delete-tree! dir)))))

(deftest broken-claiming
  (let [dir (tu/temp-dir! "lsp-broken-claim")
        file (str dir "/a.clj")]
    (try
      (spit (str dir "/deps.edn") "{}")
      (spit file "1")
      (let [st (tu/new-state {:servers {}})]
        (testing "nothing broken means no instant errors"
          (is (empty? (runtime/broken-claiming st file))))
        (testing "a broken claimant appears with its error attached"
          (runtime/mark-broken! st "clojure-lsp" ":not installed")
          (let [b (first (runtime/broken-claiming st file))]
            (is (= "clojure-lsp" (:id b)))
            (is (some? (:error b)))
            (is (str/includes? (ex-message (:error b)) ":not installed")))))
      (finally (tu/delete-tree! dir)))))

;; ─── Disconnects ──────────────────────────────────────────────────────────

(deftest disconnect-bookkeeping
  (let [a (fake-conn "clojure-lsp" "/p/a")
        b (fake-conn "clojure-lsp" "/p/b")
        c (fake-conn "gopls" "/p/a")
        st (tu/new-state {:servers {}} [a b c])]
    (testing "every conn is in the map"
      (is (= 3 (count (runtime/all-conns st)))))
    (testing "disconnect! is safe on a missing key"
      (is (nil? (runtime/disconnect! st ["nope" "/p/a"]))))
    (testing "disconnect-server! drops every root of that server"
      (is (true? (runtime/disconnect-server! st "clojure-lsp")))
      (is (= ["gopls"] (mapv :name (runtime/all-conns st))))
      (is (false? (runtime/disconnect-server! st "clojure-lsp"))))
    (testing "shutdown-all! empties the map"
      (runtime/shutdown-all! st)
      (is (empty? (runtime/all-conns st))))))

;; ─── Idle reaper ──────────────────────────────────────────────────────────

(deftest reaping-predicate
  (let [st (tu/new-state {:servers {"clojure-lsp" {:command ["x"]}}
                          :settings {:idle-timeout 15}})
        long-ago (- (concurrent/monotonic-ms) (* 20 60 1000))]
    (testing "an idle conn past the window is reaped"
      (is (true? (runtime/reaping? st (fake-conn "clojure-lsp" "/p" :last-used long-ago)))))
    (testing "a recently used conn is kept"
      (is (false? (runtime/reaping? st (fake-conn "clojure-lsp" "/p")))))
    (testing "a per-server timeout override wins"
      (let [st (tu/new-state {:servers {"clojure-lsp" {:command ["x"]
                                                       :idle-timeout 30}}
                              :settings {:idle-timeout 15}})]
        (is (false? (runtime/reaping? st (fake-conn "clojure-lsp" "/p"
                                                    :last-used long-ago))))))
    (testing "a zero timeout disables reaping"
      (let [st (tu/new-state {:servers {"clojure-lsp" {:command ["x"]
                                                       :idle-timeout 0}}})]
        (is (false? (runtime/reaping? st (fake-conn "clojure-lsp" "/p"
                                                    :last-used long-ago))))))
    (testing "a :keep-alive server is never reaped"
      (let [st (tu/new-state {:servers {"clojure-lsp" {:command ["x"]
                                                       :lifecycle :keep-alive}}})]
        (is (false? (runtime/reaping? st (fake-conn "clojure-lsp" "/p"
                                                    :last-used long-ago))))))))

(deftest reap-tick
  (let [idle (fake-conn "clojure-lsp" "/p/a"
                        :last-used (- (concurrent/monotonic-ms) (* 60 60 1000)))
        live (fake-conn "clojure-lsp" "/p/b")
        st (tu/new-state {:servers {"clojure-lsp" {:command ["x"]}}} [idle live])]
    (runtime/reap-tick! st)
    (testing "only the idle conn is disconnected"
      (is (= ["/p/b"] (mapv :root (runtime/all-conns st)))))))

;; ─── Effective registry reshaping through the state ───────────────────────

(deftest configured-servers-drive-detection
  (let [st (tu/new-state {:servers {"jdtls" {:disabled true}}})]
    (testing "a disabled builtin disappears from detection"
      (is (not-any? #(= "jdtls" (:id %))
                    (detect/effective-servers (runtime/configured-servers st)))))
    (testing "an unknown-id custom server joins it"
      (runtime/set-config! st {:servers {"mine" {:command ["mine-ls"]
                                                 :extensions ["foo"]}}})
      (is (some #(= "mine" (:id %))
                (detect/effective-servers (runtime/configured-servers st)))))))

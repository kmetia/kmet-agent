(ns kmet.extensions.lsp-adapter.e2e-test
  "End-to-end against scripts/fake-lsp-server.bb (plan §15): the handshake,
   the tool operations (definition, hover, documentSymbol, references, call
   hierarchy), the server→client configuration auto-reply, push-diagnostics
   collection, the sticky broken set, and a clean shutdown. Spawns the
   fixture through the runtime (^:slow)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.lsp-adapter.runtime :as runtime]
            [kmet.extensions.lsp-adapter.test-util :as tu]
            [kmet.extensions.lsp-adapter.tools :as tools]))

(defn- temp-project
  "A temp workspace the fake server can root itself in: the root marker
   plus a file it claims."
  [& [marker]]
  (let [dir (tu/temp-dir! "e2e")]
    (spit (str dir "/root-marker.txt") "")
    (spit (str dir "/sample.txt") "line zero\nline one\n")
    {:dir dir
     :sample (str dir "/sample.txt")
     ;; the fake needs kmet.libs.json on its classpath; it inherits the
     ;; parent's, which a checkout run gets from bb.edn/deps.edn
     :cfg {:servers {"fake" (assoc (tu/fake-entry)
                                   :env {"BABASHKA_CLASSPATH"
                                         (System/getProperty "java.class.path")})}}
     :marker (str dir "/config-marker")
     :marker-arg marker}))

(deftest ^:slow fake-server-end-to-end
  (let [{:keys [dir sample cfg]} (temp-project)
        marker (str dir "/config-marker")
        cfg (update-in cfg [:servers "fake" :command]
                       conj marker)
        st (runtime/new-state nil cfg)
        changes (atom 0)]
    (try
      (runtime/set-on-change! st (fn [] (swap! changes inc)))
      (testing "definition returns a shaped location"
        (let [out (:content (tools/execute st nil {:operation "definition"
                                                   :filePath sample
                                                   :line 1 :character 1}))]
          (is (str/includes? out "── fake ("))
          (is (str/includes? out "sample.txt:5:3"))))
      (testing "hover shapes the server's contents"
        (is (str/includes? (:content (tools/execute st nil
                                                    {:operation "hover"
                                                     :filePath sample
                                                     :line 1 :character 1}))
                           "hover docs for fake")))
      (testing "documentSymbol flattens the tree with kind names"
        (let [out (:content (tools/execute st nil {:operation "documentSymbol"
                                                   :filePath sample}))]
          (is (str/includes? out "alpha fn"))
          (is (str/includes? out "beta var"))))
      (testing "references lists both sites"
        (let [out (:content (tools/execute st nil {:operation "references"
                                                   :filePath sample
                                                   :line 1 :character 1}))]
          (is (str/includes? out "sample.txt:10:1"))
          (is (str/includes? out "sample.txt:12:2"))))
      (testing "incomingCalls composes prepare + incoming"
        (let [out (:content (tools/execute st nil
                                           {:operation "incomingCalls"
                                            :filePath sample
                                            :line 1 :character 1}))]
          (is (str/includes? out "caller fn"))
          (is (str/includes? out "(1 site)"))))
      (testing "the server→client configuration probe was auto-answered"
        (is (tu/wait-until #(.exists (io/file marker)) 5000)))
      (testing "a pushed diagnostic is collected and rendered"
        (is (tu/wait-until
             (fn []
               (str/includes? (:content (tools/execute st nil
                                                       {:operation "diagnostics"
                                                        :filePath sample}))
                              "ERROR [1:1] fake diagnostic"))
             5000)))
      (testing "the on-change hook fired during connects"
        (is (pos? @changes)))
      (testing "shutdown-all closes and forgets the connection"
        (is (seq @(:conns st)))
        (runtime/shutdown-all! st)
        (is (empty? @(:conns st))))
      (finally
        (runtime/shutdown-all! st)
        (tu/delete-tree! dir)))))

(deftest ^:slow broken-server-fails-fast-and-sticks
  (let [dir (tu/temp-dir! "broken")
        file (str dir "/m.zzz")]
    (try
      (spit (str dir "/root-marker.txt") "")
      (spit file "x")
      (let [st (runtime/new-state nil
                                  {:servers {"missing"
                                             {:command ["definitely-not-a-real-bin-xyz"]
                                              :extensions ["zzz"]
                                              :root-markers ["root-marker.txt"]}}})
            attempt (fn []
                      (let [t0 (System/currentTimeMillis)
                            out (try (:content (tools/execute
                                                st nil
                                                {:operation "definition"
                                                 :filePath file
                                                 :line 1 :character 1}))
                                     (catch Exception e (ex-message e)))]
                        [out (- (System/currentTimeMillis) t0)]))
            [out1 _ms1] (attempt)
            [out2 ms2] (attempt)]
        (testing "a missing binary is reported as not installed"
          (is (str/includes? out1 "not installed"))
          (is (str/includes? out2 "not installed")))
        (testing "the second attempt is served from the broken set (fast)"
          (is (< ms2 500)))
        (testing "an explicit clear lets a later claim retry"
          (runtime/clear-broken! st "missing")
          (is (nil? (runtime/broken st "missing")))))
      (finally (tu/delete-tree! dir)))))

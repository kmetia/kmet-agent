(ns kmet.loader.test-core
  "Conformance suite for kmet.loader.core — this list IS the spec
   (loader.md §8). Cases 1-8 and 10-12 run against the in-memory backend
   and the host root; case 9 (defining-ctx inheritance) needs a code
   backend and arrives with the sci backend (Phase 1)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.loader.core :as ldr]
            [kmet.loader.memory :as mem]))

(defn- req [kind name] {:kind kind :name name})

(defn- first-hit [l kind name] (first (ldr/find l (req kind name))))

(defn- load-name [l kind name] (ldr/load l (req kind name)))

(defn- caught [f]
  (try
    (f)
    nil
    (catch Throwable e e)))

(defn- ex-data-of [f]
  (try
    (ex-data (caught f))
    (catch Throwable _ nil)))

;; ─── §8.1-8.6: resolution tiers and policies ───────────────────────────────

(deftest test-case-1-v1-v2-isolation
  (testing "two ctxs, one lib at v1 and v2: distinct definitions, both correct"
    (let [v1 (mem/memory {:vars {'lib.core/foo (atom :v1)}})
          v2 (mem/memory {:vars {'lib.core/foo (atom :v2)}})
          c1 (load-name v1 :var "lib.core/foo")
          c2 (load-name v2 :var "lib.core/foo")]
      (is (not (identical? c1 c2)))
      (is (= :v1 @c1))
      (is (= :v2 @c2))
      (is (identical? v1 (:loader (first-hit v1 :var "lib.core/foo"))))
      (is (identical? v2 (:loader (first-hit v2 :var "lib.core/foo")))))))

(deftest test-case-2-hermetic
  (let [iso (ldr/isolated)]
    (is (= [] (ldr/find iso (req :ns "clojure.set"))))
    (is (= [] (ldr/find iso (req :var "clojure.string/join"))))
    (is (nil? (ldr/resolve iso (req :ns "clojure.set"))))
    (is (= :loader/miss (:type (ex-data-of #(ldr/load iso (req :ns "clojure.set"))))))
    (is (= :loader/miss (:type (ex-data-of #(ldr/load iso (req :var "clojure.string/join"))))))))

(deftest test-case-3-shared-by-reference
  (testing "a delegated var is the same cell, not a copy"
    (let [cell (atom 7)
          l (ldr/->loader (fn [req]
                            (when (= "lib/x" (:name req))
                              {:kind :var :cell cell})))]
      (is (identical? cell (load-name l :var "lib/x")))
      (is (identical? cell (ldr/resolve l (req :var "lib/x"))))))
  (testing "the host root hands back the host Var object itself"
    (let [join (ns-resolve 'clojure.string 'join)]
      (is (identical? join (load-name (ldr/root) :var "clojure.string/join")))
      (let [ctx (ldr/delegating (ldr/isolated) (ldr/root))]
        (is (identical? join (load-name ctx :var "clojure.string/join")))
        (is (identical? join (ldr/resolve ctx (req :var "clojure.string/join"))))))))

(deftest test-case-4-deny-is-not-miss
  (let [own (mem/memory {:vars {'secret/x (atom :real)}})
        l (ldr/deny own #{'secret})]
    (let [d (ex-data-of #(ldr/find l (req :var "secret/x")))]
      (is (true? (:loader/denied d)))
      (is (= :var (:kind d)))
      (is (= "secret/x" (:name d))))
    (is (true? (:loader/denied (ex-data-of #(ldr/load l (req :var "secret/x"))))))
    (is (true? (:loader/denied (ex-data-of #(ldr/resolve l (req :var "secret/x"))))))
    ;; denial does not fall through to the ctx's own roots / delegate
    (is (identical? own (ldr/parent l)))
    (is (= :real @(load-name own :var "secret/x")) "the wrapped loader is untouched"))
  (testing "prefix matching is boundary-aware, not substring"
    (let [own (mem/memory {:vars {'secretary/x (atom 1), 'secret/x (atom 2)}})
          l (ldr/deny own #{'secret})]
      (is (= 1 @(load-name l :var "secretary/x")))
      (is (true? (:loader/denied (ex-data-of #(ldr/find l (req :var "secret/x")))))))))

(deftest test-case-5-self-first
  (let [host (mem/memory {:vars {'lib.shared/foo (atom :host)
                                 'other.lib/foo (atom :host-other)}})
        own (mem/memory {:vars {'lib.shared/foo (atom :own)}} {:parent host})]
    (is (= :host @(load-name own :var "lib.shared/foo"))
        "without self-first the delegate is consulted first")
    (let [sf (ldr/self-first own #{'lib})]
      (is (= :own @(load-name sf :var "lib.shared/foo")))
      (is (= :host-other @(load-name sf :var "other.lib/foo"))
          "outside the prefix L resolves exactly as it does alone"))))

(deftest test-case-6-composed-delegates
  (let [a (mem/memory {:vars {'a/x (atom :a)}})
        b (mem/memory {:vars {'b/x (atom :b)}})
        c (mem/memory {:vars {'c/x (atom :c)}})
        l (ldr/delegating a (ldr/pool [b c]))]
    (is (= :a @(load-name l :var "a/x")))
    (is (= :b @(load-name l :var "b/x")))
    (is (= :c @(load-name l :var "c/x")))
    (is (identical? a (ldr/parent l)))
    (is (nil? (ldr/parent a)))
    (is (= [l a] (->> (iterate ldr/parent l) (take-while some?) vec))
        "the parent walk terminates")
    (is (some #(str/starts-with? % "pool#") (:delegates (ldr/status l))))))

(deftest test-allow-whitelist
  (let [host (mem/memory {:vars {'kmet.tui.core/x (atom :tui)
                                 'other.lib/y (atom :other)}})
        l (ldr/allow host #{'kmet.tui})]
    (is (= :tui @(load-name l :var "kmet.tui.core/x")))
    (is (= [] (ldr/find l (req :ns "kmet.tui.core")))
        "a matching miss stays a miss; only non-matching names are denied")
    (is (true? (:loader/denied (ex-data-of #(ldr/find l (req :var "other.lib/y"))))))
    (is (= :other @(load-name host :var "other.lib/y")) "the wrapped loader is untouched")))

(deftest test-lifted-resolve-fn
  (let [cell (atom 1)
        l (ldr/->loader (fn [req]
                          (when (= "lib/y" (:name req))
                            {:kind :var :cell cell})))]
    (is (= 1 @(load-name l :var "lib/y")))
    (is (= [] (ldr/find l (req :var "lib/z"))))
    (is (= :loader/miss (:type (ex-data-of #(ldr/load l (req :var "lib/z"))))))
    (is (= :loader/bad-resolve-fn (:type (ex-data-of #(ldr/->loader 42)))))))

;; ─── §8.7: unload ──────────────────────────────────────────────────────────

(deftest test-case-7-unload
  (let [released (atom [])
        l (mem/memory {:vars {'lib/x (atom :v)}
                       :namespaces {"lib.ns" {:source "(ns lib.ns)"}}}
                      {:id "unload-fixture"
                       :release-fn (fn [_] (swap! released conj :ran) {:resources 1})})
        cell (load-name l :var "lib/x")
        ns-handle (load-name l :ns "lib.ns")]
    (is (= :v @cell))
    (let [rep (ldr/unload! l)]
      (is (true? (:unloaded rep)))
      (is (false? (:already rep)))
      (is (= {:namespaces 1 :registrations 0 :resources 1} (:released rep)))
      (is (false? (:raced rep)))
      (is (= [] (:errors rep)))
      (is (= [:ran] @released)))
    (is (true? (:already (ldr/unload! l))) "the second call is a no-op")
    (is (true? (ldr/unloaded? l)))
    (is (= :v @cell) "already-resolved definitions stay live")
    (is (= "(ns lib.ns)" (:source ns-handle)))
    (doseq [op [#(ldr/find l (req :var "lib/x"))
                #(ldr/resolve l (req :var "lib/x"))
                #(ldr/load l (req :var "lib/x"))]]
      (is (= :loader/unloaded (:type (ex-data-of op))) "usage after unload throws"))))

(deftest test-case-7b-unload-reports-teardown-errors
  (let [l (mem/memory {} {:release-fn (fn [_] (throw (ex-info "boom" {:x 1})))})
        rep (ldr/unload! l)]
    (is (true? (:unloaded rep)) "a teardown failure does not fail the postcondition")
    (is (= 1 (count (:errors rep))))
    (is (= "boom" (:message (first (:errors rep)))))))

(deftest test-case-7c-unload-never-blocks-and-reports-the-race
  (let [go (promise)
        started (promise)
        l (ldr/make-loader
           {:id "raced-fixture"
            :locate-fn (fn [_] [{:kind :ns :file "memory://slow"}])
            :ns-load-fn (fn [_ _ _]
                          (deliver started true)
                          @go
                          {:namespace 'slow.lib})})
        out (atom nil)
        worker (Thread. #(reset! out (ldr/load l (req :ns "slow.lib"))))]
    (.start worker)
    (is (true? (deref started 5000 false)))
    (let [rep (ldr/unload! l)]
      (is (true? (:unloaded rep)))
      (is (true? (:raced rep)))
      (is (= 1 (:in-flight rep))) "the race is reported, not waited on")
    (deliver go :go)
    (.join worker 5000)
    (is (= 'slow.lib (:namespace @out))
        "the in-flight load finishes into the closed loader")
    (is (= :loader/unloaded (:type (ex-data-of #(ldr/load l (req :ns "slow.lib"))))))))

;; ─── §8.8: resources ───────────────────────────────────────────────────────

(deftest test-case-8-resources
  (let [l (mem/memory {:resources {"lib/cfg.edn" "hello"
                                   "lib/b.bin" (.getBytes "bin" "UTF-8")}})
        hit (first-hit l :resource "lib/cfg.edn")]
    (is (= :resource (:kind hit)))
    (is (= "memory://lib/cfg.edn" (:url hit)))
    (is (= "hello" (slurp (ldr/open-hit l hit))))
    (is (= "hello" (slurp (ldr/load l (req :resource "lib/cfg.edn")))))
    (is (= "hello" (slurp (ldr/load l hit))))
    (is (= "bin" (slurp (ldr/open-hit l (first-hit l :resource "lib/b.bin")))))
    (is (nil? (ldr/open-hit l (first-hit l :var "no/such"))) "only resources open"))
  (testing "the host root serves classpath resources"
    (let [l (ldr/root)
          hit (first-hit l :resource "kmet/tui/tui.md")]
      (is (= :resource (:kind hit)))
      (is (pos? (count (slurp (ldr/open-hit l hit)))))
      (is (= [] (ldr/find l (req :resource "no/such/resource.txt"))))))
  (testing "self-first hits keep their owning loader for opening"
    (let [host (mem/memory {:resources {"lib/cfg.edn" "host"}})
          own (mem/memory {:resources {"lib/cfg.edn" "own"}} {:parent host})
          sf (ldr/self-first own #{'lib})]
      (is (= "own" (slurp (ldr/open-hit sf (first-hit sf :resource "lib/cfg.edn"))))))))

;; ─── §8.10-8.12: identity, data, laziness ──────────────────────────────────

(deftest test-case-10-dispatch-follows-the-value
  (let [cell2 (atom :ctx2)
        ctx2 (mem/memory {:vars {'lib/x cell2}
                          :resources {"lib/cfg.edn" "ctx2-content"}})
        ctx1 (mem/memory {:vars {'lib/x (atom :ctx1)}
                          :resources {"lib/cfg.edn" "ctx1-content"}})]
    (let [var-hit (first-hit ctx2 :var "lib/x")]
      (is (identical? ctx2 (:loader var-hit)))
      (is (identical? cell2 (ldr/load ctx1 var-hit))
          "loading ctx2's hit through ctx1 keeps ctx2's definition")
      (is (identical? cell2 (ldr/resolve ctx1 (req :var "lib/x")))))
    (let [res-hit (first-hit ctx2 :resource "lib/cfg.edn")]
      (is (= "ctx2-content" (slurp (ldr/load ctx1 res-hit)))
          "the hit's home loader owns opening"))))

(deftest test-case-11-hits-are-data
  (let [l (mem/memory {:vars {'lib/x (atom 1)}
                       :resources {"lib/cfg.edn" "c"}})
        v-hit (first-hit l :var "lib/x")
        r-hit (first-hit l :resource "lib/cfg.edn")]
    (is (= v-hit (first-hit l :var "lib/x")))
    (is (= r-hit (first-hit l :resource "lib/cfg.edn")))
    (is (string? (pr-str v-hit)))
    (is (string? (pr-str r-hit)))
    (doseq [hit [v-hit r-hit]
            v (vals hit)]
      (is (not (fn? v)))
      (is (not (instance? java.io.InputStream v))))
    ;; load from a hit == load from a request
    (is (identical? (ldr/load l (req :var "lib/x")) (ldr/load l v-hit)))
    (is (= (slurp (ldr/load l (req :resource "lib/cfg.edn")))
           (slurp (ldr/load l r-hit))))))

(deftest test-case-12-eager-construction-lazy-load
  (testing "loader construction validates roots eagerly"
    (is (= :loader/bad-root
           (:type (ex-data-of #(ldr/url-search ["/no/such/kmet-loader-root"])))))
    (is (some? (first-hit (ldr/url-search ["src"]) :ns "kmet.loader.core"))))
  (testing "a source that disappears after find fails at load, not silently"
    (let [src (atom {:namespaces {"vanish.lib" {:source "(ns vanish.lib)"}}})
          l (mem/memory src)
          hit (first-hit l :ns "vanish.lib")]
      (is (some? hit))
      (is (= "memory://vanish.lib" (:file hit)))
      (swap! src update :namespaces dissoc "vanish.lib")
      (is (= :loader/unreadable (:type (ex-data-of #(ldr/load l hit)))))))
  (testing "a located ns with no code backend is unreadable, not silently loaded"
    (let [l (ldr/url-search ["src"])]
      (is (= :loader/unreadable
             (:type (ex-data-of #(ldr/load l (req :ns "kmet.loader.core")))))))))

;; ─── Link table, status, ambient tier, concurrency ─────────────────────────

(deftest test-memory-handles
  (let [handle {:linked 'elsewhere}
        l (mem/memory {:handles {"shared.lib" handle}
                       :namespaces {"src.lib" {:source "(ns src.lib)"}}})]
    (is (identical? handle (ldr/load l {:kind :ns :name "shared.lib"}))
        "a :handles entry resolves by reference, without reading")
    (is (identical? handle (ldr/resolve l (req :ns "shared.lib"))))
    (is (= "(ns src.lib)" (:source (load-name l :ns "src.lib"))))))

(deftest test-link-table-semantics
  (let [cell (atom 1)
        l (mem/memory {:vars {'lib/x cell}})]
    (is (some? (first-hit l :var "lib/x")))
    (is (nil? (ldr/resolve l (req :var "lib/x"))) "find alone installs nothing")
    (is (identical? cell (ldr/load l (req :var "lib/x"))))
    (is (identical? cell (ldr/resolve l (req :var "lib/x"))))
    (is (= 1 (:loaded (ldr/status l))))))

(deftest test-status
  (let [parent (mem/memory {})
        l (mem/memory {:vars {'lib/x (atom 1)}
                       :namespaces {"lib.ns" {:source "(ns lib.ns)"}}}
                      {:id "status-fixture" :parent parent})]
    (load-name l :var "lib/x")
    (load-name l :ns "lib.ns")
    (let [s (ldr/status l)]
      (is (= "status-fixture" (:loader-id s)))
      (is (= 1 (:loaded-namespaces s)))
      (is (= 2 (:loaded s)))
      (is (= 0 (:in-flight s)))
      (is (false? (:unloaded? s)))
      (is (true? (:parent-loaded? s)))
      (is (map? (:delegate s)) "the delegate's status, one level")
      (is (nil? (:delegate (:delegate s)))))
    (ldr/unload! parent)
    (is (false? (:parent-loaded? (ldr/status l))))))

(deftest test-ambient-loader
  (is (identical? (ldr/root) (ldr/current-loader)))
  (let [l (mem/memory {})]
    (ldr/with-loader l
      (is (identical? l (ldr/current-loader)))
      (is (identical? l (deref (future (ldr/current-loader)) 5000 nil))
          "binding-conveying futures inherit the ambient loader"))
    (is (identical? (ldr/root) (ldr/current-loader))))
  (ldr/with-loader nil
    (is (identical? (ldr/root) (ldr/current-loader)))))

(deftest test-circular-load-detection
  (let [loader (ldr/make-loader
                {:locate-fn (fn [_] [{:kind :ns :file "memory://self"}])
                 :ns-load-fn (fn [home _ _]
                               (ldr/load home (req :ns "self.lib")))})]
    (is (= :loader/circular
           (:type (ex-data-of #(ldr/load loader (req :ns "self.lib"))))))))

(deftest test-concurrent-same-key-loads-once
  (let [inits (atom 0)
        go (promise)
        l (ldr/make-loader
           {:locate-fn (fn [_] [{:kind :ns :file "memory://x"}])
            :ns-load-fn (fn [_ _ _]
                          (swap! inits inc)
                          (deliver go true)
                          (Thread/sleep 50)
                          {:namespace 'x})})
        h1 (atom nil)
        h2 (atom nil)
        t1 (Thread. #(reset! h1 (ldr/load l (req :ns "x"))))
        t2 (Thread. #(do (deref go 5000 nil) (reset! h2 (ldr/load l (req :ns "x")))))]
    (.start t1)
    (.start t2)
    (.join t1 5000)
    (.join t2 5000)
    (is (= 1 @inits) "the in-flight claim dedupes initialization")
    (is (identical? @h1 @h2))))

(deftest test-request-validation
  (let [l (mem/memory {})]
    (is (= :loader/bad-request (:type (ex-data-of #(ldr/find l {:kind :wat :name "x"})))))
    (is (= :loader/bad-request (:type (ex-data-of #(ldr/find l {:kind :var})))))))

(deftest test-as-classloader-unsupported-host
  (let [l (mem/memory {})]
    (is (= :loader/no-classloader (:type (ex-data-of #(ldr/as-classloader l)))))
    (is (nil? (ldr/context l)))))

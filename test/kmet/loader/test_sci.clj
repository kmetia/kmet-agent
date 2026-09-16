(ns kmet.loader.test-sci
  "Conformance for the SCI backend (kmet.loader.sci): the code-path
   cases of loader.md §8 — v1/v2 isolation (1), shared by reference (3),
   defining-ctx inheritance (9), the find/load read discipline (11) and a
   vanishing source (12) — plus the backend's own contract: `require`
   routed through the loader, injected namespaces, policies, resources,
   unload and the eval seam.

   Case 9 note: on SCI a dynamic `require` reached through a value follows
   the *ambient* context (loader.md §3), so the conformance shape is the
   lexical one — app1's `(:require [lib1])` is compiled against ctx1's
   lib1, and ctx2 calling app1/f still sees ctx1's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.loader.core :as ldr]
            [kmet.loader.sci :as lsci]
            [sci.core :as sci]))

(def ^:private shared-cell
  "A host value with identity, shared into contexts by sci/copy-var*."
  (atom 1))

(defn- ctx-of [l] (ldr/context l))

(defn- load-ns [l nm] (ldr/load l {:kind :ns :name nm}))

(defn- call-var [l sym] ((sci/resolve (ctx-of l) sym)))

(defn- err [f]
  (try
    (f)
    nil
    (catch Throwable e e)))

(defn- chain [e]
  (take-while some? (iterate ex-cause e)))

(defn- data-of [e]
  (try
    (ex-data e)
    (catch Throwable _ nil)))

(defn- chain-type? [e t]
  (boolean (some #(= t (:type (data-of %))) (chain e))))

(defn- chain-denied? [e]
  (boolean (some #(:loader/denied (data-of %)) (chain e))))

(defn- chain-message? [e s]
  (boolean (some #(str/includes? (str (ex-message %)) s) (chain e))))

(defn- srcs
  "A provider over SRC-S (ns-sym → {:file … :source …}) that records every
   lookup in CALLS."
  [src-map calls]
  (fn [ns-sym]
    (swap! calls conj ns-sym)
    (get src-map ns-sym)))

;; ─── §8 code-path cases ────────────────────────────────────────────────────

(deftest test-case-1-v1-v2-isolation
  (let [v1 (lsci/sci-loader {:id "v1"
                             :sources {'lib {:file "lib-v1.clj"
                                             :source "(ns lib) (defn version [] :v1)"}}})
        v2 (lsci/sci-loader {:id "v2"
                             :sources {'lib {:file "lib-v2.clj"
                                             :source "(ns lib) (defn version [] :v2)"}}})]
    (load-ns v1 "lib")
    (load-ns v2 "lib")
    (is (= :v1 (call-var v1 'lib/version)))
    (is (= :v2 (call-var v2 'lib/version)))
    (is (not (identical? (sci/resolve (ctx-of v1) 'lib/version)
                         (sci/resolve (ctx-of v2) 'lib/version))))))

(deftest test-case-3-shared-by-reference
  (let [v (sci/copy-var* #'shared-cell (sci/create-ns 'shared))
        l (lsci/sci-loader {:id "share" :namespaces {'shared {'cell v}}})]
    (is (identical? v (ldr/load l {:kind :var :name "shared/cell"}))
        "the injected sci var comes back, not a copy")
    (is (identical? shared-cell (deref (ldr/load l {:kind :var :name "shared/cell"})))
        "and its root is the same atom")
    (is (identical? v (ldr/resolve l {:kind :var :name "shared/cell"})))))

(deftest test-case-9-defining-ctx-inheritance
  (let [ctx1 (lsci/sci-loader
              {:id "ctx1"
               :sources {'lib1 {:file "lib1.clj" :source "(ns lib1) (defn where [] :lib1-ctx1)"}
                         'app1 {:file "app1.clj"
                                :source "(ns app1 (:require [lib1])) (defn f [] (lib1/where))"}}})
        _ (load-ns ctx1 "app1")
        app1-ns (get-in @(:env (ctx-of ctx1)) [:namespaces 'app1])
        ctx2 (lsci/sci-loader
              {:id "ctx2"
               :sources {'lib1 {:file "lib2.clj" :source "(ns lib1) (defn where [] :lib2-ctx2)"}}
               :namespaces {'app1 app1-ns}})]
    (is (= :lib1-ctx1 (call-var ctx1 'app1/f)))
    (is (= :lib1-ctx1 (call-var ctx2 'app1/f))
        "f compiled in ctx1 keeps ctx1's lib1 when ctx2 calls it")
    (is (nil? (sci/find-ns (ctx-of ctx2) 'lib1))
        "ctx2's own lib1 was never needed")))

(deftest test-case-11-find-locates-load-reads
  (let [forces (atom 0)
        l (lsci/sci-loader
           {:id "lazy"
            :sources (fn [ns-sym]
                       (when (= ns-sym 'lazy.ns)
                         {:file "lazy.clj"
                          :source (fn []
                                    (swap! forces inc)
                                    "(ns lazy.ns) (def x :read)")}))})]
    (let [hit (first (ldr/find l {:kind :ns :name "lazy.ns"}))]
      (is (= :ns (:kind hit)))
      (is (= "lazy.clj" (:file hit)))
      (is (string? (pr-str hit)))
      (is (identical? l (:loader hit)))
      (is (= 0 @forces) "find locates without reading"))
    (is (= [] (ldr/find l {:kind :ns :name "other.ns"})))
    (load-ns l "lazy.ns")
    (is (= 1 @forces) "load reads exactly once")
    (is (= :read (deref (sci/resolve (ctx-of l) 'lazy.ns/x))))))

(deftest test-case-12-vanishing-source
  (let [src (atom {'vanish {:file "vanish.clj" :source "(ns vanish)"}})
        l (lsci/sci-loader {:id "vanish" :sources (fn [ns-sym] (get @src ns-sym))})
        hit (first (ldr/find l {:kind :ns :name "vanish"}))]
    (is (some? hit))
    (swap! src dissoc 'vanish)
    (is (= :loader/unreadable (:type (ex-data (err #(ldr/load l hit))))))))

;; ─── require / linkage ─────────────────────────────────────────────────────

(deftest test-require-routes-through-the-loader
  (let [calls (atom [])
        l (lsci/sci-loader {:id "req"
                            :sources (srcs {'a {:file "a.clj"
                                                :source "(ns a (:require [b])) (defn v [] (b/v))"}
                                            'b {:file "b.clj" :source "(ns b) (defn v [] :b)"}}
                                           calls)})]
    (load-ns l "a")
    (is (= '[a a b b] @calls) "each namespace is located (a) then read (a)")
    (is (= :b (call-var l 'a/v)))
    (is (some? (sci/find-ns (ctx-of l) 'b)))
    (is (some? (ldr/resolve l {:kind :ns :name "b"}))
        "the nested require installed b's link")
    (is (some? (ldr/resolve l {:kind :ns :name "a"})))))

(deftest test-linked-namespace-is-not-re-read
  (let [calls (atom [])
        l (lsci/sci-loader {:id "twice"
                            :sources (srcs {'x {:file "x.clj" :source "(ns x) (def v 1)"}} calls)})]
    (load-ns l "x")
    (load-ns l "x")
    (ldr/find l {:kind :ns :name "x"})
    (is (= '[x x] @calls) "locate + read once; the link table serves the rest")))

(deftest test-require-of-a-host-namespace-fails-actionably
  ;; On babashka the root is invisible from inside an evaluation (its
  ;; registry functions are SCI-scoped there), on jolt it is visible —
  ;; either way the require must fail as :loader/unreadable with the
  ;; "inject at construction" diagnostic, never silently load nothing.
  (let [l (lsci/sci-loader
           {:id "ref"
            :parent (ldr/delegating (ldr/isolated) (ldr/root))
            :sources {'app {:file "app.clj" :source "(ns app (:require [kmet.loader.core]))"}}})
        e (err #(load-ns l "app"))]
    (is (some? e))
    (is (chain-message? e "inject"))
    (is (chain-type? e :loader/unreadable))))

(deftest test-require-respects-policies
  (let [l (lsci/sci-loader
           {:id "policy"
            :parent (ldr/deny (ldr/isolated) #{'secret})
            :sources {'app {:file "app.clj" :source "(ns app (:require [secret.lib]))"}
                      'secret.lib {:file "secret.clj" :source "(ns secret.lib)"}}})
        e (err #(load-ns l "app"))]
    (is (some? e))
    (is (chain-denied? e)
        "the parent's deny policy applies to a nested require")))

(deftest test-require-after-unload-fails
  (let [l (lsci/sci-loader {:id "closed"
                            :sources {'a {:file "a.clj" :source "(ns a)"}}})]
    (ldr/unload! l)
    (let [e (err #(sci/eval-string* (ctx-of l) "(require 'a)"))]
      (is (some? e))
      (is (chain-type? e :loader/unloaded)))))

(deftest test-require-cycle-terminates
  (let [l (lsci/sci-loader
           {:id "cycle"
            :sources {'a {:file "a.clj" :source "(ns a (:require [b])) (defn fa [] :a)"}
                      'b {:file "b.clj" :source "(ns b (:require [a])) (defn fb [] :b)"}}})]
    (is (some? (load-ns l "a")))
    (is (= :a (call-var l 'a/fa)))
    (is (= :b (call-var l 'b/fb)))))

(deftest test-injected-namespaces-are-not-re-read
  (let [calls (atom [])
        l (lsci/sci-loader
           {:id "inj"
            :sources (srcs {'app {:file "app.clj"
                                  :source "(ns app (:require [shared])) (def y shared/x)"}}
                           calls)
            :namespaces {'shared {'x 42}}})]
    (load-ns l "app")
    (is (= '[app app] @calls) "SCI never asks the provider for an injected namespace")
    (is (= 42 (deref (sci/resolve (ctx-of l) 'app/y))))))

;; ─── Requests, lifecycle, seams ────────────────────────────────────────────

(deftest test-find-without-a-parent-is-hermetic
  (let [l (lsci/sci-loader {:id "hermetic"})]
    (is (= [] (ldr/find l {:kind :ns :name "kmet.loader.core"})))
    (is (= [] (ldr/find l {:kind :var :name "kmet.loader.core/root"})))
    (is (= :loader/miss (:type (ex-data (err #(load-ns l "kmet.loader.core"))))))))

(deftest test-var-and-class-locate
  (let [l (lsci/sci-loader {:id "varc"
                            :sources {'v {:file "v.clj" :source "(ns v) (defn f [] :f)"}}})]
    (is (= [] (ldr/find l {:kind :var :name "v/f"}))
        "a var is not locatable before its namespace is loaded")
    (load-ns l "v")
    (is (some? (:cell (first (ldr/find l {:kind :var :name "v/f"})))))
    (is (= :f (call-var l 'v/f))))
  (let [l (lsci/sci-loader {:id "cls"})]
    (is (= [] (ldr/find l {:kind :class :name "java.time.Instant"})))
    (sci/add-class! (ctx-of l) 'java.time.Instant java.time.Instant)
    (is (identical? java.time.Instant
                    (:class (first (ldr/find l {:kind :class :name "java.time.Instant"})))))
    (is (identical? java.time.Instant (ldr/load l {:kind :class :name "java.time.Instant"})))))

(deftest test-resources
  (let [f (io/file "deps.edn")
        l (lsci/sci-loader {:id "res"
                            :resources (fn [nm]
                                         (when (= nm "kmet/deps.edn")
                                           {:url (str (io/as-url f))}))})]
    (let [hit (first (ldr/find l {:kind :resource :name "kmet/deps.edn"}))]
      (is (= :resource (:kind hit)))
      (is (string? (:url hit)))
      (is (pos? (count (slurp (ldr/open-hit l hit)))))
      (is (= (slurp f) (slurp (ldr/load l hit)))))
    (is (= [] (ldr/find l {:kind :resource :name "no/such.edn"})))))

(deftest test-unload
  (let [l (lsci/sci-loader {:id "unload"
                            :sources {'u {:file "u.clj" :source "(ns u) (defn g [] :u)"}}})]
    (load-ns l "u")
    (let [rep (ldr/unload! l)]
      (is (true? (:unloaded rep)))
      (is (false? (:already rep)))
      (is (= 1 (:namespaces (:released rep))))
      (is (= [] (:errors rep))))
    (is (true? (:already (ldr/unload! l))))
    (is (= :u (call-var l 'u/g)) "already-loaded code stays live")
    (is (= :loader/unloaded (:type (ex-data (err #(ldr/find l {:kind :ns :name "u"}))))))
    (is (= :loader/unloaded (:type (ex-data (err #(load-ns l "u"))))))))

(deftest test-eval-seam
  (let [seen (atom [])
        l (lsci/sci-loader
           {:id "eval"
            :sources {'e {:file "e.clj" :source "(ns e) (def x 1)"}}
            :eval-fn (fn [ctx opts]
                       (swap! seen conj opts)
                       (sci/eval-string* ctx (:source opts)))})]
    (load-ns l "e")
    (is (= 1 (count @seen)))
    (is (= 'e (:namespace (first @seen))))
    (is (= "e.clj" (:file (first @seen))))
    (is (true? (:load? (first @seen))))
    (is (= 1 (deref (sci/resolve (ctx-of l) 'e/x))))))

(deftest test-constructor-sources-forms
  (testing "a map of sources, string key, plain string source"
    (let [l (lsci/sci-loader {:id "map" :sources {"lib" "(ns lib) (def x 1)"}})]
      (load-ns l "lib")
      (is (= 1 (deref (sci/resolve (ctx-of l) 'lib/x))))))
  (testing "an invalid :sources is rejected at construction"
    (is (= :loader/bad-sources (:type (ex-data (err #(lsci/sci-loader {:sources 42}))))))))

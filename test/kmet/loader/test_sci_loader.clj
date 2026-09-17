(ns kmet.loader.test-sci-loader
  "Conformance for the SCI backend (kmet.loader.sci-loader): the code-path
   cases of loader.md §8 — v1/v2 isolation (1), shared by reference (3),
   defining-ctx inheritance (9), the find/load read discipline (11), a
   vanishing source (12), and the private-load semantics the Jolt suite
   pinned first (13 concurrent contexts, 14 unload, 15 a failed load, 16
   context-owned names, 18 an unservable requirement) — plus the backend's
   own contract: `require` routed through the loader, injected namespaces,
   policies, resources, unload and the eval seam. Case 17 is host-only
   (the Jolt root loads host sources on demand); kmet's root answers
   loaded host namespaces only, so this backend injects shared names and
   fails an unservable require.

   Case 9 note: on SCI a dynamic `require` reached through a value follows
   the *ambient* context (loader.md §3), so the conformance shape is the
   lexical one — app1's `(:require [lib1])` is compiled against ctx1's
   lib1, and ctx2 calling app1/f still sees ctx1's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.loader.core :as ldr]
            [kmet.loader.sci-loader :as lsci]
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

;; §8.13-8.18: the private-load semantics the Jolt native suite pinned
;; first (loaderconf cases 13-18).
(deftest test-case-13-concurrent-per-context-loads
  ;; Several contexts race on one namespace name. Each has its own SCI
  ;; context and its own source, so every load must end with its own
  ;; definition — no cross-talk through the shared in-flight bookkeeping.
  (let [mk (fn [n]
             (lsci/sci-loader
              {:id (str "conc" n)
               :sources {'conc {:file (str "conc" n ".clj")
                                :source (fn []
                                          (Thread/sleep 5)
                                          (str "(ns conc) (defn who [] :c" n ")"))}}}))
        ls (mapv mk (range 4))
        loaded (mapv deref (mapv #(future (load-ns % "conc")) ls))]
    (is (every? some? loaded))
    (is (= [:c0 :c1 :c2 :c3] (mapv #(call-var % 'conc/who) ls)))
    (is (apply distinct? (mapv #(sci/resolve (ctx-of %) 'conc/who) ls))
        "each context holds its own var")))

(deftest test-case-14-unload-releases-without-touching-siblings
  (let [mk (fn [id] (lsci/sci-loader {:id id :parent (ldr/root)}))
        s1 (mk "u14a")
        s2 (mk "u14b")]
    (is (some? (load-ns s1 "clojure.string")))
    (is (some? (ldr/load s1 {:kind :var :name "clojure.string/join"})))
    (is (some? (load-ns s2 "clojure.string")))
    (is (some? (ldr/load s2 {:kind :var :name "clojure.string/join"})))
    (let [rep (ldr/unload! s1)]
      (is (true? (:unloaded rep)))
      (is (= 1 (:namespaces (:released rep))) "its namespace link is counted out")
      (is (= 0 (:in-flight rep))))
    (is (= :loader/unloaded
           (:type (ex-data (err #(ldr/load s1 {:kind :var :name "clojure.string/join"})))))
        "the unloaded loader refuses new loads")
    (is (identical? (ns-resolve 'clojure.string 'join)
                    (ldr/resolve s2 {:kind :var :name "clojure.string/join"}))
        "the sibling's link is untouched")
    (is (= "ab" ((ldr/load s2 {:kind :var :name "clojure.string/join"}) ["a" "b"]))
        "and the definition stays callable")
    (is (some? (load-ns (mk "u14c") "clojure.string"))
        "a fresh loader loads the same name again")))

(deftest test-case-15-failed-load-leaves-nothing-and-retries
  (testing "a source that throws after (ns …) leaves no half-built namespace"
    (let [src (atom "(ns boom15) (def x (throw (ex-info \"broken\" {})))")
          l (lsci/sci-loader {:id "fail15"
                              :sources (fn [s]
                                         (when (= s 'boom15)
                                           {:file "boom15.clj" :source @src}))})]
      (is (some? (err #(load-ns l "boom15"))))
      (is (nil? (ldr/resolve l {:kind :ns :name "boom15"})) "no link")
      (is (nil? (sci/find-ns (ctx-of l) 'boom15)) "nothing left in the context")
      (is (= 0 (:in-flight (ldr/status l))) "the in-flight claim was released")
      (reset! src "(ns boom15) (def x :fixed)")
      (is (some? (load-ns l "boom15")))
      (is (= :fixed (deref (sci/resolve (ctx-of l) 'boom15/x)))
          "the retry read the fixed source instead of re-using the broken one")))
  (testing "a failed nested require leaves the requester uninstalled too"
    (let [srcs (atom {'app15 {:file "app15.clj"
                              :source "(ns app15 (:require [dep15])) (def v dep15/x)"}})
          l (lsci/sci-loader {:id "fail15b" :sources (fn [s] (get @srcs s))})]
      (is (chain-type? (err #(load-ns l "app15")) :loader/unreadable)
          "the requirement error survives the host's wrapper")
      (is (nil? (sci/find-ns (ctx-of l) 'app15)))
      (swap! srcs assoc 'dep15 {:file "dep15.clj" :source "(ns dep15) (def x :dep)"})
      (is (some? (load-ns l "app15")))
      (is (= :dep (deref (sci/resolve (ctx-of l) 'app15/v)))))))

(deftest test-case-16-a-contexts-names-are-invisible-through-the-root
  (let [l (lsci/sci-loader {:id "priv16"
                            :sources {'priv16 {:file "priv16.clj"
                                               :source "(ns priv16) (def v :private)"}}})]
    (load-ns l "priv16")
    (is (= :private (deref (sci/resolve (ctx-of l) 'priv16/v))) "the owner sees it")
    (is (= [] (ldr/find (ldr/root) {:kind :ns :name "priv16"})))
    (is (= [] (ldr/find (ldr/root) {:kind :var :name "priv16/v"})))
    (is (= :loader/miss
           (:type (ex-data (err #(ldr/load (ldr/root) {:kind :ns :name "priv16"}))))))
    (let [other (lsci/sci-loader {:id "other16" :parent (ldr/root)})]
      (is (= [] (ldr/find other {:kind :ns :name "priv16"})))
      (is (= :loader/miss (:type (ex-data (err #(load-ns other "priv16")))))))))

(deftest test-case-18-a-requirement-the-loader-cannot-serve
  (let [srcs {'app18 {:file "app18.clj"
                      :source "(ns app18 (:require [nope.ns])) (def v nope.ns/x)"}}
        l (lsci/sci-loader {:id "req18" :sources srcs})
        e (err #(load-ns l "app18"))]
    (is (some? e))
    (is (chain-type? e :loader/unreadable))
    (is (chain-message? e "inject") "the message says how to provide it")
    (is (nil? (ldr/resolve l {:kind :ns :name "app18"})) "nothing was linked")
    (is (nil? (sci/find-ns (ctx-of l) 'app18)) "and nothing was left behind")
    (testing "the same source loads once the requirement can be served"
      (let [l2 (lsci/sci-loader
                {:id "req18b"
                 :sources (assoc srcs 'nope.ns {:file "nope.clj"
                                                :source "(ns nope.ns) (def x :served)"})})]
        (is (some? (load-ns l2 "app18")))
        (is (= :served (deref (sci/resolve (ctx-of l2) 'app18/v))))))))

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

(deftest test-base-forks-share-injected-namespaces-and-isolate-defs
  ;; a :base context is forked per loader: injected namespaces/classes are
  ;; shared (one copy), definitions and :namespaces overrides stay in the
  ;; fork, and each fork routes requires through its own :load-fn.
  (let [base (sci/init {:namespaces {'shared {'x 42}}})
        a (lsci/sci-loader
           {:id "fork-a"
            :base base
            :sources {'app {:file "app-a.clj"
                            :source "(ns app (:require [shared] [dep])) (def v [shared/x dep/y])"}
                      'dep {:file "dep-a.clj" :source "(ns dep) (def y :a)"}}})
        b (lsci/sci-loader
           {:id "fork-b"
            :base base
            :sources {'app {:file "app-b.clj"
                            :source "(ns app (:require [shared] [dep])) (def v [shared/x dep/y])"}
                      'dep {:file "dep-b.clj" :source "(ns dep) (def y :b)"}}})]
    (testing "each fork serves its own namespaces through its own :load-fn"
      (load-ns a "app")
      (load-ns b "app")
      (is (= [42 :a] (deref (sci/resolve (ctx-of a) 'app/v))))
      (is (= [42 :b] (deref (sci/resolve (ctx-of b) 'app/v)))))
    (testing "the injected namespace is visible in both forks; the base is untouched"
      (is (= 42 (sci/eval-string* (ctx-of a) "shared/x")))
      (is (= 42 (sci/eval-string* (ctx-of b) "shared/x")))
      (is (nil? (sci/find-ns base 'app)))
      (is (nil? (sci/find-ns base 'dep))))
    (testing "a per-loader :namespaces override is local to its fork"
      (let [c (lsci/sci-loader {:id "fork-c"
                                :base base
                                :namespaces {'shared {'x :overridden}}})]
        (is (= :overridden (sci/eval-string* (ctx-of c) "shared/x")))
        (is (= 42 (sci/eval-string* (ctx-of a) "shared/x"))
            "sibling forks and the base keep the base's value")
        (is (= 42 (sci/eval-string* base "shared/x")))))
    (testing "classes added to the base are inherited by later forks"
      (sci/add-class! base 'java.time.Instant java.time.Instant)
      (let [d (lsci/sci-loader {:id "fork-d" :base base})]
        (is (identical? java.time.Instant
                        (:class (first (ldr/find d {:kind :class :name "java.time.Instant"})))))))))

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

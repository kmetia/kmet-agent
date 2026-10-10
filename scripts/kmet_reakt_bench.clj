;; reakt-layer microbenchmarks behind perf.md §14: per-op costs of the
;; reactive engine (kmet.libs.reakt + kmet.libs.weak) and the scaling that
;; points at the algorithmic candidates. Same steady-state method as
;; kmet_perf_bench.clj (§6.2b): 2 warming rounds, median of 5 rounds;
;; report same-run host ratios, not absolutes across machines.
;;
;;   bb   scripts/kmet_reakt_bench.clj
;;   jolt scripts/kmet_reakt_bench.clj
(ns kmet-reakt-bench
  (:require [kmet.libs.reakt :as r]
            [kmet.libs.weak :as weak]))

(defn- now [] (System/nanoTime))
(defn- median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn- per
  [label n f]
  (dotimes [_ 2] (dotimes [_ n] (f)))
  (let [ts (doall (for [_ (range 5)]
                    (let [t (now)]
                      (dotimes [_ n] (f))
                      (/ (double (- (now) t)) n 1000.0))))]
    (println (format "%-48s %10.3f us" label (median ts)))))

(println "host:" (or (System/getProperty "babashka.version") "jolt"))

;; ── Type checks: the classification primitives ────────────────────────────
(def a (atom 0))
(def rx (r/make-reaction (fn [] (r/tracked-deref a))))
(deref rx)
(per "instance? String" 200000 #(instance? String "x"))
(per "instance? Object" 200000 #(instance? Object a))
(per "instance? clojure.lang.Atom" 200000 #(instance? clojure.lang.Atom a))
(per "instance? clojure.lang.IRef" 200000 #(instance? clojure.lang.IRef a))
(per "instance? clojure.lang.IDeref" 200000 #(instance? clojure.lang.IDeref a))
(per "satisfies? RXRef (atom)" 200000 #(satisfies? r/RXRef a))
(per "satisfies? RXRef (reaction)" 200000 #(satisfies? r/RXRef rx))
(per "trackable-ref? (atom)" 200000 #(r/trackable-ref? a))
(per "trackable-ref? (reaction)" 200000 #(r/trackable-ref? rx))
(per "reaction? (reaction)" 200000 #(r/reaction? rx))
(per "-cell (reaction)" 200000 #(r/-cell rx))

;; ── Tracked derefs ────────────────────────────────────────────────────────
;; The scope is the two capture buckets tracked-deref writes and track-render
;; reads: [(atom {}) (atom {})] — plain refs, then library refs.
(def sc [(atom {}) (atom {})])
(per "tracked-deref atom, no scope" 200000 #(r/tracked-deref a))
(binding [r/*tracking-scope* sc]
  (per "tracked-deref atom, scope bound" 200000 #(r/tracked-deref a))
  (per "tracked-deref reaction, scope bound" 200000 #(r/tracked-deref rx)))

;; ── Reaction run and lifecycle ────────────────────────────────────────────
(per "deref idle reaction" 200000 #(deref rx))
(per "flush! empty queue" 200000 #(r/flush!))
(def rx0 (r/make-reaction (fn [] 1)))
(deref rx0)
(per "force-run! 0-dep body" 20000 #(r/force-run! rx0))
(def rxu (r/make-reaction (fn [] (count [1 2])) {:rerun-without-deps? true}))
(deref rxu)
(per "deref :rerun-without-deps? body" 20000 #(deref rxu))
(per "make-reaction (never run)" 20000 #(r/dispose! (r/make-reaction (fn [] 1))))

;; ── Dep-count scaling: force-run! includes the watch-set diff ─────────────
(doseq [k [1 5 20 50]]
  (let [as (vec (for [_ (range k)] (atom 0)))
        rk (r/make-reaction #(reduce + (map r/tracked-deref as)))]
    @rk
    (per (str "force-run! reaction, " k " deps")
         (if (> k 20) 5000 20000)
         #(r/-force-run rk))))

;; ── Invalidation storms: dirty N watched atoms then settle, per cycle ─────
(def atoms (vec (for [_ (range 500)] (atom 0))))
(def rxs (mapv (fn [x] (r/make-reaction #(r/tracked-deref x))) atoms))
(doseq [x rxs] (deref x))
(doseq [[k n] [[1 5000] [50 200] [500 20]]]
  (per (str "dirty " k " watched atoms + settle") n
       #(do (dotimes [i k] (swap! (atoms i) inc))
            (r/flush!))))
(per "some identical? over 500 vector" 20000
     #(some (fn [x] (identical? x -1)) (vec (range 500))))

;; ── Weak registry ─────────────────────────────────────────────────────────
(per "weak/register! refresh" 20000 #(weak/register! ::bench rx0 {} (fn [_ _])))
(per "weak/sweep! (empty)" 200000 #(weak/sweep!))
(per "weak/subject" 200000 #(weak/subject ::bench))

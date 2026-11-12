# Lock + blocking file I/O from many futures is ~7–11× slower than the JVM on Jolt

Filed as [jolt#1654](https://github.com/jolt-lang/jolt/issues/1654).

## Summary

400 futures each take a `ReentrantLock` and append one line through
`io/output-stream :append true`:

| workload | Jolt | JVM 25 | babashka 1.13.229 |
|---|---:|---:|---:|
| futures + lock + atom inc | 14–18 ms | 4–9 ms | 3.3 ms |
| futures + file append, no lock | 20–26 ms | 3–6 ms | 3.1–3.8 ms |
| **futures + lock + file append** | **196–320 ms** | **26–30 ms** | **16–17 ms** |

The lock alone and the file I/O alone are only a few times slower; the
combination — holding a lock across blocking file I/O from many futures —
is ~7–11× the JVM and ~12–19× bb.

This is not a correctness issue; the appends serialize correctly. kmet's
session journal uses exactly this pattern (`ReentrantLock` + output-stream
append), and `kmet.app.session.test-store/test-session-concurrent-appends`
is 331–541 ms on Jolt vs 43 ms on bb.

## Environment

- Jolt `v0.8.27-52-g7ed36439`
- Clojure `1.12.0-jolt`
- JVM control: Clojure `1.12.6`, Java `25.0.2`
- babashka `1.13.229`
- Linux x86_64
- Jolt AOT cache warm (second run)

## Reproduction

Self-contained script: `scripts/repros/jolt_lock_io_perf.clj`

```sh
jolt       scripts/repros/jolt_lock_io_perf.clj
bb         scripts/repros/jolt_lock_io_perf.clj
clojure -M scripts/repros/jolt_lock_io_perf.clj
```

Minimal form:

```clojure
(require '[babashka.fs :as fs]
         '[clojure.java.io :as io])

(def payload (.getBytes "x\n" "UTF-8"))
(def n 400)

(let [file (str (fs/create-temp-file))
      lock (java.util.concurrent.locks.ReentrantLock.)
      futures (doall
               (for [_ (range n)]
                 (future
                   (.lock lock)
                   (try
                     (with-open [os (io/output-stream file :append true)]
                       (.write os payload))
                     (finally (.unlock lock))))))]
  (doseq [f futures] @f))
```

Observed Jolt (three runs, min/median):

```
futures + lock + atom inc              min     14.7 ms  median     15.3 ms
futures + lock + file append           min    246.5 ms  median    261.0 ms
futures + file append, no lock         min     19.8 ms  median     20.9 ms
```

## Analysis

- Lock alone (`lock + atom inc`) is ~2–4× the JVM/bb.
- File I/O alone (`append, no lock`) is ~4–7× the JVM.
- Together they are ~7–11× the JVM and ~12–19× bb, so the cost is in
  holding the lock across blocking file I/O under future contention.
- A `locking` monitor variant is also affected (earlier scratch run:
  ~190–360 ms), but the `ReentrantLock` case is the one kmet uses.
- Hypothesis: blocking file open/write/close while holding the lock from
  many futures causes extra scheduler/parking or runtime-lock overhead;
  the file path itself is fast when no lock is held. Not verified further.
- Real-world impact: session appends are deliberately serialized; a burst
  of concurrent bash results and messages pays the cost. Absolute impact
  is sub-second at 400 appends.

## Proposal

- Add the locked-append pattern to Jolt's benchmark/conformance suite, or
  confirm the slowdown is expected.
- Investigate the interaction between `ReentrantLock`/`locking`, futures,
  and blocking file I/O. If it is scheduler/fiber parking overhead, the
  fix may be in the lock/future path; if it is IO under contention, a
  faster append path may help.
- No API change is requested.

## Expected

Jolt should stay within a small factor of the JVM/bb (say ≤2×) for the
locked append pattern.

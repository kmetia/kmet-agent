# rewrite-clj parsing is ~17–27× slower on Jolt; clojure.tools.reader is ~13–18× slower

Filed as [jolt#1646](https://github.com/jolt-lang/jolt/issues/1646).

## Summary

`rewrite-clj.parser/parse-string` and `parse-string-all` are dramatically
slower on Jolt than on the JVM (or babashka). A 50-byte form parsed 5000
times takes ~0.8–1.1 s on Jolt and ~40–50 ms on the JVM/bb; a 1.28 MB
document takes ~3.4–3.8 s on Jolt and ~140–280 ms on the JVM/bb.

`clojure.tools.reader/read-string`, which rewrite-clj's reader sits on, is
also ~13–18× slower on Jolt, while `clojure.core/read-string` is at parity.
So part of the gap is below rewrite-clj.

## Environment

- Jolt `v0.8.27-52-g7ed36439` (installed build; `jolt --version`)
- Clojure `1.12.0-jolt`
- rewrite-clj `1.3.58`
- JVM control: Clojure `1.12.6`, Java `25.0.2`
- babashka control: `1.13.229`
- Linux x86_64
- Jolt AOT cache warm (second run; the first only compiles the script/deps)

## Reproduction

Self-contained script, no kmet code: `scripts/repros/rewrite_clj_parse_perf.clj`

```sh
clojure -M scripts/repros/rewrite_clj_parse_perf.clj
bb                 scripts/repros/rewrite_clj_parse_perf.clj
jolt               scripts/repros/rewrite_clj_parse_perf.clj
jolt               scripts/repros/rewrite_clj_parse_perf.clj   # AOT-warm run
```

Minimal form:

```clojure
(require '[rewrite-clj.parser :as p])
(let [form "(defn f [x] (let [y (inc x)] (when (pos? y) (* y 2))))"]
  (dotimes [_ 2]
    (let [t (System/nanoTime)]
      (dotimes [_ 5000] (p/parse-string form))
      (println (/ (- (System/nanoTime) t) 1e6) "ms"))))
```

## Results

Min of 3 warm runs across repeated runs:

| benchmark | JVM 25 | babashka 1.13.229 | Jolt |
|---|---:|---:|---:|
| `parse-string` × 5000, one small form | 29–47 ms | 38–51 ms | 818–1059 ms |
| `parse-string-all`, 1.28 MB / 4000 forms | 120–140 ms | 279–334 ms | 3.2–3.6 s |
| `clojure.tools.reader/read-string` × 5000 | 17–36 ms | 32–40 ms | 313–419 ms |
| `clojure.core/read-string` × 5000 (control) | 25–43 ms | 30–39 ms | 32–38 ms |

Real source tree (`parse-string-all` over the kmet tree, warm Jolt cache):

| corpus | JVM | babashka | Jolt |
|---|---:|---:|---:|
| 160 files, 2.8 MB | 151–168 ms | 162–169 ms | 3.9–4.6 s |
| 37 files, 0.7 MB | 35 ms | 38 ms | 1.03–1.30 s |

## Analysis

- The `clojure.core/read-string` control is the same speed on Jolt, bb and
  the JVM, so Jolt's built-in reader is fine. `clojure.tools.reader` is also
  ~13–18× slower on Jolt, so part of the gap is below rewrite-clj (likely its
  char-level `IndexingPushbackReader` path); the remaining factor is
  rewrite-clj's parser/node layer.
- Both `parse-string` (one form) and `parse-string-all` (a whole document)
  are affected, including a tiny form repeated 5000 times; this is not file
  IO, corpus size or GC.
- Real-world impact: `rewrite-clj.parser/parse-string-all` is 78% of
  `kmet.app.test-submit-guard`'s Jolt time (3.57 s of 4.56 s) and ~51% of
  `kmet.libs.test-self-contained` (0.86 s of 1.71 s). The rest of the latter
  is rewrite-clj node traversal, so ~95% of that namespace's Jolt time is
  rewrite-clj.
- Not a first-run/AOT artifact: the reported Jolt numbers are the second,
  AOT-warm run and are stable across repetitions.

## Expected

Jolt should be within a small factor of the JVM/bb here, as `read-string`
already is.

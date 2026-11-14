# Expose an enumeration of Jolt's modeled classes (`babashka.classes/all-classes` equivalent)

Filed as [jolt#1663](https://github.com/jolt-lang/jolt/issues/1663) on 2026-11-14.

Environment: Jolt `v0.8.27-52-g7ed36439`, Clojure `1.12.0-jolt`, Linux aarch64; babashka `1.13.229` for comparison.

Self-contained repro: `scripts/repros/jolt_all_classes.clj`.

## Summary

Jolt models a large, open set of JDK and Clojure classes, but there is no supported way to enumerate them. Babashka exposes `babashka.classes/all-classes`; Jolt has no equivalent. Tools that embed SCI, build a sandbox class map, or offer class-name completion must hard-code a subset and silently miss classes Jolt actually models.

## Motivation

kmet's script/eval tool copies the host class surface into SCI. On babashka it uses `babashka.classes/all-classes`; on the JVM it uses a short explicit list; on Jolt `host-class-map` returns `{}` because there is no class table (`modules/app/src/kmet/app/tools/script.cljc`). Scripts running on Jolt therefore cannot resolve host classes such as `System`, `Thread`, `StringBuilder`, `java.io.File` or `Collections`, even though Jolt models them. The same gap affects any SCI host or editor tool that wants Jolt's class surface.

## Reproduction

```sh
# babashka
bb -e '(require (quote [babashka.classes :as c])) (println (count (c/all-classes)))'
# => 658

# jolt
jolt -e '(require (quote [babashka.classes :as c]))'
# => FileNotFoundException: Could not locate babashka/classes.jolt ...
```

On Jolt, a class can be resolved only if its name is already known:

```clojure
(require '[jolt.host :as h])
(h/jolt-class-for "java.util.Collections")   ; works
(Class/forName "java.util.Collections")      ; works for registered classes
;; but there is no (h/all-classes), (h/class-names) or (h/class-table)
```

## Current state (v0.8.27-52-g7ed36439)

- `jolt.host/jolt-class-for` interns a Class for any supplied name; `Class/forName` throws for names with no host registration. Neither enumerates.
- `jolt.host` has `class-supers`, `class-ancestors` and `class-bases`, but no enumerator.
- `clojure.core`'s namespace map (`ns-map`) exposes 642 distinct class objects on this build. That is only a boot-time subset: the modeled graph has 992 rows, and the statics/constructor/value-tag registries add 38 canonical names (26 statics-only, 2 ctor-only, 10 value-tag-only), for 1,030 modeled names.
- An enumeration must include classes with no graph row: `java.util.Collections`, `java.util.Arrays`, `java.util.Objects`, `java.nio.file.Files`, `java.util.concurrent.Executors` (statics-only), `java.net.URL` (ctor-only), and `java.nio.file.WatchKey` (value-tag-only). A graph-only walk would omit exactly the classes a sandbox needs.
- The `jolt-class-for` interner table is not the answer: it accepts arbitrary spellings such as `no.such.Class`, so it contains typos and aliases, not the modeled surface.

## Proposed API

Expose from `jolt.host` (or a public `jolt.classes` namespace; naming is your call):

```clojure
(jolt.host/class-names)
;; => ("clojure.core.ArrayChunk" ... "java.util.ArrayList" ... "java.util.Collections" ...)
;; canonical JVM names, one per modeled class, sorted, no short aliases

(jolt.host/all-classes)
;; => Class objects, same set and order
;; (mapv #(.getName %) (jolt.host/all-classes)) = (jolt.host/class-names)
```

- `all-classes` is the required babashka equivalent; `class-names` is useful for callers building `{symbol Class}` maps. A single `class-table` returning `{canonical-name Class}` would also work.
- Include interfaces, value/instance classes, statics-only classes, ctor-only classes, value-tag-only classes, and classes registered later by `deftype`/`defrecord` and libraries (RFC 0014 or `register-class-supers!`).
- Make it dynamic: each call must see classes added since boot. Return an eager vector snapshot (walking under the graph lock), not a once-realized delay or a lazy seq over the live table.
- Deterministic: sorted by canonical name, unique. Do not return short aliases; callers can derive simple names with `.getSimpleName`.
- Scope: modeled classes only, not a classpath. Unmodeled JDK classes remain absent, matching `Class/forName`.

## Implementation hint

`jvm-class-parents` (`host/chez/java/class-hierarchy.ss`) is the graph; `class-statics-tbl` and `class-ctors-tbl` (`host/chez/java/host-static.ss`) and `jhost-tag->fqn` (`host/chez/java/class-hierarchy.ss`) cover the rest. `class-token-alist`/`class-fqn-list` (`host/chez/java/host-class.ss`) show the existing walk, but are computed at boot and therefore incomplete for later registrations. If exposed through `jolt.host`, add the exports via `def-var!` and update `host/chez/jolt-host-manifest.txt`.

## Tests

- Includes `java.lang.String`, `java.util.ArrayList`, `java.util.Collections`, `java.net.URL`, `clojure.lang.IFn`, and `java.nio.file.WatchKey`.
- Names are canonical, unique, and sorted; `.getSimpleName` is non-empty.
- For every class, `(= c (jolt-class-for (.getName c)))`.
- After a `defrecord`/`deftype` or a `register-class-supers!` call, a later call includes the new class.
- Two calls with no intervening registration return the same set and order.

# Native loader: private source namespaces recompile on every load

Filed as [jolt#1653](https://github.com/jolt-lang/jolt/issues/1653).

## Summary

Jolt's native loader (`jolt.loader`) reads and compiles context-private
namespaces from source on every load. There is no AOT reuse for them, so a
host that loads extensions from `src/` pays full compilation on every run.
In kmet, five shipped extensions cost ~3.7–4.6 s per run on Jolt vs ~0.21 s
on babashka. A full-suite cleanup that restores 91 evicted extension
namespaces costs ~18.6 s on Jolt vs ~0.37 s on bb.

The loader docstring explains the current constraint:

> Namespace sources are read and compiled directly at `load`, deliberately
> not through the AOT cache: a compiled artifact bakes its cross-namespace
> links (verified — code compiled against a var keeps the cell it saw when
> its file was compiled), so an artifact is only reusable by a context whose
> cells are the ones it was compiled against. […] only a context-private
> namespace pays compilation, and a sound per-context artifact cache belongs
> with per-context var tables.

This issue is a request to make that cost avoidable.

## Environment

- Jolt `v0.8.27-52-g7ed36439`
- Clojure `1.12.0-jolt`
- Linux x86_64

## Impact in kmet

`kmet.app.extensions.test-session/test-shipped-extensions-load-from-src`
loads the shipped extensions through the real native loader and then
reloads every loaded `kmet.extensions.*` namespace to repair the loader's
evictions.

| phase | Jolt | babashka |
|---|---:|---:|
| 5 shipped extension native loads | 3.7–4.6 s | 0.21 s |
| mcp-adapter native load (16 files, 324 KB) | 1.61–2.20 s | 58 ms |
| mcp-adapter cached `require` | 147–351 ms | — |
| lsp-adapter native load | 0.83–1.06 s | 34 ms |
| tree-sitter native load | 0.76–0.81 s | 29 ms |
| reload 45 source namespaces | 5.67 s | 204 ms |
| reload 46 test namespaces | 13.50 s | 333 ms |
| reload all 91 extension namespaces | 18.61 s | 370 ms |
| full-suite `test-session` | 14.6 s | 1.0 s |

That single namespace is ~20–25% of Jolt's measured suite time; the reload
is the majority of it.

## Reproduction

Self-contained `jolt.loader` reproduction: `scripts/repros/jolt_native_loader_perf.clj`

```sh
jolt scripts/repros/jolt_native_loader_perf.clj
```

Each fresh loader recompiles the same 20-file/113 KB source tree:

```
20 source files, 113.1 KB under /tmp/...
fresh native loader load: 2294.9 ms
fresh native loader load: 2108.2 ms
fresh native loader load: 2090.9 ms
```

A normal `require` of the same sources against a warm AOT cache is much
faster (the mcp adapter above is 147–351 ms for 16 files/324 KB).

Minimal form:

```clojure
(require '[babashka.fs :as fs]
         '[jolt.loader :as jl])

(defn- ms [t0] (/ (- (System/nanoTime) t0) 1e6))

(let [root (str (fs/create-temp-dir))]
  ;; write probe/ext/ns0.clj … ns19.clj, each requiring the previous one
  (dotimes [_ 3]
    (let [l (jl/classpath [root])
          t0 (System/nanoTime)]
      (jl/load l {:kind :ns :name "probe.ext.ns19"})
      (println (format "fresh loader: %.1f ms" (ms t0)))
      (jl/unload! l))))
```

## Proposal

### 1. Short term: expose the evicted set and restore it precisely

`load` / `unload!` should report the namespace names they evicted, for
example:

```clojure
{:evicted ["kmet.extensions.mcp-adapter.core" ...]}
```

and offer a supported `restore-evicted!` / `reload-evicted!` operation.
Today a host cannot know which of its loaded namespaces were evicted, so
kmet reloads *all* 91 `kmet.extensions.*` namespaces when only 45 source
namespaces were affected. Reloading only the evicted set saves ~13.5 s in
the kmet case without any cache work.

### 2. Medium term: a loader-scoped artifact cache

A sound cache must respect the cell-link constraint. Proposal:

- A loader owns a **var-generation** counter, bumped by eviction, `unload!`,
  `:reload`, and any host var redefinition a cached artifact links to.
- Cache key: source content hash + dependency surfaces + loader
  roots/host-view digest + var-generation.
- On a match, load the cached fasl; on a mismatch, compile and store.
- Expose it as an opt-in (`:compile-cache` on `jolt.loader/classpath`, or a
  dedicated constructor), leaving default semantics unchanged.

This becomes fully effective once per-context var tables exist. Until then
it can still save repeated loads within one context and makes the generation
contract explicit.

### 3. Alternative for extension hosts: precompiled roots

A native loader root may be a directory or jar whose namespaces were
compiled ahead of time, with metadata describing the cell graph the artifact
expects. The loader validates that graph and loads the artifact, or
recompiles if it cannot be satisfied. This is the shape extension hosts
need: load compiled shipped extensions instead of evaluating `src/` every
run.

### 4. `:reload`

`:reload` intentionally bypasses the cache for live editing. A fast path is
welcome when the source hash and cell graph are unchanged; an explicit
`:force` / `:reload-all` keeps the current behavior.

## Expected outcome

- Extension native loads: ~4 s → ~0.5 s (cached-`require` territory).
- Evicted-set restore: ~18.6 s → ~5.7 s for the 45 affected source
  namespaces, before any caching.
- kmet's `test-session`: ~14.6 s → ~2 s, removing roughly 20% of Jolt's
  measured suite time.

## Acceptance criteria

- A test with two fresh loaders over the same source root: the second load
  is a cache hit (within a documented threshold), and each context still
  gets independent cells.
- `:reload` of unchanged source and unchanged cells hits the cache; an edit
  misses and recompiles.
- `load` reports the evicted names, and a restore operation can reload
  exactly that set without touching unrelated namespaces.

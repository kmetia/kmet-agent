(ns kmet.libs.concurrent
  "Small concurrency primitives shared across layers:

   - `spawn` — the extension-SCI daemon-thread replacement for `future`
   - `or-signal` — a read-only OR-view over cancel signals (the provider
     stream's guard trip, the script bridge's abort), used wherever a
     consumer must poll two triggers as one

   Extensions run in isolated SCI contexts where `future`/`pmap`/`pcalls`
   are not available — SCI is a pure interpreter with no bundled
   Executor, while babashka injects `future` only into its host context
   (see `kmet.app.extensions/build-context-namespaces`). `kmet` therefore
   exposes this tiny helper instead of whitelisting `future`:

   - host: `babashka` embeds `sci` + injects `future` via `:namespaces`
   - extensions: second `sci` context built by `kmet.app.extensions` —
     only `slurp`/`spit`/`file-seq` from `clojure.core` plus the shared
     `kmet.tui.*` / `kmet.libs.*` layers are injected; `future` is
     deliberately omitted.

   Whitelisting `future` would pull in an implicit `Executor`, make
   extension work survive `unload-extension!`/`reload-extensions!`
   (non-daemon futures keep kmet alive), enable unbounded submission
   with no backpressure, and require the rest of the `future` family
   (`future-call`, `future-cancel`, …) plus `pmap`/`pcalls` for
   consistency. Keeping extensions on an explicit daemon `Thread` makes
   the lifecycle obvious and keeps unload/reload clean. If a nicer
   primitive is needed later, expose exactly this `spawn` — not `future`.")

(defn spawn
  "Start a daemon thread running `(f)` — the extension-SCI replacement
   for `future` (which is not available in extension contexts).

   Exceptions in `f` are logged via `kmet.debug/log` and otherwise
   swallowed (extension background work must never kill the host).
   Returns the `Thread` (already started, daemon=true)
   so callers that need to `.interrupt` / `.join` it can."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable e
                                     (try
                                       (requiring-resolve 'kmet.debug/log)
                                       (when-let [log-fn (resolve 'kmet.debug/log)]
                                         (log-fn "spawn failed" e))
                                       (catch Throwable _ nil))))))]
    (.setDaemon t true)
    (.start t)
    t))

(defn or-signal
  "A read-only derefable that is true when any of SIGNALS fires — the OR-view
   of a run's cancel atom and a local trigger. A nil signal never fires
   (a script run outside the loop's cancel plumbing). The view is deref-only:
   the underlying atoms stay the single source of truth, never reset or
   watched through it."
  [& signals]
  (reify clojure.lang.IDeref
    (deref [_] (boolean (some (fn [s] (when s (boolean @s))) signals)))))

(ns kmet.app.root
  "The process root: the ctx an entry mounts once, and what the extension
   host's ctx-less reads, the rpc row's context-free commands and `/units`
   answer from.

   An atom rather than a delay because the entry that boots the composition
   (`bb start`) publishes *its* root here, so a process has one tree — the one
   it mounted. The first root wins; a test that publishes its own puts the
   previous one back (`reset-root!`/`restore-root!`) rather than clearing it,
   because the root carries the process's registrations.")

(defonce ^:private app-root (atom nil))

(defn publish!
  "Make CTX the process root — the ctx `ctx` returns and every retired shim
   reads. Returns the root in place.

   A caller publishes **before** anything reads a shim: otherwise the first
   reader boots a second composition behind the one the entry mounted, and
   the retired process-level code (the façades, the extension host's ctx-less
   reads, `/units`) sees a tree that is not the one the run is using. The
   first root wins — a second publish returns the root already in place rather
   than stranding a tree things are already reading. A process that runs a
   second entry in one image must `reset-root!` first; an entry is ordinarily a
   process, so nothing in the entry path does."
  [ctx]
  (if (compare-and-set! app-root nil {:ctx ctx})
    ctx
    (:ctx @app-root)))

(defn ctx
  "The published process root, or nil when the process has mounted none yet
   (or when the first one was reset for a test)."
  []
  (:ctx @app-root))

(defn reset-root!
  "Clear the process root and return what it was.

   A test seam, and it hands the old state back for a reason: the root is
   process-global and carries registrations, so a test that published its own
   must put the previous one back (`restore-root!`) — clearing it would make the
   next test boot a **second** composition that has none of the registrations
   the first one carried."
  []
  (let [prev @app-root]
    (reset! app-root nil)
    prev))

(defn restore-root!
  "Put back what `reset-root!` returned (nil clears it)."
  [prev]
  (reset! app-root prev))

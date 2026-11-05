(ns kmet.app.front-end
  "The front-end surface a run installs for the extension host: the mode kind
   (`:mode`), whether a UI port exists (`has-ui`) and the port itself.

   The port is the run's `:ui` provider's `:port` (`kmet.app.ui`): the ABI's
   mandatory answers plus the provider's dialog answer. The run supplies the
   surface's other two keys (`:mode`, `has-ui`) and any wire capabilities on
   top — a mode does not build the base, it binds the one its composition
   mounted.

   A mode is not a front end for the process's life — it is one for its run. It
   installs the surface when it starts serving and puts back what was there when
   it ends: *what was there*, not the dispatcher's default, because a process can
   have a front end before it has this run (an embedder's port, another mode) and
   a run's teardown must not take it away."
  (:require [kmet.app.extensions.core :as extensions]))

(defn install!
  "Bind SURFACE for the run and return the fn that restores the surface bound
   before it. Installing and restoring are one pair, so every mode writes the
   rule once: `(install! {:mode :rpc :has-ui true :ui port})`.

   A `:runtime` map (the selected agent's
   `kmet.app.session.runtime/extension-runtime`) is installed process-wide with
   the surface, so an extension command or fiber outside a run acts on the
   agent this mode serves; restoring puts the previous capabilities back.
   Without the key the process's runtime capabilities are left alone."
  [surface]
  (let [previous (extensions/front-end-surface)
        install-runtime? (contains? surface :runtime)]
    (extensions/bind-extensions! surface)
    (when install-runtime?
      (extensions/bind-runtime-capabilities! (or (:runtime surface) {})))
    (fn []
      (extensions/bind-extensions! previous)
      (when install-runtime?
        (extensions/bind-runtime-capabilities! (or (:runtime previous) {}))))))

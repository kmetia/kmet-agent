(ns kmet.app.extensions
  "The extension host as a plugin: what a run's hooks resolve through, and the
   process's registry handle.

   The host lives in `modules/app/src/kmet/app/extensions` and may not
   require what owns its registries, so it takes them as a value
   (`kmet.app.extensions.core/bind-services!`): a registry handle for its own
   tags — hooks, renderers, flags, the UI port — and the ctx an extension's
   `:inject` mounts under. This row binds both from the composition it runs in.

   **It owns the process handle.** The row publishes
   `(kmet.app.registries/process-handle scope)` as
   `kmet.app.registry/current` and binds the complete service map the host
   reads — the handle, the ctx, the three resource operations and the owner
   sweep — so an extension's command is the `:commands` service the run reads,
   and unloading it sweeps the same handle it registered through. It provides no
   ctx service: the binding and the publication are the effect.

   Before 5l-ii the host bound one handle and kmet1's shim built another over
   two remainders, so a registration made through one was invisible to a reader
   of the other. The row replaces the binding it finds (kmet1's process host
   included) rather than overlaying it: it supplies every key the host reads, and
   kmet1's base binding remains only for the window before the root mounts.

   **Every registration is routed, not held.** Tools and run_code-only tool
   sources go to the composition's `:tools` service (the loop's tool table, the
   `/tools` selector, run_code's surface), commands, prompts and skills to their
   services, and every other host tag (hooks, renderers, flags, the UI port,
   themes) stays in the handle's remainder. The routing table is
   `kmet.app.registries/registry-tags`, one place for both the host and the
   shim.

   The binding is process-wide and one owner at a time, so disposing this row
   puts back both what it bound and what it published — a second composition
   (or a test) cannot leave the host pointing at a dead root."
  (:require [kmet.app.extensions.core :as extensions]
            [kmet.app.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]))

(defn- serve
  "Bind the host's services for this composition and publish its registry as
   the process handle: the registry routes every tag to the service that owns
   it, the resource operations are the app's own, and the ctx is the scope an
   extension's `:inject` mounts under. Returns the disposer that restores both
   (the previous binding, the previous publisher)."
  [ctx _config]
  (let [base (extensions/host-binding)
        scope (:parent ctx)
        handle (registries/process-handle scope)
        previous (registry/publish-process-handle! handle)]
    (extensions/bind-services!
     (fn [] (registries/host-services handle scope)))
    (fn []
      (registry/publish-process-handle! previous)
      (extensions/bind-services! base))))

(srce/defplugin plugin
  {:name :extensions
   :apply serve})

(ns kmet.app.registries
  "The registry services — `:commands`, `:prompts`, `:skills`, `:tools` (and
   the event vocabulary, whose row is `kmet.app.events`) — as one row shape.

   Each registry is a `kmet.app.registry/Registry` handle over an atom,
   installed by one `provide!` into the app scope, and mutated through that
   handle afterwards (`kmet.app.tools`' rule: installation is the provide, and a
   plugin adding a command never re-provides). They stay **separate services**
   rather than one `:registries` key because their consumers differ — the model
   sees `:tools`, the slash parser `:commands`, the prompt builder `:prompts` —
   and because a per-agent override should be per-seam ([`docs/architecture.md`]
   § Service catalog).

   The rows are one declaration each and one implementation: a handle has no
   knowledge of its tag, so the difference between them is the name and the key,
   and writing four copies of the same twenty lines would be the thing
   AGENTS.md forbids.

   `process-handle` is the other half: the one handle a process publishes
   (`kmet.app.registry/current`), routing every tag to the service that owns
   it in a composition. The `:extensions` row mounts it, because that row owns
   the host whose registrations are the reason a process needs one handle."
  (:require [kmet.app.commands :as commands]
            [kmet.app.prompts :as prompts]
            [kmet.app.registry :as registry]
            [kmet.app.skills :as skills]
            [kmet.srce.core :as srce]))

(defn service
  "A fresh registry handle: an atom, empty, owned by whatever provides it."
  []
  (registry/atom-registry))

(defn apply-row
  "The row's apply for the service NAME: build the handle and provide it into
   the mount site's scope, so the app's other rows see it while the entry stays
   this fiber's effect."
  [name]
  (fn [ctx _config]
    (srce/provide! ctx name (service) {:in (:parent ctx)})))

(srce/defplugin tools
  {:name :tools
   :provide #{:tools}
   :apply (apply-row :tools)})

(srce/defplugin commands
  {:name :commands
   :provide #{:commands}
   :apply (apply-row :commands)})

(srce/defplugin prompts
  {:name :prompts
   :provide #{:prompts}
   :apply (apply-row :prompts)})

(srce/defplugin skills
  {:name :skills
   :provide #{:skills}
   :apply (apply-row :skills)})

;; ─── The process handle ───────────────────────────────────────────────────

(def registry-tags
  "Every registry tag the process handle routes, and the service that owns it —
   nil for the tags with no service (the extension host's own registrations:
   hooks, themes, the UI port, entry/message renderers, flags), which share the
   handle's `remainder` until they get one.

   The host's own lists (`host-tags` plus `resource-tags` below) must equal this
   one; a test holds them to it, because a forgotten tag leaks silently."
  {:kmet/tool :tools
   :kmet/tool-source :tools
   :kmet/event :events
   :kmet/command :commands
   :kmet/prompt :prompts
   :kmet/skill :skills
   :kmet/hook nil
   :kmet/theme nil
   :kmet/ui-port nil
   :kmet/entry-renderer nil
   :kmet/message-renderer nil
   :kmet/flag nil})

(def resource-tags
  "The registries an extension's contributed resources live in — what unloading
   it sweeps besides the host's own tags (`kmet.app.extensions.core/host-tags`)."
  [:kmet/command :kmet/prompt :kmet/skill])

(defn process-handle
  "The process registry handle for a composition: one façade that routes each
   tag to the service that owns it in SCOPE, so a command registered here is the
   `:commands` service kmet2 reads, and one with no service still has somewhere
   to go. SCOPE is the app scope, not the mount site, because a row provides
   into its parent."
  [scope]
  (let [remainder (registry/atom-registry)]
    (registry/router
     (fn [tag]
       ;; a service the composition does not mount (a bare test tree, a profile
       ;; without the row) falls back to the remainder rather than a nil handle
       (or (when-let [service (registry-tags tag)]
             (srce/get scope service))
           remainder)))))

(defn- resource-services
  "The resource operations the extension host calls, backed by the registries
   their own namespaces read: those answer through HANDLE, the process handle
   the `:extensions` row publishes, so these are the app's own fns rather than a
   second registry implementation.

   `:deregister-owner!` is the host's second sweep: it takes a contributor's
   commands, prompts and skills with it, on top of the host's own tags the
   caller sweeps through the same handle."
  [handle]
  {:commands {:register! commands/register-command!
              :unregister! commands/unregister-command!
              :all commands/get-commands}
   :prompts {:register! prompts/register-prompt-template!
             :load-dir! prompts/load-prompt-templates-from-dir}
   :skills {:register! skills/register-extension-skill!
            :load-dir! skills/load-skills-from-dir}
   :deregister-owner! (fn [owner]
                        (doseq [tag resource-tags]
                          (registry/deregister-owner! handle tag owner)))})

(def host-service-keys
  "The keys the extension host reads from its bound services
   (`kmet.app.extensions.core/services`): the process handle, the ctx an
   `:inject` mounts under, the three resource operations, and the owner sweep.
   An owner binds exactly these; a test compares the binding to this list, so a
   key the host grows cannot be silently dropped."
  [:registry :ctx :commands :prompts :skills :deregister-owner!])

(defn deregister-owner!
  "Remove every registration OWNER holds, across every tag `registry-tags`
   routes — unloading a contributor's entries in one call, on top of the
   resource sweep `host-services` supplies."
  [handle owner]
  (doseq [tag (keys registry-tags)]
    (registry/deregister-owner! handle tag owner))
  nil)

(defn host-services
  "The complete service map the extension host reads, for HANDLE and CTX — what
   an owner binds with `kmet.app.extensions.core/bind-services!`. One builder
   for every owner (the `:extensions` row, kmet1's shim fallback), so the
   contract cannot drift between them."
  [handle ctx]
  (merge {:registry handle :ctx ctx} (resource-services handle)))

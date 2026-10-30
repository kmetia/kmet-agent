(ns kmet.app.presets
  "A preset is a bundle mounted under one ctx.

   `docs/architecture.md` § Composition: a **bundle** is reusable rows plus
   patches, a **profile** stacks bundles at the root, and a **preset** mounts
   one under an agent's child ctx. The machinery is the same on purpose — one
   composition language, two mount points — so a preset is named like a bundle
   and composed by `kmet.app.profile`, never re-declared here.

   What a preset is *for*: per-agent composition (`docs/architecture.md` § Multi-session
   server mode). The agent's child ctx inherits root services through the
   chain, so the difference a preset makes is a row the child provides itself —
   a `:permissions` policy for one agent, a service nothing above it provides, a
   listener. A child cannot shadow a service an ancestor provides: the kernel
   refuses a second provider on the chain, so a preset that wants a different
   `:tools` has to be the composition's choice, not a per-agent one. Mounting on
   the child ctx is also what keeps the row's lifetime the agent's: the fiber is
   under the child, so disposing the agent disposes it.

   The rows must be **active** after the mount settles. A preset that composes
   but does not mount (a name nothing provides, an `apply` that threw) is a
   composition bug, and the agent it was composing for must not be created with
   half its world — so the failure is thrown here, before the agent exists, and
   what *did* mount is unwound before the throw. `:agents/create!`'s rollback
   releases the child ctx on top of that, which is the belt to this
   suspenders: a caller mounting onto a ctx it keeps is the case only this can
   undo."
  (:require [kmet.app.events :as events]
            [kmet.app.extensions.rows :as extension-rows]
            [kmet.app.profile :as profile]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

(def ^:private builtin
  "The built-in table, read once. A preset mount is per agent and a server
   creates one agent per connection, so a resource read per mount would be a
   parse of a jar entry for data that cannot change while the process lives."
  (delay (profile/builtin)))

(defn- check-extension-rows!
  "Refuse an `:ext/…` row in a preset: the extension's plugin mounts under the
   host ctx (app-wide), so a per-agent row would own a process-wide extension."
  [preset-name rows]
  (when-let [row (some #(when (extension-rows/source? (:plugin %)) %) rows)]
    (throw (ex-info (str "preset " (pr-str preset-name) " names extension row "
                         (pr-str (:id row)) " — :ext/… rows belong to a profile")
                    {:type :kmet.app/preset-extension-row
                     :preset preset-name
                     :row row}))))

(defn mount!
  "Mount PRESET-NAME's rows under CTX and return `{:preset :rows :fibers}`.
   Throws `:kmet.app/preset-failed` when a row is not active, naming it, and
   `:kmet.app/preset-extension-row` for an `:ext/…` row (an extension mounts
   app-wide; it belongs to a profile).

   CTX is the ctx the preset is for — an agent's child ctx from
   `:agents/create!`'s `:setup` — and the fibers live and die with it."
  ([ctx preset-name] (mount! ctx @builtin preset-name))
  ([ctx table preset-name]
   (let [{:keys [rows]} (profile/compose [(profile/bundle table preset-name)])
         _ (check-extension-rows! preset-name rows)
         fibers (runtime/mount-rows! ctx rows)]
     (if-let [failed (seq (runtime/inactive-rows rows fibers))]
       (do
         ;; a preset is a set: half of it is not this preset. `create!`'s
         ;; rollback would release the whole child ctx, but a caller that
         ;; mounted onto a ctx it means to keep has no other way to undo this
         ;; one — and a half-mounted preset is precisely the state a per-agent
         ;; policy must not be left in.
         (doseq [fiber (rseq fibers)] (srce/dispose! fiber))
         (doseq [f failed] (srce/log! ctx :error "preset row did not mount" f))
         (throw (ex-info (str "preset " (pr-str preset-name) " did not mount: "
                              (mapv :id failed))
                         {:type :kmet.app/preset-failed
                          :preset preset-name
                          :fibers failed})))
       (do (events/declare-mounted! fibers)
           {:preset preset-name :rows (count rows) :fibers fibers})))))

(defn setup
  "The `:setup` a `:agents/create!` opts carries: mount PRESET-NAME under the
   agent's child ctx — `(create! ctx {:setup (presets/setup :bundle/gated)})`.
   A collection of names mounts each in order, which is how a front end's row
   config names more than one. `setup` runs before the factory, so no agent
   observes a half-composed world."
  ([preset-name]
   (if (coll? preset-name)
     (fn [ctx _id] (doseq [name preset-name] (mount! ctx name)))
     (setup @builtin preset-name)))
  ([table preset-name]
   (fn [ctx _id] (mount! ctx table preset-name))))

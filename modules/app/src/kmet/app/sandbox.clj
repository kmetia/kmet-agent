(ns kmet.app.sandbox
  "The `:sandbox` service: the realm a code-mode script's tool calls run in.

   A script is not a ctx — it is an SCI context with a tool surface — so the
   boundary around it is the context its inner tool calls execute against: a
   child of the run's ctx that is a **realm** for the names the value names
   (`srce/isolate`), so a tool a script calls cannot read the composition's
   `:tools`, `:shell` or `:workspace` from the ctx it was handed. The seam is
   the ctx under the realm boundary, and the value is what the boundary is
   (docs/architecture.md § Service catalog).

   Per agent, like `:shell` and `:permissions`, and for the kernel's reason: a
   child may not shadow a service its ancestor provides, so a per-agent value
   has to be the only provider on its chain. The agents factory provides the
   default on every agent's ctx (`kmet.app.loop.driver`); a preset that mounts
   the row on the child ctx first wins, because a preset names a bundle and a
   bundle is how a profile says what one agent's world is.

   The value is data — a set of service names — and `realm` is the derivation;
   nothing here resolves a service itself, so the row can be mounted by a
   composition, a preset or a test with no boot at all."
  (:require [kmet.srce.core :as srce]))

(def default-isolate
  "The names a script realm blocks when nothing named a boundary: the three
   execution seams an agent carries on its ctx. A script's calls reach the
   tools it was offered (the bridge's surface, not the host's `:tools` table)
   and the run's shell, cwd and skills through the invocation pipeline's
   bindings (`kmet.app.tools.invoke`) — never through a service lookup a
   scripted call could make on its own."
  #{:tools :shell :workspace})

(def default-value
  "The boundary an agent with no `:sandbox` row runs under. The safe default is
   the boundary, not no boundary: a composition that mounted nothing still
   gives each script a realm, the way the `:approvals` row's default denies
   rather than allows."
  {:isolate default-isolate})

(defn value
  "The `:sandbox` service value CONFIG describes: `{:isolate #{…}}`.

   A missing or nil `:isolate` is the default boundary; an explicit empty
   collection is a realm for nothing (a script sees its run's services), which
   is a composition saying so rather than an omission."
  [config]
  (let [isolate (:isolate config)]
    {:isolate (if (nil? isolate) default-isolate (set isolate))}))

(defn realm
  "CTX's realm under SANDBOX: a child ctx where the value's names do not
   resolve, so a lookup through it stops at the boundary.

   A value that isolates nothing returns CTX itself — a realm that blocks
   nothing would only add a node to the tree. The caller detaches a realm it
   built when the boundary's work is over (`srce/detach!`); the kernel keeps
   the ctx resolving either way, which is what lets a call outlive the script
   that started it."
  [ctx sandbox]
  (if-let [names (seq (:isolate (or sandbox default-value)))]
    (apply srce/isolate ctx names)
    ctx))

;; The row: the boundary a profile — or a preset — names.
;;
;;   {:id :sandbox :plugin kmet.app.sandbox/plugin
;;    :config {:isolate [:workspace]}}   ; a narrower boundary than the default
;;
;; The entry lands in the mount's parent store (`{:in (:parent ctx)}`, the
;; registries' shape), so a preset mounted on an agent's ctx provides the value
;; the *agent* resolves, and the row's fiber still owns it: disposing the
;; preset removes the value with it.
(srce/defplugin plugin
  {:name :sandbox
   :provide #{:sandbox}
   :apply (fn [ctx config]
            (srce/provide! ctx :sandbox (value config) {:in (:parent ctx)}))})

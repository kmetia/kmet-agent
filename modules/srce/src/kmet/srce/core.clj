(ns kmet.srce.core
  "The kernel's public surface — the one namespace app code requires.

   A ctx is a value (`ctx?`), a fiber is an atom holding its record, and every
   registration is reversible: `provide!`, `on` and `effect` return disposers
   that the owning fiber unwinds LIFO. Dependencies are declared with
   `:inject`; a plugin waits for them, so mount order is requirements rather
   than boot sequencing. See the module note (`srce.md`) for the contracts.

   Two names shadow clojure.core — `get` and `extend`."
  (:refer-clojure :exclude [get extend])
  (:require [kmet.srce.ctx :as ctx]
            [kmet.srce.event :as event]
            [kmet.srce.fiber :as fiber]))

;; ── ctx ───────────────────────────────────────────────────────────────────

(def root
  "A root ctx. A value, not a process singleton; META is merged as-is."
  ctx/root)

(def ctx?
  "Is X a ctx?"
  ctx/ctx?)

(def extend
  "A child ctx with META merged in; the parent is not mutated."
  ctx/extend)

(def isolate
  "A child ctx that is a realm for NAMES."
  ctx/isolate)

(def inside?
  "Is CTX in ROOT's subtree? Inclusive."
  ctx/inside?)

(def children
  "This ctx's child ctxs, creation order."
  ctx/children)

(def detach!
  "Remove CTX from its parent's children — the lifetime a plain child ctx
   (`extend`, `isolate`) has. Keeps the `:parent` chain; refuses while child
   ctxs remain."
  ctx/detach!)

(def mount-fiber
  "The fiber whose mount created this ctx, or nil for a plain child and the
   root."
  ctx/mount-fiber)

(def attr
  "The nearest non-nil K on the ctx chain, with no realm stop — the walk for
   keys the app carries (:base-url, :resolver, an app-owned queue)."
  ctx/attr)

;; ── services ──────────────────────────────────────────────────────────────

(def get
  "The provider value for NAME, or nil when absent, outside this realm, or not
   currently available."
  ctx/get)

(def provider
  "The provider record {:name :fiber :value :check} for NAME, or nil."
  ctx/provider)

(def services
  "The realm-aware merge of what CTX can see: {name provider-record}."
  ctx/services)

(def provide!
  "Register VALUE as NAME for the current ctx's realm; returns a disposer."
  fiber/provide!)

(def log!
  "Log through the :logger service; silent when it is absent."
  ctx/log!)

;; ── effects and fibers ────────────────────────────────────────────────────

(def effect
  "Register a reversible effect on the current fiber; returns a single-shot
   disposer. A disposer that cannot finish inline returns an undelivered promise
   instead of blocking: `dispose!` reports it as `:undoing` rather than waiting,
   and the caller that owns the lifetime awaits it."
  ctx/effect)

(def effects
  "The effect labels in creation order."
  fiber/effects)

(def fiber-info
  "FIBER as a plain map (identity, lifecycle, inject/provides/waiting-for, effect
   labels, its ctx) — what inspection and reconciliation read instead of the
   record."
  fiber/fiber-info)

(def fibers
  "Every mounted fiber of CTX's root as `fiber-info` data, mount order."
  fiber/fibers)

(def active?
  "Is FIBER :active?"
  fiber/active?)

(def plugin-source
  "Resolve a plugin source: a var as-is, a keyword through RESOLVER."
  fiber/plugin-source)

(def normalize-inject
  "Normalize :inject to {name nil|:optional}; throws :srce/bad-inject."
  fiber/normalize-inject)

(def plugin!
  "Mount SOURCE as a new fiber under CTX with CONFIG."
  fiber/plugin!)

(def inject!
  "Mount {name :inject :apply} under CTX. The name is required."
  fiber/inject!)

(def runtime
  "The runtime for SOURCE, or nil."
  fiber/runtime)

(def runtime?
  "Is a runtime mounted for this source?"
  fiber/runtime?)

(def runtimes
  "Every plugin runtime, mount order."
  fiber/runtimes)

(def settle!
  "Run any pending provider change to a fixpoint; answers `{:rounds n}` for the
   fixpoint rounds this call ran."
  fiber/settle!)

(def dispose!
  "Unwind FIBER's effects LIFO and detach it. Idempotent; answers `{:disposed n
   :failed [{:label … :error …}] :undoing [{:label … :handle p}]}` — `:undoing`
   is a disposer that handed back work it could not finish, which the kernel
   never awaits."
  fiber/dispose!)

(def restart!
  "Dispose FIBER and mount it again, new uid; returns the new fiber."
  fiber/restart!)

(def update!
  "Change FIBER's config in place (same fiber, same uid) through the plugin's
   `:update` — `(fn [ctx old-config new-config] …)`; refuses without one. A
   throw unwinds what the hook registered. Dependents keep their resolved value
   until their own reload."
  fiber/update!)

(def dispose-plugin!
  "Dispose every fiber of SOURCE; answers the aggregate of the disposals."
  fiber/dispose-plugin!)

(def dispose-subtree!
  "Dispose every fiber under CTX, children first; answers the aggregate of the
   disposals."
  fiber/dispose-subtree!)

;; ── events ────────────────────────────────────────────────────────────────

(def on
  "Register F as a listener for NAME; returns a disposer reporting removal."
  event/on)

(def once
  "Register F for NAME's next emit; returns its disposer."
  event/once)

(def dispatch-modes
  "The dispatch modes the kernel implements (six non-awaiting folds)."
  event/dispatch-modes)

(def emit!
  "Emit EVENT to the listeners of NAME visible from CTX; results are ignored."
  event/emit!)

(def bail!
  "Emit EVENT until a listener returns non-nil; that value is the answer."
  event/bail!)

(def waterfall!
  "Emit EVENT as around-middleware: `(f event next)`, and not calling `next`
   vetoes the rest."
  event/waterfall!)

(def collect-last!
  "Run every listener; the last non-nil result is the answer."
  event/collect-last!)

(def collect-all!
  "Run every listener; every non-nil result as `[[data result] …]`."
  event/collect-all!)

(def boundary!
  "Dispatch as a boundary: each listener sees the accumulated map merged into its
   event and returns the keys to accumulate."
  event/boundary!)

(def invoke!
  "Call one listener; a throw is logged and reads as nil (the dispatch rule)."
  event/invoke!)

(def listeners
  "The resolved listener list for NAME from CTX, in dispatch order."
  event/listeners)

(def event-names
  "Every event name registered on CTX or an ancestor, sorted."
  event/names)

(def validate-plugin!
  "Check a plugin map and return it with `:inject` normalized; throws
   `:srce/bad-plugin`. Public because `defplugin` expands to it, so a macro a
   sandboxed caller uses must reach public API only."
  fiber/validate-plugin!)

;; ── plugins as data ───────────────────────────────────────────────────────

(defmacro defplugin
  "Define a plugin var from a map literal. The literal shape is checked now
   (a map with a keyword :name and an :apply key); the full runtime check —
   fns, :inject, :config, :provide — runs when the def loads and once at each
   mount. The var carries the plugin name as `:srce/plugin` metadata, which is
   what an inspection tool reads without booting."
  [sym plugin]
  (when-not (and (map? plugin)
                 (keyword? (:name plugin))
                 (contains? plugin :apply))
    (throw (ex-info (str "invalid plugin " sym ": a map with a keyword :name and an :apply key")
                    {:type :srce/bad-plugin
                     :problems (cond-> []
                                 (not (map? plugin)) (conj :map)
                                 (not (keyword? (:name plugin))) (conj :name)
                                 (not (contains? plugin :apply)) (conj :apply))})))
  `(def ~(vary-meta sym assoc :srce/plugin (:name plugin))
     (validate-plugin! ~plugin)))

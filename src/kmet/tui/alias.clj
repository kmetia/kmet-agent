(ns kmet.tui.alias
  "Qualified-keyword tag aliases (tui.md §2.9) — reusable, named tag-level
   components.

   A hiccup element whose head is a QUALIFIED keyword resolves through this
   registry to a fn `(fn [attrs children] tree)`; an UNQUALIFIED keyword is
   still a host tag (the closed table in kmet.tui.hiccup). Unlike a fn head
   the tag is data — a keyword, never a var or symbol — so no *ns*
   resolution happens at reconcile time and a tree site can embed
   `[:my.ns/chip {:model m} child]` without requiring the provider.

   Since the tree carries the keyword, the registry is process-global and
   mutable; registration is explicit and last-wins (an extension reload
   re-registers its aliases). Extension aliases must be registered through
   the extension api (kmet.extension/register-alias!) so unload removes
   them, and must be top-level fns — never closures over extension state,
   which the registration would keep reachable for as long as it lives.

   Underneath, an alias element is an ordinary fn component (wrapped in a
   ComponentFn by the reconciler), so reactions, with-let state, refs,
   keyed reuse and the unchanged-subtree skip all apply unchanged.")

(defonce ^:private registry (atom {}))

(defn registered
  "Snapshot of the alias registry {:alias-kw fn} — tooling, tests, and the
   unknown-alias error's list/did-you-mean."
  []
  @registry)

(defn lookup
  "The registered fn for ALIAS-KW, or nil."
  [alias-kw]
  (get @registry alias-kw))

(defn- invalid!
  [alias-kw f why]
  (throw (ex-info (str "kmet.tui.alias: invalid alias registration (" why
                       "): " (pr-str alias-kw) " → " (pr-str f))
                  {:alias alias-kw :f f})))

(declare unregister!)

(defn register!
  "Register (or replace) ALIAS-KW — a QUALIFIED keyword; unqualified
   keywords are host tags and are rejected — with F,
   `(fn [attrs children] tree)`: attrs is the element's props map without
   :key/:ref, children a flat vector of the element's raw child nodes ([]
   when none). Returns a deregister fn that removes the alias only while it
   still maps to F, so an older owner's unload can never wipe a newer
   registration."
  [alias-kw f]
  (when-not (and (keyword? alias-kw) (some? (namespace alias-kw)))
    (invalid! alias-kw f "aliases must be qualified keywords"))
  (when-not (fn? f)
    (invalid! alias-kw f "the alias fn is (fn [attrs children] tree)"))
  (swap! registry assoc alias-kw f)
  (fn [] (unregister! alias-kw f)))

(defn unregister!
  "Remove ALIAS-KW's registration when it still maps to F; a no-op
   otherwise (the identity guard behind register!'s deregister fn)."
  [alias-kw f]
  (swap! registry (fn [m]
                    (if (identical? (get m alias-kw) f)
                      (dissoc m alias-kw)
                      m)))
  nil)

(defn reset-registry!
  "Clear every registration — test isolation and host teardown only. An
   alias erased while a tree still mounts it fails loudly on its next body
   run (see head), so app code never calls this."
  []
  (reset! registry {})
  nil)

(defn- resolve-alias!
  "The live registration for ALIAS-KW, or throw when it is gone — the
   wrapper resolves per call, never at parse."
  [alias-kw]
  (or (lookup alias-kw)
      (throw (ex-info (str "kmet.tui.alias: alias " alias-kw " is no longer "
                           "registered — it mounted while registered and was "
                           "removed since (extension unload?); remount the "
                           "tree or re-register the alias")
                      {:alias alias-kw}))))

(defn head
  "The reconciler head for ALIAS-KW: a fn dispatching (attrs) / (attrs
   children) to the REGISTERED fn — children defaulted to []. The
   registration is resolved at CALL time and only the keyword is captured,
   so an unloaded provider's fn is released even while its alias
   components are still mounted, and a re-registration takes effect on
   the component's next body run (an uncached body re-derives every pass;
   a body with tracked deps waits for its next dep change). A body run
   with the alias unregistered throws loudly."
  [alias-kw]
  (fn
    ([attrs]
     ((resolve-alias! alias-kw) attrs []))
    ([attrs children]
     ((resolve-alias! alias-kw) attrs (or children [])))))

(defmacro defalias
  "Define NAME as an alias in the current namespace: registers
   :<ns>/<name> with the defn-shaped FORMS (an optional docstring, then
   [attrs children] and the body) and defs NAME to the alias KEYWORD — so
   a tree site writes [name attrs children…], the var evaluates to the
   keyword, and ordinary require/go-to-definition still work:

     (defalias chip
       \"A one-line status chip.\"
       [{:keys [label]} _children]
       [:text {:padding-x 0 :padding-y 0} label])

     [:container {} [chip {:label \"ready\"}]]   ;; same namespace or another

   The var is NOT a function — an alias exists only as a tag head; call
   kmet.tui.alias/register! directly for a programmatic fn."
  [name & forms]
  (let [[docstring forms] (if (string? (first forms))
                            [(first forms) (rest forms)]
                            [nil forms])
        alias-kw (keyword (str *ns*) (clojure.core/name name))]
    `(do
       (register! ~alias-kw (fn ~@forms))
       (def ~(cond-> name docstring (with-meta {:doc docstring}))
         ~alias-kw))))

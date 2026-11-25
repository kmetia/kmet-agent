(ns kmet.app.registry
  "Name-addressed registrations behind one handle.

   Commands, prompt templates, skills and tools all register a value under a
   `[TAG name]` address, held by an owner, and answer the same questions: which
   value is `entry`'s for a name, what does the registry list (`entries`,
   `effective-entries`), what did one owner register (`by-owner`). Those rules
   live here once, over a **handle** — a value implementing `Registry`:

   - `atom-registry` — an atom of entries. What the app's `:tools` service
     installs: `register!` returns a disposer the caller ties to its fiber
     (`srce/effect`), so an extension's registrations unwind with it. It is the
     only handle: the legacy CLI's scope-node store (`registry_store`) went
     with the store at landing 4e.

   The rules: an entry is addressed by its own name; owners are searched in
   display order, so the owner that took a name keeps it; `effective-entries`
   collapses the listing to one entry per name, so listing and lookup agree; a
   whole-registry clear removes by tag across every owner, because one owner
   holds several registries. The listing layout is the handle's (see
   `entries`).

   **Ownership is the address.** A registration's OWNER is a path —
   `[:app :tools]`, `[:app :commands]`, `[:app :extensions <name>]` — and
   nothing else has to exist for it to be addressable. An owner holds as many
   tags as it registers under, so unloading an extension is one
   `deregister-owner!` per tag rather than a disposer per name, and a name it
   took is the name it keeps until it unloads.

   The registries themselves stay in their namespaces: they own the tag, the
   domain validation and the owner default. Only the mechanics live here."
  (:require [kmet.app.debug :as debug]))

(defprotocol Registry
  "A tag/name/owner-addressed registration store. The five methods are the
   whole contract; `entry`, `entries` and `effective-entries` below are the
   shared readings over it, so every handle answers them identically."
  (register!
    [registry tag name value owner]
    "Install VALUE under NAME in TAG's registry for OWNER; re-registering the
     same (tag, name, owner) replaces. Returns a disposer that reports whether
     it removed the registration.")
  (deregister!
    [registry tag name owner]
    "Remove NAME's registration for TAG — only OWNER's with an OWNER, every
     owner's with nil. Returns nil.")
  (by-owner
    [registry tag name]
    "Every value registered under NAME in TAG's registry: {owner value}. The
     raw owner view a collision check needs; lookup goes through `entry`.")
  (entries
    [registry tag]
    "TAG's registrations in display order: `[[name value] …]`, a name held by
     several owners appearing once per owner — use `effective-entries` for the
     registry a lookup sees. The handle chooses the layout (`atom-registry`
     interleaves owners in registration order), but the winning registration
     must come first for a name: the first occurrence is what `entry` returns.")
  (deregister-owner!
    [registry tag owner]
    "Remove every registration OWNER holds in TAG; other owners' entries in the
     tag survive. Unloading a contributor is this call per tag — an extension
     registers several names under one owner path, and disposing one name at a
     time would leave the rest behind. Returns nil.")
  (clear!
    [registry tag]
    "Remove every TAG registration, whichever owner holds it (mainly for
     /reload and tests). Returns nil."))

;; ─── Shared readings ────────────────────────────────────────────────────────

(defn entry
  "The value registered under NAME in TAG's registry, or nil. Owners are
   searched in display order, so the owner that took the name keeps it."
  [registry tag name]
  (second (first (filter #(= name (first %)) (entries registry tag)))))

(defn effective-entries
  "TAG's effective registrations in display order: one per name, from the
   owner that took it (`entry`'s rule), so listing and lookup agree."
  [registry tag]
  (second
   (reduce (fn [[seen acc] [name value]]
             (if (contains? seen name)
               [seen acc]
               [(conj seen name) (conj acc [name value])]))
           [#{} []]
           (entries registry tag))))

(defn router
  "A Registry that dispatches every call by its tag: HANDLE-FOR maps a tag to
   the handle that owns it. The app provides one service per seam, so the
   translation belongs in one place — and the
   shared readings above (`entry`, `effective-entries`) work through it
   unchanged, because they only ever call the protocol."
  [handle-for]
  (reify Registry
    (register! [_ tag name value owner]
      (register! (handle-for tag) tag name value owner))
    (deregister! [_ tag name owner]
      (deregister! (handle-for tag) tag name owner))
    (by-owner [_ tag name] (by-owner (handle-for tag) tag name))
    (deregister-owner! [_ tag owner] (deregister-owner! (handle-for tag) tag owner))
    (entries [_ tag] (entries (handle-for tag) tag))
    (clear! [_ tag] (clear! (handle-for tag) tag))))

(defn atom-registry
  "A registry handle over an atom: the app's `:tools` service registry.

   An entry is plain data — `{:tag :name :owner :value}` — and registration
   order is display order, so `entries` lists what was installed in the order
   it was installed. A disposer removes exactly the entry it installed (a stale
   disposer cannot remove a newer registration), which is how an extension's
   tools unwind with its fiber."
  []
  (let [state (atom [])]
    (reify Registry
      (register! [_ tag name value owner]
        (let [entry {:tag tag :name name :owner owner :value value}]
          (swap! state
                 (fn [v]
                   (conj (into [] (remove #(and (= tag (:tag %))
                                                (= name (:name %))
                                                (= owner (:owner %))))
                               v)
                         entry)))
          (fn []
            (let [present? (boolean (some #(identical? % entry) @state))]
              (swap! state (fn [v] (into [] (remove #(identical? % entry)) v)))
              present?))))
      (deregister! [_ tag name owner]
        (swap! state
               (fn [v]
                 (into [] (remove #(and (= tag (:tag %))
                                        (= name (:name %))
                                        (or (nil? owner) (= owner (:owner %)))))
                       v)))
        nil)
      (by-owner [_ tag name]
        (into {}
              (keep (fn [e] (when (and (= tag (:tag e)) (= name (:name e)))
                              [(:owner e) (:value e)])))
              @state))
      (entries [_ tag]
        (into [] (keep (fn [e] (when (= tag (:tag e)) [(:name e) (:value e)]))) @state))
      (deregister-owner! [_ tag owner]
        (swap! state
               (fn [v]
                 (into [] (remove #(and (= tag (:tag %)) (= owner (:owner %)))) v)))
        nil)
      (clear! [_ tag]
        (swap! state (fn [v] (into [] (remove #(= tag (:tag %))) v)))
        nil))))

(defn dispose-deregisters!
  "Run FNS — the deregisters an extension recorded for what lives outside the
   app's registries — in reverse registration order, each isolated so a
   failure is logged instead of skipping the rest. The complement of
   `deregister-owner!`: that sweeps what a registry holds, this releases what
   none does (event subscriptions, aliases, a theme's removal). Returns nil."
  [fns]
  (doseq [f (reverse fns)]
    (try (f)
         (catch Throwable t
           (debug/log-error "extension cleanup failed: " t))))
  nil)

;; ─── The handle a caller with no ctx in hand reads ──────────────────────────

(defonce ^:private nobody-published (atom-registry))

(defonce ^:private process-handle (atom nil))

(defn current
  "The registry handle a caller that has no ctx in hand reads — the commands,
   prompts and skills registries, which are addressed by a name and answer
   questions outside any one agent's ctx.

   The owner publishes: the retired process shim published a router over the app
   ctx's services, an agent driver an agent's own. Nobody having published (a
   cold process, a bare test) is not an error — the handle is an empty atom
   registry, one per process, so a registration made then is still visible to a
   later read in the same process rather than vanishing into a fresh map."
  []
  (or @process-handle nobody-published))

(defn publish-process-handle!
  "Point `current` at HANDLE and return the previous one, so a second owner (an
  agent's ctx, a test) can put it back. nil un-publishes."
  [handle]
  (let [previous @process-handle]
    (reset! process-handle handle)
    previous))

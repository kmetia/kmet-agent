(ns kmet.app.inspect
  "The booted view: a ctx tree as text — `/units` and `--dump-state`.

   One renderer, two entry points: `units` renders the subtree of the ctx it is
   given, and both entry points hand it the **process root** — `/units` the
   live one, `--dump-state` the one it booted — so the command and the flag
   cannot compute two listings that drift. A subtree is still a valid origin (an
   agent's ctx holds what a preset composed on it, not the app's mounts); it is
   just not what either entry point asks for.

   One unit per line with no indentation (the path is in the line), parents
   before children and fibers in mount order — the two properties that made
   the legacy CLI's store listing usable. A **ctx** line is a path and its child count; a
   **fiber** line is its state, what it injects, what it provides and its effect
   labels, plus the two facts a store could never show: `waiting-for` on a
   `:pending` fiber and the error on a `:failed` one.

   Every reading is the kernel's — `srce/children` and `srce/fiber-info` — so
   this namespace adds no kernel API. Nothing here prints a ctx or a value
   holding one: a ctx is cyclic, and a report names states, counts and service
   names."
  (:require [clojure.string :as str]
            [kmet.srce.core :as srce]))

(def ^:private min-path-width
  "The narrowest path column, so a shallow tree still lines its facts up."
  35)

(defn- pad
  "S in a column N wide; a longer S is not truncated."
  [s n]
  (let [s (str s)]
    (str s (str/join "" (repeat (max 0 (- n (count s))) " ")))))

;; ── paths ─────────────────────────────────────────────────────────────────

(defn- unnamed-segment
  "The path segment for a ctx the app built without a name (a run's own ctx):
   its position among its parent's children, so two siblings cannot collide."
  [ctx]
  (keyword "ctx"
           (str (inc (count (take-while #(not (identical? % ctx))
                                        (srce/children (:parent ctx))))))))

(defn- unit-path
  "CTX's unit path, root first: each ancestor's `:name`, or `:ctx/N` for a ctx
   the app built without one. A nameless root contributes no segment, so it
   renders as the origin `[]`."
  [ctx]
  (loop [c ctx acc []]
    (let [segment (or (:name c) (when (:parent c) (unnamed-segment c)))
          acc (if segment (into [segment] acc) acc)]
      (if-let [parent (:parent c)]
        (recur parent acc)
        acc))))

(defn- path-text
  "A unit path as the listing prints it (`[:app :tools]`). `pr-str`, not `str`:
   the hosts disagree on what a collection's `toString` prints."
  [path]
  (pr-str path))

;; ── the facts ─────────────────────────────────────────────────────────────

(defn- bare
  "A name as the listing prints it: a keyword without its colon, because that is
   what a service name is; anything else `pr-str`s, so a malformed declaration
   renders instead of breaking the listing that exists to show it."
  [n]
  (if (keyword? n) (name n) (pr-str n)))

(defn- bracket
  "NAMES — already printed by `bare` — in the listing's bracket form: sorted,
   space-separated, paired with the opening and closing bracket
   (`provides=[sessions]`)."
  [names]
  (str "[" (str/join " " (sort names)) "]"))

(defn- inject-entries
  "What a fiber injects: a required name as itself, an optional one with a `?`.
   An optional dependency that is absent does not hold the fiber pending, so
   the two must not read alike."
  [inject]
  (map (fn [[k v]] (str (bare k) (when (= :optional v) "?"))) inject))

(defn- label-text
  "An effect label as the listing prints it: a keyword loses its colon so it
   reads like a path (`:tool/read` → `tool/read`, the label a tool row's mount
   carries), anything else `str` — a label is already its own printed form."
  [label]
  (let [s (str label)]
    (if (keyword? label) (subs s 1) s)))

(defn- fiber-details
  "A fiber's facts in render order, from its `srce/fiber-info` projection: what
   it injects, what it provides, its effect labels, then the two states that
   have one — what a pending fiber waits for and why a failed one failed."
  [{:keys [inject provides effects waiting-for error]}]
  (cond-> []
    (seq inject)
    (conj (str "inject=" (bracket (inject-entries inject))))

    (seq provides)
    (conj (str "provides=" (bracket (map bare provides))))

    (seq effects)
    (conj (str "effects=[" (str/join ", " (map label-text effects)) "]"))

    (seq waiting-for)
    (conj (str "waiting-for=" (bracket (map bare waiting-for))))

    error
    (conj (str "error=" (pr-str (or (ex-message error) (str error)))))))

;; ── lines ─────────────────────────────────────────────────────────────────

(defn- ctx-line
  "A ctx: its path, and how many children hang under it."
  [ctx]
  {:path (path-text (unit-path ctx))
   :kind "ctx"
   :details [(str "children=" (count (srce/children ctx)))]})

(defn- fiber-line
  "A mount: its path, its state, and the fiber's facts."
  [ctx fiber]
  (let [{:keys [uid state] :as info} (srce/fiber-info fiber)]
    {:path (path-text (unit-path ctx))
     :kind (str "fiber#" uid)
     :state (str state)
     :details (fiber-details info)}))

(defn- unit-lines
  "CTX's own line — a fiber line when CTX is a mount, a ctx line otherwise —
   followed by its children's, depth first."
  [ctx]
  (let [line (if-let [fiber (srce/mount-fiber ctx)]
               (fiber-line ctx fiber)
               (ctx-line ctx))]
    (into [line] (mapcat unit-lines (srce/children ctx)))))

(defn- render
  "LINES as text: the path column, the kind column, the state, then the facts.
   Trailing whitespace is trimmed and no line is indented — the path is what
   locates a unit."
  [lines]
  (let [pw (apply max min-path-width (map (comp count str :path) lines))
        kw (apply max 8 (map (comp count :kind) lines))]
    (->> lines
         (map (fn [{:keys [path kind state details]}]
                (-> (str (pad path pw) "  " (pad kind kw) "  "
                         (when state (str (pad state 9) " "))
                         (str/join "  " details))
                    (str/replace #"\s+$" ""))))
         (str/join "\n"))))

(defn units
  "The unit tree under CTX as text: one flat line per unit, parents before
   children, fibers in mount order. CTX itself renders as a ctx even when it is
   a mount's own ctx, because it is the origin of what was asked for — the
   process root for both entry points."
  [ctx]
  (render (into [(ctx-line ctx)] (mapcat unit-lines (srce/children ctx)))))

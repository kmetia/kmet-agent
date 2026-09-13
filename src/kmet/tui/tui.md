# kmet TUI — package documentation

`kmet.tui.*` is a generic terminal-UI library (a Clojure/Babashka port of
`@earendil-works/pi-tui`, extended with a Reagent-style component layer).
It knows nothing about chat, LLMs or sessions — the app layer (`kmet.app.ui.*`)
builds on it, and extensions consume the same namespaces.

This document is the authoritative usage reference for the package. Keep it
up to date whenever the described behavior changes.

## Contents

1. [Architecture overview](#1-architecture-overview)
2. [The Hiccup DSL](#2-the-hiccup-dsl)
3. [Reactivity](#3-reactivity)
4. [State handling](#4-state-handling--global--local--props)
5. [Lifecycle](#5-lifecycle)
6. [Frame scheduling](#6-frame-scheduling)
7. [Input](#7-input--imperative-by-design)
8. [Protocols](#8-protocols)
9. [Theming](#9-theming)
10. [Component catalog](#10-component-catalog)
11. [Debugging rendering](#11-debugging-rendering)
12. [Testing & performance invariants](#12-testing--performance-invariants)
13. [Layer boundaries](#13-layer-boundaries)
14. [Roadmap](#14-roadmap)

---

## 1. Architecture overview

Two layers, one rule: **records are the terminal's "DOM elements"; function
components sit above them**, exactly like React components above `[:div]`.

- **Host elements** (records built with `defcomponent`): Text, Box,
  Container, VStack, HStack, Markdown, Editor, Input, SelectList, … — the
  terminal's primitives.
- **Components** (plain fns): `(fn [props] tree)` — the composition layer.
- **Reconciler** (`kmet.tui.hiccup`): compiles trees to records and diffs
  them by key.
- **Root** (`hiccup/root`): the one constructor from a tree to a mounted,
  disposable record.
- **Reactivity** (`kmet.libs.reakt` + `track!`): dependency-discovering
  reactions over plain atoms; invalidation schedules the next frame.
- **Output**: every record caches its rendered lines per width; the frame
  loop emits only the diff against the previous frame.

The **transcript lives in the terminal's own scrollback** — this is an
inline ("main screen") TUI, not an alt-screen one. Output is never confined
to an owned viewport: the stack renders every component at natural height
and whatever exceeds the screen scrolls into the native scrollback, which
the user can browse while streaming continues below. A *full redraw*
(a resize, an explicit rebuild — `tui-request-render` with force, i.e.
`tui-resume!` or the scrollback heal — or a shrink that starts above the
window) re-emits the whole transcript, so it also clears the scrollback
(`\u001b[3J`) or the re-emit would duplicate the history. A change that
starts *above* the window is otherwise handled without one: since the
terminal has no addressable scrollback, the diff is clamped to the window
top and only visible lines are repainted in place (a same-height or growing
change entirely above the window emits nothing; a shrink keeps the full
redraw) — this keeps the destructive clear out of the *automatic* streaming
path, where it otherwise yanked the viewport to the top on Termux and
Windows Terminal (microsoft/terminal#20370; pi #4506/#6502). What clamping
leaves behind is a stale scrollback: those lines were written with the old
content and the terminal cannot rewrite them, so the TUI *records* that
(`tui-scrollback-dirty?`) and rebuilds it with one clearing full redraw at a
streaming-free boundary — the app calls `tui-heal-scrollback!` at the end of
a turn (`on-agent-done` / `on-agent-error`, gated on nothing else streaming:
no turn, bash command or compaction) and on idle input. Both are moments
where the user is expected to be at the document end, so the clear's viewport
jump lands on a screen transition instead of mid-stream. An *explicit* reflow
is not the automatic path the clamp protects: the tool-output / thinking
toggles (and the `set-tools-expanded` extension API) and a theme switch are
discrete user actions, so the app forces the clearing rebuild for them rather
than leave the scrollback showing pre-reflow content (a theme switch would
leave the whole transcript in the old theme). Two consequences
that shape the rest of this document: components above the viewport must
not change gratuitously (§3.2's caching rules), and there is no viewport to
hit-test — mouse support would need a different model.

End-to-end flow:

```
atom change → reaction dirty → queued → frame flush runs it →
   = -gated notify → invalidate + schedule frame → fn bodies re-run →
   tree → compile+reconcile (keyed, per level) → record tree →
   lines (each record caches) → line diff → terminal bytes
```

### Namespaces

| namespace | role |
|---|---|
| `kmet.tui.core` | TUI instance: create/start/stop, child list, focus, overlays, input listeners, flash, render loop with line diffing |
| `kmet.tui.terminal` | backend abstraction: the lean `ITerminal` protocol (raw mode, bounded reads, live size, writes, progress) + shared ANSI verbs, Kitty/query wrappers and drain; `create-terminal` resolves the host backend at runtime |
| `kmet.tui.terminal-jline` / `kmet.tui.terminal-native` | the two backends — JLine on bb/JVM, termios (Unix; kernel32 pending) FFI over `jolt.ffi` on Jolt; only these namespaces touch platform deps |
| `kmet.tui.protocols` | `IComponent`, `IFocusable`, `IEditorComponent` |
| `kmet.tui.macros` | `defcomponent`, `track!`, `with-let`, `invalidate-cache`, deref-capture runtime |
| `kmet.libs.reakt` | reactions/track/cursor/batching over plain atoms |
| `kmet.tui.hiccup` | tag table, compile/reconcile, `root`, `ref`, `compute`, `render-lines` |
| `kmet.tui.components.*` | host elements (see §10) |
| `kmet.tui.theme` | color/styling API, active theme atom, theme files |
| `kmet.tui.keys` / `keybindings` | key names, Kitty protocol decoding, keybinding manager |
| `kmet.tui.autocomplete` / `fuzzy` | editor autocomplete dropdown, fuzzy matching |
| `kmet.tui.utils` | text wrapping, visible width, truncation helpers |
| `kmet.tui.border` | box-drawing glyph sets (frames, rules, table junctions) |
| `kmet.tui.timers` | loop-owned timer registry (§6.1) |

---

## 2. The Hiccup DSL

### 2.1 Syntax — Hiccup vectors

Trees are plain data plus whatever code you want, evaluated once per
re-render:

```clojure
[:box {:padding-x 1}
 [:text "hi"]
 (when-let [s @status] [:status-line s])   ;; nil → skipped
 existing-component                        ;; mounted components pass through
                                          ;; (records and reified IComponents)
 ;; seqs get spliced — always key spliced children, or prepending an
 ;; item rebuilds every unkeyed sibling after it (either spelling works):
 (map #(vector :text {:key (:id %) :text (:content %)}) msgs)
 (map (fn [m] ^{:key (:id m)} [:text (:content m)]) msgs)]
```

A tree becomes live only through `hiccup/root` (§2.6) — there is no other
public entry point.

Children rules:

| child | result |
|---|---|
| `nil` | skipped — this is the `when`/`when-let`/`if` support, free |
| string | compiled to a Text (the tag's `:primary` shorthand, §2.2) |
| record | passed through as-is (identity preserved; never disposed by reconcile — ownership stays with whoever created it) |
| reified/deftype'd `IComponent` | same as records — spliced foreign, never disposed (bb caveat: `satisfies?` can miss reifies from other evaluation contexts even though dispatch works on them, so detection is best-effort; hand the dock records — ui-custom wraps duck-typed maps in a CustomDialogAdapter for exactly this reason) |
| seq | spliced (each element treated as a child) |
| stack-entry map (VStack/HStack) | passed through as-is |

Tags are keywords from the closed tag table (§2.2) or function heads:
`[status-area {:mode :normal}]` is valid Reagent-style usage. Two more
normalizer rules keep call sites terse:

- **Props map optional** — `[:v-stack child…]` compiles with `{}`; the map
  slot is needed only when props exist (`:key`, `:ref`, options).
- **A fn component may return a seq of roots** — spliced where the element
  sits (the fragment equivalent), for wrappers that must not introduce a
  Box/VStack node into layout.

**Keys** identify a child across passes (§2.3) and can be written either way:

```clojure
[:text {:key (:id m) :text (:content m)}]      ;; :key prop
^{:key (:id m)} [:text (:content m)]           ;; element metadata (reagent-style)
```

The prop wins when both are given. On a fn head the metadata sits on the
vector the same way (`^{:key id} [row-comp {:item item}]`). Metadata on a
non-vector child (a record, a string, a stack entry) is ignored — those
match by identity or kind.

Validation fails loudly: unknown tags throw with a did-you-mean suggestion
(`:tst` → did-you-mean `:text`), children on a leaf tag throw, duplicate
`:key`s throw, stack-entry maps outside a stack tag throw, keyword children
throw.

### 2.2 Host elements — the tag table

Host elements are a **closed set** — `hiccup.clj` hardcodes the tag → ctor
table (no registry). Custom composition uses fn heads `[my-fn props]`;
extensions never add host elements. Tags and props:

| tag | props | children |
|---|---|---|
| `:text` | `:text` (primary), `:padding-x` `:padding-y` (default 1), `:bg-fn` | none (leaf) |
| `:markdown` | `:text` (primary), `:theme`, `:padding-x`, `:default-style`, `:transform`, `:border` (table glyphs, §2.8) | none (leaf) |
| `:spacer` | `:lines` (default 1) | none (leaf) |
| `:dynamic-border` | `:color-fn` (primary; default: theme `:border` color), `:border` (§2.8) | none (leaf) |
| `:truncated-text` | `:text` (primary), `:padding-x` `:padding-y` (default 0) | none (leaf) |
| `:spinner` | `:text` (primary), `:active`, `:prefix`, `:frames`, `:interval-ms`, `:spinner-color-fn`, `:message-color-fn` | none (leaf) |
| `:input` | `:value` (primary), `:on-submit`, `:on-escape` | none (leaf) |
| `:expandable-text` | `:collapsed-fn`, `:expanded-fn` (both required), `:expanded?`, `:padding-x` `:padding-y` | none (leaf) |
| `:image` | `:base64-data`, `:mime-type` (both required), `:theme`, `:max-width-cells` (default 60), `:max-height-cells`, `:filename`, `:image-id` | none (leaf) |
| `:select-list` | `:items` (primary), `:height` (default 10), `:theme`, `:header`, `:no-match-text`, `:min-primary-column-width` `:max-primary-column-width`, `:truncate-primary`, `:on-select`, `:on-escape`, `:on-selection-change`, `:on-key` | none (leaf) |
| `:settings-list` | `:items` (primary), `:theme`, `:on-change`, `:on-escape`, `:enable-search`, `:max-visible` (default 10) | none (leaf) |
| `:editor` | `:text` (primary), `:height` (default 12), `:padding-x`, `:border-fn`, `:border` (§2.8), `:keybindings`, `:terminal-rows`, `:on-submit`, `:on-change` | none (leaf) |
| `:cancellable-loader` | `:spinner` (defaults to a fresh active Spinner), `:on-abort`, `:text` (message for the default spinner) | none (leaf) |
| `:box` | `:padding-x` `:padding-y` (default 1), `:bg-fn` | yes |
| `:container` | — | yes |
| `:v-stack` | `:gap` | yes (entry maps allowed: `{:component c}`) |
| `:h-stack` | `:gap`, `:align` (`:stretch` default) | yes (entry maps allowed) |
| `:scroll-view` | `:follow-end` (default true), `:primary`, `:overscroll` (`:chain` default), `:scrollbar` (`:hidden` default), `:scrollbar-style`, `:scrollbar-hide-delay-ms` | yes — exactly ONE (more throws) |

`:primary` names the positional shorthand: `[:text "hi"]` compiles to props
`{:text "hi"}` merged over defaults.

**Stateful leaves** (`:input` `:select-list` `:settings-list` `:editor`
`:spinner` `:cancellable-loader` `:expandable-text`): while their props
stay `=`-equal the instance (and its state) is kept. A CHANGED prop takes
the tag's **apply path** (§2.3) — every stateful tag declares one, so the
live instance is patched through its setters instead of rebuilt: text,
cursor, selection, focus, undo history, the spinner's animation clock and
a loader's abort signal all survive a prop change. What declines the patch
(and rebuilds, as the pre-R1 code always did) is a prop the tag cannot
express on the live instance — an editor's `:border`/`:keybindings`, a
settings list's `:enable-search`, a spinner's `:frames`/`:interval-ms`
(the only setter, `set-indicator!`, would switch it to verbatim
rendering), a cancellable-loader's `:spinner` child (a swap, with nothing
owning the replacement). Live updates can still go through `:ref` plus the
component's setters (e.g. `(input/input-set-value! (deref r) "x")`), the
same contract the spliced-record pattern always used. Focus is a host
concern — mount the component, then `tui-set-focus` on the ref'd instance.

**Host-internal components without a tag**: `alt_screen_flash` — it needs
the TUI's own request-render callback (`kmet.tui.core/tui-flash!` owns its
single instance), so it cannot be constructed from a tree.

Fn heads are fn **values**, never symbols — trees are built at runtime and
symbol resolution would couple the DSL to caller namespaces.

### 2.3 Compile + reconcile — the keyed diff

The reconciler is React's render pass, recursive and keyed — **one
reconciler per component, not global**:

```clojure
;; per component: diff desired tree against previous children
(hiccup/reconcile! children-atom tree)
```

Matching: the `:key` prop wins; fallback is match-kind (tag / fn value /
record payload / string). Reorder by key = reuse (like React), so stateful
subtrees (editors, `with-let` state, caches) survive reorders. Keys may also
come from element metadata (§2.1). Matched
children get their props re-applied wholesale — `(reset! (:props c) props)`
— so **every prop is live**: equal values no-op (memoized children for
free), changed values re-apply. Unmatched previous children are **disposed**
(children-first contract, §5.1).

Ownership rides the `:dsl/meta` stamp: everything the DSL constructs carries
it; foreign records spliced into trees never do and are never disposed.
Display leaves (Text/Markdown/Spacer/string) with changed props are rebuilt
rather than mutated — identity-free, their caches absorb rendering;
containers and fn components keep identity across passes. A host tag with
an `:apply` in its tag-table spec takes a third path: the changed props are
patched onto the live instance (setter calls, `bump! :applies` — §11's
counters) and the instance is kept; only a falsy return rebuilds. Two
rules govern the patch. First, the tag's STRUCTURAL props (§2.2 lists
which) are checked in their constructed form — a nil prop and its default
are the same component — and decline the patch when they differ. Second, a
STATE-CARRYING prop (`:value`, `:text`, `:items`, `:expanded?`) is written
through only when it changed from the previous pass's props AND differs
from the live value, coerced the way construction coerces it (nil ⇒ the
default); an unchanged prop never overwrites live state, so a keystroke or
a ref-driven toggle survives an unrelated prop change, while a prop that
did change wins. An `:items` change is a REFRESH of the same list, not a
wholesale replacement: the typed filter/query and the selection position
survive it (the resetting `select-list-set-items!` /
`settings-list-set-items!` default is the imperative variant, for a
genuinely new list). The stamp's recorded props are re-pointed at the
applied map, so the next equal pass is the plain reuse fast path again.

**Containers have their own rule**: they never take the rebuild branch —
a fresh construct starts with an empty child pool, so the whole subtree
(and every descendant's state) would be lost. A container instead always
reconciles its children in place and patches its structural props through
its `:apply` (`:box` padding/bg, `:v-stack`/`:h-stack` gap, `:h-stack`
align, every `:scroll-view` prop) — the §4 props/state migration, landed.
A container `:apply` is therefore TOTAL; a container tag without one keeps
its structural props as constructed (the pre-migration behavior).
One mechanism fills everything: containers are constructed empty and
filled by the same keyed diff through per-tag children lenses.

**Duplicate `:key`s throw at reconcile** — two spliced siblings sharing a
key makes reuse undefined; throwing beats a silently vanishing subtree.

### 2.4 Refs — the imperative escape hatch

Focus, editor text access and scrolling are imperative calls on concrete
records. A tree that declares such an element reaches its instantiated
record through a ref — a second pseudo-prop beside `:key`:

```clojure
(def editor-ref (hiccup/ref))

[:editor-container {:ref editor-ref}]

;; elsewhere — an event handler or effect, never a render body:
(tui/tui-set-focus t @editor-ref)
```

Rules:

- refs are created with `(hiccup/ref)`; reconcile fills them on construct
  and clears them when the element is disposed — or when the element stops
  declaring that handle (a replaced or dropped `:ref` prop), so an
  abandoned handle never derefs a live component; treat as read-only;
- deref only outside render bodies (handlers, effects): nil until first
  reconcile constructs the element;
- one ref per element instance — sharing across two elements means
  last-mount wins.

### 2.5 Fn components — ComponentFn

A plain fn used as a tag head is wrapped in a ComponentFn record whose body
runs inside a reaction: dependencies auto-discovered at deref time, re-runs
queued to the frame flush, notification only when the output changes by `=`.
An idle UI runs zero bodies.

- **Props re-applied on reuse** via reset!; equal-value resets no-op.
- **Reconciliation is bounded**: per component's direct children — there is
  no whole-app vdom pass.
- **Dispose order is contractual**: children first, then own cleanups
  (§5.1).
- **Dynamic `hiccup/*width*`**: bound around bodies for truncation at the
  real width; a resize forces one re-derive of affected idle bodies, then
  they re-cache. `hiccup/*comp*` is the running ComponentFn itself.
- **Error contract**: a throwing component fn crashes the render loop
  (Throwable → render-crash.log → tui-stop) — loud beats silently wrong.

**The three forms** (Reagent's taxonomy):

| Reagent | here |
|---|---|
| Form-1: pure fn returning a tree | `(defn status-area [props] …tree)` — reaction-backed automatically |
| Form-2: local state | `with-let` bindings (§5.2) |
| Form-3: class components | `defcomponent` records (§3.2, §8) |
| — | raw records spliced into trees — the fourth form, for live instances owned elsewhere (reified IComponents splice too, §2.1 children rules) |

Bodies collecting **no tracked dependency** (only untracked reads or static
trees) re-run on every pass — batched semantics, uncached, never stale.
Mixed bodies must read reactive inputs through component-body derefs,
`tracked-deref`, computes or cursors (the coverage contract, §3.1).

**Choosing the form** — the uncached (bare `@`/untracked) form is the safe
default whenever state mutates asynchronously in multi-swap sequences
(a `future`/timer clearing one key then setting another, e.g. a
delete-confirm + status flow): the memoized `tracked-deref` form caches the
body's tree on `:idle` and coalesces changes behind a dirty gate, so a body
run that reads the state between two swaps can cache a tree stale relative
to the atom — and the second swap's watch may be swallowed, so it never
re-derives. The untracked form re-reads per pass, so it cannot cache stale
output (this is also why the old `track!` renders were immune). Only adopt
`tracked-deref` when a body is expensive enough to need narrow memoization
and its state changes are single swaps the reaction can observe.

**Granularity guidance** — don't build one giant screen component reading
all app state: any change then re-runs the entire body, tracked reads of
large collections pay a structural equality walk per change, and mapping a
big message list into elements inside one body resurrects a full-tree
rebuild at frame rate. Many small components reacting narrowly win; see §4
for where state lives.

Footguns (all documented in §5.2): keep fn bodies pure per pass, create
subscriptions once, hoist inline callbacks to named fns or stable values —
a props map rebuilt each frame containing fresh fn literals defeats
prop-equality memoization.

### 2.6 Mounting — hiccup/root

Trees enter the TUI through one constructor:

```clojure
(hiccup/root dock-component)     ; bare fn: shorthand for [fn {}]
(hiccup/root [:box {:padding 1} …])
(hiccup/root [[:text "a"] [:text "b"]])   ; seq of roots
```

Returns an IComponent: first render compiles/reconciles the tree, later
renders re-reconcile like any ComponentFn. Mount it anywhere a record is
accepted today (`tui-add-child`, `container-add-child`, `tui-show-overlay`,
stack entry maps) and call `dispose` when it leaves (overlay close,
shutdown). Ownership follows the container it was handed to; nothing else
retains it.

### 2.7 Headless rendering — trees are data, tests stay plain

Compilation is pure, so components are unit-testable without a terminal:

```clojure
(hiccup/render-lines [:box {:padding-x 0} [:text "hi"]] 40)
;; => ["hi"] — the exact lines the frame loop would draw
```

Assert on returned lines directly, or call twice across a state change and
diff — identical lines prove keyed reuse and cache hits held. No tty, no
sleeps: fast-path `bb test` material, never `^:slow`.

Trees compiled OUTSIDE a mount (`hiccup/compile-tree` for widgets, dialogs,
any hold-then-render lifetime) are owned by their holder: dispose them with
`hiccup/dispose-tree!` on replacement/close — with-let cleanups and
reactions unwind through it. Pure-string leaves may be abandoned safely;
reactive subtrees may not.

---

### 2.8 Borders — glyph sets as data

Every box-drawing glyph a component draws comes from one place,
`kmet.tui.border`: a border is a map of eight frame parts (`:top`
`:bottom` `:left` `:right` and four corners) plus five table junctions
(`:tee-down` ┬, `:tee-up` ┴, `:tee-left` ┤, `:tee-right` ├, `:cross` ┼).

```clojure
[:dynamic-border {:color-fn accent-fn :border :rounded}]
[:markdown {:text t :padding-x 0 :border :ascii}]
[:editor {:text draft :border {:top "─"}}]          ; a partial map merges over :normal
```

| style | glyphs |
|---|---|
| `:normal` (default) | `─ │ ┌ ┐ └ ┘` + junctions — the pi-parity set |
| `:rounded` `:thick` `:double` `:block` | the usual box-drawing variants |
| `:ascii` | `- | +` for a terminal that cannot draw the rest (serial console, `TERM=vt100`) |
| `:hidden` | spaces — the frame still costs its cells, so a row of frames stays aligned while its ink is gone |
| `:none` | no border at all: the component draws nothing where the frame would be |

Rules:

- A style is a keyword, a partial map (merged over `:normal`), or `:none`;
  `nil` means `:normal`. An unknown keyword throws at **construction**
  with a did-you-mean, so a typo cannot silently draw the wrong frame.
- The props are resolved when the component is built, not per render.
- **`:none` removes chrome, never structure.** Where the border is the
  component's own ink — a `dynamic-border` rule, the bash-execution box —
  `:none` draws nothing there. Where it is structural — a markdown table's
  box, the editor's rule (it carries the scroll indicators) — `:none` falls
  back to the default set; the table would not be a table without its box.
- Components with a border prop: `:dynamic-border` (a rule),
  `:markdown` (table glyphs), `:editor` (the rule above and below the
  text), `kmet.app.ui.bash-execution` (its box, `:border` option). All
  default to `:normal`, so existing output is unchanged.
- A component that *draws* a frame uses `border/top-line`,
  `border/bottom-line`, `border/mid-line` (with an optional edge-styling fn
  so the sides can take a different colour than the content) and
  `border/rule-line` rather than inlining glyphs.

## 3. Reactivity

### 3.1 `kmet.libs.reakt` — reactions over plain atoms

A Babashka port of `reagent.ratom`'s semantics. There is **no custom atom
type**: Babashka seals `IWatchable`/`IReset` away from pure-source
implementations, so dependency capture rides the existing deref funnel —
`kmet.libs.reakt/tracked-deref`, through which every component render body
routes its reads via `track!`. Plain `clojure.lang.Atom`s ARE the tracked
inputs — plain atoms need no wrapper, so there is no `ratom` sugar.

API: `make-reaction` / `reaction` (macro) / `derive` (derived ref over
explicit deps) / `cursor` (read-only lens) / `writable-cursor` +
`writable-cursor?` + `cursor-reset!` / `cursor-swap!` (write-through lens) /
`watch-ref` / `unwatch-ref` (reactions aren't IRefs — core `add-watch`
cannot take them) / `add-on-dispose!` / `flush!` (drain the batch queue) /
`force-run!` / `invalidate!` / `dispose!` / `tracked-deref` / `changed?`.

**Two cursors, split by capability.** `cursor` derives: reads are
`(get-in @source path)`, tracked like any dep, and a subscriber that only
reads cannot write. `writable-cursor` is its read-write sibling for state
the UI EDITS (a setting, a filter, a draft field): same tracked read, plus
`cursor-reset!` / `cursor-swap!`, which write the value back into the
source (`assoc-in` at the path; `[]` replaces the whole value) and return
it — a two-way binding is `@cur` plus a setter call, no setter callback
threaded through props. Writes are `=`-gated (an equal value touches
neither the source nor its watchers — the differing check that keeps an
`on-change` → write-back → re-render cycle from looping); otherwise a
write is an ordinary source change, so queued invalidation, `watch-ref`
watchers and the `:auto-run?` callback all flow normally. The source is a
plain atom (or another writable cursor, nesting the lens). Create one
once per owner and dispose it with the owner: a disposed cursor is inert
(derefs answer nil, writes are ignored) rather than a zombie writer into a
live source — and the refusal propagates, so writing through a nested lens
whose ancestor was disposed returns nil instead of claiming success.

Scheduling: a reaction whose deps change (by `=`) is marked dirty and
**enqueued**; `flush!` runs each dirty reaction once per pass — drained
from the render loop's ~16ms tick. Deref outside any reaction settles the
queue first and always answers the CURRENT value. Watchers fire only on
real output changes; sticky errors rethrow without re-execution until the
next dep change clears them.

Coverage contract — tracked reads are exactly:
(a) component render bodies via `track!` (automatic),
(b) explicit `reakt/tracked-deref` calls in hand-written bodies,
(c) nested reaction/cursor derefs (automatic).

Layering note: `kmet.libs.reakt` has no TUI dependencies, so
`kmet.app.*` (non-ui) code may require it directly for derived state and
reactions outside any component — only `kmet.tui.*` itself is off-limits
to the app layer.
A bare `@plain-atom` inside a hand-written reaction body is UNTRACKED —
correct under the batched fallback (§2.5), just not narrow.

### 3.2 `track!` — the reactive render cache

Record components wrap their render body:

```clojure
(defcomponent Text nil [text-atom padding-x padding-y bg-fn cache]
  (render [this width]
    (track! this width ...)))
```

Every `@atom` read in the body is recorded; when any changes, the cache
invalidates automatically — setters become plain `reset!`/`swap!`, no manual
`(protocols/invalidate c)` calls. While all tracked values are unchanged the
cached result returns untouched. `defcomponent` generates the
cache-clearing `invalidate` method when the render uses `track!`; write an
`invalidate` method only for extra side effects (delegating to children,
requesting renders).

- **`track-deps`** declares dependencies inside a track! body whose *values*
  don't appear there but must still invalidate: `(track-deps @a @b)`.
- Atoms the render body itself mutates should be read through non-tracking
  helpers so they don't self-invalidate.
- **Do NOT use track!** for: transparent parents (Container, Box, VStack,
  HStack, ScrollView, ChatHistory — children change independently and the
  parent can't track that), time-animated output (spinners, status flashes —
  must render fresh every pass), and focused input widgets (editor/input).
  A child's internal state affecting output means the parent must deref it
  too.

### 3.3 Derived state — `hiccup/compute`

Derived refs over the atoms the app already owns — sugar over a reaction
whose body reads each listed dep tracked and applies F to their current
values:

```clojure
(defn compute
  "Re-derives when any dep changes by =, applying F to the deps' current
   values; equal results notify nobody." [deps f] ...)
```

Anything F reads through tracked channels joins the discovered set
automatically. Equal-value recomputation notifies nobody → fine-grained
invalidation for free, and invalidation schedules the next frame (§6) —
subscribing is enough to stay live, no manual request-render.

Two usage patterns over the one primitive:

```clojure
;; Per-instance — compute under with-let: created once, disposed with
;; the instance (automatic when created during a render pass):
(defn message [props]
  (with-let [content (hiccup/compute [(:messages-atom props)]
                          #(get-in % [(:idx props) :content]))]
    [:text {:text @content}]))

;; Shared — def'd top-level computes; (def ...) IS the registry:
(def agent-status-sub (hiccup/compute [agent-state] :status))
(defn status-line [_props] [:text {:text (str @agent-status-sub)}])
```

There is deliberately no `reg-sub`/`subscribe`. Create computes ONCE per
instance — one built bare inside a render body leaks a reaction per pass
(visible as `:computes` climbing in hiccup's `--debug` counters).

---

## 4. State handling — global / local / props

Three homes for state, decided by one question: *how many components read it?*

| State | Home | examples |
|---|---|---|
| Read by ≥2 components | **Global**: app-owned domain atoms + computes/subscriptions | session state, config, theme, messages |
| Read by 1 component (+ children) | **Local**: `with-let` bindings / record state fields | filter text, expansion, selection, draft |
| Passed to a child as configuration | **Props**: re-applied by reconcile every pass | labels, callbacks, indices, layout params |

The payoff: app code stays pure data — a `swap!` on a domain atom watched
by a compute replaces all find-component-and-poke-its-setter plumbing:

```clojure
;; app layer: pure update, no component knowledge
(swap! messages-atom assoc-in [idx :content] new-text)

;; view: the message component subscribes to its slice (§3.3 pattern)
```

Where the UI *edits* the global state, the write half is a writable
cursor (§3.1) — the same pure-data story with a setter instead of a
`swap!` at the call site:

```clojure
(def transport (reakt/writable-cursor cfg [:http-transport]))

@transport                                   ;; tracked read
(reakt/cursor-reset! transport :babashka)    ;; write back, =-gated
```

**Hot-path carve-out**: the transcript is NOT a fn component re-deriving
from a message seq — that would be O(transcript) rebuild per token. It
stays records with instance storage; screens reference it as a splice/tag.

---

## 5. Lifecycle

### 5.1 dispose

`IComponent` has a `dispose` method (default no-op, synthesized by
`defcomponent`). Callers invoke it unconditionally when a component leaves —
overlay close, reconcile removal, shutdown; implementations must be
**idempotent**.

- **Order is contractual**: containers dispose children first, then run
  their own cleanups — a child cleanup may still read intact parent state.
- Child lists are replaced through `container-replace-children!` when the
  old children are discarded for good — it disposes the ones it drops, so
  a rebuild cannot leak their track! watches. `container-set-children!`
  moves children without disposing: use it when the previous children are
  reused elsewhere (the imperative equivalent of the reconciler's dispose
  branch). Rebuilding by hand (`container-clear` + re-add) is the leak the
  helper exists to prevent.
- Hand-rolled implementors (reify/defrecord outside `defcomponent`) MUST
  include `dispose` — there is no universal default under SCI.
- `defcomponent` prepends track-watch teardown to every dispose: watches
  must never outlive the component ("zombie watchers").
- Timers/intervals belong in `dispose` — a dropped component must not keep
  a ticker invalidating forever.
- Trees compiled outside a mount are disposed by their holder via
  `hiccup/dispose-tree!` (widget replacement, dialog close) — see §2.7.
- Do NOT branch on `(satisfies? SomeProto reify)` under Babashka/SCI —
  satisfies? can return false for sci reify instances. Dispatch through
  the protocol's multimethod instead (methods register reliably); this is
  how extension dialog/widget disposal routes.

### 5.2 Local state — with-let

Transient state for fn components (Reagent Form-2's good half):

```clojure
(defn timer [props]
  (with-let [start (system-time)]          ;; init: once per instance
    (str "elapsed: " (- (system-time) start) "s")
    (finally (stop-timer!))))              ;; cleanup: once at dispose
```

Bindings initialize exactly once per instance; the body re-runs every pass
with the same values. A top-level `(finally …)` is stolen as cleanup (runs
LIFO across nested with-lets).

Footguns:

- Fn bodies must be **pure per pass** — creation-time side effects go in
  `with-let` init, cleanup in `finally`.
- Subscriptions/computes are created **once** (under `with-let` or shared
  `def`s) — never bare in the body.
- `with-let` works only inside a component body (the wrapper binds the
  store); calling it from an async callback throws loudly.
- A TOP-LEVEL `(try … (finally …))` in the body is captured by the cleanup
  extractor (same footgun as Reagent's) — nest the try inside a let when
  you need both.
- Using one expansion site twice in one render pass throws — each instance
  needs its own element.

---

## 6. Frame scheduling

**A dependency change schedules the render** — the other half of the
reactive loop:

- All invalidation funnels through `kmet.tui.macros/invalidate-cache`;
  track!'s watches, generated invalidate methods and subscription teardown
  all reach it, and it fires the frame hook (installed by `tui.core` on
  start, cleared on stop; a no-op default keeps headless tests pure).
- Coalescing is free: `tui-request-render` sets an idempotent flag polled by
  the ~16ms loop; N invalidations between frames collapse into one.
- Equal-value no-ops stay no-ops end to end: a compute recomputing to the
  same value requests no frame.
- Manual `tui-request-render` stays valid forever (idempotent); keep it next
  to ordering-sensitive mutations (focus changes, scroll-to-end, overlay
  show/hide) and before mutations nothing else tracks yet.
- The hook runs inside a watch on the mutating thread and must not throw.
- **Time-driven work uses the timer registry (§6.1)** — not its own thread.

### 6.1 Timers — `kmet.tui.timers`

The one place UI timing lives. The frame loop calls `pump!` once a tick and
fires whatever is due, so a thunk runs on the **loop thread** — the thread
that renders — and may touch widgets and component state directly:

```clojure
(def id (timers/every! 1000 #(swap! now-atom (System/currentTimeMillis))))
(timers/after! 1500 #(swap! flash-atom dec))     ; one-shot
(timers/cancel! id)                              ; idempotent
```

- `after!` fires once, `every!` repeats until cancelled, both return an id.
- A repeating timer reschedules from **now**, not from a missed due time: a
  spinner that fell behind must not fire a burst to catch up.
- A thunk that wants a repaint mutates tracked state (the reactive chain
  schedules the frame) or calls `macros/schedule-frame!`.
- A throwing thunk is logged and swallowed; its repeating timer keeps its
  next tick — the `schedule-frame!` policy.
- `tui-stop` calls `cancel-all!`, so no timer outlives the session; a
  component still cancels its own id in `dispose` (that is what keeps a
  dropped component from poking a dead tree), and `cancel!` is idempotent so
  a double stop is harmless.
- Headless tests drive `pump!` by hand — no sleeps, no wall-clock races.
- **Not for**: I/O timeouts (the input pipeline's sequence/negotiation
  flushes, OSC-11 query deadlines) and background pollers (the theme-file
  watcher). Those must work with no loop running, and must not be throttled
  to the loop's cadence.

---

## 7. Input — imperative by design

Input goes to the **focused leaf only** (`tui/tui-set-focus`; pi parity —
Kitty release events, IME and focus routing are machinery the tree never
sees). Consequences:

- **No declarative input props, ever**: a `:on-key` prop in a tree is a
  design error, not a missing feature. Interactivity = focus + widget
  records (Editor/Input/SelectList/SettingsList) + `:ref` + keybindings.
- Containers do not receive input; a Box exists for padding/background.
- Key names come from `kmet.tui.keys` (`keys/KEY-UP`, `(keys/ctrl "p")`,
  …); `kmet.tui.keybindings` maps binding IDs to resolved chords with user
  overrides and conflict detection.
- **Widgets resolve their own keys through the manager** (P3): SelectList,
  Input, SettingsList and the editor match `tui.select.*` / `tui.input.*` /
  `tui.editor.*` through the global KeybindingsManager — the editor prefers
  an injected one, like pi's `CustomEditor` — so a user override in
  `keybindings.edn` moves the widget itself, not just the hint that reads
  the same table. The editor checks `tui.editor.historyPrevious/Next` after
  interrupt/exit but before the other app actions (pi: custom-editor), so a
  user can bind ctrl+p to history even though it cycles models by default,
  while escape/ctrl+d still cancel/exit.
- **The TUI definition table is pi's `TUI_KEYBINDINGS` plus a fixed set of
  kmet deltas**, all rebindable (one id = all its chords; a user override
  replaces the whole set):
  - kmet chords folded onto pi ids: `tui.editor.deleteCharBackward` +=
    ctrl+h, `tui.input.tab` += ctrl+i, `tui.select.up/down` += ctrl+p/ctrl+n
    (vim alternates), `tui.input.newLine` += ctrl+enter/alt+enter,
    `tui.editor.jumpBackward` += ctrl+shift+], `app.tree.editLabel` +=
    legacy `L` (terminals that send uppercase instead of shift+l);
    `tui.select.cancel` already carries escape/ctrl+c from pi.
    A kmet delta that shares a chord with a widget's own app id means the
    widget must check the specific id first — the scoped-models selector
    checks `app.models.toggleProvider` (ctrl+p, pi) before navigation.
  - kmet-only ids (pi has the action without a binding, or the action
    itself): `tui.editor.redo` (ctrl+z; pi's undo is one-way, and in the app
    `app.suspend` still owns ctrl+z — app actions dispatch first),
    `tui.editor.killLine` (ctrl+w, the whole-line kill — the editor tries it
    before `deleteWordBackward`, which also claims ctrl+w from pi and takes
    over if the user moves killLine away), `tui.select.first`/`last`
    (home/end), `tui.settings.cycleBackward`/`cycleForward` (left/right).
- **What stays raw** is either pi's own raw matching or a deliberately
  order-dependent kmet extra:
  - pi raw: `space` (SettingsList, config screen), `ctrl+c` (config and
    scoped-models screens), `escape` (scoped-models), the auth selector's
    `j`/`k`, and the editor's `shift+backspace`-style encodings.
  - kmet extras: SelectList's `shift+pageUp`/`shift+pageDown` paging — a
    shared def cannot carry it, because matching `tui.select.pageUp`
    (`pageUp`, pi) would make the list eat the plain chord; the thinking
    selector's ctrl+c clear-search-then-cancel (it must not run through
    `tui.select.cancel`, or escape would clear instead of cancel); and the
    editor's late ctrl+p/ctrl+n history fallback, which fires only after app
    actions declined the chord (a def would beat `app.model.cycleForward`).
- The standalone editor cancel is `tui.select.cancel` (pi's base Editor has
  no escape leg — its CustomEditor owns `app.interrupt`), and
  `tui.input.copy` (ctrl+c) hands the key back to the parent before any
  cancel.
- Widgets implement `handle-input`; dialogs trap keys manually around their
  focused editor.

**Modality + focus home.** Input reaching a focused-but-inert component
is the silent failure mode of focus-only routing, so two rules keep it
recoverable:

1. **Modality is enforced at dispatch.** While a visible capturing overlay
   exists, `dispatch-input!` snaps focus to its component before delivery —
   focus stolen by anything else comes straight back, and a hidden or
   removed overlay never keeps receiving keys. There is no restore state
   machine to drift out of sync.
2. **Restore resolves from live state.** When an overlay stops capturing
   (set-hidden! / unfocus / removal), focus goes to the visible overlay
   below it, else the app-registered **focus home**, else null (keys drop
   at the dispatch guard). No snapshot of "what was focused before" is
   kept and no tree walk validates it - both rotted once already. Removal
   specifically is guarded by a watch on the stack atom
   (`::ghost-guard`): a swap! that removes the focused entry always
   restores, so a future removal path that forgets to cannot orphan
   input.

The app layer registers the home once per session; interactive points it
through the dock state and the ACTIVE editor so custom-editor swaps stay
live:

```clojure
(tui/tui-set-focus-home! t #(or (:component @dock-current)
                                @current-editor-atom))
```

`kmet.tui.core` stays generic: it knows nothing about editors, only about
the thunk. If the home is unregistered or throws, focus becomes null -
input drops at the dispatch guard rather than reaching a removed dialog.

### 7.1 Key labels — how a chord is shown

Two forms, one table (`kmet.tui.keys/key-label`):

| form | fn | renders |
|---|---|---|
| raw | `keybindings/key-text`, `app-kb/key-text` | `pageUp`, `alt+b` — for settings screens and anything machine-facing |
| label | `keybindings/key-label-text`, `app-kb/key-label` | `pgup`, `alt+←` — for hints and help lines |

`key-label` maps one chord: `pageUp` → `pgup`, `pageDown` → `pgdn`,
`escape` → `esc`, `up`/`down`/`left`/`right` → `↑`/`↓`/`←`/`→`; everything
else renders as itself, and a modified key relabels its key part only
(`alt+left` → `alt+←`). `keys/key-text`/`key-label-text` join an id's
chords with `/`. `key-hint` renders through the label form, so a hint line
and the tree help cannot drift; the tree selector's private prettify pass
was replaced by the shared table.

---

## 8. Protocols

Exactly three component protocols, by design:

```clojure
(defprotocol IComponent            ; implemented for you by defcomponent
  (render [this width])            ; -> lines (seq of strings); required
  (handle-input [this data])       ; default no-op
  (invalidate [this])              ; default: cache clear (+ your extras)
  (dispose [this]))                ; default: watch teardown (+ your extras)

(defprotocol IFocusable            ; focus routing (input/editor/select/settings lists)
  (focused [this]) (set-focused! [this val]))

(defprotocol IEditorComponent      ; extension seam for alternative editors
  (editor-get-text [this]) (editor-set-text! [this text]) …)
```

The package has one more protocol, outside the component model:
`kmet.tui.terminal/ITerminal` — the lean platform seam every terminal
backend implements (`start!`/`stop!`/`started?`/`write-output`/
`read-input`/`columns`/`rows`/`set-progress!`). The cursor/clear/title
verbs, Kitty/query wrappers and the drain loop are plain fns above it, so
a backend supplies only its platform primitives (`kmet.tui.terminal-jline`,
`kmet.tui.terminal-native` — §13).

Notes:

- Components needing extra protocols (e.g. IFocusable) use a separate
  `extend-type` form after the `defcomponent`.
- Message-style components carry their kind as DATA: `defcomponent Name
  kind [fields…]` stamps KIND as the record's first field; dispatch reads
  `(:kind component)`. There is no kind protocol.
- `render` always returns lines, never a tree — the tree level belongs
  above the protocol, in the DSL.

---

## 9. Theming

All styling goes through `kmet.tui.theme`; raw ANSI escapes are banned
outside `src/kmet/tui/` and `kmet.libs.terminal`.

```clojure
(theme/fg theme :primary text)     ; wraps, resets fg only (\u001b[39m)
(theme/bg theme :user-message-bg text)
(theme/bold text) (theme/dim t) (theme/italic t) …   ; attribute-specific resets
(theme/get-fg-ansi theme :accent)  ; raw escape for a known color (throws on unknown)
```

Attribute-specific resets (not catch-all `\u001b[0m`) make nested styles
compose correctly.

The active theme is a reactive input: `theme/theme-atom` (a plain atom).
Components subscribe through a shared compute — e.g. the app defines
`kmet.app.ui.subs/theme-sub` = `(hiccup/compute [theme/theme-atom]
identity)` — instead of receiving theme as a constructor argument; a palette
switch invalidates exactly the subscribed subtrees. Construction-time
snapshot reads (`get-current-theme`) remain valid.

Theme definitions are EDN files (`examples/themes/` for format); the
color mode (`:truecolor` / `:256color`) is detected from the environment at
construction (`COLORTERM=truecolor|24bit` → truecolor, else 256-color — the
safe default) and baked into the resolved ANSI strings, so a theme built on
a non-truecolor terminal never emits truecolor codes that would degrade.
`make-theme`'s optional mode argument pins it (pi: `createTheme`'s mode);
nil detects.

---

## 10. Component catalog

All under `src/kmet/tui/components/`, constructed via `make-*` fns (or the
DSL tags of §2.2):

| component | purpose |
|---|---|
| `text` | multi-line word-wrapped text, optional padding/bg |
| `truncated_text` | single-line truncated text |
| `markdown` | markdown renderer with syntax highlighting |
| `box` | padding + background wrapper (no input) |
| `container` | transparent child list |
| `stack` / `v_stack` / `h_stack` | vertical/horizontal layout, gaps, entry maps |
| `scroll_view` | viewport scrolling around a child |
| `dynamic_border` | border drawn around current content dimensions |
| `input` | single-line input widget (focusable) |
| `editor` | multi-line editor: wrapping, undo/redo, kill-ring, history, paste markers, autocomplete hooks (focusable) |
| `editing` | grapheme/cursor editing primitives behind the editor |
| `select_list` / `settings_list` | interactive lists (focusable) |
| `spinner` | animated indicator (time-animated — never cached) |
| `cancellable_loader` | loader with abort signal |
| `expandable_text` | collapsed/expanded long text (deref-aware caching) |
| `image` | inline image protocol rendering (kitty/iTerm style) |
| `alt_screen_flash` | alternate-screen takeover + restore |

Frame glyphs come from `kmet.tui.border` (§2.8), not from the components:
`dynamic_border` and `editor` draw a rule, `markdown` its table, and the
app-layer `bash_execution` its box — each with a `:border` style.

Message-like app components live in `kmet.app.ui.*`, not here — this layer
stays generic: chat history, tool executions, the skill invocation
message (`skill_message`, which renders a `/skill:name` block as a
collapsible `[skill] name (ctrl+o to expand)` entry — pi:
SkillInvocationMessageComponent), and the inline image block
(`image_block`: one image rendered as the terminal image, or as the
`imageFallback` text indicator when the `:terminal {:show-images …}`
setting is off or the terminal lacks protocol support — pi:
ToolExecutionComponent's Image child + `getTextOutput`).

---

## 11. Debugging rendering

### Print what a component renders

The fastest way to see what a component produces: render it headless and
print the lines —

```clojure
(require '[kmet.tui.hiccup :as hiccup])

;; any tree data …
(doseq [l (hiccup/render-lines [:box {:padding-x 1} [:text "hi"]] 40)]
  (println l))

;; … or a live record instance (records pass through compile untouched)
(doseq [l (hiccup/render-lines my-component 80)]
  (println l))
```

Notes:

- `render-lines` accepts one element vector, a seq of roots, or a single
  record; it returns exactly the lines the frame loop would draw.
- It is a ONE-SHOT inspection tool: DSL-owned roots are disposed after the
  call — don't reuse it as a second render path for mounted components.
- Lines carry ANSI styling; pipe through `cat -v` (or strip escapes) when
  eyeballing. For width math use `kmet.tui.utils/visible-width`, never
  `.length` — escape bytes count otherwise.
- Invalidation debugging: render before and after a state change and diff
  the line seqs — identical output proves keyed reuse and caches held (§2.7).
- Never `println` from inside a live TUI's render bodies or input handlers:
  stdout writes land mid-frame and corrupt the display. Use the logs below.

### Per-frame counters

Under `--debug`, hiccup exposes process-wide counters:

```clojure
(hiccup/counters)
;; {:bodies-run 2 :bodies-skipped 37 :constructs 0 :reuses 5
;;  :applies 1 :disposals 0 :computes 4}
(hiccup/reset-counters!)   ;; back to zero (tests)
```

Reading them: `bodies-run` climbing on frames where nothing the body derefs
changed means either an inline-callback trap (fresh fn literals in props,
§2.5) or broken equality; `computes` climbing frame over frame means a
compute created bare inside a render body instead of under `with-let`
(§3.3); `applies` (the apply-path count, §2.3) climbing every frame on a
stateful tag whose props never settle means fresh fn literals in its props
— the tag is patching rather than reusing.

### Frame dumps & full-redraw reasons (env flags)

- `KMET_TUI_DEBUG=1 bb run` — every frame dumps `newLines` vs
  `previousLines`, viewportTop, hardwareCursorRow and size into
  `$TMPDIR/tui/render-*.log` (or `java.io.tmpdir/tui/` when `TMPDIR` is
  unset — this babashka hardcodes java.io.tmpdir to `/tmp`; pi: PI_TUI_DEBUG).
- `KMET_DEBUG_REDRAW=1 bb run` — appends one line per FULL redraw with its
  trigger reason to `kmet-debug-render.log` (cwd); a steady stream during
  normal streaming points at shrink/full-redraw churn. `firstChanged <
  viewportTop, scrollback only` is NOT a full redraw — it is the benign
  in-place/no-op path for a change above the window (§1).

### Crash + error logs

| file | written when |
|---|---|
| `kmet-crash.log` | a rendered line exceeds the terminal width — dumps all rendered lines with visible widths + the offending index; the frame truncates the line and keeps running |
| `render-crash.log` | a render body threw — full stack trace, then the TUI stops (loud-crash contract, §2.5) |
| `debug.log` | opt-in via `--debug`: lifecycle events (submit, cancel, agent turns) |
| `kmet.error.log` | unhandled top-level exceptions |

### When bytes look wrong but headless render looks right

Scroll-region/diff bugs are invisible at the lines level. Capture the
session's raw output — `scripts/tmux_capture.sh` or
`scripts/pty_capture.py` — and replay it through the minimal ANSI emulator:
`python3 scripts/term_dump.py out.raw` prints the frames (with colors) at
sync boundaries. See AGENTS.md ("Debugging scripts") for the exact
invocations.

## 12. Testing & performance invariants

- **Headless first**: `hiccup/render-lines` covers construction, keyed
  reuse, caching and invalidation without a terminal (§2.7). Real-terminal
  behavior (raw mode, query timeouts, subprocess spawns) belongs in
  `^:slow` tests.
- **Idle-UI invariant**: an idle UI runs zero fn bodies and zero reaction
  re-runs — render a tree twice with no state change between passes;
  invocation counters (§11) must stay flat. This pins the memoization
  contract (reactions + caches + equality no-ops).
- **Timers are pumped, not slept on** (§6.1): a test drives
  `timers/pump!` by hand instead of waiting for a real interval, so timer
  assertions are deterministic. Clean up with `timers/cancel-all!` in a
  fixture when a case arms timers directly.
- New test namespaces register in `kmet.tasks.runner/all-namespaces`.

---

## 13. Layer boundaries

```
kmet.app        owns atoms, pure data updates (no component knowledge)
kmet.app.ui     fn components (shared def'd computes, with-let local state)
                + hiccup/root mount points
kmet.tui        reagent, hiccup, macros, protocols, components — generic;
                no app/chat/session concepts; may depend on kmet.libs.*
kmet.libs.*     self-contained (terminal protocol lives here too)
```

`kmet.tui.*` must never require `kmet.app.*`, `kmet.modes.*` or
`kmet.ai.*`; app-specific components belong in `kmet.app.ui.*`.

---

## 14. Roadmap

Sections 1–13 describe current behavior. **The items below are not
implemented** — this is a plan record, kept so the analysis behind the
decisions is not lost. When an item lands, fold its behavior into the
relevant section, add it to the Done table and strike it from the plan; a
declined item moves to "Deliberately not borrowing" with its rationale.

Sources: the R items are ideas borrowed from glimmer (R1–R6 landed, R3b
and R7 declined 2026-09-11); the P items are pi parity (P1–P3 landed). The
remaining kmet↔pi gaps are tracked in `alignment.md` (§2, §6), and the
rendering-shaped ones are postponed below.

- **R items — ideas borrowed from [glimmer](https://github.com/jolt-lang/glimmer)**
  (a reactive core + reagent-style component model targeting Jolt) and
  [glimmer-tui](https://github.com/jolt-lang/glimmer-tui), its ncursesw
  terminal backend. Both MIT; neither is a dependency. Glimmer's layer is
  not adoptable wholesale: it has no width, no input/focus, no disposal and
  no render cache, and a parent re-render re-invokes every child body —
  whereas kmet's narrower layer already runs on both bb and Jolt. So the
  R items are idea-level borrows only.

### Done

| # | idea | landed as |
|---|---|---|
| R4 | loop-owned timer registry | `kmet.tui.timers` (§6.1) — `after!`/`every!`/`cancel!`/`cancel-all!`, pumped by the frame loop, cancelled by `tui-stop`; the bash driver + elapsed tick, the running-tool repaint, the scrollbar-hide debounce, flash expiry and the selector status auto-hide all ride it |
| R5 | border sets as data | `kmet.tui.border` (§2.8) — `:border` on `:dynamic-border`, `:markdown` (table glyphs), `:editor`; `make-bash-execution :border` |
| R6 | `^{:key}` metadata | keys read from element metadata as well as the `:key` prop (§2.1) |
| R3a | key labels | `keys/key-label` + `keybindings/key-label-text` (§7.1) — hints and the tree help render `pgup`/`↑`, replacing the private `prettify-keys` regex pass |
| R1 | prop→state apply path | a `:apply (fn [comp prev-props props])` spec on the tag table + the apply branch in `reuse-or-build` (§2.3): all seven stateful tags patch the live instance on a changed prop (state and focus survive) and decline to rebuild only when the tag cannot express the prop (`:border`/`:keybindings`, `:enable-search`, `:frames`/`:interval-ms`, a loader's `:spinner` child); state-carrying props are written only when THAT prop changed (live edits survive unrelated changes) and coerce like construction (nil ⇒ default); new `select-list`/`settings-list` setters (`-set-height!`, `-set-on-select!`, `-set-items!`, …) back the patch paths, and `cancellable-loader`'s protocol dispose now stops its spinner (pi: dispose → stop). The §4 props/state migration landed with it: `:box` (`:padding-x`/`:padding-y`/`:bg-fn`), `:v-stack`/`:h-stack` (`:gap`, `:align`) and `:scroll-view` (all six props, via new setters) declare TOTAL applys — containers never rebuild (a fresh construct would lose the subtree), so their structural props are live instead of create-time |
| R2 | writable cursor | `kmet.libs.reakt/writable-cursor` + `cursor-reset!`/`cursor-swap!` (§3.1): a tracked-read lens that writes back through its source with `assoc-in`, `=`-gated, nested lenses composing, inert once disposed; read-only `cursor` stays the derivation primitive |
| P1 | skill invocation message | `kmet.app.skills/parse-skill-block` (the inverse of the expander) + `kmet.app.ui.skill-message` — a `/skill:name` block renders as a collapsible `[skill] name (ctrl+o to expand)` message instead of dumping its body into the transcript |
| P2 | images in chat (TUI half) | `kmet.app.ui.image_block` + the live `ui.subs/image-settings-sub`: tool-result and user/custom-message images render inline, or as the `imageFallback` text indicator when `:show-images` is off / the terminal lacks support; `:terminal {:show-images :image-width-cells}` in `config.clj` + terminal-support-gated `/settings` rows. The wire half landed separately: `images.blockImages` = `app/loop.clj` (`convertToLlmWithBlockImages`) + an ungated `/settings` row; `images.autoResize` stays provider work (tracked in `alignment.md` §2) |
| P3 | widget keys through the manager | `kmet.tui.keybindings/global-match?` + the editor's injected-manager `kb-match?` (§7): SelectList, Input, SettingsList and the editor resolve their own ids through the KeybindingsManager, so a user override moves the widget; the editor resolves `tui.editor.historyPrevious/Next` between interrupt/exit and the other app actions (pi: custom-editor order). The TUI definition table was aligned to pi's `TUI_KEYBINDINGS` (`historyPrevious/Next`, `jumpForward/Backward`, `yankPop`, `ctrl+left/right`, `ctrl+home/end`, `ctrl+pageUp/Down`). Follow-ups (same day): kmet extension ids `tui.editor.redo`/`tui.editor.killLine`/`tui.select.first`/`tui.select.last`/`tui.settings.cycleBackward`/`tui.settings.cycleForward`; folded alias chords on pi ids (`ctrl+h`, `ctrl+i`, `ctrl+p`/`ctrl+n`, `ctrl+enter`/`alt+enter`, `ctrl+shift+]`, the tree's legacy `L`); the thinking selector's `app.thinking.save` (a pi id kmet was missing) and the config screen's `tui.select.*`/`tui.input.tab` legs (pi's raw space/ctrl+c kept). §7 lists the remaining raw matches and why each stays raw |

### Plan

Nothing tracked. The two glimmer borrows that were pending here — R3b
(focus-derived help line) and R7 (declarative `:overlay`) — were
re-evaluated and declined 2026-09-11; the analysis is recorded under
"Deliberately not borrowing" so it is not redone.

### Postponed indefinitely

Decided 2026-09-10: not planned, not tracked further. Recorded so the
analysis is not redone — revisit only on a concrete user request.

| feature | pi ref | why not |
|---|---|---|
| Mermaid diagrams | `markdown.mermaid` setting (`off`/`final`/`streaming`) | needs a layout engine (pi ships a mermaid renderer); terminal payoff is poor and the markdown path is already the largest renderer |
| LaTeX rendering | `tui/src/latex.ts` | same shape of work as Mermaid, smaller audience |
| Alt-screen search | `alt-screen-search.ts` | needs a fullscreen/alt-screen mode (below) and the transcript model here is the native scrollback, not an owned viewport |
| Fullscreen (alt-screen) TUI mode | `--tui-mode` | the opposite of the deliberate inline model (§1: transcript in the native scrollback, `\u001b[3J`-based full redraws); an alt-screen mode would fork the renderer, the scroll model and every overlay/scroll assumption |

### Deliberately not borrowing

Recorded so the analysis is not redone:

- **Two-pass box layout** (`measure`/`arrange`, per-node `:natural`/`:min`,
  `:hexpand`/`:halign`, margin/padding shorthand, proportional
  `shrink-to-fit`, serve-in-order-then-clip) — kmet renders *lines*:
  components return line seqs, stacks concatenate, and the interactive
  layout scrolls the terminal's own scrollback. A box model needs an owned
  screen; keep it as a reference. The transferable insight, should a flex
  layer ever appear: let a node declare what it can survive on, squeeze
  proportionally, clip last.
- **Cell-grid screen + `clip` wrappers** (`:size`/`:clear!`/`:put!`/
  `:cursor!`/`:present!` as a map; clipping as a screen wrapper; a
  placeholder cell after a double-width glyph; painting the node's own rect
  so a partly-scrolled row lands on the right line) — kmet's model is ANSI
  strings + a differential line writer + `slice-with-width`, with the
  over-wide-line guard at the frame boundary (§11). Two details worth
  remembering: clipping should be a no-op, never an exception; and
  `drop-cells` pads the gap a straddling wide glyph leaves at a *left-edge*
  cut (kmet's `:strict?` drops the glyph instead — right for editor
  windowing, wrong for column-aligned output).
- **`IReactiveCell`** (swappable cell backend) — `tracked-deref` +
  `watch-ref` is already kmet's seam, and Babashka seals
  `IWatchable`/`IReset`, so a drop-in atom cannot exist anyway (§3.1).
- **Focus ring recomputed from the tree + `:autofocus`** — kmet focus is
  imperative and dialog-scoped (§7). The one idea to revisit is
  `:autofocus`: it exists so a focused text field does not swallow the
  app's single-key bindings before the user presses Tab.
- **Mouse hit-testing / wheel-under-pointer** — kmet parses mouse
  sequences only to keep the input buffer clean (§7) and has no owned
  viewport to hit-test. A feature (alt-screen region), not a transplant.
- **`reload!` / `run-async` / `usable-terminal?`** — kmet's dev loop is
  nREPL + `tui-invalidate`, and it owns its terminal adapter
  (`kmet.tui.terminal`: JLine on bb/JVM, termios/kernel32 FFI on Jolt —
  both behind the `ITerminal` protocol).
- **Focus-derived help line (R3b)** — glimmer-tui derives a help bar from
  the focused widget's `:bindings`. Declined as specced 2026-09-11:
  "focus-derived" is nearly vacuous here (focus is imperative and every
  dialog has exactly one focusable child, so a declaration would just
  name that child), and hint choreography is dynamic per dialog
  (delete-confirm rows, expand↔collapse, filter modes). Key text cannot
  drift already — hints read the shared chord table (§7.1) — so only the
  per-dialog item lists stay hand-written, which is where they belong.
  One piece stays opportunistic, untracked: the tree selector's chunk
  renderer (`compact-raw-keys`/`format-help-keys` plus wrapping) is the
  only wrapped hint composition — extract it into a shared help-line
  helper if a third dialog needs one.
- **Declarative `:overlay` (R7)** — glimmer-tui declares an overlay in the
  tree: no space at its declaration site, painted last and never clipped,
  modal focus capture, Esc closes. Declined 2026-09-11. The pain is real
  but small: dialogs are shown imperatively (`tui-show-overlay`), so
  declaration site ≠ owner, yet only ~4 flows float — tree label-edit
  input, the branch-summary asks, custom-summary input, extension
  `ui-custom` overlays; every other panel docks in the editor like pi's
  `showSelector`, which the dock already renders declaratively. The
  imperative stack would stay underneath regardless (placement, sizing,
  z-order and focus restore are session-owned; kmet renders lines, with no
  screen coordinates to anchor to, so "painted last, never clipped" is
  moot), and keeping overlay identity and focus order stable across
  re-rendered declarations is the real work — the tree→session hook is
  the spike. Esc-closes conflicts with per-dialog escape semantics (tree
  back-navigation, login cancel); pi is imperative (`showOverlay`), so a
  declarative path would fork every future dialog port, and extension
  overlays must stay imperative anyway. If dialog-chaining ownership ever
  bites, the 80% is an app-level flow helper in `app/ui` (a small state
  stack + `next!`/`back!` over `tui-show-overlay`) — no core changes.

### Suggested order

Nothing tracked. The R3b extraction stays opportunistic (only if a third
dialog needs wrapped chunks); R7 is declined.


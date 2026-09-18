# Hiccup migration plan — `kmet.app.ui.*`

`tui.md` (§2) is the authoritative spec. This file is the migration
inventory: which `app/ui` files still build trees with imperative
`make-*` + `container-add-child` / `container-replace-children!`, which
pattern replaces each case, and what explicitly stays imperative.

## 0. Rule for new code

New screens, dialogs, selectors and chrome are fn components + hiccup
trees (`hiccup/root` for mounted roots, `h/compile-tree` for frames) over
the closed tag set in `kmet.tui.hiccup` (`:box` `:container` `:text`
`:spacer` `:dynamic-border` `:truncated-text` `:select-list`
`:settings-list` `:input` `:editor` `:scroll-view` `:v-stack` `:h-stack`
`:markdown` `:spinner` `:image` ...). Do not extend the legacy
`make-*` + `container-add-child` / `container-replace-children!` style;
when you are already editing a hybrid file, migrate the rows/frame you
touched (Tier 1 first). Reaching a DSL-owned stateful leaf from a wrapper
— focus forwarding, reading an input's value, wiring a callback
post-mount — goes through `hiccup/ref`: the sanctioned escape hatch
(tui.md §2.4), not a workaround.

Reference patterns: `session_selector.clj` (`hiccup/root` body
tracked-derefing rows as `[:text ...]` data from the state atom, its
search/rename inputs tag-owned `[:input]` elements whose text, caret and
emphasis are state fed back as props; the dock disposes on displacement
too — §3.3), `login_dialog.clj`
(`hiccup/root` + `r/tracked-deref` on a row-descriptor atom + static
chrome built once outside the body; mounted `:borrowed?` because the
auth flow re-mounts it around a prompt selector), `fork_selector.clj`
(`compile-tree`
frame + `track!` list returning strings), `bash_execution.clj`
(`hiccup/root` + long-lived `Spinner` spliced foreign), `dock.clj` /
`status_indicator.clj:make-status-area` (fn components).

## 1. The three shapes in the tree today

1. **DONE (hiccup)** — frame/root is a tree; only intentional foreign
   children remain (focused inputs, long-lived spinners, static chrome
   built once outside the body for identity stability).
   `session_selector`, `login_dialog`, `fork_selector`, `bash_execution`,
   `tree_selector` panel, `dock`, `status-area`, plus the four converted
   selectors (`thinking_selector`, `model_selector`,
   `scoped_models_selector`, `auth_selector`) and the `tool_renderers`
   surface (each renderer assembles one `compile-tree`).
2. **HYBRID** — `compile-tree` chrome + imperative content spliced
   foreign: a `Container` of `make-text` rows rebuilt on every filter
   pass, plus `make-input` search fields, plus `make-select-list` /
   `make-settings-list` where a tag would do.
   `dialogs`, `settings_selector`.
3. **KEEP (imperative by design)** — transcript records and string-direct
   `track!` leaves. Not migration targets (§4).

## 2. Inventory (2026-09-18)

| file | shape | remaining `make-*` | plan |
|---|---|---|---|
| `assistant_message.clj` | KEEP | transient `md/make-markdown` per reflow (`render-text-to-width`, `render-thinking-to-width`) | none — render-to-width helper, not a tree node |
| `auth_selector.clj` | DONE | none — root bodies for both selectors, `[:truncated-text]` rows, inputs foreign, `dispose` unwinds both; new `test_auth_selector.clj` (render-driven) | none (Phase 1 #3) |
| `model_selector.clj` | DONE | none — root body, keyed `[:text]` rows, live scope/hint labels as elements, input foreign, `dispose` unwinds both | none (Phase 1 #2) |
| `scoped_models_selector.clj` | DONE | none — root body, keyed `[:text]` rows, live footer as an element, input foreign, `dispose` unwinds both | none (Phase 1 #2) |
| `thinking_selector.clj` | DONE | none — root body, keyed `[:text]` rows, input foreign, `dispose` unwinds both | none (Phase 1 #1) |
| `dialogs.clj` | DONE | none — `[:select-list]` / `[:input]` elements under a `hiccup/ref`, read back right after `compile-tree` (the record keeps its `:select-list`/`:input-comp` field); frame hint bug fixed first (see Phase 2) | none (Phase 2 #1) |
| `settings_selector.clj` | DONE | none — `[:settings-list]` element under a `hiccup/ref`, read back right after `compile-tree`; the `:on-change` case hoisted to a named local, escape wired through the tag's `:on-escape`, one `dispose-tree!` unwinds the whole tree | none (Phase 2 #2) |
| `tool_renderers.clj` | DONE | none — every renderer assembles a `h/compile-tree` (the last imperative legs, `render-edit-result`'s error branch, `render-bash-call` and `render-bash-result`, converted; the mangled token-per-line block reflowed, the collapsed output cap hoisted to `bash-result-preview-lines`) | none (Phase 1 #4) |
| `chat_history.clj` | KEEP + Tier 1 helpers | `make-plain-msg` (204–206), `make-plain-md-msg` (213–215), `StatusLine` (249–250) | Tier 1 optional: helpers → `[:container {} [:spacer] [:text/:markdown/:truncated-text]]`; `ChatHistoryComponent` itself stays a record |
| `session_selector.clj` | DONE (full) | none — both inputs are `[:input]` tags (`:value`/`:cursor`/`:focused?` props over the state mirror; see Phase 2 #3) | none — the body reads state via `tracked-deref` and `hide!` disposes the root (which cascades to the tag-owned inputs) |
| `login_dialog.clj` | DONE (full) | none — the prompt field is a `[:input]` tag whose row descriptor carries its text/caret and whose emphasis is the dialog's focus flag (Phase 2 #4) | none — chrome is `[:dynamic-border]` / `[:text]` elements and the input is tag-owned |
| `bash_execution.clj` | DONE | `spinner/make-spinner` (248) spliced into `hiccup/root` (256) | none — long-lived spinner identity intentional |
| `tree_selector.clj` | DONE | `make-tree-list` ctor (937); `dialogs/make-input-dialog` (1082); panel `compile-tree` (1099) | none — `TreeList` is a string-direct `track!` leaf by design; the close path disposes the frame + the spliced list |
| `user_message.clj` | KEEP | `container`/`box`/`md`/`spacer`/`image-block` (89–114) | none (§4) |
| `custom_message.clj` | KEEP | `container`/`spacer`/`box`/`text`/`md`/`image-block` (81–93,153–155) | none (§4) |
| `summary_message.clj` | KEEP | `text`/`md`/`spacer`/`container`/`box` (129–144,174–183) | none — transcript record (§4) |
| `skill_message.clj` | KEEP | `text`/`md`/`container`/`box`/`spacer` (132–137,175–186) | none (§4) |
| `tool_execution.clj` | KEEP | `container`/`box`/`spacer`/`image-block` (221–222,277–279) | none (§4) |
| `image_block.clj` | KEEP | transient `ic/make-image` per render (54) | none — render-time branch, not a stored tree |
| `status_indicator.clj` | KEEP | `spinner/make-spinner` host-owned (79) | none |
| `resource_config.clj` | KEEP / Tier 3 | `input/make-input` (557); render returns string lines directly | full-screen `hiccup/root` (session_selector pattern) only if rewriting the screen anyway |
| `footer.clj`, `pending_messages.clj`, `loaded_resources.clj` | KEEP | none | string-direct `track!` renders — no tree to migrate |
| `dock.clj`, `subs.clj`, `model_catalog.clj`, `external_editor.clj`, `custom_dialog_adapter.clj`, `footer_data_provider.clj` | N/A | none | fn component / data / adapter — nothing to migrate; the dock owns the *displacement* and *input* halves of a panel's lifecycle (`mount!`/`clear!` dispose the panel they lift out unless it was mounted `:borrowed?`, and its `::focus-guard` watch hands focus back when the occupant leaves — tui.md §7) |

Tier 2's former blockers (no `:on-change` on `:input`, the dialogs
prefill cursor poke, `session_selector`'s post-construct wiring) were all
reachable through refs (§3.2), and the props the tags gained along the way
(`:on-change`, `:cursor`) were ergonomics — but the two input KEEPs were
not: they turned on `:focused?`, because a rebuilt element has no way to
restate its emphasis (§3.2). Tier 1's rows work is mostly plumbing deletion plus one
idiom (see §3.1's "what reuse actually buys") — a wash at today's
windowed selector sizes, real at list scale — and the transcript stays
put for design reasons, not because a DSL container over records would
be slow (§4).

## 3. Migration patterns

### 3.1 Rows: rebuilt `Container` → data-derived `[:text]` seq

Before (every selector repeats this):

```clojure
rows (container/make-container)
...
(container/container-add-child rows (text/make-text line 1 0))
...
(container/container-replace-children! (:list-container this) @(:children rows))
```

After (the converted-selector pattern — rows re-derive from the state
atom inside the tree body, keyed, `r/tracked-deref` on the atom):

```clojure
;; root body (or compile-tree frame in the hybrid step): keep the
;; defcomponent + handle-input + dock mount, drop the refresh fn +
;; list-container.
(defn- row-elements
  "Row data as hiccup elements (replaces *-refresh!)."
  [th filtered selected start-idx end-idx]
  (concat (map (fn [i]
                 [:truncated-text {:key i :padding-x 1
                                   :text (row-line th (nth filtered i)
                                                   (= i selected))}])
               (range start-idx end-idx))
          [(when (or (pos? start-idx) (< end-idx (count filtered)))
             [:truncated-text {:padding-x 1
                               :text (theme/fg th :muted
                                               (str "  (" (inc selected)
                                                    "/" (count filtered) ")"))}])
           (when (zero? (count filtered))
             [:truncated-text {:padding-x 1
                               :text (theme/fg th :muted "  No matches")}])]))

;; pre-conversion shape (compile-tree frame + a foreign input; §3.3's root
;; body or a `[:input {...}]` tag replaces either half)
(h/compile-tree
   [:container {}
    [:dynamic-border {:color-fn border-fn}] ; created once per selector
    [:spacer {:lines 1}]
    search-input ;; foreign: focus target, updated via input-set-value!
    [:spacer {:lines 1}]
    (row-elements th filtered selected start-idx end-idx)
    [:spacer {:lines 1}]
    [:dynamic-border {:color-fn border-fn}]])
```

Rules: a body-built seq splices (§2.1) — always `:key` spliced rows or
prepending rebuilds every unkeyed sibling; **the rows value must be a
seq, not a vector** (a vector is always ONE element: `mapv`/`into []`
rows parse as an element whose head is the first row — `invalid element
head`; `map`/`concat`/`for` splice); `:text` takes the
pre-styled string (truncate with `u/truncate-to-width` against the
known panel width, or move to `hiccup/root` and read
`hiccup/*width*`); the refresh fn shrinks to a pure
state→elements fn, so the `rows-container` / `list-container` field,
`container-replace-children!` and the `text`/`truncated-text` +
`container` requires go away. The `*-refresh!` call sites become
recompiles or (better) a `hiccup/root` whose reaction tracks the state
atom directly. State and input stay where they are (state atom +
`handle-input` + `input-get-value` → `swap!` filter); only the view
becomes declarative. `footer-text`-style live labels (`model_selector`
`:scope-text`, `scoped_models_selector` `:footer-text` via
`text-set!`) become elements too — derived strings in the same
sequence, not setter-poked records.

**What reuse actually buys**: unchanged rows reconcile by reuse (stable
record, its track! cache intact); a row whose `:text` changes is
rebuilt, because the `[:text]` tag carries no patch lens — a text prop
change constructs a fresh Text and disposes the old one. Measured on
the converted `thinking_selector` (4 rows, one arrow move): 1 body run,
2 constructs / 2 disposals — only the two rows whose text changed; the
imperative `container-replace-children!` rebuilt and disposed all 4.
The `bodies-run` win is separate: the root body is memoized on the
tracked state atom, so idle frames run zero bodies and render zero rows
(the table's third column).

**Why rows-first is a measured win**: `container-replace-children!`
rebuilds AND disposes every row per refresh, while reconcile reuses
unchanged children and re-renders cache hits. Whole-list refresh with one
row changing (babashka, this tree):

| rows | imperative rebuild | hiccup rows | hiccup, idle frame |
|---|---|---|---|
| 120 | 0.99 ms | 1.04 ms | 0.12 ms |
| 500 | 13.1 ms | 4.0 ms | 0.51 ms |
| 1000 | 47.0 ms | 8.7 ms | 1.36 ms |

A wash at selector sizes, 3–5× on list-sized content — and it also
deletes the manual refresh wiring. Keyed rows stay the rule: keys keep
reuse position-independent under reordering. Unkeyed siblings share one
match bucket consumed in order (an O(1) bucket cursor since Phase 0), so
without keys a prepend rebuilds every sibling after it.

### 3.2 Stateful leaves: foreign record → tag (`:apply` keeps state)

`dialogs` content, `settings_selector` list, selector search inputs:

```clojure
;; before — owned record spliced foreign
sl (select-list/make-select-list items :height h :theme t
                                 :on-select f :on-escape g)
;; after — same component, DSL-owned, state survives via :apply
[:select-list {:items items :height h :theme t
               :on-select f :on-escape g}]
```

```clojure
[:input {:ref search-ref :on-submit submit! :on-escape cancel!}]
```

Preconditions before converting a leaf: the tag's `:apply` must express
every prop you change per pass (tui.md §2.3 — structural props that
decline the patch rebuild and lose state: spinner `:frames` /
`:interval-ms`, settings-list `:enable-search`, editor `:border` /
`:keybindings`, cancellable-loader `:spinner`). Callbacks as fresh fn
literals per pass defeat prop-equality memoization (§2.5 footgun) — hoist
to named fns or stable values.

**Reaching the instance — refs are the sanctioned path.** Input routing
delivers to the focused leaf only, with no bubbling (tui.md §7), so a
selector stays the focus target and forwards its layout keys plus every
other keystroke. When the embedded input is a tag, the wrapper reaches it
through a ref — the mechanism tui.md §2.4 exists for, with a lifecycle
(fill on construct, clear on dispose or a replaced `:ref` prop) hardened
since this inventory was written:

```clojure
(def search-ref (hiccup/ref))

;; in the root body — the input is DSL-owned and keeps its state
[:input {:ref search-ref}]

;; handle-input / set-focused! — outside render bodies:
(protocols/handle-input @search-ref data)
(input/input-get-value @search-ref)     ;; keystroke → filter sync
```

The tag grew the prop surface the old blockers wanted: Phase 0 landed
`:on-change` (parity with `[:editor]`, fired on value-changing edits) and
`:cursor` (state-carrying like `:value`, nil = unmanaged), and Phase 2
landed `:focused?` — the element's own emphasis flag, applied at
construction and written through on change, never a rebuild trigger
(tui.md §2.4). With text, caret and emphasis all declareable, a tagged
input that a branch swap disposes and rebuilds comes back whole; the ref
stays for *reaching* the instance (forwarding keys, reading its value),
not for keeping it alive.

Each Tier 2 file is one commit with its interaction test
(type-then-rerender keeps text / selection / focus).

### 3.3 Frames: `compile-tree` + foreign content → `hiccup/root` body

Only for screens whose content is already data-derived (selectors first,
`resource_config` last). Move the `compile-tree` vector into a
`hiccup/root` fn body reading the state atom tracked, keep the focused
input foreign (or `:ref`'d tag per §3.2), keep `handle-input` /
`IFocusable` / `dispose`-unwinds-the-root exactly as they are:

```clojure
root (hiccup/root
      (fn [_props]
        (let [st (r/tracked-deref (:state-atom sel)) ...]
          [:container {}
           [:dynamic-border {:color-fn border-fn}]
           [:spacer {:lines 1}]
           search-input ;; or [:input {:ref r ...}]
           [:spacer {:lines 1}]
           (map row->text rows)
           [:spacer {:lines 1}]
           [:dynamic-border {:color-fn border-fn}]])))
```

Pitfalls: build static chrome (borders, titles) once outside the body
and close over it (`login_dialog` pattern — `border` + `title`
constructed once, only the row-descriptor seq re-derives inside) — a
body-built record changes identity every re-run and churns
retire+reconstruct (the converted selectors close over a per-instance
`border-fn` for exactly this reason); `hiccup/*width*` is the truncation
width inside the body (`session_selector` reads `w` once per pass and
threads it through `header-line` / `hint-lines` / `content-lines`);
`compile-tree`-outside-a-mount trees are holder-disposed
(`dispose-tree!`), `root`s via `dispose`. **A root conversion must wire
`dispose`** (`dispose` the root plus any foreign child the root cannot
own — inputs) and make the close path call it; the dock covers the
*displaced* case itself (`dock/mount!`/`clear!` dispose the panel they
lift out unless it was mounted `:borrowed?` — pi's
`disposeActiveSelector` before every `showSelector`), but a panel that
never leaves the dock still needs its own close to dispose — an unwired
root leaks its reaction, and the old splices already leaked their track!
watches on close (verified: the four converted selectors,
`session_selector`, `settings_selector`, `fork_selector` and
`tree_selector` dispose their roots on close, and their watch-registry
entries drop to 0).
`scope-text` / `footer-text` / `hint-text` conditionals
(`model_selector:269-274`) become `when` elements inline — nil splices
free (§2.1) — instead of nil-or-record fields.

Read the state atom with `r/tracked-deref` — never a bare `@`: an
untracked read leaves the reaction without deps, so the body re-derives
on every render pass instead of on change — correct output, silent loss
of the memoization (`bodies-run` climbs in `hiccup/counters`).
`bash_execution` is the reference (`(r/tracked-deref state-atom)`).
Memoization is safe under state mutated from futures/timers: a run
compares the values it read against their deps' current ones after
registering watches, so a write landing mid-run re-runs it instead of
being swallowed (tui.md §3.1;
`test-dep-written-mid-run-still-invalidates`).
Theme reads are plain
(`theme/get-current-theme`): a memoized body restyles on its next derive,
while the stable per-instance border `:color-fn` re-reads the theme at
every render (borders restyle live, chrome on the next state change —
the same asymmetry the old selectors had).

## 4. Non-goals (stays imperative)

- **Transcript hot path** — `ChatHistoryComponent`, `UserMessage`,
  `AssistantMessage`, `CustomMessage`, `SkillInvocationMessage`,
  `ToolExecutionComponent`: records with instance storage, `track!`
  caches, theme apply-once, renderer-state / last-component dedup, image
  children lifecycle, streaming reflow, and persistence reading the
  message maps directly. What must NEVER move to the DSL is message
  *content* re-derived as data inside a body — a token append would
  re-run the body and rebuild O(transcript) elements, superlinear
  (measured: ~6× the record at 300 messages). A DSL **container** that
  only splices the message records and tracks the messages vector is a
  different thing: its body re-runs on add/remove, not per token, and it
  measures on par-or-slightly-better than the record (2400 messages: 6.9
  vs 7.4 ms per streaming pass; 6.2 vs 6.6 idle). It stays a non-target
  anyway — it buys a few percent and costs the persistence/lifecycle
  clarity that is the record's reason to exist. Helpers under
  `chat_history` (§2, Tier 1 optional) are the only transcript-adjacent
  exception.
- **String-direct `track!` leaves** — `footer`, `pending_messages`,
  `loaded_resources`, `ForkMessageList`, `TreeList`, `TreeSearchLine` /
  `TreeHelpLine`, `ResourceConfigScreen` render, `Retry` / `Compaction` /
  `BranchSummary` indicators: they return plain string lines, not child
  trees. Wrapping them in hiccup adds reconcile cost for zero benefit.
- **Intentional foreign splices** — `bash_execution` spinner (animation
  identity), `login_dialog` border (built once outside the body),
  `image_block` transient image-per-render, `assistant_message`
  transient markdown-per-reflow.

## 5. Tiers

- **Tier 1** — DONE: the four selectors (Phase 1 #1–#3) and the
  `tool_renderers` imperative legs (Phase 1 #4). Remaining: the optional
  `chat_history` plain-msg helpers. No behavior change; assert with
  `hiccup/render-lines` headless tests (no tty, `bb test` material,
  never `^:slow`); watch `hiccup/counters` (`bodies-run` climbing on
  idle frames = inline-callback trap).
- **Tier 2 (one commit per file + interaction test)** — stateful leaves
  to tags: **DONE in full**. `dialogs` (`:select-list`, `:input`),
  `settings_selector` (`:settings-list`), `login_dialog` (borders +
  prompt field) and `session_selector` (search/rename fields) are all
  tag-owned; the two former input KEEPs (Phase 2 #3/#4) converted once
  `[:input]` gained the `:focused?` prop, which is what makes a rebuilt
  element come back whole. The conversions needed one component API
  addition — that prop — plus `hiccup/materialize-ref!` for the
  "key before the first paint" window; both are documented in tui.md
  §2.4. Where a tag owns the leaf, verify typing/selection/focus survive
  unrelated prop passes (`:apply` semantics, tui.md §2.3).
- **Tier 3 (optional, only with a rewrite)** — full-screen roots:
  `resource_config` → `hiccup/root`. Never a drive-by.

## 6. Plan

Ordered phases; each step is its own commit and ships alone. Validation
per step is the changed-file loop (`bb test-changed`, `bb lint-changed`,
`bb format-check-changed`); conversion steps assert the two-render
property — first render settles, an idle second render runs zero bodies
(`:bodies-skipped` 1) — and a state change runs exactly one body run,
reuses unchanged rows and disposes dropped ones, with a steady
`macros/watch-registry` count across navigation.

### Phase 0 — reconciler + tag ergonomics (independent; land any time) — DONE

1. **DONE — `split-bucket` O(1)** (`kmet.tui.hiccup`): a per-bucket index
   cursor instead of `(vec (rest bucket))` per pop. Removes the
   superlinear walk for long unkeyed lists (forced re-derive with no
   content change, measured n=4000: 295 → 80 ms/pass unkeyed; n=1000:
   32 → 19 ms/pass).
   Keys stay the rule for reuse under reordering; after this they are no
   longer about matching cost.
2. **DONE — ref-free inputs**: `:input` gained `:on-change` (parity with
   `:editor`; component + tag + `:apply` patch; fires on value-changing
   edits, silent for cursor moves and programmatic `input-set-value!`)
   and `:cursor` (state-carrying like `:value`, nil = unmanaged) for
   dialog prefills. The ref path (§3.2) stays the general mechanism.
   Interaction tests: typing fires the callback; an unrelated prop pass
   neither clobbers typed text nor moves a live cursor.

### Phase 1 — Tier 1 (legacy rows and renderer legs)

One commit per file, simplest first:

1. **DONE — `thinking_selector`**: rows + `thinking-refresh!` → a root
   body (`r/tracked-deref` on the state atom; a stable per-instance
   border `:color-fn`); `rows-container` and the `container`/`text`
   requires are gone; the refresh call sites (up/down, ctrl+c, filter)
   are plain state swaps; `dispose` unwinds the root reaction and the
   foreign input, wired through `show-thinking-selector`'s close.
   Tests render (`rows` slices the rendered lines) and pin the
   idle-frame memoization + rebuild counts.
2. **DONE — `model_selector`, `scoped_models_selector`**: root bodies
   with keyed `[:text]` rows; the live labels (`:scope-text`,
   `scope-hint-text`, `hint-text`, `:footer-text`) are elements now — all
   `text-set!` setters gone; `dispose` unwinds the root reaction and the
   foreign input through each show-*'s close. Tests render.
3. **DONE — `auth_selector`**: both selectors are root bodies
   (`[:truncated-text]` rows, `[:text]` method rows); a new render-driven
   `test_auth_selector.clj` added — it had none (rows, status indicators,
   clamped nav, search/empty states, idle-frame memoization, dispose
   unwinds); interactive mode's four auth mount sites now go through
   `mount-selector!` / `close-selector!` (dock done + dispose).
4. **DONE — `tool_renderers`**: the three remaining imperative legs
   (`render-edit-result`'s error branch, `render-bash-call`,
   `render-bash-result`) assemble `h/compile-tree` trees from
   `tool-text` leaves; the `container` / `text` / `spacer` requires are
   gone, the mangled token-per-line block is reflowed, and the collapsed
   output cap is hoisted next to the call-side one
   (`bash-result-preview-lines`). The ticker/state/`:last-component`
   contracts are untouched — the renderers still return a fresh
   component per pass (or nil), so the execution drops and disposes the
   previous one. Behavior pinned by a HEAD-vs-new render dump (244
   identical lines: collapsed/expanded windows, hints, truncation
   footers, timing) plus new render-driven tests for the two result
   legs (`test-bash-result`, `test-edit-result-error-leg`,
   `test-edit-result-preview-state`).
5. `chat_history` helpers (`make-plain-msg`, `make-plain-md-msg`,
   `StatusLine`) — optional, no behavior change, no measurable win.

Acceptance per file: interaction behavior identical (existing tests),
state changes re-derive only what changed, idle frames flat.

### Phase 2 — Tier 2 via refs

One commit per file, each with its interaction test (§3.2). Note: the
`dialogs` leg also surfaced a real bug — `frame` destructured its rest
arg as a map while both call sites passed `:hint-keys [...]` keywords, so
the hint line was always empty; fixed first as its own commit
(`fix(ui): dialog key hints were never rendered`) so the conversion
commit stays behavior-neutral.

1. **DONE — `dialogs`**: `frame` takes the content *element* instead of a
   pre-built foreign record — the tree constructs `[:select-list {...}]`
   / `[:input {...}]` under a `hiccup/ref`, and the dialog reads the
   instance back right after `compile-tree` to keep its `:select-list` /
   `:input-comp` field (so `handle-input`, `IFocusable` forwarding and
   `dispose` are unchanged). The `select-list`/`input` requires are gone;
   the input's prefill is the tag's construction inputs `:value` + `:cursor
   (count prefill)` (pi: LabelInput). Pinned by a commit-A-vs-new dump
   (130 identical lines: renders at 3 widths, nav/select/cancel, focus
   forwarding, typing/submit/trim/escape, prefill cursor) and the new
   render-driven tests (`test-input-dialog-prefill`, hint tests).
2. **DONE — `settings_selector`**: one flat binding — `[:settings-list]`
   under a ref (items construction-time; the panel is re-opened, not
   re-rendered live), the big `:on-change` case hoisted to a named local
   next to a named `on-escape` (which the tag applies via
   `settings-list-set-on-escape!`), and one `dispose-tree!` unwinds the
   chrome *and* the list (both DSL-owned now — the explicit
   `protocols/dispose sl` is gone). The `settings-list` require drops.
   Pinned by a pre-conversion-vs-new dump (103 identical lines: the frame
   at 100/60/30 cols, two-downs selection, a search filter, cleared
   search, escape restoring the dock) and a new `show-settings`
   interaction test (dock + focus target, filter/clear, escape unwinds).
3. **DONE — `session_selector` search/rename inputs** (the revisit
   condition this item names — "only if focus/`IFocusable` becomes a tag
   prop" — landed with `[:input :focused?]`). The two mutually exclusive
   branches now declare `[:input {:ref ... :value ... :cursor ...
   :focused? ...}]`: the panel mirrors text and caret into state as it
   forwards (`:query`/`:query-caret`, `:rename-value`/`:rename-caret`),
   the rename prefill is state set by `enter-rename-mode!`, and
   `IFocusable` shrank to the panel's own flag (the tree derives each
   input's emphasis). The `:search-input`/`:rename-input` record fields,
   their explicit disposes and the imperative `input-set-value!` prefill
   are gone. The instance churn the old note worried about is real — one
   construct + one dispose per mode switch — and accepted: the props bring
   text, caret and emphasis back, and the switch is user-initiated. Keys
   that arrive before a host render materialize the tree on demand
   (`panel-input`), so a mode switch the host has not painted yet cannot
   drop a key. Pinned by a pre-conversion-vs-new dump (**212 identical
   lines**: 15 states across 3 widths, focused/unfocused, caret moves,
   insert-at-caret, rename prefill/edit, list⇄rename returns, delete
   confirm/cancel) plus `mode-switch-preserves-text-and-caret` and
   `focus-drives-the-input-emphasis`.
4. **DONE — `login_dialog`** (chrome first, then the field). The border
   splices are two `[:dynamic-border {:color-fn accent-fn}]` elements
   sharing the one stable `accent-fn` the dialog already created — equal
   props keep reconcile on the same instances (the tag has no `:apply`, so
   a body-built closure would rebuild both) — pinned by a counters test: 3
   constructs on the first render, zero disposals across a rows change.
   The prompt field is now a `[:input]` too: the `{:row :input}` marker
   carries its text and caret (`:value`/`:caret` — the panel mirrors the
   live instance into the descriptor as it forwards, and
   `show-input-prompt!`'s clear is the fresh row's empty `:value`, a prop
   change the moved instance writes through), `:focused?` is the dialog's
   focus flag, and `IFocusable` shrank to that atom. `handle-input` still
   forwards through the ref (`materialize-ref!`, which compiles the tree on
   demand so a key before the first paint is not dropped) and answers the
   cancel key itself in the URL/device-code states, where pi mounts no
   input at all and escape is the only key that matters. The
   `:input-comp` field, its callback teardown and its explicit dispose are
   gone; `login_dialog`'s own dump is **273 identical lines** across all
   seven show-* states, both prompts (typing, caret moves, submit), the
   manual-input cancel and escape-in-the-auth-state, plus the new
   `test-prompt-field-emphasis-is-data` and
   `test-escape-cancels-without-a-mounted-field`.

### Phase 3 — only with a rewrite

- `resource_config` → full-screen `hiccup/root` (session_selector
  pattern) if the screen is being rewritten anyway.
- Transcript container → DSL root: explicitly **not planned** — measured
  perf-neutral, costs persistence/lifecycle clarity (§4).

### Guardrails

- No new `container-add-child` / `container-replace-children!` in
  `kmet.app.ui`; existing sites shrink file by file as the phases land.
- Converted roots read state through `r/tracked-deref` (§3.3), never a
  bare `@`.
- Keep `tui.md` §2.3/§2.4 in lockstep: this plan leans on the `:apply`
  semantics and the ref lifecycle; a behavior change to either updates
  both docs in the same commit.
- A converted root that forwards keys to a tag-owned input reaches it
  through a ref + `hiccup/materialize-ref!` (the element exists only
  after a render pass — a key arriving first must not be dropped) and
  declares its text/caret/emphasis as props (`:value`, `:cursor`,
  `:focused?`), mirroring the live instance into state as it forwards.
  Never hold the instance's text in a closure: a branch switch retires
  the element, and only the props bring it back.
- Focus is guarded, not remembered: a removal path that can drop a focus
  holder calls `tui-release-focus!` (the TUI's own container ops do), and
  a surface owner guards the atom that holds its panel (the dock's
  `::focus-guard` watch). Explicit `tui-set-focus` stays for deliberate
  moves only — tui.md §7.
- Root conversions dispose on their **close callbacks** *and* the dock
  disposes on **displacement**: `dock/mount!`/`dock/clear!` unwind the
  panel they lift out (pi: `disposeActiveSelector` at the top of
  `showSelector`; the same call in `setCustomEditorComponent`, the
  extension mounts and `stop`), so a selector that never runs its own done
  — an editor swap, a session reset, another panel taking the dock — still
  releases its root reaction and foreign children. Panels whose owner keeps
  custody pass `:borrowed? true` (the auth dialog a prompt selector
  temporarily replaces, an extension dialog the registry closes) and are
  left to that owner.

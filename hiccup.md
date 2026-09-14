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
touched (Tier 1 first).

Reference patterns: `session_selector.clj` (`hiccup/root` body re-derives
rows as `[:text ...]` data from the state atom, search/rename `Input`
spliced foreign, focus/dispose imperative), `login_dialog.clj`
(`hiccup/root` + `r/tracked-deref` on a row-descriptor atom + static
chrome built once outside the body), `fork_selector.clj` (`compile-tree`
frame + `track!` list returning strings), `bash_execution.clj`
(`hiccup/root` + long-lived `Spinner` spliced foreign), `dock.clj` /
`status_indicator.clj:make-status-area` (fn components).

## 1. The three shapes in the tree today

1. **DONE (hiccup)** — frame/root is a tree; only intentional foreign
   children remain (focused inputs, long-lived spinners, static chrome
   built once outside the body for identity stability).
   `session_selector`, `login_dialog`, `fork_selector`, `bash_execution`,
   `tree_selector` panel, `dock`, `status-area`.
2. **HYBRID** — `compile-tree` chrome + imperative content spliced
   foreign: a `Container` of `make-text` rows rebuilt on every filter
   pass, plus `make-input` search fields, plus `make-select-list` /
   `make-settings-list` where a tag would do.
   `auth_selector`, `model_selector`, `scoped_models_selector`,
   `thinking_selector`, `dialogs`, `settings_selector`,
   `tool_renderers` (partial).
3. **KEEP (imperative by design)** — transcript records and string-direct
   `track!` leaves. Not migration targets (§4).

## 2. Inventory (2026-09-14)

| file | shape | remaining `make-*` | plan |
|---|---|---|---|
| `assistant_message.clj` | KEEP | transient `md/make-markdown` per reflow (`render-text-to-width`, `render-thinking-to-width`) | none — render-to-width helper, not a tree node |
| `auth_selector.clj` | HYBRID | rows `container` + `truncated-text` (137,150,155,160); `input` + `list-container` (179–180); frame `compile-tree` (187). Method selector: rows `container` + `text` (294,299), `list-container` (313), frame (317) | Tier 1: rows → `[:text]` seqs; Tier 2: `[:input]` |
| `model_selector.clj` | HYBRID | rows `container` + `text` + `spacer` (219–245); `input` + `list-container` + `scope-text` (267–272); frame (279) | Tier 1 |
| `scoped_models_selector.clj` | HYBRID | rows `container` + `text` + `spacer` (303–336); `input` + `rows-container` + `footer-text` (358–360); frame (364) | Tier 1 |
| `thinking_selector.clj` | HYBRID | rows `container` + `text` (185–194); `input` + `rows-container` (222–223); frame (228) | Tier 1 |
| `dialogs.clj` | HYBRID | frame `compile-tree` (49); `select-list/make-select-list` (82); `input/make-input` (117) | Tier 2: `[:select-list]` / `[:input]` — `:apply` covers all props used; keep `defcomponent` shell for `IFocusable` + `handle-input` forwarding |
| `settings_selector.clj` | HYBRID | `settings-list/make-settings-list` (220); frame `compile-tree` (332) | Tier 2: `[:settings-list]` |
| `tool_renderers.clj` | PARTIAL | trees already (444,453,526,570,575,946,970); imperative leftovers: `render-edit-result` (711), `render-bash-call` (766), `render-bash-result` (791,838,848,869), default/warning legs (907,920) — all `container` + `spacer` + `text` | Tier 1: `[:container {} [:spacer] [:text ...]]` / `[:box ...]` |
| `chat_history.clj` | KEEP + Tier 1 helpers | `make-plain-msg` (145–147), `make-plain-md-msg` (154–157), `StatusLine` (191–192) | Tier 1 optional: helpers → `[:container {} [:spacer] [:text/:markdown/:truncated-text]]`; `ChatHistoryComponent` itself stays a record |
| `session_selector.clj` | DONE (pattern) | 2× `input/make-input` (859–860); `hiccup/root` (899) | Tier 2 optional: `[:input {:ref ...}]`; low priority, works as-is |
| `login_dialog.clj` | DONE | `input/make-input` (260); `db/make-dynamic-border` built once outside body (275); `hiccup/root` (279) | none — border-once-outside is the documented identity pattern; input could go `[:input]` (Tier 2, optional) |
| `bash_execution.clj` | DONE | `spinner/make-spinner` (248) spliced into `hiccup/root` (256) | none — long-lived spinner identity intentional |
| `tree_selector.clj` | DONE | `make-tree-list` ctor (937); `dialogs/make-input-dialog` (1082); panel `compile-tree` (1099) | none — `TreeList` is a string-direct `track!` leaf by design |
| `user_message.clj` | KEEP | `container`/`box`/`md`/`spacer`/`image-block` (89–114) | none (§4) |
| `custom_message.clj` | KEEP | `container`/`spacer`/`box`/`text`/`md`/`image-block` (81–93,153–155) | none (§4) |
| `skill_message.clj` | KEEP | `text`/`md`/`container`/`box`/`spacer` (132–137,175–186) | none (§4) |
| `tool_execution.clj` | KEEP | `container`/`box`/`spacer`/`image-block` (221–222,277–279) | none (§4) |
| `image_block.clj` | KEEP | transient `ic/make-image` per render (54) | none — render-time branch, not a stored tree |
| `status_indicator.clj` | KEEP | `spinner/make-spinner` host-owned (79) | none |
| `resource_config.clj` | KEEP / Tier 3 | `input/make-input` (557); render returns string lines directly | full-screen `hiccup/root` (session_selector pattern) only if rewriting the screen anyway |
| `footer.clj`, `pending_messages.clj`, `loaded_resources.clj` | KEEP | none | string-direct `track!` renders — no tree to migrate |
| `dock.clj`, `subs.clj`, `model_catalog.clj`, `external_editor.clj`, `custom_dialog_adapter.clj`, `footer_data_provider.clj` | N/A | none | fn component / data / adapter — nothing to migrate |

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

After (the `session_selector` pattern — rows re-derive from the state
atom inside the tree body, keyed, truncated at `hiccup/*width*`):

```clojure
;; compile-tree frame (current hybrid step): keep the defcomponent +
;; handle-input + dock mount, drop the refresh fn + list-container.
(defn- row-elements
  "Row data as hiccup elements (replaces *-refresh!)."
  [th filtered selected start-idx end-idx]
  (into (mapv (fn [i]
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

c (h/compile-tree
   [:container {}
    [:dynamic-border {:color-fn #(theme/fg th :accent %)}]
    [:spacer {:lines 1}]
    search-input ;; stays foreign: focus target, updated via input-set-value!
    [:spacer {:lines 1}]
    (row-elements th filtered selected start-idx end-idx)
    [:spacer {:lines 1}]
    [:dynamic-border {:color-fn #(theme/fg th :accent %)}]])
```

Rules: a body-built seq splices (§2.1) — always `:key` spliced rows or
prepending rebuilds every unkeyed sibling; `:text` takes the
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
[:input {:value search :on-submit submit! :on-escape cancel!}]
;; focus via the component contract, not a ref: dock/mount! takes the
;; focus target (mount! cs frame sl), and IFocusable forwards to it —
;; no hiccup/ref is used anywhere in app/ui today, do not add the first.
```

Preconditions before converting a leaf: the tag's `:apply` must express
every prop you change per pass (tui.md §2.3 — structural props that
decline the patch rebuild and lose state: spinner `:frames` /
`:interval-ms`, settings-list `:enable-search`, editor `:border` /
`:keybindings`, cancellable-loader `:spinner`). Focus stays imperative:
`mount` the tree, then `tui-set-focus` on the ref'd instance
(`session_selector` forwards `IFocusable` to its inputs the same way).
Callbacks as fresh fn literals per pass defeat prop-equality memoization
(§2.5 footgun) — hoist to named fns or stable values. Three further
blockers to check per file before converting the search input:

1. **No `:on-change` on `:input`.** The tag only carries `:value` /
   `:on-submit` / `:on-escape`; every hybrid selector syncs keystroke →
   filter in `handle-input` via `(input/input-get-value search-input)`
   after forwarding (`thinking_selector:151`, `model_selector:150`,
   `auth_selector:110`, `session_selector:forward-to-search!`). A tag
   keeps that shape only if `handle-input` can still reach the live
   instance (via `:ref` deref) or the filter moves into `:on-submit`.
   Until one of those lands, the search input stays foreign.
2. **`dialogs` prefill pokes the cursor atom** (`(reset!
   (:cursor-atom inp) (count prefill))`) — needs an `:initial-cursor`
   affordance or the record stays foreign.
3. **`session_selector` rename submit is wired post-construct**
   (`input-set-on-submit!` after `hiccup/root`) — hoist into the tag
   props or keep foreign.

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
retire+reconstruct; `hiccup/*width*` is the truncation width inside the
body (`session_selector` reads `w` once per pass and threads it through
`header-line` / `hint-lines` / `content-lines`); `compile-tree`-
outside-a-mount trees are holder-disposed (`dispose-tree!`), `root`s via
`dispose` (which must unwind the root — `session_selector`,
`login_dialog` both do). `scope-text` / `footer-text` / `hint-text`
conditionals (`model_selector:269-274`) become `when` elements inline —
nil splices free (§2.1) — instead of nil-or-record fields.

## 4. Non-goals (stays imperative)

- **Transcript hot path** — `ChatHistoryComponent`, `UserMessage`,
  `AssistantMessage`, `CustomMessage`, `SkillInvocationMessage`,
  `ToolExecutionComponent`: records with instance storage, `track!`
  caches, theme apply-once, renderer-state / last-component dedup, image
  children lifecycle, streaming reflow. A fn-component transcript would
  re-derive O(transcript) per token and break persistence (which reads
  the message maps directly). Helpers under `chat_history` (§2, Tier 1
  optional) are the only transcript-adjacent exception.
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

- **Tier 1 (do on touch)** — data-only rows and leftover renderer
  branches: `auth_selector` rows, `model_selector` rows,
  `scoped_models_selector` rows, `thinking_selector` rows (+
  `AuthMethodSelector` rows), `tool_renderers` imperative legs,
  `chat_history` plain-msg helpers. No behavior change; assert with
  `hiccup/render-lines` headless tests (no tty, `bb test` material,
  never `^:slow`); watch `hiccup/counters` (`bodies-run` climbing on
  idle frames = inline-callback trap).
- **Tier 2 (one commit per file + interaction test)** — stateful leaves
  to tags: `dialogs` (`:select-list`, `:input`), `settings_selector`
  (`:settings-list`), selector search inputs (`:input` + `:ref`).
  Verify typing/selection/focus survive unrelated prop passes
  (`:apply` semantics, tui.md §2.3).
- **Tier 3 (optional, only with a rewrite)** — full-screen roots:
  `resource_config` → `hiccup/root`. Never a drive-by.

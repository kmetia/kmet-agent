# perf — runtime CPU profiles (babashka vs jolt)

Why `jolt run` burns more CPU than `bb run` on the same tree, what was measured,
and what to do about it. Numbers from a Termux/aarch64 phone (100x30 tmux pty,
`jolt v0.8.6-83-g3de3e02b`, bb 1.12.x), 2026-09-11.

**Re-verified on `jolt v0.8.10-10-g9c439021` (Termux/aarch64 phone, bb
1.13.222) — §11 is the phone baseline; §12 re-runs the same harnesses on
x86_64 WSL2 at `jolt v0.8.10-32-g9786b7fa` (typing parity, ~1.8x cold
render).** §9 (v0.8.6-86, x86_64 WSL2) and §10
(the first phone pass at v0.8.6-83) are kept as history. The v0.8.10 upgrade
inverted the host comparison: jolt's primitives are no longer 2–5x slower on
the frame path (§11.1).

**§13 re-checks the live frame path on the phone at `jolt v0.8.20`, `bb`
1.13.225 (2026-10-09): steady frames ~14 ms bb / ~12.5 ms jolt on a 2.6 MB
transcript after the §13.3 cursor-marker memo (20.3 ms before it), redraw
~3.2 s / ~5.0 s, idle 4.2 % / 2.6 % of a core; the `bb run` recipe is fixed
to `bb start`.**

**§14 profiles the reakt layer (2026-10-10): per-op costs of the reactive
engine and the algorithmic candidates it points at — the watch-set diff is
O(deps²) per reaction run, the enqueue dedup is O(queue) (O(N²) across a
storm), and jolt's `instance?` against the modeled class hierarchy costs
1–1.8 µs per call (a perf finding tracked in [`jolt-bugs.md`](jolt-bugs.md),
fixed in `v0.8.21`).
The frame bench cannot see any of it: its harness mounts no fn components
(a call-counted probe reports zero `ComponentFn` renders on a replayed
session, §14.1).**

**§23 is the message-cache walk (`:parts`) in 2026-11: the per-ref
re-verification on every `track!` hit that made compose O(refs × messages),
the two changes that removed it (watch at the read, then trust the
invalidation edges), the guarantee change that came with it, the measured
before/after, and why the parent probe was declined. §23.1 holds the earlier
render-path structure findings.**

**§16 re-checks the paths those two fixes touched, on the installed
`jolt v0.8.21` / `bb` 1.13.225 (2026-10-11): the reakt layer's jolt
primitive deficit is gone — `tracked-deref` in a scope is now 1.9x faster
than bb's, an invalidation storm 2.8x cheaper — the timed `poll` costs the
untimed one, and §15's SSE table holds (reader accommodation removal
cost-neutral). The one surviving reakt deficit is a many-dep `force-run!`
(5.5x at 50 deps), traced to Jolt's constant `hash` for objects (§16.2,
filed as [jolt#1313](https://github.com/jolt-lang/jolt/issues/1313)).**

**§17 re-checks the filed tickets on the installed `v0.8.21-61-g4d665200`
dev build (`bb` 1.13.226, x86_64, 2026-10-12): #1313's identity hash is in
and the surviving deficit is closed — the many-dep `force-run!` is linear
again (1.66x bb at 50 deps, was 5.5x and superlinear on the phone; 1.2–1.7x
out to 200 deps); #1316's io opts work, so the `spit` workaround can go at the
next floor bump. Typing CPU, frame phases, queue drains and the parked handoff
are unchanged or better (§17).**

**§18 chases the two remaining jolt outliers. The `str/replace
ANSI-CODE-RE` loss is a jolt/irregex DFA defect — its leftmost search re-runs
the anchored match at every start position, so a match at position k costs
~60 ns×k (24 µs at k=400, 14x the same pattern on the backtracker); filed as
[jolt#1327](https://github.com/jolt-lang/jolt/issues/1327). The
`normalize-terminal-output` guard is a wash, not a bug (guarded 0.50 vs
0.51 µs full unguarded on jolt). Two kmet call sites that still fed the regex
to jolt are fixed: `truncate-to-width` **1630 → 93 µs** on a styled line
(17.5x; 700 → 272 on bb) and `wrap-single-line`'s strip 16.9 → 1.3 µs (§18).**

**§19 sweeps the rest of the regex-on-hot-path surface. The bash/tool output
sanitizers now use the shared scanner and skip the CR passes when a chunk has
no CR: on a 1.8 KB chunk jolt 75.8 → 39.6 µs plain and 164.3 → 46.4 styled,
bb 86.7 → 56.9 plain. The markdown table lookbehind split, regex line
splitting, and the diff-line parse measured fine (or would regress bb) and
are left alone (§19).**

**§20 moves the wrap tracker's ANSI scan onto the scanners on both hosts.
`ansi-process-text!` / `ansi-process-range!` and `split-long-word`'s walker
no longer touch the regex engine, and `ansi-sequence-end`'s OSC branch swaps
its per-character loop (slower than the regex itself on a long OSC) for
`str/index-of`. Styled-line tracker: jolt 40.5 → 4.2 µs, bb 15.8 → 6.8; a
12k-string exhaustive corpus now pins every scanner against the regex (§20).**

**§21 profiles the markdown tokenizer (`kmet.libs.markdown/parse`) and removes
four costs from the whole-message re-parse: the per-character inline loop now
chunks plain runs between a stateful matcher's specials, the URL/email gates
check real candidates instead of first characters, code blocks accumulate
lines and join once (they were O(n²)), and the block dispatch gates its six
per-line regexes behind first-char checks. Output-identical (780k/220k-string
differential fuzz across seeds, §21.6): `md/parse` 2 KB 5.1x bb / 2.9x jolt, a
110 KB fenced block 30x/18.5x, `perf.md` itself 2.5x/1.56x (§21).**

**TL;DR** — it is not a busy loop and not the terminal backend. Idle CPU is
*lower* on jolt than on bb. The frame gap was (a) every keystroke/frame
re-renders the whole component tree + re-normalizes every line + diffs/emits,
and (b) jolt's *runtime primitives* being 2–5x slower on exactly the operations
that path is made of (`java.util.regex` → irregex, per-call string helpers,
collection/seq allocation). **At v0.8.10, (b) no longer holds** (§11.1): jolt
now wins the width/string/regex/`md/parse` primitives; what still loses is
seq/allocation-heavy work (`mapv` 2.7x, the editor's 20-line render 2.7x, the
tool renderers 1.8–3x — §11.2) and the per-line `normalize-terminal-output`
early-out (§11.3). On a 3.7k-line live transcript a frame is ~20 ms on bb /
~25 ms on jolt, split differently per host (bb: render-stack; jolt: normalize)
— and until cb763f6 the whole-transcript kitty-image walk was 6–7 ms of it on
both (§11.3). `jolt build` is still *not* a lever (§6.2).

The §5 wins are applied; §5.4 re-verifies cleanly at v0.8.10 (jolt regex
17.05 µs vs scanner 1.79 — §11.1) and typing CPU is back at parity on the
phone (§11.4). The next levers are §6.3 (incremental markdown), the jolt
normalize guard (revisit — §11.1/§11.3) and §6.6 (scanner for `ansi-code-at`);
§6.1's line-work items are applied and the SEGMENT-RESET concat it worried
about is gone (§11.5).

---

## 1. Method

Reproduce with:

```sh
# app-level: same tree, same config, tmux pty 100x30
# (`bb run` was the task name until 00b3e373 renamed it to `start`; `bb run`
# now opens a babashka REPL — see §13.2)
tmux new-session -d -s pj -x 100 -y 30 'jolt start'
tmux new-session -d -s pb -x 100 -y 30 'bb start'
# per-thread CPU from /proc/<pid>/stat fields 14+15 (user+sys ticks, 100 Hz)
# per-key CPU: u0=$(awk '{print $14}' /proc/PID/stat); <send N keys>; u1=...
```

Numbers below are `user` ticks unless stated. Every microbench was run with
warmup (2–3 untimed passes) — one-pass timings are dominated by JIT/irregex
warmup and are misleading on both hosts (a 1-pass bench reported bb as 8x
slower than jolt on code that is actually 2x slower in steady state).
`bb` is a GraalVM native image; `jolt run` compiles each top-level form to
native Scheme as it loads it (§6.2). Both run the same source.

Frame attribution was measured by temporarily instrumenting
`kmet.tui.core/run-render-loop!` with per-phase counters (render-stack,
normalize, applyLineResets, emit) and reverting the patch — see §4.

---

## 2. What is not the cause

| hypothesis | measurement | verdict |
|---|---|---|
| busy loop / spinning frame loop | idle `jolt run` = 47–56 ticks/10 s (~5 % of a core); `strace -c` shows only 2 `ioctl(TIOCGWINSZ)`/frame and **zero** writes | no |
| native terminal backend (poll/read FFI) | idle CPU is *lower* than bb's JLine loop (~7–8 %); `!seq 1 3000` costs jolt 1.30 s vs bb 1.07 s (1.2x) | no |
| startup | CPU to first idle frame: jolt ~2.8 s, bb ~3.6 s | jolt cheaper |
| forced full redraws | 3 resizes: jolt 30 ticks, bb 31 ticks | equal |
| transcript size | typing cost per key is flat from a fresh session to an 800-line transcript (`!seq 1 800`) | frame cost is fixed overhead, not transcript length |
| `Thread/sleep` / timer granularity | 16 ms loop; per-frame render dominates (below) | no |

So the CPU goes where the app does work — and the app does a *lot* of work per
frame by design (full-tree render + line normalization + diff).

---

## 3. Where the CPU goes

### 3.1 Per frame (instrumented render loop, 200 frames incl. typing)

| phase | bb | jolt | jolt/bb |
|---|---|---|---|
| `stack/render-stack` (whole-tree walk + component renders) | 590 ms (73 %) | 1007 ms (67 %) | 1.7x |
| line normalization (`normalize-terminal-output`, cursor extraction) | 31 ms (4 %) | 122 ms (8 %) | 3.9x |
| `applyLineResets` (`(str line SEGMENT-RESET)` per line) | 31 ms (4 %) | 80 ms (5 %) | 2.6x |
| diff + emit (`main-diff`, write) | 158 ms (20 %) | 294 ms (20 %) | 1.9x |
| **total** | **807 ms** | **1499 ms** | **1.9x** |

`stack/render-stack` is the single biggest phase on *both* hosts: the per-frame
tree walk (hiccup template run + reconcile + per-component render) dominates, and
jolt's interpreter pays ~1.7x for it. Since `track!` caches leaf renders, the
remaining cost is the walk itself, not the leaf rendering.

### 3.2 End-to-end scenarios

| scenario | bb | jolt | ratio |
|---|---|---|---|
| typing 100 keys (empty editor, ~20 ms apart) | ~0.7 s | ~1.6–1.9 s | 2.5x |
| typing 50 keys with an 800-line transcript | 0.30 s | 0.60 s | 2.0x |
| paste 5000 chars | 2.6 s | 5.9 s | 2.3x |
| `!seq 1 3000` (bash tool output) | 1.07 s | 1.30 s | 1.2x |
| idle (60 s) | ~7–8 % core | ~5 % core | 0.7x |

Per keystroke: ~12 ms CPU on jolt, ~6–7 ms on bb; ~60 % of it is the frame body
above, the rest is input processing and keybinding dispatch (§3.4).

### 3.3 Hot operations (steady-state microbenchmarks, pre-§5)

Per-op, same code both hosts (the §5-optimized numbers are in §5):

| operation | bb | jolt | jolt/bb |
|---|---|---|---|
| `visible-width`, plain 100-char line | 7.2 µs | 11.4 µs | 1.6x |
| `visible-width`, 1-char string (per-grapheme calls!) | 0.5 µs | 3.0 µs | **6.0x** |
| `visible-width`, 100-char ANSI-styled line | 7.7 µs | 23.9 µs | 3.1x |
| `str/replace ANSI-CODE-RE` (styled line) | 5.2 µs | 19.6 µs | 3.8x |
| `re-find #"[^\u0020-\u007e]"` (ASCII test) | 0.27 ms/2k | 0.43 ms/2k | 1.6x |
| `re-pattern` + `re-find` vs precompiled | 1.6 µs vs 0.6 µs | 1.7 µs vs 1.1 µs | — |
| `matches-key?` (parse + normalize per call) | 6.1 µs | 18.4 µs | **3.0x** |
| `parse-key` (single char / arrow) | 3.3 / 1.5 µs | 5.1 / 4.7 µs | 1.5x / 3.1x |
| editor `render`, 500-char line | 1.9 ms | 3.6 ms | 1.9x |
| editor `render`, 20 lines | 0.38 ms | 1.9 ms | **5.0x** |
| markdown `render`, 2 KB message | 11.1 ms | 29.7 ms | 2.7x |
| `md/parse`, 21.6 KB | 44 ms | 84 ms | 1.9x |
| **pure compute** `fib 27` | 121 ms | 5.5 ms | **0.05x** |
| keyword lookup ×1e6 | 188 ms | 39 ms | 0.2x |
| `subs` ×1e5 | 20 ms | 7 ms | 0.4x |
| `assoc-in` ×1e5 | 154 ms | 224 ms | 1.45x |
| `swap!` ×1e5 | 19 ms | 27 ms | 1.4x |
| `mapv inc` ×1e5 | 62 ms | 284 ms | **4.6x** |

Reading: jolt's *native* primitives win big (numbers, `subs`, keyword lookup),
but every `java.util.regex` call (→ irregex), every Clojure-callable string
helper (`str/replace`, and small-string overhead everywhere), and
collection/seq allocation loses 1.4–6x. The TUI frame path is made almost
entirely of the latter, so the frame is ~2x slower on jolt while idle is
cheaper. (Jolt compiles user code natively — see §6.2 — so this is the
*primitive* layer, not interpretation.)

**Superseded at v0.8.10 — see §11.1**: jolt now wins most of these primitives;
the remaining losses are seq/allocation-heavy work (and the tool renderers,
§11.2).

Two Jolt quirks behind the numbers:

- **Small strings are the worst case.** `visible-width` on a 1-char string is
  **6x** slower on jolt — per-grapheme width calls (editor wrap, markdown
  inline styling) call it once per character. On bb a regex call has a low
  fixed cost; on jolt `re-matcher`/irregex setup dominates short inputs.
- **`mapv`/seq allocation** (4.6x) shows up in every per-line `mapv` chain in
  the frame body (`normalize-terminal-output`, `applyLineResets`,
  `composite-flashes`, the diff's per-line loops).

### 3.4 Keybinding dispatch

`kmet.tui.keys/matches-key?` parses the raw input string (`parse-key`:
3+ `re-matches` per call) and normalizes *both* sides (`str/split` on `"+"`,
two sets) **per chord per binding checked**. The editor's `handle-input` checks
~20 builtin ids plus `dispatch-app-action!`'s ~12 registered app actions, each
with 1–3 chords — so ~30–60 `parse-key` + normalize rounds per keystroke.
That is ~0.6–1.1 ms/keystroke on jolt (3x bb's per-call cost). It is a real
share of the 12 ms/keystroke, and the fix is a cache (§5.2).

### 3.5 Markdown / streaming

`Markdown` re-parses the *entire* message text on every render
(`components/markdown.clj`, `md/parse` at the top of `render`), and the
assistant message path builds a fresh `make-markdown` component per render.
While a response streams, every frame re-parses and re-styles the whole message:
2 KB costs ~11 ms bb / ~30 ms jolt **per frame**, 20 KB ~205 ms / ~570 ms.
This is the most expensive path during streaming on both hosts, and the one
where jolt's regex/markup-heavy render is doubly penalized (2.7x). Block-level
incremental parsing is the fix (§6.3) — it helps bb too.

---

## 4. How the frame instrumentation was done (repro)

`run-render-loop!` was temporarily patched with `defonce` atom counters
(`__perf-*`): a start timestamp at the top of the `(when @(:render-requested? …))`
block, side-effecting `let` bindings (`__pm1`/`__pm2`/`__pm3`) after
`stack/render-stack`, after the `normalize-terminal-output` mapv, and after the
`SEGMENT-RESET` mapv, plus an accumulator after the `when` that adds the
deltas and `spit`s a report every 200 frames. Revert after measuring; the
patch is ~30 lines and purely additive.

Note for future measurement: report `render_ns` must be divided by the frames in
the window, and idle frames (no render requested) must be excluded — the counters
above only run when a frame is rendered.

---

## 5. Applied quick wins (local, semantics-preserving)

All four are applied in this tree (the sections below started as proposals and
now record the measured result). Re-verify with the recipes in §1. The per-op
numbers below are the original phone/`-83` measurements; an A/B against the
pre-§5 *files* on the installed `-86` build confirms all four still win on jolt
(§9.2).

### 5.1 `visible-width`: printable-ASCII test first, strip only when needed

`utils/visible-width` unconditionally ran `(str/replace s ANSI-CODE-RE "")`
before measuring. The printable-ASCII test `#"[^\u0020-\u007e]"` *also matches
an ESC byte*, so it can run first: a pure-ASCII line answers `(count s)` with
neither a strip nor a grapheme walk; a styled or non-ASCII line strips (only
when an ESC is actually present) and falls through to the walker. The final
form (with §5.4's `strip-ansi`):

```clojure
(defn visible-width [s]
  (if (empty? s) 0
      (if (re-find #"[^\u0020-\u007e]" s)
        (visible-width-plain
         (if (str/includes? s "\u001b") (strip-ansi s) s))
        (count s))))
```

An earlier version checked `(str/includes? s "\u001b")` first; measuring both
showed the ASCII-test-first order is strictly better (one full scan instead of
two on plain lines, and one cheap immediate match on styled lines).

Measured (100 k iterations, 3 warmups, steady state):

| input | before | after |
|---|---|---|
| plain 100-char | bb 6.90 µs / jolt 10.90 µs | bb **3.24 µs** / jolt **5.48 µs** |
| 1-char string | bb 0.54 µs / jolt 3.0 µs | bb 0.36 µs / jolt **0.99 µs** |
| ANSI-styled 100-char | bb 8.17 / jolt 24.5 | bb 7.41 / jolt **9.75** (with §5.4) |
| mixed markdown line | bb 4.57 / jolt 16.5 | bb 4.81 / jolt **7.65** (with §5.4) |

Broad reach: `visible-width` is called per line in the frame diff, per grapheme
in editor wrapping and markdown inline styling, per item in every selector.

### 5.2 `keys/matches-key?`: parse once, normalize once

Within a single keystroke the same raw `data` string was parsed 30–60 times
(once per chord of every binding checked: the editor's builtin ids plus
`dispatch-app-action!`'s app handlers) and *both* sides re-normalized
(`str/split` + set) per call. Two caches, both invalidation-safe:

- raw `data` → parsed key id: a one-slot memo (all calls in a keystroke share
  the same string). `parse-key` is a pure function of (data, `kitty-active?`,
  `legacy-map`); the entry stores the kitty flag and self-invalidates when it
  flips, and `set-kitty-active!` also clears it eagerly;
- key-id string → normalized `{:key … :mods #{…}}`: a small global map (the id
  vocabulary is the keybinding tables, so it stays bounded).

Measured: `matches-key?` bb 6.1 µs → **~1.0 µs**, jolt 18.4 µs → **~1.0 µs**
(100-call rounds, both hosts). ~15–18x per call; with 30–60 calls per keystroke
that is ~0.5–1 ms/keystroke on jolt, ~0.2–0.3 ms on bb.

### 5.3 Editor: compile the autocomplete trigger spec once

`trigger-pattern` rebuilt `(re-pattern (str "(?:^|[\s])[" … "]…$"))` — with a
per-char escaping pass — on every keystroke, and
`maybe-trigger-autocomplete` built a `(set (provider-trigger-chars …))` per
keystroke. Both are now memoized per trigger-char set in one `trigger-spec`
(`{:pattern re :chars #{…}}`).

`re-pattern`+`re-find` vs a precompiled pattern measured 1.6 vs 0.6 µs (bb) and
1.7 vs 1.1 µs (jolt) per call; the win is modest but the calls are per-keystroke
and the rewrite is local.

**Deliberately not changed**: the remaining tiny per-keystroke `re-find`s
(`#"^\s"`, `#"[\s\t]"`, `#"[a-zA-Z0-9.\-_]"`). At ~1–2 µs each they are noise
next to the frame cost, and replacing them with hand-written char predicates
risks *widening/narrowing* the whitespace/word sets differently per host
(Java's `\s` vs irregex's) for no measurable gain.

### 5.4 ANSI strip: host-specific implementation (`.cljc` carve-out)

The one operation where jolt's regex engine hurts the most is the ANSI strip
itself: `java.util.regex` → irregex on jolt. A hand-rolled scanner seeded by
`str/index-of` (native on both hosts) is semantically identical (the test suite
pins both implementations against each other on a corpus, including the
sequences the regex does *not* match: private-parameter CSI, unterminated OSC,
a lone ESC) and much faster where it matters:

| strip a… | bb | jolt |
|---|---|---|
| styled 100-char line (2 escapes) | regex 4.6 µs / scanner 8.1 µs | regex 17.3 µs / **scanner 3.4 µs** |
| markdown-styled line | regex 2.8 µs / scanner 10.4 µs | regex 11.7 µs / **scanner 3.6 µs** |
| plain 100-char, no ESC | regex 3.8 / scanner 0.6 | regex 5.5 / scanner 1.2 |

So the two hosts want opposite implementations — exactly the case the reader
conditional convention exists for. `kmet.tui.utils` became **`utils.cljc`**
(the third host-branching file after `http.cljc` / `extensions.cljc` /
the terminal backend):

```clojure
(defn- strip-ansi [s]
  #?(:jolt (strip-ansi-native s)
     :default (str/replace s ANSI-CODE-RE "")))
```

`strip-ansi-native` is public (the bb lint view reads only the `:default`
branch and would flag a private one unused) and `strip-ansi-codes` — the
existing public API, used by the tool renderers — now delegates to it. Both
branches are exercised on both hosts by `test-strip-ansi-host-equivalence`.

Resulting `visible-width` costs (steady state, 100 k iterations):

| input | before | bb | jolt |
|---|---|---|---|
| plain 100-char | bb 6.90 / jolt 10.90 | 3.10 | 5.47 |
| ANSI-styled 100-char | bb 8.17 / jolt 24.54 | 7.71 | **9.75** |
| markdown-styled line | bb 4.57 / jolt 16.50 | 4.63 | **7.65** |
| 1-char string | bb 0.54 / jolt 3.0 | 0.37 | 1.01 |

The styled-line 3x gap is gone (jolt 1.26x bb on that input, was 3.0x).

---

## 6. Suggestions (not yet applied)

Ordered by expected effect on `jolt run` typing/streaming CPU.

### 6.1 Cut per-frame line work (the reset concat remains)

> **Status (2026-09-21, §11):** both items below are applied — the emit-time
> `SEGMENT-RESET` (4240e45) removed the per-line mapv this section proposed,
> and the normalize early-out (b9408c5) is in the tree; §11.1 re-measures the
> latter as a wash on bb and a ~0.3 µs/line loss on jolt.

Per frame every line is re-normalized and re-reset. Of the proposals below,
**one is dead and one stands** (§9.4):

- ~~`(mapv utils/normalize-terminal-output lines)` … guard the replaces behind
  `str/includes?`~~ — **DEAD, do not apply.** A/B'd with the §4 loop on `-86`:
  `normalize-terminal-output` costs **1.05 µs** on a plain 100-char line on
  jolt, and the guarded form (`3 × str/includes?` ≈ 0.35 µs each + pass-through)
  costs **1.18 µs** — the scans are not cheaper than the replaces they skip.
  The Thai/Lao replaces are near-free on non-matching input anyway (the
  decomposed case measures 0.41 µs), and the whole phase is 4-8 % of frame.
  (`#953` landed, so a `java.text.Normalizer` call is now *available* — it is
  not *faster* than the current replaces, so do not switch.)
- `(mapv (fn [line] (str line SEGMENT-RESET)) lines)` allocates a new string
  per line per frame. Cache the composed string on the component cache keyed by
  the raw line, or diff the *raw* lines and append `SEGMENT-RESET` only at
  emit time (the diff's `not=` comparison works on raw lines just as well).
- `composite-flashes` runs `visible-width` per flash line per frame — cheap
  after 5.1, fine.

These two phases were ~12 % of frame time on jolt (202 ms/200 frames, §3.1).
The `normalize` half is **not** recoverable (above), so the remaining item is
the `SEGMENT-RESET` concat alone — 2.9 µs/line on jolt vs 1.05 on bb, ~6 % of
frame — where the cost is the extra per-line `mapv` walk, not the 0.05 µs concat
itself.

### 6.2 `jolt build` is not the lever it looks like

`jolt build -m NS` AOT-compiles the entry closure (default release profile;
`--dev` is unoptimized; `--dynamic` keeps the crypto/ssl/z natives
runtime-loaded, which kmet needs — without it the build stops with the
native-library notice and exits 2). kmet builds and runs:

```sh
jolt build -m kmet.agent.core -o kmet-jolt --dynamic   # ~15 min on the phone, 52 MB
./kmet-jolt                                       # full TUI, works
```

**It does not meaningfully reduce CPU.** Measured on the same tree, same
scenarios:

- full-redraw resizes (20×): `jolt run` 127–134 ticks, compiled 122–130, bb 104–106;
- paste 5000 chars: `jolt run` 663/680 ticks, compiled 674/710 — *identical*;
- typing: within run-to-run noise.

That is explained by jolt's `run` path itself: jolt **already compiles every
top-level form to Scheme** (analyzer → IR → emitter → Chez `eval`, i.e. native
compilation per form) — see `host/chez/compile-eval.ss`. `jolt build` only adds
whole-program direct-linking/inlining. A minimal compiled project confirms the
ceiling: `visible-width` 5.17 → 5.05 µs, `matches-key?` 1.01 → 0.90 µs,
fib 27 4.6 → 3.7 ms (1.0–1.25x). So the runtime gap vs bb is **not
interpretation** — it is the *primitives*: irregex for `java.util.regex`, Chez
string/collection representation, and the JDK-shim dispatch layer. Optimize the
code (host carve-outs like §5.4), not the build mode.

Two build notes for anyone trying it: a minimal closure can die with jolt#944
(`unbound fn jolt.time.impl/register-type!`) — an early `(:require [jolt.time])`
in the entry namespace fixes it (kmet's own closure already loads it); and the
build needs ~15 min on a phone.

### 6.2b Measurement caveats (learned the hard way)

- **Typing benchmarks are noisy.** At a 15–20 ms key interval against a 16 ms
  frame loop, the number of frames per key varies run-to-run (coalescing) and
  swings the ticks 30–60 %. Use several rounds and report medians, or prefer the
  deterministic recipes: N full-redraw resizes, one large paste, one long
  bash-output command.
- **Never measure while a `jolt build` runs** — it pegs 55 % of the CPU and
  every comparison silently drifts.
- **Warm up microbenches 2–3 times**; a single timed pass measures JIT/irregex
  warmup and can invert the ranking (see the intro).
- `/proc/<pid>/stat` fields 14+15 are 100 Hz ticks on this device; `ps` %CPU is
  averaged over process lifetime and useless for short scenarios.

### 6.3 Markdown: incremental block parsing (§3.5)

`md/parse` on the whole text per render is the streaming bottleneck (2 KB ≈
30 ms/frame on jolt). Cache parsed blocks by (block text) and reparse only the
last (streaming) block — the tokenizer already produces a block vector, so a
memo keyed on the text up to the last incomplete block is straightforward.
This benefits bb equally and is the biggest *algorithmic* win available.

### 6.4 Reduce tree-walk cost

`stack/render-stack` is 67–73 % of frame time. Options, in increasing
invasiveness:

- Keep the root hiccup template's node set stable so reconciliation hits the
  reuse path (avoid rebuilding vectors per pass — the DSL already does this).
- Per-frame, skip `render` calls on components whose cache is warm and whose
  width did not change, by failing fast in the walker rather than inside the
  component (saves the interpreted call + cache-key construction per node).
- If jolt ever supports `jolt build` in the normal dev loop, prefer it here.

### 6.5 Keybinding dispatch: dispatch on the parsed key instead of looping

Even with the 5.2 cache, the loop is O(bindings) per keystroke. Reverse the
table when a manager is (re)built: parsed-key-id → [binding-id …], then a
keystroke is one hash lookup + chords compare. This is bb-relevant too.

### 6.6 Extend the scanner to the remaining ANSI helpers (jolt only)

§5.4 carved out the *strip*; the same trick applies to the other regex-per-
escape sites, all of which sit on jolt's slow path:

- `slice-with-width` / `truncate-to-width` / `sgr-state-at` / `composite-line`
  call `ansi-code-at` → `match-at` → `re-matcher` once per escape;
- `ansi-code-at` re-measured on `-86`: **jolt 1.36 µs vs scanner 0.26 µs**
  (5x, unchanged in shape); bb is the opposite way (regex 0.55 vs scanner 0.74),
  hence another `#?(:jolt … :default …)`: still worth doing (§9.4).

```clojure
(defn- ansi-code-at [s i]
  #?(:jolt (when-let [end (ansi-sequence-end s i)] [(subs s i end) (- end i)])
     :default …existing matcher…))
```

Same equivalence contract as §5.4 (the corpus test already pins the scanner;
extend it to `ansi-code-at`). Expected effect: scroll-view compositing and
line truncation on ANSI-dense lines (tool output, markdown) — smaller than
§5.4's strip win, since those run per escape rather than per line.

### 6.7 Watch upstream

- `java.util.regex` on jolt is irregex. **#945 (DFA blowup), #953 (Normalizer),
  #956 (UNIX_LINES terminators) and #955 (Base64 MIME) all landed in the
  installed `v0.8.6-86` (PR #957)** and moved a large share of §3.3 at once
  (§9.1). They did not close the gap: jolt's regex is still 2-4x bb on this
  path, so the host carve-outs stay.
- **jolt's regex engine picks its matcher from capture-group count.** A
  group-free pattern goes to irregex's DFA, a grouped one to the backtracker
  (`java.util.regex`'s own engine). On kmet's patterns this is mostly the right
  split, so treat it as context, not an action item: the effect is 1.9-3x **for**
  the DFA where matches are dense or anchored (`tool_renderers`'s diff tokenizer,
  `edit_diff`), ~3x **against** it where sparse matches are consumed
  (ANSI-CODE-RE `re-seq`/`str/replace` — already on the scanner), and ~25 ms
  one-time for a 49-branch union (§9.1). bb shows no gap in any of them.
- Two measurement traps this file has fallen into twice (§9.1): irregex's warmup
  rounds run 3-4x steady state, so benchmarks must interleave A/B/A and discard
  warmup (§6.2b); and wrapping a grouped pattern as `( (?i)… )` moves `(?i)`
  *inside* the group, which produced a bogus 113x regression.
- Reader/IO workarounds (#946/#947/#948/#952/#954) are on the tool/IO paths,
  not the frame path; they do not affect the numbers above. All of them have
  since landed (#946/#948/#952 in `-86`; #947/#954 in `-98`) and the last
  workarounds are gone (all closed upstream).

---

## 7. End-to-end result of the applied wins

Same harness (fresh session, 14 s settle, 150 keys at 25 ms), same device,
KILLED of any background build, median of 3:

| tree | `jolt run` (ticks / 150 keys) | `bb run` | jolt/bb |
|---|---|---|---|
| HEAD | 158 / 157 / 155 → **157** | 115 / 106 / 114 → **114** | 1.38x |
| with §5 wins | 113 / 113 / 99 → **113** | 116 / 107 / 127 → **116** | **0.97x** |

`jolt run` typing CPU dropped **~28 %** on this workload (the ratio used to be
1.4–2.5x depending on the run). bb is unchanged, as expected: §5.1 and §5.2 are
near-no-ops on its primitives, and §5.4 deliberately keeps the regex path there.

**The parity claim is phone-specific.** Re-measured on x86_64 (§9.3) the same
~24 % drop reproduces, but from 68 to 51 ticks/300 keys against bb's 30-32 —
**~1.6x**, not 0.97x. The phone is memory-bandwidth-bound, which penalizes
jolt's allocation-heavy path hardest; on a fast x86 box that pressure is absent
and the residual primitive gap shows. Read §7's ratio as "closed on this
device", not as a host-independent property.

Caveat: this is one workload (typing into a fresh editor). Streaming markdown
and scrolled-up frames depend on the frame path — re-measure with a pinned
fixture before generalizing; that is exactly what §6.3 targets.

---

## 8. Verification

- Correctness: `kmet.test-utils`, `kmet.test-keys`,
  `kmet.tui.components.test-editor`, `kmet.tui.components.test-markdown`,
  `kmet.tui.components.test-truncated-text`, `kmet.tui.components.test-text`,
  `kmet.tui.test-render-loop`, `kmet.agent.ui.tui.test-tool-renderers` (or
  `bb test-changed`) — and the same namespaces under `jolt test`, since §5.4
  adds a jolt-only code path. All green on both hosts when the §5 wins landed.
- Equality contract for the scanner: `kmet.test-utils/test-strip-ansi-host-equivalence`
  compares `strip-ansi-native` against the regex strip on a corpus (private-parameter
  CSI, unterminated OSC, lone ESC, OSC 8 hyperlinks, CJK) **on both hosts**.
- Performance: re-run the recipes in §1; use steady-state (warmup) microbenches
  and the `/proc/<pid>/stat` per-key method (a 14 s settle, 150 keys at 25 ms,
  fresh session per round, median of 3). Record numbers here.

---

## 9. Re-measurement on the installed `v0.8.6-86-g234f460b` (x86_64)

Re-run of §3/§5/§7 after upgrading jolt. Different machine class from §1's
Termux/aarch64 phone (x86_64 WSL2, 14 cores; `bb` 25.0.4/GraalVM): **absolute
µs are not comparable across machines** — compare ratios and same-machine A/Bs,
not the tables' numbers. The §5 optimizations are pinned the strongest way
available: the pre-§5 *files* (`db199e5^`'s `utils.clj` / `keys.clj` /
`editor.clj`) loaded side by side with the current tree, on the same jolt.

> Measurement caveat: like §4, the frame instrumentation was applied, measured,
> and reverted (`git status` clean before and after). The per-op A/Bs are
> steady-state medians of 5 rounds with 2 warmups; the typing numbers are
> medians of 3 fresh sessions.

### 9.1 Upstream moved the primitives a lot

`v0.8.6-86` = PR #957 (regex DFA work budget #945, UNIX_LINES terminator set
#956, Normalizer #953, Base64 MIME #955, Reader/IO #946/#948/#952) plus the
upstream perf items in its changelog. Same microbenches, phone numbers alongside:

| op (jolt) | §3.3 (−83, phone) | −86 (x86) | bb −86 | jolt/bb |
|---|---|---|---|---|
| `str/replace` ANSI-CODE-RE, styled 100ch | 17.3 µs | **7–8** | 1.7 | 4.1x |
| `visible-width` styled 100ch | 24.5 | **4.2** | 2.9 | 1.4x |
| `visible-width` 1-char | 3.0 | **0.27** | 0.11 | 2.5x |
| `visible-width` plain 100ch | 10.9 | **2.5** | 1.2 | 2.1x |

(`md/parse` is measured at a different size than §3.3's row — 16.8 KB here vs
21.6 KB there: **84 ms → 25 ms** on jolt, 13 ms bb, i.e. still ~1.9x.)

So #945/#956 landed and the regex engine is materially faster — but jolt's
`java.util.regex` is still 2-4x bb on this path, and **`mapv`/seq allocation is
~10x** (`mapv identity` over 100 short lines: jolt 13.3 µs vs bb 1.4; `mapv inc`
over 100: 13.8 vs 2.0). Every per-line `mapv` chain in the frame body pays it.

**The #945 stall is gone, but the pattern it hit still pays a one-time DFA
build.** kmet's real `retryable-error-regex` (the 49-alternation union, 873
chars) has no capture group, so jolt compiles it to irregex's **DFA**; adding
one capture group forces the **backtracker** (`java.util.regex`'s own engine).
Cold start, one variant per fresh process:

| `retryable-error-regex` | jolt | bb |
|---|---|---|
| cold first `re-find` (compile + DFA build) | **29.4 ms** | 0.42 ms |
| … with one capture group (backtracker) | **3.7 ms** | 0.40 ms |
| steady-state per `re-find` (1800-char miss) | 796 µs | 464 µs |
| … with one capture group | 758 µs | 470 µs |

So the DFA *build* is the cold cost (~25 ms of the 29.4, vs bb's 0.42), and
**steady state is a wash** (796 vs 758 µs — the 1800-char scan dominates either
engine). The workaround buys the one-time 25 ms and nothing per call.

**Not filed upstream, and it should stay that way.** Nothing kmet runs gets
faster from a fix: the classifier is a single `re-find` on an error path; the one
scanning regex (§5.4) is already on the hand-rolled scanner, which beats *both*
engines; and the remaining group-free multi-branch patterns are on the **right**
engine — `tool_renderers`'s diff tokenizer `#"\s+|\S+"` is *faster* group-free
(grouping costs 1.15x on jolt, 1.45x on bb), `edit_diff`'s
`#"[^\n]*\n|[^\n]+"` is a wash. Worse, the obvious upstream fix
("multi-branch → prefer the backtracker") would regress the diff tokenizer, and
the DFA is the only thing standing between `kmet.libs.markdown`'s
nested-quantifier matchers and catastrophic backtracking on non-matching input.
If it is ever raised upstream, raise it narrowly as *"do not build a DFA the
budget will reject"*, never as an engine preference.

> Earlier revisions of this file claimed a 2.5x steady-state win for the
grouped `retryable-error-regex` (2.01 → 0.79 ms). That was an artifact: the
non-interleaved benchmark measured the *warming* rounds (irregex's first
rounds are 3-4x its steady state — see §6.2b). The interleaved A/B/A numbers
above replace it, and the proposed change to `kmet.app.loop` was dropped. A
second revision measured a grouped `bare-url-re` as 113x slower; that was
building the grouped pattern as `( (?i)… )`, which moves `(?i)` *inside* the
group — with `(?i)` outside, grouped and group-free are identical (0.108 vs
0.121 µs).

### 9.2 The §5 wins are still needed — A/B against the pre-§5 files

| op | pre-§5 | current | Δ | (bb pre → cur) |
|---|---|---|---|---|
| `strip-ansi-codes` styled | 6.85 µs | **0.92** | 7.4x | 1.77 → 2.09 |
| `visible-width` plain 100 | 4.99 | **2.47** | 2.0x | 2.54 → 1.16 |
| `visible-width` 1-char | 1.02 | **0.27** | 3.8x | 0.19 → 0.11 |
| `visible-width` styled | 9.35 | **4.24** | 2.2x | 2.96 → 2.94 |
| `visible-width` md | 9.28 | **4.28** | 2.2x | 2.90 → 3.47 |
| `matches-key?` ctrl+c | 5.95 | **0.31** | **19x** | 2.14 → 0.62 |
| 40 chord checks (one keystroke) | 244 µs | **12** | 20x | 92 → 13 |

- **§5.1** holds, and the ASCII-first regex is still the right test: a hand-rolled
  `loop`+`nth`+`int` printable-ASCII scan costs 3.6 µs vs the regex's 2.2 on
  jolt (7.3 vs 1.1 on bb), so `re-find #"[^\u0020-\u007e]"` stays.
- **§5.4** holds and the margin *widened*: jolt's regex strip got ~2.5x faster
  (17.3 → 7-8 µs) but the scanner stayed ~1 µs, so 5x → **7.4x**. Frame-level,
  with the carve-out forced off: diff+emit 27.9 → **34.9 ms**/200 frames (+25 %),
  total frame 221 → 238 ms (+8 %), repeatable over 3 rounds.
- **§5.2** holds and is the dominant end-to-end win (below).
- **§5.3** (editor `trigger-spec`) is *not* re-measured: the code is unchanged
  and untouched by the upgrade, and its re-pattern/escaping cost sits on the
  same keystroke path §5.2 already dominates. No reason to expect it regressed.

### 9.3 End-to-end: the improvement reproduces, the parity does not

300 keys @ 20 ms, fresh session, 14 s settle, net CPU ticks (`/proc` fields 14+15
minus an equal-length idle window), median shown:

| tree | jolt runs | median | bb median |
|---|---|---|---|
| pre-§5 files | 66 / 68 / 81 | **68** | — |
| current | 48 / 51 / 52 | **~51** | 30–32 |

**~24 % typing-CPU drop** (perf.md's ~28 % reproduces). But jolt/bb is **~1.6x**
here, not §7's 0.97x — parity was a phone property, not a host-independent one.
The §5 wins bought the same *relative* improvement; they did not close the
absolute gap on x86. Absolute ticks are not comparable to §7's either (a faster
box commits the same key work in fewer ticks); read the ratios, not the counts.

### 9.4 Frame attribution (200 frames, 23-line doc, instrumented per §4)

> **Superseded by §11.3** (phone, v0.8.10). Note the `applyLineResets` row
> predates 4240e45, which moved the resets to emit time — that phase no
> longer exists.

| phase | jolt | bb | ratio | §3.1 ratio |
|---|---|---|---|---|
| render-stack | ~760 µs | ~375 | 2.0x | 1.7x |
| normalize | ~84 | ~22 | 3.8x | 3.9x |
| applyLineResets | ~67 | ~27 | 2.5x | 2.6x |
| diff+emit | ~140 | ~77 | 1.8x | 1.9x |
| **total** | **~1050** | **~498** | **2.1x** | 1.9x |

Corrections to §3/§6:

- **The pre-§5 and current frame phases are identical.** The §5 wins landed in
  the *input* path (`matches-key?`), not the frame path — `visible-width` is not
  called often enough per frame for its 2x to register there. §3.1's per-phase
  ratios are the *unoptimized* frame; they remain the right targets.
- **§6.1's normalize guard is dead**: `normalize-terminal-output` 1.05 µs vs the
  guarded 1.18 µs on a plain line. The `SEGMENT-RESET` concat is the only
  remaining line-work item, ~6 % of frame.
- **§6.6 still stands**: `ansi-code-at` regex 1.36 µs vs scanner 0.26 on jolt
  (bb 0.55 vs 0.74 the other way) — another `#?(:jolt … :default …)`.
- **§6.3 is now the top algorithmic lever**: `Markdown` re-parses the whole text
  per render (`text-atom` ticks, so `track!` cannot cache it) — 2.4 ms/frame at
  2 KB, **25 ms at 16.8 KB** on jolt (13 ms bb).

---

## 10. Full expanded render of a large session (2026-09-19)

Resume path: `session/load-session` → `replay-branch!` → the first
`core/render` of the whole chat, tool display mode `:expanded` (every tool and
thinking block at full size). This is the expensive end of the frame path —
the scroll view renders the *entire* child document every pass ("render itself
never clips", `scroll_view.clj`) and windows it afterwards; `track!` makes the
follow-up frames cheap, but the first one pays for every message. Measured on
the Termux/aarch64 phone with `bb` (same tree, same sessions in
`~/.kmet/sessions/`); A/B runs of the same tree (numbers vary ±10–20 % run to
run).

Two real sessions, both ~3.8 MB on disk:

| session | context messages (after compaction) | final lines | old: replay | old: render #1 | old: render #2 | new: replay | new: render #1 | new: render #2 |
|---|---|---|---|---|---|---|---|---|
| A `1a0060c8776` | 224 (110 tools) | 3,620 | 1.9 s | 3.4 s | 1.5 s | **15 ms** | **0.93 s** | **2 ms** |
| B `1a099712492` | 636 (591 tools) | 17,339 | 10.3 s | 17.5 s | 6.8 s | **19 ms** | **4.8 s** | **3 ms** |

"Old/new" = before/after the fixes below, old files checked out
(`6be6ae2`) and re-measured back to back on 2026-09-19; the `new` columns are
the current tree (§10.5's grapheme fast path included). To a stable first
frame (replay + the first content-stable render): session A **6.7 s →
0.95 s**, session B **34.6 s → 4.8 s**. Replay and the old render #2 carry
the most run-to-run noise: B's replay measured 10.3–14.7 s across runs, and
the old render #2 depends on the worktree — the pre-10.3 call preview read
the *current* files, so it forced a settle frame for every edit whose file
had changed since, and the same old code measured 6.8 s here against 0.35 s
in an earlier run where it needed few corrections. Steady-state frames after
that: **2–6 ms** (session B), so streaming/typing is unaffected — the cost is
concentrated in the first full render and in any invalidation that drops the
whole tree. Session B's per-role cold split at HEAD (bb): assistant markdown
2.9 s / 10,068 lines / **1.50 MB** of text (~1.9 µs/char), tools 1.2 s /
7,012 lines / 1.03 MB, compaction summary 0.09 s, user 3 ms.

Cheap follow-up interactions, once the transcript is warm (session B,
re-measured at HEAD; the pre-10.3 table read 1.9 / 3.7 / 17.5 / 18.9 s — the
edit settle fired again inside every global reflow):

| action | cost | note |
|---|---|---|
| Ctrl+O → collapsed | 0.6 s | tool components re-render only |
| Ctrl+O → expanded | 1.4 s | full tool output re-render |
| theme switch | 4.2 s | every markdown message re-parses (colors are baked in) |
| output-pad change | 4.0–4.2 s | every boxed message re-wraps |
| ordinary frame | 3–5 ms | caches warm |

### 10.1 Fix: the assistant render body tracked its own output atoms

`AssistantMessageComponent/render` reads `rendered-text-atom` /
`rendered-*-lines-atom` / `last-render-width-atom` inside the `track!` body,
but `reflow-all!` *writes* those atoms on a cache miss. track! stores the
values **as read**, so the write landed after the read and the frame was
discarded ("a body that invalidates itself mid-run is not cached") — the next
render re-ran the body, and for a thinking-only/tool-call message (text `nil`
vs the stored `""`) it reflowed and re-parsed the whole markdown again. So a
width change paid the reflow twice — a second parse for the empty-text
messages, a second body run for every message; the terminal's first paint at
a width other than 80 always did. The output atoms are the
body's own products, not inputs — read them through untracked helpers (the
same pattern tool_execution uses for `last-call-component`), leaving
text/thinking/streaming/hide/label/pad/theme as the tracked input set. Session
A render #2: **1.57 s → 0.15–0.21 s**; the width-change test
(`test-width-change-reflows-once`) fails on the old code.

### 10.2 Fix: no eager width-80 reflow in the assistant constructor

`make-assistant-message` reflowed the whole message at a hardcoded width 80 at
construction. Replay constructs every message, so resuming a large session
parsed 1.5 MB of markdown at 80 — and a first frame at any other width could
not reuse a line of it, parsing everything again. Lines are now
built lazily by the first render at the width it is actually given (the
`track!` stale check can see they are empty). Session B replay:
**10–15 s → 19 ms** (the first runs measured 14.7 s; replay varies with load),
combined with 10.1, the first render became content-stable
(previously it produced 16,543 lines and a second pass grew the document).

### 10.3 Fix: replayed edits render the recorded diff on the first pass

`render-edit-call` computed its preview from the *current* file (slurp + fuzzy
apply + diff + highlight), then `render-edit-result` compared that against the
session-recorded `:details :diff`, installed the recorded one when they
differed, and called `:invalidate` — a forced second frame. On replay the
filesystem is the wrong source of truth anyway (the file has changed since),
and the settle frame changed lines **above** the 30-line viewport: the diff
clamped to the window and set `scrollback-dirty?`, so the next idle input ran
`tui-heal-scrollback!` and re-emitted the whole document with `\u001b[3J`
(the "screen redraws again on the first key press after resume" report). The
call renderer now prefers the recorded diff when the result already carries
one (live streaming is unaffected: `:details` only exists after the result),
and `render-edit-result` agrees with the cached preview, so it neither
rewrites state nor invalidates. With 10.1/10.2 already in: session A first
render **4.7 s → 3.2 s** and content-stable; cold edit tools **552 ms →
258 ms** (19 calls); session B first render **17.6 s → 13.7 s**. Because the
settle also fired inside every global reflow, the warm interactions above
dropped with it (session B: Ctrl+O expanded 3.7 s → 2.9 s, theme switch
17.5 s → 13.9 s). The regression test
(`test-edit-call-prefers-the-recorded-result-diff`) fails on the old code.

A follow-up closes the replay case the recorded diff cannot cover — a
*finished errored* edit has no `:details :diff`, so the call renderer still
computed the preview from today's file (a slurp + fuzzy apply that can only
re-derive the failure; on session B's two errored calls this was the whole
jolt-vs-bb gap). It now skips computation when the render context carries
`:is-error`, placed *after* the cached-preview branch so a live pass keeps the
preview its result dedups against; the recorded error then renders on the
result side. Cold edit tools, session B: **bb 417 → 321 ms, jolt 456 →
309 ms** (the hosts are now at parity). The regression test
(`test-edit-call-skips-the-preview-for-an-errored-result`) fails on the old
code.

### 10.4 bb vs jolt: the profiles invert by subsystem

Same harness (`scripts/bench/kmet_render_bench.clj`), same phone, width 100, one run
each at HEAD (±10–20 %):

| metric | A bb | A jolt | B bb | B jolt |
|---|---|---|---|---|
| replay | 15 ms | 16 ms | 19 ms | 20 ms |
| render #1 (cold) | 3.09 s | 2.69 s | 13.88 s | 11.50 s |
| render #2 (warm) | 1.6 ms | 1.4 ms | 3.4 ms | 4.3 ms |
| Ctrl+O → collapsed | 0.57 s | 0.84 s | 1.33 s | 1.65 s |
| Ctrl+O → expanded | 1.09 s | 1.16 s | 2.93 s | 2.99 s |
| theme switch | 2.97 s | 2.06 s | 13.72 s | 8.31 s |
| output-pad change | 3.00 s | 2.04 s | 13.98 s | 8.36 s |

Per-role cold split of B (`roles` mode; two runs where they disagreed;
pre-workaround):

| role | bb | jolt | |
|---|---|---|---|
| assistant markdown | 10.7 / 11.2 s | 5.5 / 6.1 s | jolt ~1.9x faster |
| tools | 2.6 s | 5.7 / 6.8 s | jolt ~2.3x slower |
| compaction | 0.41 / 0.51 s | 0.17 s | jolt ~2.6x faster |

So §9.3's "jolt ~1.6x slower" is not a single factor: the markdown-heavy work
(assistant render, the theme/pad global reflows, the compaction summary) is
~2x **faster** on jolt, while tool rendering is ~2.3x slower. The whole tool
gap is one call pair. On session B two `edit` calls carry no recorded
`:details :diff` (their results were errors), so `render-edit-call` falls back
to computing a preview from the *current* file — `interactive.clj`, 275 KB /
5,140 lines, changed since the session — through
`kmet.libs.edit-diff/apply-edits-to-normalized-content`, which fails the exact
match and scans for the fuzzy one. One call: **bb 196 ms, jolt 2,851 ms**
(slurp + normalize are ~2 ms on both); the regex behind it is filed upstream
as [jolt#1062](https://github.com/jolt-lang/jolt/issues/1062) — per-line
`$`-anchored patterns are 10–70x slower on jolt, and the fix is `str/trimr`
(landed, below).
Excluding it, the 34 recorded diffs
render *faster* on jolt than on bb (edit tool ≈54 ms vs ≈330 ms); bash and
read are within 10 % (`bash` bb 1,481 ms / 140, jolt 1,616; `read` bb 640 / 25,
jolt 741).

**After the workarounds (landed 2026-09-19):** `str/trimr` in place of the
per-line regex, one memoized normalization per apply pass, and a single
`re-matches [\x00-\x7F]*` scan that lets pure-ASCII text skip both NFKC and
the four quote/dash/space replaces — a 248 KB ASCII text normalizes in **11 ms
bb / 9 ms jolt** (was 76 / 47). The failing preview call is on
`interactive.clj`, which has 4,175 non-ASCII chars (box drawing, em dashes),
so its guard fails fast and the replaces still run: **bb ~72 ms, jolt
~138 ms** (was 196 / 2,851). The jolt edit tool in the roles profile:
3,339 → 744 (trimr) → 456 (memoized normalization) → **309 ms** (§10.3's
errored-preview skip; bb 522 → 417 → 321), the hosts now at parity.
For non-ASCII content the four whole-text replaces dominate (53 ms bb /
103 ms jolt); selective per-class replaces (the detection scans cost as much
as the passes) and one alternation with a callback (60 / 96 ms) both measured
no better.

### 10.5 Fix: `visible-width` skipped the grapheme walker for plain code points

After 10.1–10.3 the cold render was still ~14 s on bb, and instrumenting the
primitives showed why: `visible-width-plain` walked **every non-ASCII code
point** one at a time through the grapheme-aware
`grapheme-width-and-next` — ~8 µs per character under SCI, 41,519 calls /
10.4 s inside a 13.8 s render. The bulk was box drawing and punctuation
(`───`, `⎿`, `⏺`, `…`, `—`), which the walker treats exactly like any other
width-1 character. The walker's special cases (CJK ranges,
`wide-emoji-ranges`, the zero-width set and variation selectors, and every
non-BMP code point) are now a regex class **generated from the same range
tables the walker branches on**; a string that misses the class takes the
arithmetic sum instead (`plain-char-width`: 1 per printable character, 0 per
control, 3 per tab). `kmet.tui.test-utils` pins the two paths equal over a
hand-picked corpus plus 400 randomised mixes of every range boundary ±1, and
asserts a box-drawing line never enters the walker.

bb, session B, **back-to-back A/B** (parent `ac42577` in a worktree vs
HEAD, same command, one after the other): cold render **14.30 → 4.18 s**,
Ctrl+O collapsed 1.41 → 0.65 s, Ctrl+O expanded 3.53 → 1.40 s, theme switch
13.77 → 4.56 s, output-pad 13.69 → 4.19 s, warm frame 3.6 → 3.5 ms; roles
total 14.4 → 4.2 s (tools 2.57 → 1.21 s: bash 1.58 → 0.69, read 0.67 →
0.40, edit 0.32 → 0.12). Session A the same way: cold render **3.16 →
0.91 s**, theme 3.10 → 0.89 s. The rendered document is **identical** —
17,335 lines / 2,594,760 chars, `hash` 2126486799 in both trees, and the
same hash again under jolt.

jolt gains less — the full render went **8.64 → 6.44 s** (1.34×; its walker
is ~2.2 µs/char, and its ANSI strip and regex engine dominate what is left),
theme switch 8.87 → 8.05 s — but the same document hash means the two hosts
stay pixel-for-pixel in step.

### 10.6 What is left

- **§6.3 (incremental markdown) is now the biggest remaining share.** After
  §10.5 the assistant markdown role is 2.9 s of the 4.2 s cold render, and
  `md/parse` (~0.8 s) plus `parse-inline` (~1.4 s) are ~2.2 s of that — every
  block is parsed once per render, and a theme switch or pad change re-pays
  it. Block-level parse reuse by text would cut both the first render and the
  global reflow.
- **Live edit previews** still compute the filesystem preview while args
  stream (inherent — the result does not exist yet). The replay half is now
  free: a finished result's diff wins over the preview (§10.3) and a finished
  *errored* edit — which records no diff — skips the computation entirely.
  What remains is repeated live previews of the same file (memoize by path +
  mtime + edits) and the non-ASCII normalization cost (§10.4).
- **Virtualization** (rendering only the visible components) would remove the
  first-render cliff entirely, but the scroll view's height math and the
  track!-cached-tree model make it a design change, not a local fix.

---

## 11. Re-measurement on `jolt v0.8.10-10-g9c439021` (Termux/aarch64, 2026-09-20/21)

Fresh sweep on the phone, after upgrading jolt from v0.8.6-83 to
**v0.8.10-10-g9c439021** (`bb` 1.13.222). Two things moved the comparison:
upstream's regex/perf work (shipped across v0.8.7–v0.8.10; the `$`-anchor fix
jolt#1062 is in this toolchain) and kmet's own §10.5 + `parse-inline` work.
The harnesses are committed: `scripts/bench/kmet_render_bench.clj` (§10's),
`scripts/bench/kmet_perf_bench.clj` (primitive sweep + `cold-regex` / `md-session` /
`kitty-scan` modes) and `scripts/bench/perf_typing.sh` (§7/§9.3's recipe, settle
default 30 s — see §11.4). All µs figures are steady-state medians with warmups
(§6.2b), reported as same-process host ratios; absolutes are phone-specific like
every phone table here.

### 11.1 The primitive profile inverted

Steady-state medians (bb / jolt; µs unless noted):

| op | bb | jolt | jolt/bb | §3.3 (-83) |
|---|---|---|---|---|
| `visible-width` plain 100 | 2.99 | 2.82 | 0.94 | 1.6x |
| `visible-width` 1-char | 0.354 | 0.293 | 0.83 | 6.0x |
| `visible-width` styled 100 | 7.75 | 5.38 | 0.69 | 3.1x |
| `visible-width` md-styled | 15.7 | 9.11 | 0.58 | — |
| `visible-width` box-drawing line | 108.8 | 9.42 | **0.09** | — |
| `visible-width` emoji line | 104.3 | 9.20 | **0.09** | — |
| `visible-width` CJK line | 941 | 266 | 0.28 | — |
| `strip-ansi-codes` styled (host path) | 4.52 | 1.79 | 0.40 | — |
| `strip-ansi-native` styled (scanner) | 7.43 | 1.79 | 0.24 | — |
| `str/replace ANSI-CODE-RE` styled | 4.42 | 17.05 | 3.9x | 3.8x |
| `ansi-code-at` hit | 2.41 | 3.40 | 1.4x | — |
| `re-find` ASCII class, plain 100 | 2.88 | 2.79 | 0.97 | 1.6x |
| `normalize-terminal-output` plain, guarded | 0.314 | 0.982 | 3.1x | — |
| … two Thai replaces only (unguarded) | 0.280 | 0.686 | 2.4x | — |
| `SEGMENT-RESET` concat alone | 0.307 | 0.129 | 0.42 | — |
| `mapv identity` 100 lines | 3.11 | 8.39 | 2.7x | ~10x (§9.1) |
| `mapv (str line reset)` 100 | 23.1 | 19.4 | 0.84 | — |
| `keys/matches-key?` | 1.20 | 0.495 | 0.41 | 3.0x |
| one keystroke, 40 chord checks | 49.9 | 27.2 | 0.55 | — |
| editor render, 500-char line | 1.42 ms | 1.07 ms | 0.75 | 1.9x |
| editor render, 20 lines | 195 µs | 523 µs | 2.7x | 5.0x |
| `md/parse` 2KB | 1.45 ms | 0.861 ms | 0.59 | — |
| `md/parse` 21.6KB | 16.25 ms | 9.53 ms | 0.59 | 1.9x (§9.1) |
| markdown render 2KB (cache miss) | 2.76 ms | 2.86 ms | 1.04 | 2.7x |
| fib 27 | 98.6 ms | 4.69 ms | 0.05 | 0.05x |
| keyword lookup ×1e6 | 107 ms | 15.97 ms | 0.15 | 0.2x |
| `subs` ×1e5 | 18.0 ms | 10.37 ms | 0.58 | 0.4x |
| `assoc-in` ×1e5 | 24.9 ms | 40.1 ms | 1.61 | 1.45x |
| `swap!` ×1e5 | 18.0 ms | 8.88 ms | 0.49 | 1.4x |
| `mapv inc` ×1e5 | 51.0 ms | 62.9 ms | 1.23 | 4.6x |

Reading: §3.3's "jolt's primitives lose 2–5x on the operations the frame path
is made of" no longer holds — jolt now wins the width/string/regex/parse
primitives; what still loses is seq/allocation-heavy work (`mapv identity`
2.7x, editor 20-line render 2.7x) plus `assoc-in`/`mapv inc`. Host carve-outs:

- **§5.4 holds and widened**: jolt's regex strip is 17.05 µs styled against the
  scanner's 1.79 (9.5x; 5x at -86), while bb keeps the regex (4.42 vs 7.43).
- **§5.1 holds** (ASCII test first, 2.99 plain). The new cost centre is
  §10.5's `grapheme-complex-re` miss test: ~1.2 µs/char on bb but ~0.1 µs/char
  on jolt, so a box-drawing line is **108.8 vs 9.42** — the widest gap in the
  table, now *against bb*. (It still replaced the ~8 µs/char SCI walker, so
  §10.5's bb win stands; the 11x jolt edge is why §11.2's assistant role is no
  longer jolt-favored.)
- **The normalize early-out (b9408c5) no longer pays**: guarded 0.314/0.982 vs
  unguarded 0.280/0.686 per plain 100-char line — a wash on bb, ~0.3 µs/line
  on jolt, where `str/includes?` is the expensive primitive and the two
  replaces are near-free. The "dead guard" of §6.1/§9.4 now measures as a
  real jolt loss; live frames pay it (§11.3).

`retryable-error-regex` (the 873-char, 49-alternation union; fresh process,
1,880-char miss; `cold-regex` mode):

| | bb | jolt |
|---|---|---|
| cold first `re-find` (group-free → DFA build) | 1.30 ms | **122.0 ms** |
| cold first `re-find` (one capture group → backtracker) | 1.31 ms | 65.1 ms |
| steady `re-find` | 1.32 ms | 3.55 ms (grouped 3.82) |

§9.1's x86 story (29.4 ms DFA build, steady-state wash) has drifted on the
phone: the DFA build is 122 ms and the grouped variant halves it, but steady
state is now 2.7x bb. The classifier runs once per error path, so this stays a
status note.

### 11.2 The render bench at v0.8.10

Session B (`1a099712492`, 17,335 harness lines; medians of 3–5 runs; §10.5's
values alongside):

| metric | bb | jolt | §10.5 bb | §10.5 jolt |
|---|---|---|---|---|
| replay | 19 ms | 21 ms | 19 | 19 |
| render #1 (cold) | 3.87 s | 5.5 s | 4.18 | 6.44 |
| render #2 (warm) | 3.4 ms | 4.4 ms | — | — |
| Ctrl+O collapsed | 644 ms | 1.46 s | 0.65 | — |
| Ctrl+O expanded | 1.42 s | 2.8 s | 1.40 | — |
| theme switch | 3.89 s | 5.5 s | 4.56 | 8.05 |
| output-pad | 3.8 s | 5.4 s | 4.19 | — |
| warm frame | 3.9 ms | 3.6 ms | 3.5 | — |

Both hosts improved (jolt mostly upstream, bb from `parse-inline`), but jolt
still pays ~1.4x on the full render and ~2x on the Ctrl+O re-renders. Run
shape matters: jolt's first run after a cold cache measured 11.3 s cold render
vs 5.3 s once warm — discard the first run per boot.

Roles B (cold per-role, one pass):

| role | bb | jolt |
|---|---|---|
| assistant markdown | 2.86 s | 3.15 s |
| tools | 1.64 s | 2.96 s |
| compaction | 0.10 s | 0.09 s |
| total | 4.60 s | 6.21 s |
| — per tool (bash / read / edit) | 992 / 511 / 133 ms | 1886 / 825 / 250 ms |

The pre-§10.5 jolt-favored assistant role is gone (bb 2.86 vs jolt 3.15): the
§10.5 width fast path and `parse-inline` cut bb harder than jolt, consistent
with the `grapheme-complex-re` gap above. The tool renderers remain jolt's
biggest deficit (1.8–1.9x on bash/edit).

Session A (`1a0060c8776`, 3,617 lines): cold bb 857 ms / jolt 1268 ms; roles
bb 852 ms (assistant 434, tools 313, compaction 104) vs jolt 1376 ms
(assistant 352, tools 949, compaction 74) — same shape: jolt wins markdown,
loses the tools ~3x (bash 259→642, edit 54→308).

### 11.3 Live frame phases (and the kitty scan that dominated them)

The §4 instrumentation was re-applied (render-stack / normalize /
composite-flashes / diff+emit / write) and reverted. Two scenarios: 300 keys
into a fresh session, and 200 keys into a resumed session B (`--session`).
**The live resumed document is 3,739 lines, not the harness's 17,335** — the
app collapses/compacts what the harness replays expanded — so these are the
frames a user actually gets:

| phase (per frame) | fresh bb | fresh jolt | B bb | B jolt |
|---|---|---|---|---|
| render-stack | 1.68 ms | 2.27 ms | 7.8 ms | 6.0 ms |
| normalize | 0.25 ms | 0.42 ms | 3.9 ms | 11.3 ms |
| composite-flashes | 0.08 ms | 0.08 ms | 0.08 ms | 0.09 ms |
| diff+emit | 0.13 ms | 0.10 ms | 8.1 ms | 7.6 ms |
| — kitty expand walk | — | — | 6.3 ms | 7.1 ms |
| write | 0.17 ms | 0.25 ms | 0.09 ms | 0.15 ms |
| **total** | **~2.3 ms** | **~3.1 ms** | **~20 ms** | **~25 ms** |

- **The kitty scan was the biggest removable item**:
  `expand-changed-range-for-kitty-images` (core.clj) walked every line of
  `prev` and `lines` on every changed frame (48/50 of them) with no
  capabilities check — 11.7 ms/pass on bb and 15.8 on jolt over the harness's
  17,335 lines (0.68 µs/line bb), and 6.3/7.1 ms on the live 3.7k-line doc.
  Fixed in **cb763f6** by guarding on `(:images (img/get-capabilities))` (the
  cached read the loop's `previous-kitty-image-ids` reset already uses):
  diff+emit 8.07 → **1.95 ms** (bb) and 7.62 → **0.54 ms** (jolt), ~20–30% of
  the frame, proportional to transcript length.
- **Per-host bottleneck split**: jolt's frame is normalize-bound (11.3 vs 3.9
  ms, ~2.9x, matching the primitive table), bb's is render-stack-bound; the
  diff is cheap on both after cb763f6. Per-line normalize (~1 µs bb, ~3 µs
  jolt on live lines) still matches §9.4's ratio; §9.4's `applyLineResets` row
  no longer exists (resets moved to emit time in 4240e45).
- `md-session` over B's assistant sources: visible text 210 msgs / 21.7k chars
  = 24.7 ms bb (1.14 µs/char) / 36.1 ms jolt (1.67); **thinking** 155 msgs /
  **485k chars** = 524 ms bb (1.08) / 640 ms jolt (1.32) — ~22x the visible
  text. Many small documents cost ~2x the per-char rate of the 21.6 KB
  fixture, and jolt's per-call overhead is what puts it behind there. That is
  §6.3's target surface.

### 11.4 Typing CPU: parity on the phone

`scripts/bench/perf_typing.sh` (300 keys @ 20 ms, net of an equal idle window, fresh
session per round; 8 runs bb / 7 jolt):

| host | runs (net ticks) | median |
|---|---|---|
| bb | 168, 180, 173, 276, 135, 266, 167, 239 | 177 |
| jolt | 163, 140, 240, 184, 272, 210, 195 | 195 |

**~1.1x — parity within the run-to-run noise** (§6.2b: ±40 % per round).
Idle is ~2–3 % of a core on both.

Method caveat learned here: **jolt no longer settles in ~3 s.** With the
current extension set jolt burns ~100 % of a core for ~22–25 s after launch
(bb: ~4 s) before the idle loop parks; bisected to the user-level `clojure`
extension failing under jolt (spec.alpha `jolt-vaget` — ~14 s of work, then
the error); in a sandbox without user extensions jolt idles at ~4 s. The
earlier per-key runs (settle 14 s) measured part of that burn as typing; the
30 s settle above is the fix, and the harness now defaults to it.

### 11.5 What this changes in this file

- **§3.3's reading is superseded** (§11.1): the jolt primitive deficit on the
  frame path is gone. The remaining per-host gap lives in seq/allocation work
  and the tool renderers (§11.2), plus jolt's per-line normalize.
- **§6.1 is fully applied and partly stale**: the emit-time `SEGMENT-RESET`
  (4240e45) removed the line-work item it proposes, and the normalize
  early-out (b9408c5) now measures as a jolt loss (§11.1). Revisit the guard:
  either host-branch it or drop it.
- **§6.3 stands and is the top lever**: the cold render is still dominated by
  assistant markdown (2.9–3.2 s of 4.6–6.2 s), and thinking markdown alone is
  485 KB on session B.
- **§6.6 stands, smaller than it looked**: `ansi-code-at` is 2.41/3.40 µs
  (regex on both); the big jolt regex loss is `str/replace ANSI-CODE-RE`
  (17.05 µs), already carved out by §5.4.
- **cb763f6** closed the last of §6.1's siblings (the kitty walk).

---

## 12. Re-measurement on x86_64 WSL2 at `jolt v0.8.10-32-g9786b7fa` (2026-09-21)

The §11 harnesses re-run on this box (x86_64 WSL2, 14 cores, `bb` 1.13.222,
`jolt v0.8.10-32-g9786b7fa`; §9's host class, 16 commits past the #1067
Normalizer fix). Absolute µs are phone-vs-desktop incomparable like §9's —
read the ratios. The fixture is the largest local session,
`19fdb60dc61-b305.ednl` (8,793 lines expanded), not §11's A/B phone sessions.

### 12.1 Primitives — §11.1's shape reproduces

Steady-state medians (µs unless noted; jolt/bb here vs §11.1's phone ratio):

| op | bb | jolt | jolt/bb | §11.1 |
|---|---|---|---|---|
| `visible-width` plain 100 | 1.247 | 1.236 | 0.99 | 0.94 |
| `visible-width` 1-char | 0.111 | 0.089 | 0.80 | 0.83 |
| `visible-width` styled 100 | 3.127 | 2.444 | 0.78 | 0.69 |
| `visible-width` box-drawing | 38.257 | 5.481 | **0.14** | 0.09 |
| `visible-width` CJK | 303.1 | 105.2 | 0.35 | 0.28 |
| `strip-ansi-codes` styled (host path) | 1.869 | 0.798 | 0.43 | 0.40 |
| `strip-ansi-native` styled (scanner) | 2.731 | 0.729 | 0.27 | 0.24 |
| `str/replace ANSI-CODE-RE` styled | 1.757 | 7.963 | **4.5** | 3.9 |
| `ansi-code-at` hit | 0.830 | 1.374 | 1.7 | 1.4 |
| `normalize-terminal-output` plain (guarded) | 0.102 | 0.487 | **4.8** | 3.1 |
| … two Thai replaces only (unguarded) | 0.080 | 0.339 | 4.2 | 2.4 |
| `SEGMENT-RESET` concat alone | 0.116 | 0.049 | 0.42 | 0.42 |
| `mapv identity` 100 lines | 1.073 | 3.031 | 2.8 | 2.7 |
| `keys/matches-key?` | 0.420 | 0.195 | 0.46 | 0.41 |
| one keystroke, 40 chord checks | 16.76 | 8.55 | 0.51 | 0.55 |
| editor render, 500-char line | 452.1 | 384.2 | 0.85 | 0.75 |
| editor render, 20 lines | 62.0 | 169.5 | 2.7 | 2.7 |
| `md/parse` 2KB | 411.8 | 309.6 | 0.75 | 0.59 |
| `md/parse` 21.6KB (ms) | 4.48 | 3.62 | 0.81 | 0.59 |
| markdown render 2KB (cache miss) | 798.6 | 1030.4 | 1.29 | 1.04 |
| fib 27 (ms) | 33.40 | 1.89 | 0.06 | 0.05 |
| keyword lookup ×1e6 (ms) | 30.14 | 5.83 | 0.19 | 0.15 |
| `subs` ×1e5 (ms) | 6.87 | 3.83 | 0.56 | 0.58 |
| `assoc-in` ×1e5 (ms) | 6.71 | 12.22 | 1.82 | 1.61 |
| `swap!` ×1e5 (ms) | 5.14 | 2.95 | 0.57 | 0.49 |
| `mapv inc` ×1e5 (ms) | 14.38 | 19.64 | 1.37 | 1.23 |

Same conclusions as the phone: jolt wins widths/strings/parse/keys;
seq/allocation (`mapv identity` 2.8x, editor 20-line 2.7x) and the
`grapheme-complex-re` miss still favour bb; the normalize guard is a jolt
loss (0.102 vs 0.487 µs, the widest ratio in the table); §5.4's carve-out is
confirmed again (`str/replace ANSI-CODE-RE` 7.96 vs scanner 0.73).

`cold-regex` (fresh process): group-free DFA first `re-find` bb 0.48 ms /
jolt **67.89 ms**, steady 0.458 / 1.796; grouped (backtracker) cold 0.44 /
**25.40**, steady 0.504 / 1.593 — the DFA build is cheaper here than the
phone's 122 ms and the grouped variant still halves it.

### 12.2 Render bench — ~1.8x cold, ~2x reflows

`19fdb60dc61-b305.ednl` (8,793 lines, width 100; single run; pre-§12.5):

| metric | bb | jolt | jolt/bb |
|---|---|---|---|
| replay | 4.2 ms | 3.7 ms | — |
| render #1 (cold) | 619.0 ms | 1132.9 ms | 1.83 |
| render #2 (warm) | 0.9 ms | 1.3 ms | — |
| Ctrl+O → collapsed | 185.7 ms | 470.1 ms | 2.53 |
| Ctrl+O → expanded | 565.7 ms | 1133.7 ms | 2.00 |
| theme switch | 613.7 ms | 1239.4 ms | 2.02 |
| theme switch back | 656.4 ms | 1380.5 ms | 2.10 |
| output-pad 1 → 2 | 628.0 ms | 1233.7 ms | 1.96 |
| output-pad 2 → 1 | 634.6 ms | 1288.6 ms | 2.03 |
| warm frame | 1.0 ms | 1.6 ms | — |

Roles (cold per-role): tools bb 661.6 / jolt 1292.3 ms (bash 335/758 n=156,
read 272/525 n=31, write 54.0/9.6 n=1), assistant 20.3/46.1, total
684.7/1345.4 — the same ~2x tools gap as §11.2.

`md-session` over this session's small texts (160 msgs, 5.7k chars):
bb 2.1 ms / jolt 2.8 ms (0.365 / 0.485 µs/char; no thinking messages in this
fixture, unlike §11.3's 485 KB). `kitty-scan`: 8,793 lines, bb 1.11 /
jolt 3.57 ms per pass — and skipped entirely without image support (cb763f6).

### 12.3 Typing CPU — parity reproduces

`scripts/bench/perf_typing.sh` (300 keys @ 20 ms, 30 s settle, fresh session per
round; net ticks):

| host | runs | median |
|---|---|---|
| bb | 39, 41, 44 | 41 |
| jolt | 46, 41, 42 | 42 |

**~1.0x** — the §9.3 x86 gap (1.6x at v0.8.6-86) is closed, as on the phone
(§11.4). Idle is 5 ticks/6 s on both, and jolt's post-launch extension burn
(§11.4) does not show with a 30 s settle.

### 12.4 The Normalizer fix in this build

This toolchain carries the `java.text.Normalizer` fix tracked as #1066: NFKC
over 1.09M ASCII chars is **1.9 ms** on jolt (bb 2.0; was 155.52 ms) and
`isNormalized` on decomposed 200k chars is 0.43 ms (was a full
normalize+compare). No kmet path changes — `edit-diff`'s ASCII guard already
skips NFKC for ASCII and is independent of the fix.

### 12.5 Applied follow-ups (2026-09-21)

Two of §12's improvement candidates landed:

1. **One `[:text]` node per tool-output block** — the bash/read/write/default
   renderers emitted one node per output line; they now emit a single joined
   node (`kmet.agent.ui.tui.tool-renderers/tool-text-lines`). Node work alone, on
   the synthetic 6,438-line body: 259 → 76 ms (bb) and 570 → 172 ms (jolt).
2. **Normalize memo** — `run-render-loop!` reuses the previous frame's
   normalized string for every line `identical?` to its previous raw line
   (`kmet.tui.core/normalize-reusing`), so streaming/typing frames stop
   re-scanning the whole document (§12.1's ~6.4 ms/frame on jolt at 8.8k
   lines). Unit-tested by call counting; full redraws still normalize all.

Same fixture, before → after (bb / jolt):

| metric | bb | jolt |
|---|---|---|
| render #1 (cold) | 619 → **402** ms | 1133 → **918** ms |
| Ctrl+O → expanded | 566 → **343** ms | 1134 → **707** ms |
| Ctrl+O → collapsed | 186 → **139** ms | 470 → **399** ms |
| theme switch | 614 → **398** ms | 1239 → **829** ms |
| output-pad change | 628 → **376** ms | 1234 → **725** ms |
| roles TOTAL | 685 → **400** ms | 1345 → **891** ms |
| — bash role | 335 → **162** ms | 758 → **370** ms |
| — read role | 272 → **214** ms | 525 → **461** ms |

The rendered document grew 8,793 → 9,040 lines: a blank line rendered as its
own per-line `[:text]` node produced zero lines, silently dropping blank
lines from syntax-highlighted read/write output (bash's blank lines were
already ANSI-wrapped and are unchanged). The single node preserves them; the
output is byte-identical across hosts before and after. Read improves least
because its cost is `theme/render-highlighted`, not the node fan-out.

Still open from §12's list: incremental markdown (§6.3) and
virtualization. One more whole-document per-frame pass found while
checking for the same pattern, not in §12.1's table:
`extract-cursor-position`'s marker strip re-scans every line whenever the
focused editor carries a cursor marker — 0.9 ms bb / 5.3 ms jolt per frame on
this document (measured), ~half of it the `some` + full `mapv` pair. It is a
candidate for the same identity-memo treatment, not applied.

A second pass landed the kitty-walk gate and the `ansi-code-at` scanner:

- **Kitty-walk gate.** `expand-changed-range-for-kitty-images` skips both
  whole-document walks when the previous frame had no image ids and LINES
  carries no image line from `first-changed` on (a suffix scan;
  `is-image-line` is the conservative over-approximation of the walk's
  extract-ids test — an image line below `first-changed` would itself be a
  change, contradicting it). The post-frame `(some is-image-line lines)` +
  collect walk now runs only on full redraws: the diff path reports image
  presence through the same gate (`::unchanged` = no diff, keep the
  previous ids). On the 9,040-line doc, image-capable capabilities, no
  images: typing-range calls 14.7 → **0.002 ms** (jolt) / 3.5 → **0.001 ms**
  (bb), mid-doc reflows 14.7 → 3.3 / 3.5 → 0.5 ms, and the 7.5 ms jolt /
  0.6 ms bb post-frame scan is gone from the diff path.
- **`ansi-code-at` scanner (§6.6).** Jolt now takes the hand-rolled scanner
  (`ansi-code-at-native`); bb/JVM keep the anchored regex matcher:
  1.374 → **0.378 µs** on jolt, bb unchanged at 0.830. The two are pinned
  against each other at every index of a corpus on both hosts
  (`kmet.test-utils/test-ansi-code-at-host-equivalence`).

---

## 13. Re-check at `jolt v0.8.20` (phone, 2026-10-09)

Fresh sweep on the Termux/aarch64 phone (`bb` 1.13.225, **`jolt v0.8.20`**),
after the 2026-10-09 TUI work (reconcile skip for `=`-equal subtrees, tag
aliases, weak reakt watches) and with the now-committed live-frame harness
`scripts/bench/kmet_frame_bench.clj` (101241d5) in place of §11.3's manual
instrumentation. The fixture is the largest local session,
`2026-10-04T15-34-09-418Z_1a1078d1709-55a72c05.ednl`: **14,881 rendered
lines / 2.63 MB** at width 100x30. §12's fixture `19fdb60dc61-b305.ednl` is
no longer on the device, so §12's absolutes are not directly comparable.

Two harness fixes were needed and are applied: settle the loop before each
scenario's event window (a trailing frame from cold/reflow was being
attributed to the next scenario), and warm up before the measured window
(the first frames after the cold render still settle caches). The tables are
medians over 4 bb / 3 jolt runs; the phone still swings **~2x run to run** on
the no-change scenario even with identical rendered bytes and identity-hit
counts (§6.2b's warning holds), so read medians and same-run host ratios,
not single runs.

### 13.1 Hot path, streaming, redraw (per-frame medians)

| scenario | bb | jolt | jolt/bb |
|---|---|---|---|
| cold (first frame) | **3.06 s** | **4.69 s** | 1.53 |
| typing (editor tick) | **14.2 ms** | **20.3 ms** | 1.43 |
| stream (append/frame) | **19.4 ms** | **30.4 ms** | 1.57 |
| redraw (one resize → clearing) | **3.17 s** | **5.05 s** | 1.59 |
| calm (no change; unstable) | 16.6–34.7 ms | 29.4–44.5 ms | — |

Phase split (per-frame medians, ms):

| phase | bb cold | jolt cold | bb typing | jolt typing | bb stream | jolt stream | bb redraw | jolt redraw |
|---|---|---|---|---|---|---|---|---|
| render-stack | 3010 | 4579 | 2.9 | 3.1 | 7.2 | 12.6 | 3108 | 4831 |
| cursor | 2.5 | 9.3 | 2.5 | 9.7 | 2.5 | 9.1 | 2.8 | 9.1 |
| normalize | 6.6 | 27.6 | 5.0 | 5.8 | 5.0 | 5.6 | 8.3 | 28.0 |
| diff+emit | 41.4 | 69.6 | 3.8 | 1.6 | 4.1 | 1.5 | 49.8 | 74.8 |

Reading:

- **No regression on the stable paths.** Typing/streaming frames have the
  same shape as §11.3 after cb763f6 + §12.5; typing is ~1.4x, streaming
  ~1.6x on jolt. bb's frame is render-stack-bound; jolt's is **cursor-bound**:
  `extract-cursor-position` is 9.7 ms of the 20.3 ms typing frame (48%; bb
  2.5 ms) — the per-line `some str/includes?` scan at ~0.6 µs/line vs bb's
  0.17 (no marker is present in the harness; a focused editor adds the
  strip). §12.5's identity-memo candidate is now the top per-frame lever on
  jolt, ahead of incremental markdown for steady frames.
- **Streaming is render-stack growth**: 12.6 ms jolt / 7.2 bb and rising with
  the message — the whole-message markdown re-parse of §6.3, unchanged, and
  ~1.5x a typing frame.
- **Redraw = the cold render again**: ~3.2 s bb / ~5.0 s jolt per resize on
  this transcript, 96-98 % render-stack; normalize stays ~4x on jolt (28 vs
  8 ms) and diff+emit pays the full 2.6 MB re-emit (~50 / 75 ms). Cold's
  normalize is the §11.1 jolt per-line loss on 177-char average lines.
- The **calm** scenario is the one unstable measurement: with the same
  identity hits (14,878/14,881) and 6-byte write, whole runs land at either
  ~typing cost or ~2x it. Treat 14-20 ms as the settled no-change frame and
  the 2x tail as the phone's clock/scheduling state.

### 13.2 Idle and typing CPU — recipe fix

The app task was renamed **`bb run` → `bb start`** in 00b3e373 (2026-09-29;
`bb run` now opens a babashka REPL, not kmet), so §1's recipe is stale and
`scripts/bench/perf_typing.sh` now documents `bb start` / `jolt start`. Corrected
fresh-app measurement (300 keys @ 20 ms, 30 s bb / 40 s jolt settle, net of an
equal idle window):

| host | idle (ticks / 6 s) | typing net (ticks / 300 keys) | ticks/key |
|---|---|---|---|
| bb | 25 (4.2 % of a core) | 208 / 241 | ~0.75 |
| jolt | 15 / 17 (2.6 %) | 234 / 234 | 0.78 |

jolt parks cheaper than bb and typing CPU is at parity (~0.75 ticks/key ≈
7.5 ms CPU/key). These absolutes are ~5x §12.3's 41 net ticks — that pass
predates the task rename and this tree, so compare methods and hosts, not the
drift.

**Turn-time idle**: while a turn runs, `status.clj`'s anim timer requests a
frame every **80 ms** (12.5 fps) for the spinner. The live session hosting
this sweep burned **~21 % of a core** while a turn waited on a tool —
1.7 ticks/frame ≈ 17 ms/frame, consistent with the typing/calm frames above.
A no-change frame is not free (whole-tree re-walk plus the document-wide
cursor/normalize/diff scans), and it is paid continuously during a turn.
The 80 ms beat also oversamples the spinner's 100 ms frame bucket
(`spinner/default-interval-ms`): 12.5 requests/s for 10 distinct frames,
and because the beats drift against the bucket one frame every 400 ms is
held 160 ms. §13.4 samples at the bucket period instead.

### 13.3 Applied: cursor-marker memo

§13.1's cursor cost is now fixed. `extract-cursor-position` keeps the
previous frame's raw lines, stripped lines and `{line-index marker-index}`
map and reuses them for lines `identical?` to their previous entry — the
`normalize-reusing` pattern. Unchanged lines are neither scanned nor
reallocated, so the marker strip also stops defeating the normalize memo
when the focused editor's marker is present. The hosts want opposite walks
(§5.4): jolt always runs the reuse loop; bb/sci's plain scan is cheaper, so
bb short-circuits a frame with no marker when the previous frame was
unmarked too, and otherwise shares the loop.

Per-frame medians on the §13.1 fixture (same tree, before → after):

| scenario | bb | jolt |
|---|---|---|
| cursor, typing | 2.5 → 2.5 ms | 9.7 → **2.8 ms** |
| typing total | 14.2 → 15.0 ms | 20.3 → **12.5 ms** |
| stream total | 19.4 → 19.4 ms | 30.4 → **22.2 ms** |
| calm (unstable) | 33.1 → 25.4 ms | 41.1 → **22.9 ms** |

bb is unchanged within the phone's run-to-run noise (its scan was already
~2.5-3 ms; the shortcut keeps the old cost when unfocused and the loop's
identity preservation when marked). On the live app (focused editor,
`scripts/bench/perf_typing.sh`, 300 keys @ 20 ms): **bb ~225 → ~191 net ticks
(−15 %), jolt 234 → ~138 (−41 %)**. In isolation on 177-char lines, the
marked-frame cursor phase is bb 5.2 (old) → 6.0 (loop, with the identity win
landing in normalize instead) and jolt 17.3 → 2.8. Tests:
`test-extract-cursor-position-reuses-unchanged-lines` plus the existing
strip tests, green on both hosts.

### 13.4 Applied: animation pacing

Both turn-time frame drivers (`start-anim-timer!` and
`start-indicator-driver!`, status.clj) now sleep `spinner/default-interval-ms`
instead of a hardcoded 80 ms, matching the 100 ms bucket the braille frame
and the transient indicators' elapsed frame derive from. Each wake is a
distinct frame: even 100 ms on-screen durations and **20 % fewer frames**
during a turn (12 vs 15 per 1.25 s at the driver, measured on bb and jolt),
each of which would otherwise pay a §13.1 typing/calm frame.

(Note — later changes on top of this: the bucket default moved to 240 ms and
the braille set was decimated to every other frame of the 10-frame pinwheel
(5 × 240 ms = the same 1.2 s revolution, **half the repaints**); the Bash
tool's progress spinner dropped its own 80 ms pi beat for the same var; and
all bucket-paced drivers are now **quiet-gated**
(`kmet.tui.timers/frame-due?` against the process-global `mark-frame!`
clock): a frame painted by streaming output, a keystroke or the elapsed
ticker within the last half bucket already carries the current animation
frame, so a due wake schedules nothing. During streaming the drivers now add
~0 frames; on a quiet screen they request at the bucket. The counts above
are the 100 ms measurement.)

### 13.5 Applied: one document pass

`extract-cursor-position` and `normalize-reusing` were two identity walks
over the same 14.9k lines, and `main-diff` then walked them a third time to
find the changed range. `build-frame-lines` now does all three in one pass:
strip the marker, normalize, reuse the previous output for lines
`identical?` to their previous raw entry, and return the changed range.
`main-diff` consumes that range instead of re-scanning (refining its edges
with the diff's own `=` compare, so a line rebuilt to the same text — the
editor's border string — still paints nothing) and falls back to its own
walk when flash compositing changed the lines, since the range describes
the pre-flash document.

Per-frame medians on the same fixture (pre-fusion = the §13.3 state, after
the cursor memo):

| scenario | bb pre | bb fused | jolt pre | jolt fused |
|---|---|---|---|---|
| typing | 14.2 ms | **9.5 ms** | 12.5 ms | **6.2 ms** |
| stream | 19.4 ms | **14.8 ms** | 22.2 ms | **15.7 ms** |
| calm (unstable) | 25.4 ms | 27.6 ms | 22.9 ms | **11.9 ms** |

Cold and redraw are render-stack-bound and unchanged within the phone's
variance (bb cold ~3.0-3.1 s, redraw ~3.2-3.3 s; jolt cold ~4.5-5.8 s,
redraw ~4.6-5.0 s across the three states); the fused pass's one full walk
is invisible beside them.

The typing frame's one changed line now costs one walk: the split
cursor+normalize phases (7.5 ms bb / 8.2 jolt) become a single 6.2 / 3.0 ms
`lines` phase, and diff+emit collapses to 0.2-0.4 ms (from 4.1 / 1.5) since
the diff no longer scans. Cold and redraw stay render-stack-bound; their one
full walk is slightly more expensive than the old split there (jolt cold:
39 ms of strip+normalize versus 9+28) and invisible beside a 4.5 s render.
bb's calm full-walk frames are the exception — sci's interpreted pass costs
more than the old `some`-based scan — but that scenario is the unstable one
and the common frames (typing, streaming) win. The phone's run-to-run state
swung ±20 % across these sweeps; the table is the clean runs.

### 13.6 Applied: a cheap walk for a changed frame

Two `build-frame-lines` changes, both host-neutral:

- **QUICK? fast path**: after a frame that repainted nothing, a document
  content-equal to the previous raw lines is detected with one host-native
  `=` (~0.5 ms for 14.9k lines) and the whole previous result is reused —
  strip/normalize/marks are pure functions of the line content — with only
  the cursor recomputed for the height. The gate is the diff's own verdict
  (`kitty-frame-lines ::unchanged`), because raw identity never settles:
  the editor re-renders a few fresh-but-equal lines every frame. With the
  pass able to prove "no change", `main-diff` treats a nil range as proof
  and skips its own walk (flash frames pass `::scan`).
- **Chunked prefix**: the shared prefix is compared in 1024-line chunks
  with the same native `=`; an equal chunk is copied whole from the
  previous output and only a differing chunk enters the interpreted
  per-line walk, then the appended tail is walked. A removal still takes
  the whole-range walk.

| scenario | phase | before | after (bb / jolt) |
|---|---|---|---|
| typing | lines | 6.0 / 2.9 ms | 1.9 / 2.2 ms |
| typing | frame | 9.0 / 6.8 ms | 6.2 / 5.4 ms |
| stream | lines | 6.0 / 2.9 ms | 1.4 / 2.4 ms |
| stream | frame | 14.0 / 14.6 ms | 9.0 / 13.8 ms |
| calm | lines | 16.5 / 7.7-16 ms | 1.6 / 1.8 ms |
| calm | frame | 25 / 15-28 ms | 9.7 / 7.5 ms |
| cold | frame | render-stack-bound | 3.3 / 4.8 s (unchanged) |
| redraw | frame | render-stack-bound | 3.4 / 5.0 s (unchanged) |

The bench now collects garbage before opening each scenario window (the
calm frames right after the 3 s cold render measured 2-3x slower than the
same frames later in the run), and its `lines` phase is the one fused
pass.

### 13.7 Next steps

- Incremental markdown (§6.3) still stands for cold and streaming: cold is
  ~98 % render-stack and the stream frame's render-stack grows with the
  message (measured: per-append render 3 -> 16 ms on bb, 4 -> 28 ms on jolt
  as the streamed text grows 3 -> 96 chunks).
- Redraw/global reflow still re-pays the full render (~3-5 s on 2.6 MB);
  nothing in this pass changes §10.6's framing of it.

---

## 14. reakt layer (2026-10-10, phone, `jolt v0.8.20-13-gb0b3355b`, bb 1.13.225)

The reactive engine under the TUI (`kmet.reakt.core` + `kmet.reakt.weak`) had
no profile yet. Reproduce with:

```sh
bb   scripts/bench/kmet_reakt_bench.clj     # full sweep
jolt scripts/bench/kmet_reakt_bench.clj
```

Steady-state per-op medians (§6.2b: 2 warming rounds, median of 5), same
phone as §13; read same-run host ratios, not absolutes. The proposed-shape
numbers are scratch replicas (kept in `target/` during the pass) of the
replacement code, measured the same way.

### 14.1 Where the layer sits in the app

- `tracked-deref` runs for every tracked read on a `track!` cache miss
  (`kmet.tui.macros` rewrites `@atom` to it) and inside every reaction body.
- Every `ComponentFn` owns a reaction (`hiccup.clj:971`); its deps are every
  ref its body tracked. `:rerun-without-deps? true` bodies (no tracked refs
  beyond the implicit props/ctree) re-run on **every** deref — every frame,
  per fn component.
- A write to an atom with N reaction watchers runs N dep-handlers; each
  enqueues in O(queue) — an invalidation storm is O(N²) before any body runs.
- The frame bench cannot see any of this: its harness mounts chat-history +
  editor only and a replayed transcript renders through record components; a
  call-counted probe on the baseline tree reported zero `ComponentFn` render
  calls in the cold/typing/stream frames (the same blindness the §13.x
  hiccup-layer change hit).

### 14.2 Primitives (µs, bb / jolt)

| op | bb | jolt |
|---|---:|---:|
| `instance?` String | 0.162 | 0.042 |
| `instance?` Object | 0.162 | 0.140 |
| `instance?` clojure.lang.Atom | 0.162 | **1.060** |
| `instance?` clojure.lang.IRef | 0.162 | **1.838** |
| `instance?` clojure.lang.IDeref | 0.490 | **1.815** |
| `satisfies? RXRef` (atom) | 1.071 | 0.458 |
| `satisfies? RXRef` (reaction) | 0.218 | 0.101 |
| `trackable-ref?` (atom / reaction) | 0.225 / 0.336 | 1.798 / 0.321 |
| `reaction?` / `-cell` (reaction) | 0.274 / 0.600 | 0.125 / 0.098 |
| `tracked-deref` atom, no scope | 0.659 | 1.967 |
| `tracked-deref` atom, scope bound | 1.362 | **4.061** |
| `tracked-deref` reaction, scope bound | 3.407 | 2.071 |
| `deref` idle reaction | 1.942 | 0.642 |
| `flush!` empty queue | 0.266 | 0.058 |
| `force-run!` 0-dep body | 12.807 | 3.483 |
| `deref` `:rerun-without-deps?` body | 14.018 | 4.188 |
| `make-reaction` (never run) | 20.347 | 16.637 |
| `weak/register!` refresh / `sweep!` empty | 2.398 / 0.623 | 1.723 / 0.517 |
| `weak/subject` | 0.578 | 0.562 |

Reading:

- **jolt's `instance?` against the modeled class hierarchy is 1.1–1.8 µs**
  (`Atom` 1.06, `IRef` 1.84, `IDeref` 1.82) against 0.04 µs for a native
  class (`String`): the class graph is walked as string comparisons per
  call (`host/chez/java/records-interop.ss`, `instance-check-base` /
  `case-string`; filed as
  [jolt#1299](https://github.com/jolt-lang/jolt/issues/1299), fixed in Jolt
  `v0.8.21`/[PR #1302](https://github.com/jolt-lang/jolt/pull/1302) — the
  modeled-class checks now measure 0.06–0.22 µs; re-measured in §16.1). bb's SCI
  checks are flat ~0.16 µs. This lands twice per
  tracked read inside a scope, so `tracked-deref` on a plain atom is
  1.36 µs bb but **4.06 µs jolt** — and jolt's atom path is the slow one
  because `trackable-ref?` checks `IRef` first (a reaction's protocol check
  is cheap: 2.07 µs).
- `-cell` is a protocol dispatch (0.60 bb / 0.10 jolt) paid per rx entry in
  every `track!` cache hit check and per flush entry.
- `tracked-deref` on a plain atom costs ~4x a raw deref on bb and ~65x on
  jolt (0.03 µs deref + the classification).

These are the as-found numbers; §14.4 records what the applied fixes
changed.

### 14.3 Scaling

`force-run!` (a full reaction re-run; the body is a trivial sum) vs dep
count — superlinear, because `update-watching!` diffs the watch set with
O(n·m) identity scans:

| deps | bb | jolt |
|---:|---:|---:|
| 1 | 20.6 | 15.0 |
| 5 | 34.9 | 39.8 |
| 20 | 129.8 | 204.0 |
| 50 | 523.5 | 781.8 |

The scratch replica of the current diff vs a same-order identity fast path
(`target/refdiff-bench.clj`):

| deps | current diff bb / jolt | fast path bb / jolt |
|---:|---:|---:|
| 1 | 1.20 / 0.70 | 0.81 / 0.07 |
| 5 | 3.47 / 1.63 | 1.59 / 0.21 |
| 20 | 24.59 / 7.06 | 4.45 / 0.67 |
| 50 | 137.0 / 29.1 | 10.7 / 2.6 |

Invalidation storms (N reactions watching N atoms, dirty then settle, one
cycle per measurement):

| cycle | bb | jolt |
|---|---:|---:|
| dirty 1 watched atom + settle | 37.1 | 26.3 |
| dirty 50 + settle | 1467 | 1021 |
| dirty 500 + settle | 26081 | 12506 |
| `some identical?` over a 500 vector | 44.8 | 37.0 |

### 14.4 Applied fixes

The six candidates below are applied (commits `6b286af7`, `72a63d3b`,
`b5b82a56`, `29fc2ecf`, `23a4af82`, `c7066d14`); the §14.2/§14.3 tables
are the as-found profile, and the after numbers here are interleaved
same-machine A/Bs against the previous commit (`target/watching-ab.clj`,
`target/storm-ab.clj`, `target/tracked-ab.clj`, `target/hit-ab.clj`,
`target/miss-ab.clj`, `target/missbuckets-ab.clj`).

1. **`update-watching!`'s set-diff was O(deps²) per run.** Applied: a
   same-order identity fast path, which also skips the no-op `:watching`
   cell write and the registry write. `force-run!` over N deps, bb
   20 deps 126 → 44 µs and 50 deps 514 → 95 µs; jolt 20 deps ~205 → 178
   and 50 deps ~800 → 695 (jolt's row is dominated by per-dep
   `tracked-deref`; its diff was only ~7 µs at n=20).
2. **`enqueue!`'s dedup was O(queue) per enqueue.** Applied: a `:queued?`
   flag in the cell, set atomically with `:dirty` and cleared when the
   run's final cell write lands, when a flush skips a settled entry, and
   on the failure/dispose paths. One write with 500 watchers + settle:
   bb 23.5 → 11.6 ms, jolt ~12.2 → ~9.5 ms; dirty-500-atoms cycles bb
   22.2 → 11.2 ms. `test-failed-reaction-requeues-on-the-next-dep-change`
   pins the failure-path clear (fails without it).
3. **`tracked-deref` classified twice whenever a tracking scope was
   bound.** Applied: one classification shared by the scope write and
   `record-dep!`. Jolt: tracked-deref on a plain atom in a scope 4.1-4.9 →
   2.3 µs, reaction 2.1 → 1.8; bb 1.36 → 1.15 and 3.4 → 3.1;
   reaction-frame-only reads unchanged. The jolt RXRef-first reorder
   originally floated here was dropped: `or` evaluates both checks on a
   miss, so IRef-first already wins for the common atom (measured 1.8 vs
   2.5 µs RXRef-first).
4. **`rx-unchanged?` read each hit's rx cell through a `-cell` protocol
   dispatch** (`29fc2ecf`), once per reactive entry per cache hit, even
   though the cache is keyed by the cell — the key is read directly now.
   Cache hits: bb 5 rx entries 7.7-8.4 → 4.4 µs and 20 entries 28 → 15;
   jolt ~1.7 → ~1.0 and ~5.9 → ~2.9; mixed 5 atoms + 5 rx bb 8.6 → 5.3.
5. **The miss path classified every tracked ref with `satisfies?`**
   (`23a4af82`): `reakt/plain-ref?` is the complement over refs that
   already cleared `trackable-ref?`, host-ordered so the common atom
   short-circuits on the cheaper check (bb `instance?` 0.16 µs vs
   `satisfies?` 1.07; jolt the reverse). The order comes from a load-time
   `host/jolt?` flag — `.clj` source cannot carry reader conditionals.
   Forced-miss renders: bb 5 atoms 43 → 37 µs and 20 atoms 130 → 109
   (-12%/-16%); jolt unchanged within its noise.
6. **`track-render`'s miss path classified every tracked ref a second
   time** (`c7066d14`, the §14.6 candidate — with its premise corrected:
   the applied `plain-ref?` pass was ~7.1 µs per 10 refs on bb, not the
   ~18 µs/10 of the rejected `reaction?`-based variant). Applied:
   `*tracking-scope*` is now the two capture buckets `[atoms rx]`;
   `tracked-deref` — which already classifies via `ref-kind` to gate
   `record-dep!` — files each read into its bucket, `track-render` reads
   them straight into the cache, and only the rx bucket is transformed
   once per body run (ref → cell key for the cell-keyed hit check), empty
   for atom-only bodies. `plain-ref?` and its `host/jolt?` flag are gone.
   Forced-miss renders, bb 20 atoms 108.1 → 100.2 µs (-7.3%), 10 atoms +
   10 reactions 152.9 → 145.3 (-5.0%), 20 reactions 182.8 → 179.0
   (-2.1%), 5-ref bodies ±1%; jolt 20 atoms 608 → 483 (-21%), mixed 593 →
   458 (-23%), 20 reactions inside the round spread (baseline 520-790,
   after 495-599). A scope-bound deref pays ~+0.3 µs bb for the `case`
   and bucket assoc — repaid per ref by the pass the body no longer runs
   at its end.

### 14.5 Checked, not applied

- **The idle deref's second cell read.** Making the re-read conditional on
  `flush!` having drained (and returning a drained flag) measured neutral:
  out-of-frame 2.09 → 2.09 µs bb / 0.65 → 0.64 jolt, in-frame 2.55 →
  2.55 µs bb (jolt 0.76 → 0.74). The extra `@cell` is ~0.03 µs bb / 0.06
  jolt — the idle read's cost is the destructure, the cond and
  `record-dep!`, not the re-read, so the contract change was reverted.
- **`run-sync!`'s per-run floor** is now the two state swaps, the pending
  holder, and the `*reaction-frame*` binding; item 1 removed the third
  (no-op watching) write. `pending` as `(atom nil)` so a no-dep body
  skips the map allocation measured neutral (0-dep `force-run!` 10.2 →
  10.2 µs) and was not kept.
- **The non-empty `flush!` machinery** is 4-10 µs/call (skeleton 4.0 bb /
  1.1 jolt), once per frame; low priority — the empty-queue path (the
  common one) already short-circuits before the CAS.
- **`make-reaction` costs ~20 µs to construct** — relevant at
  replay/cold-render scale, hard to shrink.

---

## 15. SSE streaming CPU (2026-10-10, phone, `jolt v0.8.20-15-g1c6da859`, bb 1.13.225)

The "Jolt burns a core while streaming" report was kmet's own reader, not the
host: `kmet.libs.sse/make-idle-reader` handed the stream across a
`LinkedBlockingQueue` **one byte at a time**, so a 1 KB/s response paid ~1000
park/wake cycles per second. A parked handoff measures ~0.045 ticks/item on
jolt and ~0.035 on bb (1000 items 3 ms apart, `LinkedBlockingQueue` +
`Thread/sleep`) — so the per-byte design cost ~40 % of a core at 1 KB/s on
*both* hosts. The reader now reads up to 8 KiB per handoff
(`make-char-idle-reader` / `make-byte-idle-reader`), one queue wake per read
burst; the single-unit `make-idle-reader` is kept as the wrapper. The same
change fixed `process-bedrock-stream`'s idle arm, which read the
`[read stop thread]` vector as a `{:read …}` map and NPE'd whenever a bedrock
idle timeout was set.

Repro: a local SSE server (300 events 30 ms apart, 200 B each) against the
TUI in a tmux pty and against a no-TUI `llm/send-message`; process ticks from
`/proc/<pid>/stat` fields 14+15 sampled across the stream window (the §1
recipe). No settlement change: the TUI needs its usual ~30 s startup settle.

| scenario (ticks/s in the stream window) | before | after |
|---|---:|---:|
| TUI `jolt start` | 86.7 | **23.8** |
| TUI `bb start` | — | 60.9 |
| no-TUI `llm/send-message` (jolt) | 31.9 | **10.7** |
| no-TUI (bb) | 19.3 | 15.4 |

Not a regression: the 2026-10-09 tip and HEAD both measured 76–87 ticks/s in
the TUI before the fix, and the per-byte reader dates to 2026-08. After the
fix jolt is **2.6x cheaper than bb** on the same TUI stream; what remains is
~10 ticks/s of SSE (HTTP body framing roughly half, decode/parse the rest)
plus the TUI's whole-message Markdown re-parse per frame (§3.5). Layer
checks on the same 300-event stream: a direct body `.read` drain costs jolt
48 ticks vs bb 32; draining chars through the chunked reader costs jolt 33
vs bb 53; a fast stream (3000 events ≈ 700 KB, no gaps) costs jolt
167 ticks/1.56 s vs bb 90/0.90 s. No host is the outlier on the full path, so
the remaining work is kmet-side (§6.3). The queue finding recorded in
`jolt-bugs.md` as
[jolt#1303](https://github.com/jolt-lang/jolt/issues/1303) (a timed `poll`
on a non-empty `LinkedBlockingQueue` cost 4.9x the untimed poll) is fixed
in Jolt `v0.8.21` ([PR #1305](https://github.com/jolt-lang/jolt/pull/1305));
the reader's untimed-poll-first accommodation is gone — cost-neutral
(§16.3/§16.4).

---

## 16. Re-check at `jolt v0.8.21` after #1299/#1303 (phone, 2026-10-11)

v0.8.21 closed the two perf tickets §14/§15 filed upstream —
[#1299](https://github.com/jolt-lang/jolt/issues/1299) (`instance?` against
the modeled class hierarchy, [PR #1302](https://github.com/jolt-lang/jolt/pull/1302))
and [#1303](https://github.com/jolt-lang/jolt/issues/1303) (a timed `poll`
on a non-empty `LinkedBlockingQueue`, [PR #1305](https://github.com/jolt-lang/jolt/pull/1305))
— and kmet dropped the companion work (aedfae90: `kmet.libs.sse`'s
untimed-poll-first, the reakt comments' stale cost claims). This section
re-runs the affected harnesses on the same Termux/aarch64 phone (`jolt
v0.8.21`, `bb` 1.13.225) and records what moved. Method and caveats are
§1/§6.2b; reakt rows are medians of 3 runs (each script's own median of 5),
and absolutes are phone-specific like every phone table here.

Caveat: a live `jolt start` app ran in another terminal for the whole
session (~25 % of a core). That is exactly the §6.2b "never measure while
something burns CPU" condition; the numbers reproduced §13/§14/§15 within
the phone's usual noise, so it is recorded rather than corrected for.

### 16.1 reakt primitives — §14.2's jolt deficit is gone

Steady-state medians (µs; J/B = jolt/bb) with §14.2's as-found jolt values
alongside:

| op | bb | jolt | J/B | §14.2 jolt |
|---|---:|---:|---:|---:|
| `instance?` String / Object | 0.161 / 0.162 | 0.043 / 0.173 | 0.27 / 1.07 | 0.042 / 0.140 |
| `instance?` Atom | 0.160 | **0.059** | 0.37 | 1.060 |
| `instance?` IRef | 0.163 | 0.205 | 1.26 | 1.838 |
| `instance?` IDeref | 0.485 | **0.266** | 0.55 | 1.815 |
| `trackable-ref?` atom / reaction | 0.307 / 0.412 | 0.315 / 0.420 | ~1.0 / ~1.0 | 1.798 / 0.321 |
| `tracked-deref` atom, no scope | 0.700 | **0.461** | 0.66 | 1.967 |
| `tracked-deref` atom, scope bound | 1.434 | **0.741** | 0.52 | 4.061 |
| `tracked-deref` reaction, scope bound | 3.513 | **1.654** | 0.47 | 2.071 |
| `deref` idle reaction | 2.043 | **0.794** | 0.39 | 0.642 |
| `force-run!` 0-dep body | 11.110 | **3.598** | 0.32 | 3.483 |
| `deref` `:rerun-without-deps?` body | 12.448 | **4.597** | 0.37 | 4.188 |
| `make-reaction` (never run) | 22.453 | 21.141 | 0.94 | 16.637 |
| `force-run!` 1 / 5 deps | 14.6 / 21.1 | **8.2** / 24.4 | 0.56 / 1.16 | 15.0 / 39.8 |
| `force-run!` 20 / 50 deps | 47.6 / 103.0 | 130.2 / 567.8 | **2.74 / 5.51** | 204.0 / 781.8 |
| dirty 1 / 50 / 500 + settle | 31.8 / 1193.8 / 12723.5 | **13.2 / 438.7 / 4508.1** | 0.42 / 0.37 / 0.35 | 26.3 / 1021 / 12506 |
| `some identical?` over 500 | 44.7 | 37.6 | 0.84 | 37.0 |
| `weak/register!` refresh | 2.388 | **0.875** | 0.37 | 1.723 |
| `weak/sweep!` empty | 0.636 | **0.102** | 0.16 | 0.517 |
| `weak/subject` | 0.581 | **0.163** | 0.28 | 0.562 |

Reading:

- Every modeled-class `instance?` dropped to a native-check cost (Atom
  1.06 → 0.059, IRef 1.84 → 0.205, IDeref 1.82 → 0.266 µs); only `IRef`
  still costs jolt a little more than bb (0.205 vs 0.163).
- `tracked-deref` is jolt-favored in every shape, so §14.2's "jolt's atom
  path is the slow one" is dead: no scope 0.46 vs 0.70, scope-bound atom
  0.74 vs 1.43 (was 4.06), scope-bound reaction 1.65 vs 3.51.
  `trackable-ref?` on an atom is parity (0.315 vs 0.307; was 1.80).
- The reaction lifecycle flipped with it: idle deref 0.79 vs 2.04,
  0-dep force-run 3.6 vs 11.1, `:rerun-without-deps?` 4.6 vs 12.4;
  `make-reaction` is near parity (~21 vs ~22). Invalidation storms are
  2.7–2.8x *cheaper* on jolt now (50 watched atoms 439 vs 1,194 µs; 500
  atoms 4.51 vs 12.72 ms).
- The one surviving deficit is the many-dep `force-run!` (2.7x at 20 deps,
  5.5x at 50) and it is genuinely superlinear (jolt 8 → 24 → 130 → 568 µs
  for 1 → 5 → 20 → 50 deps). §14.4 item 1's identity fast path is in place;
  §16.2 traces the cost below reakt.

### 16.2 The many-dep residual is Jolt's constant object `hash`

A focused probe (`target/perf-recheck/force-run-probe.clj`, 50 deps): on
jolt `r/-force-run` costs **604 µs**, the body called directly (no reaction
frame) **20 µs**, and the body *in a frame* — i.e. with `record-dep!`
capturing each read — **582 µs** (bb: 90 / 31 / 73). `record-dep!` is a
`(swap! pending assoc ref v)` per dep, so the pending map is keyed by refs,
and building a 50-entry map keyed by atoms is where the time goes:

| 50 chained assocs | bb | jolt |
|---|---:|---:|
| atom keys | 20.2 µs | **495.8 µs** |
| keyword keys | 20.6 µs | 19.1 µs |

The cause is below Clojure: on jolt, `(hash x)` for a modeled object or a
`reify` (Atom, Delay, Volatile, `Object`, `StringBuilder`, reactions —
which `make-reaction` builds with `reify`) returns the **same constant for
every instance**; 50 distinct atoms hash to 1 distinct value (bb: 50). The
map degrades to a single collision bucket scanned per assoc — O(n²) in the
dep count. `System/identityHashCode` does return distinct per-object values,
so the host can fix it; records and deftypes already hash correctly. This is
not reakt-specific: any jolt hash map keyed by refs/objects (kmet's
tracking-scope buckets, third-party caches) pays it. **Filed upstream as
[jolt#1313](https://github.com/jolt-lang/jolt/issues/1313)**: "`hash` of
modeled/reify objects is a fixed constant, making maps keyed by them
O(n²)".

### 16.3 #1303: the timed poll costs the untimed one

`target/bench_poll.clj` (scratch, from the §15 investigation), 200k dequeues
from a pre-filled `LinkedBlockingQueue`:

| | `poll()` | `poll(100 ms)` | `take()` |
|---|---:|---:|---:|
| jolt v0.8.21 | 61 ms | **62 ms** | 53 ms |
| bb 1.13.225 | 82 ms | 115 ms | 79 ms |

The timed poll on a non-empty queue was 4.9x the untimed one when §15 filed
#1303; it now costs the same. The reader's untimed-first accommodation is
gone (aedfae90) and the parked handoff (`target/bench_handoff.clj`, 1000
items 3 ms apart, timed poll) is unchanged: jolt 0.043 ticks/item (was
~0.045), bb 0.033 (was ~0.035). Removal measured cost-neutral.

### 16.4 SSE streaming — §15's table holds

Same probe server and TUI tmux harness as §15 (300 events, 30 ms apart,
200 B; ticks/s in the stream window):

| scenario | pre-fix | §15 after | v0.8.21 |
|---|---:|---:|---:|
| TUI `jolt start` | 86.7 | 23.8 | **25.0** |
| TUI `bb start` | — | 60.9 | **63.3** |
| no-TUI jolt | 31.9 | 10.7 | **10.3** |
| no-TUI bb | 19.3 | 15.4 | **12.3** |

jolt stays ~2.5x cheaper than bb on the TUI stream (63.3 / 25.0), idle is
2.4 (jolt) vs 4.0 (bb) ticks/s, and the remaining work is kmet-side (§6.3). Harness
note: the scratch probe agent's `:http-idle-timeout-ms 1500` (added after
§15 for the headers-timeout work) is below jolt's first-chunk latency in
this environment — a no-TUI series with 1500/2000/3000/5000 ms reproduces
it (jolt errors before the first chunk at 1500, completes at 2000+; bb
completes at 1500) — so the runs above used 120000 like §15. That latency
is a separate, non-CPU observation about the jolt HTTP path, worth its own
look.

### 16.5 Frame and typing CPU — unregressed

Frame bench (§13's harness and fixture, 100x30, one run per host — §13
reports medians of 3–5; the frame path is untouched by the two fixes and the
bench mounts no fn components (§14.1), so this is a sanity check):

| scenario | bb | jolt | jolt/bb |
|---|---:|---:|---:|
| cold (first frame) | 3.11 s | 3.97 s | 1.28 |
| typing (editor tick) | 4.4 ms | 7.6 ms | 1.74 |
| stream (append/frame) | 8.9 ms | 12.5 ms | 1.40 |
| calm (no change) | 5.0 ms | 9.6 ms | 1.93 |
| redraw (resize -> clearing) | 3.36 s | 4.15 s | 1.24 |

All inside §13.5/§13.6's post-fix range. Typing CPU (`perf_typing.sh`, 300
keys @ 20 ms, fresh session, 30/40 s settle; net ticks): bb 196 / 214 / 188,
jolt 167 / 189 / 190 / 180 / 173 — medians **196 / 180**, parity. bb matches
§13.3; jolt's spread against §13.3's ~138 is the phone's run-to-run state
under the background app's load, not a regression — neither fix adds per-key
work (the reader accommodation removal measured neutral, §16.3).

### 16.6 What this changes in the file

- §14.2's as-found table stands as history; §16.1 supersedes its reading:
  jolt is ahead on every reakt row except multi-dep `force-run!` (and `IRef`
  `instance?` by 0.04 µs).
- The §14.4 applied fixes remain the end state; nothing there needs
  revisiting now that `instance?` is cheap. The dropped RXRef-first reorder
  stays dropped (IRef-first is still the atom's short-circuit), and the
  §14.4 item 1 identity fast path now has a new bottleneck below it (§16.2).
- §15's table holds and its accommodation removal is confirmed neutral;
  "remaining work is §6.3" still stands.
- The §16.2 finding is filed upstream as
  [jolt#1313](https://github.com/jolt-lang/jolt/issues/1313) (constant object
  `hash`); drop this note's caveat once a tagged Jolt release carries the fix.

---

## 17. Re-check at `jolt v0.8.21-61-g4d665200` after #1313/#1316 (x86_64, 2026-10-12)

The installed toolchain is now a dev build **61 commits past the v0.8.21
tag** (`bb` 1.13.226, x86_64/14 cores), carrying #1314/#1313 (identity hash
for modeled/reify objects), #1320/#1316 (io opener opts), and the
condition-variable perf PRs #1315/#1317 plus #1318 (socket bulk write) that
landed after §16. No tag carries them yet. This re-runs §16's harnesses on
this box; §16 was the phone, so read same-run host ratios and movement
against §16's ratios, not absolutes across sections (§12's x86 table is one
toolchain generation older). Caveat, as in §16: a live `jolt start` ran in
another terminal at ~20 % of a core for the whole session — the recorded
§6.2b condition, not corrected for; the numbers landed inside the expected
bands.

Harnesses: `scripts/bench/kmet_reakt_bench.clj`, `scripts/bench/kmet_perf_bench.clj`,
`scripts/bench/kmet_frame_bench.clj` (`19fdb60dc61-b305.ednl`, 9,067 rendered
lines), `scripts/bench/perf_typing.sh`, and scratch probes under
`target/perf-check-17/` (map-key, scaling, queue, handoff, io).

### 17.1 reakt layer — the many-dep deficit is gone

`scripts/bench/kmet_reakt_bench.clj`, both hosts, medians of 5 rounds (µs; J/B =
jolt/bb; §16.1's phone ratio alongside):

| op | bb | jolt | J/B | §16.1 J/B |
|---|---:|---:|---:|---:|
| `tracked-deref` atom, scope bound | 0.530 | **0.177** | 0.33 | 0.52 |
| `tracked-deref` reaction, scope bound | 1.250 | **0.454** | 0.36 | 0.47 |
| `deref` idle reaction | 0.708 | **0.233** | 0.33 | 0.39 |
| `force-run!` 0-dep body | 3.244 | **1.058** | 0.33 | 0.32 |
| `force-run!` 1 / 5 deps | 3.9 / 6.2 | **1.6** / **4.2** | 0.42 / 0.68 | 0.56 / 1.16 |
| `force-run!` 20 / 50 deps | 15.7 / 32.4 | 23.2 / 53.7 | **1.48 / 1.66** | 2.74 / 5.51 |
| dirty 1 / 50 / 500 + settle | 9.6 / 395 / 4433 | **2.4 / 111 / 1177** | 0.25 / 0.28 / 0.27 | 0.42 / 0.37 / 0.35 |
| `some identical?` over 500 | 17.1 | 16.2 | 0.95 | 0.84 |

Every reakt row is at parity or jolt-favored except the many-dep
`force-run!`, and that moved from superlinear — the phone went 8 → 24 → 130
→ 568 µs for 1 → 5 → 20 → 50 deps, 2.7x/5.5x bb — to **linear** (1.6 → 4.2
→ 23.2 → 53.7 µs, 1.2–1.7x bb out to 200 deps, §17.2). The invalidation
storms stay 2.7–4x cheaper on jolt. §16.1's "one surviving reakt deficit"
is closed, and §16.2's caveat can be dropped when a tag carries #1313.

### 17.2 #1313: identity hash, and the flat cost left below it

`hash` is distinct per instance on the dev build (50 of 50 atoms, both
`reify` objects; §16.2 measured one constant for every instance). §16.2's
probe, re-run both hosts (µs):

| probe | bb | jolt | §16.2 jolt (phone) |
|---|---:|---:|---:|
| 50 chained assocs, atom keys | 5.3 | **42.1** | 495.8 |
| 50 chained assocs, keyword keys | 6.0 | 8.7 | 19.1 |
| full `force-run!` 50 deps | 35.7 | 56.0 | 604 |
| body direct (no frame) | 11.0 | 7.0 | 20 |
| body in a capture frame | 28.0 | 49.2 | 582 |

The O(n²) is gone: assoc with atom keys is linear in n — per-entry 1.02 /
0.82 / 0.74 / 0.76 / 0.65 µs at 10 / 50 / 100 / 200 / 400 keys against
bb's flat ~0.13 — and `force-run!` grows ~0.75 µs/dep (jolt 13 / 28 / 39 /
78 / 149 µs at 10 / 25 / 50 / 100 / 200 deps vs bb 8 / 16 / 28 / 65 / 103).
What remains is a **flat per-ref-key cost**: a ref-keyed assoc is ~5x a
keyword-keyed one on jolt (bb is parity), and `(hash obj)` costs 0.275 µs
against `identityHashCode`'s 0.044 and a keyword's 0.013 — so the 50-dep
capture is ~42–49 µs of the 56 µs run. That is a constant factor, not a
scaling cliff: many-dep reactions are rare in kmet and now cost 1.7x bb
instead of 5.5x. Recorded as understood; not an action item.

### 17.3 Frame path and typing CPU — unregressed

Frame bench (§13's harness, `19fdb60dc61-b305.ednl`, 100x30, one run per
host; §16.5 used the phone's 2.63 MB fixture, so compare this table's ratios
with §16.5's band, not the absolutes):

| scenario | bb | jolt | J/B | §16.5 J/B |
|---|---:|---:|---:|---:|
| cold (first frame) | 464 ms | 721 ms | 1.55 | 1.28 |
| typing (editor tick) | 1.37 ms | 1.39 ms | 1.02 | 1.74 |
| stream (append/frame) | 2.67 ms | 3.78 ms | 1.42 | 1.40 |
| calm (no change) | 2.30 ms | 1.97 ms | 0.86 | 1.93 |
| redraw (resize -> clearing) | 428 ms | 723 ms | 1.69 | 1.24 |

The frame bench mounts no fn components (§14.1), so it cannot see the hash
fix; this is the sanity check that the 61 commits did not move the frame
path. Cold stays render-stack-bound (jolt 541 ms stack / 169 ms diff+emit vs
bb 402 / 60); typing and calm are at parity, stream stays ~1.4x. Typing CPU
on the live app (`perf_typing.sh`, 300 keys @ 20 ms, fresh session, 30 s bb /
40 s jolt settle, net ticks): bb 38 / 37 / 36, jolt 37 / 32 / 35 — medians
**37 / 35, ~0.95x**, the parity of §12.3/§16.5 held.

### 17.4 Queue drains and the parked handoff — the CV work holds

200k dequeue rounds and a 1000-item, 3 ms-apart parked handoff (§15/§16.3
probes); jolt's timed poll is at or below its untimed one, and the handoff
is 0.008 ticks/item against bb's 0.006 (phone §16.3: 0.043 / 0.033):

| probe | bb | jolt |
|---|---:|---:|
| drain 200k `poll()` | 20.7 ms | **16.4 ms** |
| drain 200k `poll(100ms)` | 30.6 ms | **13.5 ms** |
| drain 200k `take()` | 20.4 ms | **13.5 ms** |
| parked handoff, `take` / timed | 0.006 / 0.006 ticks/item | 0.008 / 0.008 |

No regression from the waiter-table rework (#1315/#1317); jolt's timed poll
no longer separates from the untimed one, the §16.3 conclusion held. The SSE
stream itself (§15/§16.4) was not re-run — its probe server was scratch — so
#1318's socket bulk write is in the build but unmeasured here.

### 17.5 #1316: the io workaround can go at the next floor bump

`(io/writer f :append true)`, `(io/reader f :encoding "UTF-8")` and
`(io/input-stream f)` all work in the installed dev build, and the append
reads back correctly (scratch probe `target/perf-check-17/io-probe.clj`).
kmet's `spit` workaround (`c56e80f7`) stays for now — the fix is on `main`,
the declared floor is the v0.8.21 tag, and the workaround is a handful of
lines. Remove it with the floor.

### 17.6 Primitives — stable against §12.1

`scripts/bench/kmet_perf_bench.clj` (same sweep as §12.1, medians of 5 rounds).
Nothing regressed in shape; the notable movers since §12.1 are all better on
jolt: `ansi-code-at` 1.374 → **0.332 µs** (the §12.5 scanner, kmet-side),
editor 20-line render 169.5 → **75.7** (J/B 2.7 → 1.28) and 500-char 384 →
**265**, markdown render 2 KB 1030 → **860**. The unchanged rows still read
like §11.1: jolt wins widths/strings/parse (`strip-ansi-codes` 0.76 vs
1.93, box-drawing 5.2 vs 38.9), loses seq/allocation (`mapv identity` 2.85
vs 1.04, `mapv inc` 21.5 vs 13.2 ms) and the normalize guard (0.476 vs
0.099 µs), and `str/replace ANSI-CODE-RE` stays jolt's 4.2x loss while the
scanner path is 0.76. `fib 27` 1.8 ms vs bb 31.8.

### 17.7 What this changes in the file

- §16.1's reakt reading is superseded by §17.1: the many-dep `force-run!`
  deficit is fixed on the dev build and needs no kmet change. The flat
  ref-key map cost below it is the residual (§17.2).
- §16.2's caveat (drop the note once a tag carries #1313) is now waiting on
  the tag only; `jolt-bugs.md` carries the re-check.
- §15/§16.3's queue conclusion holds under the CV rework; §17.4 adds the
  x86 reference points.
- The top levers are unchanged: incremental markdown (§6.3, cold and
  streaming), virtualization (§10.6), and the tool renderers' seq/allocation
  work (§11.2/§12.1).

---

## 18. The two remaining jolt outliers, chased (x86_64, 2026-10-12)

§17.6's widest jolt losses were `str/replace ANSI-CODE-RE` styled (7.0 vs
1.7 µs, 4.2x) and the guarded `normalize-terminal-output` (0.476 vs 0.099,
4.8x). This pass finds one is a jolt/irregex defect (filed as
[jolt#1327](https://github.com/jolt-lang/jolt/issues/1327)), the other is not
a bug, and fixes the two kmet call sites that still fed the regex to jolt hot
paths.

### 18.1 The regex outlier is the DFA's leftmost search

`re-find` with a group-free pattern (here `#"\u001b\[[0-9;]*[a-zA-Z]"`), one
match at position k in an otherwise plain string (precomputed; µs, medians;
same machine as §17):

| match at | jolt DFA (group-free) | jolt backtracker (1 capture) | bb |
|---:|---:|---:|---:|
| 0 | 0.28 | 0.20 | 0.13 |
| 100 | 6.09 | 0.66 | 0.58 |
| 200 | 11.75 | 0.93 | 1.01 |
| 400 | 24.21 | 1.62 | 2.08 |

The cost is not the scan: a no-match input of the same length costs 2.0 µs
(bb 2.0), and the same pattern with one capture group — which routes to the
backtracker — matches at 400 in **1.62 µs against the DFA's 24.21** (bb 2.08
for the backtracker). The DFA path in `irregex-search/matches` runs the
search DFA to the earliest possible end and then re-runs the anchored
`dfa-match/longest` at every start position back to the search origin (the
`lp2` loop, `vendor/irregex/irregex.scm`), allocating match memory per
attempt — ~60 ns per position before the match. `re-seq` and `str/replace`
pay it once per match plus once to fail: a styled line's second escape (at
index ~114) makes the 2-match replace 6.8 µs on jolt against bb's 1.7.

**Verdict: worth reporting**, with the caveat that the fix is in the DFA's
leftmost-start recovery — a "prefer the backtracker" change would regress
what the DFA wins (no-match scans are 2.0 vs 6.4, and §9.1's diff tokenizer).
Filed 2026-10-12 as
[jolt#1327](https://github.com/jolt-lang/jolt/issues/1327); the scratch repro
is `target/perf-check-17/prefix-scaling.clj` and `retry-probe.clj`.

### 18.2 `normalize-terminal-output` — the guard is a wash, not a bug

Full-function A/B (µs):

| input | guarded (current) | unguarded, full | bb guarded | bb unguarded |
|---|---:|---:|---:|---:|
| plain 100-char | 0.497 | 0.507 | 0.141 | 0.156 |
| 100-char with tab | 4.932 | 4.566 | 3.593 | 3.727 |

§11.1/§12.1's "the guard no longer pays" compared the guarded form against
*two replaces without the tab check*; adding the tab test back makes the two
forms equal. The 4–5x ratio to bb is jolt's per-call string primitives —
`str/includes?` 0.19 vs 0.05, a no-match literal `str/replace` 0.185 vs 0.064
— the same primitive gap as the rest of the table. Nothing to file, nothing
to change in kmet.

### 18.3 Fixed: two kmet sites fed the raw regex to jolt

Applied (`modules/tui/src/kmet/tui/utils.cljc`):

- **`truncate-to-width` called `match-at ANSI-CODE-RE` at *every index* of a
  styled line** — a fresh matcher per character — instead of the
  ESC-guarded `ansi-code-at` (scanner on jolt). A 300-char styled line
  truncated to 100 columns: **1630 → 93 µs on jolt** (17.5x), **700 → 272 µs
  on bb** (2.6x). This was the widest single-call loss found in the pass.
- **`wrap-single-line` stripped with the raw `str/replace ANSI-CODE-RE`**;
  it now uses the host-branched `strip-ansi` (scanner on jolt, regex
  elsewhere). On jolt: 16.9 → 1.3 µs styled, 5.6 → 0.7 µs plain; bb is
  unchanged (same regex path as before).

Both changes are output-identical mechanisms, pinned by the existing
`kmet.test-utils` corpus tests. Gates: `bb test-changed` 13,358 assertions
and `jolt test-changed` 13,248 green, `bb lint-changed` 0 findings,
`bb format-check-changed` clean. A frame-bench A/B cannot resolve the win —
single runs on this box swing up to 1.5x on identical logic (bb cold 402–696
ms across runs) — so the function-level A/B is the evidence.

Still on the raw regex at §18 time: the wrap tracker (`ansi-process-text!`'s
`re-seq`, `ansi-process-range!`'s `re-find`) — scannerized in §20.

---

## 19. Remaining regex-on-hot-path candidates (x86_64, 2026-10-12)

A sweep of `src/` for regexes on per-frame / per-line / per-chunk paths after
§18, with the candidates measured rather than guessed (scratch probes
`target/perf-check-17/hot-regex-probe.clj` and `sanitize-probe.clj`).

### 19.1 Fixed: the bash/tool output sanitizers

`kmet.agent.bash-executor/sanitize-output` ran a private copy of
`ANSI-CODE-RE` plus two unconditional CR replaces on every decoded bash
chunk (the streaming path, so once per process read);
`kmet.agent.ui.tui.tool-renderers/sanitize-display-text` and
`kmet.agent.ui.tui.bash-execution/bash-execution-append-output!` repeated the CR
passes. The private ANSI regex is gone — the shared host-branched
`kmet.tui.utils/strip-ansi-codes` does the strip (scanner on Jolt) — and the
CR passes are guarded with `str/includes? "\r"`. Real function, 1.8 KB
chunk (µs; medians; output equal on a plain/styled/CRLF/private-CSI/OSC-8/
NUL/tab corpus):

| input | old jolt | new jolt | old bb | new bb |
|---|---:|---:|---:|---:|
| plain LF | 75.8 | **39.6** | 86.7 | **56.9** |
| ANSI-styled | 164.3 | **46.4** | 84.6 | **57.6** |
| CRLF | 92.0 | **53.4** | 79.7 | 86.2 |

CRLF data pays the extra `includes?` scan on bb (~8%) and everything else
wins on both hosts; LF-only chunks are the common case. The control-class
replace (`[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]`, ~30 ns/char on both
hosts) stays: a `re-find` guard scans the same bytes, so it does not pay.

### 19.2 Measured and left alone

| candidate | measurement | verdict |
|---|---|---|
| markdown `split-table-row` lookbehind `(?<!\\)\|` | jolt 4.5 µs, bb 4.6 (parity) | no — the editor's lookbehind pathology does not apply here; a hand scan is 8.2 µs on jolt |
| `str/split #"\n" -1` (40 lines) | jolt 6.5 µs, bb 13.9 | no — a `nth`-loop scanner is 56/108 µs |
| diff-line parse `^([ +-])(\s*\d*)\s(.*)$` | jolt 0.96 vs hand 0.42; bb 0.52 vs 1.58 | no — host-split, cold path, ~1 ms per 2000-line diff |
| combined CR+control single pass | jolt 37.6 vs 44.2; bb 60.9 vs 45.9 | no — jolt-only win, loses on bb once the CR guard is in |
| `plain-char-width` `re-seq` scans | jolt already 7x faster than bb on box lines (§17.6) | no |
| fuzzy `word-boundary?` 1-char `re-find` | per matched query char | left — the §5.3 tiny-re-find class |

### 19.3 The wrap tracker (fixed in §20)

The wrap tracker and `split-long-word`'s walker moved onto the scanners in
§20. Still on the regex on jolt, small and only when a sequence is present:
`apply-sgr-code`'s anchored `re-find` and `active-osc-8-close`'s `re-seq
osc-8-link-re`.

---

## 20. The wrap tracker's ANSI scan (x86_64, 2026-10-12)

§19.3's remaining candidate. `ansi-process-text!` / `ansi-process-range!`
fed the tracker from `re-seq` / `re-find ANSI-CODE-RE`, and
`split-long-word`'s walker re-found codes the same way. All three now walk
the hand-rolled scanners on both hosts (`ansi-codes-scan` /
`ansi-codes-scan-range` over `ansi-sequence-end`; the walker uses
`ansi-code-at`), so no regex runs on the wrap path.

Function-level A/B (µs, medians; probe `target/perf-check-17/
wrap-tracker-probe.clj`, after-probe `wrap-tracker-after.clj`):

| scan | jolt regex | jolt scanner | bb regex | bb scanner |
|---|---:|---:|---:|---:|
| `ansi-process-text!`, 800-char styled line | 40.5 | **4.2** | 15.8 | **6.8** |
| `ansi-process-text!`, OSC 8 hyperlink | 4.1 | **2.1** | **2.2** | 2.9 |
| `ansi-process-range!`, styled CJK piece | 4.6 | **2.1** | 7.3 | **5.7** |
| `ansi-process-range!`, plain CJK piece | 1.7 | **0.21** | 4.4 | **0.61** |

The 800-char styled line is the DFA's worst case (§18.1): the closing reset
sits at the end, so `re-seq` pays the late-match walk at every match. A
4338-char styled line makes the tracker 270.0 → 9.4 µs (~36% of that line's
`wrap-text-with-ansi`). bb's one loss is the short OSC 8 string (2.2 →
2.9 µs): a hyperlink-heavy line pays ~2.5 µs extra, against ~9 µs saved on
a styled one (`bb-tracker-ab.clj` counts it inside one process).

### 20.1 The OSC scan is index-of now

Using the scanner on bb forced a look at `ansi-sequence-end`'s OSC branch:
the per-character loop was *slower than the regex itself* on a long
unterminated OSC on both hosts (jolt 391.8 vs 308.8 µs; bb 1364.3 vs
688.6 µs at 16 KB) and on a 0x9C-terminated body (290.4 vs 506.3 µs on
jolt; 1165.6 vs 106.3 on bb), because it walked the whole tail one char at
a time. It now finds BEL and ESC with `str/index-of` (native on both hosts)
and searches the rare single-byte ST only inside the body before the first
BEL/ESC — best or near-best on both hosts across short hyperlinks,
10-link lines, 16 KB tails and both long-OSC shapes
(`osc-indexof-probe.clj`), and still exactly ANSI-CODE-RE's language.

### 20.2 Parity pins

`test/kmet/test_utils.clj` walks a 12,332-string corpus — the curated
`ansi-corpus`, every string up to length 4 over the escape alphabet
(`ESC [ ] ; ? 1 m \ BEL 0x9C`) and a deterministic 1200-string LCG sample up
to length 16 — comparing the scanner against the regex reference on strip,
anchored code-at at every index, the full scan and every `[i, stop)` scan
(~0.3 s on both hosts).

Writing that pin surfaced a latent parity gap in `ansi-sequence-end`: its
OSC walk continued past a bare ESC inside an OSC body to a later BEL, where
`ANSI-CODE-RE` (both engines) fails the match and can only match a later
OSC. It predates §20 — `strip-ansi` and `ansi-code-at` already diverged
from the regex on Jolt for that input — and is fixed to return nil.

`ANSI-CODE-RE` is now public: its remaining production callers sit in
`:default` reader branches, which the bb lint view does not read, so a
private var reads as unused there (the `match-at` / `ansi-code-at-native`
precedent).

---

## 21. The markdown tokenizer: chunked runs, gates, linear code blocks (phone, 2026-10-12, `jolt v0.8.21-61-g4d665200`, bb 1.13.225)

`md/parse` is the whole-message re-parse §6.3 has carried since the first
profiles (assistant text and thinking re-parse per streamed delta and theme
switch), and the inline tokenizer was still a per-character loop. This pass
profiled `kmet.libs.markdown` itself and removed four independent costs,
all output-identical. A/B probe: `target/mdbench/final_ab.clj` against a
frozen copy of HEAD's parser (`mdbench.orig-markdown`), same process,
alternating rounds, medians:

```sh
bb   -cp src:target target/mdbench/final_ab.clj
jolt -Sdeps '{:paths ["src" "target"]}' -e "(require 'mdbench.final-ab)"
```

### 21.1 Chunked plain runs (bb: 0.9 µs/char loop → 0.09 µs/char chunked)

A plain character walked the whole `cond` every iteration; on bb that
iteration measures ~0.85–0.9 µs/char, so a 17 KB prose line is a 27 ms
`parse-inline` call even with no markup. `parse-inline` now asks a stateful
matcher (`MATCHER-NEXT` behind `NEXT-SPECIAL-INDEX`, `re-matcher` +
`.find`/`.start`, the JVM-branch idiom of `kmet.tui.utils/match-at`, verified
on both hosts) for the next
`INLINE-SPECIAL-RE` index, and appends `(subs line i j)` for the whole run
up to it; when there is no match left the rest of the line is one append.
The matcher only advances (consumed-token positions behind the cursor are
skipped), so a line costs one regex pass plus one call per special.

The first attempt stopped at every h/H/f/F/w/W (the bare-url first chars)
and *lost* on prose: a stop is worth ~1 µs on bb, English hits one every
~4 chars, and the per-char loop it replaced costs ~0.9 µs. The matcher
pattern therefore spells out the literal schemes
(`(?i)https?://|ftp://|www\.|[...]`) and lets the regex engine skip the
ordinary words. The same pattern costs ~0.06 µs/char to scan versus
~0.014 µs/char for a bare class (`find_cost` probe), which is the price of
not stopping at h/f/w. Lines holding an `@` keep the per-char loop: an
email match can start at any local-part char, so a run chunk would skip
one. A whole-line fast path returns `[{:type :text :s line}]` without the
accumulator machinery when the first scan finds nothing.

### 21.2 The candidate gates ran the regex too often

The old gates only checked the *first* character. Two consequences:

- every h/f/w in prose paid `(subs line i)` (a copy of the whole line
  remainder) plus a `bare-url-re` miss. The gate is now the literal scheme
  prefix (`ci-prefix-at?`, no allocation); the regex still decides.
- on a line containing any `@`, every alphanumeric char paid a
  `(subs line i)` + `bare-email-re` miss. `EMAIL-FROM` bounds attempts to
  one per local-part run: after a failed attempt the scanner walks the
  run's local chars and the next attempt is allowed only past its first
  non-local char (the same `@`/domain make every start inside the run fail
  the same way). Success at a run's earliest position is equivalent to
  success at any later one, so the regex results are unchanged.

The differential fuzz caught an inverted `<=`/`>=` in that condition during
the pass (`~~user@example.com` lost its link); the fixed version is pinned
by `test-inline-email-after-marker-char` and `test-inline-multiple-emails`.
`at-prose` (100 `@user mentioned this…` segments, 4.4 KB) is the shape:
bb 8.9 → 8.3 ms, jolt 21.0 → 8.0 ms (jolt's irregex miss was the bigger
loss).

### 21.3 Code blocks were O(n²)

`code-state` accumulated the block with `(vswap! code-state update :text str
(str "\n" l))` — a copy of the whole accumulated block *per interior line*.
It now conjs raw lines and joins once at the closing fence (or at EOF). A
110 KB fenced block: **bb 358 → 11.9 ms (30x), jolt 168 → 9.1 ms (18.5x)**.

### 21.4 Block dispatch and the result vector

A plain 50-char line ran fence/hr/heading/quote/ul/ol/empty — 6–7 regexes —
before falling through to the paragraph branch. Each is now behind a
necessary-prefix `fc` gate (`marker-first-chars` for hr/lists, `` ` ``/`#`/`>`
for fence/heading/quote; `^\s*$` behind an empty `trimmed`), and the `:blank`
case still runs the regex when the gate passes. `parse`'s result vector is a
transient behind its volatile (all appends). 4000 lines × 50 chars:
**bb 252 → 36 ms (7x)**; the block loop alone (stubbed `parse-inline`) is
~4.2 µs/line on that fixture.

### 21.5 Totals

Medians of 5 alternating rounds; `orig` is the frozen pre-pass parser
(`md2k` mixed, `md-prose` one 17 KB plain line, `corpus` 20
assistant-style sections, `md-code` the 21.3 block, `at-prose` above;
`perf.md` itself as a real long document):

| fixture | bb old | bb new | jolt old | jolt new |
|---|---:|---:|---:|---:|
| `md/parse` 2 KB | 1408 µs | **278 µs** (5.1x) | 1144 µs | **397 µs** (2.9x) |
| `md/parse` 21.6 KB | 15.3 ms | **3.08 ms** (5.0x) | 11.8 ms | **3.95 ms** (3.0x) |
| prose 17 KB, one line | 27.2 ms | **1.64 ms** (16.6x) | 46.2 ms | **2.16 ms** (21x) |
| corpus 8 KB | 10.2 ms | **6.6 ms** (1.55x) | 10.2 ms | **8.0 ms** (1.27x) |
| code 110 KB | 358 ms | **11.9 ms** (30x) | 168 ms | **9.1 ms** (18.5x) |
| `perf.md` 119 KB (at capture) | 148.6 ms | **60.2 ms** (2.5x) | 160.4 ms | **103.0 ms** (1.56x) |

The remaining inversion in the mixed fixtures is the `corpus` row: it is
markup-dense (a link, a bare URL, backticks and emphasis in every ~400-char
section), so the runs between specials are short, the per-call matcher scan
is paid often, and the URL regex itself runs on real URLs. The added
`bb scripts/bench/kmet_perf_bench.clj` rows read 275.8 µs / 2.99 ms / 1.71 ms /
11.81 ms on bb and 458.2 µs / 5.82 ms / 2.37 ms / 9.69 ms on jolt (the
sweep runs after minutes of other benches, so trust the A/B ratios, not
those absolutes).

### 21.6 Parity pins

`parse` must be bit-identical to the pre-pass parser, so the frozen HEAD
copy stays as the reference: ~780,000 random/fragment strings on bb and
~220,000 on jolt across eight seeds (the final code was re-checked with
seeds 3/5/11/42/99, hard strings to 300 chars) produce equal ASTs
(0 mismatches), and the existing 74
`kmet.libs.test-markdown` tests pass on both hosts (145 assertions, run
directly under jolt's `clojure.test`). Three tests pin the cases the fuzz
exercised: an email starting after a marker char (`~~user@example.com`),
multiple emails per line, and case-insensitive/mid-word URL schemes.
`bb test-changed` 1728 tests / 11,001 assertions green, `bb lint-changed`
0 findings, `bb format-check-changed` clean.

---

## 22. Rendering hot paths after the streaming work (x86_64, 2026-10-29, bb 1.13.229 / `jolt v0.8.23-199-g0ac561e1`)

§21 and the three streaming landings (`f5bd6d56` re-render only the changed
block, `83a42d95` re-highlight only a fence's tail, `2a615ca6` line-start
code cache) changed the markdown render path. This pass re-measured the three
hot paths — full re-render, streaming, idle — on the desktop rather than the
phone, and removed the per-character/per-content cliffs it found: the
collapsed shell preview, the grapheme walker on wide text, and the per-code-
point walk over long wide/narrow words.

Fixture: `~/.kmet/sessions/19fdb60dc61-b305.ednl` (427 KB, 358 messages; 2,262
rendered lines collapsed / 9,040 expanded at width 100; 1.36 MB of tool
output). Harnesses: `scripts/bench/kmet_frame_bench.clj` (live loop frames),
`scripts/bench/kmet_render_bench.clj` (cold/warm/global reflows), plus scratch
probes in `target/streambench/` (streaming shapes, markdown phases, wrap and
CJK width micro, per-role attribution) kept out of the tree like §21's
`target/mdbench`. `wrap-micro`/`cjk-width` print their fixtures' char counts,
which caught two mislabelled runs in this pass — read the shapes, not the
labels.

### 22.1 Idle: the parked loop costs nothing

The render loop parks on `wake/park!` with a 100 ms heartbeat (resize poll +
safety net) and renders only when a frame is requested. Measured on the real
app in a tmux pty (`/proc/<pid>/stat` user+sys ticks, 10 s window): **bb 0.1 %
of a core, jolt 0.0 %** after settling. The 2.9 % a 15 s settle still caught
is host startup (extensions, catalog), not the loop — the same burn §11.4
records for jolt, and it ends after ~20 s.

A *requested* frame with no content change ("calm") still costs 1.4–2.1 ms
(render-stack 1.3–1.9 ms), and a per-frame render is what a spinner bucket
(240 ms) or an input tick buys; nothing periodic requests frames, so this is
the input-latency cost, not idle cost.

### 22.2 Streaming: per-append is fine at real block sizes

Frame bench, bb, expanded tools: **1.55 ms/frame** median (render-stack 1.0,
lines 0.31, diff+emit 0.21; 644-byte writes) on the 9 k-line document;
collapsed 1.17–1.25 ms. §13.5/§13.6 measured 9–15 ms for the same scenario
on the phone (14.9 k lines) before the incremental markdown work, so the
streaming landing holds.

The synthetic shapes (24 KB body, 10-char deltas, bb) show what the cache can
and cannot do:

| shape | deltas | total | median | max | full render |
|---|---:|---:|---:|---:|---:|
| blank-line separated (`thinking`) | 2400 | 308 ms | **0.12 ms** | 3.1 ms | 8.6 ms |
| one growing paragraph (`prose`) | 2400 | 6.0 s | 2.4 ms | 9.4 ms | 6.8 ms |
| growing code fence | 2402 | 4.4 s | 1.7 ms | 8.5 ms | 100.8 ms |
| growing list (no blank lines) | 2400 | 10.2 s | 4.3 ms | 16.1 ms | 8.2 ms |

The blank-line case is the one real thinking/text streams hit: the stable
prefix advances at every clean blank run, so the delta pays only its trailing
block. A construct spanning blank lines (a long paragraph, a list, a fence) is
re-rendered per delta — the fence is still 23x cheaper than a full render
(highlight + wrapped-body caches), the list and paragraph are *at* full-render
cost per delta because the block itself is the unit.

Real sessions never reach those shapes: across the 77 stored sessions that
parse as EDN the largest blank-line-free run in an assistant message is
**1.16 KB** (text) / **0.9 KB** (thinking), i.e. ≤0.2 ms per append. The synthetic list/paragraph
rows are the ceiling, not the common case.

One O(document) cost per append remains by construction: `render-lines`
rebuilds the returned line vector (`(into [] cat per-block)`) and compares
`(str/starts-with? text stable-text)` against the whole prefix, and the frame
then walks the document anyway. Measured with a 1.5 MB markdown body the
per-delta cost rises 1.5 → 6 ms as the body grows; at message sizes it is
noise.

### 22.3 Full re-render: still the whole document, but the collapsed preview no longer wraps it all

Cold frame, bb, width 100: collapsed sits around **110–125 ms frame** after
§22.4/§22.5 (the same-run A/B is in §22.4); expanded 406 ms (render-stack
351 ms, diff+emit 53 ms writing 1.48 MB). Warm frame 1.1 ms. Role split
(one cold run per mode; the per-message harness swings ±25 % run to run, so
read the shares):

| role | expanded | collapsed |
|---|---|---|
| tools | 340 ms — **85 %** (read 176, bash 160, write 4) | 91 ms — **86 %** (bash 73, write 11, read 7) |
| assistant markdown | 15 ms | 13 ms |
| user markdown | 1.4 ms | 1.2 ms |

Markdown is no longer the cost on a tool-heavy session — the tool renderers
are. A fresh expanded `read` result highlights its whole body (tokenize ~75 %
of `render-highlighted` on both hosts), and only expanded mode renders file
bodies at all (`render-read-result` returns nil when collapsed).

A session-B-sized markdown render (1.5 MB assistant text, 32 k lines, bb)
shows where the remaining big-session time goes: **1031 ms cold**, `wrap`
512 ms (50 %, 9,856 calls), `parse` + `parse-inline` 364 ms (35 %), highlight
127 ms (12 %), visible-width 97 ms (9 %). The block cache answers a repeat
render of the same text at the same key in **0.7 ms**, but a theme switch
re-keys it: **1.08 s** again. A fresh render through the cache costs the same
as a plain render (within noise); when the stable segment's last block is a
code fence it pays one extra full `md/parse` (the `clean-probe` parses the
segment plus 256 spaces — measured 2× the document's chars against 1× for a
paragraph-ending segment), i.e. parse's whole 16 % share again.

### 22.4 Applied: the collapsed shell preview is bounded

`output-result-nodes` styled, joined and wrapped the **entire** shell output
and only then kept the last five visual lines (`truncate-to-visual-lines`
wraps to count). Collapsed is the default display mode, so every replay,
theme switch, pad change and expand/collapse re-wrap paid O(content) per
result — up to the tool's output cap each:

| 135 KB shell output, collapsed preview | before | after |
|---|---:|---:|
| bb | 336 ms | **2.4 ms** |
| jolt | 164 ms | **0.7 ms** |

(`20 KB`: bb 46 → 1.0 ms, jolt 27 → 0.35 ms.)

`bounded-tail-visual-lines` is the tail mirror of the existing
`bounded-head-visual-lines`: it styles and wraps only the last lines until the
visual window is full (each logical line contributes at least one visual
line), so the cost is O(window). Wrapped lines are then trimmed to the cap as
before, and the expand hint now reports **logical lines** not shown in full
(`:consumed`), the same count the head previews and `bash_execution`'s live
preview report (pi: `hiddenLineCount`) — the previous visual count was the
only visual/logical mismatch left in the transcript.

Collapsed cold frame on the fixture, medians of 3 back-to-back runs with the
change stashed vs applied on the same tree: **bb 233 → 153 ms frame total
(−34 %)**, and the preview fix alone accounts for it (stash only that file:
156 ms) — the width work in §22.5 is noise on an ASCII/box-drawing
transcript. **jolt** (single runs, before the drift was noticed): 345.3 →
192.3 ms, render-stack 328.5 → 179.6. The collapsed render bench reads
106–121 ms/2,262 lines with a 1.1 ms warm frame (bb). Absolute times drifted
±30 % between this pass's runs (background load), so the tables' before/after
pairs are the numbers to trust, not their absolutes; the calm/stream frames
stayed at 1.4/1.2 ms (bb) and 1.1/1.1 ms (jolt) throughout. The rendered document is
otherwise identical: dumping the whole collapsed transcript before/after and
diffing shows the same 2,261 lines, differing only in the 36 hint counts
(e.g. `+66` → `+42` where lines wrap). Tests pin the wrapped-tail window, the
logical hint count, and that a 2,000-line body wraps only its tail;
`test-bash-result`'s existing tail/cap/hint cases pass unchanged.

### 22.5 Applied: BMP-wide text takes the arithmetic width path

`visible-width-plain` sent every string with a CJK or wide-emoji code point to
the grapheme walker (~8 µs/code point under bb/SCI) because
`grapheme-complex-re` mixed the wide ranges into the "needs the walker" class.
But the walker only decides things a per-character sum cannot: zero-width
characters and variation selectors (0), pairing constructs (regional
indicators, skin tones, ZWJ chains) and astral code points (one grapheme, two
UTF-16 units). A BMP-wide code point is just 2 columns, which is arithmetic.

The single class is now three answers behind one presence scan
(`width-path`): a `width-special-re` miss (the §10.5 class, unchanged —
accents, Cyrillic, box drawing) stays `:narrow` on exactly one scan; a hit is
then classified by `walker-re` (zero-width ∪ non-BMP) into `:walker`, or
`:wide` when no walker code point appears at all. `plain-char-width` takes
that verdict and adds one column per BMP-wide match — a whole-string
`str/replace` delta (one native pass, no per-match allocation). Both classes
are generated from the walker's own tables (`cjk-ranges`, the BMP slice of
`wide-emoji-ranges`), so the arithmetic and the walker agree by construction,
and `test-utils`'s equivalence corpus (CJK, fullwidth, Hangul, CJK
compatibility, BMP emoji, plus the boundary ±1 / 400 random-mix checks) pins
it; `wide-lines-skip-the-walker` asserts the CJK/Hangul/fullwidth/BMP-emoji
cases never enter the walker while flags, ZWJ chains, VS16 and astral emoji
still do.

| op | bb before | bb after | jolt before | jolt after |
|---|---:|---:|---:|---:|
| `visible-width` CJK 196 chars | 700 µs | **40 µs** (17x) | 206 µs | **43 µs** (4.8x) |
| `visible-width` BMP emoji 119 chars | 520 µs | **28 µs** (19x) | 163 µs | **23 µs** (7x) |
| `visible-width` Cyrillic 360 chars | 160 µs | **82 µs** (2x) | 19 µs | **20 µs** (=) |
| `wrap` CJK 196 chars @100 | 1.68 ms | **250 µs** (6.7x) | 628 µs | **205 µs** (3.1x) |
| `wrap` CJK 40 words @100 | 1.69 ms | **180 µs** (9.4x) | 511 µs | **180 µs** (2.8x) |
| stream a 1,320-char CJK paragraph (6-char deltas) | 2.32 s / 10.3 ms median | **235 ms / 1.0 ms** (10x) | 739 ms / 3.4 ms | **258 ms / 1.1 ms** (2.9x) |

Astral text is unchanged by design (it still walks: 40 astral emoji, 200
UTF-16 units, measure ~120 µs on jolt / ~400 µs on bb either way). The boring
non-ASCII case does not regress — jolt is unchanged, and bb measures ~2x
faster than the old single class on the same fixture (same code-point set,
isolated from the new tables; the cause was not chased), which matters because
every box-drawing or accented line in the transcript pays it.

The change is output-neutral on real content: dumping the fixture's whole
transcript before/after (collapsed 2,261 lines, expanded 9,039) and diffing
gives 0 differing lines. `bb test` 4,340 tests / 26,367 assertions (the only
4 failures are `kmet.app.test-process-state`'s pre-existing ledger misses for
`codemode.cljc`'s lane cells and `mutation.clj`'s `tails`, unrelated to this
pass), and `test-utils` + `test-tool-renderers` pass under jolt.

The width work left one more walk in the path — long unbroken words — which
§22.6 removes.

### 22.6 Applied: long wide/narrow words wrap by native slicing

An unbroken run (a CJK paragraph has no spaces at all) is one word, so
`wrap-single-line` hands it to `split-long-word`, which walked code points to
place the cuts (`ansi-active-codes` per piece plus `char-width` per code
point). But two shapes need no walk: a word whose code points are all
2 columns (BMP-wide: CJK, Hangul, fullwidth, BMP emoji) slices at
`width / 2` characters, and a word with nothing wider than one column
(accents, Cyrillic, box drawing) slices at `width` characters. Both are one
native scan to identify (`all-bmp-wide-re` / the absence of
`width-special-re` and controls) and then `subs` per piece; an ANSI-free word
also cannot change the tracker between pieces, so the active state is read
once (`walk-pieces` keeps the per-code-point fallback for mixed
wide/narrow, astral or code-bearing words, now without the per-piece code
scan). The per-word width in `wrap-single-line` was deliberately left alone:
an A/B of the uniform shortcut there measured no better on short words (one
run read CJK 40 words 170 → 226 µs; later runs were within noise), so the
second per-word classification was dropped rather than kept for a maybe.

`wrap-text-with-ansi` micro, before this pass / after both fixes:

| shape | bb | jolt |
|---|---:|---:|
| CJK 196 chars, one word @100 | 1.68 ms → **161 µs** (10.5x) | 628 µs → **123 µs** (5.1x) |
| Cyrillic 360 chars, one word @100 | 1.13 ms → **302 µs** (3.7x) | 359 µs → **99 µs** (3.6x) |
| CJK 40 words @100 | 1.69 ms → 193 µs | 511 µs → 168 µs |
| astral emoji 40 words @100 | 0.90 ms → 0.97 ms (=) | 273 µs → 321 µs (=) |
| ASCII 400 @100 | 71 µs → 71 µs (=) | — |
| stream a 1,320-char CJK paragraph (6-char deltas) | 2.32 s / 10.3 ms median → **175 ms / 0.78 ms** (13x) | 739 ms / 3.40 ms → **174 ms / 0.68 ms** (4.2x) |

The same-process A/B (stashed vs applied, interleaved in one process) shows
the per-word shapes and plain ASCII unchanged within the bench's ±20 % noise
(CJK 40 words 0.86–1.13x, Cyrillic 40 words 0.82–1.22x, ASCII 0.97–1.18x),
CJK-in-prose lines unchanged, and the unbroken shapes 1.5–3.7x. Output is
bit-identical: a frozen copy of the pre-pass `utils` wraps 5,000 random
strings (CJK, astral emoji, flags, ZWJ, VS16, skin tones, controls, SGR and
OSC 8 codes, widths 1–30) and every line of the 9,040-line transcript dump at
7 widths the same on both hosts — 0 mismatches. `test-wrap-long-unbreakable-
word-shapes` pins the three new slicings plus the mixed walker and a styled
all-wide word; `test-wrap-cjk-width` (existing) covers the all-wide path.

What is left in this path is the *narrow* per-character work for mixed
wide/narrow unbroken words and astral runs: the walker is ~2 µs/code point
(astral emoji 40 words is ~0.9 ms either way), and CJK prose with spaces
still pays the width classifier per word (~1 µs each). Neither is on the
streaming path at real block sizes (§22.2).

### 22.7 What is left, in the order the numbers point

- **A theme switch (and every key change) re-parses and re-styles
  everything.** The markdown block cache key includes the theme, so 1.5 MB
  costs 1.08 s again; a parse-level cache keyed on (text, transform, width)
  would keep `parse` + `parse-inline` (35 %) and re-style from tokens, and the
  read/write renderers would keep their tokens too (`render-highlighted`'s
  tokenize is 75–90 % of the call on both hosts, and a resize re-tokenizes
  identical text).
- **Expanded tool bodies are highlighted whole.** `read` calls
  `render-highlighted` on the full file at every width/theme change; the
  `write` tool already keeps a per-component highlight cache
  (`write-highlight-cache`), the read result does not.
- Virtualization still stands as §10.6 left it: the scroll view renders the
  whole document, so a redraw (resize) re-renders every component
  (468 ms on the fixture, 1.48 MB written).

## 23. The message-cache walk (`:parts`) — 2026-11

`ChatHistory`'s walk over its message components
(`kmet.ui.tui.chat-history/message-parts`) was the largest single item in
compose on a 447-message / 18,243-line session (`:tool-display-mode
:expanded`): 3.9 ms of a 7 ms typing frame, 75–80 % of compose. One `track!`
hit per message (`kmet.hikap.component/track-render`), ~6 µs each (user 3.9,
assistant 4.8, tool 7.9); ~78 % of that was the per-ref walk — the hit
re-derefed every tracked atom and compared values (`atoms-unchanged?`, 220 ns
per atom on the phone) — and ~1.2 µs was fixed overhead. The walk existed as
the net under a real hole and cost more than the work it guarded.

Two changes landed, with one guarantee change named:

1. **Watch at the read.** `kmet.reakt.core/*on-tracked-read*` is called with
   the ref and its kind just before `tracked-deref` derefs it; `track-render`
   installs a `:plain` ref's watch there, and registers the weak entry before
   the body with the previous pass's payload. That closes both windows the
   old net covered: **read→watch** (a write between a read and the body's end
   used to be unobserved and cached as-read) and **check→write** (the cache
   write was a read-then-act, so a concurrent `invalidate-cache` was erased
   by it). The commit is now an in-flight token: the miss path parks
   `{::rendering true}` in the cache atom and commits with
   `(swap! cache-atom (fn [cur] (if (identical? cur token) M cur)))`, which
   deletes `invalidated?`, its cache-atom watch and the add/remove-watch pair.
   Reaction watches stay post-body — a reaction's own derive fires its
   watchers, so an at-read install would invalidate the pass that read it;
   `rx-unchanged?`'s state test carries that half. A throwing body leaves no
   watches, no entry and no cache (it unwatches the union of this pass's reads
   and the previous payload); without that, a pass that throws strands every
   watch it installed.
2. **Trust presence** (the guarantee change). The hit path is
   `contains? :atoms` + width + `rx-unchanged?` + `tracked?`: no value
   re-verification. A watch lost behind `track!`'s back now leaves the
   component stale until something else invalidates it (accepted, pinned by
   `test-a-lost-watch-leaves-the-component-stale`), and an entry dropped by a
   stray teardown re-renders and re-registers (`tracked?`, pinned by
   `test-a-dropped-registry-entry-heals-through-the-hit-path`). Reactions keep
   `rx-unchanged?`: an input write marks a reaction `:dirty` without notifying
   its subscribers. `tui.md` §3.2 is the contract.

Measured after, dev host (WSL2/x86), same session: `:parts` calm 1.25–3.06 ms,
typing 1.20–2.17 ms, against 3.6–4.8 / 3.9–6.8 before. The gain is
ref-count dependent: a min-of-batches micro-benchmark on synthetic caches
(integer values) has 20 refs 0.88–1.20 µs → 0.41–0.49, 8 refs barely moving,
and a **1-ref `Text` hit slower** (0.48–0.51 → 0.67–0.77 µs), because the
ownership read is a fixed cost. Real message caches read 12–30 refs whose
values are theme maps and strings, where the old structural `=` was the
dominant term — the frame numbers, not the synthetic ones, are the win. This
host is noisy (WSL2 swings ~2× on identical code), so those are ranges, not
points; the soundness gate was a test, not the benchmark
(`kmet.ui.tui.test-track-staleness`: each message family's invalidated render
must equal a fresh component's render, and it was proved to bite — disabling
the at-read watch fails all five families).

**Declined: the parent probe.** Skipping the child's `render` call through a
`stable-lines` helper at `message-parts`/`Container.render` was modelled at
~0.2 ms and is worth nothing measured: after change 2 the phase sits at
~1.2 ms with the ~1.2 ms flatten/flags loop as its floor, and the probe's
cost is the same ownership read a 1-ref hit already loses to. Nothing of it
shipped. Also rejected: **verify-at-write** (a write can land after the check,
before the watch — the window shrinks, it does not close);
**a flag re-check at the commit** (the flag is set by the watch, i.e. after
the invalidating write, so it cannot be read at the right time — the token
exists for that reason); **reaction-driven invalidation of dependents**
(sound, but it buys nothing for this walk — no message component reads a
reaction — and needs ordering rules around manual reactions self-disposing on
unwatch).

Reproduce: `bb scripts/bench/kmet_frame_bench.clj <session> 100 30
calm,typing,stream` (the script's header says what each scenario drives and
how paused frames are excluded). Any session with 400+ messages works.

### 23.1 Earlier render-path structure findings

- ~~**Frame rebuild copies the whole document**~~ **fixed** (`build-frame-lines`,
  `frame.clj:113-187`): the pass now starts from the previous output vector, `assoc`s only
  the differing chunks, `conj`s the appended tail, and returns the previous vector itself
  when nothing changed (so the diff scan's `identical?` fast path sees it).

- ~~**The flat document is rebuilt at every container level**~~ **fixed**: children are
  flattened through `protocols/cached-lines` (the atom-backed `concat-cached`), a
  per-instance memo (`Container`'s
  `:lines-cache`, `ChatHistoryComponent :lines-cache`, `Box`'s `:cache`, the frame's
  per-run `document-cache`) that hands back the previous line vector — the same object,
  so the parent hits too — while every child returns the object it returned last pass.
  The key holds the assembly's non-child inputs (`[width]` for a container; geometry and
  a background sample for a Box), so a re-derived child is a miss: no invalidation hook,
  no parent link, and no staleness when a reactive child re-derives. Grouping separators
  are parts (one shared `[""]`), so a quiet-run regrouping can only show up as a changed
  parts sequence. `Container.render` no longer calls `render-all`, and `join-lines` is
  the one join (a single part is returned as-is). Measured (Termux/bb, 9k-line
  transcript, 4 nesting levels): idle compose 3.44 → 1.91 ms, keystroke 4.33 →
  2.10 ms; the frame pass then sees the identical document and takes its
  unchanged-document fast paths. Retained cost is one pointer array per memoized level
  (~8 bytes/line; no line text is copied) and dies with the component or the loop run.
  The quiet-mode re-parse went with it: `skill-block/parse` ran per user message per
  pass (105 µs for a 3 KB block — a transcript with a dozen skill invocations burned
  milliseconds every frame); the parse now happens once in `chat-history-add-message!`
  and rides the message map as `:skill-block` (stripped by `chat-history-get-messages`).

- ~~**`run-render-loop!` is ~720 lines**~~ **fixed**: the frame moved to `kmet.tui.frame`
  (`render-frame!` + the document/diff/emit passes over an explicit frame-state map); the loop
  is now 201 lines of lifecycle, timers, lock and schedule, and `test_frame` drives one frame
  on a stub TUI with no terminal. The review asked for three entry points
  (`build-frame`/`diff-frame`/`emit-frame`); they share the cursor and viewport state the diff
  mutates, so they landed as one `render-frame!` with the passes inside it — the split was
  the untested part, not the boundary. `main-diff` then came out too: `diff-frame!` (CC 79) is
  a top-level fn taking the frame values and the emit kit it needs, so the pass is 290 lines
  instead of 551 — the mass moved, it did not shrink (`bb slop` erosion 0.829).

- **Frame state is ~17 independent atoms** updated field by field — `create-tui` builds them
  (`core.clj:131-195`), `reset-frame!` resets nine one at a time (`frame.clj:435-451`), the
  commit writes nine in a row (`frame.clj:981-1010`), and the loop clears `force-redraw?`
  (`core.clj:2127-2129`): one `(:frame tui)` atom holding a previous-frame map removes the two
  lists that must stay in sync. 29 read sites, all in `core.clj` and `frame.clj`
  (`render-frame!` reads the memo at `frame.clj:796-798`).

- ~~**`(System/getenv "TERMUX_VERSION")` runs on every frame**~~ **fixed**: `termux-host?`.

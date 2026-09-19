# perf — runtime CPU profiles (babashka vs jolt)

Why `jolt run` burns more CPU than `bb run` on the same tree, what was measured,
and what to do about it. Numbers from a Termux/aarch64 phone (100x30 tmux pty,
`jolt v0.8.6-83-g3de3e02b`, bb 1.12.x), 2026-09-11.

**Re-verified on the installed `jolt v0.8.6-86-g234f460b` (x86_64 WSL2) — §9 is
the current state.** Every µs figure in §3/§5/§7 predates that build and is
stale in absolute terms; the orderings and the §5 decisions all still hold.

**TL;DR** — it is not a busy loop and not the terminal backend. Idle CPU is
*lower* on jolt than on bb. The gap is that (a) every keystroke/frame re-renders
the whole component tree + re-normalizes every line + diffs/emits, and (b) jolt's
*runtime primitives* are 2–5x slower than bb's on exactly the operations that
path is made of: `java.util.regex` (→ irregex on jolt), per-call string helpers
(`str/replace`, small-string overhead), and collection/seq allocation. It is not
interpretation: jolt already compiles every top-level form to native Scheme
(§6.2). Jolt is *faster* on numeric/compute-heavy code (fib 27: 5.5 ms vs bb
121 ms) and on `subs`/`index-of`, so this is a per-op profile mismatch, not a
uniformly slow runtime.

The quick wins in §5 are applied and measured: **`jolt run` typing CPU dropped
~28 %** on that workload (§7), with bb unchanged. Parity with `bb run` held on
the phone but **not on x86_64** (~1.6x there — §9.3): the improvement is
host-independent, the ratio is not. The next levers are §6.3 (incremental
markdown) and §6.6 (extend the ANSI scanner carve-out to the remaining
per-escape sites); §6.1's normalize guard is dead (§9.4). `jolt build` is *not*
a lever (§6.2).

---

## 1. Method

Reproduce with:

```sh
# app-level: same tree, same config, tmux pty 100x30
tmux new-session -d -s pj -x 100 -y 30 'jolt run -m kmet.core'
tmux new-session -d -s pb -x 100 -y 30 'bb run'
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
`terminal_native.cljc`):

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
jolt build -m kmet.core -o kmet-jolt --dynamic   # ~15 min on the phone, 52 MB
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
  workarounds are gone (`jolt-bugs.md`).

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
  `kmet.tui.test-render-loop`, `kmet.app.ui.test-tool-renderers` (or
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
| A `1a0060c8776` | 224 (110 tools) | 3,620 | 1.9 s | 3.4 s | 1.5 s | **15 ms** | 3.2 s | **2 ms** |
| B `1a099712492` | 636 (591 tools) | 17,339 | 10.3 s | 17.5 s | 6.8 s | **19 ms** | 13.7 s | **3 ms** |

"Old/new" = before/after the three fixes below, old files checked out
(`6be6ae2`) and re-measured back to back on 2026-09-19. To a stable first
frame (replay + the first content-stable render): session A **6.7 s → 3.2 s**,
session B **34.6 s → 13.7 s**. Replay and the old render #2 carry the most
run-to-run noise: B's replay measured 10.3–14.7 s across runs, and the old
render #2 depends on the worktree — the pre-10.3 call preview read the
*current* files, so it forced a settle frame for every edit whose file had
changed since, and the same old code measured 6.8 s here against 0.35 s in an
earlier run where it needed few corrections. Steady-state frames after that:
**2–6 ms** (session B), so streaming/typing is unaffected — the cost is
concentrated in the first full render and in any invalidation that drops the
whole tree. Session B's per-role cold split at HEAD: assistant markdown
10.5 s / 10,068 lines / **1.50 MB** of text (~7 µs/char), tools 2.7 s /
7,012 lines / 1.03 MB (~2.6 µs/char — §10.3 renders the recorded diffs on the
first pass, so more lines than the pre-10.3 6,216 and less than half the
time), compaction summary 0.40 s, user 3 ms.

Cheap follow-up interactions, once the transcript is warm (session B,
re-measured at HEAD; the pre-10.3 table read 1.9 / 3.7 / 17.5 / 18.9 s — the
edit settle fired again inside every global reflow):

| action | cost | note |
|---|---|---|
| Ctrl+O → collapsed | 1.3 s | tool components re-render only |
| Ctrl+O → expanded | 2.9 s | full tool output re-render |
| theme switch | 13.6–13.9 s | every markdown message re-parses (colors are baked in) |
| output-pad change | 13.9–14.3 s | every boxed message re-wraps |
| ordinary frame | 4–5 ms | caches warm |

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

### 10.4 bb vs jolt: the profiles invert by subsystem

Same harness (`scripts/kmet_render_bench.clj`), same phone, width 100, one run
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

Per-role cold split of B (`roles` mode; two runs where they disagreed):

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
(slurp + normalize are ~2 ms on both); the regex behind it is filed as the
`jolt-bugs.md` perf ticket — per-line `$`-anchored patterns are 10–70x slower
on jolt, and the fix is `str/trimr`. Excluding it, the 34 recorded diffs
render *faster* on jolt than on bb (edit tool ≈54 ms vs ≈330 ms); bash and
read are within 10 % (`bash` bb 1,481 ms / 140, jolt 1,616; `read` bb 640 / 25,
jolt 741).

### 10.5 What is left

- **§6.3 (incremental markdown) is the lever that matters here.** The first
  full render is ~7 µs/char of markdown; the 1.5 MB session is 13.7 s and a
  theme switch or pad change re-pays it. Block-level parse reuse by text would
  cut both the first render and the global reflow.
- **Live edit previews** still compute the filesystem preview while args
  stream (inherent — the result does not exist yet); the replay half is now
  free. A *finished* call with no recorded diff (an errored edit) still
  recomputes that preview from today's file on replay — the one 14x-jolt hot
  spot in §10.4; either skip it for completed calls or memoize it by
  (path, mtime, edits).
- **Virtualization** (rendering only the visible components) would remove the
  first-render cliff entirely, but the scroll view's height math and the
  track!-cached-tree model make it a design change, not a local fix.

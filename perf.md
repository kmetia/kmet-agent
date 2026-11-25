# perf — runtime CPU profiles (babashka vs jolt)

Why `jolt run` burns more CPU than `bb run` on the same tree, what was measured,
and what to do about it. Numbers from a Termux/aarch64 phone (100x30 tmux pty,
`jolt v0.8.6-83-g3de3e02b`, bb 1.12.x), 2026-09-11.

**§1–§12 are the condensed early passes (v0.8.6–v0.8.10); their detailed
tables and run logs are in git history. §13 onward is current.** The v0.8.10
upgrade inverted the host comparison: jolt's primitives are no longer 2–5x
slower on the frame path (§11.1).

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
`normalize-terminal-output` guard is a wash, not a bug (guarded 0.50 vs 0.51
µs full unguarded on jolt). Two kmet call sites that still fed the regex
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
phone (§11.4). The next lever is §6.3 (incremental markdown); the normalize
guard is dead (§6.1), §6.6's scanner is applied (§12.5), and the
SEGMENT-RESET concat is gone (§11.5).

---

## 1. Method

Reproduce with:

```sh
# app-level: same tree, same config, tmux pty 100x30
tmux new-session -d -s pj -x 100 -y 30 'jolt start'
tmux new-session -d -s pb -x 100 -y 30 'bb start'
# per-thread CPU from /proc/<pid>/stat fields 14+15 (user+sys ticks, 100 Hz)
# per-key CPU: u0=$(awk '{print $14}' /proc/PID/stat); <send N keys>; u1=...
```

Numbers below are `user` ticks unless stated. Warm up microbenches 2–3 times;
one-pass timings are dominated by JIT/irregex warmup and can invert the
ranking. `bb` is a GraalVM native image; `jolt run` compiles each top-level
form to native Scheme as it loads it (§6.2). Both run the same source.

Frame attribution was measured by temporarily instrumenting
`kmet.tui.core/run-render-loop!` with per-phase counters and reverting the
patch — see §4.

---

## 2. What is not the cause

Idle is 47–56 ticks/10 s (~5 % of a core), with only two
`ioctl(TIOCGWINSZ)`/frame and zero writes; the native terminal backend is
cheaper than bb's JLine loop; startup is jolt ~2.8 s vs bb ~3.6 s; forced
resizes are equal (30 vs 31 ticks); and per-key cost is flat from a fresh
session to an 800-line transcript. The CPU is the app's per-frame work —
full-tree render, line normalization and diff — not a busy loop, the backend,
startup or transcript size.

---

## 3. Where the CPU goes (v0.8.6, historical)

### 3.1 Per frame (200 instrumented frames incl. typing)

Render-stack 590/1007 ms (73/67 %), normalize 31/122 ms (4/8 %),
`applyLineResets` 31/80 ms (4/5 %), diff+emit 158/294 ms (20 %); total
807/1499 ms, 1.9x (bb/jolt). Render-stack is the per-frame tree walk, not
leaf rendering — `track!` caches leaves.

### 3.2 End-to-end scenarios

Typing 100 keys 0.7 vs 1.6–1.9 s (2.5x); 50 keys with an 800-line
transcript 0.30 vs 0.60 s (2.0x); paste 5000 chars 2.6 vs 5.9 s (2.3x);
`!seq 1 3000` 1.07 vs 1.30 s (1.2x); idle ~7–8 % vs ~5 % of a core (0.7x).
Per keystroke: ~12 ms CPU on jolt, ~6–7 ms on bb.

### 3.3 Hot operations (steady-state microbenchmarks, pre-§5)

Jolt won native primitives (`fib 27` 0.05x, keyword lookup 0.2x, `subs`
0.4x) and lost regex/string/allocation work (`visible-width` on a 1-char
string 6x, `matches-key?` 3x, editor render of 20 lines 5x, `mapv inc`
4.6x). **Superseded at v0.8.10 — see §11.1.**

### 3.5 Markdown / streaming

`md/parse` re-parsed the whole message per render, making streaming the
other per-frame bottleneck; incremental block parsing is §6.3's target.

---

## 4. How the frame instrumentation was done (repro)

`run-render-loop!` was temporarily patched with `defonce` atom counters
(`__perf-*`): a start timestamp at the top of the render-requested block,
side-effecting `let` bindings after `stack/render-stack`, after the
`normalize-terminal-output` mapv and after the `SEGMENT-RESET` mapv, plus an
accumulator that spits a report every 200 frames. Revert after measuring;
the patch is ~30 lines and purely additive. Report `render_ns` divided by the
frames in the window, excluding idle frames (the counters only run when a
frame renders).

---

## 5. Applied quick wins (v0.8.6)

All four are in the tree; re-verify with the recipes in §1. An A/B against
the pre-§5 files on `-86` confirms all four still win on jolt (§9.2).

### 5.1 `visible-width`: printable-ASCII test first

The printable-ASCII test `#"[^\u0020-\u007e]"` also matches ESC, so it
runs first: a pure-ASCII line answers `(count s)` with neither a strip nor a
grapheme walk; a styled or non-ASCII line strips only when an ESC is present
and falls through to the walker. The final form (with §5.4's `strip-ansi`):

```clojure
(defn visible-width [s]
  (if (empty? s) 0
      (if (re-find #"[^\u0020-\u007e]" s)
        (visible-width-plain
         (if (str/includes? s "\u001b") (strip-ansi s) s))
        (count s))))
```

Measured (100 k iterations, steady state, bb/jolt): plain 100-char
6.90/10.90 → 3.24/5.48 µs; 1-char 0.54/3.0 → 0.36/0.99; ANSI-styled
8.17/24.5 → 7.41/9.75; mixed markdown 4.57/16.5 → 4.81/7.65. It is called
per line in the frame diff, per grapheme in editor wrapping and markdown
inline styling, and per item in every selector.

### 5.2 `keys/matches-key?`: parse once, normalize once

A keystroke parsed the same raw `data` 30–60 times and re-normalized both
sides per call. Two invalidation-safe caches: raw data → parsed key id (a
one-slot memo storing the kitty flag, cleared on a flip) and key id →
`{:key … :mods #{…}}` (bounded by the keybinding vocabulary). `matches-key?`
6.1/18.4 → **~1.0 µs** on both hosts (15–18x); 30–60 calls/keystroke saves
~0.5–1 ms on jolt and ~0.2–0.3 ms on bb.

### 5.3 Editor: compile the autocomplete trigger spec once

`trigger-pattern` and the provider trigger-char set were rebuilt per
keystroke; both are now memoized in one `trigger-spec`
(`{:pattern re :chars #{…}}`). Precompiled `re-find` 1.6 → 0.6 µs (bb),
1.7 → 1.1 µs (jolt). The remaining tiny per-keystroke `re-find`s are left
alone: ~1–2 µs each, and hand-written predicates risk host-specific
whitespace/word-set drift for no measurable gain.

### 5.4 ANSI strip: host-specific implementation (`.cljc` carve-out)

A hand-rolled scanner seeded by `str/index-of` is semantically identical to
the regex (pinned on both hosts, including private-parameter CSI,
unterminated OSC and a lone ESC) and much faster on jolt: a styled 100-char
line costs regex 17.3 µs vs scanner 3.4; bb prefers the regex (4.6 vs 8.1).
So `kmet.tui.utils` became `utils.cljc`:

```clojure
(defn- strip-ansi [s]
  #?(:jolt (strip-ansi-native s)
     :default (str/replace s ANSI-CODE-RE "")))
```

`strip-ansi-native` is public (the bb lint view reads only the `:default`
branch) and `strip-ansi-codes` delegates to it. Both branches are exercised
by `test-strip-ansi-host-equivalence`. The styled-line `visible-width` gap
fell from 3x to 1.26x.

---

## 6. Suggestions and measurement notes (status at v0.8.10)

### 6.1 Cut per-frame line work

The normalize guard is dead: `normalize-terminal-output` costs 1.05 µs on a
plain line on jolt, the guarded form 1.18 µs (the scans are not cheaper than
the replaces they skip). The emit-time `SEGMENT-RESET` (4240e45) removed the
per-line `mapv`; no line-work item remains beyond §6.3.

### 6.2 `jolt build` is not the lever it looks like

`jolt build -m … --dynamic` makes a working ~52 MB binary but does not
reduce CPU (resize 127–134 vs 122–130 ticks; paste identical) because
`jolt run` already compiles every top-level form to Scheme. Optimize the
code, not the build mode. Build notes: a minimal closure can hit jolt#944
(load `jolt.time` early); ~15 min on a phone.

### 6.2b Measurement caveats (learned the hard way)

- **Typing benchmarks are noisy.** At a 15–20 ms key interval against a 16 ms
  frame loop, frames per key vary run-to-run and swing ticks 30–60 %. Use
  several rounds and medians, or deterministic recipes (N full-redraw
  resizes, one large paste, one long bash-output command).
- **Never measure while a `jolt build` runs** — it pegs 55 % of the CPU.
- **Warm up microbenches 2–3 times**; one timed pass measures JIT/irregex
  warmup and can invert the ranking.
- `/proc/<pid>/stat` fields 14+15 are 100 Hz ticks; `ps` %CPU is averaged
  over process lifetime and useless for short scenarios.

### 6.3 Markdown: incremental block parsing — still the top lever

`md/parse` parses the whole text per render (2 KB ≈ 30 ms/frame on jolt).
Cache parsed blocks by block text and reparse only the last streaming
block; the tokenizer already produces a block vector. Benefits bb equally.
Still open; §21 and §23 measure the tokenizer and the message cache.

### 6.4 Reduce tree-walk cost — structural, open

Render-stack is 67–73 % of frame time. Keep the root node set stable so
reconciliation reuses nodes, and fail fast on warm components in the walker
instead of inside `render`.

### 6.5 Keybinding dispatch — open

Even with §5.2 the loop is O(bindings) per keystroke. Reverse the table when
a manager is built: parsed key id → [binding id …], one hash lookup per
keystroke.

### 6.6 Extend the scanner to the remaining ANSI helpers — applied

`ansi-code-at` now has a jolt scanner (`ansi-code-at-native`): 1.374 →
0.378 µs on jolt, bb keeps the regex at 0.830. The two are pinned by
`test-ansi-code-at-host-equivalence` (§12.5).

### 6.7 Upstream notes

jolt's regex engine picks DFA for group-free patterns and the backtracker
for grouped ones; irregex warmup runs 3–4x steady, and wrapping a grouped
pattern as `( (?i)… )` moves `(?i)` inside the group (a bogus 113x
regression measured once). Reader/IO workarounds all closed upstream.

---

## 7. End-to-end result of the applied wins (v0.8.6)

Same harness (fresh session, 14 s settle, 150 keys at 25 ms, median of 3):
jolt 157 → **113** ticks/150 keys after the §5 wins; bb 114/116. A ~28 %
drop on the phone. On x86_64 the same ~24 % drop reproduces, but from 68 to
51 ticks against bb's 30–32 — **~1.6x, not parity**. The phone's parity is a
memory-bandwidth property, not host-independent.

---

## 8. Verification

Correctness: `kmet.test-utils`, `kmet.test-keys`,
`kmet.tui.components.test-editor`, `.test-markdown`, `.test-truncated-text`,
`.test-text`, `kmet.tui.test-render-loop`,
`kmet.agent.ui.tui.test-tool-renderers` (or `bb test-changed`), on both hosts
where §5.4 branches. The scanner equivalence tests pin both paths.
Performance: §1 recipes, steady-state microbenches, `/proc` per-key,
14 s settle, 150 keys at 25 ms, fresh session, median of 3.

---

## 9. v0.8.6-86 x86_64 re-measurement (historical)

Different host class: compare ratios and same-machine A/Bs, not absolutes.

### 9.1 Primitives

The upgrade moved a large share of §3.3 at once, but jolt's regex stayed
2–4x bb on this path and the host carve-outs stayed. The §5 optimizations
were A/B'd against the pre-§5 files: all four still win. Two traps this
file has fallen into twice: irregex's warmup rounds run 3–4x steady state
(interleave A/B/A), and wrapping a grouped pattern as `( (?i)… )` moves
`(?i)` inside the group. Cold 49-alternation regex: 29.4 ms DFA build on
jolt, steady-state wash.

### 9.2 The §5 wins still win

`matches-key?` 5.95 → 0.31 µs (19x); 40 chord checks 244 → 12 µs. §5.1
holds (ASCII-first regex stays). §5.4's margin widened: jolt's regex strip
17.3 → 7–8 µs against the scanner's ~1 µs (5x → 7.4x); forcing the carve-out
off costs 8 % of frame time. §5.3 was unchanged and not re-measured.

### 9.3 End-to-end

300 keys @ 20 ms: pre-§5 68 ticks → 51 (~24 % drop), bb 30–32 — **~1.6x**,
not §7's 0.97x. The same relative improvement; the absolute gap did not
close on x86. Read ratios, not counts.

### 9.4 Frame attribution (superseded by §11.3)

Render-stack 2.0x, normalize 3.8x, `applyLineResets` 2.5x, diff+emit 1.8x;
total 2.1x. The §5 wins live in the input path, not the frame path. The
normalize guard is dead; `SEGMENT-RESET` moved to emit time (4240e45); §6.6
still stands; §6.3 is the top algorithmic lever (2.4 ms/frame at 2 KB,
25 ms at 16.8 KB on jolt).

---

## 10. Large-session expanded render (2026-09-19)

Resume replays the branch, then renders the whole chat expanded; `track!`
makes later frames cheap but the first pass pays for every message. Two
~3.8 MB sessions: A 3,620 lines, B 17,339. Fixes 10.1–10.5 cut replay +
first render from **6.7 s → 0.95 s** (A) and **34.6 s → 4.8 s** (B);
steady-state frames are 2–6 ms.

### 10.1 Assistant render body tracked its own output atoms

The render body read `rendered-text-atom` / `rendered-*-lines-atom` /
`last-render-width-atom` inside `track!` while `reflow-all!` wrote them on a
cache miss, so the frame was discarded and re-ran, reflowing twice on a
width change. Output atoms are products, not inputs: read them through
untracked helpers. Session A render #2 **1.57 s → 0.15–0.21 s**.

### 10.2 No eager width-80 reflow in the constructor

`make-assistant-message` reflowed every message at width 80; replay then
could not reuse a line at another width. Lines are now built lazily by the
first render at its actual width. Session B replay **10–15 s → 19 ms**.

### 10.3 Replayed edits render the recorded diff

The call renderer preferred today's file over the session-recorded diff and
forced a settle frame (whose invalidation re-emitted the document). It now
prefers the recorded diff once the result carries one, and skips preview
computation for an errored result (placed after the cached-preview branch).
A first render 4.7 → 3.2 s; B 17.6 → 13.7 s; cold edit tools 552 → 258 ms.

### 10.4 bb vs jolt: profiles invert by subsystem

Markdown-heavy work is ~2x faster on jolt; tool rendering is ~2.3x slower.
One errored edit preview (275 KB file) cost 196 ms bb / 2,851 ms jolt; fixed
with `str/trimr`, one memoized normalization per apply pass, and an ASCII
guard (jolt edit role 3,339 → 309 ms, now at parity with bb's 321).

### 10.5 `visible-width` grapheme fast path

Plain non-ASCII code points were walked through the grapheme-aware path
(~8 µs/char under SCI). A regex class generated from the walker's own range
tables lets strings with no special code point take the arithmetic sum.
Session B cold render 14.30 → **4.18 s** (bb), 8.64 → **6.44 s** (jolt);
rendered document identical (17,335 lines, same hash on both hosts).

### 10.6 What is left

Incremental markdown (§6.3) is the biggest remaining share; live edit
previews still compute the filesystem preview while args stream; full
virtualization remains a design change, not a local fix.

---

## 11. v0.8.10 phone re-measurement (historical)

### 11.1 The primitive profile inverted

Jolt now wins width/string/regex/`md/parse`; what still loses is
seq/allocation work (`mapv` 2.7x, editor 20-line render 2.7x), the per-line
normalize (3 µs/line), and the `grapheme-complex-re` miss (a box-drawing line
108.8 vs 9.42 µs, now against bb). §5.4 holds and widened (17.05 vs 1.79 µs
styled); §5.1 holds. The normalize guard is a real jolt loss (0.314/0.982
guarded vs 0.280/0.686 unguarded). Cold 49-alternation regex: 122 ms DFA
build / 65 ms grouped, steady 3.55 ms (2.7x bb).

### 11.2 The render bench

Session B: cold 3.87/5.5 s, reflows ~1.4–2x, warm 3.9/3.6 ms; roles
assistant 2.86/3.15 s, tools 1.64/2.96 s (bash 992/1886, read 511/825,
edit 133/250). Session A same shape: jolt wins markdown, loses tools ~3x.

### 11.3 Live frame phases (and the kitty scan)

The live resumed document is 3,739 lines, not the harness's 17,335: fresh
frames 2.3/3.1 ms, resumed B 20/25 ms. The kitty-image walk ran every
changed frame with no capability check (6.3/7.1 ms on the live doc);
guarding it on `(:images (img/get-capabilities))` (cb763f6) cut diff+emit
8.07 → 1.95 ms (bb) and 7.62 → 0.54 ms (jolt). Jolt is normalize-bound,
bb render-stack-bound. Thinking markdown is 485 KB / ~22x the visible text —
§6.3's target.

### 11.4 Typing CPU: parity on the phone

300 keys @ 20 ms, net of idle: bb median 177 ticks, jolt 195 (~1.1x, within
noise). Jolt no longer settles in ~3 s: the user-level `clojure` extension
burns 22–25 s before the idle loop parks (sandboxed: ~4 s), so the harness
defaults to a 30 s settle.

### 11.5 What this changes in this file

§3.3's reading is superseded; §6.1 is applied and its normalize guard is
stale; §6.3 is the top lever; §6.6 stands but is smaller (the big jolt
regex loss was the strip, already carved out); cb763f6 closed the last of
§6.1's siblings.

---

## 12. v0.8.10 x86_64 re-measurement (historical)

Fixture `19fdb60dc61-b305.ednl` (8,793 lines); ratios only.

### 12.1 Primitives — §11.1's shape reproduces

Jolt wins widths/strings/parse/keys; `mapv identity` 2.8x, editor 20-line
2.7x, normalize guard 4.8x (widest ratio), §5.4 confirmed (7.96 vs 0.73 µs
styled). Cold regex: 67.9 ms DFA / 25.4 ms grouped.

### 12.2 Render bench — ~1.8x cold, ~2x reflows

Cold 619/1133 ms, warm 0.9/1.3 ms; Ctrl+O collapsed 186/470, expanded
566/1134, theme 614/1239, pad 628/1234 ms; roles tools 662/1292 ms. Kitty
scan 1.11/3.57 ms per pass (8,793 lines) and skipped without image support.

### 12.3 Typing CPU — parity reproduces

300 keys @ 20 ms, 30 s settle: bb median 41, jolt 42 (~1.0x). Idle 5
ticks/6 s on both; the extension burn does not show with the longer settle.

### 12.4 The Normalizer fix in this build

NFKC over 1.09M ASCII chars is 1.9 ms on jolt (was 155.52); `isNormalized`
0.43 ms. No kmet path changes — `edit-diff`'s ASCII guard was already
independent of the fix.

### 12.5 Applied follow-ups (2026-09-21)

One `[:text]` node per tool-output block (259 → 76 ms bb, 570 → 172 jolt)
and a normalize memo keyed on identical raw lines (cold 619 → 402 ms bb,
1133 → 918 jolt; reflows ~25–35 % cheaper). A later pass added the kitty
gate and the `ansi-code-at` scanner (1.374 → 0.378 µs jolt). Still open:
incremental markdown (§6.3), virtualization, and memoizing
`extract-cursor-position`'s marker strip (0.9/5.3 ms per frame).

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

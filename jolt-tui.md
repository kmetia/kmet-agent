# jolt-tui: porting kmet's TUI to Jolt

How to build a cross-platform TUI for Jolt by porting `kmet.tui`
(a Clojure/Babashka port of `@earendil-works/pi-tui`), following the same
architecture pi-tui proves out: **portable ANSI rendering + platform-aware
raw input**, with the platform half swapped from JLine to C FFI.

> Status of the previous draft of this file: direction right, code samples
> wrong. `tty-mode-set!` is Gambit, not Chez/Jolt. `get-proc`,
> `make-uint32-ref`, `os-type`, `bitwise-ior`/`bitwise-and`/`bitwise-not`,
> `(import (jolt.ffi))` do not exist in Jolt. The real API is
> `jolt.ffi/defcfn` + `with-out`/`read`/`write` (see §4). The 5-case
> `read-key` loop also ignores ~2000 lines of input machinery in
> `src/kmet/tui/core.clj` that exists for observed corruption bugs (§7).
> This rewrite fixes both.

Source of truth: `src/kmet/tui/tui.md` (package docs),
`src/kmet/tui/terminal.clj` (the protocol seam + shared terminal logic),
`src/kmet/tui/terminal_jline.clj` (bb/JVM backend) and
`src/kmet/tui/terminal_native.cljc` (Jolt backend),
`src/kmet/libs/terminal.clj` + `src/kmet/tui/keys.clj` (portable),
`src/kmet/tui/core.clj` (input/render loop to reimplement against).
Jolt API refs: `jolt-lang.github.io/docs/native-interop.html`,
`docs/host-interop.html`.
Companion: `jolt-port.md` (the port's remaining work; this file is its
TUI deep-dive).

---

## 0. Status

### Current (2026-09-18): the terminal adapter exists on Unix

**Implemented and verified end to end** — first on `jolt
v0.8.6-72-g0f7d1a11` (WSL2 x86_64), then re-verified on Termux/aarch64
`jolt v0.8.8-103-g7b234243` (the focused suites and the nested-pty test
below). The abstraction was first made total on bb/JVM (no behavior
change), then the Jolt backend landed:

- **`kmet.tui.terminal` is protocol + shared logic only.** The platform
  protocol is lean — `start!` / `stop!` / `started?` / `write-output` /
  `read-input` / `columns` / `rows` / `set-progress!` — and everything else
  (cursor/clear/title/move verbs, the Kitty and query wrappers, the drain
  loop) is derived once, above the backend. `create-terminal` resolves the
  backend at runtime (`requiring-resolve`), so neither host loads the
  other's platform deps. The old JLine reach-throughs in `core.clj`
  (`(.terminal …)`, `(.reader …)`, `.getWidth`/`.getHeight`) and in
  `modes/interactive.clj` (the `(:writer …)` nil-check) are gone: the
  reader loop and the render loop speak only to the protocol.
- **`kmet.tui.terminal-jline`** is the bb/JVM backend — the only namespace
  importing `org.jline.*`, behavior unchanged (raw mode + timed reads +
  live size + the stty snapshot workaround).
- **`kmet.tui.terminal-native`** (`.cljc`, body under one `#?(:jolt …)`
  branch) is the Jolt backend: termios raw mode (tcgetattr → cfmakeraw →
  tcsetattr), `poll(2)` + `read(2)` for bounded reads, `ioctl(TIOCGWINSZ)`
  for the live size, and UTF-8 reassembly across read boundaries via
  `kmet.libs.terminal/utf8-decode`. Restore runs on `stop!` and on a
  `Runtime.addShutdownHook` backstop (the classic raw-mode-left-on failure).
  No subprocesses, no libraries beyond libc. §5 is the reference for the
  FFI shape; the landed code differs in three places: `TCSANOW` (not
  `TCSAFLUSH` — flush would discard typed-ahead input at start),
  `poll` + one 1KB read per pass (not a blocking read per char), and an
  atomic `claim-restore!` so `stop!` and the shutdown hook can never
  double-free the saved termios.
- **Windows is open.** `create-terminal` throws a clear error there;
  §6 is the design (kernel32 `GetConsoleMode`/`SetConsoleMode` +
  `ENABLE_VIRTUAL_TERMINAL_INPUT`, `WaitForSingleObject` + `ReadFile`,
  `GetConsoleScreenBufferInfo` — the same three calls as pi's
  `win32-platform.c`).
- **Mouse tracking** is not implemented (no enable/disable sequences);
  the key parser already recognizes and filters mouse events (`keys.clj`,
  §7.7).

Verified with the pty scripts (`scripts/pty_capture.py`):

| check | result |
|---|---|
| FFI round-trip in a pty | raw size 90×25 via `ioctl`; `read` of `hi👋` → `[104 105 128075]`; cooked restore |
| `jolt test kmet.libs.test-terminal` | 4 tests / 17 assertions green (decoder) |
| `jolt test kmet.tui.test-terminal-native` | 1 test / 5 assertions green (tty-free surface) |
| `jolt test-ext kmet.tui.test-terminal-native` | 1 test / 7 assertions green (nested jolt in a real pty) |
| `jolt test-ext kmet.tui.test-render-loop` | **12 tests / 77 assertions green** (the suite drives a protocol stub, no JLine) |
| real kmet TUI on Jolt | `jolt run -m kmet.core` in a pty: renders, `/quit` exits 0, cursor restored (`\u001b[?25h`) |
| suspend/resume shape | create → raw → read → stop, twice in one process: both rounds read their input |

Rows 2–5 re-verified 2026-09-18 on Termux/aarch64 — the nested-pty row
runs the real raw-mode/read path under bionic. Follow-ups: Windows (§6)
and a Jolt-host variant of the pty app smoke — the existing
`modes.test-overlay-input-smoke` spawns `bb run` (testing bb's TUI even under
the Jolt runner), so it is `^:bb-only`.
Known divergences from the JLine backend: it uses stdin/stdout directly
(pi does the same — JLine instead opens the system terminal, so a
redirected stdout would still reach `/dev/tty` there), and it registers
one shutdown hook per suspend/resume cycle (each a no-op once its
terminal stopped; bounded by user actions).

**`jolt test-ext` red-set correction (this doc had it wrong).**
`kmet.modes.test-overlay-input-smoke` is *not* blocked by the adapter: the
test spawns a hardcoded `bb run` through a pty, so on the Jolt host it
exercises bb's TUI, not Jolt's. It is now `^:bb-only` (its ~20 s of stages
were over the runner's then-15 s per-namespace cap), so `jolt test-ext`
skips it while bb `test-ext` still runs it. Not
a terminal gap. `kmet.tui.test-render-loop` is green on `jolt test-ext`.

### The portable core (verified 2026-09-10, unchanged)

`jolt test` is green (2032 tests / 13803 assertions, `jolt
v0.8.6-18-g64bdeff4`): `kmet.libs.reakt`, `kmet.tui.{hiccup,macros,
protocols,keys,keybindings,utils,theme,border,timers}`, all 21
`kmet.tui.components.*` and the `kmet.app.ui.*` layer above them run on Jolt
as-is — nothing there imports JLine or any other JVM-only class. The
headless surface (`hiccup/render-lines`, `core/render`) is what the tests
drive, so steps 2–3 of §13 below were already done, and §10's "port"
column for the whole component set means *no work required*.

### History — what this file was written for

The sections below (§§1–9, 11–12, 14) are the design + reference material
for the port: architecture mapping (§1), the JLine surface being replaced
(§2), rendering (3), the FFI ground rules (§4 — worth reading before any
binding), raw mode Unix/Windows (§§5–6), the input pipeline (§7), key
parsing (§8), concurrency/host-shims (§9), what ports unchanged (§10),
packaging (§11), a sketch (§12), next steps (§13) and the evaluated-and-
rejected babashka.ffi variant (§14). The §§4–6 FFI notes were confirmed
against the real jolt checkout while implementing; §5 now shows the landed
shape, §6 is the Windows design still to implement.

---

## 1. Architecture overview

pi-tui / kmet separate three concerns. Only the third changes per platform:

| concern | pi-tui (Node) | kmet (bb/JVM) | jolt-tui (Jolt/Chez) |
|---|---|---|---|
| rendering | ANSI escapes to stdout | same — pure Clojure | same — port as-is |
| key parsing | `parseKey`, Kitty + legacy tables | `tui/keys.clj`, 0 Java interop | port verbatim |
| protocol knowledge | `terminal.ts` constants + negotiation | `libs/terminal.clj`, pure | port verbatim |
| raw mode + I/O | `stdin.setRawMode` + libuv | **JLine** (`terminal_jline.clj`) | **termios FFI** (`terminal_native.cljc`, Unix) + **kernel32 FFI** (Windows, open) |
| Windows VT input | `win32-console-mode.node` (`GetConsoleMode`/`SetConsoleMode` + `ENABLE_VIRTUAL_TERMINAL_INPUT`) | free via JLine | `defcfn` to `kernel32.dll` — same three calls |

kmet enforces this split: raw `\u001b` is banned outside `src/kmet/tui/`
and `src/kmet/libs/terminal.clj`, and the `ITerminal` protocol
(`terminal.clj`) is the sole seam between the portable core and the OS —
since the 2026-09-11 refactor it is a *total* seam (nothing outside a
backend touches a backend's private state), so a backend swap touches no
core code. The port kept ~12k lines of `reakt` + `hiccup` + components +
`utils` + `keys` + `libs.terminal` and replaced the ~240-line adapter
with two backends behind the protocol.

---

## 2. Why kmet uses JLine, and what "replace with raw mode" means

In kmet, JLine **is** raw mode plus portable I/O around it.
`terminal_jline.clj` is literally `(.enterRawMode t)`. Nothing else from
JLine is used — no `LineReader`, no completion; the editor is custom
(`tui/components/editor.clj`). This holds for the bb/JVM backend only: the
Jolt backend (`terminal_native.cljc`) supplies the same primitive set
(raw on/off, bounded reads, live size, writes) through libc. The full
surface the bb backend takes from JLine:

- **raw on/off + handle acquire**: `.enterRawMode`, `.reader`/`.writer`,
  `.close` (`terminal_jline.clj`).
- **timed reads**: `NonBlockingReader.read(timeout)` — a bounded `100ms`
  read plus a `1ms` drain batch (`core.clj`'s input reader), `.ready`/`.read`
  in the backend's `read-input` (`drain-input!` is backend-neutral in
  `terminal.clj`). A blocking read deadlocks close on aarch64 Linux (the
  deadlock note in `core.clj`, jline3 #1909).
- **size**: live `.getWidth`/`.getHeight` polled by the render loop — every
  ~16ms while frames are being requested, at most 100ms apart while parked
  (`core.clj` `IDLE-HEARTBEAT-MS`) — because WINCH signal handlers don't
  register under bb's GraalVM image; resize arrives via poll, not via
  callback.
- **portability**: `TerminalBuilder/terminal` opens `/dev/tty`, detects the
  terminal type, and handles the Windows console. Babashka bundles JLine
  4.4.0, so this costs zero extra deps.

Half the work is already manual: `run-stty`/`capture-stty-snapshot`
(`terminal_jline.clj`) saves `stty -g` before JLine construction because
JLine's FFM termios mapping writes baud `0` on construction and its own
restore leaves speed `0`.

Replacing JLine therefore means reimplementing, per platform:

1. raw on/off with save/restore on every exit path (normal, exception,
   shutdown hook — JLine's `.close` currently owns this);
2. timed/batched reads (else the close deadlock returns);
3. live size queries (subprocess `stty size` per poll is too heavy; want
   `ioctl(TIOCGWINSZ)` or a slow cadence);
4. the Windows console (`GetConsoleMode`/`SetConsoleMode` + VT-input flag) —
   the hard part JLine currently gives you for free.

On bb/JVM there is no reason to do this (re-solve solved bugs to save a
bundled dep). On Jolt there is no JVM, so it is mandatory — §§4–5 below.

Decision (2026-09-06, probed on bb 1.13.220 / libffi 3.8.0): JLine stays
the bb default — bundled 4.4.0, zero packaging cost, and the baud-`0` +
aarch64 close-deadlock workarounds already hold. `babashka.ffi` can express
the same §§4–6 bindings (translation in §14), but replacing JLine with it
is rejected: same ~1wk Unix + 2–4wk Windows effort as the Jolt adapter for
zero gain. The "opt-in FFI terminal alongside JLine as a port-validation
rig" this predicted is exactly what landed (2026-09-11): the FFI backend
is Jolt-only, behind the same protocol, and bb never loads it.

---

## 3. Rendering: portable, port as-is

All drawing is ANSI/VT sequences; Windows Terminal, iTerm2, GNOME Terminal
all accept them. kmet's render loop diffs lines and emits only changes
(`core.clj` frame loop); implement the same virtual-buffer diff or port the
function. Nothing here touches the OS:

```clojure
(print "\u001b[2J\u001b[H") (flush)   ; clear + home
(printf "\u001b[%d;%dH" r c) (flush)  ; move cursor
(print "\u001b[?25l") (flush)         ; hide cursor
(print "\u001b[?25h") (flush)         ; show cursor
```

Styling goes through `tui/theme.clj` (attribute-specific resets, never bare
`\u001b[0m`, so nested styles compose). Keep that rule.

---

## 4. FFI ground rules (read before writing any binding)

Jolt has no JVM and no `java.*`. The outside world is reached through
`jolt.ffi`: declare the library, bind each C function with arg/return type
keywords, marshal memory by hand. No GC of foreign memory; every `alloc`
needs a `free` on every path (prefer the `with-*` scoped macros).

```clojure
(ns my.tui.term
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; Outside a deps.edn project, load before first call:
;; (ffi/load-library)          ; process symbols (libc/POSIX)
;; (ffi/load-library "libsqlite3.dylib") ; one file
;; Inside a project, prefer deps.edn (next block).
```

`deps.edn` — shape per Native Interop guide + `jolt-core/jolt/deps.clj`:
per-OS candidate vectors tried in order (`:darwin`/`:linux`/`:windows` —
`deps.clj:964`; verify `:mac`-alias and `:optional` in the guide, they are
not in `deps.clj`); `:process` uses process symbols (libc/POSIX — no file —
`deps.clj:967-977`); `:static` bakes the archive into `jolt build`
(`deps.clj:1046`).
libc/POSIX needs no declaration at all — call `(ffi/load-library)` (or
`nil`) for the boot's global handle. Do NOT re-load it: re-loading
re-promotes the global handle above scoped `:jolt/native` handles — the
boringssl shadowing bug `ffi.ss:41-51,230-232,998-999` exists to end:

```clojure
{:jolt/native [{:name "kernel32" :windows ["kernel32.dll"]}]}
;; without deps.edn: (ffi/load-library {:darwin "…" :linux "…" :windows "…"})
;; (per-OS map; probe vector-vs-scalar + `ffi-candidate-list` on the checkout —
;; the guide's word for it), or
;; (ffi/load-library) / (ffi/load-library nil) for process symbols only.
```

Keep the candidates for `run`/`repl`; for `jolt build`, add `:static
{:archive "/path/to/libfoo.a"}` to bake the archive into the binary
(build needs only `cc` on `PATH`; running needs nothing). Details:
Native Interop guide, "Static vs dynamic linking".

Binding shape — both are MACROS (Chez needs types at compile time):
`defcfn` defs the binding (docstring/attr-map supported, plus a wrapper
form for out-params — `stdlib/jolt/ffi.clj:1410-1454`), `foreign-fn`/`cfn` (same
thing) expand inline. Signatures must be literal; trailing option is
`:blocking` or a literal `{:blocking … :capture-native-error …}` map
(`ffi.clj:1335-1380`):

```clojure
(ffi/defcfn c-strlen "strlen" [:string] :size_t)
;; :blocking emits __collect_safe so a parked thread does NOT pin the GC for
;; every thread. Mark anything that can wait (read/recv/sleep/lock).
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t :blocking)
;; capture variant: [result errno] atomically (non-void scalar results only):
(ffi/defcfn c-read-cap "read" [:int :pointer :size_t] :ssize_t {:blocking true :capture-native-error true})
```

Type keywords (`ffi.clj:19-29` — the doc header lists the full set;
`(alloc n)` + `free`, arena-owned `(alloc arena n)` / `confined-arena`
(one thread) / `shared-arena` (any thread) closed by `close-arena` or
`with-arena`): `:int :uint :long :ulong :int64 :uint64 :size_t
:ssize_t :iptr :uptr :double :float :pointer :string :bool :void :uint8
(`:u8`/`:byte`) `:char`, plus exact widths `:int8`/`:i8 :int16`/`:short
:uint16`/`:ushort :int32 :uint32`. `:bool` is one-byte `_Bool` (jolt
truthiness on the way out). `:string` carries nil↔NULL in both directions
(`""` still allocates); false in a `:string` position is rejected. **A jolt
pointer is a raw address integer** — no bounds checks; `size` answers only
what jolt was told. Memory is ZEROED on alloc. Out-params use a scoped
cell:

```clojure
;; sqlite3_open(path, &db) pattern — the shape of every GetConsoleMode call:
(defn open-db [path]
  (ffi/with-out [pp :pointer]
    (let [rc (sqlite3-open path pp)]
      (when-not (zero? rc)
        (throw (ex-info (str "open failed: " path) {:rc rc})))
      (ffi/read pp :pointer))))
```

Memory (caller-owned `(alloc n)` + `free` (`ffi.clj:738-798`), arena-owned
`(alloc arena n)` / `confined-arena` (one thread) / `shared-arena` (any
thread) closed by `with-open`-style `close-arena` (`ffi.clj:605-630)):
Scopes returning the body value: `with-alloc`, `with-out`, `with-layout`,
`with-c-string`, `with-c-string-array`. `sizeof` / `alignof` take a keyword
or compiled layout. `read`/`write`: **value BEFORE offset** (`(write p t v)`
/ `(write p t v off)` — babashka.ffi order). No `ffi/copy` confusion:
`read-array`/`write-array` move scalar arrays element-wise (one-byte
widths as one block copy); `read-bytes`/`write-bytes` encode/decode UTF-8;
`read-into!` fills an existing buffer (streaming reads); `byte-buffer`
shares memory zero-copy. `layout` takes a LITERAL descriptor
(macros need it at compile time — `(layout d)` on a runtime value has
nothing to compile); unions `[:union …]` read as a pointer to the bytes.
`string->ptr`/`ptr->string` round-trip nil. `null`/`null?` (host-provided),
`loaded?`, `defining-libraries` (duplicate-symbol probe — RTLD_LOCAL keeps
natives apart; `ffi.ss` scoped-loader section). errno: `(ffi/errno)` immediately after the
failing call (allocation/park/FFI in between may overwrite),
`(ffi/errno-message e?)`.

OS detection uses the shimmed `System/getProperty` / `System/getenv`
(`host-static-methods.ss`: `os.name` answers `"Mac OS X"` / `"Windows"` /
`"Linux"` from Chez's machine-type; `getenv` with no args returns the whole
map, one arg the value or nil; `java.io.tmpdir` answers `$TMPDIR` else `/tmp`
— still pass an explicit dir, kmet's `libs.http/temp-dir` pattern):

```clojure
(defn windows? []
  (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "win"))
```

Notes and traps:

- A `defcfn` resolves against declared natives first, process-global
  fallback second — a system lib can't shadow your binding.
- Variadic marker is `:&` (`:varargs` is the older spelling, same thing —
  `ffi.clj:1337`): `(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int)`,
  bare `(ffi/defcfn c-open "open" [:string :int :&] :int)` infers the tail
  per call (first call of a new shape compiles, ~0.8ms, then cached).
  Load-bearing on Apple arm64 (variadics travel on the stack — fixed-arity
  bindings hand garbage). Neither form combines with `:blocking`
  (`ffi.clj:272`). C promotions apply past the marker: pass sub-int values
  as `:int`, float as `:double` (incl. `:bool`).
- Callbacks into Jolt (a `qsort` comparator, a signal handler) use
  `ffi/foreign-callable` (macro) + `ffi/free-callable`, or arena-owned
  `ffi/callback` (no explicit release). `:collect-safe` goes on the
  CALLABLE when C invokes it from a thread jolt never started OR from a
  jolt thread parked in a `:blocking` call (e.g. GUI main loop); omit it
  for same-thread callbacks (qsort comparator) — it costs an activation
  per call. Without it on a foreign/parked thread the process dies with a
  nonrecoverable memory fault (`ffi.clj:1489-1520`). `export!` (`ffi.clj:1541-1555`)
  publishes entry points for `jolt build --library` (resolved via
  `jolt_lookup` after `jolt_library_init`; verify threading constraints on
  the checkout before relying on them).
- Windows caveat: `process.ss:20` notes the FFI surface is missing on some
  Windows machine types (Chez `open-process-ports` fallback there — verify
  which types on the checkout). Test a `kernel32` `defcfn` on real Windows
  early; declare
  the dll under `:windows` (or `load-library` the `{:windows …}` map). Fallback is a tiny C helper in `native/` baked
  via `:static {:archive …}` — the same role as pi-tui's vendored
  `win32-console-mode.node` (3 KB, one function). (`:jolt/native` platform keys
  are `:darwin`/`:linux`/`:windows` — `deps.clj:964`; natives load RTLD_LOCAL
  and resolve per-handle — `ffi.ss:227-268` — so the boringssl shadowing
  (`ffi.ss:41-51`) can't recur; `defining-libraries` names duplicates.)

---

## 5. Raw mode: Unix via termios FFI

Chez (hence Jolt) has no `tty-mode-set!` — that procedure is Gambit (Marc
Feeley's `tty-mode-set!` mails describe the Gambit API). On Chez you drive
`termios` yourself, exactly like the Chez raw-input gist does with
`foreign-procedure`: `tcgetattr` → `cfmakeraw` → `tcsetattr`, restore on
exit. In Jolt the `foreign-procedure` step is `defcfn` against process
symbols:

```clojure
(ns my.tui.unix
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn c-tcgetattr "tcgetattr" [:int :pointer] :int)
(ffi/defcfn c-tcsetattr "tcsetattr" [:int :int :pointer] :int)
(ffi/defcfn c-cfmakeraw "cfmakeraw" [:pointer] :void)

(def STDIN-FD 0)
(def TCSANOW 0)
(def TCSAFLUSH 2)
;; Opaque termios buffer: big enough for Linux (60B) and macOS (~72B).
(def TERMIOS-SIZE 256)

(defonce saved-termios (atom nil))

(defn enable-raw! []
  ;; Save cooked state once; build raw from a byte copy so restore is exact.
  ;; NOTE: write takes VALUE before offset: (ffi/write p t v) / (write p t v off).
  ;; Prefer with-alloc so a throw between alloc and try can't leak: with-alloc
  ;; frees exactly once however the body ends (`ffi.clj:1275-1281`).
  (ffi/with-alloc [saved TERMIOS-SIZE]
    (ffi/with-alloc [raw TERMIOS-SIZE]
      (when-not (zero? (c-tcgetattr STDIN-FD saved))
        (throw (ex-info "tcgetattr failed" {:errno (ffi/errno)})))
      (ffi/copy saved raw TERMIOS-SIZE)
      (c-cfmakeraw raw)
      (when-not (zero? (c-tcsetattr STDIN-FD TCSAFLUSH raw))
        (throw (ex-info "tcsetattr (raw) failed" {:errno (ffi/errno)})))
      ;; Steal the saved block out of the scope: it must survive until
      ;; restore-cooked!, so forget both frees by copying to a caller-owned
      ;; block. (Simpler alternative: one caller-owned (ffi/alloc) for saved
      ;; + with-alloc for raw — same guarantee, less copying.)
      (let [kept (ffi/alloc TERMIOS-SIZE)]
        (ffi/copy saved kept TERMIOS-SIZE)
        (reset! saved-termios kept)
        true))))

(defn restore-cooked! []
  (when-let [saved @saved-termios]
    (c-tcsetattr STDIN-FD TCSAFLUSH saved)
    (ffi/free saved)
    (reset! saved-termios nil)))

(defn with-raw-mode [f]
  ;; dynamic-wind equivalent: raw in, cooked out on every path.
  (enable-raw!)
  (try (f)
       (finally (restore-cooked!))))
```

`ffi/errno` reads the calling thread's slot (`__errno_location` /
`__error` / `_errno` per OS — `ffi.clj:1566-1575`), correct under threads and
fibers; read it IMMEDIATELY (an alloc/park/FFI call in between may
overwrite). `ffi/errno-message` renders via `strerror`. `try`/`finally`
and `ex-info` are portable. (Also: `tcsetattr`'s fd param is the same
`:int`; `cfmakeraw`'s arg is `:pointer` to the struct bytes — opaque buffer
is fine, no `layout` needed.)

Reads: raw mode makes bytes available immediately, but `read-char`-style
port reads still buffer. The landed backend never parks a blocking read:
`read-input` polls first (`poll(2)` with the caller's timeout, then one
`read(2)` into a 1KB buffer), so the reader thread wakes on the timeout
and re-checks the stop flag — `stop!` needs no wakeup byte or fd close.
Both calls are `:blocking` (they run off the fiber carrier):

```clojure
(ffi/defcfn c-poll "poll" [:pointer :ulong :int] :int :blocking)
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t :blocking)

(def pollfd-layout
  (ffi/layout [:struct [[:fd :int] [:events :short] [:revents :short]]]))

(defn read-once!
  "Poll FD for at most TIMEOUT-MS, then read one bufferful."
  [fd timeout-ms]
  (ffi/with-alloc [pfd (ffi/sizeof pollfd-layout)]
    (ffi/write-field pfd pollfd-layout [:fd] fd)
    (ffi/write-field pfd pollfd-layout [:events] 1)  ; POLLIN
    (when (pos? (c-poll pfd 1 timeout-ms))
      ;; POLLIN → read; POLLERR|HUP|NVAL (0x38) → a gone pty, back off.
      (let [revents (ffi/read-field pfd pollfd-layout [:revents])]
        (when (pos? (bit-and revents 1))
          (ffi/with-alloc [buf 1024]
            (let [n (c-read fd buf 1024)]
              (when (pos? n) (ffi/read-array buf n)))))))))
```

UTF-8 is reassembled across read boundaries (`utf8-decode` keeps the
partial tail per terminal — a read boundary is not a character boundary),
and a dead pty (`POLLHUP`/`POLLERR`/`POLLNVAL`) backs off instead of
spinning. Feed one burst per pass — kmet's reader drains everything
already queued behind the first char (`core.clj`) so a multi-byte sequence
never straddles a scheduling stall byte-by-byte (that stall is what flushed
phantom Escapes and leaked `[200~` as text); the same `read-input` bound
serves drain-on-exit.

Size: no subprocess. `ioctl(TIOCGWINSZ)` answers, with a `COLUMNS`/`LINES`
env fallback (pi: `process.stdout.columns || env || 80/24`). The landed
shape (`terminal_native.cljc`):

```clojure
;; winsize is 4× unsigned short {ws_row, ws_col, ws_xpixel, ws_ypixel} —
;; rows = uint16 @0, cols = uint16 @2. ioctl is variadic (int fd, unsigned
;; long request, ...): bind the bare-:& form and pass the out-pointer as
;; the tail. os.name answers "Linux" / "Mac OS X" / "Windows" (match
;; strings, not keywords) — the cond only needs the Mac branch.
(def tiocgwinsz
  (if (str/includes? (str (System/getProperty "os.name")) "Mac")
    0x40087468
    0x5413))

(ffi/defcfn c-ioctl "ioctl" [:int :ulong :&] :int)
(ffi/with-alloc [ws 8]
  (when (zero? (c-ioctl 0 tiocgwinsz ws))
    {:rows (ffi/read ws :uint16) :cols (ffi/read ws :uint16 2)}))
```

---

## 6. Raw mode + VT input: Windows via kernel32 FFI

Windows has no `termios`. Call the console API directly — these are the same
three calls as pi-tui's `win32-console-mode.c`
(`GetStdHandle`/`GetConsoleMode`/`SetConsoleMode`):

```clojure
(ns my.tui.win
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn Win-GetStdHandle   "GetStdHandle"   [:int] :pointer)
(ffi/defcfn Win-GetConsoleMode "GetConsoleMode" [:pointer :pointer] :int)
(ffi/defcfn Win-SetConsoleMode "SetConsoleMode" [:pointer :uint] :int)

(def STD-INPUT-HANDLE -10)
(def ENABLE-VIRTUAL-TERMINAL-INPUT 0x0200)
(def ENABLE-LINE-INPUT 0x0002)
(def ENABLE-ECHO-INPUT 0x0004)

(defonce saved-mode (atom nil))

(defn get-mode [handle]
  ;; with-out allocates ONE scalar of the type (`ffi.clj:1283-1287); the form
  ;; answers the BODY's value, so read the cell inside.
  (ffi/with-out [m :uint]
    (when (pos? (Win-GetConsoleMode handle m))
      (ffi/read m :uint))))

(defn enable-vt-input! []
  ;; Makes the console emit VT sequences (e.g. \u001b[Z for Shift+Tab).
  ;; pi runs this AFTER setRawMode(true): raw mode resets console flags.
  (let [h (Win-GetStdHandle STD-INPUT-HANDLE)]
    (when-let [mode (get-mode h)]
      (Win-SetConsoleMode h (bit-or mode ENABLE-VIRTUAL-TERMINAL-INPUT)))))

(defn enable-raw! []
  (let [h (Win-GetStdHandle STD-INPUT-HANDLE)]
    (when-let [mode (get-mode h)]
      (reset! saved-mode mode)
      ;; clear LINE + ECHO; OR in VT-INPUT in the same step (ordering above)
      (Win-SetConsoleMode h (bit-or (bit-and mode
                                             (bit-not (bit-or ENABLE-LINE-INPUT
                                                              ENABLE-ECHO-INPUT)))
                                    ENABLE-VIRTUAL-TERMINAL-INPUT)))))

(defn restore-cooked! []
  (when-let [mode @saved-mode]
    (Win-SetConsoleMode (Win-GetStdHandle STD-INPUT-HANDLE) mode)
    (reset! saved-mode nil)))

(defn set-raw-mode! [on?]
  (if on? (enable-raw!) (restore-cooked!)))
```

Combined entry (replaces the old draft's `os-type`/`tty-mode-set!` version):

```clojure
(defn set-raw-mode! [on?]
  (if (windows?) (win/set-raw-mode! on?)
      (if on? (unix/enable-raw!) (unix/restore-cooked!))))
```

Restore must run on every exit path (normal stop, exception, shutdown
hook) — a crashed TUI that leaves the console raw/echo-off is the classic
failure. pi-tui's helper is deliberately non-fatal when missing; keep that:
warn and continue with degraded keys, never crash startup.

After raw + VT-input are on, the byte stream is uniform across platforms
and one shared parser handles it (§8).

---

## 7. The input subsystem the minimal loop misses

`read-char` + a 5-case `case` (up/down/left/right/shift-tab) will appear to
work and then corrupt keys in production. `core.clj`'s machinery exists for
observed bugs — and it is shared code, already running on both hosts; the
backend's only job is to feed it bytes. The behavior to preserve:

1. **Kitty negotiation + fallback** (`libs/terminal.clj`, `core.clj`
   interceptors). Send `\u001b[>5u\u001b[?u\u001b[c` (flags 1+4 —
   event types are deliberately not requested; see the flag block in
   `libs/terminal.clj`); consume the reply
   (`\u001b[?Nu` / `\u001b[>Nu` / DA `\u001b[?..c`) in the input path so it
   never dispatches as keys. Non-zero flags → Kitty; zero/DA → xterm
   `modifyOtherKeys` (`\u001b[>4;2m`). Flush held fragments after
   `NEGOTIATION-FLUSH-TIMEOUT-MS` (150ms) so a stalled reply is never
   swallowed forever. Prefix-hold rule matters: a bare `\u001b[` is NOT
   held (it's also an arrow-key prefix; holding it across a 50ms+ conpty
   split corrupts ctrl+arrows into Escape + text) — only unambiguous heads
   (`\u001b[?`+digits, `\u001b[>`) are.
2. **Terminal-response interception**. Cell-size (`\u001b[6;h;wt`, ungated),
   OSC-11 background (gated on an outstanding query), color-scheme report
   (`\u001b[?997;Nn`) are consumed before key parsing, with the same
   prefix-hold discipline (cell-size needs the `;` — bare `\u001b[6` also
   prefixes keys).
3. **Structural completeness gate** (`keys/complete-sequence?`). Dispatch an
   ESC sequence only when structurally finished (CSI/OSC/DCS/APC/SS3/mouse
   rules) AND recognized. A partial CSI prefix that happens to parse as
   `alt+[` must never dispatch early and swallow the rest.
4. **Leading-sequence scan + garbage drop**. The buffer can hold several
   sequences; dispatch the leading complete one, re-process the remainder.
   A complete-but-unrecognized leading sequence is dropped (Termux DA
   `\u001b[?64;..c`, unparsed Kitty push forms) — otherwise the buffer grows
   and swallows all later input ("frozen, keys dead, no crash log").
5. **Generation-guarded flush timers**. Lone ESC waits `ESCAPE-FLUSH-MS`
   (100ms — pi's 10ms orphans tails on WSL/conpty 50ms+ splits, leaking
   `[27;5;97~` as text); partial CSI waits `SEQUENCE-FLUSH-MS` (50ms).
   Fire only after true idleness (input-generation counter); re-check under
   the dispatch lock — a stale timer must neither dispatch a split
   sequence's head as Escape nor clear a fresh arm. Timers stay
   `future`+generation-counter first (`future-cancel` also interrupts a
   sleeping timer thread, §9 — use it as backup, never as the only guard).
6. **Pastes**. Bracketed-paste markers (`\u001b[200~`/`\u001b[201~`, mode
   2004) dispatch immediately with surrounding text kept in order.
   Unbracketed bursts (IME injection, tmux `send-keys`) arrive as raw
   bytes: a CR ending a paste-like burst (≥4 chars incl. the CR within
   100ms, at least one non-CR) is rewritten to `\n` so pasted `/cmd` text
   can't submit; the LF half of a rewritten CRLF is swallowed within 50ms.
   Bulk-insert runs (everything up to the first control/ESC) to keep big
   pastes O(n).
7. **Mouse/focus filtering**. Complete SGR/X10 mouse and `\u001b[I`/`O`
   focus sequences are recognized so partial fragments never leak as editor
   text (main-screen model: not enabled, still filtered).
8. **Listener chain + modality**. Listeners run first, each may `:consume`
   or return transformed `:data` (empty string drops the event). While a
   visible capturing overlay exists, input snaps to it before delivery;
   hidden/removed overlays never keep keys. Focus restore resolves from
   live state (topmost visible overlay → app-registered focus-home thunk →
   null drops at the guard) — no "previously focused" snapshot, plus a
   watch on the overlay stack as the unbypassable restore chokepoint
   (`::ghost-guard`). Key-release events are filtered unless the component
   opts in (`:wants-key-release?`).
9. **Drain on exit**. Disable Kitty + `modifyOtherKeys`, then drain pending
   input (≤1000ms, stop after 50ms idle) so late release sequences don't
   leak into the parent shell.

---

## 8. Key parsing: port verbatim

`src/kmet/tui/keys.clj` (499 lines, **0 Java interop**) ports unchanged in
logic; only check the regex engine (Jolt uses irregex — common patterns
work, some Java-specific features differ). Order matters (mirrors pi's
`parseKey`): Kitty CSI-u/arrows/functional → `modifyOtherKeys`
(`CSI 27;mods;code ~`) → Kitty-active mode remaps (`\u001b\r`, `\n` →
`shift+enter`) → legacy table (arrows, SS3 application-cursor mode,
shift/ctrl/alt-cursor, Emacs `alt+b/f/p/n`, `alt+enter/space/backspace`,
`shift+tab` = `\u001b[Z`, F1–F12 in all pi legacy forms) → `ESC`+ctrl →
`ctrl+alt+letter` / `alt+key` → ctrl singles → DEL/BS → `space` →
printable. `matches-key?` treats modifier order as insignificant.
`is-key-release?` / `is-key-repeat?` decode Kitty event types 3/2 and must
exclude bracketed paste content (MAC addresses contain `:3F`).

---

## 9. Concurrency + host-shim mapping

Verified against the checkout at `~/jolt` (`533b04a3`, 2026-09-08;
`host/chez/java/concurrency.ss`, `host-static-methods.ss`,
`host/chez/locks.ss`,
`jolt-core/clojure/core/30-macros.clj:129-130`). Carriers differ, so pick by
blocking shape: `future` = real OS thread, shared heap (blocking FFI,
`read(2)`, sleeps go here); fiber (`go`/`io-thread`/`jolt.fibers/spawn`) =
multiplexed carrier (channel ops and `deref` park, but a blocking FFI call
or `Thread/sleep` pins the carrier — `fibers.clj` header pins exactly
this). Mapping for `core.clj`'s idioms:

| kmet (bb/JVM) | Jolt |
|---|---|
| `future` body on thread pool | `future` on a real OS thread, shared heap — reader + timers translate directly |
| `(Thread/sleep ms)` | interruptible sleep on OS threads (same door as `TimeUnit.sleep`); on a fiber it pins the carrier — `fibers.clj` header (“park-capable waits — channel ops, deref, `jolt.socket`/`jolt.process` IO — are the ones to use inside a body”). Sleeps belong in `future`s, never in `go`/fiber bodies |
| `future-cancel` | real `cancel(true)` (`concurrency.ss:182-208`): marks cancelled+done (derefs throw `CancellationException`) **and interrupts the worker** — a thread parked in an interruptible wait (`Thread/sleep`, future/promise deref, `CountDownLatch`, blocking-queue ops) is thrown out promptly; running compute sees it via `Thread/interrupted` / `.isInterrupted`. Caveat: a thread blocked in a `__collect_safe` foreign call only sees the interrupt when it returns to Scheme (`concurrency.ss:1205-1206` — "like the JVM not killing native code"). So cancel *does* stop sleeping flush timers; it does *not* unblock a parked `read(2)` — `stop!` still needs a wakeup byte / fd close (§5) |
| `locking` | **present** (`clojure.core/locking` → `jolt.host/with-monitor` — `30-macros.clj:129-130` → `concurrency.ss:1170-1177`, per-object reentrant monitor with dynamic-wind release). NOT fiber-aware: `host/chez/locks.ss` exists precisely because an OS mutex across a fiber switch loses exclusion either way (unwind releases mid-section; no-unwind lets a carrier-mate walk in). `with-monitor` is that same monitor with a dynamic-wind release plus park-rewind handling — usable for `dispatch-lock`, but keep the body short, non-sleeping, and park-free; generation counters (§7.5) stay the primary stale-timer defense, cancel the backup |
| `System/getenv` | shimmed — keep (`KMET_*` flags translate to env reads) |
| `ProcessBuilder` + `stty` snapshot/restore | replace with §5 FFI (no subprocess on the hot path) |
| `StringBuilder` + `.append/.charAt/.length` | shimmed (`append/toString/length/charAt/setLength`) — keep, verify arities |
| `java.util.Base64` (OSC-52) | shimmed — keep |
| `Pattern/compile/quote/split`, `MULTILINE` | shimmed — keep, but re-run key/response regex tests under irregex |
| `LocalDateTime/now` log timestamps, `ProcessHandle/.pid` log names | shape differs: time values live behind the `io.github.jolt-lang/time` dep (already in `deps.edn` — `DateTimeFormatter`/`ZoneId`/`ZonedDateTime` shims); pid via the `ProcessHandle` shim (`.pid` — `process.ss:1009`) — verify call shapes on the checkout before porting log paths |
| `(io/writer path :append true)` + `with-open` + `.write` | reshaped, not absent: `jolt-io-writer` takes ONE arg (no opts — `io.ss:1314-1323`) and `spit` takes `:append` (`io.ss:1164-1195`; non-append `spit` is temp-file+rename, append writes in place — `io.ss:1173-1195`). Crash/write logs become `(spit path text :append true)`; `with-open` exists (`30-macros.clj:198`, closes via `__close`); `FileWriter`/`file-writer` persists on flush/close |
| `babashka.fs` (`directory?`, `cwd`, `file-separator`, …) | present via install roots + `jolt.bb.fs` supplement (supplement loads after `babashka.fs`; install copy always wins over a project copy); vendored sources carry no version constants — re-verify the pins by file comparison on any Jolt upgrade |
| `String.getBytes` (OSC-52 `libs/terminal.clj:257`) | present and charset-aware (`natives-str.ss:512-513` — `.getBytes` with/without charset; shared codec at `natives-str.ss:315-318`) — keep, or use `ffi/write-bytes` |
| `clojure.java.io` (`reader`/`writer`/`file`/`input-stream`/…) | present as vars (`io.ss:1375-1377` + `io-streams.ss:832-833,857` — note: `writer`/`input-stream`/`output-stream` are defined in BOTH files, streams file loads after and wins; `resource` takes an optional ignored loader arg — `io.ss:1444-1458`) — keep call sites, verify arities |

STM (`ref`/`dosync`/`alter`) is present; agents are real async (per-agent
serialized dispatch on worker threads, `await`/`await-for`,
`shutdown-agents` gate; `send-via` behaves as `send`). Neither is the timer
mechanism — timers stay `future` + generation counters.

---

## 10. What ports unchanged, what gets rewritten

| namespace | verdict |
|---|---|
| `libs.terminal` (Kitty/OSC constants, negotiation + response parsing) | port logic verbatim; `Base64` shimmed (keep or use `ffi/write-bytes`); log-file names need no `LocalDateTime` (format manually or drop the timestamp) |
| `tui.keys`, `tui.keybindings`, `tui.utils` (width/wrap/truncate), `libs.reakt`, `tui.hiccup`, `tui.macros`, `tui.protocols`, `tui.border`, `tui.timers`, all `tui.components.*` | **already green on Jolt, unchanged** (§0) — the `add-watch`-on-atom and regex spots checked out; `test-border`/`test-timers`/`test-hiccup`/`test-track`/`test-reakt-integration` run as-is |
| `tui.theme` | portable — already polls (`theme.clj:625-647`: "babashka.fs has no watcher"); keep the poll, keep `java.nio` out per the AGENTS.md rule |
| `tui.terminal` (240 lines) | **rewrite** per §§4–6 behind the same `ITerminal` protocol — **done 2026-09-11**: the protocol is now lean and total, the JLine half moved to `terminal_jline.clj`, and the Jolt half is `terminal_native.cljc` (Unix; Windows open) |
| `tui.core` input half + start/stop/resize/drain | **reimplement** per §§5–7,9 (reader thread, poll-based resize, generation-guarded timers, restores) — **done**: the reader loop calls `read-input` and the loop polls `columns`/`rows`; no JLine types remain in `core.clj` |
| `tui.core` render half (diff, overlays, flashes, Kitty-image ranges, crash/debug logs) | port logic; retarget logging to portable I/O |

Suggested order (revised in §0 — the headless half is already green):
the `ITerminal` adapter (§§5–6), then the input transport (§7), then the
render loop's terminal I/O, then mouse. Validate interactively with the
tmux/pty capture scripts (`scripts/tmux_capture.sh`, `pty_capture.py`,
`term_dump.py` equivalents). The headless path already pins the idle-UI
invariant on both hosts: no state change ⇒ zero fn bodies / reaction
re-runs (`hiccup/render-lines`, no tty, no sleeps).

---

## 11. Packaging

- Unix/macOS: no extra runtime deps — libc/`termios` are process symbols.
- Windows: no extra DLLs — `kernel32.dll` is always present; bind directly.
- Optional tiny C helper (pi-tui's `win32-console-mode.node` pattern): only
  if the Windows FFI surface proves missing (§4 caveat); ship via
  `:jolt/native` `:static {:archive …}` so `jolt build` cc-links it in.
  (Verify the `run`-with-`:static`-only behavior on the checkout before
  relying on it.)
- `jolt build` gives the single self-contained executable (runtime + app +
  static natives); linking needs Chez's kernel dev files (`libkernel.a`,
  `scheme.h`) + `cc` — both ship with the prebuilt jolt binary, NOT with
  distro `chezscheme` packages (per README).
  **Verified and automated on Unix.** `kmet.tasks.build-jolt` (the `jolt
  dist` branch) drives `jolt build -m kmet.core` and bakes the app in:
  `deps.edn` `:jolt/build {:embed ["src" "target/kmet-version"]}` carries
  the model catalogs and the built-as version, and the artifact lands in
  `dist/kmet-<ver>-jolt<jv>-<platform>`. The native FFI terminal (raw
  mode, reads, size, bracketed paste) works inside the AOT image, and the
  model catalogs load from the embedded resources. One build hazard to
  know: with the `io.github.jolt-lang/time` git dep on the classpath, a
  *minimal* closure can die at startup on
  `unbound fn jolt.time.impl/register-type!` (the jolt#944 build-time gap;
  `perf.md` records it) — kmet's own graph resolves a `java.time` class
  while the build analyzes, and an early `(:require [jolt.time])` in the
  entry namespace is the workaround if that ever stops being true.
- `jolt-lang/glimmer-tui` (evaluated 2026-09-06): the one Jolt terminal lib
  — terminal backend for `glimmer`, painting through `ncursesw` via
  `jolt.ffi` (ncurses 6.0 subset only; Unix-only per its `:jolt/native`
  entries). Requires jolt ≥0.7.24 (older jolts exported the kernel's own
  ncurses symbols, so an FFI-loaded ncursesw bound back into them →
  `initscr` "Error opening terminal" or segfault — `--exclude-libs`
  (`build.ss:386-411`, verified in-tree) keeps them out).
  Rejected as the kmet backend: fullscreen `initscr` takeover vs the inline
  ANSI/scrollback model, ncurses `wgetch` codes vs Kitty/modifyOtherKeys/
  OSC/2026-sync/images, indexed colour only, no bracketed-paste decode,
  macOS mouse ABI v1 has no wheel-down, and `initscr` exits the process on
  failure. Steal its FFI idioms (`setlocale` LC dance,
  `setupterm`-before-`initscr` probing, `raw`/`noecho`/`keypad`/`wtimeout`,
  MEVENT offsets, per-platform mouse shift) and its screen-map headless-test
  pattern — not the backend.
- ncurses ships nowhere *with* Jolt: Linux links it dynamic (`-lncurses
  -ltinfo`; minimal containers may need `apt install libncursesw6`), macOS
  uses the dyld shared cache, Windows (`ta6nt`) links none (cross kernels
  are often `--disable-curses` — Console API instead), Termux satisfies the
  Linux candidates from the OS lib.

---

## 12. Minimal end-to-end sketch (corrected API)

```clojure
(ns my.tui.main
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]
            [my.tui.term :as term]     ; set-raw-mode! from §§5–6
            [my.tui.keys :as keys]))   ; ported §8 parser

(defn clear-screen [] (print "\u001b[2J\u001b[H") (flush))
(defn hide-cursor [] (print "\u001b[?25l") (flush))
(defn show-cursor [] (print "\u001b[?25h") (flush))

(defn run-tui []
  (clear-screen)
  (hide-cursor)
  (print "Press 'q' to quit") (flush)
  (term/set-raw-mode! true)
  (try
    (loop []
      ;; real port: this read is the §5 reader future feeding the §7 buffer;
      ;; shown inline here only to keep the sketch small.
      (let [k (keys/read-key-blocking!)]
        (cond (= k "q") :quit
              (= k "escape") (recur)
              :else (do (println (str "\r\nkey: " k)) (recur)))))
    (finally
      (term/set-raw-mode! false)
      (clear-screen)
      (show-cursor)
      (println "Goodbye!"))))
```

`read-key-blocking!` above is a placeholder for the §7 pipeline
(reader bytes → negotiation/response interceptors → structural buffer →
`parse-key`), not a bare `read-char`.

---

## 13. Next steps

1. **Windows backend** (§6): `GetStdHandle`/`GetConsoleMode`/
   `SetConsoleMode` + `ENABLE_VIRTUAL_TERMINAL_INPUT`,
   `WaitForSingleObject` + `ReadFile`, `GetConsoleScreenBufferInfo` —
   `terminal_native.cljc`'s Windows error is the landing spot.
2. **Mouse tracking** — the enable/disable sequences are not implemented;
   the key parser already recognizes and filters mouse events (§7.7).
3. **Jolt-host pty app smoke** — `modes.test-overlay-input-smoke` spawns
   `bb run` and is `^:bb-only`; a Jolt-host variant is the follow-up.

---

## 14. Appendix: the same §§4–6 approach on Babashka (evaluated, not adopted)

Technically possible — probed 2026-09-06 on Termux (glibc bb 1.13.220,
`libffi` 3.8.0): variadic `ioctl` binds and calls, `tcgetattr` binds,
thread-local errno readable via `__errno_location` + `strerror` (returned
25 `ENOTTY` correctly), `read` binds, `GetConsoleMode` resolves to nil on
Linux without crashing. API translation (`jolt.ffi` → `babashka.ffi`, per
the `babashka/ffi` guide):

- `alloc` *requires* an arena (`confined` one thread / `shared`
  cross-thread / `global` process lifetime), released with `with-open` —
  no `free`/`with-alloc`. Never pass confined segments across threads.
- No `:blocking` annotation and no `errno` primitive: read errno manually
  on the same thread immediately after the call (`__errno_location` glibc /
  `__error` macOS / `_errno` Windows) — fine for startup checks, fiddly for
  per-read `EAGAIN`/`EINTR` classification.
- Variadic `ioctl`/`fcntl` via `:&` always go through libffi (~1µs —
  irrelevant at the loop's poll rates); plain ≤6-arg signatures hit the trampoline set
  (~30ns). `stop!` still needs a wakeup-byte/fd-close: `future-cancel`
  cannot unblock a parked native `read`, same caveat as §9, undocumented here.
- Windows type traps: `:long`/`:ulong` are always 64-bit but C `long` /
  `DWORD` is 32-bit → use `:int`/`:uint`; `:bool` is 1-byte `_Bool` but
  `BOOL` is 4-byte → use `:int`; `HANDLE` → `:pointer`. Callbacks via
  `ffi/callback` are arena-owned (≤4 args / 2 doubles, or 6 int/pointer; no
  `:float`; return `:void`/int/`:pointer`/`:double`) and must never let
  exceptions escape.
- Floors: `babashka.ffi` is experimental; the musl/fully-static fallback
  binary has **no libffi** (hard failure where today's binary still runs);
  JVM runs will eventually need `--enable-native-access` (JEP 472 `deny`
  default). Concepts transfer to Jolt; code does not (arenas vs `free`, no
  `:blocking`, different callback form). Any prototype validates against the
  §7 pipeline with the pty capture scripts (`scripts/pty_capture.py` +
  `term_dump.py`).

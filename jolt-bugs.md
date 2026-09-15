# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet or the
removal of a kmet workaround. Closed and
unfiled findings are not tracked here. `jolt-port.md` / `jolt-tui.md` describe
port state without ticket IDs.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

### [jolt#1011](https://github.com/jolt-lang/jolt/issues/1011) — `Object.wait` / `.notify` / `.notifyAll` missing on every object

**Area:** host method tables — `java.lang.Object`. `locking`/monitor-enter is
real (per-object, reentrant, fiber-aware) and deliberately uninterruptible like
the JVM, but the wait/notify family is absent on every object.
`monitor-wait!` in `host/chez/java/concurrency.ss` is the monitor-ENTER
contention wait, not `Object.wait`. Recorded upstream as a deliberate gap ("NOT
a divergence, and recorded so it does not read as an oversight") in
`test/conformance/known-divergences.edn` and `test/chez/unit.edn`. Verified on
`v0.8.8-4-g2039710e` (2026-09-15):

```clojure
;; succeeds on bb/JVM, fails on jolt:
(let [o (Object.)]
  (locking o (.wait o 10)))
;; jolt: IllegalArgumentException: No matching method wait found taking 1 args
;;       for class java.lang.Object  (the no-arg/notify forms report a field)
```

**Workaround:** `src/kmet/tui/wake.cljc` — the render loop's park/wake
primitive (tui.md §6). Each function is `#?(:jolt … :default …)`: the object
monitor on bb/JVM, a capacity-1 `LinkedBlockingQueue` binary semaphore on jolt.

| API | bb/JVM (`:default`) | jolt |
|---|---|---|
| `make-waker` | `(Object.)` | `(java.util.concurrent.LinkedBlockingQueue. 1)` |
| `wake!` | `(locking w (.notifyAll w))` | `(.offer w :wake)` |
| `park!` | `(locking w (.wait w timeout-ms))` | `(.poll w timeout-ms TimeUnit/MILLISECONDS)` |

Both hosts keep one contract because `park!` re-runs the caller's recheck before
blocking (under the monitor): the request flag / batch-queue state is read
there, so a wakeup racing the park is consumed, never lost — a monitor
`notifyAll` with nobody waiting is dropped, the queue would remember it, and the
recheck makes the two behave identically. Consumers: `kmet.tui.core`
(create-tui's waker; `tui-request-render` sets the flag then wakes; the loop's
`work-pending?` recheck and `idle-park-ms` timeout) and
`test/kmet/tui/test_wake.clj`.

No RFC 0014 provider shim: `java.lang.Object` is runtime-implemented, so the
claim is refused, and the member cannot be backed by a registration — jolt's
object monitors (`object-monitor`, `monitor-enter!`/`monitor-exit!`, the waiter
list, the condition variable) are internal Scheme in
`host/chez/java/concurrency.ss`, and the only exposed piece
(`jolt.host/with-monitor`) is enter/exit with no wait/notify to call. A
registration that cannot release and reacquire the monitor would have wrong
semantics (a naive `.wait` = `Thread/sleep` inside the monitor would hold it and
block the notifier), so the host-conditional workaround is the honest fix.

**Removal:** replace each `#?(:jolt X :default Y)` in `src/kmet/tui/wake.cljc`
with just `Y` (the monitor body) and drop the ns docstring's workaround
sentence — the API and its callers do not change; then delete this entry and
the now-false pointers to the gap in `jolt-port.md` §9, `jolt/README.md`,
`tui.md` (§1 table and §6) and the `test/kmet/tui/test_wake.clj` docstring.
Verify on jolt: the repro above prints; `jolt test` (`kmet.tui.test-wake`
covers wake, timeout and the recheck contract on whichever branch compiles); a
pty smoke of `jolt run -m kmet.core` (a key echoes immediately, an idle
terminal repaints only on input/timers, a resize reflows within the
heartbeat). `bb` behavior must not change (it already used the monitor branch).

### [jolt#992](https://github.com/jolt-lang/jolt/issues/992) — Windows: `jolt C:/…/file.clj` is read as project-relative — `open-input-file` fails for `./C:/…`

**Area:** `jolt-core/jolt/main.clj` (`file-arg`)

`file-arg` (main.clj:320-327) recognizes POSIX absolutes only — `"-"`,
`(str/starts-with? x "/")`, `(str/starts-with? x "./")` — so every drive-absolute
argument lands in the `:else` join `(str (project-dir) "/" x)`. `project-dir`
is `JOLT_PWD` or `"."` (main.clj:9), so the prebuilt binary tries
`./C:/Users/x/hello.clj` and both `jolt <file>` and `jolt run <file>` fail with
`open-input-file: … invalid argument`, from any cwd and for either separator
spelling. Relative and `./`-relative arguments work, which is why it is easy to
miss.

Same Windows path family as jolt#991 (the canonicalize half); both want the
classification `jolt.deps/native-path-kind-for` already does
(deps.clj:195-214: drive-absolute `C:/x`, UNC `//server/share`, root-relative
`/x`, drive-relative `C:x`), or `io.ss`'s `jfile-abs`.

**Workaround:** none needed in kmet — kmet drives jolt through `run`/tasks, not
`jolt <absolute file>`; a user hitting it can pass a cwd-relative path
(`jolt Users/x/hello.clj` from `C:\` works). Removal: nothing to delete when
`file-arg` stops joining a drive-absolute path to `project-dir`.

### [jolt#991](https://github.com/jolt-lang/jolt/issues/991) — Windows: `File.getCanonicalPath` prepends `/` to a drive-absolute path — and the value it returns cannot be used for I/O

**Area:** `host/chez/java/io.ss` (`jfile-canonical`, the no-`realpath` fallback)

`realpath(3)` is unbound on Windows, so `jfile-canonical` falls back to
`path-parent` / `jfile-fold-dots` — POSIX models that split on `/` only, keep
`C:` as an ordinary segment, and rejoin every segment as `"/" + segment`.
`File.getCanonicalPath`, `File.getCanonicalFile` and `babashka.fs/canonicalize`
therefore answer `/C:/Users/…` for every spelling of a drive-absolute path
(backslash input has no recognised separator at all), and any read or write
with the returned value fails against `C:/C:/…`. Two spellings of the same file
also canonicalize to different strings, so the identity comparison the realpath
switch exists for (jolt#693) still fails on Windows.

kmet hits it on every session write: `kmet.app.session/create-session` stamps
the session file with `(str (fs/canonicalize file))`, and print mode dies before
the first token with `open-output-file: failed for
C:/C:\Users\…\.kmet\sessions\….ednl.tmp.spit-tmp-…: invalid argument`.

**Workaround:** none in kmet — every `babashka.fs/canonicalize` consumer is hit
(session-file identity, package/extension path identity); the
`{:nofollow-links true}` spelling dodges the fallback but drops symlink
resolution, so it is not a drop-in. Removal: nothing to delete when the fallback
preserves the drive/UNC root and folds both separators — the call sites work
unchanged; verify with
`jolt -e '(println (str (babashka.fs/canonicalize "C:/…")))'`, then a Windows
`jolt run -p`. (The sibling file-argument defect is jolt#992.)

### [jolt#989](https://github.com/jolt-lang/jolt/issues/989) — Windows: a `:jolt/native` spec with no `:windows` candidates searches for nothing — and the diagnostic can't say so

**Area:** native loading / `:jolt/native` platform keys (Windows)

`jolt.main/load-natives!` (`jolt-core/jolt/main.clj:75-99`) reads candidates
from exactly one key — the one `current-platform` selects. On Windows a spec
declaring only `:darwin`/`:linux` yields `cands []`, so `some` returns nil
without a single `jolt.ffi/load-native` call, and the message says
`not found — tried [] for windows` about a search that never happened. The
libraries are not missing: `jolt-lang/crypto`, `jolt-lang/jolt-crypto` and
`jolt-lang/http-client` declare no `:windows` candidate, so the DLLs (beside
`jolt.exe`, or Git for Windows on PATH) are never asked for. Strict entry
points throw before app code runs (`jolt -e`, `run -m`, `build`); the task
path warns on every invocation. The issue carries a `user32.dll` minimal repro
(always in `system32`, still "not found") and the full code trace.

kmet hit it as three warnings from `jolt run -p 'say hello'` (crypto, ssl, z)
and a fatal `jolt -e`. An app cannot just add the missing key: the project's
own `:jolt/native` is first through the dedup (`jolt.deps` `:natives`,
deps.clj:1794, keyed on `:name` by `native-key`, deps.clj:1000), so a
windows-only project entry would dedup the deps' linux/darwin specs away and
break those platforms instead.

**Workaround** (`deps.edn`): the project-level `:jolt/native` block declaring
`crypto`/`ssl`/`z` — the upstream `:darwin`/`:linux` candidate lists mirrored
verbatim (mandatory, see the dedup above) plus the Windows names jolt's own
Maven fetcher already uses (`stdlib/jolt/mvn_http.clj` crypto-names /
ssl-names): `libcrypto-3-x64.dll`, `libssl-3-x64.dll`, `zlib1.dll`. The DLLs
are environment, not repo code — Git for Windows supplies them (on PATH), and
the loader also finds them beside `jolt.exe` (executable-directory search).
Verified on v0.8.7/Windows 11: the three warnings disappear, a strict
`jolt -e` in the checkout runs, and OpenSSL works (`MessageDigest` SHA-256
through the loaded DLL). Removal: delete the `:jolt/native` block from
`deps.edn` and re-run `jolt -e '(println :ok)'` on Windows with the DLLs
present — no complaint means the loader/library side has caught up. (`z` may
outlive the jolt fix — its gap is the `zlib1.dll` name, which no generic
spelling/glob fallback can derive; filed as jolt#990 below.)

### [jolt#990](https://github.com/jolt-lang/jolt/issues/990) — `crypto` / `ssl` / `z`: declare `:windows` candidates for the OpenSSL and libz natives

**Area:** `:jolt/native` declarations (`jolt-lang/crypto`, `jolt-lang/jolt-crypto`, `jolt-lang/http-client`)

The libraries kmet resolves for OpenSSL (`io.github.jolt-lang/crypto`, plus
`jolt-lang/jolt-crypto` riding `io.github.jolt-lang/http-client`'s own
deps.edn) and libz (`io.github.jolt-lang/http-client`) declare only
`:darwin`/`:linux` candidates, so on Windows jolt#989's empty-candidate path
fires for all three. Filed in the jolt tracker so the declarations land
together with the loader fix; the candidates are Git for Windows' DLL names —
the same ones jolt's own Maven fetcher already uses
(`stdlib/jolt/mvn_http.clj`): `libcrypto-3-x64.dll` (+ `-3.dll`,
`-1_1-x64.dll` fallbacks), `libssl-3-x64.dll` (+ fallbacks), `zlib1.dll`
(+ `z.dll`). Fixing this alone makes the kmet-side block redundant; fixing
only jolt#989 does not. Both crypto/ssl declarers must carry the key — the
same-`:name` dedup keeps the first spec through the graph, so a single fixed
repo may still lose to the other's unix-only entry.

**Workaround** (`deps.edn`): the same project-level `:jolt/native` block as
jolt#989 — one block, one removal step. Removal: bump the
`io.github.jolt-lang/crypto` and `io.github.jolt-lang/http-client` git shas to
revisions carrying the `:windows` keys, delete the `:jolt/native` block, and
re-run `jolt -e '(println :ok)'` on Windows with the DLLs present. If only
jolt#989 has landed by then, the block still has to stay for `z`.

### [jolt#1006](https://github.com/jolt-lang/jolt/issues/1006) — SCI cannot implement a `copy-var*`-injected host protocol from `defrecord`/`extend-type` (follow-up to #1000, which closed as recipe-only)

**Area:** SCI interop / `defprotocol` representation

Upstream closed #1000 with merge #1003 (`6e76671b`: `~@` lazy past the first
splice + `:sigs` on protocol values — in the built `v0.8.1-443-gd55ec029`,
verified on-device). What landed: SCI's `deftype` analysis ordering is fixed and
the babashka recipe (multimethods on `sci.impl.types/type-impl` + a SCI-side
protocol map + the host protocol extended to `SciRecord`/`SciType`, pinned by
jolt's `sci-functional-test` gate) runs end to end. What did NOT land: the
transparent flow — `(defrecord R [] p/P (m …))` over a `copy-var*`-injected host
protocol — still fails under SCI on jolt with `Unable to resolve symbol:
<method>` (phase `analysis`) and works on bb/JVM. Minimal repro on the built
binary: inject `kmet.tui.protocols` by reference (`copy-var*` per var, as
`shared-var-map` does) and eval
`(defrecord R [x] kmet.tui.protocols/IComponent (render …) …)` →
`FAIL: Unable to resolve symbol: render`. Upstream's own commit message declares
this flow unsupported ("fails on the JVM too"); the recipe is the supported
path. SCI-defined protocols are fine on jolt (verified: `defprotocol` +
`deftype`/`defrecord` in one context, cross-namespace via load-fn, and aliased
method calls all evaluate).

This is the `defcomponent` blocker: `defcomponent` expands to exactly that
`defrecord` over the injected `kmet.tui.protocols/IComponent`, so every
extension component fails. Deep probe of the real loader context (`eval-source!`
chain): `extensions/mcp-adapter/src` fails at
`extensions/mcp_adapter/panel.clj:450` (`(defcomponent McpPanel …)`) with
`Unable to resolve symbol: render`. `lsp-adapter` (`panel.clj:61` `LspPanel`)
and `review` (`dialogs.clj:93,108` plus `extend-type … IFocusable` at `:101,116`)
fail with the same surface. NOTE: `lsp-adapter` is no longer blocked by #999 —
`(java.net.URLDecoder/decode …)` resolves in SCI on the built binary (verified
directly, via the loader's retry path); it dies earlier in its panel.
`tree-sitter` (no components) loads fine, which corroborates.

(#998 `Matcher` and #999 `URLEncoder`/`URLDecoder` class tokens landed with
merge #1002 and are verified fixed on-device — `Class/forName`, `instance?`,
`class`, and the SCI shapes all answer — so their sections are pruned; neither
blocks any shipped extension anymore.)

**Workaround** (`test/kmet/app/test_extensions.clj:1056`): the `^:bb-only` gate
on `test-shipped-extensions-load-from-src` stays for #1006 (`mcp-adapter` /
`review` / `lsp-adapter` panels) plus the clojure extension's unfiled
Maven-chain gaps below — none of the three former tickets covers clojure
anymore. Error-attribution pitfall: `load-extension!` names the ENTRY file in
the error string (`…/core.clj`, `…/mcp_adapter.clj`); the real failure is the
deepest `ex-data :file` in the cause chain. — `extensions/clojure/src`
(unfiled, needs triage — not a jolt runtime ticket yet): its `deps.edn` closure
pulls the raw Maven sources (`rewrite-clj 1.2.57`, `tools.reader 1.5.2`, `cljfmt
0.16.5`) because jolt has no bundled ports for them (`bundled-port-namespaces` /
`bb-shared-namespaces` are bb-only by design; `host-requires!` skips them on
jolt). Requiring the closure in the real context gives: `reader-types` OK,
`edamame.core` OK, `rewrite-clj.reader` FAIL at `tools.reader
impl/inspect.clj:49` (`defmethod inspect* clojure.lang.PersistentVector$ChunkedSeq`
— the `$ArrayMap$Seq` / `$NodeSeq` methods below it are the same shape),
`rewrite-clj.node` / `cljfmt.core` FAIL at `rewrite-clj interop.cljc:42`
(`(instance? clojure.lang.IMeta data)`). All three classes exist on jolt
(`Class/forName` OK) and answer once `sci/add-class!`-registered (verified
individually) — but dep-closure sources bypass both seed paths
(`register-source-classes!` runs only for own-artifact sources in `make-load-fn`;
`eval-source-with-retry!` covers only the entry source), so each miss is fatal
single-attempt. Behind those sits a harder wall: `tools.reader
reader_types.clj:14-15` imports `java.io InputStream/BufferedReader/Closeable`
and implements `Closeable` in `deftype` positions (`:75,100,166`) — SCI rejects
host interfaces in `deftype` on BOTH hosts (`defrecord/deftype currently only
support protocol implementations`, verified on bb too), which bb never hits
because it injects its port. Fix directions: kmet-side (extend seeding/retry to
dep-closure sources; covers `IMeta`/`ChunkedSeq`) and upstream-or-port for the
`Closeable`-in-`deftype` wall. Removal: when #1006 lands (or
kmet reworks `defcomponent`/injection to the recipe) AND the clojure chain
loads, drop `^:bb-only`, run
`jolt test kmet.app.test-extensions/test-shipped-extensions-load-from-src`, and
delete this block.

### [jolt#1007](https://github.com/jolt-lang/jolt/issues/1007) — `:as :stream` never returns on a body that does not end: the `java.net.http` shim reads every response to EOF before handing back the `HttpResponse`

**Area:** `io.github.jolt-lang/http-client` — `src/jolt/http/core.clj`
(`read-response`, `read-chunked`, `read-sized`), `src/jolt/http/jdk.clj`
(`net-http-send` → `core/make-bais`)

The shim buffers: `core/read-response` returns a COMPLETE body (Content-Length
via `read-sized`, chunked via `read-chunked` to the terminal chunk,
read-to-close when unframed), and `net-http-send` maps
`:jolt.http/handler-inputstream` — `BodyHandlers/ofInputStream`, which is what
`babashka.http-client`'s `:as :stream` builds — to a `ByteArrayInputStream` over
those bytes. So a response that never ends never returns: the call blocks
inside the shim until the socket closes or the deadline fires. bb/JVM with the
same `org.babashka/http-client` returns as soon as the headers are in and
streams incrementally. Verified on jolt v0.8.8 + http-client @ `b98833b8`
(upstream `main`) + `org.babashka/http-client` 0.4.25:

```
bb:    call returned 200, then "data: tick N" every 250ms
jolt:  SocketTimeoutException: Response exceeded the total time limit of the
       request timeoutms — at core/read-response (core.clj:653),
       jdk/net-http-send (jdk.clj:610), babashka.http-client.internal/request
```

(no `:timeout` → blocks forever). Upstream documents the buffering in its
README ("Response bodies are read in full before the response is returned…"),
so #1007 is a parity/feature request, not a regression. Condensed repro — one
server command, one client file run under both hosts:

```sh
bb -e '(let [s (java.net.ServerSocket. 8765) c (.accept s) w (java.io.PrintWriter. (.getOutputStream c) true)] (.print w "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n") (.flush w) (dotimes [i 10] (.print w (str "data: tick " i "\n\n")) (.flush w) (Thread/sleep 250)))' &

;; client.clj
(require '[babashka.http-client :as http])
(let [r (http/get "http://127.0.0.1:8765/" {:as :stream :timeout 2000})]
  (println "call returned" (:status r))
  (println (line-seq (clojure.java.io/reader (:body r)))))
```

**Workaround** (`src/kmet/libs/http.cljc:740`): the host carve-out in `request`
— `#?(:jolt (= :stream (:as opts)) :default false)` folded into the `curl?`
decision, so every `:as :stream` request on jolt goes through `curl-request`
(SOCKS/https-scheme proxies keep their own, unchanged curl fallback).
Consequence: on jolt every LLM provider SSE stream (the `kmet.ai.api/*` hot
path) runs on a curl subprocess — no pooled connections, no native proxy
routing, one child process per request. Removal: delete the conditional (the
`if` becomes plain `(if curl? …)`), drop the "live `:as :stream` feeds on Jolt"
caveats from the three docstrings in the same file (ns docstring ~31,
`set-transport!` ~675, the `request` body comment ~732-739), update the
transport-mode comment in `test/kmet/libs/test_http.clj` (~111: "curl only for
the fallback cases: SOCKS/https-scheme proxies, and live `:as :stream` feeds on
Jolt") — `deftest-transports test-stream` then exercises the native path under
`:platform` on jolt — and re-run the repro above under jolt plus
`jolt test` over `kmet.libs.test-http`. `jolt-port.md` §B1 records the same
decision and wants its "What still keeps curl on Jolt: live streams" text
removed with it.

### [jolt#1015](https://github.com/jolt-lang/jolt/issues/1015) — `StringBuilder` / `StringBuffer` `append(char[])` and `append(char[], offset, len)` append the array's rendering instead of its characters

**Area:** host method tables — `java.lang.StringBuilder` / `java.lang.StringBuffer`
(`host/chez/java/host-static-classes.ss`, `string-builder-methods`). The single
`append` arm funnels every argument through `append-text` (`render-piece` +
CharSequence start/end substring), so a `char[]` renders as `"#object[[C]"`;
the 3-arg form then substrings that rendering — silently appending the wrong
characters, or throwing `StringIndexOutOfBoundsException` once the requested
length exceeds it. The JVM dispatches those calls to the distinct
`append(char[], int offset, int len)` overload (offset/len, not start/end).
Verified on `v0.8.8-4-g2039710e` (2026-09-15):

```clojure
;; succeeds on bb/JVM, fails on jolt:
(let [b (char-array [\a \b \c])]
  [(str (doto (java.lang.StringBuilder.) (.append b)))
   (str (doto (java.lang.StringBuilder.) (.append b 0 2)))])
;; bb/JVM: ["abc" "ab"]
;; jolt:   ["#object[[C]" "#o"]
```

The `String(char[], offset, count)` path already handles char arrays
(`char-array-arg?` / `char-array->string` in the same file, used by
`writer-piece`); only `append` lacks the dispatch. The same `append-text`
helper also backs `insert` (jolt#1016 — same fix, shared workaround).

**Workaround:** the nine mock-server body readers in `test/kmet/ai/test_llm.clj`
build the request body with `(.append sb (String. buf 0 m))` instead of the
native `(.append sb buf 0 m)` — without it the server thread died with the
`StringIndexOutOfBoundsException` above and every mock-server e2e hung until
the runner's per-namespace timeout. Removal: drop the `(String. …)` wrapping at
the nine sites (grep `(String. buf 0 m)` in that test file) — and this block —
when the overloads land.

### [jolt#1016](https://github.com/jolt-lang/jolt/issues/1016) — `StringBuilder` / `StringBuffer` `insert(int, char[])` / `insert(int, char[], int offset, int len)` insert the array's rendering instead of its characters

**Area:** host method tables — same `string-builder-methods` in
`host/chez/java/host-static-classes.ss`; the `insert` arm passes its value
argument through the same `append-text` helper as #1015, so a `char[]` is
rendered and the 4-arg form substrings the rendering. Verified on
`v0.8.8-4-g2039710e` (2026-09-15):

```clojure
;; succeeds on bb/JVM, fails on jolt:
(let [b (char-array [\a \b \c])]
  [(str (doto (java.lang.StringBuilder. "x") (.insert 0 b)))
   (str (doto (java.lang.StringBuilder. "x") (.insert 0 b 0 2)))])
;; bb/JVM: ["abcx" "abx"]
;; jolt:   ["#object[[C]x" "#ox"]
```

**Workaround:** none of its own — the nine-site `(.append sb (String. buf 0 m))`
workaround and its removal checklist live with #1015 (the `char-array-arg?`
dispatch fix there covers `insert` too), so both entries come out together when
the fix lands.

### [jolt#1017](https://github.com/jolt-lang/jolt/issues/1017) — on the curl path (`:as :stream` on Jolt, #1007) a stream is cut at `:timeout`/`--max-time`, a total deadline, even while data flows

**Area:** kmet-side consequence of #1007: `kmet.libs.http` routes `:as :stream`
through curl on Jolt, and `curl-argv` maps `:timeout` to `--max-time` (total
transfer deadline). Providers set `:timeout (or total-timeout idle-timeout)`
(default `:http-idle-timeout-ms` 300000), so every provider stream is
hard-capped at 5 min wall-clock on Jolt, while bb's `java.net.http` timeout
never cuts an in-progress body. Verified on `v0.8.8-4-g2039710e`
(2026-09-15): server streams a chunk every 200 ms, client
`{:as :stream :timeout 1000}` — Jolt delivers 5 of 40 ticks and cuts at ~1.2 s
(server sees a broken pipe); bb delivers all 40 over ~8.1 s.

**Workaround / relation:** none of its own — the divergence exists only because
of #1007's stream carve-out, so the #1007 removal checklist retires it when the
shim can stream. If fixed kmet-side instead, the change is in
`src/kmet/libs/http.cljc` (`curl-argv`'s `:max-time`): don't apply an
idle-derived `:timeout` to `:as :stream` (let the SSE reader's idle timeout +
`abort!` govern), or plumb an explicit total deadline separately.
`test-llm-body-stall-idle-timeout-completes` is `^:bb-only` for this.

### [jolt#1020](https://github.com/jolt-lang/jolt/issues/1020) — host method tables silently ignore extra trailing arguments; `String/valueOf(char[], offset, count)` ignores offset/count

**Area:** host method dispatch (`host/chez/java/natives-str.ss`'s
`jolt-string-method` and other `rest`-taking tables): extra args are dropped
instead of raising the JVM's arity error, so
`(String/valueOf (char-array [\a \b \c]) 1 2)` returns `"abc"` instead of
`"bc"`. Found while sweeping the char[] API family around #1015/#1016.

**Workaround:** none in kmet — no call sites; tracked because the
silent-result class can mask user errors.

### [jolt#1021](https://github.com/jolt-lang/jolt/issues/1021) — `String/copyValueOf` missing (both arities)

**Area:** `java.lang.String` static table — `(String/copyValueOf (char-array …))`
and the 3-arg form throw `No matching field or method: String/copyValueOf`;
they are identical to the matching `String/valueOf` overloads on the JVM (and
pair with #1020).

**Workaround:** none in kmet — no call sites.

### [jolt#1022](https://github.com/jolt-lang/jolt/issues/1022) — `StringBuilder`/`StringBuffer` `getChars(int, int, char[], int)` missing

**Area:** `string-builder-methods` (`host/chez/java/host-static-classes.ss`) has
no `getChars`, so `(.getChars (StringBuilder. "abc") 0 3 dst 0)` throws
`No matching method getChars found`; `String.getChars` works. The char[] family
companion to #1015/#1016.

**Workaround:** none in kmet — no call sites.

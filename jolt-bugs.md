# jolt-bugs — reported upstream issues

All Jolt upstream issues kmet has reported are tracked here (Windows-specific
ones included), with the kmet-side workaround (if any) and exactly what to
remove or update. A ticket is done only when its fix reaches a tagged
release: closed upstream but unreleased still counts as live, because the
workaround protects the declared floor (the latest tagged release). Status
checked against GitHub and the re-fetched `origin/main` on 2026-10-06, with
the `jolt v0.8.18` build installed (latest tag: v0.8.18; `origin/main` at
`2dd4fe81`).

## Reported issues

Each entry below records the kmet-side workaround (if any) and exactly what
to remove or update, whether the upstream ticket is still open or its fix is
awaiting a release.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (labeled `deferred`; last upstream activity 2026-09-18). It affects
extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### [jolt#1281](https://github.com/jolt-lang/jolt/issues/1281) — Windows: canonical paths keep NTFS 8.3 short names

Open (filed 2026-10-08). On Windows, `java.io.File.getCanonicalPath`,
`babashka.fs/real-path`, and
`babashka.fs/canonicalize` leave an 8.3 short component (`MARKO~1.KOC`)
unexpanded; the JVM resolves it to the long spelling (`Marko.Kocic`). Two
spellings of one directory then compare unequal whenever one side comes from
the OS (env vars frequently carry the short spelling) and the other from a
child process. Downstream, kmet's
`kmet.app.test-extensions/test-exec-runs-in-the-runtime-cwd` fails under
`jolt test-ext`/Windows: the test directory is short-spelled through
`java.io.tmpdir`, while the child's `git rev-parse --show-toplevel` prints
the long spelling.

Environment: jolt `v0.8.19-5-g8753e115` on Windows 11 Enterprise 10.0.26200
(AMD64). Repro (run under both hosts):

```clojure
(require '[babashka.fs :as fs])
(let [short (System/getProperty "java.io.tmpdir")]
  (prn :tmp short)
  (prn :canonicalize (str (fs/canonicalize short)))
  (prn :getCanonicalPath (.getCanonicalPath (clojure.java.io/file short)))
  (prn :real-path (str (fs/real-path short))))
```

bb/JVM returns `C:\Users\Marko.Kocic\AppData\Local\Temp` from all three;
jolt keeps `C:\Users\MARKO~1.KOC\AppData\Local\Temp`.

Workaround: none yet — the affected test stays red on jolt/Windows until the
short name is resolved upstream (or the test drops canonical-path comparison
for the child cwd).

### [jolt#1282](https://github.com/jolt-lang/jolt/issues/1282) — babashka.http-client request timeout keeps the JVM timeout contract

Open (filed 2026-10-08). Against a server that accepts a request but never
responds, `babashka.http-client` with `{:timeout 1000}` raises
`java.net.SocketTimeoutException: Response exceeded the request timeout` on
jolt, where babashka/JVM raises `java.net.http.HttpTimeoutException: request
timed out`. Timeout classification by the `"timed out"` token (kmet.libs.http
`transport-error-message` and the retry classifier) cannot recognize the jolt
error; the socket read timeout also preempts the SSE idle watchdog, so the
idle-timeout path never runs. Downstream,
`kmet.ai.test-llm/test-llm-request-timeout-follows-idle` and
`test-llm-body-stall-idle-timeout-completes` fail under `jolt test-ext`.

Environment: jolt `v0.8.19-5-g8753e115` on Windows 11 Enterprise 10.0.26200
(AMD64) — the port divergence is not necessarily Windows-only. Repro (run
under both hosts):

```clojure
(require '[babashka.http-client :as http])
(let [ss (java.net.ServerSocket. 0)
      port (.getLocalPort ss)
      _ (doto (Thread. (fn []
                         (try (let [s (.accept ss)]
                                (Thread/sleep 10000)
                                (.close s))
                              (catch Exception _ nil))))
          (.setDaemon true)
          (.start))]
  (try
    (http/get (str "http://localhost:" port "/x") {:timeout 1000})
    (println "RESULT: no error")
    (catch Exception e
      (println "RESULT: class=" (.getName (class e)))
      (println "RESULT: message=" (ex-message e)))
    (finally (.close ss))))
```

bb/JVM: `HttpTimeoutException` / `request timed out`; jolt: `SocketTimeoutException`
/ `Response exceeded the request timeout`.

Workaround: none yet — the affected tests stay red on jolt until the port
matches babashka's timeout contract (or kmet's classifier learns the jolt
spelling).

### [jolt#1283](https://github.com/jolt-lang/jolt/issues/1283) — Windows: closing a process stream while a reader blocks segfaults

Open (filed 2026-10-08). Closing a child's `:out`/`:err` stream while a
thread is parked in `.read` on it crashes jolt with a segmentation fault
(exit 139) on Windows; bb/JVM completes the same sequence. One parked reader
plus a close is enough — allocation/GC is not required. Environment: jolt
`v0.8.19-5-g8753e115` on Windows 11 Enterprise 10.0.26200 (AMD64).

Workaround to remove once jolt#1283 lands: `kmet.app.bash-executor`
`create-default-ops`'s `:cleanup` bounds the drain to a 2 s grace and closes
a still-stuck reader's stream from a background future, abandoning the read —
the shape the reduced repro crashes on, survived in the suite only on timing.
The timeout/read machinery is also what keeps a detached descendant from
stalling the tool, so the background-thread close stays (the JVM's pipe close
drains on the calling thread on Windows); once the fix lands, re-check
`jolt test-ext kmet.app.test-tools` for the safepoint warning ("a garbage
collection has been waiting 2 s for 2 threads...") and drop any
jolt-specific guard added for this ticket. `test-tool-bash-background-pipe-closed`
stays as the regression guard.

### [jolt#1284](https://github.com/jolt-lang/jolt/issues/1284) — `--opt` builds: concurrent `extend-protocol` dispatch miscompiles

Open (filed 2026-10-08). In an optimized-emission binary (`jolt build --opt`,
which `jolt dist` uses by default), a single protocol call site that
dispatches over eight `extend-protocol` record types, driven from eight
concurrent `future`s, throws
`java.lang.ClassCastException: fx=: #[chez-jrdesc-v3 "…R6" …] is not a fixnum`
(also seen: `*: … is not a number`, and `vector-set!: 10 is not a valid index
…` from a lazily grown dispatch cache). The record value is fed to fixnum
arithmetic inside the dispatch path. The same program is clean interpreted
(`jolt run`), in a `--dev` build, and sequentially in the same `--opt` binary
with the same total dispatch count — so the trigger is concurrent dispatch
through the optimized per-site inline cache, not workload size, record count,
or file content.

Downstream, this is the "jolt/rewrite-clj backend bug" seen in the
`tools + duplication` session: parallel `clojure_edit` /
`clojure_edit_replace_sexp` batches on the jolt dist host failed with
`$record-oops: invalid who argument jfn$rewrite-clj.node.whitespace/$…
(… WhitespaceNode …)` and `… CommentNode … is not a fixnum`, while the same
calls succeeded sequentially, under `jolt run`, under `--dev`, and on bb/JVM.
kmet runs one assistant batch's tool calls in parallel `future`s by default
(`kmet.app.loop/tool-execution-mode`, `:parallel`), and cljfmt/rewrite-clj
dispatch through protocols — so the race lands in the Clojure extension
tools first.

Environment: jolt `v0.8.19-5-g8753e115` on Windows 11 Enterprise 10.0.26200
(AMD64); `--dynamic` in the build commands is only this machine's link
choice. Repro (reduced, no kmet code; full text in the issue):

```clojure
(ns jolt-proto-race)

(defrecord R0 [v]) (defrecord R1 [v]) (defrecord R2 [v]) (defrecord R3 [v])
(defrecord R4 [v]) (defrecord R5 [v]) (defrecord R6 [v]) (defrecord R7 [v])

(defprotocol Tag (tag [x]))

(extend-protocol Tag
  R0 (tag [_] 0) R1 (tag [_] 1) R2 (tag [_] 2) R3 (tag [_] 3)
  R4 (tag [_] 4) R5 (tag [_] 5) R6 (tag [_] 6) R7 (tag [_] 7))

(defn make-xs [n]
  (loop [i 0 acc (transient [])]
    (if (< i n)
      (recur (inc i) (conj! (conj! (conj! (conj! (conj! (conj! (conj! (conj! acc
                                                                              (->R0 i))
                                                                    (->R1 i))
                                                          (->R2 i))
                                                (->R3 i))
                                      (->R4 i))
                            (->R5 i))
                  (->R6 i))
           (->R7 i)))
      (persistent! acc))))

(defn work [xs n]
  (dotimes [_ n]
    (doseq [x xs]
      (tag x))))

(defn -main [& _]
  (let [xs (make-xs 200)
        ;; same total dispatch work as the concurrent phase below
        seq-result (try (work xs 2400) :ok (catch Throwable t [:throw (str t)]))
        workers (mapv (fn [_]
                        (future (try (work xs 300) :ok
                                     (catch Throwable t [:throw (str t)]))))
                      (range 8))
        results (mapv deref workers)
        bad (into {} (keep-indexed (fn [i r] (when (not= :ok r) [i r])) results))]
    (println "sequential-2400-iters:" seq-result)
    (println "concurrent-8x300-bad:" (count bad) "of 8")
    (doseq [[i r] bad]
      (println "  worker" i ":" (subs (str r) 0 240)))
    (System/exit (if (or (seq bad) (not= :ok seq-result)) 1 0))))
```

`jolt build -m jolt-proto-race -o proto-race-opt --opt --dynamic` then
running it: 7-8 of 8 workers throw on every run; the `--dev` build, `jolt
run`, and the sequential phase are clean.

Workaround: none yet — do not rely on parallel clojure-tool batches in a
jolt `--opt` build (they may still succeed; the race is timing-dependent).
If the race interferes with a session, the stopgap is to make the affected
batch run sequentially: register the Clojure extension's tools with
`:execution-mode :sequential` (any sequential call makes kmet run the whole
batch sequentially) or build the artifact with `jolt dist --dev` at the cost
of the smaller/faster opt binary. Once the fix is in a tagged release at or
above the declared floor, remove that pin (if taken) and re-check parallel
`clojure_edit` batches against the dist binary; the reduced repro above is
the regression guard.


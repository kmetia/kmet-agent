# jolt-bugs — reported upstream issues

A ticket remains live here until its fix reaches a tagged release. Status
checked on 2026-10-09 against Jolt `v0.8.20` (`143c371`) and
`jolt-lang/http-client` `v0.1.2` (`00455b8`), the versions now pinned by the
project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open. It affects extensions that choose the SCI loader backend on Jolt
and copy or implement host protocols; shipped extensions using Jolt's native
loader are not blocked. `jolt/deps.edn` pins the SCI revision from
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) for the `:sci`
fallback. Keep the pin until the change is released by Jolt.

## Resolved in the pinned releases

These issues no longer need kmet-side workarounds:

- [jolt#1281](https://github.com/jolt-lang/jolt/issues/1281), Windows 8.3
  short-name canonicalization, and
  [jolt#1283](https://github.com/jolt-lang/jolt/issues/1283), closing a
  Windows process pipe with a read in flight, are fixed in Jolt `v0.8.20`
  ([PR #1287](https://github.com/jolt-lang/jolt/pull/1287)).
- [jolt#1284](https://github.com/jolt-lang/jolt/issues/1284), concurrent
  protocol dispatch in `--opt` builds, is fixed in Jolt `v0.8.20`
  ([PR #1286](https://github.com/jolt-lang/jolt/pull/1286)).
- [jolt#1282](https://github.com/jolt-lang/jolt/issues/1282), HTTP request
  timeout exception parity, is fixed in `jolt-lang/http-client` `v0.1.2`
  ([PR #36](https://github.com/jolt-lang/http-client/pull/36)). Its existing
  v0.0.14 behavior also keeps request deadlines from
  cutting off an SSE body after response headers arrive.

Regression coverage includes runtime-cwd canonicalization
(`kmet.app.test-extensions/test-exec-runs-in-the-runtime-cwd`), HTTP request
and SSE idle timeouts (`kmet.ai.test-llm/test-llm-request-timeout-follows-idle`,
`test-llm-body-stall-idle-timeout-completes`), and the detached-process pipe
drain (`kmet.app.test-tools/test-tool-bash-background-pipe-closed`).

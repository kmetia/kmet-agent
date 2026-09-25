# tree-sitter extension — specification

Syntax validation hooks + structural code tools for kmet, powered by the
official `tree-sitter` CLI loading WASM grammars as data. Feature parity
target: rab's native tree-sitter extension and pi's `pi-tree-sitter`
package (write-guard + `list_symbols`, `find_definition`, `get_symbol_body`,
`find_callers`, `find_callees`).

**Status: designed & empirically validated end-to-end on Termux/aarch64;
not yet implemented.** Every mechanism below was proven working in a
scratch harness (`~/ts-test`) unless marked otherwise.

## Why this architecture (constraints that shaped it)

- Babashka cannot load native libs (no JNI/FFI) nor execute WASM itself —
  all parsing happens in a subprocess.
- No JS intermediary (no node/web-tree-sitter).
- No building, ever, at runtime — prebuilt executables + data files only.
- Primary platform is Termux/Android aarch64; Windows/macOS/Linux x64+arm64
  must all work.

Chosen stack: **official `tree-sitter` CLI** (prebuilt static-ish binaries,
embeds wasmtime, loads `.wasm` grammars from `TREE_SITTER_LIBDIR`) +
**grammar `.wasm` files fetched from Zed's public extension registry**
(wasi-format modules — the format the CLI accepts).

Empirical facts the implementation depends on (verified against CLI 0.26.13):

1. Grammar discovery needs, per language, a scaffold dir under a configured
   `parser-directories` entry:
   ```json
   // <parser-dirs>/tree-sitter-clojure/tree-sitter.json
   {"metadata": {"version": "0.1.0", "license": "MIT",
                 "description": "...", "links": {"repository": "..."}},
    "grammars": [{"name": "clojure",
                  "file-types": ["clj", "cljs", "cljc", "edn"],
                  "scope": "source.clojure"}]}
   ```
   plus `src/grammar.json` = `{"name":"clojure"}` and an **empty stub
   `src/parser.c` with mtime older than the `.wasm`**. Without
   `tree-sitter.json`, the grammar.json fallback registers empty
   `file-types` and discovery by extension fails.
2. With `--wasm`, the loader looks for `<TREE_SITTER_LIBDIR>/<name>.wasm`.
   If that file is newer than `src/parser.c` it is loaded directly — zero
   compilation. The stub-parser.c mtime trick makes load-only deterministic.
3. Invocation shape (validated):
   ```bash
   TREE_SITTER_LIBDIR=~/.kmet/agent/tree-sitter/libs \
     tree-sitter parse --wasm --config-path <our-config.json> FILE
   ```
4. Old npm grammar packs (`tree-sitter-wasms`, jsDelivr artifacts used by
   pi-tree-sitter) are **emscripten/dylink-format and rejected** ("failed to
   parse dylink section"). Only wasi-format modules load. Do not reuse those
   URLs.
5. Zed registry download endpoint:
   `https://api.zed.dev/extensions/<id>/<version>/download` → tarball with
   `grammars/<lang>.wasm` (wasi-format, loads cleanly) plus useful `.scm`
   query files (`outline.scm`, `highlights.scm`). **Caveat (validated while
   implementing): the registry only covers languages zed does NOT ship
   built-in** — clojure, java, kotlin, … For built-in ones (python,
   typescript/tsx, bash, rust, go) the same wasi-format wasm comes pinned
   straight from the official grammar repos' GitHub releases
   (`tree-sitter/tree-sitter-python` v0.25.0,
   `tree-sitter/tree-sitter-typescript` v0.23.2 — both verified loading in
   CLI 0.26.13).
6. On Termux the released `tree-sitter-cli-linux-arm64` binary needs the
   glibc loader: `<prefix>/glibc/lib/ld-linux-aarch64.so.1 --library-path …`
   — same launcher pattern `kmet.tasks.build` already emits for babashka itself.
7. CLI subcommands have NO grammar/binary auto-download; acquisition is
   entirely our extension's job (below).

## On-disk layout (user-level cache)

```
~/.kmet/agent/tree-sitter/
├── bin/
│   ├── manifest.edn          ; pinned CLI version + per-target {url sha256}
│   ├── tree-sitter           ; downloaded executable (chmod +x)
│   └── tree-sitter.sh        ; Termux-only glibc launcher (generated)
├── config.json               ; generated: {"parser-directories": ["…/grammars"]}
├── grammars/                 ; scaffold dirs, generated per cached language
│   └── tree-sitter-clojure/…
└── libs/
    ├── manifest.edn          ; pinned language table {lang {:source :zed|:direct,
    │                         ; :id/:url :version :sha256 :file-types :probe}}
    └── clojure.wasm …        ; extracted blobs (this dir IS TREE_SITTER_LIBDIR)
```

## Auto-download

**Binary, on first use:** resolve target `{os-arch}` → look up
`bin/manifest.edn` → download → sha256 verify → chmod → on Termux also emit
the `.sh` launcher. All six release targets exist upstream (linux-x64/arm64,
macos-x64/arm64, windows-x64/arm64). Version pin upgrades = new manifest.

**Grammars, on first use per language:** tool call arrives for an unmapped
file extension → consult `libs/manifest.edn` (lang → zed extension id +
pinned version + expected sha256) → download tarball → extract
`grammars/<lang>.wasm` → generate scaffold dir → **verify by actually
parsing a tiny snippet** (load-check, mirrors pi-tree-sitter's
"validate bytes before persisting") → persist. Corrupt/mismatched cache
entries are deleted and re-fetched. Network/spawn failure ⇒ the current
tool call degrades gracefully (see Never-throw below); retry next call.
No background refresh thread; version bumps land via manifest updates.

## Hook policy (mirrors the shipped clojure extension)

**Implemented** in `dispatch.clj` + `hooks.clj` on top of `validate.clj`;
write-block and edit-warn semantics follow paren_repair exactly, plus the
never-throw rule below.

Composition context: `on-tool-call` hooks chain in registration order,
arg-rewrites propagate, blocks don't short-circuit (first block's reason
wins), and **a throwing hook becomes a block** (fail-closed) — see
`interactive.clj` before-tool-call loop.

1. **Language dispatch by file extension.**
   - Clojure family (`.clj .cljs .cljc .cljd .bb .edn .lpy`):
     if the **clojure extension is enabled, register nothing for these
     files** — its paren-repair hooks own them. Detection is within the
     extension contract, checked lazily per relevant hook invocation:
     ```clojure
     (some #(#{\"clojure_edit\" \"clojure_edit_replace_sexp\"
               \"clojure_paren_repair\"} (:name %))
           ((:get-all-tools api)))
     ```
     (`:get-all-tools` is on the api map.) When the clojure extension is
     *not* enabled, fall back to the comment/string-aware delimiter-balance
     check only — never tree-sitter-clojure for gating: sogaiu's grammar is
     intentionally permissive, near-zero ERROR signal, false-block risk with
     no upside.
   - Languages with a cached grammar: tree-sitter parse → block on
     ERROR/MISSING nodes, capped at ~10, each with line/col/snippet
     (MISSING: what token was expected).
   - Everything else: delimiter-balance fallback.
2. **Semantics identical to `extensions/clojure/src/paren_repair.clj`:**
   - `write` → pre-hook (**blocking**): content IS the file, validate
     directly; reason format matches theirs (report + \"Write blocked — \"
     + actionable hint).
   - `edit` → post-result hook (**non-blocking warn**, successful edits
     only): re-read the resulting file, and when it no longer parses append
     `⚠️ <report>` + fix hint to the result content. Rationale (theirs):
     edit newText alone isn't the file — a fragment may be unbalanced
     standalone yet correct in context; no simulation, no duplication of
     the edit engine.
   - Accept both `:path` and `:file_path` arg keys (as they do).
3. **Never-throw rule (addition to their pattern):** every failure of our
   *infrastructure* — binary missing, download failed, spawn error, timeout,
   malformed CLI output — returns `nil` (pass-through) and logs/notifies;
   `{:block …}` is reserved exclusively for syntax errors actually observed
   in validated content. Because the host converts hook exceptions into
   blocks, letting infra errors escape would brick all editing.

## Tools

All five parse on demand via short-lived CLI invocations (`parse --wasm`,
ms-scale; the CLI has no server mode — do not build one). The CLI's
**default s-expression output** is used (not `-x` XML): named nodes with
byte ranges and `field:` prefixes, roughly half the size of the XML form.
Language support = languages whose grammar is in the pinned manifest AND
which ship an extraction-rule set (`queries/<lang>.edn`; start: clojure,
python, typescript/tsx — grow by authoring rules).

Trees are parsed in Clojure by `sexp.clj` (tokenize + recursive descent;
positions are BYTE offsets, so names are recovered by slicing source at
byte ranges with an ASCII fast path). Symbols/calls are extracted by a
per-language rule walk over the tree; every call records its nearest
enclosing def, which is what makes find_callers/find_callees work.
Per-process caches keyed by file size+mtime make repeated project-wide
queries near-instant.

| Tool | Implementation |
|---|---|
| `list_symbols` | walk parsed sexp tree for def nodes (name/kind/range/signature) |
| `find_definition` | name-filtered defs over tracked project files |
| `get_symbol_body` | def node range → source slice |
| `find_callers` / `find_callees` | call records with nearest enclosing def, range-scoped |

Guidelines text steers agents away from grep for structure questions
(same wording spirit as rab/pi-tree-sitter).

## Tool renderers (mirror rab's TreeSitterToolRenderer)

**Implemented** in `render.clj`, aligned with the lsp-adapter renderer's
conventions: container/text/spacer building, shared path helpers from
`kmet.app.ui.tool-renderers` (shorten + OSC-8 hyperlink), the standard
"(N more lines, <key> to expand)" collapsed-preview footer, and syntax
highlighting through `kmet.tui.theme`'s markdown-theme `:highlight-code`
(language derived from the file extension). Style tokens follow lsp usage:
`:tool-title` `:accent` `:muted` `:tool-output` `:success` `:error`
(`:dim` folded into `:muted`).

rab's renderer (src/extensions/tree_sitter/renderer.rs) maps 1:1 onto kmet's
extension rendering contract — same data flow, different theme API:

**Data flow:** each tool's `execute` returns
`{:content "…text the model sees…" :details {...}}`; the host stores
`:details` and hands it to the renderer together with the `expanded?`
flag (per-tool toggle + chat-history-wide expand switch — already built
into `tool_execution.clj`). Renderers never re-parse anything.

**Shared call line** (all five tools):

```
list_symbols — handle_submit  in src/app/server.ts  [kind: function]
find_callers — handle_submit  in src/app/server.ts
```

| Segment | rab style | kmet equivalent |
|---|---|---|
| tool name | toolTitle + bold | `(theme/fg theme :tool-title (theme/bold "list_symbols"))` |
| ` — {name}` | accent | `(theme/fg theme :accent …)` |
| `  in {path}` | muted | `(theme/fg theme :muted …)` (shorten + linkify like edit-tool paths) |
| `  [kind: X]` | dim | `(theme/fg theme :muted …)` (kmet has no :dim token on this surface) |

Segments omitted when the arg is absent. Signature:
`(fn [name args theme width context] …component)`.

**Shared result line** for list_symbols / find_definition / find_callers /
find_callees — driven by `:details {:count n :label "symbols"
:name "handle_submit" :file-count m}`:

- no `:details` → raw content text (error messages pass through untouched)
- `expanded?` → full content text (the complete listing the model saw)
- collapsed, `count = 0` → `No symbols found` in `:dim`, plus
  `for 'handle_submit'` in `:accent` when a name was queried
- collapsed, otherwise → `✓ 12 symbols` in `:success`, `for 'handle_submit'`
  in `:accent`, `across 3 files` in `:muted` (pluralize; omit absent parts)

**get_symbol_body special case** — details
`{:name s :line-count n :path p :body src}`:

- error or missing body → content in `:error`
- `expanded?` → body rendered through `kmet.libs/highlight` syntax
  coloring (rab uses syntect here; kmet's highlight lib is the
  counterpart), falling back to raw text when highlighting yields nothing
- collapsed → `✓ ` (:success) + `handle_submit` (:accent) +
  ` (42 lines) in ` (:dim) + `src/app/server.ts` (:muted)

All of this lives in one `renderers.clj` namespace inside the extension,
built from `kmet.tui.components.text` + `kmet.tui.theme` only (shared-by-
reference libraries, per src/kmet/extension.md) — no dependency on host app UI
namespaces, unlike the clojure extension which reuses
`kmet.app.ui.tool-renderers` because its tools are edit-shaped; ours have
custom shapes and own the whole renderer.

## Non-goals / open items

- No incremental/reparse caching in v1; no daemon.
- `.scm` authoring per language is the main ongoing cost — keep the set
  honest (only ship languages where queries are tested).
- Zed registry has no SLA; manifests pin versions + sha256 so breakage is
  loud, and a vendored-release fallback URL can be added per language later.
- Windows arm64 CLI asset exists upstream but is untested here.

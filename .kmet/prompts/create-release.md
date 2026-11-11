---
description: Build all release artifacts and draft GitHub release notes with a Serbian codename
argument-hint: "[version-or-tag]"
---

# Create a release

Requested version/tag: ${1:-not specified}

Follow these steps in order. If a build, artifact check, or version check
fails, stop and report the failure; do not present partial output as a
release. Do not edit source files while producing the release: the only writes
are the artifacts under `dist/` and the scratch notes file under `target/`.

## 1. Preflight

- Work from the repository root.
- Require a clean worktree: `git status --porcelain` must print nothing. A
  dirty tree stamps `-dirty` into every artifact name, so stop and ask the
  user to commit or stash first.
- Check the toolchain: `bb`, `jolt`, and `clojure` must be on `PATH`.
  `bb dist --all` needs network on its first run (babashka release binaries
  are cached under `target/build-cache/`); `bb dist-jvm` needs the Clojure CLI;
  `jolt dist` needs a Jolt at or above `:jolt/min-version` in `deps.edn` and
  the native toolchain its link mode requires.
- Resolve the version the packagers will stamp:
  `git describe --tags --match 'v[0-9]*' --dirty`.
  Strip the leading `v`; call the result `<version>` (for example `0.4.0` or
  `0.4.0-6-gabc1234`). The release label is `v<version>`.
  - Use `v<version>` in the release title, notes heading, and compare link;
    use `<version>` in artifact names under `dist/`.
  - If a requested version/tag was given above, strip any leading `v` and
    compare it with `<version>`. If it differs, stop: the packagers stamp git,
    not an arbitrary string, so that tag must point at HEAD first. Do not
    create, move, or push a tag unless the user asked for that separately.
  - A clean checkout past the tag is valid and reads `<tag>-N-g<sha>`. A
    release normally comes from the tagged commit, so call out an untagged
    version explicitly in the final answer and do not present its notes as
    publishable.
- Record the previous release tag for the changelog:
  `git describe --tags --match 'v[0-9]*' --abbrev=0 HEAD^` (fall back to
  `git tag --list 'v[0-9]*' --sort=-v:refname | head -1`). If no tag exists,
  compare against the repository's first commit and say this is the first
  release.
- Only if the user explicitly asks for a fully validated release, run
  `bb test`, `bb test-ext`, `bb lint`, and `bb format-check` before building.

## 2. Clear `dist/`

```sh
rm -rf ./dist
mkdir -p ./dist
test -z "$(ls -A ./dist)"
```

On Windows, use `Remove-Item -Recurse -Force dist` and recreate the directory
instead.

## 3. Build the artifacts

Run these sequentially; all three write into `dist/`:

```sh
bb dist --all                 # every published babashka platform
bb dist-jvm                   # platform-independent JVM artifact
jolt dist --dynamic --smoke   # dynamic Jolt build for this host
```

- `bb dist --all` builds the five published babashka platforms:
  `linux-amd64`, `linux-aarch64`, `macos-amd64`, `macos-aarch64`, and
  `windows-amd64`. It cross-builds by downloading and appending the babashka
  release binaries, so no other host is needed.
- `bb dist-jvm` builds and smoke-tests the JVM uberjar, then packs
  `dist/kmetc-<version>-jvm.zip`.
- `jolt dist --dynamic --smoke` builds only this host's dynamic Jolt artifact.
  Cross-building a dynamic Jolt target is a separate `--target` /
  `--target-pack` operation; do not add one unless the user asks.
- Do not use `bb clean` here; it also removes `target/` and caches.

## 4. Verify the build

```sh
ls -1 dist/*.zip
du -ch dist/*.zip | tail -1
```

Expect seven zips sharing one version:

- five `kmet-<version>-<platform>.zip`
- one `kmetc-<version>-jvm.zip`
- one `kmetj-<version>-<platform>-dynamic.zip`

Confirm the artifact version in every file name matches the `<version>` from
step 1. If a zip is missing, a name carries `-dev` or `-dirty`, or the versions
differ, stop and report it. For extra confidence, inspect one zip's layout with
`unzip -l <zip>`: one top-level folder named after the zip, holding the
executable (and launcher where applicable) and `LICENSE`.

## 5. Collect the changes

Use the previous tag (`PREV`) and the release commit (`HEAD`):

```sh
git log --no-merges --oneline "$PREV..HEAD"
git log --format='%h %s%n%b' "$PREV..HEAD"
git diff --stat "$PREV..HEAD"
```

Read the source or docs behind a change when its message is ambiguous. Group
the work by user-visible area, for example: new commands and workflows, TUI,
sessions, providers/models, tools/extensions, hosts and packaging, fixes,
performance, under the hood. Omit pure refactors, test-only work, and CI churn
unless they affect users or compatibility. Do not invent features that are not
in the diff.

## 6. Choose the codename

Pick an unused, Serbia-related codename and use it in the GitHub release name
as well as the notes heading. Good sources:

- Serbian Orthodox feast days and slavas: Vidovdan, Đurđevdan, Nikoljdan,
  Sveti Sava, Petrovdan, Ilindan, Aranđelovdan, Spasovdan, Velika Gospojina,
  Mala Gospojina, Mratindan, Badnji dan, Vaskrs. `Mitrovdan` is already used
  by v0.3.0.
- Places, mountains, rivers and monasteries: Tara, Zlatibor, Kopaonik,
  Fruška gora, Đerdap, Uvac, Drina, Morava, Sava, Dunav, Rtanj, Avala, Kosmaj,
  Studenica, Sopoćani, Mileševa, Manasija, Ravanica, Golubac, Fetislam.
- Serbian historical and cultural names: Stefan Nemanja, Sava, Lazar,
  Miloš Obilić, Marko Kraljević, Karađorđe, Vuk Karadžić, Njegoš, Tesla,
  Pupin, Milanković, Nadežda Petrović.

Check the existing release names at
<https://github.com/kmetia/kmet-agent/releases> (or `gh release list`, when
available) and never reuse one. Prefer the feast day nearest the release date
when one fits; otherwise choose any unused name. Avoid names that are
political, derogatory, or ambiguous. Capitalize it as a proper name.

Use the title format `v<version> - <Codename> release`, matching
`v0.3.0 - Mitrovdan release`. The GitHub release name must carry the codename;
do not put it only in the body.

## 7. Draft the notes

Write the notes to `target/release-notes.md` (scratch space, not a release
asset; create `target/` if needed) and print the complete Markdown in the final
answer inside one ````markdown` fence so it is ready to copy.

Use this shape, dropping sections that do not apply:

````markdown
# v<version> — <Codename>

One short paragraph: what this release is and why a user should care.

## Highlights

- **Lead with the outcome.** One or two sentences on the most important
  user-visible change.
- **...** ...
- **...** ...
- **...** ...

## <Area>

- ...

## Breaking changes

- ... (only when users must change configuration, commands, or code)

## Downloads

| Host | Artifact | Run as | Needs |
|---|---|---|---|
| Babashka | `kmet-<version>-<platform>.zip` | `kmet` | nothing |
| Jolt (dynamic) | `kmetj-<version>-<platform>-dynamic.zip` | `kmetj` | native libraries on the host |
| JVM | `kmetc-<version>-jvm.zip` | `kmetc` | a Java runtime |

**Full changelog:** https://github.com/kmetia/kmet-agent/compare/<previous-tag>...v<version>
````

Notes rules:

- Highlights: 3–7 bullets ordered by impact. A reader should understand the
  release without scrolling. Put a breaking change, host/runtime floor, or
  migration step first when it matters.
- Categorized sections carry enough detail to decide whether to upgrade, not a
  commit dump. If a section would hold one low-value item, fold it into Fixes
  or Under the hood.
- Quote commands, paths, configuration keys, and artifact names exactly.
- Include model catalog refreshes, dependency floors, and notable performance
  work when present.
- Use relative links for repository docs (`docs/...`) and the absolute compare
  URL above.
- Keep the note scannable: no emojis, no attribution lines, and no
  machine-generated footer.
- Put the actual asset names from `dist/` in the Downloads table, with one
  babashka row per platform.

## 8. Final answer

- Give the release title verbatim, the exact version, and the notes path.
- Summarize the artifacts: count, total size, and any target that could not be
  built.
- Print the full notes inside one ````markdown` fence.
- State that nothing was tagged, committed, pushed, uploaded, or published.
- Remind the user that the next step is to create the GitHub release, paste the
  title and body, and upload the zips from `dist/` as assets.

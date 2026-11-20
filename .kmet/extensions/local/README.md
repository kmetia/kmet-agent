# local extension (project-local)

Exposes the project test gate as `local/gate`. Formatting and lint are the
separate `bb format-check` / `bb lint` tasks, not part of the gate. File
discovery and content search are built into the eval sandbox as the `glob` and
`grep` verbs, so this extension no longer contributes them.

## Compose

The extension lives at `.kmet/extensions/local` (the project-local
discovery root). It is loaded only when composed:

```edn
;; .kmet/patch.edn
{:rows [{:id :ext/local :plugin :ext/local}]}
```

Restart the app (or `/reload` an already mounted extension) after
adding the row.

## Use

```clojure
(local/gate)                                      ; changed, bb, tests
(local/gate {:full true})                         ; full test tree
(local/gate {:tasks [:test :test-ext]})
(local/gate {:host :jolt :tasks [:test]})         ; jolt test tasks
(local/gate {:host :jvm :tasks [:test]})          ; bb test*-jvm

(glob "**/*.clj" {:path "modules/app/src" :max 10})
(glob "**/*.clj" {:path "/data/data/com.termux/files/home/kmet/modules"})
(grep "register-eval-source!" {:path "modules/app/src"})
(grep "defn" {:path "core.clj" :ignore-case? true})
```

Patterns are relative to `:path`, which may be absolute or `~`. Both verbs
show paths relative to the call's cwd, `~/...` under home, and absolute paths
outside it. `grep` skips binary and oversized files; inside a git work tree it
uses git's file list, so `.gitignore` is honored.

The returned map carries `:report`, `:findings` by category, and per-gate
results. Test failures carry the exact `:namespace`.

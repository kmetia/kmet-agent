# local extension (project-local)

Exposes the project gate workflow as `local/gate` and file discovery/search
as `local/glob` and `local/grep`.

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
(local/gate)                                      ; changed, bb, format+lint+test
(local/gate {:full true})                         ; full tree
(local/gate {:tasks [:format :lint :test :test-ext]})
(local/gate {:host :jolt :tasks [:test]})         ; jolt test tasks
(local/gate {:host :jvm :tasks [:test]})          ; bb test*-jvm

(local/glob "**/*.clj" {:path "modules/app/src" :max 10})
(local/glob "**/*.clj" {:path "/data/data/com.termux/files/home/kmet/modules"})
(local/grep "register-eval-source!" {:path "modules/app/src"})
(local/grep "defn" {:path "core.clj" :ignore-case? true})
```

Patterns are relative to `:path`, which may be absolute or `~`. Both verbs
show paths relative to the call's cwd, `~/...` under home, and absolute paths
outside it. `grep` skips files above `:max-file-bytes`.

Formatting is checked, fixed, and re-checked. The returned map carries
`:report`, `:findings` by category, `:formatted`, and per-gate results.
Test failures carry the exact `:namespace`.

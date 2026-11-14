# local extension (project-local)

Exposes the project gate workflow as the eval verb `local/gate`.

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
```

Formatting is checked, fixed, and re-checked. The returned map carries
`:report`, `:findings` by category, `:formatted`, and per-gate results.
Test failures carry the exact `:namespace`.

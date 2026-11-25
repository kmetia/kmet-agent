# sklop (склоп)

**sklop** (Serbian **склоп**, IPA /sklôp/, "assembly" or "composition") is
the composition layer above [`srce`](https://github.com/kmetia/srce): rows, bundles,
profiles, patches, cards and mounts as data.

A caller supplies the policy; sklop supplies the language:

- `describe` / `describe-layer` take `:describe-source`, the fn that turns a
  row's plugin source into a card without mounting it.
- `mount-rows!` takes `:resolve-source` and `:timeout-ms`, the fn that turns a
  row into what `srce/plugin!` mounts and the budget for asynchronous setups.
- `profile`, `bundle`, `layers`, `compose` and `render` operate on an EDN
  table the caller supplies; sklop reads no resource and no process value.

```clojure
(require '[kmet.sklop.core :as sklop]
         '[kmet.srce.core :as srce])

(def plugins {:a {:name :a :apply (fn [_ _] nil)}})

(def table
  {:bundles {:bundle/base {:rows [{:id :a :plugin :a}]}}
   :profiles {:demo {:bundles [[:bundle/base {}]]
                     :patch [{:id :a :config {:n 1}}]}}})

(def rows (:rows (sklop/compose (sklop/layers table :demo))))
(sklop/mount-rows! (srce/root) rows
                   {:resolve-source (fn [row] (get plugins (:plugin row)))
                    :timeout-ms 5000})
```

## What it is not

sklop does not know kmet's profile table, `:ext/…` sources, `:mode`/`:defer`
row flags or the boot/submission policy. Those stay in the application:
`kmet.app.profiles` (the committed table), `kmet.app.catalog` (the source
policy) and `kmet.app.runtime` (boot, submissions, events and teardown).
Errors raised by the language are `:kmet.sklop/*`.

## Install

Consume it from this repository as a path dependency, next to the kernel:

```clojure
{:deps {kmet/srce  {:local/root "/path/to/srce"}
        kmet/libs  {:local/root "/path/to/kmet-agent/modules/libs"}
        kmet/sklop {:local/root "/path/to/kmet-agent/modules/sklop"}}}
```

Require one namespace: `kmet.sklop.core`.

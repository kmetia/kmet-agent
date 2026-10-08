(ns kmet.tui.test-dispose
  "Dispose plumbing tests (dsl.md §5, stage 2): defcomponent synthesizes a
   no-op dispose, containers delegate to their children, the TUI disposes
   removed/cleared children, and dispose is idempotent."
  (:require [clojure.test :as t]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.components.scroll-view :as scroll-view]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.v-stack :as v-stack]
            [kmet.tui.core :as core]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]))

(defcomponent DisposalProbe nil [flag-atom render-fn]
  (render [_this _width]
    (if render-fn (render-fn) []))
  (dispose [this] (swap! (:flag-atom this) conj :probe)))

(t/deftest defcomponent-synthesizes-no-op-dispose
  ;; a component WITHOUT a custom dispose still satisfies IComponent and
  ;; can be disposed safely (the synthesized no-op)
  (let [c (text/make-text "x" 0 0)]
    (t/is (satisfies? protocols/IComponent c))
    (t/is (nil? (protocols/dispose c)) "no throw, returns nil")
    (t/is (nil? (protocols/dispose c)) "idempotent")))

(t/deftest custom-dispose-fires
  (let [flag (atom [])
        p (map->DisposalProbe {:kind nil :flag-atom flag})]
    (protocols/dispose p)
    (t/is (= [:probe] @flag))
    (protocols/dispose p)
    (t/is (= [:probe :probe] @flag) "custom dispose runs each call — keep it idempotent internally")))

(t/deftest containers-delegate-to-children
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        b (box/make-box 0 0 nil)]
    (box/box-add-child b child)
    (protocols/dispose b)
    (t/is (= [:probe] @flag) "box delegates"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        c (container/make-container [child])]
    (protocols/dispose c)
    (t/is (= [:probe] @flag) "container delegates"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        vs (v-stack/make-v-stack [child])]
    (protocols/dispose vs)
    (t/is (= [:probe] @flag) "v-stack delegates through entry maps"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        hs (h-stack/make-h-stack [{:component child}])]
    (protocols/dispose hs)
    (t/is (= [:probe] @flag) "h-stack delegates through entry maps"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        sv (scroll-view/make-scroll-view child)]
    (protocols/dispose sv)
    (t/is (= [:probe] @flag) "scroll-view delegates to its single child"))
  ;; stack entry maps resolve via stack/entry-component
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        vs (v-stack/make-v-stack [{:component child}])]
    (protocols/dispose vs)
    (t/is (= [:probe] @flag))))

(t/deftest tui-disposes-removed-and-cleared-children
  (let [tui (core/create-tui nil)
        flag (atom [])
        probe (fn [] (map->DisposalProbe {:kind nil :flag-atom flag}))
        a (probe) b (probe)
        _ (core/tui-add-child tui a)
        _ (core/tui-add-child tui b)]
    (core/tui-remove-child tui a)
    (t/is (= [:probe] @flag))
    (core/tui-clear tui)
    (t/is (= [:probe :probe] @flag))
    (t/is (empty? @(:components tui)))))

(t/deftest tolerant-disposal-handles-foreign-shapes
  ;; finding 1: extension widget/footer paths hand the TUI raw maps and
  ;; multi-root compiled trees; strict protocol dispatch used to throw on
  ;; them, so the removal paths silently skipped disposal
  (let [called (atom [])]
    (protocols/dispose-component! nil)
    (t/is true "nil is a no-op")
    (protocols/dispose-component!
     {:render (fn [_] []) :dispose (fn [] (swap! called conj :duck))})
    (t/is (= [:duck] @called) "a duck-typed map disposes through :dispose")
    (protocols/dispose-component! {:render (fn [_] ["no dispose key"])})
    (t/is (= [:duck] @called) "a map without :dispose does not propagate")
    (protocols/dispose-component!
     [{:dispose (fn [] (swap! called conj :a))}
      {:dispose (fn [] (swap! called conj :b))}])
    (t/is (= [:duck :a :b] @called) "a sequence disposes every element")))

(t/deftest tui-clear-disposes-duck-typed-children
  ;; finding 1: a duck-typed child (extension footer/widget) reaches
  ;; tui-remove-child / tui-clear — both must dispose it, not throw
  (let [tui (core/create-tui nil)
        called (atom [])
        duck {:render (fn [_] []) :dispose (fn [] (swap! called conj :duck))}]
    (core/tui-add-child tui duck)
    (core/tui-clear tui)
    (t/is (= [:duck] @called) "the duck-typed child was disposed")
    (t/is (empty? @(:components tui)))))

(t/deftest track-watches-are-removed-on-dispose
  (let [c (text/make-text "hello" 0 0)
        ta (:text-atom c)
        cache (:cache c)]
    (protocols/render c 20)
    (protocols/render c 20)
    (t/is (some? @cache) "render populated the reactive cache")
    (protocols/dispose c)
    (reset! ta "goodbye")
    (t/is (some? @cache)
          "no zombie watch: post-dispose writes leave the dead cache alone")
    (protocols/dispose c)
    (reset! ta "x")
    (t/is (some? @cache) "dispose stays idempotent")))

(t/deftest custom-dispose-still-fires-with-watch-teardown-prepended
  (let [flag (atom [])
        p (map->DisposalProbe {:kind nil :flag-atom flag})]
    (protocols/dispose p)
    (t/is (= [:probe] @flag)
          "the prepended cleanup did not displace the custom body")))

(t/deftest render-and-dispose-returns-lines-and-disposes
  (let [flag (atom [])
        p (map->DisposalProbe {:kind nil :flag-atom flag
                               :render-fn (fn [] ["rendered"])})]
    (t/is (= ["rendered"] (core/render-and-dispose p 10))
          "the render result is returned")
    (t/is (= [:probe] @flag)
          "the temporary component was disposed after rendering")))

(t/deftest render-and-dispose-disposes-when-render-throws
  (let [flag (atom [])
        p (map->DisposalProbe {:kind nil
                               :flag-atom flag
                               :render-fn (fn [] (throw (ex-info "render failed" {})))})]
    (t/is (thrown? Exception (core/render-and-dispose p 10))
          "the render error propagates")
    (t/is (= [:probe] @flag)
          "disposal runs even when the render throws")))

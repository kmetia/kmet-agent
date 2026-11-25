(ns kmet.app.test-inspect
  "kmet.app.inspect — the unit tree `/units` and `--dump-state` render.

   The renderer's contract is the one the legacy CLI's store listing had: one flat line
   per unit, no indentation, a stable order (parents before children, fibers in
   mount order) — plus the two facts a store could not show, a fiber's state and
   what it waits for."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.inspect :as inspect]
            [kmet.srce.core :as srce]))

(defn- provider
  "A plugin that provides NAME into the mount site's scope — the app's own rule,
   so a sibling's `:inject` sees it."
  [name]
  {:name name
   :provide [name]
   :apply (fn [ctx _]
            (srce/provide! ctx name {:name name} {:in (:parent ctx)}))})

(defn- app-root [] (srce/root {:name :app}))

(defn- lines-of [ctx] (str/split-lines (inspect/units ctx)))

(defn- line-for
  "The rendered line whose path is PATH."
  [ctx path]
  (some (fn [line] (when (str/starts-with? line path) line)) (lines-of ctx)))

;; ── the shape ────────────────────────────────────────────────────────────

(deftest one-flat-line-per-unit-parents-before-children
  (let [ctx (app-root)
        _ (srce/plugin! ctx (provider :tools))
        _ (srce/plugin! ctx {:name :sessions :apply (fn [_ _] nil)})
        lines (lines-of ctx)]
    (is (= 3 (count lines)) "the root, then its two mounts")
    (testing "the root line is a path and its child count"
      (is (str/starts-with? (first lines) "[:app]"))
      (is (str/includes? (first lines) "ctx"))
      (is (str/includes? (first lines) "children=2")))
    (testing "fibers in mount order, not name order"
      (is (str/starts-with? (second lines) "[:app :tools]"))
      (is (str/starts-with? (nth lines 2) "[:app :sessions]")))
    (testing "nothing is indented — the path locates the unit"
      (is (every? #(not (str/starts-with? % " ")) lines))
      (is (not-any? #(str/ends-with? % " ") lines) "no trailing padding"))))

(deftest a-fiber-line-carries-its-state-provides-and-effects
  (let [ctx (app-root)
        _ (srce/plugin! ctx {:name :tools
                             :provide [:tools]
                             :apply (fn [c _]
                                      (srce/provide! c :tools {})
                                      (srce/effect c (fn [] nil) "tool read")
                                      nil)})
        line (line-for ctx "[:app :tools]")]
    (is (str/includes? line "fiber#"))
    (is (str/includes? line ":active"))
    (is (str/includes? line "provides=[tools]"))
    (is (str/includes? line "effects=[provide :tools, tool read]"))))

;; ── the two states a store could not show ────────────────────────────────

(deftest a-pending-fiber-says-what-it-waits-for
  (let [ctx (app-root)
        _ (srce/plugin! ctx {:name :ui/terminal :inject [:ui] :apply (fn [_ _] nil)})
        line (line-for ctx "[:app :ui/terminal]")]
    (is (str/includes? line ":pending"))
    (is (str/includes? line "inject=[ui]"))
    (is (str/includes? line "waiting-for=[ui]"))))

(deftest a-failed-fiber-carries-its-error
  (let [ctx (app-root)
        _ (srce/plugin! ctx (provider :ui))
        _ (srce/plugin! ctx {:name :ui/tk
                             :inject [:ui]
                             :apply (fn [_ _]
                                      (throw (ex-info "tcl not found" {})))})
        line (line-for ctx "[:app :ui/tk]")]
    (is (str/includes? line ":failed"))
    (is (str/includes? line "inject=[ui]") "what it injected is still shown")
    (is (str/includes? line "error=\"tcl not found\""))))

(deftest an-optional-dependency-does-not-read-like-a-required-one
  ;; An absent :optional inject does not hold the fiber pending, so the two
  ;; must not render alike.
  (let [ctx (app-root)
        _ (srce/plugin! ctx {:name :ui/terminal
                             :inject {:ui :optional}
                             :apply (fn [_ _] nil)})
        line (line-for ctx "[:app :ui/terminal]")
        _ (srce/plugin! ctx {:name :odd
                             :inject {42 nil}
                             :apply (fn [_ _] nil)})
        odd (line-for ctx "[:app :odd]")]
    (is (str/includes? line "inject=[ui?]"))
    (is (str/includes? line ":active"))
    (is (str/includes? odd "inject=[42]") "a malformed name renders instead of throwing")))

;; ── paths ────────────────────────────────────────────────────────────────

(deftest a-child-ctx-renders-under-its-parent-path
  (let [ctx (app-root)
        agent (srce/extend ctx {:name :agent/a7f3})
        _ (srce/plugin! agent (provider :tools))
        lines (lines-of ctx)]
    (is (str/starts-with? (second lines) "[:app :agent/a7f3]"))
    (is (str/includes? (second lines) "children=1"))
    (is (str/starts-with? (nth lines 2) "[:app :agent/a7f3 :tools]"))))

(deftest a-fiber-s-mount-is-a-unit-and-recurses
  ;; A plugin may mount on its own ctx: the fiber line takes the mount ctx's
  ;; place in the tree, and what hangs under it still renders.
  (let [ctx (app-root)
        tools (srce/plugin! ctx (provider :tools))
        _ (srce/plugin! (:ctx @tools) (provider :tool/read))
        lines (lines-of ctx)]
    (is (str/starts-with? (second lines) "[:app :tools]"))
    (is (str/starts-with? (nth lines 2) "[:app :tools :tool/read]"))))

(deftest a-nameless-child-gets-a-segment-of-its-own
  ;; `srce/extend` for a run's own ctx carries no name; two siblings must not
  ;; collapse onto one path.
  (let [ctx (app-root)
        _ (srce/extend ctx {})
        _ (srce/extend ctx {})
        lines (lines-of ctx)]
    (is (str/starts-with? (second lines) "[:app :ctx/1]"))
    (is (str/starts-with? (nth lines 2) "[:app :ctx/2]"))))

(deftest a-subtree-renders-only-itself
  (let [ctx (app-root)
        agent (srce/extend ctx {:name :agent/a7f3})
        _ (srce/plugin! agent (provider :tools))
        _ (srce/plugin! ctx (provider :sessions))
        lines (lines-of agent)]
    (is (= 2 (count lines)) "the given ctx, then its own subtree")
    (is (str/starts-with? (first lines) "[:app :agent/a7f3]")
        "the path runs from the root even when a subtree was asked for")
    (is (not-any? #(str/includes? % ":sessions") lines)
        "a sibling of the asked-for ctx is not part of its subtree")))

(deftest a-disposed-fiber-leaves-no-line
  (let [ctx (app-root)
        probe (srce/plugin! ctx {:name :probe :apply (fn [_ _] nil)})]
    (is (some? (line-for ctx "[:app :probe]")))
    (srce/dispose! probe)
    (is (nil? (line-for ctx "[:app :probe]")))))
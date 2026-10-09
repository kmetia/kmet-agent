(ns kmet.tui.test-alias
  "Headless tests for qualified-keyword tag aliases (tui.md §2.9):
   resolution through kmet.tui.alias, attrs/children delivery, the
   fn-component behaviors an alias inherits (keyed reuse, with-let state,
   reactions, refs, dispose), registration lifecycle (last-wins, identity-
   guarded deregistration, call-time re-resolution), defalias, and the
   loud unknown/unregistered errors."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.alias :as alias]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :refer [with-let]]
            [kmet.libs.reakt :as r]))

;; restore the load-time registry (the defalias below) after each test so
;; tests never leak registrations into one another
(t/use-fixtures :each
  (fn [f]
    (let [snapshot (alias/registered)]
      (try
        (f)
        (finally
          (alias/reset-registry!)
          (doseq [[k v] snapshot] (alias/register! k v)))))))

(defn- lines
  "Rendered, trimmed lines of a freshly compiled TREE."
  [tree width]
  (mapv str/trimr (h/render-lines tree width)))

(defn- rendered
  "Trimmed lines of a mounted ROOT at WIDTH (reuse across passes)."
  [root width]
  (mapv str/trimr (core/render root width)))

;; ── resolution + attrs/children delivery ──────────────────────────────────

(t/deftest registered-qualified-tag-renders
  (alias/register! :test/chip
                   (fn [{:keys [label]} _children]
                     [:text {:padding-x 0 :padding-y 0} label]))
  (t/is (= ["ready"] (lines [:container {} [:test/chip {:label "ready"}]] 20)))
  (t/is (= ["ready"] (lines [:test/chip {:label "ready"}] 20))
        "an alias element is a root like any other"))

(t/deftest attrs-and-children-delivery
  (let [seen (atom nil)]
    (alias/register! :test/probe
                     (fn [attrs children]
                       (reset! seen {:attrs attrs :children children})
                       [:text {:padding-x 0 :padding-y 0} "x"]))
    (h/render-lines [:container {}
                     [:test/probe {:a 1} [:text "c"] "s" [:spacer {:lines 2}]]]
                    20)
    (t/is (= {:a 1} (:attrs @seen)) "attrs are the props map")
    (t/is (= [[:text "c"] "s" [:spacer {:lines 2}]] (:children @seen))
          "children are the raw nodes as a flat vector")
    (h/render-lines [:container {} [:test/probe]] 20)
    (t/is (= {:attrs {} :children []} @seen)
          "no props map and no children → {} / []")))

(t/deftest children-are-flattened-and-nils-dropped
  ;; seq children are spliced and nils dropped before the alias sees them —
  ;; [alias (map …)] delivers the mapped nodes, not one lazy seq
  (let [seen (atom nil)]
    (alias/register! :test/flat
                     (fn [_ children]
                       (reset! seen children)
                       [:text {:padding-x 0 :padding-y 0} "x"]))
    (h/render-lines [:container {}
                     [:test/flat {}
                      nil
                      (list [:spacer {:lines 1}] nil [:spacer {:lines 2}])]]
                    20)
    (t/is (= [[:spacer {:lines 1}] [:spacer {:lines 2}]] @seen))))

(t/deftest key-and-ref-are-pseudo-props-not-attrs
  (let [seen (atom nil)]
    (alias/register! :test/keys
                     (fn [attrs _]
                       (reset! seen attrs)
                       [:text {:padding-x 0 :padding-y 0} "x"]))
    (h/render-lines [:container {} [:test/keys {:key :k :ref (h/ref) :a 1}]] 20)
    (t/is (= {:a 1} @seen))))

;; ── inherits fn-component behavior ────────────────────────────────────────

(t/deftest keyed-reorder-reuses-alias-instances
  (let [inits (atom [])]
    (alias/register! :test/kid
                     (fn [{:keys [id]} _children]
                       (with-let [_ (swap! inits conj id)]
                         [:text {:padding-x 0 :padding-y 0} (str "k" (name id))])))
    (let [ids (atom [:b :a])
          root (h/root (fn [_]
                         [:container {}
                          (map (fn [id] ^{:key id} [:test/kid {:id id}]) @ids)]))]
      (t/is (= ["kb" "ka"] (rendered root 20)))
      (swap! ids reverse)
      (t/is (= ["ka" "kb"] (rendered root 20)) "the reorder took effect")
      (t/is (= [:b :a] @inits) "reorder reused both instances — no re-init")
      ;; a key change is a remount: a fresh instance initializes
      (reset! ids [:a :c])
      (rendered root 20)
      (t/is (= [:b :a :c] @inits) "the new key mounted one fresh instance"))))

(t/deftest changed-children-are-not-frozen-by-reuse
  ;; THE implementation trap: children ride :ctree and are reset on reuse —
  ;; a wrapper closure capturing them would keep the stale vector
  (alias/register! :test/pass (fn [_children children]
                                (into [:v-stack {:gap 0}] children)))
  (let [s (atom "a")
        root (h/root (fn [_]
                       [:test/pass {}
                        [:text {:padding-x 0 :padding-y 0} (r/tracked-deref s)]]))]
    (t/is (= ["a"] (rendered root 20)))
    (reset! s "b")
    (t/is (= ["b"] (rendered root 20))) "the reused alias sees the new children"))

(t/deftest alias-body-reacts-to-tracked-derefs
  (let [s (atom "a")]
    (alias/register! :test/reactive
                     (fn [_ _]
                       [:text {:padding-x 0 :padding-y 0} (r/tracked-deref s)]))
    (let [root (h/root [:container {} [:test/reactive]])]
      (t/is (= ["a"] (rendered root 20)))
      (h/reset-counters!)
      (reset! s "b")
      (t/is (= ["b"] (rendered root 20)))
      (t/is (= 2 (:bodies-run (h/counters)))
            "the root body re-derives (uncached) and the alias body re-runs"))))

(t/deftest nested-alias-elements-compose
  (alias/register! :test/inner (fn [_ _] [:text {:padding-x 0 :padding-y 0} "inner"]))
  (alias/register! :test/outer (fn [_ _] [:container {} [:test/inner]]))
  (t/is (= ["inner"] (lines [:test/outer] 20))))

(t/deftest refs-inside-alias-children-fill
  (alias/register! :test/ref-host (fn [_ children] (into [:container {}] children)))
  (let [r (h/ref)
        show? (atom true)
        root (h/root (fn [_]
                       [:test/ref-host {}
                        (when (r/tracked-deref show?)
                          [:text {:ref r :padding-x 0 :padding-y 0} "x"])]))]
    (core/render root 20)
    (t/is (some? (deref r)) "the child's ref filled through the alias")
    (reset! show? false)
    (core/render root 20)
    (t/is (nil? (deref r)) "dropping the child still clears the handle")))

(t/deftest alias-dispose-cascades-to-its-subtree
  (let [cleaned (atom 0)]
    (alias/register! :test/clean
                     (fn [_ children]
                       (into [:container {}
                              (with-let [] (finally (swap! cleaned inc))
                                        [:text {:padding-x 0 :padding-y 0} "held"])]
                             children)))
    (let [root (h/root [:container {} [:test/clean {}]])]
      (core/render root 20)
      (h/dispose-tree! root)
      (t/is (= 1 @cleaned) "the alias instance's with-let finally ran"))))

;; ── registration lifecycle ────────────────────────────────────────────────

(t/deftest registration-is-last-wins-and-resolved-at-call-time
  (alias/register! :test/live (fn [_ _] [:text {:padding-x 0 :padding-y 0} "v1"]))
  (let [root (h/root [:container {} [:test/live]])]
    (t/is (= ["v1"] (rendered root 20)))
    (alias/register! :test/live (fn [_ _] [:text {:padding-x 0 :padding-y 0} "v2"]))
    (t/is (= ["v2"] (rendered root 20))
          "a re-registration takes effect without remounting")))

(t/deftest unkeyed-alias-element-keeps-identity-and-its-ref
  ;; the element's own :ref points at the ComponentFn wrapper; an unkeyed
  ;; alias is reused across a prop change (match kind = the alias keyword)
  (alias/register! :test/stable (fn [_ _] [:text {:padding-x 0 :padding-y 0} "static"]))
  (let [n (atom 0)
        r (h/ref)
        root (h/root (fn [_]
                       [:container {} [:test/stable {:ref r :n (r/tracked-deref n)}]]))]
    (core/render root 20)
    (let [w1 (deref r)]
      (t/is (some? w1) "the alias element's ref filled")
      (h/reset-counters!)
      (swap! n inc)
      (core/render root 20)
      (t/is (identical? w1 (deref r)) "the prop change reused the alias instance")
      (t/is (zero? (:constructs (h/counters))))
      (t/is (zero? (:disposals (h/counters)))))))

(t/deftest cached-body-picks-up-a-reregistration-on-its-next-run
  ;; registry resolution happens at body-run time: an idle reaction keeps
  ;; its last output until its next dep change (the precise reload
  ;; semantics documented in tui.md §2.9)
  (let [s (atom 0)]
    (alias/register! :test/rerun
                     (fn [_ _]
                       [:text {:padding-x 0 :padding-y 0}
                        (str "v1/" (r/tracked-deref s))]))
    (let [root (h/root [:container {} [:test/rerun]])]
      (t/is (= ["v1/0"] (rendered root 20)))
      (alias/register! :test/rerun
                       (fn [_ _]
                         [:text {:padding-x 0 :padding-y 0}
                          (str "v2/" (r/tracked-deref s))]))
      (t/is (= ["v1/0"] (rendered root 20))
            "an idle alias body keeps its last output")
      (swap! s inc)
      (t/is (= ["v2/1"] (rendered root 20))
            "the next body run resolves the new registration"))))

(t/deftest deregistration-is-identity-guarded
  (let [f1 (fn [_ _] [:text {:padding-x 0 :padding-y 0} "f1"])
        f2 (fn [_ _] [:text {:padding-x 0 :padding-y 0} "f2"])
        dereg1 (alias/register! :test/wins f1)]
    (alias/register! :test/wins f2)
    (dereg1)
    (t/is (identical? f2 (alias/lookup :test/wins))
          "the stale owner's dereg must not wipe the newer registration")
    (alias/unregister! :test/wins f2)
    (t/is (nil? (alias/lookup :test/wins)))))

(t/deftest unregistered-while-mounted-fails-loudly
  (alias/register! :test/gone (fn [_ _] [:text {:padding-x 0 :padding-y 0} "here"]))
  (let [root (h/root [:container {} [:test/gone]])]
    (t/is (= ["here"] (rendered root 20)))
    (alias/unregister! :test/gone (alias/lookup :test/gone))
    (t/is (thrown-with-msg? Exception #"no longer registered"
                            (core/render root 20))
          "a mounted alias whose registration was removed fails on the next pass")))

(t/deftest register-validation
  (t/is (thrown-with-msg? Exception #"qualified keyword"
                          (alias/register! :unqualified (fn [_ _] nil)))
        "unqualified keywords belong to the host tag table")
  (t/is (thrown-with-msg? Exception #"invalid alias registration"
                          (alias/register! :test/not-a-fn "nope"))))

;; ── unknown aliases ───────────────────────────────────────────────────────

(t/deftest unknown-alias-throws-with-registry-and-did-you-mean
  (alias/register! :test/alpha (fn [_ _] [:text {:padding-x 0 :padding-y 0} "a"]))
  (let [msg (try
              (h/render-lines [:container {} [:test/alph]] 20)
              (catch Exception e (ex-message e)))]
    (t/is (and (string? msg) (str/includes? msg "unknown alias :test/alph")))
    (t/is (re-find #"Registered aliases: \[[^\]]*:test/alpha\]" msg)
          "the registry snapshot lists the registered aliases")
    (t/is (str/includes? msg "Did you mean :test/alpha?")))
  ;; a qualified keyword NEVER falls back to the host table
  (t/is (thrown-with-msg? Exception #"unknown alias"
                          (h/render-lines [:container {} [:text/nope]] 20))))

;; ── defalias ──────────────────────────────────────────────────────────────

(alias/defalias test-chip
  "A test chip."
  [{:keys [label]} _children]
  [:text {:padding-x 0 :padding-y 0} label])

(t/deftest defalias-defines-the-keyword-var
  (t/is (= :kmet.tui.test-alias/test-chip test-chip))
  (t/is (not (fn? test-chip)) "the var is the keyword, not the fn")
  (t/is (= "A test chip." (:doc (meta #'test-chip))))
  (t/is (= ["yo"] (lines [:container {} [test-chip {:label "yo"}]] 20))))

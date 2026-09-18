(ns kmet.tui.test-hiccup
  "Headless tests for the hiccup construction layer (dsl.md §2, stages 2–3).
   Cases adapted from hiccup's own compiler tests where they transfer
   (normalization, nil/seq handling, loud head validation) plus the kmet-
   specific contracts: closed tag table with did-you-mean, leaf-tag throw,
   :primary shorthand, :key/:ref pseudo-props, record/map passthrough,
   fn components (ComponentFn), keyed reconcile/reuse, refs, and the
   memoization/idle invariant."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.components.alt-screen-flash :as asf]
            [kmet.tui.components.cancellable-loader :as cancellable-loader]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :as macros :refer [with-let defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.stack :as stack]
            [kmet.libs.reakt :as rag]
            [kmet.libs.terminal-image :as timg]))

(defn- joined [tree width]
  (str/join "\n" (h/render-lines tree width)))

;; ── basic compilation & rendering (hiccup: basic-element tests) ───────────

(t/deftest basic-element
  (t/is (str/includes? (joined [:text "hello"] 40) "hello"))
  ;; [:text value] shorthand == explicit props map
  (t/is (= (h/render-lines [:text "hi"] 40)
           (h/render-lines [:text {:text "hi"}] 40))))

(t/deftest props-override-defaults
  ;; text defaults to padding-x 1; an explicit 0 removes it
  (let [lines (mapv str/trimr
                    (h/render-lines [:text {:padding-x 0 :padding-y 0} "hi"]
                                    40))]
    (t/is (= ["hi"] lines))
    (let [padded (h/render-lines [:text "hi"] 40)]
      (t/is (= 3 (count padded)) "default padding-y 1 wraps both sides"))))

(t/deftest nested-containers-render-children
  (let [out (joined [:box {} [:text "a"] [:text "b"]] 20)]
    (t/is (str/includes? out "a"))
    (t/is (str/includes? out "b")))
  ;; three levels deep
  (t/is (str/includes?
         (joined [:container [:v-stack [:text "deep"]]] 30)
         "deep")))

;; ── child normalization (hiccup: nil / seq / string content) ─────────────

(t/deftest nil-children-are-skipped
  ;; this is the when/when-let/if support — free by contract
  (t/is (= ["x"] (mapv str/trimr
                       (h/render-lines [:container
                                        nil
                                        [:text {:padding-x 0 :padding-y 0} "x"]
                                        nil]
                                       10)))))

(t/deftest seqs-are-spliced
  ;; map over data → spliced children (always key them once keys matter)
  (let [tree (list
              [:text {:padding-x 0 :padding-y 0} "one"]
              [:text {:padding-x 0 :padding-y 0} "two"])
        lines (h/render-lines [:v-stack tree] 20)]
    (t/is (= ["one" "two"] (mapv str/trimr lines))))
  ;; a spliced seq at top level = multiple roots
  (t/is (= ["a" "b"] (mapv str/trimr
                           (h/render-lines
                            (list [:text {:padding-x 0 :padding-y 0} "a"]
                                  [:text {:padding-x 0 :padding-y 0} "b"])
                            10)))))

(t/deftest bare-string-children-become-text
  ;; zero-padding bare Text — distinct from the [:text] tag defaults.
  ;; containers pad lines to full width; compare trimmed.
  (t/is (= ["raw"] (mapv str/trimr (h/render-lines [:container "raw"] 10)))))

;; ── passthrough rules ────────────────────────────────────────────────────

(t/deftest records-pass-through-with-identity
  ;; dropping a live component into a tree keeps THE INSTANCE — the
  ;; adapter path that lets unmigrated widgets participate
  (let [c (text/make-text "live" 0 0)]
    (t/is (identical? c (h/compile-element c)))
    (t/is (identical? c (h/compile-tree c)))
    (let [lines (h/render-lines [:container c] 20)]
      (t/is (= ["live"] (mapv str/trimr lines))))))

(t/deftest stack-entry-maps-pass-through-inside-stacks
  ;; VStack/HStack accept entry maps alongside components — compile must
  ;; not touch them INSIDE a stack tag; entries arrive spliced (a map right
  ;; after the tag would be the props slot); anywhere outside a stack they
  ;; throw — a bare {:component c} has no render meaning there.
  (let [entry {:component (text/make-text "e" 0 0) :height 1}]
    (t/is (= ["e"] (mapv str/trimr
                         (h/render-lines [:v-stack (list entry)] 20))))
    (t/is (thrown-with-msg?
           Exception #"outside a stack tag"
           (h/render-lines [:container (list entry)] 10)))))

;; ── pseudo-props ─────────────────────────────────────────────────────────

(t/deftest key-and-ref-pseudo-props-are-stripped
  ;; ctors never see them; no unknown-key crash, no behavior change vs the
  ;; same tree without them. :ref must be a real (hiccup/ref) handle.
  (let [tree (fn [extra]
               [:box extra
                [:text (merge {:padding-x 0 :padding-y 0} extra) "hi"]])]
    (t/is (= (h/render-lines (tree {}) 20)
             (h/render-lines (tree {:key 7}) 20)))
    ;; box: padding-y 1 puts the child at line index 1; box's own blank
    ;; lines trim to empty strings
    (t/is (= ["" " hi" ""]
             (mapv str/trimr (h/render-lines (tree {:key 1}) 20))))
    ;; non-ref :ref values are rejected loudly at parse
    (t/is (thrown-with-msg? Exception #"hiccup/ref"
                            (h/render-lines [:text {:ref :fake} "x"] 10)))))

;; ── contents are concatenated / lazy & eager seqs (hiccup core_test) ────

(t/deftest multiple-string-children-concatenate
  ;; hiccup: [:body "foo" "bar"] — two bare strings become two Texts
  (t/is (= ["foo" "bar"]
           (mapv str/trimr (h/render-lines [:container "foo" "bar"] 20)))))

(t/deftest lists-of-strings-splice-inside-containers
  ;; hiccup: [:body (list "foo" "bar")]
  (t/is (= ["foo" "bar"]
           (mapv str/trimr
                 (h/render-lines [:container (list "foo" "bar")] 20)))))

(t/deftest lazy-seq-children-expand
  ;; hiccup: [:ul (for ...)] — lazy seqs from map/for work as children
  (t/is (= ["a" "b"]
           (mapv str/trimr
                 (h/render-lines
                  [:v-stack (map #(vector :text {:padding-x 0 :padding-y 0} %)
                                 ["a" "b"])]
                  20)))))

;; ── documented divergences from hiccup ──────────────────────────────────

(t/deftest keyword-children-throw-unlike-hiccup
  ;; hiccup renders [:div :foo] as "foo"; here a bare keyword child is
  ;; almost always a bug (forgotten props / wrong value) — stay loud.
  (t/is (thrown? Exception (h/render-lines [:container :foo] 10))))

(t/deftest vector-without-tag-head-as-child-throws-like-hiccup
  ;; hiccup: "vecs don't expand - error if vec doesn't have tag name"
  (t/is (thrown? Exception
                 (h/render-lines [:container [[:text {:padding-x 0
                                                      :padding-y 0} "a"]]]
                                 10))))

(t/deftest nil-and-missing-text-content-render-empty
  ;; hiccup coerces nil content to nothing; Text.render treats nil like
  ;; blank, and BLANK TEXT RENDERS ZERO LINES — an invisible placeholder
  ;; (padding included), not a stack of blanks.
  (t/is (= [] (h/render-lines [:text nil] 20)))
  (t/is (= [] (h/render-lines [:text {}] 20)))
  (t/is (= [] (h/render-lines [:text {} nil] 20))))

(t/deftest bare-map-children-throw
  ;; stack-entry maps ({:component c}) pass through as CHILDREN; a BARE
  ;; data map child is a mistake that would detonate at render time —
  ;; throw at compile instead. (A map directly after the tag is the props
  ;; slot, by design.)
  (t/is (thrown? Exception
                 (h/render-lines [:container "x" {:not-a-component true}]
                                 10)))
  (t/is (thrown? Exception (h/render-lines [:container {} {}] 10))))

(t/deftest unknown-tags-throw-loudly
  (let [e (try (h/render-lines [:tst "x"] 20) nil
               (catch Exception ex ex))]
    (t/is (some? e))
    (t/is (str/includes? (ex-message e) "unknown tag :tst"))
    (t/is (str/includes? (ex-message e) ":text") "lists known tags")))

(t/deftest invalid-heads-throw-loudly
  (t/is (thrown? Exception (h/render-lines ["not-a-tag"] 10)))
  (t/is (thrown? Exception (h/render-lines ['sym 1] 10)))
  (t/is (thrown? Exception (h/render-lines 42 10))))

(t/deftest fn-heads-are-function-components
  ;; stage 3: fn heads compile to ComponentFn wrappers — reactive bodies,
  ;; props passed through. Bare @plain-atom reads are untracked; the batched
  ;; fallback keeps them live by re-deriving every pass (never stale, just
  ;; not narrow)
  (let [state (atom "live")
        status (fn [{:keys [label]}]
                 [:text {:padding-x 0} (str label ": " @state)])
        r (h/root (fn [_] [status {:label "s"}]))]
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "s: live"))
    (reset! state "changed")
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "changed"))))

(t/deftest children-on-leaf-tags-throw
  (t/is (thrown? Exception (h/render-lines [:text "a" "b"] 10)))
  (t/is (thrown? Exception (h/render-lines [:spacer 2 [:text "x"]] 10))))

(t/deftest dynamic-border-leaf-renders-colored-rules
  ;; :color-fn as prop map or primary shorthand; the rule fills the width
  (let [marker (fn [s] (str "<b>" s "</b>"))
        lines (h/render-lines
               [:container {}
                [:dynamic-border {:color-fn marker}]
                [:text {:padding-x 0 :padding-y 0} "x"]
                [:dynamic-border marker]] 40)]
    (t/is (= 3 (count lines)))
    (t/is (= (first lines) (str "<b>" (apply str (repeat 40 "─")) "</b>")))
    (t/is (= (last lines) (first lines)))))

(t/deftest dynamic-border-default-color-fn-renders
  ;; no :color-fn → the component's own default (theme border color)
  (let [lines (h/render-lines [:container {} [:dynamic-border]] 40)]
    (t/is (= 1 (count lines)))
    (t/is (str/includes? (first lines) "─"))))

;; ── full tag coverage: every component has a tag (React parity) ─────────

(t/deftest truncated-text-tag
  ;; truncates to the width; :padding-x insets
  (let [wide (h/render-lines [:truncated-text {:padding-x 0} "abcdefghijklmnop"] 10)]
    (t/is (= 1 (count wide)))
    (t/is (= 10 (count (first wide))))
    (t/is (str/ends-with? (first wide) "...")))
  (let [primary (h/render-lines [:truncated-text "hi"] 12)]
    (t/is (str/includes? (first primary) "hi"))))

(t/deftest spinner-tag
  ;; inactive by default (invisible); active shows prefix + frame + text
  (t/is (empty? (h/render-lines [:spinner "Lazy"] 20)))
  (let [lines (h/render-lines [:spinner {:text "Work" :active true :prefix ""}] 20)]
    (t/is (= 2 (count lines)) "leading blank line (pi Loader shape)")
    (t/is (str/includes? (last lines) "Work"))))

(t/deftest input-tag
  ;; :value pre-fills; :on-submit is wired; props are live (the apply path,
  ;; tui.md §2.2) and the :ref + setter path stays valid
  (let [r (h/ref)
        submitted (atom nil)
        on-submit (fn [v] (reset! submitted v))
        r2 (h/root (fn [_]
                     [:input {:value "abc" :on-submit on-submit :ref r}]))]
    (core/render r2 20)
    (let [i (deref r)]
      (t/is (some? i) ":ref points at the Input instance")
      (t/is (= "abc" (input/input-get-value i)) ":value pre-filled")
      (input/input-set-value! i "xyz")
      (t/is (= "xyz" (input/input-get-value i)) "setter updates the instance")
      (protocols/handle-input i "\r")
      (t/is (= "xyz" @submitted) "enter fires :on-submit with the value"))))

(t/deftest expandable-text-tag
  (let [mk (fn [expanded?] [:expandable-text {:collapsed-fn (fn [] "COLLAPSED")
                                              :expanded-fn (fn [] "EXPANDED")
                                              :expanded? expanded?}])]
    (t/is (str/includes? (str/join "\n" (h/render-lines (mk false) 30)) "COLLAPSED"))
    (t/is (str/includes? (str/join "\n" (h/render-lines (mk true) 30)) "EXPANDED"))))

(t/deftest image-tag-renders-fallback
  ;; capabilities stubbed to "no image protocol" — the fallback must not
  ;; depend on the terminal the test happens to run in
  (let [prev-caps (timg/get-capabilities)]
    (try
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks false})
      (let [lines (h/render-lines [:image {:base64-data "AAA=" :mime-type "image/png"}] 40)]
        (t/is (str/includes? (str/join "\n" lines) "image/png")))
      (finally (timg/set-capabilities! prev-caps)))))

(t/deftest select-list-tag
  ;; primary :items; callbacks wired on the instance
  (let [chosen (atom nil)
        on-select (fn [item] (reset! chosen item))
        r (h/ref)
        lines (h/render-lines
               [:select-list [{:label "one" :value 1} {:label "two" :value 2}]]
               20)
        r2 (h/root (fn [_]
                     [:select-list {:items [{:label "a" :value :a}]
                                    :on-select on-select :ref r}]))]
    (t/is (str/includes? (str/join "\n" lines) "one"))
    (core/render r2 20)
    (let [sl (deref r)]
      (t/is (some? sl))
      (protocols/handle-input sl "\r")
      (t/is (some? @chosen) "enter fires :on-select with the item"))))

(t/deftest settings-list-tag
  (let [lines (h/render-lines
               [:settings-list [{:id :a :label "A" :value "x" :values ["x" "y"]}]]
               30)]
    (t/is (str/includes? (str/join "\n" lines) "A"))))

(t/deftest editor-tag
  ;; :text pre-fills the editor
  (let [lines (h/render-lines [:editor {:text "hi there" :height 3}] 40)]
    (t/is (str/includes? (str/join "\n" lines) "hi there"))))

(t/deftest cancellable-loader-tag
  ;; default spinner is active and shows :text; a :spinner prop wins
  (let [lines (h/render-lines [:cancellable-loader {:text "Loading"}] 20)]
    (t/is (str/includes? (str/join "\n" lines) "Loading"))))

(t/deftest alt-screen-flash-tag
  ;; :request-render patches through the apply path (tui.md §2.2) — a fresh
  ;; closure per pass keeps the instance and its pending entries
  (let [r (h/ref)
        cb (atom (fn []))
        root (h/root (fn [_] [:alt-screen-flash {:request-render @cb :ref r}]))]
    (core/render root 30)
    (let [inst (deref r)]
      (t/is (some? inst) ":ref points at the container")
      (asf/alt-screen-flash! inst "Saved!" :duration-ms 60000)
      (t/is (str/includes? (str/join "\n" (core/render root 30)) "Saved!")
            "entries render inline")
      (reset! cb (fn []))
      (t/is (str/includes? (str/join "\n" (core/render root 30)) "Saved!")
            "a fresh callback closure patches — the pending entry survives")
      (t/is (identical? inst (deref r))
            "instance kept across the prop change"))))

(t/deftest scroll-view-tag
  ;; single child rendered inside the viewport
  (let [lines (h/render-lines
               [:scroll-view {:scrollbar :always} [:text {:padding-x 0} "inner"]] 20)]
    (t/is (str/includes? (str/join "\n" lines) "inner")))
  ;; more than one child is a bug — throw loudly
  (t/is (thrown? Exception
                 (h/render-lines [:scroll-view {} [:text "a"] [:text "b"]] 10)))
  ;; child swap across reconcile passes keeps the scroll-view instance
  (let [state (atom "v1")
        r (h/root (fn [_] [:scroll-view {} [:text {:padding-x 0} @state]]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v1"))
    (reset! state "v2")
    (protocols/invalidate r)
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v2"))))

(t/deftest stateful-leaf-identity-stable-props
  ;; a stateful host leaf keeps its instance (and state) across passes
  ;; while its props stay =-equal — a fresh closure prop would rebuild it
  (let [r (h/ref)
        r2 (h/root (fn [_] [:select-list {:items [{:label "a" :value :a}]
                                          :ref r}]))]
    (core/render r2 20)
    (let [first (deref r)]
      (t/is (some? first))
      (core/render r2 20)
      (t/is (identical? first (deref r)) "same props → same instance"))))

;; ═══════════════════════════════════════════════════════════════════════
;; R1 — prop→state apply path (tui.md §2.2–§2.3)
;; ═══════════════════════════════════════════════════════════════════════

(t/deftest stateful-tag-props-patch-in-place
  ;; a changed prop on an :apply tag patches the live instance — identity,
  ;; state and focus survive; only a prop the tag cannot express rebuilds
  (let [iref (h/ref)
        v (atom "a")
        root (h/root (fn [_] [:input {:ref iref :value (rag/tracked-deref v)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (h/reset-counters!)
      (reset! v "ab")
      (core/render root 40)
      (t/is (identical? i1 (deref iref)) "live prop change patches, no rebuild")
      (t/is (= "ab" (input/input-get-value (deref iref))))
      (t/is (= 1 (:applies (h/counters))))
      (t/is (zero? (:constructs (h/counters))) "no fresh construction")
      ;; the patched instance remembers the new props — an equal pass is
      ;; the plain reuse fast path again
      (core/render root 40)
      (t/is (identical? i1 (deref iref)))
      (t/is (= 1 (:applies (h/counters))) "no re-apply on unchanged props"))))

(t/deftest materialize-ref-compiles-on-demand
  ;; an element's ref fills during a render pass; a handler that needs the
  ;; instance before the host painted compiles the tree itself instead of
  ;; dropping the key (the dialog/selector input-forwarding path)
  (let [iref (h/ref)
        root (h/root (fn [_] [:container {} [:input {:ref iref}]]))]
    (t/is (nil? (deref iref)) "unrendered tree: the ref is empty")
    (let [i (h/materialize-ref! root iref)]
      (t/is (some? i) "materializing compiles the body")
      (t/is (identical? i (deref iref)) "and fills the ref")
      (t/is (some? (h/materialize-ref! root iref)) "second call is a plain deref")))
  ;; an element genuinely absent from the tree stays nil — callers decide
  (let [iref (h/ref)
        present? (atom false)
        root (h/root (fn [_]
                       (if (rag/tracked-deref present?)
                         [:container {} [:input {:ref iref}]]
                         [:container {} [:text {:text "no field"}]])))]
    (core/render root 40)
    (t/is (nil? (deref iref)))
    (t/is (nil? (h/materialize-ref! root iref)) "absent element → nil")
    (t/is (nil? (h/materialize-ref! root iref)) "and stays nil, no crash")))

(t/deftest input-tag-focus-is-data
  ;; :focused? is the element's own emphasis flag (cursor + key
  ;; eligibility) — set on construct, patched in place when it changes,
  ;; never a rebuild trigger. Routing stays imperative (tui-set-focus).
  (let [iref (h/ref)
        on (atom false)
        root (h/root (fn [_] [:input {:ref iref
                                      :focused? (rag/tracked-deref on)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (t/is (false? (protocols/focused i1)) "construct applies the false prop")
      (h/reset-counters!)
      (reset! on true)
      (core/render root 40)
      (t/is (identical? i1 (deref iref)) "a focus change patches, no rebuild")
      (t/is (true? (protocols/focused i1)))
      (t/is (= 1 (:applies (h/counters))))
      (t/is (zero? (:constructs (h/counters))) "no fresh construction")
      ;; an unchanged prop never fights an imperative write
      (protocols/set-focused! i1 false)
      (core/render root 40)
      (t/is (false? (protocols/focused i1))
            "equal props take the reuse fast path — no re-apply"))))

(t/deftest input-tag-focus-survives-a-branch-rebuild
  ;; the reason the prop exists: a tagged input that leaves a branch is
  ;; disposed and rebuilt as a fresh instance; :focused? restores the
  ;; emphasis the rebuilt instance would otherwise lose (the host would have
  ;; to re-focus it by hand — the session selector's list⇄rename switch).
  (let [iref (h/ref)
        mode (atom :list)
        root (h/root (fn [_]
                       (if (= :rename (rag/tracked-deref mode))
                         [:container {}
                          [:input {:ref iref :value "name" :focused? true}]]
                         [:container {}
                          [:text {:text "list"}]])))]
    (core/render root 40)
    (t/is (nil? (deref iref)) "no input in list mode")
    (h/reset-counters!)
    (reset! mode :rename)
    (core/render root 40)
    (let [i1 (deref iref)]
      (t/is (some? i1) "rename mode constructs the input")
      (t/is (true? (protocols/focused i1)) "the rebuilt instance comes back focused")
      (t/is (= "name" (input/input-get-value i1)) "prefill rides the :value prop"))
    (reset! mode :list)
    (core/render root 40)
    (t/is (nil? (deref iref)) "leaving the branch retires it")
    (t/is (pos? (:disposals (h/counters))) "retirement disposes the instance")))

(t/deftest input-tag-uncontrolled-text-survives-prop-change
  ;; an absent :value prop is not a write — typed text survives an
  ;; unrelated prop change (the construct-equivalent patch contract)
  (let [iref (h/ref)
        submit (atom (fn [_]))
        root (h/root (fn [_] [:input {:ref iref
                                      :on-submit (rag/tracked-deref submit)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (protocols/handle-input i1 "hi")
      (reset! submit (fn [_] :other))
      (core/render root 40)
      (t/is (identical? i1 (deref iref)))
      (t/is (= "hi" (input/input-get-value (deref iref)))
            "no :value prop → nothing is written over the typed text"))))

(t/deftest input-tag-unchanged-value-never-clobbers-typing
  ;; the write gate on :value is the PROP change, not the props-map change:
  ;; a static :value plus typing survives an unrelated prop change, while a
  ;; real :value change is an instruction and wins (nil ⇒ the construct
  ;; default, an empty input)
  (let [iref (h/ref)
        v (atom "abc")
        submit (atom (fn [_]))
        root (h/root (fn [_] [:input {:ref iref
                                      :value (rag/tracked-deref v)
                                      :on-submit (rag/tracked-deref submit)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (protocols/handle-input i1 "d")
      (t/is (= "dabc" (input/input-get-value i1)) "typed at the cursor")
      (reset! submit (fn [_] :other))
      (core/render root 40)
      (t/is (= "dabc" (input/input-get-value i1))
            ":value unchanged from the previous pass → no write")
      (reset! v "xyz")
      (core/render root 40)
      (t/is (= "xyz" (input/input-get-value i1))
            "a changed :value prop patches the live instance")
      (reset! v nil)
      (core/render root 40)
      (t/is (= "" (input/input-get-value i1))
            "nil ⇒ the construct default, like make-input with no value"))))

(t/deftest input-tag-on-change-fires-on-typing
  ;; :on-change has editor parity: a value-changing edit fires it with the
  ;; current value, a cursor-only move does not, and an unrelated prop pass
  ;; neither fires it nor clobbers the typed text
  (let [iref (h/ref)
        submit (atom (fn [_]))
        seen (atom [])
        on-change (fn [v] (swap! seen conj v))
        root (h/root (fn [_] [:input {:ref iref
                                      :on-change on-change
                                      :on-submit (rag/tracked-deref submit)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (protocols/handle-input i1 "a")
      (protocols/handle-input i1 "b")
      (t/is (= ["a" "ab"] @seen) "typing fires :on-change with the value")
      (protocols/handle-input i1 "\u001b[D")
      (t/is (= ["a" "ab"] @seen) "a cursor-only move is not a change")
      (reset! submit (fn [_] :other))
      (core/render root 40)
      (t/is (= ["a" "ab"] @seen) "an unrelated prop pass fires nothing")
      (t/is (= "ab" (input/input-get-value i1)) "typed text survived")
      (protocols/handle-input i1 "c")
      (t/is (= ["a" "ab" "acb"] @seen)
            "the next edit fires with the value at the live cursor"))))

(t/deftest input-tag-cursor-prop-prefills-and-patches
  ;; :cursor is a state-carrying prop like :value: it positions the cursor
  ;; at construction (dialog prefills), a changed value patches the live
  ;; instance, and an unchanged one never clobbers a user-moved cursor
  ;; (nil = unmanaged, as at construction)
  (let [iref (h/ref)
        cur (atom 3)
        cb (atom (fn [_]))
        root (h/root (fn [_] [:input {:ref iref
                                      :value "abcdef"
                                      :cursor (rag/tracked-deref cur)
                                      :on-change (rag/tracked-deref cb)}]))]
    (core/render root 40)
    (let [i1 (deref iref)]
      (t/is (= 3 @(:cursor-atom i1)) "constructed at the :cursor")
      (protocols/handle-input i1 "\u001b[H")
      (t/is (zero? @(:cursor-atom i1)) "home moved the live cursor")
      (reset! cb (fn [_] :other))
      (core/render root 40)
      (t/is (identical? i1 (deref iref)) "a cursor prop patches, no rebuild")
      (t/is (zero? @(:cursor-atom i1))
            "the unchanged :cursor prop did not write the live cursor")
      (reset! cur 6)
      (core/render root 40)
      (t/is (= 6 @(:cursor-atom i1)) "a changed :cursor prop patches")
      (reset! cur nil)
      (core/render root 40)
      (t/is (= 6 @(:cursor-atom i1)) "nil :cursor is unmanaged — no write"))))

(t/deftest editor-tag-unchanged-text-never-clobbers-typing
  ;; the same gate on :text: an unrelated prop change (:height) patches the
  ;; live editor without resetting what the user typed
  (let [eref (h/ref)
        h (atom 3)
        root (h/root (fn [_] [:editor {:ref eref
                                       :text "draft"
                                       :height (rag/tracked-deref h)}]))]
    (core/render root 40)
    (let [e1 (deref eref)]
      (protocols/handle-input e1 "X")
      (t/is (= "draftX" (editor/editor-get-text e1)))
      (reset! h 5)
      (core/render root 40)
      (t/is (identical? e1 (deref eref)))
      (t/is (= "draftX" (editor/editor-get-text e1))
            "the unchanged :text prop did not clobber live typing")
      (t/is (= 5 @(:height-atom e1)) "the changed :height prop patched"))))

(t/deftest editor-tag-props-patch-in-place
  ;; :text patches =-gated; structural props (border/keybindings) have no
  ;; setters — the apply fn declines and the leaf rebuilds
  (let [eref (h/ref)
        draft (atom "x")
        border (atom :normal)
        root (h/root (fn [_] [:editor {:ref eref
                                       :text (rag/tracked-deref draft)
                                       :border (rag/tracked-deref border)
                                       :height 3}]))]
    (core/render root 40)
    (let [e1 (deref eref)]
      (reset! draft "xy")
      (core/render root 40)
      (t/is (identical? e1 (deref eref)) "text change patches the live editor")
      (t/is (= "xy" (editor/editor-get-text (deref eref))))
      (reset! border :ascii)
      (core/render root 40)
      (t/is (not (identical? e1 (deref eref)))
            "a structural prop change rebuilds (apply declined)"))))

(t/deftest select-list-tag-props-patch-in-place
  ;; items replace without a rebuild; an unrelated prop change must not wipe
  ;; the typed filter, and an items REFRESH (the declarative patch) keeps
  ;; the filter and the selection — same question re-asked, not a wholesale
  ;; replacement (that is select-list-set-items!'s resetting default)
  (let [sref (h/ref)
        items (atom [{:label "one"} {:label "two"}])
        on-esc (atom (fn []))
        root (h/root (fn [_] [:select-list {:ref sref
                                            :items (rag/tracked-deref items)
                                            :on-escape (rag/tracked-deref on-esc)}]))]
    (core/render root 40)
    (let [s1 (deref sref)]
      (protocols/set-focused! s1 true)
      ;; selection: move to the second row, then refresh the items
      (protocols/handle-input s1 "\u001b[B")
      (t/is (= 1 @(:selected-idx-atom s1)))
      (reset! items [{:label "one"} {:label "two"} {:label "three"}])
      (core/render root 40)
      (t/is (identical? s1 (deref sref)) "items change patches, no rebuild")
      (t/is (= 3 (count @(:items-atom s1))))
      (t/is (= 1 @(:selected-idx-atom s1))
            "the selection index survived the refresh")
      (t/is (= "two" (:label (select-list/select-list-get-selected s1))))
      ;; filter: type, then an unrelated prop change
      (protocols/handle-input s1 "t")
      (t/is (= "t" @(:filter-atom s1)) "typed filter installed")
      (reset! on-esc (fn [] :new))
      (core/render root 40)
      (t/is (identical? s1 (deref sref)))
      (t/is (= "t" @(:filter-atom s1)) "unrelated prop change kept the filter")
      (t/is (protocols/focused s1) "focus survives the patch")
      (t/is (= "two" (:label (select-list/select-list-get-selected s1)))
            "the typed filter still selects through the patched instance")
      ;; … and a refresh keeps the typed filter too
      (reset! items (conj @items {:label "tango"}))
      (core/render root 40)
      (t/is (= 4 (count @(:items-atom s1))))
      (t/is (= "t" @(:filter-atom s1)) "an items refresh kept the typed filter")
      (t/is (= "two" (:label (select-list/select-list-get-selected s1)))))))

(t/deftest select-list-set-items-default-still-resets
  ;; the imperative wholesale-replacement default is unchanged: without
  ;; :preserve-state? the query and selection reset with the new items
  (let [sl (select-list/make-select-list [{:label "one"} {:label "two"}])]
    (protocols/handle-input sl "t")
    (t/is (= "t" @(:filter-atom sl)))
    (select-list/select-list-set-items! sl [{:label "three"}])
    (t/is (= "" @(:filter-atom sl)) "the filter reset with the new items")
    (t/is (zero? @(:selected-idx-atom sl))))
  ;; …and :preserve-state? keeps the selection position
  (let [sl (select-list/make-select-list [{:label "one"} {:label "two"}])]
    (protocols/handle-input sl "\u001b[B")
    (t/is (= 1 @(:selected-idx-atom sl)))
    (select-list/select-list-set-items! sl [{:label "a"} {:label "b"} {:label "c"}]
                                        {:preserve-state? true})
    (t/is (= 1 @(:selected-idx-atom sl)) "preserve kept the selection")))

(t/deftest settings-list-tag-props-patch-in-place
  (let [gref (h/ref)
        rows (atom [{:id :a :label "A" :value true :values [true false]}])
        search (atom false)
        root (h/root (fn [_] [:settings-list {:ref gref
                                              :items (rag/tracked-deref rows)
                                              :enable-search (rag/tracked-deref search)}]))]
    (core/render root 40)
    (let [g1 (deref gref)]
      (reset! rows [{:id :a :label "A" :value false :values [true false]}])
      (core/render root 40)
      (t/is (identical? g1 (deref gref)) "items change patches, no rebuild")
      (t/is (false? (:value (settings-list/settings-list-get-item (deref gref) :a))))
      (reset! search true)
      (core/render root 40)
      (t/is (not (identical? g1 (deref gref)))
            "enable-search is construct-time — a change rebuilds"))))

(t/deftest settings-list-items-refresh-keeps-the-search-query
  ;; an items refresh must not eat the user's query — the search box text
  ;; and the filter atom both stay (the resetting set-items! default is the
  ;; wholesale-replacement variant)
  (let [gref (h/ref)
        rows (atom [{:id :a :label "Alpha" :value "x" :values ["x" "y"]}
                    {:id :b :label "Beta" :value "z" :values ["z" "w"]}])
        root (h/root (fn [_] [:settings-list {:ref gref
                                              :items (rag/tracked-deref rows)
                                              :enable-search true}]))]
    (core/render root 40)
    (let [g1 (deref gref)]
      (protocols/handle-input g1 "b")
      (t/is (= "b" @(:filter-atom g1)) "typed query installed")
      (reset! rows (conj @rows {:id :c :label "Beta 2" :value "q" :values ["q"]}))
      (core/render root 40)
      (t/is (identical? g1 (deref gref)) "items refresh patches, no rebuild")
      (t/is (= 3 (count @(:items-atom g1))))
      (t/is (= "b" @(:filter-atom g1)) "an items refresh kept the query")
      (t/is (= "b" (input/input-get-value @(:search-input-atom g1)))
            "the search box still shows the query the list applies"))))

(t/deftest settings-list-set-items-default-still-resets
  ;; the imperative default resets both halves of the query — the filter
  ;; atom AND the visible search box — so they cannot drift apart
  (let [sl (settings-list/make-settings-list
            [{:id :a :label "Alpha" :value "x" :values ["x"]}
             {:id :b :label "Beta" :value "z" :values ["z"]}]
            :enable-search true)]
    (protocols/handle-input sl "b")
    (settings-list/settings-list-set-items! sl [{:id :c :label "Gamma"
                                                 :value "q" :values ["q"]}])
    (t/is (= "" @(:filter-atom sl)))
    (t/is (= "" (input/input-get-value @(:search-input-atom sl))))
    (t/is (zero? @(:selected-idx-atom sl)))))

(t/deftest expandable-text-tag-props-patch-in-place
  (let [col (fn [] "collapsed")
        exp (fn [] "expanded")
        xref (h/ref)
        open? (atom false)
        root (h/root (fn [_] [:expandable-text {:ref xref
                                                :collapsed-fn col
                                                :expanded-fn exp
                                                :expanded? (rag/tracked-deref open?)}]))]
    (core/render root 40)
    (let [x1 (deref xref)]
      (reset! open? true)
      (core/render root 40)
      (t/is (identical? x1 (deref xref)))
      (t/is (expandable-text/expandable-text-get-expanded (deref xref))))))

(t/deftest expandable-text-unchanged-expanded-never-collapses-live-state
  ;; the gate on :expanded? is the prop change; a props-map change that
  ;; leaves it alone (a :padding-x key appearing — equal to its default)
  ;; neither rebuilds nor collapses a programmatic toggle
  (let [col (fn [] "collapsed")
        exp (fn [] "expanded")
        xref (h/ref)
        extra (atom nil)
        root (h/root (fn [_] [:expandable-text
                              (merge {:ref xref
                                      :collapsed-fn col
                                      :expanded-fn exp
                                      :expanded? false}
                                     (rag/tracked-deref extra))]))]
    (core/render root 40)
    (let [x1 (deref xref)]
      (expandable-text/expandable-text-set-expanded! x1 true)
      (reset! extra {:padding-x 0})
      (core/render root 40)
      (t/is (identical? x1 (deref xref))
            "a missing padding key equals its 0 default — no rebuild churn")
      (t/is (expandable-text/expandable-text-get-expanded (deref xref))
            "the unchanged :expanded? prop did not collapse the live state"))))

(t/deftest spinner-tag-props-patch-without-restarting-the-animation
  ;; :text patches in place — a rebuild would restart the animation clock;
  ;; :active flips start/stop; :frames has no faithful setter (the only one,
  ;; set-indicator!, switches the spinner to verbatim rendering), so a
  ;; change there rebuilds
  (let [sref (h/ref)
        msg (atom "a")
        active (atom true)
        root (h/root (fn [_] [:spinner {:ref sref
                                        :text (rag/tracked-deref msg)
                                        :active (rag/tracked-deref active)
                                        :prefix ""}]))]
    (core/render root 30)
    (let [s1 (deref sref)
          start1 @(:start-atom s1)]
      (reset! msg "b")
      (core/render root 30)
      (t/is (identical? s1 (deref sref)))
      (t/is (= "b" @(:text-atom (deref sref))))
      (t/is (identical? start1 @(:start-atom (deref sref)))
            "the animation clock was not reset")
      (reset! active false)
      (core/render root 30)
      (t/is (identical? s1 (deref sref)))
      (t/is (false? (spinner/spinner-active? (deref sref))))
      (reset! active true)
      (core/render root 30)
      (t/is (spinner/spinner-active? (deref sref))))))

(t/deftest spinner-tag-frames-change-rebuilds
  (let [sref (h/ref)
        frames (atom nil)
        root (h/root (fn [_] [:spinner {:ref sref :text "x" :active true
                                        :prefix ""
                                        :frames (rag/tracked-deref frames)}]))]
    (core/render root 30)
    (let [s1 (deref sref)]
      (reset! frames ["A"])
      (core/render root 30)
      (t/is (not (identical? s1 (deref sref)))
            ":frames cannot be patched faithfully — the leaf rebuilds"))))

(t/deftest cancellable-loader-tag-props-patch-in-place
  ;; :on-abort patches (the abort signal survives); a changed :spinner child
  ;; cannot be swapped — it rebuilds (and the retired loader's dispose stops
  ;; the old spinner, pi: CancellableLoader.dispose → Loader.stop)
  (let [cref (h/ref)
        cb (atom (fn []))
        root (h/root (fn [_] [:cancellable-loader
                              {:ref cref :text "loading"
                               :on-abort (rag/tracked-deref cb)}]))]
    (core/render root 30)
    (let [c1 (deref cref)]
      (reset! cb (fn [] :other))
      (core/render root 30)
      (t/is (identical? c1 (deref cref)))
      (t/is (false? (cancellable-loader/cancellable-loader-aborted? (deref cref)))
            "the abort signal survived the patch")
      (let [sp (atom (spinner/make-spinner :text "one" :active true))
            sref (h/ref)
            lroot (h/root (fn [_] [:cancellable-loader
                                   {:ref sref
                                    :spinner (rag/tracked-deref sp)}]))]
        (core/render lroot 30)
        (let [c2 (deref sref)
              old-spinner (:spinner c2)]
          (reset! sp (spinner/make-spinner :text "two" :active true))
          (core/render lroot 30)
          (t/is (not (identical? c2 (deref sref)))
                "a changed :spinner child rebuilds")
          (t/is (not (spinner/spinner-active? old-spinner))
                "the retired loader's dispose stopped the old spinner"))))))

(t/deftest writable-cursor-two-way-binding-through-a-body
  ;; R1+R2: a writable cursor read through a body re-renders when written
  ;; through — the write is an ordinary source change
  (let [cfg (atom {:transport :curl})
        cur (rag/writable-cursor cfg [:transport])
        root (h/root (fn [_] [:text {:padding-x 0}
                              (name (rag/tracked-deref cur))]))]
    (t/is (str/includes? (str/join "\n" (core/render root 20)) "curl"))
    (t/is (= :babashka (rag/cursor-reset! cur :babashka)))
    (t/is (str/includes? (str/join "\n" (core/render root 20)) "babashka"))))

;; ── root mounting (dsl.md §2.6) ──────────────────────────────────────────

(t/deftest root-mounts-elements-and-fns
  ;; static element root
  (let [r (h/root [:text {:padding-x 0 :padding-y 0} "static"])]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "static")))
  ;; fn root re-derives per frame from closures over app atoms (batched)
  (let [state (atom "v1")
        r (h/root (fn [_props] [:text {:padding-x 0 :padding-y 0} @state]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v1"))
    (reset! state "v2")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v2")
          "bare fn roots re-derive on the next pass")))

(t/deftest tracked-bodies-memoize-until-deps-change
  ;; stage-3 semantics: a body reading reactive inputs through tracked-deref
  ;; runs ONCE while deps hold, re-runs exactly when one changes. The idle-UI
  ;; invariant: zero fn bodies when nothing changed.
  (let [calls (atom 0)
        s (atom "a")
        r (h/root (fn [_]
                    (swap! calls inc)
                    [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 1 @calls) "two identical passes, one body run")
    (reset! s "b")
    (core/render r 20)
    (t/is (= 2 @calls) "dep change re-runs the body")
    (core/render r 20)
    (t/is (= 2 @calls) "still clean — no extra runs")))

(t/deftest root-of-seq-tree-renders-all-roots
  (let [r (h/root [[:text {:padding-x 0 :padding-y 0} "one"]
                   [:text {:padding-x 0 :padding-y 0} "two"]])]
    (t/is (= ["one" "two"]
             (mapv str/trimr (core/render r 10))))))
;; ═══════════════════════════════════════════════════════════════════════
;; Stage 3 — keyed reconcile, refs, disposal, scheduling (dsl.md §2.3–§2.5)
;; ═══════════════════════════════════════════════════════════════════════

;; A FOREIGN component: constructed outside the DSL (no :dsl/meta stamp),
;; so reconcile must reuse it by identity and NEVER dispose it.
(defcomponent ForeignText nil [text-atom cache disposed?]
  (render [_this _width] [(str/trimr @text-atom)])
  (dispose [_this] (reset! disposed? true)))

(t/deftest keyed-reuse-survives-prepending
  ;; the motivating case: prepending must not rebuild the other keyed
  ;; siblings — identity rides the key (dsl.md §2.3). Refs are STABLE
  ;; handles created once, outside the body.
  (let [ref0 (h/ref) ref1 (h/ref) ref2 (h/ref)
        refs {0 ref0 1 ref1 2 ref2}
        msgs (atom [{:id 1 :text "one"} {:id 2 :text "two"}])
        r (h/root (fn [_]
                    [:container
                     (map (fn [{:keys [id text]}]
                            [:text {:key id :padding-x 0 :padding-y 0
                                    :ref (refs id)} text])
                          @msgs)]))]
    (core/render r 20)
    (let [one (deref ref1)]
      (t/is (instance? kmet.tui.components.text.Text one) "ref filled on mount")
      ;; prepend id 0 — id 1 keeps its instance
      (swap! msgs (fn [m] (vec (cons {:id 0 :text "zero"} m))))
      (core/render r 20)
      (t/is (identical? one (deref ref1))
            "same key → same record across prepend")
      ;; remove id 1 entirely → its ref clears, others survive
      (swap! msgs (fn [m] (vec (remove #(= 1 (:id %)) m))))
      (core/render r 20)
      (t/is (nil? (deref ref1)) "removed element cleared its ref")
      (t/is (some? (deref ref0)) "survivor's ref still filled"))))

(t/deftest unkeyed-siblings-match-in-order-and-reuse
  ;; one bucket per match-kind is consumed in order (i-th desired ↔ i-th
  ;; previous, the bucket-cursor contract): an unchanged re-derive reuses
  ;; every instance without constructing, and a shrink keeps the head while
  ;; the removed tail clears its ref
  (let [refs (mapv (fn [_] (h/ref)) (range 4))
        n (atom 4)
        r (h/root (fn [_]
                    [:container
                     (map-indexed (fn [i _]
                                    [:text {:padding-x 0 :padding-y 0
                                            :ref (refs i)}
                                     (str "row" i)])
                                  (range @n))]))]
    (core/render r 20)
    (let [live (mapv deref refs)]
      (t/is (every? some? live) "all four rows mounted")
      (h/reset-counters!)
      (core/render r 20)
      (t/is (zero? (:constructs (h/counters)))
            "an unchanged pass constructs nothing (every sibling reused)")
      (t/is (zero? (:disposals (h/counters))))
      (reset! n 2)
      (core/render r 20)
      (t/is (identical? (nth live 0) (deref (refs 0)))
            "the surviving head kept its instance")
      (t/is (identical? (nth live 1) (deref (refs 1))))
      (t/is (nil? (deref (refs 2))) "the removed tail cleared its ref"))))

(t/deftest metadata-key-behaves-as-a-key
  ;; reagent-style ^{:key k} on the element vector, as an alternative to the
  ;; :key prop — same reuse contract, same duplicate detection
  (let [refs {0 (h/ref) 1 (h/ref)}
        msgs (atom [1])
        r (h/root (fn [_]
                    [:container
                     (map (fn [id]
                            ^{:key id} [:text {:padding-x 0 :padding-y 0
                                               :ref (refs id)} (str "m" id)])
                          @msgs)]))]
    (core/render r 20)
    (let [one (deref (refs 1))]
      (t/is (instance? kmet.tui.components.text.Text one))
      ;; prepend id 0: the metadata-keyed survivor keeps its instance
      (swap! msgs (fn [m] (vec (cons 0 m))))
      (core/render r 20)
      (t/is (identical? one (deref (refs 1)))
            "metadata key survives a prepend like a :key prop")
      (t/is (= ["m0" "m1"] (mapv str/trimr (core/render r 20))))
      (t/is (some? (deref (refs 0))) "new metadata-keyed sibling mounted")
      ;; metadata keys participate in duplicate detection
      (t/is (thrown-with-msg?
             Exception #"duplicate :key"
             (h/render-lines
              [:container
               ^{:key :a} [:text {:padding-x 0 :padding-y 0} "1"]
               ^{:key :a} [:text {:padding-x 0 :padding-y 0} "2"]]
              10))))))

(t/deftest metadata-key-props-win-and-fn-components-take-it-too
  (t/testing "an explicit :key prop wins over the metadata"
    ;; both siblings are keyed :a once the metadata loses, so the props key
    ;; must be the one in effect for the duplicate to be detected
    (t/is (thrown-with-msg?
           Exception #"duplicate :key"
           (h/render-lines
            [:container
             ^{:key :meta} [:text {:key :a :padding-x 0 :padding-y 0} "1"]
             [:text {:key :a :padding-x 0 :padding-y 0} "2"]]
            10))))
  (t/testing "fn component invocations are keyed by metadata as well"
    (let [inits (atom [])
          kid (fn [{:keys [id]}]
                (with-let [_ (swap! inits conj id)]
                  [:text {:padding-x 0 :padding-y 0} (str "k" (name id))]))
          ids (atom [:b :a])
          r (h/root (fn [_]
                      [:container
                       (map (fn [id] ^{:key id} [kid {:id id}]) @ids)]))]
      (t/is (= ["kb" "ka"] (mapv str/trimr (core/render r 20))))
      (t/is (= #{:a :b} (set @inits)) "one instance per key")
      ;; reverse: the reorder must be visible AND must not remount (each
      ;; with-let init runs once per instance, so a count of 2 means both
      ;; instances survived the reorder)
      (swap! ids (fn [v] (vec (reverse v))))
      (t/is (= ["ka" "kb"] (mapv str/trimr (core/render r 20)))
            "the reorder took effect")
      (t/is (= 2 (count @inits)) "reorder reused the fn instances — no re-init"))))

(t/deftest removed-keyed-children-are-disposed-root-teardown-cascades
  (let [log (atom [])
        ids (atom [1 2])
        kid (fn [i]
              (fn [_props]
                (with-let [_ (swap! log conj [:init i])]
                  [:text {:padding-x 0 :padding-y 0} (str "k" i)]
                  (finally (swap! log conj [:dispose i])))))
        r (h/root (fn [_]
                    [:container
                     (map (fn [i] [(kid i) {:key i}]) @ids)]))]
    (core/render r 20)
    (t/is (= [[:init 1] [:init 2]] @log) "inits only, no cleanups yet")
    ;; remove id 1: its cleanup fires, id 2 untouched
    (reset! ids [2])
    (core/render r 20)
    (t/is (= [[:init 1] [:init 2] [:dispose 1]] @log))
    ;; root teardown disposes the rest through the container cascade
    (protocols/dispose r)
    (t/is (contains? (set @log) [:dispose 2]))))

(t/deftest foreign-records-reused-never-disposed
  (let [disposed (atom false)
        foreign (map->ForeignText {:text-atom (atom "f")
                                   :cache (atom nil)
                                   :disposed? disposed})
        shown (atom true)
        r (h/root (fn [_]
                    [:container (when @shown foreign)]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "f"))
    (t/is (false? @disposed) "still mounted")
    ;; same instance reused while present
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "f"))
    ;; removal takes it out of the tree but must NOT dispose it
    (reset! shown false)
    (core/render r 20)
    (t/is (false? @disposed) "foreign record left to its owner")
    ;; teardown of the root doesn't reach it either
    (protocols/dispose r)
    (t/is (false? @disposed))))

(t/deftest reified-components-pass-through-with-identity
  ;; the record splice's sibling: a hand-rolled (reify IComponent) — the
  ;; shape extension dialogs used to arrive in before CustomDialogAdapter —
  ;; splices as a foreign child instead of hitting the cannot-compile
  ;; throw. Regression: /tools crashed the render loop because
  ;; make-dock-area fed the reified wrapper through reconcile as a bare root.
  (let [disposed (atom false)
        foreign (reify protocols/IComponent
                  (render [_this _width] ["reified"])
                  (handle-input [_this _data] nil)
                  (invalidate [_this] nil)
                  (dispose [_this] (reset! disposed true)))
        shown (atom true)
        r (h/root (fn [_]
                    [:container
                     [:text "chrome"]
                     (when @shown foreign)]))]
    (t/is (identical? foreign (h/compile-element foreign))
          "compile-element preserves identity like records")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "reified"))
    ;; same instance reused across passes while present
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "reified"))
    ;; removal must NOT dispose it; teardown doesn't reach it either
    (reset! shown false)
    (core/render r 20)
    (t/is (false? @disposed) "foreign reify left to its owner")
    (protocols/dispose r)
    (t/is (false? @disposed))
    ;; dispose-tree! over a bare compiled reify must not blow up on
    ;; contains? either (a reify isn't associative)
    (h/dispose-tree! foreign)
    (t/is (false? @disposed))))

(t/deftest reified-component-as-bare-root-renders
  ;; a reify as a bare ROOT renders like a record root; [foreign] is
  ;; ELEMENT syntax ([tag ...]) — a component in the tag slot is an
  ;; invalid head, same as a record there
  (let [foreign (reify protocols/IComponent
                  (render [_this _width] ["solo"])
                  (handle-input [_this _data] nil)
                  (invalidate [_this] nil)
                  (dispose [_this] nil))]
    (t/is (= ["solo"] (mapv str/trimr (h/render-lines foreign 20))))
    (t/is (thrown-with-msg? Exception #"invalid element head"
                            (h/render-lines [foreign] 20)))
    ;; non-component scalars still throw loudly (maps have their own
    ;; bare-map-child error)
    (t/is (thrown-with-msg? Exception #"cannot compile tree node"
                            (h/render-lines :a-keyword 20)))))

(t/deftest duplicate-keys-throw
  (t/is (thrown-with-msg? Exception #"duplicate :key"
                          (h/render-lines
                           [:container
                            [:text {:key :a :padding-x 0 :padding-y 0} "1"]
                            [:text {:key :a :padding-x 0 :padding-y 0} "2"]]
                           10))))

(t/deftest leaf-prop-change-rebuilds-equal-props-reuse
  ;; display leaves rebuild when props change (identity-free); equal props
  ;; keep the instance for free
  (let [txt (atom "a")
        ref (h/ref)
        r (h/root (fn [_]
                    [:container
                     [:text {:key :t :padding-x 0 :padding-y 0 :ref ref}
                      @txt]]))]
    (core/render r 10)
    (let [i1 (deref ref)]
      (reset! txt "b")
      (core/render r 10)
      (let [i2 (deref ref)]
        (t/is (not (identical? i1 i2)) "changed content → new instance")
        (core/render r 10)
        (t/is (identical? i2 (deref ref))
              "unchanged pass reuses the instance")))))

(t/deftest body-sees-width-dynamic
  (let [r (h/root (fn [_] [:text {:padding-x 0} (str "w" h/*width*)]))]
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "w30"))))

(t/deftest dep-change-schedules-a-frame-through-the-hook
  ;; §3.4 pulled forward with ComponentFn: the dep-handler invokes the
  ;; :auto-run? callback ON THE MUTATOR'S THREAD the moment a dependency
  ;; changes by = (Reagent's component path), so idle UIs wake up without
  ;; any other frame source. Default no-op keeps headless use pure.
  (let [s (atom 0)
        fired (atom 0)
        r (h/root (fn [_] [:text {:padding-x 0} (str (rag/tracked-deref s))]))]
    (core/render r 20)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (reset! s 1)
      (t/is (= 1 @fired) "dep change fires the hook immediately")
      ;; callback scheduling never enqueues — the body reruns at the next
      ;; render's deref, and no further dep changes means no more pokes
      (rag/flush!)
      (t/is (= 1 @fired) "flush adds nothing (nothing was queued)")
      (t/is (str/includes? (str/join "\n" (core/render r 20)) "1")
            "next render brought the reaction current")
      (t/is (= 1 @fired) "rendering itself does not poke the hook")
      (finally
        (macros/set-frame-hook! nil)))))

(t/deftest counters-track-bodies-and-cache-hits
  (h/reset-counters!)
  (let [s (atom "x")
        r (h/root (fn [_] [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))))
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))) "clean pass skipped the body")
    (t/is (= 1 (:bodies-skipped (h/counters))) "counted as cache hit")))

;; ═══════════════════════════════════════════════════════════════════════
;; Stage-3 review pass — edge-case pins (dsl.md review notes)
;; ═══════════════════════════════════════════════════════════════════════

(t/deftest stack-entry-opts-update-inner-identity-preserved
  ;; entry maps are rebuilt per pass (fresh opts), the INNER component is
  ;; matched by identity and reused — grow/shrink changes land without
  ;; tearing the widget down
  (let [inner (text/make-text "row" 0 0)
        grow (atom 1)
        r (h/root (fn [_]
                    [:v-stack (list {:component inner :grow @grow})]))]
    (core/render r 20)
    (let [vs (:c (first @(:kids r)))
          e1 (first @(:entries-atom vs))]
      (t/is (= 1 (:grow e1)) "initial opts installed")
      (reset! grow 4)
      (core/render r 20)
      (let [e2 (first @(:entries-atom vs))]
        (t/is (= 4 (:grow e2)) "updated opts installed")
        (t/is (identical? (stack/entry-component e1)
                          (stack/entry-component e2))
              "inner survived the opts change")))))

(t/deftest same-fn-twice-with-keys-independent-state
  ;; two instances of one fn element, keyed apart, keep SEPARATE with-let
  ;; state slots (each wrapper has its own store). The bodies are UNTRACKED
  ;; (no reactive reads) so the valve re-runs them every pass — three
  ;; renders, three increments EACH, independently: a shared slot would
  ;; show interleaved counts instead.
  (let [r (h/root (fn [_]
                    [:container
                     [(fn [{:keys [n]}]
                        (with-let [hits (atom 0)]
                          (swap! hits inc)
                          [:text {:padding-x 0} (str n "=" @hits)]))
                      {:key :a :n "a"}]
                     [(fn [{:keys [n]}]
                        (with-let [hits (atom 0)]
                          (swap! hits inc)
                          [:text {:padding-x 0} (str n "=" @hits)]))
                      {:key :b :n "b"}]]))]
    (core/render r 20)
    (core/render r 20)
    (let [out (str/join "\n" (core/render r 20))]
      (t/is (str/includes? out "a=3") "first instance counted its own passes")
      (t/is (str/includes? out "b=3") "second instance counted its own passes"))))

(t/deftest mid-run-invalidation-convergence-survives-with-let
  ;; a dep written DURING the body makes run-sync! loop; each iteration
  ;; begins a new pass over the store — the double-use guard must not
  ;; fire for the SAME expansion site across convergence iterations
  (let [log (atom [])
        src (atom 0)
        widget (fn [_props]
                 (with-let [_ (swap! log conj :init)]
                   (rag/tracked-deref src)
                   ;; write a tracked dep while the body runs:
                   ;; forces exactly one convergence re-run
                   (when (zero? @src) (reset! src 1))
                   [:text {:padding-x 0} "x"]))
        r (h/root (fn [_] [:container [widget {}]]))]
    (core/render r 20)
    (t/is (= [:init] @log) "one init despite the convergence re-run")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "x"))))

(t/deftest replaced-ref-handles-are-cleared
  ;; a ref the element stops declaring is abandoned: the old handle must
  ;; clear, or it would deref a live component forever (inline refs are
  ;; the anti-pattern, but neither abandon path may leave a stale handle)
  (let [r1 (h/ref)
        r2 (h/ref)
        n (atom 0)
        root (h/root (fn [_] [:text {:padding-x 0
                                     :ref (if (even? @n) r1 r2)} "x"]))]
    (core/render root 10)
    (t/is (some? @r1))
    (t/is (nil? @r2))
    (reset! n 1)
    (core/render root 10)
    (t/is (nil? @r1) "the replaced handle cleared")
    (t/is (some? @r2) "the new handle filled")
    ;; re-passing the SAME handle is the hoisted pattern — no churn, still
    ;; filled on every pass
    (core/render root 10)
    (t/is (some? @r2)))
  ;; and dropping the ref prop entirely clears it too
  (let [r (h/ref)
        n (atom 0)
        root (h/root (fn [_] (if (zero? @n)
                               [:text {:padding-x 0 :ref r} "x"]
                               [:text {:padding-x 0} "x"])))]
    (core/render root 10)
    (t/is (some? @r))
    (reset! n 1)
    (core/render root 10)
    (t/is (nil? @r) "the dropped handle cleared")
    ;; the element's later removal still clears the remembered handle
    (reset! n 0)
    (core/render root 10)
    (t/is (some? @r))))

(t/deftest container-structural-props-are-live
  ;; the §4 props/state migration via the apply path: a changed structural
  ;; prop patches the container in place — the instance (and its children's
  ;; state) survives AND the layout follows, instead of being ignored
  (let [px (atom 1)
        bref (h/ref)
        tref (h/ref)
        root (h/root (fn [_]
                       [:box {:key :b :ref bref :padding-x (rag/tracked-deref px)}
                        [:text {:padding-x 0 :padding-y 0 :ref tref} "hi"]]))]
    (core/render root 20)
    (let [box1 (deref bref)
          txt1 (deref tref)]
      (reset! px 3)
      (core/render root 20)
      (t/is (identical? box1 (deref bref))
            "structural prop change keeps the container instance")
      (t/is (identical? txt1 (deref tref)) "children survive — no rebuild")
      (let [lines (mapv str/trimr (core/render root 20))]
        (t/is (some #(= "   hi" %) lines)
              "the changed padding took effect in the layout")))))

(t/deftest stack-gap-and-align-are-live
  ;; the same migration on the stacks: :gap / :align patch in place
  (let [gap (atom 0)
        vref (h/ref)
        vroot (h/root (fn [_] [:v-stack {:ref vref :gap (rag/tracked-deref gap)}
                               [:text {:padding-x 0 :padding-y 0} "a"]
                               [:text {:padding-x 0 :padding-y 0} "b"]]))]
    (core/render vroot 10)
    (let [vs (deref vref)]
      (t/is (= 2 (count (core/render vroot 10))))
      (reset! gap 2)
      (core/render vroot 10)
      (t/is (identical? vs (deref vref)))
      (t/is (= 4 (count (core/render vroot 10)))
            "the changed gap took effect")))
  (let [align (atom :stretch)
        hroot (h/root (fn [_] [:h-stack {:gap 1 :align (rag/tracked-deref align)}
                               [:text {:padding-x 0 :padding-y 0} "a"]
                               [:text {:padding-x 0 :padding-y 0} "b\nb2"]]))]
    (t/is (= ["a   b     " "    b2    "] (core/render hroot 10)))
    (reset! align :end)
    (t/is (= ["    b     " "a   b2    "] (core/render hroot 10))
          "the changed align re-laid the row")))

(t/deftest scroll-view-props-are-live
  ;; every :scroll-view prop patches through its setter — the instance and
  ;; its child survive, the behavior follows
  (let [sref (h/ref)
        follow (atom true)
        sb (atom :hidden)
        delay (atom 1000)
        root (h/root (fn [_] [:scroll-view {:ref sref
                                            :follow-end (rag/tracked-deref follow)
                                            :scrollbar (rag/tracked-deref sb)
                                            :scrollbar-hide-delay-ms (rag/tracked-deref delay)}
                              [:text {:padding-x 0 :padding-y 0} "x"]]))]
    (core/render root 10)
    (let [sv (deref sref)]
      (t/is (true? @(:follow-end?-atom sv)))
      (t/is (= :hidden @(:scrollbar-atom sv)))
      (reset! follow false)
      (reset! sb :always)
      (reset! delay 250)
      (core/render root 10)
      (t/is (identical? sv (deref sref)) "props patch, no rebuild")
      (t/is (false? @(:follow-end?-atom sv)))
      (t/is (= :always @(:scrollbar-atom sv)))
      (t/is (= 250 @(:scrollbar-hide-delay-ms-atom sv))))))

(t/deftest scroll-view-tag-follow-end-false-is-honored
  ;; pre-existing bug: the ctor's (or follow-end true) coerced an explicit
  ;; false back to true — the shared prop resolver keys off the prop's
  ;; presence instead
  (let [sref (h/ref)
        root (h/root (fn [_] [:scroll-view {:ref sref :follow-end false}
                              [:text {:padding-x 0 :padding-y 0} "x"]]))]
    (core/render root 10)
    (t/is (false? @(:follow-end?-atom (deref sref))))))

(t/deftest string-children-survive-second-pass
  ;; Stage 3 review find: a matched ::string item fell into the passthrough
  ;; branch of reuse-or-build, which installed a NIL child (the parsed
  ;; string has no :c) — the text silently vanished on every pass after
  ;; the first. Strings are display leaves: rebuild on change, keep when
  ;; equal.
  (let [r (h/root (fn [_] [:container [:v-stack "hello"]]))]
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "hello")
          "first pass renders")
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "hello")
          "second pass keeps the string child")))

(t/deftest string-children-update-and-computed-strings-rebuild
  (let [txt (atom "hello")
        r (h/root (fn [_] [:v-stack
                           "static"
                           (when-some [t @txt] [:text {:padding-x 0} t])
                           (str "count-" (count @txt))]))]
    (core/render r 40)
    (reset! txt "hi!")
    (let [lines (str/join "\n" (core/render r 40))]
      (t/is (str/includes? lines "static") "unchanged sibling kept")
      (t/is (str/includes? lines "hi!") "text element updated")
      (t/is (str/includes? lines "count-3") "computed string rebuilt")
      (t/is (not (str/includes? lines "count-5")) "stale computed string gone"))))

(t/deftest bare-ref-as-primary-value-throws
  ;; [:widget (h/ref)] missing the props map — every other child position
  ;; rejects refs; the primary slot must not swallow one either
  (t/is (thrown-with-msg? Exception #"missing|did you mean \[text"
                          (h/render-lines [:text (h/ref)] 10)))
  (t/is (thrown? Exception (h/render-lines [:container [(h/ref)]] 10))))

(t/deftest props-only-bodies-rederive-tracked-bodies-memoize
  ;; the valve contract, both sides: framework reads (props/ctree) are
  ;; EXEMPT from dependency counting, so a body whose ONLY inputs are
  ;; props has zero real deps and re-derives every pass (correctness
  ;; first — an untracked read anywhere must never poison the cache;
  ;; per-frame re-derivation is the documented batched fallback). A body
  ;; reading app state through tracked-deref/cursors/slices HAS a real
  ;; dep and memoizes until it changes.
  (h/reset-counters!)
  (let [r (h/root (fn [{:keys [label]}]
                    [:text {:padding-x 0} label]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 2 (:bodies-run (h/counters))) "props-only: rederived")
    (t/is (zero? (:bodies-skipped (h/counters))) "nothing cached"))
  (h/reset-counters!)
  (let [s (atom "x")
        r (h/root (fn [_] [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))) "tracked: ran once")
    (t/is (= 1 (:bodies-skipped (h/counters))) "tracked: cached")))

;; ── stage-3 review fixes ──────────────────────────────────────────────────

(t/deftest keyed-element-switching-kind-remounts
  ;; same :key, different element kind: remount (React semantics), not a
  ;; crash — the keyed bucket carries the kind, so a host↔fn switch under
  ;; one key retires + constructs instead of reusing across kinds
  (let [sw (atom false)
        disposed (atom false)
        myfn (fn [_]
               (with-let [] (finally (reset! disposed true)))
               [:text {:padding-x 0} "FN"])
        r (h/root (fn [_]
                    [:container {}
                     (if @sw
                       [:box {:key "k"} [:text {:padding-x 0} "BOX"]]
                       [myfn {:key "k"}])]))]
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "FN"))
    (reset! sw true)
    (let [out (str/join "\n" (core/render r 40))]
      (t/is (str/includes? out "BOX") "kind switch renders the new element")
      (t/is @disposed "the retired fn instance was disposed"))
    (reset! sw false)
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "FN")
          "switching back remounts instead of crashing")))

(t/deftest entry-map-first-child-is-child-not-props
  ;; {:component c} in the first slot is a stack ENTRY, not a props map —
  ;; consuming it as props silently dropped the child it wraps
  (let [inner (text/make-text "INNER" 0 0)]
    (t/is (str/includes? (joined [:v-stack {:component inner}] 20) "INNER")
          "entry map without a props map still mounts its component")
    (t/is (thrown? Throwable
                   (joined [:box {:component (text/make-text "X" 0 0)}] 20))
          "entry map outside a stack tag still throws loudly")))

(t/deftest width-change-invalidates-idle-wrappers
  ;; *width* shapes output but is not a tracked dep — a resize must force
  ;; one re-derive of an idle reaction, which then re-caches (track!'s
  ;; per-width cache contract, one level up)
  (let [st (atom "S")
        r (h/root (fn [_]
                    [:text {:padding-x 0}
                     (str (rag/tracked-deref st) "/w" h/*width*)]))]
    (core/render r 20)
    (h/reset-counters!)
    (t/is (str/includes? (str/join "\n" (core/render r 60)) "S/w60")
          "resize alone re-derives the body at the new width")
    (t/is (= 1 (:bodies-run (h/counters))) "exactly one forced run")
    (core/render r 60)
    (t/is (= 1 (:bodies-run (h/counters))) "same width stays memoized")
    (reset! st "T")
    (t/is (str/includes? (str/join "\n" (core/render r 60)) "T/w60"))
    (t/is (= 2 (:bodies-run (h/counters))) "dep change still re-derives")))

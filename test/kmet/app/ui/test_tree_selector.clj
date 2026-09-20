(ns kmet.app.ui.test-tree-selector
  "Tree selector tests (pi TreeSelectorComponent/TreeList parity): flatten
   indentation rules (flat single-child chains, branch +1), connectors /
   gutters / virtual roots, filter modes with tool-call-only assistant
   hiding, search tokens, folded-subtree hiding, selection restore by id,
   folding + foldable checks, segment jumps, paging clamps, row rendering
   (cursor, path markers, connectors, labels, timestamps, status line) and
   horizontal panning that keeps the selected anchor readable."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.tree-selector :as ts]
            [kmet.tui.components.input :as input]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.utils :as u]))

(defn- node
  "A bare tree node; parent ids are attached by make-tree-list."
  [id role & {:as k}]
  (merge {:id id :role role :children []} k))

(defn- user-node [id text & children]
  (node id :user :content [{:type :text :text text}]
        :children (vec children)))

(defn- assistant-node [id text & args]
  (let [[children kws] (split-with #(not (keyword? %)) args)
        {:keys [stop-reason]} (apply hash-map kws)]
    (node id :assistant
          :content (if (seq text) [{:type :text :text text}] [])
          :stop-reason (or stop-reason :stop)
          :children (vec children))))

;; a branches into b | c(d,e) | f(aborted); the active leaf is e, so the
;; active-first ordering is a, c, e, d, b, f.
(def ^:private tree
  [(user-node "a" "hello"
              (assistant-node "b" "hi there")
              (assistant-node "c" "branch two"
                              (node "d" :tool :tool-call-id "t1"
                                    :tool-name "read")
                              (user-node "e" "go deeper"))
              (assistant-node "f" "" :stop-reason :aborted))])

(defn- new-list
  ([] (new-list tree))
  ([tree* & opts]
   (tui-kb/set-global-keybindings!
    (kb/create-agent-keybindings-manager "target/test-tree-selector-keybindings"))
   (apply ts/make-tree-list tree* :leaf-id "e" opts)))

(defn- press
  "Feed a raw terminal key sequence (pi parseKey inputs)."
  [tl key]
  (protocols/handle-input
   tl
   (case key
     "up" "\u001b[A"
     "down" "\u001b[B"
     "pageUp" "\u001b[5~"
     "pageDown" "\u001b[6~"
     "home" "\u001b[H"
     "end" "\u001b[F"
     "backspace" "\u007f"
     "escape" "\u001b"
     "ctrl+left" "\u001b[1;5D"
     "ctrl+right" "\u001b[1;5C"
     key)))

(defn- plain
  "Rendered lines with ANSI codes stripped."
  ([tl] (plain tl 80))
  ([tl width] (mapv #(u/strip-ansi-codes %) (protocols/render tl width))))

(defn- visible-ids
  [tl]
  (set (map #(get-in % [:node :id]) (:filtered @(:state-atom tl)))))

(defn- selected-id
  [tl]
  (get-in (nth (:filtered @(:state-atom tl))
               (:selected-idx @(:state-atom tl)))
          [:node :id]))

(defn- set-mode!
  [tl mode]
  (swap! (:state-atom tl) assoc :mode mode :query "" :folded #{})
  (#'ts/refilter! tl))

;; ─── Flatten: indentation / connectors / gutters ────────────────────────────

(t/deftest flatten-indent-rules
  (let [flat (#'ts/flatten-tree* (#'ts/attach-parent-ids tree)
                                 #{"a" "c" "e"} false)
        by-id (into {} (map (fn [f] [(get-in f [:node :id]) f])) flat)]
    ;; branching root stays at 0; its children shift +1; grandchildren +1
    (t/is (= 0 (:indent (by-id "a"))))
    (t/is (= 1 (:indent (by-id "b"))))
    (t/is (= 2 (:indent (by-id "d"))))
    ;; single-child chains stay flat
    (let [chain [(user-node "r" "root"
                            (user-node "s" "mid"
                                       (user-node "t" "leaf")))]]
      (t/is (= [0 0 0] (mapv :indent (#'ts/flatten-tree*
                                      (#'ts/attach-parent-ids chain)
                                      #{"t"} false)))))))

(t/deftest flatten-connectors-and-gutters
  (let [flat (#'ts/flatten-tree* (#'ts/attach-parent-ids tree)
                                 #{"a" "c" "e"} false)
        by-id (into {} (map (fn [f] [(get-in f [:node :id]) f])) flat)]
    ;; the root shows no connector; branching children do; last sibling └
    (t/is (false? (:show-connector (by-id "a"))))
    (t/is (true? (:show-connector (by-id "c"))))
    (t/is (false? (:is-last (by-id "b"))))
    (t/is (true? (:is-last (by-id "f"))))
    ;; descendants of non-last siblings carry a │ gutter at the connector level
    (t/is (= [{:position 0 :show true}] (:gutters (by-id "d"))))))

(t/deftest flatten-multiple-roots-virtual-root
  (let [two [(user-node "x" "one") (user-node "y" "two")]
        flat (#'ts/flatten-tree* (#'ts/attach-parent-ids two) #{} true)]
    ;; virtual root: top-level nodes sit at indent 1 with suppressed display
    (t/is (= [1 1] (mapv :indent flat)))
    (t/is (true? (:virtual-root-child? (first flat))))
    (t/is (true? (:show-connector (second flat))))))

;; ─── Filtering ──────────────────────────────────────────────────────────────

(t/deftest filter-modes-hide-and-show
  (let [tl (new-list)]
    (t/is (= #{"a" "b" "c" "d" "e" "f"} (visible-ids tl)))
    (set-mode! tl :user-only)
    (t/is (= #{"a" "e"} (visible-ids tl)))
    (set-mode! tl :labeled-only)
    (t/is (= #{} (visible-ids tl)))
    (set-mode! tl :all)
    (t/is (= #{"a" "b" "c" "d" "e" "f"} (visible-ids tl)))))

(t/deftest settings-entries-hidden-by-default
  (let [tree* [(user-node "u" "go"
                          (node "lab" :label :label "x" :target-id "u")
                          (node "mc" :model-change :provider "p" :model "m")
                          (user-node "w" "after"))]]
    (t/is (= #{"u" "w"}
             (visible-ids (ts/make-tree-list tree* :leaf-id "w"
                                             :max-visible-lines 10))))
    (let [tl (ts/make-tree-list tree* :leaf-id "w" :max-visible-lines 10)]
      (set-mode! tl :all)
      (t/is (= #{"u" "lab" "mc" "w"} (visible-ids tl))))))

(t/deftest toolcall-only-assistant-hidden-unless-exceptional
  (let [tree* [(user-node "u" "go"
                          (assistant-node "silent" ""
                                          (user-node "w" "after"))
                          (assistant-node "aborted" "" :stop-reason :aborted))]
        tl (ts/make-tree-list tree* :leaf-id "w" :max-visible-lines 10)]
    ;; no-text assistant hidden even though an ancestor of the leaf...
    (t/is (not (contains? (visible-ids tl) "silent")))
    ;; ...and its child reattaches to the visible root u; with the aborted
    ;; sibling visible u branches, so the row shifts +1 (pi recalc)
    (let [st @(:state-atom tl)
          w-row (some #(when (= "w" (get-in % [:node :id])) %) (:filtered st))]
      (t/is (= "u" (get (:visible-parent st) "w")))
      (t/is (= 1 (:indent w-row))))
    ;; abnormal stop reasons stay visible without text
    (t/is (contains? (visible-ids tl) "aborted"))))

(t/deftest search-tokens-filter
  (let [tl (new-list)]
    (press tl "d")                     ; printable chars append to the query
    ;; "d" matches "go deeper" (content) and the read-tool row (tool name)
    (t/is (= #{"d" "e"} (visible-ids tl)))
    (press tl "backspace")
    (t/is (= "" (:query @(:state-atom tl))))
    (t/is (= #{"a" "b" "c" "d" "e" "f"} (visible-ids tl)))))

;; ─── Folding ────────────────────────────────────────────────────────────────

(t/deftest folding-hides-subtrees
  (let [tl (new-list)]
    ;; "c" is a branch point → foldable; "e" is a leaf → not
    (t/is (#'ts/foldable? @(:state-atom tl) "c"))
    (t/is (not (#'ts/foldable? @(:state-atom tl) "e")))
    (swap! (:state-atom tl) update :folded conj "c")
    (#'ts/refilter! tl)
    (t/is (contains? (visible-ids tl) "c"))
    (t/is (not (contains? (visible-ids tl) "e")))
    (t/is (not (contains? (visible-ids tl) "d")))
    ;; unfold restores
    (swap! (:state-atom tl) update :folded disj "c")
    (#'ts/refilter! tl)
    (t/is (contains? (visible-ids tl) "e"))))

(t/deftest segment-jump-navigation
  (let [tl (new-list)]
    ;; initial selection: e (idx 2 of [a c e d b f]); one down lands on d
    (press tl "down")
    (t/is (= "d" (selected-id tl)))
    ;; ctrl+left jumps up to the start of d's branch segment: c
    (press tl "ctrl+left")
    (t/is (= "c" (selected-id tl)))))

(t/deftest navigation-keys-move-and-wrap
  (let [tl (new-list)]
    ;; starts on the leaf e (idx 2)
    (t/is (= "e" (selected-id tl)))
    (press tl "down")
    (t/is (= "d" (selected-id tl)))
    (dotimes [_ 4] (press tl "up"))   ; wraps past the top to f
    (t/is (= "f" (selected-id tl)))
    (press tl "down")                 ; wraps back to the top
    (t/is (= "a" (selected-id tl)))))

(t/deftest paging-clamps
  (let [many (mapv #(user-node (str "m" %) (str "msg " %)) (range 30))
        tl (ts/make-tree-list many :leaf-id nil :max-visible-lines 5)]
    ;; no leaf → pi parity lands the selection on the last row
    (press tl "end")
    (t/is (= 29 (:selected-idx @(:state-atom tl))))
    (press tl "pageUp")
    (t/is (= 24 (:selected-idx @(:state-atom tl))))
    (press tl "pageDown")
    (t/is (= 29 (:selected-idx @(:state-atom tl))))
    ;; down wraps to the top
    (press tl "down")
    (t/is (zero? (:selected-idx @(:state-atom tl))))
    ;; pageUp clamps at 0
    (press tl "pageDown")
    (dotimes [_ 6] (press tl "pageUp"))
    (t/is (zero? (:selected-idx @(:state-atom tl))))))

;; ─── Rendering ──────────────────────────────────────────────────────────────

(t/deftest render-rows-show-structure
  (let [lines (plain (new-list))]
    (t/is (some #(str/includes? % "user: hello") lines))
    (t/is (some #(str/includes? % "├⊟ • assistant: branch two") lines))
    (t/is (some #(str/includes? % "│  ├─ • user: go deeper") lines))
    (t/is (some #(str/includes? % "│  └─ [read]") lines))
    (t/is (some #(str/includes? % "├─ assistant: hi there") lines))
    (t/is (some #(str/includes? % "└─ assistant: (aborted)") lines))
    ;; status line always present
    (t/is (some #(re-find #"3/6" %) lines))))

(t/deftest cursor-marks-selected-row
  (let [lines (plain (new-list))]
    (t/is (some #(str/starts-with? % "› ") lines))))

(t/deftest render-empty-state
  (let [tl (new-list)]
    (set-mode! tl :labeled-only)
    (let [lines (plain tl)]
      (t/is (some #(str/includes? % "No entries found") lines))
      (t/is (some #(str/includes? % "(0/0)") lines))
      (t/is (some #(str/includes? % "[labeled]") lines)))))

(t/deftest label-and-timestamp-rendering
  (let [tree* [(node "l" :user :content [{:type :text :text "tagged"}]
                     :label "keep" :label-timestamp "2026-02-14T10:30:00Z")]
        tl (ts/make-tree-list tree* :leaf-id "l" :max-visible-lines 5)]
    (t/is (some #(str/includes? % "[keep]") (plain tl)))
    ;; timestamps off by default; toggling (shift+t) shows [+label time]+HH:MM
    (t/is (not-any? #(str/includes? % "[+label time]") (plain tl)))
    (press tl "\u001b[116;2u")         ; kitty-protocol shift+t
    (t/is (true? (:show-label-timestamps @(:state-atom tl))))
    (let [lines (plain tl)]
      (t/is (some #(str/includes? % "[+label time]") lines))
      (t/is (some #(re-find #"\d{2}:\d{2}" %) lines)))))

(t/deftest shift-t-does-not-become-search-query
  ;; a bare uppercase letter is NOT shift+letter under kitty-protocol key
  ;; decoding (normalizeShiftedLetterIdentity lowercases it), so it must not
  ;; pollute the search query — but real shift+t (ESC[116;2u) must not either
  (let [tl (new-list)]
    (press tl "T")                     ; plain t → search query "T" (raw char)
    (t/is (= "T" (:query @(:state-atom tl))))
    (press tl "backspace")
    (t/is (= "" (:query @(:state-atom tl))))
    (press tl "\u001b[116;2u")         ; real shift+t → toggle, not search
    (t/is (= "" (:query @(:state-atom tl))))
    (t/is (true? (:show-label-timestamps @(:state-atom tl))))))

(t/deftest app-tree-editlabel-carries-the-legacy-chord-and-is-rebindable
  ;; bare L was a widget-local shim for terminals that send uppercase
  ;; instead of shift+l; it now lives in app.tree.editLabel's default-keys,
  ;; so a rebind moves it and the tree keeps no raw key matching.
  (let [seen (atom nil)
        tl (new-list tree :on-label-edit (fn [id label] (reset! seen [id label])))]
    (press tl "L")
    (t/is (some? @seen) "bare L edits the selected entry's label"))
  (let [seen (atom nil)
        tl (new-list tree :on-label-edit (fn [id label] (reset! seen [id label])))]
    (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings)
                               {"app.tree.editLabel" "ctrl+e"})
    (try
      (press tl "L")
      (t/is (nil? @seen) "rebound away: L is no longer a label edit")
      (t/is (= "L" (:query @(:state-atom tl))) "…it feeds the search query again")
      (press tl "\u0005")                       ;; ctrl+e
      (t/is (some? @seen) "the new chord edits")
      (finally
        (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings) {})))))

(t/deftest tree-label-area-swaps-in-an-inline-editor
  ;; pi: TreeSelectorComponent showLabelInput — the label input replaces the
  ;; list inside the panel; it is not a floating overlay.
  (let [tl (new-list)
        submitted (atom nil)
        in (input/make-input)
        _ (input/input-set-on-submit! in (fn [v] (reset! submitted v)))
        area (ts/map->TreeLabelArea {:tree-list tl
                                     :label-input-atom (atom nil)
                                     :focused? (atom false)})
        lines (fn [] (mapv #(u/strip-ansi-codes %)
                           (protocols/render area 80)))]
    (t/is (some #(str/includes? % "hello") (lines))
          "without an editor the tree list renders")
    (reset! (:label-input-atom area) in)
    (input/input-set-value! in "keep")
    (input/input-set-cursor! in (count "keep"))
    (t/is (some #(str/includes? % "Label (empty to remove):") (lines)))
    (t/is (some #(str/includes? % "> keep") (lines)))
    (t/is (some #(str/includes? % "save") (lines)))
    ;; keys route to the editor, not the list
    (protocols/handle-input area "x")
    (protocols/handle-input area "\r")
    (t/is (= "keepx" @submitted) "the input receives typing and enter")
    ;; focus forwards to the active child
    (protocols/set-focused! area true)
    (t/is (true? (protocols/focused in)))
    (reset! (:label-input-atom area) nil)
    (protocols/set-focused! area true)
    (t/is (true? (protocols/focused tl)) "back to the list: focus follows")))

;; ─── Horizontal panning ─────────────────────────────────────────────────────

(t/deftest panning-keeps-selected-anchor-readable
  ;; a deep row whose anchor sits far beyond a narrow viewport: the pan must
  ;; keep ~⅓ viewport of selected content after the anchor plus context
  (let [pad (apply str (repeat 40 " "))
        rows [{:gutter "› "
               :body (str (th/dim pad) "target-content-here")
               :anchor-col 40 :body-width 59 :selected? true}
              {:gutter "  "
               :body (str (th/dim pad) "other-row-content")
               :anchor-col 40 :body-width 56 :selected? false}]
        lines (mapv u/strip-ansi-codes
                    (#'ts/render-horizontal-viewport rows 44))]
    (t/is (= 2 (count lines)))
    (t/is (every? #(<= (u/visible-width %) 44) lines))
    ;; the fixed gutter stays; the body panned left but keeps the target text
    (t/is (str/includes? (first lines) "target-content-here"))))

(t/deftest no-panning-when-everything-fits
  (let [rows [{:gutter "› " :body "short" :anchor-col 0 :body-width 5
               :selected? true}]
        lines (#'ts/render-horizontal-viewport rows 40)]
    (t/is (= ["› short"] (mapv u/strip-ansi-codes lines)))))

;; ─── Selection persistence ──────────────────────────────────────────────────

(t/deftest selection-survives-refilter
  (let [tl (new-list)]
    ;; select d, then re-filter in the same mode: carried by entry id
    (press tl "down")
    (t/is (= "d" (selected-id tl)))
    (#'ts/refilter! tl)
    (t/is (= "d" (selected-id tl)))))

(t/deftest initial-selected-id-lands-selection
  (t/is (= "f" (selected-id (new-list tree :initial-selected-id "f")))))

;; ─── Display texts ──────────────────────────────────────────────────────────

(t/deftest entry-display-text-formats
  (let [theme-current (th/get-current-theme)
        show (fn [entry] (u/strip-ansi-codes
                          (#'ts/entry-display-text theme-current {} entry false)))]
    (t/is (str/includes?
           (show {:role :tool :tool-call-id "t1" :tool-name "read"})
           "[read]"))
    (t/is (str/includes?
           (#'ts/entry-display-text
            theme-current {"t1" {:name "read" :arguments {:path "/tmp/x"}}}
            {:role :tool :tool-call-id "t1" :tool-name "read"} false)
           "[read: /tmp/x]"))
    (t/is (str/includes?
           (show {:role :compaction :summary "s" :tokens-before 23400})
           "[compaction: 23k tokens]"))
    (t/is (str/includes?
           (show {:role :assistant :content [] :stop-reason :aborted})
           "(aborted)"))
    (t/is (str/includes?
           (show {:role :model-change :provider "anthropic" :model "sonnet"})
           "[model: anthropic/sonnet]"))
    ;; label entries render balanced brackets, incl. the cleared state
    (t/is (str/includes? (show {:role :label :label "keep"})
                         "[label: keep]"))
    (t/is (str/includes? (show {:role :label :label nil})
                         "[label: (cleared)]"))))

(t/deftest copy-entry-text-extracts
  (t/is (= "cmd" (#'ts/copy-entry-text {:role :bash :command "cmd"})))
  (t/is (= "txt" (#'ts/copy-entry-text {:role :user
                                        :content [{:type :text :text "txt"}]})))
  (t/is (nil? (#'ts/copy-entry-text {:role :assistant :content []}))))

(t/deftest format-tool-call-digests
  (t/is (= "[read: /a/b:2-3]"
           (#'ts/format-tool-call {:name "read"
                                   :arguments {:path "/a/b" :offset 2 :limit 2}})))
  (t/is (= "[bash: ls -la]"
           (#'ts/format-tool-call {:name "bash" :arguments {:command "ls -la"}})))
  (t/is (= "[grep: /foo/ in .]"
           (#'ts/format-tool-call {:name "grep" :arguments {:pattern "foo"}})))
  (t/is (str/starts-with?
         (#'ts/format-tool-call {:name "custom" :arguments {:a 1}})
         "[custom: ")))

;; ─── Help line ──────────────────────────────────────────────────────────────

(t/deftest help-lines-wrap-and-resolve-keys
  (let [lines (#'ts/tree-help-lines 200)]
    (t/is (= 1 (count lines)))
    (t/is (str/includes? (first lines) "move"))
    (t/is (str/includes? (first lines) "filters")))
  (t/is (< 1 (count (#'ts/tree-help-lines 24)))))

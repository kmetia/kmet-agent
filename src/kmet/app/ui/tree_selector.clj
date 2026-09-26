(ns kmet.app.ui.tree-selector
  "Session tree navigation panel (pi: TreeSelectorComponent / TreeList):
   browse the entry tree and branch to the selected entry. Visual parity
   with pi: 3-char indent levels where single-child chains stay flat and
   only branch points (plus their first generation) shift right, ├─/└─
   connectors, │ gutters continuing at ancestor branch positions, a virtual
   root when several roots exist, accent • markers along the active path,
   [label] tags, fold indicators (⊞/⊟) and horizontal panning that keeps
   the selected row's anchor readable while the fixed gutter stays visible.
   Behaviors (pi parity): filter modes (ctrl+d/t/u/l/a + ctrl+o /
   shift+ctrl+o cycles) whose changes preserve the selection by entry id
   (nearest visible ancestor fallback), free-text search (printable keys
   append, backspace edits, escape clears first), folding +
   segment-jump navigation (app.tree.foldOrUp / unfoldOrDown), paging
   (left/right/pageUp/pageDown), copy (app.message.copy), label editing
   (shift+l) and label timestamp display (shift+t). ON-NAVIGATE receives
   the chosen entry — the caller performs the branch."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.session :as session]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.config :as cfg]
            [kmet.libs.clipboard :as clipboard]
            [kmet.tui.components.input :as input]
            [kmet.tui.hiccup :as h]
            [kmet.tui.core :as tui]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :refer [defcomponent track!]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.utils :as u]))

;; ─── Layout constants (pi: TreeList viewport tuning) ────────────────────────

(def ^:private TREE-GUTTER-WIDTH 2)
(def ^:private MIN-VISIBLE-ANCHOR-CONTENT-WIDTH 4)
(def ^:private MAX-VISIBLE-ANCHOR-CONTENT-WIDTH 20)
(def ^:private MIN-ANCHOR-CONTEXT-WIDTH 2)
(def ^:private MAX-ANCHOR-CONTEXT-WIDTH 12)

;; ─── Filter modes (pi: FilterMode) ──────────────────────────────────────────

(def ^:private tree-filter-modes
  "The /tree selector filter modes (default hides bookkeeping entries)."
  [:default :no-tools :user-only :labeled-only :all])

(defn- tree-filter-mode-label
  [mode]
  (case mode
    :no-tools " [no-tools]"
    :user-only " [user]"
    :labeled-only " [labeled]"
    :all " [all]"
    ""))

(def ^:private settings-entry-roles
  "Entry roles hidden in the default view (pi: isSettingsEntry)."
  #{:label :custom :model-change :thinking-level-change :session_info})

;; ─── Session tree snapshot ──────────────────────────────────────────────────

(defn selector-tree
  "Session entry tree for the selector: raw entries enriched with resolved
   labels/timestamps and :children (pi: getTree + labelsById /
   labelTimestampsById). Nodes ARE the session entries, so display code can
   read stop reasons, tool calls, commands, summaries, etc."
  [sess]
  (let [entries @(:entries sess)
        labels (session/get-labels sess)
        by-parent (group-by :parent-id entries)]
    (letfn [(build [e]
              (let [l (get labels (:id e))]
                (cond-> (assoc e :children (mapv build (get by-parent (:id e))))
                  l (assoc :label (:label l) :label-timestamp (:timestamp l)))))]
      (mapv build (get by-parent nil)))))

(defn- collect-tool-calls
  "Tool-call lookup {id {:name :arguments}} over every assistant entry
   (pi: toolCallMap, filled during flattenTree)."
  [nodes]
  (letfn [(walk [acc n]
            (let [acc (reduce (fn [acc tc]
                                (assoc acc (:id tc) {:name (:name tc) :arguments (:arguments tc)}))
                              acc
                              (when (= :assistant (:role n)) (:tool-calls n)))]
              (reduce walk acc (:children n))))]
    (reduce walk {} nodes)))

;; ─── Display text (pi: getEntryDisplayText & friends) ───────────────────────

(defn- normalize-text
  "pi normalize — newlines/tabs become spaces, edges trimmed."
  [s]
  (str/trim (str/replace (str s) #"[\n\t]" " ")))

(defn- entry-full-text
  "Concatenated :text blocks of an entry's content (strings pass through;
   pi extractFullContent)."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (str/join (map #(str (:text %))
                   (filter #(= :text (:type %)) content)))
    :else ""))

(defn- has-text-content?
  "True when an entry carries any non-blank text (pi hasTextContent)."
  [entry]
  (let [c (:content entry)]
    (cond
      (string? c) (not (str/blank? c))
      (sequential? c) (boolean (some #(and (= :text (:type %))
                                           (not (str/blank? (str (:text %)))))
                                     c))
      :else false)))

(defn- error-or-aborted?
  "pi isErrorOrAborted — the completion stopped abnormally (anything other
   than a natural stop/tool-use turn: aborted, error, length, ...)."
  [entry]
  (let [sr (:stop-reason entry)]
    (and (some? sr) (not= :stop sr) (not= :end-turn sr) (not= :tool-use sr))))

(defn- shorten-path
  "pi formatToolCall shortenPath — $HOME collapses to ~."
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (seq home) (.startsWith path home))
      (str "~" (subs path (count home)))
      path)))

(defn- format-tool-call
  "pi formatToolCall - per-tool argument digests ([read: p:o-l],
   [bash: cmd], [grep: /pat/ in dir], ...). Arguments carry keyword keys
   (registry normalize-args parses JSON keywordized); string lookups cover
   anything that slipped through raw."
  [{:keys [arguments] tool-name :name}]
  (let [get-arg (fn [& ks]
                  (some (fn [k]
                          (let [v (or (get arguments k) (get arguments (name k)))]
                            (when (some? v) v)))
                        ks))
        path (fn [default & ks]
               (shorten-path (str (or (apply get-arg ks) default))))
        capped (fn [s n] (subs s 0 (min n (count s))))]
    (case tool-name
      "read" (let [p (path "" :path :file_path)
                   offset (get-arg :offset)
                   limit (get-arg :limit)]
               (if (or offset limit)
                 (let [start (or offset 1)
                       end (when limit (+ start limit -1))]
                   (str "[read: " p ":" start (when end (str "-" end)) "]"))
                 (str "[read: " p "]")))
      "write" (str "[write: " (path "" :path :file_path) "]")
      "edit" (str "[edit: " (path "" :path :file_path) "]")
      "bash" (let [raw (str (get-arg :command))]
               (str "[bash: " (capped (normalize-text raw) 50)
                    (when (> (count raw) 50) "...") "]"))
      "grep" (str "[grep: /" (or (get-arg :pattern) "")
                  "/ in " (path "." :path) "]")
      "find" (str "[find: " (or (get-arg :pattern) "")
                  " in " (path "." :path) "]")
      "ls" (str "[ls: " (path "." :path) "]")
      (let [all (pr-str (or arguments {}))]
        (str "[" (or tool-name "tool") ": " (capped all 40)
             (when (> (count all) 40) "...") "]")))))

(defn- entry-display-text
  "pi getEntryDisplayText — colored role prefix + normalized digest; the
   selected row's whole text is bolded. An empty assistant completion shows
   as muted (aborted)/(error)/(no content) depending on its stop reason."
  [theme tool-calls entry selected?]
  (let [result
        (case (:role entry)
          :user (str (th/fg theme :accent "user: ")
                     (normalize-text (entry-full-text (:content entry))))
          :assistant (let [text (normalize-text (entry-full-text (:content entry)))
                           err (:error-message entry)]
                       (str (th/fg theme :success "assistant: ")
                            (cond
                              (seq text) text
                              (= :aborted (:stop-reason entry)) (th/fg theme :muted "(aborted)")
                              (seq (str err)) (th/fg theme :error
                                                     (normalize-text (subs (str err) 0 (min 80 (count (str err))))))
                              :else (th/fg theme :muted "(no content)"))))
          :tool (let [tc (get tool-calls (:tool-call-id entry))]
                  (th/fg theme :muted
                         (if tc
                           (format-tool-call tc)
                           (str "[" (or (:tool-name entry) "tool") "]"))))
          :bash (th/fg theme :dim (str "[bash]: "
                                       (normalize-text (str (:command entry)))))
          :custom-message (str (th/fg theme :custom-message-label
                                      (str "[" (name (:custom-type entry :custom)) "]: "))
                               (normalize-text (entry-full-text (:content entry))))
          :compaction (let [tokens (Math/round (/ (double (or (:tokens-before entry) 0)) 1000.0))]
                        (th/fg theme :border-accent (str "[compaction: " tokens "k tokens]")))
          :branch-summary (str (th/fg theme :warning "[branch summary]: ")
                               (normalize-text (str (:summary entry))))
          :model-change (th/fg theme :dim (str "[model: " (name (:provider entry :unknown))
                                               "/" (name (:model entry :unknown)) "]"))
          :thinking-level-change (th/fg theme :dim (str "[thinking: "
                                                        (name (:thinking-level entry :unknown)) "]"))
          :custom (th/fg theme :dim (str "[custom: " (name (:custom-type entry :custom)) "]"))
          :label (th/fg theme :dim (str "[label: " (or (:label entry) "(cleared)") "]"))
          :session_info (let [nm (str (:name entry))]
                          (th/fg theme :dim
                                 (if (seq nm)
                                   (str "[title: " nm "]")
                                   (str "[title: " (th/italic (th/fg theme :dim "empty")) "]"))))
          (th/fg theme :dim (str "[" (:role entry) "]")))]
    (if selected? (th/bold result) result)))

(defn- copy-entry-text
  "pi getEntryCopyText — the full text worth copying for an entry (bash
   command, message text incl. error fallback, custom message, summary);
   nil when there is nothing."
  [entry]
  (let [text
        (case (:role entry)
          :bash (:command entry)
          :assistant (let [t (entry-full-text (:content entry))]
                       (if (str/blank? t) (:error-message entry) t))
          :user (entry-full-text (:content entry))
          :custom-message (entry-full-text (:content entry))
          :compaction (:summary entry)
          :branch-summary (:summary entry)
          nil)]
    (when (and (string? text) (not (str/blank? (str/trim text))))
      text)))

(defn- format-label-timestamp
  "pi formatLabelTimestamp — HH:MM today, M/D HH:MM this year, YY/M/D HH:MM
   otherwise (local zone). Unparseable timestamps render raw."
  [ts]
  (try
    (let [ldt (java.time.LocalDateTime/ofInstant (java.time.Instant/parse ts)
                                                 (java.time.ZoneId/systemDefault))
          now (java.time.LocalDateTime/now)
          hhmm (format "%02d:%02d" (.getHour ldt) (.getMinute ldt))]
      (cond
        (= (.toLocalDate ldt) (.toLocalDate now)) hhmm
        (= (.getYear ldt) (.getYear now))
        (str (.getMonthValue ldt) "/" (.getDayOfMonth ldt) " " hhmm)
        :else (str (mod (.getYear ldt) 100) "/" (.getMonthValue ldt) "/"
                   (.getDayOfMonth ldt) " " hhmm)))
    (catch Exception _ ts)))

(defn- entry-search-text
  "pi extractContent — the content capped at 200 chars for search/display
   haystacks, so giant messages don't bloat the token match."
  [content]
  (subs (entry-full-text content) 0 (min 200 (count (entry-full-text content)))))

(defn- searchable-text
  "Lowercased haystack for search tokens (pi getSearchableText): label,
   role name, content, command/type/model/thinking specifics."
  [node]
  (let [parts (transient [(str (or (:label node) ""))
                          (name (:role node :unknown))])
        content (:content node)
        add! (fn [acc & xs] (reduce conj! acc xs))
        parts
        (case (:role node)
          :assistant (add! parts (entry-search-text content))
          :user (add! parts (entry-search-text content))
          :tool (add! parts (str (:tool-name node)))
          :bash (add! parts (str (:command node)))
          :custom-message (add! parts (name (:custom-type node :custom))
                                (entry-search-text content))
          :compaction (add! parts "compaction" (str (:summary node)))
          :branch-summary (add! parts "branch summary" (str (:summary node)))
          :session_info (add! parts "title" (str (:name node)))
          :model-change (add! parts "model" (str (:provider node)) (str (:model node)))
          :thinking-level-change (add! parts "thinking"
                                       (name (:thinking-level node :unknown)))
          :custom (add! parts "custom" (name (:custom-type node :custom)))
          :label (add! parts "label" (str (:label node)))
          parts)]
    (str/lower-case (str/join " " (persistent! parts)))))

(defn- search-token-match?
  "Every whitespace-separated token of QUERY occurs in NODE's haystack
   (pi searchTokens.every(...))."
  [node query]
  (let [tokens (remove str/blank? (str/split (str/lower-case query) #"\s+"))]
    (if (empty? tokens)
      true
      (let [hay (searchable-text node)]
        (every? #(str/includes? hay %) tokens)))))

;; ─── Flatten (pi: flattenTree) ──────────────────────────────────────────────

(defn- order-active-first
  "Stable partition putting the subtree containing the active leaf first
   (pi: orderedChildren / orderedRoots)."
  [nodes active]
  (let [{on-path true off-path false} (group-by #(contains? active (:id %)) nodes)]
    (concat on-path off-path)))

(defn- active-path-set
  "Ids on the path root → LEAF-ID inclusive (a node's subtree contains the
   active leaf iff it lies on that path — pi containsActive)."
  [parent-lookup leaf-id]
  (loop [id leaf-id acc #{}]
    (if (nil? id) acc (recur (get parent-lookup id) (conj acc id)))))

(defn- attach-parent-ids
  "Ensure every node carries :parent-id (session entries have it; bare test
   trees may not — recalculation and ancestor walks rely on it)."
  [nodes & [parent-id]]
  (mapv (fn [n]
          (let [n (cond-> n (nil? (:parent-id n)) (assoc :parent-id parent-id))]
            (assoc n :children (attach-parent-ids (:children n) (:id n)))))
        nodes))

(defn- collect-parents
  "{id parent-id} over a whole tree (REAL ancestry, unaffected by
   filtering/folding — pi reads entry.parentId directly)."
  [nodes]
  (letfn [(walk [acc n]
            (reduce walk (assoc acc (:id n) (:parent-id n)) (:children n)))]
    (reduce walk {} nodes)))

(defn- child-indent
  "pi's indentation rule: branching parents shift children +1, the first
   generation after a branch keeps that extra level, single-child chains
   stay flat."
  [indent n-kids just-branched]
  (cond
    (> n-kids 1) (inc indent)
    (and just-branched (pos? indent)) (inc indent)
    :else indent))

(defn- flatten-tree*
  "Pre-order flat list carrying each node's visual structure (pi
   flattenTree): {:node :indent :show-connector :is-last :gutters
   :virtual-root-child?}. With multiple roots they are treated as children
   of a virtual branching root (indent 1, suppressed display)."
  [tree active multiple-roots?]
  (let [roots (vec (order-active-first tree active))
        init (map-indexed (fn [i n]
                            [n (if multiple-roots? 1 0) multiple-roots? multiple-roots?
                             (= i (dec (count roots))) [] multiple-roots?])
                          roots)]
    (loop [stack (vec (reverse init))
           result []]
      (if (empty? stack)
        result
        (let [[node indent just-branched show-connector is-last gutters virtual-root?]
              (peek stack)
              children (order-active-first (vec (:children node)) active)
              n-kids (count children)
              multiple-children? (> n-kids 1)
              child-ind (child-indent indent n-kids just-branched)
              display-indent (if multiple-roots? (max 0 (dec indent)) indent)
              ;; a displayed connector leaves a │ gutter (until its own last
              ;; sibling ends) at its position for descendants
              child-gutters (if (and show-connector (not virtual-root?))
                              (conj gutters {:position (max 0 (dec display-indent))
                                             :show (not is-last)})
                              gutters)
              kid-items (map-indexed (fn [i c]
                                       [c child-ind multiple-children? multiple-children?
                                        (= i (dec n-kids)) child-gutters false])
                                     children)]
          (recur (into (pop stack) (reverse kid-items))
                 (conj result {:node node
                               :indent indent
                               :show-connector show-connector
                               :is-last is-last
                               :gutters gutters
                               :virtual-root-child? virtual-root?})))))))

;; ─── Filtering (pi: applyFilter + recalculateVisualStructure) ───────────────

(defn- passes-mode?
  "Mode predicate over one node (pi applyFilter): the default view hides
   bookkeeping entries; no-tools additionally hides tool results;
   user-only/labeled-only narrow hard; all shows everything."
  [node mode leaf-id]
  (and
   ;; assistant completions with only tool calls (no text) are noise —
   ;; unless they ended abnormally or are the current leaf
   (not (and (= :assistant (:role node))
             (not= (:id node) leaf-id)
             (not (has-text-content? node))
             (not (error-or-aborted? node))))
   (case mode
     :user-only (= :user (:role node))
     :no-tools (and (not (contains? settings-entry-roles (:role node)))
                    (not= :tool (:role node)))
     :labeled-only (some? (:label node))
     :all true
     (not (contains? settings-entry-roles (:role node))))))

(defn- recalc-visual-structure
  "Recompute indentation/connectors/gutters for the FILTERED view (pi
   recalculateVisualStructure): hidden intermediate entries vanish, so
   descendants attach to their nearest visible ancestor; indentation
   follows the same rules as flatten-tree* over the visible tree.
   REAL-PARENT-OF resolves entries' true ancestry over the WHOLE tree (pi
   reads the full entry map, not just the visible rows). Returns
   {:filtered [...] :visible-parent {} :visible-children {}
    :multiple-roots? bool} — real parent links stay untouched."
  [visible real-parent-of]
  (if (empty? visible)
    {:filtered [] :visible-parent {} :visible-children {} :multiple-roots? false}
    (let [ids (set (map #(get-in % [:node :id]) visible))
          by-id (into {} (map (fn [f] [(get-in f [:node :id]) f])) visible)
          visible-ancestor (fn [nid]
                             (loop [cur (real-parent-of nid)]
                               (cond
                                 (nil? cur) nil
                                 (contains? ids cur) cur
                                 :else (recur (real-parent-of cur)))))
          vparent (volatile! {})
          vchildren (volatile! {nil []})]
      (doseq [f visible]
        (let [nid (get-in f [:node :id])
              anc (visible-ancestor nid)]
          (vswap! vparent assoc nid anc)
          (vswap! vchildren update anc (fnil conj []) nid)))
      (let [roots (get @vchildren nil)
            multiple-roots? (> (count roots) 1)
            init (map-indexed (fn [i nid]
                                [nid (if multiple-roots? 1 0) multiple-roots? multiple-roots?
                                 (= i (dec (count roots))) [] multiple-roots?])
                              roots)]
        (loop [stack (vec (reverse init))
               out []]
          (if (empty? stack)
            {:filtered out
             :visible-parent @vparent
             :visible-children @vchildren
             :multiple-roots? multiple-roots?}
            (let [[nid indent just-branched show-connector is-last gutters virtual-root?]
                  (peek stack)
                  f (by-id nid)
                  kids (get @vchildren nid [])
                  n-kids (count kids)
                  multiple-children? (> n-kids 1)
                  child-ind (child-indent indent n-kids just-branched)
                  display-indent (if multiple-roots? (max 0 (dec indent)) indent)
                  child-gutters (if (and show-connector (not virtual-root?))
                                  (conj gutters {:position (max 0 (dec display-indent))
                                                 :show (not is-last)})
                                  gutters)
                  kid-items (map-indexed (fn [i cid]
                                           [cid child-ind multiple-children? multiple-children?
                                            (= i (dec n-kids)) child-gutters false])
                                         kids)
                  updated (assoc f
                                 :indent indent
                                 :show-connector show-connector
                                 :is-last is-last
                                 :gutters gutters
                                 :virtual-root-child? virtual-root?)]
              (recur (into (pop stack) (reverse kid-items))
                     (conj out updated)))))))))

(defn- apply-tree-filter
  "Derive the visible rows from ST's mode/query/folds over FLAT (pi
   applyFilter): mode + token search pass, then folded nodes hide their
   whole subtree (transitively, in file order), then the visual structure
   is recalculated over what remains."
  [{:keys [mode query folded leaf-id] :as st} flat]
  (let [passed (filterv (fn [f]
                          (let [n (:node f)]
                            (and (passes-mode? n mode leaf-id)
                                 (search-token-match? n query))))
                        flat)
        skip (loop [[f & fs] flat, skipped #{}]
               (if (nil? f)
                 skipped
                 (let [pid (get-in f [:node :parent-id])]
                   (recur fs
                          (if (and pid (or (contains? folded pid)
                                           (contains? skipped pid)))
                            (conj skipped (get-in f [:node :id]))
                            skipped)))))
        visible (filterv #(not (contains? skip (get-in % [:node :id]))) passed)
        {:keys [parent-lookup]} st]
    (recalc-visual-structure visible #(get parent-lookup %))))

(defn- nearest-visible-index
  "Index of TARGET-ID among the filtered rows, walking up real ancestors;
   last row as fallback (pi findNearestVisibleIndex)."
  [filtered parent-lookup target-id]
  (if (empty? filtered)
    0
    (let [idx-by-id (into {}
                          (map-indexed (fn [i f] [(get-in f [:node :id]) i]))
                          filtered)]
      (loop [cur target-id]
        (cond
          (contains? idx-by-id cur) (idx-by-id cur)
          (some? cur) (recur (get parent-lookup cur))
          :else (dec (count filtered)))))))

;; ─── Navigation helpers (folding / segment jumps) ───────────────────────────

(defn- row-at
  [st idx]
  (when (seq (:filtered st))
    (nth (:filtered st) (min (max 0 idx) (dec (count (:filtered st)))) nil)))

(defn- row-id-at
  [st idx]
  (some-> (row-at st idx) :node :id))

(defn- foldable?
  "A node folds when it has visible children and is either a visible root
   or a segment start (its visible parent has >1 visible children) — pi
   isFoldable."
  [{:keys [visible-children visible-parent]} entry-id]
  (let [kids (get visible-children entry-id)]
    (and (seq kids)
         (let [pid (get visible-parent entry-id)]
           (or (nil? pid)
               (> (count (get visible-children pid)) 1))))))

(defn- branch-segment-index
  "pi findBranchSegmentStart — the next branch segment start in DIRECTION
   (:up walks visible parents, jumping only when the segment start lies
   before the current selection; :down follows first children)."
  [direction {:keys [filtered selected-idx visible-parent visible-children]}]
  (if (empty? filtered)
    selected-idx
    (let [idx-by-id (into {}
                          (map-indexed (fn [i f] [(get-in f [:node :id]) i]))
                          filtered)
          sel-id (when (seq filtered)
                   (get-in (row-at {:filtered filtered} selected-idx) [:node :id]))]
      (if-not sel-id
        selected-idx
        (case direction
          :down (loop [cur sel-id]
                  (let [kids (get visible-children cur)]
                    (cond
                      (empty? kids) (or (idx-by-id cur) selected-idx)
                      (> (count kids) 1) (or (idx-by-id (first kids)) selected-idx)
                      :else (recur (first kids)))))
          :up (loop [cur sel-id]
                (let [pid (get visible-parent cur)]
                  (cond
                    (nil? pid) (or (idx-by-id cur) selected-idx)
                    (and (> (count (get visible-children pid)) 1)
                         (< (or (idx-by-id cur) selected-idx) selected-idx))
                    (or (idx-by-id cur) selected-idx)
                    :else (recur pid)))))))))

;; ─── Row assembly & horizontal viewport (pi: render + renderHorizontalViewport)

(defn- status-labels
  "Active-mode tag plus the label-time flag (pi getStatusLabels)."
  [{:keys [mode show-label-timestamps]}]
  (str (tree-filter-mode-label mode)
       (when show-label-timestamps " [+label time]")))

(defn- build-row
  "One tree row assembled like pi render(): cursor gutter, dim char-by-char
   prefix (│ gutters at ancestor connector positions, ├─/└─ with ⊞/⊟ fold
   indicators), accent • on the active path, [label], optional timestamp,
   display text; selectedBg spans gutter and body separately so panning
   keeps the selection tinted. Returns {:gutter :body :anchor-col
   :body-width :selected?}."
  [theme tool-calls st f selected?]
  (let [{:keys [node indent show-connector is-last gutters virtual-root-child?]} f
        entry-id (:id node)
        {:keys [multiple-roots? folded active-path show-label-timestamps]} st
        display-indent (if multiple-roots? (max 0 (dec indent)) indent)
        connector? (and show-connector (not virtual-root-child?))
        connector-pos (if connector? (dec display-indent) -1)
        total (* display-indent 3)
        folded? (contains? folded entry-id)
        foldable (foldable? st entry-id)
        prefix
        (apply str
               (for [i (range total)
                     :let [level (quot i 3)
                           pos (mod i 3)
                           gutter (some #(when (= (:position %) level) %) gutters)]]
                 (cond
                   gutter (if (zero? pos) (if (:show gutter) "│" " ") " ")
                   (= level connector-pos)
                   (cond
                     (zero? pos) (if is-last "└" "├")
                     (= pos 1) (if folded? "⊞" (if foldable "⊟" "─"))
                     :else " ")
                   :else " ")))
        ;; roots without a displayed connector carry the fold marker beside them
        fold-marker (when (and folded? (not connector?)) (th/fg theme :accent "⊞ "))
        path-marker (when (contains? active-path entry-id) (th/fg theme :accent "• "))
        label-part (when (:label node)
                     (th/fg theme :warning (str "[" (:label node) "] ")))
        ts-part (when (and show-label-timestamps (:label node) (:label-timestamp node))
                  (th/fg theme :muted (str (format-label-timestamp (:label-timestamp node)) " ")))
        prefix-part (str (th/dim prefix) fold-marker path-marker)
        cursor (if selected? (th/fg theme :accent "› ") "  ")
        content (entry-display-text theme tool-calls node selected?)
        body (str prefix-part label-part ts-part content)]
    {:gutter (if selected? (th/bg theme :selected-bg cursor) cursor)
     :body (if selected? (th/bg theme :selected-bg body) body)
     :anchor-col (u/visible-width prefix-part)
     :body-width (u/visible-width body)
     :selected? selected?}))

(defn- render-horizontal-viewport
  "pi renderHorizontalViewport — clip rows to WIDTH while keeping the tree
   gutter fixed: when the selected row's anchor would sit too far right to
   leave useful content visible, the bodies pan left just enough for ~⅓ of
   the viewport (4–20 cols) of selected content plus 2–12 cols of anchor
   context. All rows clip at the same offset so the frame stays aligned."
  [rows width]
  (let [viewport-width (max 0 (- width TREE-GUTTER-WIDTH))
        max-body-width (reduce #(max %1 (:body-width %2)) 0 rows)
        max-scroll (max 0 (- max-body-width viewport-width))
        selected-row (some #(when (:selected? %) %) rows)
        horizontal-scroll
        (when (and selected-row (pos? max-scroll))
          (let [min-visible-anchor-content-width
                (min MAX-VISIBLE-ANCHOR-CONTENT-WIDTH
                     (max MIN-VISIBLE-ANCHOR-CONTENT-WIDTH (quot viewport-width 3)))]
            (when (> (:anchor-col selected-row)
                     (- viewport-width min-visible-anchor-content-width))
              (let [anchor-context-width
                    (min MAX-ANCHOR-CONTEXT-WIDTH
                         (max MIN-ANCHOR-CONTEXT-WIDTH (quot viewport-width 4)))]
                (min max-scroll (- (:anchor-col selected-row) anchor-context-width))))))]
    (mapv (fn [{:keys [gutter body]}]
            (let [line (if (and horizontal-scroll (pos? horizontal-scroll))
                         (str gutter
                              (:text (u/slice-with-width body horizontal-scroll viewport-width :strict? true))
                              "\u001b[0m")
                         (str gutter body))]
              (u/truncate-to-width line width "")))
          rows)))

;; ─── Help line (pi: TREE_HELP_ITEMS / compactRawKeys / TreeHelp.render) ────

(def ^:private tree-help-items
  "pi TREE_HELP_ITEMS — semantic key-hint chunks; label-first puts the word
   before its keys."
  [{:keys ["tui.select.up" "tui.select.down"] :label "move"}
   {:keys ["tui.editor.cursorLeft" "tui.editor.cursorRight"] :label "page"}
   {:keys ["app.tree.foldOrUp" "app.tree.unfoldOrDown"] :label "branch"}
   {:keys ["app.message.copy"] :label "copy"}
   {:keys ["app.tree.editLabel"] :label "label"}
   {:keys ["app.tree.toggleLabelTimestamp"] :label "label time"}
   {:keys ["app.tree.filter.default" "app.tree.filter.noTools"
           "app.tree.filter.userOnly" "app.tree.filter.labeledOnly"
           "app.tree.filter.all"]
    :label "filters" :label-first true}
   {:keys ["app.tree.filter.cycleForward" "app.tree.filter.cycleBackward"]
    :label "cycle" :label-first true}])

(defn- prettify-keys
  "pi's post-processing of rendered key text: page names shorten and arrow
   keys become glyphs. The table lives in kmet.tui.keys/key-label now
   (§7.1), so this is a map over the CHORDS rather than a regex pass
   over the joined string (the old word-boundary regexes were a
   workaround for exactly that; compact-raw-keys has already joined them)."
  [s]
  (->> (str/split s #"/")
       (map keys/key-label)
       (str/join "/")))

(defn- compact-raw-keys
  "pi compactRawKeys — chords sharing a modifier collapse: ctrl+d/t/u/l/a."
  [keys*]
  (if (= 1 (count keys*))
    (first keys*)
    (let [split (fn [k]
                  (if-let [i (str/last-index-of k "+")]
                    [(subs k 0 (inc i)) (subs k (inc i))]
                    ["" k]))
          parts (map split keys*)
          prefix (ffirst parts)]
      (if (and (seq prefix) (every? #(= prefix (first %)) parts))
        (apply str prefix (interpose "/" (map second parts)))
        (str/join "/" keys*)))))

(defn- format-help-keys
  "pi formatHelpKeys — first resolved chord per keybinding id, compacted;
   \"\" when none resolve."
  [kmgr ids]
  (let [resolved (keep #(first (tui-kb/get-keys kmgr %)) ids)]
    (when (seq resolved)
      (prettify-keys (compact-raw-keys resolved)))))

(defn- tree-help-lines
  "pi TreeHelp.render — chunk-aware wrapping: items accumulate on a line
   separated by ' · ' until they no longer fit, then wrap onto the next."
  [width]
  (let [kmgr (or (tui-kb/get-global-keybindings)
                 ;; no manager installed (tests, pre-startup) — app defaults
                 ;; so app.tree.* key hints resolve
                 (app-kb/create-agent-keybindings-manager "target/tree-help-keybindings"))
        indent "  "
        separator " · "
        rendered (for [{:keys [keys label label-first]} tree-help-items
                       :let [text (format-help-keys kmgr keys)]]
                   (if (seq text)
                     (if label-first (str label " " text) (str text " " label))
                     label))
        step (fn [{:keys [current out]} item]
               (let [candidate (if (seq current)
                                 (str current separator item)
                                 (if (<= (u/visible-width (str indent item)) width)
                                   (str indent item)
                                   item))]
                 (if (or (empty? current) (<= (u/visible-width candidate) width))
                   {:current candidate :out out}
                   {:current (if (<= (u/visible-width (str indent item)) width)
                               (str indent item)
                               item)
                    :out (into out (u/wrap-text-with-ansi (str/trimr current) width))})))
        {:keys [current out]} (reduce step {:current "" :out []} rendered)]
    (if (seq current)
      (into out (u/wrap-text-with-ansi (str/trimr current) width))
      out)))

;; ─── State derivation ───────────────────────────────────────────────────────

(defn- refilter!
  "Re-run apply-tree-filter over the current flat list and restore the
   selection onto TARGET-ID, else the carried last-selected-id, else the
   current leaf — walking up to the nearest visible ancestor (pi
   applyFilter's selection bookkeeping)."
  ([tl] (refilter! tl nil))
  ([tl target-id]
   (swap! (:state-atom tl)
          (fn [{:keys [flat leaf-id parent-lookup selected-idx last-selected-id]
                :as st}]
            (let [carried (or target-id
                              (row-id-at st selected-idx)
                              last-selected-id)
                  res (apply-tree-filter st flat)
                  n (count (:filtered res))
                  idx (nearest-visible-index (:filtered res) parent-lookup
                                             (or carried leaf-id))]
              (merge st res
                     {:selected-idx idx
                      :last-selected-id (if (pos? n)
                                          (get-in (nth (:filtered res) idx) [:node :id])
                                          carried)}))))))

;; ─── Component ──────────────────────────────────────────────────────────────

(defcomponent TreeList nil
              [state-atom on-select-atom on-cancel-atom on-copy-atom
               on-label-edit-atom max-visible-lines focused? cache-atom]

  (render [this width]
    (track! this width
      (let [{:keys [filtered selected-idx] :as st} @state-atom
            theme-current (th/get-current-theme)]
        (if (empty? filtered)
          [(u/truncate-to-width (th/fg theme-current :muted "  No entries found") width)
           (u/truncate-to-width
            (th/fg theme-current :muted (str "  (0/0)" (status-labels st))) width)]
          (let [n (count filtered)
                h (max 1 max-visible-lines)
                start-idx (max 0 (min (- selected-idx (quot h 2)) (- n h)))
                rows (mapv (fn [i] (build-row theme-current (:tool-calls st) st
                                              (nth filtered i) (= i selected-idx)))
                           (range start-idx (min (+ start-idx h) n)))
                status (str "  (" (inc selected-idx) "/" n ")" (status-labels st))]
            (conj (render-horizontal-viewport rows width)
                  (u/truncate-to-width (th/fg theme-current :muted status) width)))))))

  (handle-input [this data]
    (let [kmgr (or (tui-kb/get-global-keybindings)
                   ;; no manager installed (tests, pre-startup) — app defaults
                   ;; so app.tree.* keys (fold/label/timestamp/filters) resolve
                   (app-kb/create-agent-keybindings-manager "target/tree-input-keybindings"))
          match (fn [id] (tui-kb/matches-key kmgr data id))
          selected-node (fn []
                          (let [st @state-atom]
                            (some-> (row-at st (:selected-idx st)) :node)))]
      (cond
        ;; up/down wrap at the ends (pi tui.select.up/down)
        (or (match "tui.select.up") (match "tui.select.down"))
        (let [dir (if (match "tui.select.up") -1 1)]
          (swap! state-atom
                 (fn [{:keys [filtered selected-idx] :as st}]
                   (if (seq filtered)
                     (assoc st :selected-idx (mod (+ selected-idx dir) (count filtered)))
                     st)))
          nil)

        ;; folding / segment jumps (pi foldOrUp/unfoldOrDown)
        (match "app.tree.foldOrUp")
        (let [st @state-atom
              cur-id (row-id-at st (:selected-idx st))]
          (if (and cur-id (foldable? st cur-id) (not (contains? (:folded st) cur-id)))
            (do (swap! state-atom update :folded conj cur-id)
                (refilter! this))
            (swap! state-atom assoc :selected-idx (branch-segment-index :up st)))
          nil)

        (match "app.tree.unfoldOrDown")
        (let [st @state-atom
              cur-id (row-id-at st (:selected-idx st))]
          (if (and cur-id (contains? (:folded st) cur-id))
            (do (swap! state-atom update :folded disj cur-id)
                (refilter! this))
            (swap! state-atom assoc :selected-idx (branch-segment-index :down st)))
          nil)

        ;; paging (pi cursorLeft/right + select.pageUp/pageDown)
        (or (match "tui.editor.cursorLeft") (match "tui.select.pageUp"))
        (do (swap! state-atom update :selected-idx #(max 0 (- % max-visible-lines)))
            nil)

        (or (match "tui.editor.cursorRight") (match "tui.select.pageDown"))
        (do (swap! state-atom
                   (fn [{:keys [filtered selected-idx] :as st}]
                     (assoc st :selected-idx
                            (min (max 0 (dec (count filtered)))
                                 (+ selected-idx max-visible-lines)))))
            nil)

        ;; enter selects (pi tui.select.confirm)
        (match "tui.select.confirm")
        (do (when-let [entry (selected-node)]
              (when-let [cb @on-select-atom] (cb entry)))
            nil)

        ;; copy (pi app.message.copy — onCopy receives nil when nothing to copy)
        (match "app.message.copy")
        (do (when-let [entry (selected-node)]
              (when-let [cb @on-copy-atom] (cb (copy-entry-text entry))))
            nil)

        ;; escape clears an active search first, then cancels
        (match "tui.select.cancel")
        (if (seq (:query @state-atom))
          (do (swap! state-atom assoc :query "" :folded #{})
              (refilter! this)
              nil)
          (do (when-let [cb @on-cancel-atom] (cb))
              nil))

        ;; direct filter modes (pi: default sets; others toggle with default)
        (match "app.tree.filter.default")
        (do (swap! state-atom assoc :mode :default :folded #{})
            (refilter! this) nil)

        (match "app.tree.filter.noTools")
        (do (swap! state-atom assoc :mode
                   (if (= :no-tools (:mode @state-atom)) :default :no-tools)
                   :folded #{})
            (refilter! this) nil)

        (match "app.tree.filter.userOnly")
        (do (swap! state-atom assoc :mode
                   (if (= :user-only (:mode @state-atom)) :default :user-only)
                   :folded #{})
            (refilter! this) nil)

        (match "app.tree.filter.labeledOnly")
        (do (swap! state-atom assoc :mode
                   (if (= :labeled-only (:mode @state-atom)) :default :labeled-only)
                   :folded #{})
            (refilter! this) nil)

        (match "app.tree.filter.all")
        (do (swap! state-atom assoc :mode
                   (if (= :all (:mode @state-atom)) :default :all)
                   :folded #{})
            (refilter! this) nil)

        (match "app.tree.filter.cycleBackward")
        (do (swap! state-atom assoc :folded #{}
                   :mode (nth tree-filter-modes
                              (mod (dec (.indexOf tree-filter-modes (:mode @state-atom)))
                                   (count tree-filter-modes))))
            (refilter! this) nil)

        (match "app.tree.filter.cycleForward")
        (do (swap! state-atom assoc :folded #{}
                   :mode (nth tree-filter-modes
                              (mod (inc (.indexOf tree-filter-modes (:mode @state-atom)))
                                   (count tree-filter-modes))))
            (refilter! this) nil)

        ;; search editing (pi deleteCharBackward)
        (match "tui.editor.deleteCharBackward")
        (do (when (seq (:query @state-atom))
              (swap! state-atom
                     (fn [{:keys [query] :as st}]
                       (assoc st :query (subs query 0 (max 0 (dec (count query))))
                              :folded #{})))
              (refilter! this))
            nil)

        ;; label edit (app.tree.editLabel also carries the legacy bare-L
        ;; chord — terminals that send uppercase instead of shift+l)
        (match "app.tree.editLabel")
        (do (when-let [entry (selected-node)]
              (when-let [cb @on-label-edit-atom]
                (cb (:id entry) (:label entry))))
            nil)

        ;; label timestamps toggle (pi toggleLabelTimestamp)
        (match "app.tree.toggleLabelTimestamp")
        (do (swap! state-atom update :show-label-timestamps not)
            nil)

        ;; any other printable character feeds the search query
        :else
        (let [has-ctrl? (some #(let [c (int %)]
                                 (or (< c 32) (== c 127)
                                     (and (>= c 128) (<= c 159))))
                              data)]
          (when (and (not has-ctrl?) (seq data))
            (swap! state-atom
                   (fn [{:keys [query] :as st}]
                     (assoc st :query (str query data) :folded #{})))
            (refilter! this))
          nil)))))

(extend-type TreeList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

;; ─── Inline label editor (pi: TreeSelectorComponent + LabelInput) ───────────

(defcomponent TreeLabelArea nil [tree-list label-input-atom focused?]
  (render [_this width]
    ;; No track! — the output is whatever the active child renders, and a
    ;; child's internal state is invisible to the deref tracker. The tree
    ;; list / input caches carry the detail; this delegate renders fresh.
    (if-let [in @label-input-atom]
      (let [theme-current (th/get-current-theme)
            kmgr (or (tui-kb/get-global-keybindings)
                     (app-kb/create-agent-keybindings-manager
                      "target/tree-label-keybindings"))
            hint (str (format-help-keys kmgr ["tui.select.confirm"]) " save"
                      "  " (format-help-keys kmgr ["tui.select.cancel"]) " cancel")]
        (into [(u/truncate-to-width
                (th/fg theme-current :muted "  Label (empty to remove):") width)]
              (concat
               (mapv #(u/truncate-to-width (str "  " %) width)
                     (protocols/render in (max 1 (- width 2))))
               [(u/truncate-to-width
                 (th/fg theme-current :dim (str "  " hint)) width)])))
      (protocols/render tree-list width)))

  (handle-input [_this data]
    (if-let [in @label-input-atom]
      (protocols/handle-input in data)
      (protocols/handle-input tree-list data))))

(extend-type TreeLabelArea
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    ;; The active child owns the real focus (the input's cursor marker, the
    ;; tree's focused? flag) — forward to whichever is showing.
    (protocols/set-focused!
     (if-let [in @(:label-input-atom this)] in (:tree-list this))
     val)))

;; ─── Construction ───────────────────────────────────────────────────────────

(defn make-tree-list
  "Create the tree list over TREE (pi TreeList constructor): flattens with
   pi's visual rules and lands the selection on INITIAL-SELECTED-ID ?? the
   current leaf. MAX-VISIBLE-LINES is the row budget (pi:
   max(5, floor(terminalHeight/2)))."
  [tree & {:keys [leaf-id max-visible-lines initial-filter-mode
                  initial-selected-id on-select on-cancel on-copy on-label-edit]}]
  (let [tree (attach-parent-ids tree)
        parent-lookup (collect-parents tree)
        active (active-path-set parent-lookup leaf-id)
        base {:tree tree
              :flat (flatten-tree* tree active (> (count tree) 1))
              :parent-lookup parent-lookup
              :active-path active
              :tool-calls (collect-tool-calls tree)
              :leaf-id leaf-id
              :mode (or initial-filter-mode :default)
              :query ""
              :folded #{}
              :filtered []
              :visible-parent {}
              :visible-children {}
              :multiple-roots? false
              :selected-idx 0
              :last-selected-id initial-selected-id
              :show-label-timestamps false}
        tl (map->TreeList {:state-atom (atom base)
                           :max-visible-lines (long (max 5 (or max-visible-lines 10)))
                           :on-select-atom (atom on-select)
                           :on-cancel-atom (atom on-cancel)
                           :on-copy-atom (atom on-copy)
                           :on-label-edit-atom (atom on-label-edit)
                           :focused? (atom false)
                           :cache-atom (atom nil)})]
    (refilter! tl initial-selected-id)
    tl))

(defn tree-list-reload!
  "Swap in a fresh TREE snapshot (after a label change persisted via
   session/set-label!) keeping the filter state and selection."
  [tl tree]
  (swap! (:state-atom tl)
         (fn [{:keys [leaf-id] :as st}]
           (let [tree (attach-parent-ids tree)
                 parent-lookup (collect-parents tree)
                 active (active-path-set parent-lookup leaf-id)]
             (assoc st
                    :tree tree
                    :flat (flatten-tree* tree active (> (count tree) 1))
                    :parent-lookup parent-lookup
                    :active-path active
                    :tool-calls (collect-tool-calls tree)))))
  (refilter! tl))

;; ─── Chrome lines (pi: SearchLine / TreeHelp child components) ──────────────

(defcomponent TreeSearchLine nil [state-atom cache-atom]
  (render [this width]
    (track! this width
      (let [q (:query @state-atom)
            theme-current (th/get-current-theme)]
        [(u/truncate-to-width
          (str "  " (th/fg theme-current :muted "Type to search:")
               (when (seq q) (str " " (th/fg theme-current :accent q))))
          width)]))))

(defcomponent TreeHelpLine nil [cache-atom]
  (render [this width]
    (track! this width
      (let [theme-current (th/get-current-theme)]
        (mapv #(th/fg theme-current :muted %) (tree-help-lines width))))))

;; ─── Panel wiring (pi: showSelector + TreeSelectorComponent layout) ─────────

(defn show-session-tree
  "Session tree navigation panel mounted in place of the editor dock (pi
   showSelector): spacer/border/bold title/help/search-line/border, the
   tree list, border — focused on the list. ON-NAVIGATE receives the chosen
   entry; the caller performs the branch. INITIAL-SELECTED-ID restores the
   highlight when re-opening mid-flow (pi initialSelectedId)."
  ([cs on-navigate] (show-session-tree cs on-navigate nil))
  ([cs on-navigate initial-selected-id]
   (let [sess @(:session-atom cs)]
     (cond
       (nil? sess)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant :content "No active session."})

       (empty? (selector-tree sess))
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant :content "Session is empty."})

       :else
       (let [panel-theme (th/get-current-theme)
             leaf-id @(:leaf-id sess)
             term-height (or (when-let [term (:terminal (:tui cs))]
                               (terminal/rows @term))
                             40)
             sel-ref (atom nil)
             ;; late binding: the list callbacks close through this — the
             ;; panel + list they must unwind are built after the callbacks
             close-ref (atom nil)
             tl (make-tree-list (selector-tree sess)
                                :leaf-id leaf-id
                                :max-visible-lines (max 5 (quot term-height 2))
                                :initial-filter-mode (cfg/get-tree-filter-mode (:config cs))
                                :initial-selected-id initial-selected-id
                                :on-select (fn [entry]
                                             (@close-ref)
                                             (cond
                                               (= (:id entry) leaf-id)
                                               (chat-history/chat-history-add-message!
                                                (:chat-history cs)
                                                {:role :assistant
                                                 :content "Already at this point."})

                                               @(:running-turn? cs)
                                               (chat-history/chat-history-add-message!
                                                (:chat-history cs)
                                                {:role :assistant
                                                 :content "Wait for the current response to finish before navigating the session tree."})

                                               :else
                                               (on-navigate entry)))
                                :on-cancel (fn [] (@close-ref))
                                :on-copy (fn [text]
                                           (chat-history/chat-history-add-message!
                                            (:chat-history cs)
                                            (if-not text
                                              {:role :assistant
                                               :content "Selected entry has no text to copy."}
                                              (if (clipboard/copy-text! text)
                                                {:role :assistant
                                                 :content "Copied selected message to clipboard."}
                                                {:role :assistant
                                                 :content "No clipboard tool available on this system."}))))
                                :on-label-edit nil)
             ;; The inline label editor (pi: TreeSelectorComponent's
             ;; LabelInput): the panel body swaps the tree list for an Input
             ;; instead of floating an overlay over the panel.
             label-input-atom (atom nil)
             area (map->TreeLabelArea {:tree-list tl
                                       :label-input-atom label-input-atom
                                       :focused? (atom false)})
             end-label-edit! (fn []
                               (reset! label-input-atom nil)
                               (protocols/set-focused! tl @(:focused? area))
                               (tui/tui-request-render (:tui cs)))
             reload-and-render! (fn []
                                  ;; labels resolve from the appended :label
                                  ;; entry, so a fresh snapshot shows them
                                  (tree-list-reload! tl (selector-tree sess))
                                  (tui/tui-request-render (:tui cs)))
             ;; late-bound: the editor needs END-LABEL-EDIT! and
             ;; RELOAD-AND-RENDER!, which need TL/AREA — attach after
             ;; construction (pi: onLabelEdit wiring)
             _ (reset! (:on-label-edit-atom tl)
                       (fn [entry-id current-label]
                         (let [in (input/make-input)]
                           (when current-label
                             (input/input-set-value! in current-label)
                             ;; pi: LabelInput places the cursor after the
                             ;; prefilled text
                             (input/input-set-cursor! in (count current-label)))
                           (input/input-set-on-submit!
                            in (fn [value]
                                 (let [label (str/trim value)
                                       label' (when (seq label) label)]
                                   ;; empty submit clears (pi LabelInput)
                                   (session/set-label! sess entry-id label')
                                   (end-label-edit!)
                                   (reload-and-render!))))
                           (input/input-set-on-escape! in end-label-edit!)
                           (reset! label-input-atom in)
                           (protocols/set-focused! in @(:focused? area))
                           (tui/tui-request-render (:tui cs)))))
             ;; The panel is a compiled hiccup tree (dsl.md): the
             ;; spacer/border/title/help/search chrome is DSL-owned; the
             ;; body (TreeLabelArea: tree list or inline label editor)
             ;; splices foreign (the focus target).
             panel (h/compile-tree
                    [:container {}
                     [:spacer {:lines 1}]
                     [:dynamic-border {:color-fn #(th/fg panel-theme :accent %)}]
                     [:text {:padding-x 0 :padding-y 0} (th/bold "  Session Tree")]
                     (map->TreeHelpLine {:cache-atom (atom nil)})
                     (map->TreeSearchLine {:state-atom (:state-atom tl)
                                           :cache-atom (atom nil)})
                     [:dynamic-border {:color-fn #(th/fg panel-theme :accent %)}]
                     [:spacer {:lines 1}]
                     area
                     [:spacer {:lines 1}]
                     [:dynamic-border {:color-fn #(th/fg panel-theme :accent %)}]])]
         (reset! close-ref
                 (fn []
                   ;; pi: done() — restore the editor and unwind the panel:
                   ;; the compiled frame's DSL chrome via dispose-tree!, the
                   ;; spliced tree-list explicitly (the tree does not own it)
                   ((:done @sel-ref))
                   (h/dispose-tree! panel)
                   (protocols/dispose tl)))
         (reset! sel-ref {:done (dock/mount! cs panel area)})
         (tui/tui-request-render (:tui cs)))))))
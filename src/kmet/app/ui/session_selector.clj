(ns kmet.app.ui.session-selector
  "Session resume selector (pi: SessionSelectorComponent + SessionList +
   session-selector-search). A bordered panel: header line (bold title with
   the scope suffix, right-aligned scope indicator, Name and Sort filters,
   Loading N/M progress), two key-hint lines, a visible search input, and
   the session rows — accent cursor, dim threaded-tree prefixes, state-
   styled messages, right-aligned cwd/path + count + age, selectedBg on
   the selection. Behaviors (pi parity): Tab toggles current-folder/all
   scope, ctrl+s cycles Threaded/Recent/Fuzzy sort, ctrl+n toggles the
   named-only filter, ctrl+p toggles path display, ctrl+d deletes with an
   inline confirmation (trash CLI first, unlink fallback; the active
   session cannot be deleted), ctrl+r renames via an inline input panel,
   ctrl+backspace is a query-empty alias for delete. Search supports
   re:<regex> and \"phrase\" tokens over id/name/message-text/cwd;
   navigation clamps at the ends (no wrap) and PageUp/PageDown jump by the
   visible page. Sessions load asynchronously with progress; stale loads
   are dropped by sequence. ON-SELECT receives the chosen session path —
   the caller performs the restore (pi: the component emits the selection,
   interactive-mode switches the session)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.session :as session]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.debug :as debug]
            [kmet.tui.components.input :as input]
            [kmet.tui.core :as tui]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.timers :as timers]
            [kmet.tui.utils :as u]))

;; ─── Layout constants (pi: SessionList.maxVisible) ─────────────────────────

(def ^:private max-visible
  "Max sessions visible, one line each (pi: maxVisible)."
  10)

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- format-session-age
  "pi: formatSessionDate — now / Nm / Nh / Nd / Nw / Nmo / Ny."
  [ms]
  (let [diff (- (System/currentTimeMillis) (long ms))
        mins (quot diff 60000)
        hours (quot mins 60)
        days (quot hours 24)]
    (cond
      (< mins 1) "now"
      (< mins 60) (str mins "m")
      (< days 1) (str hours "h")
      (< days 7) (str days "d")
      (< days 30) (str (quot days 7) "w")
      (< days 365) (str (quot days 30) "mo")
      :else (str (quot days 365) "y"))))

(defn- shorten-path
  "pi: shortenPath — replace the $HOME prefix with ~."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (str/blank? (str path)) (str path)
      (and (seq home) (str/starts-with? path home)) (str "~" (subs path (count home)))
      :else path)))

(defn- normalize-message
  "pi: displayText.replace(control chars).trim — control characters become
   spaces so a multi-line first message renders as one row."
  [text]
  (-> (str/replace (str text) #"[\x00-\x1f\x7f]" " ")
      str/trim))

(defn- canon-path
  "Canonicalize PATH for identity comparisons (pi: canonicalizePath);
   unresolvable paths pass through unchanged."
  [path]
  (when path
    (try (str (fs/canonicalize path))
         (catch Exception _ path))))

(defn- has-session-name?
  "pi: hasSessionName — a non-blank display name is set."
  [info]
  (boolean (seq (str/trim (str (:name info))))))

;; ─── Search (pi: session-selector-search.ts) ───────────────────────────────

(defn- search-text
  "pi: getSessionSearchText — id, name, all message text, and cwd are
   searchable."
  [info]
  (str (:id info) " " (or (:name info) "") " " (or (:all-messages-text info) "")
       " " (or (:cwd info) "")))

(defn- parse-search-query
  "pi: parseSearchQuery — \"re:<pattern>\" switches to case-insensitive
   regex mode; otherwise whitespace-separated fuzzy tokens with \"phrase\"
   quoting. Unbalanced quotes fall back to plain whitespace tokenization.
   Returns {:mode :tokens|:regex :tokens [...] :regex pattern :error str?};
   a non-nil :error means nothing can match."
  [query]
  (let [trimmed (str/trim (str query))]
    (cond
      (str/blank? trimmed)
      {:mode :tokens :tokens [] :regex nil}

      (str/starts-with? trimmed "re:")
      (let [pattern (str/trim (subs trimmed 3))]
        (if (str/blank? pattern)
          {:mode :regex :tokens [] :regex nil :error "Empty regex"}
          (try
            {:mode :regex :tokens [] :regex (re-pattern (str "(?i)" pattern))}
            (catch Exception e
              {:mode :regex :tokens [] :regex nil :error (ex-message e)}))))

      :else
      (let [flush-token (fn [tokens buf kind]
                          (let [v (str/trim buf)]
                            (cond-> tokens
                              (seq v) (conj {:kind kind :value v}))))
            step (fn [{:keys [tokens buf in-quote]} ch]
                   (cond
                     (= ch \")
                     (if in-quote
                       {:tokens (flush-token tokens buf :phrase) :buf "" :in-quote false}
                       {:tokens (flush-token tokens buf :fuzzy) :buf "" :in-quote true})

                     (and (not in-quote) (re-find #"\s" (str ch)))
                     {:tokens (flush-token tokens buf :fuzzy) :buf "" :in-quote false}

                     :else
                     {:tokens tokens :buf (str buf ch) :in-quote in-quote}))
            final (reduce step {:tokens [] :buf "" :in-quote false} trimmed)]
        (if (:in-quote final)
          {:mode :tokens
           :tokens (->> (str/split trimmed #"\s+")
                        (map str/trim)
                        (remove str/blank?)
                        (mapv (fn [t] {:kind :fuzzy :value t})))
           :regex nil}
          {:mode :tokens :tokens (flush-token (:tokens final) (:buf final) :fuzzy)
           :regex nil})))))

(defn- normalize-whitespace-lower
  "pi: normalizeWhitespaceLower."
  [text]
  (-> (str/lower-case (str text)) (str/replace #"\s+" " ") str/trim))

(defn- match-session
  "pi: matchSession — lower score is better. Regex hits score by first-match
   index, phrase tokens by containment index (both ×0.1), fuzzy tokens sum
   their fuzzy-match scores. All tokens must match."
  [info parsed]
  (let [text (search-text info)]
    (cond
      (= :regex (:mode parsed))
      (if-let [re (:regex parsed)]
        (let [m (re-matcher re text)]
          (if (.find m)
            {:matches true :score (* (.start m) 0.1)}
            {:matches false :score 0}))
        {:matches false :score 0})

      (empty? (:tokens parsed))
      {:matches true :score 0}

      :else
      (loop [[tok & more] (:tokens parsed) total 0]
        (if (nil? tok)
          {:matches true :score total}
          (if (= :phrase (:kind tok))
            (let [phrase (normalize-whitespace-lower (:value tok))]
              (if (str/blank? phrase)
                (recur more total)
                (if-let [idx (str/index-of (normalize-whitespace-lower text) phrase)]
                  (recur more (+ total (* idx 0.1)))
                  {:matches false :score 0})))
            (let [m (fuzzy/fuzzy-match (:value tok) text)]
              (if (:matches m)
                (recur more (+ total (:score m)))
                {:matches false :score 0}))))))))

(defn- filter-and-sort-sessions
  "pi: filterAndSortSessions — an empty query keeps incoming (newest-first)
   order; recent mode filters without reordering; anything else sorts by
   match score with modified-desc tie-break. A parse error matches nothing."
  [sessions query sort-mode]
  (if (str/blank? (str/trim (str query)))
    (vec sessions)
    (let [parsed (parse-search-query query)]
      (if (:error parsed)
        []
        (if (= :recent sort-mode)
          (filterv #(:matches (match-session % parsed)) sessions)
          (->> sessions
               (keep (fn [info]
                       (let [{:keys [matches score]} (match-session info parsed)]
                         (when matches {:info info :score score}))))
               (sort-by (juxt :score (comp - :modified :info)))
               (mapv :info)
               vec))))))

;; ─── Threaded tree (pi: buildSessionTree / flattenSessionTree) ─────────────

(defn- build-session-tree-flat
  "Group sessions under their parent session (canonical-path lookup) and
   return the flattened display list: roots and every child list sorted by
   latest subtree activity descending, each node carrying {:depth :is-last
   :ancestor-continues} for the tree-prefix rendering (pi:
   buildSessionTree + flattenSessionTree)."
  [sessions]
  (let [idx (into {} (map-indexed (fn [i info] [(:path info) i]) sessions))
        children (volatile! (vec (repeat (count sessions) [])))
        roots (volatile! [])]
    (doseq [i (range (count sessions))
            :let [parent (when-let [pp (canon-path (:parent-session-path (nth sessions i)))]
                           (get idx pp))]]
      (if (and parent (not= parent i))
        (vswap! children update parent conj i)
        (vswap! roots conj i)))
    (let [latest-cache (atom {})]
      (letfn [(latest [i]
                (or (get @latest-cache i)
                    (let [v (apply max (long (or (:modified (nth sessions i)) 0))
                                   (map latest (@children i)))]
                      (swap! latest-cache assoc i v)
                      v)))
              (by-activity [is] (vec (sort-by latest > is)))]
        (let [roots* (by-activity @roots)
              children* (mapv by-activity @children)
              out (volatile! [])]
          (letfn [(walk [i depth ancestor-continues is-last]
                    (vswap! out conj {:info (nth sessions i)
                                      :depth depth
                                      :is-last is-last
                                      :ancestor-continues ancestor-continues})
                    (let [kids (children* i)]
                      (doseq [c kids]
                        ;; continuation lines only for non-root ancestors
                        (walk c (inc depth)
                              (conj ancestor-continues (and (pos? depth) (not is-last)))
                              (= c (peek kids))))))]
            (doseq [r roots*]
              (walk r 0 [] (= r (peek roots*)))))
          @out)))))

(defn- build-tree-prefix
  "pi: buildTreePrefix — │/space gutter columns plus ├─/└─ branch."
  [{:keys [depth is-last ancestor-continues]}]
  (if (zero? depth)
    ""
    (str (apply str (map #(if % "│  " "   ") ancestor-continues))
         (if is-last "└─ " "├─ "))))

;; ─── Deletion (pi: deleteSessionFile — trash CLI, unlink fallback) ─────────

(defn- delete-session-file
  "Delete a session file, trying the `trash` CLI first and falling back to
   permanent deletion (pi: deleteSessionFile). Returns {:ok bool :method
   :trash|:unlink :error str?}."
  [path]
  (let [unlink (fn [hint]
                 (try
                   (fs/delete path)
                   {:ok true :method :unlink}
                   (catch Exception e
                     {:ok false :method :unlink
                      :error (if hint
                               (str (ex-message e) " (" hint ")")
                               (ex-message e))})))
        trash-error-hint (fn [stderr]
                           (when-let [line (first (str/split-lines (str/trim (str stderr))))]
                             (when (seq line)
                               (str "trash: " (subs line 0 (min 200 (count line)))))))]
    (if (fs/which "trash")
      (let [args (if (str/starts-with? path "-") ["--" path] [path])
            result (try @(proc/process (into ["trash"] args)
                                       {:out :string :err :string})
                        (catch Exception _ nil))]
        (if (or (and result (zero? (:exit result))) (not (fs/exists? path)))
          {:ok true :method :trash}
          (unlink (some-> result :err trash-error-hint))))
      (unlink nil))))

;; ─── State helpers ─────────────────────────────────────────────────────────

(defn- active-sessions
  "The cached session infos of the active scope (pi: currentSessions /
   allSessions)."
  [st]
  (or (case (:scope st)
        :current (:current-sessions st)
        :all (:all-sessions st))
      []))

(defn- scope-loading?
  "True while the active scope's listing is still loading."
  [st]
  (case (:scope st)
    :current (:current-loading st)
    :all (:all-loading st)))

(defn- refilter!
  "Recompute the flattened display list from the active scope's cache,
   query, sort mode, and name filter; clamp the selection (pi:
   filterSessions — the selection index is kept, not reset)."
  [this]
  (let [st @(:state-atom this)
        name-filtered (if (= :named (:name-filter st))
                        (filterv has-session-name? (active-sessions st))
                        (active-sessions st))
        flat (if (and (= :threaded (:sort st)) (str/blank? (str/trim (:query st))))
               (build-session-tree-flat name-filtered)
               (mapv (fn [info] {:info info :depth 0 :is-last true
                                 :ancestor-continues []})
                     (filter-and-sort-sessions name-filtered (:query st) (:sort st))))
        n (count flat)]
    (swap! (:state-atom this)
           (fn [s]
             (-> s (assoc :flat flat)
                 (update :selected-idx #(min % (max 0 (dec n)))))))))

(defn- set-sessions!
  "Replace the active scope's cached list and re-render the rows (pi:
   SessionList.setSessions)."
  [this sessions]
  (swap! (:state-atom this)
         assoc (case (:scope @(:state-atom this))
                 :current :current-sessions
                 :all :all-sessions)
         (vec sessions))
  (refilter! this))

(defn- clear-status!
  "Drop the header status message and its auto-hide timer."
  [this]
  (when-let [id @(:timer-atom this)]
    (timers/cancel! id)
    (reset! (:timer-atom this) nil))
  (swap! (:state-atom this) dissoc :status))

(defn- set-status!
  "Show a header status message (:info/:error), auto-hidden after MS (pi:
   SessionSelectorHeader.setStatusMessage). The auto-hide rides the
   loop-owned timer registry (§6.1): it fires on the loop thread and is
   cancelled by dispose / tui-stop rather than by a parked thread."
  [this type message ms]
  (clear-status! this)
  (swap! (:state-atom this) assoc :status {:type type :message message})
  (when ms
    (reset! (:timer-atom this)
            (timers/after! ms
                           (fn []
                             (swap! (:state-atom this) dissoc :status)
                             ((:request-render this)))))))

(defn- hide!
  "Close the selector through the caller-installed close fn (pi: done() —
   restores the editor dock) and stop pending loads."
  [this]
  (swap! (:state-atom this) assoc :cancelled true)
  (when-let [h @(:hide-fn-atom this)]
    ((:hide h))))

(defn- selected-info
  "The currently highlighted session info, or nil."
  [this]
  (let [{:keys [flat selected-idx]} @(:state-atom this)
        n (count flat)]
    (when (pos? n)
      (:info (nth flat (min selected-idx (dec n)))))))

(defn- current-session-path?
  "True when PATH is the active session's own file (deletion guard)."
  [this path]
  (and (:current-session-file this)
       (= (canon-path path) (canon-path (:current-session-file this)))))

;; ─── Actions ───────────────────────────────────────────────────────────────

(declare exit-rename-mode!)

(defn- scope-state-keys
  "The state keys holding a scope's loading flag and cache."
  [scope]
  (case scope
    :current {:loading :current-loading :cache :current-sessions}
    :all {:loading :all-loading :cache :all-sessions}))

(defn session-selector-set-listing!
  "Feed a finished listing for SCOPE (:current/:all) into the selector and
   refresh the rows when that scope is active (the synchronous half of the
   load cycle — also usable directly by hosts that list sessions
   themselves). The cwd column follows the scope (pi: setSessions(sessions,
   showCwd))."
  [sel scope infos]
  (let [{:keys [loading cache]} (scope-state-keys scope)]
    (swap! (:state-atom sel)
           (fn [s]
             (cond-> (assoc s cache (vec infos)
                            loading false)
               (= (:scope s) scope) (assoc :progress nil))))
    (when (= (:scope @(:state-atom sel)) scope)
      (refilter! sel))))

(defn- load-scope!
  "Load a scope's sessions asynchronously with progress updates; stale
   progress (scope switched or a newer load started) is dropped (pi:
   loadScope + allLoadSeq). Returns the load future."
  [this scope reason]
  (let [seq# (swap! (:seq-atom this) inc)
        loader (case scope :current (:current (:loaders this)) :all (:all (:loaders this)))
        {:keys [loading]} (scope-state-keys scope)]
    (swap! (:state-atom this) assoc loading true :progress nil)
    ((:request-render this))
    (future
      (try
        (session-selector-set-listing!
         this scope
         (loader (fn [loaded total]
                   (let [st @(:state-atom this)]
                     (when (and (not (:cancelled st))
                                (= (:scope st) scope)
                                (= seq# @(:seq-atom this)))
                       (swap! (:state-atom this)
                              assoc :progress {:loaded loaded :total total})
                       ((:request-render this)))))))
        ((:request-render this))
        (catch Exception e
          (debug/log "session-selector load: " e)
          (swap! (:state-atom this) assoc loading false)
          (when (and (= (:scope @(:state-atom this)) scope)
                     (= seq# @(:seq-atom this)))
            (set-status! this :error
                         (str "Failed to load sessions: " (ex-message e)) 4000)
            (when (= reason :initial)
              (set-sessions! this []))
            ((:request-render this))))))))

(defn- toggle-scope!
  "Tab — switch between the current folder and all sessions; the all-scope
   list is loaded once and cached (pi: toggleScope)."
  [this]
  (let [st @(:state-atom this)]
    (if (= :current (:scope st))
      (do (swap! (:state-atom this) assoc :scope :all)
          (if (some? (:all-sessions st))
            (set-sessions! this (:all-sessions st))
            (when-not (:all-loading st)
              (load-scope! this :all :toggle))))
      (do (swap! (:state-atom this) assoc :scope :current)
          (set-sessions! this (or (:current-sessions st) []))))))

(defn- cycle-sort!
  "ctrl+s — cycle Threaded → Recent → Fuzzy (pi: toggleSortMode)."
  [this]
  (swap! (:state-atom this)
         assoc :sort (case (:sort @(:state-atom this))
                       :threaded :recent
                       :recent :relevance
                       :threaded))
  (refilter! this))

(defn- toggle-named-filter!
  "ctrl+n — toggle between all sessions and named-only (pi:
   toggleNameFilter)."
  [this]
  (swap! (:state-atom this)
         assoc :name-filter (if (= :all (:name-filter @(:state-atom this)))
                              :named :all))
  (refilter! this))

(defn- toggle-path!
  "ctrl+p — toggle the session file path column (pi: Ctrl+P handler)."
  [this]
  (swap! (:state-atom this) update :show-path not))

(defn- start-delete-confirmation!
  "ctrl+d — arm the inline delete confirmation; deleting the active session
   shows an error instead (pi: startDeleteConfirmationForSelectedSession)."
  [this]
  (when-let [info (selected-info this)]
    (if (current-session-path? this (:path info))
      (set-status! this :error "Cannot delete the currently active session" 3000)
      (do (swap! (:state-atom this) assoc :confirming-delete (:path info))
          ((:request-render this))))))

(defn- perform-delete!
  "Delete the confirmed session off-thread: drop it from both scope caches,
   report the outcome in the header, and refresh the active scope (pi:
   onDeleteSession)."
  [this path]
  (future
    (try
      (let [{:keys [ok method error]} ((:delete-session-fn this) path)]
        (if ok
          (let [drop-path (fn [infos]
                            (when infos
                              (vec (remove #(= path (:path %)) infos))))]
            (swap! (:state-atom this)
                   (fn [s]
                     (-> s
                         (update :current-sessions drop-path)
                         (update :all-sessions drop-path))))
            (refilter! this)
            (set-status! this :info
                         (if (= :trash method) "Session moved to trash" "Session deleted")
                         2000)
            (load-scope! this (:scope @(:state-atom this)) :refresh))
          (set-status! this :error (str "Failed to delete: " (or error "Unknown error")) 3000))
        ((:request-render this)))
      (catch Exception e
        (debug/log "session-selector delete: " e)
        (set-status! this :error (str "Failed to delete: " (ex-message e)) 3000)
        ((:request-render this))))))

(defn- enter-rename-mode!
  "ctrl+r — swap the panel content for the rename input, prefilled with the
   session's current name (pi: enterRenameMode)."
  [this path]
  (let [current (some #(when (= (:path %) path) %) (active-sessions @(:state-atom this)))
        rename-input (:rename-input this)
        current-name (or (:name current) "")]
    (input/input-set-value! rename-input current-name)
    (reset! (:cursor-atom rename-input) (count current-name))
    (swap! (:state-atom this) assoc :rename-mode true :rename-target path)
    ((:request-render this))))

(defn- rename-selected!
  "ctrl+r entry point — guarded while the active scope is loading (pi:
   onRenameSession)."
  [this]
  (when-not (scope-loading? @(:state-atom this))
    (when-let [info (selected-info this)]
      (enter-rename-mode! this (:path info)))))

(defn- exit-rename-mode!
  "Leave rename mode and rebuild the list layout (pi: exitRenameMode)."
  [this]
  (swap! (:state-atom this) assoc :rename-mode false :rename-target nil)
  ((:request-render this)))

(defn- confirm-rename!
  "Enter in rename mode — persist the new name off-thread, refresh the
   active scope, and leave rename mode (pi: confirmRename; blank names keep
   the editor open)."
  [this value]
  (let [next (str/trim (str value))]
    (when (seq next)
      (let [target (:rename-target @(:state-atom this))]
        (if-not target
          (exit-rename-mode! this)
          (future
            (try
              ((:rename-session-fn this) target next)
              (load-scope! this (:scope @(:state-atom this)) :refresh)
              (exit-rename-mode! this)
              (catch Exception e
                (debug/log "session-selector rename: " e)
                (exit-rename-mode! this)
                (set-status! this :error (str "Failed to rename: " (ex-message e)) 3000)
                ((:request-render this))))))))))

(defn- move-selection!
  "Up/Down/PageUp/PageDown — clamp at the ends, no wrap (pi SessionList)."
  [this delta]
  (let [n (count (:flat @(:state-atom this)))]
    (when (pos? n)
      (swap! (:state-atom this)
             update :selected-idx #(max 0 (min (dec n) (+ % delta)))))))

(defn- forward-to-search!
  "Everything that isn't a selector key goes to the search input; a changed
   value re-filters the list (pi: searchInput.handleInput + filterSessions)."
  [this data]
  (protocols/handle-input (:search-input this) data)
  (let [value (input/input-get-value (:search-input this))]
    (when-not (= value (:query @(:state-atom this)))
      (swap! (:state-atom this) assoc :query value)
      (refilter! this))))

;; ─── Rendering ─────────────────────────────────────────────────────────────

(defn- header-line
  "pi: SessionSelectorHeader.render line 1 — bold title left; scope ◉/○
   indicator, Name and Sort filters right (Loading N/M replaces the scope
   indicator while the active scope loads)."
  [th st width]
  (let [muted #(theme/fg th :muted %)
        accent #(theme/fg th :accent %)
        title (if (= :current (:scope st))
                "Resume Session (Current Folder)"
                "Resume Session (All)")
        sort-label (case (:sort st) :threaded "Threaded" :recent "Recent" "Fuzzy")
        name-label (if (= :all (:name-filter st)) "All" "Named")
        scope-text (if (scope-loading? st)
                     (str (muted "○ Current Folder | ")
                          (accent (if-let [p (:progress st)]
                                    (str "Loading " (:loaded p) "/" (:total p))
                                    "Loading ...")))
                     (if (= :current (:scope st))
                       (str (accent "◉ Current Folder") (muted " | ○ All"))
                       (str (muted "○ Current Folder | ") (accent "◉ All"))))
        right-text (u/truncate-to-width
                    (str scope-text "  " (muted "Name: ") (accent name-label)
                         "  " (muted "Sort: ") (accent sort-label))
                    width "")
        left (u/truncate-to-width (theme/bold title)
                                  (max 0 (- width (u/visible-width right-text) 1)) "")
        spacing (max 0 (- width (u/visible-width left) (u/visible-width right-text)))]
    (str left (apply str (repeat spacing \space)) right-text)))

(defn- hint-lines
  "pi: SessionSelectorHeader.render hint lines — the delete-confirmation
   prompt or status message replaces them while active."
  [th st width]
  (let [muted #(theme/fg th :muted %)
        sep (muted " · ")
        truncate #(u/truncate-to-width % width "…")]
    (if (:confirming-delete st)
      [(theme/fg th :error
                 (truncate (str "Delete session? "
                                (app-kb/key-hint "tui.select.confirm" "confirm")
                                " · "
                                (app-kb/key-hint "tui.select.cancel" "cancel"))))
       ""]
      (if-let [status (:status st)]
        [(let [color (if (= :error (:type status))
                       #(theme/fg th :error %)
                       #(theme/fg th :accent %))]
           (color (truncate (:message status))))
         ""]
        [(truncate (str (app-kb/key-hint "tui.input.tab" "scope") sep
                        (muted "re:<pattern> regex · \"phrase\" exact")))
         (truncate (str/join sep
                             [(app-kb/key-hint "app.session.toggleSort" "sort")
                              (app-kb/key-hint "app.session.toggleNamedFilter" "named")
                              (app-kb/key-hint "app.session.delete" "delete")
                              (app-kb/key-hint "app.session.togglePath"
                                               (str "path "
                                                    (if (:show-path st) "(on)" "(off)")))
                              (app-kb/key-hint "app.session.rename" "rename")]))]))))

(defn- empty-message
  "pi: SessionList.render empty state — named-filter and scope aware."
  [st]
  (let [toggle (app-kb/key-text "app.session.toggleNamedFilter")]
    (cond
      (= :named (:name-filter st))
      (if (= :all (:scope st))
        (str "  No named sessions found. Press " toggle " to show all.")
        (str "  No named sessions in current folder. Press " toggle
             " to show all, or Tab to view all."))
      (= :all (:scope st)) "  No sessions found"
      :else "  No sessions in current folder. Press Tab to view all.")))

(defn- session-row-line
  "pi: SessionList.render row — `› ` accent cursor for the selection, dim
   tree prefix, message truncated and styled (error while confirming
   delete, accent for the current session, warning for named ones, bold
   when selected), right-aligned shortened cwd/path + count + age, and the
   selected row on a selectedBg background."
  [th st node width current-file]
  (let [{:keys [info index]} node
        selected? (= index (:selected-idx st))
        confirming? (= (:path info) (:confirming-delete st))
        current? (and current-file
                      (= (canon-path (:path info)) (canon-path current-file)))
        display (normalize-message (or (:name info) (:first-message info) ""))
        prefix (build-tree-prefix node)
        right-part (as-> (str (:message-count info) " "
                              (format-session-age (:modified info))) rp
                     (if (and (= :all (:scope st)) (seq (str (:cwd info))))
                       (str (shorten-path (:cwd info)) " " rp) rp)
                     (if (:show-path st)
                       (str (shorten-path (:path info)) " " rp) rp))
        cursor (if selected? (theme/fg th :accent "› ") "  ")
        available-for-msg (max 10 (- width 2 (u/visible-width prefix)
                                     (+ (u/visible-width right-part) 2)))
        msg (u/truncate-to-width display available-for-msg "…")
        color (cond confirming? :error
                    current? :accent
                    (has-session-name? info) :warning)
        styled-msg (cond-> (if color (theme/fg th color msg) msg)
                     selected? theme/bold)
        left-part (str cursor (theme/fg th :dim prefix) styled-msg)
        spacing (max 1 (- width (u/visible-width left-part)
                          (u/visible-width right-part)))
        line (str left-part (apply str (repeat spacing \space))
                  (theme/fg th (if confirming? :error :dim) right-part))
        line (if selected? (theme/bg th :selected-bg line) line)]
    (u/truncate-to-width line width)))

(defn- content-lines
  "pi: SessionList.render body — the rows viewport centered on the
   selection, the contextual empty message, and the (N/M) scroll indicator
   when clipped."
  [this th st width]
  (let [flat (:flat st)
        n (count flat)]
    (if (zero? n)
      [(theme/fg th :muted (u/truncate-to-width (empty-message st) width "…"))]
      (let [selected (min (:selected-idx st) (dec n))
            start (max 0 (min (- selected (quot max-visible 2)) (- n max-visible)))
            end (min (+ start max-visible) n)
            rows (mapv (fn [i]
                         (session-row-line th st (assoc (nth flat i) :index i)
                                           width (:current-session-file this)))
                       (range start end))
            scroll (when (or (pos? start) (< end n))
                     [(theme/fg th :muted
                                (u/truncate-to-width
                                 (str "  (" (inc selected) "/" n ")") width ""))])]
        (into rows (or scroll []))))))

(defcomponent SessionSelector nil
              [state-atom search-input rename-input loaders current-session-file
               rename-session-fn delete-session-fn request-render on-select-atom
               on-cancel-atom timer-atom seq-atom hide-fn-atom focused? cache-atom
               root]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom]
      (cond
        ;; Rename mode — escape leaves, everything else edits the name
        ;; (pi: SessionSelectorComponent.handleInput rename branch)
        (:rename-mode st)
        (if (kb/matches-key kmgr data "tui.select.cancel")
          (do (exit-rename-mode! this) nil)
          (do (protocols/handle-input rename-input data) nil))

        ;; Delete confirmation intercepts every key until resolved
        ;; (pi: SessionList.handleInput confirming branch)
        (:confirming-delete st)
        (let [path (:confirming-delete st)]
          (cond
            (kb/matches-key kmgr data "tui.select.confirm")
            (do (swap! state-atom assoc :confirming-delete nil)
                (perform-delete! this path)
                nil)

            (kb/matches-key kmgr data "tui.select.cancel")
            (do (swap! state-atom assoc :confirming-delete nil)
                ((:request-render this))
                nil)

            :else nil))

        :else
        (cond
          (kb/matches-key kmgr data "tui.input.tab")
          (do (toggle-scope! this) nil)

          (kb/matches-key kmgr data "app.session.toggleSort")
          (do (cycle-sort! this) nil)

          (kb/matches-key kmgr data "app.session.toggleNamedFilter")
          (do (toggle-named-filter! this) nil)

          (kb/matches-key kmgr data "app.session.togglePath")
          (do (toggle-path! this) nil)

          (kb/matches-key kmgr data "app.session.delete")
          (do (start-delete-confirmation! this) nil)

          (kb/matches-key kmgr data "app.session.rename")
          (do (rename-selected! this) nil)

          ;; Non-invasive delete alias: only arms deletion when the query is
          ;; empty, otherwise it edits the query (pi: deleteNoninvasive)
          (kb/matches-key kmgr data "app.session.deleteNoninvasive")
          (if (seq (str/trim (:query st)))
            (do (forward-to-search! this data) nil)
            (do (start-delete-confirmation! this) nil))

          (kb/matches-key kmgr data "tui.select.up")
          (do (move-selection! this -1) nil)

          (kb/matches-key kmgr data "tui.select.down")
          (do (move-selection! this 1) nil)

          (kb/matches-key kmgr data "tui.select.pageUp")
          (do (move-selection! this (- max-visible)) nil)

          (kb/matches-key kmgr data "tui.select.pageDown")
          (do (move-selection! this max-visible) nil)

          (kb/matches-key kmgr data "tui.select.confirm")
          (do (when-let [info (selected-info this)]
                (clear-status! this)
                (hide! this)
                (when-let [cb @on-select-atom]
                  (cb (:path info))))
              nil)

          (kb/matches-key kmgr data "tui.select.cancel")
          (do (clear-status! this)
              (hide! this)
              (when-let [cb @on-cancel-atom]
                (cb))
              nil)

          :else
          (do (forward-to-search! this data) nil)))))
  (dispose [this]
    ;; cancel the pending status auto-hide before the tree goes (§6.1)
    (when-let [id @(:timer-atom this)]
      (timers/cancel! id)
      (reset! (:timer-atom this) nil))
    ;; unwind the content tree's reaction (watch on the state atom) with the
    ;; selector — show-session-selector's done disposes it on editor restore
    (protocols/dispose (:root this))))

;; ─── IFocusable — forward to the inputs (IME cursor positioning) ───────────

(extend-type SessionSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)
    (protocols/set-focused! (:rename-input this) val)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn- default-rename-session!
  "Persist a session display name (pi: SessionManager.open +
   appendSessionInfo)."
  [path name]
  (session/append-session-info! (session/load-session path) name))

(defn make-session-selector
  "Create the session resume selector (pi: SessionSelectorComponent).
   Options:
     :loaders               — {:current (fn [on-progress]) :all (fn [..])}
                              returning session-info maps (pi:
                              SessionsLoader pair)
     :current-session-file  — the active session's file, marked accent and
                              protected from deletion
     :rename-session        — (fn [path name]); defaults to appending a
                              session_info entry
     :delete-session        — (fn [path] {:ok .. :method .. :error ..});
                              defaults to trash-then-unlink
     :on-select             — (fn [path]) after the selector closes
     :on-cancel             — (fn []) on escape
     :request-render        — host re-render callback
   The initial listing is NOT started here — show-session-selector kicks it
   off so tests can build selectors synchronously."
  [& {:keys [loaders current-session-file rename-session delete-session
             on-select on-cancel request-render]}]
  (let [search-input (input/make-input)
        rename-input (input/make-input)
        sel (map->SessionSelector
             {:state-atom (atom {:scope :current
                                 :sort :threaded
                                 :name-filter :all
                                 :show-path false
                                 :query ""
                                 :flat []
                                 :selected-idx 0
                                 :current-sessions nil
                                 :all-sessions nil
                                 :current-loading false
                                 :all-loading false
                                 :progress nil
                                 :confirming-delete nil
                                 :status nil
                                 :rename-mode false
                                 :rename-target nil})
              :search-input search-input
              :rename-input rename-input
              :loaders (or loaders {:current (fn [_] []) :all (fn [_] [])})
              :current-session-file (some-> current-session-file canon-path)
              :rename-session-fn (or rename-session default-rename-session!)
              :delete-session-fn (or delete-session delete-session-file)
              :request-render (or request-render (fn []))
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :timer-atom (atom nil)
              :seq-atom (atom 0)
              :hide-fn-atom (atom nil)
              :focused? (atom false)
              :cache-atom (atom nil)})
        ;; The panel frame as a mounted hiccup root (dsl.md): the border,
        ;; header and hint lines and the width-dependent rows re-derive from
        ;; the state atom and the render width; the search/rename input
        ;; splices foreign (lifecycle owned by the selector). Blank lines are
        ;; spacers — the DSL does not full-width-pad blanks (only the bg'd
        ;; selection row and the border span the panel; :text and
        ;; dynamic-border provide that).
        root (hiccup/root
              (fn [_props]
                (let [w hiccup/*width*
                      th (theme/get-current-theme)
                      st @(:state-atom sel)
                      border-fn #(theme/fg th :accent %)
                      tree (if (:rename-mode st)
                             [:container {}
                              [:spacer {:lines 1}]
                              [:dynamic-border {:color-fn border-fn}]
                              [:spacer {:lines 1}]
                              [:text {:text (str " " (theme/bold "Rename Session"))
                                      :padding-x 0 :padding-y 0}]
                              [:spacer {:lines 1}]
                              (:rename-input sel)
                              [:spacer {:lines 1}]
                              [:text {:text (str " "
                                                 (theme/fg th :muted
                                                           (str (app-kb/key-text "tui.select.confirm")
                                                                " to save · "
                                                                (app-kb/key-text "tui.select.cancel")
                                                                " to cancel")))
                                      :padding-x 0 :padding-y 0}]
                              [:spacer {:lines 1}]
                              [:dynamic-border {:color-fn border-fn}]]
                             (let [hints (hint-lines th st w)]
                               [:container {}
                                [:spacer {:lines 1}]
                                [:dynamic-border {:color-fn border-fn}]
                                [:spacer {:lines 1}]
                                [:text {:text (header-line th st w) :padding-x 0 :padding-y 0}]
                                [:text {:text (hints 0) :padding-x 0 :padding-y 0}]
                                [:text {:text (hints 1) :padding-x 0 :padding-y 0}]
                                [:spacer {:lines 1}]
                                (:search-input sel)
                                [:spacer {:lines 1}]
                                (map (fn [row]
                                       [:text {:text row :padding-x 0 :padding-y 0}])
                                     (content-lines sel th st w))
                                [:spacer {:lines 1}]
                                [:dynamic-border {:color-fn border-fn}]]))]
                  tree)))]
    (input/input-set-on-submit! rename-input (fn [value] (confirm-rename! sel value)))
    (assoc sel :root root)))

(defn show-session-selector
  "Open the session resume selector in place of the editor (pi:
   showSelector — the component replaces editorContainer's content and
   done() restores the editor + focus). SESSION-DIR-FN returns the base
   sessions dir — the current-folder scope lists its cwd-encoded
   subdirectory, the all scope walks the base dir. ON-SELECT receives the
   chosen session path after the selector closes; the caller performs the
   restore."
  ([cs session-dir-fn on-select]
   (show-session-selector cs session-dir-fn on-select {}))
  ([cs session-dir-fn on-select & [{:keys [current-session-file]}]]
   (let [base-dir (session-dir-fn)
         ;; the current-folder scope is the session's project (pi: the
         ;; runtime cwd), so a session resumed from elsewhere browses that
         ;; project's sessions
         cwd-dir (session/session-dir-for-cwd
                  base-dir
                  (or (some-> (:footer-provider cs) fdp/fdp-get-cwd)
                      (str (fs/cwd))))
         tui* (:tui cs)
         ;; late binding: the hide/select/cancel closures resolve the
         ;; selector through this atom once it exists
         sel-atom (atom nil)
         sel (make-session-selector
              :loaders {:current #(session/list-sessions-info cwd-dir %)
                        :all #(session/list-sessions-info base-dir %)}
              :current-session-file (or current-session-file
                                        (some-> cs :session-atom deref :file))
              :on-select (fn [path]
                           (hide! @sel-atom)
                           (on-select path))
              :on-cancel (fn [] (hide! @sel-atom))
              :request-render (fn [] (tui/tui-request-render tui*)))]
     (reset! sel-atom sel)
     ;; pi: showSelector — swap the selector into the editor dock; hide!
     ;; runs the returned done, restoring the editor + focus
     (reset! (:hide-fn-atom sel) {:hide (dock/mount! cs sel)})
     (load-scope! sel :current :initial)
     sel)))

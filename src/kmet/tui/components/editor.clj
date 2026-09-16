(ns kmet.tui.components.editor
  "Multi-line text editor with word-wrap, vertical scrolling, and cursor.
   Port of @earendil-works/pi-tui Editor.
   Phase 2b.1 — Core Editor: multi-line editing, word-wrap, cursor movement,
   vertical scrolling, border, basic editing (typing, backspace, delete,
   enter newline, submit).
   Phase 4 — Editor quick wins: paste-marker atomic segments, CSI-u paste
   decode, smart path spacing, paste marker renumbering, history draft
   preservation, dynamic editor height.
   Phase 6 — App actions (pi: CustomEditor): registered action handlers
   dispatched from handle-input (editor-set-on-action!), and expanded-text
   support for the external editor flow (editor-get-expanded-text)."
  (:require [clojure.string :as str]
            [kmet.tui.border :as border]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.utils :as u]
            [kmet.tui.components.editing :as edit]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.autocomplete :as ac]))

;; ─── Grapheme helpers ─────────────────────────────────────────────────────
;; Imported from kmet.tui.components.editing (grapheme-left, grapheme-right,
;; grapheme-at, grapheme-segments, KillRing, etc.)

;; ─── Word-wrapped line layout ──────────────────────────────────────────────

(defrecord TextChunk [text start-index end-index])

(defn- word-wrap-line
  "Split a single logical line into word-wrapped visual chunks.
   Paste markers (ids in valid-paste-ids) are treated as atomic segments
   so wrapping never breaks inside a marker."
  [line max-width valid-paste-ids]
  (if (or (nil? line) (<= max-width 0))
    [(map->TextChunk {:text (or line "") :start-index 0 :end-index (count (or line ""))})]
    (let [line-width (u/visible-width line)]
      (if (<= line-width max-width)
        [(map->TextChunk {:text line :start-index 0 :end-index (count line)})]
        (let [segments (edit/segment-with-markers line edit/grapheme-segments valid-paste-ids)
              n (count segments)
              result (volatile! [])
              cw (volatile! 0)    ;; current line width
              cs (volatile! 0)    ;; current chunk start index
              woi (volatile! -1)  ;; wrap opportunity segment index
              wow (volatile! 0)]  ;; wrap opportunity width
          (loop [i 0]
            (when (< i n)
              (let [seg (nth segments i)
                    gwidth (u/visible-width (:text seg))
                    char-idx (:start seg)
                    is-ws (boolean (re-find #"^\s" (:text seg)))]
                (if (> gwidth max-width)
                  ;; Segment wider than the wrap width
                  (if (edit/paste-marker? (:text seg))
                    ;; Paste markers are atomic — overflow to their own chunk
                    ;; instead of breaking inside the marker
                    (do (when (< @cs char-idx)
                          (vswap! result conj
                                  (map->TextChunk {:text (subs line @cs char-idx)
                                                   :start-index @cs
                                                   :end-index char-idx})))
                        (vswap! result conj
                                (map->TextChunk {:text (:text seg)
                                                 :start-index char-idx
                                                 :end-index (+ char-idx (count (:text seg)))}))
                        (vreset! cs (+ char-idx (count (:text seg))))
                        (vreset! cw gwidth)
                        (vreset! woi -1) (vreset! wow 0))
                    ;; Other atomic segment wider than max-width: recurse
                    (let [sub-chunks (word-wrap-line (:text seg) max-width valid-paste-ids)]
                      (doseq [sc (butlast sub-chunks)]
                        (vswap! result conj
                                (map->TextChunk {:text (:text sc)
                                                 :start-index (+ char-idx (:start-index sc))
                                                 :end-index (+ char-idx (:end-index sc))})))
                      (let [last-sc (last sub-chunks)]
                        (vreset! cs (+ char-idx (:start-index last-sc)))
                        (vreset! cw (u/visible-width (:text last-sc)))
                        (vreset! woi -1) (vreset! wow 0))))
                  ;; Normal-width grapheme
                  (do
                    ;; Overflow check
                    (when (> (+ @cw gwidth) max-width)
                      (if (and (>= @woi 0)
                               (<= (+ (- @cw @wow) gwidth) max-width))
                        (let [opp-seg (nth segments @woi)]
                          (vswap! result conj
                                  (map->TextChunk {:text (subs line @cs (:start opp-seg))
                                                   :start-index @cs
                                                   :end-index (:start opp-seg)}))
                          (vreset! cs (:start opp-seg))
                          ;; The emitted chunk covered everything up to and
                          ;; including the trailing whitespace (width @wow);
                          ;; only the non-whitespace content advanced since
                          ;; the opportunity stays on this line (pi:
                          ;; currentWidth -= wrapOppWidth). Setting cw to
                          ;; @wow instead overcounts and forces an extra
                          ;; mid-word hard break right after every
                          ;; word-boundary wrap.
                          (vswap! cw - @wow))
                        (do
                          (when (< @cs char-idx)
                            (vswap! result conj
                                    (map->TextChunk {:text (subs line @cs char-idx)
                                                     :start-index @cs
                                                     :end-index char-idx})))
                          (vreset! cs char-idx)
                          (vreset! cw 0)))
                      (vreset! woi -1) (vreset! wow 0))
                    ;; Advance
                    (vswap! cw + gwidth)
                    ;; Record wrap opportunity (ws followed by non-ws)
                    (let [nxt (when (< (inc i) n) (nth segments (inc i)))]
                      (when (and is-ws nxt (not (re-find #"^\s" (:text nxt))))
                        ;; The opportunity is the segment AFTER the whitespace
                        ;; (pi: wrapOppIndex = next.index) — backtracking there
                        ;; keeps the trailing space on the wrapped line.
                        (vreset! woi (inc i))
                        (vreset! wow @cw))))))
              (recur (inc i))))
          (vswap! result conj
                  (map->TextChunk {:text (subs line @cs)
                                   :start-index @cs
                                   :end-index (count line)}))
          @result)))))

;; ─── Visual line map ───────────────────────────────────────────────────────

(defrecord VisualLineInfo [logical-line start-col length text])

(defn- build-visual-line-map
  "Build a vector of VisualLineInfo from logical lines and content width."
  [lines width valid-paste-ids]
  (if (or (empty? lines) (and (= (count lines) 1) (empty? (first lines))))
    [(map->VisualLineInfo {:logical-line 0 :start-col 0 :length 0 :text ""})]
    (vec (mapcat
          (fn [i]
            (let [line (or (nth lines i) "")
                  lw (u/visible-width line)]
              (if (<= lw width)
                [(map->VisualLineInfo {:logical-line i :start-col 0
                                       :length (count line) :text line})]
                (map #(map->VisualLineInfo {:logical-line i
                                            :start-col (:start-index %)
                                            :length (count (:text %))
                                            :text (:text %)})
                     (word-wrap-line line width valid-paste-ids)))))
          (range (count lines))))))

(defn- find-visual-line-at
  "Find visual line index containing the given logical line + column."
  [visual-lines logical-line col]
  (loop [i 0]
    (if (>= i (count visual-lines))
      (max 0 (dec (count visual-lines)))
      (let [vl (nth visual-lines i)]
        (if (and (= (:logical-line vl) logical-line)
                 (>= col (:start-col vl))
                 (<= col (+ (:start-col vl) (:length vl))))
          i
          (recur (inc i)))))))

(defn- find-current-visual-line
  "Find the visual line index for the current cursor position."
  [visual-lines cursor-line cursor-col]
  (find-visual-line-at visual-lines cursor-line cursor-col))

;; ─── Editor state ─────────────────────────────────────────────────────────

(defrecord EditorState [lines cursor-line cursor-col])

(defn make-editor-state
  ([] (map->EditorState {:lines [""] :cursor-line 0 :cursor-col 0}))
  ([text]
   (let [lines (if (empty? text) [""] (clojure.string/split-lines text))
         last-idx (count lines)]
     (map->EditorState {:lines lines
                        :cursor-line (max 0 (dec last-idx))
                        :cursor-col (count (last lines))}))))

;; ─── Public helpers ────────

(defn editor-get-text [editor]
  (clojure.string/join "\n" (:lines @(:state-atom editor))))

;; ─── Kill ring
;; Imported from kmet.tui.components.editing

;; ─── Undo/redo stacks

(defn- snapshot-state
  "Capture the editor state (lines, cursor) plus the paste store so undo
   and history drafts restore markers consistently."
  [editor]
  (let [s @(:state-atom editor)]
    {:lines (:lines s) :cursor-line (:cursor-line s) :cursor-col (:cursor-col s)
     :paste-store @(:paste-store editor)}))

(defn- undo-push [stack-atom snapshot]
  (swap! stack-atom conj snapshot))

(defn- undo-pop [stack-atom]
  (let [s @stack-atom]
    (when (seq s)
      (let [snapshot (peek s)]
        (swap! stack-atom pop)
        snapshot))))

(defn- push-undo-state [editor]
  (undo-push (:undo-stack editor) (snapshot-state editor)))

(defn- handle-undo [editor]
  (when-let [snapshot (undo-pop (:undo-stack editor))]
    (undo-push (:redo-stack editor) (snapshot-state editor))
    (reset! (:state-atom editor)
            (map->EditorState {:lines (:lines snapshot)
                               :cursor-line (:cursor-line snapshot)
                               :cursor-col (:cursor-col snapshot)}))
    (reset! (:paste-store editor) (:paste-store snapshot))
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-redo [editor]
  (when-let [snapshot (undo-pop (:redo-stack editor))]
    (undo-push (:undo-stack editor) (snapshot-state editor))
    (reset! (:state-atom editor)
            (map->EditorState {:lines (:lines snapshot)
                               :cursor-line (:cursor-line snapshot)
                               :cursor-col (:cursor-col snapshot)}))
    (reset! (:paste-store editor) (:paste-store snapshot))
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

;; ─── Word navigation

(defn- word-boundary-left [editor-lines cursor-line cursor-col]
  (let [cl (max 0 cursor-line) cc (max 0 cursor-col)]
    (if (and (zero? cl) (zero? cc))
      [0 0]
      (let [line (get editor-lines cl "")]
        (if (> cc 0)
          (let [before (subs line 0 cc)
                no-trail (clojure.string/replace before #"\s+$" "")
                trimmed (count no-trail)]
            (if (zero? trimmed) [cl 0]
                (let [last-char (subs no-trail (dec trimmed))
                      word-char? (boolean (re-find #"^\w" last-char))]
                  (loop [i (dec trimmed)]
                    (if (<= i 0) [cl 0]
                        (let [c (subs line i (inc i)) is-word (re-find #"^\w" c) is-space (re-find #"^\s" c)] (cond is-space (if word-char? [cl (inc i)] (recur (dec i))) word-char? (if is-word (recur (dec i)) [cl (inc i)]) :else (if is-word [cl (inc i)] (recur (dec i))))))))))
          (if (> cl 0)
            (let [prev-line (get editor-lines (dec cl) "")]
              (if (zero? (count prev-line))
                (word-boundary-left editor-lines (dec cl) 0)
                (let [no-trail (clojure.string/replace prev-line #"\s+$" "")
                      trimmed (count no-trail)]
                  (if (zero? trimmed) [(dec cl) 0]
                      (let [last-char (subs no-trail (dec trimmed))
                            word-char? (boolean (re-find #"^\w" last-char))]
                        (loop [i (dec trimmed)]
                          (if (<= i 0) [(dec cl) 0]
                              (let [c (subs prev-line i (inc i)) is-word (re-find #"^\w" c) is-space (re-find #"^\s" c)] (cond is-space (if word-char? [(dec cl) (inc i)] (recur (dec i))) word-char? (if is-word (recur (dec i)) [(dec cl) (inc i)]) :else (if is-word [(dec cl) (inc i)] (recur (dec i))))))))))))
            [0 0]))))))

(defn- word-boundary-right [editor-lines cursor-line cursor-col]
  (let [cl (max 0 cursor-line) cc (max 0 cursor-col) total (count editor-lines)]
    (if (and (>= cl (dec total)) (>= cc (count (get editor-lines cl "")))) [cl cc]
        (let [line (get editor-lines cl "") n (count line)]
          (if (< cc n)
          ;; Pure Clojure word boundary detection
            (let [after (subs line cc)
                  skip-ws (count (take-while #(re-find #"^\s" (str %)) after))
                  start (+ cc skip-ws)]
              (if (>= start n)
                (if (< cl (dec total)) [(inc cl) 0] [cl n])
                (let [rest-str (subs line start)
                      word-len (count (take-while #(re-find #"^\w" (str %)) rest-str))]
                  (if (pos? word-len)
                    [cl (+ start word-len)]
                    (let [non-ws (count (take-while #(not (re-find #"^\s" (str %))) rest-str))]
                      [cl (+ start (max 1 non-ws))])))))
            (if (< cl (dec total)) [(inc cl) 0] [cl cc]))))))

;; ─── Marker-aware segmentation & paste store sync ────────────────────────
;; Paste markers ([paste #N ...]) act as atomic units for cursor movement,
;; deletion, and word-wrap. When a marker is deleted the paste store is
;; cleaned up and remaining markers are renumbered (pi: paste marker
;; renumbering).

(defn- valid-paste-ids
  "Set of paste marker ids currently present in the paste store."
  [editor]
  (set (keys @(:paste-store editor))))

(defn- segment
  "Grapheme segments of text, with paste markers treated as atomic units."
  [editor text]
  (edit/segment-with-markers text edit/grapheme-segments (valid-paste-ids editor)))

(defn- segment-left
  "Move one (marker-aware) grapheme segment left within text."
  [editor text pos]
  (if (<= pos 0)
    0
    (:start (last (segment editor (subs text 0 pos))))))

(defn- segment-right
  "Move one (marker-aware) grapheme segment right within text."
  [editor text pos]
  (let [n (count text)]
    (if (>= pos n)
      n
      (let [segs (segment editor (subs text pos))]
        (if (seq segs)
          (+ pos (count (:text (first segs))))
          n)))))

(defn- sync-paste-store!
  "Sync paste-store with the markers present in lines:
   - drops entries whose marker was deleted,
   - renumbers remaining markers to close gaps (store keys and marker text),
   - resets the paste counter so future markers stay sequential.
   Returns the (possibly rewritten) lines."
  [editor lines]
  (let [store @(:paste-store editor)
        markers (vec (mapcat edit/find-paste-markers-in-line lines))
        live-ids (mapv :id (filter #(contains? store (:id %)) markers))
        new-ids (mapv inc (range (count live-ids)))
        id->new (zipmap live-ids new-ids)]
    (reset! (:paste-store editor)
            (into {} (map (fn [id] [(id->new id) (get store id)]) live-ids)))
    (reset! (:paste-counter editor) (count live-ids))
    (if (= live-ids new-ids)
      lines
      (mapv #(edit/renumber-paste-markers-in-line % id->new) lines))))

(defn- after-destructive-edit!
  "Run after any edit that may have removed text: drop stale paste store
   entries, renumber remaining markers, and clamp the cursor to the line."
  [editor]
  (when (seq @(:paste-store editor))
    (swap! (:state-atom editor)
           (fn [st]
             (let [lines (sync-paste-store! editor (:lines st))
                   cl (:cursor-line st)]
               (assoc st
                      :lines lines
                      :cursor-col (min (:cursor-col st) (count (get lines cl "")))))))))

;; ─── Autocomplete
;; Port of pi editor.ts autocomplete integration (synchronous — no debounce
;; or abort machinery needed since every request computes fresh from the
;; current editor state):
;; - Tab triggers completion (slash command name or file path)
;; - "/" at message start, trigger chars (@/#) at token boundaries, and
;;   letters within those contexts auto-trigger the dropdown
;; - The dropdown is a SelectList rendered below the editor border

(def ^:private default-trigger-chars ["@" "#"])

(defn- provider-trigger-chars
  [editor]
  (distinct (concat default-trigger-chars
                    (if-let [ap @(:autocomplete-provider editor)]
                      (vec (ac/get-trigger-characters ap))
                      []))))

;; Memo of the compiled autocomplete trigger spec per trigger-char set:
;; {:pattern re :chars #{\c …}}. The set comes from the provider's
;; `get-trigger-characters` and is stable per provider, so keying on its
;; joined chars keeps re-pattern (with its escaping pass) off the
;; per-keystroke path.
(defonce ^:private trigger-spec-cache (atom {}))

(defn- trigger-spec
  "Compiled trigger info for EDITOR: {:pattern re :chars #{…}}, rebuilt only
   when the trigger-char set changes (the escaping pass and re-pattern were
   per keystroke)."
  [editor]
  (let [chars (provider-trigger-chars editor)
        k (clojure.string/join "\u0000" chars)]
    (or (get @trigger-spec-cache k)
        (let [escaped (->> chars
                           (map #(clojure.string/replace % #"[\\^$.*+?()\[\]{}|-]" "\\$&"))
                           (clojure.string/join ""))
              spec {:pattern (re-pattern (str "(?:^|[\\s])[" escaped "][^\\s]*$"))
                    :chars (set chars)}]
          (swap! trigger-spec-cache assoc k spec)
          spec))))

(defn- trigger-pattern
  "Regex matching text-before-cursor that is in a symbol-trigger context
   (e.g. \"@foo\" at line start or after whitespace)."
  [editor]
  (:pattern (trigger-spec editor)))

(defn- is-at-start-of-message?
  [editor]
  (let [state @(:state-atom editor)
        line (or (nth (:lines state) (:cursor-line state)) "")
        trimmed (clojure.string/trim (subs line 0 (:cursor-col state)))]
    (or (empty? trimmed) (= trimmed "/"))))

(defn- is-in-slash-command-context?
  [editor]
  (let [state @(:state-atom editor)
        line (or (nth (:lines state) (:cursor-line state)) "")
        before (subs line 0 (:cursor-col state))]
    (clojure.string/starts-with? (clojure.string/triml before) "/")))

(defn- cancel-autocomplete
  [editor]
  (reset! (:autocomplete-state editor) nil)
  (reset! (:autocomplete-list editor) nil)
  (reset! (:autocomplete-prefix editor) ""))

(defn- get-best-autocomplete-match-index
  "Index of the item whose value exactly equals prefix, else the first
   item whose value starts with prefix, else -1 (pi:
   getBestAutocompleteMatchIndex)."
  [items prefix]
  (loop [i 0 first-prefix-idx -1]
    (if (>= i (count items))
      first-prefix-idx
      (let [v (or (:value (nth items i)) "")]
        (cond
          (= v prefix) i
          (and (= first-prefix-idx -1) (clojure.string/starts-with? v prefix))
          (recur (inc i) i)
          :else (recur (inc i) first-prefix-idx))))))

(defn- apply-autocomplete-suggestions
  "Show the dropdown for the given suggestions (pi:
   applyAutocompleteSuggestions)."
  [editor suggestions state-kw]
  (let [items (mapv (fn [it]
                      {:label (:label it)
                       :value (:value it)
                       :description (:description it)})
                    (:items suggestions))
        sl (select-list/make-select-list items
                                         :height @(:autocomplete-max-visible editor)
                                         :theme @(:autocomplete-theme editor)
             ;; pi: SLASH_COMMAND_SELECT_LIST_LAYOUT (min 12 / max 32)
                                         :min-primary-column-width 12
                                         :max-primary-column-width 32)]
    (when-let [idx (get-best-autocomplete-match-index items (:prefix suggestions))]
      (when (>= idx 0)
        (reset! (:selected-idx-atom sl) idx)))
    (reset! (:autocomplete-prefix editor) (:prefix suggestions))
    (reset! (:autocomplete-list editor) sl)
    (reset! (:autocomplete-state editor) state-kw)))

(defn- set-editor-state!
  "Replace the editor state (lines + cursor) from an apply-completion
   result, pushing an undo snapshot first (pi: pushUndoSnapshot + apply)."
  [editor result]
  (push-undo-state editor)
  (reset! (:redo-stack editor) [])
  (reset! (:last-action editor) nil)
  (reset! (:state-atom editor)
          (map->EditorState {:lines (:lines result)
                             :cursor-line (:cursor-line result)
                             :cursor-col (:cursor-col result)})))

(defn- apply-selected-completion!
  "Apply the currently selected dropdown item to the editor (pi: Tab/Enter
   in autocomplete mode)."
  [editor]
  (when-let [sl @(:autocomplete-list editor)]
    (when-let [item (select-list/select-list-get-selected sl)]
      (when-let [ap @(:autocomplete-provider editor)]
        (let [state @(:state-atom editor)
              result (ac/apply-completion ap (:lines state) (:cursor-line state)
                                          (:cursor-col state) item
                                          @(:autocomplete-prefix editor))]
          (set-editor-state! editor result)
          (cancel-autocomplete editor)
          (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))))

(defn- request-autocomplete
  "Query the provider and show/update the dropdown (sync port of pi's
   requestAutocomplete/startAutocompleteRequest). force? mirrors Tab file
   completion; explicit-tab? + force? + single match applies immediately."
  [editor {:keys [force explicit-tab]}]
  (let [ap @(:autocomplete-provider editor)]
    (when ap
      (let [state @(:state-atom editor)
            lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
        (if (and force (not (ac/should-trigger-file-completion ap lines cl cc)))
          nil
          (let [suggestions (ac/get-suggestions ap lines cl cc {:force (boolean force)})]
            (if (or (nil? suggestions) (empty? (:items suggestions)))
              (cancel-autocomplete editor)
              (if (and force explicit-tab (= 1 (count (:items suggestions))))
                (let [state2 @(:state-atom editor)
                      result (ac/apply-completion ap (:lines state2) (:cursor-line state2)
                                                  (:cursor-col state2)
                                                  (first (:items suggestions))
                                                  (:prefix suggestions))]
                  (set-editor-state! editor result)
                  (cancel-autocomplete editor)
                  (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))
                (apply-autocomplete-suggestions editor suggestions
                                                (if force :force :regular))))))))))

(defn- refresh-autocomplete
  "Keep an open dropdown in sync after cursor movement (pi:
   updateAutocomplete on cursor moves)."
  [editor]
  (when @(:autocomplete-state editor)
    (request-autocomplete editor {:force (= @(:autocomplete-state editor) :force)
                                  :explicit-tab false})))

(defn- retrigger-autocomplete
  "After backspace/delete: refresh the dropdown if open, otherwise re-open
   it when the text before the cursor is in a completable context (pi:
   updateAutocomplete / tryTriggerAutocomplete)."
  [editor]
  (if @(:autocomplete-state editor)
    (refresh-autocomplete editor)
    (let [state @(:state-atom editor)
          line (or (nth (:lines state) (:cursor-line state)) "")
          before (subs line 0 (:cursor-col state))]
      (when (or (is-in-slash-command-context? editor)
                (re-find (trigger-pattern editor) before))
        (request-autocomplete editor {:force false :explicit-tab false})))))

(defn- maybe-trigger-autocomplete
  "After a character insert, auto-trigger or refresh the dropdown (pi:
   tryTriggerAutocomplete / updateAutocomplete)."
  [editor char]
  (let [state @(:state-atom editor)
        lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)
        line (or (nth lines cl) "")
        before (subs line 0 cc)]
    (if @(:autocomplete-state editor)
      (request-autocomplete editor {:force (= @(:autocomplete-state editor) :force)
                                    :explicit-tab false})
      (cond
        (and (= char "/") (is-at-start-of-message? editor))
        (request-autocomplete editor {:force false :explicit-tab false})

        (contains? (:chars (trigger-spec editor)) char)
        (let [char-before (when (>= (count before) 2)
                            (subs before (- (count before) 2) (dec (count before))))]
          (when (or (= (count before) 1)
                    (and char-before (re-find #"[\s\t]" char-before)))
            (request-autocomplete editor {:force false :explicit-tab false})))

        (re-find #"[a-zA-Z0-9.\-_]" char)
        (when (or (is-in-slash-command-context? editor)
                  (re-find (trigger-pattern editor) before))
          (request-autocomplete editor {:force false :explicit-tab false}))))))

(declare insert-character)

(defn- handle-tab [editor]
  (if @(:autocomplete-state editor)
    ;; Dropdown visible — apply the selected item
    (do (apply-selected-completion! editor) nil)
    (if-let [ap @(:autocomplete-provider editor)]
      (let [state @(:state-atom editor)
            lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)
            line (or (nth lines cl) "")
            before (subs line 0 cc)]
        (if (and (is-in-slash-command-context? editor)
                 (not (clojure.string/includes? (clojure.string/triml before) " ")))
          ;; Slash command name completion
          (do (request-autocomplete editor {:force false :explicit-tab true}) nil)
          ;; File completion (provider may decline)
          (if (ac/should-trigger-file-completion ap lines cl cc)
            (do (request-autocomplete editor {:force true :explicit-tab true}) nil)
            nil)))
      ;; No provider — legacy 4-space indent
      (insert-character editor "    "))))

;; ─── Line editing actions

(defn- insert-character
  "Insert a character. With :skip-undo-coalescing true (pi:
   skipUndoCoalescing) the undo stack and last-action are left untouched,
   for programmatic inserts that manage their own snapshot."
  [editor char & {:keys [skip-undo-coalescing]}]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when-not skip-undo-coalescing
      (when (or (re-find #"^\s" char) (not= @(:last-action editor) :type-word))
        (push-undo-state editor))
      (reset! (:last-action editor) :type-word))
    (reset! (:redo-stack editor) [])
    (swap! (:state-atom editor) assoc
           :lines (assoc lines cl (str (subs line 0 cc) char (subs line cc)))
           :cursor-col (+ cc (count char)))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))
    (when-not skip-undo-coalescing
      (maybe-trigger-autocomplete editor char))))

(defn- handle-backspace [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (if (> cc 0)
      (let [line (or (nth lines cl) "") glen (segment-left editor line cc)]
        (swap! (:state-atom editor) assoc
               :lines (assoc lines cl (str (subs line 0 glen) (subs line cc)))
               :cursor-col glen))
      (when (> cl 0)
        (let [prev-line (or (nth lines (dec cl)) "") cur-line (or (nth lines cl) "")]
          (swap! (:state-atom editor) assoc
                 :lines (vec (concat (subvec lines 0 (dec cl))
                                     [(str prev-line cur-line)] (subvec lines (inc cl))))
                 :cursor-line (dec cl) :cursor-col (count prev-line)))))
    (after-destructive-edit! editor)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))
    (retrigger-autocomplete editor)))

(defn- handle-forward-delete [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (if (< cc (count line))
      (let [nxt (segment-right editor line cc)]
        (swap! (:state-atom editor) assoc
               :lines (assoc lines cl (str (subs line 0 cc) (subs line nxt)))))
      (when (< cl (dec (count lines)))
        (let [next-line (or (nth lines (inc cl)) "")]
          (swap! (:state-atom editor) assoc
                 :lines (vec (concat (subvec lines 0 (inc cl))
                                     [(str line next-line)] (subvec lines (+ cl 2))))))))
    (after-destructive-edit! editor)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))
    (retrigger-autocomplete editor)))

(defn- add-new-line [editor]
  (cancel-autocomplete editor)
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (swap! (:state-atom editor) assoc
           :lines (vec (concat (subvec lines 0 cl) [(subs line 0 cc)] [(subs line cc)]
                               (subvec lines (inc cl))))
           :cursor-line (inc cl) :cursor-col 0)
    (reset! (:preferred-col-atom editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-kill-to-line-start [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when (pos? cc)
      (push-undo-state editor)
      (let [deleted (subs line 0 cc)]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend true
                             :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc
               :lines (assoc lines cl (subs line cc)) :cursor-col 0)
        (after-destructive-edit! editor))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-kill-to-line-end [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when (< cc (count line))
      (push-undo-state editor)
      (let [deleted (subs line cc)]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend false
                             :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc
               :lines (assoc lines cl (subs line 0 cc)))
        (after-destructive-edit! editor))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-delete-word-backward [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
    (when (or (pos? cc) (pos? cl))
      (push-undo-state editor)
      (let [[new-line new-col] (word-boundary-left lines cl cc)
            deleted (if (= new-line cl)
                      (subs (nth lines cl) new-col cc)
                      (str (subs (nth lines new-line) new-col) "\n"
                           (clojure.string/join "\n" (subvec lines (inc new-line) cl)) "\n"
                           (subs (nth lines cl) 0 cc)))]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend true
                             :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (let [new-lines (if (= new-line cl)
                          (assoc lines cl (str (subs (nth lines cl) 0 new-col) (subs (nth lines cl) cc)))
                          (vec (concat (subvec lines 0 new-line)
                                       [(str (subs (nth lines new-line) 0 new-col) (subs (nth lines cl) cc))]
                                       (subvec lines (inc cl)))))]
          (swap! (:state-atom editor) assoc :lines new-lines :cursor-line new-line :cursor-col new-col))
        (after-destructive-edit! editor))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-delete-word-forward [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)
        [tline tcol] (word-boundary-right lines cl cc)]
    (when (or (not= tline cl) (not= tcol cc))
      (push-undo-state editor)
      (let [deleted (if (= tline cl)
                      (subs (nth lines cl) cc tcol)
                      (str (subs (nth lines cl) cc) "\n"
                           (clojure.string/join "\n" (subvec lines (inc cl) tline)) "\n"
                           (subs (nth lines tline) 0 tcol)))]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend false
                             :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (let [new-lines (if (= tline cl)
                          (assoc lines cl (str (subs (nth lines cl) 0 cc) (subs (nth lines cl) tcol)))
                          (vec (concat (subvec lines 0 cl)
                                       [(str (subs (nth lines cl) 0 cc) (subs (nth lines tline) tcol))]
                                       (subvec lines (inc tline)))))]
          (swap! (:state-atom editor) assoc :lines new-lines)))
      (after-destructive-edit! editor)
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-kill-line [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)]
    (when (seq lines)
      (push-undo-state editor)
      (let [deleted (nth lines cl "")
            new-lines (if (= 1 (count lines)) [""]
                          (vec (concat (subvec lines 0 cl) (subvec lines (inc cl)))))
            new-cl (min cl (dec (count new-lines)))]
        (edit/kill-ring-push (:kill-ring editor) (str deleted "\n") :prepend false
                             :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc :lines new-lines :cursor-line new-cl :cursor-col 0)
        (after-destructive-edit! editor))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

;; ─── Yank helpers

(defn- handle-yank [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) text (edit/kill-ring-peek (:kill-ring editor))]
    (when text
      (push-undo-state editor)
      (reset! (:redo-stack editor) [])
      (if (clojure.string/includes? text "\n")
        (let [parts (clojure.string/split text #"\n" -1)
              first-part (first parts) rest-parts (rest parts)
              line (nth lines cl "")
              new-line (str (subs line 0 cc) first-part)
              remaining (subs line cc)
              new-lines (vec (concat (subvec lines 0 cl) [new-line] rest-parts
                                     (when (seq remaining) [remaining])
                                     (subvec lines (inc cl))))]
          (swap! (:state-atom editor) assoc
                 :lines new-lines :cursor-line (+ cl (count rest-parts))
                 :cursor-col (count (or (last rest-parts) ""))))
        (let [line (nth lines cl "")
              new-val (str (subs line 0 cc) text (subs line cc))]
          (swap! (:state-atom editor) assoc
                 :lines (assoc lines cl new-val) :cursor-col (+ cc (count text)))))
      (reset! (:last-action editor) :yank)
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-yank-pop [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) kr (:kill-ring editor)]
    (when (and (= @(:last-action editor) :yank) (> (edit/kill-ring-length kr) 1))
      (push-undo-state editor)
      (reset! (:redo-stack editor) [])
      (let [_ (edit/kill-ring-rotate kr)
            new-text (or (edit/kill-ring-peek kr) "")]
        (if (clojure.string/includes? new-text "\n")
          (let [parts (clojure.string/split new-text #"\n" -1)
                first-part (first parts) rest-parts (rest parts)
                line (nth lines cl "")
                new-line (str (subs line 0 cc) first-part)
                remaining (subs line cc)
                new-lines (vec (concat (subvec lines 0 cl) [new-line] rest-parts
                                       (when (seq remaining) [remaining])
                                       (subvec lines (inc cl))))]
            (swap! (:state-atom editor) assoc
                   :lines new-lines :cursor-line (+ cl (count rest-parts))
                   :cursor-col (count (or (last rest-parts) ""))))
          (let [line (nth lines cl "")
                new-val (str (subs line 0 cc) new-text (subs line cc))]
            (swap! (:state-atom editor) assoc
                   :lines (assoc lines cl new-val) :cursor-col (+ cc (count new-text)))))
        (reset! (:last-action editor) :yank)
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

;; ─── Paste markers

(defn- exit-history-browsing!
  "Leave history browsing without touching text (pi exitHistoryBrowsing)."
  [editor]
  (reset! (:history-idx editor) -1)
  (reset! (:history-draft editor) nil))

(defn- handle-paste [editor text]
  (cancel-autocomplete editor)
  (exit-history-browsing! editor)
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state)
        prev-char (when (pos? cc) (subs (nth lines cl "") (dec cc) cc))
        text (edit/smart-path-spacing (edit/filter-paste-printable (edit/normalize-paste-text text)) prev-char)
        paste-lines (clojure.string/split text #"\n" -1)
        line-count (count paste-lines)
        total-chars (count text)]
    (when (seq text)
      (push-undo-state editor)
      (reset! (:last-action editor) nil)
      (reset! (:redo-stack editor) [])
      (if (and (<= line-count 10) (<= total-chars 1000))
        (let [first-line (first paste-lines) rest-lines (rest paste-lines)
              cur-line (nth lines cl "")
              new-cur-line (str (subs cur-line 0 cc) first-line (subs cur-line cc))
              remaining (subs cur-line cc)
              new-lines (if (empty? rest-lines)
                          (assoc lines cl new-cur-line)
                          (vec (concat (subvec lines 0 cl)
                                       [(str (subs cur-line 0 cc) first-line)]
                                       (mapv (fn [l] l) rest-lines)
                                       (when (seq remaining) [remaining])
                                       (subvec lines (inc cl)))))]
          (if (empty? rest-lines)
            ;; Single-line paste — cursor lands after the pasted text
            (swap! (:state-atom editor) assoc
                   :lines new-lines :cursor-col (+ cc (count first-line)))
            (swap! (:state-atom editor) assoc
                   :lines new-lines :cursor-line (+ cl (count rest-lines))
                   :cursor-col (count (last rest-lines)))))
        (let [n (swap! (:paste-counter editor) inc)
              marker (if (> line-count 10)
                       (str "[paste #" n " +" line-count " lines — ctrl+o to expand]")
                       (str "[paste #" n " " total-chars " chars — ctrl+o to expand]"))
              cur-line (nth lines cl "")
              new-cur-line (str (subs cur-line 0 cc) marker (subs cur-line cc))]
          (swap! (:paste-store editor) assoc n text)
          (swap! (:state-atom editor) assoc
                 :lines (assoc lines cl new-cur-line) :cursor-col (+ cc (count marker)))))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

;; ─── Character jump mode

(defn- enter-jump-mode [editor dir]
  (reset! (:jump-mode editor) {:dir dir :char nil}))

(defn- handle-jump-character [editor char]
  (let [jm @(:jump-mode editor) dir (:dir jm)
        state @(:state-atom editor) lines (:lines state)
        cl (:cursor-line state) cc (:cursor-col state)]
    (reset! (:jump-mode editor) nil)
    (when (and dir char)
      (let [result (if (= dir :forward)
                     (let [line (nth lines cl "") idx (clojure.string/index-of line (str char) cc)]
                       (if (>= idx 0) [cl idx]
                           (loop [i (inc cl)]
                             (when (< i (count lines))
                               (let [li (nth lines i "") idx (clojure.string/index-of li (str char))]
                                 (if (>= idx 0) [i idx] (recur (inc i))))))))
                     (let [line (nth lines cl "")
                           idx (if (<= cc 0) -1 (clojure.string/last-index-of line (str char) (dec cc)))]
                       (if (>= idx 0) [cl idx]
                           (loop [i (dec cl)]
                             (when (>= i 0)
                               (let [li (nth lines i "") idx (clojure.string/last-index-of li (str char))]
                                 (if (>= idx 0) [i idx] (recur (dec i)))))))))]
        (when result
          (swap! (:state-atom editor) assoc
                 :cursor-line (first result) :cursor-col (second result))
          (reset! (:preferred-col-atom editor) nil))))))

;; ─── Cursor movement

(defn- move-cursor-word-left [editor]
  (let [s @(:state-atom editor)
        [nl nc] (word-boundary-left (:lines s) (:cursor-line s) (:cursor-col s))]
    (swap! (:state-atom editor) assoc :cursor-line nl :cursor-col nc)
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)))

(defn- move-cursor-word-right [editor]
  (let [s @(:state-atom editor)
        [nl nc] (word-boundary-right (:lines s) (:cursor-line s) (:cursor-col s))]
    (swap! (:state-atom editor) assoc :cursor-line nl :cursor-col nc)
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)))

;; ─── History navigation

(declare history-restore-draft!)

(defn- history-set-state!
  "Replace the editor state with TEXT's lines and cursor, resetting the
   per-edit state (undo/redo stacks, preferred column, jump mode) so
   history entries load clean (pi: setTextInternal)."
  [editor text]
  (reset! (:state-atom editor) (make-editor-state text))
  (reset! (:scroll-offset-atom editor) 0)
  (reset! (:preferred-col-atom editor) nil)
  (reset! (:undo-stack editor) [])
  (reset! (:redo-stack editor) [])
  (reset! (:last-action editor) nil)
  (reset! (:jump-mode editor) nil))

(defn- current-visual-line-idx
  "Index of the visual line the cursor is on (0-based), considering word wrap
   (pi: isOnFirstVisualLine / isOnLastVisualLine)."
  [editor]
  (let [state @(:state-atom editor)
        width @(:last-width-atom editor)
        visual-lines (build-visual-line-map (:lines state) width (valid-paste-ids editor))]
    (find-current-visual-line visual-lines (:cursor-line state) (:cursor-col state))))

(defn- history-load!
  "Load the history entry at IDX into the editor with the cursor at the start
   (Up) or end (Down) — pi: navigateHistory setTextInternal with
   cursorPlacement 'start' | 'end'. The placement matters: Up leaves the
   cursor at (0,0) so the next Up keeps navigating history; Down leaves it at
   the end so the next Down keeps navigating."
  [editor idx cursor-start?]
  (history-set-state! editor (nth @(:history editor) idx))
  ;; history-set-state! clears the undo stack, so the draft goes back on top
  ;; of it — Ctrl+Z while browsing returns to the draft
  (when-let [draft @(:history-draft editor)]
    (undo-push (:undo-stack editor) draft))
  (let [lines (:lines @(:state-atom editor))]
    (swap! (:state-atom editor) assoc
           :cursor-line (if cursor-start?
                          0
                          (max 0 (dec (count lines))))
           :cursor-col (if cursor-start?
                         0
                         (count (last lines))))))

(defn- history-backward [editor]
  (let [h @(:history editor)
        n (count h)
        idx @(:history-idx editor)]
    (when (pos? n)
      (let [entering? (neg? idx)
            new-idx (if entering? (dec n) (max -1 (dec idx)))]
        (when entering?
          ;; Entering history mode — capture the current editor state so
          ;; Up then Down returns to it exactly (pi: historyDraft)
          (reset! (:history-draft editor) (snapshot-state editor)))
        (reset! (:history-idx editor) new-idx)
        (if (neg? new-idx)
          ;; Browsed past the first entry — back to the draft
          (history-restore-draft! editor)
          (history-load! editor new-idx true))
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

(defn- history-forward [editor]
  (let [h @(:history editor)
        n (count h)
        idx @(:history-idx editor)]
    (when (and (pos? n) (not (neg? idx)))
      (let [new-idx (if (>= idx (dec n)) -1 (inc idx))]
        (reset! (:history-idx editor) new-idx)
        (if (neg? new-idx)
          ;; Past the newest entry — restore the draft
          (history-restore-draft! editor)
          (history-load! editor new-idx false))
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

(defn- history-restore-draft!
  "Restore the editor state captured when history browsing began (pi:
   historyDraft), or clear to empty if no draft was captured."
  [editor]
  (if-let [draft @(:history-draft editor)]
    (do (reset! (:state-atom editor) (map->EditorState draft))
        (reset! (:paste-store editor) (:paste-store draft))
        (reset! (:scroll-offset-atom editor) 0)
        (reset! (:preferred-col-atom editor) nil)
        (reset! (:last-action editor) nil)
        (reset! (:jump-mode editor) nil)
        (reset! (:history-draft editor) nil))
    (history-set-state! editor "")))

(defn editor-push-history! [editor text]
  (when (and (seq text) (not= text (peek @(:history editor))))
    ;; pi: addToHistory caps history at 100 entries (newest kept)
    (swap! (:history editor)
           (fn [h]
             (let [h (conj h text)]
               (if (> (count h) 100) (subvec h 1) h))))
    (reset! (:history-idx editor) -1)))

;; ─── Height helper
;; Dynamic height (pi): 30% of terminal rows, min 5. Used when a
;; :terminal-rows source is provided; otherwise :height is the fallback.

(defn- get-editor-height [editor]
  (if-let [rows-fn @(:terminal-rows-atom editor)]
    (let [rows (rows-fn)]
      (if (and rows (pos? rows))
        (max 5 (quot (* rows 3) 10))
        (or @(:height-atom editor) 12)))
    (or @(:height-atom editor) 12)))

;; ─── Internal helpers (cursor movement)
(defn- move-cursor-horizontal [editor dir]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (reset! (:preferred-col-atom editor) nil)
    (if (neg? dir)
      (if (> cc 0)
        (swap! (:state-atom editor) assoc :cursor-col (segment-left editor line cc))
        (when (> cl 0)
          (let [prev-line (or (nth lines (dec cl)) "")]
            (swap! (:state-atom editor) assoc
                   :cursor-line (dec cl)
                   :cursor-col (count prev-line)))))
      (if (< cc (count line))
        (swap! (:state-atom editor) assoc :cursor-col (segment-right editor line cc))
        (when (< cl (dec (count lines)))
          (swap! (:state-atom editor) assoc
                 :cursor-line (inc cl)
                 :cursor-col 0))))))

(defn- move-cursor-vertical [editor dir]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        width @(:last-width-atom editor)
        visual-lines (build-visual-line-map lines width (valid-paste-ids editor))
        current-idx (find-current-visual-line visual-lines cl cc)
        target-idx (+ current-idx dir)]
    (when (and (>= target-idx 0) (< target-idx (count visual-lines)))
      (let [target-vl (nth visual-lines target-idx)
            preferred @(:preferred-col-atom editor)
            target-col (if preferred
                         (min preferred (:length target-vl))
                         (do (when (pos? dir)
                               (reset! (:preferred-col-atom editor) cc))
                             (min cc (:length target-vl))))]
        (swap! (:state-atom editor) assoc
               :cursor-line (:logical-line target-vl)
               :cursor-col (+ (:start-col target-vl) target-col))))))

(defn- page-scroll [editor dir]
  (let [page-size (max 1 (dec (get-editor-height editor)))]
    (dotimes [_ page-size]
      (move-cursor-vertical editor dir))))

;; ─── App actions (pi: CustomEditor.handleInput) ───────────────────────────
;; Registered action handlers (via editor-set-on-action!) are matched against
;; the keybindings manager (the app-level KeybindingsManager in kmet.core) and
;; take precedence over editor-internal key handling. Priority handlers
;; (editor-set-priority-action!, used for extension shortcuts — pi:
;; onExtensionShortcut) are checked BEFORE every app action, so extensions
;; can bind keys the app owns.

(defn- keybindings-manager
  "The manager this editor resolves bindings through: an injected one, else
   the global singleton (pi: CustomEditor's KeybindingsManager)."
  [editor]
  (or @(:keybindings editor) (kb/get-global-keybindings)))

(defn- kb-match?
  "Does DATA match keybinding ID through this editor's manager (pi:
   KeybindingsManager.matches)? Editor-internal keys and app actions both
   resolve here, so user overrides apply to both."
  [editor data keybinding-id]
  (kb/matches-key (keybindings-manager editor) data keybinding-id))

(defn- dispatch-priority-action!
  "Try to dispatch data to a priority action handler (extension shortcuts).
   Returns true if a handler matched and ran. Handlers are checked in
   registration order; the first matching key wins (pi: extension shortcuts
   run before builtin keybindings; between extensions, last registration
   wins per key, first registration wins per keypress)."
  [editor data]
  (let [kmgr (keybindings-manager editor)
        handlers @(:priority-action-handlers editor)]
    (loop [ids (keys handlers)]
      (if-let [action-id (first ids)]
        (if (kb/matches-key kmgr data action-id)
          (do ((get handlers action-id)) true)
          (recur (next ids)))
        false))))

(defn- dispatch-app-action!
  "Try to dispatch data to a registered app action handler. Returns true if a
   handler matched and ran, false otherwise. Mirrors pi's CustomEditor:
   - app.interrupt (escape) only fires when the autocomplete dropdown is not
     showing (the dropdown intercepts escape first)
   - app.exit (ctrl+d) only fires when the editor is empty (otherwise the key
     deletes the char forward)
   - all other registered actions are matched via the keybindings manager"
  [editor data]
  (let [kmgr (keybindings-manager editor)
        handlers @(:action-handlers editor)
        text (clojure.string/join "\n" (:lines @(:state-atom editor)))]
    (cond
      ;; Extension shortcuts first (pi: onExtensionShortcut runs before
      ;; every builtin binding, escape included)
      (dispatch-priority-action! editor data)
      true

      (and (get handlers "app.interrupt")
           (kb/matches-key kmgr data "app.interrupt")
           (not @(:autocomplete-state editor)))
      (do ((get handlers "app.interrupt")) true)

      (and (get handlers "app.exit")
           (kb/matches-key kmgr data "app.exit")
           (empty? text))
      (do ((get handlers "app.exit")) true)

      :else
      (loop [ids (seq (remove #(contains? #{"app.interrupt" "app.exit"} %)
                              (keys handlers)))]
        (if-let [action-id (first ids)]
          (if (kb/matches-key kmgr data action-id)
            (do ((get handlers action-id)) true)
            (recur (next ids)))
          false)))))

;; ─── Top border (pi: Editor.renderTopBorder / CustomEditor override) ───────
;; The top border is one line, optionally delegated to an installed
;; top-border fn — the app's status-in-border hook (pi:
;; CustomEditor.renderTopBorder, the embedWorkingStatus opt-in). The fn
;; receives {:width :hidden-line-count :rule :border-fn} and returns the
;; line, or nil to fall back to the default (the plain rule, or the
;; centered scroll label when the view is scrolled).

(defn- rule-run
  "N copies of the already-styled rule glyph BDR."
  [bdr n]
  (apply str (repeat (max 0 n) bdr)))

(defn- scroll-border-line
  "The rule line carrying a centered ` ↑ N more ` (or ↓) label for HIDDEN
   lines, or a truncated prefix when the centered label does not fit (pi:
   createScrollBorder). GLYPH is the unstyled rule char and STYLE its
   border color fn."
  [glyph style direction hidden width]
  (let [bdr (style glyph)
        label (str " " direction " " hidden " more ")
        label-w (u/visible-width label)
        width (max 0 width)]
    (if (<= (+ label-w 2) width)
      (let [left (quot (- width label-w) 2)]
        (str (rule-run bdr left) (style label) (rule-run bdr (- width left label-w))))
      (let [indicator (str glyph glyph glyph " " direction " " hidden " more ")
            prefix-w (u/visible-width indicator)]
        (if (<= prefix-w width)
          (str (style indicator) (rule-run bdr (- width prefix-w)))
          ;; not even the prefix fits: keep the arrow head, ellipsize the rest
          (let [ellipsis (subs "..." 0 (max 0 (min width 3)))
                head-w (max 0 (- width (count ellipsis)))]
            (style (str (subs indicator 0 (min (count indicator) head-w)) ellipsis))))))))

(defn- default-top-border
  "The top border when no top-border fn is installed or it declined (pi:
   Editor.renderTopBorder)."
  [glyph style hidden-line-count width]
  (if (pos? hidden-line-count)
    (scroll-border-line glyph style "↑" hidden-line-count width)
    (rule-run (style glyph) width)))

;; ─── Editor component ──────────────────────────────────────────────────────

(defcomponent Editor nil [state-atom scroll-offset-atom preferred-col-atom
                          last-width-atom focused? on-submit on-change
                          disable-submit padding-x border-fn height-atom
                          border-atom
                          undo-stack redo-stack kill-ring last-action
                          paste-buffer paste-state paste-store paste-counter
                          jump-mode
                          history history-idx history-draft
                          terminal-rows-atom
                          autocomplete-provider autocomplete-state
                          autocomplete-list autocomplete-prefix
                          autocomplete-max-visible autocomplete-theme
                          action-handlers keybindings
                          priority-action-handlers top-border-fn-atom]

  (render [this width]
    (let [state @state-atom
          padding-x @padding-x
          max-padding (max 0 (quot (dec width) 2))
          padding-x (min padding-x max-padding)
          content-width (max 1 (- width (* padding-x 2)))
          layout-width (max 1 (- content-width (if (zero? padding-x) 1 0)))
          _ (reset! last-width-atom layout-width)
          lines (:lines state)
          cursor-line (:cursor-line state)
          cursor-col (:cursor-col state)
          visual-lines (build-visual-line-map lines layout-width (valid-paste-ids this))
          max-visible (get-editor-height this)
          cursor-visual-idx (find-current-visual-line visual-lines cursor-line cursor-col)
          scroll-offset @scroll-offset-atom
          new-offset (cond
                       (< cursor-visual-idx scroll-offset) cursor-visual-idx
                       (>= cursor-visual-idx (+ scroll-offset max-visible))
                       (- cursor-visual-idx max-visible -1)
                       :else scroll-offset)
          max-offset (max 0 (- (count visual-lines) max-visible))
          scroll-offset (max 0 (min new-offset max-offset))]
      (reset! scroll-offset-atom scroll-offset)
      (let [visible (subvec visual-lines scroll-offset
                            (min (+ scroll-offset max-visible) (count visual-lines)))
            ;; the rule glyph comes from the border set (tui.md §2.8);
            ;; :none has no rule glyph, so fall back to the default bar
            rule (or (border/rule @border-atom) "─")
            style (or @border-fn identity)
            bdr (style rule)
            left-pad (apply str (repeat padding-x \space))
            right-pad left-pad
            result (volatile! [])]
        ;; Top border — an installed top-border fn renders it (the app's
        ;; status-in-border hook); when absent or declining, the default
        ;; rule (with the scroll label when scrolled).
        (vswap! result conj
                (or (when-some [top-fn @top-border-fn-atom]
                      (top-fn {:width width
                               :hidden-line-count scroll-offset
                               :rule rule
                               :border-fn @border-fn}))
                    (default-top-border rule style scroll-offset width)))
        ;; Render visible lines
        (doseq [[vi vl] (map-indexed vector visible)]
          (let [vl-has-cursor (= (+ vi scroll-offset) cursor-visual-idx)
                vl-cursor-col (- cursor-col (:start-col vl))
                display-text (:text vl)
                line-width (u/visible-width display-text)]
            (if (and vl-has-cursor (>= vl-cursor-col 0) (<= vl-cursor-col (count display-text)))
              (let [before (subs display-text 0 vl-cursor-col)
                    at-cursor (edit/grapheme-at display-text vl-cursor-col)
                    after (subs display-text
                                (min (count display-text) (+ vl-cursor-col (count at-cursor))))
                    cursor-at-end? (empty? after)
                    display-with-cursor (str before
                                             (when @focused? u/CURSOR-MARKER)
                                             "\u001b[7m" (if cursor-at-end? " " at-cursor) "\u001b[0m"
                                             after)
                    effective-width (if cursor-at-end?
                                      (+ line-width 1)
                                      line-width)
                    cursor-in-padding? (and cursor-at-end?
                                            (> effective-width content-width)
                                            (pos? padding-x))
                    p (apply str (repeat (max 0 (- content-width effective-width)) \space))
                    rp (if cursor-in-padding? (subs right-pad 1) right-pad)]
                (vswap! result conj (str left-pad display-with-cursor p rp)))
              (let [p (apply str (repeat (max 0 (- content-width line-width)) \space))]
                (vswap! result conj (str left-pad display-text p right-pad))))))
        ;; Bottom border
        (let [remaining (- (count visual-lines) (+ scroll-offset (count visible)))]
          (vswap! result conj
                  (if (pos? remaining)
                    (scroll-border-line rule style "↓" remaining width)
                    (rule-run bdr width))))
        ;; Autocomplete dropdown below the border (pi: SelectList in render)
        (when (and @(:autocomplete-state this) @(:autocomplete-list this))
          (doseq [line (protocols/render @(:autocomplete-list this) content-width)]
            (let [lw (u/visible-width line)
                  line-padding (apply str (repeat (max 0 (- content-width lw)) \space))]
              (vswap! result conj
                      (str left-pad line line-padding right-pad)))))
        @result)))

  (handle-input [this data]
    (if (and @jump-mode (:dir @jump-mode) (nil? (:char @jump-mode)))
      (let [char (first data)]
        (when (and char (not= (int char) 27))
          (handle-jump-character this (str char)))
        nil)
      (let [state @state-atom
            lines (:lines state)]
        (cond
          ;; Paste handling takes precedence over key dispatch so streamed
          ;; bracketed-paste content (including \r, escape, and CSI-u encoded
          ;; control bytes) is buffered literally instead of triggering actions.
          ;; A nested START while already buffering is literal paste content
          ;; (pi treats everything between the first START and first END as
          ;; data): fall through to the buffering leg instead of resetting.
          ;; pi strips only the first START (String.replace, string pattern).
          (and (clojure.string/includes? data "\u001b[200~")
               (not= @paste-state :buffering))
          (do (reset! paste-state :buffering)
              (reset! paste-buffer "")
              (let [remaining (clojure.string/replace-first data "\u001b[200~" "")]
                (when (seq remaining)
                  (protocols/handle-input this remaining)))
              nil)

          ;; Trailing input after END (pi: remaining + recursive handleInput)
          ;; re-enters dispatch instead of dropping.
          (= @paste-state :buffering)
          (do (swap! paste-buffer str data)
              (let [buf @paste-buffer
                    end-idx (clojure.string/index-of buf "\u001b[201~")]
                (when (and end-idx (>= end-idx 0))
                  (let [paste-text (subs buf 0 end-idx)
                        remaining (subs buf (+ end-idx (count "\u001b[201~")))]
                    (handle-paste this (edit/decode-csi-u paste-text))
                    ;; Only leave buffering once the end marker arrives
                    (reset! paste-state :idle)
                    (reset! paste-buffer "")
                    (when (seq remaining)
                      (protocols/handle-input this remaining)))))
              nil)

          ;; Autocomplete dropdown — intercept only dropdown keys; other
          ;; input falls through to normal editing (which refreshes it)
          (and @(:autocomplete-state this) @(:autocomplete-list this)
               (or (kb-match? this data "tui.select.cancel")
                   (kb-match? this data "tui.select.up")
                   (kb-match? this data "tui.select.down")
                   (kb-match? this data "tui.input.tab")
                   (and (kb-match? this data "tui.select.confirm") (not @disable-submit))))
          (let [sl @(:autocomplete-list this)
                prefix @(:autocomplete-prefix this)]
            (cond
              (kb-match? this data "tui.select.cancel")
              (do (cancel-autocomplete this) nil)

              (or (kb-match? this data "tui.select.up")
                  (kb-match? this data "tui.select.down"))
              (do (protocols/handle-input sl data) nil)

              (kb-match? this data "tui.input.tab")
              (do (apply-selected-completion! this) nil)

              (and (kb-match? this data "tui.select.confirm") (not @disable-submit))
              ;; pi tui.select.confirm with the dropdown open: apply the
              ;; selected completion; only a command-name completion (prefix
              ;; starts with "/") falls through to submit — an argument
              ;; completion (e.g. "/login git" → "/login github-copilot")
              ;; replaces the typed text and waits for a second Enter.
              (do (if (str/starts-with? prefix "/")
                    (do (apply-selected-completion! this)
                        (when-let [cb @on-submit]
                          (cb (clojure.string/join "\n" (:lines @(:state-atom this))))))
                    (apply-selected-completion! this))
                  nil)))

          ;; pi (custom-editor) order: interrupt and exit are tried BEFORE the
          ;; explicit history bindings, so a user binding history to escape/
          ;; ctrl+d cannot swallow cancel/exit. `and` short-circuits: when
          ;; neither id matches, dispatch-app-action! is not called here and
          ;; the general leg below runs the remaining actions exactly once.
          (and (or (kb-match? this data "app.interrupt")
                   (kb-match? this data "app.exit"))
               (dispatch-app-action! this data))
          nil

          ;; pi (custom-editor): explicit history bindings take precedence
          ;; over the remaining app actions while the editor is focused —
          ;; the escape hatch that lets a user bind ctrl+p to history even
          ;; though it cycles models by default. Both default to no keys, so
          ;; this only fires for user-bound chords.
          (kb-match? this data "tui.editor.historyPrevious")
          (do (history-backward this) nil)

          (kb-match? this data "tui.editor.historyNext")
          (do (history-forward this) nil)

          ;; App actions (pi: CustomEditor.handleInput) — registered action
          ;; handlers take precedence over editor-internal key handling. Paste
          ;; buffering and the autocomplete dropdown are intercepted above, so
          ;; they keep priority.
          (dispatch-app-action! this data)
          nil

          (and (kb-match? this data "tui.input.submit") (not @disable-submit))
          (do (when-let [cb @on-submit] (cb (clojure.string/join "\n" lines))) nil)

          (kb-match? this data "tui.input.newLine")
          (do (add-new-line this) nil)

          ;; pi base editor: Ctrl+C is the copy binding and is handed back
          ;; to the parent (no editing) — after app actions, so app.clear
          ;; still owns it in the app
          (kb-match? this data "tui.input.copy")
          nil

          ;; kmet: the base editor's standalone cancel. pi's Editor has no
          ;; escape leg (its CustomEditor owns app.interrupt) — resolving
          ;; through tui.select.cancel keeps the fallback rebindable and
          ;; matches Input's cancel; in the app an app.interrupt handler
          ;; consumes escape before this leg.
          (kb-match? this data "tui.select.cancel")
          (do (reset! jump-mode nil)
              (when-let [cb @on-submit] (cb nil))
              nil)

          (kb-match? this data "tui.editor.deleteCharBackward")
          (do (handle-backspace this) nil)

          (kb-match? this data "tui.editor.deleteCharForward")
          (do (handle-forward-delete this) nil)

          (kb-match? this data "tui.editor.undo")
          (do (handle-undo this) nil)

          (kb-match? this data "tui.editor.redo")
          (do (handle-redo this) nil)

          (kb-match? this data "tui.input.tab")
          (do (handle-tab this) nil)

          ;; jumpBackward's kmet chord ctrl+shift+] is part of the id's
          ;; default-keys, so a rebind moves both it and pi's ctrl+alt+]
          (kb-match? this data "tui.editor.jumpForward")
          (do (enter-jump-mode this :forward) nil)

          (kb-match? this data "tui.editor.jumpBackward")
          (do (enter-jump-mode this :backward) nil)

          (kb-match? this data "tui.editor.deleteToLineStart")
          (do (handle-kill-to-line-start this) nil)

          (kb-match? this data "tui.editor.deleteToLineEnd")
          (do (handle-kill-to-line-end this) nil)

          ;; ctrl+w keeps kmet's kill-the-line behavior (pi has no line kill;
          ;; deleteWordBackward also claims ctrl+w, so a user rebinding
          ;; killLine away gets the pi chord back)
          (kb-match? this data "tui.editor.killLine")
          (do (handle-kill-line this) nil)

          (or (kb-match? this data "tui.editor.deleteWordBackward")
              (keys/matches-key? data (keys/alt "h")))
          (do (handle-delete-word-backward this) nil)

          (kb-match? this data "tui.editor.deleteWordForward")
          (do (handle-delete-word-forward this) nil)

          (kb-match? this data "tui.editor.yank")
          (do (handle-yank this) nil)

          (kb-match? this data "tui.editor.yankPop")
          (do (handle-yank-pop this) nil)

          (kb-match? this data "tui.editor.cursorUp")
          (do (let [state @state-atom
                    lines (:lines state)
                    cc (:cursor-col state)
                    first-visual? (zero? (current-visual-line-idx this))]
                (cond
                  ;; pi: history on Up when on the first visual line and
                  ;; (editor empty || browsing history || cursor at col 0)
                  (and first-visual?
                       (or (and (= 1 (count lines)) (= "" (first lines)))
                           (not (neg? @(:history-idx this)))
                           (zero? cc)))
                  (history-backward this)
                  ;; Already at the top — jump to the start of the line
                  ;; (pi: moveToLineStart)
                  first-visual?
                  (swap! state-atom assoc :cursor-col 0)
                  :else
                  (move-cursor-vertical this -1)))
              nil)

          (kb-match? this data "tui.editor.cursorDown")
          (do (let [state @state-atom
                    lines (:lines state)
                    cl (:cursor-line state)
                    line (nth lines cl "")
                    last-visual? (= (current-visual-line-idx this)
                                    (dec (count (build-visual-line-map
                                                 lines @(:last-width-atom this)
                                                 (valid-paste-ids this)))))]
                (cond
                  ;; pi: Down navigates history only while browsing, and only
                  ;; from the last visual line
                  (and (not (neg? @(:history-idx this))) last-visual?)
                  (history-forward this)
                  ;; Already at the bottom — jump to the end of the line
                  ;; (pi: moveToLineEnd)
                  last-visual?
                  (swap! state-atom assoc :cursor-col (count line))
                  :else
                  (move-cursor-vertical this 1)))
              nil)

          (keys/matches-key? data (keys/ctrl "p"))
          (do (history-backward this) nil)

          (keys/matches-key? data (keys/ctrl "n"))
          (do (history-forward this) nil)

          (kb-match? this data "tui.editor.cursorLeft")
          (do (move-cursor-horizontal this -1)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.cursorRight")
          (do (move-cursor-horizontal this 1)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.cursorLineStart")
          (do (swap! state-atom assoc :cursor-col 0)
              (reset! preferred-col-atom nil)
              (reset! last-action nil)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.cursorLineEnd")
          (do (let [line (nth (:lines @state-atom) (:cursor-line @state-atom) "")]
                (swap! state-atom assoc :cursor-col (count line))
                (reset! preferred-col-atom nil)
                (reset! last-action nil))
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.cursorWordLeft")
          (do (move-cursor-word-left this)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.cursorWordRight")
          (do (move-cursor-word-right this)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.pageUp")
          (do (page-scroll this -1)
              (refresh-autocomplete this) nil)

          (kb-match? this data "tui.editor.pageDown")
          (do (page-scroll this 1)
              (refresh-autocomplete this) nil)

          :else
          (let [printable (keys/decode-printable-key data)
                has-ctrl? (and (nil? printable)
                               (some #(let [c (int %)]
                                        (or (< c 32) (== c 127)
                                            (and (>= c 128) (<= c 159))))
                                     data))]
            (when-not has-ctrl?
              (insert-character this (or printable data)))))))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-editor
  "Create a new Editor component.
   Options key-value pairs:
     :height  — number of visible lines, fallback when no :terminal-rows (default 12)
     :padding-x — horizontal padding (default 0)
     :border-fn — function to style border chars
     :border — a kmet.tui.border set for the rule above and below the text
               (default :normal; :ascii draws it with -, :hidden keeps the
               rule's cells without ink), resolved here so an unknown style
               throws at construction. The rule is structural — it carries
               the scroll indicators and the editor's height — so :none
               falls back to the default bar rather than removing it
     :keybindings — KeybindingsManager used to match app action handlers
                    (default: the global keybindings manager)
     :terminal-rows — (fn [] int) returning terminal rows for dynamic height
                      (30% of rows, min 5 lines; pi behavior)
     The top border can be taken over later with editor-set-top-border-fn!
     (the app's status-in-border hook)."
  [& {:keys [height padding-x border-fn keybindings terminal-rows border]
      :or {height 12 padding-x 0}}]
  (map->Editor {:state-atom (atom (make-editor-state))
                :scroll-offset-atom (atom 0)
                :preferred-col-atom (atom nil)
                :last-width-atom (atom 80)
                :focused? (atom false)
                :on-submit (atom nil)
                :on-change (atom nil)
                :disable-submit (atom false)
                :padding-x (atom padding-x)
                :border-fn (atom border-fn)
                :border-atom (atom (border/resolve border))
                :height-atom (atom height)
                :undo-stack (atom [])
                :redo-stack (atom [])
                :kill-ring (edit/make-kill-ring)
                :last-action (atom nil)
                :paste-buffer (atom "")
                :paste-state (atom :idle)
                :paste-store (atom {})
                :paste-counter (atom 0)
                :jump-mode (atom nil)
                :history (atom [])
                :history-idx (atom -1)
                :history-draft (atom nil)
                :terminal-rows-atom (atom terminal-rows)
                :autocomplete-provider (atom nil)
                :autocomplete-state (atom nil)
                :autocomplete-list (atom nil)
                :autocomplete-prefix (atom "")
                :autocomplete-max-visible (atom 5)
                :autocomplete-theme (atom select-list/default-theme)
                :action-handlers (atom {})
                :priority-action-handlers (atom {})
                :top-border-fn-atom (atom nil)
                :keybindings (atom keybindings)}))

(defn editor-set-text! [editor text]
  (cancel-autocomplete editor)
  (let [old-text (editor-get-text editor)]
    ;; pi: push an undo snapshot when the content differs so programmatic
    ;; changes (submit-clear, external editor, app.clear) are undoable
    (when (not= old-text text)
      (push-undo-state editor))
    (reset! (:state-atom editor) (make-editor-state text))
    (reset! (:scroll-offset-atom editor) 0)
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:redo-stack editor) [])
    (reset! (:last-action editor) nil)
    (reset! (:jump-mode editor) nil)
    (reset! (:history-idx editor) -1)
    (reset! (:history-draft editor) nil)
    ;; Reconcile the paste store with the new text (e.g. cleared after submit)
    (sync-paste-store! editor (:lines @(:state-atom editor)))))

(defn editor-set-on-submit! [editor f]
  (reset! (:on-submit editor) f))

(defn editor-set-on-change! [editor f]
  (reset! (:on-change editor) f))

(defn editor-set-padding-x!
  "Set the horizontal padding in columns (pi: EditorComponent.setPaddingX)."
  [editor n]
  (reset! (:padding-x editor) n))

(defn editor-set-top-border-fn!
  "Install F as the editor's top-border renderer, or nil to restore the
   default rule. F is called on every render with
   {:width :hidden-line-count :rule :border-fn} and returns the full top
   border line, or nil to fall back to the default (a plain rule, or the
   centered scroll label when scrolled). This is the app's status-in-border
   hook (pi: CustomEditor.renderTopBorder — the embedWorkingStatus opt-in
   that puts the session status in the editor's first line; the app's
   kmet.app.ui.status-indicator/editor-embeds-status? reads it back)."
  [editor f]
  (reset! (:top-border-fn-atom editor) f))

(defn editor-insert-text-at-cursor!
  "Insert text at the current cursor position (pi:
   EditorComponent.insertTextAtCursor — used by clipboard/image paste).
   Pushes an undo snapshot and fires the on-change callback."
  [editor text]
  (when (seq text)
    (let [state @(:state-atom editor)
          lines (:lines state)
          cursor-line (:cursor-line state)
          cursor-col (:cursor-col state)
          line (nth lines cursor-line "")
          new-line (str (subs line 0 cursor-col) text (subs line cursor-col))
          new-state (-> state
                        (assoc :lines (assoc lines cursor-line new-line))
                        (assoc :cursor-col (+ cursor-col (count text))))]
      (push-undo-state editor)
      (reset! (:state-atom editor) new-state)
      (reset! (:redo-stack editor) [])
      (reset! (:last-action editor) nil)
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))
  nil)

(defn editor-set-autocomplete-provider!
  "Set the autocomplete provider (an AutocompleteProvider object)."
  [editor provider]
  (cancel-autocomplete editor)
  (reset! (:autocomplete-provider editor) provider))

(defn editor-set-autocomplete-max-visible!
  "Set the maximum dropdown height in lines (default 5)."
  [editor n]
  (reset! (:autocomplete-max-visible editor) n))

(defn editor-set-autocomplete-theme!
  "Set the SelectList theme used for the dropdown."
  [editor theme]
  (reset! (:autocomplete-theme editor) theme))

(defn editor-set-on-tab! [editor f]
  (editor-set-autocomplete-provider! editor f))

(defn editor-set-on-action!
  "Register (or replace) an app action handler on the editor
   (pi: CustomEditor.onAction). The handler is a zero-arg fn called from
   handle-input when the keybindings manager matches the action's key chord;
   pass nil to unregister. Action IDs are keybinding IDs such as
   \"app.interrupt\", \"app.exit\", or \"app.editor.external\"."
  [editor action-id handler]
  (if handler
    (swap! (:action-handlers editor) assoc action-id handler)
    (swap! (:action-handlers editor) dissoc action-id))
  nil)

(defn editor-set-priority-action!
  "Register or clear a priority action handler (extension shortcuts — pi:
   onExtensionShortcut): checked before every builtin app action, including
   app.interrupt/app.exit. HANDLER — nil removes the registration."
  [editor action-id handler]
  (if handler
    (swap! (:priority-action-handlers editor) assoc action-id handler)
    (swap! (:priority-action-handlers editor) dissoc action-id))
  nil)

(defn editor-get-expanded-text
  "Get the editor text with paste markers expanded to their stored content
   (pi: Editor.getExpandedText — used by the external editor flow)."
  [editor]
  (reduce-kv (fn [result id content]
               (if content
                 (clojure.string/replace result
                                         (re-pattern (str "\\[paste #" id "[^\\]]*\\]"))
                                         content)
                 result))
             (editor-get-text editor)
             @(:paste-store editor)))

(defn editor-autocomplete-active?
  "True when the autocomplete dropdown is currently showing."
  [editor]
  (boolean @(:autocomplete-state editor)))

(defn editor-get-history [editor]
  @(:history editor))

(defn editor-set-history! [editor history]
  (reset! (:history editor) (vec history))
  (reset! (:history-idx editor) -1)
  (reset! (:history-draft editor) nil))

(defn editor-get-paste [editor id]
  (get @(:paste-store editor) id))

(defn editor-set-height! [editor h]
  (reset! (:height-atom editor) h))

(defn editor-set-terminal-rows!
  "Set the terminal rows source (fn returning row count) used for dynamic
   height. Pass nil to revert to the fixed :height fallback."
  [editor f]
  (reset! (:terminal-rows-atom editor) f))

(defn editor-get-text-length [editor]
  (count (editor-get-text editor)))

;; ─── IEditorComponent ───────────────────────────────────────────────────────

(extend-type Editor
  protocols/IEditorComponent
  (editor-get-text [this] (editor-get-text this))
  (editor-set-text! [this text] (editor-set-text! this text))
  (editor-get-expanded-text [this] (editor-get-expanded-text this))
  (editor-add-to-history! [this text] (editor-push-history! this text))
  (editor-insert-text-at-cursor! [this text] (editor-insert-text-at-cursor! this text))
  (editor-set-autocomplete-provider! [this provider]
    (editor-set-autocomplete-provider! this provider))
  (editor-set-autocomplete-max-visible! [this n]
    (editor-set-autocomplete-max-visible! this n))
  (editor-set-padding-x! [this n] (editor-set-padding-x! this n))
  (editor-set-on-submit! [this f] (editor-set-on-submit! this f))
  (editor-set-on-change! [this f] (editor-set-on-change! this f)))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type Editor
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

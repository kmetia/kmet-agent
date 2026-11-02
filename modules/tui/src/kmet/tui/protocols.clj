(ns kmet.tui.protocols
  "Protocols for the TUI component system.
   Extracted from core.clj to avoid circular dependencies.")

(defprotocol IComponent
  "Component interface (pi: Component). Implement render/handle-input/
   invalidate/dispose. Records may optionally carry a :wants-key-release?
   field (pi: Component.wantsKeyRelease) — when true, key release events are
   delivered to handle-input (filtered otherwise). kmet does not request
   event types (kmet.tui.terminal-escape/DESIRED-KITTY-FLAGS), so this only has an
   effect on terminals that report releases anyway."
  (render [this width] "Render component to lines (seq of strings)")
  (handle-input [this data] "Handle keyboard input")
  (invalidate [this] "Clear cached render state")
  (dispose [this] "Release resources (timers, watches); must be idempotent.
                   Containers delegate to their children.
                   HAND-ROLLED implementors (reify/defrecord outside
                   defcomponent) MUST include this method — there is no
                   universal default under SCI."))

(defn join-lines
  "Concatenate PARTS — each a sequence of lines — into one line vector. The
   transient reduce is the frame path's shared form: this flattens a whole
   document (tens of thousands of lines) per pass, where the old
   (vec (mapcat …)) spent real allocation/GC on lazy seqs. A single part is
   returned as-is when it is already a vector, so a one-child container
   copies nothing."
  [parts]
  (let [parts (if (vector? parts) parts (vec parts))]
    (if (= 1 (count parts))
      (let [p (nth parts 0)]
        (if (vector? p) p (vec p)))
      (persistent!
       (reduce (fn [acc p] (reduce conj! acc p))
               (transient [])
               parts)))))

(defn render-all
  "Render each component in COMPONENTS and concatenate the lines into one
   vector (join-lines). ELEM, when given, names the key holding the
   component in item maps (the reconciler's {:c …} shape) — a direct get,
   so the caller need not allocate a mapped-components seq."
  ([components width]
   (join-lines (mapv (fn [c] (render c width)) components)))
  ([items width elem]
   (join-lines (mapv (fn [it] (render (get it elem) width)) items))))

(defn concat-cached
  "Flatten PARTS — child line vectors, in render order — into one line
   vector, reusing PREV's result when nothing the assembly depends on
   changed.

   PREV is the :cache value this function returned last pass (nil on the
   first pass); the caller stores it. KEY carries every input of the
   assembly besides PARTS (the width, mode flags, a background sample) and
   is compared with =. A hit needs KEY = the previous KEY and every part
   identical? to the part it replaced; it returns the previous vector
   ITSELF, so a caller above sees an unchanged object and can reuse its own
   assembly too.

   Correctness rests on KEY and PARTS together describing the assembly: the
   identity check only decides whether the concatenation can be skipped, so
   a child that re-derives (returning a fresh vector) is a miss, never a
   stale result. A container whose assembly is more than this join passes
   JOIN-FN (built from PARTS on a miss) and puts whatever JOIN-FN reads
   besides PARTS into KEY. A container that inserts separator lines should
   list them as parts (one shared, identity-stable vector) rather than
   inside JOIN-FN — then a changed separator is a changed parts sequence.

   Returns {:lines <vector> :cache <next PREV>}. Holds one reference per
   part and one line vector, the same objects the result already
   references, so an unchanged document costs no per-frame allocation."
  ([prev key parts] (concat-cached prev key parts join-lines))
  ([prev key parts join-fn]
   (let [parts (if (vector? parts) parts (vec parts))
         n (count parts)]
     (if (and prev
              (= key (:key prev))
              (= n (count (:parts prev)))
              (loop [i 0]
                (if (>= i n)
                  true
                  (if (identical? (nth parts i) (nth (:parts prev) i))
                    (recur (inc i))
                    false))))
       {:lines (:lines prev) :cache prev}
       (let [lines (join-fn parts)]
         {:lines lines :cache {:key key :parts parts :lines lines}})))))

(defn render-all-cached
  "Render COMPONENTS at WIDTH into one line vector through concat-cached:
   every component still renders once per call (its own cache decides
   whether that is cheap), and the concatenation is skipped when the whole
   set returns the objects it returned last pass."
  [prev key components width]
  (concat-cached prev key (mapv (fn [c] (render c width)) components)))

(defn dispose-component!
  "Dispose a value of any shape: a defcomponent record, a reify, a
   duck-typed {:render … :dispose …} map, or a sequence of any of these
   (widget strips can hold multi-root compiled trees). A :dispose key wins;
   otherwise the protocol's dispose. Exceptions are swallowed — disposal is
   cleanup, and a broken foreign component must not take the frame down.
   Idempotent by convention; nil is a no-op."
  [component]
  (when (some? component)
    (if (sequential? component)
      (doseq [c component] (dispose-component! c))
      (if-let [f (:dispose component)]
        (try (f) (catch Exception _))
        (try (dispose component) (catch Exception _))))))

(defprotocol IFocusable
  (focused [this])
  (set-focused! [this val]))

(defprotocol IEditorComponent
  "Custom editor contract (pi: EditorComponent in editor-component.ts).
   Extends IComponent with text access and app-integration callbacks so
   extensions can provide alternative editor implementations (vim/emacs
   modes etc.). Callbacks and appearance that pi exposes as properties
   (onSubmit/onChange/borderColor) remain duck-typed record fields."
  (editor-get-text [this] "Current text content (paste markers unexpanded)")
  (editor-set-text! [this text] "Replace the full text")
  (editor-get-expanded-text [this] "Text with paste markers expanded; falls back to plain text")
  (editor-add-to-history! [this text] "Add text to the history stack")
  (editor-insert-text-at-cursor! [this text] "Insert text at the current cursor")
  (editor-set-autocomplete-provider! [this provider] "Set the autocomplete provider")
  (editor-set-autocomplete-max-visible! [this n] "Set the autocomplete dropdown height")
  (editor-set-padding-x! [this n] "Set horizontal padding")
  (editor-set-on-submit! [this f] "Set the submit callback")
  (editor-set-on-change! [this f] "Set the change callback"))

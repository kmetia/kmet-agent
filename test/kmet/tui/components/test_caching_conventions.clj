(ns kmet.tui.components.test-caching-conventions
  "Convention test: every component with a render method must either use
   track! with a :cache-atom (or legacy :cache) field, or be on the
   documented uncached allowlist.

   Rationale: an uncached component whose output changes while sitting above
   the render viewport forces needless work every frame — a mid-document line
   changing above the viewport is no longer destructive (it is repainted in
   place, or skipped when entirely above the window; see
   kmet.tui.test-render-loop), but a volatile component that spans the window
   still repaints its visible lines every frame and flickers. track! makes
   the cache the default so volatile renders can only live in the two
   documented places: at the document bottom (spinner/status) or cached so
   they tick with real updates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(def ^:private uncached-allowlist
  "Components deliberately without track! caching, keyed by record name.
   :transparent-parent — output depends on children's internal state, which
     track! cannot see; the render walks children (each cached) every pass.
     Box additionally memoizes its padding/bg composition on child-lines.
   :time-animated — output depends on wall-clock time; must render fresh
     every pass.
   :focused-input — user-editable widgets.
   :stateless-ephemeral — no mutable state, or recreated per render (a
     track! cache would never be hit)."
  {"Box"                        :transparent-parent
   "Container"                  :transparent-parent
   "HStack"                     :transparent-parent
   "VStack"                     :transparent-parent
   "ChatHistoryComponent"       :transparent-parent
   "ScrollView"                 :transparent-parent
   "SelectorDialog"    :transparent-parent
   "InputDialog"       :transparent-parent
   "ModelSelector"              :transparent-parent
   "ScopedModelsSelector"       :transparent-parent
   "ThinkingSelector"           :transparent-parent
   "AuthSelector"               :transparent-parent
   "AuthMethodSelector"         :transparent-parent
   "LoginDialog"                :transparent-parent
   ;; delegates to a DSL root whose body tracks the status text atom
   "StatusLine"                 :transparent-parent
   ;; transparent delegate over foreign dialog closures — nothing the
   ;; deref tracker could see; lifetime owned by ui-custom's bookkeeping
   "CustomDialogAdapter"        :transparent-parent
   "Spinner"                    :time-animated
   "StatusIndicator"            :time-animated
   "RetryStatusIndicator"       :time-animated
   "CompactionStatusIndicator"  :time-animated
   "BranchSummaryStatusIndicator" :time-animated
   "AltScreenFlashContainer"    :time-animated
   "CancellableLoader"          :time-animated
   "Input"                      :focused-input
   "Editor"                     :focused-input
   "DynamicBorder"              :stateless-ephemeral
   "BashExecutionComponent"     :transparent-parent})

(def ^:private component-dirs
  ["src/kmet/tui/components" "src/kmet/app/ui"])

;; ─── Top-level form splitting (paren-balanced, strings/comments skipped) ──

(def ^:private special-chars
  "Structural chars the form scanner reacts to: parens, quotes, backslash,
   semicolon, newline (regex matcher walks these; escaped chars are skipped
   via region narrows)."
  #"[()\"\\;\n]")

(defn- top-level-forms
  "Split SRC into its top-level forms, skipping string/regex literals and
   line comments so parens inside them don't unbalance the scan. A single
   native regex matcher walks the structural chars — the loop runs once per
   special character, not once per source character."
  [src]
  (let [m (re-matcher special-chars src)
        n (count src)]
    (loop [depth 0 start 0 in-str false in-comment false acc []]
      (if (.find m)
        (let [i (.start m)
              c (.group m)]
          (cond
            ;; a comment runs to the end of its line
            in-comment
            (if (= c "\n")
              (recur depth start false false acc)
              (recur depth start false true acc))

            ;; inside a string only backslash (escape) and the closing
            ;; quote change state; parens/semicolons/newlines are content
            in-str
            (if (= c "\\")
              (do (.region m (min n (+ i 2)) n)  ;; skip the escaped char
                  (recur depth start true false acc))
              (recur depth start (not= c "\"") false acc))

            (= c ";") (recur depth start false true acc)
            (= c "\"") (recur depth start true false acc)
            (= c "(") (recur (inc depth) start false false acc)

            (= c ")")
            (let [d (dec depth)]
              (if (zero? d)
                (recur 0 (inc i) false false
                       (conj acc (subs src start (inc i))))
                (recur d start false false acc)))

            ;; newline outside a string/comment — plain whitespace
            :else (recur depth start false false acc)))
        (if (zero? depth) acc (conj acc (subs src start n)))))))
(defn- strip-leading-comments
  "Remove leading comment lines and whitespace from a form text so the
   def regex can anchor on the def form itself."
  [s]
  (loop [s s]
    (let [t (str/triml s)]
      (if (str/starts-with? t ";")
        (recur (str/replace-first t #";[^\n]*" ""))
        t))))

(defn- component-info
  "Parse a top-level defrecord/defcomponent form into
   {:name :has-render? :uses-track? :fields} or nil."
  [form]
  (let [form (strip-leading-comments form)]
    (when-let [[_ kind name fields]
               (re-find #"^\(def(record|component)\s+([^\s\[(]+)(?:\s+[^\s\[(]+)?\s*\[([^\]]*)\]" form)]
      {:kind kind
       :name name
       :has-render? (boolean (re-find #"\(\s*render\s+\[" form))
       :uses-track? (boolean (re-find #"track!" form))
       :fields fields})))

(def ^:private expected-component-count
  "Total number of render-bearing components across component-dirs. Bumped
   when components are added or removed; a mismatch means the scan below
   silently lost forms (a scanner regression), so the convention checks
   would otherwise pass vacuously."
  50)

(deftest caching-conventions
  (let [checked (atom [])]
    (doseq [dir component-dirs
            f (->> (fs/list-dir dir)
                   (filter #(str/ends-with? (str %) ".clj"))
                   (sort-by str))]
      (let [src (slurp (str f))]
        (doseq [form (top-level-forms src)]
          (when-let [{:keys [kind name has-render? uses-track? fields]} (component-info form)]
            (when has-render?
              (swap! checked conj name)
              ;; All UI components are defcomponents — extra protocols (e.g.
              ;; IFocusable) go in separate extend-type forms (AGENTS.md).
              (is (= "component" kind)
                  (str name " (in " f ") must be a defcomponent — extra "
                       "protocols go in extend-type forms after the call"))
              (let [has-cache-field? (boolean (re-find #"cache-atom|\bcache\b" fields))
                    allowlisted? (contains? uncached-allowlist name)]
                (is (or (and uses-track? has-cache-field?)
                        allowlisted?)
                    (str name " (in " f ") must use track! with a :cache-atom/"
                         "legacy :cache field or be on the uncached allowlist "
                         "(see test-caching-conventions docstring)"))))))))
    (let [checked-names (set @checked)]
      ;; Every allowlisted component must actually exist — catches allowlist
      ;; rot (renamed/removed components).
      (is (every? checked-names (keys uncached-allowlist))
          (str "uncached-allowlist entries missing from the component scan: "
               (pr-str (remove checked-names (keys uncached-allowlist)))))
      ;; The full inventory must be scanned — a scanner regression that
      ;; silently drops forms would otherwise pass the per-component checks
      ;; vacuously.
      (is (= expected-component-count (count @checked))
          (str "expected " expected-component-count " render-bearing components, "
               "scanned " (count @checked) ": " (pr-str (sort @checked)))))))

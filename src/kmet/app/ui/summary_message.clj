(ns kmet.app.ui.summary-message
  "SummaryMessageComponent — pi's CompactionSummaryMessageComponent and
   BranchSummaryMessageComponent (one record, :variant :compaction |
   :branch). Renders a compaction or branch summary as a custom-message-bg
   box with the pi layout:

     [compaction]          | [branch]
     <blank>
     collapsed:  Compacted from 12,345 tokens (ctrl+o to expand)
                 Branch summary (ctrl+o to expand)
     expanded:   **Compacted from 12,345 tokens**
                 <summary, as Markdown>
                 **Branch Summary**
                 <summary, as Markdown>

   Collapsed is the default and shows no summary text; expansion follows
   the shared ctrl+o tool-display mode atom (pi: toolOutputExpanded) — one
   atom, so every summary flips together. :quiet projects to collapsed,
   like the info banner and loaded resources.

   pi renders the label/bg in the custom-message palette (its two classes
   are identical modulo label/heading text), so this component reuses the
   same colours as CustomMessageComponent."
  (:require [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.subs :as s]
            [kmet.libs.reakt :as reakt]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.macros :refer [track! defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

;; ─── Labels & content strings (pi: updateDisplay) ──────────────────────────

(defn- variant-label
  "The bracketed label text (pi: `[compaction]` / `[branch]`)."
  [variant]
  (case variant :compaction "compaction" "branch"))

(defn- tokens-before-label
  "pi: message.tokensBefore.toLocaleString() — grouped digits. Legacy or
   hand-edited entries without a numeric :tokens-before fall back instead
   of crashing the replay (the session loader does not validate entries)."
  [tokens-before]
  (cond
    (number? tokens-before) (str (format "%,d" (long tokens-before)) " tokens")
    (some? tokens-before) (str tokens-before " tokens")
    :else "unknown tokens"))

(defn- collapsed-line
  "The collapsed one-liner: the pi wording with the expand key dimmed
   (pi: theme.fg(customMessageText, ...) + theme.fg(dim, keyText(...)))."
  [tokens-before variant thm]
  (let [hint (theme/fg thm :dim (app-kb/key-text "app.tools.expand"))
        intro (case variant
                :compaction (str "Compacted from " (tokens-before-label tokens-before)
                                 " (")
                "Branch summary (")]
    (str (theme/fg thm :custom-message-text intro)
         hint
         (theme/fg thm :custom-message-text " to expand)"))))

(defn- expanded-markdown
  "The expanded heading + summary (pi: `**Compacted from N tokens**\\n\\n` /
   `**Branch Summary**\\n\\n` + summary)."
  [tokens-before variant summary]
  (case variant
    :compaction (str "**Compacted from " (tokens-before-label tokens-before) "**\n\n" summary)
    (str "**Branch Summary**\n\n" summary)))

;; ─── Record ────────────────────────────────────────────────────────────────

(declare apply-theme! rebuild-content!)

(defn- expanded-state
  "The expansion state the children were last built for, read WITHOUT
   tracking — the render sync compares against it; the body's own tracked
   read of the same atom keeps the cache honest (see skill-message)."
  [comp]
  @(:expanded-atom comp))

(defcomponent SummaryMessage :summary
              [tools-expanded-atom   ;; shared ctrl+o display-mode atom (:collapsed | :expanded | :quiet)
               variant               ;; :compaction | :branch
               tokens-before         ;; int or nil (legacy entries)
               summary-text          ;; the summary body
               spacer                ;; Spacer(1) above the box (pi adds one to the chat)
               box                   ;; Box with the custom-message background
               inner-container       ;; label + blank line + collapsed line / expanded Markdown
               applied-theme-atom    ;; scratch: theme the box/children were built with
               expanded-atom         ;; expanded state the children were built for
               output-pad-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [mode (or (some-> tools-expanded-atom reakt/tracked-deref) :collapsed)
            expanded (= :expanded mode)
            ;; tracked read: a palette switch re-applies the box background once
            thm (deref s/theme-sub)
            _ (when-not (identical? thm @applied-theme-atom)
                (reset! applied-theme-atom thm)
                (apply-theme! this thm))
            ;; the shared mode is the single source of truth (pi:
            ;; toolOutputExpanded) — rebuild at most once per flip
            _ (when (not= expanded (expanded-state this))
                (rebuild-content! this expanded))
            ;; tracked: the sync above invalidates this cache mid-body, which
            ;; track! answers by not caching the frame (one extra body run per
            ;; flip — the same shape as the theme apply-once above)
            _ @expanded-atom]
        (into [] (concat (protocols/render @spacer width)
                         (protocols/render @box width))))))
  (dispose [_this]
    (protocols/dispose @spacer)
    (protocols/dispose @box)))

;; ─── Internal: rebuild the children ────────────────────────────────────────

(defn- rebuild-content!
  "Rebuild the children for expansion state EXPANDED? (pi: updateDisplay):
   label line, blank line, then the collapsed Text or the expanded Markdown."
  [comp expanded?]
  (reset! (:expanded-atom comp) (boolean expanded?))
  (let [thm (deref s/theme-sub)
        container @(:inner-container comp)
        label-line (text/make-text
                    (theme/fg thm :custom-message-label
                              (theme/bold (str "[" (variant-label (:variant comp)) "]")))
                    0 0)
        content-children
        (if expanded?
          [(md/make-markdown (expanded-markdown (:tokens-before comp) (:variant comp)
                                                (:summary-text comp))
                             :theme (theme/get-markdown-theme thm)
                             :default-style (fn [str] (theme/fg thm :custom-message-text str))
                             :padding-x 0)]
          [(text/make-text (collapsed-line (:tokens-before comp) (:variant comp) thm) 0 0)])]
    ;; replace (not clear+add): dropped children are disposed, so their track!
    ;; watches do not outlive them (zombie watchers, tui.md §5.1)
    (container/container-replace-children!
     container (concat [label-line (spacer/make-spacer 1)] content-children))))

(defn- apply-theme!
  "Apply THEME to the derived structures (box background + rebuilt children).
   Runs at construction and whenever theme-sub changes (render, apply-once)."
  [comp thm]
  (box/box-set-bg-fn @(:box comp) #(theme/bg thm :custom-message-bg %))
  (rebuild-content! comp @(:expanded-atom comp)))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn summary-message-set-output-pad!
  "Set the box's horizontal padding in place, reusing the children."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (box/box-set-padding-x! @(:box comp) n))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-summary-message
  "Create a SummaryMessageComponent.
   Options:
     :variant              — :compaction | :branch
     :summary              — the summary body text
     :tokens-before        — pre-compaction token count (nil → generic label)
     :tools-expanded-atom  — the shared ctrl+o display-mode atom
     :output-pad           — horizontal padding (default 1)"
  [& {:keys [variant summary tokens-before tools-expanded-atom output-pad]
      :or {variant :compaction output-pad 1}}]
  (let [thm (theme/get-current-theme)
        inner-container (container/make-container)
        b (box/make-box output-pad 1 nil)]
    (box/box-add-child b inner-container)
    (let [comp (map->SummaryMessage
                {:kind :summary
                 :tools-expanded-atom (or tools-expanded-atom (atom :collapsed))
                 :variant variant
                 :tokens-before tokens-before
                 :summary-text (or summary "")
                 :spacer (atom (spacer/make-spacer 1))
                 :box (atom b)
                 :inner-container (atom inner-container)
                 :applied-theme-atom (atom nil)
                 :expanded-atom (atom false)
                 :output-pad-atom (atom output-pad)
                 :cache-atom (atom nil)})]
      ;; children + background built here from the global theme snapshot; the
      ;; first render re-applies from theme-sub if it changed meanwhile
      (reset! (:applied-theme-atom comp) thm)
      (apply-theme! comp thm)
      comp)))

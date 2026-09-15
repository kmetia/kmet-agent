(ns kmet.app.ui.skill-message
  "SkillInvocationMessageComponent — pi's SkillInvocationMessageComponent.

   A `/skill:name args` invocation expands to a `<skill …>` block
   (kmet.app.skills/expand-skill-command) that goes to the model verbatim
   and is stored in the session as the user message's content. Rendering
   that text directly would dump the XML wrapper and the whole skill body
   into the transcript, so the block is parsed back
   (kmet.app.skills/parse-skill-block) and shown as a dedicated message:

     collapsed   [skill] <name> (ctrl+o to toggle)
     expanded    [skill]
                 **<name>**

                 <skill body, as Markdown>
     quiet       [skill] <name> — one dimmed line, no box/hint (like hidden
                 thinking); !/!! bash keeps the collapsed preview instead

   Expansion follows the shared ctrl+o tool-display mode atom (pi:
   toolOutputExpanded, extended with quiet) — one atom, so every skill
   message flips together.
   The trailing user message (the args after the block) renders below as a
   normal user message, exactly as pi splits the two.

   The bracketed label and the expand hint are shared with the compact read
   call an agent's later SKILL.md read renders as
   (`tool-renderers/format-compact-read-call`), so the two spellings cannot
   drift."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.libs.reakt :as reakt]
            [kmet.app.ui.subs :as s]
            [kmet.app.ui.user-message :as um]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.utils :as utils]
            [kmet.tui.macros :refer [track! defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

;; ─── Shared styling (also used by the compact read call) ───────────────────

(defn bracket
  "The bracketed `[skill]` marker — bold, in the custom-message label colour."
  [thm]
  (theme/fg thm :custom-message-label (theme/bold "[skill]")))

(defn label
  "The bracketed marker plus its separating space — the prefix of the
   collapsed line and of the compact read call."
  [thm]
  (str (bracket thm) " "))

(defn expand-hint
  "` (ctrl+o to toggle)` in dim — pi: keyText('app.tools.expand')."
  [thm]
  (theme/fg thm :dim (str " (" (app-kb/key-text "app.tools.expand") " to toggle)")))

(defn collapsed-line
  "The one-line collapsed form: `[skill] <name> (ctrl+o to toggle)`."
  [thm name]
  (str (label thm)
       (theme/fg thm :custom-message-text name)
       (expand-hint thm)))

;; ─── Record ────────────────────────────────────────────────────────────────

(declare apply-theme! rebuild-content!)

(defn- expanded-state
  "The expansion state the children were last built for, read WITHOUT
   tracking — the sync below compares against it; the body's own tracked
   read of the same atom is what keeps the cache honest."
  [comp]
  @(:expanded-atom comp))

(defcomponent SkillInvocationMessage :skill
              [tools-expanded-atom   ;; shared ctrl+o display-mode atom (:collapsed | :expanded | :quiet)
               box                   ;; Box with the custom-message background
               inner-container       ;; label/line + body children
               skill-name-atom
               content-atom          ;; the skill body (Markdown source when expanded)
               user-message-atom     ;; UserMessageComponent for the trailing args, or nil
               user-spacer-atom      ;; Spacer(1) separating the two, or nil
               applied-theme-atom    ;; scratch: theme the box/children were built with
               expanded-atom         ;; expanded state the children were built for
               output-pad-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [mode (or (some-> tools-expanded-atom reakt/tracked-deref) :collapsed)
            quiet? (= :quiet mode)
            expanded (= :expanded mode)
            ;; tracked reads: the shared mode flips every skill message at
            ;; once, and a palette switch re-applies the box background
            thm (deref s/theme-sub)
            _ (when-not (identical? thm @applied-theme-atom)
                (reset! applied-theme-atom thm)
                (apply-theme! this thm))
            ;; the shared mode is the single source of truth (pi:
            ;; toolOutputExpanded, extended): sync only when it moved, so
            ;; the children are rebuilt at most once per flip
            _ (when (not= expanded (expanded-state this))
                (rebuild-content! this expanded))
            ;; tracked: the sync above invalidates this cache mid-body, which
            ;; track! answers by not caching the frame (one extra body run
            ;; per flip — the same shape as the theme apply-once above)
            _ @expanded-atom
            output-pad @output-pad-atom]
        ;; Quiet short-circuit — one dimmed `[skill] name` line, no
        ;; box/hint/body (like hidden thinking). The trailing user message
        ;; (the invocation args) still renders: it is user content, not
        ;; tool output. Reads only mode/theme/name/pad. The name is
        ;; one-lined like tool titles (truncate counts columns, not lines).
        (if quiet?
          (let [content-width (max 1 (- width (* 2 output-pad)))
                raw (str "[skill] " (str/replace (or @(:skill-name-atom this) "")
                                                 #"[ \t]*[\r\n]+[ \t\r\n]*" " "))
                line (str (apply str (repeat output-pad \space))
                          (theme/italic (theme/fg thm :thinking-text
                                                  (utils/truncate-to-width raw content-width "..."))))]
            (into ["" line]
                  (concat (when-let [sp @user-spacer-atom]
                            (protocols/render sp width))
                          (when-let [um @user-message-atom]
                            (protocols/render um width)))))
          (into [] (concat (protocols/render @box width)
                           (when-let [sp @user-spacer-atom]
                             (protocols/render sp width))
                           (when-let [um @user-message-atom]
                             (protocols/render um width))))))))
  (invalidate [_this]
    (protocols/invalidate @box)
    (when-let [sp @user-spacer-atom] (protocols/invalidate sp))
    (when-let [um @user-message-atom] (protocols/invalidate um)))
  (dispose [_this]
    (protocols/dispose @box)
    (when-let [sp @user-spacer-atom] (protocols/dispose sp))
    (when-let [um @user-message-atom] (protocols/dispose um))))

;; ─── Internal: rebuild the children ────────────────────────────────────────

(defn- rebuild-content!
  "Rebuild the children for expansion state EXPANDED? (recorded on the
   component). Collapsed is a single Text line; expanded is the `[skill]`
   label followed by a Markdown of `**name**` + the body (pi: updateDisplay)."
  [comp expanded?]
  (reset! (:expanded-atom comp) (boolean expanded?))
  (let [thm (deref s/theme-sub)
        container @(:inner-container comp)
        name @(:skill-name-atom comp)
        children
        (if expanded?
          [(text/make-text (bracket thm) 0 0)
           (md/make-markdown (str "**" name "**\n\n" @(:content-atom comp))
                             :theme (theme/get-markdown-theme thm)
                             :default-style (fn [s] (theme/fg thm :custom-message-text s))
                             :padding-x 0)]
          [(text/make-text (collapsed-line thm name) 0 0)])]
    ;; replace (not clear+add): the dropped children are disposed, so their
    ;; track! watches do not outlive them (zombie watchers, tui.md §5.1)
    (container/container-replace-children! container children)))

(defn- apply-theme!
  "Apply THEME to the derived structures (box background + rebuilt children).
   Runs at construction and whenever theme-sub changes (render, apply-once)."
  [comp thm]
  (box/box-set-bg-fn @(:box comp) #(theme/bg thm :custom-message-bg %))
  (rebuild-content! comp @(:expanded-atom comp)))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn skill-message-set-output-pad!
  "Set the box's horizontal padding in place, reusing the children; the
   trailing user message follows the same padding."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (box/box-set-padding-x! @(:box comp) n)
  (when-let [um @(:user-message-atom comp)]
    (um/user-message-set-output-pad! um n)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-skill-invocation-message
  "Create a SkillInvocationMessage from a parsed skill block.

   Options:
     :skill-block           — {:name :location :content :user-message}
     :tools-expanded-atom   — the shared ctrl+o display-mode atom (:collapsed | :expanded | :quiet)
     :user-message          — a UserMessageComponent for the trailing args,
                              or nil; separated from the box by a Spacer(1)
                              (pi adds the pair to the chat container)
     :output-pad            — horizontal padding (default 1)"
  [& {:keys [skill-block tools-expanded-atom user-message output-pad]
      :or {output-pad 1}}]
  (let [thm (theme/get-current-theme)
        inner-container (container/make-container)
        b (box/make-box output-pad 1 nil)]
    (box/box-add-child b inner-container)
    (let [comp (map->SkillInvocationMessage
                {:kind :skill
                 :tools-expanded-atom (or tools-expanded-atom (atom :collapsed))
                 :box (atom b)
                 :inner-container (atom inner-container)
                 :skill-name-atom (atom (:name skill-block))
                 :content-atom (atom (:content skill-block ""))
                 :user-message-atom (atom user-message)
                 :user-spacer-atom (atom (when user-message (spacer/make-spacer 1)))
                 :applied-theme-atom (atom nil)
                 :expanded-atom (atom false)
                 :output-pad-atom (atom output-pad)
                 :cache-atom (atom nil)})]
      ;; Children and background built here from the global theme snapshot;
      ;; the first render re-applies from theme-sub if it changed meanwhile
      (reset! (:applied-theme-atom comp) thm)
      (apply-theme! comp thm)
      comp)))

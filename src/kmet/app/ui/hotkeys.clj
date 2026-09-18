(ns kmet.app.ui.hotkeys
  "The /hotkeys view — pi: handleHotkeysCommand. A hiccup tree
   (tui.md §14.1): the frame (borders, title) and the spacers are hiccup nodes, and the
   key/action data rides one :markdown node as pi's GFM tables — a
   `**Section**` header per group plus `| Key | Action |` rows, the key
   column resolved through the live keybindings manager (keybindings.edn
   overrides and extension registrations apply), plus an Extensions section
   for shortcuts registered at runtime. Only bindings the caller reports as
   wired are shown (see make-hotkeys-view), so a declared-but-unwired action
   is never advertised; a row whose ids are all unbound or unwired leaves no
   line."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.libs.process :as process]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.theme :as theme]))

;; ─── Section data ─────────────────────────────────────────────────────────

(def ^:private binding-sections
  "pi: handleHotkeysCommand's tables as data: each row is [action spec],
   SPEC a vector of keybinding ids or literal key text (`/`, `!`, `!!`).
   kmet deltas: app.quit and app.view.forceRedraw are kmet-only global
   bindings (pi has neither), and app.clipboard.pasteImage has no binding
   yet — its row appears when the feature lands."
  [{:title "Navigation"
    :rows [["Move cursor / browse history"
            ["tui.editor.cursorUp" "tui.editor.cursorDown"
             "tui.editor.cursorLeft" "tui.editor.cursorRight"]]
           ["Move by word"
            ["tui.editor.cursorWordLeft" "tui.editor.cursorWordRight"]]
           ["Start of line" ["tui.editor.cursorLineStart"]]
           ["End of line" ["tui.editor.cursorLineEnd"]]
           ["Jump forward to character" ["tui.editor.jumpForward"]]
           ["Jump backward to character" ["tui.editor.jumpBackward"]]
           ["Scroll by page" ["tui.editor.pageUp" "tui.editor.pageDown"]]]}
   {:title "Editing"
    :rows [["Send message" ["tui.input.submit"]]
           [(str "New line"
                 (when process/windows-os? " (Ctrl+Enter on Windows Terminal)"))
            ["tui.input.newLine"]]
           ["Delete word backwards" ["tui.editor.deleteWordBackward"]]
           ["Delete word forwards" ["tui.editor.deleteWordForward"]]
           ["Delete to start of line" ["tui.editor.deleteToLineStart"]]
           ["Delete to end of line" ["tui.editor.deleteToLineEnd"]]
           ["Paste the most-recently-deleted text" ["tui.editor.yank"]]
           ["Cycle through the deleted text after pasting" ["tui.editor.yankPop"]]
           ["Undo" ["tui.editor.undo"]]
           ["Path completion / accept autocomplete" ["tui.input.tab"]]]}
   {:title "Other"
    :rows [["Cancel autocomplete / abort streaming" ["app.interrupt"]]
           ["Clear editor (first) / exit (second)" ["app.clear"]]
           ["Exit (when editor is empty)" ["app.exit"]]
           ["Quit kmet (from anywhere)" ["app.quit"]]
           ["Suspend to background" ["app.suspend"]]
           ["Cycle thinking level" ["app.thinking.cycle"]]
           ["Cycle models" ["app.model.cycleForward" "app.model.cycleBackward"]]
           ["Open model selector" ["app.model.select"]]
           ["Toggle tool output expansion" ["app.tools.expand"]]
           ["Toggle thinking block visibility" ["app.thinking.toggle"]]
           ["Edit message in external editor" ["app.editor.external"]]
           ["Copy last assistant message" ["app.message.copy"]]
           ["Queue follow-up message" ["app.message.followUp"]]
           ["Restore queued messages" ["app.message.dequeue"]]
           ["Paste image or text from clipboard" ["app.clipboard.pasteImage"]]
           ["Force a full redraw" ["app.view.forceRedraw"]]
           ["Slash commands" "/"]
           ["Run bash command" "!"]
           ["Run bash command (excluded from context)" "!!"]]}])

(defn- key-md
  "The markdown key cell for a row spec: a vector of keybinding ids keeps
   only the ids that are both wired (WIRED? — the caller's wiring predicate)
   and bound, resolving each through the live manager into a backticked code
   span (tui.md §7.1 — the form every in-app hint uses; pi's table cells
   carry the key as inline code too) and joining them with ' / '; a string
   is literal key text, always shown (the submit dispatch wires it). nil
   when no id survives."
  [wired? spec]
  (if (string? spec)
    (str "`" spec "`")
    (not-empty
     (str/join " / " (keep #(when (wired? %)
                              (when-let [label (not-empty (app-kb/key-label %))]
                                (str "`" label "`")))
                           spec)))))

(defn- extension-rows
  "Rows for shortcuts extensions registered at runtime: every resolved
   definition that is not a builtin id (an extension shortcut registers the
   raw key as its id), with the definition's description."
  [wired? kmgr]
  (->> (tui-kb/get-resolved-bindings kmgr)
       keys
       (remove #(contains? app-kb/all-keybinding-defs %))
       sort
       (keep (fn [id]
               (when-let [k (and (wired? id) (not-empty (app-kb/key-label id)))]
                 [k (or (:description (tui-kb/get-definition kmgr id))
                        "Extension shortcut")])))))

(defn- sections
  "The live sections: binding-sections with unwired and unbound rows dropped,
   empty sections dropped, plus the Extensions section when any extension
   shortcut is registered."
  [wired?]
  (let [kmgr (tui-kb/get-global-keybindings)
        ext (vec (extension-rows wired? kmgr))]
    (cond-> (->> binding-sections
                 (keep (fn [{:keys [title rows]}]
                         (when-let [rows (not-empty
                                          (vec (keep (fn [[action spec]]
                                                       (when-let [k (key-md wired? spec)]
                                                         [k action]))
                                                     rows)))]
                           {:title title :rows rows})))
                 vec)
      (seq ext) (conj {:title "Extensions" :rows ext}))))

;; ─── View ─────────────────────────────────────────────────────────────────

(defn- table-md
  "One GFM table from ROWS ([key-md action]) — pi's `| Key | Action |` /
   `|-----|--------|` shape."
  [rows]
  (str "| Key | Action |\n|-----|--------|\n"
       (str/join "\n" (map (fn [[key action]] (str "| " key " | " action " |"))
                           rows))))

(defn- sections-md
  "The sections as pi's single Markdown body: a bold `**Title**` header
   followed by its table, a blank line between sections (pi:
   handleHotkeysCommand's `hotkeys` string)."
  [sections]
  (str/join "\n\n" (map (fn [{:keys [title rows]}]
                          (str "**" title "**\n" (table-md rows)))
                        sections)))

(defn- tree
  "The view tree for SECTIONS ([{:title str :rows [[key-md action] ...]}]),
   styled with the current theme (pi: handleHotkeysCommand's DynamicBorder +
   bold accent title + one Markdown carrying the section tables)."
  [sections]
  (let [th (theme/get-current-theme)
        accent #(theme/fg th :accent %)]
    [:container {}
     [:spacer {:lines 1}]
     [:dynamic-border {}]
     [:spacer {:lines 1}]
     [:text {:text (theme/bold (accent "Keyboard Shortcuts"))
             :padding-x 1 :padding-y 0}]
     [:spacer {:lines 1}]
     [:markdown {:text (sections-md sections)
                 :theme (theme/get-markdown-theme th)
                 :padding-x 1}]
     [:spacer {:lines 1}]
     [:dynamic-border {}]]))

(defn make-hotkeys-view
  "Mount the /hotkeys view as an IComponent (chat-history-add-message!
   {:component ...}). WIRED? — a predicate over keybinding ids naming the
   bindings actually wired in the calling context (the interactive mode
   supplies it from its own wiring); the view shows those only, so a
   declared-but-unwired binding is never advertised. The body re-derives the
   sections and the markdown body on each pass, so a theme switch or an
   extension registering a shortcut re-renders a mounted view (the labels
   and definitions deref the live manager)."
  [wired?]
  (hiccup/root (fn [_] (tree (sections wired?)))))

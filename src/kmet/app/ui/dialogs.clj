(ns kmet.app.ui.dialogs
  "The app's dialog kit — selector and input prompt components (framed
   with DynamicBorders and a bold accent title; the input dialog
   implements IFocusable and forwards focus to the inner component so the
   IME candidate window follows the cursor). Used by the
   interactive mode's OAuth/API-key/auth-method prompts and the /tree
   flow. The app's own kit: extensions build their own dialogs with
   kmet.tui.* and mount them via ui-custom (kmet.extension docstring).

   The frame is a compiled hiccup tree (dsl.md): the border/spacer/title/
   hint chrome AND the interactive content (the `[:select-list]` /
   `[:input]` element) are DSL-owned, so the dialog disposes the frame
   once. The tree constructs the content record — the dialog reaches it
   through the `hiccup/ref` handle it declared, read right after
   `compile-tree` (tui.md §2.4)."
  (:require [clojure.string :as str]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.keybindings :as kb]))

;; ─── Shared frame helpers ──────────────────────────────────────────────────

(defn- title-str
  "Bold accent title (pi: theme.fg('accent', theme.bold(title)))."
  [th title]
  (theme/fg th :accent (theme/bold title)))

(defn- hint-str
  "Keybinding hint string built from the global keybindings
   (pi: keyHint/rawKeyHint); styled dim at the call site."
  [& [key-ids]]
  (let [kmgr (kb/get-global-keybindings)]
    (str/join " • "
              (keep (fn [[k desc]]
                      (when-let [ktext (kb/key-text kmgr k)]
                        (str ktext " " desc)))
                    key-ids))))

(defn- frame
  "The dialog frame as a compiled hiccup tree: a top/bottom DynamicBorder,
   a bold accent title, the interactive CONTENT-EL (a `[:select-list]` /
   `[:input]` element whose `:ref` the caller holds) and a dim keybinding
   hint. Returns the frame's root component (a Container) — its dispose
   cascades to the DSL-owned chrome AND the content, so the dialog
   disposes the frame once."
  [th title content-el & {:keys [hint-keys]}]
  (h/compile-tree
   [:container {}
    [:dynamic-border {:color-fn #(theme/fg th :accent %)}]
    [:spacer {:lines 1}]
    [:text {:padding-x 1 :padding-y 0} (title-str th title)]
    [:spacer {:lines 1}]
    content-el
    [:spacer {:lines 1}]
    [:text {:padding-x 1 :padding-y 0} (theme/fg th :dim (hint-str hint-keys))]
    [:spacer {:lines 1}]
    [:dynamic-border {:color-fn #(theme/fg th :accent %)}]]))

;; ─── Selector (pi: ExtensionSelectorComponent) ─────────────────────────────

(defcomponent SelectorDialog nil [container select-list focused?-atom]
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:select-list this) data))
  (invalidate [this] (protocols/invalidate (:container this)))
  (dispose [this] (protocols/dispose (:container this))))

(extend-type SelectorDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:select-list this) val)))

(defn make-selector-dialog
  "Create a selector dialog over a SelectList.
   TITLE — dialog title; OPTIONS — vector of strings; ON-SELECT receives
   the chosen string; ON-CANCEL fires on escape. TH — theme map."
  [title options on-select on-cancel th]
  (let [items (mapv (fn [o] {:value o :label o}) options)
        sl-ref (h/ref)
        container (frame th title
                         [:select-list {:ref sl-ref
                                        :items items
                                        :height (min (count options) 10)
                                        :theme (theme/get-select-list-theme th)
                                        :on-select (fn [item]
                                                     (on-select (:label item)))
                                        :on-escape on-cancel}]
                         :hint-keys [["tui.select.up" "navigate"]
                                     ["tui.select.down" "navigate"]
                                     ["tui.select.confirm" "select"]
                                     ["tui.select.cancel" "cancel"]])]
    (map->SelectorDialog
     {:container container
      :select-list @sl-ref
      :focused?-atom (atom false)})))

;; ─── Input (pi: ExtensionInputComponent) ───────────────────────────────────

(defcomponent InputDialog nil [container input-comp focused?-atom]
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:input-comp this) data))
  (invalidate [this] (protocols/invalidate (:container this)))
  (dispose [this] (protocols/dispose (:container this))))

(extend-type InputDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:input-comp this) val)))

(defn make-input-dialog
  "Create a one-line input dialog over an Input. TITLE — dialog title;
   ON-SUBMIT receives the trimmed string; ON-CANCEL fires on escape. TH —
   theme map. PREFILL — optional initial text (default \"\") with the
   cursor placed after it (pi: LabelInput)."
  [title on-submit on-cancel th & [prefill]]
  (let [inp-ref (h/ref)
        container (frame th title
                         ;; :value/:cursor are construction inputs here —
                         ;; the frame is compiled once, never re-applied
                         [:input {:ref inp-ref
                                  :value (or prefill "")
                                  ;; pi: LabelInput places the cursor after
                                  ;; the prefilled text
                                  :cursor (count (or prefill ""))
                                  :on-submit (fn [v] (on-submit (str/trim v)))
                                  :on-escape on-cancel}]
                         :hint-keys [["tui.select.confirm" "submit"]
                                     ["tui.select.cancel" "cancel"]])]
    (map->InputDialog
     {:container container
      :input-comp @inp-ref
      :focused?-atom (atom false)})))

(ns kmet.app.ui.settings-submenu
  "Submenu components layered over the settings/select lists (pi:
   modes/interactive/components/settings-submenu.ts). A submenu is a titled
   pane with one interactive child; the SettingsList's :submenu item
   contract opens it, renders it in place of the list, and forwards input
   to it through the child's ref."
  (:require [kmet.tui.hiccup :as h]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.select-list :as select-list]))

;; ─── SubmenuPanel ──────────────────────────────────────────────────────────

(defcomponent SubmenuPanel nil [root input-ref cache-atom]
  (render [this width] (protocols/render (:root this) width))

  (handle-input [this data]
    (when-let [child (h/materialize-ref! this input-ref)]
      (protocols/handle-input child data)
      nil))

  (invalidate [_this]
    ;; propagate to the interactive child (pi: Submenu components delegate
    ;; invalidate)
    (when-let [child (deref input-ref)]
      (protocols/invalidate child)))

  (dispose [this]
    ;; the compiled tree is DSL-owned and dispose unwinds its reactions and
    ;; track! watches
    (h/dispose-tree! (:root this))))

(defn panel
  "Wrap ROOT — a compiled hiccup tree — as a submenu whose input goes to the
   component REF points at. Render and dispose delegate to the tree."
  [root input-ref]
  (map->SubmenuPanel {:root root
                      :input-ref input-ref
                      :cache-atom (atom nil)}))

;; ─── SelectSubmenu (pi: SelectSubmenu) ────────────────────────────────────

(defn make-select-submenu
  "Build a titled single-step submenu over a SelectList (pi: SelectSubmenu).
   TITLE renders bold in the accent color, an optional DESCRIPTION muted;
   ITEMS ({:label :value :description}) are shown with CURRENT-VALUE
   preselected. ON-SELECT receives the selected item's :value, ON-CANCEL the
   escape key, ON-SELECTION-CHANGE the newly highlighted item's :value (the
   live-preview hook)."
  [title description items current-value
   & {:keys [on-select on-cancel on-selection-change]}]
  (let [th (theme/get-current-theme)
        list-ref (h/ref)
        root (h/compile-tree
              [:container {}
               [:text {:padding-x 0 :padding-y 0}
                (theme/fg th :accent (theme/bold title))]
               (when (seq description)
                 [:spacer {:lines 1}])
               (when (seq description)
                 [:text {:padding-x 0 :padding-y 0}
                  (theme/fg th :muted description)])
               [:spacer {:lines 1}]
               [:select-list {:ref list-ref
                              :items (vec items)
                              :height (min (count items) 10)
                              :on-select (fn [item]
                                           (when-let [cb on-select]
                                             (cb (:value item))))
                              :on-escape on-cancel
                              :on-selection-change (fn [item]
                                                     (when-let [cb on-selection-change]
                                                       (cb (:value item))))}]
               [:spacer {:lines 1}]
               [:text {:padding-x 0 :padding-y 0}
                (theme/dim "  Enter to select · Esc to go back")]])
        sl (deref list-ref)]
    (when-let [idx (and sl
                        (first (keep-indexed (fn [i item]
                                               (when (= (:value item) current-value)
                                                 i))
                                             items)))]
      (select-list/select-list-set-selected! sl idx))
    (panel root list-ref)))

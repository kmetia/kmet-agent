(ns kmet.ui.tui.loaded-resources
  "LoadedResources — resource listing between the header and the chat (pi:
   showLoadedResources). One expandable section per resource group (Context,
   Skills, Prompts, Extensions, Themes): a '[Name]' heading with a dim
   compact comma list when collapsed, the full path list when expanded.
   Sections expand with the same app.tools.expand state as the header.
   Sections carry raw items; styling happens in render (theme-atom) so theme
   changes re-style without a rebuild."
  (:require [clojure.string :as str]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent LoadedResources nil [sections-atom expanded?-atom theme-atom cache-atom]
  (render [this width]
    (track! this width
      (let [th @theme-atom
            expanded? @expanded?-atom
            sections @sections-atom]
        (if (empty? sections)
          []
          (into []
                (mapcat (fn [{:keys [name items expanded-items]}]
                          (let [heading (theme/fg th :md-heading (str "[" name "]"))
                                compact (theme/dim (str "  " (str/join ", " items)))
                                expanded-body (str/join "\n" expanded-items)
                                body (if expanded? expanded-body compact)
                                lines (u/wrap-text-with-ansi (str heading "\n" body)
                                                             (max 1 width))]
                            (conj (vec lines) ""))))
                sections))))))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-loaded-resources
  "Create a LoadedResources component.
   Options:
     :theme     — Theme record (default dark-theme)
     :expanded? — initial expansion state (default false)"
  [& {:keys [theme expanded?]
      :or {theme theme/dark-theme expanded? false}}]
  (map->LoadedResources {:sections-atom (atom [])
                         :expanded?-atom (atom (boolean expanded?))
                         :theme-atom (atom theme)
                         :cache-atom (atom nil)}))

(defn loaded-resources-set-sections!
  "Replace the resource sections. Each section is
   {:name \"Skills\" :items [compact labels] :expanded-items [full lines]}."
  [comp sections]
  (and (reset! (:sections-atom comp) (vec sections))
       nil))

(defn loaded-resources-set-expanded!
  "Set the section expansion state (pi: toolOutputExpanded drives header and
   resource sections together)."
  [comp expanded?]
  (and (reset! (:expanded?-atom comp) (boolean expanded?))
       nil))

(defn loaded-resources-set-theme!
  "Switch the theme (live re-theme on theme changes)."
  [comp th]
  (and (reset! (:theme-atom comp) th)
       nil))

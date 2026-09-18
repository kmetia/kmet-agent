(ns kmet.app.ui.custom-message
  "CustomMessageComponent component — Pi's CustomMessageComponent.
   Wraps label and content Text children in a Box with custom-message-bg.
   Matching Pi architecture: Box handles padding/background/caching."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.app.ui.subs :as s]
            [kmet.app.ui.image-block :as image-block]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.macros :refer [track! track-deps defcomponent]]))

(declare apply-theme!)

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent CustomMessageComponent :custom
              [spacer          ;; Spacer(1) for top vertical gap (Pi-style)
               box             ;; Box wrapping the content
               inner-container  ;; Container holding label + content Text children
               label-atom
               content-atom
               images-atom     ;; content image blocks (:data / :mime-type maps)
               applied-theme-atom  ;; scratch: theme the box/children were built with
               output-pad-atom
               expanded-atom   ;; current expanded state (collapsible messages)
               collapsed-content-atom  ;; content when collapsed (nil = not collapsible)
               expanded-content-atom   ;; content when expanded (nil = not collapsible)
               cache-atom]
  (render [this width]
    (track! this width
      (let [s @spacer
            b @box
            ;; tracked read: the pad atom is shared by every message the chat
            ;; history owns — one reset! re-pads them all (the box setter
            ;; no-ops when the value is unchanged)
            pad (deref output-pad-atom)
            _ (box/box-set-padding-x! b pad)
            ;; tracked read: a palette switch re-applies once, then re-caches
            thm (deref s/theme-sub)
            _ (when-not (identical? thm @applied-theme-atom)
                (reset! applied-theme-atom thm)
                (apply-theme! this thm))]
        (track-deps @inner-container @label-atom @content-atom
                    @expanded-atom @collapsed-content-atom
                    @expanded-content-atom @s/image-settings-sub)
        (into [] (concat (protocols/render s width)
                         (protocols/render b width))))))
  (invalidate [_this]
    (protocols/invalidate @spacer)
    (protocols/invalidate @box))
  (dispose [_this]
    (protocols/dispose @spacer)
    (protocols/dispose @box)))

;; ─── Internal: rebuild the content children ────────────────────────────────

(defn- current-content
  "Pick the content to display: collapsed/expanded variant when collapsible,
   otherwise the plain content."
  [comp]
  (let [collapsed @(:collapsed-content-atom comp)
        expanded-content @(:expanded-content-atom comp)
        expanded @(:expanded-atom comp)]
    (cond
      (and (some? collapsed) (some? expanded-content)) (if expanded expanded-content collapsed)
      :else @(:content-atom comp))))

(defn- rebuild-content!
  "Rebuild the children inside the inner container with current content/theme.
   Label is plain Text (pi: Text), content is Markdown tinted
   custom-message-text (pi: Markdown with {color: customMessageText}). A
   Spacer(1) always separates the label from the content (pi: box.addChild
   label Text → new Spacer(1) → content Markdown)."
  [comp]
  (let [theme (deref s/theme-sub)
        ;; pi interpolates customType into `[${customType}]` — stringify so
        ;; keyword custom types (the extension API default) render instead
        ;; of crashing the `seq` check below
        label (let [l @(:label-atom comp)]
                (when (some? l) (if (keyword? l) (name l) (str l))))
        content (current-content comp)
        container @(:inner-container comp)
        label-children (when (seq label)
                         (let [label-str (theme/fg theme :custom-message-label
                                                   (theme/bold (str "[" label "]")))]
                           [(text/make-text label-str 0 0)
                            ;; pi: box.addChild(new Spacer(1)) — blank line
                            ;; between label and content
                            (spacer/make-spacer 1)]))
        content-children (when (seq content)
                           [(md/make-markdown content
                                              :theme (theme/get-markdown-theme theme)
                                              :default-style (fn [s]
                                                               (theme/fg theme :custom-message-text s))
                                              :padding-x 0)])
        image-children (mapcat (fn [img]
                                 [(spacer/make-spacer 1)
                                  (image-block/make-image-block
                                   (:data img) (:mime-type img)
                                   :fallback-style (fn [thm s] (theme/fg thm :custom-message-text s)))])
                               @(:images-atom comp))]
    ;; replace (not clear+add): the dropped children are disposed, so their
    ;; track! watches/cleanups do not outlive them (zombie-watcher invariant)
    (container/container-replace-children!
     container (concat label-children content-children image-children))))

;; ─── Public API (defined before make- to avoid forward ref) ──────────────
;; Label and plain content are fixed at construction (the atoms are the
;; single data home, read by persistence); only the collapsible variants
;; mutate afterwards.

(defn custom-message-set-collapsible-content!
  "Set collapsed/expanded content variants (pi: ExpandableText).
   Pass nil content to clear collapsible mode and fall back to plain content."
  [comp collapsed expanded]
  (reset! (:collapsed-content-atom comp) collapsed)
  (reset! (:expanded-content-atom comp) expanded)
  (reset! (:expanded-atom comp) false)
  (rebuild-content! comp))

(defn custom-message-collapsible?
  "True if the message has collapsed/expanded content variants."
  [comp]
  (and (some? @(:collapsed-content-atom comp))
       (some? @(:expanded-content-atom comp))))

(defn custom-message-set-expanded!
  [comp expanded?]
  (reset! (:expanded-atom comp) expanded?)
  (rebuild-content! comp))

(defn- apply-theme!
  "Apply THEME to the derived structures (box bg-fn + rebuilt children).
   Runs at construction and whenever theme-sub changes (render, apply-once)."
  [comp theme]
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :custom-message-bg %))
  (rebuild-content! comp))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-custom-message
  "Create a CustomMessageComponent.
   Options:
     :label       — bracketed label line (default nil)
     :content     — message text (default \"\")
     :images      — optional [{:data base64 :mime-type str} …] content images
     :theme       — theme map (default dark-theme)
     :output-pad  — horizontal padding (default 1); :output-pad-atom takes
                    a caller-owned atom instead (the chat history shares one)"
  [& {:keys [label content images output-pad output-pad-atom]
      :or {content "" output-pad 1}}]
  (let [pad-atom (or output-pad-atom (atom output-pad))
        inner-container (container/make-container)
        s (spacer/make-spacer 1)
        b (box/make-box @pad-atom 1 nil)]
    (box/box-add-child b inner-container)
    (let [comp (map->CustomMessageComponent {:kind :custom
                                             :spacer (atom s)
                                             :box (atom b)
                                             :inner-container (atom inner-container)
                                             :label-atom (atom label)
                                             :content-atom (atom content)
                                             :images-atom (atom (vec images))
                                             :applied-theme-atom (atom nil)
                                             :output-pad-atom pad-atom
                                             :expanded-atom (atom false)
                                             :collapsed-content-atom (atom nil)
                                             :expanded-content-atom (atom nil)
                                             :cache-atom (atom nil)})]
      ;; Set initial content; theme applies here from the global snapshot and
      ;; again on the first render from theme-sub if it changed meanwhile
      (rebuild-content! comp)
      (let [thm (theme/get-current-theme)]
        (reset! (:applied-theme-atom comp) thm)
        (apply-theme! comp thm))
      comp)))

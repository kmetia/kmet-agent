(ns kmet.app.ui.user-message
  "UserMessageComponent component — Pi's UserMessageComponent.
   Wraps a Markdown child in a Box with user-message-bg background; text is
   tinted user-message-text via the markdown component's :default-style (pi:
   Markdown with defaultTextStyle {color: userMessageText}). Box handles
   padding/background/caching, Markdown handles parsing and word-wrap.
   Extension markdown transformers apply to the text per render (pi:
   createMarkdownTransform(\"user\", false))."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.app.ui.subs :as s]
            [kmet.app.ui.image-block :as image-block]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.macros :refer [track! track-deps defcomponent]]
            [kmet.app.extensions :as extensions]))

(declare apply-theme!)

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent UserMessageComponent :user
              [box             ;; Box wrapping the content
               content-atom    ;; Box child: the Markdown, or a Container of Markdown + image blocks
               markdown-comp   ;; Markdown child component
               text-atom       ;; raw text (uncolored, for backward compat)
               applied-theme-atom  ;; scratch: theme the box/markdown were built with
               output-pad-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [b @box
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
        (track-deps @text-atom @s/image-settings-sub)
        (protocols/render b width))))
  (invalidate [_this]
    (protocols/invalidate @box))
  (dispose [_this]
    (protocols/dispose @box)))

;; ─── Theme application (defined before make-user-message; forward-declared
;; ─── for the render method's apply-once use)

(defn- apply-theme!
  "Apply THEME to the derived structures (box bg-fn + markdown tint). Runs at
   construction and whenever theme-sub changes (render, apply-once)."
  [comp theme]
  ;; Update bg-fn on existing box and re-tint the markdown child
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :user-message-bg %))
  (let [m @(:markdown-comp comp)]
    (md/markdown-set-theme! m (theme/get-markdown-theme theme))
    (md/markdown-set-default-style! m
                                    (fn [s] (theme/fg theme :user-message-text s)))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn- make-user-transform
  "Extension markdown transform for user messages (pi:
   createMarkdownTransform(\"user\", false)): transformers apply in
   registration order at render time (each text/width change re-runs them),
   throwing transformers are skipped; the transformer list is read at apply
   time so late registrations take effect on the next render."
  []
  (fn [text {:keys [available-width]}]
    (extensions/apply-markdown-transformers
     text {:message-type :user
           :is-streaming false
           :available-width available-width})))

(defn- make-content
  "Box child for a user message: the Markdown alone, or a Container with the
   Markdown plus one image block per attached image (image attachments
   render inline, pi: prompt images)."
  [markdown images]
  (if (seq images)
    (let [c (container/make-container)]
      (container/container-add-child c markdown)
      (doseq [img images]
        (container/container-add-child c (spacer/make-spacer 1))
        (container/container-add-child
         c (image-block/make-image-block
            (:data img) (:mime-type img)
            :fallback-style (fn [thm s] (theme/fg thm :user-message-text s)))))
      c)
    markdown))

(defn make-user-message
  "THEME is no longer taken: styling subscribes to ui.subs/theme-sub and
   follows palette changes live (Stage 5).
   :images — optional [{:data base64 :mime-type str} …] message attachments."
  [& {:keys [text images output-pad output-pad-atom]
      :or {text "" output-pad 1}}]
  (let [t0 (theme/get-current-theme)
        ;; the pad lives in an atom: the chat history passes one shared atom
        ;; so every message re-pads on a single reset!; a bare number is
        ;; private to this component (standalone callers, tests)
        pad-atom (or output-pad-atom (atom output-pad))
        m (md/make-markdown ""
                            :theme (theme/get-markdown-theme t0)
                            :default-style (fn [s]
                                             (theme/fg t0 :user-message-text s))
                            :transform (make-user-transform)
                            :padding-x 0)
        content (make-content m images)
        b (box/make-box @pad-atom 1 nil)
        comp (map->UserMessageComponent {:kind :user
                                         :box (atom b)
                                         :content-atom (atom content)
                                         :markdown-comp (atom m)
                                         :text-atom (atom text)
                                         :applied-theme-atom (atom nil)
                                         :output-pad-atom pad-atom
                                         :cache-atom (atom nil)})]
    (box/box-add-child b content)
    ;; Set initial text (content is fixed at construction — user messages
    ;; never mutate); theme applies on the first render from theme-sub
    ;; (apply-once pattern), seeded here so pre-render snapshots are styled
    (reset! (:text-atom comp) text)
    (md/markdown-set-text! m text)
    (let [thm (theme/get-current-theme)]
      (reset! (:applied-theme-atom comp) thm)
      (apply-theme! comp thm))
    comp))

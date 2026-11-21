(ns kmet.ui.tui.image-block
  "Inline image rendering for chat content — one block per image (pi:
   ToolExecutionComponent's Image child + getTextOutput's imageFallback).

   A block renders the terminal image when display is enabled (the
   :show-images setting AND terminal protocol support) and the styled
   imageFallback text indicator otherwise. It subscribes to the shared
   ui.subs/image-settings-sub, so a /settings change re-renders every
   mounted block — tool results and message images alike."
  (:require [kmet.ui.tui.subs :as s]
            [kmet.tui.terminal-image :as timg]
            [kmet.tui.components.image :as ic]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]))

;; ─── Content helper ────────────────────────────────────────────────────────

(defn content-images
  "Image blocks of a message content value (a string, or a vector of content
   blocks — pi: user message attachments, image tool results) as
   [{:data base64 :mime-type str} …], [] when there are none. Block :type
   may be the keyword :image or the string \"image\" (session data mixes
   both); malformed blocks are dropped."
  [content]
  (if (or (nil? content) (string? content))
    []
    (into []
          (comp (filter #(contains? #{:image "image"} (:type %)))
                (keep (fn [b]
                        (when (and (:data b) (:mime-type b))
                          {:data (:data b) :mime-type (:mime-type b)}))))
          content)))

(defn images-enabled?
  "Whether SETTINGS render images as terminal images: the :show-images
   setting AND terminal protocol support (pi: caps.images && showImages —
   otherwise the imageFallback text indicator is drawn instead)."
  [settings]
  (and (:show-images settings)
       (boolean (:images (timg/get-capabilities)))))

(defn- kitty-image-id
  "The block's Kitty image id, allocated on the first terminal-image render
   and reused by every later one. A fresh id per reflow would make the TUI
   diff delete and re-transmit the image each time. Read through a fn, not
   inside the track! body: the one-time allocation must not self-invalidate
   the render cache (ImageComponent's current-image-id rule)."
  [comp]
  (or @(:image-id-atom comp)
      (let [id (timg/allocate-image-id)]
        (reset! (:image-id-atom comp) id)
        id)))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent ImageBlock nil
              [data mime-type filename dimensions fallback-style image-id-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [settings (deref s/image-settings-sub)
            theme (deref s/theme-sub)
            style (or fallback-style (fn [_ s] s))]
        (if (images-enabled? settings)
          (let [image (ic/make-image data mime-type
                                     {:fallback-color #(style theme %)}
                                     :max-width-cells (:image-width-cells settings)
                                     :filename filename
                                     :image-id (kitty-image-id this))]
            (core/render-and-dispose image width))
          [(utils/truncate-to-width
            (style theme (timg/image-fallback mime-type
                                              :dimensions dimensions
                                              :filename filename))
            width)])))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-image-block
  "Create an inline image block. OPTS:
     :filename       — name shown in the text fallback
     :fallback-style — (fn [theme text] styled) applied to the fallback
                       text and to the image component's render-failure
                       fallback (pi: fallbackColor)"
  [data mime-type & {:keys [filename fallback-style]}]
  (map->ImageBlock {:data data
                    :mime-type mime-type
                    :filename filename
                    :dimensions (or (timg/get-image-dimensions data mime-type)
                                    {:width-px 800 :height-px 600})
                    :fallback-style fallback-style
                    :image-id-atom (atom nil)
                    :cache-atom (atom nil)}))
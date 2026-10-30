(ns kmet.ai.message
  "Provider message and content-block shapes: the malli schemas and the two
   readers a block shape needs. Messages cross the session-file and wire
   boundaries, so their shape is described here rather than ad hoc in every
   caller; the converters stay with their callers (kmet.app.loop.message) and
   the providers."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def TextBlock
  [:map {:closed false}
   [:type [:= :text]]
   [:text :string]])

(def ThinkingBlock
  [:map {:closed false}
   [:type [:= :thinking]]
   [:thinking [:or :string :nil]]])

(def ImageBlock
  [:map {:closed false}
   [:type [:= :image]]
   [:data [:or :string :nil]]
   [:mime-type {:optional true} [:or :string :nil]]])

(def ToolCallBlock
  [:map {:closed false}
   [:type [:= :tool_call]]
   [:id [:or :string :nil]]
   [:name [:or :string :nil]]
   [:arguments {:optional true} [:or :map :nil]]])

(def ToolUseBlock
  [:map {:closed false}
   [:type [:= :tool_use]]
   [:id [:or :string :nil]]
   [:name [:or :string :nil]]
   [:input {:optional true} [:or :map :nil]]])

(def ToolResultBlock
  [:map {:closed false}
   [:type [:= :tool_result]]
   [:tool_use_id {:optional true} [:or :string :nil]]
   [:tool-call-id {:optional true} [:or :string :nil]]
   [:content [:or :string :nil [:vector :any]]]
   [:is-error {:optional true} :boolean]])

(def ContentBlock
  [:multi {:dispatch :type}
   [:text TextBlock]
   [:thinking ThinkingBlock]
   [:image ImageBlock]
   [:tool_call ToolCallBlock]
   [:tool_use ToolUseBlock]
   [:tool_result ToolResultBlock]])

(def Message
  [:map {:closed false}
   [:role keyword?]
   [:content [:or :string :nil [:vector ContentBlock]]]
   [:tool-calls {:optional true} [:or :nil [:vector :any]]]
   [:tool-results {:optional true} [:or :nil [:vector :any]]]
   [:stop-reason {:optional true} [:or :keyword :string :nil]]
   [:error-message {:optional true} [:or :string :nil]]
   [:timestamp {:optional true} [:or :string :nil]]])

(def messages-schema [:vector Message])

(defn validate-message
  [m]
  (m/validate Message m))

(defn explain-message
  [m]
  (me/humanize (m/explain Message m)))

;; ─── Block readers ─────────────────────────────────────────────────────────

(defn content-text
  "Extract plain text from a message content block vector.
   A block has {:type :text :text \"...\"} or {:type \"text\" :text \"...\"}."
  [content]
  (str/join (for [b content
                  :when (or (= (:type b) :text)
                            (= (:type b) "text"))]
              (:text b))))

(defn image-block?
  "True if a content block is an image block (kmet canonical
   {:type :image :data base64 :mime-type str}, matching pi's read tool
   {type: \"image\", data, mimeType} format)."
  [b]
  (or (= (:type b) :image) (= (:type b) "image")))

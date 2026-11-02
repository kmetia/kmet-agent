(ns kmet.app.session.schema
  "Malli schemas for session files: the header and the entry union.

   Used at the session boundary by the strict load pass: with it on
   (`kmet.app.session.store/report-wrong-shapes!`, which `--debug` sets) a
   wrong-shape entry is reported and skipped and a wrong-shape header is
   reported. Off — the default — a session file that parses is loaded as
   written, because dropping data is opt-in."
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn- common-entry
  "Shared fields carried by every runtime/appended entry."
  [role & role-specific]
  (into [:map {:closed false}
         [:id :string]
         [:parent-id {:optional true} [:or :nil :string]]
         [:timestamp {:optional true} [:or :nil :string]]
         [:role [:= role]]]
        role-specific))

(def AssistantEntry
  (common-entry :assistant
                [:content {:optional true} [:or :string :nil [:vector :any]]]
                [:tool-calls {:optional true} [:or :nil [:vector :any]]]
                [:tool-results {:optional true} [:or :nil [:vector :any]]]
                [:stop-reason {:optional true} [:or :keyword :string :nil]]
                [:error-message {:optional true} [:or :string :nil]]))

(def UserEntry
  (common-entry :user
                [:content {:optional true} [:or :string :nil [:vector :any]]]))

(def ToolEntry
  (common-entry :tool
                [:content {:optional true} [:or :string :nil [:vector :any]]]
                [:tool-name {:optional true} [:or :string :nil]]
                [:tool-call-id {:optional true} [:or :string :nil]]
                [:is-error {:optional true} :boolean]
                [:result-tokens {:optional true} [:or :nil :int]]))

(def BashEntry
  (common-entry :bash
                [:command {:optional true} [:or :string :nil]]
                [:output {:optional true} [:or :string :nil]]
                [:exit-code {:optional true} [:or :nil :int]]
                [:full-output-path {:optional true} [:or :string :nil]]
                [:exclude-from-context? {:optional true} :boolean]))

(def ThinkingLevelChangeEntry
  (common-entry :thinking-level-change
                [:thinking-level {:optional true} [:or :keyword :string :nil]]))

(def ModelChangeEntry
  (common-entry :model-change
                [:provider {:optional true} [:or :keyword :string :nil]]
                [:model {:optional true} [:or :string :nil]]))

(def TurnStartEntry
  "A turn boundary written into the log before the entries it opens. The
   `:turn-start` *event* is the inner loop's turn index; this role is the
   user-facing turn — a typed prompt or an agent.continue — and it projects to
   no wire message (the model never sees it)."
  (common-entry :turn-start
                [:turn-id {:optional true} [:or :string :nil]]
                [:reason {:optional true} [:or :keyword :string :nil]]))

(def SessionInfoEntry
  (common-entry :session_info
                [:name {:optional true} [:or :string :nil]]))

(def CompactionEntry
  (common-entry :compaction
                [:summary {:optional true} [:or :string :nil]]))

(def ContextEditEntry
  (common-entry :context-edit
                [:target-id {:optional true} [:or :string :nil]]
                [:replacement {:optional true} [:or :nil :map]]))

(def CustomEntry
  (common-entry :custom
                [:custom-type {:optional true} [:or :string :keyword :nil]]
                [:data {:optional true} :any]))

(def LabelEntry
  (common-entry :label
                [:target-id {:optional true} [:or :string :nil]]
                [:label {:optional true} [:or :string :nil]]))

(def Entry
  [:multi {:dispatch :role}
   [:assistant AssistantEntry]
   [:user UserEntry]
   [:tool ToolEntry]
   [:bash BashEntry]
   [:turn-start TurnStartEntry]
   [:thinking-level-change ThinkingLevelChangeEntry]
   [:model-change ModelChangeEntry]
   [:session_info SessionInfoEntry]
   [:compaction CompactionEntry]
   [:context-edit ContextEditEntry]
   [:custom CustomEntry]
   [:label LabelEntry]])

(def Header
  [:map {:closed false}
   [:type [:= :session]]
   [:version [:int {:min 1}]]
   [:id :string]
   [:created-at {:optional true} [:or :string :nil]]
   [:cwd {:optional true} [:or :string :nil]]
   [:parent-session {:optional true} [:or :string :nil]]])

(defn validate-header?
  [h]
  (m/validate Header h))

(defn explain-header
  [h]
  (me/humanize (m/explain Header h)))

(defn validate-entry?
  [e]
  (m/validate Entry e))

(defn explain-entry
  [e]
  (me/humanize (m/explain Entry e)))

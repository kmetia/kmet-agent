(ns kmet.ui.tui.fork-selector
  "Fork-from-message selector (pi: UserMessageSelectorComponent): rows are
   the session's user messages — two lines each (cursor + message text,
   then a muted \"Message N of M\" position line), the selection bold with
   an accent cursor, navigation wraps at the ends and starts at the most
   recent message. Mounted in place of the editor (pi: showSelector);
   selecting one calls ON-SELECT with the entry id — the mode performs the
   fork (pi: the component emits the selection, interactive-mode forks)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.subs :as s]
            [kmet.reakt.core :as r]
            [kmet.tui.hikap :as h]
            [kmet.tui.keybindings :as kb]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.components.filter-panel :as fp]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.ui.tui.list-screen :as ls]))

(def ^:private max-visible
  "Max messages visible (pi: UserMessageList.maxVisible)."
  10)

(def ^:private description
  "Select a user message to copy the active path up to that point into a new session")

(defn- collect-messages
  "The session's user messages as {:id :text} in chronological order (pi:
   getUserMessagesForForking iterates ALL entries — every branch — not just
   the active path)."
  [sess]
  (->> @(:entries sess)
       (filter #(= :user (:role %)))
       (keep (fn [e]
               (let [t (session/session-entry-text e)]
                 (when (seq t)
                   {:id (:id e) :text t}))))
       vec))

(defcomponent ForkMessageList nil
              [messages-atom selected-idx-atom on-select-atom on-cancel-atom cache-atom]

  (render [this width]
    (track! this width
      (let [messages @messages-atom
            n (count messages)]
        (if (zero? n)
          [(theme/fg (theme/get-current-theme) :muted "  No user messages found")]
          (let [th (theme/get-current-theme)
                selected @selected-idx-atom
                [start end] (ls/window selected max-visible n)
                row-lines (mapcat
                           (fn [i]
                             (let [{:keys [text]} (nth messages i)
                                   selected? (= i selected)
                                   ;; normalize to a single line (pi: replace \n)
                                   normalized (str/trim (str/replace (str text) #"\n" " "))
                                   cursor (if selected? (theme/fg th :accent "› ") "  ")
                                   msg (u/truncate-to-width normalized (- width 2))
                                   message-line (str cursor
                                                     (if selected? (theme/bold msg) msg))
                                   metadata-line (theme/fg th :muted
                                                           (str "  Message " (inc i) " of " n))]
                               [message-line metadata-line ""]))
                           (range start end))
                scroll (when-let [ind (ls/scroll-indicator start end selected n)]
                         [(theme/fg th :muted ind)])]
            (vec (concat row-lines scroll)))))))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          n (count @messages-atom)]
      (cond
        ;; Up — previous (older) message, wrapping to the newest at the top
        ;; (pi: UserMessageList.handleInput wraps both ends; the shared
        ;; filter-panel step clamps a stale index first)
        (kb/matches-key kmgr data "tui.select.up")
        (do (fp/select-step! selected-idx-atom :wrap -1 n)
            nil)

        ;; Down — next (newer) message, wrapping to the oldest at the bottom
        (kb/matches-key kmgr data "tui.select.down")
        (do (fp/select-step! selected-idx-atom :wrap 1 n)
            nil)

        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when (pos? n)
              (when-let [cb @on-select-atom]
                (cb (:id (nth @messages-atom @selected-idx-atom)))))
            nil)

        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb))
            nil)

        :else nil))))

(defn show-fork-selector
  "Select a user message to fork from (pi: UserMessageSelectorComponent).
   ON-SELECT receives the chosen entry id; the caller performs the fork."
  [cs on-select]
  (let [sess (runtime/session (:engine cs))]
    (cond
      (nil? sess)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant :content "No active session."})

      (not (fs/exists? (:file sess)))
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content "Wait for the first assistant response before forking."})

      :else
      (let [messages (collect-messages sess)]
        (if (empty? messages)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant
                                                   :content "No messages to fork from."})
          (let [;; pi: start at the most recent message unless an initial id
                ;; is given
                messages-atom (atom messages)
                selected-idx-atom (atom (dec (count messages)))
                on-select-atom (atom nil)
                on-cancel-atom (atom nil)
                list (map->ForkMessageList
                      {:messages-atom messages-atom
                       :selected-idx-atom selected-idx-atom
                       :on-select-atom on-select-atom
                       :on-cancel-atom on-cancel-atom
                       :cache-atom (atom nil)})
                ;; one color-fn for the panel's lifetime: a changed prop
                ;; rebuilds the border, so a fresh closure per body run would
                ;; churn it on every state change
                border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
                ;; The panel is a reactive body (dsl.md): the spacer/title/
                ;; border chrome re-derives on a theme switch (the tracked
                ;; theme-sub read); the message list splices foreign (the
                ;; focus target).
                panel (h/root
                       (fn [_props]
                         (let [th (r/tracked-deref s/theme-sub)]
                           [:container {}
                            [:spacer {:lines 1}]
                            [:text {:padding-x 1 :padding-y 0} (theme/bold "Fork from Message")]
                            [:text {:padding-x 1 :padding-y 0} (theme/fg th :muted description)]
                            [:spacer {:lines 1}]
                            [:dynamic-border {:color-fn border-fn}]
                            [:spacer {:lines 1}]
                            list
                            [:spacer {:lines 1}]
                            [:dynamic-border {:color-fn border-fn}]])))
                ;; pi: close — restore the editor and unwind the panel: the
                ;; frame's DSL chrome via dock/dispose! (leave then
                ;; dispose), the spliced list explicitly (the tree does not
                ;; own it)
                close! (fn []
                         (dock/close! cs panel)
                         (protocols/dispose list))]
            (reset! on-select-atom
                    (fn [entry-id]
                      (close!)
                      (on-select entry-id)))
            (reset! on-cancel-atom
                    (fn [] (close!)))
            ;; pi: showSelector — mount the panel, focus the list
            ;; (focus: selector.getMessageList())
            (dock/mount! cs panel {:focus-target list})))))))
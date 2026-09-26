(ns kmet.app.ui.fork-selector
  "Fork-from-message selector (pi: UserMessageSelectorComponent): rows are
   the session's user messages — two lines each (cursor + message text,
   then a muted \"Message N of M\" position line), the selection bold with
   an accent cursor, navigation wraps at the ends and starts at the most
   recent message. Mounted in place of the editor (pi: showSelector);
   selecting one calls ON-SELECT with the entry id — the mode performs the
   fork (pi: the component emits the selection, interactive-mode forks)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.session :as session]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.tui.hiccup :as h]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent track!]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

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
                start (max 0 (min (- selected (quot max-visible 2)) (- n max-visible)))
                end (min (+ start max-visible) n)
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
                scroll (when (or (pos? start) (< end n))
                         [(theme/fg th :muted
                                    (str "  (" (inc selected) "/" n ")"))])]
            (vec (concat row-lines scroll)))))))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          n (count @messages-atom)]
      (cond
        ;; Up — previous (older) message, wrapping to the newest at the top
        ;; (pi: UserMessageList.handleInput wraps both ends)
        (kb/matches-key kmgr data "tui.select.up")
        (do (when (pos? n)
              (swap! selected-idx-atom #(if (zero? %) (dec n) (dec %))))
            nil)

        ;; Down — next (newer) message, wrapping to the oldest at the bottom
        (kb/matches-key kmgr data "tui.select.down")
        (do (when (pos? n)
              (swap! selected-idx-atom #(if (= % (dec n)) 0 (inc %))))
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
  (let [sess @(:session-atom cs)]
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
          (let [th (theme/get-current-theme)
                ;; pi: start at the most recent message unless an initial id
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
                ;; The panel is a compiled hiccup tree (dsl.md): the
                ;; spacer/title/border chrome is DSL-owned; the message list
                ;; splices foreign (the focus target).
                panel (h/compile-tree
                       [:container {}
                        [:spacer {:lines 1}]
                        [:text {:padding-x 1 :padding-y 0} (theme/bold "Fork from Message")]
                        [:text {:padding-x 1 :padding-y 0} (theme/fg th :muted description)]
                        [:spacer {:lines 1}]
                        [:dynamic-border {:color-fn #(theme/fg th :accent %)}]
                        [:spacer {:lines 1}]
                        list
                        [:spacer {:lines 1}]
                        [:dynamic-border {:color-fn #(theme/fg th :accent %)}]])
                ;; late binding: the list callbacks reach done via this atom
                sel-atom (atom nil)
                ;; pi: done() — restore the editor and unwind the panel: the
                ;; compiled frame's DSL chrome via dispose-tree!, the spliced
                ;; list explicitly (the tree does not own it)
                close! (fn []
                         ((:done @sel-atom))
                         (h/dispose-tree! panel)
                         (protocols/dispose list))]
            (reset! on-select-atom
                    (fn [entry-id]
                      (close!)
                      (on-select entry-id)))
            (reset! on-cancel-atom
                    (fn [] (close!)))
            ;; pi: showSelector — mount the panel, focus the list
            ;; (focus: selector.getMessageList())
            (reset! sel-atom {:done (dock/mount! cs panel list)})))))))

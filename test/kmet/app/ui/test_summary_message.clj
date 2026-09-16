(ns kmet.app.ui.test-summary-message
  "Tests for the compaction/branch summary message (pi:
   CompactionSummaryMessageComponent / BranchSummaryMessageComponent):
   collapsible custom-message-bg boxes, collapsed by default, flipped by
   the shared ctrl+o tool-display mode atom, with pi's collapse/expand
   wording (token count + key hint)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.chat-history :as ch]
            [kmet.app.ui.summary-message :as sm]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :as macros]
            [kmet.tui.theme :as theme]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain-lines [component width]
  (mapv strip-ansi (core/render component width)))

(defn- joined [component width]
  (str/join "\n" (plain-lines component width)))

;; The hint text comes from the keybindings manager; install one so the
;; collapsed line reads "ctrl+o to expand" rather than the empty fallback.
(defn- with-keybindings [f]
  (let [prev (kb/get-global-keybindings)]
    (try
      (kb/set-global-keybindings! (app-kb/create-agent-keybindings-manager
                                   "target/test-summary-message-kb"))
      (f)
      (finally (kb/set-global-keybindings! prev)))))

(t/use-fixtures :each with-keybindings)

;; ─── The component ────────────────────────────────────────────────────────

(deftest test-collapsed-compaction-is-a-one-liner
  (let [c (sm/make-summary-message
           :variant :compaction
           :summary "the summary body"
           :tokens-before 12345
           :tools-expanded-atom (atom :collapsed))
        lines (plain-lines c 70)]
    (is (some #(str/includes? % "[compaction]") lines))
    (is (some #(str/includes? % "Compacted from 12,345 tokens (ctrl+o to expand)") lines)
        "pi wording: token count + dim expand hint")
    (is (not-any? #(str/includes? % "the summary body") lines)
        "the summary text is hidden while collapsed")))

(deftest test-expanded-compaction-shows-heading-and-body
  (let [c (sm/make-summary-message
           :variant :compaction
           :summary "the summary body"
           :tokens-before 12345
           :tools-expanded-atom (atom :expanded))
        text (joined c 70)]
    (is (str/includes? text "[compaction]"))
    (is (str/includes? text "Compacted from 12,345 tokens") "the heading")
    (is (str/includes? text "the summary body") "the body renders as Markdown")
    (is (not (str/includes? text "to expand"))
        "the hint belongs to the collapsed form")))

(deftest test-branch-variant
  (testing "collapsed"
    (let [text (joined (sm/make-summary-message
                        :variant :branch
                        :summary "gone down the other path"
                        :tools-expanded-atom (atom :collapsed))
                       70)]
      (is (str/includes? text "[branch]"))
      (is (str/includes? text "Branch summary (ctrl+o to expand)"))
      (is (not (str/includes? text "gone down the other path")))))
  (testing "expanded"
    (let [text (joined (sm/make-summary-message
                        :variant :branch
                        :summary "gone down the other path"
                        :tools-expanded-atom (atom :expanded))
                       70)]
      (is (str/includes? text "Branch Summary") "pi heading, title case")
      (is (str/includes? text "gone down the other path")))))

(deftest test-missing-tokens-before-degrades-gracefully
  ;; legacy entries may lack :tokens-before — pi would print `undefined`;
  ;; the label falls back to a generic phrase instead
  (let [text (joined (sm/make-summary-message
                      :variant :compaction
                      :summary "old summary"
                      :tokens-before nil
                      :tools-expanded-atom (atom :collapsed))
                     70)]
    (is (str/includes? text "Compacted from unknown tokens (ctrl+o to expand)")))
  (let [text (joined (sm/make-summary-message
                      :variant :compaction
                      :summary "old summary"
                      :tokens-before nil
                      :tools-expanded-atom (atom :expanded))
                     70)]
    (is (str/includes? text "Compacted from unknown tokens"))))

(deftest test-non-numeric-tokens-before-does-not-crash
  ;; the session loader does not validate entries — a string token count
  ;; (hand-edited/corrupt session) must not crash the replay
  (let [text (joined (sm/make-summary-message :variant :compaction :summary "s"
                                              :tokens-before "123"
                                              :tools-expanded-atom (atom :collapsed))
                     70)]
    (is (str/includes? text "Compacted from 123 tokens"))))

(deftest test-the-shared-toggle-drives-it
  ;; pi: setExpanded(toolOutputExpanded) — one atom flips every summary
  (let [toggle (atom :collapsed)
        a (sm/make-summary-message :variant :compaction :summary "one"
                                   :tokens-before 100 :tools-expanded-atom toggle)
        b (sm/make-summary-message :variant :branch :summary "two"
                                   :tools-expanded-atom toggle)]
    (is (not (str/includes? (joined a 70) "one")))
    (is (not (str/includes? (joined b 70) "two")))
    (reset! toggle :expanded)
    (is (str/includes? (joined a 70) "one") "flipped by the shared toggle")
    (is (str/includes? (joined b 70) "two") "both summaries flip together")
    (reset! toggle :collapsed)
    (is (not (str/includes? (joined a 70) "one")) "and back")))

(deftest test-quiet-projects-to-collapsed
  ;; :quiet has no summary special case — it collapses, like the info
  ;; banner and loaded resources
  (let [toggle (atom :quiet)
        c (sm/make-summary-message :variant :compaction :summary "quiet body"
                                   :tokens-before 5 :tools-expanded-atom toggle)]
    (is (not (str/includes? (joined c 70) "quiet body")))
    (is (str/includes? (joined c 70) "[compaction]"))
    (reset! toggle :expanded)
    (is (str/includes? (joined c 70) "quiet body"))))

(deftest test-theme-switch-rebuilds-the-children
  ;; the box background and the markdown tint come from the theme; a palette
  ;; switch must re-apply them (apply-once on theme-sub)
  (let [c (sm/make-summary-message :variant :compaction :summary "body"
                                   :tokens-before 1
                                   :tools-expanded-atom (atom :collapsed))
        before (core/render c 70)]
    (is (some #(str/includes? % "\u001b[") before) "themed output carries ANSI")
    (reset! theme/theme-atom (theme/get-theme "light"))
    (try
      (let [after (core/render c 70)]
        (is (not= before after) "the palette change re-rendered the message")
        (is (str/includes? (str/join after) "[compaction]") "still the collapsed line"))
      (finally (reset! theme/theme-atom (theme/get-theme "dark"))))))

(deftest test-expand-collapse-rebuild-no-watch-leak
  ;; rebuild-content! replaces the label/content children on every toggle;
  ;; the replaced children must be disposed (their track! watches would
  ;; otherwise accumulate per toggle)
  (let [watchers #(count @(deref #'macros/watch-registry))
        expand! (fn [comp expanded?]
                  (reset! (:expanded-atom comp) expanded?)
                  ((var-get #'kmet.app.ui.summary-message/rebuild-content!) comp expanded?)
                  (core/render comp 70))
        comp (sm/make-summary-message :variant :compaction :summary "body"
                                      :tokens-before 1
                                      :tools-expanded-atom (atom :collapsed))]
    (core/render comp 70)
    (let [baseline (watchers)]
      (dotimes [i 6] (expand! comp (odd? i)))
      (is (= baseline (watchers))
          "toggling expansion does not accumulate watches"))))

;; ─── Wiring into the chat history ─────────────────────────────────────────

(deftest test-chat-history-dispatches-summary-roles
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :compaction :summary "sum body"
                                      :tokens-before 4200})
    (ch/chat-history-add-message! ch {:role :branch-summary :summary "branch body"})
    (let [text (str/join "\n" (plain-lines ch 70))]
      (is (str/includes? text "[compaction]"))
      (is (str/includes? text "Compacted from 4,200 tokens (ctrl+o to expand)"))
      (is (str/includes? text "[branch]"))
      (is (str/includes? text "Branch summary (ctrl+o to expand)")))
    (testing "the component carries the :summary kind"
      (is (= :summary (:kind (:component (first @(:messages-atom ch)))))))
    (testing "the shared ctrl+o toggle expands both"
      (is (true? (ch/chat-history-toggle-tool-expanded! ch)))
      (let [text (str/join "\n" (plain-lines ch 70))]
        (is (str/includes? text "sum body"))
        (is (str/includes? text "branch body"))))))

(deftest test-chat-history-dispatches-custom-role
  ;; a bare :custom message (no registered renderer) renders the labeled box
  ;; (pi: addMessageToChat case "custom" → CustomMessageComponent default)
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :custom :custom-type "note"
                                      :display true :content "hello"})
    (is (= :custom (:kind (:component (first @(:messages-atom ch))))))
    (is (str/includes? (joined ch 70) "[note]"))
    (is (str/includes? (joined ch 70) "hello"))))

(deftest test-chat-history-custom-role-flattens-text-blocks
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :custom :custom-type "note"
                                      :display true
                                      :content [{:type :text :text "one"}
                                                {:type :image :data "x" :mime-type "image/png"}
                                                {:type :text :text "two"}]})
    (let [text (joined ch 70)
          comp (:component (first @(:messages-atom ch)))]
      (is (str/includes? text "one"))
      (is (str/includes? text "two"))
      (is (= [{:data "x" :mime-type "image/png"}] @(:images-atom comp))
          "image blocks embedded in :content ride the component"))))

(deftest test-chat-history-custom-role-honors-display-flag
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :custom :custom-type "note"
                                      :display false :content "hidden"})
    (is (empty? @(:messages-atom ch))))
  (testing "a direct add with no :display has nothing to gate on and renders"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :custom :custom-type "note"
                                        :content "no flag"})
      (is (seq @(:messages-atom ch))))))

(deftest test-keyword-custom-type-renders
  ;; regression: a keyword :label crashed CustomMessageComponent's seq check
  ;; (`seq` on a keyword throws) — the extension API defaults to :custom
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :custom :custom-type :note
                                      :display true :content "hi"})
    (is (str/includes? (joined ch 70) "[note]"))))

(deftest test-chat-history-dispatches-notice-role
  ;; pi: addCompactionCostNotice — a plain warning line, no box, no prefix
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :notice :style :warning
                                      :content "Compaction: 12k tokens billed (~$0.01)"})
    (let [text (joined ch 70)]
      (is (str/includes? text "Compaction: 12k tokens billed"))
      (is (not (str/includes? text "Warning:")) "no Warning: prefix"))
    (is (empty? (ch/chat-history-get-messages ch))
        "notices are UI-only/derived, not chat messages")))

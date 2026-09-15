(ns kmet.app.ui.test-chat-history
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.macros :as macros]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.ui :as ui]
            [kmet.app.ui.chat-history :as ch]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain-lines
  "Render component and strip ANSI codes for easier testing."
  [component width]
  (mapv strip-ansi (core/render component width)))

(deftest test-create
  (testing "create chat history component"
    (let [ch (ch/make-chat-history)]
      (is (some? ch))
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch))))))

(deftest test-add-message
  (testing "add a user message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (is (= 1 (count (ch/chat-history-get-messages ch))))
      (is (= :user (:role (first (ch/chat-history-get-messages ch)))))
      (is (= "hello" (:content (first (ch/chat-history-get-messages ch))))))))

(deftest test-render-user
  (testing "render a user message with Pi-style user-message-bg box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "Hello world"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"Hello world" %) lines)
            "User message content should be visible")
        ;; Should not have old-style headers
        (is (not-any? #(re-find #"─── You" %) lines)
            "Should not have old-style '─── You' header")))))

(deftest test-render-assistant
  (testing "render an assistant message with Pi-style plain text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :assistant :content "Hi there!"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"Hi there!" %) lines)
            "Assistant message content should be visible")
        (is (not-any? #(re-find #"─── Assistant" %) lines)
            "Should not have old-style '─── Assistant' header")))))

(deftest test-render-tool
  (testing "render a tool message with Pi-style box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :tool :name "my-tool" :content "file contents" :is-error false})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"my-tool" %) lines))
        (is (some #(re-find #"file contents" %) lines))))))

(deftest test-render-tool-error
  (testing "render a tool error message with Pi-style box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :tool :name "my-tool" :content "command not found" :is-error true})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"my-tool" %) lines))
        (is (some #(re-find #"command not found" %) lines))))))

(deftest test-render-info
  (testing "render an info message with Pi-style custom-message-bg"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "system" :content "Info message"})
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"system" %) lines)
            "Info label should be visible")
        (is (some #(re-find #"Info message" %) lines)
            "Info content should be visible")))))

(deftest test-streaming
  (testing "streaming text renders with Pi-style"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Hello")
      (ch/chat-history-append-thinking-text! ch "Thinking...")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Hello" %) lines))
        (is (some #(re-find #"Thinking" %) lines))))))

(deftest test-append-streaming
  (testing "append streaming text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Hello ")
      (ch/chat-history-append-streaming-text! ch "world")
      (is (= "Hello world" (ch/chat-history-get-streaming-text ch))))))

(deftest test-append-streaming-schedules-frame
  ;; Stage 5 (§3.4): on-agent-text retired its manual request-render — the
  ;; append swap must reach the assistant component's track! watch, whose
  ;; invalidate-cache schedules EXACTLY ONE frame per real change. Requires a
  ;; rendered-once placeholder so the watches are installed.
  (let [ch (ch/make-chat-history)
        fired (atom 0)]
    (ch/chat-history-start-streaming! ch)
    (core/render ch 40)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (ch/chat-history-append-streaming-text! ch "Hello")
      (is (= 1 @fired) "first append scheduled exactly one frame")
      (ch/chat-history-append-streaming-text! ch " world")
      (is (= 2 @fired) "second append scheduled another frame")
      (is (= "Hello world" (ch/chat-history-get-streaming-text ch)))
      (finally
        (macros/set-frame-hook! nil)))))

(deftest test-finalize-streaming
  (testing "finalize streaming text into a message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Final message")
      (let [msg (ch/chat-history-finalize-streaming! ch)]
        (is (some? msg))
        ;; After finalize, streaming text cleared, message is in history
        (is (= "" (ch/chat-history-get-streaming-text ch)))
        (let [msgs (ch/chat-history-get-messages ch)]
          (is (= 1 (count msgs)))
          (is (= :assistant (:role (first msgs))))
          (is (= "Final message" (:content (first msgs)))))))))

(deftest test-mark-streaming-tool-calls-then-finalize
  (testing "marking the streaming message tool-call-bearing does not break
            finalize's identity match, so the entry still materializes"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-append-streaming-text! ch "Final message ")
      (ch/chat-history-mark-streaming-tool-calls! ch)
      (ch/chat-history-finalize-streaming! ch)
      (let [entry (peek @(:messages-atom ch))]
        (is (false? (:streaming? entry)) "finalize found the marked entry")
        (is (nil? (:text-atom entry)) "content atoms materialized and stripped")
        (is (= "Final message " (:content entry)))
        (is (true? (:tool-calls? entry)) "the tool-call flag survives finalization")))))

(deftest test-finalize-empty
  (testing "finalize empty streaming returns nil"
    (let [ch (ch/make-chat-history)]
      (is (nil? (ch/chat-history-finalize-streaming! ch)))
      (is (= [] (ch/chat-history-get-messages ch))))))

(deftest test-clear
  (testing "clear all messages and info"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-append-streaming-text! ch "thinking")
      (ch/chat-history-set-info-msg! ch {:label "test" :content "info"})
      (ch/chat-history-clear! ch)
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch)))))
  (testing "clear disposes message components so owned drivers stop"
    (let [ch (ch/make-chat-history)
          bash-comp (ui/make-bash-execution :command "sleep 10")]
      ;; Mount through the chat (the production path) so the root reaction
      ;; owns the state watch. After clear!, the driver is stopped (done set,
      ;; future cancelled) — assert on those, not on hook traffic, which the
      ;; test's own mounts also generate.
      (ch/chat-history-add-message! ch {:role :bash :command "sleep 10"
                                        :component bash-comp})
      (core/render ch 40)
      (is (false? @(:done-atom bash-comp)) "driver armed while mounted")
      (is (some? @(:ticker-id-atom bash-comp)) "driver timer armed while mounted")
      (ch/chat-history-clear! ch)
      (is (true? @(:done-atom bash-comp)) "clear tripped the driver done flag")
      (is (nil? @(:ticker-id-atom bash-comp)) "clear cancelled the driver timer")
      (is (nil? @(:elapsed-ticker-id-atom bash-comp)) "clear cancelled the elapsed ticker")
      (is (= [] (ch/chat-history-get-messages ch)) "messages cleared"))))

(deftest test-tool-expanded-toggle
  (testing "toggle tool expanded state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "files"})
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (true? (ch/chat-history-get-tool-expanded ch)))
      ;; the child reads the shared toggle atom reactively — no per-child
      ;; push happened, yet the next render must show expanded output
      (let [child (:component (first @(:messages-atom ch)))]
        (is (some #(re-find #"files" %) (core/render child 60))
            "tool output visible after global expand with no push"))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (false? (ch/chat-history-get-tool-expanded ch))))))

(deftest test-tool-display-mode-cycle
  (testing "ctrl+o cycles collapsed → quiet → expanded → collapsed"
    (let [ch (ch/make-chat-history)]
      (is (= :collapsed (ch/chat-history-get-tool-display-mode ch)))
      (is (= :quiet (ch/chat-history-cycle-tool-display! ch)))
      (is (= :expanded (ch/chat-history-cycle-tool-display! ch)))
      (is (= :collapsed (ch/chat-history-cycle-tool-display! ch)))))
  (testing "set rejects unknown modes"
    (let [ch (ch/make-chat-history)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ch/chat-history-set-tool-display-mode! ch :loud)))
      (is (= :collapsed (ch/chat-history-get-tool-display-mode ch))
          "rejected set leaves the mode untouched")))
  (testing "quiet reads as not-expanded through the boolean shim"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-tool-display-mode! ch :quiet)
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      ;; the toggle shim leaves quiet via expanded
      (is (true? (ch/chat-history-toggle-tool-expanded! ch)))
      (is (= :expanded (ch/chat-history-get-tool-display-mode ch))))))

(deftest test-tool-display-mode-init
  (testing "make-chat-history seeds the mode; default stays collapsed"
    (let [ch (ch/make-chat-history)]
      (is (= :collapsed (ch/chat-history-get-tool-display-mode ch))))
    (doseq [mode [:collapsed :quiet :expanded]]
      (let [ch (ch/make-chat-history :tool-display-mode mode)]
        (is (= mode (ch/chat-history-get-tool-display-mode ch)))))
    (let [ch (ch/make-chat-history :tool-display-mode :bogus)]
      (is (= :collapsed (ch/chat-history-get-tool-display-mode ch))
          "invalid init degrades to collapsed"))))

(deftest test-quiet-run-groups-without-inner-separators
  (testing "consecutive quiet :tool entries share one run separator; other roles keep theirs"
    (let [ch (ch/make-chat-history :tool-display-mode :quiet)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-add-message! ch {:role :tool :name "first-tool" :content "a"})
      (ch/chat-history-add-message! ch {:role :tool :name "second-tool" :content "b"})
      (ch/chat-history-add-message! ch {:role :assistant :content "reply"})
      (ch/chat-history-add-message! ch {:role :tool :name "third-tool" :content "c"})
      (let [lines (plain-lines ch 40)
            idx (fn [re] (first (keep-indexed #(when (re-find re %2) %1) lines)))]
        (is (= ["hello" "first-tool" "second-tool" "reply" "third-tool"]
               (filterv seq (map str/trim lines)))
            "no blank line inside a quiet run, separators elsewhere")
        (is (= "" (nth lines (dec (idx #"first-tool")))) "one separator opens the run")
        (is (= "" (nth lines (dec (idx #"third-tool")))) "a new run re-opens after other roles")
        (is (not= "" (nth lines (dec (idx #"second-tool")))) "no separator inside the run"))))
  (testing "a quiet run includes :skill entries (user blocks split into :skill)"
    (let [ch (ch/make-chat-history :tool-display-mode :quiet)
          block "<skill name=\"demo\" location=\"/x/SKILL.md\">\nbody here\n</skill>"]
      (ch/chat-history-add-message! ch {:role :tool :name "first-tool" :content "a"})
      (ch/chat-history-add-message! ch {:role :user :content block})
      (ch/chat-history-add-message! ch {:role :tool :name "second-tool" :content "b"})
      (let [lines (plain-lines ch 40)
            idx (fn [re] (first (keep-indexed #(when (re-find re %2) %1) lines)))]
        (is (= ["first-tool" "[skill] demo" "second-tool"]
               (filterv seq (map str/trim lines))))
        (is (not= "" (nth lines (dec (idx #"\[skill\]")))) "skill continues the run"))))
  (testing "collapsed mode keeps each tool box's own separator"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-add-message! ch {:role :tool :name "first-tool" :content "a"})
      (ch/chat-history-add-message! ch {:role :tool :name "second-tool" :content "b"})
      (let [lines (plain-lines ch 40)
            idx (fn [re] (first (keep-indexed #(when (re-find re %2) %1) lines)))
            blank? #(= "" (str/trim %))]
        (is (blank? (nth lines (dec (idx #"first-tool")))))
        (is (blank? (nth lines (dec (idx #"second-tool"))))
            "each box keeps its own separator outside quiet")))))

(deftest test-quiet-run-survives-silent-tool-call-assistant
  (testing "a finalized tool-call-only assistant message renders nothing and keeps the run"
    (let [ch (ch/make-chat-history :tool-display-mode :quiet)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-add-message! ch {:role :tool :name "first-tool" :content "a"})
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-mark-streaming-tool-calls! ch)
      (ch/chat-history-finalize-streaming! ch)
      (ch/chat-history-add-message! ch {:role :tool :name "second-tool" :content "b"})
      (let [lines (plain-lines ch 40)
            idx (fn [re] (first (keep-indexed #(when (re-find re %2) %1) lines)))]
        (is (= ["hello" "first-tool" "second-tool"]
               (filterv seq (map str/trim lines)))
            "the silent assistant contributes no lines")
        (is (= "" (nth lines (dec (idx #"first-tool")))) "one separator opens the run")
        (is (not= "" (nth lines (dec (idx #"second-tool")))) "no separator inside the run"))))
  (testing "an assistant message with real text still breaks the run"
    (let [ch (ch/make-chat-history :tool-display-mode :quiet)]
      (ch/chat-history-add-message! ch {:role :tool :name "first-tool" :content "a"})
      (ch/chat-history-add-message! ch {:role :assistant :content "Thinking..." :tool-calls? true})
      (ch/chat-history-add-message! ch {:role :tool :name "second-tool" :content "b"})
      (let [lines (plain-lines ch 40)
            idx (fn [re] (first (keep-indexed #(when (re-find re %2) %1) lines)))]
        (is (= "" (nth lines (dec (idx #"second-tool"))))
            "visible thinking re-opens the run with a separator")))))

(deftest test-thinking-hidden-toggle
  (testing "toggle thinking hidden state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :assistant :content "hello" :thinking "thinking..."})
      (is (false? (ch/chat-history-get-thinking-hidden ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (true? (ch/chat-history-get-thinking-hidden ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (false? (ch/chat-history-get-thinking-hidden ch))))))

(deftest test-render-multiple
  (testing "multiple messages render"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "line1"})
      (ch/chat-history-add-message! ch {:role :user :content "line2"})
      (let [lines (core/render ch 40)]
        (is (pos? (count lines)))))))

(deftest test-multiple-messages
  (testing "multiple messages render in order"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "First"})
      (ch/chat-history-add-message! ch {:role :assistant :content "Second"})
      (ch/chat-history-add-message! ch {:role :user :content "Third"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"First" %) lines))
        (is (some #(re-find #"Second" %) lines))
        (is (some #(re-find #"Third" %) lines))
        (let [first-idx (first (keep-indexed #(when (re-find #"First" %2) %1) lines))
              third-idx (first (keep-indexed #(when (re-find #"Third" %2) %1) lines))]
          (is (< first-idx third-idx) "Messages should maintain order"))))))

(deftest test-cache-invalidation
  (testing "cache invalidates on new message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "original"})
      (let [lines1 (plain-lines ch 40)]
        (is (some #(re-find #"original" %) lines1)))
      (ch/chat-history-add-message! ch {:role :user :content "new"})
      (let [lines2 (plain-lines ch 40)]
        (is (some #(re-find #"new" %) lines2))))))

(deftest test-remove-streaming-placeholder
  (testing "placeholder is the last entry — removed, streaming state cleared"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (ch/chat-history-start-streaming! ch)
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (nil? @(:streaming-atom ch)))
      (is (= ["initial"] (map :content (ch/chat-history-get-messages ch))))))

  (testing "consumed steering message sits after the placeholder — it survives"
    ;; on-agent-error / handle-cancel remove the empty placeholder when a
    ;; run dies before the next assistant message-start. A drained
    ;; steering/follow-up user message (or a tool execution) is appended
    ;; AFTER the placeholder, so position-based removal would delete the
    ;; user's message instead (pi: agent_end removes the streamingComponent
    ;; by reference).
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-add-message! ch {:role :user :content "STEERED"})
      (ch/chat-history-add-message! ch {:role :user :content "TOOL"})
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (nil? @(:streaming-atom ch)))
      (is (= ["initial" "STEERED" "TOOL"]
             (map :content (ch/chat-history-get-messages ch))))))

  (testing "trailing status lines are dropped with the placeholder"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-show-status! ch "some status")
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (empty? (ch/chat-history-get-messages ch)))))

  (testing "no streaming in progress — no-op"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (is (nil? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= 1 (count (ch/chat-history-get-messages ch)))))))

(deftest test-rebuild
  (testing "rebuild replaces all messages and preserves the info banner"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-set-info-msg! ch {:label "banner" :content "info text"})]
      (ch/chat-history-add-message! ch {:role :user :content "old 1"})
      (ch/chat-history-add-message! ch {:role :assistant :content "old 2"})
      (ch/chat-history-rebuild! ch [{:role :user :content "new 1"}
                                    {:role :assistant :content "new 2"}])
      ;; old messages gone, new ones present
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"new 1" %) lines) "new user message rendered")
        (is (some #(re-find #"new 2" %) lines) "new assistant message rendered")
        (is (not-any? #(re-find #"old 1|old 2" %) lines) "old messages removed")
        (is (some #(re-find #"info text" %) lines) "info banner preserved"))))

  (testing "rebuild with no info banner"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "old"})
      (ch/chat-history-rebuild! ch [{:role :user :content "only"}])
      (is (= [{:role :user :content "only"}]
             (ch/chat-history-get-messages ch))))))

(deftest test-insert-before-streaming
  (testing "injected :info message lands above the streaming placeholder"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-append-streaming-text! ch "response")
      (ch/chat-history-insert-before-streaming! ch
                                                {:role :info :label "ext" :content "injected note"})
      (let [msgs (ch/chat-history-get-messages ch)]
        (is (= 3 (count msgs)))
        (is (= :user (:role (nth msgs 0))))
        (is (= :info (:role (nth msgs 1))) "info box sits above the response")
        (is (= :assistant (:role (nth msgs 2))))))))

(deftest test-insert-before-streaming-appends-when-no-streaming
  (testing "falls back to appending when no streaming placeholder exists"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (ch/chat-history-insert-before-streaming! ch
                                                {:role :info :label "ext" :content "note"})
      (let [msgs (ch/chat-history-get-messages ch)]
        (is (= 2 (count msgs)))
        (is (= :info (:role (nth msgs 1))))))))

(deftest test-user-message-block-content
  (testing "block-vector user content renders as plain text, not a literal vector"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :user :content [{:type :text :text "hi there"}]})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"hi there" %) lines))
        (is (not-any? #(re-find #"\{:type" %) lines)
            "raw block vector must not render")))))

(deftest test-user-message-image-renders-inline
  (testing "image blocks render as image elements — without protocol support the text indicator, never [image mime] placeholders"
    (let [prev-caps (timg/get-capabilities)]
      (try
        (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
        (let [ch (ch/make-chat-history)]
          (ch/chat-history-add-message! ch
                                        {:role :user
                                         :content [{:type :text :text "see:"}
                                                   {:type :image :data "AA" :mime-type "image/png"}]})
          (let [lines (plain-lines ch 40)]
            (is (some #(re-find #"see:" %) lines))
            (is (some #(re-find #"\[Image: \[image/png\] 800x600\]" %) lines))
            (is (not-any? #(re-find #"\[image image/png\]" %) lines))))
        (finally (timg/set-capabilities! prev-caps))))))

(deftest test-show-status
  (testing "status line renders and is not persisted as a message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Tool output: expanded" %) lines)
            "status text should render"))
      (is (= [:user] (mapv :role (ch/chat-history-get-messages ch)))
          "status must not appear in persisted messages")
      (is (= ["hello"] (mapv :content (ch/chat-history-get-messages ch)))))))

(deftest test-show-status-updates-in-place
  (testing "repeated status updates replace the line instead of appending"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :user :content "hi"})]
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (ch/chat-history-show-status! ch "Tool output: collapsed")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Tool output: collapsed" %) lines))
        (is (not-any? #(re-find #"Tool output: expanded" %) lines)
            "old status text should be replaced, not accumulated")))))

(deftest test-info-collapsible
  (testing "collapsible info banner toggles with the tool-expand action"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch
                                     {:label "kmet" :content "plain"
                                      :collapsed-content "Press ctrl+o to expand"
                                      :expanded-content "Full help here"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Press ctrl\+o to expand" %) lines)
            "collapsed content shown by default")
        (is (not-any? #(re-find #"Full help here" %) lines)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Full help here" %) lines)
            "expanded content after toggle")
        (is (not-any? #(re-find #"Press ctrl\+o to expand" %) lines))))))

(deftest test-info-non-collapsible-untouched
  (testing "non-collapsible info banner is left untouched by the toggle"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "kmet" :content "static text"})
      (ch/chat-history-toggle-tool-expanded! ch)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"static text" %) lines))))))

(deftest test-rebuild-preserves-collapsible-info
  (testing "rebuild keeps collapsible variants and expanded state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch
                                     {:label "kmet" :content "plain"
                                      :collapsed-content "Collapsed banner"
                                      :expanded-content "Expanded banner"})
      (ch/chat-history-toggle-tool-expanded! ch)  ;; expand
      (ch/chat-history-rebuild! ch [{:role :user :content "new"}])
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Expanded banner" %) lines)
            "expanded state preserved across rebuild"))
      (ch/chat-history-toggle-tool-expanded! ch)  ;; collapse
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Collapsed banner" %) lines)
            "collapsible variants preserved across rebuild")))))

(deftest test-flag-toggle-no-content
  (testing "toggles flip the tracked flags even with no matching components"
    (let [ch (ch/make-chat-history)]
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (true? (ch/chat-history-get-tool-expanded ch)) "flag flips without tools")
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (true? (ch/chat-history-get-thinking-hidden ch)) "flag flips without messages"))))

(deftest test-new-messages-inherit-flags
  (testing "new tool components inherit the expansion flag"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "files"})
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      ;; a NEW tool added after the toggle is expanded too
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "more"})
      (let [tools (keep (fn [m] (when (contains? (:component m) :expanded-atom)
                                  (:component m)))
                        @(:messages-atom ch))]
        (is (= 2 (count tools)))
        (is (every? #(identical? (:tools-expanded-atom ch)
                                 (:tools-expanded-atom %))
                    tools)
            "all tools — old and new — link to the shared toggle atom"))))
  (testing "new assistant messages inherit the thinking-hidden flag"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :assistant :content "a" :thinking "t"})
      (ch/chat-history-toggle-thinking-hidden! ch)
      (ch/chat-history-add-message! ch
                                    {:role :assistant :content "b" :thinking "t2"})
      (let [assistants (keep (fn [m] (when (contains? (:component m) :hide-thinking-atom)
                                       (:component m)))
                             @(:messages-atom ch))]
        (is (= 2 (count assistants)))
        (is (every? #(true? @(:hide-thinking-atom %)) assistants)
            "all assistant messages — old and new — have thinking hidden")))))

(deftest test-remove-streaming-placeholder-skips-status
  (testing "placeholder removal drops trailing status lines with it"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= [] (ch/chat-history-get-messages ch))
          "the status line and the placeholder are both removed"))))

(deftest test-show-status-appends-then-scrolls-away
  (testing "a status is a trailing entry — the next message renders below it,
            pushing the status up out of the pinned-bottom position"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :user :content "hello"})
          _ (ch/chat-history-show-status! ch "Tool output: expanded")
          _ (ch/chat-history-add-message! ch {:role :assistant :content "response"})
          lines (plain-lines ch 40)
          status-idx (first (keep-indexed #(when (re-find #"Tool output" %2) %1) lines))
          resp-idx (first (keep-indexed #(when (re-find #"response" %2) %1) lines))]
      (is status-idx)
      (is resp-idx)
      (is (< status-idx resp-idx)
          "the new message appears after the status — the status is no longer
           pinned to the bottom of the chat"))))

(deftest test-info-banner-in-children
  (testing "the info banner is a chat message: themed, persisted as :info, survives placeholder removal"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "kmet" :content "banner"})
      (is (some? @(:info-comp-atom ch)) "banner component exists")
      ;; output-pad must reach the banner without errors (theme needs no
      ;; walk — components subscribe to ui.subs/theme-sub, Stage 5)
      (ch/chat-history-set-output-pad! ch 2)
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (is (= [:info :user] (mapv :role (ch/chat-history-get-messages ch))))
      (ch/chat-history-start-streaming! ch)
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= [:info :user] (mapv :role (ch/chat-history-get-messages ch)))
          "placeholder removal removes the placeholder, not the banner or the message"))))

(deftest test-show-error-warning
  (testing "show-error! / show-warning! render plain spacer + colored text (pi: showError/showWarning)"
    (let [ch (ch/make-chat-history)
          _ (ui/show-warning! ch "bash already running")
          _ (ui/show-error! ch "command failed")
          lines (plain-lines ch 40)]
      (is (some #(re-find #"Warning: bash already running" %) lines))
      (is (some #(re-find #"Error: command failed" %) lines))
      (is (not-any? #(re-find #"custom-message" %) lines)
          "no background box — plain text like pi"))))

(deftest test-info-banner-first-user-spacing
  (testing "first user message after the info banner gets the same separator as later messages (visible gap between the two boxes)"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-set-info-msg! ch {:label "kmet" :content "banner"})
          _ (ch/chat-history-add-message! ch {:role :user :content "hello"})
          lines (plain-lines ch 20)
          banner-idx (first (keep-indexed #(when (re-find #"banner" %2) %1) lines))
          hello-idx (first (keep-indexed #(when (re-find #"hello" %2) %1) lines))]
      (is banner-idx)
      (is hello-idx)
      (is (= hello-idx (+ banner-idx 4))
          "banner content → 3 blank lines (box bottom pad + separator + user box top pad) → user text"))))

(deftest test-unknown-system-role-renders
  (testing "roles without a dedicated component (:system, :unknown) render as plain text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :system :content "compaction summary"})
      (ch/chat-history-add-message! ch {:role :unknown :content "mystery content"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"compaction summary" %) lines))
        (is (some #(re-find #"mystery content" %) lines))
        (is (= 2 (count (ch/chat-history-get-messages ch)))
            "messages are kept, not silently dropped")))))

(deftest test-tool-block-content
  (testing "tool messages with block-vector content (agent-loop :context-replaced) render"
    (let [ch (ch/make-chat-history)]
      ;; :context-replaced rebuild feeds agent messages whose tool content is
      ;; [{:type :tool_result :content "..."}] — must not crash or print the raw vector
      (ch/chat-history-add-message! ch
                                    {:role :tool :name "bash"
                                     :content [{:type :tool_result :tool_use_id "x" :content "file text"}]
                                     :is-error false})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"file text" %) lines)
            "tool_result content extracted from the block")
        (is (not-any? #(re-find #"tool_result|PersistentVector" %) lines)
            "raw block vector must not render")))))

(deftest test-hidden-thinking-label
  (testing "chat-history-set-hidden-thinking-label! applies to existing and
            new assistant messages (pi: setHiddenThinkingLabel)"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :assistant
                                              :content "resp"
                                              :thinking "secret"})
          _ (ch/chat-history-toggle-thinking-hidden! ch)
          _ (ch/chat-history-set-hidden-thinking-label! ch "Thoughts hidden")
          lines (plain-lines ch 40)]
      (is (some #(re-find #"Thoughts hidden" %) lines)
          "existing hidden message shows the custom label")
      (is (not-any? #(re-find #"secret" %) lines)
          "thinking content stays hidden")
      ;; nil restores the default
      (ch/chat-history-set-hidden-thinking-label! ch nil)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Thinking\.\.\." %) lines)
            "nil restores the default label")))))

(deftest test-render-prebuilt-component
  (testing "a message carrying a pre-built :component renders it as-is"
    (let [ch (ch/make-chat-history)
          comp (core/make-text "prebuilt!")]
      (ch/chat-history-add-message! ch {:component comp})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"prebuilt!" %) lines)
            "the pre-built component's text should be visible, not empty")))))

(deftest test-info-banner-replacement-disposes-previous
  (testing "replacing the info banner disposes the previous component — its
            children's track! watches must not outlive it"
    (let [watchers #(count @(deref #'macros/watch-registry))
          h (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! h {:label "a" :content "one"})
      (core/render h 60)
      (let [first-banner @(:info-comp-atom h)
            baseline (watchers)]
        (is (some? first-banner))
        (ch/chat-history-set-info-msg! h {:label "b" :content "two"})
        (core/render h 60)
        (is (not (identical? first-banner @(:info-comp-atom h))))
        (is (= baseline (watchers))
            "the replaced banner's watches were torn down")))))

(deftest test-info-banner-survives-rebuild-with-images
  (testing "chat-history-rebuild! preserves the banner's content images"
    (let [prev-caps (timg/get-capabilities)]
      (try
        (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
        (let [h (ch/make-chat-history)]
          (ch/chat-history-set-info-msg! h {:label "ext" :content "note"
                                            :images [{:data "AA" :mime-type "image/png"}]})
          (core/render h 60)
          (is (= [{:data "AA" :mime-type "image/png"}]
                 @(:images-atom @(:info-comp-atom h)))
              "banner carries the image blocks")
          (ch/chat-history-rebuild! h [{:role :user :content "hi"}])
          (let [lines (plain-lines h 60)]
            (is (some #(re-find #"\[Image: \[image/png\]" %) lines)
                "the rebuilt banner still renders its image")
            (is (some #(re-find #"note" %) lines))))
        (finally (timg/set-capabilities! prev-caps))))))

(deftest test-get-messages-info-entry-carries-images
  (testing "the banner entry from chat-history-get-messages round-trips images"
    (let [h (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! h {:label "ext" :content "note"
                                        :images [{:data "AA" :mime-type "image/png"}]})
      (let [info (first (ch/chat-history-get-messages h))]
        (is (= :info (:role info)))
        (is (= [{:data "AA" :mime-type "image/png"}] (:images info)))))))

(deftest test-skill-invocation-with-images
  (testing "images attached to a /skill: invocation render with the trailing
            args (previously dropped: the skill branch rendered only text)"
    (let [prev-caps (timg/get-capabilities)
          block (str "<skill name=\"demo\" location=\"/x/SKILL.md\">\n"
                     "body\n</skill>\n\nargs here")]
      (try
        (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
        (let [ch (ch/make-chat-history)]
          (ch/chat-history-add-message! ch
                                        {:role :user
                                         :content [{:type :text :text block}
                                                   {:type :image :data "AA" :mime-type "image/png"}]})
          (let [lines (plain-lines ch 60)]
            (is (some #(re-find #"\[skill\]" %) lines) "skill message rendered")
            (is (some #(re-find #"args here" %) lines) "args rendered below")
            (is (some #(re-find #"\[Image: \[image/png\]" %) lines)
                "the attached image renders")))
        (finally (timg/set-capabilities! prev-caps))))))

(ns kmet.ui.tui.test-assistant-message
  (:require [clojure.string :as str]
            [kmet.test-utils :as tu]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.libs.theme :as theme]
            [kmet.tui.protocols :as protocols]
            [kmet.hikap.component :as component]
            [kmet.tui.components.markdown :as md]
            [kmet.ui.tui.assistant-message :as am]))

(t/use-fixtures :each tu/with-extension-host)

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-reflow-does-not-retain-temporary-markdown
  (let [watchers component/live-watch-count
        baseline (watchers)]
    ;; Repeated construction models resume; changing both content and width
    ;; models streaming and terminal reflow. Only the owner may stay tracked.
    (dotimes [_ 3]
      (let [c (am/make-assistant-message :text "response" :thinking "reasoning")]
        (try
          (am/assistant-message-set-streaming! c true)
          (dotimes [i 5]
            (swap! (:text-atom c) str " more text")
            (swap! (:thinking-text-atom c) str " more thinking")
            (core/render c (+ 40 i))
            (is (component/tracked? c) "the owner stays tracked")
            (is (<= (watchers) (inc baseline))
                "temporary text/thinking Markdown must not survive reflow"))
          (am/assistant-message-set-thinking-mode! c :hidden)
          (core/render c 60)
          (am/assistant-message-set-thinking-mode! c :visible)
          (am/assistant-message-set-streaming! c false)
          (core/render c 60)
          (is (component/tracked? c) "the owner stays tracked")
          (is (<= (watchers) (inc baseline)))
          (finally (protocols/dispose c))))
      (is (<= (watchers) baseline)
          "disposing the message returns the registry to its baseline"))))

(deftest test-create
  (testing "create assistant message component"
    (let [c (am/make-assistant-message :text "hello")]
      (is (some? c)))))

(deftest test-render-text
  (testing "renders message text"
    (let [c (am/make-assistant-message :text "Hello world")
          lines (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"Hello world" %) lines)))))

(deftest test-render-markdown-highlight
  (testing "assistant text renders markdown with syntax-highlighted fences"
    (let [c (am/make-assistant-message :text "```clojure\n(defn f [] 1)\n```")
          lines (core/render c 40)]
      (is (some #(.contains % (theme/fg (theme/get-current-theme) :syntax-keyword "defn")) lines)
          "code fence keywords get syntax colors")
      (is (some #(re-find #"\(defn f \[\] 1\)" %) (mapv strip-ansi lines))
          "fence content renders as markdown, not raw backticks"))))

(deftest test-render-thinking
  (testing "renders thinking text in italic"
    (let [c (am/make-assistant-message :text "response" :thinking "reasoning...")
          rendered (core/render c 40)]
        ;; Should have italic ANSI codes
      (is (some #(re-find #"\u001b\[3m" %) rendered)
          "Thinking should be in italic")
      (is (some #(re-find #"reasoning" %) (mapv strip-ansi rendered))))))

(deftest test-hide-thinking
  (testing "when hidden, shows 'Thinking...' label instead of content"
    (let [c (am/make-assistant-message :text "response" :thinking "secret reasoning"
                                       :thinking-mode :hidden)
          plain (mapv strip-ansi (core/render c 40))]
      (is (not-any? #(re-find #"secret reasoning" %) plain)
          "Thinking content should be hidden")
      (is (some #(re-find #"Thinking" %) plain)
          "Should show Thinking... label"))))

(deftest test-custom-hidden-label
  (testing "set-hidden-label! replaces the hidden-thinking label (pi:
            setHiddenThinkingLabel)"
    (let [c (am/make-assistant-message :text "response" :thinking "secret"
                                       :thinking-mode :hidden)
          _ (reset! (:hidden-label-atom c) "Thoughts hidden")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"Thoughts hidden" %) plain)
          "custom label shown")
      (is (not-any? #(re-find #"Thinking\.\.\." %) plain)
          "default label replaced"))))

(deftest test-data-atom-append
  ;; Stage 5: content lives in a data-layer atom shared into the record —
  ;; the app swaps it, the component's track! watch picks up the change and
  ;; the next render shows it (no setter, no manual invalidate)
  (testing "swapping the data text atom updates the render"
    (let [ta (atom "Hello ")
          c (am/make-assistant-message :text-atom ta)]
      (swap! ta str "world")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"Hello world" %) plain)
            "external swap visible on next render"))
      (swap! ta str "!")
      (is (some #(re-find #"Hello world!" %)
                (mapv strip-ansi (core/render c 40)))
          "streaming appends keep flowing through"))))

(deftest test-data-atom-thinking
  (testing "swapping the data thinking atom updates the render"
    (let [tha (atom "step1 ")
          c (am/make-assistant-message :thinking-atom tha)]
      (swap! tha str "step2")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"step1 step2" %) plain))))))

(deftest test-set-thinking-mode
  (testing "set-thinking-mode! re-renders the label projection"
    (let [c (am/make-assistant-message :thinking "reasoning..." :thinking-mode :hidden)]
      (am/assistant-message-set-thinking-mode! c :visible)
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"reasoning" %) plain)
            "switching to visible should show thinking content")))))

(deftest test-empty-message
  (testing "finalized empty message renders a muted placeholder (not a blank bubble)"
    (let [c (am/make-assistant-message)
          lines (mapv strip-ansi (core/render c 40))]
      (is (= [(apply str (repeat 40 \space)) " (no response)"] lines)
          "finalized empty response shows the placeholder (top spacer + output pad)"))))

(deftest test-empty-message-streaming
  (testing "streaming empty message renders nothing (working indicator covers the wait)"
    (let [c (am/make-assistant-message)
          _ (am/assistant-message-set-streaming! c true)
          lines (core/render c 40)]
      (is (zero? (count lines)) "streaming empty renders []"))))

(deftest test-only-thinking
  (testing "message with only thinking renders correctly"
    (let [c (am/make-assistant-message :thinking "just thinking")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"just thinking" %) plain)))))

(deftest test-whitespace-only-renders-nothing
  (testing "whitespace-only text/thinking finalizes to the placeholder (pi: content.text.trim() check)"
    (let [c (am/make-assistant-message :text "   \n  ")]
      (is (= [(apply str (repeat 40 \space)) " (no response)"]
             (mapv strip-ansi (core/render c 40)))
          "whitespace-only content is an empty response → placeholder"))))

(deftest test-tool-calls-only-renders-nothing
  (testing "a finalized tool-call-only message renders nothing — its tool\n            components are the visuals, no '(no response)' bubble"
    ;; constructor flag (replayed sessions: assistant entry carries :tool-calls)
    (let [c (am/make-assistant-message :tool-calls? true)]
      (is (zero? (count (core/render c 40))) "tool-call-only renders []"))
    ;; live flag flip after finalization (:tool-execution-start path) —
    ;; including flipping AFTER a cached placeholder render (track!
    ;; watches the flag atom, so the cache invalidates and re-renders)
    (let [c (am/make-assistant-message)]
      (is (= [" (no response)"] (rest (mapv strip-ansi (core/render c 40))))
          "pre-mark render shows the placeholder")
      (am/assistant-message-set-tool-calls! c true)
      (is (zero? (count (core/render c 40))) "late-marked tool-call-only renders []"))))

(deftest test-text-trimmed
  (testing "leading/trailing whitespace is trimmed before wrap (pi: text.trim())"
    (let [c (am/make-assistant-message :text "  hello world  ")
          lines (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"hello world" %) lines))
      (is (not-any? #(re-find #"^ {2}hello" %) lines)
          "no leading spaces survive"))))

(deftest test-width-change-reflows-once
  (testing "a width change reflows ONCE: the frame that reflowed is cached.
            The body must not track its own rendered-* output atoms — a
            tracked read can never equal the value written mid-body, so
            track! would drop the frame and the next render would re-parse
            the markdown again (regression: the doubled cost of resuming a
            large session at a new width)"
    (let [c (am/make-assistant-message :thinking "why")
          reflows (atom 0)]
      (add-watch (:rendered-text-atom c) :test-reflow-count
                 (fn [_ _ _ _] (swap! reflows inc)))
      (core/render c 60)
      (is (= 1 @reflows) "one reflow on the width change")
      (is (some? @(:cache-atom c)) "the reflowing frame is cached")
      (core/render c 60)
      (is (= 1 @reflows) "the next render serves the cache — no second reflow"))))

(deftest test-shared-thinking-mode-atom
  (testing "messages sharing the chat history's thinking-mode atom flip together —
            one reset! is enough, reflow happens lazily on the next render"
    (let [shared (atom :hidden)
          label-shared (atom "Thinking...")
          a (am/make-assistant-message :text "response a" :thinking "secret alpha"
                                       :thinking-mode-atom shared
                                       :hidden-label-atom label-shared)
          b (am/make-assistant-message :text "response b" :thinking "secret beta"
                                       :thinking-mode-atom shared
                                       :hidden-label-atom label-shared)
          plain-a #(mapv strip-ansi (core/render a 40))
          plain-b #(mapv strip-ansi (core/render b 40))]
      ;; hidden initially — both show the label, neither leaks content
      (is (some #(re-find #"Thinking" %) (plain-a)))
      (is (not-any? #(re-find #"secret alpha" %) (plain-a)))
      ;; flip the SHARED atom once — both messages pick it up on next render
      (reset! shared :visible)
      (is (some #(re-find #"secret alpha" %) (plain-a)) "message a expanded")
      (is (some #(re-find #"secret beta" %) (plain-b)) "message b expanded too")
      ;; and the per-message setter still works (writes the same shared atom)
      (am/assistant-message-set-thinking-mode! b :hidden)
      (is (= :hidden @shared) "setter hits the shared atom")
      (is (not-any? #(re-find #"secret beta" %) (plain-b)) "b hides again"))))

(deftest test-stream-last-thinking
  (testing ":stream-last renders the live block's tail while the model thinks
            and the hidden label for the blocks whose phase ended"
    (let [thinking (str/join "\n\n" (for [i (range 30)] (str "thought line " i)))
          active (atom true)
          c (am/make-assistant-message :thinking thinking
                                       :thinking-mode :stream-last
                                       :thinking-active-atom active)
          plain #(mapv strip-ansi (core/render c 80))]
      (am/assistant-message-set-streaming! c true)
      (let [lines (plain)
            content (remove str/blank? lines)]
        (is (= 7 (count lines))
            "the live window is six lines plus the message's top pad")
        (is (some #(re-find #"thought line 29" %) content)
            "the newest thinking is visible")
        (is (not-any? #(re-find #"thought line 0$" %) content)
            "the head of the block is not rendered"))
      (testing "the phase ends → the hidden label replaces the tail"
        (reset! active false)
        (let [lines (plain)]
          (is (some #(re-find #"Thinking\.\.\." %) lines)
              "a finished block reads exactly as it does under :hidden")
          (is (not-any? #(re-find #"thought line" %) lines)
              "no thinking content outside the label")))
      (testing "a finalized message keeps the label"
        (am/assistant-message-set-streaming! c false)
        (is (some #(re-find #"Thinking\.\.\." %) (plain))))
      (testing "an answer arriving with the phase closed renders the label and the text"
        (swap! (:text-atom c) str "the answer")
        (let [lines (plain)]
          (is (some #(re-find #"the answer" %) lines))
          (is (some #(re-find #"Thinking\.\.\." %) lines))
          (is (not-any? #(re-find #"thought line" %) lines))))))
  (testing "the live tail keeps a stable height while the block streams"
    (let [c (am/make-assistant-message :thinking ""
                                       :thinking-mode :stream-last
                                       :thinking-active? true)
          heights (atom [])]
      (am/assistant-message-set-streaming! c true)
      (dotimes [i 20]
        (swap! (:thinking-text-atom c) str "\n\nthought " i)
        (swap! heights conj (count (core/render c 80))))
      (let [filled (vec (drop 4 @heights))]
        (is (seq filled))
        (is (apply = filled)
            "once the block fills the window its height never changes — a
             resizing block made the chat's bottom jump by a line per delta")
        (is (= 7 (first filled)) "six tail lines plus the top pad")))))

(deftest test-streaming-thinking-renders-only-the-tail
  (testing "a thinking delta re-renders the trailing markdown blocks, not the
            whole thinking history — the streaming reflow used to re-parse and
            re-render tens of KB per delta, which made the block stutter"
    ;; lines end without a trailing space: a space before the blank line
    ;; would be trimmed while it is the text's end and reappear once the next
    ;; paragraph streams in, legitimately changing the previous block
    (let [line (str/join " " (repeat 120 "word"))
          thinking (apply str (for [i (range 30)]
                                (str "paragraph " i " " line "\n\n")))
          c (am/make-assistant-message :thinking thinking)]
      (am/assistant-message-set-streaming! c true)
      (core/render c 80)
      (let [calls (atom 0)
            orig (deref #'md/render-token-lines)]
        (with-redefs-fn {#'md/render-token-lines
                         (fn [& args] (swap! calls inc) (apply orig args))}
          (fn []
            (swap! (:thinking-text-atom c) str "and a growing tail")
            (core/render c 80)))
        (is (<= @calls 2)
            (str "only the trailing block re-renders, got " @calls))
        (is (= (mapv strip-ansi (core/render (am/make-assistant-message :thinking (str thinking "and a growing tail")) 80))
               (mapv strip-ansi (core/render c 80)))
            "the incremental render equals a fresh full render")))))

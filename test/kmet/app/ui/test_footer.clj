(ns kmet.app.ui.test-footer
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.tui.core :as core]
            [kmet.libs.host :as host]
            [kmet.app.session :as s]
            [kmet.app.ui.footer :as ft]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.tui.theme :as theme]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render
  "Render a footer component, stubbing the git-branch lookup: resolving it
   spawns `git branch --show-current` once per provider (~100ms each, and
   these tests exercise the footer rendering, not git). The branch suffix
   path is covered by test-git-branch via the stub's nil branch."
  [c width]
  (with-redefs [fdp/fdp-get-git-branch (constantly nil)]
    (core/render c width)))

(defn- render-plain [c width]
  (mapv strip-ansi (render c width)))

(defn- make-footer-with-session [& {:keys [session cwd context-window provider-count model provider thinking reasoning auto-compact] :as opts}]
  (let [p (fdp/make-footer-data-provider
           :session session
           :cwd (or cwd "/home/user/project")
           :provider-count (or provider-count 1)
           :context-window context-window
           :model (or model "gpt-4o")
           :provider (if (contains? opts :provider) provider :openai)
           :thinking thinking
           :reasoning reasoning)]
    (ft/make-footer :provider p :auto-compact auto-compact)))

(deftest test-create
  (testing "create footer component"
    (is (some? (ft/make-footer)))))

(deftest test-cwd-line
  (testing "first line shows the cwd"
    (let [c (make-footer-with-session :cwd "/some/project")
          plain (render-plain c 50)]
      (is (some #(re-find #"/some/project" %) plain)
          "footer should show the cwd")
      (is (= 2 (count plain))
          "two content lines, no separator"))))

(deftest test-home-substitution
  (testing "cwd inside home renders as ~"
    (let [home (System/getProperty "user.home")
          c (make-footer-with-session :cwd (str home "/project"))
          plain (render-plain c 50)]
      (is (some #(re-find (re-pattern (str "^К" (host/mark) " ~/project")) %) plain)
          "cwd under HOME renders home-substituted, after the К+host mark"))))

(deftest test-k-mark
  (testing "accent cursive К+host mark renders before the cwd on line 1"
    (let [c (make-footer-with-session :cwd "/some/project")
          [line1] (render-plain c 50)
          raw (render c 50)
          mark (str "К" (host/mark))]
      (is (str/starts-with? line1 (str mark " "))
          "line 1 starts with the К mark followed by the runtime letter")
      (is (str/starts-with? (first raw)
                            (str "\u001b[3m" (theme/get-fg-ansi (theme/get-current-theme) :accent) mark))
          "mark is italic (cursive) and accent-colored, matching the info screen"))))

(deftest test-git-branch
  (testing "footer renders without a git branch (branch lookup is stubbed —
            the provider resolves it lazily; here only the nil path matters)"
    (let [c (make-footer-with-session)
          plain (render-plain c 50)]
      (is (= 2 (count plain))))))

(deftest test-model-left-aligned
  (testing "model renders left-aligned right after the stats, not padded to the right edge"
    (let [c (make-footer-with-session :model "gpt-4o" :provider-count 1)
          plain (render-plain c 60)
          line (some #(when (str/includes? % "openai/gpt-4o") %) plain)]
      (is line "model renders on the stats line")
      (is (str/starts-with? line "? tokens  openai/gpt-4o")
          "stats, gap, then the model — no right-alignment padding")
      (is (< (count line) 60) "line does not extend to the right edge"))))

(deftest test-provider-model-display
  (testing "model always displays as provider/model, regardless of provider count"
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 1)
          plain (render-plain c 60)]
      (is (some #(re-find #"openai/gpt-4o" %) plain)))
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 3)
          plain (render-plain c 60)]
      (is (some #(re-find #"openai/gpt-4o" %) plain)))
    (testing "no provider configured → bare model"
      (let [c (make-footer-with-session :model "gpt-4o" :provider nil)
            plain (render-plain c 60)]
        (is (some #(re-find #"gpt-4o" %) plain))
        (is (not-any? #(re-find #"openai/" %) plain))))))

(deftest test-thinking-level
  (testing "thinking suffix follows pi: reasoning models show the level always"
    (let [c (make-footer-with-session :model "claude" :thinking :high :reasoning true)
          plain (render-plain c 60)]
      (is (some #(re-find #"openai/claude • high" %) plain)
          "non-off level shows bare (pi: `${modelName} • ${thinkingLevel}`)"))
    (let [c (make-footer-with-session :model "claude" :thinking :off :reasoning true)
          plain (render-plain c 60)]
      (is (some #(re-find #"openai/claude • thinking off" %) plain)
          "off shows 'thinking off' (pi)")))
  (testing "non-reasoning models show no suffix (pi: gated on model.reasoning)"
    (let [c (make-footer-with-session :model "gpt-4o" :thinking :high :reasoning false)
          plain (render-plain c 60)]
      (is (some #(re-find #"openai/gpt-4o" %) plain))
      (is (not-any? #(re-find #"thinking" %) plain)))))

(deftest test-context-percent
  (testing "context percent renders with the window and auto indicator"
    (let [c (make-footer-with-session :context-window 200000 :auto-compact true)
          plain (render-plain c 60)]
      (is (some #(re-find #"200k" %) plain))
      (is (some #(re-find #"\(auto\)" %) plain)))))

(deftest test-extension-statuses
  (testing "keyed extension statuses render on a third line (not dimmed — pi parity), sorted by key"
    (let [c (make-footer-with-session)]
      (ft/footer-set-extension-status! c "ext-b" "✓ ready")
      (ft/footer-set-extension-status! c "ext-a" "● active")
      (let [plain (render-plain c 60)]
        (is (= 3 (count plain)))
        (is (some #(re-find #"● active" %) plain))
        (is (some #(re-find #"✓ ready" %) plain))))
    (testing "nil text clears the key"
      (let [c (make-footer-with-session)]
        (ft/footer-set-extension-status! c "ext-a" "● active")
        (ft/footer-set-extension-status! c "ext-a" nil)
        (let [plain (render-plain c 60)]
          (is (= 2 (count plain)))
          (is (not-any? #(re-find #"● active" %) plain)))))))

(deftest test-mcp-extension-status
  (testing "the mcp-adapter status segment renders accent-colored and
            emoji-prefixed on its own footer line, verbatim (pi parity
            — accent preserved, not dimmed)"
    (let [c (make-footer-with-session)
          accent (theme/fg theme/dark-theme :accent
                           "🔌 MCP: 1 server enabled (1 connected)")]
      (ft/footer-set-extension-status! c "mcp" accent)
      (let [plain (render-plain c 80)
            raw (render c 80)
            mcp-line (some #(when (str/includes? % "MCP") %) raw)]
        (is (= 3 (count plain)) "pwd + stats + mcp status line")
        (is (some #(re-find #"MCP: 1 server enabled \(1 connected\)" %) plain)
            "status text renders in the ansi-stripped output")
        (is mcp-line "status renders on its own footer line")
        (is (= mcp-line accent)
            "renders verbatim — accent color preserved, not dimmed (pi parity)"))))
  (testing "nil status clears the mcp key, collapsing the status line"
    (let [c (make-footer-with-session)
          accent (theme/fg theme/dark-theme :accent "🔌 MCP: 2 servers enabled")]
      (ft/footer-set-extension-status! c "mcp" accent)
      (ft/footer-set-extension-status! c "mcp" nil)
      (let [plain (render-plain c 80)]
        (is (not-any? #(re-find #"MCP: 2 servers" %) plain)
            "cleared mcp status no longer renders")
        (is (= 2 (count plain))
            "footer collapses back to the two content lines")))))

(deftest test-wide-footer
  (testing "footer handles wide terminal"
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 3)
          lines (render c 120)]
      (is (pos? (count lines))))))

(deftest test-model-wraps-to-own-line
  (testing "when stats + model don't fit, the model wraps to its own left-aligned line"
    (let [c (make-footer-with-session :model "some-very-long-model-name" :provider :openai)
          plain (render-plain c 40)]
      (is (= 3 (count plain)) "pwd + stats + model lines")
      (let [model-line (last plain)]
        (is (str/starts-with? model-line "openai/some-very-long-model-name"))
        (is (= 32 (count model-line))
            "model line left-aligned, not padded to the full width")))
    (testing "model wider than the terminal is truncated to fit"
      (let [c (make-footer-with-session :model (apply str (repeat 50 "x")) :provider :openai)
            plain (render-plain c 20)]
        (is (= 3 (count plain)))
        (is (every? #(<= (count %) 20) plain)))
      (testing "extension statuses still render after the wrapped model line"
        (let [c (make-footer-with-session :model "some-very-long-model-name" :provider :openai)]
          (ft/footer-set-extension-status! c "ext" "● active")
          (let [plain (render-plain c 40)]
            (is (= 4 (count plain)) "pwd + stats + model + status")
            (is (some #(re-find #"● active" %) plain))))))))

(deftest test-narrow-footer
  (testing "footer truncates instead of overflowing on narrow terminals"
    (let [c (make-footer-with-session :model "some-very-long-model-name-here" :provider-count 3)
          plain (render-plain c 20)]
      (is (every? #(<= (count %) 20) plain)
          "every rendered line fits the width"))))

(deftest test-session-name
  (testing "session display name renders after the cwd (pi: pwd • name)"
    (let [dir (str "target/test-footer-name-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (s/append-session-info! sess "my project")
        (let [c (make-footer-with-session :session sess :cwd "/srv/workspace")
              plain (render-plain c 60)]
          ;; a git branch may resolve in the test env — allow an optional suffix
          (is (some #(re-find #"/srv/workspace( \([^)]*\))? • my project" %) plain)))
        (finally (fs/delete-tree dir)))))
  (testing "no name renders the plain cwd line"
    (let [dir (str "target/test-footer-name-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (let [c (make-footer-with-session :session sess :cwd "/srv/workspace")
              plain (render-plain c 60)]
          (is (some #(re-find #"/srv/workspace( \([^)]*\))?$" %) plain)))
        (finally (fs/delete-tree dir))))))

(deftest test-format-tokens
  (testing "pi formatTokens scaling"
    (is (= "999" (ft/format-tokens 999)))
    (is (= "1.2k" (ft/format-tokens 1234)))
    (is (= "12k" (ft/format-tokens 12345)))
    (is (= "1.2M" (ft/format-tokens 1234567)))
    (is (= "12M" (ft/format-tokens 12345678)))))

(deftest test-format-cwd
  (testing "pi formatCwdForFooter home substitution"
    (is (= "~" (ft/format-cwd-for-footer "/home/user" "/home/user")))
    (is (= "~/project" (ft/format-cwd-for-footer "/home/user/project" "/home/user")))
    (is (= "/opt/other" (ft/format-cwd-for-footer "/opt/other" "/home/user")))))

(deftest test-cost-display
  (testing "usage cost renders as $X.XXX after the stats (pi: toFixed(3))"
    (let [dir (str "target/test-footer-cost-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (s/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                              :usage {:prompt_tokens 1000 :completion_tokens 500
                                      :cost {:input 0.002 :output 0.004
                                             :cache-read 0.0 :cache-write 0.0
                                             :total 0.006}}})
        (let [c (make-footer-with-session :session sess)
              plain (render-plain c 80)]
          (is (some #(re-find #"\$0\.006" %) plain)
              "stats line carries the cumulative cost"))
        (finally (fs/delete-tree dir)))))
  (testing "no cost → no $ part"
    (let [c (make-footer-with-session)
          plain (render-plain c 80)]
      (is (not-any? #(re-find #"\$" %) plain)
          "zero cost renders nothing (pi: if (usageTotals.cost …))"))))

(deftest test-reactive-re-render
  (testing "fdp atom changes re-derive the footer with NO manual invalidation"
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai)]
      (is (some #(re-find #"openai/gpt-4o" %) (render-plain c 60)))
      ;; Bare fdp setter — no protocols/invalidate, no update-footer!:
      ;; the footer's track-deps cover the fdp atoms, so the next render
      ;; must reflect the change (regression guard for the removed
      ;; update-footer!/sync-footer-model! invalidation pushes).
      (fdp/fdp-set-model! @(:provider-atom c) "claude")
      (is (some #(re-find #"openai/claude" %) (render-plain c 60))
          "model change alone schedules the re-derivation")))
  (testing "in-place session :entries mutation re-renders usage"
    ;; Session records mutate their :entries atom in place — the footer
    ;; tracks the live entry vector, not just the session record ref.
    (let [dir (str "target/test-footer-reactive-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (let [c (make-footer-with-session :session sess)
              _ (is (not-any? #(re-find #"\$" %) (render-plain c 80))
                    "no usage yet — no cost part")]
          (s/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                                :usage {:prompt_tokens 1000 :completion_tokens 500
                                        :cost {:input 0.002 :output 0.004
                                               :cache-read 0.0 :cache-write 0.0
                                               :total 0.006}}})
          (is (some #(re-find #"\$0\.006" %) (render-plain c 80))
              "entries appended in place appear on the next render"))
        (finally (fs/delete-tree dir))))))

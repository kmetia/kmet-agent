(ns kmet.app.ui.test-skill-message
  "Tests for the skill invocation message (pi: parseSkillBlock +
   SkillInvocationMessageComponent): a `/skill:name` block renders as a
   collapsible invocation instead of dumping its body into the transcript,
   and the trailing args stay a normal user message."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.skills :as skills]
            [kmet.app.ui.chat-history :as ch]
            [kmet.app.ui.skill-message :as sm]
            [kmet.app.ui.subs :as subs]
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
;; collapsed line reads "ctrl+o to toggle" rather than the empty fallback.
(defn- with-keybindings [f]
  (let [prev (kb/get-global-keybindings)]
    (try
      (kb/set-global-keybindings! (app-kb/create-agent-keybindings-manager
                                   "target/test-skill-message-kb"))
      (f)
      (finally (kb/set-global-keybindings! prev)))))

(t/use-fixtures :each with-keybindings)

(def ^:private block
  "<skill name=\"demo-skill\" location=\"demo:SKILL.md\">\nReferences are relative to /skills/demo.\n\n# Steps\n\n1. do a thing\n2. do another\n</skill>")

;; ─── parse-skill-block (pi: parseSkillBlock) ───────────────────────────────

(deftest test-parse-skill-block
  (testing "a block without a trailing message"
    (let [p (skills/parse-skill-block block)]
      (is (= "demo-skill" (:name p)))
      (is (= "demo:SKILL.md" (:location p)))
      (is (str/includes? (:content p) "# Steps"))
      (is (str/includes? (:content p) "References are relative to"))
      (is (nil? (:user-message p)))))
  (testing "a block with a trailing message"
    (let [p (skills/parse-skill-block (str block "\n\ndo the thing"))]
      (is (= "do the thing" (:user-message p)))))
  (testing "the trailing message is trimmed"
    (let [p (skills/parse-skill-block (str block "\n\n  padded  "))]
      (is (= "padded" (:user-message p)))))
  (testing "an empty trailing message is no message"
    (is (nil? (:user-message (skills/parse-skill-block (str block "\n\n"))))))
  (testing "non-blocks"
    (is (nil? (skills/parse-skill-block "just a message")))
    (is (nil? (skills/parse-skill-block "")))
    (is (nil? (skills/parse-skill-block nil)))
    (is (nil? (skills/parse-skill-block 42)))
    (is (nil? (skills/parse-skill-block (str "prefix " block)))
        "the block must span the whole message")
    (is (nil? (skills/parse-skill-block (str block " suffix"))))))

(deftest test-parse-round-trips-the-expander
  ;; the parser is the inverse of what expand-skill-command builds — keep
  ;; them in step (both live in kmet.app.skills)
  (let [deregister (skills/register-extension-skill!
                    (str "---\nname: round-trip-demo\ndescription: a demo skill\n---\n"
                         "# Body\n\nStep one.\n")
                    {:location "test-ext:skills/round-trip/SKILL.md"
                     :extension "test-ext"})]
    (try
      (testing "no args"
        (let [expanded (skills/expand-skill-command "/skill:round-trip-demo")
              parsed (skills/parse-skill-block expanded)]
          (is (= "round-trip-demo" (:name parsed)))
          (is (= "test-ext:skills/round-trip/SKILL.md" (:location parsed)))
          (is (str/includes? (:content parsed) "Step one."))
          (is (nil? (:user-message parsed)))))
      (testing "with args"
        (let [parsed (skills/parse-skill-block
                      (skills/expand-skill-command "/skill:round-trip-demo be brief"))]
          (is (= "be brief" (:user-message parsed)))))
      (finally (deregister)))))

;; ─── The component ────────────────────────────────────────────────────────

(deftest test-collapsed-is-one-line-with-the-expand-hint
  (let [toggle (atom :collapsed)
        c (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom toggle)
        lines (plain-lines c 60)]
    (is (= 3 (count lines)) "box padding-y 1 above and below")
    (is (some #(str/includes? % "[skill] demo-skill") lines))
    (is (some #(str/includes? % "(ctrl+o to toggle)") lines))
    (testing "the skill body is NOT dumped into the transcript"
      (is (not-any? #(str/includes? % "do a thing") lines)))
    (testing "the XML wrapper is not shown either"
      (is (not-any? #(str/includes? % "<skill") lines)))))

(deftest test-expanded-shows-name-and-body
  (let [toggle (atom :expanded)
        c (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom toggle)
        text (joined c 60)]
    (is (str/includes? text "[skill]"))
    (is (str/includes? text "demo-skill") "the name heads the body")
    (is (str/includes? text "do a thing") "the body renders when expanded")
    (is (str/includes? text "do another"))
    (is (not (str/includes? text "(ctrl+o to toggle)"))
        "the hint belongs to the collapsed form")))

(deftest test-the-shared-toggle-drives-it
  ;; pi: setExpanded(toolOutputExpanded) — one atom flips every skill message
  (let [toggle (atom :collapsed)
        a (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom toggle)
        b (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom toggle)]
    (is (not (str/includes? (joined a 60) "do a thing")))
    (is (not (str/includes? (joined b 60) "do a thing")))
    (reset! toggle :expanded)
    (is (str/includes? (joined a 60) "do a thing") "flipped by the shared toggle")
    (is (str/includes? (joined b 60) "do a thing") "both messages flip together")
    (reset! toggle :collapsed)
    (is (not (str/includes? (joined a 60) "do a thing")) "and back")))

(deftest test-the-shared-toggle-is-authoritative
  ;; pi: toolOutputExpanded is the single source of truth — there is no
  ;; local expansion state a caller could set behind its back
  (let [toggle (atom :collapsed)
        c (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom toggle)]
    (is (not (str/includes? (joined c 60) "do a thing")))
    (reset! toggle :expanded)
    (is (str/includes? (joined c 60) "do a thing"))
    (reset! toggle :collapsed)
    (is (not (str/includes? (joined c 60) "do a thing"))
        "collapsing again rebuilds the one-line form")))

(deftest test-theme-switch-rebuilds-the-children
  ;; the box background and the markdown tint come from the theme; a palette
  ;; switch must re-apply them (apply-once on theme-sub)
  (let [c (sm/make-skill-invocation-message
           :skill-block (skills/parse-skill-block block)
           :tools-expanded-atom (atom :collapsed))
        before (core/render c 60)]
    (is (some #(str/includes? % "\u001b[") before) "themed output carries ANSI")
    (reset! theme/theme-atom (theme/get-theme "light"))
    (try
      (let [after (core/render c 60)]
        (is (not= before after) "the palette change re-rendered the message")
        (is (str/includes? (str/join after) "[skill]") "still the collapsed line"))
      (finally (reset! theme/theme-atom (theme/get-theme "dark"))))))

;; ─── Wiring into the chat history ─────────────────────────────────────────

(deftest test-chat-history-renders-a-skill-invocation
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :user :content block})
    (let [text (str/join "\n" (plain-lines ch 60))]
      (is (str/includes? text "[skill] demo-skill"))
      (is (str/includes? text "(ctrl+o to toggle)"))
      (is (not (str/includes? text "do a thing")) "body hidden while collapsed")
      (testing "the shared ctrl+o toggle expands it"
        (is (true? (ch/chat-history-toggle-tool-expanded! ch)))
        (let [expanded (str/join "\n" (plain-lines ch 60))]
          (is (str/includes? expanded "do a thing")))))
    (testing "quiet is one dimmed line with no hint or body"
      (let [ch2 (ch/make-chat-history)]
        (ch/chat-history-add-message! ch2 {:role :user :content block})
        (ch/chat-history-set-tool-display-mode! ch2 :quiet)
        (let [text (str/join "\n" (plain-lines ch2 60))]
          (is (str/includes? text "[skill] demo-skill"))
          (is (not (str/includes? text "do a thing")) "body hidden in quiet")
          (is (not (str/includes? text "ctrl+o")) "no hint in quiet"))))))

(deftest test-chat-history-renders-the-trailing-message-separately
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :user :content (str block "\n\nbe brief")})
    (let [text (str/join "\n" (plain-lines ch 60))]
      (is (str/includes? text "[skill] demo-skill"))
      (is (str/includes? text "be brief") "the args render as a normal user message")
      (is (not (str/includes? text "do a thing")) "the body stays collapsed"))
    (testing "no args → no trailing user message"
      (let [ch2 (ch/make-chat-history)]
        (ch/chat-history-add-message! ch2 {:role :user :content block})
        (let [text (str/join "\n" (plain-lines ch2 60))]
          (is (str/includes? text "[skill]")))))))

(deftest test-plain-user-messages-are-unchanged
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :user :content "an ordinary message"})
    (let [text (str/join "\n" (plain-lines ch 60))]
      (is (str/includes? text "an ordinary message"))
      (is (not (str/includes? text "[skill]"))))))

(deftest test-output-pad-walk-reaches-the-skill-message
  (let [ch (ch/make-chat-history)]
    (ch/chat-history-add-message! ch {:role :user :content (str block "\n\nargs")})
    (ch/chat-history-set-output-pad! ch 3)
    (let [lines (core/render ch 60)]
      (is (some #(str/includes? (strip-ansi %) "[skill]") lines))
      (is (some #(str/includes? (strip-ansi %) "args") lines)
          "the trailing user message followed the padding change"))))

;; ─── Shared styling with the compact read call ────────────────────────────

(deftest test-label-and-hint-are-shared-with-the-read-renderer
  ;; one spelling for `[skill] ` and the expand hint, so a `/skill:`
  ;; invocation and the agent's later SKILL.md read cannot drift
  (let [thm (deref subs/theme-sub)]
    (is (str/includes? (strip-ansi (sm/label thm)) "[skill]"))
    (is (str/includes? (strip-ansi (sm/expand-hint thm)) "ctrl+o to toggle"))
    (testing "the collapsed line is exactly label + name + hint"
      (is (= (str (sm/label thm) (theme/fg thm :custom-message-text "demo")
                  (sm/expand-hint thm))
             (sm/collapsed-line thm "demo"))))))

(deftest test-expand-collapse-rebuild-no-watch-leak
  ;; rebuild-content! replaces the [skill] label / body Markdown on every
  ;; toggle; the replaced children must be disposed (their track! watches
  ;; would otherwise accumulate per toggle)
  (let [watchers #(count @(deref #'macros/watch-registry))
        expand! (fn [comp expanded?]
                  (reset! (:expanded-atom comp) expanded?)
                  ((var-get #'kmet.app.ui.skill-message/rebuild-content!) comp expanded?)
                  (core/render comp 60))
        comp (sm/make-skill-invocation-message
              :skill-block {:name "demo" :location "/x" :content "body text"}
              :tools-expanded-atom (atom :collapsed))]
    (core/render comp 60)
    (let [baseline (watchers)]
      (dotimes [i 6] (expand! comp (odd? i)))
      (is (= baseline (watchers))
          "toggling expansion does not accumulate watches"))))

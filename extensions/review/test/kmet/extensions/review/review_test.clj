(ns kmet.extensions.review.review-test
  "Tests for the review extension's init/shutdown lifecycle, command
   registration, and arg parsing. The dialogs and command handlers
   exercise the live kmet extension context (ui-custom, ctx :mode,
   etc.) and are hard to unit-test without the host runtime — those
   flows are validated by manual /review and /end-review invocations
   in the TUI."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.extensions.review.core :as review]
            [kmet.extensions.review.dialogs :as dlg]))

;; -- init / shutdown -----------------------------------------------------

(deftest init-registers-commands-test
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (review/init api)
    (let [cmds (set (keys (:commands @state)))]
      (is (contains? cmds "review"))
      (is (contains? cmds "end-review")))))

(deftest init-registers-handlers-test
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (review/init api)
    (let [handlers (set (keys (:handlers @state)))]
      (is (contains? handlers :session-start))
      (is (contains? handlers :session-tree)))))

;; -- Arg tokenization ---------------------------------------------------

;; The tokenize-args and parse-args functions are private. We test
;; the user-visible surface: empty / invalid / unrecognized inputs
;; return :target nil (the caller shows the selector) and well-formed
;; inputs return the expected :target map.

;; -- parse-args (via #') -----------------------------------------------

(defn- parse [args]
  (try (#'review/parse-args args)
       (catch Exception e {:error (ex-message e)})))

(deftest parse-args-empty-test
  (is (= {:target nil} (parse "")))
  (is (= {:target nil} (parse nil))))

(deftest parse-args-uncommitted-test
  (is (= {:target {:type :uncommitted} :extra-instruction nil}
         (parse "uncommitted"))))

(deftest parse-args-base-branch-test
  (is (= {:target {:type :base-branch :branch "main"}
          :extra-instruction nil}
         (parse "branch main"))))

(deftest parse-args-base-branch-missing-test
  ;; No branch arg: target is nil (caller shows selector)
  (is (= {:target nil :extra-instruction nil}
         (parse "branch"))))

(deftest parse-args-commit-test
  (is (= {:target {:type :commit :sha "abc1234" :title nil}
          :extra-instruction nil}
         (parse "commit abc1234"))))

(deftest parse-args-commit-with-title-test
  (is (= {:target {:type :commit :sha "abc1234" :title "WIP flarble"}
          :extra-instruction nil}
         (parse "commit abc1234 WIP flarble"))))

(deftest parse-args-folder-test
  (is (= {:target {:type :folder :paths ["src" "docs"]}
          :extra-instruction nil}
         (parse "folder src docs"))))

(deftest parse-args-folder-missing-test
  (is (= {:target nil :extra-instruction nil}
         (parse "folder"))))

(deftest parse-args-extra-flag-test
  (testing "--extra with following arg"
    (is (= {:target {:type :uncommitted}
            :extra-instruction "focus on performance"}
           (parse "uncommitted --extra \"focus on performance\""))))
  (testing "--extra=value"
    (is (= {:target {:type :uncommitted}
            :extra-instruction "focus on errors"}
           (parse "uncommitted --extra=focus on errors"))))
  (testing "--extra without value"
    (let [r (parse "uncommitted --extra")]
      (is (contains? r :error)))))

(deftest parse-args-quoted-paths-test
  ;; Folder preserves spaces inside quotes (tokenizer keeps them as one
  ;; token; previously the join/split in parse-args dropped them).
  (is (= {:target {:type :folder :paths ["src/My File.clj" "docs"]}
          :extra-instruction nil}
         (parse "folder \"src/My File.clj\" docs"))))

(deftest parse-args-unknown-subcommand-test
  ;; unknown subcommand -> target nil, caller shows selector
  (is (= {:target nil} (parse "garbage"))))

;; -- preset-state + smart-default ---------------------------------------

(deftest smart-default-test
  ;; The smart default index matches the presets order:
  ;; 0 uncommitted, 1 base-branch, 2 commit
  (is (= 0 (dlg/smart-default true false)))
  (is (= 1 (dlg/smart-default false true)))
  (is (= 2 (dlg/smart-default false false))))

;; -- Custom review instructions -----------------------------------------
;;
;; The settings live as a `review-settings` custom entry. pi reads them
;; with sessionManager.getEntries (ALL entries), not getBranch: the
;; fresh-session jump branches away from the entry that was just saved, so
;; a branch-scoped read loses the instructions right when the review prompt
;; is assembled. The stubs below model exactly that split.
;;
;; Module-local atoms: @#'review/x derefs the var to its atom, @@#'… to the
;; value (SCI vars are not IAtoms themselves).

(defn- stub-review-api
  "Nullable api with a session facade whose current branch holds only the
   first user message (the state after the fresh-session jump) while
   ALL-ENTRIES is the whole session (get-all-entries). Records
   append-entry! calls and ui-calls."
  [all-entries]
  (let [{:keys [api] :as nullable} (ext/create-nullable-api)
        appended (atom [])
        branch (atom [{:id "u1" :role :user :content "hi"}])
        settings-type-matcher (fn [custom-type entry]
                                (and (= :custom (:role entry))
                                     (= custom-type (:custom-type entry))))
        session {:append-entry! (fn [custom-type data]
                                  (swap! appended conj {:custom-type custom-type
                                                        :data data})
                                  "gen-id")
                 :get-all-entries (fn [custom-type]
                                    (filterv #(settings-type-matcher custom-type %)
                                             all-entries))
                 :get-entries (fn [custom-type]
                                (filterv #(settings-type-matcher custom-type %) @branch))
                 :get-branch (fn [] @branch)
                 :get-leaf-id (fn [] "leaf-1")}]
    (assoc nullable
           :api (assoc api :session session)
           :appended appended)))

(defn- settings-entry [instructions]
  {:role :custom
   :custom-type "review-settings"
   :data {:custom-instructions instructions}})

(deftest review-settings-read-session-wide-test
  (reset! @#'review/review-custom-instructions nil)
  (let [{:keys [api]} (stub-review-api
                       [(settings-entry "First")
                        (settings-entry "Latest instruction")])]
    (#'review/apply-review-settings! api)
    (is (= "Latest instruction" @@#'review/review-custom-instructions)
        "last session-wide entry wins (pi: getEntries scan)")
    (let [prompt (#'review/assemble-review-prompt
                  api "/nonexistent-review-test-cwd" {:type :uncommitted} nil)]
      (is (str/includes? prompt "Shared custom review instructions"))
      (is (str/includes? prompt "Latest instruction")))))

(deftest execute-review-keeps-origin-and-instructions-test
  ;; The fresh-session branch jump emits :session-tree, whose handler
  ;; re-derives module state from the new branch. The origin must be
  ;; restored afterwards (pi: lockedOriginId) and the settings must still
  ;; be found session-wide — otherwise the review prompt silently drops
  ;; the custom instructions.
  (reset! @#'review/review-origin-id nil)
  (reset! @#'review/review-custom-instructions nil)
  (let [{:keys [api state appended]}
        (stub-review-api [(settings-entry "Check off-by-one errors")])
        nav-calls (atom [])
        ctx {:cwd "/nonexistent-review-test-cwd"
             :mode :interactive
             :navigate-tree (fn [target-id opts]
                              (swap! nav-calls conj [target-id opts])
                              (#'review/apply-review-settings! api)
                              (#'review/apply-review-state! api)
                              {:cancelled false})}]
    (is (true? (#'review/execute-review api ctx {:type :uncommitted} true nil)))
    (is (= [["u1" {:summarize false :label "code-review"}]] @nav-calls))
    (is (= "leaf-1" @@#'review/review-origin-id)
        "origin survives the :session-tree reset")
    (is (= [{:custom-type "review-session"
             :data {:active true :origin-id "leaf-1"}}]
           @appended))
    (let [sent (some (fn [[k text _]] (when (= k :send-user-message) text))
                     (:ui-calls @state))]
      (is (str/includes? sent "Check off-by-one errors")
          "custom instructions reach the review prompt"))))

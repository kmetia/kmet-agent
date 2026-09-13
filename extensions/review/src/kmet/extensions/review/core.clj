(ns kmet.extensions.review.core
  "Code review extension - port of pi-review to kmet with a
   language-agnostic rubric. Provides /review and /end-review slash
   commands, fresh-session branching with a code-review label, a
   Review active widget, project-level REVIEW_GUIDELINES.md loading,
   and shared custom review instructions persisted as session entries.

   PR review mode was dropped: pi-review needs the gh CLI plus a
   clean work tree to `gh pr checkout`, both environment-fragile. The
   same review is reachable via `branch <remote-branch>` once a PR's
   head is fetched.

   Module-level state - the active review's origin leaf - is process-
   local. A single review is active at a time (the UI and /end-review
   assume so); the origin is re-derived from the session on
   :session-start and :session-tree via the review-session entry."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.extensions.review.dialogs :as dlg]
            [kmet.extensions.review.git :as git]
            [kmet.extensions.review.prompts :as prompts]
            [kmet.libs.concurrent :as concurrent]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]))

(def ^:private spawn concurrent/spawn)

;; -- Constants ------------------------------------------------------------

(def ^:private review-state-type "review-session")
(def ^:private review-anchor-type "review-anchor")
(def ^:private review-settings-type "review-settings")

;; -- Module-local state ---------------------------------------------------

(defonce ^:private review-origin-id (atom nil))
(defonce ^:private end-review-in-progress (atom false))
(defonce ^:private review-custom-instructions (atom nil))

(defn- active-review? [] (some? @review-origin-id))

;; -- Project guidelines (pi: loadProjectReviewGuidelines) ----------------

(defn- project-review-guidelines
  "Read REVIEW_GUIDELINES.md beside the .kmet (or .pi) project marker.
   Walks up from CWD until a marker dir is found. The .pi fallback
   covers projects that still use the upstream pi monorepo convention
   (kmet already swaps its marker to .kmet)."
  [cwd]
  (let [start (try (str (fs/canonicalize (str cwd)))
                   (catch Exception _ (str cwd)))]
    (loop [dir start
           visited #{}]
      (let [kmet-dir (str dir "/.kmet")
            pi-dir (str dir "/.pi")
            guidelines (str dir "/REVIEW_GUIDELINES.md")
            found? (or (and (fs/exists? kmet-dir) (fs/directory? kmet-dir))
                       (and (fs/exists? pi-dir) (fs/directory? pi-dir)))]
        (cond
          found?
          (let [content (when (fs/exists? guidelines)
                          (try (slurp guidelines)
                               (catch Exception e
                                 (try
                                   (when-let [log-fn (resolve 'kmet.debug/log)]
                                     (log-fn "Failed to read REVIEW_GUIDELINES.md" e))
                                   (catch Throwable _ nil))
                                 nil)))]
            (when (and content (seq (str/trim content)))
              (str/trim content)))

          (contains? visited dir) nil

          :else
          (let [parent (try (str (fs/canonicalize (str (fs/parent dir))))
                            (catch Exception _ (str (fs/parent dir))))]
            (if (or (= parent dir) (contains? visited parent))
              nil
              (recur parent (conj visited dir)))))))))

;; -- Session state read/write --------------------------------------------

(defn- read-review-state
  "Most recent review-session custom entry on the active branch, or nil."
  [api]
  (let [entries ((:get-branch (ext/session api)))]
    (some (fn [entry]
            (when (and (= :custom (:role entry))
                       (= review-state-type (:custom-type entry)))
              (:data entry)))
          (reverse entries))))

(defn- read-review-settings
  "Most recent review-settings custom entry, or
   {:custom-instructions nil}."
  [api]
  (let [entries ((:get-entries (ext/session api)) review-settings-type)]
    (if-let [entry (last entries)]
      (let [data (:data entry)]
        {:custom-instructions (some-> data :custom-instructions str/trim not-empty)})
      {:custom-instructions nil})))

;; -- Widget (pi: setReviewWidget) ----------------------------------------

(defn- set-review-widget! [api active?]
  (ext/ui-set-widget api "review" nil)
  (when active?
    (ext/ui-set-widget
     api "review"
     (fn [_tui th]
       (h/compile-tree
        [:container {}
         [:text {:padding-x 1 :padding-y 0}
          (theme/fg th :warning "Review session active, return with /end-review")]
         [:spacer {:lines 1}]]))
     {:placement :above-editor})))

;; -- Settings persistence ------------------------------------------------

(defn- persist-review-settings! [api]
  ((:append-entry! (ext/session api))
   review-settings-type
   {:custom-instructions @review-custom-instructions}))

(defn- set-review-custom-instructions! [api instructions]
  (reset! review-custom-instructions
          (when (and instructions (seq (str/trim instructions)))
            (str/trim instructions)))
  (persist-review-settings! api))

;; -- State restore on session start / tree ------------------------------

(defn- apply-review-settings! [api]
  (let [{:keys [custom-instructions]} (read-review-settings api)]
    (reset! review-custom-instructions custom-instructions)))

(defn- apply-review-state!
  "Restore the active review from the session branch and refresh the
   widget. Called on :session-start and :session-tree."
  [api]
  (let [state (read-review-state api)]
    (if (and (:active state) (:origin-id state))
      (do (reset! review-origin-id (:origin-id state))
          (set-review-widget! api true))
      (do (reset! review-origin-id nil)
          (set-review-widget! api false)))))

;; -- Helpers: arg parsing (pi: tokenizeArgs / parseArgs) ----------------

(defn- tokenize-args
  "Sh+quoted argument tokenizer (pi: tokenizeArgs). Supports single
   and double quotes with backslash escapes. Returns a vector of
   token strings."
  [value]
  (loop [tokens []
         current ""
         quote nil
         i 0]
    (if (>= i (count value))
      (if (seq current)
        (conj tokens current)
        tokens)
      (let [char (.charAt value i)]
        (cond
          quote
          (if (= char \\)
            (if (>= (inc i) (count value))
              (recur tokens current quote (inc i))
              (recur tokens (str current (.charAt value (inc i))) quote (+ i 2)))
            (if (= char quote)
              (recur tokens current nil (inc i))
              (recur tokens (str current char) quote (inc i))))

          (or (= char \") (= char \'))
          (recur tokens current char (inc i))

          (Character/isWhitespace char)
          (recur (if (seq current) (conj tokens current) tokens)
                 ""
                 nil
                 (inc i))

          :else
          (recur tokens (str current char) nil (inc i)))))))

(def ^:private target-types
  "Valid /review subcommand values (pi: switch cases). Note: pi
   dispatches on the subcommand string directly (\"branch\" /\"
   commit\"), not on a normalized type name."
  #{:uncommitted :branch :commit :folder})

(defn- parse-args
  "Parse the /review argument string. Returns
   {:target ... :extra-instruction ... :error ...} where TARGET is one
   of {:type :uncommitted}, {:type :base-branch :branch str},
   {:type :commit :sha str :title str-or-nil}, or
   {:type :folder :paths [str ...]}. --extra extracts the per-call
   extra instruction."
  [args]
  (if (str/blank? args)
    {:target nil}
    (let [;; pre-extract --extra=<rest-of-string> as a single value
          ;; before tokenizing (pi: --key=val captures the rest as
          ;; one token, even when it contains spaces)
          [args pre-extra] (let [trimmed (str/trim args)
                                 m (re-matches #"(?s)(.*?)--extra=(.*)" trimmed)]
                             (if m
                               [(str/trim (second m))
                                (str/trim (nth m 2))]
                               [trimmed nil]))
          raw (tokenize-args args)
          parts+extra (reduce
                       (fn [acc part]
                         (cond
                           (and (str/starts-with? part "--extra=")
                                (not (:in-extra acc)))
                           (assoc acc :in-extra true
                                  :extra-instruction (subs part 8))

                           (and (= part "--extra")
                                (not (:in-extra acc)))
                           (assoc acc :in-extra true)

                           (:in-extra acc)
                           (assoc acc :in-extra false
                                  :extra-instruction part)

                           :else
                           (update acc :parts conj part)))
                       {:parts [] :extra-instruction nil :in-extra false}
                       raw)
          _ (when (:in-extra parts+extra)
              (throw (ex-info "Missing value for --extra" {:args args})))
          parts (:parts parts+extra)
          extra (:extra-instruction parts+extra)
          sub (when (seq parts) (keyword (str/lower-case (first parts))))]
      (if (and sub (not (contains? target-types sub)))
        (if (or extra pre-extra)
          {:target nil :extra-instruction (or extra pre-extra)}
          {:target nil})
        (case sub
          nil {:target nil :extra-instruction (or extra pre-extra)}
          :uncommitted {:target {:type :uncommitted}
                        :extra-instruction (or extra pre-extra)}
          :branch (let [branch (second parts)]
                    (if branch
                      {:target {:type :base-branch :branch branch}
                       :extra-instruction (or extra pre-extra)}
                      {:target nil :extra-instruction (or extra pre-extra)}))
          :commit (let [sha (second parts)]
                    (if sha
                      (let [rest-parts (drop 2 parts)
                            title (when (seq rest-parts)
                                    (let [s (str/join " " rest-parts)]
                                      (when (seq (str/trim s)) s)))]
                        {:target {:type :commit :sha sha :title title}
                         :extra-instruction (or extra pre-extra)})
                      {:target nil :extra-instruction (or extra pre-extra)}))
          :folder (let [paths (->> (rest parts)
                                   (map str/trim)
                                   (remove str/blank?)
                                   vec)]
                    (if (seq paths)
                      {:target {:type :folder :paths paths}
                       :extra-instruction (or extra pre-extra)}
                      {:target nil :extra-instruction (or extra pre-extra)})))))))

;; -- Merge-base resolution -----------------------------------------------

(defn- resolve-merge-base [api branch]
  (or (git/merge-base api branch)
      (git/merge-base-fallback api branch)))

;; -- Preset state --------------------------------------------------------

(defn- preset-state [api]
  (let [uncommitted? (git/uncommitted? api)
        current (git/current-branch api)
        default (git/default-branch api)
        on-feature? (and current (not= current default))
        smart (dlg/smart-default uncommitted? on-feature?)
        custom-set? (some? @review-custom-instructions)]
    {:smart-default smart
     :custom-set? custom-set?}))

;; -- Picking a target via the dialogs ------------------------------------

(defn- show-target-dialog!
  "Walk the user through the preset selector and any sub-dialogs to
   arrive at a target map. Returns nil on cancel."
  [api]
  (loop []
    (let [{:keys [smart-default custom-set?]} (preset-state api)
          picked (dlg/show-preset-selector! api custom-set? smart-default)]
      (cond
        (nil? picked) nil

        (= :toggle-custom-instructions picked)
        (do (if custom-set?
              (do (set-review-custom-instructions! api nil)
                  (ext/ui-notify api "Custom review instructions removed" :info))
              (let [text (dlg/show-custom-instructions-input! api)]
                (if (and (some? text) (not (str/blank? text)))
                  (do (set-review-custom-instructions! api text)
                      (ext/ui-notify api "Custom review instructions saved" :info))
                  (ext/ui-notify api "Custom review instructions not changed" :info))))
            (recur))

        (= :uncommitted picked)
        {:type :uncommitted}

        (= :base-branch picked)
        (let [branches (git/local-branches api)
              current (git/current-branch api)
              default (git/default-branch api)
              candidates (if current
                           (filterv #(not= % current) branches)
                           (vec branches))]
          (if (empty? candidates)
            (do (ext/ui-notify api
                               (str "No other branches found"
                                    (when current (str " (current branch: " current ")")))
                               :error)
                (recur))
            (let [sorted (vec (sort-by (fn [b]
                                         [(not= b default) (str/lower-case b)])
                                       candidates))
                  items (mapv (fn [b]
                                {:value b
                                 :label b
                                 :description (when (= b default) "(default)")})
                              sorted)
                  picked-item (dlg/show-branch-selector! api items
                                                         "Select base branch")]
              (if picked-item
                {:type :base-branch :branch (:value picked-item)}
                (recur)))))

        (= :commit picked)
        (let [commits (git/recent-commits api 20)]
          (if (empty? commits)
            (do (ext/ui-notify api "No commits found" :error) (recur))
            (let [items (mapv (fn [c]
                                {:value (:sha c)
                                 :label (str (subs (:sha c) 0 (min 7 (count (:sha c))))
                                             " " (:title c))})
                              commits)
                  picked-item (dlg/show-commit-selector! api items)]
              (if picked-item
                {:type :commit
                 :sha (:value picked-item)
                 :title (:title picked-item)}
                (recur)))))

        (= :folder picked)
        ;; nil = esc (back to the preset picker, pi: showReviewSelector
        ;; `break`); [] = blank submit (loop the folder input, pi:
        ;; showFolderInput returns null on empty paths and the selector
        ;; loop re-prompts)
        (let [paths (dlg/show-folder-input! api)]
          (cond
            (nil? paths) (recur)
            (empty? paths) (recur)
            :else {:type :folder :paths paths}))

        :else nil))))

;; -- Build the full review prompt ---------------------------------------

(defn- assemble-review-prompt [_api cwd target extra-instruction]
  (let [body (prompts/build-review-prompt target)
        project (project-review-guidelines cwd)]
    (str prompts/review-rubric
         "\n\n---\n\nPlease perform a code review with the following focus:\n\n"
         body
         (when-let [custom @review-custom-instructions]
           (str "\n\nShared custom review instructions (applies to all reviews):\n\n"
                custom))
         (when (and extra-instruction (seq (str/trim extra-instruction)))
           (str "\n\nAdditional user-provided review instruction:\n\n"
                (str/trim extra-instruction)))
         (when project
           (str "\n\nThis project has additional instructions for code reviews:\n\n"
                project)))))

;; -- Execute the review (pi: executeReview) ------------------------------

(defn- execute-review
  [api ctx target use-fresh-session extra-instruction]
  (cond
    (active-review?)
    (do (ext/ui-notify api "Already in a review. Use /end-review to finish first."
                       :warning)
        false)

    :else
    (let [merge-base (when (= :base-branch (:type target))
                       (resolve-merge-base api (:branch target)))
          target (assoc target :merge-base merge-base)]
      (if use-fresh-session
        (let [leaf ((:get-leaf-id (ext/session api)))
              origin (if leaf
                       (do (reset! review-origin-id leaf) leaf)
                       (do ((:append-entry! (ext/session api))
                            review-anchor-type
                            {:created-at (str (java.util.Date.))})
                           (let [leaf2 ((:get-leaf-id (ext/session api)))]
                             (when leaf2 (reset! review-origin-id leaf2))
                             leaf2)))]
          (if (nil? origin)
            (do (ext/ui-notify api "Failed to create review anchor - not in a session" :error)
                false)
            (let [entries ((:get-branch (ext/session api)))
                  first-user (some (fn [e]
                                     (when (= :user (:role e))
                                       e))
                                   entries)]
              (if first-user
                (let [nav-result (try
                                   ((:navigate-tree ctx) (:id first-user)
                                                         {:summarize false :label "code-review"})
                                   (catch Exception _ {:error true}))]
                  (cond
                    (:error nav-result)
                    (do (reset! review-origin-id nil)
                        (ext/ui-notify api "Failed to start review: navigate-tree failed" :error)
                        false)

                    (:cancelled nav-result)
                    (do (reset! review-origin-id nil) false)

                    :else
                    (do (ext/ui-set-editor-text api "")
                        (set-review-widget! api true)
                        ((:append-entry! (ext/session api))
                         review-state-type
                         {:active true :origin-id origin})
                        (let [prompt (assemble-review-prompt api (or (:cwd ctx) (System/getProperty "user.dir")) target extra-instruction)
                              hint (prompts/user-facing-hint target)]
                          (ext/ui-notify api (str "Starting review: " hint " (fresh session)") :info)
                          (ext/send-user-message api prompt)
                          true))))
                (do (set-review-widget! api true)
                    ((:append-entry! (ext/session api))
                     review-state-type
                     {:active true :origin-id origin})
                    (let [prompt (assemble-review-prompt api (or (:cwd ctx) (System/getProperty "user.dir")) target extra-instruction)
                          hint (prompts/user-facing-hint target)]
                      (ext/ui-notify api (str "Starting review: " hint " (fresh session)") :info)
                      (ext/send-user-message api prompt)
                      true))))))
        ;; non-fresh session: just send the review prompt in place
        (let [prompt (assemble-review-prompt api (or (:cwd ctx) (System/getProperty "user.dir")) target extra-instruction)
              hint (prompts/user-facing-hint target)]
          (ext/ui-notify api (str "Starting review: " hint) :info)
          (ext/send-user-message api prompt)
          true)))))

;; -- /review command -----------------------------------------------------

(defn- handle-review-command [api ctx args]
  (cond
    (not= :interactive (:mode ctx))
    (ext/ui-notify api "Review requires interactive mode" :error)

    (active-review?)
    (ext/ui-notify api "Already in a review. Use /end-review to finish first."
                   :warning)

    (not (git/in-git-repo? api))
    (ext/ui-notify api "Not a git repository" :error)

    :else
    (let [{:keys [target extra-instruction error]}
          (try (parse-args args)
               (catch Exception e
                 {:error (ex-message e)}))]
      (if error
        (ext/ui-notify api error :error)
        (if target
          ;; Direct invocation with parsed target — still needs the
          ;; "Empty branch / Current session" choice when the session
          ;; already has messages (pi parity).
          (let [entries ((:get-branch (ext/session api)))
                message-count (count (filter #(contains? #{:user :assistant :tool :bash} (:role %)) entries))]
            (if (zero? message-count)
              (execute-review api ctx target true extra-instruction)
              (spawn
               (fn []
                 (try
                   (when-let [choice (dlg/show-review-location-selector! api)]
                     (let [use-fresh? (= choice :empty-branch)]
                       (execute-review api ctx target use-fresh? extra-instruction)))
                   (catch Exception e
                     (ext/ui-notify api (str "Review failed: " (or (ex-message e) (str e))) :error)))))))
          ;; No target from args — walk the dialog off the dispatch thread
          ;; so the reader loop keeps draining keys into the overlay
          ;; (tui.md §7: input is focused-leaf only; blocking the
          ;; dispatch thread stalls process-input-buffer! and close
          ;; never fires).
          (spawn
           (fn []
             (try
               (loop [picked (show-target-dialog! api)]
                 (if (nil? picked)
                   (ext/ui-notify api "Review cancelled" :info)
                   (let [entries ((:get-branch (ext/session api)))
                         message-count (count (filter #(contains? #{:user :assistant :tool :bash} (:role %)) entries))]
                     (if (zero? message-count)
                       (execute-review api ctx picked true extra-instruction)
                       (let [choice (dlg/show-review-location-selector! api)]
                         (cond
                           (nil? choice)
                           ;; cancelled "Start review in:" — go back to preset picker (pi parity)
                           (recur (show-target-dialog! api))

                           (= choice :empty-branch)
                           (execute-review api ctx picked true extra-instruction)

                           (= choice :current-session)
                           (execute-review api ctx picked false extra-instruction)))))))
               (catch Exception e
                 (ext/ui-notify api (str "Review failed: " (or (ex-message e) (str e))) :error))))))))))

;; -- End review ----------------------------------------------------------

(defn- get-active-origin [api _ctx]
  (or @review-origin-id
      (let [state (read-review-state api)]
        (cond
          (and (:active state) (:origin-id state))
          (do (reset! review-origin-id (:origin-id state))
              (:origin-id state))

          (:active state)
          (do (set-review-widget! api false)
              ((:append-entry! (ext/session api))
               review-state-type {:active false})
              (ext/ui-notify api
                             "Review state was missing origin info; cleared review status."
                             :warning)
              nil)

          :else nil))))

(defn- clear-review-state! [api]
  (set-review-widget! api false)
  (reset! review-origin-id nil)
  ((:append-entry! (ext/session api))
   review-state-type {:active false}))

(defn- navigate-back [_api ctx origin summarize?]
  (try
    (let [result ((:navigate-tree ctx) origin
                                       (cond-> {:summarize (boolean summarize?)}
                                         summarize? (assoc :custom-instructions
                                                           prompts/review-summary-prompt
                                                           :replace-instructions true)))]
      (cond
        (:cancelled result) {:ok? false :cancelled? true}
        :else {:ok? true}))
    (catch Exception e
      {:ok? false
       :error (or (ex-message e) (str e))})))

(defn- end-review! [api ctx action]
  (let [origin (get-active-origin api ctx)]
    (cond
      (nil? origin)
      (do (when-not (read-review-state api)
            (ext/ui-notify api
                           (str "Not in a review branch (use /review first, or "
                                "review was started in current session mode)")
                           :info))
          :error)

      (= :return-only action)
      (let [r (navigate-back api ctx origin false)]
        (cond
          (:cancelled? r)
          (do (ext/ui-notify api "Navigation cancelled. Use /end-review to try again." :info)
              :cancelled)
          (:error r)
          (do (ext/ui-notify api (str "Failed to return: " (:error r)) :error) :error)
          :else
          (do (clear-review-state! api)
              (ext/ui-notify api "Review complete! Returned to original position." :info)
              :ok)))

      :else
      (let [r (navigate-back api ctx origin true)]
        (cond
          (:cancelled? r)
          (do (ext/ui-notify api "Summarization cancelled. Use /end-review to try again." :info)
              :cancelled)
          (:error r)
          (do (ext/ui-notify api (str "Summarization failed: " (:error r)) :error) :error)
          :else
          (do (clear-review-state! api)
              (case action
                :return-and-summarize
                (do (when (str/blank? (or (try (ext/ui-get-editor-text api)
                                               (catch Exception _ ""))
                                          ""))
                      (ext/ui-set-editor-text api "Act on the review findings"))
                    (ext/ui-notify api "Review complete! Returned and summarized." :info)
                    :ok)

                :return-and-fix
                (do (ext/send-user-message api prompts/review-fix-findings-prompt
                                           {:deliver-as :follow-up})
                    (ext/ui-notify api
                                   "Review complete! Returned and queued a follow-up to fix findings."
                                   :info)
                    :ok))))))))

(defn- handle-end-review-command [api ctx]
  (if-not (compare-and-set! end-review-in-progress false true)
    (ext/ui-notify api "/end-review is already running" :info)
    ;; Selector blocks on its ui-custom promise — run off the
    ;; dispatch thread like /review above.
    (spawn
     (fn []
       (try
         (if-not (= :interactive (:mode ctx))
           (ext/ui-notify api "End-review requires interactive mode" :error)
           (let [choice (dlg/show-end-review-selector! api)]
             (if (nil? choice)
               (ext/ui-notify api "Cancelled. Use /end-review to try again." :info)
               (case choice
                 :return-and-summarize (end-review! api ctx :return-and-summarize)
                 :return-and-fix (end-review! api ctx :return-and-fix)
                 :return-only (end-review! api ctx :return-only)))))
         (catch Exception e
           (ext/ui-notify api (str "Review failed: " (or (ex-message e) (str e))) :error))
         (finally
           (reset! end-review-in-progress false)))))))

;; -- Init / shutdown -----------------------------------------------------

(defn init [api]
  (ext/on-event api :session-start
                (fn [_ev _ctx]
                  (apply-review-settings! api)
                  (apply-review-state! api)))
  (ext/on-event api :session-tree
                (fn [_ev _ctx]
                  (apply-review-settings! api)
                  (apply-review-state! api)))
  (ext/register-command!
   api
   {:name "review"
    :description "Review code changes (uncommitted, base branch, commit, or folder)"
    :handler (fn [ctx args]
               (handle-review-command api ctx args))})
  (ext/register-command!
   api
   {:name "end-review"
    :description "Complete review and return to the original position"
    :handler (fn [ctx _args]
               (handle-end-review-command api ctx))}))

(defn shutdown [_api] nil)


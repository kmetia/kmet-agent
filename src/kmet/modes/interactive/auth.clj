(ns kmet.modes.interactive.auth
  "Login/logout flows for the interactive mode: provider option
   discovery, auth-type selection, API-key and OAuth login (pi:
   getLoginProviderOptions / showLoginAuthTypeSelector / showLoginDialog /
   showApiKeyLoginDialog / OAuthSelectorComponent). The command handlers
   are registered by kmet.modes.interactive.commands."
  (:require [clojure.string :as str]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.app.ui.auth-selector :as auth-selector]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.login-dialog :as login-dialog]
            [kmet.ai.auth :as auth]
            [kmet.ai.models :as models]
            [kmet.modes.interactive.state :as state]))

;; ─── Login/logout (pi getLoginProviderOptions / showLoginAuthTypeSelector /
;;    showLoginDialog / showApiKeyLoginDialog / OAuthSelectorComponent) ──────

(def ^:private api-key-login-label "Sign in with an API key")

(declare show-login-provider-selector!)

(defn- oauth-prompt!
  "Show PROMPT inside the dock-mounted login dialog and block for the
   entered string (pi showAuthPrompt → LoginDialogComponent.showPrompt /
   showManualInput / showAuthSelect; the flow runs on a future, so kmet
   blocks on the promise). :select swaps the dock to a method selector and
   restores the dialog after; :text/:secret prompts through the dialog's
   input; :manual-code is the manual-paste variant. The pending promise is
   registered in PROMPT-STATE so a loopback flow's :abort-prompt! can
   settle it when the browser callback wins the race. Dialog cancel settles
   the promise with the cancellation ex-info, which propagates here."
  [cs dlg prompt prompt-state]
  (case (:type prompt)
    :select
    (let [p (promise)
          labels (mapv :label (:options prompt))
          ;; pi showAuthSelect: swap the dock to the selector, restore the
          ;; login dialog when it resolves (re-mounting IS the restore)
          sel-atom (atom nil)
          restore #(dock/mount! cs dlg nil {:borrowed? true})
          sel (auth-selector/make-auth-method-selector
               (:message prompt) labels
               (fn [label]
                 (state/close-selector! sel-atom)
                 (restore)
                 (deliver p (or (:id (first (filter #(= label (:label %)) (:options prompt))))
                                label)))
               (fn []
                 (state/close-selector! sel-atom)
                 (restore)
                 (deliver p (ex-info "Login cancelled" {:type :login-cancelled}))))]
      (reset! prompt-state {:promise p})
      (state/mount-selector! cs sel-atom sel)
      (login-dialog/await-prompt! p))

    :manual-code
    (let [p (login-dialog/login-dialog-show-manual-input! dlg (:message prompt))]
      (reset! prompt-state {:promise p})
      (login-dialog/await-prompt! p))

    (let [p (login-dialog/login-dialog-show-prompt!
             dlg (:message prompt) (:placeholder prompt))]
      (reset! prompt-state {:promise p})
      (login-dialog/await-prompt! p))))

(defn- oauth-notify!
  "Map an OAuth AuthEvent onto the dock-mounted login dialog (pi
   notifyAuthDialog): :device-code shows the verification URI + user code
   and the waiting line; :auth-url shows the URL (+ instructions) and opens
   the browser; :info/:progress append dim lines."
  [dlg event]
  (case (:type event)
    :device-code
    (do (login-dialog/login-dialog-show-device-code!
         dlg (:verification-uri event) (:user-code event))
        (login-dialog/login-dialog-show-waiting! dlg "Waiting for authentication..."))
    :auth-url
    (login-dialog/login-dialog-show-auth! dlg (:url event) (:instructions event))
    :info
    (login-dialog/login-dialog-show-info! dlg (:message event))
    (login-dialog/login-dialog-show-progress! dlg (:message event)))
  nil)

(defn- oauth-login!
  "Run an OAuthAuth login flow on a future with a dock-mounted login dialog
   (pi showLoginDialog): prompts and auth events render inside the dialog;
   on success the credential is persisted to auth.edn and the editor is
   restored. Escape cancels the flow through the dialog's on-complete.
   :abort-prompt! — the loopback flows' race hook — settles the pending
   manual-paste prompt as cancelled when the browser callback wins (pi
   manualAbort.abort())."
  [cs provider]
  (let [oauth (:oauth provider)
        signal (atom false)
        prompt-cancelled (atom false)
        prompt-state (atom nil)
        cancel-pending! (fn []
                          (reset! prompt-cancelled true)
                          (when-let [p (:promise @prompt-state)]
                            (deliver p (ex-info "Login cancelled"
                                                {:type :login-cancelled}))))
        dlg (login-dialog/make-login-dialog
             (:tui cs) (:name provider)
             (fn [_success _message]
               ;; escape — abort the flow like pi's AbortController; the
               ;; future unwinds at the next signal check and restores
               (reset! signal true)
               (cancel-pending!)))
        prompt-fn (fn [prompt]
                    (when @prompt-cancelled
                      (throw (ex-info "Login cancelled" {:type :login-cancelled})))
                    (oauth-prompt! cs dlg prompt prompt-state))
        interaction {:signal signal
                     :prompt prompt-fn
                     :abort-prompt! cancel-pending!
                     :notify (fn [event] (oauth-notify! dlg event))}
        done (dock/mount! cs dlg nil {:borrowed? true})]
    (future
      (try
        (let [credential ((:login oauth) interaction)]
          (auth/set-oauth-credential! (:id provider) credential)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant
                                                   :content (str "Logged in to " (:name provider)
                                                                 ". Credentials saved to "
                                                                 (auth/auth-file-path) ".")})
          (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
            (state/update-footer! cs)))
        (catch Exception e
          ;; pi: silent on "Login cancelled", an error otherwise
          (when-not (str/includes? (or (ex-message e) "") "Login cancelled")
            (chat-history/show-warning! (:chat-history cs)
                                        (str "Failed to login to " (:name provider) ": "
                                             (ex-message e)))))
        (finally
          (done)
          ;; release the dialog's content-tree reaction (rows watches) —
          ;; the dialog leaves the dock for good here
          (protocols/dispose dlg)
          (tui/tui-request-render (:tui cs)))))))

(defn- api-key-login!
  "The api-key login flow (pi showApiKeyLoginDialog): the key is prompted
   inside a dock-mounted \"Login to <provider>\" dialog and saved to
   auth.edn."
  [cs p]
  (let [dlg (login-dialog/make-login-dialog
             (:tui cs) (:name p)
             ;; escape before submitting — nothing to abort, silently
             ;; restore like pi (the "Login cancelled" error is suppressed)
             (fn [_success _message] nil))
        done (dock/mount! cs dlg nil {:borrowed? true})]
    (future
      (try
        (let [key (str/trim (login-dialog/await-prompt!
                             (login-dialog/login-dialog-show-prompt!
                              dlg (str "Enter " (:name p) " API key") nil)))]
          (if (seq key)
            (do (auth/set-credential! (:id p) key)
                (chat-history/chat-history-add-message! (:chat-history cs)
                                                        {:role :assistant
                                                         :content (str "Saved API key for " (:name p)
                                                                       ". Credentials saved to "
                                                                       (auth/auth-file-path) ".")})
                (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
                  (state/update-footer! cs)))
            (chat-history/show-warning! (:chat-history cs)
                                        "No API key entered — nothing saved.")))
        (catch Exception e
          (when-not (str/includes? (or (ex-message e) "") "Login cancelled")
            (chat-history/show-warning! (:chat-history cs)
                                        (str "Failed to save API key for " (:name p) ": "
                                             (ex-message e)))))
        (finally
          (done)
          ;; release the dialog's content-tree reaction (rows watches) —
          ;; the dialog leaves the dock for good here
          (protocols/dispose dlg)
          (tui/tui-request-render (:tui cs)))))))

;; ─── Provider options (pi getLoginProviderOptions / getLogoutProviderOptions
;;    / findLoginProviderOptions / handleLoginCommand) ────────────────────────

(defn- format-auth-type-label
  "pi formatAuthSelectorProviderType."
  [auth-type]
  (if (= :oauth auth-type) "subscription" "API key"))

(defn- api-key-login-path?
  "True when the provider offers an api-key login (env vars, models.edn
   configured key, or :auth-header) — openai-codex is oauth-only."
  [p]
  (or (seq (:env-vars p)) (:api-key p) (:auth-header p)))

(defn- login-provider-options
  "pi getLoginProviderOptions: one entry per offered auth type per provider,
   sorted by display name. Each entry carries the provider's auth status
   (auth/provider-auth-status) for the selector's ✓/• indicator."
  []
  (->> (models/get-providers)
       (mapcat (fn [p]
                 (let [status (auth/provider-auth-status (:id p))]
                   (concat
                    (when (:oauth p)
                      [{:id (name (:id p)) :name (:name p) :auth-type :oauth
                        :method-name (:name (:oauth p)) :status status
                        :provider p}])
                    (when (api-key-login-path? p)
                      [{:id (name (:id p)) :name (:name p) :auth-type :api-key
                        :status status :provider p}])))))
       (sort-by :name)))

(defn- logout-provider-options
  "pi getLogoutProviderOptions: one entry per stored credential, sorted by
   display name (the id stands in for providers no longer registered)."
  []
  (->> (auth/get-credentials)
       (mapv (fn [[pid cred]]
               (let [type (if (= :oauth (:type cred)) :oauth :api-key)
                     p (models/get-provider pid)]
                 {:id (name pid)
                  :name (or (:name p) (name pid))
                  :auth-type type
                  :status {:configured? true :type type :source "stored credential"}
                  :provider p})))
       (sort-by :name)))

(defn- find-login-provider-options
  "pi findLoginProviderOptions: exact id or name match on the lowercased
   reference, empty when nothing matches."
  [provider-ref]
  (let [ref (str/lower-case (str/trim (or provider-ref "")))]
    (when (seq ref)
      (filterv #(or (= (str/lower-case (:id %)) ref)
                    (= (str/lower-case (:name %)) ref))
               (login-provider-options)))))

(defn- start-provider-login!
  "pi startProviderLogin: oauth entries run the OAuth flow, api-key entries
   the key prompt."
  [cs entry]
  (if (= :oauth (:auth-type entry))
    (oauth-login! cs (:provider entry))
    (api-key-login! cs (:provider entry))))

(defn- show-login-auth-type-selector!
  "Offer a provider's (or the global) auth methods (pi
   showLoginAuthTypeSelector): the oauth subscription label (the OAuthAuth's
   :login-label or \"Sign in with an account\") and \"Sign in with an API
   key\" in a dock-mounted method selector; a single available method starts
   directly."
  ([cs] (show-login-auth-type-selector! cs nil))
  ([cs provider-options]
   (let [oauth-entry (some #(when (= :oauth (:auth-type %)) %) provider-options)
         subscription-label (or (when oauth-entry
                                  (:login-label (:oauth (:provider oauth-entry))))
                                "Sign in with an account")
         available-types (if provider-options
                           (set (map :auth-type provider-options))
                           #{:oauth :api-key})
         options (cond-> []
                   (contains? available-types :oauth) (conj subscription-label)
                   (contains? available-types :api-key) (conj api-key-login-label))]
     (cond
       (empty? options)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant
                                                :content "No login methods available."})

       (and provider-options (= 1 (count options)))
       (start-provider-login! cs (first provider-options))

       :else
       (let [title (if (seq provider-options)
                     (str "Select authentication method for "
                          (:name (first provider-options)) ":")
                     "Select authentication method:")
             sel-atom (atom nil)
             sel (auth-selector/make-auth-method-selector
                  title options
                  (fn [label]
                    (state/close-selector! sel-atom)
                    (let [auth-type (if (= label subscription-label)
                                      :oauth :api-key)]
                      (if provider-options
                        (when-let [entry (some #(when (= auth-type (:auth-type %)) %)
                                               provider-options)]
                          (start-provider-login! cs entry))
                        (show-login-provider-selector! cs auth-type))))
                  (fn []
                    (state/close-selector! sel-atom)
                    (tui/tui-request-render (:tui cs))))]
         (state/mount-selector! cs sel-atom sel))))))

(defn- show-login-provider-selector!
  "pi showLoginProviderSelector: the searchable provider selector over the
   auth-type-filtered options; SEARCH pre-fills the filter (an unmatched
   /login argument). Cancel reopens the auth-type selector when one was
   shown (pi), otherwise just restores the editor."
  ([cs auth-type] (show-login-provider-selector! cs auth-type nil))
  ([cs auth-type search]
   (let [entries (vec (cond->> (login-provider-options)
                        auth-type (filter #(= auth-type (:auth-type %)))))]
     (if (empty? entries)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant
                                                :content (case auth-type
                                                           :oauth "No subscription providers available."
                                                           :api-key "No API key providers available."
                                                           "No login providers available.")})
       (let [sel-atom (atom nil)
             sel (auth-selector/make-auth-selector
                  :login entries
                  (fn [provider-id selected-type]
                    (state/close-selector! sel-atom)
                    (if-let [entry (some #(when (and (= provider-id (:id %))
                                                     (= selected-type (:auth-type %)))
                                            %)
                                         entries)]
                      (start-provider-login! cs entry)
                      (tui/tui-request-render (:tui cs))))
                  (fn []
                    (state/close-selector! sel-atom)
                    (if auth-type
                      (show-login-auth-type-selector! cs)
                      (tui/tui-request-render (:tui cs))))
                  search)]
         (state/mount-selector! cs sel-atom sel))))))

(defn login-argument-completions
  "pi getArgumentCompletions for /login (getLoginProviderCompletionOptions +
   createFuzzyAutocompleteItems): one item per provider id with both auth
   types merged, sorted by display name, fuzzy-filtered over
   \"id name auth-types\" — the value completes to the provider id and the
   description reads \"Name · subscription/API key\"."
  [prefix]
  (let [options (->> (login-provider-options)
                     (group-by :id)
                     (mapv (fn [[id entries]]
                             (let [types (->> entries
                                              (map :auth-type) distinct
                                              (sort-by {:oauth 0 :api-key 1}))
                                   pname (:name (first entries))
                                   labels (map format-auth-type-label types)]
                               {:id id :name pname
                                :type-desc (str/join "/" labels)
                                :search (str/join " "
                                                  (concat [id pname]
                                                          (for [[t l] (map vector types labels)
                                                                part [(clojure.core/name t) l]]
                                                            part)))})))
                     (sort-by :name))
        filtered (fuzzy/fuzzy-filter options prefix :search)]
    (when (seq filtered)
      (mapv (fn [{:keys [id name type-desc]}]
              {:value id :label id
               :description (if (= name id) type-desc (str name " · " type-desc))})
            filtered))))

(defn handle-login-command!
  "pi handleLoginCommand: bare /login opens the auth-type selector; a
   reference resolves by exact id/name (case-insensitive) — one hit starts
   directly, several hits on one provider open its method selector, and no
   hit opens the provider selector pre-filtered with the typed text."
  [cs provider-ref]
  (if (str/blank? provider-ref)
    (show-login-auth-type-selector! cs)
    (let [options (find-login-provider-options provider-ref)]
      (cond
        (= 1 (count options))
        (start-provider-login! cs (first options))

        (and (pos? (count options))
             (= 1 (count (distinct (map :id options)))))
        (show-login-auth-type-selector! cs options)

        :else
        (show-login-provider-selector! cs nil provider-ref)))))

(defn handle-logout-command!
  "pi showOAuthSelector(\"logout\"): the searchable selector over the stored
   credentials; selecting removes the credential (pi modelRuntime.logout)."
  [cs]
  (let [entries (logout-provider-options)]
    (if (empty? entries)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content "No stored credentials to remove. /logout only removes credentials saved by /login; environment variables are unchanged."})
      (let [sel-atom (atom nil)
            sel (auth-selector/make-auth-selector
                 :logout entries
                 (fn [provider-id _selected-type]
                   (state/close-selector! sel-atom)
                   (if-let [entry (some #(when (= provider-id (:id %)) %) entries)]
                     (try
                       (auth/remove-credential! (keyword provider-id))
                       (when (and (:session-atom cs) (:footer-comp cs)
                                  (:footer-provider cs))
                         (state/update-footer! cs))
                       (chat-history/chat-history-add-message! (:chat-history cs)
                                                               {:role :assistant
                                                                :content (if (= :oauth (:auth-type entry))
                                                                           (str "Logged out of " (:name entry))
                                                                           (str "Removed stored API key for " (:name entry)
                                                                                ". Environment variables are unchanged."))})
                       (catch Exception e
                         (chat-history/show-warning! (:chat-history cs)
                                                     (str "Logout failed: " (ex-message e)))))
                     (tui/tui-request-render (:tui cs))))
                 (fn []
                   (state/close-selector! sel-atom)
                   (tui/tui-request-render (:tui cs))))]
        (state/mount-selector! cs sel-atom sel)))))

(ns kmet.extensions.mcp-adapter.auth
  "OAuth adapter for HTTP MCP servers (§7.8 of the design contract — pi:
   mcp-auth.ts / mcp-oauth-provider.ts / oauth-handler.ts /
   mcp-auth-flow.ts / mcp-callback-server.ts, adapted onto the generic
   machinery in kmet.libs.oauth).

   Thin adapter: the interactive flow's host side (the browser/callback
   server race, the manual-paste prompt, status text) lives here; the
   protocol steps live in kmet.libs.mcp.auth (mcp.md Phase 2) — issuer
   binding and the credential store (SEP-2352), discovery, client
   registration incl. the SEP-837 application_type, PKCE request/response
   handling with the RFC 9207 `iss` check, the device flow, and the
   request-auth header provider. The extension cannot require kmet.ai.*,
   so the generic machinery lives in kmet.libs.oauth (RFC 8414 discovery,
   RFC 7591 DCR, PKCE loopback + RFC 8628 device flows, token
   exchange/refresh).

   Flow (per server, §7.8):
     1. token lookup — expired → refresh; missing/refresh-failed → flow
     2. discovery (RFC 9728 protected-resource metadata for the AS
        location — the 401 WWW-Authenticate `resource_metadata` when we
        have seen one, else the well-known probes — then RFC 8414 / OIDC
        authorization-server metadata; :authorization-server-url skips
        discovery)
     3. client registration (RFC 7591) unless :oauth {:client-id ...}
     4. authorization — PKCE loopback on an OS-assigned port (default), or
        the RFC 8628 device flow (forced via :flow :device, or auto when
        the metadata exposes a device endpoint and the host is headless)
     5. tokens stored; requests attach Authorization: Bearer; 401 with a
        stored refresh token → refresh once + retry once"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.libs.mcp.auth :as mcp-auth]
            [kmet.libs.oauth :as oauth-lib]))

;; ─── kmet.libs.mcp.auth re-exports ────────────────────────────────────────
;; The policy, discovery, flow steps, request-auth and store machinery
;; lives in the lib (mcp.md Phase 2). These aliases keep the validation
;; scripts' call surface (auth/<name>) stable; the extension calls the lib
;; directly where a name is not part of that surface.

(def canonical-resource-uri mcp-auth/canonical-resource-uri)
(def parse-www-authenticate mcp-auth/parse-www-authenticate)
(def discover-meta mcp-auth/discover-meta)
(def make-auth-fns mcp-auth/make-auth-fns)
(def machine-token-cached? mcp-auth/machine-token-cached?)
(def server-entry mcp-auth/server-entry)
(def server-issuer mcp-auth/server-issuer)
(def store-server! mcp-auth/store-server!)
(def logout! mcp-auth/logout!)

;; ─── Token store (kmet.libs.mcp.auth) ─────────────────────────────────────
;; The :file / :keyring / :auto backends, their path/permission handling
;; and logout live in the lib (mcp.md Phase 2). The extension keeps what is
;; host policy — where the plaintext store file sits — plus the names
;; core.clj and the validation scripts call.

(defn store-path
  "The plaintext OAuth token store file (<agent-dir>/mcp-oauth.edn; the
   :file backend — keyring mode stores per-server secrets instead)."
  []
  (str (fs/path (config/agent-dir) "mcp-oauth.edn")))

(defn configure-storage!
  "Set the token-storage mode from the merged SETTINGS (:token-storage;
   default :auto) and the host store path. The MCP_TOKEN_STORAGE env var
   wins when set (testing / headless hosts). Call at init and after
   /mcp refresh."
  [settings]
  (let [env (System/getenv "MCP_TOKEN_STORAGE")
        mode (cond
               (and env (seq (str/trim env))) (keyword (str/trim env))
               (contains? (or settings {}) :token-storage) (:token-storage settings)
               :else :auto)]
    (mcp-auth/configure-storage! {:mode mode :path (store-path)})))

;; ─── Callback server (process-wide, OS-assigned port) ─────────────────────

(defonce ^:private callback-state (atom nil))
(defonce ^:private current-flow (atom nil))

(defn- redirect-uri-for
  "The loopback redirect URI for the callback server (config
   :redirect-uri wins; must be an http loopback URI with an explicit
   port, pi parseOAuthRedirectUri). Returns [uri bind-port] — the bind
   port (the configured URI's port, or 0 for OS-assigned) feeds
   ensure-callback-server! so the browser callback reaches the server."
  [cfg default-port default-path]
  (if-let [configured (:redirect-uri cfg)]
    (let [uri (try (java.net.URI. configured) (catch Exception _ nil))
          host (some-> uri .getHost str/lower-case)
          port (some-> uri .getPort)]
      (when-not (and uri (= "http" (.getScheme uri))
                     (contains? #{"localhost" "127.0.0.1" "::1"} host)
                     (pos? port))
        (throw (ex-info (str "MCP auth failed: :redirect-uri must be an http:// "
                             "loopback URI with an explicit port")
                        {:type :oauth-invalid-config})))
      [configured port])
    [(str "http://" (oauth-lib/callback-host) ":" default-port default-path)
     default-port]))

(defn- callback-path
  "The callback route a bind PATH serves: /callback when the configured
   URI carries none."
  [path]
  (or (not-empty path) "/callback"))

(defn callback-redirect-uri
  "The redirect URI a flow must advertise for the bound callback server
   BOUND ({:port :path}). The configured URI is returned when it names the
   bound server exactly — preserving the config's host spelling; otherwise
   the bound server's own URI is returned, because the first flow's
   :redirect-uri fixed the bind and a later config's port/path would send
   the browser to an address nothing listens on. A path-less configured
   URI also gets the bound URI: the config names no route to preserve."
  ([bound] (callback-redirect-uri bound nil nil nil))
  ([bound bind-port bind-path configured]
   (if (and (some? configured)
            (some? (not-empty bind-path))
            (= bind-port (:port bound))
            (= bind-path (:path bound)))
     configured
     (str "http://" (oauth-lib/callback-host) ":" (:port bound) (:path bound)))))

(defn- ensure-callback-server!
  "Start the process-wide loopback callback server once. PORT is the bind
   port: a configured :redirect-uri supplies its explicit port (the
   browser callback must reach the server); 0 → OS assigns. PATH is the
   callback path (default /callback; a configured :redirect-uri's path is
   served instead). Every later flow reuses the bound port, so a DCR'd
   client's redirect URI stays valid. Returns {:port n :path str}."
  [& [port path]]
  (or @callback-state
      (locking callback-state
        (or @callback-state
            (let [route (callback-path path)
                  server (oauth-lib/start-callback-server
                          (or port 0)
                          (fn [{:keys [path query-params]}]
                            (let [flow @current-flow]
                              (cond
                                (not= path route)
                                {:status 404
                                 :body (oauth-lib/oauth-error-html
                                        "Callback route not found.")}

                                (nil? flow)
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "No OAuth flow is in progress.")}

                                (not= (:state query-params) (:state flow))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html "State mismatch.")}

                                (and (nil? (:code query-params))
                                     (nil? (:error query-params)))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "Missing authorization code.")}

                                :else
                                ;; the whole response goes to the flow: the
                                ;; lib validates `iss` (RFC 9207) and any
                                ;; `error` before the code is exchanged. The
                                ;; page stays generic — an unvalidated error
                                ;; description must never be displayed
                                (do (deliver (:code-p flow) query-params)
                                    (if (:error query-params)
                                      {:status 400
                                       :body (oauth-lib/oauth-error-html
                                              "Authorization failed. Return to kmet for details.")}
                                      {:status 200
                                       :body (oauth-lib/oauth-success-html
                                              "MCP authentication completed. You can close this window.")}))))))]
              (reset! callback-state {:server server
                                      :port (:port server)
                                      :path route})
              @callback-state)))))

(defn- ensure-callback-redirect!
  "Ensure the process-wide callback server and return the redirect URI to
   use for this flow. The server binds ONCE: the first flow's
   :redirect-uri config (explicit port + path) wins; later flows reuse the
   bound server, so the authorize URL always matches the server the
   browser hits (a DCR'd client's registered URI stays valid) and a later
   config that asks for a different port/path cannot point the browser at
   a dead address."
  [cfg]
  (if-let [configured (:redirect-uri cfg)]
    (let [[_ bind-port] (redirect-uri-for cfg 0 "/callback")
          uri-obj (try (java.net.URI. configured) (catch Exception _ nil))
          bind-path (some-> uri-obj .getPath)]
      (callback-redirect-uri (ensure-callback-server! bind-port bind-path)
                             bind-port bind-path configured))
    (callback-redirect-uri (ensure-callback-server!))))

(defn shutdown!
  "Close the callback server and drop machine-token caches (extension
   unload). Idempotent."
  []
  (mcp-auth/clear-machine-tokens!)
  (mcp-auth/clear-challenges!)
  (when-let [{:keys [server]} @callback-state]
    (try ((:close server)) (catch Exception _ nil))
    (reset! callback-state nil)
    (reset! current-flow nil)))

;; ─── Full flow (/mcp auth, §7.8.4) ────────────────────────────────────────

(defn- run-pkce-flow
  "Host half of the PKCE loopback flow: bind the callback server, hand the
   authorize URL to the user, race the callback against the manual-paste
   prompt, then let the lib complete (RFC 9207 validation + code exchange +
   issuer-bound store)."
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        redirect-uri (ensure-callback-redirect! cfg)
        {:keys [url pending]} (mcp-auth/prepare-pkce-flow
                               name definition metadata redirect-uri)
        code-p (promise)]
    (reset! current-flow {:state (:state pending) :code-p code-p})
    (try
      ((:notify interaction)
       {:type :auth-url :url url
        :instructions "Open the URL in your browser to authorize MCP access."})
      (try ((:open-url interaction) url) (catch Exception _ nil))
      (let [result (oauth-lib/wait-for-callback-or-manual
                    interaction code-p
                    {:type :manual-code
                     :message (str "Complete login in your browser, or paste the "
                                   "authorization code / redirect URL here:")}
                    600000)
            response (case (:source result)
                       :callback (:value result)
                       :manual (let [parsed (or (oauth-lib/parse-authorization-input
                                                 (:value result))
                                                {})]
                                 ;; a bare pasted code carries no state; the
                                 ;; manual entry is an explicit user action
                                 ;; in this terminal, so bind the flow's
                                 ;; state (complete-pkce-flow! requires it)
                                 (cond-> parsed
                                   (and (seq (:code parsed))
                                        (nil? (:state parsed)))
                                   (assoc :state (get pending :state))))
                       :cancelled (throw (ex-info "Login cancelled"
                                                  {:type :login-cancelled}))
                       :timeout (throw (ex-info "OAuth login timed out"
                                                {:type :oauth-timeout}))
                       :error (throw (:error result)))]
        (mcp-auth/complete-pkce-flow! name pending response))
      (finally
        ;; pi: manualAbort.abort — dismiss the pending manual-paste dialog
        ;; and unblock its prompt when the callback won (no-op when the
        ;; user already dismissed it)
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        (reset! current-flow nil)))))

(defn- run-device-flow
  "Host half of the RFC 8628 device flow: hand the user code to the user,
   then let the lib poll and store the tokens."
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        ;; a pre-registered client needs no DCR, so no redirect URI (and
        ;; no callback server) for this flow either
        redirect-uri (when-not (:client-id cfg)
                       (ensure-callback-redirect! cfg))
        started (mcp-auth/begin-device-flow! name definition metadata redirect-uri)]
    ((:notify interaction)
     {:type :device-code
      :user-code (:user-code started)
      :verification-uri (:verification-uri started)
      :expires-in-seconds (:expires-in started)})
    (try ((:open-url interaction) (:verification-uri started))
         (catch Exception _ nil))
    (mcp-auth/complete-device-flow! name started (:signal interaction))))

(defn run-flow!
  "Run the auth flow for a server (§7.8) — fresh login, replaces stored
   tokens. Machine grants (client-credentials / jwt-bearer) just fetch +
   cache a token, validating the config. INTERACTION: {:signal
   cancel-atom :has-ui bool :notify (fn [event-map]) :prompt (fn
   [prompt-map] → string) :open-url (fn [url])}. Returns :logged-in;
   throws on failure/cancel."
  [name definition interaction]
  (if (mcp-auth/machine-grant? definition)
    (do (mcp-auth/fetch-machine-token! name definition) :logged-in)
    (let [metadata (mcp-auth/discover-meta name definition)
          flow (mcp-auth/resolve-flow (:oauth definition) metadata interaction)]
      ;; only the PKCE flow sends a code challenge — a device-only server
      ;; must not be refused for missing PKCE metadata
      (when (= :pkce flow)
        (mcp-auth/verify-pkce-support! name definition metadata))
      (case flow
        :pkce (run-pkce-flow name definition metadata interaction)
        :device (run-device-flow name definition metadata interaction)))))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn auth-status
  "Auth state for a server: nil (not configured) | :bearer | :logged-in |
   :expired | :none (oauth configured, no tokens) | :client-credentials |
   :jwt-bearer (machine grants — always available, tokens fetched on
   demand). A stored client registration without tokens counts as :none."
  [name definition]
  (when (and (:url definition) (:auth definition))
    (case (:auth definition)
      :bearer (if (seq (mcp-auth/bearer-token definition)) :bearer :none)
      :oauth (cond
               (mcp-auth/machine-grant? definition) (mcp-auth/grant-of definition)
               :else (let [entry (server-entry name)]
                       (cond
                         (nil? (get-in entry [:tokens :access])) :none
                         (mcp-auth/token-expired? entry) :expired
                         :else :logged-in)))
      nil)))

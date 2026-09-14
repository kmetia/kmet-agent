(ns extensions.mcp-adapter.auth
  "OAuth adapter for HTTP MCP servers (§7.8 of the design contract — pi:
   mcp-auth.ts / mcp-oauth-provider.ts / oauth-handler.ts /
   mcp-auth-flow.ts / mcp-callback-server.ts, adapted onto the generic
   machinery in kmet.libs.oauth).

   Thin adapter: server config → lib calls, token-store file wiring
   (<agent-dir>/mcp-oauth.edn, plaintext with 0600 perms — bb has no OS
   keyring; documented tradeoff; pi uses the keyring), browser open,
   status text. The extension cannot require kmet.ai.*, so the generic
   machinery lives in kmet.libs.oauth (RFC 8414 discovery, RFC 7591 DCR,
   PKCE loopback + RFC 8628 device flows, token exchange/refresh).

   Flow (per server, §7.8):
     1. token lookup — expired → refresh; missing/refresh-failed → flow
     2. discovery (RFC 8414; :authorization-server-url skips it)
     3. client registration (RFC 7591) unless :oauth {:client-id ...}
     4. authorization — PKCE loopback on an OS-assigned port (default), or
        the RFC 8628 device flow (forced via :flow :device, or auto when
        the metadata exposes a device endpoint and the host is headless)
     5. tokens stored; requests attach Authorization: Bearer; 401 with a
        stored refresh token → refresh once + retry once"
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [extensions.mcp-adapter.config :as config]
            [kmet.libs.oauth :as oauth-lib]))

(defn- read-text
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn- write-text
  [path text]
  (spit path (str text)))

;; ─── Token store ──────────────────────────────────────────────────────────
;; Two backends, selected by settings :token-storage (or the MCP_TOKEN_STORAGE
;; env override):
;;   :file    — <agent-dir>/mcp-oauth.edn, plaintext with 0600 perms
;;              (Phase 1 behavior; the only backend on Termux/Android and
;;              on hosts without a keyring tool)
;;   :keyring — the OS credential store via platform tools: macOS `security`
;;              (generic-password), Linux `secret-tool` (libsecret),
;;              Windows Credential Manager via a PowerShell P/Invoke
;;              (CredWrite/CredRead/CredDelete). Per-server secrets,
;;              service "kmet-mcp" / account "oauth:<server>", payload =
;;              pr-str of the entry map (compact — gnome-keyring's
;;              GKeyFile backend corrupts multiline secrets, pi parity).
;;   :auto    — default: keyring when a platform tool is available, else
;;              the plaintext file (the documented Phase-1 tradeoff holds
;;              exactly where the OS offers no keyring).

(def ^:private storage-mode (atom nil))

(defn configure-storage!
  "Set the token-storage mode from the merged SETTINGS (:token-storage;
   default :auto). The MCP_TOKEN_STORAGE env var wins when set (testing /
   headless hosts). Call at init and after /mcp refresh."
  [settings]
  (let [env (System/getenv "MCP_TOKEN_STORAGE")
        mode (cond
               (and env (seq (str/trim env))) (keyword (str/trim env))
               (contains? (or settings {}) :token-storage) (:token-storage settings)
               :else :auto)]
    (reset! storage-mode (if (contains? #{:auto :keyring :file} mode) mode :auto))))

(defn store-path
  "The plaintext OAuth token store file (<agent-dir>/mcp-oauth.edn; the
   :file backend — keyring mode stores per-server secrets instead)."
  []
  (str (fs/path (config/agent-dir) "mcp-oauth.edn")))

(defn- read-edn
  [path]
  (try
    (when (fs/exists? path)
      (let [raw (edn/read-string {:default (fn [_ _] nil)} (read-text path))]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn- write-file-0600
  "Atomic write (temp + rename) with 0600 perms (best-effort — Windows has
   no posix perms)."
  [path content]
  (let [tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (write-text tmp content)
    (try (fs/set-posix-file-permissions tmp "rw-------") (catch Exception _ nil))
    (fs/move tmp path {:replace-existing true})
    nil))

;; ─── :file backend ────────────────────────────────────────────────────────

(defn- read-file-store
  []
  (or (read-edn (store-path)) {:servers {}}))

(defn- write-file-store!
  [store]
  (write-file-0600 (store-path) (pr-str store)))

;; ─── :keyring backend ─────────────────────────────────────────────────────

(def ^:private keyring-service "kmet-mcp")

(defn- account-for
  [name]
  (str "oauth:" name))

(defn- keyring-tool
  "The platform keyring tool as an argv vector, or nil when unavailable:
   macOS security, Linux secret-tool, Windows PowerShell (Credential
   Manager P/Invoke). Termux has none."
  []
  (cond
    (System/getenv "TERMUX_VERSION") nil
    (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
    (if (fs/which "security") ["security"] nil)
    (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win")
    (cond
      (fs/which "powershell.exe") ["powershell.exe" "-NoProfile" "-NonInteractive" "-Command"]
      (fs/which "pwsh") ["pwsh" "-NoProfile" "-NonInteractive" "-Command"]
      :else nil)
    :else (if (fs/which "secret-tool") ["secret-tool"] nil)))

(defn keyring-available?
  "True when the current platform has a keyring tool (the :auto backend
   picks :keyring exactly then)."
  []
  (boolean (keyring-tool)))

(defn storage-kind
  "The effective storage backend (:file | :keyring) — for status text."
  []
  (let [mode (or @storage-mode :auto)]
    (if (and (= mode :keyring) (not (keyring-available?)))
      ;; configured keyring but no tool: report file with a warning marker
      ;; (callers degrade — reads/writes fall back below)
      :file
      (if (= mode :auto)
        (if (keyring-available?) :keyring :file)
        mode))))

(defn- run-tool
  "Run a keyring tool argv; STDIN is the payload when given. Returns
   {:ok true :out str} or {:ok false :error str}."
  [argv & [stdin]]
  (try
    (let [p (apply proc/process argv
                   {:in (if stdin :stream :discard)
                    :out :stream :err :stream})]
      (when stdin
        (io/copy stdin (:in p))
        (try (.close (:in p)) (catch Exception _ nil)))
      (let [r (deref p 15000 nil)]
        (if (nil? r)
          {:ok false :error "keyring tool timed out"}
          (let [out (or (some-> (:out r) slurp) "")
                err (or (some-> (:err r) slurp) "")]
            (if (zero? (:exit r))
              {:ok true :out out}
              {:ok false :error (str err " (exit " (:exit r) ")")})))))
    (catch Exception e
      {:ok false :error (ex-message e)})))

(defn- shell-quote
  "Single-quote for PowerShell argument passing (embedded quotes doubled)."
  [s]
  (str "'" (str/replace s "'" "''") "'"))

(defn- read-edn-from-string
  [s]
  (try
    (let [parsed (edn/read-string {:default (fn [_ _] nil)} s)]
      (when (map? parsed) parsed))
    (catch Exception _ nil)))

(def ^:private windows-cred-script-cache (atom nil))

(def ^:private windows-cred-body
  ;; PowerShell Credential-Manager P/Invoke (CredWrite/CredRead/
  ;; CredDelete). The script reads $op/$t/$p; missing read entries exit 0
  ;; with no output (normal — nothing stored yet). Windows-only; untested
  ;; on real Windows hosts (no way to run one here — recorded limitation).
  (str "$ErrorActionPreference='Stop'\n"
       "Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;using System.Text;"
       "public class KmetCred{"
       "[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]"
       "public struct CRED{public uint Flags;public uint Type;public IntPtr TargetName;"
       "public IntPtr Comment;public long LastWritten;public uint BlobSize;public IntPtr Blob;"
       "public uint Persist;public uint AttrCount;public IntPtr Attrs;public IntPtr Alias;"
       "public IntPtr UserName;}"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredRead(string t,uint ty,uint f,out IntPtr c);"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredWrite(ref CRED c,uint f);"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredDelete(string t,uint ty,uint f);"
       "[DllImport(\"advapi32.dll\")]public static extern void CredFree(IntPtr b);}'\n"
       "if($op -eq 'read'){"
       "$h=[IntPtr]::Zero;"
       "if([KmetCred]::CredRead($t,1,0,[ref]$h)){"
       "$c=[Runtime.InteropServices.Marshal]::PtrToStructure($h,[KmetCred+CRED]);"
       "$n=[int]$c.BlobSize;"
       "if($n -gt 0){$b=New-Object byte[] $n;[Runtime.InteropServices.Marshal]::Copy($c.Blob,$b,0,$n);"
       "[Console]::Out.WriteLine([Text.Encoding]::UTF8.GetString($b))}"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.TargetName);[KmetCred]::CredFree($h)}"
       "}elseif($op -eq 'write'){"
       "$c=New-Object KmetCred+CRED;$c.Type=1;$c.Persist=2;"
       "$c.TargetName=[Runtime.InteropServices.Marshal]::StringToCoTaskMemUni($t);"
       "$b=[Text.Encoding]::UTF8.GetBytes($p);$c.BlobSize=$b.Length;"
       "$c.Blob=[Runtime.InteropServices.Marshal]::AllocCoTaskMem($b.Length);"
       "[Runtime.InteropServices.Marshal]::Copy($b,0,$c.Blob,$b.Length);"
       "if(-not [KmetCred]::CredWrite([ref]$c,0)){throw 'CredWrite failed'}"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.TargetName);"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.Blob)"
       "}else{[KmetCred]::CredDelete($t,1,0)|Out-Null}"))

(defn- windows-cred-script
  "The full PowerShell -Command body for an operation (read/write/delete)
   on TARGET with optional PAYLOAD (variables inlined — PowerShell -Command
   does not pass $args reliably across versions). Cached base body."
  [op target & [payload]]
  (str "$op=" (shell-quote op) ";$t=" (shell-quote target)
       ";$p=" (if payload (shell-quote payload) "$null") ";"
       @windows-cred-script-cache))

;; initialize the cached script body at load (top-level form, after both
;; defs — sci evaluates in order)
(reset! windows-cred-script-cache windows-cred-body)

(defn- keyring-read
  "The stored entry for NAME from the OS keyring, or nil. macOS/Linux print
   the secret on stdout; Windows PowerShell prints the payload line."
  [name]
  (let [tool (keyring-tool)]
    (cond
      (nil? tool) nil
      (= "security" (first tool))
      (let [r (run-tool (conj tool "find-generic-password" "-a" (account-for name)
                              "-s" keyring-service "-w"))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret)))))

      (= "secret-tool" (first tool))
      (let [r (run-tool (conj tool "lookup" "service" keyring-service
                              "account" (account-for name)))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret)))))

      :else
      (let [r (run-tool (conj tool (windows-cred-script "read" (account-for name))))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret))))))))

(defn- keyring-write!
  "Store the ENTRY for NAME in the OS keyring. Returns true on success."
  [name entry]
  (let [tool (keyring-tool)
        payload (pr-str entry)]
    (cond
      (nil? tool) false
      (= "security" (first tool))
      (:ok (run-tool (conj tool "add-generic-password" "-U" "-a" (account-for name)
                           "-s" keyring-service "-w" payload)))

      (= "secret-tool" (first tool))
      (:ok (run-tool (conj tool "store" "--label=kmet-mcp" "service" keyring-service
                           "account" (account-for name))
                     payload))

      :else
      (:ok (run-tool (conj tool (windows-cred-script "write" (account-for name) payload)))))))

(defn- keyring-clear!
  "Delete the stored entry for NAME. Missing entries are not an error."
  [name]
  (let [tool (keyring-tool)]
    (when tool
      (cond
        (= "security" (first tool))
        (run-tool (conj tool "delete-generic-password" "-a" (account-for name)
                        "-s" keyring-service))

        (= "secret-tool" (first tool))
        (run-tool (conj tool "clear" "service" keyring-service
                        "account" (account-for name)))

        :else
        (run-tool (conj tool (windows-cred-script "delete" (account-for name)))))))
  nil)

;; ─── backend dispatch ─────────────────────────────────────────────────────

(defn- keyring-mode?
  []
  (= :keyring (storage-kind)))

(defn- server-entry
  "The stored {:tokens .. :client-info ..} entry for a server, or nil."
  [name]
  (if (keyring-mode?)
    (keyring-read name)
    (get-in (read-file-store) [:servers name])))

(defn- store-server!
  [name entry]
  (if (keyring-mode?)
    (keyring-write! name entry)
    (write-file-store! (assoc-in (read-file-store) [:servers name] entry))))

(defn- clear-server!
  [name]
  (if (keyring-mode?)
    (keyring-clear! name)
    (let [store (read-file-store)]
      (when (contains? (:servers store) name)
        (write-file-store! (update store :servers dissoc name))))))

(defn- token-expired?
  "True when the stored tokens are expired (60s skew, pi's 5-min window
   reduced for MCP's shorter-lived tokens)."
  [entry]
  (let [expires (get-in entry [:tokens :expires])]
    (and (number? expires)
         (<= expires (+ (System/currentTimeMillis) 60000)))))

;; in-memory cache for machine-grant tokens (client-credentials /
;; jwt-bearer — §7.8.6); defined before logout! below
(defonce ^:private machine-token-cache (atom {}))

(defn logout!
  "Clear the stored OAuth tokens + client info for a server (§10.6), and
   any cached machine-grant token."
  [name]
  (swap! machine-token-cache dissoc name)
  (clear-server! name))

;; ─── Discovery (RFC 8414) ─────────────────────────────────────────────────

(defn- origin-of
  "scheme://host of a URL."
  [url]
  (try
    (let [uri (java.net.URI. url)]
      (str (.getScheme uri) "://" (.getAuthority uri)))
    (catch Exception _ url)))

(defn- issuer-matches?
  "Lenient issuer check: the metadata issuer must share the server URL's
   origin (or be the full server URL) — catches gross mismatches while
   allowing path variations real servers use."
  [url issuer]
  (let [origin (origin-of url)
        base (str/replace url #"/+$" "")]
    (or (= issuer origin)
        (= issuer base)
        (= origin (origin-of issuer)))))

(defn- discover-meta
  "Authorization-server metadata for a server (§7.8.2): config
   :authorization-server-url fetches the metadata from that URL directly
   (skips well-known discovery); otherwise RFC 8414 discovery against the
   server URL. Throws when nothing answers. The issuer check (RFC 8414) is
   skipped when :skip-issuer-metadata-validation is set."
  [definition]
  (let [cfg (:oauth definition)
        url (:url definition)
        headers (:headers definition)
        metadata (cond
                   ;; an explicit token endpoint needs no discovery at all
                   (:token-endpoint cfg) {:token_endpoint (:token-endpoint cfg)}

                   (:authorization-server-url cfg)
                   (:body (oauth-lib/fetch-json (:authorization-server-url cfg)
                                                {:method :get
                                                 :headers headers
                                                 :timeout 5000}))

                   :else
                   (oauth-lib/discover-authorization-server url {:headers headers}))]
    (when-not (map? metadata)
      (throw (ex-info (str "MCP auth failed: no OAuth authorization server metadata "
                           "discovered at " url)
                      {:type :oauth-no-metadata})))
    (when-not (or (:token-endpoint cfg)
                  (true? (:skip-issuer-metadata-validation cfg)))
      (when (and (seq (:issuer metadata))
                 (not (issuer-matches? url (:issuer metadata))))
        (throw (ex-info (str "MCP auth failed: authorization server issuer mismatch ("
                             (:issuer metadata) " vs " url "). Set "
                             ":skip-issuer-metadata-validation true to override.")
                        {:type :oauth-issuer-mismatch}))))
    metadata))

(defn- required-endpoint
  "One metadata endpoint or a clear error."
  [metadata key name]
  (or (get metadata key)
      (throw (ex-info (str "MCP auth failed: authorization server metadata for " name
                           " has no " key)
                      {:type :oauth-no-endpoint :endpoint key}))))

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
            (let [callback-path (or path "/callback")
                  server (oauth-lib/start-callback-server
                          (or port 0)
                          (fn [{:keys [path query-params]}]
                            (let [flow @current-flow]
                              (cond
                                (not= path callback-path)
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

                                (nil? (:code query-params))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "Missing authorization code.")}

                                :else
                                (do (deliver (:code-p flow)
                                             {:code (:code query-params)})
                                    {:status 200
                                     :body (oauth-lib/oauth-success-html
                                            "MCP authentication completed. You can close this window.")})))))]
              (reset! callback-state {:server server
                                      :port (:port server)
                                      :path callback-path})
              @callback-state)))))

(defn- ensure-callback-redirect!
  "Ensure the process-wide callback server and return the redirect URI to
   use for this flow. The server binds ONCE: the first flow's
   :redirect-uri config (explicit port + path) wins; later flows reuse the
   bound server and derive the URI from it, so the authorize URL always
   matches the server the browser hits (a DCR'd client's registered URI
   stays valid)."
  [cfg]
  (if-let [configured (:redirect-uri cfg)]
    (let [[uri bind-port] (redirect-uri-for cfg 0 "/callback")
          uri-obj (try (java.net.URI. configured) (catch Exception _ nil))]
      (ensure-callback-server! bind-port (some-> uri-obj .getPath))
      uri)
    (let [{:keys [port path]} (ensure-callback-server!)]
      (str "http://" (oauth-lib/callback-host) ":" port path))))

(defn shutdown!
  "Close the callback server and drop machine-token caches (extension
   unload). Idempotent."
  []
  (reset! machine-token-cache {})
  (when-let [{:keys [server]} @callback-state]
    (try ((:close server)) (catch Exception _ nil))
    (reset! callback-state nil)
    (reset! current-flow nil)))

;; ─── Client info (config pre-registered or RFC 7591 DCR) ──────────────────

(defn- scopes-string
  "Config :scopes — a string or a vector, joined with spaces."
  [cfg]
  (let [scopes (:scopes cfg)]
    (cond
      (nil? scopes) nil
      (string? scopes) scopes
      (sequential? scopes) (str/join " " scopes)
      :else (str scopes))))

(defn- stored-client-id
  [name]
  (get-in (server-entry name) [:client-info :client-id]))

(defn- resolve-client-id!
  "The client id for a server: config :oauth {:client-id ...} wins; else
   the stored (DCR'd) client; else RFC 7591 dynamic registration against
   the metadata's registration_endpoint (registered once, persisted with
   the entry)."
  [name definition metadata]
  (let [cfg (:oauth definition)]
    (or (:client-id cfg)
        (stored-client-id name)
        (let [registration-endpoint (get metadata :registration_endpoint)]
          (when-not registration-endpoint
            (throw (ex-info (str "MCP auth failed: " name " has no OAuth client id and the "
                                 "authorization server does not support dynamic client "
                                 "registration. Set :oauth {:client-id ...} in mcp.edn.")
                            {:type :oauth-no-registration})))
          (let [redirect-uri (ensure-callback-redirect! cfg)
                client (oauth-lib/register-client
                        registration-endpoint
                        {:redirect-uris [redirect-uri]
                         :client-name "kmet"
                         :scope (scopes-string cfg)})]
            (store-server! name (assoc (or (server-entry name) {})
                                       :client-info {:client-id (:client_id client)
                                                     :redirect-uris [redirect-uri]}))
            (:client_id client))))))

;; ─── Token storage ────────────────────────────────────────────────────────

(defn- tokens->store
  "Normalize a lib token map {:access :refresh :expires-in :scope} into the
   store shape {:access .. :refresh .. :expires ms :scope ..}."
  [tokens]
  (cond-> {:access (:access tokens)
           :expires (+ (System/currentTimeMillis) (* 1000 (:expires-in tokens)))}
    (:refresh tokens) (assoc :refresh (:refresh tokens))
    (:scope tokens) (assoc :scope (:scope tokens))))

(defn- store-tokens!
  [name tokens]
  (store-server! name (assoc (or (server-entry name) {}) :tokens tokens)))

(defn- bearer-token
  "The static bearer token from config (:bearer-token or
   :bearer-token-env)."
  [definition]
  (or (:bearer-token definition)
      (when-let [env-name (:bearer-token-env definition)]
        (System/getenv env-name))))

(defn- auth-required-error
  [name]
  (ex-info (str "MCP auth failed: " name " is not authenticated. Run /mcp auth "
                name " to log in.")
           {:type :mcp-auth-required}))

(defn- oauth-bearer-header
  [tokens]
  {"Authorization" (str "Bearer " (:access tokens))})

;; ─── Machine grants (client-credentials / jwt-bearer, §7.8.6) ────────────
;; Non-interactive grants: a token is fetched on demand from the token
;; endpoint (discovery, :authorization-server-url, or an explicit
;; :token-endpoint), cached in memory with its expiry, and re-fetched on
;; expiry or 401 — the re-fetch IS the refresh (no refresh token is
;; expected). Nothing is persisted: the token store stays for the
;; interactive grants.

(defn- grant-of
  "The configured grant: :authorization-code (default) |
   :client-credentials | :jwt-bearer."
  [definition]
  (or (get-in definition [:oauth :grant]) :authorization-code))

(defn- machine-grant?
  [definition]
  (contains? #{:client-credentials :jwt-bearer} (grant-of definition)))

(defn- fetch-machine-token!
  "Fetch a fresh token for a machine-grant server and cache it. Throws
   MCP auth failed on any error (§7.7)."
  [name definition]
  (let [cfg (:oauth definition)
        token-endpoint (required-endpoint (discover-meta definition)
                                          :token_endpoint name)
        tokens (case (grant-of definition)
                 :client-credentials
                 (oauth-lib/client-credentials-token
                  token-endpoint
                  {:client-id (:client-id cfg)
                   :client-secret (:client-secret cfg)
                   :token-endpoint-auth-method (:token-endpoint-auth-method cfg)
                   :scope (scopes-string cfg)})

                 :jwt-bearer
                 (let [key-file (:private-key-file cfg)
                       jwk (:private-key-jwk cfg)]
                   (when-not (or key-file jwk)
                     (throw (ex-info (str "MCP auth failed: " name " jwt-bearer grant "
                                          "requires :oauth {:private-key-file ...} or "
                                          ":private-key-jwk ...")
                                     {:type :oauth-invalid-config})))
                   (when (and key-file (not (fs/exists? key-file)))
                     (throw (ex-info (str "MCP auth failed: private key file not found: "
                                          key-file)
                                     {:type :oauth-invalid-config})))
                   (oauth-lib/jwt-bearer-token
                    token-endpoint
                    {:private-key (if (and key-file (string? key-file))
                                    (read-text key-file)
                                    jwk)
                     :algorithm (:algorithm cfg)
                     :issuer (:issuer cfg)
                     :subject (:subject cfg)
                     :audience (:audience cfg)
                     :client-id (:client-id cfg)
                     :scope (scopes-string cfg)})))
        stored (tokens->store tokens)]
    (swap! machine-token-cache assoc name stored)
    stored))

(defn- machine-token-header
  "Authorization header for a machine-grant server: the cached token, or
   a fresh fetch when missing/expired. FORCE skips the cache — the 401
   retry path must not resend a rejected token."
  [name definition force]
  (let [entry (get @machine-token-cache name)
        expired? (and entry (<= (:expires entry)
                                (+ (System/currentTimeMillis) 60000)))]
    (if (and entry (not force) (not expired?))
      (oauth-bearer-header entry)
      (oauth-bearer-header (fetch-machine-token! name definition)))))

;; ─── Request auth (§7.8.5) ────────────────────────────────────────────────

(declare refresh-tokens!)

(defn- oauth-header
  "Authorization header from the stored tokens; refreshes silently when
   expired, throws when not authenticated (§7.8.5 pre-emptive refresh)."
  [name definition]
  (let [entry (server-entry name)]
    (cond
      (nil? entry) (throw (auth-required-error name))
      (token-expired? entry)
      (if-let [refreshed (refresh-tokens! name definition)]
        (oauth-bearer-header refreshed)
        (throw (auth-required-error name)))
      :else (oauth-bearer-header (:tokens entry)))))

(defn- refresh-tokens!
  "Refresh the stored tokens; returns the fresh tokens map or nil when no
   refresh token is stored / the refresh failed (error recorded in the
   store state only on success)."
  [name definition]
  (let [entry (server-entry name)
        refresh (get-in entry [:tokens :refresh])]
    (when (and (seq refresh)
               (seq (:url definition)))
      (try
        (let [metadata (discover-meta definition)
              token-endpoint (required-endpoint metadata :token_endpoint name)
              client-id (or (get-in definition [:oauth :client-id])
                            (stored-client-id name))
              tokens (when client-id
                       (oauth-lib/refresh-access-token
                        token-endpoint
                        {:client-id client-id
                         :refresh-token refresh
                         :scope (get-in entry [:tokens :scope])}))]
          (when tokens
            (store-tokens! name (tokens->store tokens))
            (tokens->store tokens)))
        (catch Exception _ nil)))))

(defn- oauth-header-after-401
  "401 retry path: refresh the stored tokens (a stored refresh token is
   required — the 401 already proved the access token invalid), then
   return fresh headers. Throws the auth-required message otherwise."
  [name definition]
  (if-let [refreshed (refresh-tokens! name definition)]
    (oauth-bearer-header refreshed)
    (throw (auth-required-error name))))

(defn make-auth-fns
  "Auth wiring for a server's HTTP conn (§7.8.5): :auth-headers — called
   per request (pre-emptive refresh on expiry); :on-401 — refresh + fresh
   headers, retried once. Static config :headers are merged in for every
   HTTP server. Returns {} when the server has no auth configured."
  [name definition]
  (let [config-headers (:headers definition)
        merge-headers (fn [auth]
                        (if (seq config-headers)
                          (merge config-headers auth)
                          auth))]
    (cond
      (= :oauth (:auth definition))
      (if (machine-grant? definition)
        {:auth-headers (fn [] (merge-headers (machine-token-header name definition false)))
         :on-401 (fn [] (merge-headers (machine-token-header name definition true)))}
        {:auth-headers (fn [] (merge-headers (oauth-header name definition)))
         :on-401 (fn [] (merge-headers (oauth-header-after-401 name definition)))})

      (= :bearer (:auth definition))
      {:auth-headers (fn [] (merge-headers (oauth-bearer-header
                                            {:access (or (bearer-token definition) "")})))}

      :else
      (when (seq config-headers)
        {:auth-headers (fn [] config-headers)}))))

;; ─── Full flow (/mcp auth, §7.8.4) ────────────────────────────────────────

(defn- resolve-flow
  "Flow selection: config :flow (:pkce | :device | :auto default). :auto →
   PKCE loopback; the device flow is auto-selected when the metadata
   exposes a device endpoint and the host is headless (no UI to paste a
   redirect URL)."
  [cfg metadata interaction]
  (let [forced (:flow cfg)
        device-endpoint? (contains? metadata :device_authorization_endpoint)]
    (case forced
      :pkce :pkce
      :device :device
      :auto (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      nil (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      (throw (ex-info (str "MCP auth failed: unknown OAuth flow " forced
                           " (expected :auto, :pkce or :device)")
                      {:type :oauth-invalid-config})))))

(defn- authorize-url
  "The authorization endpoint URL with the PKCE challenge, state, scope
   and redirect URI."
  [authorize-endpoint client-id redirect-uri challenge state scope]
  (str authorize-endpoint
       "?response_type=code"
       "&client_id=" (oauth-lib/url-encode client-id)
       "&redirect_uri=" (oauth-lib/url-encode redirect-uri)
       "&code_challenge=" (oauth-lib/url-encode challenge)
       "&code_challenge_method=S256"
       "&state=" (oauth-lib/url-encode state)
       (when (seq scope)
         (str "&scope=" (oauth-lib/url-encode scope)))))

(defn- run-pkce-flow
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        {:keys [verifier challenge]} (oauth-lib/generate-pkce)
        state (oauth-lib/random-hex 16)
        redirect-uri (ensure-callback-redirect! cfg)
        client-id (resolve-client-id! name definition metadata)
        code-p (promise)
        authorize-endpoint (required-endpoint metadata :authorization_endpoint name)]
    (reset! current-flow {:state state :code-p code-p})
    (try
      (let [url (authorize-url authorize-endpoint client-id redirect-uri
                               challenge state (scopes-string cfg))]
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
              code (case (:source result)
                     :callback (:code (:value result))
                     :manual (let [parsed (oauth-lib/parse-authorization-input
                                           (:value result))]
                               (when (and (:state parsed)
                                          (not= (:state parsed) state))
                                 (throw (ex-info "OAuth state mismatch"
                                                 {:type :oauth-state-mismatch})))
                               (:code parsed))
                     :cancelled (throw (ex-info "Login cancelled"
                                                {:type :login-cancelled}))
                     :timeout (throw (ex-info "OAuth login timed out"
                                              {:type :oauth-timeout}))
                     :error (throw (:error result)))]
          (when-not code
            (throw (ex-info "Missing authorization code"
                            {:type :oauth-missing-code})))
          (let [tokens (oauth-lib/exchange-authorization-code
                        (required-endpoint metadata :token_endpoint name)
                        {:client-id client-id
                         :code code
                         :code-verifier verifier
                         :redirect-uri redirect-uri
                         :scope (scopes-string cfg)})]
            (store-tokens! name (tokens->store tokens))
            (store-server! name (assoc (server-entry name)
                                       :client-info {:client-id client-id
                                                     :redirect-uris [redirect-uri]}))
            :logged-in)))
      (finally
        ;; pi: manualAbort.abort — dismiss the pending manual-paste dialog
        ;; and unblock its prompt when the callback won (no-op when the
        ;; user already dismissed it)
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        (reset! current-flow nil)))))

(defn- run-device-flow
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        client-id (resolve-client-id! name definition metadata)
        device (oauth-lib/start-device-authorization
                (required-endpoint metadata :device_authorization_endpoint name)
                {:client-id client-id :scope (scopes-string cfg)})
        token-endpoint (required-endpoint metadata :token_endpoint name)]
    ((:notify interaction)
     {:type :device-code
      :user-code (:user-code device)
      :verification-uri (:verification-uri device)
      :expires-in-seconds (:expires-in device)})
    (try ((:open-url interaction) (:verification-uri device))
         (catch Exception _ nil))
    (let [tokens (oauth-lib/poll-oauth-device-code-flow
                  {:interval-seconds (:interval device)
                   :expires-in-seconds (:expires-in device)
                   :wait-before-first-poll true
                   :signal (:signal interaction)
                   :poll
                   (fn []
                     (let [raw (:body (oauth-lib/fetch-json
                                       token-endpoint
                                       {:method :post
                                        :headers {"Content-Type"
                                                  "application/x-www-form-urlencoded"
                                                  "Accept" "application/json"}
                                        :body (str "grant_type=urn:ietf:params:oauth:"
                                                   "grant-type:device_code"
                                                   "&device_code="
                                                   (oauth-lib/url-encode (:device-code device))
                                                   "&client_id="
                                                   (oauth-lib/url-encode client-id))
                                        :timeout 15000}))]
                       (cond
                         (string? (:access_token raw))
                         {:status :complete :value raw}

                         (string? (:error raw))
                         (case (:error raw)
                           "authorization_pending" {:status :pending}
                           "slow_down" {:status :slow_down
                                        :interval-seconds (:interval raw)}
                           {:status :failed
                            :message (str "Device flow failed: " (:error raw)
                                          (when (:error_description raw)
                                            (str ": " (:error_description raw))))})

                         :else
                         {:status :failed
                          :message "Invalid device token response"})))})]
      (store-tokens! name (tokens->store {:access (:access_token tokens)
                                          :expires-in (:expires_in tokens)
                                          :refresh (:refresh_token tokens)
                                          :scope (:scope tokens)}))
      (store-server! name (assoc (server-entry name)
                                 :client-info {:client-id client-id}))
      :logged-in)))

(defn run-flow!
  "Run the auth flow for a server (§7.8) — fresh login, replaces stored
   tokens. Machine grants (client-credentials / jwt-bearer) just fetch +
   cache a token, validating the config. INTERACTION: {:signal
   cancel-atom :has-ui bool :notify (fn [event-map]) :prompt (fn
   [prompt-map] → string) :open-url (fn [url])}. Returns :logged-in;
   throws on failure/cancel."
  [name definition interaction]
  (if (machine-grant? definition)
    (do (fetch-machine-token! name definition) :logged-in)
    (let [metadata (discover-meta definition)
          flow (resolve-flow (:oauth definition) metadata interaction)]
      (case flow
        :pkce (run-pkce-flow name definition metadata interaction)
        :device (run-device-flow name definition metadata interaction)))))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn auth-status
  "Auth state for a server: nil (not configured) | :bearer | :logged-in |
   :expired | :none (oauth configured, no tokens) | :client-credentials |
   :jwt-bearer (machine grants — always available, tokens fetched on
   demand)."
  [name definition]
  (when (and (:url definition) (:auth definition))
    (case (:auth definition)
      :bearer (if (seq (bearer-token definition)) :bearer :none)
      :oauth (cond
               (machine-grant? definition) (grant-of definition)
               (nil? (server-entry name)) :none
               (token-expired? (server-entry name)) :expired
               :else :logged-in)
      nil)))

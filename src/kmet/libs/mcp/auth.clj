(ns kmet.libs.mcp.auth
  "MCP authorization policy (§7.8) for HTTP servers.

   Phase 2 home for what the extension's auth namespace used to hold: the
   generic mechanics (RFC 8414 discovery, RFC 7591 DCR, PKCE loopback and
   RFC 8628 device flows, token exchange/refresh) live in kmet.libs.oauth;
   this namespace owns the MCP-specific policy built on them — RFC 8707
   resource canonicalization, the RFC 9728 `WWW-Authenticate` challenge
   record, scope/resource selection, the credential store (:file and
   OS-keyring backends), RFC 9728/8414 discovery, the token lifecycle
   (bearer and machine grants, refresh) and the request-auth header
   provider. The 2026 hardening is here: the issuer-keyed credential
   store with a read-side migration from the per-server shape (SEP-2352),
   the RFC 9207 authorization-response `iss` validation, and the SEP-837
   `application_type` on dynamic client registration.

   Hosts: the namespace is loaded by the extension (loader [:jolt :sci]),
   so it stays plain maps and functions — no protocols or records — and
   uses the same host surface as the other libs (System/getenv and
   System/getProperty, java.net.URI, babashka.fs, babashka.process)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.oauth :as oauth]))

;; ─── RFC 8707 resource indicator + RFC 9728 scope challenge ───────────────

(defn canonical-resource-uri
  "The RFC 8707 §2 canonical URI of the MCP server a token is for: the
   server url lowercased in scheme and host, without query or fragment,
   and without a trailing slash unless the path is only \"/\". Sent as
   the `resource` parameter of authorization and token requests — the
   authorization spec makes it mandatory in both, whatever the
   authorization server supports."
  [url]
  (when (string? url)
    (when-let [[_ scheme authority path] (re-find #"^(?i)(https?)://([^/?#]+)([^?#]*)" url)]
      (let [p (or path "")]
        (str (str/lower-case scheme) "://"
             (str/lower-case authority)
             (if (or (str/blank? p) (= p "/")) "" (str/replace p #"/+$" "")))))))

(defn parse-www-authenticate
  "The auth-param map of a `WWW-Authenticate: Bearer ...` challenge:
   {:resource-metadata \"...\" :scope \"a b\" :error \"insufficient_scope\"}.
   RFC 9728 §5.1 resource_metadata points at the protected-resource
   document; the scope parameter is the server's authoritative minimum
   for this request. nil when the header is absent or not a Bearer
   challenge."
  [header]
  (when (and (string? header) (str/starts-with? (str/trim header) "Bearer"))
    (let [params (str/split (subs (str/trim header) 6) #",")]
      (not-empty
       (into {}
             (keep (fn [param]
                     (when-let [[_ k v] (re-matches #"\s*([A-Za-z_-]+)\s*=\s*\"([^\"]*)\"\s*"
                                                    param)]
                       ;; resource_metadata → :resource-metadata, so both
                       ;; the RFC 9728 spelling and a hyphenated one land
                       ;; on the same key
                       [(keyword (str/replace (str/lower-case k) "_" "-")) v])))
             params)))))

;; the last WWW-Authenticate challenge seen per server (the transport
;; records it on every 401), so discovery and scope selection can use
;; what the server actually asked for
(defonce ^:private challenges (atom {}))

(defn challenge
  "The last recorded challenge params for a server, or nil."
  [name]
  (get @challenges name))

(defn record-challenge!
  "Remember a server's latest Bearer challenge. A non-Bearer or absent
   header leaves a previously recorded challenge in place."
  [name header]
  (when-let [params (parse-www-authenticate header)]
    (swap! challenges assoc name params))
  nil)

(defn clear-challenges!
  "Forget the recorded challenge for a server; with no argument, forget
   every recorded challenge (extension unload)."
  ([]
   (reset! challenges {})
   nil)
  ([name]
   (swap! challenges dissoc name)
   nil))

(defn scopes-string
  "Config :scopes — a string or a vector, joined with spaces."
  [cfg]
  (let [scopes (:scopes cfg)]
    (cond
      (nil? scopes) nil
      (string? scopes) scopes
      (sequential? scopes) (str/join " " scopes)
      :else (str scopes))))

(defn effective-scopes
  "The scopes to request for a server: config :scopes wins; else the
   scope the server challenged with (authoritative for the current
   request); else `scopes_supported` from the protected-resource
   metadata (spec, scope selection strategy)."
  [name definition prm]
  (or (scopes-string (:oauth definition))
      (:scope (challenge name))
      (let [supported (:scopes_supported prm)]
        (when (seq supported)
          (str/join " " supported)))))

(defn effective-resource
  "The RFC 8707 resource indicator for a server: its url, canonicalized.
   Config :resource overrides (a server fronted by a gateway)."
  [definition]
  (or (get-in definition [:oauth :resource])
      (canonical-resource-uri (:url definition))))

;; ─── Credential store ─────────────────────────────────────────────────────
;; Where a server's tokens and DCR client info live across runs. Two
;; backends, selected by configure-storage!; :auto picks the best available:
;;
;;   :file    — a plaintext EDN file with 0600 perms and an atomic write, at
;;              the path the host configures (the extension passes
;;              <agent-dir>/mcp-oauth.edn). The only backend on
;;              Termux/Android and hosts without a keyring tool. Issuer-keyed
;;              (SEP-2352):
;;
;;                {:version 2
;;                 :servers {"name" {:issuer "https://as.example"}}
;;                 :issuers {"https://as.example"
;;                           {:client-info {:client-id .. :redirect-uris [..]}
;;                            :tokens {"name" {:access .. :expires ..}}}}}
;;
;;              A registration is shared by every server resolving to the same
;;              authorization server; tokens stay per server (they are
;;              resource-bound). A pre-SEP-2352 entry has no :issuer: it is
;;              read as-is and bound to the first issuer resolved after the
;;              upgrade (back-stamp) — its registration is claimed only when
;;              the issuer has none, so a later legacy entry joins the
;;              registration already in use instead of replacing it.
;;   :keyring — the OS credential store via platform tools: macOS `security`
;;              (generic-password), Linux `secret-tool` (libsecret), Windows
;;              Credential Manager via a PowerShell P/Invoke
;;              (CredWrite/CredRead/CredDelete). Per-server secrets, service
;;              "kmet-mcp" / account "oauth:<server>", payload = pr-str of
;;              the entry map (compact — gnome-keyring's GKeyFile backend
;;              corrupts multiline secrets, pi parity).
;;   :auto    — keyring when a platform tool is available, else the
;;              plaintext file.

(defonce ^:private storage-config
  ;; {:mode :auto | :file | :keyring, :path <plaintext store file>} — reset
  ;; by configure-storage!, read by every store operation
  (atom {:mode :auto :path nil}))

(defn configure-storage!
  "Configure the credential store for this host. OPTS:
     :mode — :auto (default) | :file | :keyring; anything else falls back
             to :auto.
     :path — the plaintext store file for the :file backend. The library
             has no default path: the host owns the location (the
             extension passes <agent-dir>/mcp-oauth.edn).
   Call at init and whenever the host config changes."
  [{:keys [mode path]}]
  (reset! storage-config
          {:mode (if (contains? #{:auto :keyring :file} mode) mode :auto)
           :path path})
  nil)

(defn- store-path
  "The configured plaintext store file for the :file backend, or nil."
  []
  (:path @storage-config))

(defn- read-text
  [path]
  (when (and path (fs/exists? path))
    (slurp path)))

(defn- write-text
  [path text]
  (spit path (str text)))

(defn- read-edn
  [path]
  (try
    (when-let [text (read-text path)]
      (let [raw (edn/read-string {:default (fn [_ _] nil)} text)]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn- write-file-0600
  "Atomic write (temp + rename) with 0600 perms (best-effort — Windows has
   no posix perms)."
  [path content]
  (let [tmp (str path ".tmp")]
    ;; a bare filename has no parent directory to create
    (when-let [parent (fs/parent path)]
      (fs/create-dirs parent))
    (write-text tmp content)
    (try (fs/set-posix-file-permissions tmp "rw-------") (catch Exception _ nil))
    (fs/move tmp path {:replace-existing true})
    nil))

;; ─── :file backend ────────────────────────────────────────────────────────

(def ^:private store-lock
  ;; serializes the file store's read-modify-write — two concurrent logins
  ;; would otherwise lose one of the entries; reads hold it too so a
  ;; replace-during-read (Windows) cannot surface as an empty store.
  ;; Reentrant: store-server!/clear-server! hold it across read + write.
  (Object.))

(defn- read-file-store
  "The credential store, migrated to the v2 (issuer-keyed) shape on read.
   A pre-SEP-2352 file keeps its per-server entries as-is — they are
   unstamped and get bound to the first issuer resolved for them — with
   only the :version marker and a :servers map added."
  []
  (locking store-lock
    (let [raw (or (read-edn (store-path)) {})]
      (cond-> (assoc raw :servers (or (:servers raw) {}))
        (not= 2 (:version raw)) (assoc :version 2)))))

(defn- write-file-store!
  [store]
  (locking store-lock
    (let [path (store-path)]
      (when (str/blank? (str path))
        (throw (ex-info "MCP credential store: no :file path configured"
                        {:type :mcp-store-unconfigured})))
      (write-file-0600 path (pr-str store)))))

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
  (let [mode (:mode @storage-config)]
    (if (and (= mode :keyring) (not (keyring-available?)))
      ;; configured keyring but no tool: report file (reads/writes fall
      ;; back below)
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
          (do
            ;; never leave the tool running behind a timeout
            (try (proc/destroy-tree p) (catch Exception _ nil))
            {:ok false :error "keyring tool timed out"})
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

;; ─── issuer-aware access (SEP-2352) ──────────────────────────────────────

(defn- binding-issuer
  "The authorization-server issuer a server's stored credentials are bound
   to, or nil when none is recorded."
  [store name]
  (get-in store [:servers name :issuer]))

(defn- drop-empty-issuer
  "Drop an issuer entry that has neither client information nor tokens
   left."
  [store issuer]
  (if (and issuer
           (empty? (get-in store [:issuers issuer :tokens]))
           (nil? (get-in store [:issuers issuer :client-info])))
    (update store :issuers dissoc issuer)
    store))

(defn- claim-unstamped
  "Move a server's unstamped pre-upgrade credentials under ISSUER: its
   tokens always, and its client registration only when ISSUER has none
   yet (an authorization server keeps one shared registration, so a later
   legacy entry must not replace the one already in use). The caller
   writes its new values on top, so they win over the claimed ones. The
   unstamped entry is bound to the first issuer resolved after the
   upgrade — the SEP-2352 back-stamp."
  [store name issuer]
  (let [raw (get-in store [:servers name])]
    (if (and (map? raw) (or (:client-info raw) (:tokens raw)))
      (cond-> (update store :servers dissoc name)
        (and (:client-info raw)
             (nil? (get-in store [:issuers issuer :client-info])))
        (assoc-in [:issuers issuer :client-info] (:client-info raw))

        (:tokens raw)
        (assoc-in [:issuers issuer :tokens name] (:tokens raw)))
      store)))

(defn- rebind-issuer
  "Point NAME at ISSUER, dropping tokens recorded for NAME under a
   different issuer: credentials from one authorization server are never
   reused at another (SEP-2352)."
  [store name issuer]
  (let [old (binding-issuer store name)]
    (cond-> store
      (and old (not= old issuer))
      (-> (update-in [:issuers old :tokens] dissoc name)
          (drop-empty-issuer old))

      true
      (assoc-in [:servers name] {:issuer issuer}))))

(defn- store-file-bound!
  [name issuer client-info tokens]
  (locking store-lock
    (write-file-store!
     (cond-> (-> (read-file-store)
                 (claim-unstamped name issuer)
                 (rebind-issuer name issuer))
       (some? client-info) (assoc-in [:issuers issuer :client-info] client-info)
       (some? tokens) (assoc-in [:issuers issuer :tokens name] tokens)))))

(defn- store-file-unbound!
  [name entry]
  (locking store-lock
    (write-file-store! (assoc-in (read-file-store) [:servers name] (dissoc entry :issuer)))))

(defn server-entry
  "The stored credential view for a server — {:issuer .. :client-info ..
   :tokens ..} (omitting what is not stored) — or nil when it has no
   stored credentials. A credential is keyed by the authorization server
   that issued it (SEP-2352); an unstamped pre-upgrade entry reads back
   as-is until the first authenticated use binds it."
  [name]
  (if (keyring-mode?)
    (let [raw (keyring-read name)]
      (when (and (map? raw) (or (:client-info raw) (:tokens raw)))
        raw))
    (let [store (read-file-store)
          issuer (binding-issuer store name)]
      (if issuer
        (let [ie (get-in store [:issuers issuer])
              client-info (:client-info ie)
              tokens (get-in ie [:tokens name])]
          (when (or client-info tokens)
            (cond-> {:issuer issuer}
              client-info (assoc :client-info client-info)
              tokens (assoc :tokens tokens))))
        (let [raw (get-in store [:servers name])]
          (when (and (map? raw) (or (:client-info raw) (:tokens raw)))
            (dissoc raw :issuer)))))))

(defn server-issuer
  "The authorization-server issuer identifier a server's stored
   credentials are bound to (SEP-2352), or nil when nothing is stored (or
   only an unstamped pre-upgrade entry)."
  [name]
  (if (keyring-mode?)
    (:issuer (keyring-read name))
    (binding-issuer (read-file-store) name)))

(defn store-server!
  "Persist a server's {:tokens .. :client-info ..} entry. An entry that
   carries :issuer (what server-entry returns for a bound server) writes
   into the issuer-keyed store and binds the server to that authorization
   server; one without is kept as an unstamped pre-SEP-2352 entry until
   the first authenticated use binds it."
  [name entry]
  (if (keyring-mode?)
    (let [issuer (:issuer entry)
          old (keyring-read name)
          base (if (or (nil? issuer)
                       (= issuer (:issuer old))
                       (nil? (:issuer old)))
                 old
                 {})]
      (keyring-write! name (merge base entry)))
    (if-let [issuer (or (:issuer entry)
                        (binding-issuer (read-file-store) name))]
      (store-file-bound! name issuer (:client-info entry) (:tokens entry))
      (store-file-unbound! name entry))))

(defn store-client-info!
  "Persist a server's OAuth client registration under the authorization
   server ISSUER and bind the server to it (SEP-2352)."
  [name issuer client-info]
  (if (keyring-mode?)
    (store-server! name {:issuer issuer :client-info client-info})
    (store-file-bound! name issuer client-info nil)))

(defn- clear-server!
  "Forget a server's stored credentials: its tokens and binding, and —
   when no other server is bound to the same authorization server — the
   shared client registration (both backends). A server with no stored
   entry is a no-op (and never initializes an unconfigured store)."
  [name]
  (if (keyring-mode?)
    (keyring-clear! name)
    (locking store-lock
      (let [store (read-file-store)]
        (when (contains? (:servers store) name)
          (let [issuer (binding-issuer store name)
                store (update store :servers dissoc name)
                store (if issuer
                        (update-in store [:issuers issuer :tokens] dissoc name)
                        store)
                store (if (and issuer
                               (not-any? (fn [[_ binding]] (= issuer (:issuer binding)))
                                         (:servers store)))
                        (update store :issuers dissoc issuer)
                        store)]
            (write-file-store! store)))))))

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

(defn- server-headers
  "Static config :headers for a discovery URL, but only when it shares
   the resource server's origin: the headers may carry an API key or
   bearer meant for the MCP server itself and must not be forwarded to
   an authorization server or metadata host the server names (RFC 9728
   discovery crosses origins by design)."
  [definition url]
  (when (and (string? url)
             (= (origin-of (:url definition)) (origin-of url)))
    (:headers definition)))

(defn- protected-resource-meta
  "RFC 9728 protected-resource metadata for a server: the
   `resource_metadata` URL from a WWW-Authenticate challenge we have
   seen for this server, else the well-known probes. nil when the
   server publishes none (older deployments — discovery then falls back
   to probing the server URL for authorization-server metadata)."
  [name definition]
  (let [url (:url definition)
        challenge (:resource-metadata (challenge name))]
    (when (string? url)
      ;; the lib returns the metadata document itself (nil when nothing
      ;; answers — an older server that publishes none). The challenge
      ;; URL may be cross-origin, in which case no config headers go out
      ;; at all (the well-known probes are best-effort without them).
      (oauth/protected-resource-metadata
       url {:headers (server-headers definition (or challenge url))
            :resource-metadata-url challenge}))))

(defn- discover-document
  "The authorization-server metadata document and the URL it came from,
   as [metadata document-url]. An explicit config :token-endpoint or
   :authorization-server-url short-circuits discovery; otherwise the
   RFC 9728 protected-resource document names the authorization
   server(s) and RFC 8414 / OIDC discovery runs against the first that
   answers, falling back to the MCP server URL when the server
   publishes no resource document. document-url is the RFC 8414 §3.3
   issuer-check reference."
  [definition prm]
  (let [cfg (:oauth definition)
        url (:url definition)
        headers (:headers definition)
        issuers (let [as (:authorization_servers prm)]
                  (cond
                    (sequential? as) as
                    (string? as) [as]
                    :else nil))]
    (cond
      ;; an explicit token endpoint needs no discovery at all
      (:token-endpoint cfg) [{:token_endpoint (:token-endpoint cfg)} url]

      ;; an explicit, user-chosen URL keeps the static headers
      ;; (pre-existing behavior)
      (:authorization-server-url cfg)
      [(:body (oauth/fetch-json (:authorization-server-url cfg)
                                {:method :get
                                 :headers headers
                                 :timeout 5000}))
       (:authorization-server-url cfg)]

      :else
      (or (some (fn [issuer]
                  (when-let [m (oauth/discover-authorization-server
                                issuer {:headers (server-headers definition issuer)})]
                    [m issuer]))
                issuers)
          (when-let [m (oauth/discover-authorization-server
                        url {:headers headers})]
            [m url])))))

(defn discover-meta
  "Authorization-server metadata for a server (§7.8.2), via
   discover-document. Throws when nothing answers. The issuer check
   (RFC 8414 §3.3) compares the issuer to the URL the document was
   fetched from — under RFC 9728 that is the authorization server, not
   the resource server — and is skipped when
   :skip-issuer-metadata-validation is set. The map carries the
   protected-resource document (for scope selection) under ::prm — nil
   when none was fetched — and the effective issuer identifier under
   ::issuer (the metadata `issuer`, else the document URL)."
  [name definition]
  (let [cfg (:oauth definition)
        url (:url definition)
        ;; explicit configs skip discovery entirely: probing the
        ;; protected-resource metadata for scopes would add up to two
        ;; 5s-timeout requests to every token fetch
        prm (when-not (or (:token-endpoint cfg) (:authorization-server-url cfg))
              (protected-resource-meta name definition))
        [metadata discovered-from] (discover-document definition prm)]
    (when-not (map? metadata)
      (throw (ex-info (str "MCP auth failed: no OAuth authorization server metadata "
                           "discovered at " url)
                      {:type :oauth-no-metadata})))
    (when-not (or (:token-endpoint cfg)
                  (true? (:skip-issuer-metadata-validation cfg)))
      (when (and (seq (:issuer metadata))
                 (not (issuer-matches? discovered-from (:issuer metadata))))
        (throw (ex-info (str "MCP auth failed: authorization server issuer mismatch ("
                             (:issuer metadata) " vs " discovered-from "). Set "
                             ":skip-issuer-metadata-validation true to override.")
                        {:type :oauth-issuer-mismatch}))))
    (assoc metadata
           ::prm prm
           ::issuer (or (:issuer metadata) discovered-from))))

(defn required-endpoint
  "One metadata endpoint or a clear error."
  [metadata key name]
  (or (get metadata key)
      (throw (ex-info (str "MCP auth failed: authorization server metadata for " name
                           " has no " key)
                      {:type :oauth-no-endpoint :endpoint key}))))

(declare store-tokens!)

(defn issuer-of
  "The authorization server's issuer identifier for a discovered metadata
   map: its `issuer` value, else the URL its document was discovered at
   (discover-meta's ::issuer)."
  [metadata]
  (or (:issuer metadata) (::issuer metadata)))

(defn application-type
  "OIDC `application_type` inference for a vector of redirect URIs
   (SEP-837): \"native\" when every URI is loopback (localhost, 127.0.0.1
   or ::1), \"web\" when every URI is a remote http(s) URI, and nil when
   they disagree (the parameter is then omitted). MCP clients must send an
   appropriate type, because an OIDC authorization server defaults an
   omitted one to \"web\" and can then reject the loopback redirect URIs a
   native client uses."
  [redirect-uris]
  (let [classify (fn [uri]
                   (try
                     (let [u (java.net.URI. (str uri))
                           host (some-> (.getHost u) str/lower-case)
                           scheme (some-> (.getScheme u) str/lower-case)]
                       (cond
                         (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"} host)
                         :native

                         (and (contains? #{"http" "https"} scheme) (seq host))
                         :web

                         :else nil))
                     (catch Exception _ nil)))
        kinds (mapv classify redirect-uris)]
    (when (and (seq kinds) (apply = kinds) (contains? #{:native :web} (first kinds)))
      (name (first kinds)))))

(defn- client-info-for
  "The client registration stored for an authorization-server ISSUER, or
   nil. In :keyring mode there is no cross-server sharing — entries are
   per server."
  [issuer]
  (when-not (keyring-mode?)
    (get-in (read-file-store) [:issuers issuer :client-info])))

(defn resolve-client-id!
  "The OAuth client id for a server, bound to the resolved authorization
   server ISSUER (SEP-2352):

     - config :oauth {:client-id ..} (pre-registered) wins; when the
       server is already bound to a different issuer this throws
       :oauth-issuer-mismatch rather than reuse mismatched credentials —
       the authorization server changed and the user must re-authenticate;
     - else the DCR client registration stored for ISSUER, shared by every
       server resolving to the same authorization server (an unstamped
       pre-upgrade entry is reused only while ISSUER has none);
     - else RFC 7591 dynamic registration at the metadata's
       registration_endpoint, with the SEP-837 `application_type`
       (config :oauth {:application-type ..} overrides the inference),
       stored under ISSUER.

   REDIRECT-URI is what DCR registers and the dedup lookup expects."
  [name definition metadata redirect-uri]
  (let [cfg (:oauth definition)
        issuer (issuer-of metadata)]
    (when-not (seq issuer)
      (throw (ex-info "MCP auth failed: authorization server metadata has no issuer"
                      {:type :oauth-no-issuer})))
    (if-let [configured (:client-id cfg)]
      (do (when-let [bound (server-issuer name)]
            (when (not= bound issuer)
              (throw (ex-info (str "MCP auth failed: " name " is bound to authorization server "
                                   bound " but the server now points at " issuer
                                   ". Run /mcp logout " name " and authenticate again.")
                              {:type :oauth-issuer-mismatch
                               :bound-issuer bound :issuer issuer}))))
          configured)
      (or (get-in (client-info-for issuer) [:client-id])
          (let [entry (server-entry name)]
            ;; an unstamped pre-upgrade entry whose issuer has no
            ;; registration yet is bound to the first issuer resolved
            ;; after the upgrade (SEP-2352 back-stamp): its own
            ;; registration is claimed rather than re-registered. Once
            ;; the issuer has one, the shared registration above wins so
            ;; the authorization matches what a completed flow stores.
            (when (and entry (or (nil? (:issuer entry)) (= issuer (:issuer entry))))
              (get-in entry [:client-info :client-id])))
          (let [registration-endpoint (:registration_endpoint metadata)]
            (when-not registration-endpoint
              (throw (ex-info (str "MCP auth failed: " name " has no OAuth client id and the "
                                   "authorization server does not support dynamic client "
                                   "registration. Set :oauth {:client-id ...} in mcp.edn.")
                              {:type :oauth-no-registration})))
            (when-not (seq redirect-uri)
              (throw (ex-info (str "MCP auth failed: dynamic client registration for "
                                   name " needs a redirect URI")
                              {:type :oauth-invalid-config})))
            (let [client (oauth/register-client
                          registration-endpoint
                          {:redirect-uris [redirect-uri]
                           :client-name "kmet"
                           :application-type (or (:application-type cfg)
                                                 (application-type [redirect-uri]))
                           :scope (scopes-string cfg)})
                  client-id (:client_id client)]
              (store-client-info! name issuer {:client-id client-id
                                               :redirect-uris [redirect-uri]})
              client-id))))))

(defn resolve-flow
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

(defn verify-pkce-support!
  "OAuth 2.1 requires PKCE for the authorization-code flow and says a
   client MUST verify the authorization server supports it before
   authorizing: an absent `code_challenge_methods_supported` means no
   PKCE support, and we must refuse rather than send a challenge the
   server will ignore. Only the PKCE flow is checked — the RFC 8628
   device flow sends no challenge. Config :skip-pkce-verification
   overrides for broken servers."
  [name definition metadata]
  (let [cfg (:oauth definition)]
    (when (and (= :authorization-code (or (:grant cfg) :authorization-code))
               (not (true? (:skip-pkce-verification cfg)))
               (:authorization_endpoint metadata)
               (nil? (:code_challenge_methods_supported metadata)))
      (throw (ex-info (str "MCP auth failed: " name " does not advertise PKCE support "
                           "(no code_challenge_methods_supported in its authorization "
                           "server metadata) — refusing to authorize. Set "
                           ":oauth {:skip-pkce-verification true} to override.")
                      {:type :oauth-no-pkce})))))

(defn- authorization-url
  "The authorization endpoint URL with the PKCE challenge, state, scope,
   redirect URI and the RFC 8707 resource indicator (mandatory in
   authorization requests as well as token requests)."
  [authorize-endpoint client-id redirect-uri challenge state scope resource]
  (str authorize-endpoint
       "?response_type=code"
       "&client_id=" (oauth/url-encode client-id)
       "&redirect_uri=" (oauth/url-encode redirect-uri)
       "&code_challenge=" (oauth/url-encode challenge)
       "&code_challenge_method=S256"
       "&state=" (oauth/url-encode state)
       (when (seq scope)
         (str "&scope=" (oauth/url-encode scope)))
       (when (seq resource)
         (str "&resource=" (oauth/url-encode resource)))))

(defn prepare-pkce-flow
  "Build the PKCE authorization request for the loopback REDIRECT-URI and
   the per-request record needed to complete it. RFC 9207 requires the
   issuer the flow started against to be recorded with the same state that
   stores the code verifier, so PENDING carries :issuer alongside
   :verifier and :state.

   Returns {:url .. :pending ..} — PENDING is
   {:issuer :verifier :state :client-id :redirect-uri :scope :resource
    :token-endpoint :iss-supported?}."
  [name definition metadata redirect-uri]
  (let [issuer (issuer-of metadata)
        {:keys [verifier challenge]} (oauth/generate-pkce)
        state (oauth/random-hex 16)
        client-id (resolve-client-id! name definition metadata redirect-uri)
        scope (effective-scopes name definition (::prm metadata))
        resource (effective-resource definition)
        authorize-endpoint (required-endpoint metadata :authorization_endpoint name)
        token-endpoint (required-endpoint metadata :token_endpoint name)]
    {:url (authorization-url authorize-endpoint client-id redirect-uri
                             challenge state scope resource)
     :pending {:issuer issuer
               :verifier verifier
               :state state
               :client-id client-id
               :redirect-uri redirect-uri
               :scope scope
               :resource resource
               :token-endpoint token-endpoint
               :iss-supported? (true? (:authorization_response_iss_parameter_supported
                                       metadata))}}))

(defn validate-authorization-response!
  "RFC 9207 §2.4 validation of an authorization response against the
   ISSUER recorded when the flow started, with ISS-SUPPORTED? the
   metadata's `authorization_response_iss_parameter_supported`. RESPONSE
   is the callback's query params ({:code :state :iss :error
   :error_description}). A present `iss` must string-equal ISSUER — no
   case folding, default-port elision or trailing-slash normalization —
   and a missing one is rejected when the server advertised support. An
   authorization `error` is surfaced only once the response is proven
   authentic: on an issuer mismatch the client must not act on or display
   the server's error text. Returns nil; throws with
   :oauth-issuer-mismatch, :oauth-iss-missing or :oauth-authorization-error."
  [response issuer iss-supported?]
  (let [{:keys [iss error error-description]} response]
    (when (and (some? iss) (not= iss issuer))
      (throw (ex-info (str "OAuth authorization response issuer mismatch ("
                           iss " vs " issuer ") — the response was not used.")
                      {:type :oauth-issuer-mismatch :iss iss :issuer issuer})))
    (when (and (nil? iss) (true? iss-supported?))
      (throw (ex-info (str "OAuth authorization response is missing the iss parameter "
                           "the authorization server advertised support for")
                      {:type :oauth-iss-missing})))
    (when error
      (throw (ex-info (str "OAuth authorization failed: " error
                           (when (seq error-description) (str " — " error-description)))
                      {:type :oauth-authorization-error :error error})))
    nil))

(defn complete-pkce-flow!
  "Validate an authorization RESPONSE (callback query params or parsed
   manual input) against the PENDING record from prepare-pkce-flow, then
   exchange its code for tokens and store them under the recorded issuer.
   Returns :logged-in; throws on state/issuer/authorization/HTTP errors."
  [name pending response]
  (let [{:keys [issuer state verifier client-id redirect-uri scope resource
                token-endpoint iss-supported?]} pending]
    (when (and (:state response) (not= (:state response) state))
      (throw (ex-info "OAuth state mismatch" {:type :oauth-state-mismatch})))
    (validate-authorization-response! response issuer iss-supported?)
    (when-not (seq (:code response))
      (throw (ex-info "Missing authorization code" {:type :oauth-missing-code})))
    (let [tokens (oauth/exchange-authorization-code
                  token-endpoint
                  {:client-id client-id
                   :code (:code response)
                   :code-verifier verifier
                   :redirect-uri redirect-uri
                   :scope scope
                   :resource resource})]
      (store-tokens! name issuer tokens)
      :logged-in)))

(defn begin-device-flow!
  "Start the RFC 8628 device flow for a server: resolve the client id
   (issuer-bound, SEP-2352), request a device code, and return
   {:issuer :client-id :scope :resource :token-endpoint :device-code
    :user-code :verification-uri :interval :expires-in}. REDIRECT-URI is
   registered with DCR for a client that has none, keeping the loopback
   callback available for a later PKCE flow."
  [name definition metadata redirect-uri]
  (let [issuer (issuer-of metadata)
        client-id (resolve-client-id! name definition metadata redirect-uri)
        scope (effective-scopes name definition (::prm metadata))
        resource (effective-resource definition)
        device (oauth/start-device-authorization
                (required-endpoint metadata :device_authorization_endpoint name)
                {:client-id client-id :scope scope :resource resource})]
    (merge {:issuer issuer
            :client-id client-id
            :scope scope
            :resource resource
            :token-endpoint (required-endpoint metadata :token_endpoint name)}
           (select-keys device [:device-code :user-code :verification-uri
                                :interval :expires-in]))))

(defn- device-token-poll
  "One RFC 8628 token-endpoint poll, in the shape
   poll-oauth-device-code-flow expects."
  [{:keys [token-endpoint device-code client-id scope resource]}]
  (let [body (:body (oauth/fetch-json
                     token-endpoint
                     {:method :post
                      :headers {"Content-Type" "application/x-www-form-urlencoded"
                                "Accept" "application/json"}
                      :body (str "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                                 "&device_code=" (oauth/url-encode device-code)
                                 "&client_id=" (oauth/url-encode client-id)
                                 (when (seq scope)
                                   (str "&scope=" (oauth/url-encode scope)))
                                 (when (seq resource)
                                   (str "&resource=" (oauth/url-encode resource))))
                      :timeout 15000}))]
    (cond
      (string? (:access_token body))
      {:status :complete :value body}

      (string? (:error body))
      (case (:error body)
        "authorization_pending" {:status :pending}
        "slow_down" {:status :slow_down :interval-seconds (:interval body)}
        {:status :failed
         :message (str "Device flow failed: " (:error body)
                       (when (:error_description body)
                         (str ": " (:error_description body))))})

      :else
      {:status :failed :message "Invalid device token response"})))

(defn complete-device-flow!
  "Poll the device-code grant from begin-device-flow! to completion, store
   the tokens under the flow's issuer and return :logged-in. SIGNAL is the
   interaction's cancel atom."
  [name started signal]
  (let [{:keys [issuer interval expires-in]} started
        raw (oauth/poll-oauth-device-code-flow
             {:interval-seconds interval
              :expires-in-seconds expires-in
              :wait-before-first-poll true
              :signal signal
              :poll (fn [] (device-token-poll started))})]
    (store-tokens! name issuer
                   {:access (:access_token raw)
                    :refresh (:refresh_token raw)
                    :expires-in (:expires_in raw)
                    :scope (:scope raw)})
    :logged-in))

(defn- tokens->store
  "Normalize a lib token map {:access :refresh :expires-in :scope} into the
   store shape {:access .. :refresh .. :expires ms :scope ..}. A response
   without :expires-in (RFC 6749 makes it RECOMMENDED, not required) gets
   no :expires — the token is used until the server rejects it and the 401
   path refreshes."
  [tokens]
  (cond-> {:access (:access tokens)}
    (number? (:expires-in tokens))
    (assoc :expires (+ (System/currentTimeMillis) (* 1000 (:expires-in tokens))))
    (:refresh tokens) (assoc :refresh (:refresh tokens))
    (:scope tokens) (assoc :scope (:scope tokens))))

(defn store-tokens!
  "Normalize and persist a token response for a server under :tokens,
   keeping the rest of its entry (client info). TOKENS is the
   kmet.libs.oauth response shape {:access :refresh :expires-in :scope}.

   The two-argument form leaves the issuer binding as it is (an unstamped
   pre-upgrade entry); the three-argument form keys the tokens to the
   authorization server ISSUER and binds the server to it (SEP-2352)."
  ([name tokens]
   (store-server! name (assoc (or (server-entry name) {}) :tokens (tokens->store tokens))))
  ([name issuer tokens]
   (let [stored (tokens->store tokens)]
     (if (keyring-mode?)
       (store-server! name {:issuer issuer :tokens stored})
       (store-file-bound! name issuer nil stored)))))

(defn token-expired?
  "True when the stored tokens are expired (60s skew, pi's 5-min window
   reduced for MCP's shorter-lived tokens). An entry without an expiry is
   not expired."
  [entry]
  (let [expires (get-in entry [:tokens :expires])]
    (and (number? expires)
         (<= expires (+ (System/currentTimeMillis) 60000)))))

(defn bearer-token
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

(defn- bearer-required-error
  [name]
  (ex-info (str "MCP auth failed: " name " has :auth :bearer but no bearer token — "
                "set :bearer-token or :bearer-token-env")
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

;; in-memory cache for machine-grant tokens (client-credentials /
;; jwt-bearer — §7.8.6): {server-name {connection-fingerprint entry}} so a
;; config edit re-fetches instead of reusing a token minted for the old
;; target; defined before logout! below
(defonce ^:private machine-token-cache (atom {}))

(defn- machine-cache-key
  "The cache path for a machine token: the server name plus a fingerprint
   of the connection-defining config (:url and :oauth) — editing either
   re-fetches rather than reusing a token minted for the old target."
  [name definition]
  [name (select-keys definition [:url :oauth])])

(defn clear-machine-tokens!
  "Forget cached machine-grant tokens; with no argument every server
   (extension unload), else just NAME."
  ([] (reset! machine-token-cache {}) nil)
  ([name] (swap! machine-token-cache dissoc name) nil))

(defn grant-of
  "The configured grant: :authorization-code (default) |
   :client-credentials | :jwt-bearer."
  [definition]
  (or (get-in definition [:oauth :grant]) :authorization-code))

(defn machine-grant?
  "True for the non-interactive grants (client-credentials / jwt-bearer)."
  [definition]
  (contains? #{:client-credentials :jwt-bearer} (grant-of definition)))

(defn fetch-machine-token!
  "Fetch a fresh token for a machine-grant server and cache it. Throws
   MCP auth failed on any error (§7.7)."
  [name definition]
  (let [cfg (:oauth definition)
        metadata (discover-meta name definition)
        token-endpoint (required-endpoint metadata :token_endpoint name)
        scope (effective-scopes name definition (::prm metadata))
        resource (effective-resource definition)
        tokens (case (grant-of definition)
                 :client-credentials
                 (oauth/client-credentials-token
                  token-endpoint
                  {:client-id (:client-id cfg)
                   :client-secret (:client-secret cfg)
                   :token-endpoint-auth-method (:token-endpoint-auth-method cfg)
                   :scope scope
                   :resource resource})

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
                   (oauth/jwt-bearer-token
                    token-endpoint
                    {:private-key (if (and key-file (string? key-file))
                                    (read-text key-file)
                                    jwk)
                     :algorithm (:algorithm cfg)
                     :issuer (:issuer cfg)
                     :subject (:subject cfg)
                     :audience (:audience cfg)
                     :client-id (:client-id cfg)
                     :scope scope
                     :resource resource})))
        stored (tokens->store tokens)]
    (swap! machine-token-cache assoc-in (machine-cache-key name definition) stored)
    stored))

(defn machine-token-cached?
  "True when any machine-grant token is cached in memory for NAME (the 401
   retry path re-fetches regardless)."
  [name]
  (boolean (seq (get @machine-token-cache name))))

(defn- machine-token-header
  "Authorization header for a machine-grant server: the cached token, or
   a fresh fetch when missing/expired. FORCE skips the cache — the 401
   retry path must not resend a rejected token."
  [name definition force]
  (let [entry (get-in @machine-token-cache (machine-cache-key name definition))
        expired? (and entry
                      (number? (:expires entry))
                      (<= (:expires entry) (+ (System/currentTimeMillis) 60000)))]
    (if (and entry (not force) (not expired?))
      (oauth-bearer-header entry)
      (oauth-bearer-header (fetch-machine-token! name definition)))))

;; ─── Request auth (§7.8.5) ────────────────────────────────────────────────

(declare refresh-tokens!)

(defn- oauth-header
  "Authorization header from the stored tokens; refreshes silently when
   expired, throws when not authenticated — no stored credentials, or
   only a client registration (a bound entry can outlive its tokens after
   an authorization-server change) — §7.8.5 pre-emptive refresh."
  [name definition]
  (let [entry (server-entry name)]
    (cond
      (nil? (get-in entry [:tokens :access])) (throw (auth-required-error name))
      (token-expired? entry)
      (if-let [refreshed (refresh-tokens! name definition)]
        (oauth-bearer-header refreshed)
        (throw (auth-required-error name)))
      :else (oauth-bearer-header (:tokens entry)))))

(defn- refresh-tokens!
  "Refresh the stored tokens; returns the fresh tokens map or nil when no
   refresh token is stored / the refresh failed. A stored refresh token is
   never sent to an authorization server other than the one it was issued
   by (SEP-2352); an unstamped pre-upgrade entry is bound on success."
  [name definition]
  (let [entry (server-entry name)
        refresh (get-in entry [:tokens :refresh])]
    (when (and (seq refresh)
               (seq (:url definition)))
      (try
        (let [metadata (discover-meta name definition)
              issuer (issuer-of metadata)]
          (when (or (nil? (:issuer entry)) (= (:issuer entry) issuer))
            (let [token-endpoint (required-endpoint metadata :token_endpoint name)
                  client-id (or (get-in definition [:oauth :client-id])
                                (get-in entry [:client-info :client-id]))
                  tokens (when client-id
                           (oauth/refresh-access-token
                            token-endpoint
                            {:client-id client-id
                             :refresh-token refresh
                             :scope (get-in entry [:tokens :scope])
                             :resource (effective-resource definition)}))]
              (when tokens
                (store-tokens! name issuer tokens)
                (tokens->store tokens)))))
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
   HTTP server. Returns nil when the server has no auth and no static
   :headers."
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
         :on-401 (fn [response]
                   (when response (record-challenge! name (transport/header-value (:headers response) "WWW-Authenticate")))
                   (merge-headers (machine-token-header name definition true)))}
        {:auth-headers (fn [] (merge-headers (oauth-header name definition)))
         :on-401 (fn [response]
                   (when response (record-challenge! name (transport/header-value (:headers response) "WWW-Authenticate")))
                   (merge-headers (oauth-header-after-401 name definition)))})

      (= :bearer (:auth definition))
      {:auth-headers (fn []
                       (let [token (bearer-token definition)]
                         (if (str/blank? token)
                           (throw (bearer-required-error name))
                           (merge-headers (oauth-bearer-header {:access token})))))}

      :else
      (when (seq config-headers)
        {:auth-headers (fn [] config-headers)}))))

(defn logout!
  "Forget a server's stored credentials, its recorded 401 challenge and
   any cached machine-grant token."
  [name]
  (clear-machine-tokens! name)
  (clear-challenges! name)
  (clear-server! name))


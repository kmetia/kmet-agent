(ns kmet.extensions.lsp-adapter.lsp
  "LSP protocol layer over kmet.libs.jsonrpc: initialize handshake,
   conservative client capabilities, the server→client request/notification
   policy, and document-sync message builders. Pure protocol — connection
   ownership lives in runtime."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.jsonrpc :as jrpc]))

(def ^:private uri-path-unreserved
  "Bytes left verbatim in a file URI path: RFC 3986 unreserved characters
   plus the '/' separator. Everything else (spaces, '#', '?', '%', unicode)
   is percent-encoded."
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/"))

(defn- utf8-percent-encoded
  "Code point CP percent-encoded as its UTF-8 octets, one %XX triplet per
   byte (pure arithmetic — no charset interop)."
  [cp]
  (let [t (fn [octet] (format "%%%02X" octet))]
    (cond
      (< cp 0x80) (t cp)
      (< cp 0x800) (str (t (bit-or 0xC0 (unsigned-bit-shift-right cp 6)))
                        (t (bit-or 0x80 (bit-and cp 0x3F))))
      (< cp 0x10000) (str (t (bit-or 0xE0 (unsigned-bit-shift-right cp 12)))
                          (t (bit-or 0x80 (bit-and (unsigned-bit-shift-right cp 6) 0x3F)))
                          (t (bit-or 0x80 (bit-and cp 0x3F))))
      :else (str (t (bit-or 0xF0 (unsigned-bit-shift-right cp 18)))
                 (t (bit-or 0x80 (bit-and (unsigned-bit-shift-right cp 12) 0x3F)))
                 (t (bit-or 0x80 (bit-and (unsigned-bit-shift-right cp 6) 0x3F)))
                 (t (bit-or 0x80 (bit-and cp 0x3F)))))))

(defn- encode-uri-path
  "Percent-encode PATH for a file URI, keeping '/' separators and combining
   surrogate pairs manually (no Character/String interop)."
  [path]
  (let [s (str path)
        n (count s)]
    (loop [i 0, ^StringBuilder sb (StringBuilder.)]
      (if (>= i n)
        (str sb)
        (let [c (nth s i)
              v (int c)
              [cp step] (if (and (<= 0xD800 v 0xDBFF)
                                 (< (inc i) n)
                                 (<= 0xDC00 (int (nth s (inc i))) 0xDFFF))
                          [(+ 0x10000
                              (bit-shift-left (- v 0xD800) 10)
                              (- (int (nth s (inc i))) 0xDC00))
                           2]
                          [v 1])]
          (.append sb (if (and (< cp 0x80)
                               (contains? uri-path-unreserved (char cp)))
                        (char cp)
                        (utf8-percent-encoded cp)))
          (recur (+ i step) sb))))))

(defn- absolute-uri-path
  "PATH as an absolute, slash-separated URI path component: absolutized
   against the process cwd; Windows shapes are normalized to JDK
   Path#toUri wire forms — drive paths root as /C:/..., UNC shares become
   the authority //server/share. Backslashes elsewhere are ordinary
   filename bytes and get percent-encoded."
  [path]
  (let [p (str (fs/absolutize (fs/path (str path))))
        unc (re-find #"^\\\\([^\\]+)[\\/](.*)$" p)
        drive (re-find #"^([A-Za-z]):[\\/](.*)$" p)]
    (cond
      unc (str "//" (nth unc 1) "/" (str/replace (nth unc 2) "\\" "/"))
      drive (str "/" (nth drive 1) "/" (str/replace (nth drive 2) "\\" "/"))
      (fs/windows?) (str/replace p "\\" "/")
      :else p)))

(defn- join-file-uri
  "FILE-URI prefix + encoded path, keeping UNC authorities intact."
  [encoded]
  (if (str/starts-with? encoded "//")
    (str "file:" encoded)
    (str "file://" encoded)))

(defn path->uri
  "file:// URI for PATH with correct percent-encoding (spaces, unicode).
   Built by hand rather than via Path#toUri: the runtime Path implementation
   class is neither registered in the extension sci sandbox (instance-method
   calls throw \"not allowed\") nor reflectable in bb's native image."
  [path]
  (join-file-uri (encode-uri-path (absolute-uri-path path))))

(defn uri->path
  "FILE-URI back to a filesystem path (percent-decoded)."
  [uri]
  (let [bare (cond
               (str/starts-with? uri "file://") (subs uri 7)
               :else uri)]
    (java.net.URLDecoder/decode bare "UTF-8")))

;; ─── Handshake ────────────────────────────────────────────────────────────

(def ^:private client-capabilities
  "Conservative and static: full-text sync assumed, push diagnostics
   wanted, workspace configuration answered. Servers needing more degrade
   gracefully."
  {:textDocument {:synchronization {:dynamicRegistration false}
                  :publishDiagnostics {:relatedInformation false
                                       :versionSupport true}}
   :workspace {:configuration true
               :workspaceFolders true
               :didChangeConfiguration {:dynamicRegistration false}}})

(defn initialize-params
  "initialize request payload. PID ties the server to our process (many
   servers exit when we die); sent as both rootUri and workspaceFolders —
   rootUri is deprecated but every shipping server still reads it."
  [root pid init-options]
  (let [root-uri (path->uri root)]
    {:processId pid
     :rootUri root-uri
     :rootPath (str root)
     :capabilities client-capabilities
     :initializationOptions (or init-options {})
     :workspaceFolders [{:uri root-uri :name (str (fs/file-name root))}]}))

(defn start!
  "Connects + handshakes a stdio LSP server: jsonrpc connect → initialize
   (INITIALIZE-TIMEOUT-MS) → initialized notification. Returns the lib
   conn; throws on any failure (runtime records it)."
  [{:keys [command env cwd on-notification on-request]}
   root init-options timeout-ms]
  (let [conn (jrpc/connect-stdio
              {:command command :env env :cwd cwd :framing :content-length
               :on-notification on-notification :on-request on-request})
        result (try
                 (jrpc/request! conn "initialize"
                                (initialize-params root
                                                   (:pid conn)
                                                   init-options)
                                {:timeout-ms timeout-ms})
                 (catch Exception e
                   ;; A server that dies during startup takes its diagnosis
                   ;; to stderr (missing temp dir, bad interpreter, ...).
                   ;; The drain thread races process death, so give it a
                   ;; bounded beat to land the lines before rethrowing.
                   (let [tail (loop [tries 0]
                                (or (not-empty (jrpc/stderr-tail conn))
                                    (when (and (< tries 5) (not (jrpc/alive? conn)))
                                      (Thread/sleep 100)
                                      (recur (inc tries)))))
                         msg (ex-message e)]
                     (jrpc/close! conn)
                     (throw (ex-info
                             (if tail
                               (str msg " — server stderr: "
                                    (str/join " | " tail))
                               msg)
                             {:server-stderr tail}
                             e)))))]
    (jrpc/notify! conn "initialized" {})
    ;; server-info rides on the lib conn itself - callers pass the conn
    ;; around as one opaque value
    (assoc conn :server-info (:serverInfo result))))

(def shutdown-dance
  "The polite LSP close sequence passed to jrpc/close!'s :graceful."
  {:request "shutdown" :notification "exit"})

;; ─── Server→client policy ────────────────────────────────────────────────

(defn make-on-request
  "Answers the requests servers legally send during a session.
   workspace/applyEdit is refused by policy: servers must never write
   through us — edits flow exclusively through kmet's own edit tools.
   Unknown methods return nil ⇒ the lib replies -32601."
  []
  (fn [method _params]
    (case method
      "workspace/configuration" []                       ; defaults per item
      "client/registerCapability" {}                     ; accept, ignore
      "window/workDoneProgress/create" {}
      "workspace/applyEdit" {:applied false
                             :failureReason "kmet edits files itself"}
      nil)))

;; ─── Document-sync builders (full text always) ───────────────────────────

(defn did-open
  "textDocument/didOpen — VERSION starts at 1."
  [path language-id text]
  {:textDocument {:uri (path->uri path)
                  :languageId language-id
                  :version 1
                  :text text}})

(defn did-change-full
  "Full-text didChange; re-reading disk per touch picks up kmet's own
   edits without watching the filesystem."
  [path version text]
  {:textDocument {:uri (path->uri path) :version version}
   :contentChanges [{:text text}]})

(defn did-close [path]
  {:textDocument {:uri (path->uri path)}})

(defn text-document-position
  "Shared {textDocument, position} params; LINE/CHARACTER arrive 1-based
   from the tool boundary and convert to the wire format exactly here."
  ([path line character]
   {:textDocument {:uri (path->uri path)}
    :position {:line (dec line) :character (dec character)}}))

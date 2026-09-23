(ns kmet.extensions.mcp-adapter.prompts
  "MCP prompts as slash commands (pi: prompts.ts, adapted to kmet).

   Every prompt advertised by a server becomes a /mcp__<server>__<prompt>
   command, registered from the metadata cache at init (available before
   any server connects) and resynced after every connect/refresh. The
   handler parses bash-style quoted args, maps positional/named values
   onto the prompt's declared arguments (named wins; undeclared named
   args pass through — the MCP spec allows arbitrary string args),
   connects lazily (honoring the failure backoff window), calls
   prompts/get and delivers the formatted result via send-user-message —
   the prompt text enters the conversation like a user message (pi
   pi.sendUserMessage)."
  (:require [clojure.string :as str]
            [kmet.extensions.mcp-adapter.client :as client]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.extension :as ext]))

;; ─── Naming (pi sanitizePromptName / formatPromptCommandName) ─────────────

(defn- sanitize-prompt-name
  [name]
  (let [cleaned (-> (str name)
                    (str/replace #"[^A-Za-z0-9_-]+" "_")
                    (str/replace #"^[_-]+|[_-]+$" ""))]
    (cond
      (str/blank? cleaned) "prompt"
      (re-matches #"^[0-9].*" cleaned) (str "_" cleaned)
      :else cleaned)))

(defn- server-part
  "The server component of the command name for a prefix mode (pi
   getServerPrefix || sanitizeServerPrefix || \"server\")."
  [server-name mode]
  (let [sanitized (proxy/sanitize-server-name server-name)
        part (case mode
               :none sanitized
               :short (let [short (proxy/sanitize-server-name
                                   (str/replace server-name #"-?mcp$" ""))]
                        (if (seq short) short "mcp"))
               :mcp "mcp"
               sanitized)]
    (if (seq part) part "server")))

(defn command-name
  "The slash command name for a prompt: mcp__<server-part>__<prompt>."
  [server-name prompt-name prefix-mode]
  (str "mcp__" (server-part server-name prefix-mode)
       "__" (sanitize-prompt-name prompt-name)))

;; ─── Arg parsing (pi parsePromptArgs / tokenizeArgs) ──────────────────────

(defn- tokenize-args
  "Bash-style tokenizer: single/double quotes group, backslash escapes
   outside single quotes, whitespace separates."
  [input]
  (let [tokens (atom [])
        current (atom "")]
    (loop [chars (seq (or input ""))
           quote nil
           escaped false]
      (if-let [char (first chars)]
        (cond
          escaped (do (swap! current str char) (recur (rest chars) quote false))
          (= char \\)
          (if (= quote \')
            (do (swap! current str char) (recur (rest chars) quote false))
            (recur (rest chars) quote true))
          (and quote (= char quote))
          (do (swap! current str char) (recur (rest chars) nil false))
          (or (= char \") (= char \'))
          (do (swap! current str char) (recur (rest chars) char false))
          (and (nil? quote) (Character/isWhitespace char))
          (do (when (seq @current)
                (swap! tokens conj @current)
                (reset! current ""))
              (recur (rest chars) quote false))
          :else (do (swap! current str char) (recur (rest chars) quote false)))
        (do (when (seq @current)
              (swap! tokens conj @current))
            @tokens)))))

(defn- find-unquoted-equals
  [token]
  (loop [chars (seq token) quote nil index 0]
    (if-let [char (first chars)]
      (cond
        (and quote (= char quote)) (recur (rest chars) nil (inc index))
        (or (= char \") (= char \')) (recur (rest chars) char (inc index))
        (= char \=) index
        :else (recur (rest chars) quote (inc index)))
      -1)))

(defn- strip-quotes
  [value]
  (if (and (>= (count value) 2)
           (or (str/starts-with? value "\"") (str/starts-with? value "'"))
           (str/ends-with? value (subs value 0 1)))
    (subs value 1 (dec (count value)))
    value))

(defn parse-args
  "Split an argument string into positional and named parts (pi
   parsePromptArgs): key=value tokens become named, the rest positional;
   quoting supported so values with spaces survive:
     /mcp__demo__brief day=today \"important tasks\""
  [input]
  (let [positional (atom [])
        named (atom {})]
    (doseq [token (tokenize-args input)]
      (let [eq (find-unquoted-equals token)]
        (if (pos? eq)
          (let [key (str/trim (subs token 0 eq))
                value (strip-quotes (str/trim (subs token (inc eq))))]
            (when (seq key)
              (swap! named assoc key value)))
          (swap! positional conj (strip-quotes token)))))
    {:positional @positional :named @named}))

(defn resolve-args
  "Map parsed args onto the prompt's declared ARGUMENTS (pi
   resolvePromptArgs): named wins over positional per slot; undeclared
   named args pass through (permissive server schemas); missing required
   args produce a usage message naming COMMAND-NAME. Returns
   {:ok true :args {...}} or {:ok false :error str}."
  [arguments parsed command-name]
  (let [declared (or arguments [])
        args (atom {})
        positional-index (atom 0)]
    (doseq [arg-def declared]
      (let [named-value (get-in parsed [:named (:name arg-def)])
            positional-value (nth (:positional parsed) @positional-index nil)
            value (or named-value positional-value)]
        (when (and value (not= "" value))
          (swap! args assoc (:name arg-def) value))
        (when (nil? named-value)
          (swap! positional-index inc))))
    (doseq [[key value] (:named parsed)]
      (when-not (contains? @args key)
        (swap! args assoc key value)))
    (let [missing (filterv (fn [arg-def]
                             (and (:required arg-def)
                                  (or (nil? (get @args (:name arg-def)))
                                      (= "" (get @args (:name arg-def))))))
                           declared)]
      (if (seq missing)
        (let [usage (str/join " " (map (fn [a] (if (:required a)
                                                 (str "<" (:name a) ">")
                                                 (str "[" (:name a) "]")))
                                       declared))
              missing-list (str/join ", " (map :name missing))]
          {:ok false
           :error (str "Missing required argument" (when (> (count missing) 1) "s")
                       ": " missing-list ".\nUsage: /" command-name " " usage)})
        {:ok true :args @args}))))

;; ─── Result formatting (pi formatPromptResult) ────────────────────────────

(defn- extract-message-text
  [message]
  (let [content (:content message)]
    (when (map? content)
      (case (:type content)
        "text" (:text content)
        "resource" (let [resource (:resource content)]
                     (if (and (map? resource) (string? (:text resource)))
                       (str "[resource " (:uri resource) "]\n" (:text resource))
                       (str "[resource " (:uri resource) "]")))
        "resource_link" (str "[resource_link " (:uri content)
                             (when (:name content) (str " — " (:name content))) "]")
        "image" (str "[image " (or (:mimeType content) "unknown")
                     (when (:data content) " (embedded)") "]")
        "audio" (str "[audio " (or (:mimeType content) "unknown") "]")
        nil))))

(defn format-result
  "Flatten a prompts/get result into a single string: a lone user message
   is returned bare; mixed roles keep inline [role] markers."
  [result]
  (let [messages (or (:messages result) [])
        texts (keep (fn [m]
                      (let [text (extract-message-text m)]
                        (when (seq (str/trim (or text "")))
                          (if (and (= "user" (:role m))
                                   (= 1 (count messages)))
                            text
                            (str "[" (:role m) "] " text)))))
                    messages)]
    (str/join "\n\n" texts)))

;; ─── Command handler (pi createPromptCommand handler) ─────────────────────
;; Defined after prompt-specs/find-live-spec (sci resolves symbols at
;; analysis time — callers must come after their callees).

(defn- prompt-specs
  "Prompt command specs from the metadata cache (fresh + non-disabled
   servers only — the cache is the only source at init, pi
   resolveCachedPrompts). Each spec: {:server :original :command-name
   :description :arguments}."
  [state]
  (let [state @state
        config (:config state)
        settings (:settings config)
        prefix-mode (or (:tool-prefix settings) :server)]
    (for [[name definition] (sort-by key (:mcp-servers config))
          :when (not (true? (:disabled definition)))
          :let [entry (metadata/server-entry (:cache state) name definition settings)]
          :when entry
          prompt (:prompts entry)]
      {:server name
       :original (:name prompt)
       :command-name (command-name name (:name prompt) prefix-mode)
       :description (or (:description prompt) "")
       :arguments (or (:arguments prompt) [])})))

(defn- find-live-spec
  "The spec for a server+original prompt from the current cache (the live
   source after connects refresh it), or nil."
  [state server original]
  (first (filter (fn [spec]
                   (and (= server (:server spec))
                        (= original (:original spec))))
                 (prompt-specs state))))

(defn- notify
  [state ctx message & [type]]
  (if (:has-ui ctx)
    (ext/ui-notify (:api @state) message (or type "error"))
    (println message)))

(defn handle-prompt-command
  "Run a prompt command: parse args → resolve against the declared
   arguments → lazy connect (failure backoff window honored) → prompts/get
   → format → send-user-message (the result enters the conversation)."
  [state spec ctx args]
  (let [parsed (parse-args (or args ""))
        resolved (resolve-args (:arguments spec) parsed (:command-name spec))]
    (if-not (:ok resolved)
      (notify state ctx (:error resolved))
      (let [prompt-args (:args resolved)]
        (cond
          (nil? (get-in @state [:config :mcp-servers (:server spec)]))
          (notify state ctx (str "MCP prompt \"" (:original spec)
                                 "\" is no longer configured. Run /mcp refresh to reload."))

          :else
          (let [conn (proxy/ensure-lazy-connected state (:server spec))]
            (if (nil? conn)
              (notify state ctx (str "MCP server \"" (:server spec)
                                     "\" is not available. Run /mcp connect "
                                     (:server spec) " to retry."))
              (let [live (or (find-live-spec state (:server spec) (:original spec))
                             spec)]
                (try
                  (let [result (client/get-prompt conn (:original live)
                                                  prompt-args)
                        text (format-result result)]
                    (if (seq (str/trim text))
                      (do (ext/send-user-message (:api @state) text)
                          (when (:has-ui ctx)
                            (ext/ui-notify (:api state)
                                           (str "Prompt \"" (:original live)
                                                "\" sent to the conversation.")
                                           "info")))
                      (notify state ctx (str "MCP prompt \"" (:original live)
                                             "\" returned no text content.")
                              "warning")))
                  (catch Exception e
                    (notify state ctx (str "MCP prompt \"" (:original live)
                                           "\" failed: " (ex-message e)))))))))))))

;; ─── Command registration (pi resolveCachedPrompts + createPromptCommand) ─

(defn- build-command-description
  [spec]
  (let [base (or (:description spec) (str "MCP prompt from " (:server spec)))
        truncated (proxy/truncate-at-word (str "MCP: " base) 120)]
    (if (seq truncated) truncated (str "MCP prompt from " (:server spec)))))

(defn sync-prompt-commands!
  "Diff-based resync (like sync-direct-tools!): register new/updated (by
   fingerprint), unregister removed. Runs at init and after every
   connect/refresh. STATE carries :registered-prompts (atom {command-name
   fingerprint})."
  [state]
  (let [specs (prompt-specs state)
        next-names (set (map :command-name specs))
        registered @(:registered-prompts @state)
        fingerprint (fn [spec]
                      (pr-str (select-keys spec [:server :original
                                                 :description :arguments])))
        make-handler (fn [spec]
                       (fn [ctx args]
                         (handle-prompt-command state spec ctx args)))]
    (doseq [spec specs]
      (let [fp (fingerprint spec)]
        (when (not= fp (get registered (:command-name spec)))
          (ext/register-command! (:api @state)
                                 {:name (:command-name spec)
                                  :description (build-command-description spec)
                                  :handler (make-handler spec)})
          (swap! (:registered-prompts @state) assoc (:command-name spec) fp))))
    (doseq [name (remove next-names (keys registered))]
      (ext/unregister-command! (:api @state) name)
      (swap! (:registered-prompts @state) dissoc name))))

;; ─── /mcp prompts listing ─────────────────────────────────────────────────

(defn prompts-text
  "Every known prompt command (cache), sorted by command name — for
   /mcp prompts."
  [state]
  (let [specs (sort-by :command-name (prompt-specs state))]
    (if (seq specs)
      (str/join "\n"
                (map (fn [spec]
                       (str "- /" (:command-name spec)
                            (when (seq (:description spec))
                              (str " — " (proxy/truncate-at-word
                                          (:description spec) 80)))))
                     specs))
      "No MCP prompts cached. Connect a server with /mcp connect to discover prompts.")))

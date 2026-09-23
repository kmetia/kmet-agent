(ns kmet.extensions.mcp-adapter.panel
  "The mcp-adapter's UI, built directly on the shared kmet.tui.* layer and
   mounted via ui-custom (pi: pi-mcp-adapter ships mcp-panel.ts and hosts
   it with ctx.ui.custom; kmet.extension docstring — extensions build
   their own components, the api carries only host bridges).

   McpPanel — port of pi-mcp-adapter's mcp-panel.ts: server/tool rows with
   direct/proxy toggles, name + description search, reconnect/auth keys,
   failure display, unsaved-changes discard confirm, rainbow scroll
   progress, key hints, 60s inactivity cancel. Shown by /mcp in UI mode.
   TextDialog — scrollable text output for search/list (the flash is a
   single line). make-prompt-dialog — the OAuth flow's input prompt."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.tui.core :as tui]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.hiccup :as h]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.tui.components.input :as input]
            [kmet.libs.clipboard :as clipboard]
            [kmet.extensions.mcp-adapter.metadata :as metadata]))

;; ─── Styling (pi mcp-panel.ts DEFAULT_THEME) ──────────────────────────────

(def ^:private panel-theme
  "Pi PanelTheme — ANSI code strings, rendered via kmet.tui.utils/sgr."
  {:border "2" :title "2" :selected "36" :direct "32" :needs-auth "33"
   :placeholder "2;3" :description "2" :hint "2" :confirm "32" :cancel "31"})

(defn- fg [code s]
  (if (or (nil? code) (str/blank? code)) s (str (u/sgr code) s (u/sgr 0))))

(defn- bold [s] (str (u/sgr 1) s (u/sgr 22)))
(defn- italic [s] (str (u/sgr 3) s (u/sgr 23)))
(defn- inverse [s] (str (u/sgr 7) s (u/sgr 27)))

(def ^:private rainbow-colors
  ["38;2;178;129;214" "38;2;215;135;175" "38;2;254;188;56" "38;2;228;192;15"
   "38;2;137;210;129" "38;2;0;175;175" "38;2;23;143;185"])

(defn- rainbow-progress [filled total]
  (str/join " " (map (fn [i]
                       (fg (nth rainbow-colors (mod i (count rainbow-colors)))
                           (if (< i filled) "●" "○")))
                     (range total))))

(defn- grouped-number
  "N with thousands separators (pi: toLocaleString)."
  [n]
  (let [parts (str/split (str n) #"\." 2)
        int-part (first parts)
        grouped (->> (reverse int-part)
                     (partition-all 3)
                     (map (fn [chunk] (apply str (reverse chunk))))
                     (reverse)
                     (str/join ","))]
    (if (next parts) (str grouped "." (second parts)) grouped)))

(defn- fuzzy-score
  "Pi fuzzyScore: substring hits beat subsequence matches."
  [query text]
  (let [lq (str/lower-case query)
        lt (str/lower-case text)]
    (if (str/includes? lt lq)
      (+ 100 (* 50 (/ (count lq) (max 1 (count lt)))))
      (loop [qi 0, score 0, consecutive 0, i 0]
        (if (or (>= i (count lt)) (>= qi (count lq)))
          (if (>= qi (count lq)) score 0)
          (if (= (nth lt i) (nth lq qi))
            (recur (inc qi) (+ score 10 consecutive) (+ consecutive 5) (inc i))
            (recur qi score 0 (inc i))))))))

(defn- sanitize-display-text
  "Pi sanitizeTerminalText: control characters → spaces."
  [s]
  (str/replace (str (or s "")) #"[\x00-\x1f\x7f\x80-\x9f]" " "))

(defn- estimate-tokens
  "Pi estimateTokens: schema + description + name over 4, +10."
  [tool]
  (let [schema-len (count (pr-str (or (:inputSchema tool) {})))
        desc-len (count (str (or (:description tool) "")))]
    (+ 10 (long (Math/ceil (/ (+ (count (str (:name tool))) desc-len schema-len) 4.0))))))

(def ^:private spawn concurrent/spawn)

(declare rebuild-server-tools! handle-discard-input)

;; ─── Server/tool state (pi McpPanel constructor) ──────────────────────────

(defn- tool-filter-for
  "The direct-tools filter for a server: server :direct-tools, else
   settings :direct-tools (pi: definition.directTools ?? globalDirect)."
  [definition settings]
  (if (contains? definition :direct-tools)
    (:direct-tools definition)
    (:direct-tools settings)))

(defn- tool-is-direct? [filter tool-name]
  (or (true? filter)
      (and (coll? filter) (some #{tool-name} filter))))

(defn- build-servers
  "Server state from config + metadata cache + callbacks (pi McpPanel
   constructor). Tool states come from the cache only (never spawns); the
   connection status comes from the live state via callbacks."
  [config cache callbacks]
  (let [settings (:settings config)]
    (mapv (fn [[name definition]]
            (let [filter (tool-filter-for definition settings)
                  disabled? (true? (:disabled definition))
                  entry (when-not disabled?
                          (metadata/server-entry cache name definition settings))
                  tools (mapv (fn [tool]
                                (let [is-direct (tool-is-direct? filter (:name tool))]
                                  {:name (:name tool)
                                   :description (str (or (:description tool) ""))
                                   :is-direct is-direct
                                   :was-direct is-direct
                                   :estimated-tokens (estimate-tokens tool)}))
                              (:tools entry))]
              {:name name
               :expanded false
               :connection-status ((:get-connection-status callbacks) name)
               :failure-message ((:get-failure-message callbacks) name)
               :tools tools
               :has-cached-data (boolean entry)}))
          (sort-by key (:mcp-servers config)))))

;; ─── Key matching (pi panel-keys.ts) ──────────────────────────────────────

(defn- select-up? [panel data]
  (kb/matches-key (:keys-manager panel) data "tui.select.up"))
(defn- select-down? [panel data]
  (kb/matches-key (:keys-manager panel) data "tui.select.down"))
(defn- select-confirm? [panel data]
  (kb/matches-key (:keys-manager panel) data "tui.select.confirm"))

(defn- save-keys
  "Resolved mcp.panel.save chords (user-overridable; default ctrl+s)."
  [panel]
  (or (seq (kb/get-keys (:keys-manager panel) "mcp.panel.save")) ["ctrl+s"]))

(defn- save-match? [panel data]
  (boolean (some #(keys/matches-key? data %) (save-keys panel))))

(defn- printable-char? [data]
  (and (= 1 (count data)) (>= (int (first data)) 32)))

;; ─── Viewport / selection helpers ─────────────────────────────────────────

(defn- selected-item [panel]
  (let [items @(:visible-atom panel)
        cursor @(:cursor-atom panel)]
    (when (seq items) (nth items (min cursor (dec (count items)))))))

(defn- server-by-name [panel name]
  (first (keep-indexed (fn [i s] (when (= name (:name s)) [i s]))
                       @(:servers-atom panel))))

(defn- selected-server [panel]
  (when-let [item (selected-item panel)]
    (nth @(:servers-atom panel) (:server-index item))))

(defn- selected-failure? [panel]
  (let [server (selected-server panel)]
    (and server
         (= :failed (:connection-status server))
         (seq (:failure-message server)))))

(defn- move-cursor! [panel delta]
  (let [n (count @(:visible-atom panel))]
    (when (pos? n)
      (swap! (:cursor-atom panel) #(max 0 (min (dec n) (+ % delta)))))))

(defn- update-dirty! [panel]
  (reset! (:dirty-atom panel)
          (boolean (some (fn [s]
                           (some (fn [t] (not= (:is-direct t) (:was-direct t)))
                                 (:tools s)))
                         @(:servers-atom panel)))))

(defn- rebuild-visible!
  "Pi rebuildVisibleItems: without a query, servers + expanded tools; with
   one, only servers with matching tools (name search also scores the
   server name at 0.6)."
  [panel]
  (let [desc? @(:desc-active-atom panel)
        query (if desc? @(:desc-query-atom panel) @(:name-query-atom panel))
        mode (if desc? :desc :name)
        items (atom [])]
    (doseq [[si server] (map-indexed vector @(:servers-atom panel))]
      (if (seq query)
        (let [matching (keep (fn [[ti tool]]
                               (let [score (if (= mode :name)
                                             (max (fuzzy-score query (:name tool))
                                                  (* 0.6 (fuzzy-score query (:name server))))
                                             (fuzzy-score query (:description tool)))]
                                 (when (pos? score)
                                   {:type :tool :server-index si :tool-index ti})))
                             (map-indexed vector (:tools server)))]
          (when (seq matching)
            (swap! items into matching)))
        (do (swap! items conj {:type :server :server-index si})
            (when (:expanded server)
              (doseq [ti (range (count (:tools server)))]
                (swap! items conj {:type :tool :server-index si :tool-index ti}))))))
    (reset! (:visible-atom panel) @items)
    (swap! (:cursor-atom panel) #(min % (max 0 (dec (count @items)))))))

(defn- build-result
  "Pi buildResult: {server-name true | false | [direct tool names]} for
   servers whose tool set changed."
  [panel]
  {:cancelled false
   :changes (into {}
                  (keep (fn [server]
                          (when (some (fn [t] (not= (:is-direct t) (:was-direct t)))
                                      (:tools server))
                            (let [direct (filterv :is-direct (:tools server))]
                              [(:name server)
                               (cond
                                 (and (pos? (count direct))
                                      (= (count direct) (count (:tools server)))) true
                                 (empty? direct) false
                                 :else (mapv :name direct))]))))
                  @(:servers-atom panel))})

(defn- toggle-item!
  "Pi toggleItem: server rows toggle every tool, tool rows toggle one."
  [panel item]
  (if (= :server (:type item))
    (let [server (nth @(:servers-atom panel) (:server-index item))
          new-state (not (every? :is-direct (:tools server)))]
      (swap! (:servers-atom panel)
             update-in [(:server-index item) :tools]
             (fn [tools] (mapv #(assoc % :is-direct new-state) tools))))
    (swap! (:servers-atom panel)
           update-in [(:server-index item) :tools (:tool-index item) :is-direct] not))
  (update-dirty! panel))

;; ─── Async actions (pi authenticateServer / reconnectServer) ──────────────

(defn- reconnect-server!
  "Pi reconnectServer: set :connecting, call callbacks.reconnect, refresh
   status/failure/cache (rebuild tools preserving toggles)."
  [panel server & [{:keys [after-auth]}]]
  (when-not (contains? #{:connecting :disabled} (:connection-status server))
    (let [name (:name server)
          server-name (sanitize-display-text name)]
      (when-let [[idx _] (server-by-name panel name)]
        (swap! (:servers-atom panel) update-in [idx :connection-status] (constantly :connecting))
        (tui/tui-request-render (:tui panel))
        (spawn
         (fn []
           (let [connected? (try ((:reconnect (:callbacks panel)) name)
                                 (catch Exception _ false))
                 status ((:get-connection-status (:callbacks panel)) name)]
             (swap! (:servers-atom panel) update-in [idx]
                    (fn [s] (assoc s
                                   :connection-status status
                                   :failure-message ((:get-failure-message (:callbacks panel)) name))))
             (when (= :connected status)
               (when-let [entry ((:refresh-cache-after-reconnect (:callbacks panel)) name)]
                 (rebuild-server-tools! panel idx entry)))
             (when after-auth
               (reset! (:auth-notice-atom panel)
                       (if (and connected? (= :connected status))
                         (str "OAuth finished for " server-name ". Reconnected.")
                         (str "OAuth finished for " server-name
                              ", but reconnect did not complete. Press ctrl+r to retry."))))
             (tui/tui-request-render (:tui panel)))))))))

(defn- authenticate-server!
  "Pi authenticateServer: guard + notice, then callbacks.authenticate (a
   promise); on resolve refresh the status and reconnect on success."
  [panel server]
  (when-not @(:auth-in-flight-atom panel)
    (when-not (contains? #{:connecting :disabled} (:connection-status server))
      (let [name (:name server)
            server-name (sanitize-display-text name)]
        (if-not ((:can-authenticate (:callbacks panel)) name)
          (do (reset! (:auth-notice-atom panel)
                      (str server-name " does not use OAuth authentication."))
              (tui/tui-request-render (:tui panel)))
          (do (reset! (:auth-in-flight-atom panel) name)
              (reset! (:auth-notice-atom panel) (str "Authenticating " server-name "..."))
              (tui/tui-request-render (:tui panel))
              (spawn
               (fn []
                 (let [result (deref ((:authenticate (:callbacks panel)) name))
                       status ((:get-connection-status (:callbacks panel)) name)
                       idx (first (server-by-name panel name))]
                   (when idx
                     (swap! (:servers-atom panel) update-in [idx]
                            (fn [s] (assoc s :connection-status status)))
                     (if (:ok result)
                       (do (reset! (:auth-notice-atom panel)
                                   (str "OAuth finished for " server-name ". Reconnecting..."))
                           (reset! (:auth-in-flight-atom panel) nil)
                           (tui/tui-request-render (:tui panel))
                           (reconnect-server! panel (nth @(:servers-atom panel) idx)
                                              {:after-auth true}))
                       (do (reset! (:auth-notice-atom panel)
                                   (str "OAuth failed for " server-name
                                        (when (seq (:message result))
                                          (str ": " (sanitize-display-text (:message result))))))
                           (reset! (:auth-in-flight-atom panel) nil)
                           (tui/tui-request-render (:tui panel))))))))))))))

(defn- rebuild-server-tools!
  "Pi rebuildServerTools: rebuild a server's tool states from a fresh cache
   entry, preserving is-direct/was-direct for tools that persist."
  [panel idx entry]
  (let [server (nth @(:servers-atom panel) idx)
        existing (into {} (map (fn [t] [(:name t) [(:is-direct t) (:was-direct t)]]))
                       (:tools server))
        new-tools (mapv (fn [tool]
                          (let [[is-direct was-direct] (get existing (:name tool) [false false])]
                            {:name (:name tool)
                             :description (str (or (:description tool) ""))
                             :is-direct is-direct
                             :was-direct was-direct
                             :estimated-tokens (estimate-tokens tool)}))
                        (:tools entry))]
    (swap! (:servers-atom panel) update-in [idx]
           (fn [s] (assoc s :tools new-tools :has-cached-data true)))
    (rebuild-visible! panel)
    (update-dirty! panel)))

;; ─── Inactivity (pi McpPanel.INACTIVITY_MS) ───────────────────────────────

(def ^:private inactivity-ms 60000)

(defn- cancel-inactivity!
  "Cancel the pending inactivity timer. The generation bump stops a
   cancelled (interrupted) timer thread from firing — an interrupt only
   short-circuits the sleep, the thread would otherwise continue into the
   done call and close the panel on the very next input."
  [panel]
  (swap! (:inactivity-gen-atom panel) inc)
  (when-let [t @(:inactivity-timer-atom panel)]
    (.interrupt t)
    (reset! (:inactivity-timer-atom panel) nil)))

(defn- reset-inactivity! [panel]
  (cancel-inactivity! panel)
  (let [gen @(:inactivity-gen-atom panel)
        t (concurrent/spawn
           (fn []
             (try (Thread/sleep inactivity-ms)
                  (catch InterruptedException _ nil))
             (when (= gen @(:inactivity-gen-atom panel))
               (try ((:done-fn panel) {:cancelled true :changes {}})
                    (catch Exception _ nil)))))]
    (reset! (:inactivity-timer-atom panel) t)))

(defn- finish!
  [panel result]
  (cancel-inactivity! panel)
  ((:done-fn panel) result))

(defn- done-cancel! [panel]
  (finish! panel {:cancelled true :changes {}}))

;; ─── Row rendering (pi render) ────────────────────────────────────────────

(defn- row [t content inner-width]
  (str (fg (:border t) "│")
       (u/truncate-to-width (str " " content) inner-width "…" true)
       (fg (:border t) "│")))

(defn- empty-row [t inner-width]
  (str (fg (:border t) "│") (apply str (repeat inner-width \space)) (fg (:border t) "│")))

(defn- divider [t inner-width]
  (str (fg (:border t) "├") (apply str (repeat inner-width "─")) (fg (:border t) "┤")))

(defn- render-connection-status [panel server]
  (let [t panel-theme]
    (cond
      (= @(:auth-in-flight-atom panel) (:name server))
      (str " " (fg (:needs-auth t) "authenticating"))

      (= :disabled (:connection-status server))
      (str " " (fg (:description t) "disabled"))

      (= :needs-auth (:connection-status server))
      (str " " (fg (:needs-auth t) "needs auth"))

      (= :connecting (:connection-status server))
      (str " " (fg (:needs-auth t) "connecting"))

      (= :failed (:connection-status server))
      (str " " (fg (:cancel t) "failed"))

      :else "")))

(defn- render-server-row [panel server is-cursor]
  (let [t panel-theme
        expand-icon (if (:expanded server) "▾" "▸")
        prefix (if is-cursor
                 (fg (:selected t) expand-icon)
                 (fg (:border t) (if (:expanded server) expand-icon "·")))
        name-str (sanitize-display-text (:name server))
        name-str (if is-cursor (fg (:selected t) (bold name-str)) name-str)
        status-label (render-connection-status panel server)]
    (if-not (:has-cached-data server)
      (str prefix " " name-str " " (fg (:description t) "(not cached)") status-label)
      (let [direct-count (count (filter :is-direct (:tools server)))
            total-count (count (:tools server))
            toggle-icon (cond
                          (and (= direct-count total-count) (pos? total-count))
                          (fg (:direct t) "●")
                          (pos? direct-count) (fg (:needs-auth t) "◐")
                          :else (fg (:description t) "○"))
            tool-info (when (pos? total-count)
                        (let [tokens (reduce + (map :estimated-tokens
                                                    (filter :is-direct (:tools server))))]
                          (fg (:description t)
                              (str direct-count "/" total-count
                                   (when (pos? direct-count)
                                     (str " ~" (grouped-number tokens)))))))]
        (str prefix " " toggle-icon " " name-str
             (when tool-info (str " " tool-info))
             status-label)))))

(defn- render-tool-row [tool is-cursor inner-width]
  (let [t panel-theme
        toggle-icon (if (:is-direct tool) (fg (:direct t) "●") (fg (:description t) "○"))
        cursor (if is-cursor (fg (:selected t) "▸") " ")
        name-str (sanitize-display-text (:name tool))
        name-str (if is-cursor (fg (:selected t) (bold name-str)) name-str)
        prefix-len (+ 7 (u/visible-width name-str))
        max-desc-len (max 0 (- inner-width prefix-len 8))
        desc-str (when (and (> max-desc-len 5) (seq (:description tool)))
                   (fg (:description t)
                       (str "— " (u/truncate-to-width (sanitize-display-text (:description tool))
                                                      max-desc-len "…"))))]
    (str " " cursor " " toggle-icon " " name-str (when desc-str (str " " desc-str)))))

;; ─── McpPanel ─────────────────────────────────────────────────────────────

(def ^:private max-visible 12)

(defcomponent McpPanel nil
              [servers-atom cursor-atom name-query-atom desc-active-atom desc-query-atom
               dirty-atom confirming-atom discard-selected-atom auth-notice-atom
               auth-in-flight-atom visible-atom inactivity-timer-atom notice-lines-atom
               callbacks tui done-fn keys-manager]
  (render [this width]
    (let [t panel-theme
          inner-w (max 1 (- width 2))
          title-text " MCP Servers "
          border-len (- inner-w (u/visible-width title-text))
          left-b (quot border-len 2)
          right-b (- border-len left-b)
          lines (volatile! [])]
      (vswap! lines conj
              (str (fg (:border t) (str "╭" (apply str (repeat left-b "─"))))
                   (fg (:title t) title-text)
                   (fg (:border t) (str (apply str (repeat right-b "─")) "╮"))))
      (vswap! lines conj (empty-row t inner-w))
      ;; search row (pi: ◎ icon + query / italic placeholder)
      (let [search-icon (fg (:border t) "◎")]
        (if @(:desc-active-atom this)
          (vswap! lines conj
                  (row t (str search-icon " " (fg (:needs-auth t) "desc:")
                              " " @(:desc-query-atom this) (fg (:selected t) "│"))
                       inner-w))
          (if (seq @(:name-query-atom this))
            (vswap! lines conj
                    (row t (str search-icon " " @(:name-query-atom this)
                                (fg (:selected t) "│"))
                         inner-w))
            (vswap! lines conj
                    (row t (str search-icon " " (fg (:placeholder t) (italic "search...")))
                         inner-w)))))
      (vswap! lines conj (empty-row t inner-w))
      (doseq [notice @(:notice-lines-atom this)]
        (vswap! lines conj (row t (fg (:hint t) (italic (sanitize-display-text notice))) inner-w)))
      (when (seq @(:notice-lines-atom this))
        (vswap! lines conj (empty-row t inner-w)))
      (vswap! lines conj (divider t inner-w))
      (if (empty? @(:servers-atom this))
        (do (vswap! lines conj (empty-row t inner-w))
            (vswap! lines conj (row t (fg (:hint t) (italic "No MCP servers configured.")) inner-w))
            (vswap! lines conj (empty-row t inner-w)))
        (let [items @(:visible-atom this)
              total (count items)
              cursor @(:cursor-atom this)
              start-idx (max 0 (min (- cursor (quot max-visible 2)) (- total max-visible)))
              end-idx (min (+ start-idx max-visible) total)]
          (vswap! lines conj (empty-row t inner-w))
          (doseq [i (range start-idx end-idx)]
            (let [item (nth items i)
                  is-cursor (= i cursor)
                  server (nth @(:servers-atom this) (:server-index item))]
              (if (= :server (:type item))
                (do (vswap! lines conj (row t (render-server-row this server is-cursor) inner-w))
                    (when (and is-cursor
                               (= :failed (:connection-status server))
                               (:failure-message server))
                      (doseq [fl (u/wrap-text-with-ansi
                                  (sanitize-display-text (:failure-message server))
                                  (max 8 (- inner-w 6)))]
                        (vswap! lines conj (row t (str " " (fg (:cancel t) fl)) inner-w)))))
                (when-let [tool (get-in @(:servers-atom this)
                                        [(:server-index item) :tools (:tool-index item)])]
                  (vswap! lines conj (row t (render-tool-row tool is-cursor inner-w) inner-w))))))
          (vswap! lines conj (empty-row t inner-w))
          (when (> total max-visible)
            (let [prog (Math/round (double (* (/ (inc cursor) total) 10)))]
              (vswap! lines conj
                      (row t (str (rainbow-progress prog 10) " "
                                  (fg (:hint t) (str (inc cursor) "/" total)))
                           inner-w))
              (vswap! lines conj (empty-row t inner-w))))
          (when-let [notice @(:auth-notice-atom this)]
            (vswap! lines conj (row t (fg (:needs-auth t) (italic (sanitize-display-text notice))) inner-w))
            (vswap! lines conj (empty-row t inner-w)))))
      (vswap! lines conj (divider t inner-w))
      (vswap! lines conj (empty-row t inner-w))
      (if @(:confirming-atom this)
        (let [discard-btn (if (zero? @(:discard-selected-atom this))
                            (inverse (bold (fg (:cancel t) " Discard ")))
                            (fg (:hint t) " Discard "))
              keep-btn (if (= 1 @(:discard-selected-atom this))
                         (inverse (bold (fg (:confirm t) " Keep & Close ")))
                         (fg (:hint t) " Keep & Close "))]
          (vswap! lines conj
                  (row t (str "Discard unsaved changes? " discard-btn " " keep-btn) inner-w)))
        (let [direct-count (reduce + (map (fn [s] (count (filter :is-direct (:tools s))))
                                          @(:servers-atom this)))
              total-tokens (reduce + (map (fn [s]
                                            (reduce + (map :estimated-tokens
                                                           (filter :is-direct (:tools s)))))
                                          @(:servers-atom this)))
              stats (if (pos? direct-count)
                      (str direct-count " direct ~" (grouped-number total-tokens) " tokens")
                      "no direct tools")]
          (vswap! lines conj
                  (row t (fg (:description t)
                             (str stats
                                  (when @(:dirty-atom this)
                                    (fg (:needs-auth t) " (unsaved)"))))
                       inner-w))))
      (vswap! lines conj (empty-row t inner-w))
      ;; key hints, wrapped (pi render hints)
      (let [save-label (first (save-keys this))
            hints (into [(str (italic "↑↓") " navigate")
                         (str (italic "space") " toggle")
                         (str (italic "⏎") " expand/auth")
                         (str (italic "ctrl+a") " auth")
                         (str (italic "ctrl+r") " reconnect")]
                        (cond-> []
                          (selected-failure? this) (conj (str (italic "ctrl+y") " copy error"))
                          true (conj (str (italic "?") " desc search"))
                          (seq save-label) (conj (str (italic save-label) " save"))
                          true (conj (str (italic "esc") " clear/close"))
                          true (conj (str (italic "ctrl+c") " quit"))))
            gap " "
            gap-w 2
            max-w (- inner-w 2)]
        (loop [hs hints, cur-line "", cur-w 0]
          (if (empty? hs)
            (when (seq cur-line)
              (vswap! lines conj (row t (fg (:hint t) cur-line) inner-w)))
            (let [h (first hs)
                  hw (u/visible-width h)
                  needed (if (zero? cur-w) hw (+ gap-w hw))]
              (if (and (pos? cur-w) (> (+ cur-w needed) max-w))
                (do (vswap! lines conj (row t (fg (:hint t) cur-line) inner-w))
                    (recur (rest hs) h hw))
                (recur (rest hs) (str cur-line (if (pos? cur-w) gap "") h)
                       (+ cur-w needed)))))))
      (vswap! lines conj
              (fg (:border t) (str "╰" (apply str (repeat inner-w "─")) "╯")))
      @lines))

  (handle-input [this data]
    (reset-inactivity! this)
    (when-not @(:auth-in-flight-atom this)
      (reset! (:auth-notice-atom this) nil))
    (if @(:confirming-atom this)
      (handle-discard-input this data)
      (cond
        (keys/matches-key? data "ctrl+c") (done-cancel! this)

        (save-match? this data) (finish! this (build-result this))

        ;; description search mode
        @(:desc-active-atom this)
        (cond
          (or (keys/matches-key? data "escape") (select-confirm? this data))
          (do (reset! (:desc-active-atom this) false)
              (reset! (:desc-query-atom this) "")
              (rebuild-visible! this))

          (keys/matches-key? data "backspace")
          (when (seq @(:desc-query-atom this))
            (swap! (:desc-query-atom this) #(subs % 0 (max 0 (dec (count %)))))
            (rebuild-visible! this))

          (select-up? this data) (move-cursor! this -1)
          (select-down? this data) (move-cursor! this 1)

          (keys/matches-key? data "space")
          (when-let [item (selected-item this)] (toggle-item! this item))

          (printable-char? data)
          (do (swap! (:desc-query-atom this) str data)
              (rebuild-visible! this))

          :else nil)

        (keys/matches-key? data "escape")
        (cond
          (seq @(:name-query-atom this))
          (do (reset! (:name-query-atom this) "")
              (rebuild-visible! this))

          @(:dirty-atom this)
          (do (reset! (:confirming-atom this) true)
              (reset! (:discard-selected-atom this) 1))

          :else (done-cancel! this))

        (select-up? this data) (move-cursor! this -1)
        (select-down? this data) (move-cursor! this 1)

        (keys/matches-key? data "space")
        (when-let [item (selected-item this)]
          (toggle-item! this item))

        (select-confirm? this data)
        (when-let [item (selected-item this)]
          (let [server (nth @(:servers-atom this) (:server-index item))]
            (if (= :server (:type item))
              (cond
                (= :disabled (:connection-status server)) nil
                (= :needs-auth (:connection-status server)) (authenticate-server! this server)
                :else (do (swap! (:servers-atom this)
                                 update-in [(:server-index item) :expanded] not)
                          (rebuild-visible! this)))
              (do (swap! (:servers-atom this)
                         update-in [(:server-index item) :tools (:tool-index item) :is-direct] not)
                  (update-dirty! this)))))

        (keys/matches-key? data "ctrl+a")
        (when-let [server (selected-server this)]
          (authenticate-server! this server))

        (keys/matches-key? data "ctrl+r")
        (when-let [server (selected-server this)]
          (reconnect-server! this server))

        (keys/matches-key? data "ctrl+y")
        (when-let [server (selected-server this)]
          (when (and (= :failed (:connection-status server)) (:failure-message server))
            (reset! (:auth-notice-atom this)
                    (if (clipboard/copy-text! (:failure-message server))
                      (str "Copied error for " (sanitize-display-text (:name server)) " to clipboard")
                      (str "Failed to copy error for " (sanitize-display-text (:name server)))))
            (tui/tui-request-render (:tui this))))

        (= data "?")
        (do (reset! (:desc-active-atom this) true)
            (reset! (:desc-query-atom this) "")
            (rebuild-visible! this))

        (keys/matches-key? data "backspace")
        (when (seq @(:name-query-atom this))
          (swap! (:name-query-atom this) #(subs % 0 (max 0 (dec (count %)))))
          (rebuild-visible! this))

        (printable-char? data)
        (do (swap! (:name-query-atom this) str data)
            (rebuild-visible! this))

        :else nil))
    nil)

  (invalidate [_this] nil))

(defn- handle-discard-input
  "Pi handleDiscardInput: y/enter-discard cancels, n/escape returns, enter
   keeps+closes, left/right/tab cycles the selection."
  [panel data]
  (cond
    (keys/matches-key? data "ctrl+c") (done-cancel! panel)

    (or (keys/matches-key? data "escape") (= data "n") (= data "N"))
    (reset! (:confirming-atom panel) false)

    (select-confirm? panel data)
    (finish! panel (if (zero? @(:discard-selected-atom panel))
                     {:cancelled true :changes {}}
                     (build-result panel)))

    (or (= data "y") (= data "Y")) (done-cancel! panel)

    (or (keys/matches-key? data "left")
        (keys/matches-key? data "right")
        (keys/matches-key? data "tab"))
    (swap! (:discard-selected-atom panel) #(if (zero? %) 1 0))

    :else nil))

(defn make-mcp-panel
  "Create the McpPanel component (pi createMcpPanel). CONFIG — merged MCP
   config {:mcp-servers ... :settings ...}; CACHE — metadata cache map;
   CALLBACKS — {:reconnect (fn [name] -> connected?) :can-authenticate
   (fn [name] -> bool) :authenticate (fn [name] -> promise of {:ok bool
   :message str}) :get-connection-status (fn [name] -> keyword)
   :get-failure-message (fn [name] -> str|nil)
   :refresh-cache-after-reconnect (fn [name] -> entry|nil)}; TUI — the tui
   record (factory arg); DONE — (fn [result]) with result {:cancelled bool
   :changes {server true|false|[names]}}; KB — the keybindings manager
   (factory arg). Registers the user-overridable mcp.panel.save keybinding
   (default ctrl+s) — idempotent."
  [config cache callbacks tui done kb]
  (kb/register-definition! kb "mcp.panel.save"
                           {:default-keys ["ctrl+s"]
                            :description "Save MCP panel changes"})
  (let [panel (map->McpPanel
               {:servers-atom (atom (build-servers config cache callbacks))
                :cursor-atom (atom 0)
                :name-query-atom (atom "")
                :desc-active-atom (atom false)
                :desc-query-atom (atom "")
                :dirty-atom (atom false)
                :confirming-atom (atom false)
                :discard-selected-atom (atom 1)
                :auth-notice-atom (atom nil)
                :auth-in-flight-atom (atom nil)
                :visible-atom (atom [])
                :inactivity-timer-atom (atom nil)
                :inactivity-gen-atom (atom 0)
                :notice-lines-atom (atom [])
                :callbacks callbacks
                :tui tui
                :done-fn done
                :keys-manager kb})]
    (rebuild-visible! panel)
    (reset-inactivity! panel)
    panel))

;; ─── TextDialog (multi-line search/list output; the flash is one line) ────

(def ^:private text-dialog-max-lines 12)

(defcomponent TextDialog nil [title content-atom scroll-top-atom wrapped-atom close-fn]
  (render [this width]
    (let [t panel-theme
          inner-w (max 1 (- width 2))
          title-text (str " " (or (:title this) "MCP") " ")
          border-len (- inner-w (u/visible-width title-text))
          left-b (quot border-len 2)
          right-b (- border-len left-b)
          content (or @content-atom "")
          wrapped (u/wrap-text-with-ansi content (max 1 (- inner-w 2)))
          n (count wrapped)
          max-lines (min text-dialog-max-lines n)
          top (min @scroll-top-atom (max 0 (- n max-lines)))
          _ (reset! wrapped-atom {:count n :max-lines max-lines})
          view (subvec wrapped top (+ top max-lines))
          lines (volatile! [])]
      (vswap! lines conj
              (str (fg (:border t) (str "╭" (apply str (repeat left-b "─"))))
                   (fg (:title t) title-text)
                   (fg (:border t) (str (apply str (repeat right-b "─")) "╮"))))
      (vswap! lines conj (empty-row t inner-w))
      (doseq [line view]
        (vswap! lines conj (row t line inner-w)))
      (when (> n max-lines)
        (vswap! lines conj
                (row t (fg (:hint t) (str "  (" (inc top) "-" (+ top max-lines) "/" n ")"))
                     inner-w)))
      (vswap! lines conj (empty-row t inner-w))
      (vswap! lines conj
              (row t (fg (:hint t) (str (italic "↑↓") " scroll · " (italic "esc") " close"))
                   inner-w))
      (vswap! lines conj
              (fg (:border t) (str "╰" (apply str (repeat inner-w "─")) "╯")))
      @lines))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          {:keys [count max-lines]} @(:wrapped-atom this)
          top @(:scroll-top-atom this)
          max-top (max 0 (- count max-lines))
          scroll-to! (fn [t] (reset! (:scroll-top-atom this) (max 0 (min t max-top))))]
      (cond
        (or (kb/matches-key kmgr data "tui.select.cancel")
            (kb/matches-key kmgr data "tui.select.confirm"))
        (do (when-let [cb @(:close-fn this)] (cb)) nil)

        (or (keys/matches-key? data "down") (keys/matches-key? data (keys/ctrl "n")))
        (do (scroll-to! (inc top)) nil)

        (or (keys/matches-key? data "up") (keys/matches-key? data (keys/ctrl "p")))
        (do (scroll-to! (dec top)) nil)

        (keys/matches-key? data (keys/shift "pageDown"))
        (do (scroll-to! (+ top (max 1 max-lines))) nil)

        (keys/matches-key? data (keys/shift "pageUp"))
        (do (scroll-to! (- top (max 1 max-lines))) nil)

        (keys/matches-key? data "home") (do (scroll-to! 0) nil)
        (keys/matches-key? data "end") (do (scroll-to! max-top) nil)
        :else nil)))

  (invalidate [_this] nil))

(defn make-text-dialog
  "Scrollable text dialog for multi-line command output (search/list).
   `close` is the host ui-custom close callback (fn [result] ...) —
   TextDialog invokes it with no args, so adapt here (the result value
   is unused by the extension)."
  [title content close]
  (map->TextDialog {:title title
                    :content-atom (atom (or content ""))
                    :scroll-top-atom (atom 0)
                    :wrapped-atom (atom {:count 0 :max-lines 0})
                    :close-fn (atom (fn [] (close nil)))}))

;; ─── OAuth prompt dialog (pi AuthPrompt → LoginDialog) ────────────────────

(defn make-prompt-dialog
  "One-line input dialog for the OAuth flow's :prompt, built from kmet.tui
   components (replaces the removed host ui-input capability). Returns a
   duck-typed component; ON-SUBMIT receives the entered string, ON-CANCEL
   fires on escape."
  [th message on-submit on-cancel]
  (let [inp (input/make-input)
        _ (input/input-set-on-submit! inp on-submit)
        _ (input/input-set-on-escape! inp on-cancel)
        ;; declarative scaffolding around the live input record —
        ;; compiled once here; dispose-tree! unwinds it with the dialog
        comp (h/compile-tree
              [:container {}
               [:text {:padding-x 1 :padding-y 0}
                (theme/fg th :accent (theme/bold "MCP OAuth"))]
               [:spacer {:lines 1}]
               [:text {:padding-x 1 :padding-y 0} message]
               [:spacer {:lines 1}]
               inp])]
    {:render (fn [width] (protocols/render comp width))
     :handle-input (fn [data] (protocols/handle-input inp data))
     :invalidate (fn [] (protocols/invalidate comp))
     :dispose #(h/dispose-tree! comp)}))

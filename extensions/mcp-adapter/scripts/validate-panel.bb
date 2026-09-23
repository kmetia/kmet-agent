#!/usr/bin/env bb
;; Panel validation for the mcp-adapter extension (§12.7): McpPanel render
;; (pi mcp-panel.ts port), navigation/expand/toggle keys, discard confirm,
;; ctrl+s save result, name search, TextDialog scrolling, and the OAuth
;; prompt dialog. Headless — runs the components directly, no TUI.
(require '[clojure.string :as str]
         '[kmet.tui.core :as core]
         '[kmet.tui.keybindings :as kb]
         '[kmet.tui.theme :as theme]
         '[kmet.extensions.mcp-adapter.metadata :as metadata]
         '[kmet.extensions.mcp-adapter.panel :as panel])

(def failures (atom 0))

(defn check [label ok?]
  (println (if ok? "PASS" "FAIL") label)
  (when-not ok? (swap! failures inc)))

(defn strip [s] (str/replace s #"\x1b\[[0-9;]*m" ""))
(defn render-lines [comp] (mapv strip (core/render comp 82)))
(defn all-same-width? [lines] (= 1 (count (distinct (map count lines)))))

(defn normalize
  "Host normalize-custom-component: duck-typed {:render :handle-input
   :invalidate} maps are wrapped in IComponent (interactive.clj) — the
   mcp-adapter's prompt dialog is duck-typed like tools.clj's."
  [x]
  (if (satisfies? kmet.tui.protocols/IComponent x)
    x
    (reify kmet.tui.protocols/IComponent
      (render [_ width] ((:render x) width))
      (handle-input [_ data] (when-let [f (:handle-input x)] (f data)))
      (invalidate [_] (when-let [f (:invalidate x)] (f))))))

(def settings {:direct-tools false :tool-prefix :server})

(def config
  {:settings settings
   :mcp-servers
   {"exa" {:url "https://mcp.exa.ai" :direct-tools ["web_search_exa"]}
    "filesystem" {:command "npx" :args ["x"]}
    "broken" {:command "nope"}}})

(def cache
  {:version 1
   :servers
   {"exa" {:config-fingerprint (metadata/config-fingerprint
                                "exa" (get-in config [:mcp-servers "exa"]) settings)
           :fetched-at (System/currentTimeMillis)
           :tools [{:name "web_search_exa" :description "Search the web" :inputSchema {}}
                   {:name "web_fetch_exa" :description "Fetch a page" :inputSchema {}}]}
    "filesystem" {:config-fingerprint (metadata/config-fingerprint
                                       "filesystem" (get-in config [:mcp-servers "filesystem"]) settings)
                  :fetched-at (System/currentTimeMillis)
                  :tools [{:name "read_file" :description "Read a file" :inputSchema {}}]}}})

(defn callbacks
  [& {:keys [statuses] :or {statuses {"exa" :idle "filesystem" :idle "broken" :failed}}}]
  {:reconnect (fn [_] false)
   :can-authenticate (fn [name] (= name "exa"))
   :authenticate (fn [_] (let [p (promise)] (deliver p {:ok true :message "ok"}) p))
   :get-connection-status (fn [name] (get statuses name :idle))
   :get-failure-message (fn [name] (when (= name "broken") "spawn failed: no such file"))
   :refresh-cache-after-reconnect (fn [_] nil)})

(defn make-panel [done] (panel/make-mcp-panel config cache (callbacks) nil done
                                              (kb/get-global-keybindings)))

(println "\n── panel render ──")
(let [pan (make-panel (fn [_] nil))
      lines (render-lines pan)]
  (check "title + border box" (some #(str/includes? % "MCP Servers") lines))
  (check "search placeholder" (some #(str/includes? % "◎ search...") lines))
  (check "all rows same width (borders aligned)" (all-same-width? lines))
  (check "failed server row + failure message"
         (and (some #(str/includes? % "broken (not cached) failed") lines)
              (some #(str/includes? % "spawn failed: no such file") lines)))
  (check "partial direct toggle (◐ exa 1/2)"
         (some #(str/includes? % "◐ exa") lines))
  (check "stats + hints" (and (some #(str/includes? % "navigate") lines)
                              (some #(str/includes? % "ctrl+s save") lines))))

(println "\n── navigation / expand ──")
(let [pan (make-panel (fn [_] nil))]
  (core/handle-input pan "\u001b[B")      ;; down → exa
  (core/handle-input pan "\r")            ;; expand
  (let [lines (render-lines pan)]
    (check "expanded server row" (some #(str/includes? % "▾ ◐ exa") lines))
    (check "tool rows" (some #(str/includes? % "web_search_exa — Search the web") lines))
    (check "rows still aligned when expanded" (all-same-width? lines))))

(println "\n── toggle → discard confirm → keep & close ──")
(let [done (atom nil)
      pan (make-panel (fn [r] (reset! done r)))]
  (core/handle-input pan "\u001b[B")      ;; exa
  (core/handle-input pan " ")             ;; toggle all → dirty
  (core/handle-input pan "\u001b")        ;; discard confirm
  (check "discard confirm shown"
         (some #(str/includes? % "Discard unsaved changes?") (render-lines pan)))
  (core/handle-input pan "\r")            ;; Keep & Close
  (let [result @done]
    (check "keep & close returns changes"
           (and (= false (:cancelled result)) (= true (get (:changes result) "exa"))))))

(println "\n── discard (y) cancels ──")
(let [done (atom nil)
      pan (make-panel (fn [r] (reset! done r)))]
  ;; cursor is on exa; toggle all → dirty; escape → confirm; y → discard
  (core/handle-input pan " ")
  (core/handle-input pan "\u001b")
  (core/handle-input pan "y")
  (check "discard cancels" (true? (:cancelled @done))))

(println "\n── ctrl+s save ──")
(let [done (atom nil)
      pan (make-panel (fn [r] (reset! done r)))]
  (core/handle-input pan "\u001b[B")
  (core/handle-input pan " ")
  (core/handle-input pan "\u0013")        ;; ctrl+s
  (let [result @done]
    (check "save closes with changes"
           (and (= false (:cancelled result)) (contains? (:changes result) "exa")))))

(println "\n── name search ──")
(let [pan (make-panel (fn [_] nil))]
  (core/handle-input pan "f")
  (core/handle-input pan "i")             ;; "fi" → only filesystem's read_file
  (let [lines (render-lines pan)]
    (check "query shown" (some #(str/includes? % "◎ fi") lines))
    (check "non-matches filtered" (not-any? #(str/includes? % "exa") lines))))

(println "\n── TextDialog ──")
(let [closed (atom 0)
      ;; the host ui-custom close is (fn [result] ...) — the real-contract
      ;; regression guard: TextDialog must adapt, not pass its 0-arg call
      ;; through (caught in the TUI smoke)
      dlg (panel/make-text-dialog "MCP search"
                                  (apply str (map #(str "line-" % "\n") (range 30)))
                                  (fn [_result] (swap! closed inc)))
      lines (mapv strip (core/render dlg 60))]
  (check "bordered, windowed view" (and (some #(str/includes? % "MCP search") lines)
                                        (< (count lines) 20)))
  (check "rows aligned" (all-same-width? lines))
  (core/handle-input dlg "\u001b[B")      ;; down
  (let [lines2 (mapv strip (core/render dlg 60))]
    (check "scrolled down" (some #(str/includes? % "line-1") lines2)))
  (core/handle-input dlg "\u001b")        ;; escape
  (check "escape closes" (= 1 @closed)))

(println "\n── OAuth prompt dialog ──")
(let [submitted (atom nil)
      comp (normalize (panel/make-prompt-dialog theme/dark-theme "Enter token"
                                                (fn [v] (reset! submitted v)) (fn [])))
      rendered (mapv strip (core/render comp 40))]
  (check "renders title" (some #(str/includes? % "MCP OAuth") rendered))
  (core/handle-input comp "\r")
  (check "submit fires" (= "" @submitted)))

(println "\n── inactivity timer ──")
(let [done (atom nil)
      pan (make-panel (fn [r] (reset! done r)))
      timer @(:inactivity-timer-atom pan)]
  (.interrupt ^Thread timer)              ;; regression: interrupt short-circuits sleep
  (core/handle-input pan "\u001b[B")      ;; resets the timer (interrupts old + bumps gen)
  (Thread/sleep 150)
  (check "cancelled timer does not close the panel" (nil? @done)))

(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))

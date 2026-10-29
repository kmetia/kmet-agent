(ns kmet.extensions.mcp-adapter.panel-test
  "The McpPanel (§12.7, pi mcp-panel.ts port) and its dialogs: render,
   navigation/expand, the direct-tools toggle with its discard confirm,
   ctrl+s save, name search, TextDialog scrolling and the OAuth prompt.
   Headless — the components run directly, no TUI."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.panel :as panel]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Fixtures ─────────────────────────────────────────────────────────────

(def ^:private settings {:direct-tools false :tool-prefix :server})

(def ^:private config
  {:settings settings
   :mcp-servers
   {"exa" {:url "https://mcp.exa.ai" :direct-tools ["web_search_exa"]}
    "filesystem" {:command "npx" :args ["x"]}
    "broken" {:command "nope"}}})

(def ^:private cache
  {:version 1
   :servers
   {"exa" {:config-fingerprint (metadata/config-fingerprint
                                "exa" (get-in config [:mcp-servers "exa"]) settings)
           :fetched-at (System/currentTimeMillis)
           :tools [{:name "web_search_exa" :description "Search the web" :inputSchema {}}
                   {:name "web_fetch_exa" :description "Fetch a page" :inputSchema {}}]}
    "filesystem" {:config-fingerprint (metadata/config-fingerprint
                                       "filesystem" (get-in config [:mcp-servers "filesystem"])
                                       settings)
                  :fetched-at (System/currentTimeMillis)
                  :tools [{:name "read_file" :description "Read a file" :inputSchema {}}]}}})

(defn- callbacks [& {:keys [statuses]
                     :or {statuses {"exa" :idle "filesystem" :idle "broken" :failed}}}]
  {:reconnect (fn [_] false)
   :can-authenticate (fn [name] (= name "exa"))
   :authenticate (fn [_] (let [p (promise)] (deliver p {:ok true :message "ok"}) p))
   :get-connection-status (fn [name] (get statuses name :idle))
   :get-failure-message (fn [name] (when (= name "broken") "spawn failed: no such file"))
   :refresh-cache-after-reconnect (fn [_] nil)})

(defn- make-panel [done]
  (panel/make-mcp-panel config cache (callbacks) nil done
                        (kb/get-global-keybindings)))

(defn- render-lines [comp]
  (tu/render-lines comp 82))

(defn- press! [comp & keys]
  (doseq [k keys] (core/handle-input comp k)))

(defn- normalize
  "The host's normalize-custom-component: a duck-typed {:render
   :handle-input :invalidate} map is wrapped in IComponent."
  [x]
  (if (satisfies? protocols/IComponent x)
    x
    (reify protocols/IComponent
      (render [_ width] ((:render x) width))
      (handle-input [_ data] (when-let [f (:handle-input x)] (f data)))
      (invalidate [_] (when-let [f (:invalidate x)] (f)))
      (dispose [_] nil))))

;; ─── Render ───────────────────────────────────────────────────────────────

(deftest panel-render
  (let [lines (render-lines (make-panel (fn [_] nil)))]
    (testing "the frame carries the title, the search box and the hints"
      (is (some #(str/includes? % "MCP Servers") lines))
      (is (some #(str/includes? % "◎ search...") lines))
      (is (some #(str/includes? % "navigate") lines))
      (is (some #(str/includes? % "ctrl+s save") lines)))
    (testing "every row is the same width (borders aligned)"
      (is (tu/all-same-width? lines)))
    (testing "a failed server shows its state and the failure message"
      (is (some #(str/includes? % "broken (not cached) failed") lines))
      (is (some #(str/includes? % "spawn failed: no such file") lines)))
    (testing "a partially selected direct-tools set renders its count"
      (is (some #(str/includes? % "◐ exa") lines)))))

;; ─── Navigation and expand ────────────────────────────────────────────────

(deftest panel-navigation
  (let [pan (make-panel (fn [_] nil))]
    (press! pan "\u001b[B" "\r")
    (let [lines (render-lines pan)]
      (testing "the expanded row gets the disclosure marker"
        (is (some #(str/includes? % "▾ ◐ exa") lines)))
      (testing "its tools are listed with their descriptions"
        (is (some #(str/includes? % "web_search_exa — Search the web") lines)))
      (testing "expanding keeps the rows aligned"
        (is (tu/all-same-width? lines))))))

;; ─── Toggle + discard confirm ─────────────────────────────────────────────

(deftest panel-toggle-confirm-keep
  (let [done (atom nil)
        pan (make-panel (fn [r] (reset! done r)))]
    (press! pan "\u001b[B" " ")
    (testing "a dirty toggle asks before discarding"
      (press! pan "\u001b")
      (is (some #(str/includes? % "Discard unsaved changes?") (render-lines pan))))
    (testing "Keep & Close returns the pending changes"
      (press! pan "\r")
      (is (= false (:cancelled @done)))
      (is (true? (get (:changes @done) "exa"))))))

(deftest panel-discard-cancels
  (let [done (atom nil)
        pan (make-panel (fn [r] (reset! done r)))]
    (press! pan " " "\u001b" "y")
    (is (true? (:cancelled @done)))))

(deftest panel-ctrl-s-saves
  (let [done (atom nil)
        pan (make-panel (fn [r] (reset! done r)))]
    (press! pan "\u001b[B" " " "\u0013")
    (is (= false (:cancelled @done)))
    (is (contains? (:changes @done) "exa"))))

;; ─── Search ───────────────────────────────────────────────────────────────

(deftest panel-name-search
  (let [pan (make-panel (fn [_] nil))]
    (press! pan "f" "i")
    (let [lines (render-lines pan)]
      (testing "the query is shown in the search box"
        (is (some #(str/includes? % "◎ fi") lines)))
      (testing "non-matching servers are filtered out"
        (is (not-any? #(str/includes? % "exa") lines))))))

;; ─── Dialogs ──────────────────────────────────────────────────────────────

(deftest text-dialog
  (let [closed (atom 0)
        dialog (panel/make-text-dialog "MCP search"
                                       (apply str (map #(str "line-" % "\n") (range 30)))
                                       (fn [_result] (swap! closed inc)))
        lines (mapv tu/strip (core/render dialog 60))]
    (testing "the dialog is bordered and windowed, not 30 lines tall"
      (is (some #(str/includes? % "MCP search") lines))
      (is (< (count lines) 20))
      (is (tu/all-same-width? lines)))
    (testing "down scrolls the body"
      (core/handle-input dialog "\u001b[B")
      (is (some #(str/includes? % "line-1") (mapv tu/strip (core/render dialog 60)))))
    (testing "escape closes through the host's 1-arg close contract"
      (core/handle-input dialog "\u001b")
      (is (= 1 @closed)))))

(deftest oauth-prompt-dialog
  (let [submitted (atom nil)
        comp (normalize (panel/make-prompt-dialog theme/dark-theme "Enter token"
                                                  (fn [v] (reset! submitted v)) (fn [])))]
    (is (some #(str/includes? % "MCP OAuth") (mapv tu/strip (core/render comp 40))))
    (core/handle-input comp "\r")
    (is (= "" @submitted))))

;; ─── Inactivity timer ─────────────────────────────────────────────────────

(deftest ^:slow inactivity-timer-restarts-without-closing
  (let [done (atom nil)
        pan (make-panel (fn [r] (reset! done r)))
        timer @(:inactivity-timer-atom pan)]
    ;; an interrupt must short-circuit the timer's sleep, not end the panel
    (.interrupt ^Thread timer)
    (core/handle-input pan "\u001b[B")
    (Thread/sleep 150)
    (is (nil? @done))))

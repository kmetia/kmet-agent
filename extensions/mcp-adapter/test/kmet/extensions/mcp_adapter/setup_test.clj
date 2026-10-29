(ns kmet.extensions.mcp-adapter.setup-test
  "The setup panel (pi mcp-setup-panel.ts): the action list, the preset
   list, host-config import with its toggle set, the custom-server form's
   entry building, the notices, and Esc closing through the host's DONE
   callback."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tui.keybindings :as kb]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.setup :as setup]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Fixtures ─────────────────────────────────────────────────────────────

(defn- callbacks
  "Callbacks recording what the panel asked for. OVERRIDES wins."
  [calls & [overrides]]
  (merge {:presets (mapv (fn [p] (select-keys p [:id :name :summary]))
                         config/known-server-presets)
          :discover-imports (fn [] [{:kind :cursor :path "/h/.cursor/mcp.json"
                                     :server-count 2}
                                    {:kind :vscode :path "/p/.vscode/mcp.json"
                                     :server-count 1}])
          :add-known (fn [preset]
                       (swap! calls conj [:add-known (:id preset)])
                       {:ok true :message (str "Added " (:id preset))})
          :add-server (fn [name entry]
                        (swap! calls conj [:add-server name entry])
                        {:ok true :message (str "Saved " name)})
          :adopt-imports (fn [kinds]
                           (swap! calls conj [:adopt-imports kinds])
                           {:ok true :message "Adopted 2 servers"})
          :scaffold (fn []
                      (swap! calls conj [:scaffold])
                      {:ok true :message "Created /p/.kmet/mcp.edn"})}
         overrides))

(defn- panel [calls done & [overrides]]
  (setup/make-setup-panel (callbacks calls overrides) done
                          (kb/get-global-keybindings)))

;; The setup panel is duck-typed ({:render :handle-input :invalidate}) —
;; the host's normalize-custom-component wraps it; a test calls the map's
;; own functions.

(defn- press! [comp & keys]
  (doseq [k keys] ((:handle-input comp) k))
  comp)

(defn- screen-text [comp]
  (str/join "\n" (mapv tu/strip ((:render comp) 82))))

(defn- type! [comp s]
  (doseq [ch (seq s)] ((:handle-input comp) (str ch)))
  comp)

;; ─── Actions screen ───────────────────────────────────────────────────────

(deftest actions-screen-render
  (let [out (screen-text (panel (atom []) (fn [_] nil)))]
    (testing "the frame, the actions and the hints are rendered"
      (is (str/includes? out "MCP setup"))
      (is (str/includes? out "Add a known server"))
      (is (str/includes? out "Add a custom server"))
      (is (str/includes? out "Import host configs"))
      (is (str/includes? out "Scaffold project config"))
      (is (str/includes? out "Enter selects · Esc closes")))))

(deftest scaffold-from-the-actions-screen
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\u001b[B" "\u001b[B" "\u001b[B" "\r")
    (testing "the scaffold action runs and reports its message"
      (is (= [[:scaffold]] @calls))
      (is (str/includes? (screen-text comp) "Created /p/.kmet/mcp.edn")))))

;; ─── Presets ──────────────────────────────────────────────────────────────

(deftest add-a-known-server
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\r")
    (testing "the preset list shows the known servers"
      (let [out (screen-text comp)]
        (is (str/includes? out "DeepWiki"))
        (is (str/includes? out "Enter adds the server · Esc back"))))
    (press! comp "\r")
    (testing "choosing a preset adds it and returns to the actions"
      (is (= [[:add-known "deepwiki"]] @calls))
      (is (str/includes? (screen-text comp) "Added deepwiki")))))

(deftest presets-esc-goes-back
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\r" "\u001b")
    (is (empty? @calls))
    (is (str/includes? (screen-text comp) "Add a known server"))))

;; ─── Host-config import ───────────────────────────────────────────────────

(deftest import-screen-selection
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\u001b[B" "\u001b[B" "\r")
    (testing "discovered host configs are listed with their server counts"
      (let [out (screen-text comp)]
        (is (str/includes? out "cursor — /h/.cursor/mcp.json"))
        (is (str/includes? out "vscode — /p/.vscode/mcp.json"))
        (is (str/includes? out "Space toggles · Enter adopts · Esc back"))))
    (testing "space deselects the highlighted kind"
      (press! comp " ")
      (press! comp "\r"))
    (testing "adoption receives only the still-selected kinds"
      (is (= [[:adopt-imports [:vscode]]] @calls))
      (is (str/includes? (screen-text comp) "Adopted 2 servers")))))

(deftest import-adopts-everything-by-default
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\u001b[B" "\u001b[B" "\r" "\r")
    (is (= [[:adopt-imports [:cursor :vscode]]] @calls))))

;; ─── Custom-server form ───────────────────────────────────────────────────

(deftest form-builds-a-stdio-entry
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\u001b[B" "\r")
    (type! comp "myserver")
    (press! comp "\r")
    (type! comp "npx -y pkg")
    (press! comp "\r")
    (press! comp "\r")
    (press! comp "\r")
    (testing "the command line is split into command + args, auth stays unset"
      (is (= [[:add-server "myserver" {:command "npx" :args ["-y" "pkg"]}]]
             @calls)))
    (testing "a successful save returns to the actions with its message"
      (is (str/includes? (screen-text comp) "Saved myserver")))))

(deftest form-builds-an-http-entry-with-auth
  (let [calls (atom [])
        comp (panel calls (fn [_] nil))]
    (press! comp "\u001b[B" "\r")
    (type! comp "web")
    (press! comp "\r")
    (type! comp "https://h/mcp")
    (press! comp "\r")
    (press! comp "\r")
    ;; the auth row defaults to "none": append "bearer" to it
    (type! comp "bearer")
    (press! comp "\r")
    (testing "a URL entry carries the url and the auth mode"
      (is (= [[:add-server "web" {:url "https://h/mcp" :auth :bearer}]] @calls)))))

(deftest form-validates-required-fields
  (testing "a blank name is refused"
    (let [calls (atom [])
          comp (panel calls (fn [_] nil))]
      (press! comp "\u001b[B" "\r" "\r" "\r" "\r" "\r")
      (is (empty? @calls))
      (is (str/includes? (screen-text comp) "Server name is required."))))
  (testing "a blank command or url is refused"
    (let [calls (atom [])
          comp (panel calls (fn [_] nil))]
      (press! comp "\u001b[B" "\r")
      (type! comp "named")
      (press! comp "\r" "\r" "\r" "\r")
      (is (empty? @calls))
      (is (str/includes? (screen-text comp) "A command or URL is required.")))))

;; ─── Close ────────────────────────────────────────────────────────────────

(deftest escape-closes-the-panel
  (let [done (atom nil)
        comp (panel (atom []) (fn [result] (reset! done result)))]
    (press! comp "\u001b")
    (testing "Esc reports a cancel through DONE so the overlay closes"
      (is (= {:cancelled true} @done)))))

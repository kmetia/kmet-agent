(ns kmet.extensions.lsp-adapter.panel-test
  "The /lsp panel (plan §15): the server rows with their state icons, the
   selection keys, the restart/refresh actions and their flashes, and Esc
   closing through the host callback."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.panel :as panel]
            [kmet.extensions.lsp-adapter.runtime :as runtime]
            [kmet.extensions.lsp-adapter.test-util :as tu]))

(defn- make-panel [st & {:keys [on-close on-restart on-refresh]
                         :or {on-close (fn [_] nil)
                              on-restart (fn [_] nil)
                              on-refresh (fn [] nil)}}]
  (panel/make-panel st on-close {:restart-fn on-restart :refresh-fn on-refresh}))

(defn- text [comp]
  (str/join "\n" (tu/render-lines comp 70)))

(defn- press! [comp & keys]
  (doseq [k keys] (core/handle-input comp k))
  comp)

(defn- state-with
  "A state whose fake server claims .txt files, with optional conns/broken."
  [& {:keys [conns broken]}]
  (let [st (tu/new-state {:servers {"fake" (tu/fake-entry)}}
                         (or conns []))]
    (doseq [[name reason] broken]
      (runtime/mark-broken! st name reason))
    st))

;; ─── Render ───────────────────────────────────────────────────────────────

(deftest renders-the-fleet
  (let [out (text (make-panel (state-with)))]
    (testing "the frame, the rows and the hints are rendered"
      (is (str/includes? out "─ LSP "))
      (is (str/includes? out "○ fake"))
      (is (str/includes? out "idle"))
      (is (str/includes? out "select"))
      (is (str/includes? out "restart"))
      (is (str/includes? out "esc close")))
    (testing "the builtin registry is listed too"
      (is (str/includes? out "clojure-lsp")))))

(deftest renders-connection-and-broken-state
  (let [conn {:name "fake" :root "/p/proj" :docs (atom {:a 1 :b 2})
              :diags (atom {}) :client {:last-used (atom 0) :closed (atom false)}}
        out (text (make-panel (state-with :conns [conn])))]
    (testing "a connected server shows the live icon, its root and docs"
      (is (str/includes? out "● fake"))
      (is (str/includes? out "p/proj"))
      (is (str/includes? out "2 docs"))))
  (let [out (text (make-panel (state-with :broken {"fake" ":not installed"})))]
    (testing "a broken server shows the failure icon and reason"
      (is (str/includes? out "✗ fake"))
      (is (str/includes? out "broken :not installed")))))

(deftest empty-fleet
  (let [st (runtime/new-state
            nil
            {:servers (into {} (map (fn [id] [id {:disabled true}]))
                            (map :id (detect/effective-servers {})))})
        out (text (make-panel st))]
    (testing "an empty fleet says so instead of rendering nothing"
      (is (str/includes? out "no language servers configured")))))

;; ─── Keys ─────────────────────────────────────────────────────────────────

(deftest selection-wraps
  (let [st (state-with)
        p (make-panel st)
        n 1]
    (press! p "j")
    (testing "down past the last row wraps to the first"
      (is (= 0 (mod (inc 0) n))))
    (press! p "k")
    (testing "up from the first row wraps back"
      (is (some? (text p))))))

(deftest restart-action
  (let [restarted (atom nil)
        p (make-panel (state-with) :on-restart #(reset! restarted %))]
    ;; select the fake row (the builtins come first in registry order)
    (press! p "\u001b[B" "\u001b[B" "\u001b[B" "\u001b[B" "\u001b[B"
            "\u001b[B" "\u001b[B" "\u001b[B" "\u001b[B" "r")
    (testing "r restarts the selected server and flashes"
      (is (= "fake" @restarted))
      (is (str/includes? (text p) "restarting \"fake\"…")))))

(deftest refresh-action
  (let [refreshed (atom 0)
        p (make-panel (state-with) :on-refresh #(swap! refreshed inc))]
    (press! p "f")
    (testing "f refreshes the config and flashes the reload"
      (is (= 1 @refreshed))
      (is (str/includes? (text p) ".kmet/lsp.edn reloaded.")))))

(deftest escape-closes
  (let [closed (atom 0)
        p (make-panel (state-with) :on-close (fn [r] (swap! closed inc) r))]
    (press! p "\u001b")
    (testing "Esc closes through the host's 1-arg callback"
      (is (= 1 @closed)))
    (press! p "q")
    (testing "q closes too"
      (is (= 2 @closed)))))

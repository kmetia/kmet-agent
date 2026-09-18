(ns kmet.app.ui.test-dock
  "Dock mount/clear lifecycle (pi: showSelector / disposeActiveSelector):
   displacement disposal, borrowed panels, the staleness gate and done()
   idempotence."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.app.ui.dock :as dock]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.core :as tui]))

(defn- panel
  "A dispose-spy panel: a duck-typed component whose :dispose records the
   label in DISPOSED."
  [disposed label]
  {:label label
   :render (fn [_ _] [])
   :dispose (fn [] (swap! disposed conj label))})

(defn- test-cs
  "A CS stand-in: the dock reads :tui, :dock-current and
   :current-editor-atom."
  []
  {:tui {}
   :dock-current (atom nil)
   :current-editor-atom (atom (editor/make-editor))})

(defn- mount-in [cs component & [focus opts]]
  (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                tui/tui-request-render (fn [_] nil)]
    (apply dock/mount! cs component focus (when opts [opts]))))

(deftest mount-records-focuses-and-restores
  (let [cs (test-cs)
        disposed (atom [])
        focused (atom nil)
        p (panel disposed "a")]
    (with-redefs [tui/tui-set-focus (fn [_ c] (reset! focused c))
                  tui/tui-request-render (fn [_] nil)]
      (let [done (dock/mount! cs p)]
        (t/is (= p (:component @(:dock-current cs))))
        (t/is (= p @focused) "focus defaults to the component")
        (t/is (empty? @disposed))
        (done)
        (t/is (nil? @(:dock-current cs)) "done restores the editor")
        (t/is (= @(:current-editor-atom cs) @focused))
        (done)
        (t/is (nil? @(:dock-current cs)) "done is idempotent")))))

(deftest mount-with-an-explicit-focus-target
  (let [cs (test-cs)
        disposed (atom [])
        focused (atom nil)
        p (panel disposed "frame")
        target (panel disposed "list")]
    (with-redefs [tui/tui-set-focus (fn [_ c] (reset! focused c))
                  tui/tui-request-render (fn [_] nil)]
      (dock/mount! cs p target)
      (t/is (= p (:component @(:dock-current cs))) "the panel is docked")
      (t/is (= target @focused) "the focus target receives keys"))))

(deftest mounting-over-a-panel-disposes-it
  (let [cs (test-cs)
        disposed (atom [])
        a (panel disposed "a")
        b (panel disposed "b")
        c (panel disposed "c")]
    (mount-in cs a)
    (mount-in cs b)
    (t/is (= ["a"] @disposed) "the displaced selector unwinds (pi disposeActiveSelector)")
    (mount-in cs c)
    (t/is (= ["a" "b"] @disposed))))

(deftest borrowed-panels-stay-alive
  ;; the auth dialog a prompt selector temporarily replaces, or an extension
  ;; dialog the registry keeps custody of: the dock must not dispose it
  (let [cs (test-cs)
        disposed (atom [])
        dlg (panel disposed "dialog")
        sel (panel disposed "selector")
        dlg2 (panel disposed "dialog-again")]
    (mount-in cs dlg nil {:borrowed? true})
    (mount-in cs sel)
    (t/is (empty? @disposed) "a selector mount leaves the borrowed dialog alone")
    ;; the dialog is re-mounted by the flow that owns it (pi showAuthSelect)
    (mount-in cs dlg2 nil {:borrowed? true})
    (t/is (= ["selector"] @disposed)
          "a non-borrowed selector is disposed when borrowed chrome takes back the dock")))

(deftest re-mounting-the-same-panel-does-not-dispose-it
  (let [cs (test-cs)
        disposed (atom [])
        p (panel disposed "a")]
    (mount-in cs p)
    (mount-in cs p)
    (t/is (empty? @disposed) "identity is respected (no self-disposal)")))

(deftest clear-takes-the-dock-back
  (let [cs (test-cs)
        disposed (atom [])
        sel (panel disposed "selector")]
    (mount-in cs sel)
    (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                  tui/tui-request-render (fn [_] nil)]
      (dock/clear! cs))
    (t/is (nil? @(:dock-current cs)) "cleared")
    (t/is (= ["selector"] @disposed) "the occupant is disposed")))

(deftest clear-leaves-borrowed-panels-to-their-owner
  (let [cs (test-cs)
        disposed (atom [])
        dlg (panel disposed "dialog")]
    (mount-in cs dlg nil {:borrowed? true})
    (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                  tui/tui-request-render (fn [_] nil)]
      (dock/clear! cs))
    (t/is (nil? @(:dock-current cs)))
    (t/is (empty? @disposed) "borrowed panels are the owner's to dispose")))

(deftest stale-done-and-invalidate-pending
  (let [cs (test-cs)
        disposed (atom [])
        a (panel disposed "a")
        b (panel disposed "b")]
    (let [done-a (mount-in cs a)]
      (mount-in cs b)
      (done-a)
      (t/is (= b (:component @(:dock-current cs)))
            "a stale done() is inert (pi activeSelectorToken)"))
    (let [done-b (mount-in cs b)]
      (dock/invalidate-pending!)
      (done-b)
      (t/is (= b (:component @(:dock-current cs)))
            "invalidate-pending! makes the pending done() inert")
      (t/is (= ["a"] @disposed)
            "disposal comes from displacement, never from a done()"))))

;; ─── Focus integrity (the ::focus-guard watch) ─────────────────────────────
;; A panel that leaves the dock while holding input would swallow every key
;; (nothing on screen reacts) — the "lost focus after close / unresponsive
;; UI" class. The guard, not each close path, is what makes the restore
;; happen: it watches :dock-current like the TUI's ::ghost-guard watches the
;; overlay stack.

(defn- focus-cs
  "A CS with a real TUI, the editor mounted as a root child, and the
   dock-aware focus home the app registers (see interactive.clj)."
  []
  (let [ui (tui/create-tui nil)
        ed (editor/make-editor)
        cs {:tui ui
            :dock-current (atom nil)
            :current-editor-atom (atom ed)}]
    (tui/tui-add-child ui ed)
    (tui/tui-set-focus-home! ui #(or (:component (deref (:dock-current cs)))
                                     (deref (:current-editor-atom cs))))
    {:cs cs :ui ui :ed ed}))

(defn- dispatch! [ui data] ((var tui/dispatch-input!) ui data))

(deftest clear-hands-input-back-to-the-editor
  (testing "a cleared occupant must not keep swallowing keys (ghost focus)"
    (let [{:keys [cs ui ed]} (focus-cs)
          p (panel (atom []) "panel")]
      (dock/mount! cs p)
      (t/is (identical? p (tui/tui-focused-component ui)) "the panel holds input")
      (dock/clear! cs)
      (t/is (identical? ed (tui/tui-focused-component ui))
            "input went back to the active editor")
      (dispatch! ui "k")
      (t/is (str/includes? (editor/editor-get-text ed) "k")
            "a key reaches the editor again"))))

(deftest a-direct-dock-reset-still-restores-focus
  (testing "the watch — not the close path — is the guarantee: a future path
            that resets :dock-current without clear! cannot strand input"
    (let [{:keys [cs ui ed]} (focus-cs)
          p (panel (atom []) "panel")]
      (dock/mount! cs p)
      (reset! (:dock-current cs) nil)
      (t/is (identical? ed (tui/tui-focused-component ui))))))

(deftest clear-leaves-unrelated-focus-alone
  (testing "clearing the dock does not steal input that legitimately sits
            elsewhere (an overlay above, another panel)"
    (let [{:keys [cs ui]} (focus-cs)
          p (panel (atom []) "panel")
          other (editor/make-editor)]
      (dock/mount! cs p)
      (tui/tui-set-focus ui other)
      (dock/clear! cs)
      (t/is (identical? other (tui/tui-focused-component ui))))))

(deftest clear-of-a-borrowed-occupant-restores-focus-too
  (testing "a borrowed panel (the login dialog) keeps its lifecycle but not
            the input: the dock still hands focus back when it leaves"
    (let [{:keys [cs ui ed]} (focus-cs)
          dlg (panel (atom []) "dialog")]
      (dock/mount! cs dlg nil {:borrowed? true})
      (t/is (identical? dlg (tui/tui-focused-component ui)))
      (dock/clear! cs)
      (t/is (identical? ed (tui/tui-focused-component ui))))))

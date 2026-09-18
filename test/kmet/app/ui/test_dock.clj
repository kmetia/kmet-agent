(ns kmet.app.ui.test-dock
  "Dock mount/clear lifecycle (pi: showSelector / disposeActiveSelector):
   displacement disposal, borrowed panels, the staleness gate and done()
   idempotence."
  (:require [clojure.test :as t :refer [deftest]]
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

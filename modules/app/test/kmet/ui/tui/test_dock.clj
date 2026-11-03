(ns kmet.ui.tui.test-dock
  "Editor-dock stack lifecycle (pi: showSelector + editorContainer):
   replace-on-mount, cover/push, membership-based removal, displacement
   disposal, borrowed panels, the focus guard and dispose!'s leave-first
   invariant."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.ui.tui.dock :as dock]
            [kmet.app.debug :as debug]
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
  "A CS stand-in: the dock reads :tui, :dock-stack and
   :current-editor-atom."
  []
  {:tui {}
   :dock-stack (atom [])
   :current-editor-atom (atom (editor/make-editor))})

(defn- top [cs] (dock/top-component @(:dock-stack cs)))

(defn- mounted
  "The stack's components, top first (for assertions)."
  [cs]
  (mapv :component @(:dock-stack cs)))

(defn- tui-stubs [f]
  (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                tui/tui-request-render (fn [_] nil)]
    (f)))

(defn- mount-in [cs component & [opts]]
  (tui-stubs #(dock/mount! cs component (or opts {})))
  (fn [] (tui-stubs #(dock/release! cs component))))

(defn- cover-in [cs component & [opts]]
  (tui-stubs #(dock/cover! cs component (or opts {})))
  (fn [] (tui-stubs #(dock/release! cs component))))

(defn- release-in [cs component]
  (tui-stubs #(dock/release! cs component)))

(defn- clear-in [cs]
  (tui-stubs #(dock/clear! cs)))

(defn- focus-cs
  "A CS with a real TUI, the editor mounted as a root child, and the
   dock-aware focus home the app registers (see interactive.clj). Focus
   assertions use it — a stubbed tui-set-focus cannot exercise the
   ::focus-guard watch."
  []
  (let [ui (tui/create-tui nil)
        ed (editor/make-editor)
        cs {:tui ui
            :dock-stack (atom [])
            :current-editor-atom (atom ed)}]
    (tui/tui-add-child ui ed)
    (tui/tui-set-focus-home! ui #(or (dock/top-focus-target (deref (:dock-stack cs)))
                                     (deref (:current-editor-atom cs))))
    {:cs cs :ui ui :ed ed}))

(defn- dispatch! [ui data] ((var tui/dispatch-input!) ui data))

(deftest mount-records-focuses-and-restores
  (let [{:keys [cs ui ed]} (focus-cs)
        disposed (atom [])
        p (panel disposed "a")]
    (dock/mount! cs p)
    (t/is (= p (top cs)))
    (t/is (identical? p (tui/tui-focused-component ui))
          "focus defaults to the component")
    (t/is (empty? @disposed))
    (dock/release! cs p)
    (t/is (nil? (top cs)) "release reveals the editor")
    (t/is (identical? ed (tui/tui-focused-component ui)))
    (dock/release! cs p)
    (t/is (nil? (top cs)) "release is idempotent")))

(deftest mount-with-an-explicit-focus-target
  (let [{:keys [cs ui]} (focus-cs)
        disposed (atom [])
        p (panel disposed "frame")
        target (panel disposed "list")]
    (dock/mount! cs p {:focus-target target})
    (t/is (= p (top cs)) "the panel is docked")
    (t/is (identical? target (tui/tui-focused-component ui))
          "the focus target receives keys")))

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

(deftest mount-replaces-only-the-top
  (testing "a replacing mount leaves covered entries below (they are
            revealed when the replacement closes)"
    (let [cs (test-cs)
          disposed (atom [])
          a (panel disposed "a")
          b (panel disposed "b")
          c (panel disposed "c")]
      (mount-in cs a)
      (cover-in cs b)
      (mount-in cs c)
      (t/is (= [a c] (mounted cs)) "the covered A stays below")
      (t/is (= ["b"] @disposed) "only the replaced top is disposed")
      (clear-in cs)
      (t/is (= ["b" "a" "c"] @disposed)
            "clear! disposes every non-borrowed entry"))))

(deftest cover-pushes-and-reveals
  (let [cs (test-cs)
        disposed (atom [])
        a (panel disposed "a")
        b (panel disposed "b")]
    (mount-in cs a)
    (let [release-b (cover-in cs b)]
      (t/is (= [a b] (mounted cs)) "the cover sits on top")
      (t/is (empty? @disposed))
      (release-b)
      (t/is (= [a] (mounted cs)) "the covered surface is revealed again")
      (t/is (empty? @disposed) "removal never disposes"))))

(deftest covering-an-already-docked-component-is-a-no-op
  (testing "cover! never adds a second entry for the same component: one
            release! must not take out two entries, a no-op leaves focus
            alone, and re-covering does not move a buried component to the
            top (release! + cover!)"
    (let [{:keys [cs ui]} (focus-cs)
          disposed (atom [])
          dlg (panel disposed "dialog")
          sel (panel disposed "selector")]
      (dock/mount! cs dlg)
      (dock/cover! cs sel)
      (dock/cover! cs sel)
      (t/is (= [dlg sel] (mounted cs))
            "covering the top again adds nothing")
      (t/is (identical? sel (tui/tui-focused-component ui))
            "focus stays where it was")
      (dock/cover! cs dlg)
      (t/is (= [dlg sel] (mounted cs))
            "covering a buried component adds nothing and leaves it buried")
      (t/is (identical? sel (tui/tui-focused-component ui))
            "a no-op cover does not move a buried component's focus")
      (dock/release! cs sel)
      (t/is (= [dlg] (mounted cs)) "one release removes the one entry")
      (t/is (empty? @disposed) "a no-op cover disposes nothing"))))

(deftest release-removes-from-any-depth-without-disposing
  (let [cs (test-cs)
        disposed (atom [])
        a (panel disposed "a")
        b (panel disposed "b")
        c (panel disposed "c")]
    (mount-in cs a)
    (cover-in cs b)
    (cover-in cs c)
    (t/is (= [a b c] (mounted cs)))
    (t/is (true? (release-in cs b)) "the covered middle entry leaves")
    (t/is (= [a c] (mounted cs)) "the survivors stay")
    (t/is (empty? @disposed) "removals never dispose")
    (t/is (nil? (release-in cs b)) "release! is idempotent")
    (release-in cs a)
    (t/is (= [c] (mounted cs)))))

(deftest borrowed-panels-stay-alive
  ;; the auth dialog a prompt selector temporarily replaces, or an extension
  ;; dialog the registry keeps custody of: the dock must not dispose it
  (let [cs (test-cs)
        disposed (atom [])
        dlg (panel disposed "dialog")
        sel (panel disposed "selector")
        dlg2 (panel disposed "dialog-again")]
    (mount-in cs dlg {:borrowed? true})
    (mount-in cs sel)
    (t/is (empty? @disposed) "a selector mount leaves the borrowed dialog alone")
    ;; the dialog takes the dock back (a re-mount, or a cover)
    (mount-in cs dlg2 {:borrowed? true})
    (t/is (= ["selector"] @disposed)
          "a non-borrowed selector is disposed when borrowed chrome takes back the dock")))

(deftest covered-borrowed-dialog-is-not-disposed
  (testing "the auth round-trip: the method selector covers the dialog, so
            closing it reveals the same dialog instead of re-mounting one"
    (let [cs (test-cs)
          disposed (atom [])
          dlg (panel disposed "dialog")
          sel (panel disposed "selector")]
      (mount-in cs dlg {:borrowed? true})
      (cover-in cs sel)
      (t/is (= [dlg sel] (mounted cs)))
      (release-in cs sel)
      (t/is (= [dlg] (mounted cs)) "the dialog was below all along")
      (t/is (empty? @disposed)))))

(deftest re-mounting-the-same-panel-does-not-dispose-it
  (let [cs (test-cs)
        disposed (atom [])
        p (panel disposed "a")]
    (mount-in cs p)
    (mount-in cs p)
    (t/is (empty? @disposed) "identity is respected (no self-disposal)")))

(deftest a-release-for-an-absent-component-is-inert
  (testing "membership replaced the generation token: a release removes only
            its own component, so it cannot yank a newer occupant"
    (let [cs (test-cs)
          disposed (atom [])
          a (panel disposed "a")
          b (panel disposed "b")
          release-a (mount-in cs a)]
      (mount-in cs b)
      (release-a)
      (t/is (= b (top cs)) "the stale release is inert")
      (t/is (= ["a"] @disposed) "disposal came from displacement"))))

(deftest a-release-removes-its-component-from-any-depth
  (testing "an owner can leave while covered — the Phase-1 residual gap"
    (let [cs (test-cs)
          disposed (atom [])
          dlg (panel disposed "dialog")
          sel (panel disposed "selector")
          release-dlg (mount-in cs dlg {:borrowed? true})]
      (cover-in cs sel)
      (release-dlg)
      (t/is (= [sel] (mounted cs)) "the covered dialog left, the selector stays")
      (t/is (empty? @disposed) "removal never disposes the borrowed panel"))))

(deftest clear-takes-the-dock-back
  (let [cs (test-cs)
        disposed (atom [])
        sel (panel disposed "selector")]
    (mount-in cs sel)
    (clear-in cs)
    (t/is (nil? (top cs)) "cleared")
    (t/is (= ["selector"] @disposed) "the occupant is disposed")))

(deftest clear-leaves-borrowed-panels-to-their-owner
  (let [cs (test-cs)
        disposed (atom [])
        dlg (panel disposed "dialog")]
    (mount-in cs dlg {:borrowed? true})
    (clear-in cs)
    (t/is (nil? (top cs)))
    (t/is (empty? @disposed) "borrowed panels are the owner's to dispose")))

;; ─── Recorded panels (show-panel!) ─────────────────────────────────────────

(deftest show-panel-records-and-close-atom-releases
  (testing "the recorded-panel pair mounts the panel and remembers it in
            the caller's late-binding atom, then closes whatever it holds"
    (let [cs (test-cs)
          disposed (atom [])
          p (panel disposed "panel")
          panel-atom (atom nil)]
      (tui-stubs #(dock/show-panel! cs panel-atom p))
      (t/is (identical? p @panel-atom))
      (t/is (identical? p (top cs)))
      (tui-stubs #(dock/close-atom! cs panel-atom))
      (t/is (nil? (top cs)))
      (t/is (= ["panel"] @disposed)))))

(deftest show-panel-records-before-publishing-the-stack
  (testing "a stack watch that closes the recorded panel during the mount
            finds it — the record lands first, as in state/mount-selector!"
    (let [cs (test-cs)
          disposed (atom [])
          p (panel disposed "panel")
          panel-atom (atom nil)
          closed? (atom false)
          stack (:dock-stack cs)]
      (add-watch stack ::close-on-mount
                 (fn [_ _ _ _]
                   (when (and @panel-atom (not @closed?))
                     (reset! closed? true)
                     (tui-stubs #(dock/close-atom! cs panel-atom)))))
      (try
        (tui-stubs #(dock/show-panel! cs panel-atom p))
        (finally (remove-watch stack ::close-on-mount)))
      (t/is (true? @closed?) "the watch closed the panel during the mount")
      (t/is (nil? (top cs)))
      (t/is (= ["panel"] @disposed)))))

;; ─── dispose! (the owner's ordered close) ──────────────────────────────────

(deftest dispose-leaves-the-stack-before-disposing
  (testing "the invariant: a discarded component can never stay docked —
            dispose! removes first (issue #5's dead panel)"
    (let [cs (test-cs)
          disposed (atom [])
          p (panel disposed "p")
          logged (atom [])]
      (mount-in cs p)
      (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))]
        (tui-stubs #(dock/dispose! cs p)))
      (t/is (nil? (top cs)) "removed before disposal")
      (t/is (= ["p"] @disposed) "then disposed")
      (t/is (= 1 (count @logged)) "the violation is recorded"))))

(deftest dispose-of-a-released-panel-is-quiet
  (testing "the ordered close (release!, then dispose!) records nothing"
    (let [cs (test-cs)
          disposed (atom [])
          p (panel disposed "p")
          logged (atom [])]
      (mount-in cs p)
      (release-in cs p)
      (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))]
        (tui-stubs #(dock/dispose! cs p))
        (t/is (empty? @logged))))))

(deftest dispose-throws-in-debug-mode
  (let [cs (test-cs)
        disposed (atom [])
        p (panel disposed "p")
        logged (atom [])]
    (mount-in cs p)
    (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))
                  debug/enabled? (constantly true)]
      (t/is (thrown? Exception
                     (tui-stubs #(dock/dispose! cs p))))
      (t/is (nil? (top cs)) "still removed first")
      (t/is (= ["p"] @disposed) "still disposed")
      (t/is (= 1 (count @logged))))))

(deftest dispose-removes-a-still-shown-overlay-first
  (testing "the overlay stack gets the same ordered close as the dock: a
            panel disposed while floating is removed first, and the
            skipped leave is recorded"
    (let [{:keys [cs ui]} (focus-cs)
          disposed (atom [])
          p (panel disposed "overlay")
          logged (atom [])]
      (tui/tui-show-overlay ui p)
      (t/is (true? (tui/tui-has-overlay? ui)))
      (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))]
        (dock/dispose! cs p))
      (t/is (false? (tui/tui-has-overlay? ui)) "removed before disposal")
      (t/is (= ["overlay"] @disposed) "then disposed")
      (t/is (= 1 (count @logged)) "the violation is recorded"))))

(deftest dispose-of-a-hidden-overlay-is-quiet
  (testing "the ordered close (tui-hide-overlay by identity, then
            dispose!) records nothing"
    (let [{:keys [cs ui]} (focus-cs)
          disposed (atom [])
          p (panel disposed "overlay")
          logged (atom [])]
      (tui/tui-show-overlay ui p)
      (tui/tui-hide-overlay ui p)
      (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))]
        (dock/dispose! cs p))
      (t/is (= ["overlay"] @disposed))
      (t/is (empty? @logged)))))

(deftest dispose-of-a-shown-overlay-throws-in-debug-mode
  (testing "the overlay order violation trips --debug like the dock's"
    (let [{:keys [cs ui]} (focus-cs)
          disposed (atom [])
          p (panel disposed "overlay")
          logged (atom [])]
      (tui/tui-show-overlay ui p)
      (with-redefs [debug/log-error (fn [& args] (swap! logged conj args))
                    debug/enabled? (constantly true)]
        (t/is (thrown? Exception (dock/dispose! cs p)))
        (t/is (false? (tui/tui-has-overlay? ui)) "still removed first")
        (t/is (= ["overlay"] @disposed) "still disposed")
        (t/is (= 1 (count @logged)))))))

;; ─── Focus integrity (the ::focus-guard watch) ─────────────────────────────
;; A panel that leaves the dock while holding input would swallow every key
;; (nothing on screen reacts) — the "lost focus after close / unresponsive
;; UI" class. The guard, not each close path, is what makes the restore
;; happen: it watches :dock-stack like the TUI's ::ghost-guard watches the
;; overlay stack.

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

(deftest a-direct-stack-reset-still-restores-focus
  (testing "the watch — not the close path — is the guarantee: a future path
            that resets :dock-stack without clear! cannot strand input"
    (let [{:keys [cs ui ed]} (focus-cs)
          p (panel (atom []) "panel")]
      (dock/mount! cs p)
      (reset! (:dock-stack cs) [])
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
      (dock/mount! cs dlg {:borrowed? true})
      (t/is (identical? dlg (tui/tui-focused-component ui)))
      (dock/clear! cs)
      (t/is (identical? ed (tui/tui-focused-component ui))))))

(deftest release-hands-input-back-to-the-editor
  (testing "release! leaves focus to the ::focus-guard watch — the editor
            receives keys again, the released panel does not"
    (let [{:keys [cs ui ed]} (focus-cs)
          dlg (panel (atom []) "dialog")]
      (dock/mount! cs dlg {:borrowed? true})
      (t/is (identical? dlg (tui/tui-focused-component ui)))
      (t/is (true? (dock/release! cs dlg)))
      (t/is (identical? ed (tui/tui-focused-component ui)))
      (dispatch! ui "k")
      (t/is (str/includes? (editor/editor-get-text ed) "k"))
      (t/is (nil? (top cs))))))

(deftest closing-a-cover-reveals-the-surface-below-and-its-focus
  (testing "the auth method-selector round trip: the selector covers the
            login dialog; closing it reveals the dialog and input lands
            back on the dialog (issue #5's actual flow)"
    (let [{:keys [cs ui ed]} (focus-cs)
          dlg (panel (atom []) "dialog")
          sel (panel (atom []) "selector")]
      (dock/mount! cs dlg {:borrowed? true})
      (t/is (identical? dlg (tui/tui-focused-component ui)))
      (dock/cover! cs sel)
      (t/is (identical? sel (tui/tui-focused-component ui)) "the cover holds input")
      (t/is (= [dlg sel] (mounted cs)) "the dialog stayed below")
      (dock/release! cs sel)
      (t/is (= [dlg] (mounted cs)) "the dialog is revealed")
      (t/is (identical? dlg (tui/tui-focused-component ui))
            "input lands back on the revealed dialog")
      (dock/release! cs dlg)
      (t/is (identical? ed (tui/tui-focused-component ui))))))

(deftest release-rebaselines-the-screen-fill-without-a-forced-rebuild
  (testing "a released panel takes its height with it: the screen fill is
            re-baselined on an ordinary frame request (never a clearing
            rebuild), so the document takes its natural height again and the
            user action keeps the pre-fill diff behavior"
    (let [cs (test-cs)
          disposed (atom [])
          p (panel disposed "p")
          requests (atom [])]
      (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                    tui/tui-request-render (fn [_ & args] (swap! requests conj (vec args)))]
        (dock/mount! cs p)
        (reset! requests [])
        (dock/release! cs p)
        (t/is (= [[]] @requests) "one ordinary render request, no force")
        (reset! requests [])
        (dock/release! cs p)
        (t/is (empty? @requests) "a release that removed nothing requests nothing")))))

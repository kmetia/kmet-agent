(ns kmet.ui.tui.interactive.test-handle-path
  "The interactive mode's own code paths over the composition's handle — the
   re-host landing 8 finished, proven where the TUI runs it rather than against
   a stand-in engine.

   `/new` is the case that shows the seam: the handle's `:new-session!` moves
   the agent in place and installs nothing, so the mode's own
   `install-live-session!` is what points the process-wide extension slot at
   the session an out-of-run handler must see. A test that stood up a
   stand-in `Engine` would pass even if the handle's op were missing, so this
   one creates through `:agents` and reads the registry afterwards: one agent,
   one new session."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.runtime :as runtime]
            [kmet.tui.components.editor :as editor]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.app.events :as events]
            [kmet.test-utils :as tu]
            [kmet.ui.tui.interactive.test-handle-support :as hs]))

(t/use-fixtures :each hs/with-boot)

(deftest new-session-moves-the-handles-session-not-an-agent
  (testing "the TUI's /new over a real handle: the agent stays, the session
            moves, the registry still lists one agent, and the front end's
            install is what an out-of-run extension handler sees"
    (let [{:keys [ctx agents handle]} (hs/create-handle! (hs/current))
          old-session (runtime/session handle)
          _ (tu/clear-listeners! (hs/events-ctx handle))
          shutdown-events (atom [])
          _ (events/on! (hs/events-ctx handle)
                        :session-shutdown
                        (fn [ev] (swap! shutdown-events conj ev)))
          cs (hs/core-state handle)]
      (try
        (session-admin/handle-new-session cs)
        (let [new-session (runtime/session handle)]
          (is (not= (:id old-session) (:id new-session))
              "a fresh session is created")
          (is (= new-session (extensions/get-session))
              "the process slot names the session the user just switched to: an
               extension command runs outside a run and has no other answer")
          (is (= [(:id handle)] (mapv :id ((:list agents) ctx)))
              "the registry still lists the agent it listed, not a second one")
          (is (= [{:type :session-shutdown :reason :new
                   :target-session-file (:file old-session)}]
                 @shutdown-events)
              "extensions are told the runtime is torn down before the swap")
          (is (str/blank? (editor/editor-get-text (:editor cs)))
              "the editor is cleared"))
        (finally (tu/clear-listeners! (hs/events-ctx handle)))))))

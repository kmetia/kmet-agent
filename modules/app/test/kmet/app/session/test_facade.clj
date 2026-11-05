(ns kmet.app.session.test-facade
  "Guard: kmet.app.session.core is the public API its dependents require.

   The implementation is split across session.{store,listing,context,stats,tree}
   with core as a compatibility façade; this pins the façade exactly, so an
   extraction cannot silently drop a function callers still use (or leak an
   implementation var into the public surface)."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.session.core :as session]))

(def ^:private public-fns
  '#{append-context-edit! append-custom-entry! append-custom-message-entry!
     append-entry append-model-change! append-session-info!
     append-thinking-level-change! branch! branch-summary-entries
     branch-with-summary! build-context build-context-messages build-session-info
     build-session-infos clone-session common-ancestor-id compact-with-summary!
     context-edit-label context-entries context-messages copy-imported-session!
     deferred-sources-listing
     create-session delete-session! delete-session-file
     derive-context-settings detect-cache-miss
     decode-cwd encode-cwd eval-library-custom-type eval-library-listing
     find-most-recent-session fork-from fork-session
     get-all-custom-entries get-branch get-custom-entries get-entry
     get-first-message get-label get-labels get-last-activity-ms
     get-last-assistant-text get-message-count get-prompt-history get-session-name
     get-session-stats get-tree legacy-encode-cwd
     legacy-session-dir-for-cwd list-sessions list-sessions-info load-session
     make-bash-entry plan-session-import project-context project-entries
     record-bash-result! replace-entries! reset-leaf! resolve-session-path
     sanitize-session-name session-cwd session-dir-for-cwd session-entry-text
     session-tool-state set-label! tool-sources-custom-type tool-state
     tool-state-changes tool-usage tool-usage-report
     usage-breakdown usage-totals})

(deftest facade-loads-the-implementation
  (testing "requiring the façade loads the namespaces behind it"
    (is (fn? session/append-entry))))

(deftest public-surface-is-pinned
  (testing "the façade exposes exactly the functions dependents require"
    (is (= public-fns (set (keys (ns-publics 'kmet.app.session.core)))))))

(ns kmet.app.session.core
  "Public API for session storage: EDNL sessions — line-delimited EDN with
   parent-child ids for tree branching (pi: @earendil-works/pi-agent session
   storage).

   The implementation lives in session.{store,listing,context,stats,tree};
   this namespace re-exports it so its dependents keep one require. The
   re-exports capture values at load time: after redefining an
   implementation var, reload this namespace (or the whole tree) too."
  (:require [kmet.app.session.context :as context]
            [kmet.app.session.listing :as listing]
            [kmet.app.session.stats :as stats]
            [kmet.app.session.store :as store]
            [kmet.app.session.tree :as tree]))

;; ─── store: entries, ids, durable I/O, append journal ────────────────────
(def append-custom-entry! store/append-custom-entry!)
(def append-custom-message-entry! store/append-custom-message-entry!)
(def append-entry store/append-entry)
(def append-model-change! store/append-model-change!)
(def append-session-info! store/append-session-info!)
(def append-thinking-level-change! store/append-thinking-level-change!)
(def branch! store/branch!)
(def compact-with-summary! store/compact-with-summary!)
(def create-session store/create-session)
(def decode-cwd store/decode-cwd)
(def encode-cwd store/encode-cwd)
(def legacy-encode-cwd store/legacy-encode-cwd)
(def legacy-session-dir-for-cwd store/legacy-session-dir-for-cwd)
(def get-all-custom-entries store/get-all-custom-entries)
(def get-branch store/get-branch)
(def get-custom-entries store/get-custom-entries)
(def get-entry store/get-entry)
(def get-session-name store/get-session-name)
(def load-session store/load-session)
(def make-bash-entry store/make-bash-entry)
(def record-bash-result! store/record-bash-result!)
(def replace-entries! store/replace-entries!)
(def reset-leaf! store/reset-leaf!)
(def sanitize-session-name store/sanitize-session-name)
(def session-cwd store/session-cwd)
(def session-dir-for-cwd store/session-dir-for-cwd)
(def session-entry-text store/session-entry-text)

;; ─── listing: on-disk discovery, header scan, info, delete, import ──────
(def build-session-info listing/build-session-info)
(def build-session-infos listing/build-session-infos)
(def copy-imported-session! listing/copy-imported-session!)
(def delete-session! listing/delete-session!)
(def delete-session-file listing/delete-session-file)
(def find-most-recent-session listing/find-most-recent-session)
(def list-sessions listing/list-sessions)
(def list-sessions-info listing/list-sessions-info)
(def plan-session-import listing/plan-session-import)
(def resolve-session-path listing/resolve-session-path)

;; ─── context: message context, append-only context edits, cache-miss ─────
(def append-context-edit! context/append-context-edit!)
(def build-context context/build-context)
(def build-context-messages context/build-context-messages)
(def context-edit-label context/context-edit-label)
(def context-entries context/context-entries)
(def context-messages context/context-messages)
(def deferred-sources-listing context/deferred-sources-listing)
(def eval-library-custom-type context/eval-library-custom-type)
(def eval-library-listing context/eval-library-listing)
(def session-tool-state context/session-tool-state)
(def tool-sources-custom-type context/tool-sources-custom-type)
(def tool-state context/tool-state)
(def tool-state-changes context/tool-state-changes)
(def derive-context-settings context/derive-context-settings)
(def detect-cache-miss context/detect-cache-miss)
(def project-context context/project-context)
(def project-entries context/project-entries)

;; ─── stats: usage/tokens, counts, prompt history, last activity ──────────
(def get-first-message stats/get-first-message)
(def get-last-activity-ms stats/get-last-activity-ms)
(def get-last-assistant-text stats/get-last-assistant-text)
(def get-message-count stats/get-message-count)
(def get-prompt-history stats/get-prompt-history)
(def get-session-stats stats/get-session-stats)
(def tool-usage stats/tool-usage)
(def tool-usage-report stats/tool-usage-report)
(def usage-breakdown stats/usage-breakdown)
(def usage-totals stats/usage-totals)

;; ─── tree: branches, labels, tree projection, fork/clone ─────────────────
(def branch-summary-entries tree/branch-summary-entries)
(def branch-with-summary! tree/branch-with-summary!)
(def clone-session tree/clone-session)
(def common-ancestor-id tree/common-ancestor-id)
(def fork-from tree/fork-from)
(def fork-session tree/fork-session)
(def get-label tree/get-label)
(def get-labels tree/get-labels)
(def get-tree tree/get-tree)
(def set-label! tree/set-label!)

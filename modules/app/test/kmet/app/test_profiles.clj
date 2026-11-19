(ns kmet.app.test-profiles
  "kmet.app.profiles — the committed composition table: the shipped profiles,
   the minimal bundle and the relationships a profile stack keeps."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.profiles :as profiles]
            [kmet.sklop.core :as sklop]))

(deftest builtin-table-is-complete
  (let [table (profiles/builtin)]
    (is (= #{:tui :tui-ask :print :rpc :rpc-ask :server}
           (set (keys (:profiles table))))
        "the shipped profiles: the TUI, its gated variant, print, rpc and the server")
    (is (contains? (:bundles table) :bundle/minimal))
    (is (= [{:id :permissions :plugin 'kmet.app.permissions/permissions
             :config {:policy :ask-all}}]
           (:rows (sklop/bundle table :bundle/gated)))
        "the gate is a bundle, so a profile may stack it and a preset may mount it")
    (testing "the TUI stack is the minimal bundle, minus the seam it replaces"
      ;; Derived from the bundle, not a row-by-row literal: the point is the
      ;; relationship (every minimal row survives, in order, except the one the
      ;; terminal provider replaces), and the generated docs/composition.md is
      ;; where the full list lives.
      (let [minimal (:rows (sklop/bundle table :bundle/minimal))
            rows (:rows (sklop/compose (sklop/layers table :tui)))]
        (is (= (conj (into [] (remove #{:approvals}) (map :id minimal))
                     :ui :mode-tui)
               (mapv :id rows))
            (str "every minimal row keeps its place; the terminal provider "
                 "replaces the approvals seam and the TUI is the mode row"))
        (is (= {:id :mode-tui :plugin 'kmet.ui.tui/plugin :mode true
                :session-lifecycle true}
               (last rows))
            "the mode row opens its own session")
        (is (not-any? #{:approvals} (map :id rows))
            "the approvals row the terminal provider shadows is gone")))
    (is (= {:id :mode-rpc :plugin 'kmet.app.modes.rpc/plugin :mode true}
           (last (:rows (sklop/compose (sklop/layers table :rpc)))))
        "and a profile with a mode row carries it last, marked :mode")))

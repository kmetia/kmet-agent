(ns kmet.app.test-tool-changes
  "P6: the declared tool set recorded as session state, the fold that replays
   it, and the request data the three mid-conversation wires consume."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
            [kmet.app.loop.state :as state]
            [kmet.app.session.core :as session]
            [kmet.app.session.schema :as schema]
            [kmet.app.tools.registry :as tools]
            [kmet.app.loop.test-support :as support]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :each (fn [f] (support/with-registry f)))

(defn- names [tools]
  (into #{} (map :name) tools))

(defn- temp-session []
  (let [dir (test-tmp/temp-dir! "test-tool-changes-")]
    {:session (session/create-session dir) :dir dir}))

(defn- tool-map [name]
  {:name name :description (str name " tool")
   :parameters {:type "object" :properties {}}})

(defn- deferred-source! [h id tools]
  (tools/register-tool-source! h id (fn [] tools)
                               {:exposure :deferred :description "A catalog"}))

(defn- changes [sess]
  (filterv #(= :tool-change (:role %)) (session/get-branch sess)))

;; ─── The record and the fold ─────────────────────────────────────────────

(t/deftest test-the-schema-accepts-the-recorded-roles
  (t/is (schema/validate-entry? {:role :custom-message :id "1" :parent-id nil
                                 :custom-type :note :content "hi" :display false})
        ":custom-message is a named entry, not a wrong shape")
  (t/is (schema/validate-entry? {:role :tool-change :id "2" :parent-id "1"
                                 :added [{:name "a" :description "a"
                                          :parameters {:type "object"
                                                       :properties {}}}]
                                 :removed ["b"]})))

(t/deftest test-a-tool-change-projects-to-a-wire-marker
  (t/is (= [{:role :tool-change :added [(tool-map "a")] :removed ["b"]}]
           (session/context-messages {:role :tool-change
                                      :added [(tool-map "a")]
                                      :removed ["b"]}))
        "the record projects to a wire-only marker at its position; the chat
         replay, export and tree still skip the entry"))

(t/deftest test-the-fold-keeps-the-last-definition-and-removes-names
  (let [entries [{:role :user :content "hi"}
                 {:role :tool-change
                  :added [{:name "a" :description "first"}]
                  :removed []}
                 {:role :tool-change
                  :added [{:name "b" :description "b"}]
                  :removed ["a"]}
                 {:role :tool-change
                  :added [{:name "a" :description "second"}]
                  :removed []}]]
    (t/is (= {"a" {:name "a" :description "second"}
              "b" {:name "b" :description "b"}}
             (session/tool-state entries))
          "a later addition redefines the name; a removal deletes it")
    (t/is (= ["a" "b"] (keys (session/tool-state entries)))
          "the fold is name-sorted")))

(t/deftest test-a-first-mutation-records-the-whole-declared-set
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (t/is (empty? (changes session)) "a bare state records nothing yet")
      (state/sync-tool-declarations! agent)
      (let [[entry :as entries] (changes session)]
        (t/is (= 1 (count entries)))
        (t/is (= (names (state/active-tools agent))
                 (into #{} (map :name) (:added entry)))
              "the initial entry carries the whole live set")
        (t/is (empty? (:removed entry))))
      (t/is (nil? (state/sync-tool-declarations! agent))
            "an unchanged set records nothing")
      (t/is (= 1 (count (changes session))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-selection-change-records-a-delta
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (state/set-active-tools! agent #{"read"})
      (state/set-active-tools! agent #{"read" "bash"})
      (let [entries (changes session)
            last-entry (peek entries)]
        (t/is (= 2 (count entries)))
        (t/is (= ["bash"] (mapv :name (:added last-entry))))
        (t/is (not (contains? (set (:removed last-entry)) "bash")))
        (t/is (= #{"bash" "read"} (names (state/active-tools agent)))))
      (state/set-active-tools! agent #{"read"})
      (t/is (= ["read"] (mapv :name (state/active-tools agent))))
      (t/is (= ["bash"] (:removed (peek (changes session)))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-resumed-session-honors-a-persisted-selection
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names ["read"]))]
    (try
      (state/sync-tool-declarations! agent)
      (t/is (= #{"read"} (names (state/active-tools agent)))
            "the configured selection is the live loadout")
      (let [resumed (support/make-agent-state :session session
                                              :enabled-tool-names (support/tool-names ["read"]))]
        (state/sync-resumed-declarations! resumed)
        (t/is (= #{"read"} (names (state/active-tools resumed)))
              "the persisted selection survives resume")
        (state/set-active-tools! resumed #{"read" "bash"})
        (t/is (= #{"bash" "read"} (names (state/active-tools resumed)))
              "a runtime override re-adds it"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-run-tool-selection-reaches-a-resumed-loadout
  ;; `--tools +eval` on a resumed session must declare the opt-in built-in the
  ;; transcript never held. The context is restored first, as the driver does,
  ;; so the delta is recorded against the session's own loadout.
  (let [{:keys [session dir]} (temp-session)
        fresh (support/make-agent-state :session session)]
    (try
      (state/sync-tool-declarations! fresh)
      (t/is (= #{"bash" "edit" "read" "write"}
               (names (state/active-tools fresh))))
      (let [resumed (support/make-agent-state :session session)]
        (state/restore-session-context! resumed)
        (state/set-active-tools! resumed #{"read" "eval"})
        (t/is (= #{"eval" "read"} (names (state/active-tools resumed)))
              "the run selection wins over the recorded loadout")
        (let [entry (peek (changes session))]
          (t/is (= ["eval"] (mapv :name (:added entry)))
                "the opt-in built-in is declared")
          (t/is (= ["bash" "edit" "write"] (:removed entry))
                "and the tools the selection drops are removed")))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-persisted-selection-filters-a-resumed-record
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (state/sync-tool-declarations! agent)
      (let [resumed (support/make-agent-state :session session
                                              :enabled-tool-names (support/tool-names ["read"]))]
        (t/is (= #{"read"} (names (state/active-tools resumed)))
              "the recorded set is filtered before the first request")
        (state/restore-session-context! resumed)
        (state/sync-resumed-declarations! resumed)
        (t/is (= #{"read"} (names (state/active-tools resumed)))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-remounted-tool-returns-unless-the-selection-excludes-it
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool! h (tool-map "flappy"))
      (state/sync-tool-declarations! agent)
      (t/is (contains? (names (state/active-tools agent)) "flappy"))
      ;; unmount: the next reconcile records the removal
      (tools/unregister-tool! h "flappy")
      (state/sync-resumed-declarations! agent)
      (t/is (not (contains? (names (state/active-tools agent)) "flappy")))
      ;; remount: the recorded removal is not a persisted disable
      (tools/register-tool! h (tool-map "flappy"))
      (let [resumed (support/make-agent-state :session session)]
        (state/sync-resumed-declarations! resumed)
        (t/is (contains? (names (state/active-tools resumed)) "flappy")
              "a seen-and-live tool returns after a remount"))
      ;; a persisted exclusion still wins
      (let [disabled (support/make-agent-state :session session
                                               :enabled-tool-names (support/tool-names ["-flappy"]))]
        (state/restore-session-context! disabled)
        (state/sync-resumed-declarations! disabled)
        (t/is (not (contains? (names (state/active-tools disabled)) "flappy"))))
      (finally
        (tools/unregister-tool! h "flappy")
        (fs/delete-tree dir)))))

(t/deftest test-a-resumed-session-adds-a-new-extension-tool
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (state/sync-tool-declarations! agent)
      (let [recorded (set (map :name (state/active-tools agent)))]
        ;; a tool registered after the record is default-on, like pi's
        ;; restored loadout
        (tools/register-tool! h (tool-map "late-extension"))
        (try
          (let [resumed (support/make-agent-state :session session)]
            (state/sync-resumed-declarations! resumed)
            (t/is (contains? (names (state/active-tools resumed)) "late-extension"))
            (t/is (every? (names (state/active-tools resumed)) recorded)
                  "the recorded set is still there"))
          (finally (tools/unregister-tool! h "late-extension"))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-resumed-redefinition-is-recorded
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool! h {:name "custom-a" :description "first"
                               :parameters {:type "object" :properties {}}
                               :execute (fn [_] {:content "ok"})})
      (state/sync-tool-declarations! agent)
      (tools/register-tool! h {:name "custom-a" :description "second"
                               :parameters {:type "object" :properties {}}
                               :execute (fn [_] {:content "ok"})})
      (let [resumed (support/make-agent-state :session session)]
        ;; a real resume rebuilds the context from the session first, so the
        ;; wire baseline is the recorded marker (prepare-request! does this at
        ;; the request checkpoint)
        (state/restore-session-context! resumed)
        (state/sync-resumed-declarations! resumed)
        (let [declared (into {} (map (juxt :name identity))
                             (state/active-tools resumed))]
          (t/is (= "second" (get-in declared ["custom-a" :description]))
                "a re-registered definition replaces the recorded one")
          (t/is (= ["custom-a"] (:removed (peek (changes session))))
                "the redefinition is a removal plus an addition")))
      (finally
        (tools/unregister-tool! h "custom-a")
        (fs/delete-tree dir)))))

(t/deftest test-a-resumed-promotion-adds-the-discovery-tool-but-keeps-removals
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names ["-bash" "-edit" "-write" "-codemode" "-eval"]))]
    (try
      (state/sync-tool-declarations! agent)
      ;; the persisted selection keeps the unrelated built-ins out; the
      ;; deferred source mounts after the record, so tool_search joins it
      (tools/register-tool-source! h ::catalog
                                   (fn [] {"deferred-a" (tool-map "deferred-a")})
                                   {:exposure :deferred :description "A catalog"})
      (let [resumed (support/make-agent-state :session session
                                              :enabled-tool-names (support/tool-names ["-bash" "-edit" "-write" "-codemode" "-eval"]))]
        (state/sync-resumed-declarations! resumed)
        (t/is (= #{"read" "tool_search"}
                 (names (state/active-tools resumed)))
              "the discovery tool joins; the recorded removals stay out")
        (state/promote-tools! resumed ["deferred-a"])
        (t/is (= #{"read" "tool_search" "deferred-a"}
                 (names (state/active-tools resumed)))
              "the promotion adds the tool without re-adding unrelated ones"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-selection-that-orphans-deferred-tools
  (let [h (support/tool-table)]
    (deferred-source! h ::catalog {"deferred-a" (tool-map "deferred-a")})
    (try
      (t/is (= ["deferred-a"]
               (state/orphaned-deferred-tools
                (support/make-agent-state :enabled-tool-names (support/tool-names ["-tool_search"]))))
            "a selected deferred tool with no discovery tool is orphaned")
      (t/is (nil? (state/orphaned-deferred-tools
                   (support/make-agent-state
                    :enabled-tool-names (support/tool-names ["-tool_search" "-deferred-a"]))))
            "excluding the deferred tool too loses nothing")
      (finally
        (tools/unregister-tool-source! h ::catalog)))))
(t/deftest test-the-prompt-derivation-honors-the-enabled-setting
  (let [{:keys [session dir]} (temp-session)
        ctx (support/process-ctx)]
    (try
      (t/is (= #{"read"}
               (names (state/declared-tools-for-session
                       ctx session {:enabled-tool-names (support/tool-names ["read"])}))))
      (state/sync-tool-declarations! (support/make-agent-state :session session))
      (t/is (= #{"read"}
               (names (state/declared-tools-for-session
                       ctx session {:enabled-tool-names (support/tool-names ["read"])})))
            "a recorded declaration is filtered for the prompt")
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-promotion-records-the-new-declaration
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (deferred-source! h ::catalog {"deferred-a" (tool-map "deferred-a")})
    (try
      (state/sync-tool-declarations! agent)
      (state/promote-tools! agent ["deferred-a"])
      (t/is (some #(= "deferred-a" (:name %))
                  (:added (peek (changes session))))
            "the promotion records the definition by value")
      (t/is (contains? (names (state/active-tools agent)) "deferred-a"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-resumed-session-replays-the-recorded-definition
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (deferred-source! h ::catalog {"deferred-a" (tool-map "deferred-a")})
    (try
      (state/sync-tool-declarations! agent)
      (state/promote-tools! agent ["deferred-a"])
      (let [resumed (support/make-agent-state :session session)
            declared (into {} (map (juxt :name identity)) (state/active-tools resumed))]
        (t/is (= "deferred-a tool" (get-in declared ["deferred-a" :description])))
        (t/is (contains? declared "deferred-a")))
      ;; a registry change after the record does not rewrite what the branch
      ;; says was sent
      (tools/unregister-tool-source! h ::catalog)
      (let [resumed (support/make-agent-state :session session)]
        (t/is (contains? (names (state/active-tools resumed)) "deferred-a")
              "the recorded declaration survives the source going away"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-compaction-re-declares-the-loadout-from-the-live-context
  ;; the wire folds the markers in the live context, not the whole branch:
  ;; after a compaction drops them, the next reconcile must re-declare the
  ;; current loadout rather than record a delta against history the wire no
  ;; longer sees (pi: declareToolChanges over the current transcript).
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (state/sync-tool-declarations! agent)
      (session/append-entry session {:role :compaction
                                     :summary "summed up"
                                     :first-kept-id nil})
      (state/refresh-context-from-session! agent)
      (t/is (empty? (filter #(= :tool-change (:role %)) @(:messages agent)))
            "the compaction dropped the initial marker")
      (state/sync-resumed-declarations! agent)
      (let [markers (filter #(= :tool-change (:role %)) @(:messages agent))]
        (t/is (= 1 (count markers)))
        (t/is (= (names (state/active-tools agent))
                 (into #{} (map :name) (:added (first markers))))
              "the new marker declares the whole current set"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-a-change-recorded-mid-batch-waits-for-the-results
  ;; a tool_search promotion (or any selection change) lands while the
  ;; assistant's tool calls are still executing. The marker must not split a
  ;; call from its result, so it is recorded at the next request boundary.
  (let [{:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (state/sync-tool-declarations! agent)
      (let [before (count (changes session))]
        (swap! (:messages agent) conj
               {:role :assistant :content []
                :tool-calls [{:id "tc1" :name "read" :arguments {}}]})
        (state/set-active-tools! agent #{"read"})
        (t/is (= before (count (changes session)))
              "mid-batch, nothing is recorded yet")
        (t/is (= :assistant (:role (peek @(:messages agent))))
              "and no marker splits the call from its result")
        (state/append-persisted-message!
         agent {:role :tool :tool-name "read"
                :content [{:type :tool_result :tool_use_id "tc1"
                           :content "ok"}]})
        (state/sync-resumed-declarations! agent)
        (let [msgs @(:messages agent)
              change-idx (last (keep-indexed (fn [i m]
                                               (when (= :tool-change (:role m)) i))
                                             msgs))
              tool-idx (first (keep-indexed (fn [i m]
                                              (when (= :tool (:role m)) i))
                                            msgs))]
          (t/is (some? change-idx))
          (t/is (> change-idx tool-idx)
                "the marker is recorded after the tool result")))
      (finally (fs/delete-tree dir)))))

(t/deftest test-the-request-messages-carry-the-markers-at-their-position
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)
        sent (support/run-turn-capturing-llm agent {:message "hi"})]
    (deferred-source! h ::catalog {"deferred-a" (tool-map "deferred-a")})
    (try
      (let [markers (filter #(= :tool-change (:role %)) (:messages sent))]
        (t/is (seq markers))
        (let [before markers]
          (t/is (seq (:added (first before))))
          (t/is (empty? (filter #(= "deferred-a" (:name %)) (:added (first before)))))
          (state/promote-tools! agent ["deferred-a"])
          (let [after (filter #(= :tool-change (:role %))
                              (:messages (support/run-turn-capturing-llm
                                          agent {:message "again"})))]
            (t/is (= (:added (first before)) (:added (first after)))
                  "the initial declaration is byte-stable across requests")
            (t/is (some #(= "deferred-a" (:name %))
                        (:added (last after)))
                  "the promotion is in the marker at the recorded position")
            (t/is (= (inc (count before)) (count after))
                  "one new marker, appended"))))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-persisted-selection-narrows-the-search-candidates
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (deferred-source! h ::catalog {"deferred-a" (tool-map "deferred-a")})
    (try
      (state/sync-tool-declarations! agent)
      (state/promote-tools! agent ["deferred-a"])
      ;; a persisted exclusion removes the promoted tool on resume
      (let [resumed (support/make-agent-state :session session
                                              :enabled-tool-names (support/tool-names ["-deferred-a"]))
            promotable (tools/promotable-tools (state/tool-table resumed)
                                               (state/ctx-contributed-tools (:ctx resumed))
                                               (state/tool-selection resumed))]
        (t/is (not (contains? promotable "deferred-a"))
              "a search cannot promise a tool the persisted selection removed"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

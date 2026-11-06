(ns kmet.app.test-tool-exposure
  "Tool exposure: where a contributed tool source's catalog lands.

   `:sandbox` (the default) adds a source's tools to the `run_code` sandbox's
   surface only — the model never sees them; `:deferred` adds them to the
   model-facing table (undeclared, tagged `:exposure :deferred` by the source),
   where the selection settings and the declared/callable split resolve over
   them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.ai.llm :as llm]
            [kmet.app.config :as cfg]
            [kmet.app.loop :as loop]
            [kmet.app.loop.state :as state]
            [kmet.app.loop.test-support :as support]
            [kmet.app.registry :as registry]
            [kmet.app.session.core :as session]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.search :as search]
            [kmet.app.tools.tool :as tool]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(defn- handle []
  (registry/atom-registry))

(defn- tool-map
  "A minimal contributed tool named NAME."
  [name]
  {:name name :description name :execute (fn [_] {:content name})})

(defn- with-sources
  "Register SPECS — [id tools-fn opts] — on H, run F, then take them away."
  [h specs f]
  (doseq [[id tools-fn opts] specs]
    (tools/register-tool-source! h id tools-fn opts))
  (try (f)
       (finally (doseq [[id] specs]
                  (tools/unregister-tool-source! h id)))))

(t/deftest test-sandbox-source-lands-in-the-sandbox-surface-only
  ;; the default exposure is today's behaviour: callable from a script, never
  ;; in the model's table
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"sandbox-a" (tool-map "sandbox-a")}) {}]]
      (fn []
        (t/is (contains? (tools/get-contributed-tools h) "sandbox-a")
              "a :sandbox source's tools are in the sandbox surface")
        (t/is (not (contains? (tools/get-all-tools h) "sandbox-a"))
              "nothing selects or declares a sandbox tool")))))

(t/deftest test-deferred-source-joins-the-table-undeclared
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"deferred-a" (tool-map "deferred-a")})
                      {:exposure :deferred :description "A deferred catalog"}]]
      (fn []
        (let [tool (get (tools/get-all-tools h) "deferred-a")]
          (t/is tool "a :deferred source's tools are in the model-facing table")
          (t/is (= :deferred (:exposure tool)))
          (t/is (not (contains? (tools/get-contributed-tools h) "deferred-a"))
                "a deferred tool is not also a sandbox contribution"))))))

(t/deftest test-deferred-tool-keeps-the-map-key-as-its-name
  ;; a source may omit :name; the key is the address a selection and a
  ;; promotion use, so the record must carry it too
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"deferred-a" {:description "no name"}})
                      {:exposure :deferred}]]
      (fn []
        (t/is (= "deferred-a" (:name (get (tools/get-all-tools h) "deferred-a"))))))))

(t/deftest test-deferred-sources-filter-by-the-selected-tools
  (let [h (handle)]
    (with-sources h [[::one (fn [] {"a" (tool-map "a") "b" (tool-map "b")})
                      {:exposure :deferred :description "One"}]
                     [::two (fn [] {"c" (tool-map "c")})
                      {:exposure :deferred :description "Two"}]]
      (fn []
        (t/is (= [::one ::two] (mapv :id (tools/get-deferred-sources h))))
        (t/is (= [::one]
                 (mapv :id (tools/get-deferred-sources h {:selected #{"b"}}))))
        (t/is (= [::two]
                 (mapv :id (tools/get-deferred-sources h {:selected #{"c"}}))))
        (t/is (empty? (tools/get-deferred-sources h {:selected #{"missing"}})))))))

(t/deftest test-source-exposure-is-validated
  (let [h (handle)]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unknown tool-source exposure"
           (tools/register-tool-source! h ::catalog (fn [] {}) {:exposure :direct})))
    (t/is (empty? (tools/get-all-tools h))
          "a refused registration installs nothing")))

(t/deftest test-deferred-sources-are-reported-with-their-description
  ;; the listing names a source and one line — never its tools
  (let [h (handle)
        specs [[::one (fn [] {"a" (tool-map "a")})
                {:exposure :deferred :description "First catalog"}]
               [::two (fn [] {"b" (tool-map "b")})
                {:exposure :deferred :description "Second catalog"}]
               [::sandbox (fn [] {"c" (tool-map "c")}) {}]]]
    (with-sources h specs
      (fn []
        (t/is (= [{:id ::one :description "First catalog"}
                  {:id ::two :description "Second catalog"}]
                 (tools/get-deferred-sources h)))))))

(t/deftest test-source-registration-replaces
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"old" (tool-map "old")}) {:exposure :deferred}]]
      (fn []
        (tools/register-tool-source! h ::catalog (fn [] {"new" (tool-map "new")})
                                     {:exposure :deferred})
        (t/is (= #{"new"} (set (keys (tools/get-all-tools h)))))
        (t/is (empty? (tools/get-contributed-tools h)))))))

(t/deftest test-registered-tool-shadows-a-contributed-name
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"shared" (tool-map "shadowed")}) {:exposure :deferred}]]
      (fn []
        (tools/register-tool! h {:name "shared" :description "registered"
                                 :execute (fn [_] {:content "registered"})})
        (try
          (t/is (= "registered" (:description (tools/get-tool h "shared"))))
          (finally (tools/unregister-tool! h "shared")))))))

(t/deftest test-broken-source-contributes-nothing
  (let [h (handle)]
    (with-sources h [[::broken (fn [] (throw (ex-info "boom" {}))) {:exposure :deferred}]
                     [::bad-shape (fn [] [1 2 3]) {:exposure :deferred}]]
      (fn []
        (t/is (empty? (tools/get-all-tools h)))
        (t/is (= [{:id ::broken :description nil} {:id ::bad-shape :description nil}]
                 (tools/get-deferred-sources h))
              "a broken source is still a source the listing names")))))

(t/deftest test-built-in-tools-declare-no-exposure-but-the-search-tool
  ;; a tool written before exposures existed declares none and reads as
  ;; :direct, so its declaration is unchanged; tool_search is the one built-in
  ;; that names an exposure of its own
  (let [h (tu/tool-registry)]
    (doseq [[name tool] (tools/get-all-tools h)]
      (if (= name search/tool-search-name)
        (t/is (= :model-only (:exposure tool)))
        (t/is (nil? (:exposure tool)) name))
      (t/is (keyword? (tool/exposure tool)) name))))

(t/deftest test-promotable-tools-are-the-selection-over-deferred-and-contributed
  ;; one answer to "what can a search reach": the `:deferred` table tools
  ;; narrowed by the selection, plus the contributed `:sandbox` catalog, which
  ;; is not selectable — a direct tool is never a candidate, and a contributed
  ;; name the table holds keeps the table's record
  (let [h (handle)
        promotable (fn [selection]
                     (tools/promotable-tools (tools/get-all-tools h)
                                             (tools/get-contributed-tools h)
                                             selection))]
    (tools/register-tool! h (tool-map "direct"))
    (tools/register-tool! h (assoc (tool-map "deferred") :exposure :deferred))
    (tools/register-tool! h (assoc (tool-map "shared") :exposure :deferred))
    (tools/register-tool-source! h ::shadow (fn [] {"shared" {:description "contributed"}}))
    (with-sources h [[::catalog (fn [] {"contributed" (tool-map "contributed")}) {}]]
      (fn []
        (t/is (= #{"deferred" "shared" "contributed"}
                 (set (keys (promotable nil))))
              "a direct tool is never a candidate")
        (t/is (= #{"deferred" "contributed"}
                 (set (keys (promotable {:enabled #{"deferred"}}))))
              "the enabled allowlist narrows deferred tools; sandbox catalog stays")
        (t/is (= #{"contributed"} (set (keys (promotable {:enabled #{}}))))
              "an empty selection still reaches the sandbox catalog")
        (t/is (= "shared" (:description (get (promotable nil) "shared")))
              "a contributed name the table holds keeps the table's record")))))

;; ─── Declared and callable (the P5 split) ─────────────────────────────────

(t/use-fixtures :each (fn [f] (support/with-registry f)))

(defn- names [tools]
  (into #{} (map :name) tools))

(defn- temp-session []
  (let [dir (test-tmp/temp-dir! "test-tool-exposure-")]
    {:session (session/create-session dir) :dir dir}))

(defn- deferred-source! [h id description tools]
  (tools/register-tool-source! h id (fn [] tools)
                               {:exposure :deferred :description description}))

(defn- search-tool!
  "Mount the `tool_search` stand-in the declared-set derivations read by name."
  [h]
  (tools/register-tool! h {:name search/tool-search-name :exposure :model-only
                           :description "search" :execute (fn [_] {:content "x"})}))

(t/deftest test-deferred-tool-is-undeclared-until-loaded
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (not (contains? (names (state/active-tools agent)) "deferred-a"))
            "not declared before a load")
      (t/is (contains? (state/callable-tool-names agent) "deferred-a")
            "callable from a script before it is declared")
      (state/promote-tools! agent ["deferred-a"])
      (t/is (contains? (names (state/active-tools agent)) "deferred-a")
            "declared after the promotion")
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-model-only-is-declared-and-not-callable
  (let [h (support/tool-table)
        agent (support/make-agent-state)]
    (tools/register-tool! h {:name "probe" :exposure :model-only
                             :description "probe" :execute (fn [_] {:content "x"})})
    (try
      (t/is (contains? (names (state/active-tools agent)) "probe"))
      (t/is (not (contains? (state/callable-tool-names agent) "probe"))
            "a script may not call a model-only tool")
      (finally (tools/unregister-tool! h "probe")))))

(t/deftest test-tool-search-is-declared-only-when-a-deferred-tool-exists
  (let [h (support/tool-table)
        agent (support/make-agent-state)]
    (search-tool! h)
    (try
      (t/is (not (contains? (names (state/active-tools agent)) search/tool-search-name))
            "no deferred tool to find, so no schema to pay for")
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (tools/unregister-tool! h search/tool-search-name)))))

(t/deftest test-the-declared-set-is-name-sorted
  ;; the wire order must be a function of the names alone: past array-map size
  ;; a map's order is hash order, so a replay of recorded names could not
  ;; reproduce the array and a change could move entries it does not touch
  (let [h (support/tool-table)
        agent (support/make-agent-state)
        names (mapv #(str "probe-" %) (range 12))]
    (doseq [name (reverse names)]
      (tools/register-tool! h (tool-map name)))
    (try
      (let [declared (mapv :name (state/active-tools agent))]
        (t/is (= (vec (sort declared)) declared)
              "a table past array-map size declares its tools in name order")
        (t/is (= declared (mapv :name (state/active-tools agent)))
              "one table, one order, on every resolution")
        ;; a change moves only the entry it adds
        (tools/register-tool! h (tool-map "probe-5a"))
        (t/is (= declared (vec (remove #{"probe-5a"}
                                       (mapv :name (state/active-tools agent)))))
              "adding a tool leaves every other entry where it was"))
      (finally
        (doseq [name (concat names ["probe-5a"])]
          (tools/unregister-tool! h name))))))

(t/deftest test-a-deferred-tool-the-selection-excludes-declares-no-search
  ;; the gate reads the selection, not the raw table: a deferred tool no
  ;; selection can reach is not promotable, so the search schema buys nothing
  (let [h (support/tool-table)
        agent (support/make-agent-state)]
    (search-tool! h)
    (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
    (try
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name))
      (reset! (:active-tools agent) #{search/tool-search-name "read"})
      (t/is (not (contains? (names (state/active-tools agent)) search/tool-search-name))
            "the selection can reach no promotable tool")
      (reset! (:active-tools agent) #{search/tool-search-name "deferred-a"})
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name)
            "the deferred tool is selectable again")
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (tools/unregister-tool! h search/tool-search-name)))))

(t/deftest test-a-contributed-sandbox-tool-can-justify-the-search
  ;; a sandbox tool is never in the table, but a search can still promote one:
  ;; the gate reads the contributed catalog, not the raw table
  (let [h (support/tool-table)
        agent (support/make-agent-state)]
    (search-tool! h)
    (tools/register-tool-source! h ::sandbox
                                 (fn [] {"sb-a" {:description "sandbox a"}}))
    (try
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name))
      (reset! (:active-tools agent) #{search/tool-search-name})
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name)
            "a sandbox tool is not selectable, so a session selection cannot starve the search")
      (finally
        (tools/unregister-tool-source! h ::sandbox)
        (tools/unregister-tool! h search/tool-search-name)))))

(t/deftest test-disabling-a-loaded-tool-hides-it-without-clearing-the-load
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (state/promote-tools! agent ["deferred-a"])
      (t/is (contains? (names (state/active-tools agent)) "deferred-a"))
      (state/set-active-tools! agent #{"read"})
      (t/is (not (contains? (names (state/active-tools agent)) "deferred-a"))
            "a disabled tool is hidden, loaded or not")
      (t/is (= ["deferred-a"] (state/loaded-tools agent))
            "the load survives the disable")
      (state/set-active-tools! agent nil)
      (t/is (contains? (names (state/active-tools agent)) "deferred-a")
            "re-enabling restores it with no second search")
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-loaded-sandbox-tool-is-declared
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool-source! h ::sandbox (fn [] {"sb-a" {:description "sandbox a"}}))
      (t/is (not (contains? (names (state/active-tools agent)) "sb-a")))
      (state/promote-tools! agent ["sb-a"])
      (let [declared (into {} (map (juxt :name identity)) (state/active-tools agent))]
        (t/is (contains? declared "sb-a") "a loaded sandbox tool is declared")
        (t/is (= {:type "object" :properties {} :required []}
                 (:parameters (get declared "sb-a")))
              "and normalized like any declared tool"))
      (finally
        (tools/unregister-tool-source! h ::sandbox)
        (fs/delete-tree dir)))))

(t/deftest test-a-promotion-survives-resume-and-a-fork-taken-after-it
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (let [early (session/append-entry session {:role :user :content "hi"})]
        (state/promote-tools! agent ["deferred-a"])
        (t/is (contains? (names (state/active-tools agent)) "deferred-a"))
        (t/is (contains? (names (state/active-tools
                                 (support/make-agent-state :session session)))
                         "deferred-a")
              "a resumed session advertises what its branch recorded")
        (let [forked (session/fork-session session (:id (last @(:entries session))))]
          (t/is (contains? (names (state/active-tools
                                   (support/make-agent-state :session forked)))
                           "deferred-a")
                "a fork taken after the promotion keeps it"))
        (let [forked (session/fork-session session (:id early))]
          (t/is (not (contains? (names (state/active-tools
                                        (support/make-agent-state :session forked)))
                                "deferred-a"))
                "a fork taken before the promotion does not")))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-an-explicit-selection-does-not-hide-a-loaded-sandbox-tool
  ;; a sandbox tool is not in the table and is not selectable: a session
  ;; selection that excludes everything still leaves sandbox tools callable
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool-source! h ::sandbox (fn [] {"sb-a" {:description "sandbox a"}}))
      (state/promote-tools! agent ["sb-a"])
      (state/set-active-tools! agent #{"read"})
      (t/is (contains? (names (state/active-tools agent)) "sb-a")
            "sandbox tools stay callable under an explicit selection")
      (finally
        (tools/unregister-tool-source! h ::sandbox)
        (fs/delete-tree dir)))))

(t/deftest test-a-recorded-collision-keeps-the-table-selection
  ;; a sandbox source may contribute a name the table already holds; the table
  ;; record is the one used, so a model-facing exclusion still hides it
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool! h (tool-map "shared"))
      (tools/register-tool-source! h ::sandbox
                                   (fn [] {"shared" {:description "sandbox"}}))
      (state/sync-tool-declarations! agent)
      (t/is (contains? (names (state/active-tools agent)) "shared"))
      (state/set-active-tools! agent #{})
      (t/is (not (contains? (names (state/active-tools agent)) "shared"))
            "the table tool's exclusion wins over the sandbox collision")
      (finally
        (tools/unregister-tool-source! h ::sandbox)
        (tools/unregister-tool! h "shared")
        (fs/delete-tree dir)))))

(t/deftest test-a-search-promotes-a-tool-into-the-next-request
  ;; the whole P5 path through the loop: the model calls tool_search, the run
  ;; records the load in the session, and the next request declares the tool —
  ;; the request before it did not
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)
        requests (atom [])
        calls (atom 0)]
    (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
    (tools/register-tool! h (search/definition h))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [o]
                      (swap! requests conj (into #{} (map :name) (:tools o)))
                      (future
                        (if (= 1 (swap! calls inc))
                          (do ((:on-tool-call o) {:id "tc1"
                                                  :name search/tool-search-name
                                                  :arguments "{\"query\":\"deferred_a tool\"}"
                                                  :index 0})
                              ((:on-done o) :tool-calls))
                          (do ((:on-text o) "found it")
                              ((:on-done o) "stop")))
                        :done))]
        @(loop/run-agent-turn agent {:message "find the tool"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (not (contains? (first @requests) "deferred-a"))
            "the first request does not declare the deferred tool")
      (t/is (contains? (first @requests) search/tool-search-name)
            "tool_search is declared because a deferred tool exists")
      (t/is (contains? (second @requests) "deferred-a")
            "the promotion declares it on the very next request")
      (t/is (= ["deferred-a"] (state/loaded-tools agent)))
      (let [msgs @(:messages agent)
            tool-idx (last (keep-indexed (fn [i m]
                                           (when (= :tool (:role m)) i))
                                         msgs))
            change-idx (last (keep-indexed (fn [i m]
                                             (when (= :tool-change (:role m)) i))
                                           msgs))]
        (t/is (> change-idx tool-idx)
              "the promotion marker follows the tool result, not the call"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (tools/unregister-tool! h search/tool-search-name)
        (fs/delete-tree dir)))))

;; ─── The deferred-source listing ──────────────────────────────────────────

(defn- listings [sess]
  (filter #(and (= :custom-message (:role %))
                (= session/tool-sources-custom-type (:custom-type %)))
          (session/get-branch sess)))

(t/deftest test-a-deferred-source-is-named-once-in-an-appended-message
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (t/is (nil? (state/sync-tool-sources-listing! agent))
            "no deferred source, nothing to say — and no message")
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (let [msg (state/sync-tool-sources-listing! agent)
            content (:content msg)]
        (t/is (some? msg))
        (t/is (str/includes? content "1 deferred tool source"))
        (t/is (str/includes? content "- catalog: A catalog"))
        (t/is (str/includes? content "tool_search"))
        (t/is (not (str/includes? content "run_code or eval"))
              "only tool_search is selected")
        (t/is (not (str/includes? content "(tools/list)")))
        (t/is (false? (:display msg)) "the model reads it, not the chat"))
      (t/is (nil? (state/sync-tool-sources-listing! agent))
            "the same set is not announced twice")
      (t/is (= 1 (count (listings session))))
      ;; a promotion is not a source change: nothing appended
      (state/promote-tools! agent ["deferred-a"])
      (t/is (nil? (state/sync-tool-sources-listing! agent)))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-script-tool-selects-the-script-intro
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names ["run_code" "deferred-a"]))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (let [msg (state/sync-tool-sources-listing! agent)
            content (:content msg)]
        (t/is (some? msg))
        (t/is (str/includes? content "run_code"))
        (t/is (not (str/includes? content "eval")))
        (t/is (str/includes? content "(tools/list)"))
        (t/is (not (str/includes? content "tool_search"))))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-an-eval-only-tool-selects-the-eval-intro
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names ["eval" "deferred-a"]))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (let [msg (state/sync-tool-sources-listing! agent)
            content (:content msg)]
        (t/is (some? msg))
        (t/is (str/includes? content "eval"))
        (t/is (not (str/includes? content "run_code")))
        (t/is (str/includes? content "(tools/list)"))
        (t/is (not (str/includes? content "tool_search"))))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-both-discovery-paths-are-announced
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state
               :session session
               :enabled-tool-names (support/tool-names ["run_code" "eval" "tool_search" "deferred-a"]))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (let [msg (state/sync-tool-sources-listing! agent)
            content (:content msg)]
        (t/is (some? msg))
        (t/is (str/includes? content "run_code or eval"))
        (t/is (str/includes? content "(tools/list)"))
        (t/is (str/includes? content "tool_search")))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-no-discovery-path-keeps-the-listing-silent
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names []))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (nil? (state/sync-tool-sources-listing! agent)))
      (t/is (empty? (listings session)))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-an-unselected-deferred-source-is-not-listed
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session
                                        :enabled-tool-names (support/tool-names ["run_code"]))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (nil? (state/sync-tool-sources-listing! agent))
            "a script tool without the deferred tool has nothing to announce")
      (t/is (empty? (listings session)))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-stale-listing-is-corrected-when-nothing-can-reach-it
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        reachable (support/make-agent-state :session session)
        unreachable (support/make-agent-state
                     :session session
                     :enabled-tool-names (support/tool-names ["deferred-a"]))]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (some? (state/sync-tool-sources-listing! reachable)))
      (let [msg (state/sync-tool-sources-listing! unreachable)
            content (:content msg)]
        (t/is (some? msg))
        (t/is (str/includes? content "not reachable"))
        (t/is (not (str/includes? content "callable"))))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-changed-description-is-appended-again
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "First line" {"a" (tool-map "a")})
      (state/sync-tool-sources-listing! agent)
      (deferred-source! h ::catalog "Second line" {"a" (tool-map "a")})
      (t/is (some? (state/sync-tool-sources-listing! agent))
            "a description change is worth a new message")
      (t/is (= 2 (count (listings session))))
      (t/is (str/includes? (:content (last (listings session))) "Second line"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-a-vanished-source-set-says-so-once
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "A catalog" {"a" (tool-map "a")})
      (state/sync-tool-sources-listing! agent)
      (tools/unregister-tool-source! h ::catalog)
      (t/is (str/includes? (:content (state/sync-tool-sources-listing! agent))
                           "No tool sources are deferred.")
            "the stale listing is corrected instead of left standing")
      (t/is (nil? (state/sync-tool-sources-listing! agent))
            "and corrected once")
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-the-listing-prefix-is-not-rewritten
  ;; gate 6: the leading system prompt is untouched — the listing is one
  ;; appended entry, so a source appearing costs no prefix miss
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)
        system-before @(:system agent)]
    (try
      (deferred-source! h ::catalog "A catalog" {"a" (tool-map "a")})
      (state/sync-tool-sources-listing! agent)
      (t/is (= system-before @(:system agent)))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (fs/delete-tree dir)))))

(t/deftest test-the-listing-is-bounded
  ;; a fleet of deferred sources must not push an unbounded message into the
  ;; conversation. Being listed is the point of the message, so descriptions
  ;; shrink first and whole sources drop only when their head lines alone do not
  ;; fit (pi's per-server budget, then the `… N more servers` line).
  (let [listing (fn [sources] (session/deferred-sources-listing sources))
        small [{:id :clojure :description "Clojure editing refinements"}]
        long-descriptions (mapv (fn [i] {:id (keyword (str "server" i))
                                         :description (apply str (repeat 250 "z"))})
                                (range 16))
        long-ids (mapv (fn [i] {:id (keyword (str "a-very-long-source-identifier-" i))
                                :description "a description"})
                       (range 400))]
    (t/is (= (str "1 deferred tool source. Their tools are callable from a script (run_code or eval);"
                  " (tools/list) shows them, and tool_search loads one tool so you can call it directly.\n\n"
                  "- clojure: Clojure editing refinements")
             (listing small))
          "a small set is the uncapped text, byte for byte")
    (let [long (listing [{:id :big :description (apply str (repeat 600 "x"))}])]
      (t/is (<= (count long) 4096))
      (t/is (str/ends-with? long "…")
            "a description over the per-source cap elides")
      (t/is (str/includes? long "- big: ")))
    (let [text (listing long-descriptions)]
      (t/is (<= (count text) 4096) "the whole listing stays under the cap")
      (t/is (not (str/includes? text "more source"))
            "descriptions shrink before a source is dropped")
      (t/is (= 16 (count (filter #(str/starts-with? % "- server") (str/split-lines text))))
            "every source is still named"))
    (let [text (listing long-ids)]
      (t/is (<= (count text) 4096))
      (t/is (str/includes? (last (str/split-lines text)) "more sources; find their tools")
            "only when the head lines alone do not fit are the rest counted in one line")
      (t/is (= text (listing long-ids)) "and the text is deterministic"))
    (t/is (= (listing []) "No tool sources are deferred."))))

(t/deftest test-every-discovery-path-combination
  (let [source {:id :symbols :description "Symbols"}
        listing (fn [opts] (session/deferred-sources-listing [source] opts))
        cases [[{} nil false]
               [{:run-code? true} "run_code" false]
               [{:eval? true} "eval" false]
               [{:tool-search? true} nil true]
               [{:run-code? true :eval? true} "run_code or eval" false]
               [{:run-code? true :tool-search? true} "run_code" true]
               [{:eval? true :tool-search? true} "eval" true]
               [{:run-code? true :eval? true :tool-search? true}
                "run_code or eval" true]]]
    (doseq [[opts label search?] cases]
      (let [text (listing opts)]
        (if label
          (do
            (t/is (str/includes? text (str "script (" label ")")))
            (t/is (= search? (str/includes? text "tool_search")))
            (when (= label "run_code")
              (t/is (not (str/includes? text "eval"))))
            (when (= label "eval")
              (t/is (not (str/includes? text "run_code")))))
          (if search?
            (t/is (str/includes? text "tool_search"))
            (t/is (nil? text))))))))

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
    (tools/register-tool! h {:name search/tool-search-name :exposure :model-only
                             :description "search" :execute (fn [_] {:content "x"})})
    (try
      (t/is (not (contains? (names (state/active-tools agent)) search/tool-search-name))
            "no deferred tool to find, so no schema to pay for")
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (t/is (contains? (names (state/active-tools agent)) search/tool-search-name))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (tools/unregister-tool! h search/tool-search-name)))))

(t/deftest test-disabling-a-loaded-tool-hides-it-without-clearing-the-load
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (deferred-source! h ::catalog "A catalog" {"deferred-a" (tool-map "deferred-a")})
      (state/promote-tools! agent ["deferred-a"])
      (t/is (contains? (names (state/active-tools agent)) "deferred-a"))
      (reset! (:enabled-tools agent) #{"read"})
      (t/is (not (contains? (names (state/active-tools agent)) "deferred-a"))
            "a disabled tool is hidden, loaded or not")
      (t/is (= ["deferred-a"] (state/loaded-tools agent))
            "the load survives the disable")
      (reset! (:enabled-tools agent) nil)
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

(t/deftest test-an-explicit-selection-hides-a-loaded-sandbox-tool
  ;; a sandbox tool is not in the table, so the enabled set is the only way to
  ;; say "not this one" — it must hide, like a disabled deferred tool does
  (let [h (support/tool-table)
        {:keys [session dir]} (temp-session)
        agent (support/make-agent-state :session session)]
    (try
      (tools/register-tool-source! h ::sandbox (fn [] {"sb-a" {:description "sandbox a"}}))
      (state/promote-tools! agent ["sb-a"])
      (reset! (:enabled-tools agent) #{"read"})
      (t/is (not (contains? (names (state/active-tools agent)) "sb-a")))
      (reset! (:enabled-tools agent) #{"read" "sb-a"})
      (t/is (contains? (names (state/active-tools agent)) "sb-a"))
      (finally
        (tools/unregister-tool-source! h ::sandbox)
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
      (let [msg (state/sync-tool-sources-listing! agent)]
        (t/is (some? msg))
        (t/is (str/includes? (:content msg) "1 deferred tool source"))
        (t/is (str/includes? (:content msg) "- catalog: A catalog"))
        (t/is (str/includes? (:content msg) "tool_search"))
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
    (t/is (= (str "1 deferred tool source. Their tools are callable from run_code now;"
                  " tool_search loads one so you can call it directly.\n\n"
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

(ns kmet.app.test-tool-search
  "`tool_search`: rank the searchable tools that are not declared yet, load
   every match (eight by default), and reply in pi's shape. The promoted names
   are the run's `:tool-load` record (see `kmet.app.loop.state`), so the call
   itself only has to hand them to `*run*`'s `:load!`."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.search :as search]))

(defn- handle []
  (registry/atom-registry))

(defn- deferred-source!
  "Register ID's catalog — NAMES, each described by its own name — as a
   deferred source."
  [h id description names]
  (tools/register-tool-source! h id
                               (fn []
                                 (into {} (map (fn [name]
                                                 [name {:description (str name " tool")}]))
                                       names))
                               {:exposure :deferred :description description}))

(defn- with-run
  "Run F with `*run*` bound to a fake run whose declared set is a live atom —
   the loop's thunk in miniature, so a promotion is visible to the next call.
   SELECTION is the run's tool selection (`*run*`'s `:selection`); nil = none."
  ([declared f] (with-run declared nil f))
  ([declared selection f]
   (binding [search/*run* {:declared (fn [] @declared)
                           :selection (fn [] selection)
                           :load! (fn [names] (swap! declared into names))}]
     (f))))

(defn- run-search [h args]
  ((:execute (search/definition h)) args))

(t/deftest test-it-loads-every-match-and-skips-what-is-declared
  (let [h (handle)
        declared (atom #{})]
    (deferred-source! h ::catalog "Repo tools" ["repo_search" "issue_create"])
    (try
      (let [r (with-run declared #(run-search h {:query "repo_search tool"}))]
        (t/is (= ["repo_search"] (:loaded (:details r))))
        (t/is (str/includes? (:content r) "Loaded 1 tool.")
              "one match, singular")
        (t/is (str/includes? (:content r) "- repo_search: repo_search tool")
              "pi's line shape: name, then the description's first line")
        (t/is (= #{"repo_search"} @declared) "the match was loaded")
        ;; the next call sees it declared and does not rank it again
        (t/is (= [] (:loaded (:details (with-run declared #(run-search h {:query "repo_search tool"})))))
              "a declared tool is not a candidate"))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-it-reaches-a-sandbox-source-too
  ;; a :sandbox tool is callable but never declared; loading one declares it
  (let [h (handle)
        declared (atom #{})]
    (tools/register-tool-source! h ::sandbox
                                 (fn [] {"browser_shot" {:description "Take a screenshot of a page"}}))
    (try
      (let [r (with-run declared #(run-search h {:query "screenshot"}))]
        (t/is (= ["browser_shot"] (:loaded (:details r))))
        (t/is (= #{"browser_shot"} @declared)))
      (finally (tools/unregister-tool-source! h ::sandbox)))))

(t/deftest test-eight-matches-by-default
  (let [h (handle)
        declared (atom #{})
        names (mapv #(str "widget_" %) (range 10))]
    (deferred-source! h ::catalog "Widget tools" names)
    (try
      (let [r (with-run declared #(run-search h {:query "widget tool"}))]
        (t/is (= search/default-limit (count (:loaded (:details r)))))
        (t/is (= search/default-limit (count @declared))))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-an-explicit-limit-caps-the-load
  (let [h (handle)
        declared (atom #{})]
    (deferred-source! h ::catalog "Widget tools" ["widget_a" "widget_b" "widget_c"])
    (try
      (let [r (with-run declared #(run-search h {:query "widget tool" :limit 2}))]
        (t/is (= 2 (count (:loaded (:details r)))))
        (t/is (= 2 (count @declared))))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-no-match-says-so-and-loads-nothing
  (let [h (handle)
        declared (atom #{})]
    (deferred-source! h ::catalog "Widget tools" ["widget_a"])
    (try
      (let [r (with-run declared #(run-search h {:query "screenshot"}))]
        (t/is (= "No matching tools found." (:content r)))
        (t/is (= [] (:loaded (:details r))))
        (t/is (empty? @declared)))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-a-blank-query-is-an-error
  (doseq [query [nil "" "   "]]
    (let [r (run-search (handle) {:query query})]
      (t/is (:is-error r))
      (t/is (= "query must not be empty" (:content r))))))

(t/deftest test-a-bad-limit-is-an-error
  (doseq [limit [0 -1 1.5 "8"]]
    (t/is (:is-error (run-search (handle) {:query "x" :limit limit}))
          (str "limit " (pr-str limit)))))

(t/deftest test-outside-a-run-nothing-is-loaded
  ;; a match that cannot be recorded is not a load: pi's `options.tools` absent
  (let [h (handle)]
    (deferred-source! h ::catalog "Widget tools" ["widget_a"])
    (try
      (binding [search/*run* nil]
        (let [r (run-search h {:query "widget tool"})]
          (t/is (= "No matching tools found." (:content r)))
          (t/is (= [] (:loaded (:details r))))))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-the-tool-is-model-only-with-a-static-description
  ;; a script must not change what the model sees mid-script, and registering a
  ;; source must not rewrite a cached schema
  (let [h (handle)
        before (search/definition h)]
    (deferred-source! h ::catalog "Widget tools" ["widget_a"])
    (try
      (let [after (search/definition h)]
        (t/is (= :model-only (:exposure before)))
        (t/is (= (:description before) (:description after)))
        (t/is (not (str/includes? (:description after) "widget_a")))
        (t/is (= search/tool-search-name (:name after))))
      (finally (tools/unregister-tool-source! h ::catalog)))))

(t/deftest test-the-selection-narrows-the-candidates
  ;; a search may only promise what the wire would declare: a deferred or
  ;; contributed sandbox tool the run's selection leaves out is not a
  ;; candidate — the same `registry/promotable-tools` the discovery gate reads
  (let [h (handle)
        declared (atom #{})]
    (deferred-source! h ::catalog "Widget tools" ["widget_a" "widget_b"])
    (tools/register-tool-source! h ::sandbox
                                 (fn [] {"browser_shot" {:description "Take a screenshot of a page"}}))
    (try
      (let [r (with-run declared {:enabled #{"widget_a" "browser_shot"}}
                #(run-search h {:query "widget tool"}))]
        (t/is (= ["widget_a"] (:loaded (:details r)))
              "widget_b is excluded by the selection")
        (t/is (= #{"widget_a"} @declared)))
      (let [r (with-run declared {:enabled #{"widget_a"}}
                #(run-search h {:query "screenshot"}))]
        (t/is (= "No matching tools found." (:content r))
              "a sandbox tool outside the selection is not a candidate"))
      (finally
        (tools/unregister-tool-source! h ::catalog)
        (tools/unregister-tool-source! h ::sandbox)))))

(t/deftest test-a-parameter-name-is-searchable
  ;; the schema text joins the searchable document (pi: schemaText), so a tool
  ;; found by its argument is discoverable
  (let [h (handle)
        declared (atom #{})
        catalog (fn []
                  {"repo_clone"
                   {:description "Clone something"
                    :parameters {:type "object"
                                 :properties
                                 {"repository_url"
                                  {:type "string"
                                   :description "The url to clone"}}}}})]
    (tools/register-tool-source! h ::catalog catalog
                                 {:exposure :deferred :description "Repo tools"})
    (try
      (t/is (= ["repo_clone"]
               (:loaded (:details
                         (with-run declared #(run-search h {:query "clone url"}))))))
      (finally (tools/unregister-tool-source! h ::catalog)))))

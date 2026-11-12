(ns kmet.app.test-eval-manifest
  "The `:eval-library` prompt manifest: a bounded, deterministic listing of the
   session's loaded eval definitions, or of the files saved for its first eval.
   It is appended once per change, never builds an environment, and never
   invokes a source."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval :as eval-service]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.loop.state :as state]
            [kmet.app.loop.test-support :as loop-support]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.test-tmp :as test-tmp]))

(defn- text-of
  [content]
  (if (string? content)
    content
    (str/join "\n" (map :text content))))

(defn- agent-for
  ([env] (agent-for env ["eval"]))
  ([env entries]
   (loop-support/make-agent-state
    :ctx (:ctx env)
    :session (:session env)
    :enabled-tool-names (loop-support/tool-names entries))))

(defn- listings [sess]
  (filter #(and (= :custom-message (:role %))
                (= session/eval-library-custom-type (:custom-type %)))
          (session/get-branch sess)))

(defn- without-building
  "Run F with environment construction disabled — the prompt manifest must read
   the disk index, never build one."
  [f]
  (with-redefs-fn {#'eval-service/build-environment
                   (fn [& _]
                     (throw (ex-info "must not build" {:type :test})))}
    f))

(defn- write-definition!
  "Write NAME's definition file directly, as a sibling session or a previous
   process would have; the service's environment stays unbuilt."
  [env scope name source]
  (eval-files/write-definition!
   (case scope
     :global (support/global-root env)
     :cwd (support/cwd-root env))
   {:name name
    :ns (namespace (symbol name))
    :kind :defn
    :source source}))

(t/deftest the-listing-is-deterministic-and-bounded
  (let [entry {:name "user/report" :scope :cwd :active true
               :arglists '([x]) :doc "Render x.\nMore detail."}
        manifest {:loaded? true :entries [entry]}
        text (session/eval-library-listing manifest)]
    (t/is (= text (session/eval-library-listing manifest)))
    (t/is (str/includes? text "- report ([x]) (cwd) — Render x."))
    (t/is (not (str/includes? text "More detail."))
          "only the first doc line is listed")
    (t/is (not (str/includes? text "(help)"))
          "an unbounded saved list needs no discovery pointer"))
  (let [saved (session/eval-library-listing
               {:loaded? false
                :entries [{:name "user/a" :scope :global :active true
                           :arglists '([]) :doc "Saved."}]})]
    (t/is (str/includes? saved "1 saved definition"))
    (t/is (str/includes? saved "first eval loads them"))
    (t/is (not (str/includes? saved "loaded definition")))
    (t/is (str/includes? saved "- a ([]) (global)"))
    (t/is (str/includes?
           (session/eval-library-listing
            {:loaded? true
             :entries [{:name "user/plain" :scope :cwd :active true}]})
           "- plain (cwd)")))
  (t/is (str/includes?
         (session/eval-library-listing
          {:loaded? true
           :entries [{:name "user/shadowed" :scope :global :active false
                      :arglists '([x]) :doc "Old."}]})
         "- shadowed ([x]) (global, shadowed)"))
  (let [empty-text (session/eval-library-listing {:loaded? true :entries []})]
    (t/is (str/includes? empty-text "Library: empty.")))
  (let [live-text (session/eval-library-listing
                   {:loaded? true :entries []
                    :session-only [{:name "user/live" :scope :cwd :active true}]})]
    (t/is (str/includes? live-text "0 saved (0 loaded), 1 session-only"))
    (t/is (str/includes? live-text "(help :library)")))
  (let [entries (mapv (fn [i]
                        {:name (str "user/definition-" i)
                         :scope :cwd
                         :active true
                         :arglists '([x])
                         :doc (apply str (repeat 250 "z"))})
                      (range 400))
        text (session/eval-library-listing {:loaded? true :entries entries})]
    (t/is (<= (count text) 4096))
    (t/is (str/includes? text "more; (library)"))
    (t/is (not (str/includes? text "user/definition-399"))))
  (let [text (session/eval-library-listing
              {:loaded? true
               :entries []
               :sources [{:id :mcp
                          :description "MCP tools: search and call."}]})]
    (t/is (str/includes? text "Library: empty."))
    (t/is (str/includes? text "Sources: 1 mounted"))
    (t/is (str/includes? text "- mcp: MCP tools: search and call.")))
  (let [sources (mapv (fn [i]
                        {:id (keyword (str "source-" i))
                         :description (apply str (repeat 200 "z"))})
                      (range 20))
        text (session/eval-library-listing
              {:loaded? true :entries [] :sources sources})]
    (t/is (<= (count text) 4096))
    (t/is (str/includes? text "Sources: 20 mounted"))))

(t/deftest a-live-only-definition-is-counted
  (let [env (support/eval-ctx)
        agent (agent-for env)]
    (support/eval! env "(defn live [] :ok)")
    (let [message (state/sync-eval-library-listing! agent)]
      (t/is (some? message))
      (t/is (str/includes? (:content message) "1 session-only")))))

(t/deftest a-saved-library-is-announced-once-and-corrected
  (let [env (support/eval-ctx)
        agent (agent-for env)]
    (write-definition! env :cwd "user/a" "(defn a [] 1)")
    (let [first (state/sync-eval-library-listing! agent)]
      (t/is (some? first))
      (t/is (str/includes? (:content first) "1 saved definition"))
      (t/is (str/includes? (text-of (:content first)) "- a ([])"))
      (t/is (false? (:display first)))
      (t/is (false? (get-in first [:details :loaded?])))
      (t/is (= 1 (get-in first [:details :count]))))
    (t/is (nil? (state/sync-eval-library-listing! agent))
          "an unchanged library is not announced twice")
    (t/is (= 1 (count (listings (:session env)))))
    (write-definition! env :cwd "user/b" "(defn b [] 2)")
    (let [second (state/sync-eval-library-listing! agent)]
      (t/is (some? second) "a changed set is announced again")
      (t/is (str/includes? (:content second) "2 saved definitions"))
      (t/is (str/includes? (text-of (:content second)) "- b ([])")))
    (fs/delete (support/library-file env :cwd "user" "a.clj"))
    (fs/delete (support/library-file env :cwd "user" "b.clj"))
    (t/is (str/includes? (:content (state/sync-eval-library-listing! agent))
                         "Library: empty.")
          "a vanished set is corrected instead of left standing")
    (t/is (nil? (state/sync-eval-library-listing! agent))
          "and corrected once")))

(t/deftest a-session-lists-saved-before-its-first-eval-and-loaded-after
  (let [env (support/eval-ctx)
        agent (agent-for env)]
    (write-definition! env :cwd "user/saved" "(defn saved [x] \"Saved fn.\" x)")
    (let [saved (state/sync-eval-library-listing! agent)]
      (t/is (some? saved))
      (t/is (str/includes? (:content saved) "saved"))
      (t/is (str/includes? (:content saved) "first eval"))
      (t/is (not (str/includes? (:content saved) "loaded definition"))))
    (let [result (support/eval! env "(+ 1 2)")]
      (t/is (str/includes? (:content result)
                           "[eval: loaded 1 library definition: user/saved (cwd)]"))
      (t/is (= ["user/saved"] (mapv :name (get-in result [:details :loaded])))))
    (let [loaded (state/sync-eval-library-listing! agent)]
      (t/is (some? loaded))
      (t/is (str/includes? (:content loaded) "saved (1 loaded)"))
      (t/is (str/includes? (text-of (:content loaded)) "- saved ([x])"))
      (t/is (true? (get-in loaded [:details :loaded?]))))
    (t/is (nil? (state/sync-eval-library-listing! agent)))))

(t/deftest the-first-eval-reports-load-failures
  (let [env (support/eval-ctx)
        path (support/library-file env :cwd "user" "broken.clj")]
    (fs/create-dirs (fs/parent path))
    (spit path "(defn broken [")
    (let [result (support/eval! env "(+ 1 2)")]
      (t/is (str/includes? (:content result) "[eval: load failed cwd"))
      (t/is (str/includes? (:content result) "broken.clj"))
      (t/is (= 1 (count (get-in result [:details :failures])))))
    (let [second (support/eval! env "(+ 2 3)")]
      (t/is (not (str/includes? (:content second) "[eval: load failed"))
            "a build failure is reported once"))))

(t/deftest the-first-eval-bounds-the-failure-report
  (let [env (support/eval-ctx)]
    (doseq [i (range 12)]
      (let [path (support/library-file env :cwd "user" (str "broken-" i ".clj"))]
        (fs/create-dirs (fs/parent path))
        (spit path "(defn broken [")))
    (let [result (support/eval! env "(+ 1 2)")]
      (t/is (= 12 (count (get-in result [:details :failures]))))
      (t/is (str/includes? (:content result) "2 more load failures")))))

(t/deftest the-first-eval-bounds-the-loaded-preview
  (let [env (support/eval-ctx)]
    (dotimes [i 13]
      (write-definition! env :cwd (str "user/a" i)
                         (str "(defn a" i " [] 1)")))
    (let [result (support/eval! env "(+ 1 2)")]
      (t/is (= 12 (count (get-in result [:details :loaded]))))
      (t/is (= 13 (get-in result [:details :loaded-count])))
      (t/is (str/includes? (:content result)
                           "1 more; (library) for all")))))

(t/deftest the-saved-manifest-cache-tracks-the-files
  (let [env (support/eval-ctx)
        agent (agent-for env)
        calls (atom 0)
        original eval-files/index]
    (write-definition! env :cwd "user/a" "(defn a [] 1)")
    (with-redefs [eval-files/index (fn [roots]
                                     (swap! calls inc)
                                     (original roots))]
      (t/is (some? (state/sync-eval-library-listing! agent)))
      (t/is (nil? (state/sync-eval-library-listing! agent)))
      (t/is (= 1 @calls) "an unchanged library is not re-parsed")
      (write-definition! env :cwd "user/b" "(defn b [] 2)")
      (t/is (some? (state/sync-eval-library-listing! agent)))
      (t/is (= 2 @calls) "a changed library is re-read"))))

(t/deftest an-unreadable-library-root-does-not-break-the-manifest
  (let [env (support/eval-ctx)]
    (fs/create-dirs (support/cwd-root env))
    (with-redefs [fs/glob (fn [& _]
                            (throw (ex-info "unreadable" {:type :test})))]
      (t/is (nil? (state/sync-eval-library-listing! (agent-for env)))))))

(t/deftest an-errored-first-eval-still-reports-the-loaded-set
  (let [env (support/eval-ctx)]
    (write-definition! env :cwd "user/saved" "(defn saved [] 1)")
    (let [result (support/eval! env "(throw (ex-info \"boom\" {}))")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result)
                           "[eval: loaded 1 library definition: user/saved (cwd)]"))
      (t/is (= ["user/saved"] (mapv :name (get-in result [:details :loaded])))))))

(t/deftest a-cross-session-save-is-not-advertised-as-live
  (let [env (support/eval-ctx)
        built (agent-for env)]
    (support/eval! env "(+ 1 2)")
    (write-definition! env :cwd "user/later" "(defn later [] 1)")
    (t/is (nil? (state/sync-eval-library-listing! built))
          "the loaded environment does not pick up another session's file")
    (t/is (empty? (listings (:session env))))
    (let [other (session/create-session (test-tmp/temp-dir! "eval-manifest-")
                                        {:cwd (:cwd env)})
          other-agent (loop-support/make-agent-state
                       :ctx (:ctx env)
                       :session other
                       :enabled-tool-names (loop-support/tool-names ["eval"]))
          saved (state/sync-eval-library-listing! other-agent)]
      (t/is (some? saved))
      (t/is (str/includes? (:content saved) "saved"))
      (t/is (str/includes? (text-of (:content saved)) "- later ([])")
            "a session without an environment lists the disk as saved"))))

(t/deftest the-manifest-requires-eval-to-be-selected
  (let [env (support/eval-ctx)]
    (write-definition! env :cwd "user/a" "(defn a [] 1)")
    (doseq [entries [[] ["codemode"]]]
      (let [agent (agent-for env entries)]
        (t/is (nil? (state/sync-eval-library-listing! agent))
              (str "entries " (pr-str entries)))
        (t/is (empty? (listings (:session env))))))
    (t/is (some? (state/sync-eval-library-listing! (agent-for env))))))

(t/deftest the-manifest-never-builds-an-environment-or-invokes-a-source
  (let [env (support/eval-ctx)
        calls (atom 0)
        _ (tools/register-eval-source! (:tools env) ::counted
                                       (fn [_]
                                         (swap! calls inc)
                                         {})
                                       {:description "Counted source."})]
    (try
      ;; the build itself reads sources; the manifest must not
      (support/eval! env "(+ 1 2)")
      (reset! calls 0)
      (let [agent (agent-for env)
            message (without-building #(state/sync-eval-library-listing! agent))]
        (t/is (some? message) "the mounted source is announced")
        (t/is (str/includes? (:content message) "- counted: Counted source."))
        (t/is (zero? @calls) "the registration description needs no source call"))
      (let [other (session/create-session (test-tmp/temp-dir! "eval-manifest-")
                                          {:cwd (:cwd env)})
            other-agent (loop-support/make-agent-state
                         :ctx (:ctx env)
                         :session other
                         :enabled-tool-names (loop-support/tool-names ["eval"]))]
        (write-definition! env :cwd "user/saved" "(defn saved [] 1)")
        (let [message (without-building #(state/sync-eval-library-listing! other-agent))]
          (t/is (some? message) "the saved view reads the disk index")
          (t/is (str/includes? (text-of (:content message)) "- saved ([])"))
          (t/is (str/includes? (:content message) "- counted: Counted source.")))
        (t/is (zero? @calls)
              "the saved view never invokes a source"))
      (finally
        (tools/unregister-eval-source! (:tools env) ::counted)))))

(t/deftest a-source-removal-is-announced
  (let [env (support/eval-ctx)
        agent (agent-for env)]
    (tools/register-eval-source! (:tools env) ::counted
                                 (fn [_] {})
                                 {:description "Counted source."})
    (let [listed (state/sync-eval-library-listing! agent)]
      (t/is (str/includes? (:content listed) "Sources: 1 mounted")))
    (tools/unregister-eval-source! (:tools env) ::counted)
    (let [corrected (state/sync-eval-library-listing! agent)]
      (t/is (some? corrected))
      (t/is (str/includes? (:content corrected) "Library: empty."))
      (t/is (not (str/includes? (:content corrected) "Counted source"))))))

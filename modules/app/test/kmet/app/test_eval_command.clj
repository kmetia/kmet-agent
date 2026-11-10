(ns kmet.app.test-eval-command
  "The `/eval` command: list, search, describe, move, drop, sources, help,
   source verbs and registration lifetime."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.commands :as commands]
            [kmet.app.commands.builtins :as builtins]
            [kmet.app.commands.eval :as eval-command]
            [kmet.app.eval :as eval-service]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(t/use-fixtures :each tu/with-clean-app-registries)

(defn- eval-env
  ([] (eval-env {}))
  ([{:keys [service-commands service-sources service-failures]}]
   (let [root (srce/root {:name :test-eval-command})
         cwd (test-tmp/temp-dir! "eval-command-project-")
         agent-dir (test-tmp/temp-dir! "eval-command-agent-")
         service (cond-> (eval-service/value root {:agent-dir agent-dir})
                   service-commands
                   (assoc :source-commands (constantly service-commands))
                   service-sources
                   (assoc :sources (constantly service-sources))
                   service-failures
                   (assoc :failures (constantly service-failures)))
         sess (session/create-session (test-tmp/temp-dir! "eval-command-session-")
                                      {:cwd cwd})]
     (srce/provide! root :eval service)
     {:root root
      :cwd cwd
      :agent-dir agent-dir
      :service service
      :session sess
      :library-root (str (fs/path agent-dir "eval"))
      :cwd-root (str (fs/path agent-dir "eval-scopes"
                              (session/encode-cwd cwd)))
      :engine {:agent-state (atom {:ctx root})
               :session (atom sess)
               :cwd (atom cwd)}})))

(defn- run [env args]
  (let [got (atom [])]
    ((:handler eval-command/command)
     {:runtime (:engine env)
      :ui {:info (fn [label content] (swap! got conj [:info label content]))
           :warning (fn [text] (swap! got conj [:warning text]))}}
     args)
    @got))

(defn- write-def! [env record]
  (eval-files/write-definition! (:cwd-root env)
                                (merge {:ns "user" :kind :defn} record)))

(t/deftest list-with-an-empty-library
  (let [env (eval-env)
        [[level label content]] (run env "")]
    (t/is (= :info level))
    (t/is (= "Eval" label))
    (t/is (= "### Eval library\n\n*No eval definitions.*" content))))

(t/deftest list-shows-and-filters-definitions
  (let [env (eval-env)]
    (write-def! env {:name "user/report"
                     :source "(defn report [x] (str \"R\" x))"})
    (write-def! env {:name "demo/area"
                     :ns "demo"
                     :source "(defn area [w h] (* w h))"})
    (let [[[level _ content]] (run env "list")]
      (t/is (= :info level))
      (t/is (str/includes? content "user/report"))
      (t/is (str/includes? content "demo/area"))
      (t/is (str/includes? content "- `user/report` · `defn`"))
      (t/is (str/includes? content "- `demo/area` · `defn`"))
      (t/is (str/includes? content "2 definitions"))
      (t/is (str/includes? content "(cwd)"))
      (t/is (not (str/includes? content "report.clj")))
      (t/is (not (str/includes? content "area.clj"))))
    (let [[_ _ content] (first (run env "list report"))]
      (t/is (str/includes? content "user/report"))
      (t/is (not (str/includes? content "demo/area"))))))

(t/deftest list-shows-the-saved-interface
  (let [env (eval-env)]
    (write-def! env {:name "user/report"
                     :source "(defn report \"Render.\" [x] x)"})
    (write-def! env {:name "user/plain" :source "(def plain 1)"})
    (let [[[level _ content]] (run env "list")]
      (t/is (= :info level))
      (t/is (str/includes? content "`user/report` · `defn` `([x])` · `(cwd)`"))
      (t/is (str/includes? content "Render."))
      (t/is (str/includes? content "`user/plain` · `def` · `(cwd)` — *no docstring*"))
      (t/is (not (str/includes? content "plain.clj"))))
    (let [[_ _ content] (first (run env "list report"))]
      (t/is (str/includes? content "Render."))
      (t/is (not (str/includes? content "user/plain"))))))

(t/deftest describe-shows-the-saved-interface-without-building
  (let [env (eval-env)
        source "(defn report \"Render a report.\" [x] (str \"R\" x))"
        path (write-def! env {:name "user/report" :source source})]
    (let [[[level label content]]
          (with-redefs-fn {#'eval-service/build-environment
                           (fn [& _]
                             (throw (ex-info "must not build" {:type :test})))}
            #(run env "describe user/report"))]
      (t/is (= :info level))
      (t/is (= "Eval" label))
      (t/is (str/includes? content "### `user/report`"))
      (t/is (str/includes? content "`defn` `([x])` · `(cwd)`"))
      (t/is (str/includes? content
                           (str "**File:** `"
                                (eval-files/display-path (:cwd env) path) "`")))
      (t/is (not (str/includes? content "**Doc:**"))
            "the docstring is not repeated outside the source")
      (t/is (str/includes? content source)))
    (let [[level content] (first (run env "describe user/nope"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "no eval definition named user/nope")))
    (let [[level content] (first (run env "describe"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "Usage: /eval describe")))))

(t/deftest a-defined-name-shorthand-describes
  (let [env (support/eval-ctx)
        _ (support/eval! env "(defn saved-answer \"Saved answer.\" [x] x)")
        _ (support/eval! env "(defn live-answer [x] x)")
        env (assoc env :engine {:agent-state (atom {:ctx (:ctx env)})
                                :session (atom (:session env))
                                :cwd (atom (:cwd env))})]
    (let [[[level label content]] (run env "saved-answer")]
      (t/is (= :info level))
      (t/is (= "Eval" label))
      (t/is (str/includes? content "### `user/saved-answer`"))
      (t/is (str/includes? content "`defn` `([x])` · `(cwd)`"))
      (t/is (str/includes? content "Saved answer.")))
    (let [[[level _ content]] (run env "live-answer")]
      (t/is (= :info level))
      (t/is (str/includes? content "### `user/live-answer`"))
      (t/is (str/includes? content "`defn` `([x])` · `(cwd)`"))
      (t/is (str/includes? content "**File:** *none*")))
    (let [[[level content]] (run env "not-a-definition")]
      (t/is (= :warning level))
      (t/is (str/includes? content "Unknown verb: not-a-definition")))))

(t/deftest a-saved-name-shorthand-describes-without-building
  (let [env (eval-env)
        _ (write-def! env {:name "user/report"
                           :source "(defn report \"Render.\" [x] x)"})]
    (with-redefs-fn {#'eval-service/build-environment
                     (fn [& _]
                       (throw (ex-info "must not build" {:type :test})))}
      #(let [[[level _ content]] (run env "user/report")]
         (t/is (= :info level))
         (t/is (str/includes? content "### `user/report`"))
         (t/is (str/includes? content "`defn` `([x])` · `(cwd)`"))
         (t/is (str/includes? content "Render."))))))

(t/deftest describe-a-source-verb-from-the-built-environment
  (let [env (support/eval-ctx)
        _ (tools/register-eval-source!
           (:tools env) :license
           (fn [_]
             {:ns {'ext.license {'add! (with-meta
                                         (fn [_] :added)
                                         {:doc "Add a header."
                                          :arglists '([path])
                                          :source "(fn [path] ...)"})}}}))
        _ (support/eval! env "(core/emit :built)")
        engine {:agent-state (atom {:ctx (:ctx env)})
                :session (atom (:session env))
                :cwd (atom (:cwd env))}
        [[level label content]]
        (run (assoc env :engine engine) "describe ext.license/add!")]
    (t/is (= :info level))
    (t/is (= "Eval" label))
    (t/is (str/includes? content "### `ext.license/add!`"))
    (t/is (str/includes? content "`verb` `([path])` · `(source)`"))
    (t/is (str/includes? content "**File:** *none*"))
    (t/is (not (str/includes? content "**Doc:**"))
          "a source-bearing entry does not repeat the docstring")
    (t/is (str/includes? content "(fn [path] ...)"))))

(t/deftest search-matches-names-and-source
  (let [env (eval-env)
        _ (write-def! env {:name "user/report"
                           :source "(defn report [x] (str \"R\" x))"})
        _ (write-def! env {:name "user/other" :source "(defn other [] :ok)"})]
    (let [[_ _ content] (first (run env "search report"))]
      (t/is (str/includes? content "user/report"))
      (t/is (not (str/includes? content "user/other"))))
    (let [[_ _ content] (first (run env "search :ok"))]
      (t/is (str/includes? content "user/other"))
      (t/is (str/includes? content "(defn other [] :ok)")))
    (let [[_ _ content] (first (run env "search absent-name"))]
      (t/is (str/includes? content "No eval definitions or sources match")))
    (let [[_ _ content] (first (run env "search"))]
      (t/is (str/includes? content "/eval search <text>")))))

(t/deftest search-finds-eval-sources
  (let [source {:id :license
                :doc "License helpers"
                :verbs [{:name "ext.license/add!"
                         :kind :verb
                         :doc "Add a header."
                         :arglists '([path])}]}
        env (eval-env {:service-sources [source]})]
    (let [[_ _ content] (first (run env "search license"))]
      (t/is (str/includes? content "### Eval sources"))
      (t/is (str/includes? content "- license: License helpers"))
      (t/is (not (str/includes? content "No eval definitions"))
            "a source match is a match"))
    (let [[_ _ content] (first (run env "search add!"))]
      (t/is (str/includes? content "### Eval sources"))
      (t/is (str/includes? content "ext.license/add! ([path]) — Add a header.")))
    (let [[_ _ content] (first (run env "search absent-name"))]
      (t/is (str/includes? content "No eval definitions or sources match"))
      (t/is (not (str/includes? content "### Eval sources"))))))

(t/deftest drop-removes-the-file-and-reports
  (let [env (eval-env)
        path (write-def! env {:name "user/doomed"
                              :source "(defn doomed [] 1)"})]
    (t/is (fs/exists? path))
    (let [[[level _ content]]
          (with-redefs-fn {#'eval-service/build-environment
                           (fn [& _]
                             (throw (ex-info "must not build" {:type :test})))}
            #(run env "drop user/doomed"))]
      (t/is (= :info level))
      (t/is (str/includes? content "[eval: forgot user/doomed (cwd)]")
            "a drop reads and deletes files without building an environment"))
    (t/is (not (fs/exists? path)))
    (let [[level content] (first (run env "drop user/nope"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "no eval library definition")))
    (let [[level content] (first (run env "drop"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "Usage: /eval drop")))))

(t/deftest drop-accepts-a-quoted-method-address
  (let [env (eval-env)
        _ (write-def! env {:name "user/area" :kind :defmulti
                           :source "(defmulti area :shape)"})
        path (write-def! env {:name "user/area :rect" :kind :defmethod
                              :source "(defmethod area :rect [m] :rect)"})]
    (let [[[level _ content]] (run env "drop \"user/area :rect\"")]
      (t/is (= :info level))
      (t/is (str/includes? content "[eval: forgot user/area :rect (cwd)]")))
    (t/is (not (fs/exists? path)))
    (t/is (fs/exists? (eval-files/path-for-name (:cwd-root env) "user/area")))))

(t/deftest list-marks-both-scopes-and-shadowing
  (let [env (eval-env)]
    (write-def! env {:name "user/f" :source "(defn f [] :cwd)"})
    (eval-files/write-definition! (:library-root env)
                                  {:name "user/f" :ns "user" :kind :defn
                                   :source "(defn f [] :global)"})
    (let [[[level _ content]] (run env "list")
          at (fn [text] (str/index-of content text))]
      (t/is (= :info level))
      (t/is (str/includes? content "2 definitions"))
      (t/is (str/includes? content "`user/f` · `defn` `([])` · `(cwd)`"))
      (t/is (str/includes? content "`user/f` · `defn` `([])` · `(global, shadowed)`"))
      (t/is (< (at "`user/f` · `defn` `([])` · `(global, shadowed)`")
               (at "`user/f` · `defn` `([])` · `(cwd)`"))))))

(t/deftest list-orders-global-cwd-then-live
  (let [env (support/eval-ctx)
        _ (eval-files/write-definition! (support/global-root env)
                                        {:name "user/alpha-global" :ns "user"
                                         :kind :defn
                                         :source "(defn alpha-global [] :global)"})
        _ (eval-files/write-definition! (support/cwd-root env)
                                        {:name "user/beta-cwd" :ns "user"
                                         :kind :defn
                                         :source "(defn beta-cwd [] :cwd)"})
        _ (support/eval! env "(defn live-tool [x] x)")
        env (assoc env :engine {:agent-state (atom {:ctx (:ctx env)})
                                :session (atom (:session env))
                                :cwd (atom (:cwd env))})]
    (let [[[level _ content]] (run env "list")
          at (fn [text] (or (str/index-of content text) -1))
          global-pos (at "user/alpha-global")
          cwd-pos (at "user/beta-cwd")
          live-pos (at "user/live-tool")]
      (t/is (= :info level))
      (t/is (< global-pos cwd-pos live-pos))
      (t/is (str/includes? content "`user/live-tool` · `defn` `([x])` · `(live)`"))
      (t/is (str/includes? content "3 definitions")))
    (let [[_ _ content] (first (run env "list live"))]
      (t/is (str/includes? content "user/live-tool"))
      (t/is (not (str/includes? content "user/alpha-global"))))))

(t/deftest move-command-moves-without-building-an-environment
  (let [env (eval-env)
        cwd-file (write-def! env {:name "user/f" :source "(defn f [] 1)"})
        global-file (eval-files/path-for-name (:library-root env) "user/f")]
    (with-redefs-fn {#'eval-service/build-environment
                     (fn [& _] (throw (ex-info "must not build" {:type :test})))}
      (fn []
        (let [[[level _ content]] (run env "move user/f global")]
          (t/is (= :info level))
          (t/is (str/includes? content "[eval: moved user/f (cwd → global)]")))))
    (t/is (not (fs/exists? cwd-file)))
    (t/is (fs/exists? global-file))
    (let [[[level content]] (run env "move user/f global")]
      (t/is (= :warning level))
      (t/is (str/includes? content "already in the global scope")))
    (let [[[level content]] (run env "move user/f")]
      (t/is (= :warning level))
      (t/is (str/includes? content "Usage: /eval move")))
    (let [[[level _ content]] (run env "move user/f cwd")]
      (t/is (= :info level))
      (t/is (str/includes? content "[eval: moved user/f (global → cwd)]")))
    (t/is (fs/exists? cwd-file))))

(t/deftest move-command-accepts-a-quoted-method-address
  (let [env (eval-env)
        cwd-file (write-def! env {:name "user/area :rect" :kind :defmethod
                                  :source "(defmethod area :rect [m] :rect)"})
        global (eval-files/path-for-name (:library-root env) "user/area :rect")]
    (let [[[level _ content]] (run env "move \"user/area :rect\" global")]
      (t/is (= :info level))
      (t/is (str/includes? content "[eval: moved user/area :rect (cwd → global)]")))
    (t/is (not (fs/exists? cwd-file)))
    (t/is (fs/exists? global))))

(t/deftest reload-builds-and-refreshes-the-session-environment
  (let [env (eval-env)
        _ (write-def! env {:name "user/saved"
                           :source "(defn saved \"Saved.\" [] :saved)"})]
    (let [[[level label content]] (run env "reload")]
      (t/is (= :info level))
      (t/is (= "Eval" label))
      (t/is (str/includes? content "[eval: loaded 1 library definition")))
    (write-def! env {:name "user/added"
                     :source "(defn added \"Added.\" [] :added)"})
    (let [[[level _ content]] (run env "reload")]
      (t/is (= :info level))
      (t/is (str/includes? content "[eval: reloaded 2 library definitions")))))

(t/deftest reload-failure-reports-through-the-warning-channel
  (let [env (eval-env)
        bad (str (fs/path (:cwd-root env) "user" "broken.clj"))]
    (fs/create-dirs (fs/parent bad))
    (spit bad "(defn broken [")
    (let [[[level content]] (run env "reload")]
      (t/is (= :warning level)
            "a failed reload uses the one-argument warning capability")
      (t/is (str/includes? content "load failed")))))

(t/deftest sources-and-help
  (let [env (eval-env)]
    (let [[[level _ content]] (run env "sources")]
      (t/is (= :info level))
      (t/is (str/includes? content "No registered eval sources"))
      (t/is (str/includes? content "No eval definitions")))
    (let [with-source (eval-env {:service-sources [{:id :license :doc "License verbs"}]})
          [[_ _ content]] (run with-source "sources")]
      (t/is (str/includes? content "- license: License verbs"))
      (t/is (str/includes? content "No eval definitions")))
    (let [[_ _ help] (first (run env "help"))]
      (t/is (str/includes? help "/eval list [pattern]"))
      (t/is (str/includes? help "/eval search <text>"))
      (t/is (str/includes? help "/eval drop <name>"))
      (t/is (str/includes? help "/eval move <name> global|cwd"))
      (t/is (str/includes? help "/eval reload"))
      (t/is (str/includes? help "/eval describe <name>"))
      (t/is (str/includes? help "/eval <name>"))
      (t/is (str/includes? help "/eval sources"))
      (t/is (str/includes? help "/eval help")))
    (let [[level content] (first (run env "nonsense"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "Unknown verb: nonsense")))))

(t/deftest sources-list-contributed-verbs
  (let [env (eval-env {:service-sources
                       [{:id :license
                         :doc "License helpers"
                         :verbs [{:name "ext.license/add!"
                                  :kind :verb
                                  :doc "Add a header."
                                  :arglists '([path])}
                                 {:name "ext.license/bare"
                                  :kind :verb
                                  :doc "(no docstring)"}]}]})
        [[level _ content]] (run env "sources")]
    (t/is (= :info level))
    (t/is (str/includes? content "- license: License helpers"))
    (t/is (str/includes? content "ext.license/add! ([path]) — Add a header."))
    (t/is (str/includes? content "ext.license/bare — (no docstring)"))))

(t/deftest sources-reports-definitions-and-failures
  (let [env (eval-env {:service-failures [{:file "/tmp/broken.clj"
                                           :error "unreadable"}]})
        _ (write-def! env {:name "user/report"
                           :source "(defn report [x] x)"})
        [[level _ content]] (run env "sources")]
    (t/is (= :info level))
    (t/is (str/includes? content "1 saved definition"))
    (t/is (str/includes? content "broken.clj"))
    (t/is (str/includes? content "unreadable"))))

(t/deftest a-source-contributed-verb-dispatches-and-appears-in-help
  (let [called (atom nil)
        env (eval-env
             {:service-commands
              {:license/lint {:description "Lint a definition"
                              :run (fn [{:keys [args session cwd service]}]
                                     (reset! called {:args args
                                                     :session session
                                                     :cwd cwd
                                                     :service? (some? service)})
                                     "Linted 1 definition")}}})]
    (let [[[level label content]] (run env "license/lint user/report")]
      (t/is (= :info level))
      (t/is (= "Eval" label))
      (t/is (= "Linted 1 definition" content)))
    (t/is (= "user/report" (:args @called)))
    (t/is (= (:session env) (:session @called)))
    (t/is (= (:cwd env) (:cwd @called)))
    (t/is (:service? @called))
    (let [[_ _ help] (first (run env "help"))]
      (t/is (str/includes? help "/eval license/lint"))
      (t/is (str/includes? help "Lint a definition")))))

(t/deftest a-real-source-command-and-sources-report
  (let [env (support/eval-ctx)
        _ (tools/register-eval-source!
           (:tools env) :license
           (fn [_]
             {:doc "License helpers"
              :commands {:license-lint
                         {:description "Lint licenses"
                          :run (fn [{:keys [args]}]
                                 (str "linted " args))}}}))
        got (atom [])
        engine {:agent-state (atom {:ctx (:ctx env)})
                :session (atom (:session env))
                :cwd (atom (:cwd env))}
        run (fn [args]
              (reset! got [])
              ((:handler eval-command/command)
               {:runtime engine
                :ui {:info (fn [label content]
                             (swap! got conj [:info label content]))
                     :warning (fn [text]
                                (swap! got conj [:warning text]))}}
               args)
              @got)]
    (t/is (= [[:info "Eval" "linted x"]] (run "license-lint x")))
    (let [[[_ _ content]] (run "sources")]
      (t/is (str/includes? content "- license: License helpers")))
    (let [[[_ _ content]] (run "search license")]
      (t/is (str/includes? content "### Eval sources"))
      (t/is (str/includes? content "- license: License helpers")))
    (let [[[_ _ content]] (run "help")]
      (t/is (str/includes? content "/eval license-lint"))
      (t/is (str/includes? content "Lint licenses")))))

(t/deftest a-built-in-verb-describes-without-a-source (let [env (support/eval-ctx) got (atom []) engine {:agent-state (atom {:ctx (:ctx env)}), :session (atom (:session env)), :cwd (atom (:cwd env))} run (fn [args] (reset! got []) ((:handler eval-command/command) {:runtime engine, :ui {:info (fn [label content] (swap! got conj [:info label content])), :warning (fn [text] (swap! got conj [:warning text]))}} args) (clojure.core/deref got)) [[level _ content]] (run "describe core/read")] (t/is (= :info level)) (t/is (str/includes? content "(built-in)")) (t/is (not (str/includes? content "source: (not recorded)"))) (t/is (str/includes? content "Read PATH"))))

(t/deftest runtime-builtins-register-eval
  (builtins/register-runtime-commands! (constantly nil))
  (t/is (some? (commands/find-command "eval"))))

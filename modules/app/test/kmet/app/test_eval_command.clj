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
    (t/is (= "No eval definitions." content))))

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
      (t/is (str/includes? content "user/report  defn ([x])  (cwd)"))
      (t/is (str/includes? content "Render."))
      (t/is (str/includes? content "user/plain  def  (cwd)  (no docstring)"))
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
      (t/is (str/includes? content "user/report  defn ([x])  (cwd)"))
      (t/is (str/includes? content
                           (str "file: " (eval-files/display-path (:cwd env) path))))
      (t/is (str/includes? content "doc: Render a report."))
      (t/is (str/includes? content source)))
    (let [[level content] (first (run env "describe user/nope"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "no eval definition named user/nope")))
    (let [[level content] (first (run env "describe"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "Usage: /eval describe")))))

(t/deftest describe-a-source-verb-from-the-built-environment
  (let [env (support/eval-ctx)
        _ (tools/register-eval-source!
           (:tools env) :license
           (fn [_]
             {:ns {'license {'add! (with-meta
                                     (fn [_] :added)
                                     {:doc "Add a header."
                                      :arglists '([path])
                                      :source "(fn [path] ...)"})}}}))
        _ (support/eval! env "(emit :built)")
        engine {:agent-state (atom {:ctx (:ctx env)})
                :session (atom (:session env))
                :cwd (atom (:cwd env))}
        [[level label content]]
        (run (assoc env :engine engine) "describe license/add!")]
    (t/is (= :info level))
    (t/is (= "Eval" label))
    (t/is (str/includes? content "license/add!  verb ([path])  (source)"))
    (t/is (str/includes? content "file: (no file)"))
    (t/is (str/includes? content "doc: Add a header."))
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
      (t/is (str/includes? content "No eval definitions match")))
    (let [[_ _ content] (first (run env "search"))]
      (t/is (str/includes? content "Usage: /eval search")))))

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
    (let [[[level _ content]] (run env "list")]
      (t/is (= :info level))
      (t/is (str/includes? content "2 definitions"))
      (t/is (str/includes? content "user/f  defn ([])  (cwd)"))
      (t/is (str/includes? content "user/f  defn ([])  (global, shadowed)")))))

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

(t/deftest sources-and-help
  (let [env (eval-env)]
    (let [[[level _ content]] (run env "sources")]
      (t/is (= :info level))
      (t/is (str/includes? content "No eval sources."))
      (t/is (str/includes? content "0 definitions loaded")))
    (let [with-source (eval-env {:service-sources [{:id :core :doc "Core verbs"}]})
          [[_ _ content]] (run with-source "sources")]
      (t/is (str/includes? content "- core: Core verbs"))
      (t/is (str/includes? content "0 definitions loaded")))
    (let [[_ _ help] (first (run env "help"))]
      (t/is (str/includes? help "/eval list [pattern]"))
      (t/is (str/includes? help "/eval search <text>"))
      (t/is (str/includes? help "/eval drop <name>"))
      (t/is (str/includes? help "/eval move <name> global|cwd"))
      (t/is (str/includes? help "/eval describe <name>"))
      (t/is (str/includes? help "/eval sources"))
      (t/is (str/includes? help "/eval help")))
    (let [[level content] (first (run env "nonsense"))]
      (t/is (= :warning level))
      (t/is (str/includes? content "Unknown verb: nonsense")))))

(t/deftest sources-list-contributed-verbs
  (let [env (eval-env {:service-sources
                       [{:id :license
                         :doc "License helpers"
                         :verbs [{:name "license/add!"
                                  :kind :verb
                                  :doc "Add a header."
                                  :arglists '([path])}
                                 {:name "license/bare"
                                  :kind :verb
                                  :doc "(no docstring)"}]}]})
        [[level _ content]] (run env "sources")]
    (t/is (= :info level))
    (t/is (str/includes? content "- license: License helpers"))
    (t/is (str/includes? content "license/add! ([path]) — Add a header."))
    (t/is (str/includes? content "license/bare — (no docstring)"))))

(t/deftest sources-reports-definitions-and-failures
  (let [env (eval-env {:service-failures [{:file "/tmp/broken.clj"
                                           :error "unreadable"}]})
        _ (write-def! env {:name "user/report"
                           :source "(defn report [x] x)"})
        [[level _ content]] (run env "sources")]
    (t/is (= :info level))
    (t/is (str/includes? content "1 definition loaded"))
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
      (t/is (str/includes? help "/eval license/lint  Lint a definition")))))

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
      (t/is (str/includes? content "- core"))
      (t/is (str/includes? content "- license: License helpers")))
    (let [[[_ _ content]] (run "help")]
      (t/is (str/includes? content "/eval license-lint"))
      (t/is (str/includes? content "Lint licenses")))))

(t/deftest runtime-builtins-register-eval
  (builtins/register-runtime-commands! (constantly nil))
  (t/is (some? (commands/find-command "eval"))))

(ns kmet.app.test-eval-support
  "Shared harness for the eval service tests: a scratch project cwd, a scratch
   agent dir, a session, the service provisioned on a test ctx, and the two
   call paths (service and tool)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.eval :as eval-service]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.core :as session]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.script :as script]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]
            [kmet.test-utils :as tu]))

(defn eval-ctx
  "A test agent: tool table, `:eval` service, and one session. OPTS may pin
   `:cwd` and `:agent-dir`, which a restart or a second session reuses."
  ([] (eval-ctx {}))
  ([{:keys [cwd agent-dir session]}]
   (let [cwd (or cwd (test-tmp/temp-dir! "eval-project-"))
         agent-dir (or agent-dir (test-tmp/temp-dir! "eval-agent-"))
         ctx (srce/root {:name :test-eval})
         handle (tu/tools-service! ctx)
         service (eval-service/value ctx {:agent-dir agent-dir})
         sess (or session
                  (session/create-session (test-tmp/temp-dir! "eval-session-")
                                          {:cwd cwd}))]
     (srce/provide! ctx :eval service)
     (tu/mount-builtin-tools! ctx)
     {:ctx ctx :tools handle :service service :session sess
      :cwd cwd :agent-dir agent-dir})))

(defn restart-service
  "A second service over the same agent ctx and roots — a process restart as
   far as the eval library is concerned."
  [{:keys [ctx agent-dir]}]
  (eval-service/value ctx {:agent-dir agent-dir}))

(defn global-root
  "The global eval library root for ENV."
  [{:keys [agent-dir]}]
  (str (fs/path agent-dir "eval")))

(defn cwd-root
  "The cwd eval library root for ENV."
  [{:keys [agent-dir cwd]}]
  (str (fs/path agent-dir "eval-scopes" (session/encode-cwd cwd))))

(defn library-file
  "A definition file under ENV's SCOPE root, then PARTS."
  [env scope & parts]
  (str (apply fs/path (case scope :global (global-root env) :cwd (cwd-root env))
              parts)))

(defn- opts-map
  [opts]
  (cond
    (empty? opts) {}
    (and (= 1 (count opts)) (map? (first opts))) (first opts)
    :else (apply hash-map opts)))

(defn eval!
  [env code & opts]
  ((:eval! (:service env))
   (merge {:code code
           :session (:session env)
           :cwd (:cwd env)
           :limits (script/resolve-limits nil)}
          (opts-map opts))))

(defn eval-in!
  "Evaluate CODE in a service for an explicit SESSION — the second-session and
   restart paths."
  [service session cwd code & opts]
  ((:eval! service)
   (merge {:code code :session session :cwd cwd
           :limits (script/resolve-limits nil)}
          (opts-map opts))))

(defn call-tool
  [env tool-name args]
  (binding [extensions/*agent-context* {:session (constantly (:session env))}]
    (tools/execute-tool (:tools env) tool-name args
                        {:ctx {:srce-ctx (:ctx env) :cwd (:cwd env)}})))

(defn library-for
  "The service's `(eval/library)` value for an explicit SESSION."
  [service session]
  (binding [extensions/*agent-context* {:session (constantly session)}]
    ((:library service))))

(defn eval-library
  "The service's `(eval/library)` value with the run's session binding."
  [env]
  (library-for (:service env) (:session env)))

(defn script-content
  "RESULT's content without the first-eval library report lines — the script's
   own output, for a test comparing exact output after an environment build."
  [result]
  (->> (str/split-lines (str (:content result)))
       (remove #(str/starts-with? % "[eval: loaded "))
       (remove #(str/starts-with? % "[eval: load failed "))
       (str/join "\n")))

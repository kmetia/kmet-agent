(ns kmet.app.extensions.test-api
  "The extension api surface: exec in the runtime cwd, the nullable api that
   captures registrations for isolated extension tests, and provider
   registration through the :models facades."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.extension :as ext]
            [kmet.app.config :as cfg]
            [kmet.ai.models :as models]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]
            [kmet.hikap.alias :as alias]
            [kmet.libs.theme :as theme]))

;; ─── Extension exec (extension api: exec) ────────────────────────────────

(t/deftest ^:slow test-exec-runs-in-the-runtime-cwd
  (testing "exec defaults to the runtime cwd (pi: options?.cwd ?? cwd) — the
            injected context's cwd, which a session switch moves; :dir still
            overrides it"
    (let [base (str (or (System/getenv "TMPDIR")
                        (System/getProperty "java.io.tmpdir"))
                    "/kmet-exec-cwd-" (System/currentTimeMillis))
          dir (str base "/runtime")
          other (str base "/override")
          exec-fn #'extensions/exec]
      (try
        (fs/create-dirs dir)
        (fs/create-dirs other)
        @(proc/shell {:dir dir :out :string :err :string} "git" "init")
        @(proc/shell {:dir other :out :string :err :string} "git" "init")
        (extensions/set-ui-registry! {:build-context (fn [] {:cwd dir})})
        (let [r (exec-fn "git" ["rev-parse" "--show-toplevel"])]
          (t/is (zero? (:exit r)))
          (t/is (= (str (fs/canonicalize dir))
                   (str (fs/canonicalize (str/trim (:out r)))))
                "the child ran in the context's cwd, not the process cwd"))
        (let [r (exec-fn "git" ["rev-parse" "--show-toplevel"] {:dir other})]
          (t/is (= (str (fs/canonicalize other))
                   (str (fs/canonicalize (str/trim (:out r)))))
                "an explicit :dir still wins"))
        (finally
          (extensions/clear-ui-registry!)
          (fs/delete-tree base))))))

;; ─── Nullable api (extension tests in isolation) ──────────────────────────

(t/deftest test-nullable-api-captures-registrations
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (ext/register-command! api {:name "nc" :description "d" :handler (fn [_ _] nil)})
    (ext/register-tool! api {:name "nt" :description "d" :execute (fn [_] {:content "x"})})
    (ext/on-event api :session-start (fn [_]))
    (ext/register-flag! api "nf" {:type :boolean})
    (ext/register-entry-renderer! api "er" (fn [_] {:role :info}))
    (ext/register-message-renderer! api "mr" (fn [_] {:role :info}))
    (ext/on-tool-call api (fn [_] nil))
    (t/is (contains? (:commands @state) "nc"))
    (t/is (contains? (:tools @state) "nt"))
    (t/is (= 1 (count (get-in @state [:handlers :session-start]))))
    (t/is (contains? (:flags @state) "nf"))
    (t/is (contains? (:entry-renderers @state) "er"))
    (t/is (contains? (:message-renderers @state) "mr"))
    (t/is (= 1 (count (:tool-call-hooks @state))))))

(t/deftest test-nullable-api-deregister-fns
  (let [{:keys [api state]} (ext/create-nullable-api)
        dereg-cmd (ext/register-command! api {:name "dc" :handler (fn [_ _] nil)})
        dereg-ev (ext/on-event api :agent-end (fn [_]))]
    (dereg-cmd)
    (dereg-ev)
    (t/is (not (contains? (:commands @state) "dc")))
    (t/is (empty? (get-in @state [:handlers :agent-end])))))

(t/deftest test-nullable-api-alias-registration
  ;; register-alias!/deregister replay for extensions that contribute tag
  ;; aliases (kmet.hikap.alias)
  (let [{:keys [api state]} (ext/create-nullable-api)
        f (fn [_attrs _children] [:text {:padding-x 0 :padding-y 0} "x"])
        dereg (ext/register-alias! api :test/nullable-chip f)]
    (t/is (identical? f (get-in @state [:aliases :test/nullable-chip])))
    (t/is (fn? dereg))
    (dereg)
    (t/is (not (contains? (:aliases @state) :test/nullable-chip)))))

(t/deftest test-extension-api-register-alias-is-tracked
  ;; the alias registry is process-global: unload must remove exactly the
  ;; aliases this extension registered
  (let [dereg-fns (atom [])
        api ((var extensions/create-extension-api)
             {:name "alias-test" :path "target/test-ext-alias.clj"
              :deregister-fns dereg-fns})
        f (fn [_attrs _children] [:text {:padding-x 0 :padding-y 0} "x"])]
    (try
      (let [dereg (ext/register-alias! api :kmet.test/alias-chip f)]
        (t/is (identical? f (alias/lookup :kmet.test/alias-chip)))
        (t/is (fn? dereg))
        (t/is (= 1 (count @dereg-fns)) "the dereg fn is tracked for unload"))
      (finally
        (doseq [f @dereg-fns] (f))))
    (t/is (nil? (alias/lookup :kmet.test/alias-chip))
          "running the tracked fns (unload-extension!) removed the alias")))

(t/deftest test-nullable-api-p2-capabilities
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (testing "ui-custom opts pass through untouched (pi: ctx.ui.custom options)"
      (let [factory (fn [_ _ _ _] nil)
            opts {:overlay true :overlay-options {:anchor :center :width 82}}]
        (ext/ui-custom api factory opts)
        (t/is (some (fn [[k f o]] (and (= k :custom) (identical? f factory) (= o opts)))
                    (:ui-calls @state)))))
    (testing "models register/unregister are captured (pi: ctx.registerProvider)"
      (ext/models-register-provider! api :ext-prov {:models [{:id "m"}]})
      (ext/models-unregister-provider! api :ext-prov)
      (t/is (some #(= [:register-provider! :ext-prov {:models [{:id "m"}]}] %)
                  (:model-calls @state)))
      (t/is (some #(= [:unregister-provider! :ext-prov] %) (:model-calls @state))))
    (testing "create-bash-tool returns a registrable tool and captures the options (pi: createBashTool)"
      (let [spawn-hook (fn [ctx] ctx)
            tool (ext/create-bash-tool api {:spawn-hook spawn-hook
                                            :expose-session-env? false})]
        (t/is (= "bash" (:name tool)))
        (t/is (fn? (:execute tool)))
        (ext/register-tool! api tool)
        (t/is (contains? (:tools @state) "bash"))
        (t/is (some #(and (identical? spawn-hook (:spawn-hook %))
                          (false? (:expose-session-env? %)))
                    (:bash-tool-opts @state)))))))

(t/deftest test-theme-lookup-shared-directly
  (testing "theme lookups come from the shared kmet.libs.theme layer, not the
            api — extensions use them directly"
    (t/is (= "dark" (:name (theme/get-theme "dark"))))
    (t/is (= "dark" (:name (theme/get-theme "does-not-exist")))
          "get-theme falls back to dark (pi getTheme)")
    (t/is (nil? (theme/get-theme-by-name "does-not-exist")))
    (t/is (map? (theme/get-all-themes)))))

(t/deftest test-nullable-api-extension-init
  (testing "a sample extension's init registers what it declares"
    (let [{:keys [api state]} (ext/create-nullable-api)]
      (load-file "test/fixtures/ext-single/hello_ext.clj")
      (let [ns-sym (find-ns 'hello-ext)
            init-var (ns-resolve ns-sym 'init)]
        (init-var api)
        (t/is (contains? (:commands @state) "hello-ext"))
        (t/is (contains? (:tools @state) "hello-ext-tool"))
        (t/is (contains? (:flags @state) "ext-hello"))
        (t/is (fn? (get-in @state [:aliases :hello-ext/chip])))
        (t/is (= 1 (count (get-in @state [:handlers :session-start]))))
        (t/is (some #(= [:set-status "hello-ext" "loaded"] %) (:ui-calls @state))
              "ui calls captured"))
      (remove-ns 'hello-ext))))

;; ─── Provider registration through the api (:models facades) ─────────────

(t/deftest test-extension-api-carries-agent-dir
  (testing "api :agent-dir is the host agent dir (KMET_CODING_AGENT_DIR-aware)"
    (with-redefs [cfg/get-agent-dir (fn [] "/sandbox/agent")]
      (let [api ((var extensions/create-extension-api)
                 {:name "agent-dir-test" :path "target/test-ext-agent-dir.clj"
                  :deregister-fns (atom [])})]
        (t/is (= "/sandbox/agent" (:agent-dir api)))
        (t/is (= "/sandbox/agent" (ext/get-agent-dir api)))))))

(t/deftest test-extension-provider-registration
  (testing "api :models register-provider! / unregister-provider! (pi ctx.registerProvider)"
    (let [api ((var extensions/create-extension-api)
               {:name "prov-test" :path "target/test-ext-prov.clj"
                :deregister-fns (atom [])})]
      (models/load-catalogs! nil)
      (models/clear-extension-providers!)
      (try
        (t/is (not (some #(= "ext-1" (:id %)) (extensions/get-all-models))))
        (let [config {:base-url "https://ext.example/v1" :api :openai-completions
                      :api-key "sk-ext" :auth-header true
                      :models [{:id "ext-1" :reasoning true}]}
              _ ((:register-provider! (:models api)) :ext-prov config)]
          (t/is (= "ext-prov" (:name (models/get-provider :ext-prov)))
                "name defaults to provider id")
          (t/is (some #(= "ext-1" (:id %)) (extensions/get-all-models))
                "registered model appears in get-all")
          (t/is (= config (extensions/get-registered-provider-config :ext-prov)))
          (testing "unregister drops the extension provider"
            ((:unregister-provider! (:models api)) :ext-prov)
            (t/is (not (some #(= "ext-1" (:id %)) (extensions/get-all-models))))
            (t/is (nil? (extensions/get-registered-provider-config :ext-prov)))))
        (testing "broken config throws without touching stored state"
          (t/is (thrown? Exception
                         ((:register-provider! (:models api)) :ext-prov
                                                              {:models [{:id "bad"}]})))
          (t/is (nil? (extensions/get-registered-provider-config :ext-prov))))
        (testing "oauth block registers an OAuthAuth on the composed provider"
          (let [login (fn [_] {:type :oauth :access "a" :refresh "r" :expires 1})
                to-auth (fn [c] {:api-key (str "Bearer " (:access c))})
                config {:base-url "https://ext.example/v1" :api :openai-completions
                        :oauth {:name "Ext SSO" :is-subscription? true
                                :login login :refresh-token (fn [c _] c) :to-auth to-auth}
                        :models [{:id "ext-1"}]}
                _ ((:register-provider! (:models api)) :ext-oauth config)
                p (models/get-provider :ext-oauth)]
            (t/is (instance? kmet.ai.oauth.OAuthAuth (:oauth p)))
            (t/is (= "Ext SSO" (:name (:oauth p))))
            (t/is (true? (:is-subscription? (:oauth p))))
            (t/is (= login (:login (:oauth p))))
            (t/is (= "Bearer a" (get-in ((:to-auth (:oauth p)) {:type :oauth :access "a"})
                                        [:api-key])))
            (t/is (= "a" (get-in ((:refresh (:oauth p)) {:type :oauth :access "a"} nil)
                                 [:access])))
            ((:unregister-provider! (:models api)) :ext-oauth)
            (t/is (nil? (:oauth (models/get-provider :ext-oauth))))))
        (testing "a broken oauth block throws at register time (pi validateExtensionProvider)"
          (t/is (thrown-with-msg? Exception #"requires :login and :to-auth"
                                  ((:register-provider! (:models api)) :ext-oauth
                                                                       {:base-url "https://ext.example/v1"
                                                                        :api :openai-completions
                                                                        :models [{:id "ext-1"}]
                                                                        :oauth {:name "broken"}}))))
        (finally
          (models/clear-extension-providers!))))))

(t/deftest test-the-host-registers-where-its-owner-points-it
  ;; The host holds no process handle of its own: it registers into whatever
  ;; services its owner bound. That is what lets it live in `app` below the
  ;; process it serves, and what gives a second owner (an agent's ctx) a
  ;; per-agent api. The composition's `:extensions` row binds the process one
  ;; and a test binds its own; `registries/host-services` builds either map.
  (let [handle (registry/atom-registry)
        ctx (srce/root {:name :host-test})
        previous (extensions/bind-services!
                  (fn [] (registries/host-services handle ctx)))]
    (try
      (extensions/register-flag! [:app :test] "host-test-flag" {:type :boolean})
      (t/is (= {:type :boolean :default nil}
               (registry/entry handle :kmet/flag "host-test-flag"))
            "the registration landed in the handle its owner bound")
      (t/is (nil? (registry/entry (registry/current) :kmet/flag "host-test-flag"))
            "and not in the process's")
      (finally
        ;; back to the binding every test after this one expects — exactly the
        ;; one that was there, not a handle rebuilt from the process root (that
        ;; read can force the boot, and the handle is captured before it)
        (extensions/bind-services! previous)))))

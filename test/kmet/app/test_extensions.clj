(ns kmet.app.test-extensions
  "Extension runtime tests: the init/shutdown contract, load/unload/reload
   lifecycle, per-extension deregistration, and the nullable api fixture
   (kmet.extension/create-nullable-api) for testing extensions in isolation.
   Contexts are per host — SCI on bb/JVM, the runtime's own loader on Jolt —
   and the suite runs on both. The remaining ^:bb-only test is the packed-
   clojure jar roundtrip, a dependency-closure fixture (src/kmet/extension.md §
   bb-bundled ports)."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.extension :as ext]
            [kmet.libs.host :as host]
            [kmet.config :as cfg]
            [kmet.ai.models :as models]
            [kmet.app.extensions :as extensions]
            [kmet.app.extensions.context :as ext-context]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.ai.hooks :as ai-hooks]
            [kmet.tui.theme :as theme]))

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
  (testing "theme lookups come from the shared kmet.tui.theme layer, not the
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
      (models/load-catalogs!)
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

;; ─── Load / unload / reload lifecycle (real runtime) ─────────────────────

(t/deftest test-load-single-file-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (t/is (some #(= "hello_ext.clj" (:name %)) (extensions/get-loaded-extensions)))
    (testing "registrations live in the real registries"
      (t/is (some? (commands/find-command "hello-ext")))
      (t/is (some? (tools/get-tool "hello-ext-tool")))
      (t/is (some? (extensions/get-flag "ext-hello"))))
    (testing "unload removes everything"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (commands/find-command "hello-ext")))
      (t/is (nil? (tools/get-tool "hello-ext-tool")))
      (t/is (nil? (extensions/get-flag "ext-hello"))))))

(t/deftest test-load-manifest-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "multi-file: helper ns + entry ns load isolated; tool from helper works"
      (t/is (some? (tools/get-tool "multi-ext-tool")))
      (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {}))))
      (if (host/jolt?)
        ;; The native backend keeps a context's namespaces in the process-global
        ;; registry — context-private there: the root hides them and unload
        ;; unmaps them (loader.md §9 Phase 2 records the one-registry gap) — so
        ;; the host-invisibility claim is the SCI backends' alone.
        (t/is (some? (find-ns 'multi-ext.main)))
        (do (t/is (nil? (find-ns 'multi-ext.main))
                  "extension namespaces never enter the global registry")
            (t/is (nil? (find-ns 'multi-ext.helper))))))
    (testing "unload removes the tool"
      (extensions/unload-all-extensions!)
      (t/is (nil? (tools/get-tool "multi-ext-tool")))
      (t/is (nil? (find-ns 'multi-ext.main))
            "unload unmaps the native backend's context namespaces")
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest test-extension-resource-in-a-late-callback
  ;; Every fn an extension registers runs in its own context whenever the app
  ;; calls it — including the fns inside a registration map that are not the
  ;; handler. The manifest fixture's :get-argument-completions reads a file
  ;; bundled at the extension's own root: on the SCI backend the injected
  ;; io/resource answers it, on Jolt the callback's ambient loader does.
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (let [cmd (commands/find-command "multi-ext-cmd")]
      (t/is (some? cmd) "the command registered")
      (t/is (= [{:value "from-own-root"}]
               ((:get-argument-completions cmd) ""))
            "the later callback resolves the extension's own resource"))
    (extensions/unload-all-extensions!)))

;; ─── Bundled resource descriptors ───────────────────

(defn- with-extension-resources
  "Run F with io/resource answering the bundle's extensions/ keys from the
   test classpath (extensions/<x> → fixtures/<x>): artifact mode's resource
   lookup, against known fixtures."
  [f]
  (let [orig io/resource
        mapped (fn [key]
                 (let [k (str key)]
                   (if (str/starts-with? k "extensions/")
                     (orig (str "fixtures/" (subs k (count "extensions/"))))
                     (orig key))))]
    (with-redefs [io/resource mapped] (f))))

(t/deftest test-load-resource-dir-descriptor
  ;; artifact mode: a :resource-dir descriptor loads the same extension the
  ;; checkout path does, with every source read through the extensions/
  ;; prefix and the SCI backend (Phase A)
  (extensions/clear-extensions!)
  (try
    (with-extension-resources
      (fn []
        (let [result (extensions/load-extension-descriptor!
                      {:name "ext-dir" :kind :resource-dir
                       :prefix "extensions/ext-dir"
                       :path "extensions/ext-dir" :bundled? true})]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (= :sci (:loader-kind result)))
          (t/is (true? (:bundled result)))
          (t/is (some? (tools/get-tool "multi-ext-tool")))
          (let [loaded (first (extensions/get-loaded-extensions))]
            (t/is (= "multi-ext" (:name loaded)))
            (t/is (= :resource-dir (:kind loaded)))
            (t/is (true? (:bundled loaded)))
            (t/is (nil? (:extension-dir loaded)))))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-resource-dir-native-root-survives-resolution
  ;; The descriptor carries the host capability, but the resolved artifact is
  ;; what create-jolt-loader/forced-loader-kind receive. Dropping :native here
  ;; would leave the Phase B switch unreachable.
  (with-extension-resources
    (fn []
      (let [resolved (@#'ext-context/resolve-extension-descriptor
                      {:name "ext-dir" :kind :resource-dir
                       :prefix "extensions/ext-dir" :path "extensions/ext-dir"
                       :native "embed:extensions/ext-dir" :bundled? true})]
        (t/is (= "embed:extensions/ext-dir" (:native (:artifact resolved))))
        (t/is (= :resource-dir (:kind resolved)))))))

(t/deftest test-resource-dir-native-capability-selects-jolt
  (if (host/jolt?)
    (with-extension-resources
      (fn []
        (let [resolved (@#'ext-context/resolve-extension-descriptor
                        {:name "ext-dir" :kind :resource-dir
                         :prefix "extensions/ext-dir" :path "extensions/ext-dir"
                         :native "embed:extensions/ext-dir" :bundled? true})
              probe (requiring-resolve 'kmet.app.extensions/embedded-roots?)]
          (with-redefs-fn {probe (constantly true)}
            (fn []
              (t/is (= :jolt (@#'ext-context/forced-loader-kind resolved [:sci :jolt])))
              (t/is (= :sci (@#'ext-context/forced-loader-kind resolved [:sci]))
                    "a sci-only bundled directory keeps its required fallback")))
          (with-redefs-fn {probe (constantly false)}
            #(t/is (= :sci (@#'ext-context/forced-loader-kind resolved [:sci :jolt])))))))
    (t/is true "skipped: embedded loader roots are Jolt-only")))

(t/deftest test-load-resource-file-descriptor
  ;; without a native target (the Babashka/older-Jolt shape), a bundled
  ;; single file stays SCI; its extension identity is the file name (what a
  ;; user's own copy carries)
  (extensions/clear-extensions!)
  (try
    (with-extension-resources
      (fn []
        (let [result (extensions/load-extension-descriptor!
                      {:name "hello-ext" :kind :resource-file
                       :path "extensions/ext-single/hello_ext.clj" :bundled? true})]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (= :sci (:loader-kind result)))
          (t/is (some? (commands/find-command "hello-ext")))
          (t/is (= "hello_ext.clj" (:name (first (extensions/get-loaded-extensions))))))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-resource-file-native-source-selects-jolt
  ;; The resource key is extensions/ext-single/hello_ext.clj, while the native
  ;; request is hello-ext. Resolution records that namespace and passes the
  ;; exact key to the adapter's :sources map.
  (if (host/jolt?)
    (with-extension-resources
      (fn []
        (let [resolved (@#'ext-context/resolve-extension-descriptor
                        {:name "hello-ext" :kind :resource-file
                         :path "extensions/ext-single/hello_ext.clj"
                         :native "extensions/ext-single/hello_ext.clj"
                         :bundled? true})
              artifact (:artifact resolved)
              probe (requiring-resolve 'kmet.app.extensions/embedded-roots?)]
          (t/is (= 'hello-ext (:entry-ns resolved)))
          (t/is (= 'hello-ext (:entry-ns artifact)))
          (t/is (= (:path resolved) (:path artifact)))
          (with-redefs-fn {probe (constantly true)}
            #(t/is (= :jolt (@#'ext-context/forced-loader-kind resolved [:sci :jolt])))))))
    (t/is true "skipped: embedded loader roots are Jolt-only")))

(t/deftest test-resource-dir-deps-lookup
  ;; a resource artifact's deps.edn is read through the same extensions/
  ;; prefix as its sources — the bundled-deps path of a built artifact (the
  ;; resolution itself is the same tools.deps pass as any extension's)
  (with-extension-resources
    (fn []
      (t/is (= {'dev.weavejester/cljfmt {:mvn/version "0.16.5"
                                         :exclusions ['rewrite-clj/rewrite-clj]}}
               (@#'ext-context/deps-of-root {:kind :resource-dir
                                             :prefix "extensions/ext-cljfmt"}))))))

(t/deftest test-duplicate-name-skipped
  ;; D11: the bundled layer ranks last — a same-name extension already
  ;; loaded (a user's own copy) is skipped, not loaded twice
  (extensions/clear-extensions!)
  (try
    (let [first-result (extensions/load-extension! "test/fixtures/ext-dir")
          second-result (extensions/load-extension! "test/fixtures/ext-dir")]
      (t/is (nil? (:error first-result)))
      (t/is (true? (:skipped second-result)))
      (t/is (= :duplicate-name (:reason second-result)))
      (t/is (= 1 (count (extensions/get-loaded-extensions)))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-unload-removes-provider-registration
  (extensions/clear-extensions!)
  (models/load-catalogs!)
  (models/clear-extension-providers!)
  (let [dir "target/test-ext-prov-unload"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/prov_unload"))
    (spit (str dir "/extension.edn") "{:name \"prov-unload\" :entry prov-unload.main :loader [:sci :jolt]}\n")
    (spit (str dir "/prov_unload/main.clj")
          (str "(ns prov-unload.main\n  (:require [kmet.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/models-register-provider! api :prov-unload\n"
               "    {:base-url \"https://prov-unload.example/v1\" :api :openai-completions\n"
               "     :api-key \"sk-x\" :models [{:id \"pu-1\"}]}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (some #(= "pu-1" (:id %)) (extensions/get-all-models))
              "registered model is visible while loaded")
        (extensions/unload-all-extensions!)
        (t/is (not (some #(= "pu-1" (:id %)) (extensions/get-all-models)))
              "unload removes the provider registration")
        (t/is (nil? (extensions/get-registered-provider-config :prov-unload))))
      (finally
        (models/clear-extension-providers!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-dir-is-own-directory
  ;; :extension-dir must be the extension's OWN directory — for a dir
  ;; extension :path IS the dir, so the old (fs/parent :path) was wrong and
  ;; broke :extension-dir-relative resource discovery (skills/...).
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "dir extension: :extension-dir is the extension dir itself"
      (let [loaded (first (extensions/get-loaded-extensions))]
        (t/is (= (str (fs/canonicalize "test/fixtures/ext-dir"))
                 (str (fs/canonicalize (:extension-dir loaded))))
              (str "got: " (:extension-dir loaded)))))
    (extensions/unload-all-extensions!)))

(t/deftest test-single-file-extension-dir-is-parent
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "single-file extension: :extension-dir is the file's parent"
      (let [loaded (first (extensions/get-loaded-extensions))]
        (t/is (= (str (fs/canonicalize "test/fixtures/ext-single"))
                 (str (fs/canonicalize (:extension-dir loaded))))
              (str "got: " (:extension-dir loaded)))))
    (extensions/unload-all-extensions!)))

(t/deftest test-unload-extension-nil-noop
  (extensions/clear-extensions!)
  ;; the load result map carries :extension (the name), not the Extension
  ;; record — passing nil to unload-extension! used to throw a cryptic
  ;; (deref nil) NPE; it must be a silent no-op
  (t/is (nil? (extensions/unload-extension! nil)))
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)))
    (testing "unload still works on the real record"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest test-load-manifest-extension-via-symlink
  ;; extension dirs are commonly installed as symlinks into a repo checkout;
  ;; strict ns-path probes resolve through the linked root or every own-file
  ;; require fails
  ;; (skipped on Windows: creating symlinks needs SeCreateSymbolicLinkPrivilege)
  (when-not (fs/windows?)
    (extensions/clear-extensions!)
    (let [link "target/ext-dir-link"]
      (fs/create-dirs "target")
      (fs/delete-if-exists link)
      (fs/create-sym-link link (fs/absolutize "test/fixtures/ext-dir"))
      (try
        (let [result (extensions/load-extension! link)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (testing "multi-file extension loads through a symlinked dir"
            (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {}))))))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists link))))))

(t/deftest test-extension-context-has-slurp-spit
  ;; slurp/spit are absent from SCI's builtin clojure.core; the context
  ;; injects the host fns (see build-context-namespaces), so extensions
  ;; can read/write files without babashka.fs workarounds
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-slurp"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/slurp_ext"))
    (spit (str dir "/extension.edn") "{:name \"slurp-ext\" :entry slurp-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/slurp_ext/main.clj")
          (str "(ns slurp-ext.main\n  (:require [kmet.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"slurp-tool\"\n"
               "                           :description \"reads/writes via slurp/spit\"\n"
               "                           :execute (fn [_]\n"
               "                                      (spit \"target/slurp-ext-out.txt\" \"written\")\n"
               "                                      {:content (slurp \"target/slurp-ext-in.txt\")})}))\n"))
    (spit "target/slurp-ext-in.txt" "input")
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "input" (:content (tools/execute-tool "slurp-tool" {}))))
        (t/is (= "written" (slurp "target/slurp-ext-out.txt"))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)
        (fs/delete-if-exists "target/slurp-ext-out.txt")
        (fs/delete-if-exists "target/slurp-ext-in.txt")))))

(t/deftest test-extension-gets-bundled-tools-reader-port
  ;; extensions can require clojure.tools.reader* without deps.edn pins: bb
  ;; pre-loads its reduced port and the context injects it by reference
  ;; (bundled-port-namespaces); on Jolt the extension runtime's own sci.core
  ;; require has already loaded the real tools.reader, which the native host
  ;; view shares by the same loaded-namespace rule.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-tools-reader"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/tr_ext"))
    (spit (str dir "/extension.edn") "{:name \"tr-ext\" :entry tr-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/tr_ext/main.clj")
          (str "(ns tr-ext.main\n  (:require [kmet.extension :as ext]\n            [clojure.tools.reader :as r]\n            [clojure.tools.reader.reader-types :as rt]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"tr-tool\"\n"
               "                           :description \"uses the bundled tools.reader port\"\n"
               "                           :execute (fn [_]\n"
               "                                      {:content (str (rt/read-char (rt/string-push-back-reader \"hi\"))\n"
               "                                                     (r/read-string \"(1 2 3)\"))})}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "h(1 2 3)" (:content (tools/execute-tool "tr-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-gets-bundled-spec-port-and-file-seq
  ;; bb's clojure.spec.alpha is a bundled port bb does not preload (unlike
  ;; tools.reader), and its Maven copy fails under SCI (spec.gen.alpha's
  ;; locking2 macro expands monitor-enter, which SCI's core lacks) — the
  ;; context requires the port and injects it by reference
  ;; (spec-port-namespaces), so extensions get working clojure.spec.alpha
  ;; without deps.edn pins. file-seq is likewise absent from SCI's core and
  ;; injected with slurp/spit (cljfmt.io's FileEntity protocol needs it).
  ;; Jolt loads the Maven spec.alpha through its native loader; both hosts
  ;; now run the same fixture cleanup after loading its source.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-spec-file-seq"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/spec_ext"))
    (fs/create-dirs (str dir "/data"))
    (spit (str dir "/data/a.clj") "x")
    (spit (str dir "/data/b.clj") "y")
    (spit (str dir "/extension.edn") "{:name \"spec-ext\" :entry spec-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/spec_ext/main.clj")
          (str "(ns spec-ext.main\n"
               "  (:require [kmet.extension :as ext]\n"
               "            [clojure.spec.alpha :as s]\n"
               "            [clojure.java.io :as io]))\n"
               "(s/def ::x int?)\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"spec-tool\"\n"
               "                           :description \"uses the bundled spec port + file-seq\"\n"
               "                           :execute (fn [_]\n"
               "                                      {:content (str (s/valid? ::x 5)\n"
               "                                                     \" \"\n"
               "                                                     (count (file-seq (io/file \"target/test-ext-spec-file-seq/data\"))))})}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "true 3" (:content (tools/execute-tool "spec-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest ^:slow test-extension-cljfmt-deps-load
  ;; regression: an extension pinning cljfmt failed to load with "Unable to
  ;; resolve symbol: monitor-enter" — the Maven spec.alpha/core.specs.alpha
  ;; jars leaked into the closure (bundled-artifact? slash-munged dots in
  ;; artifact names, missing repository/org/clojure/spec.alpha/) and failed
  ;; under SCI, while cljfmt.io's file-seq and cljfmt.config's
  ;; clojure.spec.alpha had no working source. The bundled ports are now
  ;; injected and the bundled jars excluded.
  ;;
  ;; Runs on both hosts: the fixture's deps.edn excludes rewrite-clj for the
  ;; bb port, and the fixed bundled set (kmet.app.extension-libs) provides
  ;; it where the declaration's gap would otherwise be — the closure
  ;; assertions below stay meaningful on both (the declared cljfmt is in the
  ;; closure, the host-provided ports are not).
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-cljfmt")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "the closure carries the declared cljfmt, not the bundled ports"
      (let [jars (extensions/extension-jars "cljfmt-ext")]
        (t/is (some #(str/includes? % "cljfmt-0.16.5.jar") jars))
        (t/is (not-any? #(str/includes? % "spec.alpha-") jars))
        (t/is (not-any? #(str/includes? % "core.specs.alpha-") jars))
        (t/is (not-any? #(re-find #"/clojure-\\d" %) jars))))
    (testing "cljfmt works end-to-end in the extension context"
      (t/is (= "(defn foo [x]\n  (if x\n    1\n    2))"
               (:content (tools/execute-tool "cljfmt-fmt"
                                             {:code "(defn foo [x]\n  (if x\n   1\n   2))"})))))
    (extensions/unload-all-extensions!)))

(t/deftest test-extension-fixed-bundled-set
  ;; the fixed bundled set (kmet.app.extension-libs; src/kmet/extension.md § Bundled
  ;; extension libraries) needs no deps.edn: a single-file extension requires
  ;; every third-party member and calls each one — same result on both hosts.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-fixed-set"
        f (str dir "/fixed_set.clj")]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (spit f
          (str/join
           "\n"
           ["(ns fixed-set-probe"
            "  (:require [kmet.extension :as ext]"
            "            [cljfmt.config :as config]"
            "            [cljfmt.core :as fmt]"
            "            [clojure.spec.alpha :as s]"
            "            [clojure.tools.reader :as r]"
            "            [edamame.core :as e]"
            "            [parinferish.core :as pf]"
            "            [rewrite-clj.zip :as z]))"
            ""
            "(defn- probe []"
            "  (let [formatted (fmt/reformat-string \"(defn f [x]\\n(+ x 1))\" config/default-config)"
            "        spec-ok (s/valid? int? 3)"
            "        read (r/read-string \"42\")"
            "        z-str (z/string (z/of-string \"(a b)\"))"
            "        edn (e/parse-string \"1 2\")"
            "        parsed (pf/parse \"(defn f [x] (+ x 1\" {:mode :indent})]"
            "    (str formatted \"|\" spec-ok \"|\" read \"|\" z-str \"|\" edn \"|\" (count parsed))))"
            ""
            "(defn init [api]"
            "  (ext/register-tool! api"
            "                      {:name \"fixed-set-tool\""
            "                       :description \"uses the fixed bundled extension set\""
            "                       :execute (fn [_] {:content (probe)})}))"]))
    (try
      (let [result (extensions/load-extension! f)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "(defn f [x]\n  (+ x 1))|true|42|(a b)|1|1"
                 (:content (tools/execute-tool "fixed-set-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-load-missing-require-error
  ;; a require the loader cannot serve must surface an actionable message,
  ;; not a bare NullPointerException (the load-fn used to call a nil
  ;; deps-resolver when the extension had no deps.edn)
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-missing-req"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/missing_req"))
    (spit (str dir "/extension.edn") "{:name \"missing-req\" :entry missing-req.main :loader [:sci :jolt]}\n")
    (spit (str dir "/missing_req/main.clj")
          "(ns missing-req.main\n  (:require [no.such.namespace]))\n(defn init [api] nil)\n")
    (let [result (extensions/load-extension! dir)]
      (t/is (str/includes? (:error result) "no.such.namespace")
            (str "actionable error, got: " (:error result)))
      (t/is (empty? (extensions/get-loaded-extensions))))
    (fs/delete-tree dir)))

(t/deftest ^:slow test-extension-lib-version-isolation
  (extensions/clear-extensions!)
  (let [ra (extensions/load-extension! "test/fixtures/ext-iso-a")
        rb (extensions/load-extension! "test/fixtures/ext-iso-b")]
    (t/is (nil? (:error ra)) (str "a: " (:error ra)))
    (t/is (nil? (:error rb)) (str "b: " (:error rb)))
    (testing "each extension serves its own declared version"
      (let [jars-a (extensions/extension-jars "iso-a")
            jars-b (extensions/extension-jars "iso-b")]
        (t/is (some #(str/includes? % "tools.cli-0.4.1.jar") jars-a))
        (t/is (some #(str/includes? % "tools.cli-1.0.206.jar") jars-b))
        (t/is (not-any? #(str/includes? % "tools.cli-1.0.206.jar") jars-a))
        (t/is (not-any? #(str/includes? % "tools.cli-0.4.1.jar") jars-b))))
    (testing "both extensions' tools work simultaneously"
      (t/is (= "iso-a" (:content (tools/execute-tool "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools/execute-tool "iso-b" {})))))
    (testing "reload keeps each extension on its declared version"
      (extensions/unload-all-extensions!)
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-a"))))
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-b"))))
      (t/is (some #(str/includes? % "tools.cli-0.4.1.jar")
                  (extensions/extension-jars "iso-a")))
      (t/is (some #(str/includes? % "tools.cli-1.0.206.jar")
                  (extensions/extension-jars "iso-b")))
      (t/is (= "iso-a" (:content (tools/execute-tool "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools/execute-tool "iso-b" {})))))
    (testing "unload releases both"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (tools/get-tool "iso-a")))
      (t/is (nil? (tools/get-tool "iso-b"))))))

(t/deftest test-load-failure-rolls-back
  (extensions/clear-extensions!)
  (testing "a file without init fails and leaves no trace"
    (let [dir "target/test-ext-bad"]
      (fs/create-dirs dir)
      (spit (str dir "/bad.clj")
            "(ns bad-ext)\n(defn not-init [api] nil)\n")
      (let [result (extensions/load-extension! (str dir "/bad.clj"))]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions))))
      (fs/delete-tree dir))))

(t/deftest test-extension-contexts-are-forked-and-isolated
  ;; the SCI backend builds the shared layers once and forks that base per
  ;; extension (kmet.loader.sci-loader :base); an extension's own namespaces
  ;; and defs stay in its fork — two extensions may even declare the same
  ;; namespace name without seeing each other. SCI-only: the Jolt native
  ;; backend keeps its own per-context world (see loader.md §9).
  (when-not (host/jolt?)
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-fork-iso"]
      (fs/delete-tree dir)
      (fs/create-dirs dir)
      (spit (str dir "/iso_a.clj")
            (str "(ns fork-iso (:require [kmet.extension :as ext]))\n"
                 "(def tag :a)\n"
                 "(defn init [api]\n"
                 "  (ext/register-tool! api {:name \"fork-iso-a\" :description \"a\"\n"
                 "                           :execute (fn [_] {:content (name tag)})}))\n"))
      (spit (str dir "/iso_b.clj")
            (str "(ns fork-iso (:require [kmet.extension :as ext]))\n"
                 "(def tag :b)\n"
                 "(defn init [api]\n"
                 "  (ext/register-tool! api {:name \"fork-iso-b\" :description \"b\"\n"
                 "                           :execute (fn [_] {:content (name tag)})}))\n"))
      (try
        (t/is (nil? (:error (extensions/load-extension! (str dir "/iso_a.clj")))))
        (t/is (nil? (:error (extensions/load-extension! (str dir "/iso_b.clj")))))
        (t/is (= "a" (:content (tools/execute-tool "fork-iso-a" {})))
              "each fork sees its own def")
        (t/is (= "b" (:content (tools/execute-tool "fork-iso-b" {}))))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-tree dir))))))

(t/deftest test-extension-context
  (testing "headless default context is complete and callable"
    (let [ctx (extensions/build-extension-context)]
      (t/is (= :print (:mode ctx)))
      (t/is (false? (:has-ui ctx)))
      (t/is (string? (:cwd ctx)))
      (t/is (nil? (:model ctx)))
      (t/is (= [] (:scoped-models ctx)))
      (t/is (true? ((:is-idle ctx))))
      (t/is (false? ((:has-pending-messages ctx))))
      (t/is (nil? ((:signal ctx))))
      (t/is (nil? ((:get-context-usage ctx))))
      (t/is (nil? ((:get-system-prompt ctx))))
      (t/is (nil? ((:wait-for-idle ctx))))
      (t/is (= {:cancelled true} ((:new-session ctx))))
      (t/is (= {:cancelled true} ((:fork ctx) "e1")))
      (t/is (= {:cancelled true} ((:navigate-tree ctx) "e1")))
      (t/is (= {:cancelled true} ((:switch-session ctx) "/x")))
      (t/is (false? ((:is-project-trusted ctx))))
      (t/is (nil? ((:get-name (:session ctx))))
            "ctx carries the read-only session facade (pi: ctx.sessionManager)")))
  (testing "event handlers receive (event ctx) — fixed arity-2 contract"
    (let [calls (atom [])
          w (extensions/wrap-event-handler
             (fn [ev ctx] (swap! calls conj [(:type ev) (:mode ctx)])))]
      (w {:type :agent-start})
      (t/is (= [[:agent-start :print]] @calls))
      (t/is (thrown? clojure.lang.ArityException
                     ((extensions/wrap-event-handler (fn [_] :legacy))
                      {:type :agent-end}))
            "arity-1 handlers fail fast — no legacy shim"))))

(t/deftest test-extension-ctx-events-via-bus
  (testing "sci handlers receive the ctx through the real bus path"
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-ctx-events"
          probes (atom [])
          unsub-a (event-bus/on-event :probe-a
                                      (fn [ev] (swap! probes conj [:a ev])))
          unsub-b (event-bus/on-event :probe-b
                                      (fn [ev] (swap! probes conj [:b (:mode ev)])))
          content (str "(ns ctx-events (:require [kmet.extension :as ext]))\n"
                       "(defn init [api]\n"
                       "  (ext/register-command! api {:name \"probe-cmd\"\n"
                       "                                :description \"pc\"\n"
                       "                                :handler (fn [c a] nil)})\n"
                       "  (ext/on-event api :session-start\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :probe-a\n"
                       "                                        :from (:type ev)\n"
                       "                                        :cmds (ext/get-commands api)})))\n"
                       "  (ext/on-event api :session-tree\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :probe-b :mode (:mode ctx)}))))\n")]
      (fs/create-dirs dir)
      (spit (str dir "/ext.clj") content)
      (let [result (extensions/load-extension! (str dir "/ext.clj"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (event-bus/emit-event! {:type :session-start})
        (event-bus/emit-event! {:type :session-tree :new-leaf-id "x"})
        (let [[[_ probe-a] [_ mode-b]] @probes]
          (t/is (= :session-start (:from probe-a))
                "arity-2 handler received the event")
          (t/is (= :print mode-b) "arity-2 handler got the ctx")
          (testing "get-commands is sanitized — handlers stay private"
            (t/is (seq (:cmds probe-a)))
            (t/is (some #(= "probe-cmd" (:name %)) (:cmds probe-a))
                  "the extension's own command is visible")
            (t/is (every? #(and (string? (:name %)) (string? (:description %)))
                          (:cmds probe-a)))
            (t/is (every? #(not (contains? % :handler)) (:cmds probe-a)))
            (t/is (every? #(not (contains? % :extension-handler)) (:cmds probe-a))))))
      (extensions/unload-all-extensions!)
      (unsub-a)
      (unsub-b)
      (fs/delete-tree dir))))
(t/deftest test-extension-command-ctx-dispatch
  ;; (Previously a top-level testing block, so its
  ;; assertions ran at namespace load instead of as a test.)
  (testing "extension commands carry :extension-handler for ctx dispatch"
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-ctx-cmd"]
      (fs/create-dirs dir)
      (spit (str dir "/ext.clj")
            (str "(ns ctx-cmd (:require [kmet.extension :as ext]))\n"
                 "(defn init [api]\n"
                 "  (ext/register-command! api\n"
                 "    {:name \"ctx-test\" :description \"d\"\n"
                 "     :handler (fn [ctx args] {:mode (:mode ctx) :args args})}))\n"))
      (let [result (extensions/load-extension! (str dir "/ext.clj"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (let [c (commands/find-command "ctx-test")]
          (t/is (fn? (:extension-handler c)))
          (t/is (= {:mode :print :args "hi"}
                   ((:extension-handler c) (extensions/build-extension-context) "hi"))))
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-namespace-isolation
  (extensions/clear-extensions!)
  (let [load (fn [name content]
               (let [dir (str "target/test-ext-iso-" name)]
                 (fs/create-dirs dir)
                 (spit (str dir "/ext.clj") content)
                 (let [result (extensions/load-extension! (str dir "/ext.clj"))]
                   (fs/delete-tree dir)
                   ;; unload the single-file probe so each testing block starts
                   ;; clean (bb tolerates same-path reloads; Jolt's runner runs
                   ;; all vars on one future where residue breaks later vars)
                   (extensions/unload-all-extensions!)
                   result)))]
    (testing "a manifest-dir extension's own internal namespaces load
              regardless of prefix (strict ns-path lookup)"
      (let [dir "target/test-ext-internal-ns"]
        (fs/create-dirs (str dir "/kmet/extensions/myext"))
        (spit (str dir "/extension.edn")
              "{:name \"internal-ns\" :entry kmet.extensions.myext.core :loader [:sci :jolt]}\n")
        (spit (str dir "/kmet/extensions/myext/core.clj")
              (str "(ns kmet.extensions.myext.core\n"
                   "  (:require [kmet.extension :as ext]\n"
                   "            [kmet.extensions.myext.internal :as internal]))\n"
                   "(defn init [api]\n"
                   "  (when-not (= 42 (internal/answer))\n"
                   "    (throw (ex-info \"internal require failed\" {}))))\n"))
        (spit (str dir "/kmet/extensions/myext/internal.clj")
              "(ns kmet.extensions.myext.internal)\n(defn answer [] 42)\n")
        (let [result (extensions/load-extension! dir)]
          (fs/delete-tree dir)
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (extensions/unload-all-extensions!))))

    (testing "kmet.app.* requires are rejected with an actionable error"
      (let [result (load "app" "(ns bad-app (:require [kmet.app.commands :as c]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "may depend only on kmet.extension, kmet.tui.* and kmet.libs.*"))))
    (testing "the loader is host machinery — not shared, not requireable"
      (let [result (load "loader-ns"
                         (str "(ns bad-loader (:require [kmet.loader.core :as ldr]))\n"
                              "(defn init [api] nil)\n"))]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "host machinery")
              (str "got: " (:error result))))
      ;; a *top-level* require evaluates in the extension's own context;
      ;; unlike a host-called init's require (which follows the ambient
      ;; host context, loader.md §3), this proves the loader is absent
      ;; from the injected share map — an injected namespace would
      ;; resolve and the require would succeed
      (let [result (load "loader-top"
                         (str "(ns loader-top (:require [kmet.extension :as ext]))\n"
                              "(require 'kmet.loader.core)\n"
                              "(defn init [api] nil)\n"))]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "host machinery")
              (str "got: " (:error result)))))
    (testing "a kmet.tui.* typo is rejected (sci would NPE silently)"
      (let [result (load "typo" "(ns bad-typo (:require [kmet.tui.theem :as t]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.tui.* library shared with extensions"))))
    (testing "a kmet.libs.* typo is rejected (sci would NPE silently)"
      (let [result (load "lib-typo" "(ns bad-lib-typo (:require [kmet.libs.hashh :as h]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.libs.* library shared with extensions"))))
    (testing "valid kmet.tui.* requires load and share the real library"
      (let [result (load "tui" "(ns good-tui\n  (:require [kmet.tui.components.text :as text]\n            [kmet.tui.protocols :as protocols]))\n(defn init [api]\n  (let [c (text/make-text \"hi\")]\n    (when-not (vector? (protocols/render c 20))\n      (throw (ex-info \"render failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "valid kmet.libs.* requires load and share the real library"
      (let [result (load "lib" "(ns good-lib\n  (:require [kmet.libs.hash :as hash]\n            [kmet.libs.yaml :as yaml]))\n(defn init [api]\n  (let [s (hash/short-hash \"hi\")]\n    (when-not (string? s)\n      (throw (ex-info \"hash failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "kmet.libs.archive loads from SCI and extracts zips (no ZipFile interop in extensions)"
      (let [result (load "archive" (str "(ns good-archive\n  (:require [babashka.fs :as fs]\n"
                                        "            [clojure.java.io :as io]\n"
                                        "            [kmet.libs.archive :as archive]))\n"
                                        "(defn init [_api]\n"
                                        "  (let [tmpdir (or (System/getenv \"TMPDIR\") (System/getProperty \"java.io.tmpdir\"))\n"
                                        "        zip (str (fs/path tmpdir \"arc-test.zip\"))\n"
                                        "        out (str (fs/path tmpdir \"arc-out\"))]\n"
                                        "    (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream zip))]\n"
                                        "      (.putNextEntry zos (java.util.zip.ZipEntry. \"a.txt\"))\n"
                                        "      (.write zos (.getBytes \"hi\" \"UTF-8\"))\n"
                                        "      (.closeEntry zos))\n"
                                        "    (let [extracted (archive/extract-zip! zip out)]\n"
                                        "      (when-not (= \"hi\" (slurp (str (first extracted))))\n"
                                        "        (throw (ex-info \"archive failed\" {}))))))\n"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "the whitelisted built-in renderer namespace loads and shares direct vars"
      (let [result (load "renderer" "(ns good-renderer\n  (:require [kmet.app.ui.tool-renderers :as renderers]))\n(defn init [_api]\n  (when-not (fn? renderers/render-edit-call)\n    (throw (ex-info \"renderer failed\" {}))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "kmet.libs.http loads and works from SCI (the outbound-HTTP boundary)"
      (let [result (load "http-lib" "(ns good-http-lib\n  (:require [kmet.libs.http :as http]))\n(defn init [api]\n  (when-not (fn? http/request)\n    (throw (ex-info \"http failed\" {}))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "direct babashka.http-client requires are rejected with an actionable error"
      (let [result (load "http-direct" "(ns bad-http-direct\n  (:require [babashka.http-client :as http]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "kmet.libs.http")
              "the rejection names the proxy-aware boundary")))))

(t/deftest ^:slow test-extension-bad-deps-fails-load
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-bad-deps"]
    (fs/create-dirs dir)
    (spit (str dir "/extension.edn") "{:name \"bad-deps\" :entry bad-deps.main :loader [:sci :jolt]}\n")
    (spit (str dir "/deps.edn") "{:deps {org.clojure/does-not-exist {:mvn/version \"9.9.9\"}}}\n")
    (fs/create-dirs (str dir "/bad_deps"))
    (spit (str dir "/bad_deps/main.clj")
          "(ns bad-deps.main (:require [org.clojure.does-not-exist :as bad]))\n(defn init [api] nil)\n")
    (testing "an unresolvable dep fails the load without killing the process"
      (let [result (extensions/load-extension! dir)]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions)))))
    (fs/delete-tree dir)))

(t/deftest test-reload-extensions
  (testing "reload-extensions! (container dirs) unloads + reloads"
    (let [container (str "target/test-ext-container-" (System/currentTimeMillis))]
      (fs/create-dirs container)
      (fs/copy-tree "test/fixtures/ext-single" container)
      (fs/copy-tree "test/fixtures/ext-dir" (str container "/multi-ext"))
      (extensions/clear-extensions!)
      (let [results (extensions/reload-extensions! [container])]
        (t/is (= 2 (count (filter #(nil? (:error %)) results)))
              (str "both loaded: " (pr-str results)))
        (t/is (some? (commands/find-command "hello-ext")))
        (t/is (some? (tools/get-tool "multi-ext-tool")))
        (testing "reload again: old state fully replaced, no duplicates"
          (extensions/reload-extensions! [container])
          (t/is (= 1 (count (filter #(= "hello_ext.clj" (:name %))
                                    (extensions/get-loaded-extensions))))
                "no duplicate extensions after reload")
          (t/is (some? (commands/find-command "hello-ext")))))
      (extensions/unload-all-extensions!)
      (fs/delete-tree container))))

;; ─── Session facades through the api ──────────────────────────────────────

(t/deftest test-session-api-facades
  (let [dir (str "target/test-ext-sess-" (System/currentTimeMillis))
        sess (session/create-session dir)]
    (extensions/set-session! sess)
    (try
      (let [id (extensions/append-custom-entry! "st" {:n 1})]
        (t/is (some? id)))
      (testing "get-all-entries reads custom entries across the session"
        (t/is (= [{:n 1}] (mapv :data (extensions/get-all-custom-entries "st"))))
        (t/is (= [] (extensions/get-all-custom-entries "nope"))))
      (testing "ctx/sessionManager facades (review extension needs them)"
        (let [_u1 (session/append-entry sess {:role :user :content [{:type :text :text "hi"}]})
              a1 (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]})
              ctx (extensions/build-extension-context)
              sess-api (:session ctx)]
          (t/is (= (:id a1) ((:get-leaf-id sess-api)))
                "get-leaf-id returns the active leaf")
          (t/is (= (:id a1) (:id ((:get-entry sess-api) (:id a1))))
                "get-entry finds by id")
          (t/is (nil? ((:get-entry sess-api) "missing"))
                "get-entry returns nil for unknown id")
          (t/is (= (:id a1) (:id (last ((:get-branch sess-api)))))
                "get-branch returns root→leaf with no args")
          (t/is (= (:id a1) (:id (last ((:get-branch sess-api) (:id a1)))))
                "get-branch respects from-id (pi parity) delegates to session")
          (t/is (= (:id a1) (:id (last (extensions/get-branch-entries))))
                "direct extensions/get-branch-entries also available")
          (t/is (nil? (extensions/get-entry "missing")))))
      (testing "headless direct facades return nil/[] when no session"
        (extensions/set-session! nil)
        (t/is (nil? (extensions/get-leaf-id)))
        (t/is (nil? (extensions/get-entry "x")))
        (t/is (= [] (extensions/get-branch-entries)))
        (extensions/set-session! sess))
      (finally
        (extensions/set-session! nil)
        (fs/delete-tree dir))))
  (testing "nullable api carries the new session facades"
    (let [{:keys [api state]} (ext/create-nullable-api)
          sess-api (:session api)]
      (t/is (= [] ((:get-branch sess-api))) "nullable get-branch → empty branch")
      (t/is (nil? ((:get-leaf-id sess-api))) "nullable get-leaf-id → nil")
      (t/is (nil? ((:get-entry sess-api) "any")) "nullable get-entry → nil")
      (t/is (= [] ((:get-entries sess-api) "x")))
      (t/is (= [] ((:get-all-entries sess-api) "x"))
            "nullable get-all-entries → empty")
      ((:set-label! sess-api) "id1" "bookmark")
      (t/is (= "bookmark" ((:get-label sess-api) "id1")))
      ((:set-name! sess-api) "hello")
      (t/is (= "hello" ((:get-name sess-api)))
            "nullable carry still records name")
      (t/is (contains? (get-in @state [:labels]) "id1")))))

(t/deftest test-markdown-transformers
  (testing "applied in registration order, last wins per position"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "a[" md "]")))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "b[" md "]")))]
      (t/is (= "b[a[hi]]"
               (extensions/apply-markdown-transformers
                "hi" {:message-type :user :is-streaming false :available-width 80}))
            "transformers chain in registration order (pi: applyMarkdownTransformers)")
      (d1)
      (t/is (= "b[hi]" (extensions/apply-markdown-transformers "hi" {}))
            "dereg removes exactly its own transformer")
      (d2)
      (t/is (= "hi" (extensions/apply-markdown-transformers "hi" {})))))
  (testing "a throwing transformer is skipped, the chain continues"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [_md _ctx] (throw (ex-info "boom" {}))))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "ok[" md "]")))]
      (t/is (= "ok[hi]" (extensions/apply-markdown-transformers "hi" {}))
            "pi: keep the current markdown and continue")
      (d1) (d2)))
  (testing "non-string results are ignored"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [_md _ctx] {:not :a-string}))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str md "!")))]
      (t/is (= "hi!" (extensions/apply-markdown-transformers "hi" {})))
      (d1) (d2))))

(t/deftest test-register-shortcut-headless
  (testing "headless register-shortcut! returns a no-op dereg fn"
    (let [dereg (extensions/register-shortcut! "ctrl+alt+x"
                                               {:description "d"
                                                :handler (fn [_] nil)})]
      (t/is (fn? dereg))
      (dereg) ;; must not throw headless
      (dereg))))

(t/deftest test-send-message-headless
  (testing "headless send-message! persists a custom message to the live session"
    (let [dir (str "target/test-ext-send-" (System/currentTimeMillis))
          sess (session/create-session dir)]
      (extensions/set-session! sess)
      (try
        (extensions/send-message! {:custom-type :note :content "hello"
                                   :display true :details {:x 1}})
        (let [entries (session/get-branch sess)
              e (last entries)]
          (t/is (= :custom-message (:role e)))
          (t/is (= :note (:custom-type e)))
          (t/is (= "hello" (:content e)))
          (t/is (= {:x 1} (:details e)))
          (t/is (= [{:role :custom
                     :custom-type :note
                     :content [{:type :text :text "hello"}]
                     :display true
                     :details {:x 1}}]
                   (session/context-messages e))
                "the entry projects into LLM context as a custom (user) message"))
        (finally
          (extensions/set-session! nil)
          (fs/delete-tree dir))))))

(t/deftest test-provider-event-bridges
  (testing "bus events drive the ai-layer hooks; last non-nil handler result wins"
    (let [u1 (event-bus/on-event :context
                                 (fn [_ev] {:messages ["first"]}))
          u2 (event-bus/on-event :context
                                 (fn [_ev] nil)) ;; nil result — first wins
          u3 (event-bus/on-event :context
                                 (fn [_ev] {:messages ["second"]}))]
      (t/is (= ["second"] (ai-hooks/apply-context-hook ["orig"]))
            "last non-nil handler result wins (pi chains)")
      (u1) (u2) (u3))
    (testing "a throwing handler is skipped"
      (let [u1 (event-bus/on-event :context
                                   (fn [_ev] (throw (ex-info "boom" {}))))
            u2 (event-bus/on-event :context
                                   (fn [_ev] {:messages ["kept"]}))]
        ;; The throwing listener prints a warning to stderr — suppress it.
        (binding [*err* (java.io.StringWriter.)]
          (t/is (= ["kept"] (ai-hooks/apply-context-hook ["orig"]))))
        (u1) (u2))
      (t/is (= ["orig"] (ai-hooks/apply-context-hook ["orig"]))
            "no handlers — passthrough"))
    (testing "before-provider-headers: the returned map replaces the headers"
      (let [u1 (event-bus/on-event :before-provider-headers
                                   (fn [_ev] {"x-api-key" "k"}))]
        (t/is (= {"x-api-key" "k"}
                 (ai-hooks/apply-before-provider-headers-hook {"a" "b"})))
        (u1))
      (t/is (= {"a" "b"} (ai-hooks/apply-before-provider-headers-hook {"a" "b"}))))
    (testing "before-provider-request: the last non-nil result replaces the payload"
      (let [u1 (event-bus/on-event :before-provider-request
                                   (fn [_ev] {:payload :replaced}))]
        (t/is (= {:payload :replaced}
                 (ai-hooks/apply-before-provider-request-hook {:payload :orig}))
              "the bridge returns the handler's replacement payload")
        (u1))
      (t/is (= {:payload :orig} (ai-hooks/apply-before-provider-request-hook {:payload :orig}))))
    (testing "after-provider-response fires with status/headers"
      (let [seen (atom nil)
            u1 (event-bus/on-event :after-provider-response
                                   (fn [ev] (reset! seen ev)))]
        (ai-hooks/apply-after-provider-response-hook 200 {"h" "v"})
        (t/is (= 200 (:status @seen)))
        (t/is (= {"h" "v"} (:headers @seen)))
        (u1)))))

(t/deftest test-resources-discover
  (extensions/clear-extensions!)
  (event-bus/clear-event-listeners!)
  (let [dir (str "target/test-ext-resources-" (System/currentTimeMillis))
        skill-dir (str dir "/skill")
        prompt-dir (str dir "/prompt")
        theme-dir (str dir "/theme")
        colors (into {} (map (fn [k] [(name k) "#000000"])
                             (concat theme/FG-TOKENS theme/BG-TOKENS)))
        unsubs (atom [])
        seen-reasons (atom [])]
    (fs/create-dirs skill-dir)
    (fs/create-dirs prompt-dir)
    (fs/create-dirs theme-dir)
    (spit (str skill-dir "/SKILL.md")
          "---\nname: ext-skill\ndescription: From an extension\n---\nSkill body")
    (spit (str prompt-dir "/ext-prompt.md") "Prompt body from an extension")
    (spit (str theme-dir "/ext-theme.edn")
          (pr-str {:name "ext-theme" :colors colors}))
    (try
      (testing "handler results are all collected and applied (pi: emitResourcesDiscover)"
        (swap! unsubs conj
               (event-bus/on-event :resources-discover
                                   (fn [ev]
                                     (swap! seen-reasons conj (:reason ev))
                                     (t/is (string? (:cwd ev)))
                                     {:skill-paths [skill-dir]
                                      :prompt-paths [prompt-dir]
                                      :theme-paths [theme-dir]})))
        (let [result (extensions/discover-resources! :startup)]
          (t/is (some? (skills/get-skill "ext-skill"))
                "contributed skill dir loads into the skills registry")
          (t/is (some? (prompts/get-prompt-template "ext-prompt"))
                "contributed prompt dir loads into the prompts registry")
          (t/is (some? (theme/get-theme "ext-theme"))
                "contributed theme dir loads into the theme store")
          (t/is (= {:skill-paths [skill-dir]
                    :prompt-paths [prompt-dir]
                    :theme-paths [theme-dir]}
                   result)
                "the collected path lists are returned")))
      (testing "re-discovery after session-start dedups (pi: mergePaths)"
        (extensions/discover-resources! :startup)
        (t/is (= 1 (count (filter #(= "ext-prompt" (:name %))
                                  (prompts/get-prompt-templates))))
              "the prompt is not loaded twice"))
      (testing "a throwing handler is skipped, others still contribute"
        (swap! unsubs conj
               (event-bus/on-event :resources-discover
                                   (fn [_] (throw (ex-info "boom" {})))))
        ;; The throwing listener prints a warning to stderr — suppress it.
        (binding [*err* (java.io.StringWriter.)]
          (extensions/discover-resources! :reload))
        (t/is (some? (skills/get-skill "ext-skill"))
              "the good handler's paths were already applied")
        (t/is (= [:startup :startup :reload] @seen-reasons)
              "every discovery carried its reason (:startup | :reload)"))
      (finally
        (doseq [u @unsubs] (u))
        (event-bus/clear-event-listeners!)
        (extensions/clear-extensions!)
        (skills/clear-skills!)
        (prompts/clear-prompt-templates!)
        (fs/delete-tree dir)))))

(t/deftest test-jar-extension-load-resource-unload
  ;; Jar distribution: code served from the archive without
  ;; expansion — per-call ZipFile on babashka, the archive root on Jolt's
  ;; native loader (jars load in place) — resources via the shadowed
  ;; io/resource, :extension-dir nil, unload clean.
  ;; The space exercises file-URL escaping through jar:file URLs on Jolt.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-jar-src"
        jar "target/test ext-jar.jar"]
    (fs/delete-tree dir)
    (fs/delete-if-exists jar)
    (fs/create-dirs (str dir "/jar_ext"))
    (spit (str dir "/extension.edn") "{:name \"jar-ext\" :entry jar-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/jar_ext/main.clj")
          (str "(ns jar-ext.main\n"
               "  (:require [kmet.extension :as ext]\n"
               "            [jar-ext.helper :as helper]\n"
               "            [clojure.java.io :as io]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"jar-ext-tool\"\n"
               "                           :description \"from a jar\"\n"
               "                           :execute (fn [_] {:content (str (helper/answer)\n"
               "                                                          \"|\" (slurp (io/resource \"data.txt\")))})}))\n"))
    (spit (str dir "/jar_ext/helper.clj")
          "(ns jar-ext.helper)\n(defn answer [] \"jar-ok\")\n")
    (spit (str dir "/data.txt") "bundled")
    (try
      (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream jar))]
        (doseq [rel ["extension.edn" "data.txt" "jar_ext/main.clj" "jar_ext/helper.clj"]]
          (.putNextEntry zos (java.util.zip.ZipEntry. rel))
          (io/copy (io/file (str dir "/" rel)) zos)
          (.closeEntry zos)))
      (let [result (extensions/load-extension! jar)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "jar-ok|bundled" (:content (tools/execute-tool "jar-ext-tool" {})))
              "own namespaces resolve + io/resource reads the jar entry")
        (t/is (= :jar (:kind (first (extensions/get-loaded-extensions)))))
        (t/is (nil? (:extension-dir (first (extensions/get-loaded-extensions))))
              ":extension-dir is nil for jars"))
      (testing ".zip suffix loads identically"
        (let [zip "target/test-ext-jar.zip"]
          (fs/delete-if-exists zip)
          (fs/copy jar zip)
          (extensions/unload-all-extensions!)
          (let [result (extensions/load-extension! zip)]
            (t/is (nil? (:error result)) (str "loaded: " (:error result)))
            (t/is (= "jar-ok|bundled" (:content (tools/execute-tool "jar-ext-tool" {})))))
          ;; The JVM caches JarURLConnection archives; unload before deleting.
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists zip)))
      (testing "load-extensions-from-dir picks up top-level jars"
        (let [container "target/test-ext-jar-container"]
          (fs/delete-tree container)
          (fs/create-dirs container)
          (fs/copy jar (str container "/jar-ext.jar"))
          (extensions/unload-all-extensions!)
          (let [results (extensions/load-extensions-from-dir container)]
            (t/is (= 1 (count (filter #(nil? (:error %)) results)))
                  (str "jar picked up: " (pr-str results)))
            (t/is (= "jar-ok|bundled" (:content (tools/execute-tool "jar-ext-tool" {})))))
          (extensions/unload-all-extensions!)
          (fs/delete-tree container)))
      (testing "the SCI backend serves the archive too"
        ;; The space also exercises escaped jar:file URLs on Jolt's SCI fallback.
        (let [sci-jar "target/test ext-jar-sci.jar"]
          (fs/delete-if-exists sci-jar)
          (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream sci-jar))]
            (doseq [rel ["extension.edn" "jar_ext/main.clj" "jar_ext/helper.clj" "data.txt"]]
              (.putNextEntry zos (java.util.zip.ZipEntry. rel))
              (if (= rel "extension.edn")
                (.write zos (.getBytes "{:name \"jar-ext\" :entry jar-ext.main :loader [:sci]}\n" "UTF-8"))
                (io/copy (io/file (str dir "/" rel)) zos))
              (.closeEntry zos)))
          (extensions/unload-all-extensions!)
          (let [result (extensions/load-extension! sci-jar)]
            (t/is (nil? (:error result)) (str "loaded: " (:error result)))
            (t/is (= :sci (:loader-kind result)))
            (t/is (= "jar-ok|bundled" (:content (tools/execute-tool "jar-ext-tool" {})))))
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists sci-jar)))
      (testing "sloppy ns (file declares another namespace) fails with an actionable error"
        (let [sdir "target/test-ext-jar-sloppy-src"
              sjar "target/test-ext-jar-sloppy.jar"]
          (fs/delete-tree sdir)
          (fs/delete-if-exists sjar)
          (fs/create-dirs (str sdir "/sloppy"))
          (spit (str sdir "/extension.edn") "{:name \"jar-sloppy\" :entry sloppy.main :loader [:sci :jolt]}\n")
          (spit (str sdir "/sloppy/main.clj")
                "(ns wrong.place)\n(defn init [api] nil)\n")
          (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream sjar))]
            (doseq [rel ["extension.edn" "sloppy/main.clj"]]
              (.putNextEntry zos (java.util.zip.ZipEntry. rel))
              (io/copy (io/file (str sdir "/" rel)) zos)
              (.closeEntry zos)))
          (extensions/unload-all-extensions!)
          (let [result (extensions/load-extension! sjar)]
            (t/is (some? (:error result)))
            (t/is (str/includes? (:error result) "strict layout violation")
                  (str "actionable error, got: " (:error result))))
          (fs/delete-tree sdir)
          (fs/delete-if-exists sjar)))
      (finally
        (extensions/unload-all-extensions!)
        (t/is (nil? (tools/get-tool "jar-ext-tool")) "unload removes the tool")
        (fs/delete-tree dir)
        (fs/delete-if-exists jar)))))

(t/deftest test-extension-dir-resource-fallback
  ;; dir extensions keep :extension-dir + a working io/resource file-URL
  ;; fallback (same test jar layout, unpacked).
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-dir-resource"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/dir_ext"))
    (spit (str dir "/extension.edn") "{:name \"dir-ext\" :entry dir-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/dir_ext/main.clj")
          (str "(ns dir-ext.main\n"
               "  (:require [kmet.extension :as ext]\n"
               "            [clojure.java.io :as io]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"dir-ext-tool\"\n"
               "                           :description \"from a dir\"\n"
               "                           :execute (fn [_] {:content (slurp (io/resource \"data.txt\"))})}))\n"))
    (spit (str dir "/data.txt") "dir-data")
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "dir-data" (:content (tools/execute-tool "dir-ext-tool" {}))))
        (t/is (= (str (fs/canonicalize dir))
                 (str (fs/canonicalize (:extension-dir (first (extensions/get-loaded-extensions))))))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-self-registers-skill-and-prompt
  ;; Extensions self-register bundled content through
  ;; ext/register-skill! / ext/register-prompt! (no host enumeration);
  ;; unload deregisters exactly what the extension added.
  (extensions/clear-extensions!)
  (skills/clear-skills!)
  (prompts/clear-prompt-templates!)
  (let [dir "target/test-ext-selfreg"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/selfreg"))
    (spit (str dir "/SKILL.md")
          "---\nname: selfreg-skill\ndescription: Self-registered skill\n---\nSkill body here")
    (spit (str dir "/tpl.md")
          "---\ndescription: Self-registered template\n---\nTemplate body $1")
    (spit (str dir "/extension.edn") "{:name \"selfreg\" :entry selfreg.main :loader [:sci :jolt]}\n")
    (spit (str dir "/selfreg/main.clj")
          (str "(ns selfreg.main\n"
               "  (:require [kmet.extension :as ext]\n"
               "            [clojure.java.io :as io]))\n"
               "(defn init [api]\n"
               "  (ext/register-skill! api (slurp (io/resource \"SKILL.md\"))\n"
               "                        {:location \"selfreg:SKILL.md\"})\n"
               "  (ext/register-prompt! api {:name \"selfreg-tpl\"\n"
               "                             :content (slurp (io/resource \"tpl.md\"))\n"
               "                             :location \"selfreg:tpl.md\"}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "Skill body here" (:body (skills/get-skill "selfreg-skill"))))
        (t/is (= "selfreg:SKILL.md" (:location (skills/get-skill "selfreg-skill"))))
        (t/is (str/includes? (skills/expand-skill-command "/skill:selfreg-skill")
                             "Skill body here")
              "expansion discloses from the in-memory body")
        (t/is (= "Template body hi" (prompts/expand-prompt-template "/selfreg-tpl hi"
                                                                    (prompts/get-prompt-templates)))))
      (finally
        (skills/clear-skills!)
        (prompts/clear-prompt-templates!)
        (extensions/unload-all-extensions!)
        (t/is (nil? (skills/get-skill "selfreg-skill")) "unload removes the skill")
        (t/is (nil? (prompts/get-prompt-template "selfreg-tpl")) "unload removes the template")
        (fs/delete-tree dir)))))

(t/deftest test-shipped-extensions-load-from-src
  ;; the repo's own extensions restructured to src/-as-artifact-root
  ;; Every shipped src/ dir loads through the real runtime.
  ;; The shipped manifests declare :loader — lsp/mcp/review/tree-sitter
  ;; [:jolt :sci], clojure [:sci :jolt]: Jolt picks its native loader when
  ;; :jolt is declared whatever the order, bb the SCI backend.
  ;;
  ;; The clojure extension loads and its tools work on both hosts too: its
  ;; rewrite-clj/edamame deps are declared for Jolt (bb's closure drops the
  ;; Maven jars for its bundled ports), and the classpath loader's
  ;; syntax-quote misresolution — which poisoned rewrite-clj's generated fns
  ;; with `<ns>/custom-zipper?` — is fixed by jolt PR #1075.
  (extensions/clear-extensions!)
  (let [shipped ["extensions/clojure/src"
                 "extensions/lsp-adapter/src"
                 "extensions/mcp-adapter/src"
                 "extensions/review/src"
                 "extensions/tree-sitter/src"]]
    (doseq [path shipped]
      (let [result (extensions/load-extension! path)]
        (t/is (nil? (:error result)) (str path " loaded: " (:error result)))
        (t/is (contains? #{:sci :jolt} (:loader-kind result))
              (str path " selected a backend (declares :loader)")))))
  (testing "tools + skills from the shipped extensions are live"
    (t/is (some? (tools/get-tool "lsp")))
    (t/is (some? (tools/get-tool "mcp")))
    (t/is (some? (skills/get-skill "mcp")))
    (t/is (some? (tools/get-tool "clojure_edit")))
    (t/is (some? (skills/get-skill "clojure-edit"))))
  (testing "extension skills disclose from memory"
    (t/is (str/includes? (skills/expand-skill-command "/skill:mcp") "mcp"))
    (t/is (str/includes? (skills/expand-skill-command "/skill:clojure-edit")
                         "clojure_edit")))
  (extensions/unload-all-extensions!)
  (skills/clear-skills!)
  (prompts/clear-prompt-templates!))

(t/deftest test-manifest-loader-declaration
  ;; the manifest :loader declaration + host selection: :sci on babashka,
  ;; :jolt preferred on Jolt with the :sci fallback, skip (not failure) when
  ;; the host offers none of the declared backends.
  (extensions/clear-extensions!)
  (let [mk (fn [name manifest]
             (let [dir (str "target/test-ext-loader-" name)]
               (fs/delete-tree dir)
               (fs/create-dirs (str dir "/loader_probe"))
               (spit (str dir "/extension.edn") manifest)
               (spit (str dir "/loader_probe/main.clj")
                     "(ns loader-probe.main)\n(defn init [api] nil)\n")
               dir))
        entry "loader-probe.main"
        manifest (fn [name loader-part]
                   (str "{:name \"" name "\" :entry " entry
                        (when loader-part (str " :loader " loader-part)) "}\n"))]
    (try
      (testing ":sci loads on every host (it is Jolt's declared fallback)"
        (let [r (extensions/load-extension! (mk "sci" (manifest "ld-sci" "[:sci]")))]
          (t/is (nil? (:error r)) (str "loaded: " (:error r)))
          (t/is (= :sci (:loader-kind r))))
        (extensions/unload-all-extensions!))
      (testing "the host's preference decides between declared backends"
        (doseq [[n ls] [["js" "[:jolt :sci]"] ["sj" "[:sci :jolt]"]]]
          (let [r (extensions/load-extension! (mk n (manifest (str "ld-" n) ls)))]
            (t/is (nil? (:error r)) (str n " loaded: " (:error r)))
            (t/is (= (if (host/jolt?) :jolt :sci) (:loader-kind r))
                  "declaration order is not a ranking"))
          (extensions/unload-all-extensions!)))
      (testing ":jolt alone loads on Jolt and skips where that backend does not exist"
        (let [r (extensions/load-extension! (mk "jolt" (manifest "ld-jolt" "[:jolt]")))]
          (if (host/jolt?)
            (t/is (= :jolt (:loader-kind r)))
            (do (t/is (true? (:skipped r)))
                (t/is (= :unsupported-loader (:reason r)))
                (t/is (= [:jolt] (:declared-loaders r)))
                (t/is (= [:sci] (:available-loaders r)))
                (t/is (nil? (:error r)) "a skip is informational, not a failure"))))
        (extensions/unload-all-extensions!))
      (testing "a declaration with no host backend is skipped, with a note"
        (let [dir (mk "jvm" (manifest "ld-jvm" "[:jvm]"))
              err-w (java.io.StringWriter.)
              [r] (binding [*err* err-w] (extensions/load-extension-paths! [dir]))]
          (t/is (true? (:skipped r)))
          (t/is (not-any? #(= "ld-jvm" (:name %)) (extensions/get-loaded-extensions)))
          (t/is (str/includes? (str err-w) "skipping")))
        (extensions/unload-all-extensions!))
      (testing "a legacy manifest (no :loader) defaults to both backends with a warning"
        (let [err-w (java.io.StringWriter.)
              r (binding [*err* err-w]
                  (extensions/load-extension! (mk "legacy" (manifest "ld-legacy" nil))))]
          (t/is (nil? (:error r)) (str "loaded: " (:error r)))
          (t/is (= (if (host/jolt?) :jolt :sci) (:loader-kind r)))
          (t/is (str/includes? (str err-w) "no :loader")))
        (extensions/unload-all-extensions!))
      (testing "a malformed :loader is an error result, not a throw"
        (let [r (extensions/load-extension! (mk "bad" (manifest "ld-bad" "\"sci\"")))]
          (t/is (nil? (:extension r)))
          (t/is (str/includes? (:error r) ":loader"))))
      (testing "get-loaded-extensions exposes the selected backend"
        (extensions/load-extension! (mk "expose" (manifest "ld-expose" "[:sci]")))
        (t/is (= :sci (:loader-kind (first (filter #(= "ld-expose" (:name %))
                                                   (extensions/get-loaded-extensions)))))))
      (finally
        (extensions/unload-all-extensions!)
        (doseq [n ["sci" "js" "sj" "jolt" "jvm" "legacy" "bad" "expose"]]
          (fs/delete-tree (str "target/test-ext-loader-" n)))))))

(t/deftest ^:slow ^:bb-only test-packed-clojure-jar-roundtrip
  ;; end-to-end jar distribution for a real shipped extension: pack
  ;; extensions/clojure/src with plain zip mechanics, load the jar, run a
  ;; tool, expand its skill, unload. Needs the cljfmt/parinferish closure
  ;; from the network cache (slow).
  (extensions/clear-extensions!)
  (skills/clear-skills!)
  (let [jar "target/test-packed-clojure.jar"]
    (fs/delete-if-exists jar)
    (try
      (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream jar))]
        (doseq [f (sort-by str (filter #(fs/regular-file? %) (fs/glob "extensions/clojure/src" "**")))]
          (let [rel (str (fs/relativize "extensions/clojure/src" f))]
            (.putNextEntry zos (java.util.zip.ZipEntry. rel))
            (io/copy (io/file (str f)) zos)
            (.closeEntry zos))))
      (let [result (extensions/load-extension! jar)]
        (t/is (nil? (:error result)) (str "packed jar loaded: " (:error result)))
        (t/is (some? (tools/get-tool "clojure_edit")))
        (t/is (str/includes? (skills/expand-skill-command "/skill:clojure-edit")
                             "clojure_edit")))
      (finally
        (extensions/unload-all-extensions!)
        (skills/clear-skills!)
        (fs/delete-if-exists jar)))))

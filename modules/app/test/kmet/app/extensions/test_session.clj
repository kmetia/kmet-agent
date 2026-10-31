(ns kmet.app.extensions.test-session
  "Session facades through the api: message and renderer ownership, headless
   registration, provider event bridges, skill/prompt self-registration, resources
   discovery, and the shipped extensions."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [kmet.extension :as ext]
            [kmet.libs.host :as host]
            [kmet.app.events :as events]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.registries :as registries]
            [kmet.app.registry :as registry]
            [kmet.srce.core :as srce]
            [kmet.app.prompts :as prompts]
            [kmet.app.session.core :as session]
            [kmet.app.skills :as skills]
            [kmet.app.test-support :as support]
            [kmet.test-utils :refer [spit-plugin]]
            [kmet.libs.theme :as theme]
            [kmet.app.tools.registry :as tools-registry]))

;; The extension host is the composition's: boot one for the namespace.
(t/use-fixtures :once support/with-composition)

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
  (let [owner (extensions/extension-owner "test-markdown-transformers")]
    (testing "applied in registration order, last wins per position"
      (extensions/clear-extensions!)
      (let [d1 (extensions/register-markdown-transformer! owner (fn [md _ctx] (str "a[" md "]")))
            d2 (extensions/register-markdown-transformer! owner (fn [md _ctx] (str "b[" md "]")))]
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
      (let [d1 (extensions/register-markdown-transformer! owner (fn [_md _ctx] (throw (ex-info "boom" {}))))
            d2 (extensions/register-markdown-transformer! owner (fn [md _ctx] (str "ok[" md "]")))]
        (t/is (= "ok[hi]" (extensions/apply-markdown-transformers "hi" {}))
              "pi: keep the current markdown and continue")
        (d1) (d2)))
    (testing "non-string results are ignored"
      (extensions/clear-extensions!)
      (let [d1 (extensions/register-markdown-transformer! owner (fn [_md _ctx] {:not :a-string}))
            d2 (extensions/register-markdown-transformer! owner (fn [md _ctx] (str md "!")))]
        (t/is (= "hi!" (extensions/apply-markdown-transformers "hi" {})))
        (d1) (d2)))
    (testing "the registration is the owner's key in the host registry"
      (let [own? (fn [] (some (fn [[_ m]] (= owner (:owner m)))
                              (registry/entries (registry/current) :kmet/hook)))
            dereg (extensions/register-markdown-transformer! owner (fn [md _] md))]
        (t/is (own?) "the transformer registered under its owner's path")
        (dereg)
        (t/is (not (own?)) "and the deregister fn removes that key")))))

(t/deftest test-renderer-ownership
  (testing "a renderer is a registration owned by its owner"
    ;; The renderers have no seam of their own yet, so they live in the registry
    ;; façade's remainder handle; ownership is the registry's, not a store path.
    (let [owner (extensions/extension-owner "test-renderer-owner")
          dereg (extensions/register-entry-renderer! owner "probe-entry" (fn [_] {:role :info}))]
      (t/is (= [owner] (vec (keys (registry/by-owner (registry/current)
                                                     :kmet/entry-renderer "probe-entry"))))
            "the renderer registers under its owner")
      (t/is (some? (extensions/get-entry-renderer "probe-entry")))
      (dereg)
      (t/is (nil? (extensions/get-entry-renderer "probe-entry"))
            "and the deregister fn removes that key")))
  (testing "message renderers share the shape"
    (let [owner (extensions/extension-owner "test-message-renderer-owner")
          dereg (extensions/register-message-renderer! owner "probe-message" (fn [_] {:role :info}))]
      (t/is (= [owner] (vec (keys (registry/by-owner (registry/current)
                                                     :kmet/message-renderer
                                                     "probe-message")))))
      (t/is (some? (extensions/get-message-renderer "probe-message")))
      (dereg)
      (t/is (nil? (extensions/get-message-renderer "probe-message")))))
  (testing "the first owner that took a type keeps it"
    ;; One renderer per custom type, so a later owner's re-registration cannot
    ;; shadow the earlier one: lookup reads owners in first-registration order.
    (let [first-owner (extensions/extension-owner "test-renderer-first")
          second-owner (extensions/extension-owner "test-renderer-second")
          renderer (fn [_] {:role :info :content "first"})
          dereg (extensions/register-entry-renderer! first-owner "taken-type" renderer)]
      (try
        (extensions/register-entry-renderer! second-owner "taken-type" (fn [_] {:role :info :content "second"}))
        (t/is (identical? renderer (extensions/get-entry-renderer "taken-type")))
        (finally
          (dereg)
          (registries/deregister-owner! (registry/current) second-owner))))))

(t/deftest test-flag-ownership
  ;; A flag is the registering owner's entry in the registry; the --ext-* value
  ;; get-flag coerces is process argv (set-cli-flags!), not engine state.
  (let [owner (extensions/extension-owner "test-flag-owner")
        dereg (extensions/register-flag! owner "probe-flag"
                                         {:type :boolean :default false})]
    (try
      (t/is (= [owner] (vec (keys (registry/by-owner (registry/current)
                                                     :kmet/flag "probe-flag"))))
            "the flag registers under its owner")
      (t/is (false? (extensions/get-flag "probe-flag"))
            "the registered default applies without argv")
      (extensions/set-cli-flags! {"probe-flag" "true"})
      (t/is (true? (extensions/get-flag "probe-flag"))
            "the argv value is coerced by the registered :type")
      (extensions/set-cli-flags! {"probe-flag" "false"})
      (t/is (false? (extensions/get-flag "probe-flag")))
      (finally
        (extensions/set-cli-flags! {})
        (dereg)
        (t/is (nil? (extensions/get-flag "probe-flag"))
              "the deregister fn removes the registered flag")))))

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
    ;; no runtime capabilities bound: the sink fallback is the runtime-less
    ;; path (kmet.app.test-ui-port covers the engine-backed path)
    (extensions/unbind-extensions!)
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

(defn- on-context
  "The bridge's :on-context hook (extensions/provider-hooks (support/composition-ctx)) — the request-time
   context phases provider events flow through."
  [messages]
  ((:on-context (extensions/provider-hooks (support/composition-ctx))) messages))

(defn- on-payload [payload] ((:on-payload (extensions/provider-hooks (support/composition-ctx))) payload))
(defn- on-headers [headers] ((:on-headers (extensions/provider-hooks (support/composition-ctx))) headers))
(defn- on-response [status headers]
  ((:on-response (extensions/provider-hooks (support/composition-ctx))) {:status status :headers headers}))

(t/deftest test-provider-event-bridges
  (testing "bus events drive the per-call provider hooks; last non-nil handler result wins"
    (let [u1 (support/on-event :context
                               (fn [_ev] {:messages ["first"]}))
          u2 (support/on-event :context
                               (fn [_ev] nil)) ;; nil result — first wins
          u3 (support/on-event :context
                               (fn [_ev] {:messages ["second"]}))]
      (t/is (= ["second"] (on-context ["orig"]))
            "last non-nil handler result wins (pi chains)")
      (u1) (u2) (u3))
    (testing "a throwing handler is skipped"
      (let [u1 (support/on-event :context
                                 (fn [_ev] (throw (ex-info "boom" {}))))
            u2 (support/on-event :context
                                 (fn [_ev] {:messages ["kept"]}))]
        ;; The throwing listener prints a warning to stderr — suppress it.
        (binding [*err* (java.io.StringWriter.)]
          (t/is (= ["kept"] (on-context ["orig"]))))
        (u1) (u2))
      (t/is (= ["orig"] (on-context ["orig"]))
            "no handlers — passthrough"))
    (testing "before-provider-headers: the returned map replaces the headers"
      (let [u1 (support/on-event :before-provider-headers
                                 (fn [_ev] {"x-api-key" "k"}))]
        (t/is (= {"x-api-key" "k"}
                 (on-headers {"a" "b"})))
        (u1))
      (t/is (= {"a" "b"} (on-headers {"a" "b"}))))
    (testing "before-provider-request: the last non-nil result replaces the payload"
      (let [u1 (support/on-event :before-provider-request
                                 (fn [_ev] {:payload :replaced}))]
        (t/is (= {:payload :replaced}
                 (on-payload {:payload :orig}))
              "the bridge returns the handler's replacement payload")
        (u1))
      (t/is (= {:payload :orig} (on-payload {:payload :orig}))))
    (testing "after-provider-response fires with status/headers"
      (let [seen (atom nil)
            u1 (support/on-event :after-provider-response
                                 (fn [ev] (reset! seen ev)))]
        (on-response 200 {"h" "v"})
        (t/is (= 200 (:status @seen)))
        (t/is (= {"h" "v"} (:headers @seen)))
        (u1)))))

(t/deftest test-context-with-system-phase
  (testing "the request-time context phases (pi: emitContext): :context sees
            the conversation only and the system prompt is re-attached to a
            replacement; :context-with-system then sees the full transcript
            and its result is used verbatim"
    (let [system-msg {:role :system :content [{:type :text :text "prompt"}]}
          user-msg {:role :user :content [{:type :text :text "hi"}]}
          seen (atom [])
          u1 (support/on-event :context
                               (fn [ev]
                                 (swap! seen conj [:context (mapv :role (:messages ev))])
                                 nil))
          u2 (support/on-event :context-with-system
                               (fn [ev]
                                 (swap! seen conj [:with-system (mapv :role (:messages ev))])
                                 nil))]
      (try
        (t/is (= [system-msg user-msg] (on-context [system-msg user-msg]))
              "no replacement — the transcript passes through")
        (t/is (= [[:context [:user]] [:with-system [:system :user]]] @seen)
              "phase order, and what each phase sees")
        (finally (u1) (u2))))
    (testing "a :context replacement gets the system prompt re-attached"
      (let [system-msg {:role :system :content [{:type :text :text "prompt"}]}
            u (support/on-event :context
                                (fn [_] {:messages [{:role :user :content "pruned"}]}))]
        (try
          (t/is (= [system-msg {:role :user :content "pruned"}]
                   (on-context [system-msg {:role :user :content "orig"}]))
                "pi: restoreSystemMessages — a pruning handler keeps the prompt")
          (finally (u)))))
    (testing "a :context-with-system result is used verbatim, even without the prompt"
      (let [system-msg {:role :system :content [{:type :text :text "prompt"}]}
            user-msg {:role :user :content [{:type :text :text "hi"}]}
            u (support/on-event :context-with-system (fn [_] {:messages [user-msg]}))
            err (java.io.StringWriter.)]
        (try
          (let [out (binding [*err* err] (on-context [system-msg user-msg]))]
            (t/is (= [user-msg] out) "honored as returned (pi: report, honor)")
            (t/is (str/includes? (str err) "removed the leading system message")
                  "and reported as an extension error"))
          (finally (u)))))))

(t/deftest test-resources-discover
  (extensions/clear-extensions!)
  (support/clear-listeners!)
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
               (support/on-event :resources-discover
                                 (fn [ev]
                                   (swap! seen-reasons conj (:reason ev))
                                   (t/is (string? (:cwd ev)))
                                   {:skill-paths [skill-dir]
                                    :prompt-paths [prompt-dir]
                                    :theme-paths [theme-dir]})))
        (let [result (extensions/discover-resources! (support/composition-ctx) :startup)]
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
        (extensions/discover-resources! (support/composition-ctx) :startup)
        (t/is (= 1 (count (filter #(= "ext-prompt" (:name %))
                                  (prompts/get-prompt-templates))))
              "the prompt is not loaded twice"))
      (testing "a throwing handler is skipped, others still contribute"
        (swap! unsubs conj
               (support/on-event :resources-discover
                                 (fn [_] (throw (ex-info "boom" {})))))
        ;; The throwing listener prints a warning to stderr — suppress it.
        (binding [*err* (java.io.StringWriter.)]
          (extensions/discover-resources! (support/composition-ctx) :reload))
        (t/is (some? (skills/get-skill "ext-skill"))
              "the good handler's paths were already applied")
        (t/is (= [:startup :startup :reload] @seen-reasons)
              "every discovery carried its reason (:startup | :reload)"))
      (finally
        (doseq [u @unsubs] (u))
        (support/clear-listeners!)
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
    (spit (str dir "/extension.edn") "{:name \"jar-ext\" :plugin jar-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/jar_ext/main.clj")
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
        (t/is (= "jar-ok|bundled" (:content (tools-registry/execute-tool (registry/current) "jar-ext-tool" {})))
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
            (t/is (= "jar-ok|bundled" (:content (tools-registry/execute-tool (registry/current) "jar-ext-tool" {})))))
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
            (t/is (= "jar-ok|bundled" (:content (tools-registry/execute-tool (registry/current) "jar-ext-tool" {})))))
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
                (.write zos (.getBytes "{:name \"jar-ext\" :plugin jar-ext.main/plugin :loader [:sci]}\n" "UTF-8"))
                (io/copy (io/file (str dir "/" rel)) zos))
              (.closeEntry zos)))
          (extensions/unload-all-extensions!)
          (let [result (extensions/load-extension! sci-jar)]
            (t/is (nil? (:error result)) (str "loaded: " (:error result)))
            (t/is (= :sci (:loader-kind result)))
            (t/is (= "jar-ok|bundled" (:content (tools-registry/execute-tool (registry/current) "jar-ext-tool" {})))))
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists sci-jar)))
      (testing "sloppy ns (file declares another namespace) fails with an actionable error"
        (let [sdir "target/test-ext-jar-sloppy-src"
              sjar "target/test-ext-jar-sloppy.jar"]
          (fs/delete-tree sdir)
          (fs/delete-if-exists sjar)
          (fs/create-dirs (str sdir "/sloppy"))
          (spit (str sdir "/extension.edn") "{:name \"jar-sloppy\" :plugin sloppy.main/plugin :loader [:sci :jolt]}\n")
          (spit-plugin (str sdir "/sloppy/main.clj")
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
        (t/is (nil? (tools-registry/get-tool (registry/current) "jar-ext-tool")) "unload removes the tool")
        (fs/delete-tree dir)
        (fs/delete-if-exists jar)))))

(t/deftest test-extension-dir-resource-fallback
  ;; dir extensions keep :extension-dir + a working io/resource file-URL
  ;; fallback (same test jar layout, unpacked).
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-dir-resource"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/dir_ext"))
    (spit (str dir "/extension.edn") "{:name \"dir-ext\" :plugin dir-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/dir_ext/main.clj")
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
        (t/is (= "dir-data" (:content (tools-registry/execute-tool (registry/current) "dir-ext-tool" {}))))
        (t/is (= (str (fs/canonicalize dir))
                 (str (fs/canonicalize (:extension-dir (first (extensions/get-loaded-extensions))))))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(defn- write-plugin-extension!
  "Write a single-namespace extension at DIR — SUBDIR/main.clj under NS, with
   PLUGIN's map and a no-op `:apply`, named by extension.edn. Returns DIR."
  [dir subdir ns plugin]
  (fs/delete-tree dir)
  (fs/create-dirs (str dir "/" subdir))
  (spit (str dir "/extension.edn")
        (pr-str {:name (name (:name plugin))
                 :plugin (symbol ns "plugin")
                 :loader [:sci :jolt]}))
  (spit (str dir "/" subdir "/main.clj")
        (str "(ns " ns ")\n(def plugin "
             (pr-str (assoc plugin :apply '(fn [_ctx _api] nil)))
             ")\n"))
  dir)

(t/deftest test-an-extensions-events-are-declared-and-swept
  ;; A plugin's `:events` map is declared into the vocabulary registry at mount
  ;; (so `emit!` honours its mode) and swept with the extension's owner on
  ;; unload — the `:kmet/event` tag rides the host's own sweep list.
  (let [dir (write-plugin-extension!
             "target/test-ext-events" "evt_ext" "evt-ext.main"
             {:name :evt-ext
              :events {:evt/ping {:mode :emit :domain :app :doc "fixture"}}})
        ctx (support/composition-ctx)]
    (try
      (extensions/unload-all-extensions!)
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= :emit (:mode (events/declaration ctx :evt/ping)))
              "the extension's own event is declared")
        (extensions/unload-all-extensions!)
        (t/is (nil? (events/declaration ctx :evt/ping))
              "unload sweeps the declaration with its owner"))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-an-extensions-declaration-is-an-effect-of-its-mount
  ;; An active extension's events are declared as an effect of its plugin fiber
  ;; (`declare-fiber!`), the same way a composition row's are: the mount's
  ;; disposal removes the vocabulary, not only the unload sweep.
  (let [dir (write-plugin-extension!
             "target/test-ext-events-dispose" "evt_disp" "evt-disp.main"
             {:name :evt-disp
              :events {:evt/disp {:mode :emit :domain :app :doc "fixture"}}})
        ctx (support/composition-ctx)]
    (try
      (extensions/unload-all-extensions!)
      (let [result (extensions/load-extension! dir)
            mount (first (filter #(= :evt-disp (:name %)) (srce/fibers ctx)))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= :active (:state mount)) "the plugin mounted")
        (t/is (some #{"declare :evt-disp"} (:effects mount))
              "the declaration is an effect of the mount")
        (t/is (some? (events/declaration ctx :evt/disp))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-a-pending-extensions-declaration-is-swept-on-unload
  ;; A pending mount cannot own effects, so its declaration is direct and the
  ;; host's unload sweep is its teardown — the other half of `declare-fiber!`.
  (let [dir (write-plugin-extension!
             "target/test-ext-events-pending" "evt_pending" "evt-pending.main"
             {:name :evt-pending
              :inject [:nothing-provides-this]
              :events {:evt/pending {:mode :emit :domain :app :doc "fixture"}}})
        ctx (support/composition-ctx)]
    (try
      (extensions/unload-all-extensions!)
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (true? (:pending result)) "the mount is pending on its inject")
        (t/is (some? (events/declaration ctx :evt/pending))
              "a pending mount still declares its events")
        (extensions/unload-all-extensions!)
        (t/is (nil? (events/declaration ctx :evt/pending))
              "unload sweeps it"))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-an-extension-cannot-redeclare-a-core-event
  ;; The vocabulary is one contract: a plugin adding a name is fine, changing
  ;; how a core event dispatches fails the load — and the load-failure unload
  ;; disposes the mount, so nothing of it stays in the tree.
  (let [dir (write-plugin-extension!
             "target/test-ext-hijack" "hijack" "hijack.main"
             {:name :hijack
              :events {:turn-end {:mode :emit :domain :loop :doc "hijacked"}}})
        ctx (support/composition-ctx)]
    (try
      (extensions/unload-all-extensions!)
      (let [before (count (srce/fibers ctx))
            result (extensions/load-extension! dir)]
        (t/is (some? (:error result)) "the load fails")
        (t/is (str/includes? (:error result) "already declared differently"))
        (t/is (= before (count (srce/fibers ctx)))
              "and no fiber of it is left mounted")
        (t/is (= :boundary (:mode (events/declaration ctx :turn-end)))
              "the core event still dispatches as a boundary"))
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
    (spit (str dir "/extension.edn") "{:name \"selfreg\" :plugin selfreg.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/selfreg/main.clj")
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

(def ^:private shipped-extension-srcs
  "The repo's own extensions, src/-as-artifact-root — every one of them loads
   through the real runtime (see `test-shipped-extensions-load-from-src`)."
  ["extensions/clojure/src"
   "extensions/lsp-adapter/src"
   "extensions/mcp-adapter/src"
   "extensions/review/src"
   "extensions/tree-sitter/src"])

(def ^:private shipped-check-probe
  "The child process behind `test-shipped-extensions-load-from-src`: load each
   path through the real runtime over a bare extension host, then print one EDN
   line per load result and a last line with the tools and skills it left live."
  "(require '[kmet.app.extensions.core :as extensions]
             '[kmet.app.registries :as registries]
             '[kmet.app.registry :as registry]
             '[kmet.app.skills :as skills]
             '[kmet.app.tools.registry :as tools-registry]
             '[kmet.srce.core :as srce])
(let [handle (registry/atom-registry)
      ctx (srce/root {:name :shipped-extensions-check})
      base (extensions/host-binding)]
  (registry/publish-process-handle! handle)
  (extensions/bind-services! (fn [] (registries/host-services handle ctx)))
  (extensions/clear-extensions!)
  (doseq [path %s]
    (let [r (extensions/load-extension! path)]
      (println (pr-str {:path path :error (:error r) :loader-kind (:loader-kind r)}))))
  (println (pr-str {:tools (into {} (map (fn [n] [n (some? (tools-registry/get-tool handle n))]))
                                 [\"lsp\" \"mcp\" \"clojure_edit\"])
                    :skills (into {} (map (fn [n] [n (some? (skills/get-skill n))]))
                                  [\"mcp\" \"clojure-edit\"])}))
  (extensions/unload-all-extensions!)
  (extensions/bind-services! base))")

(defn- shipped-check-results
  "Load the shipped extensions in a fresh process and return the per-load
   result maps plus the tools/skills summary.

   Out of process on purpose: Jolt's native loader gives an extension private
   copies of its namespaces — it evicts any installed version before
   evaluating and unmaps its own on unload, while the classpath copies the rest
   of this suite links against keep their cells. Loading them here would take
   those cells with it, and a `with-redefs` (how every extension test redirects
   a path) would no longer reach the code under test."
  []
  (let [script (str "target/test-shipped-extensions-" (System/nanoTime) ".clj")
        out (str script ".out")]
    (spit script (format shipped-check-probe (pr-str shipped-extension-srcs)))
    (try
      (let [proc (p/process (if (host/jolt?) ["jolt" script] ["bb" script])
                            {:in :discard :out out :err :inherit})
            {:keys [exit]} (p/check proc)]
        (t/is (zero? exit))
        (let [lines (str/split-lines (slurp out))
              [summary & results] (reverse (mapv edn/read-string lines))]
          (t/is (map? summary))
          {:summary summary
           :results (into {} (map (juxt :path identity) results))}))
      (finally (fs/delete-tree script) (fs/delete-tree out)))))

(t/deftest ^:slow test-shipped-extensions-load-from-src
  ;; the repo's own extensions restructured to src/-as-artifact-root: every
  ;; shipped src/ dir loads through the real runtime.
  ;; The shipped manifests declare :loader — lsp/mcp/review/tree-sitter
  ;; [:jolt :sci], clojure [:sci :jolt]: Jolt picks its native loader when
  ;; :jolt is declared, whatever the order.
  ;;
  ;; The clojure extension loads and its tools work on both hosts: its
  ;; rewrite-clj/edamame deps are declared for Jolt (bb's closure drops the
  ;; Maven jars for its bundled ports), and the classpath loader's
  ;; syntax-quote misresolution — which poisoned rewrite-clj's generated fns
  ;; with `<ns>/custom-zipper?` — is fixed by jolt PR #1075.
  (let [{:keys [summary results]} (shipped-check-results)]
    (doseq [path shipped-extension-srcs]
      (let [{:keys [error loader-kind]} (get results path)]
        (t/is (nil? error) (str path " loaded: " error))
        (t/is (contains? #{:sci :jolt} loader-kind)
              (str path " selected a backend (declares :loader)"))))
    (testing "tools + skills from the shipped extensions are live"
      (t/is (true? (get-in summary [:tools "lsp"])))
      (t/is (true? (get-in summary [:tools "mcp"])))
      (t/is (true? (get-in summary [:skills "mcp"])))
      (t/is (true? (get-in summary [:tools "clojure_edit"])))
      (t/is (true? (get-in summary [:skills "clojure-edit"]))))))

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
               (spit-plugin (str dir "/loader_probe/main.clj")
                            "(ns loader-probe.main)\n(defn init [api] nil)\n")
               dir))
        entry "loader-probe.main"
        manifest (fn [name loader-part]
                   (str "{:name \"" name "\" :plugin " entry "/plugin"
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

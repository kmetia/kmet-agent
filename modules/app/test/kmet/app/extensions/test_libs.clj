(ns kmet.app.extensions.test-libs
  "The shared-library lists against the source trees, the packed-jar roundtrip, and
   extension hooks living in the host's registry (order and disposal)."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.extensions.context :as ext-context]
            [kmet.app.registry :as registry]
            [kmet.app.skills :as skills]
            [kmet.app.test-support :as support]
            [kmet.test-utils :refer [spit-plugin]]
            [kmet.app.tools.registry :as tools]))

;; The extension host is the composition's: boot one for the namespace.
(t/use-fixtures :once support/with-composition)

;; ─── The shared-library lists vs the source trees ────────────────────────

(def ^:private host-backend-namespaces
  "Shared-layer namespaces deliberately outside the extension lists: each
   loads host resources at namespace load, so host-requires!'s eager require
   cannot pull it on every host."
  '#{kmet.libs.clipboard-win        ;; user32/kernel32 FFI at namespace load
     kmet.libs.host-native          ;; Jolt host backend (.jolt source)
     kmet.tui.terminal-jline        ;; imports org.jline.* (bb/JVM only)
     kmet.tui.terminal-native-unix  ;; Jolt/Unix backend (.jolt source)
     kmet.tui.terminal-native-win}) ;; Jolt/Windows backend (.jolt source)

(def ^:private app-shared-namespaces
  "Shared-layer namespaces an extension may require that are not the framework's
   own, both in this module's tree: `kmet.app.keybindings` (the key-hint table)
   and `kmet.ui.tui.tool-renderers` (the built-in tool renderers an extension's
   tools render through)."
  '#{kmet.app.keybindings kmet.ui.tui.tool-renderers})

(defn- library-ns-name
  "The namespace symbol for source file P under DIR, read from its path; PREFIX
   is prefixed unless it is empty (a module whose tree spans namespaces)."
  [prefix dir p]
  (symbol (str (when (seq prefix) (str prefix "."))
               (-> (str/replace (str (fs/relativize (fs/path dir) p)) "\\" "/")
                   (str/replace #"\.(clj|cljc|jolt|bb)$" "")
                   (str/replace "_" "-")
                   (str/replace "/" ".")))))

(defn- shared-source-namespaces
  "Every namespace defined in the shared TUI/libs source trees — the lists'
   universe, host backends included so the test can pin their exclusion."
  []
  (let [roots [["kmet.tui" "modules/tui/src/kmet/tui"]
               ;; the whole libs module: the theme loader and the fuzzy matcher
               ;; are kmet.libs.* leaves that the components and the world load
               ;; share
               ["" "modules/libs/src"]
               ["kmet.hikap" "modules/hikap/src/kmet/hikap"]
               ["kmet.reakt" "modules/reakt/src/kmet/reakt"]]]
    (into #{}
          (mapcat (fn [[prefix dir]]
                    (map #(library-ns-name prefix dir %)
                         (concat (fs/glob dir "*.{clj,cljc,jolt,bb}")
                                 (fs/glob dir "**/*.{clj,cljc,jolt,bb}")))))
          roots)))

(t/deftest test-shared-library-lists-match-the-source-trees
  (testing "the extension shared-library lists and the source trees agree:
            a namespace added or removed without updating its list fails"
    (let [entries (into (set @#'ext-context/tui-library-namespaces)
                        @#'ext-context/libs-library-namespaces)
          sources (shared-source-namespaces)
          known (into entries (into host-backend-namespaces app-shared-namespaces))]
      (t/is (< 60 (count entries)) "the lists are real, not empty")
      (t/is (< 60 (count sources)) "the source scan found the trees")
      (t/is (empty? (remove known sources))
            (str "shared namespaces missing from the lists: "
                 (vec (sort (remove known sources)))))
      (t/is (empty? (remove (into sources app-shared-namespaces) entries))
            (str "stale shared-library list entries: "
                 (vec (sort (remove (into sources app-shared-namespaces) entries)))))
      (t/is (empty? (filter entries host-backend-namespaces))
            "host backends stay out of the eagerly-required lists")
      (t/is (every? fs/exists? ["modules/app/src/kmet/app/keybindings.clj"
                                "modules/app/src/kmet/ui/tui/tool_renderers.clj"])
            "the shared-layer list entries have source files"))))

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
          ;; jar entries must use / separators — fs/relativize gives \ on Windows
          (let [rel (str/replace (str (fs/relativize "extensions/clojure/src" f)) "\\" "/")]
            (.putNextEntry zos (java.util.zip.ZipEntry. rel))
            (io/copy (io/file (str f)) zos)
            (.closeEntry zos))))
      (let [result (extensions/load-extension! jar)]
        (t/is (nil? (:error result)) (str "packed jar loaded: " (:error result)))
        (t/is (some? (tools/get-tool (registry/current) "clojure_edit")))
        (t/is (str/includes? (skills/expand-skill-command "/skill:clojure-edit")
                             "clojure_edit")))
      (finally
        (extensions/unload-all-extensions!)
        (skills/clear-skills!)
        (fs/delete-if-exists jar)))))

;; ─── Extension hooks live in the host's registry (P2) ─────────────────────

(t/deftest extension-hooks-live-in-the-host-registry
  ;; whatever an extension's plugin body installs belongs to
  ;; [:app :extensions <name>]. Hooks are read from one registry rather than
  ;; copied into each engine, so one registered with no engine running
  ;; reaches every engine, and deregistering removes the only copy.
  (let [owner (extensions/extension-owner "test-hooks")
        dereg (extensions/register-input-hook! owner (fn [{:keys [text]}] {:action :transform :text (str text "!")}))
        dereg-start (extensions/register-before-agent-start-hook! owner (fn [_] {:system-prompt "SYS"}))]
    (try
      (t/is (= "hi!" (:text (extensions/apply-input-hooks "hi" :interactive)))
            "the registered hook is read back from the registry")
      (t/is (= "SYS" (:system-prompt
                      (extensions/apply-before-agent-start-hooks "p" "orig")))
            "before-agent-start hooks read the same registry")
      (t/is (= {:input 1 :before-agent-start 1}
               (frequencies
                (keep (fn [[n m]] (when (= owner (:owner m)) (first n)))
                      (registry/entries (registry/current) :kmet/hook))))
            "both registered under the extension's own owner, tagged per kind")
      (finally (dereg) (dereg-start)))
    (t/is (= "hi" (:text (extensions/apply-input-hooks "hi" :interactive)))
          "deregistering stops it")))

(t/deftest hooks-run-owner-by-owner-in-registration-order
  ;; Hook order is owner-major whatever the registry's listing order: owners
  ;; in first-registration order, each owner's own registrations in its own
  ;; order — so one extension's hooks stay together and in its own sequence.
  (let [a (extensions/extension-owner "test-hook-order-a")
        b (extensions/extension-owner "test-hook-order-b")
        seen (atom [])
        hook (fn [tag] (fn [_] (swap! seen conj tag) nil))
        dereg-a1 (extensions/register-input-hook! a (hook :a1))
        dereg-b1 (extensions/register-input-hook! b (hook :b1))
        dereg-a2 (extensions/register-input-hook! a (hook :a2))]
    (try
      (extensions/apply-input-hooks "hi" :interactive)
      (t/is (= [:a1 :a2 :b1] @seen)
            "one owner's hooks are adjacent, in its registration order")
      (reset! seen [])
      (extensions/apply-input-hooks "hi" :interactive)
      (t/is (= [:a1 :a2 :b1] @seen) "and the order is stable")
      (finally (dereg-a1) (dereg-b1) (dereg-a2)))))

(t/deftest test-extension-hooks-are-disposed-on-unload
  ;; End to end: ext/on-input and ext/on-before-agent-start register under the
  ;; extension's own owner on the app tree, so unload — which runs the tracked
  ;; deregisters — takes them with it. The pending buffers this replaced kept
  ;; a copy the next engine picked up, so an unloaded extension's hook returned.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-hook-unload"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/hook_ext"))
    (spit (str dir "/extension.edn")
          "{:name \"hook-ext\" :plugin hook-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/hook_ext/main.clj")
                 (str "(ns hook-ext.main\n  (:require [kmet.extension :as ext]))\n"
                      "(defn init [api]\n"
                      "  (ext/on-input api (fn [{:keys [text]}]\n"
                      "                      {:action :transform :text (str text \"-hooked\")}))\n"
                      "  (ext/on-before-agent-start api (fn [_] {:system-prompt \"HOOK SYS\"}))\n"
                      "  (ext/on-tool-call api (fn [_] {:args {:by \"hook-ext\"}})))\n"))
    (try
      (let [tool-hooks (count (extensions/get-tool-call-hooks))
            result (extensions/load-extension! dir)
            owner (extensions/extension-owner "hook-ext")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (str/includes? (:text (extensions/apply-input-hooks "hi" :interactive))
                             "hi-hooked")
              "the extension's input hook runs")
        (t/is (str/includes? (str (:system-prompt
                                   (extensions/apply-before-agent-start-hooks "p" "orig")))
                             "HOOK SYS")
              "and its before-agent-start hook")
        (t/is (= (inc tool-hooks) (count (extensions/get-tool-call-hooks)))
              "and its tool-call hook reaches the transform chain")
        (t/is (= 3 (count (filter (fn [[_ m]] (= owner (:owner m)))
                                  (registry/entries (registry/current) :kmet/hook))))
              "all three live under the extension's owner")
        (extensions/unload-all-extensions!)
        (t/is (= tool-hooks (count (extensions/get-tool-call-hooks)))
              "unload removes the tool-call hook")
        (t/is (not (str/includes? (:text (extensions/apply-input-hooks "hi" :interactive))
                                  "-hooked"))
              "the input hook")
        (t/is (not (str/includes? (str (:system-prompt
                                        (extensions/apply-before-agent-start-hooks "p" "orig")))
                                  "HOOK SYS"))
              "and the before-agent-start hook")
        (t/is (empty? (filter (fn [[_ m]] (= owner (:owner m)))
                              (registry/entries (registry/current) :kmet/hook)))
              "with nothing left under the owner"))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest deregistering-one-hook-leaves-its-neighbours
  ;; One registration, one key, one value guard: removing a hook leaves the
  ;; owner's other hooks alone — including a second registration of the very
  ;; same fn, which the identity removal this replaced also took.
  (let [owner (extensions/extension-owner "test-hook-neighbours")
        seen (atom [])
        shared (fn [_] (swap! seen conj :shared) nil)
        dereg-shared-1 (extensions/register-input-hook! owner shared)
        dereg-other (extensions/register-input-hook! owner (fn [_] (swap! seen conj :other) nil))
        dereg-shared-2 (extensions/register-input-hook! owner shared)]
    (try
      (extensions/apply-input-hooks "hi" :interactive)
      (t/is (= [:shared :other :shared] @seen) "all three registrations run")
      (reset! seen [])
      (dereg-shared-1)
      (extensions/apply-input-hooks "hi" :interactive)
      (t/is (= [:other :shared] @seen)
            "one registration is gone; its neighbours and the equal fn are not")
      (finally (dereg-shared-1) (dereg-shared-2) (dereg-other)))))

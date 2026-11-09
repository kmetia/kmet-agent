(ns kmet.app.test-resources
  "The `:resources` selection service: defaults, the two scopes, the
   available/selected split, and the writers that keep each scope's shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.config :as cfg]
            [kmet.app.config-schema :as config-schema]
            [kmet.app.packages :as packages]
            [kmet.app.registries :as registries]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as resources]
            [kmet.srce.core :as srce]))

(defn- handle
  "A registry handle with SPECS — `[name owner]` pairs — registered under
   TAG."
  [tag specs]
  (let [r (registry/atom-registry)]
    (doseq [[name owner] specs]
      (registry/register! r tag name {:name name} owner))
    r))

(defn- service
  "The resources service over tool/skill/prompt `[name owner]` specs."
  ([tools] (service tools [] []))
  ([tools skills prompts]
   (resources/service
    {:tools (handle :kmet/tool tools)
     :skills (handle :kmet/skill skills)
     :prompts (handle :kmet/prompt prompts)})))

(defn- dissoc-in
  "Remove PATH's leaf from M, pruning maps left empty by the removal."
  [m [k & ks]]
  (if ks
    (let [child (get m k)]
      (if (map? child)
        (let [child' (dissoc-in child ks)]
          (if (empty? child') (dissoc m k) (assoc m k child')))
        m))
    (dissoc m k)))

(defn- with-settings
  "Run F with the two settings scopes replaced by atoms, so a writer test
   reads exactly what it saved."
  [global project f]
  (let [g (atom global)
        p (atom project)]
    (with-redefs [cfg/read-global-settings-map (fn [] @g)
                  cfg/read-project-settings-map (fn [] @p)
                  cfg/save-setting! (fn [path value]
                                      (swap! g assoc-in path value)
                                      value)
                  cfg/save-project-setting! (fn [path value]
                                              (swap! p assoc-in path value)
                                              value)
                  cfg/remove-setting! (fn [path]
                                        (swap! g dissoc-in path)
                                        nil)
                  cfg/remove-project-setting! (fn [path]
                                                (swap! p dissoc-in path)
                                                nil)]
      (f g p))))

(defn- enabled [s kind]
  ((:enabled-names s) kind))

(defn- available? [s kind name]
  ((:available? s) kind name))

(defn- selected? [s kind name]
  ((:selected? s) kind name))

(defn- enabled? [s kind name]
  ((:enabled? s) kind name))

(defn- row
  "The catalog row named NAME, or nil."
  [rows name]
  (first (filter #(= name (:name %)) rows)))

;; ─── Defaults ──────────────────────────────────────────────────────────────

(deftest defaults
  (with-settings nil nil
    (fn [_ _]
      (let [s (service [["read" :tool/read]
                        ["codemode" :tool/codemode]
                        ["eval" :tool/eval]
                        ["ext_tool" [:app :extensions "demo"]]]
                       [["a" :app] ["b" :app]]
                       [["p" :app]])
            rows ((:catalog s) :tool)]
        (is (= #{"read" "ext_tool"} (enabled s :tool))
            "opt-in built-ins are off, every other tool is on")
        (is (= #{"a" "b"} (enabled s :skill)))
        (is (= #{"p"} (enabled s :prompt)))
        (is (available? s :tool "codemode"))
        (is (not (selected? s :tool "codemode"))
            "a default-off tool is not selected by default")
        (is (not (enabled? s :tool "codemode")))
        (is (selected? s :tool "ext_tool")
            "an extension tool defaults on")
        (is (= {:kind :extension :id :ext/demo}
               (:provider (row rows "ext_tool")))
            "a row names the extension that provides it")
        (is (= :resource (:kind (first rows))))
        (is (= {:kind :builtin :id :tool/read}
               (:provider (row rows "read")))
            "a built-in tool row names its composition row")
        (is (:global? (row rows "read")))
        (is (:selected? (row rows "read")))
        (is (:enabled? (row rows "read")))))))

;; ─── Global lists ──────────────────────────────────────────────────────────

(deftest global-lists
  (let [s (service [] [["a" :app] ["b" :app] ["c" :app]] [])]
    (with-settings nil nil
      (fn [_ _]
        (is (= #{"a" "b" "c"} (enabled s :skill))
            "a missing list inherits the defaults")))
    (with-settings {:resources {:skills []}} nil
      (fn [_ _]
        (is (= #{} (enabled s :skill))
            "an empty list selects nothing")))
    (with-settings {:resources {:skills ["-a" "+b"]}} nil
      (fn [_ _]
        (is (= #{"b" "c"} (enabled s :skill))
            "a modifier-only list starts from defaults")))
    (with-settings {:resources {:skills ["a"]}} nil
      (fn [_ _]
        (is (= #{"a"} (enabled s :skill))
            "a plain list is absolute")))
    (with-settings {:resources {:skills ["a" "-a" "+b"]}} nil
      (fn [_ _]
        (is (= #{"b"} (enabled s :skill))
            "plain names form the base, then modifiers apply in order")))
    (with-settings {:resources {:skills "a"}} nil
      (fn [_ _]
        (is (= #{} (enabled s :skill))
            "a malformed list selects nothing")))))

;; ─── Project deltas ────────────────────────────────────────────────────────

(deftest project-deltas
  (let [s (service [] [["a" :app] ["b" :app] ["c" :app]] [])]
    (with-settings {:resources {:skills ["-a"]}} nil
      (fn [_ _]
        (is (= #{"b" "c"} (enabled s :skill)))
        (is (= :inherit ((:project-state s) :skill "a")))))
    (with-settings {:resources {:skills ["-a"]}}
      {:resources {:skills ["+a"]}}
      (fn [_ _]
        (is (= #{"a" "b" "c"} (enabled s :skill)))
        (is (= :enabled ((:project-state s) :skill "a")))))
    (with-settings {:resources {:skills ["-a"]}}
      {:resources {:skills ["-b"]}}
      (fn [_ _]
        (is (= #{"c"} (enabled s :skill)))
        (is (= :disabled ((:project-state s) :skill "b")))))
    (with-settings nil
      {:resources {:skills ["+a" "-a"]}}
      (fn [_ _]
        (is (not (enabled? s :skill "a"))
            "the last project entry decides")
        (is (= :disabled ((:project-state s) :skill "a")))))
    (with-settings {:resources {:skills ["-a"]}}
      {:resources {:skills []}}
      (fn [_ _]
        (is (= #{"b" "c"} (enabled s :skill))
            "an empty project list inherits global")))
    (with-settings {:resources {:skills ["-a"]}}
      {:resources {:skills ["a"]}}
      (fn [_ _]
        (is (= #{"b" "c"} (enabled s :skill))
            "a project plain name invalidates the whole type list")))))

(deftest project-applies-over-global-not-defaults
  (let [s (service [] [["a" :app] ["b" :app] ["c" :app]] [])]
    (with-settings {:resources {:skills ["a"]}}
      {:resources {:skills ["+b"]}}
      (fn [_ _]
        (is (= #{"a" "b"} (enabled s :skill))
            "the project delta modifies global, and the plain global list still
             excludes c"))))
  (let [s (service [] [["a" :app] ["b" :app]] [])]
    (with-settings {:resources {:skills []}}
      {:resources {:skills ["+a"]}}
      (fn [_ _]
        (is (= #{"a"} (enabled s :skill))
            "a project delta applies over an empty global selection")))))

;; ─── Availability ──────────────────────────────────────────────────────────

(deftest selection-survives-unavailability
  (with-settings {:resources {:tools ["read" "ghost"]}} nil
    (fn [_ _]
      (let [before (service [["read" :tool/read]])
            after (service [["read" :tool/read] ["ghost" [:app :extensions "demo"]]])]
        (is (selected? before :tool "ghost")
            "selected? ignores availability")
        (is (not (available? before :tool "ghost")))
        (is (not (enabled? before :tool "ghost")))
        (is (nil? (row ((:catalog before) :tool) "ghost"))
            "an unavailable resource has no row")
        (is (enabled? after :tool "ghost")
            "the stored entry applies once the provider mounts")
        (is (some? (row ((:catalog after) :tool) "ghost")))
        (is (= #{"read" "ghost"} (enabled after :tool)))))))

(deftest unavailable-rows-follow-the-scope
  (let [s (service [["read" :tool/read]])]
    (with-settings {:resources {:tools ["ghost" "-read" "codemode"]}}
      {:resources {:tools ["+project-ghost"]}}
      (fn [_ _]
        (let [global-rows ((:unavailable s) :tool :global)
              project-rows ((:unavailable s) :tool :project)]
          (is (= ["codemode" "ghost"] (mapv :name global-rows))
              "stored global names no provider offers are reported")
          (is (= ["project-ghost"] (mapv :name project-rows))
              "project rows come from the project delta only")
          (is (every? #(false? (:available? %))
                      (concat global-rows project-rows)))
          (is (every? #(= {:kind :unavailable :id nil} (:provider %))
                      (concat global-rows project-rows)))
          (is (every? #(false? (:enabled? %))
                      (concat global-rows project-rows)))
          (is (= {:global? true :project-state :inherit :selected? true}
                 (select-keys (row global-rows "ghost")
                              [:global? :project-state :selected?])))
          (is (= {:global? false :project-state :enabled :selected? true}
                 (select-keys (row project-rows "project-ghost")
                              [:global? :project-state :selected?]))))))
    (with-settings {:resources {:tools ["ghost"]}} nil
      (fn [_ _]
        (is (empty? ((:unavailable s) :tool :project))
            "with no project delta there are no project rows")))))

(deftest an-invalid-project-list-has-no-unavailable-rows
  (let [s (service [])]
    (with-settings nil {:resources {:tools ["ghost"]}}
      (fn [_ _]
        (is (empty? ((:unavailable s) :tool :project))
            "a plain project entry invalidates the whole list")))))

(deftest unavailable-reader-checks-its-arguments
  (let [s (service [])]
    (is (thrown? clojure.lang.ExceptionInfo ((:unavailable s) :bogus :global)))
    (is (thrown? clojure.lang.ExceptionInfo ((:unavailable s) :tool :bogus)))))

;; ─── Writers ───────────────────────────────────────────────────────────────

(deftest global-writer-keeps-modifier-shape
  (with-settings nil nil
    (fn [global _]
      (let [s (service [])]
        (is (= ["-read"] ((:set-global! s) :tool "read" false)))
        (is (= {:resources {:tools ["-read"]}} @global))
        (is (nil? ((:set-global! s) :tool "read" true))
            "enabling a default-on tool removes its entry")
        (is (= {} @global)
            "and the emptied :resources map is removed")
        (is (nil? ((:set-global! s) :tool "codemode" false))
            "disabling a default-off tool removes nothing")
        (is (= ["+codemode"] ((:set-global! s) :tool "codemode" true)))
        (is (= {:resources {:tools ["+codemode"]}} @global)))))
  (with-settings {:resources {:tools ["-ext"]}} nil
    (fn [_ _]
      (let [s (service [])]
        (is (nil? ((:set-global! s) :tool "ext" true))
            "enabling a default-on extension tool clears its -name")))))

(deftest global-writer-rewrites-plain-selection
  (with-settings {:resources {:tools ["read" "codemode"]}} nil
    (fn [global _]
      (let [s (service [])]
        (is (= ["codemode"] ((:set-global! s) :tool "read" false)))
        (is (= ["codemode"] (:tools (:resources @global))))
        (is (= [] ((:set-global! s) :tool "codemode" false))
            "an emptied plain selection is stored as []")
        (is (= [] (:tools (:resources @global))))
        (is (= ["new"] ((:set-global! s) :tool "new" true)))
        (is (= ["new"] (:tools (:resources @global))))))))

(deftest forgetting-a-global-entry-preserves-the-list-shape
  (with-settings {:resources {:tools ["read" "ghost"]}} nil
    (fn [global _]
      (let [s (service [])]
        (is (= ["read"] ((:set-global! s) :tool "ghost" :inherit)))
        (is (= ["read"] (get-in @global [:resources :tools]))))))
  (with-settings {:resources {:tools ["-ghost" "+read"]}} nil
    (fn [global _]
      (let [s (service [])]
        (is (= ["+read"] ((:set-global! s) :tool "ghost" :inherit)))
        (is (= ["+read"] (get-in @global [:resources :tools]))))))
  (with-settings {:resources {:tools ["ghost"]}} nil
    (fn [global _]
      (let [s (service [])]
        (is (nil? ((:set-global! s) :tool "ghost" :inherit)))
        (is (= {} @global)
            "an emptied list clears the key and restores defaults"))))
  (with-settings {:resources {:tools []}} nil
    (fn [global _]
      (let [s (service [])]
        (is (= [] ((:set-global! s) :tool "ghost" :inherit)))
        (is (= [] (get-in @global [:resources :tools]))
            "an empty list is left alone, not reset to defaults"))))
  (with-settings {:resources {:tools ["-read"]}} nil
    (fn [_ _]
      (let [s (service [["read" :tool/read]])]
        (is (nil? ((:set-global! s) :tool "read" :inherit)))
        (is (selected? s :tool "read")
            "forgetting a disable restores the default selection"))))
  (with-settings nil nil
    (fn [_ _]
      (let [s (service [])]
        (is (thrown? clojure.lang.ExceptionInfo
                     ((:set-global! s) :tool "read" :bogus)))))))

(deftest project-writer-drops-plain-entries
  (with-settings nil {:resources {:skills ["plain" "-read"]}}
    (fn [_ project]
      (let [s (service [])]
        (is (= ["-read" "+bash"]
               ((:set-project! s) :skill "bash" :enabled)))
        (is (= ["-read" "+bash"]
               (get-in @project [:resources :skills])))
        (is (= ["-read"]
               ((:set-project! s) :skill "bash" :inherit)))
        (is (= ["-read"]
               ((:set-project! s) :skill "read" :disabled))))))
  (with-settings nil nil
    (fn [_ project]
      (let [s (service [])]
        (is (= ["+a"] ((:set-project! s) :prompt "a" :enabled)))
        (is (nil? ((:set-project! s) :prompt "a" :inherit))
            "an emptied project delta clears the key")
        (is (= {} @project))
        (is (thrown? clojure.lang.ExceptionInfo
                     ((:set-project! s) :prompt "a" :bogus)))))))

(deftest provider-follows-the-effective-tool-value
  (with-settings nil nil
    (fn [_ _]
      (let [tools (registry/atom-registry)]
        (registry/register! tools :kmet/tool "read"
                            {:name "read" :from :builtin} :tool/read)
        (registry/register! tools :kmet/tool "read"
                            {:name "read" :from :extension}
                            [:app :extensions "demo"])
        (let [s (resources/service
                 {:tools tools
                  :skills (handle :kmet/skill [])
                  :prompts (handle :kmet/prompt [])})
              row (first ((:catalog s) :tool))]
          (is (= {:kind :extension :id :ext/demo} (:provider row))
              "a shadowing tool's row names the extension, not the shadowed row"))))))

(deftest provider-follows-a-deferred-tool-source
  (with-settings nil nil
    (fn [_ _]
      (let [tools (registry/atom-registry)
            source-owner [:app :extensions "tree-sitter"]
            direct-owner [:app :extensions "direct"]]
        (registry/register! tools :kmet/tool "shared"
                            {:name "shared" :description "registered"}
                            direct-owner)
        (registry/register! tools :kmet/tool-source ::symbols
                            {:id ::symbols
                             :exposure :deferred
                             :description "symbols"
                             :tools-fn (fn []
                                         {"shared" {:name "shared"
                                                    :description "source"}
                                          "get_symbol_body"
                                          {:name "get_symbol_body"
                                           :description "body"}})}
                            source-owner)
        (let [s (resources/service
                 {:tools tools
                  :skills (handle :kmet/skill [])
                  :prompts (handle :kmet/prompt [])})
              rows ((:catalog s) :tool)]
          (is (= {:kind :extension :id :ext/tree-sitter}
                 (:provider (row rows "get_symbol_body")))
              "a deferred-source tool names the extension that owns the source")
          (is (= {:kind :extension :id :ext/direct}
                 (:provider (row rows "shared")))
              "a registered tool shadows the source contribution"))))))

;; ─── Settings schema ───────────────────────────────────────────────────────

(deftest settings-schema
  (is (config-schema/validate-config?
       {:resources {:tools ["-bash"] :skills ["+review"] :prompts []}}))
  (is (config-schema/validate-config?
       {:resources {:tools nil}})
      "a cleared type key reads as absent")
  (is (not (config-schema/validate-config?
            {:resources {:tools "bash"}})))
  (is (not (config-schema/validate-config?
            {:resources {:skills [1]}})))
  (is (config-schema/validate-resource-settings? nil))
  (is (config-schema/validate-resource-settings? {:tools ["-bash"]}))
  (is (not (config-schema/validate-resource-settings? {:tools "bash"}))))

(deftest scopes-are-read-unmerged
  (with-settings {:resources {:tools ["read"]}}
    {:resources {:skills ["+review"]}}
    (fn [_ _]
      (is (= {:global {:tools ["read"]}
              :project {:skills ["+review"]}}
             (cfg/read-resource-settings))
          "each scope keeps its own map; the service merges per type"))))

;; ─── Read-time filters ─────────────────────────────────────────────────────

(deftest the-read-time-filter
  (let [items [{:name "a"} {:name "b"}]]
    (is (= items (resources/enabled nil :skill items))
        "a missing service keeps every item")
    (let [s (service [] [["a" :app]] [])]
      (with-settings nil nil
        (fn [_ _]
          (is (= [{:name "a"}] (resources/enabled s :skill items))
              "only the enabled names survive")
          (is (= [] (resources/enabled s :tool items))
              "an empty selection keeps none"))))))

(deftest input-expansion-follows-the-selection
  (let [handle (registry/atom-registry)
        previous (registry/publish-process-handle! handle)
        skill {:name "demo"
               :body "SKILL BODY"
               :location "ext:skills/demo/SKILL.md"
               :extension "ext"}
        template {:name "deploy" :content "DEPLOY BODY"}]
    (try
      (registry/register! handle :kmet/skill "demo" skill :test)
      (registry/register! handle :kmet/prompt "deploy" template :test)
      (let [s (resources/service {:tools (registry/atom-registry)
                                  :skills handle
                                  :prompts handle})]
        (with-settings nil nil
          (fn [_ _]
            (is (str/includes? (packages/expand-input "/skill:demo" s) "SKILL BODY")
                "an enabled skill expands")
            (is (= "DEPLOY BODY" (packages/expand-input "/deploy" s))
                "an enabled template expands")))
        (with-settings {:resources {:skills [] :prompts []}} nil
          (fn [_ _]
            (is (= "/skill:demo" (packages/expand-input "/skill:demo" s))
                "a disabled skill passes through")
            (is (= "/deploy" (packages/expand-input "/deploy" s))
                "a disabled template passes through")))
        (is (str/includes? (packages/expand-input "/skill:demo") "SKILL BODY")
            "no resources service keeps the process registrations"))
      (finally
        (registry/publish-process-handle! previous)))))

;; ─── The row ───────────────────────────────────────────────────────────────

(deftest the-row-provides-the-service
  (let [ctx (srce/root)]
    (doseq [row [registries/tools registries/skills registries/prompts]]
      (srce/plugin! ctx row))
    (srce/settle! ctx)
    (let [fiber (srce/plugin! ctx resources/plugin)]
      (srce/settle! ctx)
      (is (fn? (:enabled-names (srce/get ctx :resources))))
      (srce/dispose! fiber)
      (is (nil? (srce/get ctx :resources))
          "the provide is the row's effect"))))

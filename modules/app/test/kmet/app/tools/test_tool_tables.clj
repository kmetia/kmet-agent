(ns kmet.app.tools.test-tool-tables
  "A tool table belongs to a registry, not to the process.

   The registry takes its handle as the first argument, and every tool — the
   built-ins included — reaches the table as a registration: a tool row
   (`:tool/read`, …) injects `:tools` and mounts its record as its own effect
   (`kmet.app.tools.registry/mount-tool!`). Two agents therefore hold two tables
   with nothing shared, and a row's disposal takes its tool with it. That is the
   property landing 4c-iii-c's gate needs; it is cheap to pin now because the
   alternative — a static table under the registrations — is exactly what a
   process default would be.

   The second property is the row's `:config`: a composition says what the
   model *reads* (label, description, prompt text) without touching what the
   tool does, the dsh shape where a deployment overrides a tool package's
   model-facing text. The settings layer's built-in name list is read back off
   the same rows (`bundle-tool-names`), so neither the tool set nor a tool's
   presentation has a second place to edit."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.profile :as profile]
            [kmet.app.registry :as registry]
            [kmet.app.runtime :as runtime]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.tool :as tool]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

(defn- probe
  "A minimal tool named PROBE whose result says where it came from."
  [from]
  {:name "probe" :label "Probe" :description "d"
   :execute (fn [_] {:content from})})

(defn- tool-rows
  "The composition's tool rows, as `:bundle/minimal` declares them."
  []
  (->> (:rows (profile/bundle (profile/builtin) :bundle/minimal))
       (filter #(str/starts-with? (str (:id %)) ":tool/"))))

(t/deftest a-registration-belongs-to-its-own-table
  (let [a (registry/atom-registry)
        b (registry/atom-registry)]
    (tools/register-tool! a (probe "from-a"))
    (t/is (contains? (tools/get-all-tools a) "probe")
          "the handle the tool was registered in sees it")
    (t/is (not (contains? (tools/get-all-tools b) "probe"))
          "and no other handle does")))

(t/deftest deferred-tools-name-their-source-owner
  (let [r (registry/atom-registry)
        a [:app :extensions "a"]
        b [:app :extensions "b"]]
    (tools/register-tool-source! r :one (fn [] {"t1" {:name "t1"}})
                                 {:exposure :deferred :owner a})
    (tools/register-tool-source! r :two (fn [] {"t1" {:name "t1"}
                                                "t2" {:name "t2"}})
                                 {:exposure :deferred :owner b})
    (tools/register-tool! r {:name "t2" :description "registered"}
                          [:app :extensions "direct"])
    (t/is (= {"t1" b} (tools/deferred-tool-owners r))
          "the later source wins a colliding name and a registration shadows both")))

(t/deftest dispatch-follows-the-handle
  (let [a (registry/atom-registry)
        b (registry/atom-registry)]
    (tools/register-tool! a (probe "from-a"))
    (tools/register-tool! b (probe "from-b"))
    (t/is (= "from-a" (:content (tools/execute-tool a "probe" {}))))
    (t/is (= "from-b" (:content (tools/execute-tool b "probe" {})))
          "the same name resolves to each handle's own tool")))

(t/deftest a-tool-row-contributes-its-name
  (let [bundle {:rows [{:id :tool/read}
                       {:id :events}            ; not a tool row
                       {:id :tool/ls}
                       {:id :foo :plugin 'some/var}]}]
    (t/is (= ["read" "ls"] (tools/bundle-tool-names bundle))
          "every :tool/<name> row contributes its name, in composition order,
           and a non-tool row contributes nothing")
    (t/is (= [] (tools/bundle-tool-names {}))
          "a bundle with no rows names no tools")))

(t/deftest the-name-list-is-the-composition
  (t/is (= (mapv tools/port-name tools/builtin-tool-names)
           (mapv :id (tool-rows)))
        "each row is named :tool/<model name>, and the settings layer's name
         list is the composition's tool rows — derived, so a row added to
         :bundle/minimal appears in it without a second list to edit")
  (t/is (= (count tools/builtin-tool-names) (count (distinct tools/builtin-tool-names)))
        "no tool is named twice"))

(t/deftest a-row-registers-the-tool-the-model-sees
  (let [{:keys [tools]} (tu/tool-ctx)]
    (t/is (= (set tools/builtin-tool-names)
             (set (keys (tools/get-all-tools tools))))
          "mounting the rows is what puts the built-ins in the table")
    (t/is (= "read" (:name (tools/get-tool tools "read")))
          "and the record the model calls is the row's own")))

(t/deftest a-tool-leaves-with-its-row
  (let [{:keys [ctx tools]} (tu/tool-ctx)
        row (fn [name]
              (srce/mount-fiber
               (first (filter #(= name (srce/attr % :name)) (srce/children ctx)))))]
    (t/is (contains? (tools/get-all-tools tools) "bash"))
    (srce/dispose! (row :tool/bash))
    (t/is (not (contains? (tools/get-all-tools tools) "bash"))
          "the registration was the row's effect, so disposing the row removed it")
    (t/is (contains? (tools/get-all-tools tools) "read")
          "and only its own tool went")))

;; ─── Row config ───────────────────────────────────────────────────────────

(defn- tool-record
  "The composition's record for the tool NAME, mounted without a config."
  [name]
  (tools/get-tool (:tools (tu/tool-ctx)) name))

(defn- read-row
  "The `:bundle/minimal` row for the tool NAME, as composition data."
  [name]
  (first (filter #(= (tools/port-name name) (:id %)) (tool-rows))))

(t/deftest a-row-config-overrides-the-presentation
  ;; The row's :config is the composition's say over what the model reads; the
  ;; tool's behavior (its execute fn, its parameters) stays the plugin's. This
  ;; is the dsh shape — a deployment overrides the model-facing description
  ;; without replacing the tool package.
  (let [ctx (srce/root {:name :test-config-tools})
        handle (tu/tools-service! ctx)]
    (runtime/mount-rows! ctx [(assoc (read-row "read")
                                     :config {:label "Open file"
                                              :description "Read a file from here."
                                              :prompt-snippet "Open files"
                                              :prompt-guidelines ["Read before write."]})])
    (srce/settle! ctx)
    (let [read-tool (tools/get-tool handle "read")]
      (t/is (= "Open file" (:label read-tool)))
      (t/is (= "Read a file from here." (:description read-tool)))
      (t/is (= "Open files" (:prompt-snippet read-tool)))
      (t/is (= ["Read before write."] (:prompt-guidelines read-tool)))
      (t/is (= "read" (:name read-tool))
            "the name is the row id's — :tool/read is the model's spelling, so a
             config cannot rename the tool out from under its row")
      (t/is (= ["path" "offset" "limit"]
               (vec (keys (get-in read-tool [:parameters :properties]))))
            "and the parameters the plugin declared are untouched"))))

(t/deftest a-nil-row-config-mounts-the-tool-as-written
  (t/is (= (tool-record "read") (tool/configure (tool-record "read") nil))
        "a row without a :config changes nothing — the common case")
  (t/is (= (tool-record "read") (tool/configure (tool-record "read") {}))))

(t/deftest a-bad-row-config-is-an-error-not-a-silent-default
  ;; A config nobody reads is a composition bug, and silently leaving the
  ;; default in place reads exactly like an applied override.
  (t/is (thrown-with-msg? Exception #":config may set"
                          (tool/configure (tool-record "bash") {:policy :allow-all}))
        "an unknown key is refused")
  (t/is (thrown-with-msg? Exception #":config may set"
                          (tool/configure (tool-record "bash") {:name "shell"}))
        ":name is not a config key — the row id is derived from the tool's name")
  (t/is (thrown-with-msg? Exception #"must be a vector of strings"
                          (tool/configure (tool-record "bash")
                                          {:prompt-guidelines "one long string"}))
        "a wrongly shaped value is refused")
  (t/is (thrown-with-msg? Exception #"must be a map"
                          (tool/configure (tool-record "bash") [:description "x"]))
        "a non-map config is refused"))

(t/deftest the-codemode-record-dispatches-through-its-own-handle
  ;; The `:tool/codemode` row builds its record for the handle it was mounted
  ;; over, so the record's sandbox surface (`select-tools` over its own
  ;; registry) is that handle's: a tool registered in A is callable from A's
  ;; record and unknown to B's, and that surface is what the bridge gates on.
  ;; This is the per-agent tool table 4c-iii-c names, one seam down — and what
  ;; the shape assertions above cannot see.
  (let [a (:tools (tu/tool-ctx))
        b (:tools (tu/tool-ctx))
        run (fn [h] (:execute (get (tools/get-all-tools h) "codemode")))
        code "(core/emit (:content @(tools/call \"probe\" {})))"]
    (tools/register-tool! a (probe "from-a"))
    (let [from-a (:content ((run a) {:code code} nil nil nil))
          from-b (:content ((run b) {:code code} nil nil nil))]
      (t/is (= "from-a" from-a)
            "A's record reaches the tool registered in A")
      (t/is (str/includes? from-b "Tool not active in this codemode call: probe")
            "B's record does not — its surface came from its own handle"))))

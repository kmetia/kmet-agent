(ns kmet.app.test-tool-exposure
  "Tool exposure: where a contributed tool source's catalog lands.

   `:sandbox` (the default) adds a source's tools to the `run_code` sandbox's
   surface only — the model never sees them; `:deferred` adds them to the
   model-facing table (undeclared, tagged `:exposure :deferred` by the source),
   where the selection settings and the declared/callable split resolve over
   them."
  (:require [clojure.test :as t]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.tool :as tool]
            [kmet.test-utils :as tu]))

(defn- handle []
  (registry/atom-registry))

(defn- tool-map
  "A minimal contributed tool named NAME."
  [name]
  {:name name :description name :execute (fn [_] {:content name})})

(defn- with-sources
  "Register SPECS — [id tools-fn opts] — on H, run F, then take them away."
  [h specs f]
  (doseq [[id tools-fn opts] specs]
    (tools/register-tool-source! h id tools-fn opts))
  (try (f)
       (finally (doseq [[id] specs]
                  (tools/unregister-tool-source! h id)))))

(t/deftest test-sandbox-source-lands-in-the-sandbox-surface-only
  ;; the default exposure is today's behaviour: callable from a script, never
  ;; in the model's table
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"sandbox-a" (tool-map "sandbox-a")}) {}]]
      (fn []
        (t/is (contains? (tools/get-contributed-tools h) "sandbox-a")
              "a :sandbox source's tools are in the sandbox surface")
        (t/is (not (contains? (tools/get-all-tools h) "sandbox-a"))
              "nothing selects or declares a sandbox tool")))))

(t/deftest test-deferred-source-joins-the-table-undeclared
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"deferred-a" (tool-map "deferred-a")})
                      {:exposure :deferred :description "A deferred catalog"}]]
      (fn []
        (let [tool (get (tools/get-all-tools h) "deferred-a")]
          (t/is tool "a :deferred source's tools are in the model-facing table")
          (t/is (= :deferred (:exposure tool)))
          (t/is (not (contains? (tools/get-contributed-tools h) "deferred-a"))
                "a deferred tool is not also a sandbox contribution"))))))

(t/deftest test-deferred-tool-keeps-the-map-key-as-its-name
  ;; a source may omit :name; the key is the address a selection and a
  ;; promotion use, so the record must carry it too
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"deferred-a" {:description "no name"}})
                      {:exposure :deferred}]]
      (fn []
        (t/is (= "deferred-a" (:name (get (tools/get-all-tools h) "deferred-a"))))))))

(t/deftest test-source-exposure-is-validated
  (let [h (handle)]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unknown tool-source exposure"
           (tools/register-tool-source! h ::catalog (fn [] {}) {:exposure :direct})))
    (t/is (empty? (tools/get-all-tools h))
          "a refused registration installs nothing")))

(t/deftest test-deferred-sources-are-reported-with-their-description
  ;; the listing names a source and one line — never its tools
  (let [h (handle)
        specs [[::one (fn [] {"a" (tool-map "a")})
                {:exposure :deferred :description "First catalog"}]
               [::two (fn [] {"b" (tool-map "b")})
                {:exposure :deferred :description "Second catalog"}]
               [::sandbox (fn [] {"c" (tool-map "c")}) {}]]]
    (with-sources h specs
      (fn []
        (t/is (= [{:id ::one :description "First catalog"}
                  {:id ::two :description "Second catalog"}]
                 (tools/get-deferred-sources h)))))))

(t/deftest test-source-registration-replaces
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"old" (tool-map "old")}) {:exposure :deferred}]]
      (fn []
        (tools/register-tool-source! h ::catalog (fn [] {"new" (tool-map "new")})
                                     {:exposure :deferred})
        (t/is (= #{"new"} (set (keys (tools/get-all-tools h)))))
        (t/is (empty? (tools/get-contributed-tools h)))))))

(t/deftest test-registered-tool-shadows-a-contributed-name
  (let [h (handle)]
    (with-sources h [[::catalog (fn [] {"shared" (tool-map "shadowed")}) {:exposure :deferred}]]
      (fn []
        (tools/register-tool! h {:name "shared" :description "registered"
                                 :execute (fn [_] {:content "registered"})})
        (try
          (t/is (= "registered" (:description (tools/get-tool h "shared"))))
          (finally (tools/unregister-tool! h "shared")))))))

(t/deftest test-broken-source-contributes-nothing
  (let [h (handle)]
    (with-sources h [[::broken (fn [] (throw (ex-info "boom" {}))) {:exposure :deferred}]
                     [::bad-shape (fn [] [1 2 3]) {:exposure :deferred}]]
      (fn []
        (t/is (empty? (tools/get-all-tools h)))
        (t/is (= [{:id ::broken :description nil} {:id ::bad-shape :description nil}]
                 (tools/get-deferred-sources h))
              "a broken source is still a source the listing names")))))

(t/deftest test-built-in-tools-declare-no-exposure
  ;; the request must not change for a tool written before exposures existed:
  ;; no exposure is declared, and the reader supplies :direct
  (let [h (tu/tool-registry)]
    (doseq [[name tool] (tools/get-all-tools h)]
      (t/is (nil? (:exposure tool)) name)
      (t/is (= :direct (tool/exposure tool)) name))))

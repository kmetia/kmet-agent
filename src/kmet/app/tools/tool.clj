(ns kmet.app.tools.tool
  "Tool record type and parameter schema helpers.")

(defrecord Tool [name label description prompt-snippet prompt-guidelines
                 parameters execute render-call render-result
                 constrained-sampling render-shell prepare-arguments
                 execution-mode streams? contextual? title])

(defn param
  "Define a tool parameter for JSON schema generation."
  [_name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn- compact->params
  "Convert a compact params map {k {:type t :description d :optional? bool}}
   to the param map expected by ->json-schema (values carry the :optional key)."
  [params]
  (into {} (map (fn [[k {:keys [type description optional?]}]]
                  [k (param k type description
                            (when optional? {:optional? true}))])
                params)))

(defn ->json-schema
  "Convert a map of param definitions to a JSON schema map."
  [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                                   {:type (name (:type v))
                                    :description (:description v)}))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})

(defn normalize-tool-definition
  "Normalize a registered tool definition: convert compact :params into a
   JSON-schema :parameters map and give argument-less tools an empty object
   schema, which provider tool APIs require instead of null."
  [definition]
  (let [parameters (if-let [params (:params definition)]
                     (->json-schema (compact->params params))
                     (or (:parameters definition)
                         {:type "object" :properties {} :required []}))]
    (assoc definition :parameters parameters)))

(defn make-tool
  "Create a Tool record.
   See tool/Tool for all fields. :execution-mode defaults to nil (= :parallel).
   :parameters may be a pre-built JSON schema map (passed through as-is);
   :params is a compact alternative — a map of param keyword →
   {:type :string|:number|:boolean :description str :optional? bool} —
   converted to a JSON schema automatically."
  [& {:keys [name label description prompt-snippet prompt-guidelines
             params parameters execute render-call render-result
             constrained-sampling render-shell prepare-arguments
             execution-mode streams? contextual? title]}]
  (map->Tool
   {:name name :label label :description description
    :prompt-snippet prompt-snippet :prompt-guidelines prompt-guidelines
    :parameters (if params (->json-schema (compact->params params)) parameters)
    :execute execute
    :render-call render-call :render-result render-result
    :constrained-sampling constrained-sampling
    :render-shell render-shell
    :prepare-arguments prepare-arguments
    :execution-mode execution-mode
    :streams? streams?
    :contextual? contextual?
    :title title}))


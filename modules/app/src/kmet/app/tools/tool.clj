(ns kmet.app.tools.tool
  "Tool record type and parameter schema helpers.")

(defrecord Tool [name label description prompt-snippet prompt-guidelines
                 parameters execute render-call render-result
                 constrained-sampling render-shell prepare-arguments
                 execution-mode serial-group streams? contextual? title status exposure])

(def exposure-values
  "The tool record's `:exposure` — how the model reaches the tool:

   - `:direct` (the default) — declared to the model and callable from a
     script;
   - `:deferred` — callable from a script, not declared until `tool_search`
     loads it;
   - `:model-only` — declared, never callable from a script (pi's
     `model-only`: `tool_search` is one, because a script that could call it
     would change the declared set mid-script);
   - `:sandbox` — callable from a script, not declared (pi's `codemode`; the
     exposure a contributed tool source gets).

   pi's `hidden` stays out: kmet can unregister a tool and exclude it with
   the `:resources` selection, and pi needs it only because it cannot
   unregister."
  #{:direct :deferred :model-only :sandbox})

(defn exposure
  "TOOL's exposure, `:direct` when the record names none — the default, so
   every tool and extension written before exposures existed is unchanged."
  [tool]
  (or (:exposure tool) :direct))

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
   schema, which provider tool APIs require instead of null. An `:exposure`
   outside `exposure-values` throws — a typo would otherwise read as
   `:direct`, declaring a tool the author meant to defer."
  [definition]
  (when-let [exposure (:exposure definition)]
    (when-not (contains? exposure-values exposure)
      (throw (ex-info (str "unknown tool exposure " (pr-str exposure)
                           " for " (pr-str (:name definition))
                           " — one of " (pr-str (vec (sort exposure-values))))
                      {:type :kmet.app/bad-tool-exposure
                       :tool (:name definition)
                       :exposure exposure}))))
  (let [parameters (if-let [params (:params definition)]
                     (->json-schema (compact->params params))
                     (or (:parameters definition)
                         {:type "object" :properties {} :required []}))]
    (assoc definition :parameters parameters)))

(defn make-tool
  "Create a Tool record.
   See tool/Tool for all fields. :execution-mode defaults to nil (= :parallel).
   :serial-group, when set, makes calls to tools sharing the group run in
   source order inside a parallel batch, while unrelated calls stay
   concurrent.
   :parameters may be a pre-built JSON schema map (passed through as-is);
   :params is a compact alternative — a map of param keyword →
   {:type :string|:number|:boolean :description str :optional? bool} —
   converted to a JSON schema automatically. :status requests the transcript's
   tail state line (pending/success/error + Elapsed/Took); nil (the default)
   renders no status line at all."
  [& {:keys [name label description prompt-snippet prompt-guidelines
             params parameters execute render-call render-result
             constrained-sampling render-shell prepare-arguments
             execution-mode serial-group streams? contextual? title status exposure]}]
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
    :serial-group serial-group
    :streams? streams?
    :contextual? contextual?
    :title title
    :status status
    :exposure exposure}))

;; ─── Row config ───────────────────────────────────────────────────────────

(def row-config-keys
  "The definition fields a tool row's `:config` may set — the record's
   model-facing presentation, which is what a composition can honestly change
   without becoming the tool.

   `:name` is deliberately absent. A row's id is the model's own spelling of
   the tool (`(port-name name)` → `:tool/read`), and `tool-row-name` reads it
   back for the composition's tool-name sets; a renamed
   tool would leave the row, the setting and the toggle naming three different
   tools. `:execute`, `:parameters` and the rest are the tool's behavior, which
   belongs to the plugin that wrote it."
  #{:label :description :prompt-snippet :prompt-guidelines})

(def ^:private row-config-checks
  "How each overridable field is checked before it replaces the default, and
   what the error says it should have been. A wrong type would reach the
   model's system prompt or tool schema, where nothing reports it — so it fails
   at the mount that read it instead."
  {:label [string? "a string"]
   :description [string? "a string"]
   :prompt-snippet [string? "a string"]
   :prompt-guidelines [#(and (sequential? %) (every? string? %))
                       "a vector of strings"]})

(defn- bad-config [msg data]
  (ex-info msg (assoc data :type :kmet.app/bad-tool-config)))

(defn configure
  "DEFINITION with a tool row's CONFIG applied — the presentation overrides
   the row names merged over the tool's own defaults, so a composition says
   what the model reads without replacing the tool.

   A nil or empty CONFIG returns DEFINITION untouched (the common case: most
   rows carry none). A key outside `row-config-keys`, or a value of the wrong
   shape, throws `:kmet.app/bad-tool-config` — a config nobody reads is a
   composition bug, and the alternative is worse than an error: a typo would
   leave the default in place and read exactly like an applied override."
  [definition config]
  (cond
    (nil? config) definition

    (not (map? config))
    (throw (bad-config (str "a tool row's :config must be a map, got "
                            (pr-str config))
                       {:config config}))

    (empty? config) definition

    :else
    (let [allowed (vec (sort row-config-keys))
          unknown (->> (keys config) (remove row-config-keys) sort vec)]
      (when (seq unknown)
        (throw (bad-config (str "a tool row's :config may set " (pr-str allowed)
                                ", not " (pr-str unknown)
                                " — " (pr-str (:name definition))
                                " owns its behavior, a row its presentation")
                           {:config config :unknown unknown :allowed allowed})))
      (doseq [[k v] config
              :let [[ok? shape] (row-config-checks k)]
              :when (not (ok? v))]
        (throw (bad-config (str "a tool row's :config " k " must be " shape
                                ", got " (pr-str v)
                                " for " (pr-str (:name definition)))
                           {:config config :key k :value v})))
      (merge definition config))))


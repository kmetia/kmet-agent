(ns kmet.app.eval.env
  "The eval environment's library stacks and name/scope rules: a name maps
   to `[global cwd]` records, the newest is active, and definitions move
   between the two roots. Shared by the service and the persistence and
   drop paths so they read one record model."
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(defn ->name
  "Normalize a string, symbol or keyword to a definition name; nil for
   anything else."
  [x]
  (cond
    (string? x) x
    (symbol? x) (str x)
    (keyword? x) (subs (str x) 1)
    :else nil))

(defn bad-request!
  "Throw the eval service's `:eval/bad-request` error."
  [message data]
  (throw (ex-info message (assoc data :type :eval/bad-request))))

(defn env-ns
  "The namespace the current call ends in; every new call starts again in
   user."
  [env]
  (or (some-> @(:call-ns env) sci/ns-name str) "user"))

(defn user-ns
  "The sandbox's user namespace."
  [env]
  (or (sci/find-ns (:sci-ctx env) 'user)
      (sci/create-ns 'user)))

(defn qualify-name
  "NAME in NS unless it already carries a namespace."
  [ns name]
  (if (str/includes? name "/") name (str ns "/" name)))

(defn scope-label
  "The display name of a record's scope."
  [scope]
  (name (or scope :active)))

(defn record-scope
  "RECORD's scope, defaulting to global like a loaded record."
  [record]
  (or (:scope record) :global))

(defn stack-for
  "The loaded records for NAME in precedence order, `[global cwd]`."
  [env name]
  (get @(:library env) name))

(defn active-record
  "The newest record for NAME, or nil."
  [env name]
  (peek (stack-for env name)))

(defn root-for
  "The library root for ENV and SCOPE, defaulting to global."
  [env scope]
  (get-in env [:roots (or scope :global)]))

(defn name-namespace
  "NAME's namespace as a string."
  [name]
  (namespace (symbol (str name))))

(defn extension-name?
  "Is NAME in an extension-owned namespace? Those definitions stay live-only."
  [name]
  (str/starts-with? (str (name-namespace name)) "ext."))

(defn missing-definition!
  "Throw the directed error for an unknown NAME in SCOPE."
  ([name scope] (missing-definition! name scope nil))
  ([name scope extra]
   (bad-request! (if scope
                   (str "no eval library definition named " name
                        " in the " (scope-label scope) " scope")
                   (str "no eval library definition named " name))
                 (merge {:name name :scope scope} extra))))

(defn record-failure!
  "Append FAILURE to the environment's load-failure list."
  [failures failure]
  (swap! failures conj failure))

(defn put-record!
  "Replace NAME's REMOVE-SCOPE record with RECORD, keeping `[global cwd]`
   order. One record per name and scope; the last load or write wins."
  [env name remove-scope record]
  (swap! (:library env) update name
         (fn [stack]
           (conj (vec (remove #(= remove-scope (record-scope %)) stack))
                 record))))

(defn add-record!
  "Index RECORD as the newest layer for its name and scope."
  [env record]
  (put-record! env (:name record) (record-scope record) record))

(defn remove-record!
  "Remove RECORD's scope from NAME's stack, dissociating the name when the
   stack is empty."
  [env name record]
  (let [scope (record-scope record)]
    (swap! (:library env)
           (fn [library]
             (let [stack (vec (remove #(= scope (record-scope %))
                                      (get library name)))]
               (if (seq stack)
                 (assoc library name stack)
                 (dissoc library name)))))))

(defn clear-unwritten!
  "Forget NAME's unwritten live-definition record."
  [env name]
  (swap! (:unwritten env) disj name)
  (swap! (:unwritten-records env) dissoc name))

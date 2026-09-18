(ns kmet.libs.json
  "JSON encode/decode boundary for kmet and its shipped extensions (pi:
   the data.json npm package + the JSON.stringify/parse global). All JSON
   touches funnel through this namespace so the JSON engine is swappable
   (clojure.data.json, the JSON library babashka bundles — and a plain
   Maven dep on Jolt, where deps resolve natively — so the swap point is
   this file alone).

   The vars below ARE clojure.data.json's own fns (def alias — no wrapper
   layer, no behavior drift): data.json's lazy parse-string, keyword
   option handling and encoding rules all apply unchanged. Call sites use
   `json/parse-string` and `json/generate-string` with the same arities
   as the previous engine; the alias keeps one require target for the
   whole codebase and lets the engine change behind it."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]))

(defn- keyword->json-str
  "Convert a keyword to its JSON string representation, preserving the
   namespace as ns/name (data.json's write-str drops the namespace on
   .toString, so we must pre-convert namespaced keywords to strings)."
  [k]
  (if-let [ns (and (keyword? k) (namespace k))]
    (str ns "/" (name k))
    (if (keyword? k) (name k) k)))

(defn- prepare-for-write
  "Walk X and convert any namespaced keyword map-keys to strings —
   data.json's JSONWriter calls .toString() on keywords, dropping the
   namespace. Non-namespaced keywords are left as-is (data.json writes
   them correctly via .toString)."
  [x]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into {} (map (fn [[k val]] [(keyword->json-str k) val]) v))
       v))
   x))

(def ^:private base-write-opts
  "Default write options — :escape-slash false keeps / unescaped,
   matching the previous default behavior."
  [:escape-slash false])

(def parse-string
  "Parse the JSON string S into Clojure data. KEYWORDS? true converts
   object keys to keywords; false (default) keeps strings. Malformed
   JSON throws. Array results are lazy seqs — realize inside a guard
   when consuming (see kmet.app.tools/edit)."
  (fn
    ([s] (json/read-str s))
    ([s keywords?] (json/read-str s :key-fn (if keywords? keyword identity)))))

(def generate-string
  "Encode X as JSON text. OPTS passes through to clojure.data.json:
   {:pretty bool :escape-non-ascii bool} and the rest of its option map."
  (fn
    ([x] (apply json/write-str (prepare-for-write x) base-write-opts))
    ([x opts]
     (apply json/write-str (prepare-for-write x)
            (concat base-write-opts (mapcat (fn [[k v]] [(keyword k) v]) opts))))))

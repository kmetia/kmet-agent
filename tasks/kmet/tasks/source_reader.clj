(ns kmet.tasks.source-reader
  "Read source files without loading them — the architecture guards' shared
   reader (kmet.libs.test-self-contained, kmet.libs.test-scope-self-contained,
   kmet.ai.test-self-contained, kmet.test-layer-guard).

   Reader conditionals resolve to THIS host's branch — the view the running
   host loads: #{:bb :clj} on babashka, #{:jolt :clj} on Jolt, #{:clj} on the
   JVM (so :default). clojure.core/read-string's one-arity throws
   \"Conditional read not allowed\" on the JVM for any #? source, and the
   :read-cond default resolves to #{:clj} on every host, which would silently
   check the :default branch instead of bb's on babashka."
  (:require [clojure.java.io :as io]
            [kmet.libs.host :as host]))

(defn reader-features
  "The reader-conditional features of the running host."
  []
  (cond
    (host/jolt?) #{:jolt :clj}
    (host/babashka?) #{:bb :clj}
    :else #{:clj}))

(def reader-views
  "Both reader views a source guard must inspect when a #? branch can define
   behavior: babashka/JVM and Jolt."
  [#{:bb :clj} #{:jolt :clj}])

(defn read-ns-form
  "The ns form of the source file at PATH, or nil when the first form is not
   an ns form. Throws (::unreadable, carrying PATH) when the file cannot be
   read or parsed — a guard that cannot read its file must never silently
   check nothing. CONTEXT labels the failure in its message.

   FEATURES picks the reader branches to resolve. The default is the running
   host's (reader-features); pass a set to read another host's view, and two
   disjoint sets (e.g. #{:bb :clj} and #{:jolt :clj}) when a guard must see
   every branch — `#?` picks the first matching branch per read, so one read
   can never return both."
  ([path] (read-ns-form path (reader-features) "source reader"))
  ([path context] (read-ns-form path (reader-features) context))
  ([path features context]
   (let [form (try
                (read-string {:read-cond :allow
                              :features features}
                             (slurp path))
                (catch Exception e
                  (throw (ex-info (str context " cannot read " path)
                                  {:type ::unreadable :path (str path)} e))))]
     (when (and (seq? form) (= 'ns (first form))) form))))

(defn read-forms
  "Every top-level form of the source file at PATH, read with the running
   host's reader branches. Throws (::unreadable, carrying PATH) when the file
   cannot be read or parsed — the same contract as read-ns-form.

   FEATURES and CONTEXT are read-ns-form's: pass a feature set to read another
   host's view, and two disjoint sets when a guard must see every branch."
  ([path] (read-forms path (reader-features) "source reader"))
  ([path context] (read-forms path (reader-features) context))
  ([path features context]
   (try
     (with-open [r (java.io.PushbackReader. (io/reader path))]
       (let [eof (Object.)]
         (binding [*read-eval* false]
           (loop [forms []]
             (let [form (read {:read-cond :allow
                               :features features
                               :eof eof}
                              r)]
               (if (identical? form eof)
                 forms
                 (recur (conj forms form))))))))
     (catch Exception e
       (throw (ex-info (str context " cannot read " path)
                       {:type ::unreadable :path (str path)} e))))))

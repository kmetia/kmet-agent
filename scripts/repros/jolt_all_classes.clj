(ns jolt-all-classes
  "Repro for jolt#1663: no enumeration of Jolt's modeled classes.

   Run:
     jolt scripts/repros/jolt_all_classes.clj
     bb   scripts/repros/jolt_all_classes.clj

   Jolt can resolve a class by name but offers no way to list the classes it
   models. babashka exposes its whole table as babashka.classes/all-classes;
   the Jolt side prints the class names reachable through clojure.core and the
   absent enumeration API.")

(defn- runtime []
  (cond
    (find-var 'clojure.core/*jolt-version*)
    (str "Jolt " @(find-var 'clojure.core/*jolt-version*))

    (System/getProperty "babashka.version")
    (str "babashka " (System/getProperty "babashka.version"))

    :else
    (str "JVM " (System/getProperty "java.version"))))

(defn- attempt [f]
  (try
    (str (f))
    (catch Throwable e
      (str "throws: " (.getMessage e)))))

(defn- value-of [x]
  (if (var? x) (deref x) x))

(defn- class-name [x]
  (try
    (when (class? x) (.getName x))
    (catch Throwable _ nil)))

(defn- bb-report []
  (let [all-classes (requiring-resolve 'babashka.classes/all-classes)
        cs (all-classes)
        names (mapv #(.getName %) cs)]
    (println "all-classes:" (count cs) "entries,"
             (count (distinct names)) "distinct names")
    (println "sample java.*:" (->> names
                                     (filter #(re-find #"^java\." %))
                                     distinct
                                     sort
                                     (take 5)
                                     vec))))

(defn- jolt-report []
  (let [class-for (requiring-resolve 'jolt.host/jolt-class-for)
        values (vals (ns-map 'clojure.core))
        direct (->> values (filter class?) (keep class-name) distinct)
        reachable (->> values (map value-of) (keep class-name) distinct)]
    (println "jolt.host enumeration vars:"
             (select-keys (ns-publics 'jolt.host)
                          '[all-classes class-names class-table]))
    ;; 642 are direct Class values; java.sql.Date sits behind a Var, so
    ;; dereferencing class vars reaches 643. The issue's 642 counted direct.
    (println "class names in clojure.core ns-map:" (count direct) "direct,"
             (count reachable) "after dereferencing class vars")
    (println "the issue reports 1,030 modeled names (992 graph + 38 non-graph)")
    (doseq [n ["java.lang.String"
               "java.util.Collections"
               "java.net.URL"
               "java.nio.file.WatchKey"]]
      (println n
               "| jolt-class-for:" (attempt #(class-for n))
               "| Class/forName:" (attempt #(Class/forName n))
               "| clojure.core var:" (some? (find-var (symbol (str "clojure.core/" n))))))))

(println "runtime:" (runtime))
(cond
  (find-var 'clojure.core/*jolt-version*) (jolt-report)
  (System/getProperty "babashka.version") (bb-report)
  :else (println "run this repro under Jolt or babashka"))

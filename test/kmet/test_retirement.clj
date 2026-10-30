(ns kmet.test-retirement
  "Guard: the retired namespace prefix. `kmet.agent.*` was kmet1's, and landing
   11 deleted that module; every remaining module's sources speak their own
   module's name. The last survivor, `kmet.agent.ui.tui.tool-renderers`, was
   renamed to `kmet.ui.tui.tool-renderers` when the project dropped its
   pre-1.0 extension-ABI compatibility promise, so nothing carries the prefix
   now and the rule has no exception.

   The module list is `modules.edn`'s, so a module added later is scanned
   without an edit here. The require graph has no edge from this file to what
   it scans, so it runs in the full suite."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tasks.modules :as modules]))

(defn- source-files
  "Every Clojure source file in every module."
  []
  (->> (modules/modules)
       (mapcat (fn [{:keys [src]}] (fs/glob src "**.{clj,cljc,cljd}")))
       (map str)
       sort))

(defn- declared-ns
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [form (read {:eof nil :read-cond :allow} r)]
      (when (and (seq? form) (= 'ns (first form)))
        (str (second form))))))

(t/deftest no-namespace-speaks-the-retired-prefix
  (let [files (source-files)
        parsed (map (fn [f] [f (declared-ns f)]) files)
        stale (->> parsed
                   (filter (fn [[_ ns]] (and ns (str/starts-with? ns "kmet.agent."))))
                   sort)]
    (t/is (seq files) "no sources scanned — check the module list")
    (t/is (every? (comp some? second) parsed)
          "every scanned file declares a namespace — a reader that returned nil
           for all of them would make this guard vacuous")
    (t/is (empty? stale)
          (str "namespaces still use the retired prefix: "
               (mapv second stale)))))

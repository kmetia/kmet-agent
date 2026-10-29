(ns kmet.test-front-end-policy
  "Guard: a front end names the process's resource policy where it wires the
   run, and never reaches for the engine constructor.

   `:load-resources!` and `:expand-input` have no defaults on purpose — the
   process owns where its packages and skill commands come from — so a front
   end that drops one stops loading skills or expanding skill commands without
   failing anything. This holds the wiring the way `kmet.test-seam-requires`
   holds the edges: statically, per call site.

   The handle front ends are what a front end is since landing 8: the layout
   loads the process's resources before it creates its agent and names that
   call, the driver names the expansion chain the engine applies, and neither
   the TUI nor the interactive cluster requires the retired
   `kmet.app.session.driver` (deleted with the handle migration) — they read a
   handle and the shared lifecycle. The require graph has no edge from here to
   the front ends, so this runs in the full suite (`bb test`), not in the
   changed-file loop of a front-end edit."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private handle-front-ends
  "The policy's two halves where a handle front end names them: the layout
   loads the process's resources before it creates its agent, and the handle
   factory names the expansion chain the engine applies."
  {"modules/app/src/kmet/ui/tui/interactive/layout.clj" 'packages/load-resources!
   "modules/app/src/kmet/app/loop/driver.clj" 'packages/expand-input})

(defn- read-forms
  "Every top-level form of PATH, read once."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow} r)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn- ns-requires
  "The namespaces PATH's ns form requires."
  [path]
  (->> (tree-seq coll? seq (first (read-forms path)))
       (filter vector?)
       (map first)
       (filter symbol?)
       set))

(def ^:private tui-sources
  "The TUI's front-end files: the row/provider entry beside the provider
   directory, and everything in it. Both roots, because `fs/glob`'s walk of the
   directory does not include its sibling file — a require in the row would
   otherwise go unseen."
  ["modules/app/src/kmet/ui/tui.clj"
   "modules/app/src/kmet/ui/tui"])

(t/deftest the-tui-reads-a-handle-and-not-the-retired-engine
  (let [files (->> (mapcat (fn [root]
                             (if (fs/directory? root)
                               (map str (fs/glob root "**.clj"))
                               [root]))
                           tui-sources)
                   sort
                   distinct)]
    (t/is (seq files) "the TUI's sources are where the guard looks")
    (t/is (some #(str/ends-with? % "ui/tui.clj") files)
          "the row file is scanned, not just the provider directory")
    (t/is (some #(str/includes? % "/interactive/layout.clj") files)
          "and the interactive cluster is scanned")
    (doseq [path files]
      (t/is (not (contains? (ns-requires path) 'kmet.app.session.driver))
            (str path " requires the retired engine (deleted with the handle"
                 " migration) — the TUI reads a handle and the shared"
                 " lifecycle")))))

(t/deftest a-handle-front-end-names-its-half-of-the-resource-policy
  (doseq [[path value] handle-front-ends]
    (t/testing path
      (t/is (fs/exists? path) "the front end is where the table says")
      (t/is (some #(= value %) (tree-seq coll? seq (read-forms path)))
            (str "it names " value)))))

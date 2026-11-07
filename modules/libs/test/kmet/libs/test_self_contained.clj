(ns kmet.libs.test-self-contained
  "Guards for the kmet.libs.* layer, which is extractable as a third-party
   package and whitelisted wholesale into extension SCI contexts:

   - self-contained: no requires outside kmet.libs.* (app, tui, modes, ai are
     forbidden; sibling libs like kmet.libs.yaml are allowed), statically or
     through a literal `(requiring-resolve 'kmet.…)`
   - sandbox-safe: no static-field reference in any code a sandbox runs. SCI
     has static-method access but no static-field access, so a `Class/FIELD`
     inside a function body is a crash in every extension. An enum constant is
     read with the static method (`valueOf`) or in a top-level `def`
     initializer, which runs on the host once and hands the sandbox the
     resulting value by reference."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.concurrent]
            [kmet.tasks.source-reader :as sr]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [sci.core :as sci]))

(def ^:private lib-files
  "Every lib source file. Memoized: the guards read the same tree repeatedly
   and it does not change during a run."
  ;; recurse: src/kmet/libs/mcp/transport/*.clj is two levels down and the
  ;; one-level scan this replaces silently stopped checking nested libs
  (memoize
   (fn []
     (letfn [(walk [dir]
               (mapcat (fn [f]
                         (if (fs/directory? f) (walk f) [f]))
                       (fs/list-dir dir)))]
       (->> (walk "modules/libs/src/kmet/libs")
            (filter #(re-find #"\.clj[ca]?$" (str %)))
            (map str))))))

(def ^:private parsed-file
  "PATH's rewrite-clj parse tree. Memoized: the static-field and
   runtime-resolve guards read the same files, and a fresh parse is the
   dominant cost on Jolt."
  (memoize
   (fn [path]
     (parser/parse-string-all (slurp path)))))

(defn- ns-form [path]
  ;; Read the ns form itself. A regex over the file was the original
  ;; implementation and it silently checked almost nothing: `.*?` stops at
  ;; the first `)` inside the ns docstring, long before `:require`.
  (sr/read-ns-form path "self-containment guard"))

(defn- kmet-requires [path]
  (when-let [form (ns-form path)]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet."))
         ;; sibling-lib requires are allowed; everything else is not
         (remove #(str/starts-with? % "kmet.libs.")))))

(def ^:private static-field-re
  "A qualified symbol whose name is ALL-CAPS: `TimeUnit/MILLISECONDS`,
   `clojure.lang.PersistentQueue/EMPTY`, `Normalizer$Form/NFKC`."
  #"[a-z][\w$]*(\.[\w$]+)*/[A-Z][A-Z0-9_]*")

(defn- head-symbol
  "The symbol a list node starts with, or nil."
  [n]
  (let [head (first (node/children n))]
    (when (and head (= :token (node/tag head)))
      (let [v (node/sexpr head)]
        (when (symbol? v) v)))))

(defn- quoted-symbol
  "The symbol a resolution argument names: `'sym` parses as `(quote sym)`."
  [form]
  (let [form (if (and (seq? form) (= 'quote (first form))) (second form) form)]
    (when (symbol? form) form)))

(defn- requiring-resolve-targets
  "The `kmet.*` symbols PATH names in a literal `(requiring-resolve 'sym)`.

   The ns-form scan above cannot see a runtime resolution, and the rank guard
   only reads `:require`. A library that reaches an upper layer this way is
   still coupled — and the self-containment claim would be false."
  [path]
  (->> (parsed-file path)
       (tree-seq node/inner? node/children)
       (keep (fn [n]
               (when (= :list (node/tag n))
                 (let [form (node/sexpr n)]
                   (when (and (seq? form) (= 'requiring-resolve (first form)))
                     (let [v (quoted-symbol (second form))]
                       (when (and v (str/starts-with? (str v) "kmet."))
                         v)))))))
       distinct))
(defn- field-refs
  "The static-field symbols in NODE. ENFORCE? is false inside a top-level `def`
   initializer — that code runs on the host, once, at load, and the sandbox
   receives the resulting value by reference — and true again inside a function
   body there, because a function body is the code a sandbox executes."
  [n enforce?]
  (let [tag (node/tag n)
        children (when (node/inner? n) (node/children n))]
    (cond
      (= :token tag)
      (let [v (node/sexpr n)]
        (if (and enforce? (symbol? v) (re-matches static-field-re (str v))) [v] []))

      ;; a `#(...)` lambda: its body is sandbox code wherever it appears
      (= :fn tag)
      (mapcat #(field-refs % true) children)

      (= :list tag)
      (let [head (head-symbol n)]
        (cond
          (= 'def head) (concat (mapcat #(field-refs % enforce?) (butlast children))
                              ;; the initializer runs on the host
                                (field-refs (last children) false))
          (contains? '#{fn fn*} head) (mapcat #(field-refs % true) children)
          :else (mapcat #(field-refs % enforce?) children)))

      (some? children)
      (mapcat #(field-refs % enforce?) children)

      :else [])))

(defn- static-fields
  "The static-field references in PATH that a sandbox would have to resolve."
  [path]
  (distinct (mapcat #(field-refs % true)
                    (node/children (parsed-file path)))))

(deftest libs-name-no-static-fields-in-sandbox-code
  ;; SCI has static-method access but no static-field access, and every
  ;; kmet.libs.* namespace is whitelisted into extension contexts — so a
  ;; `Class/FIELD` inside a function body is a crash at the call, in the sandbox
  ;; only (the host runs it fine). A `def` initializer is exempt because it runs
  ;; on the host; an enum constant is read there with the static method
  ;; (`(TimeUnit/valueOf "MILLISECONDS")`), which the sandbox resolves too.
  (doseq [f (lib-files)
          :let [fields (static-fields f)]]
    (is (empty? fields)
        (str f " names static fields " (vec fields)
             " in code a sandbox runs — SCI has no static-field access; either read"
             " it in a top-level def initializer (host-side) or use a non-enum"
             " static method"))))

(deftest libs-are-self-contained
  (doseq [f (lib-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces, found: " (vec deps))))))

(deftest libs-do-not-resolve-upward-at-runtime
  ;; A literal `(requiring-resolve 'kmet.app.…)` is the same upward edge as a
  ;; `:require` the ns-form scan forbids — and the rank guard cannot see it.
  (doseq [f (lib-files)
          :let [targets (remove #(str/starts-with? (str %) "kmet.libs.")
                                (requiring-resolve-targets f))]]
    (is (empty? targets)
        (str f " resolves an upper-layer namespace at runtime: " (vec targets)
             " — install the host sink (kmet.libs.log) or return the value,"
             " never require upward"))))

(deftest the-queue-runs-inside-a-sandbox
  ;; the static-field rule above is checked at the source; this is the executable
  ;; half for the primitive landing 6a added — a bare SCI context (no host classes
  ;; registered, no imports) must create a queue and hand a value over, because
  ;; extensions reach this namespace through exactly such a context.
  (let [ctx (sci/init {:namespaces
                       {'kmet.libs.concurrent
                        (into {}
                              (for [[sym v] (ns-publics 'kmet.libs.concurrent)]
                                [sym @v]))}})]
    (is (= :handed-over
           (sci/eval-string* ctx
                             "(let [q (kmet.libs.concurrent/queue)]
                                (kmet.libs.concurrent/put! q :handed-over)
                                (kmet.libs.concurrent/take! q))"))
        "put!/take! work with no host classes available")
    (is (= :kmet.libs.concurrent/closed
           (sci/eval-string* ctx
                             "(let [q (kmet.libs.concurrent/queue)]
                                (kmet.libs.concurrent/close! q)
                                (kmet.libs.concurrent/take! q))"))
        "and so does close!, marker and all")))

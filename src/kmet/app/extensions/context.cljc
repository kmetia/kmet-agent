(ns kmet.app.extensions.context
  "Per-extension isolated evaluation contexts: artifact/jar discovery,
   class seeding, the shared SCI base, loader construction (SCI and the
   Jolt native loader), and source evaluation. Private machinery of
   kmet.app.extensions — see there for the runtime contract."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [sci.core :as sci]
            [kmet.libs.host :as host]
            [kmet.loader.sci-loader :as loader-sci]
            #?(:jolt [kmet.loader.jolt-loader :as loader-jolt]))) ; jolt

;; ─── Isolated extension contexts (sci) ───────────────────────────────────
;; Each extension evaluates inside its own sci context: a private namespace
;; registry plus a per-extension loader that serves (1) the extension's own
;; files, (2) the jars its deps.edn declares (the complete transitive
;; closure, resolved in-process via clojure.tools.deps), and (3) anything else on
;; the classpath. Global namespaces the extension may touch — kmet.extension
;; (the contract), clojure.*, babashka.*, and the shared library layers
;; kmet.tui.* (pi: @earendil-works/pi-tui) and kmet.libs.* (generic
;; self-contained utilities) — are injected as shared references, never
;; re-evaluated, so kmet's registries and protocols are not duplicated.
;; kmet.* namespaces outside that set (app internals) are not served at
;; all: re-evaluating them in a context would create context-local
;; copies of their registries, and sharing them would break the layer
;; boundary.

(def ^:private bb-imports
  "babashka's default imports (babashka.impl.classes/imports): the
   unqualified classnames lib sources may use."
  '{AbstractMethodError java.lang.AbstractMethodError
    Appendable java.lang.Appendable
    ArithmeticException java.lang.ArithmeticException
    AssertionError java.lang.AssertionError
    BigDecimal java.math.BigDecimal
    BigInteger java.math.BigInteger
    Boolean java.lang.Boolean
    Byte java.lang.Byte
    Callable java.util.concurrent.Callable
    Character java.lang.Character
    CharSequence java.lang.CharSequence
    Class java.lang.Class
    ClassCastException java.lang.ClassCastException
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Compiler clojure.lang.Compiler
    Double java.lang.Double
    Error java.lang.Error
    Exception java.lang.Exception
    ExceptionInInitializerError java.lang.ExceptionInInitializerError
    IndexOutOfBoundsException java.lang.IndexOutOfBoundsException
    IllegalArgumentException java.lang.IllegalArgumentException
    IllegalStateException java.lang.IllegalStateException
    Integer java.lang.Integer
    InterruptedException java.lang.InterruptedException
    Iterable java.lang.Iterable
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    LinkageError java.lang.LinkageError
    Math java.lang.Math
    NullPointerException java.lang.NullPointerException
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runnable java.lang.Runnable
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    SecurityException java.lang.SecurityException
    Short java.lang.Short
    StackOverflowError java.lang.StackOverflowError
    StackTraceElement java.lang.StackTraceElement
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    ThreadLocal java.lang.ThreadLocal
    Thread$UncaughtExceptionHandler java.lang.Thread$UncaughtExceptionHandler
    Throwable java.lang.Throwable
    VirtualMachineError java.lang.VirtualMachineError
    ThreadDeath java.lang.ThreadDeath
    UnsupportedOperationException java.lang.UnsupportedOperationException})

(def bb-bundled-libs
  "Libraries babashka bundles whose evaluated Maven copy fails in an SCI
   extension context on bb — a native implementation backed by classes the
   GraalVM image does not register for reflection, or an SCI representation
   limit. Verified on bb 1.13.222: cheshire (Jackson reflection),
   clj-commons/clj-yaml (flatland defrecord over a protocol), http-kit
   (unregistered org.httpkit classes), selmer (java.sql.Time). Extensions
   should omit them from deps.edn and use the bundled copy — JSON, YAML and
   HTTP have the kmet.libs.json/yaml/http seams. Plain Maven libs bb happens
   to preload whose copies run fine, and libs injected as bundled ports
   under clojure.*/rewrite-clj/edamame (shared-namespace? handles those, see
   bundled-artifacts for the closure exclusion), are deliberately not
   listed."
  #{"cheshire/cheshire"
    "clj-commons/clj-yaml"
    "http-kit/http-kit"
    "selmer/selmer"})

(def ^:private bundled-port-namespaces
  "bb-bundled namespaces that are reduced custom ports, not the Maven
   sources: bb pre-loads them and serves them under its own require, and
   the raw Maven copies fail under SCI (tools.reader 1.3+ has deftypes
   implementing the java.io.Closeable interface, which SCI's deftype
   rejects). Injected by reference like the adapted libs — a declared
   Maven version cannot win for these, because it would not evaluate."
  '#{clojure.tools.reader
     clojure.tools.reader.edn
     clojure.tools.reader.reader-types})

(def ^:private bb-shared-namespaces
  "bb pre-loads these adapted-lib namespaces at startup (rewrite-clj ports,
    edamame, the data.xml family) and their Maven copies cannot run under
    SCI: rewrite-clj/edamame require clojure.tools.reader.impl.* (impl.inspect
    dispatches on the removed PersistentArrayMap$Seq class), and data.xml's
    copy uses definline (unsupported by SCI). Injected by reference into
    extension contexts like the custom ports, so extensions resolving them
    get the bundled copy and must not declare the Maven libs in deps.edn."
  '#{rewrite-clj.node
     rewrite-clj.parser
     rewrite-clj.paredit
     rewrite-clj.zip
     rewrite-clj.zip.subedit
     edamame.core
     clojure.data.xml})

(def ^:private missing-classname-re
  "Matches SCI's analysis error for an unregistered class:
   `Unable to resolve classname: fq.Name`."
  #"Unable to resolve classname: (\S+)")

(def ^:private missing-symbol-re
  "Matches SCI's analysis error for an unresolvable qualified symbol,
   `Unable to resolve symbol: Qualifier/member`, where the qualifier may
   itself be dotted: short-name statics surface here (`Thread/sleep` —
   the analyzer resolves the qualifier through :imports before it ever
   consults :classes), and so do BARE fully-qualified class names
   (`java.net.http.HttpTimeoutException` alone, as opposed to
   `fq.Name/member`), which carry no member part at all."
  #"Unable to resolve symbol: ([^/\s]+?)(?:/\S+)?$")

(defn- try-add-class!
  "Class/forName FQ-NAME and register it on CTX via sci/add-class!. True
   on success; false when the host cannot load the name (ClassNotFound,
   or a runtime-registered provider class like MessageDigest on Jolt).
   Both hosts load ordinary JDK classes by name; factory-made runtime
   classes (MessageDigest delegates) are NOT forName-loadable and stay on
   the explicit runtime-classes fallback below."
  [ctx fq-name]
  (try
    (sci/add-class! ctx (symbol fq-name) (Class/forName ^String fq-name))
    true
    (catch Throwable _ false)))

(defn- try-add-class-short!
  "Register a bb-imports short name (e.g. StringBuilder) as well as its
   FQ name. SCI resolves a SHORT type hint (`^StringBuilder`) through
   class->opts by the short symbol, falling back to the env :imports map
   — a fallback that is host-dependent. Registering both keys makes
   short hints resolve identically on bb and Jolt. Returns true when
   either registration succeeded."
  [ctx short-sym fq-name]
  (let [fq-ok? (try-add-class! ctx fq-name)]
    (when-let [^Class c (try (Class/forName ^String fq-name) (catch Throwable _ nil))]
      (try (sci/add-class! ctx short-sym c) (catch Throwable _ nil)))
    fq-ok?))

(def ^:private runtime-classes
  "Live Class objects for classes Class/forName cannot load: JDK factory
   methods return internal wrappers (e.g. MessageDigest/getInstance
   returns a $Delegate$CloneableDelegate) whose names are not loadable.
   Seeded per context after the bb-imports sweep (try-add-class! covers
   every ordinary class). Extend when another factory-made class is
   needed — ordinary classes need no entry here."
  (delay [(class (java.security.MessageDigest/getInstance "SHA-256"))
          (.getSuperclass (class (java.security.MessageDigest/getInstance "SHA-256")))]))

(def ^:private tui-library-namespaces
  "The generic TUI layer and the supported app-level tool renderers shared
   with extension contexts. Required once before per-extension contexts are
   built; injected by reference so component and protocol identity is shared."
  '[kmet.tui.autocomplete
    kmet.tui.border
    kmet.tui.core
    kmet.tui.fuzzy
    kmet.tui.hiccup
    kmet.tui.keybindings
    kmet.tui.keys
    kmet.tui.macros
    kmet.tui.protocols
    kmet.tui.theme
    kmet.tui.timers
    kmet.tui.utils
    kmet.tui.components.alt-screen-flash
    kmet.tui.components.box
    kmet.tui.components.cancellable-loader
    kmet.tui.components.container
    kmet.tui.components.dynamic-border
    kmet.tui.components.editing
    kmet.tui.components.editor
    kmet.tui.components.expandable-text
    kmet.tui.components.h-stack
    kmet.tui.components.image
    kmet.tui.components.input
    kmet.tui.components.markdown
    kmet.tui.components.scroll-view
    kmet.tui.components.select-list
    kmet.tui.components.settings-list
    kmet.tui.components.spacer
    kmet.tui.components.spinner
    kmet.tui.components.stack
    kmet.tui.components.text
    kmet.tui.components.v-stack
    kmet.tui.components.truncated-text
    kmet.app.ui.tool-renderers
    kmet.app.keybindings])

(def ^:private libs-library-namespaces
  "The generic kmet.libs.* layer shared with extension contexts. Every lib
   is self-contained (enforced by kmet.libs.test-self-contained), so the
   whole prefix is whitelisted. Required once here so they exist when a
   per-extension context is built — injection is by reference, never
   re-evaluated, so any protocols they define keep their identity. Keep in
   sync with src/kmet/libs/ when a lib is added or removed, except a host
   backend its caller loads at call time on its own platform
   (kmet.libs.clipboard-win loads user32.dll — unloadable elsewhere)."
  '[kmet.libs.archive
    kmet.libs.aws-sigv4
    kmet.libs.clipboard
    kmet.libs.concurrent
    kmet.libs.crypto
    kmet.libs.dynamic-value
    kmet.libs.edit-diff
    kmet.libs.edn-store
    kmet.libs.highlight
    kmet.libs.host
    kmet.libs.http
    kmet.libs.json
    kmet.libs.jsonrpc
    kmet.libs.markdown
    kmet.libs.oauth
    kmet.libs.process
    kmet.libs.reakt
    kmet.libs.sse
    kmet.libs.terminal
    kmet.libs.terminal-image
    kmet.libs.yaml])

(defn- ns-path
  "The classpath path for NS-SYM: namespace-munged (dashes → underscores),
   dots as slashes — matching how jars and source dirs store files."
  [ns-sym]
  (str/replace (namespace-munge (str ns-sym)) "." "/"))

(def ^:private source-extensions
  "Source file suffixes probed (in order) for a strict ns-path lookup."
  [".cljc" ".clj" ".bb"])

(defn- entry-name-ok?
  "True when RAW (a jar entry name) is a safe relative path: not absolute,
   no .. segments. Normalizes \\ → / first (the zip spec allows both;
   mirrors kmet.libs.archive/entry-target)."
  [raw]
  (let [rel (str/replace (str raw) "\\" "/")]
    (not (or (str/blank? rel)
             (str/starts-with? rel "/")
             (some #(= ".." %) (str/split rel #"/"))))))

(defn jar-entry-source
  "The source string of ENTRY-NAME inside the zip at JAR-PATH, or nil.
   Opens and closes the ZipFile per call — no handles are held, so unload
   needs no cleanup. ZipFile, not JarFile: Jolt implements java.util.zip
   and not java.util.jar."
  [jar-path entry-name]
  (let [jar (java.util.zip.ZipFile. (str jar-path))]
    (try
      (when-let [entry (.getEntry jar ^String entry-name)]
        (when-not (.isDirectory entry)
          (with-open [is (.getInputStream jar entry)]
            (slurp is))))
      (finally (.close jar)))))

(defn- jar-entry-names
  "The set of safe relative entry names in the zip at JAR-PATH."
  [jar-path]
  (let [jar (java.util.zip.ZipFile. (str jar-path))]
    (try
      (into #{}
            (comp (map (fn [^java.util.zip.ZipEntry e] (.getName e)))
                  (map #(str/replace % "\\" "/"))
                  (filter entry-name-ok?))
            (enumeration-seq (.entries jar)))
      (finally (.close jar)))))

(defn jar-namespaces
  "The entry-name set + namespace-symbol set of the jar at JAR-PATH,
   collected once at load: {:entries #{...} :namespaces #{...}}. Namespace
   symbols reverse-map from the safe .clj/.cljc/.bb entry names."
  [jar-path]
  (let [entries (jar-entry-names jar-path)]
    {:entries entries
     :namespaces (into #{}
                       (comp (filter #(re-find #"\.(cljc|clj|bb)$" %))
                             (map #(str/replace % #"\.(cljc|clj|bb)$" ""))
                             (map #(str/replace % "_" "-"))
                             (map #(str/replace % "/" "."))
                             (map symbol))
                       entries)}))

(defn artifact-source
  "The source of NS-SYM in ARTIFACT (:dir/:jar/:resource-dir/:resource-file),
   or nil. Strict ns-path lookup: dirs probe <root>/<ns-path>.<ext> (direct
   fs probes need no follow-links handling — symlinked roots resolve through
   the fs); resource dirs probe the same path under the artifact's resource
   prefix; a resource file answers only its recorded entry namespace from the
   exact resource key; jars probe entry names through a per-call ZipFile.
   Returns {:source :display}."
  [{:keys [kind root prefix] :as artifact} ns-sym]
  (let [base (ns-path ns-sym)]
    (case kind
      :jar
      (some (fn [ext]
              (when-let [source (jar-entry-source root (str base ext))]
                {:source source :display (str root "!/" base ext)}))
            source-extensions)

      :resource-file
      (when (= ns-sym (:entry-ns artifact))
        (when-let [r (io/resource (:path artifact))]
          {:source (slurp r) :display (:path artifact)}))

      :resource-dir
      (some (fn [ext]
              (when-let [r (io/resource (str prefix "/" base ext))]
                {:source (slurp r) :display (str prefix "/" base ext)}))
            source-extensions)

      (some (fn [ext]
              (let [f (io/file (str root) (str base ext))]
                (when (.exists f)
                  {:source (slurp f) :display (str f)})))
            source-extensions))))

(defn artifact-owns-ns?
  "True when NS-SYM is one of ARTIFACT's own namespaces. Strict layout, so
   membership derives from paths, never file contents: dirs probe the fs,
   resource dirs the resource prefix, jars check JAR-INFO (the
   entry/namespace sets collected once at load; nil for the others)."
  [{:keys [kind root prefix] :as artifact} jar-info ns-sym]
  (case kind
    :jar (contains? (:namespaces jar-info) ns-sym)
    :resource-file (= ns-sym (:entry-ns artifact))
    :resource-dir
    (boolean (some (fn [ext]
                     (io/resource (str prefix "/" (ns-path ns-sym) ext)))
                   source-extensions))
    (boolean (some (fn [ext]
                     (.exists (io/file (str root) (str (ns-path ns-sym) ext))))
                   source-extensions))))

(defn deps-of-root
  "The :deps map from deps.edn of ARTIFACT (an artifact map — a :dir/:jar
   root or a :resource-dir prefix), or nil."
  [artifact]
  (let [f (case (:kind artifact)
            :resource-dir (io/resource (str (:prefix artifact) "/deps.edn"))
            :resource-file nil
            (let [f (io/file (str (:root artifact)) "deps.edn")]
              (when (.exists f) f)))]
    (when f
      (:deps (edn/read-string (slurp f))))))

(defn- track-cached-jar!
  "Track the JVM's cached JarFile for the archive at ROOT in JAR-FILES (a
   {root jar-file} atom) so `unload-extension!` can close it. Opening a
   `jar:` URL with the JDK's default caching pins the archive for the JVM's
   lifetime — on Windows the jar then cannot be deleted after unload. Jolt's
   jar: URLs open per call and cache nothing, so the branch is a no-op
   there."
  [jar-files root url]
  #?(:jolt nil
     :default
     (when (and jar-files (not (contains? @jar-files root)))
       (try
         (let [conn ^java.net.JarURLConnection (.openConnection ^java.net.URL url)]
           (swap! jar-files assoc root (.getJarFile conn)))
         (catch Exception _ nil)))))

(defn- extension-resource-fn
  "A clojure.java.io/resource replacement scoped to one extension artifact:
   own artifact first (dir: file URL when present; resource dir: the
   artifact's resource-prefix lookup; jar: jar:file:...!/entry URL when the
   entry exists), then the deps.edn closure jars, then the
   host classpath. Both arities ([path] [path loader] — the loader is
   ignored). Host slurp opens file: and jar: URLs via openStream, so
   extension code reads bundled resources with no extraction. JAR-INFO is
   the jar-namespaces map collected once at load (nil for dirs) — the zip
   is never re-enumerated per lookup. JAR-FILES registers the JDK's cached
   JarFile for the extension's own jar so unload can release it (see
   track-cached-jar!)."
  [artifact jar-info deps-resolver jar-files]
  (let [host-resource (deref #'clojure.java.io/resource)
        ;; Jolt PR #1123 fixed File.toURI and the file: URL opener on
        ;; Windows, so use the host's normal file URL spelling here too.
        jar-url (fn [root rel]
                  (java.net.URL.
                   (str "jar:" (.toURL (.toURI (io/file root))) "!/" rel)))
        own (fn [rel]
              (let [{:keys [kind root prefix]} artifact]
                (cond
                  (= :jar kind)
                  (when (contains? (:entries jar-info) rel)
                    (let [u (jar-url root rel)]
                      (track-cached-jar! jar-files root u)
                      u))

                  (= :resource-dir kind)
                  (host-resource (str prefix "/" rel))

                  (= :resource-file kind)
                  nil

                  :else
                  (let [f (io/file (str root) rel)]
                    (when (.exists f)
                      (io/as-url f))))))]
    (letfn [(find-it [path]
              (let [rel (str path)]
                (or (own rel)
                    (some (fn [entry]
                            (if (fs/directory? (str entry))
                              ;; a :local/root directory dep
                              (let [f (io/file (str entry) rel)]
                                (when (.exists f) (io/as-url f)))
                              (when (jar-entry-source entry rel)
                                (jar-url entry rel))))
                          (when deps-resolver (deps-resolver)))
                    (host-resource rel))))]
      (fn
        ([path] (find-it path))
        ([path _loader] (find-it path))))))

(defn- shared-var-map
  "The SCI namespace map for one host namespace: every public var copied
   to a sci.lang.Var via sci/copy-var* (ns-interns, not ns-publics —
   some load-bearing vars are private, e.g.
   clojure.spec.alpha/check-spec-asserts, and extensions resolve
   against the full interns surface anyway).
   Host Var objects cannot cross: bb's SCI namespaces already hold
   sci.lang.Vars (ns-interns works there by accident), but Jolt
   namespaces hold clojure.lang.Vars and SCI's analyzer calls
   vars/isMacro on the value (`No method isMacro` otherwise). copy-var*
   preserves :macro/:arglists/:doc metadata (macros keep expanding) and
   the live root (atoms stay deref'able, fns stay callable). Never
   deref: a deref'd fn loses macro metadata and a deref'd atom loses its
   identity."
  [ns-sym]
  (let [sci-ns (sci/create-ns ns-sym)]
    (into {}
          (keep (fn [[k v]]
                  ;; bb's clojure.repl/print-doc is a future, not a var —
                  ;; deref would block-then-cast. Only Vars cross.
                  (when (var? v)
                    [k (sci/copy-var* v sci-ns)])))
          (ns-interns ns-sym))))

(defn- tui-layer?
  "Is namespace name N one of the shared TUI layers? The one place the set is
   spelled out — shared-namespace? (the SCI injection and the native host
   filter) and shared-tui-namespaces both read it."
  [n]
  (or (str/starts-with? n "kmet.tui.")
      (= n "kmet.app.ui.tool-renderers")
      (= n "kmet.app.keybindings")))

(defn- libs-layer?
  "Is namespace name N a shared kmet.libs.* layer? (see tui-layer?)"
  [n]
  (str/starts-with? n "kmet.libs."))

(def ^:private bundled-extension-lib-prefixes
  "The third-party roots of the fixed bundled extension set
   (kmet.app.extension-libs) that are not already covered by the
   clojure.*/babashka.* clauses or the bb port sets. Shared by reference on
   both hosts: the SCI path injects them into the base context, the Jolt
   host view passes them through."
  ["cljfmt" "edamame" "parinferish" "rewrite-clj"])

(defn- bundled-extension-lib?
  "Is namespace name N part of the fixed bundled extension set? (The set's
   clojure.* members — spec.alpha, tools.reader, core.async, rrb-vector —
   are admitted by shared-namespace?'s host clauses and port sets; see
   bundled-extension-lib-prefixes for the rest.)"
  [n]
  (boolean (some #(or (= n %) (str/starts-with? n (str % ".")))
                 bundled-extension-lib-prefixes)))

(defn- shared-namespace?
  "Is NS-OBJ a host namespace extension contexts share? The scan's rule,
   used by the SCI path (which copies the vars into its context) and by the
   Jolt native path (which filters the host root with the same names).
   kmet.extension itself and the clojure.core patches are added on top by
   build-context-namespaces."
  [ns-obj]
  (let [n (str (ns-name ns-obj))]
    (and (not (str/starts-with? n "sci."))
         (not= n "clojure.core")
         ;; plain-bundled libraries whose Maven versions run
         ;; under SCI (data.json, tools.cli, data.csv, ...)
         ;; are NOT injected — they resolve through the
         ;; load-fn, so a declared Maven version wins over
         ;; the host classpath. The adapted ports
         ;; (core.async, bundled-port-namespaces,
         ;; bb-shared-namespaces, the data.xml family), the
         ;; fixed bundled extension set
         ;; (bundled-extension-lib?) and the kmet layers stay
         ;; injected: their Maven copies fail under SCI, so
         ;; the bundled copy is the only working one.
         (not (or (and (str/starts-with? n "clojure.data.")
                       (not (or (= n "clojure.data.xml")
                                (str/starts-with? n "clojure.data.xml."))))
                  (and (str/starts-with? n "clojure.tools.")
                       (not (contains? bundled-port-namespaces
                                       (ns-name ns-obj))))))
         (or (str/starts-with? n "clojure.")
             (str/starts-with? n "babashka.")
             (contains? bb-shared-namespaces (ns-name ns-obj))
             (bundled-extension-lib? n)
             (tui-layer? n)
             (libs-layer? n)))))

(defn- shared-namespace-names
  "The shared host namespaces' names, scanned from all-ns: the native
   backend's host-root filter (create-jolt-loader) and the SCI base
   context's cache key (shared-context — the namespaces copied into the
   base; the set changing invalidates it). kmet.extension is the one
   explicit extra, the contract namespace itself (the clojure.core patches
   ride on clojure.core, which every host has without asking a loader)."
  []
  (into (sorted-set 'kmet.extension)
        (keep (fn [ns-obj] (when (shared-namespace? ns-obj) (ns-name ns-obj))))
        (all-ns)))

(defn- build-context-namespaces
  "The shared namespace map for the base extension context (see
   shared-context): kmet.extension (the contract), the clojure.*/babashka.*
   builtins (incl. slurp/spit, which SCI's builtin clojure.core lacks but
   bb's env has), and the shared library layers kmet.tui.* and kmet.libs.*.
   The loader (kmet.loader.*) lives outside this tree — host machinery,
   deliberately not extension-visible. clojure.java.io/resource is the host
   lookup here; an extension's artifact-scoped resource fn is merged onto
   its fork instead (create-sci-loader). Values are deref'd (see
   shared-var-map): host Var objects cannot enter a SCI context."
  []
  (into {'kmet.extension (shared-var-map 'kmet.extension)
         ;; slurp/spit/file-seq are absent from SCI's builtin clojure.core —
         ;; inject the host fns so extensions can read/write files directly
         ;; (the mcp-adapter used to work around this with babashka.fs
         ;; read-all-lines/write-bytes; file-seq is needed by libs such as
         ;; cljfmt.io's FileEntity protocol). sci merges these into its core.
         'clojure.core {'slurp (deref #'slurp)
                        'spit (deref #'spit)
                        'file-seq (deref #'file-seq)}}
        (keep (fn [ns-obj]
                (when (shared-namespace? ns-obj)
                  [(ns-name ns-obj) (shared-var-map (ns-name ns-obj))]))
              (all-ns))))

(defn ns-form-of-source
  "The (ns ...) form at the start of the SOURCE string, or nil when there
   is none (or it can't be read)."
  [source]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader (.getBytes ^String source "UTF-8")))]
      (let [form (read rdr)]
        (when (and (list? form) (= 'ns (first form)))
          form)))
    (catch Exception _ nil)))

(defn jar-artifact?
  "True when ARTIFACT is a jar/zip artifact (both hosts keep those
   unexpanded: the archive root is the code root)."
  [artifact]
  (and artifact (= :jar (:kind artifact))))

(defn- jar-archive?
  "True when F is a regular .jar/.zip file path."
  [f path]
  (and (fs/regular-file? f)
       (let [lower (str/lower-case (str path))]
         (or (str/ends-with? lower ".jar")
             (str/ends-with? lower ".zip")))))

#?(:jolt
   (defn- temp-root
     "The platform temp dir for the single-file materialization cache:
      $TMPDIR first (Termux has no /tmp and babashka hardcodes
      java.io.tmpdir to /tmp; Jolt honors it, but the explicit lookup is
      the shared pattern), else java.io.tmpdir."
     []
     (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))))

(def default-loaders
  "The :loader value assumed when a manifest omits it (and the implicit
   declaration of a single-file extension): both backends, so the host's
   preference selects exactly the backend that existed before :loader —
   SCI on babashka, the native loader on Jolt."
  [:sci :jolt])

(defn- manifest-loaders
  "Validate and normalize MANIFEST's :loader value. Absent answers
   DEFAULT-LOADERS with :legacy? true (the caller warns); present must be a
   non-empty sequential collection of keywords — unknown kinds are kept
   (forward compatibility), duplicates dropped. Throws on a malformed
   value."
  [path manifest]
  (let [loaders (:loader manifest)]
    (cond
      (nil? loaders)
      {:declared-loaders default-loaders :legacy? true}

      (and (sequential? loaders)
           (seq loaders)
           (every? keyword? loaders))
      {:declared-loaders (vec (distinct loaders)) :legacy? false}

      :else
      (throw (ex-info (str "extension.edn :loader must be a non-empty vector of loader kinds, got: "
                           (pr-str loaders))
                      {:path path :manifest manifest})))))

(defn- manifest-info
  "Validate the manifest map M of the artifact at PATH (FALLBACK-NAME names
   it when :name is absent): {:name :entry-ns :declared-loaders
   :legacy-loader?}. Throws on a non-symbol :entry or a malformed :loader."
  [path m fallback-name]
  (let [{:keys [declared-loaders legacy?]} (manifest-loaders path m)
        entry-ns (:entry m)]
    (when-not (symbol? entry-ns)
      (throw (ex-info (str "extension.edn :entry must be a namespace symbol, got: "
                           (pr-str entry-ns))
                      {:path path :manifest m})))
    {:name (or (:name m) fallback-name)
     :entry-ns entry-ns
     :declared-loaders declared-loaders
     :legacy-loader? legacy?}))

(defn- canonical-path
  "The canonical display/identity path of a filesystem artifact root F
   (symlinks resolved; unresolvable paths kept as normalized strings)."
  [f]
  (try
    (str (fs/canonicalize (io/file f)))
    (catch Exception _ (str (fs/normalize (io/file f))))))

(defn- jar-extension
  "Resolve a jar/zip artifact at ROOT (PATH names it for errors)."
  [root path]
  (when-not (contains? (jar-entry-names root) "extension.edn")
    (throw (ex-info (str "Extension archive " path " has no extension.edn")
                    {:path path})))
  (let [m (edn/read-string (jar-entry-source root "extension.edn"))
        {:keys [name entry-ns declared-loaders legacy-loader?]}
        (manifest-info path m (fs/file-name (io/file root)))]
    {:name name
     :kind :jar
     :artifact {:kind :jar :root root}
     :entry-ns entry-ns
     :declared-loaders declared-loaders
     :legacy-loader? legacy-loader?
     :path (canonical-path (io/file root))}))

(defn- dir-extension
  "Resolve a directory artifact at F (PATH names it for errors)."
  [f path]
  (let [manifest-file (io/file f "extension.edn")]
    (when-not (.exists manifest-file)
      (throw (ex-info (str "Extension dir " path " has no extension.edn")
                      {:path path})))
    (let [m (edn/read-string (slurp manifest-file))
          {:keys [name entry-ns declared-loaders legacy-loader?]}
          (manifest-info path m (fs/file-name f))]
      {:name name
       :kind :dir
       :artifact {:kind :dir :root (str f)}
       :entry-ns entry-ns
       :declared-loaders declared-loaders
       :legacy-loader? legacy-loader?
       :path (canonical-path f)})))

(defn- file-extension
  "Resolve a single-file extension at F."
  [f]
  {:name (fs/file-name f)
   :kind :file
   :artifact nil
   :entry-ns nil
   :declared-loaders default-loaders
   :legacy-loader? false
   :file f
   :path (canonical-path f)})

(defn resolve-extension
  "Resolve PATH into {:name str :kind :file/:dir/:jar :artifact map-or-nil
   :entry-ns symbol-or-nil :declared-loaders [kw ...] :legacy-loader? bool
   :file io.File :path str}. :artifact ({:kind :dir/:jar :root str}) is the
   strict-layout root for manifest extensions; :entry-ns is the manifest
   :entry symbol; :declared-loaders is the manifest's :loader declaration
   (defaulted for legacy manifests). A directory must contain extension.edn;
   a jar must carry it at its root. A plain file is the entry itself
   (:entry-ns nil — its ns is read from the file at load) and implicitly
   supports both loaders. :path is the canonicalized display/identity
   path."
  [path]
  (let [f (io/file path)]
    (cond
      (jar-archive? f path)
      ;; both hosts keep the archive unexpanded: the jar path is the root
      ;; (Jolt's loader reads it through its central directory, babashka
      ;; probes its entries per call — see artifact-source).
      (jar-extension (str f) path)

      (.isDirectory f)
      (dir-extension f path)

      :else
      (file-extension f))))

(defn resolve-extension-descriptor
  "Resolve a bundled descriptor (kmet.app.bundled-extensions/artifacts) into
   the resolve-extension shape. Descriptor kinds :dir/:file/:jar are
   checkout artifacts and resolve exactly like paths (:root names them);
   :resource-dir reads its manifest from the resource prefix and carries a
   descriptor-supplied :native root when the host supports embedded roots;
   :resource-file reads its namespace from the resource and, when
   :native is supplied, carries that exact key for native source mapping.
   DESCRIPTOR's :path is the
   synthetic identity used for display and dedupe."
  [{:keys [kind root prefix path name native]}]
  (case kind
    (:dir :file :jar) (assoc (resolve-extension root) :bundled? true)

    :resource-dir
    (let [manifest-key (str prefix "/extension.edn")]
      (if-let [r (io/resource manifest-key)]
        (let [m (edn/read-string (slurp r))
              {:keys [name entry-ns declared-loaders legacy-loader?]}
              (manifest-info manifest-key m name)]
          {:name name
           :kind :resource-dir
           :artifact (cond-> {:kind :resource-dir :prefix prefix}
                       native (assoc :native native))
           :entry-ns entry-ns
           :declared-loaders declared-loaders
           :legacy-loader? legacy-loader?
           :path path
           :bundled? true})
        (throw (ex-info (str "Bundled extension resource missing: " manifest-key)
                        {:path path :resource manifest-key}))))

    :resource-file
    (if-let [r (io/resource path)]
      ;; the name is the resource's file name, not the manifest name: the
      ;; extension identity a user's own copy of the same single file would
      ;; carry (dedupe, D11); the manifest name stays the display name
      (let [source (slurp r)
            ns-form (ns-form-of-source source)
            entry-ns (when ns-form (second ns-form))]
        (when-not entry-ns
          (throw (ex-info (str "Bundled extension resource does not start with (ns ...): " path)
                          {:path path :resource path})))
        {:name (fs/file-name path)
         :kind :resource-file
         :artifact (when native
                     {:kind :resource-file
                      :path path
                      :entry-ns entry-ns
                      :native native})
         :entry-ns entry-ns
         :declared-loaders default-loaders
         :legacy-loader? false
         :file r
         :path path
         :bundled? true})
      (throw (ex-info (str "Bundled extension resource missing: " path)
                      {:path path :resource path})))))

(defn- ns-clause
  "The (:require ...) / (:use ...) / (:require-macros ...) reference form of
   an ns form, or nil (ns forms: (ns name docstring? attr-map? & refs))."
  [ns-form clause-key]
  (some #(when (and (seq? %) (= clause-key (first %))) %)
        (filter #(not (or (string? %) (map? %))) (nnext ns-form))))

(defn- require-libspec-libs
  "The library symbols of an ns :require / :require-macros / :use clause
   (each libspec is a bare symbol or a [lib ...] vector)."
  [clause]
  (keep (fn [spec]
          (cond
            (symbol? spec) spec
            (and (vector? spec) (seq spec) (symbol? (first spec))) (first spec)
            :else nil))
        clause))

(defn- validate-entry-requires!
  "Fail fast with an actionable error when NS-FORM (the entry ns form, or
   any internal extension ns form validated by the load-fn) requires a
   kmet.* namespace outside the shared set (kmet.extension + kmet.tui.* +
   kmet.libs.*) or the extension's own internal namespaces (OWNS-NS?, a
   path-derived predicate — those resolve regardless of their prefix),
   or requires babashka.http-client directly (outbound HTTP must
   go through kmet.libs.http). Without this the error would be silent:
   sci's require machinery NPEs on a load-fn failure and swallows the
   original exception."
  [ext-name ns-form tui-namespaces libs-namespaces owns-ns?]
  ;; NOTE: :refer [defcomponent] (a macro referred without a namespaced
  ;; use) leaves no libspec — the ns symbol never appears. SCI resolves
  ;; referred macros through the already-required ns, so validation only
  ;; needs the :require entries; nothing extra to check here.
  (doseq [clause-key [:require :require-macros :use]
          lib (require-libspec-libs (ns-clause ns-form clause-key))]
    (let [s (str lib)]
      (cond
        ;; the extension's own internal namespace — always resolvable
        ;; (strict layout: membership derives from paths, not contents)
        (owns-ns? lib) nil

        ;; the loader is host machinery: extensions never need it (their
        ;; requires are served by the loader itself), so it is not shared
        ;; into contexts and requires of it fail like any other unshared
        ;; internal
        (str/starts-with? s "kmet.loader")
        (throw (ex-info
                (str "Extension " ext-name " requires " lib
                     " — the loader is host machinery and is not part of"
                     " the extension contract")
                {:extension ext-name :ns lib}))

        (str/starts-with? s "kmet.tui.")
        ;; tui-namespaces covers what is loaded NOW; the library-root list
        ;; covers what CAN be shared (lazily-loaded internal nss validate
        ;; before their requires are loaded — e.g. review/dialogs needing
        ;; kmet.tui.macros on second load).
        (when-not (or (contains? tui-namespaces lib)
                      (contains? (set tui-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.tui.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        ;; the shared set is the injection allowlist PLUS the full library
        ;; roots: tui-namespaces/libs-namespaces only cover namespaces
        ;; loaded at context-build time, but a lazily-loaded internal ns
        ;; (e.g. review/dialogs requiring kmet.tui.macros on second load)
        ;; must validate against what CAN be shared, not what happens to
        ;; be loaded. The load-fn serves kmet.tui.* from the host anyway.
        (#{"kmet.app.ui.tool-renderers" "kmet.app.keybindings"} s)
        (when-not (or (contains? tui-namespaces lib)
                      (contains? (set tui-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the shared renderer library surface")
                  {:extension ext-name :ns lib})))

        (str/starts-with? s "kmet.libs.")
        (when-not (or (contains? libs-namespaces lib)
                      (contains? (set libs-library-namespaces) lib))
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.libs.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        ;; direct outbound HTTP is not available to extensions — the
        ;; proxy-aware kmet.libs.http boundary is shared by reference
        (= s "babashka.http-client")
        (throw (ex-info
                (str "Extension " ext-name " requires babashka.http-client"
                     " — extensions must use kmet.libs.http (the proxy-aware"
                     " outbound-HTTP boundary) instead")
                {:extension ext-name :ns lib}))

        (and (str/starts-with? s "kmet.")
             (not= lib 'kmet.extension))
        (throw (ex-info
                (str "Extension " ext-name " requires " lib
                     " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")
                {:extension ext-name :ns lib}))))))

(defn- shared-tui-namespaces
  "The set of TUI and supported renderer namespace symbols currently loaded
   (what the context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (when (tui-layer? (str (ns-name ns-obj)))
                 (ns-name ns-obj)))
             (all-ns))))

(defn- shared-libs-namespaces
  "The set of kmet.libs.* namespace symbols currently loaded (what the
   context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (when (libs-layer? (str (ns-name ns-obj)))
                 (ns-name ns-obj)))
             (all-ns))))

#?(:jolt
   (defn- closure-jars
     "The dependency source ROOTS for DEPS-MAP on Jolt — the equivalent of
      bb's jar closure. jolt.deps/resolve-deps (the tools.deps expansion
      engine, part of jolt's own core — but NOT of a `jolt build` app image,
      so in a built binary this resolution fails with \"Could not locate
      jolt/deps.jolt\"; the extension guide records the gap) fetches
      Maven/git deps and returns their jars (unexpanded — jolt loads a jar
      root through its central
      directory) plus :local/root paths; the source provider and the loader
      read both (see dep-source). Resolution failures throw, mirroring the
      bb branch. A jar is read in place, so unload releases it with the
      context like jars on bb."
     [deps-map]
     (let [resolve-deps (requiring-resolve 'jolt.deps/resolve-deps)
           base (System/getProperty "user.dir")
           roots (:roots (resolve-deps deps-map base))]
       (vec roots)))
   :bb
   (do
     (def bundled-artifacts
       "Artifacts babashka ships — clojure + spec are always bundled, and
   rewrite-clj/edamame are bundled adapted ports (their Maven copies require
   clojure.tools.reader.impl.*, which bb does not ship) — excluded from
   extension closures, matching bb's add-deps classpath-overrides: a declared
   Maven copy (jolt needs one) still resolves, but bb serves the bundled port."
       #{"org.clojure/clojure"
         "org.clojure/spec.alpha"
         "org.clojure/core.specs.alpha"
         "rewrite-clj/rewrite-clj"
         "borkdude/edamame"})

     (defn- bundled-artifact?
       "True when ENTRY is a jar of one of the artifacts babashka ships (clojure
   + spec are always bundled), which must not be served to extension
   contexts — the SCI-incompatible Maven copies would be evaluated instead
   of bb's bundled ports. Matches the m2 layout: only the group is
   slash-munged, the artifact name keeps its dots (org.clojure/spec.alpha
   lives at repository/org/clojure/spec.alpha/)."
       [entry]
       (some (fn [ga]
               (let [[g a] (str/split ga #"/" 2)]
                 (str/includes? entry (str "repository/" (str/replace g "." "/") "/" a "/"))))
             bundled-artifacts))

     (defn- closure-jars
       "The complete transitive jar set for DEPS-MAP, computed in-process via
        clojure.tools.deps (bundled with babashka; the native resolver — no
        JVM) — no subprocess, no global classpath changes, nothing written
        outside ~/.m2. Resolution failures throw."
       [deps-map]
       (let [create-basis (requiring-resolve 'clojure.tools.deps/create-basis)
             ;; :root/:user/:project nil = no ambient deps.edn (the old
             ;; -Srepro/-Sdeps-file pairing); the repos are explicit because
             ;; nothing is inherited to supply the tools.deps defaults.
             basis (create-basis {:root nil :user nil :project nil
                                  :extra {:deps deps-map
                                          :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                                      "clojars" {:url "https://repo.clojars.org/"}}}})]
         (->> (:classpath-roots basis)
              (map str)
              (filter #(or (str/includes? % ".m2") (str/includes? % ".gitlibs")))
              (remove bundled-artifact?)
              vec)))))

(defonce ^:private jars-cache (atom {}))

(defn- jars-for
  "The jar set for DEPS-MAP, cached by the map across loads (reloads and
   extensions sharing the same deps reuse it; a deps.edn change is a new
   key and re-resolves)."
  [deps-map]
  (let [key (pr-str deps-map)]
    (or (get @jars-cache key)
        (let [jars (closure-jars deps-map)]
          (swap! jars-cache assoc key jars)
          jars))))

(defn make-deps-resolver
  "Memoized per-extension closure resolver: resolves the extension's jar
   set on the first call (load-extension! forces that call in host scope,
   before the SCI eval — see there), records it on the record's :jars (for
   introspection), reuses it after. nil when the extension has no deps.edn."
  [deps-map jars-atom]
  (when deps-map
    (let [resolved (volatile! nil)]
      (fn []
        (or @resolved
            (let [jars (jars-for deps-map)]
              (reset! jars-atom jars)
              (vreset! resolved jars)))))))

(defn- dep-source
  "The {:file :source} of NS-SYM inside one closure ENTRY of the
   extension's deps.edn resolution, or nil. Jars (both hosts — jolt.deps
   keeps Maven/git deps unexpanded) get per-call ZipFile probes; a
   :local/root directory gets a plain strict ns-path fs probe."
  [entry ns-sym]
  (let [base (ns-path ns-sym)]
    (if (fs/directory? (str entry))
      (some (fn [ext]
              (let [f (io/file (str entry) (str base ext))]
                (when (.exists f)
                  {:file (str f) :source (slurp f)})))
            source-extensions)
      (some (fn [ext]
              (when-let [source (jar-entry-source entry (str base ext))]
                {:file (str entry "!/" base ext) :source source}))
            source-extensions))))

(defn- resource-source
  "The source of NS-SYM from the classpath, or nil."
  [ns-sym]
  (let [base (ns-path ns-sym)]
    (some (fn [ext] (when-let [r (io/resource (str base ext))]
                      {:file (str r) :source (slurp r)}))
          source-extensions)))

(defn- register-source-classes!
  "Pre-register classes an evaluated SOURCE needs: scan the source text
   for fully-qualified static/ctor positions (`(fq.Name/...`, `(fq.Name.`),
   bare class positions (`(instance? fq.Name`), and type hints
   (`^StringBuilder`, `^java.io.InputStream` — hinted classes never appear
   in a callable position, so the scan collects them separately, resolving
   short hints through bb-imports). The loader's :eval-fn calls this for
   every source it evaluates, so the first internal-namespace use of an
   unseeded JDK class resolves on the first analysis pass. Short-name
   statics need no scan — the seed covers bb-imports. Same lazy forName as
   the entry path; unresolvable names are skipped (the analysis error
   surfaces them)."
  [ctx source]
  (let [text (str source)
        ctor-pat (re-pattern (str "\\(" "([a-z][a-z0-9_]*"
                                  "(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+)" "[/.]"))
        inst-pat (re-pattern (str "\\(instance\\?\\s+" "([a-z][a-z0-9_]*"
                                  "(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+)"))
        hint-pat (re-pattern "\\^[a-zA-Z_][a-zA-Z0-9_.]*\\s+")
        ;; NOTE: hint-pat has no capture group, so re-seq returns strings
        ;; (not vectors) — destructure as m, not [m] (a [m] destructure
        ;; binds the leading \^ Character and subs throws
        ;; "Character cannot be cast to String"). Trim the trailing
        ;; whitespace before the bb-imports lookup.
        ;; hints are collected as [short-or-nil fq] so the short alias is
        ;; registered too (see try-add-class-short!).
        hints (keep (fn [m]
                      (let [sym (str/trim (subs m 1))]
                        (if (str/includes? sym ".")
                          [nil sym]
                          (when-let [fq (get bb-imports (symbol sym))]
                            [(symbol sym) (str fq)]))))
                    (re-seq hint-pat text))]
    (doseq [[_ fq] (concat (re-seq ctor-pat text)
                           (re-seq inst-pat text))]
      (try-add-class! ctx fq))
    (doseq [[short fq] hints]
      (if short
        (try-add-class-short! ctx short fq)
        (try-add-class! ctx fq))))
  nil)

(defn- make-source-fn
  "Per-extension namespace resolver for the loader's :sources: own
   artifact (or, for a single-file extension, its literal LITERAL source
   keyed by ns symbol), declared deps (closure resolved lazily on first
   library require), then bb-bundled classpath namespaces. kmet.* beyond
   the contract and undeclared non-bundled libraries are rejected with
   actionable errors — extensions must depend only on kmet.extension.

   Every own-artifact source is require-validated on load (not just the
   entry namespace): a forbidden/misspelled kmet.* require or a direct
   babashka.http-client require in an internal namespace fails with the
   same actionable messages as the entry check. The source's (ns ...)
   must also match the requested symbol (strict layout is enforced at
   load, not just pack time) — otherwise the failure surfaces later as
   a missing init fn."
  [ext-name artifact owns-ns? deps-resolver tui-namespaces libs-namespaces literal]
  (fn [ns-sym]
    (or (when-let [{:keys [source display]} (and literal (get literal ns-sym))]
          (validate-entry-requires! ext-name (ns-form-of-source source)
                                    tui-namespaces libs-namespaces owns-ns?)
          {:file display :source source})
        (when-let [{:keys [source display]} (and artifact (artifact-source artifact ns-sym))]
          (let [ns-form (ns-form-of-source source)]
            (when-not (= ns-sym (second ns-form))
              (throw (ex-info (str "Extension " ext-name " strict layout violation: "
                                   display " declares " (second ns-form)
                                   ", expected " ns-sym)
                              {:extension ext-name :ns ns-sym})))
            (validate-entry-requires! ext-name ns-form
                                      tui-namespaces libs-namespaces
                                      owns-ns?))
          {:file display :source source})
        (when-let [entries (and deps-resolver (deps-resolver))]
          (some (fn [e] (dep-source e ns-sym))
                entries))
        (when-not (str/starts-with? (str ns-sym) "kmet.")
          (resource-source ns-sym))
        (throw (ex-info
                (cond
                  ;; the loader is host machinery (see build-context-namespaces)
                  (str/starts-with? (str ns-sym) "kmet.loader")
                  (str "Extension " ext-name " requires " ns-sym
                       " — the loader is host machinery and is not part of the extension contract")

                  (or (str/starts-with? (str ns-sym) "kmet.tui.")
                      (= (str ns-sym) "kmet.app.ui.tool-renderers")
                      (= (str ns-sym) "kmet.app.keybindings"))
                  (str "Extension " ext-name " requires " ns-sym
                       " — the shared TUI/renderer library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str ns-sym) "kmet.libs.")
                  (str "Extension " ext-name " requires " ns-sym
                       " — the kmet.libs.* library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str ns-sym) "kmet.")
                  (str "Extension " ext-name " requires " ns-sym
                       " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")

                  :else
                  (str "Extension " ext-name " requires " ns-sym
                       " — not declared in deps.edn and not a babashka-bundled library"))
                {:extension ext-name :ns ns-sym})))))

(def ^:private spec-port-namespaces
  "bb-bundled clojure.spec ports (spec.alpha and, transitively, its
   spec.gen.alpha / core.specs.alpha deps). bb does not preload them at
   startup (unlike tools.reader / rewrite-clj), and the Maven copies fail
   under SCI — spec.gen.alpha's locking2 macro expands to
   monitor-enter/monitor-exit, which SCI's core lacks — so they are
   required here (bb serves its own ports) and injected by reference into
   extension contexts by the all-ns scan in build-context-namespaces.
   Extensions (e.g. cljfmt.config) get a working clojure.spec.alpha
   without deps.edn pins."
  '[clojure.spec.alpha])

(defn- seed-context-classes!
  "Pre-register every bb-imports FQ name plus the runtime-classes (factory-made
   classes Class/forName cannot load) on CTX. Ordinary JDK classes resolve by
   name on both hosts, so this sweep makes the common System/File/Thread
   references first-try hits; anything else resolves lazily via
   eval-source-with-retry! / the load-fn path. Same call on both hosts."
  [ctx]
  (doseq [[short fq] bb-imports]
    (try-add-class-short! ctx short (str fq)))
  (doseq [^Class c @runtime-classes]
    (try
      (sci/add-class! ctx (symbol (.getName c)) c)
      (catch Throwable _ nil)))
  nil)

(defn- host-requires!
  "Require the shared library layers before a per-extension context is
   built, so the all-ns scan in build-context-namespaces finds them.
   kmet.app.extension-libs is the fixed bundled extension set (see there):
   on Jolt it is already loaded (statically required for the build
   closure), on bb/JVM this first extension load pulls it in. bb-only ports
   (clojure.spec, rewrite-clj, tools.reader, data.xml — SCI-incompatible
   Maven sources with bb-bundled replacements) are required only on bb:
   Jolt uses the Maven copies from the fixed set instead."
  []
  (require 'clojure.core.async)
  (apply require (concat tui-library-namespaces libs-library-namespaces))
  (require 'kmet.app.extension-libs)
  (when-not (host/jolt?)
    (apply require (concat spec-port-namespaces bb-shared-namespaces)))
  nil)

;; ─── The shared base context (per-extension forks) ───────────────────────

(defn- reader-features
  "The reader-conditional features for SCI-evaluated extension and
   dependency sources: the HOST's own feature plus :clj. Jolt runs the SCI
   backend too (the declared :sci fallback), but a #?(:bb … :jolt …) branch
   must still select what the host selects natively — the features follow
   the host, not the backend."
  []
  (if (host/jolt?) #{:jolt :clj} #{:bb :clj}))

(defonce ^:private shared-context-cache
  ;; {:names (sorted-set shared ns symbols) :ctx sci context} — see shared-context
  (atom nil))

(defn- build-shared-context
  "The base SCI context every extension context forks from: the shared
   namespace map (build-context-namespaces) with the bb imports and the
   runtime classes seeded. The copied shared vars (thousands) are built
   once here, not per extension; forks then add only their own environment
   deltas."
  []
  (let [ctx (sci/init {:namespaces (build-context-namespaces)
                       :classes {:allow :all}
                       :imports bb-imports
                       :features (reader-features)})]
    (seed-context-classes! ctx)
    ctx))

(defn- shared-context
  "The cached base context, built on first use and rebuilt when the shared
   namespace name set changes (shared-namespace-names): host-requires!
   loads the library roots up front, but app code can lazily load more
   clojure.*/babashka.* namespaces, and a context built before that would
   not inject them. Forks hold their own env snapshots, so a rebuild never
   affects already-loaded extensions. Locked: extension loads could be
   concurrent."
  []
  (let [names (shared-namespace-names)]
    (or (when-let [cached @shared-context-cache]
          (when (= names (:names cached))
            (:ctx cached)))
        (locking shared-context-cache
          (let [cached @shared-context-cache]
            (if (and cached (= names (:names cached)))
              (:ctx cached)
              (let [ctx (build-shared-context)]
                (reset! shared-context-cache {:names names :ctx ctx})
                ctx)))))))

(defn- missing-class-of
  "The class to register for an eval failure with message MSG, or nil.
   Three miss shapes: `Unable to resolve classname: fq.Name` (FQN —
   forName it directly); `Unable to resolve symbol: Short/method`
   (short-name static — resolve Short through bb-imports first); and
   `Unable to resolve symbol: fq.Name/member` (a dotted FQ qualifier in
   a class position such as instance? — forName the qualifier directly).
   Returns the FQ name or nil when the message is not a resolvable class
   miss."
  [msg]
  (or (second (re-find missing-classname-re (str msg)))
      (when-let [[_ qualifier] (re-find missing-symbol-re (str msg))]
        (if (str/includes? qualifier ".")
          qualifier
          (when-let [fq (get bb-imports (symbol qualifier))]
            (str fq))))))

(defn- eval-source-with-retry!
  "eval-string* SOURCE in CTX, registering lazily-missed classes until the
   source evaluates or a failure is not a resolvable class miss. SCI
   analyzes the whole source before evaluating, so one source can miss
   several classes in sequence (each retry surfaces the next); the loop
   caps at 100 registrations — past that the source is pathological and
   the last error propagates. Re-evaluation is safe: SCI keeps the
   partially-loaded namespaces in the context, so already-evaluated
   top-level forms re-evaluate idempotently (defs re-def, requires
   no-op). Tracked to avoid re-adding: each miss registers a new class,
   so the loop always makes progress."
  [ctx source]
  (loop [attempt 0]
    (let [result (try
                   (sci/eval-string* ctx source)
                   (catch Exception e e))]
      (if-not (instance? Throwable result)
        result
        (let [fq (missing-class-of (ex-message result))]
          (if (and fq (< attempt 100) (try-add-class! ctx fq))
            (recur (inc attempt))
            (throw result)))))))

(defn with-sci-io
  "Run THUNK with SCI's *out*/*err* bound to the host streams. babashka's
   SCI bundle binds them; on Jolt they start unbound, so extension code
   calling println/prn would fail (`.write` on an unbound var) — Jolt's
   :sci fallback binds them at the two boundaries SCI code runs behind:
   evaluation (eval-extension-source) and registered callbacks
   (loader-aware)."
  [thunk]
  #?(:jolt (sci/binding [sci/out *out* sci/err *err*] (thunk))
     :default (thunk)))

(defn- eval-extension-source
  "The loader's :eval-fn: evaluate one namespace SOURCE in its context —
   pre-register the classes the source references (the bb seed covers
   short names; this covers FQ ones appearing only in a callable
   position), eval with class-miss retry, then wrap failures with the
   source's display (entry and internal namespaces alike; an already
   wrapped nested failure is left alone)."
  [ctx {:keys [source file]}]
  (register-source-classes! ctx source)
  (try
    (with-sci-io #(eval-source-with-retry! ctx source))
    (catch Exception e
      (let [data (try (ex-data e) (catch Throwable _ nil))]
        (if (:extension-file data)
          (throw e)
          (throw (ex-info (str (ex-message e) " (" file ")")
                          (assoc data :extension-file file)
                          e)))))))

#?(:jolt
   (defn- materialize-single-file!
     "Write a single-file extension's SOURCE into a per-extension cache dir at
      the path its namespace names (dots as slashes, dashes munged) and answer
      that dir: the Jolt native reader is strict-layout, so a loose single file
      needs a home at its own path. Keyed by the source's content, so an
      edited extension re-materializes (see the key comment)."
     [ext-name ns-sym file source]
     ;; the key is the CONTENT, not the file name: several single-file probes
     ;; share a base name ("ext.clj") and, written in the same second, the same
     ;; mtime — a name+mtime key would serve one probe's source to another. A
     ;; changed source is a new key, so an edited extension re-materializes.
     (let [dir (str (fs/path (temp-root) "kmet-ext-src"
                             (str/replace
                              (str ext-name "-" (ns-path ns-sym) "-"
                                   (count source) "-" (hash source))
                              #"[^A-Za-z0-9._-]" "_")))
           target (str (fs/path dir (str (ns-path ns-sym) "."
                                         (or (fs/extension (str file)) "clj"))))]
       (fs/create-dirs (fs/parent target))
       ;; write-then-rename: the key IS the content, so a half-written file
       ;; left under a valid key would be read as that content by every later
       ;; load (and by a concurrent one in another process)
       (let [tmp (str target ".tmp")]
         (spit tmp source)
         (fs/move tmp target {:replace-existing true}))
       dir)))

#?(:jolt
   (do
     (defn- own-source-entries
       "The extension's own source files under ROOT (a directory or a jar
        archive): maps of {:rel path-below-root :display path-for-errors
        :source text} for every .clj/.cljc/.jolt entry the strict lookups
        in artifact-source would serve. Dep roots are deliberately not
        walked: they are external libraries."
       [root]
       (if (fs/directory? root)
         (for [f (->> (concat (fs/list-dir root) (fs/glob root "**/*"))
                      (filter fs/regular-file?)
                      distinct)
               :let [rel (str/replace
                          (str (fs/normalize (fs/relativize root (str f))))
                          "\\" "/")]
               :when (contains? #{"clj" "cljc" "jolt"} (fs/extension rel))]
           {:rel rel :display (str f) :source (slurp (str f))})
         (for [rel (jar-entry-names root)
               :when (contains? #{"clj" "cljc" "jolt"} (fs/extension rel))]
           {:rel rel
            :display (str root "!/" rel)
            :source (jar-entry-source root rel)})))

     (defn- validate-native-sources!
       "Validate EXT-NAME's own sources before the Jolt native reader reads
        any: it never calls back into kmet, so the checks the SCI path makes
        lazily in its source provider (make-source-fn) happen here, up
        front, over the artifact's own files — dep roots are deliberately
        skipped, they are external libraries. Two checks: every declared ns
        must live where its name munges to (otherwise the strict-layout
        reader can never find it — the SCI path's 'strict layout
        violation'), and every ns form must satisfy the extension
        contract's requires. ROOT is a directory or a jar archive."
       [ext-name root owns-ns? tui-namespaces libs-namespaces]
       (doseq [{:keys [rel display source]} (own-source-entries root)
               :let [ext (fs/extension rel)
                     ns-form (ns-form-of-source source)]
               :when ns-form]
         (let [relative (subs rel 0 (- (count rel) (inc (count ext))))]
           (when-not (= relative (ns-path (second ns-form)))
             (throw (ex-info (str "Extension " ext-name " strict layout violation: "
                                  display " declares " (second ns-form)
                                  ", which the loader would look for at "
                                  (ns-path (second ns-form)))
                             {:extension ext-name :ns (second ns-form)}))))
         (validate-entry-requires! ext-name ns-form tui-namespaces libs-namespaces owns-ns?)))))

(defn- create-sci-loader
  "The SCI backend's per-extension loader: a fork of the shared base context
   (shared-context — the injected contract + builtins + shared library
   layers, with the imports and classes already seeded) carrying this
   extension's source provider, :load-fn and, for artifact extensions, an
   artifact-scoped clojure.java.io/resource merged onto the fork. Runs on
   babashka, the JVM and — for manifests declaring :sci without a native
   alternative — Jolt; the reader features follow the host (reader-features)."
  [ext artifact owns-ns? deps-resolver literal
   tui-namespaces libs-namespaces]
  ;; the extension's own resources shadow the host's inside the context: a
  ;; jar's entries and a declared dep's roots answer io/resource. The base
  ;; context carries the host lookup; the per-extension fn is merged per
  ;; namespace (sci/merge-opts), leaving the base and sibling forks alone.
  (let [ext-name (:name ext)
        resource-fn (when artifact
                      (extension-resource-fn
                       artifact
                       (when (jar-artifact? artifact) (jar-namespaces (:root artifact)))
                       deps-resolver
                       (:jar-files ext)))]
    (loader-sci/sci-loader
     {:id (str "ext:" ext-name)
      :sources (make-source-fn ext-name artifact owns-ns? deps-resolver
                               tui-namespaces libs-namespaces literal)
      :base (shared-context)
      :namespaces (when resource-fn
                    {'clojure.java.io {'resource resource-fn}})
      :sci-opts {:classes {:allow :all}
                 :imports bb-imports
                 :features (reader-features)}
      :eval-fn eval-extension-source})))

#?(:jolt
   (defn- jolt-host-builtin?
     "May the native Jolt loader ask the host root for NM on demand? The
      loaded-namespace scan (shared-namespace-names) cannot cover stdlib names
      no host namespace has needed yet — rewrite-clj's custom zipper requires
      clojure.zip — so the classpath loader's host view passes the standard
      families (clojure.*/babashka.*) through lazily. The exclusions keep the
      declared-deps isolation: clojure.data.*/clojure.tools.* and the spec
      artifacts are ordinary Maven deps on Jolt (its resolver drops clojure's
      transitive spec.alpha), so a declared version must win over any host
      copy. NM is an :ns name or an :ns/var name."
     [nm]
     (let [n (str nm)
           n (if-let [i (str/index-of n "/")] (subs n 0 i) n)]
       (and (or (str/starts-with? n "clojure.")
                (str/starts-with? n "babashka."))
            (not (str/starts-with? n "clojure.data."))
            (not (str/starts-with? n "clojure.tools."))
            (not (or (str/starts-with? n "clojure.spec.")
                     (str/starts-with? n "clojure.core.specs.")))))))

#?(:jolt
   (defn- create-jolt-loader
     "The native backend's per-extension loader: own sources from a directory
      root, a jar, an embedded directory root, or an exact embedded source
      mapping for a single file; plus the dep roots (jars load in place), with
      the shared contract as the filtered host root. Own filesystem sources are
      validated up front because the native reader never calls back into kmet;
      bundled embedded artifacts are gated by bb check-bundled-extensions."
     [ext-name artifact owns-ns? deps-resolver literal
      tui-namespaces libs-namespaces]
     (let [native (:native artifact)
           mapped-file? (and native (= :resource-file (:kind artifact)))
           embedded? (boolean native)
           dep-roots (vec (when deps-resolver (deps-resolver)))
           root (cond
                  mapped-file? nil
                  embedded? native
                  artifact (:root artifact)
                  :else (let [[ns-sym {:keys [file source]}] (first literal)]
                          (materialize-single-file! ext-name ns-sym file source)))
           roots (into (if root [root] []) dep-roots)
           sources (when mapped-file?
                     {(str (:entry-ns artifact)) (:path artifact)})]
       (when-not embedded?
         (validate-native-sources! ext-name root owns-ns? tui-namespaces libs-namespaces))
       (loader-jolt/classpath
        roots
        {:id (str "ext:" ext-name)
         :sources sources
         :parent (loader-jolt/host-view
                  (loader-jolt/root)
                  (shared-namespace-names)
                  ;; the stdlib families resolve lazily; the fixed bundled
                  ;; extension set loads eagerly, so this catches a set
                  ;; sub-namespace outside its require chain while the host
                  ;; copy still wins per the set's contract
                  {:also (fn [nm]
                           (or (jolt-host-builtin? nm)
                               (bundled-extension-lib? nm)))})}))))

(defn host-loader-preference
  "Loader backends available on this host, most preferred first: Jolt has
   the native loader and falls back to SCI; babashka (and the JVM) has only
   the SCI backend."
  []
  (if (host/jolt?) [:jolt :sci] [:sci]))

(defn select-loader-kind
  "The backend to load an extension declaring DECLARED-LOADERS, or nil when
   this host offers none of them. The host's preference order decides (Jolt
   prefers its native loader over the SCI fallback), never the manifest's
   order — :loader is a compatibility set, not a ranking."
  [declared-loaders]
  (let [declared (set declared-loaders)]
    (first (filter declared (host-loader-preference)))))

(defn resource-kind?
  "True for the bundled resource artifact kinds — a built artifact's view
   of a directory/file extension."
  [kind]
  (contains? #{:resource-dir :resource-file} kind))

#?(:jolt
   (defn- embedded-roots?
     "Does this Jolt runtime support embedded loader roots? The capability
      probe is the Phase B gate: without it, bundled resource artifacts
      stay on the SCI backend."
     []
     (loader-jolt/embedded-roots?)))

(defn forced-loader-kind
  "The loader-kind a bundled resource artifact must use, or nil when the
   manifest declares none. A resource descriptor carrying :native prefers
   Jolt's native loader behind the embedded-root capability probe; any
   descriptor without a usable native root, or a manifest that does not
   declare :jolt, falls back to the required :sci backend."
  [{:keys [kind artifact]} declared-loaders]
  (when (resource-kind? kind)
    (let [native #?(:jolt (if (and (:native artifact) (embedded-roots?))
                            :jolt
                            :sci)
                    :default :sci)]
      (first (filter #(contains? (set declared-loaders) %)
                     [native :sci])))))

(defn create-loader
  "Build the isolated loader for one extension on the selected backend
   (LOADER-KIND :sci/:jolt — see select-loader-kind). Both backends get the
   same contract (the contract namespace + builtins + kmet.tui.*/kmet.libs.*;
   the loader itself deliberately excluded) and the same extension sources
   and dep closure — they differ in what that means: injected namespace maps
   and a source provider under SCI, the filtered host root and real source
   roots under the native Jolt loader."
  [loader-kind ext artifact owns-ns? deps-resolver literal]
  (host-requires!)
  #?(:jolt
     (case loader-kind
       :jolt (create-jolt-loader (:name ext) artifact owns-ns? deps-resolver literal
                                 (shared-tui-namespaces) (shared-libs-namespaces))
       :sci (create-sci-loader ext artifact owns-ns? deps-resolver literal
                               (shared-tui-namespaces) (shared-libs-namespaces)))
     :default
     (create-sci-loader ext artifact owns-ns? deps-resolver literal
                        (shared-tui-namespaces) (shared-libs-namespaces))))


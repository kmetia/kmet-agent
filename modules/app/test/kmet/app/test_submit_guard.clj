(ns kmet.app.test-submit-guard
  "The submit guard: a mutation that runs plugin code belongs to the thread that
   owns the composition, so app code makes those calls only from the owner-side
   sites below, and every other thread submits (`kmet.app.runtime/submit!`) and
   rides the turn boundary.

   The kernel has no lock. `provide!`/`on`/`effect` only append and are safe
   from any thread; `plugin!`/`restart!`/the `dispose!` family run `apply` and
   disposers and stay on the owner's thread (`srce.md` §8), because a disposer
   may re-enter the kernel and a concurrent pass could remount a dependent
   mid-teardown. A tool body that mounted instead would race a pass; one that
   submits has its change applied at `:agent-settled` — invisible to the turn
   that queued it, live for the next.

   The ledger fails in both directions (a call site with no reason, a reason
   with no call site) and reads parsed nodes, so a docstring naming a mutator is
   prose. A kernel fn added later that runs plugin code belongs in
   `structural-mutators`; the last test fails when a name in that set stops
   existing, so a rename cannot quietly unguard the scan."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def ^:private source-roots
  "The tree a kmet2 kernel mutation can live in: the app the server embeds, and
   the front end a terminal runs."
  ["modules/app/src"])

(def ^:private structural-mutators
  "The `kmet.srce.core` calls that run plugin code, and are therefore the
   owner's alone. `detach!` is not among them: it removes a ctx and runs no
   `apply` and no disposer, and `settle!` is the pass itself, not a mount. A fn
   the kernel grows that mounts, remounts or tears down belongs here."
  #{"plugin!" "restart!" "update!" "dispose!" "dispose-plugin!" "dispose-subtree!"})

(def ^:private owner-sites
  "Every structural mutation the app and UI sources still make, and why that
   call site is the owner's.

   File (repo-relative) → fn name → the reason. Adding a call means adding a
   reason; deleting one means deleting it here."
  {"modules/app/src/kmet/app/runtime.clj"
   {"plugin!"
    "The composition's own mount (`mount-rows!`). It runs on the thread that called `boot` — the profile row and the preset both mount from here — and this is also where `boundary!`, the drain every other thread's change rides, lives."
    "dispose-subtree!"
    "`shutdown!`: the composition tearing itself down after its mode row stopped serving, on the same thread that booted it."}

   "modules/app/src/kmet/app/presets.clj"
   {"dispose!"
    "The rollback of a preset whose mount threw. It runs inside `:agents/create!`, which serializes creation with a CAS and rolls the child ctx back before any handle is published."}

   "modules/app/src/kmet/app/agents.clj"
   {"dispose-subtree!"
    "`dispose!`'s release of the agent's child ctx — after the factory aborted the turn, which is the ordering `:agents` exists to own. A creation that throws releases the same way, before the handle is published."}

   "modules/app/src/kmet/app/extensions/core.cljc"
   {"plugin!"
    "Loading an extension mounts it under the host ctx (`mount-plugin!`, reached only from `load-extension!` and its descriptor twin). Its callers are the composition's extension rows (`kmet.app.extensions.rows`), the TUI's `/reload` command (through those rows), and the test fixtures — the thread the entry or the command runs on, the same one that unloads the extension below. A thread that mounts from inside a tool would be the bug this guard is for; none does."

    "dispose-subtree!"
    "Unloading an extension: the record leaves the process registry first, then the extension's own ctx — its rows, whatever its body mounted there, and its fiber — unwinds. Its callers are `/reload`'s row reload (`kmet.app.extensions.rows/reload-all!`), a row's disposal effect, the rollback of a load that threw, the process's own shutdown, and tests — all on the thread the command or the entry runs on. A tool thread that unloads would be the bug this guard is for; none does."}})

(defn- source-files
  "Every Clojure source file under SOURCE-ROOTS, repo-relative and
   `/`-normalized."
  []
  (->> source-roots
       (filter fs/directory?)
       (mapcat #(fs/glob % "**/*.{clj,cljc}"))
       (map #(str/replace (str %) "\\" "/"))
       sort))

(defn- ns-form
  "FILE's own `(ns …)` node, or nil. The alias scan reads this and nothing else:
   a docstring that happens to spell the kernel's name is not a require."
  [root]
  (->> (if (= :forms (n/tag root)) (n/children root) [root])
       (filter #(= :list (n/tag %)))
       (filter (fn [node]
                 (when-let [head (first (n/children node))]
                   (= "ns" (n/string head)))))
       first))

(def ^:private srce-alias
  "The alias a file binds `kmet.srce.core` to. Every app and UI require of the
   kernel is `:as srce`."
  #"\[kmet\.srce\.core[^\]]*?:as\s+([^\s\]\)]+)")

(defn- kernel-names
  "Every name FILE refers to `kmet.srce.core` by: the alias its ns form binds,
   plus `srce` and the full namespace — the two spellings a structural call is
   written as even when the file's own alias differs. A call under one of these
   is a call on the kernel whatever the ns form says, so adding the require and
   the call in one edit cannot slip past the scan.

   Throws when the ns form requires the kernel in a shape this scan cannot read
   (a `:refer`, a `:rename`): an unreadable require would silently narrow the
   guard."
  [file root]
  (let [text (if-let [form (ns-form root)] (n/string form) "")
        declared (into #{} (map second) (re-seq srce-alias text))]
    (when (and (re-find #"kmet\.srce\.core\s*:" text) (empty? declared))
      (throw (ex-info (str "the submit guard cannot read " file
                           "'s kmet.srce.core require: alias the kernel with :as")
                      {:type ::unreadable :file file})))
    (into #{"srce" "kmet.srce.core"} declared)))

(def ^:private call-head
  "A qualified call head: `alias/name`."
  #"^([^\s/]+)/([\w!\-\?\*<>]+)$")

(defn- structural-call
  "The `{:fn :row}` NODE performs when it is a structural kernel call under one
   of ALIASES, else nil."
  [node aliases]
  (when (= :list (n/tag node))
    (when-let [head (first (n/children node))]
      (when-let [[_ alias name] (re-matches call-head (n/string head))]
        (when (and (contains? aliases alias)
                   (contains? structural-mutators name))
          {:fn name :row (:row (meta node))})))))

(defn- call-sites
  "Every structural kernel mutation FILE performs, as `[{:fn :row}]` in source
   order. Parsed, not grepped: a comment or a docstring naming a mutator is
   prose."
  [file]
  (let [text (slurp file)
        root (p/parse-string-all text)
        aliases (kernel-names file root)]
    (into [] (keep #(structural-call % aliases))
          (tree-seq n/inner? n/children root))))

;; ── the guard ─────────────────────────────────────────────────────────────

(deftest the-guard-sees-the-trees-it-guards
  (is (every? fs/directory? source-roots)
      "every scanned tree exists — the guard cannot silently narrow")
  (is (seq (source-files)) "the walk finds sources")
  (testing "the walk reads a real call site with its line, and does not read prose"
    (let [sites (call-sites "modules/app/src/kmet/app/runtime.clj")]
      (is (= #{"plugin!" "dispose-subtree!"} (into #{} (map :fn) sites))
          "`runtime.clj` mounts a row and shuts its subtree down — the two
           sites `owner-sites` carries. A third one here is a ledger row, not
           a broken test; the docstring's `srce/plugin!` prose is not a call")
      (is (every? (comp number? :row) sites)
          "a call site carries its line, so a failure can name it"))))

(deftest every-structural-mutation-names-its-thread
  (let [found (into {}
                    (keep (fn [file]
                            (when-let [sites (seq (call-sites file))]
                              [file (vec sites)])))
                    (source-files))
        ledger (into {} (map (fn [[file fns]] [file (set (keys fns))])) owner-sites)]
    (testing "no call site is unaccounted for"
      (doseq [[file sites] found
              {:keys [fn row]} sites
              :when (not (contains? (get ledger file #{}) fn))]
        (is false
            (str file ":" row " calls " fn " with no reason in owner-sites — a"
                 " thread that is not the owner submits instead"))))
    (testing "no reason is stale"
      (doseq [[file fns] ledger
              f (sort fns)]
        (is (contains? (into #{} (map :fn) (get found file [])) f)
            (str file " no longer calls " f " — remove its reason"))))
    (testing "every reason is written"
      (doseq [[file fns] owner-sites
              [f reason] fns]
        (is (and (string? reason) (not (str/blank? reason)))
            (str file "/" f " has a reason"))))
    (is (= 6 (reduce + (map count (vals owner-sites))))
        "the ledger's own size; a mutation moved or added updates the map")))

(deftest a-tool-thread-submits
  (testing "the code that runs on a tool thread never mutates the kernel
            structurally — it submits, and the loop's turn boundary applies it"
    (let [files (filter (fn [f]
                          (or (str/includes? f "/kmet/app/tools/")
                              (str/includes? f "/kmet/app/modes/")
                              (str/includes? f "/kmet/ui/")))
                        (source-files))]
      (is (seq files) "the tool layer, the mode rows and the UI are scanned")
      (is (some #(str/includes? % "/kmet/ui/") files)
          "the UI root contributes")
      (doseq [f files
              {:keys [fn row]} (call-sites f)]
        (is false
            (str f ":" row " calls " fn " — submit the change and let"
                 " `:agent-settled` apply it"))))))

(deftest the-guard-watches-names-the-kernel-has
  (testing "a renamed kernel fn breaks the guard instead of unguarding it"
    ;; ns-publics needs the namespace loaded: a filtered test run loads only the
    ;; requested namespaces, so require it here rather than relying on another
    ;; test namespace having pulled it in.
    (require 'kmet.srce.core)
    (let [publics (into #{} (map (comp name key)) (ns-publics 'kmet.srce.core))]
      (doseq [f (sort structural-mutators)]
        (is (contains? publics f)
            (str "kmet.srce.core has no " f " — rename it in structural-mutators"))))))

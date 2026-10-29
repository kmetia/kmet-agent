(ns kmet.app.test-process-state
  "Landing 9d's guard: every process cell the app still holds has a written
   reason.

   The audit that planned landing 9 counted the mutable `defonce`s a second
   session could observe. 9a–9c took the ones that were settings or a per-agent
   world; what is left is the state a *process* genuinely owns — the extension
   host's bindings, the debug switch, a cache, the one published root — and the
   difference between that and a cell nobody has moved yet is a sentence, not a
   count.

   So the guard is the list: a cell with no reason fails, and a reason with no
   cell fails too, because a stale entry would let the next cell of that name
   slip in. It reads the sources rather than loading them — the subject is what
   a file declares, and a guard that loaded the app would boot half of it.

   `modules/app/src/kmet/ui` holds the front end beside the business tree: the
   two cells there are the one terminal a process runs, and a second session on
   a server never loads that tree — which is exactly why their reasons are the
   terminal's and not the app's."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def ^:private source-root
  "The tree a process cell can live in: the app the server embeds, and the
   front end a terminal runs."
  ["modules/app/src"])

(def ^:private process-cells
  "Every `defonce` the app and UI sources hold, and why each is the process's
   rather than a ctx's.

   File path (repo-relative) → cell name → the reason. Adding a cell means
   adding a reason; deleting one means deleting it here."
  {"modules/app/src/kmet/app/registry.clj"
   {'nobody-published
    "The empty handle `current` answers before a composition publishes one. One per process, so a registration made then is still visible to a later read instead of vanishing into a fresh map."
    'process-handle
    "The one handle a process publishes (`publish-process-handle!`): the extension host and the extension ABI address registries by name, outside any one agent's ctx. The publishing owner hands back the handle it replaced."}

   "modules/app/src/kmet/app/extensions/core.cljc"
   {'extensions
    "The loaded extension records. An extension installs into the process's host and unloads from it; the host is process-level by decision (docs/architecture.md § Service catalog)."
    'cli-flag-values
    "Extension `--flag value` values parsed once from the process argv (`get-flag` reads them). There is one command line per process."
    'extension-binding
    "The process's front-end binding — headless until a front end owns the process. A run's own binding rides `*agent-context*`, so a per-agent front end never needs this cell to change."
    'session-atom
    "The 'current session' mirror the extension api's session facades read. The mode that draws installs its run's session; a run of an agent reads its own through `*agent-context*`."
    'context-sink-atom
    "The front end's base-context contribution (`:build-context`), installed by the one mode that draws."
    'entry-sink-atom
    "The entry/transcript sink extension-contributed entries render through, installed by the one mode that draws."
    'services*
    "What the host may not require — the registry handle, the ctx an `:inject` mounts under, the resource operations — bound by the composition's owner and put back when it goes. Unbound is an error, not a process default."
    'hook-seq
    "The hook id sequence: a hook is not addressed by name and one owner may hold several, so ids must not repeat within a process."}

   "modules/app/src/kmet/app/extensions/rows.clj"
   {'mounted
    "The extensions mounted composition rows own: a row records its entry and config here so `/reload` can reload it with the same config and disposal can unload whichever record is current. The composition is the process's; a row's lifetime is the row's."}

   "modules/app/src/kmet/app/extensions/context.cljc"
   {'jars-cache
    "The extension loader's jar-set cache, keyed by a `deps.edn` map. Its values are derived from the classpath, which does not change in a running process."
    'shared-context-cache
    "The shared SCI base context every extension context forks from: built once (thousands of copied vars) and immutable afterwards."}

   "modules/app/src/kmet/app/session/store.clj"
   {'report-wrong-shape
    "The one `--debug` switch's per-load diagnostic, owned by `kmet.app.debug`; a session loads on whichever thread asked for it and the switch is process-wide."}

   "modules/app/src/kmet/app/tools/run_code.cljc"
   {'bridge-queue
    "The bridge's worker pool — a thread *bound*, not a setting (9b's written decision): the worker count caps concurrent tool executions process-wide, and everything per-run travels in the submission's `:bindings`."
    'lane-queue
    "The admission lanes' one FIFO: a submission enters here in call order, a sequential tool's barrier and the in-flight count are read against it, and the entries carry their run's lane and cancel view. One queue per process, because the ordering a tool's `:sequential` promises must hold across runs that share the pool."
    'lane-dispatcher
    "The one process-wide dispatcher that drains `lane-queue` (started on the first submission). It never blocks on a lane — a settling task posts `:settled` — so a script waiting on a barrier cannot stall another run's submissions, and a hung tool stalls only its own lane. A second dispatcher would admit the same call twice."}

   "modules/app/src/kmet/app/tools/mutation.clj"
   {'tails
    "The per-path mutation tails (key → the promise the last section holding it delivers). Defonce because a reload must not empty the map while a lock is held: the next caller of that path would run alongside the holder. The paths are a process's, not a run's — two runs' tools can name one file."}

   "modules/app/src/kmet/app/debug.clj"
   {'write-lock
    "The one lock every diagnostics write takes: the debug and error files are written from the loop's thread, tool threads and the TUI, and a line must never interleave with a rotation."
    'write-failure-reported?
    "The one-shot guard behind the stderr report for an unwritable log path: the first failure is reported, later ones stay quiet so a full disk does not turn every log call into another error line."
    'debug-enabled
    "The `--debug` switch — the process's command line, and one place owns it (the session loader's diagnostic reads it through this namespace)."}

   "modules/app/src/kmet/app/modes/server.clj"
   {'ask-route
    "The socket server's approval route: installed by the run that serves the socket and put back when it ends. One server per process today; the per-connection surface is 10b/10c."}

   "modules/app/src/kmet/app/root.clj"
   {'app-root
    "The one place a process names its root (docs/architecture.md: root.clj's process-root atom stays). A second root is a second app; a test that publishes its own puts the previous one back."}

   "modules/app/src/kmet/ui/tui/interactive.clj"
   {'live-mode
    "The running terminal mode. One interactive TUI per process because the terminal is: a server's headless profiles never load this tree."
    'pending-answers
    "The dialogs the one terminal is waiting on. Teardown denies them, so a tool call never waits on a dialog nobody can answer."}})

(defn- source-files
  "Every Clojure source file in SOURCE-ROOT, repo-relative and `/`-normalized."
  []
  (->> source-root
       (filter #(fs/directory? %))
       (mapcat #(fs/glob % "**/*.{clj,cljc}"))
       (map #(str/replace (str %) "\\" "/"))
       sort))

(def ^:private defonce-form
  "One `(defonce` and the name that follows its metadata."
  #"\(defonce\s+(?:\^\S+\s+)*([^\s()\[\]{}]+)")

(defn- defonce-names
  "The defonce names FILE declares, in source order.

   The count of `(defonce` forms is compared to the names found, so a cell
   whose name is not on the defonce's first line fails loudly instead of
   escaping the ledger."
  [file]
  (let [text (slurp file)
        names (mapv (comp symbol second) (re-seq defonce-form text))
        forms (count (re-seq #"\(defonce\b" text))]
    (when (not= forms (count names))
      (throw (ex-info (str "the process-state guard cannot read every defonce in " file
                           ": " forms " form(s), " (count names) " name(s)")
                      {:type ::unreadable :file file :forms forms :names names})))
    names))

(deftest the-guard-sees-the-trees-it-guards
  (is (every? fs/directory? source-root)
      "every scanned tree exists — the guard cannot silently narrow")
  (is (= 25 (count (mapcat (fn [[file cells]] (map (fn [n] [file n]) (keys cells)))
                           process-cells)))
      "the ledger's own size; a cell added or removed updates the map")
  (testing "the scan finds the cells the ledger lists"
    (is (= (set (keys process-cells))
           (into #{} (keep (fn [file]
                             (when (seq (defonce-names file)) file)))
                 (source-files))))))

(deftest every-process-cell-has-a-written-reason
  (let [found (into {}
                    (keep (fn [file]
                            (when-let [names (seq (defonce-names file))]
                              [file (set names)])))
                    (source-files))
        ledger (into {}
                     (map (fn [[file cells]] [file (set (keys cells))]))
                     process-cells)]
    (testing "no cell is undeclared"
      (doseq [[file names] found
              name (sort names)]
        (is (contains? (get ledger file #{}) name)
            (str file " declares " name " with no reason in process-cells"))))
    (testing "no reason is stale"
      (doseq [[file names] ledger
              name (sort names)]
        (is (contains? (get found file #{}) name)
            (str file " no longer declares " name " — remove its reason")))
      (doseq [[file cells] process-cells
              [name reason] cells]
        (is (and (string? reason) (not (str/blank? reason)))
            (str file "/" name " has a reason"))))))

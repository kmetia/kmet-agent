(ns kmet.tasks.runner
  "Test runner for both hosts (babashka and jolt).

   Slow tests are marked with ^:slow on the deftest (tests that wait real
   wall-clock time: sleeps, terminal-query timeouts; real network calls; and
   subprocess spawns — bash tool, shell commands, git).
   `bb test` runs all tests except ^:slow ones; `bb test-ext` runs only the
   ^:slow tests. Both are selected at the individual test level — no whole
   namespaces are excluded.

   A deftest marked ^:bb-only exercises bb-only behavior (kmet.tasks.build's
   packaging, bb-bundled SCI ports, Maven deps resolution, the packed-clojure
   extension closure, curl --compressed — babashka.classpath and the bundled
   ports are bb/JVM-only): it runs under `bb test` and is skipped on
   the jolt host. The namespace still loads there (a load gap would report it
   unloaded), so calling a bb-only entry point under jolt surfaces as a fast
   ::bb-only ex-info from the guarded function, not a crash.

   The runner is TOLERANT: every test namespace is required inside a try.
   A namespace that cannot load under the host (a babashka-internal
   require, a JDK class gap, a java.time.* gap — see jolt-port.md) is
   reported and skipped, never fatal; the remaining namespaces run. This
   is what lets the same runner serve `bb test` and `jolt test` while the
   Jolt port is staged. On a full run every unloadable namespace is listed
   with its load failure reason; on a filtered run the list holds only the
   requested namespaces that failed to load.

   Filters select tests: a plain var name (e.g. `test-tool-bash`), an ns/var
   pair (e.g. `kmet.app.test-loop/my-test`), or a whole namespace
   (a known test ns, a dotted ns name, or ns/ with an empty var part).
   Whole-namespace filters select by slow?; var filters ignore slow?.

   Engine differences: on babashka, each test var runs with stdout/stderr
   captured (replayed on failure, discarded on pass) and the counters are
   per-run refs. Jolt's clojure.test port has no per-var ref counters and
   no host output capture — jolt counts into its own process-wide
   `clojure.test/counters` atom, which the runner reads as before/after
   deltas (see run-ns-vars-jolt). The ^:slow split and per-var filters
   work identically on both hosts: a namespace that loads has its vars
   selected by ^:slow metadata, and jolt's clojure.test/test-vars applies
   each namespace's :once/:each fixtures exactly like the bb engine."
  (:require [clojure.string :as str]
            [clojure.test :as t]))

(def all-namespaces
  "Every test namespace. The slow/fast split happens per test var via ^:slow
   metadata, so no namespace is excluded from either run."
  '[kmet.test-utils kmet.test-keys
    kmet.app.test-session
    kmet.app.test-compaction
    kmet.app.test-tools
    kmet.ai.test-llm
    kmet.ai.test-attribution
    kmet.ai.test-models
    kmet.app.test-model-resolver
    kmet.libs.test-dynamic-value
    kmet.libs.test-version
    kmet.ai.test-model-config
    kmet.ai.test-provider-composer
    kmet.ai.test-auth
    kmet.ai.test-google-adc
    kmet.ai.test-oauth
    kmet.ai.test-image-models
    kmet.ai.test-model-data
    kmet.ai.test-self-contained
    kmet.ai.test-constrained-sampling
    kmet.ai.test-api-tools
    kmet.app.test-loop
    kmet.test-theme kmet.test-config
    kmet.test-http-boundary
    kmet.tasks.build-test
    kmet.tasks.build-jolt-test
    kmet.app.test-skills

    kmet.app.test-prompts
    kmet.app.test-extensions
    kmet.app.test-packages
    kmet.test-package-manager
    kmet.app.ui.test-resource-config
    kmet.app.ui.test-hotkeys
    kmet.app.test-extensions-ui
    kmet.app.test-interactive-ui
    kmet.app.test-event-bus
    kmet.app.test-theme-controller
    kmet.app.test-commands
    kmet.app.test-keybindings
    kmet.modes.test-print
    kmet.modes.test-interactive
    kmet.modes.test-overlay-input-smoke
    kmet.test-editing
    kmet.tui.test-fuzzy
    kmet.tui.test-border
    kmet.tui.test-timers
    kmet.tui.test-wake
    kmet.tui.test-autocomplete
    kmet.tui.test-core
    kmet.libs.test-reakt
    kmet.tui.test-reakt-integration
    kmet.tui.test-compute
    kmet.tui.test-hiccup
    kmet.tui.test-dispose
    kmet.tui.components.test-text
    kmet.tui.components.test-spacer
    kmet.tui.components.test-container
    kmet.tui.components.test-box
    kmet.tui.components.test-input
    kmet.tui.components.test-editor
    kmet.tui.components.test-select-list
    kmet.tui.components.test-settings-list
    kmet.tui.components.test-markdown
    kmet.tui.components.test-track
    kmet.tui.components.test-caching-conventions
    kmet.tui.components.test-scroll-view
    kmet.tui.components.test-stack
    kmet.tui.components.test-v-stack
    kmet.tui.components.test-h-stack
    kmet.tui.components.test-truncated-text
    kmet.tui.components.test-alt-screen-flash
    kmet.tui.components.test-cancellable-loader
    kmet.tui.components.test-dynamic-border
    kmet.tui.components.test-spinner
    kmet.tui.components.test-expandable-text
    kmet.tui.test-overlay
    kmet.tui.test-negotiation
    kmet.tui.test-terminal-response
    kmet.tui.test-render-loop
    kmet.tui.test-terminal-native
    kmet.tui.test-utils
    kmet.libs.test-self-contained
    kmet.loader.test-self-contained
    kmet.loader.test-core
    kmet.loader.test-jolt-loader
    kmet.loader.test-sci-loader
    kmet.libs.test-host
    kmet.libs.test-crypto
    kmet.libs.test-sse
    kmet.libs.test-terminal
    kmet.libs.test-terminal-image
    kmet.libs.test-yaml
    kmet.libs.test-markdown
    kmet.libs.test-highlight
    kmet.libs.test-oauth
    kmet.libs.test-edn-store
    kmet.libs.test-edit-diff
    kmet.libs.test-jsonrpc
    kmet.libs.test-json
    kmet.libs.test-http
    kmet.libs.test-archive
    kmet.libs.test-aws-sigv4
    kmet.libs.test-num
    kmet.libs.test-context
    kmet.app.ui.test-chat-history
    kmet.app.ui.test-user-message
    kmet.app.ui.test-image-block
    kmet.app.ui.test-assistant-message
    kmet.app.ui.test-tool-renderers
    kmet.app.ui.test-tool-execution
    kmet.app.ui.test-custom-message
    kmet.app.ui.test-settings-selector
    kmet.app.ui.test-skill-message
    kmet.app.ui.test-summary-message
    kmet.app.ui.test-bash-execution
    kmet.app.ui.test-dialogs
    kmet.app.ui.test-external-editor
    kmet.app.ui.test-login-dialog
    kmet.app.ui.test-footer
    kmet.app.ui.test-footer-data-provider
    kmet.app.ui.test-pending-messages
    kmet.app.ui.test-status-indicator
    kmet.app.ui.test-loaded-resources
    kmet.app.ui.test-scoped-models-selector
    kmet.app.ui.test-model-selector
    kmet.app.ui.test-thinking-selector
    kmet.app.ui.test-auth-selector
    kmet.app.ui.test-dock
    kmet.app.ui.test-session-selector
    kmet.app.ui.test-fork-selector
    kmet.app.ui.test-tree-selector
    kmet.test-core
    kmet.tasks.test-changed
    kmet.tasks.test-clean
    kmet.tasks.format-test
    kmet.tasks.test-lint])

(defn- try-require
  "Require NS-SYM; returns nil on success, the throwable on failure."
  [ns-sym]
  (try (require ns-sym) nil
       (catch Throwable e e)))

(defn- load-failure-message
  "One-line reason NS-SYM failed to load (deepest cause message first)."
  [e]
  (loop [e e]
    (if-let [c (.getCause e)]
      (recur c)
      (or (.getMessage e) (str e)))))

(defn- ns-vars-of
  "Load NS-SYM and return {:vars [test vars]} on success, or
   {:vars [] :unloaded [NS-SYM message]} when it cannot load."
  [ns-sym]
  (if-let [e (try-require ns-sym)]
    {:vars [] :unloaded [ns-sym (load-failure-message e)]}
    {:vars (vals (ns-interns ns-sym))}))

(def jolt?
  "True when running under the jolt host (its clojure.test port differs from
   babashka's: no ref-based per-run counters, no ^:slow split, its own
   process-wide counters and per-namespace fixtures/registry)."
  (boolean (find-var 'clojure.core/*jolt-version*)))

(defn- test-var? [v slow?]
  (and (:test (meta v))
       ;; ^:bb-only vars run only under bb — the jolt host filters them out
       ;; of whole-namespace selection (var filters ignore bb-only, like slow?)
       (or (not jolt?) (not (:bb-only (meta v))))
       (if slow? (:slow (meta v)) (not (:slow (meta v))))))

(defn- var-matches-filter?
  "True when a test var matches any filter (plain name or ns/var)."
  [v filters]
  (let [vn (name (:name (meta v)))
        ns-full (str (:ns (meta v)))]
    (some #(or (= % vn)
               (= % (str ns-full "/" vn)))
          filters)))

(defn- split-filters
  "Split FILTERS into {:named [ns/var ...] :nss [ns ...] :plain [var ...]}.
   A bare filter naming a known test namespace (or containing a dot) is a
   whole-namespace request; anything else bare is a var name. An ns/var
   filter with an empty var part counts as a whole-namespace request."
  [filters]
  (let [known (set all-namespaces)]
    (reduce (fn [acc f]
              (let [f (str f)]
                (if-let [slash (str/index-of f "/")]
                  (let [var-part (subs f (inc slash))]
                    (if (seq var-part)
                      (update acc :named conj f)
                      (update acc :nss conj (subs f 0 slash))))
                  (if (or (contains? known (symbol f)) (str/includes? f "."))
                    (update acc :nss conj f)
                    (update acc :plain conj f)))))
            {:named [] :nss [] :plain []}
            filters)))

(defn- select-requested-nss
  "Load NSS whole-namespace requests, selecting vars by SLOW?."
  [nss slow?]
  (reduce (fn [acc ns-str]
            (let [ns-sym (symbol ns-str)
                  {vars :vars unloaded :unloaded} (ns-vars-of ns-sym)]
              (cond-> acc
                unloaded (update :unloaded conj unloaded)
                :always (update :vars into (filter #(test-var? % slow?)) vars))))
          {:vars [] :unloaded []}
          nss))

(defn- select-plain-filters
  "Scan namespaces in order for plain var-name FILTERS, stopping once every
   unmatched filter has answered. UNMATCHED is the set of plain names still
   seeking their first match; plain names matched by whole-namespace requests
   are already satisfied."
  [filters unmatched]
  (loop [nss all-namespaces
         remaining unmatched
         acc {:vars [] :unloaded []}]
    (if (or (nil? (first nss)) (empty? remaining))
      acc
      (let [ns-sym (first nss)
            {vars :vars unloaded :unloaded} (ns-vars-of ns-sym)
            matched (keep #(when (and (contains? remaining (name (:name (meta %))))
                                      (var-matches-filter? % filters))
                             (name (:name (meta %))))
                          vars)]
        (recur (rest nss)
               (apply disj remaining matched)
               (cond-> acc
                 unloaded (update :unloaded conj unloaded)
                 :always (update :vars into (filter #(var-matches-filter? % filters)) vars)))))))

(defn- select-named-filters
  "Load exactly the namespaces referenced by ns/var NAMED filters and return
   the matching vars. Like plain var filters, ns/var filters ignore slow?."
  [named]
  (reduce (fn [acc filter-str]
            (let [slash (str/index-of filter-str "/")
                  ns-sym (symbol (subs filter-str 0 slash))
                  var-name (subs filter-str (inc slash))
                  {vars :vars unloaded :unloaded} (ns-vars-of ns-sym)]
              (cond-> acc
                unloaded (update :unloaded conj unloaded)
                :always (update :vars into
                                (filter #(and (:test (meta %))
                                              (= var-name (name (:name (meta %))))))
                                vars))))
          {:vars [] :unloaded []}
          named))

(defn- select-vars
  "Load every namespace (or, with FILTERS, the ones needed to answer them)
   and return {:vars [matching test vars] :unloaded [[ns message] ...]}.
   Unloadable namespaces are reported, never fatal. With no FILTERS the
   slow? flag selects ^:slow vs non-slow vars. Whole-namespace filters
   (a known test ns, a dotted ns name, or ns/ with an empty var part)
   select by slow?; var filters (plain names or ns/var) ignore slow?.
   The :unloaded list holds only explicitly requested namespaces (whole
   ns requests and ns/var filters) — namespaces scanned incidentally for
   plain var names are never reported. :filters echoes the raw FILTERS."
  ([slow?] (select-vars slow? nil))
  ([slow? filters]
   (if-not (seq filters)
     (reduce (fn [acc ns-sym]
               (let [{vars :vars unloaded :unloaded} (ns-vars-of ns-sym)]
                 (cond-> acc
                   unloaded (update :unloaded conj unloaded)
                   :always (update :vars into (filter #(test-var? % slow?)) vars))))
             {:vars [] :unloaded []}
             all-namespaces)
     (let [strs (map str filters)
           {:keys [named nss plain]} (split-filters strs)
           ns-sel (when (seq nss) (select-requested-nss nss slow?))
           satisfied (into #{} (map (fn [v] (name (:name (meta v)))) (:vars ns-sel)))
           still-plain (remove satisfied plain)
           plain-sel (when (seq still-plain) (select-plain-filters strs (set still-plain)))
           named-sel (when (seq named) (select-named-filters named))
           vars (vec (distinct (concat (:vars ns-sel) (:vars plain-sel) (:vars named-sel))))
           unloaded (vec (distinct (concat (:unloaded ns-sel) (:unloaded named-sel))))]
       {:vars vars :unloaded unloaded :filters (vec strs)}))))

(defn- plural
  "N + label, singular for 1."
  [n singular plural]
  (str n " " (if (= 1 n) singular plural)))

(defn- fmt-summary
  "One-line test summary with zero counts omitted."
  [n-test n-pass n-fail n-error]
  (str/join ", "
            (cond-> [(plural n-test "test" "tests")]
              (pos? (+ n-pass n-fail n-error))
              (conj (plural (+ n-pass n-fail n-error) "assertion" "assertions"))
              (pos? n-fail) (conj (plural n-fail "failure" "failures"))
              (pos? n-error) (conj (plural n-error "error" "errors")))))

(defn- fmt-duration
  "Format elapsed milliseconds as a compact duration (ms or s)."
  [ms]
  (if (< ms 1000)
    (str (long ms) " ms")
    (format "%.1f s" (/ ms 1000.0))))

(defn- capture-streams!
  "Point System/out and System/err at fresh byte streams; returns the
   saved originals and the writers to replay later."
  []
  (let [out-baos (java.io.ByteArrayOutputStream.)
        err-baos (java.io.ByteArrayOutputStream.)
        out-writer (java.io.OutputStreamWriter. out-baos "UTF-8")
        err-writer (java.io.OutputStreamWriter. err-baos "UTF-8")
        saved-out System/out
        saved-err System/err]
    (System/setOut (java.io.PrintStream. out-baos true "UTF-8"))
    (System/setErr (java.io.PrintStream. err-baos true "UTF-8"))
    {:saved-out saved-out :saved-err saved-err
     :out-writer out-writer :err-writer err-writer
     :out-baos out-baos :err-baos err-baos}))

(defn- restore-streams!
  "Restore System/out/err saved by capture-streams!."
  [{:keys [saved-out saved-err]}]
  (System/setOut saved-out)
  (System/setErr saved-err))

(defn- test-var-with-capture
  "Run one test var with stdout/stderr captured: replay the captured
   output when the var fails (so diagnostics from the code under test stay
   visible next to the failure report), discard it on success."
  [v]
  (let [{:keys [out-writer err-writer out-baos err-baos] :as streams}
        (capture-streams!)
        counters-before @t/*report-counters*]
    (try
      (binding [*out* out-writer
                *err* err-writer
                t/*test-out* out-writer]
        (t/test-var v))
      (let [counters-after @t/*report-counters*
            failed? (or (pos? (- (:fail counters-after) (:fail counters-before)))
                        (pos? (- (:error counters-after) (:error counters-before))))
            out (String. (.toByteArray out-baos) "UTF-8")
            err (String. (.toByteArray err-baos) "UTF-8")]
        (when failed?
          (when (seq out) (print out))
          (when (seq err) (binding [*out* *err*] (print err))))
        (flush))
      (finally
        (restore-streams! streams)))))

(defn- run-ns-vars
  "Run the selected vars of one namespace, applying its :once fixtures
   around the namespace and :each fixtures around every var, like
   clojure.test/test-ns. Prints a summary line with elapsed time.
   Each var runs with stdout/stderr captured (see test-var-with-capture).
   (bb's clojure.test/test-vars silently drops vars — we drive test-var
   directly, and jolt's port shares the shape.)"
  [ns-sym vars]
  (let [ns-obj (find-ns ns-sym)
        before @t/*report-counters*
        start-ms (System/currentTimeMillis)
        once-fx (t/join-fixtures (:clojure.test/once-fixtures (meta ns-obj)))
        each-fx (t/join-fixtures (:clojure.test/each-fixtures (meta ns-obj)))]
    (println "\nTesting" (ns-name ns-obj))
    (binding [*ns* ns-obj]
      (once-fx
       (fn []
         (doseq [v vars]
           (each-fx (fn [] (test-var-with-capture v)))))))
    (let [after @t/*report-counters*
          n-test (- (:test after) (:test before))
          n-pass (- (:pass after) (:pass before))
          n-fail (- (:fail after) (:fail before))
          n-error (- (:error after) (:error before))
          elapsed-ms (- (System/currentTimeMillis) start-ms)]
      (println (str "  " (fmt-summary n-test n-pass n-fail n-error)
                    " (" (fmt-duration elapsed-ms) ")")))))

(def ^:private jolt-ns-timeout-ms
  "Per-namespace cap for the Jolt engine. Jolt runs are slower and burstier
   than babashka's (a loaded device has pushed real namespaces past 20 s), so
   the cap absorbs the real work while still reporting and moving past a hung
   namespace."
  60000)

(defn- run-ns-vars-jolt
  "Run the selected vars of one namespace on jolt. Jolt's clojure.test port
   has no per-var ref counters and no host output capture; clojure.test/
   test-vars applies the ns's :once/:each fixtures and test-var through
   jolt's own process-wide counters atom. Prints the same per-namespace
   header/summary as the bb engine.

   Jolt-specific: test-vars runs on a future with a per-namespace timeout.
   A namespace whose test infrastructure hangs on Jolt (JDK classes with
   missing methods — e.g. java.net.Socket's OutputStream only has write(int),
   not write(byte[]); or java.io.DataInputStream lacking a ctor — crash the
   helper thread and leave curl/threads blocked forever) would otherwise
   stall the whole run. The timeout lets the runner report and move on;
   future-cancel interrupts the worker thread."
  [ns-sym vars]
  (let [counters (var-get (requiring-resolve (quote clojure.test/counters)))
        before @counters
        start-ms (System/currentTimeMillis)
        ;; future + deref-with-timeout so a hung namespace can't block forever.
        ;; deref returns the future's value (::ok or a Throwable) or ::timeout.
        f (future
            (try
              (t/test-vars vars)
              ::ok
              (catch Throwable e e)))
        deref-result (deref f jolt-ns-timeout-ms ::timeout)]
    (when (= ::timeout deref-result)
      (future-cancel f))
    (println "\nTesting" (ns-name (find-ns ns-sym)))
    (let [after @counters
          n-test (- (:test after) (:test before))
          n-pass (- (:pass after) (:pass before))
          n-fail (- (:fail after) (:fail before))
          n-error (- (:error after) (:error before))
          elapsed-ms (- (System/currentTimeMillis) start-ms)]
      (cond
        (= ::timeout deref-result)
        (println (str "  TIMED OUT after " (fmt-duration jolt-ns-timeout-ms)
                      " — test infrastructure hung (likely a JDK class gap on Jolt)"))
        (instance? Throwable deref-result)
        (println (str "  ERROR: " (.getMessage deref-result)))
        :else
        (println (str "  " (fmt-summary n-test n-pass n-fail n-error)
                      " (" (fmt-duration elapsed-ms) ")"))))))

(defn- run-selected
  "Run selected test vars, grouped by namespace so fixtures apply per ns.
   Engine-specific: bb runs each var with output capture and per-run ref
   counters (see run-ns-vars); jolt's port counts into its own process-wide
   `counters` atom and has no host output capture (see run-ns-vars-jolt).
   Returns the aggregate {:test :pass :fail :error} map."
  [vars]
  (if jolt?
    ;; jolt's counters atom is process-wide and cumulative — the aggregate
    ;; is the delta across the whole run (per-ns deltas come from
    ;; run-ns-vars-jolt's own before/after reads).
    (let [counters (var-get (requiring-resolve (quote clojure.test/counters)))
          before @counters]
      (doseq [[ns-sym ns-vars] (sort-by key (group-by (comp ns-name :ns meta) vars))]
        (run-ns-vars-jolt ns-sym ns-vars))
      (let [after @counters]
        {:test (- (:test after) (:test before))
         :pass (- (:pass after) (:pass before))
         :fail (- (:fail after) (:fail before))
         :error (- (:error after) (:error before))}))
    (binding [t/*report-counters* (ref t/*initial-report-counters*)]
      (doseq [[ns-sym ns-vars] (sort-by key (group-by (comp ns-name :ns meta) vars))]
        (run-ns-vars ns-sym ns-vars))
      @t/*report-counters*)))

(defn- report-unloaded
  "Print the unloadable-namespace list, prefixed by HEADER when given."
  ([unloaded] (report-unloaded unloaded "Namespaces that could not load (skipped):"))
  ([unloaded header]
   (when (seq unloaded)
     (println (str "\n" header))
     (doseq [[ns-sym msg] unloaded]
       (println "  " ns-sym " — " msg)))))

(defn- report-no-match
  "Print why a filtered run matched no test vars. UNLOADED holds the
   requested namespaces that failed to load (nil when all requested
   namespaces loaded); FILTERS are the raw filter strings."
  [unloaded filters]
  (println (str "\nNo test vars matched: " (str/join " " (map str filters)) "."))
  (if (seq unloaded)
    (report-unloaded unloaded "Requested namespace(s) failed to load (no tests could run):")
    (println "No requested namespaces failed to load — no test vars matched the filter and the slow?/bb-only selection.")))

(defn- run-and-summarize
  "Run the selected test vars, print the summary, exit with status 0/1.
   SELECTION carries {:vars :unloaded :filters}; :unloaded holds the
   requested namespaces that failed to load (full run: every unloadable
   namespace). The skip report prints on a full run and on a filtered run
   that matched vars; a filtered run that matched no vars names the filters
   instead and, when a requested namespace failed to load, surfaces its reason.
   When MARK-VALIDATED? and everything passed, records
   the changed-files baseline (kmet.tasks.changed — bb only)."
  [{:keys [vars unloaded filters]} mark-validated?]
  (let [start-ms (System/currentTimeMillis)
        models-var (try (requiring-resolve 'kmet.ai.models/*use-models-cache*)
                        (catch Throwable _ nil))
        results (if (and (not jolt?) models-var)
                  (with-bindings {models-var false} (run-selected vars))
                  (run-selected vars))
        n-tests (:test results)
        n-assertions (+ (:pass results) (:fail results) (:error results))
        total-ms (- (System/currentTimeMillis) start-ms)
        fails (:fail results)
        errs (:error results)]
    (when (or (empty? filters) (seq vars))
      (report-unloaded unloaded))
    (when (and (seq filters) (empty? vars))
      (report-no-match unloaded filters))
    (println (str "\nRan " n-tests " tests containing " n-assertions " assertions in "
                  (fmt-duration total-ms) "."))
    (when (pos? (+ fails errs))
      (println (str (str/join ", " [(plural fails "failure" "failures")
                                    (plural errs "error" "errors")]) ".")))
    (println "Results:" (str/join ", "
                                  (cond-> [(str (:pass results) " passed")]
                                    (pos? fails) (conj (str fails " failed"))
                                    (pos? errs) (conj (plural errs "error" "errors")))))
    (when (and (not jolt?) mark-validated? (zero? (+ fails errs)))
      (try ((requiring-resolve 'kmet.tasks.changed/mark-validated!))
           (catch Throwable e
             (.println System/err
                       (str "warning: could not update changed-files baseline: "
                            (.getMessage e))))))
    (System/exit (if (pos? (+ fails errs)) 1 0))))

(defn -main
  "Run the test suites.
   slow? selects ^:slow vs non-slow vars (`bb test` false, `bb test-ext`
   true; `jolt test` false, `jolt test-ext` true — the same split works on
   both hosts because ^:slow var metadata is preserved under jolt).
   Remaining args are filters: plain test var names (`bb test test-tool-bash`),
   ns/var pairs (`bb test-ext kmet.app.test-loop/my-test`), or whole
   namespaces (`bb test kmet.tui.test-fuzzy`, or ns/ with a trailing slash).
   Whole-namespace filters select by slow?; var filters ignore slow?.
   The skip report lists unloadable namespaces (full run: all of them;
   filtered run: only the requested ones) with the load failure reason.
   A full run without filters records the changed-files baseline after a
   green result, so `bb test-changed` sees a clean slate.
   ^:bb-only vars (bb-only behavior — kmet.tasks.build,
   bb-bundled SCI ports, Maven deps, the packed-clojure closure, curl --compressed) run
   under bb and are dropped from whole-namespace selection on jolt; var
   filters ignore bb-only too, so an explicit request runs and reports the
   underlying ::bb-only error."
  [slow? & filters]
  (let [selection (select-vars slow? (seq filters))]
    (run-and-summarize selection (empty? filters))))

(defn run-ns-syms
  "Run the test vars of NS-SYMS matching SLOW? (true = ^:slow only, false =
   non-slow only), for `bb test-ext-changed` / `bb test-changed`."
  [ns-syms slow?]
  (let [{vars :vars unloaded :unloaded}
        (reduce (fn [acc ns-sym]
                  (let [{vars :vars unloaded :unloaded} (ns-vars-of ns-sym)]
                    (cond-> acc
                      unloaded (update :unloaded conj unloaded)
                      :always (update :vars into (filter #(test-var? % slow?)) vars))))
                {:vars [] :unloaded []}
                ns-syms)]
    (run-and-summarize {:vars vars :unloaded unloaded} false)))

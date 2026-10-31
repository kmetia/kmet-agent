(ns kmet.tasks.runner
  "Test runner for both hosts (babashka and jolt).

   Slow tests are marked with ^:slow on the deftest (tests that wait real
   wall-clock time: sleeps, terminal-query timeouts; real network calls; and
   subprocess spawns — bash tool, shell commands, git).
   `bb test` runs all tests except ^:slow ones; `bb test-ext` runs only the
   ^:slow tests; `bb test-jvm` / `bb test-ext-jvm` run the same two selections
   under JVM Clojure. All are selected at the individual test level — no whole
   namespaces are excluded.

   A deftest marked ^:bb-only exercises bb-only behavior (kmet.tasks.build's
   packaging, bb-bundled SCI ports, Maven deps resolution, the packed-clojure
   extension closure, curl --compressed — babashka.classpath and the bundled
   ports exist on babashka only): it runs under `bb test` and is skipped on
   the jolt and JVM hosts. The namespace still loads there (a load gap would
   report it unloaded), so calling a bb-only entry point off babashka surfaces
   as a fast ::bb-only ex-info from the guarded function, not a crash.

   The runner is TOLERANT per namespace: every test namespace is required
   inside a try. A namespace that cannot load under the host (a babashka-internal
   require, a JDK class gap, a java.time.* gap) is reported and skipped,
   and the remaining namespaces still run — but the
   run FAILS (exit 1): a skipped namespace means its tests never ran, and a
   green result would silently shrink the gate. This is what lets the same
   runner serve `bb test`, `jolt test` and the JVM tasks while the non-bb
   ports are staged. On a full run every unloadable namespace is listed with
   its load failure reason; on a filtered run the list holds only the
   requested namespaces that failed to load.

   Filters select tests: a plain var name (e.g. `test-tool-bash`), an ns/var
   pair (e.g. `kmet.app.loop.test-turn/my-test`), or a whole namespace
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
            [clojure.test :as t]
            [kmet.libs.host :as host]
            [kmet.tasks.modules :as modules]))

(def jolt?
  "True when running under the jolt host (its clojure.test port differs from
   babashka's: no ref-based per-run counters, no ^:slow split, its own
   process-wide counters and per-namespace fixtures/registry). The JVM host
   has Clojure's own clojure.test and shares babashka's engine branch."
  (host/jolt?))

(def babashka?
  "True on the babashka host — the only host whose runtime bundles
   babashka.classpath, the bb SCI ports and the Maven deps resolver the
   ^:bb-only tests exercise. Those vars are skipped on jolt and on the JVM."
  (host/babashka?))

(def all-namespaces
  "Every test namespace, derived from the tree (kmet.tasks.modules/
   test-namespaces) — there is no hand-maintained registry to drift. The
   slow/fast split happens per test var via ^:slow metadata, so no namespace
   is excluded from either run."
  (modules/test-namespaces))

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

(defn- test-var? [v slow?]
  (and (:test (meta v))
       ;; ^:bb-only vars run only under bb — jolt and the JVM host filter them
       ;; out of whole-namespace selection (var filters ignore bb-only, like
       ;; slow?)
       (or babashka? (not (:bb-only (meta v))))
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
  "Format elapsed MICROSECONDS as a compact duration (µs, ms or s)."
  [us]
  (cond
    (< us 1000) (str (long us) " µs")
    (< us 1000000) (let [ms (/ us 1000.0)]
                     (if (< ms 10)
                       (format "%.1f ms" ms)
                       (str (long ms) " ms")))
    :else (format "%.1f s" (/ us 1000000.0))))

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
        start-ns (System/nanoTime)
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
          elapsed-us (quot (- (System/nanoTime) start-ns) 1000)]
      (println (str "  " (fmt-summary n-test n-pass n-fail n-error)
                    " (" (fmt-duration elapsed-us) ")")))))

(def ^:private jolt-ns-timeout-ms
  "Per-namespace cap for the Jolt engine. Jolt runs are slower and burstier
   than babashka's — the subprocess suites (mcp-adapter's client and e2e
   suites spawn a fixture per case) legitimately run past 50 s here, and a
   loaded device stretches them further — so the cap absorbs the real work
   while still reporting and moving past a hung namespace."
  180000)

(defn- run-ns-vars-jolt
  "Run the selected vars of one namespace on jolt. Jolt's clojure.test port
   has no per-var ref counters and no host output capture; clojure.test/
   test-vars applies the ns's :once/:each fixtures and test-var through
   jolt's own process-wide counters atom. Prints the same per-namespace
   header/summary as the bb engine — header first, so the namespace's own
   (uncaptured here) output reads under its header.

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
        start-ns (System/nanoTime)
        _ (println "\nTesting" (ns-name (find-ns ns-sym)))
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
    (let [after @counters
          n-test (- (:test after) (:test before))
          n-pass (- (:pass after) (:pass before))
          n-fail (- (:fail after) (:fail before))
          n-error (- (:error after) (:error before))
          elapsed-us (quot (- (System/nanoTime) start-ns) 1000)]
      (cond
        (= ::timeout deref-result)
        (println (str "  TIMED OUT after " (fmt-duration (* 1000 jolt-ns-timeout-ms))
                      " — test infrastructure hung (likely a JDK class gap on Jolt)"))
        (instance? Throwable deref-result)
        (println (str "  ERROR: " (.getMessage deref-result)))
        :else
        (println (str "  " (fmt-summary n-test n-pass n-fail n-error)
                      " (" (fmt-duration elapsed-us) ")"))))))

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
  ([unloaded] (report-unloaded unloaded "Namespaces that could not load (skipped — the run fails):"))
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

(defn- exit!
  "Exit the process with CODE. A seam: the runner tests redefine it to observe
   the status instead of killing the test process."
  [code]
  (System/exit code))

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
  (let [start-ns (System/nanoTime)
        cache-vars (into {}
                         (for [sym '[kmet.ai.models/*use-models-cache*
                                     kmet.ai.image-models/*use-image-models-cache*]
                               :let [v (try (requiring-resolve sym)
                                            (catch Throwable _ nil))]
                               :when v]
                           [v false]))
        results (if (and (not jolt?) (seq cache-vars))
                  (with-bindings cache-vars (run-selected vars))
                  (run-selected vars))
        n-tests (:test results)
        n-assertions (+ (:pass results) (:fail results) (:error results))
        total-us (quot (- (System/nanoTime) start-ns) 1000)
        fails (:fail results)
        errs (:error results)
        unloaded-count (count unloaded)]
    (when (or (empty? filters) (seq vars))
      (report-unloaded unloaded))
    (when (and (seq filters) (empty? vars))
      (report-no-match unloaded filters))
    (println (str "\nRan " n-tests " tests containing " n-assertions " assertions in "
                  (fmt-duration total-us) "."))
    (when (pos? (+ fails errs))
      (println (str (str/join ", " [(plural fails "failure" "failures")
                                    (plural errs "error" "errors")]) ".")))
    (println "Results:" (str/join ", "
                                  (cond-> [(str (:pass results) " passed")]
                                    (pos? fails) (conj (str fails " failed"))
                                    (pos? errs) (conj (plural errs "error" "errors"))
                                    (pos? unloaded-count)
                                    (conj (str (plural unloaded-count "namespace" "namespaces")
                                               " failed to load")))))
    (when (and (not jolt?) mark-validated? (zero? (+ fails errs)) (empty? unloaded))
      (try ((requiring-resolve 'kmet.tasks.changed/mark-validated!))
           (catch Throwable e
             (.println System/err
                       (str "warning: could not update changed-files baseline: "
                            (.getMessage e))))))
    ;; A namespace that could not load fails the run: its tests never ran, so
    ;; exit 0 would silently shrink the gate (a missing require once dropped a
    ;; whole namespace from an otherwise green run).
    (exit! (if (or (pos? (+ fails errs)) (pos? unloaded-count)) 1 0))))

(defn- slow-flag
  "SLOW? as a boolean: the bb tasks pass the value (`bb test` false,
   `bb test-ext` true), a JVM command line passes the string
   \"false\"/\"true\"."
  [v]
  (cond
    (boolean? v) v
    (string? v) (= "true" (str/lower-case v))
    :else (boolean v)))

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

(defn- isolate-diagnostics!
  "Point the app's diagnostics logs at target/test-logs for this run: a test
   that reports an unhandled error must not append to the checkout's `logs/`.
   `kmet.app.debug` reads the system property before its own defaults, so no
   app namespace has to load here. A host that cannot set a property keeps
   the default."
  []
  (try
    (System/setProperty "kmet.log.dir" "target/test-logs")
    (catch Throwable _ nil)))

(defn run-changed!
  "Run the test namespaces the changed files affect (kmet.tasks.changed), with
   SLOW? selecting ^:slow vs non-slow vars — the shared body of
   `test-changed` / `test-ext-changed` on every host (the JVM twins included;
   `bb.edn` calls this, and `-main`'s `--changed` flag).
   Prints the nothing-to-run line and exits 0 when no test namespace is
   affected."
  [slow?]
  (isolate-diagnostics!)
  (let [nss ((requiring-resolve 'kmet.tasks.changed/affected-test-nss))]
    (if (seq nss)
      (run-ns-syms nss slow?)
      (println "No affected test namespaces — nothing to run."))))

(defn -main
  "Run the test suites.
   slow? selects ^:slow vs non-slow vars (`bb test` false, `bb test-ext`
   true; `jolt test` false, `jolt test-ext` true; the JVM tasks pass the same
   values as strings — see slow-flag). The split works on all three hosts
   because ^:slow var metadata survives jolt.
   `--changed` as the first remaining arg selects the test namespaces the
   changed files affect instead of the full tree (`bb test-changed`; the JVM
   task `test-changed-jvm` is `-m kmet.tasks.runner false --changed`).
   Remaining args are filters: plain test var names (`bb test
   test-tool-bash`), ns/var pairs (`bb test-ext kmet.app.loop.test-turn/my-test`),
   or whole namespaces (`bb test kmet.libs.test-fuzzy`, or ns/ with a trailing
   slash). Whole-namespace filters select by slow?; var filters ignore slow?.
   The skip report lists unloadable namespaces (full run: all of them;
   filtered run: only the requested ones) with the load failure reason.
   A full run without filters records the changed-files baseline after a
   green result, so `bb test-changed` sees a clean slate.
   ^:bb-only vars (bb-only behavior — kmet.tasks.build,
   bb-bundled SCI ports, Maven deps, the packed-clojure closure, curl --compressed)
   run under bb only: whole-namespace selection drops them on jolt and on the
   JVM; var filters ignore bb-only too, so an explicit request runs and
   reports the underlying ::bb-only error."
  [slow? & args]
  (isolate-diagnostics!)
  (let [slow? (slow-flag slow?)
        args (vec args)]
    (if (= "--changed" (first args))
      (run-changed! slow?)
      (run-and-summarize (select-vars slow? (seq args)) (empty? args)))))

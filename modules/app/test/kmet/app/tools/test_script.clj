(ns kmet.app.tools.test-script
  "The shared script layer's kept-context seams: a definition survives a call,
   `seed!` replaces the tools namespace's key set per call, a timeout
   leaves the next call clean, and cancellation ends a host-blocked call."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.script :as script]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.libs.process :as kprocess]
            [kmet.test-http-server :as http-server]
            [kmet.test-tmp :as test-tmp]))

(defn- tool-def [name]
  {:name name
   :description (str name " test tool")
   :parameters {:type "object" :properties {}}
   :execute (fn [_] {:content name})})

(defn- kept-context []
  (let [holder (script/interrupt-holder)
        ctx (script/context (script/base-namespaces)
              (script/interrupt-fn holder "eval")
              "eval")]
    {:ctx ctx :holder holder}))

(defn- run-opts
  "The script tool's seams over TOOLS, the only callable tools."
  [tools code]
  {:tool "eval"
   :code code
   :get-all-tools (constantly tools)
   :get-contributed-tools (constantly {})
   :select-tools (fn [all _] all)
   :execute-tool (fn [& _] {:content "not dispatched"})})

(defn- run-in
  ([s tools code] (run-in s tools code nil))
  ([{:keys [ctx holder]} tools code extra]
   (script/run (merge (run-opts tools code)
                      {:sci-ctx ctx
                       :interrupt holder}
                      extra))))

(t/use-fixtures :each (fn [f] (http/set-transport! :platform) (f)))

(t/deftest test-a-kept-context-keeps-a-definition
  (let [s (kept-context)]
    (t/is (= "42" (:content (run-in s {} "(do (def kept 42) kept)"))))
    (t/is (= "43" (:content (run-in s {} "(+ kept 1)"))))))

(t/deftest test-the-context-resolves-host-classes
  (let [{:keys [ctx]} (kept-context)]
    (t/is (= {:value :caught}
             (script/eval-source! ctx
                                  "(try (throw (ex-info \"boom\" {})) (catch Throwable e :caught))"
                                  (atom 'user))))
    (t/is (= {:value 1}
             (script/eval-source! ctx
                                  "(with-open [r (clojure.java.io/reader (java.io.StringReader. \"ab\"))] (count (line-seq r)))"
                                  (atom 'user))))))

(t/deftest test-seed-replaces-the-tools-namespace
  (let [s (kept-context)]
    (t/is (= "true"
             (:content (run-in s {"a" (tool-def "a")}
                               "(core/emit (contains? (ns-publics 'tools) 'a))"))))
    (t/is (= "[false true true]"
             (:content (run-in s {"b" (tool-def "b")}
                               (str "(core/emit [(contains? (ns-publics 'tools) 'a)"
                                    " (contains? (ns-publics 'tools) 'b)"
                                    " (contains? (ns-publics 'tools) 'call)])")))))))

(t/deftest test-a-run-needs-a-tool-name
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/prepare {})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/run {:code "1"}))))

(t/deftest test-builtin-verb-help-is-generated-from-var-metadata
  (let [help script/builtin-verb-help
        core-names (set (map #(str "core/" %) script/core-refer-verbs))]
    (t/is (= (set (filter #(str/starts-with? % "core/") (keys help))) core-names))
    (t/is (= #{"clj/edit-form" "clj/replace-sexp" "clj/paren-repair"}
             (set (filter #(str/starts-with? % "clj/") (keys help)))))
    (doseq [[name {:keys [doc arglists]}] help]
      (t/is (string? doc) (str name " has a docstring"))
      (t/is (seq arglists) (str name " has arglists")))
    (t/is (str/includes? (pr-str (:arglists (get help "core/read"))) "offset"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/read"))) "as"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/read"))) "start"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/read"))) "end"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/bash"))) "timeout"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/glob"))) "gitignore?"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/glob"))) "stat"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/grep"))) "max-file-bytes"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/grep"))) "context"))
    (t/is (contains? help "core/fetch"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/fetch"))) "follow-redirects"))
    (t/is (str/includes? (pr-str (:arglists (get help "core/session-recall"))) "scope"))
    (t/is (str/includes? (pr-str (:arglists (get help "clj/edit-form"))) "file_path"))
    (t/is (str/includes? (pr-str (:arglists (get help "clj/replace-sexp"))) "match_form"))
    (t/is (str/includes? (pr-str (:arglists (get help "clj/paren-repair"))) "format"))))

(t/deftest test-strings-side-channel
  (let [s (kept-context)
        path (str "target/test-strings-" (random-uuid) ".py")
        content "pattern = r\"\\s+\"\n"]
    (try
      (let [result (run-in s {}
                           (str "(core/write " (pr-str path) " ($ \"file\"))")
                           {:strings {"file" content}})]
        (t/is (not (:is-error result)) (:content result))
        (t/is (= content (slurp path)))
        (t/is (= "exact" (:content (run-in s {} "(core/emit (core/$ \"text\"))" {:strings {"text" "exact"}})))))
      (let [definition (run-in s {} "(defn put-it [p] (core/write p ($ \"file\")))")]
        (t/is (not (:is-error definition)) (:content definition)))
      (let [result (run-in s {} (str "(put-it " (pr-str path) ")") {:strings {"file" "kept\n"}})]
        (t/is (not (:is-error result)) (:content result))
        (t/is (= "kept\n" (slurp path))))
      (let [result (run-in s {} "(core/bash ($ \"script\"))" {:strings {"script" "printf 'a\\nb\\n'"}})]
        (t/is (not (:is-error result)) (:content result))
        (t/is (str/includes? (:content result) "a\nb")))
      (let [result (run-in s {} "(core/emit ($ \"missing\"))"
                           {:strings {"present" "x"}})]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "no strings entry named")))
      (let [result (run-in s {} "($ \"x\")")]
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "carried no strings")))
      (let [error (try (run-in s {} "($ \"n\")" {:strings {"n" 1}})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (instance? clojure.lang.ExceptionInfo error))
        (t/is (str/includes? (ex-message error) "must be a string")))
      (finally (fs/delete-if-exists path)))))

(t/deftest test-json-keywordized-strings-keep-their-exact-names
  (let [s (kept-context)
        name "dir/name with space:1"]
    (t/is (= "restored"
             (:content (run-in s {}
                               (str "(core/emit ($ " (pr-str name) "))")
                               {:strings {(keyword name) "restored"}}))))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"strings name must be a string"
                            (run-in s {} "1" {:strings {1 "x"}})))))

(t/deftest test-core-grep-argument-error-is-actionable
  (let [s (kept-context)
        result (run-in s {} "(core/grep {:path \".\" :pattern \"needle\"})")]
    (t/is (:is-error result))
    (t/is (str/includes? (:content result) "PATTERN first"))
    (t/is (str/includes? (:content result) ":pattern"))))

(t/deftest test-core-grep-and-glob-use-the-file-search-implementation
  (let [s (kept-context)
        dir (str (fs/absolutize (test-tmp/temp-dir! "test-core-search-")))]
    (fs/create-dirs (fs/path dir "src"))
    (spit (str (fs/path dir "src/a.clj")) "(def needle 1)\n")
    (spit (str (fs/path dir "src/b.txt")) "needle\n")
    (let [globbed (:content (run-in s {} (str "(core/emit (core/glob \"**/*.clj\" {:path "
                                              (pr-str dir) "}))")))
          grepped (:content (run-in s {} (str "(core/emit (core/grep \"needle\" {:path "
                                              (pr-str dir) " :glob \"**/*.clj\"}))")))]
      (t/is (str/includes? globbed "src/a.clj"))
      (t/is (str/includes? grepped "src/a.clj"))
      (t/is (not (str/includes? grepped "src/b.txt"))))))

(defn- write-cancel-fixture!
  "Write a parent/child fixture in DIR whose child writes MARKER after a few
   seconds while the parent blocks; the parent writes STARTED once the child
   is running, so the caller fires its signal against a live tree. Return the
   argv that starts the parent. The script name is relative and the caller
   sets :dir, so a checkout path with spaces never reaches cmd's /c quoting.
   The child is a descendant, so a leftover marker means the tree survived,
   not just the direct child."
  [dir marker started]
  (if kprocess/windows-os?
    (let [child (str (fs/path dir "child.cmd"))
          parent (str (fs/path dir "parent.cmd"))]
      (spit child (str "@echo off\r\n"
                       "ping -n 4 127.0.0.1 >NUL\r\n"
                       "echo alive>\"" marker "\"\r\n"))
      (spit parent (str "@echo off\r\n"
                        "start /b \"\" \"" child "\"\r\n"
                        "echo ready>\"" started "\"\r\n"
                        "ping -n 30 127.0.0.1 >NUL\r\n"))
      ["cmd.exe" "/c" "parent.cmd"])
    (let [script (str (fs/path dir "cancel.sh"))]
      (spit script (str "#!/bin/sh\n"
                        "(sleep 3; echo alive > " (pr-str marker) ") &\n"
                        "echo ready > " (pr-str started) "\n"
                        "sleep 30\n"))
      ["sh" "cancel.sh"])))

(defn- shell-argv
  "The host shell's argv that runs COMMAND."
  [command]
  (if kprocess/windows-os?
    ["cmd.exe" "/c" command]
    ["sh" "-c" command]))

(defn- read-stdin-argv
  "The host shell's argv that copies one stdin line to stdout."
  []
  (if kprocess/windows-os?
    ["cmd.exe" "/v:on" "/c" "set /p x= & echo !x!"]
    ["sh" "-c" "read x; echo $x"]))

(defn- run-cancelled-process
  "Run WRAP's code with the fixture's argv — a parent that blocks while a
   child writes a marker after a few seconds. Wait for the fixture's child to
   be running, then fire the call's signal; a signal before the spawn would
   race the kill against a fork it never saw. Returns {:started? bool
   :returned? bool :marker-exists? bool}, the marker checked after the write
   would have landed."
  [wrap]
  (let [dir (test-tmp/temp-dir! "script-cancel-")
        marker (str (fs/path dir "marker"))
        started (str (fs/path dir "started"))
        argv (write-cancel-fixture! dir marker started)
        s (kept-context)
        signal (atom false)
        fut (future (script/run (assoc (run-opts {} (wrap argv dir))
                                       :timeout 10
                                       :signal signal
                                       :sci-ctx (:ctx s)
                                       :interrupt (:holder s))))
        deadline (+ (concurrent/monotonic-ms) 10000)]
    (loop []
      (when (and (not (fs/exists? started))
                 (< (concurrent/monotonic-ms) deadline))
        (Thread/sleep 10)
        (recur)))
    (let [started? (fs/exists? started)]
      (reset! signal true)
      (let [returned? (not= ::timeout (deref fut 5000 ::timeout))]
        (Thread/sleep 4000)
        {:started? started?
         :returned? returned?
         :marker-exists? (fs/exists? marker)}))))

(t/deftest ^:slow test-a-timeout-leaves-the-next-call-clean
  (let [s (kept-context)
        r (script/run (assoc (run-opts {} "(loop [] (recur))")
                             :timeout 0.1
                             :sci-ctx (:ctx s)
                             :interrupt (:holder s)))]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= "42" (:content (run-in s {} "(+ 40 2)"))))))

(t/deftest ^:slow test-cancellation-ends-a-host-blocked-call-with-a-deadline
  ;; Regression: the deadline branch only waited for the clock, so Escape
  ;; during a host-blocked call (SCI's interrupt cannot reach it) ran until
  ;; the model's timeout, with the tool box's Elapsed ticking the whole time.
  (let [s (kept-context)
        signal (atom false)
        started (concurrent/monotonic-ms)
        fut (future (script/run (assoc (run-opts {} "(core/sleep 5000)")
                                       :timeout 10
                                       :signal signal
                                       :sci-ctx (:ctx s)
                                       :interrupt (:holder s))))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref fut 7000 ::timeout)]
      (t/is (not= ::timeout r) "the call returns on Escape, not at the deadline")
      (t/is (< (- (concurrent/monotonic-ms) started) 4000)
            "abandoned after the grace, well before the 10s deadline")
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest test-a-finished-script-with-a-fired-signal-is-cancelled
  ;; A signal that lands as the script finishes must still report aborted:
  ;; abort-reason wins over the script's own success.
  (let [done (doto (promise) (deliver true))]
    (t/is (= :done (#'script/await-script done (atom false) nil)))
    (t/is (= :cancelled (#'script/await-script done (atom true) nil)))
    (t/is (= :done (#'script/await-script done (atom false) 0)))
    (t/is (= :timeout (#'script/await-script (promise) (atom false) 1)))))

(t/deftest test-rearm-keeps-the-run-signal-separate-from-the-abort-reason
  ;; A timeout abort must not be mistaken for Escape: the interrupt-fn checks
  ;; the raw run signal, so a deadline that lands between its checks cannot
  ;; relabel the timeout as an abort.
  (let [holder (script/interrupt-holder)
        signal (atom false)
        abort (atom nil)
        interrupt (script/interrupt-fn holder "eval")]
    (script/rearm! holder {:signal signal
                           :cancelled (concurrent/or-signal signal abort)
                           :deadline nil
                           :abort abort})
    (t/is (identical? signal (:signal @holder)))
    (reset! abort :timeout)
    (t/is (= :timeout (try (interrupt) false
                           (catch Exception e (:reason (ex-data e))))))
    (reset! abort nil)
    (reset! signal true)
    (t/is (= :aborted (try (interrupt) false
                           (catch Exception e (:reason (ex-data e))))))))

(t/deftest ^:slow test-cancellation-kills-a-p-sh-process
  (let [{:keys [started? returned? marker-exists?]}
        (run-cancelled-process
         (fn [argv dir]
           (str "(p/sh " (str/join " " (map pr-str argv))
                " :dir " (pr-str dir) ")")))]
    (t/is started? "the fixture's parent and child were running")
    (t/is returned? "the call returns on Escape")
    (t/is (not marker-exists?)
          "the spawned shell was killed, not left writing after the call")))

(t/deftest ^:slow test-cancellation-kills-a-bare-shell-process
  (let [{:keys [started? returned? marker-exists?]}
        (run-cancelled-process
         (fn [argv dir]
           (str "(p/shell {:out :string :err :string :dir " (pr-str dir)
                "} " (pr-str argv) ")")))]
    (t/is started? "the fixture's parent and child were running")
    (t/is returned? "the call returns on Escape")
    (t/is (not marker-exists?)
          "the spawned shell was killed, not left writing after the call")))

(t/deftest ^:slow test-shell-keeps-babashka-process-semantics
  (let [s (kept-context)
        run #(run-in s {} %)
        echo-argv (shell-argv "echo hi")
        exit-argv (shell-argv "exit 3")
        echo-code (str "(core/emit (:out (p/shell {:out :string :err :string} "
                       (pr-str echo-argv) ")))")
        exit-code (str "(core/emit (try (p/shell {:out :string :err :string} "
                       (pr-str exit-argv)
                       ") :ok (catch Exception _ :exit-3)))")
        continue-code (str "(core/emit (:exit (p/shell {:out :string :err :string :continue true} "
                           (pr-str exit-argv) ")))")]
    (t/is (= "hi" (str/trim (:content (run echo-code)))))
    (t/is (= ":exit-3" (:content (run exit-code))))
    (t/is (= "3" (:content (run continue-code))))))

(t/deftest ^:slow test-cancellation-kills-a-background-process
  (let [{:keys [started? marker-exists?]}
        (run-cancelled-process
         (fn [argv dir]
           (str "(p/process " (pr-str argv) " {:dir " (pr-str dir) "})")))]
    (t/is started? "the fixture's parent and child were running")
    (t/is (not marker-exists?)
          "Escape kills a process spawned by an already-finished script")))

(t/deftest test-p-sh-accepts-trailing-keyword-options
  (let [s (kept-context)
        code (str "(core/emit (:out (p/sh "
                  (str/join " " (map pr-str (read-stdin-argv)))
                  " :in \"hi\\n\")))")]
    (t/is (= "hi" (str/trim (:content (run-in s {} code)))))))

(defn- run-with-dispatch
  "Run CODE with TOOLS dispatched through the bridge, so the bare tool wrappers
   settle a real tool result."
  [s tools code]
  (script/run (assoc (run-opts tools code)
                     :sci-ctx (:ctx s)
                     :interrupt (:holder s)
                     :execute-tool (fn [name args _]
                                     (if-let [tool (get tools name)]
                                       ((:execute tool) args)
                                       {:content (str "Unknown tool: " name)
                                        :is-error true})))))

(t/deftest test-core-file-verbs-report-their-result
  (t/testing "write and edit return the tool's success message; edit accepts a
            collection of replacements"
    (let [s (kept-context)
          path (str "target/test-core-file-verbs-" (random-uuid) ".txt")]
      (try
        (let [result (run-in s {}
                             (str "(core/emit (core/write " (pr-str path) " "
                                  (pr-str "alpha\nbeta\n") "))"))]
          (t/is (str/includes? (:content result) "wrote")))
        (let [result (run-in s {}
                             (str "(core/emit (core/edit " (pr-str path)
                                  " \"alpha\" \"gamma\"))"))]
          (t/is (str/includes? (:content result) "replaced 1 block")))
        (let [result (run-in s {}
                             (str "(core/emit (core/edit " (pr-str path)
                                  " [[\"gamma\" \"delta\"] [\"beta\" \"epsilon\"]]))"))]
          (t/is (str/includes? (:content result) "replaced 2 blocks"))
          (t/is (= "delta\nepsilon\n" (slurp path))))
        (let [result (run-in s {}
                             (str "(core/emit (core/edit " (pr-str path)
                                  " {:edits [[\"delta\" \"one\"] [\"epsilon\" \"two\"]]}))"))]
          (t/is (str/includes? (:content result) "replaced 2 blocks"))
          (t/is (= "one\ntwo\n" (slurp path))))
        (finally (fs/delete-if-exists path))))))

(t/deftest test-edit-map-values-are-normalized
  (t/testing "map-form edits stringify old/new like pair-form edits"
    (let [s (kept-context)
          path (str "target/test-edit-map-values-" (random-uuid) ".txt")]
      (try
        (run-in s {} (str "(core/write " (pr-str path) " \"1\")"))
        (let [result (run-in s {}
                             (str "(core/emit (core/edit " (pr-str path)
                                  " {:edits [{:old-text 1 :new-text 2}]}))"))]
          (t/is (str/includes? (:content result) "replaced 1 block"))
          (t/is (= "2" (slurp path))))
        (finally (fs/delete-if-exists path))))))

(t/deftest test-edit-map-requires-both-keys
  (t/testing "a map edit without old or new text is rejected, not stringified"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"edit expects"
                            (script/edit-text "unused.txt" [{:old-text "a"}] nil)))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"edit expects"
                            (script/edit-text "unused.txt" [{:new-text "b"}] nil))))
  (t/testing "the error names the accepted keys and the offending value"
    (let [error (try (script/edit-text "unused.txt" [{"a" "b"}] nil)
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (str/includes? (ex-message error) ":old-text"))
      (t/is (str/includes? (ex-message error) ":new-text"))
      (t/is (str/includes? (ex-message error) "{\"a\" \"b\"}")))))

(t/deftest test-bash-options-must-be-positive-integers
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (script/bash-text "echo hi" {:max-lines 0} nil)))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (script/bash-text "echo hi" {:max-bytes 1.5} nil))))

(t/deftest ^:slow test-core-bash-options
  (t/testing "bash accepts :cwd, :max-lines and :as :map"
    (let [s (kept-context)
          dir (test-tmp/temp-dir! "test-core-bash-")
          marker-name (str "cwd-marker-" (random-uuid) ".txt")
          marker (str (fs/path dir marker-name))]
      (try
        (run-in s {}
                (str "(core/bash "
                     (pr-str (str "echo cwd-ok > " marker-name))
                     " {:cwd " (pr-str dir) "})"))
        (t/is (fs/exists? marker))
        (t/is (str/includes? (slurp marker) "cwd-ok"))
        (let [result (run-in s {}
                             "(core/emit (core/bash \"echo alpha && echo beta && echo gamma\" {:max-lines 2}))")]
          (t/is (str/includes? (:content result) "gamma"))
          (t/is (not (str/includes? (:content result) "alpha"))))
        (let [result (run-in s {}
                             "(core/emit (core/bash \"echo hi && exit 3\" {:as :map}))")]
          (t/is (str/includes? (:content result) ":exit-code 3"))
          (t/is (str/includes? (:content result) "hi")))
        (finally
          (fs/delete-tree dir)
          (fs/delete-if-exists marker-name))))))

(t/deftest test-core-verbs-use-the-implementations-not-the-tool-bridge (let [s (kept-context) read-tools {"read" (tool-def "read")} bash-tools {"bash" (tool-def "bash")} path (str "target/test-core-verbs-" (random-uuid) ".txt")] (t/is (str/includes? (:content (run-with-dispatch s read-tools "(core/emit (core/read \"no-such-file\"))")) "File not found") "core/read calls the read implementation, not the tool stub") (t/is (= "hi\n" (:content (run-with-dispatch s bash-tools "(core/emit (core/bash \"echo hi\"))"))) "core/bash calls the bash implementation, not the tool stub") (try (run-with-dispatch s {"write" (tool-def "write")} (str "(core/write " (pr-str path) " \"old\")")) (t/is (= "old" (slurp path))) (run-with-dispatch s {"edit" (tool-def "edit")} (str "(core/edit " (pr-str path) " \"old\" \"new\")")) (t/is (= "new" (slurp path))) (finally (fs/delete-if-exists path)))))

(defn- with-server
  "Run F with the base URL of a one-shot server handled by HANDLER."
  [handler f]
  (let [[base close] (http-server/start-server handler)]
    (try (f base) (finally (close)))))

(defn- with-response
  "Run F with the base URL of a server answering every request with STATUS,
   BODY and HEADERS."
  [status body headers f]
  (with-server (fn [s _ _ _] (http-server/respond s status body headers)) f))

(defn- request-body
  "Read the request body from RDR using the Content-Length header."
  [headers rdr]
  (let [n (Long/parseLong (get headers "content-length" "0"))
        buf (char-array n)]
    (loop [off 0]
      (when (< off n)
        (let [read (.read rdr buf off (- n off))]
          (when (pos? read)
            (recur (+ off read))))))
    (String. buf)))

(t/deftest test-fetch-get-string
  (with-response "200 OK" "hello" {"Content-Type" "text/plain"}
    (fn [base]
      (let [r (script/fetch-response (str base "/") nil nil)]
        (t/is (= 200 (:status r)))
        (t/is (= "hello" (:body r)))
        (t/is (= "text/plain" (get (:headers r) "content-type")))))))

(t/deftest test-fetch-status-and-throw
  (with-response "404 Not Found" "missing" {"Content-Type" "text/plain"}
    (fn [base]
      (let [r (script/fetch-response (str base "/") nil nil)]
        (t/is (= 404 (:status r)))
        (t/is (= "missing" (:body r))))
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"404"
                              (script/fetch-response (str base "/")
                                                     {:throw? true} nil))))))

(t/deftest test-fetch-sends-headers
  (with-server
    (fn [s _ headers _]
      (http-server/respond s "200 OK" (get headers "x-test" "") {}))
    (fn [base]
      (t/is (= "yes" (:body (script/fetch-response
                             (str base "/") {:headers {"X-Test" "yes"}} nil)))))))

(t/deftest test-fetch-json
  (with-response "200 OK" "{\"a\":1}" {"Content-Type" "application/json; charset=utf-8"}
    (fn [base]
      (t/is (= {:a 1} (:body (script/fetch-response (str base "/") {:as :json} nil))))))
  (with-response "200 OK" "{\"b\":2}" {"Content-Type" "application/vnd.test+json"}
    (fn [base]
      (t/is (= {:b 2} (:body (script/fetch-response (str base "/") {:as :json} nil))))))
  (with-response "200 OK" "1" {"Content-Type" "text/plain"}
    (fn [base]
      (t/is (= 1 (:body (script/fetch-response (str base "/") {:as :json} nil))))))
  (with-response "200 OK" "" {"Content-Type" "application/json"}
    (fn [base]
      (t/is (nil? (:body (script/fetch-response (str base "/") {:as :json} nil))))))
  (with-response "404 Not Found" "{\"error\":true}" {"Content-Type" "application/json"}
    (fn [base]
      (t/is (= {:error true}
               (:body (script/fetch-response (str base "/") {:as :json} nil))))))
  (with-response "500 Internal Server Error" "oops" {"Content-Type" "text/plain"}
    (fn [base]
      (t/is (= "oops" (:body (script/fetch-response (str base "/") {:as :json} nil)))))))

(t/deftest test-fetch-json-parse-error-names-url-and-status
  (with-response "200 OK" "not json" {"Content-Type" "application/json"}
    (fn [base]
      (let [url (str base "/bad")
            error (try (script/fetch-response url {:as :json} nil)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (= :script/fetch-json (:type (ex-data error))))
        (t/is (str/includes? (ex-message error) url))
        (t/is (str/includes? (ex-message error) "200"))
        (t/is (not (str/includes? (ex-message error) "not json")))))))

(t/deftest test-fetch-json-request-body-and-content-type
  (let [seen (atom nil)]
    (with-server
      (fn [s _ headers rdr]
        (reset! seen [(get headers "content-type") (request-body headers rdr)])
        (http-server/respond s "200 OK" "ok" {}))
      (fn [base]
        (t/is (= "ok" (:body (script/fetch-response
                              (str base "/") {:method :post :body {:a 1}} nil))))
        (t/is (= ["application/json" "{\"a\":1}"] @seen))
        (t/is (= "ok" (:body (script/fetch-response
                              (str base "/")
                              {:method :post
                               :headers {"content-type" "application/vnd.test+json"}
                               :body {:a 1}} nil))))
        (t/is (= ["application/vnd.test+json" "{\"a\":1}"] @seen))))))

(t/deftest test-fetch-request-options
  (let [seen (atom nil)
        signal (atom false)]
    (with-redefs [http/request (fn [opts]
                                 (reset! seen opts)
                                 {:status 200 :headers {} :body ""})]
      (script/fetch-response "http://example.test/"
                             {:timeout 1.5
                              :follow-redirects :never
                              :throw? true
                              :headers {"X-Test" "1"}}
                             signal)
      (t/is (= 1500 (:timeout @seen)))
      (t/is (= :never (:follow-redirects @seen)))
      (t/is (true? (:throw? @seen)))
      (t/is (identical? signal (:signal @seen)))
      (t/is (= :get (:method @seen)))
      (t/is (= "1" (get (:headers @seen) "X-Test")))
      (script/fetch-response "http://example.test/" nil nil)
      (t/is (= 120000 (:timeout @seen)))
      (t/is (false? (:throw? @seen)))
      (script/fetch-response "http://example.test/" {:timeout 0} nil)
      (t/is (= 120000 (:timeout @seen)))
      (script/fetch-response "http://example.test/" {:timeout 0.0001} nil)
      (t/is (= 1 (:timeout @seen)))
      (script/fetch-response "http://example.test/" {:body {:a 1}} nil)
      (t/is (= {"Content-Type" "application/json"} (:headers @seen)))
      (script/fetch-response "http://example.test/"
                             {:body {:a 1} :headers {:content-type "x/y"}} nil)
      (t/is (= {:content-type "x/y"} (:headers @seen))))))

(t/deftest test-fetch-rejects-a-bad-surface
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a URL"
                          (script/fetch-response "" nil nil)))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #":string or :json"
                          (script/fetch-response "http://example.test/"
                                                 {:as :bytes} nil))))

(t/deftest test-fetch-is-a-referred-core-verb
  (with-response "200 OK" "hello" {}
    (fn [base]
      (let [s (kept-context)
            url (str base "/")]
        (t/is (str/includes?
               (:content (run-in s {} (str "(core/emit (fetch \"" url "\"))")))
               "hello"))
        (t/is (str/includes?
               (:content (run-in s {} (str "(core/emit (core/fetch \"" url "\"))")))
               "hello"))))))

(t/deftest ^:slow test-fetch-timeout
  (with-server
    (fn [_ _ _ _] (Thread/sleep 2000))
    (fn [base]
      (let [error (try (script/fetch-response (str base "/") {:timeout 0.1} nil)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (= :transport-error (:type (ex-data error))))))))

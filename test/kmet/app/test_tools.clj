(ns kmet.app.test-tools
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.extensions :as extensions]
            [kmet.ai.api.shared :as schema-shared]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.bash-executor :as bash-exec]))

(t/deftest test-tool-cwd-binding
  (t/testing "relative tool paths resolve against the bound runtime cwd (pi:
              the cwd the tool definitions were created with), not the process cwd"
    (let [dir (str (fs/absolutize (str "target/test-tools-cwd-" (System/currentTimeMillis))))]
      (fs/create-dirs dir)
      (try
        (t/is (= (System/getProperty "user.dir") (tool-util/cwd))
              "unbound: the process cwd")
        (binding [tool-util/*cwd* dir]
          (t/is (= dir (tool-util/cwd)))
          (t/is (= "/abs/path.txt" (tool-util/resolve-tool-path "/abs/path.txt"))
                "absolute paths pass through")
          (t/is (= (str (fs/path dir "sub/notes.txt"))
                   (tool-util/resolve-tool-path "sub/notes.txt")))
          (t/is (not (:is-error (tools/execute-tool "write" {:path "notes.txt" :content "hi"}))))
          (t/is (= "hi" (slurp (str (fs/path dir "notes.txt"))))
                "write lands inside the bound cwd")
          (t/is (str/includes? (:content (tools/execute-tool "read" {:path "notes.txt"})) "hi"))
          (t/is (not (:is-error (tools/execute-tool "edit" {:path "notes.txt"
                                                            :old-text "hi"
                                                            :new-text "bye"}))))
          (t/is (= "bye" (slurp (str (fs/path dir "notes.txt"))))))
        (finally (fs/delete-tree dir))))))

(t/deftest ^:slow test-tool-bash-cwd-binding
  (t/testing "the bash tool runs in the bound runtime cwd"
    (let [dir (str (fs/absolutize (str "target/test-tools-bash-cwd-" (System/currentTimeMillis))))]
      (fs/create-dirs dir)
      (try
        (binding [tool-util/*cwd* dir]
          (let [result (tools/execute-tool "bash" {:command "pwd"})]
            (t/is (not (:is-error result)))
            (t/is (str/includes? (:content result) dir))))
        (finally (fs/delete-tree dir))))))

;; ─── Tool registry ─────────────────────────────────────────────────────────

(t/deftest test-tools-get-all
  (let [all (tools/get-all-tools)]
    (t/is (map? all))
    (t/is (contains? all "read"))
    (t/is (contains? all "write"))
    (t/is (contains? all "edit"))
    (t/is (contains? all "bash"))
    ;; grep/find/ls are opt-in extensions, not builtins (the shipped
    ;; extensions/ files register them)
    (t/is (not (contains? all "grep")))
    (t/is (not (contains? all "find")))
    (t/is (not (contains? all "ls")))))

(t/deftest test-tool-resolution-shadowing
  (t/testing "one resolution path: a custom tool reusing a built-in's name is
              listed AND resolved (custom shadows built-in, pi's layering)"
    (try
      (tools/register-tool! {:name "read" :label "Shadow read"
                             :description "shadow"
                             :parameters {:type "object" :properties {}}
                             :execute (fn [_] {:content "shadow"})})
      (t/is (= (get (tools/get-all-tools) "read") (tools/get-tool "read"))
            "get-tool resolves the same map get-all-tools lists")
      (t/is (= "Shadow read" (:label (tools/get-tool "read"))))
      (t/is (= "shadow" (:content (tools/execute-tool "read" {})))
            "dispatch executes the listed record")
      (finally
        (tools/unregister-tool! "read")
        (t/is (= "Read file" (:label (tools/get-tool "read"))))))))

(t/deftest test-search-tools-extensions
  (t/testing "grep/find/ls ship as opt-in extensions: loading the shipped
              files registers the tools; results are matches only and
              relative paths resolve against the ctx cwd"
    (let [dir (str (fs/absolutize (str "target/test-tools-search-"
                                       (System/currentTimeMillis))))]
      (fs/create-dirs (str (fs/path dir ".git")))
      (try
        (spit (str (fs/path dir "a.clj")) "hello\nworld\n")
        (spit (str (fs/path dir "b.clj")) "nothing\n")
        ;; a repo's object store is never source-search material
        (spit (str (fs/path dir ".git" "obj")) "world\n")
        (doseq [path ["extensions/grep-tool.clj"
                      "extensions/find-tool.clj"
                      "extensions/ls-tool.clj"]]
          (let [r (extensions/load-extension! path)]
            (t/is (nil? (:error r)) (str path ": " (:error r)))))
        (doseq [tool-name ["grep" "find" "ls"]]
          ;; the extensions pass renderers/render-bash-result (:render-result);
          ;; identity is only comparable on bb — on Jolt the loader wraps
          ;; registered fns in the extension's ambient binding
          (t/is (fn? (:render-result (tools/get-tool tool-name)))
                (str tool-name " carries a result renderer")))
        (let [ctx {:cwd dir}]
          (let [g (tools/execute-tool "grep" {:pattern "world"} {:ctx ctx})]
            (t/is (not (:is-error g)))
            (t/is (str/includes? (:content g) "a.clj"))
            (t/is (str/includes? (:content g) "world"))
            (t/is (not (str/includes? (:content g) "nothing")))
            (t/is (not (str/includes? (:content g) ".git"))
                  ".git is skipped"))
          (let [g-file (tools/execute-tool "grep" {:pattern "world"
                                                   :path (str (fs/path dir "a.clj"))}
                                           {:ctx ctx})]
            (t/is (str/includes? (:content g-file) "target/test-tools-search-")
                  "single-file search reports the path, not just the basename")
            (t/is (str/includes? (:content g-file) "world")))
          (let [f (tools/execute-tool "find" {:pattern "b\\.clj"} {:ctx ctx})]
            (t/is (not (:is-error f)))
            (t/is (str/includes? (:content f) "b.clj"))
            (t/is (not (str/includes? (:content f) "a.clj"))))
          (let [l (tools/execute-tool "ls" {:path dir} {:ctx ctx})]
            (t/is (not (:is-error l)))
            (t/is (str/includes? (:content l) "a.clj"))
            (t/is (str/includes? (:content l) "b.clj")))
          (let [ll (tools/execute-tool "ls" {:path dir :long true} {:ctx ctx})]
            (t/is (re-find #"(?m)^- +\d+ a\.clj$" (:content ll))
                  "long form carries type and size")))
        (extensions/unload-all-extensions!)
        (t/is (nil? (tools/get-tool "grep")) "unload deregisters the tool")
        (t/is (nil? (tools/get-tool "find")))
        (t/is (nil? (tools/get-tool "ls")))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-tree dir))))))

(t/deftest test-tools-get
  (let [t (tools/get-tool "read")]
    (t/is (some? t))
    (t/is (= "read" (:name t)))
    (t/is (fn? (:execute t)))))

(t/deftest test-tools-get-unknown
  (t/is (nil? (tools/get-tool "nonexistent"))))

;; ─── Tool record ──────────────────────────────────────────────────────────

(t/deftest test-tool-record
  (let [t (tools/get-tool "read")]
    (t/is (map? t))
    (t/is (string? (:name t)))
    (t/is (string? (:label t)))
    (t/is (string? (:description t)))
    (t/is (map? (:parameters t)))
    (t/is (fn? (:execute t)))))

(t/deftest test-tool-record-fields
  (let [t (tools/get-tool "read")]
    (t/is (= "read" (:name t)))
    (t/is (= "Read file" (:label t)))
    (t/is (= 3 (count (keys (:parameters t)))))))

(t/deftest test-tool-quiet-titles
  (t/testing "built-in tools carry a :title fn for the quiet one-liner"
    (doseq [[tool-name args expected] [["read" {:path "src/a.clj" :offset 5 :limit 3} "read src/a.clj:5-7"]
                                       ["read" {:path "src/a.clj"} "read src/a.clj"]
                                       ["write" {:path "src/a.clj"} "write src/a.clj"]
                                       ["edit" {:path "src/a.clj"} "edit src/a.clj"]
                                       ["bash" {:command "ls -la"} "bash $ ls -la"]]]
      (let [title (:title (tools/get-tool tool-name))]
        (t/is (fn? title) (str tool-name " defines :title"))
        (t/is (= expected (title args)))))
    (t/is (nil? ((:title (tools/get-tool "read")) nil)) "nil args → nil (quiet falls back to the name)")
    (t/is (nil? ((:title (tools/get-tool "read")) {})) "missing arg → nil")
    (t/is (nil? ((:title (tools/get-tool "bash")) {:command ""})) "empty command → nil")))

;; ─── Tool read ────────────────────────────────────────────────────────────

(t/deftest test-tool-read-file
  (spit "target/test-tools-read.txt" "hello\nworld\nfoo\nbar")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt"})]
    (t/is (string? (:content result)))
    (t/is (.contains (:content result) "hello"))
    (t/is (not (:is-error result)))))

(t/deftest test-tool-read-with-offset
  (spit "target/test-tools-read.txt" "line1\nline2\nline3\nline4")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 2})]
    (t/is (not (.contains (:content result) "line1")))
    (t/is (.contains (:content result) "line3"))))

(t/deftest test-tool-read-with-limit
  (spit "target/test-tools-read.txt" "a\nb\nc\nd\ne")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :limit 2})
        content (:content result)]
    (t/is (.contains content "a"))
    (t/is (.contains content "b"))
    ;; Pi: a user limit stops early with a continuation footer
    (t/is (not (re-find #"(?m)^c$" content)))
    (t/is (.contains content "more lines in file"))))

(t/deftest test-tool-read-nonexistent
  (let [result (tools/execute-tool "read" {:path "nonexistent-file"})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "not found"))))

;; ─── Tool write ───────────────────────────────────────────────────────────

(t/deftest test-tool-write
  (let [result (tools/execute-tool "write" {:path "target/test-tools-write.txt" :content "hello world"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "Successfully wrote"))
    (t/is (= "hello world" (slurp "target/test-tools-write.txt")))))

;; ─── Tool edit ────────────────────────────────────────────────────────────

(t/deftest test-tool-edit
  (spit "target/test-tools-edit.txt" "hello world")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :old-text "hello"
                                           :new-text "bye"})]
    (t/is (not (:is-error result)))
    (t/is (= "bye world" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-pattern-not-found
  (spit "target/test-tools-edit.txt" "hello")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :old-text "nonexistent"
                                           :new-text "x"})]
    (t/is (:is-error result))))

(t/deftest test-tool-edit-nonexistent-file
  (let [result (tools/execute-tool "edit" {:path "target/nonexistent-edit.txt"
                                           :old-text "x" :new-text "y"})]
    (t/is (:is-error result))))

(t/deftest test-tool-edit-crlf-preserved
  (t/testing "CRLF files match with LF old-text, keep CRLF, diff has no \r (pi normalizeToLF + restoreLineEndings)"
    (spit "target/test-tools-edit-crlf.txt" "alpha\r\nbeta\r\ngamma")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-crlf.txt"
                                             :edits [{:old-text "beta" :new-text "BETA"}]})]
      (t/is (not (:is-error result)))
      (t/is (= "alpha\r\nBETA\r\ngamma" (slurp "target/test-tools-edit-crlf.txt")))
      (t/is (not (clojure.string/includes? (get-in result [:details :diff]) "\r"))))))

(t/deftest test-tool-edit-bom-preserved
  (t/testing "UTF-8 BOM is stripped for matching and restored on write (pi stripBom)"
    (spit "target/test-tools-edit-bom.txt" "\uFEFFline1\nline2")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-bom.txt"
                                             :edits [{:old-text "line2" :new-text "LINE2"}]})]
      (t/is (not (:is-error result)))
      ;; slurp strips the BOM on Jolt (Chez transcoded port), so compare
      ;; raw bytes: EF BB BF + the edited content.
      (t/is (= (seq (.getBytes "\uFEFFline1\nLINE2" "UTF-8"))
               (seq (fs/read-all-bytes "target/test-tools-edit-bom.txt")))))))

(t/deftest test-tool-edit-fuzzy-match
  (t/testing "trailing whitespace differences match fuzzily (pi normalizeForFuzzyMatch)"
    (spit "target/test-tools-edit-fuzzy.txt" "alpha\nbeta  \ngamma")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-fuzzy.txt"
                                             :edits [{:old-text "beta \t" :new-text "BETA"}]})]
      (t/is (not (:is-error result)))
      ;; Pi: the changed line is rewritten from the normalized base (trailing
      ;; whitespace stripped); unchanged lines keep their original bytes
      (t/is (= "alpha\nBETA\ngamma" (slurp "target/test-tools-edit-fuzzy.txt"))))))

(t/deftest test-tool-edit-duplicate-error
  (t/testing "ambiguous old-text reports occurrence count (pi getDuplicateError)"
    (spit "target/test-tools-edit-dup.txt" "x\nx")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-dup.txt"
                                             :edits [{:old-text "x" :new-text "X"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "Found 2 occurrences")))))

(t/deftest test-tool-edit-no-change-error
  (t/testing "identical replacement reports no-change (pi getNoChangeError)"
    (spit "target/test-tools-edit-nc.txt" "alpha")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-nc.txt"
                                             :edits [{:old-text "alpha" :new-text "alpha"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "No changes made")))))

(t/deftest test-tool-edit-overlap-error
  (t/testing "overlapping edits are rejected (pi overlap check)"
    (spit "target/test-tools-edit-ov.txt" "abcdef")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-ov.txt"
                                             :edits [{:old-text "abcd" :new-text "X"}
                                                     {:old-text "cdef" :new-text "Y"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "overlap")))))

(t/deftest test-tool-edit-empty-oldtext-error
  (t/testing "empty old-text is rejected (pi getEmptyOldTextError)"
    (spit "target/test-tools-edit-empty.txt" "alpha")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-empty.txt"
                                             :edits [{:old-text "" :new-text "x"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "oldText must not be empty")))))

(t/deftest ^:slow test-tool-bash-streams
  (t/testing "bash tool streams partial output via on-update (pi onUpdate)"
    (let [updates (atom [])
          result (tools/execute-tool "bash" {:command "echo first; sleep 0.3; echo second"}
                                     {:on-update (fn [partial]
                                                   (swap! updates conj (:content partial)))})]
      (t/is (not (:is-error result)))
      (t/is (seq @updates))
      (t/is (some #(clojure.string/includes? % "first") @updates))
      (t/is (clojure.string/includes? (:content result) "second")))))

;; ─── Tool bash ────────────────────────────────────────────────────────────

(t/deftest ^:slow test-tool-bash
  (let [result (tools/execute-tool "bash" {:command "echo hello bash"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello bash"))))

(t/deftest ^:slow test-tool-bash-error
  (let [result (tools/execute-tool "bash" {:command "ls /nonexistent-path-xyz"})]
    (t/is (:is-error result))))

(t/deftest ^:slow test-tool-bash-exit-code
  (let [result (tools/execute-tool "bash" {:command "exit 42"})]
    (t/is (:is-error result) "Non-zero exit should be error")))

(t/deftest ^:slow test-tool-bash-large-stderr
  (let [result (tools/execute-tool "bash"
                                   {:command "for i in $(seq 1 200); do echo err$i >&2; done; echo ok"})]
    (t/is (not (:is-error result)) "Large stderr should not deadlock")
    (t/is (.contains (:content result) "ok"))))

(t/deftest ^:slow test-tool-bash-large-stdout
  (let [result (tools/execute-tool "bash"
                                   {:command "for i in $(seq 1 200); do echo out$i; done; echo done"})]
    (t/is (not (:is-error result)) "Large stdout should not deadlock")
    (t/is (.contains (:content result) "done"))))

(t/deftest ^:slow test-tool-bash-stdout-and-stderr-merged
  (let [result (tools/execute-tool "bash" {:command "echo hello; echo world >&2"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello"))
    (t/is (.contains (:content result) "world"))))

(t/deftest ^:slow test-tool-bash-stdin-eof
  (t/testing "commands that read stdin get EOF, not the TTY (pi stdio ignore)"
    ;; Regression: `cat` with no args inherited the TTY stdin and deadlocked
    ;; the TUI (kmet spawns with stdin redirected from /dev/null instead).
    (let [result (tools/execute-tool "bash" {:command "cat; echo FINISHED"
                                             :timeout 15})]
      (t/is (not (:is-error result)) "stdin must be EOF, not the TTY")
      (t/is (.contains (:content result) "FINISHED")))))

(t/deftest ^:slow test-tool-bash-background-pipe-closed
  (t/testing "detached child holding the pipe open doesn't stall the tool (pi waitForChildProcess)"
    ;; The descendant outlives the tool's bounded drain: a broken
    ;; implementation that waits for pipe EOF takes ~10s (the sleep's
    ;; lifetime); the fixed one returns at the ~2s grace + spawn overhead.
    ;; The 8s window separates the two with margin on both sides, and is
    ;; generous enough for a loaded host.
    (let [start (System/currentTimeMillis)
          result (tools/execute-tool "bash" {:command "sleep 10 &"})
          elapsed (- (System/currentTimeMillis) start)]
      (t/is (not (:is-error result)))
      (t/is (< elapsed 8000)
            (str "tool should return at bash exit + the bounded drain, took " elapsed "ms")))))

(t/deftest ^:slow test-tool-bash-cancel-signal
  (t/testing "setting the bound cancel signal kills the process (pi AbortSignal)"
    (let [sig (atom false)
          f (binding [bash-tool/*cancel-signal* sig]
              (future (tools/execute-tool "bash" {:command "sleep 30"})))]
      (Thread/sleep 500)
      (reset! sig true)
      (let [result (deref f 5000 ::timeout)]
        (t/is (not= ::timeout result) "cancelled bash must return promptly")
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "aborted"))))))

;; ─── Tool bash: pi parity (session env, spawn options, shell settings) ─────

(t/deftest test-bash-strip-session-env
  (t/testing "stale KMET_* session vars are dropped from the inherited env (pi resolveSpawnContext deletes them)"
    (let [strip @#'bash-exec/strip-session-env]
      (t/is (= {"PATH" "/bin"}
               (strip {"PATH" "/bin"
                       "KMET_SESSION_ID" "old" "KMET_SESSION_FILE" "old"
                       "KMET_PROVIDER" "old" "KMET_MODEL" "old"
                       "KMET_REASONING_LEVEL" "old"})))
      (t/is (= {} (strip {}))))))

(t/deftest test-bash-session-env
  (t/testing "session-env builds the KMET_* map (pi resolveSpawnContext)"
    (t/is (= {"KMET_SESSION_ID" "s1" "KMET_SESSION_FILE" "/tmp/s.ednl"
              "KMET_PROVIDER" "deepseek" "KMET_MODEL" "m1"
              "KMET_REASONING_LEVEL" "off"}
             (bash-tool/session-env {:session {:id "s1" :file "/tmp/s.ednl"}
                                     :provider :deepseek :model "m1" :thinking-level :off})))
    (t/testing "absent values are omitted (pi: session file only when persisted)"
      (t/is (= {} (bash-tool/session-env {})))
      (t/is (= {} (bash-tool/session-env nil)))
      (t/is (= {"KMET_MODEL" "m1"} (bash-tool/session-env {:model "m1"}))))))

(t/deftest test-bash-session-env-keys-in-sync
  (t/testing "every variable session-env can emit is stripped from the inherited env (no nested-kmet leak)"
    (t/is (set/subset? (set (keys (bash-tool/session-env
                                   {:session {:id "s" :file "f"}
                                    :provider :p :model "m" :thinking-level :off})))
                       (set bash-exec/session-env-keys)))))

(t/deftest ^:slow test-tool-bash-session-env
  (t/testing "pi: exposeSessionEnvironment — the run's KMET_* session env reaches the command"
    (let [env-fn (fn [] (bash-tool/session-env {:session {:id "sess-1" :file "/tmp/tool-s.ednl"}
                                                :provider :deepseek
                                                :model "m-9"
                                                :thinking-level :off}))
          result (binding [bash-tool/*session-env-fn* env-fn]
                   (tools/execute-tool
                    "bash"
                    {:command (str "echo id=$KMET_SESSION_ID file=$KMET_SESSION_FILE"
                                   " p=$KMET_PROVIDER m=$KMET_MODEL t=$KMET_REASONING_LEVEL")}))]
      (t/is (not (:is-error result)))
      (t/is (str/includes? (:content result) "id=sess-1"))
      (t/is (str/includes? (:content result) "file=/tmp/tool-s.ednl"))
      (t/is (str/includes? (:content result) "p=deepseek"))
      (t/is (str/includes? (:content result) "m=m-9"))
      (t/is (str/includes? (:content result) "t=off")))))

(t/deftest ^:slow test-tool-bash-session-env-not-exposed
  (t/testing "a tool built with :expose-session-env? false injects nothing (pi exposeSessionEnvironment: false)"
    (let [tool (bash-tool/create-tool {:expose-session-env? false})
          result (binding [bash-tool/*session-env-fn* (fn [] {"KMET_SESSION_ID" "sess-1"})]
                   ((:execute tool) {:command "echo id=[$KMET_SESSION_ID]"}))]
      (t/is (not (:is-error result)))
      (t/is (str/includes? (:content result) "id=[]"))
      (t/is (not-any? #(str/includes? % "KMET_* environment variables")
                      (:prompt-guidelines tool))
            "the session-env guideline is gated on exposure (pi: exposeSessionEnvironment)")
      (t/is (nil? (:prompt-guidelines tool))
            "exposure off leaves the contribution's guidelines empty (pi)"))))

(t/deftest test-tool-bash-create-tool-defaults
  (t/testing "create-tool is pi's createBashTool — the built-in tool with defaults"
    (let [tool (bash-tool/create-tool)
          builtin (tools/get-tool "bash")]
      (t/is (= "bash" (:name tool)))
      (t/is (= "Execute command" (:label tool)))
      (t/is (= (:description builtin) (:description tool)))
      (t/is (= (:parameters tool) (:parameters builtin)))
      (t/is (= ["You can inspect KMET_* environment variables for current model and session details."]
               (:prompt-guidelines tool)))
      (t/is (:streams? tool)))))

(t/deftest ^:slow test-tool-bash-create-tool-spawn-hook
  (t/testing "pi: BashSpawnHook — command/cwd/env are rewritten before spawn, after the KMET_* injection"
    (let [seen (atom nil)
          tool (bash-tool/create-tool {:spawn-hook (fn [ctx]
                                                     (reset! seen ctx)
                                                     (assoc ctx :command "echo HOOKED"))})
          result (binding [bash-tool/*session-env-fn* (fn [] {"KMET_SESSION_ID" "sess-1"})]
                   ((:execute tool) {:command "echo unreachable"}))]
      (t/is (not (:is-error result)))
      (t/is (str/includes? (:content result) "HOOKED"))
      (t/is (not (str/includes? (:content result) "unreachable")))
      (t/is (= "echo unreachable" (:command @seen)))
      (t/is (= (System/getProperty "user.dir") (:cwd @seen)))
      (t/is (= "sess-1" (get (:env @seen) "KMET_SESSION_ID"))
            "the hook receives the injected session env (pi: injection happens before spawnHook)"))))

(t/deftest ^:slow test-tool-bash-shell-command-prefix
  (t/testing "pi: shellCommandPrefix — a settings line is prepended to every command"
    (bash-exec/set-shell-options! {:command-prefix "echo PREFIX-LINE"})
    (try
      (let [result (tools/execute-tool "bash" {:command "echo body"})]
        (t/is (not (:is-error result)))
        (t/is (str/starts-with? (:content result) "PREFIX-LINE"))
        (t/is (str/includes? (:content result) "body")))
      (t/testing "an explicit tool option wins (pi: options baked into the tool)"
        (let [tool (bash-tool/create-tool {:command-prefix "echo TOOL-PREFIX"})
              result ((:execute tool) {:command "echo body"})]
          (t/is (str/starts-with? (:content result) "TOOL-PREFIX"))
          (t/is (not (str/includes? (:content result) "PREFIX-LINE")))))
      (finally
        (bash-exec/set-shell-options! {:shell-path nil :command-prefix nil})))))

(t/deftest ^:slow test-tool-bash-shell-path
  (t/testing "pi: shellPath — a custom shell binary runs the command"
    (when-let [sh (first (filter #(fs/exists? %) ["/bin/sh" "/usr/bin/sh" "/bin/bash" "/usr/bin/bash"]))]
      (bash-exec/set-shell-options! {:shell-path sh})
      (try
        (let [result (tools/execute-tool "bash" {:command "echo shell-ok"})]
          (t/is (not (:is-error result)))
          (t/is (str/includes? (:content result) "shell-ok")))
        (finally
          (bash-exec/set-shell-options! {:shell-path nil :command-prefix nil}))))))

(t/deftest test-tool-bash-shell-path-not-found
  (t/testing "a missing custom shell errors out (pi: Custom shell path not found)"
    (let [tool (bash-tool/create-tool {:shell-path "/nonexistent/kmet-shell"})
          result ((:execute tool) {:command "echo x"})]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "Custom shell path not found")))))

;; ─── Unknown tool ─────────────────────────────────────────────────────────

(t/deftest test-truncate-tail-surrogate-boundary
  (t/testing "byte-cut landing mid-UTF8 keeps whole char boundary (pi truncateStringToBytesFromEnd)"
    (let [s (str (apply str (repeat 9 "x")) "😀" (apply str (repeat 9 "y")))
          ;; UTF-8 bytes = 9 + 4 + 9 = 22; max-bytes 10 → pi slices last 10 bytes
          ;; skipping the partial emoji bytes at the boundary (byte 10 is inside emoji)
          result (bash-exec/truncate-tail s :max-bytes 10)
          content (:content result)]
      (t/is (:truncated result))
      ;; pi skips the incomplete UTF-8 char, so the cut lands on a whole
      ;; char boundary: only the 9 trailing y's survive (9 bytes ≤ 10).
      ;; (No lone-surrogate regex: Jolt strings are codepoint-indexed and
      ;; its regex engine rejects surrogate ranges at compile time.)
      (t/is (= "yyyyyyyyy" content))
      ;; pi skips the incomplete UTF-8 char, so emoji is dropped
      (t/is (not (str/includes? content "\uFFFD")) "no replacement char")
      (t/is (<= (bash-exec/byte-length content) 10) "output fits byte limit"))))

(t/deftest test-bash-executor-streaming-utf8-decode
  (t/testing "a multi-byte char split across reads reassembles (pi TextDecoder stream: true)"
    ;; The stream is decoded by a CharsetDecoder with CodingErrorAction/REPLACE
    ;; fed one chunk at a time: a split sequence stays in the input buffer and
    ;; rides into the next read.
    (let [feed (fn [chunks]
                 (:output
                  (bash-exec/execute-bash
                   {:command "noop"
                    :operations (fn [{:keys [on-data]}]
                                  (doseq [c chunks] (on-data c 0 (alength c)))
                                  {:exit-code 0})})))
          text "héllo → 日本語 ünïcode ∂ßπ"
          bs (.getBytes text "UTF-8")]
      (doseq [size [1 2 3 5]]
        (t/is (= text (feed (mapv (fn [i]
                                    (java.util.Arrays/copyOfRange
                                     bs i (min (alength bs) (+ i size))))
                                  (range 0 (alength bs) size))))
              (str "split size " size)))
      (t/is (= "A\uFFFDB" (feed [(byte-array [0x41 (unchecked-byte 0xFF) 0x42])]))
            "invalid bytes decode to U+FFFD, not throw")
      (t/is (= "\uFFFD" (feed [(byte-array [(unchecked-byte 0xE4)])]))
            "a stranded lead byte at EOF flushes as U+FFFD"))))

(t/deftest test-tool-unknown
  (let [result (tools/execute-tool "unknown-tool" {})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "Unknown tool"))))

;; ─── Tool schemas ─────────────────────────────────────────────────────────

(t/deftest test-tool-anthropic-schema
  (let [tool (tools/get-tool "read")
        schema (schema-shared/tool->anthropic-schema tool)]
    (t/is (= "read" (:name schema)))
    (t/is (string? (:description schema)))
    (t/is (map? (:input_schema schema)))))

(t/deftest test-tool-openai-schema
  (let [tool (tools/get-tool "read")
        schema (schema-shared/tool->openai-schema tool)]
    (t/is (= "function" (:type schema)))
    (t/is (= "read" (get-in schema [:function :name])))))

;; ─── Custom tool registration ─────────────────────────────────────────────

(t/deftest test-tools-register-custom
  (let [custom (tools/make-tool
                :name "custom"
                :label "Custom"
                :description "A custom tool"
                :parameters {:type "object" :properties {} :required []}
                :execute (fn [_] {:content "done"}))]
    (tools/register-tool! custom)
    (t/is (contains? (tools/get-all-tools) "custom"))
    (let [result (tools/execute-tool "custom" {})]
      (t/is (= "done" (:content result))))
    (tools/unregister-tool! "custom")
    (t/is (not (contains? (tools/get-all-tools) "custom")))))

(t/deftest test-tools-register-normalizes-provider-schema
  (t/testing "argument-less extension tools get an object schema"
    (let [name "schema-less-tool"]
      (tools/register-tool! {:name name
                             :description "No arguments"
                             :execute (fn [_] {:content "ok"})})
      (try
        (t/is (= {:type "object" :properties {} :required []}
                 (:parameters (tools/get-tool name))))
        (finally
          (tools/unregister-tool! name)))))
  (t/testing "extension :params are converted to provider schema"
    (let [name "compact-schema-tool"]
      (tools/register-tool! {:name name
                             :description "Compact params"
                             :params {:value {:type :string
                                              :description "A value"}}
                             :execute (fn [_] {:content "ok"})})
      (try
        (t/is (= {:type "object"
                  :properties {"value" {:type "string" :description "A value"}}
                  :required ["value"]}
                 (:parameters (tools/get-tool name))))
        (finally
          (tools/unregister-tool! name))))))

(t/deftest test-tools-prepare-arguments
  (t/testing "pi prepareToolCallArguments: the shim rewrites args before execution"
    (let [custom (tools/make-tool
                  :name "prepare-args"
                  :label "Prepare"
                  :description "Applies the shim"
                  :parameters {:type "object" :properties {} :required []}
                  :prepare-arguments (fn [args] (assoc args "normalized" true))
                  :execute (fn [args] {:content (str "got " (count args) " keys")}))]
      (tools/register-tool! custom)
      (try
        (let [result (tools/execute-tool "prepare-args" {"raw" 1})]
          (t/is (= "got 2 keys" (:content result))
                "execute receives the prepared args (raw + normalized)"))
        (finally
          (tools/unregister-tool! "prepare-args")))))
  (t/testing "a throwing shim surfaces as a tool error (pi: validation failure)"
    (let [custom (tools/make-tool
                  :name "prepare-throws"
                  :label "Prepare"
                  :description "Throws in the shim"
                  :parameters {:type "object" :properties {} :required []}
                  :prepare-arguments (fn [_] (throw (ex-info "bad args" {})))
                  :execute (fn [_] {:content "unreachable"}))]
      (tools/register-tool! custom)
      (try
        (let [result (tools/execute-tool "prepare-throws" {})]
          (t/is (:is-error result))
          (t/is (.contains (:content result) "bad args")))
        (finally
          (tools/unregister-tool! "prepare-throws"))))))

;; ─── Edge cases ──────────────────────────────────────────────────────────

(t/deftest test-tool-read-binary
  (spit "target/test-tools-binary.bin" (byte-array [0 1 2 3]))
  (let [result (tools/execute-tool "read" {:path "target/test-tools-binary.bin"})]
    (t/is (string? (:content result)))))

;; ─── Tool schema: no internal :optional leak ────────────────────────────

(t/deftest test-tool-schema-no-optional
  (doseq [tool-name ["read" "write" "edit" "bash"]]
    (let [tool (tools/get-tool tool-name)
          params (:parameters tool)
          props (:properties params)]
      (doseq [[k v] props]
        (t/is (not (contains? v :optional))
              (str "Property " k " of " tool-name " should not have :optional"))))))

(t/deftest test-tool-edit-edits-array
  (spit "target/test-tools-edit.txt" "one two three")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits [{:old-text "one" :new-text "1"}
                                                   {:old-text "three" :new-text "3"}]})]
    (t/is (not (:is-error result)))
    (t/is (= "1 two 3" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-camelcase-keys
  (spit "target/test-tools-edit.txt" "hello world")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits [{:oldText "hello" :newText "bye"}]})]
    (t/is (not (:is-error result)))
    (t/is (= "bye world" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-json-string-edits
  (spit "target/test-tools-edit.txt" "alpha beta")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits "[{\"oldText\":\"beta\",\"newText\":\"BETA\"}]"})]
    (t/is (not (:is-error result)))
    (t/is (= "alpha BETA" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-string-args-json
  ;; String args only reach execute-tool when the LLM emitted malformed
  ;; tool-call arguments (pi: parseStreamingJson) — valid JSON strings must
  ;; parse to a map and work normally.
  (spit "target/test-tools-read.txt" "string args")
  (let [result (tools/execute-tool "read" "{\"path\": \"target/test-tools-read.txt\"}")]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "string args"))))

(t/deftest test-tool-string-args-malformed
  ;; Malformed arguments JSON used to crash edit with "java.lang.String
  ;; cannot be cast to clojure.lang.Associative" (assoc on a string in
  ;; normalize-edits). It must degrade to {} and fail validation instead.
  (let [result (tools/execute-tool "edit" "{malformed-json")]
    (t/is (:is-error result))
    (t/is (not (re-find #"ClassCastException" (:content result))))
    (t/is (re-find #"at least one replacement" (:content result)))))

(t/deftest test-tool-edit-empty-edits
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "at least one replacement"))))

(t/deftest test-tool-read-offset-1-indexed
  (spit "target/test-tools-read.txt" "line1\nline2\nline3\nline4")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 2 :limit 2})]
    (t/is (.contains (:content result) "line2"))
    (t/is (.contains (:content result) "line3"))
    (t/is (not (.contains (:content result) "line1")))))

(t/deftest test-tool-read-truncation-metadata
  (spit "target/test-tools-read.txt" (clojure.string/join "\n" (range 1 3001)))
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt"})]
    (t/is (= 3000 (:total-lines (:truncation result))))
    (t/is (= 2000 (:output-lines (:truncation result))))
    (t/is (= :lines (:truncated-by (:truncation result))))))

(t/deftest test-tool-write-parentless-path
  ;; Regression: (fs/create-dirs (fs/parent f)) with a parentless path → nil → crash
  (let [result (tools/execute-tool "write" {:path "target/parentless-write.txt" :content "x"})]
    (t/is (not (:is-error result)) "parentless path should succeed")
    (t/is (.contains (:content result) "Successfully wrote"))))

(t/deftest test-tool-read-beyond-eof
  (spit "target/test-tools-read.txt" "a\nb\nc")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 99})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "beyond end of file"))))

(t/deftest test-tool-read-image
  ;; Pi detects images by magic bytes (mime.ts), not extension — fake bytes are text
  (let [png-bytes (byte-array [(unchecked-byte 0x89) 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
                               0 0 0 0x0D  0x49 0x48 0x44 0x52
                               0 0 0 16  0 0 0 16  8 2 0 0 0
                               0 0 0 0  0 0 0 16  0x49 0x44 0x41 0x54 0 0])]
    (java.nio.file.Files/write (.toPath (java.io.File. "target/test-tools-read.png")) png-bytes
                               (into-array java.nio.file.OpenOption
                                           [java.nio.file.StandardOpenOption/CREATE
                                            java.nio.file.StandardOpenOption/TRUNCATE_EXISTING]))
    (let [result (tools/execute-tool "read" {:path "target/test-tools-read.png"})]
      (t/is (some? (:images result)))
      (t/is (.contains (:content result) "Read image file"))
      (t/is (.contains (:content result) "image/png")))))

(t/deftest test-tool-read-gif-signature
  ;; Regression (pi 47a18e37b): only the complete GIF87a/GIF89a header is an
  ;; image — a text file merely starting with "GIF" is text.
  (spit "target/test-tools-read-gif.txt" "GIF is an image format, not this file")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read-gif.txt"})]
    (t/is (nil? (:images result)))
    (t/is (.contains (:content result) "GIF is an image format")))
  (doseq [signature ["GIF87a" "GIF89a"]]
    (spit "target/test-tools-read-gif.gif" (str signature " fake image bytes"))
    (let [result (tools/execute-tool "read" {:path "target/test-tools-read-gif.gif"})]
      (t/is (= "image/gif" (-> result :images first :mime-type)))
      (t/is (.contains (:content result) "image/gif")))))

(ns kmet.app.session.test-store
  "Storage: honest round-trips (ids, parent chains, UTF-8), the append
   journal, torn-tail repair, session names, bash results, and custom
   entries. Every case drives the façade (kmet.app.session.core)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.session.core :as s]
            [kmet.app.session.test-support :as support]
            [kmet.test-utils :refer [slash]]))

(t/use-fixtures :once support/with-clean-test-dir)

(t/deftest test-session-create
  (let [session (s/create-session support/test-dir)]
    (t/is (string? (:id session)))
    (t/is (string? (:file session)))
    (t/is (instance? clojure.lang.Atom (:entries session)))
    (t/is (instance? clojure.lang.Atom (:leaf-id session)))
    (t/is (empty? @(:entries session)))
    (t/is (nil? @(:leaf-id session)))
    (t/is (not (.exists (io/file (:file session))))
          "lazy creation: no file until the first assistant message (G4)")))

(t/deftest test-session-header
  ;; G1: header carries type/version/id/created-at/cwd/parent-session
  (let [session (s/create-session support/test-dir
                                  {:cwd "/home/user/proj"
                                   :parent-session "/home/user/proj/parent.ednl"})]
    (t/is (= :session (:type (:header session))))
    (t/is (= 1 (:version (:header session))))
    (t/is (= (:id session) (:id (:header session))))
    (t/is (= "/home/user/proj" (:cwd (:header session))))
    (t/is (= "/home/user/proj/parent.ednl" (:parent-session (:header session))))
    (t/is (string? (:created-at (:header session)))))
  (t/is (nil? (:parent-session (:header (s/create-session support/test-dir))))
        "parent-session omitted for a root session")
  (t/is (= (str (fs/cwd)) (:cwd (:header (s/create-session support/test-dir))))
        "cwd defaults to the process cwd"))

(t/deftest test-session-lazy-creation
  ;; G4: no file until the first assistant message — user/bash entries
  ;; accumulate in memory; the assistant entry persists header + everything
  ;; in order (pi: _persist)
  (let [dir (str "target/test-sess-lazy-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (t/is (not (fs/exists? (:file sess))) "no file after create")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :bash :command "ls" :output "" :exit-code 0})
      (t/is (not (fs/exists? (:file sess)))
            "no file with only user/bash entries")
      (s/append-entry sess {:role :assistant :content "a"})
      (t/is (fs/exists? (:file sess))
            "file appears on the first assistant message")
      (let [lines (str/split-lines (slurp (:file sess)))]
        (t/is (= :session (:type (edn/read-string (first lines))))
              "header is the first line")
        (t/is (= 4 (count lines)) "header + 3 entries persisted in order"))
      (let [loaded (s/load-session (:file sess))]
        (t/is (= 3 (count @(:entries loaded))))
        (t/is (= (:id sess) (:id loaded)) "id round-trips via the header")
        (t/is (= (:header sess) (:header loaded)) "header round-trips"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-legacy-file-loads
  ;; Files without a header (pre-G1 format) still load: entries-only, :header
  ;; nil, :id derived from the filename
  (let [dir (str "target/test-sess-legacy-" (System/currentTimeMillis))
        sess (s/create-session dir)
        file (:file sess)]
    (try
      (spit file "{:id \"1\" :role :user :content \"a\"}\n")
      (spit file "{:id \"2\" :role :assistant :content \"b\"}\n" :append true)
      (let [loaded (s/load-session file)]
        (t/is (nil? (:header loaded)))
        (t/is (= 2 (count @(:entries loaded))))
        (t/is (= (str/replace (fs/file-name file) #"\.ednl$" "") (:id loaded))
              "legacy id derives from the filename"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-append-entry
  (let [session (s/create-session support/test-dir)
        entry (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})]
    (t/is (string? (:id entry)))
    (t/is (nil? (:parent-id entry)))
    (t/is (= :user (:role entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (string? @(:leaf-id session)))))

(t/deftest test-session-write-round-trips-utf8
  ;; The byte-stream writer behind kmet.libs.fs/publish! is explicitly UTF-8,
  ;; matching the reader; non-ASCII message content survives save + reload.
  (let [dir (str "target/test-sess-utf8-" (System/currentTimeMillis))
        sess (s/create-session dir)
        text "héllo — ünïcode ✓ 日本語"]
    (try
      (s/append-entry sess {:role :user :content [{:type :text :text text}]})
      (s/append-entry sess {:role :assistant :content [{:type :text :text text}]})
      (let [loaded (s/load-session (:file sess))]
        (t/is (= text (get-in (first @(:entries loaded)) [:content 0 :text])))
        (t/is (= text (get-in (second @(:entries loaded)) [:content 0 :text]))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-concurrent-appends
  ;; Concurrent appends (a ! bash result on its future thread + a submitted
  ;; message on the agent thread) must not orphan sibling entries from the
  ;; branch — pi serializes all mutations through storage.enqueue; kmet locks
  ;; the session. Regression: this used to lose ~38% of entries on reload.
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "seed"}]})
    ;; An assistant entry persists the file first, so the concurrent appends
    ;; below exercise the serialized append-to-file path (lazy creation G4
    ;; would otherwise hold everything in memory).
    (s/append-entry session {:role :assistant :content [{:type :text :text "boot"}]})
    (let [futures (doall
                   (for [_ (range 200)]
                     [(future (s/append-entry session {:role :bash :command "ls"}))
                      (future (s/append-entry session {:role :user :content [{:type :text :text "followup"}]}))]))]
      (doseq [[f1 f2] futures] @f1 @f2))
    (let [loaded (s/load-session (:file session))
          n-branch (count (s/get-branch loaded))
          n-total (count @(:entries loaded))]
      (t/is (= n-branch n-total)))))

(t/deftest test-session-append-multiple
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (t/is (= 3 (count @(:entries session))))))

(t/deftest test-session-parent-id-chain
  (let [session (s/create-session support/test-dir)
        e1 (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
        e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})]
    (t/is (= (:id e1) (:parent-id e2)))))

(t/deftest test-session-get-branch
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (let [branch (s/get-branch session)]
      (t/is (= 3 (count branch)))
      (t/is (= :user (:role (first branch))))
      (t/is (= :user (:role (last branch)))))))

(t/deftest test-session-get-branch-empty
  (let [session (s/create-session support/test-dir)]
    (t/is (empty? (s/get-branch session)))))

(t/deftest test-session-save-and-load
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "ping"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "pong"}]})
    (let [file (:file session)]
      (t/is (.exists (io/file file)))
      (let [loaded (s/load-session file)]
        (t/is (= (:id session) (:id loaded)))
        (t/is (= (:header session) (:header loaded)) "header round-trips")
        (t/is (= 2 (count @(:entries loaded))))
        (t/is (= :user (:role (first @(:entries loaded)))))
        (t/is (= :assistant (:role (second @(:entries loaded)))))))))

(t/deftest test-session-load-nonexistent
  (t/is (thrown? Exception (s/load-session "nonexistent.ednl"))))

(t/deftest test-encode-cwd
  ;; G2: pi getDefaultSessionDirPath — `--` + cwd with leading slash stripped
  ;; and / \ : replaced by -
  (t/is (= "--home-user-proj--" (s/encode-cwd "/home/user/proj")))
  (t/is (= "--home-user-proj--" (s/encode-cwd "\\home\\user\\proj")))
  (t/is (= "--C--Users-dev--" (s/encode-cwd "C:\\Users\\dev")))
  (t/is (= "--a-b--" (s/encode-cwd "a/b"))))

(t/deftest test-session-dir-for-cwd-needs-a-base
  ;; a blank base makes the encoded name a relative path, so the directory would
  ;; be created under the process cwd instead of a sessions dir
  (t/is (= "/base/--home-proj--" (s/session-dir-for-cwd "/base" "/home/proj")))
  (doseq [base [nil ""]]
    (t/is (thrown-with-msg? Exception #"base sessions dir"
                            (s/session-dir-for-cwd base "/home/proj")))))

;; ─── Regression: corrupted entries ────────────────────────────────────────

(t/deftest test-session-load-with-corrupt-entry
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    ;; Write: valid, broken (unclosed map), valid
    (spit file "{:id \"1\" :role :user :content \"hello\"}\n")
    (spit file "{:bad \"entry\"\n" :append true)  ;; unclosed map → parse error
    (spit file "{:id \"3\" :role :assistant :content \"world\"}\n" :append true)
    (let [loaded (support/silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (= 2 (count entries)) "Should skip corrupt entry, keep both valid ones")
      (t/is (= "1" (:id (first entries))))
      (t/is (= "3" (:id (second entries))))
      (t/is (= :user (:role (first entries))))
      (t/is (= :assistant (:role (second entries)))))))

(t/deftest test-session-load-with-multiple-corrupt-entries
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    ;; Write: valid, broken, valid, broken, valid
    (spit file "{:id \"1\" :role :user :content \"a\"}\n")
    (spit file "garbage!@\n" :append true)         ;; invalid characters
    (spit file "{:id \"3\" :role :user :content \"b\"}\n" :append true)
    (spit file "{{:id \"4\"}\n" :append true)     ;; double brace → parse error
    (spit file "{:id \"5\" :role :assistant :content \"c\"}\n" :append true)
    (let [loaded (support/silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (= 3 (count entries)) "Should skip all corrupt entries, keep all valid ones")
      (t/is (= "1" (:id (nth entries 0))))
      (t/is (= "3" (:id (nth entries 1))))
      (t/is (= "5" (:id (nth entries 2)))))))

(t/deftest test-session-load-all-corrupt
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    (spit file "{{{{{{\n")
    (spit file "[invalid}^&*\n" :append true)
    (let [loaded (support/silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (empty? entries) "Should return empty entries when all lines are corrupt"))))

(t/deftest test-session-load-empty-file
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    (spit file "")
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (empty? entries) "Empty file should produce empty entries"))))

(t/deftest test-session-load-with-blank-lines
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    (spit file "{:id \"1\" :role :user :content \"hi\"}\n")
    (spit file "\n" :append true)  ;; blank line
    (spit file "  \n" :append true)  ;; whitespace line
    (spit file "{:id \"4\" :role :assistant :content \"bye\"}\n" :append true)
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (= 2 (count entries)) "Should skip blank lines")
      (t/is (= "1" (:id (first entries))))
      (t/is (= "4" (:id (second entries)))))))

;; ─── G13: streaming load, torn-tail + unterminated-tail repair ───────────

(t/deftest test-session-streaming-load
  ;; G13: load streams in 1 MB chunks — a session larger than one chunk
  ;; round-trips whole, including entries straddling a chunk boundary
  (let [dir (str "target/test-sess-stream-" (System/currentTimeMillis))
        sess (s/create-session dir)
        n 500  ;; ~1.3 MB of lines (500 × 2.6 KB), well past one 1 MB chunk —
              ;; fewer, longer lines keep the same total size with a fraction
              ;; of the per-line EDN parse cost
        entry (fn [i] {:role :user :content [{:type :text :text (str "msg " i " " (support/pad-string 2500 \x))}]})]
    (try
      (s/append-entry sess {:role :assistant :content "start"})  ;; persist (lazy G4)
      (spit (:file sess)
            (apply str (map prn-str (map entry (range n))))
            :append true)
      (let [loaded (s/load-session (:file sess))
            entries @(:entries loaded)]
        (t/is (= (inc n) (count entries)) "header + 1 + N entries all loaded")
        (t/is (= (:header sess) (:header loaded)) "header intact")
        (t/is (= "msg 0 " (subs (get-in (second entries) [:content 0 :text]) 0 6))
              "the entry right after the chunk boundary loads whole"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-torn-tail-repair
  ;; G13: a partial final line (crashed append) is dropped and the valid
  ;; prefix published atomically — the file on disk is repaired, not just
  ;; skipped-with-warning
  (let [dir (str "target/test-sess-torn-" (System/currentTimeMillis))
        file (str dir "/sess.ednl")]
    (try
      (fs/create-dirs dir)
      (spit file "{:type :session :version 1 :id \"abc\" :created-at \"2025-01-01T00:00:00Z\" :cwd \"/tmp\"}\n")
      (spit file "{:id \"1\" :role :user :content \"hello\"}\n" :append true)
      (spit file "{:id \"2\" :role :assistant :content \"wor" :append true)  ;; torn
      (let [loaded (support/silent-stderr #(s/load-session file))
            entries @(:entries loaded)]
        (t/is (= 1 (count entries)) "torn entry dropped")
        (t/is (= "1" (:id (first entries))))
        (t/is (= "abc" (:id loaded)) "header still read"))
      (let [remaining (str/split-lines (slurp file))]
        (t/is (= 2 (count remaining)) "file repaired: header + 1 entry")
        (t/is (= "1" (:id (edn/read-string (second remaining))))
              "the entry after the torn line survives the repair"))
      (let [loaded-again (s/load-session file)]
        (t/is (= 1 (count @(:entries loaded-again)))
              "reload after repair: no further warnings/repairs"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-torn-tail-only-line
  ;; G13: a file consisting solely of a torn line repairs to an empty
  ;; session (no entries, no header)
  (let [dir (str "target/test-sess-torn-only-" (System/currentTimeMillis))
        file (str dir "/sess.ednl")]
    (try
      (fs/create-dirs dir)
      (spit file "{:id \"1\" :role :user")
      (let [loaded (support/silent-stderr #(s/load-session file))]
        (t/is (empty? @(:entries loaded)))
        (t/is (nil? (:header loaded))))
      (t/is (= "\n" (slurp file)) "file repaired to an empty session file")
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-torn-tail-with-earlier-corruption
  ;; G13: repair drops only the torn tail; malformed middle lines are
  ;; preserved (pi v4: physicalLines.slice — the tail is the unacknowledged
  ;; partial append, middle corruption is skipped-with-warning not erased)
  (let [dir (str "target/test-sess-torn-mid-" (System/currentTimeMillis))
        file (str dir "/sess.ednl")]
    (try
      (fs/create-dirs dir)
      (spit file "{:id \"1\" :role :user :content \"a\"}\n")
      (spit file "garbage!@\n" :append true)  ;; malformed middle line
      (spit file "{:id \"2\" :role :assistant :content \"b" :append true)  ;; torn
      (let [loaded (support/silent-stderr #(s/load-session file))
            entries @(:entries loaded)]
        (t/is (= 1 (count entries)))
        (t/is (= "1" (:id (first entries)))))
      (let [remaining (str/split-lines (slurp file))]
        (t/is (= 2 (count remaining)) "torn line gone, garbage line kept")
        (t/is (= "garbage!@" (second remaining))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-unterminated-tail-repair
  ;; G13: a valid final line without a trailing newline gets one appended on
  ;; load, so a later append can't glue onto it (pi v4 unterminated-tail
  ;; repair)
  (let [dir (str "target/test-sess-unterm-" (System/currentTimeMillis))
        file (str dir "/sess.ednl")]
    (try
      (fs/create-dirs dir)
      (spit file "{:id \"1\" :role :user :content \"hi\"}")  ;; no \n
      (let [loaded (s/load-session file)]
        (t/is (= 1 (count @(:entries loaded))))
        (t/is (str/ends-with? (slurp file) "\n")
              "trailing newline appended"))
      ;; the repair keeps subsequent appends on their own line
      (spit file "{:id \"2\" :role :assistant :content \"bye\"}\n" :append true)
      (let [loaded (s/load-session file)]
        (t/is (= 2 (count @(:entries loaded)))
              "append after repair stays a separate line"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-streaming-load-utf8-boundary
  ;; G13: streaming decodes UTF-8 incrementally (pi's StringDecoder) — a
  ;; multi-byte character straddling the 1 MB chunk boundary loads intact
  ;; instead of being replaced with U+FFFD
  (let [dir (str "target/test-sess-utf8-" (System/currentTimeMillis))
        file (str dir "/sess.ednl")
        ;; 漢's 3 bytes span byte 1048575..1048577 — right across the 1 MB
        ;; read boundary
        line (prn-str {:id "1" :role :user
                       :content (str (support/pad-string 1048541 \a) "漢")})]
    (try
      (fs/create-dirs dir)
      (spit file (str line "\n"))
      (let [loaded (s/load-session file)
            content (:content (first @(:entries loaded)))]
        (t/is (= 1 (count @(:entries loaded))))
        (t/is (= \漢 (last content))
              "multi-byte char across the chunk boundary intact"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-compact-with-summary
  (let [dir (str "target/test-sess-cws-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (doseq [i (range 6)]
        (s/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
      (let [entries (s/get-branch sess)
            first-kept-id (:id (nth entries 3))
            summary-entry (s/compact-with-summary! sess "SUMMARY" first-kept-id)
            branch (s/get-branch sess)
            context (s/build-context sess)]
        (t/is (some? summary-entry))
        (t/is (= :compaction (:role summary-entry)))
        (t/is (= "SUMMARY" (:summary summary-entry)))
        (t/is (= first-kept-id (:first-kept-id summary-entry)))
        (t/is (= 7 (count branch)) "append-only: 6 entries + compaction")
        (t/is (= (:id (last entries)) (:parent-id summary-entry))
              "compaction is a child of the previous leaf")
        (t/is (= 4 (count context)) "context = compaction + 3 kept entries")
        (t/is (= :compaction (:role (first context))))
        (t/is (= first-kept-id (:id (second context))) "first kept entry follows")
        (t/is (= (:id (first entries)) (:id (first branch)))
              "summarized history stays in the branch")
        ;; persist the file (lazy G4: needs an assistant message) so the
        ;; reload checks below have something to read
        (s/append-entry sess {:role :assistant :content "done"})
        ;; file reloadable, context survives a reload
        (let [loaded (s/load-session (:file sess))]
          (t/is (= 8 (count @(:entries loaded)))
                "6 user + compaction + the done entry persist")
          (t/is (= 5 (count (s/build-context loaded)))
                "context = compaction + 3 kept + done")))
      (finally (fs/delete-tree dir)))))

;; ─── Atomic rewrite (pi: temp-file publication) ──────────────────────────

(t/deftest test-replace-entries
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "old"})
    (s/replace-entries! session [{:role :user :content "new1"}
                                 {:role :assistant :content "new2"}])
    (let [branch (s/get-branch session)]
      (t/is (= 2 (count branch)))
      (t/is (= "new1" (-> branch first :content)))
      (t/is (= "new2" (-> branch second :content)))
      (t/is (= (:id (first branch)) (:parent-id (second branch)))
            "linear chain rebuilt")
      (let [loaded (s/load-session (:file session))]
        (t/is (= 2 (count @(:entries loaded))))))))

;; ─── Session display name (pi: /name command) ────────────────────────────

(t/deftest test-sanitize-session-name
  (t/is (= "my session" (s/sanitize-session-name " my\nsession\r\n "))
        "newlines collapse to spaces, then trim")
  (t/is (= "" (s/sanitize-session-name "  \n "))
        "whitespace-only names sanitize to empty"))

(t/deftest test-append-session-info
  (let [session (s/create-session support/test-dir)
        entry (s/append-session-info! session "my session")]
    (t/is (= :session_info (:role entry)))
    (t/is (= "my session" (:name entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (= 1 (count (s/get-branch session)))
          "session_info participates in the branch tree")))

(t/deftest test-get-session-name
  (let [session (s/create-session support/test-dir)]
    (t/is (nil? (s/get-session-name session)))
    (s/append-entry session {:role :user :content "hi"})
    (s/append-session-info! session "alpha")
    (t/is (= "alpha" (s/get-session-name session)))
    (s/append-session-info! session "beta")
    (t/is (= "beta" (s/get-session-name session))
          "latest session_info entry wins")))

(t/deftest test-get-session-name-empty-clears
  (let [session (s/create-session support/test-dir)]
    (s/append-session-info! session "alpha")
    (s/append-session-info! session "   ")
    (t/is (nil? (s/get-session-name session))
          "empty name explicitly clears the title")))

(t/deftest test-session-name-persists
  (let [session (s/create-session support/test-dir)
        file (:file session)]
    (s/append-entry session {:role :user :content "hi"})
    (s/append-entry session {:role :assistant :content "hello"}) ;; persist (lazy G4)
    (s/append-session-info! session "my session")
    (let [loaded (s/load-session file)]
      (t/is (= "my session" (s/get-session-name loaded))))))

;; ─── Bash result recording ─────────────────────────────────────────────────

(t/deftest test-make-bash-entry
  (let [entry (s/make-bash-entry "echo hi" {:output "hi\n" :exit-code 0} false)]
    (t/is (= :bash (:role entry)))
    (t/is (= "echo hi" (:command entry)))
    (t/is (= "hi\n" (:output entry)))
    (t/is (= 0 (:exit-code entry)))
    (t/is (false? (:cancelled entry)))
    (t/is (false? (:exclude-from-context? entry)))
    (t/is (false? (:truncated entry)))
    (t/is (nil? (:full-output-path entry))))
  (t/is (= true (:exclude-from-context?
                 (s/make-bash-entry "ls" {:output "" :exit-code 1} true))))
  (t/is (= "" (:output (s/make-bash-entry "ls" {} false))))
  (t/is (= true (:cancelled (s/make-bash-entry "ls" {:cancelled true} false)))))

(t/deftest test-record-bash-result
  (let [session-dir (io/file support/test-dir "bash")
        sess (s/create-session (str session-dir))
        result {:output "clean\n" :exit-code 0}
        entry (s/record-bash-result! sess "git st" result false)]
    (t/is (= :bash (:role entry)))
    (t/is (= "git st" (:command entry)))
    (t/is (= 1 (count @(:entries sess))))
    (t/is (= (:id entry) (:id (first @(:entries sess)))))))

;; ─── Branching (G17): branch!/reset-leaf! ──────────────────────────────────

(t/deftest test-branch-moves-leaf
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (s/append-entry session {:role :assistant :content "a1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          _ (s/append-entry session {:role :assistant :content "a2"})]
      ;; branch back to the second user message: next append is its child
      (s/branch! session (:id u2))
      (t/is (= (:id u2) @(:leaf-id session)))
      (t/is (= [:user :assistant :user] (map :role (s/get-branch session))))
      (let [a3 (s/append-entry session {:role :assistant :content "a3"})]
        (t/is (= (:id u2) (:parent-id a3))
              "append after branch becomes a child of the new leaf")
        (t/is (= :assistant (:role (last (s/get-branch session)))))
        (t/is (= 5 (count @(:entries session)))
              "old branch entries remain in the file")))))

(t/deftest test-branch-unknown-entry-throws
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (t/is (thrown? clojure.lang.ExceptionInfo (s/branch! session "nope")))))

(t/deftest test-reset-leaf
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (s/reset-leaf! session)
    (t/is (nil? @(:leaf-id session)))
    (let [u2 (s/append-entry session {:role :user :content "q2"})]
      (t/is (nil? (:parent-id u2))
            "append after reset becomes a new root")
      (t/is (= [u2] (s/get-branch session))))))

;; ─── Runtime working directory (pi: AgentSession._cwd) ────────────────────

(t/deftest test-session-cwd
  (t/testing "a session's runtime cwd is its recorded header cwd"
    (let [dir (str (fs/absolutize (str support/test-dir "-cwd")))
          other (str dir "-other")]
      (try
        (fs/create-dirs other)
        (t/testing "the recorded cwd when the directory exists"
          (let [s (s/create-session dir {:cwd other})]
            (t/is (= (slash other) (slash (s/session-cwd s))))))
        (t/testing "nil when it is gone (pi: MissingSessionCwdError — the caller falls back)"
          (let [s (s/create-session dir {:cwd (str dir "/gone")})]
            (t/is (nil? (s/session-cwd s)))))
        (t/testing "nil without a header (legacy/empty)"
          (let [s (s/create-session dir)]
            (t/is (nil? (s/session-cwd (assoc s :header nil))))))
        (finally (fs/delete-tree dir) (fs/delete-tree other))))))

;; ─── Model & thinking changes (G6 — pi: appendModelChange /
;;     appendThinkingLevelChange / getSessionContextSettings) ───────────────

(t/deftest test-model-and-thinking-change-entries
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-model-change! session :anthropic "claude-sonnet")
    (s/append-thinking-level-change! session :high)
    ;; persisted on disk (assistant message triggers the lazy write)
    (s/append-entry session {:role :assistant :content "a"})
    (let [loaded (s/load-session (:file session))
          branch (s/get-branch loaded)]
      (t/is (= [:user :model-change :thinking-level-change :assistant]
               (mapv :role branch)))
      (t/is (= :anthropic (:provider (nth branch 1))))
      (t/is (= "claude-sonnet" (:model (nth branch 1))))
      (t/is (= :high (:thinking-level (nth branch 2))))
      ;; excluded from LLM context and message counts
      (t/is (= [:user :assistant] (mapv :role (mapcat s/context-messages branch)))
            "change entries project to no context messages")
      (t/is (= 2 (s/get-message-count loaded))))))

;; ─── G9/G10: custom + custom_message entries ──────────────────────────────

(t/deftest test-custom-entry
  ;; G9: custom entries are extension state — persisted, never in context
  (let [session (s/create-session support/test-dir)
        e (s/append-custom-entry! session :my-state {:count 1})]
    (t/is (= :custom (:role e)))
    (t/is (= :my-state (:custom-type e)))
    (t/is (= {:count 1} (:data e)))
    (t/is (empty? (s/context-messages e)) "custom entries excluded from context")
    (t/is (= [e] (s/get-custom-entries session :my-state)))
    (t/is (empty? (s/get-custom-entries session :other)) "type filter")
    ;; state survives save/load
    (let [loaded (s/load-session (:file (do (s/append-entry session {:role :assistant :content "a"})
                                            session)))]
      (t/is (= :my-state (:custom-type (first (s/get-custom-entries loaded :my-state))))))))

(t/deftest test-custom-entry-branch-scoped
  ;; get-custom-entries follows the ACTIVE branch (pi: getBranch)
  (let [session (s/create-session support/test-dir)
        q (s/append-entry session {:role :user :content "q"})
        _ (s/append-custom-entry! session :my-state {:v 1})]
    (s/branch! session (:id q))
    (t/is (empty? (s/get-custom-entries session :my-state))
          "abandoned branch state not visible"))
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/branch! session (:id (first (s/get-branch session))))
    (s/append-custom-entry! session :my-state {:v 2})
    (t/is (= 1 (count (s/get-custom-entries session :my-state))))
    (t/is (= {:v 2} (:data (first (s/get-custom-entries session :my-state)))))))

(t/deftest test-all-custom-entries-session-wide
  ;; get-all-custom-entries spans abandoned branches (pi: getEntries);
  ;; get-custom-entries stays branch-scoped (pi: getBranch)
  (let [session (s/create-session support/test-dir)
        q (s/append-entry session {:role :user :content "q"})
        _ (s/append-custom-entry! session :my-state {:v 1})]
    (s/branch! session (:id q))
    (t/is (empty? (s/get-custom-entries session :my-state))
          "branch read does not see abandoned state")
    (t/is (= [{:v 1}] (mapv :data (s/get-all-custom-entries session :my-state)))
          "session read sees abandoned state")
    (t/is (empty? (s/get-all-custom-entries session :other)) "type filter")))

(t/deftest test-custom-message-entry
  ;; G10: custom_message entries participate in context as a :custom-role
  ;; message; display flag + details carried through; excluded from counts
  (let [session (s/create-session support/test-dir)
        e (s/append-custom-message-entry! session :note "hello" true {:x 1})
        msg (first (s/context-messages e))]
    (t/is (= :custom-message (:role e)))
    (t/is (= :note (:custom-type e)))
    (t/is (= true (:display e)))
    (t/is (= {:x 1} (:details e)))
    (t/is (= :custom (:role msg)) "projects to a custom-role message")
    (t/is (= [{:type :text :text "hello"}] (:content msg))
          "string content normalized to a text block")
    (t/is (= :note (:custom-type msg)))
    (t/is (= true (:display msg)))
    (t/is (= {:x 1} (:details msg)))
    (t/is (= 0 (s/get-message-count session)) "custom entries are not messages")))

(t/deftest test-custom-message-entry-block-content
  ;; block content passes through unmodified
  (let [session (s/create-session support/test-dir)
        e (s/append-custom-message-entry! session :img
                                          [{:type :image :data "aa" :mime-type "image/png"}]
                                          true)
        msg (first (s/context-messages e))]
    (t/is (= [{:type :image :data "aa" :mime-type "image/png"}] (:content msg)))))

(t/deftest test-custom-message-entry-nil-content
  ;; nil content defaults to an empty block vector (pi: content ?? [])
  (let [session (s/create-session support/test-dir)
        e (s/append-custom-message-entry! session :quiet nil false)
        msg (first (s/context-messages e))]
    (t/is (= [] (:content msg)))
    (t/is (= false (:display msg)))))

(t/deftest test-entry-id-format
  ;; G12: ids are <hex-ms>-<8-hex> — time-ordered prefix + 32-bit random,
  ;; so same-ms cross-process collisions are negligible
  (let [session (s/create-session support/test-dir)
        e (s/append-entry session {:role :user :content "q"})]
    (t/is (re-matches #"[0-9a-f]+-[0-9a-f]{8}" (:id e)))
    (t/is (re-matches #"[0-9a-f]+-[0-9a-f]{8}" (:id session)) "session id too")
    (t/is (not= (:id e) (:id session)))))

(t/deftest test-custom-entries-persist-and-replay
  ;; G10: custom_message entries with display=true replay into chat history
  ;; data as labeled info messages; display=false and :custom state are
  ;; skipped (covered by replay-branch! in interactive mode — here we check
  ;; the projection + branch content survive a round-trip)
  (let [dir (str "target/test-sess-custom-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "q"})
      (s/append-custom-message-entry! sess :note "hello" true {:x 1})
      (s/append-custom-entry! sess :state {:v 1})
      (s/append-entry sess {:role :assistant :content "a"})
      (let [loaded (s/load-session (:file sess))
            roles (mapv :role (s/get-branch loaded))
            ctx-roles (mapv :role (mapcat s/context-messages (s/get-branch loaded)))]
        (t/is (= [:user :custom-message :custom :assistant] roles))
        (t/is (= [:user :custom :assistant] ctx-roles)
              "custom state excluded from context, custom_message projected")
        (t/is (= "hello" (:content (nth (s/get-branch loaded) 1)))
              "entry content persisted verbatim"))
      (finally (fs/delete-tree dir)))))

(ns kmet.app.test-session
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.session :as s]
            [kmet.ai.usage :as usage]
            [kmet.test-utils :refer [slash]]))

(def test-dir "target/test-sessions")

;; The list-sessions test asserts per file, so a stale dir would make the
;; assertion count grow across runs — start each run from a clean dir.
(t/use-fixtures :once
  (fn [f]
    (fs/delete-tree test-dir)
    (f)))

(defn- silent-stderr
  "Run f with stderr redirected — suppresses the expected warning
   output from deliberately corrupt test session files."
  [f]
  (binding [*err* (java.io.StringWriter.)]
    (f)))

(defn- pad-string
  "A string of N copies of C. char-array + Arrays/fill is one native call,
   ~100× faster than (apply str (repeat n c)) in babashka — used to build
   the >1 MB test payloads."
  [n c]
  (let [a (char-array n)]
    (java.util.Arrays/fill a c)
    (String. a)))

(t/deftest test-session-create
  (let [session (s/create-session test-dir)]
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
  (let [session (s/create-session test-dir
                                  {:cwd "/home/user/proj"
                                   :parent-session "/home/user/proj/parent.ednl"})]
    (t/is (= :session (:type (:header session))))
    (t/is (= 1 (:version (:header session))))
    (t/is (= (:id session) (:id (:header session))))
    (t/is (= "/home/user/proj" (:cwd (:header session))))
    (t/is (= "/home/user/proj/parent.ednl" (:parent-session (:header session))))
    (t/is (string? (:created-at (:header session)))))
  (t/is (nil? (:parent-session (:header (s/create-session test-dir))))
        "parent-session omitted for a root session")
  (t/is (= (str (fs/cwd)) (:cwd (:header (s/create-session test-dir))))
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
  (let [session (s/create-session test-dir)
        entry (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})]
    (t/is (string? (:id entry)))
    (t/is (nil? (:parent-id entry)))
    (t/is (= :user (:role entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (string? @(:leaf-id session)))))

(t/deftest test-session-concurrent-appends
  ;; Concurrent appends (a ! bash result on its future thread + a submitted
  ;; message on the agent thread) must not orphan sibling entries from the
  ;; branch — pi serializes all mutations through storage.enqueue; kmet locks
  ;; the session. Regression: this used to lose ~38% of entries on reload.
  (let [session (s/create-session test-dir)]
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
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (t/is (= 3 (count @(:entries session))))))

(t/deftest test-session-parent-id-chain
  (let [session (s/create-session test-dir)
        e1 (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
        e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})]
    (t/is (= (:id e1) (:parent-id e2)))))

(t/deftest test-session-get-branch
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (let [branch (s/get-branch session)]
      (t/is (= 3 (count branch)))
      (t/is (= :user (:role (first branch))))
      (t/is (= :user (:role (last branch)))))))

(t/deftest test-session-get-branch-empty
  (let [session (s/create-session test-dir)]
    (t/is (empty? (s/get-branch session)))))

(t/deftest test-session-save-and-load
  (let [session (s/create-session test-dir)]
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

(t/deftest test-session-list-sessions
  (let [s1 (s/create-session test-dir)
        _ (s/append-entry s1 {:role :user :content "q"})
        _ (s/append-entry s1 {:role :assistant :content "a"})
        s2 (s/create-session test-dir)
        _ (s/append-entry s2 {:role :user :content "q"})
        _ (s/append-entry s2 {:role :assistant :content "a"})
        files (s/list-sessions test-dir)]
    (t/is (sequential? files))
    (t/is (>= (count files) 2))
    (doseq [f files]
      (t/is (str/ends-with? f ".ednl")))))

(t/deftest test-encode-cwd
  ;; G2: pi getDefaultSessionDirPath — `--` + cwd with leading slash stripped
  ;; and / \ : replaced by -
  (t/is (= "--home-user-proj--" (s/encode-cwd "/home/user/proj")))
  (t/is (= "--home-user-proj--" (s/encode-cwd "\\home\\user\\proj")))
  (t/is (= "--C--Users-dev--" (s/encode-cwd "C:\\Users\\dev")))
  (t/is (= "--a-b--" (s/encode-cwd "a/b"))))

(t/deftest test-session-list-cwd-dirs
  ;; G2: sessions live in <base>/<--cwd-->/; list-sessions on the base walks
  ;; the cwd subdirs (pi: listAll), a cwd dir lists flat, and legacy flat
  ;; files in the base stay visible (no regression on upgrade)
  (let [dir (str "target/test-sess-cwd-" (System/currentTimeMillis))
        cwd-a (s/session-dir-for-cwd dir "/home/user/proj-a")
        cwd-b (s/session-dir-for-cwd dir "/home/user/proj-b")
        sa (s/create-session cwd-a)
        _ (s/append-entry sa {:role :assistant :content "a1"})
        sb (s/create-session cwd-b)
        _ (s/append-entry sb {:role :assistant :content "b1"})
        ;; a legacy pre-G2 session file placed flat in the base dir
        legacy (s/create-session dir)
        _ (s/append-entry legacy {:role :assistant :content "legacy"})]
    (try
      (t/is (= 3 (count (s/list-sessions dir)))
            "base listing walks cwd subdirs + legacy flat files")
      (t/is (= 1 (count (s/list-sessions cwd-a))) "cwd dir lists flat")
      (finally (fs/delete-tree dir)))))

(t/deftest test-find-most-recent-session
  ;; G23/continue: pi findMostRecentSession — header-based discovery in the
  ;; cwd dir, scoped by header :cwd; legacy headerless files and other-cwd
  ;; sessions excluded, no fallback
  (let [dir (str "target/test-sess-mrs-" (System/currentTimeMillis))
        cwd-a (s/session-dir-for-cwd dir "/home/user/proj-a")
        cwd-b (s/session-dir-for-cwd dir "/home/user/proj-b")
        sa1 (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (s/append-entry sa1 {:role :assistant :content "a1"})
        sa2 (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (s/append-entry sa2 {:role :assistant :content "a2"})
        sb (s/create-session cwd-b {:cwd "/home/user/proj-b"})
        _ (s/append-entry sb {:role :assistant :content "b1"})
        ;; a legacy headerless file in the cwd dir — must be excluded
        legacy (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (spit (:file legacy) "{:id \"1\" :role :assistant :content \"old\"}\n")
        now (System/currentTimeMillis)]
    ;; Discovery orders files by mtime. Set the ordering explicitly instead of
    ;; sleeping between writes, including a newer headerless decoy.
    (fs/set-last-modified-time (:file sa1) (- now 40))
    (fs/set-last-modified-time (:file sb) (- now 30))
    (fs/set-last-modified-time (:file sa2) (- now 20))
    (fs/set-last-modified-time (:file legacy) (- now 10))
    (try
      (t/is (= (:file sa2) (s/find-most-recent-session cwd-a "/home/user/proj-a"))
            "newest matching session in the cwd dir wins")
      (t/is (nil? (s/find-most-recent-session cwd-a "/home/user/proj-b"))
            "header :cwd mismatch → excluded")
      (t/is (nil? (s/find-most-recent-session cwd-b "/home/user/proj-a"))
            "dir scoping: proj-b has no proj-a session")
      (t/is (nil? (s/find-most-recent-session dir "/home/user/proj-a"))
            "only the cwd dir is scanned, not the base")
      (t/is (nil? (s/find-most-recent-session (str dir "/missing") "/home/user/proj-a"))
            "missing dir → nil")
      (finally (fs/delete-tree dir)))))

(t/deftest test-find-most-recent-session-header-across-chunks
  ;; G13: the bounded header scan is chunked (4 KB) and line-oriented — a
  ;; header larger than one chunk (big :cwd) is assembled across chunk
  ;; boundaries and still discovered
  (let [dir (str "target/test-sess-hdr-chunk-" (System/currentTimeMillis))
        cwd-dir (s/session-dir-for-cwd dir "/home/user/proj")
        big-cwd (str "/home/user/" (apply str (repeat 20000 "d")))
        sess (s/create-session cwd-dir {:cwd big-cwd})
        _ (s/append-entry sess {:role :assistant :content "a"})]
    (try
      (t/is (> (count (slurp (:file sess))) 20000)
            "header line really spans multiple 4 KB chunks")
      (t/is (= (:file sess) (s/find-most-recent-session cwd-dir big-cwd))
            "cross-chunk header discovered")
      (finally (fs/delete-tree dir)))))

(t/deftest test-find-most-recent-session-header-scan-limit
  ;; G13: the header scan is bounded at 1 MB (pi:
  ;; MAX_SESSION_HEADER_SCAN_BYTES) — a file whose header line exceeds the
  ;; limit is treated as headerless, never read to the end
  (let [dir (str "target/test-sess-hdr-limit-" (System/currentTimeMillis))
        cwd-dir (s/session-dir-for-cwd dir "/home/user/proj")
        big-cwd (str "/home/user/" (pad-string 1100000 \d))
        sess (s/create-session cwd-dir {:cwd big-cwd})
        _ (s/append-entry sess {:role :assistant :content "a"})
        other (s/create-session cwd-dir {:cwd "/home/user/proj"})
        _ (s/append-entry other {:role :assistant :content "b"})]
    (try
      (t/is (nil? (s/find-most-recent-session cwd-dir big-cwd))
            "oversized header → not discovered")
      (t/is (= (:file other)
               (s/find-most-recent-session cwd-dir "/home/user/proj"))
            "a normal header in the same dir is still found")
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-nonexistent-dir
  (let [files (s/list-sessions "nonexistent-dir")]
    (t/is (nil? files))))

(t/deftest test-session-first-message
  ;; pi: buildSessionInfo firstMessage — the first user message text
  (let [session (s/create-session test-dir)]
    (t/is (= "(no messages)" (s/get-first-message session)) "empty session")
    (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "hi"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "steered"}]})
    (t/is (= "hello" (s/get-first-message session)) "first user message wins")))

(t/deftest test-session-prompt-history
  ;; Prompt history (editor Up/Down) is reconstituted from :user entries of
  ;; the ACTIVE branch on session resume (pi: buildContextEntries — abandoned
  ;; branches and pre-compaction messages excluded), matching the editor's
  ;; conventions: empty skipped, consecutive duplicates collapsed, newest 100
  ;; kept (pi: addToHistory).
  (let [session (s/create-session test-dir)]
    (t/is (empty? (s/get-prompt-history session)) "empty session")
    (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "hi"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "how are you"}]})
    (s/append-entry session {:role :bash :command "ls" :output "" :exit-code 0})
    (s/append-entry session {:role :user :content [{:type :text :text ""}]})
    (t/is (= ["hello" "how are you"] (s/get-prompt-history session))
          "user messages in order, empty messages skipped")
    ;; consecutive duplicates are collapsed (editor-push-history! dedupes)
    (s/append-entry session {:role :user :content [{:type :text :text "how are you"}]})
    (t/is (= ["hello" "how are you"] (s/get-prompt-history session))
          "consecutive duplicate collapsed")))

(t/deftest test-session-prompt-history-active-branch-only
  ;; Prompts from an abandoned branch are never in the restored history —
  ;; the active branch (root→leaf) is the only source (pi: buildContextEntries).
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "kept"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})
    ;; branch point: "old" prompt + its assistant reply, then branch back
    (let [branch-at (:id (s/append-entry session {:role :user :content [{:type :text :text "old"}]}))]
      (s/append-entry session {:role :assistant :content [{:type :text :text "b"}]})
      (s/branch! session branch-at)
      (s/append-entry session {:role :user :content [{:type :text :text "new"}]})
      (s/append-entry session {:role :assistant :content [{:type :text :text "c"}]})
      (t/is (= ["kept" "old" "new"] (s/get-prompt-history session))
            "active branch only — 'old' is on the path, abandoned siblings excluded")))
  ;; compaction: pre-compaction prompts are excluded (pi: buildContextEntries
  ;; returns [compaction, ...from first-kept-id])
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "old prompt"}]})
    (let [kept (:id (s/append-entry session {:role :assistant :content [{:type :text :text "x"}]}))]
      (s/append-entry session {:role :user :content [{:type :text :text "new prompt"}]})
      (s/append-entry session {:role :assistant :content [{:type :text :text "y"}]})
      (s/compact-with-summary! session "summary" kept)
      (t/is (= ["new prompt"] (s/get-prompt-history session))
            "pre-compaction prompts excluded"))))

(t/deftest test-session-message-count
  ;; pi: buildSessionInfo messageCount — message entries only. kmet's :bash
  ;; role is the EDN analogue of pi's tool-message entries (bash results are
  ;; stored as tool messages in pi), so :bash counts; display-only :info
  ;; entries don't (pi: custom_message is a separate entry type).
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "b"}]})
    (s/append-entry session {:role :session_info :name "t"})
    (s/append-entry session {:role :bash :command "ls" :output "" :exit-code 0})
    (s/append-entry session {:role :info :label "display-only" :content "x"})
    (t/is (= 3 (s/get-message-count session)))))

(t/deftest test-session-build-info
  ;; G15: buildSessionInfo — streaming per-file session info. Header
  ;; required (legacy headerless → nil), name = latest session_info incl.
  ;; explicit clears, messageCount = message entries only, firstMessage =
  ;; first user message, modified = latest message activity, cwd + parent
  ;; path from the header.
  (let [dir (str "target/test-sess-buildinfo-" (System/currentTimeMillis))
        sess (s/create-session dir {:cwd "/home/user/proj"
                                    :parent-session "/old/parent.ednl"})]
    (try
      (s/append-entry sess {:role :user :content [{:type :text :text "hello"}]})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "world"}]})
      (s/append-entry sess {:role :session_info :name "My Session"})
      (s/append-entry sess {:role :bash :command "ls" :output "" :exit-code 0})
      (let [info (s/build-session-info (:file sess))]
        (t/is (some? info))
        (t/is (= (:file sess) (:path info)))
        (t/is (= (:id sess) (:id info)))
        (t/is (= "/home/user/proj" (:cwd info)))
        (t/is (= "/old/parent.ednl" (:parent-session-path info)))
        (t/is (= "My Session" (:name info)))
        (t/is (= 3 (:message-count info)) "message entries only")
        (t/is (= "hello" (:first-message info)))
        (t/is (= "hello world" (:all-messages-text info)))
        (t/is (number? (:modified info)))
        (t/is (number? (:created info))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-build-info-headerless
  ;; G15: legacy headerless files yield nil info (pi: buildSessionInfo
  ;; returns null when the first parseable entry isn't a session header)
  (let [dir (str "target/test-sess-buildinfo-hl-" (System/currentTimeMillis))
        f (str dir "/legacy.ednl")]
    (try
      (fs/create-dirs dir)
      (spit f (prn-str {:id "1" :parent-id nil :role :user
                        :content "old" :timestamp (str (java.time.Instant/now))}))
      (t/is (nil? (s/build-session-info f)))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-build-info-name-cleared
  ;; G15: latest session_info wins, incl. explicit clears → nil name
  (let [dir (str "target/test-sess-buildinfo-clear-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :assistant :content "a"})
      (s/append-entry sess {:role :session_info :name "T"})
      (s/append-entry sess {:role :session_info :name ""})
      (t/is (nil? (:name (s/build-session-info (:file sess)))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-build-info-modified
  ;; G15: modified = latest message activity time (pi: buildSessionInfo),
  ;; not the file mtime
  (let [dir (str "target/test-sess-buildinfo-mod-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :assistant :content "a"})
      (s/append-entry sess {:role :assistant :content "b"})
      (let [info (s/build-session-info (:file sess))
            last-ts (:timestamp (last @(:entries sess)))
            last-ms (try (-> (java.time.Instant/parse last-ts) (.toEpochMilli))
                         (catch Exception _ nil))]
        (t/is (some? info))
        (t/is (= (:modified info) last-ms)
              "modified reflects the latest message activity"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info
  ;; G15: list-sessions-info streams per-file infos (buildSessionInfo) with
  ;; a progress callback, walks cwd subdirs, excludes legacy headerless
  ;; files, newest modified first
  (let [dir (str "target/test-sess-listinfo-" (System/currentTimeMillis))
        cwd-a (s/session-dir-for-cwd dir "/home/user/proj-a")
        cwd-b (s/session-dir-for-cwd dir "/home/user/proj-b")
        sa (s/create-session cwd-a)
        _ (s/append-entry sa {:role :assistant :content "a1"})
        _ (s/append-entry sa {:role :assistant :content "a2"})
        sb (s/create-session cwd-b)
        _ (s/append-entry sb {:role :assistant :content "b1"})
        legacy (s/create-session dir)
        _ (s/append-entry legacy {:role :assistant :content "legacy"})
        headerless (str dir "/headerless.ednl")
        progress (atom [])]
    (try
      (spit headerless (prn-str {:id "9" :parent-id nil :role :user
                                 :content "old" :timestamp (str (java.time.Instant/now))}))
      (let [infos (s/list-sessions-info dir
                                        (fn [loaded total]
                                          (swap! progress conj [loaded total])))]
        (t/is (= 3 (count infos)) "3 header-bearing sessions, headerless excluded")
        (t/is (= #{(:file sa) (:file sb) (:file legacy)} (set (map :path infos))))
        (t/is (= [4 4] (last @progress)) "progress counts all scanned files, incl. headerless")
        (t/is (apply >= (map :modified infos)) "sorted newest modified first"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-nonexistent
  (t/is (= [] (s/list-sessions-info "nonexistent-dir"))))

(t/deftest test-session-last-activity
  ;; pi: modified — the last entry's timestamp, falling back to file mtime
  (let [session (s/create-session test-dir)
        now (System/currentTimeMillis)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (let [ms (s/get-last-activity-ms session)]
      (t/is (number? ms))
      (t/is (>= ms (- now 5000)) "last activity is recent"))))

(t/deftest test-session-last-activity-empty
  ;; Unsaved (lazy) sessions have no file — last activity falls back to the
  ;; header created-at (G4), so the resume dialog can still format an age.
  (let [session (s/create-session test-dir)]
    (t/is (number? (s/get-last-activity-ms session)))
    (t/is (pos? (s/get-last-activity-ms session)))))

(t/deftest test-session-fork
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
    (let [e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})
          fork (s/fork-session session (:id e2))]
      (t/is (some? fork))
      (t/is (not= (:id session) (:id fork)))
      (t/is (>= (count @(:entries fork)) 1)))))

(t/deftest test-session-fork-nonexistent
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/fork-session session "nonexistent")))))

(t/deftest test-session-get-tree
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    ;; a recorded empty completion (clean stream, zero content) — pi's tree
    ;; selector labels it "(no content)" (getEntryDisplayText)
    (s/append-entry session {:role :assistant :content []})
    (let [tree (s/get-tree session)]
      (t/is (vector? tree))
      (t/is (pos? (count tree)))
      (t/is (= :user (:role (first tree)))
            "Root is the first entry (user)")
      (t/is (= 1 (count (:children (first tree))))
            "Root has one child")
      (t/is (= :assistant (:role (first (:children (first tree)))))
            "First child is assistant")
      ;; linear chain q1 → a1 → q2 → empty assistant
      (let [empty-node (-> tree first :children first :children first :children first)]
        (t/is (= :assistant (:role empty-node)))
        (t/is (= "(no content)" (:summary empty-node))
              "empty assistant entries are labeled '(no content)' (pi)")))))

(t/deftest test-session-get-tree-large-session
  ;; get-tree builds correct structure at scale: a long chain plus a
  ;; fan-out branch (regression guard for the per-parent full scan this
  ;; replaced — group-by keeps construction linear in entry count)
  (let [session (s/create-session test-dir)
        n 2000]
    (dotimes [i n]
      (s/append-entry session {:role (if (even? i) :user :assistant)
                               :content [{:type :text :text (str "m" i)}]}))
    (s/branch! session (:id (first @(:entries session))))
    (s/append-entry session {:role :user :content [{:type :text :text "fork"}]})
    ;; iterative: the chain is 2000 deep, a recursive walk overflows
    (letfn [(depth [root]
              (loop [stack [[root 1]]
                     deepest 0]
                (if-let [[node d] (peek stack)]
                  (recur (into (pop stack) (map (fn [c] [c (inc d)]) (:children node)))
                         (max deepest d))
                  deepest)))]
      (let [tree (s/get-tree session)
            root (first tree)]
        (t/is (= 1 (count tree)) "single root")
        (t/is (= n (depth root)) "chain covers every appended entry")
        (t/is (= 2 (count (:children root))) "branched entry fans out to 2 children")
        (t/is (= "fork" (-> root :children peek :summary str/trim))
              "branch sibling keeps append order after the chain child")))))

(t/deftest test-session-delete
  (let [session (s/create-session test-dir)
        f (:file session)]
    (s/append-entry session {:role :assistant :content "hi"}) ;; persist (lazy G4)
    (t/is (.exists (io/file f)))
    (s/delete-session! session)
    (t/is (not (.exists (io/file f))))))

;; ─── Regression: corrupted entries ────────────────────────────────────────

(t/deftest test-session-load-with-corrupt-entry
  (let [session (s/create-session test-dir)
        file (:file session)]
    ;; Write: valid, broken (unclosed map), valid
    (spit file "{:id \"1\" :role :user :content \"hello\"}\n")
    (spit file "{:bad \"entry\"\n" :append true)  ;; unclosed map → parse error
    (spit file "{:id \"3\" :role :assistant :content \"world\"}\n" :append true)
    (let [loaded (silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (= 2 (count entries)) "Should skip corrupt entry, keep both valid ones")
      (t/is (= "1" (:id (first entries))))
      (t/is (= "3" (:id (second entries))))
      (t/is (= :user (:role (first entries))))
      (t/is (= :assistant (:role (second entries)))))))

(t/deftest test-session-load-with-multiple-corrupt-entries
  (let [session (s/create-session test-dir)
        file (:file session)]
    ;; Write: valid, broken, valid, broken, valid
    (spit file "{:id \"1\" :role :user :content \"a\"}\n")
    (spit file "garbage!@\n" :append true)         ;; invalid characters
    (spit file "{:id \"3\" :role :user :content \"b\"}\n" :append true)
    (spit file "{{:id \"4\"}\n" :append true)     ;; double brace → parse error
    (spit file "{:id \"5\" :role :assistant :content \"c\"}\n" :append true)
    (let [loaded (silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (= 3 (count entries)) "Should skip all corrupt entries, keep all valid ones")
      (t/is (= "1" (:id (nth entries 0))))
      (t/is (= "3" (:id (nth entries 1))))
      (t/is (= "5" (:id (nth entries 2)))))))

(t/deftest test-session-load-all-corrupt
  (let [session (s/create-session test-dir)
        file (:file session)]
    (spit file "{{{{{{\n")
    (spit file "[invalid}^&*\n" :append true)
    (let [loaded (silent-stderr #(s/load-session file))
          entries @(:entries loaded)]
      (t/is (empty? entries) "Should return empty entries when all lines are corrupt"))))

(t/deftest test-session-load-empty-file
  (let [session (s/create-session test-dir)
        file (:file session)]
    (spit file "")
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (empty? entries) "Empty file should produce empty entries"))))

(t/deftest test-session-load-with-blank-lines
  (let [session (s/create-session test-dir)
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
        entry (fn [i] {:role :user :content [{:type :text :text (str "msg " i " " (pad-string 2500 \x))}]})]
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
      (let [loaded (silent-stderr #(s/load-session file))
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
      (let [loaded (silent-stderr #(s/load-session file))]
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
      (let [loaded (silent-stderr #(s/load-session file))
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
                       :content (str (pad-string 1048541 \a) "漢")})]
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

;; ─── Append-only compaction: context build (pi: buildContextEntries) ─────

(t/deftest test-build-context-no-compaction
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "a"})
    (s/append-entry session {:role :assistant :content "b"})
    (t/is (= 2 (count (s/build-context session)))
          "no compaction on the path → full branch")))

(t/deftest test-build-context-empty
  (let [session (s/create-session test-dir)]
    (t/is (empty? (s/build-context session)))))

(t/deftest test-build-context-latest-compaction-wins
  (let [session (s/create-session test-dir)]
    (dotimes [i 6]
      (s/append-entry session {:role :user :content (str "m" i)}))
    (let [first-kept-1 (:id (nth (s/get-branch session) 2))]
      (s/compact-with-summary! session "FIRST" first-kept-1)
      (s/append-entry session {:role :user :content "m6"})
      (s/append-entry session {:role :user :content "m7"})
      (let [e2 (s/get-branch session)
            first-kept-2 (:id (nth e2 4))]
        (s/compact-with-summary! session "SECOND" first-kept-2)
        (let [context (s/build-context session)]
          (t/is (= "SECOND" (:summary (first context)))
                "latest compaction wins")
          (t/is (= first-kept-2 (:id (second context)))
                "its first-kept-id starts the tail"))))))

(t/deftest test-build-context-messages
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-entry session {:role :assistant :content "a"})
    (s/append-session-info! session "t")
    (s/append-entry session {:role :info :label "note" :content "x"})
    (s/compact-with-summary! session "SUM" (:id (first (s/get-branch session))))
    (let [msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= 3 (count msgs)))
      (t/is (= :compaction (:role (first msgs))))
      (t/is (= "SUM" (:summary (first msgs)))
            "compaction keeps its role + summary (pi: compactionSummary AgentMessage)")
      (t/is (= "q" (-> msgs second :content)))
      (t/is (= "a" (-> msgs last :content)))
      (t/is (not-any? #(contains? #{:info :session_info} (:role %)) msgs)
            ":info/:session_info are metadata, excluded from context"))))

(t/deftest test-compaction-retains-old-entries
  (let [session (s/create-session test-dir)]
    (dotimes [i 4]
      (s/append-entry session {:role :user :content (str "q" i)}))
    (let [branch (s/get-branch session)
          first-kept-id (:id (nth branch 2))]
      (s/compact-with-summary! session "SUM" first-kept-id)
      (s/append-entry session {:role :assistant :content "after"}))
    (let [loaded (s/load-session (:file session))]
      (t/is (= 6 (count @(:entries loaded))) "all entries persist on reload")
      (t/is (some #(= "q0" (:content %)) @(:entries loaded))
            "summarized history stays reachable (tree/fork)"))))

;; ─── Atomic rewrite (pi: temp-file publication) ──────────────────────────

(t/deftest test-replace-entries
  (let [session (s/create-session test-dir)]
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
  (let [session (s/create-session test-dir)
        entry (s/append-session-info! session "my session")]
    (t/is (= :session_info (:role entry)))
    (t/is (= "my session" (:name entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (= 1 (count (s/get-branch session)))
          "session_info participates in the branch tree")))

(t/deftest test-get-session-name
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/get-session-name session)))
    (s/append-entry session {:role :user :content "hi"})
    (s/append-session-info! session "alpha")
    (t/is (= "alpha" (s/get-session-name session)))
    (s/append-session-info! session "beta")
    (t/is (= "beta" (s/get-session-name session))
          "latest session_info entry wins")))

(t/deftest test-get-session-name-empty-clears
  (let [session (s/create-session test-dir)]
    (s/append-session-info! session "alpha")
    (s/append-session-info! session "   ")
    (t/is (nil? (s/get-session-name session))
          "empty name explicitly clears the title")))

(t/deftest test-session-name-persists
  (let [session (s/create-session test-dir)
        file (:file session)]
    (s/append-entry session {:role :user :content "hi"})
    (s/append-entry session {:role :assistant :content "hello"}) ;; persist (lazy G4)
    (s/append-session-info! session "my session")
    (let [loaded (s/load-session file)]
      (t/is (= "my session" (s/get-session-name loaded))))))

;; ─── Usage tracking ────────────────────────────────────────────────────────

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
  (let [session-dir (io/file test-dir "bash")
        sess (s/create-session (str session-dir))
        result {:output "clean\n" :exit-code 0}
        entry (s/record-bash-result! sess "git st" result false)]
    (t/is (= :bash (:role entry)))
    (t/is (= "git st" (:command entry)))
    (t/is (= 1 (count @(:entries sess))))
    (t/is (= (:id entry) (:id (first @(:entries sess)))))))

(t/deftest test-entry-usage
  (t/testing "OpenAI usage shape — input excludes cached tokens (pi normalizeUsage)"
    (t/is (= {:input 70 :output 20 :cache-read 30 :cache-write 0 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                 :prompt_tokens_details {:cached_tokens 30}}))))
  (t/testing "OpenAI cache-write tokens are split out and subtracted (OpenRouter-compatible)"
    (t/is (= {:input 65 :output 20 :cache-read 30 :cache-write 5 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                 :prompt_tokens_details {:cached_tokens 30
                                                         :cache_write_tokens 5}}))))
  (t/testing "Anthropic usage shape — input_tokens already excludes cache"
    (t/is (= {:input 100 :output 20 :cache-read 30 :cache-write 10 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20
                                 :cache_read_input_tokens 30
                                 :cache_creation_input_tokens 10}))))
  (t/testing "OpenAI Responses shape — input_tokens includes cache tokens,
             subtracted from the details sub-map (pi normalizeUsage)"
    (t/is (= {:input 65 :output 20 :cache-read 30 :cache-write 5 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20 :total_tokens 120
                                 :input_tokens_details {:cached_tokens 30
                                                        :cache_write_tokens 5}
                                 :output_tokens_details {:reasoning_tokens 10}}))))
  (t/testing "Responses usage without a cache details sub-map passes through"
    (t/is (= {:input 100 :output 20 :cache-read 0 :cache-write 0 :cost 0.0}
             (usage/entry-usage {:input_tokens 100 :output_tokens 20 :total_tokens 120}))))
  (t/testing "Google's already-normalized shape (sse/google-usage)"
    (t/is (= {:input 10 :output 20 :cache-read 5 :cache-write 0 :cost 0.0}
             (usage/entry-usage {:input 10 :output 20 :cache-read 5 :cache-write 0}))))
  (t/testing "fully-cached prompt → zero input, cache tokens still reported"
    (t/is (= {:input 0 :output 1 :cache-read 100 :cache-write 0 :cost 0.0}
             (usage/entry-usage {:prompt_tokens 100 :completion_tokens 1
                                 :prompt_tokens_details {:cached_tokens 100}}))))
  (t/testing "cost is carried through from the breakdown attached by llm"
    (t/is (= 0.0042 (:cost (usage/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                               :cost {:input 0.0014 :output 0.0028
                                                      :cache-read 0.0 :cache-write 0.0
                                                      :total 0.0042}})))))
  (t/testing "Bedrock ConverseStream shape — cache_read/write input tokens"
    (t/is (= {:input 7 :output 5 :cache-read 2 :cache-write 1 :cost 0.0}
             (usage/entry-usage {:input_tokens 10 :output_tokens 5 :total_tokens 15
                                 :cache_read_input_tokens 2
                                 :cache_write_input_tokens 1}))))
  (t/testing "unknown shape returns nil"
    (t/is (nil? (usage/entry-usage {:foo 1})))
    (t/is (nil? (usage/entry-usage nil)))))

(t/deftest test-usage-totals
  (let [dir (str "target/test-sess-usage-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "hi"})
      (s/append-entry sess {:role :assistant :content "a"
                            :usage {:prompt_tokens 10 :completion_tokens 2}})
      (s/append-entry sess {:role :assistant :content "b"
                            :usage {:prompt_tokens 20 :completion_tokens 4
                                    :prompt_tokens_details {:cached_tokens 6}}})
      (s/append-entry sess {:role :assistant :content "c"
                            :usage {:prompt_tokens 100 :completion_tokens 0
                                    :cost {:input 0.001 :output 0.0
                                           :cache-read 0.0 :cache-write 0.0
                                           :total 0.001}}})
      (t/is (= {:input 124 :output 6 :cache-read 6 :cache-write 0 :cost 0.001}
               (s/usage-totals sess)))
      (finally (fs/delete-tree dir)))))

;; ─── Branching (G17): branch!/reset-leaf!/branch-with-summary! ─────────────

(t/deftest test-branch-moves-leaf
  (let [session (s/create-session test-dir)]
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
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (t/is (thrown? clojure.lang.ExceptionInfo (s/branch! session "nope")))))

(t/deftest test-reset-leaf
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (s/reset-leaf! session)
    (t/is (nil? @(:leaf-id session)))
    (let [u2 (s/append-entry session {:role :user :content "q2"})]
      (t/is (nil? (:parent-id u2))
            "append after reset becomes a new root")
      (t/is (= [u2] (s/get-branch session))))))

(t/deftest test-branch-with-summary
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          summary (s/branch-with-summary! session (:id u2)
                                          "explored option B" {:details {:x 1}})]
      (t/is (= :branch-summary (:role summary)))
      (t/is (= (:id u2) (:from-id summary)))
      (t/is (= (:id u2) (:parent-id summary))
            "summary entry is a child of the new leaf position")
      (t/is (= "explored option B" (:summary summary)))
      (t/is (= {:x 1} (:details summary)))
      (t/is (= (:id summary) @(:leaf-id session))))
    ;; nil branch-from-id → root, from-id "root"
    (s/reset-leaf! session)
    (let [summary (s/branch-with-summary! session nil "back to root")]
      (t/is (= "root" (:from-id summary)))
      (t/is (nil? (:parent-id summary))))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/branch-with-summary! session "nope" "x")))))

(t/deftest test-branch-summary-projects-to-context
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          _ (s/branch-with-summary! session (:id u2) "abandoned path")
          msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= :branch-summary (:role (last msgs))))
      (t/is (= "abandoned path" (:summary (last msgs)))
            "branch_summary keeps its role + summary (pi: branchSummary)"))))

(t/deftest test-branch-summary-entries
  ;; branch: root → u1 → a1 → u2 → a2; navigate from a2 back to u1
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})
          u2 (s/append-entry session {:role :user :content "q2"})
          a2 (s/append-entry session {:role :assistant :content "a2"})]
      (t/is (= [a2] (s/branch-summary-entries session (:id a2) (:id u2)))
            "abandoned tail from the old leaf down to (excluding) the common ancestor")
      (t/is (= [u2 a2] (s/branch-summary-entries session (:id a2) (:id a1)))
            "chronological order (rootward first), common ancestor (a1) excluded"))))

;; ─── Labels (G11): set-label!/get-label ────────────────────────────────────

(t/deftest test-labels
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})]
      (t/is (nil? (s/get-label session (:id a1))))
      (s/set-label! session (:id a1) "fix-123")
      (t/is (= "fix-123" (s/get-label session (:id a1))))
      ;; latest-wins
      (s/set-label! session (:id a1) "fix-456")
      (t/is (= "fix-456" (s/get-label session (:id a1))))
      ;; clearing
      (s/set-label! session (:id a1) nil)
      (t/is (nil? (s/get-label session (:id a1))))
      (t/is (thrown? clojure.lang.ExceptionInfo (s/set-label! session "nope" "x"))))))

;; ─── Fork (G18): createBranchedSession semantics ───────────────────────────

(t/deftest test-fork-keeps-ids-and-parent-chain
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})]
    (s/branch! session (:id u2))
    (let [fork (s/fork-session session (:id u2))]
      (t/is (some? fork))
      (t/is (= [(:id q1) (:id a1) (:id u2)] (map :id (s/get-branch fork)))
            "fork branch = root→entry")
      (t/is (= (:id u2) (:id (last (s/get-branch fork))))
            "entry ids are preserved")
      (t/is (= (:file session) (get-in fork [:header :parent-session]))
            "fork header links the source file")
      (t/is (= (get-in session [:header :cwd]) (get-in fork [:header :cwd]))
            "fork keeps the source cwd")
      (t/is (= (:id u2) (:parent-id (s/append-entry fork {:role :assistant :content "a3"})))
            "fork's next append children off the preserved leaf id"))))

(t/deftest test-fork-rechains-around-labels
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        ;; label entry chains off a1, then the next message chains off the label
        lab (s/set-label! session (:id a1) "keep")
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id lab) (:parent-id u2))
          "later entries are children of the label entry")
    (let [fork (s/fork-session session (:id a2))
          fork-u2 (s/get-entry fork (:id u2))
          fork-a2 (s/get-entry fork (:id a2))
          fork-leaf (last (s/get-branch fork))]
      (t/is (= [(:id q1) (:id a1) (:id u2) (:id a2)]
               (mapv :id (butlast (s/get-branch fork))))
            "fork path skips labels and re-chains parents")
      (t/is (= :label (:role fork-leaf))
            "the recreated label chains off the last retained entry (the new leaf)")
      (t/is (= (:id a1) (:target-id fork-leaf)))
      (t/is (= (:id a1) (:parent-id fork-u2))
            "re-chained: u2's parent is a1, not the removed label")
      (t/is (= (:id u2) (:parent-id fork-a2))
            "a2's parent chain is intact")
      (t/is (= "keep" (s/get-label fork (:id a1)))
            "label recreated in the fork (original target, latest label)")
      (t/is (= (:timestamp lab) (:timestamp fork-leaf))
            "recreated label entry keeps its original timestamp")
      (t/is (= 5 (count @(:entries fork)))
            "fork holds the four retained entries + the recreated label"))))

(t/deftest test-clone-session
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/clone-session session))
          "nothing to clone before the first entry")
    (s/append-entry session {:role :user :content "q1"})
    (s/append-entry session {:role :assistant :content "a1"})
    (let [clone (s/clone-session session)]
      (t/is (some? clone))
      (t/is (= (map :id (s/get-branch session))
               (map :id (s/get-branch clone)))
            "clone copies the full active branch")
      (t/is (= (:file session) (get-in clone [:header :parent-session]))))))

(t/deftest test-fork-from
  (let [source (s/create-session test-dir)]
    (s/append-entry source {:role :user :content "q1"})
    (s/append-entry source {:role :assistant :content "a1"})
    (let [path (:file source)
          ;; force persistence (lazy G4 writes on first assistant message)
          _ (t/is (fs/exists? path))
          target-cwd (str (fs/path (fs/temp-dir) "other-proj"))
          fork (s/fork-from path target-cwd test-dir)]
      (t/is (some? fork))
      (t/is (= (map :id (s/get-branch source))
               (map :id (s/get-branch fork)))
            "fork-from copies all entries verbatim (ids preserved)")
      (t/is (= target-cwd (get-in fork [:header :cwd]))
            "fork-from header carries the target cwd")
      (t/is (= path (get-in fork [:header :parent-session])))
      (t/is (fs/exists? (:file fork))
            "fork-from writes the fork file immediately")))
  (let [empty-file (str test-dir "/empty.ednl")]
    (spit empty-file "")
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/fork-from empty-file (str (fs/temp-dir)) test-dir)))
    (fs/delete empty-file)))

(t/deftest test-common-ancestor-id
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        _ (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id q1) (s/common-ancestor-id session (:id a2) (:id q1))))
    (t/is (= (:id u2) (s/common-ancestor-id session (:id a2) (:id u2))))
    (t/is (= (:id a2) (s/common-ancestor-id session (:id a2) (:id a2)))
          "same entry is its own common ancestor")
    (t/is (nil? (s/common-ancestor-id session nil (:id a2)))
          "no old leaf → nil")))

;; ─── Runtime working directory (pi: AgentSession._cwd) ────────────────────

(t/deftest test-session-cwd
  (t/testing "a session's runtime cwd is its recorded header cwd"
    (let [dir (str (fs/absolutize (str test-dir "-cwd")))
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

;; ─── Import (pi: agent-session-runtime importFromJsonl) ───────────────────

(t/deftest test-import-plan-and-copy
  (let [dir (str (fs/absolutize (str test-dir "-import")))
        src-dir (str dir "/src")
        dest-dir (str dir "/dest")]
    (fs/delete-tree dir)
    (try
      (let [sess (s/create-session src-dir)]
        (s/append-entry sess {:role :user :content [{:type :text :text "hello"}]})
        (s/append-entry sess {:role :assistant :content [{:type :text :text "reply"}]})
        (let [source (:file sess)
              dest (str (fs/normalize (fs/path dest-dir (fs/file-name source))))]
          (t/testing "the source's basename lands in the destination dir"
            (let [plan (s/plan-session-import source dest-dir)]
              (t/is (= source (:source plan)))
              (t/is (= dest (:path plan)))
              (t/is (false? (:already-stored? plan)))))
          (t/testing "copying creates the dir and is verbatim"
            (t/is (= dest (s/copy-imported-session! (s/plan-session-import source dest-dir))))
            (t/is (fs/exists? dest))
            (t/is (= (slurp source) (slurp dest))))
          (t/testing "a taken name is suffixed (pi: COPYFILE_EXCL retry)"
            (let [plan (s/plan-session-import source dest-dir)]
              (t/is (= (slash (fs/path dest-dir
                                       (str/replace (fs/file-name source) #"\.ednl$" "-1.ednl")))
                       (slash (:path plan))))
              (t/is (= (:path plan) (s/copy-imported-session! plan)))
              (t/is (fs/exists? (:path plan))))))
        (t/testing "a source already in the destination dir is not copied again"
          (let [same-dir (str dir "/already")
                sess (s/create-session same-dir)
                _ (s/append-entry sess {:role :user :content "x"})
                _ (s/append-entry sess {:role :assistant :content "y"})
                stored (:file sess)
                plan (s/plan-session-import stored same-dir)]
            (t/is (true? (:already-stored? plan)))
            (t/is (= stored (:path plan)))
            (t/is (= stored (s/copy-imported-session! plan)))
            (t/is (= 1 (count (fs/list-dir same-dir))) "nothing new was written")))
        (t/testing "a missing file throws pi's SessionImportFileNotFoundError"
          (let [e (try (s/plan-session-import (str dir "/ghost.ednl") dest-dir) nil
                       (catch Exception e e))]
            (t/is (some? e))
            (t/is (= :session-import/file-not-found (:type (ex-data e))))
            (t/is (str/includes? (ex-message e) "File not found"))))
        (t/testing "a file that is not a kmet session throws"
          (let [junk (str dir "/junk.ednl")]
            (spit junk "not a session\n")
            (let [e (try (s/plan-session-import junk dest-dir) nil
                         (catch Exception e e))]
              (t/is (some? e))
              (t/is (= :session-import/not-a-session (:type (ex-data e)))))))
        (t/testing "a directory is not importable"
          (let [e (try (s/plan-session-import src-dir dest-dir) nil
                       (catch Exception e e))]
            (t/is (= :session-import/file-not-found (:type (ex-data e)))))))
      (finally (fs/delete-tree dir)))))

;; ─── Model & thinking changes (G6 — pi: appendModelChange /
;;     appendThinkingLevelChange / getSessionContextSettings) ───────────────

(t/deftest test-model-and-thinking-change-entries
  (let [session (s/create-session test-dir)]
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

(t/deftest test-derive-context-settings
  ;; G6: model/thinking derived from the branch path — latest wins, defaults
  ;; when absent (pi: getSessionContextSettings)
  (let [session (s/create-session test-dir)]
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "empty session: defaults")
    (s/append-entry session {:role :user :content "q"})
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "no change entries: defaults")
    (s/append-model-change! session :anthropic "claude-sonnet")
    (s/append-thinking-level-change! session :medium)
    (t/is (= {:thinking-level :medium :model "claude-sonnet" :provider :anthropic}
             (s/derive-context-settings session)))
    (s/append-model-change! session :opencode-go "gpt-4o")
    (s/append-thinking-level-change! session :off)
    (t/is (= {:thinking-level :off :model "gpt-4o" :provider :opencode-go}
             (s/derive-context-settings session))
          "latest change entries win")))

(t/deftest test-derive-context-settings-branch-aware
  ;; derivation follows the ACTIVE branch — settings from abandoned branches
  ;; don't leak in (pi: getSessionContextSettings walks root→leaf)
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        _ (s/append-model-change! session :anthropic "claude-sonnet")
        _ (s/append-thinking-level-change! session :high)]
    (s/branch! session (:id q1))
    (t/is (= {:thinking-level :off :model nil :provider nil}
             (s/derive-context-settings session))
          "branching away abandons the change entries"))
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/branch! session (:id (first (s/get-branch session))))
    (s/append-model-change! session :opencode-go "gpt-4o")
    (t/is (= {:thinking-level :off :model "gpt-4o" :provider :opencode-go}
             (s/derive-context-settings session))
          "new branch carries its own change entries")))

(t/deftest test-tree-displays-change-entries
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-model-change! session :anthropic "claude-sonnet")
    (s/append-thinking-level-change! session :high)
    (letfn [(flatten-tree [nodes]
              (mapcat (fn [n]
                        (cons (:summary n) (flatten-tree (:children n))))
                      nodes))]
      (t/is (= ["q" "[model: anthropic/claude-sonnet]" "[thinking: high]"]
               (vec (flatten-tree (s/get-tree session))))
            "tree shows the switch instead of (empty)"))))

;; ─── G9/G10: custom + custom_message entries ──────────────────────────────

(t/deftest test-custom-entry
  ;; G9: custom entries are extension state — persisted, never in context
  (let [session (s/create-session test-dir)
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
  (let [session (s/create-session test-dir)
        q (s/append-entry session {:role :user :content "q"})
        _ (s/append-custom-entry! session :my-state {:v 1})]
    (s/branch! session (:id q))
    (t/is (empty? (s/get-custom-entries session :my-state))
          "abandoned branch state not visible"))
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/branch! session (:id (first (s/get-branch session))))
    (s/append-custom-entry! session :my-state {:v 2})
    (t/is (= 1 (count (s/get-custom-entries session :my-state))))
    (t/is (= {:v 2} (:data (first (s/get-custom-entries session :my-state)))))))

(t/deftest test-all-custom-entries-session-wide
  ;; get-all-custom-entries spans abandoned branches (pi: getEntries);
  ;; get-custom-entries stays branch-scoped (pi: getBranch)
  (let [session (s/create-session test-dir)
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
  (let [session (s/create-session test-dir)
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
  (let [session (s/create-session test-dir)
        e (s/append-custom-message-entry! session :img
                                          [{:type :image :data "aa" :mime-type "image/png"}]
                                          true)
        msg (first (s/context-messages e))]
    (t/is (= [{:type :image :data "aa" :mime-type "image/png"}] (:content msg)))))

(t/deftest test-custom-message-entry-nil-content
  ;; nil content defaults to an empty block vector (pi: content ?? [])
  (let [session (s/create-session test-dir)
        e (s/append-custom-message-entry! session :quiet nil false)
        msg (first (s/context-messages e))]
    (t/is (= [] (:content msg)))
    (t/is (= false (:display msg)))))

(t/deftest test-tree-displays-custom-entries
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-custom-entry! session :my-state {:v 1})
    (s/append-custom-message-entry! session :note "hi" true)
    (s/append-custom-message-entry! session :quiet nil false)
    (letfn [(flatten-tree [nodes]
              (mapcat (fn [n]
                        (cons (:summary n) (flatten-tree (:children n))))
                      nodes))]
      (t/is (= ["q" "[custom: my-state]" "hi" "[custom: quiet]"]
               (vec (flatten-tree (s/get-tree session))))
            "tree shows custom entries instead of (empty)"))))

(t/deftest test-entry-id-format
  ;; G12: ids are <hex-ms>-<8-hex> — time-ordered prefix + 32-bit random,
  ;; so same-ms cross-process collisions are negligible
  (let [session (s/create-session test-dir)
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

(t/deftest test-session-stats
  ;; G22 /session: get-session-stats aggregates over ALL entries (pi:
  ;; getSessionStats) — user/assistant counts, tool results (:tool and
  ;; :bash), tool calls from assistant :tool-calls, and usage totals.
  (let [dir (str "target/test-sess-stats-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "q1"})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "a1"}]
                            :tool-calls [{:name "read" :arguments {:path "x"}}]
                            :usage {:prompt_tokens 100 :completion_tokens 20
                                    :cost {:total 0.01}}})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "a2"}]
                            :usage {:prompt_tokens 50 :completion_tokens 10
                                    :cost {:total 0.005}}})
      (s/append-entry sess {:role :tool :content "result" :tool-name "read"})
      (s/append-entry sess {:role :bash :command "ls" :output "x" :exit-code 0})
      (s/append-entry sess {:role :info :content "display only"})
      (let [stats (s/get-session-stats sess)]
        (t/is (= 1 (:user-messages stats)))
        (t/is (= 2 (:assistant-messages stats)))
        (t/is (= 1 (:tool-calls stats)) "one tool call on the first assistant")
        (t/is (= 2 (:tool-results stats)) ":tool and :bash both count as results")
        (t/is (= 5 (:total-messages stats)) ":info is display-only, not a message")
        (t/is (= {:input 150 :output 30 :cache-read 0 :cache-write 0 :total 180}
                 (:tokens stats)))
        (t/is (= 0.015 (:cost stats))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-stats-empty
  (let [sess (s/create-session test-dir)
        stats (s/get-session-stats sess)]
    (t/is (= 0 (:user-messages stats)))
    (t/is (= 0 (:total-messages stats)))
    (t/is (= {:input 0 :output 0 :cache-read 0 :cache-write 0 :total 0}
             (:tokens stats)))
    (t/is (= 0.0 (:cost stats)))))

(t/deftest test-last-assistant-text
  ;; G22 /copy: last assistant message with non-empty text on the branch;
  ;; empty (aborted) assistant entries are skipped.
  (let [dir (str "target/test-sess-last-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (t/is (nil? (s/get-last-assistant-text sess)) "no messages yet")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :assistant :content []})  ;; aborted, no content
      (s/append-entry sess {:role :assistant :content [{:type :text :text "first"}]})
      (s/append-entry sess {:role :assistant :content [{:type :text :text "  second  "}]})
      (t/is (= "second" (s/get-last-assistant-text sess)))
      (s/append-entry sess {:role :assistant :content []})  ;; aborted again
      (t/is (= "second" (s/get-last-assistant-text sess))
            "trailing empty assistant does not shadow the last text")
      (finally (fs/delete-tree dir)))))

(t/deftest test-usage-breakdown
  ;; G22 /session cost breakdown (pi: getUsageCostBreakdown) — assistant
  ;; usage attributed to the model active at that point (:model-change),
  ;; compaction/summary usage under "Tools/summaries", sorted by cost desc.
  (let [dir (str "target/test-sess-breakdown-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-model-change! sess :anthropic "claude-3")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :assistant :content "a"
                            :usage {:prompt_tokens 100 :completion_tokens 20 :cost {:total 0.01}}})
      (s/append-model-change! sess :openai "gpt-4o")
      (s/append-entry sess {:role :assistant :content "b"
                            :usage {:prompt_tokens 50 :completion_tokens 5 :cost {:total 0.02}}})
      (s/append-entry sess {:role :compaction :summary "x" :first-kept-id "y"
                            :usage {:input_tokens 10 :output_tokens 2 :cost {:total 0.001}}})
      (let [b (s/usage-breakdown sess)]
        (t/is (= ["openai/gpt-4o" "anthropic/claude-3" "Tools/summaries"]
                 (mapv :key b))
              "sorted by cost desc; compaction under Tools/summaries")
        (t/is (= 55 (:tokens (first b))))
        (t/is (= 12 (:tokens (last b)))))
      (t/is (= [] (s/usage-breakdown (s/create-session test-dir)))
            "no usage -> no buckets")
      (finally (fs/delete-tree dir)))))

(t/deftest test-usage-breakdown-model-stamps
  ;; pi parity: assistant entries carry their own :provider/:model provenance
  ;; (add-assistant-message!) and are attributed by it directly; the
  ;; :model-change walk is only the fallback for legacy unstamped entries.
  (let [dir (str "target/test-sess-breakdown-stamps-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      ;; no :model-change at all — initial-model usage must not be unknown
      (s/append-entry sess {:role :assistant :content "a"
                            :provider :anthropic :model "claude-3"
                            :usage {:prompt_tokens 100 :completion_tokens 10 :cost {:total 0.01}}})
      ;; mid-session switch: the walk says openai/gpt-4o, the stamp wins
      (s/append-model-change! sess :openai "gpt-4o")
      (s/append-entry sess {:role :assistant :content "b"
                            :provider :anthropic :model "claude-3"
                            :usage {:prompt_tokens 50 :completion_tokens 5 :cost {:total 0.02}}})
      (let [b (s/usage-breakdown sess)]
        (t/is (= ["anthropic/claude-3"] (mapv :key b))
              "stamped entries attribute by their own provider/model, stamp beats walk")
        (t/is (= 165 (:tokens (first b))))
        (t/is (== 0.03 (:cost (first b))) "usage and cost sum across both segments"))
      (finally (fs/delete-tree dir))))
  (t/testing "legacy files: no stamp and no switch yet lands under the unknown bucket"
    (let [dir (str "target/test-sess-breakdown-unknown-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (s/append-entry sess {:role :assistant :content "a"
                              :usage {:prompt_tokens 10 :completion_tokens 1}})
        (t/is (= ["unknown"] (mapv :key (s/usage-breakdown sess))))
        (finally (fs/delete-tree dir))))))

;; ─── Cache-miss detection (pi: cache-stats.ts detectMiss) ─────────────────

(t/deftest test-detect-cache-miss
  (let [with-usage (fn [role usage & [model]] {:role role :usage usage :model model})
        entries [{:role :model-change :model "m1"}
                 (with-usage :assistant {:input_tokens 10000 :output_tokens 100
                                         :input_tokens_details {:cached_tokens 9000}})
                 (with-usage :assistant {:input_tokens 10000 :output_tokens 100
                                         :input_tokens_details {:cached_tokens 0}})]]
    (t/testing "a drop from cached to uncached counts the miss"
      (let [miss (s/detect-cache-miss entries)]
        (t/is (some? miss))
        (t/is (= 10000 (:missed-tokens miss))))))
  (t/testing "no cache activity on the previous turn → nil"
    (let [entries [{:role :assistant :usage {:input_tokens 1000 :output_tokens 10}}
                   {:role :assistant :usage {:input_tokens 1000 :output_tokens 10}}]]
      (t/is (nil? (s/detect-cache-miss entries)))))
  (t/testing "fewer than two assistant messages → nil"
    (t/is (nil? (s/detect-cache-miss [{:role :assistant :usage {:input_tokens 1}}]))))
  (t/testing "model change between turns is reported"
    (let [entries [{:role :model-change :model "m1"}
                   {:role :assistant :usage {:input_tokens 10000 :output_tokens 100
                                             :input_tokens_details {:cached_tokens 9000}}}
                   {:role :model-change :model "m2"}
                   {:role :assistant :usage {:input_tokens 10000 :output_tokens 100
                                             :input_tokens_details {:cached_tokens 0}}}]]
      (t/is (true? (:model-changed (s/detect-cache-miss entries)))))))

;; ─── Tool-result token attribution (script.md T0) ─────────────────────────

(t/deftest test-session-tool-usage
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "hi"})
    (s/append-entry session {:role :tool
                             :tool-name "read"
                             :content [{:type :tool_result
                                        :tool_use_id "t1"
                                        :content (pad-string 400 \a)}]})
    (s/append-entry session {:role :tool
                             :tool-name "grep"
                             :content [{:type :tool_result
                                        :tool_use_id "t2"
                                        :content "ab"}]})
    ;; an assistant entry publishes the file (lazy creation G4)
    (s/append-entry session {:role :assistant :content "ok"})
    (t/testing "tool results carry an estimated :result-tokens (chars/4)"
      (let [read-entry (first (filter #(= "read" (:tool-name %))
                                      @(:entries session)))]
        (t/is (= 100 (:result-tokens read-entry)))))
    (t/testing "tool-usage derives per-tool totals + a :total"
      (let [u (s/tool-usage session)]
        (t/is (= {:calls 1 :tokens 100} (get u "read")))
        (t/is (= {:calls 1 :tokens 1} (get u "grep")))
        (t/is (= {:calls 2 :tokens 101} (:total u)))))
    (t/testing "the report names the tools and totals"
      (let [report (s/tool-usage-report session)]
        (t/is (str/includes? report "read"))
        (t/is (str/includes? report "TOTAL"))))
    (t/testing "loaded sessions report from the persisted entries"
      (let [loaded (s/load-session (:file session))]
        (t/is (= {:calls 2 :tokens 101} (:total (s/tool-usage loaded))))))
    (t/testing "unstamped tool entries (rebuilt contexts, legacy files) estimate on the fly"
      (let [rebuilt (s/create-session test-dir)]
        (s/replace-entries! rebuilt [{:role :tool
                                      :tool-name "bash"
                                      :content [{:type :tool_result
                                                 :tool_use_id "t3"
                                                 :content (pad-string 40 \b)}]}])
        (t/is (= {:calls 1 :tokens 10} (get (s/tool-usage rebuilt) "bash")))))))

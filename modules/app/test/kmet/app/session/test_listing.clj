(ns kmet.app.session.test-listing
  "On-disk discovery: list-sessions and the session-info projection,
   cwd-encoded directories, candidate ordering, delete, and import."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.session.listing :as listing]
            [kmet.app.session.core :as s]
            [kmet.app.session.test-support :as support]
            [kmet.test-utils :refer [slash]]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :once support/with-clean-test-dir)

(t/deftest test-session-list-sessions
  (let [s1 (s/create-session support/test-dir)
        _ (s/append-entry s1 {:role :user :content "q"})
        _ (s/append-entry s1 {:role :assistant :content "a"})
        s2 (s/create-session support/test-dir)
        _ (s/append-entry s2 {:role :user :content "q"})
        _ (s/append-entry s2 {:role :assistant :content "a"})
        files (s/list-sessions support/test-dir)]
    (t/is (sequential? files))
    (t/is (>= (count files) 2))
    (doseq [f files]
      (t/is (str/ends-with? f ".ednl")))))

(t/deftest test-session-list-cwd-dirs
  ;; G2: sessions live in <base>/<--cwd-->/; list-sessions on the base walks
  ;; the cwd subdirs (pi: listAll), a cwd dir lists flat, and legacy flat
  ;; files in the base stay visible (no regression on upgrade)
  (let [dir (test-tmp/temp-dir! "test-sess-cwd-")
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
  (let [dir (test-tmp/temp-dir! "test-sess-mrs-")
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
  (let [dir (test-tmp/temp-dir! "test-sess-hdr-chunk-")
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
  (let [dir (test-tmp/temp-dir! "test-sess-hdr-limit-")
        cwd-dir (s/session-dir-for-cwd dir "/home/user/proj")
        big-cwd (str "/home/user/" (support/pad-string 1100000 \d))
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

(t/deftest test-session-build-info
  ;; G15: buildSessionInfo — streaming per-file session info. Header
  ;; required (legacy headerless → nil), name = latest session_info incl.
  ;; explicit clears, messageCount = message entries only, firstMessage =
  ;; first user message, modified = latest message activity, cwd + parent
  ;; path from the header.
  (let [dir (test-tmp/temp-dir! "test-sess-buildinfo-")
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
  (let [dir (test-tmp/temp-dir! "test-sess-buildinfo-hl-")
        f (str dir "/legacy.ednl")]
    (try
      (fs/create-dirs dir)
      (spit f (prn-str {:id "1" :parent-id nil :role :user
                        :content "old" :timestamp (str (java.time.Instant/now))}))
      (t/is (nil? (s/build-session-info f)))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-build-info-name-cleared
  ;; G15: latest session_info wins, incl. explicit clears → nil name
  (let [dir (test-tmp/temp-dir! "test-sess-buildinfo-clear-")
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
  (let [dir (test-tmp/temp-dir! "test-sess-buildinfo-mod-")
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
  ;; a progress callback carrying partial snapshots, walks cwd subdirs,
  ;; excludes legacy headerless files, newest modified first
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-")
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
                                        {:on-progress
                                         (fn [loaded total partial]
                                           (swap! progress conj
                                                  {:loaded loaded :total total
                                                   :partial partial}))})]
        (t/is (= 3 (count infos)) "3 header-bearing sessions, headerless excluded")
        (t/is (= #{(:file sa) (:file sb) (:file legacy)} (set (map :path infos))))
        (t/is (= [4 4] (last (map (juxt :loaded :total) @progress)))
              "progress counts all scanned files, incl. headerless")
        (t/is (= [1 2 3 4] (map :loaded @progress))
              "progress reports every completed file")
        (t/is (apply >= (map :modified infos)) "sorted newest modified first")
        (t/is (some (comp some? :partial) @progress)
              "partial snapshots publish before the listing finishes")
        (doseq [p (keep :partial @progress)]
          (t/is (or (empty? p) (apply >= (map :modified p)))
                "partial snapshots are newest first"))
        (t/is (= (set (map :path infos))
                 (set (map :path (:partial (last @progress)))))
              "the final publish carries the finished snapshot"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-stop
  ;; :stop? ends a listing early: no new files are scheduled, the sessions
  ;; loaded so far are returned, and the rest are never read (closing the
  ;; resume overlay must stop an in-flight scan)
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-stop-")
        stop? (atom false)
        loaded-count (atom 0)]
    (try
      (doseq [i (range 15)]
        (let [sess (s/create-session dir)]
          (s/append-entry sess {:role :assistant :content (str "m" i)})))
      (let [infos (s/list-sessions-info dir
                                        {:stop? (fn [] @stop?)
                                         :on-progress
                                         (fn [loaded _ _]
                                           (reset! loaded-count loaded)
                                           (reset! stop? true))})]
        (t/is (pos? (count infos)) "the sessions loaded so far come back")
        (t/is (<= (count infos) 10)
              "a stop leaves at most the first concurrency window loaded")
        (t/is (< @loaded-count 15) "the remaining files were never read"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-empty-dir
  ;; a real directory with no session files reports nothing and never fires
  ;; progress (the empty listing short-circuits before the scan)
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-empty-")
        progress (atom [])]
    (try
      (fs/create-dirs dir)
      (t/is (= []
               (s/list-sessions-info dir
                                     {:on-progress
                                      (fn [& _] (swap! progress conj :called))})))
      (t/is (empty? @progress))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-headerless-only
  ;; when every file is headerless the listing is empty and its snapshots
  ;; are empty too — progress still reports each file
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-headerless-")
        progress (atom [])]
    (try
      (fs/create-dirs dir)
      (dotimes [i 2]
        (spit (str dir "/legacy" i ".ednl")
              (prn-str {:id (str "l" i) :parent-id nil :role :user
                        :content (str "old" i)
                        :timestamp (str (java.time.Instant/now))})))
      (let [infos (s/list-sessions-info
                   dir
                   {:on-progress
                    (fn [loaded total partial]
                      (swap! progress conj [loaded total partial]))})]
        (t/is (= [] infos))
        (t/is (= [1 2] (map first @progress)) "one update per completed file")
        (t/is (every? (fn [[_ _ partial]] (empty? partial)) @progress)
              "a headerless file never enters a snapshot"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-publish-every
  ;; :publish-every controls only the snapshot cadence: progress still
  ;; fires per file, and the first and last updates always carry a snapshot
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-cadence-")
        progress (atom [])]
    (try
      (dotimes [i 12]
        (let [sess (s/create-session dir)]
          (s/append-entry sess {:role :assistant :content (str "m" i)})))
      (let [infos (s/list-sessions-info
                   dir
                   {:publish-every 100
                    :on-progress
                    (fn [loaded total partial]
                      (swap! progress conj {:loaded loaded
                                            :total total
                                            :partial partial}))})]
        (t/is (= 12 (count infos)))
        (t/is (= (range 1 13) (map :loaded @progress)) "progress fires per file")
        (t/is (= [1 12] (map :loaded (filter :partial @progress)))
              "snapshots only on the first and last update"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-stop-before-start
  ;; an already-true stop predicate prevents every file read and every
  ;; progress update
  (let [dir (test-tmp/temp-dir! "test-sess-listinfo-prestop-")
        progress (atom [])]
    (try
      (let [sess (s/create-session dir)]
        (s/append-entry sess {:role :assistant :content "m"}))
      (t/is (= []
               (s/list-sessions-info
                dir
                {:stop? (fn [] true)
                 :on-progress (fn [& _] (swap! progress conj :called))})))
      (t/is (empty? @progress))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-file-candidates-order
  ;; discovery streams newest-first by mtime, file name descending as the
  ;; tiebreak (pi: candidate stats sort)
  (let [dir (test-tmp/temp-dir! "test-sess-candidates-")
        t0 1700000000000
        mk (fn [name mtime]
             (let [f (str dir "/" name)]
               (spit f "")
               (fs/set-last-modified-time f (java.time.Instant/ofEpochMilli mtime))
               f))]
    (try
      (fs/create-dirs dir)
      (mk "a_newest.ednl" (+ t0 180000))
      (mk "b_oldest.ednl" (+ t0 60000))
      (mk "c_middle.ednl" (+ t0 120000))
      (mk "d_tie.ednl" (+ t0 120000))
      (let [candidates (@#'listing/session-file-candidates dir)]
        (t/is (= ["a_newest.ednl" "d_tie.ednl" "c_middle.ednl" "b_oldest.ednl"]
                 (mapv (comp fs/file-name :path) candidates)))
        (t/is (= [(+ t0 180000) (+ t0 120000) (+ t0 120000) (+ t0 60000)]
                 (mapv :mtime candidates))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-info-nonexistent
  (t/is (= [] (s/list-sessions-info "nonexistent-dir"))))

(t/deftest test-session-delete
  ;; The service and the resume selector share one policy (trash first,
  ;; unlink fallback); -which is stubbed so the test never touches the
  ;; developer's trash.
  (let [session (s/create-session support/test-dir)
        f (:file session)]
    (s/append-entry session {:role :assistant :content "hi"}) ;; persist (lazy G4)
    (t/is (.exists (io/file f)))
    (with-redefs [fs/which (fn [_] nil)]
      (s/delete-session! session))
    (t/is (not (.exists (io/file f))))))

(t/deftest test-session-delete-policy
  (let [dir (test-tmp/temp-dir! "sess-del-")]
    (try
      (testing "no trash CLI: the file is unlinked"
        (let [f (str (fs/path dir "s.ednl"))]
          (spit f "x")
          (with-redefs [fs/which (fn [_] nil)]
            (t/is (= {:ok true :method :unlink} (s/delete-session-file f))))
          (t/is (not (fs/exists? f)))))
      (testing "a file already gone is a success (a concurrent delete wins)"
        (let [missing (str (fs/path dir "gone.ednl"))]
          (with-redefs [fs/which (fn [_] nil)]
            (t/is (= {:ok true :method :unlink} (s/delete-session-file missing))))
          (with-redefs [fs/which (fn [_] "/usr/bin/trash")]
            (t/is (= {:ok true :method :trash} (s/delete-session-file missing))))))
      (testing "a trash failure falls back to the unlink error"
        (let [f (str (fs/path dir "locked.ednl"))]
          (spit f "x")
          (with-redefs [fs/which (fn [_] "/usr/bin/trash")
                        proc/process (fn [& _] (throw (ex-info "no trash" {:type :test})))]
            ;; the trash call throws, the file still exists, so the unlink
            ;; runs and succeeds
            (t/is (= {:ok true :method :unlink} (s/delete-session-file f))))))
      (finally (fs/delete-tree dir)))))

;; ─── Import (pi: agent-session-runtime importFromJsonl) ───────────────────

(t/deftest test-import-plan-and-copy
  (let [dir (str (fs/absolutize (str support/test-dir "-import")))
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

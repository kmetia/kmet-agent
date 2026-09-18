(ns kmet.app.ui.test-session-selector
  "Session selector tests (pi SessionSelectorComponent parity): layout and
   header fields, visible search filtering (fuzzy/phrase/regex), clamped
   navigation + page jumps, Tab scope toggle with cached all-listing,
   sort cycling and threaded tree prefixes, named filter + contextual
   empty states, delete confirmation flow (active-session guard included),
   rename panel flow, and select/cancel wiring."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.session-selector :as ss]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- info
  "A session-info map like kmet.app.session/build-session-info returns."
  [path & {:keys [name first-message modified count cwd parent]}]
  (let [now (now-ms)]
    {:path path
     :id (str "id-" (hash path))
     :cwd (or cwd "/data/data/com.termux/files/home/proj")
     :name name
     :parent-session-path parent
     :created now
     :modified (or modified (- now 3600000))
     :message-count (or count 3)
     :first-message (or first-message "hello world")
     :all-messages-text (or first-message "hello world")}))

(defn- new-sel
  "Build a selector over canned listings; loads are fed synchronously via
   session-selector-set-listing! (the async half is exercised separately)."
  [& {:keys [current all current-session-file on-select on-cancel
             rename-session delete-session]}]
  (let [dir (str (fs/create-dirs (fs/path "target" "test-session-selector-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir)))
  (let [loader (fn [infos] (fn [_] infos))
        sel (ss/make-session-selector
             :loaders {:current (loader (or current []))
                       :all (loader (or all []))}
             :current-session-file current-session-file
             :on-select on-select
             :on-cancel on-cancel
             :rename-session rename-session
             :delete-session delete-session)]
    (when current
      (ss/session-selector-set-listing! sel :current current))
    (when all
      (ss/session-selector-set-listing! sel :all all))
    sel))

(defn- press
  "Feed a raw terminal key sequence (pi parseKey inputs)."
  [sel key]
  (protocols/handle-input
   sel
   (case key
     "up" "\u001b[A"
     "down" "\u001b[B"
     "pageUp" "\u001b[5~"
     "pageDown" "\u001b[6~"
     "enter" "\r"
     "escape" "\u001b"
     "tab" "\t"
     "ctrl+s" "\u0013"
     "ctrl+n" "\u000e"
     "ctrl+p" "\u0010"
     "ctrl+d" "\u0004"
     "ctrl+r" "\u0012"
     "backspace" "\u007f"
     key)))

(defn- render-text
  "The selector's rendered lines, ANSI-stripped."
  [sel width]
  (mapv u/strip-ansi-codes (protocols/render sel width)))

(defn- selected-row-text
  "Text of the row carrying the › cursor, ANSI-stripped."
  [sel width]
  (some #(when (str/includes? % "› ") %) (render-text sel width)))

(defn- wait-for
  "Poll PRED until truthy (async completions land on futures)."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (if (pred)
        true
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          (throw (ex-info "wait-for timeout" {})))))))

;; ─── Layout ─────────────────────────────────────────────────────────────────

(t/deftest layout-border-header-input-rows
  (let [now (now-ms)
        a (info "/tmp/s/a.ednl" :first-message "fix models config" :count 33
                :modified (- now (* 8 3600000)))
        b (info "/tmp/s/b.ednl" :first-message "ls" :count 6
                :modified (- now (* 18 3600000)))
        sel (new-sel :current [a b])
        ls (render-text sel 100)]
    ;; frame: blank, top border, blank, header, hint1, hint2, blank, input,
    ;; blank, rows..., blank, bottom border
    (t/is (str/blank? (str/trim (ls 0))))
    (t/is (re-matches #"[─]+" (str/trim (ls 1))))
    (t/is (str/includes? (ls 3) "Resume Session (Current Folder)"))
    (t/is (str/includes? (ls 3) "◉ Current Folder"))
    (t/is (str/includes? (ls 3) "○ All"))
    (t/is (str/includes? (ls 3) "Name: All"))
    (t/is (str/includes? (ls 3) "Sort: Threaded"))
    (t/is (str/includes? (ls 4) "scope"))
    (t/is (str/includes? (ls 4) "regex"))
    (t/is (str/includes? (ls 5) "sort"))
    (t/is (str/includes? (ls 5) "named"))
    (t/is (str/includes? (ls 5) "delete"))
    (t/is (str/includes? (ls 5) "path (off)"))
    (t/is (str/includes? (ls 5) "rename"))
    (t/is (str/starts-with? (ls 7) "> "))
    ;; rows: cursor on the first, right-aligned count + age, full-width pad
    (t/is (str/includes? (ls 9) "› fix models config"))
    (t/is (str/includes? (ls 9) "33 8h"))
    (t/is (str/includes? (ls 10) "ls"))
    (t/is (str/includes? (ls 10) "6 18h"))
    (t/is (= 100 (count (ls 9))))
    ;; no scroll indicator with everything visible
    (t/is (not-any? #(str/includes? % "(1/2)") ls))))

(t/deftest header-shows-loading-progress
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")])]
    (swap! (:state-atom sel) assoc :current-loading true
           :progress {:loaded 40 :total 174})
    (let [header (nth (render-text sel 100) 3)]
      (t/is (str/includes? header "Loading 40/174"))
      (t/is (str/includes? header "○ Current Folder")))
    (swap! (:state-atom sel) assoc :progress nil)
    (t/is (str/includes? (nth (render-text sel 100) 3) "Loading ..."))))

(t/deftest non-blank-lines-span-panel-width
  "The DSL frame does not full-width-pad blank lines (spacers render empty;
   trailing spaces are invisible). What matters visually — the border, the
   selected row's background bar, and every content line — spans the panel."
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")])]
    (doseq [line (protocols/render sel 80)]
      (when (pos? (u/visible-width line))
        (t/is (= 80 (u/visible-width line)))))))

;; ─── Selection ──────────────────────────────────────────────────────────────

(t/deftest navigation-clamps-no-wrap
  (let [sessions [(info "/tmp/s/a.ednl" :first-message "row one")
                  (info "/tmp/s/b.ednl" :first-message "row two")
                  (info "/tmp/s/c.ednl" :first-message "row three")]
        sel (new-sel :current sessions)]
    (dotimes [_ 5] (press sel "down"))
    (t/is (str/includes? (selected-row-text sel 100) "row three"))
    (dotimes [_ 5] (press sel "up"))
    (t/is (str/includes? (selected-row-text sel 100) "row one"))))

(t/deftest page-jump-and-scroll-indicator
  (let [sessions (mapv #(info (str "/tmp/s/s" % ".ednl")) (range 25))
        sel (new-sel :current sessions)]
    ;; 25 rows clipped to 10 → indicator with the total
    (t/is (some #(str/includes? % "(1/25)") (render-text sel 100)))
    (press sel "pageDown")
    ;; selected = 10, viewport centered: start 5..14, clipped both ends
    (t/is (some #(str/includes? % "(11/25)") (render-text sel 100)))
    (press sel "pageUp")
    (t/is (some #(str/includes? % "(1/25)") (render-text sel 100)))))

(t/deftest enter-selects-escape-cancels
  (let [selected (atom nil)
        cancelled (atom false)
        hidden (atom 0)
        a (info "/tmp/s/a.ednl")
        sel (new-sel :current [a] :on-select (fn [p] (reset! selected p))
                     :on-cancel (fn [] (reset! cancelled true)))]
    (reset! (:hide-fn-atom sel) {:hide (fn [] (swap! hidden inc))})
    (press sel "enter")
    (t/is (= "/tmp/s/a.ednl" @selected))
    (t/is (= 1 @hidden))
    (press sel "escape")
    (t/is @cancelled)
    (t/is (= 2 @hidden))))

(t/deftest enter-on-empty-list-is-a-no-op
  (let [selected (atom nil)
        sel (new-sel :current [] :on-select (fn [p] (reset! selected p)))]
    (press sel "enter")
    (t/is (nil? @selected))))

;; ─── Search ─────────────────────────────────────────────────────────────────

(t/deftest search-input-visible-and-filters
  (let [a (info "/tmp/s/a.ednl" :first-message "install deps.clj now")
        b (info "/tmp/s/b.ednl" :first-message "zz" :cwd "/q/q")
        sel (new-sel :current [a b])]
    (press sel "d")
    (press sel "e")
    (press sel "p")
    (press sel "s")
    (let [ls (render-text sel 100)]
      ;; typed query is visible in the search input
      (t/is (str/includes? (nth ls 7) "> deps"))
      (t/is (some #(str/includes? % "deps.clj") ls))
      (t/is (not-any? #(str/includes? % "zz") ls)))))

(t/deftest search-matches-name-messages-and-cwd
  (let [a (info "/tmp/s/a.ednl" :name "clojure setup")
        b (info "/tmp/s/b.ednl" :first-message "plain talk")
        c (info "/tmp/s/c.ednl" :first-message "misc" :cwd "/x/clojure-dir")
        sel (new-sel :current [a b c])]
    (doseq [ch "clojure"] (press sel (str ch)))
    (let [ls (render-text sel 100)]
      ;; name hit
      (t/is (some #(str/includes? % "clojure setup") ls))
      ;; message-text-only session with no matching subsequence
      (t/is (not-any? #(str/includes? % "plain talk") ls))
      ;; cwd hit
      (t/is (some #(str/includes? % "misc") ls)))))

(t/deftest phrase-search-exact-substring
  (let [a (info "/tmp/s/a.ednl" :first-message "install deps.clj on termux now")
        b (info "/tmp/s/b.ednl" :first-message "deps on clj mind")
        sel (new-sel :current [a b])]
    (doseq [ch "\"deps.clj on te\""] (press sel (str ch)))
    (let [ls (render-text sel 100)]
      (t/is (some #(str/includes? % "install deps.clj") ls))
      (t/is (not-any? #(str/includes? % "deps on clj") ls)))))

(t/deftest regex-search
  (let [a (info "/tmp/s/a.ednl" :first-message "error 42 happened")
        b (info "/tmp/s/b.ednl" :first-message "all fine here")
        sel (new-sel :current [a b])]
    (doseq [ch "re:error \\d+"] (press sel (str ch)))
    (let [ls (render-text sel 100)]
      (t/is (some #(str/includes? % "error 42") ls))
      (t/is (not-any? #(str/includes? % "all fine") ls)))))

(t/deftest invalid-regex-matches-nothing
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl" :first-message "anything")])]
    (doseq [ch "re:error["] (press sel (str ch)))
    (let [ls (render-text sel 100)]
      (t/is (every? #(not (str/includes? % "anything")) (drop 9 ls)))
      ;; the query itself is still visible
      (t/is (str/includes? (nth ls 7) "re:error[")))))

(t/deftest filter-clamps-selection-instead-of-resetting
  (let [a (info "/tmp/s/a.ednl" :first-message "zz target")
        b (info "/tmp/s/b.ednl" :first-message "filler one")
        c (info "/tmp/s/c.ednl" :first-message "filler two")
        sel (new-sel :current [b c a])]
    ;; move to the last row (index 2), then filter down to one hit
    (dotimes [_ 2] (press sel "down"))
    (doseq [ch "zz"] (press sel (str ch)))
    (t/is (some #(str/includes? % "zz target") (render-text sel 100)))
    ;; selection clamped to the single remaining row — no crash, no wrap
    (t/is (some #(str/includes? % "› zz target") (render-text sel 100)))))

(t/deftest backspace-edits-the-query
  (let [a (info "/tmp/s/a.ednl" :first-message "alpha beta")
        sel (new-sel :current [a])]
    (doseq [ch "alphax"] (press sel (str ch)))
    (press sel "backspace")
    (t/is (str/includes? (nth (render-text sel 100) 7) "> alpha"))))

;; ─── Scope ──────────────────────────────────────────────────────────────────

(t/deftest tab-toggles-scope-with-cwd-column
  (let [home (System/getProperty "user.home")
        a (info "/tmp/s/a.ednl" :first-message "in proj" :cwd (str home "/proj"))
        b (info "/tmp/s/b.ednl" :first-message "elsewhere" :cwd "/opt/x")
        sel (new-sel :current [a] :all [a b])]
    (t/is (str/includes? (nth (render-text sel 100) 3) "Resume Session (Current Folder)"))
    (t/is (not-any? #(str/includes? % "~/proj") (render-text sel 100)))
    (press sel "tab")
    (let [ls (render-text sel 100)]
      (t/is (str/includes? (nth ls 3) "Resume Session (All)"))
      (t/is (str/includes? (nth ls 3) "◉ All"))
      (t/is (some #(str/includes? % "~/proj") ls))
      (t/is (some #(str/includes? % "/opt/x") ls)))
    (press sel "tab")
    (t/is (str/includes? (nth (render-text sel 100) 3) "Resume Session (Current Folder)"))))

;; ─── Sort modes ─────────────────────────────────────────────────────────────

(t/deftest sort-cycles-through-three-modes
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")])]
    (t/is (str/includes? (nth (render-text sel 100) 3) "Sort: Threaded"))
    (press sel "ctrl+s")
    (t/is (str/includes? (nth (render-text sel 100) 3) "Sort: Recent"))
    (press sel "ctrl+s")
    (t/is (str/includes? (nth (render-text sel 100) 3) "Sort: Fuzzy"))
    (press sel "ctrl+s")
    (t/is (str/includes? (nth (render-text sel 100) 3) "Sort: Threaded"))))

(t/deftest threaded-mode-builds-tree-prefixes
  (let [now (now-ms)
        parent (info "/tmp/s/root.ednl" :first-message "root session"
                     :modified (- now (* 5 3600000)))
        child (info "/tmp/s/kid.ednl" :first-message "forked work"
                    :parent "/tmp/s/root.ednl" :modified (- now 60000))
        sel (new-sel :current [child parent])]
    (let [ls (render-text sel 100)]
      ;; child sorts under its parent (subtree activity newest first)
      (t/is (some #(str/includes? % "› root session") ls))
      (t/is (some #(and (str/includes? % "└─ forked work")
                        (not (str/includes? % "›"))) ls)))
    ;; non-threaded modes flatten the tree
    (press sel "ctrl+s")
    (t/is (not-any? #(str/includes? % "└─") (render-text sel 100)))))

;; ─── Named filter ───────────────────────────────────────────────────────────

(t/deftest named-filter-toggles-and-empty-state
  (let [named (info "/tmp/s/a.ednl" :name "clojure")
        unnamed (info "/tmp/s/b.ednl" :first-message "anon work")
        sel (new-sel :current [named unnamed])]
    (press sel "ctrl+n")
    (let [ls (render-text sel 100)]
      (t/is (str/includes? (nth ls 3) "Name: Named"))
      (t/is (some #(str/includes? % "clojure") ls))
      (t/is (not-any? #(str/includes? % "anon work") ls)))
    ;; nothing named in the listing → contextual hint
    (let [sel2 (new-sel :current [unnamed])]
      (press sel2 "ctrl+n")
      (t/is (some #(str/includes? % "No named sessions in current folder")
                  (render-text sel2 100))))))

;; ─── Path column ────────────────────────────────────────────────────────────

(t/deftest path-toggle-shows-file-paths
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl" :first-message "work")])]
    (t/is (not-any? #(str/includes? % "/tmp/s/a.ednl") (render-text sel 100)))
    (press sel "ctrl+p")
    (t/is (some #(str/includes? % "/tmp/s/a.ednl") (render-text sel 100)))
    (t/is (str/includes? (nth (render-text sel 100) 5) "path (on)"))))

;; ─── Delete ─────────────────────────────────────────────────────────────────

(t/deftest delete-confirmation-flow
  (let [deleted (atom [])
        backing (atom nil)
        a (info "/tmp/s/a.ednl" :first-message "keep me")
        b (info "/tmp/s/b.ednl" :first-message "delete me")
        _ (reset! backing [a b])
        sel (ss/make-session-selector
             :loaders {:current (fn [_] @backing) :all (fn [_] [])}
             :delete-session (fn [path]
                               (swap! deleted conj path)
                               ;; the world moves on: the file is gone
                               (swap! backing (fn [s] (vec (remove #(= (:path %) path) s))))
                               {:ok true :method :unlink}))]
    (ss/session-selector-set-listing! sel :current @backing)
    ;; arm the confirmation on the second row
    (press sel "down")
    (press sel "ctrl+d")
    (t/is (some #(str/includes? % "Delete session?") (render-text sel 100)))
    ;; escape disarms
    (press sel "escape")
    (t/is (not-any? #(str/includes? % "Delete session?") (render-text sel 100)))
    (t/is (empty? @deleted))
    ;; other keys are swallowed while confirming (nothing deleted yet)
    (press sel "ctrl+d")
    (press sel "x")
    (t/is (= 0 (count @deleted)))
    ;; confirm deletes, reports, and refreshes the listing
    (press sel "enter")
    (wait-for #(= ["/tmp/s/b.ednl"] @deleted))
    (wait-for #(boolean (some (fn [l] (str/includes? l "Session deleted"))
                              (render-text sel 100))))
    ;; the row disappears from the (refreshed) listing
    (wait-for #(not-any? (fn [l] (str/includes? l "delete me"))
                         (render-text sel 100)))))

(t/deftest delete-failure-shows-error
  (let [a (info "/tmp/s/a.ednl")
        sel (new-sel :current [a]
                     :delete-session (fn [_] {:ok false :method :unlink
                                              :error "permission denied"}))]
    (press sel "ctrl+d")
    (press sel "enter")
    (wait-for #(boolean (some (fn [l]
                                (str/includes? l "Failed to delete: permission denied"))
                              (render-text sel 100))))))

(t/deftest cannot-delete-active-session
  (let [a (info "/tmp/s/a.ednl")
        deleted (atom [])
        sel (new-sel :current [a] :current-session-file "/tmp/s/a.ednl"
                     :delete-session (fn [p] (swap! deleted conj p) {:ok true :method :unlink}))]
    (press sel "ctrl+d")
    (t/is (some #(str/includes? % "Cannot delete the currently active session")
                (render-text sel 100)))
    ;; enter would resume the selected (current) session — never deletes
    (t/is (empty? @deleted))))

;; ─── Rename ─────────────────────────────────────────────────────────────────

(t/deftest rename-panel-flow
  (let [renamed (atom [])
        named (info "/tmp/s/a.ednl" :name "old name")
        sel (new-sel :current [named]
                     :rename-session (fn [path name] (swap! renamed conj [path name])))]
    (press sel "ctrl+r")
    (let [ls (render-text sel 100)]
      (t/is (some #(str/includes? % "Rename Session") ls))
      (t/is (some #(str/includes? % "> old name") ls))
      (t/is (some #(str/includes? % "to save") ls)))
    ;; typing edits, escape leaves without saving
    (press sel "X")
    (press sel "escape")
    (t/is (empty? @renamed))
    (t/is (some #(str/includes? % "› old name") (render-text sel 100)))
    ;; re-enter, submit the edited name (the input resets to the current name)
    (press sel "ctrl+r")
    (press sel "Y")
    (press sel "enter")
    (wait-for #(= [["/tmp/s/a.ednl" "old nameY"]] @renamed))
    (wait-for #(not-any? (fn [l] (str/includes? l "Rename Session"))
                         (render-text sel 100)))))

(t/deftest rename-blank-submit-stays-in-mode
  (let [renamed (atom [])
        sel (new-sel :current [(info "/tmp/s/a.ednl" :name "nm")]
                     :rename-session (fn [path name] (swap! renamed conj [path name])))]
    (press sel "ctrl+r")
    (dotimes [_ 2] (press sel "backspace"))
    (press sel "enter")
    (t/is (empty? @renamed))
    (t/is (some #(str/includes? % "Rename Session") (render-text sel 100)))))

;; ─── Empty states ───────────────────────────────────────────────────────────

(t/deftest empty-state-messages
  (let [sel (new-sel :current [])]
    (t/is (some #(str/includes? % "No sessions in current folder. Press Tab to view all.")
                (render-text sel 100)))
    (press sel "tab")
    ;; the all-scope listing loads asynchronously
    (wait-for #(boolean (some (fn [l] (str/includes? l "No sessions found"))
                              (render-text sel 100))))))

;; ─── Presentation details ───────────────────────────────────────────────────

(t/deftest multi-line-first-message-renders-as-one-row
  (let [a (info "/tmp/s/a.ednl" :first-message "bb run\nWarning: classpath")
        sel (new-sel :current [a])]
    (doseq [line (render-text sel 100)]
      (t/is (not (str/includes? line "\n"))))
    (t/is (some #(str/includes? % "bb run Warning: classpath") (render-text sel 100)))))

(t/deftest selected-row-has-background-and-bold
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl" :first-message "pick me")
                               (info "/tmp/s/b.ednl" :first-message "other")])
        raw (protocols/render sel 100)
        selected-line (some #(when (str/includes? % "pick me") %) raw)
        plain-line (some #(when (str/includes? % "other") %) raw)]
    (t/is (re-find #"\u001b\[48;" selected-line))
    (t/is (re-find #"\u001b\[1m" selected-line))
    (t/is (not (re-find #"\u001b\[48;" plain-line)))))

(t/deftest long-messages-truncate-with-ellipsis
  (let [long (apply str (repeat 200 "word "))
        sel (new-sel :current [(info "/tmp/s/a.ednl" :first-message long)])
        ls (render-text sel 60)]
    (t/is (some #(and (str/includes? % "word") (str/includes? % "…")) ls))
    (doseq [line ls] (t/is (<= (count line) 60)))))

(t/deftest age-formatting-buckets
  (let [now (now-ms)
        mk #(info (str "/tmp/s/age" % ".ednl") :modified %2)
        sel (new-sel :current [(mk 1 (- now 30000))            ; now
                               (mk 2 (- now (* 5 60000)))       ; 5m
                               (mk 3 (- now (* 3 3600000)))     ; 3h
                               (mk 4 (- now (* 2 86400000)))    ; 2d
                               (mk 5 (- now (* 15 86400000)))   ; 2w
                               (mk 6 (- now (* 100 86400000)))  ; 3mo
                               (mk 7 (- now (* 800 86400000)))]) ; 2y
        ls (render-text sel 120)]
    (t/is (some #(str/includes? % "now") ls))
    (t/is (some #(str/includes? % "5m") ls))
    (t/is (some #(str/includes? % "3h") ls))
    (t/is (some #(str/includes? % "2d") ls))
    (t/is (some #(str/includes? % "2w") ls))
    (t/is (some #(str/includes? % "3mo") ls))
    (t/is (some #(str/includes? % "2y") ls))))

(t/deftest focus-forwards-to-search-input
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")])]
    (protocols/set-focused! sel true)
    (t/is (protocols/focused sel))
    (t/is (protocols/focused (:search-input sel)))
    (protocols/set-focused! sel false)
    (t/is (not (protocols/focused (:search-input sel))))))

(t/deftest async-load-completes-and-refreshes
  (let [done (promise)
        sel (ss/make-session-selector
             :loaders {:current (fn [_] (deliver done true)
                                  [(info "/tmp/s/async.ednl" :first-message "async row")])
                       :all (fn [_] [])})]
    (ss/session-selector-set-listing! sel :current [])
    ;; drive the async half: load-scope! is private, so emulate what it does
    ;; after the loader returns — feed through the public entry point
    (future
      (let [infos ((get-in sel [:loaders :current]) (fn [_ _]))]
        (ss/session-selector-set-listing! sel :current infos)))
    (wait-for #(realized? done))
    (wait-for #(boolean (some (fn [l] (str/includes? l "async row"))
                              (render-text sel 100))))))

;; ─── Panel lifecycle: reactive body + dispose ──────────────────────────────

(t/deftest root-body-memoizes-idle-frames
  ;; the body reads the state atom through tracked-deref: an idle re-render
  ;; hands back the cached tree (0 bodies run), a state change re-derives once
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")
                               (info "/tmp/s/b.ednl")])]
    (render-text sel 100)
    (hiccup/reset-counters!)
    (render-text sel 100)
    (t/is (zero? (:bodies-run (hiccup/counters))) "idle frame: body cached")
    (t/is (= 1 (:bodies-skipped (hiccup/counters))))
    (hiccup/reset-counters!)
    (press sel "down")
    (render-text sel 100)
    (t/is (= 1 (:bodies-run (hiccup/counters))) "state change re-derives once")))

(t/deftest dispose-unwinds-the-root-and-inputs
  ;; the close path (hide!) disposes the panel: the root reaction and the two
  ;; foreign inputs must unwind — the dock drops records without disposing
  (let [sel (new-sel :current [(info "/tmp/s/a.ednl")])
        watchers #(count @(deref #'macros/watch-registry))
        before (watchers)]
    (render-text sel 100)
    (t/is (> (watchers) before) "a rendered panel registers track! watches")
    (protocols/dispose sel)
    (t/is (= before (watchers)) "dispose unwinds the root and both inputs")
    (protocols/dispose sel)
    (t/is (= before (watchers)) "dispose is idempotent")))

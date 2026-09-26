(ns kmet.app.test-context
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.context :as context]
            [kmet.test-utils :refer [slash]]))

(defn- tmp-dir
  "Writable temp dir under target/ (ancestors may contain uncontrolled
   AGENTS.md files, e.g. this repo's own — assertions account for that)."
  [suffix]
  (str (fs/absolutize (fs/file "target" (str "test-ctx-" suffix "-" (System/currentTimeMillis))))))

(defn- under-tmp?
  "True when a path string is inside the temp tree (separator-agnostic: bb
   renders \\ between components, jolt /)."
  [tmp p]
  (str/starts-with? (slash (str p)) (str (slash tmp) "/")))

(defn- git!
  "Run a git command inside DIR. Returns trimmed stdout."
  [dir & args]
  (str/trim (:out (apply proc/shell {:out :string :err :string :continue true}
                         "git" "-C" dir args))))

(t/deftest test-agents-override-precedence
  (t/testing "AGENTS.override.md wins over AGENTS.md in the same directory"
    (let [tmp (tmp-dir "override")
          agent-dir (str tmp "/agent")]
      (try
        (io/make-parents (str agent-dir "/AGENTS.md"))
        (io/make-parents (str agent-dir "/AGENTS.override.md"))
        (spit (str agent-dir "/AGENTS.md") "# base rules")
        (spit (str agent-dir "/AGENTS.override.md") "# override rules")
        (let [files (context/load-project-context-files agent-dir agent-dir)
              mine (filter #(under-tmp? tmp (:path %)) files)
              f (first mine)]
          (t/is (= 1 (count mine)))
          (t/is (str/ends-with? (:path f) "AGENTS.override.md"))
          (t/is (= "# override rules" (:content f))))
        (finally (fs/delete-tree tmp))))))

(t/deftest ^:slow test-worktree-shadow-detection
  (t/testing "nested linked worktree: main repo's AGENTS.md not double-loaded"
    (let [tmp (tmp-dir "worktree")]
      (try
        (fs/create-dirs tmp)
        (git! tmp "init" "-q")
        (git! tmp "config" "user.email" "test@test")
        (git! tmp "config" "user.name" "Test")
        (spit (str tmp "/AGENTS.md") "# main rules")
        (git! tmp "add" "AGENTS.md")
        (git! tmp "commit" "-q" "-m" "init")
        (let [wt (str tmp "/wt")]
          (git! tmp "worktree" "add" "-q" "-b" "feature" wt)
          (spit (str wt "/AGENTS.md") "# worktree rules")
          (let [files (context/load-project-context-files (str tmp "/agent") wt)
                mine (filter #(under-tmp? tmp (:path %)) files)
                paths (map :path mine)]
            (t/is (= 1 (count mine)))
            (t/is (= (slash (str wt "/AGENTS.md")) (slash (first paths))))
            (t/is (= "# worktree rules" (:content (first mine))))))
        ;; .git/objects files are read-only on Windows — delete-tree needs :force
        (finally (fs/delete-tree tmp {:force true})))))
  (t/testing "sibling worktree (not nested): both context files load"
    (let [tmp (tmp-dir "sibling")
          sibling (str tmp "-sib")]
      (try
        (fs/create-dirs tmp)
        (git! tmp "init" "-q")
        (git! tmp "config" "user.email" "test@test")
        (git! tmp "config" "user.name" "Test")
        (spit (str tmp "/AGENTS.md") "# main rules")
        (git! tmp "add" "AGENTS.md")
        (git! tmp "commit" "-q" "-m" "init")
        ;; a SIBLING worktree lives OUTSIDE the main repo dir (pi:
        ;; `git worktree add ../feat`) — findShadowedContextFile returns
        ;; undefined (the main repo's .git is not the common git dir), and
        ;; the main repo is not an ancestor of the sibling's cwd anyway, so
        ;; only the sibling's own context file loads
        (git! tmp "worktree" "add" "-q" "-b" "feature" sibling)
        (spit (str sibling "/AGENTS.md") "# worktree rules")
        (let [files (context/load-project-context-files (str sibling "/agent") sibling)
              under? #(str/starts-with? (slash (str (:path %))) (str (slash sibling) "/"))
              mine (filter under? files)]
          (t/is (= [(slash (str sibling "/AGENTS.md"))]
                   (slash (mapv :path mine)))
                "only the sibling's own AGENTS.md loads (no shadow, no main-repo ancestor)"))
        (finally (fs/delete-tree tmp {:force true})
                 (fs/delete-tree sibling {:force true}))))))

(t/deftest test-load-project-context-files
  (t/testing "agent dir file first, then nearest ancestor first (pi order)"
    (let [tmp (tmp-dir "order")
          agent-dir (str tmp "/agent")
          dir-a (str tmp "/a")
          dir-b (str tmp "/a/b")
          agent-file (str agent-dir "/AGENTS.md")
          a-file (str dir-a "/AGENTS.md")
          b-file (str dir-b "/CLAUDE.md")]
      (try
        (io/make-parents agent-file)
        (io/make-parents a-file)
        (io/make-parents b-file)
        (spit agent-file "# agent rules")
        (spit a-file "# a rules")
        (spit b-file "# b rules")
        (let [files (context/load-project-context-files agent-dir dir-b)]
          ;; take 3: ancestors above the temp tree may add more files
          (t/is (= (slash [agent-file b-file a-file])
                   (slash (take 3 (map :path files))))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "missing context files contribute nothing (pi)"
    (let [tmp (tmp-dir "empty")
          x-dir (str tmp "/x")]
      (try
        (io/make-parents (str x-dir "/.keep"))
        (let [files (context/load-project-context-files (str tmp "/agent") x-dir)]
          (t/is (not-any? #(under-tmp? tmp %) (map :path files))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "same file deduped when agent dir is inside the cwd chain (pi)"
    (let [tmp (tmp-dir "dedupe")
          agent-dir (str tmp "/agent")
          f (str agent-dir "/AGENTS.md")]
      (try
        (io/make-parents f)
        (spit f "# rules")
        (let [files (context/load-project-context-files agent-dir agent-dir)]
          (t/is (= (slash f) (slash (first (map :path files)))))
          (t/is (= 1 (count (filter #(under-tmp? tmp %) (map :path files))))))
        (finally (fs/delete-tree tmp))))))

;; NOTE: git worktree tests spawn git (slow) — they live in test_context.clj
;; because they exercise context.clj directly; keep them ^:slow so the
;; default `bb test` run stays fast.

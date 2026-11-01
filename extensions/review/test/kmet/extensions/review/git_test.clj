(ns kmet.extensions.review.git-test
  "Tests for the review extension's git helpers. The `ext/exec` api
   capability dispatches through the host (which we don't have in a
   unit test), so we wrap babashka.process/sh directly here."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.extensions.review.git :as g]
            [kmet.test-tmp :as test-tmp]))

;; -- Helpers ------------------------------------------------------------

(defn- sh
  "Run a shell command, returning the :exit code."
  [cmd & args]
  (:exit (apply p/sh cmd args [:out :string :err :string])))

(defn- exec
  "Build a minimal api with :exec that runs commands in DIR."
  [dir]
  {:exec (fn [command args _opts]
           (let [r (p/sh (into [command] args)
                         {:out :string :err :string :dir dir})]
             {:exit (:exit r) :out (:out r) :err (:err r)}))})

(defn- setup-repo
  "Create a throwaway git repo with two branches (main, feature) and
   three commits. Returns the directory path."
  []
  (let [dir (test-tmp/temp-dir! "kmet-review-")]
    (p/sh "mkdir" "-p" dir)
    (p/sh "git" "-C" dir "init" "-q" "-b" "main")
    (p/sh "git" "-C" dir "config" "user.email" "test@test")
    (p/sh "git" "-C" dir "config" "user.name" "Test")
    (spit (str dir "/README.md") "hello\n")
    (p/sh "git" "-C" dir "add" ".")
    (p/sh "git" "-C" dir "commit" "-q" "-m" "Initial commit")
    (spit (str dir "/file.txt") "more\n")
    (p/sh "git" "-C" dir "add" ".")
    (p/sh "git" "-C" dir "commit" "-q" "-m" "Second commit")
    (p/sh "git" "-C" dir "checkout" "-q" "-b" "feature")
    (spit (str dir "/feature.txt") "feat\n")
    (p/sh "git" "-C" dir "add" ".")
    (p/sh "git" "-C" dir "commit" "-q" "-m" "Add feature")
    (p/sh "git" "-C" dir "checkout" "-q" "main")
    dir))

(def ^:private test-dir (atom nil))

(defn- with-test-repo [f]
  (let [dir (setup-repo)]
    (reset! test-dir dir)
    (f dir)))

;; -- Tests --------------------------------------------------------------

(deftest ^:bb-only in-git-repo?-in-repo-test
  (with-test-repo
    (fn [dir]
      (is (true? (g/in-git-repo? (exec dir)))))))

(deftest ^:bb-only in-git-repo?-out-of-repo-test
  ;; The subject is "no parent repository", so the path cannot come from the
  ;; run's scratch root (kmet.test-tmp) — that lives inside this checkout,
  ;; whose own .git a walk up would find. A system temp dir has no parent
  ;; git; the test removes it itself rather than leaving it behind.
  (let [non-repo (str (or (System/getenv "TMPDIR")
                          (System/getProperty "java.io.tmpdir"))
                      "/kmet-no-git-" (System/nanoTime))]
    (.mkdirs (java.io.File. non-repo))
    (try
      (is (false? (g/in-git-repo? (exec non-repo))))
      (finally (p/sh "rm" "-rf" non-repo)))))

(deftest ^:bb-only local-branches-test
  (with-test-repo
    (fn [dir]
      (let [branches (g/local-branches (exec dir))]
        (is (contains? (set branches) "main"))
        (is (contains? (set branches) "feature"))
       ;; no "remotes/" entries
        (is (not-any? #(str/starts-with? % "remotes/") branches))))))

(deftest ^:bb-only recent-commits-test
  (with-test-repo
    (fn [dir]
     ;; repo has 2 commits on main + 1 on feature
      (let [commits (g/recent-commits (exec dir) 10)]
       ;; We end on main, so log --oneline shows 2
        (is (= 2 (count commits)))
        (is (every? :sha commits))
        (is (every? :title commits))
        (is (every? #(re-matches #"[0-9a-f]{7,40}" (:sha %)) commits))))))

(deftest ^:bb-only recent-commits-limit-test
  (with-test-repo
    (fn [dir]
      (let [commits (g/recent-commits (exec dir) 2)]
        (is (= 2 (count commits)))))))

(deftest ^:bb-only current-branch-test
  (with-test-repo
    (fn [dir]
      (is (= "main" (g/current-branch (exec dir)))))))

(deftest ^:bb-only default-branch-test
  (with-test-repo
    (fn [dir]
     ;; No origin/HEAD configured in our throwaway repo, so it falls
     ;; back to "main" (which exists in the local branches).
      (is (= "main" (g/default-branch (exec dir)))))))

(deftest ^:bb-only uncommitted?-clean-test
  (with-test-repo
    (fn [dir]
      (is (false? (g/uncommitted? (exec dir))))
      (is (false? (g/pending-changes? (exec dir)))))))

(deftest ^:bb-only uncommitted?-untracked-test
  (with-test-repo
    (fn [dir]
      (spit (str dir "/new.txt") "x")
      (is (true? (g/uncommitted? (exec dir))))
     ;; untracked files do NOT block branch switching
      (is (false? (g/pending-changes? (exec dir)))))))

(deftest ^:bb-only pending-changes?-modified-test
  (with-test-repo
    (fn [dir]
      (spit (str dir "/README.md") "modified\n")
      (is (true? (g/uncommitted? (exec dir))))
     ;; modified tracked file blocks branch switching
      (is (true? (g/pending-changes? (exec dir)))))))

(deftest ^:bb-only merge-base-fallback-test
  ;; `merge-base` first tries the upstream tracking branch (which
  ;; doesn't exist in our throwaway repo); the fallback should still
  ;; resolve a sha via `git merge-base HEAD <branch>`.
  (with-test-repo
    (fn [dir]
      (let [sha (g/merge-base-fallback (exec dir) "feature")]
        (is (string? sha))
        (is (re-matches #"[0-9a-f]{7,40}" sha))))))

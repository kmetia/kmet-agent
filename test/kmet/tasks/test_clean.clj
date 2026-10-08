(ns kmet.tasks.test-clean
  "kmet.tasks.clean — the `clean` task: its artifact list, path expansion, size
   accounting and the git guard. The end-to-end run uses a sandbox root under
   target/ (gitignored, and without a .git of its own, so the guard stays off
   and no git runs); the guard needs a real repository, hence its ^:slow
   test."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.clean :as clean]))

(def ^:private artifacts @#'clean/artifacts)
(def ^:private expand #'clean/expand)
(def ^:private hr-size #'clean/hr-size)

(defn- sandbox
  "A scratch project root under target/."
  []
  (str (fs/create-temp-dir {:dir "target" :prefix "clean-test-"})))

(defn- touch!
  "Create ROOT-relative REL (with parents) holding \"x\"."
  [root rel]
  (let [path (fs/path root rel)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) "x")
    (str path)))

(defn- capture!
  "Run clean! with its report captured: [{:keys [removed kept bytes]} output]."
  [opts]
  (let [out (java.io.StringWriter.)
        result (binding [*out* out] (clean/clean! opts))]
    [result (str out)]))

(defn- exists-under?
  [root rel]
  (fs/exists? (fs/path root rel)))

(deftest test-artifact-patterns-are-project-local
  (testing "no pattern can reach outside the root (the guard only knows the
            tree below it), and none targets the repository metadata"
    (doseq [{:keys [paths]} artifacts
            path paths]
      (is (not (str/starts-with? path "/")) path)
      (is (not (str/includes? path "..")) path)
      (is (not (re-find #"(^|/)\.git(/|$)" path)) path))))

(deftest test-expand
  (let [root (sandbox)]
    (try
      (touch! root "target/kmet.jar")
      (touch! root "extensions/foo/target/x.jar")
      (touch! root "extensions/bar/README.md")
      (touch! root "debug.log")
      (testing "a literal is the path itself, when it exists"
        (is (= [(str (fs/path root "target"))] (@expand root "target")))
        (is (= [] (@expand root "dist"))))
      (testing "a glob finds every hit, hidden directories included"
        (is (= [(str (fs/path root "extensions/foo/target"))]
               (@expand root "extensions/*/target")))
        (is (= [(str (fs/path root "debug.log"))] (@expand root "*.log"))))
      (finally (fs/delete-tree root)))))

(deftest test-human-size
  (is (= "0 B" (@hr-size 0)))
  (is (= "512 B" (@hr-size 512)))
  (is (= "1.0 KiB" (@hr-size 1024)))
  (is (= "1.5 KiB" (@hr-size 1536)))
  (is (= "1.0 MiB" (@hr-size (* 1024 1024))))
  (is (str/ends-with? (@hr-size (* 3 1024 1024 1024)) "GiB")))

(deftest test-clean-removes-artifacts-and-keeps-source
  (let [root (sandbox)
        gone ["target/kmet.jar" "target/bb-lint/src/a.clj" "dist/kmet-bin"
              "extensions/foo/target/x.jar" ".cpcache/basis" ".jolt/cpcache/k.edn"
              ".clj-kondo/.cache/analysis" "debug.log" "kmet.error.log"
              ".kmet-changed-baseline"]
        kept ["src/kmet/core.clj" ".clj-kondo/config.edn" ".kmet/settings.edn"
              ".kmet/prompts/test.md" ".lsp/settings.edn" "target-ish.txt"]]
    (try
      (doseq [rel (concat gone kept)] (touch! root rel))
      (let [[{:keys [removed kept bytes dry-run?]} out] (capture! {:root root})]
        (testing "every artifact is removed"
          (is (every? #(not (exists-under? root %)) gone))
          (is (= #{"target" "dist" "extensions/foo/target" ".cpcache" ".jolt"
                   ".clj-kondo/.cache" "debug.log" "kmet.error.log"
                   ".kmet-changed-baseline"}
                 (set removed))))
        (testing "source, config and user state stay"
          (is (every? #(exists-under? root %) kept))
          (is (empty? kept)))
        (testing "the report names each removal and the total"
          (is (str/includes? out "clean: removed target"))
          (is (str/includes? out "clean: removed .clj-kondo/.cache"))
          (is (str/includes? out "clean: 9 path(s) removed"))
          (is (pos? bytes))
          (is (false? dry-run?))))
      (finally (fs/delete-tree root)))))

(deftest test-dry-run-changes-nothing
  (let [root (sandbox)]
    (try
      (touch! root "target/kmet.jar")
      (touch! root "dist/kmet-bin")
      (let [[{:keys [removed bytes dry-run?]} out] (capture! {:root root :dry-run? true})]
        (is (exists-under? root "target/kmet.jar"))
        (is (exists-under? root "dist/kmet-bin"))
        (is (true? dry-run?))
        (is (= #{"target" "dist"} (set removed)))
        (is (pos? bytes))
        (is (str/includes? out "clean: would remove target"))
        (is (str/includes? out "dry run")))
      (finally (fs/delete-tree root)))))

(deftest test-empty-root-reports-nothing-to-remove
  (let [root (sandbox)]
    (try
      (let [[result out] (capture! {:root root})]
        (is (= [] (:removed result)))
        (is (= [] (:kept result)))
        (is (= 0 (:bytes result)))
        (is (str/includes? out "clean: nothing to remove")))
      (finally (fs/delete-tree root)))))

(defn- git!
  [root & args]
  (let [res (apply p/shell {:continue true :out :string :err :string}
                   "git" "-C" (str root) (concat ["-c" "user.email=t@t" "-c" "user.name=t"] args))]
    (when-not (zero? (:exit res))
      (throw (ex-info (str "git " (str/join " " args) " failed: " (:err res))
                      {:exit (:exit res)})))
    res))

(deftest ^:slow test-git-guard-keeps-tracked-trees
  (let [root (sandbox)]
    (try
      (touch! root "src/keep.clj")
      (touch! root "target/keep.jar")
      (touch! root "dist/kmet-bin")
      (git! root "init" "-q")
      (git! root "add" "-f" "src/keep.clj" "target/keep.jar")
      (let [[{:keys [removed kept]} out] (capture! {:root root})]
        (testing "a tree holding a tracked file is skipped whole"
          (is (exists-under? root "target/keep.jar"))
          (is (= ["target"] kept))
          (is (str/includes? out "clean: kept target (tracked by git)")))
        (testing "its untracked siblings still go"
          (is (not (exists-under? root "dist")))
          (is (= ["dist"] removed))))
      ;; .git/objects files are read-only on Windows — delete-tree needs :force
      (finally (fs/delete-tree root {:force true})))))

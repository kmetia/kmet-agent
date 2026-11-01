(ns kmet.app.test-git-packages
  "Git package sources (pi: utils/git.ts + the git half of
   core/package-manager.ts): URL parsing and identity, the managed-checkout
   layout, and the install/update/remove lifecycle. The lifecycle tests run
   real git subprocesses and are marked ^:slow (they belong to `bb
   test-ext`); parsing and layout tests are fast."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [kmet.app.git-packages :as git]
            [kmet.test-utils :refer [slash]]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-"))

;; ─── URL parsing (pi: parseGitUrl) ────────────────────────────────────────

(t/deftest test-parse-git-url
  (t/testing "git: host/path shorthand"
    (let [p (git/parse-git-url "git:github.com/user/repo")]
      (t/is (= "github.com" (:host p)))
      (t/is (= "user/repo" (:path p)))
      (t/is (= "https://github.com/user/repo" (:repo p)))
      (t/is (nil? (:ref p)))
      (t/is (false? (:pinned? p)))))

  (t/testing "@ref pins a checkout"
    (let [p (git/parse-git-url "git:github.com/user/repo@v1.2.0")]
      (t/is (= "v1.2.0" (:ref p)))
      (t/is (true? (:pinned? p)))
      (t/is (= "https://github.com/user/repo" (:repo p)))))

  (t/testing "#ref pins a checkout"
    (let [p (git/parse-git-url "https://github.com/user/repo#v1")]
      (t/is (= "v1" (:ref p)))
      (t/is (= "https://github.com/user/repo" (:repo p)))))

  (t/testing "scp-like form needs the git: prefix"
    (let [p (git/parse-git-url "git:git@github.com:user/repo")]
      (t/is (= "git@github.com:user/repo" (:repo p)))
      (t/is (= "github.com" (:host p))))
    (let [p (git/parse-git-url "git:git@github.com:user/repo@v1")]
      (t/is (= "git@github.com:user/repo" (:repo p)))
      (t/is (= "v1" (:ref p)))))

  (t/testing "explicit protocol URLs parse without the prefix"
    (t/is (= "github.com" (:host (git/parse-git-url "https://github.com/user/repo"))))
    (t/is (= "host" (:host (git/parse-git-url "ssh://git@host/user/repo"))))
    (t/is (= "host" (:host (git/parse-git-url "git://host/user/repo"))))
    (t/is (= "github.com" (:host (git/parse-git-url "git:https://github.com/user/repo")))))

  (t/testing "the .git suffix is stripped from the path, not the clone URL"
    (let [p (git/parse-git-url "https://github.com/user/repo.git")]
      (t/is (= "user/repo" (:path p)))
      (t/is (= "https://github.com/user/repo.git" (:repo p)))))

  (t/testing "bare user/repo shorthand resolves to GitHub (hosted-git-info)"
    (let [p (git/parse-git-url "git:user/repo")]
      (t/is (= "github.com" (:host p)))
      (t/is (= "user/repo" (:path p)))))

  (t/testing "non-git sources"
    (t/is (nil? (git/parse-git-url "github.com/user/repo")))
    (t/is (nil? (git/parse-git-url "npm:@foo/bar")))
    (t/is (nil? (git/parse-git-url "./local")))
    (t/is (nil? (git/parse-git-url "git:")))
    (t/is (nil? (git/parse-git-url ""))))

  (t/testing "incomplete or unsafe paths are rejected"
    (t/is (nil? (git/parse-git-url "git:github.com/user")))
    (t/is (nil? (git/parse-git-url "git:github.com/../../etc")))
    (t/is (nil? (git/parse-git-url "git:github.com/user/%2e%2e/repo")))
    (t/is (nil? (git/parse-git-url "git:host/user\\evil")))
    (t/is (nil? (git/parse-git-url "https://example.com/file.zip"))))

  (t/testing "an option-looking or malformed ref is rejected (argument injection)"
    (t/is (nil? (git/parse-git-url "git:github.com/user/repo@--upload-pack=/tmp/evil")))
    (t/is (nil? (git/parse-git-url "git:github.com/user/repo@v1..2")))
    (t/is (nil? (git/parse-git-url "git:github.com/user/repo@main~1")))
    (t/testing "ordinary refs still parse"
      (t/is (= "v1.2.0" (:ref (git/parse-git-url "git:github.com/user/repo@v1.2.0"))))
      (t/is (= "refs/heads/main" (:ref (git/parse-git-url "git:github.com/user/repo@refs/heads/main"))))))

  (t/testing "//subdir names a directory inside the repository"
    (let [p (git/parse-git-url "git:github.com/user/repo//packages/foo")]
      (t/is (= "packages/foo" (:dir p)))
      (t/is (= "user/repo" (:path p)))
      (t/is (= "https://github.com/user/repo" (:repo p))))
    (t/is (= "packages/foo" (:dir (git/parse-git-url "git:github.com/user/repo@v1//packages/foo"))))
    (t/is (= "v1" (:ref (git/parse-git-url "git:github.com/user/repo#v1//packages/foo"))))
    (t/is (= "packages/foo" (:dir (git/parse-git-url "git:https://github.com/user/repo//packages/foo"))))
    (t/is (= "dir" (:dir (git/parse-git-url "git:git@github.com:user/repo//dir"))))
    (t/is (= "packages/foo" (:dir (git/parse-git-url "git:github.com/user/repo//./packages/foo/"))))
    (t/testing "a scheme's // is not the separator"
      (t/is (nil? (:dir (git/parse-git-url "git:https://github.com/user/repo"))))
      (t/is (nil? (:dir (git/parse-git-url "ssh://git@host/user/repo")))))
    (t/testing "unsafe or empty subdirectories are rejected"
      (t/is (nil? (git/parse-git-url "git:github.com/user/repo//../evil")))
      (t/is (nil? (git/parse-git-url "git:github.com/user/repo//")))
      (t/is (nil? (git/parse-git-url "git:github.com/user/repo//a//b")))
      (t/is (nil? (git/parse-git-url "git:github.com/user/repo//a\nb"))))))

(t/deftest test-git-identity
  (let [identity #(git/git-identity (git/parse-git-url %))]
    (t/testing "URL form and ref do not affect identity"
      (t/is (= (identity "https://github.com/user/repo")
               (identity "git:github.com/user/repo@v1")))
      (t/is (= (identity "git:git@github.com:user/repo")
               (identity "git:github.com/user/repo")))
      (t/is (= "git:github.com/user/repo" (identity "git:user/repo"))))
    (t/testing "the subdirectory is part of the package identity"
      (t/is (= "git:github.com/user/repo//packages/a"
               (identity "git:github.com/user/repo//packages/a")))
      (t/is (not= (identity "git:github.com/user/repo//packages/a")
                  (identity "git:github.com/user/repo//packages/b")))
      (t/is (not= (identity "git:github.com/user/repo")
                  (identity "git:github.com/user/repo//packages/a"))))
    (t/testing "the repository identity ignores ref and subdirectory"
      (t/is (= "github.com/user/repo"
               (git/repo-identity (git/parse-git-url "git:github.com/user/repo@v1//a")))))
    (t/testing "different repos differ"
      (t/is (not= (identity "git:github.com/user/one")
                  (identity "git:github.com/user/two"))))))

;; ─── Managed checkout layout ──────────────────────────────────────────────

(t/deftest test-install-path
  (let [root (str (fs/path (tmp-dir) "git"))
        source (git/parse-git-url "git:github.com/user/repo")]
    (t/is (= (slash (str (fs/path root "github.com" "user" "repo")))
             (slash (git/install-path root source))))
    (t/testing "package-dir adds a subdirectory to the shared checkout"
      (let [sub (git/parse-git-url "git:github.com/user/repo//packages/foo")]
        (t/is (= (slash (str (fs/path root "github.com" "user" "repo")))
                 (slash (git/install-path root sub))))
        (t/is (= (slash (str (fs/path root "github.com" "user" "repo"
                                      "packages" "foo")))
                 (slash (git/package-dir sub root))))))
    (t/testing "package-dir re-checks containment for hand-built sources"
      (t/is (thrown? Exception
                     (git/package-dir {:host "github.com" :path "user/repo" :dir "../evil"}
                                      root))))
    (t/testing "a hostile host/path cannot escape the root"
      (t/is (thrown? Exception
                     (git/install-path root {:host ".." :path "user/repo"})))
      (t/is (thrown? Exception
                     (git/install-path root {:host "github.com" :path "../../etc"}))))))

;; ─── Lifecycle (pi: installGit / updateGit / removeGit) ───────────────────

(defn- git!
  "Run git ARGS in DIR (nil = the process cwd), failing the test on a
   non-zero exit. Commits get a throwaway identity so no global git config
   is needed."
  [dir & args]
  (let [{:keys [exit err]} (apply p/shell
                                  {:out :string :err :string :dir dir
                                   :extra-env {"GIT_AUTHOR_NAME" "kmet-test"
                                               "GIT_AUTHOR_EMAIL" "kmet-test@example.com"
                                               "GIT_COMMITTER_NAME" "kmet-test"
                                               "GIT_COMMITTER_EMAIL" "kmet-test@example.com"}}
                                  "git" args)]
    (t/is (zero? exit) (str "git " (str/join " " args) ": " err))))

(defn- make-origin
  "A bare origin plus a work clone holding one commit on main; returns
   {:bare BARE :work WORK}. The bare path doubles as the clone URL."
  [base]
  (let [bare (str (fs/path base "origin.git"))
        work (str (fs/path base "work"))]
    (git! nil "init" "--bare" "-b" "main" bare)
    (git! nil "init" "-b" "main" work)
    (spit (str (fs/path work "ext.clj")) "(ns pkg-one)\n")
    (git! work "add" ".")
    (git! work "commit" "-m" "one")
    (git! work "remote" "add" "origin" bare)
    (git! work "push" "-u" "origin" "main")
    {:bare bare :work work}))

(defn- local-source
  "A parsed-source map for a fixture repository at URL (a local path)."
  [url]
  (assoc (git/parse-git-url "git:example.com/user/repo") :repo url))

(defn- commit-change!
  [work content message]
  (spit (str (fs/path work "ext.clj")) content)
  (git! work "add" ".")
  (git! work "commit" "-m" message)
  (git! work "push"))

(t/deftest ^:slow test-install-update-remove-lifecycle
  (let [base (tmp-dir)
        {:keys [bare work]} (make-origin base)
        root (str (fs/path base "git"))
        source (local-source bare)
        installed (git/install! source root)]
    (t/testing "install clones into root/host/path and seeds the .gitignore"
      (t/is (= (slash (str (fs/path root "example.com" "user" "repo")))
               (slash installed)))
      (t/is (= "(ns pkg-one)\n" (slurp (str (fs/path installed "ext.clj")))))
      (t/is (fs/exists? (str (fs/path root ".gitignore")))))

    (t/testing "an unpinned update follows the origin upstream"
      (commit-change! work "(ns pkg-two)\n" "two")
      (t/is (= installed (git/update! source root)))
      (t/is (= "(ns pkg-two)\n" (slurp (str (fs/path installed "ext.clj"))))))

    (t/testing "install on an existing checkout reconciles it"
      (commit-change! work "(ns pkg-three)\n" "three")
      (git/install! source root)
      (t/is (= "(ns pkg-three)\n" (slurp (str (fs/path installed "ext.clj"))))))

    (t/testing "remove deletes the checkout and prunes empty parents"
      (git/remove! source root)
      (t/is (not (fs/exists? installed)))
      (t/is (fs/exists? root))
      (t/is (not (fs/exists? (str (fs/path root "example.com"))))))))

(t/deftest ^:slow test-pinned-ref-lifecycle
  (let [base (tmp-dir)
        {:keys [bare work]} (make-origin base)
        root (str (fs/path base "git"))]
    (git! work "tag" "v1")
    (git! work "push" "origin" "v1")
    (let [pinned (assoc (local-source bare) :ref "v1" :pinned? true)
          installed (git/install! pinned root)]
      (t/testing "a pinned install checks out the ref"
        (t/is (= "(ns pkg-one)\n" (slurp (str (fs/path installed "ext.clj"))))))

      (t/testing "moving the pin follows the moved ref on update"
        (commit-change! work "(ns pkg-two)\n" "two")
        (git! work "tag" "-f" "v1")
        (git! work "push" "--force" "origin" "v1")
        (git/update! pinned root)
        (t/is (= "(ns pkg-two)\n" (slurp (str (fs/path installed "ext.clj")))))))))

(t/deftest ^:slow test-failed-clone-cleans-up
  (let [base (tmp-dir)
        root (str (fs/path base "git"))
        target (git/install-path root (git/parse-git-url "git:example.com/user/repo"))]
    (t/is (thrown? Exception
                   (git/install! (local-source (str (fs/path base "nope"))) root)))
    (t/testing "no partial checkout and no empty host/path dirs remain"
      (t/is (not (fs/exists? target)))
      (t/is (fs/exists? root))
      (t/is (not (fs/exists? (str (fs/path root "example.com"))))))))

(t/deftest ^:slow test-monorepo-subdirectories-share-one-checkout
  (let [base (tmp-dir)
        {:keys [bare work]} (make-origin base)
        root (str (fs/path base "git"))]
    (fs/create-dirs (str (fs/path work "packages" "a")))
    (fs/create-dirs (str (fs/path work "packages" "b")))
    (spit (str (fs/path work "packages" "a" "ext.clj")) "(ns pkg-a)\n")
    (spit (str (fs/path work "packages" "b" "ext.clj")) "(ns pkg-b)\n")
    (git! work "add" ".")
    (git! work "commit" "-m" "packages")
    (git! work "push")
    (let [a (assoc (local-source bare) :dir "packages/a")
          b (assoc (local-source bare) :dir "packages/b")]
      (t/testing "subdirectory packages share the repository checkout"
        (t/is (= (git/install-path root a) (git/install-path root b)))
        (git/install! a root)
        (t/is (git/installed? a root))
        (t/is (= "(ns pkg-a)\n" (slurp (str (fs/path (git/package-dir a root) "ext.clj")))))
        (git/install! b root)
        (t/is (= "(ns pkg-b)\n" (slurp (str (fs/path (git/package-dir b root) "ext.clj"))))))
      (t/testing "a missing subdirectory is reported with the repository's directories"
        (let [msg (git/package-dir-error (assoc a :dir "packages/nope") root)]
          (t/is (str/includes? msg "No directory \"packages/nope\""))
          (t/is (str/includes? msg "packages"))))
      (t/testing "removing the shared checkout removes both package dirs"
        (git/remove! a root)
        (t/is (not (fs/exists? (git/package-dir a root))))
        (t/is (not (fs/exists? (git/package-dir b root))))))))

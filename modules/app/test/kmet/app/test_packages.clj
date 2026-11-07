(ns kmet.app.test-packages
  "Package manager tests (pi: core/package-manager.ts — the local and git
   source subset): source parsing, path identity, settings persistence,
   filter patterns, per-package resolution and the package resource
   loaders."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.packages :as pkgs]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.app.config :as cfg]
            [kmet.libs.theme :as theme]
            [kmet.test-utils :as tu :refer [slash spit-plugin]]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-"))

(defn- make-package
  "A conventional kmet package directory: extensions/, skills/ (a SKILL.md
   root plus a flat .md), prompts/, themes/."
  [root]
  (fs/create-dirs (str root "/extensions"))
  (fs/create-dirs (str root "/skills/root"))
  (fs/create-dirs (str root "/prompts"))
  (fs/create-dirs (str root "/themes"))
  (spit-plugin (str root "/extensions/one.clj") "(ns pkg-one)\n")
  (spit-plugin (str root "/extensions/two.clj") "(ns pkg-two)\n")
  (spit-plugin (str root "/extensions/three.clj") "(ns pkg-three)\n")
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  (spit (str root "/skills/flat.md")
        "---\nname: flat-skill\ndescription: flat skill\n---\nbody\n")
  (spit (str root "/prompts/x.md") "# x\n")
  (spit (str root "/prompts/y.md") "# y\n")
  (spit (str root "/themes/t.edn")
        (pr-str {:name "package-theme"
                 :colors (into {} (map (fn [k] [(name k) "#000000"]))
                               (concat theme/FG-TOKENS theme/BG-TOKENS))}))
  root)

;; ─── Source parsing ────────────────────────────────────────────────────────

(t/deftest test-source-parsing
  (t/testing "local sources"
    (t/is (pkgs/local-source? "./rel"))
    (t/is (pkgs/local-source? "/abs"))
    (t/is (pkgs/local-source? "~/home"))
    (t/is (pkgs/local-source? "bare-name"))
    (t/is (= :local (:kind (pkgs/parse-source "  ./x  "))))
    (t/is (= "./x" (:path (pkgs/parse-source "  ./x  ")))))
  (t/testing "remote sources are non-local; git ones parse as :git"
    (t/is (not (pkgs/local-source? "npm:@foo/bar")))
    (t/is (not (pkgs/local-source? "git:github.com/user/repo")))
    (t/is (not (pkgs/local-source? "git://host/repo")))
    (t/is (not (pkgs/local-source? "https://host/repo")))
    (t/is (not (pkgs/local-source? "ssh://git@host/repo")))
    (t/testing "git repositories"
      (t/is (= :git (:kind (pkgs/parse-source "git:github.com/user/repo"))))
      (t/is (= :git (:kind (pkgs/parse-source "https://github.com/user/repo"))))
      (t/is (= "v1" (get-in (pkgs/parse-source "git:github.com/user/repo@v1") [:git :ref]))))
    (t/testing "sources kmet cannot install stay :remote"
      (t/is (= :remote (:kind (pkgs/parse-source "npm:foo@1.0.0"))))
      (t/is (= :remote (:kind (pkgs/parse-source "github:user/repo"))))
      (t/is (= :remote (:kind (pkgs/parse-source "git://host/x")))))))

(defn- with-stubbed-cwd
  "Run F with the agent dir and cwd pointed at empty temp dirs, so the
   real user/project auto roots stay out of pure package-resolution
   assertions (pi tests build isolated agent/cwd fixtures the same way)."
  [f]
  (let [sandbox (tmp-dir)
        empty-cwd (tmp-dir)]
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str (fs/path empty-cwd ".kmet")))
                  fs/cwd (fn [] empty-cwd)]
      (f))))

;; ─── Resolution over a package directory ───────────────────────────────────

(t/deftest test-resolve-conventional-package
  (let [dir (make-package (tmp-dir))
        res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [dir]} nil))]
    (t/is (= #{:extensions :skills :prompts :themes} (set (keys res))))
    (t/is (= 3 (count (:extensions res))))
    (t/is (= 2 (count (:skills res))))
    (t/is (= 2 (count (:prompts res))))
    (t/is (= 1 (count (:themes res))))
    (t/is (every? :enabled (mapcat res (keys res))))
    (t/is (= (slash dir) (slash (get-in (first (:extensions res)) [:metadata :base-dir]))))
    (t/is (= (slash dir) (slash (get-in (first (:skills res)) [:metadata :base-dir]))))))

(t/deftest test-resolve-single-file-package
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [f]} nil))]
      (t/is (= [(slash f)] (slash (mapv :path (:extensions res)))))
      (t/is (empty? (:skills res)))
      (t/is (= (slash dir) (slash (get-in (first (:extensions res)) [:metadata :base-dir])))))))

(t/deftest test-resolve-extension-dir-package
  (let [dir (tmp-dir)]
    (spit (str dir "/extension.edn") "{:name \"pkg-ext\" :plugin pkg.ext/plugin}\n")
    (spit-plugin (str dir "/pkg_ext.clj") "(ns pkg.ext)\n")
    (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [dir]} nil))]
      (t/is (= [(slash dir)] (slash (mapv :path (:extensions res)))))
      (t/is (empty? (:skills res))))))

(t/deftest test-resolve-bare-dir-is-extension-container
  (let [dir (tmp-dir)]
    (spit-plugin (str dir "/a.clj") "(ns bare-a)\n")
    (spit-plugin (str dir "/b.clj") "(ns bare-b)\n")
    (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [dir]} nil))]
      (t/is (= (slash #{(str dir "/a.clj") (str dir "/b.clj")})
               (slash (set (map :path (:extensions res)))))))))

(t/deftest test-missing-package-skipped
  (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [(str (tmp-dir) "/nope")]} nil))]
    (t/is (empty? (:extensions res)))))

(t/deftest test-remote-package-warns-and-skips
  (let [out (java.io.StringWriter.)]
    (binding [*err* out]
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages ["npm:@foo/bar"]} nil))]
        (t/is (empty? (:extensions res)))
        (t/is (str/includes? (str out) "remote source"))))))

(t/deftest test-git-package-not-installed-warns
  (let [out (java.io.StringWriter.)]
    (binding [*err* out]
      (let [res (with-stubbed-cwd
                  #(pkgs/resolve-package-items {:packages ["git:github.com/user/repo"]} nil))]
        (t/is (empty? (:extensions res)))
        (t/is (str/includes? (str out) "is not installed"))
        (t/is (str/includes? (str out) "kmet install git:github.com/user/repo"))))))

(t/deftest test-resolve-installed-git-package
  (with-stubbed-cwd
    (fn []
      (let [checkout (str (fs/path (cfg/get-agent-dir) "git" "github.com" "user" "repo"))]
        (fs/create-dirs (str checkout "/extensions"))
        (spit-plugin (str checkout "/extensions/x.clj") "(ns pkg-x)\n")
        (let [res (pkgs/resolve-package-items {:packages ["git:github.com/user/repo"]} nil)]
          (t/is (= [(slash (str checkout "/extensions/x.clj"))]
                   (slash (mapv :path (:extensions res)))))
          (t/is (= "git:github.com/user/repo"
                   (get-in (first (:extensions res)) [:metadata :source]))))))))

;; ─── Filters (object entries) ──────────────────────────────────────────────

(t/deftest test-filter-patterns
  (let [dir (make-package (tmp-dir))]
    (t/testing "glob include + exclude"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :extensions ["extensions/*.clj" "!extensions/two.clj"]}]} nil))]
        (t/is (= #{"one.clj" "three.clj"}
                 (set (map (comp fs/file-name :path)
                           (filter :enabled (:extensions res))))))))
    (t/testing "empty array disables that type (items stay, unchecked)"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :skills []}]} nil))]
        (t/is (= 2 (count (:skills res))))
        (t/is (every? (complement :enabled) (:skills res)))
        (t/is (= 3 (count (:extensions res))))
        (t/is (every? :enabled (:extensions res)))
        (t/is (= 2 (count (:prompts res))))))
    (t/testing "-path force-excludes exactly"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :extensions ["-extensions/two.clj"]}]} nil))]
        (t/is (= 3 (count (:extensions res))))
        (t/is (= #{"one.clj" "three.clj"}
                 (set (map (comp fs/file-name :path)
                           (filter :enabled (:extensions res))))))))
    (t/testing "bare name include matches any file of that name"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :extensions ["two.clj"]}]} nil))]
        (t/is (= 1 (count (filter :enabled (:extensions res)))))))
    (t/testing "skill patterns may name the skill root"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :skills ["skills/root"]}]} nil))]
        (t/is (= 1 (count (filter :enabled (:skills res)))))))
    (t/testing "prompt include"
      (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :prompts ["prompts/x.md"]}]} nil))]
        (t/is (= 1 (count (filter :enabled (:prompts res)))))))))

(t/deftest test-filter-on-single-artifact-ignored
  ;; files and extension.edn dirs are single extensions — filters don't
  ;; apply (pi: file sources bypass package filters)
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source f :extensions []}]} nil))]
      (t/is (= 1 (count (:extensions res)))))))

;; ─── Scope precedence and deltas ───────────────────────────────────────────

(t/deftest test-project-entry-wins
  (let [dir (make-package (tmp-dir))
        res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :extensions []}]}
                                                           {:packages [dir]}))]
    (t/is (= 3 (count (:extensions res))))
    (t/is (= :project (get-in (first (:extensions res)) [:metadata :scope])))))

(t/deftest test-autoload-false-delta-over-user
  (let [dir (make-package (tmp-dir))
        res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [{:source dir :extensions ["-extensions/two.clj"]}]}
                                                           {:packages [{:source dir :autoload false
                                                                        :extensions ["+extensions/two.clj"]}]}))]
      ;; user entry: one.clj + three.clj; delta re-enables two.clj
    (t/is (= #{"one.clj" "two.clj" "three.clj"}
             (set (map (comp fs/file-name :path) (:extensions res)))))
    (t/is (every? :enabled (:extensions res)))))

(t/deftest test-autoload-false-empty-is-no-op
  (let [dir (make-package (tmp-dir))
        res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [dir]}
                                                           {:packages [{:source dir :autoload false :extensions []}]}))]
    (t/is (= 3 (count (:extensions res))))))
(t/deftest test-canonical-dedupe-across-packages
  ;; the same file listed by two packages resolves once (first wins)
  (let [root (make-package (tmp-dir))
        res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [root]} {:packages [root]}))]
    (t/is (= 2 (count (:skills res))))))

(t/deftest test-resolve-preserves-package-order
  ;; >8 items of one type must keep the resolution order (package order,
  ;; discovery order within each) — a state map would reorder them
  (let [p1 (str (fs/path (tmp-dir) "p1"))
        p2 (str (fs/path (tmp-dir) "p2"))]
    (doseq [root [p1 p2]]
      (fs/create-dirs (str root "/extensions"))
      (doseq [i (range 10)]
        (spit-plugin (str root "/extensions/e" i ".clj") "(ns x)\n")))
    (let [res (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [p1 p2]} nil))]
      (t/is (= (into (mapv #(str "e" % ".clj") (range 10))
                     (mapv #(str "e" % ".clj") (range 10)))
               (mapv (comp fs/file-name :path) (:extensions res)))))))

;; ─── Settings persistence (add / remove / list) ───────────────────────────

(defn- with-isolated-settings
  "Run F with the global settings file, agent dir, project settings file
   and project dir pointed at temp dirs (pi user/project scope). The cwd
   is stubbed to an empty temp dir so the real .kmet auto roots stay out
   of the resolution."
  [f]
  (let [global-dir (tmp-dir)
        project-dir (str (fs/path (tmp-dir) ".kmet"))
        empty-cwd (tmp-dir)]
    (with-redefs [cfg/global-settings-path (fn [] (str (fs/path global-dir "settings.edn")))
                  cfg/get-agent-dir (fn [] global-dir)
                  cfg/project-settings-path (fn [] (str (fs/path project-dir "settings.edn")))
                  cfg/project-dir (fn [] project-dir)
                  fs/cwd (fn [] empty-cwd)]
      (f {:global-dir global-dir :project-dir project-dir}))))

(t/deftest test-add-and-remove-user-package
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "my-pkg"))
            _ (fs/create-dirs pkg-dir)
            pkg-file (str pkg-dir "/ext.clj")]
        (spit pkg-file "(ns x)\n")
        (t/testing "add stores the path relative to the agent dir"
          (t/is (true? (pkgs/add-package-to-settings! pkg-file)))
          (let [settings (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))]
            (t/is (= ["my-pkg/ext.clj"] (:packages settings)))))
        (t/testing "re-adding the same package reports no change"
          (t/is (false? (pkgs/add-package-to-settings! pkg-file))))
        (t/testing "adding a sibling spelling matches and reports no change"
          (t/is (false? (pkgs/add-package-to-settings! (str pkg-dir "/sub/../ext.clj")))))
        (t/testing "list shows the configured package with its installed path"
          (let [listed (pkgs/list-configured-packages)]
            (t/is (= 1 (count listed)))
            (t/is (= "my-pkg/ext.clj" (:source (first listed))))
            (t/is (= :user (:scope (first listed))))
            (t/is (= (slash pkg-file) (slash (:installed-path (first listed)))))))
        (t/testing "remove deletes the entry"
          (t/is (true? (pkgs/remove-package-from-settings! pkg-file)))
          (t/is (empty? (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn")))))))
          (t/is (false? (pkgs/remove-package-from-settings! pkg-file))))))))

(t/deftest test-add-project-package
  (with-isolated-settings
    (fn [{:keys [project-dir]}]
      (let [pkg-dir (str (fs/normalize (fs/path project-dir ".." "pkgs")))
            _ (fs/create-dirs pkg-dir)
            pkg-file (str pkg-dir "/ext.clj")]
        (spit pkg-file "(ns x)\n")
        (t/is (true? (pkgs/add-package-to-settings! pkg-file {:local true})))
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= ["../pkgs/ext.clj"] (:packages settings))))
        (let [listed (pkgs/list-configured-packages)]
          (t/is (= 1 (count listed)))
          (t/is (= :project (:scope (first listed)))))))))

(t/deftest test-git-package-settings-identity
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/testing "add stores the git source verbatim"
        (t/is (true? (pkgs/add-package-to-settings! "git:github.com/user/repo")))
        (t/is (= ["git:github.com/user/repo"]
                 (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))))))
      (t/testing "another URL form or ref updates the same entry"
        (t/is (true? (pkgs/add-package-to-settings! "git:https://github.com/user/repo@v2")))
        (t/is (= ["git:https://github.com/user/repo@v2"]
                 (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))))))
      (t/testing "installed path is the managed checkout"
        (let [checkout (str (fs/path global-dir "git" "github.com" "user" "repo"))]
          (fs/create-dirs checkout)
          (t/is (= (slash checkout)
                   (slash (pkgs/get-installed-path "git:github.com/user/repo" :user))))))
      (t/testing "remove matches by identity"
        (t/is (true? (pkgs/remove-package-from-settings! "git:git@github.com:user/repo")))
        (t/is (empty? (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn")))))))))))

(t/deftest test-git-subdirectory-packages
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/testing "two subdirectories of one repository are distinct settings entries"
        (t/is (true? (pkgs/add-package-to-settings! "git:github.com/user/repo//packages/a")))
        (t/is (true? (pkgs/add-package-to-settings! "git:github.com/user/repo//packages/b")))
        (t/is (= ["git:github.com/user/repo//packages/a"
                  "git:github.com/user/repo//packages/b"]
                 (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))))))
      (t/testing "the installed path is the subdirectory inside the shared checkout"
        (let [sub (str (fs/path global-dir "git" "github.com" "user" "repo" "packages" "a"))]
          (fs/create-dirs sub)
          (t/is (= (slash sub)
                   (slash (pkgs/get-installed-path "git:github.com/user/repo//packages/a" :user))))
          (t/is (nil? (pkgs/get-installed-path "git:github.com/user/repo//packages/b" :user)))))
      (t/testing "resolution uses the subdirectory as the package root"
        (let [a (str (fs/path global-dir "git" "github.com" "user" "repo" "packages" "a"))]
          (fs/create-dirs (str a "/extensions"))
          (spit-plugin (str a "/extensions/x.clj") "(ns sub-a)\n")
          (let [res (pkgs/resolve-package-items
                     {:packages ["git:github.com/user/repo//packages/a"
                                 "git:github.com/user/repo//packages/b"]}
                     nil)]
            (t/is (= [(slash (str a "/extensions/x.clj"))]
                     (slash (mapv :path (:extensions res)))))
            (t/is (= "git:github.com/user/repo//packages/a"
                     (get-in (first (:extensions res)) [:metadata :source])))))))))

(t/deftest test-git-subdir-file-is-a-single-extension
  (with-stubbed-cwd
    (fn []
      (let [checkout (str (fs/path (cfg/get-agent-dir) "git" "github.com" "user" "repo"))
            file (str checkout "/tools/ext.clj")]
        (fs/create-dirs (str checkout "/tools"))
        (spit file "(ns tool-ext)\n")
        (let [res (pkgs/resolve-package-items
                   {:packages ["git:github.com/user/repo//tools/ext.clj"]} nil)]
          (t/is (= [(slash file)] (slash (mapv :path (:extensions res)))))
          (t/is (true? (get-in (first (:extensions res)) [:metadata :single-extension]))))))))

(t/deftest test-git-subdirectory-missing-dir-warns
  (with-stubbed-cwd
    (fn []
      (let [checkout (str (fs/path (cfg/get-agent-dir) "git" "github.com" "user" "repo"))
            out (java.io.StringWriter.)]
        (fs/create-dirs checkout)
        (binding [*err* out]
          (let [res (pkgs/resolve-package-items
                     {:packages ["git:github.com/user/repo//packages/a"]} nil)]
            (t/is (empty? (:extensions res)))
            (t/is (str/includes? (str out) "has no \"packages/a\" in its checkout"))))))))

(t/deftest test-list-empty
  (with-isolated-settings
    (fn [_]
      (t/is (empty? (pkgs/list-configured-packages))))))

(t/deftest test-remove-missing-package
  (with-isolated-settings
    (fn [_]
      (t/is (false? (pkgs/remove-package-from-settings! "/does/not/exist"))))))

(t/deftest test-remove-by-sibling-spelling
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "pkgs"))
            _ (fs/create-dirs pkg-dir)]
        (pkgs/add-package-to-settings! pkg-dir)
        (t/is (true? (pkgs/remove-package-from-settings!
                      (str pkg-dir "/../pkgs"))))))))

(t/deftest test-object-entry-list-and-remove
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "pkgs"))
            _ (fs/create-dirs pkg-dir)]
        (cfg/save-setting! [:packages] [{:source (pkgs/normalize-source-for-settings pkg-dir :user)
                                         :extensions []}])
        (let [listed (pkgs/list-configured-packages)]
          (t/is (= 1 (count listed)))
          (t/is (true? (:filtered (first listed)))))
        (t/is (true? (pkgs/remove-package-from-settings! pkg-dir)))))))

(t/deftest test-normalize-relative-storage
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/is (= "sub/x.clj"
               (pkgs/normalize-source-for-settings (str (fs/path global-dir "sub" "x.clj")) :user))))))

(t/deftest test-normalize-source-at-base-dir
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (t/testing "a source that is the scope base dir stores as \".\" (not blank)"
        (t/is (= "." (pkgs/normalize-source-for-settings global-dir :user)))
        (t/is (= "." (pkgs/normalize-source-for-settings project-dir :project)))))))

;; ─── The package resource loaders ─────────────────────────────────────────

(defn- with-empty-registries
  [f]
  (skills/clear-skills!)
  (prompts/clear-prompt-templates!)
  (try (f)
       (finally
         (skills/clear-skills!)
         (prompts/clear-prompt-templates!))))

(defn- with-settings
  "Run F with user settings pointing at PACKAGES (absolute temp paths, so
   no scope-base resolution is involved). Agent dir and cwd point at empty
   temp dirs so the unified resolution's auto/top-level layers contribute
   nothing — only the packages under test load."
  [f & packages]
  (let [sandbox (tmp-dir)
        empty-cwd (tmp-dir)]
    (with-redefs [pkgs/user-settings-map (fn [] {:packages (vec packages)})
                  pkgs/project-settings-map (fn [] {})
                  cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str (fs/path empty-cwd ".kmet")))
                  fs/cwd (fn [] empty-cwd)]
      (f))))

(t/deftest test-load-skills-and-prompts
  (with-empty-registries
    (fn []
      (let [dir (make-package (tmp-dir))]
        (with-settings (fn []
                         (pkgs/load-skills!)
                         (pkgs/load-prompts!)
                         (t/is (= #{"skill-a" "flat-skill"}
                                  (set (map :name (skills/get-skills)))))
                         (t/is (= #{"x" "y"}
                                  (set (map :name (prompts/get-prompt-templates))))))
          dir)))))

(t/deftest test-package-loaders-respect-filters
  (with-empty-registries
    (fn []
      (let [dir (make-package (tmp-dir))]
        (with-settings (fn []
                         (pkgs/load-skills!)
                         (t/is (= #{"skill-a"}
                                  (set (map :name (skills/get-skills))))))
          {:source dir :skills ["-skills/flat.md"]})))))

(t/deftest test-package-themes-load
  (let [dir (make-package (tmp-dir))]
    (try
      (with-settings (fn []
                       (theme/load-theme-paths! (pkgs/resolve-theme-paths))
                       (t/is (some? (theme/get-theme-by-name "package-theme"))))
        {:source dir})
      (finally
        (theme/unregister-theme! "package-theme")))))

;; ─── Pattern engine ────────────────────────────────────────────────────────

(t/deftest test-apply-patterns
  (let [base "/pkg"
        paths ["/pkg/extensions/a.clj" "/pkg/extensions/b.clj" "/pkg/extensions/c.clj"]
        rel (fn [p] (str/replace (slash p) (str base "/") ""))]
    (t/is (= #{"extensions/a.clj" "extensions/b.clj" "extensions/c.clj"}
             (set (map rel (pkgs/apply-patterns paths ["extensions/*.clj"] base)))))
    (t/testing "exclude glob"
      (t/is (= #{"extensions/a.clj" "extensions/c.clj"}
               (set (map rel (pkgs/apply-patterns paths
                                                  ["extensions/*.clj" "!extensions/b.clj"] base))))))
    (t/testing "force-exclude exact"
      (t/is (= #{"extensions/a.clj" "extensions/c.clj"}
               (set (map rel (pkgs/apply-patterns paths ["-extensions/b.clj"] base))))))
    (t/testing "force-include adds back over an exclude"
      (t/is (= #{"extensions/b.clj"}
               (set (map rel (pkgs/apply-patterns paths
                                                  ["!extensions/*" "+extensions/b.clj"] base))))))
    (t/testing "plain name pattern"
      (t/is (= #{"extensions/a.clj"}
               (set (map rel (pkgs/apply-patterns paths ["a.clj"] base))))))
    (t/testing "no includes means everything"
      (t/is (= 3 (count (pkgs/apply-patterns paths [] base)))))
    (t/testing "absolute-path glob"
      (t/is (= 1 (count (pkgs/apply-patterns paths [(str base "/extensions/b.clj")] base))))))
  (t/testing "double-star crosses directories"
    (let [paths ["/pkg/a/b/c.md" "/pkg/x.md"]]
      (t/is (= 2 (count (pkgs/apply-patterns paths ["**/*.md"] "/pkg"))))
      (t/is (= 1 (count (pkgs/apply-patterns paths ["a/**/*.md"] "/pkg"))))))
  (t/testing "trailing double-star matches any depth"
    (t/is (= 2 (count (pkgs/apply-patterns ["/pkg/skills/a.md" "/pkg/skills/x/y.md"]
                                           ["skills/**"] "/pkg"))))
    (t/is (= 2 (count (pkgs/apply-patterns ["/pkg/a/b/c.md" "/pkg/x.md"]
                                           ["**"] "/pkg")))))
  (t/testing "wildcards need an explicit dot for hidden segments"
    (t/is (= 0 (count (pkgs/apply-patterns ["/pkg/.hidden.md"] ["*.md"] "/pkg"))))
    (t/is (= 1 (count (pkgs/apply-patterns ["/pkg/.hidden.md"] [".*.md"] "/pkg"))))))

(t/deftest test-glob-classes
  ;; minimatch character-class parity: malformed classes never throw, `[.]`
  ;; writes the dot itself, a class containing `/` is literal text
  (let [m (fn [pat paths] (vec (sort (pkgs/apply-patterns paths [pat] "/pkg"))))]
    (t/testing "[.] is a literal dot, so it matches a hidden segment"
      (t/is (= ["/pkg/.hidden"] (m "[.]hidden" ["/pkg/.hidden" "/pkg/x"]))))
    (t/testing "a class that can match other characters keeps the hidden guard"
      (t/is (= ["/pkg/ax"] (m "[a.]x" ["/pkg/.x" "/pkg/ax"])))
      (t/is (= ["/pkg/bx"] (m "[!a]x" ["/pkg/.x" "/pkg/bx" "/pkg/ax"]))))
    (t/testing "malformed classes are literals or never-match (no regex error)"
      (t/is (= ["/pkg/[]x"] (m "[]x" ["/pkg/[]x" "/pkg/x"])))
      (t/is (= ["/pkg/[!]"] (m "[!]" ["/pkg/[!]" "/pkg/x"])))
      (t/is (= ["/pkg/[a"] (m "[a" ["/pkg/[a" "/pkg/a"])))
      (t/is (empty? (m "[z-a]" ["/pkg/z" "/pkg/a"]))))
    (t/testing "a reversed range inside a wider class is dropped"
      (t/is (= ["/pkg/x"] (m "[z-ax]" ["/pkg/x" "/pkg/z" "/pkg/a"]))))
    (t/testing "a class containing / is literal (minimatch splits on /)"
      (t/is (= ["/pkg/[a/b].clj"] (m "[a/b].clj" ["/pkg/[a/b].clj" "/pkg/ab.clj"]))))
    (t/testing "ranges"
      (t/is (= ["/pkg/a" "/pkg/b" "/pkg/z"]
               (m "[a-cz]" ["/pkg/a" "/pkg/b" "/pkg/z" "/pkg/d"]))))))

(t/deftest test-delta-entry-without-user-entry-resolves-in-project-scope
  ;; pi findAutoloadDeltaBase: with no user entry of the same identity the
  ;; delta entry resolves against its own scope — it used to fall back to
  ;; the user base dir
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (let [user-pkg (str (fs/path global-dir "pkg"))
            proj-pkg (str (fs/path project-dir "pkg"))]
        (doseq [[root file] [[user-pkg "user-ext.clj"] [proj-pkg "proj-ext.clj"]]]
          (fs/create-dirs (str root "/extensions"))
          (spit (str root "/extensions/" file) "(ns x)\n"))
        (let [res (pkgs/resolve-package-items
                   nil
                   {:packages [{:source "./pkg" :autoload false
                                :extensions ["+extensions/proj-ext.clj"]}]})]
          (t/is (= ["proj-ext.clj"] (mapv (comp fs/file-name :path) (:extensions res))))
          (t/is (str/starts-with? (slash (:path (first (:extensions res)))) (slash proj-pkg)))
          (t/is (every? :enabled (:extensions res))))))))

(t/deftest test-delta-patterns
  (let [paths ["/pkg/extensions/a.clj" "/pkg/extensions/b.clj"]]
    (t/is (= {"/pkg/extensions/a.clj" true}
             (pkgs/apply-autoload-disabled-patterns paths ["+extensions/a.clj"] "/pkg")))
    (t/is (= {"/pkg/extensions/b.clj" false}
             (pkgs/apply-autoload-disabled-patterns paths ["-extensions/b.clj"] "/pkg")))))

;; ─── Skill file discovery helpers ─────────────────────────────────────────

(t/deftest test-discover-skill-files
  (let [dir (make-package (tmp-dir))]
    (t/is (= (slash #{(str dir "/skills/root/SKILL.md") (str dir "/skills/flat.md")})
             (slash (set (skills/discover-skill-files (str dir "/skills"))))))))

(t/deftest test-extension-items-are-composition-only
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [file (str (fs/path global-dir "ext.clj"))]
        (spit file "(ns x)\n")
        (t/testing "a file source resolves to one available extension"
          (let [item (first (:extensions (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [file]} nil))))]
            (t/is (true? (get-in item [:metadata :single-extension])))
            (t/is (true? (:enabled item))))
          (t/testing "an extension.edn directory too"
            (let [dir (tmp-dir)]
              (spit (str dir "/extension.edn") "{:name \"pkg-ext\" :plugin pkg.ext/plugin}\n")
              (let [item (first (:extensions (with-stubbed-cwd #(pkgs/resolve-package-items {:packages [dir]} nil))))]
                (t/is (true? (get-in item [:metadata :single-extension]))))))
          (t/testing "conventional package resources are not single-extension units"
            (let [pkg (make-package (tmp-dir))
                  item (first (:extensions
                               (with-stubbed-cwd
                                 #(pkgs/resolve-package-items {:packages [pkg]} nil))))]
              (t/is (not (get-in item [:metadata :single-extension]))))))))))

;; ─── Top-level settings entries (pi: resolveLocalEntries) ─────────────────

(t/deftest test-top-level-plain-file-and-dir
  ;; plain entries expand against the base dir — a file contributes itself,
  ;; a directory its discovered items — and every collected file is enabled.
  (let [sandbox (tmp-dir)
        ext-file (str sandbox "/solo.clj")
        extra (str sandbox "/extra")]
    (spit ext-file "(ns solo)\n")
    (fs/create-dirs extra)
    (spit-plugin (str extra "/x.clj") "(ns x)\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str (tmp-dir) "/.kmet"))
                  fs/cwd (fn [] (tmp-dir))]
      (let [res (pkgs/resolve-package-items {:extensions ["solo.clj" "extra"]} nil)]
        (t/is (= (slash #{ext-file (str extra "/x.clj")})
                 (slash (set (map :path (:extensions res))))))
        (t/is (every? :enabled (:extensions res)))
        (t/is (every? #(= :top-level (get-in % [:metadata :origin])) (:extensions res)))
        (t/is (every? #(= "local" (get-in % [:metadata :source])) (:extensions res)))
        (t/is (every? #(= sandbox (get-in % [:metadata :base-dir])) (:extensions res)))))))

(t/deftest test-top-level-signed-entries-ignored
  ;; top-level entries are discovery paths only. Glob and signed entries no
  ;; longer narrow or adjust the collected set: selection is :resources.
  (let [sandbox (tmp-dir)]
    (fs/create-dirs (str sandbox "/prompts"))
    (spit (str sandbox "/prompts/a.md") "# a\n")
    (spit (str sandbox "/prompts/b.md") "# b\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  fs/cwd (fn [] (tmp-dir))]
      (let [res (pkgs/resolve-package-items
                 {:prompts ["prompts" "prompts/*.md" "!prompts/b.md"
                            "-prompts/b.md" "+prompts/a.md"]}
                 nil)
            local (filterv #(= "local" (get-in % [:metadata :source])) (:prompts res))]
        (t/is (= (slash #{(str sandbox "/prompts/a.md") (str sandbox "/prompts/b.md")})
                 (slash (set (map :path local)))))
        (t/is (every? :enabled local))))))

(t/deftest test-top-level-project-scope
  (let [sandbox (tmp-dir)
        proj (tmp-dir)
        proj-ext (str proj "/.kmet/extensions")]
    (fs/create-dirs proj-ext)
    (spit-plugin (str proj-ext "/p.clj") "(ns p)\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str proj "/.kmet"))
                  fs/cwd (fn [] proj)]
      (let [res (pkgs/resolve-package-items nil {:extensions ["extensions"]})]
        (t/is (= [(slash (str proj-ext "/p.clj"))] (slash (mapv :path (:extensions res)))))
        (t/is (= :project (get-in (first (:extensions res)) [:metadata :scope])))))))

(t/deftest test-top-level-missing-path-skipped
  (with-redefs [cfg/get-agent-dir (fn [] (tmp-dir))
                cfg/project-dir (fn [] (str (tmp-dir) "/.kmet"))
                fs/cwd (fn [] (tmp-dir))]
    (let [res (pkgs/resolve-package-items {:extensions ["nope.clj"]} nil)]
      (t/is (empty? (:extensions res))))))

;; ─── Auto-dir scans (pi: addAutoDiscoveredResources) ──────────────────────

(t/deftest test-auto-dirs-scan
  (let [sandbox (tmp-dir)
        proj (tmp-dir)]
    (fs/create-dirs (str sandbox "/extensions"))
    (spit-plugin (str sandbox "/extensions/a.clj") "(ns a)\n")
    (spit-plugin (str sandbox "/extensions/b.clj") "(ns b)\n")
    (fs/create-dirs (str proj "/.kmet/extensions"))
    (spit-plugin (str proj "/.kmet/extensions/c.clj") "(ns c)\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str proj "/.kmet"))
                  fs/cwd (fn [] proj)]
      (let [res (pkgs/resolve-package-items {:extensions ["!extensions/b.clj"]} nil)
            by-name (into {} (map (fn [i] [(fs/file-name (:path i)) i]) (:extensions res)))]
        (t/is (= #{"a.clj" "b.clj" "c.clj"} (set (keys by-name))))
        (t/is (every? :enabled (vals by-name))
              "top-level signed entries no longer adjust the auto scan")
        (t/is (= :project (get-in (by-name "c.clj") [:metadata :scope]))
              "project auto wins order")
        (t/is (= :user (get-in (by-name "a.clj") [:metadata :scope])))
        (t/is (every? #(= "auto" (get-in % [:metadata :source])) (vals by-name)))))))

(t/deftest test-precedence-project-local-beats-user-auto
  ;; same canonical path via a project top-level entry and the user auto
  ;; dir: the project-local item wins (first-wins in pi precedence order)
  (let [sandbox (tmp-dir)
        proj (tmp-dir)
        shared (str proj "/shared.clj")]
    (fs/create-dirs (str sandbox "/extensions"))
    (spit shared "(ns shared)\n")
    (spit-plugin (str sandbox "/extensions/other.clj") "(ns other)\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str proj "/.kmet"))
                  fs/cwd (fn [] proj)]
      (let [res (pkgs/resolve-package-items nil {:extensions [(str proj "/shared.clj")]})]
        (t/is (some #(= (slash shared) (slash (:path %))) (:extensions res)))))))

(t/deftest test-user-view-excludes-project-scope
  ;; PROJECT-SCOPE? false is pi's global config view (untrusted settings
  ;; manager): project settings entries, project packages and project auto
  ;; dirs are all absent
  (let [sandbox (tmp-dir)
        proj (tmp-dir)]
    (fs/create-dirs (str sandbox "/prompts"))
    (spit (str sandbox "/prompts/user.md") "# u\n")
    (fs/create-dirs (str proj "/.kmet/prompts"))
    (spit (str proj "/.kmet/prompts/proj.md") "# p\n")
    (with-redefs [cfg/get-agent-dir (fn [] sandbox)
                  cfg/project-dir (fn [] (str proj "/.kmet"))
                  fs/cwd (fn [] proj)]
      (let [user-view (pkgs/resolve-package-items nil {:prompts ["prompts/proj.md"]} nil false)
            full-view (pkgs/resolve-package-items nil {:prompts ["prompts/proj.md"]})]
        (t/is (= [(slash (str sandbox "/prompts/user.md"))]
                 (slash (mapv :path (:prompts user-view)))))
        (t/is (= (slash #{(str sandbox "/prompts/user.md") (str proj "/.kmet/prompts/proj.md")})
                 (slash (set (map :path (:prompts full-view))))))))))

(t/deftest test-auto-dir-scope-not-path-prefix
  ;; regression: the agent dir can live inside the project scope root
  ;; (cwd = $HOME → project $HOME/.kmet contains agent $HOME/.kmet/agent).
  ;; Scope must come from exact root equality — a path-prefix test
  ;; mislabeled the agent auto roots as project and the global view lost
  ;; them.
  (let [home-root (tmp-dir)
        project-dir (str (fs/path home-root ".kmet"))
        agent-dir (str (fs/path project-dir "agent"))]
    (fs/create-dirs (str (fs/path agent-dir "extensions")))
    (spit-plugin (str (fs/path agent-dir "extensions/a.clj")) "(ns a)\n")
    (with-redefs [cfg/get-agent-dir (fn [] agent-dir)
                  cfg/project-dir (fn [] project-dir)
                  fs/cwd (fn [] home-root)]
      (let [full (:extensions (pkgs/resolve-package-items nil nil))
            item (first full)]
        (t/is (= 1 (count full)))
        (t/is (= :user (get-in item [:metadata :scope])))
        (t/is (= :top-level (get-in item [:metadata :origin])))
        (t/is (= "auto" (get-in item [:metadata :source]))))
      (t/is (= 1 (count (:extensions (pkgs/resolve-package-items nil nil nil false))))
            "global view keeps the agent auto resources"))))

;; ─── Bundled extensions ─────────────────────────────

(t/deftest test-bundled-layer-ranks-after-packages
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str (fs/path global-dir "pkg"))]
        (fs/create-dirs (str pkg "/extensions"))
        (spit-plugin (str pkg "/extensions/p.clj") "(ns p)\n")
        (let [bundled (pkgs/bundled-items)
              resolved (pkgs/resolve-package-items {:packages [pkg]} nil nil true bundled)
              names (mapv #(or (get-in % [:metadata :display-name])
                               (fs/file-name (:path %)))
                          (:extensions resolved))
              idx-of (fn [n] (first (keep-indexed #(when (= n %2) %1) names)))]
          (t/is (< (idx-of "p.clj") (idx-of "clojure"))
                "package resources rank before bundled")
          (t/is (= 1 (count (filter #(= "clojure" (get-in % [:metadata :display-name]))
                                    (:extensions resolved))))
                "the bundled item is available to the composition")
          (t/is (every? :enabled (filter pkgs/bundled-item? (:extensions resolved)))
                "bundled items are always available"))))))


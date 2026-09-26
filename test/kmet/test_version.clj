(ns kmet.test-version
  "kmet.version — jolt's checkout-version rule (jolt tools/version.sh):
   the one definition behind artifact names and `kmet --version`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.version :as version]))

(deftest describe-normalization
  (testing "a release tag stands as git describe reports it, v kept"
    (is (= "v1.2.3" (@#'version/normalize-describe "v1.2.3")))
    (is (= "v0.1.0-1-g45ed657" (@#'version/normalize-describe "v0.1.0-1-g45ed657")))
    (is (= "v0.1.0-1-g45ed657-dirty"
           (@#'version/normalize-describe "v0.1.0-1-g45ed657-dirty"))))
  (testing "no release tag: the bare sha is prefixed, never left alone"
    ;; a sha such as 0a1b2c3 would read as version 0 to anything parsing
    ;; leading digits; dev-g0a1b2c3 has no number for a floor to grab
    (is (= "dev-g0a1b2c3" (@#'version/normalize-describe "0a1b2c3")))
    (is (= "dev-g0a1b2c3-dirty" (@#'version/normalize-describe "0a1b2c3-dirty"))))
  (testing "nothing to describe"
    (is (= "dev" (@#'version/normalize-describe nil)))))

(deftest checkout-version-asks-git-the-right-question
  (with-redefs [version/git-out
                (fn [dir & args]
                  (is (= "/some/repo" dir) "the dir is handed to git")
                  (is (= ["describe" "--tags" "--always" "--dirty" "--match" "v[0-9]*"]
                         (vec args))
                      "release tags only, dirty included, always answers")
                  "v1.2.3-4-gabc1234")]
    (is (= "v1.2.3-4-gabc1234" (version/checkout-version "/some/repo"))))
  (testing "git missing or failing reads dev"
    (with-redefs [version/git-out (fn [& _] nil)]
      (is (= "dev" (version/checkout-version))))))

(deftest artifact-version-strips-the-v
  (with-redefs [version/git-out (constantly "v1.2.3-4-gabc1234")]
    (is (= "1.2.3-4-gabc1234" (version/artifact-version))))
  (testing "a dev version has no v to strip"
    ;; git-out answers the raw describe result (a bare sha here); the dev-g
    ;; prefix is the rule's, not the stub's
    (with-redefs [version/git-out (constantly "0a1b2c3")]
      (is (= "dev-g0a1b2c3" (version/artifact-version))))
    (with-redefs [version/git-out (constantly nil)]
      (is (= "dev" (version/artifact-version))))))

(defn- git-available? []
  (try
    (zero? (:exit (p/shell {:out :discard :err :discard :continue true}
                           "git" "--version")))
    (catch Exception _ false)))

(defn- git!
  "Run git in DIR, returning trimmed stdout; throws on a non-zero exit."
  [dir & args]
  (let [{:keys [exit out err]}
        (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
               "git" "-c" "user.name=kmet-test" "-c" "user.email=kmet-test@example.invalid"
               "-c" "init.defaultBranch=main" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " err)
                      {:exit exit})))
    (str/trim out)))

(deftest ^:slow checkout-version-reads-a-real-repo
  ;; jolt's version-smoke.sh for the babashka/jolt hosts: the properties only
  ;; a real repo can prove — release tags only (vnightly at HEAD must not win),
  ;; -dirty on edits, dev-g<sha> without a reachable release tag.
  (when (git-available?)
    (let [dir (fs/create-dirs "target/test-version-repo")]
      (try
        (git! dir "init" "-q")
        (spit (str (fs/path dir "f")) "one\n")
        (git! dir "add" "f")
        (git! dir "commit" "-q" "-m" "one")
        (git! dir "tag" "v0.1.0")
        (testing "on the release tag"
          (is (= "v0.1.0" (version/checkout-version dir))))
        (spit (str (fs/path dir "f")) "two\n")
        (git! dir "commit" "-q" "-am" "two")
        (let [sha (git! dir "rev-parse" "--short" "HEAD")]
          (testing "past the tag: commits-since and the sha"
            (is (= (str "v0.1.0-1-g" sha) (version/checkout-version dir))))
          (testing "a rolling vnightly tag at HEAD is not the version"
            (git! dir "tag" "vnightly")
            (is (= (str "v0.1.0-1-g" sha) (version/checkout-version dir)))
            (git! dir "tag" "-d" "vnightly"))
          (testing "uncommitted edits say so"
            (spit (str (fs/path dir "f")) "three\n")
            (is (= (str "v0.1.0-1-g" sha "-dirty") (version/checkout-version dir)))
            (git! dir "checkout" "--" "f"))
          (testing "no release tag reachable: the sha is prefixed"
            (git! dir "tag" "-d" "v0.1.0")
            (is (= (str "dev-g" sha) (version/checkout-version dir))))
          (testing "artifact-version is the same rule, v stripped"
            (git! dir "tag" "v0.2.0")
            (is (= "0.2.0" (version/artifact-version dir)))))
        (finally
          (fs/delete-tree dir))))))

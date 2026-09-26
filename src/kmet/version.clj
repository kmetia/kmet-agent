(ns kmet.version
  "kmet's checkout version — jolt's rule (jolt tools/version.sh), so the base
   version kmet stamps on artifacts (`kmet-<ver>-<host><host-ver>-<platform>`)
   and reports from `kmet --version` reads like the compiler's:

     v0.8.0                on a release tag
     v0.8.0-56-g63374117   56 commits past it, at that sha (-dirty with edits)
     dev-g63374117         no release tag reachable (a shallow clone)
     dev                   not a git checkout

   Release tags only (`v` + digit): a rolling `vnightly` tag is the NEAREST
   tag from main, so a bare `git describe --tags` would answer it. The no-tag
   case is prefixed rather than left as the bare sha — a sha like 0a1b2c3
   would read as version 0 to anything parsing leading digits, while
   dev-g0a1b2c3 reads as a version with no number, which no floor applies to.

   One definition on purpose (jolt's version-smoke.sh enforces the same for
   its side): the packagers, `kmet --version`, and any future consumer call
   here — none re-derives the rule inline."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn- git-out
  "Output of `git ARGS` run in DIR (nil = the process cwd), trimmed; nil when
   git is missing or the command fails."
  [dir & args]
  (try
    (let [{:keys [out exit]} (apply p/shell (cond-> {:out :string :err :suppress}
                                              dir (assoc :dir (str dir)))
                                    "git" args)]
      (when (zero? exit) (not-empty (str/trim out))))
    (catch Exception _ nil)))

(defn- normalize-describe
  "What a `git describe --always --dirty` result means as a version: a
   `v<digit>` release tag stands as-is, anything else (a bare sha — no release
   tag was reachable) is prefixed `dev-g`, and no result at all is `dev`."
  [described]
  (cond
    (nil? described) "dev"
    (re-find #"^v[0-9]" described) described
    :else (str "dev-g" described)))

(defn checkout-version
  "The version of the git checkout at DIR (nil = the process's working
   directory), by jolt's rule — see the namespace docstring for the shapes."
  ([] (checkout-version nil))
  ([dir]
   (normalize-describe (git-out dir "describe" "--tags" "--always" "--dirty"
                                "--match" "v[0-9]*"))))

(defn artifact-version
  "checkout-version with the leading `v` stripped — the base version artifact
   names carry."
  ([] (artifact-version nil))
  ([dir] (str/replace (checkout-version dir) #"^v" "")))

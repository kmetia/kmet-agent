(ns kmet.tasks.test-lint
  "kmet.tasks.lint — the two-view lint engine. The reader-conditional rewrite, the
   projections and the finding merge are pure units; the end-to-end probe
   (^:slow) spawns clj-kondo and covers the whole pipeline: both projections,
   both passes, dedupe."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.lint :as lint]))

(def ^:private views @#'lint/views)
(def ^:private bb (first views))
(def ^:private jolt (second views))
(def ^:private rewrite-features #'lint/rewrite-features)
(def ^:private feature-spans #'lint/feature-spans)
(def ^:private mirror-path #'lint/mirror-path)
(def ^:private mirror-source #'lint/mirror-source)
(def ^:private projection? #'lint/projection?)
(def ^:private file-info #'lint/file-info)
(def ^:private pass-targets #'lint/pass-targets)
(def ^:private dedupe-findings #'lint/dedupe-findings)
(def ^:private summarize #'lint/summarize)
(def ^:private lint-paths! #'lint/lint-paths!)

(deftest test-feature-spans
  (testing "only reader-conditional feature keys count"
    (is (= [[3 8]] (@feature-spans "#?(:jolt A)" ":jolt")))
    (is (= [[4 9]] (@feature-spans "#?@(:jolt A :bb B)" ":jolt")))
    (is (= [[4 7]] (@feature-spans "#?@(:bb A :jolt B)" ":bb"))))
  (testing "data, strings, comments and namespaced keywords are not features"
    (is (empty? (@feature-spans "#{:jolt :clj}" ":jolt")))
    (is (empty? (@feature-spans "{:jolt [1]}" ":jolt")))
    (is (empty? (@feature-spans "(def x :jolt)" ":jolt")))
    (is (empty? (@feature-spans ":jolt/provides :jolt.kmet/x" ":jolt")))
    (is (empty? (@feature-spans "\"#?(:jolt x)\"" ":jolt")))
    (is (empty? (@feature-spans "; #?(:jolt x)\n" ":jolt"))))
  (testing "a branch may itself hold anything, including a conditional"
    (is (= [[3 8] [18 23]] (@feature-spans "#?(:jolt #?(:bb A :jolt B))" ":jolt")))
    (is (= [[3 6]] (@feature-spans "#?(:bb [1 2])" ":bb")))
    (is (= [[3 6]] (@feature-spans "#?(:bb (f \"a)b\"))" ":bb")))
    (is (= [[3 6]] (@feature-spans "#?(:bb ^:private (def x 1) :jolt y)" ":bb")))))

(deftest test-rewrite-features
  (testing "a feature key is re-spelled, its branch selection unchanged"
    (is (= "(ns x (:require #?@(:clj  [[a]])))\n"
           (@rewrite-features "(ns x (:require #?@(:jolt [[a]])))\n" ":jolt" ":clj")))
    (is (= "#?(:clj  A :clj B)" (@rewrite-features "#?(:jolt A :clj B)" ":jolt" ":clj")))
    (is (= "#?(:clj A :jolt B)" (@rewrite-features "#?(:bb A :jolt B)" ":bb" ":clj")))
    (testing "the other host's feature is rewritten too when it is a feature —
              clj-kondo picks the first match, which is the branch this host reads"
      (is (= "#?(:bb A :clj  B)" (@rewrite-features "#?(:bb A :jolt B)" ":jolt" ":clj")))))
  (testing "a :jolt rewrite is column-exact: the token loses one char, the pad returns it"
    (doseq [s ["#?(:jolt A)" "#?(:jolt\n   A)" "#?(:jolt nil :clj [[a]])" "#?@(:jolt [1 2])"]]
      (is (= (count s) (count (@rewrite-features s ":jolt" ":clj"))) s)))
  (testing "the :default fallback (kmet's `:jolt` + other-host pattern) survives"
    (is (= "#?(:clj  (= :stream (:as opts)) :default false)"
           (@rewrite-features "#?(:jolt (= :stream (:as opts)) :default false)" ":jolt" ":clj")))
    (is (= "#?(:bb A :default B)" (@rewrite-features "#?(:bb A :default B)" ":jolt" ":clj"))))
  (testing "a :bb rewrite grows the line by one — there is no 3-char feature
            clj-kondo knows — and the tokens stay separate"
    (is (= "#?(:clj A)" (@rewrite-features "#?(:bb A)" ":bb" ":clj"))))
  (testing "nothing to rewrite leaves the text alone"
    (is (= "#{:bb :clj :cljs :cljr :default}"
           (@rewrite-features "#{:bb :clj :cljs :cljr :default}" ":bb" ":clj")))
    (is (= "(def s #{:bb :clj})\n" (@rewrite-features "(def s #{:bb :clj})\n" ":bb" ":clj")))))

(deftest test-projection-predicates
  (let [dir (fs/create-temp-dir {:dir "." :prefix "lint-probe-"})
        path (fn [name content] (let [f (str (fs/file dir name))] (spit f content) f))]
    (try
      (let [joltish (path "cond.cljc" "(ns c)\n#?(:jolt (defn f [] 1) :bb (defn f [] 2))\n")
            plain (path "plain.clj" "(ns p)\n(defn f [] 1)\n")]
        (testing "a file is projected only by the view whose feature it carries"
          (is (true? (boolean (@projection? jolt (slurp joltish)))))
          (is (true? (boolean (@projection? bb (slurp joltish)))))
          (is (false? (boolean (@projection? jolt (slurp plain)))))
          (is (false? (boolean (@projection? bb (slurp plain))))))
        (testing "the jolt pass reads a :bb-only file raw — clj-kondo skips it
                  exactly as jolt does — and projects a :jolt one"
          (let [bb-only (path "bb_only.cljc" "(ns b)\n#?(:bb (defn f [] 1))\n")]
            (is (= [(str (@mirror-path bb bb-only))]
                   (@pass-targets bb [(@file-info bb-only)])))
            (is (= [bb-only] (@pass-targets jolt [(@file-info bb-only)])))
            (is (= [(str (@mirror-path jolt joltish))]
                   (@pass-targets jolt [(@file-info joltish)])))))
        (testing "a plain file is read in place by the whole-tree view, and is
                  not the conditional view's business"
          (is (= [plain] (@pass-targets bb [(@file-info plain)])))
          (is (= [] (@pass-targets jolt [(@file-info plain)]))))
        (testing "jolt/ is jolt-only code: the jolt pass reads it, token or not
                  (the babashka view passes it too — its config excludes jolt/)"
          (let [info (@file-info "jolt/src/jolt/kmet/providers.clj")]
            (is (empty? (:views info)))
            (is (= ["jolt/src/jolt/kmet/providers.clj"] (@pass-targets jolt [info])))))
        (testing ".jolt is Jolt-only by extension: the jolt pass reads it, token
                  or not, and the babashka pass never sees it"
          (let [backend (path "backend.jolt" "(ns kmet.tui.backend)\n(defn f [] 1)\n")
                info (@file-info backend)]
            (is (empty? (:views info)))
            (is (= [backend] (@pass-targets jolt [info])))
            (is (= [] (@pass-targets bb [info]))))))
      (finally (fs/delete-tree dir)))))

(deftest test-mirror-paths
  (is (= (str (fs/file "target/bb-lint" "src" "kmet" "tasks" "build.cljc"))
         (@mirror-path bb "src/kmet/tasks/build.cljc")))
  (is (= (str (fs/file "target/jolt-lint" "jolt" "src" "jolt" "kmet" "providers.clj"))
         (@mirror-path jolt "jolt/src/jolt/kmet/providers.clj")))
  (testing "a mirror copy maps back to its source"
    (is (= "src/kmet/libs/http.cljc"
           (@mirror-source jolt (@mirror-path jolt "src/kmet/libs/http.cljc")))))
  (testing "no mirror for a file outside the project — projecting one would
            write back through a `..` segment"
    (is (nil? (@mirror-source jolt "target/jolt-lint/../../x.cljc")))
    (is (thrown? Exception (@mirror-path bb "../outside.cljc")))))

(defn- finding
  [message & {:as m}]
  (merge {:filename "src/a.cljc" :row 1 :col 2 :level :error :type "unresolved-symbol"
          :message message}
         m))

(deftest test-dedupe-findings
  (testing "the same finding from both views collapses to one"
    (is (= 1 (count (@dedupe-findings [(finding "x") (finding "x")])))))
  (testing "position, level and message all take part in identity"
    (is (= 3 (count (@dedupe-findings [(finding "x")
                                       (finding "x" :row 2)
                                       (finding "x" :level :warning)]))))
    (is (= 2 (count (@dedupe-findings [(finding "x" :filename "src/b.cljc")
                                       (finding "x")]))))))

(deftest test-summarize
  (is (= {:errors 2 :warnings 1 :info 0}
         (@summarize [(finding "a")
                      (finding "b")
                      (finding "c" :level :warning)])))
  (is (= {:errors 0 :warnings 0 :info 0} (@summarize []))))

(deftest ^:slow test-both-views-end-to-end
  ;; A probe inside the project so the run picks up the project config (the
  ;; babashka view) and the overlay (the jolt view). The unconditional form is
  ;; what both views see, hence the dedupe check.
  (let [dir (fs/create-temp-dir {:dir "." :prefix "lint-probe-"})
        file (str (fs/file dir "probe.cljc"))]
    (try
      (spit file (str "(ns " (fs/file-name dir) ".probe)\n"
                      "#?(:jolt (defn probe-jolt [] (undefined-jolt-sym))\n"
                      "   :bb   (defn probe-bb [] (undefined-bb-sym)))\n"
                      "(defn probe-shared [] (undefined-shared-sym))\n"))
      (let [findings (binding [*out* (java.io.StringWriter.)] (@lint-paths! [file]))
            messages (frequencies (map :message findings))]
        (testing "one run covers both branches and the shared code"
          (is (= 1 (get messages "Unresolved symbol: undefined-jolt-sym")))
          (is (= 1 (get messages "Unresolved symbol: undefined-bb-sym")))
          (is (= 1 (get messages "Unresolved symbol: undefined-shared-sym"))))
        (testing "findings carry the real path (normalized, mirror-free —
                  however the caller spelled it)"
          (is (every? #(= (str (fs/normalize file)) (:filename %)) findings)))
        (testing "the summary counts every finding once"
          (is (= {:errors 3 :warnings 0 :info 0} (@summarize findings)))))
      (finally (fs/delete-tree dir)))))

(ns kmet.tui.test-autocomplete
  "Tests for kmet.tui.autocomplete — slash command, argument, and file
   path completion via CombinedAutocompleteProvider."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.tui.autocomplete :as ac]
            [babashka.fs :as fs]))

(def ^:private test-dir (str (or (System/getenv "TMPDIR")
                                 (System/getProperty "user.home"))
                             "/kmet-autocomplete-test"))

(defn- with-temp-dir
  [f]
  (fs/delete-tree test-dir)
  (fs/create-dirs test-dir)
  (spit (str test-dir "/alpha.txt") "a")
  (spit (str test-dir "/beta.txt") "b")
  (spit (str test-dir "/.gitignore") "ignored.txt\n*.tmp\n!keep.tmp\nbuild/\n!build/artifact.clj\n")
  (spit (str test-dir "/ignored.txt") "i")
  (spit (str test-dir "/junk.tmp") "j")
  (spit (str test-dir "/keep.tmp") "k")
  (spit (str test-dir "/.hidden.clj") "h")
  (fs/create-dirs (str test-dir "/nested"))
  (spit (str test-dir "/nested/gamma.md") "g")
  (spit (str test-dir "/nested/xgamma.log") "x")
  (spit (str test-dir "/nested/delta-two.md") "d")
  (fs/create-dirs (str test-dir "/build"))
  (spit (str test-dir "/build/artifact.clj") "x")
  ;; the same dir path is reused across tests — start with a fresh snapshot
  (ac/invalidate-file-cache!)
  (try
    (f)
    (finally
      (fs/delete-tree test-dir))))

(def ^:private commands
  [{:name "model" :description "Switch model" :argument-hint "<provider:model>"
    :get-argument-completions (fn [_] [{:value "gpt-4o" :label "gpt-4o"}
                                       {:value "claude-3" :label "claude-3"}])}
   {:name "theme" :description "Switch theme" :argument-hint "<name>"}
   {:name "new" :description "Start a new session"}])

(defn- make-provider
  []
  (ac/make-combined-provider
   :commands-fn (constantly commands)
   :base-path test-dir))

(t/deftest slash-command-name-completion
  (let [p (make-provider)
        s (ac/get-suggestions p ["/mod"] 0 4 {:force false})]
    (t/is (some? s))
    (t/is (= "/mod" (:prefix s)))
    (t/is (= ["model"] (mapv :value (:items s))))))

(t/deftest slash-command-fuzzy-name-completion
  (let [p (make-provider)
        s (ac/get-suggestions p ["/th"] 0 3 {:force false})]
    (t/is (some? s))
    (t/is (= ["theme"] (mapv :value (:items s))))))

(t/deftest slash-command-argument-completion
  (let [p (make-provider)
        s (ac/get-suggestions p ["/model "] 0 7 {:force false})]
    (t/is (some? s))
    (t/is (= "" (:prefix s)))
    (t/is (= ["gpt-4o" "claude-3"] (mapv :value (:items s))))))

(t/deftest slash-command-without-arg-completion
  (let [p (make-provider)]
    (t/is (nil? (ac/get-suggestions p ["/theme "] 0 7 {:force false})))))

(t/deftest no-suggestions-for-plain-text
  (let [p (make-provider)]
    (t/is (nil? (ac/get-suggestions p ["hello world"] 0 11 {:force false})))))

(t/deftest file-path-completion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["alp"] 0 3 {:force true})]
        (t/is (some? s))
        (t/is (= ["alpha.txt"] (mapv :label (:items s))))))))

(t/deftest home-path-completion-no-double-slash
  ;; ~/sr must complete to ~/src, not ~//src (fs/parent of a bare
  ;; name is nil and the home-relative branch must not join an empty
  ;; parent with "/").
  (with-temp-dir
    (fn []
      (with-redefs [ac/expand-home-path (fn [p] (str test-dir (subs p 1)))]
        (let [p (make-provider)
              s (ac/get-suggestions p ["~/al"] 0 4 {:force false})]
          (t/is (some? s))
          (t/is (= ["~/alpha.txt"] (mapv :value (:items s)))))))))

(t/deftest file-path-completion-with-slash
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["nested/gam"] 0 10 {:force true})]
        (t/is (some? s))
        (t/is (= ["gamma.md"] (mapv :label (:items s))))
        (t/is (= ["nested/gamma.md"] (mapv :value (:items s))))))))

(t/deftest at-prefix-file-completion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@be"] 0 3 {:force false})]
        (t/is (some? s))
        (t/is (= ["@beta.txt"] (mapv :value (:items s))))))))

(t/deftest at-fuzzy-walk-finds-nested-files
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@delta"] 0 6 {:force false})]
        (t/is (some? s))
        (t/is (= ["@nested/delta-two.md"] (mapv :value (:items s))))
        (t/is (= ["nested/delta-two.md"] (mapv :description (:items s))))))))

(t/deftest at-scoped-walk-completion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@nested/gam"] 0 11 {:force false})]
        (t/is (some? s))
        (t/is (= ["gamma.md"] (mapv :label (take 1 (:items s)))))
        (t/is (= ["@nested/gamma.md"] (mapv :value (take 1 (:items s)))))))))

(t/deftest at-ranking-prefers-the-better-match
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@gamma"] 0 6 {:force false})]
        (t/is (some? s))
        ;; exact basename (gamma.md, 100) before substring (xgamma.log, 50)
        (t/is (= ["gamma.md" "xgamma.log"] (mapv :label (:items s))))))))

(t/deftest at-directory-suggestion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@nest"] 0 5 {:force false})]
        (t/is (some? s))
        (t/is (= ["nested/"] (mapv :label (:items s))))
        (t/is (= ["@nested/"] (mapv :value (:items s))))))))

(t/deftest at-gitignore-prunes-ignored-entries
  (with-temp-dir
    (fn []
      (let [p (make-provider)]
        (t/is (nil? (ac/get-suggestions p ["@ignored"] 0 8 {:force false})))
        (t/is (nil? (ac/get-suggestions p ["@junk"] 0 5 {:force false})))
        (t/is (some? (ac/get-suggestions p ["@keep"] 0 5 {:force false})))
        ;; a gitignored directory is pruned (negations inside it cannot win)
        (t/is (nil? (ac/get-suggestions p ["@build"] 0 6 {:force false})))
        (t/is (nil? (ac/get-suggestions p ["@artifact"] 0 9 {:force false})))))))

(t/deftest at-includes-hidden-files
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@hidden"] 0 7 {:force false})]
        (t/is (some? s))
        (t/is (= ["@.hidden.clj"] (mapv :value (:items s))))))))

(t/deftest at-snapshot-persists-until-invalidated
  (with-temp-dir
    (fn []
      (let [p (make-provider)]
        (t/is (some? (ac/get-suggestions p ["@beta"] 0 5 {:force false})))
        (spit (str test-dir "/beta2.txt") "b2")
        (t/is (nil? (ac/get-suggestions p ["@beta2"] 0 6 {:force false}))
              "still the cached snapshot")
        (ac/invalidate-file-cache!)
        (t/is (some? (ac/get-suggestions p ["@beta2"] 0 6 {:force false})))))))

(t/deftest at-caps-suggestions-at-20
  (with-temp-dir
    (fn []
      (doseq [i (range 30)]
        (spit (str test-dir "/cap-" i ".txt") "c"))
      (ac/invalidate-file-cache!)
      (let [p (make-provider)
            s (ac/get-suggestions p ["@cap"] 0 4 {:force false})]
        (t/is (some? s))
        (t/is (= 20 (count (:items s))))))))

(t/deftest gitignore-rule-semantics
  (let [parsed #'ac/parse-gitignore-rules
        ignored? #'ac/ignored?
        verdict (fn [text rel dir?]
                  (ignored? [{:prefix "" :rules (parsed text)}] rel dir?))]
    (t/is (= :ignored (verdict "*.log\n" "a.log" false)))
    (t/is (= :ignored (verdict "a.log" "sub/deep/a.log" false))
          "an unanchored pattern matches at any depth")
    (t/is (= :unignored (verdict "*.log\n!important.log\n" "important.log" false))
          "the last matching rule wins")
    (t/is (= :ignored (verdict "/top.txt\n" "top.txt" false)))
    (t/is (nil? (verdict "/top.txt\n" "sub/top.txt" false))
          "a leading slash anchors to the rules file's directory")
    (t/is (= :ignored (verdict "build/\n" "build" true)))
    (t/is (nil? (verdict "build/\n" "build" false))
          "a trailing slash matches directories only")
    (t/is (nil? (verdict "build/\n" "mybuild" true)))
    (t/is (= :ignored (verdict "build/\n" "a/build/artifact.clj" false))
          "anything below an ignored directory is ignored")
    (t/is (= :ignored (verdict "**/generated\n" "a/b/generated" true)))
    (t/is (= :ignored (verdict "docs/*.md\n" "docs/x.md" false)))
    (t/is (nil? (verdict "docs/*.md\n" "other/x.md" false)))
    (t/is (= :ignored (verdict "\\#file\n" "#file" false))
          "a backslash escapes a leading hash")
    (t/is (= :ignored (verdict "[]\n*.log\n" "a.log" false))
          "an uncompilable pattern is skipped, not fatal for the file")))

(t/deftest gitignore-nested-layer-precedence
  (let [parsed #'ac/parse-gitignore-rules
        ignored? #'ac/ignored?
        stack [{:prefix "" :rules (parsed "*.log")}
               {:prefix "nested" :rules (parsed "!xgamma.log")}]]
    (t/is (= :ignored (ignored? stack "nested/other.log" false)))
    (t/is (= :unignored (ignored? stack "nested/xgamma.log" false))
          "a deeper .gitignore re-includes what the root ignored")
    (t/is (= :ignored (ignored? stack "xgamma.log" false))
          "a nested layer only applies to its subtree")))

(t/deftest bare-at-lists-walk-entries-not-ignored
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            s (ac/get-suggestions p ["@"] 0 1 {:force false})]
        (t/is (some? s))
        (t/is (some #(= "@beta.txt" (:value %)) (:items s)))
        (t/is (not-any? #(str/includes? (:value %) "ignored.txt") (:items s)))
        (t/is (not-any? #(str/includes? (:value %) "junk.tmp") (:items s)))
        (t/is (not-any? #(str/includes? (:value %) "@build") (:items s)))))))

(t/deftest git-info-exclude-is-honored
  (with-temp-dir
    (fn []
      (fs/create-dirs (str test-dir "/.git/info"))
      (spit (str test-dir "/.git/info/exclude") "excluded.txt\n")
      (spit (str test-dir "/excluded.txt") "x")
      (ac/invalidate-file-cache!)
      (let [p (make-provider)]
        (t/is (nil? (ac/get-suggestions p ["@excluded"] 0 9 {:force false})))))))

(t/deftest scoped-walk-respects-parent-gitignore
  (with-temp-dir
    (fn []
      (spit (str test-dir "/nested/sub.tmp") "x")
      (let [p (make-provider)
            s (ac/get-suggestions p ["@nested/"] 0 8 {:force false})]
        ;; `*.tmp` lives in the root .gitignore; a scoped walk must still see it
        (t/is (some? s))
        (t/is (not-any? #(str/includes? (:value %) "sub.tmp") (:items s)))
        (t/is (nil? (ac/get-suggestions p ["@nested/sub"] 0 11 {:force false})))))))

(t/deftest at-scopes-are-cwd-only
  (with-temp-dir
    (fn []
      (let [p (make-provider)]
        (doseq [token ["@/" "@/tmp/x" "@C:/x" "@~/x" "@../x"]]
          (t/is (nil? (ac/get-suggestions p [token] 0 (count token) {:force false}))
                token))
        (t/is (some? (ac/get-suggestions p ["@./nested/gam"] 0 13 {:force false}))
              "a ./ scope stays inside the cwd")))))

(t/deftest file-cache-evicts-oldest-snapshot
  (with-temp-dir
    (fn []
      (doseq [i (range 9)]
        (let [d (str test-dir "/scope-" i)]
          (fs/create-dirs d)
          (spit (str d "/keep.txt") "k")))
      (ac/invalidate-file-cache!)
      (let [p (make-provider)]
        (doseq [i (range 9)]
          (let [s (str "@scope-" i "/")]
            (ac/get-suggestions p [s] 0 (count s) {:force false})))
        (t/is (= 8 (count @@#'ac/file-cache)))
        (t/is (some? (ac/get-suggestions p ["@scope-0/"] 0 9 {:force false}))
              "an evicted scope re-walks on demand")))))

(t/deftest base-path-fn-is-resolved-per-call
  (with-temp-dir
    (fn []
      ;; the app passes a fn so a session switch's runtime cwd is picked up
      ;; (pi: setupAutocompleteProvider rebuilds the provider on session
      ;; replacement)
      (let [dir (atom test-dir)
            p (ac/make-combined-provider
               :commands-fn (constantly commands)
               :base-path #(deref dir))]
        (t/is (= ["beta.txt"] (mapv :label (:items (ac/get-suggestions p ["be"] 0 2 {:force true})))))
        (let [other (str test-dir "/nested")]
          (reset! dir other)
          (t/is (= ["gamma.md"] (mapv :label (:items (ac/get-suggestions p ["ga"] 0 2 {:force true})))))
          (reset! dir test-dir)
          (t/is (nil? (ac/get-suggestions p ["ga"] 0 2 {:force true}))
                "back to the first base dir — resolution is live, not captured"))))))

(t/deftest should-trigger-file-completion
  (let [p (make-provider)]
    (t/is (false? (ac/should-trigger-file-completion p ["/model"] 0 6)))
    (t/is (true? (ac/should-trigger-file-completion p ["src/fo"] 0 6)))
    (t/is (true? (ac/should-trigger-file-completion p ["/model gpt"] 0 10)))))

(t/deftest apply-slash-command-completion
  (let [p (make-provider)
        r (ac/apply-completion p ["/mo"] 0 3 {:value "model" :label "model"} "/mo")]
    (t/is (= ["/model "] (:lines r)))
    (t/is (= 7 (:cursor-col r)))
    (t/is (= 0 (:cursor-line r)))))

(t/deftest apply-at-prefix-completion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            r (ac/apply-completion p ["@al"] 0 3 {:value "@alpha.txt" :label "alpha.txt"} "@al")]
        (t/is (= ["@alpha.txt "] (:lines r)))
        (t/is (= 11 (:cursor-col r)))))))

(t/deftest apply-file-path-completion
  (with-temp-dir
    (fn []
      (let [p (make-provider)
            r (ac/apply-completion p ["nested/ga"] 0 9 {:value "nested/gamma.md" :label "gamma.md"} "nested/ga")]
        (t/is (= ["nested/gamma.md"] (:lines r)))
        (t/is (= 15 (:cursor-col r)))))))

(t/deftest apply-completion-keeps-other-lines
  (let [p (make-provider)
        r (ac/apply-completion p ["first" "/mo"] 1 3 {:value "model" :label "model"} "/mo")]
    (t/is (= ["first" "/model "] (:lines r)))
    (t/is (= 1 (:cursor-line r)))))

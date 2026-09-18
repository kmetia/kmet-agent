(ns kmet.tui.test-autocomplete
  "Tests for kmet.tui.autocomplete — slash command, argument, and file
   path completion via CombinedAutocompleteProvider."
  (:require [clojure.test :as t]
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
  (fs/create-dirs (str test-dir "/nested"))
  (spit (str test-dir "/nested/gamma.md") "g")
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

(ns kmet.app.compaction.test-modes
  "Compactor selection and keep:N orchestration: VCC runs without the LLM,
   VCC turns free-form instructions into a follow-up prompt, and an explicit
   keep:N cut is honored by both builtin compactors."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.app.compaction :as compact]
            [kmet.app.compaction.stage :as stage]
            [kmet.app.compaction.vcc :as vcc]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.loop.test-support :as support]
            [kmet.app.session.core :as session]
            [kmet.app.session.context :as context]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :each support/with-host)

(defn- with-session
  "Run F with a session and an agent whose compaction reads the given
   settings."
  [{:keys [compactor on-event keep-recent-tokens context-window
           compact-reserve-tokens]} f]
  (let [dir (test-tmp/temp-dir! "compaction-modes-")
        sess (session/create-session dir)]
    (try
      (f sess (support/make-agent-state
               :session sess
               :compactor (or compactor :llm)
               :keep-recent-tokens (or keep-recent-tokens 20000)
               :context-window context-window
               :compact-reserve-tokens compact-reserve-tokens
               :on-event on-event))
      (finally
        (fs/delete-tree dir)))))

(defn- user! [sess text]
  (session/append-entry sess {:role :user
                              :content [{:type :text :text text}]}))

(defn- assistant! [sess text]
  (session/append-entry sess {:role :assistant
                              :content [{:type :text :text text}]}))

(defn- error-message [f]
  (try (f) nil (catch Exception e (ex-message e))))

(defn- keep-error [agent keep-user-turns]
  (error-message
   #(compact/compact-context! agent nil :manual
                              {:keep-user-turns keep-user-turns})))

(defn- compaction-entry [sess]
  (last (filter #(= :compaction (:role %)) (session/get-branch sess))))

(t/deftest vcc-compactor-runs-without-the-llm
  (with-session
    {:compactor :vcc :keep-recent-tokens 5}
    (fn [sess agent]
      (user! sess "first request with enough words to need compaction")
      (assistant! sess "first answer")
      (user! sess "second request with enough words to need compaction")
      (assistant! sess "second answer")
      (with-redefs [llm/send-message
                    (fn [& _]
                      (throw (ex-info "VCC must not call the LLM" {})))]
        (t/is (true? (compact/compact-context! agent nil :manual))))
      (let [entry (compaction-entry sess)]
        (t/is (= :compaction (:role entry)))
        (t/is (str/includes? (:summary entry) "[Early Session Requests]"))
        (t/is (= :vcc (get-in entry [:details :compactor])))
        (t/is (pos? (:tokens-before entry)))))))

(t/deftest vcc-preserves-a-branch-summary
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (session/append-entry sess {:role :branch-summary
                                  :summary "BRANCH CONTEXT"})
      (user! sess "two")
      (t/is (true? (compact/compact-context! agent nil :manual)))
      (t/is (str/includes? (:summary (compaction-entry sess))
                           "BRANCH CONTEXT")
            "a branch summary is not dropped by VCC"))))

(t/deftest vcc-turns-free-form-instructions-into-a-follow-up
  (let [events (atom [])]
    (with-session
      {:compactor :vcc :keep-recent-tokens 1
       :on-event (fn [e] (swap! events conj e))}
      (fn [sess agent]
        (user! sess "one")
        (assistant! sess "a")
        (user! sess "two")
        (let [result (compact/compact-context! agent "focus on tests" :manual)]
          (t/is (map? result) "the compaction runs")
          (t/is (= "focus on tests" (:follow-up-prompt result))
                "the instructions become a post-compaction prompt")
          (t/is (some? (compaction-entry sess)))
          (t/is (not (str/includes? (:summary (compaction-entry sess))
                                    "focus on tests"))
                "the instructions are not fed to the summarizer"))))))

(t/deftest vcc-accepts-keep-n-without-instructions
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "first request")
      (assistant! sess "a")
      (user! sess "second request")
      (assistant! sess "b")
      (t/is (true? (compact/compact-context! agent nil :manual
                                             {:keep-user-turns 1})))
      (t/is (= :compaction (:role (compaction-entry sess))))
      (t/is (str/includes? (:summary (compaction-entry sess))
                           "[Early Session Requests]")))))

(t/deftest keep-n-cut-is-shared-by-both-compactors
  (doseq [compactor [:llm :vcc]]
    (with-session
      {:compactor compactor :keep-recent-tokens 20000}
      (fn [sess agent]
        (let [_ (user! sess "one")
              _ (assistant! sess "a1")
              _ (user! sess "two")
              _ (assistant! sess "a2")
              u3 (user! sess "three")
              _ (assistant! sess "a3")
              _ (user! sess "four")
              _ (assistant! sess "a4")]
          (with-redefs [llm/send-message
                        (fn [opts]
                          (future
                            (when-let [on-text (:on-text opts)]
                              (on-text "llm summary"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))
                            :done))]
            (t/is (true? (compact/compact-context! agent nil :manual
                                                   {:keep-user-turns 2})))
            (let [entry (compaction-entry sess)]
              (t/is (= (:id u3) (:first-kept-id entry))
                    (str compactor " keeps exactly the last two user turns"))
              (t/is (= (:id u3)
                       (:id (second (context/context-entries
                                     (session/get-branch sess))))))
              (when (= :vcc compactor)
                (t/is (not (str/includes? (:summary entry) "three"))
                      "the kept turn is not summarized")))))))))

(t/deftest keep-zero-compacts-everything
  (with-session
    {:compactor :vcc :keep-recent-tokens 20000}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a1")
      (user! sess "two")
      (t/is (true? (compact/compact-context! agent nil :manual
                                             {:keep-user-turns 0})))
      (let [entry (compaction-entry sess)]
        (t/is (nil? (:first-kept-id entry)))
        (t/is (= [entry] (context/context-entries (session/get-branch sess)))
              "compact-all keeps only the summary entry")))))

(t/deftest vcc-keep-n-larger-than-history-compacts-all
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (t/is (true? (compact/compact-context! agent nil :manual
                                             {:keep-user-turns 5})))
      (t/is (some? (compaction-entry sess)))
      (t/is (nil? (:first-kept-id (compaction-entry sess)))
            "pi-vcc compacts all when keep:N cannot form a boundary"))))

(t/deftest keep-n-counts-the-projection-not-omitted-users
  (with-session
    {:compactor :vcc :keep-recent-tokens 20000}
    (fn [sess agent]
      (let [u1 (user! sess "omitted request")
            _ (assistant! sess "a")
            u2 (user! sess "kept request")]
        (session/append-context-edit! sess (:id u1) nil)
        (t/is (true? (compact/compact-context! agent nil :manual
                                               {:keep-user-turns 1})))
        (let [entry (compaction-entry sess)]
          (t/is (= (:id u2) (:first-kept-id entry)))
          (t/is (not (str/includes? (:summary entry) "omitted request"))))))))

(t/deftest vcc-explicit-keep-n-is-respected-without-a-budget-recut
  (with-session
    {:compactor :vcc
     :keep-recent-tokens 1
     :context-window 10
     :compact-reserve-tokens 2}
    (fn [sess agent]
      (let [_ (user! sess "one with plenty of tokens")
            _ (assistant! sess "answer one with plenty of tokens")
            _ (user! sess "two with plenty of tokens")
            _ (assistant! sess "answer two with plenty of tokens")
            _ (user! sess "three with plenty of tokens")
            _ (assistant! sess "answer three with plenty of tokens")]
        (t/is (true? (compact/compact-context! agent nil :manual
                                               {:keep-user-turns 2})))
        (let [entry (compaction-entry sess)]
          (t/is (nil? (:keep-fallback (:details entry)))
                "the oversized explicit tail reports its fallback")
          (t/is (some? (:first-kept-id entry))
                "the token cut still keeps a tail"))))))

(t/deftest llm-compactor-still-accepts-instructions
  (with-session
    {:compactor :llm :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (user! sess "two")
      (with-redefs [llm/send-message
                    (fn [opts]
                      (future
                        (when-let [on-text (:on-text opts)]
                          (on-text "llm summary"))
                        (when-let [on-done (:on-done opts)]
                          (on-done :stop))
                        :done))]
        (t/is (true? (compact/compact-context! agent "focus on tests" :manual)))
        (t/is (str/includes? (:summary (compaction-entry sess)) "llm summary"))))))

(t/deftest a-replaced-vcc-strategy-may-accept-instructions
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (user! sess "two")
      (let [strategies {:llm stage/llm-strategy
                        :vcc (assoc vcc/strategy
                                    :supports-custom-instructions? true)}]
        (t/is (true? ((var stage/compact-context*)
                      strategies agent "focus on tests" :manual nil)))
        (t/is (= :compaction (:role (compaction-entry sess))))
        (t/is (str/includes? (:summary (compaction-entry sess))
                             "[Compaction Instructions]"))))))

(t/deftest keep-user-turns-is-validated-at-the-contract
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (t/is (re-find #"non-negative integer" (keep-error agent "1")))
      (t/is (re-find #"non-negative integer" (keep-error agent -1))))))

(t/deftest live-compactor-setter-selects-vcc
  (with-session
    {:compactor :llm :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (user! sess "two")
      (loop-state/set-compactor! agent :vcc)
      (t/is (= :vcc (:compactor @(:cfg agent))))
      (with-redefs [llm/send-message
                    (fn [& _]
                      (throw (ex-info "VCC must not call the LLM" {})))]
        (t/is (true? (compact/compact-context! agent nil :manual))))
      (t/is (= :vcc (get-in (compaction-entry sess) [:details :compactor]))))))

(defn- llm-summary-stub []
  (fn [_opts]
    (future
      (when-let [on-text (:on-text _opts)] (on-text "llm summary"))
      (when-let [on-done (:on-done _opts)] (on-done :stop))
      :done)))

(t/deftest vcc-skip-for-providers-defers-to-the-llm
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (reset! (:provider agent) :xai)
      (swap! (:cfg agent) assoc :vcc-skip-for-providers ["xai"])
      (user! sess "one")
      (assistant! sess "a")
      (user! sess "two")
      (with-redefs [llm/send-message (llm-summary-stub)]
        (t/is (true? (compact/compact-context! agent nil :manual)))
        (t/is (= :llm (get-in (compaction-entry sess) [:details :compactor])))))))

(t/deftest vcc-smart-keep-grows-a-small-tail
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "first request here")
      (assistant! sess "a")
      (let [u2 (user! sess "second request here")]
        (assistant! sess "b")
        (let [u3 (user! sess "third request here")]
          (assistant! sess "c")
          (t/is (true? (compact/compact-context! agent nil :manual)))
          (t/is (= (:id u2) (:first-kept-id (compaction-entry sess)))
                "smart keep grows from one turn to the largest tail under 25k")
          (t/is (not= (:id u3) (:first-kept-id (compaction-entry sess)))))))))

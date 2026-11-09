(ns kmet.app.compaction.test-modes
  "Compactor selection and keep:N orchestration: VCC runs without the LLM,
   VCC rejects free-form instructions, and an explicit keep:N cut is honored
   by both builtin compactors."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.app.compaction :as compact]
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
        (t/is (str/includes? (:summary entry) "[User Messages]"))
        (t/is (= :vcc (get-in entry [:details :compactor])))
        (t/is (pos? (:tokens-before entry)))))))

(t/deftest vcc-rejects-free-form-instructions
  (let [events (atom [])]
    (with-session
      {:compactor :vcc :keep-recent-tokens 1
       :on-event (fn [e] (swap! events conj e))}
      (fn [sess agent]
        (user! sess "one")
        (assistant! sess "a")
        (user! sess "two")
        (t/is (= :failed (compact/compact-context! agent "focus on tests" :manual)))
        (t/is (nil? (compaction-entry sess))
              "a rejected request appends no compaction entry")
        (t/is (not-any? #(= :session-before-compact (:type %)) @events)
              "rejection happens before the extension boundary")
        (t/is (some #(and (= :session-compact-failed (:type %))
                          (str/includes? (:error-message %)
                                         "cannot apply custom compaction instructions"))
                    @events))))))

(t/deftest vcc-accepts-keep-n-without-instructions
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (user! sess "two")
      (assistant! sess "b")
      (t/is (true? (compact/compact-context! agent nil :manual
                                             {:keep-user-turns 1})))
      (t/is (= :compaction (:role (compaction-entry sess))))
      (t/is (str/includes? (:summary (compaction-entry sess)) "[User Messages]")))))

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

(t/deftest keep-n-larger-than-history-compacts-nothing
  (with-session
    {:compactor :vcc :keep-recent-tokens 1}
    (fn [sess agent]
      (user! sess "one")
      (assistant! sess "a")
      (t/is (false? (compact/compact-context! agent nil :manual
                                              {:keep-user-turns 5})))
      (t/is (nil? (compaction-entry sess))))))

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

(t/deftest keep-n-tail-too-large-falls-back-to-the-token-cut
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
          (t/is (= :tail-too-large (:keep-fallback (:details entry)))
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

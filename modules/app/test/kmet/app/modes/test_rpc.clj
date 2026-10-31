(ns kmet.app.modes.test-rpc
  "RPC mode end to end: `--profile rpc` boots the same composition print mode
   does, serves the EDN protocol on stdin and stdout, and streams a run as the
   wire projection. One agent, one stream, commands in.

   The model is stubbed and the session log lives under target/
   (`kmet.app.test-support`), as for every mode test."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.app.session.core :as session]
            [kmet.ai.models :as models]
            [kmet.app.modes.rpc :as rpc]
            [kmet.app.test-support :as support :refer [answering
                                                       with-mode-run
                                                       with-model-catalog]]))

(def ^:private run-wait-ms
  "How long a run may take to produce the record a test waits for: a cold Jolt
   process spends several seconds reaching the provider on its first run."
  60000)

(defn- run-rpc
  "Run the mode with LINES on stdin, answering `{:code :err :records}` — the
   protocol records it wrote, in order.

   stdin is a loopback socket, so the test owns both ends: it can keep the
   mode's stdin open while a run streams and close it when it is done. That
   matters because closing stdin ends the mode — and a mode that ends while its
   run is live cancels the run, which is right for a client that left and wrong
   for a test that only wanted to read the answer. `:until` is a string to wait
   for on the wire before closing, and `:then` lines are written after that
   wait (a command whose answer depends on the run having ended). The wait is
   bounded by the same 60 s the file's stalled-run derefs use: Jolt's first run
   in a process pays a cold-start cost (first tool, prompt and model
   resolution) of several seconds, so a tighter bound fails whichever test
   happens to run first. ARGS replace `--profile rpc` when a test wants another
   spelling of the invocation."
  [{:keys [lines args until then]}]
  (let [server (java.net.ServerSocket. 0)
        accepted (future (.accept server))
        client (java.net.Socket. "127.0.0.1" (.getLocalPort server))
        peer @accepted
        reader (io/reader (.getInputStream peer))
        writer (io/writer (.getOutputStream client))
        out (java.io.StringWriter.)
        result (future (binding [*in* reader]
                         (support/run-main-into (or args ["--profile" "rpc"]) out)))]
    (try
      (doseq [line lines]
        (.write writer (str line "\n")))
      (.flush writer)
      (catch java.io.IOException e
        ;; the mode stopped before the test finished writing: its own failure is
        ;; the one to report, so wait for it and say what it printed
        (let [{:keys [code err]} (deref result 60000 ::timeout)]
          (throw (ex-info (str "the mode ended before the test's input did"
                               " (exit " code "): " err)
                          {:code code :err err :lines lines}
                          e)))))
    (try
      (when until
        (support/wait-until #(str/includes? (str out) until) run-wait-ms))
      (doseq [line then]
        (.write writer (str line "\n")))
      (.flush writer)
      (finally
        ;; EOF for the mode: the end of its input. The mode's own end of the
        ;; socket stays open until it has read to that EOF and returned.
        (.close client)))
    (let [{:keys [code err]} (deref result 60000 ::timeout)]
      (.close peer)
      (.close server)
      {:code code
       :err err
       :records (mapv edn/read-string (remove str/blank? (str/split-lines (str out))))})))

(defn- responses
  [records]
  (filter #(= :response (:type %)) records))

(defn- by-id
  "The one response that carries ID — every command in these tests names one,
   because the id is how a client correlates an answer with its request."
  [records id]
  (first (filter #(= id (:id %)) (responses records))))

(deftest rpc-mode-serves-a-prompt-and-streams-the-wire
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code records]}
            (run-rpc {:lines ["{:type :prompt :id \"p1\" :message \"hi\"}"]
                      :until ":agent-settled"})]
        (is (= 0 code) "stdin closing ends the mode, and an orderly end exits 0")
        (is (= {:type :response :command :prompt :success true :id "p1"
                :data {:disposition :started}}
               (by-id records "p1"))
            "the command is answered with the id it carried, and accepted is
             not completed")
        (is (some #(and (= :message-update (:type %))
                        (= "hello from the model" (get-in % [:delta :content])))
                  records)
            "the run streams as the wire projection — the same events edn print
             mode prints")
        (is (some #(= :agent-settled (:type %)) records)
            "and the settle is the end a client waits for")))))

(deftest rpc-expands-a-clients-text-before-the-agent-sees-it
  ;; 5n's expansion chain is the front end's in rpc too: a prompt's text is
  ;; expanded (`/skill:`, templates) before the run, never by the model.
  (let [request (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [f (:on-text opts)] (f "ok"))
          (when-let [f (:on-done opts)] (f :stop))
          :done))
      (fn []
        (with-redefs [packages/expand-input (fn [text] (str "expanded:" text))]
          (let [{:keys [code]} (run-rpc {:lines ["{:type :prompt :id \"p\" :message \"/greet world\"}"]
                                         :until ":agent-settled"})]
            (is (= 0 code))
            (is (= "expanded:/greet world"
                   (->> (:messages @request)
                        (some #(when (= :user (:role %)) %))
                        :content first :text))
                "the run's message is the expansion, not the command")))))))

(deftest a-portable-command-is-dispatched-before-the-model
  ;; docs/rpc.md § Portable slash commands: the prompt's slash line runs the
  ;; registry with {:runtime handle :ui command-ui :frontend :rpc}; the answer
  ;; is :handled and nothing reaches the model. An unknown slash stays a prompt
  ;; (pinned by rpc-expands-a-clients-text-before-the-agent-sees-it).
  (let [request (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [f (:on-text opts)] (f "ran"))
          (when-let [f (:on-done opts)] (f :stop))
          :done))
      (fn []
        (let [{:keys [code records]}
              (run-rpc {:lines ["{:type :prompt :id \"n\" :message \"/name gates\"}"]})]
          (is (= 0 code))
          (is (= {:disposition :handled} (:data (by-id records "n")))
              "a command is answered as handled, not started")
          (is (nil? @request)
              "and the model was never called: the command is the front end's")
          (is (some #(and (= :ui-request (:type %))
                          (= :chat-info (:method %))
                          (= "Name" (:label %))
                          (str/includes? (str (:content %)) "gates"))
                    records)
              "the command's reply travels the port's output channel"))))))

(deftest a-wire-prompt-runs-the-extension-input-hooks
  ;; The wire's prompt is the submission chain, not a direct send (10a): an
  ;; extension's input hook sees the client's text and may transform it, and
  ;; the front-end kind it sees is :rpc.
  (let [request (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [f (:on-done opts)] (f :stop))
          :done))
      (fn []
        (with-redefs [extensions/apply-input-hooks
                      (fn [text mode _opts]
                        {:text (str "hooked:" text " from " (name mode))})]
          (let [{:keys [code]} (run-rpc {:lines ["{:type :prompt :message \"hi\"}"]
                                         :until ":agent-settled"})]
            (is (= 0 code))
            (is (= "hooked:hi from rpc"
                   (->> (:messages @request)
                        (some #(when (= :user (:role %)) %))
                        :content first :text))
                "the run receives the hooked text, and the hook saw :rpc")))))))

(deftest rpc-mode-answers-a-malformed-line-and-an-unknown-command
  (with-mode-run
    (answering "never runs")
    (fn []
      (let [{:keys [code records]} (run-rpc {:lines ["not edn at all"
                                                     "{:type :frobnicate :id \"f1\"}"]})]
        (is (= 0 code) "a bad line is an answer, not a crash")
        (is (= :parse (:command (first (responses records))))
            "a malformed line answers a parse failure without an id: there is
             nothing to correlate it with")
        (is (str/includes? (str (:error (by-id records "f1"))) "Unknown command")
            "an unknown type says so, so a client can tell a typo from a
             refusal")))))

(deftest the-tables-last-command-answers-silently
  ;; kmet1's table is complete: `respond` is the one command pi answers no
  ;; record to, and this mode has nothing pending to resolve (kmet2's extension
  ;; ABI carries no dialogs — the live request/response pair is the server's
  ;; approval route). An answer with no id is a failure, because the id is what
  ;; correlates it; an unknown type still reads as a typo.
  (with-mode-run
    (answering "never runs")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :respond :id \"r\"}"
                              "{:type :respond}"
                              "{:type :get-state :id \"s\"}"
                              "{:type :frobnicate :id \"f\"}"]})]
        (is (nil? (by-id records "r"))
            "a valid answer writes no record")
        (is (str/includes? (str (:error (by-id records nil))) "respond requires a string :id")
            "and the failure for one without an id carries no id either")
        (is (some? (by-id records "s"))
            "the reader kept serving after the silent command")
        (is (str/includes? (str (:error (by-id records "f"))) "Unknown command")
            "an unknown type still reads as a typo")))))

(deftest the-command-registry-is-on-the-wire
  ;; get-commands reports the same filtered registry `prompt` dispatches
  ;; through (docs/rpc.md), so a client builds a palette without guessing: the
  ;; portable builtins as :builtin, then prompt templates and skills.
  (with-mode-run
    (answering "never runs")
    (fn []
      (let [{:keys [records]} (run-rpc {:lines ["{:type :get-commands :id \"g\"}"]})
            commands (:commands (:data (by-id records "g")))]
        (is (seq commands))
        (is (= :builtin (:source (some #(when (= "name" (:name %)) %) commands)))
            "/name is one of the portable builtins")
        (is (= :builtin (:source (some #(when (= "units" (:name %)) %) commands))))
        (is (every? #(contains? #{:builtin :extension :prompt :skill} (:source %)) commands)
            "every entry names its source")))))

(deftest the-state-a-client-polls
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :prompt :id \"p\" :message \"hi\"}"
                              "{:type :get-state :id \"s\"}"]})
            state (:data (by-id records "s"))]
        (is (= "deepseek-v4-flash" (:model state))
            "the agent's model, as the run's creation option set it")
        (is (= :opencode-go (:provider state)))
        (is (string? (get-in state [:session :id]))
            "and the session the events belong to")
        (is (number? (get-in state [:session :message-count])))
        (is (contains? state :turn-running?)
            "whether a turn is in flight, which is what a reconnecting client
             folds")))))

(deftest the-invocations-model-reaches-the-run
  ;; `--model` and `--provider` are the agent's creation options here too — the
  ;; driver resolves the pattern per creation, exactly as in print mode. Without
  ;; this the mode would silently run the settings' model while the command line
  ;; said otherwise.
  (with-model-catalog
    (fn []
      (with-mode-run
        (answering "ok")
        (fn []
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :get-state :id \"s\"}"]
                          :args ["--profile" "rpc"
                                 "--model" "opencode-go/deepseek-v4-flash:high"]})
                state (:data (by-id records "s"))]
            (is (= "deepseek-v4-flash" (:model state))
                "the run's model is the one the pattern named")
            (is (= :opencode-go (:provider state)))
            (is (= :high (:thinking-level state))
                "and its thinking level, which the settings did not set")))))))

(deftest an-image-reaches-a-run-but-never-a-queue
  ;; The handle carries a prompt's images to the loop (landing 8), so a client
  ;; that sends one gets it run. A queue command still refuses: the loop's
  ;; queues carry text, and dropping the image would be a wrong answer.
  (let [request (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [f (:on-text opts)] (f "ok"))
          (when-let [f (:on-done opts)] (f :stop))
          :done))
      (fn []
        (let [{:keys [records]}
              (run-rpc {:lines ["{:type :prompt :id \"p\" :message \"look\" :images [{:type :image :data \"eA==\" :mime-type \"image/png\"}]}"
                                "{:type :steer :id \"s\" :message \"look\" :images [{:type :image :data \"eA==\"}]}"]
                        :until ":agent-settled"})]
          (is (:success (by-id records "p"))
              "a prompt with images is accepted")
          (is (some #(= :image (:type %))
                    (->> (:messages @request)
                         (some #(when (= :user (:role %)) %))
                         :content))
              "and the image block is in the request the provider received")
          (is (str/includes? (str (:error (by-id records "s"))) ":images")
              "a queue command still refuses by name"))))))

(deftest the-model-and-compaction-setters-answer-on-the-wire
  ;; 8's three lifted commands: the handle writes this agent's state, the
  ;; answer is the wire's, and the next get-state reads the change back.
  (with-redefs [models/get-available (fn [& _] [{:provider :opencode-go
                                                 :id "deepseek-v4-flash"}])]
    (with-mode-run
      (answering "never runs")
      (fn []
        (let [{:keys [code records]}
              (run-rpc {:lines ["{:type :set-model :id \"m\" :provider \"opencode-go\" :model-id \"deepseek-v4-flash\"}"
                                "{:type :set-auto-compaction :id \"a\" :enabled false}"
                                "{:type :get-state :id \"s\"}"
                                "{:type :compact :id \"c\"}"
                                "{:type :set-model :id \"bad\" :provider \"x\" :model-id \"y\"}"]})]
          (is (= 0 code))
          (is (= "deepseek-v4-flash" (:id (:data (by-id records "m"))))
              "set-model answers the switched record")
          (is (true? (:success (by-id records "a"))))
          (is (false? (get-in (by-id records "s") [:data :auto-compaction-enabled]))
              "the auto-compaction change is read back")
          (is (= {:accepted true} (:data (by-id records "c")))
              "an idle compact is accepted")
          (is (str/includes? (str (:error (by-id records "bad"))) "Model not found")
              "an unavailable model is refused by name"))))))

(deftest the-session-log-is-on-the-wire
  ;; get-entries is the log a client reads after a run, in append order: the
  ;; queue bookkeeping the send appended, then the turn. The log is the record,
  ;; not a transcript view — a client that wants only messages filters roles.
  (with-mode-run
    (answering "an answer")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :prompt :message \"hi\"}"]
                      :until ":agent-settled"
                      :then ["{:type :get-entries :id \"e\"}"]})
            entries (:entries (:data (by-id records "e")))]
        (is (= :assistant (:role (last (remove #(= :custom (:role %)) entries))))
            "the turn's last message is the answer (the queue facts may land
             before or after it: their position is the threads')")
        (is (= ["hi"] (mapv (comp :text first :content)
                            (filter #(= :user (:role %)) entries)))
            "and carries the user's message")
        (is (= 2 (count (filter #(= :custom (:role %)) entries)))
            "with the queue facts the send appended: the log is the record, not
             a transcript view. Their position is the threads', not the
             protocol's — the delivery fact lands when the run takes the
             message, which is not the same moment as the turn's first entry")
        (is (every? #(string? (:id %)) entries)
            "every entry carries the id a fork or a client would name")
        (is (some #(= "hi" (get-in % [:data :text])) entries)
            "a text prompt's queue fact carries the string it queued, not a
             wrapper map — the payload shape is part of the log's")))))

(deftest the-session-reads-are-on-the-wire
  ;; docs/rpc.md § Session: the reads a client makes after a settle — the
  ;; transcript, /copy's text, the /session numbers, the tree, and the export.
  ;; Each is a read of the agent this mode serves; none moves the session.
  (with-mode-run
    (answering "an answer")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :prompt :message \"hi\"}"]
                      :until ":agent-settled"
                      :then ["{:type :get-last-assistant-text :id \"t\"}"
                             "{:type :get-session-stats :id \"st\"}"
                             "{:type :get-tree :id \"tr\"}"
                             "{:type :get-messages :id \"m\"}"
                             "{:type :export-html :id \"x\" :output-path \"target/test-modes/export.html\"}"
                             "{:type :get-entries :id \"e\"}"
                             "{:type :get-entries :id \"es\" :since \"no-such-entry\"}"
                             "{:type :get-state :id \"s\"}"]})
            entries (get-in (by-id records "e") [:data :entries])
            state (:data (by-id records "s"))]
        (is (= "an answer" (get-in (by-id records "t") [:data :text]))
            "the last assistant text is /copy's source")
        (is (= [1 1] [(get-in (by-id records "st") [:data :user-messages])
                      (get-in (by-id records "st") [:data :assistant-messages])])
            "the stats count the turn's messages")
        (is (string? (get-in (by-id records "st") [:data :file])))
        (is (vector? (get-in (by-id records "tr") [:data :tree]))
            "the tree is the branch structure, not the flat log")
        (is (some #(= :user (:role %)) (get-in (by-id records "m") [:data :messages]))
            "the live context is what the next request sends")
        (is (str/ends-with? (str (get-in (by-id records "x") [:data :path])) "export.html")
            "the export answers the path it wrote")
        (is (= (:id (peek entries)) (get-in (by-id records "e") [:data :leaf-id]))
            "get-entries reports the branch's tip, which is the last append here")
        (is (str/includes? (str (:error (by-id records "es"))) "Entry not found")
            "a :since that names no entry is refused by name")
        (is (= :completed (:last-outcome state))
            "the state says how the last prompt settled")
        (is (false? (:compacting? state))
            "and whether a compaction runs (none here)")
        (is (= 2 (get-in state [:session :message-count]))
            "the transcript's message entries — the queue facts are the log's")
        (is (nil? (get-in state [:session :name]))
            "an unnamed session carries no :name, as pi omits it")))))

(deftest get-entries-reads-after-the-entry-it-names
  ;; The command, not the transport (the two tests above drive the wire): the
  ;; positive `:since` path needs an id the client read from an earlier answer,
  ;; so the state is a handle over a session the test built. `-c`/`--session`
  ;; cannot drive it yet — an rpc row creates its own session and refuses the
  ;; flags by name — which is landing 9's resume-by-id.
  ;; a directory under target/: Termux has no /tmp, and a mode test's litter
  ;; belongs where `bb clean` finds it
  (let [dir (str "target/test-rpc-entries-" (random-uuid))]
    (try
      (let [sess (session/create-session dir)
            append! (fn [role text]
                      (session/append-entry sess {:role role
                                                  :content [{:type :text :text text}]}))
            _ (append! :user "one")
            tip (last @(:entries sess))
            _ (append! :assistant "two")
            _ (append! :user "three")
            state {:handle {:session (atom sess)}}
            entry-text (fn [entry]
                         (-> entry :content first :text))
            after (:data (rpc/handle-record state {:type :get-entries
                                                   :since (:id tip)}))
            all (:data (rpc/handle-record state {:type :get-entries}))]
        (is (= ["two" "three"] (mapv entry-text (:entries after)))
            "the answer starts after the named entry")
        (is (= 3 (count (:entries all))) "and without :since it is the whole log")
        (is (= (:id (last @(:entries sess))) (:leaf-id all))
            "the leaf id is the tip the next append chains onto"))
      (finally (fs/delete-tree dir)))))

(deftest the-session-moves-are-on-the-wire
  ;; docs/rpc.md § Session: new-session, switch-session, clone and
  ;; set-session-name move the one agent this mode serves, and get-state reads
  ;; the move back. switch-session takes a file the test wrote, so the client's
  ;; :since sits on an id from a session it knows — the narrowing the read was
  ;; built for. Every move re-installs the process-wide extension session.
  (let [installed (atom [])]
    (with-mode-run
      (answering "an answer")
      (fn []
        (with-redefs [extensions/install-session! (fn [sess] (swap! installed conj (:id sess)))]
          (let [other (session/create-session "target/test-modes")
                _ (session/append-entry other {:role :user
                                               :content [{:type :text :text "earlier"}]})
                _ (session/append-entry other {:role :assistant
                                               :content [{:type :text :text "reply"}]})
                tip (last @(:entries other))
                {records :records}
                (run-rpc {:lines ["{:type :new-session :id \"n\"}"
                                  "{:type :get-state :id \"s1\"}"
                                  (str "{:type :switch-session :id \"w\" :session-path \""
                                       (:file other) "\"}")
                                  "{:type :get-state :id \"s2\"}"
                                  (str "{:type :get-entries :id \"e\" :since \"" (:id tip) "\"}")
                                  "{:type :set-session-name :id \"nm\" :name \"  wire session  \"}"
                                  "{:type :get-state :id \"s3\"}"
                                  "{:type :clone :id \"c\"}"
                                  "{:type :get-state :id \"s4\"}"
                                  "{:type :fork :id \"f\" :entry-id \"no-such-entry\"}"
                                  "{:type :switch-session :id \"bad\" :session-path \"\"}"
                                  "{:type :set-session-name :id \"bn\" :name \"  \"}"]})
                new-session (:data (by-id records "n"))
                after-new (:data (by-id records "s1"))
                switched (:data (by-id records "s2"))
                renamed (:data (by-id records "s3"))
                cloned (:data (by-id records "s4"))]
            (is (= :new-session (:command (by-id records "n"))))
            (is (false? (:cancelled new-session)))
            (is (string? (:session-id new-session))
                "a fresh session, and the client learns its id and file")
            (is (= (:session-id new-session) (:id (:session after-new)))
                "and get-state reads the move back (the commands are served
                 in order, so the state follows the new session)")
            (is (false? (:was-running new-session))
                "nothing was in flight to settle")
            (is (= (:id other) (get-in switched [:session :id]))
                "switch-session moved the agent to the file it named")
            (is (= [] (get-in (by-id records "e") [:data :entries]))
                "and the log after the tip is empty: :since narrowed to nothing")
            (is (= (:id tip) (get-in (by-id records "e") [:data :leaf-id])))
            (is (= "wire session" (get-in renamed [:session :name]))
                "set-session-name sanitizes and the state reads it back")
            (is (= {:cancelled false} (:data (by-id records "c")))
                "clone forked at the leaf")
            (is (not= (:id (:session switched)) (:id (:session cloned)))
                "and the agent moved to the clone")
            (is (str/includes? (str (:error (by-id records "f"))) "Entry not found")
                "a fork naming no entry is refused by name")
            (is (str/includes? (str (:error (by-id records "bad"))) ":session-path")
                "a blank path is a command-shape error, not a move")
            (is (= "Session name cannot be empty" (:error (by-id records "bn")))
                "and a whitespace-only name is refused")
            (is (= 4 (count @installed))
                "the created session, then each of the three moves, was installed")
            (is (= (get-in cloned [:session :id]) (peek @installed))
                "the last install is the session the agent ended on")))))))

(deftest the-cycles-and-queue-modes-answer
  ;; docs/rpc.md § Model, thinking, and queue modes: each setter writes this
  ;; agent's state, get-state reads it back, and an invalid value is refused by
  ;; name. The catalog is pinned to two models so the cycle has somewhere to go.
  (let [catalog [{:provider :opencode-go :id "deepseek-v4-flash" :reasoning true}
                 {:provider :opencode-go :id "deepseek-v4-pro" :reasoning true}]]
    (with-redefs [models/get-available (fn [& _] catalog)
                  ;; cycling resolves each candidate through get-model, so the
                  ;; pinned snapshot and the record lookup agree
                  models/get-model (fn [provider model-id]
                                     (first (filter #(and (= provider (:provider %))
                                                          (= model-id (:id %)))
                                                    catalog)))]
      (with-mode-run
        (answering "never runs")
        (fn []
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :get-available-thinking-levels :id \"l\"}"
                                  "{:type :set-thinking-level :id \"t\" :level :high}"
                                  "{:type :get-state :id \"s1\"}"
                                  "{:type :cycle-thinking-level :id \"c\"}"
                                  "{:type :set-thinking-level :id \"bad\" :level :nonsense}"
                                  "{:type :set-steering-mode :id \"sm\" :mode :all}"
                                  "{:type :set-follow-up-mode :id \"fm\" :mode :one-at-a-time}"
                                  "{:type :get-state :id \"s2\"}"
                                  "{:type :set-steering-mode :id \"bm\" :mode :nonsense}"
                                  "{:type :cycle-model :id \"cm\"}"]})
                levels (get-in (by-id records "l") [:data :levels])
                cycled (by-id records "c")]
            (is (seq levels) "the model's levels are the answer")
            (is (= :high (get-in (by-id records "s1") [:data :thinking-level]))
                "a set level is read back from the state")
            (is (or (nil? (:data cycled))
                    (not= :high (get-in cycled [:data :level])))
                "a cycle moves off the level it was on (nil data when the model
               offers fewer than two)")
            (is (str/includes? (str (:error (by-id records "bad"))) "Unknown thinking level")
                "an unknown level is refused by name")
            (is (= [:all :one-at-a-time]
                   [(get-in (by-id records "s2") [:data :steering-mode])
                    (get-in (by-id records "s2") [:data :follow-up-mode])])
                "the queue modes are read back")
            (is (str/includes? (str (:error (by-id records "bm"))) "Unknown queue mode")
                "an invalid mode is the runtime setter's refusal")
            (is (= "deepseek-v4-pro" (get-in (by-id records "cm") [:data :model :id]))
                "cycle-model answers the record it switched to")
            (is (false? (get-in (by-id records "cm") [:data :scoped?]))
                "nothing scoped it, so the catalog drove the cycle")))))))

(deftest clear-queue-answers-the-texts-it-dropped
  ;; pi: clear_queue — the queues are the loop's, and a client that clears them
  ;; gets the texts back. The run stalls, so the follow-up queued behind it is
  ;; still there to drop: a steer would be racy here, because the loop drains
  ;; steering into the request it is building (that timing is its own, and the
  ;; log test above pins the delivery fact instead).
  (let [release (promise)
        stalled (fn [opts]
                  (future
                    (deref release 20000 ::timeout)
                    (when-let [on-text (:on-text opts)] (on-text "done"))
                    (when-let [on-done (:on-done opts)] (on-done :stop))
                    :done))]
    (with-mode-run
      stalled
      (fn []
        (try
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :prompt :message \"go\"}"
                                  "{:type :follow-up :message \"later\"}"
                                  "{:type :clear-queue :id \"q\"}"
                                  "{:type :get-state :id \"s\"}"]})]
            (is (= {:steering [] :follow-up ["later"]}
                   (:data (by-id records "q")))
                "the dropped texts, by target")
            (is (zero? (get-in (by-id records "s") [:data :pending-message-count]))
                "and the queues are empty afterwards"))
          (finally (deliver release true)))))))

(deftest a-queue-command-tells-the-client-what-it-did
  ;; kmet2's delivery rule is start-or-queue: `steer`/`follow-up` on an idle
  ;; agent starts a run (kmet1 queued and waited for one), so the answer says
  ;; `:started` rather than a `:queued` nothing would drain. While a run is live
  ;; the answer is `:queued`, which is the pi-compatible case.
  (let [release (promise)
        stalled (fn [opts]
                  (future
                    (deref release 20000 ::timeout)
                    (when-let [on-text (:on-text opts)] (on-text "ok"))
                    (when-let [on-done (:on-done opts)] (on-done :stop))
                    :done))]
    (with-mode-run
      stalled
      (fn []
        (try
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :steer :id \"s1\" :message \"idle steer\"}"
                                  "{:type :follow-up :id \"f1\" :message \"busy follow-up\"}"]})]
            (is (= {:disposition :started} (:data (by-id records "s1")))
                "an idle steer starts the run it would otherwise wait for")
            (is (= {:disposition :queued} (:data (by-id records "f1")))
                "and a follow-up behind that run queues"))
          (finally (deliver release true)))))))

(deftest abort-cancels-a-compaction-too
  ;; kmet1's abort cancelled a manual compaction as well as the turn: it is
  ;; work nobody waits for once the client asks to stop. The answer's `:idle`
  ;; speaks for the turn, so it is still true. A command-level test: a
  ;; compaction that stalls is not something the wire can arrange hermetically.
  (let [cancelled (atom [])
        handle {:turn-running? (atom false)
                :agent-state (atom {:compacting? (atom true)})}
        state {:agents {:cancel! (fn [_handle opts] (swap! cancelled conj opts))}
               :handle handle}]
    (is (= {:type :response :command :abort :success true :id "a"
            :data {:idle true}}
           (rpc/handle-record state {:type :abort :id "a"})))
    (is (= [{:kind :user}] @cancelled)
        "the compaction was cancelled, and only once")))

(deftest the-model-catalog-is-on-the-wire
  ;; get-available-models answers the snapshot a `--model` pattern resolves
  ;; against. What the catalog contains is kmet.ai.models' subject; what this
  ;; mode owes is the answer.
  (with-redefs [models/get-available (fn [& _] [{:provider :opencode-go
                                                 :id "deepseek-v4-flash"}])]
    (with-mode-run
      (answering "ok")
      (fn []
        (let [{:keys [records]} (run-rpc {:lines ["{:type :get-available-models :id \"m\"}"]})]
          (is (= [{:provider :opencode-go :id "deepseek-v4-flash"}]
                 (:models (:data (by-id records "m"))))))))))

(deftest an-idle-abort-is-a-no-op
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [records]} (run-rpc {:lines ["{:type :abort :id \"a\"}"]})]
        (is (= {:type :response :command :abort :success true :id "a"
                :data {:idle true}}
               (by-id records "a"))
            "nothing was running, so nothing was cancelled")))))

(deftest rpc-mode-binds-a-front-end-with-a-port
  ;; kmet1's rpc mode is `:mode :rpc`, `has-ui true`, with a port whose output
  ;; channels travel the protocol: an extension's message reaches the client as
  ;; a `:ui-request` record.
  (let [seen (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! seen {:mode (extensions/extension-mode)
                      :has-ui (extensions/extension-has-ui?)})
        (extensions/ui-notify "hello from an extension" :warning)
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (let [{:keys [records]}
              (run-rpc {:lines ["{:type :prompt :message \"hi\"}"]
                        :until ":agent-settled"})]
          (is (= {:mode :rpc :has-ui true} @seen)
              "the run reports a front end with a port, not print mode's headless one")
          (is (some #(and (= :ui-request (:type %))
                          (= :notify (:method %))
                          (= "hello from an extension" (:message %))
                          (= :warning (:notify-type %)))
                    records)
              "and the port's channel is the wire: the client sees what the
               extension asked for"))))))

(deftest a-diagnostic-mount-creates-no-agent
  ;; `--dump-state` mounts every row of the profile, this one included: with no
  ;; `:on-result` the mode must stay inert — no agent, no stdin read.
  (let [{:keys [code out]} (support/run-main-isolated ["--profile" "rpc" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-rpc") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")
    (is (not (str/includes? out ":agent/"))
        "and no agent was created to serve a run nobody asked for")))

(deftest kmet1s-mode-alias-reaches-the-same-mode
  ;; `--mode rpc` is the kmet1 spelling of `--profile rpc` (landing 5b): it now
  ;; boots a mode instead of reporting that the profile has none.
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [code records]} (run-rpc {:lines ["{:type :get-state :id \"g\"}"]
                                             :args ["--mode" "rpc"]})]
        (is (= 0 code))
        (is (some? (by-id records "g"))
            "the alias boots the same protocol mode")))))

(deftest an-abort-leaves-the-reader-serving
  ;; The provider stalls, so the run is live for every command after the first.
  ;; The abort answers without waiting for it — a reader parked in a wait would
  ;; stop serving, and the get-state that follows is what proves it did not.
  (let [release (promise)
        stalled (fn [opts]
                  (future
                    (deref release 10000 ::timeout)
                    (when-let [on-text (:on-text opts)] (on-text "ok"))
                    (when-let [on-done (:on-done opts)] (on-done :stop))
                    :done))]
    (with-mode-run
      stalled
      (fn []
        (try
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :prompt :id \"1\" :message \"hi\"}"
                                  "{:type :prompt :id \"2\" :message \"more\"}"
                                  "{:type :prompt :id \"3\" :message \"more\" :streaming-behavior :steer}"
                                  "{:type :abort :id \"4\"}"
                                  "{:type :get-state :id \"5\"}"]})]
            (is (str/includes? (str (:error (by-id records "2"))) "already running")
                "a prompt while a run is live must say which queue to use")
            (is (= {:disposition :queued} (:data (by-id records "3")))
                "and with one named, the message queues")
            (is (= {:idle true} (:data (by-id records "4")))
                "the abort cancelled the run and says the agent is idle")
            (is (map? (:data (by-id records "5")))
                "and the reader kept serving commands"))
          (finally (deliver release true)))))))

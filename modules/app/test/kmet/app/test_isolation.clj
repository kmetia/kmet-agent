(ns kmet.app.test-isolation
  "Landing 9d's verification: two agents in one process, two worlds.

   The gate is one test, not five unit assertions, because the property is the
   composition's: an agent's cwd, shell and filesystem are read from the run it
   belongs to — its session's cwd, its `:shell` service value, the tool layer's
   `*cwd*` binding — and there is no process default left for them to fall back
   on. So this boots the app's own rows, creates two agents with two sessions
   and two shell prefixes, runs a turn on each asking for `bash` (`pwd`) and
   `read` (a relative `marker.txt`), and checks that each answered in its own
   world.

   The shell prefix is the proof of the shell (`echo SHELL_A` runs before the
   command, so the output carries it); the session's cwd is the proof of the
   working directory (a relative `pwd`/`marker.txt` resolves there and nowhere
   else — the two directories exist only inside this test)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.ai.llm :as llm]
            [kmet.app.config :as cfg]
            [kmet.app.core :as core]
            [kmet.app.events :as events]
            [kmet.app.runtime :as app-runtime]
            [kmet.app.session.runtime :as runtime]
            [kmet.libs.json :as json]
            [kmet.srce.core :as srce]
            [kmet.test-tmp :as test-tmp]))

(def ^:private tmp-root "target/test-isolation-worlds")

(def ^:private config
  "A run config that resolves a model and keeps its session log under the test
   tree. The model is never called — the transport is stubbed."
  {:provider :opencode-go
   :model "deepseek-v4-flash"
   :session-dir (test-tmp/temp-dir! "test-isolation-sessions-")})

(defn- world
  "A cwd under the test tree holding MARKER as `marker.txt`. Returns the dir."
  [name marker]
  (let [dir (fs/absolutize (str tmp-root "/" name))]
    (fs/create-dirs dir)
    (spit (fs/file dir "marker.txt") marker)
    (str dir)))

(defn- stub-model
  "send-message stub: the first call asks for CALLS (a seq of
   `{:name :arguments}`), every later call answers text."
  [calls]
  (let [n (atom 0)]
    (fn [opts]
      (future
        (if (= 1 (swap! n inc))
          (do (doseq [[i call] (map-indexed vector calls)]
                ((:on-tool-call opts) (assoc call :index i)))
              ((:on-done opts) :tool-calls))
          (do ((:on-text opts) "done")
              ((:on-done opts) :stop)))
        :done))))

(defn- run-turn!
  "Send GO to HANDLE through the registry and wait for the turn to settle, with
   the model asking for CALLS first."
  [agents handle calls]
  (with-redefs [cfg/get-api-key (fn [_] "test-key")
                ;; the resources selection reads the settings files directly,
                ;; so a developer's tool selection would otherwise decide this
                ;; run's declared set
                cfg/read-resource-settings (fn [] {:global nil :project nil})
                llm/send-message (stub-model calls)]
    ((:send! agents) handle "go" {:target :next-turn})
    ((:when-idle agents) handle)))

(defn- ends-between
  "The `:tool-execution-end` events recorded while F ran."
  [ends f]
  (let [n (count @ends)]
    (f)
    (vec (drop n @ends))))

(defn- result-of
  "The `:result` of TOOL's end event among ENDS."
  [ends tool]
  (:result (first (filter #(= tool (:tool-name %)) ends))))

(deftest two-agents-run-in-two-worlds
  (fs/delete-tree tmp-root)
  (fs/create-dirs tmp-root)
  (let [boot (app-runtime/boot (core/compose-config {:profile :tui}) {})
        ctx (:ctx boot)
        ends (atom [])
        unsubscribe (events/on! ctx :tool-execution-end #(swap! ends conj %))]
    (try
      (let [agents (srce/get ctx :agents)
            log (srce/get ctx :sessions)
            dir-a (world "world-a" "MARKER_A")
            dir-b (world "world-b" "MARKER_B")
            ;; each agent's own shell and model: 9's :llm/:models row needed no
            ;; cell moved, only the proof
            create (fn [dir prefix model]
                     ((:create! agents) ctx
                                        {:config (assoc config
                                                        :shell-command-prefix prefix
                                                        :model model)
                                         :session ((:create! log)
                                                   {:dir (str dir "/sessions") :cwd dir})}))
            a (create dir-a "echo SHELL_A" "deepseek-v4-flash")
            b (create dir-b "echo SHELL_B" "claude-haiku-5-5")
            call (fn [id tool args]
                   {:id id :name tool :arguments (json/generate-string args)})
            turn (fn [handle]
                   (run-turn! agents handle
                              [(call "tc-bash" "bash" {"command" "pwd"})
                               (call "tc-read" "read" {"path" "marker.txt"})]))]
        (is (= ["deepseek-v4-flash" "claude-haiku-5-5"]
               [(runtime/model a) (runtime/model b)])
            "two agents, two models — the catalog is a value on the handle, so
             no process cell had to move for it")
        (let [for-a (ends-between ends #(turn a))
              for-b (ends-between ends #(turn b))
              bash-a (:content (result-of for-a "bash"))
              bash-b (:content (result-of for-b "bash"))
              read-a (result-of for-a "read")
              read-b (result-of for-b "read")]
          (is (= 2 (count for-a)) "the first agent's batch ran both tools")
          (is (= 2 (count for-b)) "the second agent's too")

          (is (str/includes? (str bash-a) "SHELL_A")
              "the first agent's run carried its own shell")
          (is (not (str/includes? (str bash-a) "SHELL_B")))
          (is (str/includes? (str bash-b) "SHELL_B")
              "and the second agent's its own")
          (is (not (str/includes? (str bash-b) "SHELL_A")))

          (is (str/includes? (str bash-a) "world-a")
              "the first agent's shell started in its session's cwd")
          (is (not (str/includes? (str bash-a) "world-b")))
          (is (str/includes? (str bash-b) "world-b")
              "and the second agent's in its own")

          (is (= "MARKER_A" (str/trim (str (:content read-a))))
              "a relative read resolved in the first agent's cwd")
          (is (= "MARKER_B" (str/trim (str (:content read-b))))
              "and the second agent read its own file, not the first's")
          (is (nil? (:is-error read-a)) "the first agent's read is not an error")
          (is (nil? (:is-error read-b)) "nor the second's")))
      (finally
        (unsubscribe)
        (srce/dispose-subtree! ctx)
        (fs/delete-tree tmp-root)))))

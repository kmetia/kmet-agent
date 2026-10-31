(ns kmet.app.modes.test-print
  "Print mode end to end: `--profile print \"…\"` boots the real composition, runs
   one prompt through the `:agents` factory, streams the text and returns an
   exit code — the first mode that drives agents instead of an engine.

   The model is stubbed and the session log is redirected under target/: an
   entry-point test must not write to the agent dir a developer's kmet reads.
   Those are `kmet.app.test-support`'s (`with-mode-run`, `answering`), shared
   with the other mode tests."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.ai.models :as models]
            [kmet.app.test-support :as support :refer [answering
                                                       with-mode-run
                                                       with-model-catalog]]
            [kmet.app.world :as world]
            [kmet.srce.core :as srce]))

(deftest print-mode-runs-a-prompt-and-exits-zero
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code out]} (support/run-main ["--profile" "print" "hello"])]
        (is (= 0 code) "a completed prompt exits 0")
        (is (= "hello from the model" out)
            "the run's text is printed from the app's event layer")
        (is (seq (fs/glob (support/sessions-dir) "**/*.ednl"))
            "and the run left its session log behind: a mode drives the same
             durable session the rest of the app reads")))))

(deftest print-mode-without-a-prompt-says-what-it-needs
  (let [{:keys [code err]} (support/run-main ["--profile" "print"])]
    (is (= 1 code))
    (is (str/includes? err "No message provided")
        "the usage names the flag and the message, before anything boots")))

(deftest a-diagnostic-mount-does-not-run-the-prompt
  ;; `--dump-state` mounts every row of the profile, this one included — a
  ;; diagnostic mount hands the mode no `:on-result`, so it stays inert and the
  ;; dump proves the row composes without printing anything or blocking.
  (let [{:keys [code out]} (support/run-main-isolated ["--profile" "print" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-print") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")))

(deftest the-entry-loads-the-world-before-it-mounts-the-mode
  ;; 5l-ii: the mode row is deferred out of the boot, the process world loads
  ;; into the mounted tree, and only then does the mode mount. The order is the
  ;; assertion: the model call is the mode's first work, so it can only come
  ;; after the load.
  (let [order (atom [])]
    (with-mode-run
      (fn [opts]
        (swap! order conj :llm)
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (with-redefs [world/load! (fn [_opts ctx]
                                    (swap! order conj
                                           [:world (some? (srce/get ctx :agents))])
                                    {})]
          (let [{:keys [code]} (support/run-main ["--profile" "print" "hello"])]
            (is (= 0 code))
            (is (= [[:world true] :llm] @order)
                "the load runs over a tree whose world rows are already
                 mounted, and the mode's run is the last thing to start")))))))

(deftest a-failed-run-exits-one-and-says-why
  ;; The mode's verdict is its exit code: a shell script reads it. The model
  ;; fails with a non-retryable message, the loop surfaces it as :error, and
  ;; the prompt reports it on stderr rather than printing a half answer.
  (with-mode-run
    (fn [opts]
      (future
        (when-let [on-error (:on-error opts)] (on-error "boom"))
        :done))
    (fn []
      (let [{:keys [code out err]} (support/run-main ["--profile" "print" "hello"])]
        (is (= 1 code) "a failed run exits 1")
        (is (= "" out) "and prints no text")
        (is (str/includes? err "Error: boom")
            "the failure reaches stderr, named as the model reported it")))))

(deftest print-mode-can-carry-the-event-stream
  ;; `--mode edn` is kmet1's machine-readable variant: the session header, then
  ;; every event as EDN, so a consumer reads a run from the wire instead of
  ;; watching text.
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code out]} (support/run-main ["--mode" "edn" "hello"])
            events (mapv edn/read-string (str/split-lines out))]
        (is (= 0 code) "a completed prompt exits 0")
        (is (= :session (:type (first events)))
            "the header comes first: the id the events belong to")
        (is (some #(and (= :message-update (:type %))
                        (= "hello from the model" (get-in % [:delta :content])))
                  events)
            "the answer arrives as deltas")
        (is (not-any? #(contains? % :message)
                      (filter (comp #{:message-update} :type) events))
            "and a delta carries the delta, not the cumulative snapshot")
        (is (some #{:agent-settled} (drop-while #(not= :message-end %)
                                                (map :type events)))
            "the settle follows the message that ended, so a consumer can stop")
        (is (not (str/includes? out "hello from the model\nhello from the model"))
            "the text is not printed twice: edn mode prints events, not the answer")))))

(deftest the-invocations-text-is-expanded-before-the-agent-sees-it
  ;; the invocation surface's gate: `/skill:` and prompt templates are the front end's
  ;; chain, not the model's prompt. The expansion itself is
  ;; `packages/expand-input`'s (tested where it lives); this pins that the mode
  ;; calls it, on the invocation's text, before sending.
  (let [request (atom nil)
        seen (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [f (:on-text opts)] (f "ok"))
          (when-let [f (:on-done opts)] (f :stop))
          :done))
      (fn []
        (with-redefs [packages/expand-input
                      (fn [text] (reset! seen text) "expanded text")]
          (let [{:keys [code err]} (support/run-main ["-p" "hello"])]
            (is (= 0 code) err)
            (is (= "hello" @seen) "the mode expands the invocation's text")
            (is (= "expanded text"
                   (->> (:messages @request)
                        (some #(when (= :user (:role %)) %))
                        :content first :text))
                "and the agent reads the expansion")))))))

(deftest a-model-override-reaches-the-run
  ;; `--model` is kmet1's pattern — provider/model[:thinking], the provider
  ;; inferred when absent — resolved against the catalog before the run. The
  ;; agent's model is an option of the agent, not the process's, so a script can
  ;; name one without editing settings.
  (let [seen (atom nil)]
    (with-model-catalog
      (fn []
        (with-mode-run
          (fn [opts]
            (reset! seen (select-keys opts [:model :provider :thinking]))
            (future
              (when-let [on-text (:on-text opts)] (on-text "ok"))
              (when-let [on-done (:on-done opts)] (on-done :stop))
              :done))
          (fn []
            (let [{:keys [code]} (support/run-main ["-p" "--model"
                                                    "opencode-go/deepseek-v4-flash:high"
                                                    "hello"])]
              (is (= 0 code))
              (is (= {:model "deepseek-v4-flash" :provider :opencode-go
                      :thinking :high}
                     @seen)
                  "the run asked for the pattern's model, provider and thinking
                   level, not the settings'"))))))))

(deftest a-model-the-catalog-does-not-have-is-named
  ;; A pattern nothing matches is a named failure before the run, as in kmet1
  ;; (which prints the same error and exits 1) — not a request that dies later
  ;; with nothing to say.
  (let [stub (fn [opts]
               (future (when-let [on-error (:on-error opts)] (on-error "boom")) :done))]
    (with-redefs [models/get-provider (fn [p] {:id p})
                  models/get-models (fn [& _] [])]
      (with-mode-run stub
        (fn []
          (let [{:keys [code out err]} (support/run-main ["-p" "--provider" "deepseek"
                                                          "--model" "no-such-model" "hi"])]
            (is (= 1 code))
            (is (= "" out) "nothing runs, so nothing is printed")
            (is (str/includes? err "Model \"no-such-model\" not found")
                "and the failure names the model that was asked for")))))))

(deftest the-invocations-prompt-and-thinking-flags-reach-the-request
  ;; the invocation surface's gate for these flags: read them back from the provider
  ;; request, not from a map a test threaded. `--thinking` is the agent's
  ;; state; the two prompt flags are what the system message is built from.
  (let [request (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! request opts)
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (let [{:keys [code err]} (support/run-main
                                  ["-p" "--thinking" "high"
                                   "--system-prompt" "SYS-MARKER"
                                   "--append-system-prompt" "APPEND-MARKER"
                                   "hello"])
              system (->> (:messages @request)
                          (some #(when (= :system (:role %)) %))
                          :content first :text)]
          (is (= 0 code) err)
          (is (= :high (:thinking @request))
              "--thinking reaches the run, over the settings'")
          (is (str/includes? (or system "") "SYS-MARKER")
              "--system-prompt replaces the prompt source")
          (is (str/includes? (or system "") "APPEND-MARKER")
              "--append-system-prompt appends to it"))))))

(deftest the-invocations-session-dir-places-the-session
  ;; `--session-dir` is a config override, and the run's session is created from
  ;; that config: the flag moves where this run writes, not the settings' dir.
  (let [dir "target/test-modes-flag"]
    (fs/delete-tree dir)
    (with-mode-run
      (answering "ok")
      (fn []
        (let [{:keys [code err]} (support/run-main ["-p" "--session-dir" dir "hi"])]
          (is (= 0 code) err)
          (is (seq (fs/glob dir "**/*.ednl"))
              "the session landed where the flag said")
          (is (empty? (fs/glob (support/sessions-dir) "**/*.ednl"))
              "and not in the settings' directory"))))))

(deftest an-at-file-is-the-message
  ;; the invocation surface's gate: `-p @tasks.md` sends the file's contents, where the
  ;; argument was, so every mode sees text rather than a filename.
  (let [path (str "target/test-at-file-" (System/nanoTime) ".md")
        request (atom nil)]
    (spit path "hello from the file")
    (try
      (with-mode-run
        (fn [opts]
          (reset! request opts)
          (future
            (when-let [on-text (:on-text opts)] (on-text "ok"))
            (when-let [on-done (:on-done opts)] (on-done :stop))
            :done))
        (fn []
          (let [{:keys [code err]} (support/run-main ["-p" (str "@" path)])]
            (is (= 0 code) err)
            (is (str/includes? (str/join "\n" (map :content (:messages @request)))
                               "hello from the file")
                "the run was asked the file's text"))))
      (finally (fs/delete-if-exists path)))))

(deftest edn-mode-carries-the-failure-on-the-wire
  ;; In edn mode the failure is an event, so it is printed like every other and
  ;; the mode adds no error line of its own: the exit code is the verdict, and a
  ;; consumer that wanted the reason already has it.
  (with-mode-run
    (fn [opts]
      (future
        (when-let [on-error (:on-error opts)] (on-error "boom"))
        :done))
    (fn []
      (let [{:keys [code out err]} (support/run-main ["--mode" "edn" "hello"])
            events (mapv edn/read-string (str/split-lines out))]
        (is (= 1 code) "a failed run exits 1")
        (is (= [{:type :error :message "boom"}]
               (filter (comp #{:error} :type) events))
            "the error is on the wire, once")
        (is (not (str/includes? err "Error:"))
            "and not repeated on stderr, where edn consumers do not look")))))

(deftest the-run-tells-the-extension-host-it-is-print-mode
  ;; kmet1's print mode goes through its session runtime, which binds the
  ;; front-end surface: kind :print, no UI, and the headless port — so an
  ;; extension that branches on ctx.mode, or asks a question, gets kmet1's answer
  ;; under either app. The binding is the run's: what was there before it comes
  ;; back, which is not the same as the dispatcher's default.
  (let [seen (atom nil)
        outer (fn [& _])]
    ;; a front end installed before the run — an embedder's, another mode's
    (extensions/bind-extensions! {:mode :interactive :has-ui true
                                  :ui {:notify outer}})
    (with-mode-run
      (fn [opts]
        ;; read from inside the run, where the binding is live
        (reset! seen {:mode (extensions/extension-mode)
                      :has-ui (extensions/extension-has-ui?)
                      :answers? (some? (extensions/ui-capability :set-theme))
                      :inert? (nil? (extensions/ui-capability :notify))
                      :confirm (extensions/ui-call :request {:kind :confirm})})
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (is (= 0 (:code (support/run-main ["-p" "hello"]))))
        (is (= {:mode :print :has-ui false :answers? true :inert? true}
               (dissoc @seen :confirm))
            "an extension sees the run's front end: print, headless, the port
             answering where it must and inert where a no-op is right")
        (is (false? (:confirm @seen))
            "a confirm question is answered — never with yes, never with nil")))
    (is (= {:mode :interactive :has-ui true :notify? true}
           {:mode (extensions/extension-mode)
            :has-ui (extensions/extension-has-ui?)
            :notify? (identical? outer (extensions/ui-capability :notify))})
        "and the surface that was there is back, its port and all")
    (extensions/unbind-extensions!)
    (is (nil? (extensions/ui-capability :set-theme))
        "a process with no front end of its own is back to inert")))

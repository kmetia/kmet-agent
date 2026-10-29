(ns kmet.app.modes.print
  "Print mode as a row: one prompt, one run, its text on stdout, and the exit
   code the entry point returns.

   pi: modes/print-mode.ts. It is the first client of the `:agents` factory
   rather than kmet1's engine — create a handle, send the message, and listen
   on the agent's own ctx (docs/architecture.md § The event bus) for the streaming
   deltas and for the settle that ends the run. The scope is per agent: two
   print runs in one process settle their own promises, because the kernel
   routes a listener on an agent's ctx only its own agent's events.

   The row's config carries the invocation's input — `:prompt`, the
   `:on-result` callback and the output `:format` — because a mode's job is what
   the *command line* asked for, not something a profile can know
   (`kmet.app.core` merges it in). `:model`/`:provider` ride the same map and
   become the created agent's options, so an override is the run's, not the
   process's.

   **The run tells the extension host what kind of front end is running**
   (`bind-front-end!`): kmet1's print mode goes through its session runtime,
   which binds `:mode :print`, `has-ui` false and the headless capability port,
   so an extension that branches on `ctx.mode` or asks a question gets the same
   answer under either app. The port is the composition's — the print profile
   stacks `:bundle/headless` and the row binds `(:port (srce/get ctx :ui))` — so
   a run that patched in a provider answers through it rather than through a
   hardcoded default.

   Two formats, kmet1's two: `:text` (the default) streams the answer and
   reports a failure on stderr; `:edn` prints the session header and every event
   as EDN for a machine to read, so the event stream carries the failure and the
   exit code is the only thing left to say it happened. The mode prints; the
   entry point owns the process and prints its own errors."
  (:require [clojure.string :as str]
            [kmet.app.wire :as wire]
            [kmet.app.packages :as packages]
            [kmet.app.events :as events]
            [kmet.app.front-end :as front-end]
            [kmet.app.invocation :as invocation]
            [kmet.app.modes.out :as out]
            [kmet.srce.core :as srce]))

(defn- text-delta
  "The text a streaming delta adds, or nil — thinking and tool-call deltas
   render nothing in print mode."
  [event]
  (let [delta (:delta event)]
    (when (= :text (:type delta))
      (:content delta))))

(defn- exit-code
  "The run's verdict: 0 for a completed prompt, 1 for anything else. A shutdown
   (`:disposed`) is not a failure to report — the process is going away — but it
   is not success either. Only text mode reports a failure itself: edn mode has
   already printed the `:error` event that carried it."
  [format outcome error]
  (if (= :completed outcome)
    0
    (do (when (and (= :text format) (not= :disposed outcome))
          (out/error! (or error
                          (case outcome
                            :aborted "Request aborted"
                            :error "Request failed"
                            "Request ended without an outcome"))))
        1)))

(defn- dispose-all
  "One disposer for several: a run's listeners go together."
  [disposers]
  (fn [] (doseq [dispose disposers] (dispose))))

(defn- subscriptions
  "SCOPE's listeners for a run of FORMAT, as one disposer — text mode takes three
   kinds (deltas, the error, the settle), edn mode takes every public event the
   vocabulary declares and prints it (an extension-only hook is not wire data).
   Both deliver SETTLED once: a settle that
   never arrives would leave the run's thread parked, so the disposer delivers
   `:disposed`."
  [scope format settled error]
  (if (= :edn format)
    (let [out *out*
          print-event (fn [event]
                        (out/record! out (wire/wire-event event))
                        (when (= :agent-settled (:type event))
                          (deliver settled event)))]
      (events/on-every scope print-event))
    (dispose-all
     [(events/on! scope :message-update
                  (fn [event]
                    (when-let [text (text-delta event)]
                      (print text)
                      (flush))))
      (events/on! scope :error (fn [event] (reset! error (:message event))))
      (events/on! scope :agent-settled (fn [event] (deliver settled event)))])))

(defn- run-prompt!
  "One prompt: create, listen on its ctx, send, wait for the settle, report.
   Returns the exit code. The mode owns the agent it made, so the handle is
   disposed whatever the run did — and a failure to create or send is one line
   on stderr and exit 1, never a thrown thread. LISTENER holds the
   subscription: the teardown can dispose it before the run ends, and this
   function never subscribes twice.

   CREATE-OPTS are the agent's own options — the invocation's run overrides
   (`invocation/create-opts`); the driver applies them to the settings it loads.
   In edn mode the session header goes out first, before the run produces any
   event: a consumer reads the header to learn the id the events belong to."
  [scope agents {:keys [prompt format] :as config} settled error listener]
  (try
    (let [handle ((:create! agents) scope (invocation/create-opts config))]
      (try
        ;; the agent exists, so its ctx is the scope that hears only its events
        (reset! listener (subscriptions (:ctx handle) format settled error))
        (when (= :edn format)
          (prn (:header ((:session handle))))
          (flush))
        ;; the mode expands the invocation's text before the agent sees it:
        ;; `/skill:` and prompt templates are the front end's chain (kmet1's
        ;; print mode does the same one layer down)
        ((:send! agents) handle (packages/expand-input prompt) {:target :next-turn})
        (exit-code format (:outcome (deref settled)) @error)
        (finally
          (events/unsubscribe! listener)
          ((:dispose! agents) handle))))
    (catch Throwable t
      (out/error! (or (ex-message t) (str t)))
      1)))

(defn- serve
  "The `:mode-print` row's apply: wire the stream, then run on a thread of its
   own — the mount must finish so the composition can settle before the run
   starts.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without running a prompt."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        {:keys [prompt on-result]} config]
    ;; nothing to run and nothing to dispose: a diagnostic mount started nothing
    (when on-result
      ;; the guard comes before any listener: a mount that throws leaves its
      ;; fiber failed and nothing disposes what it registered
      (when (str/blank? (str prompt))
        (throw (ex-info (str "print mode needs a prompt: "
                             "kmet --profile print \"your message\"")
                        {:type :kmet.app/no-prompt})))
      (let [format (:format config :text)
            error (atom nil)
            settled (promise)
            ;; the agent does not exist yet: its listener is set once it does,
            ;; and either the teardown or the run's finally disposes it
            listener (atom nil)
            ;; kmet1's print mode binds exactly this, a layer down: kind :print,
            ;; no UI, and the composition's port (the headless answers, because
            ;; the profile stacks :bundle/headless)
            restore (front-end/install! {:mode :print
                                         :has-ui false
                                         :ui (:port (srce/get ctx :ui))})]
        (future
          (on-result {:exit (run-prompt! scope agents (assoc config :format format)
                                         settled error listener)}))
        (fn []
          ;; a shutdown before the run ends must wake it: the settle promise is
          ;; the only thing it blocks on, and an event that never comes would
          ;; leave the thread parked
          (deliver settled {:outcome :disposed})
          (events/unsubscribe! listener)
          (restore))))))

(srce/defplugin plugin
  {:name :mode-print
   ;; :ui is the ABI port the run binds: the profile stacks :bundle/headless,
   ;; so a composition that patched the provider out fails at the mount instead
   ;; of binding nil
   :inject [:agents :ui]
   :apply serve})

(ns kmet.app.tools.bash
  "Bash tool implementation — delegates to kmet.app.bash-executor for execution.
   Pi: bash tool wraps createLocalBashOperations, same engine as ! commands.
   Streams live output via an optional on-update callback (pi: onUpdate)."
  (:require [clojure.string :as str]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.util :as tool-util]))

(defn title
  "Quiet one-liner body for the bash tool: verb + `$ <cmd>` — pure data, or nil when the command is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [cmd (tool-util/title-str-arg args :command)]
    (str "bash $ " cmd)))

(def ^:private update-throttle-ms 100)  ;; pi: BASH_UPDATE_THROTTLE_MS
(def ^:private max-live-bytes (* 50 1024))  ;; pi: DEFAULT_MAX_BYTES

(def ^:dynamic *cancel-signal*
  "Cancel-signal atom for bash execution (pi passes an AbortSignal to tool
   execute). Bound by kmet.app.loop around each agent run and by the interactive
   ! flow around the user-bash emit, so Escape cancels bash everywhere — the
   loop's own tool futures AND extension code (custom tool executes, event
   handlers) that calls execute-tool. nil outside those paths."
  nil)

(defn- byte-length
  "UTF-8 byte length (pi: Buffer.byteLength)."
  [s]
  (alength (.getBytes ^String s "UTF-8")))

(defn execute
  "Execute a bash command via the shared bash executor.
   on-update — optional (fn [partial]) receiving {:content str :is-partial true}
   snapshots of the live output, throttled (pi: bash onUpdate).
   Returns {:content str :is-error bool :truncation map?}
   matching the expected tool result format."
  [{:keys [command timeout]} & [on-update]]
  (let [live-chunks (atom [])  ;; whole decoded chunks — no mid-string truncation
        live-bytes (atom 0)
        last-update (atom 0)
        trim-live! (fn []
                     ;; Pi: OutputAccumulator rolling tail — drop leading chunks
                     ;; until the live buffer fits in max-live-bytes
                     (loop []
                       (when (and (> @live-bytes max-live-bytes) (seq @live-chunks))
                         (let [c (first @live-chunks)]
                           (swap! live-chunks subvec 1)
                           (swap! live-bytes - (byte-length c))
                           (recur)))))
        send-update (fn []
                      (when (and on-update (pos? @live-bytes))
                        (let [now (System/currentTimeMillis)]
                          (when (>= (- now @last-update) update-throttle-ms)
                            (reset! last-update now)
                            (on-update {:content (apply str @live-chunks)
                                        :is-partial true})))))]
    (try
      (let [result (bash-exec/execute-bash
                    {:command command
                     :cwd (or (System/getProperty "user.dir") ".")
                     :timeout timeout  ;; nil = no timeout (pi: optional, no default)
                     :signal *cancel-signal*  ;; agent-loop cancel (pi: AbortSignal)
                     :on-chunk (fn [chunk]
                                 (swap! live-chunks conj chunk)
                                 (swap! live-bytes + (byte-length chunk))
                                 (trim-live!)
                                 (send-update))
                     :max-lines bash-exec/DEFAULT-MAX-LINES
                     :max-bytes bash-exec/DEFAULT-MAX-BYTES})
            {:keys [output exit-code cancelled truncated truncation full-output-path timed-out]} result
            ;; Pi: [Showing lines X-Y of Z. Full output: path] footer for the LLM
            ;; (stripped from the TUI display by the bash render-result, which
            ;; shows the truncation warning instead)
            footer (when (and truncation full-output-path)
                     (let [total-lines (:total-lines truncation)
                           output-lines (:output-lines truncation)
                           start-line (+ (- total-lines output-lines) 1)
                           end-line total-lines
                           limit-str (when (= (:truncated-by truncation) :bytes)
                                       (str " (" (bash-exec/format-size
                                                  (or (:max-bytes truncation)
                                                      bash-exec/DEFAULT-MAX-BYTES))
                                            " limit)"))]
                       (str "\n\n[Showing lines " start-line "-" end-line " of " total-lines
                            limit-str ". Full output: " full-output-path "]")))]
        (if cancelled
          ;; Pi: appendStatus — only adds \n\n separator when there is output
          {:content (str (when (seq (str output footer)) (str output footer "\n\n")) "Command aborted")
           :is-error true}
          (let [is-error (or timed-out (and exit-code (not= exit-code 0)))
                ;; Pi: formatOutput — empty successful output shows "(no output)"
                base (if (and (not is-error) (empty? output)) "(no output)" output)
                ;; Pi: appendStatus(outputText, status) — status follows the output
                status (cond
                         timed-out (str "Command timed out after " (or timeout "?") " seconds")
                         is-error (str "Command exited with code " exit-code)
                         :else nil)
                content (if status
                          (str (when (seq (str base footer))
                                 (str base footer "\n\n"))
                               status)
                          (str base footer))]
            (if truncated
              ;; Pi: details are lost when the tool throws, so errors carry no
              ;; truncation metadata (the footer stays in the content instead)
              (cond-> {:content content
                       :is-error is-error}
                (not is-error)
                (assoc :truncation {:total-lines (:total-lines truncation)
                                    :total-bytes (:total-bytes truncation)
                                    :shown-lines (:output-lines truncation)
                                    :truncated-by (:truncated-by truncation)
                                    :max-bytes (:max-bytes truncation)
                                    :max-lines (:max-lines truncation)
                                    :full-output-path full-output-path}))
              {:content content
               :is-error is-error}))))
      (catch Exception e
        (let [msg (ex-message e)]
          (if (str/includes? msg "timeout")
            {:content (str "Command timed out after " (or timeout "?") " seconds") :is-error true}
            {:content (str "Error: " msg) :is-error true}))))))

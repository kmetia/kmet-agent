(ns kmet.app.tools.bash-executor
  "Bash command execution with streaming, truncation, and temp file spill.
   Port of pi's bash-executor.ts + OutputAccumulator."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.process :as process]
            [kmet.libs.host :as host]
            [kmet.app.debug :as debug]
            [kmet.app.tools.bash-output :as bash-out]))

(def MAX-ROLLING-BYTES
  "Rolling tail kept in memory while streaming; a larger output spills to the
   temp file (pi: OutputAccumulator)."
  (* bash-out/DEFAULT-MAX-BYTES 2))

(def TEMP-FILE-PREFIX "kmet-bash-")
(def TEMP-FILE-SUFFIX ".log")

(def session-env-keys
  "Session metadata env vars injected per execution (see
   kmet.app.tools.bash/session-env). Stripped from the inherited environment
   before the current session's values are merged (pi: resolveSpawnContext
   deletes PI_SESSION_ID/PI_SESSION_FILE/PI_PROVIDER/PI_MODEL/
   PI_REASONING_LEVEL first), so a nested kmet's parent values never leak
   into a child command."
  ["KMET_SESSION_ID" "KMET_SESSION_FILE" "KMET_PROVIDER" "KMET_MODEL"
   "KMET_REASONING_LEVEL"])

(defn- strip-session-env
  "Drop the session metadata vars from an inherited environment (pi:
   resolveSpawnContext's deletes before the current session's values are
   merged)."
  [env]
  (apply dissoc env session-env-keys))

(defn- create-temp-file
  "Create the spill file for a truncated output (pi: createOutputFileStream).
   PREFIX names the tool's spill file, defaulting to TEMP-FILE-PREFIX
   (pi: OutputAccumulator tempFilePrefix)."
  [prefix]
  (let [tmp-dir (or (System/getenv "TMPDIR")
                    (System/getProperty "java.io.tmpdir"))
        tmp (fs/create-temp-file {:prefix (or prefix TEMP-FILE-PREFIX)
                                  :suffix TEMP-FILE-SUFFIX
                                  :dir tmp-dir})]
    (fs/delete-on-exit tmp)
    (str (fs/canonicalize tmp))))

(defn- find-bash-on-path []
  (try
    (let [cmd (if process/windows-os?
                ["cmd.exe" "/c" "where bash.exe"]
                ["sh" "-c" "command -v bash"])
          ;; Same class as the main spawn: this probe runs inside the TUI
          ;; process, so it must never inherit the TTY (a failed `where`
          ;; would garble the screen; stdin is never read but redirecting
          ;; it keeps the invariant). Note: babashka has no `:ignore` —
          ;; stderr must be `:discard` (unread pipe would block on output).
          result @(proc/process cmd {:out :string :err :discard
                                     :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
          output (str/trim (:out result))
          ;; Pi: where can return non-existent paths on Windows — verify the file exists
          first-match (first (str/split-lines output))]
      (when (and first-match
                 (or (not process/windows-os?) (fs/exists? first-match))
                 (seq (str/trim first-match)))
        (str/trim first-match)))
    (catch Exception _ nil)))

(defn- resolve-shell []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (find-bash-on-path)
      (when process/windows-os?
        (some (fn [env-var]
                (when-let [pf (System/getenv env-var)]
                  (let [path (str pf "\\Git\\bin\\bash.exe")]
                    (when (fs/exists? path) path))))
              ["ProgramFiles" "ProgramFiles(x86)"]))
      (if process/windows-os? "cmd.exe" "sh")))

(defn- wait-for-child!
  "pi: waitForChildProcess — wait for the DIRECT child to exit and return its
   exit code.

   babashka.process's deref is not usable as the wait: on Windows (bb/JVM) it
   blocks until the child's stdout/stderr pipes are closed, not merely until
   the child exits, so a detached descendant holding the pipes would stall
   the tool for that descendant's whole lifetime — and the bounded drain that
   exists for exactly that case (see the :cleanup in create-default-ops)
   would never run. `:proc` carries the java.lang.Process on both hosts; its
   waitFor returns when the child itself exits. The record's own :exit is set
   only by deref and is not read anywhere."
  [p]
  (if-let [child (:proc p)]
    (.waitFor child)
    (:exit (deref p))))

(defn- wsl-launcher-bash?
  "True when SHELL is a WSL bash launcher: the legacy
   System32\\bash.exe, or the Windows 11 AppExecutionAlias at
   %LOCALAPPDATA%\\Microsoft\\WindowsApps\\bash.exe. Both hand the command
   to the distro through a layer that expands `$name` against the distro
   environment before bash runs the script (`$i` becomes an empty word);
   the `-s` transport with the command on stdin preserves it verbatim."
  [shell]
  (boolean (re-find #"(?i)(?:windows\\system32|windowsapps)\\bash\.exe"
                    (str/replace shell "/" "\\"))))

;; ─── Spawn helpers ───────────────────────────────────────────────────────
;;
;; The spawn half is one decision (which argv transport) plus the two watchers
;; that kill the tree (timeout, cancel) and the stream drain. Each is a plain
;; fn over the values it needs: they run once per bash call, or once per 8 KiB
;; read chunk, never per byte.

(defn- stdin-transport?
  "Whether this spawn sends the command on stdin instead of argv. A WSL bash
   launcher expands `$name` against the distro environment before bash runs the
   script (`$i` becomes an empty word), so the `-s` transport with the command
   on stdin preserves it verbatim."
  [shell shell-args]
  (and (nil? shell-args) process/windows-os? (wsl-launcher-bash? shell)))

(defn- shell-argv
  "The argv that runs COMMAND under SHELL. An explicit SHELL-ARGS template wins
   (pi: ShellConfig args, argv transport) and the command is its final element;
   then cmd /c, the WSL `-s` stdin transport, and sh -c. setsid wraps it when
   the host has one, so the shell leads its own process group and
   kill-process-tree! can group-kill it — catches the background jobs mksh
   reparents outside the ppid tree."
  [shell shell-args command use-stdin?]
  (cond
    (some? shell-args)
    (if-let [setsid @process/setsid-path]
      (into [setsid shell] (conj (vec shell-args) command))
      (into [shell] (conj (vec shell-args) command)))

    (str/includes? shell "cmd") [shell "/c" command]

    use-stdin? [shell "-s"]

    :else (if-let [setsid @process/setsid-path]
            [setsid shell "-c" command]
            [shell "-c" command])))

(defn- track-spawned-pid!
  "The child's pid, tracked while it runs so kill-process-tree! can reach the
   whole group; nil when the host exposes no pid."
  [p]
  (let [pid (try (-> p :proc .pid) (catch Exception _ nil))]
    (when pid (process/track-pid! pid))
    pid))

(defn- write-command-to-stdin!
  "The WSL `-s` transport: write the command to the child's stdin and close it —
   the EOF pi's `ignore` stdio gives the other transports."
  [p command]
  (when (:in p)
    (try (spit (:in p) command) (catch Exception _ nil))
    (process/close-stdin! p)))

(defn- watch-timeout!
  "Kill the process tree when TIMEOUT seconds elapse first. Pi handles the
   timeout itself (setTimeout + killProcessTree): babashka.process has no
   :timeout option, and a deref-with-timeout would leave the process running."
  [pid timeout done timed-out]
  (when (and (number? timeout) (pos? timeout) pid)
    (let [deadline (+ (concurrent/monotonic-ms) (* timeout 1000))]
      (future
        (loop []
          (when (and (not @done) (< (concurrent/monotonic-ms) deadline))
            (Thread/sleep 100)
            (recur)))
        (when-not @done
          (reset! timed-out true)
          (process/kill-process-tree! pid))))))

(defn- watch-signal!
  "Kill the process tree when SIGNAL fires mid-run. DONE lets the poller exit on
   normal completion — without it the future spins until the next cancel (a
   thread leak per bash call); future-cancel cannot stop a running loop, so the
   flag is the exit mechanism."
  [pid signal done]
  (when (and signal pid)
    (future
      (loop []
        (when-not (or @signal @done)
          (Thread/sleep 200)
          (recur)))
      (when @signal (process/kill-process-tree! pid)))))

(defn- pump-stream!
  "Read STREAM to EOF in 8 KiB chunks, handing each to ON-DATA with TAG."
  [stream tag on-data]
  (let [buf (byte-array 8192)]
    (loop []
      (let [n (.read stream buf)]
        (when (pos? n)
          (on-data buf 0 n tag)
          (recur))))))

(defn- await-readers!
  "Give the pipe readers until DEADLINE to EOF on their own, so up to a pipe
   buffer of in-flight output is drained before anything is closed."
  [readers deadline]
  (doseq [f readers
          :when f]
    (try (deref f (max 0 (- deadline (concurrent/monotonic-ms))) nil)
         (catch Exception _ nil))))

(defn- close-reader!
  "Close one reader's stream. A reader that already EOF'd closes on the spot; a
   stuck one is closed from a background future when the host's pipe close
   drains on the calling thread (the Windows JVM does), so the bounded return
   survives. Jolt 0.8.20's Windows pipe close is nonblocking and defers
   releasing an in-flight read's buffer, so it closes inline there."
  [f stream]
  (if (and f
           (not (future-done? f))
           (not (and (host/jolt?) process/windows-os?)))
    (try (future (try (.close stream) (catch Exception _ nil)))
         (catch Exception _ nil))
    (try (.close stream) (catch Exception _ nil))))

(defn- drain-streams!
  "Pi: waitForChildProcess finalize. The process exiting does not mean its
   output was read — up to a pipe buffer can still be in flight — so the readers
   drain first, and only a reader genuinely stuck on a pipe held open by a
   detached descendant is closed. Java process-pipe reads cannot be unblocked
   (close and interrupt are no-ops on a blocked FileInputStream.read), so the
   drain is a bounded settle: readers that already EOF'd join instantly, while a
   stuck one is abandoned after the grace period and terminates when the
   descendant exits. Both readers share ONE grace deadline — draining them
   sequentially would double (or worse) the bounded return time."
  [out-future err-future p]
  (let [deadline (+ (concurrent/monotonic-ms) 2000)
        readers [out-future err-future]]
    (await-readers! readers deadline)
    (doseq [[f stream] (map vector readers [(:out p) (:err p)])
            :when stream]
      (close-reader! f stream))
    (doseq [f readers
            :when f]
      (try (deref f 250 nil) (catch Exception _ nil)))))

(defn- finish-spawn
  "DONE is set and the exit code known: either the spawn's result (with the
   stream cleanup that drains the pipes) or the throw the caller must see.
   After the process exits, abort/timeout are inferred from the flags, not the
   exit code — the kill itself returns normally with a SIGKILL code (pi)."
  [exit-code timeout signal timed-out cleanup]
  (cond
    @timed-out (throw (ex-info (str "timeout:" timeout) {}))
    (and signal @signal) (throw (ex-info "aborted" {}))
    :else {:exit-code exit-code
           :cleanup cleanup}))

(defn create-default-ops
  "Pi: createLocalShellOperations/getShellConfig — the local spawn for one
   shell. SHELL-ARGS is an explicit argv template (pi: ShellConfig args):
   it replaces the platform heuristics (cmd /c, WSL -s, sh -c) and the
   command is appended as the final element (argv transport)."
  [& {:keys [shell-path shell-args]}]
  ;; Pi: throw early if custom shellPath is specified but not found
  (when (and shell-path (not (fs/exists? shell-path)))
    (throw (ex-info (str "Custom shell path not found: " shell-path)
                    {:shell-path shell-path})))
  (let [shell (or shell-path (resolve-shell))]
    (fn spawn [{:keys [command cwd on-data signal timeout env]}]
      (when-not (fs/exists? cwd)
        (throw (ex-info (str "Working directory does not exist: " cwd) {:cwd cwd})))
      (let [;; Pi: getShellConfig resolves shell + args per platform
            use-stdin? (stdin-transport? shell shell-args)
            argv (shell-argv shell shell-args command use-stdin?)
            proc-opts {:dir cwd :err :pipe :out :pipe :env env
                       ;; Pi: stdio [ignore, pipe, pipe] — stdin is a
                       ;; NUL//dev/null redirect so a command that reads stdin
                       ;; (e.g. bare `cat`) hits EOF instead of inheriting the
                       ;; TTY and deadlocking the TUI. The WSL `bash -s`
                       ;; transport needs a real pipe instead: it writes the
                       ;; command to stdin, then closes it.
                       :in (if use-stdin?
                             :pipe
                             (fs/file (if process/windows-os? "NUL" "/dev/null")))}
            p (proc/process argv proc-opts)
            _ (when use-stdin? (write-command-to-stdin! p command))
            pid (track-spawned-pid! p)
            timed-out (atom false)
            ;; DONE is shared by both watchers below: it becomes true once the
            ;; process has exited, so each watcher can stop itself instead of
            ;; sleeping out its full window (future-cancel can't stop a running
            ;; future).
            done (atom false)
            _ (watch-timeout! pid timeout done timed-out)
            out-future (future (try (pump-stream! (:out p) :stdout on-data)
                                    (catch Exception e
                                      (debug/log "ops stdout: " e))))
            err-future (when-let [err-stream (:err p)]
                         (future (try (pump-stream! err-stream :stderr on-data)
                                      (catch Exception e
                                        (debug/log "ops stderr: " e)))))
            _ (watch-signal! pid signal done)
            ;; Wait for the process here, in binding order, so the watchers
            ;; above are already polling while we block. The watchers kill the
            ;; tree on timeout/cancel, so the wait returns on every path — and
            ;; DONE is only set below, which is what lets the signal poller keep
            ;; killing while the process is still running.
            exit-code (try (wait-for-child! p) (catch Exception _ nil))]
        ;; DONE stops both watchers within their next sleep cycle. Set only
        ;; after the process has exited; setting it earlier would tell the
        ;; watchers we are done while the process is still running, so
        ;; cancel/timeout would never kill it.
        (reset! done true)
        (when pid (process/untrack-pid! pid))
        (finish-spawn exit-code timeout signal timed-out
                      (fn [] (drain-streams! out-future err-future p)))))))

;; ─── Stream helpers ──────────────────────────────────────────────────────
;;
;; One bash call walks its streams: raw bytes → that stream's decoder →
;; sanitized text → the rolling tail and the caller's chunk callback. The state
;; is a flat map of atoms built once per call; a chunk is 8 KiB, so the few
;; calls a chunk costs are not a per-byte tax.

(defn- collect-text!
  "One decoded chunk: count its bytes, keep the rolling tail bounded, and hand
   it to the caller's chunk callback."
  [{:keys [tail-buf tail-bytes total-decoded-bytes tail-starts-at-line-boundary
           on-chunk]} text]
  (let [bytes (bash-out/byte-length text)]
    (swap! total-decoded-bytes + bytes)
    (swap! tail-buf conj text)
    (swap! tail-bytes + bytes)
    (when (> @tail-bytes MAX-ROLLING-BYTES)
      (loop []
        (when (and (> (count @tail-buf) 1)
                   (> @tail-bytes MAX-ROLLING-BYTES))
          (let [removed (first @tail-buf)]
            (swap! tail-buf subvec 1)
            (swap! tail-bytes - (bash-out/byte-length removed))
            (when (not (str/ends-with? removed "\n"))
              (reset! tail-starts-at-line-boundary false)))
          (recur))))
    (when on-chunk
      (try (on-chunk text)
           (catch Exception e
             (debug/log "bash chunk callback: " e))))))

(defn- make-decoder
  "Pi: streaming UTF-8 decoder (TextDecoder with {stream: true}) for one stream:
   a CharsetDecoder with REPLACE whose input buffer carries a sequence split
   across reads into that stream's next chunk; a sequence stranded at EOF
   decodes as U+FFFD. stdout and stderr are independent — a partial sequence at
   the end of one never combines with the other's bytes."
  []
  (let [decoder (doto (.newDecoder (java.nio.charset.Charset/forName "UTF-8"))
                  (.onMalformedInput java.nio.charset.CodingErrorAction/REPLACE)
                  (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPLACE))
        ;; Capacity 8192 (the read-stream buffer) + 3: the carry after .compact
        ;; is at most a 3-byte partial sequence.
        decode-in (java.nio.ByteBuffer/allocate (+ 8192 3))
        decode-out (java.nio.CharBuffer/allocate 8192)
        append-out!
        (fn [^StringBuilder sb]
          (.flip decode-out)
          (.append sb decode-out)
          (.clear decode-out))
        decode-into!
        (fn [sb end?]
          (loop []
            (let [res (.decode decoder decode-in decode-out end?)]
              (when (.isError res)
                (throw (ex-info "UTF-8 decode error" {:result (str res)})))
              (append-out! sb)
              (when (.isOverflow res) (recur)))))]
    {:chunk
     (fn [raw-bytes offset len]
       (when (pos? len)
         (.put decode-in raw-bytes offset len))
       (.flip decode-in)
       (let [sb (StringBuilder.)]
         (decode-into! sb false)
         (.compact decode-in)
         (let [s (str sb)]
           (when (pos? (count s)) s))))
     :finish
     (fn []
       (.flip decode-in)
       (let [sb (StringBuilder.)]
         (decode-into! sb true)
         (loop []
           (let [res (.flush decoder decode-out)]
             (when (.isError res)
               (throw (ex-info "UTF-8 flush error" {:result (str res)})))
             (append-out! sb)
             (when (.isOverflow res) (recur))))
         (let [s (str sb)]
           (when (pos? (count s)) s))))}))

(defn- spill-chunk!
  "Switch the output to the spill file (once, replaying the in-memory chunk log
   into it) and append RAW-BYTES: the file then holds the whole output."
  [{:keys [raw-chunks temp-file-path temp-file-stream temp-file-prefix]}
   raw-bytes offset len]
  (when (nil? @temp-file-path)
    (let [path (create-temp-file temp-file-prefix)
          output-stream (java.io.FileOutputStream. path)]
      (reset! temp-file-path path)
      (reset! temp-file-stream output-stream)
      (doseq [[data start end] @raw-chunks]
        (.write output-stream data start (- end start)))
      (.flush output-stream)))
  (when @temp-file-stream
    (.write @temp-file-stream raw-bytes offset len)
    (.flush @temp-file-stream)))

(defn- handle-raw-bytes!
  "One read chunk: spool the raw bytes (memory until the byte budget, then the
   spill file), decode them through that stream's decoder, and pass the
   sanitized text into the rolling tail."
  ([ctx raw-bytes offset len]
   (handle-raw-bytes! ctx raw-bytes offset len :stdout))
  ([{:keys [temp-file-path temp-file-stream total-decoded-bytes max-bytes
            raw-chunks decoders] :as ctx}
    raw-bytes offset len stream]
   (if (or @temp-file-stream @temp-file-path
           (> @total-decoded-bytes max-bytes))
     (spill-chunk! ctx raw-bytes offset len)
     (let [copy (java.util.Arrays/copyOfRange raw-bytes offset (+ offset len))]
       (swap! raw-chunks conj [copy 0 (alength copy)])))
   ;; Pi: streaming decode — handles multi-byte sequences split across chunks
   (when-let [decoded ((:chunk (get decoders stream :stdout))
                       raw-bytes offset len)]
     (let [clean (bash-out/sanitize-output decoded)]
       (when (seq clean)
         (collect-text! ctx clean))))))

(defn- close-temp-stream!
  "Close the spill stream once; its path stays for the result."
  [{:keys [temp-file-stream]}]
  (when @temp-file-stream
    (try (.close @temp-file-stream) (catch Exception _ nil))
    (reset! temp-file-stream nil)))

(defn- clean-tail
  "The rolling tail as clean output: a tail that no longer starts at a line
   boundary (the rolling buffer dropped a partial line) loses its first line."
  [{:keys [tail-buf tail-starts-at-line-boundary]}]
  (let [raw-output (apply str @tail-buf)]
    (if @tail-starts-at-line-boundary
      raw-output
      (if-let [first-newline (str/index-of raw-output "\n")]
        (subs raw-output (inc first-newline))
        raw-output))))

(defn- truncate-and-persist!
  "Truncate CLEAN-OUTPUT to the call's limits and spill the full text to a temp
   file when the limits dropped something (pi: persistIfTruncated)."
  [{:keys [temp-file-path temp-file-prefix max-lines max-bytes]} clean-output]
  (let [truncation (bash-out/truncate-tail clean-output
                                           :max-lines max-lines
                                           :max-bytes max-bytes)
        truncated (:truncated truncation)
        content (:content truncation)]
    (when (and truncated (nil? @temp-file-path))
      (let [path (create-temp-file temp-file-prefix)]
        (reset! temp-file-path path)
        (spit path clean-output)))
    {:content content
     :truncated truncated
     :truncation truncation
     :full-output-path @temp-file-path}))

(defn- finalize-output
  "The call's result once the streams have settled: close the spill stream,
   flush both decoders' end-of-input carry into the rolling tail, then clean,
   truncate and persist it."
  [ctx]
  (close-temp-stream! ctx)
  (doseq [decoder (vals (:decoders ctx))
          :let [flushed ((:finish decoder))]
          :when flushed]
    (let [clean (bash-out/sanitize-output flushed)]
      (when (seq clean)
        (collect-text! ctx clean))))
  (let [{:keys [content truncated truncation full-output-path]}
        (truncate-and-persist! ctx (clean-tail ctx))]
    {:output content
     :exit-code nil
     :cancelled false
     :truncated truncated
     :truncation truncation
     :full-output-path full-output-path}))

(defn- settle-grace!
  "Grace polling for stream data still in flight after the operations complete:
   wait while the tail keeps growing, each growth buying another 100 ms."
  [{:keys [tail-bytes]}]
  (loop [last-bytes @tail-bytes
         deadline (+ (concurrent/monotonic-ms) 100)]
    (when (< (concurrent/monotonic-ms) deadline)
      (let [current-bytes @tail-bytes]
        (if (> current-bytes last-bytes)
          (recur current-bytes (+ (concurrent/monotonic-ms) 100))
          (do (Thread/sleep 10)
              (recur last-bytes deadline)))))))

(defn- failure-result
  "The call's result when the run threw: the same clean/truncate/persist tail,
   classified as cancelled, timed out, or an error appended to the output."
  [ctx signal e]
  (let [{:keys [content truncated truncation full-output-path]}
        (truncate-and-persist! ctx (clean-tail ctx))
        result {:output content
                :exit-code nil
                :cancelled false
                :truncated truncated
                :truncation truncation
                :full-output-path full-output-path}]
    (cond
      (and signal @signal) (assoc result :cancelled true)
      (str/includes? (str (ex-message e)) "timeout") (assoc result :timed-out true)
      :else (assoc result :output (str content "\n\nError: " (ex-message e))))))

(defn- new-run-state
  "One call's streaming state: the raw chunk log and the rolling tail (whose
   first-line flag records a dropped partial line), the spill file's path and
   stream, both streams' decoders, and the limits the tail is cut to."
  [temp-file-prefix max-bytes max-lines on-chunk]
  {:raw-chunks (atom [])
   :tail-buf (atom [])
   :tail-bytes (atom 0)
   :total-decoded-bytes (atom 0)
   :tail-starts-at-line-boundary (atom true)
   :temp-file-path (atom nil)
   :temp-file-stream (atom nil)
   :decoders {:stdout (make-decoder) :stderr (make-decoder)}
   :on-chunk on-chunk
   :max-bytes max-bytes
   :max-lines max-lines
   :temp-file-prefix temp-file-prefix})

(defn execute-bash
  [{:keys [command cwd env on-chunk signal timeout spawn-hook
           operations shell-path shell-args command-prefix temp-file-prefix
           max-lines max-bytes]
    :or {cwd (System/getProperty "user.dir")
         max-lines bash-out/DEFAULT-MAX-LINES
         max-bytes bash-out/DEFAULT-MAX-BYTES}}]
  (let [;; The shell settings are the caller's (pi: shellPath /
        ;; shellCommandPrefix read at tool creation and in executeBash) — the
        ;; tool resolves the run's :shell service before calling in, so no
        ;; process default is consulted here and two sessions cannot disagree
        ;; about which shell they got (docs/architecture.md § Service catalog).
        ;; Resolve command with prefix (pi: commandPrefix)
        resolved-command (if command-prefix
                           (str command-prefix "\n" command)
                           command)
        ;; Use provided operations or default (pi: BashOperations)
        exec-ops (or operations (create-default-ops :shell-path shell-path
                                                    :shell-args shell-args))
        ctx (new-run-state temp-file-prefix max-bytes max-lines on-chunk)]
    (try
      (let [base-env (strip-session-env (into {} (System/getenv)))
            merged-env (if env (merge base-env env) base-env)
            {:keys [command cwd env]}
            (if spawn-hook
              (spawn-hook {:command resolved-command :cwd cwd :env merged-env})
              {:command resolved-command :cwd cwd :env merged-env})
            ops-result (exec-ops
                        {:command command
                         :cwd cwd
                         :on-data (partial handle-raw-bytes! ctx)
                         :signal signal
                         :timeout timeout
                         :env env})
            exit-code (:exit-code ops-result)]
        (settle-grace! ctx)
        ;; Pi: waitForChildProcess finalize — once the output has gone idle,
        ;; destroy the streams so a detached descendant holding the pipe open
        ;; can't keep the reader thread alive.
        (when-let [cleanup (:cleanup ops-result)]
          (try (cleanup) (catch Exception _ nil)))
        (assoc (finalize-output ctx) :exit-code exit-code))

      (catch Exception e
        (debug/log "bash execute error: " e)
        (failure-result ctx signal e)))))

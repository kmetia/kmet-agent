(ns kmet.modes.test-overlay-input-smoke
  "End-to-end pty regression for the overlay-focus incident: open the /lsp
   dialog, close it with ESC, type - the editor must receive the text.
   Drives this host's real app (`jolt start` under jolt, `bb start` under
   babashka) through a pty via an inline python3 driver, so this is ^:slow
   and skipped when python3 is unavailable or the host cannot fork a pty
   (Windows).

   The driver stages key off OUTPUT markers, not wall-clock delays, so a
   slow or fast startup cannot make the sequence racy: each stage waits for
   its marker (the first frame's logo before typing, the panel row before
   ESC) plus a settle delay; a stage without a marker runs a fixed delay
   after the previous write.

   The app runs with a private agent dir under target/ whose settings.edn
   enables the lsp-adapter bundled extension, so the /lsp panel opens
   regardless of the developer's own extension set; its clojure-lsp row
   doubles as proof the extension loaded."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.host :as host]))

(def ^:private driver
  "Staged pty driver: forks `<launcher> start` in a pty, writes each stage's
   bytes once its marker has appeared, and tees all output to OUTFILE.
   Stage format (comma-separated): MARKER|DELAY|HEXPAYLOAD, where MARKER is
   literal output text ('-' = no marker: DELAY after the previous write) and
   DELAY is a settle in seconds after the marker. Exits once every stage ran
   plus GRACE seconds of settle time (or the hard deadline hits); nonzero on
   early EOF or a deadline with stages left unfired."
  "
import fcntl, os, pty, select, struct, sys, termios, time
outfile, cwd, deadline_s, launcher, raw_stages = (
    sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4], sys.argv[5])
stages = [(m, float(d), bytes.fromhex(p))
          for m, d, p in (s.split('|', 2) for s in raw_stages.split(',') if s)]
grace = 3.0
out = open(outfile, 'wb')
pid, fd = pty.fork()
if pid == 0:
    os.environ['TERM'] = 'xterm-256color'
    os.chdir(cwd)
    os.execvp(launcher, [launcher, 'start'])
    os._exit(127)
# 100x30 keeps the splash header and the /lsp panel rows on screen
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 100, 0, 0))
start, seen, status = time.time(), b'', 0
fired, fired_at, due = 0, 0.0, None
try:
    while True:
        now = time.time() - start
        if fired == len(stages):
            if now >= fired_at + grace:
                break
        elif now >= deadline_s:
            status = 2  # a stage never fired: nothing more will be learned
            break
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try:
                data = os.read(fd, 65536)
            except OSError:
                status = 1  # child died early
                break
            if not data:
                status = 1
                break
            out.write(data)
            out.flush()
            seen += data
        if due is None and fired < len(stages):
            marker, delay, _payload = stages[fired]
            if marker == '-':
                due = fired_at + delay  # no marker: settle after the last write
            elif marker.encode() in seen:
                due = now + delay
        if due is not None and now >= due:
            os.write(fd, stages[fired][2])
            fired += 1
            fired_at, due = now, None
finally:
    try:
        os.kill(pid, 9)
    except OSError:
        pass
sys.exit(status)
")

(defn- python3-available? []
  (boolean (fs/which "python3")))

(defn- run-stages! [out-file agent-dir stages]
  (let [{:keys [exit]} (process/shell {:in driver
                                       :continue true
                                       :extra-env {"KMET_CODING_AGENT_DIR" agent-dir}}
                                      "python3" "-"
                                      (str out-file)
                                      (str (fs/cwd))
                                      "75"
                                      (if (host/jolt?) "jolt" "bb")
                                      (str/join ","
                                                (for [[marker delay hex] stages]
                                                  (str marker "|" delay "|" hex))))]
    exit))

(deftest ^:slow test-overlay-close-keeps-editor-alive
  (testing "the /lsp incident end to end: ESC-closing the dialog must not
           swallow subsequent typing"
    (if-not (and (python3-available?) (not (host/windows?)))
      (is true "skipped: python3 or a POSIX pty is not available")
      ;; target/ over fs/temp-dir: /tmp does not exist everywhere
      ;; (Termux) and target is already gitignored build space
      (let [out-dir (str (fs/path (fs/cwd) "target"))
            _ (fs/create-dirs out-dir)
            out-file (str (fs/path out-dir "overlay-smoke-out.raw"))
            ;; a private agent dir pins the extension set: the /lsp panel
            ;; needs lsp-adapter whatever the developer's own settings say
            agent-dir (str (fs/path out-dir "overlay-smoke-agent"))
            _ (fs/create-dirs agent-dir)
            _ (spit (str (fs/path agent-dir "settings.edn"))
                    (pr-str {:bundled-extensions ["lsp-adapter"]
                             :session-dir "sessions"}))
            logo (str "kmet (" (host/runtime-name) ")")
            ;; Payloads travel as HEX so no encoding layer can mangle ESC/CR.
            exit (run-stages! out-file agent-dir
                              [[logo 0.5 "2f6c7370"]         ;; "/lsp"
                               ["-" 1.2 "0d"]                ;; enter — its own stage: a CR inside a multi-char burst is rewritten to a newline by the paste-burst guard
                               ["clojure-lsp" 0.8 "1b"]      ;; the /lsp panel row is up: now ESC it closed
                               ["-" 1.5 "68656c6c6f2d736d6f6b65"]])] ; "hello-smoke"
        (testing "dialog opens"
          (is (zero? exit)
              (str "overlay pty driver failed (" exit "): " (slurp out-file)))
          (let [captured (slurp out-file)]
            (is (str/includes? captured "clojure-lsp")
                "the /lsp panel was actually open")
            (is (str/includes? captured "hello-smoke")
                "typed text reached the editor after ESC close")))
        (fs/delete out-file)))))

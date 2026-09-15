(ns kmet.modes.test-overlay-input-smoke
  "End-to-end pty regression for the overlay-focus incident: open the /lsp
   dialog, close it with ESC, type - the editor must receive the text.
   Drives a real `bb run` through a pty via an inline python3 driver, so
   this is ^:slow and skipped when python3 is unavailable (Windows).

   Assumes the repo-default extension set is enabled - the /lsp panel
   footer marker doubles as proof the lsp-adapter loaded; on a machine
   without it the panel never opens and the test fails loudly rather
   than passing vacuously."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def ^:private driver
  "Staged pty driver: forks `bb run` in a pty, writes each stage's bytes at
   its delay, tees all output to OUTFILE. Exits once every stage ran plus
   GRACE seconds of settle time (or the hard deadline hits); nonzero on
   early EOF."
  "
import fcntl, os, pty, select, struct, sys, termios, time
outfile, cwd, deadline_s = sys.argv[1], sys.argv[2], float(sys.argv[3])
pairs = sys.argv[4].split(',') if len(sys.argv) > 4 else []
stages = [(float(a), b) for a, b in (pair.split('|', 1) for pair in pairs)]
grace = 3.0
last_stage = stages[-1][0] if stages else 0.0
out = open(outfile, 'wb')
pid, fd = pty.fork()
if pid == 0:
    os.environ['TERM'] = 'xterm-256color'
    os.chdir(cwd)
    os.execvp('bb', ['bb', 'run'])
    os._exit(127)
# no winsize means a 0-column pty - kmet renders one char per line
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 100, 0, 0))
start = time.time()
done = set()
status = 0
try:
    while True:
        now = time.time() - start
        if now >= deadline_s or now >= last_stage + grace:
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
        for i, (at, payload) in enumerate(stages):
            if i not in done and now >= at:
                os.write(fd, payload.encode())
                done.add(i)
finally:
    try:
        os.kill(pid, 9)
    except OSError:
        pass
sys.exit(status)
")

(defn- python3-available? []
  (boolean (fs/which "python3")))

(defn- run-stages! [out-file stages]
  (let [{:keys [exit]} (process/shell {:in driver}
                                      "python3" "-"
                                      (str out-file)
                                      (str (fs/cwd))
                                      "75"
                                      (str/join ","
                                                (for [[delay bytes] stages]
                                                  (str delay "|" bytes))))]
    exit))

;; ^:bb-only: the driver spawns `bb run`, so under jolt it would exercise
;; bb's TUI, not jolt's (jolt-tui.md lists a jolt-host variant as a follow-up)
(deftest ^:slow ^:bb-only test-overlay-close-keeps-editor-alive
  (testing "the /lsp incident end to end: ESC-closing the dialog must not
           swallow subsequent typing"
    (if-not (python3-available?)
      (is true "skipped: python3 not available")
      ;; target/ over fs/temp-dir: /tmp does not exist everywhere
      ;; (Termux) and target is already gitignored build space
      (let [out-dir (str (fs/path (fs/cwd) "target"))
            _ (fs/create-dirs out-dir)
            out-file (str (fs/path out-dir "overlay-smoke-out.raw"))]
        (testing "dialog opens"
          (is (= 0 (run-stages! out-file
                                ;; enter in its own stage: a CR inside a
                                ;; multi-char burst is rewritten to a
                                ;; newline by the paste-burst guard
                                [[14.0 "/lsp"]
                                 [15.2 "\r"]
                                 [16.0 "\u001b"]
                                 [17.5 "hello-smoke"]])))
          (let [captured (slurp out-file)]
            (is (str/includes? captured "clojure-lsp")
                "the /lsp panel was actually open")
            (is (str/includes? captured "hello-smoke")
                "typed text reached the editor after ESC close")))
        (fs/delete out-file)))))

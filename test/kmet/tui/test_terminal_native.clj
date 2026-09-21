(ns kmet.tui.test-terminal-native
  "Native (FFI) terminal backend tests — Jolt-only.

   The backends are .jolt sources (Jolt-only by extension), so on bb/JVM
   they are not even loadable and every test below skips. Under `jolt test`
   they exercise the host's real backend: the tty-free surface directly and
   the Unix pty roundtrip (raw mode + reads + restore, plus two concurrent
   reads) by spawning a nested jolt in a pty through an embedded
   marker-driven driver (^:slow). The Windows console roundtrip and its
   UTF-16 helpers live in the .cljc sibling (a .clj file may not carry
   reader conditionals, and the runner loads these on both hosts)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.libs.host :as host]
            [kmet.tui.terminal :as term]))

(defn- native-create-terminal
  "This host's native backend create-terminal, or nil on bb/JVM (the native
   namespaces are empty there). Resolved at runtime so this test namespace
   compiles on both hosts."
  []
  (when (host/jolt?)
    (let [ns-sym (if (host/windows?)
                   'kmet.tui.terminal-native-win
                   'kmet.tui.terminal-native-unix)]
      (require ns-sym)
      (some-> (ns-resolve ns-sym 'create-terminal) deref))))

(deftest native-terminal-before-start
  (testing "creation, size fallback and reads before start! work without a tty"
    (if-let [create (native-create-terminal)]
      (let [t (create)]
        (t/is (false? (term/started? t)))
        (t/is (pos? (term/columns t)))
        (t/is (pos? (term/rows t)))
        (t/is (neg? (term/read-input t 10))
              "before raw mode a read waits out its timeout instead of consuming cooked input")
        (term/stop! t)
        (t/is (false? (term/started? t))))
      (t/is true "skipped: the native backend is Jolt-only"))))

;; ─── pty harness ───────────────────────────────────────────────────────────
;; A pty driver whose stages key off OUTPUT markers instead of wall-clock
;; delays, so process startup speed cannot make the sequence racy: each
;; (marker, payload) stage writes payload the moment marker appears in the
;; child's output.

(def ^:private pty-driver
  "Embedded pty driver. Waits for each stage's marker in the captured output,
   then writes its payload (HEX bytes — never non-ASCII argv). Reaps the child
   whether it exits normally or the pty read hits EIO first."
  "
import fcntl, os, pty, select, struct, sys, termios, time
outfile, cwd, timeout_s, raw_stages, exec_cmd = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4], sys.argv[5]
stages = [(m, p) for m, p in (pair.split('|', 1) for pair in raw_stages.split(',') if pair)]
out = open(outfile, 'wb')
pid, fd = pty.fork()
if pid == 0:
    os.environ['TERM'] = 'xterm-256color'
    os.chdir(cwd)
    os.execvp('jolt', ['jolt', exec_cmd])
    os._exit(127)
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 25, 90, 0, 0))
start, seen, done, status, exited = time.time(), b'', set(), 0, False
try:
    while time.time() - start < timeout_s:
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try:
                data = os.read(fd, 65536)
            except OSError:
                break  # EIO: the slave side closed (Linux)
            if not data:
                break  # EOF (BSD/macOS)
            out.write(data); out.flush(); seen += data
        for i, (marker, payload) in enumerate(stages):
            if i not in done and marker.encode() in seen:
                done.add(i)
                # Give the fixture's threads time to actually enter poll(2)
                # before the byte lands: the race under test needs BOTH polls
                # registered when the input arrives.
                time.sleep(0.25)
                os.write(fd, bytes.fromhex(payload))
        wp, st = os.waitpid(pid, os.WNOHANG)
        if wp:
            status, exited = st, True
            break
    if not exited:
        # EOF/EIO or the deadline: the child should be exiting now; reap it,
        # and only then force-kill.
        grace = time.time() + 3
        while time.time() < grace:
            wp, st = os.waitpid(pid, os.WNOHANG)
            if wp:
                status, exited = st, True
                break
            time.sleep(0.05)
finally:
    out.close()
    if not exited:
        try: os.killpg(pid, 9)
        except OSError: pass
        try: status, _ = os.waitpid(pid, 0); exited = True
        except OSError: pass
sys.exit(os.waitstatus_to_exitcode(status) if exited else 124)
")

(def ^:private pty-fixture
  "Script the pty test runs under a nested jolt. The concurrent reads are
   the regression probe for the io-lock: the driver sends ONE byte only
   after both futures are polling, so unserialized they would both see the
   fd readable — the loser's blocking read(2) then waits for a keypress
   that never comes (and stop! would hang behind it). With the lock the
   winner takes the byte and the loser times out."
  "(require '[kmet.tui.terminal :as term]
            '[kmet.tui.terminal-native-unix :as native])
(let [t (native/create-terminal)]
  (term/start! t (fn [_] nil) (fn [] nil))
  (println \"STARTED\" (term/started? t) (term/columns t) (term/rows t))
  (println \"POLLING-BOTH\")
  (let [f1 (future (term/read-input t 2000))
        f2 (future (term/read-input t 2000))
        r1 (deref f1 8000 :timeout)
        r2 (deref f2 8000 :timeout)]
    (println \"CONC\" (pr-str [r1 r2])))
  (let [deadline (+ (System/currentTimeMillis) 6000)]
    (loop [got []]
      (if (or (>= (count got) 3) (> (System/currentTimeMillis) deadline))
        (println \"GOT\" (pr-str got))
        (let [c (term/read-input t 200)]
          (if (pos? c) (recur (conj got c)) (recur got))))))
  (term/stop! t)
  (println \"STOPPED\" (term/started? t)))
")

(deftest ^:slow native-terminal-pty-roundtrip
  (testing "over a real pty: raw mode, live 90x25 size, concurrent + multi-byte reads, restore"
    (if-let [_create (native-create-terminal)]
      (let [jolt (fs/which "jolt")
            python (fs/which "python3")]
        (if-not (and jolt python (not (host/windows?)))
          (t/is true "skipped: the pty driver needs POSIX pty (jolt/python3 not usable here)")
          ;; target/ over fs/temp-dir: /tmp does not exist everywhere (Termux)
          (let [out-dir (str (fs/path (fs/cwd) "target"))
                _ (fs/create-dirs out-dir)
                fixture (str (fs/path out-dir "native-terminal-pty-fixture.clj"))
                driver (str (fs/path out-dir "native-terminal-pty-driver.py"))
                out (str (fs/path out-dir "native-terminal-pty.raw"))]
            (spit fixture pty-fixture)
            (spit driver pty-driver)
            ;; Stage 1: one byte ('x') once both futures are polling (the
            ;; driver waits 250ms after the marker, see the driver comment).
            ;; Stage 2: 'hi👋' once both concurrent reads resolved (CONC).
            ;; Payloads travel as hex so no encoding layer can mangle them.
            (let [{:keys [exit err]} (process/shell {:out :string :err :string}
                                                    (str python) driver
                                                    out (str (fs/cwd)) "30"
                                                    "POLLING-BOTH|78,CONC|6869f09f918b"
                                                    fixture)]
              (t/is (zero? exit) (str "pty driver failed (" exit "): " err (slurp out))))
            (let [captured (slurp out)]
              (t/is (str/includes? captured "STARTED true 90 25"))
              (let [[_ conc] (re-find #"CONC \[([^\]]*)\]" captured)
                    results (when conc (mapv parse-long (str/split conc #"\s+")))]
                (t/is (= 2 (count results)) "both concurrent reads resolved")
                (t/is (= [120] (vec (filter #(and % (pos? %)) results)))
                      "the single byte landed in exactly one read")
                (t/is (= [-1] (vec (filter #(and % (neg? %)) results)))
                      "the other read timed out — no blocking read left waiting"))
              (t/is (str/includes? captured "GOT [104 105 128075]")
                    "multi-byte input reassembled after the concurrent phase")
              (t/is (str/includes? captured "STOPPED false")))
            (fs/delete-if-exists fixture)
            (fs/delete-if-exists driver)
            (fs/delete-if-exists out))))
      (t/is true "skipped: the native backend is Jolt-only"))))

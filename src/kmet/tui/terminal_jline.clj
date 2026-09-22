(ns kmet.tui.terminal-jline
  "JLine 4.x terminal backend (Babashka bundles JLine 4.4.0) — the only
   namespace that imports org.jline.*. Port of @earendil-works/pi-tui
   ProcessTerminal's platform half as kmet runs it on bb/JVM: raw mode and
   timed reads from JLine, live width/height from the terminal, plus the
   stty snapshot workaround around JLine's own FFM termios handling."
  (:require [clojure.string :as str]
            [kmet.libs.terminal :as lib]
            [kmet.tui.terminal :as term])
  (:import (org.jline.terminal TerminalBuilder Terminal)
           (org.jline.utils NonBlockingReader)))

(defn- run-stty
  "Run `stty` with inherited stdin so it sees the controlling terminal
   (Java's default pipe-redirect hides it). Returns trimmed stdout, or nil
   when stty is unavailable or fails (Windows, non-tty stdin, ...). The
   stream read is bounded — a hung stty must never block startup or exit."
  [& args]
  (try
    (let [pb (ProcessBuilder. (into-array String (cons "stty" args)))
          _ (.redirectInput pb java.lang.ProcessBuilder$Redirect/INHERIT)
          _ (.redirectErrorStream pb true)
          p (.start pb)
          out (deref (future (slurp (.getInputStream p))) 2000 nil)]
      (when out
        (.waitFor p)
        (let [out (str/trim out)]
          (when (seq out) out))))
    (catch Exception _ nil)))

(defn- capture-stty-snapshot
  "The full `stty -g` saved-state string of the current terminal, or nil."
  []
  (run-stty "-g"))

(def ^:private utf8-codepage 65001)

(defn- chcp-output->codepage
  "The code page parsed from chcp's output — `Active code page: 437` —
   or nil when the output carries no number (a failure message)."
  [out]
  (when-let [[_ n] (re-find #"(\d+)" (or out ""))]
    (Long/parseLong n)))

(defn- run-chcp
  "Run the Windows console code-page tool chcp.com with ARGS, answering
   the active code page when it prints one, nil on non-Windows, no
   console, or any failure. Spawned by absolute path: ProcessBuilder did
   not find the extensionless bare name, and the child must share kmet's
   console for the setting to be visible (it does on a TTY).
   Bounded like run-stty — a hung chcp must never delay startup or exit."
  [& args]
  (when-let [exe (some-> (System/getenv "SystemRoot") (str "\\System32\\chcp.com"))]
    (try
      (let [pb (ProcessBuilder. (into-array String (cons exe args)))
            _ (.redirectErrorStream pb true)
            p (.start pb)
            out (deref (future (slurp (.getInputStream p))) 2000 nil)]
        (when out
          (.waitFor p)
          (chcp-output->codepage out)))
      (catch Exception _ nil))))

(defn- enter-utf8-codepage!
  "Switch the console to UTF-8 (code page 65001) — JLine writes UTF-8
   bytes through its writer, and a console on its OEM code page (437 on
   en-US) renders them as mojibake. Answers the previous code page when
   it changed, nil when there was nothing to do (already UTF-8, no
   console, or a non-Windows host)."
  []
  (when-let [current (run-chcp)]
    (when-not (= current utf8-codepage)
      (run-chcp (str utf8-codepage))
      current)))

(defrecord JLineTerminal [^Terminal terminal ^NonBlockingReader reader ^java.io.Writer writer
                          input-handler resize-handler running? progress-interval-atom
                          stty-snapshot-atom codepage-atom]

  term/ITerminal
  (start! [this on-input on-resize]
    (let [t (:terminal this)
          w (:writer this)]
      (reset! (:codepage-atom this) (enter-utf8-codepage!))
      (.enterRawMode t)
      (.write w lib/BRACKETED-PASTE-ON)
      (.flush w)
      (assoc this :input-handler on-input
             :resize-handler on-resize
             :running? true)))

  (stop! [this]
    (when (:running? this)
      (try
        (when-let [w (:writer this)]
          (.write w lib/BRACKETED-PASTE-OFF)
          (.flush w))
        (finally
          (when-let [cp @(:codepage-atom this)]
            (run-chcp (str cp))
            (reset! (:codepage-atom this) nil))
          (.close (:terminal this))))
      ;; Re-apply the pre-raw-mode terminal state. JLine's own restore
      ;; misses the baud rate (see create-terminal) and may leave speed 0.
      (when-let [snapshot @(:stty-snapshot-atom this)]
        (run-stty snapshot))
      (assoc this :running? false)))

  (started? [this] (boolean (:running? this)))

  (write-output [this s]
    (when-let [w (:writer this)]
      (.write w s)
      (.flush w)
      (lib/write-log! s)))

  (read-input [this timeout-ms]
    (if-let [r (:reader this)]
      (.read r (long timeout-ms))
      -1))

  (columns [this] (.getWidth (:terminal this)))
  (rows [this] (.getHeight (:terminal this)))
  (set-progress! [this active]
    (term/apply-progress! this (:progress-interval-atom this) active)))

(defn create-terminal []
  (let [;; JLine's FFM termios mapping (FfmUnixSysTerminal on aarch64 Linux)
        ;; writes a baud rate of 0 the moment the terminal is constructed —
        ;; capture the cooked-state `stty -g` snapshot BEFORE that, so stop!
        ;; can restore the real speed (and flags) after JLine's own restore.
        snapshot (capture-stty-snapshot)
        t (TerminalBuilder/terminal)]
    (map->JLineTerminal {:terminal t
                         :reader (.reader t)
                         :writer (.writer t)
                         :progress-interval-atom (atom nil)
                         :stty-snapshot-atom (atom snapshot)
                         :codepage-atom (atom nil)})))

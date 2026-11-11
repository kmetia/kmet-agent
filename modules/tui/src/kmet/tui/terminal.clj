(ns kmet.tui.terminal
  "Terminal backend abstraction: the ITerminal protocol plus the portable
   terminal knowledge every backend shares.

   The TUI core reaches the OS only through ITerminal. A backend supplies
   the platform primitives — raw mode, writing, bounded reads, live size,
   the progress keepalive; everything else (cursor/clear/title sequences,
   write log, Kitty negotiation, terminal queries, drain-on-exit) is
   derived here or in kmet.tui.terminal-escape. Backends are resolved lazily by
   create-terminal, so neither host touches the other's platform deps and
   neither OS loads the other's FFI surface:

   - kmet.tui.terminal-jline       — Babashka/JVM: JLine (bb bundles 4.4.7;
     the JVM uses the pin in modules/tui/deps.edn).
   - kmet.tui.terminal-native-unix — Jolt/Unix: termios + poll(2) over
     jolt.ffi.
   - kmet.tui.terminal-native-win  — Jolt/Windows: the kernel32 console
     API over jolt.ffi.

   A backend record owns its private state (reader/writer, raw-mode
   snapshot, progress interval atom); nothing outside the backend reads
   those fields. The small shared pieces the platform backends build on —
   the bounded-read buffer size, the COLUMNS/LINES size fallback and the
   pending-queue helper — live here."
  (:require [kmet.libs.host :as host]
            [kmet.tui.terminal-escape :as escape]))

;; ─── The protocol: platform primitives only ────────────────────────────────

(defprotocol ITerminal
  (start! [this on-input on-resize] "Enter raw mode, start reading input")
  (stop! [this] "Restore the terminal (raw mode off) and release resources")
  (started? [this] "True once start! entered raw mode — writes are live")
  (write-output [this s] "Write text to the terminal and flush")
  (read-input [this timeout-ms]
    "Read one char, waiting at most TIMEOUT-MS. Returns the char code, or a
     negative value when no input arrived (the JLine NonBlockingReader
     contract: 0..0x10FFFF on data, negative on timeout/partial sequence).")
  (columns [this] "Live terminal width in columns")
  (rows [this] "Live terminal height in rows")
  (set-progress! [this active] "Show/hide the terminal progress indicator (OSC 9;4)"))

;; ─── Portable ANSI verbs (identical for every backend) ─────────────────────

(defn hide-cursor! [terminal] (write-output terminal "\u001b[?25l"))
(defn show-cursor! [terminal] (write-output terminal "\u001b[?25h"))
(defn clear-line! [terminal] (write-output terminal "\u001b[2K"))
(defn clear-screen! [terminal] (write-output terminal "\u001b[2J\u001b[H"))
(defn set-title! [terminal title] (write-output terminal (str "\u001b]0;" title "\u0007")))
(defn move-by! [terminal lines]
  (cond
    (pos? lines) (write-output terminal (str "\u001b[" lines "B"))
    (neg? lines) (write-output terminal (str "\u001b[" (- lines) "A"))
    :else nil))
(defn clear-from-cursor! [terminal] (write-output terminal "\u001b[J"))

(defn apply-progress!
  "Shared OSC 9;4 body for a backend's set-progress! method: writes the
   active/clear sequence and owns the keepalive future held in
   INTERVAL-ATOM (some terminals drop the indicator without periodic
   re-assertion — pi: setInterval keepalive). A clear is only written when
   an active indicator existed — the interval IS the active-state marker,
   so an unconditional clear would emit an OSC on every turn end while the
   feature is off."
  [terminal interval-atom active]
  (if active
    (do (write-output terminal escape/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
        (when (nil? @interval-atom)
          (reset! interval-atom
                  (future
                    (try
                      (loop []
                        (Thread/sleep escape/TERMINAL-PROGRESS-KEEPALIVE-MS)
                        (when @interval-atom
                          (write-output terminal escape/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
                          (recur)))
                      (catch InterruptedException _))))))
    (when-let [f @interval-atom]
      (future-cancel f)
      (reset! interval-atom nil)
      (write-output terminal escape/TERMINAL-PROGRESS-CLEAR-SEQUENCE))))

;; ─── Shared native-backend pieces ──────────────────────────────────────────

(def read-buf-size
  "One bufferful per bounded read in the native backends: bytes on Unix and
   for a Windows pipe, UTF-16 code units on the Windows console path."
  1024)

(defn env-size
  "COLUMNS/LINES fallback for a live-size query that failed (pi:
   process.stdout.columns || env || 80/24). A zero, negative or unparsable
   value falls back to FALLBACK."
  [name fallback]
  (let [v (parse-long (str (System/getenv name)))]
    (if (and v (pos? v)) v fallback)))

(defn pop-pending!
  "Take the next buffered code point from PENDING-ATOM (a backend's pending
   queue), or nil. Atomic: the reader thread and the drain-on-exit path can
   both call read-input while shutdown winds down."
  [pending-atom]
  (let [[old _] (swap-vals! pending-atom
                            (fn [p] (if (seq p) (subvec p 1) p)))]
    (when (seq old)
      (nth old 0))))

;; ─── Backend dispatch ──────────────────────────────────────────────────────

(defn create-terminal
  "Create the host's terminal backend (Jolt detection: kmet.libs.host).
   The backend namespace is resolved at call time, so the JLine backend
   never loads on Jolt (no org.jline.*) and the FFI backends never load on
   bb/JVM (no jolt.ffi); on Jolt the OS picks the FFI backend, so a Unix
   host never defines a kernel32 binding and a Windows host never defines
   a termios one."
  []
  (cond
    (not (host/jolt?)) ((requiring-resolve 'kmet.tui.terminal-jline/create-terminal))
    (host/windows?) ((requiring-resolve 'kmet.tui.terminal-native-win/create-terminal))
    :else ((requiring-resolve 'kmet.tui.terminal-native-unix/create-terminal))))

;; ─── Kitty protocol wrappers (lib fns bound to this terminal's writer) ─────

(defn write-fn
  "A write-fn bound to the terminal's write-output (for kmet.tui.terminal-escape)."
  [terminal]
  #(write-output terminal %))

(defn query-kitty-protocol!
  "Send the Kitty keyboard protocol query (pi: queryAndEnableKittyProtocol)."
  [terminal]
  (escape/query-kitty-protocol! (write-fn terminal)))

(defn disable-kitty-protocol!
  "Disable the Kitty keyboard protocol and modifyOtherKeys (pi: drainInput /
   stop). Resets the global kitty-active flag."
  [terminal]
  (escape/disable-kitty-protocol! (write-fn terminal)))

(defn handle-negotiation-sequence!
  "Act on a parsed negotiation response (pi: handleKeyboardProtocolNegotiation-
   Sequence): kitty flags non-zero → enable Kitty protocol; zero flags or a
   device-attributes report (when kitty is inactive) → modifyOtherKeys
   fallback."
  [terminal parsed]
  (escape/handle-negotiation-sequence! (write-fn terminal) parsed))

(defn drain-input!
  "Disable the keyboard protocols and drain pending input so late key
   release sequences do not leak to the parent shell (pi: drainInput —
   max 1000ms, exits after 50ms of input idle). Backend-neutral: pulls
   through the protocol's bounded read-input."
  [terminal]
  (escape/disable-kitty-protocol! (write-fn terminal))
  (let [max-ms 1000
        idle-ms 50]
    (loop [last-read (System/nanoTime)
           waited 0]
      (when (and (< waited max-ms)
                 (< (- (System/nanoTime) last-read) (* idle-ms 1000000)))
        (if (>= (read-input terminal 10) 0)
          (recur (System/nanoTime) 0)
          (recur last-read (+ waited 10))))))
  nil)

;; ─── Terminal queries (pi: terminal.ts / tui.ts) ───────────────────────────

(defn query-cell-size!
  "Query the terminal's cell size in pixels (CSI 16 t). The response
   (\u001b[6;h;wt) is consumed by the TUI input path."
  [terminal]
  (write-output terminal escape/CELL-SIZE-QUERY))

(defn query-osc-11-background!
  "Query the terminal's default background color (OSC 11;?)."
  [terminal]
  (write-output terminal escape/OSC-11-BACKGROUND-QUERY))

(defn query-color-scheme!
  "Query the terminal's color scheme preference (CSI ? 996 n); the reply
   is \u001b[?997;1n (dark) / ;2n (light)."
  [terminal]
  (write-output terminal escape/COLOR-SCHEME-QUERY))

(defn set-color-scheme-notifications!
  "Enable/disable unsolicited color scheme reports (CSI ? 2031 h/l)."
  [terminal enabled?]
  (write-output terminal (if enabled?
                           escape/COLOR-SCHEME-NOTIFICATIONS-ON
                           escape/COLOR-SCHEME-NOTIFICATIONS-OFF)))

;; Re-exported protocol constants (for kmet.tui.core and other tui namespaces)
(def BRACKETED-PASTE-ON escape/BRACKETED-PASTE-ON)
(def BRACKETED-PASTE-OFF escape/BRACKETED-PASTE-OFF)
(def PASTE-START escape/PASTE-START)
(def PASTE-END escape/PASTE-END)
(def CSI-2026-SYNC-ON escape/CSI-2026-SYNC-ON)
(def CSI-2026-SYNC-OFF escape/CSI-2026-SYNC-OFF)
(def DESIRED-KITTY-FLAGS escape/DESIRED-KITTY-FLAGS)
(def KITTY-KEYBOARD-PROTOCOL-QUERY escape/KITTY-KEYBOARD-PROTOCOL-QUERY)
(def NEGOTIATION-FLUSH-TIMEOUT-MS escape/NEGOTIATION-FLUSH-TIMEOUT-MS)
(def OSC-11-BACKGROUND-QUERY escape/OSC-11-BACKGROUND-QUERY)
(def COLOR-SCHEME-QUERY escape/COLOR-SCHEME-QUERY)
(def COLOR-SCHEME-NOTIFICATIONS-ON escape/COLOR-SCHEME-NOTIFICATIONS-ON)
(def COLOR-SCHEME-NOTIFICATIONS-OFF escape/COLOR-SCHEME-NOTIFICATIONS-OFF)
(def CELL-SIZE-QUERY escape/CELL-SIZE-QUERY)
(def TERMINAL-PROGRESS-ACTIVE-SEQUENCE escape/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
(def TERMINAL-PROGRESS-CLEAR-SEQUENCE escape/TERMINAL-PROGRESS-CLEAR-SEQUENCE)
(def TERMINAL-PROGRESS-KEEPALIVE-MS escape/TERMINAL-PROGRESS-KEEPALIVE-MS)
(def parse-negotiation-sequence escape/parse-negotiation-sequence)
(def split-negotiation-response escape/split-negotiation-response)
(def split-terminal-response escape/split-terminal-response)
(def negotiation-prefix? escape/negotiation-prefix?)
(def cell-size-response-prefix? escape/cell-size-response-prefix?)
(def osc-11-response-prefix? escape/osc-11-response-prefix?)
(def color-scheme-report-prefix? escape/color-scheme-report-prefix?)
(def parse-osc-11-background-response escape/parse-osc-11-background-response)
(def parse-terminal-color-scheme-report escape/parse-terminal-color-scheme-report)
(def parse-cell-size-response escape/parse-cell-size-response)
(def enable-modify-other-keys! escape/enable-modify-other-keys!)
(def disable-modify-other-keys! escape/disable-modify-other-keys!)

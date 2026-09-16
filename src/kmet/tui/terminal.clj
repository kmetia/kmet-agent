(ns kmet.tui.terminal
  "Terminal backend abstraction: the ITerminal protocol plus the portable
   terminal knowledge every backend shares.

   The TUI core reaches the OS only through ITerminal. A backend supplies
   the platform primitives — raw mode, writing, bounded reads, live size,
   the progress keepalive; everything else (cursor/clear/title sequences,
   write log, Kitty negotiation, terminal queries, drain-on-exit) is
   derived here or in kmet.libs.terminal. Backends are resolved lazily by
   create-terminal, so neither host touches the other's platform deps:

   - kmet.tui.terminal-jline  — Babashka/JVM: JLine 4.4.0 (bundled).
   - kmet.tui.terminal-native — Jolt: termios (Unix) / kernel32 (Windows)
     raw mode, byte reads and live size over jolt.ffi.

   A backend record owns its private state (reader/writer, raw-mode
   snapshot, progress interval atom); nothing outside the backend reads
   those fields."
  (:require [kmet.libs.host :as host]
            [kmet.libs.terminal :as lib]))

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
   re-assertion — pi: setInterval keepalive)."
  [terminal interval-atom active]
  (if active
    (do (write-output terminal lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
        (when (nil? @interval-atom)
          (reset! interval-atom
                  (future
                    (try
                      (loop []
                        (Thread/sleep lib/TERMINAL-PROGRESS-KEEPALIVE-MS)
                        (when @interval-atom
                          (write-output terminal lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
                          (recur)))
                      (catch InterruptedException _))))))
    (do (when-let [f @interval-atom]
          (future-cancel f)
          (reset! interval-atom nil))
        (write-output terminal lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE))))

;; ─── Backend dispatch ──────────────────────────────────────────────────────

(defn create-terminal
  "Create the host's terminal backend (Jolt detection: kmet.libs.host).
   The backend namespace is resolved at call time, so the JLine backend
   never loads on Jolt (no org.jline.*) and the FFI backend never loads on
   bb/JVM (no jolt.ffi)."
  []
  (if (host/jolt?)
    ((requiring-resolve 'kmet.tui.terminal-native/create-terminal))
    ((requiring-resolve 'kmet.tui.terminal-jline/create-terminal))))

;; ─── Kitty protocol wrappers (lib fns bound to this terminal's writer) ─────

(defn write-fn
  "A write-fn bound to the terminal's write-output (for kmet.libs.terminal)."
  [terminal]
  #(write-output terminal %))

(defn query-kitty-protocol!
  "Send the Kitty keyboard protocol query (pi: queryAndEnableKittyProtocol)."
  [terminal]
  (lib/query-kitty-protocol! (write-fn terminal)))

(defn disable-kitty-protocol!
  "Disable the Kitty keyboard protocol and modifyOtherKeys (pi: drainInput /
   stop). Resets the global kitty-active flag."
  [terminal]
  (lib/disable-kitty-protocol! (write-fn terminal)))

(defn handle-negotiation-sequence!
  "Act on a parsed negotiation response (pi: handleKeyboardProtocolNegotiation-
   Sequence): kitty flags non-zero → enable Kitty protocol; zero flags or a
   device-attributes report (when kitty is inactive) → modifyOtherKeys
   fallback."
  [terminal parsed]
  (lib/handle-negotiation-sequence! (write-fn terminal) parsed))

(defn drain-input!
  "Disable the keyboard protocols and drain pending input so late key
   release sequences do not leak to the parent shell (pi: drainInput —
   max 1000ms, exits after 50ms of input idle). Backend-neutral: pulls
   through the protocol's bounded read-input."
  [terminal]
  (lib/disable-kitty-protocol! (write-fn terminal))
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
  (write-output terminal lib/CELL-SIZE-QUERY))

(defn query-osc-11-background!
  "Query the terminal's default background color (OSC 11;?)."
  [terminal]
  (write-output terminal lib/OSC-11-BACKGROUND-QUERY))

(defn query-color-scheme!
  "Query the terminal's color scheme preference (CSI ? 996 n); the reply
   is \u001b[?997;1n (dark) / ;2n (light)."
  [terminal]
  (write-output terminal lib/COLOR-SCHEME-QUERY))

(defn set-color-scheme-notifications!
  "Enable/disable unsolicited color scheme reports (CSI ? 2031 h/l)."
  [terminal enabled?]
  (write-output terminal (if enabled?
                           lib/COLOR-SCHEME-NOTIFICATIONS-ON
                           lib/COLOR-SCHEME-NOTIFICATIONS-OFF)))

;; Re-exported protocol constants (for kmet.tui.core and other tui namespaces)
(def BRACKETED-PASTE-ON lib/BRACKETED-PASTE-ON)
(def BRACKETED-PASTE-OFF lib/BRACKETED-PASTE-OFF)
(def PASTE-START lib/PASTE-START)
(def PASTE-END lib/PASTE-END)
(def CSI-2026-SYNC-ON lib/CSI-2026-SYNC-ON)
(def CSI-2026-SYNC-OFF lib/CSI-2026-SYNC-OFF)
(def DESIRED-KITTY-FLAGS lib/DESIRED-KITTY-FLAGS)
(def KITTY-KEYBOARD-PROTOCOL-QUERY lib/KITTY-KEYBOARD-PROTOCOL-QUERY)
(def NEGOTIATION-FLUSH-TIMEOUT-MS lib/NEGOTIATION-FLUSH-TIMEOUT-MS)
(def OSC-11-BACKGROUND-QUERY lib/OSC-11-BACKGROUND-QUERY)
(def COLOR-SCHEME-QUERY lib/COLOR-SCHEME-QUERY)
(def COLOR-SCHEME-NOTIFICATIONS-ON lib/COLOR-SCHEME-NOTIFICATIONS-ON)
(def COLOR-SCHEME-NOTIFICATIONS-OFF lib/COLOR-SCHEME-NOTIFICATIONS-OFF)
(def CELL-SIZE-QUERY lib/CELL-SIZE-QUERY)
(def TERMINAL-PROGRESS-ACTIVE-SEQUENCE lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
(def TERMINAL-PROGRESS-CLEAR-SEQUENCE lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE)
(def TERMINAL-PROGRESS-KEEPALIVE-MS lib/TERMINAL-PROGRESS-KEEPALIVE-MS)
(def parse-negotiation-sequence lib/parse-negotiation-sequence)
(def split-negotiation-response lib/split-negotiation-response)
(def split-terminal-response lib/split-terminal-response)
(def negotiation-prefix? lib/negotiation-prefix?)
(def cell-size-response-prefix? lib/cell-size-response-prefix?)
(def osc-11-response-prefix? lib/osc-11-response-prefix?)
(def color-scheme-report-prefix? lib/color-scheme-report-prefix?)
(def parse-osc-11-background-response lib/parse-osc-11-background-response)
(def parse-terminal-color-scheme-report lib/parse-terminal-color-scheme-report)
(def parse-cell-size-response lib/parse-cell-size-response)
(def enable-modify-other-keys! lib/enable-modify-other-keys!)
(def disable-modify-other-keys! lib/disable-modify-other-keys!)

(ns kmet.tui.clipboard
  "System clipboard copy — the platform tools (pi: utils/clipboard.ts
   copyToClipboard — /copy), with the Win32 clipboard API over the host FFI
   as Windows' no-subprocess path (kmet.tui.clipboard-win). Termux,
   Wayland, X11, macOS, Windows; the calling layer may add an OSC52 fallback
   for environments with no tool."
  (:require [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.libs.host :as host]))

(def ^:private tool-timeout-ms 5000)

(defn- run-tool!
  "Run CMD with TEXT piped to stdin. Returns true when the tool exited 0
   before the timeout; false on non-zero exit, spawn failure, or timeout.
   babashka's :timeout opt is ignored by proc/process, so the deadline is a
   deref timeout; a hung tool is destroyed (tree) so it can't linger."
  [cmd text]
  (try
    (let [p (proc/process cmd {:out :string :err :discard :in text})
          result (deref p tool-timeout-ms :timed-out)]
      (if (= result :timed-out)
        (do (proc/destroy-tree p)
            false)
        (zero? (:exit result))))
    (catch Exception _ false)))

(defn- native-copy!
  "The no-subprocess Windows backend (Win32 CF_UNICODETEXT over the host
   FFI). True only when it copied; false on every other platform and when
   the backend cannot load or fails, so the caller can fall back to the
   platform tools."
  [text]
  (if-not (host/windows?)
    false
    (if-let [set-clipboard! (try (requiring-resolve 'kmet.tui.clipboard-win/set-clipboard!)
                                 (catch Exception _ nil))]
      (try
        (boolean (set-clipboard! text))
        (catch Exception _ false))
      false)))

(defn copy-text!
  "Copy TEXT to the system clipboard. Windows goes through the Win32 API
   over the host FFI first (kmet.tui.clipboard-win, no subprocess); the
   platform tools are used everywhere else: Termux (termux-clipboard-set),
   Wayland (wl-copy), X11 (xclip, then xsel), macOS (pbcopy). On Windows
   there is no subprocess fallback (clip.exe/PowerShell paths mangle
   non-ASCII text), so a failed native copy returns false. Returns true
   when something copied TEXT, false when nothing was available."
  [text]
  (let [os (str/lower-case (System/getProperty "os.name" ""))
        termux? (seq (System/getenv "TERMUX_VERSION"))
        candidates (cond-> []
                     termux? (conj ["termux-clipboard-set"])
                     (and (not termux?) (str/includes? os "linux"))
                     (into [["wl-copy"] ["xclip" "-selection" "clipboard"]
                            ["xsel" "--clipboard" "--input"]])
                     (and (not termux?) (str/includes? os "mac"))
                     (conj ["pbcopy"]))]
    (or (native-copy! text)
        (boolean (some #(run-tool! % text) candidates)))))

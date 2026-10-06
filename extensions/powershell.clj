(ns kmet.extensions.powershell
  "powershell — execute PowerShell commands on Windows (opt-in extension).

   Port of pi's powershell tool: the bash execution engine and renderers
   with a PowerShell shell — PowerShell 7 (pwsh.exe) preferred over Windows
   PowerShell (powershell.exe), pi's POWERSHELL_ARGS
   (\"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command\"), and
   pi's UTF-8 output prefix so non-ASCII output survives the console's
   default code page. The call line is the shared shell-call renderer with
   pi's `PS>` prompt; results render through the bash result renderer.

   Pi parity: the tool is Windows-only. On a host without PowerShell the
   tool still registers and every call reports pi's error instead of
   running — the failure pi's getPowerShellConfig raises per call.

   Enable by symlinking/copying into ~/.kmet/agent/extensions/ or
   .kmet/extensions/, then restart or /reload — see extensions/README.md."

  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.libs.process :as process]))

;; ─── pi constants ──────────────────────────────────────────────────────────

(def ^:private prompt "PS>")

(def ^:private powershell-args
  "pi: POWERSHELL_ARGS. The command is appended as the final argv element."
  ["-NoProfile" "-NonInteractive" "-ExecutionPolicy" "Bypass" "-Command"])

(def ^:private utf8-output-prefix
  "pi: UTF8_OUTPUT_PREFIX — first statement of every command so non-ASCII
   output is written as UTF-8 (Windows PowerShell 5.1 otherwise encodes with
   the console code page). No trailing newline: the executor joins the
   prefix and the command with one."
  "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}")

(def ^:private temp-file-prefix
  "pi: ShellToolConfig tempFilePrefix (\"pi-powershell\") — the spill file of a
   truncated output is named kmet-powershell-*.log instead of kmet-bash-*.log."
  "kmet-powershell-")

(def ^:private description
  "pi: createShellToolDefinition's description with shellName \"PowerShell\"
   and the standard 2000-line / 50KB truncation defaults."
  (str "Execute a PowerShell command in the current working directory. "
       "Returns stdout and stderr. Output is truncated to last 2000 lines "
       "or 50KB (whichever is hit first). If truncated, full output is "
       "saved to a temp file. Optionally provide a timeout in seconds."))

;; ─── PowerShell resolution ─────────────────────────────────────────────────

(defn- resolve-powershell
  "pi: getPowerShellConfig — PowerShell 7 before Windows PowerShell; nil on
   a non-Windows host or when neither executable is on PATH."
  []
  (when process/windows-os?
    (some-> (or (fs/which "pwsh.exe") (fs/which "powershell.exe")) str)))

(defn- unavailable-message
  "pi's per-call failure for a host that cannot run PowerShell (resolved once
   at load: restart or /reload to pick up a later install)."
  []
  (if process/windows-os?
    (str "No PowerShell executable found. Install PowerShell or add "
         "powershell.exe/pwsh.exe to PATH.")
    "The powershell tool is only available on Windows."))

;; ─── Rendering ─────────────────────────────────────────────────────────────

(defn- title
  "Quiet one-liner body: `PS> <cmd>` (the bash tool's `$ <cmd>` with pi's
   PowerShell prompt). Nil when the command is missing or empty."
  [args]
  (let [cmd (:command args)]
    (when (and (string? cmd) (not (str/blank? cmd)))
      (str prompt " " cmd))))

(defn- shell-tool
  "The tool-facing shape both branches share: pi's PowerShell prompt for the
   call line and the quiet title, the bash result body for output."
  [tool]
  (assoc tool
         :prompt-snippet "Execute PowerShell commands"
         :render-call (renderers/shell-call-renderer prompt)
         :render-result renderers/render-bash-result
         :title title))

(defn- make-tool
  "The powershell tool: the bash engine wired to the resolved PowerShell
   (explicit shell args + UTF-8 prefix), or — when PowerShell is unavailable
   — the same tool-facing shape whose execute reports pi's error."
  [api]
  (let [shell (resolve-powershell)
        tool (ext/create-bash-tool
              api
              (cond-> {:name "powershell"
                       :label "PowerShell"
                       :description description}
                shell (assoc :shell-path shell
                             :shell-args powershell-args
                             :command-prefix utf8-output-prefix
                             :temp-file-prefix temp-file-prefix)))]
    (cond-> (shell-tool tool)
      (nil? shell)
      (assoc :execute (fn [_args & _]
                        {:content (unavailable-message) :is-error true})))))

(defn init
  "Register the powershell tool (an opt-in Windows shell tool)."
  [api]
  (ext/register-tool! api (make-tool api)))

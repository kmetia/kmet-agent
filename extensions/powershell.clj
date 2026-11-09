(ns kmet.extensions.powershell
  "powershell — execute PowerShell commands on Windows (opt-in extension).

   Port of pi's powershell tool: the bash execution engine and renderers
   with a PowerShell shell — PowerShell 7 (pwsh.exe) preferred over Windows
   PowerShell (powershell.exe), pi's POWERSHELL_ARGS
   (\"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command\"), and
   pi's UTF-8 output prefix so non-ASCII output survives the console's
   default code page. The call line is the shared shell-call renderer with
   the `ps $` prompt; results render through the bash result renderer.

   The extension also contributes the `ext.powershell/run` eval verb:
   `(ext.powershell/run \"Get-ChildItem\" {:timeout 5})` returns the
   command's output and throws on failure, like `core/bash`; Escape and the
   eval deadline stop the command's process tree. The tool and the verb run
   the same execute — one implementation, two entry points.

   Pi parity: the tool is Windows-only. On a host without PowerShell the
   tool still registers and every call reports an unavailable-PowerShell
   error instead of running — the failure pi's getPowerShellConfig raises
   per call. The verb reports the same error, thrown as an exception.

   Enable by symlinking/copying into ~/.kmet/agent/extensions/ or
   .kmet/extensions/, then restart or /reload — see extensions/README.md."

  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.ui.tui.tool-renderers :as renderers]
            [kmet.libs.process :as process]))

;; ─── pi constants ──────────────────────────────────────────────────────────

;; kmet's shell-style prefix (pi renders `PS>`): `ps $ <cmd>` reads like the
;; bash tool's `$ <cmd>`.
(def ^:private prompt "ps $")

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
  "The per-call failure for a host that cannot run PowerShell (resolved once
   at load: restart or /reload to pick up a later install)."
  []
  (if process/windows-os?
    (str "No PowerShell executable found. Install PowerShell or add "
         "powershell.exe/pwsh.exe to PATH.")
    "PowerShell is only available on Windows."))

;; ─── Rendering ─────────────────────────────────────────────────────────────

(defn- title
  "Quiet one-liner body: `ps $ <cmd>` (the bash tool's `$ <cmd>` with a
   PowerShell marker). Nil when the command is missing or empty."
  [args]
  (let [cmd (:command args)]
    (when (and (string? cmd) (not (str/blank? cmd)))
      (str prompt " " cmd))))

;; ─── The shared execute ────────────────────────────────────────────────────

(defn- make-tool
  "Build the powershell tool; its :execute is the one implementation the eval
   verb shares. The bash engine is wired to the resolved PowerShell (explicit
   shell args + UTF-8 prefix), or reports the per-call unavailable error when
   none exists. The `ps $` call line and quiet title are the bash renderers'
   PowerShell spelling."
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
                             :temp-file-prefix temp-file-prefix)))
        execute (if shell
                  (fn [args & [on-update]]
                    ((:execute tool) args on-update))
                  (fn [_args & _]
                    {:content (unavailable-message) :is-error true}))]
    (assoc tool
           :execute execute
           :prompt-snippet "Execute PowerShell commands"
           :render-call (renderers/shell-call-renderer prompt)
           :render-result renderers/render-bash-result
           :title title)))

;; ─── The eval verb ─────────────────────────────────────────────────────────

(defn- eval-source
  "The eval source INIT registers: the tool's execute as
   `ext.powershell/run`."
  [execute]
  {:doc "PowerShell execution (Windows)."
   :ns {'ext.powershell
        {'run (with-meta
                (fn [command & [opts]]
                  (let [result (execute (merge opts {:command (str command)}) nil)]
                    (if (:is-error result)
                      (throw (ex-info (str (:content result))
                                      {:type :powershell/command-failed
                                       :result result}))
                      (:content result))))
                {:doc (str "Run COMMAND with the powershell tool's implementation; "
                           "OPTS may carry :timeout in seconds.")
                 :arglists '([command] [command opts])})}}})

(defn init
  "Register the powershell tool and its eval verb — one execute behind both
   (an opt-in Windows shell surface)."
  [api]
  (let [tool (make-tool api)]
    (ext/register-tool! api tool)
    (ext/register-eval-source! api :powershell
                               (fn [_] (eval-source (:execute tool))))))

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/powershell
   :apply (fn [_ctx api] (init api) nil)})

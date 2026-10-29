(ns kmet.app.tools.shell
  "The run's shell settings as a service value: `:shell-path` (pi: shellPath)
   and `:command-prefix` (pi: shellCommandPrefix).

   These were a process atom (`kmet.app.tools.bash-executor`'s `shell-options`,
   written by `kmet.app.config/load-config`), which is one set of settings for
   every agent in the process — exactly what a second session may not have. They
   are a per-agent service now: the agents factory provides `:shell` on each
   agent's own ctx, from that agent's config, which is where a preset's shell
   settings already arrive (docs/architecture.md § Service catalog).

   Per agent and not a root row, for the kernel's reason: a child may not shadow
   a service its ancestor provides, so a per-agent value has to be the only
   provider on its chain — the same reason `:permissions` mounts per agent.

   The value is an atom for the same reason `kmet.app.loop.stages`' map is: the
   settings are live (`/reload` replaces the config), and a mounted value that
   only changes by remounting would make a reloaded setting invisible. `refresh!`
   is the reload seam: it re-seats the atom from the engine's current config.

   The value is read at execution time, not captured at tool creation: the
   built-in registry entry is built at load time (see
   `kmet.app.tools.registry/base-tools`), so a bash tool that closed over these
   would wear the first composition's settings. Instead a run binds
   `kmet.app.tools.bash/*shell*` from the value on its own ctx (the loop and the
   TUI's `!` path do), and extension-built bash tools inherit it the same way an
   extension's tool inherits `*cancel-signal*`."
  (:require [kmet.app.config :as config]
            [kmet.srce.core :as srce]))

(defn value
  "The `:shell` service value CONFIG describes. Absent settings are nil, which
   is the platform default for both (pi: undefined shellPath/commandPrefix)."
  [config]
  {:shell-path (config/get-shell-path config)
   :command-prefix (config/get-shell-command-prefix config)})

(defn refresh!
  "Re-seat CTX's `:shell` value from CONFIG — the `/reload` path, beside
   `kmet.app.session.runtime/refresh-loop-stages!`. A ctx whose composition
   mounts no `:shell` is left alone; the next run binds the new value."
  [ctx config]
  (when-let [s (and ctx (srce/get ctx :shell))]
    (reset! s (value config))))

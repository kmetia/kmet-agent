(ns kmet.app.world
  "The process world a run stands on: the provider catalogs and the models.edn
   layer over them, the config, the themes, and the extension CLI flags.
   Extensions are composition rows and load when they mount, not here.

   The world is a **row** — `:world` in `:bundle/minimal` — and it is
   `:defer`: a generic boot leaves it out, because a diagnostic mount
   (`--dump-state`) and a test's boot must not read the machine's settings, and
   because the only caller that can mount it is the entry. It has the
   invocation, and a mode must mount *after* the load. So
   `kmet.app.core/run-mode` holds the row back, mounts it with
   `{:invocation opts}` as its config, and reads the `:world` service the row
   provides (`{:config <the loaded config>}`) to hand the mode the world the
   composition loaded.

   The order inside the load is load-bearing: the provider catalogs and
   models.edn come first, so the model patterns the run resolves afterwards see
   both the built-ins and the user's providers; the config follows (it publishes
   the composition as the process root); the themes come next (a composition's
   `:ext/…` rows loaded their extensions earlier, when the composition mounted);
   and the CLI flags are last, because their registrations are the ones the
   parse collected.

   The row mounts at the **root**, never on an agent's ctx: the world is the
   process's, so a preset that names a bundle carrying this row is refused
   instead of loading a second world inside one agent."
  (:require [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.models :as models]
            [kmet.app.root :as root]
            [kmet.libs.theme :as theme]
            [kmet.srce.core :as srce]))

(defn load!
  "Load the process world and return the config.

   With CTX, it is published as the process root so a front end resolves the
   tree the run mounted. Extensions are composition rows and already mounted
   by the time the world loads; nothing here loads one. Without CTX the
   host's bound services boot the root on their first read."
  ([opts] (load! opts nil))
  ([opts ctx]
   ;; Provider/model registry — loads the committed catalogs (pi registers its
   ;; generated providers at startup), then the models.edn user config layer
   ;; (custom providers + overrides; errors surface as warnings). The agent
   ;; dir is the host's: kmet.ai takes it as an argument.
   (let [agent-dir (cfg/get-agent-dir)]
     (models/load-catalogs! agent-dir)
     (image-models/load-image-catalogs! agent-dir)
     (models/load-models-config! agent-dir))
   (when-let [err (models/get-model-config-error)]
     (binding [*out* *err*]
       (println "Warning: models.edn:" err)))
   ;; Config before the themes and flags, and before the model resolution
   ;; `run-mode` does afterwards.
   (let [config (cfg/init!)]
     (when ctx (root/publish! ctx))
     ;; Extensions are plugins: the composition's `:ext/…` rows loaded them
     ;; when they mounted (kmet.app.extensions.rows). Nothing here loads an
     ;; extension from settings; `kmet config` composes rows into the patch
     ;; layers instead.
     (theme/load-theme-paths! (packages/resolve-theme-paths))
     ;; Extension CLI flags: the collected --flags become readable via
     ;; extensions/get-flag (pi: registerFlag + getFlag).
     (extensions/set-cli-flags! (:ext-flags opts))
     config)))

;; ── the row ───────────────────────────────────────────────────────────────

(defn- root-context
  "The root ctx CTX is mounted under, or a throw when it is not: the world is
   the process's, so it mounts at the root — a run's composition, a test's boot
   — and never under an agent, which is what a preset naming a bundle that
   carries this row would otherwise do."
  [ctx]
  (let [under (:parent ctx)]
    (if (nil? (:parent under))
      under
      (throw (ex-info (str "the :world row mounts at the root, not inside an"
                           " agent's ctx — the process world is the"
                           " composition's")
                      {:type :kmet.app/world-not-root
                       :mount (:name ctx)
                       :under (:name under)})))))

(defn- serve
  "The `:world` row's apply: load the process world into the root CTX is
   mounted under, and provide what it loaded as `:world`.

   The row's `:config` carries the invocation the entry parsed
   (`{:invocation opts}`) — the extension `--flags` the load installs come from
   there. A world row a composition mounts by hand has no invocation, and loads
   with none."
  [ctx config]
  (let [root-ctx (root-context ctx)]
    (srce/provide! ctx :world
                   {:config (load! (:invocation config) root-ctx)}
                   {:in root-ctx})
    ;; the provide! registers its own effect; apply returning it would add a
    ;; second, no-op disposer to the fiber
    nil))

(srce/defplugin plugin
  {:name :world
   :provide #{:world}
   :apply serve})

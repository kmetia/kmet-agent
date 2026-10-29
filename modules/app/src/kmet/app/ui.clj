(ns kmet.app.ui
  "The `:ui` capability service: what a UI provider must answer, and the ABI
   port a run binds from it.

   A provider is a value, not a widget set (docs/architecture.md § Pluggable UI).
   `:kind`, `:render-tree`, `:request!` and `:capabilities` are required;
   whatever else a provider offers (`:widget-set`, `:notify`, `:input!`, a
   toolkit's own hooks) is optional and must be named in `:capabilities`, so a
   consumer can ask for what the provider it has can do instead of probing.
   `check` is where a provider missing a required key fails — at the mount,
   naming the provider — rather than at the first extension call with `nil is
   not a function`; `:kmet.app/ui-provider` carries the key names for a caller
   that wants to say more.

   `:request!` takes the provider's ctx and the request; the extension host's
   port takes the request alone, so `port` closes over the ctx the provider
   mounted in. The port is the ABI map a mode binds
   (`kmet.app.front-end/install!`): the headless answers every front end must
   give, plus the provider's dialog answer and, when declared, its notify. What
   a provider adds beyond that (rpc's wire records, the TUI's render hooks)
   wraps the port; the service is the base, not the ceiling."
  (:require [clojure.string :as str]
            [kmet.app.extensions.core :as extensions]
            [kmet.srce.core :as srce]))

(def required-keys
  "What every UI provider answers, declared once and checked at the mount."
  [:kind :render-tree :request! :capabilities])

(defn check
  "PROVIDER validated as a UI provider: PROVIDER itself when it answers every
   `required-keys`, else an ex-info naming PROVIDER-NAME and the keys it lacks.
   Idempotent, so a caller may check and pass the value on."
  [provider-name provider]
  (if-let [missing (seq (remove #(some? (get provider %)) required-keys))]
    (throw (ex-info (str "UI provider " provider-name " is missing "
                         (str/join ", " (map name missing)))
                    {:type :kmet.app/ui-provider
                     :provider provider-name
                     :missing (vec missing)}))
    provider))

(defn port
  "The ABI capability map a run binds for PROVIDER in CTX: the answers every
   front end must give (the getters, `:custom`, `:set-theme`, terminal input),
   the provider's `:request!` as `:request`, and its `:notify` when declared.
   The rest of the contract stays on the provider — the port is the extension
   host's surface, not the whole of `:ui`."
  [ctx provider]
  (let [request! (:request! provider)]
    (cond-> (assoc (extensions/headless-ui-port)
                   :request (fn [request] (request! ctx request)))
      (:notify provider) (assoc :notify (:notify provider)))))

(defn provide!
  "Mount PROVIDER (named PROVIDER-NAME) at `:ui` in CTX for the mount site's
   siblings — the scope `(:parent ctx)` a row provides into, as the
   `:systemPrompt` row does. The provided value is the checked provider plus
   the `:port` a mode binds (`(:port (srce/get ctx :ui))`).

   Returns nil: the provide effect owns the teardown, while a plugin's non-nil
   apply result would be registered as a second one and the store would be
   unwound (and its service event emitted) twice."
  [ctx provider-name provider]
  (let [provider (check provider-name provider)]
    (srce/provide! ctx :ui (assoc provider :port (port ctx provider))
                   {:in (:parent ctx)})
    nil))

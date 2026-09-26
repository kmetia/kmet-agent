(ns kmet.ai.hooks
  "Injectable provider-event hooks (pi: context / before_provider_request /
   before_provider_headers / after_provider_response events).

   kmet.ai must not depend on kmet.app (the event bus lives there), so the
   extension bridge (kmet.app.extensions) installs these single-fn slots —
   defaults are no-ops. The slots live in their own namespace because both
   kmet.ai.api.shared (payload/context application) and kmet.ai.http
   (headers/response application, where the HTTP call happens) must reach
   them without a require cycle.

   All hooks are single-fn slots built on the slot helpers below (also used
   by kmet.ai.auth for its config-key/oauth sources); the extension bridge
   chains the bus handlers inside one fn (last non-nil handler result wins —
   pi chains handler results, each handler seeing the previous replacement).")

;; ─── Slot helpers ─────────────────────────────────────────────────────────

(defn make-slot
  "Create an empty hook slot (atom holding nil)."
  []
  (atom nil))

(defn set-slot!
  "Install F as the slot's handler. Returns nil."
  [slot f]
  (reset! slot f)
  nil)

(defn apply-slot
  "Apply SLOT to args, returning (apply f args) when installed, or nil."
  [slot & args]
  (when-let [f @slot]
    (apply f args)))

(defonce ^:private context-hook (make-slot))
(defonce ^:private before-provider-request-hook (make-slot))
(defonce ^:private before-provider-headers-hook (make-slot))
(defonce ^:private after-provider-response-hook (make-slot))

(defn set-context-hook!
  "Install the context-event hook (pi: emitContext — fired before each LLM
   call; the returned messages replace the outgoing messages, nil keeps
   them)."
  [f]
  (set-slot! context-hook f))

(defn apply-context-hook
  "Apply the context hook to MESSAGES (nil result keeps them unchanged)."
  [messages]
  (or (apply-slot context-hook messages) messages))

(defn set-before-provider-request-hook!
  "Install the before-provider-request hook (pi: emitBeforeProviderRequest
   — fired with the assembled request payload before the HTTP call; the
   returned payload replaces it, nil keeps it)."
  [f]
  (set-slot! before-provider-request-hook f))

(defn apply-before-provider-request-hook
  "Apply the before-provider-request hook to PAYLOAD."
  [payload]
  (or (apply-slot before-provider-request-hook payload) payload))

(defn set-before-provider-headers-hook!
  "Install the before-provider-headers hook (pi: emitBeforeProviderHeaders
   — fired with the final request headers before the HTTP call; handlers
   return the replacement map, nil values delete a header)."
  [f]
  (set-slot! before-provider-headers-hook f))

(defn apply-before-provider-headers-hook
  "Apply the before-provider-headers hook to HEADERS (returns the possibly
   replaced map)."
  [headers]
  (or (apply-slot before-provider-headers-hook headers) headers))

(defn set-after-provider-response-hook!
  "Install the after-provider-response hook (pi: emitAfterProviderResponse
   — fired with {:status n :headers {...}} after the response is received,
   before its body is consumed)."
  [f]
  (set-slot! after-provider-response-hook f))

(defn apply-after-provider-response-hook
  "Apply the after-provider-response hook (fire-and-forget)."
  [status headers]
  (apply-slot after-provider-response-hook {:status status :headers headers})
  nil)

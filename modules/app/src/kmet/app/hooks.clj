(ns kmet.app.hooks
  "The around-hook primitive: one chain per point, one dispatch.

   An around hook is `(fn [op next])`:

   - do not call NEXT — the hook's return value settles the call and nothing
     below it runs (a veto, a cached answer, a stub);
   - `(next op)` — run the rest of the chain over a rebuilt op;
   - transform what NEXT returned — rewrite the result, or record it.

   So one hook does the work of a before hook (rebuild the op), an after hook
   (transform the return) and both at once (timing, retry, state that spans the
   call), and a hook can settle a call *successfully* without executing it,
   which a before/after pair cannot express.

   Hooks compose in registration order with the first registered outermost: it
   sees the op before any other hook and the result after all of them.

   A hook that throws fails the call closed — a hook is a guard, so a crash
   must not let the call through. The call is settled as an error naming the
   hook's message, whether it threw before descending into NEXT or after the
   body ran. A throw from NEXT itself is the operation's own error and
   propagates untouched: the body's caller already turns a failing operation
   into a result, and a hook failure must stay distinguishable from it.")

(defn- hook-owner
  "The extension a hook belongs to, for an error the model reads: the owner
   path's last element, so an extension named tree-sitter renders as
   tree-sitter. A hook registered outside an extension carries no owner."
  [hook]
  (let [owner (:owner (meta hook))]
    (cond
      (nil? owner) nil
      (sequential? owner) (last owner)
      :else owner)))

(defn- hook-error
  "The fail-closed result of a hook that threw, as the model sees it — naming
   the extension that registered the hook when it has one, so a hook failure
   is actionable in a process with several extensions loaded."
  [hook t]
  {:content (str "tool hook error"
                 (when-some [owner (hook-owner hook)] (str " in " owner))
                 ": " (ex-message t))
   :is-error true})

(def ^:private not-run
  "The sentinel RAN starts as, so a nil result from NEXT is told apart from a
   hook that never ran the call at all."
  ::not-run)

(defn- run-hook
  "Run one HOOK over OP with NEXT as its continuation.

   RAN and FROM-NEXT are what make the fail-closed policy positional: a throw
   with FROM-NEXT set came out of the body and is rethrown; any other throw is
   the hook's own and settles the call as an error. A hook that descends and
   returns nil passes NEXT's result through, so a hook that only observes the
   call need not return it."
  [hook op next]
  (let [ran (volatile! not-run)
        from-next (volatile! false)
        inner (fn [op]
                (let [r (try
                          (next op)
                          (catch Throwable t
                            (vreset! from-next true)
                            (throw t)))]
                  (vreset! ran r)
                  r))
        result (try
                 (hook op inner)
                 (catch Throwable t
                   (when @from-next (throw t))
                   (hook-error hook t)))]
    (cond
      (some? result) result
      (not (identical? not-run @ran)) @ran
      ;; a hook that settles a call is responsible for the result: a nil one
      ;; would erase the call from the transcript
      :else (hook-error hook (ex-info "hook settled the call without a result" {})))))

(defn around
  "Compose HOOKS into one chain, `(fn [op next] → result)`.

   Registration order, first registered outermost (see the namespace
   docstring): the innermost hook's NEXT is the point's own continuation — the
   operation itself, or the operation behind a gate the point installs there."
  [hooks]
  (fn [op next]
    ((reduce (fn [inner hook]
               (fn [op] (run-hook hook op inner)))
             next
             (reverse hooks))
     op)))

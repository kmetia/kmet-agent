(ns kmet.app.session.backend
  "The EDNL session backend — `kmet.app.sessions`' `:sessionPersistence`
   contract over kmet1's store.

   A projection, not a copy: every op delegates to the namespace that already
   answers it (`store/create-session`, `store/load-session`, `store/append-entry`,
   `listing/list-sessions`, `listing/delete-session!`), so the file format,
   the torn-tail repair, the lazy first write and the branch chain stay where
   they are implemented. The handle is `kmet.app.session.store/Session`; the
   app never looks inside it.

   The file moved into `app` with the session layer it names — the module
   changed, the namespace did not — so the row that
   mounts it and the service that reads it are now both here.

   The row is `:sessionPersistence`: it provides a backend into the mount
   site's scope, where the `:sessions` row next to it injects it. A second
   backend is a second row providing the same key, never a change to `:sessions`
   (`docs/architecture.md` § Service catalog)."
  (:require [kmet.app.session.listing :as listing]
            [kmet.app.session.store :as store]
            [kmet.srce.core :as srce]))

(defn ednl
  "A fresh EDNL backend value. Stateless: the sessions it hands out carry the
   file, the entries and the mutation lock, exactly as kmet1's store built them."
  []
  {:create! (fn [{:keys [dir cwd parent-session]}]
              (store/create-session
               dir
               (cond-> {}
                 cwd (assoc :cwd cwd)
                 parent-session (assoc :parent-session parent-session))))
   :load (fn [path] (store/load-session path))
   :paths (fn [dir] (listing/list-sessions dir))
   :append! (fn [session entry] (store/append-entry session entry))
   :entries (fn [session] @(:entries session))
   :delete! (fn [session] (listing/delete-session! session))
   :header (fn [session] (:header session))})

(defn- serve
  "The `:sessionPersistence` row: provide the EDNL backend into the mount site's
   scope — the app scope — so a sibling `:sessions` row sees it while the entry
   stays this fiber's effect (the `:tools`/`:events`/`:sessions` rule)."
  [ctx _config]
  (srce/provide! ctx :sessionPersistence (ednl) {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :sessionPersistence
   :provide #{:sessionPersistence}
   :apply serve})

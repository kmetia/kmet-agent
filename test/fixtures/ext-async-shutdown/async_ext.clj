(ns async-ext
  "Sample extension whose shutdown is asynchronous: it hands back a promise
   rather than blocking the caller, which is what `unload-extension!` defers its
   code release on.

   `(:config api)` picks the mode: `{:mode :hold}` hands back a promise nobody
   delivers, so a test can observe the deferred release without racing it; the
   default delivers from a spawned thread."
  (:require [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]))

(defn init [api]
  (ext/register-command! api
                         {:name "async-ext"
                          :description "Test extension with an asynchronous shutdown"
                          :handler (fn [_cs args] (str "async-ext:" args))}))

(defn shutdown [api]
  (if (= :hold (:mode (:config api)))
    (promise)
    (let [done (promise)]
      ;; the work a real extension would await: closing a connection, stopping a
      ;; subprocess. `spawn` because an extension context has no `future`.
      (concurrent/spawn (fn [] (deliver done :stopped)))
      done)))

(def plugin
  {:name :ext/async-ext
   :apply (fn [_ctx api] (init api) nil)})

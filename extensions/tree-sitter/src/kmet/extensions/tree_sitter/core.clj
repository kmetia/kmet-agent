;; tree-sitter extension — structural code intelligence for kmet.
;;
;; Registers:
;;   list_symbols, find_definition, get_symbol_body,
;;   find_callers, find_callees — parse-on-demand tools over the
;;   cached tree-sitter CLI + WASM grammars (auto-downloaded).
;;   write-block / edit-warn syntax-validation hooks over the same
;;   grammars (clojure-family files defer to the clojure extension).

(ns kmet.extensions.tree-sitter.core
  (:require [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]
            [kmet.extensions.tree-sitter.hooks :as hooks]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.tools :as tools]))

(defn init [api]
  ;; the host agent dir (KMET_CODING_AGENT_DIR-aware) holds the CLI,
  ;; grammar and config caches
  (when-let [dir (ext/get-agent-dir api)]
    (paths/set-default-root! (str (fs/path dir "tree-sitter"))))
  ;; bundled EDN resources resolve via the shadowed io/resource (dir, src
  ;; symlink and unexpanded jar installs alike)
  ;; clojure-extension presence is checked lazily at hook time
  (dispatch/set-api! api)
  (doseq [tool (tools/tool-defs)]
    (ext/register-tool! api tool))
  (ext/on-tool api hooks/on-tool))

;; unload unregisters everything this extension added (tools + hooks)
;; automatically — nothing to tear down by hand
(defn shutdown [_api] nil)

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/tree-sitter
   :apply (fn [_ctx api] (init api) nil)})

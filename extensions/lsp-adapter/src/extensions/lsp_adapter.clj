(ns extensions.lsp-adapter
  "lsp-adapter entry (plan §13, Rev 2): one lazy `lsp` tool over stdio
   language servers, a `/lsp` command, footer status, idle reaper.

   Simplified contract: config is a single optional .kmet/lsp.edn (project
   only, entries verbatim EDN — see README); lifecycle is lazy-only; enable
   /disable is editing that file plus `/lsp refresh`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [extensions.lsp-adapter.detect :as detect]
            [extensions.lsp-adapter.panel :as panel]
            [extensions.lsp-adapter.render :as render]
            [extensions.lsp-adapter.runtime :as runtime]
            [extensions.lsp-adapter.tools :as tools]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.edn-store :as edn-store]
            [kmet.tui.theme :as theme]))

(def ^:private state-atom (atom nil))

(def ^:private home-str (str (fs/home)))

(def ^:private spawn concurrent/spawn)

(defn- project-config-path []
  (str (fs/path (str (fs/cwd)) ".kmet" "lsp.edn")))

(defn- load-config []
  ;; Lenient: missing/malformed/type-broken file → {} — startup never
  ;; crashes on config. Entries are used verbatim (detect normalizes).
  (or (edn-store/read-edn-map (project-config-path)) {}))

(defn- short-root
  "ROOT with $HOME folded to ~."
  [root]
  (let [r (str root)]
    (if (str/starts-with? r home-str)
      (str "~" (subs r (count home-str)))
      r)))

;; ─── Footer status ────────────────────────────────────────────────────────

(defn- status-text
  "Compact footer line, mcp-adapter style: \"LSP <connected>/<total>\".
   total is the effective fleet — user-configured servers plus the builtin
   registry (counting only :servers entries hid every builtin connection
   behind an empty footer when no .kmet/lsp.edn existed). nil clears the
   slot unless at least one server is CONNECTED - an idle fleet is not
   worth footer space."
  [st]
  (let [;; the fleet is configured ∪ builtin registry — counting only
         ;; :servers entries hid every builtin connection behind an
         ;; empty footer when no .kmet/lsp.edn existed
        ids (into #{} (map :id) (detect/effective-servers
                                 (runtime/configured-servers st)))
        connected (into #{} (map :name) (runtime/all-conns st))
        n-connected (count (filter ids connected))]
    (when (pos? n-connected)
      (str "LSP " n-connected "/" (count ids)))))

(defn- update-status! [st]
  (when-let [api (:api st)]
    (let [broken? (seq @(:broken st))
          color (if broken? :error :accent)]
      (ext/ui-set-status api "lsp"
                         (some->> (status-text st)
                                  (theme/fg (theme/get-current-theme) color))))))

;; ─── The lsp tool ─────────────────────────────────────────────────────────

(def ^:private tool-description
  "Interact with Language Server Protocol servers for code intelligence.
Supported operations (pass as `operation`): definition, references, hover,
documentSymbol, workspaceSymbol (needs `query`), implementation,
prepareCallHierarchy, incomingCalls, outgoingCalls, diagnostics. All need
`filePath`; position-based ones also need `line` and `character`
(1-based, as shown in editors). Empty results report (no results).
Servers spawn lazily on first touch of a claimed language and may take
seconds while indexing; a failed install stays failed until /lsp restart.

Typical flow: grep finds candidates → definition jumps → references checks
blast radius before an edit → after editing, diagnostics reports current
errors.")

(defn- register-tool! [st]
  (ext/register-tool! (:api st)
                      {:name "lsp"
                       :label "LSP"
                       :description tool-description
                       :prompt-snippet
                       "Semantic code intelligence: definition, references, hover, symbols, call hierarchy, diagnostics"
                       :prompt-guidelines
                       ["Use the lsp tool for semantic code intelligence — definition, references, hover, documentSymbol, workspaceSymbol, implementation, call hierarchy, diagnostics."
                        "Prefer lsp/definition over grep to jump to a symbol's definition; lsp/references to check blast radius before an edit; lsp/diagnostics after editing to catch current errors."
                        "lsp operations need filePath; position-based ones (definition, references, hover, implementation, prepareCallHierarchy, incomingCalls, outgoingCalls) also need 1-based line and character."
                        "workspaceSymbol needs a query. Empty results report (no results)."
                        "Servers spawn lazily on first touch of a claimed language and may take seconds while indexing; a failed install stays failed until /lsp restart."]
                       :parameters
                       {:type "object"
                        :properties
                        {"operation"
                         {:type "string"
                          :enum ["definition" "references" "hover"
                                 "documentSymbol" "workspaceSymbol"
                                 "implementation" "prepareCallHierarchy"
                                 "incomingCalls" "outgoingCalls"
                                 "diagnostics"]
                          :description "Which LSP capability to invoke."}
                         "filePath"
                         {:type "string"
                          :description "File to query (absolute preferred; resolved against cwd)."}
                         "line" {:type "number"
                                 :description "1-based line, as shown in editors."}
                         "character" {:type "number"
                                      :description "1-based character."}
                         "query" {:type "string"
                                  :description "workspaceSymbol search text."}}
                        :required ["operation" "filePath"]}
                       :contextual? true
                       ;; Custom transcript renderers (pi: renderCall/
                       ;; renderResult) — structured sections from execute's
                       ;; :details, plain preview fallback without them.
                       :render-call render/render-lsp-call
                       :render-result render/render-lsp-result
                       :title render/title-lsp
                       :execute (fn [args _on-update signal _ctx]
           ;; Tool contract (kmet.app.tools.registry): execute returns
           ;; {:content ...}; :details rides along for the renderer. A bare
           ;; string reaches the after-tool-call chain, whose
           ;; (contains? result :is-error) throws on strings.
                                  (let [res (tools/execute st signal args)]
                                    (cond-> {:content (:content res)}
                                      (:details res) (assoc :details (:details res)))))}))

;; ─── /lsp command ─────────────────────────────────────────────────────────

(def ^:private subcommands ["status" "list" "restart" "refresh"])

(defn- split-args
  "\"restart clojure-lsp\" → [\"restart\" \"clojure-lsp\"]"
  [args]
  (let [parts (str/split (str/trim (or args "")) #"\s+" 2)]
    [(first parts) (str/trim (get parts 1 ""))]))

(defn- server-names [st]
  (mapv :id (detect/effective-servers (runtime/configured-servers st))))

(defn- completions [st arg-prefix]
  (let [prefix (str/trim (str arg-prefix))]
    (if-not (str/includes? prefix " ")
      (mapv (fn [s] {:value s :label s})
            (filter #(str/starts-with? % prefix) subcommands))
      (let [[sub rest] (str/split prefix #"\s+" 2)]
        (when (= "restart" sub)
          (mapv (fn [s] {:value (str sub " " s) :label s})
                (filter #(str/starts-with? % (or rest ""))
                        (server-names st))))))))

(defn- report
  "Multi-line command output: chat-info in the TUI, println headless."
  [ctx title text]
  (if (:has-ui ctx)
    (ext/ui-chat-info (:api @state-atom) title text)
    (println text)))

(defn- status-report [st]
  (let [conns (sort-by :name (runtime/all-conns st))
        broken (sort-by key @(:broken st))
        lines (concat
               (for [conn conns]
                 (format "%s @ %s (%d open docs)"
                         (:name conn) (short-root (:root conn))
                         (count (runtime/open-docs conn))))
               (for [[name {:keys [reason]}] broken]
                 (str name ": broken —" reason)))]
    (if (seq lines)
      (str/join "\n" lines)
      "no language servers connected")))

(defn- open-panel!
  "The interactive /lsp panel (TUI); headless callers fall back to text."
  [st ctx]
  (if (:has-ui ctx)
    (ext/ui-custom
     (:api @state-atom)
     (fn [_tui _theme _kb close]
       (panel/make-panel st close
                         {:restart-fn (fn [name]
                                        (runtime/clear-broken! st name)
                                        (runtime/disconnect-server! st name))
                          :refresh-fn (fn []
                                        (runtime/set-config! st (load-config))
                                        (runtime/clear-all-broken! st))}))
     {:overlay true
      :overlay-options {:anchor :center :width 64}})
    (report ctx "LSP" (status-report st))))

(defn- list-report [st]
  (str/join "\n"
            (for [{:keys [id extensions root-markers rootless]}
                  (detect/effective-servers (runtime/configured-servers st))]
              (format "%s — %s | roots: %s"
                      id
                      (str/join " " (sort extensions))
                      (if rootless "(file dir)" (str/join ", " root-markers))))))

(defn- handle-lsp-command [st args ctx]
  (let [[sub arg] (split-args args)
        sub (if (str/blank? sub) "status" sub)]
    (case sub
      "status" (open-panel! st ctx)
      "list" (report ctx "LSP servers" (list-report st))
      "restart"
      (if (str/blank? arg)
        (report ctx "LSP" "usage: /lsp restart <server>")
        (do (runtime/clear-broken! st arg)
            (runtime/disconnect-server! st arg)
            (update-status! st)
            (report ctx "LSP"
                    (str "Restarted \"" arg "\" — reconnects on next use."))))
      "refresh"
      (do (runtime/set-config! st (load-config))
          (runtime/clear-all-broken! st)
          (doseq [conn (runtime/all-conns st)
                  :when (not (contains? (set (server-names st))
                                        (:name conn)))]
            (runtime/disconnect-server! st (:name conn)))
          (update-status! st)
          (report ctx "LSP" ".kmet/lsp.edn reloaded."))
      (report ctx "LSP"
              (str "unknown /lsp subcommand: " sub
                   " (status list restart refresh)")))))

;; ─── Reaper ───────────────────────────────────────────────────────────────

(defn- start-reaper! [st]
  (spawn
   (fn []
     (loop []
       (Thread/sleep 30000)
       (when-not @(:reaper-stop st)
         (try (runtime/reap-tick! st) (catch Exception _ nil))
         (update-status! st))
       (recur)))))

;; ─── Init / shutdown ──────────────────────────────────────────────────────

(defn init [api]
  (let [st (runtime/new-state api (load-config))]
    (reset! state-atom st)
    ;; footer refreshes on every lazy connect/disconnect/broken change -
    ;; runtime can't call the entry, so it invokes this injected hook
    (runtime/set-on-change! st #(update-status! st))
    (register-tool! st)
    (ext/register-command! api
                           {:name "lsp"
                            :description
                            "Language server status, list, restart, refresh"
                            :get-argument-completions
                            (fn [pfx] (completions st pfx))
                            :handler (fn [ctx args]
                                       (handle-lsp-command st args ctx))})
    (start-reaper! st)
    (ext/on-event api :session-shutdown
                  (fn [ev _ctx]
                    (when (= :quit (:reason ev))
                      (runtime/shutdown-all! st)
                      (update-status! st))))
    st))

(defn shutdown [_api]
  (when-let [st @state-atom]
    (reset! (:reaper-stop st) true)
    (runtime/shutdown-all! st))
  nil)

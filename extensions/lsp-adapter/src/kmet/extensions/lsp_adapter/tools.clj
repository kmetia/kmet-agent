(ns kmet.extensions.lsp-adapter.tools
  "The agent-facing `lsp` tool (plan §11, Rev 2): operation dispatch with
   camelCase aliases, the single 1-based→0-based coordinate conversion,
   token-economical result shaping, and diagnostics rendering. Empty
   results report \"(no results)\"."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.jsonrpc :as jrpc]
            [kmet.extensions.lsp-adapter.lsp :as lsp]
            [kmet.extensions.lsp-adapter.runtime :as runtime]))

;; ─── Operation table ──────────────────────────────────────────────────────

(def ^:private operations
  {"definition"           {:method "textDocument/definition"}
   "references"           {:method "textDocument/references"
                           :params (fn [td pos]
                                     {:textDocument td :position pos
                                      :context {:includeDeclaration true}})}
   "hover"                {:method "textDocument/hover"}
   "documentSymbol"       {:method "textDocument/documentSymbol"
                           :file-only true}
   "workspaceSymbol"      {:method "workspace/symbol"
                           :symbol-query true}
   "implementation"       {:method "textDocument/implementation"}
   "prepareCallHierarchy" {:method "textDocument/prepareCallHierarchy"}
   "incomingCalls"        {:hierarchy "callHierarchy/incomingCalls"}
   "outgoingCalls"        {:hierarchy "callHierarchy/outgoingCalls"}
   "diagnostics"          {:diagnostics true}})

(def ^:private aliases
  {"goToDefinition" "definition"
   "findReferences" "references"
   "goToImplementation" "implementation"})

(def ^:private usage
  (str "usage: lsp({operation, filePath[, line, character, query]}) — "
       "operations: definition references hover documentSymbol "
       "workspaceSymbol implementation prepareCallHierarchy incomingCalls "
       "outgoingCalls diagnostics; line/character are 1-based"))

(defn- canonical-op
  "Canonical operation KEYWORD (:definition …) or nil."
  [op]
  ;; NB: bind the NAME, not the matched spec map
  (let [name (or (and (contains? operations op) op)
                 (get aliases op))]
    (when name (keyword name))))

;; ─── Shaping (token economy) ─────────────────────────────────────────────

(def ^:private max-lines 50)
(def ^:private max-hover-chars 2000)

(defn- rel-path [path cwd]
  (let [p (str path)]
    (if (and cwd (str/starts-with? p (str cwd "/")))
      (subs p (inc (count (str cwd))))
      p)))

(defn- kind-name [k] (or ({1 "file" 2 "module" 3 "namespace" 5 "class"
                           6 "method" 7 "property" 8 "field" 9 "constructor"
                           10 "enum" 11 "interface" 12 "fn" 13 "var"
                           14 "const" 20 "struct" 25 "type-param"} k)
                         (str "kind:" k)))

(defn- shape-locations
  "Array-or-single Location → deduped `path:line:col` display lines plus the
   structured items behind them, capped. Items carry absolute paths — the
   renderer relativizes/hyperlinks against its own cwd."
  [res]
  (let [locs (cond (map? res) [res]
                   (vector? res) res
                   :else [])
        deduped (distinct
                 (keep (fn [{:keys [uri range]}]
                         (when (and uri range)
                           {:path (lsp/uri->path uri)
                            :line (inc (get-in range [:start :line]))
                            :col (inc (get-in range [:start :character]))}))
                       locs))
        lines (map (fn [{:keys [path line col]}]
                     (format "%s:%s:%s" path line col))
                   deduped)
        shown (take max-lines lines)
        more (- (count lines) max-lines)]
    (when (seq lines)
      {:lines (concat shown (when (pos? more)
                              [(str "… and " more " more")]))
       :items deduped
       :more more})))

(defn- shape-hover
  "Hover contents → single-element {:lines :items} (one :text item), or nil
   when the server sent nothing usable."
  [res]
  (let [contents (:contents res)]
    (when contents
      (let [text (cond
                   (string? contents) contents
                   (map? contents) (or (:value contents) "")
                   (vector? contents) (str/join "\n"
                                                (map (fn [c]
                                                       (or (:value c)
                                                           (if (string? c) c "")))
                                                     contents))
                   :else (str contents))]
        (when-not (str/blank? text)
          {:lines [(if (> (count text) max-hover-chars)
                     (str (subs text 0 max-hover-chars) "…")
                     text)]
           :items [{:text (if (> (count text) max-hover-chars)
                            (str (subs text 0 max-hover-chars) "…")
                            text)}]})))))

(defn- symbol->items
  "One symbol tree node → its {:depth :name :kind} item followed by the
   flattened items of its children."
  [{:keys [name kind children]} depth]
  (cons {:depth depth
         :name name
         :kind (kind-name kind)}
        (mapcat #(symbol->items % (inc depth)) children)))

(defn- shape-symbols
  "Symbol tree → flattened {:depth :name :kind} items plus the matching
   display lines (derived from the items so the two never drift)."
  [res]
  (let [items (mapcat #(symbol->items % 0) (or res []))
        lines (map (fn [{:keys [depth name kind]}]
                     (str (apply str (repeat depth "  "))
                          (or name "?") " " kind))
                   items)]
    (when (seq lines)
      {:lines (take max-lines lines)
       :items (take max-lines items)
       :more (max 0 (- (count lines) max-lines))})))

(defn- shape-hierarchy
  "Incoming/outgoing call results → caller/callee lines with site counts,
   deduped (servers repeat an item once per resolution context), plus
   {:name :kind :path :line :sites} items."
  [res]
  (let [items (vec
               (for [{:keys [from fromRanges]} (or res [])
                     :when (map? from)
                     :let [entry {:name (:name from)
                                  :kind (kind-name (:kind from))
                                  :path (some-> from :uri lsp/uri->path)
                                  :line (some-> from :range :start :line inc)
                                  :sites (count fromRanges)}]]
                 entry))
        deduped (distinct items)
        lines (for [{:keys [name kind path line sites]} deduped]
                (str (format "%s %s @ %s:%s" name kind path line)
                     (when (pos? sites)
                       (str " (" sites " site" (when (> sites 1) "s") ")"))))]
    (when (seq lines)
      {:lines lines :items deduped :more 0})))

(defn- shape-result
  "Per-operation shaping of one conn's raw result → {:lines :items :more};
   nil when empty. OP is the canonical keyword (:definition …)."
  [op res]
  (case op
    :definition (shape-locations res)
    :references (shape-locations res)
    :implementation (shape-locations res)
    :hover (shape-hover res)
    :documentSymbol (shape-symbols res)
    :workspaceSymbol (shape-symbols res)
    :prepareCallHierarchy (let [items (mapv (fn [item]
                                              {:name (:name item)
                                               :kind (kind-name (:kind item))
                                               :path (some-> item :uri lsp/uri->path)
                                               :line (some-> item :range :start :line inc)
                                               :sites 0})
                                            (or res []))]
                            (when (seq items)
                              {:lines (mapv #(format "%s %s @ %s:%s"
                                                     (:name %) (:kind %)
                                                     (:path %) (:line %))
                                            items)
                               :items items
                               :more 0}))
    :incomingCalls (shape-hierarchy res)
    :outgoingCalls (shape-hierarchy res)
    nil))

(def ^:private op->section-kind
  "Wire operation → renderer section kind (render.clj switches on these)."
  {:definition :locations
   :references :locations
   :implementation :locations
   :documentSymbol :symbols
   :workspaceSymbol :symbols
   :hover :hover
   :prepareCallHierarchy :prepare
   :incomingCalls :hierarchy
   :outgoingCalls :hierarchy})

(defn- detail-sections
  "Shaped per-conn results → :details section maps for the TUI renderer."
  [op results]
  (let [kind (or (get op->section-kind op) op)]
    (for [{:keys [name root shaped]} results
          :when (seq (:lines shaped))]
      {:server name
       :root (str root)
       :kind kind
       :more (:more shaped 0)
       :items (:items shaped [])})))

(defn- assemble
  "Multi-server output: labelled sections per conn (already shaped),
   failures listed last, nil when everything came back empty."
  [results errors cwd]
  (let [sections (for [{:keys [name root shaped]} results
                       :when (seq (:lines shaped))]
                   (into [(str "── " name " (" (rel-path root cwd) ") ──")]
                         (:lines shaped)))
        failure-lines (map (fn [{:keys [name message]}]
                             (str name ": " message))
                           errors)
        body (concat (apply concat sections) failure-lines)]
    (when-not (every? str/blank? body)
      (str/join "\n" body))))

;; ─── Diagnostics rendering ───────────────────────────────────────────────

(def ^:private severity-names {1 "ERROR" 2 "WARN" 3 "INFO" 4 "HINT"})
(def ^:private max-per-file 8)
(def ^:private flood-threshold 40)
(def ^:private max-project-files 5)

(defn- render-diag
  [{:keys [severity range message source]}]
  (format "%s [%s:%s] %s%s"
          (get severity-names severity "ERROR")
          (inc (get-in range [:start :line]))
          (inc (get-in range [:start :character]))
          (str/trim (or message ""))
          (if source (str " (" source ")") "")))

(defn- render-file-diags
  "Capped per-file block — display lines plus {:severity :line :col :message
   :source} items; a flood collapses to one line (a flood is almost never
   caused by the agent, and a wall of noise is the opposite of useful)."
  [entries]
  (let [sorted (vec (sort-by #(get-in % [:range :start :line]) entries))
        n (count sorted)]
    (cond
      (= 0 n) nil
      (>= n flood-threshold)
      {:lines [(format "%d diagnostics (flood suppressed)" n)] :items []}
      :else
      {:lines (concat (map render-diag (take max-per-file sorted))
                      (when (> n max-per-file)
                        [(str "… and " (- n max-per-file) " more")]))
       :items (mapv (fn [{:keys [severity range message source]}]
                      {:severity severity
                       :line (inc (get-in range [:start :line]))
                       :col (inc (get-in range [:start :character]))
                       :message (str/trim (or message ""))
                       :source source})
                    (take max-per-file sorted))})))

(defn diagnostics-report
  "The `diagnostics` operation: queried file plus up to MAX-PROJECT-FILES
   other files with errors, across every live conn claiming PATH. Reads
   collected push state only — no server round-trip. Returns
   {:text string :sections [...]} — TEXT is the model-facing report,
   SECTIONS feed the TUI renderer."
  [st path]
  (let [cwd (str (fs/cwd))
        file-uri (lsp/path->uri path)
        claimed (set (map (comp str :root) (runtime/claiming-specs st path)))
        relevant (filter (fn [conn]
                           (contains? claimed (str (:root conn))))
                         (runtime/all-conns st))
        file-blocks (for [conn relevant
                          :let [entry (get (runtime/diagnostics-for conn)
                                           file-uri)
                                block (render-file-diags (:diagnostics entry []))]
                          :when block]
                      {:label (str (:name conn) " @ "
                                   (rel-path (:root conn) cwd))
                       :lines (:lines block)
                       :items (:items block)})
        others (for [conn relevant
                     [uri {:keys [diagnostics]}]
                     (runtime/diagnostics-for conn)
                     :let [errs (filter #(= 1 (:severity %)) diagnostics)]
                     :when (and (not= uri file-uri) (seq errs))]
                 {:path (lsp/uri->path uri) :errors (count errs)})
        top-others (->> others (group-by :path)
                        (map (fn [[p xs]]
                               {:path p
                                :errors (transduce (map :errors) + 0 xs)}))
                        (sort-by :errors >)
                        (take max-project-files))]
    (if (and (empty? file-blocks) (empty? top-others))
      {:text "(no results)" :sections []}
      {:text (str/join "\n"
                       (concat
                        (mapcat (fn [{:keys [label lines]}]
                                  (into [(str "── " label " ──")] lines))
                                file-blocks)
                        (when (seq top-others)
                          (into ["── project (files with errors) ──"]
                                (map (fn [{:keys [path errors]}]
                                       (str (rel-path path cwd) ": " errors " error"
                                            (when (> errors 1) "s")))
                                     top-others)))))
       :sections (concat
                  (map (fn [{:keys [label items]}]
                         {:label label :kind :diagnostics :items items})
                       file-blocks)
                  (when (seq top-others)
                    [{:kind :project-errors
                      :items (mapv (fn [{:keys [path errors]}]
                                     {:path path :errors errors})
                                   top-others)}]))})))

;; ─── Tool entry point ────────────────────────────────────────────────────

(defn- resolve-path [cwd p]
  (let [p (str p)]
    (if (fs/absolute? p) p (str (fs/path cwd p)))))

(defn- usage-error [msg]
  (throw (ex-info (str msg ". " usage) {:type ::usage})))

(defn execute
  "Tool executor. SIGNAL is the run's abort atom — polled before starting,
   so ESC skips the round-trip entirely (in-flight waits stay bounded by
   request timeouts). Returns {:content string :details map} — CONTENT is
   the shaped multi-server text, DETAILS the structured sections the TUI
   renderer consumes (absent on usage/abort paths)."
  [st signal args]
  (let [args (or args {})
        raw-op (:operation args)
        op (canonical-op raw-op)
        spec (some-> op name operations)
        cwd (str (fs/cwd))]
    (cond
      (str/blank? (str raw-op)) (usage-error "operation is required")
      (nil? op) (usage-error (str "unknown operation: " raw-op))
      (and signal @signal) {:content "(aborted)"}
      (not (:filePath args)) (usage-error "filePath is required")
      :else
      (let [path (resolve-path cwd (:filePath args))
            line (:line args)
            character (:character args)
            position-op? (not (or (:file-only spec) (:symbol-query spec)
                                  (:diagnostics spec)))]
        (when position-op?
          (when-not (and (number? line) (number? character))
            (usage-error (str (name op) " requires numeric 1-based line and "
                              "character"))))
        (when (and (:symbol-query spec) (str/blank? (:query args)))
          (usage-error "workspaceSymbol requires query"))
        (if (:diagnostics spec)
          (let [{:keys [text sections]} (diagnostics-report st path)]
            {:content text
             :details {:op :diagnostics :cwd cwd
                       :sections (vec sections)}})
          (do
            (when-not (fs/exists? path)
              (throw (ex-info (str "no such file: " path) {:type ::usage})))
            (let [td {:uri (lsp/path->uri path)}
                  ;; 1-based wire input, clamped against 0/negative values
                  pos {:line (max 0 (dec (or line 1)))
                       :character (max 0 (dec (or character 1)))}
                  ;; position ops without a custom :params fn still must
                  ;; carry the position — servers 500 without one (-32603)
                  params (cond
                           (:params spec) ((:params spec) td pos)
                           (:symbol-query spec) {:query (:query args)}
                           position-op? {:textDocument td :position pos}
                           :else {:textDocument td})
                  request (fn [conn method prm]
                            (jrpc/request! (:client conn) method prm {}))
                  hierarchy? (boolean (:hierarchy spec))
                  {:keys [results errors]}
                  (runtime/for-file st path
                                    (fn [conn]
                                      (if hierarchy?
                                        (let [prep (request
                                                    conn
                                                    "textDocument/prepareCallHierarchy"
                                                    {:textDocument td
                                                     :position pos})
                                              item (first prep)]
                                          (if item
                                            (request conn (:hierarchy spec)
                                                     {:item item})
                                            ::none))
                                        (request conn (:method spec) params))))
                  shaped (for [{:keys [name root value]} results]
                           {:name name
                            :root root
                            :shaped (if (= ::none value)
                                      {:lines [] :items []}
                                      (or (shape-result op value)
                                          {:lines [] :items []}))})
                  content (or (assemble shaped errors cwd)
                              "(no results)")]
              (cond-> {:content content}
                (not= content "(no results)")
                (assoc :details {:op op :cwd cwd
                                 :sections (vec (concat
                                                 (detail-sections op shaped)
                                                 (for [{:keys [name message]} errors]
                                                   {:kind :error
                                                    :items [{:name name
                                                             :message message}]})))})))))))))

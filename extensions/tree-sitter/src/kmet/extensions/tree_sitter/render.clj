(ns kmet.extensions.tree-sitter.render
  "TUI renderers for the five tree-sitter tools (wired as :render-call /
   :render-result on the registered tools). Result rendering consumes the
   structured :details maps emitted by the execute step and falls back to a
   plain preview when they are absent (replayed sessions, error results).
   All styling goes through kmet.tui.theme; paths are shortened +
   hyperlinked via the shared host helpers in kmet.app.ui.tool-renderers."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.tool-renderers :as shared]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.libs.highlight :as hl]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]))

;; ─── Small helpers ────────────────────────────────────────────────────────

(defn- arg
  "Tool args may carry keyword or string keys (MCP passthrough vs schema
   normalization) — read either."
  [args k]
  (or (get args k) (get args (name k))))

(defn- title-path
  [p]
  (let [home (System/getProperty "user.home" "")]
    (if (and (string? p) (seq home)
             (or (= p home) (str/starts-with? p (str home "/"))))
      (str "~" (subs p (count home)))
      p)))

(defn- title-str
  [v]
  (when (and (string? v) (seq v)) v))

(defn title-list-symbols
  [args]
  (when-let [p (title-str (arg args :path))]
    (str "list_symbols " (title-path p))))

(defn- title-in-root
  [tool-name s r]
  (str tool-name " " s
       (when (and r (not= r ".")) (str " in " (title-path r)))))

(defn title-find-definition
  [args]
  (when-let [s (title-str (arg args :symbol))]
    (title-in-root "find_definition" s (title-str (arg args :root)))))

(defn title-get-symbol-body
  [args]
  (when-let [s (title-str (arg args :symbol))]
    (when-let [p (title-str (arg args :path))]
      (str "get_symbol_body " s " in " (title-path p)))))

(defn title-find-callers
  [args]
  (when-let [s (title-str (arg args :symbol))]
    (title-in-root "find_callers" s (title-str (arg args :root)))))

(defn title-find-callees
  [args]
  (when-let [s (title-str (arg args :symbol))]
    (when-let [p (title-str (arg args :path))]
      (str "find_callees " s " in " (title-path p)))))

(defn- txt [s] (text/make-text s 0 0))

(def ^:private preview-lines 6)

(defn- expand-hint
  "The standard collapsed-preview footer: '(N more lines, <key> to toggle)'."
  [more theme width]
  (utils/truncate-to-width
   (str (theme/fg theme :muted (str "... (" more " more lines,"))
        " " (app-kb/key-hint "app.tools.expand" "to toggle")
        (theme/fg theme :muted ")"))
   width "..."))

(defn- plain-rows!
  "Append LINES as tool-output rows inside container C (truncated); STYLE
   overrides the row color (errors render in :error)."
  ([c lines theme width] (plain-rows! c lines theme width :tool-output))
  ([c lines theme width style]
   (doseq [line lines]
     (container/container-add-child
      c (txt (utils/truncate-to-width (theme/fg theme style line)
                                      width "..."))))))

(defn- fallback-result!
  "Plain-text preview used when :details are unavailable (replayed old
   sessions): 6-line preview collapsed, full content expanded; error text
   renders in :error."
  [c content is-error theme width expanded?]
  (let [lines (-> (or content "") str/split-lines vec)
        total (count lines)
        shown (take (if expanded? total preview-lines) lines)
        more (- total preview-lines)]
    (container/container-add-child c (spacer/make-spacer 1))
    (plain-rows! c shown theme width (if is-error :error :tool-output))
    (when (and (not expanded?) (pos? more))
      (container/container-add-child
       c (txt (expand-hint more theme width))))))

;; ─── Call renderer ────────────────────────────────────────────────────────

(defn render-call
  "One-line op summary per SPEC §Tool renderers: bold tool name, accent
   symbol (`— handle_submit`), muted `in <path>` (hyperlinked file paths,
   plain roots). Safe mid-stream — partial args render what's there."
  [_name args theme width & [{:keys [cwd]}]]
  (let [path (arg args :path)
        symbol (arg args :symbol)
        root (arg args :root)
        dir? (and root (fs/directory? root))
        title (theme/fg theme :tool-title (theme/bold _name))
        sym (when symbol (theme/fg theme :accent (str "— " symbol)))
        target (cond
                 path (str "in " (shared/render-tool-path path theme (str cwd)))
                 dir? (str "in "
                           (shared/link-path
                            (theme/fg theme :muted root) root (str cwd)))
                 root (str "in " (theme/fg theme :muted root)))
        line (str title
                  (when sym (str " " sym))
                  (when (seq (str target)) (str " " target)))]
    (txt (utils/truncate-to-width line width "..."))))

;; ─── Result renderers ─────────────────────────────────────────────────────

(defn- pluralize
  "symbols -> symbol when N is 1, else unchanged."
  [label n]
  (if (= n 1) (str/replace label #"s$" "") label))

(defn- summary-result!
  "Collapsed summary line for the four list-style tools: `✓ 12 symbols`
   (success), `for 'handle_submit'` (accent), `across 3 files` (muted);
   zero-hit variant dims the whole line. Expanded shows the full listing
   the model saw."
  [c {:keys [count label name file-count]} content theme width expanded?]
  (if-not expanded?
    (let [hit? (pos? (or count 0))
          parts (cond-> [(theme/fg theme (if hit? :success :muted)
                                   (if hit?
                                     (str "✓ " count " " (pluralize label count))
                                     (str "No " label " found")))]
                  name (conj (theme/fg theme :accent (str "for '" name "'")))
                  (and hit? file-count) (conj (theme/fg theme :muted
                                                        (str "across " file-count " "
                                                             (pluralize "files" file-count)))))
          line (str/join " " parts)]
      (container/container-add-child c (spacer/make-spacer 1))
      (container/container-add-child
       c (txt (utils/truncate-to-width line width "..."))))
    (do (container/container-add-child c (spacer/make-spacer 1))
        (plain-rows! c (str/split-lines (or content "")) theme width))))

(def ^:private ext-lang
  "File extension → highlight language name (kmet.libs.highlight set)."
  {"clj" "clojure" "cljs" "clojure" "cljc" "clojure" "cljd" "clojure"
   "bb" "clojure" "edn" "clojure" "lpy" "clojure"
   "py" "python"
   "ts" "typescript" "mts" "typescript" "cts" "typescript"
   "tsx" "typescript"})

(defn- lang-for [path]
  (some-> (fs/extension path) str/lower-case ext-lang))

(defn- body-result!
  "get_symbol_body rendering. Collapsed: `✓ handle_submit (42 lines) in
   src/…`. Expanded: the body through the shared syntax highlighter
   (language derived from the file extension), falling back to plain
   tool-output rows when the language is unsupported."
  [c {:keys [name line-count path body]} theme width expanded?]
  (if-not expanded?
    (let [line (str (theme/fg theme :success "✓ ")
                    (theme/fg theme :accent (str name))
                    (theme/fg theme :dim (str " (" line-count " lines) in "))
                    (shared/render-tool-path path theme nil))]
      (container/container-add-child c (spacer/make-spacer 1))
      (container/container-add-child
       c (txt (utils/truncate-to-width line width "..."))))
    (let [lang (lang-for path)
          highlight (get (theme/get-markdown-theme theme) :highlight-code)
          lines (if (and lang (hl/supports-language? lang))
                  (highlight body lang)
                  (mapv #(theme/fg theme :tool-output %)
                        (str/split-lines (str body))))]
      (container/container-add-child c (spacer/make-spacer 1))
      (doseq [l lines]
        (container/container-add-child
         c (txt (utils/truncate-to-width l width "...")))))))

(defn render-result
  "Structured transcript rendering of a tree-sitter tool result. Shape comes
   from the execute step's :details ({:count :label :name :file-count} for
   the four list-style tools, {:name :line-count :path :body} for
   get_symbol_body); without details this degrades to the default plain
   preview (replayed sessions). Errors pass through in the error color."
  [content is-error theme width expanded? _started-at _ended-at _truncation
   {:keys [details]}]
  (let [c (container/make-container)]
    (cond
      (or is-error (nil? details))
      (fallback-result! c content is-error theme width expanded?)

      (contains? details :body)
      (body-result! c details theme width expanded?)

      :else
      (summary-result! c details content theme width expanded?))
    c))

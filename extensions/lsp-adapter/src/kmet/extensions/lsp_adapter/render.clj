(ns kmet.extensions.lsp-adapter.render
  "TUI renderers for the lsp tool (wired as :render-call / :render-result on
   the registered tool). The result renderer consumes the structured
   :details sections emitted by kmet.extensions.lsp-adapter.tools/execute and
   falls back to a plain preview when they are absent (replayed sessions,
   error results). All styling goes through kmet.tui.theme; paths are
   shortened + hyperlinked via the shared host helpers in
   kmet.app.ui.tool-renderers."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.tool-renderers :as shared]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]))

;; ─── Small helpers ────────────────────────────────────────────────────────

(def ^:private preview-lines 6)

(defn- arg
  "Tool args may carry keyword or string keys (MCP passthrough vs schema
   normalization) — read either."
  [args k]
  (or (get args k) (get args (name k))))

(defn- txt [s] (text/make-text s 0 0))

(defn- fold-home
  "ROOT with $HOME folded to ~."
  [root]
  (let [r (str root) home (str (fs/home))]
    (if (str/starts-with? r home)
      (str "~" (subs r (count home)))
      r)))

(defn- display-root
  "Section root relative to CWD when inside it, else home-folded absolute."
  [root cwd]
  (let [r (str root) c (str cwd)]
    (if (str/starts-with? r (str c "/"))
      (subs r (inc (count c)))
      (fold-home r))))

(defn- expand-hint
  "The standard collapsed-preview footer: '(N more lines, <key> to toggle)'."
  [more theme width]
  (utils/truncate-to-width
   (str (theme/fg theme :muted (str "... (" more " more lines,"))
        " " (app-kb/key-hint "app.tools.expand" "to toggle")
        (theme/fg theme :muted ")"))
   width "..."))

;; ─── Call renderer ────────────────────────────────────────────────────────

(defn render-lsp-call
  "One-line op summary: `lsp references src/kmet/core.clj:235:7` or
   `lsp workspaceSymbol \"query\"`. Op bold, path accent+hyperlinked,
   1-based coords muted. Safe mid-stream (partial args render what's there)."
  [_name args theme width & [{:keys [cwd]}]]
  (let [op (arg args :operation)
        fp (arg args :filePath)
        q (arg args :query)
        l (arg args :line)
        c (arg args :character)
        coords (when (and (number? l) (number? c))
                 (theme/fg theme :muted (str ":" l ":" c)))
        title (theme/fg theme :tool-title
                        (theme/bold (str "lsp " (or op "?"))))
        target (cond
                 (string? q) (theme/fg theme :accent (str "\"" q "\""))
                 :else (str (shared/render-tool-path fp theme (str cwd))
                            coords))
        line (str title (when (and target (seq (str target))) " ") target)]
    (txt (utils/truncate-to-width line width "..."))))

(defn- title-nonblank
  [v]
  (when (and (string? v) (seq v)) v))

(defn title-lsp
  [args]
  (when-let [op (title-nonblank (arg args :operation))]
    (let [fp (arg args :filePath)
          q (arg args :query)
          l (arg args :line)
          c (arg args :character)]
      (str "lsp " op
           (cond
             (title-nonblank q) (str " \"" q "\"")
             (title-nonblank fp)
             (str " " (fold-home fp)
                  (when (and (number? l) (number? c)) (str ":" l ":" c)))
             :else "")))))

;; ─── Row builders (kind → styled strings) ─────────────────────────────────

(defn- location-rows
  "Group consecutive same-file items under one hyperlinked path header
   (relative to cwd when inside it); indented line:col rows beneath, each
   hyperlinked to the file."
  [items theme cwd]
  (mapcat (fn [group]
            (let [path (:path (first group))]
              (cons (shared/render-tool-path (display-root path cwd) theme cwd)
                    (for [{:keys [line col]} group]
                      (shared/link-path
                       (str "  "
                            (theme/fg theme :tool-output line)
                            (theme/fg theme :muted (str ":" col)))
                       path cwd)))))
          (partition-by :path items)))

(def ^:private kind-glyphs
  {"namespace" "⌂" "module" "□" "class" "◇" "interface" "◈"
   "fn" "ƒ" "method" "λ" "constructor" "λ" "var" "ν"
   "property" "◦" "field" "•" "const" "π" "enum" "ε"
   "struct" "▤" "type-param" "T"})

(defn- glyph-color [kind]
  (cond
    (#{"fn" "method" "constructor"} kind) :tool-title
    (#{"namespace" "module" "class" "interface" "enum" "struct" "type-param"}
     kind) :accent
    :else :muted))

(defn- symbol-rows
  "Tree rows with a colored kind glyph replacing the kind word."
  [items theme]
  (for [{:keys [depth name kind]} items]
    (str (apply str (repeat (+ 2 (* 2 (or depth 0))) " "))
         (theme/fg theme (glyph-color kind)
                   (get kind-glyphs kind "·"))
         " "
         (theme/fg theme :tool-output (or name "?")))))

(defn- hover-rows
  "Strip markdown fences + inline backticks; first content line emphasized,
   remaining doc lines muted."
  [text theme]
  (let [lines (->> (str/split-lines (str text))
                   (remove #(str/starts-with? % "```"))
                   (map #(str/replace % #"`([^`]*)`" "$1"))
                   (drop-while str/blank?))
        first-idx (first (keep-indexed (fn [i l] (when-not (str/blank? l) i))
                                       lines))]
    (map-indexed (fn [i l]
                   (cond
                     (= i first-idx) (theme/fg theme :tool-output (theme/bold l))
                     (str/blank? l) l
                     :else (theme/fg theme :muted l)))
                 lines)))

(defn- hierarchy-rows
  "ƒ name path:line rows (deduped at shaping time), site counts muted."
  [items theme cwd]
  (for [{:keys [name kind path line sites]} items]
    (str "  "
         (theme/fg theme (glyph-color kind) (get kind-glyphs kind "·"))
         " "
         (theme/fg theme :tool-output (or name "?"))
         " "
         (if path
           (shared/link-path
            (theme/fg theme :muted (str (display-root path cwd) ":" line))
            path cwd)
           (theme/fg theme :muted "?"))
         (when (pos? (or sites 0))
           (theme/fg theme :muted
                     (str " (" sites " site" (when (> sites 1) "s") ")"))))))

(def ^:private severity-style
  {1 ["✗" :error] 2 ["▲" :warning] 3 ["ℹ" :muted] 4 ["·" :muted]})

(defn- diagnostic-rows
  "Severity glyph + colored [line:col] + message (source)."
  [items theme]
  (for [{:keys [severity line col message source]} items]
    (let [[glyph color] (get severity-style severity ["✗" :error])]
      (str (theme/fg theme color glyph) " "
           (theme/fg theme :muted (str "[" line ":" col "]")) " "
           (theme/fg theme :tool-output message)
           (when source (theme/fg theme :muted (str " (" source ")")))))))

(defn- project-error-rows
  "Project summary: path accent-hyperlinked, error count muted."
  [items theme cwd]
  (for [{:keys [path errors]} items]
    (str (shared/render-tool-path (display-root path cwd) theme cwd) " "
         (theme/fg theme :error errors)
         (theme/fg theme :muted (str " error" (when (> errors 1) "s"))))))

(defn- error-rows
  "Per-server failures: ✗ name: message in error color."
  [items theme]
  (for [{:keys [name message]} items]
    (str (theme/fg theme :error "✗ ")
         (theme/fg theme :error (str name ": " message)))))

(defn- section-header
  "Muted '── server (short-root) ──' divider; diagnostics sections carry a
   prebuilt :label, the project summary gets a fixed title."
  [{:keys [server root label kind]} theme cwd]
  (theme/fg theme :muted
            (cond
              label (str "── " label " ──")
              (= kind :project-errors) "── project (files with errors) ──"
              :else (str "── " server
                         (when root (str " (" (display-root root cwd) ")"))
                         " ──"))))

(defn- section-rows
  "[header & rows] styled strings for one details section."
  [{:keys [kind items] :as section} theme cwd]
  (cons (section-header section theme cwd)
        (case (keyword kind)
          :locations (location-rows items theme cwd)
          :symbols (symbol-rows items theme)
          :hover (hover-rows (:text (first items)) theme)
          (:hierarchy :prepare) (hierarchy-rows items theme cwd)
          :diagnostics (diagnostic-rows items theme)
          :project-errors (project-error-rows items theme cwd)
          :error (error-rows items theme)
          (map #(theme/fg theme :tool-output %) items))))

;; ─── Result renderer ──────────────────────────────────────────────────────

(defn- fallback-result!
  "Plain-text preview used when :details are unavailable (replayed old
   sessions) or the tool errored: 6-line preview, full content expanded."
  [c content is-error theme width expanded?]
  (let [style (if is-error :error :tool-output)
        lines (-> (or content "") str/split-lines vec)
        total (count lines)
        shown (take (if expanded? total preview-lines) lines)
        more (- total preview-lines)]
    (container/container-add-child c (spacer/make-spacer 1))
    (doseq [line shown]
      (container/container-add-child
       c (txt (utils/truncate-to-width (theme/fg theme style line) width "..."))))
    (when (and (not expanded?) (pos? more))
      (container/container-add-child
       c (txt (expand-hint more theme width))))))

(defn render-lsp-result
  "Structured transcript rendering of an lsp tool result. Sections come from
   the execute step's :details map (context); without them this degrades to
   the default plain preview. Collapsed shows the first PREVIEW-LINES rows
   across all sections with the shared expand hint."
  [content is-error theme width expanded? _started-at _ended-at _truncation
   {:keys [details cwd]}]
  (let [c (container/make-container)
        sections (vec (:sections details))]
    (if (or is-error (empty? sections))
      (fallback-result! c content is-error theme width expanded?)
      (let [all-rows (mapcat #(section-rows % theme cwd) sections)
            total (count all-rows)
            shown (vec (if expanded? all-rows (take preview-lines all-rows)))
            more (- total preview-lines)]
        (container/container-add-child c (spacer/make-spacer 1))
        (doseq [row shown]
          (container/container-add-child
           c (txt (utils/truncate-to-width row width "..."))))
        (when (and (not expanded?) (pos? more))
          (container/container-add-child
           c (txt (expand-hint more theme width))))
        (when (= "(no results)" content)
          (container/container-add-child
           c (txt (theme/fg theme :muted (theme/italic "(no results)")))))))
    c))
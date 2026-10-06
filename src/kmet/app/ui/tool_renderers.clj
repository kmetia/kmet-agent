(ns kmet.app.ui.tool-renderers
  "Reusable built-in tool renderers. These functions are also the supported
   host renderer surface for extensions."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]
            [kmet.libs.terminal-image :as timg]
            [kmet.libs.edit-diff :as edit-diff]
            [kmet.libs.highlight :as hl]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.skill-message :as skill-message]
            [kmet.tui.hiccup :as h]
            [kmet.app.bash-executor :as bash-exec]))

;; ─── Shared render helpers (pi: render-utils.ts) ────────────────────────────

(defn- tool-text
  "The ubiquitous renderer leaf: zero-padded text. Element form keeps the
   assembled trees declarative; compile-tree turns them into the same plain
   records the imperative builders produced (all content is precomputed
   strings — nothing reactive inside, so cache-miss rebuilds abandon them
   safely)."
  [s]
  [:text {:padding-x 0 :padding-y 0} s])

(defn- tool-text-lines
  "A block of pre-styled output lines as ONE [:text] node (joined with
   newlines; the Text component splits on them itself). One node renders the
   same lines as one node per line without paying the per-node hiccup
   compile + component render — measured ~3.3x on a 6.4k-line bash body
   (perf.md §12). Returns a node vector (empty for no lines) to concat."
  [lines]
  (when (seq lines)
    [(tool-text (str/join "\n" lines))]))

(defn- tool-path-str
  "Pi: str() — string passes through, missing → \"\", non-string → null."
  [raw-path]
  (if (string? raw-path) raw-path (if (nil? raw-path) "" nil)))

(defn- replace-tabs
  "Pi: replaceTabs — tabs render as 3 spaces."
  [text]
  (str/replace text "\t" "   "))

(defn- normalize-display-text
  "Pi: normalizeDisplayText — strip carriage returns."
  [text]
  (str/replace text "\r" ""))

(defn- sanitize-display-text
  "Pi: getTextOutput — strip ANSI codes and control characters
   (except \t \n \r) before display."
  [text]
  (-> text
      (utils/strip-ansi-codes)
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      (str/replace #"[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]" "")))

(defn- shorten-path
  "Pi: shortenPath — replace the home directory prefix with ~."
  [path]
  (let [home (str (fs/home))]
    (if (and (pos? (count home))
             (or (= path home) (str/starts-with? path (str home "/"))))
      (str "~" (subs path (count home)))
      path)))

(defn- url-encode
  "Percent-encode a path for use in a file:// URL (pi: pathToFileURL)."
  [s]
  (let [safe #{\- \_ \. \~ \/ \:}
        bytes (.getBytes ^String s "UTF-8")]
    (apply str
           (for [b bytes
                 :let [c (bit-and (int b) 0xFF)]]
             (if (or (and (>= c 0x41) (<= c 0x5A))
                     (and (>= c 0x61) (<= c 0x7A))
                     (and (>= c 0x30) (<= c 0x39))
                     (contains? safe (char c)))
               (char c)
               (format "%%%02X" c))))))

(defn- file-url
  "Pi: pathToFileURL(...).href — file:// + percent-encoded absolute path."
  [path]
  (str "file://" (url-encode (if (str/starts-with? path "/") path (str "/" path)))))

(defn- resolve-path
  "Pi: resolvePath — absolute path for raw-path relative to cwd."
  [raw-path cwd]
  (if (fs/absolute? raw-path)
    (str (fs/absolutize raw-path))
    (str (fs/normalize (fs/path cwd raw-path)))))

(defn link-path
  "Pi: linkPath — wrap styled text in an OSC 8 hyperlink when the terminal
   supports hyperlinks and the path is non-empty. Public: extensions build
   clickable location rows (e.g. the lsp tool renderer)."
  [styled raw-path cwd]
  (if (and (:hyperlinks (timg/get-capabilities))
           (string? raw-path)
           (pos? (count raw-path)))
    (str "\u001b]8;;" (file-url (resolve-path raw-path cwd)) "\u001b\\" styled "\u001b]8;;\u001b\\")
    styled))

(defn render-tool-path
  "Pi: renderToolPath — accent path (shortened + hyperlinked when supported);
   '...' toolOutput when empty; '[invalid arg]' error when the arg is not a string.
   Public: shared extension surface (see src/kmet/extension.md §Custom renderers)."
  [raw-path theme cwd]
  (let [s (tool-path-str raw-path)]
    (if (nil? s)
      (theme/fg theme :error "[invalid arg]")
      (if (empty? s)
        (theme/fg theme :tool-output "...")
        (link-path (theme/fg theme :accent (shorten-path s)) s cwd)))))

(defn- trim-trailing-empty-lines
  "Pi: trimTrailingEmptyLines — drop empty lines at the end of a vector."
  [lines]
  (let [n (count lines)]
    (loop [end n]
      (if (and (pos? end) (= "" (nth lines (dec end))))
        (recur (dec end))
        (subvec lines 0 end)))))

(defn- wrap-line-prefix
  "Wrap as much of LINE as NEED visual lines require. The first pass wraps a
   char prefix of (inc need) × width — enough for unstyled text, because
   every wrapped visual line consumes at most width visible columns; the
   prefix doubles while ANSI escapes (zero-width chars) make it under-fill.
   A 50 KB single line costs ~0.2 ms here vs ~13 ms wrapped whole (~300 ms
   fg-styled, whose ANSI forces the per-char path). Widths ≤ 0 render
   nothing (no columns to fill; the budget could not grow). Returns
   {:wrapped [...] :whole? bool}: :whole? true only when WRAPPED is the
   line's complete wrap (the prefix reached the line's end)."
  [line need width]
  (if (<= width 0)
    {:wrapped [] :whole? false}
    (let [n (count line)]
      (loop [budget (* (inc need) width)]
        (let [wrapped (utils/wrap-text-with-ansi (subs line 0 (min n budget)) width)]
          (cond
            (> (count wrapped) need) {:wrapped (vec (take need wrapped)) :whole? false}
            (>= budget n) {:wrapped (vec wrapped) :whole? true}
            :else (recur (* 2 budget))))))))

(defn- bounded-head-visual-lines
  "Bounded head VISUAL-line truncation over logical LINES. Wraps each line
   only as far as MAX-VISUAL requires (see wrap-line-prefix), so the cost is
   O(shown head) instead of O(content) — a full wrap to report the exact
   skipped VISUAL count would cost ~80 ms on a 135 KB body vs ~0.6 ms here.
   This variant instead lets the caller report logical lines:
   :consumed counts lines shown in FULL (a line cut mid-wrap is not
   consumed), so (- total consumed) is the pi-style \"N more lines\" hint
   count. Returns {:visual-lines [...] :consumed n}."
  [lines max-visual width]
  (loop [remaining lines
         consumed 0
         acc []
         visual 0]
    (if (or (>= visual max-visual) (empty? remaining))
      {:visual-lines acc :consumed consumed}
      (let [room (- max-visual visual)
            {:keys [wrapped whole?]}
            (wrap-line-prefix (first remaining) room width)]
        (if whole?
          (recur (rest remaining) (inc consumed) (into acc wrapped)
                 (+ visual (count wrapped)))
          {:visual-lines (into acc wrapped) :consumed consumed})))))

(defn expand-hint
  "The compact ctrl+o nag shared by every collapsed body: `... (+N lines,
   ctrl+o toggle)`. Public: the built-in renderers and extensions use it so
   every transcript hint reads the same. N is the hidden count: visual lines
   for the shell output window, logical lines for file previews."
  [theme hidden-lines]
  (str (theme/fg theme :muted (str "... (+" hidden-lines " lines, "))
       (app-kb/key-hint "app.tools.expand" "toggle")
       (theme/fg theme :muted ")")))

;; ─── Compact read classification (pi: read.ts getCompactReadClassification) ─

(def ^:private compact-resource-file-names
  #{"AGENTS.override.md" "AGENTS.md" "AGENTS.MD" "CLAUDE.md" "CLAUDE.MD"})

(defn display-path
  "The /-separated form of PATH for tool result text: str of a Windows Path
   renders with backslashes, and result paths are read-only labels. Public:
   shared with the opt-in search tools."
  [path]
  (str/replace (str path) fs/file-separator "/"))

(defn path-relative-to-cwd-or-absolute
  "Pi: formatPathRelativeToCwdOrAbsolute — path relative to cwd when inside
   it, absolute otherwise. fs-aware: a literal \"/\" prefix never matches on
   Windows (C:\\…\\x vs C:\\…/), which fell back to the full path. Public:
   shared with the opt-in search tools' result rendering."
  [file-path cwd]
  (let [abs (resolve-path file-path cwd)
        cwd-abs (str (fs/absolutize cwd))]
    (cond
      (= abs cwd-abs)
      "."

      (fs/starts-with? abs cwd-abs)
      (try (display-path (fs/relativize cwd-abs abs))
           (catch Exception _ (display-path abs)))

      :else
      (display-path abs))))

(defn- get-pi-docs-classification
  "Pi: getPiDocsClassification — README.md and docs/* / examples/* inside the
   package root render as 'read docs'. In kmet the package root is the repo
   root that contains README.md; walk up from CWD (the runtime cwd — the
   project the session works in, not the process cwd). Returns
   {:kind :docs :label str} or nil."
  [absolute-path cwd]
  (try
    (let [;; Find repo root by walking up from cwd until README.md is found
          repo-root (loop [d (str (fs/absolutize cwd))]
                      (cond
                        (fs/exists? (str (fs/path d "README.md"))) d
                        (= d (str (fs/parent d))) nil
                        :else (recur (str (fs/parent d)))))]
      (when repo-root
        (let [rel (try (str (fs/relativize (fs/path repo-root) (fs/path absolute-path)))
                       (catch Exception _ nil))]
          (when (and rel
                     (not (str/blank? rel))
                     (not= rel "..")
                     (not (str/starts-with? rel (str ".." fs/file-separator)))
                     (not (fs/absolute? rel)))
            (let [label (str/replace rel fs/file-separator "/")]
              (when (or (= label "README.md")
                        (str/starts-with? label "docs/")
                        (str/starts-with? label "examples/"))
                {:kind :docs :label label}))))))
    (catch Exception _ nil)))

(defn- compact-read-classification
  "Pi: getCompactReadClassification — SKILL.md, AGENTS.md/CLAUDE.md, and pi-docs
   files render as compact labels instead of paths. Returns
   {:kind :skill|:resource|:docs :label str} or nil."
  [raw-path cwd]
  (when (and (string? raw-path) (pos? (count raw-path)))
    (let [absolute (resolve-path raw-path cwd)
          file-name (fs/file-name absolute)]
      (cond
        (= file-name "SKILL.md")
        {:kind :skill
         :label (or (some-> (fs/parent absolute) fs/file-name str) file-name)}
        :else
        (if-let [docs (get-pi-docs-classification absolute cwd)]
          docs
          (when (contains? compact-resource-file-names file-name)
            {:kind :resource
             :label (path-relative-to-cwd-or-absolute absolute cwd)}))))))

(defn- read-line-range
  "Pi: formatReadLineRange — ':start-end' warning suffix when offset/limit given."
  [args theme]
  (let [offset (:offset args)
        limit (:limit args)]
    (when (or offset limit)
      (let [start-line (or offset 1)
            end-line (when limit (+ start-line limit -1))]
        (theme/fg theme :warning
                  (str ":" start-line (when end-line (str "-" end-line))))))))

(defn- format-compact-read-call
  "Pi: formatCompactReadCall — skill/resources render as labeled read calls
   with an expand hint instead of a full path. The skill label and the hint
   come from the skill message component, so the read call and a
   `/skill:` invocation's collapsed line cannot diverge."
  [classification tool-name theme range-str]
  (let [expand-hint (skill-message/expand-hint theme)]
    (if (= :skill (:kind classification))
      (str (skill-message/label theme)
           (theme/fg theme :custom-message-text (:label classification))
           range-str expand-hint)
      (str (theme/fg theme :tool-title
                     (theme/bold (str tool-name " " (name (:kind classification)))))
           " " (theme/fg theme :accent (:label classification))
           range-str expand-hint))))

;; ─── Edit diff preview (pi: computeEditsDiff + renderDiff) ─────────────────
;; Applies edits in memory and produces pi-format line-numbered diff lines:
;;   " 123 content" context, "-123 content" removed, "+123 content" added,
;;   " ..." skip markers. Single-line -/+ pairs get word-level intra-line
;;   inverse highlighting (pi: diffWords + renderIntraLineDiff).

(defn- compute-edit-preview
  "Try to apply edits in memory and return a pi-format diff.
   Args: path (raw, for messages — must match the edit tool's error text),
   resolved-path (absolute: the file the tool will actually touch), edits —
   vector of {:old-text str :new-text str}.
   Uses the same BOM/line-ending normalization and exact-then-fuzzy matching
   as the edit tool (kmet.libs.edit-diff) so preview and result are
   byte-comparable and error messages match.
   Returns {:success? bool :diff str :diff-lines [\"+123 content\" ...] :error str?}
   with :diff-lines [] when the edit produces no visible diff (whitespace-only change)."
  [path resolved-path edits]
  (try
    (let [f (io/file resolved-path)]
      (if-not (fs/exists? f)
        {:success? false :error (str "File not found: " path)}
        (let [content (slurp f)
              {:keys [text]} (edit-diff/strip-bom content)
              normalized (edit-diff/normalize-to-lf text)
              {:keys [base-content new-content]}
              (edit-diff/apply-edits-to-normalized-content normalized edits path)
              {:keys [diff]} (edit-diff/format-diff-lines
                              (str/split-lines base-content)
                              (str/split-lines new-content))]
          (if (str/blank? diff)
            ;; Only whitespace/trailing-newline changes — no visible diff
            ;; lines, so don't store a diff-lines vector with one empty line
            ;; (renders as a blank box line: \"diff with no difference\").
            {:success? true :diff "" :diff-lines []}
            {:success? true
             :diff diff
             :diff-lines (vec (str/split-lines diff))}))))
    (catch Exception e
      ;; Pi: preview and tool surface the same error so the render-result can
      ;; suppress an execution error already shown by the preview
      {:success? false
       :error (if (= :edit-error (:type (ex-data e)))
                (ex-message e)
                (str "Error editing " path ": " (ex-message e)))})))

(defn- edit-preview
  "Compute the edit preview, failing on missing/empty edits (pi: validateEditInput).
   PATH is the raw argument (kept for the error message, which must match
   the edit tool's); CWD — the render context's runtime cwd — resolves a
   relative path to the file the tool will actually touch, since the tool
   resolves against the session's cwd too (:cwd nil leaves the path as-is)."
  [path cwd edits]
  (if (or (nil? edits) (empty? edits))
    {:success? false :error "Edit tool input is invalid. edits must contain at least one replacement."}
    (compute-edit-preview path
                          (if (and cwd (string? path)) (resolve-path path cwd) path)
                          edits)))

(defn- normalize-edit-args
  "Pi: prepareEditArguments — normalize edit tool args into a vector of
   {:old-text :new-text}. Handles: edits as a JSON string (array), camelCase
   oldText/newText keys, and the legacy top-level old-text/new-text pair
   (appended, matching the tool's normalize-edits)."
  [args]
  (let [parsed (cond
                 (string? (:edits args)) (try (let [p (json/parse-string (:edits args) true)]
                                                ;; parses arrays lazily — realize
                                                ;; inside the guard so a malformed JSON
                                                ;; string degrades to nil instead of
                                                ;; crashing the render preview in the
                                                ;; mapv below (render loop death)
                                                (when (sequential? p) (vec p)))
                                              (catch Exception _ nil))
                 (sequential? (:edits args)) (:edits args)
                 :else nil)
        kebab (mapv (fn [e] {:old-text (or (:old-text e) (:oldText e))
                             :new-text (or (:new-text e) (:newText e))})
                    parsed)
        legacy (when (and (or (string? (:old-text args)) (string? (:oldText args)))
                          (or (string? (:new-text args)) (string? (:newText args))))
                 {:old-text (or (:old-text args) (:oldText args))
                  :new-text (or (:new-text args) (:newText args))})]
    (cond-> (seq kebab)
      legacy (conj legacy))))
(defn- word-diff
  "Pi: renderIntraLineDiff — word-level diff of two strings.
   Common leading/trailing tokens stay plain; the changed middle gets
   inverse styling. The first changed part's leading whitespace stays
   unstyled. O(n) prefix/suffix trimming (pi uses diffWords LCS, which is
   quadratic and too slow for very long lines). Returns
   {:removed-line :added-line}."
  [old-s new-s]
  (let [old-tokens (vec (re-seq #"\s+|\S+" old-s))
        new-tokens (vec (re-seq #"\s+|\S+" new-s))
        n (count old-tokens) m (count new-tokens)
        p (loop [i 0]
            (if (and (< i n) (< i m) (= (nth old-tokens i) (nth new-tokens i)))
              (recur (inc i)) i))
        s (loop [k 0]
            (if (and (>= (- n 1 k) p) (>= (- m 1 k) p)
                     (= (nth old-tokens (- n 1 k)) (nth new-tokens (- m 1 k))))
              (recur (inc k)) k))
        old-mid (subvec old-tokens p (- n s))
        new-mid (subvec new-tokens p (- m s))]
    (if (and (empty? old-mid) (empty? new-mid))
      {:removed-line old-s :added-line new-s}
      (let [removed (StringBuilder.)
            added (StringBuilder.)
            old-ws (when (seq old-mid) (re-find #"^\s" (first old-mid)))
            new-ws (when (seq new-mid) (re-find #"^\s" (first new-mid)))
            emit (fn [sb tokens]
                   (doseq [t tokens] (.append sb (theme/inverse t))))]
        (doseq [t (subvec old-tokens 0 p)] (.append removed t))
        (doseq [t (subvec new-tokens 0 p)] (.append added t))
        (when old-ws (.append removed (first old-mid)))
        (when new-ws (.append added (first new-mid)))
        (emit removed (if old-ws (subvec old-mid 1) old-mid))
        (emit added (if new-ws (subvec new-mid 1) new-mid))
        (doseq [t (subvec old-tokens (- n s))] (.append removed t))
        (doseq [t (subvec new-tokens (- m s))] (.append added t))
        {:removed-line (str removed) :added-line (str added)}))))
(defn- style-change-pair
  "Style a consecutive -/+ run, applying word-level intra-line diff to
   single pairs (pi: renderDiff). Returns a vector of styled strings."
  [removed added tabs theme]
  (if (and (= 1 (count removed)) (= 1 (count added)))
    (let [{:keys [removed-line added-line]}
          (word-diff (tabs (:content (first removed)))
                     (tabs (:content (first added))))]
      [(theme/fg theme :tool-diff-removed
                 (str "-" (:line-num (first removed)) " " removed-line))
       (theme/fg theme :tool-diff-added
                 (str "+" (:line-num (first added)) " " added-line))])
    (into []
          (concat
           (mapv #(theme/fg theme :tool-diff-removed
                            (str "-" (:line-num %) " " (tabs (:content %)))) removed)
           (mapv #(theme/fg theme :tool-diff-added
                            (str "+" (:line-num %) " " (tabs (:content %)))) added)))))

(defn- render-diff-lines
  "Style pi-format diff lines; single -/+ pairs get intra-line inverse
   highlighting. Returns a vector of styled strings (pi: renderDiff).
   A + run with no preceding - run (pure insertion) is styled as added
   lines — it must not fall through to the context branch, which would
   strip the + marker and present new lines as unchanged context.
   A -/+ pair whose contents are identical after trimming trailing
   whitespace (e.g. a trailing-space-only change) is not rendered — the
   difference is invisible, so showing -/+ lines would be a \"diff with
   no difference\"."
  [diff-lines theme]
  (let [n (count diff-lines)
        parse (fn [line]
                (when-let [m (re-find #"^([ +-])(\s*\d*)\s(.*)$" line)]
                  {:prefix (nth m 1) :line-num (nth m 2) :content (nth m 3)}))
        tabs (fn [s] (str/replace s "\t" "   "))
        collect-run (fn [prefix j]
                      (loop [j j acc []]
                        (if-let [q (and (< j n) (parse (nth diff-lines j)))]
                          (if (= prefix (:prefix q))
                            (recur (inc j) (conj acc q))
                            acc)
                          acc)))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [p (parse (nth diff-lines i))]
          (cond
            ;; removal run (+ optional following addition run) — paired
            ;; styling, single pairs get word-level intra-line diff
            (and p (= "-" (:prefix p)))
            (let [removed (collect-run "-" i)
                  rn (count removed)
                  added (collect-run "+" (+ i rn))
                  next-i (+ i rn (count added))
                  ;; single -/+ pair whose visible content is identical
                  ;; (trailing-whitespace-only change) — skip it entirely
                  invisible? (and (= 1 rn) (= 1 (count added))
                                  (= (str/trimr (:content (first removed)))
                                     (str/trimr (:content (first added)))))]
              (if invisible?
                (recur next-i acc)
                (let [styled (style-change-pair removed added tabs theme)]
                  (recur next-i (into acc styled)))))

            ;; pure insertion run — no removals to pair with; style-change-pair
            ;; with an empty removed list styles every line as added
            (and p (= "+" (:prefix p)))
            (let [added (collect-run "+" i)]
              (recur (+ i (count added))
                     (into acc (style-change-pair [] added tabs theme))))

            :else
            (recur (inc i)
                   (conj acc
                         (theme/fg theme :tool-diff-context
                                   (if p
                                     (str " " (:line-num p) " " (tabs (:content p)))
                                     (nth diff-lines i)))))))))))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width context) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded? started-at ended-at truncation context) → IComponent or nil.
;; context is a ToolRenderContext map (see tool-execution-context). Built-in renderers ignore it.

(defn- renderable-edit-input
  "Pi: getRenderablePreviewInput — path must be a non-empty string and edits
   a non-empty vector of {:old-text str :new-text str}."
  [raw-path edits]
  (and (string? raw-path)
       (pos? (count raw-path))
       (seq edits)
       (every? (fn [e] (and (string? (:old-text e)) (string? (:new-text e)))) edits)))

(defn- build-edit-box
  "Pi: buildEditCallComponent — Box whose body background is constant.
   Option 1: the edit box's success/error state rides the tool component's
   tail state line (state-result-nodes), never the body — a settle must not
   repaint lines above the window."
  [name preview raw-path theme cwd _context]
  (let [bg-fn #(theme/bg theme :tool-pending-bg %)
        kids (cond-> [(tool-text (str (theme/fg theme :tool-title
                                                (theme/bold (str name " ")))
                                      (render-tool-path raw-path theme cwd)))]
               preview (into [[:spacer {:lines 1}]
                              (tool-text
                               (if (:success? preview)
                                 (str/join "\n"
                                           (render-diff-lines (:diff-lines preview) theme))
                                 (theme/fg theme :error (:error preview))))]))]
    (h/compile-tree (into [:box {:padding-x 1 :padding-y 1 :bg-fn bg-fn}] kids))))

(defn render-read-call
  [name args theme _width context]
  (let [name (if (seq name) name "read")
        raw-path (:file_path args (:path args))
        range-str (read-line-range args theme)
        classification (when-not (:expanded context)
                         (compact-read-classification raw-path (:cwd context)))]
    (h/compile-tree
     (tool-text
      (if classification
        (format-compact-read-call classification name theme range-str)
        (str (theme/fg theme :tool-title (theme/bold (str name " ")))
             (render-tool-path raw-path theme (:cwd context))
             range-str))))))

;; Shared path→language mapping (pi: getLanguageFromPath): the read result
;; uses it for highlightCode-on-expanded-output and the write cache helpers
;; below reuse it — one definition, declared before both call sites.
(defn- lang-from-path
  "Pi: getLanguageFromPath — path → language name, or nil when the extension
   is missing or unsupported. Returns the canonical language key
   ('clj' → 'clojure'); basename languages (Dockerfile, Makefile) resolve
   directly."
  [raw-path]
  (when (and (string? raw-path) (seq raw-path))
    (let [basename (fs/file-name raw-path)
          ext (fs/extension raw-path)]
      (cond
        (seq ext) (hl/canonical-language ext)
        ;; no dot-extension: try the basename itself (Dockerfile, Makefile)
        (seq basename) (hl/canonical-language basename)
        :else nil))))

(defn render-read-result
  "Collapsed: spacer + up to 10 visual output lines + expand hint + truncation
   warn. The cap counts VISUAL lines at the render width, not logical lines —
   a long wrapped line must not push the preview past its budget. The bounded
   wrap stops at the cap; the hint reports the logical lines not shown in
   full.
   Pi: formatReadResult — when expanded, content is syntax-highlighted
   by path language (highlightCode); unknown languages and errors fall back
   to toolOutput color."
  [content is-error theme width expanded? _started-at _ended-at truncation context]
  (if (and (not expanded?) (not is-error))
    nil
    (let [args (:args context)
          raw-path (:file_path args (:path args))
          lang (when-not is-error (lang-from-path raw-path))
          normalized (replace-tabs (sanitize-display-text (or content "")))
          lines (if lang
                  (trim-trailing-empty-lines
                   (theme/render-highlighted theme normalized lang))
                  (trim-trailing-empty-lines (str/split-lines normalized)))
          total (count lines)
          {:keys [visual-lines consumed]}
          (if expanded?
            {:visual-lines lines :consumed total}
            (bounded-head-visual-lines lines 10 width))
          remaining (- total consumed)
          truncation-warn
          (when (seq lines)
            (let [{:keys [first-line-exceeds-limit truncated-by output-lines
                          total-lines max-lines max-bytes]} truncation]
              (cond
                first-line-exceeds-limit
                (str "[First line exceeds "
                     (bash-exec/format-size (or max-bytes bash-exec/DEFAULT-MAX-BYTES))
                     " limit]")
                (= truncated-by :lines)
                (str "[Truncated: showing " output-lines " of " total-lines
                     " lines (" (or max-lines bash-exec/DEFAULT-MAX-LINES) " line limit)]")
                (= truncated-by :bytes)
                (str "[Truncated: " output-lines " lines shown ("
                     (bash-exec/format-size (or max-bytes bash-exec/DEFAULT-MAX-BYTES)) " limit)]"))))
          kids (concat
                (when (seq lines)
                  (concat
                   (tool-text-lines (if lang
                                      visual-lines
                                      (mapv #(theme/fg theme :tool-output %) visual-lines)))
                   (when (pos? remaining)
                     [(tool-text (expand-hint theme remaining))])
                   (when truncation-warn
                     [[:spacer {:lines 1}]
                      (tool-text (theme/fg theme :warning truncation-warn))]))))]
      (h/compile-tree (into [:container {} [:spacer {:lines 1}]] kids)))))

(declare write-highlight-cache
         update-write-highlight-cache
         write-rendered-lines)

(def ^:private write-preview-lines
  "Collapsed cap on the rendered file body, in visual lines — a wrapped
   line counts once, so a long line cannot push the preview past its
   budget."
  10)

(defn render-write-call
  [name args theme width context]
  (let [name (if (seq name) name "write")
        raw-path (:file_path args (:path args))
        content (:content args)
        title (tool-text (str (theme/fg theme :tool-title (theme/bold (str name " ")))
                              (render-tool-path raw-path theme (:cwd context))))
        kids (if (nil? (tool-path-str content))
               ;; pi: fileContent === null → clear cache + error text
               (let [set-state! (:set-state! context)]
                 (when set-state!
                   (set-state! (assoc (:state context) :write-cache nil)))
                 [title (tool-text (str "\n\n"
                                        (theme/fg theme :error "[invalid content arg - expected string]")))])
               (if-not (seq content)
                 [title]
                 (let [state (:state context)
                       set-state! (:set-state! context)
                       ;; pi: argsComplete → full rebuild; streaming → incremental
                       cache (if (:args-complete context)
                               (write-highlight-cache theme raw-path content)
                               (update-write-highlight-cache theme (:write-cache state) raw-path content))
                       _ (when set-state!
                           (set-state! (assoc state :write-cache cache)))
                       lines (write-rendered-lines theme cache content)
                       total (count lines)
                       {:keys [visual-lines consumed]}
                       (if (:expanded context)
                         {:visual-lines lines :consumed total}
                         (bounded-head-visual-lines lines write-preview-lines width))
                       ;; :consumed counts lines shown in full, so :remaining
                       ;; includes a line cut mid-wrap — the hint's budget is
                       ;; "not fully shown" lines, matching the logical total
                       remaining (- total consumed)]
                   (into [title [:spacer {:lines 1}] [:spacer {:lines 1}]]
                         (concat
                          (tool-text-lines visual-lines)
                          (when (pos? remaining)
                            [(tool-text (expand-hint theme remaining))]))))))]
    (h/compile-tree (into [:container {}] kids))))

(defn render-write-result
  [content is-error theme _width _expanded? & _]
  (when is-error
    (h/compile-tree (tool-text (str "\n" (theme/fg theme :error content))))))

;; ─── Write highlight cache (pi: write.ts WriteHighlightCache) ─────────────
;; Streaming-safe: on partial tool calls, only the appended delta is
;; re-highlighted; the first WRITE-PARTIAL-FULL-HIGHLIGHT-LINES are refreshed
;; so multi-line tokens (strings, comments) spanning the cut get their scope
;; fixed once context arrives. State lives in the tool-execution :state atom
;; (pi: the cache field on WriteCallRenderComponent).

(def ^:private write-partial-full-highlight-lines 50)

(defn- write-highlight-cache
  "Pi: rebuildWriteHighlightCacheFull — fresh cache, nil when the path has no
   supported language. Stores raw path/content for delta detection, normalized
   (CR-stripped, tab-expanded) source lines and their highlighted versions."
  [theme raw-path file-content]
  (when-let [lang (lang-from-path raw-path)]
    (let [display (normalize-display-text file-content)
          normalized (replace-tabs display)]
      {:lang lang
       :raw-path raw-path
       :raw-content file-content
       :normalized-lines (str/split normalized #"\n" -1)
       :highlighted-lines (theme/render-highlighted theme normalized lang)})))

(defn- refresh-write-highlight-prefix
  "Pi: refreshWriteHighlightPrefix — re-highlight the first 50 normalized
   lines as a block so multi-line tokens crossing the append cut recover
   their scope. When the block highlight misses a line (rare), fall back to
   a single-line highlight of that line (pi: highlightSingleLine)."
  [theme cache]
  (let [prefix-count (min write-partial-full-highlight-lines (count (:normalized-lines cache)))]
    (if (pos? prefix-count)
      (let [prefix-source (str/join "\n" (take prefix-count (:normalized-lines cache)))
            prefix-highlighted (theme/render-highlighted theme prefix-source (:lang cache))
            nls (:normalized-lines cache)]
        (assoc cache :highlighted-lines
               (mapv (fn [i]
                       (let [h (nth prefix-highlighted i nil)]
                         (if (seq h)
                           h
                           (or (first (theme/render-highlighted theme (nth nls i "") (:lang cache))) ""))))
                     (range prefix-count))))
      cache)))

(defn- update-write-highlight-cache
  "Pi: updateWriteHighlightCacheIncremental — when the cache is missing,
   stale (path/lang changed) or the content is not a prefix of the cached
   raw content, rebuild. Otherwise append the delta: the first delta segment
   merges into the last existing line (guarded for an empty cache, pi pushes
   a blank line first), later segments become new lines, each highlighted
   individually; then the prefix block is refreshed."
  [theme cache raw-path file-content]
  (if-let [lang (lang-from-path raw-path)]
    (if (or (nil? cache)
            (not= (:lang cache) lang)
            (not= (:raw-path cache) raw-path)
            (not (str/starts-with? file-content (:raw-content cache))))
      (write-highlight-cache theme raw-path file-content)
      (let [raw-len (count (:raw-content cache))
            delta (subs file-content raw-len)]
        ;; pi: fileContent.length === cache.rawContent.length → no-op
        (if (= (count file-content) raw-len)
          cache
          (let [segments (-> delta
                             normalize-display-text
                             replace-tabs
                             (str/split #"\n" -1))
                nls (:normalized-lines cache)
                hls (:highlighted-lines cache)
                ;; pi: empty cache gets a blank line pushed before merging
                [nls hls] (if (seq nls)
                            [nls hls]
                            [[""] [""]])
                last-idx (dec (count nls))
                merged (str (nth nls last-idx) (first segments))
                merged-hl (or (first (theme/render-highlighted theme merged lang)) "")
                new-lines (rest segments)
                new-hls (mapv (fn [l]
                                (or (first (theme/render-highlighted theme l lang)) ""))
                              new-lines)
                nls' (into (conj (pop (vec nls)) merged) new-lines)
                hls' (into (conj (pop (vec hls)) merged-hl) new-hls)]
            (refresh-write-highlight-prefix
             theme
             (assoc cache
                    :raw-content file-content
                    :normalized-lines nls'
                    :highlighted-lines hls'))))))
    nil))

(defn- write-rendered-lines
  "Pi: formatWriteCall's renderedLines — highlighted lines when the cache
   has a language; else plain lines in toolOutput color (pi colors the
   no-lang fallback with theme.fg('toolOutput', ...)). Trailing empty lines
   are trimmed BEFORE coloring so the trim sees raw strings."
  [theme cache file-content]
  (if (:lang cache)
    (trim-trailing-empty-lines (:highlighted-lines cache))
    (->> (str/split-lines (replace-tabs (normalize-display-text file-content)))
         trim-trailing-empty-lines
         (mapv #(theme/fg theme :tool-output %)))))

(defn- recorded-edit-preview
  "The result-recorded edit diff as a preview map, or nil when the result
   carries none. The recorded diff is authoritative over the call-time
   filesystem preview (pi: updateResult replaces the preview with the actual
   diff) — a replayed edit renders the real diff on its first pass instead
   of computing a preview from the file's current content and correcting it
   one frame later."
  [context]
  (when-let [diff (get-in context [:details :diff])]
    {:success? true
     :diff diff
     :diff-lines (vec (str/split-lines diff))}))

(defn render-edit-call
  [name args theme _width context]
  (let [name (if (seq name) name "edit")
        raw-path (:file_path args (:path args))
        edits (normalize-edit-args args)
        args-key (str raw-path "|" (pr-str edits))
        state (:state context)
        set-state! (:set-state! context)
        args-changed? (not= args-key (:edit-args-key state))
        state (if args-changed?
                (let [s' (-> state
                             (assoc :edit-args-key args-key)
                             (dissoc :edit-preview))]
                  (when set-state! (set-state! s'))
                  s')
                state)
        recorded (recorded-edit-preview context)
        ;; An errored result has no diff to be corrected by, and the preview
        ;; cannot succeed where the tool failed: computing it from today's
        ;; file only re-derives the failure on a replayed call (the session no
        ;; longer owns that file). A cached preview from the live pass is
        ;; still honored above — the result side dedups against it.
        result-error? (:is-error context)
        preview (cond
                  ;; A finished result's diff wins over the call-time preview:
                  ;; it is exactly what render-edit-result installs a frame
                  ;; later, so seeding it here removes that settle frame (and
                  ;; the filesystem preview computation it was correcting).
                  recorded
                  (do (when (and set-state! (not= recorded (:edit-preview state)))
                        (set-state! (assoc state :edit-preview recorded)))
                      recorded)
                  (contains? state :edit-preview) (:edit-preview state)
                  result-error? nil
                  (and (:args-complete context)
                       (renderable-edit-input raw-path edits))
                  (let [p (edit-preview raw-path (:cwd context) edits)]
                    (when set-state! (set-state! (assoc state :edit-preview p)))
                    p)
                  :else nil)]
    (build-edit-box name preview raw-path theme (:cwd context) context)))
(defn render-edit-result
  [content is-error theme _width _expanded? _started-at _ended-at _truncation context]
  (let [state (:state context)
        set-state! (:set-state! context)
        preview-error (:error (:edit-preview state))]
    (if is-error
      (if (= content preview-error)
        nil
        ;; pi: new Text(content, 1, 0) — indented one column, on a spacer
        (h/compile-tree
         [:container {}
          [:spacer {:lines 1}]
          [:text {:padding-x 1 :padding-y 0} (theme/fg theme :error content)]]))
      (let [result-diff (get-in context [:details :diff])
            preview (:edit-preview state)
            preview-diff (when (and preview (:success? preview)) (:diff preview))
            clear-preview? (and (not (:is-partial context))
                                (not is-error)
                                preview
                                (not (:success? preview)))
            corrected-preview (recorded-edit-preview context)
            next-preview (cond
                           (and corrected-preview (not= result-diff preview-diff))
                           corrected-preview

                           clear-preview?
                           nil

                           :else ::unchanged)]
        (when (not= next-preview ::unchanged)
          (when set-state!
            (if next-preview
              (set-state! (assoc state :edit-preview next-preview))
              (set-state! (dissoc state :edit-preview))))
          (when-let [invalidate (:invalidate context)]
            (invalidate)))
        nil))))
(def ^:private bash-result-preview-lines
  "Collapsed cap on the rendered output, in visual lines — a wrapped line
   counts once, and the expand hint reports the rest."
  5)

(defn shell-call-renderer
  "Build the shared call renderer for a shell tool with PROMPT (`$` for bash,
   `PS>` for PowerShell — pi: createShellRenderers(prompt)). The returned fn
   has the standard (name args theme width context) render-call signature:
   `PROMPT <command>` (+ timeout suffix), rendered verbatim whatever the
   display mode (pi: the call line never truncates; Text wraps it at the
   width)."
  [prompt]
  (fn [_name args theme _width _context]
    (let [cmd (:command args)
          timeout (:timeout args)
          cmd-str (if (string? cmd) cmd (if (nil? cmd) "" nil))
          cmd-display (cond
                        (nil? cmd-str) (theme/fg theme :error "[invalid arg]")
                        (empty? cmd-str) (theme/fg theme :tool-output "...")
                        :else cmd-str)
          cmd-line (theme/fg theme :tool-title (theme/bold (str prompt " " cmd-display)))
          timeout-suffix (if (and (number? timeout) (pos? timeout))
                           (theme/fg theme :muted (str " (timeout " timeout "s)"))
                           "")]
      (h/compile-tree (tool-text (str cmd-line timeout-suffix))))))

(def render-bash-call
  "Call line for the bash tool: `$ <command>` (+ timeout suffix) — the shared
   shell-call renderer with bash's prompt."
  (shell-call-renderer "$"))

(defn- output-result-nodes
  "The output body (collapsed to a visual-line window with an expand hint,
   verbatim when expanded) and the truncation warning, as hiccup nodes. The
   runtime appends its own truncation footer naming the full-output file; the
   renderer strips it and rebuilds the information as its own warn line (pi:
   strip the trailing [...] block)."
  [content theme width expanded? ended-at truncation]
  (let [full-output-path (:full-output-path truncation)
        output (let [trimmed (str/trim (or content ""))]
                 (if (and truncation
                          (some? ended-at)
                          (str/ends-with? trimmed "]"))
                   (let [footer-start (str/last-index-of trimmed "\n\n[")]
                     (if (and footer-start
                              (let [footer (subs trimmed footer-start)]
                                (or (and full-output-path
                                         (str/includes? footer full-output-path))
                                    ;; the run_code tool's footer names the
                                    ;; truncation instead of a spill file
                                    (str/includes? (str/lower-case footer) "truncat"))))
                       (str/trimr (subs trimmed 0 footer-start))
                       trimmed))
                   trimmed))]
    (concat
     (when (seq output)
       (let [styled (->> (str/split-lines output)
                         (mapv #(theme/fg theme :tool-output %))
                         (str/join "\n"))]
         (if expanded?
           (concat [[:spacer {:lines 1}]]
                   [(tool-text styled)])
           (let [{:keys [visual-lines skipped-count]}
                 (utils/truncate-to-visual-lines styled
                                                 bash-result-preview-lines
                                                 width)]
             (concat
              [[:spacer {:lines 1}]]
              (tool-text-lines visual-lines)
              (when (pos? skipped-count)
                [(tool-text
                  (utils/truncate-to-width
                   (expand-hint theme skipped-count)
                   width
                   "..."))]))))))
     (when truncation
       (let [{:keys [total-lines shown-lines truncated-by max-bytes]} truncation
             size-str (when (= truncated-by :bytes)
                        (bash-exec/format-size
                         (or max-bytes bash-exec/DEFAULT-MAX-BYTES)))
             truncated-part (if (= truncated-by :bytes)
                              (str "Truncated: " shown-lines " lines shown ("
                                   size-str " limit)")
                              (str "Truncated: showing " shown-lines " of "
                                   total-lines " lines"))
             warn (str "["
                       (str/join ". "
                                 (cond-> []
                                   full-output-path
                                   (conj (str "Full output: " full-output-path))
                                   :always
                                   (conj truncated-part)))
                       "]")]
         [[:spacer {:lines 1}]
          (tool-text (theme/fg theme :warning warn))])))))

(defn state-result-nodes
  "The tool block's tail state line — the ONLY line of a block that may
   change after it was painted. The box body keeps the constant
   :tool-pending-bg (option 1), so a settle can never rewrite a line above
   the window; the state rides this line's foreground color instead: muted
   while running, the default color once the result arrived, error (+ a
   (!) marker) on failure, with the elapsed/took duration as the text.

   MEASURED-MS — the tool's own recorded duration (run_code's :details
   :elapsed-ms) — wins over the component timestamp span when given. Nil
   when there is nothing to say: a replayed success has no started-at (pi
   renders replayed tools without a duration) and no error; a replayed
   error still gets its (!) marker. The component calls this for a tool
   whose definition opted in with :status, or when both renderers produced
   nothing — the tail is then the block's only presence (see
   tool-execution)."
  [theme started-at ended-at is-error & [measured-ms]]
  (let [elapsed-ms (or measured-ms
                       (when started-at
                         (- (or ended-at (System/currentTimeMillis)) started-at)))
        done? (or (some? ended-at) (some? measured-ms))
        text (str (when is-error "(!) ")
                  (when elapsed-ms
                    (str (if done? "Took" "Elapsed") " "
                         (format "%.1f" (float (/ (max 0 elapsed-ms) 1000))) "s")))
        fg-key (cond is-error :error
                     done? nil
                     :else :muted)]
    (when (seq text)
      (h/compile-tree
       [:container {}
        [:spacer {:lines 1}]
        [:text {:padding-x 0 :padding-y 0}
         (if fg-key (theme/fg theme fg-key text) text)]]))))

(defn render-bash-result
  "Result body for the shell tool: the output (collapsed to a visual-line
   window with an expand hint, verbatim when expanded) and the truncation
   warning. The elapsed/took state line is the tool component's tail line
   (state-result-nodes), not the renderer's — see tool-execution."
  [content _is-error theme width expanded? _started-at ended-at truncation _context]
  (h/compile-tree
   (into [:container {}]
         (output-result-nodes content theme width expanded? ended-at truncation))))

(defn render-code-call
  "The shared call line for tools whose primary argument is code (run_code,
   clojure_eval): `<label> <code>` rendered verbatim, whatever the display
   mode (no head window, no expand hint — the bash call line's policy).
   LABEL is plain text (styled bold tool-title); SUFFIX is appended as
   given (pre-styled)."
  [label code suffix theme _width _context]
  (let [code-str (if (string? code) code (if (nil? code) "" nil))
        code-display (cond
                       (nil? code-str) (theme/fg theme :error "[invalid arg]")
                       (empty? code-str) (theme/fg theme :tool-output "...")
                       :else code-str)
        code-line (theme/fg theme :tool-title
                            (theme/bold (str label " " code-display)))]
    (h/compile-tree (tool-text (str code-line suffix)))))

(defn render-run-code-call
  "Call line for the run_code tool: `run_code <code>` (+ an explicit timeout
   suffix), via the shared code-call renderer. The quiet title mirrors the
   one-line head of the same call."
  [_name args theme width context]
  (let [timeout (:timeout args)
        suffix (if (and (number? timeout) (pos? timeout))
                 (theme/fg theme :muted (str " (" timeout "s)"))
                 "")]
    (render-code-call "run_code" (:code args) suffix theme width context)))

(defn- run-code-calls-nodes
  "One muted summary line for the run_code tool's inner-call trace (details
   :calls): the tool work the run_code call did is otherwise invisible in the
   transcript. Failed/incomplete entries are counted."
  [context theme]
  (let [calls (seq (get-in context [:details :calls]))]
    (when calls
      (let [counts (frequencies (map :tool calls))
            summary (str/join ", "
                              (map (fn [n]
                                     (let [c (get counts n)]
                                       (if (> c 1) (str n " ×" c) n)))
                                   (sort (keys counts))))
            failed (count (remove :ok calls))
            total (count calls)]
        [[:spacer {:lines 1}]
         (tool-text
          (theme/fg theme :muted
                    (str total " tool call" (when (> total 1) "s")
                         ": " summary
                         (when (pos? failed) (str ", " failed " failed")))))]))))

(defn render-run-code-result
  "Result body for the run_code tool: the shell-style body (output preview,
   truncation warning) plus the inner-call summary line. The elapsed/took
   state line — the tool's own measured time when the result carries it —
   is the tool component's tail line (state-result-nodes)."
  [content _is-error theme width expanded? _started-at ended-at truncation context]
  (h/compile-tree
   (into [:container {}]
         (concat (output-result-nodes content theme width expanded? ended-at truncation)
                 (run-code-calls-nodes context theme)))))

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn render-default-call
  "Default render-call: show tool name bolded in tool-title color,
   followed by a compact representation of the args.  Truncation is
   column-width-aware (pi: truncateToWidth) so wide CJK/emoji glyphs
   are never split or overcounted."
  [name args theme width & [_context]]
  (let [title (theme/fg theme :tool-title (theme/bold name))
        title-width (+ (utils/visible-width name) 2) ;; name + space
        avail (- width title-width)
        param-str (when (and (pos? avail) (map? args) (seq args))
                    (let [values (map (comp pr-str val) args)
                          joined (str/join " " values)]
                      (utils/truncate-to-width joined avail "...")))]
    (h/compile-tree
     (tool-text
      (if (seq param-str)
        (str title " " param-str)
        title)))))

(defn render-default-result
  "Default render-result: show collapsed preview (5 lines) with expand hint,
   full content when expanded. The preview cap counts VISUAL lines at the
   render width — wrap first, then take the head — because the Text component
   wraps long lines at render time, so a logical-line cap would let a few
   long lines fill the transcript. The bounded wrap stops at the cap (hidden
   lines are never wrapped); the hint reports the logical lines not shown in
   full. (Pi's fallback slices logical lines, so this deliberately diverges
   on the cap.)"
  [content _is-error theme width expanded? & _]
  (let [lines (-> (or content "") str/split-lines trim-trailing-empty-lines)
        total (count lines)
        {:keys [visual-lines consumed]}
        (if expanded?
          {:visual-lines lines :consumed total}
          (bounded-head-visual-lines lines 5 width))
        remaining (- total consumed)
        kids (concat
              (tool-text-lines (mapv #(theme/fg theme :tool-output %) visual-lines))
              (when (pos? remaining)
                [(tool-text (expand-hint theme remaining))]))]
    (h/compile-tree (into [:container {} [:spacer {:lines 1}]] kids))))

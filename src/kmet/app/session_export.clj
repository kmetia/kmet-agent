(ns kmet.app.session-export
  "Standalone HTML export of a kmet session (pi: export-html — G22 /export).
   EDN entries render to a self-contained dark page: user/assistant/tool/
   bash messages, thinking, tool calls, compaction and branch summaries,
   custom/custom-message entries, model/thinking changes, and the session
   name. No JS, no external assets — the file opens anywhere. JSONL export
   is deliberately not built (no pi interop, §0 of the session plan)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.session :as session]))

;; ─── HTML escaping ─────────────────────────────────────────────────────────

(defn- escape-html
  "Escape text for safe embedding in HTML (pi: escapeHtml)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- escape-pre
  "Escape text and normalize line breaks for a <pre> block."
  [s]
  (str/replace (escape-html s) "\n" "<br>"))

;; ─── Content extraction ────────────────────────────────────────────────────

(defn- content-text
  "Plain text of an entry's :content — a string or a vector of blocks
   (:text, :tool_result, :thinking — pi: extractTextContent)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      content
      (str/join "\n"
                (for [b content
                      :let [t (case (:type b)
                                :tool_result (get-in b [:content] "")
                                (:text :thinking) (:text b)
                                "")]]
                  (str t))))))

(defn- image-html
  "Render an image content block as a self-contained base64 data-URI <img>
   (pi: export-html renderResultImages / message-images — the export embeds
   the payload so the file opens anywhere). CLASS is the pi class name
   (message-image | tool-image). Blocks without :data (malformed) render as
   the text placeholder instead, so a broken block never emits a broken
   image."
  [b class]
  (if (seq (:data b))
    (str "<img src=\"data:" (escape-html (or (:mime-type b) "image/png"))
         ";base64," (escape-html (:data b))
         "\" class=\"" class "\">")
    (str "<div class=\"image\">[image: " (escape-html (or (:mime-type b) "unknown"))
         (when-let [name (:name b)] (str " " (escape-html name)))
         "]</div>")))

(defn- content->html
  "Render an entry's content blocks: text blocks inline, images as inline
   base64 <img> elements (pi: message-images), tool results in a nested
   block."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str "<pre>" (escape-pre content) "</pre>")
      (str/join
       (for [b content]
         (case (:type b)
           :text (str "<pre>" (escape-pre (:text b)) "</pre>")
           :image (image-html b "message-image")
           :thinking (str "<details class=\"thinking\"><summary>Thinking</summary><pre>"
                          (escape-pre (:text b)) "</pre></details>")
           :tool_result (str "<div class=\"tool-result\"><pre>" (escape-pre (str (:content b)))
                             "</pre></div>")
           ""))))))

(defn- tool-calls->html
  "Render an assistant entry's :tool-calls vector."
  [tool-calls]
  (when (seq tool-calls)
    (str "<div class=\"tool-calls\">"
         (str/join
          (for [tc tool-calls]
            (str "<div class=\"tool-call\"><span class=\"tool-name\">"
                 (escape-html (name (or (:name tc) (:tool-name tc) "tool")))
                 "</span>"
                 (when-let [args (:arguments tc)]
                   (str "<pre class=\"tool-args\">" (escape-pre (pr-str args)) "</pre>"))
                 "</div>")))
         "</div>")))

;; ─── Entry rendering ───────────────────────────────────────────────────────

(defn- role-label
  "Display label for an entry role (pi: export-html role labels)."
  [role]
  (case role
    :user "User"
    :assistant "Assistant"
    :tool "Tool Result"
    :bash "Bash"
    :system "System"
    :compaction "Compaction"
    :branch-summary "Branch Summary"
    :custom "Custom"
    :custom-message "Custom Message"
    :model-change "Model"
    :thinking-level-change "Thinking Level"
    :session-info "Session Info"
    :info "Info"
    (str (name role))))

(defn- summary-text
  "Plain text of a compaction/branch-summary entry's :summary."
  [e]
  (str (:summary e "")))

(defn- tool-images->html
  "Render a tool entry's image blocks as inline base64 <img> elements
   (pi: export-html renderResultImages — the tool result's images, before
   the output text)."
  [e]
  (when-let [images (seq (:images e))]
    (str "<div class=\"tool-images\">"
         (apply str (map #(image-html % "tool-image") images))
         "</div>")))

(defn- entry->html
  "Render a single session entry as an HTML <div class=\"entry\"> block.
   :label entries are skipped (bookkeeping, not conversation content)."
  [e]
  (let [role (:role e)]
    (when-not (= :label role)
      (let [label (role-label role)
            header (cond
                     (= :model-change role)
                     (str "[model: " (escape-html (name (:provider e))) "/" (escape-html (:model e)) "]")
                     (= :thinking-level-change role)
                     (str "[thinking: " (escape-html (name (:thinking-level e))) "]")
                     (= :custom role)
                     (str "[custom: " (escape-html (name (:custom-type e))) "]")
                     (= :custom-message role)
                     (str "[custom: " (escape-html (name (:custom-type e))) "]")
                     (= :bash role)
                     (str "$ " (escape-html (:command e)))
                     (= :tool role)
                     (str "→ " (escape-html (or (:tool-name e) "tool")))
                     :else nil)]
        (str "<div class=\"entry " (name role) "\">"
             "<div class=\"role\">" (escape-html label)
             (when (seq header) (str " <span class=\"header\">" header "</span>"))
             "</div>"
             (when (= :assistant role) (tool-calls->html (:tool-calls e)))
             (when (and (= :assistant role) (seq (:thinking e)))
               (str "<details class=\"thinking\"><summary>Thinking</summary><pre>"
                    (escape-pre (:thinking e)) "</pre></details>"))
             (when (contains? #{:compaction :branch-summary} role)
               (str "<pre>" (escape-pre (summary-text e)) "</pre>"))
             (when (contains? #{:user :assistant :system :info :custom :custom-message} role)
               (content->html e))
             (when (= :bash role)
               (str "<pre class=\"bash-output\">" (escape-pre (:output e ""))
                    (when (:exit-code e) (str "\n[exit " (:exit-code e) "]"))
                    (when (:truncated e) "\n[truncated]")
                    "</pre>"))
             (when (= :tool role)
               (str (tool-images->html e)
                    "<pre class=\"tool-output\">" (escape-pre (content-text e)) "</pre>"))
             (when (= :session-info role)
               (str "<div class=\"session-name\">" (escape-html (or (:name e) "")) "</div>"))
             "</div>")))))

;; ─── Full document ─────────────────────────────────────────────────────────

(defn- format-tokens
  "Pi: formatTokens — 999 → \"999\", 1234 → \"1.2k\", 1234567 → \"1.2M\"."
  [n]
  (let [n (long n)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (str (format "%.1f" (/ (double n) 1000.0)) "k")
      (< n 1000000) (str (Math/round (double (/ n 1000))) "k")
      (< n 10000000) (str (format "%.1f" (/ (double n) 1000000.0)) "M")
      :else (str (Math/round (double (/ n 1000000))) "M"))))

(defn- models-in-session
  "Unique model keys in order of first appearance (pi: computeStats models)."
  [entries]
  (->> entries
       (filter #(= :model-change (:role %)))
       (map (fn [e] (str (name (:provider e)) "/" (:model e))))
       distinct
       vec))

(defn- tool-params->html
  "Render a tool's JSON-schema :parameters as name/description lines (pi:
   renderTool — param type + required marker)."
  [params]
  (when (and params (seq (:properties params)))
    (let [required (set (:required params))]
      (apply str
             (for [[pname p] (:properties params)]
               (str "<div class=\"tool-param\">"
                    (escape-html (name pname))
                    (when (contains? required (name pname)) " (required)")
                    " — " (escape-html (or (:description p) ""))
                    "</div>"))))))

(defn- tool->html
  "Render one tool definition (pi: tools-list tool-item)."
  [t]
  (str "<div class=\"tool-item\"><span class=\"tool-name\">" (escape-html (:name t))
       "</span> — <span class=\"tool-desc\">" (escape-html (or (:description t) "")) "</span>"
       (tool-params->html (:parameters t))
       "</div>"))

(def ^:private page-css
  "body{background:#1e1e2e;color:#cdd6f4;font-family:ui-monospace,Menlo,Consolas,monospace;margin:0;padding:2rem;line-height:1.5}
   h1{color:#89b4fa;font-size:1.3rem}
   .meta{color:#6c7086;margin-bottom:1.5rem}
   .entry{margin:0 0 1rem;padding:.6rem .9rem;border:1px solid #313244;border-radius:6px;background:#181825}
   .entry.user{border-left:3px solid #89b4fa}
   .entry.assistant{border-left:3px solid #a6e3a1}
   .entry.tool,.entry.bash{border-left:3px solid #f9e2af}
   .entry.compaction,.entry.branch-summary{border-left:3px solid #f38ba8;opacity:.85}
   .entry.custom,.entry.custom-message{border-left:3px solid #cba6f7}
   .role{color:#89b4fa;font-weight:bold;margin-bottom:.3rem}
   .header{color:#6c7086;font-weight:normal}
   pre{white-space:pre-wrap;word-wrap:break-word;margin:.2rem 0;font-family:inherit}
   .bash-output,.tool-output,.tool-args{color:#a6adc8;background:#11111b;padding:.4rem;border-radius:4px}
   .tool-calls{margin:.3rem 0}
   .tool-call{margin:.2rem 0}
   .tool-name{color:#f9e2af}
   .thinking{margin:.3rem 0}
   .thinking summary{color:#6c7086;cursor:pointer}
   .thinking pre{color:#a6adc8}
   .image,.session-name{color:#cba6f7}
   .message-image,.tool-image{display:block;max-width:min(100%,40rem);max-height:24rem;margin:.3rem 0;border-radius:4px}
   .tool-images{margin:.3rem 0}
   details.system-prompt,details.tools{margin:0 0 1rem;padding:.6rem .9rem;border:1px solid #313244;border-radius:6px;background:#181825}
   details.system-prompt summary,details.tools summary{color:#89b4fa;font-weight:bold;cursor:pointer}
   details.system-prompt pre{color:#a6adc8;background:#11111b;padding:.4rem;border-radius:4px}
   .tool-item{margin:.3rem 0}
   .tool-param{margin:.1rem 0 .1rem 1.2rem;color:#a6adc8}
   .tool-desc{color:#a6adc8}")

(defn session->html
  "Render the full standalone HTML document for a session (pi:
   generateHtml — header meta + stats panel + system prompt + tools + one
   block per entry). OPTS: :system-prompt (string), :tools (seq of
   {:name :description :parameters})."
  [session & [{:keys [system-prompt tools]}]]
  (let [header (:header session)
        entries @(:entries session)
        name (session/get-session-name session)
        stats (session/get-session-stats session)
        models (models-in-session entries)
        msg-parts (cond-> []
                    (pos? (:user-messages stats)) (conj (str (:user-messages stats) " user"))
                    (pos? (:assistant-messages stats)) (conj (str (:assistant-messages stats) " assistant"))
                    (pos? (:tool-results stats)) (conj (str (:tool-results stats) " tool results")))
        {:keys [input output cache-read cache-write]} (:tokens stats)
        token-parts (cond-> []
                      (pos? input) (conj (str "↑" (format-tokens input)))
                      (pos? output) (conj (str "↓" (format-tokens output)))
                      (pos? cache-read) (conj (str "R" (format-tokens cache-read)))
                      (pos? cache-write) (conj (str "W" (format-tokens cache-write))))]
    (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "<title>kmet session"
         (when name (str " — " (escape-html name)))
         "</title>\n<style>" page-css "</style>\n</head>\n<body>\n"
         "<h1>Session"
         (when name (str ": " (escape-html name)))
         "</h1>\n"
         "<div class=\"meta\">"
         (when header
           (str "id: " (escape-html (:id header))
                (when (:cwd header) (str " · cwd: " (escape-html (:cwd header))))
                (when (:created-at header) (str " · created: " (escape-html (:created-at header))))
                (when (:parent-session header) (str " · parent: " (escape-html (:parent-session header))))))
         (when (seq models) (str "<br>Models: " (escape-html (str/join ", " models))))
         (when (seq msg-parts) (str "<br>Messages: " (escape-html (str/join ", " msg-parts))))
         "<br>Tool Calls: " (:tool-calls stats)
         (when (seq token-parts) (str "<br>Tokens: " (escape-html (str/join " " token-parts))))
         "<br>Cost: $" (format "%.3f" (:cost stats))
         "</div>\n"
         (when system-prompt
           (str "<details class=\"system-prompt\"><summary>System Prompt</summary><pre>"
                (escape-pre system-prompt) "</pre></details>\n"))
         (when (seq tools)
           (str "<details class=\"tools\"><summary>Available Tools</summary>"
                (apply str (map tool->html tools))
                "</details>\n"))
         (str/join "\n" (keep entry->html entries))
         "\n</body>\n</html>\n")))

(defn default-export-path
  "Default HTML output path for a session (pi: exportSessionToHtml's
   <app>-session-<basename>.html): in CWD, or the process cwd without one.
   The caller passes the session's runtime working directory, so a session
   resumed or imported from another project exports into that project's
   directory — where its tools and the user's shell work (pi resolves the
   default against its process cwd; kmet's runtime cwd is the session's)."
  ([session] (default-export-path session (str (fs/cwd))))
  ([session cwd]
   (let [basename (-> (:file session) fs/file-name (str/replace #"\.ednl$" ""))]
     (str (fs/path cwd (str "kmet-session-" basename ".html"))))))

(defn export-to-html!
  "Write the session's HTML export to PATH (default: default-export-path in
   the runtime working directory from the :cwd opt). OPTS: :path, :cwd (the
   runtime working directory the default path resolves against, e.g. the
   session's), :system-prompt (string), :tools (seq of tool defs) — pi:
   exportSessionToHtml(state) embeds the system prompt and tool definitions.
   Creates parent dirs; returns the written path. Throws when the session
   has no file or the file does not exist yet (lazy creation — nothing to
   export)."
  [session & [{:keys [path cwd system-prompt tools]}]]
  (let [file (:file session)]
    (when (or (nil? file) (not (fs/exists? file)))
      (throw (ex-info "Nothing to export yet - start a conversation first"
                      {:type :export-error :path file})))
    (let [out (or path (default-export-path session (or cwd (str (fs/cwd)))))]
      (fs/create-dirs (fs/parent out))
      (spit out (session->html session {:system-prompt system-prompt :tools tools}))
      (str out))))

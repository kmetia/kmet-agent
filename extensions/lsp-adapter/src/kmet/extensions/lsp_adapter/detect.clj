(ns kmet.extensions.lsp-adapter.detect
  "Language detection + server registry + workspace-root resolution for the
   lsp-adapter extension (plan §7, simplified per Rev 2).

   Detection answers three questions for a touched file — which languageId,
   which server(s) claim it, which workspace root — as pure data over the
   builtin registry reshaped by user config. Only the final spawn (runtime)
   touches processes.

   Simplifications vs the original plan: no rust `[workspace]` special case
   (rust-analyzer copes rooted at the nearest Cargo.toml), and files outside
   cwd attach at their own directory instead of walking to home."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; ─── Path → languageId ────────────────────────────────────────────────────

(def language-ids
  "Lowercased extension (dot-less) → LSP languageId. Every extension any
   builtin server claims MUST appear here — a claimed-but-unmapped
   extension opens as plaintext and the server silently answers nothing."
  {"clj" "clojure" "cljs" "clojure" "cljc" "clojure" "edn" "clojure" "bb" "clojure"
   "ts" "typescript" "tsx" "typescriptreact" "mts" "typescript" "cts" "typescript"
   "js" "javascript" "jsx" "javascriptreact" "mjs" "javascript" "cjs" "javascript"
   "py" "python" "pyi" "python"
   "go" "go"
   "rs" "rust"
   "c" "c" "h" "c" "cc" "cpp" "cpp" "cpp" "cxx" "cpp" "ixx" "cpp"
   "hpp" "cpp" "hxx" "cpp" "hh" "cpp"
   "m" "objective-c" "mm" "objective-cpp"
   "java" "java"
   "rb" "ruby" "rake" "ruby" "gemspec" "ruby"
   "sh" "shellscript" "bash" "shellscript" "zsh" "shellscript"
   "json" "json" "yaml" "yaml" "yml" "yaml" "toml" "toml"
   "md" "markdown" "html" "html" "css" "css" "scss" "scss"
   "nix" "nix" "zig" "zig" "swift" "swift" "cmake" "cmake"})

(def filename-language-ids
  "Extension-less filenames that are themselves language markers."
  {"makefile" "makefile" "dockerfile" "dockerfile" "cmakelists.txt" "cmake"})

(defn language-id
  "LSP languageId for PATH; \"plaintext\" fallback so didOpen always
   carries a well-formed payload."
  [path]
  (let [name (str/lower-case (str (fs/file-name path)))
        ext (when-let [i (str/last-index-of name ".")]
              (subs name (inc i)))]
    (or (and ext (language-ids ext))
        (filename-language-ids name)
        "plaintext")))

;; ─── Builtin registry ─────────────────────────────────────────────────────

(def ^:private builtin-descriptors
  "Static descriptors, best-effort per entry: a missing binary surfaces in
   status, nothing crashes. Earlier entries win ties when two servers claim
   the same extension."
  [{:id "clojure-lsp"
    :command ["clojure-lsp"]
    :extensions #{"clj" "cljs" "cljc" "edn" "bb"}
    :root-markers ["deps.edn" "project.clj" "shadow-cljs.edn" "bb.edn"
                   ".clj-kondo"]}
   {:id "typescript-language-server"
    :command ["typescript-language-server" "--stdio"]
    :extensions #{"ts" "tsx" "mts" "cts" "js" "jsx" "mjs" "cjs"}
    :root-markers ["package.json" "tsconfig.json" "jsconfig.json"
                   "package-lock.json" "pnpm-lock.yaml" "yarn.lock"
                   "bun.lock" "bun.lockb"]
    :exclude-root-markers ["deno.json" "deno.jsonc"]}
   {:id "pyright-langserver"
    :command ["pyright-langserver" "--stdio"]
    :extensions #{"py" "pyi"}
    :root-markers ["pyproject.toml" "setup.py" "setup.cfg" "requirements.txt"
                   "pyrightconfig.json" "Pipfile"]}
   {:id "rust-analyzer"
    :command ["rust-analyzer"]
    :extensions #{"rs"}
    :root-markers ["Cargo.toml"]}
   {:id "gopls"
    :command ["gopls" "serve"]
    :extensions #{"go"}
    :root-markers ["go.mod" "go.work"]}
   {:id "clangd"
    :command ["clangd"]
    :extensions #{"c" "cc" "cpp" "cxx" "ixx" "h" "hh" "hpp" "hxx" "m" "mm"}
    :root-markers ["compile_commands.json" ".clangd" "CMakeLists.txt"
                   "Makefile" "meson.build"]}
   {:id "ruby-lsp"
    :command ["ruby-lsp"]
    :extensions #{"rb" "rake" "gemspec"}
    :root-markers ["Gemfile" "Rakefile" ".rubocop.yml" "config.ru"]}
   {:id "bash-language-server"
    :command ["bash-language-server" "start"]
    :extensions #{"sh" "bash" "zsh"}
    :rootless true}
   {:id "jdtls"
    :command ["jdtls"]
    :extensions #{"java"}
    :root-markers ["pom.xml" "build.gradle" "build.gradle.kts"
                   "settings.gradle" "settings.gradle.kts"]}])

;; ─── Effective registry (config reshapes builtins) ────────────────────────

(defn- as-argv
  "User :command as full argv vector: string → [string], vector kept,
   :args appended."
  [{:keys [command args]}]
  (when command
    (into (if (vector? command) command [command]) args)))

(defn effective-servers
  "Builtin registry reshaped by CFG-SERVERS (the :servers map from
   .kmet/lsp.edn):

     same-id entry  — :command/:args override argv; :extensions replaces
                      the claimed list; :extend-extensions appends;
                      :root-markers/:exclude-root-markers/:rootless replace
                      when present; :disabled true removes the builtin
     unknown id     — becomes a custom server when it carries :command;
                      ignored (shown as misconfigured by callers) otherwise

   Returns an ordered vector of normalized descriptors. Non-map values are
   ignored — type-malformed configs degrade like syntax-malformed ones."
  [cfg-servers]
  (let [overrides (into {}
                        (keep (fn [[name entry]]
                                (when (map? entry) [name entry])))
                        cfg-servers)
        disabled? (fn [id] (true? (get-in overrides [id :disabled])))
        customize (fn [desc]
                    (let [ovr (get overrides (:id desc))
                          argv (or (as-argv ovr) (:command desc))
                          exts (or (some-> ovr :extensions set)
                                   (:extensions desc))
                          extra (some-> ovr :extend-extensions set)]
                      (cond-> (assoc desc :command argv :extensions exts)
                        (contains? ovr :root-markers)
                        (assoc :root-markers (:root-markers ovr))
                        (contains? ovr :exclude-root-markers)
                        (assoc :exclude-root-markers (:exclude-root-markers ovr))
                        (contains? ovr :rootless)
                        (assoc :rootless (:rootless ovr))
                        extra
                        (update :extensions into extra))))
        builtin-ids (set (map :id builtin-descriptors))
        builtins (for [desc builtin-descriptors
                       :when (not (disabled? (:id desc)))]
                   (if (contains? overrides (:id desc))
                     (customize desc)
                     desc))
        customs (for [[name entry] cfg-servers
                      :when (and (map? entry)
                                 (:command entry)
                                 (not (contains? builtin-ids name)))]
                  (-> {:id name
                       :command (as-argv entry)
                       :extensions (set (:extensions entry))
                       :filenames (set (:filenames entry))}
                      (cond->
                       (:root-markers entry)
                        (assoc :root-markers (:root-markers entry))
                        (:exclude-root-markers entry)
                        (assoc :exclude-root-markers (:exclude-root-markers entry))
                        (:root-dir entry)
                        (assoc :root-dir (:root-dir entry))
                        (:rootless entry)
                        (assoc :rootless (:rootless entry)))))]
    (vec (concat builtins customs))))

;; ─── Claiming & workspace roots ───────────────────────────────────────────

(defn- claimed?
  "True when DESC claims PATH by extension or whole filename."
  [desc path]
  (let [name (str/lower-case (str (fs/file-name path)))
        ext (when-let [i (str/last-index-of name ".")] (subs name (inc i)))]
    (or (and ext (contains? (:extensions desc) ext))
        (contains? (:filenames desc) name))))

(defn- normalize-dir [^Object d]
  (str/replace (str d) "\\" "/"))

(defn- under?
  "True when CHILD equals DIR or sits below it."
  [child dir]
  (let [c (normalize-dir child)
        d (normalize-dir dir)]
    (or (= c d) (str/starts-with? c (str d "/")))))

(defn- walk-root
  "Nearest ancestor of PATH holding any INCLUDE marker. An EXCLUDE marker
   found first hands off entirely (::excluded - another server owns this
   tree). No marker anywhere falls back to STOP so single-file projects
   still attach."
  [include exclude path stop]
  (let [stop (fs/canonicalize stop)]
    (loop [dir (fs/parent path)]
      (cond
        (nil? dir) stop
        (some #(fs/exists? (fs/path dir %)) exclude) ::excluded
        (some #(fs/exists? (fs/path dir %)) include) dir
        (= (fs/canonicalize dir) (fs/canonicalize stop)) stop
        :else (recur (fs/parent dir))))))

(defn resolve-root
  "Workspace ROOT for DESC given the queried PATH: explicit :root-dir wins,
   rootless servers attach at the file's directory, marker walk-up is
   bounded by CWD (files outside cwd attach at their own directory)."
  [desc path cwd]
  (cond
    (:root-dir desc) (str (:root-dir desc))
    (:rootless desc) (str (fs/parent path))
    (not (under? path cwd)) (str (fs/parent path))
    :else
    (let [found (walk-root (:root-markers desc [])
                           (:exclude-root-markers desc [])
                           path cwd)]
      (when-not (= ::excluded found) (str found)))))

(defn claiming
  "All servers claiming PATH: [{:id :desc :root}] in registry order."
  [eff path cwd]
  (for [desc eff
        :when (claimed? desc path)
        :let [root (resolve-root desc path cwd)
              root (when root (fs/canonicalize root))]
        :when root]
    {:id (:id desc) :desc desc :root root}))

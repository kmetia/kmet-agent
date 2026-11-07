(ns kmet.app.prompts
  "Prompt templates for kmet, aligned with pi (pi: core/prompt-templates.js,
   docs/prompt-templates.md). A template is a .md file in the prompts dir
   (non-recursive) with optional YAML frontmatter: description (falls back to
   the first non-empty body line, truncated to 60 chars) and argument-hint.
   The filename without .md is the command name: /name args expands to the
   template body with $1, $@, ${1:-default}, ${@:N} placeholders substituted.

   Templates live in the app root's `:prompts` registry, under the `[:app
   :prompts]` owner address, keyed by `[:kmet/prompt <name>]`: visible to
   every engine, outliving one, and disposed with their owner rather than
   copied into an engine and reset by hand. A name holds one
   template, so re-registering replaces the earlier one — pi's loader assigns
   into a name-keyed map the same way. Extension registrations carry the
   extension's own owner path, so unloading it disposes the template."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as app-resources]
            [kmet.libs.yaml :as yaml]))

;; ─── Template registry ────────────────────────────────────────────────────

(def prompts-owner
  "The app's prompt-template registry owner — the registry's address."
  [:app :prompts])

(defn- registered-prompt-templates
  "The effective prompt templates in registration order (a re-registration of
   a name moves the template to the end), one per name."
  []
  (mapv second (registry/effective-entries (registry/current) :kmet/prompt)))

(defn- prompt-template-for
  "The template named NAME, or nil (see `kmet.app.registry/entry` for the
   owner rule)."
  [name]
  (registry/entry (registry/current) :kmet/prompt name))

;; ─── Argument parsing (pi: parseCommandArgs) ──────────────────────────────

(def ^:private js-space-separators
  "JS \\s space separators beyond what Character/isWhitespace covers:
   the NBSP-family (Zs) minus the no-break spaces isWhitespace already
   excludes, plus the Zl/Zp line/paragraph separators and the FEFF BOM.
   Character/isSpaceChar (Zs+Zl+Zp) is unshimmed on Jolt, so the set is
   explicit (pi: /\\s/ in parseCommandArgs)."
  #{\u00a0 \u1680 \u2000 \u2001 \u2002 \u2003 \u2004 \u2005 \u2006 \u2007
    \u2008 \u2009 \u200a \u2028 \u2029 \u202f \u205f \u3000 \ufeff})

(defn- js-whitespace?
  "True for characters JS /\\s/ treats as whitespace: Java's
   Character/isWhitespace misses the NBSP-family (incl. U+FEFF BOM), so union
   with the explicit separator set (pi: /\\s/ in parseCommandArgs)."
  [c]
  (or (Character/isWhitespace c)
      (contains? js-space-separators c)))

(defn parse-command-args
  "Parse command arguments respecting quoted strings (bash-style, pi:
   parseCommandArgs). Returns a vector of argument strings."
  [args-string]
  (let [args (volatile! [])
        cur (volatile! [])
        q (volatile! nil)]
    (doseq [c args-string]
      (if @q
        (if (= c @q) (vreset! q nil) (vswap! cur conj c))
        (cond
          (or (= c \") (= c \')) (vreset! q c)
          (js-whitespace? c)
          (do (when (seq @cur) (vswap! args conj (str/join @cur)))
              (vreset! cur []))
          :else (vswap! cur conj c))))
    (when (seq @cur) (vswap! args conj (str/join @cur)))
    @args))

;; ─── Argument substitution (pi: substituteArgs) ───────────────────────────

(defn- parse-long-safe
  "parse-long returning nil on non-numeric/overflow input (pi's parseInt
   clamps instead of throwing)."
  [s]
  (try (parse-long s) (catch Exception _ nil)))

(defn substitute-args
  "Substitute argument placeholders in template content (pi: substituteArgs):
   $1, $2, ... positional; $@ and $ARGUMENTS for all args; ${N:-default} with
   a default when missing/empty; ${@:-default} / ${ARGUMENTS:-default}; and
   bash-style slicing ${@:N} / ${@:N:L} (1-indexed). Single pass — values are
   not recursively substituted."
  [content args]
  (let [all-args (str/join " " args)
        n (count args)]
    (str/replace content
                 #"\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)"
                 (fn [[_ default-target default-value slice-start slice-length simple]]
                   (cond
                     default-target
                     (if (or (= default-target "@") (= default-target "ARGUMENTS"))
                       (if (seq all-args) all-args default-value)
                       (let [i (parse-long-safe default-target)
                             value (when (and i (>= i 1) (<= i n)) (nth args (dec i)))]
                         (if (seq value) value default-value)))
                     slice-start
                     (let [start (if-let [i (parse-long-safe slice-start)]
                                   (min n (max 0 (dec i)))
                                   n)
                           end (if-let [l (parse-long-safe slice-length)]
                                 (min n (+ start l))
                                 n)]
                       (str/join " " (subvec args start end)))
                     :else
                     (if (or (= simple "@") (= simple "ARGUMENTS"))
                       all-args
                       (if-let [i (parse-long-safe simple)]
                         (nth args (dec i) "")
                         "")))))))

;; ─── Loading (pi: loadTemplateFromFile / loadTemplatesFromDir) ────────────

(defn- parse-template-content
  "Parse RAW .md content into a prompt template (pi: loadTemplateFromFile
   body). LOCATION is the display locator (a file path, or an
   `ext-name:relative/path` locator for extension templates); EXTENSION is
   the owning extension name, or nil. Description = a string frontmatter
   description, else the first non-empty body line truncated to 60 chars
   with \"...\" (pi: only string frontmatter values count). Returns
   {:template map-or-nil :diagnostics [warning-maps]}."
  [raw name location extension]
  (try
    (let [{:keys [frontmatter body]} (yaml/parse-frontmatter raw)
          fm-desc (let [d (get frontmatter "description")]
                    (when (string? d) (str/trim d)))
          fm-hint (let [h (get frontmatter "argument-hint")]
                    (when (string? h) h))
          first-line (first (filter #(seq (str/trim %)) (str/split-lines body)))
          description (cond
                        (seq fm-desc) fm-desc
                        first-line (if (> (count first-line) 60)
                                     (str (subs first-line 0 60) "...")
                                     first-line)
                        :else "")]
      {:template
       (cond-> {:name name
                :description description
                :content body
                :location (str location)
                :extension extension
                :file-path (when (nil? extension) (str location))}
         (seq fm-hint) (assoc :argument-hint fm-hint))
       :diagnostics []})
    (catch Exception e
      {:template nil
       :diagnostics [{:type "warning"
                      :message (or (ex-message e) "failed to parse prompt template file")
                      :path (str location)}]})))

(defn- make-template-from-content
  "Build a prompt template map from RAW .md content (extension templates).
   Returns the template map, or nil on parse failure — registration skips a
   bad template; the file loading path reports diagnostics instead."
  [raw name location extension]
  (:template (parse-template-content raw name location extension)))

(defn- load-template-from-file
  "Load a prompt template from a .md file (name = filename without .md).
   Returns {:template map-or-nil :diagnostics [warning-maps]} — an
   unreadable file or malformed frontmatter is reported, not silently
   skipped (pi: loadTemplateFromFile)."
  [file-path]
  (try
    (parse-template-content (slurp file-path)
                            (str/replace (fs/file-name file-path) #"\.md$" "")
                            (str file-path)
                            nil)
    (catch Exception e
      {:template nil
       :diagnostics [{:type "warning"
                      :message (or (ex-message e) "failed to read prompt template file")
                      :path (str file-path)}]})))

(defn register-prompt-template!
  "Register a prompt template from an extension's bundled .md content string
   (the extension reads its own resource via io/resource and hands the
   content over, so jarred templates need no filesystem path). A name holds
   one template: re-registering under the same name replaces the earlier
   one.
   OPTS: :name (command name), :content (raw .md), :location (display
   locator, e.g. `my-ext:prompts/foo.md`), :extension (owner name), :owner
   (the owner path the registration belongs to — an extension passes
   `extension-owner`, so unloading it disposes the template; the app's
   registry by default).
   Returns a deregister fn removing exactly this template — a no-op when the
   content failed to parse."
  [{:keys [name content location extension owner]}]
  (if-let [t (make-template-from-content content name
                                         (or location (str (or extension "prompt")))
                                         extension)]
    (registry/register! (registry/current)
                        :kmet/prompt (:name t) t (or owner prompts-owner))
    (fn [] nil)))

(defn prompt-template-files-in-dir
  "Prompt template .md files directly inside DIR (non-recursive, pi:
   loadTemplatesFromDir discovery). Returns the ordered file paths."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (->> (fs/list-dir d)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".md")))
           (map str)
           vec))))

(defn load-prompt-template-files!
  "Load prompt templates from explicit .md file paths (the package-resource
   unit, pi: package prompts load) under OWNER (the app's registry by
   default; a resource contribution passes its own owner path, so the
   templates go with it). Adds them to the registry, reports
   load failures as warnings on stderr (pi: prompt resource diagnostics), and
   returns the loaded templates."
  ([file-paths] (load-prompt-template-files! file-paths prompts-owner))
  ([file-paths owner]
   (let [loaded (volatile! [])
         warnings (volatile! [])]
     (doseq [f file-paths]
       (when (str/ends-with? (fs/file-name (str f)) ".md")
         (let [result (load-template-from-file (str f))]
           (when-let [t (:template result)]
             (vswap! loaded conj t))
           (vswap! warnings into (:diagnostics result)))))
     (doseq [{:keys [message path]} @warnings]
       (binding [*out* *err*]
         (println (str "Warning: prompt template at " path ": " message))))
     (let [ts @loaded
           owner (or owner prompts-owner)]
       (doseq [t ts]
         (registry/register! (registry/current)
                             :kmet/prompt (:name t) t owner))
       ts))))

(defn load-prompt-templates-from-dir
  "Load .md prompt templates from a directory (non-recursive, pi:
   loadTemplatesFromDir) under OWNER (the app's registry by default). Adds
   them to the registry; returns the loaded templates."
  ([dir] (load-prompt-templates-from-dir dir prompts-owner))
  ([dir owner]
   (load-prompt-template-files! (prompt-template-files-in-dir dir) owner)))

(defn get-prompt-templates
  []
  (registered-prompt-templates))

(defn clear-prompt-templates!
  "Remove every loaded prompt template (pi: resourceLoader.reload re-discovers
   from scratch). Used by /reload."
  []
  (registry/clear! (registry/current) :kmet/prompt))

(defn get-prompt-template
  [name]
  (prompt-template-for name))

;; ─── Expansion (pi: expandPromptTemplate) ─────────────────────────────────

(defn expand-prompt-template
  "Expand /name args into the template body when name matches a template.
   Returns the expanded content, or the original text when not a template
   (pi: expandPromptTemplate)."
  [text template-list]
  (if-not (str/starts-with? text "/")
    text
    (if-let [[_ name args-string] (re-matches #"^/([^\s]+)(?:\s+([\s\S]*))?$" text)]
      (if-let [t (first (filter #(= name (:name %)) template-list))]
        (substitute-args (:content t) (parse-command-args (or args-string "")))
        text)
      text)))

;; ─── Autocomplete (pi: interactive-mode templateCommands) ─────────────────

(defn as-command-maps
  "Templates in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode converts templates to SlashCommand format)."
  [template-list]
  (mapv (fn [t]
          (cond-> {:name (:name t)
                   :description (:description t)}
            (:argument-hint t) (assoc :argument-hint (:argument-hint t))))
        template-list))

(defn enabled-command-maps
  "The templates the `:resources` selection enables, in slash-command shape. A
   nil RESOURCES (the composition mounted no row) keeps every template."
  [resources]
  (as-command-maps
   (app-resources/enabled resources :prompt (get-prompt-templates))))

(ns kmet.tui.keybindings
  "KeybindingsManager — port of @earendil-works/pi-tui KeybindingsManager.
   Maps keybinding IDs (e.g. \"tui.editor.cursorUp\") to resolved key chords
   (e.g. [\"up\"]), with support for user overrides and conflict detection."
  (:require [clojure.string :as str]
            [kmet.tui.keys :as keys]))

;; ─── Default TUI keybinding definitions ──────────────────────────────────
;; Each entry: {:default-keys [key-id ...] :description str}

(def tui-keybinding-defs
  {"tui.editor.cursorUp"           {:default-keys ["up"]                :description "Move cursor up"}
   "tui.editor.cursorDown"         {:default-keys ["down"]              :description "Move cursor down"}
   "tui.editor.historyPrevious"    {:default-keys []                    :description "Select previous prompt history entry"}
   "tui.editor.historyNext"        {:default-keys []                    :description "Select next prompt history entry"}
   "tui.editor.cursorLeft"         {:default-keys ["left" "ctrl+b"]     :description "Move cursor left"}
   "tui.editor.cursorRight"        {:default-keys ["right" "ctrl+f"]    :description "Move cursor right"}
   "tui.editor.cursorWordLeft"     {:default-keys ["alt+left" "ctrl+left" "alt+b"]  :description "Move cursor word left"}
   "tui.editor.cursorWordRight"    {:default-keys ["alt+right" "ctrl+right" "alt+f"] :description "Move cursor word right"}
   "tui.editor.cursorLineStart"    {:default-keys ["home" "ctrl+home" "ctrl+a"]     :description "Move to line start"}
   "tui.editor.cursorLineEnd"      {:default-keys ["end" "ctrl+end" "ctrl+e"]      :description "Move to line end"}
   "tui.editor.jumpForward"        {:default-keys ["ctrl+]"]            :description "Jump forward to character"}
   "tui.editor.jumpBackward"       {:default-keys ["ctrl+alt+]" "ctrl+shift+]"] :description "Jump backward to character"}
   "tui.editor.pageUp"             {:default-keys ["pageUp" "ctrl+pageUp"]  :description "Page up"}
   "tui.editor.pageDown"           {:default-keys ["pageDown" "ctrl+pageDown"] :description "Page down"}
   "tui.editor.deleteCharBackward" {:default-keys ["backspace" "ctrl+h"] :description "Delete char backward"}
   "tui.editor.deleteCharForward"  {:default-keys ["delete" "ctrl+d"]   :description "Delete char forward"}
   "tui.editor.deleteWordBackward" {:default-keys ["ctrl+w" "alt+backspace"] :description "Delete word backward"}
   "tui.editor.deleteWordForward"  {:default-keys ["alt+d" "alt+delete"]     :description "Delete word forward"}
   "tui.editor.deleteToLineStart"  {:default-keys ["ctrl+u"]            :description "Delete to line start"}
   "tui.editor.deleteToLineEnd"    {:default-keys ["ctrl+k"]            :description "Delete to line end"}
   "tui.editor.yank"               {:default-keys ["ctrl+y"]            :description "Yank"}
   "tui.editor.yankPop"            {:default-keys ["alt+y"]             :description "Yank pop"}
   "tui.editor.undo"               {:default-keys ["ctrl+-"]            :description "Undo"}
   ;; kmet extension ids — pi's TUI_KEYBINDINGS has neither a redo (undo is
   ;; one-way there) nor a line kill. They live here so both stay rebindable
   ;; through the manager like every pi id (the editor's ctrl+w kill-line
   ;; takes precedence over deleteWordBackward's ctrl+w, which fires when
   ;; the user moves killLine elsewhere)
   "tui.editor.redo"               {:default-keys ["ctrl+z"]            :description "Redo"}
   "tui.editor.killLine"           {:default-keys ["ctrl+w"]            :description "Delete the current line"}
   "tui.input.newLine"             {:default-keys ["shift+enter" "ctrl+j" "ctrl+enter" "alt+enter"] :description "Insert newline"}
   "tui.input.submit"             {:default-keys ["enter"]              :description "Submit input"}
   "tui.input.tab"                {:default-keys ["tab" "ctrl+i"]       :description "Tab / autocomplete"}
   "tui.input.copy"               {:default-keys ["ctrl+c"]             :description "Copy selection"}
   "tui.select.up"                {:default-keys ["up" "ctrl+p"]       :description "Move selection up"}
   "tui.select.down"              {:default-keys ["down" "ctrl+n"]     :description "Move selection down"}
   "tui.select.pageUp"            {:default-keys ["pageUp"]             :description "Page up"}
   "tui.select.pageDown"          {:default-keys ["pageDown"]           :description "Page down"}
   "tui.select.confirm"           {:default-keys ["enter"]              :description "Confirm selection"}
   "tui.select.cancel"            {:default-keys ["escape" "ctrl+c"]    :description "Cancel selection"}
   ;; kmet extension ids — pi's SelectList/SettingsList have no first/last
   ;; jumps or value cycling on left/right
   "tui.select.first"             {:default-keys ["home"]                :description "Move selection to the first item"}
   "tui.select.last"              {:default-keys ["end"]                 :description "Move selection to the last item"}
   "tui.settings.cycleBackward"   {:default-keys ["left"]                :description "Cycle the value backward"}
   "tui.settings.cycleForward"    {:default-keys ["right"]               :description "Cycle the value forward"}})

;; ─── KeybindingsManager record ─────────────────────────────────────────────

(defrecord KeybindingsManager [definitions-atom  ;; atom of {id -> {:default-keys [...] :description str}}
                               user-bindings-atom  ;; atom of {id -> key-str-or-vec}
                               keys-by-id-atom  ;; atom of {id -> [key-str ...]}
                               conflicts-atom]  ;; atom of [{:key key-str :keybindings [id ...]}]
  Object
  (toString [_this] (str "#KeybindingsManager<" (count @keys-by-id-atom) " bindings>")))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- normalize-keys
  "Convert nil, string, or vector to a deduplicated vector of key strings."
  [k]
  (cond
    (nil? k) []
    (string? k) [k]
    (vector? k) (vec (distinct k))
    :else []))

(defn- find-conflicts
  "Find keys that are bound to multiple keybinding IDs in user-bindings."
  [definitions user-bindings]
  (let [claims (atom {})]
    (doseq [[id keys] user-bindings]
      (when (contains? definitions id)
        (doseq [key (normalize-keys keys)]
          (swap! claims update key (fnil conj #{}) id))))
    (vec (for [[key ids] @claims
               :when (> (count ids) 1)]
           {:key key :keybindings (vec ids)}))))

(defn- resolve-all-keys
  "Build the resolved keys-by-id map from definitions + user-bindings."
  [definitions user-bindings]
  (into {} (for [[id def] definitions]
             (if-let [user-keys (get user-bindings id)]
               [id (normalize-keys user-keys)]
               [id (normalize-keys (:default-keys def))]))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-keybindings-manager
  "Create a KeybindingsManager from definitions and optional user overrides."
  ([definitions] (make-keybindings-manager definitions {}))
  ([definitions user-bindings]
   (let [user-bindings (or user-bindings {})]
     (map->KeybindingsManager
      {:definitions-atom (atom definitions)
       :user-bindings-atom (atom user-bindings)
       :keys-by-id-atom (atom (resolve-all-keys definitions user-bindings))
       :conflicts-atom (atom (find-conflicts definitions user-bindings))}))))

(defn make-tui-keybindings-manager
  "Create a KeybindingsManager with the default TUI keybindings.
   Optionally accepts user overrides map."
  ([]
   (make-tui-keybindings-manager {}))
  ([user-bindings]
   (make-keybindings-manager tui-keybinding-defs user-bindings)))

;; ─── API ───────────────────────────────────────────────────────────────────

(defn get-keys
  "Get the resolved key chords for a keybinding ID.
   Returns a vector of key strings (e.g. [\"ctrl+e\"])."
  [kmgr keybinding-id]
  (get @(:keys-by-id-atom kmgr) keybinding-id []))

(defn matches-key
  "Check if raw input data matches a keybinding ID.
   Returns true if any of the resolved key chords match the input."
  [kmgr data keybinding-id]
  (let [key-chords (get-keys kmgr keybinding-id)]
    (some #(keys/matches-key? data %) key-chords)))

(defn get-definition
  "Get the definition map for a keybinding ID, or nil."
  [kmgr keybinding-id]
  (get @(:definitions-atom kmgr) keybinding-id))

(defn get-conflicts
  "Get vector of conflict maps: {:key key-str :keybindings [id ...]}."
  [kmgr]
  @(:conflicts-atom kmgr))

(defn set-user-bindings!
  "Replace all user overrides and rebuild the resolution."
  [kmgr user-bindings]
  (reset! (:user-bindings-atom kmgr) user-bindings)
  (let [definitions @(:definitions-atom kmgr)]
    (reset! (:keys-by-id-atom kmgr) (resolve-all-keys definitions user-bindings))
    (reset! (:conflicts-atom kmgr) (find-conflicts definitions user-bindings)))
  nil)

(defn register-definition!
  "Register a runtime keybinding definition (pi: extension shortcuts add
   bindings at runtime — kmet registers them with the app manager so
   key-hints resolve and user overrides apply). DEF — {:default-keys [...]
   :description str}. A later registration of the same id replaces the
   earlier definition and re-resolves keys/conflicts."
  [kmgr keybinding-id def]
  (swap! (:definitions-atom kmgr) assoc keybinding-id def)
  (set-user-bindings! kmgr @(:user-bindings-atom kmgr))
  nil)

(defn unregister-definition!
  "Remove a runtime keybinding definition (extension unload)."
  [kmgr keybinding-id]
  (swap! (:definitions-atom kmgr) dissoc keybinding-id)
  (set-user-bindings! kmgr @(:user-bindings-atom kmgr))
  nil)

(defn get-user-bindings
  "Get the current user overrides map."
  [kmgr]
  @(:user-bindings-atom kmgr))

(defn get-resolved-bindings
  "Get the fully resolved bindings map (id -> [keys]) for all definitions."
  [kmgr]
  @(:keys-by-id-atom kmgr))

;; ─── Global singleton ─────────────────────────────────────────────────────

(defonce ^:private global-kmgr (atom nil))

(defn set-global-keybindings!
  "Set the global KeybindingsManager singleton."
  [kmgr]
  (reset! global-kmgr kmgr))

(defn get-global-keybindings
  "Get the global KeybindingsManager, creating a default TUI one if none set."
  []
  (or @global-kmgr
      (let [default (make-tui-keybindings-manager)]
        (reset! global-kmgr default)
        default)))

(defn global-match?
  "Does raw input DATA match keybinding ID in the global manager (pi:
   getKeybindings().matches)? Generic widgets resolve their own keys through
   this so user overrides (keybindings.edn) apply to them; widgets taking an
   injected manager (the editor) resolve through that one instead."
  [data keybinding-id]
  (matches-key (get-global-keybindings) data keybinding-id))

;; ─── Key hint formatting ──────────────────────────────────────────────────
;; These produce ANSI-styled strings for display in tool outputs.
;; The theme-fn-dir and theme-fn-muted are color/styling functions from
;; kmet.tui.theme (dim, muted). We accept them as args to avoid circular deps.

(defn key-text
  "Pi: keyText — display text for a keybinding ID: all resolved key chords
   joined with '/'. Returns nil when nothing is bound."
  [kmgr keybinding-id]
  (let [chords (get-keys kmgr keybinding-id)]
    (when (seq chords)
      (str/join "/" chords))))

(defn key-label-text
  "Like key-text, but each chord rendered through keys/key-label — the
   human-facing form for hints and help bars (\"pgup\", \"↑\"). nil when
   the id has no binding."
  [kmgr keybinding-id]
  (let [chords (get-keys kmgr keybinding-id)]
    (when (seq chords)
      (str/join "/" (map keys/key-label chords)))))

(defn key-hint
  "Format a keybinding hint string.
   kmgr    — KeybindingsManager
   id      — keybinding ID (e.g. \"app.tools.expand\")
   desc    — description text (e.g. \"to expand\")
   dim-fn  — (fn [s]) -> ANSI-dimmed string
   muted-fn — (fn [s]) -> ANSI-muted string

   Returns \"ctrl+e to expand\" styled with dim/muted colors. The chord is
   rendered through key-label (pageUp → pgup), so hints read the same way
   everywhere."
  [kmgr id desc dim-fn muted-fn]
  (let [k (key-label-text kmgr id)]
    (if k
      (str (dim-fn k) (muted-fn (str " " desc)))
      (muted-fn desc))))

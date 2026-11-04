(ns kmet.extensions.tools
  "Interactive /tools selector — port of pi's example tools extension
   (packages/coding-agent/examples/extensions/tools.ts).

   Provides a /tools command that opens an interactive selector to enable
   and disable tools. Toggling is a runtime change plus a tools-config
   custom entry on the active branch, restored on session start and branch
   navigation. Ctrl+S persists the current selection through the
   :enabled-tools setting (ext/set-tool-enabled!), in the scope shown in
   the header; Tab switches that write scope. The dialog never writes
   settings without that explicit save. An opt-in built-in (`run_code`,
   `eval`) is written as `+name` on an enabled save, so an all-enabled
   selection stays a non-empty setting instead of collapsing to the
   default-off fallback.

   Loading this extension replaces the builtin /tools listing command —
   builtins never clobber commands an extension registered (pi has no
   builtin /tools; the example extension owns the name there).

   The dialog is built from the generic TUI layer (kmet.tui.* — the port
   of pi's @earendil-works/pi-tui), which extension contexts share by
   reference: the SettingsList component handles navigation, value cycling
   and key parsing itself; the theme helpers handle styling. ui-custom
   accepts either an IComponent or a duck-typed {:render :handle-input
   :invalidate} map (pi: custom() accepts both a Component and a
   duck-typed object).

   Usage: symlink or copy this file into ~/.kmet/agent/extensions/ (global)
   or .kmet/extensions/ (project-local), then restart kmet or run /reload."
  (:require [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.utils :as u]))

;; ─── Extension state ───────────────────────────────────────────────────────

(def ^:private tools-config-type "tools-config")

(def ^:private empty-selection
  {:scope :global
   :entries {:global [] :project []}
   :paths {}
   :enabled #{}
   :origin {}})

(defn- persist-state!
  "Append the current selection as a tools-config custom entry (pi:
   persistState). Entries ride the branch, so restore reads the last one on
   the active path."
  [api state]
  ((:append-entry! (ext/session api))
   tools-config-type {:enabled-tools (vec (sort (:enabled-tools @state)))}))

(defn- apply-tools!
  "Apply the current selection to the agent (pi: applyTools)."
  [api state]
  (ext/set-active-tools api (vec (:enabled-tools @state))))

(defn- refresh-selection!
  "Re-read the settings view and keep the write scope the dialog already
   chose. Returns the selection."
  [api state]
  (let [selection (or (ext/tool-selection api) empty-selection)]
    (swap! state (fn [st]
                   (assoc st
                          :selection selection
                          :scope (or (:scope st) (:scope selection)))))
    selection))

(defn- restore-from-branch!
  "Sync the selection with the session (pi: restoreFromBranch): the last
   tools-config entry on the active branch wins, filtered to tools that
   still exist; without one, mirror the currently active tools."
  [api state]
  (let [sess (ext/session api)
        all-tools (ext/get-all-tools api)
        all-names (set (map :name all-tools))
        saved (last (keep (fn [e] (some-> (:data e) :enabled-tools))
                          ((:get-entries sess) tools-config-type)))
        enabled (if saved
                  (set (filter all-names saved))
                  (set (or (ext/get-active-tools api) all-names)))]
    (swap! state assoc :all-tools all-tools :enabled-tools enabled)
    (when saved (apply-tools! api state))))

;; ─── The /tools dialog (pi: the ctx.ui.custom factory) ─────────────────────

(defn- mark-session-origin!
  "A toggle installs a runtime selection, so every live row's current state
   now comes from the session until Ctrl+S writes the settings."
  [state]
  (swap! state update :selection assoc :origin
         (zipmap (map :name (:all-tools @state)) (repeat :session))))

(defn- selection-items
  "The SettingsList rows: one per live tool, with the effective source of
   its current state as a label suffix."
  [{:keys [all-tools enabled-tools selection]}]
  (let [origin (:origin selection)]
    (mapv (fn [t]
            (let [tool-name (:name t)
                  source (get origin tool-name :default)]
              {:id tool-name
               :label (str tool-name " [" (name source) "]")
               :value (if (contains? enabled-tools tool-name)
                        "enabled" "disabled")
               :values ["enabled" "disabled"]}))
          (sort-by :name all-tools))))

(defn- scope-line
  "The header's write-scope line: the target settings file and the entry
   count each scope currently holds."
  [state]
  (let [scope (:scope @state)
        selection (:selection @state)
        entries (:entries selection)
        counts (str "global " (count (:global entries))
                    " · project " (count (:project entries)))
        path (get-in selection [:paths scope] "settings.edn")]
    (str (if (= :project scope) "Project" "Global") " · " path " · " counts)))

(defn- dialog-header
  [state th kb-mgr width]
  (let [save-key (or (kb/key-label-text kb-mgr "app.tools.save") "ctrl+s")
        title (theme/bold "Tool Configuration")
        hints (theme/fg th :muted
                        (str "  tab scope · space toggle · " save-key
                             " save · esc close"))]
    [(u/truncate-to-width (str title hints) width "")
     (u/truncate-to-width
      (theme/fg th :muted (str "Scope: " (scope-line state)))
      width "")
     ""]))

(defn- save-selection!
  "Persist the checked rows through the :enabled-tools setting in the write
   scope. The setting is applied to the runtime, the branch entry is
   refreshed, and the dialog re-reads its provenance. A failure leaves the
   dialog open and reports the error.

   The host writes one state per live tool. An opt-in built-in's enabled
   state is a +name entry (its default is off), so an all-enabled save is
   never the empty setting that would fall back to the opt-out default."
  [api state settings]
  (try
    (let [scope (:scope @state)
          enabled (:enabled-tools @state)]
      (doseq [t (:all-tools @state)]
        (ext/set-tool-enabled! api (:name t) (contains? enabled (:name t))
                               {:scope scope}))
      ;; the setting now equals the selection: clear the session override so
      ;; the settings, not a stale runtime set, are the source it reports
      (ext/set-active-tools api nil)
      (persist-state! api state)
      (let [selection (refresh-selection! api state)]
        (swap! state assoc :enabled-tools (:enabled selection))
        (settings-list/settings-list-set-items!
         settings (selection-items @state) {:preserve-state? true})
        (ext/ui-notify api (str "Saved " (name scope) " tool selection") :info)))
    (catch Exception e
      (ext/ui-notify api (str "Cannot save tool selection: " (ex-message e))
                     :error))))

(defn- toggle-row!
  "Apply a row change to the runtime and the branch, then mark every row as
   session-sourced: the toggle installed a whole runtime selection."
  [api state settings id new-value]
  (swap! state update :enabled-tools
         (if (= new-value "enabled") conj disj) id)
  (apply-tools! api state)
  (persist-state! api state)
  (mark-session-origin! state)
  (settings-list/settings-list-set-items! settings (selection-items @state)
                                          {:preserve-state? true}))

(defn- tools-dialog
  "Build the /tools selector component. TUI/TH/KB/CLOSE are what ui-custom
   passes to factories (pi: (tui, theme, kb, done)); the render loop
   re-renders after every input, so no explicit requestRender is needed.
   The SettingsList handles navigation, value cycling and escape itself."
  [api state _tui th kb-mgr close]
  (let [settings (settings-list/make-settings-list
                  (selection-items @state)
                  :theme (theme/get-settings-list-theme th)
                  :max-visible (min (+ (count (:all-tools @state)) 2) 15))
        _ (settings-list/settings-list-set-on-change!
           settings (fn [id new-value]
                      (toggle-row! api state settings id new-value)))
        _ (settings-list/settings-list-set-on-escape! settings
                                                      (fn [] (close nil)))]
    {:render (fn [width]
               (into (dialog-header state th kb-mgr width)
                     (protocols/render settings width)))
     :handle-input (fn [data]
                     (cond
                       (kb/global-match? data "tui.input.tab")
                       (swap! state update :scope
                              #(if (= :global %) :project :global))

                       (kb/matches-key kb-mgr data "app.tools.save")
                       (save-selection! api state settings)

                       :else
                       (protocols/handle-input settings data)))
     :invalidate (fn [] (protocols/invalidate settings))
     :dispose (fn [] (protocols/dispose settings))}))

(defn- open-selector!
  "Open the /tools selector. Headless/print mode has no UI registry
   (ctx.mode !== 'tui' in pi) — notify instead of showing a dialog."
  [api state ctx]
  (swap! state assoc :all-tools (or (ext/get-all-tools api) []))
  (refresh-selection! api state)
  (when (or (not= :interactive (:mode ctx))
            (not (ext/ui-custom api (fn [tui th kb close]
                                      (tools-dialog api state tui th kb close)))))
    (ext/ui-notify api "/tools requires TUI mode" :error)))

(defn init
  "Register the /tools command and restore the selection on session start
   and branch navigation (pi: toolsExtension)."
  [api]
  (let [state (atom {:all-tools []
                     :enabled-tools #{}
                     :scope nil
                     :selection nil})]
    (ext/register-command! api
                           {:name "tools"
                            :description "Enable/disable tools interactively"
                            :handler (fn [ctx _args] (open-selector! api state ctx))})
    ;; handlers receive (event ctx) — pi parity (pi: on('session_start',
    ;; (_event, ctx) => ...)); ctx is unused here but required arity
    (ext/on-event api :session-start
                  (fn [_ev _ctx] (restore-from-branch! api state)))
    (ext/on-event api :session-tree
                  (fn [_ev _ctx] (restore-from-branch! api state)))))

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/tools
   :apply (fn [_ctx api] (init api) nil)})

(ns kmet.app.ui.login-dialog
  "Login flow dialog (pi: modes/interactive/components/login-dialog.ts
   LoginDialogComponent): dock-mounted dialog that replaces the editor
   during an OAuth/API-key login — bold accent \"Login to <provider>\" title,
   a dynamic content area (auth URL, device code, progress, waiting), and a
   one-line input the flows prompt through. Escape aborts the flow.

   The dynamic content is a mounted hiccup tree (dsl.md stage 4): show-*
   mutations are pure swaps on a row-descriptor atom, the root's reaction
   re-derives on change, and reconcile reuses unchanged rows (equal props)
   plus the input record (identity splice). The defcomponent shell stays for
   IFocusable/handle-input — focus and key routing are imperative (dsl.md
   §5 input boundary)."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.components.input :as input]
            [kmet.tui.core :as tui]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.reakt :as r]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

;; ─── Browser (pi utils/open-browser.ts) ────────────────────────────────────

(defn open-browser
  "Open a URL/file with the platform default handler (pi openBrowser):
   macOS `open`, Windows rundll32 handler, else xdg-open. Best-effort —
   launcher failures are swallowed (the caller shows the target anyway)."
  [target]
  (let [os (str/lower-case (str (System/getProperty "os.name")))
        cmd (cond
              (str/includes? os "mac") ["open" target]
              (str/includes? os "win") ["rundll32" "url.dll,FileProtocolHandler" target]
              :else ["xdg-open" target])]
    (try
      ;; :continue — a missing launcher must not throw (pi swallows the
      ;; spawn error event)
      (p/shell {:out :ignore :err :ignore :continue true} cmd)
      (catch Exception _ nil))))

;; ─── Content rows ──────────────────────────────────────────────────────────
;; Row descriptors: {:row :spacer} | {:row :text :text s} (padding 1 0) |
;; {:row :input} (the live Input record) | {:row :submitted :text s}
;; (`> answer`, padding 0 0).

(def ^:private spacer-row {:row :spacer})

(defn- text-row [s] {:row :text :text s})

(defn- repaint!
  "Request a TUI render after mutating the dialog off the input thread (pi:
   every LoginDialogComponent.show* ends with this.tui.requestRender())."
  [d]
  (when-let [tui (:tui d)]
    (tui/tui-request-render tui))
  nil)

(defn- linked-url-line
  "Accent OSC 8-hyperlinked URL text (pi showAuth/showDeviceCode first line)."
  [th url]
  (theme/fg th :accent (u/osc-hyperlink url url)))

(defn- click-hint-line
  "Dim hyperlink carrying the platform click hint (pi clickHint)."
  [th url]
  (theme/fg th :dim (u/osc-hyperlink url "Ctrl+click to open")))

(defn- cancel-hint-line
  "(escape to cancel) hint line, pi keyHint styling."
  []
  (str "(" (app-kb/key-hint "tui.select.cancel" "to cancel") ")"))

(defn- submit-hint-line
  "(escape to cancel, enter to submit) hint line."
  []
  (str "(" (app-kb/key-hint "tui.select.cancel" "to cancel,") " "
       (app-kb/key-hint "tui.select.confirm" "to submit") ")"))

;; ─── Flow states (pi showAuth / showDeviceCode / showPrompt / ...) ─────────

(defn login-dialog-show-auth!
  "Show an OAuth authorize URL (+ optional instructions) and open it in the
   platform browser (pi showAuth). The Input is NOT part of the tree here —
   pi only mounts it while a prompt is active."
  [d url instructions]
  (let [th (theme/get-current-theme)]
    (reset! (:rows-atom d)
            (vec (remove nil?
                         [spacer-row
                          (text-row (linked-url-line th url))
                          (text-row (click-hint-line th url))
                          (when instructions spacer-row)
                          (when instructions
                            (text-row (theme/fg th :warning instructions)))]))))
  (open-browser url)
  (repaint! d))

(defn login-dialog-show-device-code!
  "Show the device-flow verification URI + user code (pi showDeviceCode)."
  [d verification-uri user-code]
  (let [th (theme/get-current-theme)]
    (reset! (:rows-atom d)
            [spacer-row
             (text-row (linked-url-line th verification-uri))
             (text-row (click-hint-line th verification-uri))
             spacer-row
             (text-row (theme/fg th :warning (str "Enter code: " user-code)))]))
  (repaint! d))

(defn- append-rows!
  "Append ROWS at the content end — exactly pi's contentContainer.addChild
   appends (the Input is not in the tree outside an active prompt, so
   there is no ordering interaction)."
  [d new-rows]
  (swap! (:rows-atom d) into new-rows))

(defn login-dialog-show-info!
  "Append provider-owned informational text (pi showInfo)."
  [d message]
  (append-rows! d [spacer-row
                   (text-row (theme/fg (theme/get-current-theme) :text message))])
  (repaint! d))

(defn login-dialog-show-waiting!
  "Append a dim waiting message + cancel hint (pi showWaiting — polling
   flows like GitHub Copilot)."
  [d message]
  (append-rows! d [spacer-row
                   (text-row (theme/fg (theme/get-current-theme) :dim message))
                   (text-row (cancel-hint-line))])
  (repaint! d))

(defn login-dialog-show-progress!
  "Append a dim progress line (pi showProgress)."
  [d message]
  (append-rows! d [(text-row (theme/fg (theme/get-current-theme) :dim message))])
  (repaint! d))

;; ─── Prompt plumbing ───────────────────────────────────────────────────────

(defn- resolve-input!
  "Deliver VALUE to the pending prompt promise and turn the prompt's input
   row into a `> value` transcript line (pi replaceInputWithSubmittedText)."
  [d value]
  (when-let [resolve @(:input-resolver-atom d)]
    (reset! (:input-resolver-atom d) nil)
    (let [rows @(:rows-atom d)
          idx (->> rows
                   (map-indexed (fn [i row] (when (= :input (:row row)) i)))
                   (remove nil?)
                   last)]
      ;; idx is always present while a prompt is pending; guard anyway —
      ;; assoc with nil index would throw inside a watch-adjacent path
      (when idx
        (swap! (:rows-atom d) assoc idx {:row :submitted :text (str "> " value)})))
    (resolve value)))

(defn login-dialog-cancel!
  "Abort the login flow: settle any pending prompt as cancelled, clean up
   the dialog's UI state (remove the input row and hint), and fire
   on-complete (pi cancel). Does NOT clear input callbacks — those are
   cleared in dispose when the dialog is truly removed. Clearing them here
   would break Escape handling if the user presses Escape again before the
   dialog is disposed."
  [d]
  ;; Clean up the dialog UI: remove the :input row and its trailing hint line
  (swap! (:rows-atom d)
         (fn [rows]
           (let [input-idx (->> rows
                                (map-indexed (fn [i row] (when (= :input (:row row)) i)))
                                (remove nil?)
                                first)]
             (if input-idx
               (into (subvec rows 0 input-idx) (drop 2 rows))
               rows))))
  (when-let [reject @(:input-rejecter-atom d)]
    (reset! (:input-resolver-atom d) nil)
    (reset! (:input-rejecter-atom d) nil)
    (reject (ex-info "Login cancelled" {:type :login-cancelled})))
  (when-let [cb @(:on-complete-atom d)]
    (cb false "Login cancelled"))
  (repaint! d)
  nil)

(defn- show-input-prompt!
  "Common tail of show-prompt!/show-manual-input!: append MESSAGE rows + the
   input row + hint, clear the input, return a promise delivering the
   submitted string (or the cancellation ex-info). Exactly one :input row
   exists at a time — a previous one (e.g. the initial bare input) MOVES to
   the new position (pi: Container.addChild moves the component), so the
   same Input instance never renders twice."
  [d rows hint-line]
  (reset! (:rows-atom d)
          (into (vec (remove #(= :input (:row %)) @(:rows-atom d)))
                (concat rows [{:row :input} (text-row hint-line)])))
  (input/input-set-value! (:input-comp d) "")
  (reset! (:cursor-atom (:input-comp d)) 0)
  (let [p (promise)]
    (reset! (:input-resolver-atom d) #(deliver p %))
    (reset! (:input-rejecter-atom d) #(deliver p %))
    (repaint! d)
    p))

(defn await-prompt!
  "Deref a show-prompt!/show-manual-input! promise; rethrows the
   \"Login cancelled\" ex-info when the dialog was cancelled instead of
   submitted."
  [p]
  (let [v @p]
    (if (instance? Exception v)
      (throw v)
      v)))

(defn login-dialog-show-prompt!
  "Prompt for text inside the dialog (pi showPrompt): message, optional dim
   `e.g., placeholder` line, the input, and the cancel/submit hint. Returns
   a promise of the entered string. Appends below existing content so an
   auth URL shown earlier stays visible."
  [d message placeholder]
  (let [th (theme/get-current-theme)
        rows (into [spacer-row
                    (text-row (theme/fg th :text message))]
                   (when (seq placeholder)
                     [(text-row (theme/fg th :dim (str "e.g., " placeholder)))]))]
    (show-input-prompt! d rows (submit-hint-line))))

(defn login-dialog-show-manual-input!
  "Manual code/URL entry for callback-server providers (pi showManualInput):
   dim prompt text + input + cancel hint; returns a promise of the string."
  [d prompt]
  (let [th (theme/get-current-theme)]
    (show-input-prompt! d
                        [spacer-row
                         (text-row (theme/fg th :dim prompt))]
                        (cancel-hint-line))))

;; ─── Component ─────────────────────────────────────────────────────────────

(defcomponent LoginDialog nil
              [root rows-atom input-comp tui input-resolver-atom
               input-rejecter-atom on-complete-atom focused? cache-atom]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [this data]
    ;; Always forward to the Input component — it handles all keys including
    ;; Escape (via its on-escape callback) and Enter (via on-submit).
    ;; This works whether the Input is currently mounted in the rows or not.
    (protocols/handle-input (:input-comp this) data))

  (dispose [this]
    ;; Clear input callbacks to prevent any stale firings after dispose
    (input/input-set-on-submit! (:input-comp this) nil)
    (input/input-set-on-escape! (:input-comp this) nil)
    ;; Dispose the input component (foreign, not auto-disposed by hiccup root)
    (protocols/dispose (:input-comp this))
    ;; Unwind the content tree's reaction (watches on the rows atom) with
    ;; the dialog — the flow owners call this after dock restore
    (protocols/dispose (:root this))))

(extend-type LoginDialog
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:input-comp this) val)))

(defn make-login-dialog
  "Create the login dialog for PROVIDER-NAME (pi LoginDialogComponent).
   ON-COMPLETE fires (fn [success message]) when the user escapes. TUI is
   the tui instance (request-render after prompt resolution)."
  [tui provider-name on-complete]
  (let [th (theme/get-current-theme)
        d (map->LoginDialog
           {:rows-atom (atom [])
            :input-comp (input/make-input)
            :tui tui
            :input-resolver-atom (atom nil)
            :input-rejecter-atom (atom nil)
            :on-complete-atom (atom on-complete)
            :focused? (atom false)
            :cache-atom (atom nil)})
        ;; the whole dialog as one reactive tree: the row descriptors are
        ;; spliced as a seq (dsl.md §2.1). The two border elements share one
        ;; stable :color-fn (created here, not in the body) so their props
        ;; stay =-equal across passes and reconcile keeps the instances
        ;; instead of rebuilding them; the title is a tag element reused by
        ;; equal props, and only the input is spliced foreign (its instance
        ;; outlives the rows it moves through). The body returns a SEQ of
        ;; sibling roots — a vector would parse its head as a tag.
        accent-fn #(theme/fg th :accent %)
        title [:text {:text (theme/fg th :accent
                                      (theme/bold (str "Login to " provider-name)))
                      :padding-x 1 :padding-y 0}]
        root (hiccup/root
              (fn [_props]
                (concat [[:dynamic-border {:color-fn accent-fn}]
                         title]
                        (mapv #(case (:row %)
                                 :spacer [:spacer {:lines 1}]
                                 :text [:text {:text (:text %) :padding-x 1 :padding-y 0}]
                                 :submitted [:text {:text (:text %) :padding-x 0 :padding-y 0}]
                                 :input (:input-comp d))
                              (r/tracked-deref (:rows-atom d)))
                        [[:dynamic-border {:color-fn accent-fn}]])))]
    (input/input-set-on-submit! (:input-comp d) #(resolve-input! d %))
    (input/input-set-on-escape! (:input-comp d) #(login-dialog-cancel! d))
    (assoc d :root root)))

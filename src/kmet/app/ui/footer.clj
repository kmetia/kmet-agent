(ns kmet.app.ui.footer
  "FooterComponent — Pi's two-line footer:
     line 1: accent cursive К mark + hosting-runtime letter (b: babashka,
             j: jolt) + cwd (home-substituted) + git branch, dim
     line 2: usage stats (↑in ↓out R W CH%) + context % colored by usage,
             followed by the provider/model (left-aligned, kmet deviation
             from pi's right alignment) with a pi-aligned thinking suffix:
             reasoning models show ' • thinking off' when off and ' •
             <level>' otherwise, non-reasoning models show nothing; the
             model line wraps to its own left-aligned line when the stats
             line is too narrow
     line 3 (optional): extension statuses, sorted by key
   Data comes from the FooterDataProvider (pi: FooterComponent + provider).
   No separator line — the two content lines are the footer (pi parity)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.host :as host]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.tui.macros :refer [track! track-deps defcomponent]]))

(defn format-tokens
  "Format token counts for compact footer display (pi: formatTokens).
   999 → \"999\", 1234 → \"1.2k\", 12345 → \"12k\", 1234567 → \"1.2M\"."
  [count]
  (let [n (long count)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (str (format "%.1f" (/ (double n) 1000.0)) "k")
      (< n 1000000) (str (Math/round (double (/ n 1000))) "k")
      (< n 10000000) (str (format "%.1f" (/ (double n) 1000000.0)) "M")
      :else (str (Math/round (double (/ n 1000000))) "M"))))

(defn format-cwd-for-footer
  "Replace the home prefix of cwd with ~ (pi: formatCwdForFooter). Returns
   cwd unchanged when it is not inside home — or when the two cannot be
   compared: on Windows a drive-absolute home against a drive-relative cwd
   makes fs/relativize throw, and the footer must render anyway. The
   rendered path is /-separated (display only)."
  [cwd home]
  (if (and home (seq home))
    (let [rel (try (some-> (fs/relativize (fs/path home) (fs/path cwd))
                           str
                           (str/replace fs/file-separator "/"))
                   (catch Exception _ nil))]
      (cond
        (nil? rel) cwd
        (empty? rel) "~"
        (or (= rel "..") (str/starts-with? rel "../")) cwd
        :else (str "~/" rel)))
    cwd))

(defn- sanitize-status-text
  "Sanitize extension status text for a single-line display (pi:
   sanitizeStatusText): collapse whitespace, strip newlines/tabs/CR."
  [text]
  (-> text
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      str/trim))

(defcomponent FooterComponent nil [provider-atom theme-atom cache-atom
                                   extension-statuses-atom auto-compact-atom]
  (render [this width]
    (track! this width
      (let [thm @theme-atom
            provider @provider-atom
            session (fdp/fdp-get-session provider)
            ;; The fdp getters below deref their atoms outside this body
            ;; (plain derefs in helper fns are invisible to the lexical
            ;; rewrite) — declare them as explicit deps so any change
            ;; invalidates this cache (tui.md §4). Session ENTRIES mutate
            ;; in place (Session carries an :entries atom), so the live
            ;; entry vector is a dep too; the dep set re-diffuses each
            ;; pass when the session record itself is replaced.
            _ (track-deps @(:model-atom provider)
                          @(:provider-atom provider)
                          @(:thinking-atom provider)
                          @(:reasoning-atom provider)
                          @(:context-window-atom provider)
                          @(:cwd-atom provider)
                          @(:git-branch-atom provider)
                          @(:session-atom provider)
                          (when session @(:entries session)))
            auto @auto-compact-atom
            usage (fdp/fdp-usage-totals provider)
            hit-rate (fdp/fdp-latest-cache-hit-rate provider)
            tokens (fdp/fdp-context-tokens provider)
            window (fdp/fdp-get-context-window provider)
            home (or (System/getenv "HOME") (System/getenv "USERPROFILE"))
            branch (fdp/fdp-get-git-branch provider)
            pwd (str (format-cwd-for-footer (fdp/fdp-get-cwd provider) home)
                     (when branch (str " (" branch ")"))
                     ;; pi: footer line 1 shows `pwd • sessionName` when set
                     (when-let [session-name (fdp/fdp-get-session-name provider)]
                       (str " • " session-name)))
            ;; cursive Cyrillic К mark before the folder, in the info-screen
            ;; accent (light blue) color; the hosting-runtime letter follows
            ;; it (b: babashka, j: jolt)
            k-mark (str (theme/italic (theme/fg thm :accent (str "К" (host/mark)))) " ")
            pwd-line (u/truncate-to-width (str k-mark (theme/dim pwd)) width (theme/dim "..."))
            ;; ── Stats left (pi: statsParts) ──────────────────────────────
            stats-parts (cond-> []
                          (pos? (:input usage)) (conj (str "↑" (format-tokens (:input usage))))
                          (pos? (:output usage)) (conj (str "↓" (format-tokens (:output usage))))
                          (pos? (:cache-read usage)) (conj (str "R" (format-tokens (:cache-read usage))))
                          (pos? (:cache-write usage)) (conj (str "W" (format-tokens (:cache-write usage)))))
            stats-parts (if (and (or (pos? (:cache-read usage)) (pos? (:cache-write usage)))
                                 hit-rate)
                          (conj stats-parts (str "CH" (format "%.1f" hit-rate) "%"))
                          stats-parts)
            ;; ── Cost (pi: `$${usageTotals.cost.toFixed(3)}`, last stats part) ──
            stats-parts (cond-> stats-parts
                          (pos? (:cost usage)) (conj (str "$" (format "%.3f" (:cost usage)))))
            ;; ── Context % (pi: contextPercentDisplay, colored by usage) ──
            percent (when (and window (pos? window) tokens)
                      (* 100.0 (/ tokens window)))
            auto-indicator (if auto " (auto)" "")
            window-display (when window (format-tokens window))
            ctx-display (if percent
                          (str (format "%.1f" percent) "%/" window-display auto-indicator)
                          (if window
                            (str "?/" window-display auto-indicator)
                            (if tokens
                              (str (format-tokens tokens) " tokens")
                              "? tokens")))
            ctx-colored (cond
                          (and percent (> percent 90)) (theme/fg thm :error ctx-display)
                          (and percent (> percent 70)) (theme/fg thm :warning ctx-display)
                          :else ctx-display)
            stats-left (str/join " " (conj stats-parts ctx-colored))
            ;; ── Model (kmet: always provider/model, pi: (provider) model
            ;;    only with multiple providers) ────────────────────────────
            model-name (or (fdp/fdp-get-model provider) "no-model")
            provider-name (fdp/fdp-get-provider provider)
            model-display (if (some? provider-name)
                            (str (name provider-name) "/" model-name)
                            model-name)
            ;; ── Thinking suffix, pi format: reasoning models show the
            ;;    level always — " • thinking off" when off, " • <level>"
            ;;    otherwise; non-reasoning models show none ───────────────
            thinking-level (or (fdp/fdp-get-thinking provider) :off)
            model-side (if (fdp/fdp-get-reasoning provider)
                         (if (= thinking-level :off)
                           (str model-display " • thinking off")
                           (str model-display " • " (name thinking-level)))
                         model-display)
            ;; ── Assemble line 2 (kmet: stats + provider/model both
            ;;    left-aligned with a gap; pi right-aligns the model) ──────
            stats-left-w0 (u/visible-width stats-left)
            [stats-left stats-left-w] (if (> stats-left-w0 width)
                                        (let [t (u/truncate-to-width stats-left width "...")]
                                          [t (u/visible-width t)])
                                        [stats-left stats-left-w0])
            min-padding 2
            model-w (u/visible-width model-side)
            total-needed (+ stats-left-w min-padding model-w)
            stats-line (if (<= total-needed width)
                         (str stats-left
                              (apply str (repeat min-padding \space))
                              model-side)
                         ;; Too narrow for stats + model: the model wraps to
                         ;; its own line, left-aligned
                         stats-left)
            model-line (when (> total-needed width)
                         (if (> model-w width)
                           (u/truncate-to-width model-side width "...")
                           model-side))
            ;; dim the parts independently — stats-left may contain colors
            ;; whose fg resets would clear an outer dim wrapper
            dim-left (theme/dim stats-left)
            dim-remainder (theme/dim (subs stats-line (count stats-left)))
            ;; ── Extension statuses (line 3, pi) ───────────────────────────
            ;; pi renders extension-set statuses verbatim (only the ellipsis
            ;; is dimmed) so an extension may carry its own accent color —
            ;; kmet dim-wrapped these previously, muting any embedded color.
            ext-statuses (->> @extension-statuses-atom
                              (sort-by key)
                              (keep (fn [[_k v]] (when v (sanitize-status-text v))))
                              (remove str/blank?))
            ext-line (when (seq ext-statuses)
                       (u/truncate-to-width
                        (str/join " " ext-statuses)
                        width (theme/dim "...")))]
        (into [pwd-line (str dim-left dim-remainder)]
              (cond-> []
                model-line (conj (theme/dim model-line))
                ext-line (conj ext-line)))))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  "Create a FooterComponent.
   Options:
     :theme      — Theme record (default dark-theme)
     :provider   — FooterDataProvider (default a bare one with no session)
     :auto-compact — whether auto-compaction is enabled (shows \" (auto)\")"
  [& {:keys [theme provider auto-compact]
      :or {theme theme/dark-theme}}]
  (map->FooterComponent {:provider-atom (atom (or provider (fdp/make-footer-data-provider)))
                         :theme-atom (atom theme)
                         :cache-atom (atom nil)
                         :extension-statuses-atom (atom {})
                         :auto-compact-atom (atom (boolean auto-compact))}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn footer-set-theme!
  "Switch the footer's theme (live re-theme on theme changes). The reset
   schedules the frame itself — theme-atom is a tracked dep of the render."
  [comp theme]
  (reset! (:theme-atom comp) theme))

(defn footer-set-extension-status!
  "Set/clear a keyed extension status shown on footer line 3 (pi:
   FooterDataProvider.setExtensionStatus). Statuses render verbatim,
   sorted by key — an extension may carry its own accent color (only the
   truncation ellipsis is dimmed). Pass nil text to clear the key."
  [comp key text]
  (swap! (:extension-statuses-atom comp)
         (fn [m] (if (nil? text) (dissoc m key) (assoc m key text))))
  nil)

(defn footer-set-auto-compact!
  "Enable/disable the \" (auto)\" context indicator (pi:
   setAutoCompactEnabled)."
  [comp enabled?]
  (reset! (:auto-compact-atom comp) (boolean enabled?))
  nil)

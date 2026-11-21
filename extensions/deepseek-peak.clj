(ns kmet.extensions.deepseek-peak
  "/deepseek-peak — DeepSeek API peak/off-peak hours in your local time zone.

   Shows when DeepSeek's API bills at the full (peak) rate vs the discounted
   off-peak rate, converted to the machine's local time zone, plus whether
   it is currently peak or off-peak and how long until the next switch.
   Hours per DeepSeek's official pricing page (api-docs.deepseek.com):
   \"Peak hours (in UTC): 1:00–4:00 AM and 6:00–10:00 AM, Monday to Friday.
   All other hours are off-peak.\" Off-peak is half the peak rate, so the
   whole UTC weekend is off-peak.

   The weekday qualifier is a UTC boundary, not a local one: west of UTC
   the week's first peak block (01:00 UTC Monday) lands on Sunday evening
   local time.

   The window table lives in `peak-windows-utc`; update it there if
   DeepSeek ever changes its schedule.

   Display follows /session: the panel is appended to the chat history as
   an :info message via kmet.extension/ui-chat-info — part of the live
   transcript, no overlay and nothing to dismiss, never sent to the LLM,
   not persisted across restarts. It is laid out as one unwrapped logical
   line per block: the chat's Markdown view word-wraps each paragraph to
   the message width, so a wide terminal shows every block on a single
   line while a narrow one wraps at word boundaries.
   Headless/print mode falls back to a one-line flash — as does a host
   whose running instance predates the bridge (/reload refreshes extension
   files, not host code).

   The local time zone is detected explicitly: on Termux/Android the JVM
   falls back to GMT (no /etc/localtime) while Android keeps the real zone
   in a system property — so TZ, getprop, /etc/timezone and the
   /etc/localtime symlink are consulted before the JVM default.

   Usage: symlink or copy this file into ~/.kmet/agent/extensions/ (global)
   or .kmet/extensions/ (project-local), then restart kmet or run /reload."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.tui.theme :as theme]))

;; ─── The schedule (api-docs.deepseek.com/quick_start/pricing) ──────────────

(def ^:private peak-windows-utc
  "Peak windows as {:start [hour minute] :end [hour minute]} pairs in UTC —
   they apply only on UTC weekdays (see utc-weekend?). Everything outside
   these windows is off-peak (half the peak rate)."
  [{:start [1 0], :end [4 0]}
   {:start [6 0], :end [10 0]}])

(defn- utc-weekend?
  "True when UTC calendar day DATE is a Saturday or Sunday — no peak windows
   exist then: the whole UTC weekend is off-peak.

   The weekday qualifier is a UTC boundary, not a local one: Sunday 18:00
   US Pacific is already Monday 01:00 UTC and bills at peak."
  [date]
  (let [dow (.getDayOfWeek date)]
    (or (= dow java.time.DayOfWeek/SATURDAY)
        (= dow java.time.DayOfWeek/SUNDAY))))

;; ─── Time helpers (java.time — comparisons are instant-based, so DST and
;;     fractional-offset zones come out right automatically) ────────────────

(def ^:private utc java.time.ZoneOffset/UTC)

(def ^:private hh-mm (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))
(def ^:private dow-hh-mm (java.time.format.DateTimeFormatter/ofPattern "EEE HH:mm"))

(defn- parse-zone
  "ZONE-STRING as a ZoneId, or nil when blank/unparseable."
  [zone-string]
  (when (and zone-string (not (str/blank? zone-string)))
    (try
      (java.time.ZoneId/of (str/trim zone-string))
      (catch Exception _ nil))))

(defn- zone-from-android-prop
  "The Android system timezone (Termux: the JVM default is GMT because
   there is no /etc/localtime — Android keeps the user's zone in a system
   property instead)."
  []
  (try
    (parse-zone (:out (proc/sh "getprop" "persist.sys.timezone")))
    (catch Exception _ nil)))

(defn- zone-from-localtime-link
  "The zone encoded in /etc/localtime's symlink target (Debian-style)."
  []
  (try
    (->> (str (fs/read-link "/etc/localtime"))
         (re-find #"/zoneinfo/(.+)$")
         (second)
         (parse-zone))
    (catch Exception _ nil)))

(def ^:private local-zone
  "The machine's real local zone. Detection order: TZ env var → Android
   system property → /etc/timezone → /etc/localtime symlink → JVM default.
   Detected once per extension load (/reload re-runs it)."
  (delay
    (or (parse-zone (System/getenv "TZ"))
        (zone-from-android-prop)
        (try (parse-zone (slurp "/etc/timezone")) (catch Exception _ nil))
        (zone-from-localtime-link)
        (java.time.ZoneId/systemDefault))))

(defn- now-local
  "NOW as a ZonedDateTime in the detected local zone."
  []
  (java.time.ZonedDateTime/now @local-zone))

(defn- windows-on-utc-date
  "The peak windows dated UTC calendar day DATE, or nil when DATE is a UTC
   weekend (off-peak all day, so no windows)."
  [date]
  (when-not (utc-weekend? date)
    (for [{[sh sm] :start, [eh em] :end} peak-windows-utc]
      {:start (java.time.ZonedDateTime/of (.atTime date sh sm) utc)
       :end (java.time.ZonedDateTime/of (.atTime date eh em) utc)})))

(defn- windows-around
  "Peak windows from NOW's UTC date through the next four days. The window
   containing NOW is always dated NOW's UTC date (windows never cross
   midnight UTC), and four days always reach the next peak day across a UTC
   weekend (Friday's last window ends 10:00 UTC, the next starts Monday
   01:00)."
  [now]
  (let [date (.toLocalDate (.withZoneSameInstant now utc))]
    (mapcat #(windows-on-utc-date (.plusDays date %)) (range 0 5))))

(defn- next-peak-date
  "The upcoming UTC weekday: NOW's UTC date when it is a weekday, otherwise
   the following Monday. Picks the day the panel renders its window lines
   for, so a UTC weekend still shows the upcoming peak windows."
  [now]
  (let [date (.toLocalDate (.withZoneSameInstant now utc))]
    (first (remove utc-weekend? (map #(.plusDays date %) (range 0 4))))))

(defn- peak-status
  "Where NOW sits relative to the schedule:
     {:phase :peak | :off-peak, :next-change ZonedDateTime}
   Peak windows run 01:00–04:00 and 06:00–10:00 UTC on UTC weekdays; the
   whole UTC weekend is off-peak. :next-change is the instant the current
   phase ends: the end of the window containing NOW when peak, otherwise
   the start of the next peak window — a UTC weekend is one contiguous
   off-peak block, so no transition occurs inside it."
  [now]
  (let [wins (windows-around now)
        current (some (fn [{:keys [start end] :as w}]
                        (when (and (not (.isBefore now start))
                                   (.isBefore now end))
                          w))
                      wins)]
    {:phase (if current :peak :off-peak)
     :next-change (or (:end current)
                      (->> wins
                           (map :start)
                           (filter #(.isBefore now %))
                           (apply min-key #(.toEpochSecond %))))}))

(defn- format-duration
  "DURATION as a compact human string: \"<1m\", \"42m\", \"1h\", \"1h 37m\"."
  [duration]
  (let [mins (.toMinutes duration)]
    (cond
      (< mins 1) "<1m"
      (< mins 60) (str mins "m")
      :else (let [h (quot mins 60), m (rem mins 60)]
              (if (zero? m) (str h "h") (format "%dh %02dm" h m))))))

(defn- fmt-local
  "Format ZDT shifted into ZONE, HH:mm (with weekday when WITH-DOW?)."
  ([zdt zone] (fmt-local zdt zone false))
  ([zdt zone with-dow?]
   (.format (if with-dow? dow-hh-mm hh-mm)
            (.withZoneSameInstant zdt zone))))

(defn- summary-line
  "One-line status for headless mode (the flash)."
  [now]
  (let [zone (.getZone now)
        {:keys [phase next-change]} (peak-status now)
        remaining (format-duration (java.time.Duration/between now next-change))]
    (if (= :peak phase)
      (format "DeepSeek: PEAK (full rate) — off-peak at %s (in %s)"
              (fmt-local next-change zone true) remaining)
      (format "DeepSeek: OFF-PEAK (half rate) — peak at %s (in %s)"
              (fmt-local next-change zone true) remaining))))

;; ─── The panel (/session-style chat info message) ─────────────────────────

(defn- window-span
  "One window as a compact span — \"04:00–07:00\" — for a comma-joined list
   sharing one logical line. WITH-DOW? prefixes the weekday of the window's
   start: the local view needs it, since west of UTC a UTC-Monday window
   starts on Sunday local time, a day the UTC rule does not imply."
  [w zone with-dow?]
  (str (fmt-local (:start w) zone with-dow?) "–" (fmt-local (:end w) zone)))

(defn- peak-panel-text
  "The panel as styled plain text for the chat :info message (dim labels,
   plain times and phase line — the /session look: a bracketed
   [DeepSeek Peak Hours] label above, ANSI passed through by the markdown
   view, so no theme instance is needed: only the global theme/dim and the
   message's own text color). One unwrapped logical line per block — UTC
   windows first (the published rule), then the same windows on the
   detected local clock, then the off-peak note, then the current phase
   with the switch countdown — blank-line separated. The chat's Markdown
   view word-wraps each paragraph to the message width: wide terminals show
   every block on a single line, narrow ones wrap at word boundaries."
  []
  (let [now (now-local)
        zone (.getZone now)
        {:keys [phase next-change]} (peak-status now)
        remaining (format-duration (java.time.Duration/between now next-change))
        peak? (= :peak phase)
        wins (windows-on-utc-date (next-peak-date now))]
    (str (theme/dim "Peak — full rate (Mon–Fri UTC): ")
         (str/join ", " (map #(window-span % utc false) wins)) "\n\n"
         (theme/dim (str "Local (" zone "): "))
         (str/join ", " (map #(window-span % zone true) wins)) "\n\n"
         (theme/dim "Off-peak — half rate: all other hours, incl. Sat/Sun UTC")
         "\n\n"
         (if peak? "● PEAK now" "● OFF-PEAK now")
         (theme/dim (format " — ends %s (in %s)"
                            (fmt-local next-change zone true) remaining)))))

(defn- show-peak-info!
  "Append the panel as an :info chat message (the /session display style —
   stays in the transcript, nothing to dismiss); flash a one-line summary
   otherwise. Feature-detects the :chat-info capability: a kmet instance
   started before the bridge existed (host code only changes on restart,
   not on /reload) has no such key, and referencing the missing var would
   fail the whole extension load — degrade to the flash instead."
  [api ctx]
  (if-let [chat-info (and (= :interactive (:mode ctx))
                          (get-in api [:ui :chat-info]))]
    (chat-info "DeepSeek Peak Hours" (peak-panel-text))
    (ext/ui-notify api (summary-line (now-local)))))

(defn init
  "Register the /deepseek-peak command."
  [api]
  (ext/register-command!
   api {:name "deepseek-peak"
        :description "DeepSeek API peak/off-peak hours in your local time zone"
        :handler (fn [ctx _args] (show-peak-info! api ctx))}))

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/deepseek-peak
   :apply (fn [_ctx api] (init api) nil)})

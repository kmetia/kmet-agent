(ns kmet.extensions.mcp-adapter.output-guard
  "Bound model-facing MCP output (pi: mcp-output-guard.ts, adapted to
   kmet's string-content tool results).

   Text output is capped at max-bytes / max-lines (defaults 50 KiB / 2000
   lines) and spilled to a temp file when oversized; the returned text is
   a head-preview plus a notice pointing at the full file. The raw MCP
   result (details :mcp-result) is kept when its JSON fits
   details-max-bytes (16 KiB), otherwise replaced with a compact summary
   and spilled too.

   Options (settings :output-guard — false disables, a map tunes):
     :max-bytes, :max-lines, :details-max-bytes
   Env kill switch: MCP_OUTPUT_GUARD=0 (also false/no/off) disables,
   1/true/yes/on enables. Image content is not part of kmet's string
   results — the pass-through case does not apply."
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.string :as str]))

(def default-max-bytes (* 50 1024))
(def default-max-lines 2000)
(def default-details-max-bytes (* 16 1024))

(defn- positive-int
  [v]
  (when (and (number? v) (pos? v) (not (Double/isNaN (double v))))
    (int v)))

(defn- env-kill-switch
  "MCP_OUTPUT_GUARD — nil when unset, else true/false."
  []
  (let [value (some-> (System/getenv "MCP_OUTPUT_GUARD")
                      str/trim
                      str/lower-case)]
    (cond
      (nil? value) nil
      (contains? #{"0" "false" "no" "off"} value) false
      (contains? #{"1" "true" "yes" "on"} value) true
      :else nil)))

(defn resolve-options
  "The effective guard options from the merged SETTINGS (pi
   resolveMcpOutputGuardOptions)."
  [settings]
  (let [configured (:output-guard settings)
        tuning (when (map? configured) configured)]
    {:enabled (or (env-kill-switch)
                  (not (false? configured)))
     :max-bytes (or (positive-int (:max-bytes tuning)) default-max-bytes)
     :max-lines (or (positive-int (:max-lines tuning)) default-max-lines)
     :details-max-bytes (or (positive-int (:details-max-bytes tuning))
                            default-details-max-bytes)}))

(defn- byte-length
  [s]
  (alength (.getBytes (str s) "UTF-8")))

(defn- text-stats-of
  [text]
  {:bytes (byte-length text)
   :lines (if (str/blank? text) 0 (count (str/split text #"\n")))})

(defn- truncate-string-to-bytes
  "Cut VALUE so its UTF-8 encoding fits MAX-BYTES without splitting a
   multi-byte char."
  [value max-bytes]
  (if (<= (byte-length value) max-bytes)
    value
    (let [bytes (.getBytes value "UTF-8")
          end (loop [end (max 0 (int max-bytes))]
                (if (and (pos? end) (= 0x80 (bit-and (aget bytes end) 0xc0)))
                  (recur (dec end))
                  end))]
      (String. bytes 0 end "UTF-8"))))

(defn- truncate-head
  "The first MAX-LINES lines of TEXT, each line byte-capped so the total
   fits MAX-BYTES (pi truncateHead)."
  [text max-bytes max-lines]
  (let [lines (str/split text #"\n")
        out (java.util.ArrayList.)]
    (loop [lines lines bytes 0]
      (cond
        (or (empty? lines) (>= (.size out) max-lines)) nil
        :else
        (let [line (first lines)
              separator-bytes (if (pos? (.size out)) 1 0)
              line-bytes (byte-length line)]
          (if (> (+ bytes separator-bytes line-bytes) max-bytes)
            (let [remaining (- max-bytes bytes separator-bytes)]
              (when (pos? remaining)
                (.add out (truncate-string-to-bytes line remaining))))
            (do (.add out line)
                (recur (rest lines) (+ bytes separator-bytes line-bytes)))))))
    (str/join "\n" (vec out))))

(defn- format-size
  [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (str (format "%.1f" (/ bytes 1024.0)) " KiB")
    :else (str (format "%.1f" (/ bytes (* 1024.0 1024.0))) " MiB")))

(defn- temp-dir-root
  "A writable temp dir: TMPDIR env when set (Termux), else the JVM tmpdir."
  []
  (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn- save-artifact
  "Spill TEXT to a fresh temp file (0600). Returns {:path str} or
   {:error str}."
  [kind text]
  (try
    ;; fs/create-temp-dir is broken in this bb version — build manually
    (let [dir (str (temp-dir-root) "/mcp-output-" (System/nanoTime))
          path (str dir "/" kind ".txt")]
      (fs/create-dirs dir)
      (spit path text)
      (try (fs/set-posix-file-permissions path "rw-------") (catch Exception _ nil))
      {:path path})
    (catch Exception e
      {:error (ex-message e)})))

(defn- format-truncation-notice
  [stats path error]
  (let [base (str "[MCP text output truncated: original "
                  (format "%,d" (:lines stats)) " lines / "
                  (format-size (:bytes stats)) ".")]
    (if path
      (str base " Full text saved to: " path
           " — use read with offset/limit or grep to inspect.]")
      (str base " Full output could not be saved: " (or error "unknown error") "]"))))

(defn guard-text
  "Bound TEXT (a tool result's content). Returns {:text str :guard
   nil|{:truncated true :original-bytes n :returned-bytes n
   :original-lines n :returned-lines n :full-output-path str}}."
  [text options]
  (let [text (if (str/blank? (or text "")) "(empty result)" (str text))
        max-bytes (or (:max-bytes options) default-max-bytes)
        max-lines (or (:max-lines options) default-max-lines)]
    (if (false? (:enabled options))
      {:text text :guard nil}
      (let [stats (text-stats-of text)]
        (if (and (<= (:bytes stats) max-bytes)
                 (<= (:lines stats) max-lines))
          {:text text :guard nil}
          (let [{:keys [path error]} (save-artifact "output" text)
                notice (format-truncation-notice stats path error)
                notice-stats (text-stats-of (str "\n\n" notice))
                preview (truncate-head text
                                       (max 0 (- max-bytes (:bytes notice-stats)))
                                       (max 0 (- max-lines (:lines notice-stats))))
                final (str preview "\n\n" notice)
                final-stats (text-stats-of final)]
            {:text final
             :guard (cond-> {:truncated true
                             :original-bytes (:bytes stats)
                             :returned-bytes (:bytes final-stats)
                             :original-lines (:lines stats)
                             :returned-lines (:lines final-stats)}
                      path (assoc :full-output-path path))}))))))

(defn bound-mcp-result
  "Keep RAW when its JSON fits DETAILS-MAX-BYTES; else a compact summary
   with the raw JSON spilled to a temp file (pi boundMcpResult)."
  [raw details-max-bytes]
  (let [raw-json (try (json/generate-string raw)
                      (catch Exception _ (pr-str raw)))
        raw-bytes (byte-length raw-json)]
    (if (<= raw-bytes details-max-bytes)
      raw
      (let [{:keys [path error]} (save-artifact "mcp-result" raw-json)
            content (or (:content raw) [])]
        {:omitted true
         :reason "Raw MCP result exceeded the details size limit and was replaced with this summary to keep session context bounded."
         :is-error (true? (:isError raw))
         :content-blocks (count content)
         :content-summary (vec (take 20
                                     (map (fn [block]
                                            (if (and (map? block)
                                                     (= "text" (:type block)))
                                              {:type "text"
                                               :bytes (byte-length (or (:text block) ""))
                                               :text-omitted true}
                                              {:type (or (:type block) "unknown")
                                               :omitted true}))
                                          content)))
         :raw-result-bytes raw-bytes
         :full-result-path path
         :write-error error}))))

(defn guarded-details
  "The details map for a guarded result: :output-guard when truncated,
   :mcp-result when bound. Both present only when meaningful (pi
   guardedMcpDetails)."
  [guard mcp-result]
  (cond-> {}
    guard (assoc :output-guard guard)
    (some? mcp-result) (assoc :mcp-result mcp-result)))

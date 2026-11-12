(ns kmet.app.tools.bash-output
  "Pure output formatting and truncation shared by bash, read, codemode and
   the TUI renderers (pi: bash-executor.ts formatSize / truncateHead /
   truncateTail).

   Kept apart from kmet.app.tools.bash-executor so formatting an output
   preview or truncating a result does not load the process engine."
  (:require [clojure.string :as str]
            [kmet.libs.terminal :as terminal]))

(def DEFAULT-MAX-LINES
  "Default output line budget (pi: DEFAULT_MAX_LINES)."
  2000)

(def DEFAULT-MAX-BYTES
  "Default output byte budget, 50 KiB (pi: DEFAULT_MAX_BYTES)."
  (* 50 1024))

;; ─── Format utilities (pi: formatSize) ──────────────────────────────────────

(defn format-size
  "Format bytes as human-readable size (pi: formatSize).
   Returns strings like '50B', '12.5KB', '1.5MB'."
  [bytes]
  (let [b (int bytes)]
    (cond
      (< b 1024) (str b "B")
      (< b (* 1024 1024)) (str (format "%.1f" (float (/ b 1024))) "KB")
      :else (str (format "%.1f" (float (/ b 1024 1024))) "MB"))))

(defn byte-length
  "UTF-8 byte length (pi: Buffer.byteLength)."
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- split-lines-for-counting
  "Pi: splitLinesForCounting — '' -> [], else split on \"\\n\" and drop a
   single trailing empty entry when the content ends with \"\\n\"."
  [content]
  (if (zero? (count content))
    []
    (let [parts (str/split content #"\n" -1)]
      (if (str/ends-with? content "\n")
        (pop (vec parts))
        (vec parts)))))

(defn- truncate-string-to-bytes-from-end
  "Pi: truncateStringToBytesFromEnd — tail slice that respects UTF-8 boundaries."
  [^String s max-bytes]
  (let [bs (.getBytes s "UTF-8")
        n (alength bs)]
    (if (<= n max-bytes)
      s
      (let [start (- n max-bytes)
            start (loop [i start]
                    (if (and (< i n)
                             (= (bit-and (bit-and (aget bs i) 0xFF) 0xC0) 0x80))
                      (recur (inc i))
                      i))
            sliced (java.util.Arrays/copyOfRange bs start n)]
        (String. sliced "UTF-8")))))

(defn sanitize-output
  "Pi: sanitizeBinaryOutput() — strips ANSI codes and control chars (except
   \\t \\n \\r) and Unicode format characters (U+FFF9-U+FFFB). The ANSI
   strip is the shared host-branched scanner
   (kmet.libs.terminal/strip-ansi-codes); the CR pass is guarded so a LF-only
   chunk (the common case) skips both replaces."
  [s]
  (let [s (terminal/strip-ansi-codes s)
        s (if (str/includes? s "\r")
            (-> s (str/replace #"\r\n" "\n") (str/replace #"\r" "\n"))
            s)]
    ;; Pi: filter out control chars + Unicode format characters
    (str/replace s #"[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]" "")))

(defn truncate-head
  "Pi: truncateHead — keep first N lines/bytes, never partial lines."
  [content & {:keys [max-lines max-bytes]
              :or {max-lines DEFAULT-MAX-LINES max-bytes DEFAULT-MAX-BYTES}}]
  (let [total-bytes (byte-length content)
        lines (split-lines-for-counting content)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil
       :total-lines total-lines :total-bytes total-bytes
       :output-lines total-lines :output-bytes total-bytes
       :last-line-partial false :first-line-exceeds-limit false
       :max-lines max-lines :max-bytes max-bytes}
      (let [first-line-bytes (if (seq lines) (byte-length (first lines)) 0)]
        (if (> first-line-bytes max-bytes)
          {:content "" :truncated true :truncated-by :bytes
           :total-lines total-lines :total-bytes total-bytes
           :output-lines 0 :output-bytes 0
           :last-line-partial false :first-line-exceeds-limit true
           :max-lines max-lines :max-bytes max-bytes}
          (let [[out-lines out-bytes truncated-by]
                (loop [i 0 acc [] b 0 tb :lines]
                  (if (or (>= i (count lines)) (>= (count acc) max-lines))
                    [acc b tb]
                    (let [line (nth lines i)
                          lb (+ (byte-length line) (if (pos? (count acc)) 1 0))]
                      (if (> (+ b lb) max-bytes)
                        [acc b :bytes]
                        (recur (inc i) (conj acc line) (+ b lb) tb)))))
                truncated-by (if (and (= (count out-lines) max-lines)
                                      (<= out-bytes max-bytes)
                                      (< (count out-lines) total-lines))
                               :lines truncated-by)
                ;; pi keeps :lines when the loop filled max-lines without
                ;; hitting the byte limit; the accumulator above already uses
                ;; :lines as default — keep it unless we broke for bytes.
                out-content (str/join "\n" out-lines)
                out-bytes (if (seq out-lines) (byte-length out-content) 0)]
            {:content out-content :truncated true :truncated-by truncated-by
             :total-lines total-lines :total-bytes total-bytes
             :output-lines (count out-lines) :output-bytes out-bytes
             :last-line-partial false :first-line-exceeds-limit false
             :max-lines max-lines :max-bytes max-bytes}))))))

(defn truncate-tail
  "Pi: truncateTail — keep last N lines/bytes, may return partial first line."
  [content & {:keys [max-lines max-bytes]
              :or {max-lines DEFAULT-MAX-LINES max-bytes DEFAULT-MAX-BYTES}}]
  (let [total-bytes (byte-length content)
        lines (split-lines-for-counting content)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil
       :total-lines total-lines :total-bytes total-bytes
       :output-lines total-lines :output-bytes total-bytes
       :last-line-partial false
       :first-line-exceeds-limit false
       :max-lines max-lines :max-bytes max-bytes}
      (let [[out-lines out-bytes last-partial truncated-by]
            (loop [i (dec (count lines)) acc [] b 0 lp false tb :lines]
              (if (or (< i 0) (>= (count acc) max-lines))
                [(vec (reverse acc)) b lp tb]
                (let [line (nth lines i)
                      lb (+ (byte-length line) (if (seq acc) 1 0))]
                  (if (> (+ b lb) max-bytes)
                    (if (empty? acc)
                      (let [t (truncate-string-to-bytes-from-end line max-bytes)]
                        [[t] (byte-length t) true :bytes])
                      [(vec (reverse acc)) b lp :bytes])
                    (recur (dec i) (conj acc line) (+ b lb) lp tb)))))
            truncated-by (if (and (= (count out-lines) max-lines)
                                  (<= out-bytes max-bytes)
                                  (< (count out-lines) total-lines))
                           :lines truncated-by)
            out-content (str/join "\n" out-lines)
            out-bytes (if (seq out-lines) (byte-length out-content) 0)]
        {:content out-content :truncated true :truncated-by truncated-by
         :total-lines total-lines :total-bytes total-bytes
         :output-lines (count out-lines) :output-bytes out-bytes
         :last-line-partial last-partial
         :last-line-bytes (if (seq lines) (byte-length (peek lines)) 0)
         :first-line-exceeds-limit false
         :max-lines max-lines :max-bytes max-bytes}))))

(defn truncation-summary
  "The quantity clause shared by tool footers and the TUI warning: a
   partially cut last line is named by its byte slice, a whole-line cut by
   the kept count, the total and the limit that fired. TAIL? adds 'the last'
   for tail truncation; head callers read the clause without a direction.
   T may name the kept count :output-lines (a truncate result) or
   :shown-lines (a tool result)."
  [t & [tail?]]
  (let [{:keys [truncated-by total-lines output-bytes last-line-partial
                last-line-bytes max-lines max-bytes]} t
        shown (or (:output-lines t) (:shown-lines t))
        tail (when tail? "the last ")]
    (cond
      last-line-partial
      (str "showing last " (format-size (or output-bytes last-line-bytes 0))
           " of line " total-lines
           (when last-line-bytes
             (str " (line is " (format-size last-line-bytes) ")")))

      (= truncated-by :lines)
      (str "showing " tail shown " of " total-lines " lines ("
           (or max-lines DEFAULT-MAX-LINES) " line limit)")

      (= truncated-by :bytes)
      (str "showing " tail shown " of " total-lines " lines ("
           (format-size (or max-bytes DEFAULT-MAX-BYTES)) " limit)")

      :else "output exceeds its limit")))

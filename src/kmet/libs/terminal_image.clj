(ns kmet.libs.terminal-image
  "Kitty terminal image protocol implementation.
   Port of @earendil-works/pi-tui terminal-image.ts. Generic protocol code —
   no app/TUI dependencies."
  (:require [clojure.string :as str])
  (:import [java.util Base64]))

;; ─── Capability detection ─────────────────────────────────────────────────

(def ^:private cached-capabilities (atom nil))
(def default-cell-dimensions {:width-px 9 :height-px 18})
(def ^:private cell-dims (atom default-cell-dimensions))

(defn get-cell-dimensions [] @cell-dims)
(defn set-cell-dimensions! [dims] (reset! cell-dims dims))

(defn detect-capabilities
  []
  (let [term-program (some-> (System/getenv "TERM_PROGRAM") str/lower-case)
        term (some-> (System/getenv "TERM") str/lower-case)
        color-term (some-> (System/getenv "COLORTERM") str/lower-case)
        has-truecolor (or (= color-term "truecolor") (= color-term "24bit"))]
    (cond
      (or (System/getenv "TMUX") (str/starts-with? (or term "") "tmux")
          (str/starts-with? (or term "") "screen"))
      {:images nil :true-color has-truecolor :hyperlinks false}
      (or (System/getenv "KITTY_WINDOW_ID") (= term-program "kitty"))
      {:images :kitty :true-color true :hyperlinks true}
      (or (System/getenv "WEZTERM_PANE") (= term-program "wezterm"))
      {:images :kitty :true-color true :hyperlinks true}
      (or (System/getenv "GHOSTTY_RESOURCES_DIR") (= term-program "ghostty")
          (str/includes? (or term "") "ghostty"))
      {:images :kitty :true-color true :hyperlinks true}
      (or (System/getenv "ITERM_SESSION_ID") (= term-program "iterm.app"))
      {:images :kitty :true-color true :hyperlinks true}
      (or (= term-program "warpterminal") (System/getenv "WARP_SESSION_ID")
          (System/getenv "WARP_TERMINAL_SESSION_UUID"))
      {:images :kitty :true-color true :hyperlinks true}
      (System/getenv "WT_SESSION")
      {:images nil :true-color true :hyperlinks true}
      (= term-program "vscode")
      {:images nil :true-color true :hyperlinks true}
      (= term-program "alacritty")
      {:images nil :true-color true :hyperlinks true}
      :else
      {:images nil :true-color has-truecolor :hyperlinks false})))

(defn get-capabilities []
  (or @cached-capabilities
      (let [caps (detect-capabilities)]
        (reset! cached-capabilities caps)
        caps)))

(defn set-capabilities! [caps] (reset! cached-capabilities caps))
(defn reset-capabilities-cache! [] (reset! cached-capabilities nil))

;; ─── Image ID allocation ─────────────────────────────────────────────────

(defn allocate-image-id [] (inc (rand-int Integer/MAX_VALUE)))

;; ─── Kitty encoding ──────────────────────────────────────────────────────

(def ^:private kitty-prefix "\u001b_G")
(def ^:private kitty-suffix "\u001b\\")
(def ^:private chunk-size 4096)

(def ^:private kitty-format-codes
  "Kitty graphics protocol format codes (f=). PNG/JPEG/GIF are supported
   natively; WebP has no protocol code and falls back to text rendering."
  {"image/png" "100"
   "image/jpeg" "27"
   "image/gif" "28"})

(defn encode-kitty
  [base64-data & {:keys [mime-type columns rows image-id move-cursor]
                  :or {mime-type "image/png" move-cursor true}}]
  (let [params (atom ["a=T" (str "f=" (get kitty-format-codes mime-type "100")) "q=2"])]
    (when (false? move-cursor) (swap! params conj "C=1"))
    (when columns (swap! params conj (str "c=" columns)))
    (when rows (swap! params conj (str "r=" rows)))
    (when image-id (swap! params conj (str "i=" image-id)))
    (let [param-str (str/join "," @params)]
      (if (<= (count base64-data) chunk-size)
        (str kitty-prefix param-str ";" base64-data kitty-suffix)
        (let [chunks (volatile! [])
              n (count base64-data)]
          (loop [offset 0]
            (when (< offset n)
              (let [end' (min (+ offset chunk-size) n)
                    chunk (subs base64-data offset end')
                    is-first (zero? offset)
                    is-last (>= end' n)]
                (vswap! chunks conj
                        (cond
                          is-first (str kitty-prefix param-str ",m=1;" chunk kitty-suffix)
                          is-last (str kitty-prefix "m=0;" chunk kitty-suffix)
                          :else (str kitty-prefix "m=1;" chunk kitty-suffix)))
                (recur end'))))
          (str/join @chunks))))))

;; ─── Image line detection ────────────────────────────────────────────────

(defn is-image-line
  "True when LINE carries a Kitty or iTerm2 inline-image sequence. Both
   markers can appear mid-line (continuation chunks), so includes? is the
   test; it subsumes the starts-with? case, scanning each marker once."
  [line]
  (or (str/includes? line kitty-prefix)
      (str/includes? line "\u001b]1337;File=")))

(defn parse-kitty-image-header
  "Parse the first Kitty APC sequence's parameters (pi: parseKittyImageHeader).
   Returns {:ids [..] :rows n} or nil when the line has no complete header.
   Only the first chunk carries a header — continuation chunks (m=1) are
   skipped by stopping at the first ';' after the prefix."
  [line]
  (let [start (str/index-of line kitty-prefix)]
    (when start
      (let [params-start (+ start (count kitty-prefix))
            params-end (str/index-of line ";" params-start)]
        (when params-end
          (let [ids (atom [])
                rows (atom 1)]
            (doseq [param (str/split (subs line params-start params-end) #",")]
              (let [[k v] (str/split param #"=" 2)]
                (when v
                  (let [n (parse-long v)]
                    (when (and n (pos? n) (<= n 0xffffffff))
                      (case k
                        "i" (swap! ids conj n)
                        "r" (reset! rows n)
                        nil))))))
            {:ids @ids :rows @rows}))))))

(defn extract-kitty-image-ids
  "Image IDs referenced by LINE (pi: extractKittyImageIds)."
  [line]
  (:ids (parse-kitty-image-header line) []))

(defn extract-kitty-image-rows
  "Rows the image in LINE occupies; 1 when LINE has no image header
   (pi: extractKittyImageRows)."
  [line]
  (:rows (parse-kitty-image-header line) 1))

;; ─── Binary helpers ──────────────────────────────────────────────────────

(defn- base64->bytes [s] (.decode (Base64/getDecoder) s))

(defn- bytes->int [ba offset len]
  (loop [i 0 result 0]
    (if (< i len)
      (recur (inc i) (+ (bit-shift-left result 8) (bit-and (aget ba (+ offset i)) 0xff)))
      result)))

;; ─── Dimension parsing ──────────────────────────────────────────────────

(defn get-png-dimensions [base64-data]
  (try
    (let [ba (base64->bytes base64-data)]
      (when (and (>= (alength ba) 24)
                 (= (aget ba 0) (unchecked-byte 0x89))
                 (= (aget ba 1) (unchecked-byte 0x50))
                 (= (aget ba 2) (unchecked-byte 0x4e))
                 (= (aget ba 3) (unchecked-byte 0x47)))
        {:width-px (bytes->int ba 16 4) :height-px (bytes->int ba 20 4)}))
    (catch Exception _ nil)))

(defn get-jpeg-dimensions [base64-data]
  (try
    (let [ba (base64->bytes base64-data) n (alength ba)]
      (when (and (>= n 2)
                 (= (aget ba 0) (unchecked-byte 0xff))
                 (= (aget ba 1) (unchecked-byte 0xd8)))
        (loop [offset 2]
          (when (< offset (- n 9))
            (let [marker (bit-and (aget ba (inc offset)) 0xff)]
              (if (and (>= marker 0xc0) (<= marker 0xc2))
                {:width-px (bytes->int ba (+ offset 7) 2)
                 :height-px (bytes->int ba (+ offset 5) 2)}
                (let [length (bytes->int ba (+ offset 2) 2)]
                  (when (>= length 2)
                    (recur (+ offset 2 length))))))))))
    (catch Exception _ nil)))

(defn get-gif-dimensions [base64-data]
  (try
    (let [ba (base64->bytes base64-data)]
      (when (>= (alength ba) 10)
        (let [sig (String. ba 0 6)]
          (when (or (= sig "GIF87a") (= sig "GIF89a"))
            {:width-px (bytes->int ba 6 2) :height-px (bytes->int ba 8 2)}))))
    (catch Exception _ nil)))

(defn- webp-chunk->dims [chunk ba n]
  (cond
    (= chunk "VP8 ")
    (when (>= n 30)
      {:width-px (bit-and (bytes->int ba 26 2) 0x3fff)
       :height-px (bit-and (bytes->int ba 28 2) 0x3fff)})
    (= chunk "VP8L")
    (when (>= n 25)
      (let [bits (bytes->int ba 21 4)]
        {:width-px (inc (bit-and bits 0x3fff))
         :height-px (inc (bit-and (bit-shift-right bits 14) 0x3fff))}))
    (= chunk "VP8X")
    (when (>= n 30)
      {:width-px (inc (bytes->int ba 24 3))
       :height-px (inc (bytes->int ba 27 3))})
    :else nil))

(defn get-webp-dimensions [base64-data]
  (try
    (let [ba (base64->bytes base64-data) n (alength ba)]
      (when (>= n 30)
        (let [riff (String. ba 0 4) webp (String. ba 8 4) chunk (String. ba 12 4)]
          (when (and (= riff "RIFF") (= webp "WEBP"))
            (webp-chunk->dims chunk ba n)))))
    (catch Exception _ nil)))

(defn get-image-dimensions [base64-data mime-type]
  (case mime-type
    "image/png" (get-png-dimensions base64-data)
    "image/jpeg" (get-jpeg-dimensions base64-data)
    "image/gif" (get-gif-dimensions base64-data)
    "image/webp" (get-webp-dimensions base64-data)
    nil))

;; ─── Cell size calculation ───────────────────────────────────────────────

(defn calculate-image-cell-size
  [img-dim max-width-cells & {:keys [max-height-cells cell-dims']
                              :or {cell-dims' default-cell-dimensions}}]
  (let [max-w (max 1 (int max-width-cells))
        max-h (when max-height-cells (max 1 (int max-height-cells)))
        img-w (max 1 (:width-px img-dim))
        img-h (max 1 (:height-px img-dim))
        cell-w (:width-px cell-dims')
        cell-h (:height-px cell-dims')
        width-scale (/ (* max-w cell-w) img-w)
        height-scale (if max-h (/ (* max-h cell-h) img-h) width-scale)
        scale (min width-scale height-scale)
        cols (Math/ceil (/ (* img-w scale) cell-w))
        rows (Math/ceil (/ (* img-h scale) cell-h))]
    {:columns (max 1 (min max-w (int cols)))
     :rows (max 1 (if max-h (min max-h (int rows)) (int rows)))}))

;; ─── Render ──────────────────────────────────────────────────────────────

(defn render-image
  [base64-data img-dim & {:keys [mime-type max-width-cells max-height-cells image-id move-cursor]
                          :or {mime-type "image/png" max-width-cells 80 move-cursor true}}]
  (let [caps (get-capabilities)]
    (when (and (:images caps) (contains? kitty-format-codes mime-type))
      (let [{:keys [columns rows]} (calculate-image-cell-size img-dim max-width-cells
                                                              :max-height-cells max-height-cells
                                                              :cell-dims' @cell-dims)]
        {:sequence (encode-kitty base64-data
                                 :mime-type mime-type
                                 :columns columns :rows rows
                                 :image-id image-id :move-cursor move-cursor)
         :rows rows
         :image-id image-id}))))

;; ─── Cleanup ─────────────────────────────────────────────────────────────

(defn delete-kitty-image [image-id]
  (str kitty-prefix "a=d,d=I,i=" image-id ",q=2" kitty-suffix))

(defn delete-all-kitty-images []
  (str kitty-prefix "a=d,d=A,q=2" kitty-suffix))

;; ─── Fallback ────────────────────────────────────────────────────────────

(defn image-fallback [mime-type & {:keys [dimensions filename]}]
  (let [parts (cond-> []
                filename (conj filename)
                :always (conj (str "[" mime-type "]"))
                dimensions (conj (str (:width-px dimensions) "x" (:height-px dimensions))))]
    (str "[Image: " (str/join " " parts) "]")))

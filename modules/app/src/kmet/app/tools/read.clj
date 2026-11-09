(ns kmet.app.tools.read
  "Read tool implementation — read file contents with offset/limit.
   Also handles image files by returning base64-encoded data in :images.
   Pi: read.ts — offset is 1-indexed; truncated output carries a
   continuation footer and :truncation metadata for the TUI warning."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.bash-output :as bash-out]
            [kmet.app.tools.mutation :as mutation]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.path :as path]
            [kmet.srce.core :as srce])
  (:import [java.util Base64]
           [java.text Normalizer Normalizer$Form]
           [java.nio.charset StandardCharsets]))

;; ─── Path resolution helpers (pi: paths.ts + path-utils.ts) ───────────────

(def ^:private unicode-spaces-pattern
  #"[\u00A0\u2000-\u200A\u202F\u205F\u3000]")

(def ^:private narrow-nb-space "\u202F")

(defn- windows-platform? []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "windows"))

(defn- normalize-windows-shell-path
  "Pi: normalizeWindowsShellPath — convert Git Bash / mnt / cygdrive drive paths."
  [p]
  (if (or (not (str/starts-with? p "/"))
          (str/starts-with? p "//")
          (str/includes? p "\\"))
    p
    (if-let [[_ drive rest] (re-matches #"^/(?:mnt/|cygdrive/)?([a-zA-Z])(?:/(.*))?" p)]
      (str (str/upper-case drive) ":\\" (when rest (str/replace rest "/" "\\")))
      p)))

(defn- normalize-path
  "Pi: normalizePath — unicode spaces, @ prefix, windows drive, ~ expansion, file://."
  ([input] (normalize-path input {}))
  ([input {:keys [normalize-unicode-spaces strip-at-prefix expand-tilde home-dir trim]
           :or {normalize-unicode-spaces false strip-at-prefix false expand-tilde true trim false}}]
   (let [s (if trim (str/trim input) input)
         s (if normalize-unicode-spaces (str/replace s unicode-spaces-pattern " ") s)
         s (if (and strip-at-prefix (str/starts-with? s "@")) (subs s 1) s)
         s (if (windows-platform?) (normalize-windows-shell-path s) s)
         home (or home-dir (System/getProperty "user.home" ""))]
     (cond
       (and expand-tilde (= s "~")) home
       (and expand-tilde (or (str/starts-with? s "~/")
                             (and (windows-platform?) (str/starts-with? s "~\\"))))
       (str home (subs s 1))
       (str/starts-with? s "file://")
       ;; Pi fileURLToPath: File(URI) decodes the URL and strips the Windows
       ;; drive URL's leading slash (jolt#1198, fixed in jolt v0.8.15).
       (try
         (str (java.io.File. (java.net.URI. s)))
         (catch Exception _ s))
       :else s))))

(defn- resolve-path
  "Pi: resolvePath(input, baseDir, opts) — normalize then resolve against baseDir.
   The default base directory is the runtime cwd (pi: the cwd the tool was
   created with), not the process cwd."
  ([input] (resolve-path input (tool-util/cwd) {}))
  ([input base-dir] (resolve-path input base-dir {}))
  ([input base-dir opts]
   (let [norm (normalize-path input opts)
         base-norm (normalize-path base-dir)]
     (if (fs/absolute? norm)
       (str (fs/normalize norm))
       (str (fs/normalize (fs/path base-norm norm)))))))

(defn- resolve-to-cwd
  "Pi: resolveToCwd — resolve filePath relative to cwd with unicode/@ handling."
  [file-path cwd]
  (resolve-path file-path cwd {:normalize-unicode-spaces true :strip-at-prefix true}))

(defn- try-macos-screenshot-path
  "Pi: tryMacOSScreenshotPath — replace ' AM.'/' PM.' space with narrow NBSP."
  [file-path]
  (str/replace file-path #"(?i) (AM|PM)\." (str narrow-nb-space "$1.")))

(defn- try-nfd-variant
  "Pi: tryNFDVariant — NFD normalize (macOS stores filenames NFD)."
  [file-path]
  (Normalizer/normalize file-path Normalizer$Form/NFD))

(defn- try-curly-quote-variant
  "Pi: tryCurlyQuoteVariant — replace straight apostrophe with U+2019."
  [file-path]
  (str/replace file-path "'" "\u2019"))

(defn- file-exists? [p]
  (try (fs/exists? p) (catch Exception _ false)))

(defn- resolve-read-path
  "Pi: resolveReadPath — try resolved + 4 macOS fallbacks."
  [file-path cwd]
  (let [resolved (resolve-to-cwd file-path cwd)]
    (if (file-exists? resolved)
      resolved
      (let [am-pm (try-macos-screenshot-path resolved)]
        (if (and (not= am-pm resolved) (file-exists? am-pm))
          am-pm
          (let [nfd (try-nfd-variant resolved)]
            (if (and (not= nfd resolved) (file-exists? nfd))
              nfd
              (let [curly (try-curly-quote-variant resolved)]
                (if (and (not= curly resolved) (file-exists? curly))
                  curly
                  (let [nfd-curly (try-curly-quote-variant nfd)]
                    (if (and (not= nfd-curly resolved) (file-exists? nfd-curly))
                      nfd-curly
                      resolved)))))))))))

;; ─── Image MIME sniffing (pi: utils/mime.ts) ─────────────────────────────

(def ^:private png-signature [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])

(defn- u8 [b] (bit-and (int b) 0xFF))

(defn- starts-with-bytes? [^bytes arr offset bytes-vec]
  (and (>= (- (alength arr) offset) (count bytes-vec))
       (every? (fn [[i v]] (= (u8 (aget arr (+ offset i))) v))
               (map-indexed vector bytes-vec))))

(defn- starts-with-ascii? [^bytes arr offset ^String text]
  (let [n (count text)]
    (and (>= (- (alength arr) offset) n)
         (every? (fn [i] (= (u8 (aget arr (+ offset i))) (int (.charAt text i))))
                 (range n)))))

(defn- read-u32-be [^bytes arr offset]
  (+ (* (u8 (aget arr offset)) 0x1000000)
     (* (u8 (aget arr (inc offset))) 0x10000)
     (* (u8 (aget arr (+ offset 2))) 0x100)
     (u8 (aget arr (+ offset 3)))))

(defn- read-u32-le [^bytes arr offset]
  (+ (u8 (aget arr offset))
     (* (u8 (aget arr (inc offset))) 0x100)
     (* (u8 (aget arr (+ offset 2))) 0x10000)
     (* (u8 (aget arr (+ offset 3))) 0x1000000)))

(defn- read-u16-le [^bytes arr offset]
  (+ (u8 (aget arr offset))
     (* (u8 (aget arr (inc offset))) 0x100)))

(defn- is-png? [^bytes buf]
  (and (>= (alength buf) 16)
       (= (read-u32-be buf (count png-signature)) 13)
       (starts-with-ascii? buf 12 "IHDR")))

(defn- is-animated-png? [^bytes buf]
  (loop [off (count png-signature)]
    (if (> (+ off 8) (alength buf))
      false
      (let [chunk-len (read-u32-be buf off)
            type-off (+ off 4)]
        (cond
          (starts-with-ascii? buf type-off "acTL") true
          (starts-with-ascii? buf type-off "IDAT") false
          :else (let [next-off (+ off 8 chunk-len 4)]
                  (if (or (<= next-off off) (> next-off (alength buf)))
                    false
                    (recur next-off))))))))

(defn- is-bmp? [^bytes buf]
  (when (>= (alength buf) 26)
    (let [declared (read-u32-le buf 2)
          pixel-off (read-u32-le buf 10)
          dib (read-u32-le buf 14)]
      (when (and (or (zero? declared) (>= declared 26))
                 (>= pixel-off (+ 14 dib))
                 (or (zero? declared) (< pixel-off declared)))
        (let [[planes bpp]
              (cond
                (= dib 12) [(read-u16-le buf 22) (read-u16-le buf 24)]
                (and (>= dib 40) (<= dib 124))
                (when (>= (alength buf) 30)
                  [(read-u16-le buf 26) (read-u16-le buf 28)])
                :else nil)]
          (when planes
            (and (= planes 1) (contains? #{1 4 8 16 24 32} bpp))))))))

(def supported-image-mime-types
  "The image types a provider-facing image block may carry — exactly what the
   sniffer below can return, so a script-built block cannot send a type the
   API will reject a whole turn over."
  #{"image/jpeg" "image/png" "image/gif" "image/webp" "image/bmp"})

(defn- detect-supported-image-mime-type
  "Pi: detectSupportedImageMimeType — sniff magic bytes."
  [^bytes buf]
  (cond
    (and (>= (alength buf) 3)
         (= (u8 (aget buf 0)) 0xFF)
         (= (u8 (aget buf 1)) 0xD8)
         (= (u8 (aget buf 2)) 0xFF))
    (if (and (>= (alength buf) 4) (= (u8 (aget buf 3)) 0xF7))
      nil
      "image/jpeg")

    (starts-with-bytes? buf 0 png-signature)
    (when (and (is-png? buf) (not (is-animated-png? buf)))
      "image/png")

    (or (starts-with-ascii? buf 0 "GIF87a")
        (starts-with-ascii? buf 0 "GIF89a"))
    "image/gif"

    (and (starts-with-ascii? buf 0 "RIFF")
         (>= (alength buf) 12)
         (starts-with-ascii? buf 8 "WEBP"))
    "image/webp"

    (and (starts-with-ascii? buf 0 "BM")
         (is-bmp? buf))
    "image/bmp"

    :else nil))

(defn image-block
  "The wire image block `{:data <base64> :mime-type <str>}` for BUF when it is
   a supported image, nil otherwise (pi: detectSupportedImageMimeType plus
   base64). The read tool and codemode's emit-image share it, so a script and
   a direct read accept exactly the same images."
  [^bytes buf]
  (when-let [mime-type (and buf (detect-supported-image-mime-type buf))]
    {:data (.encodeToString (Base64/getEncoder) buf) :mime-type mime-type}))

;; ─── Tool implementation ───────────────────────────────────────────────────

(def ^:dynamic *skill-resolver*
  "A fn of the raw read path answering an extension skill's SKILL.md text, or
   nil — bound by a run from the agent's own `:skills` registry
   (`kmet.app.skills/content-resolver`).

   Extension skills (an `ext:path` locator, a jar artifact) have no filesystem
   path, so the <location> in <available_skills> resolves through read from
   memory instead. This is a binding and not a process cell (landing 9b) for
   the same reason the shell settings are: two agents may hold different skill
   sets, and a tool's `execute` receives only its arguments — the run conveys
   what it alone knows. A read outside a run (a test, a snapshot) simply has no
   resolver and treats the path as a file."
  nil)

(defn- resolve-skill-text
  "The in-memory SKILL.md text for RAW-PATH when a bound resolver knows it, or
   nil."
  [raw-path]
  (when-let [f *skill-resolver*]
    (try (f raw-path) (catch Exception _ nil))))

(defn title
  "Quiet one-liner body for the read tool: verb + `~`-shortened path +
   plain `:start-end` range suffix — pure data (no styling, no cwd), or
   nil when the path carries no argument (the quiet branch falls back to
   the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (let [offset (when (map? args) (:offset args))
          limit (when (map? args) (:limit args))]
      (str "read " (path/home-relative raw-path)
           (when (or offset limit)
             (let [start (or offset 1)
                   end (when (and (number? limit) (pos? limit))
                         (+ start limit -1))]
               (str ":" start (when end (str "-" end)))))))))

(defn execute
  "Read file contents with optional offset/limit (1-indexed, pi: read.ts).
   For image files, returns the image data in :images and a text
   description in :content. Truncated text reads return :truncation. The
   filesystem read takes the file's mutation lock, so it cannot observe a
   concurrent write's truncated half."
  [{:keys [path file_path offset limit]} _on-update signal _ctx]
  (let [raw-path (or path file_path)]
    (try
      (if (or (nil? raw-path) (and (string? raw-path) (str/blank? raw-path)))
        {:content "File not found: " :is-error true}
        (let [cwd (tool-util/cwd)
              ;; A non-file locator (an extension skill's ext:path) has no
              ;; filesystem form: on Windows the ':' is an illegal path
              ;; char, so resolution throws before the skill resolver below
              ;; gets a chance. Fall back to the raw string — the resolver
              ;; matches the exact location and cwd-joined forms.
              abs-path (try (resolve-read-path (str raw-path) cwd)
                            (catch Exception _ (str raw-path)))
              f (io/file abs-path)
              on-disk? (file-exists? abs-path)
              ;; Extension skills (ext:path locators) have no filesystem
              ;; path — serve their in-memory body so the <location> in
              ;; <available_skills> loads through read directly.
              skill-text (when-not on-disk? (resolve-skill-text (str raw-path)))
              all-bytes (cond skill-text (.getBytes ^String skill-text "UTF-8")
                              on-disk? (mutation/with-file-lock abs-path signal
                                         #(fs/read-all-bytes f)))]
          (if (nil? all-bytes)
            {:content (str "File not found: " raw-path) :is-error true}
            (let [image (image-block all-bytes)]
              (if image
                ;; Image file — return base64 (pi: processImage would resize; we return raw)
                {:content (str "Read image file [" (:mime-type image) "]")
                 :images [image]}
                ;; Text file — decode as UTF-8 (pi: buffer.toString('utf-8'))
                (let [text-content (String. all-bytes StandardCharsets/UTF_8)
                      ;; Pi: allLines = textContent.split("\n") — keeps trailing empty
                      all-lines (vec (str/split text-content #"\n" -1))
                      total-file-lines (count all-lines)
                      start-line (if offset (max 0 (dec offset)) 0)
                      start-line-display (inc start-line)]
                  (if (>= start-line total-file-lines)
                    {:content (str "Offset " offset " is beyond end of file (" total-file-lines " lines total)")
                     :is-error true}
                    (let [selected-content (if (some? limit)
                                             (let [end (min (+ start-line limit) total-file-lines)]
                                               (str/join "\n" (subvec all-lines start-line end)))
                                             (str/join "\n" (subvec all-lines start-line)))
                          user-limited-lines (when (some? limit)
                                               (let [end (min (+ start-line limit) total-file-lines)]
                                                 (- end start-line)))
                          truncation (bash-out/truncate-head selected-content)
                          output-text (cond
                                        (:first-line-exceeds-limit truncation)
                                        (let [first-line-size (bash-out/format-size
                                                               (bash-out/byte-length (nth all-lines start-line "")))]
                                          (str "[Line " start-line-display " is " first-line-size ", exceeds "
                                               (bash-out/format-size bash-out/DEFAULT-MAX-BYTES)
                                               " limit. Use bash: sed -n '" start-line-display "p' " raw-path
                                               " | head -c " bash-out/DEFAULT-MAX-BYTES "]"))

                                        (:truncated truncation)
                                        (let [end-line-display (+ start-line-display (:output-lines truncation) -1)
                                              next-offset (inc end-line-display)]
                                          (str (:content truncation)
                                               (if (= (:truncated-by truncation) :lines)
                                                 (str "\n\n[Showing lines " start-line-display "-" end-line-display " of "
                                                      total-file-lines ". Use offset=" next-offset " to continue.]")
                                                 (str "\n\n[Showing lines " start-line-display "-" end-line-display " of "
                                                      total-file-lines " (" (bash-out/format-size bash-out/DEFAULT-MAX-BYTES)
                                                      " limit). Use offset=" next-offset " to continue.]"))))

                                        (and user-limited-lines (< (+ start-line user-limited-lines) total-file-lines))
                                        (let [remaining (- total-file-lines (+ start-line user-limited-lines))
                                              next-offset (+ start-line user-limited-lines 1)]
                                          (str (:content truncation) "\n\n[" remaining " more lines in file. Use offset="
                                               next-offset " to continue.]"))

                                        :else (:content truncation))]
                      (cond-> {:content output-text}
                        (:truncated truncation)
                        (assoc :truncation {:total-lines (:total-lines truncation)
                                            :total-bytes (:total-bytes truncation)
                                            :output-lines (:output-lines truncation)
                                            :output-bytes (:output-bytes truncation)
                                            :truncated-by (:truncated-by truncation)
                                            :max-lines (:max-lines truncation)
                                            :max-bytes (:max-bytes truncation)
                                            :first-line-exceeds-limit (:first-line-exceeds-limit truncation)}))))))))))
      (catch Exception e
        {:content (str "Error reading " raw-path ": " (ex-message e)) :is-error true}))))

;; ─── The tool and its row ──────────────────────────────────────────────────

(def definition
  "The `read` tool's record — what the `:tool/read` row mounts."
  (tool/make-tool
   :name "read"
   :label "Read file"
   :description "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete."
   :prompt-snippet "Read file contents"
   :prompt-guidelines ["Use read to examine files instead of cat or sed."]
   :params {:path   {:type :string :description "Path to the file to read (relative or absolute)"}
            :offset {:type :number :description "Line number to start reading from (1-indexed)" :optional? true}
            :limit  {:type :number :description "Maximum number of lines to read" :optional? true}}
   :execute execute
   ;; the cancel signal is the third execute argument (see execute)
   :contextual? true
   :title title))

(srce/defplugin plugin
  {:name :tool/read
   :inject [:tools]
   :apply (fn [ctx config] (registry/mount-tool! ctx definition config))})

(ns kmet.libs.clipboard-win
  "Windows clipboard backend over the host FFI: CF_UNICODETEXT through
   OpenClipboard / EmptyClipboard / SetClipboardData, no subprocess.

   kmet.libs.clipboard resolves this namespace at call time and only on
   Windows, so a Unix host never loads it; user32 and kernel32 are loaded
   here, at namespace load. babashka.ffi and jolt.ffi expose the same
   FFM-style seam for everything used here — the one host difference is the
   string model: Jolt chars are code points, babashka/JVM chars are UTF-16
   code units (see utf16-units)."
  (:require [#?(:bb babashka.ffi :jolt jolt.ffi) :as ffi]))

;; user32 for the clipboard API, kernel32 for the global heap that the
;; clipboard takes ownership of.
(ffi/load-library "user32.dll")
(ffi/load-library "kernel32.dll")

(def ^:private win-open-clipboard (ffi/cfn "OpenClipboard" [:pointer] :int))
(def ^:private win-empty-clipboard (ffi/cfn "EmptyClipboard" [] :int))
(def ^:private win-close-clipboard (ffi/cfn "CloseClipboard" [] :int))
(def ^:private win-global-alloc (ffi/cfn "GlobalAlloc" [:uint :uint64] :pointer))
(def ^:private win-global-lock (ffi/cfn "GlobalLock" [:pointer] :pointer))
(def ^:private win-global-unlock (ffi/cfn "GlobalUnlock" [:pointer] :int))
(def ^:private win-global-free (ffi/cfn "GlobalFree" [:pointer] :pointer))
(def ^:private win-set-clipboard-data
  (ffi/cfn "SetClipboardData" [:uint :pointer] :pointer))

(def ^:private gmem-moveable 0x0002)
(def ^:private cf-unicode-text 13)
;; OpenClipboard fails while another process holds the clipboard open; the
;; documented idiom is a handful of retries (a copy holds it for ms).
(def ^:private open-retries 5)
(def ^:private open-retry-ms 20)

(defn- utf16-units
  "TEXT as UTF-16 code units: the host's own chars on babashka/JVM (already
   code units), a Jolt char (a code point) above the BMP expanded to its
   surrogate pair."
  [text]
  (mapcat (fn [unit]
            (if (> unit 0xFFFF)
              (let [v (- unit 0x10000)]
                [(+ 0xD800 (bit-shift-right v 10))
                 (+ 0xDC00 (bit-and v 0x3FF))])
              [unit]))
          (map int text)))

(defn- open!
  "OpenClipboard, retrying while another process holds it. True when the
   clipboard is open (CloseClipboard is then the caller's)."
  []
  (loop [attempt 0]
    (cond
      (pos? (win-open-clipboard ffi/null)) true
      (< attempt open-retries) (do (Thread/sleep open-retry-ms)
                                   (recur (inc attempt)))
      :else false)))

(defn set-clipboard!
  "Put TEXT on the Windows clipboard as CF_UNICODETEXT. Returns true when
   the system took the data — the GMEM block then belongs to it — false when
   another process keeps the clipboard open or the allocation fails."
  [text]
  (if-not (open!)
    false
    (try
      (let [units (utf16-units text)
            nbytes (* 2 (inc (count units)))
            hmem (when (pos? (win-empty-clipboard))
                   (win-global-alloc gmem-moveable nbytes))]
        (if (or (nil? hmem) (ffi/null? hmem))
          false
          (let [locked (win-global-lock hmem)]
            (if (or (nil? locked) (ffi/null? locked))
              (do (win-global-free hmem) false)
              (let [ptr (ffi/reinterpret locked nbytes)]
                (dotimes [i (count units)]
                  (ffi/write ptr :uint16 (nth units i) (* 2 i)))
                (ffi/write ptr :uint16 0 (* 2 (count units)))
                (win-global-unlock hmem)
                (if (ffi/null? (win-set-clipboard-data cf-unicode-text hmem))
                  (do (win-global-free hmem) false)
                  true))))))
      (finally (win-close-clipboard)))))

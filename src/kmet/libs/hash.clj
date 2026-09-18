(ns kmet.libs.hash
  "Deterministic hashing helpers (pi: packages/ai/src/utils/hash.ts).
   Pure Clojure — no JDK interop — so this namespace loads on both
   babashka and Jolt.")

(defn- imul
  "32-bit signed multiply (JS Math.imul — babashka has no builtin; the
   operands are masked to unsigned 32-bit and the product truncated)."
  [a b]
  (unchecked-int
   (bit-and (unchecked-multiply (bit-and a 0xFFFFFFFF)
                                (bit-and b 0xFFFFFFFF))
            0xFFFFFFFF)))

(defn- ushr32
  "Unsigned 32-bit shift right (JS >>> — the shift applies to the int32
   pattern, not the 64-bit long the value is held in)."
  [x n]
  (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))

(defn short-hash
  "Fast deterministic hash to shorten long strings (pi shortHash — used for
   Mistral's 9-char alphanumeric tool-call ids). Returns a base-36 string of
   the two 32-bit hashes (h2 then h1), matching pi's
   (h2 >>> 0).toString(36) + (h1 >>> 0).toString(36)."
  [s]
  (let [[h1 h2] (reduce (fn [[h1 h2] ch]
                          [(imul (bit-xor h1 ch) 2654435761)
                           (imul (bit-xor h2 ch) 1597334677)])
                        [0xDEADBEEF 0x41C6CE57]
                        (map int s))
        h1 (bit-xor (imul (bit-xor h1 (ushr32 h1 16)) 2246822507)
                    (imul (bit-xor h2 (ushr32 h2 13)) 3266489909))
        h2 (bit-xor (imul (bit-xor h2 (ushr32 h2 16)) 2246822507)
                    (imul (bit-xor h1 (ushr32 h1 13)) 3266489909))]
    (str (Long/toString (bit-and h2 0xFFFFFFFF) 36)
         (Long/toString (bit-and h1 0xFFFFFFFF) 36))))

(def ^:private crc32-table
  "Precomputed CRC-32 table (polynomial 0xEDB88320). Pure Clojure — one
   implementation on every host and in shared extension contexts, rather
   than picking Jolt's java.util.zip.CRC32 vs the JDK's."
  (vec (map (fn [n]
              (loop [c n i 0]
                (if (< i 8)
                  (recur (if (bit-test c 0)
                           (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1))
                           (unsigned-bit-shift-right c 1))
                         (inc i))
                  c)))
            (range 256))))

(defn crc32
  "CRC-32 of a byte array (ISO 3309 / ITU-T V.42, the same checksum
   java.util.zip.CRC32 computes). Pure Clojure — works on both babashka
   and Jolt (and in extension SCI contexts) through one code path.
   Returns an unsigned 32-bit result as a long."
  [ba]
  (let [len (alength ba)]
    (loop [i 0 crc 0xFFFFFFFF]
      (if (< i len)
        (let [idx (bit-and (bit-xor crc (bit-and (aget ba i) 0xFF)) 0xFF)]
          (recur (inc i)
                 (bit-xor (unsigned-bit-shift-right crc 8)
                          (nth crc32-table idx))))
        (bit-xor crc 0xFFFFFFFF)))))

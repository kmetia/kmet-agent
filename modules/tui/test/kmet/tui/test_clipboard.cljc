(ns kmet.tui.test-clipboard
  "Windows clipboard backend tests. kmet.tui.clipboard-win loads user32 and
   kernel32 at namespace load, so this file never requires it: the backend's
   vars are resolved at runtime and every test skips off Windows. The
   roundtrip test is ^:slow — it sets CF_UNICODETEXT and reads it back
   through the same API, so a default run never touches the user's
   clipboard.

   The FFI namespace is resolved at first use, not required: the JVM library
   needs JDK 22+, and an older JVM must still LOAD this file (every test here
   skips there, as the backend falls back to the platform tools)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.clipboard :as clipboard]
            [kmet.tui.host :as host]))

(def ^:private ffi
  "The host's FFI namespace, resolved at first use: babashka.ffi on babashka
   and on the JVM (the standalone org.babashka/ffi), jolt.ffi on Jolt. nil
   when it cannot load — a JVM older than the Class-File/FFM API the library
   needs (JDK 22+)."
  (delay
    (try
      (let [ns-sym (if (host/jolt?) 'jolt.ffi 'babashka.ffi)]
        (require ns-sym)
        ns-sym)
      (catch Exception _ nil))))

(defn- ffi-var
  "The value of the host FFI's var NAMED NM — a function, or the null pointer
   for `null` — or nil when the FFI is unavailable."
  [nm]
  (some-> @ffi (ns-resolve nm) deref))

(def ^:private win-read
  "The clipboard-reader bindings, created on first use (Windows only —
   load-library is unreachable elsewhere)."
  (delay
    (when (and (host/windows?) @ffi)
      ((ffi-var 'load-library) "user32.dll")
      ((ffi-var 'load-library) "kernel32.dll")
      {:open ((ffi-var 'cfn) "OpenClipboard" [:pointer] :int)
       :close ((ffi-var 'cfn) "CloseClipboard" [] :int)
       :get ((ffi-var 'cfn) "GetClipboardData" [:uint] :pointer)
       :lock ((ffi-var 'cfn) "GlobalLock" [:pointer] :pointer)
       :unlock ((ffi-var 'cfn) "GlobalUnlock" [:pointer] :int)})))

(defn- win-var
  "A private var of the Windows backend, resolved at runtime so a Unix host
   never loads the namespace (and clj-kondo needs no private-call
   exemption)."
  [sym]
  (try
    (some-> (requiring-resolve sym) deref)
    (catch Exception _ nil)))

(defn- clipboard-units
  "The CF_UNICODETEXT code units on the clipboard ([] when it holds no
   text), read straight from the global heap the system handed out, or nil
   when the clipboard cannot be opened. The locked block has no size of its
   own, so the view is a generous bound — the scan stops at the NUL
   terminator inside it."
  []
  (let [{:keys [open close get lock unlock]} @win-read
        attempt (fn [] (open (ffi-var 'null)))]
    (when (loop [left 5]
            (cond
              (pos? (attempt)) true
              (pos? left) (do (Thread/sleep 20) (recur (dec left)))
              :else false))
      (try
        (let [hmem (get 13)]
          (when-not (or (nil? hmem) ((ffi-var 'null?) hmem))
            (let [ptr ((ffi-var 'reinterpret) (lock hmem) (* 2 1024 1024))]
              (try
                (loop [i 0
                       units []]
                  (let [unit ((ffi-var 'read) ptr :uint16 (* 2 i))]
                    (if (zero? unit) units (recur (inc i) (conj units unit)))))
                (finally (unlock hmem))))))
        (finally (close))))))

(deftest utf16-units
  (testing "the host's string model becomes UTF-16 code units"
    (if-let [units (win-var 'kmet.tui.clipboard-win/utf16-units)]
      (do
        (t/is (= [104 105] (vec (units "hi"))))
        (t/is (= [0x2014] (vec (units "—"))) "a BMP char is one unit")
        (t/is (= [0xD83D 0xDE00] (vec (units "😀")))
              "an astral char is a surrogate pair — already units on bb, one code point on jolt"))
      (t/is true "skipped: the Windows backend did not load"))))

(deftest ^:slow clipboard-roundtrip
  (testing "the exact units land on the clipboard"
    ;; ^:slow — the test owns the system clipboard while it runs (the
    ;; console-backend roundtrip's opt-in precedent for invasive Windows
    ;; tests).
    (if-not (host/windows?)
      (t/is true "skipped: Windows only")
      (let [units (win-var 'kmet.tui.clipboard-win/utf16-units)
            backend (win-var 'kmet.tui.clipboard-win/set-clipboard!)]
        (if-not (and units backend)
          (t/is true "skipped: the Windows backend did not load")
          (doseq [[label copy!] [["backend" backend]
                                 ["copy-text!" clipboard/copy-text!]]
                  text ["a—b" "line1\nline2" "中文😀" ""]]
            (t/is (true? (copy! text)) (str label " copies " (pr-str text)))
            (let [read (clipboard-units)]
              (t/is (some? read) (str "the clipboard opens again after " label))
              (t/is (= (vec (units text)) (vec read))
                    (str "the clipboard holds " (pr-str text) " after " label)))))))))

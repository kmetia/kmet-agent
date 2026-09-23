(ns kmet.libs.test-clipboard
  "Windows clipboard backend tests. kmet.libs.clipboard-win loads user32 and
   kernel32 at namespace load, so this file never requires it: the backend's
   vars are resolved at runtime and every test skips off Windows. The
   roundtrip test is ^:slow — it sets CF_UNICODETEXT and reads it back
   through the same API, so a default run never touches the user's
   clipboard."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.libs.clipboard :as clipboard]
            [kmet.libs.host :as host]
            #?(:bb [babashka.ffi :as ffi] :jolt [jolt.ffi :as ffi])))

(def ^:private win-read
  "The clipboard-reader bindings, created on first use (Windows only —
   load-library is unreachable elsewhere)."
  (delay
    (when (host/windows?)
      (ffi/load-library "user32.dll")
      (ffi/load-library "kernel32.dll")
      {:open (ffi/cfn "OpenClipboard" [:pointer] :int)
       :close (ffi/cfn "CloseClipboard" [] :int)
       :get (ffi/cfn "GetClipboardData" [:uint] :pointer)
       :lock (ffi/cfn "GlobalLock" [:pointer] :pointer)
       :unlock (ffi/cfn "GlobalUnlock" [:pointer] :int)})))

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
        attempt (fn [] (open ffi/null))]
    (when (loop [left 5]
            (cond
              (pos? (attempt)) true
              (pos? left) (do (Thread/sleep 20) (recur (dec left)))
              :else false))
      (try
        (let [hmem (get 13)]
          (when-not (or (nil? hmem) (ffi/null? hmem))
            (let [ptr (ffi/reinterpret (lock hmem) (* 2 1024 1024))]
              (try
                (loop [i 0
                       units []]
                  (let [unit (ffi/read ptr :uint16 (* 2 i))]
                    (if (zero? unit) units (recur (inc i) (conj units unit)))))
                (finally (unlock hmem))))))
        (finally (close))))))

(deftest utf16-units
  (testing "the host's string model becomes UTF-16 code units"
    (if-let [units (win-var 'kmet.libs.clipboard-win/utf16-units)]
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
      (let [units (win-var 'kmet.libs.clipboard-win/utf16-units)
            backend (win-var 'kmet.libs.clipboard-win/set-clipboard!)]
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

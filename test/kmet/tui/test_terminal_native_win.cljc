(ns kmet.tui.test-terminal-native-win
  "Windows console backend tests for kmet.tui.terminal-native-win —
   Jolt/Windows only: the UTF-16 decoding helper directly, and the console
   roundtrip over CONIN$/CONOUT$ (^:slow) — a .cljc because a .clj file may
   not carry reader conditionals (clj-kondo rejects them, and both lint
   views must read this file); on bb/JVM the namespace body is empty and
   every test skips.

   The roundtrip test points the process's std handles at CONIN$/CONOUT$
   for the duration (a `jolt test` process usually has redirected handles
   with a console behind them), so the public create-terminal path resolves
   them exactly like a terminal launch. The mode snapshot assertion is the
   point: a raw console left behind is the classic TUI failure."
  #?(:jolt
     (:require [clojure.test :as t :refer [deftest testing]]
               [jolt.ffi :as ffi]
               [kmet.libs.host :as host]
               [kmet.tui.terminal :as term]
               [kmet.tui.terminal-native-win :as win])))

#?(:jolt
   (do
     (ffi/defcfn ^:private win-create-file
       "CreateFileA" [:string :uint :uint :pointer :uint :uint :pointer] :pointer)
     (ffi/defcfn ^:private win-get-std-handle "GetStdHandle" [:int] :pointer)
     (ffi/defcfn ^:private win-set-std-handle "SetStdHandle" [:int :pointer] :int)
     (ffi/defcfn ^:private win-get-console-mode "GetConsoleMode" [:pointer :pointer] :int)
     (ffi/defcfn ^:private win-get-console-output-cp "GetConsoleOutputCP" [] :uint)
     (ffi/defcfn ^:private win-set-console-output-cp "SetConsoleOutputCP" [:uint] :int)
     (ffi/defcfn ^:private win-get-console-cp "GetConsoleCP" [] :uint)
     (ffi/defcfn ^:private win-set-console-cp "SetConsoleCP" [:uint] :int)
     (ffi/defcfn ^:private win-close-handle "CloseHandle" [:pointer] :int)

     (defn- open-console-handle
       "CreateFileA(DEVICE) with read+write access — GENERIC_READ|GENERIC_WRITE,
        FILE_SHARE_READ|FILE_SHARE_WRITE, OPEN_EXISTING."
       [device]
       (win-create-file device 0xC0000000 3 0 3 0 0))

     (defn- console-mode-of [handle]
       (ffi/with-out [m :uint]
         (when (pos? (win-get-console-mode handle m))
           (ffi/read m :uint))))

     (defn- win-var
       "A private var of the Windows backend, resolved at runtime so clj-kondo
        does not need a private-call exemption for the pure helper test."
       [sym]
       (some-> (ns-resolve 'kmet.tui.terminal-native-win sym) deref))

     (deftest utf16-code-points
       (testing "ReadConsoleW's UTF-16 code units become kmet code points"
         (if-let [decode (win-var 'utf16-units->code-points)]
           (do
             (t/is (= [[104 105] nil] (decode [104 105] nil))
                   "BMP units pass through unchanged")
             (t/is (= [[128075] nil] (decode [0xD83D 0xDC4B] nil))
                   "a surrogate pair is one code point (U+1F44B)")
             (t/is (= [[] 0xD83D] (decode [0xD83D] nil))
                   "a high half split by the read boundary is carried")
             (t/is (= [[128075] nil] (decode [0xDC4B] 0xD83D))
                   "the carried high half combines with the next read's low half")
             (t/is (= [[0xFFFD 104] nil] (decode [0xDC4B 104] nil))
                   "a lone low surrogate is U+FFFD")
             (t/is (= [[0xFFFD 104] nil] (decode [0xD83D 104] nil))
                   "a high half followed by a non-low unit is U+FFFD"))
           (t/is true "skipped: the Windows backend did not load"))))

     (deftest ^:slow windows-console-raw-mode-roundtrip
       ;; The guard runs before any kernel32 binding is CALLED: the defcfns
       ;; resolve lazily, but calling a symbol the host does not have throws
       ;; (foreign-procedure: no entry for …), so a Jolt/Unix run must reach
       ;; the skip before the first CreateFileA.
       (testing "a real console: raw mode, live size, output, exact restore"
         (if-not (host/windows?)
           (t/is true "skipped: Windows only")
           (let [in (open-console-handle "CONIN$")
                 out (open-console-handle "CONOUT$")
                 console? (and (some? (console-mode-of in))
                               (some? (console-mode-of out)))]
             (if-not console?
               (do (win-close-handle in)
                   (win-close-handle out)
                   (t/is true "skipped: no console attached to this process"))
               (let [old-in (win-get-std-handle -10)
                     old-out (win-get-std-handle -11)
                     before (console-mode-of in)
                     orig-out-cp (win-get-console-output-cp)
                     orig-in-cp (win-get-console-cp)
                     ;; force a non-UTF-8 code page so the switch is
                     ;; observable however the console was configured
                     before-out-cp 437
                     before-in-cp 437]
                 (try
                   (win-set-console-output-cp before-out-cp)
                   (win-set-console-cp before-in-cp)
                   (win-set-std-handle -10 in)
                   (win-set-std-handle -11 out)
                   (let [t (win/create-terminal)]
                     (t/is (false? (term/started? t)))
                     (t/is (pos? (term/columns t)))
                     (t/is (pos? (term/rows t)))
                     (term/start! t (fn [_] nil) (fn [] nil))
                     (try
                       (t/is (true? (term/started? t)))
                       (t/is (pos? (term/columns t)))
                       (t/is (pos? (term/rows t)))
                       (t/is (= 65001 (win-get-console-output-cp))
                             "start! put the console on the UTF-8 code page")
                       ;; WriteConsoleW — the console path of write-output
                       (term/write-output t " ")
                       (finally
                         (term/stop! t)))
                     (t/is (false? (term/started? t))))
                   (t/is (= before (console-mode-of in))
                         "stop! restored the exact console input mode")
                   (t/is (= before-out-cp (win-get-console-output-cp))
                         "stop! restored the console output code page")
                   (t/is (= before-in-cp (win-get-console-cp))
                         "stop! restored the console input code page")
                   (finally
                     (win-set-console-output-cp orig-out-cp)
                     (win-set-console-cp orig-in-cp)
                     (win-set-std-handle -10 old-in)
                     (win-set-std-handle -11 old-out)
                     (win-close-handle in)
                     (win-close-handle out)))))))))))

(ns kmet.test-keys
  (:require [clojure.test :as t :refer [testing]]
            [kmet.tui.keys :as k]))

(t/deftest test-key-constants
  (t/is (= "up" k/KEY-UP))
  (t/is (= "down" k/KEY-DOWN))
  (t/is (= "left" k/KEY-LEFT))
  (t/is (= "right" k/KEY-RIGHT))
  (t/is (= "enter" k/KEY-ENTER))
  (t/is (= "escape" k/KEY-ESC))
  (t/is (= "tab" k/KEY-TAB))
  (t/is (= "backspace" k/KEY-BACKSPACE))
  (t/is (= "delete" k/KEY-DELETE))
  (t/is (= "home" k/KEY-HOME))
  (t/is (= "end" k/KEY-END))
  (t/is (= "space" k/KEY-SPACE)))

(t/deftest test-modifier-helpers
  (t/is (= "ctrl+c" (k/ctrl "c")))
  (t/is (= "shift+a" (k/shift "a")))
  (t/is (= "alt+left" (k/alt "left"))))

(t/deftest test-parse-key-ctrl
  (t/is (= "ctrl+a" (k/parse-key (str (char 1)))))
  (t/is (= "ctrl+z" (k/parse-key (str (char 26)))))
  (t/is (= "ctrl+c" (k/parse-key (str (char 3)))))
  (t/is (= "ctrl+u" (k/parse-key (str (char 21)))))
  (t/is (= "ctrl+k" (k/parse-key (str (char 11)))))
  (t/is (= "ctrl+-" (k/parse-key (str (char 31)))))
  (t/is (= "ctrl+\\" (k/parse-key (str (char 28))))))

(t/deftest test-parse-key-special
  (t/is (= "backspace" (k/parse-key "\u007f")))
  (t/is (= "backspace" (k/parse-key "\u0008")))
  (t/is (= "tab" (k/parse-key "\t")))
  (t/is (= "enter" (k/parse-key "\r")))
  (t/is (= "escape" (k/parse-key "\u001b"))))

(t/deftest test-parse-key-legacy
  (t/is (= "up" (k/parse-key "\u001b[A")))
  (t/is (= "down" (k/parse-key "\u001b[B")))
  (t/is (= "right" (k/parse-key "\u001b[C")))
  (t/is (= "left" (k/parse-key "\u001b[D")))
  (t/is (= "home" (k/parse-key "\u001b[H")))
  (t/is (= "end" (k/parse-key "\u001b[F")))
  (t/is (= "delete" (k/parse-key "\u001b[3~")))
  (t/is (= "pageUp" (k/parse-key "\u001b[5~"))))

(t/deftest test-parse-key-alt
  (t/is (= "alt+left" (k/parse-key "\u001bb")))
  (t/is (= "alt+right" (k/parse-key "\u001bf")))
  (t/is (= "alt+up" (k/parse-key "\u001bp")))
  (t/is (= "alt+enter" (k/parse-key "\u001b\r")))
  (t/is (= "alt+enter" (k/parse-key "\u001b\n"))))

(t/deftest test-parse-key-modified
  (t/is (= "ctrl+up" (k/parse-key "\u001b[1;5A")))
  (t/is (= "ctrl+down" (k/parse-key "\u001b[1;5B")))
  (t/is (= "ctrl+up" (k/parse-key "\u001bOa")))
  (t/is (= "shift+tab" (k/parse-key "\u001b[Z")))
  (t/is (= "up" (k/parse-key "\u001bOA")))
  (t/is (= "down" (k/parse-key "\u001bOB")))
  (t/is (= "right" (k/parse-key "\u001bOC")))
  (t/is (= "left" (k/parse-key "\u001bOD"))))

(t/deftest test-parse-key-regular
  (t/is (= "a" (k/parse-key "a")))
  (t/is (= "Z" (k/parse-key "Z")))
  (t/is (= "3" (k/parse-key "3")))
  (t/is (= "." (k/parse-key ".")))
  (t/is (= "space" (k/parse-key " ")) "space parses to its key id (pi)"))

(t/deftest test-matches-key
  (t/is (k/matches-key? "q" "q"))
  (t/is (k/matches-key? "\u001b[A" "up"))
  (t/is (k/matches-key? "\u007f" "backspace"))
  (t/is (k/matches-key? (str (char 3)) "ctrl+c"))
  (t/is (k/matches-key? (str (char 31)) "ctrl+-"))
  (t/is (k/matches-key? "\r" "enter"))
  (t/is (k/matches-key? "\u001bb" "alt+left"))
  (t/is (k/matches-key? "\u001bf" "alt+right"))
  (t/is (k/matches-key? "\u001b[H" "home"))
  (t/is (k/matches-key? "\u001b[3~" "delete"))
  (t/is (not (k/matches-key? "a" "b"))))

(t/deftest test-key-release-repeat-detection
  ;; Shape-based, never gated on the negotiated flag (pi: isKeyRelease /
  ;; isKeyRepeat): the startup `CSI > 7u` push is what makes the terminal
  ;; send releases, so filtering must not depend on the flags reply
  ;; arriving (issue #4: a lost reply doubled every keypress).
  (k/set-kitty-active! false)
  (t/is (true? (k/is-key-release? "\u001b[97;1:3u")))
  (t/is (true? (k/is-key-repeat? "\u001b[97;1:2u")))
  (t/is (nil? (k/is-key-release? "\u001b[97;1u")) "press is not a release")
  (t/is (nil? (k/is-key-repeat? "\u001b[97;1u")))
  ;; bracketed paste content is never a release/repeat (pi: ":3F" in MACs)
  (t/is (nil? (k/is-key-release? "\u001b[200~90:62:3F:A5\u001b[201~")))
  (t/is (nil? (k/is-key-repeat? "\u001b[200~x:2u\u001b[201~")))
  ;; the negotiated flag does not change the answer either way
  (k/set-kitty-active! true)
  (try
    (t/is (true? (k/is-key-release? "\u001b[97;1:3u")))
    (t/is (true? (k/is-key-repeat? "\u001b[97;1:2u")))
    (finally (k/set-kitty-active! false))))

;; ─── Kitty protocol parsing (pi keys.ts parity) ────────────────────────────

(t/deftest test-parse-kitty-csi-u
  (testing "CSI-u forms: codepoint, modifiers, event types, alternate keys"
    (t/is (= "a" (k/parse-key "\u001b[97u")) "plain key (flag 1 disambiguate)")
    (t/is (= "shift+a" (k/parse-key "\u001b[97;2u")) "shift (1-indexed mod)")
    (t/is (= "ctrl+c" (k/parse-key "\u001b[99;5u")) "ctrl")
    (t/is (= "alt+x" (k/parse-key "\u001b[120;3u")) "alt")
    (t/is (= "shift+ctrl+p" (k/parse-key "\u001b[112;6u")) "ctrl+shift (pi order)")
    (t/is (= "a" (k/parse-key "\u001b[97;1:2u")) "repeat event type ignored for id")
    (t/is (= "a" (k/parse-key "\u001b[97;1:3u")) "release event type ignored for id")
    (t/is (= "shift+a" (k/parse-key "\u001b[97:65;2u")) "alternate-key form")
    (t/is (= "a" (k/parse-key "\u001b[97::104;1u")) "base-layout-key form")
    (t/is (= "space" (k/parse-key "\u001b[32u")))
    (t/is (= "enter" (k/parse-key "\u001b[13u")))
    (t/is (= "tab" (k/parse-key "\u001b[9u")))
    (t/is (= "escape" (k/parse-key "\u001b[27u")))
    (t/is (= "backspace" (k/parse-key "\u001b[127u")))
    (t/is (= "0" (k/parse-key "\u001b[57399u")) "KP_0 normalizes to 0")
    (t/is (= "up" (k/parse-key "\u001b[57419u")) "KP_UP normalizes to up")))

(t/deftest test-parse-kitty-arrows-functional
  (testing "modified arrows, functional keys, home/end"
    (t/is (= "ctrl+up" (k/parse-key "\u001b[1;5A")))
    (t/is (= "alt+right" (k/parse-key "\u001b[1;3C")))
    (t/is (= "shift+down" (k/parse-key "\u001b[1;2B")))
    (t/is (= "shift+delete" (k/parse-key "\u001b[3;2~")))
    (t/is (= "ctrl+home" (k/parse-key "\u001b[7;5~")))
    (t/is (= "ctrl+end" (k/parse-key "\u001b[1;5F")))
    (t/is (= "ctrl+home" (k/parse-key "\u001b[1;5H")))
    (t/is (= "pageUp" (k/parse-key "\u001b[5;1~")))
    (t/is (= "insert" (k/parse-key "\u001b[2~")))
    (t/is (= "ctrl+pageDown" (k/parse-key "\u001b[6;5~")))))

(t/deftest test-parse-modify-other-keys
  (testing "xterm modifyOtherKeys: CSI 27;mods;code ~"
    (t/is (= "ctrl+c" (k/parse-key "\u001b[27;5;99~")))
    (t/is (= "shift+enter" (k/parse-key "\u001b[27;2;13~")))
    (t/is (= "alt+a" (k/parse-key "\u001b[27;3;97~")))
    (t/is (= "a" (k/parse-key "\u001b[27;1;97~")))
    (t/is (= "shift+ctrl+p" (k/parse-key "\u001b[27;6;112~"))
          "pi format order: shift, ctrl, alt, super")))

(t/deftest test-kitty-mode-aware-legacy
  (testing "with Kitty active, \\x1b\\r and \\n are shift+enter (pi)"
    (k/set-kitty-active! true)
    (try
      (t/is (= "shift+enter" (k/parse-key "\u001b\r")))
      (t/is (= "shift+enter" (k/parse-key "\n")))
      (finally (k/set-kitty-active! false)))
    (t/is (= "alt+enter" (k/parse-key "\u001b\r")) "non-Kitty legacy alt+enter")
    (t/is (= "ctrl+j" (k/parse-key "\n")) "non-Kitty \\n is ctrl+j (kmet editor newline)")))

(t/deftest test-parse-alt-sequences
  (testing "ESC-prefixed legacy alt/modifier sequences"
    (t/is (= "alt+a" (k/parse-key "\u001ba")))
    (t/is (= "alt+1" (k/parse-key "\u001b1")))
    (t/is (= "alt+space" (k/parse-key "\u001b ")))
    (t/is (= "alt+backspace" (k/parse-key "\u001b\u007f")))
    (t/is (= "alt+backspace" (k/parse-key "\u001b\b")))
    (t/is (= "alt+left" (k/parse-key "\u001bB")) "pi maps ESC+B to alt+left")
    (t/is (= "alt+right" (k/parse-key "\u001bF")))
    (t/is (= "ctrl+alt+a" (k/parse-key "\u001b\u0001")))
    (t/is (= "ctrl+alt+[" (k/parse-key "\u001b\u001b")))
    (t/is (= "ctrl+alt+-" (k/parse-key "\u001b\u001f")))))

(t/deftest test-matches-key-modifier-order
  (testing "modifier order is insignificant (pi: parseKeyId splits on '+')"
    (t/is (k/matches-key? "\u001b[112;6u" "ctrl+shift+p"))
    (t/is (k/matches-key? "\u001b[112;6u" "shift+ctrl+p"))
    (t/is (k/matches-key? "\u001b[112;6u" (k/ctrl-shift "p")))
    (t/is (not (k/matches-key? "\u001b[112;6u" "ctrl+alt+p")))))

(t/deftest test-matches-key-printable
  (testing "printable chars match themselves and their ids"
    (t/is (k/matches-key? "a" "a"))
    (t/is (k/matches-key? " " "space"))
    (t/is (k/matches-key? "\u001b[32u" "space") "kitty space matches the space id")))

(t/deftest test-decode-kitty-printable
  (testing "unmodified and shifted CSI-u sequences decode to text"
    (t/is (= "a" (k/decode-kitty-printable "\u001b[97u")))
    (t/is (= "à" (k/decode-kitty-printable "\u001b[224u"))
          "Italian-layout printable key (pi #3780)")
    (t/is (= "@" (k/decode-kitty-printable "\u001b[64u")))
    (t/is (= "@" (k/decode-kitty-printable "\u001b[64;2u")) "shift-modified")
    (t/is (= "x" (k/decode-kitty-printable "\u001b[120:88u"))
          "the shifted codepoint is used only when Shift is held")
    (t/is (= "X" (k/decode-kitty-printable "\u001b[120:88;2u")))
    (t/is (= "." (k/decode-kitty-printable "\u001b[57409u"))
          "functional keypad codepoints map to their printable equivalents"))
  (testing "control, alt and function sequences are not text"
    (t/is (nil? (k/decode-kitty-printable "\u001b[97;5u")) "ctrl+a")
    (t/is (nil? (k/decode-kitty-printable "\u001b[97;3u")) "alt+a")
    (t/is (nil? (k/decode-kitty-printable "\u001b[13;2u")) "shift+enter")
    (t/is (nil? (k/decode-kitty-printable "\u001b[A")) "arrow")
    (t/is (nil? (k/decode-kitty-printable "abc")))))

(t/deftest test-decode-printable-key
  (testing "kitty CSI-u and modifyOtherKeys printables decode"
    (t/is (= "a" (k/decode-printable-key "\u001b[97u")))
    (t/is (= "@" (k/decode-printable-key "\u001b[27;2;64~")))
    (t/is (nil? (k/decode-printable-key "\u001b[27;5;97~")) "modifyOtherKeys ctrl+a")
    (t/is (nil? (k/decode-printable-key "\u001b[27;3;97~")) "modifyOtherKeys alt+a")
    (t/is (nil? (k/decode-printable-key "x")))))

(t/deftest test-decode-out-of-range-never-throws
  (testing "malformed/astral codepoints are nil, not exceptions"
    (t/is (nil? (k/decode-kitty-printable "\u001b[99999999999999999999u")))
    (t/is (nil? (k/decode-kitty-printable "\u001b[97;99999999999999999999u")))
    (t/is (nil? (k/decode-kitty-printable "\u001b[128512u"))
          "astral codepoints have no single-char representation")
    (t/is (nil? (k/decode-printable-key "\u001b[27;2;99999999999999999999~")))
    (t/is (nil? (k/parse-key "\u001b[128512u")))
    (t/is (nil? (k/parse-key "\u001b[27;99999999999999999999;97~")))))

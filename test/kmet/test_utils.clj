(ns kmet.test-utils
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.commands :as commands]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.prompts :as prompts]
            [kmet.app.profile :as profile]
            [kmet.app.registries :as registries]
            [kmet.app.registry :as registry]
            [kmet.app.runtime :as runtime]
            [kmet.app.session.runtime :as rt]
            [kmet.app.skills :as skills]
            [kmet.app.tools.registry :as tools]
            [kmet.srce.core :as srce]
            [kmet.tui.utils :as u]))

(defn test-engine
  "A minimal session-runtime Engine for mode-level tests: the given
   :agent-state and :session atoms (exactly as the Engine record holds
   them), plus optional :config, :cwd (a value or an atom) and the
   :turn-running?/:compaction-queued/:pending-compaction atoms. Subscribers,
   last-run and disposal are fresh.

   The Engine record is what `kmet.app.session.runtime` reads, and production
   builds one through `kmet.app.loop.driver`'s handle factory. A mode test's
   subject is the UI, so it takes the value without booting a composition; a
   test that needs the run ops creates a handle instead."
  [{:keys [agent-state session config cwd turn-running? compaction-queued
           pending-compaction]}]
  (rt/map->Engine {:agent-state agent-state
                   :session session
                   :config (atom (or config {}))
                   :subscribers (atom [])
                   :last-run (atom nil)
                   :disposed? (atom false)
                   :cwd (if (instance? clojure.lang.IDeref cwd)
                          cwd
                          (atom (or cwd (str (fs/cwd)))))
                   :turn-running? (or turn-running? (atom false))
                   :compaction-queued (or compaction-queued (atom []))
                   :pending-compaction (or pending-compaction (atom []))
                   :pending-ui (atom {})
                   :run-callbacks (atom nil)
                   :front-end (atom nil)}))

(defn with-test-engine
  "Add a test engine to a CoreState-like map, reusing the map's legacy
   :agent-state/:session-atom/:config/:running-turn?/:compaction-queued
   values as the engine's atoms (see test-engine) so direct test writes and
   the runtime accessors agree. The legacy keys stay in the map for test
   bodies that read them."
  [cs]
  (let [engine (test-engine {:agent-state (or (:agent-state cs) (atom nil))
                             :session (or (:session-atom cs) (atom nil))
                             :config (:config cs)
                             :cwd (:cwd cs)
                             :turn-running? (:running-turn? cs)
                             :compaction-queued (:compaction-queued cs)})]
    ;; the registry's one op the TUI calls: disposal. In a composition it also
    ;; releases the agent's child ctx; a test engine has none.
    (assoc cs :engine engine
           :agents {:dispose! (fn [handle] (when-let [d (:dispose! handle)] (d)))})))

(defn clear-app-registries!
  "Clear the process-global registrations a test leaves in the app's
   registries: commands, prompt templates, skills, and the themes loaded from
   contributed paths. A test that exercises a real composition loads the
   developer's own skills and prompts, and one that registers the builtin
   commands leaves the app's slash registry filled; P2 made those
   registrations app-scoped rather than per-engine, so a later namespace in
   the same process would see them. Hook, renderer and flag registrations are
   per-owner and tests deregister what they register."
  []
  (commands/clear-commands!)
  (prompts/clear-prompt-templates!)
  (skills/clear-skills!)
  ;; the themes live in the host's registry, so there is nothing to clear when
  ;; no host is bound — and asking an unbound host for its handle is an error
  (when (extensions/host-binding)
    (extensions/unregister-contributed-themes!)))

(defn tools-service!
  "Mount a `:tools` row under CTX over a fresh handle and answer the handle —
   the shape the app's own row provides, without booting the composition. A test
   ctx that needs its own tool table starts here."
  [ctx]
  (let [handle (registry/atom-registry)]
    (srce/plugin! ctx {:name :tools
                       :provide #{:tools}
                       :apply (fn [c _] (srce/provide! c :tools handle
                                                       {:in (:parent c)}))})
    (srce/settle! ctx)
    handle))

(defn mount-builtin-tools!
  "Mount the composition's built-in tool rows under CTX, which must provide
   `:tools`: the rows `:bundle/minimal` declares, read from the table rather
   than restated here. A test that reads a tool table without booting the app (a
   chat display's `:tool-fn`, a loop's table) gets the built-ins the way a run
   does — a row each, registered into the handle — instead of a second, static
   table beside them."
  [ctx]
  (let [rows (->> (:rows (profile/bundle (profile/builtin) :bundle/minimal))
                  (filter #(tools/tool-row-name (:id %))))]
    (runtime/mount-rows! ctx rows)
    (srce/settle! ctx)
    ctx))

(defn tool-ctx
  "A bare ctx with a fresh tool table and the built-in tool rows mounted into
   it: `{:ctx ctx :tools handle}`. The ctx is the caller's to dispose; a test
   that only reads the table can keep the handle and forget the ctx."
  []
  (let [ctx (srce/root {:name :test-tools})
        handle (tools-service! ctx)]
    (mount-builtin-tools! ctx)
    {:ctx ctx :tools handle}))

(defn tool-registry
  "A tool registry handle for tests: a fresh handle with the built-in tool rows
   mounted into it (`tool-ctx`), so `get-tool` answers for `read` the way a
   booted run does. The tool layer takes its registry as the first argument (two
   agents, two tables), so a test that reads the table says which one — never a
   process global."
  []
  (:tools (tool-ctx)))

(defn tool-fn
  "The chat history's `:tool-fn` — a 1-arg name → tool definition lookup — over
   a fresh `tool-registry`, so a display renders the built-ins a run would offer."
  []
  (let [reg (tool-registry)]
    (fn [name] (tools/get-tool reg name))))

(defn clear-listeners!
  "Drop every listener registered on CTX — the one thing a test needs from the
   kernel's table that the production API cannot express. The fiber's effect
   list keeps the dead entries, whose disposers then remove nothing."
  [ctx]
  (reset! (:listeners ctx) {}))

(defn with-extension-host
  "clojure.test :each fixture: this namespace needs a bound extension host.

   Rendering a message asks the host for the markdown-transformers hook, and
   the host is unbound in a fresh process (`kmet/no-host-services`). The binding
   is a composition's `:extensions` row, so a namespace that boots one has a
   host and one that does not must bind its own: this fixture binds a fresh
   registry over a bare ctx for the test and restores the previous binding.
   Declaring it here makes the dependency the namespace's, not whichever
   require happened to come along first.

   It also publishes the handle as the process registry, as a composition's row
   does: a test that registers into `registry/current` and a test that reads
   through the host must be looking at one table."
  [f]
  (let [handle (registry/atom-registry)
        ctx (srce/root {:name :test-host})
        base (extensions/host-binding)
        previous (registry/publish-process-handle! handle)]
    (extensions/bind-services!
     (fn [] (registries/host-services handle ctx)))
    (try (f)
         (finally
           (registry/publish-process-handle! previous)
           (extensions/bind-services! base)
           (srce/dispose-subtree! ctx)))))

(defn spit-plugin
  "Write an extension source to PATH and append the plugin var the loader
   mounts: a fixture keeps writing a plain `(defn init [api] …)` body, and the
   mount is still a plugin. Returns PATH."
  [path source]
  (spit path source)
  (spit path (str "\n(def plugin {:name :ext/fixture"
                  " :apply (fn [_ctx api] (init api) nil)})\n")
        :append true)
  path)

(defn with-clean-app-registries
  "clojure.test :each fixture: clear the app tree's process-global
   registrations before and after each test (see clear-app-registries!)."
  [f]
  (clear-app-registries!)
  (try (f)
       (finally (clear-app-registries!))))

(defn clear-extension-wiring!
  "Reset the process-global extension wiring a front end install leaves
   behind: the binding (front-end kind + capability map) and the
   session/context/entry sinks, which close over the installing test's fake
   CoreState. Call from a test's finally so a later test — in this namespace
   or another one run in the same process — never sees the fake context.
   A leaked :interactive binding (the registry install bound the kind, only
   the port was cleared) used to break test-extensions' headless
   expectations when test-interactive ran first."
  []
  (extensions/set-session! nil)
  (extensions/set-context-sink! nil)
  (extensions/set-entry-sink! nil)
  (extensions/unbind-extensions!))

(defn slash
  "The /-separated form of path values — strings, Paths/Files, or maps, sets
   and vectors of them. kmet renders Windows paths with \\ on bb and with /
   on jolt, so path expectations compare through this on both sides."
  [x]
  (cond
    (nil? x) nil
    (string? x) (str/replace x "\\" "/")
    (map? x) (into {} (map (fn [[k v]] [k (slash v)])) x)
    (set? x) (into #{} (map slash) x)
    (sequential? x) (mapv slash x)
    :else (str/replace (str x) "\\" "/")))

(defn strip-ansi
  "Remove ANSI CSI/OSC escape sequences from a string (the production
   host-optimal stripper). OSC 8 hyperlinks are path metadata emitted only
   when the detected terminal supports them, so plain-text assertions go
   through this to stay independent of capability detection. Test helpers
   that render TUI components commonly need the raw plain text."
  [s]
  (u/strip-ansi-codes s))

(defn await-collected
  "True when PRED becomes true within ~5 s of forced-GC attempts (~50 ms
   apart). ^:slow tests only: it calls System/gc and sleeps between attempts,
   so it must never appear in the fast suite."
  [pred]
  (loop [n 100]
    (cond
      (pred) true
      (zero? n) false
      :else (do (System/gc)
                (Thread/sleep 50)
                (recur (dec n))))))

(def throwaway-rsa-pkcs8-pem
  "A throwaway PKCS#8 RSA-2048 private key (test-only, generated offline).
   Embedded so tests that only need a valid RSA key don't pay a runtime
   keygen; the matching SPKI public key is kmet.libs.test-crypto's
   rsa-spki-pem."
  "-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCpdAxfCPdeAsCi
xyUaiUjddK7U4Sz0N6uWI0An23JWClamCRgEZh+cNlqr0vkRTbmKCPqetqYK52CZ
23iJVJEHsDYvE6pdz9s0T3Q8452PYTzd/WC/yhakAMOY4/OwqiXfRzsgmVxYzhdw
7KLEFr/zjWS5ak206ilWXnJGsDA5/PmxU6E1J7M47NpHyY0rf6OnTULSR6sbliaM
H6RIgqo0JaPn8DROK4kskT1wUJ9rTtXW2pkUT7M0gRTviglK7sX0zrNQyxUdQAfS
VZiZdwm1OB7rCAVoAy8a1CWeF6WaGxPqvSNV/Gd8oghNxyjH6l6hOdrguvTMRqNO
WCydEoMnAgMBAAECggEARl9kd99QY+F+wNs1hIS5FwdLSCtPsQihoNN45FSVcjgO
7htetwz2WbiVBUCzoUUHJdU+x8UQxfx7OMpVj6roJCpBBmIDYbxdy6annlXk7FO0
pHiCwoOZzXhnwsoAPUEcQ1LgslQebt8ACyyrGAZepzX0O7BzHwwUKMNGcCbhkGLb
CJBkFzJN5eHWTKKaYxQkGXw1juJ7Lh5NmOMySi05LE5qP45uiCp+DAVHuS9/p+Ks
PHjygqWJrhAqUSjLyKzA9lhy8egOCVcHqvx34PvbiPQBFW+t7hxUezqsDT14zC0w
jac05hFvNbf6/9XkDlSObYroC8VFM2TLTbY3wbB8uQKBgQDTIn5opffVnbfCMvmT
Df4VUj3mOwNzabwatj0vR4bftCQt0zC9W6o34fuaE3ZO+rCRQYMYCzObqJxRehRg
JJxCO5xSkmFRGH6BnTUDPxWd/fgacDkiYHzsXut35bkDpmuh3NEnsYjRgz4vF5am
ufUWNe2vMayrWE8jGAlll5tPcwKBgQDNdiEJHrLBz2TMjC71GtuppoV/y9XqA+ne
np4X6QgGWD3SsRE9E59/wGizABSYNm4qQULIdVLxrDa0OkbWueevl29o86jtQBGf
7E49qGBrOn6mpHgTkzyuqUsPv8k12nV4XgF6yd/YT/z9BYiroF+xpriFWmjFlewO
hLg9wLJofQKBgQC7sb14oJq7D3Ots41FGrpXD/6xLqM5tbSgdoPcBRU0x7sl9cot
fFDNTxtR9J1HRvsylm/JLyQY7ALTN9jnQ4SaKM4mZbUX19OWvD/FfA5mnsEyF7Pj
CK3ln9hqhRE92nNyuJBDXkNIJglg6fJmk4f6z8RV664ZwO7RPuwToXxTWwKBgC+J
ZE1S3/L9hAnbaUXmZ+pRj9Oomp6hNyv9sQBBN00Yf31O4HksIBwTrWW04A7zkPuP
y97LXlxyBtoeHaOysYsr02gJ8AysZSeGKfgUC/LjPshZhzqoN0MGAkrriac/Gyy1
O0xohrTqlNMJw7xJx7o3o7ue1lzYuhwF0wbenaiVAoGAZH/30L9vOmosxj3/RSNU
HyVpn/Xy1FBH+ksziAmWKTw2doapMfzLNwU3F/ApHNor25rydqvs1kAnWLx9LG4p
qNTnIeSaG1o2fAJopTjOfMSjuXBGv5GrUbYZqu8yf+guRqpgHmrHkzxmOirpqpYH
QWdiAKmzt6MfYOx22Q50Fqk=
-----END PRIVATE KEY-----")

(t/deftest test-strip-ansi-strips-osc-hyperlinks
  ;; Both OSC terminators (BEL and ESC\) and the CSI styles around them.
  (t/is (= "read src/a.clj"
           (strip-ansi (str "\u001b]8;;file:///x/src/a.clj\u001b\\"
                            "\u001b[36mread src/a.clj\u001b[0m"
                            "\u001b]8;;\u001b\\"))))
  (t/is (= "link" (strip-ansi "\u001b]8;;https://x.example/\u0007link\u001b]8;;\u0007"))))

(t/deftest test-visible-width
  (t/is (= 0 (u/visible-width "")))
  (t/is (= 5 (u/visible-width "hello")))
  (t/is (= 4 (u/visible-width "中文")))
  (t/is (= 2 (u/visible-width "🍎")))
  (t/is (= 6 (u/visible-width "中文🍎")))   ;; 2+2+2=6
  (t/is (= 6 (u/visible-width "ab\tc"))))   ;; tab expands to 3 spaces (pi)

(def ^:private ansi-corpus
  "Strings the scanner/regex equivalence tests walk at every index: matched
   CSI/OSC, private-parameter CSI, unterminated OSC, an OSC body interrupted
   by a bare ESC, a lone ESC, CJK, tabs."
  ["" "plain" "a\tb" "中文"
   "\u001b[31mred\u001b[0m"
   "\u001b[38;5;196mx\u001b[39m"
   "\u001b[?25lprivate\u001b[?25h"
   "\u001b]8;;https://x.example/\u0007link\u001b]8;;\u0007"
   "\u001b]0;title\u001b\\after"
   "\u001b]0;title\u009cafter"
   "\u001b]0;unterminated"
   "\u001b]0;x\u001bY\u0007"
   "\u001b]8;;https://x.example/\u001bY\u0007link\u001b]8;;\u0007"
   "\u001b["
   "\u001b"
   "\u001bX"
   "\u001b[1;31"
   "\u001b[1;31m"
   "pre\u001b[1moops\u001b[m\u001b[?25l"
   "\u001b[?2026h\u001b[1;1H\u001b[?2026l"
   (str "你好" "\u001b[31m" "你好")])

(def ^:private ansi-re
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")

(def ^:private ansi-alphabet
  "The escape-grammar alphabet: both introducers, CSI parameter and final
   characters, the private-parameter `?`, ST's backslash and both OSC
   terminators (BEL, 0x9C)."
  [\u001b \[ \] \; \? \1 \m \\ \u0007 \u009c])

(defn- ansi-strings-up-to
  "All strings of length 0..MAX over ansi-alphabet — every escape-grammar
   interaction (and near-miss) of that size."
  [max]
  (letfn [(step [n]
            (if (zero? n)
              [""]
              (for [p (step (dec n)) c ansi-alphabet] (str p c))))]
    (for [n (range (inc max)) s (step n)] s)))

(defn- ansi-random-strings
  "Deterministic LCG sample of N strings up to MAX-LEN long, so longer
   interactions are covered without enumerating every combination."
  [n max-len]
  (let [m (count ansi-alphabet)
        next-seed (fn [seed] (mod (+ (* seed 1103515245) 12345) 2147483648))
        build (fn [seed len]
                (loop [i 0 seed seed sb (StringBuilder.)]
                  (if (>= i len)
                    (str sb)
                    (let [seed (next-seed seed)]
                      (recur (inc i) seed
                             (.append sb ^Character
                                      (nth ansi-alphabet (mod seed m))))))))]
    (loop [k n seed 42 out []]
      (if (zero? k)
        out
        (let [seed (next-seed seed)
              len (mod seed (inc max-len))]
          (recur (dec k) (next-seed seed)
                 (conj out (build seed len))))))))

(def ^:private ansi-pin-corpus
  "Curated sequences plus every string up to length 4 over the escape
   alphabet plus a deterministic sample of longer strings."
  (concat ansi-corpus
          (ansi-strings-up-to 4)
          (ansi-random-strings 1200 16)))

(defn- regex-code-at
  "ANSI-CODE-RE anchored at I — ansi-code-at's :default side."
  [s i]
  (when (and (< i (count s)) (= \u001b (nth s i)))
    (let [m (re-matcher ansi-re s)]
      (when (and (.find m i) (= i (.start m)))
        [(.group m) (- (.end m) i)]))))

(defn- regex-codes [s] (vec (re-seq ansi-re s)))

(defn- regex-codes-range
  "The old re-find loop of ansi-process-range!."
  [s i stop]
  (loop [j i out []]
    (if (>= j stop)
      out
      (let [m (when (= \u001b (nth s j))
                (re-find ansi-re (subs s j)))]
        (if (and m (str/starts-with? (subs s j) m))
          (recur (+ j (count m)) (conj out m))
          (recur (inc j) out))))))

(t/deftest test-strip-ansi-host-equivalence
  ;; The Jolt path strips ANSI with a hand-rolled scanner (strip-ansi-native)
  ;; while bb/JVM keep the regex strip. The scanner must accept exactly the
  ;; regex's language — including the sequences the regex does NOT match
  ;; (private-parameter CSI, unterminated OSC, a lone ESC), which must stay
  ;; in the output as literal text. Both implementations run on both hosts,
  ;; so this test pins them against each other wherever the suite runs.
  (let [native-strip (var-get #'u/strip-ansi-native)
        corpus ansi-corpus]
    (doseq [s corpus]
      (t/is (= (str/replace s ansi-re "") (native-strip s)) (pr-str s))))
  ;; and the public width path agrees on strip-needing input
  (t/is (= 3 (u/visible-width "\u001b[31mabc\u001b[0m")))
  ;; private-parameter CSI is NOT ANSI-CODE-RE: ESC measures 0, the rest
  ;; sorts as literal text (the documented quirk both strippers share)
  (t/is (= 5 (u/visible-width "\u001b[?25l"))))

(t/deftest test-ansi-code-at-host-equivalence
  ;; ansi-code-at's Jolt side is the hand-rolled scanner
  ;; (ansi-code-at-native); bb/JVM keep the anchored regex matcher (match-at +
  ;; ANSI-CODE-RE). Both must agree at every index of every curated string —
  ;; including the indices where neither matches.
  (let [native-code (var-get #'u/ansi-code-at-native)]
    (doseq [s ansi-corpus
            i (range (inc (count s)))]
      (t/is (= (regex-code-at s i) (native-code s i))
            (str (pr-str s) " @" i)))))

(t/deftest test-ansi-scanner-regex-equivalence
  ;; The scanners must accept exactly ANSI-CODE-RE's language in every case:
  ;; the curated corpus, every string up to length 4 over the escape alphabet
  ;; and a deterministic sample of longer strings, compared on strip, the
  ;; anchored code-at, the full code scan and the [i, stop) scan. Each
  ;; property reports over the whole corpus at once (first mismatches shown)
  ;; so the exhaustive walk stays fast.
  (let [native-strip (var-get #'u/strip-ansi-native)
        native-code (var-get #'u/ansi-code-at-native)
        native-codes (var-get #'u/ansi-codes-scan)
        native-range (var-get #'u/ansi-codes-scan-range)
        corpus ansi-pin-corpus
        show (fn [label bad]
               (t/is (empty? bad)
                     (str label ": " (count bad) " mismatch(es), first: "
                          (pr-str (vec (take 6 bad))))))]
    (show "strip"
          (for [s corpus
                :when (not= (str/replace s ansi-re "") (native-strip s))]
            (pr-str s)))
    (show "codes"
          (for [s corpus
                :when (not= (regex-codes s) (native-codes s))]
            (pr-str s)))
    (show "code-at"
          (for [s corpus
                i (range (inc (count s)))
                :when (not= (regex-code-at s i) (native-code s i))]
            [s i]))
    (show "range"
          (for [s corpus
                i (range (count s))
                stop (range i (count s))
                :when (not= (regex-codes-range s i stop)
                            (native-range s i stop))]
            [s i stop]))))

(t/deftest test-visible-width-narrow-dingbats
  ;; Regression: the coarse emoji block ranges (0x2600-0x27BF etc.) counted
  ;; text-presentation dingbats as 2 columns, so background padding ended a
  ;; column short (e.g. "✓" in the model selector) and markdown table
  ;; separators misaligned. Only default-emoji-presentation (Emoji_Presentation
  ;; =Yes, e.g. ⌚ ⏰ ⚽ ☕ 😀) / EAW-wide chars are 2-wide; the text-presentation
  ;; forms (✔ ☑ ☀ ❤ …) render narrow bare and only turn 2-wide with U+FE0F.
  (doseq [s ["✓" "✗" "★" "☆" "❶" "➤" "✦" "✔" "☑" "☀" "❤" "⌚" "⏰"
             "⚽" "☕" "😀"]]
    (t/is (= (if (contains? #{"⌚" "⏰" "⚽" "☕" "😀"} s) 2 1)
             (u/visible-width s))
          (str "width of " (pr-str s)))))

(t/deftest test-visible-width-vs16
  ;; Text-presentation bases stay narrow bare but turn 2-wide with U+FE0F
  ;; (©️ ™️ ↔️ ▶️ ▪️ ✔️ ☀️ ☑️ ❤️ …). Default-presentation emoji and EAW-wide
  ;; chars (⬛ ⬜ …) are already 2-wide bare.
  (t/is (= 1 (u/visible-width "©")))
  (t/is (= 2 (u/visible-width "©️")))
  (t/is (= 2 (u/visible-width "™️")))
  (t/is (= 2 (u/visible-width "↔️")))
  (t/is (= 1 (u/visible-width "▶")))
  (t/is (= 2 (u/visible-width "▶️")))
  (t/is (= 2 (u/visible-width "▪️")))
  (t/is (= 1 (u/visible-width "✔")))
  (t/is (= 2 (u/visible-width "✔️")))
  (t/is (= 1 (u/visible-width "❤")))
  (t/is (= 2 (u/visible-width "❤️")))
  (t/is (= 2 (u/visible-width "⬛")))
  (t/is (= 2 (u/visible-width "⬛️"))))

(t/deftest test-visible-width-zwj-vs16-chain
  ;; A VS16 between the emoji base and the ZWJ must not break the chain
  ;; (❤️‍🔥 🏳️‍🌈); plain wide chars never chain (漢‍🔥 = 4); keycaps keep 2.
  (t/is (= 2 (u/visible-width "❤️‍🔥")))
  (t/is (= 2 (u/visible-width "🏳️‍🌈")))
  (t/is (= 2 (u/visible-width "👨‍👩‍👧‍👦")))
  (t/is (= 4 (u/visible-width "漢‍🔥")))
  (t/is (= 2 (u/visible-width "1️⃣"))))

(t/deftest test-visible-width-skin-tone-modifiers
  ;; A skin-tone modifier on an emoji base is part of the glyph: default-
  ;; presentation bases are wide already and zero the modifier via prev-w;
  ;; text-presentation bases (☝ ✌ 🏋 …) pair with it through the
  ;; modifier-base set, FE0F or not. A lone modifier keeps its 2 columns.
  (t/is (= 2 (u/visible-width "👍🏽")))
  (t/is (= 2 (u/visible-width "☝🏽")))
  (t/is (= 2 (u/visible-width "☝️🏽")))
  (t/is (= 2 (u/visible-width "✌🏽")))
  (t/is (= 2 (u/visible-width "🏋🏽")))
  (t/is (= 2 (u/visible-width "🏽"))))

(t/deftest test-visible-width-halfwidth
  ;; FF61..FFDC are halfwidth forms (ｱｲｳ…) — narrow, not 2 columns.
  (t/is (= 1 (u/visible-width "ｱ")))
  (t/is (= 3 (u/visible-width "ｱｲｳ")))
  (t/is (= 2 (u/visible-width "Ａ"))))

(t/deftest test-apply-background-to-line
  ;; The reported bug: a line ending in a non-ASCII narrow char (✓) must pad
  ;; to full width so the background reaches the line end.
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "a✓" 4 nil))))
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "a✔" 4 nil))))
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "中" 4 nil)))))

(t/deftest test-truncate-to-width
  (t/is (= "hello" (u/truncate-to-width "hello" 10)))
  (t/is (= "" (u/truncate-to-width "" 5)))
  (t/is (<= (u/visible-width (u/truncate-to-width "hello world" 5)) 5)))

(t/deftest test-truncate-to-width-pi-parity
  ;; pi: truncateToWidth guards on max-width — fitting text is never
  ;; truncated even when an ellipsis is requested
  (t/is (= "abcdef" (u/truncate-to-width "abcdef" 6 "...")))
  (t/is (= "a very ..." (u/truncate-to-width "a very long line here" 10 "..."))
        "kept prefix + ellipsis = max-width")
  (t/is (= "hello..." (u/truncate-to-width "hello world foo" 8 "...")))
  ;; pi: truncateFragmentToWidth — an ellipsis wider than max-width is clipped
  (t/is (= ".." (u/truncate-to-width "hello world" 2 "...")))
  (t/is (= "" (u/truncate-to-width "hello world" 0 "..."))))

(t/deftest test-truncate-to-width-pad
  ;; pi: truncateToWidth's pad flag — the result is right-padded with
  ;; spaces to exactly max-width, keeping box borders aligned for short
  ;; rows (the mcp-adapter panel renders every row padded)
  (t/is (= "hello     " (u/truncate-to-width "hello" 10 "…" true)))
  (t/is (= "hello" (u/truncate-to-width "hello" 10 "…" false)))
  (t/is (= "a very ..." (u/truncate-to-width "a very long line here" 10 "..." true))
        "truncated result already fills max-width — pad is a no-op")
  (t/is (= 10 (u/visible-width (u/truncate-to-width "hello" 10 "…" true))))
  (t/is (= 10 (u/visible-width (u/truncate-to-width "\u001b[31mhello\u001b[0m" 10 "…" true)))
        "ANSI codes are preserved and don't count toward the pad")
  (t/is (= 8 (u/visible-width (u/truncate-to-width "中文服务器" 8 "…" true)))
        "wide chars pad correctly")
  (t/is (= "     " (u/truncate-to-width "" 5 "…" true)))
  (t/is (= "" (u/truncate-to-width "" 5 "…" false))))

(t/deftest test-truncate-to-width-osc-8-close
  ;; pi: getActiveOsc8Close — cutting through a hyperlink label must close
  ;; the link before the ellipsis so following text isn't swallowed by it
  (t/testing "BEL-terminated link is closed with BEL"
    (let [link "\u001b]8;;https://example.com\u0007"
          result (u/truncate-to-width (str link "hello world") 9 "…")]
      (t/is (= (str link "hello wo\u001b]8;;\u0007…") result))
      (t/is (= 9 (u/visible-width result)))))
  (t/testing "ST-terminated link is closed with ST"
    (let [link "\u001b]8;;https://example.com\u001b\\"
          result (u/truncate-to-width (str link "hello world") 5 "…")]
      (t/is (= (str link "hell\u001b]8;;\u001b\\…") result))
      (t/is (= 5 (u/visible-width result)))))
  (t/testing "an already-closed link is not closed again"
    (let [text "\u001b]8;;https://example.com\u0007ok\u001b]8;;\u0007hello world"
          result (u/truncate-to-width text 6 "…")]
      (t/is (str/ends-with? result "hel…"))
      (t/is (not (str/includes? result "\u001b]8;;\u0007\u001b]8;;\u0007")) "no doubled close")
      (t/is (= 6 (u/visible-width result))))))

(t/deftest test-wrap-long-unbreakable-word
  ;; A single word longer than the width wraps across lines (pi: breakLongWord)
  ;; instead of being clipped — every character is preserved.
  (let [lines (u/wrap-text-with-ansi (apply str (repeat 60 "x")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines)))
    (t/is (= 60 (apply + (map u/visible-width lines))) "no characters lost")))

(t/deftest test-wrap-no-spurious-blank
  ;; Regression: a too-long word at line start used to emit an empty line.
  (let [lines (u/wrap-text-with-ansi (str (apply str (repeat 60 "x")) " more") 22)]
    (t/is (not-any? empty? lines) "no blank lines from word wrapping")
    (t/is (= 65 (apply + (map u/visible-width lines))) "all characters kept")
    (t/is (clojure.string/includes? (last lines) "more")
          "a following word joins the long word's last piece")))

(t/deftest test-wrap-ansi-survives-break
  ;; The fg escape on a styled long word is re-applied to continuation lines.
  (let [styled (str "\u001b[38;2;212;212;212m" (apply str (repeat 60 "x")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 22)]
    (t/is (= 60 (apply + (map u/visible-width lines))))
    (t/is (clojure.string/includes? (first lines) "\u001b[38")
          "first piece keeps the fg code")
    (t/is (clojure.string/includes? (second lines) "\u001b[38")
          "continuation piece is re-styled")))

(t/deftest test-wrap-ansi-multiword-styled-line
  ;; A styled multi-word line (edit diff / bash output: one fg code at the
  ;; start, reset at the end) must re-open the style on every wrapped
  ;; continuation line — previously only the first line kept it.
  (let [styled (str "\u001b[38;5;245m" (clojure.string/join " " (repeat 8 "word123456")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 20)]
    (t/is (>= (count lines) 2) "line wraps")
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[38")
            (str "every wrapped line is re-styled: " (pr-str l))))
    (t/is (= 87 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-fast-path-first-piece-styled
  ;; A styled word that must be broken across lines (ASCII fast path) — the
  ;; FIRST piece starts a fresh line and must re-open the style, not just
  ;; the continuations (pi: breakLongWord starts with getActiveCodes).
  (let [styled "\u001b[31mone two three\u001b[0m"
        lines (u/wrap-text-with-ansi styled 3)]
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "every piece is re-styled: " (pr-str l))))
    (t/is (= 13 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-styles-survive-literal-newline
  ;; A style left open on one line is re-emitted on the next input line (pi:
  ;; wrapTextWithAnsi carries the tracker across input lines).
  (let [styled "\u001b[31mline one words here\nline two words here\u001b[0m"
        lines (u/wrap-text-with-ansi styled 12)]
    (t/is (>= (count lines) 2))
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "each line is re-styled: " (pr-str l))))    (t/is (= 38 (apply + (map u/visible-width lines))))))

(t/deftest test-wrap-cjk-width
  (let [lines (u/wrap-text-with-ansi (clojure.string/join "" (repeat 15 "汉字")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines))
          "wide characters counted as 2 columns")
    (t/is (= 60 (apply + (map u/visible-width lines))))))

(t/deftest test-wrap-long-unbreakable-word-shapes
  (t/testing "an all-wide word slices at half the width (2 columns per char)"
    (let [lines (u/wrap-text-with-ansi (apply str (repeat 60 "\u4e2d")) 22)]
      (t/is (= [11 11 11 11 11 5] (mapv count lines))
            "11 chars are 22 columns; every piece stays within the width")
      (t/is (= [22 22 22 22 22 10] (mapv u/visible-width lines)))
      (t/is (= 120 (apply + (map u/visible-width lines))) "no characters lost")))
  (t/testing "an all-narrow non-ASCII word slices at the width"
    (let [lines (u/wrap-text-with-ansi (apply str (repeat 60 "\u043f")) 22)]
      (t/is (= [22 22 16] (mapv u/visible-width lines)))
      (t/is (= 60 (apply + (map u/visible-width lines))))))
  (t/testing "a mixed wide/narrow word still walks and never overflows"
    (let [lines (u/wrap-text-with-ansi (apply str (repeat 40 "\u65e5a")) 22)]
      (t/is (= 120 (apply + (map u/visible-width lines)))
            "40 wide + 40 narrow columns, nothing lost")
      (t/is (every? #(<= (u/visible-width %) 22) lines) "every piece fits")))
  (t/testing "a styled all-wide word re-opens the style on every piece"
    (let [styled (str "\u001b[31m" (apply str (repeat 30 "\u4e2d")) "\u001b[0m")
          lines (u/wrap-text-with-ansi styled 22)]
      (t/is (= 60 (apply + (map u/visible-width lines))))
      (t/is (every? #(str/includes? % "\u001b[31m") lines)
            "every piece keeps the fg code"))))

;; ─── ANSI-aware slicing + compositing (HStack support) ─────────────────────

(t/deftest test-slice-with-width-plain
  (t/is (= {:text "hello" :width 5} (u/slice-with-width "hello world" 0 5 :strict? true)))
  (t/is (= {:text "world" :width 5} (u/slice-with-width "hello world" 6 5 :strict? true)))
  (t/is (= {:text "" :width 0} (u/slice-with-width "hello" 0 0 :strict? true))))

(t/deftest test-slice-with-width-ansi
  ;; ANSI codes before the window are prepended to the first kept character
  (let [styled (str "\u001b[31m" "red" "\u001b[39m" "tail")
        s (u/slice-with-width styled 0 3 :strict? true)]
    (t/is (= 3 (:width s)))
    (t/is (str/includes? (:text s) "red"))))

(t/deftest test-slice-with-width-strict
  ;; strict? excludes a wide char crossing the end boundary
  (let [s (u/slice-with-width "中文x" 0 3 :strict? true)]
    (t/is (= 2 (:width s)) "second 2-wide char would cross col 3 → excluded")))

(t/deftest test-composite-line-plain
  ;; the overlay region is SGR-isolated from the base (SEGMENT-RESET before
  ;; and after it, pi: compositeTuiLine), so the raw output carries resets
  (t/is (= (str "..." u/SEGMENT-RESET "X" u/SEGMENT-RESET "......")
           (u/composite-line ".........." "X" 3 1 10)))
  (t/is (= (str "ab " u/SEGMENT-RESET "cd" u/SEGMENT-RESET)
           (str/trim (u/composite-line "ab        " "cd" 3 2 10)))))

(t/deftest test-composite-line-ansi-overlay
  (let [out (u/composite-line ".........." (str "\u001b[31m" "XX" "\u001b[39m") 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "\u001b[31m") "overlay styling kept")))

(t/deftest test-composite-line-inherits-base-style
  ;; The after segment keeps the base's background across the overlay region
  (let [bg (str "\u001b[41m" "abcdefghij" "\u001b[49m")
        out (u/composite-line bg "XX" 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "abc"))
    (t/is (str/includes? out "fghij"))
    (t/is (str/includes? out "\u001b[41m") "after content restyled with the base bg")))

(t/deftest test-sgr-state-at
  (t/is (= "" (u/sgr-state-at "plain" 0)))
  (let [styled (str "\u001b[31m" "ab" "\u001b[39m" "cd")]
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 0)))
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 1)))
    (t/is (= "" (u/sgr-state-at styled 2)) "reset applied at col 2")))

(t/deftest test-composite-line-image-passthrough
  ;; pi: compositeTuiLine returns kitty image lines untouched
  (let [img-line (str "\u001b_Gf=100,m=0;" "aGVsbG8=" "\u001b\\" "rest")]
    (t/is (= img-line (u/composite-line img-line "XX" 3 2 10)))))

(t/deftest test-slice-with-width-graphemes-agree-with-visible-width
  ;; skin-tone cluster and flag pair measure 2 columns in both
  (let [s "👍🏽"]
    (t/is (= 2 (:width (u/slice-with-width s 0 10 :strict? true))))
    (t/is (= (u/visible-width s) (:width (u/slice-with-width s 0 10 :strict? true)))))
  (t/is (= 2 (:width (u/slice-with-width "🇺🇸" 0 10 :strict? true)))))

(ns kmet.app.loop-guard
  "Repeat-loop guard (kmet-specific circuit breaker): detect a model
   stuck emitting identical tool calls (or repeating its reasoning) with a
   sliding window of canonical (tool, args) signatures. Pure detection —
   settling the run (loop-guard-give-up!) stays in kmet.app.loop because it
   emits the :loop-guard UI event.

   kmet-specific — no pi counterpart (research: MukundaKatta/tool-loop-guard,
   isr4el-silv4/loop-guard, ollama-loop-guard)."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]))

(def ^:private loop-guard-exempt-tools
  "Tools that never trip the repeat-loop guard regardless of repetition
   count. Read-only inspectors are cheap and harmless — re-reading the same
   file (or read → edit → read verify patterns) is legitimate work."
  #{"read"})

(def loop-guard-reset-tools
  "Tools whose successful execution resets the repeat-loop guard's memory of
   other calls. Writes change the world, so a repeated call afterwards (e.g.
   re-running a test after an edit) observes new state and is not a loop.
   Only successful (non-error) executions reset; failed or blocked writes
   change nothing and keep the history. Each writer's own signature is kept,
   so repeating the identical write itself still trips. Accepted evasion:
   alternating a repeat with a novel successful write never trips — each
   successful write counts as progress."
  #{"write" "edit"})

(declare canonical-json)

(defn loop-guard-signature
  "Canonical repeat-loop key for a tool call: `name \\0 key-sorted JSON
   args`. Argument maps differing only in key order count as identical
   (deep key-sort, pi/dirge canonical_json). Returns nil for exempt tools
   (read) — they never enter the window."
  [tool-name args]
  (when-not (contains? loop-guard-exempt-tools tool-name)
    (let [args (if (string? args)
                 ;; OpenAI wire format: the whole args arrive as a JSON string
                 (let [parsed (try (json/parse-string args true) (catch Exception _ nil))]
                   (if (map? parsed) parsed args))
                 args)]
      (str tool-name "\u0000" (canonical-json args)))))

(defn- canonical-json
  "Canonical JSON for repeat-loop comparison: keys recursively sorted, so
   {:a 1 :b 2} and {:b 2 :a 1} compare equal. Args may be a map (Anthropic/
   Google), a string (OpenAI raw JSON — parsed ONLY at the top level, since
   only the whole args string arrives as JSON from the provider), or a
   vector (nested arrays — key-sorted recursively so edit batches with
   reordered maps still match). Values keep their JSON types (a string
   \"5\" never collides with the number 5; a nested string containing JSON
   text stays a string and never collides with a real nested map)."
  [args]
  (cond
    (map? args)
    (json/generate-string
     (into (sorted-map)
           (map (fn [[k v]] [(name k) (canonical-json v)]))
           args))
    (vector? args)
    (json/generate-string (mapv canonical-json args))
    (string? args)
    (pr-str args)
    :else (pr-str args)))

(def thinking-loop-error
  "Error message used when the thinking-loop guard cuts a stream. A
   distinct sentinel so the loop's error path can recognize it without
   string-matching a provider error."
  "thinking-loop guard: repeated reasoning detected")

(def ^:private thinking-loop-min-span
  "Minimum length (chars) of a repeated thinking segment before the guard
   considers it a loop. Below this, short repeated phrases in normal
   reasoning pass. Mirrors ollama-loop-guard's repeat-span-min (24)."
  24)

(def ^:private thinking-loop-delimiters
  "Chars that end a thinking-loop segment: ASCII sentence/line ends plus
   CJK full stops (。！？) — a multilingual reasoning loop (e.g. Chinese)
   repeats with 。-terminated segments and would otherwise be invisible
   (ollama-loop-guard / llama.cpp use \".!?\\n\"; the CJK additions cover
   the other major script)."
  #{\. \! \? \newline \。 \！ \？})

(def ^:private thinking-loop-ws
  "Whitespace consumed after a segment delimiter (Java \\s, ASCII six)."
  #{\space \tab \newline \u000B \formfeed \return})

(defn- split-thinking-segments
  "Split TAIL on thinking-loop delimiters without regex: jolt's irregex is
   pathological on the lookbehind this replaces (~1 s for a 4000-char
   buffer there vs ~1.5 ms here). Each segment keeps its delimiter plus
   following whitespace; thinking-loop? trims anyway, so this is
   downstream-identical to the old str/split (pinned across a 38-case
   corpus incl. CJK, CRLF and blank runs on both hosts)."
  [tail]
  (let [n (count tail)]
    (if (zero? n)
      []
      (loop [i 0 start 0 out []]
        (if (>= i n)
          (let [seg (subs tail start n)]
            (if (and (seq out) (= "" seg)) out (conj out seg)))
          (if (contains? thinking-loop-delimiters (nth tail i))
            (let [j (loop [k (inc i)]
                      (if (and (< k n) (contains? thinking-loop-ws (nth tail k)))
                        (recur (inc k))
                        k))]
              (recur j j (conj out (subs tail start j))))
            (recur (inc i) start out)))))))

(defn thinking-loop?
  "True when the recent THINKING text contains a repeated segment: the same
   segment (split on sentence/line delimiters, incl. CJK full stops)
   appearing >= 3 times within the trailing MAX-CHARS window. Catches
   degenerate reasoning loops where the model repeats the same analysis
   without producing content (research: ollama-loop-guard, llama.cpp
   line-level repetition). Pure and total (nil/empty → false)."
  [thinking & {:keys [max-chars] :or {max-chars 4000}}]
  (when (string? thinking)
    (let [tail (subs thinking (max 0 (- (count thinking) max-chars)))
          segments (->> (split-thinking-segments tail)
                        (map str/trim)
                        (remove #(or (empty? %) (< (count %) thinking-loop-min-span))))
          counts (frequencies segments)]
      (boolean (some #(>= % 3) (vals counts))))))

(defn loop-guard-filter
  "Pure repeat-loop detection for one tool-call batch.
   STATE — {:window [signature...] :suppressed n} (window is newest-last,
   capped at 4×threshold+1).
   Returns {:suppressed [tool-call...] :survivors [tool-call...]
            :state next-state}.
   A call is suppressed when its signature already appears (threshold-1)
   times in the window — i.e. the threshold-th identical call. The window
   is big enough (4×threshold+1) that alternating/cyclic patterns
   (1,2,1,2,1,2 / 1,2,3,1,2,3 / up to 2×threshold distinct calls) also
   trip: each signature's occurrences accumulate in the window until one
   reaches threshold. Exempt tools (read) never count."
  [{:keys [window suppressed]} threshold tool-calls]
  (let [window-size (inc (* 4 threshold))
        result (reduce (fn [{:keys [window suppressed-calls] :as acc} tc]
                         (if-let [sig (loop-guard-signature (:name tc) (:arguments tc))]
                           (let [count-in-window (count (filter #(= sig %) window))]
                             (if (>= count-in-window (dec threshold))
                               (assoc acc :suppressed-calls (conj suppressed-calls tc))
                               (assoc acc :window (conj window sig))))
                           acc))
                       {:window window :suppressed-calls []}
                       tool-calls)
        ;; window trimmed to window-size (drop oldest)
        window (let [w (:window result)]
                 (if (> (count w) window-size) (subvec w (- (count w) window-size)) w))
        ;; survivors are the non-suppressed calls in source order — rebuild
        ;; from the input so :survivors stays in original order
        suppressed-calls (:suppressed-calls result)
        suppressed-set (set (map :id suppressed-calls))
        survivors (into [] (remove #(contains? suppressed-set (:id %))) tool-calls)]
    {:suppressed suppressed-calls
     :survivors survivors
     :state {:window window
             :suppressed (+ suppressed (count suppressed-calls))}}))

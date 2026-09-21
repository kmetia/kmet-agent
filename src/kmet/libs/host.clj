(ns kmet.libs.host
  "Which runtime hosts this kmet process.

   kmet runs on babashka and on Jolt: Jolt defines the
   clojure.core/*jolt-version* var (jolt-port.md), babashka does not — the
   only supported non-Jolt host, so anything that isn't Jolt is babashka.
   UI surfaces that name or badge the host (the welcome header logo, the
   footer's К mark) share this one detection instead of repeating it, and
   platform-specific branches (the terminal backend pick, app seams) share
   windows?."
  (:require [babashka.fs :as fs]))

(defn jolt?
  "True on the Jolt host: jolt defines clojure.core/*jolt-version*;
   babashka does not."
  []
  (boolean (find-var 'clojure.core/*jolt-version*)))

(defn windows?
  "True when the OS is Windows — the predicate the tree's platform branches
   share (babashka.fs/windows?, which answers from `os.name` on both hosts).
   macOS/Linux/Android are the false case, so a Unix-only branch can test
   `(not (windows?))`."
  []
  (fs/windows?))

(defn runtime-name
  "Name of the hosting runtime: \"jolt\" or \"babashka\"."
  []
  (if (jolt?) "jolt" "babashka"))

(defn mark
  "Single-letter runtime mark for compact UI badges (the footer's Кb/Кj):
   the runtime name's initial — \"b\" on babashka, \"j\" on Jolt."
  []
  (subs (runtime-name) 0 1))

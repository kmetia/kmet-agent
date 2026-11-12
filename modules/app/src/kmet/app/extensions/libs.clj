(ns kmet.app.extensions.libs
  "The fixed set of bundled libraries kmet provides on both hosts — to
   extension contexts and to codemode scripts alike. See
   src/kmet/extension.md § Bundled extension libraries.

   The requires below are the set's enumeration of third-party roots:
   loading the namespace is the point. On Jolt it is statically required by
   kmet.app.extensions.core, so `jolt dist` pulls the whole set into the app's
   require closure (AOT), and the extension host view shares the loaded
   namespaces by reference; on babashka/JVM host-requires! and codemode's
   require-host-namespaces! require it on first use (the requires resolve to
   the bb-bundled ports there), so both SCI contexts inject the set
   identically.

   `bundled-library-roots` and `bundled-library-namespace?` are the set's
   one piece of code: the roots and the membership rule shared by the
   extension context filter and the sandbox's namespace copy, so the surface
   cannot drift from the set.

   clojure.* (core.async and rrb-vector included) and babashka.fs /
   babashka.process are shared by the host clauses in kmet.app.extensions.core,
   not from here."
  (:require [cljfmt.config]
            [cljfmt.core]
            [clojure.spec.alpha]
            [clojure.string :as str]
            [clojure.tools.reader]
            [edamame.core]
            [parinferish.core]
            [rewrite-clj.zip]))

(def bundled-library-roots
  "The third-party roots of the fixed bundled set that the host's
   clojure.*/babashka.* clauses do not already cover. A namespace is a set
   member when it is a root or lives under one."
  ["cljfmt" "edamame" "parinferish" "rewrite-clj"])

(defn bundled-library-namespace?
  "Is namespace name N part of the fixed bundled extension set? (The set's
   clojure.* members — spec.alpha, tools.reader, core.async, rrb-vector —
   are admitted by the hosts' clojure.*/babashka.* clauses instead; the
   roots above cover the rest.)"
  [n]
  (boolean (some #(or (= n %) (str/starts-with? n (str % ".")))
                 bundled-library-roots)))

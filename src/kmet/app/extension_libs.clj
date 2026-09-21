(ns kmet.app.extension-libs
  "The fixed set of bundled libraries kmet provides to extension contexts on
   both hosts — see extensions.md § Bundled extension libraries.

   The requires below are the set's enumeration of third-party roots. There
   is no code here: loading the namespace is the point. On Jolt it is
   statically required by kmet.app.extensions, so `jolt dist` pulls the
   whole set into the app's require closure (AOT), and the extension host
   view shares the loaded namespaces by reference; on babashka/JVM
   host-requires! requires it on the first extension load (the requires
   resolve to the bb-bundled ports there), so the SCI base context injects
   the set identically.

   clojure.* (core.async and rrb-vector included) and babashka.fs /
   babashka.process are shared by the host clauses in kmet.app.extensions,
   not from here."
  (:require [cljfmt.config]
            [cljfmt.core]
            [clojure.spec.alpha]
            [clojure.tools.reader]
            [edamame.core]
            [parinferish.core]
            [rewrite-clj.zip]))

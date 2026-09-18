(ns kmet.app.ui.subs
  "Shared state subscriptions for the UI: the app's globally-read atoms
   (tui.md §4/§9), deref'd by components instead of passed as constructor
   arguments, so a change invalidates exactly the subscribed subtrees and
   no re-theming walk is needed. These subs ARE the source atoms — a plain
   alias, not a compute: there is no derivation to do, `track!` watches the
   atom and gates on identical?/= exactly like a reaction's notification
   gate does, and a reaction's per-read cache verification costs about
   twice an atom deref, paid by every subscriber on every frame. Use
   kmet.tui.hiccup/compute for actual derivation over deps, not aliasing.
   Treat the subs as read-only: they ARE the atoms, so a write through a
   sub name mutates global state past its owner — write the owning atom
   through its API instead (the theme setters, the settings rows)."
  (:require [kmet.tui.theme :as theme]))

(def theme-sub
  "The active theme (pi: the global theme getter) as a subscribable ref —
   kmet.tui.theme/theme-atom itself. Deref inside render bodies: the tracked
   read subscribes the component, and a theme switch (settings, /theme,
   custom-file reload) invalidates exactly the subscribed caches on the next
   frame."
  theme/theme-atom)

(def image-settings-atom
  "Live inline-image display settings: {:show-images boolean
   :image-width-cells number}. Seeded from the config's :terminal map at
   startup (modes.interactive/build-layout), reset by the /settings rows and
   re-seeded by /reload (pi: settingsManager.reload); every image renders
   through image-settings-sub, so a change re-renders all mounted images at
   once (pi: settingsManager setShowImages / setImageWidthCells updating the
   chat's tool executions)."
  (atom {:show-images true :image-width-cells 60}))

(def image-settings-sub
  "The image display settings as a subscribable ref — image-settings-atom
   itself (see theme-sub on why the sub is the atom, not a compute)."
  image-settings-atom)

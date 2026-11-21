(ns kmet.ui.tui.key-hint
  "Styled key hints: the TUI keybinding lookup plus the live theme's dim and
   muted. The business keybinding layer (`kmet.app.keybindings`) owns the
   definitions and the manager; only the front end may read the theme."
  (:require [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.theme :as theme]))

(defn- hint-styling
  "The key-hint styling: the active theme's dim and muted. Derived at call
   time, so a theme change re-renders the hint on the next pass."
  []
  {:dim theme/dim
   :muted #(theme/fg (theme/get-current-theme) :muted %)})

(defn key-hint
  "Format a keybinding hint for ID with the live theme's styling."
  ([id desc]
   (let [{:keys [dim muted]} (hint-styling)]
     (tui-kb/key-hint (tui-kb/get-global-keybindings) id desc dim muted)))
  ([kmgr id desc]
   (let [{:keys [dim muted]} (hint-styling)]
     (tui-kb/key-hint kmgr id desc dim muted))))

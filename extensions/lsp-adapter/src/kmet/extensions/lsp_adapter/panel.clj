(ns kmet.extensions.lsp-adapter.panel
  "The /lsp interactive panel, built directly on the shared kmet.tui layer
   and mounted via ui-custom (same pattern as mcp-adapter's McpPanel,
   leaner: LSP has no auth or toggles - rows are state plus a restart
   action).

   Keys: up/down (or k/j) select · enter or r restart selected ·
   f refresh config · esc/q close."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.runtime :as runtime]))

(defn- short-root [root home]
  (let [r (str root)]
    (if (str/starts-with? r home)
      (str "~" (subs r (count home)))
      r)))

(defn- server-rows
  "One row-map per effective server, in registry order; state derived
   from live conns plus the broken set."
  [st]
  (let [home (str (fs/home))
        conns (group-by :name (runtime/all-conns st))
        broken @(:broken st)]
    (for [{:keys [id]} (detect/effective-servers
                        (get-in (runtime/config st) [:servers]))
          :let [roots (mapv :root (get conns id []))
                b (get broken id)]]
      {:name id
       :icon (cond b "✗" (seq roots) "●" :else "○")
       :detail (cond
                 b (str "broken " (:reason b))
                 (seq roots) (str/join ", " (map #(short-root % home) roots))
                 :else "idle")
       :docs (transduce (map #(count (runtime/open-docs %))) + 0
                        (get conns id []))})))

(defn- row-line
  [selected {:keys [icon name detail docs]} inner-w t]
  (let [marker (if selected "▸ " "  ")
        right (str detail
                   (when (pos? docs)
                     (str " · " docs " doc" (when (> docs 1) "s"))))
        pad (max 1 (- inner-w (+ 6 (count name) (count right))))
        line (str marker icon " " name
                  (apply str (repeat pad " "))
                  right)]
    (cond
      selected (theme/fg t :accent line)
      (= icon "✗") (theme/fg t :error line)
      (= icon "●") (theme/fg t :success line)
      :else (theme/fg t :dim line))))

(defcomponent LspPanel nil [st close-fn handlers sel flash]
  (render [_this width]
    ;; no track!: interactive dialog - the host re-renders on every
    ;; keypress, so state is read fresh per pass (McpPanel precedent)
    (let [_ @(:conns st)
          _ @(:broken st)
          _ @sel
          _ @flash
          t (theme/get-current-theme)
          inner-w (max 1 (- width 2))
          title " LSP "
          rows (vec (server-rows st))
          n (count rows)
          sel-i (min @sel (max 0 (dec n)))
          lines (volatile! [])]
      (vswap! lines conj
              (theme/fg t :border
                        (str "╭─" title
                             (apply str (repeat (max 0 (- inner-w 3 (count title))) "─"))
                             "╮")))
      (if (empty? rows)
        (vswap! lines conj (theme/fg t :dim "  no language servers configured"))
        (doseq [row rows]
          (vswap! lines conj
                  (row-line (= (:name row) (:name (get rows sel-i)))
                            row inner-w t))))
      (when @flash
        (vswap! lines conj (theme/fg t :success (str " " @flash))))
      ;; key hints, packed into width-bounded lines (mcp-adapter precedent:
      ;; a single hardcoded hints line clips on narrow terminals)
      (let [hints [(str (theme/italic "↑↓") " select")
                   (str (theme/italic "⏎") "/" (theme/italic "r") " restart")
                   (str (theme/italic "f") " refresh")
                   (str (theme/italic "esc") " close")]
            gap-w 2
            max-w (- inner-w 2)]
        (loop [hs hints, cur-line "", cur-w 0]
          (if (empty? hs)
            (when (seq cur-line)
              (vswap! lines conj
                      (theme/fg t :dim (str "  " cur-line))))
            (let [h (first hs)
                  hw (u/visible-width h)
                  needed (if (zero? cur-w) hw (+ gap-w hw))]
              (if (and (pos? cur-w) (> (+ cur-w needed) max-w))
                (do (vswap! lines conj
                            (theme/fg t :dim (str "  " cur-line)))
                    (recur (rest hs) h hw))
                (recur (rest hs)
                       (str cur-line (if (pos? cur-w) "  " "") h)
                       (+ cur-w needed)))))))
      (vswap! lines conj (theme/fg t :border
                                   (str "╰" (apply str (repeat inner-w "─")) "╯")))
      @lines))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          rows (vec (server-rows st))
          n (count rows)
          selected-name #(:name (get rows (min @sel (max 0 (dec n))) nil))]
      (cond
        (or (kb/matches-key kmgr data "tui.select.cancel")
            (keys/matches-key? data "q"))
        (do (close-fn) nil)

        (or (keys/matches-key? data "down") (keys/matches-key? data "j")
            (keys/matches-key? data (keys/ctrl "n")))
        (do (swap! sel #(mod (inc %) (max 1 n))) nil)

        (or (keys/matches-key? data "up") (keys/matches-key? data "k")
            (keys/matches-key? data (keys/ctrl "p")))
        (do (swap! sel #(mod (dec %) (max 1 n))) nil)

        (= data "f")
        (do ((:refresh-fn handlers #()))
            (reset! flash ".kmet/lsp.edn reloaded.")
            nil)

        (or (keys/matches-key? data "enter") (= data "r"))
        (do (when-let [name (selected-name)]
              ((:restart-fn handlers #()) name)
              (reset! flash (str "restarting \"" name "\"…")))
            nil)

        :else nil))))

(defn make-panel
  "Build the /lsp panel component. ST is the runtime state map; CLOSE is
   the host ui-custom callback (invoked with no args); HANDLERS supplies
   :restart-fn (fn [server-name]) and :refresh-fn (fn [])."
  [st close handlers]
  (map->LspPanel {:st st
                  :close-fn (fn [] (close nil))
                  :handlers handlers
                  :sel (atom 0)
                  :flash (atom nil)}))

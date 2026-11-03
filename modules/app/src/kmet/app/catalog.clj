(ns kmet.app.catalog
  "Row cards — what a profile row's plugin declares, for composition and
   `--dump-config`.

   A profile row names a plugin **source** (a var, or an `:ext/…` id the
   extension host resolves). Describing a row resolves the source and reads the
   plugin map's own keys — name, `:inject`, `:provide` — so the card is the
   declaration itself: there is no second copy to drift, and no generated file
   in the path. `defplugin` defines a valid map and dereferencing it mounts
   nothing, so this is inspection, not boot.

   A source that does not resolve is a composition error, reported by
   `--dump-config` as well as by boot: a var source that is not there is
   `:kmet.app/unknown-plugin`, an extension id that is not installed is
   `:kmet.app/unknown-extension` — both a typo, not a runtime condition. A var
   whose namespace **fails to load** is not a typo, so the load error travels
   with the report (the message names its file:line, `:load-error` holds it,
   and it stays the cause) instead of the row reading as absent. An `:ext/…`
   source is resolved from the installed extensions by name
   (`kmet.app.extensions.rows`) without loading it, so the dump names a real
   row."
  (:require [kmet.app.extensions.rows :as extension-rows]
            [kmet.srce.core :as srce]))

(defn- unknown-plugin
  "The composition error for a row's plugin SOURCE. CAUSE, when the source
   failed to load rather than being a var that is not there, is attached and
   named in the message — a syntax error must not read as a typo — with the
   file:line the host put in its ex-data (Clojure and SCI both do), so a
   broken plugin points at itself."
  [source cause]
  (let [d (some-> cause ex-data)
        file (or (:file d) (:clojure.error/source d))
        line (or (:line d) (:clojure.error/line d))
        failure (when cause
                  (str (when (and file line) (str file ":" line ": "))
                       (or (ex-message cause) "load failed")))]
    (ex-info (str "unknown plugin source: " (pr-str source)
                  " — a row's :plugin must name a defplugin var"
                  (when failure (str "; loading it failed: " failure)))
             (cond-> {:type :kmet.app/unknown-plugin :source source}
               failure (assoc :load-error failure))
             cause)))

(defn- card
  "The card SOURCE's plugin map declares (:name :inject :provide); a source
   that does not resolve to a plugin map is `:kmet.app/unknown-plugin`.
   Loading the namespace mounts nothing."
  [source]
  (let [plugin (try
                 (let [v (requiring-resolve source)]
                   (when (var? v) @v))
                 (catch Throwable e
                   (throw (unknown-plugin source e))))]
    (if (and (map? plugin) (keyword? (:name plugin)))
      (select-keys plugin [:name :inject :provide])
      (throw (unknown-plugin source nil)))))

(defn- bad-row [row message]
  (throw (ex-info message {:type :kmet.app/bad-row :row row})))

(defn describe
  "The composition card for ROW: the row plus `:id`, `:name`, `:inject` and
   `:provide`. A var `:plugin` is loaded and read; an `:ext/…` keyword is
   resolved from the installed extensions without loading it. Any other keyword
   is a bad row: a built-in source is a var, never a registry key."
  [row]
  (let [source (:plugin row)
        described
        (cond
          (extension-rows/source? source)
          (extension-rows/card row)

          (keyword? source)
          (bad-row row (str "a keyword :plugin source must be an :ext/… extension"
                            " id; a built-in source is a var symbol — got "
                            (pr-str source)))

          (symbol? source)
          (let [c (card source)]
            (merge c row {:id (or (:id row) (:name c))}))

          :else
          (bad-row row (str "a row needs a :plugin source (a var symbol or an"
                            " :ext/… keyword), got " (pr-str row))))]
    (update described :inject srce/normalize-inject)))

(defn- describe-patch [patch]
  (if (:plugin patch) (describe patch) patch))

(defn describe-layer
  "Describe a layer's rows, and its insert/replace patches (the ones carrying
   a `:plugin` source); config-only and remove patches pass through."
  [layer]
  (cond-> layer
    (:rows layer) (update :rows #(mapv describe %))
    (:patch layer) (update :patch #(mapv describe-patch %))))

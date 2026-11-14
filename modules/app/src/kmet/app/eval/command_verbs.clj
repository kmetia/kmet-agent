(ns kmet.app.eval.command-verbs
  "The `/eval` command vocabulary: the words the command accepts, their
   arguments and one-line summaries. Data only: handlers and output live in
   `kmet.app.commands.eval`, while source validation reads the reserved names
   here so dispatch, help, completion and the reserved-name guard cannot
   drift apart."
  (:require [clojure.string :as str]))

(def verbs
  "The explicitly dispatched `/eval` verbs, in display order. A verb's name
   is its primary spelling; aliases resolve through `find-verb` but are not
   listed or completed."
  [{:name "list"
    :args "[pattern]"
    :summary "List global, cwd, then live definitions"}
   {:name "index"
    :args "<text>"
    :summary "Search the live sandbox by name, docstring or source"}
   {:name "describe"
    :args "<name>"
    :summary "Describe one definition or source verb"}
   {:name "drop"
    :args "<name>…"
    :summary "Delete actual files and unbind the live names"}
   {:name "move"
    :args "<name> global|cwd"
    :summary "Move a definition to the other scope"}
   {:name "reload"
    :args ""
    :summary "Rebuild the sandbox from the library files"}
   {:name "sources"
    :args ""
    :summary "List eval sources and saved definitions"}
   {:name "help"
    :args "[topic|name]"
    :summary "Index, sandbox topic, or one definition"
    :aliases ["--help" "-h"]}])

(def fallback
  "The describe shorthand shown after the explicit verbs: any name that is
   not a known verb is resolved as a definition or source verb."
  {:name "<name>"
   :summary "Describe a definition (describe shorthand)"})

(def names
  "The primary spellings in display order."
  (mapv :name verbs))

(def all-names
  "Every accepted spelling, primary names first, then aliases in table order."
  (vec (mapcat (fn [{:keys [name aliases]}]
                 (cons name aliases))
               verbs)))

(def reserved-names
  "The `/eval` spellings an eval source may not replace."
  (set all-names))

(defn find-verb
  "The verb VERB names by its primary spelling or an alias, or nil. Exact and
   case-sensitive, like the command line that passes the word in."
  [word]
  (some (fn [{:keys [name aliases] :as verb}]
          (when (or (= word name) (some #{word} aliases))
            verb))
        verbs))

(defn usage
  "The display form of VERB's name and arguments, without the `/eval ` prefix."
  [{:keys [name args]}]
  (str name (when (seq args) (str " " args))))

(def argument-hint
  "The command record's argument hint, derived from the table."
  (str "<" (str/join "|" names) "> [args], or " (:name fallback)))

(defn completions
  "Completions for the text after `/eval `: primary names that start with
   PREFIX, in display order. Aliases are suggestions, not completions."
  [prefix]
  (let [prefix (str (or prefix ""))]
    (->> verbs
         (filter (fn [{:keys [name]}] (str/starts-with? name prefix)))
         (mapv (fn [{:keys [name summary]}]
                 {:value name :label name :description summary})))))

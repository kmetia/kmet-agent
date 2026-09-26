(ns kmet.modes.interactive.resources
  "The loaded-resources sections shown by the interactive mode's
   LoadedResources component (pi: showLoadedResources)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.libs.context :as context]
            [kmet.app.skills :as skills]
            [kmet.app.prompts :as prompts]
            [kmet.app.extensions :as extensions]))

;; ─── Loaded resources (pi: showLoadedResources) ────────────────────────────

(defn- display-path
  "Home-relative display path for a loaded resource (pi: formatDisplayPath)."
  [p]
  (let [p (str p)
        home (System/getProperty "user.home")]
    (if (and (seq home) (str/starts-with? p home))
      (str "~" (subs p (count home)))
      p)))

(defn build-loaded-resource-sections
  "Pi: showLoadedResources — one section per loaded resource group (Context,
   Skills, Prompts, Extensions). Sections carry raw items; styling happens
   in the LoadedResources component render."
  []
  (let [context-files (context/load-project-context-files
                       (cfg/get-agent-dir) (str (fs/cwd)))
        skills (skills/get-skills)
        templates (prompts/get-prompt-templates)
        extensions (extensions/get-loaded-extensions)
        sections (cond-> []
                   (seq context-files)
                   (conj {:name "Context"
                          :items (mapv (comp display-path :path) context-files)
                          :expanded-items (mapv #(str "  " (display-path (:path %))) context-files)})
                   (seq skills)
                   (conj {:name "Skills"
                          :items (mapv :name skills)
                          :expanded-items (mapv (fn [s]
                                                  (str "  " (or (:file-path s) (:name s))))
                                                skills)})
                   (seq templates)
                   (conj {:name "Prompts"
                          :items (mapv #(str "/" (:name %)) templates)
                          :expanded-items (mapv (fn [t] (str "  /" (:name t))) templates)})
                   (seq extensions)
                   (conj {:name "Extensions"
                          :items (mapv :name extensions)
                          :expanded-items (mapv (fn [e]
                                                  (str "  " (display-path (:path e))))
                                                extensions)}))]
    sections))

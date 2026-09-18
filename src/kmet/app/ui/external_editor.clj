(ns kmet.app.ui.external-editor
  "Editor content access + external editor launch (pi:
   modes/interactive/external-editor.ts). The editor-text-* accessors read
   and write the active editor through IEditorComponent when available,
   falling back to the field-based editor fns (duck-typed custom editors);
   handle-external-editor opens the editor content in $EDITOR on a temp
   file (pi: handleOpenExternalEditor)."
  (:require [kmet.app.ui :as ui]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.editor :as editor]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]))

(declare editor-text-get editor-text-set! editor-text-get-expanded)

(defn handle-external-editor
  "Open TARGET-EDITOR's content in $EDITOR (default nano). Suspends the TUI
   (terminal restored to normal mode, input reader paused), spawns the external
   editor on a temp file with inherited stdio, reads the result back into the
   editor, then resumes the TUI. TARGET-EDITOR defaults to the active editor.
   pi: handleOpenExternalEditor in interactive-mode.ts."
  [cs & [target-editor]]
  (let [target-editor (or target-editor @(:current-editor-atom cs))
        content (editor-text-get-expanded target-editor)
        tmp-dir (or (System/getenv "TMPDIR")
                    (System/getProperty "java.io.tmpdir")
                    "/tmp")
        _ (fs/create-dirs tmp-dir)
        tmp-file (str (fs/create-temp-file
                       {:prefix "kmet-editor-" :suffix ".md" :dir tmp-dir}))]
    ;; suspend is inside the try so the finally always resumes the TUI
    (try
      (tui/tui-suspend! (:tui cs))
      (spit tmp-file content)
      ;; pi: external editor command — config > VISUAL > EDITOR > nano
      (let [editor-cmd (or (System/getenv "VISUAL")
                           (System/getenv "EDITOR")
                           "nano")
            parts (str/split editor-cmd #"\s+")
            _ (println "Launching external editor: " editor-cmd)
            _ (println "kmet will resume when the editor exits.")
            result (try
                     (let [p (proc/process (concat parts [tmp-file])
                                           {:out :inherit :err :inherit :in :inherit
                                            ;; the editor opens where the session
                                            ;; works, not the launch directory
                                            ;; (pi: spawn cwd)
                                            :dir (or (some-> (:footer-provider cs)
                                                             fdp/fdp-get-cwd)
                                                     (str (fs/cwd)))})
                           exit-code (:exit @p)]
                       (if (zero? exit-code) :ok :cancelled))
                     (catch Exception e
                       (debug/log "external editor error: " e)
                       (ui/chat-history-add-message! (:chat-history cs)
                                                     {:role :assistant
                                                      :content (str "External editor failed to start: "
                                                                    (ex-message e))})
                       :error))]
        (when (= result :ok)
          (let [new-content (try (slurp tmp-file) (catch Exception _ nil))]
            (when (and new-content (not= new-content content))
              ;; pi: strip a single trailing newline added by editors
              (let [new-content (if (and (seq new-content)
                                         (str/ends-with? new-content "\n"))
                                  (subs new-content 0 (dec (count new-content)))
                                  new-content)]
                (editor-text-set! target-editor new-content)
                (debug/log "external editor content: " (pr-str new-content)))))))
      (finally
        (try (fs/delete-if-exists tmp-file) (catch Exception _ nil))
        (tui/tui-resume! (:tui cs))))
    nil))

(defn editor-text-get
  "Read the editor text through IEditorComponent when available, falling
   back to the field-based editor fn (duck-typed custom editors)."
  [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-text ed)
    (editor/editor-get-text ed)))

(defn editor-text-set!
  "Replace the editor text through IEditorComponent when available, falling
   back to the field-based editor fn (duck-typed custom editors)."
  [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-text! ed text)
    (editor/editor-set-text! ed text))
  nil)

(defn editor-text-get-expanded
  "Read the editor text with paste markers expanded through IEditorComponent
   when available, falling back to the field-based editor fn (pi:
   getEditorText = getExpandedText ?? getText)."
  [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-expanded-text ed)
    (editor/editor-get-expanded-text ed)))

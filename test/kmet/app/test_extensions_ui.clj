(ns kmet.app.test-extensions-ui
  "Extension UI registry tests — dispatch, no-op-before-install, and the
   extension-facing ui-* API (pi: ExtensionUIContext)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.app.extensions :as ex]))

(defn- install-test-registry!
  "Install a registry that records calls, with a :custom impl returning a
   promise resolved by the factory's done callback."
  []
  (let [calls (atom [])]
    (ex/set-ui-registry!
     {:notify (fn [message type] (swap! calls conj [:notify message type]) nil)
      :custom (fn [factory opts]
                (swap! calls conj [:custom factory opts])
                (let [p (promise)]
                  (try (factory nil nil nil (fn [v] (deliver p v)))
                       (catch Exception _ (deliver p nil)))
                  p))
      :set-status (fn [key text] (swap! calls conj [:set-status key text]))
      :set-widget (fn [key content opts] (swap! calls conj [:set-widget key content opts]))
      :set-footer (fn [factory] (swap! calls conj [:set-footer factory]))
      :set-header (fn [factory] (swap! calls conj [:set-header factory]))
      :set-title (fn [title] (swap! calls conj [:set-title title]))
      :on-terminal-input (fn [handler] (swap! calls conj [:on-terminal-input handler]) ::unsub)
      :set-editor-text (fn [text] (swap! calls conj [:set-editor-text text]))
      :get-editor-text (fn [] (swap! calls conj [:get-editor-text]) "txt")
      :paste-to-editor (fn [text] (swap! calls conj [:paste-to-editor text]))
      :set-working-indicator (fn [options] (swap! calls conj [:set-working-indicator options]))
      :set-working-message (fn [msg] (swap! calls conj [:set-working-message msg]))
      :set-working-visible (fn [v] (swap! calls conj [:set-working-visible v]))
      :set-hidden-thinking-label (fn [label] (swap! calls conj [:set-hidden-thinking-label label]))
      :set-editor-component (fn [factory] (swap! calls conj [:set-editor-component factory]))
      :add-autocomplete-provider (fn [factory] (swap! calls conj [:add-autocomplete-provider factory]))
      :set-theme (fn [theme-or-name] (swap! calls conj [:set-theme theme-or-name]))
      :get-tools-expanded (fn [] (swap! calls conj [:get-tools-expanded]) false)
      :set-tools-expanded (fn [v] (swap! calls conj [:set-tools-expanded v]))
      :get-tool-display-mode (fn [] (swap! calls conj [:get-tool-display-mode]) :collapsed)
      :set-tool-display-mode (fn [v] (swap! calls conj [:set-tool-display-mode v]))
      :reset (fn [] (swap! calls conj [:reset]))})
    calls))

(deftest test-dispatch-through-registry
  (testing "ui-* fns dispatch to the installed registry impls"
    (let [calls (install-test-registry!)]
      (t/is (nil? (ex/ui-notify "hi" :info)))
      (ex/ui-set-status "ext" "● active")
      (ex/ui-set-widget "w" ["line1"] {:placement :below-editor})
      (ex/ui-set-footer (fn [] nil))
      (ex/ui-set-header (fn [] nil))
      (ex/ui-set-title "kmet")
      (t/is (= ::unsub (ex/ui-on-terminal-input (fn [_] nil))))
      (ex/ui-set-editor-text "hello")
      (t/is (= "txt" (ex/ui-get-editor-text)))
      (ex/ui-paste-to-editor "pasted")
      (ex/ui-set-working-indicator {:frames []})
      (ex/ui-set-working-message "Working...")
      (ex/ui-set-working-visible true)
      (ex/ui-set-hidden-thinking-label "Thinking…")
      (ex/ui-set-editor-component (fn [] nil))
      (ex/ui-add-autocomplete-provider (fn [] nil))
      (ex/ui-set-theme "dark")
      (t/is (= false (ex/ui-get-tools-expanded)))
      (ex/ui-set-tools-expanded true)
      (t/is (= :collapsed (ex/ui-get-tool-display-mode)))
      (ex/ui-set-tool-display-mode :quiet)
      (ex/ui-reset!)
      (t/is (= [:notify
                :set-status :set-widget :set-footer :set-header :set-title
                :on-terminal-input :set-editor-text :get-editor-text
                :paste-to-editor :set-working-indicator :set-working-message
                :set-working-visible :set-hidden-thinking-label
                :set-editor-component :add-autocomplete-provider
                :set-theme :get-tools-expanded
                :set-tools-expanded :get-tool-display-mode
                :set-tool-display-mode :reset]
               (mapv first @calls)))
      (t/is (= "● active" (-> (nth @calls 1) (nth 2))))
      (t/is (= {:placement :below-editor} (-> (nth @calls 2) (nth 3))))
      (t/is (= {:frames []} (-> (nth @calls 10) (nth 1)))))))

(deftest test-ui-custom-promise
  (testing "ui-custom returns a promise resolved by the factory's done fn"
    (let [calls (install-test-registry!)
          p (ex/ui-custom (fn [_tui _theme _kb done] (done 42))
                          {:overlay true})]
      (t/is (= 42 (deref p 1000 ::timeout)))
      (t/is (= :custom (first (first @calls))))
      (t/is (= {:overlay true} (-> (first @calls) (nth 2)))
            "ui-custom forwards its opts map untouched (pi: custom(factory, options))"))))

(deftest test-ui-noop-before-install
  (testing "ui-* fns are inert before the registry is installed (pi: inert
            before startup)"
    (ex/clear-ui-registry!)
    (t/is (nil? (ex/ui-get-editor-text)))
    (t/is (nil? (ex/ui-notify "hi")))
    (t/is (nil? (ex/ui-set-status "k" "v")))))

(deftest test-ui-custom-factory-error
  (testing "a throwing factory resolves the promise with nil (no hang)"
    (let [p (do (install-test-registry!)
                (ex/ui-custom (fn [_ _ _ _] (throw (ex-info "boom" {})))))]
      (t/is (nil? (deref p 1000 ::timeout))))))

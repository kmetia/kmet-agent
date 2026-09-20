(ns kmet.tui.components.test-settings-list
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.settings-list :as sl]))

;; Raw key sequences
(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-LEFT "\u001b[D")
(def ^:const K-RIGHT "\u001b[C")
(def ^:const K-BS "\u007f")
(def ^:const K-ESC "\u001b")
(def ^:const K-ENTER "\r")

(def sample-items
  [{:id :theme :label "Theme" :value "dark" :values ["dark" "light" "auto"]}
   {:id :font-size :label "Font Size" :value 12 :values [10 12 14 16 18]}
   {:id :tab-size :label "Tab Size" :value 4 :values [2 4 8]}])

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-settings-list-create
  (let [s (sl/make-settings-list sample-items)]
    (t/is (satisfies? core/IComponent s))
    (t/is (satisfies? core/IFocusable s))
    (t/is (not (core/focused s)))))

(t/deftest test-settings-list-create-empty
  (let [s (sl/make-settings-list [])]
    (t/is (satisfies? core/IComponent s))))

(t/deftest test-settings-list-focus
  (let [s (sl/make-settings-list sample-items)]
    (core/set-focused! s true)
    (t/is (core/focused s))
    (core/set-focused! s false)
    (t/is (not (core/focused s)))))

;; ─── Navigation ────────────────────────────────────────────────────────────

(t/deftest test-settings-list-navigate-down
  (let [s (sl/make-settings-list sample-items)]
    (core/handle-input s K-DOWN)
    (t/is (= 1 @(:selected-idx-atom s)))))

(t/deftest test-settings-list-navigate-up
  (let [s (sl/make-settings-list sample-items)]
    (core/handle-input s K-DOWN)
    (core/handle-input s K-DOWN)
    (core/handle-input s K-UP)
    (t/is (= 1 @(:selected-idx-atom s)))))

(t/deftest test-settings-list-navigate-past-end
  ;; pi: navigation wraps around
  (let [s (sl/make-settings-list sample-items)]
    (dotimes [_ 10] (core/handle-input s K-DOWN))
    (t/is (= 1 @(:selected-idx-atom s)))))

;; ─── Cycling values ───────────────────────────────────────────────────────

(t/deftest test-settings-list-cycle-right
  (let [s (sl/make-settings-list sample-items)]
    (core/handle-input s K-RIGHT)
    (t/is (= "light" (:value (first @(:items-atom s)))))))

(t/deftest test-settings-list-cycle-left
  (let [s (sl/make-settings-list sample-items)]
    (core/handle-input s K-DOWN)  ;; select font-size (value=12)
    (core/handle-input s K-LEFT)  ;; cycle left to previous value
    (t/is (= 10 (:value (second @(:items-atom s)))))))

(t/deftest test-settings-list-cycle-wrap-around
  (let [s (sl/make-settings-list sample-items)]
    (dotimes [_ 3] (core/handle-input s K-RIGHT))  ;; dark > light > auto > dark
    (t/is (= "dark" (:value (first @(:items-atom s)))))))

;; ─── On-change callback ────────────────────────────────────────────────────

(t/deftest test-settings-list-on-change
  (let [changes (atom [])
        s (sl/make-settings-list sample-items
                                 :on-change (fn [id val] (swap! changes conj [id val])))]
    (core/handle-input s K-RIGHT)
    (t/is (= [[:theme "light"]] @changes))))

(t/deftest test-settings-list-cycle-enter
  ;; pi: Enter cycles to the next value (activateItem)
  (let [changes (atom [])
        s (sl/make-settings-list sample-items
                                 :on-change (fn [id val] (swap! changes conj [id val])))]
    (core/handle-input s K-ENTER)
    (t/is (= [[:theme "light"]] @changes))
    (t/is (= "light" (:value (first @(:items-atom s)))))))

(t/deftest test-settings-list-cycle-seq-values
  ;; Regression: the Theme row passes (sort ...) — an ArraySeq, not a
  ;; vector. cycle-value! called .indexOf on it; babashka's native image
  ;; refuses the reflective invocation of ArraySeq.indexOf
  ;; (MissingReflectionRegistrationError), the input dispatch aborts and the
  ;; value never changes — "switching theme doesn't change the UI".
  (let [changes (atom [])
        seq-items [{:id :theme :label "Theme" :value "light"
                    :values (sort ["dark" "light"])}]
        s (sl/make-settings-list seq-items
                                 :on-change (fn [id val] (swap! changes conj [id val])))]
    (core/handle-input s K-RIGHT)
    (t/is (= [[:theme "dark"]] @changes)
          "cycling a seq-valued row fires on-change")
    (t/is (= "dark" (:value (first @(:items-atom s)))))))

;; ─── Filtering (pi: enableSearch opt-in) ───────────────────────────────────

(t/deftest test-settings-list-filter
  (let [s (sl/make-settings-list sample-items :enable-search true)]
    (core/handle-input s "font")
    (t/is (= "font" @(:filter-atom s)))))

(t/deftest test-settings-list-filter-backspace
  (let [s (sl/make-settings-list sample-items :enable-search true)]
    (core/handle-input s "f")
    (core/handle-input s K-BS)
    (t/is (= "" @(:filter-atom s)))))

(t/deftest test-settings-list-filter-renders-search-line
  ;; pi: search-enabled settings lists render a "> " input line up top
  (let [s (sl/make-settings-list sample-items :enable-search true)
        lines (vec (core/render s 50))]
    (t/is (some #(.contains % "> ") lines))))

;; ─── Escape ────────────────────────────────────────────────────────────────

(t/deftest test-settings-list-escape
  (let [s (sl/make-settings-list sample-items)
        escaped (atom false)]
    (sl/settings-list-set-on-escape! s (fn [] (reset! escaped true)))
    (core/handle-input s K-ESC)
    (t/is @escaped)))

;; ─── get-item / set-value ─────────────────────────────────────────────────

(t/deftest test-settings-list-get-item
  (let [s (sl/make-settings-list sample-items)
        item (sl/settings-list-get-item s :theme)]
    (t/is (= "Theme" (:label item)))
    (t/is (= "dark" (:value item)))))

(t/deftest test-settings-list-get-item-not-found
  (let [s (sl/make-settings-list sample-items)]
    (t/is (nil? (sl/settings-list-get-item s :nonexistent)))))

(t/deftest test-settings-list-set-value
  (let [s (sl/make-settings-list sample-items)]
    (sl/settings-list-set-value! s :theme "light")
    (t/is (= "light" (:value (sl/settings-list-get-item s :theme))))))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-settings-list-render
  (let [s (sl/make-settings-list sample-items)
        lines (core/render s 50)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "Theme") lines))
    (t/is (some #(.contains % "Font Size") lines))))

(t/deftest test-settings-list-render-empty
  (let [s (sl/make-settings-list [])
        lines (core/render s 30)]
    (t/is (pos? (count lines)))))

;; ─── Default theme ─────────────────────────────────────────────────────────

(t/deftest test-settings-list-default-theme
  (t/is (some? sl/default-theme)))

(t/deftest test-settings-list-keys-resolve-through-the-manager
  (t/testing "P3: the list's keys go through the manager — a user override
              replaces the default chord"
    (let [prev (kb/get-global-keybindings)
          changed (atom nil)]
      (try
        (kb/set-global-keybindings!
         (kb/make-keybindings-manager kb/tui-keybinding-defs
                                      {"tui.select.confirm" "ctrl+y"}))
        (let [s (sl/make-settings-list sample-items
                                       :on-change (fn [id v] (reset! changed [id v])))]
          (core/handle-input s K-ENTER)
          (t/is (nil? @changed) "enter was rebound away from confirm")
          (core/handle-input s (str (char 25)))
          (t/is (= [:theme "light"] @changed) "ctrl+y cycles the value"))
        (finally
          (kb/set-global-keybindings! prev))))))

(t/deftest test-settings-list-value-cycling-resolves-through-the-manager
  (t/testing "tui.settings.cycleForward/Backward (kmet) are ids"
    (let [prev (kb/get-global-keybindings)]
      (try
        (kb/set-global-keybindings!
         (kb/make-keybindings-manager kb/tui-keybinding-defs
                                      {"tui.settings.cycleForward" "ctrl+e"
                                       "tui.settings.cycleBackward" "ctrl+y"}))
        (let [changes (atom [])
              s (sl/make-settings-list sample-items
                                       :on-change (fn [id v] (swap! changes conj [id v])))]
          (core/handle-input s K-RIGHT)
          (t/is (empty? @changes) "right was rebound away")
          (core/handle-input s "\u0005")
          (t/is (= [[:theme "light"]] @changes) "ctrl+e cycles forward")
          (core/handle-input s "\u0019")
          (t/is (= [[:theme "light"] [:theme "dark"]] @changes) "ctrl+y cycles back"))
        (finally
          (kb/set-global-keybindings! prev))))))

;; ─── Submenus (pi: SettingItem.submenu) ───────────────────────────────────

(defn- inner-list
  "A one-row settings list used as a test submenu; its on-change commits
   through DONE."
  [done]
  (sl/make-settings-list
   [{:id :inner :label "Inner" :value "x" :values ["x" "y"]}]
   :on-change (fn [_ v] (done v))))

(t/deftest test-settings-list-select-item
  (t/testing "selectItem moves the selection to an id (pi: selectItem)"
    (let [s (sl/make-settings-list sample-items)]
      (sl/settings-list-select-item! s :tab-size)
      (t/is (= 2 @(:selected-idx-atom s)))
      (sl/settings-list-select-item! s :nope)
      (t/is (= 2 @(:selected-idx-atom s)) "an unknown id is a no-op"))))

(t/deftest test-settings-list-submenu-opens-on-enter
  (let [s (sl/make-settings-list
           [{:id :theme :label "Theme" :value "dark"
             :submenu (fn [_current done] (inner-list done))}])]
    (t/is (nil? @(:submenu-atom s)))
    (core/handle-input s K-ENTER)
    (t/is (some? @(:submenu-atom s)) "Enter opens the submenu")
    (t/is (some #(str/includes? % "Inner") (core/render s 80))
          "the list renders the submenu in place of its rows")
    (t/is (not-any? #(str/includes? % "Theme") (core/render s 80))
          "the main rows are hidden while the submenu is open")))

(t/deftest test-settings-list-submenu-forwards-input-and-commits
  (let [changes (atom [])
        s (sl/make-settings-list
           [{:id :theme :label "Theme" :value "dark"
             :submenu (fn [_current done] (inner-list done))}]
           :on-change (fn [id v] (swap! changes conj [id v])))]
    (core/handle-input s K-ENTER)  ;; open
    (core/handle-input s K-ENTER)  ;; forwarded: inner cycles x → y → done "y"
    (t/is (nil? @(:submenu-atom s)) "done closes the submenu")
    (t/is (= [[:theme "y"]] @changes) "done fires the parent :on-change")
    (t/is (= "y" (:value (first @(:items-atom s)))))
    (t/is (some #(str/includes? % "Theme") (core/render s 80))
          "the main list is back after close")))

(t/deftest test-settings-list-submenu-escape-closes-without-change
  (let [changes (atom [])
        s (sl/make-settings-list
           [{:id :a :label "A" :value "1" :values ["1" "2"]}
            {:id :theme :label "Theme" :value "dark"
             :submenu (fn [_current done]
                        (let [inner (inner-list done)]
                          (sl/settings-list-set-on-escape! inner (fn [] (done)))
                          inner))}]
           :on-change (fn [id v] (swap! changes conj [id v])))]
    (core/handle-input s K-DOWN)   ;; select the theme row (idx 1)
    (core/handle-input s K-ENTER)  ;; open
    (core/handle-input s K-ESC)    ;; forwarded → inner escape → done
    (t/is (nil? @(:submenu-atom s)))
    (t/is (empty? @changes) "escape commits nothing")
    (t/is (= "dark" (:value (second @(:items-atom s)))))
    (t/is (= 1 @(:selected-idx-atom s))
          "selection returns to the row that opened the submenu")))

(t/deftest test-settings-list-submenu-disposed-on-close
  (let [disposed (atom false)
        s (sl/make-settings-list
           [{:id :theme :label "Theme" :value "dark"
             :submenu (fn [_current done]
                        (reify protocols/IComponent
                          (render [_ _width] ["sub"])
                          (handle-input [_ _data] (done "auto"))
                          (invalidate [_] nil)
                          (dispose [_] (reset! disposed true))))}])]
    (core/handle-input s K-ENTER)
    (t/is (false? @disposed))
    (core/handle-input s "x")  ;; forwarded to the submenu → done
    (t/is (true? @disposed) "closing disposes the submenu (track! watches)")
    (t/is (= "auto" (:value (first @(:items-atom s)))))))

(t/deftest test-settings-list-submenu-disposed-with-the-list
  (let [disposed (atom false)
        s (sl/make-settings-list
           [{:id :theme :label "Theme" :value "dark"
             :submenu (fn [_current _done]
                        (reify protocols/IComponent
                          (render [_ _width] ["sub"])
                          (handle-input [_ _data] nil)
                          (invalidate [_] nil)
                          (dispose [_] (reset! disposed true))))}])]
    (core/handle-input s K-ENTER)
    (protocols/dispose s)
    (t/is (true? @disposed) "disposing the list disposes an open submenu")))

(t/deftest test-settings-list-submenu-row-cycles-nothing
  (t/testing "left/right are a no-op on a submenu row — :values is ignored
              for it even when both are present (pi: activateItem has no
              cycle branch for submenu items)"
    (let [changes (atom [])
          s (sl/make-settings-list
             [{:id :theme :label "Theme" :value "dark"
               :values ["dark" "light"]
               :submenu (fn [_current done] (inner-list done))}]
             :on-change (fn [id v] (swap! changes conj [id v])))]
      (core/handle-input s K-RIGHT)
      (core/handle-input s K-LEFT)
      (t/is (empty? @changes))
      (t/is (= "dark" (:value (first @(:items-atom s)))))
      (t/is (nil? @(:submenu-atom s)) "left/right never opens the submenu"))))



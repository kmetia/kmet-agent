(ns kmet.ui.test-headless
  "The headless `:ui` provider and the service it implements (`kmet.app.ui`).

   Four properties: a provider answers the contract's required keys, a provider
   that does not fails at the mount naming what it lacks, the profiles that
   render nothing publish `:ui`, and a run binds the row's `:port` — so the
   dialog a run's extension asks answers from the composition
   (`ui-request-defaults` by kind, or the provider that replaced the row)
   rather than from a hardcoded fallback, and a composition that patched the row
   out has no `:ui` at all."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.core :as core]
            [kmet.app.runtime :as runtime]
            [kmet.app.test-support :as support]
            [kmet.app.ui :as ui]
            [kmet.srce.core :as srce]
            [kmet.ui.headless :as headless]
            [kmet.test-tmp :as test-tmp]))

;; ── the contract ─────────────────────────────────────────────────────────

(def ^:private providers
  "Every `:ui` provider the tree ships, by the var a contract test can read. A
   provider added without a line here is not covered — landing 5l added
   `kmet.ui.tui`; a second provider (`kmet.ui.tk`) is separate development — and
   a provider that drops a required key fails the check below."
  {:ui/headless #'headless/provider})

(defn- check-error
  "The ex-info `ui/check` throws for PROVIDER, or nil when it passes."
  [provider]
  (try (ui/check :ui/broken provider) nil (catch Exception e e)))

(deftest every-provider-answers-the-contract
  (doseq [[name var] providers
          :let [provider (var-get var)]]
    (is (= provider (ui/check name provider))
        (str name " answers " ui/required-keys))
    (is (keyword? (:kind provider)))
    (is (fn? (:render-tree provider)))
    (is (fn? (:request! provider)))
    (is (set? (:capabilities provider)))))

(deftest the-port-adapts-the-contract
  ;; The port takes the request alone, so the provider's ctx is closed in; a
  ;; declared notify rides the port and an undeclared one stays absent. These
  ;; are `kmet.app.ui/port`'s optional branches — the headless provider declares
  ;; neither, so nothing else exercises them.
  (let [seen (atom nil)
        notify (fn [_message _type] nil)
        provider {:kind :test
                  :render-tree (fn [_tree] nil)
                  :request! (fn [ctx _request] (reset! seen ctx) :answered)
                  :capabilities #{:notify}
                  :notify notify}
        port (ui/port :provider-ctx provider)]
    (is (= :answered ((:request port) {:kind :confirm})))
    (is (= :provider-ctx @seen) "the request sees the ctx the provider mounted in")
    (is (identical? notify (:notify port)) "a declared notify rides the port")
    (is (not (contains? (ui/port :provider-ctx (dissoc provider :notify)) :notify))
        "an undeclared one stays absent")))

(deftest the-headless-provider-offers-nothing-optional
  (is (= :headless (:kind headless/provider)))
  (is (= #{} (:capabilities headless/provider))
      "a consumer asking for :widget-set or :notify sees it declared none"))

(deftest a-provider-missing-a-required-key-fails-at-the-mount-naming-it
  (let [e (check-error {:kind :x :request! (fn [_ _] nil) :capabilities #{}})]
    (is (= :kmet.app/ui-provider (:type (ex-data e))))
    (is (= [:render-tree] (:missing (ex-data e))))
    (is (str/includes? (ex-message e)
                       "UI provider :ui/broken is missing render-tree")
        "the failure names the provider and the key, not a call site"))
  (is (= [:render-tree]
         (:missing (ex-data (check-error {:kind :x :render-tree nil
                                          :request! (fn [_ _] nil)
                                          :capabilities #{}}))))
      "a key that is present but nil does not answer either"))

(deftest the-profiles-that-render-nothing-stack-the-provider
  (doseq [profile [:print :rpc :rpc-ask :server]]
    (let [rows (:rows (core/compose-config
                       (core/parse-args ["--profile" (name profile)])))]
      (is (some #(= 'kmet.ui.headless/plugin (:plugin %)) rows)
          (str profile " stacks :bundle/headless")))))

;; ── the row's port, and the absence of one ───────────────────────────────

(defn- scratch-patch!
  "Write CONTENT to a patch file under target/ and return the path."
  [content]
  (let [path (test-tmp/temp-file! ".edn")]
    (io/make-parents path)
    (spit path content)
    path))

(defn- boot-print
  "The print profile booted with PATCH (a patch path, or nil), in a sandbox: a
   diagnostic boot starts no run, and the session/config lookups read target/."
  [patch]
  (support/with-mode-run
    (fn [_] :done)
    (fn []
      (let [opts (cond-> (core/parse-args ["--profile" "print"])
                   patch (assoc :patch [patch]))]
        (runtime/boot (core/compose-config opts) {:diagnose? true})))))

(defn- dump-state-with
  "The print profile's `--dump-state` text with PATCH, in the same sandbox."
  [patch]
  (support/with-mode-run
    (fn [_] :done)
    (fn []
      (:out (support/run-main ["--profile" "print" "--patch" patch "--dump-state"])))))

(defn- line-of
  "The first dump line mentioning NEEDLE — or `\"\"`, so a missing row fails an
   `str/includes?` assertion instead of throwing."
  [dump needle]
  (or (some #(when (str/includes? % needle) %) (str/split-lines dump)) ""))

(deftest the-rows-port-answers-dialogs-by-kind
  (let [booted (boot-print nil)]
    (try
      (let [provider (srce/get (:ctx booted) :ui)
            port (:port provider)]
        (is (= :headless (:kind provider)) "the row is the headless provider")
        (is (false? ((:request port) {:kind :confirm}))
            "a confirm answers the ABI's default from the row")
        (is (nil? ((:request port) {:kind :select})))
        (is (= {:success false :error "UI not available"} ((:set-theme port) :dark))
            "and the port keeps the answers every front end must give"))
      (finally (runtime/shutdown! booted)))))

(deftest without-the-row-there-is-no-ui-service
  (let [path (scratch-patch! "{:rows [] :patch [{:id :ui :remove true}]}")
        booted (boot-print path)]
    (try
      (is (nil? (srce/get (:ctx booted) :ui))
          "a composition that patched the provider out has no :ui — the nil the
           gate distinguishes from a hardcoded default")
      (finally
        (runtime/shutdown! booted)
        (io/delete-file path true)))))

(def test-provider
  "A provider with a recognisable dialog answer, so a *run's* bound port can be
   traced to the row: a mode that kept its own headless port would answer
   `false` for a confirm, this one answers `:from-the-row`."
  {:name :ui/test-provider
   :provide #{:ui}
   :apply (fn [ctx _]
            (ui/provide! ctx :ui/test-provider
                         {:kind :test
                          :render-tree (fn [_tree] nil)
                          :request! (fn [_ctx request]
                                      (if (= :confirm (:kind request))
                                        :from-the-row
                                        (extensions/ui-request-default (:kind request))))
                          :capabilities #{}}))})

(deftest a-mode-binds-the-rows-port-not-a-default
  ;; The gate's behavioural half inside a run: print's provider is replaced by
  ;; one whose answer is recognisable, and the surface the run binds answers
  ;; with it — a mode that built its own headless port would answer `false`.
  (let [path (scratch-patch!
              "{:rows [] :patch [{:id :ui :plugin kmet.ui.test-headless/test-provider}]}")
        surface (atom nil)]
    (try
      (support/with-mode-run
        (fn [opts]
          (reset! surface (extensions/front-end-surface))
          (future (when-let [on-done (:on-done opts)] (on-done :stop)) :done))
        (fn []
          (let [{:keys [code err]} (support/run-main ["-p" "--patch" path "hi"])]
            (is (= 0 code) err))))
      (is (= :from-the-row ((get-in @surface [:ui :request]) {:kind :confirm}))
          "the run's port is the row's provider, not the ABI's default")
      (is (false? (:has-ui @surface)) "print still declares no terminal UI")
      (finally (io/delete-file path true)))))

(def probe
  "A consumer that needs `:ui` unconditionally: its row is pending until the
   provider mounts, which is what proves the provider is published, not just
   composed. The patch files above name this var."
  {:name :ui/test-probe
   :inject [:ui]
   :apply (fn [_ _] nil)})

(deftest a-consumer-of-the-row-is-active
  (let [path (scratch-patch!
              "{:rows [{:id :ui/test-probe :plugin kmet.ui.test-headless/probe}]\n  :patch []}")]
    (try
      (is (str/includes? (line-of (dump-state-with path) ":ui/test-probe") ":active")
          "the probe mounts over the provider the profile stacked")
      (finally (io/delete-file path true)))))

(deftest a-consumer-without-the-row-is-pending-not-failed
  (let [path (scratch-patch!
              (str "{:rows [{:id :ui/test-probe :plugin kmet.ui.test-headless/probe}]\n"
                   " :patch [{:id :ui :remove true}]}"))]
    (try
      (let [line (line-of (dump-state-with path) ":ui/test-probe")]
        (is (str/includes? line ":pending"))
        (is (str/includes? line "waiting-for=[ui]")
            "without the row the consumer waits, and the dump says for what"))
      (finally (io/delete-file path true)))))

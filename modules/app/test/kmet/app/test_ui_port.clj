(ns kmet.app.test-ui-port
  "Front-end extension binding + UI provider contract: bind-extensions!
   carries the front-end kind (ctx.mode / ctx.has-ui), the headless port
   auto-answers, and the install/unbind pair owns the port registration."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.app.extensions.core :as ex]
            [kmet.app.registry :as registry]
            [kmet.test-utils :as tu]))

;; The binding and the app's registrations are process-global state;
;; every test starts and ends headless with a cleared tree.
(t/use-fixtures :each
  (fn [f]
    (ex/unbind-extensions!)
    ;; the port and the capability registrations need a host; the fixture binds
    ;; one over its own registry, as a bare test process does
    (tu/with-extension-host
      (fn []
        (tu/clear-app-registries!)
        (try (f)
             (finally
               (ex/unbind-extensions!)
               (tu/clear-app-registries!)))))))

(deftest test-bind-extensions-sets-kind-and-port
  (testing "bind-extensions! installs the front-end kind and capability map"
    (ex/bind-extensions! {:mode :interactive :has-ui true
                          :ui {:notify (fn [_ _] :notified)}})
    (t/is (= :interactive (ex/extension-mode)))
    (t/is (true? (ex/extension-has-ui?)))
    (t/is (= :notified (ex/ui-call :notify "hi" :info)))
    (t/is (nil? (ex/ui-call :missing)))))

(deftest test-unbind-restores-headless-defaults
  (ex/bind-extensions! {:mode :gui :has-ui true :ui {:notify (fn [_ _] nil)}})
  (ex/unbind-extensions!)
  (t/is (= :print (ex/extension-mode)))
  (t/is (false? (ex/extension-has-ui?)))
  (t/is (nil? (ex/ui-call :notify "hi" :info))))

(deftest test-install-front-end-owns-the-port
  (testing "install-front-end! binds the surface and records the port"
    (try
      (ex/install-front-end! {:mode :interactive :has-ui true
                              :ui {:notify (fn [_ _] :notified)}})
      (t/is (= :interactive (ex/extension-mode)))
      (t/is (= :notified (ex/ui-call :notify "hi" :info)))
      (t/is (contains? (registry/entry (registry/current) :kmet/ui-port :port) :notify)
            "the port is the [:app :ui] registration")
      (finally
        (ex/unbind-extensions!)
        (registry/deregister! (registry/current) :kmet/ui-port :port [:app :ui])))
    (t/is (= :print (ex/extension-mode))
          "unbinding restores the headless binding")))

(deftest test-port-only-swap-preserves-kind
  (testing "set-ui-registry!/clear-ui-registry! swap the port, not the kind"
    (ex/bind-extensions! {:mode :gui :has-ui true :ui {}})
    (ex/set-ui-registry! {:get-editor-text (fn [] "draft")})
    (t/is (= :gui (ex/extension-mode)))
    (t/is (= "draft" (ex/ui-call :get-editor-text)))
    (ex/clear-ui-registry!)
    (t/is (= :gui (ex/extension-mode)))
    (t/is (nil? (ex/ui-call :get-editor-text)))))

(deftest test-build-extension-context-carries-mode-and-port
  (testing "ctx.mode / ctx.has-ui come from the binding; :build-context merges"
    (ex/bind-extensions! {:mode :interactive :has-ui true
                          :ui {:build-context (fn [] {:cwd "/tmp/x"})}})
    (let [ctx (ex/build-extension-context)]
      (t/is (= :interactive (:mode ctx)))
      (t/is (true? (:has-ui ctx)))
      (t/is (= "/tmp/x" (:cwd ctx)))))
  (testing "the binding wins over a :build-context that restates the kind"
    (ex/bind-extensions! {:mode :gui :has-ui true
                          :ui {:build-context (fn [] {:mode :print :has-ui false})}})
    (let [ctx (ex/build-extension-context)]
      (t/is (= :gui (:mode ctx)))
      (t/is (true? (:has-ui ctx))))))

(deftest test-headless-port-auto-answers
  (testing "the degraded port answers every capability without a front end"
    (ex/bind-extensions! {:mode :print :has-ui false :ui (ex/headless-ui-port)})
    (t/is (nil? (ex/ui-notify "hi" :info))
          "omitted setters fall through ui-call as no-ops")
    (t/is (= "" (ex/ui-get-editor-text)))
    (t/is (false? (ex/ui-get-tools-expanded)))
    (t/is (= :collapsed (ex/ui-get-tool-display-mode)))
    (t/is (= {:success false :error "UI not available"} (ex/ui-set-theme "dark")))
    (t/is (fn? (ex/ui-on-terminal-input (fn [_] nil)))
          ":on-terminal-input returns a no-op unsubscriber")
    (t/is (nil? (deref (ex/ui-custom (fn [_ _ _ _] nil) {}) 100 ::timeout))
          ":custom returns an already-resolved promise")
    (t/is (nil? (ex/ui-call :request {:kind :select})))
    (t/is (false? (ex/ui-call :request {:kind :confirm})))))

(deftest test-headless-port-custom-via-extension-api
  (testing "ext/ui-custom through the headless port resolves instead of nil"
    (ex/bind-extensions! {:mode :print :has-ui false :ui (ex/headless-ui-port)})
    (t/is (nil? (deref (ex/ui-custom (fn [_ _ _ done] (done nil)) {})
                       100 ::timeout)))))

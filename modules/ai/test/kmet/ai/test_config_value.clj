(ns kmet.ai.test-config-value
  "Phase 6: config-value resolution — env interpolation, $$/$! escapes,
   !command (cached, timeout), missing-env → nil, is-config-value-configured?,
   header resolution (pi resolve-config-value.ts)."
  (:require [clojure.test :as t]
            [kmet.ai.config-value :as dynamic-value]))

;; ─── Parsing (pi parseConfigValueTemplate) ─────────────────────────────────

(t/deftest test-parse-config-value
  (t/testing "literal without $"
    (t/is (= {:type :template
              :parts [{:type :literal :value "sk-abc"}]}
             (dynamic-value/parse-config-value "sk-abc"))))
  (t/testing "command"
    (t/is (= {:type :command :command "!echo hi"}
             (dynamic-value/parse-config-value "!echo hi"))))
  (t/testing "$NAME env part"
    (t/is (= {:type :template :parts [{:type :env :name "FOO"}]}
             (dynamic-value/parse-config-value "$FOO"))))
  (t/testing "${NAME} env part"
    (t/is (= {:type :template :parts [{:type :env :name "FOO_BAR"}]}
             (dynamic-value/parse-config-value "${FOO_BAR}"))))
  (t/testing "$$ escapes a literal $; $! a literal !"
    (t/is (= {:type :template :parts [{:type :literal :value "$"}]}
             (dynamic-value/parse-config-value "$$")))
    (t/is (= {:type :template :parts [{:type :literal :value "!"}]}
             (dynamic-value/parse-config-value "$!"))))
  (t/testing "interpolation inside larger literals (pi: ${KEY}_BAR)"
    (t/is (= {:type :template
              :parts [{:type :env :name "KEY_PREFIX"}
                      {:type :literal :value "_BAR"}]}
             (dynamic-value/parse-config-value "${KEY_PREFIX}_BAR"))))
  (t/testing "invalid ${name} and bare $ stay literal"
    (t/is (= {:type :template :parts [{:type :literal :value "${1BAD}"}]}
             (dynamic-value/parse-config-value "${1BAD}")))
    (t/is (= {:type :template :parts [{:type :literal :value "$"}]}
             (dynamic-value/parse-config-value "$")))))

;; ─── Env var name introspection ────────────────────────────────────────────

(t/deftest test-env-var-names
  (t/testing "single reference → name"
    (t/is (= "FOO" (dynamic-value/get-config-value-env-var-name "$FOO")))
    (t/is (nil? (dynamic-value/get-config-value-env-var-name "${FOO}_BAR")))
    (t/is (nil? (dynamic-value/get-config-value-env-var-name "literal")))
    (t/is (nil? (dynamic-value/get-config-value-env-var-name "!cmd"))))
  (t/testing "all names, deduped, in order"
    (t/is (= ["A" "B"] (dynamic-value/get-config-value-env-var-names "${A}_${B}${A}")))
    (t/is (= [] (dynamic-value/get-config-value-env-var-names "literal")))
    (t/is (= [] (dynamic-value/get-config-value-env-var-names "!cmd"))))
  (t/testing "missing names"
    (with-redefs [dynamic-value/getenv (fn [k] (when (= k "A") "a"))]
      (t/is (= ["B"] (dynamic-value/get-missing-config-value-env-var-names "${A}_${B}" nil)))
      (t/is (= [] (dynamic-value/get-missing-config-value-env-var-names "${A}" nil))))
    (t/testing "explicit env map takes precedence over process env"
      (with-redefs [dynamic-value/getenv (fn [_] nil)]
        (t/is (= [] (dynamic-value/get-missing-config-value-env-var-names "${B}" {"B" "b"})))))))

;; ─── Classification (pi isCommandConfigValue / isConfigValueConfigured) ────

(t/deftest test-classification
  (t/is (true? (dynamic-value/is-command-config-value? "!op read")))
  (t/is (false? (dynamic-value/is-command-config-value? "$FOO")))
  (t/testing "is-config-value-configured?: literals and commands always"
    (with-redefs [dynamic-value/getenv (fn [_] nil)]
      (t/is (true? (dynamic-value/is-config-value-configured? "sk-literal")))
      (t/is (true? (dynamic-value/is-config-value-configured? "!cmd")))))
  (t/testing "is-config-value-configured?: $ENV needs the var"
    (with-redefs [dynamic-value/getenv (fn [k] (when (= k "FOO") "v"))]
      (t/is (true? (dynamic-value/is-config-value-configured? "$FOO")))
      (t/is (false? (dynamic-value/is-config-value-configured? "${MISSING}")))
      (t/is (false? (dynamic-value/is-config-value-configured? "${MISSING}_x"))))))

;; ─── Resolution (pi resolveConfigValue) ────────────────────────────────────

(t/deftest ^:slow test-resolve-config-value
  (t/testing "literal"
    (t/is (= "sk-abc" (dynamic-value/resolve-config-value "sk-abc" nil))))
  (t/testing "$NAME / ${NAME} interpolation (env map first, then process env)"
    (with-redefs [dynamic-value/getenv (fn [k] (when (= k "FOO") "proc"))]
      (t/is (= "proc" (dynamic-value/resolve-config-value "$FOO" nil)))
      (t/is (= "explicit" (dynamic-value/resolve-config-value "$FOO" {"FOO" "explicit"})))
      (t/is (= "pre_SFX" (dynamic-value/resolve-config-value "${KEY_PREFIX}_SFX" {"KEY_PREFIX" "pre"})))))
  (t/testing "missing env var → nil"
    (with-redefs [dynamic-value/getenv (fn [_] nil)]
      (t/is (nil? (dynamic-value/resolve-config-value "$MISSING" nil)))
      (t/is (nil? (dynamic-value/resolve-config-value "pre_${MISSING}_post" nil)))))
  (t/testing "$$ / $! escapes"
    (t/is (= "$" (dynamic-value/resolve-config-value "$$" nil)))
    (t/is (= "!" (dynamic-value/resolve-config-value "$!" nil)))
    (t/is (= "a$b" (dynamic-value/resolve-config-value "a$$b" nil))))
  (t/testing "!command executes, stdout trimmed"
    (dynamic-value/clear-config-value-cache!)
    (t/is (= "hi" (dynamic-value/resolve-config-value "!echo hi" nil)))))

(t/deftest ^:slow test-resolve-config-value-command-failures
  (dynamic-value/clear-config-value-cache!)
  (t/testing "non-zero exit → nil"
    (t/is (nil? (dynamic-value/resolve-config-value "!false" nil))))
  (t/testing "empty stdout → nil"
    (t/is (nil? (dynamic-value/resolve-config-value "!true" nil)))))

(t/deftest test-command-cache
  (dynamic-value/clear-config-value-cache!)
  (let [calls (atom 0)]
    (with-redefs [dynamic-value/execute-command-uncached
                  (fn [_] (swap! calls inc) "cached-result")]
      (t/is (= "cached-result" (dynamic-value/resolve-config-value "!cmd" nil)))
      (t/is (= "cached-result" (dynamic-value/resolve-config-value "!cmd" nil)))
      (t/is (= 1 @calls) "second resolution hits the cache")
      (t/testing "clear-config-value-cache! forces re-execution"
        (dynamic-value/clear-config-value-cache!)
        (t/is (= "cached-result" (dynamic-value/resolve-config-value "!cmd" nil)))
        (t/is (= 2 @calls))))))

(t/deftest ^:slow test-resolve-config-value-or-throw
  (with-redefs [dynamic-value/getenv (fn [_] nil)]
    (t/testing "resolvable → value"
      (t/is (= "sk-1" (dynamic-value/resolve-config-value-or-throw "sk-1" "API key for provider \"x\"" nil))))
    (t/testing "missing single env var names it"
      (let [e (try (dynamic-value/resolve-config-value-or-throw "$MISSING" "API key for provider \"x\"" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve API key for provider \"x\" from environment variable: MISSING"
                 (ex-message e)))))
    (t/testing "missing multiple env vars listed"
      (let [e (try (dynamic-value/resolve-config-value-or-throw "${A}_${B}" "key" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve key from environment variables: A, B"
                 (ex-message e)))))
    (t/testing "failed command names it"
      (dynamic-value/clear-config-value-cache!)
      (let [e (try (dynamic-value/resolve-config-value-or-throw "!false" "key" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve key from shell command: false"
                 (ex-message e)))))))

;; ─── Header resolution (pi resolveHeaders / resolveHeadersOrThrow) ─────────

(t/deftest test-resolve-headers
  (with-redefs [dynamic-value/getenv (fn [k] (when (= k "K") "v"))]
    (t/testing "values resolve as config values; unresolvable ones drop"
      (t/is (= {"X-Literal" "a" "X-Env" "v"}
               (dynamic-value/resolve-headers {"X-Literal" "a" "X-Env" "$K" "X-Drop" "$MISSING"} nil))))
    (t/testing "nil when no headers or all drop"
      (t/is (nil? (dynamic-value/resolve-headers nil nil)))
      (t/is (nil? (dynamic-value/resolve-headers {"X" "$MISSING"} nil)))
      (t/is (nil? (dynamic-value/resolve-headers {} nil))))))

(t/deftest test-resolve-headers-or-throw
  (with-redefs [dynamic-value/getenv (fn [_] nil)]
    (t/testing "throws on an unresolvable header, naming it"
      (let [e (try (dynamic-value/resolve-headers-or-throw {"X" "$MISSING"} "model \"p/m\"" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve model \"p/m\" header \"X\" from environment variable: MISSING"
                 (ex-message e)))))
    (t/testing "resolves clean maps"
      (t/is (= {"X" "a"} (dynamic-value/resolve-headers-or-throw {"X" "a"} "p" nil))))))

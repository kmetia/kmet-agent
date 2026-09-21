(ns kmet.tasks.test-changed
  "Tests for the changed-file/require-graph helper behind `bb *-changed`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.changed :as changed]))

(deftest path->ns-mapping
  (testing "src files map to namespaces (underscores become dashes)"
    (is (= 'kmet.core (changed/path->ns "src/kmet/core.clj")))
    (is (= 'kmet.app.ui.model-selector (changed/path->ns "src/kmet/app/ui/model_selector.clj")))
    (is (= 'kmet.ai.api.openai-completions (changed/path->ns "src/kmet/ai/api/openai_completions.clj"))))
  (testing "test files map to test namespaces"
    (is (= 'kmet.app.test-loop (changed/path->ns "test/kmet/app/test_loop.clj"))))
  (testing "task files map through their own root (tasks/, not src/)"
    (is (= 'kmet.tasks.build (changed/path->ns "tasks/kmet/tasks/build.cljc")))
    (is (= 'kmet.tasks.generate-image-models
           (changed/path->ns "tasks/kmet/tasks/generate_image_models.clj")))
    (is (= 'kmet.tasks.test-lint (changed/path->ns "test/kmet/tasks/test_lint.clj"))))
  (testing "extension files map to path-derived namespaces"
    (is (= 'extensions.tools (changed/path->ns "extensions/tools.clj"))))
  (testing "Windows fs/glob separators normalize — the graph keys are /-spelled"
    (is (= 'kmet.app.ui.model-selector
           (changed/path->ns "src\\kmet\\app\\ui\\model_selector.clj")))
    (is (= 'kmet.tasks.test-lint
           (changed/path->ns "test\\kmet\\tasks\\test_lint.clj")))))

(deftest ns-requires-extraction
  (testing "vector entries"
    (is (= '#{kmet.app.loop kmet.ai.models kmet.config}
           (changed/ns-requires
            '(ns kmet.modes.print
               "doc"
               (:require [kmet.app.loop :as agent]
                         [kmet.ai.models :as models]
                         [kmet.config :as cfg]))))))
  (testing "prefix-list entries expand to full namespaces"
    (is (= '#{kmet.libs.a kmet.libs.b.c kmet.libs.d}
           (changed/ns-requires
            '(ns foo (:require (kmet.libs [a :as x] [b.c :as y] d)))))))
  (testing "non-kmet requires are ignored, non-ns forms yield nil"
    (is (= '#{} (changed/ns-requires '(ns foo (:require [clojure.string :as str])))))
    (is (nil? (changed/ns-requires '(defn foo [] 1))))))

(deftest affected-test-namespaces
  (testing "a source change reaches direct and transitive test dependents across layers"
    (let [nss (set (changed/affected-test-nss-by '[kmet.ai.models]))]
      (is (contains? nss 'kmet.ai.test-models))
      (is (contains? nss 'kmet.ai.test-provider-composer))
      (is (contains? nss 'kmet.app.test-model-resolver))))
  (testing "changing a test namespace includes itself"
    (let [nss (set (changed/affected-test-nss-by '[kmet.tui.components.test-text]))]
      (is (contains? nss 'kmet.tui.components.test-text))))
  (testing "a -test suffix counts too (kmet.tasks.build-test mirrors its src ns)"
    (let [nss (set (changed/affected-test-nss-by '[kmet.tasks.build]))]
      (is (contains? nss 'kmet.tasks.build-test)))
    (let [nss (set (changed/affected-test-nss-by '[kmet.tasks.build-jolt]))]
      (is (contains? nss 'kmet.tasks.build-jolt-test))))
  (testing "tasks/ is a scanned root, so a change in what task code requires reaches it
            (the closure works across roots, not just src/)"
    (let [nss (set (changed/affected-test-nss-by '[kmet.libs.archive]))]
      (is (contains? nss 'kmet.tasks.build-test))
      (is (contains? nss 'kmet.tasks.build-jolt-test))))
  (testing "a dev-loop task change reaches its own test"
    (is (contains? (set (changed/affected-test-nss-by '[kmet.tasks.clean]))
                   'kmet.tasks.test-clean)))
  (testing "unrelated namespaces stay out"
    (let [nss (set (changed/affected-test-nss-by '[kmet.ai.models]))]
      (is (not (contains? nss 'kmet.tui.components.test-text)))))
  (testing "extension namespaces never enter root test selection (their tests run from inside the extension directory)"
    (let [nss (set (changed/affected-test-nss-by '[kmet.extension extensions.tools]))]
      (is (empty? (filter #(str/starts-with? (str %) "extensions.") nss)))
      (is (contains? nss 'kmet.app.test-extensions)))))

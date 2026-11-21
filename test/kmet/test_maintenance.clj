(ns kmet.test-maintenance
  "The maintenance flags' surface — `--list-models` output (pi
   cli/list-models.ts) and its token-count formatting — plus the marker
   `--version` reads to tell a checkout from a packaged image."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.ai.models :as models]
            [kmet.app.maintenance :as maintenance]
            [kmet.app.version :as version]))

(t/deftest test-format-token-count
  (t/is (= "200K" (@#'maintenance/format-token-count 200000)))
  (t/is (= "1M" (@#'maintenance/format-token-count 1000000)))
  (t/is (= "1.5M" (@#'maintenance/format-token-count 1500000)))
  (t/is (= "128K" (@#'maintenance/format-token-count 128000)))
  (t/is (= "4.1K" (@#'maintenance/format-token-count 4096)) "pi toFixed(1)"))

(defn- tmodel [provider id context max-out reasoning]
  (models/map->Model {:id id :name id :provider provider
                      :api :openai-completions :base-url "https://x"
                      :reasoning reasoning :input [:text :image]
                      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                      :context-window context :max-tokens max-out}))

(t/deftest test-list-models-table
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000000 8192 true)
                                    (tmodel :alpha "alpha-1" 128000 4096 false)
                                    (tmodel :beta "beta-1" 200000 16384 true)])]
                (maintenance/list-models nil)))]
    (t/is (str/includes? out "provider  model") "header row")
    (t/is (str/includes? out "alpha     alpha-1") "two-space aligned columns")
    (t/is (str/includes? out "1M") "context formatted")
    (t/is (str/includes? out "128K") "context formatted (K)")
    (t/is (str/includes? out "yes") "thinking flag")
    (t/is (str/includes? out "no") "non-reasoning flag")
    (t/is (str/includes? out "yes") "images flag (input includes :image)")
    ;; sorted by provider then id: alpha-1 < zeta, then beta
    (t/is (< (str/index-of out "alpha-1") (str/index-of out "zeta")))
    (t/is (< (str/index-of out "zeta") (str/index-of out "beta-1")))))

(t/deftest test-list-models-search
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000 100 true)
                                    (tmodel :beta "beta-1" 1000 100 false)])]
                (maintenance/list-models "beta")))]
    (t/is (str/includes? out "beta-1"))
    (t/is (not (str/includes? out "zeta")) "search filters"))

  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000 100 true)])]
                (maintenance/list-models "nope")))]
    (t/is (str/includes? out "No models matching \"nope\""))))

(t/deftest test-list-models-search-tokens
  ;; the local filter keeps the fuzzy matcher's case-insensitive, multi-token
  ;; subsequence behavior; only the discarded score is gone
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000 100 true)
                                    (tmodel :beta "beta-1" 1000 100 false)])]
                (maintenance/list-models "ALPHA Z")))]
    (t/is (str/includes? out "alpha     zeta"))
    (t/is (not (str/includes? out "beta-1")))))

(t/deftest test-list-models-no-available
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available (fn [] [])]
                (maintenance/list-models nil)))]
    (t/is (str/includes? out "No models available"))))

(t/deftest bootstrap-carries-the-source-marker
  ;; a stale marker path silently pins `kmet --version` to "dev" in every
  ;; source run; the marker names the process bootstrap (kmet/app/core.clj),
  ;; which every artifact carries
  (t/is (some? (io/resource @#'version/source-marker))
        "the source-run marker must name a real namespace source"))

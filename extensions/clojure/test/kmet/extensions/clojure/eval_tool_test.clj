(ns kmet.extensions.clojure.eval-tool-test
  "Tests for the clojure_eval tool wrapper: ctx forwarding, renderers and
   registration. The shared evaluation behavior is tested in
   kmet.libs.test-clj-eval."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.extensions.clojure.eval-tool :as eval-tool]
            [kmet.libs.nrepl :as nrepl]
            [kmet.libs.theme :as theme]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]))

(defn- plain
  "Render a renderer result headlessly, ANSI-stripped."
  [comp width]
  (when comp
    (mapv utils/strip-ansi-codes (core/render comp width))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Wrapper forwarding
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-cwd-is-passed-to-discovery
  (let [captured (atom nil)]
    (with-redefs [nrepl/discover-port (fn [host dir]
                                        (reset! captured [host dir])
                                        nil)]
      (eval-tool/execute {:code "(+ 1 2)"} nil nil {:cwd "/project"})
      (is (= ["127.0.0.1" "/project"] @captured)))))

(deftest ^:bb-only test-cancel-is-passed-to-eval-code
  (let [captured (atom nil)
        signal (atom false)]
    (with-redefs [nrepl/eval-code (fn [opts]
                                    (reset! captured opts)
                                    {:ok? true :values ["1"] :out "" :err ""
                                     :events [[:value "1"]] :env :clj})]
      (eval-tool/execute {:code "1" :port 7888} nil signal {:cwd "/project"})
      (is (fn? (:cancel @captured)))
      (is (false? ((:cancel @captured))))
      (reset! signal true)
      (is (true? ((:cancel @captured)))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Renderers
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-call-renderer-and-title
  (testing "label, code and port render on the call line"
    (let [lines (plain (eval-tool/render-eval-call "clojure_eval"
                                                   {:code "(+ 1 2)" :port 7888}
                                                   theme/dark-theme 60 {}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "clj eval $ (+ 1 2)"))
      (is (str/includes? (first lines) ":7888"))))
  (testing "a leading blank line is dropped from the call line"
    (let [lines (plain (eval-tool/render-eval-call "clojure_eval" {:code "\n(+ 1 2)"}
                                                   theme/dark-theme 60 {}) 60)]
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) "clj eval $ (+ 1 2)"))))
  (testing "a large multi-line payload renders in full (no head window)"
    (let [code (str/join "\n" (mapv #(str "(println " % ")") (range 20)))
          lines (plain (eval-tool/render-eval-call "clojure_eval" {:code code}
                                                   theme/dark-theme 60 {:expanded false}) 60)]
      (is (= 20 (count lines)) "every payload line renders")
      (is (str/starts-with? (first lines) "clj eval $ (println 0)"))
      (is (str/includes? (peek lines) "(println 19)") "the tail renders")
      (is (not-any? #(str/includes? % "more lines,") lines) "no collapse hint")))
  (testing "expanded renders the payload verbatim"
    (let [code (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (eval-tool/render-eval-call "clojure_eval" {:code code}
                                                   theme/dark-theme 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "the quiet title keeps the first non-blank line, shortened"
    (is (= "clj eval $ (+ 1 2)" (eval-tool/title {:code "(+ 1 2)\n(+ 3 4)"})))
    (is (= "clj eval $ (+ 1 2)" (eval-tool/title {:code "\n  \n(+ 1 2)"}))
        "a leading blank line does not blank the title")
    (let [long-title (eval-tool/title {:code (apply str (repeat 200 "x"))})]
      (is (str/ends-with? long-title "…"))
      (is (= (+ (count "clj eval $ ") 81) (count long-title))))
    (is (nil? (eval-tool/title {})))
    (is (nil? (eval-tool/title {:code "\n  \n"})) "blank code → nil (quiet falls back to the name)")))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Registration
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-the-record-opts-into-the-tail-status-line
  (testing "clojure_eval is a runner tool: :status true"
    ;; the record travels into the deferred source's tools map, so the
    ;; registration path is the source's — only the map is this module's
    (is (true? (:status (eval-tool/definition))))))

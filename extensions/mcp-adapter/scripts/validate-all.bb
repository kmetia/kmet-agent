#!/usr/bin/env bb
;; Run every mcp-adapter validation script with the classpath a direct run
;; needs (the extension's src and the repo src; org.clojure/data.json is
;; bb-bundled since 1.13.224).
;;
;; Usage: bb scripts/validate-all.bb
(require '[babashka.process :as proc])

(def cp "../../src:src")

(def runs
  [["validate-names.bb"]
   ["validate-client.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]
   ["validate-config.bb"]
   ["validate-oauth.bb" "scripts/fake-oauth-server.bb"]
   ["validate-panel.bb"]
   ["validate-script.bb" "scripts/fake-mcp-server.bb"]
   ["e2e.bb" "scripts/fake-mcp-server.bb"]
   ["validate-protocol.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]])

(def failures (atom 0))

(doseq [[script & argv] runs]
  (println (str "\n════ " script " ════"))
  (let [{:keys [exit]} (apply proc/shell {:out :inherit :err :inherit :continue true}
                              "bb" "-cp" cp (str "scripts/" script) argv)]
    (when-not (zero? exit)
      (swap! failures inc)
      (println (str "FAILED: " script " (exit " exit ")")))))

(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))

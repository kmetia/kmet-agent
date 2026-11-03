;; Live composed-run verification of tool exposure (todo.md § Tool exposure,
;; P5 leftover 2 — "a composed-app run of the same check").
;;
;; `scripts/live_tool_exposure_check.bb` proves the derivation and the wire: it
;; builds its tool table with `register-tool!` / `register-tool-source!` and
;; calls `kmet.ai.llm/send-message` directly, so the composition's rows never
;; mount. This one boots the app — `--profile print` through `bb start` — with an
;; extension installed in a scratch agent dir and mounted as a composition row
;; (`:ext/p5_probe`), and reads what each provider request declared by listening
;; for `:before-provider-request` from inside that extension.
;;
;;   round 1  the deferred source is registered, nothing loaded
;;              → the source's two schemas are absent, `tool_search` is there
;;   round 2  the model called `tool_search`, which promoted both tools
;;              → both are declared now, and everything from round 1 is still
;;                declared (a promotion adds, it does not reorder)
;;   round 3  the model called the promoted tool, nothing changed
;;              → the array is identical to round 2's, item for item
;;
;; What it also measures, and why P6's byte-stability gate needs an order of its
;; own first: a promotion lands the new schemas at map order inside the array
;; (the run that closed this leftover put them at positions 2 and 17 of 19), not
;; appended — so "unchanged up to the delta" is not yet a property the
;; derivation has.
;;
;;   bb scripts/live_tool_exposure_composed_check.bb
;;   bb scripts/live_tool_exposure_composed_check.bb commandcode deepseek/deepseek-v4.1-flash
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def root (str (fs/canonicalize ".")))
(def work (fs/path root "target/live-tool-exposure-composed"))
(def agent-dir (fs/path work "agent"))
(def capture-dir (fs/path work "captured"))
(def sessions (fs/path work "sessions"))

(def provider (keyword (or (first *command-line-args*) "github-copilot")))
(def model-id (or (second *command-line-args*) "claude-haiku-4.5"))

(def deferred-names ["cat_alpha" "cat_beta"])
(def tools-name "tool_search")

(defn- fail! [message]
  (binding [*out* *err*] (println message))
  (System/exit 1))

;; ─── The scratch install: agent dir, auth, extension, patch ──────────────

(def extension-source
  (str "(ns p5-probe\n"
       "  \"The composed-run probe: a deferred source, two direct tools, and a\n"
       "   capture of every provider request's tools array.\"\n"
       "  (:require [babashka.fs :as fs]\n"
       "            [kmet.extension :as ext]\n"
       "            [kmet.srce.core :as srce]))\n\n"
       "(def ^:private out-dir \"" capture-dir "\")\n\n"
       "(defn- stand-in [name]\n"
       "  {:name name\n"
       "   :label name\n"
       "   :description (str name \" — a stand-in tool for the exposure check.\")\n"
       "   :parameters {:type \"object\" :properties {} :required []}\n"
       "   :execute (fn [_] {:content (str name \" ok\") :is-error false})})\n\n"
       "(srce/defplugin plugin\n"
       "  {:name :ext/p5_probe\n"
       "   :apply\n"
       "   (fn [_ctx api]\n"
       "     (ext/register-tool! api (stand-in \"probe_one\"))\n"
       "     (ext/register-tool! api (stand-in \"probe_two\"))\n"
       "     (ext/register-tool-source! api ::catalog\n"
       "                                (fn []\n"
       "                                  {\"cat_alpha\" (stand-in \"cat_alpha\")\n"
       "                                   \"cat_beta\" (stand-in \"cat_beta\")})\n"
       "                                {:exposure :deferred})\n"
       "     (let [n (atom 0)]\n"
       "       (ext/on-event api :before-provider-request\n"
       "                     (fn [event _ctx]\n"
       "                       (let [i (swap! n inc)\n"
       "                             payload (:payload event)]\n"
       "                         (fs/create-dirs out-dir)\n"
       "                         (spit (fs/file out-dir (str \"request-\" i \".edn\"))\n"
       "                               (pr-str {:index i\n"
       "                                        :tools (mapv :name (:tools payload))})))\n"
       "                       ;; nil: observe only, the payload is left as it was\n"
       "                       nil)))\n"
       "     nil)})\n"))

(defn- install!
  "The scratch agent dir the run stands on: settings, a link to this machine's
   credentials, the probe extension, and the patch that mounts it as a row."
  []
  (when-not (fs/exists? (fs/path (fs/home) ".kmet" "agent" "auth.edn"))
    (fail! (str "no auth.edn in " (fs/home) "/.kmet/agent — the run needs a credential")))
  (fs/delete-tree work)
  (fs/create-dirs (fs/path agent-dir "extensions"))
  (fs/create-dirs capture-dir)
  (fs/spit (fs/path agent-dir "settings.edn")
           (pr-str {:provider provider
                    :model model-id
                    :thinking :off
                    ;; the probe must be the only extra thing mounted: the
                    ;; bundled set (mcp, review, tools) would add its own rows
                    :bundled-extensions []}))
  (fs/create-sym-link (fs/path agent-dir "auth.edn")
                      (fs/path (fs/home) ".kmet" "agent" "auth.edn"))
  (fs/spit (fs/path agent-dir "extensions" "p5_probe.clj") extension-source)
  (let [patch (fs/path work "patch.edn")]
    (fs/spit patch (pr-str {:rows [{:plugin :ext/p5_probe}] :patch []}))
    patch))

;; ─── The run ─────────────────────────────────────────────────────────────

(def ^:private prompt
  (str "Use the " tools-name " tool with the query cat. It returns deferred tools."
       " Then call the tool named cat_alpha with no arguments, and reply with the"
       " exact content cat_alpha returned."))

(defn- evolve-environment
  "The inherited environment without the harness's session bindings — a child
   that resumed the parent's session would answer from its transcript — and
   with the scratch agent dir pinned."
  []
  (-> (into {} (System/getenv))
      (dissoc "KMET_SESSION_FILE" "KMET_SESSION_ID" "KMET_MODEL" "KMET_PROVIDER"
              "KMET_REASONING_LEVEL")
      (assoc "KMET_CODING_AGENT_DIR" (str agent-dir))))

(defn- run-print! [patch]
  (let [proc (p/process {:dir root
                         :env (evolve-environment)
                         :out :string
                         :err :string}
                        "bb" "start" "--print"
                        "--patch" (str patch)
                        "--provider" (name provider)
                        "--model" model-id
                        "--session-dir" (str sessions)
                        prompt)
        result (deref proc (* 10 60 1000) ::timeout)]
    (when (= ::timeout result)
      (p/destroy-tree proc)
      (fail! "the run did not finish in ten minutes"))
    (when-not (zero? (:exit result))
      (fail! (str "the run failed (exit " (:exit result) "):\n" (:err result))))
    result))

;; ─── The verdict ─────────────────────────────────────────────────────────

(defn- requests []
  (let [files (sort-by (comp parse-long #(re-find #"\d+" (str (fs/file-name %))))
                       (fs/glob capture-dir "request-*.edn"))]
    (when (< (count files) 3)
      (fail! (str "expected three captured requests, found " (count files)
                  " — did the model call " tools-name " and cat_alpha?")))
    (mapv (fn [f] (edn/read-string (slurp (str f)))) files)))

(defn- verdict [reqs]
  (let [names (mapv :tools reqs)
        [r1 r2 r3] names
        added (remove (set r1) r2)
        positions (into {} (for [n added] [n (.indexOf ^java.util.List r2 n)]))
        ok [(not-any? (set deferred-names) r1)
            (contains? (set r1) tools-name)
            (every? (set r2) r1)
            (= (set deferred-names) (set added))
            (= r2 r3)]]
    (println "round 1 declared:" (count r1) "tools" (pr-str r1))
    (println "  deferred source contributes no schema:" (nth ok 0)
             " " tools-name "declared:" (nth ok 1))
    (println "round 2 (after" (str tools-name ") declared:") (count r2) "tools")
    (println "  everything from round 1 still declared:" (nth ok 2)
             " promoted exactly the deferred names:" (nth ok 3))
    (println "  additions:" (pr-str added) "at positions" (pr-str positions)
             "| appended would be" (count r1) "and" (inc (count r1)))
    (println "round 3 identical to round 2:" (nth ok 4))
    (println)
    (println "COMPOSED CHECK:" (if (every? true? ok) "PASS" "FAIL"))
    (every? true? ok)))

(let [patch (install!)
      result (run-print! patch)]
  (when (str/includes? (str (:out result)) "did not")
    (println "reply:" (str/trim (str (:out result)))))
  (System/exit (if (verdict (requests)) 0 1)))

(ns kmet.extensions.lsp-adapter.detect-test
  "Language detection, the builtin registry's reshaping by config, and
   workspace-root resolution (plan §7) — pure data, no processes."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.lsp-adapter.detect :as detect]
            [kmet.extensions.lsp-adapter.test-util :as tu]))

;; ─── languageId ───────────────────────────────────────────────────────────

(deftest builtin-claims-are-all-mapped
  (testing "every extension a builtin claims has a languageId entry"
    (is (every? #(contains? detect/language-ids %)
                (mapcat :extensions (detect/effective-servers {}))))))

(deftest language-id
  (testing "the extension decides the languageId"
    (is (= "clojure" (detect/language-id "/p/f.clj")))
    (is (= "typescriptreact" (detect/language-id "/p/f.tsx")))
    (is (= "ruby" (detect/language-id "/p/Gemfile.rb")))
    (is (= "cpp" (detect/language-id "/p/f.hpp"))))
  (testing "the whole clojure family maps to clojure"
    (is (every? #(= "clojure" (detect/language-id (str "/p/f." %)))
                ["clj" "cljs" "cljc" "edn" "bb"])))
  (testing "extension-less markers resolve by filename, case-insensitively"
    (is (= "cmake" (detect/language-id "/x/CMakeLists.txt")))
    (is (= "makefile" (detect/language-id "/x/Makefile")))
    (is (= "dockerfile" (detect/language-id "/x/Dockerfile"))))
  (testing "an unknown extension falls back to plaintext"
    (is (= "plaintext" (detect/language-id "/x/nope.unknownext"))))
  (testing "a path with no extension is plaintext unless it is a marker"
    (is (= "plaintext" (detect/language-id "/x/LICENSE")))))

;; ─── Effective registry ───────────────────────────────────────────────────

(deftest effective-servers-default-order
  (testing "no config means every builtin in registry order"
    (is (= ["clojure-lsp" "typescript-language-server" "pyright-langserver"
            "rust-analyzer" "gopls" "clangd" "ruby-lsp"
            "bash-language-server" "jdtls"]
           (mapv :id (detect/effective-servers {})))))
  (testing "non-map entries are ignored"
    (is (= 9 (count (detect/effective-servers {"broken" "nope"}))))))

(deftest effective-servers-reshaped-by-config
  (let [eff (detect/effective-servers
             {"jdtls" {:disabled true}
              "rust-analyzer" {:request-timeout-ms 60000}
              "clojure-lsp" {:extend-extensions ["janet"]}
              "fennel" {:command ["fennel-ls"] :extensions ["fnl"]}})
        clojure-lsp (some #(when (= "clojure-lsp" (:id %)) %) eff)
        fennel (some #(when (= "fennel" (:id %)) %) eff)]
    (testing ":disabled removes a builtin"
      (is (not-any? #(= "jdtls" %) (mapv :id eff))))
    (testing "a same-id override keeps the builtin argv and folds extensions"
      (is (= ["clojure-lsp"] (:command clojure-lsp)))
      (is (contains? (:extensions clojure-lsp) "janet"))
      (is (contains? (:extensions clojure-lsp) "clj")))
    (testing "an unknown id with a :command appends a custom server last"
      (is (= ["fennel-ls"] (:command fennel)))
      (is (= "fennel" (:id (last eff)))))
    (testing "an unknown id without a :command is ignored"
      (is (not-any? #(= "ghost" (:id %))
                    (detect/effective-servers {"ghost" {:extensions ["x"]}}))))))

(deftest effective-servers-argv-overrides
  (let [eff (detect/effective-servers
             {"gopls" {:command ["/opt/gopls"] :args ["serve" "-rpc.trace"]}
              "clangd" {:command "/usr/bin/clangd"}
              "gopls-extra" {:command "x"}})
        gopls (some #(when (= "gopls" (:id %)) %) eff)
        clangd (some #(when (= "clangd" (:id %)) %) eff)]
    (testing "a vector command appends :args"
      (is (= ["/opt/gopls" "serve" "-rpc.trace"] (:command gopls))))
    (testing "a string command becomes a one-element argv"
      (is (= ["/usr/bin/clangd"] (:command clangd))))))

;; ─── Roots ────────────────────────────────────────────────────────────────

(deftest root-walk-up
  (let [tmp (tu/temp-dir! "root-walk")
        proj (str tmp "/proj")]
    (try
      (fs/create-dirs (str proj "/nested/deep"))
      (spit (str proj "/deps.edn") "{}")
      (spit (str proj "/nested/deep/f.clj") "(+ 1 1)")
      (let [claim (first (detect/claiming (detect/effective-servers {})
                                          (str proj "/nested/deep/f.clj")
                                          proj))]
        (testing "the marker walk-up finds the project root"
          (is (= (fs/canonicalize proj) (:root claim))))
        (testing "the claim names the registry entry"
          (is (= "clojure-lsp" (:id claim)))))
      (finally (tu/delete-tree! tmp)))))

(deftest exclude-marker-hands-off
  (let [tmp (tu/temp-dir! "deno")]
    (try
      (fs/create-dirs (str tmp "/js"))
      (spit (str tmp "/deno.json") "{}")
      (spit (str tmp "/js/a.ts") "let x = 1")
      (testing "a deno project is not claimed by typescript-language-server"
        (is (empty? (detect/claiming (detect/effective-servers {})
                                     (str tmp "/js/a.ts") tmp))))
      (finally (tu/delete-tree! tmp)))))

(deftest outside-cwd-attaches-at-the-file
  (let [tmp (tu/temp-dir! "outside")
        outside (str tmp "/outside")]
    (try
      (fs/create-dirs outside)
      (spit (str outside "/a.rb") "1")
      (let [claim (first (detect/claiming (detect/effective-servers {})
                                          (str outside "/a.rb")
                                          "/definitely/not/this/cwd"))]
        (testing "a file outside cwd attaches at its own directory"
          (is (= (fs/canonicalize outside) (:root claim)))))
      (finally (tu/delete-tree! tmp)))))

(deftest rootless-and-explicit-root
  (let [tmp (tu/temp-dir! "rootless")]
    (try
      (fs/create-dirs (str tmp "/sub"))
      (spit (str tmp "/sub/run.sh") "echo hi")
      (testing "a rootless server attaches at the file's directory"
        (let [claim (first (detect/claiming (detect/effective-servers {})
                                            (str tmp "/sub/run.sh") tmp))]
          (is (= "bash-language-server" (:id claim)))
          (is (= (fs/canonicalize (str tmp "/sub")) (:root claim)))))
      (testing "a custom server's explicit :root-dir wins over the walk-up"
        (spit (str tmp "/deps.edn") "{}")
        (spit (str tmp "/sub/x.clj") "1")
        (let [eff (detect/effective-servers
                   {"mine" {:command ["mine-ls"] :extensions ["clj"]
                            :root-dir "/explicit/root"}})
              claim (some #(when (= "mine" (:id %)) %)
                          (detect/claiming eff (str tmp "/sub/x.clj") tmp))]
          (is (= (fs/canonicalize "/explicit/root") (:root claim)))))
      (finally (tu/delete-tree! tmp)))))

(deftest no-marker-falls-back-to-cwd
  (let [tmp (tu/temp-dir! "no-marker")]
    (try
      (spit (str tmp "/a.py") "1")
      (testing "a claimed file with no marker anywhere attaches at cwd"
        (let [claim (first (detect/claiming (detect/effective-servers {})
                                            (str tmp "/a.py") tmp))]
          (is (= (fs/canonicalize tmp) (:root claim)))))
      (finally (tu/delete-tree! tmp)))))

(deftest custom-server-by-filename
  (testing "a custom server can claim a whole filename"
    (let [eff (detect/effective-servers
               {"justfile" {:command ["just-lsp"] :filenames ["justfile"]}})
          claim (first (detect/claiming eff "/p/justfile" "/p"))]
      (is (= "justfile" (:id claim))))))

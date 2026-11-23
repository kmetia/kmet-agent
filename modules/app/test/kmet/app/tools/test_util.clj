(ns kmet.app.tools.test-util
  "Unit tests for the tools' shared helpers — the quiet-title path forms, the
   one-line title bodies (the quiet transcript line and the approval question),
   the shell-command display normalization, and the tool-call argument/path
   grammar shared with compaction and session recall."
  (:require [clojure.test :as t]
            [kmet.app.tools.util :as util]
            [kmet.test-utils :refer [slash]]))

(t/deftest test-strip-cwd-cd-prefix
  (let [cwd (slash "/work/project")]
    (t/testing "a cd to the working directory is dropped with its && separator"
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd " cwd " && rg foo") cwd)))
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "  cd " cwd " && rg foo") cwd))
            "leading whitespace goes with the prefix")
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd -- " cwd " && rg foo") cwd))
            "`--` passes through cd's option parsing")
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd " cwd " &&\nrg foo") cwd))
            "the newline after && belongs to the prefix")
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd " cwd " &&   rg foo") cwd))))
    (t/testing "quoted and relative spellings of the same directory resolve"
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd \"" cwd "\" && rg foo") cwd)))
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd '" cwd "' && rg foo") cwd)))
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix "cd . && rg foo" cwd)))
      (t/is (= "rg foo" (util/strip-cwd-cd-prefix (str "cd " cwd "/sub/.. && rg foo") cwd))
            "the path normalizes before it is compared"))
    (t/testing "a cd anywhere else stays visible"
      (t/is (= "cd /other && rg foo" (util/strip-cwd-cd-prefix "cd /other && rg foo" cwd))
            "another absolute directory")
      (t/is (= "cd sub && rg foo" (util/strip-cwd-cd-prefix "cd sub && rg foo" cwd))
            "a subdirectory is not the working directory")
      (t/is (= "cd .. && rg foo" (util/strip-cwd-cd-prefix "cd .. && rg foo" cwd))))
    (t/testing "only a leading cd joined by && counts"
      (t/is (= (str "cd " cwd "; rg foo") (util/strip-cwd-cd-prefix (str "cd " cwd "; rg foo") cwd))
            "`;` runs the rest even when the cd failed")
      (t/is (= "cd /other" (util/strip-cwd-cd-prefix (str "cd " cwd " && cd /other") cwd))
            "only the redundant first hop goes; a later cd is the command")
      (t/is (= "echo a && rg foo" (util/strip-cwd-cd-prefix "echo a && rg foo" cwd))
            "no leading cd")
      (t/is (= (str "cd " cwd) (util/strip-cwd-cd-prefix (str "cd " cwd) cwd))
            "a bare cd is the command")
      (t/is (= (str "cd " cwd " &&") (util/strip-cwd-cd-prefix (str "cd " cwd " &&") cwd))
            "nothing left to show after the prefix"))
    (t/testing "missing inputs pass through untouched"
      (t/is (= "cd /work && ls" (util/strip-cwd-cd-prefix "cd /work && ls" nil)))
      (t/is (= "cd /work && ls" (util/strip-cwd-cd-prefix "cd /work && ls" "")))
      (t/is (nil? (util/strip-cwd-cd-prefix nil cwd)))
      (t/is (= {:command "cd /work && ls"} (util/strip-cwd-cd-prefix {:command "cd /work && ls"} cwd))))))

;; ─── One-line call names ──────────────────────────────────────────────────

(t/deftest test-title-body
  (t/is (= "$ echo hi" (util/title-body (fn [_] "$ echo hi") {})))
  (t/is (= "echo hi && ls" (util/title-body (fn [_] "echo hi\n  &&\n ls") {}))
        "a multiline title collapses to one line (whitespace around the newline too)")
  (t/is (= "a b" (util/title-body (fn [_] "a\r\n\r\nb") {}))
        "CRLF runs collapse too")
  (t/is (= "a   b" (util/title-body (fn [_] "a\tb") {})) "tabs expand")
  (t/is (nil? (util/title-body nil {})) "a tool with no :title has no body")
  (t/is (nil? (util/title-body (fn [_] "  \n ") {})) "a blank body is no body")
  (t/is (nil? (util/title-body (fn [_] (throw (ex-info "boom" {}))) {}))
        "a throwing title degrades to no body, never to an exception")
  (t/is (nil? (util/title-body (fn [_] :not-a-string) {}))))

(t/deftest test-strings-entries
  (t/testing "entries sort by displayed name, with keywordized JSON keys restored"
    (t/is (= [{:name ":leading" :text "colon"}
              {:name "a/b" :text "slash"}
              {:name "with space" :text "space"}]
             (util/strings-entries
              {:strings {(keyword "with space") "space"
                         (keyword ":leading") "colon"
                         (keyword "a/b") "slash"}}))))
  (t/testing "string keys pass through and non-string values are skipped"
    (t/is (= [{:name "a" :text "one"}]
             (util/strings-entries {:strings {:a "one" :bad 1}}))))
  (t/testing "absent, non-map and empty strings carry no entries"
    (t/is (nil? (util/strings-entries {})))
    (t/is (nil? (util/strings-entries nil)))
    (t/is (nil? (util/strings-entries {:strings []})))
    (t/is (nil? (util/strings-entries {:strings {}})))))

(t/deftest test-tool-call-arguments
  (t/testing "arguments are a map, EDN text, or nothing"
    (t/is (= {:path "x"} (util/args-map {:arguments {:path "x"}})))
    (t/is (= {:path "x"} (util/args-map {:arguments "{:path \"x\"}"})))
    (t/is (= {} (util/args-map {:arguments "not edn"})))
    (t/is (= {} (util/args-map {}))))
  (t/testing "an argument accepts keyword and string keys"
    (t/is (= "x" (util/arg {:arguments {:path "x"}} :path)))
    (t/is (= "x" (util/arg {:arguments {"path" "x"}} :path))))
  (t/testing "the tool name is lower-cased and defaults empty"
    (t/is (= "read" (util/tool-name {:name "Read"})))
    (t/is (= "" (util/tool-name {}))))
  (t/testing "paths prefer the single path arg, then the :paths list"
    (t/is (= ["a"] (util/tool-paths {:arguments {:path "a"}})))
    (t/is (= ["a" "b"] (util/tool-paths {:arguments {:paths ["a" "" nil "b"]}})))
    (t/is (= ["a"] (util/tool-paths {:arguments {:file_path "a"}})))
    (t/is (= [] (util/tool-paths {})))))

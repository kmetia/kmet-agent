(ns kmet.app.test-session-export
  "HTML export tests (G22 /export — pi: export-html)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.session :as s]
            [kmet.app.session-export :as se]))

(def test-dir "target/test-session-export")

(t/use-fixtures :once
  (fn [f]
    (fs/delete-tree test-dir)
    (f)))

(defn- make-session
  "A session with one of every exported entry kind, in a fresh subdir."
  []
  (let [dir (str test-dir "/" (System/currentTimeMillis))]
    (s/create-session dir)))

(t/deftest test-export-escapes-html
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content [{:type :text :text "a <b> & 'c' \"d\""}]})
    (s/append-entry sess {:role :assistant :content [{:type :text :text "<script>alert(1)</script>"}]})
    (let [html (se/session->html sess)]
      (t/is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;")
            "script content escaped")
      (t/is (not (str/includes? html "<script>alert"))
            "no raw script tag")
      (t/is (str/includes? html "a &lt;b&gt; &amp; &#39;c&#39; &quot;d&quot;")))))

(t/deftest test-export-entry-kinds
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "hello"})
    (s/append-entry sess {:role :assistant :content [{:type :text :text "hi"}]
                          :thinking "let me think"
                          :tool-calls [{:name "read" :arguments {:path "x"}}]})
    (s/append-entry sess {:role :tool :content "file content" :tool-name "read"})
    (s/append-entry sess {:role :bash :command "ls -la" :output "a.txt\nb.txt" :exit-code 0})
    (s/append-entry sess {:role :compaction :summary "summed up" :first-kept-id "x"})
    (let [html (se/session->html sess)]
      (t/is (str/includes? html ">User<"))
      (t/is (str/includes? html ">Assistant<"))
      (t/is (str/includes? html "Thinking") "thinking field rendered as a details block")
      (t/is (str/includes? html ">read<") "tool call name")
      (t/is (str/includes? html "Tool Result"))
      (t/is (str/includes? html "$ ls -la") "bash command")
      (t/is (str/includes? html "[exit 0]"))
      (t/is (str/includes? html ">Compaction<"))
      (t/is (str/includes? html "summed up")))))

(def ^:private png
  "A 1x1 PNG, base64."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(t/deftest test-export-user-image-embedded
  ;; a user message image block becomes an inline base64 data-URI
  ;; <img class="message-image"> (pi: export-html message-images) — not the
  ;; old [image: mime] text placeholder
  (let [sess (make-session)]
    (s/append-entry sess {:role :user
                          :content [{:type :text :text "look:"}
                                    {:type :image :data png :mime-type "image/png"}]})
    (let [html (se/session->html sess)]
      (t/is (str/includes? html (str "src=\"data:image/png;base64," png "\""))
            "the image is embedded as a data URI")
      (t/is (str/includes? html "class=\"message-image\""))
      (t/is (not (str/includes? html "[image: image/png]"))
            "no text placeholder once the image is embedded"))))

(t/deftest test-export-tool-images-embedded
  ;; a tool entry's :images become base64 <img class="tool-image"> elements
  ;; before the output text (pi: renderResultImages)
  (let [sess (make-session)]
    (s/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                          :tool-calls [{:id "t1" :name "read" :arguments {:path "x.png"}}]})
    (s/append-entry sess {:role :tool
                          :content [{:type :tool_result :tool_use_id "t1"
                                     :content "Read image file [image/png]"}]
                          :tool-name "read" :is-error false
                          :images [{:data png :mime-type "image/png"}]})
    (let [html (se/session->html sess)
          img-pos (str/index-of html "class=\"tool-image\"")
          out-pos (str/index-of html "Read image file")]
      (t/is (str/includes? html (str "src=\"data:image/png;base64," png "\"")))
      (t/is (str/includes? html "<div class=\"tool-images\">"))
      (t/is (and img-pos out-pos (< img-pos out-pos))
            "images render before the tool output text"))))

(t/deftest test-export-tool-without-images-unchanged
  ;; a tool entry without :images renders no image markup
  (let [sess (make-session)]
    (s/append-entry sess {:role :tool :content "plain output" :tool-name "bash"})
    (let [html (se/session->html sess)]
      (t/is (not (str/includes? html "<div class=\"tool-images\">")))
      (t/is (not (str/includes? html "<img"))))))

(t/deftest test-export-malformed-image-falls-back
  ;; an image block without :data keeps the text placeholder instead of
  ;; emitting a broken <img>
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content [{:type :image :mime-type "image/png"}]})
    (let [html (se/session->html sess)]
      (t/is (str/includes? html "[image: image/png]"))
      (t/is (not (str/includes? html "<img"))))))

(t/deftest test-export-image-mime-escaped
  ;; the mime type is HTML-escaped inside the data URI (attribute breakout
  ;; guard)
  (let [sess (make-session)]
    (s/append-entry sess {:role :user
                          :content [{:type :image :data "AA"
                                     :mime-type "image/x\"onload=1"}]})
    (let [html (se/session->html sess)]
      (t/is (str/includes? html "image/x&quot;onload=1"))
      (t/is (not (str/includes? html "src=\"data:image/x\""))))))

(t/deftest test-export-skips-labels
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "q"})
    (s/append-entry sess {:role :assistant :content "a"})
    (s/set-label! sess (:id (first (s/get-branch sess))) "important")
    (let [html (se/session->html sess)]
      (t/is (not (str/includes? html ">Label<"))
            "label bookkeeping entries are not exported"))))

(t/deftest test-export-session-name-and-meta
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "q"})
    (s/append-entry sess {:role :assistant :content "a"})
    (s/append-session-info! sess "My Session")
    (let [html (se/session->html sess)]
      (t/is (str/includes? html "My Session"))
      (t/is (str/includes? html (:id (:header sess)))))))

(t/deftest test-export-to-file
  (let [sess (make-session)
        out (str test-dir "/out/session.html")]
    (s/append-entry sess {:role :user :content "q"})
    (s/append-entry sess {:role :assistant :content "a"})
    (let [written (se/export-to-html! sess {:path out})]
      (t/is (str/ends-with? written "out/session.html"))
      (t/is (fs/exists? out))
      (t/is (str/starts-with? (slurp out) "<!DOCTYPE html>")))))

(t/deftest test-export-default-path
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "q"})
    (s/append-entry sess {:role :assistant :content "a"})
    (let [path (se/default-export-path sess)]
      (t/is (str/starts-with? (fs/file-name path) "kmet-session-"))
      (t/is (str/ends-with? path ".html")))))

(t/deftest test-export-default-path-follows-the-runtime-cwd
  (t/testing "the default path resolves against the given runtime cwd — a
              session resumed/imported from another project exports there,
              not into the launch directory"
    (let [sess (make-session)]
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :assistant :content "a"})
      (let [project (str test-dir "/project")
            path (se/default-export-path sess project)]
        (t/is (= project (str (fs/parent path))))
        (t/is (str/starts-with? (fs/file-name path) "kmet-session-"))
        (t/testing "export-to-html! takes the same :cwd"
          (let [written (se/export-to-html! sess {:cwd project})]
            (t/is (= path written))
            (t/is (fs/exists? written))))))))

(t/deftest test-export-refuses-unpersisted
  ;; Lazy creation (G4): no file until the first assistant message.
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "q"})
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Nothing to export"
                            (se/export-to-html! sess)))))

(t/deftest test-export-system-prompt-and-tools
  ;; pi: exportSessionToHtml(state) embeds the system prompt, tool list,
  ;; and a stats panel (models/messages/tokens/cost).
  (let [sess (make-session)]
    (s/append-entry sess {:role :user :content "q"})
    (s/append-model-change! sess :anthropic "claude-3")
    (s/append-entry sess {:role :assistant :content "a"
                          :usage {:prompt_tokens 100 :completion_tokens 20 :cost {:total 0.01}}})
    (let [html (se/session->html sess {:system-prompt "You are kmet."
                                       :tools [{:name "read"
                                                :description "Read a file"
                                                :parameters {:type :object
                                                             :properties {:path {:type :string :description "file path"}}
                                                             :required ["path"]}}]})]
      (t/is (str/includes? html ">System Prompt<"))
      (t/is (str/includes? html "You are kmet."))
      (t/is (str/includes? html ">Available Tools<"))
      (t/is (str/includes? html ">read<"))
      (t/is (str/includes? html "(required)"))
      (t/is (str/includes? html "Models: anthropic/claude-3"))
      (t/is (str/includes? html "1 assistant"))
      (t/is (str/includes? html "↑100"))
      (t/is (str/includes? html "↓20"))
      (t/is (str/includes? html "Cost: $0.010")))))

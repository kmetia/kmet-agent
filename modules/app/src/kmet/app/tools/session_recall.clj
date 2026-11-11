(ns kmet.app.tools.session-recall
  "`session_recall`: search the live session, including entries dropped by
   compaction, and expand a summary's #N refs (pi-vcc: src/tools/recall.ts).
   The session record is passed in the tool context by the loop; the EDN
   entries are already in memory, so no file reader is needed."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.session.core :as session]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.srce.core :as srce]))

(def ^:private search-page-size 5)
(def ^:private range-page-size 20)
(def ^:private touched-page-size 5)
(def ^:private recent-count 25)
(def ^:private search-cap 50)
(def ^:private relative-floor 0.2)
(def ^:private output-cap (* 50 1024))
(def ^:private paired-result-cap 4000)
(def ^:private stop-words
  #{"a" "an" "the" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "can" "shall" "of" "in" "to" "for"
    "with" "on" "at" "from" "by" "as" "into" "through" "during"
    "before" "after" "above" "below" "between" "out" "off" "over"
    "under" "again" "further" "then" "once" "here" "there" "when"
    "where" "why" "how" "all" "both" "each" "few" "more" "most"
    "other" "some" "such" "no" "nor" "not" "only" "own" "same"
    "so" "than" "too" "very" "just" "about" "it" "its" "that"
    "this" "what" "which" "who" "whom" "these" "those"})

(defn- text-of [content]
  (cond
    (string? content) content
    (nil? content) ""
    (vector? content) (str/join "\n"
                                (keep (fn [b]
                                        (case (:type b)
                                          :text (:text b)
                                          :tool_result (text-of (:content b))
                                          nil))
                                      content))
    :else (str content)))

(defn- clip [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 (max 1 (- n 3))) "..."))))

(defn- args-map [tc]
  (let [a (:arguments tc)]
    (cond (map? a) a
          (string? a) (try (edn/read-string a) (catch Exception _ {}))
          :else {})))

(defn- arg [tc k]
  (let [a (args-map tc)]
    (or (get a k) (get a (name k)))))

(defn- tool-name [tc]
  (str/lower-case (str (or (:name tc) ""))))

(defn- tool-paths [tc]
  (let [one (some #(let [v (arg tc %)] (when (string? v) v))
                  [:path :file_path :filePath :file])
        many (arg tc :paths)]
    (cond one [one]
          (sequential? many) (vec (filter #(and (string? %) (seq %)) many))
          :else [])))

(defn- tool-calls-text [entry recall?]
  (str/join "\n"
            (keep (fn [tc]
                    (when-not (and recall? (= "session_recall" (tool-name tc)))
                      (let [a (args-map tc)]
                        (str (:name tc) "("
                             (str/join ", " (map (fn [[k v]] (str (name k) "=" v)) a))
                             ")"))))
                  (:tool-calls entry))))

(defn- render-entry [entry index]
  (let [text (case (:role entry)
               :user (text-of (:content entry))
               :assistant (str (text-of (:content entry))
                               (when (seq (:tool-calls entry)) "\n")
                               (tool-calls-text entry true))
               :tool (str "[" (:tool-name entry) "] " (text-of (:content entry)))
               :bash (str "$ " (:command entry) "\n" (:output entry))
               "")
        files (when (= :assistant (:role entry))
                (vec (distinct (mapcat tool-paths (:tool-calls entry)))))]
    {:index index
     :role (case (:role entry) :tool "tool_result" (name (:role entry)))
     :text text
     :summary (case (:role entry)
                :tool (clip text 200)
                :bash (clip text 300)
                (clip text 300))
     :files files
     :raw entry}))

(defn- rendered-entries [sess scope]
  (let [all (vec @(:entries sess))
        index-by-id (session/global-index-by-id all)
        lineage-ids (when (= scope :lineage)
                      (set (keep :id (session/get-branch sess))))]
    {:index-by-id index-by-id
     :lineage-ids lineage-ids
     :entries (->> all
                   (keep (fn [e]
                           (when (session/context-role? (:role e))
                             (when-let [i (get index-by-id (:id e))]
                               (when (or (nil? lineage-ids)
                                         (contains? lineage-ids (:id e)))
                                 (render-entry e i))))))
                   vec)}))

(defn- escape-regex [s]
  (str/replace s #"[.*+?{}()\[\]\\^$|]" "\\$&"))

(defn- looks-like-regex? [q]
  (boolean (re-find #"[|*+?{}()\[\]\\^$.]" q)))

(defn- term-re [term]
  (try (re-pattern (str "(?i)" term))
       (catch Exception _ (re-pattern (str "(?i)" (escape-regex term))))))

(defn- significant-terms [q]
  (let [terms (remove str/blank? (str/split (str/trim q) #"\s+"))
        meaningful (remove #(contains? stop-words (str/lower-case %)) terms)]
    (if (seq meaningful) meaningful terms)))

(defn- term-freq [text re]
  (count (re-seq re text)))

(defn- word-count [s]
  (count (re-seq #"[\p{L}\p{N}]+" s)))

(defn- bm25-scores [docs terms]
  (let [n (count docs)
        avg-dl (/ (max 1 (reduce + 0 (map word-count docs))) (max 1 n))
        compiled (mapv (fn [t] {:term t :re (term-re t)}) terms)
        df (into {} (map (fn [{:keys [term re]}]
                           [term (count (filter #(re-find re %) docs))])
                         compiled))
        lens (mapv word-count docs)]
    (mapv (fn [i]
            (let [doc (nth docs i)
                  dl (nth lens i)]
              (reduce (fn [score {:keys [term re]}]
                        (let [tf (term-freq doc re)]
                          (if (zero? tf)
                            score
                            (let [doc-freq (get df term 0)
                                  idf (Math/log (+ 1 (/ (- n doc-freq 0.5)
                                                        (+ doc-freq 0.5))))
                                  tf-norm (/ (* tf 2.2)
                                             (+ tf (* 1.2
                                                      (+ 0.25 (* 0.75 (/ dl avg-dl))))))]
                              (+ score (* idf tf-norm))))))
                      0.0
                      compiled)))
          (range (count docs)))))

(defn- snippet [text terms]
  (when (and (seq terms) (seq text))
    (let [lines (str/split-lines text)
          idx (first (keep-indexed (fn [i l]
                                     (when (some #(re-find (term-re %) l) terms) i))
                                   lines))]
      (when idx
        (let [start (max 0 (- idx 2))
              end (min (count lines) (+ idx 3))]
          (str/join "\n"
                    (concat (when (pos? start) [(str "...(" start " lines above)")])
                            (map (fn [l] (if (> (count l) 300)
                                           (str (subs l 0 150) "..." (subs l (- (count l) 150)))
                                           l))
                                 (subvec lines start end))
                            (when (< end (count lines))
                              [(str "...(" (- (count lines) end) " lines below)")]))))))))

(defn- cap-hits [hits]
  (let [total (count hits)]
    {:hits (vec (take search-cap hits))
     :total total
     :truncated (> total search-cap)}))

(defn- search-entries [entries query skip]
  (let [entries (if (seq skip)
                  (vec (remove #(contains? skip (:index %)) entries))
                  entries)
        raw (str/trim query)
        terms (significant-terms raw)
        regex (when (looks-like-regex? raw)
                (try (re-pattern (str "(?i)" raw)) (catch Exception _ nil)))
        regex-hits (when regex
                     (vec (keep (fn [e]
                                  (when (re-find regex (str (:role e) " " (:text e)
                                                            " " (str/join " " (:files e))))
                                    (assoc e :snippet (snippet (:text e) terms))))
                                entries)))
        regex-result (when (seq regex-hits) (cap-hits regex-hits))]
    (or regex-result
        (let [docs (mapv #(str (:role %) " " (:text %)
                               " " (str/join " " (:files %)))
                         entries)
              scores (bm25-scores docs terms)
              matched (keep-indexed (fn [i e]
                                      (when (some #(re-find (term-re %) (nth docs i))
                                                  terms)
                                        {:entry (assoc e :snippet (snippet (:text e) terms))
                                         :score (nth scores i)}))
                                    entries)
              sorted (sort-by :score > matched)
              top (some-> sorted first :score)
              distinct-terms (count (distinct (map str/lower-case terms)))
              floored (if (and top (>= distinct-terms 2))
                        (filter #(>= (:score %) (* top relative-floor)) sorted)
                        sorted)]
          (cap-hits (mapv :entry floored))))))

(defn- format-entry [e]
  (let [files (when (seq (:files e)) (str " files:[" (str/join ", " (:files e)) "]"))]
    (str "#" (:index e) " [" (:role e) "]" files " "
         (or (:snippet e) (:summary e)))))

(defn- format-entries [entries header]
  (str header "\n\n" (str/join "\n\n" (map format-entry entries))))

(defn- page-info [n page-size page]
  (let [pages (max 1 (long (Math/ceil (/ (max 1 n) page-size))))]
    {:pages pages :page (max 1 (or page 1))}))

(defn- run-search [entries query page scope skip]
  (let [{:keys [hits total truncated]} (search-entries entries query skip)
        {pages :pages page :page} (page-info (count hits) search-page-size page)]
    (cond
      (zero? (count hits))
      (str "No matches for \"" query "\" in session history.")

      (> page pages)
      (str "Page " page " is outside the available range 1-" pages
           " (" (count hits) " matches" (when (= scope :all) ", scope: all") ")"
           (when truncated (str " — showing " (count hits) " of " total " matches"))
           ". Use page:1.." pages ".")

      :else
      (let [start (* (dec page) search-page-size)
            page-hits (subvec (vec hits) start (min (count hits) (+ start search-page-size)))
            header (if (> pages 1)
                     (str "Page " page "/" pages " (" (count hits) " total matches"
                          (when (= scope :all) ", scope: all")
                          (when truncated (str " — showing " (count hits) " of " total " matches"))
                          "):")
                     (str (count hits) " matches"
                          (when (= scope :all) " (scope: all)")
                          (when truncated (str " — showing " (count hits) " of " total " matches"))
                          ":"))
            around (when-let [n (:index (first page-hits))]
                     (str "\n--- Read around a hit: range:[" (max 0 (- n 3)) ", " (+ n 3)
                          "]; full text: expand:[" n "]"
                          (when (= scope :all) " (with scope:'all')") " ---"))
            footer (when (< page pages)
                     (str "\n--- Use page:" (inc page)
                          (when (= scope :all) " with scope:'all'") " for more results ---"))]
        (str (format-entries page-hits header) around footer)))))

(defn- run-range [entries range page scope]
  (let [[from to] range]
    (if (or (not= 2 (count range))
            (not (and (integer? from) (integer? to)))
            (neg? from) (> from to))
      (str "Invalid range " (pr-str range)
           ": use range:[from, to] with two #N indices, from <= to.")
      (let [in-range (filterv #(<= from (:index %) to) entries)
            last-index (:index (peek entries))]
        (if (empty? in-range)
          (str "No messages #" from "..#" to " in session history"
               (when last-index (str " (last entry is #" last-index ")")) ".")
          (let [hi (min to (or last-index to))
                clamped (< to (or last-index to))
                {pages :pages page :page} (page-info (count in-range) range-page-size page)]
            (if (> page pages)
              (str "Page " page " is outside the available range 1-" pages
                   " (" (count in-range) " messages)")
              (let [start (* (dec page) range-page-size)
                    page-entries (subvec (vec in-range) start
                                         (min (count in-range) (+ start range-page-size)))
                    header (str "Range #" from "..#" hi
                                (when (> pages 1) (str ", page " page "/" pages))
                                " (" (count in-range) " messages"
                                (when (= scope :all) ", scope: all")
                                (when clamped (str ", #" hi " is the last entry")) ")")
                    footer (when (< page pages)
                             (str "\n--- Use range:[" from ", " hi "] page:" (inc page)
                                  (when (= scope :all) " scope:'all'") " for the next "
                                  range-page-size " ---"))]
                (str (format-entries page-entries header) footer)))))))))

(defn- tool-result-id [entry]
  (some #(when (= :tool_result (:type %)) (:tool_use_id %)) (:content entry)))

(defn- pair-summary [e]
  (let [text (:text e)]
    (if (> (count text) paired-result-cap)
      (str (subs text 0 paired-result-cap)
           "\n...[result of #" (:index e) " clipped at " paired-result-cap
           " of " (count text) " chars; expand:[" (:index e) "] for all of it]")
      text)))

(defn- tool-result-pairs [entries pos ids requested]
  (loop [i (inc pos) ids (set ids) out []]
    (if (>= i (count entries))
      out
      (let [e (nth entries i)
            raw (:raw e)
            id (tool-result-id raw)]
        (cond
          (= :assistant (:role raw)) out

          (and (= :tool (:role raw)) (contains? ids id))
          (recur (inc i) (disj ids id)
                 (if (contains? requested (:index e))
                   out
                   (conj out (assoc e :summary (pair-summary e)))))

          :else (recur (inc i) ids out))))))

(defn- run-expand [entries all-entries indices scope]
  (let [all-by-index (into {} (map (juxt :index identity) all-entries))
        invalid (remove #(contains? all-by-index %) indices)
        requested (set indices)]
    (if (seq invalid)
      (str "Cannot expand indices outside session history: " (str/join ", " invalid))
      (let [found (mapv (fn [i]
                          (let [e (get all-by-index i)
                                pos (first (keep-indexed
                                            (fn [pos x] (when (= (:index x) (:index e)) pos))
                                            all-entries))]
                            {:index i :entry e :pos pos
                             :off-path (not-any? #(= i (:index %)) entries)}))
                        indices)
            out (vec (mapcat (fn [{:keys [entry pos]}]
                               (let [ids (keep :id (:tool-calls (:raw entry)))]
                                 (into [(assoc entry :summary (:text entry))]
                                       (tool-result-pairs all-entries pos ids requested))))
                             found))
            off (keep (fn [{:keys [index off-path]}] (when off-path (str "#" index))) found)
            note (when (and (= scope :lineage) (seq off))
                   (str (str/join ", " off) " " (if (= 1 (count off)) "is" "are")
                        " not on the current conversation path (edited or retried branch).\n\n"))]
        (str (when (= scope :all) "Scope: all\n\n") note
             (format-entries out (str "Expanded " (str/join ", " (map #(str "#" %) indices)))))))))

(defn- content-bearing? [args]
  (and (seq (or (keep #(let [v (get args %)] (when (string? v) v))
                      [:path :file_path :filePath :file])
                (filter string? (:paths args))))
       (or (string? (:content args))
           (and (sequential? (:edits args)) (seq (:edits args)))
           (string? (:oldText args))
           (string? (:newText args)))))

(defn- file-touches [entries]
  (let [m (reduce (fn [m e]
                    (let [raw (:raw e)
                          tcs (when (= :assistant (:role raw)) (:tool-calls raw))]
                      (reduce (fn [m tc]
                                (let [args (args-map tc)
                                      paths (tool-paths tc)
                                      drillable (content-bearing? args)
                                      code-paths (and (string? (:code args))
                                                      (sequential? (:paths args)))]
                                  (if (or drillable code-paths)
                                    (reduce (fn [m p]
                                              (if-let [i (first (keep-indexed
                                                                 (fn [i pair]
                                                                   (when (= (first pair) p) i))
                                                                 m))]
                                                (update-in m [i 1] conj
                                                           {:index (:index e)
                                                            :tool-name (:name tc)
                                                            :drillable drillable})
                                                (conj m [p [{:index (:index e)
                                                             :tool-name (:name tc)
                                                             :drillable drillable}]])))
                                            m paths)
                                    m)))
                              m tcs)))
                  []
                  entries)]
    m))

(defn- short-path [path cwd]
  (let [p (str/replace (str path) "\\" "/")
        c (str/replace (str cwd) "\\" "/")]
    (if (str/starts-with? p (str c "/"))
      (str "." (subs p (count c)))
      (let [parts (str/split p #"/")]
        (if (> (count parts) 3)
          (str ".../" (str/join "/" (take-last 3 parts)))
          p)))))

(defn- run-touched [entries page cwd]
  (let [touched (file-touches entries)
        {pages :pages page :page} (page-info (count touched) touched-page-size page)]
    (cond
      (empty? touched)
      "No file operations found in session history."

      (> page pages)
      (str "Page " page " is outside the available range 1-" pages
           " (" (count touched) " files).")

      :else
      (let [start (* (dec page) touched-page-size)
            shown (subvec (vec touched) start
                          (min (count touched) (+ start touched-page-size)))
            header (if (> pages 1)
                     (str "Page " page "/" pages " (" (count touched) " total files)")
                     (str (count touched) " files touched"))
            lines (map (fn [[path entries]]
                         (str "  " (short-path path cwd) "    "
                              (str/join ", " (map (fn [e] (str "#" (:index e)
                                                               " (" (:tool-name e) ")"))
                                                  entries))))
                       shown)
            text (str header ":\n\n" (str/join "\n" lines)
                      (when (< page pages)
                        (str "\n\n--- Use page:" (inc page) " for more results ---")))
            write (some (fn [[path entries]]
                          (when-let [e (last (filter #(and (:drillable %)
                                                           (re-find #"(?i)write|edit|patch"
                                                                    (:tool-name %)))
                                                     entries))]
                            [path e]))
                        shown)]
        (if write
          (str text "\n--- File content at an entry: query:'#" (:index (second write))
               ":" (last (str/split (first write) #"/")) "' ---")
          text)))))

(defn- parse-drill [q]
  (when-let [[_ n path suffix] (re-matches #"^#(\d+):(.+?)(?::(full|\d+(?::\d+)?))?$" q)]
    (let [[offset limit] (when (and suffix (not= suffix "full"))
                           (mapv parse-long (str/split suffix #":")))]
      {:index (parse-long n)
       :path path
       :full (= suffix "full")
       :offset offset
       :limit limit})))

(defn- content-calls [entry]
  (let [tcs (or (:tool-calls entry)
                (when (vector? (:content entry))
                  (filter #(= :toolCall (:type %)) (:content entry))))]
    (keep (fn [tc]
            (let [args (args-map tc)]
              (when (content-bearing? args)
                {:name (:name tc)
                 :path (first (tool-paths tc))
                 :content (:content args)
                 :edits (:edits args)
                 :oldText (:oldText args)
                 :newText (:newText args)})))
          tcs)))

(defn- call-body [call]
  (cond
    (string? (:content call)) (:content call)
    (seq (:edits call)) (str/join "\n\n"
                                  (map-indexed (fn [i e]
                                                 (str "--- edit " (inc i) " ---\n"
                                                      (:oldText e "") "\n--- becomes ---\n"
                                                      (:newText e "")))
                                               (:edits call)))
    (and (:oldText call) (:newText call))
    (str "--- old ---\n" (:oldText call) "\n--- new ---\n" (:newText call))
    :else "(no file content found in tool call arguments)"))

(defn- format-call [call entry-index]
  (let [body (call-body call)
        lines (str/split-lines body)
        total (count lines)
        full? (:full call)
        offset (:offset call)
        limit (or (:limit call) 30)]
    (cond
      full?
      (if (> (count body) output-cap)
        (str "File: " (:path call) "\nTool: " (:name call) "\n\n"
             (subs body 0 output-cap)
             "\n\n... (" (- (count body) output-cap)
             " more bytes — file exceeds 50KB display limit.)")
        (str "File: " (:path call) "\nTool: " (:name call) "\n\n" body))

      offset
      (let [start (max 0 offset)
            end (min total (+ start limit))
            visible (subvec (vec lines) start end)]
        (if (empty? visible)
          (str "Offset " start " is beyond file length " total ".")
          (str "File: " (:path call) "\nTool: " (:name call)
               "\nLines " (inc start) "-" end " (of " total "):\n\n"
               (str/join "\n" visible)
               (when (< end total)
                 (str "\n\n--- Use #" entry-index ":" (:path call) ":" end
                      " or #" entry-index ":" (:path call) ":" end ":" limit
                      " for next " limit " lines, #" entry-index ":" (:path call)
                      ":full for complete ---")))))

      (> total 30)
      (str "File: " (:path call) "\nTool: " (:name call) "\n\n"
           (str/join "\n" (take 30 lines))
           "\n\n...(" (- total 30) " more lines — use #" entry-index ":"
           (:path call) ":full for complete content, or #" entry-index ":"
           (:path call) ":30 for next 30 lines)")

      :else (str "File: " (:path call) "\nTool: " (:name call) "\n\n" body))))

(defn- run-drill [all-entries target _scope]
  (let [entry (first (filter #(= (:index %) (:index target)) all-entries))]
    (if-not entry
      (str "Entry #" (:index target) " not found in session history.")
      (let [calls (content-calls (:raw entry))
            matched (if (= "file" (:path target))
                      calls
                      (filter #(str/includes? (:path %) (:path target)) calls))]
        (cond
          (empty? calls) (str "No file content found in entry #" (:index target) ".")
          (empty? matched) (str "No file content found in entry #" (:index target)
                                " for \"" (:path target) "\".")
          (> (count matched) 1)
          (str "Entry #" (:index target) " has " (count matched)
               " file operations:"
               (str/join "" (map #(str "\n  [#" (:index target) ":" (:path %) "] "
                                       (:name %) "(" (:path %) ")")
                                 matched))
               "\n\nUse #" (:index target) ":<more-specific-path> to drill into a specific file.")
          :else (format-call (assoc (first matched) :full (:full target)
                                    :offset (:offset target) :limit (:limit target))
                             (:index target)))))))

(defn- to-index [v]
  (cond
    (integer? v) v
    (string? v) (when-let [[_ n] (re-matches #"\s*#?(\d+)\s*" v)] (parse-long n))
    :else nil))

(defn- index-list [v]
  (cond
    (sequential? v) (vec (keep to-index v))
    (string? v) (let [t (str/replace (str/trim v) #"^\[|\]$" "")]
                  (when (re-matches #"\s*#?\d+(\s*[,\s]\s*#?\d+)*\s*" t)
                    (mapv parse-long (re-seq #"\d+" t))))
    :else (when-let [n (to-index v)] [n])))

(defn- range-pair [v]
  (cond
    (sequential? v) (vec (map to-index v))
    (string? v) (when-let [[_ a b] (re-matches
                                    #"\[?\s*#?(\d+)\s*(?:(?:-|\.\.|,|to)\s*#?(\d+)\s*)?\]?"
                                    (str/trim v))]
                  [(parse-long a) (parse-long (or b a))])
    :else (when-let [n (to-index v)] [n n])))

(defn normalize-params [raw]
  (if-not (map? raw)
    {}
    (let [known #{"query" "range" "expand" "page" "scope" "mode"}
          dropped (atom [])
          drop! (fn [k why] (swap! dropped conj (str k " (" why ")")))
          p (reduce-kv (fn [p k v]
                         (let [k (name k)]
                           (if (contains? known k)
                             (assoc p (keyword k) v)
                             (do (drop! k "not a session_recall param") p))))
                       {} raw)
          p (if-let [n (to-index (:page p))]
              (if (>= n 1) (assoc p :page n) (do (drop! "page" "not a page number") (dissoc p :page)))
              (dissoc p :page))
          p (if (contains? p :expand)
              (let [xs (index-list (:expand p))]
                (if (seq xs) (assoc p :expand xs)
                    (do (drop! "expand" "not #N indices") (dissoc p :expand))))
              p)
          p (if (contains? p :range)
              (let [pair (range-pair (:range p))]
                (if (and (= 2 (count pair)) (every? some? pair))
                  (let [[a b] pair] (assoc p :range (if (<= a b) [a b] [b a])))
                  (do (drop! "range" "not [from, to]") (dissoc p :range))))
              p)
          p (update p :scope #(when % (str/lower-case (str/trim (str %)))))
          p (update p :mode #(when % (str/lower-case (str/trim (str %)))))
          p (if (and (#{"all" "lineage"} (:mode p)) (nil? (:scope p)))
              (-> p (assoc :scope (:mode p)) (dissoc :mode))
              p)
          p (if (and (:scope p) (not (#{"all" "lineage"} (:scope p))))
              (do (drop! "scope" "use 'lineage' or 'all'") (dissoc p :scope)) p)
          p (if (and (:mode p) (not (#{"hybrid" "touched"} (:mode p))))
              (do (drop! "mode" "use 'touched' or leave it out") (dissoc p :mode)) p)
          p (if (and (:query p) (not (string? (:query p))))
              (do (drop! "query" "not text") (dissoc p :query)) p)]
      (cond-> p (seq @dropped) (assoc :_vcc-dropped @dropped)))))

(defn- parse-request [params]
  (let [scope (if (= "all" (:scope params)) :all :lineage)
        q (some-> (:query params) str/trim not-empty)
        indices (vec (distinct (:expand params)))
        touched? (= "touched" (:mode params))
        page (max 1 (or (:page params) 1))
        set {:query (boolean q) :expand (seq indices) :range (seq (:range params))
             :page (> page 1) :touched touched?}
        drill (when q (parse-drill q))
        used (cond drill #{:query}
                   touched? #{:touched :page}
                   (seq indices) #{:expand}
                   (:range params) #{:range :page}
                   q #{:query :page}
                   :else #{})
        ignored (keep (fn [[k v]] (when (and v (not (contains? used k)))
                                    (if (= k :touched) "mode" (name k))))
                      set)
        dropped (:_vcc-dropped params)
        action (cond drill {:kind :drill :target drill}
                     touched? {:kind :touched :page page}
                     (seq indices) {:kind :expand :indices indices}
                     (:range params) {:kind :range :range (:range params) :page page}
                     q {:kind :search :query q :page page}
                     :else {:kind :recent})]
    {:scope scope :action action
     :ignored (vec ignored)
     :dropped (vec dropped)}))

(defn- ignored-note [{:keys [ignored dropped action]}]
  (str (when (seq dropped) (str "Ignored: " (str/join ", " dropped) ".\n\n"))
       (when (seq ignored)
         (str "Ignored: " (str/join ", " ignored)
              " (" (case (:kind action)
                     :drill "a #N:path query runs alone"
                     :touched "mode:'touched' runs alone"
                     :expand "expand runs alone; send the rest in a separate call"
                     :range "range runs alone; send the rest in a separate call"
                     :recent "page applies to query or range results"
                     "") ").\n\n"))))

(defn- current-turn-indices [sess index-by-id]
  (let [branch (vec (session/get-branch sess))
        last-user (last (keep-indexed (fn [i e] (when (= :user (:role e)) i)) branch))]
    (if (nil? last-user)
      #{}
      (let [after (subvec branch (inc last-user))]
        (if (or (empty? after)
                (some #(= :compaction (:role %)) after))
          #{}
          (set (keep #(get index-by-id (:id %)) (subvec branch last-user))))))))

(defn- cap-output [text]
  (if (<= (count text) output-cap)
    text
    (str "Warning: output clipped at " output-cap " of " (count text)
         " chars (one recall result is capped at 50 KB). Ask for less at once: "
         "fewer expand indices, a narrower query, or a line range for file "
         "content ('#N:path:offset:limit').\n\n"
         (subs text 0 output-cap) "\n...[clipped at 50 KB]")))

(defn- off-path-note []
  "Nothing on the current conversation path; showing edited or retried branches (scope:'all').\n\n")

(defn- run-action [sess {:keys [scope action]}]
  (let [{:keys [entries index-by-id]} (rendered-entries sess scope)
        all (rendered-entries sess :all)]
    (case (:kind action)
      :drill
      (let [target (:target action)
            on-path? (some #(= (:index %) (:index target)) entries)]
        (str (when-not on-path? (off-path-note))
             (run-drill (:entries all) target scope)))

      :touched
      (str (when (= scope :all) "Scope: all\n\n")
           (run-touched entries (:page action) (or (session/session-cwd sess)
                                                   (str (fs/cwd)))))

      :expand
      (let [text (run-expand entries (:entries all) (:indices action) scope)]
        (if (and (= scope :lineage) (str/starts-with? text "Cannot expand"))
          (str (off-path-note) (run-expand (:entries all) (:entries all)
                                           (:indices action) :all))
          text))

      :range
      (let [text (run-range entries (:range action) (:page action) scope)]
        (if (and (= scope :lineage) (str/starts-with? text "No messages"))
          (let [wide (run-range (:entries all) (:range action) (:page action) :all)]
            (if (str/starts-with? wide "No messages") text (str (off-path-note) wide)))
          text))

      :search
      (let [skip (current-turn-indices sess index-by-id)
            text (run-search entries (:query action) (:page action) scope skip)
            text (if (and (= scope :lineage) (str/starts-with? text "No matches"))
                   (let [wide (run-search (:entries all) (:query action) (:page action) :all
                                          (current-turn-indices sess
                                                                (:index-by-id
                                                                 (rendered-entries sess :all))))]
                     (if (str/starts-with? wide "No matches") text (str (off-path-note) wide)))
                   text)
            in-turn (when (and (seq skip) (str/starts-with? text "No matches"))
                      (count (filter #(contains? skip (:index %))
                                     (:hits (search-entries entries (:query action) nil)))))]
        (if (pos? (or in-turn 0))
          (str "No earlier matches for \"" (:query action) "\"; its " in-turn
               " match" (when (not= 1 in-turn) "es") " are in the current turn, "
               "which is already in your context.")
          text))

      :recent
      (if (empty? entries)
        "No entries in session history."
        (str (when (= scope :all) "Scope: all\n\n")
             (format-entries (vec (take-last recent-count entries)) "Session history:"))))))

(defn run-recall [sess params]
  (let [request (parse-request params)]
    (cap-output (str (ignored-note request) (run-action sess request)))))

(defn- execute [_args _on-update _signal ctx]
  (if-let [sess (:session-record ctx)]
    {:content (run-recall sess _args) :is-error false}
    {:content "No session file available." :is-error false}))

(def definition
  (tool/make-tool
   :name "session_recall"
   :label "Session Recall"
   :description (str/join "\n"
                          ["Recall earlier parts of this session, including anything dropped by compaction."
                           "Use it before saying the context is gone."
                           ""
                           "Pick one per call:"
                           "- query: keyword search (regex works), 5 results per page."
                           "- range: [from, to]: entries #from..#to in order, 20 per page."
                           "- expand: [N, ...]: full text of those entries."
                           "- mode: 'touched': files worked on, with entry indices."
                           "- query '#N:path': file content from entry N ('#N:path:full' for all lines)."
                           ""
                           "Add to any of them: page: next page; scope: 'lineage' (default) or 'all'."
                           "#N is the number shown in summary refs like (#12) and in recall results."
                           "Only the current session is searchable. One result is capped at 50 KB."])
   :prompt-snippet (str "Recall earlier parts of this session before saying the context is gone. "
                        "One per call: query (search), range:[from, to], expand:[N], mode:'touched'. "
                        "#N is the number in summary refs like (#12).")
   :parameters {:type "object"
                :properties {"query" {:type "string" :description "Plain keywords, regex, or '#N:path'."}
                             "range" {:type "array" :items {:type "number"}
                                      :description "[from, to]: inclusive #N range."}
                             "expand" {:type "array" :items {:type "number"}
                                       :description "#N indices to return full content for."}
                             "page" {:type "number" :description "1-based page for query, range or touched."}
                             "scope" {:type "string" :description "'lineage' (default) or 'all'."}
                             "mode" {:type "string" :description "'touched' or leave it out."}}
                :required []}
   :prepare-arguments normalize-params
   :execute execute
   :contextual? true))

(srce/defplugin plugin
  {:name :tool/session_recall
   :inject [:tools]
   :apply (fn [ctx config] (registry/mount-tool! ctx definition config))})


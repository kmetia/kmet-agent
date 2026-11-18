(ns kmet.app.eval.results
  "The per-session result ring behind the sandbox's `result` verb: every
   call's pre-truncation body is kept newest-last in a bounded vector, listed
   and read by `result-command`, and not stored back when the call used it.
   ENV supplies `:results`, `:result-next` and `:skip-result-store?`."
  (:require [clojure.string :as str]
            [kmet.app.eval.env :as env]))

(def ^:private result-ring-size
  "How many recent eval results a session keeps for (eval/result)."
  8)

(def ^:private result-read-default-chars
  "How much of a stored result (eval/result) returns by default. Kept
   below the tool's 16 KiB default so a map of escaped text still fits."
  6000)

(def ^:private result-read-max-chars
  "The most (eval/result) returns in one slice."
  50000)

(defn- result-head
  "The first line of a stored result, clipped for a result list."
  [content]
  (let [line (str/trim (or (first (str/split-lines content)) ""))]
    (if (> (count line) 100)
      (str (subs line 0 100) "…")
      line)))

(defn store-result!
  "Keep RESULT in ENV's result ring and return its id. The content is the
   full pre-truncation body; the ring is bounded and oldest-first."
  [env result]
  (let [id (swap! (:result-next env) inc)]
    (swap! (:results env)
           (fn [results]
             (let [results (conj (vec results) (assoc result :id id))]
               (subvec results (max 0 (- (count results) result-ring-size))))))
    id))

(defn- result-list
  "The compact (eval/result) listing, newest first."
  [env]
  (->> @(:results env)
       reverse
       (mapv (fn [{:keys [id content status error? elapsed-ms chars lines truncated?]}]
               (cond-> {:id id :status status :chars chars :lines lines
                        :truncated? truncated? :head (result-head content)}
                 error? (assoc :error? true)
                 elapsed-ms (assoc :elapsed-ms elapsed-ms))))))

(defn- result-read
  "Read one stored result, or a slice of it. OPTS is {:offset N :limit N}."
  [env id & [opts]]
  (let [entry (first (filter #(= id (:id %)) @(:results env)))]
    (when-not entry
      (env/bad-request! (str "no eval result " id "; (result) lists them")
                        {:id id}))
    (let [content (:content entry)
          total (count content)
          offset (max 0 (long (or (:offset opts) 0)))
          limit (max 1 (min result-read-max-chars
                            (long (or (:limit opts) result-read-default-chars))))
          start (min offset total)
          end (min total (+ start limit))]
      (cond-> {:id id :status (:status entry) :chars total :offset start
               :content (subs content start end)}
        (:error? entry) (assoc :error? true)
        (:truncated? entry) (assoc :truncated? true)
        (< end total) (assoc :next-offset end)))))

(defn- latest-result-id
  "The newest stored result id, or nil when the ring is empty."
  [env]
  (:id (peek @(:results env))))

(defn- resolve-result-id
  "Resolve :last, -1, or \"last\" to ENV's newest result id."
  [env id]
  (if (or (= id :last)
          (= id -1)
          (and (string? id) (= "last" (str/lower-case id))))
    (latest-result-id env)
    id))

(defn- result-command
  "The (eval/result) command: no args lists; ID reads (:last, -1 or
   \"last\" reads the newest); the remaining args are an opts map or
   keyword pairs (:offset N :limit M). A call using (eval/result) is not
   stored back into the ring, so slicing a large result cannot evict it."
  [env & args]
  (reset! (:skip-result-store? env) true)
  (case (count args)
    0 (result-list env)
    1 (let [id (resolve-result-id env (first args))]
        (when (nil? id)
          (env/bad-request! "no eval results yet" {:id (first args)}))
        (result-read env id))
    (let [[raw-id & more] args
          id (resolve-result-id env raw-id)
          opts (cond
                 (and (= 1 (count more)) (map? (first more))) (first more)
                 (and (= 1 (count more)) (nil? (first more))) {}
                 (odd? (count more))
                 (env/bad-request! "result needs an opts map or keyword pairs"
                                   {:args (vec args)})
                 :else (apply hash-map more))]
      (when (nil? id)
        (env/bad-request! "no eval results yet" {:id raw-id}))
      (result-read env id opts))))

(defn result-verb
  "List recent eval results, or read one result or slice (ID may be :last; :offset N :limit M or a map); a call using it is not stored."
  {:arglists '([] [id] [id {:keys [offset limit]}]
                  [id & {:keys [offset limit]}])}
  [env-ref]
  (fn [& args] (apply result-command @env-ref args)))

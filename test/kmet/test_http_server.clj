(ns kmet.test-http-server
  "A one-shot local HTTP server for tests: no external network, no extra deps."
  (:require [clojure.string :as str]))

(defn respond
  "Write an HTTP/1.1 response to socket S."
  [s status body hdrs]
  (let [b (.getBytes body)
        h (apply str (map (fn [[k v]] (str k ": " v "\r\n")) hdrs))
        head (str "HTTP/1.1 " status " X\r\n" h
                  "Content-Length: " (count b) "\r\n\r\n")]
    (.write (.getOutputStream s) (.getBytes head))
    (.write (.getOutputStream s) b)
    (.flush (.getOutputStream s))))

(defn read-request
  "Read [req-line headers-map body-reader] off socket S: the request line,
   the headers, and the SAME BufferedReader that consumed the header block
   (a fresh reader on the raw stream would lose buffered body bytes)."
  [s]
  (let [rdr (java.io.BufferedReader.
             (java.io.InputStreamReader. (.getInputStream s)))
        req-line (.readLine rdr)]
    (loop [m {}]
      (let [l (.readLine rdr)]
        (if (seq (or l ""))
          (recur (if-let [[_ k v] (re-matches #"^([^:]+):\s*(.*)" l)]
                   (assoc m (str/lower-case k) v)
                   m))
          [req-line m rdr])))))

(defn start-server
  "A one-shot test server: each connection is handled by HANDLER (fn [s
   req-line headers rdr]) and closed — RDR is the BufferedReader that
   consumed the header block (read the body from it). Returns
   [base-url close-fn]."
  [handler]
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (loop []
                       (let [s (.accept ss)
                             [req-line headers rdr] (read-request s)]
                         (handler s req-line headers rdr)
                         (try (.close s) (catch Exception _ nil)))
                       (when-not (.isClosed ss) (recur)))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))]
    [(str "http://127.0.0.1:" port)
     (fn [] (try (.close ss) (catch Exception _ nil)))]))

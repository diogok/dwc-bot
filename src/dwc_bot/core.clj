(ns dwc-bot.core
  (:require [clojure.java.jdbc :refer :all])
  (:require [clojure.data.json :as json])
  (:require [clojure.data.xml :as xml])
  (:require [dwc-io.archive :as dwca]
            [dwc-io.validation :as valid]
            [dwc-io.fixes :as fixes])
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c])
  (:require [batcher.core :refer :all])
  (:require [clojure.core.async :refer [<! <!! >! >!! go go-loop chan close!]])
  (:require [clojure.java.io :as io])
  (:require [taoensso.timbre :as log])
  (:require [environ.core :refer (env)]))

(defn wat
  [stuff] 
  (log/info stuff)
  stuff)

(declare conn)

(def metafields ["identifier" "source" "hash" "timestamp"])

(def base-inputs
  [
    "http://ipt.jbrj.gov.br/jbrj/"
    "http://ipt.jbrj.gov.br/reflora/"
    "http://ipt1.cria.org.br/ipt/"
   ])

(def fields
  (sort
    (filter 
      #(not (some #{"order" "references" "group"} [%]))
       (distinct valid/all-fields))))

(defn in-f-0
  [f in]
  (str  " " (name f) " in (" (apply str (interpose "," (map #(if (string? %) (str "\"" % "\"") %) (map f in)))) ") "))

(defn in-f
  [f in]
  (str " " (name f) " MATCH '"
    (apply str
      (interpose " OR " (map f in)))
       "'"))

(defn connect
  []
  (let [db-path   (str (or (env :data-dir) "data") "/dwc.db")
        db-file   (io/file db-path)
        db-folder (io/file (.getParent db-file))]
      (log/info "Using" db-path)
      (when-not (.exists db-folder) (.mkdir db-folder))
      (def conn {:classname "org.sqlite.JDBC" :subprotocol "sqlite" :subname (.getAbsolutePath db-file)})
      (when-not (.exists db-file)
        (do
          (execute! conn 
            [(str "CREATE VIRTUAL TABLE occurrences USING fts4(" (apply str (interpose " , " (apply conj fields metafields))) ")")])
          (execute! conn
            ["CREATE TABLE input (url)"])
          (execute! conn
            ["CREATE TABLE resources (dwca,link,pub,title)"])
          (execute! conn
            ["CREATE TABLE output (url)"])
          ))))

(defn put-output
  [url] 
   (let [url (if (.endsWith url "/") url (str url "/"))]
     (insert! conn :output {:url url})))

(defn rm-output
  [url]
  (delete! conn :output ["url=?" url])
  (delete! conn :output ["url=?" (str url "/")]))

(defn get-outputs
  [] 
  (distinct (query conn ["SELECT url FROM output;"] :row-fn :url)))

(defn put-input
  [url] 
   (let [url (if (.endsWith url "/") url (str url "/"))]
     (insert! conn :input {:url url})))

(defn rm-input
  [url]
  (delete! conn :input ["url=?" url])
  (delete! conn :input ["url=?" (str url "/")]))

(defn get-inputs
  [] 
  (distinct
    (map
      (fn [url]
        (-> url 
            (.trim)
            (str "/rss.do")
            (.replace "//rss" "/rss")))
      (query conn ["SELECT url FROM input;"] :row-fn :url))))

(defn get-tag-value
  [el tag] 
   (first
     (:content
       (first
         (filter
           #(= (:tag %) tag)
           (:content el))))))

(defn parse-date
  [date]
  (f/parse (f/with-locale (f/formatters :rfc822) (java.util.Locale. "en")) date))

(defn date-to-timestamp
  [date]
   (String/valueOf (c/to-long (parse-date date))))

(defn item-to-resource
  [item] 
  {:title (get-tag-value item :title)
   :link  (get-tag-value item :link)
   :pub   (get-tag-value item :pubDate)
   :dwca  (str (get-tag-value item :dwca) 
               "&timestamp=" 
               (date-to-timestamp (get-tag-value item :pubDate)))})

(defn get-resources
  [source] 
  (let [rss (xml/parse (io/reader source))]
    (->> rss
      (:content)
      (first)
      (:content)
      (filter #(= (:tag %) :item))
      (map item-to-resource))))

(defn all-resources
  [] (flatten (map get-resources (get-inputs))))

(defn db-resources
  []
   (reduce
     (fn [recs rec]
       (assoc recs (:link rec) rec))
     {}
     (query conn ["SELECT * FROM resources"])))

(defn changed-resource?
  [on-db rec]
   (if-let [db-rec (on-db (:link rec))]
     (let [db-time (parse-date (:pub db-rec))
           rec-time (parse-date (:pub rec))]
       (t/after? rec-time db-time))
     true))

(defn changed-resources
  [] 
   (let [on-db    (db-resources)
         all-recs (all-resources)]
     (filter (partial changed-resource? on-db) all-recs)))

(defn put-resources
  [recs] 
 (log/info "New resources")
 (doseq [r recs] (log/info r))
 (with-db-connection [cc conn]
   (execute! cc ["PRAGMA synchronous = OFF"])
   (query    cc ["PRAGMA journal_mode = WAL"])
   (try
     (with-db-transaction [c cc]
       (delete! c :resources [(in-f-0 :link recs)])
       (apply insert! c :resources recs))
     (catch Exception e (log/warn "Problem inserting resources" e)))))

(defn estimate
  [resource] 
  (let [page (slurp (:link resource))
        match (re-find #"([1-9]+) ([KM])B" page)]
    (condp = (last match)
      "K" (* (Integer/valueOf (second match)) 1024)
      "M" (* (Integer/valueOf (second match)) 1024 1024)
      (second match))))

(defn now
  [] (int (/ (System/currentTimeMillis) 1000)))

(defn hashe
  [occ] 
   (assoc occ :hash (String/valueOf (hash occ))))

(defn metadata
  [occ src pre-hash] 
  (assoc occ :identifier (hash (str src "#" (:occurrenceID occ)))
             :timestamp (now)
             :hash pre-hash
             :source src))

(defn fix
  [src occ] 
  (let [pre-hash (:hash occ)]
    (-> occ
      (fixes/-fix->)
      (metadata src pre-hash)
      (dissoc :order)
      (dissoc :references)
      (dissoc :group))))

(defn find-hashes-0
  [c occs] 
   (query c [(str "SELECT hash FROM occurrences WHERE " (in-f :hash occs))] :row-fn :hash))

(defn find-hashes
  [c occs] 
   (set (flatten (map (partial find-hashes-0 c) (partition-all 10 occs)))))

(defn find-ids-0
  [c occs] 
   (query c [(str "SELECT identifier FROM occurrences WHERE " (in-f :identifier occs))] :row-fn :identifier))

(defn find-ids
  [c occs]
  (set (flatten (map (partial find-ids-0 c) (partition-all 10 occs)))))

(defn bulk-insert
  [src occs]
   (with-db-connection [cc conn]
     (log/info "Got" (count occs) "from" src)
     (execute! cc ["PRAGMA synchronous = OFF"])
     (query    cc ["PRAGMA journal_mode = WAL"])
     (try
       (with-db-transaction [c cc]
         (time
           (let [occs (map hashe occs)

                 hashes-found (find-hashes c occs)

                 new-occs (filter (fn [o] (nil? (hashes-found (:hash o)))) occs)
                 new-occs (map (partial fix src) new-occs)

                 ids-found (find-ids c new-occs)

                 to-del-ids (filter (fn [o] (not (nil? (ids-found (:identifier o))))) new-occs)]
             (log/info (count hashes-found) "not changed," (count to-del-ids) "changed and" (- (count new-occs) (count to-del-ids)) "new.")
             (when-not (empty? to-del-ids)
               (delete! c :occurrences [(in-f :identifier to-del-ids)]))
             (when-not (empty? new-occs)
               (apply insert! c :occurrences new-occs)))))
         (catch Exception e (log/warn e)))))

(defn search
  [q start] 
  (query conn
     ["SELECT * FROM occurrences WHERE occurrences MATCH ? LIMIT 5000 OFFSET ?" q  start]
     :row-fn fixes/fix-nils))

(defn run
  [source]
   (log/info "->" source)
   (let [waiter (chan 1)
         batch  (batcher {:size (* 1 1024)
                          :fn (partial bulk-insert source)
                          :end waiter})]
     (dwca/read-archive-stream source
       (fn [occ] (>!! batch occ)))
     (close! batch)
     (<!! waiter)))

(defn start [ & args ] 
   (connect)
   (doseq [url base-inputs]
     (put-input url))
   (let [status (atom :idle)]
     (future
       (while
         (and (not (nil? @status)) (not (= :stop @status)))
         (do
           (log/info "Bot Active")
           (swap! status (fn [_] :active))
           (let [recs  (changed-resources)]
             (log/info "Got" (count recs) "resources")
             (if (not (empty? recs)) (put-resources recs))
             (doseq [rec recs]
               (log/info "Resource" rec)
               (try
                 (when (and (= :active @status) (dwca/occurrences? (:dwca rec)))
                   (run (:dwca rec)))
                 (catch Exception e (log/warn "Exception runing" rec e)))))
           (swap! status (fn [_] :idle))
           (log/info "Will rest.")
           (Thread/sleep (* 30 60 1000)))))
     status))


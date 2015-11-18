(ns dwc-bot.core
  (:require [clojure.java.jdbc :refer :all])
  (:require [clojure.data.json :as json])
  (:require [clojure.data.xml :as xml])
  (:require [dwc-io.archive :as dwca]
            [dwc-io.validation :as valid]
            [dwc-io.fixes :as fixes])
  (:require [clj-time.core :as t]
            [clj-time.format :as f])
  (:require [batcher.core :refer :all])
  (:require [clojure.core.async :refer [<! <!! >! >!! go go-loop chan close!]])
  (:require [clojure.java.io :as io])
  (:require [environ.core :refer (env)]))

(declare conn)

(def metafields ["identifier" "source" "hash"])

(def fields
  (sort
    (filter 
      #(not (some #{"order" "references" "group"} [%]))
       (distinct valid/all-fields))))

(defn in-f
  [f in]
  (str  " "(name f) " in (" (apply str (interpose "," (map #(if (string? %) (str "\"" % "\"") %) (map f in)))) ") "))

(defn connect
  []
  (let [db-path   (str (or (env "DATA_DIR") "data") "/dwc.db")
        db-file   (io/file db-path)
        db-folder (io/file (.getParent db-file))
        create    (not (.exists db-file))]
      (println "Using" db-path)
      (if (not (.exists db-folder)) (.mkdir db-folder))
      (def conn {:classname "org.sqlite.JDBC" :subprotocol "sqlite" :subname (.getAbsolutePath db-file)})
      (if create
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
  (distinct
    (map
      :url
      (query conn ["SELECT url FROM output;"]))))

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
      (fn [row]
        (-> row
            :url
            (.trim)
            (str "/rss.do")
            (.replace "//rss" "/rss")))
      (query conn ["SELECT url FROM input;"]))))

(defn get-tag-value
  [el tag] 
   (first
     (:content
       (first
         (filter
           #(= (:tag %) tag)
           (:content el))))))

(defn item-to-resource
  [item] 
  {:title (get-tag-value item :title)
   :link  (get-tag-value item :link)
   :dwca  (get-tag-value item :dwca)
   :pub   (get-tag-value item :pubDate)})

(defn get-resources
  [source] 
  (let [rss (xml/parse (io/reader source))]
    (->> rss
      (:content)
      (first)
      (:content)
      (filter #(= (:tag %) :item))
      (map item-to-resource))))

(defn parse-date
  [date]
  (f/parse (f/with-locale (f/formatters :rfc822) (java.util.Locale. "en")) date))

(defn all-resources
  [] (flatten (map get-resources (get-inputs))))

(defn changed-resources
  [] 
   (let [on-db (reduce (fn [recs rec] (assoc recs (:link rec) rec)) {} (query conn ["SELECT * FROM resources"]))
         all-recs (all-resources)]
     (filter 
       (fn [rec]
           (if-let [db-rec (on-db (:link rec))]
             (let [db-time (parse-date (:pub db-rec))
                   rec-time (parse-date (:pub rec))]
               (t/after? rec-time db-time))
             true))
       all-recs)))

(defn put-resources
  [recs] 
   (delete! conn :resources [(in-f :link recs)])
   (apply insert! conn :resources recs))

(defn estimate
  [resource] 
  (let [page (slurp (:link resource))
        match (re-find #"([1-9]+) ([KM])B" page)]
    (condp = (last match)
      "K" (* (Integer/valueOf (second match)) 1024)
      "M" (* (Integer/valueOf (second match)) 1024 1024)
      (second match))))

(defn metadata
  [occ src] 
  (assoc occ :identifier (str src "#" (:occurrenceID occ))
             :source src
             :hash (hash occ)))

(defn now
  [] (int (/ (System/currentTimeMillis) 1000)))

(defn fix
  [src occ] 
  (-> occ
    fixes/-fix->
    (dissoc :order)
    (dissoc :references)
    (dissoc :group)
    (metadata src)))

(defn wat
  [stuff] 
  (println stuff)
  stuff)

(defn bulk-insert
  [src occs]
   (with-db-connection [cc conn]
     (println "Got" (count occs) "from" src)
     (execute! cc ["PRAGMA synchronous = OFF"])
     (query    cc ["PRAGMA journal_mode = WAL"])
     (with-db-transaction [c cc]
       (time
         (let [occs (map (partial fix src) occs)

               got-hash  (set (map :hash (query c [(str "SELECT hash FROM occurrences WHERE " (in-f :hash occs))])))
               got-ids   (set (map :identifier (query c [(str "SELECT identifier FROM occurrences WHERE " (in-f :identifier occs))])) )

               to-del-hash (filter #(not (nil? (got-hash (:hash %)))) occs)
               to-del-ids (filter #(not (nil? (got-ids (:identifier %)))) occs)]
           (if (not (empty? to-del-hash))
             (delete! c :occurrences [(in-f :hash to-del-hash)]))
           (if (not (empty? to-del-ids))
             (delete! c :occurrences [(in-f :identifier to-del-hash)]))
           (apply insert! c :occurrences occs))))))

(defn search
  [q] 
  (map 
    #(dissoc % :hash)
    (query conn ["SELECT * FROM occurrences WHERE occurrences MATCH ?" q])))

(defn run
  [source]
   (println "->" source)
   (let [wait  (chan 1)
         batch (batcher {:time 0 
                         :size 1024
                         :fn (partial bulk-insert source)
                         :end wait})]
     (dwca/read-archive-stream source
       (fn [occ] (>!! batch occ)))
     (close! batch)
     (<!! wait)))

(defn start [ & args ] 
   (connect)
   (let [recs  (changed-resources)
         dwcas (take 1 (reverse (take 3 (map :dwca recs))))
         links (filter dwca/occurrences? dwcas)]
     (doseq [link links]
       (run link))))


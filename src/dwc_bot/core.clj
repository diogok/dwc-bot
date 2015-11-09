(ns dwc-bot.core
  (:require [clojure.java.jdbc :refer :all])
  (:require [clojure.data.json :as json])
  (:require [clojure.data.xml :as xml])
  (:require [dwc-io.archive :as dwca]
            [dwc-io.validation :as valid]
            [dwc-io.fixes :as fixes])
  (:require [batcher.core :refer :all])
  (:require [clojure.core.async :refer [<! <!! >! >!! go go-loop chan close!]])
  (:require [clojure.java.io :as io]))

(declare conn)

(def fields
  (sort
    (filter 
      #(not (some #{"order" "references" "group"} [%]))
       (distinct valid/all-fields))))

(defn connect
  [] 
  (let [db-path   "data/db.db"
        db-file   (io/file db-path)
        db-folder (io/file (.getParent db-file))
        create    (not (.exists db-file))]
      (if (not (.exists db-folder)) (.mkdir db-folder))
      (def conn {:classname "org.sqlite.JDBC" :subprotocol "sqlite" :subname (.getAbsolutePath db-file)})
      (if create
        (execute! conn 
          [(str "CREATE VIRTUAL TABLE occurrences USING fts4(" (apply str (interpose " , " fields)) ")")]))))

(defn get-sources
  [] 
   (with-open [reader (io/reader (io/resource "sources.txt"))]
     (doall 
       (map 
         (fn [line]
           (-> line
               (.trim)
               (str "rss.do")))
         (line-seq reader)))))

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

(defn all-resources
  [] (flatten (map get-resources (get-sources))))

(defn estimate
  [resource] 
  (let [page (slurp (:link resource))
        match (re-find #"([1-9]+) ([KM])B" page)]
    (condp = (last match)
      "K" (* (Integer/valueOf (second match)) 1024)
      "M" (* (Integer/valueOf (second match)) 1024 1024)
      (second match))))

(defn fix 
  [occ] (-> occ fixes/fix-fields fixes/fix-id
          (dissoc :order :references :group)))

(defn bulk-insert
  [occs]
   (with-db-connection [c conn]
     (println "Got")
     (execute! c ["PRAGMA synchronous = OFF"])
     (query    c ["PRAGMA journal_mode = OFF"])
     (time
     (apply insert! c :occurrences (map fix occs))
     )
     ))

(defn -main [ & args ] 
   (connect)
   (let [batch (batcher 1024 0 bulk-insert)
         links (map :dwca (take 2 (all-resources)))]
     (doseq [link links]
       (do
         (println link)
         (dwca/read-archive-stream link
          (partial >!! batch))))
     (close! batch)))


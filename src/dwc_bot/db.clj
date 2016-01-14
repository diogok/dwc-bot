(ns dwc-bot.db
  (:require [dwc-io.validation :as valid]
            [dwc-io.fixes :as fixes])
  (:require [clojure.java.jdbc :refer [insert! delete! execute! query with-db-connection with-db-transaction]])
  (:require [clojure.java.io :as io])
  (:require [taoensso.timbre :as log])
  (:require [environ.core :refer (env)]))

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
  "Constuct the part of query for the sql IN clause"
  [f in]
  (str  " " (name f) " in (" (apply str (interpose "," (map #(if (string? %) (str "\"" % "\"") %) (map f in)))) ") "))

(defn in-f
  "Constuct the part of query for the sql IN clause in the form of a MATCH query"
  [f in]
  (str " " (name f) " MATCH '"
    (apply str
      (interpose " OR " (map f in)))
       "'"))

(defn connect
  "Connect (and create if needed) to the DB"
  ([] (connect (str (or (env :data-dir) "data") "/dwc.db")))
  ([db-path] 
    (let [db-file   (io/file db-path)
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
          )))))

(defn put-input
  "Insert an input URL in the DB"
  [url] 
   (let [url (if (.endsWith url "/") url (str url "/"))]
     (insert! conn :input {:url url})))

(defn rm-input
  "Remove a URL from the DB"
  [url]
  (delete! conn :input ["url=?" url])
  (delete! conn :input ["url=?" (str url "/")]))

(defn get-inputs
  "Get the input URLS from the DB"
  [] 
  (distinct
    (map
      (fn [url]
        (-> url 
            (.trim)
            (str "/rss.do")
            (.replace "//rss" "/rss")))
      (query conn ["SELECT url FROM input;"] :row-fn :url))))

(defn db-resources
  "List resources from DB"
  []
   (reduce
     (fn [recs rec]
       (assoc recs (:link rec) rec))
     {}
     (query conn ["SELECT * FROM resources"])))

(defn put-resources
  "Save resources into the db"
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

(defn find-hashes-0
  "Find the hashes for the occurrences"
  [c occs] 
   (query c [(str "SELECT hash FROM occurrences WHERE " (in-f :hash occs))] :row-fn :hash))

(defn find-hashes
  "Find the hashes for the occurrences"
  [c occs] 
   (set (flatten (map (partial find-hashes-0 c) (partition-all 10 occs)))))

(defn find-ids-0
  "Return identifiers for given occurrences"
  [c occs] 
   (query c [(str "SELECT identifier FROM occurrences WHERE " (in-f :identifier occs))] :row-fn :identifier))

(defn find-ids
  "Return a set of identifiers for given occurrences"
  [c occs]
  (set (flatten (map (partial find-ids-0 c) (partition-all 10 occs)))))

(defn search-all
  "Search using query string without limits"
 [q] 
  (query conn
     ["SELECT * FROM occurrences WHERE occurrences MATCH ?" q]
     :row-fn fixes/-fix->))

(defn search
  "Search using a query string, and possible start and limit"
 ([q] (search q 0)) 
 ([q start] (search q start 5000))
 ([q start limit] 
  (query conn
     ["SELECT * FROM occurrences WHERE occurrences MATCH ? LIMIT ? OFFSET ?" q limit start]
     :row-fn fixes/-fix->)))

(defn search-filtered
  "Search with filters (a hashmap of field=>value) and possible start and limit"
 ([filters] (search-filtered filters 0))
 ([filters start] (search-filtered filters 0 5000))
 ([filters start limit] 
  (query conn
    ["SELECT * FROM occurrences WHERE occurrences MATCH ? LIMIT ? OFFSET ?"
     (apply str (interpose " AND " (map #(str (key %) ":" (val %)) (into {} (filter second filters)))))
     limit start])
   :row-fn fixes/-fix->))


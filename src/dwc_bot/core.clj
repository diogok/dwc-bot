(ns dwc-bot.core
  (:require [dwc-bot.db :refer :all])
  (:require [clojure.java.jdbc :refer [insert! delete! execute! query with-db-connection with-db-transaction]])
  (:require [clojure.data.xml :as xml])
  (:require [dwc-io.archive :as dwca]
            [dwc-io.fixes :as fixes])
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c])
  (:require [batcher.core :refer :all])
  (:require [clojure.core.async :refer [<! <!! >! >!! go go-loop chan close!]])
  (:require [clojure.java.io :as io])
  (:require [environ.core :refer (env)])
  (:require [taoensso.timbre :as log]))

(def status (atom :idle))

(defn parse-date
  "Parse date from IPT RSS into a date object"
  [date]
  (f/parse (f/with-locale (f/formatters :rfc822) (java.util.Locale. "en")) date))

(defn date-to-timestamp
  "Format a date object as timestamp"
  [date]
   (String/valueOf (c/to-long (parse-date date))))

(defn now
  "Get current timestamp"
  [] (int (/ (System/currentTimeMillis) 1000)))

(defn get-tag-value
  "Extract the value of a tag inside an element"
  [el tag] 
   (first
     (:content
       (first
         (filter
           #(= (:tag %) tag)
           (:content el))))))

(defn item-to-resource
  "Transform an ipt rss item xml tag into a hashmap"
  [item] 
  {:title (get-tag-value item :title)
   :link  (get-tag-value item :link)
   :pub   (get-tag-value item :pubDate)
   :dwca  (str (get-tag-value item :dwca) 
               "&timestamp=" 
               (date-to-timestamp (get-tag-value item :pubDate)))})

(defn get-resources
  "Get all the resources of an IPT rss URL"
  [source] 
  (try
    (log/info "Will load source" source)
    (let [rss (xml/parse (io/reader source))]
      (->> rss
        (:content)
        (first)
        (:content)
        (filter #(= (:tag %) :item))
        (map item-to-resource)))
    (catch Exception e 
      (log/warn "Fail to load" source e))))

(defn all-resources
  "Get all resources of all inputs ipts"
  [] (flatten (map get-resources (get-inputs))))

(defn changed-resource?
  "Check if a resource is newer them the last time we crawled it"
  [on-db rec]
   (if-let [db-rec (on-db (:link rec))]
     (let [db-time (parse-date (:pub db-rec))
           rec-time (parse-date (:pub rec))]
       (t/after? rec-time db-time))
     true))

(defn changed-resources
  "Get all resources that have changed from all the inputs"
  [] 
   (let [on-db    (db-resources)
         all-recs (all-resources)]
     (filter (partial changed-resource? on-db) all-recs)))

(defn hashe
  "Assoc a hash value of an occurrence"
  [occ] 
   (assoc occ :hash (String/valueOf (hash occ))))

(defn metadata
  "Assoc metadata to the occurrence"
  [occ src pre-hash] 
  (assoc occ :identifier (hash (str src "#" (:occurrenceID occ)))
             :timestamp (now)
             :hash pre-hash
             :source src))

(defn fix
  "Apply common fixes before sending to DB, and assoc metadata, hash and ID"
  [src occ] 
  (let [pre-hash (:hash occ)]
    (-> occ
      (fixes/-fix->)
      (metadata src pre-hash)
      (dissoc :order)
      (dissoc :references)
      (dissoc :group))))

(defn bulk-insert
  "Insert/delete/update the bulk of occurrences"
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

(defn run
  "Run the crawler in a single resource(dwca)"
  [source rec]
   (log/info "->" source)
   (let [waiter (chan 1)
         batch  (batcher {:size (* 1 1024)
                          :fn (partial bulk-insert source)
                          :end waiter})]
     (try
       (do
         (dwca/read-archive-stream source
           (fn [occ] (>!! batch occ)))
         (put-resources [rec]))
       (catch Exception e
         (log/warn "Fail to read or process" source e)))
     (close! batch)
     (<!! waiter)))

(defn load-base-inputs-0
  "Load a config file list into a list"
  [file] 
  (with-open [rdr (io/reader file)]
    (doall (line-seq rdr))))

(defn load-base-inputs
  "Load default sources from various config file"
  [] 
  (let [etc   (io/file "/etc/biodiv/dwc-bot.list")
        env   (io/file (or (env :sources) "sources.list"))
        base  (io/file (io/resource "sources.list"))]
    (if (.exists env)
      (load-base-inputs-0 env)
      (if (.exists etc)
        (load-base-inputs-0 etc)
        (load-base-inputs-0 base)))))

(defn start 
  "Keep on running the bot on all sources. 
   Return an status atom that can swap to :stop to stop the bot."
  [ & args ] 
   (connect)
   (let [base-inputs (load-base-inputs)]
     (doseq [url base-inputs]
       (put-input url)))
   (future
     (while
       (and (not (nil? @status)) (not (= :stop @status)))
       (do
         (log/info "Bot Active")
         (swap! status (fn [_] :active))
         (let [recs  (changed-resources)]
           (log/info "Got" (count recs) "resources")
           (doseq [rec recs]
             (log/info "Resource" rec)
             (try
               (when (and (= :active @status) (dwca/occurrences? (:dwca rec)))
                 (run (:dwca rec) rec))
               (catch Exception e (log/warn "Exception runing" rec e)))))
         (swap! status (fn [_] :idle))
         (log/info "Will rest.")
         (Thread/sleep (* 30 60 1000)))))
     status)


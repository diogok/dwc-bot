(ns dwc-bot.web
  (:require [dwc-bot.core :as core])
  (:require [clojure.data.json :refer [write-str read-str]])
  (:require [environ.core :refer (env)])
  (:require [ring.adapter.jetty :refer (run-jetty)])
  (:gen-class))

(def server (atom nil))

(def app
  (fn [req] 
    nil
    ))

(defn start
  [join] 
  (let
    [host (or (env :host) "0.0.0.0")
     port (or (env :port)"8080")]
    (core/start)
    (println "Listening on" (str host ":" port))
    (swap! server
      (fn [_]
        (run-jetty #'app
          {:port (Integer/valueOf port) :host host :join? join})))
    @server))

(defn stop
  [] 
  (.stop @server)
  (swap! server (fn [_] nil)))

(defn -main
  [ & args] 
  (start true))


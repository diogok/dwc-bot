(ns dwc-bot.web
  (:require [dwc-bot.core :as core])
  (:require [clojure.data.json :refer [write-str read-str]])
  (:require [environ.core :refer (env)])
  (:require [ring.adapter.jetty :refer (run-jetty)])

  (:use ring.middleware.params
        ring.middleware.keyword-params
        ring.middleware.resource)

  (:gen-class))

(def routes
  {[:get "/"] 
     (fn [req]
       {:some "thing"})
   [:get "/search"]
    (fn [req] nil)
   [:get "/input"
    (fn[req] (core/get-inputs))]
   [:post "/input"
    (fn[req] nil)]
   [:get "/output"
    (fn [req] (core/get-outputs))]
   [:post "/output"
    (fn[req] nil)]
  })

(def handler
  (fn [req] 
    (let [uri    (:uri req)
          method (:request-method req)]
      (if-let [hand (get routes [method uri])]
        (hand req)
        {:status 404 :body {:error "not found"}}))))

(def server (atom nil))

(defn mid-json
  [handler]
  (fn [req]
    (let [res (handler req)]
      (if (not (nil? (:status res)))
        (assoc res :body (write-str (:body res))
                   :headers {"Content-Type" "application/json"})
        {:status 200 :headers {"Content-Type" "application/json"} :body (write-str res)}))))

(def app
  (-> handler
    (mid-json)
    (wrap-params)
    (wrap-keyword-params)
    (wrap-resource "public")))

(defn start
  [join] 
  (let
    [host (or (env :host) "0.0.0.0")
     port (or (env :port)"8080")
     opts {:port (Integer/valueOf port) :host host :join? join}]
    (future (core/start))
    (println "Listening on" (str host ":" port))
    (let [s (run-jetty #'app opts)]
      (swap! server (fn [_] s)))
    @server))

(defn stop
  [] 
  (.stop @server)
  (swap! server (fn [_] nil)))

(defn -main
  [ & args] 
  (start true))


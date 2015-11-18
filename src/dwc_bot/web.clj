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
       {:status 302 :headers {"Location" "/index.html"}})
   [:get "/search"]
    (fn [req] 
      (core/search (get (:query-params req) "q")))
   [:get "/input"
    (fn[req] (core/get-inputs))]
   [:post "/input"
    (fn[req] (core/put-input (:url (:body req))))]
   [:get "/output"
    (fn [req] (core/get-outputs))]
   [:post "/output"
    (fn[req] (core/put-output (:url (:body req))))]
  })

(def handler
  (fn [req] 
    (let [uri    (:uri req)
          method (:request-method req)]
      (if-let [hand (get routes [method uri])]
        (hand req)
        {:status 404 :body {:error "not found"}}))))

(def server (atom nil))
(def bot    (atom nil))

(defn mid-json
  [handler]
  (fn [req]
    (let [req (if (= "application/json" (:content-type req)) (assoc req :body (read-str (slurp (:body req)) :key-fn keyword)) req)
          res (handler req)]
      (if (not (nil? (:status res)))
        (if (:body res)
          (assoc res :body (write-str (:body res))
                     :headers {"Content-Type" "application/json"})
          res)
        {:status 200 :headers {"Content-Type" "application/json"} :body (write-str res)}))))

(def app
  (-> handler
    (mid-json)
    (wrap-params)
    (wrap-keyword-params)
    (wrap-resource "public")))

(defn start
  ([] (start false))
  ([join] 
  (let
    [host (or (env :host) "0.0.0.0")
     port (or (env :port) "8080")
     opts {:port (Integer/valueOf port) :host host :join? join}]
    (println "Listening on" (str host ":" port))
    (let [s (run-jetty #'app opts)
          b (core/start)]
      (swap! server (fn [_] s))
      (swap! bot (fn [_] b)))
    [@server @bot])))

(defn stop
  [] 
  (.stop @server)
  (swap! @bot (fn [_] :stop))
  (swap! server (fn [_] nil))
  (swap! bot (fn [_] nil)))

(defn -main
  [ & args] 
  (start true))


(ns dwc-bot.web
  (:require [dwc-bot.core :as core]
            [dwc-bot.db :as db])
  (:require [clojure.data.json :refer [write-str read-str]])
  (:require [environ.core :refer (env)])
  (:require [taoensso.timbre :as log])
  (:require [ring.adapter.jetty :refer (run-jetty)])

  (:use ring.middleware.params
        ring.middleware.keyword-params
        ring.middleware.resource
        ring.middleware.cors
        ring.middleware.reload)

  (:gen-class))

(defn result
  [data]
  {:result data
   :count (count data)
   :success true})

(def routes
  {[:get "/"] 
     (fn [req]
       {:status 302 :headers {"Location" "/index.html"}})
   [:get "/inputs"]
    (fn [req] (result (map #(.replace % "/rss.do" "") (db/get-inputs))))
   [:post "/inputs"]
    (fn [req]
      (db/put-input (:url (:body req)))
      {:status 201 :body (:url (:body req))})
   [:get "/resources"]
    (fn [req] (result (core/all-resources)))
   [:get "/fields"]
     (fn [req]
       (result db/fields))
   [:get "/search"]
    (fn [req] 
      (result (db/search (get (:query-params req) "q")
                           (Integer/valueOf
                             (or (get  (:query-params req) "start") "0"))
                           (Integer/valueOf
                             (or (get  (:query-params req) "limit") "5000")))))
   [:get "/search/filtered"]
    (fn [req] 
      (result (db/search-filtered 
                      (into {}
                        (filter #(not (some #{"start" "limit"} [(key %)]))
                        (:query-params req)))
                      (Integer/valueOf
                         (or (get  (:query-params req) "start") "0"))
                      (Integer/valueOf
                         (or (get  (:query-params req) "limit") "5000")))))
  })

(def handler
  (fn [req] 
    (let [uri    (:uri req)
          method (:request-method req)]
      (log/info "Request " method uri)
      (if-let [hand (get routes [method uri])]
        (try 
          (hand req)
          (catch Exception e 
            (do
              (log/error "Exception from " req e)
              (.printStackTrace e)
              {:status 500 :body (:error (.getMessage e))})))
        {:status 404 :body {:error "not found"}}))))

(def server (atom nil))
(def bot    (atom nil))

(defn maybe-json
  [req] 
  (if (= "application/json" (:content-type req))
    (assoc req :body (read-str (slurp (:body req)) :key-fn keyword))
    req))

(defn mid-json
  [handler]
  (fn [req]
    (let [req (maybe-json req)
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
    (wrap-resource "public")
    (wrap-cors :access-control-allow-origin [#".*"]
               :access-control-allow-methods [:get :put :post :delete])
    (wrap-reload)))

(defn start
  ([] (start false))
  ([join] 
  (let
    [host (or (env :host) "0.0.0.0")
     port (or (env :port) "8383")
     opts {:port (Integer/valueOf port) :host host :join? join}]
    (log/info "Listening on" (str host ":" port))
    (let [b (core/start)
          s (run-jetty #'app opts)]
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


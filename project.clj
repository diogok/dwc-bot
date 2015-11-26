(defproject dwc-bot "0.0.1"
  :description "Eat DarwinCore Archives"
  :url "http://github.com/diogok/dwc-bot"
  :license {:name "MIT"}
  :main dwc-bot.web
  :dependencies [[org.clojure/clojure "1.7.0"]

                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.xml "0.0.7"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/data.json "0.2.5"]

                 [org.clojure/java.jdbc "0.4.1"]
                 [org.xerial/sqlite-jdbc "3.8.11.1"]

                 [clj-time "0.11.0"]

                 [batcher "0.0.4"]
                 [dwc-io "0.0.50"]

                 [ring/ring-core "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [javax.servlet/servlet-api "2.5"]

                 [com.taoensso/timbre "4.1.4"]
                 [environ "1.0.0"]]
  :profiles {:uberjar {:aot :all}
             :jar {:aot :all}
             :dev {:dependencies [[midje "1.8.2"]]
                   :plugins [[lein-midje "3.2"]]}})

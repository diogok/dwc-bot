(defproject dwc-bot "0.0.4"
  :description "Eat DarwinCore Archives"
  :url "http://github.com/diogok/dwc-bot"
  :license {:name "MIT"}
  :main dwc-bot.web
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [org.clojure/core.async "0.2.385"]
                 [org.clojure/data.xml "0.0.7"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/data.json "0.2.6"]

                 [org.clojure/java.jdbc "0.6.1"]
                 [org.xerial/sqlite-jdbc "3.8.11.1"]

                 [clj-time "0.11.0"]

                 [batcher "0.1.1"]
                 [dwc-io "0.0.56"]

                 [ring/ring-core "1.5.0"]
                 [ring/ring-devel "1.5.0"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring-cors "0.1.7"]

                 [com.taoensso/timbre "4.3.1"]
                 [environ "1.0.2"]]
  :source-paths ["src"]
  :profiles {:uberjar {:aot [dwc-bot.web]}
             :dev {:dependencies [[midje "1.8.2"]]
                   :plugins [[lein-midje "3.2"]]}})

(ns dwc-bot.core-test
  (:require [midje.sweet  :refer :all]
            [dwc-bot.core :refer :all]))

(connect)

(fact "Working sources"
  (let [src "http://ipt.jbrj.gov.br/jbrj"]
    (put-source src)
    (get-sources) => (list (str src "/rss.do"))
    (rm-source src)
    (get-sources) => (empty (list nil))
    (put-source src)
    (let [rs (get-resources (first (get-sources)))
          r0 (first rs)]
      (:dwca r0) => "http://ipt.jbrj.gov.br/jbrj/archive.do?r=lista_especies_flora_brasil"
      (:title r0) => "Brazilian Flora Checklist - Lista de Espécies da Flora do Brasil - Version 393.38")))

(fact "Can index occurrences"
  (let [src "http://ipt.jbrj.gov.br/jbrj/archive.do?r=jbrj_w"]
    (time (run src))
    (count (time (search "jbrj_w"))) => 10121))


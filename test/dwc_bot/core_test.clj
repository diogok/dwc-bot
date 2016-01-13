(ns dwc-bot.core-test
  (:require [clojure.java.jdbc :refer :all])
  (:require [midje.sweet  :refer :all]
            [dwc-bot.core :refer :all]
            [dwc-bot.db   :refer :all]))

(connect "data-test/dwc.db") 

(execute! conn ["DELETE FROM input"])
(execute! conn ["DELETE FROM occurrences"])
(execute! conn ["DELETE FROM resources"])

(fact "Working sources"
  (let [src "http://ipt.jbrj.gov.br/jbrj"]
    (put-input src)
    (get-inputs) => (list (str src "/rss.do"))
    (rm-input src)
    (get-inputs) => (empty (list nil))
    (put-input src)
    (let [rs (get-resources (first (get-inputs)))
          r0 (first rs)]
      #_"Uhm..."
    )))

(fact "Can index occurrences"
  (let [src "http://ipt.jbrj.gov.br/jbrj/archive.do?r=jbrj_w"]
    (time (run src))
    (println "search")
    (count (time (search-all "jbrj_w"))) => 10121))

(fact "Index only changes"
  (let [occ0 {:occurrenceID "0"}
        occ1 {:occurrenceID "1"}
        occ2 {:occurrenceID "2"}]
    (bulk-insert "hello_test" [occ0 occ1])
    (map :occurrenceID (search-all "hello_test"))
     => (list "0" "1")
    (let [reocc0 (first (search-all "occurrenceID:0"))
          reocc1 (first (search-all "occurrenceID:1"))]
      (bulk-insert "hello_test" [occ0 occ1])
      (search "hello_test") => (list reocc0 reocc1)
      (bulk-insert "hello_test" [occ0 occ1 occ2])
      (search "hello_test") => (list reocc0 reocc1 occ2)
      (bulk-insert "hello_test" [occ0 occ1 (assoc occ2 :family "ACA")])
      (search "hello_test") => (list reocc0 reocc1 (assoc occ2 :family "ACA"))
      )))


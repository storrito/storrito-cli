(ns storrito.cli.upload-test
  (:require [clojure.test :refer [deftest is]]
            [storrito.cli.upload :as upload]))

(deftest put-timeout-grows-with-the-file
  (is (= (* 30 60 1000) (upload/put-timeout-ms 1))
      "30 minutes for a small file")
  (is (= (* 30 60 1000) (upload/put-timeout-ms (* 100 1024 1024)))
      "100 MB fit the 30 minutes at 500 KB/s")
  (is (= (* 1000 (quot (* 5 1024 1024 1024) 500000))
         (upload/put-timeout-ms (* 5 1024 1024 1024)))
      "a 5 GB temp-blob gets about 3 hours"))

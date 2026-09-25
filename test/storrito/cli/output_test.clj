(ns storrito.cli.output-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [storrito.cli.output :as output]))

(deftest errors-carry-exit-codes-and-hints
  (let [e (try
            (output/throw-error "Not logged in" {:exit :not-logged-in
                                                 :hint "Run `storrito login`."})
            (catch Exception e e))
        data (output/error-data e)]
    (is (output/error? e))
    (is (= 3 (output/exit-code data)))
    (is (= {:error "Not logged in"
            :exit :not-logged-in
            :retryable false
            :hint "Run `storrito login`."}
           data))))

(deftest unknown-exceptions-become-generic-errors
  (let [data (output/error-data (RuntimeException. "boom"))]
    (is (= "boom" (:error data)))
    (is (= 1 (output/exit-code data)))))

(deftest results-are-json
  (is (= {:a 1}
         (json/parse-string (output/json-str {:a 1} {:pretty false})
                            true)))
  (let [printed (with-out-str (output/print-result {:a [1 2]} {:pretty false}))]
    (is (= "{\"a\":[1,2]}\n" printed))))

(deftest exit-codes-are-stable
  (is (= {:ok 0 :error 1 :usage 2 :not-logged-in 3 :validation 4 :rate-limited 5 :network 6}
         output/exit-codes)))

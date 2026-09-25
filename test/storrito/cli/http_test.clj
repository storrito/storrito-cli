(ns storrito.cli.http-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [clojure.test :refer [deftest is]]
            [storrito.cli.fake :as fake]
            [storrito.cli.http :as http]
            [storrito.cli.output :as output]))

(defn flaky-handler
  "Fails `failures` times with 503, then answers 200."
  [failures]
  (let [remaining (atom failures)]
    (fn [_]
      (if (pos? @remaining)
        (do (swap! remaining dec)
            {:status 503 :body "deploying"})
        (fake/json-response 200 {:ok true})))))

(deftest retries-transient-statuses
  (fake/with-server [url (flaky-handler 2)]
    (let [sleeps (atom [])
          response (http/request {:method :get :url url}
                                 {:retries 5
                                  :sleep #(swap! sleeps conj %)})]
      (is (= 200 (:status response)))
      (is (= {:ok true} (http/parse-json-body response)))
      (is (= 2 (count @sleeps)))
      (is (every? #(<= 2000 % 2999) @sleeps)))))

(deftest gives-up-after-the-retries
  (fake/with-server [url (flaky-handler 10)]
    (let [response (http/request {:method :get :url url}
                                 {:retries 2
                                  :sleep (fn [_])})]
      (is (= 503 (:status response))))))

(deftest a-dead-host-is-a-network-error
  (let [e (try
            (http/request {:method :get :url "http://127.0.0.1:9/"}
                          {:retries 1
                           :sleep (fn [_])})
            (catch Exception e e))]
    (is (output/error? e))
    (is (= :network (:exit (output/error-data e))))
    (is (true? (:retryable (output/error-data e))))))

(deftest json-request-carries-the-token
  (let [request (http/json-request "https://x/api/v1/p" "tok" {:a 1})]
    (is (= "Bearer tok" (get-in request [:headers "Authorization"])))
    (is (= "{\"a\":1}" (:body request)))
    (is (= :post (:method request)))))

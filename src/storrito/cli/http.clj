(ns storrito.cli.http
  "HTTP requests with the retries the API documentation asks for: a 429
   (rate limit), 502, 503 and 504 (a deploy behind the load balancer)
   and connection errors are retried every 2 seconds plus 0-999 ms."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [storrito.cli.output :as output]))

(def retry-statuses
  #{429 502 503 504})

(def default-retries
  5)

(defn retry-delay-ms
  []
  (+ 2000 (rand-int 1000)))

(defn network-error
  [e url]
  (output/error (str "Could not reach " url ": " (ex-message e))
                {:exit :network
                 :retryable true
                 :hint "Check the network connection and retry."}))

(defn request-once
  "One attempt. Returns the response map, or `{:network-error error}`."
  [{:keys [url] :as request}]
  (try
    (http/request (assoc request :throw false))
    (catch Exception e
      {:network-error (network-error e url)})))

(defn request
  "Sends the `request` (a `babashka.http-client` request map), retrying
   `retries` times on transient failures. Returns the last response.
   Throws a CLI error when the network never answered."
  ([request]
   (request request {}))
  ([request {:keys [retries sleep] :or {retries default-retries
                                       sleep Thread/sleep}}]
   (loop [attempt 0
          response (request-once request)]
     (let [transient? (or (:network-error response)
                          (contains? retry-statuses (:status response)))]
       (if (and transient?
                (< attempt retries))
         (do (sleep (retry-delay-ms))
             (recur (inc attempt)
                    (request-once request)))
         (if-let [error (:network-error response)]
           (throw (ex-info (:error error)
                           (assoc error ::output/error true)))
           response))))))

(defn parse-json-body
  "The parsed JSON body of a response, nil when the body is not JSON."
  [{:keys [body]}]
  (when (and (string? body)
             (seq body))
    (try
      (json/parse-string body true)
      (catch Exception _
        nil))))

(defn json-request
  "A POST with a JSON `body` and a Bearer `token`."
  [url token body & [{:keys [timeout]}]]
  {:method :post
   :url url
   :headers (cond-> {"Content-Type" "application/json"
                     "Accept" "application/json"
                     "User-Agent" "storrito-cli"}
              token (assoc "Authorization" (str "Bearer " token)))
   :body (json/generate-string body)
   :timeout (or timeout 120000)})

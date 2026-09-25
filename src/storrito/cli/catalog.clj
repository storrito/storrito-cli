(ns storrito.cli.catalog
  "The API catalog (`/documentation/api/v1/catalog.json`) that the
   procedure commands are derived from, cached in the config directory
   and revalidated with its ETag at most once a day, or when asked
   (`--refresh`, an unknown command, `storrito login`)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [storrito.cli.config :as config]
            [storrito.cli.output :as output]))

(defn catalog-url
  []
  (or (config/env "STORRITO_CATALOG_URL")
      "https://storrito.com/documentation/api/v1/catalog.json"))

(def max-age-ms
  (* 24 60 60 1000))

(defn cache-file
  []
  (config/config-file "catalog.json"))

(defn etag-file
  []
  (config/config-file "catalog.etag"))

(defn cached
  "The cached catalog, or nil."
  []
  (config/read-json-file (cache-file)))

(defn cache-age-ms
  []
  (let [file (cache-file)]
    (if (fs/exists? file)
      (- (System/currentTimeMillis)
         (.toMillis (fs/last-modified-time file)))
      Long/MAX_VALUE)))

(defn store!
  [body etag]
  (config/write-private-file! (cache-file) body)
  (when etag
    (config/write-private-file! (etag-file) etag)))

(defn fetch!
  "Downloads the catalog, revalidating the cache with `If-None-Match`.
   Returns the catalog. Falls back to the cache when the network fails."
  []
  (let [etag (when (fs/exists? (etag-file))
               (str/trim (slurp (fs/file (etag-file)))))
        response (try
                   (http/request {:method :get
                                  :url (catalog-url)
                                  :headers (cond-> {"Accept" "application/json"
                                                    "User-Agent" "storrito-cli"}
                                             (and etag (cached)) (assoc "If-None-Match" etag))
                                  :timeout 30000
                                  :throw false})
                   (catch Exception e
                     {:network-error e}))]
    (cond
      (= 200 (:status response))
      (let [catalog (json/parse-string (:body response) true)]
        (store! (:body response)
                (get-in response [:headers "etag"]))
        catalog)

      (= 304 (:status response))
      (do (fs/set-last-modified-time (cache-file)
                                     (System/currentTimeMillis))
          (cached))

      (cached)
      (cached)

      (:network-error response)
      (output/throw-error (str "Could not download the API catalog from "
                               (catalog-url)
                               ": "
                               (ex-message (:network-error response)))
                          {:exit :network
                           :retryable true})

      :else
      (output/throw-error (str "Could not download the API catalog from "
                               (catalog-url)
                               ": HTTP "
                               (:status response))
                          {:exit :error
                           :status (:status response)
                           :retryable true}))))

(defn catalog
  "The catalog, from the cache when it is younger than a day."
  [& [{:keys [refresh]}]]
  (if (and (not refresh)
           (< (cache-age-ms) max-age-ms))
    (or (cached)
        (fetch!))
    (fetch!)))

(defn procedures
  [catalog]
  (:procedures catalog))

(defn find-procedure
  [catalog name]
  (first (filter #(= name (:name %))
                 (procedures catalog))))

(defn base-url
  "The API base URL of the organization, from the catalog's template.
   `$STORRITO_API_BASE` overrides the template for a development
   environment, e.g. `http://ORG_UUID.localhost:8080/api/v1/`."
  [catalog org-uuid]
  (str/replace (or (config/env "STORRITO_API_BASE")
                   (:baseUrlTemplate catalog)
                   "https://ORG_UUID.storrito.com/api/v1/")
               "ORG_UUID"
               org-uuid))

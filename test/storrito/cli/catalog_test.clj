(ns storrito.cli.catalog-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [storrito.cli.catalog :as catalog]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]))

(deftest the-catalog-is-cached-and-revalidated-with-its-etag
  (let [hits (atom 0)]
    (fake/with-server [url (fake/catalog-handler hits)]
      (fake/with-config-dir {"STORRITO_CATALOG_URL" url}
        (is (= 3 (count (catalog/procedures (catalog/catalog)))))
        (is (= 1 @hits))
        (is (fs/exists? (catalog/cache-file)))
        (is (= "\"catalog-v1\"" (slurp (fs/file (catalog/etag-file)))))
        (catalog/catalog)
        (is (= 1 @hits) "a young cache is used without a request")
        (catalog/catalog {:refresh true})
        (is (= 2 @hits) "a refresh revalidates")
        (fs/set-last-modified-time (catalog/cache-file)
                                   (- (System/currentTimeMillis) (* 2 catalog/max-age-ms)))
        (is (= "status-example"
               (:name (catalog/find-procedure (catalog/catalog) "status-example"))))
        (is (= 3 @hits) "an old cache is revalidated (304)")))))

(deftest the-cache-survives-an-unreachable-server
  (let [hits (atom 0)]
    (fake/with-server [url (fake/catalog-handler hits)]
      (fake/with-config-dir {"STORRITO_CATALOG_URL" url}
        (catalog/catalog)))
    ;; The server is gone now, the config dir too; a fresh config dir
    ;; with a dead URL and no cache is a network error:
    (fake/with-config-dir {"STORRITO_CATALOG_URL" "http://127.0.0.1:9/catalog.json"}
      (let [e (try (catalog/catalog) (catch Exception e e))]
        (is (= :network (:exit (output/error-data e))))))))

(deftest the-cache-is-used-when-the-server-is-gone
  (let [hits (atom 0)
        started (fake/start-server! (fake/catalog-handler hits))]
    (fake/with-config-dir {"STORRITO_CATALOG_URL" (:url started)}
      (catalog/catalog)
      (fake/stop-server! started)
      (is (= 3 (count (catalog/procedures (catalog/catalog {:refresh true}))))
          "the cache answers when the download fails"))))

(deftest the-base-url-comes-from-the-template
  (fake/with-config-dir {}
    (is (= (str "https://" fake/org-uuid ".storrito.com/api/v1/")
           (catalog/base-url fake/catalog fake/org-uuid)))
    (swap! config/env-overrides assoc "STORRITO_API_BASE" "http://ORG_UUID.localhost:8080/api/v1/")
    (is (= (str "http://" fake/org-uuid ".localhost:8080/api/v1/")
           (catalog/base-url fake/catalog fake/org-uuid)))))

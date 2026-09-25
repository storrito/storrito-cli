(ns storrito.cli.upgrade-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]
            [storrito.cli.upgrade :as upgrade]))

(defn downloads-handler
  "Serves latest.json, latest.txt and the executable for the running
   platform with the content `binary`; `bad-sha` corrupts the manifest."
  [{:keys [version binary bad-sha]}]
  (let [platform (upgrade/platform)
        file (str "storrito-" platform)
        digest (str/join (map #(format "%02x" %)
                              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                       (.getBytes ^String binary "UTF-8"))))]
    (fn [{:keys [uri] :as request}]
      (let [base (str "http://127.0.0.1:" (:server-port request))]
        (cond
          (= uri "/latest.json")
          (fake/json-response 200 {:version version
                                   :files {platform {:url (str base "/" version "/" file)
                                                     :sha256 (if bad-sha "00" digest)
                                                     :size (count (.getBytes ^String binary "UTF-8"))}}})

          (= uri "/latest.txt")
          {:status 200 :body (str version "\n")}

          (= uri (str "/" version "/" file))
          {:status 200 :body binary}

          :else
          {:status 404 :body "not found"})))))

(deftest versions-compare-numerically
  (is (upgrade/newer? "0.2.0" "0.1.9"))
  (is (upgrade/newer? "1.0.0" "0.10.0"))
  (is (upgrade/newer? "0.1.10" "0.1.9"))
  (is (not (upgrade/newer? "0.1.0" "0.1.0")))
  (is (not (upgrade/newer? "0.1.0" "0.2.0"))))

(deftest the-platform-is-known
  (is (contains? #{"linux-amd64" "linux-aarch64" "macos-amd64" "macos-aarch64" "windows-amd64"}
                 (upgrade/platform))))

(deftest running-from-source-has-no-executable
  (is (nil? (upgrade/executable-path))))

(deftest upgrade-replaces-the-executable-when-newer
  (fake/with-server [url (downloads-handler {:version "9.9.9" :binary "NEW BINARY"})]
    (fake/with-config-dir {"STORRITO_DOWNLOADS_URL" url}
      (let [exe (str (fs/path (config/ensure-config-dir!) "storrito"))]
        (spit exe "OLD BINARY")
        (let [result (upgrade/upgrade {:flags {} :exe-path exe})]
          (is (true? (:upgraded result)))
          (is (= "9.9.9" (:to result)))
          (is (= "NEW BINARY" (slurp exe)))
          (when-not (config/windows?)
            (is (str/starts-with? (fs/posix->str (fs/posix-file-permissions exe)) "rwx")))
          (is (not (fs/exists? (str exe ".new")))))))))

(deftest upgrade-is-a-no-op-when-current
  (fake/with-server [url (downloads-handler {:version "0.0.1" :binary "NEW BINARY"})]
    (fake/with-config-dir {"STORRITO_DOWNLOADS_URL" url}
      (let [exe (str (fs/path (config/ensure-config-dir!) "storrito"))]
        (spit exe "OLD BINARY")
        (let [result (upgrade/upgrade {:flags {} :exe-path exe})]
          (is (false? (:upgraded result)))
          (is (= "OLD BINARY" (slurp exe))))
        (let [result (upgrade/upgrade {:flags {"force" true} :exe-path exe})]
          (is (true? (:upgraded result)) "--force reinstalls")
          (is (= "NEW BINARY" (slurp exe))))))))

(deftest a-corrupt-download-leaves-the-executable-alone
  (fake/with-server [url (downloads-handler {:version "9.9.9" :binary "NEW BINARY" :bad-sha true})]
    (fake/with-config-dir {"STORRITO_DOWNLOADS_URL" url}
      (let [exe (str (fs/path (config/ensure-config-dir!) "storrito"))]
        (spit exe "OLD BINARY")
        (let [e (try (upgrade/upgrade {:flags {} :exe-path exe}) (catch Exception e e))]
          (is (str/includes? (:error (output/error-data e)) "Checksum mismatch"))
          (is (= "OLD BINARY" (slurp exe)))
          (is (not (fs/exists? (str exe ".new")))))))))

(deftest the-passive-check-remembers-the-latest-version-for-a-day
  (fake/with-server [url (downloads-handler {:version "9.9.9" :binary "x"})]
    (fake/with-config-dir {"STORRITO_DOWNLOADS_URL" url}
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (upgrade/check-for-update!))
        (is (str/includes? (str err) "9.9.9"))
        (is (= "9.9.9" (:latest (config/read-json-file (upgrade/check-file))))))
      ;; The server is irrelevant now, the file answers:
      (swap! config/env-overrides assoc "STORRITO_DOWNLOADS_URL" "http://127.0.0.1:9")
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (upgrade/check-for-update!))
        (is (str/includes? (str err) "9.9.9")))))
  (fake/with-config-dir {"STORRITO_DOWNLOADS_URL" "http://127.0.0.1:9"}
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (upgrade/check-for-update!))
      (is (= "" (str err)) "no network, no hint, no error"))))

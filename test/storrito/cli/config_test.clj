(ns storrito.cli.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]))

(deftest config-dir-honours-the-environment
  (fake/with-config-dir {}
    (is (= (config/env "STORRITO_CONFIG_DIR")
           (str (config/config-dir))))))

(deftest credentials-round-trip-with-private-permissions
  (fake/with-config-dir {}
    (let [credentials (-> (config/read-credentials)
                          (config/put-org-entry fake/org-uuid {:kind "api-credential" :token "id:secret"})
                          (config/put-org-entry fake/other-org-uuid {:kind "workos" :accessToken "t"}))]
      (config/write-credentials! credentials)
      (is (= fake/org-uuid (:defaultOrg (config/read-credentials)))
          "the first stored organization is the default")
      (is (= {:kind "api-credential" :token "id:secret"}
             (config/org-entry (config/read-credentials) fake/org-uuid)))
      (is (= [fake/org-uuid fake/other-org-uuid]
             (config/org-uuids (config/read-credentials))))
      (when-not (config/windows?)
        (is (= "rw-------"
               (fs/posix->str (fs/posix-file-permissions (config/credentials-file))))))
      (is (empty? (filter #(re-find #"\.tmp-" (str %))
                          (fs/list-dir (config/config-dir))))
          "no temp file left behind"))))

(deftest removing-the-default-org-picks-the-next-one
  (let [credentials (-> {:defaultOrg nil :orgs {}}
                        (config/put-org-entry fake/org-uuid {:kind "api-credential" :token "a"})
                        (config/put-org-entry fake/other-org-uuid {:kind "api-credential" :token "b"}))
        without-default (config/remove-org-entry credentials fake/org-uuid)
        empty (config/remove-org-entry without-default fake/other-org-uuid)]
    (is (= fake/other-org-uuid (:defaultOrg without-default)))
    (is (nil? (config/org-entry without-default fake/org-uuid)))
    (is (nil? (:defaultOrg empty)))))

(deftest missing-or-corrupt-credentials-read-as-empty
  (fake/with-config-dir {}
    (is (= {:defaultOrg nil :orgs {}} (config/read-credentials)))
    (config/ensure-config-dir!)
    (spit (fs/file (config/credentials-file)) "{not json")
    (is (= {:defaultOrg nil :orgs {}} (config/read-credentials)))))

(deftest the-lock-is-exclusive-and-stale-locks-are-removed
  (fake/with-config-dir {}
    (let [events (atom [])]
      (config/with-lock
        (swap! events conj :first)
        (is (thrown? clojure.lang.ExceptionInfo
                     (config/with-lock* (fn [] (swap! events conj :never))
                                        {:timeout-ms 200}))
            "a second lock times out while the first is held"))
      (config/with-lock
        (swap! events conj :second))
      (is (= [:first :second] @events))
      ;; A lock file left behind by a crashed process, older than the
      ;; stale limit:
      (config/ensure-config-dir!)
      (spit (fs/file (config/lock-file)) "")
      (fs/set-last-modified-time (config/lock-file)
                                 (- (System/currentTimeMillis) (* 2 config/lock-stale-ms)))
      (config/with-lock
        (swap! events conj :after-stale))
      (is (= [:first :second :after-stale] @events)))))

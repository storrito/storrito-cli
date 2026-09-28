(ns storrito.cli.auth-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [clojure.test :refer [deftest is]]
            [storrito.cli.auth :as auth]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]))

(defn exit-of
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (:exit (output/error-data e)))))

(deftest the-organization-comes-from-the-flag-the-env-or-the-default
  (fake/with-config-dir {}
    (is (= :not-logged-in (exit-of #(auth/resolve-org {} {:defaultOrg nil}))))
    (is (= fake/org-uuid (auth/resolve-org {} {:defaultOrg fake/org-uuid})))
    (is (= fake/other-org-uuid (auth/resolve-org {:org fake/other-org-uuid} {:defaultOrg fake/org-uuid})))
    (is (= :usage (exit-of #(auth/resolve-org {:org "acme"} {}))))
    (swap! config/env-overrides assoc "STORRITO_ORG" fake/other-org-uuid)
    (is (= fake/other-org-uuid (auth/resolve-org {} {:defaultOrg fake/org-uuid})))))

(deftest the-token-comes-from-the-env-or-the-stored-login
  (fake/with-config-dir {}
    (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                     fake/org-uuid
                                                     {:kind "api-credential" :token "id:secret"}))
    (is (= {:org-uuid fake/org-uuid :token "id:secret"}
           (auth/resolve-auth {})))
    (is (= :not-logged-in (exit-of #(auth/resolve-auth {:org fake/other-org-uuid}))))
    (swap! config/env-overrides assoc "STORRITO_TOKEN" "env-id:env-secret")
    (is (= "env-id:env-secret" (:token (auth/resolve-auth {:org fake/other-org-uuid})))
        "an env token needs no stored login")))

(deftest an-expiring-workos-login-is-refreshed-and-stored
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 0 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url}
        (let [fresh (fake/access-token)
              stale (fake/access-token {:exp (- (quot (System/currentTimeMillis) 1000) 5)})]
          (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                           fake/org-uuid
                                                           {:kind "workos"
                                                            :accessToken fresh
                                                            :refreshToken "refresh-1"
                                                            :workosOrgId "org_01TEST"
                                                            :expiresAt (+ (quot (System/currentTimeMillis) 1000) 3000)}))
          (is (= fresh (:token (auth/resolve-auth {})))
              "a fresh token is used as is")
          (is (empty? (:requests @state)))
          (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                           fake/org-uuid
                                                           {:kind "workos"
                                                            :accessToken stale
                                                            :refreshToken "refresh-1"
                                                            :workosOrgId "org_01TEST"
                                                            :expiresAt 1}))
          (let [{:keys [token]} (auth/resolve-auth {})
                entry (config/org-entry (config/read-credentials) fake/org-uuid)]
            (is (not= stale token))
            (is (= token (:accessToken entry)) "the new token is stored")
            (is (= "refresh-1-rotated" (:refreshToken entry)))
            (is (= "org_01TEST" (:workosOrgId entry)))
            (is (= fake/org-uuid (:orgUuid entry)))
            (is (= 1 (count (:requests @state))))))))))

(deftest an-ended-workos-session-asks-for-a-new-login
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 0 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url}
        (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                         fake/org-uuid
                                                         {:kind "workos"
                                                          :accessToken "old"
                                                          :refreshToken "expired"
                                                          :expiresAt 1}))
        (is (= :not-logged-in (exit-of #(auth/resolve-auth {}))))))))

(deftest parallel-invocations-refresh-once
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 0 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url}
        (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                         fake/org-uuid
                                                         {:kind "workos"
                                                          :accessToken "stale"
                                                          :refreshToken "refresh-1"
                                                          :workosOrgId "org_01TEST"
                                                          :expiresAt 1}))
        (let [tokens (->> (repeatedly 4 #(future (:token (auth/resolve-auth {}))))
                          (doall)
                          (mapv deref))]
          (is (= 1 (count (distinct tokens)))
              "every invocation ends up with the same fresh token")
          (is (not= "stale" (first tokens)))
          (is (= 1 (count (:requests @state)))
              "the lock serializes the refresh, the others re-read the file")
          (is (= "refresh-1-rotated"
                 (:refreshToken (config/org-entry (config/read-credentials) fake/org-uuid)))))))))

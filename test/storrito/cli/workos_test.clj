(ns storrito.cli.workos-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [clojure.test :refer [deftest is]]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]
            [storrito.cli.workos :as workos]))

(deftest device-flow-polls-until-the-login-completes
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 2 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url
                             "STORRITO_WORKOS_CLIENT_ID" "client_test"}
        (let [device (workos/authorize-device)
              sleeps (atom [])
              response (workos/poll-device-authorization device
                                                         {:sleep #(swap! sleeps conj %)})
              entry (workos/login-entry response)]
          (is (= "ABCD-EFGH" (:user_code device)))
          (is (= "refresh-1" (:refresh_token response)))
          (is (= [1000 1000] @sleeps) "two pending polls, the interval is 1 s")
          (is (= "workos" (:kind entry)))
          (is (= fake/org-uuid (:orgUuid entry)))
          (is (= "org_01TEST" (:workosOrgId entry)))
          (is (= "user@example.com" (:email entry)))
          (is (pos-int? (:expiresAt entry)))
          (is (= "client_test"
                 (get-in (first (:requests @state)) [:params :client_id]))))))))

(deftest device-flow-gives-up-when-the-code-expires
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 100 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url}
        (let [now (atom 0)
              e (try
                  (workos/poll-device-authorization {:device_code "d" :interval 1 :expires_in 3}
                                                    {:sleep (fn [ms] (swap! now + ms))
                                                     :now #(deref now)})
                  (catch Exception e e))]
          (is (= :not-logged-in (:exit (output/error-data e)))))))))

(deftest refresh-rotates-and-reports-an-ended-session
  (let [state (atom {})]
    (fake/with-server [url (fake/workos-handler {:pending 0 :state state})]
      (fake/with-config-dir {"STORRITO_WORKOS_API_BASE" url}
        (let [response (workos/refresh "refresh-1" "org_01TEST")]
          (is (= "refresh-1-rotated" (:refresh_token response)))
          (is (= "org_01TEST"
                 (get-in (last (:requests @state)) [:params :organization_id]))))
        (let [e (try (workos/refresh "expired") (catch Exception e e))]
          (is (= :not-logged-in (:exit (output/error-data e)))))))))

(deftest jwt-claims-are-decoded-without-verification
  (let [claims (workos/decode-jwt-claims (fake/access-token {:org fake/other-org-uuid :exp 42}))]
    (is (= fake/other-org-uuid (:organization:external_id claims)))
    (is (= 42 (:exp claims)))))

(ns storrito.cli.workos
  "The WorkOS AuthKit device flow (RFC 8628) and token refresh, as a
   public client: only the client id, no secret.

   https://workos.com/docs/authkit/cli-auth

   1. `authorize-device` asks for a device code and a user code.
   2. The user opens the verification URL, signs in and confirms the
      code (and picks the organization when in several).
   3. `poll-device-authorization` exchanges the device code for an
      access token (a JWT, 5 minutes) and a refresh token.
   4. `refresh` gets a new pair with the refresh token. Refresh tokens
      rotate; WorkOS keeps a 30 second replay grace, so parallel
      invocations converge on one result."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [storrito.cli.config :as config]
            [storrito.cli.output :as output]))

(def production-client-id
  "The WorkOS client id of the Storrito application. Public: every login
   redirect carries it."
  "client_01JW6BPNGEMFVHDNH2D6THARXP")

(defn client-id
  []
  (or (config/env "STORRITO_WORKOS_CLIENT_ID")
      production-client-id))

(defn api-base
  []
  (or (config/env "STORRITO_WORKOS_API_BASE")
      "https://api.workos.com"))

(defn form-body
  [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k)
                        "="
                        (java.net.URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn post-form
  "POSTs form parameters to a WorkOS user-management endpoint. Returns
   `{:status ... :body <parsed JSON>}`."
  [path params]
  (let [response (http/request {:method :post
                                :url (str (api-base) path)
                                :headers {"Content-Type" "application/x-www-form-urlencoded"
                                          "Accept" "application/json"
                                          "User-Agent" "storrito-cli"}
                                :body (form-body params)
                                :timeout 30000
                                :throw false})]
    {:status (:status response)
     :body (try
             (json/parse-string (:body response) true)
             (catch Exception _
               {:error "invalid_response"
                :error_description (str (:body response))}))}))

(defn authorize-device
  "Starts the device flow. Returns the WorkOS response: `device_code`,
   `user_code`, `verification_uri`, `verification_uri_complete`,
   `expires_in` and `interval`."
  []
  (let [{:keys [status body]} (post-form "/user_management/authorize/device"
                                         {:client_id (client-id)})]
    (if (= 200 status)
      body
      (output/throw-error (str "WorkOS did not start the device login: "
                               (or (:error_description body)
                                   (:error body)
                                   status))
                          {:exit :error
                           :status status
                           :details body}))))

(defn authenticate-request
  [params]
  (post-form "/user_management/authenticate"
             (assoc params :client_id (client-id))))

(defn poll-device-authorization
  "Polls until the user completed the login. Returns the authentication
   response (`access_token`, `refresh_token`, `user`, `organization_id`),
   or throws when the user denied or the code expired.

   `sleep` and `now` are injectable for the tests."
  [{:keys [device_code interval expires_in]}
   & [{:keys [sleep now on-pending]
       :or {sleep Thread/sleep
            now #(System/currentTimeMillis)
            on-pending (fn [_])}}]]
  (let [deadline (+ (now) (* 1000 (or expires_in 300)))]
    (loop [interval-s (or interval 5)]
      (let [{:keys [status body]} (authenticate-request
                                    {:grant_type "urn:ietf:params:oauth:grant-type:device_code"
                                     :device_code device_code})]
        (cond
          (= 200 status)
          body

          (= "authorization_pending" (:error body))
          (if (< (now) deadline)
            (do (on-pending interval-s)
                (sleep (* 1000 interval-s))
                (recur interval-s))
            (output/throw-error "The login code expired before the login was completed"
                                {:exit :not-logged-in
                                 :hint "Run `storrito login` again."}))

          (= "slow_down" (:error body))
          (do (sleep (* 1000 (+ interval-s 5)))
              (recur (+ interval-s 5)))

          (= "access_denied" (:error body))
          (output/throw-error "The login was denied in the browser"
                              {:exit :not-logged-in})

          (= "expired_token" (:error body))
          (output/throw-error "The login code expired"
                              {:exit :not-logged-in
                               :hint "Run `storrito login` again."})

          :else
          (output/throw-error (str "WorkOS rejected the device login: "
                                   (or (:error_description body)
                                       (:error body)
                                       status))
                              {:exit :error
                               :status status
                               :details body}))))))

(defn refresh
  "Exchanges the refresh token for a new access and refresh token, in
   the organization `organization-id` (a WorkOS `org_...` id) when
   given. Returns the authentication response, or throws
   `:not-logged-in` when the session ended (`invalid_grant`)."
  [refresh-token & [organization-id]]
  (let [{:keys [status body]} (authenticate-request
                                (cond-> {:grant_type "refresh_token"
                                         :refresh_token refresh-token}
                                  organization-id (assoc :organization_id organization-id)))]
    (cond
      (= 200 status)
      body

      (= "invalid_grant" (:error body))
      (output/throw-error "The login expired"
                          {:exit :not-logged-in
                           :hint "Run `storrito login` to sign in again."
                           :details body})

      :else
      (output/throw-error (str "WorkOS could not refresh the login: "
                               (or (:error_description body)
                                   (:error body)
                                   status))
                          {:exit :error
                           :status status
                           :retryable (contains? #{429 500 502 503 504} status)
                           :details body}))))

(defn decode-jwt-claims
  "The claims of a JWT, decoded without verification. The API verifies
   the token; the CLI only reads the organization and the expiry."
  [token]
  (let [[_ payload] (str/split token #"\." 3)]
    (json/parse-string
      (String. (.decode (java.util.Base64/getUrlDecoder)
                        ^String payload)
               "UTF-8")
      true)))

(defn login-entry
  "The `credentials.json` entry of an authentication response."
  [{:keys [access_token refresh_token user organization_id]}]
  (let [claims (decode-jwt-claims access_token)]
    {:kind "workos"
     :accessToken access_token
     :refreshToken refresh_token
     :expiresAt (:exp claims)
     :orgUuid (:organization:external_id claims)
     :workosOrgId (or organization_id
                      (:org_id claims))
     :email (or (:email user)
                (:user:email claims))
     :sessionId (:sid claims)}))

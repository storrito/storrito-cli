(ns storrito.cli.auth
  "Which organization a command talks to and with which token.

   The organization comes from `--org`, else `$STORRITO_ORG`, else the
   default organization in `credentials.json`. The token comes from
   `$STORRITO_TOKEN` (an API credential `id:secret`, for CI and agents
   in the cloud), else from the organization's login in
   `credentials.json`: an API credential stored by `storrito login
   --token`, or a WorkOS login whose access token is refreshed under the
   credentials lock when it expires within a minute."
  (:require [storrito.cli.config :as config]
            [storrito.cli.output :as output]
            [storrito.cli.workos :as workos]))

(def refresh-margin-seconds
  60)

(defn now-seconds
  []
  (quot (System/currentTimeMillis) 1000))

(defn uuid-string?
  [s]
  (boolean (and (string? s)
                (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                            s))))

(defn resolve-org
  "The UUID (a string) of the organization for this invocation, or
   throws `:not-logged-in`."
  [{:keys [org]} credentials]
  (let [org-uuid (or org
                     (config/env "STORRITO_ORG")
                     (:defaultOrg credentials))]
    (cond
      (nil? org-uuid)
      (output/throw-error "No organization: not logged in"
                          {:exit :not-logged-in
                           :hint "Run `storrito login`, or set STORRITO_TOKEN and STORRITO_ORG."})

      (not (uuid-string? org-uuid))
      (output/throw-error (str "Not an organization UUID: " org-uuid)
                          {:exit :usage
                           :hint "The organization UUID is the subdomain of your Storrito account, e.g. https://ORG_UUID.storrito.com"})

      :else
      org-uuid)))

(defn expires-soon?
  [{:keys [expiresAt]}]
  (or (nil? expiresAt)
      (< expiresAt
         (+ (now-seconds) refresh-margin-seconds))))

(defn refresh-entry
  "A new `credentials.json` entry from a refreshed WorkOS login, keeping
   the organization of the old entry."
  [entry response]
  (merge entry
         (workos/login-entry response)))

(defn refreshed-workos-token
  "The access token of the WorkOS login of `org-uuid`, refreshed and
   stored when it expires soon. Under the credentials lock, and the
   file is re-read inside the lock: a parallel invocation may have
   refreshed already."
  [org-uuid]
  (config/with-lock
    (let [credentials (config/read-credentials)
          entry (config/org-entry credentials org-uuid)]
      (if (expires-soon? entry)
        (let [response (workos/refresh (:refreshToken entry)
                                       (:workosOrgId entry))
              entry* (refresh-entry entry response)]
          (config/write-credentials! (config/put-org-entry credentials org-uuid entry*))
          (:accessToken entry*))
        (:accessToken entry)))))

(defn token-for-org
  "The Bearer token for `org-uuid`, or throws `:not-logged-in`."
  [org-uuid credentials]
  (or (config/env "STORRITO_TOKEN")
      (let [entry (config/org-entry credentials org-uuid)]
        (case (:kind entry)
          "api-credential" (:token entry)
          "workos" (if (expires-soon? entry)
                     (refreshed-workos-token org-uuid)
                     (:accessToken entry))
          (output/throw-error (str "Not logged in to organization " org-uuid)
                              {:exit :not-logged-in
                               :hint "Run `storrito login`, or set STORRITO_TOKEN."})))))

(defn resolve-auth
  "The organization and token of this invocation:
   `{:org-uuid ... :token ...}`."
  [opts]
  (let [credentials (config/read-credentials)
        org-uuid (resolve-org opts credentials)]
    {:org-uuid org-uuid
     :token (token-for-org org-uuid credentials)}))

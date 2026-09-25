(ns storrito.cli.login
  "The commands around the login: `login`, `logout`, `status` and
   `org`."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [storrito.cli.auth :as auth]
            [storrito.cli.catalog :as catalog]
            [storrito.cli.commands :as commands]
            [storrito.cli.config :as config]
            [storrito.cli.output :as output]
            [storrito.cli.workos :as workos]))

(defn say
  "Tells the human something on stderr, so that stdout stays JSON."
  [& parts]
  (binding [*out* *err*]
    (println (apply str parts))
    (flush)))

(defn open-browser!
  "Opens the URL in the default browser. Returns false when that did not
   work (a headless machine), the caller prints the URL anyway."
  [url]
  (try
    (let [command (cond
                    (config/windows?) ["rundll32" "url.dll,FileProtocolHandler" url]
                    (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac") ["open" url]
                    :else ["xdg-open" url])]
      (process/shell {:out :discard
                      :err :discard
                      :continue true}
                     (str/join " " (map #(str "\"" % "\"") command)))
      true)
    (catch Exception _
      false)))

(defn verify-login!
  "Calls `generate-uuid`, the cheapest procedure, to verify a token."
  [org-uuid token]
  (let [catalog (catalog/catalog)]
    (commands/invoke {:base-url (catalog/base-url catalog org-uuid)
                      :token token}
                     "generate-uuid"
                     {}
                     {:retries 1})))

(defn login-with-token
  "`storrito login --token id:secret --org <uuid>`: stores an API
   credential."
  [{:keys [token org]}]
  (let [org-uuid (auth/resolve-org {:org org} (config/read-credentials))]
    (verify-login! org-uuid token)
    (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                     org-uuid
                                                     {:kind "api-credential"
                                                      :token token}))
    {:loggedIn true
     :org org-uuid
     :kind "api-credential"
     :defaultOrg (:defaultOrg (config/read-credentials))}))

(defn login-with-device-flow
  "`storrito login`: the WorkOS device flow, see `storrito.cli.workos`."
  [{:keys [no-browser]}]
  (let [{:keys [user_code verification_uri verification_uri_complete] :as device} (workos/authorize-device)]
    (say "Open " verification_uri " and enter the code " user_code)
    (when-not no-browser
      (when (open-browser! verification_uri_complete)
        (say "Waiting for the login in the browser...")))
    (let [response (workos/poll-device-authorization device)
          entry (workos/login-entry response)
          org-uuid (:orgUuid entry)]
      (when-not org-uuid
        (output/throw-error "The login has no organization"
                            {:exit :not-logged-in
                             :hint "Sign up at https://storrito.com first, then run `storrito login` again."}))
      (config/with-lock
        (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                         org-uuid
                                                         entry)))
      (catalog/catalog {:refresh true})
      {:loggedIn true
       :org org-uuid
       :kind "workos"
       :email (:email entry)
       :defaultOrg (:defaultOrg (config/read-credentials))})))

(defn login
  [{:keys [token] :as opts}]
  (if token
    (login-with-token opts)
    (login-with-device-flow opts)))

(defn logout
  "`storrito logout [--org <uuid>] [--all]`: forgets the login(s)."
  [{:keys [org all]}]
  (config/with-lock
    (let [credentials (config/read-credentials)
          org-uuids (if all
                      (config/org-uuids credentials)
                      [(auth/resolve-org {:org org} credentials)])]
      (config/write-credentials! (reduce config/remove-org-entry
                                         credentials
                                         org-uuids))
      {:loggedOut org-uuids})))

(defn entry-status
  [org-uuid entry default-org]
  (cond-> {:org org-uuid
           :kind (:kind entry)
           :default (= org-uuid default-org)}
    (:email entry) (assoc :email (:email entry))
    (:expiresAt entry) (assoc :accessTokenExpiresAt (:expiresAt entry))))

(defn status
  "`storrito status`: the logins, no network."
  [_]
  (let [credentials (config/read-credentials)
        default-org (:defaultOrg credentials)]
    {:defaultOrg default-org
     :tokenFromEnv (boolean (config/env "STORRITO_TOKEN"))
     :orgFromEnv (config/env "STORRITO_ORG")
     :configDir (str (config/config-dir))
     :logins (mapv (fn [org-uuid]
                     (entry-status org-uuid
                                   (config/org-entry credentials org-uuid)
                                   default-org))
                   (config/org-uuids credentials))}))

(defn org
  "`storrito org list` and `storrito org default <uuid>`."
  [{:keys [positional]}]
  (let [[subcommand org-uuid] positional
        credentials (config/read-credentials)]
    (case subcommand
      "list" {:defaultOrg (:defaultOrg credentials)
              :orgs (config/org-uuids credentials)}
      "default" (do
                  (when-not (config/org-entry credentials (str org-uuid))
                    (output/throw-error (str "Not logged in to organization " org-uuid)
                                        {:exit :usage
                                         :hint "Run `storrito login` for that organization first."}))
                  (config/with-lock
                    (config/write-credentials! (assoc (config/read-credentials)
                                                      :defaultOrg org-uuid)))
                  {:defaultOrg org-uuid})
      (output/throw-error "Usage: storrito org list | storrito org default <uuid>"
                          {:exit :usage}))))

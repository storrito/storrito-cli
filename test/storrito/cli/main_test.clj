(ns storrito.cli.main-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.main :as main]))

(defn run
  "Runs the CLI and captures stdout, stderr and the exit code."
  [& args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit (binding [*out* out
                       *err* err]
               (main/run args))]
    {:exit exit
     :out (str out)
     :err (str err)
     :json (try (json/parse-string (str out) true) (catch Exception _ nil))
     :error (try (json/parse-string (str err) true) (catch Exception _ nil))}))

(defn with-api
  "Calls `f` with the atom of the API calls, in front of a fake API and
   catalog server, logged in via the environment (`STORRITO_TOKEN` and
   `STORRITO_ORG`)."
  [f]
  (let [calls (atom [])
        hits (atom 0)]
    (fake/with-server [api-url (fake/api-handler {:token "id:secret" :calls calls})]
      (fake/with-server [catalog-url (fake/catalog-handler hits)]
        (fake/with-config-dir {"STORRITO_CATALOG_URL" catalog-url
                               "STORRITO_API_BASE" (str api-url "/api/v1/")
                               "STORRITO_TOKEN" "id:secret"
                               "STORRITO_ORG" fake/org-uuid}
          (f calls))))))

(deftest version-and-help
  (fake/with-config-dir {"STORRITO_CATALOG_URL" "http://127.0.0.1:9/"}
    (let [{:keys [exit json]} (run "--version")]
      (is (= 0 exit))
      (is (= "0.3.0" (:version json))))
    (let [{:keys [exit out]} (run)]
      (is (= 0 exit))
      (is (str/includes? out "Commands:"))
      (is (str/includes? out "catalog is not available")))))

(deftest a-procedure-call-end-to-end
  (with-api
    (fn [calls]
      (let [{:keys [exit json]} (run "status-example" "--storyPostUuid" "abc" "--compact")]
        (is (= 0 exit))
        (is (= "scheduled" (:status json)))
        (is (= {:storyPostUuid "abc"} (:params (first @calls))))
        (is (= "Bearer id:secret" (:authorization (first @calls)))))
      (let [{:keys [exit error out]} (run "status-example" "--json" "{}")]
        (is (= 4 exit))
        (is (= "" out) "errors leave stdout empty")
        (is (= "validation" (:exit error)))
        (is (= 400 (:status error))))
      (let [{:keys [exit error]} (run "status-example")]
        (is (= 2 exit))
        (is (str/includes? (:error error) "--storyPostUuid")))
      (let [{:keys [exit out]} (run "status-example" "--help")]
        (is (= 0 exit))
        (is (str/includes? out "--storyPostUuid"))))))

(deftest unknown-commands-and-missing-logins
  (with-api
    (fn [_]
      (let [{:keys [exit error]} (run "nope")]
        (is (= 2 exit))
        (is (str/includes? (:error error) "Unknown command")))))
  (let [hits (atom 0)]
    (fake/with-server [catalog-url (fake/catalog-handler hits)]
      (fake/with-config-dir {"STORRITO_CATALOG_URL" catalog-url}
        (let [{:keys [exit error]} (run "generate-uuid")]
          (is (= 3 exit))
          (is (= "not-logged-in" (:exit error)))
          (is (str/includes? (:hint error) "storrito login"))))))
  (fake/with-config-dir {"STORRITO_CATALOG_URL" "http://127.0.0.1:9/"}
    (let [{:keys [exit error]} (run "generate-uuid")]
      (is (= 6 exit) "without a catalog the command cannot even be resolved")
      (is (= "network" (:exit error))))))

(deftest commands-listing-is-json-for-agents
  (with-api
    (fn [_]
      (let [{:keys [exit json]} (run "commands")]
        (is (= 0 exit))
        (is (true? (:catalogAvailable json)))
        (is (= #{"builtin" "procedure"} (set (map :kind (:commands json)))))
        (is (some #(= "status-example" (:name %)) (:commands json)))
        (is (= "Returns the status of a story post."
               (:description (first (filter #(= "status-example" (:name %)) (:commands json))))))))))

(deftest login-with-a-token-stores-it-after-a-check
  (let [calls (atom [])
        hits (atom 0)]
    (fake/with-server [api-url (fake/api-handler {:token "id:secret" :calls calls})]
      (fake/with-server [catalog-url (fake/catalog-handler hits)]
        (fake/with-config-dir {"STORRITO_CATALOG_URL" catalog-url
                               "STORRITO_API_BASE" (str api-url "/api/v1/")}
          (let [{:keys [exit error]} (run "login" "--token" "wrong:secret" "--org" fake/org-uuid)]
            (is (= 3 exit))
            (is (= "not-logged-in" (:exit error))))
          (let [{:keys [exit json]} (run "login" "--token" "id:secret" "--org" fake/org-uuid)]
            (is (= 0 exit))
            (is (true? (:loggedIn json)))
            (is (= fake/org-uuid (:defaultOrg json))))
          (is (= "generate-uuid" (:procedure (last @calls))))
          (let [{:keys [json]} (run "status")]
            (is (= [{:org fake/org-uuid :kind "api-credential" :default true}]
                   (:logins json))))
          (let [{:keys [exit json]} (run "generate-uuid")]
            (is (= 0 exit))
            (is (string? (:uuid json))))
          (let [{:keys [json]} (run "org" "list")]
            (is (= [fake/org-uuid] (:orgs json))))
          (let [{:keys [exit json]} (run "logout")]
            (is (= 0 exit))
            (is (= [fake/org-uuid] (:loggedOut json))))
          (is (empty? (config/org-uuids (config/read-credentials)))))))))

(deftest login-with-the-device-flow
  (let [state (atom {})
        hits (atom 0)]
    (fake/with-server [workos-url (fake/workos-handler {:pending 1 :state state})]
      (fake/with-server [catalog-url (fake/catalog-handler hits)]
        (fake/with-config-dir {"STORRITO_CATALOG_URL" catalog-url
                               "STORRITO_WORKOS_API_BASE" workos-url}
          (let [{:keys [exit json err]} (run "login" "--no-browser")]
            (is (= 0 exit))
            (is (str/includes? err "ABCD-EFGH") "the code is shown on stderr")
            (is (= fake/org-uuid (:org json)))
            (is (= "workos" (:kind json)))
            (is (= "user@example.com" (:email json))))
          (let [entry (config/org-entry (config/read-credentials) fake/org-uuid)]
            (is (= "refresh-1" (:refreshToken entry)))
            (is (= fake/org-uuid (:defaultOrg (config/read-credentials))))))))))

(deftest upload-creates-a-temp-blob-and-puts-the-file
  (let [puts (atom [])
        calls (atom [])
        hits (atom 0)]
    (fake/with-server [blob-url (fn [request]
                                  (swap! puts conj {:content-type (get-in request [:headers "content-type"])
                                                    :length (get-in request [:headers "content-length"])
                                                    :body (slurp (:body request))})
                                  {:status 200 :body ""})]
      (fake/with-server [api-url (fake/api-handler
                                   {:token "id:secret"
                                    :calls calls
                                    :responses {"create-temp-blob-upload-url"
                                                (fn [request]
                                                  (let [{:keys [tempBlobUuid]} (:params request)]
                                                    (fake/json-response 200 {:tempBlobUuid tempBlobUuid
                                                                             :url (str "https://blob/" tempBlobUuid)
                                                                             :uploadUrl (str blob-url "/put")})))}})]
        (fake/with-server [catalog-url (fake/catalog-handler hits)]
          (fake/with-config-dir {"STORRITO_CATALOG_URL" catalog-url
                                 "STORRITO_API_BASE" (str api-url "/api/v1/")
                                 "STORRITO_TOKEN" "id:secret"
                                 "STORRITO_ORG" fake/org-uuid}
            (let [file (str (config/config-dir) "/story.mp4")]
              (config/ensure-config-dir!)
              (spit file "fake video bytes")
              (let [{:keys [exit json]} (run "upload" file)]
                (is (= 0 exit))
                (is (true? (:uploaded json)))
                (is (= "video/mp4" (:contentType json)))
                (is (str/starts-with? (:url json) "https://blob/"))
                (is (= {:tempBlobUuid (:tempBlobUuid json)
                        :contentType "video/mp4"
                        :contentLengthBytes 16}
                       (:params (first @calls))))
                (is (= [{:content-type "video/mp4" :length "16" :body "fake video bytes"}]
                       @puts))))
            (let [{:keys [exit error]} (run "upload" "/nonexistent.mp4")]
              (is (= 2 exit))
              (is (str/includes? (:error error) "File not found")))))))))

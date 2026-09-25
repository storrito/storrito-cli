(ns storrito.cli.fake
  "Fake servers for the tests: the Storrito API, the catalog and WorkOS,
   on ephemeral ports, plus a temporary config directory."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as srv]
            [storrito.cli.config :as config]))

(defn json-response
  [status data]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

(defn read-json-body
  [request]
  (let [body (:body request)
        s (cond
            (nil? body) ""
            (string? body) body
            :else (slurp body))]
    (when (seq s)
      (json/parse-string s true))))

(defn parse-form-body
  [request]
  (let [body (slurp (:body request))]
    (into {}
          (for [pair (str/split body #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn start-server!
  "Starts `handler` on a free port. Returns `{:server ... :url ...}`."
  [handler]
  (let [server (srv/run-server handler {:port 0
                                        :legacy-return-value? false})]
    {:server server
     :url (str "http://127.0.0.1:" (srv/server-port server))}))

(defn stop-server!
  [{:keys [server]}]
  (srv/server-stop! server))

(defmacro with-server
  "Binds `sym` to the URL of a server running `handler`."
  [[sym handler] & body]
  `(let [started# (start-server! ~handler)
         ~sym (:url started#)]
     (try
       ~@body
       (finally
         (stop-server! started#)))))

(defmacro with-config-dir
  "Runs `body` with a fresh temporary config directory and the given
   environment overrides (a map of names to values)."
  [env & body]
  `(let [dir# (fs/create-temp-dir {:prefix "storrito-cli-test"})]
     (try
       (reset! config/env-overrides
               (merge {"STORRITO_CONFIG_DIR" (str dir#)}
                      ~env))
       ~@body
       (finally
         (reset! config/env-overrides {})
         (fs/delete-tree dir#)))))

(defn jwt
  "An unsigned JWT with `claims`, enough for the CLI which never
   verifies tokens."
  [claims]
  (let [encode (fn [data]
                 (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                                  (.getBytes (json/generate-string data) "UTF-8")))]
    (str (encode {:alg "RS256" :kid "test"})
         "."
         (encode claims)
         "."
         "signature")))

(def org-uuid
  "0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d")

(def other-org-uuid
  "7a1c2d3e-4f50-4617-8899-aabbccddeeff")

(defn access-token
  [& [{:keys [org exp email]}]]
  (jwt {:sub "user_01TEST"
        :sid "session_01TEST"
        :org_id "org_01TEST"
        :user:email (or email "user@example.com")
        :organization:external_id (or org org-uuid)
        :exp (or exp (+ (quot (System/currentTimeMillis) 1000) 300))}))

(def catalog
  {:catalogVersion 1
   :baseUrlTemplate "https://ORG_UUID.storrito.com/api/v1/"
   :documentationUrl "https://storrito.com/documentation/api/v1/index.md"
   :procedures [{:name "generate-uuid"
                 :doc "Generates a UUID.\n\n```\ncurl ...\n```"
                 :inputSchema {:type "object" :properties {} :additionalProperties false}
                 :exampleInput {}
                 :outputSchema {:type "object" :properties {:uuid {:type "string" :format "uuid"}}}}
                {:name "status-example"
                 :doc "Returns the status of a story post. Second sentence."
                 :inputSchema {:type "object"
                               :properties {:storyPostUuid {:type "string" :format "uuid"
                                                            :description "The UUID of the story post."}
                                            :verbose {:type "boolean"}
                                            :limit {:type "integer"}
                                            :ratio {:type "number"}
                                            :tags {:type "array" :items {:type "string"}}
                                            :options {:type "object"}}
                               :required ["storyPostUuid"]
                               :additionalProperties false}
                 :exampleInput {:storyPostUuid "EXAMPLE_STORY_POST_UUID"}}
                {:name "create-temp-blob-upload-url"
                 :doc "Creates an upload URL."
                 :inputSchema {:type "object"
                               :properties {:tempBlobUuid {:type "string" :format "uuid"}
                                            :contentType {:type "string"}
                                            :contentLengthBytes {:type "integer"}}
                               :required ["tempBlobUuid" "contentType" "contentLengthBytes"]}}]})

(defn catalog-handler
  "Serves the catalog with an ETag. Counts the requests in `hits`."
  [hits]
  (let [body (json/generate-string catalog)
        etag "\"catalog-v1\""]
    (fn [request]
      (swap! hits inc)
      (if (= etag (get-in request [:headers "if-none-match"]))
        {:status 304 :headers {"ETag" etag}}
        {:status 200
         :headers {"Content-Type" "application/json" "ETag" etag}
         :body body}))))

(defn api-handler
  "A fake API: requires the Bearer `token`, records the calls in
   `calls`, answers `generate-uuid` and `status-example`, plus whatever
   `responses` maps a procedure name to (a response map or a function
   of the request)."
  [{:keys [token calls responses]}]
  (fn [{:keys [uri headers] :as request}]
    (let [procedure (last (str/split uri #"/"))
          params (read-json-body request)]
      (swap! calls conj {:procedure procedure
                         :params params
                         :authorization (get headers "authorization")})
      (cond
        (not= (str "Bearer " token) (get headers "authorization"))
        {:status 401 :headers {"Content-Type" "text/plain"} :body "forbidden"}

        (contains? responses procedure)
        (let [response (get responses procedure)]
          (if (fn? response)
            (response (assoc request :params params))
            response))

        (= "generate-uuid" procedure)
        (json-response 200 {:uuid (str (random-uuid))})

        (= "status-example" procedure)
        (if (:storyPostUuid params)
          (json-response 200 {:status "scheduled" :params params})
          (json-response 400 {:errorMessage "invalid params for procedure: status-example"
                              :procedureName "status-example"
                              :validationErrorExplanation {:storyPostUuid ["missing required key"]}}))

        :else
        {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))))

(defn workos-handler
  "A fake WorkOS: the device flow needs `pending` polls before it
   succeeds; refresh rotates the token unless it is `expired`."
  [{:keys [pending state]}]
  (fn [{:keys [uri] :as request}]
    (let [params (parse-form-body request)]
      (swap! state update :requests (fnil conj []) {:uri uri :params params})
      (case uri
        "/user_management/authorize/device"
        (json-response 200 {:device_code "device-123"
                            :user_code "ABCD-EFGH"
                            :verification_uri "https://authkit.example/device"
                            :verification_uri_complete "https://authkit.example/device?user_code=ABCD-EFGH"
                            :expires_in 300
                            :interval 1})

        "/user_management/authenticate"
        (case (:grant_type params)
          "urn:ietf:params:oauth:grant-type:device_code"
          (if (pos? (get @state :pending pending))
            (do (swap! state update :pending (fnil dec pending))
                (json-response 400 {:error "authorization_pending"}))
            (json-response 200 {:access_token (access-token)
                                :refresh_token "refresh-1"
                                :organization_id "org_01TEST"
                                :user {:email "user@example.com"}}))

          "refresh_token"
          (if (= "expired" (:refresh_token params))
            (json-response 400 {:error "invalid_grant" :error_description "Session ended"})
            (json-response 200 {:access_token (access-token)
                                :refresh_token (str (:refresh_token params) "-rotated")
                                :organization_id "org_01TEST"
                                :user {:email "user@example.com"}}))

          (json-response 400 {:error "unsupported_grant_type"}))

        {:status 404 :body "not found"}))))

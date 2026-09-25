(ns storrito.cli.config
  "The config directory of the CLI and the files in it:

   - `credentials.json`: the logins per organization and the default
     organization, mode 0600. Shape:

         {\"defaultOrg\": \"<org uuid>\",
          \"orgs\": {\"<org uuid>\": {\"kind\": \"workos\", ...}
                   \"<org uuid>\": {\"kind\": \"api-credential\", ...}}}

   - `catalog.json` and `catalog.etag`: the cached API catalog.

   - `credentials.lock`: taken while a login is refreshed, so that
     parallel invocations (agents run commands concurrently) do not race
     on the rotating refresh token.

   The directory is `$STORRITO_CONFIG_DIR`, else `%APPDATA%\\storrito` on
   Windows, else `$XDG_CONFIG_HOME/storrito`, else `~/.config/storrito`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn windows?
  []
  (str/includes? (str/lower-case (System/getProperty "os.name" ""))
                 "win"))

(def env-overrides
  "Environment variables as the tests set them; the JVM cannot change
   its environment."
  (atom {}))

(defn env
  "The environment variable, nil when unset or blank."
  [name]
  (let [value (or (get @env-overrides name)
                  (System/getenv name))]
    (when-not (str/blank? value)
      value)))

(defn config-dir
  []
  (fs/path
    (or (env "STORRITO_CONFIG_DIR")
        (when (windows?)
          (some-> (env "APPDATA")
                  (fs/path "storrito")))
        (some-> (env "XDG_CONFIG_HOME")
                (fs/path "storrito"))
        (fs/path (fs/home) ".config" "storrito"))))

(defn ensure-config-dir!
  "Creates the config directory, readable only by the user where the
   file system supports it."
  []
  (let [dir (config-dir)]
    (when-not (fs/exists? dir)
      (fs/create-dirs dir)
      (try
        (fs/set-posix-file-permissions dir "rwx------")
        (catch Exception _
          ;; Windows has no POSIX permissions; the profile ACL applies.
          nil)))
    dir))

(defn config-file
  [name]
  (fs/path (config-dir) name))

(defn read-json-file
  "The parsed JSON of the file, nil when it does not exist or is not
   valid JSON (a truncated write is treated like a missing file)."
  [path]
  (when (fs/exists? path)
    (try
      (json/parse-string (slurp (fs/file path))
                         true)
      (catch Exception _
        nil))))

(defn write-private-file!
  "Writes `content` to `path` with mode 0600, via a temp file in the same
   directory and an atomic move, so that a concurrent reader never sees
   a half-written file."
  [path content]
  (ensure-config-dir!)
  (let [path (fs/path path)
        tmp (fs/path (fs/parent path)
                     (str (fs/file-name path) ".tmp-" (random-uuid)))]
    (spit (fs/file tmp) content)
    (try
      (fs/set-posix-file-permissions tmp "rw-------")
      (catch Exception _
        nil))
    (fs/move tmp path {:replace-existing true
                       :atomic-move true})
    path))

(defn write-json-file!
  [path data]
  (write-private-file! path
                       (json/generate-string data {:pretty true})))

(defn credentials-file
  []
  (config-file "credentials.json"))

(defn read-credentials
  []
  (or (read-json-file (credentials-file))
      {:defaultOrg nil
       :orgs {}}))

(defn write-credentials!
  [credentials]
  (write-json-file! (credentials-file)
                    credentials))

(defn org-entry
  "The login of the organization `org-uuid` (a string), or nil."
  [credentials org-uuid]
  (get-in credentials [:orgs (keyword org-uuid)]))

(defn put-org-entry
  "Stores the login of `org-uuid`. The first stored organization becomes
   the default."
  [credentials org-uuid entry]
  (-> credentials
      (assoc-in [:orgs (keyword org-uuid)] entry)
      (update :defaultOrg #(or % org-uuid))))

(defn remove-org-entry
  [credentials org-uuid]
  (let [credentials (update credentials :orgs dissoc (keyword org-uuid))
        remaining (keys (:orgs credentials))]
    (cond-> credentials
      (= org-uuid (:defaultOrg credentials))
      (assoc :defaultOrg (some-> (first remaining)
                                 (name))))))

(defn org-uuids
  [credentials]
  (mapv name
        (keys (:orgs credentials))))

(defn lock-file
  []
  (config-file "credentials.lock"))

(def lock-stale-ms
  "A lock older than this is left over from a crashed process."
  30000)

(defn with-lock*
  "Runs `f` while holding the credentials lock: an exclusively created
   lock file, retried for up to `timeout-ms`. Stale locks are removed."
  [f {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (ensure-config-dir!)
  (let [lock (fs/path (lock-file))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [acquired? (try
                        (java.nio.file.Files/createFile lock
                                                        (make-array java.nio.file.attribute.FileAttribute 0))
                        true
                        (catch java.nio.file.FileAlreadyExistsException _
                          false))]
        (cond
          acquired?
          (try
            (f)
            (finally
              (fs/delete-if-exists lock)))

          (> (- (System/currentTimeMillis)
                (.toMillis (fs/last-modified-time lock)))
             lock-stale-ms)
          (do (fs/delete-if-exists lock)
              (recur))

          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 50)
              (recur))

          :else
          (throw (ex-info "Could not lock the credentials file"
                          {:lock (str lock)})))))))

(defmacro with-lock
  [& body]
  `(with-lock* (fn [] ~@body) {}))

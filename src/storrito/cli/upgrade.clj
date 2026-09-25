(ns storrito.cli.upgrade
  "`storrito upgrade`: replaces the running executable with the latest
   release from `https://storrito.com/downloads/cli/`.

   `latest.json` names the version and, per platform, the URL, sha256
   and size of the executable. The new file is downloaded next to the
   running one, verified, made executable, then moved into place: an
   atomic rename on Unix, where the running
   process keeps its old inode; on Windows the running exe is renamed
   to `storrito.old.exe` first, which is deleted on the next start.

   Also the passive check: at most once a day, and only in a terminal,
   a one-line hint on stderr when a newer version exists. Never installs
   on its own."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [storrito.cli.config :as config]
            [storrito.cli.http :as http]
            [storrito.cli.output :as output]
            [storrito.cli.version :as version]))

(defn downloads-url
  []
  (or (config/env "STORRITO_DOWNLOADS_URL")
      "https://storrito.com/downloads/cli"))

(defn platform
  "The platform name of the running executable, e.g. `linux-amd64`, or
   nil when there is no build for it."
  []
  (let [os (str/lower-case (System/getProperty "os.name" ""))
        arch (str/lower-case (System/getProperty "os.arch" ""))
        os* (cond
              (str/includes? os "linux") "linux"
              (str/includes? os "mac") "macos"
              (str/includes? os "win") "windows")
        arch* (cond
                (contains? #{"amd64" "x86_64"} arch) "amd64"
                (contains? #{"aarch64" "arm64"} arch) "aarch64")]
    (when (and os* arch*)
      (if (= "windows" os*)
        "windows-amd64"
        (str os* "-" arch*)))))

(defn executable-path
  "The path of the running self-contained executable. babashka puts the
   appended jar, which is the executable itself, on the class path. Nil
   when running from source."
  []
  (let [class-path (System/getProperty "java.class.path" "")]
    (when (and (not (str/includes? class-path (System/getProperty "path.separator" ":")))
               (fs/regular-file? class-path))
      (str (fs/canonicalize class-path)))))

(defn version-vector
  [s]
  (mapv parse-long (re-seq #"\d+" (or s ""))))

(defn newer?
  "Whether version `a` is newer than version `b`."
  [a b]
  (pos? (compare (version-vector a)
                 (version-vector b))))

(defn fetch-manifest
  []
  (let [url (str (downloads-url) "/latest.json")
        response (http/request {:method :get
                                :url url
                                :headers {"Accept" "application/json"
                                          "User-Agent" "storrito-cli"}
                                :timeout 30000}
                               {:retries 2})]
    (if (= 200 (:status response))
      (http/parse-json-body response)
      (output/throw-error (str "Could not read " url ": HTTP " (:status response))
                          {:exit :network
                           :status (:status response)
                           :retryable true}))))

(defn sha256
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (fs/file path))]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [n (.read in buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (str/join (map #(format "%02x" %) (.digest digest)))))

(defn download-file!
  [url dest]
  (let [response (http/request {:method :get
                                :url url
                                :headers {"User-Agent" "storrito-cli"}
                                :as :stream
                                :timeout (* 10 60 1000)}
                               {:retries 2})]
    (when-not (= 200 (:status response))
      (output/throw-error (str "Download failed: HTTP " (:status response) " " url)
                          {:exit :network
                           :status (:status response)
                           :retryable true}))
    (with-open [in (:body response)]
      (io/copy in (fs/file dest)))))

(defn old-executable-path
  [exe-path]
  (str (fs/path (fs/parent exe-path)
                (str (fs/strip-ext (fs/file-name exe-path)) ".old.exe"))))

(defn cleanup-old-executable!
  "Deletes the `storrito.old.exe` a Windows upgrade left behind. Called
   at startup; ignores a file that is still locked."
  [exe-path]
  (when (config/windows?)
    (let [old (old-executable-path exe-path)]
      (when (fs/exists? old)
        (try
          (fs/delete old)
          (catch Exception _
            nil))))))

(defn install!
  "Moves the verified `new-file` over the executable at `exe-path`."
  [exe-path new-file]
  (if (config/windows?)
    (let [old (old-executable-path exe-path)]
      (when (fs/exists? old)
        (fs/delete old))
      (fs/move exe-path old)
      (fs/move new-file exe-path))
    (do
      (fs/set-posix-file-permissions new-file "rwxr-xr-x")
      (fs/move new-file exe-path {:replace-existing true
                                  :atomic-move true}))))

(defn upgrade
  "`storrito upgrade [--force]`. `exe-path` is injectable for the tests."
  [{:keys [flags exe-path]}]
  (let [exe-path (or exe-path
                     (executable-path)
                     (output/throw-error "Not running as an installed executable"
                                         {:exit :error
                                          :hint "Running from source? Use git instead. Installed with a package manager? Upgrade there."}))
        platform* (or (platform)
                      (output/throw-error (str "No build for this platform: "
                                               (System/getProperty "os.name") " "
                                               (System/getProperty "os.arch"))
                                          {:exit :error}))
        manifest (fetch-manifest)
        latest (:version manifest)
        current version/version]
    (if (and (not (newer? latest current))
             (not (get flags "force")))
      {:upgraded false
       :version current
       :latest latest}
      (let [{:keys [url size] expected-sha256 :sha256} (get-in manifest [:files (keyword platform*)])
            _ (when-not url
                (output/throw-error (str "The release " latest " has no build for " platform*)
                                    {:exit :error}))
            new-file (str (fs/path (fs/parent exe-path)
                                   (str (fs/file-name exe-path) ".new")))]
        (try
          (download-file! url new-file)
          (let [actual (sha256 new-file)]
            (when-not (= expected-sha256 actual)
              (output/throw-error (str "Checksum mismatch for " url)
                                  {:exit :error
                                   :retryable true
                                   :details {:expected expected-sha256 :actual actual}}))
            (when (and size (not= size (fs/size new-file)))
              (output/throw-error (str "Size mismatch for " url)
                                  {:exit :error
                                   :retryable true})))
          (install! exe-path new-file)
          (catch Exception e
            (fs/delete-if-exists new-file)
            (throw e)))
        {:upgraded true
         :from current
         :to latest
         :executable exe-path}))))

(def check-interval-ms
  (* 24 60 60 1000))

(defn check-file
  []
  (config/config-file "update-check.json"))

(defn latest-version-quickly
  "The latest version from `latest.txt`, nil when the network does not
   answer within two seconds. Never throws."
  []
  (try
    (let [response (http/request-once {:method :get
                                       :url (str (downloads-url) "/latest.txt")
                                       :headers {"User-Agent" "storrito-cli"}
                                       :timeout 2000})]
      (when (= 200 (:status response))
        (str/trim (:body response))))
    (catch Exception _
      nil)))

(defn check-for-update!
  "Prints the update hint on stderr when a newer version is known, and
   asks for the latest version at most once a day. Off with
   `STORRITO_NO_UPDATE_CHECK`."
  []
  (when-not (config/env "STORRITO_NO_UPDATE_CHECK")
    (let [now (System/currentTimeMillis)
          {:keys [checkedAt latest]} (config/read-json-file (check-file))
          latest (if (and checkedAt
                          (< (- now checkedAt) check-interval-ms))
                   latest
                   (let [fetched (latest-version-quickly)]
                     (when fetched
                       (config/write-json-file! (check-file)
                                                {:checkedAt now
                                                 :latest fetched}))
                     fetched))]
      (when (and latest
                 (newer? latest version/version))
        (binding [*out* *err*]
          (println (str "A new version of the Storrito CLI is available: "
                        latest " (you have " version/version "). Run: storrito upgrade"))
          (flush))))))

(ns storrito.cli.upload
  "`storrito upload <file>`: a local image, video or audio file as a
   temp-blob in one go, `create-temp-blob-upload-url` plus the PUT of
   the bytes. Prints the temp-blob's `url` to use inside the `html` of
   `schedule-instagram-story` and the other scheduling procedures."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [storrito.cli.commands :as commands]
            [storrito.cli.http :as http]
            [storrito.cli.output :as output]))

(def content-types
  {"jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "png" "image/png"
   "gif" "image/gif"
   "webp" "image/webp"
   "mp4" "video/mp4"
   "mov" "video/quicktime"
   "m4v" "video/mp4"
   "webm" "video/webm"
   "mp3" "audio/mpeg"
   "m4a" "audio/mp4"
   "aac" "audio/aac"
   "wav" "audio/wav"
   "ogg" "audio/ogg"})

(defn content-type
  [path given]
  (or given
      (get content-types
           (some-> (fs/extension path)
                   (str/lower-case)))
      (output/throw-error (str "Unknown file type: " path)
                          {:exit :usage
                           :hint "Pass --content-type, e.g. --content-type video/mp4"})))

(defn put-file!
  [upload-url path content-type]
  (let [response (http/request {:method :put
                                :url upload-url
                                :headers {"Content-Type" content-type}
                                :body (fs/file path)
                                :timeout (* 30 60 1000)}
                               {:retries 2})]
    (when-not (<= 200 (:status response) 299)
      (output/throw-error (str "The upload failed with HTTP " (:status response))
                          {:exit :error
                           :status (:status response)
                           :retryable true
                           :details (:body response)}))))

(defn upload
  [api {:keys [positional flags]}]
  (let [[path] positional]
    (when-not path
      (output/throw-error "Usage: storrito upload <file> [--content-type <type>] [--uuid <uuid>]"
                          {:exit :usage}))
    (when-not (fs/exists? path)
      (output/throw-error (str "File not found: " path)
                          {:exit :usage}))
    (let [content-type (content-type path (get flags "content-type"))
          temp-blob-uuid (or (get flags "uuid")
                             (str (random-uuid)))
          result (commands/invoke api
                                  "create-temp-blob-upload-url"
                                  {:tempBlobUuid temp-blob-uuid
                                   :contentType content-type
                                   :contentLengthBytes (fs/size path)})]
      (when-let [upload-url (:uploadUrl result)]
        (put-file! upload-url path content-type))
      {:tempBlobUuid (:tempBlobUuid result)
       :url (:url result)
       :contentType content-type
       :contentLengthBytes (fs/size path)
       :uploaded (boolean (:uploadUrl result))})))

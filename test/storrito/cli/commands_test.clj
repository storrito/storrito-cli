(ns storrito.cli.commands-test
  {:clj-kondo/config '{:lint-as {storrito.cli.fake/with-server clojure.core/let}}}
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storrito.cli.catalog :as catalog]
            [storrito.cli.commands :as commands]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]))

(def status-example
  (catalog/find-procedure fake/catalog "status-example"))

(defn exit-of
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (:exit (output/error-data e)))))

(deftest flags-are-parsed-in-every-spelling
  (is (= {:flags {"a" "1" "b" "2" "c" true "d" true}
          :positional ["cmd" "x"]}
         (commands/parse-flags ["cmd" "--a" "1" "--b=2" "--c" "--d" "x"]
                               {:boolean? #{"d"}})))
  (is (= {:flags {"a" true} :positional ["--not-a-flag"]}
         (commands/parse-flags ["--a" "--" "--not-a-flag"]))))

(deftest flag-specs-come-from-the-schema-required-first
  (let [specs (commands/flag-specs status-example)]
    (is (= ["storyPostUuid" "limit" "options" "ratio" "tags" "verbose"]
           (mapv :flag specs)))
    (is (true? (:required? (first specs))))
    (is (= "uuid" (:format (first specs))))
    (is (= "The UUID of the story post." (:description (first specs))))))

(deftest values-are-coerced-by-type
  (let [params (commands/params status-example
                                {"storyPostUuid" "0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d"
                                 "verbose" true
                                 "limit" "10"
                                 "ratio" "0.5"
                                 "tags" "[\"a\",\"b\"]"
                                 "options" "{\"x\":1}"
                                 "org" "ignored"})]
    (is (= {:storyPostUuid "0f0d6c2e-6a4b-4b1a-9d6c-8b7e2a5f1c3d"
            :verbose true
            :limit 10
            :ratio 0.5
            :tags ["a" "b"]
            :options {:x 1}}
           params)))
  (is (= :usage (exit-of #(commands/params status-example {"storyPostUuid" "x" "limit" "ten"}))))
  (is (= :usage (exit-of #(commands/params status-example {"storyPostUuid" "x" "tags" "not json"}))))
  (is (= :usage (exit-of #(commands/params status-example {"storyPostUuid" "x" "verbose" "maybe"})))))

(deftest at-values-read-files-and-json-gives-the-base-map
  (let [file (fs/create-temp-file {:suffix ".html"})]
    (try
      (spit (fs/file file) "<insta-story>hi</insta-story>")
      (is (= {:storyPostUuid "<insta-story>hi</insta-story>"}
             (commands/params status-example {"storyPostUuid" (str "@" file)})))
      (finally
        (fs/delete file))))
  (is (= {:storyPostUuid "from-json" :limit 3}
         (commands/params status-example {"json" "{\"storyPostUuid\":\"from-json\"}"
                                          "limit" "3"})))
  (is (= {:storyPostUuid "flag"}
         (commands/params status-example {"json" "{\"storyPostUuid\":\"json\"}"
                                          "storyPostUuid" "flag"}))
      "flags override the JSON map")
  (is (= {} (commands/params status-example {"json" "{}"}))
      "with --json the API validates the required keys")
  (is (= :usage (exit-of #(commands/params status-example {"json" "[1]"})))))

(deftest unknown-and-missing-flags-are-usage-errors
  (let [e (try (commands/params status-example {"storyPostUuid" "x" "typo" "1"}) (catch Exception e e))]
    (is (= :usage (:exit (output/error-data e))))
    (is (str/includes? (:error (output/error-data e)) "--typo")))
  (let [e (try (commands/params status-example {}) (catch Exception e e))]
    (is (= :usage (:exit (output/error-data e))))
    (is (str/includes? (:error (output/error-data e)) "--storyPostUuid"))))

(deftest help-text-lists-flags-and-an-example
  (let [help (commands/help-text status-example)]
    (is (str/starts-with? help "storrito status-example [flags]"))
    (is (str/includes? help "--storyPostUuid <string (uuid)>  (required)"))
    (is (str/includes? help "The UUID of the story post."))
    (is (str/includes? help "--verbose <boolean>"))
    (is (re-find #"--json '\{\"storyPostUuid\":\"[0-9a-f-]{36}\"\}'" help)
        "the example placeholder is replaced by a UUID")
    (is (not (str/includes? help "curl")))))

(deftest doc-helpers
  (is (= "Generates a UUID." (commands/strip-code-blocks (:doc (catalog/find-procedure fake/catalog "generate-uuid")))))
  (is (= "Returns the status of a story post." (commands/first-sentence (:doc status-example)))))

(deftest invoke-maps-responses-to-results-and-errors
  (let [calls (atom [])]
    (fake/with-server [url (fake/api-handler {:token "tok" :calls calls
                                              :responses {"rate-limited" {:status 429 :body "{\"errorMessage\":\"too many\"}"}
                                                          "broken" {:status 500 :body "oops"}}})]
      (let [api {:base-url (str url "/api/v1/") :token "tok"}
            fast {:retries 0}]
        (is (= {:status "scheduled" :params {:storyPostUuid "u"}}
               (commands/invoke api "status-example" {:storyPostUuid "u"} fast)))
        (is (= "Bearer tok" (:authorization (first @calls))))
        (let [e (try (commands/invoke api "status-example" {} fast) (catch Exception e e))
              data (output/error-data e)]
          (is (= :validation (:exit data)))
          (is (= 400 (:status data)))
          (is (= {:storyPostUuid ["missing required key"]}
                 (get-in data [:details :validationErrorExplanation]))))
        (is (= :not-logged-in (exit-of #(commands/invoke {:base-url (str url "/api/v1/") :token "wrong"} "generate-uuid" {} fast))))
        (is (= :usage (exit-of #(commands/invoke api "nope" {} fast))))
        (is (= :rate-limited (exit-of #(commands/invoke api "rate-limited" {} fast))))
        (let [e (try (commands/invoke api "broken" {} fast) (catch Exception e e))
              data (output/error-data e)]
          (is (= :error (:exit data)))
          (is (true? (:retryable data))))))))
